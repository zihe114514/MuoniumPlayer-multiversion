package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.Objects;

/**
 * Material 3 visual foundation for MuoniumPlayer-owned music controls.
 *
 * <p>This is a token and primitive layer, not a page shell or a dependency on OneConfig. All
 * colors are opaque ARGB. Coordinates and sizes use the caller's logical UI units.
 *
 * <p>Stable use: choose {@link Palette} with {@link #palette(Mode)}, then pass the existing
 * {@link Painter} to {@link #surface}, {@link #button}, {@link #navigationItem}, or
 * {@link #progress}. The painter is borrowed and is never retained or closed here.
 */
public final class MaterialDesign3 {
    private MaterialDesign3() {}

    public enum Mode { LIGHT, DARK }

    public enum ButtonStyle { FILLED, TONAL, OUTLINED, TEXT }

    public enum Status { PLAYING, PAUSED, IDLE, WARNING, ERROR }

    /** Semantic roles follow Material 3; secondary and tertiary are tuned for music surfaces. */
    public record Palette(
            int primary, int onPrimary, int primaryContainer, int onPrimaryContainer,
            int secondary, int onSecondary, int secondaryContainer, int onSecondaryContainer,
            int tertiary, int onTertiary, int tertiaryContainer, int onTertiaryContainer,
            int error, int onError, int errorContainer, int onErrorContainer,
            int success, int onSuccess, int successContainer, int onSuccessContainer,
            int warning, int onWarning, int warningContainer, int onWarningContainer,
            int background, int onBackground,
            int surface, int onSurface, int surfaceVariant, int onSurfaceVariant,
            int surfaceContainerLowest, int surfaceContainerLow, int surfaceContainer,
            int surfaceContainerHigh, int surfaceContainerHighest,
            int outline, int outlineVariant, int inverseSurface, int inverseOnSurface,
            int inversePrimary, int scrim, int shadow) {

        public int surfaceAtElevation(int level) {
            int clamped = (int) Math.round(Geometry.clamp(level, 0, 5));
            return switch (clamped) {
                case 0 -> surface;
                case 1 -> surfaceContainerLow;
                case 2, 3 -> surfaceContainer;
                case 4 -> surfaceContainerHigh;
                default -> surfaceContainerHighest;
            };
        }

        public int status(Status status) {
            return switch (status) {
                case PLAYING -> success;
                case PAUSED, IDLE -> onSurfaceVariant;
                case WARNING -> warning;
                case ERROR -> error;
            };
        }

        public int statusContainer(Status status) {
            return switch (status) {
                case PLAYING -> successContainer;
                case PAUSED, IDLE -> surfaceContainerHigh;
                case WARNING -> warningContainer;
                case ERROR -> errorContainer;
            };
        }
    }

    /** Material type scale sizes and line heights in logical UI units. */
    public record TypeScale(
            double displayLarge, double displayLargeLine,
            double headlineLarge, double headlineLargeLine,
            double headlineMedium, double headlineMediumLine,
            double titleLarge, double titleLargeLine,
            double titleMedium, double titleMediumLine,
            double bodyLarge, double bodyLargeLine,
            double bodyMedium, double bodyMediumLine,
            double bodySmall, double bodySmallLine,
            double labelLarge, double labelLargeLine,
            double labelMedium, double labelMediumLine,
            double labelSmall, double labelSmallLine) {}

    public static final class Shape {
        public static final double NONE = 0;
        public static final double EXTRA_SMALL = 4;
        public static final double SMALL = 8;
        public static final double MEDIUM = 12;
        public static final double LARGE = 16;
        public static final double EXTRA_LARGE = 28;
        public static final double FULL = 999;
        private Shape() {}
    }

    public static final class Space {
        public static final double XS = 4;
        public static final double SM = 8;
        public static final double MD = 12;
        public static final double LG = 16;
        public static final double XL = 20;
        public static final double XXL = 24;
        public static final double XXXL = 32;
        public static final double SECTION = 40;
        public static final double PAGE = 48;
        private Space() {}
    }

    public static final class Control {
        public static final double COMPACT_HEIGHT = 32;
        public static final double STANDARD_HEIGHT = 40;
        public static final double LARGE_HEIGHT = 56;
        public static final double LIST_ROW_HEIGHT = 56;
        public static final double SLIDER_TRACK = 4;
        public static final double FOCUS_WIDTH = 2;
        private Control() {}
    }

    public static final TypeScale TYPE = new TypeScale(
            57, 64, 32, 40, 28, 36, 22, 28, 16, 24, 16, 24,
            14, 20, 12, 16, 14, 20, 12, 16, 11, 16);

    private static final Palette LIGHT = new Palette(
            0xFF465D91, 0xFFFFFFFF, 0xFFD9E2FF, 0xFF001A41,
            0xFF416A66, 0xFFFFFFFF, 0xFFC4EDE7, 0xFF00201D,
            0xFF81534A, 0xFFFFFFFF, 0xFFFFDAD1, 0xFF33100A,
            0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002,
            0xFF386A20, 0xFFFFFFFF, 0xFFB7F397, 0xFF062100,
            0xFF795900, 0xFFFFFFFF, 0xFFFFE08A, 0xFF251A00,
            0xFFF8F9FF, 0xFF191B20, 0xFFF8F9FF, 0xFF191B20,
            0xFFE1E2EC, 0xFF44474F, 0xFFFFFFFF, 0xFFF2F3FA,
            0xFFECEEF5, 0xFFE6E8EF, 0xFFE1E2EC,
            0xFF747780, 0xFFC4C6D0, 0xFF2E3036, 0xFFF0F0F7,
            0xFFB1C5FF, 0xFF000000, 0xFF000000);

    private static final Palette DARK = new Palette(
            0xFFB1C5FF, 0xFF182F60, 0xFF2E4678, 0xFFD9E2FF,
            0xFFA8D1CA, 0xFF103733, 0xFF284E49, 0xFFC4EDE7,
            0xFFF4B8A9, 0xFF4B261F, 0xFF663B33, 0xFFFFDAD1,
            0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
            0xFF9DD67D, 0xFF173800, 0xFF285112, 0xFFB7F397, 0xFFE9C36A,
            0xFF3E2E00, 0xFF5A4300, 0xFFFFE08A,
            0xFF111318, 0xFFE3E2E9, 0xFF111318, 0xFFE3E2E9,
            0xFF42464F, 0xFFC4C6D0, 0xFF0C0E13, 0xFF191B20,
            0xFF1D2026, 0xFF272A30, 0xFF32353C,
            0xFF8E9099, 0xFF42464F, 0xFFE3E2E9, 0xFF2E3036,
            0xFF465D91, 0xFF000000, 0xFF000000);

    public static Palette palette(Mode mode) {
        return Objects.requireNonNull(mode, "mode") == Mode.LIGHT ? LIGHT : DARK;
    }

    public static Palette light() { return LIGHT; }

    public static Palette dark() { return DARK; }

    /** Draw a flat or tonal-elevated container using Material 3 surface roles. */
    public static void surface(Painter painter, Rect bounds, Palette palette, int elevation, double radius) {
        Objects.requireNonNull(painter, "painter");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(palette, "palette");
        int level = (int) Math.round(Geometry.clamp(elevation, 0, 5));
        if (level > 0) {
            double lift = level * 1.5;
            painter.shadow(bounds, radius, 5 + level * 2, 0, 0, lift,
                    withAlpha(palette.shadow(), Math.min(72, 18 + level * 8)));
        }
        painter.rect(bounds, Math.max(0, radius), palette.surfaceAtElevation(level));
    }

    public static void card(Painter painter, Rect bounds, Palette palette, boolean selected) {
        surface(painter, bounds, palette, selected ? 2 : 0, Shape.LARGE);
        painter.outline(bounds, Shape.LARGE, 1, selected ? palette.primary() : palette.outlineVariant());
    }

    public static void divider(Painter painter, double x1, double y, double x2, Palette palette) {
        painter.line(x1, y, x2, y, 1, palette.outlineVariant());
    }

    public static void button(
            Painter painter, Rect bounds, String label, Palette palette, ButtonStyle style,
            boolean enabled, boolean focused) {
        Objects.requireNonNull(painter, "painter");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(style, "style");

        int fill = switch (style) {
            case FILLED -> palette.primary();
            case TONAL -> palette.secondaryContainer();
            case OUTLINED, TEXT -> 0x00000000;
        };
        int ink = switch (style) {
            case FILLED -> palette.onPrimary();
            case TONAL -> palette.onSecondaryContainer();
            case OUTLINED, TEXT -> palette.primary();
        };
        if (!enabled) {
            fill = style == ButtonStyle.FILLED ? withAlpha(palette.onSurface(), 31) : 0x00000000;
            ink = withAlpha(palette.onSurface(), 97);
        }
        double radius = Shape.FULL;
        if ((fill >>> 24) != 0) painter.rect(bounds, radius, fill);
        if (style == ButtonStyle.OUTLINED) {
            painter.outline(bounds, radius, 1, enabled ? palette.outline() : withAlpha(palette.onSurface(), 31));
        }
        if (focused) painter.outline(bounds.inset(2), Math.max(0, radius - 2), Control.FOCUS_WIDTH, palette.primary());
        double size = TYPE.labelLarge();
        double x = bounds.x() + Math.max(Space.LG, (bounds.w() - painter.measure(label, size, true)) / 2);
        painter.text(label, x, bounds.y() + (bounds.h() - TYPE.labelLargeLine()) / 2, size, true, ink);
    }

    /** A compact selection row for a player/library navigation rail. */
    public static void navigationItem(
            Painter painter, Rect bounds, String label, Palette palette, boolean selected, boolean enabled) {
        int ink = enabled ? (selected ? palette.onSecondaryContainer() : palette.onSurfaceVariant())
                : withAlpha(palette.onSurface(), 97);
        if (selected) painter.rect(bounds, Shape.FULL, palette.secondaryContainer());
        painter.text(label, bounds.x() + Space.LG, bounds.y() + (bounds.h() - TYPE.labelLargeLine()) / 2,
                TYPE.labelLarge(), selected, ink);
    }

    /** Draws a status label and dot with semantic status colors. */
    public static void statusBadge(Painter painter, Rect bounds, String label, Palette palette, Status status) {
        painter.rect(bounds, Shape.SMALL, palette.statusContainer(status));
        double diameter = 8;
        double dotX = bounds.x() + Space.SM;
        double dotY = bounds.y() + (bounds.h() - diameter) / 2;
        painter.rect(new Rect(dotX, dotY, diameter, diameter), Shape.FULL, palette.status(status));
        painter.text(label, dotX + diameter + Space.SM,
                bounds.y() + (bounds.h() - TYPE.labelMediumLine()) / 2,
                TYPE.labelMedium(), true, palette.onSurface());
    }

    public static void progress(Painter painter, Rect bounds, double fraction, Palette palette) {
        double height = Math.min(Control.SLIDER_TRACK, Math.max(0, bounds.h()));
        double y = bounds.y() + (bounds.h() - height) / 2;
        Rect track = new Rect(bounds.x(), y, Math.max(0, bounds.w()), height);
        painter.rect(track, height / 2, palette.surfaceContainerHighest());
        double filled = track.w() * Geometry.clamp(fraction, 0, 1);
        if (filled > 0) painter.rect(new Rect(track.x(), track.y(), filled, track.h()), height / 2, palette.primary());
    }

    /** Draws a small live-level motif for playback or audio activity indicators. */
    public static void equalizer(Painter painter, Rect bounds, double[] levels, Palette palette, boolean active) {
        Objects.requireNonNull(levels, "levels");
        if (levels.length == 0 || bounds.w() <= 0 || bounds.h() <= 0) return;
        double gap = Math.min(3, bounds.w() / (levels.length * 2.0));
        double barWidth = (bounds.w() - gap * (levels.length - 1)) / levels.length;
        for (int i = 0; i < levels.length; i++) {
            double fraction = Geometry.clamp(levels[i], 0, 1);
            double height = Math.max(2, bounds.h() * fraction);
            double x = bounds.x() + i * (barWidth + gap);
            double y = bounds.bottom() - height;
            int color = active ? (i % 3 == 2 ? palette.tertiary() : palette.primary()) : palette.outlineVariant();
            painter.rect(new Rect(x, y, Math.max(0, barWidth), height), Math.min(barWidth, Shape.EXTRA_SMALL), color);
        }
    }

    private static int withAlpha(int color, int alpha) {
        int clamped = (int) Geometry.clamp(alpha, 0, 255);
        return (color & 0x00FFFFFF) | (clamped << 24);
    }
}
