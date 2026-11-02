package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 直射声链的纯数学:从「源→听者」的几何量(距离、遮挡量、方向声像)推导
 * 直达声的增益 / 低通截止 / 左右分配。
 *
 * <p>全部静态纯函数,零状态 —— 可单测、可在采样线程调。公式出处(全网检索
 * 定稿):</p>
 * <ul>
 *   <li><b>距离衰减</b> = Steam Audio inverse 模型 {@code 1/(1+(d/ref−1)²)}
 *       (ref 内全增益,之外平滑下滑,边界无爆点);</li>
 *   <li><b>遮挡低通</b> = sonicether/Sound-Physics 的指数式
 *       {@code fcRatio = exp(−遮挡×3)}:实心方块遮挡量 1.0、非实心 0.15,
 *       一块实心墙 ≈ fc 1kHz,两块 ≈ 200Hz —— 穿墙的「闷」;</li>
 *   <li><b>空气吸收</b> = {@code 18kHz·e^(−(d−6m)/16m)},与
 *       {@link SceneAcoustics} 的抽头空气吸收同一条曲线(近场不染色);</li>
 *   <li><b>声像</b> = 等功率律,与 {@link SceneResponse} 抽头同款;M/S 域
 *       实现:中置分量(定位)按等功率对衰减分配,侧分量(立体声宽度)原样
 *       保留 —— pan=0 且满增益时逐样本恒等,无声源零染色。</li>
 * </ul>
 */
public final class DirectPath {

    /** 距离衰减的参考距离(米):以内全增益。 */
    private static final double ATTEN_REF_METERS = 8.0;
    /** 遮挡→截止的衰减率(Sound Physics 同款):一块实心墙 fc 降到 ≈1kHz。 */
    private static final double OCCLUSION_RATE = 3.0;
    /** 遮挡低通的截止下限(Hz):再闷也留低频体感。 */
    private static final double OCCLUSION_FC_MIN = 200.0;
    /** 直射空气吸收的截止下限(Hz)。 */
    private static final double AIR_FC_MIN = 500.0;
    /** 总截止下限(两者取 min 后再兜底):数值防御,理论到不了。 */
    private static final double TOTAL_FC_MIN = 200.0;
    /** 全开截止(Hz):等于这个视为「不压」,音频侧跳过滤波。 */
    public static final double FC_OPEN = 20000.0;
    /** 遮挡量里非实心方块(栅栏/玻璃/树叶)的记账比例(Sound Physics 0.15)。 */
    public static final float OCCLUSION_NON_SOLID = 0.15f;

    private DirectPath() {
    }

    /**
     * 距离衰减:ref(8m)内 1.0,之外 {@code 1/(1+(d/ref−1)²)}。
     * 8m=1.0、24m≈0.20、48m≈0.038、96m≈0.008(≈−42dB)。
     */
    public static double distanceAttenuation(double meters) {
        if (!(meters >= 0.0) || Double.isNaN(meters)) {
            return 0.0;
        }
        final double over = Math.max(0.0, meters / ATTEN_REF_METERS - 1.0);
        return 1.0 / (1.0 + over * over);
    }

    /**
     * 遮挡量 → 截止频率比例(0..1]:无遮挡 1.0,一块实心墙 exp(−3)≈0.05
     * (×20kHz = 1kHz)。负数/NaN 按无遮挡。
     */
    public static double occlusionFcRatio(double occlusion) {
        if (!(occlusion > 0.0)) {
            return 1.0;
        }
        return Math.exp(-occlusion * OCCLUSION_RATE);
    }

    /** 直射空气吸收的截止(Hz):6m 内不染色,之后指数下滑,下限 500Hz。 */
    public static double airAbsorptionFc(double meters) {
        if (!(meters > AIR_FREE)) {
            return FC_OPEN;
        }
        return Math.max(AIR_FC_MIN, AIR_FC_REF * Math.exp(-(meters - AIR_FREE) / AIR_DECAY));
    }

    /** 遮挡截止(Hz):{@code 20kHz × occlusionFcRatio},下限 200Hz。 */
    public static double occlusionFc(double occlusion) {
        return Math.max(OCCLUSION_FC_MIN, FC_OPEN * occlusionFcRatio(occlusion));
    }

    /** 总截止 = min(遮挡, 空气),再兜底;结果 ≥ {@link #FC_OPEN} 时返回 open。 */
    public static double totalCutoffHz(double occlusion, double meters) {
        final double fc = Math.min(occlusionFc(occlusion), airAbsorptionFc(meters));
        if (fc >= FC_OPEN) {
            return FC_OPEN;
        }
        return Math.max(TOTAL_FC_MIN, fc);
    }

    /**
     * 遮挡对增益的额外贡献:截止比例的 0.1 次幂(Sound Physics 同款)——
     * 一块实心墙增益 ×0.74,闷为主、弱为辅。
     */
    public static double occlusionGain(double occlusion) {
        return Math.pow(occlusionFcRatio(occlusion), 0.1);
    }

    /**
     * 直达声总增益(距离 × 遮挡),夹 [0,1]。
     */
    public static double directGain(double meters, double occlusion) {
        final double g = distanceAttenuation(meters) * occlusionGain(occlusion);
        return Math.max(0.0, Math.min(1.0, g));
    }

    /**
     * 直达声的声像:源相对听者在玩家右手方向上的水平投影,−1(正左)..1(正右)。
     * 由树侧算(它才有玩家朝向),这里只提供夹取。
     */
    public static float clampPan(float pan) {
        if (Float.isNaN(pan)) {
            return 0f;
        }
        return Math.max(-1f, Math.min(1f, pan));
    }

    /** 等功率左增益:pan −1..1 → cos((pan+1)π/4)。 */
    public static double gainL(float pan) {
        return Math.cos((clampPan(pan) + 1f) * (Math.PI / 4.0));
    }

    /** 等功率右增益:pan −1..1 → sin((pan+1)π/4)。 */
    public static double gainR(float pan) {
        return Math.sin((clampPan(pan) + 1f) * (Math.PI / 4.0));
    }

    // 与 SceneAcoustics 的空气吸收常量同源(复制一份避免跨类读私有;
    // 数值改动须两处同步 —— 常量处有注释互指)。
    private static final double AIR_FREE = 6.0;
    private static final double AIR_DECAY = 16.0;
    private static final double AIR_FC_REF = 18000.0;
}
