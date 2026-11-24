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

    static final class Transition {
        double alpha = 1, blur, glitch, seed, exitProgress;
        void reset() { alpha = 1; blur = glitch = seed = exitProgress = 0; }
    }

    static void transition(int kind, boolean exiting, double progress, double seed, Transition out) {
        double linear = clamp(progress);
        double amount = exiting ? inOut(linear) : 1 - inOut(linear);
        out.reset();
        out.exitProgress = exiting ? linear : 0;
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