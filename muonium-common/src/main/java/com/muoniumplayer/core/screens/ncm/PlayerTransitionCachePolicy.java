package com.muoniumplayer.core.screens.ncm;

/** 转场缓存的纯数据预算；运动中不重拍，稳定帧的捕获也必须节流。 */
final class PlayerTransitionCachePolicy {
    static final long MAX_PIXELS = 4_194_304L;
    static final long CAPTURE_INTERVAL_NANOS = 250_000_000L;
    static final long MAX_AGE_NANOS = 750_000_000L;
    private long lastCaptureNanos;
    private boolean captured;
    private double compositeNanos;

    boolean canCapture(long now, int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS
                && (!this.captured || now - this.lastCaptureNanos >= CAPTURE_INTERVAL_NANOS);
    }

    void captured(long now) {
        this.lastCaptureNanos = now;
        this.captured = true;
    }

    boolean isFresh(long now) {
        return this.captured && now >= this.lastCaptureNanos
                && now - this.lastCaptureNanos <= MAX_AGE_NANOS;
    }

    void observeComposite(long nanos) {
        this.compositeNanos = this.compositeNanos == 0.0 ? Math.max(0L, nanos)
                : this.compositeNanos * .85 + Math.max(0L, nanos) * .15;
    }

    boolean canSoften(long pixels, double frameMillis) {
        // 不用世界 FPS 单独降级；同时出现长帧和本层提交昂贵才摘掉附加采样。
        return pixels <= MAX_PIXELS / 2 && !(frameMillis > 40.0 && this.compositeNanos > 2_000_000.0);
    }

    void reset() {
        this.lastCaptureNanos = 0L;
        this.captured = false;
        this.compositeNanos = 0.0;
    }
}
