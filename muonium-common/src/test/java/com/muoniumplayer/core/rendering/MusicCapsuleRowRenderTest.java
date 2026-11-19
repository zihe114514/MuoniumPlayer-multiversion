package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.settings.HudConfig;
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
class MusicCapsuleRowRenderTest {
    @BeforeAll
    static void fonts() { MusicCardRenderTest.fonts(); }

    @Test
    void compactRowCentersSmallArtworkAndTextWithoutDrawingPlaybackRail() throws Exception {
        Field canvasField = field(SkijaHost.class, "canvas"), frameField = field(SkijaHost.class, "inFrame");
        String[] fields = {"frameSnapshot", "rowText", "rowPreviousText", "rowFade", "rowLyric", "rowOffset", "rowPreviousOffset"};
        Object[] saved = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) saved[i] = field(MusicCapsuleCard.class, fields[i]).get(null);
        Object oldCanvas = canvasField.get(null), oldFrame = frameField.get(null);
        float baseWidth = HudConfig.dynamicIslandMaxWidth;
        float maximumWidth = HudConfig.islandMusicCapsuleMaxWidth;
        Music music = new Music("我要让你忍不住动心", "我要让你忍不住动心", "", 83621L,
                Collections.emptyList(), Collections.emptyList(), null, 138_000L, 0L, 0L, Collections.emptyList());
        music.setSource(MusicPlatform.QQ);
        music.setSourceId("compact-row-probe");
        try (Surface surface = Surface.makeRasterN32Premul(440, 126);
             Surface artwork = Surface.makeRasterN32Premul(40, 40); Bitmap bitmap = new Bitmap()) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0);
            canvasField.set(null, canvas);
            frameField.set(null, true);
            field(MusicCapsuleCard.class, "frameSnapshot").set(null,
                    new CloudMusic.PlaybackSnapshot(83621L, music.getId(), 46_000L, 138_000L, true, null, music));
            field(MusicCapsuleCard.class, "rowText").set(null, music.getName());
            field(MusicCapsuleCard.class, "rowPreviousText").set(null, "");
            field(MusicCapsuleCard.class, "rowFade").set(null, 1f);
            field(MusicCapsuleCard.class, "rowLyric").set(null, null);
            field(MusicCapsuleCard.class, "rowOffset").set(null, 0.0);
            artwork.getCanvas().clear(0xFFEFA2CB);
            String source = System.getProperty("muonium.island.rowPreviewCover");
            try (Image cover = source == null || source.isBlank() ? artwork.makeImageSnapshot()
                    : Image.makeFromEncoded(Files.readAllBytes(Path.of(source)))) {
                assertNotNull(cover);
                for (int i = 0; i < 3; i++) {
                    double width = DynamicIslandLayoutCalculator.musicRowWidth(160.0 + i * 90.0, 1.0, 440.0);
                    double x = 220.0 - width * .5, y = 8.0 + i * 38.0;
                    int save = canvas.save();
                    try {
                        canvas.clipRect(Rect.makeXYWH((float) x, (float) y, (float) width,
                                (float) MusicCapsuleCard.ROW_HEIGHT));
                        canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH((float) x, (float) y,
                                (float) width, (float) MusicCapsuleCard.ROW_HEIGHT, 13f), SkijaGfx.fill(0xED121622));
                        MusicCapsuleCard.INSTANCE.drawRow(x, y, width, MusicCapsuleCard.ROW_HEIGHT, 1f, cover);
                    } finally {
                        canvas.restoreToCount(save);
                    }
                }
            }
            canvas.drawRect(Rect.makeXYWH(2, 2, 2, 2), SkijaGfx.fill(0xFFFFFFFF));
            assertTrue(bitmap.allocN32Pixels(440, 126, false));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            assertEquals(0xFFFFFFFF, bitmap.getColor(2, 2), "row must release its clip");
            assertEquals(0, bitmap.getColor(220, 35), "no progress rail may spill below the slim capsule");
            assertEquals(0xED121622, bitmap.getColor(220, 32), "row bottom keeps only its plain surface");
            field(MusicCapsuleCard.class, "rowText").set(null, music.getName().repeat(3));
            HudConfig.dynamicIslandMaxWidth = 160f;
            HudConfig.islandMusicCapsuleMaxWidth = 160f;
            double narrow = MusicCapsuleCard.rowTargetWidth();
            HudConfig.islandMusicCapsuleMaxWidth = 400f;
            assertTrue(MusicCapsuleCard.rowTargetWidth() > narrow, "capsule maximum must control the actual lyric row");
            String output = System.getProperty("muonium.island.rowPreviewDir");
            if (output != null && !output.isBlank()) {
                Path target = Path.of(output).resolve("music-island-slim.png");
                Files.createDirectories(target.getParent());
                try (Image image = surface.makeImageSnapshot(); Data png = EncoderPNG.encode(image)) {
                    assertNotNull(png);
                    Files.write(target, png.getBytes());
                }
            }
        } finally {
            HudConfig.dynamicIslandMaxWidth = baseWidth;
            HudConfig.islandMusicCapsuleMaxWidth = maximumWidth;
            canvasField.set(null, oldCanvas);
            frameField.set(null, oldFrame);
            for (int i = 0; i < fields.length; i++) field(MusicCapsuleCard.class, fields[i]).set(null, saved[i]);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    @Test
    void productionLyricRowWaitsForTheClippedSyllableAndHoldsAtTheTail() throws Exception {
        Field canvasField = field(SkijaHost.class, "canvas"), frameField = field(SkijaHost.class, "inFrame");
        String[] fields = {"frameSnapshot", "rowText", "rowPreviousText", "rowFade", "rowLyric", "rowOffset",
                "rowPreviousOffset", "rowElapsedMillis"};
        Object[] saved = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) saved[i] = field(MusicCapsuleCard.class, fields[i]).get(null);
        Object oldCanvas = canvasField.get(null), oldFrame = frameField.get(null);
        float maximum = HudConfig.islandMusicCapsuleMaxWidth, base = HudConfig.dynamicIslandMaxWidth;
        float scale = HudConfig.dynamicIslandScale;
        long lyricOffset = field(CloudMusic.class, "lyricOffsetMillis").getLong(null);
        float[] levels = (float[]) field(MusicCapsuleCard.class, "BAR_LEVELS").get(null);
        float[] oldLevels = levels.clone();
        String text = "就是爱你爱着你不论白天黑夜都想和你在一起";
        LyricLine line = new LyricLine(10_000L, text);
        line.duration = text.length() * 600L;
        for (int i = 0; i < text.length(); i++) {
            line.words.add(new LyricLine.Word(text.substring(i, i + 1), 10_000L + i * 600L, 450L));
        }
        Music music = new Music(text, text, "", 62421L, Collections.emptyList(), Collections.emptyList(),
                null, 180_000L, 0L, 0L, Collections.emptyList());
        music.setSource(MusicPlatform.QQ);
        music.setSourceId("capsule-sung-row");
        try (Surface surface = Surface.makeRasterN32Premul(960, 560);
             Surface art = Surface.makeRasterN32Premul(40, 40); Bitmap bitmap = new Bitmap()) {
            canvasField.set(null, surface.getCanvas());
            frameField.set(null, true);
            field(CloudMusic.class, "lyricOffsetMillis").setLong(null, 0L);
            HudConfig.islandMusicCapsuleMaxWidth = 160f;
            HudConfig.dynamicIslandMaxWidth = 160f;
            HudConfig.dynamicIslandScale = 1f;
            field(MusicCapsuleCard.class, "rowText").set(null, text);
            field(MusicCapsuleCard.class, "rowPreviousText").set(null, "");
            field(MusicCapsuleCard.class, "rowFade").set(null, 1f);
            field(MusicCapsuleCard.class, "rowLyric").set(null, line);
            for (int i = 0; i < levels.length; i++) levels[i] = .18f + i * .14f;
            assertEquals(6, levels.length);
            double viewport = 160.0 - MusicCapsuleCard.ROW_TEXT_LEFT - MusicCapsuleCard.ROW_BARS_WIDTH
                    - 6.0 - MusicCapsuleCard.ROW_RIGHT_PAD;
            int visible = (int) (viewport / FontManager.pf20.getStringWidthD("爱"));
            long boundary = 10_000L + visible * 600L;
            long[] positions = {10_000L, boundary, boundary + 80L, boundary + 160L,
                    10_000L + line.duration + 160L, 100_000L};
            surface.getCanvas().clear(0xFF17242B);
            art.getCanvas().clear(0xFFCF97AC);
            try (Image cover = art.makeImageSnapshot()) {
                for (int i = 0; i < positions.length; i++) {
                    field(MusicCapsuleCard.class, "frameSnapshot").set(null,
                            new CloudMusic.PlaybackSnapshot(621L, music.getId(), positions[i], 180_000L, true, null, music));
                    int save = surface.getCanvas().save();
                    try {
                        surface.getCanvas().scale(3f, 3f);
                        double x = 80, y = 6 + i * 30;
                        MusicCapsuleCard.INSTANCE.drawSurface(x, y, 160, MusicCapsuleCard.ROW_HEIGHT, 13, 1f);
                        MusicCapsuleCard.INSTANCE.drawRow(x, y, 160, MusicCapsuleCard.ROW_HEIGHT, 1f, cover);
                    } finally {
                        surface.getCanvas().restoreToCount(save);
                    }
                    double offset = field(MusicCapsuleCard.class, "rowOffset").getDouble(null);
                    if (i <= 1) assertEquals(0.0, offset, 1e-8);
                    if (i == 2) assertTrue(offset > 0.0);
                    if (i >= 4) assertEquals(FontManager.pf20.getStringWidthD(text) - viewport, offset, 1e-8);
                }
            }
            assertTrue(bitmap.allocN32Pixels(960, 560, false));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            int lastY = 18 + 5 * 90, previousY = 18 + 4 * 90;
            for (int y = 0; y < 78; y++) for (int x = 240; x < 720; x++) {
                assertEquals(bitmap.getColor(x, previousY + y), bitmap.getColor(x, lastY + y),
                        "tail does not wrap even after a long wait");
            }
            double baselineOffset = field(MusicCapsuleCard.class, "rowOffset").getDouble(null);
            field(CloudMusic.class, "lyricOffsetMillis").setLong(null, 80L);
            field(MusicCapsuleCard.class, "frameSnapshot").set(null,
                    new CloudMusic.PlaybackSnapshot(621L, music.getId(), boundary, 180_000L, false, null, music));
            try (Image cover = art.makeImageSnapshot()) {
                MusicCapsuleCard.INSTANCE.drawRow(2000, 2000, 160, MusicCapsuleCard.ROW_HEIGHT, 1f, cover);
            }
            assertTrue(field(MusicCapsuleCard.class, "rowOffset").getDouble(null) > 0.0,
                    "draw uses the same lyric offset as the playback timeline");
            double pausedOffset = field(MusicCapsuleCard.class, "rowOffset").getDouble(null);
            try (Image cover = art.makeImageSnapshot()) {
                MusicCapsuleCard.INSTANCE.drawRow(2000, 2000, 160, MusicCapsuleCard.ROW_HEIGHT, 1f, cover);
            }
            assertEquals(pausedOffset, field(MusicCapsuleCard.class, "rowOffset").getDouble(null));
            assertTrue(baselineOffset > pausedOffset, "backwards seek discards the previous tail offset");
            String output = System.getProperty("muonium.island.rowPreviewDir");
            if (output != null && !output.isBlank()) {
                Path target = Path.of(output).resolve("music-capsule-singing.png");
                Files.createDirectories(target.getParent());
                try (Image image = surface.makeImageSnapshot(); Data png = EncoderPNG.encode(image)) {
                    assertNotNull(png);
                    Files.write(target, png.getBytes());
                }
            }
        } finally {
            HudConfig.islandMusicCapsuleMaxWidth = maximum;
            HudConfig.dynamicIslandMaxWidth = base;
            HudConfig.dynamicIslandScale = scale;
            field(CloudMusic.class, "lyricOffsetMillis").setLong(null, lyricOffset);
            System.arraycopy(oldLevels, 0, levels, 0, levels.length);
            canvasField.set(null, oldCanvas);
            frameField.set(null, oldFrame);
            for (int i = 0; i < fields.length; i++) field(MusicCapsuleCard.class, fields[i]).set(null, saved[i]);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
