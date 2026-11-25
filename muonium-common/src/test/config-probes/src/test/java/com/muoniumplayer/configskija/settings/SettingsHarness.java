package com.muoniumplayer.configskija.settings;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.settings.*;
import com.muoniumplayer.configskija.ui.settings.SettingsSchema.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;

/** Runnable headless integration harness. No Minecraft, loader, native library or external test runner. */
public final class SettingsHarness {
    static int assertions;
    static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    static void equal(Object actual, Object expected, String message) { check(Objects.deepEquals(actual, expected), message + ": " + actual + " != " + expected); }
    static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < .00001, message + ": " + actual + " != " + expected); }
    public static final class Fixture implements ConfigHost, SettingsSchema, SettingsSchema.Provider {
        final Map<String, Object> values = new LinkedHashMap<>(); final Map<String, String> disabled = new HashMap<>(), titles = new HashMap<>();
        final Map<String, OptionStyle> styles = new HashMap<>(); final Map<String, GroupStyle> groups = new HashMap<>();
        final List<Category> categories = new ArrayList<>(); final List<Category> builtins = new ArrayList<>(); final List<String> errors = new ArrayList<>(); String clipboard = ""; int writes, persists; Consumer<String> fileResult;
        boolean lock;
        Option add(String id, Kind kind, Object value) { values.put(id, value); Option o = new Option(id, id, "Description for " + id, kind, 0, 100, 0, List.of("First", "Second", "Third"), () -> values.get(id), v -> { values.put(id, v); writes++; }, () -> disabled.getOrDefault(id, ""), () -> values.put(id, value), () -> titles.getOrDefault(id, id)); styles.put(id, OptionStyle.forOption(o)); return o; }
        void category(String id, String title, Option... options) { categories.add(new Category(id, title, "", "", List.of(new Group(id + ".group", "General", List.of(options))))); }
        public SettingsSchema settingsSchema() { return this; }
        public OptionStyle option(Option option) { return styles.get(option.id()); }
        public GroupStyle group(Group group) { return groups.getOrDefault(group.id(), GroupStyle.plain()); }
        public boolean locked() { return lock; }
        public boolean chooseFile(Option option, OptionStyle style, String current, Consumer<String> result) { fileResult = result; return true; }
        public int modifierBit(int code) { return code == 341 ? 1 : 0; }
        public String keyName(int code) { return code == 341 ? "Ctrl" : code == 65 ? "A" : Integer.toString(code); }
        public List<Category> categories() { return categories; }
        public List<Category> favoriteCategories() { return builtins; }
        public List<HudElement> huds() { return List.of(); }
        public void persist() { persists++; }
        public void close() {}
        public String clipboard() { return clipboard; }
        public void clipboard(String text) { clipboard = text; }
        public void error(String action, Throwable error) { errors.add(action + ": " + error); }
    }
    public record Hit(String id, Rect bounds, Runnable action) {}
    public record Text(String text, double x, double y, double size) {}
    public record Radial(Rect bounds, double cx, double cy, double radius) {}
    public static class TracePainter implements Painter {
        public final List<Double> layers = new ArrayList<>();
        public final List<Radial> vignettes = new ArrayList<>(); public final List<double[]> polygons = new ArrayList<>();
        public final List<Rect> rectangles = new ArrayList<>(); public final List<Text> texts = new ArrayList<>(); int saves;
        public void layer(Rect bounds, double alpha) { layers.add(alpha); save(); }
        public void save() { saves++; } public void restore() { check(saves > 0, "balanced painter state"); saves--; }
        public void translate(double x, double y) {} public void scale(double x, double y) {} public void clip(Rect r, double radius) { check(r.w() >= 0 && r.h() >= 0, "nonnegative clip"); }
        public void rect(Rect r, double radius, int color) { check(Double.isFinite(r.x()) && Double.isFinite(r.y()) && r.w() >= 0 && r.h() >= 0, "finite draw geometry"); rectangles.add(r); }
        public void outline(Rect r, double radius, double width, int color) {} public void line(double x1, double y1, double x2, double y2, double width, int color) {}
        public void text(String text, double x, double y, double size, boolean bold, int color) { texts.add(new Text(text, x, y, size)); }
        public void vignette(Rect bounds, double cx, double cy, double radius, int color) { vignettes.add(new Radial(bounds, cx, cy, radius)); }
        public void polygon(double[] xy, double radius, int color) { check(radius == 2 && xy.length == 6, "original range thumb uses rounded 3-vertex polygon with radius 2"); polygons.add(xy.clone()); }
        public double measure(String text, double size, boolean bold) { return text == null ? 0 : text.codePointCount(0, text.length()) * size * .52; }
    }
    public static class Context implements TextInputController.TextInput {
        public final TextInputController input = new TextInputController();
        final Fixture host; Painter drawing; final TracePainter painter = new TracePainter(); final UiPreferences preferences = new UiPreferences(Map.of(), () -> {});
        final Map<String, Hit> hits = new LinkedHashMap<>(); final Map<String, Consumer<String>> edits = new HashMap<>(); final Map<String, TextEdit> editors = new HashMap<>();
        final List<String> sounds = new ArrayList<>(), toasts = new ArrayList<>(); final History<Map<String, Object>> history;
        UiPage overlay; SettingsPage page; Navigation.Route route = new Navigation.Route(Navigation.Page.SETTINGS); String query = "";
        String transactionOption;
        Rect content = new Rect(289, 88, 1052, 562); double x, y, now = 10, scroll, maximum; int nativeCode; boolean strict = true; BiConsumer<Double, Double> drag; Runnable finish;
        Context(Fixture host, boolean favorites) { this.host = host; page = new SettingsPage(favorites); history = new History<>(100, () -> new LinkedHashMap<>(host.values), values -> { host.values.clear(); host.values.putAll(values); }); }
        void frame() { content = new Rect(289, 88, 1052, 562); hits.clear(); edits.clear(); painter.rectangles.clear(); painter.texts.clear(); input.beginFrame(this); page.render(this); if (overlay != null) { input.pushScope(this, "overlay", true); overlay.render(this); input.popScope(); } input.endFrame(this); equal(painter.saves, 0, "no leaked canvas save"); }
        Hit hit(String id) { Hit hit = hits.get(id); if (hit == null) throw new AssertionError("Missing hit: " + id + " available=" + hits.keySet()); return hit; }
        void click(String id) { Hit hit = hit(id); x = hit.bounds.x() + hit.bounds.w() / 2; y = hit.bounds.y() + hit.bounds.h() / 2; hit.action.run(); }
        void type(String id, String text) { Consumer<String> edit = edits.get(id); if (edit == null) throw new AssertionError("Missing editor " + id + ": " + edits.keySet()); check(input.focus(this, id, false), "registered bare field focus: " + id); editors.get(id).selectAll(); input.key(this, ConfigView.Key.NONE, text, false, false, false); }
        void dragTo(double x, double y) { this.x = x; this.y = y; if (drag != null) drag.accept(x, y); }
        void release() { Runnable end = finish; drag = null; finish = null; if (end != null) end.run(); }
        public Painter painter() { return drawing == null ? painter : drawing; } public Theme theme() { return Theme.BUILTIN[0]; } public int accent() { return 0xff8759ff; }
        public ConfigHost host() { return host; } public UiPreferences preferences() { return preferences; } public String query() { return query; } public Navigation.Route route() { return route; }
        public double mouseX() { return x; } public double mouseY() { return y; } public double width() { return 1391; } public double height() { return 700; } public double time() { return now; } public double delta() { return .016; }
        public boolean control() { return false; } public boolean shift() { return false; } public boolean alt() { return false; } public boolean textFocused() { return input.focused(); } public int nativeKeyCode() { return nativeCode; }
        public Rect content() { return content; } public void content(Rect bounds) { content = bounds; } public double scroll() { return scroll; } public void scroll(double value) { scroll = Math.max(0, Math.min(maximum, value)); } public void scrollRange(double value) { maximum = value; scroll(scroll); }
        public void hit(String id, Rect bounds, Runnable action, String tooltip) { hits.put(id, new Hit(id, bounds, action)); }
        public void contentHit(String id, Rect bounds, Runnable action, String tooltip) { double x = Math.max(content.x(), bounds.x()), y = Math.max(content.y(), bounds.y()), right = Math.min(content.right(), bounds.right()), bottom = Math.min(content.bottom(), bounds.bottom()); if (right > x && bottom > y) hit(id, new Rect(x, y, right - x, bottom - y), action, tooltip); }
        public void tooltip(String id, Rect bounds, String text) {}
        public void startDrag(BiConsumer<Double, Double> update, Runnable end) { if (finish != null) release(); drag = update; finish = end; }
        public void navigate(Navigation.Route route) { this.route = route; scroll = 0; }
        public void toast(String message) { toasts.add(message); } public void sound(String event) { sounds.add(event); }
        public void edit(ConfigHost.Option option, Object value) { if (history.active()) option.set(value); else { history.begin(option.title()); option.set(value); history.commit(); } }
        public void beginEdit(ConfigHost.Option option) { transactionOption = option.id(); beginEdit(option.title()); }
        public void beginEdit(String name) { if (strict) check(!history.active(), "no nested history begin: " + name); history.begin(name); } public void commitEdit() { history.commit(); } public void cancelEdit() { history.cancel(); }
        public TextInputController textInputController() { return input; }
        public void bareTextField(String id, TextEdit edit, Rect bounds, TextInputController.Style style, Consumer<String> changed, Runnable onBlur) { edits.put(id, changed); editors.put(id, edit); TextInputController.TextInput.super.bareTextField(id, edit, bounds, style, changed, onBlur); }
        public void textField(String id, TextEdit edit, Rect bounds, String placeholder, Consumer<String> changed) { throw new AssertionError("Settings must register bare fields, never legacy boxed textField: " + id); }
        public void textDialog(String title, String hint, String initial, String action, Consumer<String> submit) {} public void confirm(String title, String description, Runnable accepted) { accepted.run(); }
        public void overlay(UiPage page) { if (overlay != null) overlay.leave(this); overlay = page; } public void dismissOverlay() { UiPage old = overlay; overlay = null; if (old != null) old.leave(this); }
        public void close() {} public double animate(String key, double target, double seconds, Motion.Easing easing) { return target; }
    }

    private static void numeric() {
        near(ControlMath.sliderValue(9.5, 300, 19, 0, 100, 0), 0, "thumb starts exactly at min"); near(ControlMath.sliderValue(290.5, 300, 19, 0, 100, 0), 100, "thumb ends exactly at max");
        near(ControlMath.sliderValue(-20, 300, 19, -10, 10, .5), -10, "drag outside clamps"); near(ControlMath.snap(.3, -.2, 2, .2), .4, "step anchored to min");
        equal(ControlMath.filterNumber("-12a..3-4e5"), "-12.345", "original numeric filter"); check(ControlMath.parse("NaN", 0, 1) == null, "reject nonfinite numeric input");
        near(ControlMath.spinnerStep(0, .1, .9), .1, "fractional bounds use .1 step"); equal(ControlMath.containerLike(new int[] {1}, List.of(2.5, -2.5), true), new int[] {3, -2}, "range integer rounding is half up");
        check(ControlMath.containerLike(new float[] {1}, List.of(.5), true) instanceof float[], "float array shape retained"); equal(ControlMath.ordered(9, 2), List.of(2d, 9d), "persist ranges ordered");
        StableList<String> slots = new StableList<>(List.of("a", "a", "b")); long first = slots.entries().get(0).id(), second = slots.entries().get(1).id(); slots.move(1, 2, 0); check(slots.entries().get(2).id() == second, "duplicate reorder preserves selected slot"); slots.update(second, "changed"); check(slots.entries().stream().filter(e -> e.id() == first).allMatch(e -> e.value().equals("a")), "duplicate edit never touches sibling");
        try { slots.move(0, 2, 1); throw new AssertionError("locked move accepted"); } catch (IllegalArgumentException expected) { assertions++; }
        slots.replace(List.of("a", "changed", "a")); check(slots.entries().get(0).id() == first, "external replace retains matching identity");
    }
    private static void colors() {
        for (int color : new int[] {0x12aabbcc, 0xff000000, 0xffffffff, 0xff800020, 0x8080ff80}) { ColorModel model = new ColorModel(color, true); equal(model.argb(), color, "HSV round trip"); equal(ColorModel.parseHex(ColorModel.hex(color)), color, "AARRGGBB round trip"); }
        equal(ColorModel.parseHex("#123456"), 0xff123456, "six digit opaque"); equal(ColorModel.parseHex("80123456"), 0x80123456, "eight digit alpha first"); check(ColorModel.parseHex("GG1122") == null, "invalid hex has no side effects"); check(ColorModel.parseHex("123") == null, "no invented short hex support"); equal(new ColorModel(0x00112233, false).argb(), 0xff112233, "noAlpha forces opaque");
        ColorModel noAlpha = new ColorModel(0xff112233, false); noAlpha.apply(0x80112233); equal(noAlpha.argb(), 0x80112233, "original noAlpha hides the alpha controls but hex still accepts alpha");
        near(ColorModel.luminance(0xffffffff), 1, "white contrast"); equal(ColorModel.hsv(360, 1, 1, 1), 0xffff0000, "hue 360 endpoint");
    }
    private static void routingAndLiveState() {
        Fixture host = new Fixture(); ConfigHost.Option first = host.add("toggle", ConfigHost.Kind.TOGGLE, false), second = host.add("choice", ConfigHost.Kind.CHOICE, "First"); host.category("one", "First category", first); host.category("two", "Second category", second);
        Context ctx = new Context(host, false); ctx.route = new Navigation.Route(Navigation.Page.SETTINGS, "two"); ctx.frame(); check(ctx.hits.containsKey("settings.choice.choice"), "route selects category by id"); check(!ctx.hits.containsKey("settings.toggle.toggle"), "other category excluded"); near(ctx.content.y(), 140, "33 dp chips plus 19 dp list separation");
        ctx.query = "toggle"; ctx.frame(); check(ctx.hits.containsKey("settings.toggle.toggle"), "search across categories"); check(!ctx.hits.containsKey("settings.category.one"), "search hides category chips");
        check(SettingsPage.matches(first, host.categories.get(0), host.categories.get(0).groups().get(0), "toggel"), "original fuzzy matcher handles bounded edit distance");
        check(!SettingsPage.matches(first, host.categories.get(0), host.categories.get(0).groups().get(0), "category toggle"), "separate fields are OR, never concatenated phrase match"); near(ctx.content.y(), 88, "search restores content origin");
        ctx.preferences.set("searchDistance", 0); ctx.query = "toggel"; ctx.frame(); check(!ctx.hits.containsKey("settings.toggle.toggle"), "distance zero disables fuzzy matches at runtime"); ctx.preferences.set("searchDistance", 2); ctx.frame(); check(ctx.hits.containsKey("settings.toggle.toggle"), "live distance preference restores fuzzy matches");
        host.titles.put("toggle", "Live title changed"); ctx.query = "live"; ctx.frame(); check(ctx.painter.texts.stream().anyMatch(t -> t.text.equals("Live title changed")), "live title supplier read every frame");
        ctx.click("settings.toggle.toggle"); equal(host.values.get("toggle"), true, "toggle setter connected"); host.values.put("toggle", false); ctx.frame(); check(ctx.painter.texts.stream().anyMatch(t -> t.text.equals("Off")), "external value read live");
        Hit old = ctx.hit("settings.toggle.toggle"); host.disabled.put("toggle", "locked now"); old.action.run(); equal(host.values.get("toggle"), false, "stale click rechecks enabledReason"); ctx.frame(); check(!ctx.hits.containsKey("settings.toggle.toggle"), "disabled controls unregistered"); host.disabled.clear(); host.styles.get("toggle").visible = () -> false; ctx.frame(); check(!ctx.hits.containsKey("settings.toggle.toggle"), "hidden is omitted");
        host.styles.get("toggle").visible = () -> true; Context favorites = new Context(host, true); favorites.preferences.favorite("toggle", true); favorites.frame(); check(!favorites.hits.containsKey("settings.toggle.toggle") && !favorites.hits.containsKey("settings.choice.choice"), "missing builtin favorite tree stays empty even when old pin preferences exist");
    }
    private static void sliderAndPopover() {
        Fixture host = new Fixture(); ConfigHost.Option slider = host.add("volume", ConfigHost.Kind.SLIDER, 50d); host.category("c", "Settings", slider); Context ctx = new Context(host, false); ctx.frame();
        Hit track = ctx.hit("settings.slider.volume.volume"); near(track.bounds.w(), 300, "standalone track is 300 dp"); ctx.click(track.id); ctx.dragTo(track.bounds.right() + 100, track.bounds.y()); ctx.release(); equal(host.values.get("volume"), 100d, "drag reaches max"); equal(ctx.history.undoSize(), 1, "whole drag is one undo record"); equal(ctx.transactionOption, "volume", "drag transaction scoped to exact option id"); ctx.history.undo(); equal(host.values.get("volume"), 50d, "undo restores drag start");
        ctx.frame(); ctx.type("settings.number.volume", "12.3456"); equal(host.values.get("volume"), 12.3456, "precise input keeps precision"); ctx.type("settings.number.volume", "-"); equal(host.values.get("volume"), 12.3456, "partial number draft does not overwrite setting");
        host = new Fixture(); ConfigHost.Option choice = host.add("mode", ConfigHost.Kind.CHOICE, "First"); host.category("c", "Settings", choice); ctx = new Context(host, false); ctx.frame(); ctx.click("settings.choice.mode"); ctx.frame(); check(ctx.overlay != null, "dropdown is an overlay UiPage"); ctx.overlay.key(ctx, ConfigView.Key.DOWN, "", false, false, false); equal(host.values.get("mode"), "First", "keyboard highlight does not prematurely commit"); ctx.overlay.key(ctx, ConfigView.Key.ENTER, "", false, false, false); equal(host.values.get("mode"), "Second", "enter commits highlighted choice"); check(ctx.overlay == null, "choice dismisses overlay");
        ctx.frame(); ctx.click("settings.choice.mode"); ctx.frame(); ctx.x = 0; ctx.y = 0; check(ctx.overlay.pointerDown(ctx, 0), "outside click consumed"); equal(host.values.get("mode"), "Second", "outside click preserves choice");
    }
    private static void colorPopup() {
        Fixture host = new Fixture(); ConfigHost.Option color = host.add("ink", ConfigHost.Kind.COLOR, 0xff335577); host.category("c", "Settings", color); Context ctx = new Context(host, false); ctx.frame(); ctx.click("settings.color.ink"); ctx.frame();
        equal(ctx.transactionOption, "ink", "color transaction scoped to exact option id"); ctx.type("settings.picker.ink.hex", "80112233"); equal(host.values.get("ink"), 0x80112233, "popup hex writes ARGB"); ctx.type("settings.picker.ink.hex", "invalid"); equal(host.values.get("ink"), 0x80112233, "invalid hex keeps current color"); ctx.type("settings.picker.ink.opacity", "25"); equal((Integer)host.values.get("ink") >>> 24, 64, "opacity field clamps and rounds alpha"); ctx.overlay.key(ctx, ConfigView.Key.ESCAPE, "", false, false, false); check(ctx.overlay != null && !ctx.input.focused(), "first Escape reverts the focused color field before closing its popup"); ctx.overlay.key(ctx, ConfigView.Key.ESCAPE, "", false, false, false); equal(ctx.history.undoSize(), 1, "popup changes share one history transaction"); check(!ctx.history.active(), "popup closes history"); ctx.history.undo(); equal(host.values.get("ink"), 0xff335577, "color popup undo restores starting value");
        host.styles.get("ink").gradient = host.styles.get("ink").chroma = true; host.values.put("ink", new ColorValue(0xff336699, false, 1, false, 0xff336699)); ctx.frame(); ctx.click("settings.color.ink"); ctx.frame(); ctx.click("settings.picker.ink.gradient"); ctx.frame(); check(((ColorValue)host.values.get("ink")).gradient(), "gradient flag persisted through actual typed value"); check(((ColorValue)host.values.get("ink")).gradientArgb() != ((ColorValue)host.values.get("ink")).argb(), "enabling equal gradient creates +40 degree endpoint"); ctx.type("settings.picker.ink.gradient.hex", "FF991122"); equal(((ColorValue)host.values.get("ink")).gradientArgb(), 0xff991122, "gradient hex endpoint writes"); ctx.dismissOverlay();
    }
    private static void listsAndAsync() {
        Fixture host = new Fixture(); ConfigHost.Option list = host.add("list", ConfigHost.Kind.CHOICE, new String[] {"same", "same", "third"}); host.styles.get("list").visualizer = Visualizer.TEXT_LIST; host.category("c", "Settings", list); Context ctx = new Context(host, false); ctx.frame();
        ctx.type("settings.list.text.list.1", "edited"); equal(host.values.get("list"), new String[] {"same", "edited", "third"}, "duplicate text list edits only selected slot"); ctx.frame(); ctx.click("settings.list.remove.2.list"); equal(host.values.get("list"), new String[] {"same", "edited"}, "remove targets stable identity");
        host = new Fixture(); ConfigHost.Option file = host.add("file", ConfigHost.Kind.CHOICE, "before"); host.styles.get("file").visualizer = Visualizer.FILE; host.category("c", "Settings", file); ctx = new Context(host, false); ctx.frame(); ctx.click("settings.file.file"); check(host.fileResult != null, "file dialog delegated to host"); Consumer<String> callback = host.fileResult; ctx.page.leave(ctx); callback.accept("stale"); equal(host.values.get("file"), "before", "late file dialog callback does not write after leaving");
    }
    private static void colorListContainer() {
        Fixture host = new Fixture(); ConfigHost.Option color = host.add("colors", ConfigHost.Kind.CHOICE, new java.awt.Color[] {new java.awt.Color(0xff123456, true)}); host.styles.get("colors").visualizer = Visualizer.COLOR_LIST; host.category("c", "Settings", color); Context ctx = new Context(host, false); ctx.frame(); ctx.click("settings.list.color.0.colors"); ctx.frame(); ctx.type("settings.picker.colors.hex", "FFABCDEF"); ctx.dismissOverlay(); Object stored = host.values.get("colors"); check(stored instanceof java.awt.Color[], "color list retains AWT Color array component type"); equal(((java.awt.Color[])stored)[0].getRGB(), 0xffabcdef, "color list hex updates actual color component");
    }
    private static void capture() {
        Fixture host = new Fixture(); ConfigHost.Option bind = host.add("bind", ConfigHost.Kind.CHOICE, Binding.none()); host.styles.get("bind").visualizer = Visualizer.KEYBIND; host.category("c", "Settings", bind); Context ctx = new Context(host, false); ctx.frame(); ctx.click("settings.keybind.bind"); check(ctx.page.recording(), "page exposes real keybind recording state"); ctx.page.rawKey(ctx, 341, true); ctx.page.rawKey(ctx, 65, true); ctx.page.rawKey(ctx, 65, false); equal(host.values.get("bind"), Binding.none(), "combo waits for all keys to release"); ctx.page.rawKey(ctx, 341, false); Binding applied = (Binding)host.values.get("bind"); equal(applied.keys(), List.of(65), "modifier split retains main key"); equal(applied.modifiers(), 1, "modifier mask retained"); check(!ctx.page.recording(), "recording gate closes after all keys release"); ctx.frame(); ctx.click("settings.keybind.bind"); ctx.page.key(ctx, ConfigView.Key.ESCAPE, "", false, false, false); equal(host.values.get("bind"), Binding.none(), "escape unbind matches original");
    }
    private static void riskInteractions() {
        Fixture host = new Fixture(); ConfigHost.Option range = host.add("range", ConfigHost.Kind.CHOICE, new float[] {20, 80}); host.styles.get("range").visualizer = Visualizer.RANGE_NUMBER; host.category("c", "Settings", range); Context ctx = new Context(host, false); ctx.frame();
        ctx.type("settings.number.range.start", "90"); equal(host.values.get("range"), new float[] {80, 90}, "crossed range persists sorted"); ctx.frame(); equal(ctx.editors.get("settings.number.range.start").text(), "90", "crossed draft is not clamped midway through typing"); ctx.editors.get("settings.number.range.start").focused = false; ctx.frame(); equal(host.values.get("range"), new float[] {80, 90}, "range loses focus without corrupting persisted pair");
        host = new Fixture(); ConfigHost.Option chain = host.add("chain", ConfigHost.Kind.CHOICE, new float[] {0, 20, 40}); host.styles.get("chain").visualizer = Visualizer.NUMBER_CHAIN; host.styles.get("chain").lockedLeading = 1; host.category("c", "Settings", chain); ctx = new Context(host, false); ctx.frame(); check(!ctx.edits.containsKey("settings.chain.chain.0"), "leading chain value cannot be edited"); check(!ctx.hits.containsKey("settings.list.drag.0.chain"), "leading chain value cannot be reordered"); ctx.type("settings.chain.chain.1", "33"); equal(host.values.get("chain"), new float[] {0, 20, 40}, "chain edit waits for edit end"); ctx.frame(); ctx.editors.get("settings.chain.chain.1").focused = false; ctx.frame(); equal(host.values.get("chain"), new float[] {0, 33, 40}, "chain value commits on focus loss");
        host = new Fixture(); ConfigHost.Option slider = host.add("slider", ConfigHost.Kind.SLIDER, 50d); host.category("c", "Settings", slider); ctx = new Context(host, false); ctx.frame(); ctx.click("settings.slider.slider.slider"); ctx.dragTo(2000, 200); check(ctx.page.key(ctx, ConfigView.Key.ESCAPE, "", false, false, false), "escape consumes active drag"); ctx.release(); equal(host.values.get("slider"), 50d, "cancelled drag restores original value"); equal(ctx.history.undoSize(), 0, "cancelled drag creates no undo record");
        ctx.frame(); ctx.type("settings.number.slider", "25"); TextEdit field = ctx.editors.get("settings.number.slider"); host.styles.get("slider").visible = () -> false; ctx.frame(); check(!field.focused, "hidden input loses keyboard ownership");
        host = new Fixture(); ConfigHost.Option head = host.add("head", ConfigHost.Kind.TOGGLE, false), child = host.add("child", ConfigHost.Kind.TOGGLE, true); host.category("accordion", "Settings", child); host.groups.put("accordion.group", new GroupStyle(true, false, head, "", "")); ctx = new Context(host, false); ctx.frame(); check(!ctx.hits.containsKey("settings.toggle.child"), "disabled accordion head initializes collapsed"); ctx.click("settings.accordion.accordion.group"); ctx.now += 1; ctx.frame(); ctx.now += 1; ctx.frame(); check(ctx.hits.containsKey("settings.toggle.child"), "accordion header expands grid after animation");
    }
    private static void confirmationAndMenus() {
        Fixture host = new Fixture(); ConfigHost.Option action = host.add("action", ConfigHost.Kind.ACTION, ""); host.styles.get("action").confirmText = "Confirm"; host.category("c", "Settings", action); Context ctx = new Context(host, false); ctx.frame(); int writes = host.writes; ctx.click("settings.button.action"); equal(host.writes, writes, "first action click arms confirmation"); ctx.now += .1; ctx.click("settings.button.action"); equal(host.writes, writes, "double-click cannot confirm"); ctx.now += .4; ctx.click("settings.button.action"); equal(host.writes, writes + 1, "confirmation executes after 400ms guard");
        host = new Fixture(); ConfigHost.Option option = host.add("reset", ConfigHost.Kind.TOGGLE, false); host.category("c", "Settings", option); ctx = new Context(host, false); ctx.frame(); ctx.click("settings.toggle.reset"); equal(host.values.get("reset"), true, "reset fixture changed"); ctx.x = 350; ctx.y = 105; check(ctx.page.pointerDown(ctx, 1), "row right click opens option actions"); ctx.frame(); ctx.click("settings.menu.reset.1"); equal(host.clipboard, "true", "copy menu reads live value");
        ctx.frame(); ctx.click("settings.actions.reset"); ctx.frame(); check(!ctx.hits.containsKey("settings.menu.reset.2"), "original setting menu has no favorite/pin action"); ctx.click("settings.menu.reset.0"); equal(host.values.get("reset"), false, "reset uses actual reset callback"); check(!ctx.history.active(), "reset closes its transaction");
        host = new Fixture(); ConfigHost.Option multi = host.add("multi", ConfigHost.Kind.CHOICE, new boolean[] {true, false, false}); host.styles.get("multi").visualizer = Visualizer.MULTI_SELECT; host.category("c", "Settings", multi); ctx = new Context(host, false); ctx.frame(); ctx.click("settings.choice.multi"); ctx.frame(); ctx.click("settings.choice.item.multi.-1"); equal(host.values.get("multi"), new boolean[] {true, true, true}, "select-all keeps boolean container shape"); check(ctx.overlay != null, "multi-select remains open after a toggle"); ctx.frame(); ctx.click("settings.choice.item.multi.1"); equal(host.values.get("multi"), new boolean[] {true, false, true}, "individual multi-select update"); ctx.dismissOverlay();
    }
    private static void boundsAndGestureIdentity() {
        Fixture host = new Fixture(); ConfigHost.Option slider = host.add("dynamic", ConfigHost.Kind.SLIDER, 5d); double[] upper = {10}; host.styles.get("dynamic").minimum = () -> 0; host.styles.get("dynamic").maximum = () -> upper[0]; host.category("c", "Settings", slider); Context ctx = new Context(host, false); ctx.frame(); ctx.click("settings.slider.dynamic.dynamic"); BiConsumer<Double, Double> staleUpdate = ctx.drag; Runnable staleEnd = ctx.finish; ctx.dragTo(2000, 100); ctx.release(); equal(host.values.get("dynamic"), 10d, "presentation dynamic maximum bounds track input"); upper[0] = 20; ctx.frame(); ctx.click("settings.slider.dynamic.dynamic"); ctx.dragTo(2000, 100); staleUpdate.accept(0d, 0d); staleEnd.run(); check(ctx.history.active(), "old drag completion cannot commit a newer gesture"); equal(host.values.get("dynamic"), 20d, "old drag callback cannot overwrite new gesture"); ctx.release();
    }
    private static void inheritedSlider() {
        Fixture host = new Fixture(); ConfigHost.Option option = host.add("inherited", ConfigHost.Kind.SLIDER, -1f); OptionStyle style = host.styles.get("inherited"); style.visualizer = Visualizer.INHERITABLE_SLIDER; style.inheritSentinel = -1d; double[] inherited = {25}; style.inheritedValue = () -> inherited[0]; host.category("c", "Settings", option); Context ctx = new Context(host, false); ctx.frame(); equal(ctx.editors.get("settings.number.inherited").text(), "25", "inherited sentinel compares numbers across wrappers"); inherited[0] = 35; ctx.frame(); equal(ctx.editors.get("settings.number.inherited").text(), "35", "inherited value supplier stays live"); ctx.type("settings.number.inherited", "41"); equal(host.values.get("inherited"), 41f, "editing inherited value takes ownership preserving Float type"); ctx.frame(); ctx.click("settings.inherit.inherited"); equal(host.values.get("inherited"), -1f, "inherit chip restores sentinel preserving Float type");
    }
    private static void nativeDrawingContracts() {
        Fixture host = new Fixture(); ConfigHost.Option option = host.add("r", ConfigHost.Kind.CHOICE, new float[] {20, 80}); host.styles.get("r").visualizer = Visualizer.RANGE_SLIDER; host.category("c", "Settings", option); Context ctx = new Context(host, false); ctx.frame(); check(ctx.painter.polygons.size() == 2, "both range thumbs use native polygons"); Radial normal = ctx.painter.vignettes.get(0); near(normal.cx(), normal.bounds().x() + normal.bounds().w() / 2, "normal vignette center x"); near(normal.cy(), normal.bounds().y() + normal.bounds().h() / 2, "normal vignette center y"); near(normal.radius(), Math.min(normal.bounds().w(), normal.bounds().h()) * .9, "normal vignette radius");
        host.groups.put("c.group", new GroupStyle(true, false, null, "", "")); ctx.frame(); Radial accordion = ctx.painter.vignettes.get(ctx.painter.vignettes.size() - 1); near(accordion.cy(), accordion.bounds().y(), "accordion vignette starts at top center"); near(accordion.radius(), accordion.bounds().w() * .8, "accordion vignette radius");
    }
    private static void multilineSetting() {
        Fixture host = new Fixture(); ConfigHost.Option option = host.add("text", ConfigHost.Kind.CHOICE, "first\nsecond"); host.styles.get("text").visualizer = Visualizer.TEXT; host.styles.get("text").multiline = true; host.category("c", "Settings", option); Context ctx = new Context(host, false); ctx.frame(); equal(ctx.editors.get("settings.text.text").text(), "first\nsecond", "first mount does not strip multiline host value"); ctx.input.focus(ctx, "settings.text.text", true); ctx.input.key(ctx, ConfigView.Key.NONE, "new\nlines", false, false, false); equal(host.values.get("text"), "new\nlines", "multiline typed/pasted setting reaches actual setter"); ctx.input.key(ctx, ConfigView.Key.ENTER, "", false, false, false); equal(host.values.get("text"), "new\nlines\n", "multiline Enter inserts newline into real host option"); ctx.input.key(ctx, ConfigView.Key.ESCAPE, "", false, false, false); equal(host.values.get("text"), "first\nsecond", "Escape restores whole multiline focus session through onChange");
    }
    private static void builtinFavorites() {
        Fixture host = new Fixture(); ConfigHost.Option ordinary = host.add("ordinary", ConfigHost.Kind.TOGGLE, false); host.category("settings", "普通设置", ordinary);
        String[] ids = {"muonium.fav.lyrics", "muonium.fav.info", "muonium.fav.island", "muonium.fav.spectrum", "muonium.fav.acoustics", "muonium.fav.lyric.font", "muonium.fav.island.scale", "muonium.fav.spectrum.opacity"};
        String[] titles = {"桌面歌词", "歌曲信息卡", "灵动岛", "音符频谱", "场景声学", "歌词字号", "灵动岛大小", "频谱不透明度"}; List<ConfigHost.Option> switches = new ArrayList<>(), sliders = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) { ConfigHost.Option option = host.add(ids[i], i < 5 ? ConfigHost.Kind.TOGGLE : ConfigHost.Kind.SLIDER, i < 5 ? Boolean.TRUE : Double.valueOf(1)); host.titles.put(ids[i], titles[i]); (i < 5 ? switches : sliders).add(option); }
        host.builtins.add(new ConfigHost.Category("muonium.favorites", "HUD 叠加层", "", "star", List.of(new ConfigHost.Group("muonium.favorites/switches", "显示开关", switches), new ConfigHost.Group("muonium.favorites/sliders", "常用调节", sliders))));
        Context ctx = new Context(host, true); ctx.route = new Navigation.Route(Navigation.Page.FAVORITES, "muonium.favorites"); ctx.preferences.favorite("ordinary", true); ctx.preferences.favorite(ids[0], false); ctx.frame();
        equal(host.favoriteCategories().get(0).groups().stream().mapToInt(group -> group.options().size()).sum(), 8, "host built-in favorites keeps the eight stable setting IDs"); check(ctx.hits.containsKey("settings.toggle.muonium.fav.lyrics"), "built-in lyrics toggle appears without user pin"); check(!ctx.hits.containsKey("settings.toggle.ordinary"), "ordinary pinned settings are never substituted into the favorite tree"); check(ctx.painter.texts.stream().anyMatch(text -> text.text().equals("显示开关")) && ctx.painter.texts.stream().anyMatch(text -> text.text().equals("常用调节")), "source favorite subcategory headings retained");
        ctx.click("settings.toggle.muonium.fav.lyrics"); equal(host.values.get(ids[0]), false, "builtin favorite uses live actual Option setter"); host.values.put(ids[0], true); ctx.frame(); check(ctx.hits.containsKey("settings.toggle.muonium.fav.lyrics"), "builtin value is read live after external change");
        ctx.query = "频谱"; ctx.frame(); check(ctx.hits.containsKey("settings.toggle.muonium.fav.spectrum"), "favorite search includes built-in switch title"); check(ctx.edits.containsKey("settings.number.muonium.fav.spectrum.opacity"), "favorite search includes built-in adjustment title"); check(!ctx.hits.containsKey("settings.toggle.muonium.fav.info"), "favorite search narrows source tree");
        ctx.query = ""; ConfigHost.Option extra = host.add("muonium.fav.extra", ConfigHost.Kind.TOGGLE, true); host.builtins.add(new ConfigHost.Category("extra", "额外分类", "", "", List.of(new ConfigHost.Group("extra/group", "General", List.of(extra))))); ctx.frame(); check(ctx.hits.containsKey("settings.category.muonium.favorites") && ctx.hits.containsKey("settings.category.extra"), "favorite tree uses original category chips when it has multiple categories"); ctx.click("settings.category.extra"); equal(ctx.route.page(), Navigation.Page.FAVORITES, "favorite category chip preserves favorite page route"); equal(ctx.route.category(), "extra", "favorite category chip selects actual category id"); ctx.frame(); check(ctx.hits.containsKey("settings.toggle.muonium.fav.extra") && !ctx.hits.containsKey("settings.toggle.muonium.fav.lyrics"), "favorite chip filters source categories");
        Context settings = new Context(host, false); settings.frame(); check(settings.hits.containsKey("settings.toggle.ordinary") && !settings.hits.containsKey("settings.toggle.muonium.fav.lyrics"), "builtin favorites never leak into ordinary Settings categories");
    }
    private static Object sample(Visualizer v) { return switch(v) { case SWITCH, CHECKBOX -> true; case SLIDER, INHERITABLE_SLIDER, NUMBER -> 50d; case RANGE_SLIDER, RANGE_NUMBER -> new float[] {20, 80}; case TEXT, FILE -> "sample"; case TEXT_LIST, FILE_LIST, DRAGGABLE_LIST -> new String[] {"First", "Second"}; case COLOR -> new ColorValue(0x803399cc, false, 1, false, 0xffcc5533); case COLOR_LIST -> new int[] {0xff112233, 0xffaabbcc}; case NUMBER_LIST, SLIDER_LIST, NUMBER_CHAIN -> new float[] {20, 40}; case RANGE_SLIDER_LIST, RANGE_NUMBER_LIST -> new float[] {20, 30, 60, 90}; case MULTI_SELECT -> new boolean[] {true, false, true}; case KEYBIND -> Binding.none(); case BUTTON, INFO -> ""; default -> 1; }; }
    private static final class CountingPainter extends TracePainter {
        int measures; long revision; double scale = 1;
        @Override public double measure(String text, double size, boolean bold) { measures++; return super.measure(text, size, bold) * scale; }
        @Override public long textMetricsRevision() { return revision; }
    }
    private static void scrollingWork() {
        Fixture host = new Fixture(); List<ConfigHost.Option> options = new ArrayList<>();
        for (int index = 0; index < 48; index++) {
            String id = "scroll" + index; options.add(host.add(id, ConfigHost.Kind.TOGGLE, true));
            host.titles.put(id, id + " long setting label ".repeat(9));
        }
        host.category("performance", "Performance", options.toArray(ConfigHost.Option[]::new));
        Context ctx = new Context(host, false); CountingPainter painter = new CountingPainter(); ctx.drawing = painter;
        ctx.frame(); int cold = painter.measures; double originalRange = ctx.maximum;
        painter.measures = 0; ctx.scroll = Math.min(100, originalRange); ctx.frame(); int warm = painter.measures;
        check(warm * 3 < cold, "warm scrolling reuses measured wraps: " + cold + " -> " + warm);
        check(painter.layers.isEmpty(), "enabled rows do not allocate opacity layers");
        ctx.scroll = 0; host.disabled.put("scroll0", "disabled"); ctx.frame();
        check(painter.layers.stream().anyMatch(alpha -> Math.abs(alpha - .65) < .000001), "disabled row retains opacity compositing");
        host.disabled.clear(); host.titles.put("scroll0", "Short label"); ctx.frame();
        check(ctx.maximum < originalRange, "changed live title recomputes wrapping");
        double shortRange = ctx.maximum; painter.scale = 2; painter.revision++; ctx.frame();
        check(ctx.maximum > shortRange, "font revision invalidates wrapping and changes layout");
        System.out.println("Settings scroll measurement calls: cold=" + cold + ", warm=" + warm);
    }
    private static void allVisualizers() {
        for (Visualizer visualizer : Visualizer.values()) {
            Fixture host = new Fixture(); ConfigHost.Option option = host.add("fixture", ConfigHost.Kind.CHOICE, sample(visualizer)); OptionStyle style = host.styles.get("fixture"); style.visualizer = visualizer; style.chroma = style.gradient = true; host.category("c", "Settings", option); Context ctx = new Context(host, false); ctx.frame(); check(host.errors.isEmpty(), "mount " + visualizer); check(ctx.painter.rectangles.size() > 0, "draw " + visualizer);
        }
    }
    public static void main(String[] args) { numeric(); colors(); routingAndLiveState(); sliderAndPopover(); colorPopup(); listsAndAsync(); colorListContainer(); capture(); riskInteractions(); confirmationAndMenus(); boundsAndGestureIdentity(); inheritedSlider(); nativeDrawingContracts(); multilineSetting(); builtinFavorites(); allVisualizers(); scrollingWork(); System.out.println("Settings harness passed: " + assertions + " checks, " + Visualizer.values().length + " visualizers; no game launched."); }
}
