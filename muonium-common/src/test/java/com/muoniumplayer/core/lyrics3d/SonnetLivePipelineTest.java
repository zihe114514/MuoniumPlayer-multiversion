package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Picture;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

import static com.muoniumplayer.core.lyrics3d.SonnetCanvasRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Parsed lyric -> real state -> live renderer dispatch -> host surface. No synthetic record-only path. */
@RequiresSkijaNative
class SonnetLivePipelineTest {
    static final WorldLyricsState.CameraView CAMERA = camera(1920, 1080, 70);

    @Test void firstGuidesAndSongEndingReachTheHostEvenWhenTheLegacyFrameHasNoRows() throws Exception {
        try (Harness run = new Harness("[00:01.000]g d light\n[00:03.500]穿过夜色\n[00:06.000]最后一行")) {
            run.frame(800, true);
            assertEquals(1, run.submissions);
            assertTrue(visible(run.pixels) > 0, "A leading guide must render before the first row spawns");
            int[] guide = run.pixels.clone();
            run.frame(1120, true);
            assertFalse(Arrays.equals(guide, run.pixels), "Live entry must advance, not reuse a still image");
            run.frame(1600, true);
            int[] full = run.pixels.clone();
            run.settings.sonnetDecorations = false;
            run.frame(1600, true);
            assertFalse(Arrays.equals(full, run.pixels), "Live dispatch must actually include attached geometry/particles");
            run.settings.sonnetDecorations = true;
            run.frame(1600, true);
            assertArrayEquals(full, run.pixels, "Toggling or seeking must not accumulate stale paint");
            int[] paused = run.pixels.clone();
            run.frame(1600, false);
            run.frame(1600, false);
            assertArrayEquals(paused, run.pixels, "Pause freezes the shot instead of retiring its owning word plane");
            double end = SonnetProgram.compile(CloudMusic.lyrics, "sonnet").finalRenderEndMs();
            run.frame((long) end + 1251, true);
            assertEquals(0, run.submissions, "The final lyric must clear after its blur/fade tail");
            assertEquals(run.pushes, run.pops);
        }
    }

    @Test void realYrcAndLrcBothProduceEntryGuidesAndMovingPixels() throws Exception {
        String yrc = "[1000,2400](1000,400,0)微光(1400,400,0)穿过(1800,400,0)漫长(2200,400,0)夜色(2600,800,0)肩上\n"
                + "[4500,2400](4500,800,0)换句(5300,800,0)再次(6100,800,0)入场";
        for (String source : List.of(yrc, "[00:01.000]微光穿过漫长夜色\n[00:04.500]换句再次入场")) {
            try (Harness run = new Harness(source)) {
                run.frame(850, true);
                assertTrue(visible(run.pixels) > 0, "Parsed leading animation cannot depend on timed-word data");
                run.frame(1300, true);
                int[] early = run.pixels.clone();
                run.frame(1550, true);
                assertTrue(visible(run.pixels) > 100);
                assertFalse(Arrays.equals(early, run.pixels));
            }
        }
    }

    @Test void readabilityKeepsCrispOppositeInkAndBoundedSamplingAtDifferentWindowSizes() throws Exception {
        WorldLyricsSettings options = settings();
        assertEquals(0x10141C, SonnetCanvasRenderer.contrastInk(0xFFFFFF));
        assertEquals(0xF4F6FA, SonnetCanvasRenderer.contrastInk(0x111111));
        assertTrue(SonnetCanvasRenderer.surfaceWidth(camera(3840, 2160, 70), options)
                >= SonnetCanvasRenderer.surfaceWidth(camera(1280, 720, 70), options));
        assertTrue(SonnetCanvasRenderer.minimumStageStroke(camera(1280, 720, 100), options)
                >= SonnetCanvasRenderer.minimumStageStroke(CAMERA, options));
        try (Harness run = new Harness("[00:01.000]微光穿过 漫长夜色 落在你的肩上\n[00:04.000]换句清屏")) {
            run.frame(2800, true);
            int[] light = run.pixels.clone();
            assertTrue(darkOutline(light) > 20, "White text requires a tight dark silhouette over bright sky");
            run.settings.colorCustom = true; run.settings.colorRgb = 0x101010;
            run.frame(2800, true);
            assertTrue(lightOutline(run.pixels) > 20, "Dark custom ink requires a light silhouette over night terrain");
            run.writeBackgroundProbe(light);
        }
    }

    @Test void continuousLiveSequenceHasGrowingArtworkAnimatedBoundariesAndDeterministicSeeks() throws Exception {
        String[] phrases = {"微光穿过夜色", "你的肩上有星河", "清晰文字重新入场", "随风落下开始清屏", "换句飞入新的画面", "最后一行光芒"};
        StringBuilder text = new StringBuilder();
        for (int line = 0; line < phrases.length; line++) {
            long start = 1000 + line * 1500L;
            text.append('[').append(start).append(",1200]");
            List<String> letters = com.muoniumplayer.core.screens.ncm.LyricGraphemeTimeline.graphemes(phrases[line]);
            for (int i = 0; i < letters.size(); i++) {
                long at = start + i * 1200 / letters.size();
                long next = start + (i + 1) * 1200 / letters.size();
                text.append('(').append(at).append(',').append(next - at).append(",0)").append(letters.get(i));
            }
            text.append('\n');
        }
        try (Harness run = new Harness(text.toString())) {
            String output = System.getenv("MUONIUM_SONNET_RENDER_PROBE_DIR");
            int[] middle = null;
            for (int frame = 0; frame < 72; frame++) {
                long time = 750 + frame * 160L;
                run.frame(time, true);
                if (frame == 20) middle = run.pixels.clone();
                if (run.submissions > 0 && output != null && !output.isEmpty()) {
                    run.writeFrame(Path.of(output).resolve(String.format("live-frame-%03d.png", frame)));
                }
            }
            assertEquals(0, run.submissions, "The live sequence must end with no stale quad");
            run.frame(750 + 20 * 160, true);
            assertArrayEquals(middle, run.pixels, "The full sequence must not change a revisited entry frame");
        }
    }

    @Test void worldDistanceAndScaleRemainTheOnlyWholeBillboardSizingControls() throws Exception {
        try (Harness run = new Harness("[00:01.000]微光穿过夜色\n[00:05.000]再次出现")) {
            run.settings.distance = 12;
            run.settings.scale = 1;
            run.frame(2200, true);
            double x = run.relX, y = run.relY, z = run.relZ;
            float initialScale = run.billboardScale;
            assertEquals(12, Math.sqrt(x * x + y * y + z * z), 0.001);
            assertEquals(WorldLyricsState.PX_TO_WORLD * 12 / WorldLyricsState.DISTANCE_NORM, initialScale, 1e-6);
            run.frame(2600, true);
            assertEquals(x, run.relX, 1e-6); assertEquals(y, run.relY, 1e-6); assertEquals(z, run.relZ, 1e-6);
            assertEquals(initialScale, run.billboardScale, "Playback must not animate the whole billboard size");
            run.settings.scale = 2;
            run.frame(2600, true);
            assertEquals(initialScale * 2, run.billboardScale, 1e-6);
            run.settings.distance = 24;
            run.frame(2600, true);
            assertEquals(24, Math.sqrt(run.relX * run.relX + run.relY * run.relY + run.relZ * run.relZ), 0.001);
            assertEquals(WorldLyricsState.PX_TO_WORLD * 2 * 24 / WorldLyricsState.DISTANCE_NORM, run.billboardScale, 1e-6);
        }
    }

    @Test void teleportsAndPausedCameraTurnsKeepTheIndependentPlaneInView() throws Exception {
        try (Harness run = new Harness("[00:01.000]微光穿过夜色\n[00:04.000]再次出现")) {
            run.frame(1700, true);
            run.camera = view(70, 64, 0, 0);
            run.frame(1700, false);
            assertTrue(run.relZ > run.settings.distance * 0.9,
                    "A teleport must reanchor from the new camera, not a stale legacy row");
            assertEquals(run.settings.distance, Math.sqrt(run.relX * run.relX + run.relY * run.relY + run.relZ * run.relZ), 0.001);
            run.settings.stayOnScreen = true;
            run.camera = view(70, 64, 0, 180);
            for (int frame = 0; frame < 10; frame++) run.frame(1700, false);
            assertTrue(-run.relZ / run.settings.distance > 0.75,
                    "A paused shot must follow turns after all legacy rows have retired");
            assertEquals(run.pushes, run.pops);
        }
    }

    @Test void newAndReanchoredSonnetCanvasStayOffCenterAtItsConfiguredDistance() throws Exception {
        try (Harness run = new Harness("[00:01.000]微光穿过夜色\n[00:04.000]再次出现")) {
            run.settings.stayOnScreen = true;
            run.settings.distance = 14;
            run.camera = view(100, 64, 0, 0);
            run.frame(1700, true);
            double firstDistance = Math.sqrt(run.relX * run.relX + run.relY * run.relY + run.relZ * run.relZ);
            double firstYawOffset = Math.atan2(-run.relX, run.relZ);
            assertEquals(run.settings.distance, firstDistance, 1e-3);
            assertTrue(Math.abs(firstYawOffset) > Math.toRadians(8),
                    "The normal lyric-row anchor should also start away from center");

            run.camera = view(0, 64, 0, 0);
            run.frame(1700, false);

            double distance = Math.sqrt(run.relX * run.relX + run.relY * run.relY + run.relZ * run.relZ);
            double yawOffset = Math.atan2(-run.relX, run.relZ);
            double pitchOffset = Math.atan2(-run.relY, Math.hypot(run.relX, run.relZ));
            assertEquals(run.settings.distance, distance, 1e-3,
                    "Position bias must not change the configured radial distance");
            assertTrue(Math.abs(yawOffset) > Math.toRadians(8),
                    "A new canvas should not be pinned to the horizontal view center");
            assertTrue(Math.abs(pitchOffset) > Math.toRadians(2),
                    "A new canvas should not be pinned to the vertical view center");

            double x = run.relX, y = run.relY, z = run.relZ;
            run.frame(1900, false);
            assertEquals(x, run.relX, 1e-6, "Playback must not animate the canvas position");
            assertEquals(y, run.relY, 1e-6, "Playback must not animate the canvas position");
            assertEquals(z, run.relZ, 1e-6, "Playback must not animate the canvas position");
        }
    }

    private static WorldLyricsState.CameraView view(double x, double y, double z, double yaw) {
        return new WorldLyricsState.CameraView() {
            public double eyeX() { return x; } public double eyeY() { return y; } public double eyeZ() { return z; }
            public double lookX() { return -Math.sin(Math.toRadians(yaw)); }
            public double lookY() { return 0; } public double lookZ() { return Math.cos(Math.toRadians(yaw)); }
            public double yawDeg() { return yaw; } public double pitchDeg() { return 0; }
            public int windowWidth() { return 1920; } public int windowHeight() { return 1080; }
        };
    }

    private static int visible(int[] pixels) {
        if (pixels == null) return 0;
        int count = 0;
        for (int pixel : pixels) if ((pixel >>> 24) > 10) count++;
        return count;
    }
    private static int darkOutline(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel >>> 24) > 100 && (pixel & 255) < 60
                && (pixel >>> 8 & 255) < 60 && (pixel >>> 16 & 255) < 60) count++;
        return count;
    }
    private static int lightOutline(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel >>> 24) > 100 && (pixel & 255) > 220
                && (pixel >>> 8 & 255) > 220 && (pixel >>> 16 & 255) > 220) count++;
        return count;
    }

    static WorldLyricsState.CameraView camera(int width, int height, double fov) {
        return new WorldLyricsState.CameraView() {
            public double eyeX() { return 0; } public double eyeY() { return 64; }
            public double eyeZ() { return 0; } public double lookX() { return 0; }
            public double lookY() { return 0; } public double lookZ() { return 1; }
            public double yawDeg() { return 0; } public double pitchDeg() { return 0; }
            public double fovDeg() { return fov; }
            public int windowWidth() { return width; } public int windowHeight() { return height; }
        };
    }

    static final class Harness implements AutoCloseable {
        final List<LyricLine> previous = new ArrayList<>(CloudMusic.lyrics);
        final CFontRenderer previousBold = FontManager.pf25bold, previousRegular = FontManager.pf25;
        final CFontRenderer bold, regular;
        final SonnetFrameSurface raster = new SonnetFrameSurface();
        final WorldLyricsSettings settings = settings();
        final WorldLyricsRenderer.Host host;
        @FunctionalInterface interface NativePresenter { int[] render(Picture picture, int width, int height) throws Exception; }
        NativePresenter nativePresenter;
        WorldLyricsState.CameraView camera = CAMERA;
        double relX, relY, relZ;
        float billboardScale;
        int[] pixels;
        int submissions, pushes, pops, width, height;
        long wallNanos = 10_000_000_000L;
        Harness(String text) throws Exception {
            SonnetCanvasRenderer.release();
            WorldLyricsState.get().resetForTest();
            Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
            bold = new CFontRenderer(latin, 25, cjk); regular = new CFontRenderer(latin, 25, cjk);
            FontManager.pf25bold = bold; FontManager.pf25 = regular;
            CloudMusic.lyrics.clear();
            CloudMusic.lyrics.addAll(LyricParser.parseLyricText(text, "", ""));
            assertFalse(CloudMusic.lyrics.isEmpty());
            CloudMusic.lyricsRevision++;
            settings.styleModern = true; settings.enabled = true; settings.spreadDeg = 0;
            host = (WorldLyricsRenderer.Host) Proxy.newProxyInstance(WorldLyricsRenderer.Host.class.getClassLoader(),
                    new Class<?>[]{WorldLyricsRenderer.Host.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "supportsGpuTrailCanvas": return true;
                    case "drawGpuTrailCanvas":
                        width = (int) args[1]; height = (int) args[2]; submissions++;
                        pixels = nativePresenter == null ? raster.rasterize((Picture) args[0], width, height).clone()
                                : nativePresenter.render((Picture) args[0], width, height); return true;
                    case "pushBillboard":
                        pushes++; relX = (double) args[0]; relY = (double) args[1]; relZ = (double) args[2];
                        billboardScale = (float) args[5]; return null;
                    case "popBillboard": pops++; return null;
                    default:
                        if (method.getReturnType() == float.class) return 9f;
                        if (method.getReturnType() == int.class) return 9;
                        if (method.getReturnType() == boolean.class) return false;
                        return null;
                }
            });
        }
        void frame(long positionMs, boolean playing) {
            submissions = 0;
            wallNanos += 500_000_000L;
            WorldLyricsRenderer.renderSnapshot(WorldLyricsState.get(), camera, settings, host, wallNanos,
                    new CloudMusic.PlaybackSnapshot(1, 1, positionMs, 20000, playing, null, null));
        }
        void writeFrame(Path target) throws Exception {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < pixels.length; i++) {
                int value = pixels[i];
                image.setRGB(i % width, i / width, value & 0xFF00FF00 | (value & 255) << 16 | value >>> 16 & 255);
            }
            Files.createDirectories(target.getParent());
            ImageIO.write(image, "png", target.toFile());
        }

        void writeBackgroundProbe(int[] source) throws Exception {
            String destination = System.getenv("MUONIUM_SONNET_RENDER_PROBE_DIR");
            if (destination == null || destination.isEmpty()) return;
            BufferedImage rgba = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < source.length; i++) {
                int value = source[i];
                rgba.setRGB(i % width, i / width, value & 0xFF00FF00 | (value & 255) << 16 | value >>> 16 & 255);
            }
            BufferedImage contact = new BufferedImage(width * 3, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = contact.createGraphics();
            for (int scene = 0; scene < 3; scene++) {
                graphics.setColor(scene == 0 ? new java.awt.Color(0xEEF6FC) : scene == 1
                        ? new java.awt.Color(0x070C16) : new java.awt.Color(0x34592C));
                graphics.fillRect(scene * width, 0, width, height);
                if (scene == 2) for (int x = 0; x < width; x += 28) {
                    graphics.setColor(new java.awt.Color(x % 3 == 0 ? 0x88936A : 0x29412D));
                    graphics.fillRect(scene * width + x, (x * 13) % height, 25, height / 2);
                }
                graphics.drawImage(rgba, scene * width, 0, null);
            }
            graphics.dispose(); Files.createDirectories(Path.of(destination));
            ImageIO.write(contact, "png", Path.of(destination).resolve("live-background-readability.png").toFile());
        }
        @Override public void close() {
            SonnetCanvasRenderer.release(); raster.close();
            FontManager.pf25bold = previousBold; FontManager.pf25 = previousRegular;
            bold.close(); regular.close();
            CloudMusic.lyrics.clear(); CloudMusic.lyrics.addAll(previous); CloudMusic.lyricsRevision++;
            WorldLyricsState.get().resetForTest();
        }
    }
}
