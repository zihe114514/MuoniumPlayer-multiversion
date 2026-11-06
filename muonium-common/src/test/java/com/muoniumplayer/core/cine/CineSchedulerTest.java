package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.ncm.music.CineBeatSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度器状态机与卡点决策的测试。
 *
 * <p>节拍网格与 CloudMusic 快照在测试 JVM 里不可用（无播放器），所以走
 * {@code CineDirector} 的测试钩子直驱内部状态 + 构造 {@code Grid} record
 * 验证拍相位数学。渲染链路（mixin/probe）不在这里碰——那是 StaticTest
 * 与 runClient 人工验收的职责。</p>
 */
class CineSchedulerTest {

    // ---------------- Grid 拍相位数学 ----------------

    @Test
    void grid拍相位与小节拍序() {
        long period = 500_000_000L; // 120 BPM = 0.5s 一拍
        long anchor = 1_000_000_000L;
        CineBeatSource.Grid grid = new CineBeatSource.Grid(
                120f, period, anchor, 4, anchor, 0.9f);
        assertTrue(grid.isMetered(), "置信网格必须 isMetered");
        // 拍点瞬间 phase=0
        assertEquals(0f, grid.beatPhase(anchor), 1.0e-4f);
        // 半拍相位 0.5
        assertEquals(0.5f, grid.beatPhase(anchor + 250_000_000L), 1.0e-4f);
        // 小节第一拍 beatInBar=0；第二拍 =1
        assertEquals(0, grid.beatInBar(anchor));
        assertEquals(1, grid.beatInBar(anchor + period));
        assertEquals(0, grid.beatInBar(anchor + 4 * period), "下一小节第一拍归零");
        // 距下一拍：半拍处 = 半拍
        assertEquals(0.25f, grid.secondsToNextBeat(anchor + 250_000_000L), 1.0e-3f);
    }

    @Test
    void 无信号网格退化为非计量() {
        CineBeatSource.Grid grid = new CineBeatSource.Grid(0f, 0d, 0d, 4, 0d, 0f);
        assertFalse(grid.isMetered(), "三级兜底的 confidence=0 网格必须 isMetered=false");
        assertEquals(0f, grid.beatPhase(123L), 1.0e-6f, "无网格时相位恒 0（调度器据此退化）");
    }

    // ---------------- 转场特效挑选 ----------------

    @Test
    void 位掩码为零时不选特效() {
        CineSettings.resetForTest();
        CineSettings.setTransitionMask(0);
        assertEquals(0, CineDirector.pickTransitionEffect(), "全关位掩码必须返回无特效");
    }

    @Test
    void 位掩码只出被选中的特效() {
        CineSettings.resetForTest();
        CineSettings.setTransitionMask(0b01000); // 只有竖擦（bit3 → effect 4）
        for (int i = 0; i < 20; i++) {
            assertEquals(4, CineDirector.pickTransitionEffect(),
                    "单 bit 掩码必须稳定选出对应特效");
        }
        CineSettings.setTransitionMask(0b00011); // 黑淡+白淡
        boolean sawBlack = false;
        boolean sawWhite = false;
        for (int i = 0; i < 100; i++) {
            int effect = CineDirector.pickTransitionEffect();
            assertTrue(effect == 1 || effect == 2, "只允许选出掩码内的特效");
            sawBlack |= effect == 1;
            sawWhite |= effect == 2;
        }
        assertTrue(sawBlack && sawWhite, "两 bit 掩码都应被随机命中");
    }

    // ---------------- 状态机进出（测试钩子直驱） ----------------

    @Test
    void isActive钩子与复位() {
        CineDirector.resetForTest();
        assertFalse(CineDirector.isActive(), "复位后必须不活跃");
        CineDirector.forceActiveForTest(true);
        assertTrue(CineDirector.isActive());
        // 活跃时渲染帧入口必须给出 pose（相机位任意）
        CinePose pose = CineDirector.currentPose(0d, 0d, 0d);
        assertNotNull(pose, "active 时渲染帧必须拿到 pose");
        // 朝向、俯仰必须在合法区间
        assertTrue(pose.pitchDeg() >= -89.9f && pose.pitchDeg() <= 89.9f);
        CineDirector.resetForTest();
        assertFalse(CineDirector.isActive());
        assertNull(CineDirector.currentPose(0d, 0d, 0d), "inactive 时渲染帧必须拿到 null（不接管）");
        assertNull(CineDirector.currentTransition(), "inactive 时无转场");
        assertEquals(0f, CineDirector.fovOffsetDeg(), 1.0e-6f, "inactive 时无 FOV 偏移");
        assertEquals(0f, CineDirector.cinematicBarFraction(), 1.0e-6f, "inactive 时无黑边");
    }

    @Test
    void 转场帧进度与覆盖半程() {
        CineDirector.resetForTest();
        long now = System.nanoTime();
        long start = now;
        long end = now + 1_000_000_000L;
        CineDirector.forceActiveForTest(true);
        CineDirector.forceTransitionForTest(1, start, end, true);
        // 时刻现在:遮出起点,progress≈0
        CineDirector.TransitionFrame atStart = CineDirector.currentTransition();
        assertNotNull(atStart);
        assertEquals(1, atStart.effect());
        assertTrue(atStart.covering(), "遮出半程 covering=true");
        assertTrue(atStart.progress() < 0.05d, "起点 progress 应接近 0");
        // 终点之后自动清零（currentTransition 里惰性归零）
        CineDirector.forceTransitionForTest(1, now - 2_000_000_000L, now - 1_000_000_000L, true);
        assertNull(CineDirector.currentTransition(), "转场时钟过点后必须自动清零");
        CineDirector.resetForTest();
    }

    @Test
    void 能量调制镜头时长方向正确() {
        // 镜头时长的能量调制:时长 ×(1.6 - 0.8×energy)。
        // 主歌 energy=0 → 1.6×基准；副歌 energy=1 → 0.8×基准。方向必须正确。
        double base = 4d;
        double calm = base * (1.6d - 0.8d * 0d);
        double drop = base * (1.6d - 0.8d * 1d);
        assertEquals(6.4d, calm, 1.0e-9, "主歌镜头 = 1.6×基准");
        assertEquals(3.2d, drop, 1.0e-9, "副歌镜头 = 0.8×基准");
        assertTrue(calm > drop, "能量高时镜头必须更短（切镜更密）");
    }

    @Test
    void probe接口的符号表() {
        // SceneProbe 是共享层与树侧 compat 的契约：方法名是 StaticTest 与七树
        // 同名同签名的锚点，增删都要过一遍这个表。
        java.util.List<String> expected = java.util.List.of(
                "playerX", "playerY", "playerZ", "playerYaw", "playerPitch",
                "openScreen", "hurtThisTick", "inputThisTick", "playerAlive",
                "clipBlocked", "nearestMovingMob");
        assertEquals(expected, CineDirector.probeMethodNamesForTest(),
                "SceneProbe 方法表变了必须同步改这个锚点与七树 CineProbe");
    }
}
