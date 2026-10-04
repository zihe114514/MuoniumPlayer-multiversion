package com.muoniumplayer.core.ncm.music;

/**
 * Automix 视觉层使用的真实交接曲线快照。
 *
 * <p>曲线不是为动画另调的一套数据：由同一个 {@link AutomixPlan} 在交接开始时采样，
 * 覆盖增益、低频交换和中/高频补偿。渲染线程只读不可变数组，不会触碰播放牌或音频线程。</p>
 */
public final class AutomixTransitionCurve {
    public static final int DEFAULT_SAMPLE_COUNT = 64;
    public enum Band { OUTGOING_GAIN, INCOMING_GAIN, OUTGOING_BASS, INCOMING_BASS,
        OUTGOING_MID, INCOMING_MID, OUTGOING_HIGH, INCOMING_HIGH }

    private final long durationMillis;
    private final double crossover;
    private final double periodMillis;
    private final float[][] values;

    private AutomixTransitionCurve(long durationMillis, double crossover, double periodMillis,
                                   float[][] values) {
        this.durationMillis = durationMillis;
        this.crossover = finite01(crossover);
        this.periodMillis = Double.isFinite(periodMillis) ? Math.max(120.0, Math.min(4_000.0, periodMillis)) : 500.0;
        this.values = values;
    }

    /** 从真实 AutomixPlan 取样；数组之后不再暴露给调用方修改。 */
    public static AutomixTransitionCurve from(AutomixPlan plan) {
        if (plan == null) return null;
        final int count = DEFAULT_SAMPLE_COUNT;
        float[][] values = new float[Band.values().length][count];
        for (int index = 0; index < count; index++) {
            double p = index / (double) (count - 1);
            values[Band.OUTGOING_GAIN.ordinal()][index] = finite(plan.outgoingGain(p));
            values[Band.INCOMING_GAIN.ordinal()][index] = finite(plan.incomingGain(p));
            values[Band.OUTGOING_BASS.ordinal()][index] = finite(plan.outgoingBassDb(p));
            values[Band.INCOMING_BASS.ordinal()][index] = finite(plan.incomingBassDb(p));
            values[Band.OUTGOING_MID.ordinal()][index] = finite(plan.outgoingMidDb(p));
            values[Band.INCOMING_MID.ordinal()][index] = finite(plan.incomingMidDb(p));
            values[Band.OUTGOING_HIGH.ordinal()][index] = finite(plan.outgoingHighDb(p));
            values[Band.INCOMING_HIGH.ordinal()][index] = finite(plan.incomingHighDb(p));
        }
        return new AutomixTransitionCurve(plan.overlapMillis, crossoverOf(plan),
                plan.animationPeriodMillis, values);
    }

    /**
     * 设置面板预览使用的安全示例曲线。
     *
     * <p>预览没有正在播放的两首歌，因此不能伪造某个实际计划；这里复用手动
     * 快速混音计划的真实 DSP 曲线，让预览仍然展示同一套增益/EQ 形状。实际
     * 播放时始终由 {@link #from(AutomixPlan)} 创建快照。</p>
     */
    public static AutomixTransitionCurve preview() {
        return from(AutomixPlan.manualQuickMix(10_000L, true));
    }

    private static double crossoverOf(AutomixPlan plan) {
        if (plan.style == FoliaTransitionStyle.BASS_SWAP) return plan.bassHandoverAt;
        if (plan.style == FoliaTransitionStyle.TAIL_RIDE) return plan.drumHandoverAt;
        return .5;
    }

    public long durationMillis() { return durationMillis; }
    public double crossover() { return crossover; }
    public double periodMillis() { return periodMillis; }
    public int samples() { return values[0].length; }

    public float value(Band band, int index) {
        if (band == null) return 0f;
        int safe = Math.max(0, Math.min(samples() - 1, index));
        return values[band.ordinal()][safe];
    }

    public float value(Band band, double progress) {
        if (band == null) return 0f;
        double p = Double.isFinite(progress) ? Math.max(0.0, Math.min(1.0, progress)) : 0.0;
        double position = p * (samples() - 1);
        int low = (int) Math.floor(position);
        int high = Math.min(samples() - 1, low + 1);
        double mix = position - low;
        return (float) (value(band, low) * (1.0 - mix) + value(band, high) * mix);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0f;
    }

    private static double finite01(double value) {
        if (!Double.isFinite(value)) return .5;
        return Math.max(0.0, Math.min(1.0, value));
    }
}

