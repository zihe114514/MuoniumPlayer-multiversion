package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界声源共享件的判定表:直射链纯数学({@link DirectPath})对表 + 状态
 * 与持久化({@link SpeakerState}/{@code SpeakerStore})的防御性往返。
 */
class DirectPathTest {

    @Test
    @DisplayName("距离衰减:8m 内全增益,之后按 inverse 模型平滑下滑")
    void distanceAttenuationTable() {
        assertEquals(1.0, DirectPath.distanceAttenuation(0.0), 1e-9);
        assertEquals(1.0, DirectPath.distanceAttenuation(8.0), 1e-9);
        // 24m:over=2 → 1/5
        assertEquals(0.20, DirectPath.distanceAttenuation(24.0), 1e-3);
        // 48m:over=5 → 1/26
        assertEquals(1.0 / 26.0, DirectPath.distanceAttenuation(48.0), 1e-3);
        // 96m:over=11 → 1/122 ≈ 0.0082(-41.7dB)
        assertTrue(DirectPath.distanceAttenuation(96.0) < 0.01,
                "96m=" + DirectPath.distanceAttenuation(96.0));
        // 单调下降,无突变(相邻格差有界)
        double prev = DirectPath.distanceAttenuation(0);
        for (int d = 1; d <= 96; d++) {
            double cur = DirectPath.distanceAttenuation(d);
            assertTrue(cur <= prev + 1e-12, "d=" + d);
            prev = cur;
        }
    }

    @Test
    @DisplayName("遮挡:无遮挡全带宽;一块实心墙 fc≈1kHz;两块起步即 200Hz 下限")
    void occlusionCutoffTable() {
        assertEquals(DirectPath.FC_OPEN, DirectPath.totalCutoffHz(0.0, 1.0), 1e-6);
        // 一块实心墙:ratio=exp(-3)≈0.0498 → fc≈996Hz
        final double one = DirectPath.totalCutoffHz(1.0, 1.0);
        assertEquals(20000.0 * Math.exp(-3.0), one, 5.0);
        // 两块:exp(-6)≈0.00248 → 496Hz,已贴 200Hz 下限之上
        final double two = DirectPath.totalCutoffHz(2.0, 1.0);
        assertTrue(two <= 500.0 && two >= 200.0, "两块墙=" + two);
        // 三块以上:夹到 200Hz 下限
        assertEquals(200.0, DirectPath.totalCutoffHz(3.0, 1.0), 1e-6);
        // 非实心遮挡(栅栏/玻璃 0.15):fc ≈ 12.7kHz,轻闷
        final double fence = DirectPath.totalCutoffHz(0.15, 1.0);
        assertTrue(fence > 10_000.0, "栅栏=" + fence);
    }

    @Test
    @DisplayName("空气吸收:近场不染色;96m 高频被吃(与 SceneAcoustics 同曲线)")
    void airAbsorption() {
        assertEquals(DirectPath.FC_OPEN, DirectPath.airAbsorptionFc(5.0), 1e-6);
        // 96m:18k·e^(-90/16)≈93 → 夹到 500Hz 下限
        assertEquals(500.0, DirectPath.airAbsorptionFc(96.0), 1e-6);
        // 30m:18k·e^(-24/16)≈3.86kHz,明显变闷
        assertTrue(DirectPath.airAbsorptionFc(30.0) > 3_000.0
                && DirectPath.airAbsorptionFc(30.0) < 5_000.0,
                "30m=" + DirectPath.airAbsorptionFc(30.0));
    }

    @Test
    @DisplayName("总截止取 min(遮挡,空气);总增益 = 距离 × 遮挡,范围 [0,1]")
    void combinedChain() {
        // 96m 无遮挡:空气吸收主导(fc=500)
        assertEquals(500.0, DirectPath.totalCutoffHz(0.0, 96.0), 1e-6);
        // 5m 一块墙:遮挡主导(fc≈1kHz)
        assertEquals(20000.0 * Math.exp(-3.0), DirectPath.totalCutoffHz(1.0, 5.0), 5.0);
        // 总增益:近场无遮挡 = 1;远处有遮挡 < 距离衰减
        assertEquals(1.0, DirectPath.directGain(5.0, 0.0), 1e-9);
        final double occluded = DirectPath.directGain(5.0, 1.0);
        assertEquals(Math.pow(Math.exp(-3.0), 0.1), occluded, 1e-6); // ×0.74
        assertTrue(DirectPath.directGain(100.0, 3.0) >= 0.0
                && DirectPath.directGain(100.0, 3.0) <= 1.0);
    }

    @Test
    @DisplayName("等功率声像:pan=0 恒等(gL=gR=1·√2/2·√2),全偏单耳 ×√2")
    void equalPowerPanning() {
        assertEquals(1.0, DirectPath.gainL(0f) * Math.sqrt(2.0), 1e-9);
        assertEquals(1.0, DirectPath.gainR(0f) * Math.sqrt(2.0), 1e-9);
        assertEquals(Math.sqrt(2.0), DirectPath.gainR(1f) * Math.sqrt(2.0), 1e-9);
        assertEquals(0.0, DirectPath.gainR(-1f), 1e-9);
        // NaN/越界夹回
        assertEquals(0f, DirectPath.clampPan(Float.NaN), 1e-9);
        assertEquals(1f, DirectPath.clampPan(9f), 1e-9);
        assertEquals(-1f, DirectPath.clampPan(-9f), 1e-9);
    }

    @Test
    @DisplayName("SpeakerState:放置拒绝 NaN;往返距离;sanitize 拒越界与坏维度串")
    void speakerStateDefensive() {
        assertNull(SpeakerState.place(Double.NaN, 0, 0, "minecraft:overworld"));
        assertNull(SpeakerState.place(0, Double.POSITIVE_INFINITY, 0, "minecraft:overworld"));
        assertNotNull(SpeakerState.place(1.5, -3.25, 64.0, "minecraft:overworld"));

        final SpeakerState s = SpeakerState.sanitize(10.0, 64.0, -5.0, "minecraft:overworld");
        assertNotNull(s);
        // (10,64,-5) → (13,64,-5):纯 X 差 3.0
        assertEquals(3.0, s.distanceTo(13.0, 64.0, -5.0), 1e-9);
        // 超世界边界、空/超长维度串 → null
        assertNull(SpeakerState.sanitize(4.0e7, 0, 0, "d"));
        assertNull(SpeakerState.sanitize(0, 0, 0, null));
        assertNull(SpeakerState.sanitize(0, 0, 0, ""));
        assertNull(SpeakerState.sanitize(0, 0, 0, "x".repeat(300)));

        // volatile 交接
        SpeakerState.set(s);
        assertTrue(SpeakerState.active());
        assertEquals(s, SpeakerState.get());
        SpeakerState.set(null);
        assertFalse(SpeakerState.active());
    }
}
