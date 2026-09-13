package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;

/**
 * Folia-style full-screen Automix transition drawing.
 *
 * <p>One ring is shared by both tracks. Its moving seam is the real handover clock: the outgoing arc
 * disappears over exactly the same wall-clock span as the audio fade while the incoming accent arc
 * grows behind it. The external tick marks the planned handover point and the whole ring breathes at
 * the outgoing track's measured tempo.</p>
 *
 * <p>The overlay is deliberately self-contained and input-transparent. It only samples the fade
 * started by {@code CloudMusic}, so a dropped frame or a screen change cannot move the seam away from
 * the audio.</p>
 */
public final class AutomixTransitionAnimation {
    private static final long ENTER_NANOS = 760_000_000L;
    private static final long EXIT_NANOS = 700_000_000L;
    private static final long DISMISS_NANOS = 520_000_000L;
    private static final long MIN_DURATION_MILLIS = 5_000L;
    private static final float RING_RADIUS = 66f;

    private static final Object LOCK = new Object();
    private static Run active;
    private static boolean paused;
    private static long pauseStartedAt;
    private static long pausedNanos;
    private static boolean dismissed;
    private static long dismissedAt;

    private AutomixTransitionAnimation() {
    }

    /** Starts the ring for a real Folia blend. Shorter/plain blends remain undecorated. */
    public static void start(long durationMillis, double crossover, double periodMillis) {
        if (durationMillis < MIN_DURATION_MILLIS) return;
        synchronized (LOCK) {
            active = new Run(System.nanoTime(), durationMillis,
                    clamp(crossover, .15, .85), clamp(periodMillis, 120.0, 4_000.0));
            paused = false;
            pauseStartedAt = 0L;
            pausedNanos = 0L;
            dismissed = false;
            dismissedAt = 0L;
        }
    }

    /** Demonstrates the ring when the HUD switch is enabled. */
    public static void preview() {
        start(10_000L, .55, 500.0);
    }

    /** Starts the short interrupt fade; used when a handover is replaced or cancelled. */
    public static void cancel() {
        synchronized (LOCK) {
            if (active == null || dismissed) return;
            dismissed = true;
            dismissedAt = System.nanoTime();
        }
    }

    /** Pauses the visual clock with the audio deck instead of letting it run through a pause. */
    public static void setPaused(boolean value) {
        synchronized (LOCK) {
            if (active == null || paused == value) return;
            long now = System.nanoTime();
            if (value) {
                paused = true;
                pauseStartedAt = now;
            } else {
                paused = false;
                if (pauseStartedAt > 0L) pausedNanos += Math.max(0L, now - pauseStartedAt);
                pauseStartedAt = 0L;
            }
        }
    }

    /** Draws above the normal HUD. Safe to call every frame. */
    public static void render() {
        Snapshot snapshot;
        synchronized (LOCK) {
            if (!AutomixSettings.isTransitionAnimationEnabled()) {
                active = null;
                return;
            }
            Run run = active;
            if (run == null) return;
            long now = System.nanoTime();
            long elapsed = elapsedNanos(run, now);
            long total = run.durationMillis * 1_000_000L;
            double opacity = transitionOpacity(elapsed, total);
            if (dismissed) {
                double left = 1.0 - clamp((now - dismissedAt) / (double) DISMISS_NANOS, 0.0, 1.0);
                opacity *= left;
                if (left <= 0.0) {
                    active = null;
                    return;
                }
            } else if (elapsed >= total + EXIT_NANOS) {
                active = null;
                return;
            }
            snapshot = new Snapshot(run, elapsed, total, opacity);
        }
        draw(snapshot);
    }

    private static void draw(Snapshot snapshot) {
        Canvas canvas = SkijaGfx.canvas();
        if (!SkijaGfx.active() || canvas == null) return;
        double width = RenderSystem.getWidth();
        double height = RenderSystem.getHeight();
        if (width <= 0.0 || height <= 0.0) return;

        double size = Math.max(104.0, Math.min(168.0, Math.min(width, height) * .42));
        double scale = size / 200.0;
        double elapsedSeconds = snapshot.elapsed / 1_000_000_000.0;
        double totalSeconds = snapshot.total / 1_000_000_000.0;
        double progress = clamp(elapsedSeconds / Math.max(.001, totalSeconds), 0.0, 1.0);
        double enterScale = .86 + .14 * smooth(Math.min(1.0,
                snapshot.elapsed / (double) ENTER_NANOS));
        double exitScale = snapshot.elapsed <= snapshot.total ? 1.0 : 1.0 + .06 * smooth(
                (snapshot.elapsed - snapshot.total) / (double) EXIT_NANOS);
        double breath = 1.0 + .018 * Math.sin(2.0 * Math.PI * elapsedSeconds
                / Math.max(.12, snapshot.run.periodMillis / 1000.0));
        int accent = NCMTheme.getAccentColor();
        if ((accent >>> 24) == 0) accent = 0xFF5FD3E8;

        int track = alpha(0xFFFFFFFF, .09 * snapshot.opacity);
        int neutral = alpha(0xFFFFFFFF, .42 * snapshot.opacity);
        int accentPaint = alpha(accent, snapshot.opacity);
        int accentSoft = alpha(accent, .23 * snapshot.opacity);
        double headAngle = Math.toRadians(-90.0 + progress * 360.0);
        double seamX = 100.0 + Math.cos(headAngle) * RING_RADIUS;
        double seamY = 100.0 + Math.sin(headAngle) * RING_RADIUS;
        double swapProgress = snapshot.run.crossover;
        double swapSeconds = totalSeconds * swapProgress;
        double before = elapsedSeconds <= swapSeconds
                ? smooth(elapsedSeconds / Math.max(.001, swapSeconds))
                : 1.0 - smooth((elapsedSeconds - swapSeconds) / Math.max(.001, totalSeconds - swapSeconds));
        double bloom = (.18 + .28 * before) * snapshot.opacity;
        double tickPulse = Math.max(0.0, 1.0 - Math.abs(elapsedSeconds - swapSeconds) / .9);
        double swapAngle = Math.toRadians(-90.0 + swapProgress * 360.0);
        double tickX = Math.cos(swapAngle);
        double tickY = Math.sin(swapAngle);

        SkijaGfx.pushAttrib();
        canvas.save();
        try {
            SkijaGfx.setBlendMode(BlendMode.SRC_OVER);
            canvas.translate((float) (width * .5), (float) (height * .5));
            canvas.scale((float) (scale * breath * enterScale * exitScale),
                    (float) (scale * breath * enterScale * exitScale));
            canvas.translate(-100f, -100f);

            // Soft bloom gathers toward the handover and lets go during the incoming half.
            SkijaShapes.radialRing(100.0, 100.0, 0.0, RING_RADIUS * 1.48,
                    alpha(accent, bloom), 0x00000000);

            drawArc(canvas, 0.0, 360.0, track, 1.25f);
            drawArc(canvas, -90.0 + progress * 360.0, 360.0 * (1.0 - progress), neutral, 1.5f);
            drawArc(canvas, -90.0, 360.0 * progress, accentPaint, 2.25f);

            try (Paint tick = new Paint()) {
                tick.setAntiAlias(true);
                tick.setMode(PaintMode.STROKE);
                tick.setStrokeWidth(1.5f);
                tick.setStrokeCap(PaintStrokeCap.ROUND);
                tick.setColor(alpha(accent, (.22 + .63 * tickPulse) * snapshot.opacity));
                double scaleTick = 1.0 + .35 * tickPulse;
                canvas.drawLine(
                        (float) (100.0 + tickX * (RING_RADIUS + 8.0)),
                        (float) (100.0 + tickY * (RING_RADIUS + 8.0)),
                        (float) (100.0 + tickX * (RING_RADIUS + 8.0 + 7.0 * scaleTick)),
                        (float) (100.0 + tickY * (RING_RADIUS + 8.0 + 7.0 * scaleTick)),
                        tick);
            }

            try (Paint glow = new Paint(); Paint head = new Paint()) {
                glow.setAntiAlias(true);
                glow.setColor(accentSoft);
                try (MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 5.0f, true)) {
                    glow.setMaskFilter(blur);
                    canvas.drawCircle((float) seamX, (float) seamY, 7.0f, glow);
                }
                head.setAntiAlias(true);
                head.setColor(accentPaint);
                canvas.drawCircle((float) seamX, (float) seamY, 2.6f, head);
            }
        } finally {
            canvas.restore();
            SkijaGfx.popAttrib();
        }
    }

    private static void drawArc(Canvas canvas, double startAngle, double sweepAngle,
                                int color, float width) {
        if (sweepAngle <= .01 || (color >>> 24) == 0) return;
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeCap(PaintStrokeCap.ROUND);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            canvas.drawArc(100f - RING_RADIUS, 100f - RING_RADIUS,
                    100f + RING_RADIUS, 100f + RING_RADIUS,
                    (float) startAngle, (float) sweepAngle, false, paint);
        }
    }

    private static long elapsedNanos(Run run, long now) {
        long elapsed = now - run.startedAt;
        if (paused && pauseStartedAt > 0L) elapsed -= Math.max(0L, now - pauseStartedAt);
        return Math.max(0L, elapsed - pausedNanos);
    }

    private static double transitionOpacity(long elapsed, long total) {
        double enter = smooth(Math.min(1.0, elapsed / (double) ENTER_NANOS));
        if (elapsed >= total) {
            return smooth(Math.max(0.0, 1.0 - (elapsed - total) / (double) EXIT_NANOS));
        }
        return enter;
    }

    private static int alpha(int argb, double alpha) {
        int sourceAlpha = (argb >>> 24) & 0xFF;
        int value = (int) Math.round(sourceAlpha * clamp(alpha, 0.0, 1.0));
        return (value << 24) | (argb & 0x00FFFFFF);
    }

    private static double smooth(double value) {
        double t = clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (Double.isNaN(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Run {
        final long startedAt;
        final long durationMillis;
        final double crossover;
        final double periodMillis;

        Run(long startedAt, long durationMillis, double crossover, double periodMillis) {
            this.startedAt = startedAt;
            this.durationMillis = durationMillis;
            this.crossover = crossover;
            this.periodMillis = periodMillis;
        }
    }

    private static final class Snapshot {
        final Run run;
        final long elapsed;
        final long total;
        final double opacity;

        Snapshot(Run run, long elapsed, long total, double opacity) {
            this.run = run;
            this.elapsed = elapsed;
            this.total = total;
            this.opacity = opacity;
        }
    }
}
