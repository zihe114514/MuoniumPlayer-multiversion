/*
 * This file is part of OneConfig.
 * OneConfig - Next Generation Config Library for Minecraft: Java Edition
 * Copyright (C) 2021~2024 Polyfrost.
 *   <https://polyfrost.org> <https://github.com/Polyfrost/>
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *   OneConfig is licensed under the terms of version 3 of the GNU Lesser
 * General Public License as published by the Free Software Foundation, AND
 * under the Additional Terms Applicable to OneConfig, as published by Polyfrost,
 * either version 1.0 of the Additional Terms, or (at your option) any later
 * version.
 *
 *   This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 * License.  If not, see <https://www.gnu.org/licenses/>. You should
 * have also received a copy of the Additional Terms Applicable
 * to OneConfig, as published by Polyfrost. If not, see
 * <https://polyfrost.org/legal/oneconfig/additional-terms>
 */

package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.host.HudExtensions;
import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Painter;

import java.time.LocalDateTime;
import java.util.*;

/** Neutral game-frame callback. Never creates a surface, GL context, native font or renderer. */
public final class HudRuntime {
    /** Width/height and drawing use host layout units; physicalPerUnit maps one unit to pixels. */
    public record Frame(
            double width,
            double height,
            double guiScale,
            double time,
            LocalDateTime dateTime,
            boolean editor,
            boolean debug,
            boolean tab,
            boolean screen,
            boolean chat,
            Set<Integer> keys,
            Map<String, String> data,
            double physicalPerUnit) {
        /** Compatibility: the original constructor describes Minecraft GUI units. */
        public Frame(
                double width,
                double height,
                double guiScale,
                double time,
                LocalDateTime dateTime,
                boolean editor,
                boolean debug,
                boolean tab,
                boolean screen,
                boolean chat,
                Set<Integer> keys,
                Map<String, String> data) {
            this(
                    width, height, guiScale, time, dateTime, editor, debug, tab, screen, chat, keys,
                    data, guiScale);
        }

        public Frame {
            if (!Double.isFinite(width)
                    || !Double.isFinite(height)
                    || !Double.isFinite(guiScale)
                    || !Double.isFinite(physicalPerUnit)
                    || !Double.isFinite(guiScale / physicalPerUnit)
                    || !Double.isFinite(time)
                    || width <= 0
                    || height <= 0
                    || guiScale <= 0
                    || physicalPerUnit <= 0)
                throw new IllegalArgumentException("Invalid HUD frame");
            Objects.requireNonNull(dateTime);
            keys = Set.copyOf(keys);
            data = Map.copyOf(data);
        }

        /** Physical pixels per supplied Painter/layout unit, independent of Minecraft GUI scale. */
        public Frame withPhysicalScale(double pixelsPerUnit) {
            return new Frame(
                    width,
                    height,
                    guiScale,
                    time,
                    dateTime,
                    editor,
                    debug,
                    tab,
                    screen,
                    chat,
                    keys,
                    data,
                    pixelsPerUnit);
        }

        public Frame editing() {
            return new Frame(
                    width,
                    height,
                    guiScale,
                    time,
                    dateTime,
                    true,
                    debug,
                    tab,
                    screen,
                    chat,
                    keys,
                    data,
                    physicalPerUnit);
        }

        public static Frame editor(double width, double height, double time) {
            return new Frame(
                    width,
                    height,
                    1,
                    time,
                    LocalDateTime.now(),
                    true,
                    false,
                    false,
                    true,
                    false,
                    Set.of(),
                    Map.of());
        }
    }

    public record TextStyle(
            String font,
            String weight,
            double size,
            boolean bold,
            boolean italic,
            boolean underline,
            boolean shadow,
            boolean outline,
            int color,
            int shadowColor,
            double shadowX,
            double shadowY,
            int align) {}

    public record Metrics(double width, double height, double baseline, double lineHeight) {
        public Metrics {
            if (!Double.isFinite(width)
                    || !Double.isFinite(height)
                    || !Double.isFinite(baseline)
                    || !Double.isFinite(lineHeight)
                    || width < 0
                    || height < 0
                    || lineHeight < 0)
                throw new IllegalArgumentException("Invalid HUD text metrics");
        }
    }

    public interface TextRenderer {
        Metrics measure(String text, TextStyle style);

        /** x/y are the top-left, not a baseline. Use the passed existing painter/frame. */
        void draw(Painter painter, String text, double x, double y, TextStyle style);
    }

    private record AxisLink(
            String parent,
            HudElement.Anchor self,
            HudElement.Anchor target,
            double offset,
            HudMerge.Axis axis) {}

    private final HudDesigner designer;
    private final HudExtensions host;
    private final Map<String, AxisLink> mergeLinks = new LinkedHashMap<>();
    private final Set<Integer> previousKeys = new HashSet<>();
    private Set<String> mergedIds = Set.of();
    private final Map<String, String> positions = new HashMap<>();
    private String owner;
    private boolean[] loadingGuard = {false};

    public HudRuntime(HudDesigner designer, HudExtensions host) {
        this.designer = Objects.requireNonNull(designer);
        this.host = host;
        this.owner = host == null ? "host" : host.hudOwner();
    }

    /** Initialize saved HUDs before any settings page is opened; host retains the returned runtime. */
    public static HudRuntime open(ConfigHost config, UiPreferences preferences) {
        if (!(config instanceof HudExtensions extension))
            throw new IllegalArgumentException(
                    "HudExtensions is required for game HUD persistence");
        boolean[] loading = {true};
        HudDesigner[] model = new HudDesigner[1];
        List<HudElement> elements = new ArrayList<>();
        for (HudElement e : config.huds())
            elements.add(
                    extension.hudCapabilities().contains(HudExtensions.Capability.HOST_SETTINGS)
                            ? new HudHostElement(e, () -> extension.hudSettings(e))
                            : e);
        model[0] =
                new HudDesigner(
                        elements,
                        preferences,
                        () -> {
                            if (loading[0]) return;
                            config.persist();
                            extension.saveHudDocument(new HudDocument(model[0].snapshot()));
                        });
        for (HudElement provider : extension.hudProviders()) model[0].registerProvider(provider);
        HudRuntime.Frame frame = extension.hudFrame();
        model[0].viewport(frame.width(), frame.height(), frame.guiScale(), frame.physicalPerUnit());
        HudRuntime runtime = new HudRuntime(model[0], extension);
        runtime.loadingGuard = loading;
        runtime.reload(extension.loadHudDocument());
        loading[0] = false;
        extension.bindHudRuntime(runtime);
        return runtime;
    }

    public HudDesigner designer() {
        return designer;
    }

    /** Profile load preserves the host configuration as the authority for intrinsic HUD geometry. */
    public void reload(HudDocument document) {
        boolean previousLoading = loadingGuard[0];
        loadingGuard[0] = true;
        try {
            if (document == null) document = HudDocument.empty();
            if (host != null) {
                Frame frame = host.hudFrame();
                designer.viewport(
                        frame.width(), frame.height(), frame.guiScale(), frame.physicalPerUnit());
            }
            Map<String, HudDesigner.State> live = new LinkedHashMap<>();
            for (var s : designer.snapshot()) if (s.type().equals("host")) live.put(s.id(), s);
            List<HudDesigner.State> states = new ArrayList<>();
            for (var s : document.elements()) {
                var current = live.remove(s.id());
                if (current != null)
                    states.add(
                            new HudDesigner.State(
                                    current.id(),
                                    current.x(),
                                    current.y(),
                                    current.width(),
                                    current.height(),
                                    current.scale(),
                                    current.hidden(),
                                    s.locked(),
                                    current.type(),
                                    current.properties(),
                                    s.anchor(),
                                    s.order()));
                else if (!s.type().equals("host")) states.add(s);
            }
            states.addAll(live.values());
            designer.restore(states);
            designer.history.clear();
            mergeLinks.clear();
            positions.clear();
            previousKeys.clear();
        } finally {
            loadingGuard[0] = previousLoading;
        }
    }

    public void tick(Frame frame) {
        designer.viewport(frame.width(), frame.height(), frame.guiScale(), frame.physicalPerUnit());
        if (host != null && !Objects.equals(owner, host.hudOwner())) {
            owner = host.hudOwner();
            reload(host.loadHudDocument());
            designer.poll(frame.time(), owner);
        }
        designer.viewport(frame.width(), frame.height(), frame.guiScale(), frame.physicalPerUnit());
        for (HudElement e : designer.elements())
            if (e instanceof HudNode n) {
                n.frame(frame.guiScale(), frame.physicalPerUnit());
                if (!frame.editor()) handleKeys(n, frame);
            }
        designer.resolveAnchors();
        previousKeys.clear();
        previousKeys.addAll(frame.keys());
    }

    public void render(Painter painter, Frame frame) {
        tick(frame);
        TextRenderer text = textRenderer();
        for (HudElement e : designer.elements())
            if (e instanceof HudNode n) {
                n.frame(frame.guiScale(), frame.physicalPerUnit());
                measure(painter, n, frame, text);
            }
        designer.resolveAnchors();
        followMergeLinks();
        List<HudMerge.Group> groups =
                frame.editor()
                        ? designer.mergeGroups()
                        : HudMerge.groups(
                                designer.elements().stream()
                                        .filter(
                                                e ->
                                                        !(e instanceof HudNode n)
                                                                || visible(n, frame)
                                                                || n.hidden()
                                                                        && n.flag(
                                                                                "keepBgWhenHidden"))
                                        .toList(),
                                designer::rect,
                                designer.anchors(),
                                Set.of());
        Set<String> ids = new HashSet<>();
        Map<String, AxisLink> next = new LinkedHashMap<>();
        for (var g : groups) {
            ids.addAll(g.ids());
            for (var l : g.links()) {
                HudElement child = designer.byId(l.child()), parent = designer.byId(l.parent());
                Rect c = designer.rect(child), p = designer.rect(parent);
                double offset =
                        l.axis() == HudMerge.Axis.X
                                ? c.x() + l.self().fx * c.w() - p.x() - l.target().fx * p.w()
                                : c.y() + l.self().fy * c.h() - p.y() - l.target().fy * p.h();
                next.put(
                        l.child() + "/" + l.axis(),
                        new AxisLink(
                                l.parent(),
                                l.self(),
                                l.target(),
                                offset / child.positionUnitScale(),
                                l.axis()));
            }
        }
        mergeLinks.clear();
        mergeLinks.putAll(next);
        mergedIds = Set.copyOf(ids);
        for (var g : groups) {
            HudNode n = (HudNode) designer.byId(g.ids().iterator().next());
            int color = color(n, "bg", frame.time());
            if (n.hidden() && frame.editor()) color = opacity(color, .35);
            HudMerge.Shape shape = new HudMerge.Shape(g.shape().loops(), g.shape().radius(), color);
            if (host != null) host.renderHudShape(painter, shape);
            else HudMerge.draw(painter, shape);
        }
        for (HudElement e : designer.orderedElements()) {
            Rect r = designer.rect(e);
            if (e instanceof HudNode n) {
                if (visible(n, frame) || frame.editor())
                    drawNode(painter, n, r, frame, text, ids.contains(n.id()));
            } else if (frame.editor()) {
                if (host != null)
                    host.renderHostHud(
                            painter, e instanceof HudHostElement h ? h.delegate : e, r, frame);
                else e.preview(r.x(), r.y());
            } else if (host != null
                    && host.hudCapabilities().contains(HudExtensions.Capability.HOST_COMPOSITION)
                    && !e.hidden())
                host.renderHostHud(
                        painter, e instanceof HudHostElement h ? h.delegate : e, r, frame);
        }
        positions.clear();
        for (HudElement e : designer.elements())
            if (e instanceof HudNode n) positions.put(n.id(), positionKey(n));
    }

    /** Library row packing must measure every provider before calculating section scroll offsets. */
    public void measurePreview(Painter painter, HudElement element, Frame frame) {
        if (element instanceof HudNode n) {
            n.frame(frame.guiScale(), frame.physicalPerUnit());
            measure(painter, n, frame, textRenderer());
        }
    }

    /** Measure-only preview card path; it never changes the instance's position/visibility/settings. */
    public void preview(Painter painter, HudElement element, Rect card, Frame frame) {
        if (!(element instanceof HudNode n)) {
            painter.text(
                    element.title(),
                    card.x() + 12,
                    card.y() + (card.h() - 12) / 2,
                    12,
                    false,
                    0xFFD5DBFF);
            return;
        }
        TextRenderer text = textRenderer();
        measure(painter, n, frame, text);
        double scale =
                Math.max(
                        .05,
                        Math.min(
                                2,
                                Math.min(
                                        (card.w() - 24) / Math.max(1, n.width()),
                                        (card.h() - 24) / Math.max(1, n.height()))));
        painter.save();
        try {
            painter.clip(card, 4);
            painter.translate(
                    card.x() + (card.w() - n.width() * scale) / 2,
                    card.y() + (card.h() - n.height() * scale) / 2);
            painter.scale(scale, scale);
            drawNode(
                    painter,
                    n,
                    new Rect(0, 0, n.width(), n.height()),
                    frame.editing(),
                    text,
                    false);
        } finally {
            painter.restore();
        }
    }

    public void textPreview(Painter painter, HudNode n, Rect bounds, double time) {
        TextRenderer exact = textRenderer();
        TextStyle base = style(n, time, 1);
        TextStyle style =
                new TextStyle(
                        base.font(),
                        base.weight(),
                        14 * n.number("textScale"),
                        base.bold(),
                        base.italic(),
                        base.underline(),
                        base.shadow(),
                        base.outline(),
                        base.color(),
                        base.shadowColor(),
                        base.shadowX(),
                        base.shadowY(),
                        1);
        String text = n.flag("brackets") ? "[Hello, OneConfig!]" : "Hello, OneConfig!";
        if ((int) n.number("caseType") == 1) text = text.toUpperCase(Locale.ROOT);
        else if ((int) n.number("caseType") == 2) text = text.toLowerCase(Locale.ROOT);
        Metrics m =
                exact == null ? fallbackMetrics(painter, text, style) : exact.measure(text, style);
        painter.save();
        try {
            painter.clip(bounds, 4);
            double x = bounds.x() + (bounds.w() - m.width()) / 2,
                    y = bounds.y() + (bounds.h() - m.height()) / 2;
            if (exact == null) fallbackDraw(painter, text, x, y, style, m);
            else exact.draw(painter, text, x, y, style);
        } finally {
            painter.restore();
        }
    }

    private TextRenderer textRenderer() {
        TextRenderer renderer = host == null ? null : host.hudTextRenderer();
        if (renderer == null
                && host != null
                && host.hudCapabilities().contains(HudExtensions.Capability.EXACT_TEXT))
            throw new IllegalStateException(
                    "EXACT_TEXT was advertised without a real text renderer");
        return renderer;
    }

    private void measure(Painter p, HudNode n, Frame f, TextRenderer exact) {
        n.frame(f.guiScale(), f.physicalPerUnit());
        if (n.type().equals("icon")) {
            n.measured(
                    n.number("iconSize") + n.number("padLeft") + n.number("padRight"),
                    n.number("iconSize") + n.number("padTop") + n.number("padBottom"));
            return;
        }
        String value = n.text(f.dateTime(), f.data());
        TextStyle style = style(n, f.time(), 1);
        Metrics metrics =
                exact == null ? fallbackMetrics(p, value, style) : exact.measure(value, style);
        n.measured(
                metrics.width() + n.number("padLeft") + n.number("padRight"),
                metrics.height() + n.number("padTop") + n.number("padBottom"));
    }

    private void drawNode(
            Painter p, HudNode n, Rect r, Frame f, TextRenderer exact, boolean merged) {
        double alpha = f.editor() && n.hidden() ? .35 : 1;
        p.layer(r, alpha);
        try {
            if (!merged && n.flag("showBackground"))
                p.rect(r, n.number("bgRadius") * n.effectiveScale(), color(n, "bg", f.time()));
            if (!f.editor() && n.hidden()) return;
            p.save();
            try {
                p.translate(r.x(), r.y());
                p.scale(n.effectiveScale(), n.effectiveScale());
                double w = r.w() / n.effectiveScale(),
                        h = r.h() / n.effectiveScale(),
                        x = n.number("padLeft"),
                        y = n.number("padTop"),
                        availW = w - x - n.number("padRight"),
                        availH = h - y - n.number("padBottom");
                if (n.type().equals("icon")) {
                    p.icon(
                            n.get("icon", "star"),
                            x,
                            y,
                            n.number("iconSize"),
                            color(n, "text", f.time()));
                    return;
                }
                String value = n.text(f.dateTime(), f.data());
                TextStyle style = style(n, f.time(), 1);
                Metrics m =
                        exact == null
                                ? fallbackMetrics(p, value, style)
                                : exact.measure(value, style);
                HudElement.Anchor align =
                        n.flag("staticWidth") ? n.anchor("alignment") : HudElement.Anchor.TOP_LEFT;
                x += Math.max(0, availW - m.width()) * align.fx;
                y += Math.max(0, availH - m.height()) * align.fy;
                if (exact != null) exact.draw(p, value, x, y, style);
                else fallbackDraw(p, value, x, y, style, m);
            } finally {
                p.restore();
            }
        } finally {
            p.restore();
        }
    }

    private void followMergeLinks() {
        if (designer.dragging()) {
            mergeLinks.clear();
            return;
        }
        for (HudElement e : designer.elements())
            if (e instanceof HudNode n
                    && positions.containsKey(n.id())
                    && !positions.get(n.id()).equals(positionKey(n)))
                for (HudMerge.Axis axis : HudMerge.Axis.values())
                    mergeLinks.remove(n.id() + "/" + axis);
        Set<String> done = new HashSet<>();
        for (HudElement e : designer.elements()) follow(e, done, new HashSet<>());
    }

    private void follow(HudElement e, Set<String> done, Set<String> visiting) {
        if (done.contains(e.id()) || !visiting.add(e.id()) || designer.anchor(e.id()) != null)
            return;
        Rect r = designer.rect(e);
        double x = r.x(), y = r.y();
        for (HudMerge.Axis axis : HudMerge.Axis.values()) {
            AxisLink l = mergeLinks.get(e.id() + "/" + axis);
            HudElement p = l == null ? null : designer.byId(l.parent());
            if (p == null
                    || !HudMerge.candidate(e)
                    || !HudMerge.candidate(p)
                    || !HudMerge.sameStyle((HudNode) e, (HudNode) p)) continue;
            follow(p, done, visiting);
            Rect pr = designer.rect(p);
            if (axis == HudMerge.Axis.X)
                x =
                        pr.x()
                                + l.target().fx * pr.w()
                                + l.offset() * e.positionUnitScale()
                                - l.self().fx * r.w();
            else
                y =
                        pr.y()
                                + l.target().fy * pr.h()
                                + l.offset() * e.positionUnitScale()
                                - l.self().fy * r.h();
        }
        e.absolutePosition(x, y, designer.viewportWidth(), designer.viewportHeight());
        done.add(e.id());
        visiting.remove(e.id());
    }

    private static String positionKey(HudNode n) {
        return n.get("section", "") + "/" + n.get("relativeX", "") + "/" + n.get("relativeY", "");
    }

    public Set<String> mergedIds() {
        return mergedIds;
    }

    public boolean visible(HudNode n, Frame f) {
        return ((int) n.number("showKey") < 0 || f.keys().contains((int) n.number("showKey")))
                && !n.hidden()
                && (!f.debug() || n.flag("showInF3"))
                && (!f.tab() || n.flag("showInTab"))
                && (!f.screen() || n.flag("showInScreens"))
                && (!f.chat() || n.flag("showInChat"));
    }

    private void handleKeys(HudNode n, Frame frame) {
        int toggle = (int) n.number("toggleKey"), show = (int) n.number("showKey");
        if (toggle >= 0 && frame.keys().contains(toggle) && !previousKeys.contains(toggle))
            designer.edit("HUD 可见性按键", () -> n.hidden(!n.hidden()));
    }

    public static TextStyle style(HudNode n, double time, double alpha) {
        return new TextStyle(
                n.get("font", "Minecraft"),
                n.get("textWeight", "Regular"),
                8 * n.number("textScale"),
                n.flag("textBold"),
                n.flag("textItalic"),
                n.flag("textUnderline"),
                n.flag("showShadow"),
                n.flag("fullShadow"),
                opacity(color(n, "text", time), alpha),
                opacity(color(n, "shadow", time), alpha),
                n.number("shadowOffsetX"),
                n.number("shadowOffsetY"),
                (int) n.number("textAlign"));
    }

    private static Metrics fallbackMetrics(Painter p, String text, TextStyle s) {
        String[] lines = text.split("\n", -1);
        double width = 0;
        for (String line : lines) width = Math.max(width, p.measure(line, s.size(), s.bold()));
        return new Metrics(width, lines.length * s.size() * 1.25, s.size(), s.size() * 1.25);
    }

    private static void fallbackDraw(
            Painter p, String text, double x, double y, TextStyle s, Metrics m) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            double lineW = p.measure(line, s.size(), s.bold()),
                    xx = x + (m.width() - lineW) * Math.max(0, Math.min(2, s.align())) / 2,
                    yy = y + i * m.lineHeight();
            if (s.shadow()) {
                if (s.outline()) {
                    for (int ox = -1; ox <= 1; ox++)
                        for (int oy = -1; oy <= 1; oy++)
                            if (ox != 0 || oy != 0)
                                p.text(line, xx + ox, yy + oy, s.size(), s.bold(), s.shadowColor());
                } else
                    p.text(
                            line,
                            xx + s.shadowX(),
                            yy + s.shadowY(),
                            s.size(),
                            s.bold(),
                            s.shadowColor());
            }
            p.text(line, xx, yy, s.size(), s.bold(), s.color());
            if (s.underline())
                p.line(
                        xx,
                        yy + m.baseline() + 1,
                        xx + lineW,
                        yy + m.baseline() + 1,
                        Math.max(.5, s.size() / 14),
                        s.color());
        }
    }

    public static int color(HudNode n, String prefix, double time) {
        int base = n.color(prefix + "Color");
        return n.flag(prefix + "Chroma")
                ? chroma(base, n.number(prefix + "ChromaSpeed"), time)
                : base;
    }

    /** Same ten-second HSV hue cycle as PolyColor; alpha and base value are not mutated. */
    public static int chroma(int argb, double speed, double seconds) {
        double r = ((argb >>> 16) & 255) / 255d,
                g = ((argb >>> 8) & 255) / 255d,
                b = (argb & 255) / 255d,
                max = Math.max(r, Math.max(g, b)),
                min = Math.min(r, Math.min(g, b)),
                delta = max - min;
        double hue =
                delta == 0
                        ? 0
                        : max == r
                                ? ((g - b) / delta) % 6
                                : max == g ? (b - r) / delta + 2 : (r - g) / delta + 4;
        hue = (hue / 6 + seconds * Math.max(0, speed) / 10) % 1;
        if (hue < 0) hue += 1;
        double sat = max == 0 ? 0 : delta / max;
        return (argb & 0xFF000000) | (hsv(hue, sat, max) & 0xFFFFFF);
    }

    public static int hsv(double h, double s, double v) {
        double c = v * s, hh = (h % 1 + 1) % 1 * 6, x = c * (1 - Math.abs(hh % 2 - 1)), m = v - c;
        double r = 0, g = 0, b = 0;
        switch ((int) hh) {
            case 0 -> {
                r = c;
                g = x;
            }
            case 1 -> {
                r = x;
                g = c;
            }
            case 2 -> {
                g = c;
                b = x;
            }
            case 3 -> {
                g = x;
                b = c;
            }
            case 4 -> {
                r = x;
                b = c;
            }
            default -> {
                r = c;
                b = x;
            }
        }
        return 0xFF000000
                | ((int) Math.round((r + m) * 255) << 16)
                | ((int) Math.round((g + m) * 255) << 8)
                | (int) Math.round((b + m) * 255);
    }

    public static int opacity(int color, double alpha) {
        return (color & 0xFFFFFF)
                | ((int) Math.round(((color >>> 24) & 255) * Math.max(0, Math.min(1, alpha)))
                        << 24);
    }
}
