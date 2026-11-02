package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HistogramEstimator} 的数学正确性:合成已知 T60 的指数衰减直方图,
 * 拟合值应回收设定值;空/稀疏输入走稳健路径,不产生 NaN 或负值。
 *
 * <p><b>合成轨迹的能量域定义</b>:T60 = 能量衰到 10⁻⁶(−60dB)的时长 ⇒
 * τ = T60/(6·ln10)。用幅度域的 T60/(3·ln10) 会让轨迹真实 T60 翻倍,
 * 测出来全是 2× 误差 —— 第一次写这个测试时踩过。</p>
 */
class HistogramEstimatorTest {

    /** 灌一个频带的指数轨迹(能量域 τ = T60/(6ln10))。 */
    private static void fillBand(EnergyField f, int band, double amplitude, double t60Sec) {
        final double tau = t60Sec / (6.0 * Math.log(10.0));
        for (int i = 0; i < EnergyField.BINS; i++) {
            final double t0 = i * EnergyField.BIN_DURATION_SEC;
            final double t1 = t0 + EnergyField.BIN_DURATION_SEC;
            final double e = amplitude * (Math.exp(-t0 / tau) - Math.exp(-t1 / tau));
            if (e > 0) {
                f.accumulate(band, t0 + EnergyField.BIN_DURATION_SEC / 2, e);
            }
        }
    }

    private static EnergyField exponentialField(double t60Sec, double amplitude) {
        final EnergyField f = new EnergyField();
        fillBand(f, 1, amplitude, t60Sec);
        return f;
    }

    @Test
    @DisplayName("指数衰减直方图:T60 拟合回收设定值(窗长覆盖的范围内 ±12%)")
    void recoversKnownT60() {
        // 2s 观察窗完整覆盖拟合窗的 T60 上限 ≈ 0.42·2s ≈ 0.84s?不对 ——
        // 拟合窗起点在 t=T60·0.5/6,终点 T60·2.5/6;终点要 < 2s ⇒ T60 < 4.8s。
        // 但窗尾段能量弱、点少,实测 4.5s 误差 ~8.5%,这里按 0.4/1.0/2.4 三个
        // 覆盖完好的点断言 ±12%。
        for (double t60 : new double[] {0.4, 1.0, 2.4}) {
            final HistogramEstimator.Result r = HistogramEstimator.estimate(exponentialField(t60, 1.0));
            assertNotNull(r, "t60=" + t60);
            assertTrue(Math.abs(r.decayMidSec - t60) / t60 < 0.12,
                    "t60=" + t60 + " 拟合=" + r.decayMidSec);
        }
    }

    @Test
    @DisplayName("三频带独立:低频衰减更长、高频更短,各自回收")
    void bandsAreIndependent() {
        final EnergyField f = new EnergyField();
        fillBand(f, 0, 1.0, 2.8);
        fillBand(f, 1, 1.0, 1.5);
        fillBand(f, 2, 1.0, 0.7);
        final HistogramEstimator.Result r = HistogramEstimator.estimate(f);
        assertNotNull(r);
        assertTrue(Math.abs(r.decayLowSec - 2.8) / 2.8 < 0.15, "low=" + r.decayLowSec);
        assertTrue(Math.abs(r.decayMidSec - 1.5) / 1.5 < 0.12, "mid=" + r.decayMidSec);
        assertTrue(Math.abs(r.decayHighSec - 0.7) / 0.7 < 0.15, "high=" + r.decayHighSec);
        assertTrue(r.decayLowSec > r.decayMidSec && r.decayMidSec > r.decayHighSec,
                "低 > 中 > 高 的物理顺序");
    }

    @Test
    @DisplayName("空直方图:返回 null(调用方静息)")
    void emptyFieldIsNull() {
        assertNull(HistogramEstimator.estimate(new EnergyField()));
        assertNull(HistogramEstimator.estimate(null));
    }

    @Test
    @DisplayName("稀疏直方图(只有头一桶):不拟合、不 NaN、比例量有界")
    void sparseFieldIsSafe() {
        final EnergyField f = new EnergyField();
        f.accumulate(1, 0.005, 1.0);
        final HistogramEstimator.Result r = HistogramEstimator.estimate(f);
        if (r != null) {
            assertTrue(Double.isFinite(r.decayMidSec) && r.decayMidSec >= 0.0);
            assertTrue(Double.isFinite(r.earlyFraction) && r.earlyFraction >= 0.0 && r.earlyFraction <= 1.0);
            assertTrue(Double.isFinite(r.diffusion) && r.diffusion > 0.0 && r.diffusion <= 1.0);
        }
    }

    @Test
    @DisplayName("早期/晚期分割:能量全在前 80ms → earlyFraction≈1;长尾 → 明显小于 1")
    void earlyFractionSeparatesEarlyFromLate() {
        final EnergyField allEarly = new EnergyField();
        allEarly.accumulate(1, 0.01, 1.0);
        allEarly.accumulate(1, 0.03, 1.0);
        final HistogramEstimator.Result early = HistogramEstimator.estimate(allEarly);
        assertNotNull(early);
        assertTrue(early.earlyFraction > 0.95, "early=" + early.earlyFraction);

        final HistogramEstimator.Result late = HistogramEstimator.estimate(exponentialField(2.0, 1.0));
        assertNotNull(late);
        // 2s 窗下 T60=2.0 的轨迹在 80ms 早期窗内的能量占比 ≈ 0.42(解析:
        // 1−e^{−0.08/τ}),断言只排除「能量挤在前段」的病态分布。
        assertTrue(late.earlyFraction < 0.5, "长尾的早期能量占比应明显小于 1: " + late.earlyFraction);
    }

    @Test
    @DisplayName("扩散度:单桶尖峰 → 低;指数尾 → 高")
    void diffusionDistinguishesSparseFromDense() {
        final EnergyField spike = new EnergyField();
        spike.accumulate(1, 0.05, 1.0);
        final HistogramEstimator.Result sparse = HistogramEstimator.estimate(spike);
        assertNotNull(sparse);
        assertTrue(sparse.diffusion < 0.1, "尖峰扩散=" + sparse.diffusion);

        final HistogramEstimator.Result dense = HistogramEstimator.estimate(exponentialField(1.0, 1.0));
        assertNotNull(dense);
        assertTrue(dense.diffusion > 0.1, "指数尾扩散=" + dense.diffusion);
    }

    @Test
    @DisplayName("越界累积被忽略:负能量/超窗时间/坏频带号不炸")
    void accumulateBoundsAreSafe() {
        final EnergyField f = new EnergyField();
        f.accumulate(-1, 0.01, 1.0);
        f.accumulate(3, 0.01, 1.0);
        f.accumulate(1, -0.5, 1.0);
        f.accumulate(1, 99.0, 1.0);
        f.accumulate(1, 0.01, -1.0);
        f.accumulate(1, 0.01, 0.0);
        assertTrue(f.isEmpty(), "全部越界 = 什么都没进");
    }
}
