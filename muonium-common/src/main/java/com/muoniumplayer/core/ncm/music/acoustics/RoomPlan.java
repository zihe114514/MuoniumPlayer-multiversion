package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 一轮推导的最终交付:音频网络该呈现的完整「房间」。由 {@link SceneResponse}
 * 在游戏线程构建,经 volatile 引用交接给音频线程 —— 不可变,建好后不许改。
 *
 * <p>四个声学量(频带/湿声/响度/干声)之外,早期反射抽头随计划一起交付:
 * 抽头延迟是整数毫秒量化的采样位置、能量已归一并分配到双耳,音频线程只管读。</p>
 */
public final class RoomPlan {

    public final double bandHz;
    /** 湿声总量 0..1(尾部网络的驱动量;已含强度)。 */
    public final float wet;
    /** 干声系数(响度 × 干声让位;已含强度)。 */
    public final float dryGain;
    /** 整体响度系数 0..1(浸液/降水;1 = 不衰减)。 */
    public final float loudness;
    /** 预延迟(秒):尾部网络的直湿间隔。 */
    public final float predelaySec;
    /** 衰减时长(秒):回授的解析来源;0 = 静息。 */
    public final float decaySec;
    /** 音色 0..1(梳内阻尼,大 = 更暗)。 */
    public final float tone;

    /** 早期反射抽头(左右能量已按等功率声像分好);空数组 = 没有抽头。 */
    public final int tapCount;
    public final float[] tapDelaySec;
    public final float[] tapGainL;
    public final float[] tapGainR;

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR) {
        this.bandHz = bandHz;
        this.wet = wet;
        this.dryGain = dryGain;
        this.loudness = loudness;
        this.predelaySec = predelaySec;
        this.decaySec = decaySec;
        this.tone = tone;
        this.tapCount = tapDelaySec.length;
        this.tapDelaySec = tapDelaySec;
        this.tapGainL = tapGainL;
        this.tapGainR = tapGainR;
    }

    /** 静息计划:直通。 */
    public static RoomPlan idle() {
        return new RoomPlan(SceneAcoustics.bandOpenHz(), 0f, 1f, 1f, 0f, 0f,
                SceneResponse.NEUTRAL_TONE,
                new float[0], new float[0], new float[0]);
    }

    @Override
    public String toString() {
        return "band=" + String.format("%.0f", bandHz)
                + " wet=" + String.format("%.2f", wet)
                + " dry=" + String.format("%.2f", dryGain)
                + " 衰减=" + String.format("%.2f", decaySec) + "s"
                + " 预延迟=" + String.format("%.1f", predelaySec * 1000) + "ms"
                + " 抽头=" + tapCount;
    }
}
