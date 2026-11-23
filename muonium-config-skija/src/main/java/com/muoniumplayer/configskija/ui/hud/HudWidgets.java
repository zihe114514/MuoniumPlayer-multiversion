package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;
import java.util.function.Consumer;

final class HudWidgets {
    static final int BLUE = 0xFF0D99FF, AMBER = 0xFFE0A44C, RED = 0xFFE5484D;

    private HudWidgets() {}

    static void panel(PageContext c, Rect r) {
        Painter p = c.painter();
        double radius = c.theme().radius(12);
        p.shadow(r, radius, 16, 0, 0, 4, 0x40000000);
        p.rect(r, radius, c.theme().popup());
        p.outline(r, radius, 1, c.theme().border());
    }

    static void label(
            PageContext c, String value, double x, double y, double size, boolean bold, int color) {
        c.painter().text(value, x, y, size, bold, color);
    }

    static void text(PageContext c, String value, Rect r, double size, boolean bold, int color) {
        String s = ellipsis(c.painter(), value, r.w(), size, bold);
        c.painter().text(s, r.x(), r.y() + (r.h() - size) / 2, size, bold, color);
    }

    static String ellipsis(Painter p, String s, double width, double size, boolean bold) {
        if (width <= 0) return "";
        if (p.measure(s, size, bold) <= width) return s;
        int end = s.length();
        while (end > 0) {
            end = s.offsetByCodePoints(end, -1);
            if (p.measure(s.substring(0, end) + "…", size, bold) <= width)
                return s.substring(0, end) + "…";
        }
        return "";
    }

    static void button(
            PageContext c,
            String id,
            Rect r,
            String title,
            String icon,
            boolean selected,
            boolean enabled,
            Runnable action,
            String tip) {
        boolean hover = r.contains(c.mouseX(), c.mouseY());
        double t =
                c.animate(
                        "hud/button/" + id,
                        enabled && hover ? 1 : 0,
                        .15,
                        Motion.Easing.FAST_OUT_SLOW_IN);
        Painter p = c.painter();
        int bg =
                selected ? c.accent() : Theme.mix(c.theme().component(), c.theme().text(), .10 * t);
        p.rect(r, c.theme().radius(5), HudRuntime.opacity(bg, enabled ? 1 : .35));
        int fg = selected ? 0xFFFFFFFF : c.theme().text();
        fg = HudRuntime.opacity(fg, enabled ? 1 : .4);
        double x = r.x() + 10;
        if (!icon.isBlank()) {
            p.icon(icon, x, r.y() + (r.h() - 16) / 2, 16, fg);
            x += title.isBlank() ? 0 : 24;
        }
        if (!title.isBlank())
            text(
                    c,
                    title,
                    new Rect(x, r.y(), Math.max(0, r.right() - x - 8), r.h()),
                    12,
                    false,
                    fg);
        c.hit(
                "hud:" + id,
                r,
                enabled
                        ? () -> {
                            action.run();
                        }
                        : () -> {},
                tip);
    }

    static void iconButton(
            PageContext c,
            String id,
            Rect r,
            String icon,
            int foreground,
            int hoveredForeground,
            Runnable action) {
        boolean hovered = r.contains(c.mouseX(), c.mouseY());
        int color =
                c instanceof HudChromeContext chrome
                        ? chrome.motion()
                                .color(
                                        "icon-button/" + id,
                                        hovered ? hoveredForeground : foreground,
                                        c.time())
                        : hovered ? hoveredForeground : foreground;
        c.painter().icon(icon, r.x(), r.y(), Math.min(r.w(), r.h()), color);
        c.hit("hud:" + id, r, action, "");
    }

    static void dashed(Painter p, Rect r, int color, double on, double off) {
        dash(p, r.x(), r.y(), r.right(), r.y(), 1, color, on, off);
        dash(p, r.right(), r.y(), r.right(), r.bottom(), 1, color, on, off);
        dash(p, r.right(), r.bottom(), r.x(), r.bottom(), 1, color, on, off);
        dash(p, r.x(), r.bottom(), r.x(), r.y(), 1, color, on, off);
    }

    static void dash(
            Painter p,
            double x,
            double y,
            double tx,
            double ty,
            double width,
            int color,
            double on,
            double off) {
        double length = Math.hypot(tx - x, ty - y);
        if (length <= 0) return;
        double vx = (tx - x) / length, vy = (ty - y) / length;
        for (double d = 0; d < length; d += on + off) {
            double end = Math.min(length, d + on);
            p.line(x + vx * d, y + vy * d, x + vx * end, y + vy * end, width, color);
        }
    }

    static double wrap(
            PageContext c,
            String value,
            double x,
            double y,
            double width,
            double size,
            double line,
            int color) {
        for (String paragraph : value.split("\n", -1)) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < paragraph.length(); ) {
                int cp = paragraph.codePointAt(i);
                i += Character.charCount(cp);
                String next = s.toString() + new String(Character.toChars(cp));
                if (c.painter().measure(next, size, false) > width && s.length() > 0) {
                    label(c, s.toString(), x, y, size, false, color);
                    y += line;
                    s.setLength(0);
                }
                s.appendCodePoint(cp);
            }
            label(c, s.toString(), x, y, size, false, color);
            y += line;
        }
        return y;
    }

    static void scrollbar(
            PageContext c,
            String id,
            Rect viewport,
            double total,
            double scroll,
            Consumer<Double> change) {
        if (total <= viewport.h()) return;
        double thumbH = Math.max(24, viewport.h() * viewport.h() / total),
                travel = viewport.h() - thumbH;
        Rect track = new Rect(viewport.right() - 6, viewport.y(), 6, viewport.h()),
                thumb =
                        new Rect(
                                track.x(),
                                track.y() + travel * scroll / (total - viewport.h()),
                                6,
                                thumbH);
        c.painter().rect(thumb, 3, Theme.alpha(c.theme().secondary(), .6));
        c.hit(
                "hud:" + id,
                track,
                () -> {
                    double offset =
                            thumb.contains(c.mouseX(), c.mouseY())
                                    ? c.mouseY() - thumb.y()
                                    : thumbH / 2;
                    c.startDrag(
                            (x, y) ->
                                    change.accept(
                                            Geometry.clamp(
                                                            (y - track.y() - offset)
                                                                    / Math.max(1, travel),
                                                            0,
                                                            1)
                                                    * (total - viewport.h())),
                            () -> {});
                },
                "");
    }

    static Rect clamp(Rect r, double width, double height) {
        return new Rect(
                Geometry.clamp(r.x(), 0, Math.max(0, width - r.w())),
                Geometry.clamp(r.y(), 0, Math.max(0, height - r.h())),
                Math.min(width, r.w()),
                Math.min(height, r.h()));
    }

    static final class Input implements UiPage {
        private final String title;
        private final Consumer<String> accept;
        private final TextEdit value = new TextEdit();
        private Rect box;
        private String error = "";

        Input(String title, String initial, Consumer<String> accept) {
            this.title = title;
            this.accept = accept;
            value.text(initial);
            value.selectAll();
            value.focused = true;
        }

        public void render(PageContext c) {
            c.painter().rect(new Rect(0, 0, c.width(), c.height()), 0, 0x55000000);
            box =
                    clamp(
                            new Rect((c.width() - 330) / 2, (c.height() - 174) / 2, 330, 174),
                            c.width(),
                            c.height());
            panel(c, box);
            label(c, title, box.x() + 16, box.y() + 14, 16, true, c.theme().text());
            c.textField(
                    "hud:input",
                    value,
                    new Rect(box.x() + 16, box.y() + 50, box.w() - 32, 34),
                    "",
                    s -> {});
            if (!error.isBlank())
                text(
                        c,
                        error,
                        new Rect(box.x() + 16, box.y() + 88, box.w() - 32, 22),
                        11,
                        false,
                        RED);
            button(
                    c,
                    "input-cancel",
                    new Rect(box.right() - 176, box.bottom() - 46, 76, 30),
                    "取消",
                    "",
                    false,
                    true,
                    c::dismissOverlay,
                    "");
            button(
                    c,
                    "input-accept",
                    new Rect(box.right() - 92, box.bottom() - 46, 76, 30),
                    "应用",
                    "",
                    true,
                    true,
                    () -> submit(c),
                    "");
        }

        private void submit(PageContext c) {
            try {
                accept.accept(value.text());
                c.dismissOverlay();
            } catch (IllegalArgumentException ex) {
                error = ex.getMessage() == null ? "无效输入" : ex.getMessage();
            }
        }

        public boolean pointerDown(PageContext c, int button) {
            if (box != null && !box.contains(c.mouseX(), c.mouseY())) c.dismissOverlay();
            return true;
        }

        public boolean key(
                PageContext c,
                ConfigView.Key key,
                String typed,
                boolean ctrl,
                boolean shift,
                boolean alt) {
            if (key == ConfigView.Key.ENTER) {
                submit(c);
                return true;
            }
            if (key == ConfigView.Key.ESCAPE) {
                c.dismissOverlay();
                return true;
            }
            editKey(c, value, key, typed, ctrl, shift);
            return true;
        }
    }

    static void editKey(
            PageContext c,
            TextEdit value,
            ConfigView.Key key,
            String typed,
            boolean ctrl,
            boolean shift) {
        if (ctrl) {
            switch (key) {
                case A -> value.selectAll();
                case C -> {
                    if (value.selected()) c.host().clipboard(value.selectedText());
                }
                case X -> {
                    if (value.selected()) {
                        c.host().clipboard(value.selectedText());
                        value.insert("");
                    }
                }
                case V -> value.insert(c.host().clipboard());
                default -> {}
            }
        }
        switch (key) {
            case LEFT -> value.move(-1, shift, ctrl);
            case RIGHT -> value.move(1, shift, ctrl);
            case HOME -> value.edge(false, shift);
            case END -> value.edge(true, shift);
            case BACKSPACE -> value.delete(true, ctrl);
            case DELETE -> value.delete(false, ctrl);
            default -> {
                if (!ctrl && typed != null && !typed.isEmpty()) value.insert(typed);
            }
        }
    }

    static final class Choices implements UiPage {
        private final List<String> choices;
        private final Consumer<String> action;
        private final Rect source;
        private final String selected;
        private final Runnable closing;
        private final HudMotion motion = new HudMotion();
        private Rect box;
        private double scroll;
        private boolean initialized, closeRequested, notified;

        Choices(Rect source, List<String> choices, Consumer<String> action) {
            this(source, choices, "", action, () -> {});
        }

        Choices(
                Rect source,
                List<String> choices,
                String selected,
                Consumer<String> action,
                Runnable closing) {
            this.source = source;
            this.choices = List.copyOf(choices);
            this.selected = selected;
            this.action = action;
            this.closing = closing;
        }

        public void render(PageContext c) {
            if (!initialized) {
                motion.seed("dropdown-alpha", 0, c.time());
                initialized = true;
            }
            double alpha =
                    motion.tween(
                            "dropdown-alpha",
                            closeRequested ? 0 : 1,
                            c.time(),
                            .3,
                            Motion.Easing.FAST_OUT_SLOW_IN);
            if (closeRequested && alpha <= .0001) {
                c.dismissOverlay();
                return;
            }
            double
                    h =
                            Math.min(
                                    c.height(),
                                    8 + choices.size() * 32 + Math.max(0, choices.size() - 1) * 4),
                    w = source.w() + 8;
            box = dropdownPosition(source, w, h, c.width(), c.height());
            Painter p = c.painter();
            p.layer(box, alpha);
            try {
                p.rect(box, 8, 0xFF101B1F);
                p.outline(box, 8, 1, c.theme().border());
                Rect area = box.inset(4);
                p.save();
                try {
                    p.clip(area, 6);
                    for (int i = 0; i < choices.size(); i++) {
                        String value = choices.get(i);
                        Rect row = new Rect(area.x(), area.y() + i * 36 - scroll, area.w(), 32);
                        if (!row.intersects(area)) continue;
                        boolean hover = row.contains(c.mouseX(), c.mouseY());
                        int
                                target =
                                        value.equals(selected)
                                                ? c.accent()
                                                : hover ? 0xFF192126 : 0x00192126,
                                bg = motion.color("dropdown-row/" + i, target, c.time());
                        p.rect(row, 6, bg);
                        HudWidgets.text(
                                c,
                                value,
                                new Rect(row.x() + 12, row.y(), row.w() - 24, row.h()),
                                12,
                                false,
                                c.theme().text());
                        if (!closeRequested && row.y() >= area.y() && row.bottom() <= area.bottom())
                            c.hit(
                                    "hud:choice/" + i,
                                    row,
                                    () -> {
                                        action.accept(value);
                                        requestClose();
                                    },
                                    "");
                    }
                } finally {
                    p.restore();
                }
            } finally {
                p.restore();
            }
        }

        static Rect dropdownPosition(
                Rect anchor, double width, double height, double screenWidth, double screenHeight) {
            double x = anchor.x();
            if (x < 0 || x + width > screenWidth) {
                x = anchor.right() - width;
                if (x < 0 || x + width > screenWidth) x = screenWidth - width;
            }
            double above = anchor.y() - 10,
                    below = screenHeight - anchor.bottom() - 10,
                    y = below >= above ? anchor.bottom() + 10 : anchor.y() - 10 - height;
            y = Math.max(0, Math.min(y, screenHeight - height));
            return new Rect(x, y, width, height);
        }

        private void requestClose() {
            closeRequested = true;
            if (!notified) {
                notified = true;
                closing.run();
            }
        }

        public boolean pointerDown(PageContext c, int b) {
            requestClose();
            return true;
        }

        public boolean wheel(PageContext c, double delta) {
            if (box != null)
                scroll =
                        Geometry.clamp(
                                scroll - delta * 36,
                                0,
                                Math.max(0, 8 + choices.size() * 36 - 4 - box.h()));
            return true;
        }

        public boolean key(
                PageContext c,
                ConfigView.Key key,
                String typed,
                boolean control,
                boolean shift,
                boolean alt) {
            if (key == ConfigView.Key.ESCAPE) requestClose();
            return true;
        }

        public void leave(PageContext c) {
            if (!notified) {
                notified = true;
                closing.run();
            }
        }
    }
}
