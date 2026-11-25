package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** ColorOption.kt:303-825. HUD colors persist chroma, while plain host colors expose alpha/hex only. */
final class HudColorPicker implements UiPage {
    private final HudDesigner designer;
    private final IntConsumer changed;
    private final HudNode node;
    private final String prefix;
    private final Rect source;
    private final TextEdit hex = new TextEdit(),
            alphaText = new TextEdit(),
            speedText = new TextEdit();
    private final HudChromeContext.Focus localFocus = new HudChromeContext.Focus();
    private final HudMotion motion = new HudMotion();
    private double hue, saturation, value, alpha, speed;
    private Rect box;
    private boolean chroma, closed;
    private TextEdit active;

    HudColorPicker(
            HudDesigner designer,
            Rect source,
            int color,
            IntConsumer changed,
            HudNode node,
            String prefix) {
        this.designer = designer;
        this.source = source;
        this.changed = changed;
        this.node = node;
        this.prefix = prefix;
        apply(color);
        speed = node == null ? 1 : node.number(prefix + "ChromaSpeed");
        chroma = node != null && node.flag(prefix + "Chroma");
        hex.text(hex(color));
        alphaText.text(String.valueOf((int) Math.round(alpha * 100)));
        speedText.text(String.format(Locale.ROOT, "%.1f", speed));
        designer.beginEdit("HUD 颜色");
    }

    private void apply(int color) {
        double[] hsv = hsv(color);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        alpha = ((color >>> 24) & 255) / 255d;
    }

    public void render(PageContext context) {
        PageContext c = context;
        double height =
                35
                        + 160
                        + 14
                        + 16
                        + 14
                        + 26
                        + 14
                        + 16
                        + 14
                        + 32
                        + 32
                        + (node == null ? 0 : 21 + 14)
                        + (chroma ? 60 : 0);
        double x = node != null ? source.x() : source.right() - 280,
                y = source.y() + (node != null ? 40 : 44);
        box = HudWidgets.clamp(new Rect(x, y, 280, height), c.width(), c.height());
        Painter p = c.painter();
        p.rect(box, c.theme().popupRadius(), c.theme().popup());
        p.outline(box, c.theme().popupRadius(), 1, c.theme().border());
        c.hit("hud:color/panel", box, () -> blur(c), "");
        double left = box.x() + 16, top = box.y() + 16, width = 248;
        HudWidgets.label(
                c,
                c.host().translate("oneconfig.ui.color_picker", "颜色选择器"),
                left,
                top,
                14,
                false,
                c.theme().text());
        Rect close = new Rect(left + width - 14, top + 3, 14, 14);
        p.icon("close", close.x(), close.y(), 14, c.theme().secondary());
        c.hit("hud:color/close", close, c::dismissOverlay, "");
        top += 35;
        Rect sv = new Rect(left, top, width, 160);
        p.save();
        try {
            p.clip(sv, c.theme().navigationRadius());
            int pure = HudRuntime.hsv(hue, 1, 1);
            p.gradient(sv, 0xFFFFFFFF, pure, 0xFF000000, 0xFF000000);
            Rect selector =
                    new Rect(
                            sv.x() + saturation * sv.w() - 7,
                            sv.y() + (1 - value) * sv.h() - 7,
                            14,
                            14);
            p.rect(selector, c.theme().circleRadius(14), color());
            p.outline(selector, c.theme().circleRadius(14), 2, 0xFFFFFFFF);
        } finally {
            p.restore();
        }
        c.hit(
                "hud:color/sv",
                sv,
                () -> {
                    blur(c);
                    c.startDrag(
                            (mx, my) -> {
                                saturation = Geometry.clamp((mx - sv.x()) / sv.w(), 0, 1);
                                value = 1 - Geometry.clamp((my - sv.y()) / sv.h(), 0, 1);
                                update(true);
                            },
                            () -> {});
                },
                "");
        top += 174;
        Rect hueBar = new Rect(left, top, width, 16);
        p.save();
        try {
            p.clip(hueBar, c.theme().navigationRadius());
            for (int i = 0; i < 6; i++) {
                int a = HudRuntime.hsv(i / 6d, 1, 1), b = HudRuntime.hsv((i + 1) / 6d, 1, 1);
                p.gradient(new Rect(left + i * width / 6, top, width / 6, 16), a, b, b, a);
            }
            thumb(c, hueBar, hue);
        } finally {
            p.restore();
        }
        c.hit(
                "hud:color/hue",
                hueBar,
                () -> {
                    blur(c);
                    c.startDrag(
                            (mx, my) -> {
                                hue = Geometry.clamp((mx - hueBar.x()) / hueBar.w(), 0, 1);
                                update(true);
                            },
                            () -> {});
                },
                "");
        top += 30;
        HudWidgets.label(
                c,
                c.host().translate("oneconfig.ui.opacity", "不透明度"),
                left,
                top + 5,
                12,
                false,
                c.theme().secondary());
        Rect opacityField = new Rect(left + width - 56, top, 56, 26);
        valueField(
                c,
                "opacity",
                alphaText,
                opacityField,
                "%",
                input -> {
                    String filtered = digits(input, 3);
                    alphaText.text(filtered);
                    if (!filtered.isEmpty()) {
                        alpha = Geometry.clamp(Integer.parseInt(filtered), 0, 100) / 100d;
                        update(true);
                    }
                });
        top += 40;
        Rect alphaBar = new Rect(left, top, width, 16);
        p.save();
        try {
            p.clip(alphaBar, c.theme().navigationRadius());
            checker(p, alphaBar, c);
            int opaque = HudRuntime.hsv(hue, saturation, value);
            p.gradient(alphaBar, opaque & 0xFFFFFF, opaque, opaque, opaque & 0xFFFFFF);
            thumb(c, alphaBar, alpha);
        } finally {
            p.restore();
        }
        c.hit(
                "hud:color/alpha",
                alphaBar,
                () -> {
                    blur(c);
                    c.startDrag(
                            (mx, my) -> {
                                alpha = Geometry.clamp((mx - alphaBar.x()) / alphaBar.w(), 0, 1);
                                alphaText.text(String.valueOf((int) Math.round(alpha * 100)));
                                update(true);
                            },
                            () -> {});
                },
                "");
        top += 30;
        if (node != null) {
            Rect rainbow = new Rect(left, top + 3.5, 14, 14);
            sweep(c, p, rainbow);
            HudWidgets.label(
                    c,
                    c.host().translate("oneconfig.ui.chroma", "流彩"),
                    left + 22,
                    top + 4,
                    13,
                    false,
                    c.theme().text());
            switchControl(c, new Rect(left + width - 74, top, 74, 21));
            top += 35;
        }
        if (chroma) {
            HudWidgets.label(
                    c,
                    c.host().translate("oneconfig.ui.speed", "速度"),
                    left,
                    top + 5,
                    12,
                    false,
                    c.theme().secondary());
            valueField(
                    c,
                    "speed",
                    speedText,
                    new Rect(left + width - 56, top, 56, 26),
                    "x",
                    input -> {
                        String filtered = HudPanel.filterNumber(input);
                        if (filtered.length() > 4) filtered = filtered.substring(0, 4);
                        speedText.text(filtered);
                        try {
                            double number = Double.parseDouble(filtered);
                            if (Double.isFinite(number)) {
                                speed = Geometry.clamp(number, .1, 4);
                                update(false);
                            }
                        } catch (NumberFormatException partial) {
                            /* Partial numeric entry is retained, as in BasicTextField. */
                        }
                    });
            top += 30;
            Rect speedBar = new Rect(left, top, width, 16);
            p.save();
            try {
                p.clip(speedBar, c.theme().navigationRadius());
                p.gradient(
                        speedBar,
                        c.theme().component(),
                        c.accent(),
                        c.accent(),
                        c.theme().component());
                thumb(c, speedBar, (speed - .1) / 3.9);
            } finally {
                p.restore();
            }
            c.hit(
                    "hud:color/speed",
                    speedBar,
                    () -> {
                        blur(c);
                        c.startDrag(
                                (mx, my) -> {
                                    speed =
                                            Geometry.clamp(
                                                    (mx - speedBar.x()) / speedBar.w() * 4, .1, 4);
                                    speedText.text(String.format(Locale.ROOT, "%.1f", speed));
                                    update(false);
                                },
                                () -> {});
                    },
                    "");
            top += 30;
        }
        Rect swatch = new Rect(left, top, 32, 32);
        p.rect(swatch, c.theme().navigationRadius(), color());
        p.outline(swatch, c.theme().navigationRadius(), 1, c.theme().border());
        Rect hexBox = new Rect(left + 42, top, 206, 32);
        p.rect(hexBox, c.theme().navigationRadius(), c.theme().component());
        p.outline(hexBox, c.theme().navigationRadius(), 1, c.theme().border());
        HudWidgets.label(c, "#", hexBox.x() + 10, hexBox.y() + 7, 13, false, c.theme().secondary());
        field(
                c,
                "hex",
                hex,
                new Rect(hexBox.x() + 24, hexBox.y(), hexBox.w() - 24, 32),
                13,
                input -> {
                    String filtered = alphanumeric(input, 8);
                    if (!filtered.equals(hex.text())) hex.text(filtered);
                    Integer color = parse(filtered);
                    if (color != null) {
                        apply(color);
                        update(false);
                    }
                });
    }

    private void valueField(
            PageContext c,
            String id,
            TextEdit edit,
            Rect field,
            String suffix,
            Consumer<String> onChange) {
        c.painter().rect(field, c.theme().navigationRadius(), c.theme().component());
        c.painter().outline(field, c.theme().navigationRadius(), 1, c.theme().border());
        double suffixWidth = c.painter().measure(suffix, 12, false);
        Rect input = new Rect(field.x() + 2, field.y(), field.w() - 16 - suffixWidth, field.h());
        field(c, id, edit, input, 12, onChange);
        HudWidgets.text(
                c,
                suffix,
                new Rect(field.right() - 8 - suffixWidth, field.y(), suffixWidth, field.h()),
                12,
                false,
                c.theme().secondary());
    }

    private void field(
            PageContext c,
            String id,
            TextEdit edit,
            Rect area,
            double size,
            Consumer<String> onChange) {
        if (c instanceof HudChromeContext chrome) {
            chrome.input("hud:color/" + id, edit, area, "", onChange, false, size);
            c.hit(
                    "hud:color/" + id,
                    area,
                    () -> {
                        active = edit;
                        chrome.focus.focus(edit, onChange);
                        edit.click(
                                c.mouseX() - area.x() - 12,
                                s -> c.painter().measure(s, size, false),
                                c.shift());
                    },
                    "");
        } else {
            c.textField("hud:color/" + id, edit, area, "", onChange);
            c.hit(
                    "hud:color/" + id,
                    area,
                    () -> {
                        active = edit;
                        localFocus.focus(edit, onChange);
                        edit.selectAll();
                    },
                    "");
        }
    }

    private void switchControl(PageContext c, Rect row) {
        Painter p = c.painter();
        HudWidgets.text(
                c,
                chroma ? "On" : "Off",
                new Rect(row.x(), row.y(), 24, 21),
                12,
                false,
                c.theme().secondary());
        Rect track = new Rect(row.right() - 42, row.y(), 42, 21);
        int base =
                motion.color(
                        "color/chroma-track",
                        chroma ? c.accent() : c.theme().controlTrack(),
                        c.time());
        p.rect(track, c.theme().circleRadius(21), base);
        double offset =
                motion.animate("color/chroma-thumb", chroma ? 24 : 3, c.time(), 1500, 1, .1);
        p.rect(
                new Rect(track.x() + offset, track.y() + 3, 15, 15),
                c.theme().circleRadius(15),
                c.theme().thumb());
        c.hit(
                "hud:color/chroma",
                track,
                () -> {
                    blur(c);
                    chroma = !chroma;
                    update(false);
                },
                "");
    }

    private static void sweep(PageContext c, Painter p, Rect r) {
        p.save();
        try {
            p.clip(r, c.theme().circleRadius(r.w()));
            double cx = r.x() + r.w() / 2, cy = r.y() + r.h() / 2;
            for (double y = r.y(); y < r.bottom(); y += .5)
                for (double x = r.x(); x < r.right(); x += .5) {
                    double h = (Math.atan2(y - cy, x - cx) / (Math.PI * 2) + 1) % 1;
                    p.rect(new Rect(x, y, .5, .5), 0, HudRuntime.hsv(h, 1, 1));
                }
        } finally {
            p.restore();
        }
    }

    private static void thumb(PageContext c, Rect bar, double fraction) {
        Rect thumb =
                new Rect(
                        bar.x() + Math.max(0, Math.round(fraction * bar.w() - 5)),
                        bar.y() + 3,
                        10,
                        10);
        c.painter().rect(thumb, c.theme().circleRadius(10), 0xFFFFFFFF);
        c.painter().outline(thumb, c.theme().checkboxRadius(), 1, 0x80FFFFFF);
    }

    private static void checker(Painter p, Rect r, PageContext c) {
        double density = Math.max(.01, c.host().uiDensity()), cell = 6 / density;
        for (double y = r.y(); y < r.bottom(); y += cell)
            for (double x = r.x(); x < r.right(); x += cell)
                p.rect(
                        new Rect(
                                x,
                                y,
                                Math.min(cell, r.right() - x),
                                Math.min(cell, r.bottom() - y)),
                        0,
                        (((int) Math.round((x - r.x()) / cell)
                                                        + (int) Math.round((y - r.y()) / cell))
                                                & 1)
                                        == 0
                                ? 0xFFCCCCCC
                                : 0xFF999999);
    }

    private void update(boolean rewriteHex) {
        if (node != null) {
            node.set(prefix + "Chroma", chroma);
            node.set(prefix + "ChromaSpeed", speed);
        }
        changed.accept(color());
        if (rewriteHex) hex.text(hex(color()));
    }

    private void blur(PageContext c) {
        active = null;
        localFocus.blur();
        if (c instanceof HudChromeContext cc) cc.focus.blur();
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
        if (key == ConfigView.Key.ESCAPE) {
            c.dismissOverlay();
            return true;
        }
        if (key == ConfigView.Key.ENTER) {
            blur(c);
            return true;
        }
        if (c instanceof HudChromeContext cc) return cc.focus.key(c, key, typed, ctrl, shift);
        return localFocus.key(c, key, typed, ctrl, shift);
    }

    public void leave(PageContext c) {
        if (closed) return;
        closed = true;
        blur(c);
        designer.commitEdit();
    }

    Rect bounds() {
        return box;
    }

    private int color() {
        return ((int) Math.round(alpha * 255) << 24)
                | (HudRuntime.hsv(hue, saturation, value) & 0xFFFFFF);
    }

    static String hex(int color) {
        return (color >>> 24) == 255
                ? String.format(Locale.ROOT, "%06X", color & 0xFFFFFF)
                : String.format(Locale.ROOT, "%08X", color);
    }

    static String alphanumeric(String input, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length() && out.length() < max; i++)
            if (Character.isLetterOrDigit(input.charAt(i))) out.append(input.charAt(i));
        return out.toString();
    }

    private static String digits(String input, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length() && out.length() < max; i++)
            if (Character.isDigit(input.charAt(i))) out.append(input.charAt(i));
        return out.toString();
    }

    static Integer parse(String s) {
        if (s.length() != 6 && s.length() != 8) return null;
        try {
            long color = Long.parseLong(s, 16);
            return (int) (s.length() == 6 ? color | 0xFF000000L : color);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static double[] hsv(int color) {
        double r = ((color >>> 16) & 255) / 255d,
                g = ((color >>> 8) & 255) / 255d,
                b = (color & 255) / 255d,
                max = Math.max(r, Math.max(g, b)),
                min = Math.min(r, Math.min(g, b)),
                d = max - min,
                h =
                        d == 0
                                ? 0
                                : max == r
                                        ? ((g - b) / d) % 6
                                        : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return new double[] {(h / 6 + 1) % 1, max == 0 ? 0 : d / max, max};
    }
}
