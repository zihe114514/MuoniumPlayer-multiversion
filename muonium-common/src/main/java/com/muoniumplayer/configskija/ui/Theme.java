package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.model.UiPreferences;

import java.util.Map;
import java.util.WeakHashMap;

/** Built-in palette, shape and transition contract. All dimensions are Shell dp. */
public record Theme(
        String id,
        String title,
        int page,
        int sidebar,
        int chip,
        int card,
        int component,
        int popup,
        int border,
        int text,
        int secondary,
        int accentText,
        int thumb,
        int favorite,
        boolean rounded,
        int controlTrack,
        int shadow,
        boolean shadowEnabled,
        double cornerScale) {
    public static final int DEFAULT_ACCENT = 0xFF2B4BFF;
    public static final double TRANSITION_SECONDS = .350;
    public static final Theme[] BUILTIN = {
        new Theme(
                "polyglass-dark",
                "PolyGlass Dark",
                0xC411171C,
                0xB3151C22,
                0xB2232D32,
                0x59232D32,
                0xFF1A2229,
                0xFF1A2229,
                0x1AFFFFFF,
                0xFFD5DBFF,
                0xFF757883,
                0xFFFFFFFF,
                0xFFFFFFFF,
                0xFFFFD700,
                true),
        new Theme(
                "polyglass-light",
                "PolyGlass Light",
                0xB0D0D7F3,
                0xB2D0D7F3,
                0x59EBF2FF,
                0x59EBF2FF,
                0xB2EBF2FF,
                0xFFD0D7F3,
                0x4DA3A3A3,
                0xFF000000,
                0xFF4E5661,
                0xFFD5DBFF,
                0xFF1A2229,
                0xFFB57D00,
                true),
        new Theme(
                "minecraft-dark",
                "Minecraft Dark",
                0xFF11171C,
                0xFF11171C,
                0xB2232D32,
                0x59232D32,
                0xFF1A2229,
                0xFF1A2229,
                0x1AFFFFFF,
                0xFFD5DBFF,
                0xFF757883,
                0xFFFFFFFF,
                0xFFFFFFFF,
                0xFFFFD700,
                false),
        new Theme(
                "minecraft-light",
                "Minecraft Light",
                0xFFE8EDFF,
                0xFFE8EDFF,
                0x59EBF2FF,
                0x59EBF2FF,
                0xB2EBF2FF,
                0xFFD0D7F3,
                0x4DA3A3A3,
                0xFF000000,
                0xFF4E5661,
                0xFFD5DBFF,
                0xFF1A2229,
                0xFFB57D00,
                false)
    };

    /** Retains the initial public constructor used by the shell and preview tools. */
    public Theme(
            String id,
            String title,
            int page,
            int sidebar,
            int chip,
            int card,
            int component,
            int popup,
            int border,
            int text,
            int secondary,
            int accentText,
            int thumb,
            int favorite,
            boolean rounded) {
        this(
                id,
                title,
                page,
                sidebar,
                chip,
                card,
                component,
                popup,
                border,
                text,
                secondary,
                accentText,
                thumb,
                favorite,
                rounded,
                0xFF74777F,
                0xFF000000,
                rounded,
                rounded ? 1 : 0);
    }

    public static Theme byId(String id) {
        for (Theme theme : BUILTIN) if (theme.id.equals(id) || theme.title.equals(id)) return theme;
        return BUILTIN[0];
    }

    public static Theme active(UiPreferences prefs) {
        return byId(prefs.get("activeTheme", prefs.get("theme", "PolyGlass Dark")));
    }

    public double radius(double radius) {
        return radius * cornerScale;
    }

    public double backgroundRadius() {
        return radius(20);
    }

    public double navigationRadius() {
        return radius(8);
    }

    public double cardRadius() {
        return radius(16);
    }

    public double checkboxRadius() {
        return radius(6);
    }

    public double buttonRadius() {
        return radius(8);
    }

    public double popupRadius() {
        return radius(16);
    }

    public double circleRadius(double diameter) {
        return rounded ? diameter / 2 : 0;
    }

    public double modIconRadius() {
        return rounded ? 4 : 0;
    }

    public double scrollBarRadius() {
        return rounded ? 8 : 0;
    }

    public boolean minecraft() {
        return id.startsWith("minecraft-");
    }

    public String previewPath() {
        return "images/" + id + ".svg";
    }

    public String logoPath() {
        return "brand/"
                + switch (id) {
                    case "polyglass-light" -> "oneconfig-light.svg";
                    case "minecraft-dark" -> "oneconfig-mc.svg";
                    case "minecraft-light" -> "oneconfig-mc-light.svg";
                    default -> "oneconfig.svg";
                };
    }

    public String iconPath(String name) {
        return "ico/" + (minecraft() ? "minecraft/" : "") + name + ".svg";
    }

    public String fontFamily() {
        return minecraft() ? "minecraft" : "host";
    }

    public String soundFamily() {
        return minecraft() ? "minecraft" : "polyglass";
    }

    public static int alpha(int color, double alpha) {
        return ((int) (Geometry.clamp(alpha, 0, 1) * 255) << 24) | (color & 0xffffff);
    }

    public static int opacity(int color, double percent) {
        return (byteOf(percent / 100) << 24) | (color & 0xffffff);
    }

    public static int multiplyAlpha(int color, double amount) {
        return alpha(color, ((color >>> 24) & 255) / 255.0 * amount);
    }

    /** Existing integer sRGB interpolation remains unchanged for its callers. */
    public static int mix(int a, int b, double t) {
        t = Geometry.clamp(t, 0, 1);
        int out = 0;
        for (int s = 0; s < 32; s += 8)
            out |= ((int) (((a >>> s) & 255) * (1 - t) + ((b >>> s) & 255) * t)) << s;
        return out;
    }

    public static int color(String value, int fallback) {
        if (value == null) return fallback;
        try {
            String v = value.trim();
            if (v.startsWith("#")) {
                v = v.substring(1);
                if (v.length() != 6 && v.length() != 8) return fallback;
                return (int) Long.parseLong(v, 16) | (v.length() == 6 ? 0xFF000000 : 0);
            }
            if (v.startsWith("0x") || v.startsWith("0X"))
                return (int) Long.parseLong(v.substring(2), 16);
            return (int) Long.parseLong(v);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    public static int accent(UiPreferences p, double monotonicSeconds) {
        int color =
                color(p.get("accentColor", p.get("accent", hex(DEFAULT_ACCENT))), DEFAULT_ACCENT);
        if (!p.flag("accentColor.chroma", false)) return color;
        double[] hsv = toHsv(color);
        return hsv(
                hsv[0]
                        + monotonicSeconds
                                * 36
                                * Math.max(0, p.number("accentColor.chromaSpeed", 1)),
                hsv[1],
                hsv[2],
                ((color >>> 24) & 255) / 255.0);
    }

    public static int accentEnd(UiPreferences p, double time) {
        return p.flag("accentColor.gradientEnabled", false) && !p.flag("accentColor.chroma", false)
                ? color(
                        p.get(
                                "accentColor.gradientArgb",
                                p.get("accentColor", hex(DEFAULT_ACCENT))),
                        DEFAULT_ACCENT)
                : accent(p, time);
    }

    public static double[] toHsv(int color) {
        double r = ((color >>> 16) & 255) / 255.0,
                g = ((color >>> 8) & 255) / 255.0,
                b = (color & 255) / 255.0;
        double hi = Math.max(r, Math.max(g, b)),
                lo = Math.min(r, Math.min(g, b)),
                d = hi - lo,
                h =
                        d == 0
                                ? 0
                                : hi == r
                                        ? (g - b) / d
                                        : hi == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return new double[] {((h * 60) % 360 + 360) % 360, hi == 0 ? 0 : d / hi, hi};
    }

    public static int hsv(double hue, double saturation, double brightness, double alpha) {
        double h = ((hue % 360 + 360) % 360) / 60,
                c = Geometry.clamp(brightness, 0, 1) * Geometry.clamp(saturation, 0, 1),
                x = c * (1 - Math.abs(h % 2 - 1)),
                m = Geometry.clamp(brightness, 0, 1) - c;
        double r = h < 1 ? c : h < 2 ? x : h < 4 ? 0 : h < 5 ? x : c,
                g = h < 1 ? x : h < 3 ? c : h < 4 ? x : 0,
                b = h < 2 ? 0 : h < 3 ? x : h < 5 ? c : x;
        return byteOf(alpha) << 24 | byteOf(r + m) << 16 | byteOf(g + m) << 8 | byteOf(b + m);
    }

    private static int byteOf(double x) {
        return (int) Math.round(Geometry.clamp(x, 0, 1) * 255);
    }

    private static double linear(double v) {
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    private static double srgb(double v) {
        return v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055;
    }

    private static double[] lab(int c) {
        double r = linear(((c >>> 16) & 255) / 255.0),
                g = linear(((c >>> 8) & 255) / 255.0),
                b = linear((c & 255) / 255.0);
        double l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b),
                m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b),
                s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
        return new double[] {
            .2104542553 * l + .7936177850 * m - .0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
            .0259040371 * l + .7827717662 * m - .8086757660 * s,
            ((c >>> 24) & 255) / 255.0
        };
    }

    private static int fromLab(double[] v) {
        double l = v[0] + .3963377774 * v[1] + .2158037573 * v[2],
                m = v[0] - .1055613458 * v[1] - .0638541728 * v[2],
                s = v[0] - .0894841775 * v[1] - 1.291485548 * v[2];
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        return byteOf(v[3]) << 24
                | byteOf(srgb(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s)) << 16
                | byteOf(srgb(-1.2684380046 * l + 2.6097574011 * m - .3413193965 * s)) << 8
                | byteOf(srgb(-.0041960863 * l - .7034186147 * m + 1.707614701 * s));
    }

    public static double[] oklab(int color) {
        return lab(color);
    }

    public static int fromOklab(double l, double a, double b, double alpha) {
        return fromLab(
                new double[] {
                    Geometry.clamp(l, 0, 1),
                    Geometry.clamp(a, -.5, .5),
                    Geometry.clamp(b, -.5, .5),
                    Geometry.clamp(alpha, 0, 1)
                });
    }

    /** Compose color animations use unpremultiplied Oklab plus linear alpha. */
    public static int mixOklab(int from, int to, double fraction) {
        double t = Geometry.clamp(fraction, 0, 1);
        if (t == 0) return from;
        if (t == 1) return to;
        double[] a = lab(from), b = lab(to);
        for (int i = 0; i < 4; i++) a[i] += (b[i] - a[i]) * t;
        return fromLab(a);
    }

    public static Theme interpolate(Theme a, Theme b, double t) {
        t = Geometry.clamp(t, 0, 1);
        return new Theme(
                b.id,
                b.title,
                mixOklab(a.page, b.page, t),
                mixOklab(a.sidebar, b.sidebar, t),
                mixOklab(a.chip, b.chip, t),
                mixOklab(a.card, b.card, t),
                mixOklab(a.component, b.component, t),
                mixOklab(a.popup, b.popup, t),
                mixOklab(a.border, b.border, t),
                mixOklab(a.text, b.text, t),
                mixOklab(a.secondary, b.secondary, t),
                mixOklab(a.accentText, b.accentText, t),
                mixOklab(a.thumb, b.thumb, t),
                mixOklab(a.favorite, b.favorite, t),
                b.rounded,
                mixOklab(a.controlTrack, b.controlTrack, t),
                mixOklab(a.shadow, b.shadow, t),
                b.shadowEnabled,
                a.cornerScale + (b.cornerScale - a.cornerScale) * t);
    }

    private static final Map<PageContext, Motion> THEME_TRACKS = new WeakHashMap<>();

    private static synchronized Motion tracks(PageContext context) {
        return THEME_TRACKS.computeIfAbsent(context, ignored -> new Motion());
    }

    private static int animatedColor(PageContext ctx, Motion motion, String key, int target) {
        double[] v = lab(target);
        for (int i = 0; i < v.length; i++)
            v[i] =
                    motion.value(
                            "appearance.theme." + key + "." + i,
                            v[i],
                            TRANSITION_SECONDS,
                            Motion.Easing.FAST_OUT_SLOW_IN,
                            ctx.time());
        return fromLab(v);
    }

    /** The shell and pages may call this with the same context; tracks are shared and retarget continuously. */
    public static Theme transition(PageContext ctx) {
        Theme t = active(ctx.preferences());
        Motion motion = tracks(ctx);
        return new Theme(
                t.id,
                t.title,
                animatedColor(ctx, motion, "page", t.page),
                animatedColor(ctx, motion, "sidebar", t.sidebar),
                animatedColor(ctx, motion, "chip", t.chip),
                animatedColor(ctx, motion, "card", t.card),
                animatedColor(ctx, motion, "component", t.component),
                animatedColor(ctx, motion, "popup", t.popup),
                animatedColor(ctx, motion, "border", t.border),
                animatedColor(ctx, motion, "text", t.text),
                animatedColor(ctx, motion, "secondary", t.secondary),
                animatedColor(ctx, motion, "accentText", t.accentText),
                animatedColor(ctx, motion, "thumb", t.thumb),
                animatedColor(ctx, motion, "favorite", t.favorite),
                t.rounded,
                animatedColor(ctx, motion, "track", t.controlTrack),
                animatedColor(ctx, motion, "shadow", t.shadow),
                t.shadowEnabled,
                motion.value(
                        "appearance.theme.radius",
                        t.cornerScale,
                        TRANSITION_SECONDS,
                        Motion.Easing.FAST_OUT_SLOW_IN,
                        ctx.time()));
    }
}
