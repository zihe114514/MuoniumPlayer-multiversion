package com.muoniumplayer.core.screens.ncm;

import java.util.Arrays;
import java.util.Locale;

/** 可选的转场 CPU 观测；不把提交耗时当作 GPU 完成或实际上屏时间。 */
final class PlayerTransitionMetrics {
    static final int PREPARE = 0, CONTENT = 1, CAPTURE = 2, COMPOSITE = 3;
    private static final boolean ENABLED = Boolean.getBoolean("muonium.player.transition.profile");
    private final long[] workNanos = new long[4];
    private final long[] intervals = new long[256];
    private long startedAt, previousFrame;
    private int frameCount, recordedFrames, cachedFrames;
    private boolean active, opening;

    void begin(boolean opening) {
        if (!ENABLED) return;
        end();
        this.opening = opening;
        this.active = true;
        this.startedAt = System.nanoTime();
        this.previousFrame = 0L;
        this.frameCount = this.recordedFrames = this.cachedFrames = 0;
        Arrays.fill(this.workNanos, 0L);
    }

    long start() {
        return ENABLED && this.active ? System.nanoTime() : 0L;
    }

    long frameStart() {
        long now = start();
        if (now == 0L) return 0L;
        if (this.previousFrame != 0L && this.recordedFrames < this.intervals.length) {
            this.intervals[this.recordedFrames++] = Math.max(0L, now - this.previousFrame);
        }
        this.previousFrame = now;
        ++this.frameCount;
        return now;
    }

    void finish(int phase, long started) {
        if (started != 0L && this.active) {
            this.workNanos[phase] += Math.max(0L, System.nanoTime() - started);
        }
    }

    void cachedFrame() {
        if (ENABLED && this.active) ++this.cachedFrames;
    }

    void frameEnd(long started) {
        if (started != 0L && this.opening && !PlayerIslandTransition.needsFrameBudget()
                && System.nanoTime() - this.startedAt >= 1_000_000_000L) end();
    }

    void end() {
        if (!ENABLED || !this.active) return;
        this.active = false;
        long now = System.nanoTime();
        Arrays.sort(this.intervals, 0, this.recordedFrames);
        double p95 = percentile(.95), p99 = percentile(.99);
        System.out.printf(Locale.ROOT,
                "[MuoniumPlayer/PlayerTransition] %s frames=%d cached=%d elapsed=%.2fms "
                        + "interval-p95=%.2fms interval-p99=%.2fms "
                        + "prepare=%.2fms content=%.2fms capture=%.2fms composite=%.2fms (CPU only)%n",
                this.opening ? "open" : "close", this.frameCount, this.cachedFrames,
                (now - this.startedAt) / 1_000_000.0, p95, p99,
                this.workNanos[PREPARE] / 1_000_000.0, this.workNanos[CONTENT] / 1_000_000.0,
                this.workNanos[CAPTURE] / 1_000_000.0, this.workNanos[COMPOSITE] / 1_000_000.0);
    }

    private double percentile(double fraction) {
        if (this.recordedFrames == 0) return 0.0;
        return this.intervals[Math.max(0, (int) Math.ceil(this.recordedFrames * fraction) - 1)] / 1_000_000.0;
    }
}
