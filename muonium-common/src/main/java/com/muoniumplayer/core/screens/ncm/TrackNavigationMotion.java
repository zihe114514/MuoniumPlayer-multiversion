package com.muoniumplayer.core.screens.ncm;

/** 全屏播放页上一曲/下一曲的短暂方向反馈时钟。 */
public final class TrackNavigationMotion {
    private static final long DURATION_NANOS = 720_000_000L;
    private int direction;
    private long startedAt;

    public void trigger(int direction) {
        if (direction == 0) return;
        this.direction = direction < 0 ? -1 : 1;
        this.startedAt = System.nanoTime();
    }

    public boolean active() {
        return direction != 0 && progress(System.nanoTime()) < 1.0;
    }

    public int direction() {
        return direction;
    }

    public double progress(long now) {
        if (startedAt == 0L) return 1.0;
        return clamp((now - startedAt) / (double) DURATION_NANOS);
    }

    public double eased(long now) {
        double t = progress(now);
        return t * t * (3.0 - 2.0 * t);
    }

    public float alpha(long now) {
        double t = progress(now);
        double enter = Math.min(1.0, t / .18);
        double exit = t <= .58 ? 1.0 : 1.0 - (t - .58) / .42;
        return (float) (Math.max(0.0, Math.min(1.0, enter))
                * Math.max(0.0, Math.min(1.0, exit)));
    }

    public double travel(long now) {
        return direction * (1.0 - eased(now)) * 18.0;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
