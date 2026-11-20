package com.muoniumplayer.core.rendering;

/** Pure timing and rounded-rectangle geometry for the model inference capsule border. */
final class InferenceCapsuleMotion {
    static final long LOOP_MILLIS = 1_650L;

    private InferenceCapsuleMotion() {
    }

    static double phase(long nowMillis) {
        return Math.floorMod(nowMillis, LOOP_MILLIS) / (double) LOOP_MILLIS;
    }

    static int sampleCount(double width, double height, double radius) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(radius)
                || width <= 0.0 || height <= 0.0) return 0;
        double r = Math.max(0.0, Math.min(radius, height * .5));
        double perimeter = 2.0 * (Math.max(0.0, width - 2.0 * r)
                + Math.max(0.0, height - 2.0 * r)) + 2.0 * Math.PI * r;
        return Math.max(12, Math.min(96, (int) Math.ceil(perimeter / 5.0)));
    }

    static int completedSamples(double progress, int sampleCount) {
        if (sampleCount <= 0 || !Double.isFinite(progress) || progress < 0.0) return 0;
        return Math.max(0, Math.min(sampleCount, (int) Math.floor(clamp01(progress) * sampleCount)));
    }

    static double progress(double progress) {
        return Double.isFinite(progress) && progress >= 0.0 ? clamp01(progress) : -1.0;
    }

    static int trailSampleCount(int sampleCount) {
        return Math.max(8, (int) Math.ceil(Math.max(0, sampleCount) * .26));
    }

    static double trailLength(double progress) {
        return progress >= 0.0 ? .14 : .26;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    static Point pointAt(double width, double height, double radius, double progress) {
        double w = Double.isFinite(width) ? Math.max(0.0, width) : 0.0;
        double h = Double.isFinite(height) ? Math.max(0.0, height) : 0.0;
        double r = Double.isFinite(radius) ? Math.max(0.0, Math.min(radius, h * .5)) : 0.0;
        double p = Double.isFinite(progress) ? progress - Math.floor(progress) : 0.0;
        double top = Math.max(0.0, w - 2.0 * r);
        double side = Math.max(0.0, h - 2.0 * r);
        double arc = Math.PI * r * .5;
        double distance = p * (2.0 * (top + side) + 4.0 * arc);
        for (int segment = 0; segment < 8; segment++) {
            double length = (segment & 1) == 0 ? (segment == 0 || segment == 4 ? top : side) : arc;
            if (distance <= length || segment == 7) {
                double along = Math.max(0.0, Math.min(length, distance));
                switch (segment) {
                    case 0: return new Point(r + along, 0.0, 0.0);
                    case 1: return arcPoint(w - r, r, -90.0, along, r, arc);
                    case 2: return new Point(w, r + along, 90.0);
                    case 3: return arcPoint(w - r, h - r, 0.0, along, r, arc);
                    case 4: return new Point(w - r - along, h, 180.0);
                    case 5: return arcPoint(r, h - r, 90.0, along, r, arc);
                    case 6: return new Point(0.0, h - r - along, 270.0);
                    default: return arcPoint(r, r, 180.0, along, r, arc);
                }
            }
            distance -= length;
        }
        return new Point(r, 0.0, 0.0);
    }

    private static Point arcPoint(double centerX, double centerY, double startDegrees,
                                  double distance, double radius, double arcLength) {
        if (radius <= 1e-9 || arcLength <= 1e-9) return new Point(centerX, centerY, startDegrees + 90.0);
        double angle = Math.toRadians(startDegrees + distance / arcLength * 90.0);
        return new Point(centerX + radius * Math.cos(angle), centerY + radius * Math.sin(angle),
                Math.toDegrees(angle) + 90.0);
    }

    static final class Point {
        final double x;
        final double y;
        final double tangentDegrees;

        Point(double x, double y, double tangentDegrees) {
            this.x = x;
            this.y = y;
            this.tangentDegrees = tangentDegrees;
        }
    }
}
