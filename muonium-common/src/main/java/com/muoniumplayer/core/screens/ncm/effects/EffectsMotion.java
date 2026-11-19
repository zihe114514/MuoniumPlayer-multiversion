package com.muoniumplayer.core.screens.ncm.effects;

/** 工作台动效：效果变化有界，空间弹簧按真实时间求解并保留改向速度。 */
public final class EffectsMotion {

    private double from;
    private double target;
    private long started;
    private long duration;

    public EffectsMotion(double value) {
        from = target = value;
    }

    public double value(long now) {
        if (duration <= 0 || now - started >= duration) return target;
        double t = Math.max(0, (now - started) / (double) duration);
        return from + (target - from) * emphasized(t);
    }

    public void retarget(double value, long now, double milliseconds) {
        if (!Double.isFinite(value) || !Double.isFinite(milliseconds)) {
            throw new IllegalArgumentException("动画目标必须是有限数值");
        }
        if (Double.compare(value, target) == 0) return;
        from = value(now);
        target = value;
        started = now;
        duration = Math.max(0, Math.round(milliseconds * 1_000_000));
    }

    public void snap(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("非法动画值");
        from = target = value;
        duration = 0;
    }

    public boolean settled(long now) { return duration <= 0 || now - started >= duration; }

    public EffectsMotion copy() {
        EffectsMotion copy = new EffectsMotion(from);
        copy.target = target;
        copy.started = started;
        copy.duration = duration;
        return copy;
    }

    /** Expressive 的空间/效果分工；参数来自 AndroidX 的 ExpressiveMotionTokens v0_14_0。 */
    public enum Scheme {
        FAST_SPATIAL(.6, 800), DEFAULT_SPATIAL(.8, 380), SLOW_SPATIAL(.8, 200),
        FAST_EFFECTS(1, 3800), DEFAULT_EFFECTS(1, 1600), SLOW_EFFECTS(1, 800);

        final double damping, stiffness;
        Scheme(double damping, double stiffness) { this.damping = damping; this.stiffness = stiffness; }
    }

    /** 单位质量弹簧的解析解。速度单位为每秒；采样不修改状态，掉帧和中途改向都不重置速度。 */
    public static final class Spring {
        private static final double REST = .001;
        private final Scheme scheme;
        private double from, target, initialVelocity, frequency;
        private double timeScale = 1;
        private long started, settles;

        public Spring(double value, Scheme scheme) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("非法弹簧值");
            this.scheme = java.util.Objects.requireNonNull(scheme, "scheme");
            from = target = value;
            frequency = Math.sqrt(scheme.stiffness);
        }

        public double value(long now) {
            if (settled(now)) return target;
            return target + displacement(Math.max(0, now - started) / 1_000_000_000.0);
        }

        public double velocity(long now) {
            if (settled(now)) return 0;
            double t = Math.max(0, now - started) / 1_000_000_000.0;
            double a = from - target, decay = scheme.damping * frequency;
            if (scheme.damping == 1) {
                double b = initialVelocity + frequency * a;
                return (b - frequency * (a + b * t)) * Math.exp(-frequency * t);
            }
            double oscillation = frequency * Math.sqrt(1 - scheme.damping * scheme.damping);
            double b = (initialVelocity + decay * a) / oscillation;
            double sin = Math.sin(oscillation * t), cos = Math.cos(oscillation * t);
            return Math.exp(-decay * t) * (oscillation * (b * cos - a * sin) - decay * (a * cos + b * sin));
        }

        public void retarget(double value, long now, double timeScale) {
            if (!Double.isFinite(value) || !Double.isFinite(timeScale) || timeScale < 0) {
                throw new IllegalArgumentException("弹簧目标和时间倍率必须有效");
            }
            if (timeScale == 0) { snap(value); return; }
            if (Double.compare(target, value) == 0 && this.timeScale == timeScale) return;
            double current = value(now), speed = velocity(now);
            from = current;
            initialVelocity = speed;
            target = value;
            this.timeScale = timeScale;
            frequency = Math.sqrt(scheme.stiffness) / timeScale;
            started = now;
            settles = Math.max(0, Math.round(restTime() * 1_000_000_000.0));
        }

        public void snap(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("非法弹簧值");
            from = target = value;
            initialVelocity = 0;
            settles = 0;
        }

        public boolean settled(long now) { return settles == 0 || now - started >= settles; }

        public Spring copy() {
            Spring copy = new Spring(from, scheme);
            copy.target = target;
            copy.initialVelocity = initialVelocity;
            copy.frequency = frequency;
            copy.timeScale = timeScale;
            copy.started = started;
            copy.settles = settles;
            return copy;
        }

        private double displacement(double t) {
            double a = from - target;
            if (scheme.damping == 1) {
                return (a + (initialVelocity + frequency * a) * t) * Math.exp(-frequency * t);
            }
            double decay = scheme.damping * frequency;
            double oscillation = frequency * Math.sqrt(1 - scheme.damping * scheme.damping);
            double b = (initialVelocity + decay * a) / oscillation;
            return Math.exp(-decay * t) * (a * Math.cos(oscillation * t) + b * Math.sin(oscillation * t));
        }

        /** 用衰减包络锁定尾端，不在过零点误判静止，也不由调用帧数决定何时结束。 */
        private double restTime() {
            double a = Math.abs(from - target);
            if (scheme.damping < 1) {
                double decay = scheme.damping * frequency;
                double oscillation = frequency * Math.sqrt(1 - scheme.damping * scheme.damping);
                double b = (initialVelocity + decay * (from - target)) / oscillation;
                double envelope = Math.hypot(a, b);
                return envelope <= REST ? 0 : Math.log(envelope / REST) / decay;
            }
            double b = Math.abs(initialVelocity + frequency * (from - target));
            if (a <= REST && Math.abs(initialVelocity) <= REST * frequency) return 0;
            double lo = 1 / frequency, hi = lo;
            while (restEnvelope(a, b, hi) > REST) hi *= 2;
            for (int i = 0; i < 30; i++) {
                double mid = (lo + hi) * .5;
                if (restEnvelope(a, b, mid) > REST) lo = mid; else hi = mid;
            }
            return hi;
        }

        private double restEnvelope(double a, double b, double t) {
            return (a + b * t + b / frequency) * Math.exp(-frequency * t);
        }
    }

    /** 首次入场沿宿主同一条可逆时钟取样；不另开一套浮层生命周期。 */
    public static double spatial(double progress, Scheme scheme) {
        if (!Double.isFinite(progress)) throw new IllegalArgumentException("非法动画进度");
        if (progress <= 0) return 0;
        if (progress >= 1) return 1;
        double frequency = Math.sqrt(scheme.stiffness), damping = scheme.damping;
        if (damping == 1) return emphasized(progress);
        double oscillation = Math.sqrt(1 - damping * damping);
        double duration = Math.log(1 / (.001 * oscillation)) / (damping * frequency);
        double t = progress * duration;
        return 1 - Math.exp(-damping * frequency * t) * (Math.cos(frequency * oscillation * t)
                + damping / oscillation * Math.sin(frequency * oscillation * t));
    }

    /** 保留给滚动和效果过渡的 (0.2, 0, 0, 1)，按时间轴反解，不把贝塞尔参数当成时间。 */
    public static double emphasized(double progress) {
        if (!Double.isFinite(progress)) throw new IllegalArgumentException("非法动画进度");
        if (progress <= 0) return 0;
        if (progress >= 1) return 1;
        double lo = 0, hi = 1;
        for (int i = 0; i < 12; i++) {
            double t = (lo + hi) * .5;
            double x = 3 * (1 - t) * (1 - t) * t * .2 + t * t * t;
            if (x < progress) lo = t; else hi = t;
        }
        double t = (lo + hi) * .5;
        return t * t * (3 - 2 * t);
    }

    public static final class Scroll {
        private final EffectsMotion position = new EffectsMotion(0);
        private double target;
        private double max;

        public double value(long now) { return position.value(now); }
        public double target() { return target; }
        public double max() { return max; }

        public void bounds(double content, double viewport, long now) {
            max = Math.max(0, content - viewport);
            double next = Math.max(0, Math.min(max, target));
            if (next != target || position.value(now) > max) {
                target = next;
                position.snap(next);
            }
        }

        public void move(double delta, long now, double milliseconds) {
            target = Math.max(0, Math.min(max, target + delta));
            position.retarget(target, now, milliseconds);
        }

        public void drag(double delta, long now) {
            target = Math.max(0, Math.min(max, target + delta));
            position.snap(target);
        }

        public void reset() { target = 0; position.snap(0); }
    }
}
