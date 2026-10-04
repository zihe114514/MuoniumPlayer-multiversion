package com.muoniumplayer.core.rendering.font;

import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;
import io.github.humbleui.skija.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

class CFontRendererCacheTest {
    private static java.awt.Font latin;
    private static java.awt.Font cjk;

    @BeforeAll static void fonts() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        latin = load("/muonium/fonts/sfregular.otf");
        cjk = load("/muonium/fonts/pf_normal.ttf");
    }

    private static java.awt.Font load(String path) throws Exception {
        try (InputStream stream = CFontRendererCacheTest.class.getResourceAsStream(path)) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            java.awt.Font font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
            SkijaText.register(font, bytes);
            return font;
        }
    }

    private static TextBlobCache cache(CFontRenderer font) throws Exception {
        Field field = CFontRenderer.class.getDeclaredField("plainTextBlobs");
        field.setAccessible(true);
        return (TextBlobCache) field.get(font);
    }

    private static byte[] render(CFontRenderer font, String text, double x, double y, int color,
                                 double glow, Shader shader) throws Exception {
        Field canvasField = SkijaHost.class.getDeclaredField("canvas");
        canvasField.setAccessible(true);
        Object previous = canvasField.get(null);
        try (Surface surface = Surface.makeRasterN32Premul(360, 110); Bitmap bitmap = new Bitmap()) {
            surface.getCanvas().clear(0);
            canvasField.set(null, surface.getCanvas());
            SkijaGfx.setBlendMode(BlendMode.SRC_OVER);
            if (shader != null) font.drawStringShaded(text, x, y, shader);
            else if (glow > 0) font.drawStringGlow(text, x, y, color, glow);
            else font.drawString(text, x, y, color);
            assertTrue(bitmap.allocPixels(ImageInfo.makeN32Premul(360, 110)));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            return bitmap.readPixels();
        } finally {
            canvasField.set(null, previous);
        }
    }

    @Test void cachedLayoutMatchesUncachedPathAtPixelLevel() throws Exception {
        try (CFontRenderer font = new CFontRenderer(latin, 18f, cjk)) {
            for (String text : new String[]{"AV Music 2026", "网易云 Music（1）・", "First line\n第二行", "\nleading\n"}) {
                byte[] cached = render(font, text, 10.25, 10.5, 0xC840A0E0, 0, null);
                byte[] uncached = render(font, "§r" + text, 10.25, 10.5, 0xC840A0E0, 0, null);
                assertArrayEquals(uncached, cached, "layout changed: " + text);
                boolean visible = false;
                for (byte value : cached) if (value != 0) { visible = true; break; }
                assertTrue(visible, "no text was rendered");
            }
        }
    }

    @Test void animationPositionTintAndGlowReuseTheSameNativeBlob() throws Exception {
        try (CFontRenderer font = new CFontRenderer(latin, 18f, cjk)) {
            String text = "音乐 Animation";
            byte[] original = render(font, text, 10, 10, 0xFFFFFFFF, 0, null);
            TextBlob blob = cache(font).get(text);
            assertNotNull(blob);
            byte[] moved = render(font, text, 14.25, 11.5, 0x80FF5500, 0, null);
            assertSame(blob, cache(font).get(text));
            assertFalse(java.util.Arrays.equals(original, moved));
            render(font, text, 10, 10, 0xAAFFCC44, 2.0, null);
            assertSame(blob, cache(font).get(text));
            assertFalse(blob.isClosed());
        }
    }

    @Test void cachedGlowAndShaderMatchOriginalDrawingAndDoNotLeakShaderState() throws Exception {
        try (CFontRenderer font = new CFontRenderer(latin, 18f, cjk);
             Shader shader = Shader.makeLinearGradient(0, 0, 300, 0, new int[]{0xFFFFFFFF, 0x2044CCFF})) {
            String text = "Music 歌词扫光";
            assertArrayEquals(render(font, "§r" + text, 10, 10, 0x90FFAA00, 1.5, null),
                    render(font, text, 10, 10, 0x90FFAA00, 1.5, null));
            assertArrayEquals(render(font, "§r" + text, 10, 10, 0, 0, shader),
                    render(font, text, 10, 10, 0, 0, shader));
            assertArrayEquals(render(font, "§r" + text, 10, 10, 0xFF33DD99, 0, null),
                    render(font, text, 10, 10, 0xFF33DD99, 0, null));
        }
    }

    @Test void formattedTextAndLongStringsStayOutsidePlainCache() throws Exception {
        try (CFontRenderer font = new CFontRenderer(latin, 18f, cjk)) {
            String formatted = "§cRed §rNormal";
            render(font, formatted, 10, 10, 0xFFFFFFFF, 0, null);
            assertNull(cache(font).get(formatted));
            String longText = "a".repeat(1025);
            render(font, longText, 10, 10, 0xFFFFFFFF, 0, null);
            assertNull(cache(font).get(longText));
        }
    }

    @Test void retargetAndCloseReleaseAllCachedNativeObjects() throws Exception {
        CFontRenderer font = new CFontRenderer(latin, 18f, cjk);
        try {
            String text = "Cache 字体";
            render(font, text, 10, 10, 0xFFFFFFFF, 0, null);
            TextBlob old = cache(font).get(text);
            font.retarget(cjk, new java.awt.Font[]{latin}, font.lineBox(), false, null);
            assertTrue(old.isClosed());
            assertNull(cache(font).get(text));
            render(font, text, 10, 10, 0xFFFFFFFF, 0, null);
            TextBlob updated = cache(font).get(text);
            assertNotNull(updated);
            assertNotSame(old, updated);
            font.close();
            assertTrue(updated.isClosed());
            assertNull(cache(font).get(text));
        } finally {
            font.close();
        }
    }

    private static TextBlob blob() {
        try (Font font = new Font(SkijaText.typefaceFor(latin), 18f)) {
            return TextBlob.makeFromPosH(new short[]{SkijaText.typefaceFor(latin).getUTF32Glyph('A')},
                    new float[]{0}, 20, font);
        }
    }

    @Test void lruEvictionClosesOldestNativeBlob() {
        try (TextBlobCache cache = new TextBlobCache(2, 100)) {
            TextBlob a = blob(), b = blob(), c = blob();
            cache.put("a", a); cache.put("b", b);
            assertSame(a, cache.get("a"));
            cache.put("c", c);
            assertTrue(b.isClosed());
            assertFalse(a.isClosed());
            assertFalse(c.isClosed());
            cache.close();
            assertTrue(a.isClosed());
            assertTrue(c.isClosed());
        }
    }

    @Test void characterBudgetAndReplacementAlsoReleaseResources() {
        try (TextBlobCache cache = new TextBlobCache(10, 5)) {
            TextBlob a = blob(), b = blob(), replacement = blob();
            cache.put("aaa", a); cache.put("bbb", b);
            assertTrue(a.isClosed());
            assertFalse(b.isClosed());
            cache.put("bbb", replacement);
            assertTrue(b.isClosed());
            assertSame(replacement, cache.get("bbb"));
        }
    }
}
