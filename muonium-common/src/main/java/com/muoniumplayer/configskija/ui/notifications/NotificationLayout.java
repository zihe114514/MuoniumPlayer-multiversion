package com.muoniumplayer.configskija.ui.notifications;

import com.muoniumplayer.configskija.ui.Geometry;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Painter;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared measurements in the original shell's dp coordinates. */
public final class NotificationLayout {
    public static final double PANEL_WIDTH = 368, LIST_MAX_HEIGHT = 420;
    private NotificationLayout() {}
    public static String relativeTime(long createdAt, long now) {
        long seconds = Math.max(0, (now - createdAt) / 1000);
        if (seconds < 5) return "Just now";
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return seconds / 60 + "m ago";
        if (seconds < 86400) return seconds / 3600 + "h ago";
        return seconds / 86400 + "d ago";
    }
    /** Original BottomStart popup: directly above the bell, separated by twelve dp. */
    public static Rect panel(Rect bell, double height, Rect shell) {
        return new Rect(Geometry.clamp(bell.x(), shell.x(), Math.max(shell.x(), shell.right() - PANEL_WIDTH)),
            Geometry.clamp(bell.y() - 12 - height, shell.y(), Math.max(shell.y(), shell.bottom() - height)), PANEL_WIDTH, height);
    }
    public static Rect intersection(Rect a, Rect b) {
        double x = Math.max(a.x(), b.x()), y = Math.max(a.y(), b.y());
        return new Rect(x, y, Math.max(0, Math.min(a.right(), b.right()) - x), Math.max(0, Math.min(a.bottom(), b.bottom()) - y));
    }
    public static double measure(Painter painter, String text, double size, int weight) {
        return painter instanceof NotificationTypography precise ? precise.notificationMeasure(text, size, weight) : painter.measure(text, size, weight >= 500);
    }
    public static void text(Painter painter, String text, double x, double y, double size, int weight, int color) {
        if (painter instanceof NotificationTypography precise) precise.notificationText(text, x, y, size, weight, color);
        else painter.text(text, x, y, size, weight >= 500, color);
    }
    /** Line opportunities support CJK and explicit newlines; long tokens fall back to Unicode code points. */
    public static List<String> wrap(Painter painter, String text, double width, double size, boolean bold) {
        return wrap(painter, text, width, size, bold ? 600 : 400);
    }
    public static List<String> wrap(Painter painter, String text, double width, double size, int weight) {
        List<String> result = new ArrayList<>();
        if (text.isEmpty()) return result;
        width = Math.max(1, width);
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) { result.add(""); continue; }
            BreakIterator breaks = BreakIterator.getLineInstance(Locale.ROOT); breaks.setText(paragraph);
            int start = 0;
            while (start < paragraph.length()) {
                int fit = start, next = start, lastBreak = -1;
                while (next < paragraph.length()) {
                    int end = paragraph.offsetByCodePoints(next, 1);
                    if (measure(painter, paragraph.substring(start, end), size, weight) > width && fit > start) break;
                    fit = end;
                    if (breaks.isBoundary(end)) lastBreak = end;
                    next = end;
                }
                int end = next == paragraph.length() ? fit : lastBreak > start ? lastBreak : fit;
                if (end <= start) end = paragraph.offsetByCodePoints(start, 1);
                result.add(paragraph.substring(start, end).stripTrailing());
                start = end;
                while (start < paragraph.length() && paragraph.charAt(start) == ' ') start++;
            }
        }
        return result;
    }
    static void lines(Painter painter, List<String> lines, double x, double y, double size, double lineHeight, boolean bold, int color) {
        lines(painter, lines, x, y, size, lineHeight, bold ? 600 : 400, color);
    }
    static void lines(Painter painter, List<String> lines, double x, double y, double size, double lineHeight, int weight, int color) {
        for (String line : lines) { text(painter, line, x, y + Math.max(0, (lineHeight - size) / 2), size, weight, color); y += lineHeight; }
    }
    /** Original toast wrapper deliberately only breaks at spaces, including long words that exceed the width. */
    static List<String> toastLines(Painter painter, String text, double width) {
        if (text.isEmpty()) return List.of();
        if (painter.measure(text, 12, false) <= width) return List.of(text);
        List<String> lines = new ArrayList<>(); String current = "";
        for (String word : text.split(" ", -1)) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (painter.measure(candidate, 12, false) <= width) current = candidate;
            else { if (!current.isEmpty()) lines.add(current); current = word; }
        }
        if (!current.isEmpty()) lines.add(current);
        return lines;
    }
}
