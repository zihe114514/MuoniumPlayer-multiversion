package com.muoniumplayer.core.screens.ncm;

import java.util.List;

/**
 * qplayer / AMLL per-line lyric scroll spring.
 *
 * This is deliberately not a generic animation helper: it follows qplayer's
 * LyricRenderer model. Every visible line owns a position and velocity, unseen
 * lines are initialized at their rest position, and only the trailing side of
 * an anchor change is delayed. Layout targets are supplied every frame, so an
 * expanding interlude slot can move the column without resetting the springs.
 */
final class LyricScrollMotion {

    private static final int VISIBLE_RADIUS = 16;
    private static final double MAX_FRAME_SECONDS = 0.05;
    private static final double LINE_DELAY_S = 0.05;
    private static final double LINE_DELAY_DECAY = 1.05;

    private static final double NORMAL_STIFFNESS = 65.0;
    private static final double NORMAL_DAMPING = 11.0;
    private static final double INTERLUDE_STIFFNESS = 55.0;
    private static final double INTERLUDE_DAMPING = 10.1;
    private static final double SEEK_STIFFNESS = 180.0;
    private static final double SEEK_DAMPING = 28.0;

    private double[] lineCurTop = new double[0];
    private double[] lineVelTop = new double[0];
    private double[] cascadeDelayBuf = new double[0];
    private int prevVisStart;
    private int prevVisEnd;
    private boolean lineSpringInit;

    private long springLastNs;
    private long springAnchorChangeNs;
    private int springAnchorPrev = Integer.MIN_VALUE;
    private int cascadeDir = 1;
    private double lastPlayheadMs = -1.0;

    private int trackSize = -1;
    private long trackFirstTimestamp = Long.MIN_VALUE;
    private long trackLastTimestamp = Long.MIN_VALUE;

    /** qplayer's rigid explicit-seek spring, expressed in rest-offset space. */
    private boolean seekSpringActive;
    private double seekSpringValue;
    private double seekSpringVelocity;
    private double seekSpringTarget;

    void reset() {
        lineCurTop = new double[0];
        lineVelTop = new double[0];
        cascadeDelayBuf = new double[0];
        prevVisStart = 0;
        prevVisEnd = 0;
        lineSpringInit = false;
        springLastNs = 0L;
        springAnchorChangeNs = 0L;
        springAnchorPrev = Integer.MIN_VALUE;
        cascadeDir = 1;
        lastPlayheadMs = -1.0;
        trackSize = -1;
        trackFirstTimestamp = Long.MIN_VALUE;
        trackLastTimestamp = Long.MIN_VALUE;
        seekSpringActive = false;
        seekSpringValue = 0.0;
        seekSpringVelocity = 0.0;
        seekSpringTarget = 0.0;
    }

    void apply(List<LyricLine> lines, double[] docTop, double restOffset, int anchorIndex,
               double playheadMs, boolean interlude, boolean manualScroll) {
        int count = Math.min(lines.size(), docTop.length);
        if (count <= 0 || anchorIndex < 0 || anchorIndex >= count) return;

        if (trackChanged(lines, count)) {
            lineCurTop = new double[0];
            lineVelTop = new double[0];
            cascadeDelayBuf = new double[0];
            prevVisStart = 0;
            prevVisEnd = 0;
            lineSpringInit = false;
            springAnchorPrev = Integer.MIN_VALUE;
        }

        if (lineCurTop.length != count) {
            lineCurTop = new double[count];
            lineVelTop = new double[count];
            lineSpringInit = false;
        }
        if (cascadeDelayBuf.length != count) cascadeDelayBuf = new double[count];

        long nowNs = System.nanoTime();
        double dt = springLastNs == 0L ? 0.0 : (nowNs - springLastNs) / 1_000_000_000.0;
        if (dt > MAX_FRAME_SECONDS) dt = MAX_FRAME_SECONDS;
        if (dt < 0.0) dt = 0.0;
        springLastNs = nowNs;

        boolean seek = lastPlayheadMs >= 0.0 && isHardClockDiscontinuity(lastPlayheadMs, playheadMs);
        lastPlayheadMs = playheadMs;
        if (seek && !seekSpringActive) {
            int seekFromAnchor = springAnchorPrev != Integer.MIN_VALUE && springAnchorPrev < count
                    ? springAnchorPrev : anchorIndex;
            seekSpringValue = lineSpringInit && seekFromAnchor >= 0 && seekFromAnchor < lineCurTop.length
                    ? lineCurTop[seekFromAnchor] - docTop[seekFromAnchor] : restOffset;
            seekSpringVelocity = 0.0;
            seekSpringActive = true;
        }
        if (seekSpringActive) seekSpringTarget = restOffset;

        int start = Math.max(0, anchorIndex - VISIBLE_RADIUS);
        int end = Math.min(count, anchorIndex + VISIBLE_RADIUS + 1);
        if (anchorIndex != springAnchorPrev) {
            if (springAnchorPrev != Integer.MIN_VALUE) {
                cascadeDir = anchorIndex > springAnchorPrev ? 1 : -1;
            }
            springAnchorPrev = anchorIndex;
            springAnchorChangeNs = nowNs;
        }
        double sinceAnchorChange = (nowNs - springAnchorChangeNs) / 1_000_000_000.0;

        if (seekSpringActive) {
            stepGlobalSpring(dt, SEEK_STIFFNESS, SEEK_DAMPING);
            boolean arrived = Math.abs(seekSpringVelocity) < 0.01
                    && Math.abs(seekSpringValue - seekSpringTarget) < 0.05;
            for (int i = start; i < end; i++) {
                double top = (arrived ? restOffset : seekSpringValue) + docTop[i];
                lineCurTop[i] = top;
                lineVelTop[i] = 0.0;
                lines.get(i).posY = top;
            }
            if (arrived) seekSpringActive = false;
            prevVisStart = start;
            prevVisEnd = end;
            lineSpringInit = true;
            return;
        }

        double stiffness = interlude ? INTERLUDE_STIFFNESS : NORMAL_STIFFNESS;
        double damping = interlude ? INTERLUDE_DAMPING : NORMAL_DAMPING;
        qplayerCascadeDelays(count, start, end, anchorIndex,
                springAnchorPrev != Integer.MIN_VALUE && !manualScroll);

        for (int i = start; i < end; i++) {
            double restTop = restOffset + docTop[i];
            LyricLine line = lines.get(i);

            if (manualScroll || !lineSpringInit || i < prevVisStart || i >= prevVisEnd) {
                lineCurTop[i] = restTop;
                lineVelTop[i] = 0.0;
            } else if (sinceAnchorChange >= cascadeDelayBuf[i] && dt > 0.0) {
                stepLineSpring(i, restTop, dt, stiffness, damping);
            }
            line.posY = lineCurTop[i];
        }

        prevVisStart = start;
        prevVisEnd = end;
        lineSpringInit = true;
    }

    /** qplayer cascade: leading side is zero-delay; trailing side starts at 50ms and decays by 1.05x. */
    private void qplayerCascadeDelays(int count, int start, int end, int anchorIndex, boolean enabled) {
        java.util.Arrays.fill(cascadeDelayBuf, 0, count, 0.0);
        if (!enabled) return;

        double delay = 0.0;
        double step = LINE_DELAY_S;
        if (cascadeDir >= 0) {
            for (int i = Math.max(start, anchorIndex + 1); i < end; i++) {
                delay += step;
                step /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = delay;
            }
        } else {
            for (int i = Math.min(end - 1, anchorIndex - 1); i >= start; i--) {
                delay += step;
                step /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = delay;
            }
        }
    }

    private void stepGlobalSpring(double dt, double stiffness, double damping) {
        if (dt <= 0.0) return;
        int steps = 1 + (int) (dt / 0.008);
        double sub = dt / steps;
        for (int step = 0; step < steps; step++) {
            double acceleration = -stiffness * (seekSpringValue - seekSpringTarget)
                    - damping * seekSpringVelocity;
            seekSpringVelocity += acceleration * sub;
            seekSpringValue += seekSpringVelocity * sub;
        }
        if (Math.abs(seekSpringVelocity) < 0.01
                && Math.abs(seekSpringValue - seekSpringTarget) < 0.05) {
            seekSpringValue = seekSpringTarget;
            seekSpringVelocity = 0.0;
        }
    }

    private void stepLineSpring(int i, double target, double dt, double stiffness, double damping) {
        double value = lineCurTop[i];
        double velocity = lineVelTop[i];
        int steps = 1 + (int) (dt / 0.008);
        double sub = dt / steps;
        for (int step = 0; step < steps; step++) {
            double acceleration = -stiffness * (value - target) - damping * velocity;
            velocity += acceleration * sub;
            value += velocity * sub;
        }
        if (Math.abs(velocity) < 0.01 && Math.abs(value - target) < 0.05) {
            value = target;
            velocity = 0.0;
        }
        lineCurTop[i] = value;
        lineVelTop[i] = velocity;
    }

    private boolean trackChanged(List<LyricLine> lines, int count) {
        long first = count > 0 ? lines.get(0).timestamp : Long.MIN_VALUE;
        long last = count > 0 ? lines.get(count - 1).timestamp : Long.MIN_VALUE;
        if (count == trackSize && first == trackFirstTimestamp && last == trackLastTimestamp) return false;
        trackSize = count;
        trackFirstTimestamp = first;
        trackLastTimestamp = last;
        return true;
    }

    private static boolean isHardClockDiscontinuity(double oldMs, double newMs) {
        double delta = newMs - oldMs;
        return Math.abs(delta) >= 1500.0;
    }
}
