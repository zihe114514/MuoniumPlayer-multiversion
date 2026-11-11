package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@RequiresSkijaNative
class RowGlowSpriteCacheTest {
    private static Font latin;

    @BeforeAll
    static void font() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        try (InputStream stream = RowGlowSpriteCacheTest.class.getResourceAsStream("/muonium/fonts/sfregular.otf")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            latin = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
            SkijaText.register(latin, bytes);
        }
    }

    @AfterEach
    void clear() {
        RowGlowSprite.close();
    }

    private static float[] mask(int w, int h, int left) {
        float[] mask = new float[w * h];
        for (int y = h / 3; y < h * 2 / 3; y++) {
            for (int x = left; x < Math.min(w, left + 8); x++) {
                mask[y * w + x] = 1f;
            }
        }
        return mask;
    }

    @Test
    void alternatingMasksReusePixelsAndRestoreEachRowsDimensions() {
        RowGlowSprite.close();
        float[] a = mask(64, 32, 20);
        float[] b = mask(96, 48, 40);
        int[] first = RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 2);
        assertNotNull(first);
        assertEquals(32, RowGlowSprite.width());
        assertEquals(16, RowGlowSprite.height());
        assertEquals(28f, RowGlowSprite.textWidth());
        int[] second = RowGlowSprite.bakeFromMask(b, 96, 48, 20, .7f, 1f, 0x4080FF, 4);
        assertNotNull(second);
        assertEquals(48, RowGlowSprite.width());
        assertEquals(24, RowGlowSprite.height());
        assertEquals(40f, RowGlowSprite.textWidth());
        assertSame(first, RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 2));
        assertEquals(32, RowGlowSprite.width());
        assertEquals(16, RowGlowSprite.height());
        assertEquals(28f, RowGlowSprite.textWidth());
        RowGlowSprite.close();
        assertArrayEquals(first, RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 2));
    }

    @Test
    void maskFingerprintCollisionsDoNotReuseAChangedPixel() {
        RowGlowSprite.close();
        float[] a = mask(64, 32, 20);
        int[] first = RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 2);
        float[] changed = a.clone();
        // n/512 = 4: the old sampled fingerprint deliberately misses index 1.
        changed[1] = 1f;
        int[] second = RowGlowSprite.bakeFromMask(changed, 64, 32, 20, .7f, 1f, 0xFF4080, 2);
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
        RowGlowSprite.close();
        assertArrayEquals(second, RowGlowSprite.bakeFromMask(changed, 64, 32, 20, .7f, 1f, 0xFF4080, 2));
    }

    @Test
    void fontSizeAndPaddingArePartOfMaskCacheIdentity() {
        RowGlowSprite.close();
        float[] a = mask(64, 32, 20);
        int[] first = RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 2);
        assertNotSame(first, RowGlowSprite.bakeFromMask(a, 64, 32, 30, .7f, 1f, 0xFF4080, 2));
        assertNotSame(first, RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 4));
        assertEquals(24f, RowGlowSprite.textWidth());
    }

    @Test
    void lruEvictsOldestEntryWithoutChangingPixelsOrGrowingBeyondTheBudget() throws Exception {
        RowGlowSprite.close();
        float[] a = mask(64, 32, 20);
        int[] first = RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 2);
        for (int i = 0; i < 8; i++) {
            assertNotNull(RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0x408000 + i, 2));
        }
        Map<?, ?> cache = (Map<?, ?>) field("CACHE").get(null);
        assertEquals(8, cache.size());
        assertTrue(field("cachedPixelCount").getInt(null) <= field("CACHE_PIXEL_BUDGET").getInt(null));
        int[] remade = RowGlowSprite.bakeFromMask(a, 64, 32, 20, .7f, 1f, 0xFF4080, 2);
        assertNotSame(first, remade);
        assertArrayEquals(first, remade);
    }

    @Test
    void alternatingTextRestoresMetadataAndDoesNotConfuseRasterTargetWithCachedSize() {
        CFontRenderer previous = FontManager.pf25bold;
        try (CFontRenderer base = new CFontRenderer(latin, 12.5f)) {
            FontManager.pf25bold = base;
            RowGlowSprite.close();
            int[] first = RowGlowSprite.bake("Hi", 0xFF4080, .7f, .5f);
            assertNotNull(first);
            int width = RowGlowSprite.width();
            int height = RowGlowSprite.height();
            float textWidth = RowGlowSprite.textWidth();
            assertNotNull(RowGlowSprite.bake("Longer lyric", 0x4080FF, .7f, .5f));
            assertSame(first, RowGlowSprite.bake("Hi", 0xFF4080, .7f, .5f));
            assertEquals(width, RowGlowSprite.width());
            assertEquals(height, RowGlowSprite.height());
            assertEquals(textWidth, RowGlowSprite.textWidth());
            int[] third = RowGlowSprite.bake("New", 0x4080FF, .7f, .5f);
            assertNotNull(third);
            RowGlowSprite.close();
            assertArrayEquals(third, RowGlowSprite.bake("New", 0x4080FF, .7f, .5f));
            assertArrayEquals(first, RowGlowSprite.bake("Hi", 0xFF4080, .7f, .5f));
        } finally {
            RowGlowSprite.close();
            FontManager.pf25bold = previous;
        }
    }

    @Test
    void fontEpochInvalidatesCachedTextEvenWhenTheBaseRendererIdentityStaysTheSame() {
        CFontRenderer previous = FontManager.pf25bold;
        try (CFontRenderer base = new CFontRenderer(latin, 12.5f)) {
            FontManager.pf25bold = base;
            RowGlowSprite.close();
            int[] first = RowGlowSprite.bake("Hi", 0xFF4080, .7f, .5f);
            assertNotNull(first);
            LyricGlyphAtlas.onFontChanged();
            int[] next = RowGlowSprite.bake("Hi", 0xFF4080, .7f, .5f);
            assertNotNull(next);
            assertNotSame(first, next);
            assertArrayEquals(first, next);
        } finally {
            RowGlowSprite.close();
            FontManager.pf25bold = previous;
        }
    }

    private static Field field(String name) throws Exception {
        Field field = RowGlowSprite.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
