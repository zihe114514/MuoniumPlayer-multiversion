package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;

/** HudSettingsReset.kt menu row/divider order, current named binding hints and 120ms Oklab hover. */
final class HudContextMenu implements UiPage {
    record Item(
            String id,
            String text,
            String icon,
            String binding,
            boolean enabled,
            int color,
            Runnable action,
            int kind) {}

    private final HudStudio studio;
    private final double x, y;
    private final HudElement target;
    private final boolean preview;
    private final HudMotion motion = new HudMotion();
    private Rect box;
    private double scroll, total;

    HudContextMenu(HudStudio studio, double x, double y, HudElement target) {
        this(studio, x, y, target, false);
    }

    HudContextMenu(HudStudio studio, double x, double y, HudElement target, boolean preview) {
        this.studio = studio;
        this.x = x;
        this.y = y;
        this.target = target;
        this.preview = preview;
    }

    private List<HudElement> targets() {
        if (target == null) return List.of();
        if (studio.designer.selected().contains(target.id()))
            return studio.designer.selected().stream()
                    .map(studio.designer::byId)
                    .filter(Objects::nonNull)
                    .toList();
        return List.of(target);
    }

    private void selected() {
        if (target != null && !studio.designer.selected().contains(target.id()))
            studio.designer.select(target.id(), false);
    }

    List<Item> items(PageContext c) {
        List<Item> out = new ArrayList<>();
        List<HudElement> targets = targets();
        boolean empty = target == null,
                allHidden = !targets.isEmpty() && targets.stream().allMatch(HudElement::hidden),
                allLocked =
                        !targets.isEmpty()
                                && targets.stream().allMatch(e -> studio.designer.locked(e.id()));
        if (!empty) {
            out.add(
                    row(
                            "settings",
                            "Settings",
                            "settings",
                            "hudSettingsKeybind",
                            targets.size() == 1,
                            0,
                            () -> {
                                selected();
                                studio.panelOpen = true;
                                studio.designerTab = false;
                            }));
            out.add(
                    row(
                            "visibility",
                            allHidden ? "Show" : "Hide",
                            allHidden ? "eye-off" : "eye",
                            "hudVisibilityKeybind",
                            true,
                            0,
                            () -> {
                                selected();
                                studio.designer.toggleHidden();
                            }));
            out.add(
                    row(
                            "lock",
                            allLocked ? "Unlock" : "Lock",
                            allLocked ? "lock" : "unlock",
                            "hudLockKeybind",
                            true,
                            allLocked ? c.accent() : 0,
                            () -> {
                                selected();
                                studio.designer.toggleLocked();
                            }));
            out.add(new Item("align", "", "", "", true, 0, () -> {}, 2));
            out.add(
                    row(
                            "anchor",
                            "Anchor",
                            "align",
                            "",
                            studio.designer.elements().stream()
                                    .anyMatch(
                                            e ->
                                                    studio.designer.canAnchor(
                                                            targets.stream()
                                                                    .map(HudElement::id)
                                                                    .toList(),
                                                            e.id())),
                            0,
                            () -> {
                                selected();
                                studio.beginAnchor();
                            }));
            if (targets.stream().anyMatch(e -> studio.designer.anchor(e.id()) != null))
                out.add(
                        row(
                                "unanchor",
                                "Remove anchor",
                                "x-circle",
                                "",
                                true,
                                0,
                                () -> {
                                    selected();
                                    studio.designer.clearAnchors();
                                }));
            out.add(divider());
            out.add(
                    row(
                            "copy",
                            "Copy",
                            "copy",
                            "hudCopyKeybind",
                            true,
                            0,
                            () -> {
                                selected();
                                studio.designer.copy();
                            }));
            out.add(
                    row(
                            "cut",
                            "Cut",
                            "scissors",
                            "hudCutKeybind",
                            true,
                            0,
                            () -> {
                                selected();
                                studio.designer.cut();
                            }));
            out.add(
                    row(
                            "paste",
                            "Paste",
                            "clipboard",
                            "hudPasteKeybind",
                            studio.designer.canPaste(),
                            0,
                            () ->
                                    studio.designer.paste(
                                            x * c.host().uiDensity(), y * c.host().uiDensity())));
            out.add(
                    row(
                            "duplicate",
                            "Duplicate",
                            "copy-plus",
                            "hudDuplicateKeybind",
                            targets.stream().anyMatch(HudElement::multipleInstancesAllowed),
                            0,
                            () -> {
                                selected();
                                studio.designer.duplicateSelection();
                            }));
            out.add(divider());
        }
        out.add(
                row(
                        "undo",
                        "Undo",
                        "undo",
                        "hudUndoKeybind",
                        studio.designer.history.canUndo(),
                        0,
                        studio.designer::undo));
        out.add(
                row(
                        "redo",
                        "Redo",
                        "redo",
                        "hudRedoKeybind",
                        studio.designer.history.canRedo(),
                        0,
                        studio.designer::redo));
        out.add(divider());
        if (empty) {
            out.add(
                    row(
                            "paste",
                            "Paste",
                            "clipboard",
                            "hudPasteKeybind",
                            studio.designer.canPaste(),
                            0,
                            () ->
                                    studio.designer.paste(
                                            x * c.host().uiDensity(), y * c.host().uiDensity())));
            out.add(
                    row(
                            "all",
                            "Select All",
                            "select-all",
                            "hudSelectAllKeybind",
                            true,
                            0,
                            studio.designer::selectAll));
            out.add(divider());
            out.add(
                    row(
                            "help",
                            c.host().translate("oneconfig.hud.tour.menu", "编辑器引导"),
                            "info",
                            "",
                            true,
                            0,
                            () -> studio.tour.start(c.time())));
        } else {
            out.add(
                    row(
                            "reset",
                            "Reset all to default",
                            "refresh",
                            "hudResetKeybind",
                            targets.stream().anyMatch(HudElement::hasResetDefaults),
                            0,
                            () -> {
                                selected();
                                studio.designer.resetSelection(false);
                            }));
            if (targets.stream().anyMatch(HudElement::deletable))
                out.add(
                        row(
                                "delete",
                                "Delete HUD",
                                "trash",
                                "hudDeleteKeybind",
                                true,
                                HudWidgets.RED,
                                () -> {
                                    selected();
                                    studio.designer.deleteSelection();
                                }));
        }
        return List.copyOf(out);
    }

    static String hint(PageContext c, String binding) {
        if (binding.isBlank() || !c.preferences().flag("showKeybindHints", true)) return null;
        return HudBindings.display(c, HudBindings.value(c.preferences(), binding));
    }

    private static Item row(
            String id,
            String text,
            String icon,
            String binding,
            boolean enabled,
            int color,
            Runnable action) {
        return new Item(id, text, icon, binding, enabled, color, action, 0);
    }

    private static Item divider() {
        return new Item("divider", "", "", "", false, 0, () -> {}, 1);
    }

    Rect measure(PageContext c) {
        double width = 190, height = 8;
        for (Item item : items(c)) {
            if (item.kind() == 1) {
                height += 9;
                continue;
            }
            if (item.kind() == 2) {
                height += 28;
                width = Math.max(width, 190);
                continue;
            }
            String hint = hint(c, item.binding());
            width =
                    Math.max(
                            width,
                            8
                                    + 20
                                    + 14
                                    + 8
                                    + c.painter().measure(item.text(), 13, false)
                                    + (hint == null
                                            ? 0
                                            : 8 + c.painter().measure(hint, 10, false)));
            height += 28;
        }
        total = height;
        return new Rect(x, y, Math.min(240, width), height);
    }

    Rect bounds() {
        return box;
    }

    public void render(PageContext c) {
        Rect natural = measure(c);
        box =
                HudWidgets.clamp(
                        new Rect(
                                x,
                                y,
                                natural.w(),
                                Math.min(natural.h(), Math.max(1, c.height() - 16))),
                        c.width(),
                        c.height());
        Painter p = c.painter();
        p.rect(box, c.theme().popupRadius(), c.theme().popup());
        p.outline(box, c.theme().popupRadius(), 1, c.theme().border());
        Rect inner = box.inset(4);
        p.save();
        try {
            p.clip(inner, Math.max(0, c.theme().popupRadius() - 4));
            double cy = box.y() + 4 - scroll;
            for (Item item : items(c)) {
                if (item.kind() == 1) {
                    p.rect(new Rect(inner.x(), cy + 4, inner.w(), 1), 0, c.theme().border());
                    cy += 9;
                    continue;
                }
                if (item.kind() == 2) {
                    align(c, cy, inner);
                    cy += 28;
                    continue;
                }
                Rect r = new Rect(inner.x(), cy, inner.w(), 28);
                drawRow(c, item, r, inner);
                cy += 28;
            }
        } finally {
            p.restore();
        }
        scroll = Geometry.clamp(scroll, 0, Math.max(0, total - box.h()));
    }

    private void drawRow(PageContext c, Item item, Rect r, Rect inner) {
        if (!r.intersects(inner)) return;
        boolean hover = !preview && item.enabled() && r.contains(c.mouseX(), c.mouseY());
        int bg =
                motion.tweenColor(
                        "menu-hover/" + item.id(),
                        Theme.alpha(c.theme().text(), hover ? .1 : 0),
                        c.time(),
                        .12);
        c.painter().rect(r, Math.max(0, c.theme().popupRadius() - 4), bg);
        if (preview && item.id().equals("anchor")) {
            c.painter().rect(r, c.theme().buttonRadius(), Theme.alpha(c.accent(), .18));
            c.painter().outline(r, c.theme().buttonRadius(), 1, c.accent());
        }
        int fg =
                item.color() != 0
                        ? item.color()
                        : item.enabled() ? c.theme().text() : c.theme().secondary();
        c.painter().icon(item.icon(), r.x() + 10, r.y() + 7, 14, fg);
        String hint = hint(c, item.binding());
        double hintWidth = hint == null ? 0 : c.painter().measure(hint, 10, false);
        HudWidgets.text(
                c,
                item.text(),
                new Rect(r.x() + 32, r.y(), r.w() - 42 - (hint == null ? 0 : hintWidth + 8), 28),
                13,
                false,
                fg);
        if (hint != null)
            HudWidgets.text(
                    c,
                    hint,
                    new Rect(r.right() - 10 - hintWidth, r.y(), hintWidth, 28),
                    10,
                    false,
                    item.enabled()
                            ? c.theme().secondary()
                            : Theme.alpha(c.theme().secondary(), .5));
        if (!preview && item.enabled() && r.y() >= inner.y() && r.bottom() <= inner.bottom())
            c.hit(
                    "hud:context/" + item.id(),
                    r,
                    () -> {
                        c.dismissOverlay();
                        item.action().run();
                    },
                    "");
    }

    private void align(PageContext c, double y, Rect inner) {
        String[]
                icons =
                        {
                            "align-left",
                            "align-center",
                            "align-right",
                            "align-top",
                            "align-middle",
                            "align-bottom"
                        },
                labels =
                        {
                            "Align left",
                            "Align centre",
                            "Align right",
                            "Align top",
                            "Align middle",
                            "Align bottom"
                        };
        for (int i = 0; i < 6; i++) {
            HudDesigner.Align align = HudDesigner.Align.values()[i];
            Rect cell = new Rect(inner.x() + 4 + i * 30, y + 2, 24, 24);
            boolean hover = !preview && cell.contains(c.mouseX(), c.mouseY());
            int bg =
                    motion.tweenColor(
                            "menu-align/" + i,
                            Theme.alpha(c.theme().text(), hover ? .1 : 0),
                            c.time(),
                            .12);
            c.painter().rect(cell, 4, bg);
            c.painter().icon(icons[i], cell.x() + 5, cell.y() + 5, 14, c.theme().text());
            if (!preview && cell.y() >= inner.y() && cell.bottom() <= inner.bottom()) {
                c.hit(
                        "hud:menu-align/" + align,
                        cell,
                        () -> {
                            selected();
                            studio.designer.align(align);
                            c.dismissOverlay();
                        },
                        "");
                c.tooltip("hud:menu-align-tip/" + align, cell, labels[i]);
            }
        }
    }

    public boolean pointerDown(PageContext c, int button) {
        if (!preview) c.dismissOverlay();
        return true;
    }

    public boolean wheel(PageContext c, double delta) {
        if (!preview) scroll = Geometry.clamp(scroll - delta * 36, 0, Math.max(0, total - box.h()));
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
