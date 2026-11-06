package com.muoniumplayer.core.ncm.music;

/**
 * 「卡点运镜」的节拍门面：把仓库已有的三级节拍链整理成调度器要的<b>网格级</b>视图。
 *
 * <p>与 {@link BeatPulseSource} 的分工：那边输出「拍点脉冲包络」（给弹簧压模型，
 * 只关心当下这一帧多响）；这边输出「节拍网格结构」（BPM、相位、每小节拍数、
 * 段落边界、置信度）——运镜调度器要提前规划「下一拍/下一小节/下一段落该干嘛」，
 * 需要的是日历不是心跳。</p>
 *
 * <h3>三级取拍（与 BeatPulseSource 同链，不重复分析）</h3>
 * <ol>
 *   <li><b>网格</b>：{@link FoliaTrackProfiler#peek} 拿 {@link FoliaTrackProfile} 的
 *       bpm + beatPhaseMillis + barPhaseMillis + beatsPerBar + sectionMillis；
 *       profile 不在盘上时由 {@link BeatPulseSource} 的既有惰性触发链去分析（这边不重复投）。</li>
 *   <li><b>实时估算</b>：无网格时用 {@link BeatPulseSource} 的估算节拍（中位数 onset 间隔
 *       + 一致度置信度）。包私有探针 {@code estimatedBeatForCine()} 把估算结果暴露给本类。</li>
 *   <li><b>无节拍</b>：返回 {@code confidence=0} 的网格，调度器退化为「能量驱动的慢切」。</li>
 * </ol>
 *
 * <p>全部读侧；换歌作废由 {@code BeatPulseSource} 的会话键负责，这里只透传。</p>
 */
public final class CineBeatSource {

    private CineBeatSource() {
    }

    /**
     * 当前播放的节拍网格视图。未播放/暂停返回 null（运镜不启动）。
     */
    public static Grid currentGrid() {
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        if (snapshot == null || !snapshot.playing) return null;
        return currentGrid(snapshot, System.nanoTime());
    }

    /** 测试注入时钟的版本；生产走 {@link #currentGrid()}。 */
    static Grid currentGrid(CloudMusic.PlaybackSnapshot snapshot, long nowNanos) {
        // 一级：Folia 离线分析的整曲网格。
        Grid grid = foliaGrid(snapshot, nowNanos);
        if (grid != null) return grid;
        // 二级：实时估算收敛的节拍。estimate = {periodNanos, confidence}。
        float[] estimate = BeatPulseSource.estimatedBeatForCine();
        if (estimate != null && estimate[0] > 0f) {
            float periodNanos = estimate[0];
            float confidence = estimate[1];
            float bpm = 60_000_000_000f / periodNanos;
            // 估算锚：估算器每次重算时把 lastBeatNanos 对齐到当时的拍点，相位从那里起算。
            double beatAnchorNanos = BeatPulseSource.estimatedPhaseNanosForCine(nowNanos);
            return new Grid(bpm, periodNanos, beatAnchorNanos, 4, beatAnchorNanos, confidence);
        }
        // 三级：无节拍信号 —— 调度器拿 confidence=0 自行退化。
        return new Grid(0f, 0f, 0f, 4, 0f, 0f);
    }

    private static Grid foliaGrid(CloudMusic.PlaybackSnapshot snapshot, long nowNanos) {
        if (!FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS)) return null;
        if (!AutomixSettings.isFoliaEngine()) return null;
        java.io.File source = snapshot.player == null ? null : snapshot.player.getSourceFile();
        if (source == null || !source.isFile()) return null;
        FoliaTrackProfile profile = FoliaTrackProfiler.peek(source);
        if (profile == null || !profile.isUsable()
                || profile.beatConfidence < 0.5f || profile.bpm <= 0f) return null;
        double periodNanos = 60_000_000_000.0 / profile.bpm;
        // 相位锚与 BeatPulseSource.currentGrid 同式：拍点在曲内时刻 t 满足
        // (t - beatPhaseMillis) ≡ 0 (mod period)，换算成墙上时钟锚。
        double beatAnchorNanos = nowNanos
                - (snapshot.positionMs - profile.beatPhaseMillis) * 1_000_000.0;
        // 小节锚同理：barPhaseMillis 是「每小节第一拍」在曲内的相位。
        double barPeriodNanos = periodNanos * Math.max(1, profile.beatsPerBar);
        double barAnchorNanos = nowNanos
                - (snapshot.positionMs - profile.barPhaseMillis) * 1_000_000.0;
        return new Grid(profile.bpm,
                periodNanos,
                beatAnchorNanos,
                Math.max(1, profile.beatsPerBar),
                barAnchorNanos,
                profile.beatConfidence);
    }

    /**
     * 节拍网格的不可变视图。时间锚全是「墙上时钟纳秒」：把 {@code nowNanos} 减锚
     * 再对周期取模即得拍内相位，与 {@code BeatPulseSource} 同一口径。
     *
     * @param bpm             每分钟拍数（0 = 无节拍信号）
     * @param beatPeriodNanos 一拍长（纳秒；0 = 无节拍信号）
     * @param beatAnchorNanos 最近一个拍点的墙上时刻
     * @param beatsPerBar     每小节拍数（默认 4）
     * @param barAnchorNanos  最近一个小节第一拍的墙上时刻（无网格时与 beatAnchor 同值）
     * @param confidence      网格置信度 0..1（0 = 无节拍，调度器退化）
     */
    public record Grid(float bpm,
                       double beatPeriodNanos,
                       double beatAnchorNanos,
                       int beatsPerBar,
                       double barAnchorNanos,
                       float confidence) {

        /** 无节拍信号（第三级兜底）：调度器应退化为能量慢切。 */
        public boolean isMetered() {
            return beatPeriodNanos > 0d && confidence > 0f;
        }

        /** 当前拍内相位 0..1（nowNanos 相对最近拍点）。 */
        public float beatPhase(long nowNanos) {
            if (beatPeriodNanos <= 0d) return 0f;
            double elapsed = nowNanos - beatAnchorNanos;
            double phase = elapsed - Math.floor(elapsed / beatPeriodNanos) * beatPeriodNanos;
            return (float) (phase / beatPeriodNanos);
        }

        /** 当前小节内第几拍（0 起算，小节第一拍 = 0）。 */
        public int beatInBar(long nowNanos) {
            if (beatPeriodNanos <= 0d) return 0;
            // barAnchor 无独立锚（估算档与 beatAnchor 同值）时按 4/4 假定小节起点=锚点。
            double barPeriod = beatPeriodNanos * Math.max(1, beatsPerBar);
            double elapsed = nowNanos - barAnchorNanos;
            double phase = elapsed - Math.floor(elapsed / barPeriod) * barPeriod;
            return (int) (phase / beatPeriodNanos);
        }

        /** 距下一个拍点的秒数（0..1 拍）。 */
        public float secondsToNextBeat(long nowNanos) {
            if (beatPeriodNanos <= 0d) return 0f;
            return (1f - beatPhase(nowNanos)) * (float) (beatPeriodNanos / 1.0e9);
        }
    }
}
