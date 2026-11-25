package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;
import java.util.function.*;

/** Page-owned controls; preference edits never create a second music configuration. */
final class AppearanceControls {
    final Map<String, TextEdit> editors = new HashMap<>();
    final AppearanceMotion motion = new AppearanceMotion();
    TextEdit focused;
    Consumer<String> changed;
    private double lastTick = -100, lastSliderValue = Double.NaN;
    Theme theme;
    double alpha = 1;

    void begin(Theme theme) {
        this.theme = theme;
        alpha = 1;
    }

    int ink(int color) {
        return Theme.multiplyAlpha(color, alpha);
    }

    double motion(PageContext c, String key, double target) {
        return motion.spring(key, target, c.time(), c.preferences().flag("animations", true));
    }

    int color(PageContext c, String key, int from, int to, double target) {
        return motion.color(
                key, target == 0 ? from : to, c.time(), c.preferences().flag("animations", true));
    }

    static List<String> wrap(Painter p, String text, double width, double size, boolean bold) {
        if (text.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String next = line.toString() + new String(Character.toChars(cp));
            if (line.length() > 0 && p.measure(next, size, bold) > Math.max(1, width)) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.appendCodePoint(cp);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    static String trim(Painter p, String text, double width, double size) {
        if (p.measure(text, size, false) <= width) return text;
        String out = "";
        for (int i = 0; i < text.length(); ) {
            int n = text.offsetByCodePoints(i, 1);
            String part = text.substring(0, n);
            if (p.measure(part + "…", size, false) > width) break;
            out = part;
            i = n;
        }
        return out + "…";
    }

    void center(PageContext c, String text, Rect r, double size, int color) {
        c.painter().textCentered(text, r, size, false, ink(color));
    }

    static void checker(Painter p, Rect r, double radius) {
        p.save();
        try {
            p.clip(r, radius);
            for (double y = r.y(); y < r.bottom(); y += 6)
                for (double x = r.x(); x < r.right(); x += 6)
                    p.rect(
                            new Rect(x, y, Math.min(6, r.right() - x), Math.min(6, r.bottom() - y)),
                            0,
                            ((int) ((x - r.x()) / 6) + (int) ((y - r.y()) / 6)) % 2 == 0
                                    ? 0xFFCCCCCC
                                    : 0xFF999999);
        } finally {
            p.restore();
        }
    }

    void accent(PageContext c, Rect r, double radius, double amount) {
        Painter p = c.painter();
        p.save();
        try {
            p.clip(r, radius);
            int a = Theme.multiplyAlpha(Theme.accent(c.preferences(), c.time()), alpha * amount),
                    b =
                            Theme.multiplyAlpha(
                                    Theme.accentEnd(c.preferences(), c.time()), alpha * amount);
            p.gradient(r, a, b, b, a);
        } finally {
            p.restore();
        }
    }

    void switchControl(
            PageContext c,
            String id,
            Rect track,
            boolean checked,
            boolean enabled,
            Runnable toggle,
            boolean popup) {
        Painter p = c.painter();
        double old = alpha;
        double amount = motion(c, id + ".on", checked ? 1 : 0),
                offset = motion(c, id + ".thumb", checked ? 24 : 3);
        p.rect(track, theme.circleRadius(track.h()), ink(theme.controlTrack()));
        accent(c, track, theme.circleRadius(track.h()), amount);
        boolean hover = enabled && track.contains(c.mouseX(), c.mouseY());
        int border =
                color(
                        c,
                        id + ".hover",
                        Theme.alpha(theme.text(), 0),
                        Theme.alpha(theme.text(), .15),
                        hover ? 1 : 0);
        p.outline(track, theme.circleRadius(track.h()), 1, ink(border));
        p.rect(
                new Rect(track.x() + offset, track.y() + 3, 15, 15),
                theme.circleRadius(15),
                ink(theme.thumb()));
        String label = checked ? "On" : "Off";
        p.textCenteredY(
                label,
                track.x() - 8 - p.measure(label, 12, false),
                track.y() + track.h() / 2,
                12,
                false,
                ink(theme.secondary()));
        if (enabled)
            hit(
                    c,
                    popup,
                    id,
                    track,
                    () -> {
                        blur();
                        c.sound("click");
                        toggle.run();
                    },
                    "");
        alpha = old;
    }

    void field(
            PageContext c,
            String id,
            Rect r,
            String value,
            Consumer<String> callback,
            UnaryOperator<String> filter,
            boolean popup) {
        Painter p = c.painter();
        TextEdit edit = editors.computeIfAbsent(id, k -> new TextEdit());
        if (edit != focused) edit.text(value);
        p.save();
        try {
            p.clip(r, 0);
            double x = r.x() + 8;
            Painter.TextBounds bounds = p.textBounds(edit.text().isEmpty() ? "0" : edit.text(), 13, false);
            double y = bounds.centeredTop(r.y() + r.h() / 2), inkY = y + bounds.top();
            double dx =
                    edit == focused
                            ? Math.max(
                                    0,
                                    p.measure(edit.text().substring(0, edit.cursor()), 13, false)
                                            - r.w()
                                            + 18)
                            : 0;
            if (edit == focused && edit.selected()) {
                double
                        a =
                                p.measure(
                                                edit.text().substring(0, edit.selectionStart()),
                                                13,
                                                false)
                                        - dx,
                        b =
                                p.measure(edit.text().substring(0, edit.selectionEnd()), 13, false)
                                        - dx;
                p.rect(
                        new Rect(x + a, inkY, b - a, bounds.height()),
                        0,
                        Theme.alpha(Theme.accent(c.preferences(), c.time()), .35));
            }
            p.text(edit.text(), x - dx, y, 13, false, ink(theme.text()));
            if (edit == focused && ((int) (c.time() * 2)) % 2 == 0) {
                double caret =
                        x - dx + p.measure(edit.text().substring(0, edit.cursor()), 13, false);
                p.line(caret, inkY, caret, inkY + bounds.height(), 1, ink(theme.text()));
            }
        } finally {
            p.restore();
        }
        hit(
                c,
                popup,
                id,
                r,
                () -> {
                    if (focused != edit) blur();
                    focused = edit;
                    edit.focused = true;
                    changed =
                            s -> {
                                String clean = filter.apply(s);
                                if (!clean.equals(edit.text())) edit.text(clean);
                                callback.accept(clean);
                            };
                    edit.click(c.mouseX() - r.x() - 8, s -> p.measure(s, 13, false), c.shift());
                },
                "");
    }

    void number(
            PageContext c,
            String id,
            Rect r,
            double value,
            double min,
            double max,
            double step,
            Consumer<Double> update,
            boolean enabled,
            boolean popup) {
        Painter p = c.painter();
        p.rect(r, theme.navigationRadius(), ink(theme.component()));
        p.outline(r, theme.navigationRadius(), 1, ink(theme.border()));
        if (enabled)
            field(
                    c,
                    id + ".input",
                    new Rect(r.x(), r.y(), r.w() - 20, r.h()),
                    format(value),
                    s -> {
                        try {
                            double v = Double.parseDouble(s);
                            if (Double.isFinite(v)) update.accept(Geometry.clamp(v, min, max));
                        } catch (NumberFormatException ignored) {
                        }
                    },
                    AppearanceControls::filterNumberInput,
                    popup);
        else p.textCenteredY(format(value), r.x() + 8, r.y() + r.h() / 2, 13, false, ink(theme.text()));
        for (int i = 0; i < 2; i++) {
            boolean up = i == 0, available = enabled && (up ? value < max : value > min);
            Rect a = new Rect(r.right() - 20, r.y() + 3 + i * 13, 18, 13);
            boolean hover = available && a.contains(c.mouseX(), c.mouseY());
            p.icon(
                    theme.iconPath(up ? "up" : "down"),
                    a.x() + 2,
                    a.y() + (a.h() - 14) / 2,
                    14,
                    ink(
                            Theme.multiplyAlpha(
                                    hover ? theme.text() : theme.secondary(), available ? 1 : .4)));
            if (available)
                hit(
                        c,
                        popup,
                        id + (up ? ".up" : ".down"),
                        a,
                        () -> {
                            blur();
                            c.sound("click");
                            update.accept(Geometry.clamp(value + (up ? step : -step), min, max));
                        },
                        "");
        }
    }

    static String filterNumberInput(String text) {
        StringBuilder result = new StringBuilder();
        boolean dot = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' && i == 0) result.append(c);
            else if (c == '.' && !dot) {
                dot = true;
                result.append(c);
            } else if (Character.isDigit(c)) result.append(c);
        }
        return result.toString();
    }

    static boolean numberInput(String text) {
        boolean dot = false;
        for (int i = 0; i < text.length(); i++) {
            char v = text.charAt(i);
            if (v == '-' && i == 0) continue;
            if (v == '.' && !dot) {
                dot = true;
                continue;
            }
            if (!Character.isDigit(v)) return false;
        }
        return true;
    }

    static String format(double value) {
        return value == (long) value
                ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    void slider(PageContext c, AppearanceSchema.Setting s, Rect r, boolean enabled) {
        Painter p = c.painter();
        double value = ((Number) s.value(c.preferences())).doubleValue(),
                fraction = (value - s.min()) / (s.max() - s.min());
        double f = motion(c, s.key() + ".fraction", fraction);
        Rect track = new Rect(r.x(), r.y(), 300, 19);
        p.rect(
                new Rect(track.x(), track.y() + 7, track.w(), 5),
                theme.checkboxRadius(),
                ink(theme.controlTrack()));
        accent(
                c,
                new Rect(track.x(), track.y() + 7, Math.max(0, track.w() * f), 5),
                theme.checkboxRadius(),
                1);
        p.rect(
                new Rect(track.x() + f * (track.w() - 19), track.y(), 19, 19),
                theme.circleRadius(19),
                ink(theme.thumb()));
        if (enabled)
            c.contentHit(
                    "appearance." + s.key() + ".track",
                    track,
                    () -> {
                        blur();
                        lastTick = -100;
                        lastSliderValue = Double.NaN;
                        c.startDrag(
                                (x, y) -> {
                                    double v =
                                            s.snap(
                                                    s.min()
                                                            + Geometry.clamp(
                                                                            (x - track.x() - 9.5)
                                                                                    / (track.w()
                                                                                            - 19),
                                                                            0,
                                                                            1)
                                                                    * (s.max() - s.min()));
                                    if (Double.compare(v, lastSliderValue) != 0) {
                                        lastSliderValue = v;
                                        if (c.time() - lastTick >= .070) {
                                            lastTick = c.time();
                                            c.sound("slider_tick");
                                        }
                                    }
                                    s.set(c.preferences(), v);
                                },
                                () -> {});
                    },
                    s.description());
        number(
                c,
                "appearance." + s.key(),
                new Rect(r.x() + 316, r.y() - 6.5, 87, 32),
                value,
                s.min(),
                s.max(),
                s.step(),
                v -> s.set(c.preferences(), v),
                enabled,
                false);
    }

    void hit(PageContext c, boolean popup, String id, Rect r, Runnable action, String tooltip) {
        if (popup) c.hit(id, r, action, tooltip);
        else c.contentHit(id, r, action, tooltip);
    }

    void blur() {
        if (focused != null) focused.focused = false;
        focused = null;
        changed = null;
    }

    boolean key(PageContext c, ConfigView.Key key, String typed, boolean ctrl, boolean shift) {
        if (focused == null) return false;
        TextEdit edit = focused;
        String before = edit.text();
        if (key == ConfigView.Key.ESCAPE
                || key == ConfigView.Key.ENTER
                || key == ConfigView.Key.TAB) {
            blur();
            return true;
        }
        if (ctrl) {
            switch (key) {
                case A -> edit.selectAll();
                case C -> c.host().clipboard(edit.selectedText());
                case X -> {
                    c.host().clipboard(edit.selectedText());
                    edit.insert("");
                }
                case V -> edit.insert(c.host().clipboard());
                default -> {}
            }
        }
        switch (key) {
            case LEFT -> edit.move(-1, shift, ctrl);
            case RIGHT -> edit.move(1, shift, ctrl);
            case HOME -> edit.edge(false, shift);
            case END -> edit.edge(true, shift);
            case BACKSPACE -> edit.delete(true, ctrl);
            case DELETE -> edit.delete(false, ctrl);
            default -> {
                if (!ctrl && !typed.isEmpty()) edit.insert(typed);
            }
        }
        if (!before.equals(edit.text()) && changed != null) {
            String attempted = edit.text();
            changed.accept(attempted);
        }
        return true;
    }
}
