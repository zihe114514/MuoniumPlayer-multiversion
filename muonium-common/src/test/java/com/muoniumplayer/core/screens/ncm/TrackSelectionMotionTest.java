package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrackSelectionMotionTest {
    private static final long START = 20_000_000_000L;

    @Test void openingAnAlreadyPlayingTrackDoesNotPretendItWasClicked() {
        TrackSelectionMotion motion = new TrackSelectionMotion();
        motion.update("a", 0, false, START, 3);
        assertEquals(1, motion.reveal());
        assertEquals(0, motion.offset());
        assertEquals(0, motion.burst());
    }

    @Test void clickIsReadableOnFirstFrameAndSettlesQuickly() {
        TrackSelectionMotion motion = new TrackSelectionMotion();
        motion.update("b", START, true, START, 3);
        assertTrue(motion.contentAlpha() >= .7f);
        assertEquals(0, motion.reveal());
        assertEquals(1, motion.burst());
        motion.update("b", START, true, START + 320_000_000L, 3);
        assertEquals(1, motion.reveal());
        assertEquals(0, motion.offset());
        assertTrue(motion.preparing());
    }

    @Test void handoverPulsesWithoutSlidingTheTitleInTwice() {
        TrackSelectionMotion motion = new TrackSelectionMotion();
        motion.update("b", START, true, START, 3);
        motion.update("b", 0, false, START + 1_000_000_000L, 3);
        assertEquals(1, motion.reveal());
        motion.update("b", 0, false, START + 1_270_000_000L, 3);
        assertEquals(1, motion.settledGlow(), 1e-9);
        assertEquals(0, motion.offset());
        motion.update("b", 0, false, START + 2_000_000_000L, 3);
        assertEquals(0, motion.settledGlow(), 1e-9);
    }

    @Test void animationDependsOnElapsedTimeNotFrameCount() {
        TrackSelectionMotion slow = new TrackSelectionMotion();
        TrackSelectionMotion fast = new TrackSelectionMotion();
        slow.update("b", START, true, START, 3);
        fast.update("b", START, true, START, 3);
        for (int i = 1; i <= 10; i++) slow.update("b", START, true, START + i * 16_000_000L, 3);
        for (int i = 1; i <= 40; i++) fast.update("b", START, true, START + i * 4_000_000L, 3);
        assertEquals(slow.reveal(), fast.reveal());
        assertEquals(slow.sweep(), fast.sweep());
        assertEquals(slow.pulse(1), fast.pulse(1));
    }

    @Test void repeatClickOnSameSongGetsANewAcknowledgement() {
        TrackSelectionMotion motion = new TrackSelectionMotion();
        motion.update("b", START, true, START, 3);
        long next = START + 2_000_000_000L;
        motion.update("b", next, true, next, 3);
        assertEquals(1, motion.burst());
        assertEquals(0, motion.sweep());
    }

    @Test void offscreenRowDoesNotReplayAnOldClickWhenScrolledIntoView() {
        TrackSelectionMotion motion = new TrackSelectionMotion();
        motion.update("b", START, true, START + 10_000_000_000L, 3);
        assertEquals(0, motion.burst());
        assertEquals(1, motion.sweep());
        assertEquals(1, motion.reveal());
    }

    @Test void rollbackAndFastChangesRemainBounded() {
        TrackSelectionMotion motion = new TrackSelectionMotion();
        for (int i = 0; i < 100; i++) {
            long now = START + i * 17_000_000L;
            motion.update(i % 2 == 0 ? "b" : "a", i % 2 == 0 ? now : 0, i % 2 == 0, now, 3);
            assertTrue(motion.reveal() >= 0 && motion.reveal() <= 1);
            assertTrue(motion.contentAlpha() >= .7 && motion.contentAlpha() <= 1);
            assertTrue(motion.offset() >= 0 && motion.offset() <= 7);
            assertTrue(motion.travel() >= 0 && motion.travel() <= 1);
        }
    }

    @Test void invalidSpeedCannotPoisonDrawingAlpha() {
        TrackSelectionMotion motion = new TrackSelectionMotion();
        motion.update("b", START, true, START + 40_000_000L, Double.NaN);
        assertTrue(Float.isFinite(motion.contentAlpha()));
        assertTrue(Double.isFinite(motion.offset()));
    }
}
