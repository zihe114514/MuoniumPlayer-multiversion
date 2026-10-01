package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 一首曲目的<b>整曲</b>时间包络，进阶模式（{@link AutomixSettings#isAdvancedModeEnabled()}）的原料。
 *
 * <p>它存在的唯一理由：{@link AutomixAutoCue} 要在<b>任意</b>两个位置之间找接缝——「从 A 的哪一帧开始退、
 * 从 B 的哪一帧开始进」。而 {@link FoliaTrackProfile} 只量了 A 的尾 30 秒和 B 的头 30 秒，
 * 因为旧规划器的启发式只需要那两段：点火点被硬压在 {@code [dur−12s, dur−overlap]} 里。
 * 要放开这个约束，就必须有一份覆盖全曲的东西。</p>
 *
 * <h2>为什么不干脆每次交接现扫</h2>
 *
 * <p>因为算不起，也来不及。一次整曲扫描要把 4 分钟素材过两遍双二阶滤波器并逐跳求 RMS，
 * 而它必须赶在<b>规划那一刻</b>之前落盘 —— 预备发生得很早（出场曲播到 10 秒就开始，见
 * {@code CloudMusic.PlayThread#maybeArmNextTrack}），规划紧跟在预备后面，绝不能压到渲染线程；
 * 更要命的是
 * {@link FfmpegAnalysisAudio} 每次缓存未命中都会<b>起一个 ffmpeg 进程</b>（上限 25 秒超时），
 * 单次 {@code readMono} 最多 60 秒窗口，整曲要开好几次进程。</p>
 *
 * <p>所以这里把结论落盘：包络在预热线程上扫一次（跟 profile 同一时机、同一线程预算），
 * 之后每次规划只是读几万字节的边车。{@link #load(File)} 是<b>只读</b>的，绝不触发扫描——
 * 规划路径拿不到包络时 {@link AutomixAutoCue} 直接弃权，退回原启发式，
 * 而不是当场去解码整首歌。</p>
 *
 * <h2>精度是刻意压低的</h2>
 *
 * <p>所有数组都量化成无符号字节（按 95 分位归一后取 0..255）。四个 20 ms 包络加一条 10 ms
 * 细包络，一首四分钟的歌约 72 KB；若存 float 是五倍，用户缓存几百首歌就会把 automix 的
 * 边车压成几十 MB，而打分根本不需要 24 bit 的动态范围——这些项全都只比相对大小。</p>
 *
 * <p>量化带来的最小非零值是 1/255 ≈ −48 dB，比可闻阈值低得多，静音段仍然精确落在 0。</p>
 */
final class AutomixEnvelope {

    /** 边车格式版本；改任何字段布局都要抬它，旧文件会被 {@link #read} 认出来并丢弃。 */
    static final int VERSION = 1;

    /** 粗包络的时间分辨率，与上游 AutoCue 同取 20 ms。 */
    static final int HOP_MILLIS = 20;
    /** 细包络只用来做形状相关（判「A 的尾巴和 B 的开头是不是同一段律动」），10 ms 足够。 */
    static final int FINE_HOP_MILLIS = 10;

    /** 单次 {@code readMono} 的请求长度上限，留 5 秒余量给两个 60 秒实现（WAV 与 ffmpeg）。 */
    private static final long CHUNK_MILLIS = 55_000L;
    /** 超过这个长度不再扫：五小时的现场录音不该为 automix 花四分钟扫包络。 */
    private static final long MAX_SCAN_MILLIS = 20 * 60_000L;

    /** 低频分界：底鼓与贝斯线的躯体，低音交接也用它。 */
    private static final double LOW_CROSSOVER_HZ = 200.0;
    /** 高频分界：镲、空气感与大部分咬字齿音。 */
    private static final double HIGH_CROSSOVER_HZ = 2_000.0;

    final long durationMillis;
    final int frameCount;
    final int fineCount;
    /** 归一化用的 95 分位全频 RMS，供需要绝对电平的调用方还原。 */
    final float levelPeak;

    // 包内可见：AutomixAutoCue 要按窗口整段取用，逐点走 rmsAt() 会把量化与索引除法重复做几万次。
    final byte[] rms;
    final byte[] low;
    final byte[] high;
    final byte[] onset;
    final byte[] fine;

    /**
     * 包内可见而不是 private：{@code AutomixAutoCueTest} 要用合成包络验证选点与弃权，
     * 而这条构造器<b>不做任何 IO</b>——它只是把已经量化好的数组装起来。
     * 真曲目的构造走 {@link #scan}。
     */
    AutomixEnvelope(long durationMillis, float levelPeak,
                    byte[] rms, byte[] low, byte[] high, byte[] onset, byte[] fine) {
        this.durationMillis = durationMillis;
        this.levelPeak = levelPeak;
        this.rms = rms;
        this.low = low;
        this.high = high;
        this.onset = onset;
        this.fine = fine;
        this.frameCount = rms.length;
        this.fineCount = fine.length;
    }

    boolean isUsable() {
        return durationMillis > 0L && frameCount > 0 && fineCount > 0
                && low.length == frameCount && high.length == frameCount && onset.length == frameCount;
    }

    // ---------------------------------------------------------------------------------------------
    // 取值：毫秒 → 量化帧，越界夹到端点，调用方不必二次判界。
    // ---------------------------------------------------------------------------------------------

    /** 全频 RMS，0..1（按 95 分位归一）。 */
    double rmsAt(long millis) {
        return sample(rms, frameFor(millis));
    }

    /** 200 Hz 以下能量占同一跳全频能量的比例，0..1。 */
    double lowShareAt(long millis) {
        return share(low, millis);
    }

    /** 2 kHz 以上能量占比，0..1。 */
    double highShareAt(long millis) {
        return share(high, millis);
    }

    /** 起声强度包络（三带上升量加权和减去局部均值），0..1。 */
    double onsetAt(long millis) {
        return sample(onset, frameFor(millis));
    }

    /** 10 ms 细包络的绝对幅值，0..1，供形状相关使用。 */
    double fineAt(long millis) {
        return sample(fine, fineFrameFor(millis));
    }

    /** 细包络一段连续区间的副本，用于相关计算；避免逐点做索引与量化还原。 */
    double[] fineRange(long startMillis, int count) {
        int from = fineFrameFor(startMillis);
        double[] out = new double[Math.max(0, Math.min(count, fineCount - from))];
        for (int i = 0; i < out.length; i++) out[i] = sample(fine, from + i);
        return out;
    }

    /** 粗包络一段连续区间的绝对幅值，0..1。 */
    double[] range(byte[] source, long startMillis, int count) {
        int from = frameFor(startMillis);
        double[] out = new double[Math.max(0, Math.min(count, frameCount - from))];
        for (int i = 0; i < out.length; i++) out[i] = sample(source, from + i);
        return out;
    }

    int framesIn(long millis) {
        return (int) Math.max(0L, Math.min((long) frameCount, millis / HOP_MILLIS));
    }

    private double share(byte[] band, long millis) {
        double full = sample(rms, frameFor(millis));
        if (full <= 1.0 / 255.0) return 0.0;
        return Math.max(0.0, Math.min(1.0, sample(band, frameFor(millis)) / full));
    }

    private int frameFor(long millis) {
        long index = millis / HOP_MILLIS;
        if (index < 0L) return 0;
        return (int) Math.min((long) frameCount - 1L, index);
    }

    private int fineFrameFor(long millis) {
        long index = millis / FINE_HOP_MILLIS;
        if (index < 0L) return 0;
        return (int) Math.min((long) fineCount - 1L, index);
    }

    private static double sample(byte[] values, int index) {
        if (values == null || values.length == 0) return 0.0;
        return (values[index] & 0xFF) / 255.0;
    }

    /** 窗口内全频包络的均值；区间不合法返回 -1，调用方据此判「无证据」。 */
    double meanLevel(long fromMillis, long toMillis) {
        if (toMillis <= fromMillis) return -1.0;
        double sum = 0.0;
        int count = 0;
        for (long millis = fromMillis; millis < toMillis; millis += HOP_MILLIS) {
            sum += rmsAt(millis);
            count++;
        }
        return count == 0 ? -1.0 : sum / count;
    }

    /**
     * 从 {@code fromMillis} 起这 {@code windowMillis} 秒里，<b>最后一个仍然越过窗口均值 55 %
     * 的位置</b>折算成窗口分数——也就是「这一段什么时候真的掉了」。
     *
     * <p>返回 0 表示给不出证据（窗口内几乎平、或素材不够）。调用方都要把 0 当作「没有结论」，
     * 而不是「第 0 帧就掉」：后者会让人声让路一进场就放开，两个主唱同时开口。</p>
     *
     * <p>这个式子只允许有一份。选点时的第 ⑧ 项打分和播放时的人声释放曲线都必须问这里，
     * 否则会出现「按一条曲线挑的接缝、按另一条曲线执行的混音」。</p>
     */
    double releaseFraction(long fromMillis, long windowMillis) {
        if (windowMillis <= 0L) return 0.0;
        int frames = (int) Math.max(1L, windowMillis / HOP_MILLIS);
        double[] level = range(rms, fromMillis, frames);
        if (level.length < 4) return 0.0;
        double body = 0.0;
        for (double value : level) body += value;
        body /= level.length;
        if (body <= 1e-4) return 0.0;
        double threshold = body * .55;
        int lastAbove = -1;
        for (int i = 0; i < level.length; i++) {
            if (level[i] > threshold) lastAbove = i;
        }
        if (lastAbove < 0) return 0.0;
        return lastAbove / (double) level.length;
    }

    // ---------------------------------------------------------------------------------------------
    // 边车读写
    // ---------------------------------------------------------------------------------------------

    private static final File DIR = new File(ConfigPaths.CACHE_DIR, "automix-cues");
    /**
     * 边车数量上限。一首四分钟的歌约 72 KB，600 份约 43 MB——再往上就该由淘汰来管，
     * 而不是假定用户永远不换列表。
     *
     * <p>与 {@code automix-profiles} 同一个位置约定（都在缓存目录下、都不属于音频缓存），
     * 所以「清空音频缓存」不会顺带删掉这些边车；淘汰只能靠这里自己做。</p>
     */
    private static final int MAX_FILES = 600;

    /**
     * 键与 {@link FoliaTrackProfiler#cacheKey(File)} 同族但<b>不含模型标记</b>：包络是纯 DSP 量，
     * 装不装 ONNX 都该是同一份东西。把模型编进键里会让「换个模型」白扫一遍整曲。
     */
    static String cacheKey(File source) {
        String value = source.getAbsolutePath() + '|' + source.length() + '|' + source.lastModified();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return Long.toUnsignedString(hash, 16);
    }

    /**
     * 只回答「这份边车在不在」，不解析内容。给调用方避免把一个已经扫好的文件再提交一次
     * 后台任务——入队本身不贵，贵的是它占住后台池的一个位置，把真交接的分析挤到后面。
     */
    static boolean isPresent(File source) {
        if (source == null) return false;
        try {
            return new File(DIR, cacheKey(source) + ".env").isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 只读。<b>永不扫描、永不联网、永不起进程。</b>读不到就是没有，调用方据此弃权。
     */
    static AutomixEnvelope load(File source) {
        if (source == null || !source.isFile()) return null;
        try {
            File file = new File(DIR, cacheKey(source) + ".env");
            if (!file.isFile()) return null;
            return read(file);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 扫一遍整曲并落盘。只允许在<b>非渲染帧的低优先级线程</b>上调用（{@link #scan} 会逐块解码，
     * 外部格式还要起 ffmpeg 进程）。已经扫过就直接回缓存，不重复解码。
     *
     * @return 磁盘上现在有没有这份可用的包络
     */
    static boolean ensure(File source, long durationMillis) {
        if (source == null || !source.isFile() || durationMillis <= 0L) return false;
        try {
            File file = new File(DIR, cacheKey(source) + ".env");
            if (file.isFile()) {
                AutomixEnvelope cached = read(file);
                if (cached != null && cached.isUsable() && cached.durationMillis == durationMillis) return true;
            }
            AutomixEnvelope scanned = scan(source, durationMillis);
            if (scanned == null || !scanned.isUsable()) return false;
            return write(file, scanned);
        } catch (Throwable failure) {
            System.err.println("[Automix/Envelope] 扫描失败 " + source.getName() + "：" + failure);
            return false;
        }
    }

    /** 读文件并校验版本与内部一致性；任何一条不符都当作「没有」。 */
    private static AutomixEnvelope read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file.toPath()))) {
            if (in.readInt() != VERSION) return null;
            long duration = in.readLong();
            float peak = in.readFloat();
            int count = in.readInt();
            int fine = in.readInt();
            if (duration <= 0L || count <= 0 || fine <= 0 || count > MAX_SCAN_MILLIS / HOP_MILLIS + 8
                    || fine > MAX_SCAN_MILLIS / FINE_HOP_MILLIS + 8) return null;
            byte[] rms = new byte[count];
            byte[] low = new byte[count];
            byte[] high = new byte[count];
            byte[] onset = new byte[count];
            byte[] fineBand = new byte[fine];
            in.readFully(rms);
            in.readFully(low);
            in.readFully(high);
            in.readFully(onset);
            in.readFully(fineBand);
            AutomixEnvelope envelope = new AutomixEnvelope(duration, peak, rms, low, high, onset, fineBand);
            return envelope.isUsable() ? envelope : null;
        }
    }

    private static boolean write(File file, AutomixEnvelope envelope) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return false;
            File temp = new File(parent, file.getName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp.toPath()))) {
                out.writeInt(VERSION);
                out.writeLong(envelope.durationMillis);
                out.writeFloat(envelope.levelPeak);
                out.writeInt(envelope.frameCount);
                out.writeInt(envelope.fineCount);
                out.write(envelope.rms);
                out.write(envelope.low);
                out.write(envelope.high);
                out.write(envelope.onset);
                out.write(envelope.fine);
            }
            if (!temp.renameTo(file) && !temp.delete()) return false;
            prune();
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * 按最后修改时间淘汰最旧的边车，把数量压回 {@link #MAX_FILES}。
     *
     * <p>只在真的写盘之后跑，不在读路径上跑——读路径每首歌都扫一遍目录是不划算的代价。
     * 被淘汰的歌下次再被扫到就重扫一遍（预热线程上，用户等不到），所以「删多了」的代价
     * 是一次重扫，而不是任何听感问题。</p>
     */
    private static void prune() {
        File[] files = DIR.listFiles();
        if (files == null || files.length <= MAX_FILES) return;
        java.util.Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        for (int i = MAX_FILES; i < files.length; i++) {
            if (!files[i].delete()) return;   // 正被读的文件删不动：留着下次再清，别硬来
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 扫描
    // ---------------------------------------------------------------------------------------------

    /**
     * 逐块解码整曲，一次过算出全频/低频/高频 RMS 与起声强度，外加一条 10 ms 的细包络。
     *
     * <p>分频用 RBJ 双二阶（Q=1/√2）而不是 FFT：整曲几百万采样点跑 FFT 只为拿两个带的能量
     * 是浪费，而带的<b>边界</b>在这里不重要、带的<b>时间对齐</b>才重要——带通后逐跳求均方，
     * 滤波器状态跨块延续，所以块与块之间没有接缝。</p>
     *
     * <p>单遍（非零相位）滤波会让低频包络晚几毫秒。这不影响选点：点火与切入的时刻取自
     * {@code rms} 与节拍网格，两者都不经过滤波器；带通只喂「频段互补」这一项打分，
     * 而那是一项比较两首歌谁更闷的相对量。</p>
     */
    private static AutomixEnvelope scan(File source, long durationMillis) {
        if (durationMillis > MAX_SCAN_MILLIS) durationMillis = MAX_SCAN_MILLIS;
        AnalysisAudio audio = AnalysisAudioSource.open(source);
        if (audio == null) return null;
        try {
            int rate = audio.sampleRate();
            if (rate <= 0) return null;
            int hop = Math.max(1, Math.round(rate * HOP_MILLIS / 1000f));
            int fineHop = Math.max(1, Math.round(rate * FINE_HOP_MILLIS / 1000f));
            int frames = (int) Math.max(1L, (durationMillis + HOP_MILLIS - 1L) / HOP_MILLIS);
            int fineFrames = (int) Math.max(1L, (durationMillis + FINE_HOP_MILLIS - 1L) / FINE_HOP_MILLIS);

            float[] rms = new float[frames];
            float[] low = new float[frames];
            float[] high = new float[frames];
            float[] fine = new float[fineFrames];

            Biquad lowPass = new Biquad(true, rate, LOW_CROSSOVER_HZ);
            Biquad highPass = new Biquad(false, rate, HIGH_CROSSOVER_HZ);

            long cursor = 0L;          // 已消费的采样数，用于把块内位置换算成全局帧号
            for (long start = 0L; start < durationMillis; start += CHUNK_MILLIS) {
                long length = Math.min(CHUNK_MILLIS, durationMillis - start);
                float[] chunk = audio.readMono(start, length);
                if (chunk == null || chunk.length == 0) break;   // 读不到就到此为止，剩下的帧留 0
                int frame = (int) (cursor / hop);
                int fineFrame = (int) (cursor / fineHop);
                double full = 0.0, lo = 0.0, hi = 0.0, fineSum = 0.0;
                int seen = 0, fineSeen = 0;
                for (float sample : chunk) {
                    double l = lowPass.process(sample);
                    double h = highPass.process(sample);
                    full += (double) sample * sample;
                    lo += l * l;
                    hi += h * h;
                    fineSum += (double) sample * sample;
                    if (++seen >= hop) {
                        if (frame < frames) rms[frame] = (float) Math.sqrt(full / seen);
                        if (frame < frames) low[frame] = (float) Math.sqrt(lo / seen);
                        if (frame < frames) high[frame] = (float) Math.sqrt(hi / seen);
                        frame++;
                        full = lo = hi = 0.0;
                        seen = 0;
                    }
                    if (++fineSeen >= fineHop) {
                        if (fineFrame < fineFrames) fine[fineFrame] = (float) Math.sqrt(fineSum / fineSeen);
                        fineFrame++;
                        fineSum = 0.0;
                        fineSeen = 0;
                    }
                    cursor++;
                }
            }
            if (cursor < rate / 2L) return null;   // 只读到不到半秒：当作没读到，别写一份假的边车

            float peak = percentile(rms, 0.95);
            if (!(peak > 0f)) return null;

            float[] onset = onsetEnvelope(rms, low, high);
            return new AutomixEnvelope(durationMillis, peak,
                    quantize(rms, peak), quantize(low, peak), quantize(high, peak),
                    quantize(onset, Math.max(1e-6f, percentile(onset, 0.95))), quantize(fine, peak));
        } finally {
            audio.close();
        }
    }

    /**
     * 起声强度 = 三带上升量的加权和减去局部均值。系数取自同族实现里经过盲听定下的一组
     * （低频上升最像「进了一段新的东西」，高频最像「咬字/镲」），减均值是为了让持续变响
     * 的段落不至于每帧都算起声。
     */
    private static float[] onsetEnvelope(float[] rms, float[] low, float[] high) {
        int n = rms.length;
        float[] raw = new float[n];
        for (int i = 1; i < n; i++) {
            raw[i] = Math.max(0f, .8f * (rms[i] - rms[i - 1])
                    + 1.25f * (low[i] - low[i - 1])
                    + 1.05f * (high[i] - high[i - 1]));
        }
        float[] out = new float[n];
        int window = Math.max(1, 100 / HOP_MILLIS);   // 约 2 秒的局部均值
        double running = 0.0;
        for (int i = 0; i < n; i++) {
            running += raw[i];
            if (i >= window) running -= raw[i - window];
            double mean = running / Math.min(i + 1, window);
            out[i] = Math.max(0f, (float) (raw[i] - .55 * mean));
        }
        return out;
    }

    private static byte[] quantize(float[] values, float peak) {
        byte[] out = new byte[values.length];
        if (!(peak > 0f)) return out;
        for (int i = 0; i < values.length; i++) {
            double scaled = values[i] / peak * 255.0;
            out[i] = (byte) Math.max(0, Math.min(255, Math.round(Double.isFinite(scaled) ? scaled : 0.0)));
        }
        return out;
    }

    /** 与 {@code AutomixAnalyzer.percentile} 同义，但那是 private 且只认 float[]，这里不想动它。 */
    private static float percentile(float[] values, double fraction) {
        if (values == null || values.length == 0) return 0f;
        float[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int index = (int) Math.max(0L, Math.min((long) copy.length - 1L,
                Math.round(fraction * (copy.length - 1))));
        return copy[index];
    }

    /**
     * RBJ  cookbook 双二阶，Direct Form I，Q=1/√2（Butterworth）。
     * {@code lowpass=true} 取 200 Hz 以下，否则取 2 kHz 以上。状态在两次调用之间保留，
     * 因此分块喂数据不会在块边界留下接缝。
     */
    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        Biquad(boolean lowpass, int sampleRate, double cornerHz) {
            double nyquist = sampleRate * 0.5;
            double corner = Math.max(10.0, Math.min(nyquist - 10.0, cornerHz));
            double w0 = 2.0 * Math.PI * corner / sampleRate;
            double sin = Math.sin(w0), cos = Math.cos(w0);
            double alpha = sin / (2.0 * 0.7071067811865476);
            double a0 = 1.0 + alpha;
            if (lowpass) {
                this.b0 = (1.0 - cos) / 2.0 / a0;
                this.b1 = (1.0 - cos) / a0;
                this.b2 = (1.0 - cos) / 2.0 / a0;
            } else {
                this.b0 = (1.0 + cos) / 2.0 / a0;
                this.b1 = -(1.0 + cos) / a0;
                this.b2 = (1.0 + cos) / 2.0 / a0;
            }
            this.a1 = -2.0 * cos / a0;
            this.a2 = (1.0 - alpha) / a0;
        }

        double process(double input) {
            double output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;
            return output;
        }
    }
}
