package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.impl.Library;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonnetStaffTest {
    @Test
    void phraseMatchesAllTwentyTwoUpstreamPitchesStepsAndDurations() {
        assertEquals(22, SonnetStaff.NOTES.size());
        assertEquals(List.of("D5", "D5", "E5", "C#5", "C#5", "C#5", "D5", "D5", "D5",
                "E5", "E5", "E5", "F5", "F5", "F5", "E5", "E5", "E5", "D5", "D5", "C#5", "D5"),
                SonnetStaff.NOTES.stream().map(note -> note.pitch).collect(Collectors.toList()));
        assertArrayEquals(new int[]{6, 6, 7, 5, 5, 5, 6, 6, 6, 7, 7, 7, 8, 8, 8, 7, 7, 7, 6, 6, 5, 6},
                SonnetStaff.NOTES.stream().mapToInt(note -> note.staffStep).toArray());
        assertArrayEquals(new double[]{1, 1.5, 0.5, 1, 1, 1, 1, 1.5, 0.5, 1, 1, 1, 1, 1.5, 0.5, 1, 1, 1, 1, 1.5, 0.5, 3},
                SonnetStaff.NOTES.stream().mapToDouble(note -> note.beats).toArray());
        assertEquals(24.0, SonnetStaff.NOTES.stream().mapToDouble(note -> note.beats).sum());
        assertEquals(24.0, SonnetStaff.TOTAL_BEATS);
        assertEquals(8000.0, SonnetStaff.LOOP_MS);
        assertEquals(4L, SonnetStaff.NOTES.stream().filter(note -> note.sharp).count());
        assertEquals(4L, SonnetStaff.NOTES.stream().filter(note -> note.beats <= 0.5).count());
        assertThrows(UnsupportedOperationException.class, () -> SonnetStaff.NOTES.clear());
    }

    @Test
    void noteWindowsFollowCumulativeBeatsAndKeepTheFinalThreeBeatHold() {
        double start = 0;
        for (int index = 0; index < SonnetStaff.NOTES.size(); index++) {
            SonnetStaff.Note note = SonnetStaff.NOTES.get(index);
            assertEquals(start, note.startBeat);
            double first = note.startBeat / 24.0 * 8000;
            double last = (note.startBeat + note.beats) / 24.0 * 8000;
            assertEquals(index, SonnetStaff.noteAt(first + 0.01));
            assertEquals(index, SonnetStaff.noteAt(last - 0.01));
            assertEquals((index + 1) % 22, SonnetStaff.noteAt(last + 0.01));
            start += note.beats;
        }
        assertEquals(21, SonnetStaff.noteAt(7000));
        assertEquals(21, SonnetStaff.noteAt(7999.99));
        assertEquals(0, SonnetStaff.noteAt(8000));
    }

    @Test
    void positiveModuloSupportsNegativeTimeRepeatedLoopsAndArbitrarySeeks() {
        for (double time : new double[]{0, 125, 1875.25, 3999.5, 6000, 7999.75}) {
            for (int loop = -100; loop <= 100; loop++) {
                double repeated = time + loop * 8000;
                assertEquals(time, SonnetStaff.cycleElapsedMs(repeated));
                assertEquals(time / 8000 * 24, SonnetStaff.beatAt(repeated));
                assertEquals(SonnetStaff.noteAt(time), SonnetStaff.noteAt(repeated));
            }
        }
        assertEquals(21, SonnetStaff.noteAt(-0.01));
        assertEquals(0, SonnetStaff.noteAt(-8000));
        assertEquals(-1, SonnetStaff.noteAt(Double.NaN));
        assertEquals(-1, SonnetStaff.noteAt(Double.POSITIVE_INFINITY));
        assertEquals(-1, SonnetStaff.noteAt(Double.NEGATIVE_INFINITY));
    }

    @Nested
    @RequiresSkijaNative
    class NativePixels {
        private static final int WIDTH = 1024, HEIGHT = 400;
        private static final int PRIMARY = 0xFFFFFF, ACCENT = 0x2080FF;

        @BeforeEach void loadNative() { Library.load(); }
        @AfterEach void closeOwnedResources() { SonnetStaff.close(); }

        @Test
        void staffHasFiveLinesSevenThreeBeatBarsAndTheClosingDoubleBar() {
            Raster image = render(500);
            // Stage width 1000 gives a 600-wide staff, spacing 20 and local y=-40,-20,0,20,40.
            for (int line = 0; line < 5; line++) {
                int alpha = image.alpha(216, 160 + line * 20);
                assertTrue(alpha >= 74 && alpha <= 80, "staff line opacity: " + alpha);
            }
            for (int gap = 0; gap < 4; gap++) assertEquals(0, image.alpha(216, 170 + gap * 20));
            for (int bar = 1; bar < 8; bar++) {
                int x = 236 + bar * 69;
                // A one-pixel stroke centered on an integer splits its coverage across two columns.
                int coverage = image.alpha(x - 1, 235) + image.alpha(x, 235);
                assertEquals(0.16 * 255, coverage, 2.0, "3/4 bar opacity " + bar);
            }
            assertTrue(image.alpha(222, 210) >= 125, "opening bar");
            assertTrue(image.alpha(802, 210) >= 125, "first closing bar");
            assertTrue(image.alpha(808, 210) >= 125, "thick closing bar");
            assertEquals(0, image.alpha(211, 200));
            assertEquals(0, image.alpha(813, 200));
        }

        @Test
        void cursorMovesLinearlyAcrossThePlayableWidthAndWrapsAtEightSeconds() {
            // Below all stems and staff/bar lines, only the cursor reaches this scanline.
            for (int step = 0; step < 4; step++) {
                Raster image = render(step * 2000);
                int x = 236 + step * 138;
                assertTrue(image.peakAlpha(x, 252, 1) >= 50, "cursor at beat " + step * 6);
                assertEquals(0, image.peakAlpha(x + 8, 252, 1));
            }
            assertArrayEquals(render(0).pixels, render(8000).pixels);
            assertArrayEquals(render(2000).pixels, render(-6000).pixels);
        }

        @Test
        void noteHeadsPulseWhileSharpsStemsAndHalfBeatFlagsRemainDrawn() {
            Raster active = render(0), inactive = render(4000);
            int firstX = 248, firstY = 180;
            assertTrue(blue(active.pixel(firstX, firstY)) > blue(inactive.pixel(firstX, firstY)) + 80,
                    "the active note must brighten above its idle opacity");
            // The fourth note is C#5: its accidental is left of its oval and its stem points up.
            assertTrue(inactive.peakAlpha(294, 186, 1) > 40, "sharp accidental");
            assertTrue(inactive.peakAlpha(325, 145, 1) > 40, "upward C# stem");
            // The third note is the half-beat E5, with a curved flag at the down-stem tail.
            assertTrue(inactive.peakAlpha(299, 227, 1) > 40, "half-beat flag curve");
            assertEquals(0, inactive.peakAlpha(335, 227, 1), "ordinary quarter note has no extra flag");
        }

        @Test
        void renderedLoopIsDeterministicAcrossForwardBackwardAndRepeatedDraws() {
            for (double time : new double[]{0, 125, 1875.25, 4000, 7999.5}) {
                Raster reference = render(time);
                render(6333);
                render(-1500);
                assertArrayEquals(reference.pixels, render(time).pixels);
                assertArrayEquals(reference.pixels, render(time + 80000).pixels);
                assertArrayEquals(reference.pixels, render(time - 80000).pixels);
            }
        }

        @Test
        void callerLayerAlphaAndCanvasTransformRemainInControl() {
            Raster full = render(4000);
            Raster half = raster(canvas -> {
                canvas.translate(512, 200);
                int save = canvas.saveLayerAlpha(Rect.makeLTRB(-512, -200, 512, 200), 128);
                try { SonnetStaff.draw(canvas, 1000, 80, PRIMARY, ACCENT, 4000); }
                finally { canvas.restoreToCount(save); }
            });
            assertEquals(full.alpha(216, 200) * 128 / 255.0, half.alpha(216, 200), 1.0);
            Raster marker = raster(canvas -> {
                canvas.translate(512, 200);
                canvas.rotate(17);
                canvas.scale(0.8f, 1.2f);
                int saveCount = canvas.getSaveCount();
                SonnetStaff.draw(canvas, 1000, 80, PRIMARY, ACCENT, 4000);
                assertEquals(saveCount, canvas.getSaveCount());
                try (Paint paint = new Paint().setColor(0xFF00FF00)) {
                    canvas.drawRect(Rect.makeXYWH(-10, 115, 20, 8), paint);
                }
            });
            Raster expectedMarker = raster(canvas -> {
                canvas.translate(512, 200);
                canvas.rotate(17);
                canvas.scale(0.8f, 1.2f);
                try (Paint paint = new Paint().setColor(0xFF00FF00)) {
                    canvas.drawRect(Rect.makeXYWH(-10, 115, 20, 8), paint);
                }
            });
            for (int index = 0; index < expectedMarker.pixels.length; index++) {
                if (expectedMarker.pixels[index] != 0) assertEquals(expectedMarker.pixels[index], marker.pixels[index]);
            }
        }

        @Test
        void closeIsIdempotentAndTheNextDrawRecreatesIdenticalArtwork() {
            Raster reference = render(1234);
            SonnetStaff.close();
            SonnetStaff.close();
            assertArrayEquals(reference.pixels, render(1234).pixels);
        }

        @Test
        void minimumWidthAndInvalidInputsDoNotCorruptTheCanvas() {
            Raster minimum = raster(canvas -> {
                canvas.translate(512, 200);
                SonnetStaff.draw(canvas, 100, 80, PRIMARY, ACCENT, 4000);
            });
            assertTrue(minimum.alpha(365, 200) > 0);
            assertEquals(0, minimum.alpha(350, 200));
            Raster invalid = raster(canvas -> {
                SonnetStaff.draw(canvas, 1000, 80, PRIMARY, ACCENT, Double.NaN);
                SonnetStaff.draw(canvas, 1000, 80, PRIMARY, ACCENT, Double.POSITIVE_INFINITY);
                SonnetStaff.draw(canvas, Float.NaN, 80, PRIMARY, ACCENT, 0);
                SonnetStaff.draw(canvas, 1000, 0, PRIMARY, ACCENT, 0);
                SonnetStaff.draw(null, 1000, 80, PRIMARY, ACCENT, 0);
            });
            assertFalse(java.util.Arrays.stream(invalid.pixels).anyMatch(pixel -> pixel != 0));
        }

        private Raster render(double elapsedMs) {
            return raster(canvas -> {
                canvas.translate(512, 200);
                SonnetStaff.draw(canvas, 1000, 80, PRIMARY, ACCENT, elapsedMs);
            });
        }

        private Raster raster(Consumer<Canvas> draw) {
            try (Bitmap bitmap = new Bitmap()) {
                assertTrue(bitmap.allocPixels(ImageInfo.makeN32Premul(WIDTH, HEIGHT)));
                try (Canvas canvas = new Canvas(bitmap)) {
                    canvas.clear(0);
                    draw.accept(canvas);
                }
                byte[] bytes = bitmap.readPixels();
                assertNotNull(bytes);
                int[] pixels = new int[WIDTH * HEIGHT];
                for (int index = 0, at = 0; index < pixels.length; index++, at += 4) {
                    pixels[index] = (bytes[at + 3] & 255) << 24 | (bytes[at + 2] & 255) << 16
                            | (bytes[at + 1] & 255) << 8 | bytes[at] & 255;
                }
                return new Raster(pixels);
            }
        }

        private record Raster(int[] pixels) {
            int pixel(int x, int y) { return pixels[y * WIDTH + x]; }
            int alpha(int x, int y) { return pixel(x, y) >>> 24; }
            int peakAlpha(int x, int y, int radius) {
                int peak = 0;
                for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
                    peak = Math.max(peak, alpha(x + dx, y + dy));
                }
                return peak;
            }
        }

        private int blue(int pixel) { return pixel & 255; }
    }
}
