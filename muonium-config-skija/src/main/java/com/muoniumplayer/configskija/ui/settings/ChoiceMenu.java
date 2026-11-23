package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import static com.muoniumplayer.configskija.ui.settings.SettingsSchema.*;

final class ChoiceMenu implements UiPage {
    private final SettingsPage page; private final ConfigHost.Option option; private final OptionStyle style; private final Rect anchor;
    private Rect bounds; private double scroll; private int highlighted; private boolean closed;
    ChoiceMenu(SettingsPage page, ConfigHost.Option option, OptionStyle style, Rect anchor) { this.page = page; this.option = option; this.style = style; this.anchor = anchor; highlighted = Math.max(0, SettingsControls.selectedIndex(option, style)); }
    private boolean multiple() { return style.visualizer == Visualizer.MULTI_SELECT && style.checkable; }
    private double itemHeight(PageContext ctx) { return multiple() ? 40 : style.visualizer == Visualizer.HUD_DROPDOWN ? 32 : page.schema.lineHeight(ctx, 13, false) + 16; }
    private boolean bulk() { return multiple() && option.choices().size() > 1; }
    private double total(PageContext ctx) { return 8 + option.choices().size() * (itemHeight(ctx) + 4) - (option.choices().isEmpty() ? 0 : 4) + (bulk() ? 49 : 0); }
    @Override public void render(PageContext ctx) {
        if (closed) return; if (!page.available(option)) { close(ctx); return; }
        boolean hud = style.visualizer == Visualizer.HUD_DROPDOWN;
        bounds = SettingsDraw.popup(hud ? new Rect(anchor.x(), anchor.y(), anchor.w() + 8, anchor.h()) : anchor, anchor.w() + (hud ? 8 : 0), hud ? total(ctx) : Math.min(280, total(ctx)), 10, ctx.shell());
        scroll = Geometry.clamp(scroll, 0, Math.max(0, total(ctx) - bounds.h()));
        if (hud) { ctx.painter().rect(bounds, 8, 0xff101b1f); ctx.painter().outline(bounds.inset(.5), 7.5, 1, ctx.theme().border()); } else SettingsDraw.frame(ctx, bounds, 8, ctx.theme().component(), ctx.theme().border()); ctx.painter().save(); ctx.painter().clip(bounds.inset(1), hud ? 7 : ctx.theme().radius(7));
        try { double y = bounds.y() + 4 - scroll;
            if (bulk()) { List<Object> flags = ControlMath.elements(option.value()); boolean all = flags.stream().filter(Boolean.TRUE::equals).count() == option.choices().size(); item(ctx, new Rect(bounds.x() + 4, y, bounds.w() - 8, 40), all ? "Deselect all" : "Select all", all, -1); y += 44; ctx.painter().rect(new Rect(bounds.x() + 4, y, bounds.w() - 8, 1), 0, ctx.theme().border()); y += 5; }
            for (int i = 0; i < option.choices().size(); i++) { boolean selected = multiple() ? i < ControlMath.elements(option.value()).size() && Boolean.TRUE.equals(ControlMath.elements(option.value()).get(i)) : i == SettingsControls.selectedIndex(option, style); item(ctx, new Rect(bounds.x() + 4, y, bounds.w() - 8, itemHeight(ctx)), option.choices().get(i), selected, i); y += itemHeight(ctx) + 4; }
            SettingsDraw.fadingEdges(ctx, page.schema, bounds.inset(1), scroll, Math.max(0, total(ctx) - bounds.h()), ctx.theme().component(), "settings.choice.fade." + option.id());
        } finally { ctx.painter().restore(); }
    }
    private void item(PageContext ctx, Rect r, String text, boolean selected, int index) {
        Rect hit = SettingsPage.intersection(r, bounds.inset(1)); if (hit.h() <= 0) return;
        double hover = page.spring(ctx, "settings.choice.hover." + option.id() + "." + index, SettingsDraw.hover(ctx, hit) || index == highlighted ? 1 : 0);
        int fill = selected && !multiple() ? ctx.accent() : SettingsDraw.opacity(ctx.theme().card(), hover); ctx.painter().rect(r, style.visualizer == Visualizer.HUD_DROPDOWN ? 6 : ctx.theme().radius(4), fill);
        double x = r.x() + 12;
        if (multiple()) { Rect check = new Rect(x, r.y() + (r.h() - 24) / 2, 24, 24); SettingsDraw.frame(ctx, check, 6, selected ? ctx.accent() : ctx.theme().component(), selected ? ctx.accent() : ctx.theme().border()); if (selected) ctx.painter().icon("tick", check.x() - 1, check.y() - 1, 26, ctx.theme().text()); x += 39; }
        String icon = index >= 0 && index < style.optionIcons.size() ? style.optionIcons.get(index) : ""; if (!icon.isEmpty()) { ctx.painter().icon(icon, x, r.y() + (r.h() - 16) / 2, 16, ctx.theme().secondary()); x += 31; }
        double size = style.visualizer == Visualizer.HUD_DROPDOWN ? 12 : 13; ctx.painter().text(SettingsDraw.trim(ctx.painter(), text, r.right() - 12 - x, size, false), x, r.y() + (r.h() - size) / 2, size, false, ctx.theme().text());
        ctx.hit("settings.choice.item." + option.id() + "." + index, hit, () -> choose(ctx, index), "");
    }
    private void choose(PageContext ctx, int index) {
        if (closed || !page.available(option)) { close(ctx); return; }
        if (multiple()) { List<Object> old = ControlMath.elements(option.value()); List<Boolean> flags = new ArrayList<>(); for (int i = 0; i < option.choices().size(); i++) flags.add(i < old.size() && Boolean.TRUE.equals(old.get(i)));
            if (index < 0) { boolean all = flags.stream().allMatch(Boolean.TRUE::equals); Collections.fill(flags, !all); } else flags.set(index, !flags.get(index)); page.write(ctx, option, ControlMath.containerLike(option.value(), flags, false));
        } else if (index >= 0 && index < option.choices().size()) { page.write(ctx, option, SettingsControls.choiceValue(option, style, index)); close(ctx); }
    }
    boolean closed() { return closed; }
    private void close(PageContext ctx) { if (!closed) { closed = true; ctx.dismissOverlay(); } }
    @Override public boolean pointerDown(PageContext ctx, int button) { if (bounds == null || !bounds.contains(ctx.mouseX(), ctx.mouseY())) { close(ctx); return true; } return button != 0; }
    @Override public boolean wheel(PageContext ctx, double delta) { if (bounds != null) scroll = Geometry.clamp(scroll - delta * 36, 0, Math.max(0, total(ctx) - bounds.h())); return true; }
    @Override public boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) {
        if (key == ConfigView.Key.ESCAPE) { close(ctx); return true; }
        if (key == ConfigView.Key.ENTER) { choose(ctx, highlighted); return true; }
        if (key == ConfigView.Key.UP || key == ConfigView.Key.DOWN || key == ConfigView.Key.HOME || key == ConfigView.Key.END) { highlighted = (int)Geometry.clamp(key == ConfigView.Key.HOME ? 0 : key == ConfigView.Key.END ? option.choices().size() - 1 : highlighted + (key == ConfigView.Key.UP ? -1 : 1), 0, Math.max(0, option.choices().size() - 1)); ensureVisible(ctx); return true; }
        if (!control && !alt && typed != null && !typed.isBlank()) { String prefix = typed.toLowerCase(Locale.ROOT); for (int offset = 1; offset <= option.choices().size(); offset++) { int i = (highlighted + offset) % option.choices().size(); if (option.choices().get(i).toLowerCase(Locale.ROOT).startsWith(prefix)) { highlighted = i; ensureVisible(ctx); break; } } return true; }
        return false;
    }
    private void ensureVisible(PageContext ctx) { if (bounds == null) return; double y = (bulk() ? 49 : 0) + highlighted * (itemHeight(ctx) + 4); if (y < scroll) scroll = y; else if (y + itemHeight(ctx) > scroll + bounds.h() - 8) scroll = y + itemHeight(ctx) - bounds.h() + 8; }
    @Override public void leave(PageContext ctx) { closed = true; }
}
