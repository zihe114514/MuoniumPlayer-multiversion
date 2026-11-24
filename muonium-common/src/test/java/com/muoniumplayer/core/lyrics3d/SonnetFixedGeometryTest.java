package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SonnetFixedGeometryTest {
    private static final int[] SEEDS = {10, 1, 2, 6, 0, 4, 8};

    @Test
    void layersDrawInOrderAndRetractInReverseOrder() {
        var firstIn = motion(0, 0.2f, 0f, 0, 5);
        var lastIn = motion(0, 0.2f, 0f, 4, 5);
        assertTrue(firstIn.visible());
        assertFalse(lastIn.visible(), "later detail must wait for the main contour");
        var firstOut = motion(0, 1f, 0.1f, 0, 5);
        var lastOut = motion(0, 1f, 0.1f, 4, 5);
        assertEquals(0f, firstOut.from());
        assertEquals(1f, firstOut.to());
        assertTrue(lastOut.from() > 0f && lastOut.to() < 1f,
                "secondary detail must start contracting before the main contour");
    }

    @Test
    void everyLayerIsCompleteAtHoldAndEmptyAtBothLifecycleEnds() {
        for (int count : new int[]{1, 2, 5, 8, 21}) {
            for (int index = 0; index < count; index++) {
                assertFalse(motion(0, 0f, 0f, index, count).visible());
                var hold = motion(0, 1f, 0f, index, count);
                assertEquals(0f, hold.from());
                assertEquals(1f, hold.to());
                assertFalse(motion(0, 1f, 1f, index, count).visible());
                var exit = motion(0, 1f, 0.7f, index, count);
                assertTrue(exit.from() > 0f && exit.to() < 1f);
                assertTrue(exit.expansion() < hold.expansion(), "exit must contract the actual coordinates");
            }
        }
    }

    @Test
    void phaseRangesStayOrderedAndContinuousIncludingInterruptedEntry() {
        for (int index = 0; index < 8; index++) {
            for (int step = 0; step <= 100; step++) {
                float progress = step / 100f;
                var state = motion(3.7, progress, progress, index, 8);
                assertTrue(state.from() >= 0f && state.to() <= 1f && state.from() <= state.to());
                var adjacent = motion(3.7, progress + 0.00001f, progress + 0.00001f, index, 8);
                assertEquals(state.from(), adjacent.from(), 0.0001f);
                assertEquals(state.to(), adjacent.to(), 0.0001f);
                assertEquals(state.expansion(), adjacent.expansion(), 0.0001f);
            }
        }
    }

    @Test
    void timeDrivesIndependentLocalBreathsAndSeekingHasNoHistory() {
        var atRest = motion(0, 1f, 0f, 0, 5);
        var later = motion(0.8, 1f, 0f, 0, 5);
        assertNotEquals(atRest.breath(), later.breath());
        assertNotEquals(atRest.expansion(), later.expansion());
        assertNotEquals(later.breath(), motion(0.8, 1f, 0f, 1, 5).breath());
        motion(12345, 0.2f, 0.8f, 4, 5);
        assertEquals(later, motion(0.8, 1f, 0f, 0, 5));
        assertEquals(motion(0.8, 1f, 0f, 0, 5), motion(0.8, 2f, -1f, 0, 5));
        assertTrue(Float.isFinite(motion(Double.MAX_VALUE, 1f, 0f, 0, 5).breath()));
        assertFalse(motion(Double.NaN, 1f, 0f, 0, 5).visible());
        assertFalse(motion(0, Float.NaN, 0f, 0, 5).visible());
        assertFalse(motion(0, 1f, Float.POSITIVE_INFINITY, 0, 5).visible());
    }

    @Test
    void polylineRevealUsesDistanceAcrossCornersAndRetractionTrimsBothEnds() {
        float[] elbow = {0f, 0f, 30f, 0f, 30f, 40f};
        assertArrayEquals(new float[]{0f, 0f, 30f, 0f, 30f, 5f},
                SonnetFixedGeometry.trimPolyline(elbow, 0f, 0.5f), 0.0001f);
        assertArrayEquals(new float[]{14f, 0f, 30f, 0f, 30f, 26f},
                SonnetFixedGeometry.trimPolyline(elbow, 0.2f, 0.8f), 0.0001f);
        float[] frame = {-50f, -36f, 50f, -36f, 50f, 36f, -50f, 36f, -50f, -36f};
        assertArrayEquals(new float[]{-50f, -36f, 36f, -36f},
                SonnetFixedGeometry.trimPolyline(frame, 0f, 0.25f), 0.0001f);
        assertArrayEquals(new float[]{36f, -36f, 50f, -36f, 50f, 36f, -36f, 36f},
                SonnetFixedGeometry.trimPolyline(frame, 0.25f, 0.75f), 0.0001f);
    }

    @Test
    void polylineTrimmingHandlesZeroLengthSegmentsAndEmptyWindows() {
        assertArrayEquals(new float[]{0f, 0f, 5f, 0f}, SonnetFixedGeometry.trimPolyline(
                new float[]{0f, 0f, 0f, 0f, 10f, 0f}, 0f, 0.5f), 0.0001f);
        assertArrayEquals(new float[0], SonnetFixedGeometry.trimPolyline(new float[]{0f, 0f, 0f, 0f}, 0f, 1f));
        assertArrayEquals(new float[0], SonnetFixedGeometry.trimPolyline(new float[]{0f, 0f, 10f, 0f}, 0.5f, 0.5f));
        assertArrayEquals(new float[0], SonnetFixedGeometry.trimPolyline(new float[]{0f, 0f, 10f, 0f}, 1f, 0f));
        assertArrayEquals(new float[0], SonnetFixedGeometry.trimPolyline(new float[]{0f, 0f, 10f, 0f}, Float.NaN, 1f));
    }

    @Test
    void archesFollowTheExactSemicircleBetweenEqualLengthLegs() {
        assertPoint(-10f, 30f, SonnetFixedGeometry.archPoint(10f, 30f, 20f, 0f));
        assertPoint(10f, -20f, SonnetFixedGeometry.archPoint(10f, 30f, 20f, 0.5f));
        assertPoint(30f, 30f, SonnetFixedGeometry.archPoint(10f, 30f, 20f, 1f));
        float leftJunction = (float) (30 / (60 + Math.PI * 20));
        assertPoint(-10f, 0f, SonnetFixedGeometry.archPoint(10f, 30f, 20f, leftJunction));
        assertPoint(30f, 0f, SonnetFixedGeometry.archPoint(10f, 30f, 20f, 1f - leftJunction));
    }

    private static SonnetFixedGeometry.LayerMotion motion(double seconds, float enter, float exit, int index, int count) {
        return SonnetFixedGeometry.layerMotion(seconds, enter, exit, 0, index, count);
    }

    private static void assertPoint(float x, float y, SonnetFixedGeometry.Point point) {
        assertEquals(x, point.x(), 0.0001f);
        assertEquals(y, point.y(), 0.0001f);
    }

    @Nested
    @RequiresSkijaNative
    class NativePixels {
        @BeforeEach
        void loadNative() { assertTrue(SkijaNatives.ensureLoaded()); }

        @AfterEach
        void releaseResources() { SonnetDecorations.close(); }

        @Test
        void allSevenDesignsDrawHoldAndRetractTheirGeometryAtConstantOpacity() {
            for (int seed : SEEDS) {
                String design = SonnetFixedGeometry.design(seed, false).name();
                byte[] early = raster(seed, false, 0.2f, 0f, 0, false);
                byte[] hold = raster(seed, false, 1f, 0f, 0, false);
                byte[] exit = raster(seed, false, 1f, 0.7f, 0, false);
                assertTrue(occupied(early) > 0, design + " needs an initial path segment");
                assertTrue(occupied(hold) > occupied(early), design + " must draw additional geometry");
                assertTrue(occupied(exit) > 0 && occupied(exit) < occupied(hold), design + " must retract geometry");
                assertFalse(Arrays.equals(mask(early), mask(hold)), design + " entry cannot be only alpha");
                assertFalse(Arrays.equals(mask(exit), mask(hold)), design + " exit cannot be only alpha");
                assertEquals(0, occupied(raster(seed, false, 0f, 0f, 0, false)), design);
                assertEquals(0, occupied(raster(seed, false, 1f, 1f, 0, false)), design);
            }
        }

        @Test
        void everyDesignBreathesWhileHeldAndRepeatedTimestampsAreIdentical() {
            for (int seed : SEEDS) {
                byte[] first = raster(seed, false, 1f, 0f, 0, false);
                byte[] later = raster(seed, false, 1f, 0f, 0.8, false);
                byte[] anotherPhase = raster(seed, false, 1f, 0f, 2.1, false);
                assertFalse(Arrays.equals(mask(first), mask(later)) && Arrays.equals(mask(first), mask(anotherPhase)),
                        "held geometry must breathe for seed " + seed);
                raster(seed, false, 0.4f, 0.7f, 123, false);
                assertArrayEquals(later, raster(seed, false, 1f, 0f, 0.8, false), "seek must be history independent");
            }
        }

        @Test
        void lifecycleOverloadKeepsTheHoldWindowAndBothInvisibleEnds() {
            for (int seed : SEEDS) {
                assertEquals(0, occupied(raster(seed, false, 0f, 0f, 2, true)));
                assertEquals(0, occupied(raster(seed, false, 1f, 0f, 2, true)));
                assertArrayEquals(raster(seed, false, 1f, 0f, 2, false),
                        raster(seed, false, 0.3f, 0f, 2, true));
                assertArrayEquals(raster(seed, false, 1f, 0f, 2, false),
                        raster(seed, false, 0.76f, 0f, 2, true));
            }
        }

        @Test
        void chorusRetainsADelayedOuterFrame() {
            byte[] early = raster(0, true, 0.2f, 0f, 0, false);
            byte[] hold = raster(0, true, 1f, 0f, 0, false);
            // Seed 0 chorus: width=150, height=108; the outer frame is 180x129.6.
            assertTrue(occupied(hold, 160, 182, 170, 330) > 0, "original outer frame must remain present");
            assertEquals(0, occupied(early, 160, 182, 170, 330), "outer contour must wait and unfold");
        }

        @Test
        void invalidClockOrPhaseDrawsNothing() {
            assertEquals(0, occupied(raster(10, false, 1f, 0f, Double.NaN, false)));
            assertEquals(0, occupied(raster(10, false, Float.NaN, 0f, 0, false)));
            assertEquals(0, occupied(raster(10, false, 1f, Float.NaN, 0, false)));
        }
    }

    private static byte[] raster(int seed, boolean chorus, float enter, float exit, double seconds, boolean lifecycle) {
        try (Bitmap bitmap = new Bitmap()) {
            assertTrue(bitmap.allocPixels(ImageInfo.makeN32Premul(512, 512)));
            try (Canvas canvas = new Canvas(bitmap)) {
                // The public bridge owns lazy paint/path initialization for both draw signatures.
                SonnetDecorations.drawFixedGeometry(canvas, 256f, 256f, 0f, 40f, 800f,
                        0xFFFFFF, 0xFF0000, 0x0000FF, seed, chorus, 1f);
                canvas.clear(0);
                int save = canvas.save();
                try {
                    canvas.translate(256f, 256f);
                    if (lifecycle) SonnetFixedGeometry.draw(canvas, 40f, 800f, 0xFFFFFF, 0xFF0000, 0x0000FF,
                            seed, chorus, 1f, seconds, enter);
                    else SonnetFixedGeometry.draw(canvas, 40f, 800f, 0xFFFFFF, 0xFF0000, 0x0000FF,
                            seed, chorus, 1f, seconds, enter, exit);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            byte[] bytes = bitmap.readPixels();
            assertNotNull(bytes);
            return bytes;
        }
    }

    private static boolean[] mask(byte[] bytes) {
        boolean[] result = new boolean[bytes.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = (bytes[i * 4 + 3] & 255) != 0;
        return result;
    }

    private static int occupied(byte[] bytes) {
        int count = 0;
        for (int i = 3; i < bytes.length; i += 4) if ((bytes[i] & 255) != 0) count++;
        return count;
    }

    private static int occupied(byte[] bytes, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            if ((bytes[(y * 512 + x) * 4 + 3] & 255) != 0) count++;
        }
        return count;
    }
}
