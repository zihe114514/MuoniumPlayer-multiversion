package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 一轮推导的完整声场。采样线程构建,通过一个 volatile 引用交给音频线程。
 * 数值与抽头数组在构造时校验并复制,交接后不再改写。
 */
public final class RoomPlan {

    public final double bandHz;
    /** 湿声总量 0..1,已含场景强度与传播稀释。 */
    public final float wet;
    /** 干声系数:整体响度 × 混响让位。 */
    public final float dryGain;
    /** 浸液/降水的整体响度,也作用于湿声。 */
    public final float loudness;
    public final float predelaySec;
    public final float decaySec;
    public final float tone;
    public final float decayLowSec;
    public final float decayHighSec;
    public final float diffusion;

    /** 相对直线传播时间的早期反射,分耳增益已按等功率声像分配。 */
    public final int tapCount;
    public final float[] tapDelaySec;
    public final float[] tapGainL;
    public final float[] tapGainR;
    /** 每条几何反射的高频幅度保留比(高频/中频),null 输入回退为 1。 */
    public final float[] tapHighRatio;

    /** 距离 × 遮挡/绕射的直射增益,两种渲染后端共用。 */
    public final float directGain;
    public final double directCutoffHz;
    public final float directPan;
    /** 源到耳的直线距离,反射相对时序的基准。 */
    public final float directDistanceMeters;
    /** 实际直射路径长度:绕射时包含额外出程,用于传播延迟。 */
    public final float directPathMeters;
    /** 保留既有字段名;现在仅请求 HRTF 直射,湿声始终走 ER + FDN。 */
    public final boolean useOpenAlReverb;
    public final float reverbDecayHfRatio;
    public final float reverbDiffusion;
    public final boolean hasSpeakerAnchor;
    /** 0 = 使用音频线程的默认收敛时间。 */
    public final float transitionSec;
    /** 主直射路径确实选用了绕射,不是仅仅找到候选路径。 */
    public final boolean diffracted;
    /** 直射的到达方向坐标;绕射时为墙角/门缝,否则为音箱锚点。 */
    public final double speakerX;
    public final double speakerY;
    public final double speakerZ;

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR) {
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR, null,
                1f, DirectPath.FC_OPEN, 0f, 0f,
                false, 1f, 0.5f, false, 0, 0, 0, 0f, false, 0f);
    }

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR,
                    float directGain, double directCutoffHz, float directPan,
                    float directDistanceMeters) {
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR, null,
                directGain, directCutoffHz, directPan, directDistanceMeters,
                false, 1f, 0.5f, false, 0, 0, 0, 0f, false, directDistanceMeters);
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
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR, null,
                directGain, directCutoffHz, directPan, directDistanceMeters,
                useOpenAlReverb, reverbDecayHfRatio, reverbDiffusion,
                hasSpeakerAnchor, speakerX, speakerY, speakerZ, 0f, false, directDistanceMeters);
    }

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR,
                    float directGain, double directCutoffHz, float directPan,
                    float directDistanceMeters,
                    boolean useOpenAlReverb, float reverbDecayHfRatio, float reverbDiffusion,
                    boolean hasSpeakerAnchor,
                    double speakerX, double speakerY, double speakerZ,
                    float transitionSec) {
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR, null,
                directGain, directCutoffHz, directPan, directDistanceMeters,
                useOpenAlReverb, reverbDecayHfRatio, reverbDiffusion,
                hasSpeakerAnchor, speakerX, speakerY, speakerZ,
                transitionSec, false, directDistanceMeters);
    }

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR,
                    float directGain, double directCutoffHz, float directPan,
                    float directDistanceMeters,
                    boolean useOpenAlReverb, float reverbDecayHfRatio, float reverbDiffusion,
                    boolean hasSpeakerAnchor,
                    double speakerX, double speakerY, double speakerZ,
                    float transitionSec, boolean diffracted) {
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR, null,
                directGain, directCutoffHz, directPan, directDistanceMeters,
                useOpenAlReverb, reverbDecayHfRatio, reverbDiffusion,
                hasSpeakerAnchor, speakerX, speakerY, speakerZ,
                transitionSec, diffracted, directDistanceMeters);
    }

    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR,
                    float directGain, double directCutoffHz, float directPan,
                    float directDistanceMeters,
                    boolean useOpenAlReverb, float reverbDecayHfRatio, float reverbDiffusion,
                    boolean hasSpeakerAnchor,
                    double speakerX, double speakerY, double speakerZ,
                    float transitionSec, boolean diffracted, float directPathMeters) {
        this(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion,
                tapDelaySec, tapGainL, tapGainR, null,
                directGain, directCutoffHz, directPan, directDistanceMeters,
                useOpenAlReverb, reverbDecayHfRatio, reverbDiffusion,
                hasSpeakerAnchor, speakerX, speakerY, speakerZ,
                transitionSec, diffracted, directPathMeters);
    }

    /** Full plan including per-path high-band retention for early reflections. */
    public RoomPlan(double bandHz, float wet, float dryGain, float loudness,
                    float predelaySec, float decaySec, float tone,
                    float decayLowSec, float decayHighSec, float diffusion,
                    float[] tapDelaySec, float[] tapGainL, float[] tapGainR, float[] tapHighRatio,
                    float directGain, double directCutoffHz, float directPan,
                    float directDistanceMeters,
                    boolean useOpenAlReverb, float reverbDecayHfRatio, float reverbDiffusion,
                    boolean hasSpeakerAnchor,
                    double speakerX, double speakerY, double speakerZ,
                    float transitionSec, boolean diffracted, float directPathMeters) {
        this.bandHz = finiteClamp(bandHz, SceneAcoustics.bandFloorHz(),
                SceneAcoustics.bandOpenHz(), SceneAcoustics.bandOpenHz());
        this.wet = (float) finiteClamp(wet, 0, 1, 0);
        this.dryGain = (float) finiteClamp(dryGain, 0, 1, 1);
        this.loudness = (float) finiteClamp(loudness, 0, 1, 1);
        this.predelaySec = (float) finiteClamp(predelaySec, 0, 0.060, 0);
        this.decaySec = (float) finiteClamp(decaySec, 0, 5.6, 0);
        this.tone = (float) finiteClamp(tone, 0.10, 0.45, SceneResponse.NEUTRAL_TONE);
        this.decayLowSec = (float) finiteClamp(decayLowSec, 0, 5.6, 0);
        this.decayHighSec = (float) finiteClamp(decayHighSec, 0, 5.6, 0);
        this.diffusion = (float) finiteClamp(diffusion, 0, 1, 1);
        final int baseCount = Math.min(192, Math.min(length(tapDelaySec),
                Math.min(length(tapGainL), length(tapGainR))));
        this.tapCount = baseCount;
        this.tapDelaySec = new float[tapCount];
        this.tapGainL = new float[tapCount];
        this.tapGainR = new float[tapCount];
        this.tapHighRatio = tapHighRatio == null ? new float[0] : new float[tapCount];
        for (int i = 0; i < tapCount; i++) {
            this.tapDelaySec[i] = (float) finiteClamp(tapDelaySec[i], 0, 0.24, 0);
            this.tapGainL[i] = (float) finiteClamp(tapGainL[i], 0, 2, 0);
            this.tapGainR[i] = (float) finiteClamp(tapGainR[i], 0, 2, 0);
            if (tapHighRatio != null) {
                this.tapHighRatio[i] = (float) finiteClamp(i < tapHighRatio.length
                        ? tapHighRatio[i] : 1f, 0, 1, 1);
            }
        }
        this.directGain = (float) finiteClamp(directGain, 0, 1, 1);
        this.directCutoffHz = finiteClamp(directCutoffHz, 200, DirectPath.FC_OPEN, DirectPath.FC_OPEN);
        this.directPan = DirectPath.clampPan(directPan);
        this.directDistanceMeters = (float) finiteClamp(directDistanceMeters, 0, 160, 0);
        this.directPathMeters = (float) finiteClamp(directPathMeters,
                this.directDistanceMeters, 168, this.directDistanceMeters);
        this.reverbDecayHfRatio = (float) finiteClamp(reverbDecayHfRatio, 0.1, 2, 1);
        this.reverbDiffusion = (float) finiteClamp(reverbDiffusion, 0, 1, 0.5);
        this.hasSpeakerAnchor = hasSpeakerAnchor && Double.isFinite(speakerX)
                && Double.isFinite(speakerY) && Double.isFinite(speakerZ);
        this.useOpenAlReverb = useOpenAlReverb && this.hasSpeakerAnchor;
        this.transitionSec = (float) finiteClamp(transitionSec, 0, 1.2, 0);
        this.diffracted = diffracted && this.hasSpeakerAnchor;
        this.speakerX = this.hasSpeakerAnchor ? speakerX : 0;
        this.speakerY = this.hasSpeakerAnchor ? speakerY : 0;
        this.speakerZ = this.hasSpeakerAnchor ? speakerZ : 0;
    }

    /** 只补过渡时长,不丢绕射标记、到达方向或路径长度。 */
    public RoomPlan withTransition(float seconds) {
        return new RoomPlan(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decayLowSec, decayHighSec, diffusion, tapDelaySec, tapGainL, tapGainR, tapHighRatio,
                directGain, directCutoffHz, directPan, directDistanceMeters,
                useOpenAlReverb, reverbDecayHfRatio, reverbDiffusion,
                hasSpeakerAnchor, speakerX, speakerY, speakerZ,
                seconds, diffracted, directPathMeters);
    }

    private static int length(float[] values) {
        return values == null ? 0 : values.length;
    }

    private static double finiteClamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    /** 静息计划:不改变音乐。 */
    public static RoomPlan idle() {
        return new RoomPlan(SceneAcoustics.bandOpenHz(), 0f, 1f, 1f, 0f, 0f,
                SceneResponse.NEUTRAL_TONE, 0f, 0f, 1f,
                new float[0], new float[0], new float[0]);
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
