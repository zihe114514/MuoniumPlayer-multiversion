package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.render.skija.SkijaShapes;

/** Shared dark semantic colors and small Skija drawing helpers for Muonium HUD screens. */
public final class MuoniumDesignSystem {

    private static final int FALLBACK_ACCENT = 0xFF8DA7FF;
    private static final int BASE_SURFACE = 0xFF101216;
    private static final int BASE_CONTAINER = 0xFF191C22;
    private static final int BASE_CONTAINER_HIGH = 0xFF22262E;
    private static final int BASE_TEXT = 0xFFE7E2EA;
    private static final int BASE_TEXT_VARIANT = 0xFFB9B6C1;
    private static final int BASE_OUTLINE = 0xFF777680;

    private MuoniumDesignSystem() {
    }

    public static int surface() {
        return palette().surface;
    }

    public static int surfaceContainer() {
        return palette().surfaceContainer;
    }

    public static int surfaceContainerHigh() {
        return palette().surfaceContainerHigh;
    }

    public static int onSurface() {
        return opaque(BASE_TEXT);
    }

    public static int onSurfaceVariant() {
        return opaque(BASE_TEXT_VARIANT);
    }

    public static int outline() {
        return palette().outline;
    }

    public static int accent() {
        return palette().accent;
    }

    public static int onAccent() {
        return contrastingText(palette().accent);
    }

    public static int accentContainer() {
        return palette().accentContainer;
    }

    public static int onAccentContainer() {
        return opaque(BASE_TEXT);
    }

    public static int stateLayer(int baseArgb, double opacity) {
        Palette palette = palette();
        return blend(opaque(baseArgb), palette.accent, clamp(opacity));
    }

    public static void drawSurface(double x, double y, double width, double height, double radius) {
        SkijaShapes.roundedRect(x, y, width, height, radius, surface());
    }

    public static void drawStateLayer(double x, double y, double width, double height,
                                      double radius, int baseArgb, double opacity) {
        SkijaShapes.roundedRect(x, y, width, height, radius, stateLayer(baseArgb, opacity));
    }

    public static void drawDivider(double x, double y, double width, double height) {
        SkijaShapes.rect(x, y, x + width, y + height, outline());
    }

    public static void drawChip(double x, double y, double width, double height, double radius,
                                boolean selected) {
        Palette palette = palette();
        SkijaShapes.roundedRect(x, y, width, height, radius,
                selected ? palette.accentContainer : palette.surfaceContainerHigh);
        drawOutline(x, y, width, height, radius, 1.0,
                selected ? palette.accent : palette.outline);
    }

    public static void drawControl(double x, double y, double width, double height, double radius,
                                   boolean hovered, boolean focused) {
        Palette palette = palette();
        int fill = hovered ? blend(palette.surfaceContainerHigh, palette.accent, 0.12)
                : palette.surfaceContainerHigh;
        SkijaShapes.roundedRect(x, y, width, height, radius, fill);
        double thickness = focused ? 2.0 : 1.0;
        drawOutline(x, y, width, height, radius, thickness,
                focused ? palette.accent : palette.outline);
    }

    private static void drawOutline(double x, double y, double width, double height,
                                    double radius, double thickness, int color) {
        SkijaShapes.roundedOutline(x, y, width, height, radius + 2.0 - thickness,
                thickness, color);
    }

    private static Palette palette() {
        int accent = currentAccent();
        int surface = blend(BASE_SURFACE, accent, 0.035);
        int container = blend(BASE_CONTAINER, accent, 0.055);
        int containerHigh = blend(BASE_CONTAINER_HIGH, accent, 0.075);
        return new Palette(accent, surface, container, containerHigh,
                blend(BASE_OUTLINE, accent, 0.22), blend(container, accent, 0.30));
    }

    private static int currentAccent() {
        try {
            return opaque(NCMTheme.getAccentColor());
        } catch (RuntimeException ignored) {
            return FALLBACK_ACCENT;
        }
    }

    private static int contrastingText(int background) {
        double luminance = (0.2126 * red(background) + 0.7152 * green(background)
                + 0.0722 * blue(background)) / 255.0;
        return luminance > 0.55 ? 0xFF101116 : BASE_TEXT;
    }

    private static int blend(int first, int second, double amount) {
        double t = clamp(amount);
        int r = (int) Math.round(red(first) + (red(second) - red(first)) * t);
        int g = (int) Math.round(green(first) + (green(second) - green(first)) * t);
        int b = (int) Math.round(blue(first) + (blue(second) - blue(first)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int opaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static int red(int color) {
        return (color >>> 16) & 0xFF;
    }

    private static int green(int color) {
        return (color >>> 8) & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class Palette {
        private final int accent;
        private final int surface;
        private final int surfaceContainer;
        private final int surfaceContainerHigh;
        private final int outline;
        private final int accentContainer;

        private Palette(int accent, int surface, int surfaceContainer, int surfaceContainerHigh,
                        int outline, int accentContainer) {
            this.accent = accent;
            this.surface = surface;
            this.surfaceContainer = surfaceContainer;
            this.surfaceContainerHigh = surfaceContainerHigh;
            this.outline = outline;
            this.accentContainer = accentContainer;
        }
    }
}
