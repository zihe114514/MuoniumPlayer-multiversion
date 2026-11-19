package com.muoniumplayer.core.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TouchGestureCoordinateSpaceTest {
    private static final double EPSILON = 1e-9;

    @Test
    void localViewTransformsOriginAndDeltasWithoutChangingRecognition() {
        TouchGestures gestures = dragging();
        double velocity = gestures.velocityY();
        Object owner = new Object();
        gestures.withCoordinateSpace(16, 22, .5, () -> {
            assertEquals(184, gestures.startX(), EPSILON);
            assertEquals(178, gestures.startY(), EPSILON);
            assertEquals(24, gestures.dy(), EPSILON);
            assertEquals(velocity * 2, gestures.velocityY(), EPSILON);
            assertTrue(gestures.isPressed());
            assertEquals(TouchGestures.Axis.VERTICAL, gestures.axis());
            assertTrue(gestures.claim(owner));
        });
        assertEquals(100, gestures.startX(), EPSILON);
        assertEquals(100, gestures.startY(), EPSILON);
        assertEquals(12, gestures.dy(), EPSILON);
        assertEquals(velocity, gestures.velocityY(), EPSILON);
        assertSame(owner, gestures.claimedBy());
        gestures.advance(32_000_000, 100, 112, false);
        assertFalse(gestures.consumeTap());
    }

    @Test
    void nestedViewsComposeAndRestoreOnException() {
        TouchGestures gestures = dragging();
        gestures.withCoordinateSpace(16, 22, .5, () -> {
            assertThrows(IllegalStateException.class, () -> gestures.withCoordinateSpace(40, 50, 2, () -> {
                assertEquals(112, gestures.startX(), EPSILON);
                assertEquals(114, gestures.startY(), EPSILON);
                assertEquals(12, gestures.dy(), EPSILON);
                throw new IllegalStateException("fixture");
            }));
            assertEquals(184, gestures.startX(), EPSILON);
            assertEquals(178, gestures.startY(), EPSILON);
            assertEquals(24, gestures.dy(), EPSILON);
        });
        assertEquals(100, gestures.startX(), EPSILON);
        assertEquals(12, gestures.dy(), EPSILON);
    }

    @Test
    void tapAndAxisGatingRemainRaw() {
        TouchGestures gestures = TouchGestures.newInstanceForTest();
        gestures.advance(0, 100, 100, true);
        gestures.withCoordinateSpace(10, 20, .25, () -> {
            assertEquals(0, gestures.dx());
            assertEquals(0, gestures.dy());
            assertEquals(TouchGestures.Axis.NONE, gestures.axis());
            assertFalse(gestures.claim(new Object()));
        });
        gestures.advance(16_000_000, 103, 101, false);
        assertTrue(gestures.consumeTap());
    }

    @Test
    void invalidScaleDoesNotInstallAViewOrRunCallback() {
        TouchGestures gestures = dragging();
        for (double scale : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> gestures.withCoordinateSpace(0, 0, scale,
                    () -> fail("Invalid view must not execute")));
            assertEquals(100, gestures.startX(), EPSILON);
            assertEquals(12, gestures.dy(), EPSILON);
        }
        assertThrows(IllegalArgumentException.class, () -> gestures.withCoordinateSpace(Double.NaN, 0, 1, () -> {}));
    }

    private static TouchGestures dragging() {
        TouchGestures gestures = TouchGestures.newInstanceForTest();
        gestures.advance(0, 100, 100, true);
        gestures.advance(16_000_000, 100, 112, true);
        return gestures;
    }
}
