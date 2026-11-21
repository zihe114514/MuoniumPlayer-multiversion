package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

/** Press ownership follows the visible lyric at the start of the gesture, not the moving row at release. */
final class AppleLyricInteraction {
    private static final double TAP_SLOP = 5.0;

    record Bounds(double left, double top, double right, double bottom) {
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        Bounds clip(Bounds other) {
            return new Bounds(Math.max(left, other.left), Math.max(top, other.top),
                    Math.min(right, other.right), Math.min(bottom, other.bottom));
        }

        boolean visible() {
            return left < right && top < bottom;
        }
    }

    private record Row(Object identity, long timestamp, Bounds bounds) {}

    private final List<Row> rows = new ArrayList<>();
    private long session;
    private long revision;
    private boolean validFrame;
    private Bounds viewport;
    private Row pressed;
    private double pressX;
    private double pressY;

    void beginFrame(long session, long revision, Bounds viewport) {
        if (!matches(session, revision)) cancel();
        this.session = session;
        this.revision = revision;
        this.viewport = viewport;
        validFrame = true;
        rows.clear();
    }

    void add(Object identity, long timestamp, Bounds box) {
        Bounds clipped = box.clip(viewport);
        if (timestamp >= 0 && clipped.visible()) rows.add(new Row(identity, timestamp, clipped));
    }

    boolean isPressed(Object row) {
        return pressed != null && pressed.identity == row;
    }

    boolean canHit(double x, double y) {
        return validFrame && hit(x, y) != null;
    }

    boolean press(double x, double y, long session, long revision) {
        cancel();
        if (!matches(session, revision)) return false;
        pressed = hit(x, y);
        pressX = x;
        pressY = y;
        return pressed != null;
    }

    boolean isPressed() {
        return pressed != null;
    }

    void drag(double x, double y) {
        if (pressed != null && !withinSlop(x, y)) cancel();
    }

    long release(double x, double y, long session, long revision) {
        try {
            if (pressed == null || !matches(session, revision)
                    || !viewport.contains(x, y) || !withinSlop(x, y)) return -1L;
            return pressed.timestamp;
        } finally {
            cancel();
        }
    }

    void cancel() {
        pressed = null;
    }

    void invalidate() {
        validFrame = false;
        rows.clear();
        cancel();
    }

    private boolean matches(long session, long revision) {
        return validFrame && this.session == session && this.revision == revision;
    }

    private Row hit(double x, double y) {
        // If adjacent animated rows overlap, the one painted last is on top.
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row row = rows.get(i);
            if (row.bounds.contains(x, y)) return row;
        }
        return null;
    }

    private boolean withinSlop(double x, double y) {
        double dx = x - pressX, dy = y - pressY;
        return dx * dx + dy * dy <= TAP_SLOP * TAP_SLOP;
    }
}
