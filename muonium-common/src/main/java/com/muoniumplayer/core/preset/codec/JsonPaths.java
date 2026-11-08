package com.muoniumplayer.core.preset.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Nested JSON and policy paths, preserving complete local snapshots when applying patches. */
public final class JsonPaths {
    private JsonPaths() {}
    public static Map<String, JsonElement> flatten(JsonObject root, Predicate<String> leaf) {
        Map<String, JsonElement> out = new LinkedHashMap<String, JsonElement>();
        if (root != null) walk(root, "", leaf, out, 0);
        return out;
    }
    private static void walk(JsonObject object, String prefix, Predicate<String> leaf,
                             Map<String, JsonElement> out, int depth) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject() && !leaf.test(path) && depth < PresetLimits.MAX_DEPTH)
                walk(value.getAsJsonObject(), path, leaf, out, depth + 1);
            else out.put(path, copy(value));
        }
    }
    public static JsonObject unflatten(Map<String, JsonElement> flat) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : flat.entrySet()) put(root, entry.getKey(), entry.getValue());
        return root;
    }
    /** Patch only policy-addressed paths; unrelated/empty objects and literal dotted keys stay intact. */
    public static JsonObject overlay(JsonObject current, Map<String, JsonElement> patch) {
        JsonObject root = current == null ? new JsonObject() : current.deepCopy();
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) put(root, entry.getKey(), entry.getValue());
        return root;
    }
    private static void put(JsonObject root, String path, JsonElement value) {
        String[] parts = path.split("\\.", -1);
        JsonObject at = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement next = at.get(parts[i]);
            if (next == null || !next.isJsonObject()) { next = new JsonObject(); at.add(parts[i], next); }
            at = next.getAsJsonObject();
        }
        at.add(parts[parts.length - 1], copy(value));
    }
    /** Trusted local sparse restore: absent fields retain their current complete value. */
    public static JsonObject merge(JsonObject current, JsonObject patch) {
        JsonObject out = current == null ? new JsonObject() : current.deepCopy();
        if (patch != null) for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            JsonElement old = out.get(entry.getKey()), value = entry.getValue();
            out.add(entry.getKey(), old != null && old.isJsonObject() && value != null && value.isJsonObject()
                    ? merge(old.getAsJsonObject(), value.getAsJsonObject()) : copy(value));
        }
        return out;
    }
    private static JsonElement copy(JsonElement value) { return value == null ? JsonNull.INSTANCE : value.deepCopy(); }
    public static boolean sameShape(JsonElement a, JsonElement b) {
        if (a == null || b == null || a.isJsonNull() || b.isJsonNull()) return true;
        if (a.isJsonPrimitive() && b.isJsonPrimitive())
            return a.getAsJsonPrimitive().isBoolean() == b.getAsJsonPrimitive().isBoolean()
                    && a.getAsJsonPrimitive().isNumber() == b.getAsJsonPrimitive().isNumber()
                    && a.getAsJsonPrimitive().isString() == b.getAsJsonPrimitive().isString();
        return a.isJsonObject() == b.isJsonObject() && a.isJsonArray() == b.isJsonArray();
    }
}
