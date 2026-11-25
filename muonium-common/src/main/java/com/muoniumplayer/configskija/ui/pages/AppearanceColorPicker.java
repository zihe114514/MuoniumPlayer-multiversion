package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.function.Consumer;

/** HSV, alpha, chroma and two-color gradient picker for the interface accent. */
final class AppearanceColorPicker implements UiPage {
    private final AppearanceControls controls = new AppearanceControls();
    private final Rect anchor;
    private double hue, saturation, brightness, opacity, speed, lastTime;
    private boolean chroma, gradient;
    private int gradientColor;
    private final TextEdit hex = new TextEdit(), gradientHex = new TextEdit();

    AppearanceColorPicker(PageContext c, Rect anchor) {
        this.anchor = anchor;
        lastTime = c.time();
        int initial = Theme.accent(c.preferences(), c.time());
        apply(initial);
        chroma = c.preferences().flag("accentColor.chroma", false);
        speed = c.preferences().number("accentColor.chromaSpeed", 1);
        gradient = c.preferences().flag("accentColor.gradientEnabled", false);
        gradientColor =
                Theme.color(
                        c.preferences().get("accentColor.gradientArgb", Theme.hex(initial)),
                        initial);
        hex.text(hex(initial));
        gradientHex.text(hex(gradientColor));
    }

    private void apply(int color) {
        double[] hsv = Theme.toHsv(color);
        hue = hsv[0];
        saturation = hsv[1];
        brightness = hsv[2];
        opacity = ((color >>> 24) & 255) / 255.0;
    }

    private int current() {
        return Theme.hsv(hue, saturation, brightness, opacity);
    }

    private static String hex(int c) {
        return ((c >>> 24) & 255) == 255
                ? String.format(java.util.Locale.ROOT, "%06X", c & 0xffffff)
                : String.format(java.util.Locale.ROOT, "%08X", c);
    }

    private void persist(PageContext c) {
        c.preferences().set("accentColor", Theme.hex(current()));
        c.preferences().set("accentColor.chroma", chroma);
        c.preferences().set("accentColor.chromaSpeed", speed);
        c.preferences().set("accentColor.gradientEnabled", gradient);
        c.preferences().set("accentColor.gradientArgb", Theme.hex(gradientColor));
    }

    private void colorChanged(PageContext c) {
        hex.text(hex(current()));
        persist(c);
    }

    public void render(PageContext c) {
        Theme t = Theme.transition(c);
        controls.begin(t);
        Painter p = c.painter();
        if (chroma) hue = (hue + 36 * speed * Math.max(0, c.time() - lastTime)) % 360;
        lastTime = c.time();
        double height = 443 + (gradient ? 46 : 0) + (chroma ? 60 : 0),
                x = Geometry.clamp(anchor.right() - 280, 8, 1103),
                y = Geometry.clamp(anchor.y() + 44, 8, Math.max(8, 692 - height));
        Rect box = new Rect(x, y, 280, height);
        c.hit("appearance.color.outside", c.shell(), c::dismissOverlay, "");
        p.rect(box, t.popupRadius(), t.popup());
        p.outline(box, t.popupRadius(), 1, t.border());
        c.hit("appearance.color.panel", box, controls::blur, "");
        x += 16;
        y += 16;
        double width = 248;
        p.text("颜色选择器", x, y, 14, false, t.text());
        Rect close = new Rect(x + width - 14, y + 3, 14, 14);
        p.icon(t.iconPath("close"), close.x(), close.y(), 14, t.secondary());
        c.hit("appearance.color.close", close, c::dismissOverlay, "关闭");
        y += 35;
        Rect sv = new Rect(x, y, width, 160);
        p.save();
        try {
            p.clip(sv, t.navigationRadius());
            p.gradient(sv, 0xFFFFFFFF, Theme.hsv(hue, 1, 1, 1), 0xFF000000, 0xFF000000);
        } finally {
            p.restore();
        }
        c.hit(
                "appearance.color.sv",
                sv,
                () -> {
                    controls.blur();
                    c.startDrag(
                            (px, py) -> {
                                saturation = Geometry.clamp((px - sv.x()) / sv.w(), 0, 1);
                                brightness = 1 - Geometry.clamp((py - sv.y()) / sv.h(), 0, 1);
                                colorChanged(c);
                            },
                            () -> {});
                },
                "");
        double shownHue = hue;
        Rect selector =
                new Rect(x + saturation * width - 7, y + (1 - brightness) * 160 - 7, 14, 14);
        p.save();
        try {
            p.clip(sv, t.navigationRadius());
            p.rect(selector, t.circleRadius(14), current());
            p.outline(selector, t.circleRadius(14), 2, 0xFFFFFFFF);
        } finally {
            p.restore();
        }
        y += 174;
        Rect hueBar = new Rect(x, y, width, 16);
        p.save();
        try {
            p.clip(hueBar, t.navigationRadius());
            for (int i = 0; i < 6; i++) {
                Rect part = new Rect(x + i * width / 6, y, width / 6 + .1, 16);
                int a = Theme.hsv(i * 60, 1, 1, 1), b = Theme.hsv((i + 1) * 60, 1, 1, 1);
                p.gradient(part, a, b, b, a);
            }
        } finally {
            p.restore();
        }
        marker(c, hueBar, shownHue / 360);
        drag(
                c,
                "hue",
                hueBar,
                v -> {
                    hue = v * 360;
                    colorChanged(c);
                });
        y += 30;
        p.text("不透明度", x, y + 4, 12, false, t.secondary());
        valueField(
                c,
                "alpha",
                new Rect(x + width - 56, y, 56, 26),
                Long.toString(Math.round(opacity * 100)),
                v -> {
                    try {
                        opacity = Geometry.clamp(Double.parseDouble(v) / 100, 0, 1);
                        colorChanged(c);
                    } catch (NumberFormatException ignored) {
                    }
                },
                "%");
        y += 40;
        Rect alphaBar = new Rect(x, y, width, 16);
        AppearanceControls.checker(p, alphaBar, t.navigationRadius());
        p.save();
        try {
            p.clip(alphaBar, t.navigationRadius());
            int a = Theme.alpha(current(), 0), b = Theme.alpha(current(), 1);
            p.gradient(alphaBar, a, b, b, a);
        } finally {
            p.restore();
        }
        marker(c, alphaBar, opacity);
        drag(
                c,
                "alpha",
                alphaBar,
                v -> {
                    opacity = v;
                    colorChanged(c);
                });
        y += 30;
        Rect rainbow = new Rect(x, y + 3.5, 14, 14);
        p.save();
        try {
            p.clip(rainbow, t.circleRadius(14));
            for (int i = 0; i < 6; i++) {
                int a = Theme.hsv(i * 60, 1, 1, 1), b = Theme.hsv((i + 1) * 60, 1, 1, 1);
                p.gradient(new Rect(x + i * 14 / 6, y + 3.5, 14 / 6.0 + .1, 14), a, b, b, a);
            }
        } finally {
            p.restore();
        }
        p.text("彩虹渐变", x + 22, y + 1, 13, false, t.text());
        controls.switchControl(
                c,
                "appearance.color.chroma",
                new Rect(x + width - 42, y, 42, 21),
                chroma,
                true,
                () -> {
                    chroma = !chroma;
                    persist(c);
                },
                true);
        y += 35;
        Rect gradientIcon = new Rect(x, y + 3.5, 14, 14);
        p.save();
        try {
            p.clip(gradientIcon, t.circleRadius(14));
            p.gradient(gradientIcon, current(), gradientColor, gradientColor, current());
        } finally {
            p.restore();
        }
        p.text("渐变", x + 22, y + 1, 13, false, t.text());
        controls.switchControl(
                c,
                "appearance.color.gradient",
                new Rect(x + width - 42, y, 42, 21),
                gradient,
                true,
                () -> {
                    gradient = !gradient;
                    if (gradient && gradientColor == current())
                        gradientColor = Theme.hsv(hue + 40, saturation, brightness, opacity);
                    persist(c);
                },
                true);
        y += 35;
        if (gradient) {
            Rect swatch = new Rect(x, y + 4, 24, 24);
            p.rect(swatch, t.navigationRadius(), gradientColor);
            p.outline(swatch, t.navigationRadius(), 1, t.border());
            hexField(
                    c,
                    "gradient",
                    new Rect(x + 34, y, width - 34, 32),
                    gradientColor,
                    v -> {
                        gradientColor = v;
                        persist(c);
                    });
            y += 46;
        }
        if (chroma) {
            p.text("速度", x, y + 4, 12, false, t.secondary());
            valueField(
                    c,
                    "speed",
                    new Rect(x + width - 56, y, 56, 26),
                    String.format(java.util.Locale.ROOT, "%.1f", speed),
                    v -> {
                        try {
                            speed = Geometry.clamp(Double.parseDouble(v), .1, 4);
                            persist(c);
                        } catch (NumberFormatException ignored) {
                        }
                    },
                    "x");
            y += 30;
            Rect bar = new Rect(x, y, width, 16);
            p.save();
            try {
                p.clip(bar, t.navigationRadius());
                int a = t.component(), b = Theme.accent(c.preferences(), c.time());
                p.gradient(bar, a, b, b, a);
            } finally {
                p.restore();
            }
            marker(c, bar, (speed - .1) / 3.9);
            drag(
                    c,
                    "speed",
                    bar,
                    v -> {
                        speed = Geometry.clamp(v * 4, .1, 4);
                        persist(c);
                    });
            y += 30;
        }
        int shown = current();
        Rect swatch = new Rect(x, y, 32, 32);
        p.rect(swatch, t.navigationRadius(), shown);
        p.outline(swatch, t.navigationRadius(), 1, t.border());
        hexField(
                c,
                "hex",
                new Rect(x + 42, y, width - 42, 32),
                shown,
                v -> {
                    apply(v);
                    persist(c);
                });
    }

    private void marker(PageContext c, Rect r, double fraction) {
        Theme t = controls.theme;
        Rect dot = new Rect(r.x() + Math.max(0, fraction * r.w() - 5), r.y() + 3, 10, 10);
        c.painter().rect(dot, t.circleRadius(10), 0xFFFFFFFF);
        c.painter().outline(dot, t.checkboxRadius(), 1, 0x80FFFFFF);
    }

    private void drag(PageContext c, String id, Rect r, Consumer<Double> changed) {
        c.hit(
                "appearance.color." + id,
                r,
                () -> {
                    controls.blur();
                    c.startDrag(
                            (x, y) -> changed.accept(Geometry.clamp((x - r.x()) / r.w(), 0, 1)),
                            () -> {});
                },
                "");
    }

    private void valueField(
            PageContext c,
            String id,
            Rect r,
            String value,
            Consumer<String> changed,
            String suffix) {
        Theme t = controls.theme;
        Painter p = c.painter();
        p.rect(r, t.navigationRadius(), t.component());
        p.outline(r, t.navigationRadius(), 1, t.border());
        controls.field(
                c,
                "appearance.color." + id + ".input",
                new Rect(r.x(), r.y(), r.w() - 14, r.h()),
                value,
                changed,
                s ->
                        id.equals("alpha")
                                ? s.chars()
                                        .filter(Character::isDigit)
                                        .limit(3)
                                        .collect(
                                                StringBuilder::new,
                                                StringBuilder::appendCodePoint,
                                                StringBuilder::append)
                                        .toString()
                                : AppearanceControls.filterNumberInput(s)
                                        .substring(
                                                0,
                                                Math.min(
                                                        4,
                                                        AppearanceControls.filterNumberInput(s)
                                                                .length())),
                true);
        p.text(suffix, r.right() - 13, r.y() + 4, 12, false, t.secondary());
    }

    private void hexField(PageContext c, String id, Rect r, int value, Consumer<Integer> changed) {
        Theme t = controls.theme;
        Painter p = c.painter();
        p.rect(r, t.navigationRadius(), t.component());
        p.outline(r, t.navigationRadius(), 1, t.border());
        p.text("#", r.x() + 10, r.y() + 6, 13, false, t.secondary());
        controls.field(
                c,
                "appearance.color." + id + ".input",
                new Rect(r.x() + 18, r.y(), r.w() - 18, r.h()),
                hex(value),
                s -> {
                    if (s.length() == 6 || s.length() == 8) {
                        int parsed = Theme.color("#" + s, Integer.MIN_VALUE);
                        if (parsed != Integer.MIN_VALUE || s.equalsIgnoreCase("80000000"))
                            changed.accept(parsed);
                    }
                },
                s -> {
                    StringBuilder clean = new StringBuilder();
                    s.codePoints()
                            .filter(Character::isLetterOrDigit)
                            .limit(8)
                            .forEach(clean::appendCodePoint);
                    return clean.toString();
                },
                true);
    }

    public boolean pointerDown(PageContext c, int button) {
        if (button == 0) controls.blur();
        return false;
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
        return controls.key(c, key, typed, ctrl, shift);
    }

    public void leave(PageContext c) {
        controls.blur();
    }
}
