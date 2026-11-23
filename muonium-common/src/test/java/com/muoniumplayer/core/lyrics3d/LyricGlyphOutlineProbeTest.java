package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresSkijaNative
class LyricGlyphOutlineProbeTest {

    private static final class Host implements LyricGlyphAtlas.Host {
        int width;
        int height;
        int[] lastCell;

        @Override public Object createPage(int width, int height) { return new Object(); }

        @Override
        public void uploadCell(Object page, int x, int y, int width, int height, int[] pixelsArgb) {
            this.width = width;
            this.height = height;
            this.lastCell = Arrays.copyOf(pixelsArgb, pixelsArgb.length);
        }

        @Override public void deletePage(Object page) { }
    }

    @Test
    void atlasOutlineHasTransparentInteriorAndCanBeInspected() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        CFontRenderer previousFont = FontManager.pf25bold;
        Host host = new Host();
        CFontRenderer font = null;
        try {
            Font source = loadFont("/muonium/fonts/sfregular.otf");
            font = new CFontRenderer(source, 18f);
            FontManager.pf25bold = font;
            LyricGlyphAtlas.setHost(host);
            LyricGlyphAtlas.invalidateAll();
            LyricGlyphAtlas.beginFrame();

            LyricGlyphAtlas.GlyphCell cell = LyricGlyphAtlas.getOutlineCell('O', 0.8f);
            assertNotNull(cell);
            assertNotNull(host.lastCell);
            int minX = cell.w, minY = cell.h, maxX = -1, maxY = -1;
            for (int y = 0; y < cell.h; y++) {
                for (int x = 0; x < cell.w; x++) {
                    if (((host.lastCell[y * cell.w + x] >>> 24) & 0xFF) > 24) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            assertTrue(maxX > minX && maxY > minY, "stroke mask should contain visible pixels");
            int centerAlpha = (host.lastCell[((minY + maxY) / 2) * cell.w + (minX + maxX) / 2] >>> 24) & 0xFF;
            assertTrue(centerAlpha < 24, "outline center must remain hollow");

            String output = System.getenv("MUONIUM_SONNET_ATLAS_PROBE_PATH");
            if (output != null && !output.isEmpty()) writeProbe(output, host.lastCell, cell.w, cell.h);
        } finally {
            LyricGlyphAtlas.invalidateAll();
            LyricGlyphAtlas.beginFrame();
            LyricGlyphAtlas.setHost(null);
            FontManager.pf25bold = previousFont;
            if (font != null) font.close();
        }
    }

    private static Font loadFont(String resource) throws Exception {
        try (InputStream stream = LyricGlyphOutlineProbeTest.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing probe font: " + resource);
            byte[] bytes = stream.readAllBytes();
            Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
            SkijaText.register(font, bytes);
            return font;
        }
    }

    private static void writeProbe(String filename, int[] pixels, int width, int height) throws Exception {
        BufferedImage cell = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        cell.setRGB(0, 0, width, height, pixels, 0, width);
        BufferedImage image = new BufferedImage(width * 5, height * 5, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setColor(new Color(0xFF101720, true));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.drawImage(cell, 0, 0, image.getWidth(), image.getHeight(), null);
        graphics.dispose();
        Path path = Path.of(filename);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }
}
