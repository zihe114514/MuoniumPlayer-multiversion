package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.model.HudElement.Anchor;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;
import java.util.function.*;

/** Floating 500px outer panel; every child keeps layout-pixel input coordinates. */
final class HudPanel {
    private record Target(
            Rect bounds, String id, boolean resetEnabled, Runnable reset, boolean keybind) {}

    private String expandedDropdown;
    private final HudMotion motion = new HudMotion();
    private final Map<String, AlignmentPulse> alignmentPulses = new HashMap<>();
    private final List<Target> targets = new ArrayList<>();
    private final Map<String, TextEdit> numberEdits = new HashMap<>();
    final TextEdit search = new TextEdit();
    private final HudStudio studio;
    private Rect bounds, body;
    private double scroll, total, offsetX, offsetY, cursor;
    private boolean userMoved;
    private HudElement retained;
    private PageContext c;
    private HudElement element;
    private HudNode node;
    private Rect clipBody;
    private boolean transitionInteractive = true;
    private final Map<Boolean, Double> tabScroll = new HashMap<>();

    HudPanel(HudStudio studio) {
        this.studio = studio;
    }

    Rect bounds() {
        return bounds;
    }

    boolean typing() {
        return search.focused;
    }

    void closed() {
        userMoved = false;
        offsetX = offsetY = scroll = 0;
        search.focused = false;
    }

    void render(PageContext context, double chromeAlpha) {
        c = context;
        targets.clear();
        boolean open = studio.panelOpen && studio.designer.primaryElement() != null;
        if (open) retained = studio.designer.primaryElement();
        double
                enter =
                        c.animate(
                                "hud/panel-enter",
                                open ? 1 : 0,
                                .30,
                                Motion.Easing.FAST_OUT_SLOW_IN),
                density = Math.max(.01, c.host().uiDensity()),
                slide =
                        motion.animate(
                                        "panel/slide",
                                        open ? 0 : Math.round(Math.min(500, c.width()) * density),
                                        c.time(),
                                        400,
                                        1,
                                        1)
                                / density;
        if (retained == null || enter <= .001) {
            bounds = null;
            return;
        }
        element = retained;
        node = element instanceof HudNode n ? n : null;
        double w = Math.min(468, Math.max(120, c.width() - 32)), h = Math.max(1, c.height() - 32);
        double leftScore = 0, rightScore = 0;
        for (HudElement e : studio.designer.elements()) {
            Rect r = studio.chromeRect(studio.designer.rect(e));
            double weight = studio.designer.selected().contains(e.id()) ? 8 : 1;
            leftScore += HudStudio.overlap(r, new Rect(0, 0, w + 32, c.height())) * weight;
            rightScore +=
                    HudStudio.overlap(r, new Rect(c.width() - w - 32, 0, w + 32, c.height()))
                            * weight;
        }
        double dock =
                c.animate(
                        "hud/panel-dock",
                        leftScore < rightScore ? 16 : Math.max(0, c.width() - w - 16),
                        .22,
                        Motion.Easing.FAST_OUT_SLOW_IN);
        double x = userMoved ? offsetX : dock, y = userMoved ? offsetY : 16;
        x = Geometry.clamp(x, 0, Math.max(0, c.width() - w));
        y = Geometry.clamp(y, 0, Math.max(0, c.height() - h));
        x += slide;
        bounds = new Rect(x, y, w, h);
        Painter p = c.painter();
        p.layer(bounds, chromeAlpha * enter);
        try {
            HudWidgets.panel(c, bounds);
            c.hit("hud:panel-block", bounds, () -> {}, "");
            HudWidgets.iconButton(
                    c,
                    "panel-close",
                    new Rect(x + 16, y + 16, 24, 24),
                    "close",
                    Theme.alpha(c.theme().text(), .7),
                    c.theme().text(),
                    () -> {
                        studio.panelOpen = false;
                        studio.designer.select(null, false);
                        closed();
                    });
            Rect sr = new Rect(Math.max(x + 52, bounds.right() - 213), y + 16, 181, 32);
            c.textField("hud:panel-search", search, sr, "Search...", v -> scroll = 0);
            Rect title = new Rect(x + 16, y + 64, w - 32, 52);
            p.icon("dots-grid", title.x(), title.y() + 4, 18, Theme.alpha(c.theme().text(), .5));
            HudWidgets.label(c, element.title(), x + 42, y + 60, 24, false, c.theme().text());
            HudWidgets.label(
                    c,
                    element.groupTitle(),
                    x + 42,
                    y + 90,
                    14,
                    false,
                    Theme.alpha(c.theme().text(), .5));
            c.hit(
                    "hud:panel-drag",
                    title,
                    () -> {
                        double sx = c.mouseX(), sy = c.mouseY(), bx = bounds.x(), by = bounds.y();
                        c.startDrag(
                                (mx, my) -> {
                                    userMoved = true;
                                    offsetX =
                                            Geometry.clamp(
                                                    bx + mx - sx, 0, Math.max(0, c.width() - w));
                                    offsetY =
                                            Geometry.clamp(
                                                    by + my - sy, 0, Math.max(0, c.height() - h));
                                },
                                () -> {});
                    },
                    "拖动面板");
            if (node != null && search.text().isBlank()) {
                HudWidgets.button(
                        c,
                        "tab-settings",
                        new Rect(x + 16, y + 124, 112, 32),
                        "Settings",
                        "settings",
                        !studio.designerTab,
                        true,
                        () -> {
                            tabScroll.put(studio.designerTab, scroll);
                            studio.designerTab = false;
                            scroll = tabScroll.getOrDefault(false, 0d);
                        },
                        "");
                HudWidgets.button(
                        c,
                        "tab-designer",
                        new Rect(x + 134, y + 124, 116, 32),
                        "Designer",
                        "paintbrush",
                        studio.designerTab,
                        true,
                        () -> {
                            tabScroll.put(studio.designerTab, scroll);
                            studio.designerTab = true;
                            scroll = tabScroll.getOrDefault(true, 0d);
                        },
                        "");
            }
            double top = node != null && search.text().isBlank() ? y + 188 : y + 140;
            body =
                    new Rect(
                            x + 16,
                            top,
                            Math.max(1, w - 48),
                            Math.max(1, bounds.bottom() - top - 16));
            clipBody = body;
            double progress =
                    c.animate(
                            "hud/panel-tab",
                            studio.designerTab ? 1 : 0,
                            .25,
                            Motion.Easing.FAST_OUT_SLOW_IN);
            p.save();
            try {
                p.clip(clipBody, 0);
                if (node != null && search.text().isBlank() && progress > .001 && progress < .999) {
                    Rect original = body;
                    PageContext interactiveContext = c;
                    double target = studio.designerTab ? 1 : 0;
                    for (int tab = 0; tab < 2; tab++) {
                        double opacity = tab == 0 ? 1 - progress : progress;
                        body =
                                new Rect(
                                        original.x() + (tab - progress) * original.w() / 3,
                                        original.y(),
                                        original.w(),
                                        original.h());
                        cursor = body.y() - scroll;
                        transitionInteractive = tab == target;
                        if (interactiveContext instanceof HudChromeContext chrome)
                            c = transitionInteractive ? chrome : chrome.silent();
                        p.layer(clipBody, opacity);
                        try {
                            if (tab == 0) settings();
                            else designer();
                            if (transitionInteractive)
                                total = Math.max(0, cursor - (body.y() - scroll));
                        } finally {
                            p.restore();
                        }
                    }
                    c = interactiveContext;
                    body = original;
                    transitionInteractive = true;
                } else {
                    cursor = body.y() - scroll;
                    if (node == null) hostSettings();
                    else {
                        if (!studio.designerTab || !search.text().isBlank()) settings();
                        if (studio.designerTab || !search.text().isBlank()) designer();
                    }
                    total = Math.max(0, cursor - (body.y() - scroll));
                }
            } finally {
                p.restore();
            }
            scroll = Geometry.clamp(scroll, 0, Math.max(0, total - body.h()));
            HudWidgets.scrollbar(
                    c,
                    "panel-scrollbar",
                    new Rect(body.x(), body.y(), body.w() + 16, body.h()),
                    total,
                    scroll,
                    v -> scroll = v);
        } finally {
            p.restore();
        }
    }

    boolean wheel(PageContext c, double delta) {
        if (bounds == null || !bounds.contains(c.mouseX(), c.mouseY())) return false;
        scroll = Geometry.clamp(scroll - delta * 44, 0, Math.max(0, total - body.h()));
        return true;
    }

    private boolean matches(String... labels) {
        String q = search.text().strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        for (String label : labels) if (label.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private void section(String title) {
        cursor += 6;
        HudWidgets.label(
                c,
                title.toUpperCase(Locale.ROOT),
                body.x(),
                cursor,
                12,
                false,
                c.theme().secondary());
        cursor += 28;
    }

    private void gap() {
        cursor += 22;
    }

    private Rect row(double h) {
        Rect r = new Rect(body.x(), cursor, body.w(), h);
        cursor += h + 12;
        return r;
    }

    private boolean fully(Rect r) {
        return transitionInteractive
                && r.y() >= clipBody.y()
                && r.bottom() <= clipBody.bottom()
                && r.x() >= clipBody.x()
                && r.right() <= clipBody.right() + .001;
    }

    private void action(String id, String label, boolean enabled, Runnable action, boolean danger) {
        Rect r = row(30);
        if (fully(r))
            HudWidgets.button(
                    c, id, r, label, danger ? "trash" : "refresh", false, enabled, action, "");
    }

    private void toggle(String id, String label, BooleanSupplier value, Consumer<Boolean> set) {
        Rect r = row(24);
        if (!fully(r)) return;
        boolean on = value.getAsBoolean();
        HudWidgets.text(
                c,
                label,
                new Rect(r.x() + 82, r.y(), r.w() - 82, r.h()),
                14,
                false,
                c.theme().text());
        HudWidgets.text(
                c,
                on ? "On" : "Off",
                new Rect(r.x(), r.y(), 24, 24),
                12,
                false,
                c.theme().secondary());
        Rect sw = new Rect(r.x() + 32, r.y() + 1.5, 42, 21);
        double t =
                c.animate(
                        "hud/switch/" + element.id() + "/" + id,
                        on ? 1 : 0,
                        .15,
                        Motion.Easing.FAST_OUT_SLOW_IN);
        double offset =
                c.animate(
                        "hud/switch-thumb/" + element.id() + "/" + id,
                        on ? 24 : 3,
                        .25,
                        Motion.Easing.SPRING);
        c.painter()
                .rect(
                        sw,
                        c.theme().circleRadius(21),
                        motion.color(
                                "hud/switch-track/" + element.id() + "/" + id,
                                on ? c.accent() : c.theme().controlTrack(),
                                c.time()));
        c.painter()
                .rect(
                        new Rect(sw.x() + offset, sw.y() + 3, 15, 15),
                        c.theme().circleRadius(15),
                        c.theme().thumb());
        c.hit(
                "hud:setting/" + id,
                r,
                () -> studio.designer.edit(label, () -> set.accept(!value.getAsBoolean())),
                "");
        target(r, id, null);
    }

    private void toggle(String key, String label) {
        toggle(key, label, () -> node.flag(key), v -> node.set(key, v));
    }

    private void number(
            String id,
            String label,
            double min,
            double max,
            double step,
            DoubleSupplier value,
            DoubleConsumer change) {
        Rect r = row(28);
        if (!fully(r)) return;
        double fieldWidth = Math.min(176, r.w());
        Rect field = new Rect(r.x(), r.y(), fieldWidth, 26);
        Painter p = c.painter();
        p.rect(field, c.theme().radius(5), c.theme().component());
        p.outline(field, c.theme().radius(5), 1, c.theme().border());
        TextEdit edit = numberEdits.computeIfAbsent(element.id() + "/" + id, k -> new TextEdit());
        if (!edit.focused) edit.text(ConfigView.format(value.getAsDouble()));
        double labelWidth =
                label.isBlank() ? 0 : Math.min(fieldWidth - 50, p.measure(label, 13, false) + 8);
        if (!label.isBlank())
            HudWidgets.text(
                    c,
                    label,
                    new Rect(field.x() + 8, field.y(), labelWidth, 26),
                    13,
                    false,
                    c.theme().text());
        Rect input =
                new Rect(
                        field.x() + labelWidth,
                        field.y(),
                        Math.max(14, field.w() - labelWidth - 20),
                        26);
        Consumer<String> changed =
                text -> {
                    String filtered = filterNumber(text);
                    if (!filtered.equals(text)) edit.text(filtered);
                    try {
                        double v = Double.parseDouble(filtered);
                        if (Double.isFinite(v)) change.accept(Geometry.clamp(v, min, max));
                    } catch (NumberFormatException partial) {
                        /* '-', '.' and empty text are pending number input, not committed values. */
                    }
                };
        if (c instanceof HudChromeContext chrome) {
            chrome.input("hud:number/" + id, edit, input, "", changed, false, 13);
            c.hit(
                    "hud:number/" + id,
                    input,
                    () -> {
                        studio.designer.beginEdit(label);
                        chrome.focus.focus(edit, changed);
                        chrome.focus.transaction(
                                studio.designer::commitEdit, studio.designer::cancelEdit);
                        edit.selectAll();
                    },
                    "");
        } else c.textField("hud:number/" + id, edit, input, "", changed);
        for (int direction : new int[] {1, -1}) {
            Rect arrow =
                    new Rect(field.right() - 20, field.y() + (direction == 1 ? 0 : 13), 18, 13);
            boolean enabled =
                    direction == 1 ? value.getAsDouble() < max : value.getAsDouble() > min;
            boolean hover = arrow.contains(c.mouseX(), c.mouseY());
            int color =
                    enabled
                            ? (hover ? c.theme().text() : c.theme().secondary())
                            : Theme.alpha(c.theme().secondary(), .4);
            p.icon(direction == 1 ? "up" : "down", arrow.x() + 2, arrow.y() - .5, 14, color);
            c.hit(
                    "hud:number/" + id + (direction == 1 ? "/+" : "/-"),
                    arrow,
                    enabled
                            ? () ->
                                    studio.designer.edit(
                                            label,
                                            () ->
                                                    change.accept(
                                                            Geometry.clamp(
                                                                    value.getAsDouble()
                                                                            + direction * step,
                                                                    min,
                                                                    max)))
                            : () -> {},
                    "");
        }
        target(field, id, null);
    }

    static String filterNumber(String input) {
        StringBuilder out = new StringBuilder();
        boolean dot = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '-' && i == 0) out.append(ch);
            else if (ch == '.' && !dot) {
                dot = true;
                out.append(ch);
            } else if (Character.isDigit(ch)) out.append(ch);
        }
        return out.toString();
    }

    private void number(String key, String label, double min, double max, double step) {
        number(key, label, min, max, step, () -> node.number(key), v -> node.set(key, v));
    }

    private void choice(String key, String title, List<String> choices) {
        choice(key, title, choices, () -> node.get(key, choices.get(0)), v -> node.set(key, v));
    }

    private void choice(
            String id,
            String label,
            List<String> choices,
            Supplier<String> read,
            Consumer<String> write) {
        Rect r = row(62);
        if (!fully(r)) return;
        HudWidgets.label(c, label, r.x(), r.y(), 14, false, c.theme().text());
        Rect button = new Rect(r.x(), r.y() + 28, Math.min(175, r.w()), 34);
        boolean expanded = id.equals(expandedDropdown),
                hover = button.contains(c.mouseX(), c.mouseY());
        int color =
                motion.color(
                        "dropdown-text/" + element.id() + "/" + id,
                        hover || expanded ? c.theme().text() : c.theme().secondary(),
                        c.time());
        Painter p = c.painter();
        p.rect(button, 8, 0xFF192126);
        p.outline(button, 8, 1, c.theme().border());
        HudWidgets.text(
                c,
                read.get(),
                new Rect(button.x() + 12, button.y(), button.w() - 36, button.h()),
                12,
                false,
                color);
        double angle =
                motion.animate(
                        "dropdown-angle/" + element.id() + "/" + id,
                        expanded ? 0 : 180,
                        c.time(),
                        1500,
                        1,
                        .01);
        drawDropdownArrow(p, button.right() - 24, button.y() + 11, angle, color);
        c.hit(
                "hud:choice/" + id,
                button,
                () -> {
                    expandedDropdown = id;
                    c.overlay(
                            new HudWidgets.Choices(
                                    button,
                                    choices,
                                    read.get(),
                                    v -> studio.designer.edit(label, () -> write.accept(v)),
                                    () -> expandedDropdown = null));
                },
                "");
        target(r, id, null);
    }

    private static void drawDropdownArrow(
            Painter painter, double x, double y, double angle, int color) {
        if (Math.abs(angle) < .01) painter.icon("up", x, y, 12, color);
        else if (Math.abs(angle - 180) < .01) painter.icon("down", x, y, 12, color);
        else {
            double cos = Math.cos(Math.toRadians(angle)), sin = Math.sin(Math.toRadians(angle));
            double[][] points = {{-3.96, 1.525}, {0, -1.525}, {3.96, 1.525}};
            for (int i = 0; i < 2; i++) {
                double[] a = points[i], b = points[i + 1];
                painter.line(
                        x + 6 + a[0] * cos - a[1] * sin,
                        y + 6 + a[0] * sin + a[1] * cos,
                        x + 6 + b[0] * cos - b[1] * sin,
                        y + 6 + b[0] * sin + b[1] * cos,
                        .75,
                        color);
            }
        }
    }

    private void text(String key, String label) {
        Rect r = row(46);
        if (!fully(r)) return;
        HudWidgets.label(c, label, r.x(), r.y(), 12, false, c.theme().text());
        HudWidgets.button(
                c,
                "text/" + key,
                new Rect(r.x(), r.y() + 18, r.w(), 28),
                node.get(key, ""),
                "text-input",
                false,
                true,
                () ->
                        c.overlay(
                                new HudWidgets.Input(
                                        label,
                                        node.get(key, ""),
                                        v -> studio.designer.edit(label, () -> node.set(key, v)))),
                "");
        target(r, key, null);
    }

    private void color(String key, String prefix, String label) {
        Rect r = row(56);
        if (!fully(r)) return;
        HudWidgets.label(c, label, r.x(), r.y(), 14, false, c.theme().text());
        Rect sw = new Rect(r.x(), r.y() + 24, 32, 32);
        int display = HudRuntime.color(node, prefix, c.time());
        c.painter().rect(sw, c.theme().radius(5), display);
        c.painter().outline(sw, c.theme().radius(5), 1, c.theme().border());
        c.hit(
                "hud:color/" + key,
                sw,
                () ->
                        c.overlay(
                                new HudColorPicker(
                                        studio.designer,
                                        sw,
                                        node.color(key),
                                        v -> node.set(key, v),
                                        node,
                                        prefix)),
                "颜色和流彩");
        target(r, key, null);
    }

    private void alignment(String key, String title) {
        Rect r = row(125);
        if (!fully(r)) return;
        Painter p = c.painter();
        Rect grid = new Rect(r.x(), r.y(), 125, 125);
        p.rect(grid, 8, 0xB3192126);
        p.outline(grid, 8, 1, c.theme().border());
        Anchor active = key.equals("growthAnchor") ? node.growthAnchor() : node.anchor(key);
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 3; x++) {
                Anchor a = Anchor.at(x, y);
                Rect cell =
                        new Rect(
                                grid.x() + x * 125d / 3,
                                grid.y() + y * 125d / 3,
                                125d / 3,
                                125d / 3);
                boolean hot = cell.contains(c.mouseX(), c.mouseY()), selected = a == active;
                double opacity =
                        c.animate(
                                "hud/alignment-hover/" + key + a,
                                hot && !selected ? .08 : 0,
                                .15,
                                Motion.Easing.FAST_OUT_SLOW_IN);
                if (opacity > 0) p.rect(cell, 0, Theme.alpha(c.theme().text(), opacity));
                int color = selected ? c.accent() : hot ? c.theme().text() : c.theme().secondary();
                double
                        scale =
                                alignmentPulses
                                        .computeIfAbsent(
                                                element.id() + "/" + key + "/" + a,
                                                k -> new AlignmentPulse())
                                        .value(selected, c.time()),
                        len = 8 * scale,
                        stroke = 1.5 * scale,
                        cx = cell.x() + cell.w() / 2,
                        cy = cell.y() + cell.h() / 2;
                if (x == 1 && y == 1) {
                    double arm = len * .8;
                    p.line(cx - arm, cy, cx + arm, cy, stroke * 1.2, color);
                    p.line(cx, cy - arm, cx, cy + arm, stroke * 1.2, color);
                } else if (x != 1 && y != 1) {
                    double pad = cell.w() * .22,
                            px = x == 0 ? cell.x() + pad : cell.right() - pad,
                            py = y == 0 ? cell.y() + pad : cell.bottom() - pad;
                    p.line(px, py, px + (x == 0 ? 1 : -1) * len, py, stroke, color);
                    p.line(px, py, px, py + (y == 0 ? 1 : -1) * len, stroke, color);
                } else if (y == 1) {
                    double tip = cx + (x == 0 ? 1 : -1) * len * .5,
                            base = cx + (x == 0 ? -1 : 1) * len * .3;
                    p.line(base, cy, tip, cy, stroke, color);
                    p.line(tip, cy - len * .5, tip, cy + len * .5, stroke, color);
                } else {
                    double tip = cy + (y == 0 ? 1 : -1) * len * .5,
                            base = cy + (y == 0 ? -1 : 1) * len * .3;
                    p.line(cx, base, cx, tip, stroke, color);
                    p.line(cx - len * .5, tip, cx + len * .5, tip, stroke, color);
                }
                c.hit(
                        "hud:anchor-grid/" + key + "/" + a,
                        cell,
                        () ->
                                studio.designer.edit(
                                        title,
                                        () -> {
                                            if (key.equals("growthAnchor")) node.growthAnchor(a);
                                            else node.set(key, a.name());
                                        }),
                        a.name());
            }
        HudWidgets.wrap(
                c,
                key.equals("growthAnchor")
                        ? "Pins this corner or edge in place;\nthe HUD only grows away from it"
                        : "Aligns the content inside the fixed box",
                r.x() + 141,
                r.y() + 40,
                Math.max(40, r.w() - 145),
                12,
                17,
                c.theme().secondary());
        target(grid, key, null);
    }

    private void target(Rect region, String id, Runnable hostReset) {
        if (!transitionInteractive) return;
        boolean enabled =
                hostReset != null || node != null && node.hasPropertyDefault(property(id));
        Runnable reset =
                hostReset != null
                        ? hostReset
                        : () -> {
                            String key = property(id);
                            if (key.equals("locked")) c.preferences().locked(element.id(), false);
                            else
                                node.resetProperty(
                                        key,
                                        studio.designer.viewportWidth(),
                                        studio.designer.viewportHeight());
                        };
        targets.add(
                new Target(
                        region,
                        id,
                        enabled,
                        reset,
                        id.equals("toggleKey") || id.equals("showKey")));
        String description = description(property(id));
        if (c instanceof HudChromeContext cc && !description.isBlank())
            cc.tooltip("hud:property-tip/" + id, region, description, 260);
    }

    private static String description(String key) {
        return switch (key) {
            case "growthAnchor" ->
                    "The point this HUD stays pinned to when its size changes, so it only ever"
                        + " grows away from that point.";
            case "staticWidth" ->
                    "Keeps this HUD at a fixed width and height. This also enables content"
                        + " alignment inside the HUD box.";
            case "useGuiScale" ->
                    "Uses Minecraft's current GUI scale for this HUD. Turn this off to use a custom"
                        + " HUD scale.";
            case "mergeBackground" ->
                    "Fuses this HUD's background with the background of HUDs it touches, so they"
                        + " draw as one shape.";
            case "mergeDiagonally" ->
                    "Also fuses backgrounds with HUDs sitting diagonally from this one, which meet"
                        + " it at a corner or only partly along an edge.";
            case "keepBgWhenHidden" ->
                    "Keeps this HUD's background in the fused shape while the HUD is hidden, so the"
                        + " shape stays whole instead of losing a piece.";
            default -> "";
        };
    }

    private static String property(String id) {
        return switch (id) {
            case "lock" -> "locked";
            case "fontSize" -> "textScale";
            default -> id;
        };
    }

    boolean rightClick(PageContext context) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            Target t = targets.get(i);
            if (!t.bounds().contains(context.mouseX(), context.mouseY())) continue;
            context.overlay(
                    new HudPropertyMenu(
                            context.mouseX(),
                            context.mouseY(),
                            t.resetEnabled(),
                            () -> studio.designer.edit("重置 " + t.id(), t.reset()),
                            t.keybind(),
                            () -> studio.designer.edit("解除按键", () -> node.set(t.id(), -1))));
            return true;
        }
        return false;
    }

    private void columns(double[] widths, double gap, Runnable... renderers) {
        Rect original = body;
        double begin = cursor, max = cursor, x = body.x();
        for (int i = 0; i < renderers.length; i++) {
            body = new Rect(x, original.y(), widths[i], original.h());
            cursor = begin;
            renderers[i].run();
            max = Math.max(max, cursor);
            x += widths[i] + gap;
        }
        body = original;
        cursor = max;
    }

    static final class AlignmentPulse {
        private boolean selected, initialized;
        private double start, from = 1, current = 1;

        double value(boolean next, double now) {
            if (!initialized) {
                initialized = true;
                selected = next;
                start = now;
                if (!next) return 1;
            } else if (next != selected) {
                if (next) {
                    from = current;
                    start = now;
                }
                selected = next;
            }
            if (!selected) return current;
            double duration = HudMotion.settlingSeconds(from, 1.15, 0, 1500, .5f, .01),
                    elapsed = Math.max(0, now - start);
            if (elapsed < duration)
                current = HudMotion.sample(from, 1.15, 0, elapsed, 1500, .5f).value();
            else
                current =
                        1.15
                                + (1 - 1.15)
                                        * Motion.ease(
                                                Motion.Easing.FAST_OUT_SLOW_IN,
                                                Math.min(1, (elapsed - duration) / .15));
            return current;
        }
    }

    private void hostSettings() {
        if (matches("Position", "位置", "Lock", "锁定", "Scale", "缩放")) {
            section("Position");
            Rect pos = studio.designer.rect(element);
            number(
                    "x",
                    "X",
                    0,
                    studio.designer.viewportWidth(),
                    1,
                    () -> studio.designer.rect(element).x(),
                    v -> studio.designer.position(element, v, studio.designer.rect(element).y()));
            number(
                    "y",
                    "Y",
                    0,
                    studio.designer.viewportHeight(),
                    1,
                    () -> studio.designer.rect(element).y(),
                    v -> studio.designer.position(element, studio.designer.rect(element).x(), v));
            toggle(
                    "lock",
                    "Lock position",
                    () -> studio.designer.locked(element.id()),
                    v -> c.preferences().locked(element.id(), v));
            if (element.supportsScale())
                number("scale", "Scale", .25, 4, .25, element::scale, element::scale);
            gap();
        }
        List<ConfigHost.Group> groups = studio.hostGroups(element);
        if (groups.isEmpty()) {
            Rect r = row(72);
            HudWidgets.wrap(
                    c,
                    "主项目专属设置尚未接入 HudExtensions.hudSettings；位置、缩放和显隐仍直接使用真实 HUD。",
                    r.x(),
                    r.y(),
                    r.w(),
                    12,
                    18,
                    c.theme().secondary());
        }
        for (var g : groups) {
            List<ConfigHost.Option> opts =
                    g.options().stream()
                            .filter(o -> matches(g.title(), o.title(), o.description()))
                            .toList();
            if (opts.isEmpty()) continue;
            section(g.title());
            for (var o : opts) hostOption(o);
            gap();
        }
    }

    private void hostOption(ConfigHost.Option o) {
        double start = cursor;
        if (!o.enabled()) {
            Rect r = row(44);
            HudWidgets.text(c, o.title(), r, 12, false, c.theme().secondary());
            if (fully(r)) c.tooltip("hud:disabled/" + o.id(), r, o.disabledReason().get());
            return;
        }
        switch (o.kind()) {
            case TOGGLE ->
                    toggle(
                            "host/" + o.id(),
                            o.title(),
                            () -> Boolean.TRUE.equals(o.value()),
                            v -> o.set(v));
            case SLIDER ->
                    number(
                            "host/" + o.id(),
                            o.title(),
                            o.min(),
                            o.max(),
                            o.step() > 0 ? o.step() : (o.max() - o.min()) / 100,
                            () -> ((Number) o.value()).doubleValue(),
                            v -> o.set(v));
            case CHOICE ->
                    choice(
                            "host/" + o.id(),
                            o.title(),
                            o.choices(),
                            () -> String.valueOf(o.value()),
                            o::set);
            case COLOR -> {
                Rect r = row(44);
                if (fully(r)) {
                    HudWidgets.label(c, o.title(), r.x(), r.y(), 12, false, c.theme().text());
                    Rect sw = new Rect(r.right() - 44, r.y(), 32, 32);
                    c.painter().rect(sw, 4, ((Number) o.value()).intValue());
                    c.hit(
                            "hud:host-color/" + o.id(),
                            sw,
                            () ->
                                    c.overlay(
                                            new HudColorPicker(
                                                    studio.designer,
                                                    sw,
                                                    ((Number) o.value()).intValue(),
                                                    o::set,
                                                    null,
                                                    "")),
                            "");
                }
            }
            case ACTION ->
                    action(
                            "host/" + o.id(),
                            o.title(),
                            true,
                            () -> studio.designer.edit(o.title(), () -> o.set("")),
                            false);
        }
        Rect region = new Rect(body.x(), start, body.w(), Math.max(0, cursor - start - 12));
        if (fully(region)) {
            target(region, "host/" + o.id(), o.reset());
            if (c instanceof HudChromeContext cc && !o.description().isBlank())
                cc.tooltip("hud:host-property-tip/" + o.id(), region, o.description(), 260);
        }
    }

    private void settings() {
        if (matches("Position", "位置", "Lock", "锁定", "Reset")) {
            section("Position");
            action(
                    "reset-position",
                    "Reset to default position",
                    node.hasResetDefaults(),
                    () -> studio.designer.resetSelection(true),
                    false);
            toggle(
                    "lock",
                    "Lock position",
                    () -> studio.designer.locked(node.id()),
                    v -> c.preferences().locked(node.id(), v));
            gap();
        }
        if (matches("GUI Scale", "Use Minecraft GUI Scale", "Custom Scale", "缩放")) {
            section("GUI Scale");
            toggle("useGuiScale", "Use Minecraft GUI Scale");
            number("customScale", "Custom Scale", .25, 4, .25);
            gap();
        }
        if (matches(
                "Background",
                "Show Background",
                "Background Color",
                "Radius",
                "Merge With Neighbours",
                "Merge Diagonally",
                "Keep Background When Hidden",
                "背景",
                "合并")) {
            section("Background");
            toggle("showBackground", "Show Background");
            if (node.flag("showBackground")) {
                columns(
                        new double[] {140, 116},
                        12,
                        () -> color("bgColor", "bg", "Background Color"),
                        () -> number("bgRadius", "Radius", 0, 32, 1));
                if (node.mergeable()) {
                    toggle("mergeBackground", "Merge With Neighbours");
                    if (node.flag("mergeBackground")) {
                        toggle("mergeDiagonally", "Merge Diagonally");
                        toggle("keepBgWhenHidden", "Keep Background When Hidden");
                    }
                }
            }
            gap();
        }
        if (matches(
                "Appearance",
                "Text Color",
                "Text Shadow",
                "Full Shadow",
                "Shadow Color",
                "阴影",
                "颜色")) {
            section("Appearance");
            color("textColor", "text", "Text Color");
            toggle("showShadow", "Text Shadow");
            if (node.flag("showShadow")) {
                toggle("fullShadow", "Full Shadow");
                color("shadowColor", "shadow", "Shadow Color");
            }
            gap();
        }
        if (matches(
                "Visibility",
                "Show in F3 Screen",
                "Show in Tab List",
                "Show in GUIs",
                "Show in Chat",
                "可见")) {
            section("Visibility");
            toggle("showInF3", "Show in F3 Screen");
            toggle("showInTab", "Show in Tab List");
            toggle("showInScreens", "Show in GUIs");
            toggle("showInChat", "Show in Chat");
            gap();
        }
        if (matches("Text", "Prefix", "Suffix", "Brackets", "Template", "图标", "文本")) {
            section(node.type().equals("icon") ? "Icon" : "Text");
            if (node.type().equals("icon")) {
                text("icon", "Icon name");
                number("iconSize", "Icon size", 6, 256, 1);
            } else {
                text("prefix", "Text Prefix");
                text(
                        node.type().equals("text") ? "text" : "template",
                        node.type().equals("text") ? "Text" : "Time template");
                text("suffix", "Text Suffix");
                toggle("brackets", "Brackets");
            }
            gap();
        }
        if (matches("Keys", "Toggle", "Show key", "按键")) {
            section("Keybinds");
            keybind("toggleKey", "Toggle visibility key");
            keybind("showKey", "Hold to show key");
            gap();
        }
        if (matches("Delete HUD", "Reset all", "重置", "删除")) {
            action(
                    "reset-all",
                    "Reset all to default",
                    true,
                    () -> studio.designer.resetSelection(false),
                    false);
            action(
                    "delete",
                    "Delete HUD",
                    node.deletable(),
                    () -> {
                        studio.designer.deleteSelection();
                        studio.panelOpen = false;
                    },
                    true);
        }
    }

    private void keybind(String key, String label) {
        Rect r = row(36);
        if (fully(r))
            HudWidgets.button(
                    c,
                    "keybind/" + key,
                    r,
                    label + ": " + ((int) node.number(key) < 0 ? "未绑定" : (int) node.number(key)),
                    "",
                    false,
                    true,
                    () -> c.overlay(new KeyCapture(node, key, label, studio.designer)),
                    "");
        target(r, key, null);
    }

    private void designer() {
        if (matches("Size & Alignment", "Static Size", "Width", "Height", "尺寸", "对齐")) {
            section("Size & Alignment");
            toggle(
                    "staticWidth",
                    "Static Size",
                    () -> node.flag("staticWidth"),
                    v -> {
                        if (v) {
                            node.set("staticW", node.width() / node.effectiveScale());
                            node.set("staticH", node.height() / node.effectiveScale());
                        }
                        node.set("staticWidth", v);
                    });
            if (node.flag("staticWidth")) {
                columns(
                        new double[] {132, 132},
                        12,
                        () -> number("staticW", "Width", 20, 2000, 1),
                        () -> number("staticH", "Height", 8, 2000, 1));
            }
            alignment(
                    node.flag("staticWidth") ? "alignment" : "growthAnchor",
                    node.flag("staticWidth") ? "Content alignment" : "Growth anchor");
            gap();
        }
        if (matches("Padding", "Edge padding", "内边距")) {
            section("Padding");
            columns(
                    new double[] {88, 88, 88, 88},
                    12,
                    () -> number("padLeft", "←", 0, 100, 1),
                    () -> number("padRight", "→", 0, 100, 1),
                    () -> number("padTop", "↑", 0, 100, 1),
                    () -> number("padBottom", "↓", 0, 100, 1));
            gap();
        }
        if (matches(
                "Text Options",
                "Font",
                "Font size",
                "Modifiers",
                "Bold",
                "Italic",
                "Weight",
                "Align",
                "Case Type",
                "字体",
                "文字")) {
            section("Text Options");
            Rect preview = row(58);
            if (fully(preview)) {
                c.painter().rect(preview, c.theme().radius(5), c.theme().component());
                if (c instanceof HudChromeContext chrome)
                    chrome.textPreview(studio.runtime, node, preview);
                else studio.runtime.textPreview(c.painter(), node, preview, c.time());
            }
            columns(
                    new double[] {175, 112, 96},
                    16,
                    () -> choice("font", "Font", List.of("Minecraft", "Poppins")),
                    () -> {
                        HudWidgets.label(
                                c, "Font size", body.x(), cursor, 14, false, c.theme().text());
                        cursor += 28;
                        number(
                                "fontSize",
                                "",
                                6,
                                64,
                                1,
                                () -> node.number("textScale") * 14,
                                v -> node.set("textScale", v / 14));
                    },
                    this::modifiers);
            columns(
                    new double[] {175, 108, 108},
                    12,
                    () ->
                            choice(
                                    "textWeight",
                                    "Weight",
                                    List.of("Thin", "Regular", "Medium", "Bold", "Black")),
                    () ->
                            radio(
                                    "textAlign",
                                    "Align",
                                    List.of("align-left", "align-center", "align-right")),
                    () ->
                            radio(
                                    "caseType",
                                    "Case Type",
                                    List.of("capitalized", "uppercase", "lowercase")));
            gap();
        }
    }

    private void modifiers() {
        Rect r = row(62);
        if (!fully(r)) return;
        HudWidgets.label(c, "Modifiers", r.x(), r.y(), 14, false, c.theme().text());
        Rect box = new Rect(r.x(), r.y() + 28, 78, 34);
        c.painter().rect(box, c.theme().radius(5), c.theme().component());
        c.painter().outline(box, c.theme().radius(5), 1, c.theme().border());
        for (int i = 0; i < 2; i++) {
            String key = i == 0 ? "textBold" : "textItalic";
            Rect icon = new Rect(box.x() + 14 + i * 32, box.y() + 8, 18, 18);
            c.painter()
                    .icon(
                            i == 0 ? "bold" : "italic",
                            icon.x(),
                            icon.y(),
                            18,
                            node.flag(key) ? c.accent() : c.theme().secondary());
            c.hit(
                    "hud:modifier/" + key,
                    icon,
                    () -> studio.designer.edit(key, () -> node.set(key, !node.flag(key))),
                    key);
            target(icon, key, null);
        }
    }

    private void radio(String key, String label, List<String> icons) {
        Rect r = row(64);
        if (!fully(r)) return;
        HudWidgets.label(c, label, r.x(), r.y(), 14, false, c.theme().text());
        Rect track = new Rect(r.x(), r.y() + 28, 108, 36);
        c.painter().rect(track, 6, 0xB3192126);
        c.painter().outline(track, 6, 1, c.theme().border());
        for (int i = 0; i < 3; i++) {
            int index = i;
            Rect cell = new Rect(track.x() + i * 36, track.y(), 36, 36);
            boolean selected = (int) node.number(key) == i;
            c.painter()
                    .icon(
                            icons.get(i),
                            cell.x() + 9,
                            cell.y() + 9,
                            18,
                            selected ? c.accent() : c.theme().secondary());
            c.hit(
                    "hud:radio/" + key + "/" + i,
                    cell,
                    () -> studio.designer.edit(label, () -> node.set(key, index)),
                    label);
        }
        target(track, key, null);
    }

    private static final class KeyCapture
            implements UiPage, com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput {
        public boolean recording() {
            return true;
        }

        public boolean rawKey(PageContext c, String token, boolean pressed) {
            if (!pressed) return true;
            String name = HudBindings.canonical(token);
            ConfigView.Key key =
                    name.equals("ESCAPE")
                            ? ConfigView.Key.ESCAPE
                            : name.equals("DELETE") ? ConfigView.Key.DELETE : ConfigView.Key.NONE;
            return key(c, key, "", c.control(), c.shift(), c.alt());
        }

        public boolean rawMouse(PageContext c, int button, boolean pressed) {
            return true;
        }

        private final HudNode node;
        private final String key, label;
        private final HudDesigner designer;

        KeyCapture(HudNode node, String key, String label, HudDesigner designer) {
            this.node = node;
            this.key = key;
            this.label = label;
            this.designer = designer;
        }

        public void render(PageContext c) {
            Rect r =
                    new Rect(
                            Math.max(0, (c.width() - 300) / 2),
                            Math.max(0, (c.height() - 110) / 2),
                            Math.min(300, c.width()),
                            110);
            c.painter().rect(new Rect(0, 0, c.width(), c.height()), 0, 0x55000000);
            HudWidgets.panel(c, r);
            HudWidgets.label(c, label, r.x() + 16, r.y() + 16, 14, true, c.theme().text());
            HudWidgets.wrap(
                    c,
                    "按下要绑定的按键；Delete 清除，Esc 取消",
                    r.x() + 16,
                    r.y() + 50,
                    r.w() - 32,
                    12,
                    18,
                    c.theme().secondary());
        }

        public boolean pointerDown(PageContext c, int b) {
            c.dismissOverlay();
            return true;
        }

        public boolean key(
                PageContext c,
                ConfigView.Key k,
                String typed,
                boolean ctrl,
                boolean shift,
                boolean alt) {
            if (k == ConfigView.Key.ESCAPE) {
                c.dismissOverlay();
                return true;
            }
            int code = k == ConfigView.Key.DELETE ? -1 : c.nativeKeyCode();
            if (code >= 0 || k == ConfigView.Key.DELETE) {
                designer.edit(label, () -> node.set(key, code));
                c.dismissOverlay();
            }
            return true;
        }
    }
}
