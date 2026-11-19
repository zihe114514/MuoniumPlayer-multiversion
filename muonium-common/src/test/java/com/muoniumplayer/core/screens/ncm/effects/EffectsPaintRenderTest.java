package com.muoniumplayer.core.screens.ncm.effects;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.muoniumplayer.core.screens.ncm.effects.EffectsLayout.Box;
import static org.junit.jupiter.api.Assertions.*;

@RequiresSkijaNative
class EffectsPaintRenderTest {

    @BeforeAll
    static void fonts() {
        assertTrue(SkijaNatives.ensureLoaded());
        FontManager.loadFonts();
    }

    @Test
    void actualIconAndLabelInkStayCenteredInsideButtonsIncludingTruncatedNames() throws Exception {
        Field host = SkijaHost.class.getDeclaredField("canvas");
        host.setAccessible(true);
        Object previous = host.get(null);
        List<Box> textInk = new ArrayList<>();
        try (Surface surface = Surface.makeRasterN32Premul(940, 560); EffectsPaint paint = new EffectsPaint()) {
            host.set(null, surface.getCanvas());
            surface.getCanvas().clear(0xFFF4F0EA);
            paint.setPalette(EffectsPalette.fromColors(0xF4F0EA, 0xFFFCF7, 0xE7DDD1, 0xD4C6B8, 0x2A2723, 0x817970, 0xB85C38));
            paint.setProbe(new EffectsPaint.Probe() {
                @Override public void text(String source, String shown, Box ink, Box allowed) {
                    assertTrue(ink.x() >= allowed.x() - .1 && ink.right() <= allowed.right() + .1, source);
                    assertEquals(allowed.centerY(), ink.centerY(), .01, "必须按真实字形墨迹垂直居中");
                    textInk.add(ink);
                }
                @Override public void button(String label, Box button, Box icon, Box text, Box content) {
                    assertEquals(button.centerX(), content.centerX(), .01, label);
                    assertTrue(content.x() >= button.x() && content.right() <= button.right(), label);
                    if (icon.width() > 0) assertEquals(button.centerY(), icon.centerY(), .01);
                }
            });
            String[] labels = {"导入预设", "应用", "-18.0 dB 配置", "Very long preset name", "文字很长的自定义方案名称"};
            for (int row = 0; row < labels.length; row++) {
                for (int col = 0; col < 4; col++) {
                    Box box = new Box(20 + col * 224, 18 + row * 54, new double[]{70, 96, 156, 206}[col], 34);
                    paint.round(surface.getCanvas(), box, col == 1 ? 8 : 17, 0xFFE0D3C7);
                    paint.buttonLabel(surface.getCanvas(), EffectsTypography.label(), labels[row], col == 0 ? null : EffectsPaint.Icon.IMPORT, box, 0xFF2A2723);
                }
            }
            EffectsPaint.Icon[] icons = EffectsPaint.Icon.values();
            for (int i = 0; i < icons.length; i++) {
                Box box = new Box(22 + (i % 12) * 74, 310 + (i / 12) * 74, 40, 40);
                paint.round(surface.getCanvas(), box, 14, 0xFFE0D3C7);
                paint.icon(surface.getCanvas(), icons[i], box.inset(8), 0xFF111111);
            }
            try (Image image = surface.makeImageSnapshot(); Bitmap bitmap = new Bitmap()) {
                assertTrue(bitmap.allocN32Pixels(940, 560, false));
                assertTrue(image.readPixels(bitmap, 0, 0));
                for (int i = 0; i < icons.length; i++) {
                    int left = 22 + (i % 12) * 74, top = 310 + (i / 12) * 74;
                    int minX = left + 40, minY = top + 40, maxX = left - 1, maxY = top - 1, pixels = 0;
                    for (int y = top; y < top + 40; y++) for (int x = left; x < left + 40; x++) {
                        int rgb = bitmap.getColor(x, y) & 0xFFFFFF;
                        if ((rgb >>> 16) < 70 && (rgb >>> 8 & 255) < 70 && (rgb & 255) < 70) {
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y); maxY = Math.max(maxY, y); pixels++;
                        }
                    }
                    assertTrue(pixels > 10, "图标必须真实画出：" + icons[i]);
                    assertEquals(left + 20, (minX + maxX + 1) * .5, 2, "图标横向光学中心：" + icons[i]);
                    assertEquals(top + 20, (minY + maxY + 1) * .5, 2, "图标纵向光学中心：" + icons[i]);
                    assertTrue(minX > left + 4 && maxX < left + 36 && minY > top + 4 && maxY < top + 36);
                }
                String output = System.getProperty("muonium.effects.previewDir");
                if (output != null && !output.isBlank()) {
                    Files.createDirectories(Path.of(output));
                    try (Data data = image.encodeToData(EncodedImageFormat.PNG)) {
                        Files.write(Path.of(output).resolve("controls-and-icons.png"), data.getBytes());
                    }
                }
            }
            assertFalse(textInk.isEmpty());
        } finally { host.set(null, previous); }
    }

    @Test
    void glyphInkCacheInvalidatesWhenFontMetricsChange() {
        CFontRenderer reference = EffectsTypography.body();
        try (CFontRenderer font = reference.deriveAtSize(16)) {
            var before = font.getGlyphBounds("调音 2.5 dB");
            assertNotNull(before);
            assertSame(before, font.getGlyphBounds("调音 2.5 dB"));
            assertTrue(font.refreshSkiaFonts());
            assertNotSame(before, font.getGlyphBounds("调音 2.5 dB"));
        }
    }
}
