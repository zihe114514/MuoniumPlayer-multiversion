package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.pages.*;

import java.util.*;

/** Rebinding is tested through the same raw callbacks as ConfigView, not only by chord string comparison. */
public final class HudBindingsTest {
    private static int assertions;

    static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        eachPreference();
        shiftAndSides();
        pureAndMouse();
        nativeState();
        studio();
        System.out.println("HudBindingsTest passed: " + assertions + " rebinding assertions");
    }

    static void none(UiPreferences prefs) {
        for (String id : HudBindings.COMMANDS.keySet()) prefs.set(id, "NONE");
    }

    static void eachPreference() {
        for (var entry : HudBindings.COMMANDS.entrySet()) {
            HudParityTest.Context c = new HudParityTest.Context(new HudParityTest.Host());
            none(c.preferences);
            c.preferences.set(entry.getKey(), "ALT+Q");
            HudBindings binds = new HudBindings();
            List<HudStudio.Command> calls = new ArrayList<>();
            binds.key(c, "LCONTROL", true, true, calls::add);
            binds.key(c, "C", true, true, calls::add);
            check(calls.isEmpty(), entry.getKey() + " no fixed Ctrl-letter command remains");
            binds.key(c, "C", false, true, calls::add);
            binds.key(c, "LCONTROL", false, true, calls::add);
            binds.key(c, "RMENU", true, true, calls::add);
            check(
                    binds.key(c, "Q", true, true, calls::add),
                    entry.getKey() + " custom chord consumed");
            check(
                    calls.equals(List.of(entry.getValue())),
                    entry.getKey() + " custom chord dispatches correct HUD command");
            binds.key(c, "Q", true, true, calls::add);
            check(calls.size() == 1, "held-key repeat cannot duplicate " + entry.getKey());
            check(
                    !binds.mouse(c, 0, true, true, calls::add),
                    "held keyboard chord does not consume unrelated mouse: " + entry.getKey());
            binds.mouse(c, 0, false, true, calls::add);
            check(
                    !binds.key(c, "LEFT", true, true, calls::add),
                    "held keyboard chord does not consume unrelated arrow: " + entry.getKey());
            binds.key(c, "LEFT", false, true, calls::add);
            binds.key(c, "Q", false, true, calls::add);
            binds.key(c, "Q", true, true, calls::add);
            check(calls.size() == 2, "release rearms " + entry.getKey());
        }
    }

    static void shiftAndSides() {
        for (String side : List.of("LCONTROL", "RCONTROL")) {
            HudParityTest.Context c = new HudParityTest.Context(new HudParityTest.Host());
            none(c.preferences);
            c.preferences.set("hudUndoKeybind", "CTRL+Z");
            c.preferences.set("hudRedoKeybind", "CTRL+SHIFT+Z");
            c.preferences.set("hudRedoAltKeybind", "CTRL+Y");
            HudBindings binds = new HudBindings();
            List<HudStudio.Command> calls = new ArrayList<>();
            binds.key(c, side, true, true, calls::add);
            binds.key(c, "RSHIFT", true, true, calls::add);
            binds.key(c, "Z", true, true, calls::add);
            check(
                    calls.equals(List.of(HudStudio.Command.REDO)),
                    "Ctrl+Shift+Z never also undoes using " + side);
            binds.key(c, "Z", false, true, calls::add);
            binds.key(c, "RSHIFT", false, true, calls::add);
            binds.key(c, "Z", true, true, calls::add);
            check(
                    calls.equals(List.of(HudStudio.Command.REDO, HudStudio.Command.UNDO)),
                    "plain undo after Shift released using " + side);
            binds.key(c, "Z", false, true, calls::add);
            binds.key(c, "Y", true, true, calls::add);
            check(
                    calls.get(calls.size() - 1) == HudStudio.Command.REDO,
                    "alternate redo preference works");
        }
        for (String side : List.of("LMETA", "RMETA")) {
            HudParityTest.Context c = new HudParityTest.Context(new HudParityTest.Host());
            none(c.preferences);
            c.preferences.set("hudVisibilityKeybind", "SUPER+H");
            HudBindings binds = new HudBindings();
            List<HudStudio.Command> calls = new ArrayList<>();
            binds.key(c, side, true, true, calls::add);
            binds.key(c, "H", true, true, calls::add);
            check(
                    calls.equals(List.of(HudStudio.Command.TOGGLE_VISIBILITY)),
                    "both Super sides work: " + side);
        }
        HudParityTest.Context pointer = new HudParityTest.Context(new HudParityTest.Host());
        HudBindings modifiers = new HudBindings();
        modifiers.key(pointer, "RCONTROL", true, false, command -> {});
        check(
                modifiers.actionModifier(pointer)
                        == !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"),
                "pointer selection uses platform action modifier with both physical sides");
        check(
                HudBindings.parse("ACTION+E").modifiers()
                        == (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                                ? 8
                                : 1),
                "action modifier follows original platform preference semantics");
    }

    static void pureAndMouse() {
        for (var entry : HudBindings.COMMANDS.entrySet()) {
            HudParityTest.Context context = new HudParityTest.Context(new HudParityTest.Host());
            none(context.preferences);
            context.preferences.set(entry.getKey(), "KEY:LSHIFT");
            HudBindings matcher = new HudBindings();
            List<HudStudio.Command> events = new ArrayList<>();
            matcher.key(context, "LSHIFT", true, true, events::add);
            check(
                    events.equals(List.of(entry.getValue())),
                    "explicit pure Shift remains valid for " + entry.getKey());
        }

        HudParityTest.Context c = new HudParityTest.Context(new HudParityTest.Host());
        none(c.preferences);
        c.preferences.set("hudLockKeybind", "KEY:LCONTROL+KEY:LSHIFT");
        HudBindings binds = new HudBindings();
        List<HudStudio.Command> calls = new ArrayList<>();
        binds.key(c, "LCONTROL", true, true, calls::add);
        check(calls.isEmpty(), "pure modifier chord waits for all physical modifier keys");
        binds.key(c, "LSHIFT", true, true, calls::add);
        check(
                calls.equals(List.of(HudStudio.Command.LOCK)),
                "recorded pure modifier chord triggers");
        binds.key(c, "LSHIFT", true, true, calls::add);
        check(calls.size() == 1, "pure modifier repeat is latched");
        binds.key(c, "LSHIFT", false, true, calls::add);
        binds.key(c, "LSHIFT", true, true, calls::add);
        check(calls.size() == 2, "pure modifier release rearms");
        binds.clear();
        calls.clear();
        none(c.preferences);
        c.preferences.set("hudCopyKeybind", "CTRL+K+MOUSE:2");
        binds.key(c, "RCONTROL", true, true, calls::add);
        binds.key(c, "K", true, true, calls::add);
        check(calls.isEmpty(), "mixed mouse chord waits for mouse");
        check(
                binds.mouse(c, 2, true, true, calls::add),
                "matching mouse chord consumed before canvas gesture");
        check(
                calls.equals(List.of(HudStudio.Command.COPY)),
                "mixed keyboard/mouse binding dispatches");
        binds.mouse(c, 2, true, true, calls::add);
        check(calls.size() == 1, "held mouse not repeated");
        binds.mouse(c, 2, false, true, calls::add);
        binds.mouse(c, 2, true, true, calls::add);
        check(calls.size() == 2, "mouse release rearms binding");
        binds.clear();
        calls.clear();
        none(c.preferences);
        c.preferences.set("hudDeleteKeybind", "MOUSE:1");
        binds.mouse(c, 1, true, true, calls::add);
        check(calls.equals(List.of(HudStudio.Command.DELETE)), "mouse-only binding supported");
        binds.clear();
        calls.clear();
        none(c.preferences);
        c.preferences.set("hudVisibilityKeybind", "KEY:CTRL");
        binds.key(c, "RCONTROL", true, true, calls::add);
        check(
                calls.equals(List.of(HudStudio.Command.TOGGLE_VISIBILITY)),
                "generic pure modifier KEY:CTRL accepts right Control");
        check(
                !HudBindings.parse("MOUSE:bad").bound()
                        && !HudBindings.parse("NONE").bound()
                        && !HudBindings.parse("CTRL+").bound(),
                "invalid/unbound canonical chords never activate");
        check(
                HudBindings.token(29).equals("LCONTROL")
                        && HudBindings.token(157).equals("RCONTROL")
                        && HudBindings.token(219).equals("LMETA")
                        && HudBindings.token(220).equals("RMETA"),
                "local legacy map covers both action modifier sides");
        check(
                HudBindings.parse("CTRL+KEY:44").keys().equals(Set.of("Z")),
                "numeric legacy keys map through the verified local namespace");
    }

    static final class Keys implements KeyBindings, KeyBindings.InputState {
        final Set<Integer> keys = new HashSet<>(), mouse = new HashSet<>();

        public List<Entry> entries() {
            return List.of();
        }

        public void set(String id, Chord chord) {}

        public void reset(String id) {}

        public String display(Chord chord) {
            return chord.toString();
        }

        public boolean keyDown(int key) {
            return keys.contains(key);
        }

        public boolean mouseDown(int button) {
            return mouse.contains(button);
        }
    }

    static final class Host extends HudParityTest.Host implements LibraryServices.Keys {
        final Keys keys = new Keys();

        public KeyBindings keyBindings() {
            return keys;
        }
    }

    static void nativeState() {
        Host host = new Host();
        HudParityTest.Context c = new HudParityTest.Context(host);
        none(c.preferences);
        c.preferences.set("hudVisibilityKeybind", "SUPER+H");
        HudBindings binds = new HudBindings();
        List<HudStudio.Command> calls = new ArrayList<>();
        host.keys.keys.add(220);
        host.keys.keys.add(35);
        binds.key(c, "H", true, true, calls::add);
        check(
                calls.equals(List.of(HudStudio.Command.TOGGLE_VISIBILITY)),
                "modifier held before page opens is read from existing InputState");
        host.keys.keys.remove(35);
        binds.poll(c);
        host.keys.keys.add(35);
        binds.key(c, "H", true, true, calls::add);
        check(
                calls.size() == 2,
                "polled release repairs missed event when overlay captured key-up");
        calls.clear();
        binds.clear();
        host.keys.keys.clear();
        none(c.preferences);
        c.preferences.set("hudCopyKeybind", "CTRL+MOUSE:0");
        host.keys.keys.add(157);
        binds.mouse(c, 0, true, true, calls::add);
        check(
                calls.equals(List.of(HudStudio.Command.COPY)),
                "existing native InputState covers modifier plus mouse chord");
    }

    static void studio() {
        HudParityTest.Host host = new HudParityTest.Host();
        HudParityTest.Context c = new HudParityTest.Context(host);
        none(c.preferences);
        c.preferences.set("hudVisibilityKeybind", "ALT+Q");
        HudStudio studio = new HudStudio(host, c.preferences);
        studio.select(List.of("host-a"), false);
        studio.render(c);
        HudElement element = host.huds().get(0);
        studio.rawKey(c, "LCONTROL", true);
        studio.rawKey(c, "H", true);
        check(!element.hidden(), "old Ctrl+H removed after rebinding");
        studio.rawKey(c, "H", false);
        studio.rawKey(c, "LCONTROL", false);
        studio.rawKey(c, "LMENU", true);
        studio.rawKey(c, "Q", true);
        check(element.hidden(), "actual HudStudio raw callback invokes reconfigured command");
        studio.rawKey(c, "Q", true);
        check(element.hidden(), "actual page raw repeat does not toggle twice");
        studio.rawKey(c, "Q", false);
        studio.rawKey(c, "LMENU", false);
        c.nativeFocused = true;
        studio.rawKey(c, "LMENU", true);
        studio.rawKey(c, "Q", true);
        check(element.hidden(), "native text focus excludes HUD commands");
        studio.rawKey(c, "Q", false);
        studio.rawKey(c, "LMENU", false);
        c.nativeFocused = false;
        c.preferences.set("searchKeybind", "CTRL+F");
        studio.rawKey(c, "LCONTROL", true);
        studio.rawKey(c, "F", true);
        studio.rawKey(c, "F", false);
        studio.rawKey(c, "LCONTROL", false);
        studio.rawKey(c, "LMENU", true);
        studio.rawKey(c, "Q", true);
        check(element.hidden(), "HUD search focus excludes HUD commands");
        studio.rawKey(c, "Q", false);
        studio.rawKey(c, "LMENU", false);
        studio.key(c, ConfigView.Key.ESCAPE, "", false, false, false);
        c.preferences.set("hudVisibilityKeybind", "MOUSE:2");
        check(studio.rawMouse(c, 2, true), "actual page mouse binding consumed");
        check(!element.hidden(), "actual page mouse binding updates host visibility");
        studio.rawMouse(c, 2, false);
        c.preferences.set("hudVisibilityKeybind", "ALT+Q");
        studio.leave(c);
        check(!studio.rawKey(c, "Q", true), "closed HUD page rejects commands");
        HudStudio fallback = new HudStudio(host, c.preferences);
        fallback.select(List.of("host-a"), false);
        c.next();
        fallback.render(c);
        c.nativeCode = 16;
        fallback.key(c, ConfigView.Key.NONE, "", false, false, true);
        check(element.hidden(), "key entrypoint matches preferences without raw host wrapper");
        c.preferences.set("hudVisibilityKeybind", "NONE");
        HudNode text = new HudNode("record", "text");
        fallback.designer().add(text, 100, 100);
        fallback.select(List.of(text.id()), true);
        fallback.panel.search.text("Keys");
        c.time = 1;
        c.next();
        fallback.render(c);
        c.time = 2;
        c.next();
        fallback.render(c);
        HudParityTest.Hit key = c.hits.get("hud:keybind/toggleKey");
        check(key != null, "single HUD key recording control exists: " + c.hits.keySet());
        key.action().run();
        check(
                c.overlay instanceof AppearancePages.RawKeyInput
                        && ((AppearancePages.RawKeyInput) c.overlay).recording(),
                "recording state passes through chrome overlay wrapper");
        c.preferences.set("hudVisibilityKeybind", "ALT+Q");
        fallback.rawKey(c, "LMENU", true);
        fallback.rawKey(c, "Q", true);
        check(!text.hidden(), "recording overlay excludes parent HUD bindings");
        c.dismissOverlay();
        c.preferences.set("hudVisibilityKeybind", "Q+W");
        fallback.rawKey(c, "Q", false);
        fallback.rawKey(c, "LMENU", false);
        fallback.rawKey(c, "Q", true);
        check(!text.hidden(), "multi-key chord waits for second ordinary key");
        fallback.rawKey(c, "W", true);
        check(text.hidden(), "multi-key chord activates on final ordinary key");
    }
}
