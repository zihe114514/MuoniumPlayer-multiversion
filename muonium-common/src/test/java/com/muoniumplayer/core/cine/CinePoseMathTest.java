package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运镜数学的纯函数测试：缓动、平滑、roll 安全值与位姿插值。
 * 这些曲线直接决定观感——端点错一点，运镜就是「抖」而不是「滑」。
 */
class CinePoseMathTest {

    @Test
    void smootherstep端点与单调性() {
        assertEquals(0d, CineShots.smootherstep(0d), 1.0e-9, "t=0 必须是 0");
        assertEquals(1d, CineShots.smootherstep(1d), 1.0e-9, "t=1 必须是 1");
        assertEquals(0.5d, CineShots.smootherstep(0.5d), 1.0e-9, "五次多项式在中点对称");
        // 单调不减
        double prev = -1d;
        for (int i = 0; i <= 100; i++) {
            double value = CineShots.smootherstep(i / 100.0);
            assertTrue(value >= prev, "smootherstep 必须单调不减");
            prev = value;
        }
        // 端点外钳制：不爆不荡
        assertEquals(0d, CineShots.smootherstep(-3d), 1.0e-9);
        assertEquals(1d, CineShots.smootherstep(5d), 1.0e-9);
    }

    @Test
    void wrapDegrees归一到180() {
        assertEquals(0f, CineShots.wrapDegrees(360f), 1.0e-4f);
        assertEquals(-179f, CineShots.wrapDegrees(181f), 1.0e-4f);
        assertEquals(179f, CineShots.wrapDegrees(-181f), 1.0e-4f);
        assertEquals(20f, CineShots.wrapDegrees(370f + 10f), 1.0e-4f, "380° ≡ 20°");
        assertEquals(-20f, CineShots.wrapDegrees(-380f), 1.0e-4f, "-380° ≡ -20°");
    }

    @Test
    void cineMathHasNoHandheldOrBeatPulseHelpers() throws Exception {
        String shots = java.nio.file.Files.readString(
                com.muoniumplayer.testsupport.ProjectPaths.sharedJava()
                        .resolve("com/muoniumplayer/core/cine/CineShots.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        String session = java.nio.file.Files.readString(
                com.muoniumplayer.testsupport.ProjectPaths.sharedJava()
                        .resolve("com/muoniumplayer/core/cine/CineSession.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(shots.contains("handheldRoll("));
        assertFalse(shots.contains("handheldPitch("));
        assertFalse(shots.contains("fovPunch("));
        assertFalse(session.contains("playback.pulse()"));
        assertFalse(session.contains("fovPunch"));
        assertFalse(session.contains("handheld"));
    }

    @Test
    void poseInterpolationUsesShortestYawAndClampsPartialTick() {
        CinePose from = CinePose.of(0d, 64d, 0d, 179f, 5f, 0.5f);
        CinePose to = new CinePose(2d, 66d, 4d, -179f, 9f, -0.5f, 8f);
        CinePose middle = CinePose.interpolate(from, to, 0.5f);
        assertEquals(180f, middle.yawDeg(), 1.0e-5f);
        assertEquals(1d, middle.x());
        assertEquals(65d, middle.y());
        assertEquals(4f, middle.fovOffsetDeg());
        assertTrue(middle.isFinite());
        assertEquals(from, CinePose.interpolate(from, to, -1f));
        assertEquals(to.x(), CinePose.interpolate(from, to, Float.NaN).x());
        assertFalse(CinePose.of(Double.NaN, 0d, 0d, 0f, 0f, 0f).isFinite());
    }

    @Test
    void hermitePoseSamplingKeepsEndpointsShortestYawAndNoOvershoot() {
        CinePose from = CinePose.of(0d, 64d, -2d, 179f, 5f, 0f);
        CinePose to = new CinePose(2d, 66d, 4d, -179f, 9f, 0f, 8f);
        CineSession.Motion start = new CineSession.Motion(5d, 0d, 0d, 45d, 0d, 0d, 3d);
        CineSession.Motion end = new CineSession.Motion(0d, 5d, 0d, -45d, 0d, 0d, 0d);
        assertEquals(from, CineSession.interpolateMotion(from, to, start, end, 0f));
        assertEquals(to, CineSession.interpolateMotion(from, to, start, end, 1f));
        double probeT = 0.00001d;
        CinePose first = CineSession.interpolateMotion(from, to, start, end, (float) probeT);
        assertEquals(5d, (first.x() - from.x()) / (probeT * 0.05d), 0.02d,
                "采样起点应延续当前相机速度");
        CinePose previous = first;
        for (int i = 1; i < 1000; i++) {
            CinePose sample = CineSession.interpolateMotion(from, to, start, end, i / 1000f);
            assertTrue(sample.x() >= -1.0e-9d && sample.x() <= 2d + 1.0e-9d);
            assertTrue(sample.y() >= 64d - 1.0e-9d && sample.y() <= 66d + 1.0e-9d);
            assertTrue(sample.z() >= -2d - 1.0e-9d && sample.z() <= 4d + 1.0e-9d);
            double yawDelta = CineShots.wrapDegrees(sample.yawDeg() - from.yawDeg());
            assertTrue(yawDelta >= -1.0e-4d && yawDelta <= 2d + 1.0e-4d);
            assertTrue(sample.fovOffsetDeg() >= 0f && sample.fovOffsetDeg() <= 8f);
            assertTrue(sample.isFinite());
            assertTrue(sample.x() >= previous.x() - 1.0e-7d);
            assertTrue(sample.y() >= previous.y() - 1.0e-7d);
            previous = sample;
        }
    }

    @Test
    void randRange可复现且在区间内() {
        double a = CineShots.randRange(42L, 1, 3d, 8d);
        double b = CineShots.randRange(42L, 1, 3d, 8d);
        assertEquals(a, b, 1.0e-12, "同 seed 同 salt 必须可复现");
        assertTrue(a >= 3d && a < 8d, "必须落在区间内");
        // 不同 salt 不相关（不同维度参数不该同起同落）
        double c = CineShots.randRange(42L, 2, 3d, 8d);
        assertTrue(a != c, "不同 salt 应给出不同值");
    }
}
