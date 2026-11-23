package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;

/** Targeted source-parity regressions for menus, default springs, tooltips and ColorOption controls. */
public final class HudUiDetailsTest {
    private static int assertions;

    static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    static void near(double a, double b, String message) {
        check(Math.abs(a - b) < .0001, message + ": " + a + " != " + b);
    }

    public static void main(String[] args) {
        scroll();
        library();
        menus();
        tooltips();
        colors();
        dropdown();
        animation();
        hostOwnership();
        textGeometry();
        System.out.println("HudUiDetailsTest passed: " + assertions + " source-parity assertions");
    }

    private static void click(HudParityTest.Context c, String id) {
        HudParityTest.Hit hit = c.hits.get(id);
        check(hit != null, "hit exists: " + id);
        c.mouseX = hit.rect().x() + hit.rect().w() / 2;
        c.mouseY = hit.rect().y() + hit.rect().h() / 2;
        hit.action().run();
    }

    private static HudParityTest.Paint.Glyph glyph(HudParityTest.Paint painter, String text) {
        return painter.glyphs.stream()
                .filter(g -> g.text().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing drawn text: " + text));
    }

    static void textGeometry() {
        for (double density : new double[] {.75, 1, 1.5, 2}) {
            HudParityTest.Host host = new HudParityTest.Host();
            host.density = density;
            HudParityTest.Context c = new HudParityTest.Context(host);
            HudChromeContext chrome = new HudChromeContext(c, new HudChromeContext.Focus());
            HudWidgets.label(chrome, "Top label", 10, 20, 14, false, 0xFFFFFFFF);
            HudParityTest.Paint.Glyph label = glyph(c.painter, "Top label");
            near(label.y(), 20 * density, "label preserves Painter top-left at density " + density);
            near(label.size(), 14 * density, "label font size scales exactly once");
            Rect row = new Rect(10, 40, 160, 30);
            HudWidgets.text(chrome, "Row label", row, 12, false, 0xFFFFFFFF);
            HudParityTest.Paint.Glyph text = glyph(c.painter, "Row label");
            check(
                    text.y() >= row.y() * density
                            && text.y() + text.size() <= row.bottom() * density,
                    "centered row text remains within its actual rectangle");
            near(
                    text.y() - row.y() * density,
                    row.bottom() * density - text.y() - text.size(),
                    "centered row text has equal top and bottom insets");
            Rect field = new Rect(10, 80, 180, 32);
            chrome.textField("top-left-field", new TextEdit(), field, "Placeholder", value -> {});
            HudParityTest.Paint.Glyph placeholder = glyph(c.painter, "Placeholder");
            check(
                    placeholder.y() >= (field.y() + 6) * density
                            && placeholder.y() + placeholder.size()
                                    <= (field.bottom() - 6) * density,
                    "placeholder fits the actual inset clip at density " + density);
            near(placeholder.size(), 12 * density, "field font size scales exactly once");

            HudStudio studio = new HudStudio(host, HudParityTest.prefs());
            studio.render(c);
            c.time = 2;
            c.next();
            c.painter.glyphs.clear();
            studio.render(c);
            Rect button = c.hits.get("hud:library-toggle").rect();
            HudParityTest.Paint.Glyph toolbar = glyph(c.painter, "HUD 库");
            check(
                    toolbar.y() >= button.y() && toolbar.y() + toolbar.size() <= button.bottom(),
                    "actual HUD toolbar text fits its button at density " + density);
            near(toolbar.size(), 12 * density, "actual toolbar has one density transform");
            HudParityTest.Paint.Glyph menu = glyph(c.painter, "菜单");
            Rect menuButton = c.hits.get("hud:menu").rect();
            check(
                    menu.x() + c.painter.measure(menu.text(), menu.size(), false)
                            <= menuButton.right() - 8 * density,
                    "CJK menu caption fits after the icon without becoming an ellipsis");
            Rect search = c.hits.get("hud:library-search").rect();
            HudParityTest.Paint.Glyph searchText = glyph(c.painter, "Search HUDs...");
            check(
                    searchText.y() >= search.y() + 6 * density
                            && searchText.y() + searchText.size() <= search.bottom() - 6 * density,
                    "actual library placeholder fits its clip at density " + density);
            Rect library = c.hits.get("hud:library-block").rect();
            HudParityTest.Paint.Glyph heading = glyph(c.painter, "HUD Library");
            near(heading.y(), library.y() + 18 * density, "library heading uses its top inset");
            HudParityTest.Paint.Glyph group = glyph(c.painter, "MUONIUMPLAYER");
            double rowTop = library.y() + 88 * density;
            check(
                    group.y() >= rowTop && group.y() + group.size() <= rowTop + 16 * density,
                    "group title stays in the icon row, above first card at density " + density);
            check(
                    group.y() + group.size() <= rowTop + 26 * density,
                    "group title never overlaps the following card");
        }
        HudNode sample = new HudNode("fallback-text-geometry", "text");
        sample.set("showShadow", false);
        sample.set("brackets", false);
        HudParityTest.Paint painter = new HudParityTest.Paint();
        HudRuntime runtime = new HudRuntime(HudParityTest.model(sample), null);
        Rect preview = new Rect(20, 30, 200, 60);
        runtime.textPreview(painter, sample, preview, 0);
        HudParityTest.Paint.Glyph sampleText = glyph(painter, "Hello, OneConfig!");
        near(
                sampleText.y(),
                preview.y() + (preview.h() - 14 * 1.25) / 2,
                "fallback sample passes text-block top without adding another baseline");
    }

    static void scroll() {
        HudScrollAnimation scroll = new HudScrollAnimation();
        scroll.begin(10, 210, 1);
        near(scroll.value(1), 10, "section jump starts at existing offset");
        check(scroll.active(), "jump is animated");
        near(
                scroll.value(1.1),
                10 + HudMotion.sample(0, 200, 0, .1, 1500, 1).value(),
                "jump uses actual default spring");
        check(scroll.value(1.15) > scroll.value(1.12), "jump advances monotonically");
        near(scroll.value(4), 210, "jump ends exactly at target");
        check(!scroll.active(), "completed jump releases pending-scroll state");
        scroll.begin(10, 210, 5, 2);
        near(
                scroll.value(5.1),
                (20 + HudMotion.sample(0, 400, 0, .1, 1500, 1).value()) / 2,
                "density uses physical pixel spring threshold");
        double current = scroll.value(5.12);
        scroll.cancel(current);
        near(scroll.value(6), current, "user wheel cancels instead of snapping to old target");
        scroll.begin(current, 0, 6);
        near(scroll.value(9), 0, "new section request starts after interruption");
    }

    static void library() {
        HudParityTest.Host host = new HudParityTest.Host();
        HudStudio studio = new HudStudio(host, HudParityTest.prefs());
        HudParityTest.Context c = new HudParityTest.Context(host);
        studio.render(c);
        studio.library.requestSection("oneconfig.builtin");
        c.time = .01;
        c.next();
        studio.render(c);
        check(studio.library.scrolling(), "mod icon request activates animated section scroll");
        near(studio.library.scrollOffset(), 0, "request never teleports in same frame");
        c.time = .11;
        c.next();
        studio.render(c);
        check(studio.library.scrollOffset() > 0, "continuous grouped list advances after request");
        c.mouseX = studio.library.bounds().x() + 40;
        c.mouseY = studio.library.bounds().y() + 100;
        check(
                studio.library.wheel(new HudChromeContext(c, new HudChromeContext.Focus()), 1),
                "wheel routed inside visible library");
        double cancelled = studio.library.scrollOffset();
        check(!studio.library.scrolling(), "wheel interrupts section jump");
        c.time = 2;
        c.next();
        studio.render(c);
        near(
                studio.library.scrollOffset(),
                cancelled,
                "cancelled jump never resumes on next frame");
    }

    static void menus() {
        HudParityTest.Host host = new HudParityTest.Host();
        UiPreferences prefs = HudParityTest.prefs();
        HudStudio studio = new HudStudio(host, prefs);
        HudParityTest.Context c = new HudParityTest.Context(host);
        studio.select(List.of("host-a"), false);
        HudContextMenu menu = new HudContextMenu(studio, 10, 10, host.huds().get(0));
        List<String> ids = menu.items(c).stream().map(HudContextMenu.Item::id).toList();
        check(
                ids.equals(
                        List.of(
                                "settings",
                                "visibility",
                                "lock",
                                "align",
                                "anchor",
                                "divider",
                                "copy",
                                "cut",
                                "paste",
                                "duplicate",
                                "divider",
                                "undo",
                                "redo",
                                "divider",
                                "reset")),
                "original full canvas menu item/divider order");
        check(
                HudContextMenu.hint(c, "hudCopyKeybind").equals("Ctrl + C"),
                "shortcut hint uses original current named chord");
        check(
                HudContextMenu.hint(c, "hudDeleteKeybind").equals("Delete"),
                "special key fallback uses native display casing");
        c.preferences.set("hudCopyKeybind", "ALT+Q");
        check(
                HudContextMenu.hint(c, "hudCopyKeybind").equals("Alt + Q"),
                "changed binding immediately updates menu hint");
        c.preferences.set("hudCopyKeybind", "NONE");
        check(HudContextMenu.hint(c, "hudCopyKeybind") == null, "unbound shortcut has no hint");
        c.preferences.set("showKeybindHints", false);
        check(HudContextMenu.hint(c, "hudUndoKeybind") == null, "global hint preference respected");
        Rect measured = menu.measure(c);
        near(
                measured.h(),
                8 + 11 * 28 + 3 * 9 + 28,
                "menu height includes exactly three 9dp dividers and one 28dp alignment row");
        check(measured.w() >= 190 && measured.w() <= 240, "original 190..240 menu width");
        c.next();
        menu.render(c);
        Rect cell = c.hits.get("hud:menu-align/LEFT").rect();
        near(cell.w(), 24, "align item 24dp");
        near(cell.h(), 24, "align item 24dp height");
        check(
                !c.hits.containsKey("hud:context/reset"),
                "intrinsic host without factory default has disabled reset");
        HudContextMenu empty = new HudContextMenu(studio, 0, 0, null);
        check(
                empty.items(c).stream()
                        .map(HudContextMenu.Item::id)
                        .toList()
                        .equals(
                                List.of(
                                        "undo", "redo", "divider", "paste", "all", "divider",
                                        "help")),
                "original empty canvas menu order");
        HudPropertyMenu property =
                new HudPropertyMenu(
                        10,
                        10,
                        false,
                        () -> {
                            throw new AssertionError("disabled reset called");
                        },
                        true,
                        () -> {});
        c.next();
        property.render(c);
        check(
                !c.hits.containsKey("hud:property-menu/reset")
                        && c.hits.containsKey("hud:property-menu/unbind"),
                "reset disabled while key unbind remains enabled");
    }

    static void tooltips() {
        HudParityTest.Context c = new HudParityTest.Context(new HudParityTest.Host());
        HudTooltip tooltip = new HudTooltip();
        Rect anchor = new Rect(200, 150, 100, 20);
        c.mouseX = 220;
        c.mouseY = 155;
        for (double time : new double[] {0, .199}) {
            c.time = time;
            tooltip.begin();
            tooltip.offer(c, "description", anchor, "Description", 260);
            tooltip.render(c);
            check(tooltip.drawn() == null, "tooltip hidden before 200ms");
        }
        c.time = .2;
        tooltip.begin();
        tooltip.offer(c, "description", anchor, "Description", 260);
        tooltip.render(c);
        Rect box = tooltip.drawn();
        check(box != null, "tooltip opens at exactly 200ms");
        near(box.x(), 205, "TopCenter anchor centered using 12dp horizontal padding");
        near(box.y(), 120, "TopCenter tooltip touches control top");
        near(box.h(), 30, "one line plus 8dp vertical padding");
        c.time = .21;
        c.mouseX = 240;
        tooltip.begin();
        tooltip.offer(c, "description", anchor, "Description", 260);
        tooltip.render(c);
        check(tooltip.drawn() != null, "pointer movement inside component does not restart timer");
        tooltip.begin();
        tooltip.render(c);
        check(tooltip.drawn() == null, "leave immediately removes tooltip");
        c.time = .3;
        tooltip.begin();
        tooltip.offer(c, "description", anchor, "Description", 260);
        tooltip.render(c);
        check(tooltip.drawn() == null, "reentry restarts 200ms delay");
        Rect edge = new Rect(0, 0, 12, 12);
        c.mouseX = 1;
        c.mouseY = 1;
        c.time = 1;
        tooltip.begin();
        tooltip.offer(
                c,
                "edge",
                edge,
                "a very long explanation that must wrap within the original 260dp maximum width and"
                        + " stay in the window",
                260);
        tooltip.render(c);
        c.time = 1.2;
        tooltip.begin();
        tooltip.offer(
                c,
                "edge",
                edge,
                "a very long explanation that must wrap within the original 260dp maximum width and"
                        + " stay in the window",
                260);
        tooltip.render(c);
        box = tooltip.drawn();
        check(
                box != null && box.w() <= 260 && box.x() >= 0 && box.y() >= 0,
                "resolved popup clipping keeps tooltip inside window");
    }

    static void colors() {
        HudParityTest.Context c = new HudParityTest.Context(new HudParityTest.Host());
        HudNode n = new HudNode("color", "text");
        HudDesigner designer = HudParityTest.model(n);
        int before = n.color("bgColor");
        HudColorPicker picker =
                new HudColorPicker(
                        designer,
                        new Rect(100, 100, 32, 32),
                        before,
                        v -> n.set("bgColor", v),
                        n,
                        "bg");
        c.overlay(picker);
        c.renderOverlay();
        near(picker.bounds().w(), 280, "original picker width");
        near(c.hits.get("hud:color/sv").rect().h(), 160, "SV exact height");
        near(c.hits.get("hud:color/sv").rect().w(), 248, "SV uses 16dp horizontal padding");
        near(c.hits.get("hud:color/hue").rect().h(), 16, "horizontal hue strip");
        near(c.hits.get("hud:color/hue").rect().w(), 248, "hue strip fills row");
        check(!c.hits.containsKey("hud:color/speed"), "speed is absent with chroma off");
        check(
                c.hits.keySet().stream()
                        .noneMatch(
                                k ->
                                        k.contains("apply")
                                                || k.contains("cancel")
                                                || k.contains("copy")
                                                || k.contains("paste")),
                "no invented apply/cancel/copy/paste buttons");
        near(picker.bounds().x(), 100, "HUD ColorButton popup is TopStart");
        click(c, "hud:color/chroma");
        c.renderOverlay();
        check(
                n.flag("bgChroma") && c.hits.containsKey("hud:color/speed"),
                "chroma toggle persists and reveals speed");
        click(c, "hud:color/speed");
        c.move(10000, c.mouseY);
        c.release();
        near(n.number("bgChromaSpeed"), 4, "speed bar max is4");
        c.renderOverlay();
        click(c, "hud:color/speed");
        c.move(-10000, c.mouseY);
        c.release();
        near(n.number("bgChromaSpeed"), .1, "speed bar min is.1");
        c.renderOverlay();
        click(c, "hud:color/opacity");
        picker.key(c, ConfigView.Key.A, "", true, false, false);
        picker.key(c, ConfigView.Key.NONE, "220", false, false, false);
        check((n.color("bgColor") >>> 24) == 255, "percent input clamps to100");
        c.renderOverlay();
        click(c, "hud:color/hex");
        picker.key(c, ConfigView.Key.A, "", true, false, false);
        picker.key(c, ConfigView.Key.NONE, "00123456", false, false, false);
        check(n.color("bgColor") == 0x00123456, "8digit input is AARRGGBB");
        picker.key(c, ConfigView.Key.A, "", true, false, false);
        picker.key(c, ConfigView.Key.NONE, "#ABCDEF", false, false, false);
        check(n.color("bgColor") == 0xFFABCDEF, "hex filters hash and accepts opaque6digits");
        int valid = n.color("bgColor");
        picker.key(c, ConfigView.Key.A, "", true, false, false);
        picker.key(c, ConfigView.Key.NONE, "ZZZZZZ", false, false, false);
        check(n.color("bgColor") == valid, "partial/invalid hex does not mutate live color");
        picker.key(c, ConfigView.Key.ESCAPE, "", false, false, false);
        check(
                !designer.history.active() && designer.history.undoSize() == 1,
                "Escape commits the one original live-edit transaction");
        check(n.color("bgColor") == valid, "Escape retains live color");
        designer.undo();
        check(
                n.color("bgColor") == before && !n.flag("bgChroma"),
                "HUD undo restores color and chroma together");
        HudColorPicker plain =
                new HudColorPicker(designer, new Rect(100, 100, 32, 32), before, v -> {}, null, "");
        c.overlay(plain);
        c.renderOverlay();
        check(
                !c.hits.containsKey("hud:color/chroma"),
                "plain Int host colors cannot advertise nonpersistent chroma");
        c.dismissOverlay();
        check(
                HudColorPicker.hex(0xFF123456).equals("123456")
                        && HudColorPicker.hex(0x80123456).equals("80123456"),
                "6/8digit formatting matches original alpha rule");
    }

    static void dropdown() {
        Rect below =
                HudWidgets.Choices.dropdownPosition(
                        new Rect(100, 100, 175, 34), 183, 112, 1000, 600);
        near(below.y(), 144, "dropdown opens below with10dp offset when more space below");
        Rect above =
                HudWidgets.Choices.dropdownPosition(
                        new Rect(900, 500, 175, 34), 183, 112, 1000, 600);
        near(above.y(), 378, "dropdown opens above when more space above");
        check(above.right() <= 1000, "dropdown right edge fallback");
        HudParityTest.Context c = new HudParityTest.Context(new HudParityTest.Host());
        String[] value = {"A"};
        int[] closed = {0};
        HudWidgets.Choices popup =
                new HudWidgets.Choices(
                        new Rect(100, 100, 175, 34),
                        List.of("A", "B"),
                        "A",
                        v -> value[0] = v,
                        () -> closed[0]++);
        c.overlay(popup);
        c.renderOverlay();
        Rect first = c.hits.get("hud:choice/0").rect(), second = c.hits.get("hud:choice/1").rect();
        near(first.h(), 32, "dropdown item height32");
        near(second.y() - first.bottom(), 4, "dropdown item gap4");
        click(c, "hud:choice/1");
        check(
                value[0].equals("B") && closed[0] == 1,
                "selection writes immediately and arrow starts closing");
        c.time = .31;
        c.renderOverlay();
        c.time = .62;
        c.renderOverlay();
        check(c.overlay == null && closed[0] == 1, "300ms dropdown exit disposes exactly once");
    }

    static void hostOwnership() {
        HudParityTest.Host host = new HudParityTest.Host();
        host.composition = false;
        HudRuntime runtime = HudRuntime.open(host, HudParityTest.prefs());
        HudParityTest.Paint painter = new HudParityTest.Paint();
        runtime.render(painter, HudParityTest.frame(false, 0, Set.of()));
        check(
                host.livePreviews == 0,
                "without HOST_COMPOSITION game frame leaves intrinsic widgets to their original HUD"
                        + " path");
        runtime.render(painter, HudParityTest.frame(true, 1, Set.of()));
        check(
                host.livePreviews == 2,
                "editor preview calls renderHostHud independently of game composition capability");
    }

    static void animation() {
        HudPanel.AlignmentPulse pulse = new HudPanel.AlignmentPulse();
        near(pulse.value(true, 0), 1, "selection pulse begins at1");
        near(pulse.value(true, .147), 1.15, "medium bouncy spring lands before150ms return tween");
        near(pulse.value(true, .297), 1, "second stage returns to1 in150ms");
        HudMotion motion = new HudMotion();
        motion.seed("visibility", 0, 0);
        motion.animate("visibility", 1, 0, 400, 1, .01);
        near(
                motion.animate("visibility", 1, .1, 400, 1, .01),
                HudMotion.sample(0, 1, 0, .1, 400, 1).value(),
                "AnimatedVisibility fade uses400 stiffness");
        near(
                motion.animate("visibility", 1, 2, 400, 1, .01),
                1,
                "visibility ends exactly after resolved duration");
        check(motion.color("row-color", 0x00000000, 0) == 0, "color spring starts at exact ARGB");
        motion.color("row-color", 0xFF123456, 0);
        check(
                motion.color("row-color", 0xFF123456, 2) == 0xFF123456,
                "Oklab vector spring finishes at exact target ARGB");
    }
}
