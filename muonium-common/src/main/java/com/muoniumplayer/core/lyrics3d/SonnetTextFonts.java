package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.TextGlow;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import java.util.LinkedHashMap;
import java.util.Map;

/** Two owned font faces at one vector size; scene/glyph caches never own native font objects. */
final class SonnetTextFonts implements AutoCloseable {
    private static final float VECTOR_SIZE = 64;
    private final CFontRenderer boldSource, regularSource;
    private final long boldRevision, regularRevision;
    private final CFontRenderer bold, regular;
    private final Map<String, Geometry> geometry = new LinkedHashMap<String, Geometry>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Geometry> entry) { return size() > 1024; }
    };

    static final class Geometry {
        final float width, originX, originY;
        Geometry(float width, float originX, float originY) {
            this.width = width; this.originX = originX; this.originY = originY;
        }
    }

    SonnetTextFonts(CFontRenderer boldSource, CFontRenderer regularSource) {
        this.boldSource = boldSource;
        this.regularSource = regularSource != null ? regularSource : boldSource;
        boldRevision = boldSource.metricsRevision();
        regularRevision = this.regularSource.metricsRevision();
        CFontRenderer ownedBold = boldSource.deriveAtSize(VECTOR_SIZE);
        try {
            regular = this.regularSource.deriveAtSize(VECTOR_SIZE);
        } catch (Throwable failure) {
            ownedBold.close();
            throw failure;
        }
        bold = ownedBold;
    }

    static SonnetTextFonts current() {
        CFontRenderer source = FontManager.pf25bold;
        return source == null || source.fontHeight <= 0 ? null : new SonnetTextFonts(source, FontManager.pf25);
    }

    boolean matchesCurrent() {
        CFontRenderer regularNow = FontManager.pf25 != null ? FontManager.pf25 : FontManager.pf25bold;
        return boldSource == FontManager.pf25bold && regularSource == regularNow
                && boldRevision == boldSource.metricsRevision()
                && regularRevision == regularSource.metricsRevision();
    }

    float width(String text, float fontSize, SonnetTypography.Role role) {
        return (float) font(role).getStringWidthD(text) * fontSize / VECTOR_SIZE;
    }

    Geometry geometry(String text, SonnetTypography.Role role) {
        boolean thin = role == SonnetTypography.Role.DECORATION;
        String key = (thin ? "r|" : "b|") + text;
        Geometry result = geometry.get(key);
        if (result != null) return result;
        CFontRenderer font = font(role);
        float advance = (float) font.getStringWidthD(text);
        // Keep one baseline for a font face: centering each ink box would lift g/j and punctuation.
        result = new Geometry(advance, -advance / 2, 2f - font.lineBox() * 0.5f);
        font.prepareText(text);
        geometry.put(key, result);
        return result;
    }

    void draw(Canvas canvas, String text, SonnetTypography.Role role, Geometry glyph,
              float fontSize, Paint paint) {
        float scale = fontSize / VECTOR_SIZE;
        canvas.save();
        try {
            canvas.scale(scale, scale);
            font(role).drawPlainTextWithPaintOn(canvas, text, glyph.originX, glyph.originY, paint);
        } finally {
            canvas.restore();
        }
    }

    void glow(Canvas canvas, String text, SonnetTypography.Role role, Geometry glyph,
              float fontSize, int glowRgb, int inkRgb, double strength, float spread, int tier) {
        if (strength <= 0.004) return;
        float scale = fontSize / VECTOR_SIZE;
        canvas.save();
        try {
            canvas.scale(scale, scale);
            TextGlow.drawGlowOn(canvas, font(role), text, glyph.originX, glyph.originY,
                    glowRgb, inkRgb, strength, spread, tier);
        } finally {
            canvas.restore();
        }
    }

    private CFontRenderer font(SonnetTypography.Role role) {
        return role == SonnetTypography.Role.DECORATION ? regular : bold;
    }

    @Override public void close() {
        geometry.clear();
        bold.close();
        regular.close();
    }
}