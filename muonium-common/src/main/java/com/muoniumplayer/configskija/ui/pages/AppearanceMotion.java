package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.ui.Theme;

import java.util.HashMap;
import java.util.Map;

/** Default Compose spring: stiffness 1500, damping ratio 1, unit mass, continuous retarget velocity. */
public final class AppearanceMotion {
    private static final double OMEGA = Math.sqrt(1500);

    private static final class Track {
        double value, velocity, target, start, from, initialVelocity;
    }

    private final Map<String, Track> tracks = new HashMap<>();

    public double spring(String key, double target, double time, boolean enabled) {
        Track t = tracks.get(key);
        if (t == null) {
            t = new Track();
            t.value = t.from = t.target = target;
            t.start = time;
            tracks.put(key, t);
            return target;
        }
        if (!enabled) {
            t.value = t.from = t.target = target;
            t.velocity = t.initialVelocity = 0;
            t.start = time;
            return target;
        }
        // The resolved Compose 1.13 alpha02 FloatSpringSpec truncates playTimeNanos to
        // milliseconds.
        double elapsed = Math.floor(Math.max(0, time - t.start) * 1000 + 1e-6) / 1000,
                offset = t.from - t.target,
                b = t.initialVelocity + OMEGA * offset,
                decay = Math.exp(-OMEGA * elapsed);
        t.value = (float) (t.target + (offset + b * elapsed) * decay);
        t.velocity = (float) ((b - OMEGA * (offset + b * elapsed)) * decay);
        if (Double.compare(t.target, target) != 0) {
            t.from = t.value;
            t.initialVelocity = t.velocity;
            t.target = target;
            t.start = time;
        }
        return t.value;
    }

    public int color(String key, int target, double time, boolean enabled) {
        double[] v = Theme.oklab(target);
        for (int i = 0; i < v.length; i++) v[i] = spring(key + "." + i, v[i], time, enabled);
        return Theme.fromOklab(v[0], v[1], v[2], v[3]);
    }

    public void clear() {
        tracks.clear();
    }
}
