package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 一轮推导的最终交付:音频网络该呈现的完整「房间」。由 {@link SceneResponse}
 * 在采样线程构建,经 volatile 引用交接给音频线程 —— 不可变,建好后不许改。
 *
 * <p>四个声学量(频带/湿声/响度/干声)之外,逐频带衰减时长是物理混响的核心:
 * 低/中/高三段独立 T60 驱动 FDN 尾的阻尼滤波(真房间低频留得久、高频被软面
 * 吃掉,一套 decaySec 表达不了);早期反射抽头随计划一起交付:抽头延迟是整数
 * 毫秒量化的采样位置、能量已归一并分配到双耳,音频线程只管读。</p>
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
    /** 衰减时长(秒):中频带 T60;直方图拟合失败时的解析兜底。0 = 静息。 */
    public final float decaySec;
    /** 音色 0..1(额外的高频让位,叠加在三频带 T60 之上;大 = 更暗)。 */
    public final float tone;
    /** 低频带衰减时长(秒):低频比中频留得久(小房间模态、大房间混响都如此)。 */
    public final float decayLowSec;
    /** 高频带衰减时长(秒):软面(羊毛/沙)高频衰减明显更快。 */
    public final float decayHighSec;
    /** 扩散度 0..1:直方图有能量桶占比 —— 驱动 FDN 混合(离散回声 vs 漫散射)。 */
    public final float diffusion;

    /** 早期反射抽头(左右能量已按等功率声像分好);空数组 = 没有抽头。 */
    public final int tapCount;
    public final float[] tapDelaySec;
    public final float[] tapGainL;
    public final float[] tapGainR;

    /** 直达声总增益 0..1(距离 × 遮挡);1 = 无声源或贴脸(不衰减)。 */
    public final float directGain;
    /** 直达声低通截止(Hz);≥20000 = 不压(无声源/近场无遮挡)。 */
    public final double directCutoffHz;
    /** 直达声像 −1(源在左)..1(源在右)。 */
    public final float directPan;
    /** 直达路程(米):抽头相对时序的基准(渲染里直达零延迟)。 */
    public final float directDistanceMeters;
    /**
     * 世界声源模式下湿声走 OpenAL EAX Reverb 总线(探针参数直驱);
     * false = 自研 FDN 链(无声源/安卓/总线不可用)。EAX 参数由
     * {@code SceneResponse} 从同一份几何推导,音频线程逐缓冲下放。
     */
    public final boolean useOpenAlReverb;
    /** EAX 混响 HF 衰减比(0.1..2):反射率越低(软面)尾越暗。 */
    public final float reverbDecayHfRatio;
    /** EAX 混响 diffusion 0..1:材质粗糙度 —— 岩壁亮面 vs 羊毛漫射。 */
    public final float reverbDiffusion;
    /** 有没有音箱锚点(false = 玩家即声源:source 贴头零衰减零方向)。 */
    public final boolean hasSpeakerAnchor;
    /** 声源世界坐标(EAX 总线的 3D 定位;无声源 0,0,0)。 */
    public final double speakerX;
    public final double speakerY;
    public final double speakerZ;

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR) {
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR,
                1f, DirectPath.FC_OPEN, 0f, 0f,
                false, 1f, 0.5f, false, 0, 0, 0);
    }

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR,
                    float directGain, double directCutoffHz, float directPan,
                    float directDistanceMeters) {
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR,
                directGain, directCutoffHz, directPan, directDistanceMeters,
                false, 1f, 0.5f, false, 0, 0, 0);
    }

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR,
                    float directGain, double directCutoffHz, float directPan,
                    float directDistanceMeters,
                    boolean useOpenAlReverb, float reverbDecayHfRatio, float reverbDiffusion,
                    boolean hasSpeakerAnchor,
                    double speakerX, double speakerY, double speakerZ) {
        this.bandHz = bandHz;
        this.wet = wet;
        this.dryGain = dryGain;
        this.loudness = loudness;
        this.predelaySec = predelaySec;
        this.decaySec = decaySec;
        this.tone = tone;
        this.decayLowSec = decayLowSec;
        this.decayHighSec = decayHighSec;
        this.diffusion = diffusion;
        this.tapCount = tapDelaySec.length;
        this.tapDelaySec = tapDelaySec;
        this.tapGainL = tapGainL;
        this.tapGainR = tapGainR;
        this.directGain = directGain;
        this.directCutoffHz = directCutoffHz;
        this.directPan = directPan;
        this.directDistanceMeters = directDistanceMeters;
        this.useOpenAlReverb = useOpenAlReverb;
        this.reverbDecayHfRatio = reverbDecayHfRatio;
        this.reverbDiffusion = reverbDiffusion;
        this.hasSpeakerAnchor = hasSpeakerAnchor;
        this.speakerX = speakerX;
        this.speakerY = speakerY;
        this.speakerZ = speakerZ;
    }

    /** 静息计划:直通。 */
    public static RoomPlan idle() {
        return new RoomPlan(SceneAcoustics.bandOpenHz(), 0f, 1f, 1f, 0f, 0f,
                SceneResponse.NEUTRAL_TONE, 0f, 0f, 1f,
                new float[0], new float[0], new float[0],
                1f, DirectPath.FC_OPEN, 0f, 0f,
                false, 1f, 0.5f, false, 0, 0, 0);
    }

    @Override
    public String toString() {
        return "band=" + String.format("%.0f", bandHz)
                + " wet=" + String.format("%.2f", wet)
                + " dry=" + String.format("%.2f", dryGain)
                + " 衰减=" + String.format("%.2f", decaySec) + "s"
                + " 低=" + String.format("%.2f", decayLowSec)
                + " 高=" + String.format("%.2f", decayHighSec)
                + " 扩散=" + String.format("%.2f", diffusion)
                + " 预延迟=" + String.format("%.1f", predelaySec * 1000) + "ms"
                + " 抽头=" + tapCount
                + " 直达=" + String.format("%.2f", directGain)
                + "@" + String.format("%.0f", directCutoffHz) + "Hz";
    }
}
