package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.ui.PageContext;
import java.util.HashMap;
import java.util.Map;

/** A scalar physical spring, preserving velocity on retarget. Values are evaluated in real seconds. */
final class SettingsMotion {
    private static final class Track { double from, target, start, velocity, stiffness, damping, threshold, seen; }
    private final Map<String, Track> tracks = new HashMap<>();
    double value(PageContext ctx, String key, double target, double stiffness, double damping, double threshold) {
        Track t = tracks.get(key); double now = ctx.time();
        if (t == null) { t = new Track(); t.from = t.target = target; t.start = now; t.stiffness = stiffness; t.damping = damping; t.threshold = threshold; tracks.put(key, t); }
        if (Double.compare(t.target, target) != 0) { double[] current = sample(t, now); t.from = current[0]; t.velocity = current[1]; t.target = target; t.start = now; t.stiffness = stiffness; t.damping = damping; t.threshold = threshold; }
        t.seen = now; if (tracks.size() > 1024) tracks.entrySet().removeIf(e -> now - e.getValue().seen > 10);
        return sample(t, now)[0];
    }
    private static double[] sample(Track t, double now) {
        double elapsed = Math.max(0, now - t.start), omega = Math.sqrt(t.stiffness), distance = t.from - t.target, velocity = t.velocity, displacement, speed;
        if (t.damping == 1) { double c = velocity + omega * distance, decay = Math.exp(-omega * elapsed); displacement = (distance + c * elapsed) * decay; speed = (velocity - omega * c * elapsed) * decay; }
        else if (t.damping < 1) { double damp = t.damping * omega, frequency = omega * Math.sqrt(1 - t.damping * t.damping), c = (velocity + damp * distance) / frequency, cos = Math.cos(frequency * elapsed), sin = Math.sin(frequency * elapsed), decay = Math.exp(-damp * elapsed); displacement = decay * (distance * cos + c * sin); speed = decay * (-damp * (distance * cos + c * sin) + frequency * (-distance * sin + c * cos)); }
        else { double root = Math.sqrt(t.damping * t.damping - 1), r1 = -omega * (t.damping - root), r2 = -omega * (t.damping + root), a = (velocity - r2 * distance) / (r1 - r2), b = distance - a; displacement = a * Math.exp(r1 * elapsed) + b * Math.exp(r2 * elapsed); speed = r1 * a * Math.exp(r1 * elapsed) + r2 * b * Math.exp(r2 * elapsed); }
        if (Math.abs(displacement) < t.threshold && Math.abs(speed) < t.threshold * 62.5) return new double[] {t.target, 0};
        return new double[] {t.target + displacement, speed};
    }
}
