package com.muoniumplayer.core.preset.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 嵌套 JSON 与点路径之间的互转。
 *
 * <p>预设引擎只和「拍平后的键 → 叶子值」打交道：HUD 是一层平铺、Folia 背景是两层嵌套、ViPER 预设体
 * 是整块不透明的对象 —— 拍平之后三者是同一种东西，合并、重置、过滤都只写一遍。哪些对象当叶子
 * 由调用方的 {@code leaf} 判定（通常是「这个路径在键策略里登记过」）。</p>
 */
public final class JsonPaths {

    private JsonPaths() {
    }

    /** 拍平；{@code leaf} 对某个路径返回 true 时，即使它的值是对象也整块当叶子。 */
    public static Map<String, JsonElement> flatten(JsonObject root, Predicate<String> leaf) {
        Map<String, JsonElement> out = new LinkedHashMap<String, JsonElement>();
        if (root != null) {
            walk(root, "", leaf, out, 0);
        }
        return out;
    }

    private static void walk(JsonObject object, String prefix, Predicate<String> leaf,
                             Map<String, JsonElement> out, int depth) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject() && !leaf.test(path)
                    && depth < PresetLimits.MAX_DEPTH) {
                walk(value.getAsJsonObject(), path, leaf, out, depth + 1);
            } else {
                out.put(path, value == null ? JsonNull.INSTANCE : value.deepCopy());
            }
        }
    }

    /** 还原成嵌套对象。路径冲突（同一段既是叶子又是对象）时后写的覆盖先写的。 */
    public static JsonObject unflatten(Map<String, JsonElement> flat) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : flat.entrySet()) {
            String[] parts = entry.getKey().split("\\.", -1);
            JsonObject at = root;
            for (int i = 0; i < parts.length - 1; i++) {
                JsonElement next = at.get(parts[i]);
                if (next == null || !next.isJsonObject()) {
                    next = new JsonObject();
                    at.add(parts[i], next);
                }
                at = next.getAsJsonObject();
            }
            JsonElement value = entry.getValue();
            at.add(parts[parts.length - 1], value == null ? JsonNull.INSTANCE : value.deepCopy());
        }
        return root;
    }

    /**
     * 两个值是不是同一种 JSON 形状（基本类型里再分布尔 / 数字 / 字符串）。
     * {@code null} 与任何形状兼容：「未选择」这类可空的值在两端可能一边是 null、一边是字符串。
     */
    public static boolean sameShape(JsonElement a, JsonElement b) {
        if (a == null || b == null || a.isJsonNull() || b.isJsonNull()) {
            return true;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            return a.getAsJsonPrimitive().isBoolean() == b.getAsJsonPrimitive().isBoolean()
                    && a.getAsJsonPrimitive().isNumber() == b.getAsJsonPrimitive().isNumber()
                    && a.getAsJsonPrimitive().isString() == b.getAsJsonPrimitive().isString();
        }
        return a.isJsonObject() == b.isJsonObject()
                && a.isJsonArray() == b.isJsonArray()
                && a.isJsonNull() == b.isJsonNull();
    }
}
