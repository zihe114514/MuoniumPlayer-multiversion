package com.muoniumplayer.core.screens.ncm.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EffectsLayoutMotionTest {

    @Test
    void responsiveRegionsStayInsideTheSurfaceAndDoNotOverlap() {
        for (double width : new double[]{0, 120, 280, 360, 480, 640, 800, 1024, 1600}) {
            for (double height : new double[]{0, 140, 260, 360, 600, 800}) {
                EffectsLayout layout = EffectsLayout.calculate(width, height, 8);
                assertTrue(layout.surface.x() >= 0);
                assertTrue(layout.surface.y() >= 0);
                assertTrue(layout.surface.right() <= width + .001);
                assertTrue(layout.surface.bottom() <= height + .001);
                assertTrue(layout.viewport.width() >= 0);
                assertTrue(layout.viewport.height() >= 0);
                assertTrue(layout.viewport.y() >= layout.toolbar.bottom() - .001);
                assertTrue(layout.viewport.bottom() <= layout.status.y() + .001);
                if (layout.usable) {
                    assertTrue(layout.toolbar.right() <= layout.surface.right());
                    assertTrue(layout.viewport.right() <= layout.surface.right());
                    if (layout.rail) assertTrue(layout.navigation.right() < layout.toolbar.x());
                    else assertTrue(layout.navigation.bottom() <= layout.toolbar.y());
                }
            }
        }
    }

    @Test
    void shortWideWindowsSwitchToTopNavigationInsteadOfClippingCategories() {
        assertFalse(EffectsLayout.calculate(1800, 450, 0).rail);
        assertTrue(EffectsLayout.calculate(1800, 800, 0).rail);
        EffectsLayout compact = EffectsLayout.calculate(1800, 450, 0);
        assertTrue(compact.navigation.bottom() <= compact.toolbar.y());
        assertTrue(compact.viewport.height() > 0);
    }

    @Test
    void designViewportKeepsTheDesktopDialogAtItsIntendedSize() {
        EffectsLayout.Viewport viewport = new EffectsLayout.Viewport(928, 508, 2);
        EffectsLayout layout = EffectsLayout.calculate(viewport.width(), viewport.height(), 0);
        EffectsLayout.Box shown = viewport.toLayout(layout.surface);
        assertEquals(viewport.hostWidth() * .74, shown.width(), 1e-9);
        assertEquals(viewport.hostHeight() * .78, shown.height(), 1e-9);
        assertTrue(shown.width() >= viewport.hostWidth() * .60 && shown.width() <= viewport.hostWidth() * .80);
        assertTrue(shown.height() >= viewport.hostHeight() * .60 && shown.height() <= viewport.hostHeight() * .80);
        assertEquals(viewport.hostWidth() * .5, shown.centerX(), 1e-9);
        assertEquals(viewport.hostHeight() * .5, shown.centerY(), 1e-9);
        assertEquals(.5, viewport.renderScale(), 0);
        for (double coordinate : new double[]{-10, 0, 12.5, 250, 928}) {
            assertEquals(coordinate, viewport.toLayout(viewport.toDesign(coordinate)), 1e-9);
        }
    }

    @Test
    void guiScaleDoesNotChangeTheAdaptiveDialogsPhysicalGeometry() {
        double pixelWidth = 1920, pixelHeight = 1080;
        EffectsLayout.Viewport viewport = new EffectsLayout.Viewport(pixelWidth * .5 - 32, pixelHeight * .5 - 32, 2);
        EffectsLayout layout = EffectsLayout.calculate(viewport.width(), viewport.height(), 0);
        for (double guiScale : new double[]{1, 2, 3, 4, 5}) {
            double correction = (pixelWidth / guiScale) / (pixelWidth * .5);
            double screenWidth = layout.surface.width() * viewport.renderScale() * correction * guiScale;
            double screenHeight = layout.surface.height() * viewport.renderScale() * correction * guiScale;
            assertEquals(viewport.width() * .74, screenWidth, 1e-9);
            assertEquals(viewport.height() * .78, screenHeight, 1e-9);
            double pointer = layout.surface.centerX() * viewport.renderScale() * correction;
            double hostPointer = pointer / correction;
            assertEquals(layout.surface.centerX(), viewport.toDesign(hostPointer), 1e-9);
        }
    }

    @Test
    void smallWindowsReserveSpaceAndKeepInputDeltasOnTheDesignAxis() {
        for (double width : new double[]{320, 480, 720, 1000, 1920}) {
            for (double height : new double[]{240, 360, 720, 1080}) {
                EffectsLayout.Viewport viewport = new EffectsLayout.Viewport(width * .5, height * .5, 2);
                EffectsLayout layout = EffectsLayout.calculate(viewport.width(), viewport.height(), 0);
                assertEquals(viewport.width() * .74, layout.surface.width(), 1e-9);
                assertEquals(viewport.height() * .78, layout.surface.height(), 1e-9);
                EffectsLayout.Box shown = viewport.toLayout(layout.surface);
                assertTrue(shown.right() <= viewport.hostWidth());
                assertTrue(shown.bottom() <= viewport.hostHeight());
            }
        }
        EffectsLayout.Viewport viewport = new EffectsLayout.Viewport(480, 270, 2);
        assertEquals(36, viewport.toDesign(18), 0, "触屏布局轴位移必须随图形同样换算");
        assertThrows(IllegalArgumentException.class, () -> new EffectsLayout.Viewport(100, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> new EffectsLayout.Viewport(100, Double.NaN, 2));
    }

    @Test
    void logarithmicChartCoordinatesRoundTripAndRejectNonfiniteValues() {
        EffectsLayout.Box plot = new EffectsLayout.Box(20, 50, 600, 180);
        for (double db : new double[]{-12, -6, 0, 6, 12}) {
            assertEquals(db, EffectsLayout.gainAtY(EffectsLayout.gainY(db, plot), plot), 1e-9);
        }
        assertEquals(20, EffectsLayout.frequencyX(20, plot), 1e-9);
        assertEquals(620, EffectsLayout.frequencyX(20000, plot), 1e-9);
        assertEquals(EffectsLayout.frequencyX(200, plot) - EffectsLayout.frequencyX(20, plot),
                EffectsLayout.frequencyX(2000, plot) - EffectsLayout.frequencyX(200, plot), 1e-9);
        assertEquals(12, EffectsLayout.gainAtY(-500, plot));
        assertEquals(-12, EffectsLayout.gainAtY(1000, plot));
        assertThrows(IllegalArgumentException.class, () -> EffectsLayout.frequencyX(0, plot));
        assertThrows(IllegalArgumentException.class, () -> EffectsLayout.gainY(Double.NaN, plot));
        assertThrows(IllegalArgumentException.class, () -> EffectsLayout.calculate(Double.POSITIVE_INFINITY, 500, 0));
    }

    @Test
    void clippedHitTargetsCannotBeClickedOutsideTheViewport() {
        EffectsLayout.Box card = new EffectsLayout.Box(10, 10, 300, 70);
        EffectsLayout.Box viewport = new EffectsLayout.Box(10, 30, 300, 20);
        EffectsLayout.Box visible = card.intersect(viewport);
        assertFalse(visible.contains(20, 15));
        assertTrue(visible.contains(20, 35));
        assertFalse(visible.contains(20, 51));
        assertEquals(20, visible.height());
    }

    @Test
    void boundedMotionRetargetsWithoutJumpingAndIsFrameRateIndependent() {
        EffectsMotion motion = new EffectsMotion(0);
        motion.retarget(1, 1_000_000, 300);
        double midway = motion.value(151_000_000);
        assertTrue(midway > 0 && midway < 1);
        motion.retarget(0, 151_000_000, 200);
        assertEquals(midway, motion.value(151_000_000), 1e-9);
        assertEquals(0, motion.value(351_000_000), 1e-9);
        assertTrue(motion.settled(351_000_000));
        EffectsMotion first = new EffectsMotion(0), second = new EffectsMotion(0);
        first.retarget(100, 0, 300);
        second.retarget(100, 0, 300);
        for (long t = 0; t <= 200_000_000; t += 7_000_000) first.value(t);
        for (long t = 0; t <= 200_000_000; t += 33_000_000) second.value(t);
        assertEquals(first.value(250_000_000), second.value(250_000_000), 0);
        assertEquals(100, first.value(300_000_000), 0);
    }

    @Test
    void easingIsMonotonicAndScrollClampsAfterResizeAndFiltering() {
        double previous = 0;
        for (int i = 0; i <= 100; i++) {
            double value = EffectsMotion.emphasized(i / 100.0);
            assertTrue(value >= previous);
            previous = value;
        }
        EffectsMotion.Scroll scroll = new EffectsMotion.Scroll();
        scroll.bounds(1000, 300, 0);
        scroll.move(900, 0, 200);
        assertEquals(700, scroll.target());
        assertEquals(700, scroll.value(300_000_000));
        scroll.bounds(100, 300, 400_000_000);
        assertEquals(0, scroll.max());
        assertEquals(0, scroll.value(400_000_000));
        assertThrows(IllegalArgumentException.class, () -> EffectsMotion.emphasized(Double.NaN));
    }
}
