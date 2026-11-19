package com.muoniumplayer.core.screens.ncm;

/** One-frame Material-inspired palette derived from the live player theme. */
public final class NcmExpressivePalette {
    private static NcmExpressivePalette livePalette;
    private static final int[] liveColors = new int[9];

    private final int background;
    private final int surface;
    private final int container;
    private final int input;
    private final int outline;
    private final int text;
    private final int muted;
    private final int primary;
    private final int primaryContainer;
    private final int primaryHover;
    private final int onPrimary;
    private final int success;
    private final int warning;
    private final int error;
    private final int containerInk;

    private NcmExpressivePalette(int background, int surface, int container, int input, int outline,
                                 int text, int muted, int primary, int primaryContainer, int primaryHover,
                                 int onPrimary, int success, int warning, int error, int containerInk) {
        this.background = background;
        this.surface = surface;
        this.container = container;
        this.input = input;
        this.outline = outline;
        this.text = text;
        this.muted = muted;
        this.primary = primary;
        this.primaryContainer = primaryContainer;
        this.primaryHover = primaryHover;
        this.onPrimary = onPrimary;
        this.success = success;
        this.warning = warning;
        this.error = error;
        this.containerInk = containerInk;
    }

    public static NcmExpressivePalette live() {
        int background = NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
        int element = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
        int hover = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
        int border = NCMScreen.getColor(NCMScreen.ColorType.BORDER);
        int text = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
        int muted = NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
        int accentHover = NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER);
        int input = NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND);
        // 仍逐帧读取实时主题；稳定色值只推导一次，过渡或封面跟随一变就失效。
        if (livePalette == null || background != liveColors[0] || element != liveColors[1]
                || hover != liveColors[2] || border != liveColors[3] || text != liveColors[4]
                || muted != liveColors[5] || accent != liveColors[6] || accentHover != liveColors[7]
                || input != liveColors[8]) {
            liveColors[0] = background;
            liveColors[1] = element;
            liveColors[2] = hover;
            liveColors[3] = border;
            liveColors[4] = text;
            liveColors[5] = muted;
            liveColors[6] = accent;
            liveColors[7] = accentHover;
            liveColors[8] = input;
            livePalette = fromColors(background, element, hover, border, text, muted, accent, accentHover, input);
        }
        return livePalette;
    }

    public static NcmExpressivePalette fromColors(int background, int element, int hover, int border,
                                                  int text, int muted, int accent, int accentHover,
                                                  int inputBackground) {
        int base = opaque(background);
        int ink = opaque(text);
        int surface = pairedSurface(mix(base, opaque(element), .28), base, ink);
        int container = pairedSurface(mix(surface, opaque(element), .52), surface, ink);
        int input = pairedSurface(mix(surface, opaque(inputBackground), .68), surface, ink);
        int hoverSurface = pairedSurface(mix(container, opaque(hover), .54), container, ink);
        int primary = readable(opaque(accent), 3, surface, container, input, hoverSurface);
        int primaryContainer = pairedSurface(mix(surface, primary, .18), surface, ink);
        int primaryHover = readable(opaque(accentHover), 3, surface, container, input, hoverSurface);
        int onPrimary = readable(ink, 4.5, primary);
        // 主色与悬停色可能落在亮度中点两侧；先固定主色的前景，再把悬停底色拉回可读区间。
        primaryHover = pairedSurface(primaryHover, primary, onPrimary);
        boolean dark = luminance(base) < .35;
        int success = readable(dark ? 0xFF9AD7B2 : 0xFF286443, 4.5, surface, container);
        int warning = readable(dark ? 0xFFE9C27F : 0xFF775300, 4.5, surface, container);
        int error = readable(dark ? 0xFFFFB4AB : 0xFFA72621, 4.5, surface, container);
        int containerInk = readable(ink, 4.5, container);
        return new NcmExpressivePalette(base, surface, container, input,
                readable(opaque(border), 3, base, surface, container),
                readable(ink, 4.5, surface, container, input, hoverSurface, primaryContainer),
                readable(opaque(muted), 4.5, surface, container, input, hoverSurface, primaryContainer),
                primary, primaryContainer, primaryHover, onPrimary, success, warning, error, containerInk);
    }

    public int background() { return background; }
    public int surface() { return surface; }
    public int container() { return container; }
    public int containerInk() { return containerInk; }
    public int input() { return input; }
    public int outline() { return outline; }
    public int text() { return text; }
    public int muted() { return muted; }
    public int primary() { return primary; }
    public int primaryContainer() { return primaryContainer; }
    public int primaryHover() { return primaryHover; }
    public int onPrimary() { return onPrimary; }
    public int success() { return success; }
    public int warning() { return warning; }
    public int error() { return error; }

    public static int mix(int from, int to, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        int r = (int) Math.round(((from >>> 16) & 255) * (1 - t) + ((to >>> 16) & 255) * t);
        int g = (int) Math.round(((from >>> 8) & 255) * (1 - t) + ((to >>> 8) & 255) * t);
        int b = (int) Math.round((from & 255) * (1 - t) + (to & 255) * t);
        return opaque((r << 16) | (g << 8) | b);
    }

    public static double contrast(int foreground, int background) {
        double a = luminance(foreground), b = luminance(background);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    public static int readableColor(int desired, int background) {
        return readable(opaque(desired), 4.5, opaque(background));
    }

    private static int pairedSurface(int color, int fallback, int foreground) {
        if (contrast(foreground, color) >= 4.5) return color;
        double lo = 0, hi = 1;
        for (int i = 0; i < 16; i++) {
            double t = (lo + hi) * .5;
            if (contrast(foreground, mix(color, fallback, t)) >= 4.5) hi = t;
            else lo = t;
        }
        return mix(color, fallback, hi);
    }

    private static int readable(int color, double minimum, int... backgrounds) {
        if (weakestContrast(color, backgrounds) >= minimum) return color;
        int white = 0xFFFFFFFF, black = 0xFF000000;
        int endpoint = weakestContrast(white, backgrounds) >= weakestContrast(black, backgrounds) ? white : black;
        if (weakestContrast(endpoint, backgrounds) < minimum) return endpoint;
        double lo = 0, hi = 1;
        for (int i = 0; i < 16; i++) {
            double t = (lo + hi) * .5;
            if (weakestContrast(mix(color, endpoint, t), backgrounds) >= minimum) hi = t;
            else lo = t;
        }
        return mix(color, endpoint, hi);
    }

    private static double weakestContrast(int color, int[] backgrounds) {
        double weakest = 21;
        for (int bg : backgrounds) weakest = Math.min(weakest, contrast(color, bg));
        return weakest;
    }

    private static double luminance(int color) {
        return .2126 * channel((color >>> 16) & 255) + .7152 * channel((color >>> 8) & 255)
                + .0722 * channel(color & 255);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }

    private static int opaque(int color) { return color | 0xFF000000; }
}
