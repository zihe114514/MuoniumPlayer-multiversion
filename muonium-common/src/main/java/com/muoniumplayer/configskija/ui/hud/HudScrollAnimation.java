package com.muoniumplayer.configskija.ui.hud;

/** animateScrollBy default spring; user scrolling cancels the animation rather than retargeting it. */
final class HudScrollAnimation {
    private double from, target, start, duration, density = 1;
    private boolean active;

    void begin(double from, double target, double now) {
        begin(from, target, now, 1);
    }

    void begin(double from, double target, double now, double density) {
        this.density = Math.max(.01, density);
        this.from = (float) (from * this.density);
        this.target = (float) (target * this.density);
        start = now;
        duration = HudMotion.settlingSeconds(0, (float) (this.target - this.from), 0, 1500, 1, .01);
        active = Math.abs(this.target - this.from) > .0001;
    }

    double value(double now) {
        if (!active) return target / density;
        if (now - start >= duration) {
            active = false;
            return target / density;
        }
        return (from
                        + HudMotion.sample(0, (float) (target - from), 0, now - start, 1500, 1)
                                .value())
                / density;
    }

    void cancel(double value) {
        active = false;
        from = target = value * density;
    }

    boolean active() {
        return active;
    }

    double target() {
        return target / density;
    }
}
