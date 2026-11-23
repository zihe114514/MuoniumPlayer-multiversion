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

package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.ui.Geometry;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Native studio HUD data. Geometry uses the original nine screen sections and a growth pin. */
public final class HudNode implements HudElement {
    private final String id, type;
    private final Map<String, String> factory;
    private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private double measuredW = 80,
            measuredH = 16,
            viewportW = 1,
            viewportH = 1,
            guiScale = 1,
            physicalPerUnit = 1;
    private double normalizedX = .5, normalizedY = .5;
    private boolean placed;

    public HudNode(String id, String type) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
        if (!java.util.Set.of("text", "date", "time", "icon").contains(type))
            throw new IllegalArgumentException("Unregistered HUD type: " + type);
        defaults().forEach((k, v) -> values.put(k, String.valueOf(v)));
        values.put(
                "title",
                switch (type) {
                    case "date" -> "Date";
                    case "time" -> "Time";
                    case "icon" -> "Icon HUD";
                    default -> "Text HUD";
                });
        if (type.equals("date")) {
            set("prefix", "Date:");
            set("template", "yyyy-MM-dd");
        }
        if (type.equals("time")) {
            set("prefix", "Time:");
            set("template", "HH:mm:ss");
        }
        if (type.equals("icon")) {
            measuredW = 24;
            measuredH = 24;
            set("iconSize", 24);
        }
        factory = Map.copyOf(values);
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("staticWidth", false);
        d.put("staticW", 200);
        d.put("staticH", 48);
        d.put("alignment", "CENTER");
        d.put("growthAnchor", "AUTO");
        d.put("padLeft", 4);
        d.put("padRight", 4);
        d.put("padTop", 4);
        d.put("padBottom", 4);
        d.put("font", "Minecraft");
        d.put("textScale", 1);
        d.put("textBold", false);
        d.put("textItalic", false);
        d.put("textUnderline", false);
        d.put("textWeight", "Regular");
        d.put("textAlign", 1);
        d.put("caseType", 0);
        d.put("useGuiScale", true);
        d.put("customScale", 1);
        d.put("hidden", false);
        d.put("showBackground", true);
        d.put("bgColor", 0x80000000);
        d.put("bgRadius", 4);
        d.put("bgChroma", false);
        d.put("bgChromaSpeed", 1);
        d.put("mergeBackground", true);
        d.put("mergeDiagonally", false);
        d.put("keepBgWhenHidden", false);
        d.put("textColor", 0xFFFFFFFF);
        d.put("textChroma", false);
        d.put("textChromaSpeed", 1);
        d.put("showShadow", false);
        d.put("fullShadow", false);
        d.put("shadowColor", 0x40000000);
        d.put("shadowChroma", false);
        d.put("shadowChromaSpeed", 1);
        d.put("shadowOffsetX", 2);
        d.put("shadowOffsetY", 2);
        d.put("showInF3", true);
        d.put("showInTab", true);
        d.put("showInScreens", true);
        d.put("showInChat", true);
        d.put("toggleKey", -1);
        d.put("showKey", -1);
        d.put("prefix", "");
        d.put("suffix", "");
        d.put("text", "Hello, OneConfig!");
        d.put("brackets", false);
        d.put("template", "HH:mm:ss");
        d.put("icon", "star");
        d.put("iconSize", 24);
        d.put("section", 0);
        d.put("relativeX", 10);
        d.put("relativeY", 10);
        return d;
    }

    public boolean hasResetDefaults() {
        return true;
    }

    public void resetToDefaults(boolean positionOnly, double width, double height) {
        if (!positionOnly) {
            values.clear();
            values.putAll(factory);
            measuredW = 80;
            measuredH = 16;
        }
        absolutePosition(10 * positionUnitScale(), 10 * positionUnitScale(), width, height);
    }

    public boolean hasPropertyDefault(String key) {
        return factory.containsKey(key) || key.equals("locked");
    }

    public void resetProperty(String key, double width, double height) {
        if (key.equals("staticW")) set(key, Math.max(minWidth() / effectiveScale(), measuredW));
        else if (key.equals("staticH"))
            set(key, Math.max(minHeight() / effectiveScale(), measuredH));
        else if (key.equals("growthAnchor")) growthAnchor(Anchor.AUTO);
        else if (factory.containsKey(key)) set(key, factory.get(key));
        else throw new IllegalArgumentException("No default for HUD property " + key);
    }

    public String id() {
        return id;
    }

    public String title() {
        return get("title", type);
    }

    public String type() {
        return type;
    }

    public String groupId() {
        return "oneconfig.builtin";
    }

    public String groupTitle() {
        return "OneConfig";
    }

    public String icon() {
        return type.equals("icon") ? get("icon", "star") : "text";
    }

    public double x() {
        return normalizedX;
    }

    public double y() {
        return normalizedY;
    }

    public double width() {
        return (flag("staticWidth")
                        ? Math.max(minWidth() / effectiveScale(), number("staticW"))
                        : Math.max(minWidth() / effectiveScale(), measuredW))
                * effectiveScale();
    }

    public double height() {
        return (flag("staticWidth")
                        ? Math.max(minHeight() / effectiveScale(), number("staticH"))
                        : Math.max(minHeight() / effectiveScale(), measuredH))
                * effectiveScale();
    }

    public double scale() {
        return number("customScale");
    }

    public double effectiveScale() {
        return scale() * (flag("useGuiScale") ? guiScale : 1) / physicalPerUnit;
    }

    /** Stored relative positions and manual-anchor offsets retain the original GUI-unit schema. */
    public double positionUnitScale() {
        return guiScale / physicalPerUnit;
    }

    /** Compatibility overload for a viewport/painter expressed in Minecraft GUI units. */
    public void frame(double guiScale) {
        frame(guiScale, guiScale);
    }

    public void frame(double guiScale, double physicalPerUnit) {
        if (!Double.isFinite(guiScale)
                || guiScale <= 0
                || !Double.isFinite(physicalPerUnit)
                || physicalPerUnit <= 0
                || !Double.isFinite(guiScale / physicalPerUnit))
            throw new IllegalArgumentException("Invalid HUD frame scale");
        this.guiScale = guiScale;
        this.physicalPerUnit = physicalPerUnit;
    }

    public boolean hidden() {
        return flag("hidden");
    }

    public void hidden(boolean hidden) {
        set("hidden", hidden);
    }

    public void scale(double scale) {
        set("customScale", Geometry.clamp(finite(scale), .25, 4));
    }

    public void position(double x, double y) {
        normalizedX = finite(x);
        normalizedY = finite(y);
        placed = false;
    }

    public void size(double w, double h) {
        set("staticWidth", true);
        set("staticW", Math.max(minWidth(), finite(w)) / effectiveScale());
        set("staticH", Math.max(minHeight(), finite(h)) / effectiveScale());
    }

    public double minWidth() {
        return (number("padLeft") + number("padRight") + 2) * effectiveScale();
    }

    public double minHeight() {
        return (number("padTop") + number("padBottom") + 2) * effectiveScale();
    }

    public boolean multipleInstancesAllowed() {
        return true;
    }

    public boolean deletable() {
        return true;
    }

    public boolean mergeable() {
        return !type.equals("icon");
    }

    public ResizeMode resizeMode() {
        return ResizeMode.CONTENT;
    }

    public Anchor growthAnchor() {
        Anchor a = anchor("growthAnchor");
        if (a != Anchor.AUTO) return a;
        int section = (int) Geometry.clamp(number("section"), 0, 8);
        return Anchor.at(section % 3, section / 3);
    }

    public Anchor anchor(String key) {
        try {
            return Anchor.valueOf(get(key, "CENTER"));
        } catch (IllegalArgumentException e) {
            return Anchor.CENTER;
        }
    }

    public void growthAnchor(Anchor anchor) {
        Rect r = bounds(viewportW, viewportH);
        set("growthAnchor", anchor.name());
        absolutePosition(r.x(), r.y(), viewportW, viewportH);
    }

    public Rect bounds(double w, double h) {
        viewportW = Math.max(1, w);
        viewportH = Math.max(1, h);
        if (!placed)
            absolutePosition(
                    normalizedX * Math.max(0, w - width()),
                    normalizedY * Math.max(0, h - height()),
                    w,
                    h);
        int section = (int) Geometry.clamp(number("section"), 0, 8);
        int col = section % 3, row = section / 3;
        double relativeX = number("relativeX") * positionUnitScale(),
                relativeY = number("relativeY") * positionUnitScale();
        double pinX = col == 0 ? relativeX : col == 1 ? w / 2 + relativeX : w - relativeX;
        double pinY = row == 0 ? relativeY : row == 1 ? h / 2 + relativeY : h - relativeY;
        Anchor a = growthAnchor();
        double x = keep(pinX - a.fx * width(), width(), w),
                y = keep(pinY - a.fy * height(), height(), h);
        normalizedX = x / Math.max(1, w - width());
        normalizedY = y / Math.max(1, h - height());
        return new Rect(x, y, width(), height());
    }

    public void absolutePosition(double x, double y, double w, double h) {
        x = finite(x);
        y = finite(y);
        viewportW = Math.max(1, w);
        viewportH = Math.max(1, h);
        int col = (int) Geometry.clamp(Math.floor((x + width() / 2) / (viewportW / 3)), 0, 2),
                row = (int) Geometry.clamp(Math.floor((y + height() / 2) / (viewportH / 3)), 0, 2);
        set("section", row * 3 + col);
        Anchor a = growthAnchor();
        double px = x + a.fx * width(), py = y + a.fy * height();
        set(
                "relativeX",
                (col == 0 ? px : col == 1 ? px - viewportW / 2 : viewportW - px)
                        / positionUnitScale());
        set(
                "relativeY",
                (row == 0 ? py : row == 1 ? py - viewportH / 2 : viewportH - py)
                        / positionUnitScale());
        normalizedX = x / Math.max(1, w - width());
        normalizedY = y / Math.max(1, h - height());
        placed = true;
    }

    public void measured(double w, double h) {
        measuredW = Math.max(1, finite(w));
        measuredH = Math.max(1, finite(h));
    }

    public String text(LocalDateTime now, Map<String, String> gameData) {
        String t;
        if (type.equals("date") || type.equals("time")) {
            try {
                t = now.format(DateTimeFormatter.ofPattern(get("template", "HH:mm:ss")));
            } catch (IllegalArgumentException e) {
                t = "";
            }
        } else t = get("text", "");
        for (var entry : gameData.entrySet())
            t = t.replace("{" + entry.getKey() + "}", entry.getValue());
        t = get("prefix", "") + t + get("suffix", "");
        if (flag("brackets") && !t.isEmpty()) t = "[" + t + "]";
        return (int) number("caseType") == 1
                ? t.toUpperCase(Locale.ROOT)
                : (int) number("caseType") == 2 ? t.toLowerCase(Locale.ROOT) : t;
    }

    public String get(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(get(key, "false"));
    }

    public double number(String key) {
        try {
            return finite(Double.parseDouble(get(key, "0")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int color(String key) {
        return (int) (long) number(key);
    }

    public void set(String key, Object value) {
        values.put(key, String.valueOf(value));
    }

    public Map<String, String> captureProperties() {
        Map<String, String> out = new LinkedHashMap<>(values);
        out.put("_measuredW", String.valueOf(measuredW));
        out.put("_measuredH", String.valueOf(measuredH));
        return Map.copyOf(out);
    }

    public void restoreProperties(Map<String, String> properties) {
        values.clear();
        defaults().forEach((k, v) -> set(k, v));
        values.putAll(properties);
        measuredW = number("_measuredW") > 0 ? number("_measuredW") : measuredW;
        measuredH = number("_measuredH") > 0 ? number("_measuredH") : measuredH;
        values.remove("_measuredW");
        values.remove("_measuredH");
        placed = true;
    }

    public HudElement duplicate(String newId) {
        HudNode n = new HudNode(newId, type);
        n.restoreProperties(captureProperties());
        n.measured(measuredW, measuredH);
        n.frame(guiScale, physicalPerUnit);
        return n;
    }

    private double keep(double pos, double size, double screen) {
        double visible = Math.min(12 * positionUnitScale(), size);
        return Geometry.clamp(pos, Math.min(visible - size, 0), Math.max(screen - visible, 0));
    }

    private static double finite(double v) {
        if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite HUD geometry");
        return v;
    }
}
