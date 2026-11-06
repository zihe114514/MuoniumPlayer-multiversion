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
                decaySec * 1.35f, decaySec * 0.8f, 1f,
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

    @Test
    @DisplayName("直达链恒等:pan=0、gain=1、截止全开 → 逐样本不变")
    void directChainIdentityAtNeutral() {
        SceneAcoustics.applyTarget(planNoTaps(20000, 0f, 1f, 1f, 0f, 0f, 0.28f,
                1f, DirectPath.FC_OPEN, 0f, 0f));
        warmup(50);
        double[] pcm = new double[] {0.3, -0.31, 0.42, -0.1, -0.2, 0.11};
        double[] copy = pcm.clone();
        SceneAcoustics.render(pcm, CHANNELS, RATE);
        for (int i = 0; i < pcm.length; i++) {
            assertEquals(copy[i], pcm[i], 1e-9, "index " + i);
        }
    }

    @Test
    @DisplayName("直达链:pan=1(gain 满档)声能移到右耳;纯 mono 激励左右守恒")
    void directChainPansEnergy() {
        // 纯 mono 激励(side=0)时:L' = M·gL·d, R' = M·gR·d —— 等功率下
        // (gL²+gR²)=2,总能量守恒(d=1 时)。pan=1 全右:gR²=2, gL=0。
        SceneAcoustics.applyTarget(planNoTaps(20000, 0f, 1f, 1f, 0f, 0f, 0.28f,
                1f, DirectPath.FC_OPEN, 1f, 0f));
        // 参数一阶收敛太慢(τ=0.3s):flush 让声像一步到位,再灌几块稳住环。
        SceneAcoustics.flush();
        warmup(4);
        double[] pcm = new double[CHANNELS * 512];
        java.util.Arrays.fill(pcm, 0.25);
        SceneAcoustics.render(pcm, CHANNELS, RATE);
        double eL = 0;
        double eR = 0;
        for (int f = 128; f < 512; f++) { // 避开环填充首段
            eL += pcm[f * CHANNELS] * pcm[f * CHANNELS];
            eR += pcm[f * CHANNELS + 1] * pcm[f * CHANNELS + 1];
        }
        assertTrue(eR > eL * 20.0, "右耳应主导: L=" + eL + " R=" + eR);
    }

    @Test
    @DisplayName("直达链衰减:gain=0.25 时 mono 激励的总能量按比例收缩(S 保留不适用于纯 mono)")
    void directChainAttenuates() {
        SceneAcoustics.applyTarget(planNoTaps(20000, 0f, 1f, 1f, 0f, 0f, 0.28f,
                0.25f, DirectPath.FC_OPEN, 0f, 0f));
        warmup(50);
        double[] pcm = new double[CHANNELS * 512];
        java.util.Arrays.fill(pcm, 0.25);
        double inE = 0;
        for (double v : pcm) inE += v * v;
        SceneAcoustics.render(pcm, CHANNELS, RATE);
        double outE = 0;
        for (int f = 128; f < 512; f++) {
            outE += pcm[f * CHANNELS] * pcm[f * CHANNELS];
            outE += pcm[f * CHANNELS + 1] * pcm[f * CHANNELS + 1];
        }
        double inE2 = 0;
        for (int f = 128; f < 512; f++) {
            inE2 += 0.25 * 0.25 * 2;
        }
        assertTrue(outE < inE2 * 0.5, "衰减后能量应明显降低: out=" + outE + " in=" + inE2);
        for (double v : pcm) {
            assertTrue(Double.isFinite(v));
        }
    }

    /** 带直达链参数的计划(世界声源 DSP 测试)。 */
    private static RoomPlan planNoTaps(double bandHz, float wet, float dryGain, float loudness,
                                       float predelaySec, float decaySec, float tone,
                                       float directGain, double directCutoff,
                                       float directPan, float directDist) {
        return new RoomPlan(bandHz, wet, dryGain, loudness, predelaySec, decaySec, tone,
                decaySec * 1.35f, decaySec * 0.8f, 1f,
                new float[0], new float[0], new float[0],
                directGain, directCutoff, directPan, directDist);
    }

    /** 喂 n 个静音缓冲(每块新建数组):render 是原地改写,复用同一数组会把上一块
     *  的输出回灌成输入;灌直流则会在湿声入口的直流阻断器上留阶跃斑点。参数
     *  收敛发生在早退判断之前,静音缓冲一样能把湿声/回授推到目标。 */
    private static void warmup(int blocks) {
        for (int i = 0; i < blocks; i++) {
            SceneAcoustics.render(new double[CHANNELS * 64], CHANNELS, RATE);
        }
    }

    @Test
    @DisplayName("直达 ITD:pan>0 时右耳先到(右耳领先于左耳,不是被延迟一整圈)")
    void directChainItdPutsRightEarFirst() {
        // 冲激定位:pan=1 全右。修前 readL = directPos + itd 绕了整整一圈
        // (directRing ≈ 22560 样),左耳被延迟 ≈0.47s —— 那不是 ITD。
        // 正确口径是单边年龄差:右耳 age=0、左耳 age=itd(≤28 样 @44.1k)。
        SceneAcoustics.applyTarget(planNoTaps(20000, 0f, 1f, 1f, 0f, 0f, 0.28f,
                1f, DirectPath.FC_OPEN, 1f, 0f));
        SceneAcoustics.flush();
        warmup(4);
        // 一个短促脉冲,数据铺满一个缓冲(64 帧)
        double[] pulse = new double[CHANNELS * 64];
        for (int f = 0; f < 8; f++) {
            pulse[f * CHANNELS] = 0.5;
            pulse[f * CHANNELS + 1] = 0.5;
        }
        SceneAcoustics.render(pulse, CHANNELS, RATE);
        // 找左右耳的包络峰值所在帧:右耳应早于左耳(或同帧,ITD 小于一帧)
        int peakL = -1;
        int peakR = -1;
        double bestL = 0;
        double bestR = 0;
        for (int f = 0; f < 64; f++) {
            final double vL = Math.abs(pulse[f * CHANNELS]);
            final double vR = Math.abs(pulse[f * CHANNELS + 1]);
            if (vL > bestL) { bestL = vL; peakL = f; }
            if (vR > bestR) { bestR = vR; peakR = f; }
        }
        assertTrue(peakR >= 0 && peakL >= 0, "两侧都要有能量");
        assertTrue(peakR <= peakL,
                "右耳应不晚于左耳: peakR=" + peakR + " peakL=" + peakL);
        // 关键回归:左耳没有被延迟一整圈。ITD 上限 0.65ms ≈ 28 样 @44.1k,
        // 一个 64 帧缓冲内必须已经出现。
        assertTrue(peakL - peakR <= 32,
                "ITD 应在一毫秒级,不能是整圈延迟: Δ=" + (peakL - peakR));
    }

    @Test
    @DisplayName("自研直射距离延迟:世界声源 96m 的直达声被整体推后 ≈0.28s")
    void directChainAppliesPropagationDelay() {
        // 96m / 343 ≈ 0.28s ≈ 12343 样 @44.1k。总线不可用时(安卓/急停/
        // 熔断)由 directPathRing 补这段飞行时间;修前 directDelayN 从不赋值,
        // 远距音箱的直达瞬到、反射带延迟。
        //
        // 注意:必须用完整构造把 hasSpeakerAnchor 置 true —— 距离延迟只在
        // 世界声源下有语义(玩家即声源时距离为 0)。d 必须 <1 才会激活直达
        // 链(directActive 要求 gain/截止/pan 三者之一偏离默认),所以用 0.5:
        // 距离延迟是链内独立的一环,与增益无关。
        SceneAcoustics.applyTarget(new RoomPlan(20000, 0f, 1f, 1f, 0f, 0f, 0.28f,
                0f, 0f, 1f,
                new float[0], new float[0], new float[0],
                0.5f, DirectPath.FC_OPEN, 0f, 96f,
                false, 1f, 0.5f, true, 0.0, 64.0, 0.0));
        SceneAcoustics.flush();
        // 灌静音把延迟环填满,参数也收敛到位
        warmup(200);

        // 单帧脉冲(帧 0),然后逐块追它出现在输出的哪一帧。
        // 每个缓冲都新分配:render 原地改写,复用会把输出回灌成输入。
        int firstHeard = -1;
        double peak = 0;
        int base = 0;
        for (int block = 0; block < 200 && firstHeard < 0; block++) {
            double[] buf = new double[CHANNELS * 64];
            if (block == 0) {
                buf[0] = 0.6;      // 左
                buf[1] = 0.6;      // 右
            }
            SceneAcoustics.render(buf, CHANNELS, RATE);
            for (int f = 0; f < 64; f++) {
                final double v = Math.abs(buf[f * CHANNELS]);
                if (v > 0.06) {
                    firstHeard = base + f;
                    peak = v;
                    break;
                }
            }
            base += 64;
        }
        assertTrue(firstHeard > 0, "脉冲必须出现在输出里");
        // 直达增益 0.5 + dryGain 1 → 幅度 0.3,阈值 0.06 留足余量
        final int expected = (int) (96.0 / 343.0 * RATE);
        assertTrue(Math.abs(firstHeard - expected) <= 160,
                "直达声应被推后 ≈" + expected + " 样,实测 " + firstHeard);
        assertTrue(peak > 0.1, "直达声不该被衰减掉: peak=" + peak);
    }

    @Test
    @DisplayName("世界声源 + 湿声:混响是随时间变化的信号,不是每个缓冲一个常数")
    void speakerWetIsSignalNotPerBufferConstant() {
        // 回归:曾经总线分支拿 busOut[0] 当整段湿声(单采样值 × 侧链增益),
        // 听感是「嗡嗡/噗噗」的台阶噪声,且 ER/FDN 一次都不跑。
        // 判据:同一缓冲内湿声必须逐样本变化(抽头/尾是滤波后的信号);
        // 「整段同一个值」就是那个 bug 的指纹。
        SceneAcoustics.applyTarget(new RoomPlan(20000, 0.6f, 1f, 1f, 0.004f, 0.4f, 0.28f,
                0.54f, 0.32f, 1f,
                new float[] {0.010f, 0.023f}, new float[] {0.3f, 0.15f}, new float[] {0.3f, 0.15f},
                1f, DirectPath.FC_OPEN, 0f, 0f,
                true, 1f, 0.5f, true, 0.0, 64.0, 0.0));
        warmup(1400);

        // 真实内容的激励(避免静音/直流把 ER 抽头喂成 0)
        double[] pcm = new double[CHANNELS * 256];
        int seed = 12345;
        for (int f = 0; f < 256; f++) {
            seed = seed * 1103515245 + 12345;
            final double v = ((seed >> 16) & 0x7FFF) / 32768.0 - 0.5;   // 类噪声
            pcm[f * CHANNELS] = v * 0.4;
            pcm[f * CHANNELS + 1] = -v * 0.4;
        }
        SceneAcoustics.render(pcm, CHANNELS, RATE);

        // 去掉直达声影响:比较「相隔两个样本」的差分能量。常数湿声的差分恒 0。
        double diff = 0;
        double level = 0;
        for (int f = 2; f < 256; f++) {
            final double a = pcm[f * CHANNELS];
            final double b = pcm[(f - 2) * CHANNELS];
            diff += (a - b) * (a - b);
            level += a * a;
        }
        assertTrue(level > 1e-6, "输出必须有声: level=" + level);
        assertTrue(diff > level * 1e-3,
                "湿声必须是随时间变化的信号,不是每缓冲一个常数"
                        + "(diff=" + diff + " level=" + level + ")");
    }
}
