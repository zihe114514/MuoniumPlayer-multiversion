package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;

/** Nine-step tour. Merging advances only after an actual new merged member and a released drag. */
public final class HudTour {
    public enum Step {
        WELCOME,
        ADDING,
        DRAGGING,
        MERGING,
        ANCHORING,
        SETTINGS,
        DESIGNER,
        FONTS,
        DONE
    }

    private static final String[] TITLES = {
        "欢迎使用 HUD 编辑器", "添加 HUD", "移动与缩放", "合并 HUD", "锚定 HUD", "设置", "设计器", "字体与文字", "设置完成"
    };
    private static final String[] BODIES = {
        "这里用来摆放与调整 HUD。下面快速过一遍基本操作。",
        "点击库里的 HUD 会放到屏幕中央，拖拽则放到指定位置。多数 HUD 可以添加多次，各自拥有独立设置。",
        "拖动 HUD 即可移动，它会吸附到屏幕边缘和其它 HUD。按住 Alt 可自由摆放。拖拽边角可缩放。在空白处拖拽可一次框选多个 HUD。",
        "把 HUD 上下叠放或并排放置，它们的背景会合并成一块。拖动其中任意一个会带着整组合并体移动。要拆出某个，先点选它再拖走。",
        "右键 HUD 打开菜单，选择「锚定」。先点自己身上的一个点，再点另一个 HUD 上的点，之后它会跟随那个 HUD 移动。",
        "点击 HUD 会打开这个面板。「设置」页包含位置、缩放、背景、合并与可见性。",
        "「设计器」页调整 HUD 的外观：尺寸、对齐、内边距与颜色。",
        "在设计器的「文字选项」里可以在 Minecraft 与 Poppins 字体之间切换，并调整大小、字重、样式与大小写。",
        "右键空白处选择「编辑器引导」可以再看一遍。"
    };
    private final HudMotion motion = new HudMotion();
    private boolean fadeInitialized;
    private final HudStudio studio;
    private volatile Step step;
    private Step last;
    private Set<String> savedSelection = Set.of(), stillMerged = Set.of();
    private boolean savedPanel, savedTab, taskDone;
    private String sample;
    private Rect card;
    private double started;

    HudTour(HudStudio studio) {
        this.studio = studio;
    }

    public Step step() {
        return step;
    }

    public boolean active() {
        return step != null;
    }

    public boolean blocksEditor() {
        return active() && step != Step.MERGING;
    }

    public boolean taskDone() {
        return taskDone;
    }

    public void start(double now) {
        if (step == null) {
            savedSelection = studio.designer.selected();
            savedPanel = studio.panelOpen;
            savedTab = studio.designerTab;
        }
        studio.clearTransient();
        taskDone = false;
        step = Step.WELCOME;
        last = null;
        started = now;
    }

    public void end() {
        if (step == null) return;
        studio.preferences.set("hud.tour.seen", true);
        step = null;
        studio.clearTransient();
        studio.designer.selection(savedSelection);
        studio.panelOpen = savedPanel && !studio.designer.selected().isEmpty();
        studio.designerTab = savedTab;
        savedSelection = Set.of();
    }

    public boolean move(int delta) {
        if (step == null || delta > 0 && step == Step.MERGING && !taskDone) return false;
        int next = step.ordinal() + delta;
        if (next < 0) return false;
        if (next >= Step.values().length) {
            end();
            return true;
        }
        step = Step.values()[next];
        return true;
    }

    public void observe(Set<String> merged, boolean dragging) {
        if (step != Step.MERGING) return;
        Set<String> retained = new HashSet<>(stillMerged);
        retained.retainAll(merged);
        stillMerged = Set.copyOf(retained);
        if (!dragging && merged.stream().anyMatch(id -> !stillMerged.contains(id))) taskDone = true;
    }

    void render(PageContext c) {
        if (!fadeInitialized) {
            motion.seed("tour/visible", 0, c.time());
            fadeInitialized = true;
        }
        boolean visible = step != null;
        double alpha = motion.animate("tour/visible", visible ? 1 : 0, c.time(), 400, 1, .01);
        if (!visible && (last == null || alpha <= 0)) return;
        Step display = visible ? step : last;
        PageContext context =
                !visible && c instanceof HudChromeContext chrome ? chrome.silent() : c;
        c.painter().layer(new Rect(0, 0, c.width(), c.height()), alpha);
        try {
            renderContent(context, display, visible);
        } finally {
            c.painter().restore();
        }
    }

    private void renderContent(PageContext c, Step displayStep, boolean visible) {
        if (visible && last != displayStep) {
            studio.clearTransient();
            sample = chooseHud();
            if (displayStep == Step.SETTINGS
                    || displayStep == Step.DESIGNER
                    || displayStep == Step.FONTS) {
                studio.designer.select(sample, false);
                studio.panelOpen = sample != null;
                studio.designerTab = displayStep != Step.SETTINGS;
            } else {
                studio.designer.select(null, false);
                studio.panelOpen = false;
            }
            if (displayStep == Step.ADDING || displayStep == Step.MERGING)
                studio.libraryOpen = true;
            if (displayStep == Step.MERGING) {
                stillMerged = mergedIds();
                taskDone = false;
            }
            last = displayStep;
        }
        observe(mergedIds(), studio.designer.dragging());
        Rect focus =
                switch (displayStep) {
                    case ADDING -> studio.library.bounds();
                    case DRAGGING, ANCHORING ->
                            sample == null
                                    ? null
                                    : studio.chromeRect(
                                            studio.designer.rect(studio.designer.byId(sample)));
                    case SETTINGS, DESIGNER, FONTS -> studio.panel.bounds();
                    default -> null;
                };
        if (displayStep == Step.ANCHORING && focus != null) {
            Rect menu = anchorMenu(c, focus);
            focus = HudStudio.union(focus, menu);
        }
        Rect target =
                focus == null
                        ? new Rect(c.width() / 2, c.height() / 2, 0, 0)
                        : new Rect(focus.x() - 6, focus.y() - 6, focus.w() + 12, focus.h() + 12);
        double hx = c.animate("hud/tour-hole-x", target.x(), .22, Motion.Easing.FAST_OUT_SLOW_IN),
                hy = c.animate("hud/tour-hole-y", target.y(), .22, Motion.Easing.FAST_OUT_SLOW_IN),
                hr =
                        c.animate(
                                "hud/tour-hole-r",
                                target.right(),
                                .22,
                                Motion.Easing.FAST_OUT_SLOW_IN),
                hb =
                        c.animate(
                                "hud/tour-hole-b",
                                target.bottom(),
                                .22,
                                Motion.Easing.FAST_OUT_SLOW_IN),
                ring =
                        c.animate(
                                "hud/tour-ring",
                                focus == null ? 0 : 1,
                                .22,
                                Motion.Easing.FAST_OUT_SLOW_IN),
                scrim =
                        c.animate(
                                "hud/tour-scrim",
                                displayStep != Step.MERGING ? 1 : 0,
                                .22,
                                Motion.Easing.FAST_OUT_SLOW_IN);
        Rect hole = new Rect(hx, hy, Math.max(0, hr - hx), Math.max(0, hb - hy));
        if (scrim > .001) scrim(c.painter(), hole, c.width(), c.height(), .6 * scrim);
        if (ring > .001) c.painter().outline(hole, 8, 1.5, HudRuntime.opacity(c.accent(), ring));
        if (visible && displayStep != Step.MERGING)
            c.hit("hud:tour-block", new Rect(0, 0, c.width(), c.height()), () -> {}, "");
        if (displayStep == Step.MERGING && !taskDone && !studio.designer.dragging()) drawHint(c);
        String body = BODIES[displayStep.ordinal()],
                task =
                        displayStep == Step.MERGING
                                ? (taskDone
                                        ? "很好，它们的背景已经合并。"
                                        : hasPair()
                                                ? "试一试：把两个 HUD 上下叠放，直到背景合并。"
                                                : "试一试：从库里再拖一个文字 HUD，叠到第一个的上方或下方。")
                                : null;
        double width = Math.min(280, c.width() - 32),
                bodyH = lines(c, body, width - 32) * 17,
                taskH = task == null ? 0 : lines(c, task, width - 66) * 17 + 16;
        double height =
                16 + 6 + 8 + 18 + 8 + bodyH + 8 + (task == null ? 0 : taskH + 8) + 4 + 30 + 16;
        Rect slot =
                place(
                        c.width(),
                        c.height(),
                        width,
                        height,
                        focus,
                        displayStep == Step.MERGING,
                        studio.library.bounds());
        double x = c.animate("hud/tour-card-x", slot.x(), .22, Motion.Easing.FAST_OUT_SLOW_IN),
                y = c.animate("hud/tour-card-y", slot.y(), .22, Motion.Easing.FAST_OUT_SLOW_IN);
        card = new Rect(x, y, width, height);
        HudWidgets.panel(c, card);
        c.hit("hud:tour-card", card, () -> {}, "");
        for (int i = 0; i < Step.values().length; i++)
            c.painter()
                    .rect(
                            new Rect(x + 16 + i * 10, y + 16, 6, 6),
                            3,
                            i <= displayStep.ordinal() ? c.accent() : c.theme().border());
        HudWidgets.label(
                c, TITLES[displayStep.ordinal()], x + 16, y + 30, 14, true, c.theme().text());
        double cy =
                HudWidgets.wrap(c, body, x + 16, y + 56, width - 32, 12, 17, c.theme().secondary())
                        + 8;
        if (task != null) {
            Rect tr = new Rect(x + 16, cy, width - 32, taskH);
            c.painter().rect(tr, c.theme().radius(5), Theme.alpha(c.accent(), .12));
            c.painter()
                    .icon(
                            taskDone ? "check-circle" : "move",
                            tr.x() + 10,
                            tr.y() + 8,
                            16,
                            c.accent());
            HudWidgets.wrap(
                    c, task, tr.x() + 34, tr.y() + 8, tr.w() - 44, 12, 17, c.theme().text());
        }
        double by = card.bottom() - 46;
        if (displayStep != Step.DONE)
            tourButton(c, "tour-skip", new Rect(x + 8, by, 90, 30), "跳过引导", false, true, this::end);
        if (displayStep != Step.WELCOME)
            tourButton(
                    c,
                    "tour-back",
                    new Rect(card.right() - 148, by, 64, 30),
                    "上一步",
                    false,
                    true,
                    () -> move(-1));
        tourButton(
                c,
                "tour-next",
                new Rect(card.right() - 80, by, 64, 30),
                displayStep == Step.DONE ? "完成" : "下一步",
                true,
                displayStep != Step.MERGING || taskDone,
                () -> move(1));
    }

    private void tourButton(
            PageContext c,
            String id,
            Rect r,
            String label,
            boolean primary,
            boolean enabled,
            Runnable action) {
        boolean hover = enabled && r.contains(c.mouseX(), c.mouseY());
        int base = primary ? c.accent() : 0, background = hover ? whiteOver(base, .12) : base;
        int animated = motion.color("tour/button-bg/" + id, background, c.time());
        double alpha =
                motion.animate("tour/button-alpha/" + id, enabled ? 1 : .4, c.time(), 1500, 1, .01);
        c.painter().layer(r, alpha);
        try {
            c.painter().rect(r, c.theme().buttonRadius(), animated);
            HudWidgets.text(
                    c, label, r.inset(6), 12, false, primary ? 0xE6FFFFFF : c.theme().secondary());
        } finally {
            c.painter().restore();
        }
        if (enabled) c.hit("hud:" + id, r, action, "");
    }

    private static int whiteOver(int base, double opacity) {
        double a = ((base >>> 24) & 255) / 255d, out = opacity + a * (1 - opacity);
        if (out == 0) return 0;
        int color = (int) Math.round(out * 255) << 24;
        for (int shift = 0; shift < 24; shift += 8) {
            double v = (255 * opacity + ((base >>> shift) & 255) * a * (1 - opacity)) / out;
            color |= (int) Math.round(v) << shift;
        }
        return color;
    }

    boolean containsCard(double x, double y) {
        return active() && card != null && card.contains(x, y);
    }

    private String chooseHud() {
        HudElement old = studio.designer.byId(sample);
        if (old != null && !old.hidden()) return sample;
        return studio.designer.elements().stream()
                .filter(e -> e instanceof HudNode && !e.hidden())
                .map(HudElement::id)
                .findFirst()
                .orElseGet(
                        () ->
                                studio.designer.elements().stream()
                                        .filter(e -> !e.hidden())
                                        .map(HudElement::id)
                                        .findFirst()
                                        .orElse(null));
    }

    private Set<String> mergedIds() {
        Set<String> ids = new HashSet<>();
        for (var g : studio.designer.mergeGroups()) ids.addAll(g.ids());
        return ids;
    }

    private boolean hasPair() {
        List<HudElement> nodes =
                studio.designer.elements().stream()
                        .filter(HudMerge::candidate)
                        .filter(e -> !studio.designer.locked(e.id()))
                        .toList();
        for (var a : nodes)
            for (var b : nodes)
                if (a != b
                        && HudMerge.sameStyle((HudNode) a, (HudNode) b)
                        && !studio.designer.mergeGroup(a.id()).contains(b.id())) return true;
        return false;
    }

    private Rect anchorMenu(PageContext c, Rect hud) {
        HudElement target = studio.designer.byId(sample);
        HudContextMenu preview = new HudContextMenu(studio, hud.right() + 8, hud.y(), target, true);
        Rect measured = preview.measure(c);
        double x =
                hud.right() + 8 + measured.w() <= c.width() - 8
                        ? hud.right() + 8
                        : Math.max(8, hud.x() - 8 - measured.w());
        double y = Geometry.clamp(hud.y(), 8, Math.max(8, c.height() - measured.h() - 8));
        preview = new HudContextMenu(studio, x, y, target, true);
        preview.render(c);
        return preview.bounds();
    }

    private void drawHint(PageContext c) {
        Rect[] hint = mergeHint();
        if (hint == null) return;
        double phase = (c.time() - started) % 2.2,
                progress =
                        phase < .4
                                ? 0
                                : phase > 1.6
                                        ? 1
                                        : Motion.ease(
                                                Motion.Easing.FAST_OUT_SLOW_IN, (phase - .4) / 1.2);
        Rect a = hint[0],
                b = hint[1],
                ghost =
                        new Rect(
                                a.x() + (b.x() - a.x()) * progress,
                                a.y() + (b.y() - a.y()) * progress,
                                a.w(),
                                a.h());
        HudWidgets.dashed(c.painter(), b, Theme.alpha(c.accent(), .8), 6, 4);
        HudWidgets.dash(
                c.painter(),
                a.x() + a.w() / 2,
                a.y() + a.h() / 2,
                b.x() + b.w() / 2,
                b.y() + b.h() / 2,
                1.5,
                Theme.alpha(c.accent(), .5),
                6,
                4);
        c.painter().rect(ghost, 4, Theme.alpha(c.accent(), .2));
        c.painter().outline(ghost, 4, 1.5, c.accent());
        c.painter()
                .rect(
                        new Rect(
                                ghost.x() + ghost.w() / 2 - 7,
                                ghost.y() + ghost.h() / 2 - 7,
                                14,
                                14),
                        7,
                        0xFFFFFFFF);
        c.painter()
                .rect(
                        new Rect(
                                ghost.x() + ghost.w() / 2 - 5,
                                ghost.y() + ghost.h() / 2 - 5,
                                10,
                                10),
                        5,
                        c.accent());
    }

    private Rect[] mergeHint() {
        List<HudElement> nodes =
                studio.designer.elements().stream()
                        .filter(HudMerge::candidate)
                        .filter(e -> !studio.designer.locked(e.id()))
                        .toList();
        for (boolean stacked : new boolean[] {true, false}) {
            double best = Double.POSITIVE_INFINITY;
            Rect[] result = null;
            for (HudElement a : nodes)
                for (HudElement b : nodes)
                    if (a != b
                            && HudMerge.sameStyle((HudNode) a, (HudNode) b)
                            && !studio.designer.mergeGroup(a.id()).contains(b.id())) {
                        Rect ar = studio.designer.rect(a), br = studio.designer.rect(b);
                        for (int sign : new int[] {1, -1}) {
                            double x = stacked ? br.x() : sign > 0 ? br.right() : br.x() - ar.w(),
                                    y =
                                            stacked
                                                    ? (sign > 0 ? br.bottom() : br.y() - ar.h())
                                                    : br.y();
                            if (x < 0
                                    || y < 0
                                    || x + ar.w() > studio.designer.viewportWidth()
                                    || y + ar.h() > studio.designer.viewportHeight()) continue;
                            double distance = Math.hypot(x - ar.x(), y - ar.y());
                            if (distance < best) {
                                best = distance;
                                result =
                                        new Rect[] {
                                            studio.chromeRect(ar),
                                            studio.chromeRect(new Rect(x, y, ar.w(), ar.h()))
                                        };
                            }
                        }
                    }
            if (result != null) return result;
        }
        return null;
    }

    static Rect place(
            double w, double h, double cw, double ch, Rect focus, boolean bottom, Rect avoid) {
        double maxX = Math.max(16, w - cw - 16),
                maxY = Math.max(16, h - ch - 16),
                cx = (w - cw) / 2,
                cy = (h - ch) / 2;
        if (bottom) {
            Rect r = new Rect(Math.max(16, cx), maxY, cw, ch);
            return avoid != null && avoid.intersects(r)
                    ? new Rect(Math.max(16, avoid.x() - 12 - cw), maxY, cw, ch)
                    : r;
        }
        if (focus == null) return new Rect(Math.max(0, cx), Math.max(0, cy), cw, ch);
        double by = Geometry.clamp(focus.y() + (focus.h() - ch) / 2, 16, maxY),
                bx = Geometry.clamp(focus.x() + (focus.w() - cw) / 2, 16, maxX);
        for (Rect r :
                List.of(
                        new Rect(focus.right() + 12, by, cw, ch),
                        new Rect(focus.x() - 12 - cw, by, cw, ch),
                        new Rect(bx, focus.bottom() + 12, cw, ch),
                        new Rect(bx, focus.y() - 12 - ch, cw, ch)))
            if (r.x() >= 16 && r.y() >= 16 && r.x() <= maxX && r.y() <= maxY) return r;
        return new Rect(cx, maxY, cw, ch);
    }

    private static int lines(PageContext c, String text, double width) {
        int lines = 1;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String n = new String(Character.toChars(cp));
            if (c.painter().measure(b + n, 12, false) > width && b.length() > 0) {
                lines++;
                b.setLength(0);
            }
            b.append(n);
        }
        return lines;
    }

    private static void scrim(Painter p, Rect hole, double width, double height, double alpha) {
        int color = Theme.alpha(0xFF000000, alpha);
        double l = Geometry.clamp(hole.x(), 0, width),
                r = Geometry.clamp(hole.right(), 0, width),
                t = Geometry.clamp(hole.y(), 0, height),
                b = Geometry.clamp(hole.bottom(), 0, height);
        p.rect(new Rect(0, 0, width, t), 0, color);
        p.rect(new Rect(0, b, width, Math.max(0, height - b)), 0, color);
        p.rect(new Rect(0, t, l, Math.max(0, b - t)), 0, color);
        p.rect(new Rect(r, t, Math.max(0, width - r), Math.max(0, b - t)), 0, color);
        double radius = Math.min(8, Math.min(hole.w(), hole.h()) / 2);
        for (double y = 0; y < radius; y += .5) {
            double inset =
                    radius
                            - Math.sqrt(
                                    Math.max(0, radius * radius - Math.pow(radius - y - .25, 2)));
            for (double x : new double[] {hole.x(), hole.right() - inset}) {
                p.rect(new Rect(x, hole.y() + y, inset, .5), 0, color);
                p.rect(new Rect(x, hole.bottom() - y - .5, inset, .5), 0, color);
            }
        }
    }
}
