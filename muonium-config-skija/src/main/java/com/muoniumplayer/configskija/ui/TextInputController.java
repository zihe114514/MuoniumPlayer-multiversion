package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.text.BreakIterator;
import java.util.*;
import java.util.function.*;

/** Bare desktop text input. Owns editing/focus/layout only; outer frames remain with each original control. */
public final class TextInputController {
    /** Implement this in ConfigView; all field calls then use this working controller, not a second editor. */
    public interface TextInput extends PageContext {
        TextInputController textInputController();
        default void bareTextField(String id, TextEdit edit, Rect bounds, Style style, Consumer<String> onChange, Runnable onBlur) {
            textInputController().field(this, id, edit, bounds, style, onChange, onBlur, null);
        }
        default void bareTextField(String id, TextEdit edit, Rect bounds, Style style, Consumer<String> onChange, Runnable onBlur, Runnable onSubmit) {
            textInputController().field(this, id, edit, bounds, style, onChange, onBlur, onSubmit);
        }
    }
    public enum Alignment { START, CENTER, END }
    /** New style instances may be supplied every frame. Coordinates and line height are original Shell dp. */
    public static final class Style {
        public double fontSize, lineHeight, caretWidth = 1, blinkSeconds = 1;
        public boolean multiline, softWrap = true, enabled = true, readOnly, selectAllOnFocus, path;
        public int maxLength = Integer.MAX_VALUE;
        public String placeholder = "", suffix = "";
        public Integer color, placeholderColor, selectionColor, caretColor;
        public Alignment alignment = Alignment.START;
        public Rect clip;
        public UnaryOperator<String> filter = UnaryOperator.identity();
        public Style(double fontSize) { if (!(fontSize > 0)) throw new IllegalArgumentException("font size"); this.fontSize = fontSize; this.lineHeight = fontSize; }
        public Style lineHeight(double value) { lineHeight = value; return this; }
        public Style multiline(boolean value) { multiline = value; return this; }
        public Style placeholder(String value) { placeholder = value == null ? "" : value; return this; }
        public Style filter(UnaryOperator<String> value) { filter = Objects.requireNonNull(value); return this; }
        public Style clip(Rect value) { clip = value; return this; }
    }
    public record Line(int start, int end, boolean newline, double top, double width, List<Integer> offsets, List<Double> stops) {
        public Line { offsets = List.copyOf(offsets); stops = List.copyOf(stops); }
        public double x(int offset) { int best = 0; while (best + 1 < offsets.size() && offsets.get(best + 1) <= offset) best++; return stops.get(best); }
        public int hit(double x) { int best = 0; double distance = Math.abs(x - stops.get(0)); for (int i = 1; i < stops.size(); i++) { double d = Math.abs(x - stops.get(i)); if (d < distance) { best = i; distance = d; } } return offsets.get(best); }
    }
    public record Layout(List<Line> lines, double lineHeight, double width, double height) {
        public Layout { lines = List.copyOf(lines); }
        public int lineAt(int offset, int affinity) { if (affinity >= 0 && affinity < lines.size()) { Line l = lines.get(affinity); if (offset >= l.start() && offset <= l.end()) return affinity; } for (int i = 0; i < lines.size(); i++) { Line l = lines.get(i); if (offset < l.end() || offset == l.end() && (l.newline() || i == lines.size() - 1)) return i; } return lines.size() - 1; }
    }
    private static final class Field {
        String id, key, scope; long order; TextEdit edit; Rect bounds, visible; Style style; Consumer<String> changed; Runnable blur, submit;
        TextEdit.Snapshot baseline; Layout layout; long layoutRevision = -1, layoutFrame = -1, frame; double layoutWidth, layoutSize, layoutLineHeight, scrollX, scrollY, blinkAt, preferredX = Double.NaN; boolean layoutWrap;
        int affinity = -1, dragAnchor, dragStart, dragEnd, dragMode = 1;
    }
    private record Scope(String name, boolean modal) {}
    private final LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
    private final ArrayDeque<Scope> scopes = new ArrayDeque<>();
    private Field active, dragging;
    private long frame, registration;
    private String topModal = "", clickKey = "";
    private int clickCount;
    private double clickTime = Double.NEGATIVE_INFINITY, clickX, clickY;
    private double doubleClickSeconds = .5, clickSlop = 4;
    /** Host may supply its actual desktop double-click time and slop. */
    public void clickConfiguration(double seconds, double distance) { doubleClickSeconds = Math.max(0, seconds); clickSlop = Math.max(0, distance); }
    public void beginFrame(PageContext ctx) { frame++; registration = 0; scopes.clear(); scopes.push(new Scope("page", false)); topModal = ""; }
    public void pushScope(PageContext ctx, String name, boolean modal) { Scope scope = new Scope(Objects.requireNonNull(name), modal); scopes.push(scope); if (modal) { topModal = name; if (active != null && !active.scope.equals(name)) blur(ctx); } }
    public void popScope() { if (scopes.size() < 2) throw new IllegalStateException("Unbalanced input scope"); scopes.pop(); }
    public void endFrame(PageContext ctx) {
        if (active != null && (active.frame != frame || !eligible(active) || !active.edit.focused)) finish(ctx, false, false);
        fields.entrySet().removeIf(e -> e.getValue().frame != frame && e.getValue() != active);
        if (scopes.size() != 1) throw new IllegalStateException("Unbalanced input scope");
    }
    public boolean focused() { return active != null && active.edit.focused && eligible(active); }
    public String focusedId() { return focused() ? active.id : ""; }
    public TextEdit focusedEdit() { return focused() ? active.edit : null; }
    public void field(PageContext ctx, String id, TextEdit edit, Rect bounds, Style style, Consumer<String> changed, Runnable onBlur) { field(ctx, id, edit, bounds, style, changed, onBlur, null); }
    public void field(PageContext ctx, String id, TextEdit edit, Rect bounds, Style style, Consumer<String> changed, Runnable onBlur, Runnable onSubmit) {
        Objects.requireNonNull(id); Objects.requireNonNull(edit); Objects.requireNonNull(bounds); Objects.requireNonNull(style);
        if (scopes.isEmpty()) scopes.push(new Scope("page", false)); String scope = scopes.peek().name(), key = scope.length() + ":" + scope + id;
        Field f = fields.computeIfAbsent(key, ignored -> new Field()); if (f.edit != null && f.edit != edit && f == active) blur(ctx);
        f.key = key; f.scope = scope; f.id = id; f.edit = edit; f.bounds = bounds; f.style = style; f.changed = changed == null ? text -> {} : changed; f.blur = onBlur; f.submit = onSubmit; f.frame = frame; f.order = registration++;
        edit.limit(style.maxLength); edit.preserveSingleLineBreaks(true); edit.multiline(style.multiline);
        f.visible = intersection(bounds, style.clip == null ? ctx.shell() : style.clip);
        if (!eligible(f)) { if (active == f) blur(ctx); edit.focused = false; }
        else if (edit.focused && active != f) focus(ctx, f, false);
        ensureLayout(ctx, f); if (active == f) ensureCaret(f); draw(ctx, f);
        if (eligible(f)) ctx.hit(id, f.visible, () -> press(ctx, f, ctx.shift(), ctx.time(), ctx.mouseX(), ctx.mouseY()), "");
    }
    /** Explicit focus makes the editor usable for Ctrl+F, programmatic chain editing, and accessibility. */
    public boolean focus(PageContext ctx, String id, boolean selectAll) { for (Field f : candidates()) if (f.id.equals(id)) { focus(ctx, f, selectAll); return true; } return false; }
    private void focus(PageContext ctx, Field f, boolean selectAll) {
        if (f == active && f.edit.focused) { if (selectAll) f.edit.selectAll(); return; }
        if (active != null) finish(ctx, false, false);
        active = f; f.edit.focused = true; f.baseline = f.edit.snapshot(); f.blinkAt = ctx.time(); f.preferredX = Double.NaN;
        if (selectAll || f.style.selectAllOnFocus) f.edit.selectAll();
    }
    public boolean pointerDown(PageContext ctx, int button) {
        if (button != 0) return false;
        List<Field> candidates = candidates(); for (int i = candidates.size() - 1; i >= 0; i--) { Field f = candidates.get(i); if (f.visible.contains(ctx.mouseX(), ctx.mouseY())) { press(ctx, f, ctx.shift(), ctx.time(), ctx.mouseX(), ctx.mouseY()); return true; } }
        blur(ctx); return false;
    }
    private void press(PageContext ctx, Field f, boolean shift, double now, double x, double y) {
        // A host can dispatch the same physical down through its hit registry after the input phase.
        if (!eligible(f) || dragging == f) return; boolean already = f == active; focus(ctx, f, false); ensureLayout(ctx, f);
        if (f.key.equals(clickKey) && now - clickTime >= 0 && now - clickTime <= doubleClickSeconds && Math.hypot(x - clickX, y - clickY) <= clickSlop) clickCount = Math.min(3, clickCount + 1); else clickCount = 1;
        clickKey = f.key; clickTime = now; clickX = x; clickY = y;
        int offset = hit(ctx, f, x, y); f.dragMode = shift ? 1 : clickCount; int oldAnchor = f.edit.anchor();
        if (shift && already) { f.edit.selection(oldAnchor, offset); f.dragAnchor = oldAnchor; }
        else if (clickCount == 2) { int[] word = f.edit.wordRange(offset); f.edit.selection(word[0], word[1]); f.dragStart = word[0]; f.dragEnd = word[1]; f.dragAnchor = word[0]; }
        else if (clickCount == 3) { int[] line = f.edit.lineRange(offset); f.edit.selection(line[0], line[1]); f.dragStart = line[0]; f.dragEnd = line[1]; f.dragAnchor = line[0]; }
        else { f.edit.selection(offset, offset); f.dragAnchor = offset; }
        f.blinkAt = now; f.preferredX = Double.NaN; dragging = f; ensureCaret(f);
    }
    public boolean pointerMove(PageContext ctx) { return pointerMove(ctx, ctx.mouseX(), ctx.mouseY()); }
    public boolean pointerMove(PageContext ctx, double x, double y) {
        Field f = dragging; if (f == null || !eligible(f) || active != f) return false; ensureLayout(ctx, f);
        if (x < f.bounds.x()) f.scrollX = Math.max(0, f.scrollX - (f.bounds.x() - x)); else if (x > f.bounds.right() && (!f.style.multiline || !f.style.softWrap)) f.scrollX += x - f.bounds.right();
        if (y < f.bounds.y()) f.scrollY = Math.max(0, f.scrollY - (f.bounds.y() - y)); else if (y > f.bounds.bottom() && f.style.multiline) f.scrollY += y - f.bounds.bottom();
        clampScroll(f); int offset = hit(ctx, f, x, y);
        if (f.dragMode == 2 || f.dragMode == 3) { int[] range = f.dragMode == 2 ? f.edit.wordRange(offset) : f.edit.lineRange(offset); if (offset < f.dragStart) f.edit.selection(f.dragEnd, range[0]); else f.edit.selection(f.dragStart, range[1]); }
        else f.edit.selection(f.dragAnchor, offset);
        f.blinkAt = ctx.time(); ensureCaret(f); return true;
    }
    public boolean pointerUp(PageContext ctx, int button) { if (button != 0 || dragging == null) return false; dragging = null; return true; }
    public boolean wheel(PageContext ctx, double delta) {
        for (Field f : candidates()) if (f.visible.contains(ctx.mouseX(), ctx.mouseY()) && f.style.multiline) { double old = f.scrollY; f.scrollY -= delta * f.layout.lineHeight() * 3; clampScroll(f); return old != f.scrollY; } return false;
    }
    public boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) {
        if (key == ConfigView.Key.TAB && !control && !alt) return tab(ctx, shift);
        Field f = active; if (!focused()) return false;
        if (key == ConfigView.Key.ESCAPE) { finish(ctx, true, false); return true; }
        if (key == ConfigView.Key.ENTER) {
            if (!f.style.multiline || control) { finish(ctx, false, true); return true; }
            if (!f.style.readOnly && !alt) mutate(ctx, f, () -> f.edit.insert("\n")); return true;
        }
        if (control && !alt) {
            switch (key) {
                case A -> f.edit.selectAll();
                case C -> { if (f.edit.selected()) ctx.host().clipboard(f.edit.selectedText()); }
                case X -> { if (!f.style.readOnly && f.edit.selected()) { ctx.host().clipboard(f.edit.selectedText()); mutate(ctx, f, () -> f.edit.insert("")); } }
                case V -> { if (!f.style.readOnly) mutate(ctx, f, () -> f.edit.insert(ctx.host().clipboard())); }
                case Z -> { if (!f.style.readOnly) mutate(ctx, f, () -> { if (shift) f.edit.redo(); else f.edit.undo(); }); }
                case Y -> { if (!f.style.readOnly) mutate(ctx, f, () -> f.edit.redo()); }
                default -> { return motionKey(ctx, f, key, control, shift, alt); }
            }
            afterMove(ctx, f); return true;
        }
        if (motionKey(ctx, f, key, control, shift, alt)) return true;
        if (typed != null && !typed.isEmpty() && !control && !alt) { if (!f.style.readOnly) mutate(ctx, f, () -> f.edit.insert(typed)); return true; }
        // A text editor owns non-shortcut character events even when they contain no printable code point.
        return !control && !alt;
    }
    private boolean motionKey(PageContext ctx, Field f, ConfigView.Key key, boolean control, boolean shift, boolean alt) {
        if (alt) return false;
        switch (key) {
            case LEFT -> { f.edit.move(-1, shift, control); f.affinity = -1; f.preferredX = Double.NaN; }
            case RIGHT -> { f.edit.move(1, shift, control); f.affinity = -1; f.preferredX = Double.NaN; }
            case HOME, END -> { if (control) f.edit.edge(key == ConfigView.Key.END, shift); else { ensureLayout(ctx, f); Line line = f.layout.lines().get(f.layout.lineAt(f.edit.cursor(), f.affinity)); f.edit.selection(shift ? f.edit.anchor() : key == ConfigView.Key.END ? line.end() : line.start(), key == ConfigView.Key.END ? line.end() : line.start()); } f.preferredX = Double.NaN; }
            case UP, DOWN -> { if (control) { f.edit.paragraph(key == ConfigView.Key.UP ? -1 : 1, shift); f.affinity = -1; } else vertical(ctx, f, key == ConfigView.Key.UP ? -1 : 1, shift); }
            case BACKSPACE, DELETE -> { if (!f.style.readOnly) mutate(ctx, f, () -> f.edit.delete(key == ConfigView.Key.BACKSPACE, control)); }
            default -> { return false; }
        }
        afterMove(ctx, f); return true;
    }
    private void vertical(PageContext ctx, Field f, int delta, boolean shift) {
        ensureLayout(ctx, f); int row = f.layout.lineAt(f.edit.cursor(), f.affinity); Line line = f.layout.lines().get(row);
        if (Double.isNaN(f.preferredX)) f.preferredX = line.x(f.edit.cursor()); int target = (int)Geometry.clamp(row + delta, 0, f.layout.lines().size() - 1);
        int offset = f.layout.lines().get(target).hit(f.preferredX); f.edit.selection(shift ? f.edit.anchor() : offset, offset); f.affinity = target;
    }
    private void afterMove(PageContext ctx, Field f) { if (active != f) return; f.blinkAt = ctx.time(); ensureLayout(ctx, f); ensureCaret(f); }
    private void mutate(PageContext ctx, Field f, Runnable action) {
        String previous = f.edit.text(); action.run(); String raw = f.edit.text(), filtered = f.style.filter.apply(raw);
        if (filtered == null) filtered = "";
        if (!raw.equals(filtered)) { int cursor = Objects.toString(f.style.filter.apply(raw.substring(0, f.edit.cursor())), "").length(), anchor = Objects.toString(f.style.filter.apply(raw.substring(0, f.edit.anchor())), "").length(); f.edit.replace(filtered, false); f.edit.selection(anchor, cursor); }
        if (!f.edit.text().equals(previous)) { f.changed.accept(f.edit.text()); }
        f.affinity = -1; f.preferredX = Double.NaN; afterMove(ctx, f);
    }
    public boolean tab(PageContext ctx, boolean backwards) {
        if (active != null && !active.edit.focused) finish(ctx, false, false);
        List<Field> choices = candidates(); if (choices.isEmpty()) return false; int index = choices.indexOf(active); int target = index < 0 ? backwards ? choices.size() - 1 : 0 : Math.floorMod(index + (backwards ? -1 : 1), choices.size());
        focus(ctx, choices.get(target), false); ensureLayout(ctx, active); ensureCaret(active); return true;
    }
    /** Commit exactly once on a click outside, navigation, screen focus loss, or explicit host blur. */
    public void blur(PageContext ctx) { finish(ctx, false, false); }
    public void clear(PageContext ctx) { blur(ctx); fields.clear(); scopes.clear(); dragging = null; clickKey = ""; }
    private void finish(PageContext ctx, boolean revert, boolean submit) {
        Field f = active; if (f == null) return; active = null; dragging = null; f.edit.focused = false; f.preferredX = Double.NaN;
        try {
            if (revert && f.baseline != null && !f.edit.text().equals(f.baseline.text())) { f.edit.restore(f.baseline); f.changed.accept(f.edit.text()); }
            if (submit && f.submit != null) f.submit.run();
        } finally { if (f.blur != null) f.blur.run(); }
    }
    private boolean eligible(Field f) { return f.style != null && f.style.enabled && f.visible != null && f.visible.w() > 0 && f.visible.h() > 0 && (topModal.isEmpty() || topModal.equals(f.scope)); }
    private List<Field> candidates() { return fields.values().stream().filter(f -> f.frame == frame && eligible(f)).sorted(Comparator.comparingLong(f -> f.order)).toList(); }
    private void ensureLayout(PageContext ctx, Field f) {
        double lineHeight = Math.max(.01, f.style.lineHeight); boolean wrap = f.style.multiline && f.style.softWrap;
        if (f.layout == null || f.layoutFrame != frame || f.layoutRevision != f.edit.revision() || f.layoutWidth != f.bounds.w() || f.layoutSize != f.style.fontSize || f.layoutLineHeight != lineHeight || f.layoutWrap != wrap) {
            f.layout = layout(f.edit, Math.max(1, f.bounds.w()), f.style.fontSize, lineHeight, wrap, text -> ctx.painter().measure(text, f.style.fontSize, false));
            f.layoutFrame = frame; f.layoutRevision = f.edit.revision(); f.layoutWidth = f.bounds.w(); f.layoutSize = f.style.fontSize; f.layoutLineHeight = lineHeight; f.layoutWrap = wrap;
        }
        clampScroll(f);
    }
    /** Pure measured layout, also used by hit tests/vertical navigation. Empty and final LF lines are retained. */
    public static Layout layout(TextEdit edit, double width, double fontSize, double lineHeight, boolean wrap, ToDoubleFunction<String> measure) {
        List<Line> lines = new ArrayList<>(); String raw = edit.text(), text = visual(raw, edit.multiline()); double widest = 0; int logicalStart = 0;
        do { int newline = edit.multiline() ? text.indexOf('\n', logicalStart) : -1, end = newline < 0 ? text.length() : newline; int start = logicalStart;
            if (start == end) lines.add(new Line(start, end, newline >= 0, lines.size() * lineHeight, 0, List.of(start), List.of(0d)));
            while (start < end) { int cut = end;
                if (wrap) { int next = start, fits = start, breakAt = -1; BreakIterator breaker = BreakIterator.getLineInstance(Locale.ROOT); breaker.setText(text);
                    while (next < end) { next = Math.min(end, edit.nextGrapheme(next)); double w = measure.applyAsDouble(text.substring(start, next)); if (w > width && fits > start) break; fits = next; if (breaker.isBoundary(next)) breakAt = next; if (w > width) break; }
                    cut = fits < end && breakAt > start ? breakAt : Math.max(edit.nextGrapheme(start), fits); cut = Math.min(end, cut);
                }
                List<Integer> offsets = new ArrayList<>(); List<Double> stops = new ArrayList<>(); offsets.add(start); stops.add(0d); int at = start;
                while (at < cut) { at = Math.min(cut, edit.nextGrapheme(at)); offsets.add(at); stops.add(measure.applyAsDouble(text.substring(start, at))); }
                double measured = stops.get(stops.size() - 1); widest = Math.max(widest, measured); lines.add(new Line(start, cut, cut == end && newline >= 0, lines.size() * lineHeight, measured, offsets, stops)); start = cut;
            }
            if (newline < 0) break; logicalStart = newline + 1;
        } while (logicalStart <= text.length());
        return new Layout(lines, lineHeight, widest, lines.size() * lineHeight);
    }
    private static String visual(String text, boolean multiline) { return multiline ? text : text.replace('\n', ' ').replace('\r', '\ufeff'); }
    private int hit(PageContext ctx, Field f, double x, double y) { ensureLayout(ctx, f); int row = (int)Geometry.clamp(Math.floor((y - f.bounds.y() + f.scrollY) / f.layout.lineHeight()), 0, f.layout.lines().size() - 1); Line line = f.layout.lines().get(row); f.affinity = row; return line.hit(x - f.bounds.x() + f.scrollX - align(f, line)); }
    private double align(Field f, Line line) { double room = Math.max(0, f.bounds.w() - line.width()); return switch(f.style.alignment) { case START -> 0; case CENTER -> room / 2; case END -> room; }; }
    private void ensureCaret(Field f) {
        Line line = f.layout.lines().get(f.layout.lineAt(f.edit.cursor(), f.affinity)); double x = line.x(f.edit.cursor()) + align(f, line), caret = Math.max(1, f.style.caretWidth);
        if (!f.style.multiline || !f.style.softWrap) { if (x < f.scrollX) f.scrollX = x; else if (x + caret > f.scrollX + f.bounds.w()) f.scrollX = x + caret - f.bounds.w(); }
        else f.scrollX = 0;
        if (line.top() < f.scrollY) f.scrollY = line.top(); else if (line.top() + f.layout.lineHeight() > f.scrollY + f.bounds.h()) f.scrollY = line.top() + f.layout.lineHeight() - f.bounds.h(); clampScroll(f);
    }
    private void clampScroll(Field f) { if (f.layout == null) return; f.scrollX = Geometry.clamp(f.scrollX, 0, Math.max(0, f.layout.width() + f.style.caretWidth - f.bounds.w())); f.scrollY = Geometry.clamp(f.scrollY, 0, Math.max(0, f.layout.height() - f.bounds.h())); }
    private void draw(PageContext ctx, Field f) {
        if (f.visible.w() <= 0 || f.visible.h() <= 0) return; Painter p = ctx.painter(); Style style = f.style; int color = style.color == null ? ctx.theme().text() : style.color;
        int selected = style.selectionColor == null ? 0x664286f4 : style.selectionColor, cursor = style.caretColor == null ? ctx.theme().text() : style.caretColor;
        p.save(); p.clip(f.visible, 0);
        try {
            if (f.edit.text().isEmpty() && !f.edit.focused && !style.placeholder.isEmpty()) p.text(style.placeholder, f.bounds.x(), f.bounds.y(), style.fontSize, false, style.placeholderColor == null ? ctx.theme().secondary() : style.placeholderColor);
            for (int row = 0; row < f.layout.lines().size(); row++) { Line line = f.layout.lines().get(row); double top = f.bounds.y() + line.top() - f.scrollY; if (top + f.layout.lineHeight() < f.visible.y() || top > f.visible.bottom()) continue; double x = f.bounds.x() + align(f, line) - f.scrollX;
                int lo = Math.max(line.start(), f.edit.selectionStart()), hi = Math.min(line.end(), f.edit.selectionEnd());
                if (f.edit.focused && f.edit.selected() && (hi > lo || line.newline() && f.edit.selectionEnd() > line.end() && f.edit.selectionStart() <= line.end())) { double left = line.x(lo), right = line.x(hi); if (line.newline() && f.edit.selectionEnd() > line.end()) right = Math.max(right, line.width() + style.fontSize * .5); p.rect(new Rect(x + left, top, Math.max(1, right - left), f.layout.lineHeight()), 0, selected); }
                int separator = style.path ? Math.max(f.edit.text().lastIndexOf('/'), f.edit.text().lastIndexOf('\\')) + 1 : 0;
                int split = Math.max(line.start(), Math.min(line.end(), separator));
                if (split > line.start()) p.text(visual(f.edit.text().substring(line.start(), split), style.multiline), x, top, style.fontSize, false, ctx.theme().secondary());
                if (split < line.end()) p.text(visual(f.edit.text().substring(split, line.end()), style.multiline), x + line.x(split), top, style.fontSize, false, color);
                if (!style.suffix.isEmpty() && row == f.layout.lines().size() - 1) p.text(style.suffix, x + line.width(), top, style.fontSize, false, ctx.theme().secondary());
            }
            boolean show = f == active && f.edit.focused && !f.edit.selected() && !style.readOnly && (style.blinkSeconds <= 0 || (ctx.time() - f.blinkAt) % style.blinkSeconds < style.blinkSeconds / 2);
            if (show) { Line line = f.layout.lines().get(f.layout.lineAt(f.edit.cursor(), f.affinity)); double x = f.bounds.x() + align(f, line) + line.x(f.edit.cursor()) - f.scrollX; x = Geometry.clamp(x, f.bounds.x(), Math.max(f.bounds.x(), f.bounds.right() - style.caretWidth)); p.rect(new Rect(x, f.bounds.y() + line.top() - f.scrollY, style.caretWidth, f.layout.lineHeight()), 0, cursor); }
        } finally { p.restore(); }
    }
    public Layout layout(PageContext ctx, String id) { for (Field f : fields.values()) if (f.id.equals(id) && f.frame == frame) { ensureLayout(ctx, f); return f.layout; } return null; }
    public Rect caretRect(PageContext ctx) { if (!focused()) return null; ensureLayout(ctx, active); Line line = active.layout.lines().get(active.layout.lineAt(active.edit.cursor(), active.affinity)); return new Rect(active.bounds.x() + align(active, line) + line.x(active.edit.cursor()) - active.scrollX, active.bounds.y() + line.top() - active.scrollY, active.style.caretWidth, active.layout.lineHeight()); }
    private static Rect intersection(Rect a, Rect b) { double x = Math.max(a.x(), b.x()), y = Math.max(a.y(), b.y()); return new Rect(x, y, Math.max(0, Math.min(a.right(), b.right()) - x), Math.max(0, Math.min(a.bottom(), b.bottom()) - y)); }
}
