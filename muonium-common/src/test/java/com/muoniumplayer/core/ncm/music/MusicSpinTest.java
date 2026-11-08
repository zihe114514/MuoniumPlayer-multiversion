package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「陀螺模式」的拍点步进与弹簧曲线。角度数学是纯函数，直接钉端点与单调性；
 * 节拍脉冲、帧长、时钟与不应期全部由探针调用方给——CloudMusic 在纯 JVM 测试里
 * 不可用（与 {@code CineSchedulerTest} 的隔离纪律一致），持久化走
 * {@code MusicSpin} 自己的懒加载文件，这里不测 IO。
 */
class MusicSpinTest {

    /** 120 BPM 的拍周期与同源不应期（0.75 拍），与生产 refractoryNanos 的口径一致。 */
    private static final long BEAT_NANOS = 500_000_000L;
    private static final long REFRACTORY_NANOS = (long) (BEAT_NANOS * 0.75);
    private static final long START_NANOS = 1_000_000_000L;
    private static final float DT = 1f / 60f;

    @Test
    void wrapDegreesEndpoints() {
        assertEquals(0f, MusicSpin.wrapDegrees(0f), 1e-6);
        assertEquals(180f, MusicSpin.wrapDegrees(180f), 1e-6);
        assertEquals(180f, MusicSpin.wrapDegrees(-180f), 1e-6);
        assertEquals(0f, MusicSpin.wrapDegrees(360f), 1e-6);
        assertEquals(0f, MusicSpin.wrapDegrees(720f), 1e-6);
        assertEquals(-90f, MusicSpin.wrapDegrees(270f), 1e-6);
        assertEquals(90f, MusicSpin.wrapDegrees(-270f), 1e-6);
        assertEquals(1f, MusicSpin.wrapDegrees(361f), 1e-6);
        // NaN 不许进矩阵/朝向字段。
        assertEquals(0f, MusicSpin.wrapDegrees(Float.NaN), 1e-6);
        assertEquals(0f, MusicSpin.wrapDegrees(Float.POSITIVE_INFINITY), 1e-6);
    }

    @Test
    void 每个拍点升沿步进一步() {
        MusicSpin.SpinProbe probe = new MusicSpin.SpinProbe(90f);
        // 节拍包络：拍点 1.0，指数回落（与 BeatPulseSource.pulseAtPhase 同形）。
        // 窗口 [1.0s, 3.0s)：拍点落在 1.0/1.5/2.0/2.5，共 4 步 = 360°。ζ=0.4 的残余
        // 振荡在末拍 0.48s 后衰减到 ~5°，界给 7°。
        float angle = 0f;
        for (int i = 0; i < 120; i++) { // 2s @60fps
            long now = START_NANOS + i * 16_666_667L;
            float phase = ((now - START_NANOS) % BEAT_NANOS) / (float) BEAT_NANOS;
            float pulse = (float) Math.exp(-phase * 4f);
            angle = probe.step(pulse, DT, now, REFRACTORY_NANOS);
        }
        assertEquals(360f, angle, 7f, "四个拍点必须恰好累计 360°");
    }

    @Test
    void 不应期挡住密集onset() {
        MusicSpin.SpinProbe probe = new MusicSpin.SpinProbe(90f);
        // t=1.0s 拍点步进一次；t=1.1s 又一次升沿（<0.375s 不应期）必须被挡掉。
        float angle = 0f;
        long[] times = {START_NANOS, START_NANOS + 100_000_000L};
        int frame = 0;
        for (int i = 0; i < 120; i++) { // 2s @60fps
            long now = START_NANOS + i * 16_666_667L;
            float pulse = 0f;
            for (long t : times) {
                long phase = Math.abs(now - t);
                if (phase < BEAT_NANOS) {
                    float p = phase / (float) BEAT_NANOS;
                    pulse = Math.max(pulse, (float) Math.exp(-p * 4f));
                }
            }
            angle = probe.step(pulse, DT, now, REFRACTORY_NANOS);
            frame++;
        }
        assertEquals(90f, angle, 2.5f, "不应期内的第二次升沿不许再步进");
    }

    @Test
    void 阈值下的波动不算拍() {
        MusicSpin.SpinProbe probe = new MusicSpin.SpinProbe(90f);
        // 能量档的杂波：脉冲在 0.2~0.45 之间抖 10s，永远不过 0.5 阈值 = 一步都没有。
        float angle = 0f;
        for (int i = 0; i < 600; i++) {
            long now = START_NANOS + i * 16_666_667L;
            float pulse = 0.2f + 0.2f * Math.abs((float) Math.sin(i * 0.7));
            angle = probe.step(pulse, DT, now, REFRACTORY_NANOS);
        }
        assertEquals(0f, angle, 1e-6f, "阈值下杂波不许触发步进");
    }

    @Test
    void 弹簧收敛且帧间加速度有界() {
        // 与 TransparentModeTest 同款指标，界按步幅比例放大（那里满幅=1，这里满幅=步幅）：
        // 一阶速度有界、二阶加速度有界、最终收敛。拍点处目标阶跃会让加速度出现
        // ω²·步幅/h帧² 量级的台阶，二阶界给到能盖住它、又仍能钉住数值发散的宽度。
        MusicSpin.SpinProbe probe = new MusicSpin.SpinProbe(90f);
        float previous = 0f, previousDelta = 0f;
        float min = 0f, max = 0f;
        for (int i = 0; i < 600; i++) {
            long now = START_NANOS + i * 16_666_667L;
            float phase = ((now - START_NANOS) % BEAT_NANOS) / (float) BEAT_NANOS;
            float pulse = (float) Math.exp(-phase * 4f);
            float value = probe.step(pulse, DT, now, REFRACTORY_NANOS);
            float delta = value - previous;
            if (i > 2) { // 首拍阶跃后的头两帧允许全力启动
                assertTrue(Float.isFinite(delta), "单帧位移出现非有限值");
                assertTrue(Math.abs(delta) < 20f, "单帧位移 " + Math.abs(delta) + " 超出弹簧峰速（发散）");
                assertTrue(Math.abs(delta - previousDelta) < 6.5f,
                        "帧间加速度 " + Math.abs(delta - previousDelta) + "：响应里有硬折角或发散");
            }
            min = Math.min(min, value);
            max = Math.max(max, value);
            previousDelta = delta;
            previous = value;
        }
        // ζ=0.4 的阶跃过冲 ≈ 步幅的 25%：max 不许出 1800 + 30，负向不许深过 −10。
        assertTrue(max <= 1830f, "过冲出圈（max=" + max + "）");
        assertTrue(min >= -10f, "过冲钻到负值太深（min=" + min + "）");
        // 10s 窗口 [1.0s, 11.0s)：拍点 1.0..10.5 共 20 步 = 1800°；末拍后 0.48s 的残余
        // 振荡 ~5°，界给 7°。
        assertTrue(Math.abs(previous - 1800f) < 7f, "20 拍后应收敛到最后目标（value=" + previous + "）");
    }

    @Test
    void 弹簧在帧尖峰下不发散() {
        // 掉帧到 100ms 一帧也不许发散：子步进必须兜住大步长。
        MusicSpin.SpinProbe probe = new MusicSpin.SpinProbe(360f);
        for (int i = 0; i < 300; i++) {
            long now = START_NANOS + i * 100_000_000L;
            float pulse = i % 5 == 0 ? 1f : 0.1f;
            float value = probe.step(pulse, 0.1f, now, REFRACTORY_NANOS);
            assertTrue(Float.isFinite(value), "大步长下出现非有限值");
            assertTrue(Math.abs(value) <= 360f * 60f + 400f, "大步长下发散（value=" + value + "）");
        }
    }

    @Test
    void 过渡转速让节拍间也匀速前进() {
        // 恒 0.3 脉冲（活跃但不过阈）：纯基础转速，无步进——5s @60°/s = 300°。
        MusicSpin.SpinProbe probe = new MusicSpin.SpinProbe(90f);
        float angle = 0f;
        for (int i = 0; i < 300; i++) {
            angle = probe.step(0.3f, 1f / 60f, START_NANOS + i * 16_666_667L, REFRACTORY_NANOS, 60f);
        }
        assertEquals(300f, angle, 2f, "基础转速必须线性累计（过渡不再停顿）");
        // baseSpeed=0 退回老版纯拍点步进口径。
        MusicSpin.SpinProbe still = new MusicSpin.SpinProbe(90f);
        float stillAngle = 0f;
        for (int i = 0; i < 60; i++) {
            stillAngle = still.step(0.3f, 1f / 60f, START_NANOS + i * 16_666_667L, REFRACTORY_NANOS, 0f);
        }
        assertEquals(0f, stillAngle, 1e-4f, "过渡转速 0 = 节拍间完全停住（老口径）");
    }

    // ---------------- 头乱晃三档 ----------------

    @Test
    void 包络为零时头部完全不动() {
        assertArrayEquals(new float[] {0f, 0f},
                MusicSpin.headDeltasFor(MusicSpin.HEAD_WOBBLE, 0f, 123f, 200f), 1e-6f);
        assertArrayEquals(new float[] {0f, 0f},
                MusicSpin.headDeltasFor(MusicSpin.HEAD_DOWN, 0f, 123f, 0f), 1e-6f);
        assertArrayEquals(new float[] {0f, 0f},
                MusicSpin.headDeltasFor(MusicSpin.HEAD_TURN, 0f, 123f, 200f), 1e-6f);
    }

    @Test
    void 低头档压到固定俯仰角() {
        // 65° 是「看地面」档：yaw 不动，pitch 按包络缩放。
        float[] full = MusicSpin.headDeltasFor(MusicSpin.HEAD_DOWN, 1f, 0f, 0f);
        assertEquals(0f, full[0], 1e-6f, "低头档不许带 yaw");
        assertEquals(65f, full[1], 1e-6f);
        // 半包络 = 半幅度：缓入缓出的中间态。
        float[] half = MusicSpin.headDeltasFor(MusicSpin.HEAD_DOWN, 0.5f, 0f, 0f);
        assertEquals(32.5f, half[1], 1e-6f);
    }

    @Test
    void 转头档平滑双轴扫掠() {
        // yaw 匀速整圈：turnAngle=200 wrap 到 -160（同一旋转角，最短路径）。
        // pitch 与累计角两倍频锁定：45° 处正弦峰 +30（下压）、90° 处过零、135° 处 -30（上抬）
        // —— 一整圈点头两回，全程正弦无折角。
        float[] quarter = MusicSpin.headDeltasFor(MusicSpin.HEAD_TURN, 1f, 0f, 45f);
        assertEquals(45f, quarter[0], 1e-4f);
        assertEquals(30f, quarter[1], 1e-3f, "45° 处 pitch 到下压峰");
        float[] half = MusicSpin.headDeltasFor(MusicSpin.HEAD_TURN, 1f, 0f, 90f);
        assertEquals(0f, half[1], 1e-3f, "90° 处 pitch 过零");
        float[] threeQuarter = MusicSpin.headDeltasFor(MusicSpin.HEAD_TURN, 1f, 0f, 135f);
        assertEquals(-30f, threeQuarter[1], 1e-3f, "135° 处 pitch 到上抬峰");
        float[] wrapped = MusicSpin.headDeltasFor(MusicSpin.HEAD_TURN, 1f, 0f, 200f);
        assertEquals(-160f, wrapped[0], 1e-4f, "yaw 按最短路径角渲染");
        // 半包络 = 恰好缩半（缓入缓出线性）。
        float[] envelope = MusicSpin.headDeltasFor(MusicSpin.HEAD_TURN, 0.5f, 0f, 45f);
        assertEquals(22.5f, envelope[0], 1e-4f);
        assertEquals(15f, envelope[1], 1e-3f);
    }

    @Test
    void 乱晃档幅度被包络钳住() {
        // 满包络下扫 10s：|yaw| ≤ 30+16+1、|pitch| ≤ 14+1（正弦峰值的代数和上界）。
        float maxYaw = 0f, maxPitch = 0f;
        for (int i = 0; i < 600; i++) {
            float t = i * 1f / 60f;
            float[] d = MusicSpin.headDeltasFor(MusicSpin.HEAD_WOBBLE, 1f, t, 0f);
            maxYaw = Math.max(maxYaw, Math.abs(d[0]));
            maxPitch = Math.max(maxPitch, Math.abs(d[1]));
            assertTrue(Float.isFinite(d[0]) && Float.isFinite(d[1]), "乱晃出现非有限值");
        }
        assertTrue(maxYaw <= 47f, "yaw 幅度出圈（max=" + maxYaw + "）");
        assertTrue(maxPitch <= 15f, "pitch 幅度出圈（max=" + maxPitch + "）");
        assertTrue(maxYaw > 20f, "yaw 几乎没动过（max=" + maxYaw + "）：正弦是不是写死了零");
        // 半包络 = 恰好缩半。
        float[] half = MusicSpin.headDeltasFor(MusicSpin.HEAD_WOBBLE, 0.5f, 0.425f, 0f);
        float[] full = MusicSpin.headDeltasFor(MusicSpin.HEAD_WOBBLE, 1f, 0.425f, 0f);
        assertEquals(full[0] * 0.5f, half[0], 1e-4f, "包络必须是线性缩放");
        assertEquals(full[1] * 0.5f, half[1], 1e-4f);
    }
}
