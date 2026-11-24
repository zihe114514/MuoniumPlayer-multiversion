package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Picture;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@RequiresSkijaNative
class SonnetCanvasRendererTest {
    private static final int WIDTH = 960, HEIGHT = 540;

    @Test void productionPictureContainsRealTextArtworkAndBothSubswitchesRemoveTheirLayers() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        List<LyricLine> lines = fixture();
        String seed = seedFor(lines, SonnetTypography.Kind.TYPE_IMPACT);
        try (CFontRenderer bold = new CFontRenderer(font("/muonium/fonts/sfbold.otf"), 25, font("/muonium/fonts/pf_middleblack.ttf"));
             CFontRenderer regular = new CFontRenderer(latin, 25, cjk);
             SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(new SonnetTextFonts(bold, regular));
             SonnetFrameSurface raster = new SonnetFrameSurface()) {
            renderer.updateProgram(lines, 1, seed);
            WorldLyricsSettings settings = settings();
            double now = 1480;
            int[] full = render(renderer, raster, now, settings);
            settings.sonnetDecorations = false;
            int[] withoutDecor = render(renderer, raster, now, settings);
            settings.sonnetGlyphEffects = false;
            int[] core = render(renderer, raster, now, settings);
            assertTrue(visible(core) > 100, "Disabling optional effects must keep the animated lyric body");
            assertFalse(Arrays.equals(full, withoutDecor), "Guide/frame/particle switch must change production pixels");
            assertFalse(Arrays.equals(core, withoutDecor), "Typography switch must remove outline/SCREEN/echo pixels");
            settings.sonnetDecorations = settings.sonnetGlyphEffects = true;
            assertArrayEquals(full, render(renderer, raster, now, settings), "A direct seek must reproduce the same frame");
            assertFalse(Arrays.equals(full, render(renderer, raster, now + 160, settings)), "Effects must actually move");
            String output = System.getenv("MUONIUM_SONNET_RENDER_PROBE_DIR");
            if (output != null && !output.isEmpty()) {
                Path directory = Path.of(output);
                Files.createDirectories(directory);
                ImageIO.write(image(full), "png", directory.resolve("all-effects.png").toFile());
                ImageIO.write(image(withoutDecor), "png", directory.resolve("glyph-effects.png").toFile());
                ImageIO.write(image(core), "png", directory.resolve("core-only.png").toFile());
                BufferedImage contact = new BufferedImage(WIDTH * 3, HEIGHT * 2, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = contact.createGraphics();
                for (int i = 0; i < 6; i++) {
                    int[] pixels = render(renderer, raster, 900 + i * 500, settings);
                    graphics.drawImage(image(pixels), i % 3 * WIDTH, i / 3 * HEIGHT, null);
                }
                graphics.dispose();
                ImageIO.write(contact, "png", directory.resolve("timeline-contact.png").toFile());
                for (int i = 0; i < 60; i++) {
                    int[] pixels = render(renderer, raster, 750 + i * 60, settings);
                    ImageIO.write(image(pixels), "png", directory.resolve(String.format("frame-%03d.png", i)).toFile());
                }
            }
        } finally { SonnetDecorations.close(); }
    }

    @Test void stableGlyphBatchMatchesPerGlyphRasterWithAllTextEffects() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        List<LyricLine> lines = fixture();
        String seed = seedFor(lines, SonnetTypography.Kind.TYPE_IMPACT);
        try (CFontRenderer bold = new CFontRenderer(font("/muonium/fonts/sfbold.otf"), 25,
                font("/muonium/fonts/pf_middleblack.ttf"));
             CFontRenderer regular = new CFontRenderer(latin, 25, cjk);
             SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(new SonnetTextFonts(bold, regular));
             SonnetFrameSurface raster = new SonnetFrameSurface()) {
            renderer.updateProgram(lines, 1, seed);
            WorldLyricsSettings settings = settings();
            settings.glowEnabled = true;
            settings.glowStrength = 0.32f;
            settings.glowSpread = 0.8f;
            boolean compared = false;
            for (double now : new double[]{1900, 2300, 2700, 3100, 3500, 3900}) {
                renderer.stableGlyphBatching = true;
                int[] batched = render(renderer, raster, now, settings);
                int batchedCount = renderer.stableGlyphCountLastFrame;
                if (batchedCount < 2) continue;
                renderer.stableGlyphBatching = false;
                int[] legacy = render(renderer, raster, now, settings);
                double error = premultipliedMeanError(batched, legacy);
                assertTrue(error < 1.25, "Stable RSXform batch changed the rendered frame at " + now
                        + "ms; premultiplied mean error=" + error + ", batched glyphs=" + batchedCount);
                compared = true;
            }
            assertTrue(compared, "Fixture must reach a stable multi-glyph prefix for comparison");
        } finally { SonnetDecorations.close(); }
    }

    @Test void prewarmedStableBlobIsPromotedWithoutRebuildingAtTheRevealBoundary() throws Exception {
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        try (CFontRenderer source = new CFontRenderer(latin, 25, cjk);
             SonnetTextFonts fonts = new SonnetTextFonts(source, source)) {
            SonnetProgram program = SonnetProgram.compile(fixture(),
                    seedFor(fixture(), SonnetTypography.Kind.TYPE_IMPACT));
            SonnetTextScene scene = new SonnetTextScene(program.shots.get(0), fonts, 1280, 720);
            try {
                SonnetTextScene.Segment segment = scene.segments.stream()
                        .filter(candidate -> !candidate.staff && candidate.glyphs.size() >= 2)
                        .findFirst().orElseThrow();
                double ghostDuration = SonnetTextMotion.ghostDuration(scene.shot.endMs - scene.shot.startMs);
                double ready = segment.stableReadyMs(2, ghostDuration);
                scene.update(ready - 200);
                io.github.humbleui.skija.TextBlob prepared = segment.prewarmStableBlob(fonts, 2);
                assertNotNull(prepared, "The future stable prefix should be built during the lead-in");
                scene.update(ready + 1);
                assertSame(prepared, segment.stableBlob(fonts, 2),
                        "The reveal boundary must promote the prewarmed native blob instead of rebuilding it");
            } finally {
                scene.close();
            }
        }
    }

    @Test void everyShotRendersThroughTheSameCanvasPipelineWithoutMissingGraphemes() throws Exception {
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        List<LyricLine> lines = fixture();
        try (CFontRenderer bold = new CFontRenderer(latin, 25, cjk);
             CFontRenderer regular = new CFontRenderer(latin, 25, cjk);
             SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(new SonnetTextFonts(bold, regular));
             SonnetFrameSurface raster = new SonnetFrameSurface()) {
            BufferedImage contact = new BufferedImage(WIDTH * 4, HEIGHT * 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = contact.createGraphics();
            int i = 0;
            for (SonnetTypography.Kind kind : SonnetTypography.Kind.values()) {
                String seed = seedFor(lines, kind);
                renderer.updateProgram(lines, 1, seed);
                int[] pixels = render(renderer, raster, 3100, settings());
                assertTrue(visible(pixels) > 200, "Missing text for " + kind);
                graphics.drawImage(image(pixels), i % 4 * WIDTH, i / 4 * HEIGHT, null);
                graphics.setColor(java.awt.Color.WHITE);
                graphics.setFont(new Font("Dialog", Font.PLAIN, 18));
                graphics.drawString(kind.name(), i % 4 * WIDTH + 18, i / 4 * HEIGHT + 28);
                i++;
            }
            graphics.dispose();
            String output = System.getenv("MUONIUM_SONNET_RENDER_PROBE_DIR");
            if (output != null && !output.isEmpty()) {
                Files.createDirectories(Path.of(output));
                ImageIO.write(contact, "png", Path.of(output).resolve("seven-layouts.png").toFile());
            }
        } finally { SonnetDecorations.close(); }
    }

    @Test void allSevenLayoutsKeepSettledWordsStillWithoutPanningZoomRotationOrParallax() throws Exception {
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        LyricLine line = new LyricLine(1000, "微光 穿过 漫长 夜色 落在 你的 肩上");
        line.duration = 10000;
        for (String word : line.lyric.split(" ")) line.words.add(new LyricLine.Word(word, 1000, 300));
        List<LyricLine> lyrics = List.of(line);
        try (CFontRenderer bold = new CFontRenderer(latin, 25, cjk);
             CFontRenderer regular = new CFontRenderer(latin, 25, cjk);
             SonnetTextFonts fonts = new SonnetTextFonts(bold, regular);
             SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(new SonnetTextFonts(bold, regular));
             SonnetFrameSurface raster = new SonnetFrameSurface()) {
            WorldLyricsSettings options = settings();
            options.sonnetDecorations = false; // Artwork may breathe; settled text and outline copies may not move.
            BufferedImage contact = new BufferedImage(WIDTH * 4, HEIGHT * 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = contact.createGraphics();
            int index = 0;
            for (SonnetTypography.Kind kind : SonnetTypography.Kind.values()) {
                String seed = seedFor(lyrics, kind);
                SonnetProgram program = SonnetProgram.compile(lyrics, seed);
                SonnetTextScene scene = new SonnetTextScene(program.shots.get(0), fonts, 1280, 720);
                float centerX = scene.layoutCenterX, centerY = scene.layoutCenterY, scale = scene.layoutScale;
                scene.update(4000);
                for (SonnetTextScene.Segment segment : scene.segments) {
                    for (SonnetTextScene.Glyph glyph : segment.glyphs) {
                        assertEquals(glyph.baseX, glyph.x, 1e-5, kind + " must settle at its layout X");
                        assertEquals(glyph.baseY, glyph.y, 1e-5, kind + " must settle at its layout Y");
                        assertEquals(1, glyph.scale, 1e-6, kind + " must not keep a camera/depth scale");
                        assertEquals(segment.box.rotationRad, glyph.rotation, 1e-6);
                    }
                    if (segment.box.role == SonnetTypography.Role.DECORATION) continue;
                    assertTrue((segment.x - segment.box.width / 2 - centerX) * scale >= -1280 * 0.43f - 0.01f);
                    assertTrue((segment.x + segment.box.width / 2 - centerX) * scale <= 1280 * 0.43f + 0.01f);
                    assertTrue((segment.y - segment.box.height / 2 - centerY) * scale >= -720 * 0.4f - 0.01f);
                    assertTrue((segment.y + segment.box.height / 2 - centerY) * scale <= 720 * 0.4f + 0.01f);
                }
                scene.update(8000);
                assertEquals(centerX, scene.layoutCenterX); assertEquals(centerY, scene.layoutCenterY);
                assertEquals(scale, scene.layoutScale, "Placement is determined once from the complete layout");
                renderer.updateProgram(lyrics, 1, seed);
                int[] settled = render(renderer, raster, 4000, options);
                assertTrue(visible(settled) > 100, kind + " must keep the whole core layout visible");
                assertArrayEquals(settled, render(renderer, raster, 8000, options),
                        kind + " pixels must not drift as playback advances after entry");
                assertFalse(Arrays.equals(settled, render(renderer, raster, 1150, options)),
                        "Removing the camera must retain the actual text entrance animation");
                graphics.drawImage(image(settled), index % 4 * WIDTH, index / 4 * HEIGHT, null);
                graphics.setColor(java.awt.Color.WHITE); graphics.setFont(new Font("Dialog", Font.PLAIN, 18));
                graphics.drawString(kind.name(), index % 4 * WIDTH + 18, index / 4 * HEIGHT + 28);
                index++;
            }
            graphics.dispose();
            String output = System.getenv("MUONIUM_SONNET_RENDER_PROBE_DIR");
            if (output != null && !output.isEmpty()) {
                Files.createDirectories(Path.of(output));
                ImageIO.write(contact, "png", Path.of(output).resolve("static-seven-layouts.png").toFile());
            }
        } finally { SonnetDecorations.close(); }
    }

    @Test void earlierWordsDoNotFollowLaterWordRevealsInTheSameLayout() throws Exception {
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        try (CFontRenderer source = new CFontRenderer(latin, 25, cjk);
             SonnetTextFonts fonts = new SonnetTextFonts(source, source)) {
            for (SonnetTypography.Kind kind : SonnetTypography.Kind.values()) {
                SonnetProgram program = SonnetProgram.compile(fixture(), seedFor(fixture(), kind));
                SonnetTextScene scene = new SonnetTextScene(program.shots.get(0), fonts, 1280, 720);
                SonnetTextScene.Glyph first = scene.segments.stream()
                        .filter(segment -> segment.box.role != SonnetTypography.Role.DECORATION)
                        .flatMap(segment -> segment.glyphs.stream())
                        .min(java.util.Comparator.comparingDouble(glyph -> glyph.startMs)).orElseThrow();
                scene.update(first.settleMs + 1);
                float x = first.x, y = first.y, scale = first.scale, rotation = first.rotation;
                scene.update(first.settleMs + 450);
                assertEquals(x, first.x); assertEquals(y, first.y);
                assertEquals(scale, first.scale); assertEquals(rotation, first.rotation);
            }
        }
    }

    @Test void gpuRejectionReplaysIdenticalPremultipliedCommandsWithStraightAbgrPixels() {
        try (SonnetFrameSurface surface = new SonnetFrameSurface()) {
            io.github.humbleui.skija.Canvas canvas = surface.begin(32, 32);
            try (io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint().setColor(0x80FF4080)) {
                canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(4, 5, 15, 10), paint);
            }
            try (Picture picture = surface.finish()) {
                int[] reference = surface.rasterize(picture, 32, 32).clone();
                final int[][] actual = new int[1][];
                WorldLyricsRenderer.Host host = (WorldLyricsRenderer.Host) java.lang.reflect.Proxy.newProxyInstance(
                        WorldLyricsRenderer.Host.class.getClassLoader(), new Class<?>[]{WorldLyricsRenderer.Host.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("supportsGpuTrailCanvas")) return true;
                            if (method.getName().equals("drawGpuTrailCanvas")) return false;
                            if (method.getName().equals("drawTrailCanvas")) { actual[0] = ((int[]) args[0]).clone(); return true; }
                            return null;
                        });
                assertTrue(surface.present(host, picture, 32, 32, 20, 20, 1, 0));
                assertArrayEquals(reference, actual[0]);
                assertEquals(0x808040FF, actual[0][6 * 32 + 5], "CPU presentation must use straight ABGR");
            }
        }
    }

    @Test void hostUsesTheShotAnchorAndIgnoresUndrawnRowProtectionAcrossGroupedLines() throws Exception {
        List<LyricLine> previous = new java.util.ArrayList<>(com.muoniumplayer.core.ncm.music.CloudMusic.lyrics);
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        try (CFontRenderer bold = new CFontRenderer(latin, 25, cjk);
             CFontRenderer regular = new CFontRenderer(latin, 25, cjk);
             SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(new SonnetTextFonts(bold, regular))) {
            com.muoniumplayer.core.ncm.music.CloudMusic.lyrics.clear();
            com.muoniumplayer.core.ncm.music.CloudMusic.lyrics.addAll(fixture());
            com.muoniumplayer.core.ncm.music.CloudMusic.lyricsRevision++;
            final float[] values = new float[2];
            final int[] pushes = new int[1], pops = new int[1];
            WorldLyricsRenderer.Host host = (WorldLyricsRenderer.Host) java.lang.reflect.Proxy.newProxyInstance(
                    WorldLyricsRenderer.Host.class.getClassLoader(), new Class<?>[]{WorldLyricsRenderer.Host.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "supportsGpuTrailCanvas": return true;
                            case "pushBillboard": values[0] = (float) args[5]; pushes[0]++; return null;
                            case "popBillboard": pops[0]++; return null;
                            case "drawGpuTrailCanvas": values[1] = (float) args[7]; return true;
                            default: return null;
                        }
                    });
            WorldLyricsState.SpawnedLine line = new WorldLyricsState.SpawnedLine(List.of("微光"),
                    1000, 1000, 0, 0, 10, 0, 0, new long[]{0, 200});
            WorldLyricsState.Row first = new WorldLyricsState.Row(line, 0, 0, 10, 0, 0, 0,
                    0, 0, 42, 0.4f, 0, true, 0.4f);
            WorldLyricsSettings settings = settings(); settings.keepDistance = false;
            com.muoniumplayer.core.ncm.music.CloudMusic.PlaybackSnapshot snap =
                    new com.muoniumplayer.core.ncm.music.CloudMusic.PlaybackSnapshot(1, 1, 1500, 5000, true, null, null);
            assertTrue(renderer.draw(new WorldLyricsState.Frame(List.of(first), true), settings, host, snap));
            float expectedScale = values[0];
            assertEquals(1, values[1], 1e-6);
            WorldLyricsState.Row next = new WorldLyricsState.Row(line, 8, 0, 20, 0, 0, 0,
                    0, 0, 73, 0.15f, 0, true, 0.5f);
            assertTrue(renderer.draw(new WorldLyricsState.Frame(List.of(next), true), settings, host, snap));
            assertEquals(expectedScale, values[0], 1e-6, "Another row must not resize a fixed shot plane");
            assertEquals(1f, values[1], 1e-6,
                    "Single-row fades cannot dim or truncate the independently animated shot");
            assertEquals(pushes[0], pops[0], "The production host stack must be balanced");
        } finally {
            com.muoniumplayer.core.ncm.music.CloudMusic.lyrics.clear();
            com.muoniumplayer.core.ncm.music.CloudMusic.lyrics.addAll(previous);
            com.muoniumplayer.core.ncm.music.CloudMusic.lyricsRevision++;
            SonnetDecorations.close();
        }
    }

    @Test void titleScreenHeartbeatReleasesIdleNativeFontsAndClearsTheRenderer() throws Exception {
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        java.lang.reflect.Field singleton = SonnetCanvasRenderer.class.getDeclaredField("instance");
        java.lang.reflect.Field last = SonnetCanvasRenderer.class.getDeclaredField("lastRenderNanos");
        java.lang.reflect.Field fontField = SonnetTextFonts.class.getDeclaredField("bold");
        singleton.setAccessible(true); last.setAccessible(true); fontField.setAccessible(true);
        SonnetCanvasRenderer.release();
        try (CFontRenderer sourceBold = new CFontRenderer(latin, 25, cjk);
             CFontRenderer sourceRegular = new CFontRenderer(latin, 25, cjk)) {
            SonnetTextFonts fonts = new SonnetTextFonts(sourceBold, sourceRegular);
            CFontRenderer ownedFont = (CFontRenderer) fontField.get(fonts);
            SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(fonts);
            singleton.set(null, renderer);
            last.setLong(renderer, 10_000_000_000L);
            SonnetCanvasRenderer.releaseIfIdle(13_000_000_000L);
            assertSame(renderer, singleton.get(null), "Keep active/briefly idle word-plane resources");
            SonnetCanvasRenderer.releaseIfIdle(13_000_000_001L);
            assertNull(singleton.get(null));
            assertEquals(-1, ownedFont.fontHeight, "The renderer must close its owned native font");
            assertTrue(sourceBold.fontHeight > 0, "The renderer must not close FontManager-owned source fonts");
            SonnetCanvasRenderer.release();
        } finally { SonnetCanvasRenderer.release(); }
    }

    private static int[] render(SonnetCanvasRenderer renderer, SonnetFrameSurface raster,
                                double now, WorldLyricsSettings settings) {
        try (Picture picture = renderer.record(0, now, settings, WIDTH, HEIGHT)) {
            int[] pixels = raster.rasterize(picture, WIDTH, HEIGHT);
            assertNotNull(pixels);
            return pixels.clone();
        }
    }

    static WorldLyricsSettings settings() {
        WorldLyricsSettings settings = WorldLyricsSettings.snapshot();
        settings.sonnet = true;
        settings.sonnetDecorations = settings.sonnetGlyphEffects = true;
        settings.glowEnabled = false;
        settings.colorCustom = false;
        settings.coverColorMode = 0;
        settings.coverColorGradient = false;
        settings.keywordColors = "";
        settings.scale = 1;
        settings.glowColorRgb = 0x79CBE3;
        return settings;
    }

    static String seedFor(List<LyricLine> lines, SonnetTypography.Kind wanted) {
        for (int i = 0; i < 1000; i++) {
            String seed = "probe-" + i;
            if (SonnetProgram.compile(lines, seed).shots.get(0).kind == wanted) return seed;
        }
        throw new AssertionError("No deterministic fixture for " + wanted);
    }

    static List<LyricLine> fixture() {
        String[] words = {"微光", "穿过", "漫长", "夜色", "落在", "你的", "肩上"};
        LyricLine line = new LyricLine(1000, String.join(" ", words));
        line.duration = 3200;
        for (int i = 0; i < words.length; i++) line.words.add(new LyricLine.Word(words[i], 1000 + i * 400, 360));
        return List.of(line);
    }

    static Font font(String path) throws Exception {
        try (InputStream stream = SonnetCanvasRendererTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            byte[] bytes = stream.readAllBytes();
            Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
            SkijaText.register(font, bytes);
            return font;
        }
    }

    private static int visible(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel >>> 24) > 10) count++;
        return count;
    }

    private static double premultipliedMeanError(int[] first, int[] second) {
        double error = 0;
        for (int i = 0; i < first.length; i++) {
            int alphaA = first[i] >>> 24, alphaB = second[i] >>> 24;
            error += Math.abs(alphaA - alphaB);
            for (int shift : new int[]{0, 8, 16}) {
                int colorA = first[i] >>> shift & 255, colorB = second[i] >>> shift & 255;
                error += Math.abs(colorA * alphaA - colorB * alphaB) / 255.0;
            }
        }
        return error / (first.length * 4.0);
    }

    private static BufferedImage image(int[] abgr) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[abgr.length];
        for (int i = 0; i < argb.length; i++) {
            int color = abgr[i];
            int a = color >>> 24, r = color & 255, g = color >> 8 & 255, b = color >> 16 & 255;
            r = (r * a + 16 * (255 - a)) / 255;
            g = (g * a + 23 * (255 - a)) / 255;
            b = (b * a + 32 * (255 - a)) / 255;
            argb[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        image.setRGB(0, 0, WIDTH, HEIGHT, argb, 0, WIDTH);
        return image;
    }
}
