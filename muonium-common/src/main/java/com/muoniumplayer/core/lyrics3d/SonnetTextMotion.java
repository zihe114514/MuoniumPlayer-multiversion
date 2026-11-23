package com.muoniumplayer.core.lyrics3d;

/** Absolute-time motion math adapted from Folia Sonnet (chthollyphile, AGPL-3.0). */
final class SonnetTextMotion {
    private SonnetTextMotion() { }

    static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    static double expo(double value) {
        double t = clamp(value);
        return t == 1 ? 1 : 1 - Math.pow(2, -10 * t);
    }

    static double inOut(double value) { return bezier(0.65, 0, 0.35, 1, value); }

    static double bezier(double x1, double y1, double x2, double y2, double value) {
        double x = clamp(value);
        if (x == 0 || x == 1) return x;
        double low = 0, high = 1, t = x;
        for (int i = 0; i < 20; i++) {
            double estimate = coordinate(x1, x2, t);
            if (Math.abs(estimate - x) < 1e-8) break;
            if (estimate < x) low = t;
            else high = t;
            double u = 1 - t;
            double slope = 3 * u * u * x1 + 6 * u * t * (x2 - x1) + 3 * t * t * (1 - x2);
            double next = Math.abs(slope) < 1e-9 ? Double.NaN : t - (estimate - x) / slope;
            t = Double.isFinite(next) && next > low && next < high ? next : (low + high) / 2;
        }
        return coordinate(y1, y2, t);
    }

    private static double coordinate(double p1, double p2, double t) {
        double u = 1 - t;
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
    }

    static double motionDuration(double startMs, double endMs) {
        double duration = Math.max(1, endMs - startMs);
        return Math.min(Math.min(1800, Math.max(650, duration * 0.42)), duration * 0.72);
    }

    static double progress(double startMs, double settleMs, double nowMs) {
        return expo((nowMs - startMs) / Math.max(settleMs - startMs, 80));
    }

    static double guideLead(double segmentStart, double segmentEnd) {
        return Math.min(380, Math.max(200, 180 + (segmentEnd - segmentStart) * 0.1));
    }

    static double ghostDuration(double shotDurationMs) {
        return Math.min(700, Math.max(400, shotDurationMs * 0.12 + 100));
    }

    static double ghostEnvelope(double progress) {
        double t = clamp(progress);
        if (t <= 0 || t >= 1) return 0;
        return t <= 0.2 ? t / 0.2 : Math.pow(1 - (t - 0.2) / 0.8, 2);
    }

    static double ghostSpread(double progress) {
        return 1 - Math.pow(1 - clamp(progress), 3);
    }

    static final class Camera {
        double x, y, scale = 1, rotation;
    }

    static void camera(SonnetTypography.Kind kind, double progress, double timeSeconds,
                       double revealDoneSeconds, double phase, double gapSeconds, Camera out) {
        path(kind, progress, out);
        double gap = Math.max(0, gapSeconds);
        if (gap > 0) {
            Camera tail = new Camera();
            path(kind, 0.8, tail);
            double drift = (1 - Math.exp(-gap * 0.4)) * 2;
            out.x += (out.x - tail.x) * drift;
            out.y += (out.y - tail.y) * drift;
            out.scale += (out.scale - tail.scale) * drift;
            out.rotation += (out.rotation - tail.rotation) * drift;
        }
        double weight = inOut((timeSeconds - revealDoneSeconds) / 1.2);
        double tau = timeSeconds * Math.PI * 2;
        out.x += (Math.sin(tau * 0.13 + phase) * 0.65 + Math.sin(tau * 0.31 + phase * 1.7) * 0.35) * 0.006 * weight;
        out.y += (Math.cos(tau * 0.11 + phase * 2.3) * 0.65 + Math.sin(tau * 0.29 + phase * 0.9) * 0.35) * 0.006 * weight;
        out.scale += Math.sin(tau * 0.09 + phase * 1.3) * 0.002 * weight;
        out.rotation += Math.sin(tau * 0.07 + phase * 2.9) * 0.0015 * weight;
    }

    static void path(SonnetTypography.Kind kind, double progress, Camera out) {
        double p = clamp(progress);
        boolean continuous = kind == SonnetTypography.Kind.TRACKING_RIBBON
                || kind == SonnetTypography.Kind.FRAGMENT_COLLAGE
                || kind == SonnetTypography.Kind.QUIET_TABLEAU
                || kind == SonnetTypography.Kind.POSTER_BLOCKS;
        double e = continuous ? p * 0.55 + inOut(p) * 0.45
                : p < 0.18 ? expo(p / 0.18) * 0.22
                : p < 0.78 ? 0.22 + (p - 0.18) / 0.6 * 0.56
                : 0.78 + (1 - Math.pow(1 - (p - 0.78) / 0.22, 2)) * 0.22;
        switch (kind) {
            case EDITORIAL_COLUMN:
                out.x = -0.055 + e * 0.095; out.y = 0.025 - e * 0.04;
                out.scale = 0.98 + e * 0.07; out.rotation = -0.006 + e * 0.01; break;
            case TYPE_IMPACT:
                out.x = -0.035 + e * 0.07; out.y = 0.018 - e * 0.028;
                out.scale = 1 + (1 - expo(Math.min(p / 0.18, 1))) * 0.22 + e * 0.08;
                out.rotation = -0.01 + e * 0.016; break;
            case FRAGMENT_COLLAGE:
                out.x = -0.045 + e * 0.085; out.y = 0.028 - Math.sin(e * Math.PI) * 0.055;
                out.scale = 0.97 + e * 0.09; out.rotation = -0.014 + e * 0.028; break;
            case TRACKING_RIBBON:
                out.x = -0.16 + e * 0.28; out.y = 0.05 - e * 0.085;
                out.scale = 0.98 + e * 0.07; out.rotation = 0.008 - e * 0.014; break;
            case MASK_REVEAL:
                out.x = 0.035 - e * 0.065; out.y = 0.1 - e * 0.135;
                out.scale = 0.96 + e * 0.12; out.rotation = -0.006 + e * 0.009; break;
            case POSTER_BLOCKS:
                out.x = -0.012 + e * 0.024; out.y = 0.008 - e * 0.016;
                out.scale = 0.99 + e * 0.025; out.rotation = -0.0015 + e * 0.003; break;
            case QUIET_TABLEAU:
            default:
                out.x = -0.022 + e * 0.04; out.y = 0.014 - e * 0.025;
                out.scale = 1 + e * 0.028; out.rotation = -0.002 + e * 0.003; break;
        }
    }

    static final class Transition {
        double alpha = 1, blur, glitch, seed;
        void reset() { alpha = 1; blur = glitch = seed = 0; }
    }

    static void transition(int kind, boolean exiting, double progress, double seed, Transition out) {
        double linear = clamp(progress);
        double amount = exiting ? inOut(linear) : 1 - inOut(linear);
        out.reset();
        if (kind == 0) {
            out.alpha = exiting ? 1 - amount : 1 - amount * 0.82;
            out.blur = amount * 14;
        } else if (kind == 1) {
            out.alpha = exiting && linear > 0.86 ? 1 - (linear - 0.86) / 0.14 : 1;
            out.glitch = amount;
            out.seed = seed * 0.0001 + Math.floor(linear * 14) * 0.173;
        } else {
            out.alpha = exiting ? 1 - amount : 1 - amount * 0.72;
        }
    }
}