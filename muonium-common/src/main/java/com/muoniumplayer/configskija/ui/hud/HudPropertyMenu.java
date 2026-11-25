package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

/** Options.kt ContextMenuItem: 4 padding, 10x6 item padding, 14 icon, 8 gap, 13 text. */
final class HudPropertyMenu implements UiPage {
    private final double x, y;
    private final boolean enabled, unbind;
    private final Runnable reset, clear;
    private final HudMotion motion = new HudMotion();
    private Rect box;

    HudPropertyMenu(
            double x, double y, boolean enabled, Runnable reset, boolean unbind, Runnable clear) {
        this.x = x;
        this.y = y;
        this.enabled = enabled;
        this.reset = reset;
        this.unbind = unbind;
        this.clear = clear;
    }

    public void render(PageContext c) {
        double width = 8 + 20 + 14 + 8 + c.painter().measure("Reset to default", 13, false);
        box =
                HudWidgets.clamp(
                        new Rect(x, y, width, 8 + 28 * (unbind ? 2 : 1)), c.width(), c.height());
        c.painter().rect(box, c.theme().popupRadius(), c.theme().popup());
        c.painter().outline(box, c.theme().popupRadius(), 1, c.theme().border());
        row(c, "reset", "Reset to default", "refresh", box.y() + 4, enabled, reset);
        if (unbind) row(c, "unbind", "Unbind", "close", box.y() + 32, true, clear);
    }

    private void row(
            PageContext c,
            String id,
            String text,
            String icon,
            double y,
            boolean enabled,
            Runnable action) {
        Rect r = new Rect(box.x() + 4, y, box.w() - 8, 28);
        boolean hover = enabled && r.contains(c.mouseX(), c.mouseY());
        int bg =
                motion.tweenColor(
                        "property-menu/" + id,
                        Theme.alpha(c.theme().text(), hover ? .1 : 0),
                        c.time(),
                        .12);
        c.painter().rect(r, Math.max(0, c.theme().popupRadius() - 4), bg);
        int color = enabled ? c.theme().text() : c.theme().secondary();
        c.painter().icon(icon, r.x() + 10, r.y() + 7, 14, color);
        HudWidgets.text(c, text, new Rect(r.x() + 32, r.y(), r.w() - 42, r.h()), 13, false, color);
        if (enabled)
            c.hit(
                    "hud:property-menu/" + id,
                    r,
                    () -> {
                        action.run();
                        c.dismissOverlay();
                    },
                    "");
    }

    public boolean pointerDown(PageContext c, int button) {
        c.dismissOverlay();
        return true;
    }

    public boolean key(
            PageContext c,
            ConfigView.Key key,
            String typed,
            boolean control,
            boolean shift,
            boolean alt) {
        if (key == ConfigView.Key.ESCAPE) c.dismissOverlay();
        return true;
    }
}
