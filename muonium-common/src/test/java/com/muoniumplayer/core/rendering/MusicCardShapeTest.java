package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardShapeTest {
    @Test
    void roundedShoulderStartsWithZeroSlopeAndCurvature() {
        double epsilon = .001;
        double first = MusicCardShape.cornerY(epsilon);
        double second = MusicCardShape.cornerY(epsilon * 2.0);
        assertTrue(first < 1e-8);
        assertTrue((second - 2.0 * first) / (epsilon * epsilon) < .02,
                "直边接圆角时曲率应逐渐长出来，不能突然切到圆弧");
        assertEquals(.2, MusicCardShape.cornerY(.8), 1e-6);
    }

    @Test
    void cornerHasNoGapAndRemainsMonotonic() {
        double previous = 0.0;
        for (int i = 0; i <= 1000; i++) {
            double y = MusicCardShape.cornerY(i / 1000.0);
            assertTrue(y >= previous && y <= 1.0);
            previous = y;
        }
        double h = .0001;
        double leftSlope = (MusicCardShape.cornerY(.8) - MusicCardShape.cornerY(.8 - h)) / h;
        double rightSlope = (MusicCardShape.cornerY(.8 + h) - MusicCardShape.cornerY(.8)) / h;
        assertEquals(leftSlope, rightSlope, .003, "两个贝塞尔段中间的切线必须连续");
    }

    @Test
    void transparentCornersStayTransparentForClickMappingAtEveryScale() {
        for (double scale : new double[]{.35, .7, 1.0, 1.35, 2.0}) {
            double w = MusicCardLayout.WIDTH * scale;
            double h = MusicCardLayout.HEIGHT * scale;
            double r = MusicCardLayout.RADIUS * scale;
            assertFalse(MusicCardShape.contains(10, 20, 10, 20, w, h, r));
            assertTrue(MusicCardShape.contains(10 + w * .5, 20, 10, 20, w, h, r));
            assertTrue(MusicCardShape.contains(10 + w * .5, 20 + h * .5, 10, 20, w, h, r));
            assertFalse(MusicCardShape.contains(9, 20 + h * .5, 10, 20, w, h, r));
        }
    }
}
