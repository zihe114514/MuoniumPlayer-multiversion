package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 能量直方图 → 逐频带混响参数的估计器。
 *
 * <p>算法整体移植自 Steam Audio 的 {@code reverb_estimator.cpp}(Valve,Apache-2.0):
 * 能量直方图按频带做 Schroeder 反向积分得到能量衰减曲线(EDC),在 log 能量域
 * 对 −2.5dB..−0.5dB 的窗做最小二乘直线拟合,斜率即逐频带混响时间。这是
 * 「多频带能量直方图 → 混响参数」的权威参考实现 —— 我们不发明数学,只把它
 * 接到 Minecraft 的方块世界上。</p>
 *
 * <h2>与 Steam Audio 原版的差异(都有物理理由)</h2>
 * <ul>
 *   <li>原版拟合前给能量乘空气吸收权重(时间越靠后的桶吃越多空气衰减);
 *       我们不乘 —— 树侧入桶时已经按分频带空气吸收折减过,再乘一次是重复扣。</li>
 *   <li>原版对 I3DL2 全参数族(Room/RoomLow/Reflections/Diffusion/…)做估计;
 *       我们只要混响需要的四样:三频带 T60 + 早期/晚期能量分割。
 *       diffusion 的「桶占比」定义保留 —— 它驱动 FDN 的混合。</li>
 *   <li>拟合窗内的点数少于 4 时(直方图太空、衰减太陡)不硬拟合,回退到
 *       「由弹射段均值 × 反射率」的解析式 —— 这是旧版 SceneResponse 已验证
 *       过的兜底路径,稀疏直方图不至于比旧版更差。</li>
 * </ul>
 */
public final class HistogramEstimator {

    /** 拟合窗(log 能量域,dB):Steam Audio 原值 —— 前段噪声大,后段太弱。 */
    private static final double FIT_MIN_DB = -2.5;
    private static final double FIT_MAX_DB = -0.5;
    /** 拟合窗内最少点数:少于它不做最小二乘,走解析兜底。 */
    private static final int MIN_FIT_POINTS = 4;
    /** 扩散度阈值:桶能量低于全窗峰值此比例视为「空桶」(原版是绝对阈值,比例版跨场景稳)。 */
    private static final double DIFFUSION_REL_THRESHOLD = 1e-4;

    private HistogramEstimator() {
    }

    /** 一轮估计的产出:三频带 T60 + 早期/晚期能量分割 + 扩散度。 */
    public static final class Result {
        public final double decayLowSec;
        public final double decayMidSec;
        public final double decayHighSec;
        /** 早期能量占比 0..1(前 80ms 窗内的能量 / 全窗能量)。 */
        public final double earlyFraction;
        /** 扩散度 0..1:有能量的桶占比 —— 稀疏回声(离散抽头)低,漫散射高。 */
        public final double diffusion;

        Result(double decayLowSec, double decayMidSec, double decayHighSec,
               double earlyFraction, double diffusion) {
            this.decayLowSec = decayLowSec;
            this.decayMidSec = decayMidSec;
            this.decayHighSec = decayHighSec;
            this.earlyFraction = earlyFraction;
            this.diffusion = diffusion;
        }
    }

    /**
     * 估计一轮。空直方图返回 null(调用方静息);单频带有能量、拟合点不足时
     * 该频带回退到中频带的值(低频衰减普遍更长,拿中频兜底比拿 0 兜底自然)。
     */
    public static Result estimate(EnergyField field) {
        if (field == null || field.isEmpty()) {
            return null;
        }

        final double mid = reverbTime(field, 1);
        double low = reverbTime(field, 0);
        double high = reverbTime(field, 2);
        if (mid <= 0.0) {
            // 中频没拟合出来但直方图非空:能量全挤在早期、没有衰减斜率可读。
            // 三频带全走解析兜底语义(返回 0,SceneResponse 有最终解析式)。
            low = 0.0;
            high = 0.0;
        } else {
            if (low <= 0.0) {
                low = mid;
            }
            if (high <= 0.0) {
                high = mid;
            }
        }

        return new Result(low, mid, high, earlyFraction(field), diffusion(field));
    }

    /**
     * 单频带 T60:Schroeder 反向积分 EDC(bin=19 往回累加),归一并取 log10,
     * 拟合窗内的点做最小二乘;斜率 s(dB/秒)→ T60 = −60/s。与原版逐行同构。
     */
    private static double reverbTime(EnergyField field, int band) {
        double totalEnergy = 0.0;
        for (int i = 0; i < EnergyField.BINS; i++) {
            totalEnergy += field.get(band, i);
        }
        if (totalEnergy < 1e-6) {
            return 0.0;
        }

        double energy = 0.0;
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXX = 0.0;
        double sumXY = 0.0;
        int n = 0;

        for (int i = EnergyField.BINS - 1; i >= 0; i--) {
            energy += field.get(band, i);
            // 桶中心时刻:x 坐标取桶中点,比取桶首更贴合「能量平均何时到达」
            final double x = (i + 0.5) * EnergyField.BIN_DURATION_SEC;
            final double y = Math.log10(energy / totalEnergy);

            if (FIT_MIN_DB <= y && y <= FIT_MAX_DB) {
                sumX += x;
                sumY += y;
                sumXX += x * x;
                sumXY += x * y;
                n++;
            }
        }

        if (n < MIN_FIT_POINTS) {
            return 0.0;
        }
        final double numerator = n * sumXY - sumX * sumY;
        final double denominator = n * sumXX - sumX * sumX;
        // 斜率 = numerator/denominator(每秒 log10 能量)。直方图桶存的是
        // 「能量」量:能量域 −60dB ⇔ log10 从 0 掉到 −6(注意不是幅度域的
        // 20log10 → −3;Steam Audio 原版同为 −6)⇒ T60 = −6/斜率。
        return Math.abs(numerator) > 1e-12
                ? Math.max(0.0, -6.0 * denominator / numerator)
                : 0.0;
    }

    /** 早期能量占比:前 80ms(4 桶)能量 / 全窗能量,中频带。 */
    private static double earlyFraction(EnergyField field) {
        double early = 0.0;
        double all = 0.0;
        for (int i = 0; i < EnergyField.BINS; i++) {
            final double e = field.get(1, i);
            all += e;
            if (i * EnergyField.BIN_DURATION_SEC < EARLY_WINDOW_SEC) {
                early += e;
            }
        }
        return all > 0.0 ? Math.max(0.0, Math.min(1.0, early / all)) : 1.0;
    }

    /**
     * 扩散度:中频带里「能量显著」的桶占观察窗的比例。离散强回声(大空房、
     * 峡谷)只点亮少数桶 → 低扩散;小房间/软面散射把能量摊满 → 高扩散。
     */
    private static double diffusion(EnergyField field) {
        double peak = 0.0;
        for (int i = 0; i < EnergyField.BINS; i++) {
            peak = Math.max(peak, field.get(1, i));
        }
        if (peak <= 0.0) {
            return 1.0;
        }
        final double threshold = peak * DIFFUSION_REL_THRESHOLD;
        int lit = 0;
        for (int i = 0; i < EnergyField.BINS; i++) {
            if (field.get(1, i) >= threshold) {
                lit++;
            }
        }
        return (double) lit / EnergyField.BINS;
    }

    /** 早期反射窗(秒):SceneAcoustics 的 ER 窗(0.16s)内、优先效应主导的
     *  前 80ms —— 直方图侧的「早期」按感知定义取更短的一档。 */
    public static final double EARLY_WINDOW_SEC = 0.08;
}
