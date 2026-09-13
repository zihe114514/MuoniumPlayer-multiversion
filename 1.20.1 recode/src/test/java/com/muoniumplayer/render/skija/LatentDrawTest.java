package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LatentDrawTest {
    @Test
    void latentEffectsActuallyDrawOffscreen() {
        assertTrue(SkijaNatives.ensureLoaded());
        int[] palette = {0x2B6CB0, 0xF6AD55, 0x1A202C, 0xE53E3E, 0x38A169, 0x805AD5};
        try (Surface surface = Surface.makeRasterN32Premul(64, 64)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0xFF000000);
            assertTrue(SkijaLatentEffects.draw(canvas, 0, 0, 64, 64,
                    1.2f, .8f, .8f, .1f, 2.5f, .55f, 1f, palette));
            try (Bitmap bitmap = new Bitmap()) {
                bitmap.allocN32Pixels(64, 64, false);
                assertTrue(canvas.readPixels(bitmap, 0, 0));
                Set<Integer> colors = new HashSet<Integer>();
                for (int y = 8; y < 64; y += 12) {
                    for (int x = 8; x < 64; x += 12) colors.add(bitmap.getColor(x, y));
                }
                assertTrue(colors.size() > 4, "shader 应产生封面配色和抖动层次");
            }
        }
    }
}
