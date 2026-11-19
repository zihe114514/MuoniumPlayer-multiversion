package com.muoniumplayer.core.rendering;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.CoverPalette;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 显式指定本地素材清单时才导出，不把本机缓存路径或试听数据写进生产代码。 */
@EnabledIfSystemProperty(named = "muonium.island.cachedPreviewManifest", matches = ".+")
class MusicCardCachedPreviewTest {
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void exportActualCardWithMatchingCachedMusicCoverAndLyrics() throws Exception {
        MusicCardRenderTest.fonts();
        Path manifestFile = Path.of(System.getProperty("muonium.island.cachedPreviewManifest"));
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestFile, StandardCharsets.UTF_8)).getAsJsonObject();
        Path output = Path.of(manifest.get("output").getAsString());
        Files.createDirectories(output);
        Field canvasField = field(SkijaHost.class, "canvas");
        Field frameField = field(SkijaHost.class, "inFrame");
        Field snapshotField = field(MusicCapsuleCard.class, "frameSnapshot");
        Object oldCanvas = canvasField.get(null), oldFrame = frameField.get(null), oldSnapshot = snapshotField.get(null);
        MusicCapsuleCard card = MusicCapsuleCard.INSTANCE;
        java.lang.reflect.Method deserialize = LocalPlaylistStore.class.getDeclaredMethod("deserializeTrack", JsonElement.class);
        deserialize.setAccessible(true);
        List<Image> contactImages = new ArrayList<>();
        try {
            for (JsonElement element : manifest.getAsJsonArray("tracks")) {
                JsonObject entry = element.getAsJsonObject();
                JsonObject resume = JsonParser.parseString(Files.readString(Path.of(entry.get("metadata").getAsString()),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                Music music = null;
                for (JsonElement track : resume.getAsJsonArray("queue")) {
                    Music candidate = (Music) deserialize.invoke(null, track);
                    if (candidate != null && candidate.getStableKey().equals(entry.get("key").getAsString())) {
                        music = candidate;
                        break;
                    }
                }
                assertNotNull(music, "封面和元数据必须按稳定键匹配");
                String headline = music.getName();
                long position = entry.has("position") ? entry.get("position").getAsLong() : 60_000L;
                if (entry.has("lyrics")) {
                    JsonObject lyrics = JsonParser.parseString(Files.readString(Path.of(entry.get("lyrics").getAsString()),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    assertEquals(music.getStableKey(), lyrics.get("key").getAsString());
                    JsonObject current = null;
                    for (JsonElement lineElement : lyrics.getAsJsonArray("lines")) {
                        JsonObject line = lineElement.getAsJsonObject();
                        if (line.get("t").getAsLong() <= position && !line.get("x").getAsString().isBlank()) current = line;
                    }
                    if (current != null) headline = current.get("x").getAsString();
                }
                Path coverPath = Path.of(entry.get("cover").getAsString());
                BufferedImage coverPixels = ImageIO.read(coverPath.toFile());
                assertNotNull(coverPixels);
                music.setCoverPalette(CoverPalette.extract(coverPixels, 6));
                int accent = MusicCardColors.accent(music.getCoverPalette(), 0xACABDF);
                int secondary = MusicCardColors.secondary(music.getCoverPalette(), accent);
                snapshotField.set(null, new CloudMusic.PlaybackSnapshot(9182L, music.getId(), position,
                        music.getDuration(), true, null, music));
                field(MusicCapsuleCard.class, "headline").set(card, headline);
                field(MusicCapsuleCard.class, "headlineFade").set(card, 1f);
                field(MusicCapsuleCard.class, "accent").set(card, accent);
                field(MusicCapsuleCard.class, "secondaryAccent").set(card, secondary);
                field(MusicCapsuleCard.class, "subtitleStartedAt").set(card, System.nanoTime());
                card.clearPointer();
                String name = entry.get("name").getAsString();
                try (Image cover = Image.makeFromEncoded(Files.readAllBytes(coverPath));
                     Surface surface = Surface.makeRasterN32Premul(1200, 620)) {
                    Canvas canvas = surface.getCanvas();
                    canvasField.set(null, canvas);
                    frameField.set(null, true);
                    canvas.clear(0xFF343A40);
                    drawUnderlay(canvas, cover, 1200, 620);
                    double x = 62, y = 68, width = 1075.2, height = 473.6, radius = MusicCardLayout.RADIUS * 3.2;
                    canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH((float) (x + 1), (float) (y + 9),
                            (float) width, (float) height, (float) radius), SkijaGfx.fillBlurred(0x66000000, 18f));
                    try (Image underlay = surface.makeImageSnapshot();
                         io.github.humbleui.skija.Path shape = MusicCardShape.outline(x, y, width, height, radius)) {
                        int save = canvas.save();
                        try {
                            canvas.clipPath(shape, true);
                            try (ImageFilter blur = ImageFilter.makeBlur(30f, 30f, FilterTileMode.CLAMP);
                                 Paint paint = new Paint().setImageFilter(blur)) {
                                canvas.drawImage(underlay, 0f, 0f, paint);
                            }
                        } finally {
                            canvas.restoreToCount(save);
                        }
                    }
                    MusicCardSurface.paint(canvas, x, y, width, height, radius, accent, secondary, 1f, true);
                    card.drawCard(x, y, width, height, 1f, cover);
                    save(surface, output.resolve(name + ".png"));
                    try (Image image = surface.makeImageSnapshot()) {
                        contactImages.add(image.makeScaled(io.github.humbleui.skija.ImageInfo.makeN32Premul(900, 465), SamplingMode.LINEAR));
                    }
                    try (Surface transparent = Surface.makeRasterN32Premul(1120, 520)) {
                        canvasField.set(null, transparent.getCanvas());
                        transparent.getCanvas().clear(0);
                        MusicCardSurface.paint(transparent.getCanvas(), 22, 22, width, height, radius,
                                accent, secondary, 1f, true);
                        card.drawCard(22, 22, width, height, 1f, cover);
                        save(transparent, output.resolve(name + "-transparent.png"));
                    }
                }
                System.out.println("Cached island preview: " + music.getName() + " / " + music.getArtistsName()
                        + " / " + music.getSource() + " / " + MusicCapsuleCard.formatTime(position));
            }
            try (Surface sheet = Surface.makeRasterN32Premul(900, 465 * contactImages.size())) {
                sheet.getCanvas().clear(0xFF101215);
                int y = 0;
                for (Image image : contactImages) {
                    sheet.getCanvas().drawImage(image, 0, y);
                    y += 465;
                }
                save(sheet, output.resolve("cached-music-cards.png"));
            }
        } finally {
            for (Image image : contactImages) if (image != null) image.close();
            canvasField.set(null, oldCanvas);
            frameField.set(null, oldFrame);
            snapshotField.set(null, oldSnapshot);
        }
    }

    private static void drawUnderlay(Canvas canvas, Image cover, int width, int height) {
        try (Paint imagePaint = new Paint().setAlphaf(.75f);
             ImageFilter blur = ImageFilter.makeBlur(38f, 38f, FilterTileMode.CLAMP)) {
            imagePaint.setImageFilter(blur);
            canvas.drawImageRect(cover, Rect.makeXYWH(0, 0, cover.getWidth(), cover.getHeight()),
                    Rect.makeXYWH(-80, -80, width + 160, height + 160), SamplingMode.LINEAR, imagePaint, false);
        }
        try (Paint paint = new Paint().setColor(0x50A2ACB6)) {
            canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(18, 26, 1164, 560, 30), paint);
        }
        int[] colors = {0xFF507AB3, 0xFFD86D7D, 0xFFCFB86B, 0xFF709D9A};
        for (int i = 0; i < colors.length; i++) {
            try (Paint paint = new Paint().setColor(colors[i])) {
                canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(100 + i * 255, 426, 154, 142, 28), paint);
            }
        }
    }

    private static void save(Surface surface, Path target) throws Exception {
        try (Image image = surface.makeImageSnapshot(); Data png = EncoderPNG.encode(image)) {
            assertNotNull(png);
            Files.write(target, png.getBytes());
        }
    }
}
