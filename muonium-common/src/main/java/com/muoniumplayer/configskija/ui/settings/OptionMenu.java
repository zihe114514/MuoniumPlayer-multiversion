package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;

final class OptionMenu implements UiPage {
    private final SettingsPage page; private final ConfigHost.Option option; private final Rect anchor; private final boolean pointer;
    private Rect bounds; private boolean closed; private int focus;
    private final Map<Integer, Runnable> keyboardActions = new HashMap<>();
    OptionMenu(SettingsPage page, ConfigHost.Option option, Rect anchor, boolean pointer) { this.page = page; this.option = option; this.anchor = anchor; this.pointer = pointer; }
    @Override public void render(PageContext ctx) {
        keyboardActions.clear();
        if (closed) return; if (!page.available(option)) { close(ctx); return; }
        boolean binding = option.value() instanceof SettingsSchema.Binding; double itemHeight = page.schema.lineHeight(ctx, 13, false) + 12, height = 8 + itemHeight * (binding ? 3 : 2);
        double width = Math.max(ctx.painter().measure("Reset to default", 13, false), ctx.painter().measure("Copy value", 13, false)) + 50;
        bounds = pointer ? new Rect(Geometry.clamp(anchor.x(), 8, ctx.shell().right() - width - 8), Geometry.clamp(anchor.y(), 8, ctx.shell().bottom() - height - 8), width, height) : SettingsDraw.popup(anchor, width, height, 0, ctx.shell());
        SettingsDraw.frame(ctx, bounds, 20, ctx.theme().popup(), ctx.theme().border());
        item(ctx, 0, "refresh", "Reset to default", option.reset() != null, itemHeight, () -> {
            if (!page.available(option) || option.reset() == null) return; page.beginTransaction(ctx, option); try { option.reset().run(); ctx.host().persist(); page.commitTransaction(ctx); } catch (RuntimeException e) { page.cancelTransaction(ctx); throw e; }
        });
        item(ctx, 1, "copy", "Copy value", true, itemHeight, () -> ctx.host().clipboard(valueText(option.value())));
        if (binding) item(ctx, 2, "close", "Unbind", true, itemHeight, () -> page.write(ctx, option, SettingsSchema.Binding.none()));
    }
    private void item(PageContext ctx, int index, String icon, String text, boolean enabled, double height, Runnable action) {
        Rect r = new Rect(bounds.x() + 4, bounds.y() + 4 + index * height, bounds.w() - 8, height); double hover = ctx.animate("settings.menu." + option.id() + index, enabled && (SettingsDraw.hover(ctx, r) || focus == index) ? .1 : 0, .12, Motion.Easing.FAST_OUT_SLOW_IN);
        ctx.painter().rect(r, ctx.theme().radius(16), Theme.alpha(ctx.theme().text(), hover)); int color = enabled ? ctx.theme().text() : ctx.theme().secondary(); ctx.painter().icon(icon, r.x() + 10, r.y() + (r.h() - 14) / 2, 14, color); ctx.painter().text(text, r.x() + 32, r.y() + 6, 13, false, color);
        if (enabled) keyboardActions.put(index, () -> { if (!closed && page.available(option)) action.run(); close(ctx); });
        if (enabled) ctx.hit("settings.menu." + option.id() + "." + index, r, () -> { if (!closed && page.available(option)) action.run(); close(ctx); }, "");
    }
    static String valueText(Object value) { if (value instanceof SettingsSchema.ColorValue c) return "#" + ColorModel.hex(c.argb()); if (value instanceof java.awt.Color c) return "#" + ColorModel.hex(c.getRGB()); if (value != null && value.getClass().isArray()) return ControlMath.elements(value).toString(); return Objects.toString(value, ""); }
    private void close(PageContext ctx) { if (!closed) { closed = true; ctx.dismissOverlay(); } }
    @Override public boolean pointerDown(PageContext ctx, int button) { if (bounds == null || !bounds.contains(ctx.mouseX(), ctx.mouseY())) { close(ctx); return true; } return button != 0; }
    @Override public boolean wheel(PageContext ctx, double delta) { return true; }
    @Override public boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { if (key == ConfigView.Key.ESCAPE) { close(ctx); return true; } if (key == ConfigView.Key.ENTER) { Runnable selected = keyboardActions.get(focus); if (selected != null) selected.run(); return true; } if (key == ConfigView.Key.UP || key == ConfigView.Key.DOWN) { focus = (int)Geometry.clamp(focus + (key == ConfigView.Key.UP ? -1 : 1), 0, option.value() instanceof SettingsSchema.Binding ? 2 : 1); return true; } return false; }
    @Override public void leave(PageContext ctx) { closed = true; }
}
