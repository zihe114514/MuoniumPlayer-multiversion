package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「通透模式」的节拍源：按<b>乐理节拍</b>出脉冲，不是「有多响压多狠」。
 *
 * <h3>三级取拍链（用户拍板）</h3>
 * <ol>
 *   <li><b>节拍网格</b>：用户装了 Folia 模型且处理方案选「智能混音」时，读
 *       {@link FoliaTrackProfile} 里 Beat This! 分析出的 {@code bpm + beatPhaseMillis}，
 *       拿当前播放位置对网格取相位 —— 每个拍点触发一次，不管那拍上有没有鼓。
 *       profile 在盘上就直接用；不在就<b>顺带触发一次后台分析</b>（低优先级池，
 *       下一首生效，不阻塞本首）；分析失败/拿不到回退下一级。</li>
 *   <li><b>实时估算</b>：没有模型也没 profile 时，对正在播的信号做在线测速 ——
 *       用 {@link PlaybackPulse} 的 onset 事件流做自相关，估出 BPM 与相位并逐拍修正。
 *       精度不如整曲模型（30s 窗口收敛），但任何歌即时生效。</li>
 *   <li><b>能量回退</b>：估算还没收敛（刚开播几秒）就退回原始
 *       {@link PlaybackPulse#beat()} 能量脉冲。</li>
 * </ol>
 *
 * <h3>线程与成本纪律</h3>
 * <p>读位置/出脉冲走渲染帧（与 {@link TransparentMode} 同线程）；触发分析投给
 * {@link BackgroundWork}（项目里 profile 预热的同款池），一次 120–180s，
 * 每首歌至多触发一次（键=音频文件），绝不重复提交。这里的自估算是
 * <b>纯增量计数器</b>，不开线程。</p>
 */
public final class BeatPulseSource {

    /** 分析在飞/已完成的歌（按音频文件绝对路径判重），防止每帧重复提交。 */
    private static final AtomicBoolean ANALYSIS_IN_FLIGHT = new AtomicBoolean();

    /** 实时估算：滑窗里最近 onset 的纳秒时刻（环形）。 */
    private static final int ONSET_WINDOW = 64;
    private static final float[] ONSET_NANOS = new float[ONSET_WINDOW];
    private static int onsetCount;
    private static long lastOnsetNanos;
    private static long lastBeatNanos;

    /** 自估节拍（纳秒一拍）。&lt;=0 表示未收敛。 */
    private static float estimatedPeriodNanos;
    private static float estimatedConfidence;

    /** 会话归属：换歌/换会话时估算与网格全部作废重建。 */
    private static long lastSessionId = Long.MIN_VALUE;
    private static long lastSongId = Long.MIN_VALUE;

    private BeatPulseSource() {
    }

    /**
     * 当前节拍脉冲 0..1：拍点上冲高、拍间落下，簧见 {@link TransparentMode}。
     *
     * <p>同一帧里 {@link TransparentMode} 的两个入口各调一次是安全的：拍点判定
     * 用单调的墙上时钟推进，走过一个拍点只报一次。暂停时返回 0 ——
     * 节拍跟着播放状态走，暂停下继续弹是 bug 不是特性。</p>
     */
    public static float beat() {
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        if (snapshot == null || !snapshot.playing) {
            resetPlaybackState();
            return 0f;
        }
        // 换歌/换会话：旧歌的网格相位、估算与 onset 窗全是错的，全部作废。
        if (snapshot.sessionId != lastSessionId || snapshot.songId != lastSongId) {
            resetPlaybackState();
            lastSessionId = snapshot.sessionId;
            lastSongId = snapshot.songId;
        }
        long now = System.nanoTime();
        Grid grid = currentGrid(snapshot, now);
        if (grid != null) {
            // 网格拍：相位=0 处打满、按 1/4 拍宽指数回落 —— 弹簧再把这包络顺成压-弹。
            double elapsed = now - grid.phaseNanos();
            double phase = elapsed - Math.floor(elapsed / grid.periodNanos()) * grid.periodNanos();
            // 网格拍喂估算器：间隔恒为周期，等价自相关的完美样本。
            recordOnset(now - (long) phase);
            return pulseAtPhase((float) (phase / grid.periodNanos()));
        }
        // 无网格：实时估算收敛了就用估算拍，否则退能量脉冲。
        if (estimatedPeriodNanos > 0f && estimatedConfidence >= 0.55f) {
            double elapsed = now - lastBeatNanos;
            double phase = elapsed - Math.floor(elapsed / estimatedPeriodNanos) * estimatedPeriodNanos;
            return pulseAtPhase((float) (phase / estimatedPeriodNanos));
        }
        float energy = PlaybackPulse.beat();
        // 能量回退期把 onset 事件喂给估算器养节拍：PlaybackPulse.pulse 升沿即拍。
        noteEnergyOnset(energy);
        return energy;
    }

    /** 清播放期状态：onset 窗、估算、能量升沿记忆。归属键由调用方重记。 */
    private static void resetPlaybackState() {
        onsetCount = 0;
        lastOnsetNanos = 0L;
        lastBeatNanos = 0L;
        estimatedPeriodNanos = 0f;
        estimatedConfidence = 0f;
        lastEnergy = 0f;
        java.util.Arrays.fill(ONSET_NANOS, 0f);
    }

    /** 能量脉冲升沿检测：上一帧低、这一帧高，视为一次 onset。 */
    private static float lastEnergy;
    private static void noteEnergyOnset(float energy) {
        if (energy > 0.35f && lastEnergy <= 0.35f) recordOnset(System.nanoTime());
        lastEnergy = energy;
    }

    /** 拍内包络：拍点 1.0，一个 1/4 拍内指数落到 0 —— 弹簧负责把它变成果冻。 */
    static float pulseAtPhase(float phase) {
        if (phase < 0f) phase = 0f;
        if (phase > 1f) phase = 1f;
        return (float) Math.exp(-phase * 4f);
    }

    // ---------------- 一级：节拍网格 ----------------

    private record Grid(double periodNanos, double phaseNanos) {
    }

    private static Grid currentGrid(CloudMusic.PlaybackSnapshot snapshot, long now) {
        // 用户口径：装了模型 + 开了智能混音才走这条（「智能过度」= Folia 引擎）。
        if (!FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS)) return null;
        if (!AutomixSettings.isFoliaEngine()) return null;
        FoliaTrackProfile profile = readProfile(snapshot);
        if (profile == null || profile.beatConfidence < 0.5f || profile.bpm <= 0f) return null;
        double periodNanos = 60_000_000_000.0 / profile.bpm;
        // profile 的相位锚在曲首：拍点在曲内时刻 t 满足 (t - phaseMillis) ≡ 0 (mod period)。
        // 换算成墙上时钟锚：now - (positionMs - phaseMillis)，即「最近一个拍点的墙上时刻」。
        double anchorNanos = now
                - (snapshot.positionMs - profile.beatPhaseMillis) * 1_000_000.0;
        return new Grid(periodNanos, anchorNanos);
    }

    /** 读盘上的 profile；没有且满足触发条件时投一次后台分析（每首歌至多一次）。 */
    private static FoliaTrackProfile readProfile(CloudMusic.PlaybackSnapshot snapshot) {
        java.io.File source = snapshot.player == null ? null : snapshot.player.getSourceFile();
        if (source == null || !source.isFile()) return null;
        FoliaTrackProfile profile = FoliaTrackProfiler.peek(source);
        if (profile != null) return profile;
        if (ANALYSIS_IN_FLIGHT.compareAndSet(false, true)) {
            String path = source.getAbsolutePath();
            long length = source.length();
            long modified = source.lastModified();
            BackgroundWork.submit(() -> {
                try {
                    // 重新解析文件身份：后台跑到的可能已经是下一首，键对不上就别硬塞。
                    java.io.File current = CloudMusic.getSnapshot() == null ? null
                            : (CloudMusic.getSnapshot().player == null ? null
                            : CloudMusic.getSnapshot().player.getSourceFile());
                    if (current != null && current.getAbsolutePath().equals(path)
                            && current.length() == length && current.lastModified() == modified) {
                        AudioPlayer deck = new AudioPlayer(current);
                        deck.setTrackName(current.getName());
                        FoliaTrackProfiler.loadOrAnalyse(deck, false);
                    }
                } catch (Throwable failure) {
                    System.out.println("[TransparentMode] 节拍 profile 后台分析失败: " + failure);
                } finally {
                    ANALYSIS_IN_FLIGHT.set(false);
                }
            });
        }
        return null;
    }

    // ---------------- 二级：实时估算（onset 自相关） ----------------

    private static void recordOnset(long now) {
        // 测试时钟重定向：advanceClockForTest 之后取测试值，生产恒走 System.nanoTime()。
        long stamp = testClockHooked ? testNowNanos : System.nanoTime();
        if (lastOnsetNanos != 0L) {
            double interval = stamp - lastOnsetNanos;
            // 网格拍是周期喂的：间隔恒等于周期本身。能量 onset 是人肉拍：
            // 0.25~2s 视作乐理拍（30~240 BPM），更密的当 trill 丢掉。
            if (interval >= 0.25e9 && interval <= 2e9) {
                ONSET_NANOS[onsetCount % ONSET_WINDOW] = (float) interval;
                onsetCount++;
                if (onsetCount >= 8) recomputeEstimate(stamp);
            }
        }
        lastOnsetNanos = stamp;
    }

    private static volatile boolean testClockHooked;

    private static void recomputeEstimate(long now) {
        // 中位数间隔：单个 onset 漏检/多检拖不歪拍子。环形窗取最近的样本。
        int count = Math.min(onsetCount, ONSET_WINDOW);
        float[] samples = new float[count];
        for (int i = 0; i < count; i++) {
            samples[i] = ONSET_NANOS[(onsetCount - 1 - i) % ONSET_WINDOW];
        }
        java.util.Arrays.sort(samples);
        float median = samples[count / 2];
        // 一致度：区间落在中位数 ±15% 内的比例。
        int agrees = 0;
        for (float sample : samples) {
            if (Math.abs(sample - median) < median * 0.15f) agrees++;
        }
        estimatedPeriodNanos = median;
        estimatedConfidence = agrees / (float) count;
        lastBeatNanos = now;
    }

    static void resetForTest() {
        resetPlaybackState();
        lastSessionId = Long.MIN_VALUE;
        lastSongId = Long.MIN_VALUE;
        testClockHooked = false;
        testNowNanos = 1_000_000_000L; // JUnit 方法序随机：时钟也必须归位
        ANALYSIS_IN_FLIGHT.set(false);
    }

    // ---- 测试专用钩子：隔离墙上时钟与 CloudMusic 快照，只驱动估算器纯逻辑 ----

    /** 按生产同款升沿路径喂能量值（测估算收敛用）。未挂钩测试时钟时直接忽略：
     *  否则 recordOnset 会拿真实 nanoTime 当样本，与后续测试时钟的间隔变成负数全丢。 */
    static void feedEnergyForTest(float energy) {
        if (!testClockHooked) return;
        if (energy > 0.35f && lastEnergy <= 0.35f) recordOnset(testNowNanos);
        lastEnergy = energy;
    }

    private static long testNowNanos = 1_000_000_000L;

    /** 推进测试时钟：recordOnset 从此取测试值，生产路径（未挂钩）不受影响。 */
    static void advanceClockForTest(long nanos) {
        testNowNanos = nanos;
        testClockHooked = true;
    }

    static float estimatedPeriodNanosForTest() {
        return estimatedPeriodNanos;
    }

    static float estimatedConfidenceForTest() {
        return estimatedConfidence;
    }

    // ---- 卡点运镜（CineBeatSource）的包私有探针：读二级估算网格，不触发任何推进 ----

    /**
     * 实时估算的 {周期纳秒, 置信度}；未收敛返回 null。
     * 与 {@link #beat()} 的收敛阈值一致（&ge;0.55），保证两级链口径相同。
     */
    static float[] estimatedBeatForCine() {
        if (estimatedPeriodNanos <= 0f || estimatedConfidence < 0.55f) return null;
        return new float[] {estimatedPeriodNanos, estimatedConfidence};
    }

    /** 估算网格的墙上时钟锚（最近一个估算拍点）；未收敛时返回 0。 */
    static double estimatedPhaseNanosForCine(long nowNanos) {
        return lastBeatNanos;
    }
}
