package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.KeyBindings;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.pages.AppearanceBindings;
import com.muoniumplayer.configskija.ui.pages.AppearanceSchema;
import com.muoniumplayer.configskija.ui.pages.LibraryServices;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Config-backed HUD chords and KeybindManager rising-edge semantics in the neutral legacy namespace. */
final class HudBindings {
    private static final int CTRL = 1, SHIFT = 2, ALT = 4, SUPER = 8;
    private static final Map<Integer, String> TOKENS =
            Map.ofEntries(
                    Map.entry(0, "NONE"),
                    Map.entry(1, "ESCAPE"),
                    Map.entry(2, "1"),
                    Map.entry(3, "2"),
                    Map.entry(4, "3"),
                    Map.entry(5, "4"),
                    Map.entry(6, "5"),
                    Map.entry(7, "6"),
                    Map.entry(8, "7"),
                    Map.entry(9, "8"),
                    Map.entry(10, "9"),
                    Map.entry(11, "0"),
                    Map.entry(12, "MINUS"),
                    Map.entry(13, "EQUALS"),
                    Map.entry(14, "BACK"),
                    Map.entry(15, "TAB"),
                    Map.entry(16, "Q"),
                    Map.entry(17, "W"),
                    Map.entry(18, "E"),
                    Map.entry(19, "R"),
                    Map.entry(20, "T"),
                    Map.entry(21, "Y"),
                    Map.entry(22, "U"),
                    Map.entry(23, "I"),
                    Map.entry(24, "O"),
                    Map.entry(25, "P"),
                    Map.entry(26, "LBRACKET"),
                    Map.entry(27, "RBRACKET"),
                    Map.entry(28, "RETURN"),
                    Map.entry(29, "LCONTROL"),
                    Map.entry(30, "A"),
                    Map.entry(31, "S"),
                    Map.entry(32, "D"),
                    Map.entry(33, "F"),
                    Map.entry(34, "G"),
                    Map.entry(35, "H"),
                    Map.entry(36, "J"),
                    Map.entry(37, "K"),
                    Map.entry(38, "L"),
                    Map.entry(39, "SEMICOLON"),
                    Map.entry(40, "APOSTROPHE"),
                    Map.entry(41, "GRAVE"),
                    Map.entry(42, "LSHIFT"),
                    Map.entry(43, "BACKSLASH"),
                    Map.entry(44, "Z"),
                    Map.entry(45, "X"),
                    Map.entry(46, "C"),
                    Map.entry(47, "V"),
                    Map.entry(48, "B"),
                    Map.entry(49, "N"),
                    Map.entry(50, "M"),
                    Map.entry(51, "COMMA"),
                    Map.entry(52, "PERIOD"),
                    Map.entry(53, "SLASH"),
                    Map.entry(54, "RSHIFT"),
                    Map.entry(55, "MULTIPLY"),
                    Map.entry(56, "LMENU"),
                    Map.entry(57, "SPACE"),
                    Map.entry(58, "CAPITAL"),
                    Map.entry(59, "F1"),
                    Map.entry(60, "F2"),
                    Map.entry(61, "F3"),
                    Map.entry(62, "F4"),
                    Map.entry(63, "F5"),
                    Map.entry(64, "F6"),
                    Map.entry(65, "F7"),
                    Map.entry(66, "F8"),
                    Map.entry(67, "F9"),
                    Map.entry(68, "F10"),
                    Map.entry(69, "NUMLOCK"),
                    Map.entry(70, "SCROLL"),
                    Map.entry(71, "NUMPAD7"),
                    Map.entry(72, "NUMPAD8"),
                    Map.entry(73, "NUMPAD9"),
                    Map.entry(74, "SUBTRACT"),
                    Map.entry(75, "NUMPAD4"),
                    Map.entry(76, "NUMPAD5"),
                    Map.entry(77, "NUMPAD6"),
                    Map.entry(78, "ADD"),
                    Map.entry(79, "NUMPAD1"),
                    Map.entry(80, "NUMPAD2"),
                    Map.entry(81, "NUMPAD3"),
                    Map.entry(82, "NUMPAD0"),
                    Map.entry(83, "DECIMAL"),
                    Map.entry(87, "F11"),
                    Map.entry(88, "F12"),
                    Map.entry(100, "F13"),
                    Map.entry(101, "F14"),
                    Map.entry(102, "F15"),
                    Map.entry(103, "F16"),
                    Map.entry(104, "F17"),
                    Map.entry(105, "F18"),
                    Map.entry(112, "KANA"),
                    Map.entry(113, "F19"),
                    Map.entry(121, "CONVERT"),
                    Map.entry(123, "NOCONVERT"),
                    Map.entry(125, "YEN"),
                    Map.entry(141, "NUMPADEQUALS"),
                    Map.entry(144, "CIRCUMFLEX"),
                    Map.entry(145, "AT"),
                    Map.entry(146, "COLON"),
                    Map.entry(147, "UNDERLINE"),
                    Map.entry(148, "KANJI"),
                    Map.entry(149, "STOP"),
                    Map.entry(150, "AX"),
                    Map.entry(151, "UNLABELED"),
                    Map.entry(156, "NUMPADENTER"),
                    Map.entry(157, "RCONTROL"),
                    Map.entry(167, "SECTION"),
                    Map.entry(179, "NUMPADCOMMA"),
                    Map.entry(181, "DIVIDE"),
                    Map.entry(183, "SYSRQ"),
                    Map.entry(184, "RMENU"),
                    Map.entry(196, "FUNCTION"),
                    Map.entry(197, "PAUSE"),
                    Map.entry(199, "HOME"),
                    Map.entry(200, "UP"),
                    Map.entry(201, "PRIOR"),
                    Map.entry(203, "LEFT"),
                    Map.entry(205, "RIGHT"),
                    Map.entry(207, "END"),
                    Map.entry(208, "DOWN"),
                    Map.entry(209, "NEXT"),
                    Map.entry(210, "INSERT"),
                    Map.entry(211, "DELETE"),
                    Map.entry(218, "CLEAR"),
                    Map.entry(219, "LMETA"),
                    Map.entry(220, "RMETA"),
                    Map.entry(221, "APPS"),
                    Map.entry(222, "POWER"),
                    Map.entry(223, "SLEEP"));
    static final Map<String, HudStudio.Command> COMMANDS;

    static {
        Map<String, HudStudio.Command> map = new LinkedHashMap<>();
        map.put("hudSettingsKeybind", HudStudio.Command.OPEN_SETTINGS);
        map.put("hudVisibilityKeybind", HudStudio.Command.TOGGLE_VISIBILITY);
        map.put("hudLockKeybind", HudStudio.Command.LOCK);
        map.put("hudCopyKeybind", HudStudio.Command.COPY);
        map.put("hudCutKeybind", HudStudio.Command.CUT);
        map.put("hudPasteKeybind", HudStudio.Command.PASTE);
        map.put("hudDuplicateKeybind", HudStudio.Command.DUPLICATE);
        map.put("hudResetKeybind", HudStudio.Command.RESET);
        map.put("hudDeleteKeybind", HudStudio.Command.DELETE);
        map.put("hudSelectAllKeybind", HudStudio.Command.SELECT_ALL);
        map.put("hudUndoKeybind", HudStudio.Command.UNDO);
        map.put("hudRedoKeybind", HudStudio.Command.REDO);
        map.put("hudRedoAltKeybind", HudStudio.Command.REDO);
        map.put("searchKeybind", HudStudio.Command.SEARCH);
        COMMANDS = Collections.unmodifiableMap(map);
    }

    record Chord(Set<String> keys, Set<Integer> mouse, int modifiers, boolean valid) {
        Chord {
            keys = Set.copyOf(keys);
            mouse = Set.copyOf(mouse);
        }

        boolean bound() {
            return valid && (!keys.isEmpty() || !mouse.isEmpty() || modifiers != 0);
        }

        boolean matches(Snapshot state, boolean strictShift) {
            if (!bound() || (state.modifiers() & modifiers) != modifiers) return false;
            if (strictShift
                    && (state.modifiers() & SHIFT) != 0
                    && (modifiers & SHIFT) == 0
                    && keys.stream().noneMatch(key -> modifier(key) == SHIFT)) return false;
            for (String key : keys) {
                int modifier = modifier(key);
                if (modifier != 0 && key.equals(group(modifier))) {
                    if ((state.modifiers() & modifier) == 0) return false;
                } else if (!state.keys().contains(key)) return false;
            }
            return state.mouse().containsAll(mouse);
        }
    }

    record Snapshot(Set<String> keys, Set<Integer> mouse, int modifiers) {}

    private final Set<String> held = new HashSet<>(), latched = new HashSet<>();
    private final Set<Integer> mouse = new HashSet<>();
    private boolean observedModifier, rawDelivered;

    static String token(int legacy) {
        return canonical(TOKENS.getOrDefault(legacy, "KEY:" + legacy));
    }

    static String canonical(String token) {
        String key = AppearanceBindings.normalize(token);
        return switch (key) {
            case "BACK" -> "BACKSPACE";
            case "RETURN", "NUMPADENTER" -> "ENTER";
            case "LEFT_CONTROL", "LEFT_CTRL" -> "LCONTROL";
            case "RIGHT_CONTROL", "RIGHT_CTRL" -> "RCONTROL";
            case "LEFT_SHIFT" -> "LSHIFT";
            case "RIGHT_SHIFT" -> "RSHIFT";
            case "LEFT_ALT" -> "LMENU";
            case "RIGHT_ALT" -> "RMENU";
            case "LEFT_SUPER", "LEFT_META" -> "LMETA";
            case "RIGHT_SUPER", "RIGHT_META" -> "RMETA";
            default -> key;
        };
    }

    static int modifier(String token) {
        return switch (token) {
            case "CTRL", "CONTROL", "LCONTROL", "RCONTROL" -> CTRL;
            case "SHIFT", "LSHIFT", "RSHIFT" -> SHIFT;
            case "ALT", "LMENU", "RMENU" -> ALT;
            case "SUPER", "META", "LMETA", "RMETA" -> SUPER;
            default -> 0;
        };
    }

    private static String group(int modifier) {
        return switch (modifier) {
            case CTRL -> "CTRL";
            case SHIFT -> "SHIFT";
            case ALT -> "ALT";
            case SUPER -> "SUPER";
            default -> "";
        };
    }

    static Chord parse(String stored) {
        String resolved = AppearanceBindings.resolveAction(stored);
        if (resolved.isBlank() || resolved.equals("NONE"))
            return new Chord(Set.of(), Set.of(), 0, false);
        Set<String> keys = new LinkedHashSet<>();
        Set<Integer> buttons = new LinkedHashSet<>();
        int mods = 0;
        for (String part : resolved.split("\\+", -1)) {
            String raw = part.trim().toUpperCase(Locale.ROOT);
            if (raw.isEmpty()) return new Chord(Set.of(), Set.of(), 0, false);
            if (raw.startsWith("MOUSE:")) {
                try {
                    int code = Integer.parseInt(raw.substring(6));
                    if (code < 0) return new Chord(Set.of(), Set.of(), 0, false);
                    buttons.add(code);
                } catch (NumberFormatException invalid) {
                    return new Chord(Set.of(), Set.of(), 0, false);
                }
                continue;
            }
            boolean explicit = raw.startsWith("KEY:");
            String key = canonical(explicit ? raw.substring(4) : raw);
            if (explicit && key.matches("[0-9]+")) {
                try {
                    key = token(Integer.parseInt(key));
                } catch (NumberFormatException invalid) {
                    return new Chord(Set.of(), Set.of(), 0, false);
                }
            }
            int modifier = modifier(key);
            if (!explicit && modifier != 0) mods |= modifier;
            else keys.add(key);
        }
        return new Chord(keys, buttons, mods, true);
    }

    static String display(PageContext c, String stored) {
        Chord chord = parse(stored);
        if (!chord.bound()) return null;
        List<String> parts = new ArrayList<>();
        for (int bit : new int[] {CTRL, SHIFT, ALT, SUPER})
            if ((chord.modifiers() & bit) != 0)
                parts.add(
                        bit == CTRL
                                ? "Ctrl"
                                : bit == SHIFT ? "Shift" : bit == ALT ? "Alt" : "Meta");
        String resolved = AppearanceBindings.resolveAction(stored);
        for (String part : resolved.split("\\+")) {
            String raw = part.trim().toUpperCase(Locale.ROOT);
            if (!raw.startsWith("KEY:") && modifier(canonical(raw)) != 0) continue;
            if (raw.startsWith("MOUSE:")) {
                int button = Integer.parseInt(raw.substring(6));
                if (c.host() instanceof LibraryServices.Keys source)
                    parts.add(source.keyBindings().display(KeyBindings.Chord.mouse(button)));
                else
                    parts.add(
                            c.host()
                                    .translate(
                                            button == 0
                                                    ? "key.mouse.left"
                                                    : button == 1
                                                            ? "key.mouse.right"
                                                            : button == 2
                                                                    ? "key.mouse.middle"
                                                                    : "key.mouse." + (button + 1),
                                            "Mouse " + (button + 1)));
                continue;
            }
            String key = canonical(raw.startsWith("KEY:") ? raw.substring(4) : raw);
            if (key.matches("[0-9]+")) key = token(Integer.parseInt(key));
            String named = key;
            Integer code =
                    TOKENS.entrySet().stream()
                            .filter(e -> canonical(e.getValue()).equals(named))
                            .map(Map.Entry::getKey)
                            .min(Integer::compare)
                            .orElse(null);
            if (code != null && c.host() instanceof LibraryServices.Keys source) {
                parts.add(source.keyBindings().display(KeyBindings.Chord.keyboard(code)));
                continue;
            }
            String nativeName =
                    switch (key) {
                        case "LCONTROL" -> "left.control";
                        case "RCONTROL" -> "right.control";
                        case "LSHIFT" -> "left.shift";
                        case "RSHIFT" -> "right.shift";
                        case "LMENU" -> "left.alt";
                        case "RMENU" -> "right.alt";
                        case "LMETA" -> "left.win";
                        case "RMETA" -> "right.win";
                        default -> key.toLowerCase(Locale.ROOT);
                    };
            String fallback;
            if (modifier(key) != 0 && !key.equals(group(modifier(key)))) {
                String[] words = nativeName.split("\\.");
                List<String> labels = new ArrayList<>();
                for (String word : words)
                    labels.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
                fallback = String.join(" ", labels);
            } else
                fallback =
                        key.length() == 1
                                ? key
                                : key.substring(0, 1) + key.substring(1).toLowerCase(Locale.ROOT);
            parts.add(c.host().translate("key.keyboard." + nativeName, fallback));
        }
        return String.join(" + ", parts);
    }

    static String value(UiPreferences prefs, String id) {
        return AppearanceSchema.PREFERENCES.stream()
                .filter(setting -> setting.key().equals(id))
                .findFirst()
                .map(setting -> String.valueOf(setting.value(prefs)))
                .orElseGet(() -> prefs.get(id, "NONE"));
    }

    private static KeyBindings.InputState input(PageContext c) {
        if (c.host() instanceof LibraryServices.Keys source
                && source.keyBindings() instanceof KeyBindings.InputState state) return state;
        return null;
    }

    private Snapshot snapshot(PageContext c, Collection<Chord> chords) {
        Set<String> keys = new HashSet<>(held);
        Set<Integer> buttons = new HashSet<>(mouse);
        KeyBindings.InputState input = input(c);
        if (input != null) {
            for (var entry : TOKENS.entrySet()) {
                String key = canonical(entry.getValue());
                boolean needed =
                        modifier(key) != 0
                                || chords.stream().anyMatch(chord -> chord.keys().contains(key));
                if (needed && input.keyDown(entry.getKey())) keys.add(key);
            }
            for (Chord chord : chords)
                for (int button : chord.mouse()) if (input.mouseDown(button)) buttons.add(button);
        }
        int modifiers = 0;
        for (String key : keys) modifiers |= modifier(key);
        if (input == null && !observedModifier) {
            if (c.control()) modifiers |= CTRL;
            if (c.shift()) modifiers |= SHIFT;
            if (c.alt()) modifiers |= ALT;
        }
        return new Snapshot(Set.copyOf(keys), Set.copyOf(buttons), modifiers);
    }

    boolean key(
            PageContext c,
            String token,
            boolean pressed,
            boolean allowed,
            Consumer<HudStudio.Command> dispatch) {
        String key = canonical(token);
        rawDelivered = true;
        if (modifier(key) != 0) observedModifier = true;
        if (pressed) held.add(key);
        else held.remove(key);
        return evaluate(c, allowed, dispatch, chord -> relevant(chord, key));
    }

    boolean mouse(
            PageContext c,
            int button,
            boolean pressed,
            boolean allowed,
            Consumer<HudStudio.Command> dispatch) {
        if (pressed) mouse.add(button);
        else mouse.remove(button);
        return evaluate(c, allowed, dispatch, chord -> chord.mouse().contains(button));
    }

    private boolean evaluate(
            PageContext c,
            boolean allowed,
            Consumer<HudStudio.Command> dispatch,
            Predicate<Chord> relevant) {
        Map<String, Chord> chords = new LinkedHashMap<>();
        for (String id : COMMANDS.keySet()) chords.put(id, parse(value(c.preferences(), id)));
        Snapshot state = snapshot(c, chords.values());
        boolean consumed = false;
        for (var entry : chords.entrySet()) {
            String id = entry.getKey();
            boolean matches =
                    allowed && entry.getValue().matches(state, id.equals("hudUndoKeybind"));
            if (matches) {
                boolean first = latched.add(id);
                if (first) dispatch.accept(COMMANDS.get(id));
                consumed |= first || relevant.test(entry.getValue());
            } else latched.remove(id);
        }
        return consumed;
    }

    private static boolean relevant(Chord chord, String key) {
        int modifier = modifier(key);
        return chord.keys().contains(key)
                || modifier != 0
                        && ((chord.modifiers() & modifier) != 0
                                || chord.keys().contains(group(modifier)));
    }

    boolean fallback(
            PageContext c,
            ConfigView.Key key,
            String typed,
            boolean control,
            boolean shift,
            boolean alt,
            Consumer<HudStudio.Command> dispatch) {
        if (rawDelivered) {
            rawDelivered = false;
            return false;
        }
        String name =
                key == ConfigView.Key.NONE
                        ? token(c.nativeKeyCode())
                        : key == ConfigView.Key.BACKSPACE ? "BACKSPACE" : key.name();
        Set<String> keys = new HashSet<>(held);
        keys.add(canonical(name));
        int mods = (control ? CTRL : 0) | (shift ? SHIFT : 0) | (alt ? ALT : 0);
        Snapshot raw = snapshot(c, List.of());
        Snapshot state = new Snapshot(Set.copyOf(keys), raw.mouse(), mods | raw.modifiers());
        boolean consumed = false;
        for (var entry : COMMANDS.entrySet()) {
            Chord chord = parse(value(c.preferences(), entry.getKey()));
            if (relevant(chord, canonical(name))
                    && chord.matches(state, entry.getKey().equals("hudUndoKeybind"))) {
                dispatch.accept(entry.getValue());
                consumed = true;
            }
        }
        return consumed;
    }

    void poll(PageContext c) {
        KeyBindings.InputState input = input(c);
        if (input == null) return;
        held.removeIf(
                key -> {
                    List<Integer> codes =
                            TOKENS.entrySet().stream()
                                    .filter(e -> canonical(e.getValue()).equals(key))
                                    .map(Map.Entry::getKey)
                                    .toList();
                    return !codes.isEmpty() && codes.stream().noneMatch(input::keyDown);
                });
        mouse.removeIf(button -> !input.mouseDown(button));
        Map<String, Chord> chords = new HashMap<>();
        for (String id : latched) chords.put(id, parse(value(c.preferences(), id)));
        Snapshot state = snapshot(c, chords.values());
        latched.removeIf(id -> !chords.get(id).matches(state, id.equals("hudUndoKeybind")));
    }

    boolean actionModifier(PageContext c) {
        int expected = AppearanceBindings.resolveAction("ACTION").equals("SUPER") ? SUPER : CTRL;
        return (snapshot(c, List.of()).modifiers() & expected) != 0;
    }

    void clear() {
        held.clear();
        mouse.clear();
        latched.clear();
        observedModifier = rawDelivered = false;
    }
}
