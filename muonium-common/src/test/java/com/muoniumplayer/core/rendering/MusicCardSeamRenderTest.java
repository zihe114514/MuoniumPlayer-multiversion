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
import io.github.humbleui.types.RRect;
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
class MusicCardSeamRenderTest {
    @BeforeAll
    static void fonts() {
        MusicCardRenderTest.fonts();
    }

    @Test
    void closingSilhouetteTouchesTheIslandThenSettlesWithoutRetracting() throws Exception {
        Field canvasField = field(SkijaHost.class, "canvas"), frameField = field(SkijaHost.class, "inFrame");
        Object oldCanvas = canvasField.get(null), oldFrame = frameField.get(null);
        int oldColor = SkijaGfx.color();
        io.github.humbleui.skija.BlendMode oldBlend = SkijaGfx.blendMode();
        Music music = new Music("聚合与回弹", "聚合与回弹", "", 98231L, Collections.emptyList(),
                Collections.emptyList(), null, 180_000L, 0L, 0L, Collections.emptyList());
        music.setSource(MusicPlatform.QQ);
        music.setSourceId("closing-seam-probe");
        music.setArtistsName("收起后再次展开");
        CloudMusic.PlaybackSnapshot snapshot = new CloudMusic.PlaybackSnapshot(98231L, music.getId(),
                60_000L, 180_000L, true, null, music);
        MusicCardInteraction state = new MusicCardInteraction();
        for (int i = 0; i < 24; i++) state.update(true, true, true, false, 0, 40.0);
        state.collapse();
        int columns = 6, cells = 30, cellWidth = 360, cellHeight = 210;
        try (Surface sheet = Surface.makeRasterN32Premul(columns * cellWidth, 5 * cellHeight);
             Surface single = Surface.makeRasterN32Premul(cellWidth, cellHeight);
             Bitmap bitmap = new Bitmap()) {
            assertTrue(bitmap.allocN32Pixels(cellWidth, cellHeight, false));
            sheet.getCanvas().clear(0xFF191D28);
            frameField.set(null, true);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            SkijaGfx.setBlendMode(io.github.humbleui.skija.BlendMode.SRC_OVER);
            boolean joined = false;
            double previousGap = Double.POSITIVE_INFINITY;
            double previousWidth = 0.0, previousHeight = 0.0;
            for (int i = 0; i < cells; i++) {
                if (i > 0) state.update(true, true, true, false, 0, 1000.0 / 40.0);
                MusicCardMotion.Frame f = MusicCardMotion.frame(state.openness(), cellWidth * .5, 20,
                        176, 26, 46, 14, 1.0, state.contentReveal());
                single.getCanvas().clear(0);
                canvasField.set(null, single.getCanvas());
                int depth = SkijaGfx.saveDepth();
                MusicCardTransitionSurface.draw(f, 1f, snapshot, MusicCardMotionBlur.Sample.STILL);
                single.getCanvas().drawRRect(RRect.makeXYWH((float) f.anchorX(), (float) f.anchorY(),
                                (float) f.anchorWidth(), (float) f.anchorHeight(), (float) (f.anchorHeight() * .5)),
                        SkijaGfx.fill(MusicCardSurface.color(0x10131B, f.anchorAlpha())));
                assertEquals(depth, SkijaGfx.saveDepth());
                assertTrue(single.readPixels(bitmap, 0, 0));
                if (state.openness() > MusicCardMotion.JOIN_OPENNESS && state.openness() < .40) {
                    double gap = f.y() - f.anchorY() - f.anchorHeight();
                    assertTrue(gap < previousGap, "incoming card must approach instead of fading while detached");
                    previousGap = gap;
                    int centerY = (int) Math.floor(f.y() + f.height() * .5);
                    assertTrue((bitmap.getColor(cellWidth / 2, centerY) >>> 24) > 180,
                            "card remains raster-visible during aggregation");
                }
                if (state.openness() >= .42) {
                    int anchorCenterY = (int) Math.floor(f.anchorY() + f.anchorHeight() * .5);
                    assertEquals(0, bitmap.getColor(cellWidth / 2, anchorCenterY) >>> 24,
                            "detached card must not show a separate camera dot");
                }
                if (state.openness() > .11 && state.openness() <= MusicCardMotion.JOIN_OPENNESS) {
                    int centerY = (int) Math.floor(f.anchorY() + f.anchorHeight() * .5);
                    assertTrue((bitmap.getColor(cellWidth / 2, centerY) >>> 24) > 220,
                            "contact produces one connected visible island-center silhouette");
                    assertEquals(176.0, f.width());
                    assertEquals(26.0, f.height());
                    assertEquals(f.anchorY() + f.anchorHeight() * .5, f.y() + f.height() * .5, 1e-8);
                    joined = true;
                }
                if (joined) {
                    assertTrue(f.anchorWidth() >= previousWidth - 1e-8);
                    assertTrue(f.anchorHeight() >= previousHeight - 1e-8);
                    assertTrue(f.anchorWidth() <= 176.0 && f.anchorHeight() <= 26.0);
                }
                previousWidth = f.anchorWidth();
                previousHeight = f.anchorHeight();
                Canvas canvas = sheet.getCanvas();
                canvasField.set(null, canvas);
                int save = canvas.save();
                try {
                    canvas.translate((i % columns) * cellWidth, (i / columns) * cellHeight);
                    MusicCardTransitionSurface.draw(f, 1f, snapshot, MusicCardMotionBlur.Sample.STILL);
                    canvas.drawRRect(RRect.makeXYWH((float) f.anchorX(), (float) f.anchorY(),
                                    (float) f.anchorWidth(), (float) f.anchorHeight(), (float) (f.anchorHeight() * .5)),
                            SkijaGfx.fill(MusicCardSurface.color(0x10131B, f.anchorAlpha())));
                    com.muoniumplayer.core.management.FontManager.pf12.drawString(i * 25 + " ms", 12, 184, 0xFFCACDD8);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            assertTrue(joined);
            assertEquals(176.0, previousWidth);
            assertEquals(26.0, previousHeight);
            String output = System.getProperty("muonium.island.transitionPreviewDir");
            if (output != null && !output.isBlank()) {
                Path target = Path.of(output).resolve("music-card-closing-sequence.png");
                Files.createDirectories(target.getParent());
                try (Image image = sheet.makeImageSnapshot(); Data png = EncoderPNG.encode(image)) {
                    assertNotNull(png);
                    Files.write(target, png.getBytes());
                }
            }
        } finally {
            SkijaGfx.setColor(oldColor);
            SkijaGfx.setBlendMode(oldBlend);
            canvasField.set(null, oldCanvas);
            frameField.set(null, oldFrame);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
