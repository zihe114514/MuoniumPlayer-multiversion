package com.muoniumplayer.core.rendering;

/** 从本曲已缓存的封面色板选取可读的进度色；无封面时不沿用上一曲的全局色板。 */
final class MusicCardColors {
    private MusicCardColors() {
    }

    static int accent(int[] palette, int fallback) {
        int chosen = fallback;
        double best = -1.0;
        if (palette != null) {
            for (int color : palette) {
                int r = (color >>> 16) & 255;
                int g = (color >>> 8) & 255;
                int b = color & 255;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                double score = (max - min) * .75 + max * .25;
                if (score > best) {
                    best = score;
                    chosen = color;
                }
            }
        }
        return lift(chosen);
    }

    static int secondary(int[] palette, int fallback) {
        if (palette == null || palette.length < 2) return mix(fallback, 0xA8B4D9, .30);
        int chosen = palette[0];
        long best = -1L;
        for (int color : palette) {
            long r = ((color >>> 16) & 255) - ((fallback >>> 16) & 255);
            long g = ((color >>> 8) & 255) - ((fallback >>> 8) & 255);
            long b = (color & 255) - (fallback & 255);
            long distance = r * r + g * g + b * b;
            if (distance > best) {
                best = distance;
                chosen = color;
            }
        }
        return lift(chosen);
    }

    /** 深色封面保留色相并提亮；灰阶封面仍然显示中性的银白进度。 */
    private static int lift(int color) {
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min < 16) return 0xE3E4EC;
        double amount = max < 185 ? (185.0 - max) / (255.0 - max) : .12;
        return mix(color, 0xFFFFFF, amount);
    }

    static int mix(int from, int to, double amount) {
        double t = Double.isFinite(amount) ? Math.max(0.0, Math.min(1.0, amount)) : 0.0;
        int r = (int) Math.round(((from >>> 16) & 255) * (1.0 - t) + ((to >>> 16) & 255) * t);
        int g = (int) Math.round(((from >>> 8) & 255) * (1.0 - t) + ((to >>> 8) & 255) * t);
        int b = (int) Math.round((from & 255) * (1.0 - t) + (to & 255) * t);
        return (r << 16) | (g << 8) | b;
    }
}
