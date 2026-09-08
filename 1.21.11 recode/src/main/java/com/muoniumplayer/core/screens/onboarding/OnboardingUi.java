package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.Map;

/** Shared low-level drawing helpers for the onboarding module. */
public final class OnboardingUi {
    public static final int BACKDROP = RGBA.color(5, 9, 19, 255);
    public static final int GLASS = RGBA.color(12, 20, 38, 246);
    public static final int GLASS_2 = RGBA.color(17, 29, 53, 238);
    public static final int WHITE = RGBA.color(245, 248, 255, 255);
    public static final int SOFT = RGBA.color(181, 197, 224, 255);
    public static final int DIM = RGBA.color(112, 133, 165, 255);
    public static final int LINE = RGBA.color(63, 92, 145, 210);
    public static final int BLUE = RGBA.color(93, 145, 255, 255);
    public static final int CYAN = RGBA.color(103, 229, 221, 255);
    public static final int VIOLET = RGBA.color(203, 119, 255, 255);
    public static final int GREEN = RGBA.color(119, 228, 169, 255);

    private static final Map<CFontRenderer, GlyphInkMetrics> PARTY_EM_SQUARE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();
    private static float stepAlpha = 1.0f;

    private OnboardingUi() {
    }

    public static void beginStep(float alpha) {
        stepAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
    }

    public static void endStep() {
        stepAlpha = 1.0f;
    }

    private static int modulate(int color) {
        return SkijaGfx.mulAlpha(color, stepAlpha);
    }

    private static Color modulate(Color color) {
        if (color == null) return null;
        int argb = SkijaGfx.argb(color);
        int modulated = modulate(argb);
        return new Color((modulated >>> 16) & 255, (modulated >>> 8) & 255,
                modulated & 255, (modulated >>> 24) & 255);
    }

    public static void roundedRect(double x, double y, double width, double height,
                                   double radius, int argb) {
        SkijaShapes.roundedRect(x, y, width, height, radius, modulate(argb));
    }

    public static void roundedRect(double x, double y, double width, double height,
                                   double radius, int r, int g, int b, int a) {
        SkijaShapes.roundedRect(x, y, width, height, radius, RGBA.color(r, g, b, a));
    }

    public static void roundedOutline(double x, double y, double width, double height,
                                      double radius, double thickness, int argb) {
        SkijaShapes.roundedOutline(x, y, width, height, radius, thickness, modulate(argb));
    }

    public static void roundedOutline(double x, double y, double width, double height,
                                      double radius, double thickness, Color color) {
        SkijaShapes.roundedOutline(x, y, width, height, radius, thickness,
                modulate(color).getRGB());
    }

    public static void roundedOutlineGradient(double x, double y, double width, double height,
                                              double radius, double thickness,
                                              Color bottomLeft, Color topLeft,
                                              Color bottomRight, Color topRight) {
        SkijaShapes.roundedOutlineGradient(x, y, width, height, radius, thickness,
                modulate(bottomLeft), modulate(topLeft),
                modulate(bottomRight), modulate(topRight));
    }

    public static void drawText(CFontRenderer font, String text, double x, double y, int color) {
        if (font != null && text != null) {
            font.drawString(text, x, y, modulate(color));
        }
    }

    public static void drawIcon(String icon, CFontRenderer font, double x, double y, int color) {
        if (font != null && icon != null) {
            font.drawString(icon, x, y, modulate(color));
        }
    }

    private static CFontRenderer iconFont(double box) {
        if (box <= 28.0) return FontManager.partyIcon24;
        if (box <= 36.0) return FontManager.partyIcon32;
        if (box <= 48.0) return FontManager.partyIcon40;
        return FontManager.partyIcon56;
    }

    private static GlyphInkMetrics partyEmSquare(CFontRenderer font) {
        if (font == null) return null;
        synchronized (PARTY_EM_SQUARE) {
            if (PARTY_EM_SQUARE.containsKey(font)) return PARTY_EM_SQUARE.get(font);
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, PartyIcons.FRAME.charAt(0));
        synchronized (PARTY_EM_SQUARE) {
            PARTY_EM_SQUARE.put(font, measured);
        }
        return measured;
    }

    public static void drawPartyIcon(String icon, double centerX, double centerY,
                                     double box, int color) {
        if (icon == null || icon.isEmpty()) return;
        CFontRenderer font = iconFont(box);
        GlyphInkMetrics square = partyEmSquare(font);
        if (square == null) {
            drawIcon(icon, font, centerX - font.getStringWidthD(icon) * .5,
                    centerY - font.getHeight() * .5, color);
            return;
        }
        drawIcon(icon, font, square.alignCenterX(centerX), square.alignCenterY(centerY), color);
    }

    public static void drawBrandIcon(String icon, CFontRenderer font,
                                     double centerX, double centerY, int color) {
        if (icon == null || icon.isEmpty() || font == null) return;
        GlyphInkMetrics ink = GlyphInkMetrics.of(font, icon.charAt(0));
        if (ink == null) {
            drawIcon(icon, font, centerX - font.getStringWidthD(icon) * .5,
                    centerY - font.getHeight() * .5, color);
            return;
        }
        drawIcon(icon, font, ink.alignCenterX(centerX), ink.alignCenterY(centerY), color);
    }

    public static boolean inside(double mouseX, double mouseY,
                                 double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public static int colorWithAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    public static Color awtColorWithAlpha(int color, int alpha) {
        return new Color((color >>> 16) & 255, (color >>> 8) & 255, color & 255,
                Math.max(0, Math.min(255, alpha)));
    }

    public static void drawPlatformIcon(MusicPlatform platform,
                                         double centerX, double centerY, double box, int color) {
        if (platform == MusicPlatform.NETEASE) {
            drawBrandIcon(MusicBrandIcons.NETEASE_CLOUD_MUSIC, FontManager.musicBrand18,
                    centerX, centerY, color);
        } else if (platform == MusicPlatform.QQ) {
            drawBrandIcon(MusicBrandIcons.QQ_MUSIC, FontManager.qqMusicIcon20,
                    centerX, centerY, color);
        } else if (platform == MusicPlatform.KUGOU) {
            drawBrandIcon(MusicBrandIcons.KUGOU_MONOGRAM, FontManager.musicBrand18,
                    centerX, centerY, color);
        } else {
            drawPartyIcon(PartyIcons.RADIO, centerX, centerY, box, color);
        }
    }

    public static String stepIcon(OnboardingStep step) {
        switch (step) {
            case ACCOUNT: return PartyIcons.PERSON;
            case SOURCE: return PartyIcons.RADIO;
            case APPEARANCE: return PartyIcons.TEXT_FORMAT;
            case FINISH: return PartyIcons.CHECK;
            default: return PartyIcons.NOTE;
        }
    }
}
