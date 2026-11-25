/*
 * This file is part of OneConfig.
 * OneConfig - Next Generation Config Library for Minecraft: Java Edition
 * Copyright (C) 2021~2024 Polyfrost.
 *   <https://polyfrost.org> <https://github.com/Polyfrost/>
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *   OneConfig is licensed under the terms of version 3 of the GNU Lesser
 * General Public License as published by the Free Software Foundation, AND
 * under the Additional Terms Applicable to OneConfig, as published by Polyfrost,
 * either version 1.0 of the Additional Terms, or (at your option) any later
 * version.
 *
 *   This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 * License.  If not, see <https://www.gnu.org/licenses/>. You should
 * have also received a copy of the Additional Terms Applicable
 * to OneConfig, as published by Polyfrost. If not, see
 * <https://polyfrost.org/legal/oneconfig/additional-terms>
 */

package com.muoniumplayer.configskija.ui.notifications;

import com.muoniumplayer.configskija.model.Notifications.Notice;

/** Source toast timing in a testable clock: 220 ms in/out, hover pause, 600 ms progress linger. */
public final class ToastTimeline {
    public enum Phase { ENTER, WAIT, LINGER, EXIT, DONE }
    private static final long TRANSITION = 220_000_000L, LINGER = 600_000_000L;
    private final Notice notice;
    private final long duration;
    private Phase phase = Phase.ENTER;
    private long phaseStart, last, remaining;
    private boolean started;
    private double opacity, progress, fraction = 1;

    public ToastTimeline(Notice notice) {
        this.notice = java.util.Objects.requireNonNull(notice);
        duration = Math.max(1, (long)(notice.duration() * 1_000_000)); remaining = duration;
    }
    public Phase phase() { return phase; }
    public double opacity() { return opacity; }
    public double progress() { return progress; }
    public double timeFraction() { return fraction; }
    public boolean done() { return phase == Phase.DONE; }
    public void advance(long now, boolean hovered) {
        if (!started) { started = true; phaseStart = last = now; }
        long dt = Math.max(0, now - last); last = now;
        if (phase == Phase.ENTER || phase == Phase.WAIT) {
            if (notice.hasProgress()) { Double sampled = notice.progressOrNull(); progress = sampled == null ? 0 : sampled; }
        }
        if (phase == Phase.ENTER) {
            double t = unit(now - phaseStart, TRANSITION); opacity = 1 - Math.pow(1 - t, 3);
            if (t < 1) return;
            phase = Phase.WAIT; phaseStart = now; dt = 0;
        }
        if (phase == Phase.WAIT) {
            if (notice.dismissRequested()) beginExit(now);
            else if (notice.hasProgress()) { if (progress >= 1) { phase = Phase.LINGER; phaseStart = now; } }
            else if (!notice.persistent()) {
                if (!hovered) remaining = Math.max(0, remaining - dt);
                fraction = unit(remaining, duration);
                if (remaining == 0) beginExit(now);
            }
        }
        if (phase == Phase.LINGER && (notice.dismissRequested() || now - phaseStart >= LINGER)) beginExit(now);
        if (phase == Phase.EXIT) {
            double t = unit(now - phaseStart, TRANSITION); opacity = 1 - t * t * t;
            if (t >= 1) { opacity = 0; phase = Phase.DONE; }
        }
    }
    private void beginExit(long now) { phase = Phase.EXIT; phaseStart = now; }
    private static double unit(long value, long total) { return Math.max(0, Math.min(1, (double)value / total)); }
    public static double quantize(double value, double physicalBarPixels) {
        double pixels = Math.max(1, physicalBarPixels);
        return Math.round(value * pixels) / pixels;
    }
}
