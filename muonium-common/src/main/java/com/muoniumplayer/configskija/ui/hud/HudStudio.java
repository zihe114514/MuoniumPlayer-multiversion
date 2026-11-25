package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.host.HudExtensions;
import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.model.HudElement.Anchor;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Full-screen HUD page. Coordinates are host layout pixels, never Shell dp. */
public final class HudStudio
        implements UiPage, com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput {
    public enum Command {
        OPEN_SETTINGS,
        COPY,
        CUT,
        PASTE,
        DELETE,
        SELECT_ALL,
        LOCK,
        TOGGLE_VISIBILITY,
        RESET,
        DUPLICATE,
        UNDO,
        REDO,
        SEARCH,
        HELP
    }

    final ConfigHost host;
    final UiPreferences preferences;
    final HudExtensions extensions;
    final HudDesigner designer;
    final HudRuntime runtime;
    final HudPanel panel;
    final HudLibrary library;
    final HudTour tour;
    boolean panelOpen, designerTab, libraryOpen = true;
    private final HudBindings bindings = new HudBindings();
    private final HudMotion motion = new HudMotion();
    private String lastHint = "";
    private Rect lastHintSlot;
    private final HudChromeContext.Focus focus = new HudChromeContext.Focus();
    private double chromeDensity = 1;
    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
    private final List<HudNode> builtins = new ArrayList<>();
    private volatile List<String> activeSelection = List.of();
    private String hovered, owner;
    private Rect bar, toolbar;
    private volatile boolean entered;
    private boolean initializing = true, autoTourChecked;
    private Anchor pickSelf;
    private Set<String> pickSources = Set.of();
    private List<HudElement> sourceHuds;

    public HudStudio(ConfigHost host, UiPreferences preferences) {
        this(host, preferences, null);
    }

    /** Reuse a game runtime created at startup, so opening a page never creates a second HUD owner. */
    public HudStudio(ConfigHost host, UiPreferences preferences, HudRuntime existing) {
        this.host = Objects.requireNonNull(host);
        this.preferences = Objects.requireNonNull(preferences);
        this.extensions = host instanceof HudExtensions e ? e : null;
        sourceHuds = List.copyOf(host.huds());
        if (existing != null) this.runtime = existing;
        else if (extensions != null) this.runtime = HudRuntime.open(host, preferences);
        else
            this.runtime =
                    new HudRuntime(new HudDesigner(sourceHuds, preferences, host::persist), null);
        designer = runtime.designer();
        panel = new HudPanel(this);
        library = new HudLibrary(this);
        tour = new HudTour(this);
        for (String type : List.of("text", "date", "time", "icon"))
            builtins.add(new HudNode("builtin/" + type, type));
        owner = extensions == null ? "host" : extensions.hudOwner();
        initializing = false;
    }

    public HudDesigner designer() {
        return designer;
    }

    public HudRuntime runtime() {
        return runtime;
    }

    public HudTour tour() {
        return tour;
    }

    /** Global host keybinds may enqueue commands; execution is on the page/client thread. */
    public boolean post(Command command) {
        if (!entered || tour.blocksEditor() || focus.focused()) return false;
        commands.add(Objects.requireNonNull(command));
        return true;
    }

    public List<String> activeSelection() {
        return activeSelection;
    }

    public void select(Collection<String> ids, boolean settings) {
        designer.selection(ids);
        panelOpen = settings && !designer.selected().isEmpty();
        libraryOpen = designer.selected().isEmpty();
    }

    private HudElement wrap(HudElement element) {
        return extensions != null
                        && extensions
                                .hudCapabilities()
                                .contains(HudExtensions.Capability.HOST_SETTINGS)
                ? new HudHostElement(element, () -> extensions.hudSettings(element))
                : element;
    }

    List<ConfigHost.Group> hostGroups(HudElement element) {
        if (extensions == null
                || !extensions.hudCapabilities().contains(HudExtensions.Capability.HOST_SETTINGS))
            return List.of();
        return List.copyOf(
                extensions.hudSettings(element instanceof HudHostElement h ? h.delegate : element));
    }

    boolean canAdd() {
        return extensions != null
                && extensions.hudCapabilities().contains(HudExtensions.Capability.ADDITIONAL_HUDS);
    }

    List<HudElement> providers() {
        LinkedHashMap<String, HudElement> result = new LinkedHashMap<>();
        for (HudElement e : sourceHuds) result.put(e.id(), e);
        if (extensions != null)
            for (HudElement e : extensions.hudProviders())
                if (e.multipleInstancesAllowed()
                        || e.hidden()
                        || designer.byId(e.id()) == null
                        || !e.deletable()) result.putIfAbsent(e.id(), e);
        if (extensions != null)
            for (HudElement provider : extensions.hudProviders())
                designer.registerProvider(provider);
        for (HudNode builtin : builtins) result.putIfAbsent(builtin.id(), builtin);
        return List.copyOf(result.values());
    }

    private void persist() {
        if (initializing) return;
        host.persist();
        if (extensions != null) extensions.saveHudDocument(new HudDocument(designer.snapshot()));
    }

    private void load(HudDocument document) {
        runtime.reload(document);
    }

    static HudRuntime.Frame editorFrame(PageContext c) {
        return c.host() instanceof HudExtensions extensions
                ? extensions.hudFrame().editing()
                : HudRuntime.Frame.editor(c.width(), c.height(), c.time());
    }

    public void render(PageContext c) {
        entered = true;
        bindings.poll(c);
        chromeDensity = Math.max(.01, host.uiDensity());
        designer.viewport(c.width(), c.height());
        if (extensions != null && !Objects.equals(owner, extensions.hudOwner())) {
            owner = extensions.hudOwner();
            initializing = true;
            try {
                load(extensions.loadHudDocument());
            } finally {
                initializing = false;
            }
            clearTransient();
            designer.select(null, false);
            panelOpen = false;
        }
        for (HudElement e : host.huds())
            if (designer.byId(e.id()) == null) designer.register(wrap(e));
        sourceHuds = List.copyOf(host.huds());
        designer.poll(c.time(), owner);
        for (Command command; (command = commands.poll()) != null; ) execute(c, command);
        HudRuntime.Frame frame = editorFrame(c);
        if (Math.abs(frame.width() - c.width()) > .01
                || Math.abs(frame.height() - c.height()) > .01)
            throw new IllegalStateException(
                    "HUD PageContext must use the same layout pixels as HudRuntime.Frame");
        runtime.render(c.painter(), frame);
        updateHover(c);
        drawCanvas(c);
        double
                chrome =
                        c.animate(
                                "hud/chrome-alpha",
                                designer.dragging()
                                        ? preferences.number("hudDragUiOpacity", .60)
                                        : 1,
                                .15,
                                Motion.Easing.FAST_OUT_SLOW_IN),
                side =
                        c.animate(
                                "hud/side-alpha",
                                designer.selected().isEmpty() ? 1 : .45,
                                .15,
                                Motion.Easing.FAST_OUT_SLOW_IN);
        HudChromeContext chromeContext = new HudChromeContext(c, focus);
        focus.tooltip.begin();
        chromeDensity = chromeContext.density;
        drawToolbar(chromeContext, side);
        drawActionBar(chromeContext, chrome);
        library.render(chromeContext, side);
        panel.render(chromeContext, chrome);
        if (!pickSources.isEmpty()) drawAnchorPicker(c);
        drawHint(chromeContext);
        if (!autoTourChecked && c.width() > 0) {
            autoTourChecked = true;
            boolean pair = designer.elements().stream().filter(HudMerge::candidate).count() >= 2;
            if (!preferences.flag("hud.tour.seen", false)
                    && pair
                    && !panelOpen
                    && !designer.dragging()) tour.start(c.time());
        }
        tour.render(chromeContext);
        if (!focus.overlayActive && !tour.blocksEditor()) focus.tooltip.render(chromeContext);
        activeSelection = tour.blocksEditor() ? List.of() : List.copyOf(designer.selected());
    }

    private void updateHover(PageContext c) {
        if (designer.dragging()
                || designer.pendingPress()
                || designer.marqueeActive()
                || !pickSources.isEmpty()
                || tour.blocksEditor()) return;
        double x = c.mouseX(), y = c.mouseY();
        HudElement previous = designer.byId(hovered);
        if (previous != null
                && bar != null
                && actionCorridor(designer.rect(previous), layoutRect(bar), x, y)) return;
        if (inChrome(x, y)) {
            hovered = null;
            return;
        }
        HudElement hit = designer.hit(x, y, true);
        if (hit != null) hovered = hit.id();
        else if (previous == null
                || bar == null
                || !union(designer.rect(previous), layoutRect(bar)).contains(x, y)) hovered = null;
    }

    private void drawCanvas(PageContext c) {
        Painter p = c.painter();
        HudDesigner.Guide g = designer.guide();
        if (g.x() != null) p.line(g.x(), 0, g.x(), c.height(), 1, 0xFFB02F1F);
        if (g.y() != null) p.line(0, g.y(), c.width(), g.y(), 1, 0xFFB02F1F);
        for (HudElement e : designer.elements()) {
            Rect r = designer.rect(e);
            boolean selected = designer.selected().contains(e.id()), hot = e.id().equals(hovered);
            if (designer.locked(e.id())) {
                if (selected || hot) HudWidgets.dashed(p, r, HudWidgets.AMBER, 2, 4);
                continue;
            }
            if (selected) {
                if (designer.dragging()) p.rect(r, 0, Theme.alpha(HudWidgets.BLUE, .10));
                p.outline(r, 0, 1, HudWidgets.BLUE);
                if (designer.selected().size() == 1) {
                    for (var handle : designer.handles(e)) {
                        Rect h = designer.handleRect(e, handle, 7);
                        p.rect(h, 0, 0xFFFFFFFF);
                        p.outline(h, 0, 1, HudWidgets.BLUE);
                    }
                    String size = Math.round(r.w()) + " x " + Math.round(r.h());
                    double w = p.measure(size, 10, false) + 10;
                    Rect badge = new Rect(r.x() + (r.w() - w) / 2, Math.max(0, r.y() - 25), w, 16);
                    p.rect(badge, 3, HudWidgets.BLUE);
                    HudWidgets.text(
                            c,
                            size,
                            new Rect(badge.x() + 5, badge.y(), badge.w() - 10, badge.h()),
                            10,
                            false,
                            0xFFFFFFFF);
                }
            } else if (e.hidden()) {
                if (!(e instanceof HudNode)) p.rect(r, 0, 0x73000000);
                HudWidgets.dashed(p, r, Theme.alpha(0xFFFFFFFF, hot ? .5 : .4), 4, 3);
            } else p.outline(r, 0, 1, Theme.alpha(0xFFFFFFFF, hot ? .5 : .2));
        }
        double alpha =
                c.animate(
                        "hud/marquee",
                        designer.marqueeActive() ? 1 : 0,
                        preferences.flag("marqueeSelectionAnim", true)
                                ? Math.max(
                                        .001,
                                        preferences.number("marqueeSelectionAnimDuration", 150)
                                                / 1000)
                                : .000001,
                        Motion.Easing.FAST_OUT_SLOW_IN);
        Rect marquee = designer.marqueeRect();
        if (alpha > .001 && marquee != null) {
            p.rect(marquee, c.theme().radius(5), Theme.alpha(HudWidgets.BLUE, .15 * alpha));
            p.outline(marquee, c.theme().radius(5), 1, Theme.alpha(HudWidgets.BLUE, alpha));
        }
        HudDesigner.Link link = designer.anchor(hovered);
        HudElement source = designer.byId(hovered),
                parent = link == null ? null : designer.byId(link.parent());
        if (source != null && parent != null) {
            double[] a = anchorPoint(source, link.self()), b = anchorPoint(parent, link.target());
            HudWidgets.dash(p, a[0], a[1], b[0], b[1], 1.5, HudWidgets.BLUE, 4, 4);
            p.rect(new Rect(b[0] - 5, b[1] - 5, 10, 10), 5, HudWidgets.BLUE);
        }
    }

    private void drawToolbar(PageContext c, double alpha) {
        double menuWidth = Math.max(64, c.painter().measure("菜单", 12, false) + 42);
        toolbar = new Rect(16, 16, Math.min(442 + menuWidth - 64, Math.max(1, c.width() - 32)), 38);
        Painter p = c.painter();
        p.layer(toolbar, alpha);
        try {
            HudWidgets.panel(c, toolbar);
            HudWidgets.button(
                    c,
                    "return",
                    new Rect(20, 20, 90, 30),
                    "返回",
                    "left-arrow",
                    false,
                    true,
                    () -> c.navigate(new Navigation.Route(Navigation.Page.SETTINGS)),
                    "");
            HudWidgets.button(
                    c,
                    "undo",
                    new Rect(116, 20, 32, 30),
                    "",
                    "undo",
                    false,
                    designer.history.canUndo(),
                    () -> designer.undo(),
                    HudContextMenu.hint(c, "hudUndoKeybind"));
            HudWidgets.button(
                    c,
                    "redo",
                    new Rect(152, 20, 32, 30),
                    "",
                    "redo",
                    false,
                    designer.history.canRedo(),
                    () -> designer.redo(),
                    HudContextMenu.hint(c, "hudRedoKeybind"));
            HudWidgets.button(
                    c,
                    "library-toggle",
                    new Rect(190, 20, 94, 30),
                    "HUD 库",
                    "hud",
                    libraryOpen,
                    true,
                    () -> {
                        designer.select(null, false);
                        panelOpen = false;
                        libraryOpen = !libraryOpen;
                    },
                    "");
            HudWidgets.button(
                    c,
                    "menu",
                    new Rect(290, 20, menuWidth, 30),
                    "菜单",
                    "dots-grid",
                    false,
                    true,
                    () -> openMenuChrome(c, designer.primaryElement()),
                    "");
            HudWidgets.button(
                    c,
                    "help",
                    new Rect(296 + menuWidth, 20, 76, 30),
                    "引导",
                    "info",
                    false,
                    true,
                    () -> tour.start(c.time()),
                    "");
        } finally {
            p.restore();
        }
        if (!canAdd()
                || extensions == null
                || !extensions.hudCapabilities().contains(HudExtensions.Capability.EXACT_TEXT)) {
            String warning = !canAdd() ? "扩展 HUD 的游戏渲染/存储桥未接入" : "Minecraft/Poppins 精确字体回调未接入";
            Rect r = new Rect(16, 60, Math.min(442, c.width() - 32), 28);
            p.rect(r, 5, 0xCC1A2229);
            HudWidgets.text(c, warning, r.inset(6), 11, false, HudWidgets.AMBER);
        }
    }

    private Rect actionBar(HudElement e, PageContext c) {
        Rect r = chromeRect(designer.rect(e));
        int icons = e.deletable() ? 4 : 3;
        double w = 24 * icons + 8 * (icons - 1) + 8,
                h = 32,
                x = Geometry.clamp(r.x() + (r.w() - w) / 2, 0, Math.max(0, c.width() - w)),
                below = r.bottom() + 8,
                above = r.y() - 8 - h - 25,
                y =
                        below + h <= c.height()
                                ? below
                                : above >= 0
                                        ? above
                                        : Geometry.clamp(
                                                r.bottom() - h, 0, Math.max(0, c.height() - h));
        return new Rect(x, y, w, h);
    }

    private void drawActionBar(PageContext c, double alpha) {
        HudElement e = designer.byId(hovered);
        if (e == null || designer.dragging() || !pickSources.isEmpty() || tour.blocksEditor()) {
            bar = null;
            return;
        }
        bar = actionBar(e, c);
        c.painter().layer(bar, alpha);
        try {
            c.painter().rect(bar, c.theme().buttonRadius(), 0x8C000000);
            c.painter().outline(bar, c.theme().buttonRadius(), 1, 0x1FFFFFFF);
            c.hit("hud:actionbar-block", bar, () -> {}, "");
            String[] icons = {
                "settings",
                e.hidden() ? "eye-off" : "eye",
                designer.locked(e.id()) ? "lock" : "unlock",
                "trash"
            };
            for (int i = 0; i < (e.deletable() ? 4 : 3); i++) {
                int action = i;
                Rect r = new Rect(bar.x() + 4 + i * 32, bar.y() + 4, 24, 24);
                int
                        foreground =
                                i == 3
                                        ? Theme.alpha(HudWidgets.RED, .7)
                                        : i == 2 && designer.locked(e.id())
                                                ? c.accent()
                                                : Theme.alpha(0xFFFFFFFF, .7),
                        hotColor =
                                i == 3
                                        ? HudWidgets.RED
                                        : i == 2 && designer.locked(e.id())
                                                ? c.accent()
                                                : 0xFFFFFFFF;
                HudWidgets.iconButton(
                        c,
                        "action/" + e.id() + "/" + i,
                        r,
                        icons[i],
                        foreground,
                        hotColor,
                        () -> {
                            designer.select(e.id(), false);
                            switch (action) {
                                case 0 -> panelOpen = true;
                                case 1 -> designer.toggleHidden();
                                case 2 -> designer.toggleLocked();
                                case 3 -> designer.deleteSelection();
                            }
                            libraryOpen = false;
                        });
            }
        } finally {
            c.painter().restore();
        }
    }

    private void drawHint(PageContext c) {
        String hint =
                !pickSources.isEmpty()
                        ? (pickSelf == null
                                ? "点击本 HUD 要锚定的点  ·  Esc 取消"
                                : "点击目标 HUD 的点  ·  右键解除锚定  ·  Esc 返回")
                        : designer.locked(hovered)
                                ? "此 HUD 已锁定  ·  点击锁按钮后可移动或缩放"
                                : hovered != null && designer.mergeGroup(hovered).size() > 1
                                        ? "拖动以移动合并组  ·  先选中再拖动可拆出"
                                        : "";
        if (designer.dragging()) hint = "";
        double alpha =
                motion.animate("hint/visible", hint.isEmpty() ? 0 : 1, c.time(), 400, 1, .01);
        if (!hint.isEmpty()) lastHint = hint;
        if (lastHint.isEmpty() || hint.isEmpty() && alpha <= 0) return;
        hint = lastHint;
        double w = Math.min(c.width() - 32, c.painter().measure(hint, 12, false) + 24), h = 28;
        List<Rect> obstacles = new ArrayList<>();
        for (HudElement e : designer.elements()) obstacles.add(chromeRect(designer.rect(e)));
        if (panel.bounds() != null) obstacles.add(panel.bounds());
        if (library.bounds() != null) obstacles.add(library.bounds());
        if (library.icons() != null) obstacles.add(library.icons());
        if (bar != null) obstacles.add(bar);
        Rect slot = hintSlot(c.width(), c.height(), w, h, obstacles);
        double x = c.animate("hud/hint-x", slot.x(), .16, Motion.Easing.FAST_OUT_SLOW_IN),
                y = c.animate("hud/hint-y", slot.y(), .16, Motion.Easing.FAST_OUT_SLOW_IN);
        Rect r = new Rect(x, y, w, h);
        c.painter().layer(r, alpha);
        try {
            c.painter().rect(r, c.theme().backgroundRadius(), c.theme().popup());
            c.painter().outline(r, c.theme().backgroundRadius(), 1, c.theme().border());
            HudWidgets.text(
                    c, hint, new Rect(x + 12, y, w - 24, h), 12, false, c.theme().secondary());
        } finally {
            c.painter().restore();
        }
    }

    private boolean bindingsAllowed(PageContext c) {
        return entered
                && !c.textFocused()
                && !focus.focused()
                && !focus.overlayActive
                && !tour.blocksEditor();
    }

    public boolean rawKey(PageContext c, String token, boolean pressed) {
        return bindings.key(
                c, token, pressed, bindingsAllowed(c), command -> dispatchBinding(c, command));
    }

    public boolean rawMouse(PageContext c, int button, boolean pressed) {
        return bindings.mouse(
                c, button, pressed, bindingsAllowed(c), command -> dispatchBinding(c, command));
    }

    public boolean recording() {
        return false;
    }

    private void dispatchBinding(PageContext c, Command command) {
        if (command == Command.OPEN_SETTINGS
                && (designer.selected().size() != 1 || designer.locked(designer.primary()))) return;
        if (command == Command.DUPLICATE && designer.selected().stream().anyMatch(designer::locked))
            return;
        if ((command == Command.COPY
                        || command == Command.CUT
                        || command == Command.DELETE
                        || command == Command.LOCK
                        || command == Command.TOGGLE_VISIBILITY
                        || command == Command.RESET)
                && designer.selected().isEmpty()) return;
        execute(c, command);
    }

    public boolean pointerDown(PageContext c, int button) {
        if (rawMouse(c, button, true)) return true;
        if (focus.overlayActive) return true;
        if (tour.blocksEditor()
                || tour.containsCard(c.mouseX() / chromeDensity, c.mouseY() / chromeDensity))
            return true;
        if (!pickSources.isEmpty()) {
            anchorClick(c, button);
            return true;
        }
        if (button == 1 && panel.rightClick(new HudChromeContext(c, focus))) return true;
        if (inChrome(c.mouseX(), c.mouseY())) return true;
        if (button == 1) {
            HudElement e = designer.primaryElement();
            if (e == null || !designer.rect(e).contains(c.mouseX(), c.mouseY()))
                e = designer.hit(c.mouseX(), c.mouseY(), true);
            if (e != null && !designer.selected().contains(e.id())) designer.select(e.id(), false);
            openMenu(c, c.mouseX(), c.mouseY(), e);
            return true;
        }
        if (button != 0) return false;
        focus.blur();
        panel.search.focused = false;
        library.search.focused = false;
        designer.press(c.mouseX(), c.mouseY(), bindings.actionModifier(c), c.shift());
        libraryOpen = designer.selected().isEmpty();
        c.startDrag(
                (x, y) -> designer.updatePointer(x, y, c.alt(), c.shift()),
                () -> designer.release());
        return true;
    }

    public void pointerUp(PageContext c, int button) {
        rawMouse(c, button, false);
        if (button == 0) designer.release();
    }

    public boolean wheel(PageContext c, double delta) {
        if (tour.blocksEditor()) return true;
        HudChromeContext cc = new HudChromeContext(c, focus);
        if (panel.wheel(cc, delta) || library.wheel(cc, delta)) return true;
        if (c.control() && !designer.selected().isEmpty())
            designer.scaleSelection(Math.pow(1.04, delta));
        return true;
    }

    public boolean key(
            PageContext c,
            ConfigView.Key key,
            String typed,
            boolean control,
            boolean shift,
            boolean alt) {
        if (c.textFocused()) {
            focus.blur();
            return false;
        }
        if (focus.key(c, key, typed, control, shift)) return true;
        if (tour.active()) {
            if (key == ConfigView.Key.ESCAPE) {
                tour.end();
                return true;
            }
            if (tour.blocksEditor()) {
                if (key == ConfigView.Key.RIGHT || key == ConfigView.Key.ENTER) tour.move(1);
                else if (key == ConfigView.Key.LEFT) tour.move(-1);
                return true;
            }
        }
        if (bindings.fallback(
                c, key, typed, control, shift, alt, command -> dispatchBinding(c, command)))
            return true;
        if (key == ConfigView.Key.ESCAPE) {
            if (!pickSources.isEmpty()) {
                if (pickSelf != null) pickSelf = null;
                else pickSources = Set.of();
                return true;
            }
            if (designer.dragging() || designer.pendingPress() || designer.marqueeActive()) {
                designer.cancel();
                return true;
            }
            if (!designer.selected().isEmpty() || panelOpen) {
                designer.select(null, false);
                panelOpen = false;
                panel.closed();
                libraryOpen = true;
                return true;
            }
            c.navigate(new Navigation.Route(Navigation.Page.SETTINGS));
            return true;
        }
        double step = shift ? 10 : 1;
        switch (key) {
            case LEFT -> designer.moveBy(-step, 0);
            case RIGHT -> designer.moveBy(step, 0);
            case UP -> designer.moveBy(0, -step);
            case DOWN -> designer.moveBy(0, step);
            case ENTER -> {
                if (designer.primaryElement() != null) panelOpen = true;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void execute(PageContext c, Command command) {
        if (tour.blocksEditor()) return;
        switch (command) {
            case OPEN_SETTINGS -> {
                if (designer.primaryElement() != null) {
                    panelOpen = true;
                    designerTab = false;
                    libraryOpen = false;
                }
            }
            case COPY -> designer.copy();
            case CUT -> designer.cut();
            case PASTE -> {
                if (designer.canPaste()) designer.paste(c.mouseX(), c.mouseY());
                else c.toast("所复制 HUD 不允许创建多个实例");
            }
            case DUPLICATE -> designer.duplicateSelection();
            case DELETE -> designer.deleteSelection();
            case SELECT_ALL -> designer.selectAll();
            case LOCK -> designer.toggleLocked();
            case TOGGLE_VISIBILITY -> designer.toggleHidden();
            case RESET -> designer.resetSelection(false);
            case UNDO -> designer.undo();
            case REDO -> designer.redo();
            case HELP -> tour.start(c.time());
            case SEARCH -> {
                if (designer.primaryElement() != null) {
                    panelOpen = true;
                    focus.focus(panel.search, v -> {});
                    panel.search.selectAll();
                } else {
                    libraryOpen = true;
                    focus.focus(library.search, v -> {});
                    library.search.selectAll();
                }
            }
        }
    }

    void clearTransient() {
        focus.blur();
        focus.tooltip.clear();
        pickSources = Set.of();
        pickSelf = null;
        hovered = null;
        bar = null;
    }

    void beginAnchor() {
        pickSources = designer.selected();
        pickSelf = pickSources.size() == 1 ? null : Anchor.AUTO;
        hovered = null;
        libraryOpen = false;
    }

    private void anchorClick(PageContext c, int button) {
        if (button == 1) {
            designer.selection(pickSources);
            designer.clearAnchors();
            clearTransient();
            return;
        }
        if (button != 0) return;
        if (pickSelf == null) {
            HudElement e = designer.byId(pickSources.iterator().next());
            Anchor p = hitAnchor(e, c.mouseX(), c.mouseY());
            if (p == null) clearTransient();
            else pickSelf = p;
            return;
        }
        for (HudElement target : designer.elements())
            if (designer.canAnchor(pickSources, target.id())) {
                Anchor p = hitAnchor(target, c.mouseX(), c.mouseY());
                if (p != null) {
                    designer.selection(pickSources);
                    designer.anchorSelection(target.id(), p, pickSelf);
                    clearTransient();
                    return;
                }
            }
        clearTransient();
    }

    private Anchor hitAnchor(HudElement e, double x, double y) {
        if (e == null) return null;
        Anchor best = null;
        double distance = 10;
        for (Anchor point : Anchor.values())
            if (point != Anchor.AUTO) {
                double[] p = anchorPoint(e, point);
                double d = Math.hypot(x - p[0], y - p[1]);
                if (d < distance) {
                    distance = d;
                    best = point;
                }
            }
        return best;
    }

    private double[] anchorPoint(HudElement e, Anchor point) {
        Rect r = designer.rect(e);
        Anchor a = point == Anchor.AUTO ? e.growthAnchor() : point;
        return new double[] {r.x() + a.fx * r.w(), r.y() + a.fy * r.h()};
    }

    private void drawAnchorPicker(PageContext c) {
        c.painter().rect(new Rect(0, 0, c.width(), c.height()), 0, 0x40000000);
        List<HudElement> targets =
                pickSelf == null
                        ? List.of(designer.byId(pickSources.iterator().next()))
                        : designer.elements().stream()
                                .filter(e -> designer.canAnchor(pickSources, e.id()))
                                .toList();
        for (HudElement e : targets) {
            if (e == null) continue;
            c.painter().outline(designer.rect(e), 0, 1, Theme.alpha(HudWidgets.BLUE, .5));
            Anchor hot = hitAnchor(e, c.mouseX(), c.mouseY());
            for (Anchor a : Anchor.values())
                if (a != Anchor.AUTO) {
                    double[] p = anchorPoint(e, a);
                    double radius = a == hot ? 7 : 5;
                    c.painter()
                            .rect(
                                    new Rect(p[0] - radius, p[1] - radius, radius * 2, radius * 2),
                                    radius,
                                    0xFFFFFFFF);
                    c.painter()
                            .rect(
                                    new Rect(
                                            p[0] - radius + 2,
                                            p[1] - radius + 2,
                                            (radius - 2) * 2,
                                            (radius - 2) * 2),
                                    radius - 2,
                                    a == hot ? c.accent() : HudWidgets.BLUE);
                }
        }
        if (pickSelf != null && pickSources.size() == 1) {
            HudElement source = designer.byId(pickSources.iterator().next());
            if (source != null) {
                double[] p = anchorPoint(source, pickSelf);
                c.painter().rect(new Rect(p[0] - 7, p[1] - 7, 14, 14), 7, c.accent());
            }
        }
    }

    void placeFromLibrary(
            PageContext c,
            HudElement prototype,
            double x,
            double y,
            boolean drag,
            double pointerX,
            double pointerY) {
        HudElement element = designer.byId(prototype.id());
        if (prototype.multipleInstancesAllowed()) {
            if (!canAdd()) throw new IllegalStateException("ADDITIONAL_HUDS callback missing");
            element = prototype.duplicate("huds/" + UUID.randomUUID());
        } else if (element == null) element = wrap(prototype);
        double q = c instanceof HudChromeContext cc ? cc.density : 1;
        x *= q;
        y *= q;
        pointerX *= q;
        pointerY *= q;
        HudElement placed = element;
        if (drag) designer.addAndBegin(placed, x, y, pointerX, pointerY);
        else designer.add(placed, x, y);
        panelOpen = !drag;
        libraryOpen = false;
        hovered = placed.id();
    }

    Rect chromeRect(Rect r) {
        return new Rect(
                r.x() / chromeDensity,
                r.y() / chromeDensity,
                r.w() / chromeDensity,
                r.h() / chromeDensity);
    }

    private Rect layoutRect(Rect r) {
        return new Rect(
                r.x() * chromeDensity,
                r.y() * chromeDensity,
                r.w() * chromeDensity,
                r.h() * chromeDensity);
    }

    private boolean inChrome(double x, double y) {
        x /= chromeDensity;
        y /= chromeDensity;
        return toolbar != null && toolbar.contains(x, y)
                || panel.bounds() != null && panel.bounds().contains(x, y)
                || library.bounds() != null && library.bounds().contains(x, y)
                || library.icons() != null && library.icons().contains(x, y)
                || bar != null && bar.contains(x, y);
    }

    public void leave(PageContext c) {
        if (designer.dragging()) designer.end();
        else if (designer.pendingPress() || designer.marqueeActive()) designer.cancel();
        tour.end();
        commands.clear();
        activeSelection = List.of();
        entered = false;
        bindings.clear();
        clearTransient();
        persist();
    }

    private void openMenuChrome(PageContext c, HudElement target) {
        c.overlay(new HudContextMenu(this, c.mouseX(), c.mouseY(), target));
    }

    private void openMenu(PageContext c, double x, double y, HudElement target) {
        HudChromeContext cc = new HudChromeContext(c, focus);
        cc.overlay(new HudContextMenu(this, x / cc.density, y / cc.density, target));
    }

    static double overlap(Rect a, Rect b) {
        return Math.max(0, Math.min(a.right(), b.right()) - Math.max(a.x(), b.x()))
                * Math.max(0, Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y()));
    }

    static Rect union(Rect a, Rect b) {
        double x = Math.min(a.x(), b.x()), y = Math.min(a.y(), b.y());
        return new Rect(
                x, y, Math.max(a.right(), b.right()) - x, Math.max(a.bottom(), b.bottom()) - y);
    }

    private static boolean actionCorridor(Rect hud, Rect bar, double x, double y) {
        if (bar.contains(x, y)) return true;
        if (bar.y() >= hud.bottom() && y >= hud.bottom() && y <= bar.y()
                || bar.bottom() <= hud.y() && y >= bar.bottom() && y <= hud.y())
            return x >= Math.min(hud.x(), bar.x()) && x <= Math.max(hud.right(), bar.right());
        return false;
    }

    static Rect hintSlot(double width, double height, double w, double h, List<Rect> obstacles) {
        List<Rect> slots =
                List.of(
                        new Rect((width - w) / 2, height - h - 16, w, h),
                        new Rect((width - w) / 2, 16, w, h),
                        new Rect(16, height - h - 16, w, h),
                        new Rect(width - w - 16, height - h - 16, w, h),
                        new Rect(16, 16, w, h),
                        new Rect(width - w - 16, 16, w, h));
        Rect best = slots.get(0);
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < slots.size(); i++) {
            double score = i;
            for (Rect r : obstacles) score += overlap(slots.get(i), r);
            if (score < bestScore) {
                bestScore = score;
                best = slots.get(i);
            }
        }
        return best;
    }
}
