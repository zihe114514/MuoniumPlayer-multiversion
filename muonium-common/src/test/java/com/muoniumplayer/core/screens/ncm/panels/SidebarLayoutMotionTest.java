package com.muoniumplayer.core.screens.ncm.panels;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SidebarLayoutMotionTest {
    @Test void newSectionEntersFromBelowThenStopsExactlyAtItsLayout() {
        SidebarLayoutMotion motion = new SidebarLayoutMotion(null);
        motion.tick(1_000, 100, 300);
        assertEquals(6.0, motion.offset());
        assertEquals(0f, motion.alpha());
        motion.tick(1_150, 100, 300);
        assertEquals(.75, motion.offset(), .00001);
        assertEquals(.875f, motion.alpha());
        motion.tick(1_300, 100, 300);
        assertEquals(0.0, motion.offset());
        assertEquals(1f, motion.alpha());
        assertTrue(motion.isFinished());
    }
    @Test void existingSectionMovesWithoutFlashingOrChangingItsTargetLayout() {
        SidebarLayoutMotion motion = new SidebarLayoutMotion(new SidebarLayoutMotion.Snapshot(140, 1f));
        motion.tick(1_000, 100, 300);
        assertEquals(40.0, motion.offset());
        assertEquals(1f, motion.alpha());
        motion.tick(1_150, 90, 300); // 滚动时目标移动，残差不重新起一趟。
        assertEquals(5.0, motion.offset(), .00001);
        assertEquals(1f, motion.alpha());
        motion.tick(1_300, 80, 300);
        assertEquals(0.0, motion.offset());
    }
    @Test void anotherRebuildContinuesFromCurrentVisualPositionAndOpacity() {
        SidebarLayoutMotion first = new SidebarLayoutMotion(null);
        first.tick(1_000, 100, 300);
        first.tick(1_120, 100, 300);
        double visualY = 100 + first.offset();
        float opacity = first.alpha();
        SidebarLayoutMotion next = new SidebarLayoutMotion(new SidebarLayoutMotion.Snapshot(visualY, opacity));
        next.tick(1_120, 120, 300);
        assertEquals(visualY, 120 + next.offset(), .00001);
        assertEquals(opacity, next.alpha());
        next.tick(1_420, 120, 300);
        assertEquals(0.0, next.offset());
        assertEquals(1f, next.alpha());
    }
    @Test void unchangedRowsDoNotAcquireAnIdleAnimation() {
        SidebarLayoutMotion motion = new SidebarLayoutMotion(new SidebarLayoutMotion.Snapshot(100, 1));
        motion.tick(1_000, 100, 300);
        assertTrue(motion.isFinished());
        assertEquals(0.0, motion.offset());
        assertEquals(1f, motion.alpha());
    }
    @Test void longMovesAreBoundedAndClockReversalDoesNotCreateInvalidAlpha() {
        SidebarLayoutMotion motion = new SidebarLayoutMotion(new SidebarLayoutMotion.Snapshot(-10_000, 1));
        motion.tick(1_000, 100, 300);
        assertEquals(-48.0, motion.offset());
        motion.tick(900, 100, 300);
        assertEquals(-48.0, motion.offset());
        assertEquals(1f, motion.alpha());
        motion.tick(2_000, 100, 300);
        assertTrue(motion.isFinished());
    }
    @Test void sourceScopedDiscoveryChangesButSharedRowsKeepTheirIdentity() {
        assertNotEquals(SidebarLayoutMotion.rowKey("nav:hot", "NETEASE"),
                SidebarLayoutMotion.rowKey("nav:hot", "KUGOU"));
        assertNotEquals(SidebarLayoutMotion.rowKey("section:discovery", "GD:netease"),
                SidebarLayoutMotion.rowKey("section:discovery", "GD:qq"));
        for (String key : new String[]{"nav:home", "section:local", "hint:local-empty",
                "local:123:mix", "imported:123:mix"}) {
            assertEquals(SidebarLayoutMotion.rowKey(key, "NETEASE"), SidebarLayoutMotion.rowKey(key, "KUGOU"));
        }
    }
}
