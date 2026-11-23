package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;
import static com.muoniumplayer.configskija.ui.settings.SettingsSchema.*;

/** Focusable 280 dp picker; one undo transaction owns the complete popup editing session. */
final class ColorPicker implements UiPage {
    private final SettingsPage page; private final ConfigHost.Option option; private final OptionStyle style; private final Rect anchor;
    private final Supplier<Object> read; private final Consumer<Object> write;
    private final ColorModel model; private final TextEdit hex = new TextEdit(), opacity = new TextEdit(), speed = new TextEdit(), gradient = new TextEdit();
    private final TextInputController localInput = new TextInputController();
    private double popupOffset = 38;
    private Rect bounds; private boolean active, closed; private Object lastValue;
    ColorPicker(SettingsPage page, ConfigHost.Option option, OptionStyle style, Rect anchor) { this(page, option, style, anchor, option::value, null); ordinary = true; popupOffset = 44; }
    ColorPicker(SettingsPage page, ConfigHost.Option option, OptionStyle style, Rect anchor, Supplier<Object> read, Consumer<Object> write) {
        this.page = page; this.option = option; this.style = style; this.anchor = anchor; this.read = read;
        // The ordinary option path uses ctx.edit below; list colors provide their own parent setter.
        this.write = write;
        lastValue = read.get(); model = new ColorModel(SettingsControls.colorArgb(lastValue), style.alpha); loadFlags(lastValue); refreshFields();
    }
    private void loadFlags(Object value) { if (value instanceof ColorValue c) { model.chroma = c.chroma(); model.speed = c.speed(); model.gradient = c.gradient(); model.gradientArgb = c.gradientArgb(); } }
    private void refreshFields() { if (!hex.focused) hex.text(ColorModel.hex(model.argb())); if (!opacity.focused) opacity.text(Long.toString(Math.round(model.alpha * 100))); if (!speed.focused) speed.text(String.format(Locale.ROOT, "%.1f", model.speed)); if (!gradient.focused) gradient.text(ColorModel.hex(model.gradientArgb)); }
    private boolean chromaCapable() { return lastValue instanceof ColorValue; }
    private boolean gradientCapable() { return style.gradient && lastValue instanceof ColorValue; }
    private double popupHeight() { return 296 + (model.alphaEnabled ? 64 : 0) + (chromaCapable() ? 35 : 0) + (gradientCapable() ? 35 : 0) + (gradientCapable() && model.gradient ? 41 : 0) + (chromaCapable() && model.chroma ? 54 : 0); }
    @Override public void render(PageContext ctx) {
        if (closed) return; if (!page.available(option)) { close(ctx); return; }
        if (!(ctx instanceof TextInputController.TextInput)) localInput.beginFrame(ctx);
        if (!active) { page.beginTransaction(ctx, option); active = true; }
        Object external = read.get(); if (!Objects.equals(external, lastValue)) { lastValue = external; model.apply(SettingsControls.colorArgb(external)); loadFlags(external); }
        if (chromaCapable() && model.chroma) { model.hue = (model.hue + 360 / page.schema.chromaCycleSeconds() * model.speed * Math.max(0, ctx.delta())) % 360; }
        refreshFields(); bounds = SettingsDraw.popup(new Rect(anchor.x(), anchor.y(), anchor.w(), 0), 280, popupHeight(), popupOffset, ctx.shell());
        SettingsDraw.frame(ctx, bounds, 20, ctx.theme().popup(), ctx.theme().border()); double x = bounds.x() + 16, y = bounds.y() + 16, w = 248;
        ctx.painter().text(page.schema.text("oneconfig.ui.color_picker", "Color Picker"), x, y, 14, false, ctx.theme().text()); Rect close = new Rect(bounds.right() - 30, y, 14, 14); ctx.painter().icon("close", close.x(), close.y(), 14, ctx.theme().secondary()); ctx.hit(id("close"), close, () -> close(ctx), ""); y += 28;
        Rect sv = new Rect(x, y, w, 160); ctx.painter().save(); ctx.painter().clip(sv, ctx.theme().radius(8)); int hue = ColorModel.hsv(model.hue, 1, 1, 1); ctx.painter().gradient(sv, 0xffffffff, hue, 0xff000000, 0xff000000);
        Rect selector = new Rect(sv.x() + model.saturation * sv.w() - 7, sv.y() + (1 - model.brightness) * sv.h() - 7, 14, 14); ctx.painter().rect(selector, ctx.theme().radius(100), model.argb()); ctx.painter().outline(selector, ctx.theme().radius(100), 2, 0xffffffff); ctx.painter().restore();
        dragHit(ctx, "sv", sv, (px, py) -> { model.saturation = Geometry.clamp((px - sv.x()) / sv.w(), 0, 1); model.brightness = 1 - Geometry.clamp((py - sv.y()) / sv.h(), 0, 1); changed(ctx); }); y += 174;
        Rect hueBar = new Rect(x, y, w, 16); ctx.painter().save(); ctx.painter().clip(hueBar, ctx.theme().radius(8)); int[] hueColors = {0xffff0000, 0xffffff00, 0xff00ff00, 0xff00ffff, 0xff0000ff, 0xffff00ff, 0xffff0000};
        for (int i = 0; i < 6; i++) ctx.painter().gradient(new Rect(x + w * i / 6, y, w / 6 + .01, 16), hueColors[i], hueColors[i + 1], hueColors[i + 1], hueColors[i]); marker(ctx, hueBar, model.hue / 360); ctx.painter().restore();
        dragHit(ctx, "hue", hueBar, (px, py) -> { model.hue = Geometry.clamp((px - hueBar.x()) / hueBar.w() * 360, 0, 360); changed(ctx); }); y += 30;
        if (model.alphaEnabled) {
            ctx.painter().text(page.schema.text("oneconfig.ui.opacity", "Opacity"), x, y + 4, 12, false, ctx.theme().secondary()); smallField(ctx, "opacity", opacity, new Rect(x + w - 56, y, 56, 20), "%", s -> s.codePoints().filter(Character::isDigit).limit(3).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(), s -> { Double value = ControlMath.parse(s, 0, 100); if (value != null) { model.alpha = value / 100; changed(ctx); } }); y += 34;
            Rect alphaBar = new Rect(x, y, w, 16); ctx.painter().save(); ctx.painter().clip(alphaBar, ctx.theme().radius(8));
            for (int row = 0; row * 6 < 16; row++) for (int col = 0; col * 6 < w; col++) ctx.painter().rect(new Rect(x + col * 6, y + row * 6, 6, 6), 0, (row + col) % 2 == 0 ? 0xffcccccc : 0xff999999);
            int opaque = model.argb() | 0xff000000; ctx.painter().gradient(alphaBar, opaque & 0xffffff, opaque, opaque, opaque & 0xffffff); marker(ctx, alphaBar, model.alpha); ctx.painter().restore();
            dragHit(ctx, "alpha", alphaBar, (px, py) -> { model.alpha = Geometry.clamp((px - alphaBar.x()) / alphaBar.w(), 0, 1); changed(ctx); }); y += 30;
        }
        if (chromaCapable()) { colorDot(ctx, new Rect(x, y + 3.5, 14, 14), ColorModel.hsv(ctx.time() * 36, 1, 1, 1)); ctx.painter().text(page.schema.text("oneconfig.ui.chroma", "Chroma"), x + 22, y + 4, 13, false, ctx.theme().text()); switchAt(ctx, "chroma", new Rect(x + w - 42, y, 42, 21), model.chroma, () -> { model.chroma = !model.chroma; changed(ctx); }); y += 35; }
        if (gradientCapable()) { Rect dot = new Rect(x, y + 3.5, 14, 14); ctx.painter().save(); ctx.painter().clip(dot, ctx.theme().radius(100)); ctx.painter().gradient(dot, model.argb(), model.gradientArgb, model.gradientArgb, model.argb()); ctx.painter().restore(); ctx.painter().text(page.schema.text("oneconfig.ui.gradient", "Gradient"), x + 22, y + 4, 13, false, ctx.theme().text()); switchAt(ctx, "gradient", new Rect(x + w - 42, y, 42, 21), model.gradient, () -> { model.gradient = !model.gradient; if (model.gradient && model.gradientArgb == model.argb()) model.gradientArgb = ColorModel.hsv((model.hue + 40) % 360, model.saturation, model.brightness, model.alpha); changed(ctx); }); y += 35; }
        if (gradientCapable() && model.gradient) { Rect swatch = new Rect(x, y + 1.5, 24, 24); SettingsDraw.frame(ctx, swatch, 8, model.gradientArgb, ctx.theme().border()); hexField(ctx, "gradient.hex", gradient, new Rect(x + 34, y, w - 34, 27), s -> { Integer value = ColorModel.parseHex(s); if (value != null) { model.gradientArgb = value; changed(ctx); } }); y += 41; }
        if (chromaCapable() && model.chroma) {
            ctx.painter().text(page.schema.text("oneconfig.ui.speed", "Speed"), x, y + 4, 12, false, ctx.theme().secondary()); smallField(ctx, "speed", speed, new Rect(x + w - 56, y, 56, 20), "x", s -> { String filtered = ControlMath.filterNumber(s); return filtered.substring(0, Math.min(4, filtered.length())); }, s -> { Double value = ControlMath.parse(s, .1, 4); if (value != null) { model.speed = value; changed(ctx); } }); y += 24;
            Rect speedBar = new Rect(x, y, w, 16); ctx.painter().save(); ctx.painter().clip(speedBar, ctx.theme().radius(8)); ctx.painter().gradient(speedBar, ctx.theme().component(), ctx.accent(), ctx.accent(), ctx.theme().component()); marker(ctx, speedBar, (model.speed - .1) / 3.9); ctx.painter().restore(); dragHit(ctx, "speed", speedBar, (px, py) -> { model.speed = Geometry.clamp((px - speedBar.x()) / speedBar.w() * 4, .1, 4); changed(ctx); }); y += 30;
        }
        Rect swatch = new Rect(x, y, 32, 32); SettingsDraw.frame(ctx, swatch, 8, model.argb(), ctx.theme().border()); hexField(ctx, "hex", hex, new Rect(x + 42, y + 2.5, w - 42, 27), s -> { Integer value = ColorModel.parseHex(s); if (value != null) { model.apply(value); changed(ctx); } });
        if (!(ctx instanceof TextInputController.TextInput)) localInput.endFrame(ctx);
    }
    private String id(String part) { return "settings.picker." + option.id() + "." + part; }
    private void marker(PageContext ctx, Rect r, double value) { Rect dot = new Rect(r.x() + Math.max(0, value * r.w() - 5), r.y() + 3, 10, 10); ctx.painter().rect(dot, ctx.theme().radius(100), 0xffffffff); ctx.painter().outline(dot, ctx.theme().radius(6), 1, 0x80ffffff); }
    private void colorDot(PageContext ctx, Rect r, int color) { ctx.painter().rect(r, ctx.theme().radius(100), color); }
    private void switchAt(PageContext ctx, String part, Rect r, boolean checked, Runnable action) { double on = page.spring(ctx, id(part + ".on"), checked ? 1 : 0); ctx.painter().rect(r, ctx.theme().radius(100), Theme.mix(0xff74777f, ctx.accent(), on)); ctx.painter().rect(new Rect(r.x() + 3 + 21 * on, r.y() + 3, 15, 15), ctx.theme().radius(100), ctx.theme().thumb()); ctx.hit(id(part), r, () -> { if (!closed && page.available(option)) action.run(); }, ""); }
    private void dragHit(PageContext ctx, String part, Rect r, BiConsumer<Double, Double> update) { ctx.hit(id(part), r, () -> { if (closed || !page.available(option)) return; input(ctx).blur(ctx); hex.focused = opacity.focused = speed.focused = gradient.focused = false; BiConsumer<Double, Double> guarded = (x, y) -> { if (!closed && page.available(option)) update.accept(x, y); }; ctx.startDrag(guarded, () -> {}); guarded.accept(ctx.mouseX(), ctx.mouseY()); }, ""); }
    private void smallField(PageContext ctx, String part, TextEdit edit, Rect r, String suffix, UnaryOperator<String> filter, Consumer<String> changed) { SettingsDraw.frame(ctx, r, 8, ctx.theme().component(), ctx.theme().border()); ctx.painter().text(suffix, r.right() - 13, r.y() + 4, 12, false, ctx.theme().secondary()); field(ctx, part, edit, new Rect(r.x() + 8, r.y() + 4, Math.max(1, r.w() - 24), r.h() - 8), 12, true, filter, changed); }
    private void hexField(PageContext ctx, String part, TextEdit edit, Rect r, Consumer<String> changed) { SettingsDraw.frame(ctx, r, 8, ctx.theme().component(), ctx.theme().border()); ctx.painter().text("#", r.x() + 10, r.y() + 7, 13, false, ctx.theme().secondary()); field(ctx, part, edit, new Rect(r.x() + 24, r.y() + 7, r.w() - 34, r.h() - 14), 13, false, ColorModel::filterHex, changed); }
    private TextInputController input(PageContext ctx) { return ctx instanceof TextInputController.TextInput input ? input.textInputController() : localInput; }
    private void field(PageContext ctx, String part, TextEdit edit, Rect r, double size, boolean rightAlign, UnaryOperator<String> filter, Consumer<String> changed) {
        TextInputController.Style style = new TextInputController.Style(size); style.lineHeight = page.schema.lineHeight(ctx, size, false); style.enabled = !closed && page.available(option); style.filter = filter; style.alignment = rightAlign ? TextInputController.Alignment.END : TextInputController.Alignment.START; style.clip = bounds;
        Consumer<String> applied = text -> { if (!closed && page.available(option)) changed.accept(text); };
        if (ctx instanceof TextInputController.TextInput input) input.bareTextField(id(part), edit, r, style, applied, null);
        else localInput.field(ctx, id(part), edit, r, style, applied, null);
    }

    private void changed(PageContext ctx) {
        if (closed || !page.available(option)) return;
        Object current = read.get(); Object value = current instanceof ColorValue ? model.value() : current instanceof java.awt.Color ? new java.awt.Color(model.argb(), true) : model.argb();
        if (ordinary) page.write(ctx, option, value); else write.accept(value);
        lastValue = read.get(); refreshFields();
    }
    private boolean ordinary;
    private void close(PageContext ctx) { if (closed) return; input(ctx).blur(ctx); closed = true; if (active) { active = false; page.commitTransaction(ctx); } hex.focused = opacity.focused = speed.focused = gradient.focused = false; ctx.dismissOverlay(); }
    @Override public boolean pointerDown(PageContext ctx, int button) { if (input(ctx).pointerDown(ctx, button)) return true; if (bounds == null || !bounds.contains(ctx.mouseX(), ctx.mouseY())) { close(ctx); return true; } return button != 0; }
    @Override public boolean wheel(PageContext ctx, double delta) { input(ctx).wheel(ctx, delta); return true; }
    @Override public boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { if (input(ctx).key(ctx, key, typed, control, shift, alt)) return true; if (key == ConfigView.Key.ESCAPE) { close(ctx); return true; } return false; }
    public boolean pointerMove(PageContext ctx) { return input(ctx).pointerMove(ctx); }
    @Override public void pointerUp(PageContext ctx, int button) { input(ctx).pointerUp(ctx, button); }
    @Override public void leave(PageContext ctx) { input(ctx).blur(ctx); if (active) { active = false; page.commitTransaction(ctx); } closed = true; hex.focused = opacity.focused = speed.focused = gradient.focused = false; }
}
