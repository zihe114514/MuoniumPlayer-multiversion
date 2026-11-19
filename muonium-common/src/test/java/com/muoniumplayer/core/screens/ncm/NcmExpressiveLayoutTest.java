package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure policy checks only; actual widget/canvas dispatch is exercised by the isolated raster probe. */
class NcmExpressiveLayoutTest {
    private static final double EPSILON = 1e-9;

    @Test
    void reproducedPlayerHostUsesDesignPixelsRatherThanTwoTimesGuiUnits() {
        double scale = NcmExpressiveLayout.scaleFor(928, 472, 2);
        assertEquals(.55, scale, EPSILON);
        assertEquals(715, 650 * scale * 2, EPSILON);
        assertTrue(650 * scale * 2 >= 650 && 650 * scale * 2 <= 750);
    }

    @Test
    void physicalDensityIsInvariantUnderGuiScaleOneTwoAndFour() {
        double[][] hosts = {{1856, 944}, {864, 720}, {1856, 552}, {1920, 1032}};
        for (double[] host : hosts) {
            double physicalDensity = NcmExpressiveLayout.scaleFor(host[0], host[1], 1);
            for (double pixelsPerUnit : new double[]{1, 2, 4}) {
                double scale = NcmExpressiveLayout.scaleFor(
                        host[0] / pixelsPerUnit, host[1] / pixelsPerUnit, pixelsPerUnit);
                assertEquals(physicalDensity, scale * pixelsPerUnit, EPSILON);
                assertEquals(650 * physicalDensity, 650 * scale * pixelsPerUnit, EPSILON);
                assertEquals(host[0] / physicalDensity, host[0] / pixelsPerUnit / scale, EPSILON);
                assertEquals(host[1] / physicalDensity, host[1] / pixelsPerUnit / scale, EPSILON);
            }
        }
    }

    @Test
    void densityHasFiniteLowerAndUpperBoundsAcrossPositiveHostSizes() {
        for (double width : new double[]{1, 320, 432, 928, 1920, 7680}) {
            for (double height : new double[]{1, 160, 276, 472, 1032, 4320}) {
                for (double pixelsPerUnit : new double[]{.5, 1, 2, 4}) {
                    double scale = NcmExpressiveLayout.scaleFor(width, height, pixelsPerUnit);
                    assertTrue(Double.isFinite(scale) && scale > 0);
                    assertTrue(scale * pixelsPerUnit >= .78 - EPSILON);
                    assertTrue(scale * pixelsPerUnit <= 1.10 + EPSILON);
                }
            }
        }
    }

    @Test
    void invalidPixelDensityFallsBackToOneWithoutNonFiniteScale() {
        double expected = NcmExpressiveLayout.scaleFor(928, 472, 1);
        for (double pixelsPerUnit : new double[]{0, -1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(expected, NcmExpressiveLayout.scaleFor(928, 472, pixelsPerUnit), EPSILON);
        }
    }

    @Test
    void shortOrNarrowPhysicalHostConstrainsDensityAndResizeRecalculatesIt() {
        assertEquals(.78, NcmExpressiveLayout.scaleFor(1856, 552, 1), EPSILON);
        assertEquals(.9, NcmExpressiveLayout.scaleFor(864, 720, 1), EPSILON);
        assertEquals(1.10, NcmExpressiveLayout.scaleFor(1856, 944, 1), EPSILON);
        double previous = 0;
        for (double factor : new double[]{.1, .25, .5, .75, 1, 2, 4}) {
            double current = NcmExpressiveLayout.scaleFor(960 * factor, 720 * factor, 1);
            assertTrue(current >= previous);
            previous = current;
        }
    }

    @Test
    void inverseCoordinatesPreserveNonzeroHostOriginForCachedRenderFixture() throws Exception {
        NcmExpressiveLayout layout = new NcmExpressiveLayout();
        // Explicit fixture seam for the last-render cache, not evidence of canvas or event dispatch.
        set(layout, "originX", 16);
        set(layout, "originY", 22);
        for (double scale : new double[]{.195, .45, .55, 1.10}) {
            set(layout, "scale", scale);
            assertEquals(scale, layout.scale(), EPSILON);
            for (double local : new double[]{-40, 0, 1, 240, 650, 1200}) {
                assertEquals(16 + local, layout.toLogicalX(16 + local * scale), EPSILON);
                assertEquals(22 + local, layout.toLogicalY(22 + local * scale), EPSILON);
            }
        }
    }

    private static void set(NcmExpressiveLayout layout, String name, double value) throws Exception {
        Field field = NcmExpressiveLayout.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setDouble(layout, value);
    }
}
