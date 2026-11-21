package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;

/** Hit boxes from the last painted classic lyric frame, not the next scroll layout. */
final class ClassicLyricPointer {
    record Track(long sessionId, String trackKey, long lyricRevision) {}
    private record Row(int index, long millis, double left, double top, double right, double bottom) {
        boolean contains(double x, double y) {
            return left <= x && x < right && top <= y && y < bottom;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private Track frameTrack;
    private double centerX, centerY, scale, rise;
    private double left, top, right, bottom;
    private Row pressed;
    private Track pressedTrack;
    private double pressX, pressY;

    void beginFrame(Track track, double centerX, double centerY, double scale, double rise,
                    double left, double top, double width, double height) {
        rows.clear();
        this.frameTrack = track;
        this.centerX = centerX;
        this.centerY = centerY;
        this.scale = scale;
        this.rise = rise;
        this.left = left;
        this.top = top;
        this.right = left + width;
        this.bottom = top + height;
    }

    void addRow(int index, long millis, double columnLeft, double rowTop, double width,
                double height, double mainTextHeight, double rowScale) {
        if (frameTrack == null || millis < 0 || height <= 0) return;
        double center = rowTop + mainTextHeight * .5;
        double paintedTop = center + (rowTop - center) * rowScale;
        rows.add(new Row(index, millis, columnLeft, paintedTop, columnLeft + width,
                center + (rowTop + height - center) * rowScale));
    }

    int hover(double x, double y) {
        Row row = hit(x, y);
        return row == null ? -1 : row.index();
    }

    boolean press(double x, double y, Track track) {
        cancel();
        if (track == null || !track.equals(frameTrack)) return false;
        pressed = hit(x, y);
        if (pressed == null) return false;
        pressedTrack = track;
        pressX = x;
        pressY = y;
        return true;
    }

    boolean isPressed() {
        return pressed != null;
    }

    void pointerMoved(double x, double y, double threshold) {
        if (pressed != null && Math.hypot(x - pressX, y - pressY) > threshold) cancel();
    }

    long release(double x, double y, double threshold, Track track) {
        pointerMoved(x, y, threshold);
        long result = pressed != null && pressedTrack.equals(track) && pressedTrack.equals(frameTrack)
                && inViewport(layoutX(x), layoutY(y)) ? pressed.millis() : -1;
        cancel();
        return result;
    }

    void invalidate() {
        frameTrack = null;
        rows.clear();
        cancel();
    }

    void cancel() {
        pressed = null;
        pressedTrack = null;
    }

    private Row hit(double x, double y) {
        if (frameTrack == null) return null;
        double layoutX = layoutX(x), layoutY = layoutY(y);
        if (!inViewport(layoutX, layoutY)) return null;
        // The later row is painted on top when two transitioning rows overlap.
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row row = rows.get(i);
            if (row.contains(layoutX, layoutY)) return row;
        }
        return null;
    }

    private boolean inViewport(double x, double y) {
        return left <= x && x < right && top <= y && y < bottom;
    }

    private double layoutX(double x) {
        return centerX + (x - centerX) / scale;
    }

    private double layoutY(double y) {
        return centerY + (y - centerY) / scale - rise;
    }
}
