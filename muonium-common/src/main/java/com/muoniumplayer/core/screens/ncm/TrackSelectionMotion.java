package com.muoniumplayer.core.screens.ncm;

/** 选曲动效的纯数值时钟。用纳秒而非帧数，同一次请求从准备到起播不会重复滑入标题。 */
public final class TrackSelectionMotion {
    private String key;
    private long request;
    private long selectedAt;
    private long settledAt;
    private boolean preparing;
    private boolean initialized;
    private double elapsed = 10_000;
    private double settledElapsed = 10_000;
    private double phase;

    public void update(String nextKey, long nextRequest, boolean nextPreparing, long now, double speed) {
        boolean changed = !initialized || !nextKey.equals(key)
                || (nextRequest != 0L && nextRequest != request);
        if (changed) {
            selectedAt = nextPreparing && nextRequest != 0L ? nextRequest
                    : initialized ? now : now - 10_000_000_000L;
            settledAt = 0L;
            key = nextKey;
            request = nextRequest;
        } else if (preparing && !nextPreparing) {
            settledAt = now;
        }
        initialized = true;
        preparing = nextPreparing;
        double rate = Double.isFinite(speed) ? Math.max(.1, Math.min(1.0, speed / 3.0)) : 1.0;
        elapsed = Math.max(0.0, (now - selectedAt) / 1_000_000.0) * rate;
        settledElapsed = settledAt == 0L ? 10_000 : Math.max(0.0, (now - settledAt) / 1_000_000.0) * rate;
        phase = Math.max(0.0, (now - selectedAt) / 1_000_000_000.0);
    }

    public double reveal() { return 1.0 - Math.pow(1.0 - clamp(elapsed / 320.0), 3); }
    public double offset() { return (1.0 - reveal()) * 7.0; }
    public float contentAlpha() { return (float) (.72 + .28 * reveal()); }
    public double burst() { return 1.0 - clamp(elapsed / 720.0); }
    public double sweep() { return clamp(elapsed / 620.0); }
    public double settledGlow() { return Math.sin(clamp(settledElapsed / 540.0) * Math.PI); }
    public double travel() { return (phase / 1.35) % 1.0; }
    public double pulse(int index) { return .5 + .5 * Math.sin(phase * 5.6 - index * .85); }
    public boolean preparing() { return preparing; }
    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
}
