package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.TextGlow;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@RequiresSkijaNative
class LyricGlyphAtlasBloomFieldTest {
    private static Font latin;
    private CFontRenderer previousFont;
    private CFontRenderer base;
    private LyricGlyphAtlas.Host previousHost;
    private CaptureHost host;

    private static final class CaptureHost implements LyricGlyphAtlas.Host {
        final List<int[]> pixels = new ArrayList<>();
        boolean reject;

        @Override
        public Object createPage(int width, int height) {
            return new Object();
        }

        @Override
        public void uploadCell(Object page, int x, int y, int w, int h, int[] pixelsArgb) {
            if (reject) throw new IllegalStateException("Rejected upload");
            assertEquals(w * h, pixelsArgb.length);
            pixels.add(pixelsArgb.clone());
        }

        @Override
        public void deletePage(Object page) {
        }
    }

    @BeforeAll
    static void font() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        try (InputStream stream = LyricGlyphAtlasBloomFieldTest.class.getResourceAsStream("/muonium/fonts/sfregular.otf")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            latin = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
            SkijaText.register(latin, bytes);
        }
    }

    @BeforeEach
    void install() {
        previousFont = FontManager.pf25bold;
        previousHost = LyricGlyphAtlas.host();
        LyricGlyphAtlas.invalidateAll();
        base = new CFontRenderer(latin, 12.5f);
        FontManager.pf25bold = base;
        host = new CaptureHost();
        LyricGlyphAtlas.setHost(host);
    }

    @AfterEach
    void clear() {
        LyricGlyphAtlas.invalidateAll();
        LyricGlyphAtlas.setHost(previousHost);
        FontManager.pf25bold = previousFont;
        base.close();
    }

    private LyricGlyphAtlas.GlyphCell glow(char c, float strength, float spread, int color) {
        LyricGlyphAtlas.beginFrame();
        LyricGlyphAtlas.GlyphCell cell = LyricGlyphAtlas.getGlowCell(c, strength, spread,
                color, 0x804020, TextGlow.GLOW_TIER_ULTRA);
        assertNotNull(cell);
        return cell;
    }

    @Test
    void colorsAndStrengthsReuseTheSameConvolutionButKeepTheirOwnPixels() throws Exception {
        glow('A', .45f, .5f, 0xFF8040);
        Map<?, ?> fields = fields();
        assertEquals(1, fields.size());
        Object shape = fields.values().iterator().next();
        LyricGlyphAtlas.GlyphCell variant = glow('A', .8f, .5f, 0x4080FF);
        assertEquals(1, fields.size());
        assertSame(shape, fields.values().iterator().next());
        int[] expected = host.pixels.get(host.pixels.size() - 1);
        glow('B', .8f, .5f, 0x4080FF);
        assertEquals(2, fields.size());
        LyricGlyphAtlas.invalidateAll();
        LyricGlyphAtlas.GlyphCell fresh = glow('A', .8f, .5f, 0x4080FF);
        assertArrayEquals(expected, host.pixels.get(host.pixels.size() - 1));
        assertEquals(variant.w, fresh.w);
        assertEquals(variant.h, fresh.h);
        assertEquals(variant.advance, fresh.advance);
        assertEquals(variant.inkX, fresh.inkX);
        assertEquals(variant.scale, fresh.scale);
    }

    @Test
    void exactSpreadAndFontEpochKeepDifferentShapesSeparate() throws Exception {
        String before = LyricGlyphAtlas.bloomFieldKey('A', .5f);
        assertNotEquals(before, LyricGlyphAtlas.bloomFieldKey('A', .5005f));
        glow('A', .45f, .5f, 0xFFFFFF);
        Object previous = fields().values().iterator().next();
        glow('A', .8f, .5005f, 0x4080FF);
        assertEquals(2, fields().size());
        assertTrue(longField("bloomFieldFloats") > 0);
        LyricGlyphAtlas.onFontChanged();
        assertTrue(fields().isEmpty());
        assertEquals(0, longField("bloomFieldFloats"));
        assertNotEquals(before, LyricGlyphAtlas.bloomFieldKey('A', .5f));
        glow('A', .45f, .5f, 0xFFFFFF);
        assertNotSame(previous, fields().values().iterator().next());
    }

    @Test
    void failedUploadRetainsThePreparedShapeWithoutCommittingACell() throws Exception {
        host.reject = true;
        LyricGlyphAtlas.beginFrame();
        assertNull(LyricGlyphAtlas.getGlowCell('A', .45f, .5f, 0xFF8040,
                0x804020, TextGlow.GLOW_TIER_ULTRA));
        assertEquals(1, fields().size());
        Object shape = fields().values().iterator().next();
        host.reject = false;
        glow('A', .45f, .5f, 0xFF8040);
        assertSame(shape, fields().values().iterator().next());
        assertEquals(1, host.pixels.size());
    }

    @Test
    void lruAndFloatBudgetBoundMemoryEvenWhenKeysKeepChanging() throws Exception {
        float[] mask = new float[64 * 64];
        for (int y = 20; y < 40; y++) for (int x = 24; x < 32; x++) mask[y * 64 + x] = 1f;
        TextGlow.BloomGlowField field = TextGlow.prepareBloomGlowField(mask, 64, 64, 40f, .5f);
        assertNotNull(field);
        Class<?> type = Class.forName(LyricGlyphAtlas.class.getName() + "$BloomGlyph");
        Constructor<?> constructor = type.getDeclaredConstructor(TextGlow.BloomGlowField.class,
                int.class, int.class, float.class);
        constructor.setAccessible(true);
        Object glyph = constructor.newInstance(field, 8, 48, 30f);
        Method put = LyricGlyphAtlas.class.getDeclaredMethod("rememberBloomFieldLocked", String.class, type);
        put.setAccessible(true);
        int capacity = intField("BLOOM_FIELD_ENTRIES");
        for (int i = 0; i <= capacity; i++) put.invoke(null, "shape" + i, glyph);
        assertEquals(capacity, fields().size());
        assertFalse(fields().containsKey("shape0"));
        assertEquals(field.floatCount() * capacity, longField("bloomFieldFloats"));
        fields().get("shape1");
        put.invoke(null, "shape-next", glyph);
        assertTrue(fields().containsKey("shape1"));
        assertFalse(fields().containsKey("shape2"));

        LyricGlyphAtlas.invalidateAll();
        TextGlow.BloomGlowField larger = TextGlow.prepareBloomGlowField(new float[256 * 256],
                256, 256, 40f, .5f);
        assertNotNull(larger);
        Object big = constructor.newInstance(larger, 8, 48, 30f);
        for (int i = 0; i < capacity; i++) put.invoke(null, "large" + i, big);
        assertTrue(fields().size() < capacity);
        long budget = longField("BLOOM_FIELD_FLOAT_BUDGET");
        assertTrue(longField("bloomFieldFloats") <= budget);
        assertEquals(larger.floatCount() * fields().size(), longField("bloomFieldFloats"));
    }

    private static Map<?, ?> fields() throws Exception {
        return (Map<?, ?>) field("BLOOM_FIELDS").get(null);
    }

    private static Field field(String name) throws Exception {
        Field field = LyricGlyphAtlas.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static long longField(String name) throws Exception {
        return field(name).getLong(null);
    }

    private static int intField(String name) throws Exception {
        return field(name).getInt(null);
    }
}
