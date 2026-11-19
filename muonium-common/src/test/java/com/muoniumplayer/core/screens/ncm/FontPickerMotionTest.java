package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FontPickerMotionTest {
    @Test
    void newFilterDoesNotCommitUntilOldContentHasLeft() {
        FontPickerMotion motion = new FontPickerMotion();
        motion.tick(1, 3);
        motion.request("consolas", 1);
        assertEquals("", motion.query());
        assertFalse(motion.acceptsInput());
        assertFalse(motion.tick(40_000_001, 3));
        assertTrue(motion.tick(90_000_001, 3));
        assertEquals("consolas", motion.query());
        assertEquals(1, motion.section());
        assertEquals(0, motion.alpha());
        motion.tick(190_000_001, 3);
        motion.tick(290_000_001, 3);
        assertTrue(motion.acceptsInput());
        assertEquals(1, motion.alpha());
    }

    @Test
    void rapidRequestsCoalesceToLastQueryAndSection() {
        FontPickerMotion motion = new FontPickerMotion();
        motion.tick(1, 3);
        motion.request("a", 1);
        motion.tick(40_000_001, 3);
        motion.request("arial", 2);
        assertTrue(motion.tick(90_000_001, 3));
        assertEquals("arial", motion.query());
        assertEquals(2, motion.section());
    }

    @Test
    void noRequestLeavesStableContentAndBoundedTimeStep() {
        FontPickerMotion motion = new FontPickerMotion();
        motion.tick(1, 1);
        assertFalse(motion.tick(Long.MAX_VALUE, 1));
        assertEquals(1, motion.alpha());
        assertEquals(0, motion.offset());
    }
}
