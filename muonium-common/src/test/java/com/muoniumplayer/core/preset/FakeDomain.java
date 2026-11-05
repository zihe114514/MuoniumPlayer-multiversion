package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.KeyPolicy;

import java.util.function.BiFunction;

/** 测试用的内存配置域：状态就是一个 JsonObject，记录 apply 被调了几次。 */
final class FakeDomain implements PresetDomain {

    private final String id;
    private final KeyPolicy policy;
    private final JsonObject defaults;
    JsonObject state;
    int applyCount;
    RuntimeException failOnApply;
    BiFunction<String, JsonElement, JsonElement> sanitizer;

    FakeDomain(String id, KeyPolicy policy, JsonObject defaults) {
        this.id = id;
        this.policy = policy;
        this.defaults = defaults.deepCopy();
        this.state = defaults.deepCopy();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return "假域 " + id;
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public KeyPolicy policy() {
        return policy;
    }

    @Override
    public JsonObject snapshot() {
        return state.deepCopy();
    }

    @Override
    public JsonObject defaults() {
        return defaults.deepCopy();
    }

    @Override
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (sanitizer == null) {
            return value;
        }
        JsonElement result = sanitizer.apply(key, value);
        if (result == null) {
            report.warn("假域丢弃了 " + key);
        }
        return result;
    }

    @Override
    public void apply(JsonObject next, CompatReport report) {
        applyCount++;
        if (failOnApply != null) {
            throw failOnApply;
        }
        state = next.deepCopy();
    }
}
