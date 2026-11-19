package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class MusicCardSurfaceTest {
    @BeforeAll
    static void nativeReady() {
        assertTrue(SkijaNatives.ensureLoaded());
    }

    @Test
    void rasterAndInputUseTheSameContinuousCorner() throws Exception {
        for (double scale : new double[]{.6, 1.0, 2.0}) {
            int width = (int) (MusicCardLayout.WIDTH * scale);
            int height = (int) (MusicCardLayout.HEIGHT * scale);
            double radius = MusicCardLayout.RADIUS * scale;
            try (io.github.humbleui.skija.Path shape = MusicCardShape.outline(0, 0, width, height, radius)) {
                for (int y = 0; y < height; y += 3) for (int x = 0; x < width; x += 3) {
                    assertEquals(shape.contains(x + .37f, y + .37f),
                            MusicCardShape.contains(x + .37, y + .37, 0, 0, width, height, radius));
                }
            }
        }
    }

    @Test
    void lowerRimTracksCoverHueWithoutTurningTheUpperTitleAreaIntoColouredFog() throws Exception {
        int[] pink = render(0xE58DBD, 0xB29FE6);
        int[] greenPixels = render(0x86BC7E, 0x9DB6AD);
        int width = 368;
        int lowerPink = pink[163 * width + 112];
        int lowerGreen = greenPixels[163 * width + 112];
        assertTrue(red(lowerPink) > green(lowerPink), "粉色封面必须在下缘留下粉色反射");
        assertTrue(green(lowerGreen) > red(lowerGreen), "绿色封面必须改变下缘反射色相");
        assertTrue(brightness(pink[163 * width + 112]) > brightness(pink[155 * width + 112]),
                "反射要集中在边缘，不能只是一片均匀的底部色晕");
        assertTrue(brightness(pink[29 * width + 170]) < 18, "标题区域应保持暗色");
        assertEquals(0, pink[16 * width + 16] >>> 24, "连续圆角外不能出现裁剪残影");
    }

    private static int[] render(int accent, int secondary) throws Exception {
        Field field = SkijaHost.class.getDeclaredField("canvas");
        field.setAccessible(true);
        Object previous = field.get(null);
        try (Surface surface = Surface.makeRasterN32Premul(368, 180); Bitmap bitmap = new Bitmap()) {
            field.set(null, surface.getCanvas());
            surface.getCanvas().clear(0);
            MusicCardSurface.paint(surface.getCanvas(), 16, 16, 336, 148, 28, accent, secondary, 1f, false);
            assertTrue(bitmap.allocN32Pixels(368, 180, false));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            int[] result = new int[368 * 180];
            for (int y = 0; y < 180; y++) for (int x = 0; x < 368; x++) result[y * 368 + x] = bitmap.getColor(x, y);
            return result;
        } finally {
            field.set(null, previous);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    private static int red(int color) { return (color >>> 16) & 255; }
    private static int green(int color) { return (color >>> 8) & 255; }
    private static int brightness(int color) { return Math.max(red(color), Math.max(green(color), color & 255)); }
}
