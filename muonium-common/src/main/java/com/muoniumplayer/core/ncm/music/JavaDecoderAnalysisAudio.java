package com.muoniumplayer.core.ncm.music;

import repackage.javazoom.jl.decoder.Bitstream;
import repackage.javazoom.jl.decoder.Decoder;
import repackage.javazoom.jl.decoder.Header;
import repackage.javazoom.jl.decoder.SampleBuffer;
import repackage.org.kc7bfi.jflac.FLACDecoder;
import repackage.org.kc7bfi.jflac.FrameListener;
import repackage.org.kc7bfi.jflac.PCMProcessor;
import repackage.org.kc7bfi.jflac.io.RandomFileInputStream;
import repackage.org.kc7bfi.jflac.metadata.SeekPoint;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * 纯 Java 解码的 mp3 / flac 分析窗口读取器——没有 ffmpeg 时的分析主力。
 *
 * <h2>为什么要有它</h2>
 * <p>{@link PcmWindowReader} 只认 WAV，mp3 / flac 一直由 {@link FfmpegAnalysisAudio}
 * 交给外部 ffmpeg 解码。桌面装没装 ffmpeg 是用户自己的事，但手机（FCL / Zalith 这类
 * OpenJDK-on-Android 环境）里没有也装不上 ffmpeg：安卓 10 起应用私有目录禁 exec、
 * 外置存储又是 noexec，可执行文件这条路整体不通。于是手机上 mp3 / flac 缓存的分析窗口
 * 全部拿不到，{@link FoliaTrackProfiler#analyse} 在第一行就静默退出，模型推理永远没跑过。</p>
 *
 * <p>mod 里本来就打包了播放链在用的两个纯 Java 解码器：JLayer（{@code repackage.javazoom.jl}，
 * MP3）与 jFLAC（{@code repackage.org.kc7bfi}，FLAC）。播放能放的格式恰好就是
 * mp3 / flac / wav（AAC 在播放前就被 {@link AacAudioDecoder} 解成 WAV 边车），
 * 所以这两个解码器 + 现成的 WAV 直读就覆盖了全部分析来源，不需要新增任何依赖。</p>
 *
 * <h2>窗口怎么取</h2>
 * <ul>
 *   <li><b>MP3</b>：先扫一遍帧头建「字节偏移 ↔ 采样位置」索引（只读不解码），
 *       之后每个窗口从最近的帧边界起解、丢掉前导、收满即停。VBR 也成立，因为
 *       每一帧的长度都由它自己的头算出来。</li>
 *   <li><b>FLAC</b>：用 jFLAC 自带的 {@code seek(sample)}（内部按 seektable 或字节估算
 *       定位）跳转，再从该点顺序解到窗口末尾。跳转失败（返回 null）时退回从头解码。</li>
 * </ul>
 *
 * <p>采样率不做重采样：窗口按文件原生采样率交付，调用方本来就带着 {@link #sampleRate()}
 * 处理（{@link FoliaTrackProfiler} 对模型输入自行重采样到 22.05k）。这样既省一层
 * 插值误差，也让「帧号 ↔ 毫秒」的换算与 {@link PcmWindowReader} 保持同一个式子。</p>
 *
 * <p>所有失败路径都返回空窗口：分析永远是加分项，读不到就退回等功率淡化。</p>
 */
final class JavaDecoderAnalysisAudio implements AnalysisAudio {

    /** 与两个既有实现对齐的单窗口上限。 */
    private static final long MAX_WINDOW_MILLIS = 60_000L;
    /** 缓存上限：约 55 秒原生采样率单声道，与 ffmpeg 路径同量级。 */
    private static final int MAX_CACHE_FRAMES = 55 * 48_000;
    private static final float[] EMPTY = new float[0];

    private final File file;
    private final String container;
    private final Source source;

    /** 最近一次解码结果，以及它在文件里的起点毫秒。{@code -1} 表示还没有缓存。 */
    private float[] cache;
    private long cacheStartMillis = -1L;

    private JavaDecoderAnalysisAudio(File file, String container, Source source) {
        this.file = file;
        this.container = container;
        this.source = source;
    }

    /**
     * 打开一个 mp3 / flac 分析源；不是这两种容器（或解码器打不开）时返回 {@code null}，
     * 由调用方继续往下找（ffmpeg 或放弃分析）。
     */
    static JavaDecoderAnalysisAudio open(File source) {
        if (source == null || !source.isFile() || source.length() < 1_024L) return null;
        String container = AudioContainerSupport.detectContainer(source);
        try {
            if ("mp3".equals(container)) {
                Mp3Source mp3 = Mp3Source.open(source);
                if (mp3 != null) return new JavaDecoderAnalysisAudio(source, container, mp3);
            } else if ("flac".equals(container)) {
                FlacSource flac = FlacSource.open(source);
                if (flac != null) return new JavaDecoderAnalysisAudio(source, container, flac);
            }
        } catch (Throwable failure) {
            System.err.println("[Automix/Decode] 纯 Java 解码器打不开 " + source.getName()
                    + "（" + container + "）: " + failure);
        }
        return null;
    }

    @Override
    public int sampleRate() {
        return source.sampleRate();
    }

    @Override
    public float[] readMono(long startMillis, long lengthMillis) {
        if (lengthMillis <= 0L) return EMPTY;
        long start = Math.max(0L, startMillis);
        long capped = Math.min(lengthMillis, MAX_WINDOW_MILLIS);

        float[] hit = fromCache(start, capped);
        if (hit != null) return hit;

        float[] decoded = EMPTY;
        try {
            long startSample = start * source.sampleRate() / 1000L;
            long want = capped * source.sampleRate() / 1000L;
            if (want > 0L && want <= Integer.MAX_VALUE - 8) {
                decoded = source.window(startSample, (int) want);
            }
        } catch (Throwable failure) {
            System.err.println("[Automix/Decode] " + container + " 窗口解码失败 "
                    + file.getName() + ": " + failure);
        }
        if (decoded.length > 0 && decoded.length <= MAX_CACHE_FRAMES) {
            this.cache = decoded;
            this.cacheStartMillis = start;
        }
        return decoded;
    }

    /**
     * 从缓存切一段。只有请求被完整覆盖时才算命中——短于请求的答案可能是「文件到头了」，
     * 也可能是「缓存不够长」，混在一起会让调用方对着半截窗口做结构判断（与 ffmpeg 路径同规则）。
     */
    private float[] fromCache(long startMillis, long lengthMillis) {
        float[] cached = this.cache;
        if (cached == null || this.cacheStartMillis < 0L) return null;
        long offset = startMillis - this.cacheStartMillis;
        if (offset < 0L) return null;
        long from = offset * source.sampleRate() / 1000L;
        long want = lengthMillis * source.sampleRate() / 1000L;
        if (want <= 0L || from >= cached.length) return null;
        if (from + want > cached.length) return null;
        float[] slice = new float[(int) want];
        System.arraycopy(cached, (int) from, slice, 0, (int) want);
        return slice;
    }

    @Override
    public void close() {
        this.cache = null;
        this.cacheStartMillis = -1L;
        try {
            source.close();
        } catch (Throwable ignored) {
        }
    }

    /** 一种容器的窗口解码策略。实现只管按采样位置取单声道窗口。 */
    private interface Source {
        int sampleRate();

        float[] window(long startSample, int want);

        void close();
    }

    // ------------------------------------------------------------------
    // MP3：帧索引 + JLayer
    // ------------------------------------------------------------------

    /** MPEG 帧头解析结果。字段都来自帧头本身，VBR / 混合码率都按帧各自成立。 */
    private static final class MpegFrame {
        final int frameLength;
        final int sampleRate;
        final int samplesPerFrame;

        MpegFrame(int frameLength, int sampleRate, int samplesPerFrame) {
            this.frameLength = frameLength;
            this.sampleRate = sampleRate;
            this.samplesPerFrame = samplesPerFrame;
        }
    }

    private static final class Mp3Source implements Source {

        private static final int MAX_FRAMES = 1_000_000;   // 约 2.5 小时 44.1k，超出视为异常文件

        private final File file;
        private final int sampleRate;
        private final long[] frameOffsets;
        private final long[] frameSamples;
        private final int frameCount;

        private Mp3Source(File file, int sampleRate,
                          long[] frameOffsets, long[] frameSamples, int frameCount) {
            this.file = file;
            this.sampleRate = sampleRate;
            this.frameOffsets = frameOffsets;
            this.frameSamples = frameSamples;
            this.frameCount = frameCount;
        }

        static Mp3Source open(File file) throws IOException {
            long[] offsets = new long[1024];
            long[] samples = new long[1024];
            int count = 0;
            int rate = 0;
            long cursor = 0L;

            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 RandomCursor cursorReader = new RandomCursor(raf)) {
                long length = raf.length();
                long position = firstFrameOffset(cursorReader, length);
                while (position + 4L <= length && count < MAX_FRAMES) {
                    int b0 = cursorReader.byteAt(position);
                    if (b0 < 0 || b0 != 0xFF) { position++; continue; }
                    int b1 = cursorReader.byteAt(position + 1);
                    if (b1 < 0 || (b1 & 0xE0) != 0xE0) { position++; continue; }
                    MpegFrame frame = parseFrame(cursorReader, position);
                    if (frame == null || position + frame.frameLength > length) { position++; continue; }
                    // 链式校验：帧尾必须紧跟另一个合法帧头，否则这个同步字只是数据里的巧合。
                    long next = position + frame.frameLength;
                    if (next + 4L <= length && !plausibleFrame(cursorReader, next)) { position++; continue; }

                    if (count == offsets.length) {
                        offsets = Arrays.copyOf(offsets, count * 2);
                        samples = Arrays.copyOf(samples, count * 2);
                    }
                    offsets[count] = position;
                    samples[count] = cursor;
                    count++;
                    cursor += frame.samplesPerFrame;
                    if (rate <= 0) {
                        rate = frame.sampleRate;
                    }
                    position = next;
                }
            }
            if (count <= 0 || rate <= 0) return null;
            return new Mp3Source(file, rate, offsets, samples, count);
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        /** 跳过 ID3v2（含 footer）得到第一个可能的帧头位置；没有标签就是 0。 */
        private static long firstFrameOffset(RandomCursor reader, long length) throws IOException {
            if (length < 10L) return 0L;
            int b0 = reader.byteAt(0), b1 = reader.byteAt(1), b2 = reader.byteAt(2);
            if (b0 != 'I' || b1 != 'D' || b2 != '3') return 0L;
            int b5 = reader.byteAt(5);
            long size = ((long) (reader.byteAt(6) & 0x7F) << 21)
                    | ((long) (reader.byteAt(7) & 0x7F) << 14)
                    | ((long) (reader.byteAt(8) & 0x7F) << 7)
                    | (long) (reader.byteAt(9) & 0x7F);
            long offset = 10L + size;
            if ((b5 & 0x10) != 0) offset += 10L;   // footer 存在
            return offset < length ? offset : length;
        }

        private static boolean plausibleFrame(RandomCursor reader, long position) throws IOException {
            if (reader.byteAt(position) != 0xFF) return false;
            int b1 = reader.byteAt(position + 1);
            if (b1 < 0 || (b1 & 0xE0) != 0xE0) return false;
            return parseFrame(reader, position) != null;
        }

        /**
         * 解析 4 字节帧头并算出帧长。MPEG 音频帧头格式是公开固定的；
         * 这里独立实现而不是借 JLayer 的 Header，是因为索引阶段不能把整段交进解码器。
         */
        private static MpegFrame parseFrame(RandomCursor reader, long position) throws IOException {
            int b0 = reader.byteAt(position), b1 = reader.byteAt(position + 1);
            int b2 = reader.byteAt(position + 2);
            if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return null;

            int versionBits = (b1 >>> 3) & 0x03;      // 3=MPEG1, 2=MPEG2, 0=MPEG2.5, 1=保留
            int layerBits = (b1 >>> 1) & 0x03;        // 3=I, 2=II, 1=III, 0=保留
            int bitrateIndex = (b2 >>> 4) & 0x0F;     // 0=free, 15=非法
            int rateIndex = (b2 >>> 2) & 0x03;        // 3=非法
            int padding = (b2 >>> 1) & 0x01;
            if (versionBits == 1 || layerBits == 0 || bitrateIndex == 0
                    || bitrateIndex == 15 || rateIndex == 3) return null;

            int layer = 4 - layerBits;                // 1..3
            int sampleRate;
            if (versionBits == 3) {
                sampleRate = MPEG1_RATES[rateIndex];
            } else if (versionBits == 2) {
                sampleRate = MPEG2_RATES[rateIndex];
            } else {
                sampleRate = MPEG25_RATES[rateIndex];
            }
            // 码率表按索引 1..14 排布（0 = free、15 = 非法，上面已挡掉），所以查表要减 1。
            int bitrate = (versionBits == 3 ? MPEG1_BITRATES : MPEG2_BITRATES)[layer - 1][bitrateIndex - 1] * 1000;
            int samplesPerFrame;
            if (layer == 1) {
                samplesPerFrame = 384;
            } else if (layer == 2) {
                samplesPerFrame = 1152;
            } else {
                samplesPerFrame = versionBits == 3 ? 1152 : 576;
            }
            int frameLength;
            if (layer == 1) {
                frameLength = ((12 * bitrate / sampleRate) + padding) * 4;
            } else if (layer == 3 && versionBits != 3) {
                frameLength = 72 * bitrate / sampleRate + padding;
            } else {
                frameLength = 144 * bitrate / sampleRate + padding;
            }
            if (frameLength <= 4) return null;
            return new MpegFrame(frameLength, sampleRate, samplesPerFrame);
        }

        @Override
        public float[] window(long startSample, int want) {
            int index = frameIndexFor(startSample);
            long skip = startSample - frameSamples[index];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(frameOffsets[index]);
                InputStream in = new BufferedInputStream(new RafInputStream(raf, frameOffsets[index]), 1 << 16);
                Bitstream bitstream = null;
                try {
                    bitstream = new Bitstream(in);
                    Decoder decoder = new Decoder();
                    // 用 JLayer 自带的 SampleBuffer：它的 clearBuffer/append/writeBuffer 时序是
                    // 解码器配套设计的，逐帧读 getBuffer() 就是这一帧的 PCM。
                    // 缓冲按帧头的真实声道数建：SampleBuffer 的第二参数是声道数，
                    // 交错长度 / 声道数才是帧数（立体声是 2304/2=1152）。
                    float[] out = new float[want];
                    int written = 0;
                    SampleBuffer buffer = null;
                    int bufferChannels = 0;
                    while (written < want) {
                        Header header = bitstream.readFrame();
                        if (header == null) break;
                        int frameChannels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                        if (buffer == null || bufferChannels != frameChannels) {
                            buffer = new SampleBuffer(header.frequency(), frameChannels);
                            bufferChannels = frameChannels;
                            decoder.setOutputBuffer(buffer);
                        }
                        decoder.decodeFrame(header, bitstream);
                        bitstream.closeFrame();
                        short[] samples = buffer.getBuffer();
                        int frames = buffer.getBufferLength() / frameChannels;
                        int offset = (int) Math.min(frames, Math.max(0L, skip));
                        for (int i = offset; i < frames && written < want; i++) {
                            float sum = 0f;
                            for (int c = 0; c < frameChannels; c++) {
                                sum += samples[i * frameChannels + c] / 32768f;
                            }
                            out[written++] = sum / frameChannels;
                        }
                        skip -= offset;
                    }
                    return written == want ? out : Arrays.copyOf(out, written);
                } finally {
                    if (bitstream != null) {
                        try {
                            bitstream.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable failure) {
                // 坏帧 / 截断：拿不到完整窗口就退回空，交给调用方走别的路。
                System.err.println("[Automix/Decode] mp3 窗口解码中断 " + file.getName()
                        + " @ " + startSample + ": " + failure);
                return EMPTY;
            }
        }

        /** 不超过 startSample 的最后一个帧。索引第一项采样位置恒为 0。 */
        private int frameIndexFor(long startSample) {
            int low = 0, high = frameCount - 1, best = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (frameSamples[mid] <= startSample) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return best;
        }

        @Override
        public void close() {
            // 索引全是内存数据，没有常驻句柄。
        }
    }

    private static final int[] MPEG1_RATES = {44_100, 48_000, 32_000};
    private static final int[] MPEG2_RATES = {22_050, 24_000, 16_000};
    private static final int[] MPEG25_RATES = {11_025, 12_000, 8_000};
    private static final int[][] MPEG1_BITRATES = {
            {32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},   // Layer I
            {32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},      // Layer II
            {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}};      // Layer III
    private static final int[][] MPEG2_BITRATES = {
            {32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},      // Layer I
            {8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},           // Layer II
            {8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};          // Layer III

    // ------------------------------------------------------------------
    // FLAC：jFLAC 原生 seek
    // ------------------------------------------------------------------

    private static final class FlacSource implements Source {

        private final File file;
        private final int sampleRate;
        private final int channels;
        private final int bitsPerSample;
        private final int maxBlockSize;

        private FlacSource(File file, int sampleRate, int channels, int bitsPerSample, int maxBlockSize) {
            this.file = file;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.maxBlockSize = maxBlockSize;
        }

        static FlacSource open(File file) {
            RandomFileInputStream in = null;
            try {
                in = new RandomFileInputStream(file);
                FLACDecoder decoder = new FLACDecoder(in);
                decoder.readMetadata();
                StreamInfo info = decoder.getStreamInfo();
                if (info == null) return null;
                int rate = info.getSampleRate();
                int channels = info.getChannels();
                int bits = info.getBitsPerSample();
                // jFLAC 只对 8/16/24 位输出 PCM（其余位深它在 decodeFrame 里直接不产出）。
                if (rate <= 0 || channels < 1 || channels > 8) return null;
                if (bits != 8 && bits != 16 && bits != 24) return null;
                int block = Math.max(1024, info.getMaxBlockSize());
                return new FlacSource(file, rate, channels, bits, block);
            } catch (Throwable failure) {
                return null;
            } finally {
                closeQuietly(in);
            }
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        /**
         * 先试 jFLAC 的 seek 跳到目标附近，跳不动（没有 seektable、估算失败）就从流头部顺序解。
         *
         * <p><b>seek 的返回值只当提示、不当真相</b>：实测 {@code seek()} 返回的
         * {@code sampleNumber} 会整整差一帧（它用字节估算 + 校准，估计值与真实帧位置可以差
         * 一个块）。所以窗口从哪一格开始、跳掉多少，全部以解码出来的帧头
         * {@code header.sampleNumber} 为准——那是从码流里读出来的，不是算出来的。</p>
         *
         * <p>跳转时多退两个块：即使估算偏晚一帧，起解位置仍在目标之前，
         * 多解的两帧（约 0.2 秒音频）相对于整窗代价可以忽略。</p>
         */
        @Override
        public float[] window(long startSample, int want) {
            float[] viaSeek = decodeWindow(startSample, want,
                    Math.max(0L, startSample - 2L * maxBlockSize), true);
            if (viaSeek.length > 0) return viaSeek;
            return decodeWindow(startSample, want, 0L, false);
        }

        private float[] decodeWindow(long startSample, int want, long seekTo, boolean useSeek) {
            RandomFileInputStream in = null;
            try {
                in = new RandomFileInputStream(file);
                FLACDecoder decoder = new FLACDecoder(in);
                FlacCollector collector = new FlacCollector(channels, bitsPerSample, startSample, want);
                SeekPoint from = null;
                if (useSeek) {
                    decoder.readMetadata();
                    from = decoder.seek(seekTo);
                    if (from == null) return EMPTY;
                }
                decoder.addFrameListener(collector);
                decoder.addPCMProcessor(collector);
                try {
                    if (from == null) {
                        decoder.decode();            // 从头顺序解（自身会先读元数据）
                    } else {
                        decoder.decode(from, null);  // 从 seek 点解到收满窗口
                    }
                    return collector.result();
                } catch (WindowComplete done) {
                    return collector.result();       // 收满即止，正常路径
                } catch (SamplesMissed missed) {
                    return EMPTY;                    // 起解位置晚于目标：交给顺序解码
                } catch (IOException end) {
                    return collector.result();       // 文件到头：已收到的样本仍然有效
                }
            } catch (Throwable failure) {
                if (useSeek) return EMPTY;           // 交给调用方走顺序解码
                System.err.println("[Automix/Decode] flac 顺序解码失败 " + file.getName()
                        + " @ " + startSample + ": " + failure);
                return EMPTY;
            } finally {
                closeQuietly(in);
            }
        }

        @Override
        public void close() {
        }
    }

    /**
     * jFLAC 的帧回调 + PCM 回调：帧头给出每一个 PCM 块在该曲目里的真实采样位置，
     * 按这个位置裁剪出目标窗口。收满窗口就抛出中止，不继续解后面的帧。
     */
    private static final class FlacCollector implements PCMProcessor, FrameListener {

        private final int channels;
        private final int bitsPerSample;
        private final int bytesPerSample;
        private final float[] out;
        private final long target;
        private final int want;
        private int written;
        /** 当前正在产出 PCM 的那一帧的起始采样位置；由 {@link #processFrame} 在 PCM 到来前更新。 */
        private long frameStart = -1L;

        FlacCollector(int channels, int bitsPerSample, long target, int want) {
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.bytesPerSample = bitsPerSample / 8;
            this.target = target;
            this.want = want;
            this.out = new float[want];
        }

        @Override
        public void processMetadata(repackage.org.kc7bfi.jflac.metadata.Metadata metadata) {
        }

        @Override
        public void processFrame(repackage.org.kc7bfi.jflac.frame.Frame frame) {
            frameStart = frame == null || frame.header == null ? -1L : frame.header.sampleNumber;
        }

        @Override
        public void processError(String message) {
        }

        @Override
        public void processStreamInfo(StreamInfo info) {
        }

        @Override
        public void processPCM(ByteData data) {
            int frameBytes = channels * bytesPerSample;
            if (frameBytes <= 0 || frameStart < 0L) return;
            int frames = data.getLen() / frameBytes;
            byte[] raw = data.getData();
            // 这一帧里属于窗口的部分：[max(frameStart, target), min(frameStart+frames, target+want))
            int offset = (int) Math.max(0L, target - frameStart);
            if (offset >= frames) {
                frameStart += frames;    // 整帧都在窗口之前
                return;
            }
            if (frameStart > target && written == 0) {
                // 仅当起解位置本身就晚于目标（前一段已经错过）才算失败；
                // 一旦开写，后面的帧起点必然大于目标，那是正常续帧，不是错误。
                throw new SamplesMissed();
            }
            int count = Math.min(frames - offset, want - written);
            for (int i = 0; i < count; i++) {
                int base = (offset + i) * frameBytes;
                float sum = 0f;
                for (int c = 0; c < channels; c++) {
                    sum += sample(raw, base + c * bytesPerSample);
                }
                out[written++] = sum / channels;
            }
            frameStart += frames;
            if (written >= want) throw new WindowComplete();
        }

        private float sample(byte[] raw, int offset) {
            if (bitsPerSample == 8) {
                return raw[offset] / 128f;
            }
            if (bitsPerSample == 24) {
                int value = (raw[offset] & 0xFF) | ((raw[offset + 1] & 0xFF) << 8)
                        | (raw[offset + 2] << 16);
                return value / 8388608f;
            }
            int value = (raw[offset] & 0xFF) | (raw[offset + 1] << 8);
            return value / 32768f;
        }

        float[] result() {
            return written == want ? out : Arrays.copyOf(out, written);
        }
    }

    /** 收满窗口时中止 jFLAC 解码循环的内部信号；不打印堆栈，也不该被当成错误。 */
    private static final class WindowComplete extends RuntimeException {
        WindowComplete() {
            super(null, null, false, false);
        }
    }

    /** 起解位置晚于目标、前面的样本已不可得：让调用方退回顺序解码。 */
    private static final class SamplesMissed extends RuntimeException {
        SamplesMissed() {
            super(null, null, false, false);
        }
    }

    // ------------------------------------------------------------------
    // 文件读取小工具
    // ------------------------------------------------------------------

    /** 顺序扫描用的缓冲游标：64KB 块读，按位置随机访问。 */
    private static final class RandomCursor implements Closeable {

        private final RandomAccessFile handle;
        private final byte[] buffer = new byte[1 << 16];
        private long bufferStart = -1L;
        private int bufferLength;

        RandomCursor(RandomAccessFile handle) {
            this.handle = handle;
        }

        /** @return 0..255；越过文件尾返回 -1 */
        int byteAt(long position) throws IOException {
            if (position < bufferStart || position >= bufferStart + bufferLength) {
                handle.seek(position);
                bufferStart = position;
                bufferLength = handle.read(buffer);
                if (bufferLength <= 0) return -1;
            }
            return buffer[(int) (position - bufferStart)] & 0xFF;
        }

        @Override
        public void close() {
        }
    }

    /** 给解码器用的只读流：从指定字节位开始，读不到也不关底层句柄。 */
    private static final class RafInputStream extends InputStream {

        private final RandomAccessFile handle;

        RafInputStream(RandomAccessFile handle, long start) throws IOException {
            this.handle = handle;
            handle.seek(start);
        }

        @Override
        public int read() throws IOException {
            return handle.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            return handle.read(target, offset, length);
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0L) return 0L;
            long position = handle.getFilePointer();
            long target = Math.min(handle.length(), position + count);
            handle.seek(target);
            return target - position;
        }

        @Override
        public int available() {
            return 0;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
