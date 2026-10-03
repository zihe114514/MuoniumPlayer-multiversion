package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrackNavigationMotionTest {
    @Test void previousAndNextKeepTheirDirection() {
        TrackNavigationMotion motion = new TrackNavigationMotion();
        motion.trigger(-1);
        assertEquals(-1, motion.direction());
        assertTrue(motion.active());
        motion.trigger(1);
        assertEquals(1, motion.direction());
        assertTrue(motion.active());
    }

    @Test void feedbackStartsVisibleAndFadesByTheEnd() {
        TrackNavigationMotion motion = new TrackNavigationMotion();
        long now = System.nanoTime();
        motion.trigger(1);
        long start = System.nanoTime();
        assertTrue(motion.alpha(start + 150_000_000L) > .4f);
        assertTrue(motion.alpha(start + 300_000_000L) > 0f);
        assertTrue(motion.alpha(start + 800_000_000L) <= .001f);
        assertTrue(motion.progress(start + 800_000_000L) >= 1.0);
    }

    @Test void movementTravelsTowardTheChosenSide() {
        TrackNavigationMotion motion = new TrackNavigationMotion();
        motion.trigger(-1);
        long start = System.nanoTime();
        assertTrue(motion.travel(start) < 0.0);
        motion.trigger(1);
        start = System.nanoTime();
        assertTrue(motion.travel(start) > 0.0);
    }

    @Test void retriggerRestartsTheShortFeedbackWindow() {
        TrackNavigationMotion motion = new TrackNavigationMotion();
        motion.trigger(1);
        long first = System.nanoTime();
        motion.trigger(-1);
        long second = System.nanoTime();
        assertEquals(-1, motion.direction());
        assertTrue(motion.alpha(second + 150_000_000L) > .4f);
        assertTrue(motion.progress(second) <= motion.progress(first + 900_000_000L));
    }

    @Test void invalidZeroDirectionDoesNothing() {
        TrackNavigationMotion motion = new TrackNavigationMotion();
        motion.trigger(0);
        assertFalse(motion.active());
        assertEquals(0, motion.direction());
    }
}
