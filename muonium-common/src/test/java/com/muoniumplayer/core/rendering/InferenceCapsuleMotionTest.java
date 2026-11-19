package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InferenceCapsuleMotionTest {
    @Test
    void phaseWrapsWithoutJumpingOutsideTheLoop() {
        assertEquals(0.0, InferenceCapsuleMotion.phase(0L), 1e-12);
        assertEquals(InferenceCapsuleMotion.phase(0L),
                InferenceCapsuleMotion.phase(InferenceCapsuleMotion.LOOP_MILLIS), 1e-12);
        assertTrue(InferenceCapsuleMotion.phase(824L) > 0.49);
        assertTrue(InferenceCapsuleMotion.phase(824L) < .51);
    }

    @Test
    void roundedPathStartsAndEndsAtTheSamePoint() {
        double width = 176.0;
        double height = 26.0;
        double radius = 13.0;
        InferenceCapsuleMotion.Point first = InferenceCapsuleMotion.pointAt(width, height, radius, 0.0);
        InferenceCapsuleMotion.Point wrapped = InferenceCapsuleMotion.pointAt(width, height, radius, 1.0);
        assertEquals(first.x, wrapped.x, 1e-9);
        assertEquals(first.y, wrapped.y, 1e-9);
        assertTrue(InferenceCapsuleMotion.sampleCount(width, height, radius) >= 12);
    }

    @Test
    void invalidGeometryHasFiniteSafeResults() {
        assertEquals(0, InferenceCapsuleMotion.sampleCount(Double.NaN, 26.0, 13.0));
        InferenceCapsuleMotion.Point point = InferenceCapsuleMotion.pointAt(Double.NaN, -1.0,
                Double.POSITIVE_INFINITY, Double.NaN);
        assertTrue(Double.isFinite(point.x));
        assertTrue(Double.isFinite(point.y));
        assertTrue(Double.isFinite(point.tangentDegrees));
    }
}
