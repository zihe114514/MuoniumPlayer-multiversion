package com.muoniumplayer.core.screens.ncm;

/** Math shared by the now-playing backdrop and lyric rendering. No native work here. */
final class AppleLyricEffects {
    private AppleLyricEffects() {}

    static int backdropColor(int[] cover, int index) {
        if (cover == null || cover.length == 0) {
            return switch (index) {
                case 0 -> 0xff302d29;
                case 1 -> 0xff242831;
                default -> 0xff30272a;
            };
        }
        int rgb = cover[Math.min(index, cover.length - 1)];
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int luma = (r * 54 + g * 183 + b * 19) >> 8;
        return 0xff000000 | (darken(r, luma) << 16) | (darken(g, luma) << 8) | darken(b, luma);
    }

    private static int darken(int value, int luma) {
        // Keep even bright covers below the lyric contrast floor; don't turn neutral album art blue.
        return (int) (16 + value * .23 + luma * .09);
    }

    static int mix(int a, int b, double fraction) {
        double t = clamp(fraction);
        return 0xff000000 | (mixChannel(a >> 16, b >> 16, t) << 16)
                | (mixChannel(a >> 8, b >> 8, t) << 8) | mixChannel(a, b, t);
    }

    private static int mixChannel(int a, int b, double t) {
        return (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
    }

    private static double clamp(double t) {
        return Math.max(0, Math.min(1, t));
    }
}
