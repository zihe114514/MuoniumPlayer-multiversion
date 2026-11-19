package com.muoniumplayer.core.rendering;

/** Music-card-only frame timing and vector vertices; no renderer, player or native ownership. */
final class MusicCardEffects {
    static final double RIPPLE_MILLIS = 620.0;
    static final double TAP_MILLIS = 560.0;
    private static final double RESULT_HOLD_MILLIS = 650.0;

    enum Icon { PREVIOUS, PLAY_PAUSE, NEXT }

    final Tap previous = new Tap(-1.0);
    final Tap playPause = new Tap(0.0);
    final Tap next = new Tap(1.0);
    final Morph playback = new Morph();
    final Ripple ripple = new Ripple();
    final CoverWait coverWait = new CoverWait();

    void tick(double deltaMillis, boolean playing) {
        tickPresentation(deltaMillis, playing);
        coverWait.tick(deltaMillis);
    }

    void tickPresentation(double deltaMillis, boolean playing) {
        double dt = frameDelta(deltaMillis);
        previous.tick(dt);
        playPause.tick(dt);
        next.tick(dt);
        ripple.tick(dt);
        playback.tick(dt, playing);
    }

    static double frameDelta(double millis) {
        return Double.isFinite(millis) ? Math.max(0.0, Math.min(40.0, millis)) : 0.0;
    }

    static final class CoverWait {
        private static final double DEADLINE_MILLIS = 800.0;
        private double elapsed = DEADLINE_MILLIS;

        void begin() { elapsed = 0.0; }
        void tick(double dt) { elapsed = Math.min(DEADLINE_MILLIS, elapsed + frameDelta(dt)); }
        boolean expired() { return elapsed >= DEADLINE_MILLIS; }
    }

    static final class Ripple {
        private double elapsed = RIPPLE_MILLIS;

        void trigger() { elapsed = 0.0; }
        void tick(double dt) { elapsed = Math.min(RIPPLE_MILLIS, elapsed + frameDelta(dt)); }
        boolean active() { return elapsed < RIPPLE_MILLIS; }
        double progress() { return elapsed / RIPPLE_MILLIS; }
        double pulse() { return active() ? Math.sin(Math.PI * progress()) : 0.0; }
        double expansion() { return 1.0 - Math.pow(1.0 - progress(), 3.0); }
    }

    /** Analytic underdamped return: immediate compression, a small rebound, then exact rest. */
    static final class Tap {
        private static final double FREQUENCY = 28.0;
        private static final double DECAY = 14.0;
        private static final double OSCILLATION = Math.sqrt(FREQUENCY * FREQUENCY - DECAY * DECAY);
        private final double direction;
        private double amount;
        private double velocity;
        private double elapsed = TAP_MILLIS;

        Tap(double direction) { this.direction = direction; }

        void trigger() {
            amount = 1.0;
            velocity = 0.0;
            elapsed = 0.0;
        }

        void tick(double deltaMillis) {
            if (elapsed >= TAP_MILLIS) return;
            double dt = frameDelta(deltaMillis);
            elapsed = Math.min(TAP_MILLIS, elapsed + dt);
            if (elapsed >= TAP_MILLIS) {
                amount = velocity = 0.0;
                return;
            }
            double seconds = dt / 1000.0;
            double cos = Math.cos(OSCILLATION * seconds);
            double sin = Math.sin(OSCILLATION * seconds);
            double decay = Math.exp(-DECAY * seconds);
            double old = amount;
            amount = decay * (old * cos + (velocity + DECAY * old) / OSCILLATION * sin);
            velocity = decay * (velocity * cos
                    - (DECAY * velocity + FREQUENCY * FREQUENCY * old) / OSCILLATION * sin);
        }

        double amount() { return amount; }
        double offsetX() { return direction * 3.4 * amount; }
        double scale() { return 1.0 - .16 * amount; }
    }

    /** A successful command takes precedence over a lagging room/mirror snapshot for one short window. */
    static final class Morph {
        private boolean initialized;
        private boolean targetPlaying;
        private boolean pendingResult;
        private double resultRemaining;
        private double pauseAmount;

        void accept(boolean available, boolean playing) {
            if (!available) return;
            if (!initialized) {
                initialized = true;
                pauseAmount = playing ? 0.0 : 1.0;
            }
            targetPlaying = playing;
            pendingResult = true;
            resultRemaining = RESULT_HOLD_MILLIS;
        }

        void tick(double deltaMillis, boolean playing) {
            double dt = frameDelta(deltaMillis);
            if (!initialized) {
                initialized = true;
                targetPlaying = playing;
                pauseAmount = playing ? 1.0 : 0.0;
                return;
            }
            if (pendingResult) {
                resultRemaining = Math.max(0.0, resultRemaining - dt);
                if (playing == targetPlaying || resultRemaining <= 0.0) pendingResult = false;
            }
            if (!pendingResult) targetPlaying = playing;
            double target = targetPlaying ? 1.0 : 0.0;
            pauseAmount += (target - pauseAmount) * (1.0 - Math.exp(-dt / 65.0));
            if (Math.abs(pauseAmount - target) < .001) pauseAmount = target;
        }

        double pauseAmount() { return pauseAmount; }
        double pauseAmount(boolean playing) { return initialized ? pauseAmount : playing ? 1.0 : 0.0; }
    }

    static double opticalOffsetX(Icon icon, double pauseAmount) {
        if (icon != Icon.PLAY_PAUSE) return 0.0;
        double p = Double.isFinite(pauseAmount) ? Math.max(0.0, Math.min(1.0, pauseAmount)) : 0.0;
        // Filled contours' area center, not their bounding box; pause bars need no offset.
        return (1.0 - p) * (16.0 + 3.0 * p) / (120.0 * (1.0 + p));
    }

    /** Two clockwise four-vertex contours, with the complete ink bounds centered at (0,0). */
    static double[] vertices(Icon icon, double pauseAmount) {
        if (icon != Icon.PLAY_PAUSE) {
            double[] skip = {-.5, -.5, .18, 0.0, .18, 0.0, -.5, .5,
                    .28, -.5, .5, -.5, .5, .5, .28, .5};
            if (icon == Icon.PREVIOUS) for (int i = 0; i < skip.length; i += 2) skip[i] = -skip[i];
            return skip;
        }
        double p = Double.isFinite(pauseAmount) ? Math.max(0.0, Math.min(1.0, pauseAmount)) : 0.0;
        // At play, the contours meet exactly and form one triangle. At pause, they are two bars.
        return new double[]{-.4, -.5, -.15 * p, -.25 - .25 * p, -.15 * p, .25 + .25 * p, -.4, .5,
                .15 * p, -.25 - .25 * p, .4, -.5 * p, .4, .5 * p, .15 * p, .25 + .25 * p};
    }
}
