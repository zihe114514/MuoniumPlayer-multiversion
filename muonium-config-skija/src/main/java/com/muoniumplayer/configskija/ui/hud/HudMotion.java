package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.ui.Motion;
import com.muoniumplayer.configskija.ui.Theme;

import java.util.HashMap;
import java.util.Map;

/** Resolved animation-core 1.13 alpha02 spring arithmetic; no UI backend dependency. */
public final class HudMotion {
    public static final float DEFAULT_STIFFNESS = 1500,
            DEFAULT_DAMPING = 1,
            TRANSITION_STIFFNESS = 400;

    public record Sample(float value, float velocity) {}

    private static final class Track {
        float from, target, velocity;
        double start, duration;
        float stiffness, damping;
        boolean finite;
    }

    private final Map<String, Track> tracks = new HashMap<>();

    private static final class ColorTrack {
        int targetArgb;
        float[] from, target, velocity = new float[4];
        double start, duration;
    }

    private final Map<String, ColorTrack> colors = new HashMap<>();

    public void seed(String key, double value, double time) {
        Track t = new Track();
        t.from = t.target = (float) value;
        t.start = time;
        t.stiffness = DEFAULT_STIFFNESS;
        t.damping = 1;
        tracks.put(key, t);
    }

    public double spring(String key, double target, double time) {
        return spring(key, target, time, DEFAULT_STIFFNESS, 1);
    }

    public double spring(String key, double target, double time, float stiffness, float damping) {
        Track t = tracks.get(key);
        if (t == null) {
            seed(key, target, time);
            t = tracks.get(key);
            t.stiffness = stiffness;
            t.damping = damping;
            return target;
        }
        Sample s =
                sample(
                        t.from,
                        t.target,
                        t.velocity,
                        Math.max(0, time - t.start),
                        t.stiffness,
                        t.damping);
        if (Float.compare(t.target, (float) target) != 0) {
            t.from = s.value();
            t.velocity = s.velocity();
            t.target = (float) target;
            t.start = time;
            t.stiffness = stiffness;
            t.damping = damping;
        }
        return s.value();
    }

    public double animate(
            String key,
            double target,
            double time,
            float stiffness,
            float damping,
            double threshold) {
        Track t = tracks.get(key);
        if (t == null) {
            seed(key, target, time);
            t = tracks.get(key);
            t.stiffness = stiffness;
            t.damping = damping;
            t.finite = true;
            t.duration = 0;
            return target;
        }
        double elapsed = Math.max(0, time - t.start);
        Sample current =
                t.finite && elapsed >= t.duration
                        ? new Sample(t.target, 0)
                        : sample(t.from, t.target, t.velocity, elapsed, t.stiffness, t.damping);
        if (Float.compare(t.target, (float) target) != 0) {
            t.from = current.value();
            t.velocity = current.velocity();
            t.target = (float) target;
            t.start = time;
            t.stiffness = stiffness;
            t.damping = damping;
            t.finite = true;
            t.duration =
                    settlingSeconds(t.from, t.target, t.velocity, stiffness, damping, threshold);
        }
        return current.value();
    }

    public int color(String key, int target, double time) {
        return colorValue("spring/" + key, target, time, 0, Motion.Easing.FAST_OUT_SLOW_IN);
    }

    public int tweenColor(String key, int target, double time, double duration) {
        return tweenColor(key, target, time, duration, Motion.Easing.FAST_OUT_SLOW_IN);
    }

    public int tweenColor(
            String key, int target, double time, double duration, Motion.Easing easing) {
        return colorValue("tween/" + key, target, time, duration, easing);
    }

    private int colorValue(
            String key, int target, double time, double tweenDuration, Motion.Easing easing) {
        ColorTrack track = colors.get(key);
        if (track == null) {
            track = new ColorTrack();
            track.from = track.target = lab(target);
            track.targetArgb = target;
            track.start = time;
            colors.put(key, track);
            return target;
        }
        double elapsed = Math.floor(Math.max(0, time - track.start) * 1000 + 1e-6) / 1000;
        float[] value = new float[4], velocity = new float[4];
        boolean finished = elapsed >= track.duration;
        for (int i = 0; i < 4; i++) {
            if (finished) value[i] = track.target[i];
            else if (tweenDuration > 0)
                value[i] =
                        (float)
                                (track.from[i]
                                        + (track.target[i] - track.from[i])
                                                * Motion.ease(easing, elapsed / tweenDuration));
            else {
                Sample sample =
                        sample(track.from[i], track.target[i], track.velocity[i], elapsed, 1500, 1);
                value[i] = sample.value();
                velocity[i] = sample.velocity();
            }
        }
        int argb =
                finished
                        ? track.targetArgb
                        : Theme.fromOklab(value[0], value[1], value[2], value[3]);
        if (track.targetArgb != target) {
            track.from = value;
            track.velocity = velocity;
            track.target = lab(target);
            track.targetArgb = target;
            track.start = time;
            track.duration = tweenDuration;
            if (tweenDuration <= 0)
                for (int i = 0; i < 4; i++)
                    track.duration =
                            Math.max(
                                    track.duration,
                                    settlingSeconds(
                                            value[i], track.target[i], velocity[i], 1500, 1, .01));
        }
        return argb;
    }

    private static float[] lab(int color) {
        double[] value = Theme.oklab(color);
        return new float[] {(float) value[0], (float) value[1], (float) value[2], (float) value[3]};
    }

    public double tween(
            String key, double target, double time, double seconds, Motion.Easing easing) {
        Track t = tracks.get(key);
        if (t == null) {
            seed(key, target, time);
            return target;
        }
        double x = Math.max(0, Math.min(1, (Math.floor(Math.max(0, time - t.start) * 1000 + 1e-6) / 1000) / Math.max(.000001, seconds))),
                value = t.from + (t.target - t.from) * Motion.ease(easing, x);
        if (Float.compare(t.target, (float) target) != 0) {
            t.from = (float) value;
            t.target = (float) target;
            t.start = time;
            t.velocity = 0;
        }
        return value;
    }

    public static Sample sample(
            double from,
            double target,
            double initialVelocity,
            double seconds,
            float stiffness,
            float damping) {
        if (!(stiffness > 0) || damping < 0 || !Double.isFinite(seconds))
            throw new IllegalArgumentException("Invalid spring");
        double time = Math.floor(Math.max(0, seconds) * 1000 + 1e-6) / 1000,
                omega = Math.sqrt(stiffness),
                offset = (float) ((float) from - (float) target),
                v = (float) initialVelocity,
                position,
                velocity;
        if (damping == 1) {
            double b = v + omega * offset, exp = Math.exp(-omega * time);
            position = (offset + b * time) * exp;
            velocity = (offset + b * time) * exp * -omega + b * exp;
        } else if (damping < 1) {
            double squared = damping * damping,
                    damped = omega * Math.sqrt(1 - squared),
                    decay = -damping * omega,
                    b = (1 / damped) * (-decay * offset + v),
                    angle = damped * time,
                    exp = Math.exp(decay * time);
            position = exp * (offset * Math.cos(angle) + b * Math.sin(angle));
            velocity =
                    position * decay
                            + exp
                                    * (-damped * offset * Math.sin(angle)
                                            + damped * b * Math.cos(angle));
        } else {
            double root = Math.sqrt(damping * damping - 1),
                    r1 = -damping * omega + omega * root,
                    r2 = -damping * omega - omega * root,
                    b = (r2 * offset - v) / (r2 - r1),
                    a = offset - b;
            position = a * Math.exp(r2 * time) + b * Math.exp(r1 * time);
            velocity = a * r2 * Math.exp(r2 * time) + b * r1 * Math.exp(r1 * time);
        }
        return new Sample((float) (position + (float) target), (float) velocity);
    }

    /** FloatSpringSpec.getDurationNanos -> SpringEstimationKt, with the same float normalization. */
    public static double settlingSeconds(
            double from,
            double target,
            double initialVelocity,
            float stiffness,
            float damping,
            double threshold) {
        if (damping <= 0) return 9223372036.854;
        float normalized = ((float) from - (float) target) / (float) threshold,
                initial = (float) initialVelocity / (float) threshold;
        if (normalized == 0 && initial == 0) return 0;
        double c1 = Math.abs((double) normalized),
                velocity = normalized < 0 ? -initial : initial,
                omega = Math.sqrt(stiffness),
                r = -damping * omega,
                duration;
        if (damping < 1) {
            double imaginary = omega * Math.sqrt(1 - (double) damping * damping),
                    c2 = (velocity - r * c1) / imaginary,
                    amplitude = Math.sqrt(c1 * c1 + c2 * c2);
            duration = Math.log(1 / amplitude) / r;
        } else if (damping == 1) duration = criticalDuration(r, c1, velocity, 1);
        else throw new IllegalArgumentException("No HUD animation uses overdamped duration");
        return (long) (duration * 1000) / 1000d;
    }

    private static double criticalDuration(double r, double c1, double velocity, double threshold) {
        double c2 = velocity - r * c1,
                t1 = Math.log(Math.abs(threshold / c1)) / r,
                log = Math.log(Math.abs(threshold / c2)),
                iteration = log;
        for (int i = 0; i < 6; i++) iteration = log - Math.log(Math.abs(iteration / r));
        double t2 = iteration / r,
                t = !Double.isFinite(t1) ? t2 : !Double.isFinite(t2) ? t1 : Math.max(t1, t2),
                inflection = -(r * c1 + c2) / (r * c2),
                x = c1 * Math.exp(r * inflection) + c2 * inflection * Math.exp(r * inflection),
                delta;
        if (Double.isNaN(inflection) || inflection <= 0) delta = -threshold;
        else if (inflection > 0 && -x < threshold) {
            if (c2 < 0 && c1 > 0) t = 0;
            delta = -threshold;
        } else {
            t = -2 / r - c1 / c2;
            delta = threshold;
        }
        double difference = Double.MAX_VALUE;
        for (int i = 0; i < 100 && difference > .001; i++) {
            double previous = t;
            t =
                    t
                            - ((c1 + c2 * t) * Math.exp(r * t) + delta)
                                    / ((c2 * (r * t + 1) + c1 * r) * Math.exp(r * t));
            difference = Math.abs(previous - t);
        }
        return t;
    }
}
