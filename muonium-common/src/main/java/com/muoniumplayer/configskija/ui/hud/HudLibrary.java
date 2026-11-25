package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;

/** Continuous grouped HUD library with the original tallest-first packing and 55% activation line. */
final class HudLibrary {
    record Entry(
            HudElement prototype,
            double width,
            double height,
            double previewScale,
            boolean legacy,
            boolean enabled) {}

    record Row(List<Entry> entries, double height, List<Double> widths) {
        Row {
            entries = List.copyOf(entries);
            widths = List.copyOf(widths);
        }
    }

    record Section(String id, String title, String icon, List<Entry> entries) {}

    private final HudMotion motion = new HudMotion();
    private final HudScrollAnimation scrollAnimation = new HudScrollAnimation();
    private final Motion.Scroll scrolling = new Motion.Scroll();
    private String pendingScroll;
    final TextEdit search = new TextEdit();
    private final HudStudio studio;
    private Rect bounds, icons;
    private double scroll, total;
    private String modIntent, activeMod;
    private final Map<String, Double> sectionOffsets = new LinkedHashMap<>();
    private final List<Rect> cards = new ArrayList<>();

    HudLibrary(HudStudio studio) {
        this.studio = studio;
    }

    Rect bounds() {
        return bounds;
    }

    Rect icons() {
        return icons;
    }

    void render(PageContext c, double sideAlpha) {
        if (scrollAnimation.active()) {
            scroll = animations(c) ? scrollAnimation.value(c.time()) : scrollAnimation.target();
            if (!animations(c)) scrollAnimation.cancel(scroll);
            scrolling.jump(scroll, c.time());
        } else scroll = scrolling.sample(c.time(), animations(c));
        for (HudElement provider : studio.providers()) {
            if (c instanceof HudChromeContext chrome)
                chrome.measureHudPreview(studio.runtime, provider);
            else studio.runtime.measurePreview(c.painter(), provider, HudStudio.editorFrame(c));
        }
        List<Section> all = sections(c, false), filtered = sections(c, true);
        boolean chrome =
                studio.designer.selected().isEmpty()
                        && !studio.designer.dragging()
                        && !studio.designer.marqueeActive();
        double
                visible =
                        c.animate(
                                "hud/library-chrome",
                                chrome ? 1 : 0,
                                .30,
                                Motion.Easing.FAST_OUT_SLOW_IN),
                open =
                        c.animate(
                                "hud/library-open",
                                studio.libraryOpen ? 1 : 0,
                                .30,
                                Motion.Easing.FAST_OUT_SLOW_IN);
        double density = Math.max(.01, c.host().uiDensity()),
                chromeSlide =
                        motion.animate(
                                        "library/slide-chrome",
                                        chrome ? 0 : Math.round(501 * density),
                                        c.time(),
                                        400,
                                        1,
                                        1)
                                / density,
                openSlide =
                        motion.animate(
                                        "library/slide-open",
                                        studio.libraryOpen ? 0 : Math.round(401 * density),
                                        c.time(),
                                        400,
                                        1,
                                        1)
                                / density;
        cards.clear();
        if (!chrome && chromeSlide >= 501 - .001) {
            bounds = null;
            icons = null;
            return;
        }
        double libraryH = Math.min(449, c.height() - 32),
                libraryW = Math.min(385, Math.max(100, c.width() - 112));
        double y = (c.height() - libraryH) / 2;
        double x = c.width() - libraryW - 100 + openSlide + chromeSlide;
        if (open > .001) {
            bounds = new Rect(x, y, libraryW, libraryH);
            Painter p = c.painter();
            p.layer(bounds, sideAlpha * open);
            try {
                HudWidgets.panel(c, bounds);
                c.hit("hud:library-block", bounds, () -> {}, "");
                HudWidgets.label(c, "HUD Library", x + 18, y + 18, 18, false, c.theme().text());
                Rect sr = new Rect(bounds.right() - 223, y + 16, 181, 32);
                if (libraryW > 320)
                    c.textField(
                            "hud:library-search",
                            search,
                            sr,
                            "Search HUDs...",
                            v -> {
                                jumpScroll(c, 0);
                                pendingScroll = null;
                                modIntent = null;
                            });
                HudWidgets.iconButton(
                        c,
                        "library-close",
                        new Rect(bounds.right() - 36, y + 18, 18, 18),
                        "close",
                        Theme.alpha(c.theme().text(), .7),
                        c.theme().text(),
                        () -> studio.libraryOpen = false);
                HudWidgets.label(
                        c,
                        "Click to add  ·  Drag to place it yourself",
                        x + 18,
                        y + 58,
                        11,
                        false,
                        c.theme().secondary());
                Rect body = new Rect(x + 18, y + 88, libraryW - 36, Math.max(0, libraryH - 106));
                // Pack and clamp first; every visible card/hit uses this frame's final scroll.
                Map<String, List<Row>> packed = new LinkedHashMap<>();
                sectionOffsets.clear(); total = 0;
                for (Section section : filtered) {
                    sectionOffsets.put(section.id(), total);
                    List<Row> rows = pack(section.entries(), body.w(), 10);
                    packed.put(section.id(), rows);
                    total += 32;
                    for (Row row : rows) total += row.height() + 10;
                }
                double maximum = Math.max(0, total - body.h());
                if (scrollAnimation.active()) {
                    scroll = Geometry.clamp(scroll, 0, maximum);
                    if (scrollAnimation.target() > maximum) scrollAnimation.begin(scroll, maximum, c.time(), density);
                    scrolling.range(maximum, c.time());
                    scrolling.jump(scroll, c.time());
                } else scroll = scrolling.range(maximum, c.time());
                if (pendingScroll != null && sectionOffsets.containsKey(pendingScroll)) {
                    double target = Math.min(sectionOffsets.get(pendingScroll), maximum);
                    if (animations(c)) {
                        if (!scrollAnimation.active() || Math.abs(scrollAnimation.target() - target) > .01)
                            scrollAnimation.begin(scroll, target, c.time(), density);
                        scrolling.jump(scroll, c.time());
                    } else { scrollAnimation.cancel(target); scroll = scrolling.jump(target, c.time()); }
                    pendingScroll = null;
                }
                p.save();
                try {
                    p.clip(body, 0);
                    double cursor = body.y() - scroll;
                    for (Section section : filtered) {
                        p.icon(section.icon(), body.x(), cursor, 16, c.theme().secondary());
                        HudWidgets.text(
                                c,
                                section.title().toUpperCase(Locale.ROOT),
                                new Rect(body.x() + 24, cursor, body.w() - 24, 16),
                                12,
                                false,
                                motion.tweenColor(
                                        "library/header/" + section.id(),
                                        section.id().equals(activeMod)
                                                ? c.theme().text()
                                                : c.theme().secondary(),
                                        c.time(),
                                        .15));
                        cursor += 26;
                        for (Row row : packed.get(section.id())) {
                            double rx = body.x();
                            for (int i = 0; i < row.entries().size(); i++) {
                                Entry entry = row.entries().get(i);
                                Rect card = new Rect(rx, cursor, row.widths().get(i), row.height());
                                if (card.intersects(body)) drawCard(c, entry, card, body);
                                rx += card.w() + 10;
                            }
                            cursor += row.height() + 10;
                        }
                        cursor += 6;
                    }
                    if (filtered.isEmpty())
                        HudWidgets.wrap(
                                c,
                                all.isEmpty()
                                        ? "HUDs appear here once a mod registers one."
                                        : "No HUDs found",
                                body.x(),
                                body.y() + 40,
                                body.w(),
                                14,
                                20,
                                c.theme().secondary());
                } finally {
                    p.restore();
                }
                double line = scroll + body.h() * .55;
                String scrolled = null;
                for (var entry : sectionOffsets.entrySet())
                    if (entry.getValue() <= line) scrolled = entry.getKey();
                activeMod = modIntent != null ? modIntent : scrolled;
                HudWidgets.scrollbar(
                        c,
                        "library-scrollbar",
                        new Rect(body.x(), body.y(), body.w() + 12, body.h()),
                        total,
                        scroll,
                        v -> {
                            jumpScroll(c, v);
                        });
            } finally {
                p.restore();
            }
        } else bounds = null;
        if (!all.isEmpty()) {
            double h = Math.min(c.height() - 32, 24 + all.size() * 48);
            icons = new Rect(c.width() - 76 + chromeSlide, (c.height() - h) / 2, 60, h);
            c.painter().layer(icons, sideAlpha);
            try {
                HudWidgets.panel(c, icons);
                c.hit("hud:library-icons", icons, () -> {}, "");
                for (int i = 0; i < all.size(); i++) {
                    Section section = all.get(i);
                    Rect icon = new Rect(icons.x() + 12, icons.y() + 12 + i * 48, 36, 36);
                    if (icon.bottom() > icons.bottom()) break;
                    boolean active = studio.libraryOpen && section.id().equals(activeMod);
                    if (active)
                        c.painter()
                                .rect(new Rect(icon.x() - 8, icon.y() + 6, 2, 24), 1, c.accent());
                    boolean hover = icon.contains(c.mouseX(), c.mouseY());
                    double iconAlpha =
                            motion.spring(
                                    "library/mod-alpha/" + section.id(),
                                    active ? 1 : hover ? .8 : .7,
                                    c.time());
                    c.painter()
                            .icon(
                                    section.icon(),
                                    icon.x(),
                                    icon.y(),
                                    36,
                                    HudRuntime.opacity(c.theme().text(), iconAlpha));
                    c.hit(
                            "hud:library-mod/" + section.id(),
                            icon,
                            () -> {
                                studio.libraryOpen = true;
                                modIntent = section.id();
                                if (filtered.stream().noneMatch(s -> s.id().equals(section.id())))
                                    search.text("");
                                pendingScroll = section.id();
                            },
                            "");
                }
            } finally {
                c.painter().restore();
            }
        } else icons = null;
    }

    private List<Section> sections(PageContext c, boolean filter) {
        double density = Math.max(.01, c.host().uiDensity());
        List<HudElement> providers = studio.providers();
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        Map<String, HudElement> first = new LinkedHashMap<>();
        String q = filter ? search.text().strip().toLowerCase(Locale.ROOT) : "";
        double maxWidth = Math.min(349, Math.max(64, c.width() - 148));
        for (HudElement e : providers) {
            if (!q.isEmpty()
                    && !(e.title() + " " + e.groupTitle() + " " + e.type())
                            .toLowerCase(Locale.ROOT)
                            .contains(q)) continue;
            boolean legacy = !(e instanceof HudNode);
            double naturalW = e.width() / Math.max(.001, e.scale()),
                    naturalH = e.height() / Math.max(.001, e.scale());
            if (naturalW <= 0) naturalW = 48;
            if (naturalH <= 0) naturalH = 48;
            double scale =
                    Math.max(
                            .05,
                            Math.min(
                                    2,
                                    Math.min(
                                            (maxWidth - 24) * density / naturalW,
                                            136 * density / naturalH)));
            double w = Math.min(maxWidth, naturalW * scale / density + 24),
                    h = naturalH * scale / density + 24;
            if (legacy) {
                w = Math.max(72, w);
                h = Math.max(72, h);
            } else {
                w = Math.min(maxWidth, Math.max(w, c.painter().measure(e.title(), 11, false) + 24));
                h += 20;
            }
            boolean enabled = legacy || studio.canAdd();
            grouped.computeIfAbsent(e.groupId(), k -> new ArrayList<>())
                    .add(new Entry(e, w, h, scale, legacy, enabled));
            first.putIfAbsent(e.groupId(), e);
        }
        List<Section> result = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            HudElement p = first.get(entry.getKey());
            result.add(new Section(entry.getKey(), p.groupTitle(), p.icon(), entry.getValue()));
        }
        return result;
    }

    static List<Row> pack(List<Entry> entries, double available, double gap) {
        List<List<Entry>> rows = new ArrayList<>();
        List<Double> widths = new ArrayList<>(), heights = new ArrayList<>();
        for (Entry e :
                entries.stream()
                        .sorted(Comparator.comparingDouble(Entry::height).reversed())
                        .toList()) {
            int index = -1;
            for (int i = 0; i < rows.size(); i++)
                if (widths.get(i) + gap + e.width() <= available
                        && e.height() >= heights.get(i) * .6) {
                    index = i;
                    break;
                }
            if (index < 0) {
                rows.add(new ArrayList<>(List.of(e)));
                widths.add(e.width());
                heights.add(e.height());
            } else {
                rows.get(index).add(e);
                widths.set(index, widths.get(index) + gap + e.width());
            }
        }
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<Entry> row = rows.get(i);
            double natural = row.stream().mapToDouble(Entry::width).sum(),
                    stretch =
                            Geometry.clamp((available - gap * (row.size() - 1)) / natural, 1, 1.35);
            out.add(
                    new Row(
                            row,
                            heights.get(i),
                            row.stream().map(e -> Math.floor(e.width() * stretch)).toList()));
        }
        return out;
    }

    private void drawCard(PageContext c, Entry entry, Rect card, Rect viewport) {
        Painter p = c.painter();
        boolean hovered =
                card.contains(c.mouseX(), c.mouseY()) && viewport.contains(c.mouseX(), c.mouseY());
        double t =
                c.animate(
                        "hud/library-card/" + entry.prototype().id(),
                        hovered ? 1 : 0,
                        .15,
                        Motion.Easing.FAST_OUT_SLOW_IN);
        p.rect(
                card,
                c.theme().radius(5),
                motion.tweenColor(
                        "library/card/" + entry.prototype().id(),
                        hovered ? c.accent() : c.theme().popup(),
                        c.time(),
                        .15));
        p.outline(card, c.theme().radius(5), 1, c.theme().border());
        if (entry.legacy()) {
            HudWidgets.text(
                    c,
                    entry.prototype().title(),
                    card.inset(12),
                    12,
                    false,
                    Theme.alpha(c.theme().text(), .85));
        } else {
            Rect preview = new Rect(card.x(), card.y(), card.w(), card.h() - 20);
            if (c instanceof HudChromeContext cc)
                cc.hudPreview(studio.runtime, entry.prototype(), preview);
            else studio.runtime.preview(p, entry.prototype(), preview, HudStudio.editorFrame(c));
            HudWidgets.text(
                    c,
                    entry.prototype().title(),
                    new Rect(card.x() + 12, card.bottom() - 20, card.w() - 24, 20),
                    11,
                    false,
                    c.theme().secondary());
        }
        if (t > .001) {
            p.rect(card, c.theme().radius(5), Theme.alpha(0xFF000000, .4 * t));
            p.icon(
                    "plus",
                    card.x() + (card.w() - 20) / 2,
                    card.y() + (card.h() - 20) / 2,
                    20,
                    HudRuntime.opacity(0xFFFFFFFF, t));
        }
        Rect hit =
                new Rect(
                        card.x(),
                        Math.max(card.y(), viewport.y()),
                        card.w(),
                        Math.max(
                                0,
                                Math.min(card.bottom(), viewport.bottom())
                                        - Math.max(card.y(), viewport.y())));
        cards.add(hit);
        c.hit(
                "hud:library-card/" + entry.prototype().id(),
                hit,
                () -> {
                    if (!entry.enabled()) {
                        c.toast("新增 HUD 的游戏渲染/存储桥尚未接入");
                        return;
                    }
                    double px = c.mouseX(), py = c.mouseY();
                    boolean[] dragged = {false};
                    double insetX = (card.w() - entry.width()) / 2 + 12,
                            insetY = (card.h() - entry.height()) / 2 + 12;
                    double density = Math.max(.01, c.host().uiDensity()),
                            localX =
                                    Math.max(
                                            0,
                                            (px - card.x() - insetX)
                                                    * density
                                                    / entry.previewScale()),
                            localY =
                                    Math.max(
                                            0,
                                            (py - card.y() - insetY)
                                                    * density
                                                    / entry.previewScale());
                    c.startDrag(
                            (x, y) -> {
                                if (!dragged[0] && Math.hypot(x - px, y - py) * density > 8) {
                                    dragged[0] = true;
                                    double q = c instanceof HudChromeContext cc ? cc.density : 1;
                                    studio.placeFromLibrary(
                                            c,
                                            entry.prototype(),
                                            x - localX * contentScale(entry.prototype()) / q,
                                            y - localY * contentScale(entry.prototype()) / q,
                                            true,
                                            x,
                                            y);
                                } else if (dragged[0])
                                    studio.designer.updatePointer(
                                            x * (c instanceof HudChromeContext cc ? cc.density : 1),
                                            y * (c instanceof HudChromeContext cc ? cc.density : 1),
                                            c.alt(),
                                            c.shift());
                            },
                            () -> {
                                if (dragged[0]) studio.designer.release();
                                else
                                    studio.placeFromLibrary(
                                            c,
                                            entry.prototype(),
                                            (c.width()
                                                            - entry.prototype().width()
                                                                    / (c
                                                                                    instanceof
                                                                                    HudChromeContext
                                                                                            cc
                                                                            ? cc.density
                                                                            : 1))
                                                    / 2,
                                            (c.height()
                                                            - entry.prototype().height()
                                                                    / (c
                                                                                    instanceof
                                                                                    HudChromeContext
                                                                                            cc
                                                                            ? cc.density
                                                                            : 1))
                                                    / 2,
                                            false,
                                            px,
                                            py);
                            });
                },
                entry.enabled() ? "点击添加；拖动放置" : "需要主项目安装 ADDITIONAL_HUDS 运行回调");
    }

    private static double contentScale(HudElement element) {
        return element instanceof HudNode n ? n.effectiveScale() : element.scale();
    }

    private static boolean animations(PageContext c) {
        return c.preferences().flag("animations", true) && c.preferences().number("animationTime", .6) > 0;
    }

    private void jumpScroll(PageContext c, double value) {
        scroll = scrolling.jump(value, c.time());
        scrollAnimation.cancel(scroll);
        pendingScroll = null;
        modIntent = null;
    }

    double scrollOffset() {
        return scroll;
    }

    boolean scrolling() {
        return scrollAnimation.active();
    }

    void requestSection(String id) {
        studio.libraryOpen = true;
        modIntent = id;
        pendingScroll = id;
    }

    boolean wheel(PageContext c, double delta) {
        if (bounds == null || !bounds.contains(c.mouseX(), c.mouseY())) return false;
        scrollAnimation.cancel(scroll);
        pendingScroll = null;
        scroll = scrolling.wheel(-delta * 36, c.time(), animations(c));
        modIntent = null;
        return true;
    }
}
