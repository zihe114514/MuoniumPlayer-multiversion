package com.muoniumplayer.core.preset.codec;

/** Bounds shared by every untrusted preset input. */
public final class PresetLimits {
    public static final int MAX_JSON_CHARS = 512 * 1024;
    public static final int MAX_SHARE_TEXT_CHARS = 512 * 1024;
    public static final int MAX_INFLATED_BYTES = 512 * 1024;
    public static final int MAX_DEPTH = 16;
    public static final int MAX_DOMAINS = 32;
    public static final int MAX_KEYS_PER_DOMAIN = 2048;
    public static final int MAX_VALUE_STRING_CHARS = 16 * 1024;
    public static final int MAX_FACETS = 64;
    public static final int MAX_NAME_CHARS = 40;
    public static final int MAX_AUTHOR_CHARS = 32;
    public static final int MAX_DESCRIPTION_CHARS = 240;
    public static final int MAX_ID_CHARS = 96;
    public static final int MAX_SHORT_TEXT_CHARS = 48;
    private PresetLimits() {}
    /** Scan before parsing, ignoring brackets inside quoted strings. */
    public static int nestingDepth(CharSequence text) {
        int depth = 0, max = 0;
        boolean inString = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{' || c == '[') max = Math.max(max, ++depth);
            else if (c == '}' || c == ']') depth = Math.max(0, depth - 1);
        }
        return max;
    }
}
