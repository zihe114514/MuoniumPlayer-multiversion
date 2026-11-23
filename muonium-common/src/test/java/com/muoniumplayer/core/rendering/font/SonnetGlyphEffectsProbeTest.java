package com.muoniumplayer.core.rendering.font;

import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresSkijaNative
class SonnetGlyphEffectsProbeTest {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 500;
    private static final int BACKGROUND = 0xFF101720;

    @Test
    void trueStrokeEchoAndChromaticGlyphLayersRender() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        Font latin = loadFont("/muonium/fonts/sfregular.otf");
        Font cjk = loadFont("/muonium/fonts/pf_normal.ttf");
        try (CFontRenderer display = new CFontRenderer(latin, 30f, cjk);
             CFontRenderer poster = display.deriveAtSize(58f);
             Surface surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT);
             Bitmap bitmap = new Bitmap()) {
            surface.getCanvas().clear(BACKGROUND);
            SkijaGfx.setBlendMode(BlendMode.SRC_OVER);
            surface.getCanvas().save();

            poster.drawGlyphsOutlineOn(surface.getCanvas(), "LUMEN", 70, 95,
                    0x5576E7FF, 2.0f);
            display.drawGlyphsOutlineOn(surface.getCanvas(), "回声", 120, 235,
                    0x6683EFFF, 1.2f);
            display.drawGlyphsOutlineOn(surface.getCanvas(), "回声", 120, 250,
                    0x3376E7FF, 1.0f);
            display.drawGlyphsOn(surface.getCanvas(), "回声", 120, 235,
                    0xFFFFFFFF, 255, 0f, null);

            display.drawGlyphsOn(surface.getCanvas(), "CHROMA", 525, 235,
                    0x99FF245E, 255, 0f, null);
            display.drawGlyphsOn(surface.getCanvas(), "CHROMA", 528, 235,
                    0x9920E8FF, 255, 0f, null);
            display.drawGlyphsOn(surface.getCanvas(), "CHROMA", 526.5, 235,
                    0xFFFFFFFF, 255, 0f, null);
            surface.getCanvas().restore();

            assertTrue(bitmap.allocPixels(ImageInfo.makeN32Premul(WIDTH, HEIGHT)));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            int[] pixels = toStraightArgb(bitmap.readPixels(), WIDTH, HEIGHT);
            boolean visible = false;
            for (int pixel : pixels) {
                if (pixel != BACKGROUND) {
                    visible = true;
                    break;
                }
            }
            assertTrue(visible, "outlined and chromatic text probe must not be blank");

            String output = System.getenv("MUONIUM_SONNET_GLYPH_PROBE_PATH");
            if (output != null && !output.isEmpty()) {
                BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
                Path path = Path.of(output);
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                ImageIO.write(image, "png", path.toFile());
            }
        }
    }

    private static Font loadFont(String resource) throws Exception {
        try (InputStream stream = SonnetGlyphEffectsProbeTest.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing probe font: " + resource);
            byte[] bytes = stream.readAllBytes();
            Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
            SkijaText.register(font, bytes);
            return font;
        }
    }

    private static int[] toStraightArgb(byte[] source, int width, int height) {
        int[] result = new int[width * height];
        for (int i = 0; i < result.length; i++) {
            int offset = i * 4;
            int blue = source[offset] & 0xFF;
            int green = source[offset + 1] & 0xFF;
            int red = source[offset + 2] & 0xFF;
            int alpha = source[offset + 3] & 0xFF;
            if (alpha > 0 && alpha < 255) {
                red = Math.min(255, (red * 255 + alpha / 2) / alpha);
                green = Math.min(255, (green * 255 + alpha / 2) / alpha);
                blue = Math.min(255, (blue * 255 + alpha / 2) / alpha);
            }
            result[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        return result;
    }
}
