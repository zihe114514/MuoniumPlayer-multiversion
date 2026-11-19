package com.muoniumplayer.core.screens.ncm.effects;

/** 一帧内共用的语义色板；主题来源与对比度修正分开，不持有全局可变颜色。 */
public record EffectsPalette(int background, int surface, int container, int input, int high, int outline,
                             int text, int muted, int primary, int primaryContainer, int onPrimary,
                             int primaryHover, int success, int warning, int error) {

    public static EffectsPalette fromColors(int background, int element, int hover, int border,
                                            int text, int secondaryText, int accent) {
        return fromColors(background, element, hover, border, text, secondaryText, accent,
                accent, element, element);
    }

    public static EffectsPalette fromColors(int background, int element, int hover, int border,
                                            int text, int secondaryText, int accent, int accentHover,
                                            int inputBackground, int navigationBackground) {
        int base = opaque(background);
        int ink = opaque(text);
        int panel = mix(base, opaque(navigationBackground), .35);
        int card = mix(base, opaque(element), .38);
        int input = mix(card, opaque(inputBackground), .32);
        int elevated = mix(card, opaque(hover), .22);
        int selected = mix(card, opaque(accent), .16);
        int baselineInk = readable(ink, 4.5, base);
        panel = pairedSurface(panel, base, baselineInk);
        card = pairedSurface(card, base, baselineInk);
        input = pairedSurface(input, base, baselineInk);
        elevated = pairedSurface(elevated, base, baselineInk);
        selected = pairedSurface(selected, base, baselineInk);
        int[] surfaces = {base, panel, card, input, elevated, selected};
        int foreground = readable(ink, 4.5, surfaces);
        int secondary = readable(opaque(secondaryText), 4.5, surfaces);
        int primary = readable(opaque(accent), 4.5, surfaces);
        int onPrimary = readable(ink, 4.5, primary);
        int primaryHover = pairedSurface(readable(opaque(accentHover), 4.5, surfaces), primary, onPrimary);
        boolean dark = luminance(base) < .35;
        return new EffectsPalette(base, panel, card, input, elevated, readable(opaque(border), 3, surfaces),
                foreground, secondary, primary, selected, onPrimary, primaryHover,
                readable(dark ? 0xFFA6D9BF : 0xFF286443, 4.5, surfaces),
                readable(dark ? 0xFFEAC38B : 0xFF775300, 4.5, surfaces),
                readable(dark ? 0xFFFFB4AB : 0xFFA72621, 4.5, surfaces));
    }

    /** 无宿主的离屏夹具使用；实际界面每帧从播放器主题取得颜色。 */
    public static EffectsPalette fallback() {
        return fromColors(0xFF141318, 0xFF27242D, 0xFF302C38, 0xFF49434F,
                0xFFECE6F0, 0xFFBEB6C7, 0xFFD0BCFF);
    }

    private static int mix(int from, int to, double amount) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            out |= ((int) Math.round(a + (b - a) * amount)) << shift;
        }
        return out;
    }

    private static int opaque(int color) { return color | 0xFF000000; }

    public static double contrast(int foreground, int background) {
        double a = luminance(foreground), b = luminance(background);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(int color) {
        return .2126 * channel((color >>> 16) & 255) + .7152 * channel((color >>> 8) & 255)
                + .0722 * channel(color & 255);
    }

    private static double channel(int value) {
        double linear = value / 255.0;
        return linear <= .04045 ? linear / 12.92 : Math.pow((linear + .055) / 1.055, 2.4);
    }

    private static double weakestContrast(int color, int[] backgrounds) {
        double weakest = 21;
        for (int background : backgrounds) weakest = Math.min(weakest, contrast(color, background));
        return weakest;
    }

    private static int pairedSurface(int surface, int fallback, int foreground) {
        if (contrast(foreground, surface) >= 4.5) return surface;
        double lo = 0, hi = 1;
        for (int i = 0; i < 12; i++) {
            double t = (lo + hi) * .5;
            if (contrast(foreground, mix(surface, fallback, t)) >= 4.5) hi = t;
            else lo = t;
        }
        return mix(surface, fallback, hi);
    }

    private static int readable(int color, double minimum, int... backgrounds) {
        if (weakestContrast(color, backgrounds) >= minimum) return color;
        int light = 0xFFFFFFFF, dark = 0xFF000000;
        int endpoint = weakestContrast(light, backgrounds) > weakestContrast(dark, backgrounds) ? light : dark;
        if (weakestContrast(endpoint, backgrounds) < minimum) return endpoint;
        double lo = 0, hi = 1;
        for (int i = 0; i < 12; i++) {
            double t = (lo + hi) * .5;
            if (weakestContrast(mix(color, endpoint, t), backgrounds) >= minimum) hi = t;
            else lo = t;
        }
        return mix(color, endpoint, hi);
    }
}
