package com.muoniumplayer.core.screens.ncm.panels;

/** 重建侧栏时的 FLIP 位移：布局立即生效，视觉残差平滑归零，不改变滚动和点击几何。 */
final class SidebarLayoutMotion {
    private static final double MAX_TRAVEL = 48.0;
    static final class Snapshot {
        final double y;
        final float alpha;
        Snapshot(double y, float alpha) {
            this.y = Double.isFinite(y) ? y : 0.0;
            this.alpha = Float.isFinite(alpha) ? Math.max(0f, Math.min(1f, alpha)) : 1f;
        }
    }
    private final Snapshot previous;
    private final boolean entering;
    private long startedAt = -1L;
    private double residual;
    private double offset;
    private float alpha;
    private boolean finished;

    SidebarLayoutMotion(Snapshot previous) {
        this.previous = previous;
        this.entering = previous == null;
        this.alpha = entering ? 0f : previous.alpha;
    }

    void tick(long now, double targetY, double durationMillis) {
        if (finished) return;
        if (startedAt < 0L) {
            startedAt = now;
            residual = entering ? 6.0 : Math.max(-MAX_TRAVEL, Math.min(MAX_TRAVEL, previous.y - targetY));
        }
        double p = Math.max(0.0, Math.min(1.0, (now - startedAt) / Math.max(1.0, durationMillis)));
        double eased = 1.0 - Math.pow(1.0 - p, 3.0);
        offset = residual * (1.0 - eased);
        float from = entering ? 0f : previous.alpha;
        alpha = (float) (from + (1.0 - from) * eased);
        finished = p >= 1.0 || (!entering && Math.abs(residual) < .01 && from >= .999f);
        if (finished) { offset = 0.0; alpha = 1f; }
    }
    double offset() { return offset; }
    float alpha() { return alpha; }
    boolean isFinished() { return finished; }

    /** 主页/本地歌单跨音源不变；发现、账户、状态行必须归当前内容源。 */
    static String rowKey(String key, String source) {
        if (key.equals("nav:home") || key.startsWith("local:") || key.startsWith("imported:")
                || key.equals("section:local") || key.equals("hint:local-empty")) return key;
        return source + ':' + key;
    }
}
