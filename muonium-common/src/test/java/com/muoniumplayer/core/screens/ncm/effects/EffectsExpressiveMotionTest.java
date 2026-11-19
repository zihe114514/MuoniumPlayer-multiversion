package com.muoniumplayer.core.screens.ncm.effects;

import org.junit.jupiter.api.Test;

import static com.muoniumplayer.core.screens.ncm.effects.EffectsMotion.Scheme.*;
import static org.junit.jupiter.api.Assertions.*;

class EffectsExpressiveMotionTest {

    @Test
    void expressiveSpatialMotionBouncesButEffectMotionNeverOvershoots() {
        EffectsMotion.Spring spatial = new EffectsMotion.Spring(0, FAST_SPATIAL);
        EffectsMotion.Spring effects = new EffectsMotion.Spring(0, FAST_EFFECTS);
        spatial.retarget(1, 0, 1);
        effects.retarget(1, 0, 1);
        double maximum = 0, previous = 0;
        for (long t = 0; t <= 1_000_000_000L; t += 1_000_000L) {
            maximum = Math.max(maximum, spatial.value(t));
            double value = effects.value(t);
            assertTrue(value >= previous - 1e-12 && value >= 0 && value <= 1);
            previous = value;
        }
        assertTrue(maximum > 1.02 && maximum < 1.12);
        assertEquals(1, spatial.value(1_000_000_000L));
        assertEquals(0, spatial.velocity(1_000_000_000L));
    }

    @Test
    void interruptionPreservesBothPositionAndVelocityIncludingSpeedChanges() {
        EffectsMotion.Spring spring = new EffectsMotion.Spring(0, DEFAULT_SPATIAL);
        spring.retarget(100, 0, 1);
        long t = 100_000_000L;
        double position = spring.value(t), velocity = spring.velocity(t);
        assertTrue(position > 0 && position < 100);
        assertTrue(velocity > 0);
        spring.retarget(-20, t, 1);
        assertEquals(position, spring.value(t), 1e-9);
        assertEquals(velocity, spring.velocity(t), 1e-9);
        t += 60_000_000L;
        position = spring.value(t);
        velocity = spring.velocity(t);
        spring.retarget(-20, t, 3);
        assertEquals(position, spring.value(t), 1e-9);
        assertEquals(velocity, spring.velocity(t), 1e-9);
        assertEquals(-20, spring.value(10_000_000_000L));
    }

    @Test
    void elapsedTimeNotSamplingCountControlsSpringAndCopiesAreIndependent() {
        EffectsMotion.Spring first = new EffectsMotion.Spring(0, DEFAULT_SPATIAL);
        EffectsMotion.Spring second = new EffectsMotion.Spring(0, DEFAULT_SPATIAL);
        first.retarget(3, 0, 1);
        second.retarget(3, 0, 1);
        for (long t = 0; t < 300_000_000L; t += 7_000_000L) first.value(t);
        for (long t = 0; t < 300_000_000L; t += 33_000_000L) second.value(t);
        assertEquals(first.value(320_000_000L), second.value(320_000_000L), 0);
        assertEquals(first.velocity(320_000_000L), second.velocity(320_000_000L), 0);
        EffectsMotion.Spring copy = first.copy();
        assertEquals(first.value(170_000_000L), copy.value(170_000_000L), 0);
        copy.retarget(0, 170_000_000L, 1);
        assertEquals(second.value(320_000_000L), first.value(320_000_000L), 0);
        assertNotEquals(copy.value(320_000_000L), first.value(320_000_000L));
    }

    @Test
    void timeScaleChangesDurationWithoutChangingCurveAndZeroMeansNoSpatialMotion() {
        EffectsMotion.Spring normal = new EffectsMotion.Spring(0, SLOW_SPATIAL);
        EffectsMotion.Spring slower = new EffectsMotion.Spring(0, SLOW_SPATIAL);
        normal.retarget(1, 0, 1);
        slower.retarget(1, 0, 3);
        for (long t = 0; t <= 500_000_000L; t += 10_000_000L) {
            assertEquals(normal.value(t), slower.value(t * 3), 1e-12);
        }
        slower.retarget(9, 100_000_000L, 0);
        assertEquals(9, slower.value(100_000_000L), 0);
        assertEquals(0, slower.velocity(100_000_000L), 0);
        assertTrue(slower.settled(100_000_000L));
        assertThrows(IllegalArgumentException.class, () -> slower.retarget(1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> slower.retarget(Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new EffectsMotion.Spring(Double.NaN, DEFAULT_SPATIAL));
    }

    @Test
    void nativeClockSpatialSamplingStartsAndEndsExactlyAndHasBoundedBounce() {
        double maximum = 0;
        for (int i = 0; i <= 1000; i++) {
            double value = EffectsMotion.spatial(i / 1000.0, DEFAULT_SPATIAL);
            assertTrue(Double.isFinite(value) && value >= 0 && value < 1.03);
            maximum = Math.max(maximum, value);
        }
        assertEquals(0, EffectsMotion.spatial(0, DEFAULT_SPATIAL));
        assertEquals(1, EffectsMotion.spatial(1, DEFAULT_SPATIAL));
        assertTrue(maximum > 1);
    }
}
