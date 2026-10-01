package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放器交接回灵动岛时的内容判据（离线，不碰 GL 也不碰 Minecraft）。
 *
 * <p>钉住的缺陷：常驻灵动岛关闭时，播放器主界面关闭的那几百毫秒里，岛被
 * {@code if (playerAbsorbImpact) shouldShow = enabled} 强制点亮，但常驻那条采样通道因为
 * {@code alwaysOn == false} 根本没跑，内容于是落进 {@code createIslandLayout} 的下载兜底，
 * 看起来像「一关播放器就开始下载」。修复给交接单独加了一条 {@code handoffAmbient} 通道，
 * 这里锁住它依赖的三个纯判据，尤其是「没歌时必须收成紧凑胶囊而不是展开空卡片」那一条。</p>
 */
class IslandHandoffAmbientStaticTest {

    /** 常驻关着时，原来的 isAmbient 一定给 false —— 这正是那条 bug 的根因，必须留在测试里。 */
    @Test
    void ambientChannelCannotCoverTheHandoffByItself() {
        assertFalse(IslandAmbientStatus.isAmbient(true, false, false, false, false),
                "常驻关闭时 isAmbient 必须是 false，所以交接需要另一条通道");
        assertTrue(IslandAmbientStatus.isAmbient(true, true, false, false, false));
        // 下载/完成停留/通知任一在发生，常驻都不许抢内容位。
        assertFalse(IslandAmbientStatus.isAmbient(true, true, true, false, false));
        assertFalse(IslandAmbientStatus.isAmbient(true, true, false, true, false));
        assertFalse(IslandAmbientStatus.isAmbient(true, true, false, false, true));
    }

    /** 交接段有歌：展开成正在播放卡片。 */
    @Test
    void handoffWithTrackExpandsToTheNowPlayingCard() {
        assertEquals(1.0, IslandAmbientStatus.expansionTarget(true, true, true), 1e-9);
    }

    /** 交接段没歌：收成紧凑胶囊。展开成一张空白/假的下载卡才是最糟的两种表现。 */
    @Test
    void handoffWithoutTrackCollapsesInsteadOfExpandingAnEmptyCard() {
        assertEquals(0.0, IslandAmbientStatus.expansionTarget(true, true, false), 1e-9);
    }

    /** 岛不显示时无论内容是什么都不许展开。 */
    @Test
    void hiddenIslandIsAlwaysCompact() {
        assertEquals(0.0, IslandAmbientStatus.expansionTarget(false, true, true), 1e-9);
        assertEquals(0.0, IslandAmbientStatus.expansionTarget(false, false, false), 1e-9);
    }

    /** 交接不改变「岛为什么要显示」的三条原有理由，只多了撞击那一段的强制可见。 */
    @Test
    void showReasonsAreUnchangedByTheHandoff() {
        assertFalse(IslandAmbientStatus.shouldShow(true, false, false, false, false),
                "常驻关、无事可报时本来就不该显示");
        assertTrue(IslandAmbientStatus.shouldShow(true, false, true, false, false));
        assertTrue(IslandAmbientStatus.shouldShow(true, false, false, true, false));
        assertTrue(IslandAmbientStatus.shouldShow(true, false, false, false, true));
        assertTrue(IslandAmbientStatus.shouldShow(true, true, false, false, false));
        assertFalse(IslandAmbientStatus.shouldShow(false, true, true, true, true),
                "总开关关掉以后任何理由都不该让岛出现");
    }
}
