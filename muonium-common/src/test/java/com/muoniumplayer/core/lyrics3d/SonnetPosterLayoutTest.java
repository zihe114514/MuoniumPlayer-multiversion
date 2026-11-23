package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.lyrics3d.SonnetTypography.Box;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Glyph;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Role;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Segment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SonnetPosterLayoutTest {
    private static final float EPSILON = 0.00015f;

    @Test
    void horizontalZonesAndJustifiedSupportsMatchUpstreamCoordinates() {
        List<Box> boxes = basic();
        List<Box> order = List.copyOf(boxes);
        SonnetPosterLayout.layout(boxes, 1280f, 720f, 40f, 28);
        // Independent goldens: direct execution of upstream layoutSonnetPosterBlocks with Node.
        assertBox(boxes.get(0), -473.6, -165.6, 128, 42, 46, false, false, -18, 10);
        assertBox(boxes.get(1), -445.6, -92.2, 184, 68, 124, false, false, -18, 10);
        assertBox(boxes.get(2), -357.6, 31.2, 360, 142, 176, false, false, -18, 10);
        assertBox(boxes.get(3), -103.6, -19.8, 116, 40, 46, false, false, -18, 10);
        assertBox(boxes.get(4), 86.4, -19.8, 152, 40, 46, false, false, 18, 10);
        assertBox(boxes.get(5), -433.6, 153.6, 208, 66, 124, false, false, -18, 10);
        assertBox(boxes.get(6), -261.6, 140.6, 104, 40, 46, false, false, -18, 10);
        for (int i = 0; i < order.size(); i++) {
            assertSame(order.get(i), boxes.get(i), "layout may move boxes, never reorder the timeline");
            assertEquals(i, boxes.get(i).segmentIndex);
        }
        assertNoOverlap(boxes);
        assertInsideCanvas(boxes, 1280, 720);
    }

    @Test
    void verticalFlowUsesRightToLeftColumnsWithoutInventingGlyphOrientation() {
        List<Box> boxes = basic();
        SonnetPosterLayout.layout(boxes, 1280f, 720f, 40f, 29);
        assertBox(boxes.get(0), 403.6, -267, 128, 42, 46, false, true, 10, -18);
        assertBox(boxes.get(1), 229.2, -254, 184, 68, 124, false, true, 10, -18);
        assertBox(boxes.get(2), -61.2, -217, 360, 142, 176, false, true, 10, -18);
        assertBox(boxes.get(3), 60.8, -110, 116, 40, 46, false, true, 10, -18);
        assertBox(boxes.get(4), 42.8, -14, 152, 40, 46, false, true, 10, -18);
        assertBox(boxes.get(5), -363.6, -255, 208, 66, 124, false, true, 10, -18);
        assertBox(boxes.get(6), -311.6, -186, 104, 40, 46, false, true, 10, -18);
        assertTrue(boxes.get(0).x > boxes.get(1).x && boxes.get(1).x > boxes.get(2).x);
        assertTrue(boxes.get(3).y < boxes.get(4).y, "supports scan top to bottom inside a column");
        assertNoOverlap(boxes);
        assertInsideCanvas(boxes, 1280, 720);
    }

    @Test
    void mixedVerticalMeasurementsSelectExactColumnDimensionsAndActualFontSizes() {
        List<Box> boxes = new ArrayList<>(List.of(
                box(0, 240, 80, Role.HERO, 36), box(1, 100, 35, Role.SUPPORT, 36),
                box(2, 140, 38, Role.SUPPORT, 36), box(3, 150, 60, Role.SEMI_HERO, 36),
                box(4, 80, 30, Role.SUPPORT, 36)));
        column(boxes.get(0), 40, 200, 3.8f * 36);
        column(boxes.get(2), 28, 120, 36);
        column(boxes.get(3), 36, 180, 2.8f * 36);
        Segment firstSegment = boxes.get(0).segment;
        SonnetPosterLayout.layout(boxes, 1000, 700, 36, 5);
        assertBox(boxes.get(0), 79.2, -180, 40, 200, 136.8, true, true, 9, -16.2);
        assertBox(boxes.get(1), 49.2, -46.5, 100, 35, 41.4, false, true, 9, -16.2);
        assertBox(boxes.get(2), 85.2, 87, 28, 120, 36, true, true, 9, 16.2);
        assertBox(boxes.get(3), -37.2, -190, 36, 180, 100.8, true, true, 9, -16.2);
        assertBox(boxes.get(4), -59.2, -69, 80, 30, 41.4, false, true, 9, -16.2);
        assertSame(firstSegment, boxes.get(0).segment, "writing direction does not replace the semantic segment");
        assertEquals(40f, boxes.get(0).verticalWidth);
        assertEquals(200f, boxes.get(0).verticalHeight);
        assertEquals(136.8f, boxes.get(0).verticalFontSize, EPSILON);
        assertNoOverlap(boxes);
    }

    @Test
    void unavailableColumnMeasurementsFallBackAsAnEntireSetAndHorizontalSeedsIgnoreAlternatives() {
        for (int missing = 0; missing < 3; missing++) {
            Box incomplete = box(0, 100, 30, Role.HERO, 40);
            column(incomplete, 24, 200, 90);
            if (missing == 0) incomplete.verticalWidth = 0;
            if (missing == 1) incomplete.verticalHeight = 0;
            if (missing == 2) incomplete.verticalFontSize = 0;
            SonnetPosterLayout.layout(List.of(incomplete), 1280, 720, 40, 1);
            assertFalse(incomplete.vertical);
            assertEquals(100f, incomplete.width);
            assertEquals(30f, incomplete.height);
            assertEquals(176f, incomplete.fontSize);
        }
        Box horizontal = box(0, 100, 30, Role.HERO, 40);
        column(horizontal, 24, 200, 90);
        horizontal.vertical = true;
        horizontal.rotationRad = 0.7f;
        SonnetPosterLayout.layout(List.of(horizontal), 1280, 720, 40, 0);
        assertFalse(horizontal.vertical);
        assertFalse(horizontal.flowVertical);
        assertEquals(0f, horizontal.rotationRad);
        assertEquals(100f, horizontal.width);
        assertEquals(30f, horizontal.height);
        assertEquals(176f, horizontal.fontSize);
    }

    @Test
    void retriesUseTheFirstFittingGlobalRungWithoutAccumulatingEarlierShrinks() {
        List<Box> boxes = supports(40, 200, 100, 40);
        SonnetPosterLayout.layout(boxes, 1280, 720, 40, 0);
        assertBox(boxes.get(0), -469.6, -216, 136, 68, 31.28, false, false, -18, 10);
        assertBox(boxes.get(1), -313.0666667, -216, 136, 68, 31.28, false, false, -18, 10);
        assertBox(boxes.get(6), 469.6, -216, 136, 68, 31.28, false, false, 18, 10);
        assertBox(boxes.get(7), -469.6, -129.6, 136, 68, 31.28, false, false, -18, 10);
        assertBox(boxes.get(39), 298.4, 216, 136, 68, 31.28, false, false, 18, 10);
        for (Box box : boxes) assertEquals(0.68f, box.fontSize / 46f, EPSILON);
        assertNoOverlap(boxes);
        assertInsideCanvas(boxes, 1280, 720);
    }

    @Test
    void emergencyFitUniformlyScalesCoordinatesDimensionsFontAndInterWordGaps() {
        List<Box> boxes = supports(24, 160, 80, 40);
        SonnetPosterLayout.layout(boxes, 480, 270, 40, 0);
        assertBox(boxes.get(0), -175.2955504, -94.8477752, 52.6088993, 26.3044496,
                15.1250585, false, false, -18, 10);
        assertBox(boxes.get(1), -107.8482436, -94.8477752, 52.6088993, 26.3044496,
                15.1250585, false, false, -18, 10);
        assertBox(boxes.get(3), 27.0463700, -94.8477752, 52.6088993, 26.3044496,
                15.1250585, false, false, 18, 10);
        assertBox(boxes.get(4), -175.2955504, -56.9086651, 52.6088993, 26.3044496,
                15.1250585, false, false, -18, 10);
        assertBox(boxes.get(23), 27.0463700, 94.8477752, 52.6088993, 26.3044496,
                15.1250585, false, false, 18, 10);
        assertTrue(boxes.get(0).fontSize < 46f * 0.52f, "emergency fit must follow the final retry");
        assertEquals(14.8384075f, boxes.get(1).x - boxes.get(0).x - boxes.get(0).width, EPSILON);
        assertNoOverlap(boxes);
        assertInsideCanvas(boxes, 480, 270);
    }

    @Test
    void halfPixelRetryToleranceStillReceivesAnExactFinalFit() {
        Box box = box(0, 80, 288.25f, Role.SUPPORT, 40);
        SonnetPosterLayout.layout(List.of(box), 640, 360, 40, 0);
        assertBox(box, -228.8346921, 0, 79.9306158, 288, 45.9601041, false, false, -18, 10);
        assertTrue(box.fontSize > 46f * 0.99f, "the +.5 tolerance avoids an unnecessary .92 retry");
        assertInsideCanvas(List.of(box), 640, 360);
    }

    @Test
    void oversizedChipRetainsUpstreamHalfSizeMinimumRatherThanInventingAnotherFit() {
        Box box = box(0, 1200, 40, Role.SUPPORT, 40);
        SonnetPosterLayout.layout(List.of(box), 400, 300, 40, 0);
        assertBox(box, 132, 0, 600, 20, 23, false, false, 18, 10);
        // Upstream only emergency-fits the stacking axis. An arbitrarily oversized chip can
        // still exceed uCapacity at its .5 minimum; preserve that source behavior explicitly.
    }

    @Test
    void supportRunKeepsItsInitialFloatReservationAfterTheZoneBottomHasBeenPassed() {
        List<Box> boxes = new ArrayList<>();
        boxes.add(box(0, 320, 80, Role.HERO, 40));
        for (int i = 1; i <= 8; i++) boxes.add(box(i, 160, 30, Role.SUPPORT, 40));
        SonnetPosterLayout.layout(boxes, 800, 600, 40, 0);
        assertBox(boxes.get(0), -176, -47.6, 320, 80, 176, false, false, -18, 10);
        for (int i = 1; i <= 8; i++) {
            assertBox(boxes.get(i), i % 2 == 1 ? 80 : 256, -72.6 + ((i - 1) / 2) * 48.4,
                    160, 30, 46, false, false, 18, 10);
        }
        assertTrue(boxes.get(7).y - boxes.get(7).height / 2 > boxes.get(0).y + boxes.get(0).height / 2);
        assertEquals(boxes.get(1).x, boxes.get(7).x, "later support rows still start beside the reserved zone");
        assertNoOverlap(boxes);
    }

    @Test
    void justificationIsCappedAndStandaloneZonesAlternateTheirBandSides() {
        List<Box> supports = supports(3, 30, 20, 40);
        SonnetPosterLayout.layout(supports, 1280, 720, 40, 0);
        assertBox(supports.get(0), -522.6, 0, 30, 20, 46, false, false, -18, 10);
        assertBox(supports.get(1), -436.6, 0, 30, 20, 46, false, false, -18, 10);
        assertBox(supports.get(2), -350.6, 0, 30, 20, 46, false, false, -18, 10);
        assertEquals(56f, supports.get(1).x - supports.get(0).x - supports.get(0).width, EPSILON);
        List<Box> startSide = bands();
        List<Box> endSide = bands();
        SonnetPosterLayout.layout(startSide, 640, 360, 20, 0);
        SonnetPosterLayout.layout(endSide, 640, 360, 20, 2);
        assertBox(startSide.get(0), -218.8, -40.9, 100, 30, 88, false, false, -9, 5);
        assertBox(startSide.get(1), 233.8, 5, 70, 25, 62, false, false, 9, 5);
        assertBox(startSide.get(2), -228.8, 45.9, 80, 20, 88, false, false, -9, 5);
        for (int i = 0; i < startSide.size(); i++) {
            assertEquals(-startSide.get(i).x, endSide.get(i).x, EPSILON);
            assertEquals(startSide.get(i).y, endSide.get(i).y, EPSILON);
        }
    }

    @Test
    void repeatedSeedsKeepGeometryAndDoNotMutateIdentityRolesOrAlternativeMeasurements() {
        for (int seed : new int[] {12, 13, -13, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            List<Box> first = basic();
            List<Box> second = basic();
            SonnetPosterLayout.layout(first, 1280, 720, 40, seed);
            SonnetPosterLayout.layout(second, 1280, 720, 40, seed);
            for (int i = 0; i < first.size(); i++) {
                Box a = first.get(i), b = second.get(i);
                assertArrayEquals(new float[] {a.x, a.y, a.width, a.height, a.fontSize, a.enterX, a.enterY},
                        new float[] {b.x, b.y, b.width, b.height, b.fontSize, b.enterX, b.enterY});
                assertEquals(i, a.segmentIndex);
                assertEquals(a.role, b.role);
                assertEquals(a.role == Role.HERO, a.hero);
                assertEquals(a.role == Role.SEMI_HERO, a.semiHero);
                assertEquals(seed % 2 != 0, a.flowVertical);
                assertFalse(a.vertical);
            }
        }
        assertDoesNotThrow(() -> SonnetPosterLayout.layout(List.of(), 1280, 720, 40, 0));
        for (int seed : new int[] {0, 1}) {
            Box solo = box(0, 200, 90, Role.HERO, 40);
            SonnetPosterLayout.layout(List.of(solo), 1280, 720, 40, seed);
            assertEquals(0f, solo.x, EPSILON);
            assertEquals(0f, solo.y, EPSILON);
        }
    }

    @Test
    void denseLyricRemainsFiniteCollisionFreeAndInReadingOrder() {
        for (int seed : new int[] {98, 99}) {
            List<Box> boxes = new ArrayList<>();
            boxes.add(box(0, 420, 150, Role.HERO, 32));
            boxes.add(box(1, 230, 76, Role.SEMI_HERO, 32));
            for (int i = 0; i < 28; i++) boxes.add(box(i + 2, 72 + i % 5 * 18, 38, Role.SUPPORT, 32));
            SonnetPosterLayout.layout(boxes, 1280, 720, 32, seed);
            assertNoOverlap(boxes);
            assertInsideCanvas(boxes, 1280, 720);
            for (int i = 2; i < boxes.size(); i++) {
                Box b = boxes.get(i);
                assertTrue(Float.isFinite(b.x) && Float.isFinite(b.y) && b.fontSize > 0f);
                if (i == 2) continue;
                Box previous = boxes.get(i - 1);
                if (seed % 2 == 0) {
                    float top = b.y - b.height / 2, previousTop = previous.y - previous.height / 2;
                    assertTrue(top >= previousTop - EPSILON);
                    if (Math.abs(top - previousTop) < EPSILON) assertTrue(b.x > previous.x);
                } else {
                    float right = b.x + b.width / 2, previousRight = previous.x + previous.width / 2;
                    assertTrue(right <= previousRight + EPSILON);
                    if (Math.abs(right - previousRight) < EPSILON) assertTrue(b.y > previous.y);
                }
            }
        }
    }

    private static List<Box> basic() {
        return new ArrayList<>(List.of(box(0, 128, 42, Role.SUPPORT, 40),
                box(1, 184, 68, Role.SEMI_HERO, 40), box(2, 360, 142, Role.HERO, 40),
                box(3, 116, 40, Role.SUPPORT, 40), box(4, 152, 40, Role.SUPPORT, 40),
                box(5, 208, 66, Role.SEMI_HERO, 40), box(6, 104, 40, Role.SUPPORT, 40)));
    }
    private static List<Box> bands() {
        return new ArrayList<>(List.of(box(0, 100, 30, Role.HERO, 20),
                box(1, 70, 25, Role.SEMI_HERO, 20), box(2, 80, 20, Role.HERO, 20)));
    }
    private static List<Box> supports(int count, float width, float height, float base) {
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < count; i++) boxes.add(box(i, width, height, Role.SUPPORT, base));
        return boxes;
    }
    private static Box box(int index, float width, float height, Role role, float base) {
        String text = "詞" + index;
        Segment segment = new Segment(text, index * 3, index * 3 + text.length(), index * 200.0,
                index * 200.0 + 150.0, true, List.of(new Glyph(text, index * 200.0, index * 200.0 + 150.0)));
        Box box = new Box(segment, index, role);
        box.width = width;
        box.height = height;
        box.fontSize = (role == Role.HERO ? 4.4f : role == Role.SEMI_HERO ? 3.1f : 1.15f) * base;
        box.x = 123f;
        box.y = 456f;
        box.rotationRad = 0.7f;
        return box;
    }
    private static void column(Box box, float width, float height, float fontSize) {
        box.verticalWidth = width;
        box.verticalHeight = height;
        box.verticalFontSize = fontSize;
    }
    private static void assertBox(Box box, double x, double y, double width, double height, double fontSize,
                                  boolean vertical, boolean flowVertical, double enterX, double enterY) {
        assertEquals(x, box.x, EPSILON);
        assertEquals(y, box.y, EPSILON);
        assertEquals(width, box.width, EPSILON);
        assertEquals(height, box.height, EPSILON);
        assertEquals(fontSize, box.fontSize, EPSILON);
        assertEquals(vertical, box.vertical);
        assertEquals(flowVertical, box.flowVertical);
        assertEquals(0f, box.rotationRad);
        assertEquals(enterX, box.enterX, EPSILON);
        assertEquals(enterY, box.enterY, EPSILON);
    }
    private static void assertInsideCanvas(List<Box> boxes, float width, float height) {
        for (Box box : boxes) {
            assertTrue(Math.abs(box.x) + box.width / 2 <= width * 0.42f + EPSILON);
            assertTrue(Math.abs(box.y) + box.height / 2 <= height * 0.40f + EPSILON);
        }
    }
    private static void assertNoOverlap(List<Box> boxes) {
        for (int i = 0; i < boxes.size(); i++) for (int j = i + 1; j < boxes.size(); j++) {
            Box a = boxes.get(i), b = boxes.get(j);
            assertTrue(a.x + a.width / 2 <= b.x - b.width / 2 + EPSILON
                    || b.x + b.width / 2 <= a.x - a.width / 2 + EPSILON
                    || a.y + a.height / 2 <= b.y - b.height / 2 + EPSILON
                    || b.y + b.height / 2 <= a.y - a.height / 2 + EPSILON,
                    "boxes overlap: " + i + ", " + j);
        }
    }
}
