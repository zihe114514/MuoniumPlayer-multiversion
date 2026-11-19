package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 专栏的真时钟契约：合成时间，不加载 Minecraft、控件、字体或网络。 */
class AnnouncementMotionTest {
    private static final long START = 1_000_000_000L;
    private static final double EPSILON = 1e-9;

    @Test
    void firstSampleAndDuplicateTimestampDoNotAdvanceOrSwap() {
        AnnouncementMotion motion = new AnnouncementMotion();
        assertEquals(0, motion.visible());
        assertEquals(0, motion.target());
        assertEquals(1, motion.alpha(), 0);
        assertTrue(motion.acceptsInput());
        motion.select(1);
        assertFalse(motion.acceptsInput());
        assertFalse(motion.tick(START, 1));
        assertFalse(motion.tick(START, 1));
        assertEquals(1, motion.alpha(), 0);
        assertEquals(0, motion.visible());
    }

    @Test
    void equalElapsedTimeMatchesThirtySixtyAndOneHundredFortyFourHzAcrossRates() {
        for (double rate : new double[]{.30, 1, 3}) {
            double outgoing = sample(30, 90_000_000L, rate, false);
            assertEquals(outgoing, sample(60, 90_000_000L, rate, false), EPSILON);
            assertEquals(outgoing, sample(144, 90_000_000L, rate, false), EPSILON);
            assertEquals(smoothstep(1 - 90 * rate / 330), outgoing, EPSILON);

            double incoming = sample(30, 240_000_000L, rate, true);
            assertEquals(incoming, sample(60, 240_000_000L, rate, true), EPSILON);
            assertEquals(incoming, sample(144, 240_000_000L, rate, true), EPSILON);
            assertEquals(smoothstep(240 * rate / 780), incoming, EPSILON);
        }
    }

    @Test
    void backwardsSamplesCannotMoveTheBaselineOrDoubleCountElapsedTime() {
        AnnouncementMotion motion = new AnnouncementMotion();
        motion.tick(START, 1);
        motion.select(1);
        motion.tick(START + 80_000_000L, 1);
        double alpha = motion.alpha();
        assertFalse(motion.tick(START + 20_000_000L, 1));
        assertFalse(motion.tick(START - 10_000_000L, 1));
        assertEquals(alpha, motion.alpha(), 0);
        assertFalse(motion.tick(START + 80_000_000L, 1));
        assertEquals(alpha, motion.alpha(), 0);
        motion.tick(START + 100_000_000L, 1);
        assertEquals(smoothstep(1 - 100.0 / 330), motion.alpha(), EPSILON);
    }

    @Test
    void backgroundGapUsesTheIntentionalHundredMsBudgetRatherThanAFrameCounter() {
        AnnouncementMotion motion = new AnnouncementMotion();
        motion.tick(START, 1);
        motion.select(1);
        assertFalse(motion.tick(START + 5_000_000_000L, 1));
        assertEquals(smoothstep(1 - 100.0 / 330), motion.alpha(), EPSILON);
        motion.tick(START + 5_050_000_000L, 1);
        assertEquals(smoothstep(1 - 150.0 / 330), motion.alpha(), EPSILON);
        assertEquals(0, motion.visible());
    }

    @Test
    void repeatedSelectionKeepsCurrentAlphaAndOnlyTheFinalSectionSwapsAtZero() {
        AnnouncementMotion motion = new AnnouncementMotion();
        motion.tick(START, 1);
        motion.select(1);
        motion.tick(START + 80_000_000L, 1);
        double alpha = motion.alpha();
        for (int section : new int[]{2, 1, 2, 2}) {
            motion.select(section);
            assertEquals(alpha, motion.alpha(), 0, "连点不能重置内容透明度");
            assertEquals(0, motion.visible(), "旧内容没有收完，不许先换数据");
        }
        boolean swapped = false;
        for (int frame = 1; frame <= 100; frame++) {
            int previous = motion.visible();
            boolean changed = motion.tick(START + 80_000_000L + frame * 10_000_000L, 1);
            assertEquals(previous != motion.visible(), changed);
            if (changed) {
                assertFalse(swapped, "只能落到最终选中的专栏一次");
                swapped = true;
                assertEquals(2, motion.visible());
                assertEquals(0, motion.alpha(), 0, "换数据的那一帧必须完全透明");
                assertFalse(motion.acceptsInput());
            }
            assertTrue(motion.visible() == 0 || motion.visible() == 2);
        }
        assertTrue(swapped);
        assertEquals(2, motion.target());
    }

    @Test
    void inputIsBlockedThroughoutBothLegsAndReturnsOnlyAtExactFullAlpha() {
        AnnouncementMotion motion = new AnnouncementMotion();
        motion.tick(START, 1);
        motion.select(2);
        assertFalse(motion.acceptsInput());
        boolean swapped = false;
        for (int frame = 1; frame <= 150; frame++) {
            swapped |= motion.tick(START + frame * 10_000_000L, 1);
            double alpha = motion.alpha();
            assertTrue(Double.isFinite(alpha) && alpha >= 0 && alpha <= 1);
            assertTrue(Double.isFinite(motion.offset()) && Math.abs(motion.offset()) <= 26);
            assertEquals(motion.visible() == motion.target() && alpha == 1, motion.acceptsInput());
            if (alpha < 1) assertFalse(motion.acceptsInput());
        }
        assertTrue(swapped);
        assertEquals(1, motion.alpha(), 0);
        assertEquals(0, motion.offset(), 0);
        assertTrue(motion.acceptsInput());
    }

    @Test
    void reversingToVisibleSectionContinuesFromCurrentAlphaWithoutUnnecessarySwap() {
        AnnouncementMotion motion = new AnnouncementMotion();
        motion.tick(START, 1);
        motion.select(1);
        motion.tick(START + 90_000_000L, 1);
        double alpha = motion.alpha();
        motion.select(0);
        assertEquals(alpha, motion.alpha(), 0);
        assertFalse(motion.acceptsInput());
        assertFalse(motion.tick(START + 180_000_000L, 1));
        assertTrue(motion.alpha() > alpha && motion.alpha() < 1);
        assertEquals(0, motion.visible());
        assertFalse(motion.acceptsInput());
    }

    @Test
    void invalidSectionsAreNoOpAndSpeedIsBoundedWithFiniteFallback() {
        AnnouncementMotion motion = new AnnouncementMotion();
        motion.select(1);
        for (int section : new int[]{-1, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            motion.select(section);
            assertEquals(1, motion.target());
            assertEquals(0, motion.visible());
            assertEquals(1, motion.alpha(), 0);
        }
        for (double speed : new double[]{-20, 0, .1, .30, 1, 3, 20,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            double rate = Double.isFinite(speed) ? Math.max(.30, Math.min(3, speed)) : 1;
            assertEquals(smoothstep(1 - 60 * rate / 330), sample(60, 60_000_000L, speed, false), EPSILON);
        }
    }

    private static double sample(int hz, long duration, double rate, boolean incoming) {
        AnnouncementMotion motion = new AnnouncementMotion();
        motion.tick(START, 1);
        motion.select(1);
        long origin = START;
        if (incoming) {
            for (int frame = 1; frame <= 4; frame++) motion.tick(START + frame * 100_000_000L, 1);
            assertEquals(1, motion.visible());
            assertEquals(0, motion.alpha(), 0);
            origin += 400_000_000L;
        }
        for (int frame = 1; ; frame++) {
            long elapsed = Math.min(duration, Math.round(frame * 1_000_000_000.0 / hz));
            motion.tick(origin + elapsed, rate);
            if (elapsed == duration) return motion.alpha();
        }
    }

    private static double smoothstep(double value) {
        return value * value * (3 - 2 * value);
    }
}
