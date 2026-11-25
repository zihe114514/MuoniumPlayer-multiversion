package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.model.HudElement.Anchor;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.time.LocalDateTime;
import java.util.*;

/** Physical-pixel regressions using the original GUI-axis equations, without a native surface. */
public final class HudUnitsTest {
    private static int assertions;
    private static int matrixCases;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 12, 0);

    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private static void near(double actual, double expected, String label) {
        check(Math.abs(actual - expected) < .00001, label + ": " + actual + " != " + expected);
    }

    private static HudRuntime.Frame frame(
            double physicalW, double physicalH, double gui, double pixelsPerUnit, double time) {
        return new HudRuntime.Frame(
                        physicalW / pixelsPerUnit,
                        physicalH / pixelsPerUnit,
                        gui,
                        time,
                        NOW,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(),
                        Map.of())
                .withPhysicalScale(pixelsPerUnit);
    }

    private static HudNode node(String id, int section, boolean gui, boolean fixed) {
        HudNode n = new HudNode(id, "text");
        Map<String, String> properties = new HashMap<>(n.captureProperties());
        properties.put("section", Integer.toString(section));
        properties.put("relativeX", "13");
        properties.put("relativeY", "17");
        properties.put("growthAnchor", "AUTO");
        properties.put("useGuiScale", Boolean.toString(gui));
        properties.put("customScale", "1.25");
        properties.put("staticWidth", Boolean.toString(fixed));
        properties.put("staticW", "80");
        properties.put("staticH", "28");
        properties.put("alignment", "TOP_LEFT");
        properties.put("mergeBackground", "false");
        properties.put("showShadow", "false");
        properties.put("brackets", "false");
        properties.put("text", "UnitProbe");
        n.restoreProperties(properties);
        return n;
    }

    private static Rect physical(Rect r, double factor) {
        return new Rect(r.x() * factor, r.y() * factor, r.w() * factor, r.h() * factor);
    }

    /** Hud.kt effectiveScale and sectionX/Y, followed by HudManager's outer GUI scale. */
    private static Rect original(
            double pw,
            double ph,
            double gui,
            boolean useGui,
            int section,
            Anchor growth,
            double custom,
            double relativeX,
            double relativeY,
            double localW,
            double localH) {
        double effective = useGui ? custom : custom / gui;
        double width = localW * effective, height = localH * effective;
        double sw = pw / gui, sh = ph / gui;
        int col = section % 3, row = section / 3;
        double fx = growth == Anchor.AUTO ? col / 2d : growth.fx;
        double fy = growth == Anchor.AUTO ? row / 2d : growth.fy;
        double px = col == 0 ? relativeX : col == 1 ? sw / 2 + relativeX : sw - relativeX;
        double py = row == 0 ? relativeY : row == 1 ? sh / 2 + relativeY : sh - relativeY;
        double x =
                Math.max(
                        Math.min(Math.min(12, width) - width, 0),
                        Math.min(Math.max(sw - Math.min(12, width), 0), px - fx * width));
        double y =
                Math.max(
                        Math.min(Math.min(12, height) - height, 0),
                        Math.min(Math.max(sh - Math.min(12, height), 0), py - fy * height));
        return new Rect(x * gui, y * gui, width * gui, height * gui);
    }

    private static void same(Rect actual, Rect expected, String label) {
        near(actual.x(), expected.x(), label + " physical x");
        near(actual.y(), expected.y(), label + " physical y");
        near(actual.w(), expected.w(), label + " physical width");
        near(actual.h(), expected.h(), label + " physical height");
    }

    public static void main(String[] args) {
        compatibility();
        viewportMatrix();
        runtimeMatrix();
        anchorsAndHistory();
        mergedResize();
        startupAndPreview();
        System.out.println(
                "HudUnitsTest passed: "
                        + matrixCases
                        + " physical cases, "
                        + assertions
                        + " assertions");
    }

    private static void compatibility() {
        HudRuntime.Frame old =
                new HudRuntime.Frame(
                        480, 270, 4, 0, NOW, false, false, false, false, false, Set.of(), Map.of());
        near(old.physicalPerUnit(), 4, "old constructor retains GUI-unit semantics");
        HudRuntime.Frame layout = old.withPhysicalScale(2);
        near(layout.width(), 480, "physical override does not reinterpret supplied viewport");
        near(layout.guiScale(), 4, "physical override preserves real GUI scale");
        near(layout.editing().physicalPerUnit(), 2, "editing preserves physical units");
        HudNode n = node("compat", 0, false, true);
        n.frame(4);
        near(n.width(), 25, "one-argument node.frame retains old GUI width");
        n.frame(4, 2);
        near(n.width(), 50, "layout width includes physical-per-unit conversion");
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                layout.withPhysicalScale(invalid);
                throw new AssertionError("Invalid frame accepted");
            } catch (IllegalArgumentException expected) {
                assertions++;
            }
            try {
                n.frame(4, invalid);
                throw new AssertionError("Invalid node scale accepted");
            } catch (IllegalArgumentException expected) {
                assertions++;
            }
        }
    }

    private static void viewportMatrix() {
        for (boolean useGui : new boolean[] {false, true})
            for (int section = 0; section < 9; section++)
                for (Anchor growth : new Anchor[] {Anchor.AUTO, Anchor.CENTER}) {
                    HudNode n = node("viewport", section, useGui, true);
                    n.set("growthAnchor", growth.name());
                    for (int[] window : new int[][] {{1200, 720}, {1920, 1080}, {2880, 1620}})
                        for (double gui : new double[] {1, 2, 3, 4})
                            for (double pp : new double[] {1, 1.5, 2, 3, 4}) {
                                n.frame(gui, pp);
                                same(
                                        physical(n.bounds(window[0] / pp, window[1] / pp), pp),
                                        original(
                                                window[0], window[1], gui, useGui, section, growth,
                                                1.25, 13, 17, 80, 28),
                                        "original viewport");
                                near(n.number("relativeX"), 13, "resize preserves canonical GUI x");
                                near(n.number("relativeY"), 17, "resize preserves canonical GUI y");
                                near(
                                        n.effectiveScale() * pp,
                                        useGui ? 1.25 * gui : 1.25,
                                        "physical custom scale");
                                HudNode copy = (HudNode) n.duplicate("duplicate");
                                same(
                                        copy.bounds(window[0] / pp, window[1] / pp),
                                        n.bounds(window[0] / pp, window[1] / pp),
                                        "duplicate units");
                                matrixCases++;
                            }
                }
        for (double gui : new double[] {1, 2, 3, 4})
            for (double pp : new double[] {1, 1.5, 2, 3, 4}) {
                HudNode n = node("position", 0, false, true);
                n.frame(gui, pp);
                n.absolutePosition(23 * gui / pp, 19 * gui / pp, 1920 / pp, 1080 / pp);
                near(n.number("relativeX"), 23, "position stores GUI x only once");
                near(n.number("relativeY"), 19, "position stores GUI y only once");
                n.set("relativeX", -1000);
                Rect offscreen = physical(n.bounds(1920 / pp, 1080 / pp), pp);
                near(
                        offscreen.right(),
                        Math.min(n.width() * pp, 12 * gui),
                        "original minimum-visible margin uses GUI units");
            }
    }

    private static final class Host extends HudParityTest.Host {
        HudRuntime.Frame current;

        Host(HudRuntime.Frame frame) {
            current = frame;
        }

        public List<HudElement> huds() {
            return List.of();
        }

        public HudRuntime.Frame hudFrame() {
            return current;
        }
    }

    private static void runtimeMatrix() {
        for (boolean useGui : new boolean[] {false, true})
            for (double gui : new double[] {1, 2, 3, 4})
                for (double pp : new double[] {1, 1.5, 2, 3, 4}) {
                    HudNode n = node("dynamic", 8, useGui, false);
                    HudRuntime.Frame f = frame(1920, 1080, gui, pp, 1);
                    Host host = new Host(f);
                    HudDesigner d = new HudDesigner(List.of(n), HudParityTest.prefs(), () -> {});
                    HudRuntime runtime = new HudRuntime(d, host);
                    PhysicalPainter painter = new PhysicalPainter(pp);
                    runtime.render(painter, f);
                    double localW = "UnitProbe".length() * 8 * .5 + 8, localH = 16;
                    same(
                            physical(d.rect(n), pp),
                            original(
                                    1920,
                                    1080,
                                    gui,
                                    useGui,
                                    8,
                                    Anchor.AUTO,
                                    1.25,
                                    13,
                                    17,
                                    localW,
                                    localH),
                            "runtime dynamic box");
                    PhysicalPainter.Text text = painter.texts.get(0);
                    near(
                            text.size(),
                            8 * 1.25 * (useGui ? gui : 1),
                            "real draw physical font size");
                    Rect box = physical(d.rect(n), pp);
                    near(
                            text.x(),
                            box.x() + 4 * 1.25 * (useGui ? gui : 1),
                            "real draw physical pad x");
                    near(
                            text.y(),
                            box.y() + 4 * 1.25 * (useGui ? gui : 1),
                            "real draw physical pad y");
                    same(
                            painter.rects.get(0),
                            box,
                            "content and background share the layout rectangle");
                    check(painter.stack.isEmpty(), "runtime restores the existing painter stack");
                    matrixCases++;
                }
    }

    private static void anchorsAndHistory() {
        HudNode parent = node("parent", 4, true, true), child = node("child", 0, false, true);
        HudDesigner d = new HudDesigner(List.of(parent, child), HudParityTest.prefs(), () -> {});
        List<HudDesigner.State> states = new ArrayList<>(d.snapshot());
        HudDesigner.State s = states.get(1);
        states.set(
                1,
                new HudDesigner.State(
                        s.id(),
                        s.x(),
                        s.y(),
                        s.width(),
                        s.height(),
                        s.scale(),
                        s.hidden(),
                        s.locked(),
                        s.type(),
                        s.properties(),
                        new HudDesigner.Link(
                                "parent", Anchor.BOTTOM_RIGHT, Anchor.TOP_LEFT, 7.5, -2.25),
                        s.order()));
        HudRuntime runtime = new HudRuntime(d, null);
        d.restore(states);
        for (int[] window : new int[][] {{1920, 1080}, {1200, 720}, {2880, 1620}, {1920, 1080}})
            for (double gui : new double[] {1, 2, 3, 4})
                for (double pp : new double[] {1, 1.5, 2, 3, 4}) {
                    HudRuntime.Frame f = frame(window[0], window[1], gui, pp, 1);
                    runtime.tick(f);
                    Rect p = physical(d.rect(parent), pp), c = physical(d.rect(child), pp);
                    near(c.x(), p.right() + 7.5 * gui, "manual anchor physical GUI x offset");
                    near(c.y(), p.bottom() - 2.25 * gui, "manual anchor physical GUI y offset");
                    near(
                            d.anchor(child.id()).offsetX(),
                            7.5,
                            "frame changes do not rewrite anchor units");
                    matrixCases++;
                }
        HudRuntime.Frame f = frame(1920, 1080, 4, 2, 5);
        runtime.tick(f);
        d.select(child.id(), false);
        Rect before = d.rect(child);
        d.edit("unit move", () -> d.position(child, before.x() + 12, before.y() + 6));
        near(d.anchor(child.id()).offsetX(), 13.5, "drag stores layout delta as GUI offset");
        near(d.anchor(child.id()).offsetY(), .75, "drag stores GUI y offset");
        d.undo();
        near(d.anchor(child.id()).offsetX(), 7.5, "undo restores GUI anchor units");
        d.redo();
        near(d.anchor(child.id()).offsetX(), 13.5, "redo restores GUI anchor units");
        runtime.tick(frame(2880, 1620, 3, 1.5, 6));
        near(
                (d.rect(child).x() - d.rect(parent).right()) * 1.5,
                13.5 * 3,
                "edited anchor survives another window and GUI scale");
    }

    private static void mergedResize() {
        HudNode parent = node("merged-parent", 0, true, true),
                child = node("merged-child", 0, true, true);
        parent.set("mergeBackground", true);
        child.set("mergeBackground", true);
        HudRuntime.Frame first = frame(1920, 1080, 4, 2, 0);
        HudDesigner d = new HudDesigner(List.of(parent, child), HudParityTest.prefs(), () -> {});
        d.viewport(first.width(), first.height(), first.guiScale(), first.physicalPerUnit());
        parent.absolutePosition(100, 100, first.width(), first.height());
        child.absolutePosition(100, 100 + parent.height(), first.width(), first.height());
        HudRuntime runtime = new HudRuntime(d, null);
        runtime.render(new PhysicalPainter(2), first);
        check(
                runtime.mergedIds().equals(Set.of(parent.id(), child.id())),
                "original touching backgrounds create a runtime merge link");
        for (double[] values :
                new double[][] {
                    {3, 1.5, 2880, 1620}, {1, 4, 1200, 720}, {2, 1, 1920, 1080}, {4, 2, 1920, 1080}
                }) {
            parent.set("staticH", parent.number("staticH") + 8);
            HudRuntime.Frame f = frame(values[2], values[3], values[0], values[1], 1);
            runtime.render(new PhysicalPainter(values[1]), f);
            near(
                    (d.rect(child).y() - d.rect(parent).bottom()) * values[1],
                    0,
                    "merged child follows parent resize across frame-unit changes");
            check(runtime.mergedIds().contains(child.id()), "merged pair stays connected");
            matrixCases++;
        }
    }

    private static void startupAndPreview() {
        HudRuntime.Frame f = frame(2000, 1200, 4, 2, 0);
        HudNode n = node("saved", 8, true, true);
        HudDesigner source = new HudDesigner(List.of(n), HudParityTest.prefs(), () -> {});
        Host host = new Host(f);
        host.saved = new HudDocument(source.snapshot());
        HudRuntime runtime = HudRuntime.open(host, HudParityTest.prefs());
        HudElement restored = runtime.designer().byId("saved");
        same(
                physical(runtime.designer().rect(restored), 2),
                original(2000, 1200, 4, true, 8, Anchor.AUTO, 1.25, 13, 17, 80, 28),
                "startup document restore uses units before first editor/game frame");
        HudStudio studio = new HudStudio(host, HudParityTest.prefs(), runtime);
        HudParityTest.Context c = new HudParityTest.Context(host);
        studio.render(c);
        HudNode provider =
                (HudNode)
                        studio.providers().stream()
                                .filter(e -> e.id().equals("builtin/text"))
                                .findFirst()
                                .orElseThrow();
        near(
                provider.positionUnitScale(),
                2,
                "library measurement preserves actual host frame units");
        check(HudStudio.editorFrame(c).editor(), "page frame is editing");
        near(
                HudStudio.editorFrame(c).physicalPerUnit(),
                2,
                "page editing preserves physical scale");
        HudChromeContext chrome = new HudChromeContext(c, new HudChromeContext.Focus());
        chrome.measureHudPreview(runtime, provider);
        near(provider.effectiveScale(), 2, "preview measurement does not reset units to identity");
        chrome.hudPreview(runtime, provider, new Rect(10, 10, 200, 100));
        near(provider.positionUnitScale(), 2, "preview drawing retains actual frame units");
        host.current = frame(2880, 1620, 3, 1.5, 1);
        runtime.reload(host.saved);
        same(
                physical(runtime.designer().rect(restored), 1.5),
                original(2880, 1620, 3, true, 8, Anchor.AUTO, 1.25, 13, 17, 80, 28),
                "profile reload uses the current window scale");
    }

    private static final class PhysicalPainter implements Painter {
        record Text(double x, double y, double size) {}

        final List<Text> texts = new ArrayList<>();
        final List<Rect> rects = new ArrayList<>();
        final Deque<double[]> stack = new ArrayDeque<>();
        double sx, sy, tx, ty;

        PhysicalPainter(double physicalPerUnit) {
            sx = sy = physicalPerUnit;
        }

        public void save() {
            stack.push(new double[] {sx, sy, tx, ty});
        }

        public void restore() {
            double[] s = stack.pop();
            sx = s[0];
            sy = s[1];
            tx = s[2];
            ty = s[3];
        }

        public void translate(double x, double y) {
            tx += x * sx;
            ty += y * sy;
        }

        public void scale(double x, double y) {
            sx *= x;
            sy *= y;
        }

        public void clip(Rect r, double radius) {}

        public void rect(Rect r, double radius, int color) {
            rects.add(new Rect(tx + r.x() * sx, ty + r.y() * sy, r.w() * sx, r.h() * sy));
        }

        public void outline(Rect r, double radius, double width, int color) {}

        public void line(double x, double y, double ex, double ey, double width, int color) {}

        public void text(String s, double x, double y, double size, boolean bold, int color) {
            texts.add(new Text(tx + x * sx, ty + y * sy, size * sx));
        }

        public double measure(String s, double size, boolean bold) {
            return s.length() * size * .5;
        }
    }
}
