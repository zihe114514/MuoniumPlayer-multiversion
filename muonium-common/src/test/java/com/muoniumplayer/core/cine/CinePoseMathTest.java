package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运镜数学的纯函数测试：缓动、平滑、punch 包络、roll 限幅。
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
    void expLerp帧率无关性() {
        // 同样的总时长,大帧小帧走出的总进度一致(指数平滑的定义性质)。
        // 10s 总时长:60fps 每帧 1/60s vs 10fps 每帧 0.1s,最终都收敛到同一比例。
        double tau = 0.2d;
        double bigFrames = 1d;
        for (int i = 0; i < 10; i++) {
            bigFrames *= (1d - CineShots.expLerp(0.1d, tau));
        }
        double smallFrames = 1d;
        for (int i = 0; i < 600; i++) {
            smallFrames *= (1d - CineShots.expLerp(1d / 60d, tau));
        }
        assertEquals(bigFrames, smallFrames, 0.01d,
                "帧率不同时 10 秒后的剩余距离必须一致（±1%）");
        // dt 上限钳制：卡顿一帧 1s 不该让 blend 直接打到 1 跳变
        double blended = CineShots.expLerp(5d, tau);
        assertTrue(blended <= 1d, "超长 dt 也要钳在 [0,1]");
    }

    @Test
    void fovPunch冲高回落与限幅() {
        long now = 1_000_000_000L;
        // punch 时刻打满幅度
        double atPeak = CineShots.fovPunch(8d, now, now, 0.2d);
        assertEquals(8d, atPeak, 1.0e-6, "鼓点瞬间 = 满幅度");
        // 0.2s 后衰减到 1/e ≈ 36.8%
        double afterTau = CineShots.fovPunch(8d, now, now + 200_000_000L, 0.2d);
        assertEquals(8d * Math.exp(-1d), afterTau, 0.05d, "τ 时刻应衰减到 1/e");
        // punch 之前恒 0（不许预告）
        assertEquals(0d, CineShots.fovPunch(8d, now + 100L, now, 0.2d),
                "punch 之前必须为 0");
        // 零幅度直接 0
        assertEquals(0d, CineShots.fovPunch(0d, now, now, 0.2d));
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
    void 手持感幅度在限内() {
        // 1.0 强度下 roll 峰值 ≈ 0.9°×(1+0.35) ≈ 1.22°，pitch ≈ 0.78°——远低于 3D 晕阈值。
        double maxRoll = 0d;
        double maxPitch = 0d;
        for (int i = 0; i < 10000; i++) {
            double t = i / 100.0;
            maxRoll = Math.max(maxRoll, Math.abs(CineShots.handheldRoll(t, 1.0d)));
            maxPitch = Math.max(maxPitch, Math.abs(CineShots.handheldPitch(t, 1.0d)));
        }
        assertTrue(maxRoll < 1.5d, "手持 roll 峰值必须 <1.5°（3D 晕安全线），实测 " + maxRoll);
        assertTrue(maxPitch < 1.2d, "手持 pitch 峰值必须 <1.2°，实测 " + maxPitch);
        // intensity=0 时完全静止
        assertEquals(0d, CineShots.handheldRoll(123.4d, 0d), 1.0e-9);
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
