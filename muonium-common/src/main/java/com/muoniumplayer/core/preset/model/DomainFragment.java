package com.muoniumplayer.core.preset.model;

import com.google.gson.JsonObject;

/**
 * 一个配置域在预设里的那一段：片段格式版本 + 稀疏的值（形状与该域的落盘 / 视图 JSON 相同）。
 *
 * <p>值对象由构造方深拷贝持有，读出也给拷贝 —— 预设对象会在列表、套用、备份之间来回传，
 * 谁改了里面的 JsonObject 都不能影响别处。</p>
 */
public final class DomainFragment {

    private final int version;
    private final JsonObject values;

    public DomainFragment(int version, JsonObject values) {
        this.version = Math.max(1, version);
        this.values = values == null ? new JsonObject() : values.deepCopy();
    }

    /** 片段格式版本；域里键改名时 +1，读取时按它走迁移。 */
    public int version() {
        return version;
    }

    /** 值（深拷贝）。 */
    public JsonObject values() {
        return values.deepCopy();
    }

    public boolean isEmpty() {
        return values.size() == 0;
    }
}
