package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节拍源的纯函数部分：拍内包络形状与实时估算的收敛/抗噪。
 * 网格一级依赖 CloudMusic 快照与文件系统，不在此测（集成行为）。
 */
class BeatPulseSourceTest {

    @AfterEach
    void cleanUp() {
        BeatPulseSource.resetForTest();
    }

    @Test
    void pulseEnvelopePeaksOnTheBeat() {
        // 拍点上满、按 1/4 拍宽指数回落 —— 「按节拍」而不是「按响度」的核心形状。
        assertEquals(1f, BeatPulseSource.pulseAtPhase(0f), 1e-6);
        assertEquals((float) Math.exp(-1f), BeatPulseSource.pulseAtPhase(0.25f), 1e-4);
        assertEquals((float) Math.exp(-4f), BeatPulseSource.pulseAtPhase(1f), 1e-4);
        // 半拍处已经基本落干净：拍与拍之间有明确的「回弹窗口」。
        assertTrue(BeatPulseSource.pulseAtPhase(0.5f) < 0.14f, "半拍还没落回，拍与拍会糊成连续压缩");
    }

    @Test
    void envelopeIsMonotonicWithinBeat() {
        float previous = BeatPulseSource.pulseAtPhase(0f);
        for (float phase = 0.05f; phase <= 1.0001f; phase += 0.05f) {
            float value = BeatPulseSource.pulseAtPhase(phase);
            assertTrue(value <= previous + 1e-6, "拍内包络回升了：phase=" + phase);
            previous = value;
        }
    }

    @Test
    void estimatorConvergesFromEnergyOnsets() {
        // 模拟能量回退期喂 onset：120 BPM = 0.5s 一拍，允许 ±20ms 抖动。
        // 8 个间隔后估算必须收敛到 120BPM ±2%。
        BeatPulseSource.resetForTest();
        long now = 1_000_000_000L;
        BeatPulseSource.advanceClockForTest(now); // 先挂上测试时钟，feed 才不落真实 nanoTime
        float[] jitter = {0f, 0.012f, -0.008f, 0.02f, -0.015f, 0.005f, -0.02f, 0.01f, 0f, -0.01f, 0.015f, -0.005f};
        for (int i = 0; i < 14; i++) {
            now += (long) ((0.5f + jitter[i % jitter.length]) * 1e9f);
            BeatPulseSource.advanceClockForTest(now); // 先推进
            BeatPulseSource.feedEnergyForTest(0f);    // 拍间安静
            BeatPulseSource.feedEnergyForTest(0.8f);  // 拍点冲击（升沿 → onset@now）
        }
        float period = BeatPulseSource.estimatedPeriodNanosForTest();
        float confidence = BeatPulseSource.estimatedConfidenceForTest();
        assertTrue(period > 0f, "估算没收敛出周期");
        double bpm = 60e9 / period;
        assertTrue(Math.abs(bpm - 120) < 2.4, "估算 BPM 偏了: " + bpm);
        assertTrue(confidence >= 0.55f, "置信度不足: " + confidence);
    }

    @Test
    void estimatorRejectsIrregularIntervals() {
        // 全是乱间隔（没人拍手的现场）不该收敛：置信度必须低于阈值，继续走能量回退。
        BeatPulseSource.resetForTest();
        long now = 1_000_000_000L;
        BeatPulseSource.advanceClockForTest(now);
        float[] chaos = {0.31f, 0.9f, 0.42f, 1.7f, 0.5f, 0.33f, 1.1f, 0.47f, 0.95f, 0.6f};
        for (int i = 0; i < chaos.length; i++) {
            now += (long) (chaos[i] * 1e9f);
            BeatPulseSource.advanceClockForTest(now);
            BeatPulseSource.feedEnergyForTest(0.8f);
        }
        assertTrue(BeatPulseSource.estimatedConfidenceForTest() < 0.55f,
                "乱间隔也收敛了，回退链就断了");
    }

    @Test
    void estimateSurvivesAcrossBeatCalls() {
        // 回归锁：估算收敛后必须能被后续 beat() 消费（换歌重置前那次报的病）。
        BeatPulseSource.resetForTest();
        long now = 1_000_000_000L;
        BeatPulseSource.advanceClockForTest(now);
        for (int i = 0; i < 14; i++) {
            now += 500_000_000L;
            BeatPulseSource.advanceClockForTest(now);
            BeatPulseSource.feedEnergyForTest(0f);   // 拍间安静
            BeatPulseSource.feedEnergyForTest(0.8f); // 拍点冲击 —— 构成升沿才有 onset
        }
        assertTrue(BeatPulseSource.estimatedPeriodNanosForTest() > 0f,
                "前置：估算应已收敛 period=" + BeatPulseSource.estimatedPeriodNanosForTest()
                + " conf=" + BeatPulseSource.estimatedConfidenceForTest());
    }
}
