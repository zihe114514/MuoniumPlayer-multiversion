package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.host.HudExtensions;
import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.model.HudElement.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Model/interaction tests only. No game, loader, native canvas or visual acceptance. */
public final class HudParityTest {
    private static int checks;

    public static void main(String[] args) {
        selection();
        resize();
        snapAndAlign();
        clipboard();
        history();
        anchors();
        merge();
        document();
        runtime();
        page();
        library();
        tour();
        densityAndInputs();
        startupAndReload();
        propertyEditing();
        numericSliderEditing();
        System.out.println(
                "HudParityTest passed: "
                        + checks
                        + " behavioral assertions (Java "
                        + System.getProperty("java.version")
                        + ")");
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < .001, message + ": " + actual + " != " + expected);
    }

    static UiPreferences prefs() {
        return new UiPreferences(Map.of(), () -> {});
    }

    static HudDesigner model(HudElement... elements) {
        HudDesigner d = new HudDesigner(List.of(elements), prefs(), () -> {});
        d.viewport(1000, 600);
        return d;
    }

    static NativeBox nativeBox(String id, double x, double y, double w, double h) {
        HudNode n = new HudNode(id, "text");
        n.set("staticWidth", true);
        n.set("staticW", w);
        n.set("staticH", h);
        n.absolutePosition(x, y, 1000, 600);
        return new NativeBox(n);
    }

    record NativeBox(HudNode node) {}

    static void selection() {
        Live a = new Live("a", 100, 100, 100, 50), b = new Live("b", 300, 100, 100, 50);
        HudDesigner d = model(a, b);
        d.selection(List.of("a", "b"));
        d.press(130, 120, false, false);
        check(d.selected().size() == 2, "press preserves multi-selection until release");
        d.updatePointer(132, 121, true, false);
        check(!d.dragging(), "3px slop remains a click");
        d.release();
        check(d.selected().equals(Set.of("a")), "click narrows on release");
        d.selection(List.of("a", "b"));
        Rect ar = d.rect(a), br = d.rect(b);
        d.press(130, 120, false, false);
        d.updatePointer(150, 140, true, false);
        check(d.dragging(), "slop starts drag");
        d.release();
        near(d.rect(a).x() - ar.x(), 20, "group x delta");
        near(d.rect(b).x() - br.x(), 20, "group keeps spacing");
        check(d.history.undoSize() == 1, "whole drag is one transaction");
        d.undo();
        near(d.rect(a).x(), 100, "undo group drag");
        d.press(130, 120, true, false);
        d.release();
        check(!d.selected().contains("a"), "control toggles selection");
        d.press(130, 120, false, true);
        d.release();
        check(d.selected().containsAll(Set.of("a", "b")), "shift adds without toggling");
        d.beginMarquee(0, 0, false);
        d.updateMarquee(450, 170);
        check(d.selected().size() == 2, "marquee overlaps both");
        d.updateMarquee(220, 170);
        check(d.selected().equals(Set.of("a")), "shrinking marquee removes stale intersections");
        d.release();
        d.beginMarquee(200, 0, true);
        d.updateMarquee(450, 170);
        check(d.selected().equals(Set.of("a", "b")), "additive marquee keeps initial set");
        d.release();
        d.select("a", false);
        d.toggleHidden();
        d.selectAll();
        check(d.selected().contains("a"), "hidden HUD remains selectable");
        d.select("b", false);
        d.toggleLocked();
        d.beginMarquee(0, 0, false);
        d.updateMarquee(1000, 600);
        check(!d.selected().contains("b"), "marquee excludes locked HUD");
        d.release();
        b.absolutePosition(100, 100, 1000, 600);
        check(d.hit(120, 120, true) == a, "locked HUD does not steal unlocked hit");
        Live outer = new Live("outer", 80, 80, 200, 150),
                inner = new Live("inner", 100, 100, 40, 30);
        HudDesigner overlap = model(inner, outer);
        check(overlap.hit(110, 110, true) == inner, "enclosed HUD is drawn/hit last");
        check(overlap.pick(110, 110, true, "inner", true) == outer, "click cycles overlap stack");
    }

    static void resize() {
        Live a = new Live("a", 100, 100, 100, 50);
        HudDesigner d = model(a);
        d.select("a", false);
        Rect r = d.rect(a);
        d.begin(r.right(), r.bottom(), HudDesigner.Corner.BOTTOM_RIGHT);
        d.drag(r.right() + 50, r.bottom(), true);
        d.end();
        near(a.scale(), 1.4, "diagonal projection, not max-axis factor");
        near(a.width() / a.height(), 2, "legacy proportional scale");
        d.undo();
        near(a.width(), 100, "undo width");
        near(a.height(), 50, "undo height");
        d.begin(r.x(), r.y(), HudDesigner.Corner.TOP_LEFT);
        d.drag(r.x() - 50, r.y() - 25, true);
        d.end();
        near(d.rect(a).right(), r.right(), "left resize pins opposite x");
        near(d.rect(a).bottom(), r.bottom(), "top resize pins opposite y");
        HudNode node = nativeBox("node", 200, 200, 100, 60).node();
        HudDesigner n = model(node);
        n.select("node", false);
        Rect nr = n.rect(node);
        n.beginHandle(nr.right(), nr.bottom(), HudDesigner.Handle.BOTTOM_RIGHT);
        n.drag(nr.right() + 40, nr.bottom() + 20, true, true);
        n.end();
        near(node.width(), 140, "shift free width");
        near(node.height(), 80, "shift free height");
        n.undo();
        near(node.width(), 100, "free resize undo width");
        for (var h : HudDesigner.Handle.values()) {
            Rect handle = n.handleRect(node, h, 7);
            check(n.handleAt(handle.x() + 3.5, handle.y() + 3.5) == h, "eight-way handle hit " + h);
        }
        a.axes = ResizeAxes.WIDTH;
        a.mode = ResizeMode.SIZE;
        d.select("a", false);
        Rect width = d.rect(a);
        d.beginHandle(width.right(), width.y() + width.h() / 2, HudDesigner.Handle.RIGHT);
        d.drag(width.right() + 40, width.y() + width.h() / 2 + 100, true, true);
        d.end();
        near(a.height(), width.h(), "width-only resize does not change height");
        a.axes = ResizeAxes.NONE;
        check(
                d.handleAt(d.rect(a).right(), d.rect(a).bottom()) == null,
                "nonresizable has no handles");
    }

    static void snapAndAlign() {
        near(HudDesigner.snap(0, 20, List.of(26d), 6)[0], 0, "exact six-pixel distance excluded");
        near(HudDesigner.snap(0, 20, List.of(25d), 6)[0], 5, "end snapping");
        check(Double.isNaN(HudDesigner.snap(0, 20, List.of(50d), 6)[1]), "no false guide");
        Live a = new Live("a", 100, 100, 100, 50), b = new Live("b", 300, 180, 100, 50);
        HudDesigner d = model(a, b);
        d.select("a", false);
        d.align(HudDesigner.Align.CENTER_X);
        near(d.rect(a).x(), 450, "single aligns to viewport");
        d.selection(List.of("a", "b"));
        d.align(HudDesigner.Align.TOP);
        near(d.rect(a).y(), d.rect(b).y(), "multi aligns to selection bounds");
        d.select("b", false);
        d.toggleLocked();
        d.selection(List.of("a", "b"));
        double before = d.rect(a).x();
        d.moveBy(5, 0);
        near(d.rect(a).x(), before, "keyboard nudge rejects mixed locked selection");
        d.viewport(20, 20);
        d.select("a", false);
        d.moveBy(10, 10);
        check(
                Double.isFinite(a.x()) && Double.isFinite(a.y()),
                "oversize dimensions remain finite");
        boolean bad = false;
        try {
            d.position(a, Double.NaN, 0);
        } catch (IllegalArgumentException e) {
            bad = true;
        }
        check(bad, "nonfinite geometry rejected before host write");
    }

    static void clipboard() {
        HudNode a = nativeBox("a", 100, 100, 100, 40).node(),
                b = nativeBox("b", 300, 100, 100, 40).node();
        a.set("text", "original");
        HudDesigner d = model(a, b);
        d.selection(List.of("a", "b"));
        d.copy();
        d.edit("change", () -> a.set("text", "changed"));
        List<String> ids = d.paste(600d, 300d);
        check(ids.size() == 2, "group pasted");
        HudNode copy = (HudNode) d.byId(ids.get(0));
        check(copy.get("text", "").equals("original"), "clipboard owns detached content");
        near(d.rect(d.byId(ids.get(1))).x() - d.rect(copy).x(), 200, "paste group spacing");
        d.undo();
        check(d.byId(ids.get(0)) == null, "undo paste removes created instances");
        d.redo();
        check(d.byId(ids.get(0)) != null, "redo recreates same identity");
        d.select("a", false);
        d.cut();
        check(d.byId("a") == null && d.canPaste(), "cut keeps detached clipboard");
        d.undo();
        check(d.byId("a") == a, "undo deletion revives source");
        d.select("a", false);
        d.deleteSelection();
        List<String> after = d.paste(null, null);
        check(after.size() == 1, "paste survives source deletion");
        Live host = new Live("host", 0, 0, 100, 50);
        HudDesigner single = model(host);
        single.select("host", false);
        single.cut();
        check(
                single.byId("host") != null && !single.canPaste(),
                "intrinsic single HUD cannot be deleted/duplicated");
        AtomicInteger saves = new AtomicInteger();
        HudDesigner drag = new HudDesigner(List.of(), prefs(), saves::incrementAndGet);
        drag.viewport(1000, 600);
        HudNode fresh = new HudNode("new", "text");
        drag.addAndBegin(fresh, 100, 100, 120, 110);
        drag.drag(200, 150, true);
        drag.end();
        check(drag.history.undoSize() == 1, "add from library plus drag is one transaction");
        check(saves.get() == 1, "one save at library drop");
        drag.undo();
        check(drag.byId("new") == null, "undo library drag removes HUD");
    }

    static void history() {
        Live a = new Live("a", 100, 100, 100, 50);
        HudDesigner d = model(a);
        d.poll(0, "profile");
        d.select("a", false);
        for (int i = 0; i < 110; i++) d.moveBy(1, 0);
        check(d.history.undoSize() == 100, "100 step history bound");
        for (int i = 0; i < 100; i++) check(d.undo(), "undo step " + i);
        check(!d.undo(), "oldest ten steps evicted");
        near(d.rect(a).x(), 110, "retained oldest layout");
        check(d.redo(), "redo available");
        d.moveBy(1, 0);
        check(!d.history.canRedo(), "new edit invalidates redo");
        d.beginEdit("cancel");
        a.hidden(true);
        d.cancelEdit();
        check(!a.hidden(), "cancel restores complete state");
        int count = d.history.undoSize();
        d.edit("empty", () -> {});
        check(d.history.undoSize() == count, "empty transaction omitted");
        a.position(.7, .7);
        d.poll(10, "profile");
        int old = d.history.undoSize();
        d.poll(10.3, "profile");
        check(d.history.undoSize() == old + 1, "settled external setting recorded at 250ms polls");
        d.poll(20, "other");
        check(
                !d.history.canUndo() && !d.canPaste(),
                "profile switch clears history/clipboard ownership");
        HudNode n = nativeBox("n", 100, 100, 100, 40).node();
        HudDesigner nodes = model(n);
        nodes.poll(0, "p");
        n.measured(400, 40);
        nodes.poll(1, "p");
        nodes.poll(2, "p");
        check(!nodes.history.canUndo(), "measurement alone never creates edit history");
    }

    static void anchors() {
        HudNode a = nativeBox("a", 100, 100, 100, 50).node(),
                b = nativeBox("b", 300, 100, 100, 50).node(),
                c = nativeBox("c", 500, 100, 100, 50).node();
        HudDesigner d = model(a, b, c);
        d.select("b", false);
        Rect before = d.rect(b);
        d.anchorSelection("a", Anchor.BOTTOM_RIGHT, Anchor.TOP_LEFT);
        near(d.rect(b).x(), before.x(), "anchor keeps position");
        d.select("a", false);
        d.moveBy(50, 20);
        near(d.rect(b).x(), before.x() + 50, "anchor follows parent movement");
        near(d.rect(b).y(), before.y() + 20, "anchor follows parent y");
        d.select("c", false);
        d.anchorSelection("b", Anchor.RIGHT, Anchor.LEFT);
        check(!d.canAnchor(Set.of("a"), "c"), "indirect anchor cycle rejected");
        check(!d.canAnchor(Set.of("a"), "a"), "self anchor rejected");
        d.select("b", false);
        Rect pin = d.rect(b);
        d.clearAnchors();
        d.select("a", false);
        d.moveBy(20, 0);
        near(d.rect(b).x(), pin.x(), "clear anchor keeps absolute position");
        d.undo();
        d.undo();
        check(d.anchor("b") != null, "undo clears restoration restores link");
        Rect start = d.rect(a);
        a.growthAnchor(Anchor.BOTTOM_RIGHT);
        a.set("staticW", 150);
        Rect grown = d.rect(a);
        near(grown.right(), start.right(), "growth anchor pins right");
        near(grown.bottom(), start.bottom(), "growth anchor pins bottom");
    }

    static void merge() {
        HudNode a = nativeBox("a", 100, 100, 100, 40).node(),
                b = nativeBox("b", 100, 140, 100, 40).node();
        HudDesigner d = model(a, b);
        List<HudMerge.Group> groups = d.mergeGroups();
        check(
                groups.size() == 1 && groups.get(0).ids().size() == 2,
                "touching compatible backgrounds merge");
        check(groups.get(0).links().size() == 2, "per-axis spanning links");
        check(
                groups.get(0).shape().loops().get(0).size() == 4,
                "shared edges removed from union outline");
        b.set("bgColor", 0x80000001);
        check(d.mergeGroups().isEmpty(), "different colors never merge");
        b.set("bgColor", 0x80000000);
        b.hidden(true);
        check(d.mergeGroups().isEmpty(), "hidden background drops out");
        b.set("keepBgWhenHidden", true);
        check(!d.mergeGroups().isEmpty(), "hidden background can remain");
        b.hidden(false);
        b.absolutePosition(200, 140, 1000, 600);
        check(d.mergeGroups().isEmpty(), "corner contact requires diagonal opt-in");
        a.set("mergeDiagonally", true);
        b.set("mergeDiagonally", true);
        check(!d.mergeGroups().isEmpty(), "both opt into diagonal merging");
        List<List<HudMerge.Point>> loops =
                HudMerge.outlines(List.of(new Rect(0, 0, 100, 30), new Rect(0, 30, 40, 40)));
        check(loops.size() == 1 && loops.get(0).size() == 6, "L shape keeps concave step");
        double area = 0;
        for (int i = 0; i < loops.get(0).size(); i++) {
            var p = loops.get(0).get(i);
            var q = loops.get(0).get((i + 1) % loops.get(0).size());
            area += p.x() * q.y() - p.y() * q.x();
        }
        near(Math.abs(area) / 2, 4600, "union contour area");
        HudNode m = nativeBox("m", 100, 100, 100, 40).node(),
                n = nativeBox("n", 100, 140, 100, 40).node();
        HudDesigner merged = model(m, n);
        merged.press(120, 115, false, false);
        merged.updatePointer(160, 135, true, false);
        merged.release();
        near(merged.rect(n).y(), 160, "unselected merged press carries whole cluster");
        check(merged.selected().equals(Set.of("m")), "whole cluster drop selects grabbed HUD");
        merged.press(160, 135, false, false);
        merged.updatePointer(220, 135, true, false);
        merged.release();
        near(merged.rect(n).x(), 140, "selected piece detaches without moving neighbour");
    }

    static void densityAndInputs() {
        Host host = new Host();
        host.density = 1.5;
        HudStudio studio = new HudStudio(host, prefs());
        Context c = new Context(host);
        studio.select(List.of("host-a"), true);
        studio.render(c);
        c.next();
        studio.render(c);
        Rect r = c.hits.get("hud:panel-drag").rect;
        near(r.h(), 78, "panel chrome dp scales to layout pixels");
        check(r.right() <= 1000 && r.bottom() <= 600, "scaled panel remains on canvas");
        studio.select(List.of(), false);
        studio.library.search.text("Text HUD");
        c.next();
        studio.render(c);
        String field = "hud:library-search";
        if (c.hits.containsKey(field)) {
            Rect box = c.hits.get(field).rect;
            c.mouseX = box.x() + 12;
            c.mouseY = box.y() + 12;
            c.hits.get(field).action.run();
            studio.key(c, ConfigView.Key.A, "", true, false, false);
            studio.key(c, ConfigView.Key.NONE, "abc", false, false, false);
            check(
                    studio.library.search.text().equals("abc"),
                    "density-aware text input edits active HUD search");
        }
        c.nativeFocused = true;
        check(
                !studio.key(c, ConfigView.Key.DELETE, "", false, false, false),
                "native text focus bypasses HUD shortcuts");
        c.nativeFocused = false;
        HudNode n = nativeBox("color", 100, 100, 100, 40).node();
        HudDesigner d = model(n);
        int original = n.color("bgColor");
        HudColorPicker color =
                new HudColorPicker(
                        d, new Rect(100, 100, 32, 32), original, v -> n.set("bgColor", v), n, "bg");
        c.overlay(color);
        c.renderOverlay();
        Rect sv = c.hits.get("hud:color/sv").rect;
        c.mouseX = sv.right() - 2;
        c.mouseY = sv.y() + 2;
        c.hits.get("hud:color/sv").action.run();
        c.release();
        check(n.color("bgColor") != original, "SV edits live color");
        color.key(c, ConfigView.Key.ESCAPE, "", false, false, false);
        check(
                n.color("bgColor") != original && !d.history.active(),
                "original picker Escape closes and commits live color");
        d.undo();
        check(n.color("bgColor") == original, "undo separately restores closed color edit");
        HudWidgets.Input input =
                new HudWidgets.Input(
                        "number",
                        "12",
                        v -> check(v.equals("42"), "initial input select-all replaced"));
        c.overlay(input);
        input.key(c, ConfigView.Key.NONE, "42", false, false, false);
        input.key(c, ConfigView.Key.ENTER, "", false, false, false);
    }

    static void startupAndReload() {
        Host host = new Host();
        HudNode saved = nativeBox("persisted", 200, 200, 100, 40).node();
        HudDesigner source = model(saved);
        host.saved = new HudDocument(source.snapshot());
        HudRuntime runtime = HudRuntime.open(host, prefs());
        check(
                runtime.designer().byId("persisted") != null,
                "startup restores extended HUD before UI exists");
        Paint p = new Paint();
        runtime.render(p, frame(false, 0, Set.of()));
        check(
                p.text.stream().anyMatch(t -> t.contains("Hello")),
                "restored HUD draws on game frame without opening editor");
        HudStudio page = new HudStudio(host, prefs(), runtime);
        check(page.runtime() == runtime, "page reuses game runtime ownership");
        page.leave(new Context(host));
        check(host.bound == runtime, "closing page leaves game runtime bound");
        host.owner = "different";
        host.saved = HudDocument.empty();
        runtime.tick(frame(false, 1, Set.of()));
        check(
                runtime.designer().byId("persisted") == null,
                "closed-page profile switch removes old profile HUD");
        HudNode parent = nativeBox("parent", 100, 100, 100, 40).node(),
                child = nativeBox("child", 300, 100, 100, 40).node();
        HudDesigner d = model(parent, child);
        d.select("child", false);
        d.anchorSelection("parent", Anchor.TOP_LEFT, Anchor.TOP_LEFT);
        d.selection(List.of("child", "parent"));
        d.moveBy(30, 20);
        near(d.rect(parent).x(), 130, "anchored parent in reverse selection moves once");
        near(d.rect(child).x(), 330, "anchored child group delta does not double");
        d.undo();
        near(d.rect(child).x(), 300, "undo anchored group delta");
        d.select("child", false);
        d.toggleLocked();
        d.copy();
        List<String> ids = d.paste(600d, 300d);
        check(ids.size() == 1 && d.locked(ids.get(0)), "locked clipboard duplicate preserves lock");
        d.select("parent", false);
        d.edit(
                "change defaults",
                () -> {
                    parent.set("text", "changed");
                    parent.hidden(true);
                });
        d.resetSelection(false);
        check(
                parent.get("text", "").equals("Hello, OneConfig!") && !parent.hidden(),
                "reset uses provider factory values");
        near(d.rect(parent).x(), 10, "factory position is ten pixels");
        HudDesigner.State
                a =
                        new HudDesigner.State(
                                "a",
                                0,
                                0,
                                10,
                                10,
                                1,
                                false,
                                false,
                                "text",
                                Map.of(),
                                new HudDesigner.Link("b", Anchor.TOP, Anchor.TOP, 0, 0),
                                0),
                b =
                        new HudDesigner.State(
                                "b",
                                0,
                                0,
                                10,
                                10,
                                1,
                                false,
                                false,
                                "text",
                                Map.of(),
                                new HudDesigner.Link("a", Anchor.TOP, Anchor.TOP, 0, 0),
                                1);
        boolean bad = false;
        try {
            new HudDocument(List.of(a, b));
        } catch (IllegalArgumentException e) {
            bad = true;
        }
        check(bad, "document rejects cyclic anchors before restore");
    }

    static void propertyEditing() {
        Host host = new Host();
        HudRuntime runtime = HudRuntime.open(host, prefs());
        HudNode node = nativeBox("edit", 100, 100, 100, 40).node();
        runtime.designer().add(node, 100, 100);
        HudStudio studio = new HudStudio(host, prefs(), runtime);
        Context c = new Context(host);
        studio.select(List.of(node.id()), true);
        studio.render(c);
        check(
                c.hits.containsKey("hud:number/customScale"),
                "real inline spinner text area rendered");
        Rect field = c.hits.get("hud:number/customScale").rect;
        c.mouseX = field.x() + 8;
        c.mouseY = field.y() + 8;
        c.hits.get("hud:number/customScale").action.run();
        studio.key(c, ConfigView.Key.NONE, "2", false, false, false);
        near(node.scale(), 2, "typing spinner writes live HUD scale");
        studio.key(c, ConfigView.Key.ESCAPE, "", false, false, false);
        near(node.scale(), 1, "Escape cancels spinner transaction");
        check(!studio.designer().history.active(), "spinner cancel closes transaction");
        c.next();
        studio.render(c);
        field = c.hits.get("hud:number/customScale").rect;
        c.mouseX = field.x() + 8;
        c.mouseY = field.y() + 8;
        c.hits.get("hud:number/customScale").action.run();
        studio.key(c, ConfigView.Key.NONE, "1.37", false, false, false);
        studio.key(c, ConfigView.Key.ENTER, "", false, false, false);
        near(node.scale(), 1.37, "precise text input is not rounded to the slider step");
        studio.designer().undo();
        near(node.scale(), 1, "undo restores committed number input");
        c.next();
        studio.render(c);
        field = c.hits.get("hud:number/customScale").rect;
        c.mouseX = field.x() + 8;
        c.mouseY = field.y() + 8;
        studio.pointerDown(c, 1);
        check(c.overlay != null, "right click spinner opens property reset menu");
        c.dismissOverlay();
        check(
                HudPanel.filterNumber("-1a.2.3").equals("-1.23"),
                "numeric filter matches original sign/dot rules");
        host.density = 1.5;
        studio = new HudStudio(host, prefs(), runtime);
        studio.library.search.text("Text HUD");
        studio.select(List.of(), false);
        c.next();
        studio.render(c);
        Rect card = c.hits.get("hud:library-card/builtin/text").rect;
        c.mouseX = card.x() + card.w() / 2;
        c.mouseY = card.y() + card.h() / 2;
        c.hits.get("hud:library-card/builtin/text").action.run();
        c.move(c.mouseX + 7, c.mouseY);
        c.release();
        check(
                runtime.designer().elements().size() == 4,
                "density-scaled library physical slop starts placement");
    }

    static void numericSliderEditing() {
        Host host = new Host();
        host.density = 1.5;
        HudRuntime runtime = HudRuntime.open(host, prefs());
        HudNode node = nativeBox("slider", 100, 100, 100, 40).node();
        runtime.designer().add(node, 100, 100);
        HudStudio studio = new HudStudio(host, prefs(), runtime);
        Context c = new Context(host);
        c.width = 1500;
        c.height = 900;
        c.content = new Rect(0, 0, c.width, c.height);
        host.frameWidth = c.width;
        host.frameHeight = c.height;
        studio.select(List.of(node.id()), true);
        studio.render(c);

        Hit slider = c.hits.get("hud:number/customScale/slider");
        check(slider != null, "numeric property renders a draggable slider");
        Rect track = slider.rect;
        int undoBefore = studio.designer().history.undoSize();
        c.mouseX = track.x() + track.w() * .5;
        c.mouseY = track.y() + track.h() * .5;
        slider.action.run();
        check(studio.designer().history.active(), "slider drag opens one history transaction");
        near(node.scale(), 2.13, "density-scaled initial slider position updates value at .01 precision");

        c.move(track.x() - 100, c.mouseY);
        near(node.scale(), .25, "slider drag clamps below minimum");
        c.move(track.right() + 100, c.mouseY);
        near(node.scale(), 4, "slider drag clamps above maximum");
        c.move(track.x() + 7 * host.density + (track.w() - 14 * host.density) * .803, c.mouseY);
        near(node.scale(), 3.26, "slider drag snaps to the .01 scale step");

        c.release();
        check(!studio.designer().history.active(), "pointer release commits slider transaction");
        check(
                studio.designer().history.undoSize() == undoBefore + 1,
                "whole slider drag adds exactly one history entry");
        check(studio.designer().undo(), "committed slider drag can be undone");
        near(node.scale(), 1, "undo restores the value from before the drag");
    }

    static void document() {
        HudNode a = nativeBox("text", 100, 100, 200, 80).node();
        a.set("text", "甲😀乙");
        HudDesigner d = model(a);
        String encoded = new HudDocument(d.snapshot()).encode();
        HudDocument decoded = HudDocument.decode(encoded);
        check(
                decoded.elements().equals(d.snapshot()),
                "document round trip contains complete detached data");
        boolean bad = false;
        try {
            HudDocument.decode("MHUD1:AAAA");
        } catch (IllegalArgumentException e) {
            bad = true;
        }
        check(bad, "truncated document rejected");
        bad = false;
        try {
            new HudDocument(List.of(d.snapshot().get(0), d.snapshot().get(0)));
        } catch (IllegalArgumentException e) {
            bad = true;
        }
        check(bad, "duplicate identity rejected");
        d.edit("mutate", () -> a.set("text", "changed"));
        check(
                decoded.elements().get(0).properties().get("text").equals("甲😀乙"),
                "persisted document never aliases live map");
    }

    static void runtime() {
        HudNode a = nativeBox("a", 100, 100, 100, 40).node(),
                b = nativeBox("b", 100, 140, 100, 40).node();
        HudDesigner d = model(a, b);
        Paint p = new Paint();
        HudRuntime runtime = new HudRuntime(d, null);
        HudRuntime.Frame f = frame(false, 0, Set.of());
        runtime.render(p, f);
        check(runtime.mergedIds().equals(Set.of("a", "b")), "runtime computes merged background");
        Rect br = d.rect(b);
        a.set("staticH", 70);
        runtime.render(p, frame(false, 1, Set.of()));
        near(d.rect(b).y(), br.y() + 30, "merged child follows natural/fixed parent resize");
        d.select("b", false);
        d.moveBy(200, 0);
        double moved = d.rect(b).x();
        runtime.render(p, frame(false, 2, Set.of()));
        near(d.rect(b).x(), moved, "manual child motion drops stale merge links");
        a.set("toggleKey", 65);
        a.hidden(false);
        runtime.tick(frame(false, 3, Set.of(65)));
        check(a.hidden(), "toggle key rising edge");
        runtime.tick(frame(false, 4, Set.of(65)));
        check(a.hidden(), "held toggle key does not repeat");
        runtime.tick(frame(false, 5, Set.of()));
        runtime.tick(frame(false, 6, Set.of(65)));
        check(!a.hidden(), "second key press toggles again");
        a.set("showInF3", false);
        check(
                !runtime.visible(
                        a,
                        new HudRuntime.Frame(
                                1000,
                                600,
                                1,
                                0,
                                LocalDateTime.of(2026, 11, 23, 12, 0),
                                false,
                                true,
                                false,
                                false,
                                false,
                                Set.of(),
                                Map.of())),
                "F3 visibility gate");
        HudNode time = new HudNode("time", "time");
        check(
                time.text(LocalDateTime.of(2026, 11, 23, 12, 34, 56), Map.of())
                        .equals("Time:12:34:56"),
                "real date/time formatting");
        time.set("template", "[");
        check(
                time.text(LocalDateTime.of(2026, 11, 23, 12, 34, 56), Map.of()).startsWith("Time:"),
                "invalid date template has safe empty value");
        check(
                HudRuntime.chroma(0x80123456, 0, 999) == 0x80123456,
                "zero chroma speed remains static");
        check((HudRuntime.chroma(0x80FF0000, 1, 2.5) >>> 24) == 0x80, "chroma preserves alpha");
        check(p.depth == 0, "runtime painter stack balanced");
    }

    static HudRuntime.Frame frame(boolean editor, double time, Set<Integer> keys) {
        return frame(1000, 600, editor, time, keys);
    }

    static HudRuntime.Frame frame(
            double width, double height, boolean editor, double time, Set<Integer> keys) {
        return new HudRuntime.Frame(
                width,
                height,
                1,
                time,
                LocalDateTime.of(2026, 11, 23, 12, 0),
                editor,
                false,
                false,
                false,
                false,
                keys,
                Map.of());
    }

    static void page() {
        Host host = new Host();
        HudStudio studio = new HudStudio(host, prefs());
        Context c = new Context(host);
        studio.render(c);
        check(host.bound == studio.runtime(), "host owns runtime after page construction");
        check(host.livePreviews == 2, "real host preview once per HUD");
        for (var e : host.huds()) {
            Rect r = studio.designer().rect(e);
            near(host.previews.get(e.id()).x(), r.x(), "preview and hit x share rectangle");
            near(host.previews.get(e.id()).y(), r.y(), "preview and hit y share rectangle");
        }
        studio.select(List.of("host-a"), true);
        c.next();
        studio.render(c);
        c.time = 1;
        c.next();
        studio.render(c);
        check(c.hits.containsKey("hud:panel-drag"), "floating panel has real drag region");
        check(c.hits.containsKey("hud:setting/lock"), "host panel lock state available");
        c.mouseX = 120;
        c.mouseY = 130;
        studio.pointerDown(c, 1);
        check(c.overlay != null, "right click opens isolated context menu");
        c.renderOverlay();
        check(c.hits.containsKey("hud:context/copy"), "context menu contains copy action");
        c.dismissOverlay();
        studio.select(List.of(), false);
        studio.post(HudStudio.Command.SELECT_ALL);
        c.next();
        studio.render(c);
        check(studio.activeSelection().size() == 2, "queued session command processed");
        studio.leave(c);
        check(
                studio.activeSelection().isEmpty() && !studio.post(HudStudio.Command.COPY),
                "leave clears active selection and rejects stale commands");
        check(c.painter.depth == 0, "page layers and saves balanced");
        check(host.saved != null, "document persisted through host bridge");
    }

    static void library() {
        HudNode n = new HudNode("template", "text");
        List<HudLibrary.Entry> entries =
                List.of(
                        new HudLibrary.Entry(n, 100, 100, 1, false, true),
                        new HudLibrary.Entry(n, 100, 65, 1, false, true),
                        new HudLibrary.Entry(n, 70, 30, 1, false, true));
        List<HudLibrary.Row> rows = HudLibrary.pack(entries, 230, 10);
        check(rows.size() == 2, "short cards excluded below 60% row-height tolerance");
        near(rows.get(0).widths().get(0), 110, "width stretching limited by exact row formula");
        check(
                rows.stream().flatMap(r -> r.widths().stream()).allMatch(w -> w <= 135),
                "stretch never exceeds 1.35");
        Host host = new Host();
        HudStudio studio = new HudStudio(host, prefs());
        Context c = new Context(host);
        studio.library.search.text("Text HUD");
        studio.render(c);
        c.time = 1;
        c.next();
        studio.render(c);
        String id = "hud:library-card/builtin/text";
        check(c.hits.containsKey(id), "built-in HUD library card registered");
        Rect card = c.hits.get(id).rect;
        c.mouseX = card.x() + card.w() / 2;
        c.mouseY = card.y() + card.h() / 2;
        c.hits.get(id).action.run();
        check(c.drag != null, "library press waits for drag/click distinction");
        int before = studio.designer().elements().size();
        c.move(c.mouseX + 4, c.mouseY);
        check(studio.designer().elements().size() == before, "8px library slop does not add early");
        c.move(400, 300);
        c.release();
        check(studio.designer().elements().size() == before + 1, "library drag creates live HUD");
        check(
                studio.designer().history.undoSize() == 1,
                "library placement one undo transaction through UI");
        studio.designer().undo();
        check(studio.designer().elements().size() == before, "undo placement removes runtime HUD");
    }

    static void tour() {
        Host host = new Host();
        HudStudio studio = new HudStudio(host, prefs());
        Context c = new Context(host);
        studio.select(List.of("host-a"), true);
        studio.render(c);
        studio.tour().start(0);
        c.next();
        studio.render(c);
        check(
                studio.tour().step() == HudTour.Step.WELCOME && studio.tour().blocksEditor(),
                "tour welcome blocks editing");
        studio.tour().move(1);
        studio.tour().move(1);
        studio.tour().move(1);
        c.next();
        studio.render(c);
        check(
                studio.tour().step() == HudTour.Step.MERGING && !studio.tour().blocksEditor(),
                "merge task allows real gestures");
        check(!studio.tour().move(1), "cannot skip unfinished merge task");
        studio.tour().observe(Set.of("new-merge"), true);
        check(!studio.tour().taskDone(), "merge task waits for drop");
        studio.tour().observe(Set.of("new-merge"), false);
        check(studio.tour().taskDone(), "new real merged member completes task");
        check(studio.tour().move(1), "next enabled after actual merge");
        studio.tour().end();
        check(
                studio.designer().selected().equals(Set.of("host-a")),
                "tour restores original selection");
        check(
                studio.preferences.flag("hud.tour.seen", false),
                "tour completion stored as UI preference");
    }

    static final class Live implements HudElement {
        final String id;
        double x, y, w, h, s = 1;
        boolean hidden;
        ResizeAxes axes = ResizeAxes.BOTH;
        ResizeMode mode = ResizeMode.SCALE;

        Live(String id, double x, double y, double w, double h) {
            this.id = id;
            this.w = w;
            this.h = h;
            absolutePosition(x, y, 1000, 600);
        }

        public String id() {
            return id;
        }

        public String title() {
            return id;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double width() {
            return w * s;
        }

        public double height() {
            return h * s;
        }

        public double scale() {
            return s;
        }

        public boolean hidden() {
            return hidden;
        }

        public void position(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public void size(double w, double h) {
            this.w = w / s;
            this.h = h / s;
        }

        public void scale(double s) {
            this.s = s;
        }

        public void hidden(boolean hidden) {
            this.hidden = hidden;
        }

        public ResizeAxes resizeAxes() {
            return axes;
        }

        public ResizeMode resizeMode() {
            return mode;
        }
    }

    static class Host implements ConfigHost, HudExtensions {
        final List<HudElement> elements =
                List.of(
                        new Live("host-a", 100, 100, 100, 50),
                        new Live("host-b", 320, 180, 120, 60));
        final Map<String, Rect> previews = new HashMap<>();
        HudRuntime bound;
        HudDocument saved;
        int livePreviews;
        String owner = "p";
        double density = 1;
        double frameWidth = 1000, frameHeight = 600;
        boolean composition = true;

        public double uiDensity() {
            return density;
        }

        public List<Category> categories() {
            return List.of();
        }

        public List<HudElement> huds() {
            return elements;
        }

        public void persist() {}

        public void close() {}

        public String clipboard() {
            return "";
        }

        public void clipboard(String s) {}

        public void error(String action, Throwable e) {
            throw new AssertionError(action, e);
        }

        public Set<Capability> hudCapabilities() {
            Set<Capability> capabilities =
                    EnumSet.of(
                            Capability.ADDITIONAL_HUDS,
                            Capability.HOST_SETTINGS,
                            Capability.EXACT_TEXT,
                            Capability.GUI_VISIBILITY);
            if (composition) capabilities.add(Capability.HOST_COMPOSITION);
            return Set.copyOf(capabilities);
        }

        public String hudOwner() {
            return owner;
        }

        public HudDocument loadHudDocument() {
            return saved == null ? HudDocument.empty() : saved;
        }

        public void saveHudDocument(HudDocument d) {
            saved = d;
        }

        public void bindHudRuntime(HudRuntime runtime) {
            bound = runtime;
        }

        public HudRuntime.Frame hudFrame() {
            return frame(frameWidth, frameHeight, true, 0, Set.of());
        }

        public List<HudElement> hudProviders() {
            return List.of();
        }

        public List<ConfigHost.Group> hudSettings(HudElement element) {
            return List.of();
        }

        public HudRuntime.TextRenderer hudTextRenderer() {
            return new HudRuntime.TextRenderer() {
                public HudRuntime.Metrics measure(String text, HudRuntime.TextStyle s) {
                    return new HudRuntime.Metrics(
                            text.length() * s.size() * .5,
                            s.size(),
                            s.size() * .8,
                            s.size() * 1.25);
                }

                public void draw(
                        Painter p, String text, double x, double y, HudRuntime.TextStyle s) {
                    p.text(text, x, y, s.size(), s.bold(), s.color());
                }
            };
        }

        public void renderHostHud(Painter p, HudElement e, Rect r, HudRuntime.Frame f) {
            livePreviews++;
            previews.put(e.id(), r);
        }

        public void renderHudShape(Painter p, HudMerge.Shape shape) {
            HudMerge.draw(p, shape);
        }
    }

    static final class Paint implements Painter {
        int depth;
        final List<String> text = new ArrayList<>();

        record Glyph(String text, double x, double y, double size) {}

        final List<Glyph> glyphs = new ArrayList<>();

        public void save() {
            depth++;
        }

        public void layer(Rect r, double alpha) {
            save();
        }

        public void restore() {
            depth--;
            check(depth >= 0, "no painter underflow");
        }

        public void translate(double x, double y) {}

        public void scale(double x, double y) {}

        public void clip(Rect r, double radius) {}

        public void rect(Rect r, double radius, int color) {}

        public void outline(Rect r, double radius, double width, int color) {}

        public void line(double x, double y, double tx, double ty, double w, int color) {}

        public void text(String s, double x, double y, double size, boolean bold, int color) {
            text.add(s);
            glyphs.add(new Glyph(s, x, y, size));
        }

        public double measure(String text, double size, boolean bold) {
            return text.codePoints()
                    .mapToDouble(cp -> size * (Character.isIdeographic(cp) ? 1 : .5))
                    .sum();
        }
    }

    record Hit(Rect rect, Runnable action) {}

    static final class Context implements PageContext {
        final Host host;
        final Paint painter = new Paint();
        Painter rasterPainter;
        Theme palette = Theme.BUILTIN[0];
        final UiPreferences preferences = prefs();
        final Map<String, Hit> hits = new LinkedHashMap<>();
        double mouseX = -100, mouseY = -100, time;
        double width = 1000, height = 600;
        int nativeCode = 65;
        boolean ctrl, shift, alt, nativeFocused;
        UiPage overlay;
        BiConsumer<Double, Double> drag;
        Runnable finish;
        Rect content = new Rect(0, 0, 1000, 600);

        Context(Host host) {
            this.host = host;
        }

        void next() {
            hits.clear();
        }

        void move(double x, double y) {
            mouseX = x;
            mouseY = y;
            if (drag != null) drag.accept(x, y);
        }

        void release() {
            Runnable f = finish;
            drag = null;
            finish = null;
            if (f != null) f.run();
        }

        void renderOverlay() {
            hits.clear();
            if (overlay != null) overlay.render(this);
        }

        public Painter painter() {
            return rasterPainter == null ? painter : rasterPainter;
        }

        public Theme theme() {
            return palette;
        }

        public int accent() {
            return 0xFF2B4BFF;
        }

        public ConfigHost host() {
            return host;
        }

        public UiPreferences preferences() {
            return preferences;
        }

        public String query() {
            return "";
        }

        public Navigation.Route route() {
            return new Navigation.Route(Navigation.Page.HUD);
        }

        public double mouseX() {
            return mouseX;
        }

        public double mouseY() {
            return mouseY;
        }

        public double width() {
            return width;
        }

        public double height() {
            return height;
        }

        public double time() {
            return time;
        }

        public double delta() {
            return .016;
        }

        public boolean control() {
            return ctrl;
        }

        public boolean shift() {
            return shift;
        }

        public boolean alt() {
            return alt;
        }

        public int nativeKeyCode() {
            return nativeCode;
        }

        public boolean textFocused() {
            return nativeFocused;
        }

        public Rect content() {
            return content;
        }

        public void content(Rect r) {
            content = r;
        }

        public double scroll() {
            return 0;
        }

        public void scroll(double n) {}

        public void scrollRange(double n) {}

        public void hit(String id, Rect r, Runnable action, String tooltip) {
            hits.put(id, new Hit(r, action));
        }

        public void contentHit(String id, Rect r, Runnable a, String t) {
            hit(id, r, a, t);
        }

        public void tooltip(String id, Rect r, String text) {}

        public void startDrag(BiConsumer<Double, Double> update, Runnable finish) {
            release();
            this.drag = update;
            this.finish = finish;
            update.accept(mouseX, mouseY);
        }

        public void navigate(Navigation.Route route) {}

        public void toast(String message) {}

        public void sound(String event) {}

        public void edit(ConfigHost.Option option, Object value) {
            option.set(value);
        }

        public void beginEdit(String name) {}

        public void commitEdit() {}

        public void cancelEdit() {}

        public void textField(
                String id, TextEdit edit, Rect r, String placeholder, Consumer<String> change) {
            hit(id, r, () -> edit.focused = true, "");
        }

        public void textDialog(
                String title,
                String hint,
                String initial,
                String action,
                Consumer<String> submit) {}

        public void confirm(String title, String description, Runnable accept) {}

        public void overlay(UiPage page) {
            if (overlay != null) overlay.leave(this);
            overlay = page;
        }

        public void dismissOverlay() {
            UiPage old = overlay;
            overlay = null;
            if (old != null) old.leave(this);
        }

        public void close() {}

        public double animate(String key, double target, double seconds, Motion.Easing easing) {
            return target;
        }
    }
}
