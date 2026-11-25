package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;

/** Exercises the actual panel hit geometry and edit history without a game or graphics context. */
public final class HudNumberLayoutTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (double density : new double[] {.75, 1, 1.5, 2}) {
            for (int width : new int[] {320, 480, 960}) {
                for (String query : List.of("Padding", "Static Size", "Text Options", "GUI Scale")) {
                    var host = new HudParityTest.Host(); host.density = density;
                    var c = new HudParityTest.Context(host);
                    c.width = host.frameWidth = width * density;
                    c.height = host.frameHeight = 1200 * density;
                    c.content = new Rect(0, 0, c.width, c.height);
                    var runtime = HudRuntime.open(host, HudParityTest.prefs());
                    HudNode node = HudParityTest.nativeBox("layout", 20, 20, 100, 40).node();
                    runtime.designer().add(node, 20, 20);
                    var studio = new HudStudio(host, HudParityTest.prefs(), runtime);
                    studio.select(List.of(node.id()), true);
                    studio.panel.search.text(query);
                    studio.render(c);
                    Rect local = studio.panel.bounds();
                    Rect panel = new Rect(local.x()*density, local.y()*density,
                            local.w()*density, local.h()*density);
                    int inputs = 0;
                    for (var hit : c.hits.entrySet()) {
                        if (!hit.getKey().startsWith("hud:number/")) continue;
                        Rect r = hit.getValue().rect();
                        check(r.x() >= panel.x() && r.right() <= panel.right() + .001,
                                "number remains within panel: " + query + " " + width + " " + density + " " + hit.getKey());
                        if (hit.getKey().endsWith("/slider") || hit.getKey().endsWith("/+")
                                || hit.getKey().endsWith("/-")) continue;
                        inputs++;
                        Rect slider = c.hits.get(hit.getKey()+"/slider").rect();
                        check(slider.y() >= r.bottom(), "slider and precise input have separate rows");
                        check(r.w() >= 56*density, "precise input retains readable width");
                        for (var glyph : c.painter.glyphs) {
                            if (!List.of("Custom Scale", "Width", "Height", "Font size", "←", "→", "↑", "↓").contains(glyph.text())) continue;
                            Rect label = new Rect(glyph.x(), glyph.y(), c.painter.measure(glyph.text(), glyph.size(), false), glyph.size());
                            check(!label.intersects(r), "label never overlays an exact numeric value");
                        }
                    }
                    check(inputs == (query.equals("Padding") ? 4 : query.equals("Static Size") ? 2 : 1),
                            "all numeric controls remain reachable: " + query + " at " + width + " / " + density + " got " + inputs);
                }
            }
        }
        stepAndUndo();
        System.out.println("HudNumberLayoutTest passed: " + checks + " layout/edit assertions");
    }
    private static void stepAndUndo() {
        var host = new HudParityTest.Host(); var c = new HudParityTest.Context(host);
        var runtime = HudRuntime.open(host, HudParityTest.prefs());
        HudNode node = HudParityTest.nativeBox("step", 20, 20, 100, 40).node();
        runtime.designer().add(node, 20, 20);
        var studio = new HudStudio(host, HudParityTest.prefs(), runtime);
        studio.select(List.of(node.id()), true);
        studio.panel.search.text("GUI Scale"); studio.render(c);
        c.hits.get("hud:number/customScale/+").action().run();
        check(Math.abs(node.scale()-1.01)<.0001, "scale spinner steps by .01");
        studio.designer().undo(); check(Math.abs(node.scale()-1)<.0001, "spinner undo restores exact value");
        c.hits.get("hud:number/customScale/-").action().run();
        check(Math.abs(node.scale()-.99)<.0001, "scale spinner decrements by .01");
        studio.designer().undo();
    }
}
