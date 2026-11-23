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
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static com.muoniumplayer.core.lyrics3d.SonnetFixedGeometry.Design.*;
import static org.junit.jupiter.api.Assertions.*;

class SonnetDecorationsTest {
    @Test
    void frameSelectionMatchesUpstreamUtf16FnvGoldensIncludingUnsignedOverflow() {
        // Computed independently with upstream hashVisualizerSeed in JavaScript, not this Java port.
        assertFrame("微光", 0, 2, 487666833L, true, 1);
        assertFrame("夜色", 3, 5, 1332863050L, true, 0);
        assertFrame("echo", 4, 8, 636350587L, true, 0);
        assertFrame("A\uD83C\uDF19e\u0301", 7, 12, 3395475036L, false, 1);
        assertFrame("", 0, 0, 2569545142L, false, 1);
        assertFrame("句:子", -3, 123, 1853730665L, false, 3);
    }

    private static void assertFrame(String text, int start, int end, long hash, boolean applied, int variant) {
        assertEquals(hash, Integer.toUnsignedLong(SonnetDecorations.frameHash(text, start, end)));
        assertEquals(applied, SonnetDecorations.hasFrame(text, start, end));
        assertEquals(variant, SonnetDecorations.frameVariant(text, start, end));
    }

    @Test
    void linearGuideProgressPreservesSeparateDrawFadeAndParticleWindows() {
        assertEquals(0f, SonnetDecorations.guideDrawProgress(0f));
        assertEquals(0.5f, SonnetDecorations.guideDrawProgress(0.175f), 1e-6f);
        assertEquals(1f, SonnetDecorations.guideDrawProgress(0.35f));
        assertEquals(1f, SonnetDecorations.guideFade(0.4f));
        assertEquals(0.5f, SonnetDecorations.guideFade(0.55f), 1e-6f);
        assertEquals(0f, SonnetDecorations.guideFade(0.7f), 1e-6f);
        assertEquals(0f, SonnetDecorations.guideBurstProgress(0.3f));
        assertEquals(0.5f, SonnetDecorations.guideBurstProgress(0.65f), 1e-6f);
        assertTrue(SonnetDecorations.guideBurstProgress(0.85f) < 1f,
                "particles must outlive the guide strokes instead of disappearing with their .7 cutoff");
        assertEquals(1f, SonnetDecorations.guideBurstProgress(1f));
    }

    @Test
    void frameExpoAndStaggeredElasticAreAppliedAtTheirOwnStages() {
        float eased = SonnetDecorations.expoOut(0.02f);
        assertEquals(0.12944944f, eased, 1e-6f);
        assertEquals(0.3236236f, SonnetDecorations.frameSideProgress(eased, 0), 1e-6f);
        assertEquals(0f, SonnetDecorations.frameSideProgress(eased, 1));
        assertEquals(0f, SonnetDecorations.frameOrnamentProgress(eased, 0), 1e-6f);
        assertTrue(SonnetDecorations.elasticOut(0.15f) > 1f, "ornaments must retain elastic overshoot");
        assertTrue(SonnetDecorations.frameOrnamentProgress(0.45f, 0) > 0f);
        assertEquals(0f, SonnetDecorations.frameOrnamentProgress(0.45f, 1), 1e-6f);
        assertEquals(1f, SonnetDecorations.expoOut(1f));
    }

    @Test
    void seededPlansKeepAllParticleShapesAndBothEmphasisThreadsWithoutGlobalRandomness() {
        Set<Integer> kinds = new HashSet<>();
        boolean foundSpline = false;
        boolean foundWithoutSpline = false;
        for (int seed = 0; seed < 32; seed++) {
            SonnetDecorations.GuidePlan emphasis = SonnetDecorations.guidePlan(seed, true);
            assertEquals(6, emphasis.particles().length);
            assertNotNull(emphasis.sCurve());
            assertNotNull(emphasis.ribbon());
            assertSame(emphasis, SonnetDecorations.guidePlan(seed, true), "reuse plans across frames");
            assertEquals(3, SonnetDecorations.guidePlan(seed, false).particles().length);
            for (SonnetDecorations.Particle particle : emphasis.particles()) {
                kinds.add(particle.kind());
                assertTrue(particle.size() >= 3f && particle.size() < 6.5f);
                assertTrue(particle.speed() >= 15f && particle.speed() < 60f);
                assertTrue(particle.rotationSpeed() >= -4f && particle.rotationSpeed() < 4f);
            }
            foundSpline |= emphasis.spline() != null;
            foundWithoutSpline |= emphasis.spline() == null;
        }
        assertEquals(Set.of(0, 1, 2, 3), kinds);
        assertTrue(foundSpline && foundWithoutSpline, "the swept HUD spline remains a seeded probabilistic accent");
    }

    @Test
    void fixedGeometrySelectionKeepsAllSevenDesignsAndTheOriginalClassProbabilities() {
        int[] normalSeeds = {10, 1, 2, 6, 0, 4, 8};
        int[] chorusSeeds = {0, 1, 2, 3, 9, 19, 29};
        SonnetFixedGeometry.Design[] expected = {
                STRAIGHT_FRAME, ROTATED_FRAME, ORBIT_CROSSHAIR, SPLIT_ARCHES, ORB_HATCH, MUSIC_STEPS, BENT_LINES
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], SonnetFixedGeometry.design(normalSeeds[i], false));
            assertEquals(expected[i], SonnetFixedGeometry.design(chorusSeeds[i], true));
        }
        int chorusHollow = 0;
        int normalHollow = 0;
        for (int seed = 0; seed < 100; seed++) {
            if (SonnetFixedGeometry.design(seed, true).ordinal() < 4) chorusHollow++;
            if (SonnetFixedGeometry.design(seed, false).ordinal() < 4) normalHollow++;
        }
        assertEquals(90, chorusHollow);
        assertEquals(50, normalHollow);
        assertEquals(ORB_HATCH, SonnetFixedGeometry.design(-1, false));
        assertEquals(STRAIGHT_FRAME, SonnetFixedGeometry.design(-3, false));
    }

    @Nested
    @RequiresSkijaNative
    class NativePixels {
        @BeforeEach
        void loadNative() { assertTrue(SkijaNatives.ensureLoaded()); }

        @AfterEach
        void releaseOwnedResources() { SonnetDecorations.close(); }

        @Test
        void guideHasTaperedTailAndWhiteFilledHeadBeforeTextArrival() {
            Raster image = raster(1024, 640, c -> SonnetDecorations.drawGuide(c, 512f, 320f,
                    0x1AA6C8, 24f, -320f, -160f, 0.175f, true, 123));
            // At progress .175 the curve has drawn to t=.5, whose independently computed head is (-136,-50).
            int pixel = image.pixel(376, 270);
            assertTrue(red(pixel) > 220 && green(pixel) > 220 && blue(pixel) > 220,
                    "the guide needs a bright filled white core, not a star sprite or a uniform line");
            // These points lie on t=.1 and t=.4 of the main curve, far from threads and particles.
            int tail = image.peakAlpha(230, 187, 3);
            int headward = image.peakAlpha(342, 253, 3);
            assertTrue(tail > 0 && headward > tail * 4, "per-segment quadratic opacity must taper the tail");
            assertTrue(image.occupied(335, 245, 348, 262) > image.occupied(224, 179, 237, 196),
                    "the headward line must also be thicker than the tail");
        }

        @Test
        void threadsHaveAnActualHollowFollowingRing() {
            int seed = 0;
            for (; seed < 512; seed++) {
                SonnetDecorations.GuidePlan plan = SonnetDecorations.guidePlan(seed, false);
                if (plan.sCurve() != null && plan.ribbon() == null && plan.spline() == null) break;
            }
            assertTrue(seed < 512);
            SonnetDecorations.Curve curve = SonnetDecorations.guidePlan(seed, false).sCurve();
            int selectedSeed = seed;
            float progress = 0.24f;
            float t = (progress - curve.delay()) / 0.55f;
            float x = 512f + SonnetDecorations.bezier(curve.x0(), curve.x1(), curve.x2(), curve.x3(), t) * 90f;
            float y = 320f + SonnetDecorations.bezier(curve.y0(), curve.y1(), curve.y2(), curve.y3(), t) * 90f;
            Raster image = raster(1024, 640, c -> SonnetDecorations.drawGuide(c, 512f, 320f,
                    0x20B5FF, 90f, -900f, -700f, progress, false, selectedSeed));
            boolean foundHollowRim = false;
            for (int i = 0; i < 32; i++) {
                double angle = i * Math.PI / 16;
                int rim = image.peakAlpha(Math.round(x + (float) Math.cos(angle) * 12f),
                        Math.round(y + (float) Math.sin(angle) * 12f), 1);
                int gap = image.peakAlpha(Math.round(x + (float) Math.cos(angle) * 8f),
                        Math.round(y + (float) Math.sin(angle) * 8f), 0);
                foundHollowRim |= rim > 20 && gap == 0;
            }
            assertTrue(foundHollowRim, "a real ring must leave a transparent gap between its rim and filled head");
        }

        @Test
        void rectangularSplineSweepsAWhiteCoreWithARecedingTail() {
            int seed = 0;
            for (; seed < 4096; seed++) {
                SonnetDecorations.GuidePlan plan = SonnetDecorations.guidePlan(seed, false);
                SonnetDecorations.Spline s = plan.spline();
                if (plan.sCurve() == null && plan.ribbon() == null && s != null && s.x() > 0.2f && s.dx() > 1f) break;
            }
            assertTrue(seed < 4096);
            SonnetDecorations.Spline spline = SonnetDecorations.guidePlan(seed, false).spline();
            int selectedSeed = seed;
            float progress = spline.delay() + spline.duration() * 0.6f;
            // At local progress .6 the head is at .9 and the tail at .45 along the straight spline.
            Raster image = raster(1024, 640, c -> SonnetDecorations.drawGuide(c, 512f, 320f,
                    0x20B5FF, 100f, -900f, -700f, progress, false, selectedSeed));
            int middleX = Math.round(512f + (spline.x() + spline.dx() * 0.65f) * 100f);
            int middleY = Math.round(320f + (spline.y() + spline.dy() * 0.65f) * 100f);
            int tailX = Math.round(512f + (spline.x() + spline.dx() * 0.2f) * 100f);
            int tailY = Math.round(320f + (spline.y() + spline.dy() * 0.2f) * 100f);
            int aheadX = Math.round(512f + (spline.x() + spline.dx() * 1.05f) * 100f);
            int aheadY = Math.round(320f + (spline.y() + spline.dy() * 1.05f) * 100f);
            assertTrue(image.hasWhiteCore(middleX, middleY, 1), "the thick spline needs a filled narrow white core");
            assertFalse(image.hasWhiteCore(tailX, tailY, 1), "the receding tail must erase the beginning of the spline");
            assertFalse(image.hasWhiteCore(aheadX, aheadY, 1), "the trajectory must not be pre-drawn ahead of its head");
        }

        @Test
        void particlesOutliveStrokesDisperseAndThenVanish() {
            Raster late = guideAt(0.85f);
            Raster dying = guideAt(0.98f);
            assertTrue(late.occupied(190, 190, 322, 322) > 0);
            assertTrue(late.occupied(190, 190, 322, 322) > late.occupied(245, 245, 267, 267),
                    "particles must burst away from the word rather than stay fixed at its center");
            assertTrue(late.alphaMass() > dying.alphaMass(), "particle opacity and scale decay independently");
            for (int p : late.argb()) {
                if (alpha(p) > 0) assertTrue(red(p) <= green(p) * 0.3 + 2,
                        "only colored particles survive after .7, no white guide heads or spline cores");
            }
            assertEquals(0, guideAt(-0.01f).alphaMass());
            assertEquals(0, guideAt(1f).alphaMass());
            assertEquals(0, guideAt(1.1f).alphaMass());
        }

        @Test
        void rawFrameProgressIsEasedExactlyOnceAndLeavesCornersOpen() {
            Raster early = frameAt(0, 0.02f);
            // At t=.02 expo gives .129449; first side reaches only 32.36% of its length.
            assertTrue(early.peakAlpha(216, 228, 1) > 0);
            assertEquals(0, early.peakAlpha(266, 228, 1), "double easing would incorrectly reach this point");
            Raster complete = frameAt(0, 1f);
            assertTrue(complete.peakAlpha(256, 228, 1) > 0);
            assertEquals(0, complete.peakAlpha(191, 228, 1), "the top edge must stop short of the corner");
            assertEquals(0, complete.peakAlpha(188, 232, 1), "the left edge must also have an open corner gap");
            assertArrayEquals(complete.argb(), frameAt(0, 5f).argb(), "grown frames hold their geometry");
            assertEquals(0, frameAt(0, 0f).alphaMass());
        }

        @Test
        void fourFrameVariantsDrawTicksFlowersBracketsDiamondsAndDashedTriangles() {
            Raster ticks = frameAt(0, 1f);
            Raster flowers = frameAt(1, 1f);
            Raster brackets = frameAt(2, 1f);
            Raster triangles = frameAt(3, 1f);
            // Frame local half-size is (68,28); all points are outside the ordinary side stroke.
            assertTrue(ticks.peakAlpha(185, 222, 1) > 0, "crop-mark right angle");
            assertTrue(flowers.peakAlpha(184, 228, 1) > 0, "filled outer flower petal");
            assertTrue(flowers.peakAlpha(188, 228, 0) > 0, "flower center");
            assertTrue(brackets.peakAlpha(256, 225, 0) > 0, "top-side filled diamond");
            assertTrue(triangles.peakAlpha(185, 225, 1) > 0, "outward-facing filled triangle");
            assertEquals(0, triangles.peakAlpha(203, 228, 0), "dash gaps must remain transparent");
            assertTrue(triangles.peakAlpha(208, 228, 0) > 0, "next dash must remain visible");
            Set<Integer> masks = Set.of(Arrays.hashCode(ticks.argb()), Arrays.hashCode(flowers.argb()),
                    Arrays.hashCode(brackets.argb()), Arrays.hashCode(triangles.argb()));
            assertEquals(4, masks.size(), "four different geometries, not rotated copies of a star");
        }

        @Test
        void fixedArtworkRendersAllSevenDistinctDesignsWithTheirNativeGeometry() {
            int[] seeds = {10, 1, 2, 6, 0, 4, 8};
            Raster[] images = new Raster[seeds.length];
            Set<Integer> masks = new HashSet<>();
            for (int i = 0; i < seeds.length; i++) {
                images[i] = fixedAt(seeds[i], false, 1f);
                assertTrue(images[i].alphaMass() > 0);
                masks.add(Arrays.hashCode(images[i].argb()));
            }
            assertEquals(7, masks.size());
            assertTrue(images[0].peakAlpha(206, 256, 1) > 0); // straight frame left side
            assertEquals(0, images[0].peakAlpha(256, 256, 2)); // hollow interior
            assertTrue(images[1].peakAlpha(228, 228, 2) > 0); // 45-degree transformed frame side
            assertTrue(images[2].peakAlpha(256, 223, 1) > 0); // orbit's upper circular rim
            assertTrue(images[2].peakAlpha(256, 256, 1) > 0); // crosshair intersection
            assertTrue(images[3].peakAlpha(237, 232, 1) > 0); // native semicircular arch apex
            assertTrue(images[3].peakAlpha(256, 286, 1) > 0); // common arch baseline
            assertTrue(images[4].peakAlpha(226, 256, 0) > 0); // actual filled orb, no hollow substitute
            assertTrue(images[4].peakAlpha(276, 316, 0) > 0); // deliberately offset hatch beyond orb
            assertTrue(red(images[5].pixel(217, 264)) > blue(images[5].pixel(217, 264)));
            assertTrue(blue(images[5].pixel(250, 257)) > red(images[5].pixel(250, 257)),
                    "music steps alternate accent and secondary colors");
            assertTrue(images[6].peakAlpha(207, 226, 1) > 0); // top tip of the first bent line
        }

        @Test
        void fixedArtworkRespectsAlphaAndTheChorusDoubleFrame() {
            Raster full = fixedAt(0, false, 1f);
            Raster half = fixedAt(0, false, 0.5f);
            assertTrue(half.alphaMass() < full.alphaMass() * 0.65);
            assertTrue(half.alphaMass() > full.alphaMass() * 0.35);
            assertEquals(0, fixedAt(0, false, 0f).alphaMass());
            Raster chorus = fixedAt(0, true, 1f);
            // width=150 in chorus; two half-widths 75 and 90, not one fat outline.
            assertTrue(chorus.peakAlpha(331, 256, 1) > 0);
            assertTrue(chorus.peakAlpha(346, 256, 1) > 0);
            assertEquals(0, chorus.peakAlpha(338, 256, 1));
        }

        @Test
        void localTransformsAreRestoredAndCloseCanRecreateAllOwnedResources() {
            Consumer<Canvas> draw = c -> {
                c.translate(20f, 30f);
                c.rotate(7f);
                c.scale(0.85f, 0.85f);
                int saves = c.getSaveCount();
                SonnetDecorations.drawGuide(c, 240f, 240f, 0x23B7FF, 32f, -100f, -50f, 0.24f, true, 18);
                assertEquals(saves, c.getSaveCount());
                SonnetDecorations.drawFrame(c, 240f, 240f, 0.8f, 90f, 32f, 32f, 0xFFFFFF, 1, 0.6f);
                assertEquals(saves, c.getSaveCount());
                SonnetDecorations.drawFixedGeometry(c, 240f, 240f, -0.45f, 40f, 800f,
                        0xFFFFFF, 0xFF0000, 0x0000FF, 1, false, 0.6f);
                assertEquals(saves, c.getSaveCount());
                // This marker uses the caller's original transform, which no decoration may change.
                SonnetDecorations.drawFrame(c, 70f, 70f, 0f, 40f, 12f, 20f, 0xFFFFFF, 0, 1f);
            };
            Raster first = raster(512, 512, draw);
            SonnetDecorations.close();
            SonnetDecorations.close();
            Raster afterClose = raster(512, 512, draw);
            assertArrayEquals(first.argb(), afterClose.argb());
            Raster marker = raster(512, 512, c -> {
                c.translate(20f, 30f);
                c.rotate(7f);
                c.scale(0.85f, 0.85f);
                SonnetDecorations.drawFrame(c, 70f, 70f, 0f, 40f, 12f, 20f, 0xFFFFFF, 0, 1f);
            });
            for (int y = 35; y < 115; y++) for (int x = 35; x < 115; x++) {
                assertEquals(marker.pixel(x, y), first.pixel(x, y), "local transform leaked into caller marker");
            }
        }

        @Test
        void directSeekAndIntermediateFramesProduceTheSameGuidePixels() {
            Raster direct = guideAt(0.43f);
            Raster seek = raster(512, 512, c -> {
                for (float p : new float[] {0f, 0.1f, 0.6f, 0.9f, 0.25f}) {
                    SonnetDecorations.drawGuide(c, 256f, 256f, 0x1AA6C8, 32f, -140f, -70f, p, true, 123);
                }
                c.clear(0);
                SonnetDecorations.drawGuide(c, 256f, 256f, 0x1AA6C8, 32f, -140f, -70f, 0.43f, true, 123);
            });
            assertArrayEquals(direct.argb(), seek.argb(), "absolute time must not depend on update history");
        }
    }

    private static Raster guideAt(float progress) {
        return raster(512, 512, c -> SonnetDecorations.drawGuide(c, 256f, 256f,
                0x1AA6C8, 32f, -140f, -70f, progress, true, 123));
    }
    private static Raster frameAt(int variant, float progress) {
        return raster(512, 512, c -> SonnetDecorations.drawFrame(c, 256f, 256f, 0f,
                120f, 40f, 32f, 0xFFFFFF, variant, progress));
    }
    private static Raster fixedAt(int seed, boolean chorus, float alpha) {
        return raster(512, 512, c -> SonnetDecorations.drawFixedGeometry(c, 256f, 256f, 0f,
                40f, 800f, 0xFFFFFF, 0xFF0000, 0x0000FF, seed, chorus, alpha));
    }
    private static Raster raster(int width, int height, Consumer<Canvas> draw) {
        try (Bitmap bitmap = new Bitmap()) {
            assertTrue(bitmap.allocPixels(ImageInfo.makeN32Premul(width, height)));
            try (Canvas canvas = new Canvas(bitmap)) {
                canvas.clear(0);
                draw.accept(canvas);
            }
            byte[] bytes = bitmap.readPixels();
            assertNotNull(bytes);
            int[] pixels = new int[width * height];
            for (int i = 0; i < pixels.length; i++) {
                int at = i * 4;
                pixels[i] = (bytes[at + 3] & 255) << 24 | (bytes[at + 2] & 255) << 16
                        | (bytes[at + 1] & 255) << 8 | bytes[at] & 255;
            }
            return new Raster(width, height, pixels);
        }
    }
    private record Raster(int width, int height, int[] argb) {
        int pixel(int x, int y) { return x < 0 || x >= width || y < 0 || y >= height ? 0 : argb[y * width + x]; }
        int peakAlpha(int x, int y, int radius) {
            int max = 0;
            for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
                max = Math.max(max, alpha(pixel(x + dx, y + dy)));
            }
            return max;
        }
        int occupied(int x0, int y0, int x1, int y1) {
            int count = 0;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                if (alpha(pixel(x, y)) > 0) count++;
            }
            return count;
        }
        boolean hasWhiteCore(int x, int y, int radius) {
            for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
                int p = pixel(x + dx, y + dy);
                if (red(p) > 16 && red(p) * 3 > blue(p)) return true;
            }
            return false;
        }
        long alphaMass() {
            long sum = 0;
            for (int pixel : argb) sum += alpha(pixel);
            return sum;
        }
    }
    private static int alpha(int pixel) { return pixel >>> 24; }
    private static int red(int pixel) { return pixel >>> 16 & 255; }
    private static int green(int pixel) { return pixel >>> 8 & 255; }
    private static int blue(int pixel) { return pixel & 255; }
}
