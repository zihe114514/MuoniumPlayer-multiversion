package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the replacement QPlayer fluid-background path. */
class QPlayerFluidDrawTest {
    @Test
    void qplayerFluidDrawsAnAlbumTextureAtEverySupportedStyle() {
        assertTrue(SkijaNatives.ensureLoaded());
        try (Surface coverSurface = Surface.makeRasterN32Premul(32, 32);
             Surface output = Surface.makeRasterN32Premul(64, 64);
             Image cover = makeCover(coverSurface)) {
            for (int style = 0; style <= 2; style++) {
                SkijaFluidBackground fluid = new SkijaFluidBackground(100 + style);
                try {
                    output.getCanvas().clear(0xFF000000);
                    assertTrue(fluid.render(output.getCanvas(), 64f, 64f, cover,
                            "qplayer-fluid-test-" + style, .75f, System.nanoTime(),
                            style, 100, 1f, .4f));
                    assertTrue(nonBlack(output), "QPlayer style " + style + " must draw");
                } finally {
                    fluid.dispose();
                }
            }
        }
    }

    private static Image makeCover(Surface surface) {
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF312E81);
        try (io.github.humbleui.skija.Paint paint =
                     new io.github.humbleui.skija.Paint().setColor(0xFFFB7185)) {
            canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(0, 0, 18, 32), paint);
        }
        return surface.makeImageSnapshot();
    }

    private static boolean nonBlack(Surface surface) {
        try (io.github.humbleui.skija.Bitmap bitmap = new io.github.humbleui.skija.Bitmap()) {
            bitmap.allocN32Pixels(64, 64, false);
            assertTrue(surface.getCanvas().readPixels(bitmap, 0, 0));
            for (int y = 4; y < 64; y += 8) {
                for (int x = 4; x < 64; x += 8) {
                    if ((bitmap.getColor(x, y) & 0x00FFFFFF) != 0) return true;
                }
            }
            return false;
        }
    }
}

