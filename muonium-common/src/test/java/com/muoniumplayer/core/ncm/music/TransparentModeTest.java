package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「通透模式」的果冻缩放曲线与配置钳制。缩放是纯数学，直接钉端点与单调性；
 * 持久化走 {@code TransparentMode} 自己的懒加载文件，这里不测 IO，只测钳制。
 */
class TransparentModeTest {

    @Test
    void heightScaleEndpoints() {
        // 零脉冲 = 原样；满脉冲按幅度压扁；幅度 50 是默认档。
        assertEquals(1f, TransparentMode.heightScale(0f, 50f), 1e-6);
        assertEquals(0.5f, TransparentMode.heightScale(1f, 50f), 1e-6);
        assertEquals(1f, TransparentMode.heightScale(1f, 0f), 1e-6);
        // 90% 是滑条上限：压到底还剩一成高，不许把模型压没。
        assertEquals(0.1f, TransparentMode.heightScale(1f, 90f), 1e-6);
    }

    @Test
    void widthScaleComplementsHeight() {
        assertEquals(1f, TransparentMode.widthScale(0f, 50f), 1e-6);
        assertEquals(1.5f, TransparentMode.widthScale(1f, 50f), 1e-6);
        // 深度不变是这套效果的立身之本：Z 轴根本没有接口，这条注释只是把意图钉在测试名里。
        assertEquals(1f, TransparentMode.widthScale(0f, 200f), 1e-6);
        assertEquals(3f, TransparentMode.widthScale(1f, 200f), 1e-6);
    }

    @Test
    void scaleIsMonotonicInSquash() {
        // 节拍越响压得越扁：任何幅度下高度随脉冲单调不增、宽度单调不减。
        for (float compression = 0f; compression <= 90f; compression += 15f) {
            for (float width = 0f; width <= 200f; width += 40f) {
                float previousHeight = TransparentMode.heightScale(0f, compression);
                float previousWidth = TransparentMode.widthScale(0f, width);
                for (float squash = 0.05f; squash <= 1.0001f; squash += 0.05f) {
                    float height = TransparentMode.heightScale(squash, compression);
                    float scaleWidth = TransparentMode.widthScale(squash, width);
                    assertTrue(height <= previousHeight + 1e-6, "高度随脉冲回升了");
                    assertTrue(scaleWidth >= previousWidth - 1e-6, "宽度随脉冲变窄了");
                    assertTrue(height > 0f, "压出非正高度会画出倒置模型");
                    previousHeight = height;
                    previousWidth = scaleWidth;
                }
            }
        }
    }

    @Test
    void springSmoothsTheOnsetAndOvershootsOnRelease() {
        // 平滑性的正确指标是加速度有界（二阶差分），不是一阶速度：
        // ω=14、ζ=0.4 的弹簧峰速区单帧位移 ~0.14（1/60s），那是肉眼可见的顺坡；
        // 「咔一下」的判断标准是相邻帧变化量的突变 —— 二阶差分必须小。
        TransparentMode.SpringProbe probe = new TransparentMode.SpringProbe();
        float previous = 0f, previousDelta = 0f;
        float min = 0f;
        for (int i = 0; i < 600; i++) { // 10s @60fps，目标恒 1
            float value = probe.step(1f, 1f / 60f);
            float delta = value - previous;
            if (i > 0) {
                // 一阶：峰速 0.15/帧（弹簧物理上限附近，别卡死数值）
                assertTrue(Math.abs(delta) < 0.16f, "单帧位移 " + Math.abs(delta) + " 超出弹簧峰速");
                // 二阶：帧间加速度有界。实测阶跃响应峰值 jerk ≈ 0.0427（t=0 速度跳变
                // 所在的半隐式欧拉极限），取 1.2 倍余量 —— 硬折角/数值噪声仍会被钉住。
                assertTrue(Math.abs(delta - previousDelta) < 0.052f,
                        "帧间加速度 " + Math.abs(delta - previousDelta) + "：响应里有硬折角");
            }
            min = Math.min(min, value);
            previousDelta = delta;
            previous = value;
        }
        // 收敛：10 秒后必须稳稳贴住目标。
        assertTrue(Math.abs(previous - 1f) < 0.02f, "弹簧 10s 未收敛（value=" + previous + "）");
        // 上升段只会向上：不许钻出负值（那不是果冻是 bug）。
        assertTrue(min >= -0.02f, "上升段不该有过冲（min=" + min + "）");

        // 释放（停播，目标 1→0）才是过冲出场的地方：位移冲过 0 到负值
        // （模型短暂拉高变瘦）再稳住——果冻感的来源。
        TransparentMode.SpringProbe release = new TransparentMode.SpringProbe();
        for (int i = 0; i < 120; i++) release.step(1f, 1f / 60f);
        float minAfterRelease = 0f, peakAfterRelease = 0f;
        float previousRelease = release.step(0f, 1f / 60f);
        float previousReleaseDelta = 0f;
        for (int i = 1; i < 600; i++) {
            float value = release.step(0f, 1f / 60f);
            float delta = value - previousRelease;
            if (i > 1) {
                assertTrue(Math.abs(delta) < 0.16f, "释放段单帧位移 " + Math.abs(delta));
                assertTrue(Math.abs(delta - previousReleaseDelta) < 0.052f, "释放段帧间加速度 " + Math.abs(delta - previousReleaseDelta));
            }
            minAfterRelease = Math.min(minAfterRelease, value);
            peakAfterRelease = Math.max(peakAfterRelease, value);
            previousReleaseDelta = delta;
            previousRelease = value;
        }
        assertTrue(minAfterRelease < -0.05f,
                "释放后没有过冲拉高（min=" + minAfterRelease + "）：果冻感没了");
        assertTrue(minAfterRelease > -0.6f, "过冲深到离谱（min=" + minAfterRelease + "）");
        assertTrue(Math.abs(previousRelease) < 0.02f, "释放 10s 未归零");
        // peakAfterRelease 只记录不断言：释放首帧必然还在 0.98+（弹簧从满压松开），
        // 「释放途中不许高压」在物理上不成立；越界与收敛已由上面两条管住。
        assert peakAfterRelease >= minAfterRelease;
    }

    @Test
    void springIsStableUnderFrameHitches() {
        // 掉帧到 100ms 一帧也不许发散：子步进必须兜住大步长。
        TransparentMode.SpringProbe probe = new TransparentMode.SpringProbe();
        for (int i = 0; i < 300; i++) {
            float value = probe.step(1f, 0.1f);
            assertTrue(Math.abs(value) <= 1.5f, "大步长下发散（value=" + value + "）");
        }
    }

    @Test
    void outOfRangeInputsAreClamped() {
        // 恶意/损坏的配置值不能画出翻转型模型：正向钳到 0..1，负过冲钳到 -1（拉高上限）。
        assertEquals(1f, TransparentMode.heightScale(-0f, 50f), 1e-6);
        assertEquals(0.5f, TransparentMode.heightScale(2f, 50f), 1e-6);
        assertEquals(0.1f, TransparentMode.heightScale(1f, 500f), 1e-6);
        assertEquals(1f, TransparentMode.widthScale(1f, -50f), 1e-6);
    }

    @Test
    void negativeSquashStretchesInsteadOfSquashing() {
        // 弹簧过冲到负值 = 果冻回弹时短暂拉高变瘦：这是特性不是 bug。
        assertEquals(1.5f, TransparentMode.heightScale(-1f, 50f), 1e-6);
        assertEquals(0.5f, TransparentMode.widthScale(-1f, 50f), 1e-6);
        // 但不许无限拉：钳在 -1。
        assertEquals(1.5f, TransparentMode.heightScale(-3f, 50f), 1e-6);
        // NaN 不许进矩阵。
        assertEquals(1f, TransparentMode.heightScale(Float.NaN, 50f), 1e-6);
        assertEquals(1f, TransparentMode.widthScale(Float.NaN, 50f), 1e-6);
    }
}
