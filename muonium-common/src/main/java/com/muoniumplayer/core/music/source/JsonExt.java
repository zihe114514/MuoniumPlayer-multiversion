package com.muoniumplayer.core.music.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * gson 安全取值。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class JsonExt {
    private JsonExt() {
    }

    /** 取对象子节点；缺失或类型不符返回 null。 */
    public static JsonObject objOrNull(JsonObject parent, String key) {
        if (parent == null || key == null) return null;
        JsonElement element = parent.get(key);
        if (element != null && element.isJsonObject()) return element.getAsJsonObject();
        return null;
    }

    /** 取数组子节点；缺失或类型不符返回 null。 */
    public static JsonArray arrOrNull(JsonObject parent, String key) {
        if (parent == null || key == null) return null;
        JsonElement element = parent.get(key);
        if (element != null && element.isJsonArray()) return element.getAsJsonArray();
        return null;
    }
}
