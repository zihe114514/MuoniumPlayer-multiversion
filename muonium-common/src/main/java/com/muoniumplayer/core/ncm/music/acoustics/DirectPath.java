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
    /** 非实心材料每单位遮挡的增益衰减(dB):0.15(单层玻璃/树叶)≈ −0.9dB,
     *  「能听见但发闷」的量级。 */
    private static final double OCCLUSION_SOFT_DB_PER_UNIT = 6.0;
    /** 实心整块每块的透射损失(dB)。
     *
     *  <p>实测参考:200mm 混凝土墙对 500Hz~1kHz 的声压级衰减在 45~55dB
     *  量级,砖墙 40~50dB。游戏里一个方块约等于 1m 厚的实心介质 —— 取
     *  40dB 是保守值(真实会更高,但那会把「墙后的音乐」压到完全无声,
     *  听感上反而不如留一点残余自然)。</p>
     *
     *  <p>这个值决定绕射是否有意义:绕射贴边绕过约 −20dB,若穿透给到
     *  −40dB,绕射就是唯一能听见的路径 —— 这正是真实世界「隔着墙能听见
     *  声音从墙角绕过来」的由来。</p> */
    private static final double SOLID_TRANSMISSION_DB_PER_BLOCK = 40.0;
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
     * 遮挡对增益的额外贡献。
     *
     * <p><b>物理</b>:只有「薄、轻、多孔」的材料才真正透声(玻璃/树叶/栅栏
     * —— 记账 0.15 那档);<b>一整块实心石头/泥土不透声</b>,声音要到听者
     * 那里只能绕过边缘(绕射)。修前用同一个 {@code fc^0.1} 曲线处理两者:
     * 一块实心墙还有 ×0.74 的增益 —— 听起来像「隔着一层布」,而不是「隔着
     * 一堵墙」;也正因为穿透给得太大,绕射路径永远赢不了它,整个绕射机制
     * 形同虚设。</p>
     *
     * <p>现在按遮挡量分段:非实心(≤{@link #OCCLUSION_SOFT_LIMIT})走原来的
     * 弱衰减;超出部分(实心整块)按 {@link #SOLID_TRANSMISSION_DB_PER_BLOCK}
     * 逐块掉 dB —— 一块实心方块 ≈ −24dB,两块 ≈ −48dB,基本等于不透声。</p>
     */
    public static double occlusionGain(double occlusion) {
        if (!(occlusion > 0.0)) {
            return 1.0;
        }
        // 探针的记账口径:实心整块 = 1.0,非实心(栅栏/玻璃/树叶)= 0.15。
        // 累计量里两者混在一起,按「整块数 + 非实心数」拆开:
        //   occl = 2.3  → 2 块实心 + 0.3 的非实心贡献
        // 低于 1 的遮挡(单层玻璃/树叶)整体按软材料处理。
        double db;
        if (occlusion < 1.0) {
            // 纯非实心:轻质透声材料,按比例弱衰减
            db = occlusion * OCCLUSION_SOFT_DB_PER_UNIT;
        } else {
            final double wholeBlocks = Math.floor(occlusion);
            final double remainder = occlusion - wholeBlocks;
            db = wholeBlocks * SOLID_TRANSMISSION_DB_PER_BLOCK
                    + remainder * OCCLUSION_SOFT_DB_PER_UNIT;
        }
        return Math.pow(10.0, -db / 20.0);
    }

    /**
     * 直达声总增益(距离 × 遮挡),夹 [0,1]。
     */
    public static double directGain(double meters, double occlusion) {
        final double g = distanceAttenuation(meters) * occlusionGain(occlusion);
        return Math.max(0.0, Math.min(1.0, g));
    }

    // ════════════════ 遮挡边缘绕射(一阶) ════════════════

    /**
     * 绕射衰减系数:声波绕过一条边缘时的能量损失(0..1,1 = 无损失)。
     *
     * <p><b>公式出处</b>:Maekawa 经验式 {@code IL = 10·log10(3 + 20N)},
     * {@code N = 2δ/λ}(δ = 绕射路径相对直达的路程差,λ = 波长)。这是
     * ISO 9613-2 引用的屏障绕射标准算法,也是 Wwise/Steam Audio 这一族
     * 几何声学实现所用的同一关系。1m 路程差 @1kHz 时 N≈5.8 → IL≈21dB。</p>
     *
     * <p>折角修正:Maekawa 原式只描述「越过屏障顶端」,不含横向绕墙角的
     * 额外损失。这里再乘一个随折角衰减的因子 —— 拐得越狠,高频越先掉。</p>
     *
     * @param pathExcessMeters 绕射路径比直达直线多走的路程(米,≥0)
     * @param bendDegrees      折角(度,0 = 直线,180 = 绕过整面墙)
     * @return 0..1 的幅度系数
     */
    public static double diffractionGain(double pathExcessMeters, double bendDegrees) {
        if (Double.isNaN(pathExcessMeters)) {
            return 0.0;
        }
        final double excess = Math.max(0.0, pathExcessMeters);
        // 零路程差 = 贴着边缘擦过,不构成绕射损失。
        // (Maekawa 式在 N=0 时给 IL=10·log10(3)≈4.8dB,那是「声影区边缘」
        // 的惩罚;但我们的 excess=0 语义是「路径与直达同长」,不该罚。)
        if (excess <= 1e-6) {
            return 1.0;
        }
        // Fresnel 数 N = 2δ/λ;λ 取中频(1kHz)波长,混响标定以此为准。
        final double fresnel = 2.0 * excess / MID_WAVELENGTH_METERS;
        // Maekawa:IL = 10·log10(3 + 20N),夹上限 25dB(ISO 9613-2 与
        // FHWA 都对此类薄屏障给出 20~25dB 的实际上限)。
        final double insertionLossDb = Math.min(DIFFRACTION_MAX_IL_DB,
                10.0 * Math.log10(3.0 + 20.0 * fresnel));
        double amplitude = Math.pow(10.0, -insertionLossDb / 20.0);
        // 折角:Maekawa 不含横向绕角,补一个单调衰减。
        final double bend = Double.isNaN(bendDegrees)
                ? 0.0 : Math.max(0.0, Math.min(180.0, bendDegrees));
        amplitude *= Math.exp(-DIFFRACTION_BEND_RATE * (bend / 180.0));
        return Math.max(0.0, Math.min(1.0, amplitude));
    }

    /**
     * 绕射路径的低通截止(Hz):绕过边缘的高频比低频损失多 —— 这是「隔着
     * 墙角听到闷声」的来源,与遮挡低通是两条独立机理。
     *
     * @param pathExcessMeters 绕射路径的额外出程
     */
    public static double diffractionCutoffHz(double pathExcessMeters) {
        final double excess = Math.max(0.0, pathExcessMeters);
        // 2m 内基本不染色;之后指数下滑,下限 400Hz(比遮挡的 200Hz 高 ——
        // 绕射比穿墙保留更多中频)。
        if (excess <= DIFFRACTION_FREE_METERS) {
            return FC_OPEN;
        }
        return Math.max(DIFFRACTION_FC_MIN_HZ,
                FC_OPEN * Math.exp(-(excess - DIFFRACTION_FREE_METERS) / DIFFRACTION_FC_DECAY));
    }

    /**
     * 直射声的<b>有效增益</b>:直达通畅走原路;被完全遮挡但找到绕射路径时,
     * 取绕射路径的增益(通常比穿墙高一个量级,听感从「变小」变「从墙角过来」)。
     *
     * <p>取两者较大而不是相加:同一声源不会「一半穿墙一半绕过」(那会凭空
     * 加倍能量)。绕射路径一旦成立,它就是主路径。</p>
     *
     * @param directMeters     直达直线路程
     * @param occlusion        穿透遮挡量(0 = 无遮挡)
     * @param hasDiffraction   是否找到绕射路径
     * @param diffractionMeters 绕射路径全长(源→边缘→耳)
     * @param pathExcess       绕射相对直达的额外出程(≥0)
     * @param bendDegrees      折角
     */
    public static double directGain(double directMeters, double occlusion,
                                    boolean hasDiffraction, double diffractionMeters,
                                    double pathExcess, double bendDegrees) {
        final double straight = directGain(directMeters, occlusion);
        if (!hasDiffraction) {
            return straight;
        }
        final double diffracted = Math.min(1.0,
                distanceAttenuation(Math.max(0.0, diffractionMeters))
                        * diffractionGain(pathExcess, bendDegrees));
        return Math.max(straight, diffracted);
    }

    /**
     * 直射声的<b>有效截止</b>:与 {@link #directGain} 同口径 —— 走绕射时
     * 用绕射低通,取两者较高(高频保留更多的那条路径赢)。
     */
    public static double totalCutoffHz(double occlusion, double meters,
                                       boolean hasDiffraction, double pathExcess) {
        final double straight = totalCutoffHz(occlusion, meters);
        if (!hasDiffraction) {
            return straight;
        }
        final double diffracted = diffractionCutoffHz(pathExcess);
        return Math.max(straight, diffracted);
    }

    /** 中频参考波长(米):343 m/s ÷ 1000 Hz。Fresnel 参数的分母。 */
    private static final double MID_WAVELENGTH_METERS = 0.343;
    /** Maekawa 插入损失的实际上限(dB):ISO 9613-2 与 FHWA 对薄屏障均
     *  给出 20~25dB 的上限,再大也听不出来了。 */
    private static final double DIFFRACTION_MAX_IL_DB = 25.0;
    /** 折角对绕射衰减的额外强度。
     *
     *  <p>Maekawa 式只描述「越过屏障顶端」的插入损失,横向绕墙角的额外损失
     *  在实测里通常只有 3~6dB(ISO 9613-2 对有限长屏障给的修正量级),
     *  远小于越过屏障本身。取 0.35 使 180° 全绕墙角时额外 ×0.70(≈ −3dB)
     *  —— 足够让「贴边绕过」优于「绕远半圈」,又不至于把绕射压到比穿透还弱。</p> */
    private static final double DIFFRACTION_BEND_RATE = 0.35;
    /** 绕射染色免区(米):贴边绕过的中高频基本原样。 */
    private static final double DIFFRACTION_FREE_METERS = 2.0;
    /** 绕射低通的下限(Hz):比遮挡的 200Hz 高,绕射保留更多中频。 */
    private static final double DIFFRACTION_FC_MIN_HZ = 400.0;
    /** 绕射低通的衰减尺度(米)。 */
    private static final double DIFFRACTION_FC_DECAY = 6.0;

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
