package com.muoniumplayer.configskija.ui.pages;

import static com.muoniumplayer.configskija.ui.pages.AppearanceSchema.*;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;

/** Theme and preference pages in the shared Shell coordinate system. */
public final class AppearancePages {
    private AppearancePages() {}

    public static UiPage themes() {
        return new Page(true);
    }

    public static UiPage preferences() {
        return new Page(false);
    }

    /** A renderer can implement this to retain the SVG preview's original colors. */
    public interface Images {
        void image(String path, Rect bounds);
    }

    /** Native radial vignette and vertical stroke gradient for a setting card. */
    public interface Surfaces {
        void settingDecoration(Rect bounds, double radius, int borderColor, int textColor);
    }

    /** The host forwards canonical key names, including both modifier sides and release events. */
    public interface RawKeyInput {
        boolean rawKey(PageContext context, String token, boolean pressed);

        boolean rawMouse(PageContext context, int button, boolean pressed);

        boolean recording();
    }

    private record Row(Setting setting, Rect bounds) {}

    private static final class Page implements UiPage, RawKeyInput {
        private final boolean themes;
        private final AppearanceControls controls = new AppearanceControls();
        private final AppearanceBindings.Recorder recorder = new AppearanceBindings.Recorder();
        private final List<Row> rows = new ArrayList<>();
        private Setting recording;
        private Rect previews;
        private double horizontal;
        private String lastQuery = "";

        Page(boolean themes) {
            this.themes = themes;
        }

        public void render(PageContext c) {
            Theme t = Theme.transition(c);
            controls.begin(t);
            Painter p = c.painter();
            rows.clear();
            Rect supplied = c.content();
            double top = Math.max(88, supplied.y());
            Rect base =
                    new Rect(supplied.x(), top, supplied.w(), Math.max(0, supplied.bottom() - top));
            Rect list = base;
            if (themes) {
                previews = new Rect(base.x(), base.y(), base.w(), 171);
                renderPreviews(c, t);
                list = new Rect(base.x(), base.y() + 191, base.w(), Math.max(0, base.h() - 191));
            }
            c.content(list);
            String query = c.query().trim();
            if (!query.equals(lastQuery)) {
                c.scroll(0);
                controls.blur();
                lastQuery = query;
            }
            List<Setting> source = themes ? THEMES : PREFERENCES;
            int distance = (int) c.preferences().number("searchDistance", 2);
            LinkedHashMap<String, List<Setting>> groups = new LinkedHashMap<>();
            for (Setting s : source)
                if (query.isEmpty() || s.matches(query, distance))
                    groups.computeIfAbsent(s.group(), k -> new ArrayList<>()).add(s);
            if (groups.isEmpty()) {
                c.scrollRange(0);
                controls.center(
                        c,
                        query.isEmpty() ? "暂无可用设置。" : "没有与「" + query + "」匹配的设置",
                        list,
                        14,
                        t.secondary());
                return;
            }
            double total = 0;
            for (var group : groups.entrySet()) {
                if (!themes) total += 51;
                for (Setting s : group.getValue()) total += rowHeight(c, s, list.w()) + 8;
            }
            total = Math.max(0, total - 8);
            c.scrollRange(Math.max(0, total - list.h()));
            double y = list.y() - c.scroll();
            p.save();
            try {
                p.clip(list, 0);
                for (var group : groups.entrySet()) {
                    if (!themes) {
                        p.text(group.getKey(), list.x(), y + 8, 22, false, t.text());
                        y += 51;
                    }
                    for (Setting s : group.getValue()) {
                        double h = rowHeight(c, s, list.w());
                        Rect r = new Rect(list.x(), y, list.w(), h);
                        if (r.intersects(list)) {
                            rows.add(new Row(s, r));
                            renderRow(c, t, s, r);
                        }
                        y += h + 8;
                    }
                }
            } finally {
                p.restore();
            }
            scrollbar(c, t, list, total);
        }

        private void renderPreviews(PageContext c, Theme t) {
            Painter p = c.painter();
            double maximum = Math.max(0, 862 - previews.w());
            horizontal = Geometry.clamp(horizontal, 0, maximum);
            p.save();
            try {
                p.clip(previews, 0);
                int i = 0;
                for (Theme candidate : Theme.BUILTIN) {
                    Rect card =
                            new Rect(previews.x() + i++ * 222 - horizontal, previews.y(), 196, 171);
                    boolean selected = Theme.active(c.preferences()).id().equals(candidate.id());
                    double selection =
                            controls.motion(
                                    c, "theme." + candidate.id() + ".selected", selected ? 1 : 0);
                    int border =
                            controls.motion.color(
                                    "theme." + candidate.id() + ".border",
                                    selected ? Theme.accent(c.preferences(), c.time()) : t.border(),
                                    c.time(),
                                    c.preferences().flag("animations", true));
                    p.rect(card, t.backgroundRadius(), t.card());
                    p.outline(card, t.backgroundRadius(), 1 + selection * 2, border);
                    Rect image = new Rect(card.x() + 14, card.y() + 14, 168, 108);
                    if (p instanceof Images images) images.image(candidate.previewPath(), image);
                    else {
                        p.outline(image, t.navigationRadius(), 1, t.border());
                        controls.center(c, "预览暂不可用", image, 13, t.secondary());
                    }
                    controls.center(
                            c,
                            candidate.title(),
                            new Rect(card.x() + 14, card.y() + 136, 168, 21),
                            14,
                            t.text());
                    if (card.intersects(previews))
                        c.hit(
                                "appearance.theme." + candidate.id(),
                                intersect(card, previews),
                                () -> {
                                    controls.blur();
                                    Theme previous = Theme.active(c.preferences());
                                    c.preferences().set("activeTheme", candidate.title());
                                    c.sound(
                                            previous.soundFamily().equals(candidate.soundFamily())
                                                    ? "click"
                                                    : "theme_changed");
                                },
                                candidate.title());
                }
            } finally {
                p.restore();
            }
        }

        private static Rect intersect(Rect a, Rect b) {
            double x = Math.max(a.x(), b.x()), y = Math.max(a.y(), b.y());
            return new Rect(
                    x,
                    y,
                    Math.max(0, Math.min(a.right(), b.right()) - x),
                    Math.max(0, Math.min(a.bottom(), b.bottom()) - y));
        }

        private static double width(PageContext c, Setting s) {
            return switch (s.kind()) {
                case TOGGLE ->
                        42
                                + 8
                                + c.painter()
                                        .measure(
                                                (Boolean) s.value(c.preferences()) ? "On" : "Off",
                                                12,
                                                false);
                case SLIDER -> 403;
                case NUMBER -> 87;
                case CHOICE -> 300;
                case KEYBIND ->
                        Math.min(
                                300,
                                44
                                        + c.painter()
                                                .measure(
                                                        AppearanceBindings.display(
                                                                (String) s.value(c.preferences())),
                                                        13,
                                                        false));
                case COLOR -> {
                    int a = Theme.accent(c.preferences(), c.time()),
                            b = Theme.accentEnd(c.preferences(), c.time());
                    yield 50 + c.painter().measure(colorLabel(a, b), 13, false);
                }
            };
        }

        private double rowHeight(PageContext c, Setting s, double available) {
            double
                    reserved =
                            width(c, s)
                                    + (c.preferences().flag("showOptionActionButtons", true)
                                                    && s.enabled(c.preferences())
                                            ? 88
                                            : 48)
                                    + (s.icon().isEmpty() ? 0 : 44),
                    labelWidth = Math.max(1, available - reserved);
            boolean stacked =
                    c.painter().measure(s.title(), 15, false)
                                    + (s.kind() == Kind.SLIDER ? 444 : 108)
                                    + (s.icon().isEmpty() ? 0 : 44)
                            > available;
            if (stacked) labelWidth = available - 32 - (s.icon().isEmpty() ? 0 : 44);
            int descriptions =
                    AppearanceControls.wrap(c.painter(), s.description(), labelWidth, 13, false)
                            .size();
            return 24
                    + Math.max(
                            stacked ? 0 : 32,
                            22.5 + (descriptions == 0 ? 0 : 2 + descriptions * 19.5))
                    + (stacked ? 42 : 0);
        }

        private void renderRow(PageContext c, Theme t, Setting s, Rect r) {
            Painter p = c.painter();
            boolean enabled = s.enabled(c.preferences());
            p.layer(r, enabled ? 1 : .65);
            try {
                controls.alpha = 1;
                p.rect(r, t.cardRadius(), controls.ink(t.card()));
                p.save();
                try {
                    p.clip(r, t.cardRadius());
                    if (p instanceof Surfaces surfaces)
                        surfaces.settingDecoration(r, t.cardRadius(), t.border(), t.text());
                    else border(c, r, t.cardRadius(), t.border());
                } finally {
                    p.restore();
                }
                double controlWidth = width(c, s), controlY = r.y() + (r.h() - 32) / 2;
                boolean stacked =
                        p.measure(s.title(), 15, false)
                                        + (s.kind() == Kind.SLIDER ? 444 : 108)
                                        + (s.icon().isEmpty() ? 0 : 44)
                                > r.w();
                double labelX = r.x() + 16,
                        labelWidth =
                                r.w()
                                        - controlWidth
                                        - (enabled
                                                        && c.preferences()
                                                                .flag(
                                                                        "showOptionActionButtons",
                                                                        true)
                                                ? 88
                                                : 48);
                if (!s.icon().isEmpty()) {
                    p.icon(
                            t.iconPath(s.icon()),
                            labelX,
                            r.y() + (r.h() - 32) / 2,
                            32,
                            controls.ink(t.text()));
                    labelX += 44;
                    labelWidth -= 44;
                }
                if (stacked) {
                    labelWidth = r.w() - 32 - (s.icon().isEmpty() ? 0 : 44);
                    controlY = r.bottom() - 44;
                }
                List<String> lines =
                        AppearanceControls.wrap(p, s.description(), labelWidth, 13, false);
                double textHeight = 22.5 + (lines.isEmpty() ? 0 : 2 + lines.size() * 19.5),
                        labelY = stacked ? r.y() + 12 : r.y() + (r.h() - textHeight) / 2;
                p.text(s.title(), labelX, labelY, 15, false, controls.ink(t.text()));
                for (int i = 0; i < lines.size(); i++)
                    p.text(
                            lines.get(i),
                            labelX,
                            labelY + 24.5 + i * 19.5,
                            13,
                            false,
                            controls.ink(t.secondary()));
                double x = r.right() - 16 - controlWidth;
                switch (s.kind()) {
                    case TOGGLE ->
                            controls.switchControl(
                                    c,
                                    "appearance." + s.key(),
                                    new Rect(r.right() - 58, controlY + 5.5, 42, 21),
                                    (Boolean) s.value(c.preferences()),
                                    enabled,
                                    () ->
                                            s.set(
                                                    c.preferences(),
                                                    !(Boolean) s.value(c.preferences())),
                                    false);
                    case SLIDER ->
                            controls.slider(c, s, new Rect(x, controlY + 6.5, 403, 19), enabled);
                    case NUMBER ->
                            controls.number(
                                    c,
                                    "appearance." + s.key(),
                                    new Rect(x, controlY, 87, 32),
                                    ((Number) s.value(c.preferences())).doubleValue(),
                                    s.min(),
                                    s.max(),
                                    s.step(),
                                    v -> s.set(c.preferences(), v),
                                    enabled,
                                    false);
                    case CHOICE -> {
                        Rect drop = new Rect(x, controlY, 300, 32);
                        boolean hover = enabled && drop.contains(c.mouseX(), c.mouseY());
                        int text =
                                controls.color(
                                        c,
                                        s.key() + ".hover",
                                        t.secondary(),
                                        t.text(),
                                        hover ? 1 : 0);
                        p.rect(drop, t.navigationRadius(), controls.ink(t.component()));
                        p.outline(drop, t.navigationRadius(), 1, controls.ink(t.border()));
                        int index = ((Number) s.value(c.preferences())).intValue();
                        p.text(
                                s.choices().get(index),
                                x + 12,
                                controlY + 6,
                                13,
                                false,
                                controls.ink(text));
                        p.icon(
                                t.iconPath("down"),
                                drop.right() - 26,
                                controlY + 9,
                                14,
                                controls.ink(text));
                        if (enabled)
                            c.contentHit(
                                    "appearance." + s.key() + ".choice",
                                    drop,
                                    () -> {
                                        controls.blur();
                                        c.sound("click");
                                        c.overlay(new Choice(s, drop));
                                    },
                                    s.description());
                    }
                    case KEYBIND -> {
                        boolean active = recording == s && recorder.active();
                        String label =
                                active
                                        ? recorder.pending()
                                        : AppearanceBindings.display(
                                                (String) s.value(c.preferences()));
                        Rect key =
                                new Rect(
                                        r.right()
                                                - 16
                                                - Math.min(300, 44 + p.measure(label, 13, false)),
                                        controlY,
                                        Math.min(300, 44 + p.measure(label, 13, false)),
                                        32);
                        boolean hover = key.contains(c.mouseX(), c.mouseY()),
                                conflict = conflicts(c, s);
                        int stroke =
                                active
                                        ? Theme.accent(c.preferences(), c.time())
                                        : conflict
                                                ? 0xFFE0524F
                                                : hover ? Theme.alpha(t.text(), .2) : t.border();
                        int text =
                                active
                                        ? Theme.accent(c.preferences(), c.time())
                                        : conflict ? 0xFFE0524F : t.text();
                        p.rect(
                                key,
                                t.navigationRadius(),
                                active
                                        ? Theme.alpha(Theme.accent(c.preferences(), c.time()), .2)
                                        : hover
                                                ? Theme.multiplyAlpha(t.component(), .8)
                                                : t.component());
                        p.outline(key, t.navigationRadius(), 1, stroke);
                        p.icon(t.iconPath("keyboard"), key.x() + 12, key.y() + 8, 16, text);
                        p.save();
                        try {
                            p.clip(new Rect(key.x() + 36, key.y(), key.w() - 40, 32), 0);
                            p.text(label, key.x() + 36, key.y() + 6, 13, false, text);
                        } finally {
                            p.restore();
                        }
                        if (enabled)
                            c.contentHit(
                                    "appearance." + s.key() + ".keybind",
                                    key,
                                    () -> {
                                        controls.blur();
                                        recording = s;
                                        recorder.begin();
                                        c.sound("click");
                                    },
                                    conflict ? "此快捷键与其他设置重复" : s.description());
                    }
                    case COLOR -> {
                        int a = Theme.accent(c.preferences(), c.time()),
                                b = Theme.accentEnd(c.preferences(), c.time());
                        Rect swatch = new Rect(x, controlY, controlWidth, 32);
                        p.save();
                        try {
                            p.clip(swatch, t.navigationRadius());
                            p.gradient(swatch, a, b, b, a);
                        } finally {
                            p.restore();
                        }
                        p.outline(swatch, t.navigationRadius(), 1, t.border());
                        int text = luminance(a) > .6 ? 0xFF000000 : 0xFFFFFFFF;
                        p.icon(t.iconPath("paintbrush"), x + 12, controlY + 9, 14, text);
                        p.text(colorLabel(a, b), x + 38, controlY + 6, 13, false, text);
                        c.contentHit(
                                "appearance.accentColor.swatch",
                                swatch,
                                () -> {
                                    controls.blur();
                                    c.sound("click");
                                    c.overlay(new AppearanceColorPicker(c, swatch));
                                },
                                s.description());
                    }
                }
                if (!enabled)
                    c.tooltip(
                            "appearance." + s.key() + ".disabled",
                            r,
                            s.disabledReason(c.preferences()));
                if (enabled && c.preferences().flag("showOptionActionButtons", true)) {
                    double visible = r.contains(c.mouseX(), c.mouseY()) ? 1 : 0;
                    Rect more = new Rect(x - 40, controlY + 4, 24, 24);
                    if (visible > 0)
                        p.icon(
                                t.iconPath("dots-vertical"),
                                more.x() + 4,
                                more.y() + 4,
                                16,
                                Theme.multiplyAlpha(t.secondary(), visible));
                    if (visible > .01)
                        c.contentHit(
                                "appearance." + s.key() + ".actions",
                                more,
                                () -> c.overlay(new Actions(s, more.x(), more.bottom())),
                                "更多操作。也可右键选项。");
                }
                controls.alpha = 1;
            } finally {
                p.restore();
            }
        }

        private static double luminance(int color) {
            double result = 0;
            double[] w = {.2126, .7152, .0722};
            for (int i = 0; i < 3; i++) {
                double v = ((color >>> (16 - i * 8)) & 255) / 255.0;
                result += w[i] * (v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4));
            }
            return result;
        }

        private static String colorLabel(int a, int b) {
            String x = hexLabel(a);
            return a == b ? x : x + " → " + hexLabel(b);
        }

        private static String hexLabel(int c) {
            return ((c >>> 24) & 255) == 255
                    ? String.format(Locale.ROOT, "#%06X", c & 0xffffff)
                    : Theme.hex(c);
        }

        private static boolean conflicts(PageContext c, Setting setting) {
            String chord = (String) setting.value(c.preferences());
            return PREFERENCES.stream()
                    .anyMatch(
                            s ->
                                    s != setting
                                            && s.kind() == Kind.KEYBIND
                                            && AppearanceBindings.conflicts(
                                                    chord, (String) s.value(c.preferences())));
        }

        private static void border(PageContext c, Rect r, double radius, int color) {
            Painter p = c.painter();
            for (int i = 0; i < (int) Math.ceil(r.h()); i++) {
                p.save();
                try {
                    p.clip(new Rect(r.x(), r.y() + i, r.w(), Math.min(1, r.h() - i)), 0);
                    p.outline(r, radius, 1, Theme.multiplyAlpha(color, 1 - (i + .5) / r.h()));
                } finally {
                    p.restore();
                }
            }
        }

        private static void scrollbar(PageContext c, Theme t, Rect list, double total) {
            if (total <= list.h()) return;
            double h = Math.max(24, list.h() * list.h() / total),
                    y = list.y() + (list.h() - h) * c.scroll() / (total - list.h());
            Rect track = new Rect(list.right() + 8.5, list.y(), 8, list.h()),
                    thumb = new Rect(track.x(), y, 8, h);
            double hover =
                    c.animate(
                            "appearance.scrollbar.hover",
                            track.contains(c.mouseX(), c.mouseY()) ? 1 : 0,
                            .3,
                            Motion.Easing.LINEAR);
            c.painter()
                    .rect(thumb, t.scrollBarRadius(), Theme.alpha(t.secondary(), .4 + .3 * hover));
            c.hit(
                    "appearance.scrollbar",
                    track,
                    () ->
                            c.startDrag(
                                    (x, py) ->
                                            c.scroll(
                                                    (py - list.y() - h / 2)
                                                            / (list.h() - h)
                                                            * (total - list.h())),
                                    () -> {}),
                    "");
        }

        public boolean wheel(PageContext c, double delta) {
            if (themes && previews != null && previews.contains(c.mouseX(), c.mouseY())) {
                horizontal =
                        Geometry.clamp(horizontal - delta * 40, 0, Math.max(0, 862 - previews.w()));
                return true;
            }
            return false;
        }

        public boolean pointerDown(PageContext c, int button) {
            if (recorder.active()) {
                recorder.mouse(button, true);
                applyRecording(c);
                return true;
            }
            if (button == 0) controls.blur();
            if (button == 1)
                for (Row row : rows)
                    if (row.bounds().contains(c.mouseX(), c.mouseY())
                            && row.setting().enabled(c.preferences())
                            && c.content().contains(c.mouseX(), c.mouseY())) {
                        controls.blur();
                        c.overlay(new Actions(row.setting(), c.mouseX(), c.mouseY()));
                        return true;
                    }
            return false;
        }

        public void pointerUp(PageContext c, int button) {
            if (recorder.active()) {
                recorder.mouse(button, false);
                applyRecording(c);
            }
        }

        public boolean recording() {
            return recorder.active();
        }

        public boolean rawKey(PageContext c, String token, boolean pressed) {
            boolean used = recorder.key(token, pressed);
            applyRecording(c);
            return used;
        }

        public boolean rawMouse(PageContext c, int button, boolean pressed) {
            boolean used = recorder.mouse(button, pressed);
            applyRecording(c);
            return used;
        }

        private void applyRecording(PageContext c) {
            String result = recorder.take();
            if (result != null && recording != null) {
                recording.set(c.preferences(), result);
                recording = null;
            }
        }

        public boolean key(
                PageContext c,
                ConfigView.Key key,
                String typed,
                boolean ctrl,
                boolean shift,
                boolean alt) {
            if (recorder.active()) {
                String token = key == ConfigView.Key.NONE ? typed : key.name();
                if (token.isBlank()) return true;
                java.util.List<String> mods = new java.util.ArrayList<>();
                if (ctrl) mods.add("CTRL");
                if (shift) mods.add("SHIFT");
                if (alt) mods.add("ALT");
                for (String mod : mods) recorder.key(mod, true);
                recorder.key(token, true);
                if (recorder.active()) {
                    recorder.key(token, false);
                    for (String mod : mods) recorder.key(mod, false);
                }
                applyRecording(c);
                return true;
            }
            return controls.key(c, key, typed, ctrl, shift);
        }

        public void leave(PageContext c) {
            controls.blur();
            recorder.cancel();
            recording = null;
        }
    }

    private static final class Choice implements UiPage {
        private final Setting setting;
        private final Rect trigger;

        Choice(Setting s, Rect trigger) {
            setting = s;
            this.trigger = trigger;
        }

        public void render(PageContext c) {
            Theme t = Theme.transition(c);
            Painter p = c.painter();
            double h = 8 + setting.choices().size() * 35;
            Rect menu =
                    new Rect(
                            trigger.x(), Geometry.clamp(trigger.bottom() + 10, 8, 692 - h), 300, h);
            c.hit("appearance.choice.outside", c.shell(), c::dismissOverlay, "");
            p.rect(menu, t.navigationRadius(), t.component());
            p.outline(menu, t.navigationRadius(), 1, t.border());
            c.hit("appearance.choice.panel", menu, () -> {}, "");
            for (int i = 0; i < setting.choices().size(); i++) {
                int index = i;
                Rect row = new Rect(menu.x() + 4, menu.y() + 4 + i * 35, 292, 31);
                boolean selected = ((Number) setting.value(c.preferences())).intValue() == i,
                        hover = row.contains(c.mouseX(), c.mouseY());
                double a =
                        c.animate(
                                "appearance.choice." + setting.key() + "." + i,
                                selected ? 1 : hover ? .35 : 0,
                                .4,
                                Motion.Easing.SPRING);
                int color =
                        Theme.mixOklab(
                                Theme.alpha(t.card(), 0),
                                selected ? Theme.accent(c.preferences(), c.time()) : t.card(),
                                a);
                p.rect(row, Math.max(0, t.navigationRadius() - 4), color);
                p.text(setting.choices().get(i), row.x() + 12, row.y() + 6, 13, false, t.text());
                c.hit(
                        "appearance." + setting.key() + ".option." + i,
                        row,
                        () -> {
                            c.sound("click");
                            setting.set(c.preferences(), index);
                            c.dismissOverlay();
                        },
                        "");
            }
        }

        public boolean key(
                PageContext c,
                ConfigView.Key key,
                String typed,
                boolean ctrl,
                boolean shift,
                boolean alt) {
            if (key == ConfigView.Key.ESCAPE) {
                c.dismissOverlay();
                return true;
            }
            return false;
        }
    }

    private static final class Actions implements UiPage {
        private final Setting setting;
        private final double x, y;

        Actions(Setting s, double x, double y) {
            setting = s;
            this.x = x;
            this.y = y;
        }

        public void render(PageContext c) {
            Theme t = Theme.transition(c);
            Painter p = c.painter();
            double h = setting.kind() == Kind.KEYBIND ? 74 : 41;
            Rect box = new Rect(Geometry.clamp(x, 8, 1183), Geometry.clamp(y, 8, 692 - h), 200, h);
            c.hit("appearance.actions.outside", c.shell(), c::dismissOverlay, "");
            p.rect(box, t.popupRadius(), t.popup());
            p.outline(box, t.popupRadius(), 1, t.border());
            c.hit("appearance.actions.panel", box, () -> {}, "");
            item(
                    c,
                    t,
                    new Rect(box.x() + 4, box.y() + 4, 192, 33),
                    "refresh",
                    "Reset to default",
                    "reset",
                    () -> setting.reset(c.preferences()));
            if (setting.kind() == Kind.KEYBIND)
                item(
                        c,
                        t,
                        new Rect(box.x() + 4, box.y() + 37, 192, 33),
                        "close",
                        "Unbind",
                        "unbind",
                        () -> setting.set(c.preferences(), "NONE"));
        }

        private void item(
                PageContext c,
                Theme t,
                Rect r,
                String icon,
                String text,
                String id,
                Runnable action) {
            double hover =
                    c.animate(
                            "appearance.actions." + setting.key() + "." + id,
                            r.contains(c.mouseX(), c.mouseY()) ? 1 : 0,
                            .12,
                            Motion.Easing.FAST_OUT_SLOW_IN);
            c.painter()
                    .rect(r, Math.max(0, t.popupRadius() - 4), Theme.alpha(t.text(), hover * .1));
            c.painter().icon(t.iconPath(icon), r.x() + 10, r.y() + 9, 14, t.text());
            c.painter().text(text, r.x() + 32, r.y() + 6, 13, false, t.text());
            c.hit(
                    "appearance." + setting.key() + "." + id,
                    r,
                    () -> {
                        c.sound("click");
                        action.run();
                        c.dismissOverlay();
                    },
                    "");
        }

        public boolean key(
                PageContext c,
                ConfigView.Key key,
                String typed,
                boolean ctrl,
                boolean shift,
                boolean alt) {
            if (key == ConfigView.Key.ESCAPE) {
                c.dismissOverlay();
                return true;
            }
            return false;
        }
    }
}
