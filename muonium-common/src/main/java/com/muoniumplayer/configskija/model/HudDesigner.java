package com.muoniumplayer.configskija.model;

import com.muoniumplayer.configskija.model.HudElement.Anchor;
import com.muoniumplayer.configskija.ui.Geometry;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.hud.HudMerge;
import com.muoniumplayer.configskija.ui.hud.HudNode;

import java.util.*;

/** Pixel-space editor state machine. Host dimensions are reread after every write. Java 17 only. */
public final class HudDesigner {
    public enum Align {
        LEFT,
        CENTER_X,
        RIGHT,
        TOP,
        CENTER_Y,
        BOTTOM
    }

    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public enum Handle {
        TOP_LEFT(-1, -1),
        TOP(0, -1),
        TOP_RIGHT(1, -1),
        RIGHT(1, 0),
        BOTTOM_RIGHT(1, 1),
        BOTTOM(0, 1),
        BOTTOM_LEFT(-1, 1),
        LEFT(-1, 0);
        public final int dx, dy;

        Handle(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        public Anchor opposite() {
            return Anchor.at(1 - dx, 1 - dy);
        }
    }

    /** Offset units belong to the child: GUI units for HudNode, layout units for intrinsic host HUDs. */
    public record Link(String parent, Anchor target, Anchor self, double offsetX, double offsetY) {
        public Link {
            Objects.requireNonNull(parent);
            Objects.requireNonNull(target);
            Objects.requireNonNull(self);
        }
    }

    public record State(
            String id,
            double x,
            double y,
            double width,
            double height,
            double scale,
            boolean hidden,
            boolean locked,
            String type,
            Map<String, String> properties,
            Link anchor,
            int order) {
        public State {
            properties = Map.copyOf(properties);
        }

        public State(
                String id,
                double x,
                double y,
                double width,
                double height,
                double scale,
                boolean hidden,
                boolean locked) {
            this(id, x, y, width, height, scale, hidden, locked, "host", Map.of(), null, 0);
        }
    }

    public record Guide(Double x, Double y) {
        public static final Guide NONE = new Guide(null, null);
    }

    private record Press(String id, double x, double y, boolean wasSelected, boolean narrow) {}

    private final ArrayList<HudElement> elements = new ArrayList<>();
    private final Map<String, HudElement> known = new LinkedHashMap<>();
    private final Map<String, State> defaults = new LinkedHashMap<>();
    private final Map<String, Link> anchors = new LinkedHashMap<>();
    private final UiPreferences preferences;
    private final Runnable persist;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    public final History<List<State>> history;
    private final LinkedHashMap<String, Rect> start = new LinkedHashMap<>();
    private final Map<String, State> startStates = new LinkedHashMap<>();
    private double width = 1, height = 1, pressX, pressY;
    private double guiScale = 1, physicalPerUnit = 1;
    private Handle handle;
    private String resizing, dragged;
    private Guide guide = Guide.NONE;
    private boolean moving, wholeMerge, addingGesture;
    private Press pending;
    private Rect marquee;
    private double marqueeX, marqueeY;
    private Set<String> marqueeBase = Set.of();
    private boolean marqueeActive;
    private Set<String> mergeExclusions = Set.of();
    private final List<State> clipboard = new ArrayList<>();
    private List<State> recorded, settling, historyOverride;
    private double nextPoll;
    private String owner = "";

    public HudDesigner(List<HudElement> elements, UiPreferences preferences, Runnable persist) {
        this.preferences = Objects.requireNonNull(preferences);
        this.persist = Objects.requireNonNull(persist);
        for (HudElement e : elements) register(e);
        history = new History<>(100, this::captureHistory, this::restore);
        recorded = snapshot();
    }

    public void viewport(double width, double height) {
        this.width = Math.max(1, finite(width));
        this.height = Math.max(1, finite(height));
    }

    /** Apply frame units before loading/restoring nodes, so canonical GUI positions never use a stale axis. */
    public void viewport(double width, double height, double guiScale, double physicalPerUnit) {
        if (!Double.isFinite(guiScale)
                || guiScale <= 0
                || !Double.isFinite(physicalPerUnit)
                || physicalPerUnit <= 0
                || !Double.isFinite(guiScale / physicalPerUnit))
            throw new IllegalArgumentException("Invalid HUD frame scale");
        this.guiScale = guiScale;
        this.physicalPerUnit = physicalPerUnit;
        viewport(width, height);
        for (HudElement e : known.values()) applyFrame(e);
    }

    private void applyFrame(HudElement e) {
        if (e instanceof HudNode n) n.frame(guiScale, physicalPerUnit);
    }

    public double viewportWidth() {
        return width;
    }

    public double viewportHeight() {
        return height;
    }

    public List<HudElement> elements() {
        return List.copyOf(elements);
    }

    public Set<String> selected() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(selected));
    }

    public String primary() {
        String last = null;
        for (String id : selected) last = id;
        return last;
    }

    public HudElement primaryElement() {
        return byId(primary());
    }

    public Guide guide() {
        return guide;
    }

    public Rect rect(HudElement e) {
        return e.bounds(width, height);
    }

    public boolean locked(String id) {
        return preferences.locked(id);
    }

    public Link anchor(String id) {
        return anchors.get(id);
    }

    public HudElement byId(String id) {
        for (HudElement e : elements) if (e.id().equals(id)) return e;
        return null;
    }

    public void registerProvider(HudElement e) {
        applyFrame(e);
        known.putIfAbsent(e.id(), e);
    }

    public void register(HudElement e) {
        if (byId(e.id()) != null) return;
        elements.add(Objects.requireNonNull(e));
        applyFrame(e);
        known.put(e.id(), e);
        defaults.putIfAbsent(e.id(), state(e, elements.size() - 1));
    }

    /** Containment order is the original stable depth ordering, with user order as its tie breaker. */
    public List<HudElement> orderedElements() {
        Map<String, Integer> depth = new HashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            Rect inner = rect(elements.get(i));
            int d = 0;
            for (int j = 0; j < elements.size(); j++)
                if (i != j) {
                    Rect outer = rect(elements.get(j));
                    if (encloses(outer, inner) && (!encloses(inner, outer) || j < i)) d++;
                }
            depth.put(elements.get(i).id(), d);
        }
        return elements.stream().sorted(Comparator.comparingInt(e -> depth.get(e.id()))).toList();
    }

    public HudElement hit(double x, double y, boolean includeHidden) {
        return pick(x, y, includeHidden, null, false);
    }

    public HudElement pick(
            double x, double y, boolean includeHidden, String current, boolean cycle) {
        List<HudElement> stack =
                orderedElements().stream()
                        .filter(e -> (includeHidden || !e.hidden()) && rect(e).contains(x, y))
                        .toList();
        List<HudElement> unlocked = stack.stream().filter(e -> !locked(e.id())).toList();
        if (!unlocked.isEmpty()) stack = unlocked;
        if (stack.isEmpty()) return null;
        if (cycle && current != null)
            for (int i = 0; i < stack.size(); i++)
                if (stack.get(i).id().equals(current))
                    return stack.get((i - 1 + stack.size()) % stack.size());
        return stack.get(stack.size() - 1);
    }

    public void select(String id, boolean toggle) {
        if (!toggle) selected.clear();
        if (id != null && byId(id) != null) {
            if (toggle && selected.contains(id)) selected.remove(id);
            else selected.add(id);
        }
    }

    public void selection(Collection<String> ids) {
        selected.clear();
        for (String id : ids) if (byId(id) != null) selected.add(id);
    }

    public void selectAll() {
        selected.clear();
        for (HudElement e : elements) if (!locked(e.id())) selected.add(e.id());
    }

    public void marquee(Rect area, boolean append) {
        if (!append) selected.clear();
        for (HudElement e : elements)
            if (!locked(e.id()) && area.intersects(rect(e))) selected.add(e.id());
    }

    public void beginMarquee(double x, double y, boolean append) {
        marqueeBase = append ? new LinkedHashSet<>(selected) : Set.of();
        marqueeX = x;
        marqueeY = y;
        marquee = new Rect(x, y, 0, 0);
        marqueeActive = true;
        if (!append) selected.clear();
    }

    public void updateMarquee(double x, double y) {
        if (!marqueeActive) return;
        marquee =
                new Rect(
                        Math.min(x, marqueeX),
                        Math.min(y, marqueeY),
                        Math.abs(x - marqueeX),
                        Math.abs(y - marqueeY));
        selection(marqueeBase);
        marquee(marquee, true);
    }

    public Rect marqueeRect() {
        return marquee;
    }

    public boolean marqueeActive() {
        return marqueeActive;
    }

    public List<Handle> handles(HudElement e) {
        if (e.resizeAxes() == HudElement.ResizeAxes.NONE) return List.of();
        if (e.resizeAxes() == HudElement.ResizeAxes.WIDTH)
            return List.of(
                    Handle.TOP_LEFT,
                    Handle.TOP_RIGHT,
                    Handle.LEFT,
                    Handle.RIGHT,
                    Handle.BOTTOM_LEFT,
                    Handle.BOTTOM_RIGHT);
        return List.of(Handle.values());
    }

    public Rect handleRect(HudElement e, Handle h, double size) {
        Rect r = rect(e);
        return new Rect(
                r.x() + r.w() * (h.dx + 1) / 2 - size / 2,
                r.y() + r.h() * (h.dy + 1) / 2 - size / 2,
                size,
                size);
    }

    public Handle handleAt(double x, double y) {
        HudElement e = primaryElement();
        if (selected.size() != 1 || e == null || locked(e.id())) return null;
        for (Handle h : handles(e)) if (handleRect(e, h, 14).contains(x, y)) return h;
        return null;
    }

    public Corner cornerAt(double x, double y) {
        Handle h = handleAt(x, y);
        if (h == null) return null;
        return switch (h) {
            case TOP_LEFT -> Corner.TOP_LEFT;
            case TOP_RIGHT -> Corner.TOP_RIGHT;
            case BOTTOM_LEFT -> Corner.BOTTOM_LEFT;
            case BOTTOM_RIGHT -> Corner.BOTTOM_RIGHT;
            default -> null;
        };
    }

    public boolean begin(double x, double y, Corner corner) {
        return beginHandle(x, y, corner == null ? null : Handle.valueOf(corner.name()));
    }

    public boolean beginHandle(double x, double y, Handle handle) {
        return beginSet(x, y, handle, new LinkedHashSet<>(selected), primary());
    }

    private boolean beginSet(double x, double y, Handle handle, Set<String> ids, String primary) {
        if (moving) end();
        if (history.active() && !addingGesture) history.cancel();
        start.clear();
        startStates.clear();
        for (String id : ids) {
            HudElement e = byId(id);
            if (e != null && !locked(id)) {
                start.put(id, rect(e));
                startStates.put(id, state(e, elements.indexOf(e)));
            }
        }
        if (start.isEmpty()) return false;
        this.pressX = x;
        this.pressY = y;
        this.handle = handle;
        dragged = primary;
        resizing = handle == null ? null : primary;
        if (resizing != null && !start.containsKey(resizing)) return false;
        if (!history.active()) history.begin(handle == null ? "移动 HUD" : "调整 HUD 大小");
        moving = true;
        for (String id : start.keySet()) byId(id).editorDragStarted();
        return true;
    }

    public boolean press(double x, double y, boolean control, boolean shift) {
        Handle h = handleAt(x, y);
        if (h != null) return beginHandle(x, y, h);
        HudElement e = pick(x, y, true, primary(), true);
        if (e == null) {
            pending = null;
            beginMarquee(x, y, control || shift);
            return true;
        }
        boolean was = selected.contains(e.id());
        boolean narrow = was && !control && !shift && selected.size() > 1;
        if (locked(e.id())) {
            if (!control) selected.clear();
            selected.add(e.id());
            return true;
        }
        if (control) select(e.id(), true);
        else if (!was) {
            if (!shift) selected.clear();
            selected.add(e.id());
        }
        pending = new Press(e.id(), x, y, was, narrow);
        return true;
    }

    public void updatePointer(double x, double y, boolean alt, boolean shift) {
        if (marqueeActive) {
            updateMarquee(x, y);
            return;
        }
        Press p = pending;
        if (p != null && !moving) {
            if (!selected.contains(p.id())
                    || locked(p.id())
                    || Math.hypot(x - p.x(), y - p.y()) <= 3) return;
            Set<String> ids = new LinkedHashSet<>(selected);
            if (!p.wasSelected()) ids.addAll(mergeGroup(p.id()));
            wholeMerge = ids.size() > selected.size();
            mergeExclusions = p.wasSelected() ? Set.copyOf(ids) : Set.of();
            pending = null;
            beginSet(p.x(), p.y(), null, ids, p.id());
        }
        drag(x, y, alt, shift);
    }

    public boolean pendingPress() {
        return pending != null;
    }

    public void release() {
        if (marqueeActive) {
            marqueeActive = false;
            return;
        }
        if (pending != null) {
            if (pending.narrow()) select(pending.id(), false);
            pending = null;
            return;
        }
        if (moving) end();
    }

    public void drag(double x, double y, boolean bypassSnap) {
        drag(x, y, bypassSnap, false);
    }

    public void drag(double x, double y, boolean bypassSnap, boolean freeResize) {
        if (!moving) return;
        double dx = finite(x) - pressX, dy = finite(y) - pressY;
        if (handle != null) {
            resize(dx, dy, freeResize);
            resolveAnchors();
            return;
        }
        if (Math.hypot(dx, dy) <= 3 && sameStart()) return;
        Rect primary = start.get(dragged);
        if (primary == null) primary = start.values().iterator().next();
        double nx = primary.x() + dx, ny = primary.y() + dy;
        guide = Guide.NONE;
        if (!bypassSnap) {
            List<Double> xs = new ArrayList<>(List.of(0d, width / 2, width)),
                    ys = new ArrayList<>(List.of(0d, height / 2, height));
            for (HudElement e : elements)
                if (!start.containsKey(e.id())) {
                    Rect r = rect(e);
                    xs.addAll(List.of(r.x(), r.x() + r.w() / 2, r.right()));
                    ys.addAll(List.of(r.y(), r.y() + r.h() / 2, r.bottom()));
                }
            double[] sx = snap(nx, primary.w(), xs, 6), sy = snap(ny, primary.h(), ys, 6);
            nx = sx[0];
            ny = sy[0];
            guide =
                    new Guide(
                            Double.isNaN(sx[1]) ? null : sx[1], Double.isNaN(sy[1]) ? null : sy[1]);
        }
        dx = nx - primary.x();
        dy = ny - primary.y();
        double minDx = Double.NEGATIVE_INFINITY,
                maxDx = Double.POSITIVE_INFINITY,
                minDy = minDx,
                maxDy = maxDx;
        for (var item : start.entrySet())
            if (!(byId(item.getKey()) instanceof HudNode)) {
                Rect r = item.getValue();
                minDx = Math.max(minDx, -r.x());
                maxDx = Math.min(maxDx, Math.max(0, width - r.w()) - r.x());
                minDy = Math.max(minDy, -r.y());
                maxDy = Math.min(maxDy, Math.max(0, height - r.h()) - r.y());
            }
        if (minDx <= maxDx) dx = Geometry.clamp(dx, minDx, maxDx);
        if (minDy <= maxDy) dy = Geometry.clamp(dy, minDy, maxDy);
        Map<String, Rect> targets = new LinkedHashMap<>();
        for (var item : start.entrySet())
            targets.put(
                    item.getKey(),
                    new Rect(
                            item.getValue().x() + dx,
                            item.getValue().y() + dy,
                            item.getValue().w(),
                            item.getValue().h()));
        placeTargets(targets);
    }

    private boolean sameStart() {
        for (var item : start.entrySet())
            if (!rect(byId(item.getKey())).equals(item.getValue())) return false;
        return true;
    }

    private void resize(double dx, double dy, boolean freeResize) {
        HudElement e = byId(resizing);
        Rect r = start.get(resizing);
        State s = startStates.get(resizing);
        if (e == null || r == null) return;
        double w = Math.max(1, r.w() + handle.dx * dx), h = Math.max(1, r.h() + handle.dy * dy);
        if (e.resizeAxes() == HudElement.ResizeAxes.WIDTH) {
            e.size(Math.max(e.minWidth(), w), r.h());
        } else if (e.resizeMode() == HudElement.ResizeMode.SIZE && !e.aspectLocked()
                || freeResize && e.resizeMode() != HudElement.ResizeMode.SCALE) {
            e.size(
                    Math.max(e.minWidth(), handle.dx == 0 ? r.w() : w),
                    Math.max(e.minHeight(), handle.dy == 0 ? r.h() : h));
        } else {
            double vx = handle.dx == 0 ? 0 : handle.dx * r.w(),
                    vy = handle.dy == 0 ? 0 : handle.dy * r.h();
            double mx = vx + dx, my = vy + dy, den = vx * vx + vy * vy;
            double factor = den > 0 ? (mx * vx + my * vy) / den : 1;
            if (e instanceof HudNode n && e.resizeMode() == HudElement.ResizeMode.CONTENT) {
                double text = Double.parseDouble(s.properties().getOrDefault("textScale", "1")),
                        next = Geometry.clamp(text * factor, 6d / 14, 64d / 14),
                        actual = next / Math.max(.001, text);
                n.set("textScale", next);
                n.measured(
                        (r.w() / n.effectiveScale() - n.number("padLeft") - n.number("padRight"))
                                        * actual
                                + n.number("padLeft")
                                + n.number("padRight"),
                        (r.h() / n.effectiveScale() - n.number("padTop") - n.number("padBottom"))
                                        * actual
                                + n.number("padTop")
                                + n.number("padBottom"));
                if (Boolean.parseBoolean(s.properties().getOrDefault("staticWidth", "false"))) {
                    n.set("staticW", Double.parseDouble(s.properties().get("staticW")) * actual);
                    n.set("staticH", Double.parseDouble(s.properties().get("staticH")) * actual);
                }
            } else if (e.supportsScale()) e.scale(Geometry.clamp(s.scale() * factor, .25, 4));
        }
        double px =
                handle.dx < 0
                        ? r.right() - e.width()
                        : handle.dx == 0 ? r.x() + (r.w() - e.width()) / 2 : r.x();
        double py =
                handle.dy < 0
                        ? r.bottom() - e.height()
                        : handle.dy == 0 ? r.y() + (r.h() - e.height()) / 2 : r.y();
        if (e.resizeAxes() == HudElement.ResizeAxes.WIDTH) py = r.y();
        position(e, px, py);
        guide = Guide.NONE;
    }

    public void end() {
        if (!moving) return;
        moving = false;
        for (String id : start.keySet()) {
            HudElement e = byId(id);
            if (e != null) e.editorDragEnded();
        }
        if (wholeMerge && dragged != null) select(dragged, false);
        else if (handle == null) selection(start.keySet());
        start.clear();
        startStates.clear();
        mergeExclusions = Set.of();
        wholeMerge = false;
        addingGesture = false;
        handle = null;
        resizing = null;
        guide = Guide.NONE;
        if (history.commit()) saved();
    }

    public void cancel() {
        pending = null;
        marqueeActive = false;
        moving = false;
        for (String id : start.keySet()) {
            HudElement e = byId(id);
            if (e != null) e.editorDragEnded();
        }
        start.clear();
        startStates.clear();
        mergeExclusions = Set.of();
        guide = Guide.NONE;
        handle = null;
        resizing = null;
        addingGesture = false;
        history.cancel();
    }

    public boolean dragging() {
        return moving;
    }

    public boolean resizing() {
        return moving && handle != null;
    }

    public void moveBy(double dx, double dy) {
        if (selected.stream().anyMatch(this::locked)) return;
        edit(
                "微调 HUD",
                () -> {
                    Map<String, Rect> boxes = new LinkedHashMap<>();
                    for (String id : selected) {
                        HudElement e = byId(id);
                        if (e != null) boxes.put(id, rect(e));
                    }
                    Map<String, Rect> targets = new LinkedHashMap<>();
                    for (var entry : boxes.entrySet()) {
                        Rect r = entry.getValue();
                        targets.put(entry.getKey(), new Rect(r.x() + dx, r.y() + dy, r.w(), r.h()));
                    }
                    placeTargets(targets);
                });
    }

    public void align(Align align) {
        List<Rect> boxes =
                selected.stream().map(this::byId).filter(Objects::nonNull).map(this::rect).toList();
        if (boxes.isEmpty()) return;
        Rect b = boxes.size() == 1 ? new Rect(0, 0, width, height) : bounds(boxes);
        edit(
                "对齐 HUD",
                () -> {
                    Map<String, Rect> targets = new LinkedHashMap<>();
                    for (String id : selected) {
                        HudElement e = byId(id);
                        if (e == null || locked(id)) continue;
                        Rect r = rect(e);
                        double x = r.x(), y = r.y();
                        switch (align) {
                            case LEFT -> x = b.x();
                            case CENTER_X -> x = b.x() + (b.w() - r.w()) / 2;
                            case RIGHT -> x = b.right() - r.w();
                            case TOP -> y = b.y();
                            case CENTER_Y -> y = b.y() + (b.h() - r.h()) / 2;
                            case BOTTOM -> y = b.bottom() - r.h();
                        }
                        targets.put(id, new Rect(x, y, r.w(), r.h()));
                    }
                    for (int pass = 0; pass < 3; pass++) placeTargets(targets);
                });
    }

    public void toggleHidden() {
        edit(
                "显示/隐藏 HUD",
                () -> {
                    boolean hide =
                            selected.stream()
                                    .map(this::byId)
                                    .anyMatch(e -> e != null && !e.hidden());
                    for (String id : selected) {
                        HudElement e = byId(id);
                        if (e != null) e.hidden(hide);
                    }
                });
    }

    public void toggleLocked() {
        edit(
                "锁定 HUD",
                () -> {
                    boolean lock = selected.stream().anyMatch(id -> !locked(id));
                    for (String id : selected) preferences.locked(id, lock);
                });
    }

    public void scaleSelection(double factor) {
        edit(
                "缩放 HUD",
                () -> {
                    for (String id : selected) {
                        HudElement e = byId(id);
                        if (e != null && !locked(id) && e.supportsScale()) {
                            Rect r = rect(e);
                            e.scale(Geometry.clamp(e.scale() * factor, .25, 4));
                            position(e, r.x(), r.y());
                        }
                    }
                    resolveAnchors();
                });
    }

    public void position(HudElement e, double x, double y) {
        if (e == null) return;
        e.absolutePosition(finite(x), finite(y), width, height);
        Link link = anchors.get(e.id());
        HudElement parent = link == null ? null : byId(link.parent());
        if (parent != null) {
            Rect r = rect(e), p = rect(parent);
            Anchor self = resolve(e, link.self());
            anchors.put(
                    e.id(),
                    new Link(
                            link.parent(),
                            link.target(),
                            link.self(),
                            (r.x() + self.fx * r.w() - p.x() - link.target().fx * p.w())
                                    / e.positionUnitScale(),
                            (r.y() + self.fy * r.h() - p.y() - link.target().fy * p.h())
                                    / e.positionUnitScale()));
        }
    }

    private void placeTargets(Map<String, Rect> targets) {
        for (var item : targets.entrySet()) {
            HudElement e = byId(item.getKey());
            if (e != null)
                e.absolutePosition(item.getValue().x(), item.getValue().y(), width, height);
        }
        for (String id : targets.keySet()) {
            HudElement e = byId(id);
            Link a = anchors.get(id);
            HudElement p = a == null ? null : byId(a.parent());
            if (e == null || p == null) continue;
            Rect r = rect(e), pr = rect(p);
            Anchor self = resolve(e, a.self());
            anchors.put(
                    id,
                    new Link(
                            a.parent(),
                            a.target(),
                            a.self(),
                            (r.x() + self.fx * r.w() - pr.x() - a.target().fx * pr.w())
                                    / e.positionUnitScale(),
                            (r.y() + self.fy * r.h() - pr.y() - a.target().fy * pr.h())
                                    / e.positionUnitScale()));
        }
        resolveAnchors();
    }

    public boolean anchorChainContains(String start, String id) {
        Set<String> seen = new HashSet<>();
        String n = start;
        for (int depth = 0; n != null && depth < 16 && seen.add(n); depth++) {
            Link a = anchors.get(n);
            if (a == null) return false;
            if (a.parent().equals(id)) return true;
            n = a.parent();
        }
        return false;
    }

    public boolean canAnchor(Collection<String> ids, String target) {
        return byId(target) != null
                && ids.stream()
                        .noneMatch(id -> id.equals(target) || anchorChainContains(target, id));
    }

    public void anchorSelection(String target, Anchor targetPoint, Anchor self) {
        if (!canAnchor(selected, target))
            throw new IllegalArgumentException("Anchor cycle or missing target");
        edit(
                "锚定 HUD",
                () -> {
                    HudElement p = byId(target);
                    Rect pr = rect(p);
                    for (String id : selected) {
                        HudElement e = byId(id);
                        if (e == null || locked(id)) continue;
                        Rect r = rect(e);
                        Anchor a = resolve(e, self);
                        anchors.put(
                                id,
                                new Link(
                                        target,
                                        targetPoint,
                                        self,
                                        (r.x() + a.fx * r.w() - pr.x() - targetPoint.fx * pr.w())
                                                / e.positionUnitScale(),
                                        (r.y() + a.fy * r.h() - pr.y() - targetPoint.fy * pr.h())
                                                / e.positionUnitScale()));
                    }
                });
    }

    public void clearAnchors() {
        edit(
                "移除锚点",
                () -> {
                    for (String id : selected) anchors.remove(id);
                });
    }

    public void resolveAnchors() {
        Set<String> done = new HashSet<>();
        for (HudElement e : elements) resolveAnchor(e, done, new HashSet<>());
    }

    private void resolveAnchor(HudElement e, Set<String> done, Set<String> visiting) {
        if (done.contains(e.id()) || !visiting.add(e.id())) return;
        Link a = anchors.get(e.id());
        HudElement p = a == null ? null : byId(a.parent());
        if (p != null) {
            resolveAnchor(p, done, visiting);
            Rect r = rect(e), pr = rect(p);
            Anchor self = resolve(e, a.self());
            e.absolutePosition(
                    pr.x()
                            + a.target().fx * pr.w()
                            + a.offsetX() * e.positionUnitScale()
                            - self.fx * r.w(),
                    pr.y()
                            + a.target().fy * pr.h()
                            + a.offsetY() * e.positionUnitScale()
                            - self.fy * r.h(),
                    width,
                    height);
        }
        done.add(e.id());
        visiting.remove(e.id());
    }

    public List<HudMerge.Group> mergeGroups() {
        return HudMerge.groups(elements, this::rect, anchors, mergeExclusions);
    }

    public Set<String> mergeGroup(String id) {
        for (var g : mergeGroups()) if (g.ids().contains(id)) return g.ids();
        return Set.of(id);
    }

    public void reorder(boolean front, boolean oneStep) {
        edit(
                "调整 HUD 层级",
                () -> {
                    if (oneStep) {
                        if (front)
                            for (int i = elements.size() - 2; i >= 0; i--) {
                                if (selected.contains(elements.get(i).id())
                                        && !selected.contains(elements.get(i + 1).id()))
                                    Collections.swap(elements, i, i + 1);
                            }
                        else
                            for (int i = 1; i < elements.size(); i++) {
                                if (selected.contains(elements.get(i).id())
                                        && !selected.contains(elements.get(i - 1).id()))
                                    Collections.swap(elements, i, i - 1);
                            }
                    } else {
                        List<HudElement> picked =
                                elements.stream().filter(e -> selected.contains(e.id())).toList();
                        elements.removeAll(picked);
                        elements.addAll(front ? elements.size() : 0, picked);
                    }
                });
    }

    public void copy() {
        clipboard.clear();
        for (State s : snapshot()) if (selected.contains(s.id())) clipboard.add(s);
    }

    public boolean canPaste() {
        return clipboard.stream()
                .anyMatch(
                        s -> {
                            HudElement e = known.get(s.id());
                            return e != null && e.multipleInstancesAllowed();
                        });
    }

    public void cut() {
        copy();
        deleteSelection();
    }

    public void deleteSelection() {
        edit(
                "删除 HUD",
                () -> {
                    List<String> ids =
                            selected.stream()
                                    .filter(
                                            id -> {
                                                HudElement e = byId(id);
                                                return e != null && e.deletable();
                                            })
                                    .toList();
                    for (String id : ids) {
                        elements.removeIf(e -> e.id().equals(id));
                        anchors.remove(id);
                    }
                    anchors.entrySet().removeIf(a -> ids.contains(a.getValue().parent()));
                    selected.removeAll(ids);
                });
    }

    public List<String> paste(Double x, Double y) {
        return duplicateStates(clipboard, x, y);
    }

    public List<String> duplicateSelection() {
        List<State> source = snapshot().stream().filter(s -> selected.contains(s.id())).toList();
        return duplicateStates(source, null, null);
    }

    private List<String> duplicateStates(List<State> source, Double x, Double y) {
        List<State> viable =
                source.stream()
                        .filter(
                                s -> {
                                    HudElement e = known.get(s.id());
                                    return e != null && e.multipleInstancesAllowed();
                                })
                        .toList();
        if (viable.isEmpty()) return List.of();
        Map<String, Rect> boxes = new LinkedHashMap<>();
        for (State s : viable) {
            HudElement prototype = known.get(s.id());
            HudElement detached = prototype.duplicate("clipboard");
            restoreElement(detached, s);
            boxes.put(s.id(), detached.bounds(width, height));
        }
        Rect b = bounds(new ArrayList<>(boxes.values()));
        double
                tx =
                        x == null
                                ? b.x() + 32
                                : Geometry.clamp(x - b.w() / 2, 0, Math.max(0, width - b.w())),
                ty =
                        y == null
                                ? b.y() + 32
                                : Geometry.clamp(y - b.h() / 2, 0, Math.max(0, height - b.h()));
        List<String> added = new ArrayList<>();
        edit(
                "粘贴 HUD",
                () -> {
                    Map<String, String> mapping = new HashMap<>();
                    for (State s : viable) {
                        String id = "huds/" + UUID.randomUUID();
                        HudElement e = known.get(s.id()).duplicate(id);
                        restoreElement(e, s);
                        register(e);
                        Rect r = boxes.get(s.id());
                        e.absolutePosition(
                                Geometry.clamp(tx + r.x() - b.x(), 0, Math.max(0, width - r.w())),
                                Geometry.clamp(ty + r.y() - b.y(), 0, Math.max(0, height - r.h())),
                                width,
                                height);
                        preferences.locked(id, s.locked());
                        mapping.put(s.id(), id);
                        added.add(id);
                    }
                    for (State s : viable)
                        if (s.anchor() != null && mapping.containsKey(s.anchor().parent())) {
                            Link a = s.anchor();
                            anchors.put(
                                    mapping.get(s.id()),
                                    new Link(
                                            mapping.get(a.parent()),
                                            a.target(),
                                            a.self(),
                                            a.offsetX(),
                                            a.offsetY()));
                        }
                    selection(added);
                });
        return List.copyOf(added);
    }

    public void addAndBegin(HudElement e, double x, double y, double pointerX, double pointerY) {
        if (moving) end();
        flushExternal();
        history.begin("添加并放置 HUD");
        addingGesture = true;
        try {
            register(e);
            e.hidden(false);
            e.absolutePosition(x, y, width, height);
            select(e.id(), false);
            if (!beginHandle(pointerX, pointerY, null)) {
                addingGesture = false;
                history.cancel();
            }
        } catch (RuntimeException ex) {
            addingGesture = false;
            history.cancel();
            throw ex;
        }
    }

    public Map<String, Link> anchors() {
        return Map.copyOf(anchors);
    }

    public void add(HudElement e, double x, double y) {
        edit(
                "添加 HUD",
                () -> {
                    register(e);
                    e.hidden(false);
                    e.absolutePosition(x, y, width, height);
                    select(e.id(), false);
                });
    }

    public void resetSelection(boolean positionOnly) {
        edit(
                "重置 HUD",
                () -> {
                    for (String id : selected) {
                        HudElement e = byId(id);
                        if (e == null || !e.hasResetDefaults()) continue;
                        anchors.remove(id);
                        e.resetToDefaults(positionOnly, width, height);
                        if (!positionOnly) preferences.locked(id, false);
                    }
                });
    }

    public void edit(String title, Runnable change) {
        if (moving) end();
        flushExternal();
        history.begin(title);
        try {
            change.run();
            if (history.commit()) saved();
        } catch (RuntimeException | Error e) {
            history.cancel();
            throw e;
        }
    }

    public void beginEdit(String title) {
        if (moving) end();
        flushExternal();
        if (!history.active()) history.begin(title);
    }

    public void commitEdit() {
        if (history.commit()) saved();
    }

    public void cancelEdit() {
        history.cancel();
        recorded = snapshot();
        settling = null;
    }

    private List<State> captureHistory() {
        if (historyOverride != null) {
            List<State> s = historyOverride;
            historyOverride = null;
            return s;
        }
        return snapshot();
    }

    private void saved() {
        recorded = snapshot();
        settling = null;
        persist.run();
    }

    public List<State> snapshot() {
        List<State> out = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) out.add(state(elements.get(i), i));
        return List.copyOf(out);
    }

    private State state(HudElement e, int order) {
        return new State(
                e.id(),
                e.x(),
                e.y(),
                e.width(),
                e.height(),
                e.scale(),
                e.hidden(),
                locked(e.id()),
                e.type(),
                e.captureProperties(),
                anchors.get(e.id()),
                order);
    }

    public void restore(List<State> states) {
        Set<String> ids = new HashSet<>();
        for (State s : states) ids.add(s.id());
        elements.removeIf(e -> !ids.contains(e.id()) && e.deletable());
        anchors.clear();
        Set<String> revived = new LinkedHashSet<>();
        for (State s : states) {
            HudElement e = byId(s.id());
            if (e == null) {
                e = known.get(s.id());
                if (e == null && !s.type().equals("host")) {
                    HudElement provider =
                            known.values().stream()
                                    .filter(
                                            p ->
                                                    p.multipleInstancesAllowed()
                                                            && p.type().equals(s.type()))
                                    .findFirst()
                                    .orElse(null);
                    if (provider != null) e = provider.duplicate(s.id());
                    else e = new HudNode(s.id(), s.type());
                }
                if (e == null) continue;
                elements.add(e);
                known.put(e.id(), e);
                revived.add(e.id());
            }
            restoreElement(e, s);
            preferences.locked(s.id(), s.locked());
            if (s.anchor() != null) anchors.put(s.id(), s.anchor());
        }
        Map<String, Integer> ranks = new HashMap<>();
        for (State s : states) ranks.put(s.id(), s.order());
        elements.sort(Comparator.comparingInt(e -> ranks.getOrDefault(e.id(), Integer.MAX_VALUE)));
        selected.removeIf(id -> byId(id) == null);
        selected.addAll(revived);
        resolveAnchors();
        recorded = snapshot();
        settling = null;
        persist.run();
    }

    private void restoreElement(HudElement e, State s) {
        applyFrame(e);
        e.scale(s.scale());
        if (Math.abs(e.width() - s.width()) > .0001 || Math.abs(e.height() - s.height()) > .0001)
            e.size(s.width(), s.height());
        e.position(s.x(), s.y());
        e.hidden(s.hidden());
        e.restoreProperties(s.properties());
    }

    public void poll(double now, String owner) {
        if (!Objects.equals(this.owner, owner)) {
            this.owner = owner;
            history.clear();
            clipboard.clear();
            recorded = snapshot();
            settling = null;
        }
        if (now < nextPoll || moving || history.active() || pending != null || marqueeActive)
            return;
        nextPoll = now + .25;
        List<State> current = snapshot();
        if (same(recorded, current)) {
            settling = null;
            return;
        }
        if (settling != null && same(settling, current)) flushExternal();
        else settling = current;
    }

    private void flushExternal() {
        if (recorded == null || history.active() || same(recorded, snapshot())) return;
        historyOverride = recorded;
        history.begin("HUD 设置");
        if (history.commit()) saved();
    }

    public boolean undo() {
        if (moving || pending != null) return false;
        flushExternal();
        return history.undo();
    }

    public boolean redo() {
        if (moving || pending != null) return false;
        flushExternal();
        return history.redo();
    }

    private boolean same(List<State> a, List<State> b) {
        if (a == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            State x = a.get(i), y = b.get(i);
            if (!x.id().equals(y.id())
                    || x.hidden() != y.hidden()
                    || x.locked() != y.locked()
                    || Math.abs(x.scale() - y.scale()) >= .001
                    || !Objects.equals(x.anchor(), y.anchor())) return false;
            Map<String, String> xp = new HashMap<>(x.properties()),
                    yp = new HashMap<>(y.properties());
            for (String derived : List.of("_measuredW", "_measuredH")) {
                xp.remove(derived);
                yp.remove(derived);
            }
            if (x.anchor() != null)
                for (String k : List.of("section", "relativeX", "relativeY")) {
                    xp.remove(k);
                    yp.remove(k);
                }
            if (!xp.equals(yp)) return false;
            if (x.type().equals("host")
                    && x.anchor() == null
                    && (Math.abs(x.x() - y.x()) * Math.max(1, width - x.width()) >= .001
                            || Math.abs(x.y() - y.y()) * Math.max(1, height - x.height()) >= .001))
                return false;
            if (x.type().equals("host")
                    && (Math.abs(x.width() - y.width()) >= .001
                            || Math.abs(x.height() - y.height()) >= .001)) return false;
        }
        return true;
    }

    public static double[] snap(double pos, double size, List<Double> lines, double threshold) {
        double nearest = threshold, delta = 0, target = Double.NaN;
        for (double line : lines)
            for (double offset : new double[] {0, size / 2, size}) {
                double d = line - (pos + offset);
                if (Math.abs(d) < nearest) {
                    nearest = Math.abs(d);
                    delta = d;
                    target = line;
                }
            }
        return new double[] {pos + delta, target};
    }

    public static Rect bounds(List<Rect> rects) {
        if (rects.isEmpty()) return new Rect(0, 0, 0, 0);
        double x = Double.POSITIVE_INFINITY, y = x, r = Double.NEGATIVE_INFINITY, b = r;
        for (Rect a : rects) {
            x = Math.min(x, a.x());
            y = Math.min(y, a.y());
            r = Math.max(r, a.right());
            b = Math.max(b, a.bottom());
        }
        return new Rect(x, y, r - x, b - y);
    }

    private static Anchor resolve(HudElement e, Anchor point) {
        return point == Anchor.AUTO ? e.growthAnchor() : point;
    }

    private static boolean encloses(Rect a, Rect b) {
        return b.x() >= a.x()
                && b.y() >= a.y()
                && b.right() <= a.right()
                && b.bottom() <= a.bottom();
    }

    private static double finite(double x) {
        if (!Double.isFinite(x))
            throw new IllegalArgumentException("Non-finite pointer coordinate");
        return x;
    }
}
