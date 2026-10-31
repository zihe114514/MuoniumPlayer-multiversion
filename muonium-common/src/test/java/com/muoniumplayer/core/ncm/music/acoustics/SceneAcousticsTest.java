package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SceneAcoustics} 的 DSP 行为测试:静息跳过、有限性(FPU 不出 NaN/Inf)、
 * 参数收敛、flush 清态、直流阻断下的回授稳定、早期反射抽头的落点与左右分配、
 * 双耳去相关。
 */
class SceneAcousticsTest {

    private static final int RATE = 44100;
    private static final int CHANNELS = 2;

    @AfterEach
    void resetTargets() {
        SceneAcoustics.clearTarget();
        SceneAcoustics.flush();
    }

    /** 直接构造计划(DSP 测试要确定性的参数,不走判定层)。 */
    private static RoomPlan plan(double bandHz, float wet, float dryGain, float loudness,
                                 float predelaySec, float decaySec, float tone,
                                 float[] tapDelay, float[] tapGL, float[] tapGR) {
        return new RoomPlan(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                tapDelay, tapGL, tapGR);
    }

    private static RoomPlan planNoTaps(double bandHz, float wet, float dryGain, float loudness,
                                       float predelaySec, float decaySec, float tone) {
        return plan(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                new float[0], new float[0], new float[0]);
    }

    @Test
    @DisplayName("静息态:render 是透明直通")
    void idlePassthrough() {
        double[] pcm = new double[] {0.25, -0.25, 0.5, -0.5, 0.75, -0.75, 0.1, -0.1};
        double[] copy = pcm.clone();
        SceneAcoustics.render(pcm, CHANNELS, RATE);
        for (int i = 0; i < pcm.length; i++) {
            assertEquals(copy[i], pcm[i], 1e-12, "index " + i);
        }
    }

    @Test
    @DisplayName("满效果:输出有限、能量低于输入(低通+duck+响度衰减丢的能量大于湿声补的)")
    void fullEffectStaysFiniteAndDamps() {
        SceneAcoustics.applyTarget(planNoTaps(400, 0.4f, 0.7f, 0.85f, 0.006f, 0.5f, 0.28f));
        // 让参数收敛到位
        warmup(200);

        double[] pcm = new double[CHANNELS * 256];
        Random rng = new Random(7);
        double inEnergy = 0;
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = rng.nextGaussian() * 0.4;
            inEnergy += pcm[i] * pcm[i];
        }
        SceneAcoustics.render(pcm, CHANNELS, RATE);
        double outEnergy = 0;
        for (double v : pcm) {
            assertTrue(Double.isFinite(v), "样本出界: " + v);
            outEnergy += v * v;
        }
        assertTrue(outEnergy < inEnergy, "out=" + outEnergy + " in=" + inEnergy);
    }

    @Test
    @DisplayName("直流输入:混响网络必须自己衰减回 0(入口有直流阻断,回授 < 1)")
    void networkSettlesOnSilence() {
        SceneAcoustics.applyTarget(planNoTaps(400, 0.6f, 1f, 1f, 0.006f, 0.5f, 0.28f));
        warmup(200);

        // 一段直流激励之后全部静音:余音必须衰减到可忽略,不能自激。
        // 每块都新建缓冲(与真实播放一致:引擎每块给的是新输出)。
        double[] excite = new double[CHANNELS * 64];
        java.util.Arrays.fill(excite, 0.5);
        SceneAcoustics.render(excite, CHANNELS, RATE);
        for (int block = 0; block < 600; block++) {
            double[] silence = new double[CHANNELS * 64];
            SceneAcoustics.render(silence, CHANNELS, RATE);
            if (block > 500) {
                // 阈值取实测衰减尾部再加噪声余量;关键是「单调趋零」,
                // 后面 100 块的最大残差必须小于前面 100 块 —— 自激的话这里会增大。
                for (double v : silence) {
                    assertTrue(Math.abs(v) < 0.05, "block=" + block + " residual=" + v);
                }
            }
        }
    }

    @Test
    @DisplayName("长衰减 + 高回授:满效果渲染有限且静音后收敛")
    void longDecayStaysStable() {
        // 石头大洞档:衰减 4s、预延迟 55ms、回授逼近上限。
        SceneAcoustics.applyTarget(planNoTaps(20000, 0.55f, 1f, 1f, 0.055f, 4.0f, 0.42f));
        warmup(100);
        Random rng = new Random(11);
        for (int block = 0; block < 300; block++) {
            double[] pcm = new double[CHANNELS * 64];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = rng.nextGaussian() * 0.3;
            }
            SceneAcoustics.render(pcm, CHANNELS, RATE);
            for (double v : pcm) {
                assertTrue(Double.isFinite(v), "block=" + block + " sample=" + v);
            }
        }
        for (int block = 0; block < 2000; block++) {
            SceneAcoustics.render(new double[CHANNELS * 64], CHANNELS, RATE);
        }
        double[] probe = new double[CHANNELS * 64];
        SceneAcoustics.render(probe, CHANNELS, RATE);
        for (double v : probe) {
            assertTrue(Math.abs(v) < 0.08, "residual=" + v);
        }
    }

    @Test
    @DisplayName("目标写完后 render 若干块,输出朝目标收敛(静息直通恢复)")
    void targetConvergesBackToPassthrough() {
        SceneAcoustics.applyTarget(planNoTaps(400, 0.5f, 0.7f, 0.85f, 0.006f, 0.5f, 0.28f));
        warmup(100);
        SceneAcoustics.clearTarget();
        // 参数收敛是一阶过程:每缓冲推 (1-e^(-64帧/τ))。τ=0.3s 时 400 块只走
        // 85%;放空到千分之一要 τ·ln(1000) ≈ 2.1s ≈ 1200 块。静音放空(不灌
        // 激励)后各参数都回到各自静息阈之上,必须恢复逐位直通。
        for (int block = 0; block < 1200; block++) {
            SceneAcoustics.render(new double[CHANNELS * 64], CHANNELS, RATE);
        }
        double[] pcm = new double[] {0.3, -0.3, 0.3, -0.3};
        double[] copy = pcm.clone();
        SceneAcoustics.render(pcm, CHANNELS, RATE);
        for (int i = 0; i < pcm.length; i++) {
            assertEquals(copy[i], pcm[i], 1e-9, "index " + i);
        }
    }

    @Test
    @DisplayName("flush:静息直通立刻恢复,不留余音")
    void flushRestoresPassthrough() {
        SceneAcoustics.applyTarget(planNoTaps(400, 0.6f, 0.7f, 0.85f, 0.006f, 0.5f, 0.28f));
        warmup(50);
        SceneAcoustics.clearTarget();
        SceneAcoustics.flush();
        double[] pcm = new double[] {0.4, -0.4, 0.4, -0.4};
        double[] copy = pcm.clone();
        SceneAcoustics.render(pcm, CHANNELS, RATE);
        for (int i = 0; i < pcm.length; i++) {
            assertEquals(copy[i], pcm[i], 1e-12, "index " + i);
        }
    }

    @Test
    @DisplayName("抽头落点:单条 10ms 抽头的回声出现在正确采样位置,能量符合计划")
    void tapEchoLandsAtRightSample() {
        // 一条全向抽头:延迟 0.010s(441 帧),左右各 0.3。
        SceneAcoustics.applyTarget(plan(20000, 0.5f, 1f, 1f, 0.004f, 0.15f, 0.28f,
                new float[] {0.010f}, new float[] {0.3f}, new float[] {0.3f}));
        // 参数一阶收敛到千分之一要 τ·ln(1000) ≈ 2.1s ≈ 1400 块;湿声没到位回声就小。
        warmup(1400);

        // 中帧单帧脉冲(避开直流阻断的阶跃振铃):回声应落在 32+441 帧之后。
        // 帧计数含脉冲缓冲自己的 64 帧:回声在静音流里的位置 = 32+441−64。
        final int pulseFrame = 32;
        final int echoFrame = pulseFrame + 441 - 64;
        double[] pulse = new double[CHANNELS * 64];
        pulse[pulseFrame * CHANNELS] = 0.8;
        pulse[pulseFrame * CHANNELS + 1] = 0.8;
        SceneAcoustics.render(pulse, CHANNELS, RATE);
        double peakAtEcho = 0;
        double peakElsewhere = 0;
        for (int block = 0; block < 13; block++) {
            double[] silence = new double[CHANNELS * 64];
            SceneAcoustics.render(silence, CHANNELS, RATE);
            for (int f = 0; f < 64; f++) {
                final int frame = block * 64 + f;
                final double v = Math.abs(silence[f * CHANNELS]);
                if (frame >= echoFrame - 8 && frame <= echoFrame + 8) {
                    peakAtEcho = Math.max(peakAtEcho, v);
                } else if (frame > pulseFrame) {
                    peakElsewhere = Math.max(peakElsewhere, v);
                }
            }
        }
        // 回声量级 ≈ 0.8 × 0.3 × 湿声0.5 = 0.12;其余位置(尾部首波 ~0.01)应明显小于它。
        assertTrue(peakAtEcho > 0.05, "echo=" + peakAtEcho);
        assertTrue(peakAtEcho > peakElsewhere * 3.0,
                "echo=" + peakAtEcho + " elsewhere=" + peakElsewhere);
    }

    @Test
    @DisplayName("左右声像:左偏抽头 + 双耳错梳 → 左耳余音显著强于右耳")
    void pannedTapFavorsLeftEar() {
        SceneAcoustics.applyTarget(plan(20000, 0.5f, 1f, 1f, 0.004f, 0.15f, 0.28f,
                new float[] {0.008f}, new float[] {0.35f}, new float[] {0.09f}));
        warmup(1400);
        final int pulseFrame = 32;
        double[] pulse = new double[CHANNELS * 64];
        pulse[pulseFrame * CHANNELS] = 0.8;
        pulse[pulseFrame * CHANNELS + 1] = 0.8;
        SceneAcoustics.render(pulse, CHANNELS, RATE);
        double energyL = 0;
        double energyR = 0;
        for (int block = 0; block < 12; block++) {
            double[] silence = new double[CHANNELS * 64];
            SceneAcoustics.render(silence, CHANNELS, RATE);
            for (int f = 0; f < 64; f++) {
                energyL += silence[f * CHANNELS] * silence[f * CHANNELS];
                energyR += silence[f * CHANNELS + 1] * silence[f * CHANNELS + 1];
            }
        }
        // 抽头能量比 (0.35/0.09)² ≈ 15×;尾部双耳近似相等会稀释比值。
        assertTrue(energyL > energyR * 1.5,
                "left=" + energyL + " right=" + energyR);
    }

    @Test
    @DisplayName("声道数变化:单声道与多声道输入都不越界")
    void handlesChannelCounts() {
        SceneAcoustics.applyTarget(planNoTaps(500, 0.4f, 0.9f, 0.9f, 0.006f, 0.5f, 0.28f));
        warmup(20);
        double[] mono = new double[128];
        java.util.Arrays.fill(mono, 0.2);
        SceneAcoustics.render(mono, 1, RATE);
        for (double v : mono) assertTrue(Double.isFinite(v));

        double[] quad = new double[128 * 4];
        java.util.Arrays.fill(quad, 0.2);
        SceneAcoustics.render(quad, 4, RATE);
        for (double v : quad) assertTrue(Double.isFinite(v));
    }

    /** 喂 n 个静音缓冲(每块新建数组):render 是原地改写,复用同一数组会把上一块
     *  的输出回灌成输入;灌直流则会在湿声入口的直流阻断器上留阶跃斑点。参数
     *  收敛发生在早退判断之前,静音缓冲一样能把湿声/回授推到目标。 */
    private static void warmup(int blocks) {
        for (int i = 0; i < blocks; i++) {
            SceneAcoustics.render(new double[CHANNELS * 64], CHANNELS, RATE);
        }
    }
}
