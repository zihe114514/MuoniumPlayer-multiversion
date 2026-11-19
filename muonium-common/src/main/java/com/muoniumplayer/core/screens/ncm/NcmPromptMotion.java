package com.muoniumplayer.core.screens.ncm;

/** 小幅状态补间使用真时间；稳定后不再分配或启动新的动画。 */
public final class NcmPromptMotion {
    private long stamp = -1;
    private double value;

    public NcmPromptMotion(double initial) {
        value = initial;
    }

    public double update(double target, double halfLifeMillis, long now) {
        if (stamp < 0) {
            stamp = now;
            return value;
        }
        double elapsed = Math.max(0, Math.min(100, (now - stamp) / 1_000_000.0));
        stamp = now;
        double amount = 1 - Math.pow(.5, elapsed / Math.max(1, halfLifeMillis));
        value += (target - value) * amount;
        if (Math.abs(target - value) < .001) value = target;
        return value;
    }

    public double value() { return value; }

    public static double reveal(long started, long now, double durationMillis) {
        double t = Math.max(0, Math.min(1, (now - started) / Math.max(1, durationMillis * 1_000_000.0)));
        return t * t * (3 - 2 * t);
    }
}
