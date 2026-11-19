package com.muoniumplayer.core.rendering;

/** Frame-local velocity estimate for split-card directional blur and silhouette trails. */
final class MusicCardMotionBlur {
    private static final long MAX_CONTIGUOUS_FRAME_NANOS = 90_000_000L;
    private static final double MAX_SPEED = 1.35;
    private static final double LONG_SIGMA_PER_SPEED = 3.2;
    private static final double MAX_LONG_SIGMA = 4.4;
    private static final double MAX_SHORT_SIGMA = 1.35;
    private static final double MAX_TRAIL_DISTANCE = 9.0;

    private double x = Double.NaN;
    private double y = Double.NaN;
    private double width = Double.NaN;
    private double height = Double.NaN;
    private long timeNanos;

    Sample advance(MusicCardMotion.Frame frame, long nowNanos) {
        if (frame == null || nowNanos <= 0L) {
            reset();
            return Sample.STILL;
        }
        double centerX = frame.x() + frame.width() * .5;
        double centerY = frame.y() + frame.height() * .5;
        if (timeNanos <= 0L || nowNanos <= timeNanos
                || nowNanos - timeNanos > MAX_CONTIGUOUS_FRAME_NANOS) {
            remember(frame, centerX, centerY, nowNanos);
            return Sample.STILL;
        }

        double dtMillis = Math.max(1.0, (nowNanos - timeNanos) / 1_000_000.0);
        double vx = (centerX - x) / dtMillis;
        double vy = (centerY - y) / dtMillis;
        double centerSpeed = Math.hypot(vx, vy);
        double widthRate = (frame.width() - width) / dtMillis;
        double heightRate = (frame.height() - height) / dtMillis;
        double shapeVX = 0.0, shapeVY = 0.0;
        if (Math.abs(widthRate) >= Math.abs(heightRate)) {
            shapeVX = Math.copySign(Math.abs(widthRate) * .35, widthRate == 0.0 ? 1.0 : widthRate);
        } else {
            shapeVY = Math.copySign(Math.abs(heightRate) * .35, heightRate);
        }
        double shapeSpeed = Math.hypot(shapeVX, shapeVY);
        double blurVX = centerSpeed > .02 ? vx + shapeVX : shapeVX;
        double blurVY = centerSpeed > .02 ? vy + shapeVY : shapeVY;
        double blurSpeed = Math.hypot(blurVX, blurVY);
        double speed = clamp(Math.max(centerSpeed, blurSpeed), 0.0, MAX_SPEED);
        double sigmaLong = Math.min(MAX_LONG_SIGMA, speed * LONG_SIGMA_PER_SPEED);
        double sigmaShort = blurSpeed <= .02 ? sigmaLong
                : Math.min(MAX_SHORT_SIGMA, .28 + speed * .92);
        double inverseBlur = blurSpeed <= .0001 ? 0.0 : 1.0 / blurSpeed;
        double inverseCenter = centerSpeed <= .0001 ? 0.0 : 1.0 / centerSpeed;
        double trailDistance = MAX_TRAIL_DISTANCE * smooth(centerSpeed / .90);
        double trailOpacity = .19 * smooth(centerSpeed / .62);
        Sample sample = new Sample(blurVX * inverseBlur, blurVY * inverseBlur,
                vx * inverseCenter, vy * inverseCenter, sigmaLong, sigmaShort,
                trailDistance, trailOpacity, speed);
        remember(frame, centerX, centerY, nowNanos);
        return sample;
    }

    void reset() {
        x = y = width = height = Double.NaN;
        timeNanos = 0L;
    }

    private void remember(MusicCardMotion.Frame frame, double centerX, double centerY, long nowNanos) {
        x = centerX;
        y = centerY;
        width = frame.width();
        height = frame.height();
        timeNanos = nowNanos;
    }

    static float[] directionalKernel(double directionX, double directionY, double speed) {
        return directionalKernel(directionX, directionY, speed, 0.0);
    }

    static float[] directionalKernel(double directionX, double directionY, double speed, double mix) {
        double magnitude = Math.hypot(directionX, directionY);
        if (!Double.isFinite(magnitude) || magnitude <= 1e-6) return identityKernel();
        double dx = directionX / magnitude, dy = directionY / magnitude;
        double sigmaLong = Math.min(2.2, Math.max(.45, speed * 1.55));
        double sigmaShort = .50;
        float[] kernel = new float[49];
        double total = 0.0;
        for (int y = -3; y <= 3; y++) for (int x = -3; x <= 3; x++) {
            double along = x * dx + y * dy;
            double across = -x * dy + y * dx;
            double weight = Math.exp(-.5 * (along * along / (sigmaLong * sigmaLong)
                    + across * across / (sigmaShort * sigmaShort)));
            kernel[(y + 3) * 7 + x + 3] = (float) weight;
            total += weight;
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= (float) total;
        if (mix > 0.0) {
            float strength = (float) clamp(mix, 0.0, .20);
            for (int i = 0; i < kernel.length; i++) kernel[i] *= strength;
            kernel[24] += 1f - strength;
        }
        return kernel;
    }

    private static float[] identityKernel() {
        float[] kernel = new float[49];
        kernel[24] = 1f;
        return kernel;
    }

    private static double smooth(double value) {
        double t = clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    record Sample(double directionX, double directionY, double trailDirectionX, double trailDirectionY,
                  double sigmaLong, double sigmaShort, double trailDistance, double trailOpacity, double speed) {
        static final Sample STILL = new Sample(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        boolean active() {
            return sigmaLong > .08 || trailOpacity > .005;
        }

        double blurMix() {
            return clamp(speed / .92, 0.0, .20);
        }
    }
}
