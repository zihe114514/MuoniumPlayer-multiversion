package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure injected-clock contracts; no Minecraft, widget, native, account or worker initialization. */
class NcmPromptMotionTest {
    private static final long START = 1_000_000_000L;
    private static final double EPSILON = 1e-9;

    @Test
    void firstSampleAndRepeatedTimestampCannotAdvanceTheMotion() {
        NcmPromptMotion motion = new NcmPromptMotion(.25);
        assertEquals(.25, motion.value(), EPSILON);
        assertEquals(.25, motion.update(1, 50, START), EPSILON);
        assertEquals(.25, motion.update(1, 50, START), EPSILON);
        assertEquals(.25, motion.update(0, 50, START), EPSILON);
    }

    @Test
    void oneHalfLifeHalvesTheRemainingDistanceInEitherDirection() {
        NcmPromptMotion opening = new NcmPromptMotion(0);
        opening.update(1, 50, START);
        assertEquals(.5, opening.update(1, 50, START + 50_000_000L), EPSILON);
        assertEquals(.75, opening.update(1, 50, START + 100_000_000L), EPSILON);
        NcmPromptMotion closing = new NcmPromptMotion(1);
        closing.update(0, 50, START);
        assertEquals(.5, closing.update(0, 50, START + 50_000_000L), EPSILON);
    }

    @Test
    void equalElapsedTimeMatchesAtThirtySixtyAndOneHundredFortyFourHz() {
        // Keep samples below the declared hitch cap and above the intentional settling threshold.
        double reference = sample(30, 240_000_000L);
        assertEquals(reference, sample(60, 240_000_000L), EPSILON);
        assertEquals(reference, sample(144, 240_000_000L), EPSILON);
        assertEquals(1 - Math.pow(.5, 240.0 / 80.0), reference, EPSILON);
    }

    @Test
    void retargetingReversesFromTheActualValueWithoutResetOrOvershoot() {
        NcmPromptMotion motion = new NcmPromptMotion(0);
        motion.update(1, 50, START);
        double peak = motion.update(1, 50, START + 50_000_000L);
        assertEquals(peak, motion.update(0, 50, START + 50_000_000L), EPSILON);
        double reversed = motion.update(0, 50, START + 100_000_000L);
        assertEquals(peak * .5, reversed, EPSILON);
        assertTrue(reversed > 0 && reversed < peak);
        assertEquals(reversed + (1 - reversed) * .5,
                motion.update(1, 50, START + 150_000_000L), EPSILON);
    }

    @Test
    void backwardsSampleCannotMoveAndAHitchUsesOnlyTheDeclaredHundredMsCap() {
        NcmPromptMotion backwards = new NcmPromptMotion(.3);
        backwards.update(1, 50, START);
        assertEquals(.3, backwards.update(1, 50, START - 1_000_000L), EPSILON);
        NcmPromptMotion hitch = new NcmPromptMotion(0);
        hitch.update(1, 50, START);
        assertEquals(.75, hitch.update(1, 50, START + 5_000_000_000L), EPSILON);
        assertEquals(.875, hitch.update(1, 50, START + 5_050_000_000L), EPSILON);
    }

    @Test
    void settledValueRemainsExactAndCanStartANewTransition() {
        NcmPromptMotion motion = new NcmPromptMotion(0);
        motion.update(1, 50, START);
        for (int frame = 1; frame <= 20; frame++) {
            double value = motion.update(1, 50, START + frame * 50_000_000L);
            assertTrue(value >= 0 && value <= 1);
        }
        assertEquals(1, motion.value(), 0);
        assertEquals(1, motion.update(1, 50, START + 1_050_000_000L), 0);
        assertEquals(.5, motion.update(0, 50, START + 1_100_000_000L), EPSILON);
    }

    @Test
    void nonpositiveHalfLifeIsClampedToOneMillisecond() {
        for (double halfLife : new double[]{0, -20, .5, 1}) {
            NcmPromptMotion motion = new NcmPromptMotion(0);
            motion.update(1, halfLife, START);
            assertEquals(.5, motion.update(1, halfLife, START + 1_000_000L), EPSILON);
        }
    }

    @Test
    void revealIsBoundedTimedSmoothstepRatherThanAFrameCounter() {
        assertEquals(0, NcmPromptMotion.reveal(START, START - 1, 400), 0);
        assertEquals(0, NcmPromptMotion.reveal(START, START, 400), 0);
        assertEquals(.15625, NcmPromptMotion.reveal(START, START + 100_000_000L, 400), EPSILON);
        assertEquals(.5, NcmPromptMotion.reveal(START, START + 200_000_000L, 400), EPSILON);
        assertEquals(1, NcmPromptMotion.reveal(START, START + 400_000_000L, 400), 0);
        assertEquals(1, NcmPromptMotion.reveal(START, START + 4_000_000_000L, 400), 0);
        double previous = 0;
        for (int frame = 0; frame <= 120; frame++) {
            double value = NcmPromptMotion.reveal(START, START + frame * 5_000_000L, 400);
            assertTrue(Double.isFinite(value) && value >= previous && value <= 1);
            previous = value;
        }
        for (double duration : new double[]{0, -10}) {
            assertEquals(0, NcmPromptMotion.reveal(START, START, duration), 0);
            assertEquals(1, NcmPromptMotion.reveal(START, START + 1, duration), 0);
        }
    }

    private static double sample(int hz, long durationNanos) {
        NcmPromptMotion motion = new NcmPromptMotion(0);
        motion.update(1, 80, START);
        for (int frame = 1; ; frame++) {
            long elapsed = Math.min(durationNanos, Math.round(frame * 1_000_000_000.0 / hz));
            motion.update(1, 80, START + elapsed);
            if (elapsed == durationNanos) return motion.value();
        }
    }
}
