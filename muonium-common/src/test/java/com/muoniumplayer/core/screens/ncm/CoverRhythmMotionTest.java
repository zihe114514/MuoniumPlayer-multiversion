package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverRhythmMotionTest {
    @Test void silenceAndUnavailableAudioNeverStartAnAnimation() {
        var motion = new CoverRhythmMotion();
        for (int ms = 0; ms < 2000; ms += 16) {
            var frame = motion.sample(.016, 1, ms, true, false, Float.NaN, 0f);
            assertEquals(0, frame.getPulse());
            assertEquals(0, frame.getEnergy());
        }
    }

    @Test void realOnsetsReactFasterThanEnergyAndPauseLetsBothSettle() {
        var motion = new CoverRhythmMotion();
        motion.sample(.016, 1, 0, true, false, 0, 0);
        var hit = motion.sample(.016, 1, 16, true, false, 1, 1);
        assertTrue(hit.getPulse() > hit.getEnergy());
        assertTrue(hit.getEnergy() > 0);
        var after = motion.sample(.016, 1, 16, false, false, 1, 1);
        assertTrue(after.getPulse() < hit.getPulse());
        assertTrue(after.getEnergy() < hit.getEnergy());
        for (int i = 0; i < 48; i++) after = motion.sample(.1, 1, 16, false, false, 1, 1);
        assertEquals(0, after.getPulse());
        assertEquals(0, after.getEnergy());
    }

    @Test void seekTrackChangeAndStaticModeClearPreviouslyAccumulatedMotion() {
        var motion = new CoverRhythmMotion();
        motion.sample(.016, 1, 0, true, false, 0, 0);
        assertTrue(motion.sample(.05, 1, 50, true, false, 1, 1).getPulse() > 0);
        assertEquals(0, motion.sample(.016, 1, 10_000, true, false, 1, 1).getPulse());
        assertTrue(motion.sample(.016, 1, 10_016, true, false, 1, 1).getPulse() > 0);
        assertEquals(0, motion.sample(.016, 2, 0, true, false, 1, 1).getEnergy());
        assertTrue(motion.sample(.016, 2, 16, true, false, 1, 1).getEnergy() > 0);
        assertEquals(0, motion.sample(.016, 2, 32, true, true, 1, 1).getEnergy());
        assertEquals(0, motion.sample(.016, 2, 48, true, false, 0, 0).getPulse());
    }
}
