package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * AMP API JSON 载荷的容错读取工具：空串兜底、类型不符不抛、null 输入安全。
 * 解析纪律与上层一致 —— 单个字段读不出只影响那一个字段，不拖垮整张列表。
 */
public final class AppleAmpHelper {

    private AppleAmpHelper() {
    }

    // ── JSON 便捷读取 ───────────────────────────────────────────────────────

    public static String text(JsonObject object, String member) {
        try {
            if (object == null || !object.has(member) || object.get(member).isJsonNull()) return "";
            return object.get(member).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean bool(JsonObject object, String member) {
        try {
            return object != null && object.has(member) && object.get(member).isJsonPrimitive()
                    && object.get(member).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static JsonArray array(JsonObject object, String member) {
        try {
            if (object == null || !object.has(member) || object.get(member).isJsonNull()) {
                return new JsonArray();
            }
            return object.getAsJsonArray(member);
        } catch (Throwable ignored) {
            return new JsonArray();
        }
    }
}
