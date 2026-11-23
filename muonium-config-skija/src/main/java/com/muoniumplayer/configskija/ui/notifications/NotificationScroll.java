package com.muoniumplayer.configskija.ui.notifications;

/** The source WheelSpring's critically damped glide, without a coroutine or a second clock. */
public final class NotificationScroll {
    private double target, position, velocity;
    private boolean active;
    public void push(double delta) {
        if (!active) { reset(); active = true; }
        if (delta * (target - position) < 0) { target = position; velocity = 0; }
        target += delta;
    }
    public double advance(double seconds) {
        if (!active || seconds <= 0) return 0;
        double omega = 2 / .06, decay = Math.exp(-omega * seconds), change = position - target;
        double linear = (velocity + change * omega) * seconds;
        double nextVelocity = (velocity - linear * omega) * decay;
        double next = target + (change + linear) * decay;
        if (Math.abs(target - next) < .5 && Math.abs(nextVelocity) < 20) {
            double step = target - position; reset(); return step;
        }
        double step = next - position; position = next; velocity = nextVelocity; return step;
    }
    public void reset() { target = position = velocity = 0; active = false; }
}
