package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class MusicCardTransitionRenderTest {
    @BeforeAll
    static void fonts() {
        MusicCardRenderTest.fonts();
    }

    @Test
    void splitCardUsesBoundedContentBlurAndReleasesClipAtEveryStage() throws Exception {
        Field canvasField = field(SkijaHost.class, "canvas");
        Field frameField = field(SkijaHost.class, "inFrame");
        Object oldCanvas = canvasField.get(null), oldFrame = frameField.get(null);
        Music music = new Music("渐进展开 · 音乐卡片", "渐进展开 · 音乐卡片", "", 82143L,
                Collections.emptyList(), Collections.emptyList(), null, 180_000L, 0L, 0L, Collections.emptyList());
        music.setSource(MusicPlatform.QQ);
        music.setSourceId("transition-probe");
        music.setArtistsName("同一设计空间 / 不挤压控件");
        CloudMusic.PlaybackSnapshot snapshot = new CloudMusic.PlaybackSnapshot(82143L, music.getId(),
                45_000L, 180_000L, true, null, music);
        try (Surface surface = Surface.makeRasterN32Premul(760, 432); Bitmap bitmap = new Bitmap()) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0);
            canvasField.set(null, canvas);
            frameField.set(null, true);
            int depth = SkijaGfx.saveDepth();
            double[] stages = {0.0, .38, .72, 1.0};
            for (int i = 0; i < stages.length; i++) {
                double center = i % 2 == 0 ? 190.0 : 570.0;
                double top = i < 2 ? 26.0 : 244.0;
                MusicCardMotion.Frame frame = MusicCardMotion.frame(stages[i], center, top,
                        170.0, 22.0, 46.0, 14.0, 1.0);
                MusicCardTransitionSurface.draw(frame, 1f, snapshot, MusicCardMotionBlur.Sample.STILL);
                assertEquals(depth, SkijaGfx.saveDepth());
                if (frame.anchorAlpha() > .001) {
                    canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH((float) frame.anchorX(),
                            (float) top, (float) frame.anchorWidth(), (float) frame.anchorHeight(), 7f),
                            SkijaGfx.fill(MusicCardSurface.color(0x11131A, frame.anchorAlpha())));
                }
            }
            canvas.drawRect(Rect.makeXYWH(2, 2, 3, 3), SkijaGfx.fill(0xFFFFFFFF));
            assertTrue(bitmap.allocN32Pixels(760, 432, false));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            assertEquals(0xFFFFFFFF, bitmap.getColor(2, 2), "card blur/clip cannot leak into the next draw");
            assertEquals(0, bitmap.getColor(5, 2));
            MusicCardMotion.Frame open = MusicCardMotion.frame(1.0, 570.0, 244.0,
                    170.0, 22.0, 46.0, 14.0, 1.0);
            int centerX = (int) (open.x() + open.width() * .5);
            int centerY = (int) (open.y() + open.height() * .5);
            int anchorY = (int) (open.anchorY() + open.anchorHeight() * .5);
            int gapY = (int) (open.y() - 6.0);
            assertEquals(0, bitmap.getColor(centerX, anchorY), "settled card must be the sole visible surface");
            assertEquals(244.0 + 14.0 + 12.0, open.y(), 1e-8);
            assertTrue((bitmap.getColor(centerX, gapY) >>> 24) < 12, "card must not leave a camera dot above it");
            assertTrue((bitmap.getColor(centerX, centerY) >>> 24) > 220, "full card surface must be visible");
            assertTrue((bitmap.getColor((int) open.x(), (int) open.y()) >>> 24) < 90,
                    "transparent rounded corner may contain only soft shadow");
            String output = System.getProperty("muonium.island.transitionPreviewDir");
            if (output != null && !output.isBlank()) {
                Path target = Path.of(output).resolve("music-card-transition.png");
                Files.createDirectories(target.getParent());
                try (Image image = surface.makeImageSnapshot(); Data png = EncoderPNG.encode(image)) {
                    assertNotNull(png);
                    Files.write(target, png.getBytes());
                }
            }
        } finally {
            canvasField.set(null, oldCanvas);
            frameField.set(null, oldFrame);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    @Test
    void directionalMotionAddsOnlyABoundedTrailingSilhouetteAndPreservesTheSharpSurface() throws Exception {
        Field canvasField = field(SkijaHost.class, "canvas"), frameField = field(SkijaHost.class, "inFrame");
        Object oldCanvas = canvasField.get(null), oldFrame = frameField.get(null);
        Music music = new Music("Velocity blur probe", "Velocity blur probe", "", 82144L,
                Collections.emptyList(), Collections.emptyList(), null, 180_000L, 60_000L, 0L, Collections.emptyList());
        music.setSource(MusicPlatform.QQ);
        music.setSourceId("motion-blur-probe");
        music.setArtistsName("Direction + speed");
        CloudMusic.PlaybackSnapshot snapshot = new CloudMusic.PlaybackSnapshot(82144L, music.getId(),
                60_000L, 180_000L, true, null, music);
        try (Surface baselineSurface = Surface.makeRasterN32Premul(760, 432);
             Surface surface = Surface.makeRasterN32Premul(760, 432);
             Bitmap baseline = new Bitmap(); Bitmap bitmap = new Bitmap()) {
            Canvas canvas = surface.getCanvas();
            baselineSurface.getCanvas().clear(0);
            canvas.clear(0);
            canvasField.set(null, baselineSurface.getCanvas());
            frameField.set(null, true);
            int depth = SkijaGfx.saveDepth();
            MusicCardMotion.Frame stationary = new MusicCardMotion.Frame(212, 128, 336, 148, 0,
                    212, 128, 336, 148, 28, 1, 212, 128, 1, 1, 0);
            MusicCardMotion.Frame start = new MusicCardMotion.Frame(192, 128, 336, 148, 0,
                    192, 128, 336, 148, 28, 1, 192, 128, 1, 1, 0);
            MusicCardMotion.Frame moved = new MusicCardMotion.Frame(212, 128, 336, 148, 0,
                    212, 128, 336, 148, 28, 1, 212, 128, 1, 1, 0);
            MusicCardMotionBlur sampler = new MusicCardMotionBlur();
            MusicCardMotionBlur.Sample still = MusicCardMotionBlur.Sample.STILL;
            MusicCardMotionBlur.Sample moving = sampler.advance(start, 5_000_000_000L);
            moving = sampler.advance(moved, 5_016_000_000L);
            assertTrue(moving.active());
            MusicCardTransitionSurface.draw(stationary, 1f, snapshot, still);
            canvasField.set(null, canvas);
            MusicCardTransitionSurface.draw(moved, 1f, snapshot, moving);
            assertEquals(depth, SkijaGfx.saveDepth());
            assertTrue(baseline.allocN32Pixels(760, 432, false));
            assertTrue(baselineSurface.readPixels(baseline, 0, 0));
            assertTrue(bitmap.allocN32Pixels(760, 432, false));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            assertTrue((bitmap.getColor(380, 202) >>> 24) > 210,
                    "motion response must keep the card body fully visible");
            int trailPixels = countAlpha(bitmap, 201, 185, 211, 219, 12);
            int basePixels = countAlpha(baseline, 201, 185, 211, 219, 12);
            assertTrue(trailPixels > basePixels, "directional silhouette must add pixels beyond the trailing card edge");
            int baselineCenter = baseline.getColor(380, 202);
            int motionCenter = bitmap.getColor(380, 202);
            assertTrue(Math.abs((baselineCenter >>> 24) - (motionCenter >>> 24)) <= 12,
                    "clear pass keeps body opacity stable");
            String output = System.getProperty("muonium.island.transitionPreviewDir");
            if (output != null && !output.isBlank()) {
                Path target = Path.of(output).resolve("music-card-motion-blur.png");
                Files.createDirectories(target.getParent());
                try (Image image = surface.makeImageSnapshot(); Data png = EncoderPNG.encode(image)) {
                    assertNotNull(png);
                    Files.write(target, png.getBytes());
                }
            }
        } finally {
            canvasField.set(null, oldCanvas);
            frameField.set(null, oldFrame);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    private static int countAlpha(Bitmap bitmap, int left, int top, int right, int bottom, int minimum) {
        int count = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            if (x >= 0 && y >= 0 && x < 760 && y < 432 && (bitmap.getColor(x, y) >>> 24) > minimum) count++;
        }
        return count;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
