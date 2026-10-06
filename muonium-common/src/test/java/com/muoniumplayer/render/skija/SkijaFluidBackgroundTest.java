package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkijaFluidBackgroundTest {
    @Test
    void allQPlayerStylesDrawAndRespondToTimeAndAudio() {
        assertTrue(SkijaNatives.ensureLoaded());
        try (Surface coverSurface = Surface.makeRasterN32Premul(32, 32);
             Surface output = Surface.makeRasterN32Premul(96, 64);
             Image cover = makeCover(coverSurface)) {
            for (int style = SkijaFluidBackground.STYLE_PIXI_RENDERER;
                 style <= SkijaFluidBackground.STYLE_CLASSIC; style++) {
                SkijaFluidBackground fluid = new SkijaFluidBackground(1234L + style);
                try {
                    int[] still = draw(fluid, output, cover, style, 0f, 0f);
                    int[] moved = draw(fluid, output, cover, style, 8f, 0f);
                    int[] reacting = draw(fluid, output, cover, style, 8f, 1f);
                    assertTrue(nonBlackPixels(still) > 100,
                            "style " + style + " must draw the album-cover fluid");
                    assertTrue(differentPixels(still, moved) > 5,
                            "style " + style + " must animate as QPlayer time advances");
                    assertTrue(differentPixels(moved, reacting) > 2,
                            "style " + style + " must react to the audio drive");
                } finally {
                    fluid.dispose();
                }
            }
        }
    }

    @Test
    void switchingCoversCrossFadesWithoutDroppingTheFluidFrame() {
        assertTrue(SkijaNatives.ensureLoaded());
        try (Surface coverA = Surface.makeRasterN32Premul(32, 32);
             Surface coverB = Surface.makeRasterN32Premul(32, 32);
             Surface output = Surface.makeRasterN32Premul(96, 64);
             Image first = makeCover(coverA);
             Image second = makeSecondCover(coverB)) {
            SkijaFluidBackground fluid = new SkijaFluidBackground(System.nanoTime());
            long now = System.nanoTime();
            try {
                assertTrue(fluid.render(output.getCanvas(), 96f, 64f, first, "first",
                        2f, now, SkijaFluidBackground.STYLE_PIXI_RENDERER, 100, 1f, 0f));
                int[] before = pixels(output);
                assertTrue(fluid.render(output.getCanvas(), 96f, 64f, second, "second",
                        2f, now + 1L, SkijaFluidBackground.STYLE_PIXI_RENDERER, 100, 1f, 0f));
                assertTrue(fluid.render(output.getCanvas(), 96f, 64f, second, "second",
                        2f, now + 450_000_000L, SkijaFluidBackground.STYLE_PIXI_RENDERER, 100, 1f, 0f));
                int[] transition = pixels(output);
                assertTrue(differentPixels(before, transition) > 2,
                        "the outgoing and incoming QPlayer cover textures should both contribute");
                assertTrue(nonBlackPixels(transition) > 100);
            } finally {
                fluid.dispose();
            }
        }
    }

    private static int[] draw(SkijaFluidBackground fluid, Surface output, Image cover,
                              int style, float time, float audio) {
        output.getCanvas().clear(0xFF000000);
        assertTrue(fluid.render(output.getCanvas(), 96f, 64f, cover, "track-" + style,
                time, System.nanoTime(), style, 100, 1f, audio));
        return pixels(output);
    }

    private static Image makeCover(Surface surface) {
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF14213D);
        try (io.github.humbleui.skija.Paint paint =
                     new io.github.humbleui.skija.Paint().setColor(0xFFE85D04)) {
            canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(0, 0, 16, 32), paint);
        }
        return surface.makeImageSnapshot();
    }

    private static Image makeSecondCover(Surface surface) {
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF14532D);
        try (io.github.humbleui.skija.Paint paint =
                     new io.github.humbleui.skija.Paint().setColor(0xFFEC4899)) {
            canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(0, 0, 32, 16), paint);
        }
        return surface.makeImageSnapshot();
    }

    private static int[] pixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(96, 64, false);
            assertTrue(surface.getCanvas().readPixels(bitmap, 0, 0));
            int[] pixels = new int[96 * 64];
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 96; x++) pixels[y * 96 + x] = bitmap.getColor(x, y);
            }
            return pixels;
        }
    }

    private static int differentPixels(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) count++;
        return count;
    }

    private static int nonBlackPixels(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel & 0x00FFFFFF) != 0) count++;
        return count;
    }
}
