package com.muoniumplayer.configskija.appearance;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.pages.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

/** Standalone Java 17 tests: no game, loader, Skija native or Compose runtime required. */
public final class AppearanceParityTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void near(double expected, double actual, double tolerance, String message) {
        check(
                Math.abs(expected - actual) <= tolerance,
                message + ": " + actual + " expected " + expected);
    }

    public static void main(String[] args) throws Exception {
        metadata(
                args.length == 0
                        ? Path.of("E:/oneconfig lite/MuoniumConfig-src")
                        : Path.of(args[0]));
        dependencies();
        search();
        theme();
        motion();
        recording();
        pages();
        picker();
        System.out.println("AppearanceParityTest: " + checks + " assertions passed");
    }

    private static AppearanceSchema.Setting pref(String key) {
        return AppearanceSchema.PREFERENCES.stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static void metadata(Path root) throws Exception {
        String source =
                Files.readString(
                        root.resolve(
                                "muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/internal/OneConfigConfig.java"));
        Pattern declaration =
                Pattern.compile(
                        "@(Switch|Slider|Dropdown|Number|Keybind)\\((.*?)\\)\\s*(?:/\\*\\*.*?\\*/\\s*)?public static ([\\w<>]+) (\\w+)\\s*=\\s*([^;]+);",
                        Pattern.DOTALL);
        Matcher m = declaration.matcher(source);
        int i = 0;
        UiPreferences empty = new UiPreferences(Map.of(), () -> {});
        while (m.find()) {
            AppearanceSchema.Setting actual = AppearanceSchema.PREFERENCES.get(i++);
            String key = m.group(4), annotation = m.group(1), initial = m.group(5).trim();
            check(actual.key().equals(key), "declaration order: " + key);
            String kind =
                    switch (annotation) {
                        case "Switch" -> "TOGGLE";
                        case "Slider" -> "SLIDER";
                        case "Number" -> "NUMBER";
                        case "Dropdown" -> "CHOICE";
                        default -> "KEYBIND";
                    };
            check(actual.kind().name().equals(kind), "kind: " + key);
            if (!kind.equals("KEYBIND")) {
                if (kind.equals("TOGGLE"))
                    check(
                            actual.value(empty).equals(Boolean.parseBoolean(initial)),
                            "default: " + key);
                else
                    near(
                            Double.parseDouble(initial.replace("f", "")),
                            ((Number) actual.value(empty)).doubleValue(),
                            1e-8,
                            "default: " + key);
            }
            if (kind.equals("KEYBIND")) {
                Matcher physical = Pattern.compile("getKey(\\w+)\\(\\)").matcher(initial);
                String expected = "NONE";
                if (physical.find()) {
                    String modifier =
                            initial.contains("HUD_REDO_MODS")
                                    ? "ACTION+SHIFT+"
                                    : initial.contains("HUD_ACTION_MODS")
                                                    || initial.contains("getActionModifier")
                                            ? "ACTION+"
                                            : "";
                    expected =
                            AppearanceBindings.resolveAction(
                                    modifier + physical.group(1).toUpperCase(Locale.ROOT));
                }
                check(expected.equals(actual.value(empty)), "original chord default: " + key);
            }
            for (String bound : List.of("min", "max", "step")) {
                Matcher value = Pattern.compile(bound + " = ([0-9.]+)f").matcher(m.group(2));
                if (value.find())
                    near(
                            Double.parseDouble(value.group(1)),
                            switch (bound) {
                                case "min" -> actual.min();
                                case "max" -> actual.max();
                                default -> actual.step();
                            },
                            1e-8,
                            bound + ": " + key);
            }
            if (annotation.equals("Dropdown")) {
                Matcher options =
                        Pattern.compile("options = \\{(.*?)\\}", Pattern.DOTALL)
                                .matcher(m.group(2));
                check(options.find(), "options exist");
                int count = 0;
                Matcher option =
                        Pattern.compile("\"oneconfig.preferences").matcher(options.group(1));
                while (option.find()) count++;
                check(actual.choices().size() == count, "choice indices: " + key);
            }
        }
        check(
                i == 50 && i == AppearanceSchema.PREFERENCES.size(),
                "all visible preferences imported");
        check(pref("oneConfigKeybind").value(empty).equals("NONE"), "open key remains unbound");
        check(
                pref("hudDeleteKeybind").value(empty).equals("DELETE"),
                "delete has no action modifier");
        check(
                pref("hudRedoKeybind")
                        .value(empty)
                        .equals(AppearanceBindings.resolveAction("ACTION+SHIFT+Z")),
                "redo default");
        check(AppearanceSchema.THEMES.size() == 2, "active theme is hidden from settings list");
        check(
                new LinkedHashSet<>(
                                AppearanceSchema.PREFERENCES.stream()
                                        .map(AppearanceSchema.Setting::group)
                                        .toList())
                        .equals(
                                new LinkedHashSet<>(
                                        List.of("界面", "HUD 编辑器", "搜索", "动画", "音效", "通用"))),
                "source subcategory order");
        String originalThemes =
                Files.readString(
                        root.resolve(
                                "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Impls.kt"));
        for (Theme t : Theme.BUILTIN) {
            check(
                    originalThemes.contains(t.id()) && originalThemes.contains(t.title()),
                    "builtin identifiers: " + t.id());
            String name = t.title().replace(" ", "");
            Matcher block =
                    Pattern.compile(
                                    "val " + name + " = UITheme\\((.*?)(?=\\nval |\\z)",
                                    Pattern.DOTALL)
                            .matcher(originalThemes);
            check(block.find(), "palette source block: " + name);
            Matcher colors =
                    Pattern.compile("Color\\(0x([0-9a-fA-F]+)\\)|Color\\.Black")
                            .matcher(block.group(1));
            int[] expected = {
                t.page(),
                t.sidebar(),
                t.chip(),
                t.card(),
                t.component(),
                t.popup(),
                t.border(),
                t.text(),
                t.secondary(),
                t.accentText(),
                t.shadow(),
                t.thumb(),
                t.favorite()
            };
            int n = 0;
            while (colors.find()) {
                check(n < expected.length, "palette color count");
                int actual =
                        colors.group(1) == null
                                ? 0xFF000000
                                : (int) Long.parseLong(colors.group(1), 16);
                check(actual == expected[n++], "original palette color: " + name + " / " + n);
            }
            check(n == expected.length, "complete palette: " + name);
        }
    }

    private static void dependencies() {
        UiPreferences p = new UiPreferences(Map.of(), () -> {});
        p.set("enableUISounds", false);
        for (String key :
                List.of(
                        "enableUIMenuSounds",
                        "enableUIClickSounds",
                        "enableUISliderSounds",
                        "enableHudEditorSounds",
                        "uiSoundVolume")) check(!pref(key).enabled(p), "sound dependency: " + key);
        check(pref("enableUIAmbience").enabled(p), "ambience independent of click master");
        p.set("enableUIAmbience", false);
        check(!pref("enableUIMusicDucking").enabled(p), "ducking dependency");
        check(!pref("uiAmbienceVolume").enabled(p), "ambience volume dependency");
        pref("uiSoundVolume").set(p, .2);
        near(
                1,
                ((Number) pref("uiSoundVolume").value(p)).doubleValue(),
                0,
                "disabled edits blocked");
        p.set("enableUISounds", true);
        pref("uiSoundVolume").set(p, .2);
        near(.2, ((Number) pref("uiSoundVolume").value(p)).doubleValue(), 0, "sound restored");
        p.set("rememberLastPage", 0);
        check(!pref("restoreHudEditor").enabled(p), "restore editor disabled for never");
        near(.65, pref("uiSoundVolume").snap(.63), 1e-10, "slider rounds to original step");
        near(.05, pref("animationTime").snap(-1), 0, "slider lower bound");
        near(200, pref("uiScale").snap(999), 0, "slider upper bound");
        p.set("pageOpacity", 17);
        pref("pageOpacity").reset(p);
        near(88, p.number("pageOpacity", 0), 0, "per setting reset");
    }

    private static void search() {
        check(
                AppearanceSchema.searchMatches("animationTime", "animtionTime", 2),
                "fuzzy id search");
        check(
                !AppearanceSchema.searchMatches("animationTime", "animtionTime", 0),
                "zero distance exact substring only");
        check(
                AppearanceSchema.searchMatches("show_page-animation/test", "animation", 0),
                "substring");
        check(pref("pageOpacity").matches("内容面板", 0), "localized title search");
        check(pref("uiSoundVolume").matches("音效", 0), "group search");
        check(!pref("pageOpacity").matches("nonexistentwombat", 2), "irrelevant search");
    }

    private static void theme() {
        check(Theme.byId("Minecraft Light") == Theme.BUILTIN[3], "persisted original name");
        check(Theme.byId("unknown") == Theme.BUILTIN[0], "unknown name falls back");
        near(20, Theme.BUILTIN[0].backgroundRadius(), 0, "shell radius");
        near(16, Theme.BUILTIN[0].cardRadius(), 0, "card radius");
        near(6, Theme.BUILTIN[0].checkboxRadius(), 0, "control radius");
        near(4, Theme.BUILTIN[0].modIconRadius(), 0, "extra mod icon shape");
        near(8, Theme.BUILTIN[0].scrollBarRadius(), 0, "extra scrollbar shape");
        for (Theme t : Theme.BUILTIN) {
            check(t.controlTrack() == 0xFF74777F, "track default");
            check(t.shadow() == 0xFF000000, "shadow default");
            check(t.shadowEnabled() == t.rounded(), "shadow flag");
        }
        check(
                Theme.BUILTIN[2].circleRadius(15) == 0 && Theme.BUILTIN[0].circleRadius(15) == 7.5,
                "circle switches instantly");
        check(Theme.mixOklab(0xFFFF0000, 0xFF0000FF, 0) == 0xFFFF0000, "Oklab from endpoint");
        check(Theme.mixOklab(0xFFFF0000, 0xFF0000FF, 1) == 0xFF0000FF, "Oklab to endpoint");
        check(
                Theme.mixOklab(0xFFFF0000, 0xFF0000FF, .5) == 0xFF8C53A2,
                "Oklab reference red-blue midpoint");
        check(Theme.mix(0, 0xFFFFFFFF, .5) == 0x7F7F7F7F, "legacy mix unchanged");
        Theme midpoint = Theme.interpolate(Theme.BUILTIN[0], Theme.BUILTIN[2], .5);
        near(10, midpoint.backgroundRadius(), 0, "shape transition");
        check(
                !midpoint.rounded() && !midpoint.shadowEnabled() && midpoint.modIconRadius() == 0,
                "noninterpolated properties use target");
        Context c = new Context();
        Theme start = Theme.transition(c);
        c.prefs.set("activeTheme", "PolyGlass Light");
        Theme switched = Theme.transition(c);
        check(start.page() == switched.page(), "theme begins at sampled value");
        c.animationScope = "different-page/";
        c.now += .175;
        Theme halfway = Theme.transition(c);
        check(
                halfway.page() != start.page() && halfway.page() != Theme.BUILTIN[1].page(),
                "theme intermediate frame remains continuous across routed animation scope");
        c.now += .175;
        check(Theme.transition(c).page() == Theme.BUILTIN[1].page(), "350ms theme target");
        UiPreferences p = new UiPreferences(Map.of("accentColor", "#FF00FF00"), () -> {});
        check(Theme.accent(p, 0) == 0xFF00FF00, "accent static");
        p.set("accentColor.chroma", true);
        check(Theme.accent(p, 0) == Theme.accent(p, 10), "10s chroma cycle");
        p.set("accentColor.gradientEnabled", true);
        p.set("accentColor.gradientArgb", "#FF0000FF");
        check(Theme.accentEnd(p, 2) == Theme.accent(p, 2), "chroma suppresses gradient");
        p.set("accentColor.chroma", false);
        check(Theme.accentEnd(p, 2) == 0xFF0000FF, "second gradient color persists");
        check(Theme.alpha(0x10223344, .8) == 0xCC223344, "opacity replaces alpha");
    }

    private static void motion() {
        AppearanceMotion s = new AppearanceMotion();
        s.spring("thumb", 3, 0, true);
        s.spring("thumb", 24, 0, true);
        double early = s.spring("thumb", 24, .05, true);
        check(early > 3 && early < 24, "critical spring in flight");
        double before = s.spring("thumb", 24, .1, true), same = s.spring("thumb", 3, .1, true);
        near(before, same, 0, "retarget does not jump");
        double later = s.spring("thumb", 3, .12, true);
        check(later != same, "retarget continues with velocity");
        near(24, s.spring("thumb", 24, .13, false), 0, "animations false applies immediately");
    }

    private static void recording() {
        var r = new AppearanceBindings.Recorder();
        r.begin();
        r.key("LEFT_CONTROL", true);
        r.key("Z", true);
        r.key("Z", false);
        check(r.take() == null && r.active(), "recording waits for modifiers to release");
        r.key("LEFT_CONTROL", false);
        check("CTRL+Z".equals(r.take()), "Ctrl+Z chord");
        r.begin();
        r.key("RIGHT_SHIFT", true);
        r.key("RIGHT_SHIFT", false);
        check("KEY:RIGHT_SHIFT".equals(r.take()), "modifier-only literal key");
        r.begin();
        r.key("ALT", true);
        r.mouse(1, true);
        r.mouse(1, false);
        r.key("ALT", false);
        check("ALT+MOUSE:1".equals(r.take()), "mouse and modifier");
        r.begin();
        r.key("ESCAPE", true);
        check("NONE".equals(r.take()), "escape unbinds");
        r.begin();
        r.key("DELETE", true);
        check("NONE".equals(r.take()), "empty delete unbinds");
        check(AppearanceBindings.conflicts("CTRL+Z", "Z+CTRL"), "unordered conflict");
        check(!AppearanceBindings.conflicts("NONE", "NONE"), "unbound no conflict");
    }

    private static void pages() {
        Context c = new Context();
        UiPage page = AppearancePages.themes();
        c.frame(page);
        check(c.draw.images.size() == 4, "four SVG preview image calls");
        check(c.hit.containsKey("appearance.theme.minecraft-dark"), "preview hit");
        c.click("appearance.theme.minecraft-dark");
        check(c.prefs.get("activeTheme", "").equals("Minecraft Dark"), "card switches theme");
        check(
                c.sounds.get(c.sounds.size() - 1).equals("theme_changed"),
                "cross sound-family event");
        c.frame(page);
        c.click("appearance.theme.minecraft-light");
        check(c.sounds.get(c.sounds.size() - 1).equals("click"), "same sound-family click");
        c = new Context();
        page = AppearancePages.preferences();
        c.frame(page);
        check(c.draw.labels.contains("界面"), "GUI section heading");
        check(c.hit.containsKey("appearance.keybindClosesGui"), "switch hit");
        c.click("appearance.keybindClosesGui");
        check(c.prefs.flag("keybindClosesGui", false), "switch writes original key");
        c.query = "pageOpacity";
        c.frame(page);
        check(c.hit.containsKey("appearance.pageOpacity.track"), "searched slider present");
        check(!c.hit.containsKey("appearance.keybindClosesGui"), "search filters unrelated switch");
        Rect track = c.hit.get("appearance.pageOpacity.track").r();
        c.mouseX = track.right() - 9.5;
        c.mouseY = track.y() + 9;
        c.click("appearance.pageOpacity.track");
        near(100, c.prefs.number("pageOpacity", 0), 0, "track maps pointer to upper endpoint");
        c.drag.accept(track.x() + 9.5, c.mouseY);
        near(0, c.prefs.number("pageOpacity", 100), 0, "track maps pointer to lower endpoint");
        c = new Context();
        page = AppearancePages.preferences();
        c.query = "pageOpacity";
        c.frame(page);
        Rect sameTrack = c.hit.get("appearance.pageOpacity.track").r();
        c.mouseX = sameTrack.x() + 9.5 + (sameTrack.w() - 19) * .88;
        c.mouseY = sameTrack.y() + 9;
        c.click("appearance.pageOpacity.track");
        check(
                c.sounds.equals(List.of("slider_tick")),
                "first gesture tick plays at unchanged value");
        c.drag.accept(c.mouseX + 1, c.mouseY);
        check(c.sounds.size() == 1, "tick throttled inside 70ms");
        c.now += .071;
        c.drag.accept(sameTrack.right() - 9.5, c.mouseY);
        check(c.sounds.size() == 2, "new step ticks after throttle");
        c = new Context();
        page = AppearancePages.preferences();
        c.query = "rememberLastPage";
        c.frame(page);
        c.click("appearance.rememberLastPage.choice");
        check(c.overlay != null, "dropdown opens overlay");
        c.frameOverlay();
        c.click("appearance.rememberLastPage.option.4");
        near(4, c.prefs.number("rememberLastPage", 0), 0, "dropdown stores original index");
        for (var setting : AppearanceSchema.PREFERENCES) {
            Context all = new Context();
            all.prefs.set("searchDistance", 0);
            all.query = setting.key();
            UiPage single = AppearancePages.preferences();
            all.frame(single);
            String suffix =
                    switch (setting.kind()) {
                        case TOGGLE -> "";
                        case SLIDER -> ".track";
                        case NUMBER -> ".input";
                        case CHOICE -> ".choice";
                        case KEYBIND -> ".keybind";
                        case COLOR -> ".swatch";
                    };
            check(
                    all.hit.containsKey("appearance." + setting.key() + suffix),
                    "every source control is reachable: " + setting.key());
        }
        c = new Context();
        page = AppearancePages.preferences();
        c.query = "searchDistance";
        c.frame(page);
        c.click("appearance.searchDistance.input");
        page.key(c, ConfigView.Key.A, "", true, false, false);
        page.key(c, ConfigView.Key.NONE, "9", false, false, false);
        near(9, c.prefs.number("searchDistance", 0), 0, "inline numeric edit persists");
        page.key(c, ConfigView.Key.NONE, "abc", false, false, false);
        near(9, c.prefs.number("searchDistance", 0), 0, "numeric filter removes letters");
        c = new Context();
        page = AppearancePages.preferences();
        c.prefs.set("enableUISounds", false);
        c.query = "enableUIMenuSounds";
        c.frame(page);
        check(
                !c.hit.containsKey("appearance.enableUIMenuSounds"),
                "disabled control has no toggle hit");
        c = new Context();
        page = AppearancePages.preferences();
        c.query = "hudRedoKeybind";
        c.frame(page);
        c.click("appearance.hudRedoKeybind.keybind");
        var raw = (AppearancePages.RawKeyInput) page;
        raw.rawKey(c, "LEFT_CONTROL", true);
        raw.rawKey(c, "LEFT_SHIFT", true);
        raw.rawKey(c, "A", true);
        raw.rawKey(c, "A", false);
        raw.rawKey(c, "LEFT_SHIFT", false);
        raw.rawKey(c, "LEFT_CONTROL", false);
        check(
                c.prefs.get("hudRedoKeybind", "").equals("CTRL+SHIFT+A"),
                "page records configurable chord");
    }

    private static void picker() {
        Context c = new Context();
        UiPage page = AppearancePages.themes();
        c.frame(page);
        c.click("appearance.accentColor.swatch");
        check(c.overlay != null, "color picker opens");
        c.frameOverlay();
        c.click("appearance.color.chroma");
        check(c.prefs.flag("accentColor.chroma", false), "chroma persists");
        c.frameOverlay();
        c.click("appearance.color.gradient");
        check(c.prefs.flag("accentColor.gradientEnabled", false), "gradient persists");
        check(
                !c.prefs.get("accentColor.gradientArgb", "").equals(c.prefs.get("accentColor", "")),
                "gradient auto generates second hue");
        c.frameOverlay();
        check(c.hit.containsKey("appearance.color.speed"), "chroma speed bar");
        check(c.hit.containsKey("appearance.color.gradient.input"), "gradient hex field");
        Rect sv = c.hit.get("appearance.color.sv").r();
        c.mouseX = sv.x();
        c.mouseY = sv.y();
        c.click("appearance.color.sv");
        check(Theme.color(c.prefs.get("accentColor", ""), 0) == 0xFFFFFFFF, "SV white corner");
    }

    record Hit(Rect r, Runnable action) {}

    static final class Draw implements Painter, AppearancePages.Images {
        final List<String> labels = new ArrayList<>(), images = new ArrayList<>();

        public void save() {}

        public void restore() {}

        public void translate(double x, double y) {}

        public void scale(double x, double y) {}

        public void clip(Rect r, double radius) {}

        public void rect(Rect r, double radius, int color) {}

        public void outline(Rect r, double radius, double width, int color) {}

        public void line(double x1, double y1, double x2, double y2, double width, int color) {}

        public void text(String text, double x, double y, double size, boolean bold, int color) {
            labels.add(text);
        }

        public double measure(String text, double size, boolean bold) {
            return text.codePoints().mapToDouble(ch -> ch > 255 ? size : size * .55).sum();
        }

        public void image(String path, Rect r) {
            images.add(path);
        }
    }

    static final class Context implements PageContext {
        final UiPreferences prefs = new UiPreferences(Map.of(), () -> {});
        final Motion motion = new Motion();
        final Draw draw = new Draw();
        final Map<String, Hit> hit = new LinkedHashMap<>();
        final List<String> sounds = new ArrayList<>();
        double now = 1, scroll, max, mouseX, mouseY;
        String query = "", clipboard = "", animationScope = "";
        Rect content;
        UiPage overlay;
        BiConsumer<Double, Double> drag;
        final ConfigHost host =
                new ConfigHost() {
                    public List<Category> categories() {
                        return List.of();
                    }

                    public List<HudElement> huds() {
                        return List.of();
                    }

                    public void persist() {}

                    public void close() {}

                    public String clipboard() {
                        return clipboard;
                    }

                    public void clipboard(String text) {
                        clipboard = text;
                    }

                    public void error(String action, Throwable error) {
                        throw new AssertionError(action, error);
                    }
                };

        void frame(UiPage page) {
            content = new Rect(289, 88, 1077, 593);
            hit.clear();
            draw.labels.clear();
            draw.images.clear();
            page.render(this);
        }

        void frameOverlay() {
            hit.clear();
            overlay.render(this);
        }

        void click(String id) {
            Hit h = hit.get(id);
            check(h != null, "hit exists: " + id);
            h.action.run();
        }

        public Painter painter() {
            return draw;
        }

        public Theme theme() {
            return Theme.active(prefs);
        }

        public int accent() {
            return Theme.accent(prefs, now);
        }

        public ConfigHost host() {
            return host;
        }

        public UiPreferences preferences() {
            return prefs;
        }

        public String query() {
            return query;
        }

        public Navigation.Route route() {
            return new Navigation.Route(Navigation.Page.PREFERENCES);
        }

        public double mouseX() {
            return mouseX;
        }

        public double mouseY() {
            return mouseY;
        }

        public double width() {
            return 1391;
        }

        public double height() {
            return 700;
        }

        public double time() {
            return now;
        }

        public double delta() {
            return 1d / 60;
        }

        public boolean control() {
            return false;
        }

        public boolean shift() {
            return false;
        }

        public boolean alt() {
            return false;
        }

        public boolean textFocused() {
            return false;
        }

        public int nativeKeyCode() {
            return 0;
        }

        public Rect content() {
            return content;
        }

        public void content(Rect r) {
            content = r;
        }

        public double scroll() {
            return scroll;
        }

        public void scroll(double v) {
            scroll = Geometry.clamp(v, 0, max);
        }

        public void scrollRange(double v) {
            max = Math.max(0, v);
            scroll = Geometry.clamp(scroll, 0, max);
        }

        public void hit(String id, Rect r, Runnable a, String tip) {
            hit.put(id, new Hit(r, a));
        }

        public void contentHit(String id, Rect r, Runnable a, String tip) {
            if (r.intersects(content)) hit(id, r, a, tip);
        }

        public void tooltip(String id, Rect r, String text) {}

        public void startDrag(BiConsumer<Double, Double> update, Runnable finish) {
            drag = update;
            update.accept(mouseX, mouseY);
        }

        public void navigate(Navigation.Route route) {}

        public void toast(String message) {}

        public void sound(String event) {
            sounds.add(event);
        }

        public void edit(ConfigHost.Option option, Object value) {
            option.set(value);
        }

        public void beginEdit(String name) {}

        public void commitEdit() {}

        public void cancelEdit() {}

        public void textField(
                String id, TextEdit edit, Rect r, String placeholder, Consumer<String> changed) {}

        public void textDialog(
                String title,
                String hint,
                String initial,
                String action,
                Consumer<String> submit) {}

        public void confirm(String title, String description, Runnable accepted) {}

        public void overlay(UiPage page) {
            overlay = page;
        }

        public void dismissOverlay() {
            if (overlay != null) overlay.leave(this);
            overlay = null;
        }

        public void close() {}

        public double animate(String key, double target, double seconds, Motion.Easing easing) {
            return motion.value(animationScope + key, target, seconds, easing, now);
        }
    }
}
