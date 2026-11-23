package com.muoniumplayer.configskija.ui.pages;

import java.util.*;

/** Canonical named chords keep preferences independent of a loader's numeric keyboard namespace. */
public final class AppearanceBindings {
    private AppearanceBindings() {}

    public static String resolveAction(String chord) {
        return chord.replace(
                "ACTION",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                        ? "SUPER"
                        : "CTRL");
    }

    public static String normalize(String token) {
        String t = token.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (t) {
            case "CONTROL" -> "CTRL";
            case "ESC" -> "ESCAPE";
            case "DEL" -> "DELETE";
            case "RETURN" -> "ENTER";
            default -> t;
        };
    }

    private static String modifier(String token) {
        return switch (token) {
            case "CTRL", "LEFT_CONTROL", "RIGHT_CONTROL", "LCONTROL", "RCONTROL" -> "CTRL";
            case "SHIFT", "LEFT_SHIFT", "RIGHT_SHIFT", "LSHIFT", "RSHIFT" -> "SHIFT";
            case "ALT", "LEFT_ALT", "RIGHT_ALT", "LMENU", "RMENU" -> "ALT";
            case "SUPER", "LEFT_SUPER", "RIGHT_SUPER", "LMETA", "RMETA" -> "SUPER";
            default -> "";
        };
    }

    public static String display(String chord) {
        if (chord.equals("NONE") || chord.isBlank()) return "None";
        return chord.replace("KEY:", "")
                .replace("MOUSE:", "Mouse ")
                .replace("CTRL", "Ctrl")
                .replace("SHIFT", "Shift")
                .replace("ALT", "Alt")
                .replace("SUPER", "Super")
                .replace("+", " + ");
    }

    public static boolean conflicts(String a, String b) {
        if (a.equals("NONE") || b.equals("NONE") || a.isBlank() || b.isBlank()) return false;
        return new HashSet<>(List.of(resolveAction(a).split("\\+")))
                .equals(new HashSet<>(List.of(resolveAction(b).split("\\+"))));
    }

    /** Original recording commits only after every recorded key and mouse button has been released. */
    public static final class Recorder {
        private final LinkedHashSet<String> recorded = new LinkedHashSet<>();
        private final Set<String> held = new HashSet<>();
        private boolean active;
        private String result;

        public void begin() {
            recorded.clear();
            held.clear();
            result = null;
            active = true;
        }

        public boolean active() {
            return active;
        }

        public void cancel() {
            recorded.clear();
            held.clear();
            result = null;
            active = false;
        }

        public String pending() {
            return recorded.isEmpty() ? "Press keys..." : display(String.join("+", recorded));
        }

        public String take() {
            String value = result;
            result = null;
            return value;
        }

        public boolean key(String token, boolean pressed) {
            if (!active) return false;
            String key = normalize(token);
            if (key.isEmpty()) return true;
            if (pressed) {
                if (key.equals("ESCAPE")
                        || recorded.isEmpty()
                                && (key.equals("BACKSPACE") || key.equals("DELETE"))) {
                    finish("NONE");
                    return true;
                }
                recorded.add(key);
                held.add(key);
            } else {
                held.remove(key);
                if (held.isEmpty() && !recorded.isEmpty()) finish(chord());
            }
            return true;
        }

        public boolean mouse(int button, boolean pressed) {
            return key("MOUSE:" + button, pressed);
        }

        private void finish(String value) {
            result = value;
            held.clear();
            active = false;
        }

        private String chord() {
            Set<String> mods = new HashSet<>();
            List<String> keys = new ArrayList<>();
            for (String token : recorded) {
                String m = modifier(token);
                if (m.isEmpty()) keys.add(token);
                else mods.add(m);
            }
            if (keys.isEmpty())
                return String.join("+", recorded.stream().map(s -> "KEY:" + s).toList());
            List<String> all = new ArrayList<>();
            for (String m : List.of("CTRL", "SHIFT", "ALT", "SUPER"))
                if (mods.contains(m)) all.add(m);
            all.addAll(keys);
            return String.join("+", all);
        }
    }
}
