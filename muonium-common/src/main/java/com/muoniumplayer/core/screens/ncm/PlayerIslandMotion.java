package com.muoniumplayer.core.screens.ncm;

/**
 * Pure, finite-duration critically damped motion in layout coordinates.
 * Retargets inherit the displayed position and velocity, not just an easing phase.
 * All times are milliseconds; velocities exposed by {@link Sample} are per second.
 * The owner advances this object once per visible frame on its render thread.
 */
public final class PlayerIslandMotion {
    public static final double OPEN_MILLIS = 880.0;
    public static final double CLOSE_MILLIS = 650.0;
    public static final double NEIGHBOR_MILLIS = 1000.0 / 30.0;

    /** Caller-owned scratch data. Allocate once and reuse; sampling allocates nothing. */
    public static final class Sample {
        public double x, y, width, height, radius;
        public double velocityX, velocityY, velocityWidth, velocityHeight, velocityRadius;
        public double surfaceCenterX, surfaceCenterY, velocityCenterX, velocityCenterY;
        public double contentAlpha, contentAlphaVelocity, contentScale, contentScaleVelocity;
        /** Affine mapping of panel layout coordinates: screen = scale * panel + translate. */
        public double contentTranslateX, contentTranslateY;
        public double contentTranslateVelocityX, contentTranslateVelocityY;
        public double targetCenterX, targetCenterY, targetWidth, targetHeight;
        /** Actual offset after clipping to +/- NEIGHBOR_MILLIS and this retarget segment. */
        public double offsetMillis, timeMillis;
        public long segment;
    }

    /**
     * First visible frame consumes zero time, including when nanoTime happens to be zero.
     * Ordinary intervals up to 80ms are real time, independent of the speed setting.
     * Longer interruptions discard excess time rather than building a catch-up debt.
     */
    public static class FrameClock {
        private long previousNanos;
        private boolean initialized;

        public void reset() {
            initialized = false;
        }

        public double step(long now, double durationMillis) {
            if (!initialized) {
                previousNanos = now;
                initialized = true;
                return 0.0;
            }
            long nanos = now - previousNanos;
            if (nanos <= 0L) return 0.0;
            previousNanos = now;
            double elapsed = nanos / 1_000_000.0;
            if (elapsed <= 80.0) return elapsed;
            // Even the fastest configured close (217ms) retains several visible steps.
            // Tiny remaining tails may finish; a stall cannot consume a whole fresh segment.
            return Math.min(NEIGHBOR_MILLIS, durationMillis * .25);
        }
    }

    private final FrameClock clock = new FrameClock();
    private final Channel x = new Channel();
    private final Channel y = new Channel();
    private final Channel width = new Channel();
    private final Channel height = new Channel();
    private final Channel radius = new Channel();
    private final Channel alpha = new Channel();
    private double panelX, panelY, panelWidth = 1.0, panelHeight = 1.0;
    private double elapsedMillis, durationMillis = 1.0, lastFrameMillis;
    private long segment;
    private boolean running;

    public PlayerIslandMotion() {
        snap(0.0, 0.0, 1.0, 1.0, .5, 0.0);
    }

    /** Content transform reference; changing layout is separate from changing motion. */
    public void setPanel(double px, double py, double pw, double ph) {
        panelX = finite(px);
        panelY = finite(py);
        panelWidth = Math.max(1.0, finite(pw));
        panelHeight = Math.max(1.0, finite(ph));
    }

    /** Immediate lifecycle reset or already-open layout update, with zero velocity. */
    public void snap(double px, double py, double pw, double ph, double pr, double pa) {
        pw = Math.max(1.0, finite(pw));
        ph = Math.max(1.0, finite(ph));
        x.snap(finite(px));
        y.snap(finite(py));
        width.snap(pw);
        height.snap(ph);
        radius.snap(Math.max(0.0, Math.min(finite(pr), Math.min(pw, ph) * .5)));
        alpha.snap(unit(finite(pa)));
        elapsedMillis = 0.0;
        durationMillis = 1.0;
        running = false;
        segment++;
    }

    public void resetClock() {
        clock.reset();
        lastFrameMillis = 0.0;
    }

    /**
     * Begin/reverse a gesture, preserving all six channels' current velocities.
     * Resize retargets use resetClock=false so continuously moving targets cannot freeze time.
     */
    public void retarget(double px, double py, double pw, double ph, double pr, double pa,
                         double millis, boolean closing, boolean resetClock) {
        double nextDuration = Math.max(1.0, finite(millis));
        pw = Math.max(1.0, finite(pw));
        ph = Math.max(1.0, finite(ph));
        // A fresh opening starts from rest, unlike an interrupted close. Ease the onset
        // before the damped response and let width lead height slightly; the container
        // unfolds instead of immediately scaling every edge at the same speed.
        boolean unfolding = !closing && alpha.position == 0.0 && alpha.velocity == 0.0
                && x.velocity == 0.0 && y.velocity == 0.0
                && width.velocity == 0.0 && height.velocity == 0.0 && radius.velocity == 0.0;
        x.retarget(finite(px), nextDuration, 8.0, unfolding);
        y.retarget(finite(py), nextDuration, unfolding ? 6.8 : 8.0, unfolding);
        width.retarget(pw, nextDuration, 8.0, unfolding);
        height.retarget(ph, nextDuration, unfolding ? 6.8 : 8.0, unfolding);
        radius.retarget(Math.max(0.0, Math.min(finite(pr), Math.min(pw, ph) * .5)),
                nextDuration, unfolding ? 6.8 : 8.0, unfolding);
        // Content rises after the surface; on close it vacates that surface earlier.
        alpha.retarget(unit(finite(pa)), nextDuration, closing ? 9.0 : 5.5, unfolding);
        elapsedMillis = 0.0;
        durationMillis = nextDuration;
        running = true;
        segment++;
        if (resetClock) resetClock();
    }

    /** Current geometry target equality, not current position equality. */
    public boolean targets(double px, double py, double pw, double ph, double pr) {
        pw = Math.max(1.0, pw);
        ph = Math.max(1.0, ph);
        pr = Math.max(0.0, Math.min(pr, Math.min(pw, ph) * .5));
        return x.target == px && y.target == py && width.target == pw
                && height.target == ph && radius.target == pr;
    }

    /** Returns the same bounded delta used for motion and material handoff. */
    public double tick(long now) {
        lastFrameMillis = clock.step(now, running ? durationMillis : Double.POSITIVE_INFINITY);
        if (!running || lastFrameMillis == 0.0) return lastFrameMillis;
        elapsedMillis = Math.min(durationMillis, elapsedMillis + lastFrameMillis);
        // nanoTime is integer nanoseconds, while configured durations may be fractional.
        if (durationMillis - elapsedMillis <= .000001) elapsedMillis = durationMillis;
        x.advance(elapsedMillis);
        y.advance(elapsedMillis);
        width.advance(elapsedMillis);
        height.advance(elapsedMillis);
        radius.advance(elapsedMillis);
        alpha.advance(elapsedMillis);
        constrainCurrent();
        if (elapsedMillis >= durationMillis) running = false;
        return lastFrameMillis;
    }

    public boolean isRunning() {
        return running;
    }

    public double remainingMillis() {
        return running ? Math.max(0.0, durationMillis - elapsedMillis) : 0.0;
    }

    public double lastFrameMillis() {
        return lastFrameMillis;
    }

    /**
     * Read current or nearby motion, without advancing the clock, changing the lifecycle,
     * or crossing a retarget boundary. Zero offset reads the exact displayed state.
     * Panel references are those of the current layout, not a prediction of future resize.
     */
    public void sample(double offsetMillis, Sample out) {
        double offset = Double.isFinite(offsetMillis)
                ? Math.max(-NEIGHBOR_MILLIS, Math.min(NEIGHBOR_MILLIS, offsetMillis)) : 0.0;
        double time = Math.max(0.0, Math.min(durationMillis, elapsedMillis + offset));
        out.offsetMillis = time - elapsedMillis;
        out.timeMillis = time;
        out.segment = segment;
        if (out.offsetMillis == 0.0) {
            out.x = x.position;
            out.y = y.position;
            out.width = width.position;
            out.height = height.position;
            out.radius = radius.position;
            out.contentAlpha = alpha.position;
            out.velocityX = x.velocity;
            out.velocityY = y.velocity;
            out.velocityWidth = width.velocity;
            out.velocityHeight = height.velocity;
            out.velocityRadius = radius.velocity;
            out.contentAlphaVelocity = alpha.velocity;
        } else {
            x.evaluate(time);
            y.evaluate(time);
            width.evaluate(time);
            height.evaluate(time);
            radius.evaluate(time);
            alpha.evaluate(time);
            out.x = x.evaluatedPosition;
            out.y = y.evaluatedPosition;
            out.width = width.evaluatedPosition;
            out.height = height.evaluatedPosition;
            out.radius = radius.evaluatedPosition;
            out.contentAlpha = alpha.evaluatedPosition;
            out.velocityX = x.evaluatedVelocity;
            out.velocityY = y.evaluatedVelocity;
            out.velocityWidth = width.evaluatedVelocity;
            out.velocityHeight = height.evaluatedVelocity;
            out.velocityRadius = radius.evaluatedVelocity;
            out.contentAlphaVelocity = alpha.evaluatedVelocity;
        }
        constrainSample(out);
        out.surfaceCenterX = out.x + out.width * .5;
        out.surfaceCenterY = out.y + out.height * .5;
        out.velocityCenterX = out.velocityX + out.velocityWidth * .5;
        out.velocityCenterY = out.velocityY + out.velocityHeight * .5;
        out.targetCenterX = panelX + panelWidth * .5;
        out.targetCenterY = panelY + panelHeight * .5;
        out.targetWidth = panelWidth;
        out.targetHeight = panelHeight;
        double sx = out.width / panelWidth;
        double sy = out.height / panelHeight;
        out.contentScale = Math.max(sx, sy);
        out.contentScaleVelocity = sx >= sy
                ? out.velocityWidth / panelWidth : out.velocityHeight / panelHeight;
        out.contentTranslateX = out.surfaceCenterX - out.contentScale * out.targetCenterX;
        out.contentTranslateY = out.surfaceCenterY - out.contentScale * out.targetCenterY;
        out.contentTranslateVelocityX = out.velocityCenterX - out.contentScaleVelocity * out.targetCenterX;
        out.contentTranslateVelocityY = out.velocityCenterY - out.contentScaleVelocity * out.targetCenterY;
    }

    private void constrainCurrent() {
        if (width.position < 1.0) {
            width.position = 1.0;
            width.velocity = 0.0;
        }
        if (height.position < 1.0) {
            height.position = 1.0;
            height.velocity = 0.0;
        }
        double maxRadius = Math.min(width.position, height.position) * .5;
        if (radius.position < 0.0) {
            radius.position = 0.0;
            radius.velocity = 0.0;
        } else if (radius.position > maxRadius) {
            radius.position = maxRadius;
            radius.velocity = (width.position <= height.position ? width.velocity : height.velocity) * .5;
        }
        if (alpha.position < 0.0 || alpha.position > 1.0) {
            alpha.position = unit(alpha.position);
            alpha.velocity = 0.0;
        }
    }

    private static void constrainSample(Sample out) {
        if (out.width < 1.0) {
            out.width = 1.0;
            out.velocityWidth = 0.0;
        }
        if (out.height < 1.0) {
            out.height = 1.0;
            out.velocityHeight = 0.0;
        }
        double maxRadius = Math.min(out.width, out.height) * .5;
        if (out.radius < 0.0) {
            out.radius = 0.0;
            out.velocityRadius = 0.0;
        } else if (out.radius > maxRadius) {
            out.radius = maxRadius;
            out.velocityRadius = (out.width <= out.height ? out.velocityWidth : out.velocityHeight) * .5;
        }
        if (out.contentAlpha < 0.0 || out.contentAlpha > 1.0) {
            out.contentAlpha = unit(out.contentAlpha);
            out.contentAlphaVelocity = 0.0;
        }
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite motion input");
        return value;
    }

    private static double unit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class Channel {
        private double position, velocity, start, startVelocity, target;
        private double seconds = .001, omega, residue, endVelocity, endAcceleration;
        private double evaluatedPosition, evaluatedVelocity;
        private boolean softStart;

        private void snap(double value) {
            position = start = target = value;
            velocity = startVelocity = 0.0;
            omega = residue = endVelocity = endAcceleration = 0.0;
            softStart = false;
        }

        private void retarget(double value, double millis, double damping, boolean softenOnset) {
            softStart = softenOnset;
            start = position;
            startVelocity = velocity;
            target = value;
            seconds = millis / 1000.0;
            omega = damping / seconds;
            double a = start - target;
            double b = startVelocity + omega * a;
            double decay = Math.exp(-omega * seconds);
            residue = (a + b * seconds) * decay;
            endVelocity = (startVelocity - omega * b * seconds) * decay;
            endAcceleration = (-omega * b - omega * startVelocity + omega * omega * b * seconds) * decay;
        }

        private void advance(double millis) {
            evaluate(millis);
            position = evaluatedPosition;
            velocity = evaluatedVelocity;
        }

        private void evaluate(double millis) {
            double time = millis / 1000.0;
            if (time <= 0.0) {
                evaluatedPosition = start;
                evaluatedVelocity = startVelocity;
                return;
            }
            if (time >= seconds) {
                evaluatedPosition = target;
                evaluatedVelocity = 0.0;
                return;
            }
            double rate = 1.0;
            if (softStart) {
                double phase = time / seconds;
                double rest = 1.0 - phase;
                // Slow only the onset, not the whole animation. This warp is monotonic,
                // rejoins the original clock at the end, and has an analytic derivative.
                time = seconds * (phase - .75 * phase * rest * rest * rest);
                rate = 1.0 - .75 * rest * rest * (1.0 - 4.0 * phase);
            }
            double a = start - target;
            double b = startVelocity + omega * a;
            double decay = Math.exp(-omega * time);
            evaluatedPosition = target + (a + b * time) * decay;
            evaluatedVelocity = (startVelocity - omega * b * time) * decay;
            // Only correct the small damped tail. Quintic Hermite bases have zero
            // initial value/slope/acceleration and exact end position/slope/acceleration.
            double tail = seconds * .35;
            double u = (time - seconds * .65) / tail;
            if (u > 0.0) {
                double u2 = u * u, u3 = u2 * u, u4 = u3 * u, u5 = u4 * u;
                double p = 10.0 * u3 - 15.0 * u4 + 6.0 * u5;
                double v = -4.0 * u3 + 7.0 * u4 - 3.0 * u5;
                double aBasis = .5 * u3 - u4 + .5 * u5;
                double dp = 30.0 * u2 - 60.0 * u3 + 30.0 * u4;
                double dv = -12.0 * u2 + 28.0 * u3 - 15.0 * u4;
                double da = 1.5 * u2 - 4.0 * u3 + 2.5 * u4;
                evaluatedPosition -= residue * p + tail * endVelocity * v + tail * tail * endAcceleration * aBasis;
                evaluatedVelocity -= residue * dp / tail + endVelocity * dv + tail * endAcceleration * da;
            }
            evaluatedVelocity *= rate;
        }
    }
}
