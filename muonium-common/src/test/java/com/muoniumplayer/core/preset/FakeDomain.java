package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

final class FakeDomain implements PresetDomain {
    private static final AtomicInteger NEXT_CODE = new AtomicInteger(100);
    private final String id;
    private final int shareCode;
    private final List<String> shareKeys = new ArrayList<String>();
    private final KeyPolicy policy;
    private final JsonObject defaults;
    JsonObject state;
    int applyCount;
    RuntimeException failOnApply;
    RuntimeException failOnRestore;
    boolean mutateBeforeFailure;
    BiFunction<String, JsonElement, JsonElement> sanitizer;
    FakeDomain(String id, KeyPolicy policy, JsonObject defaults) { this(id, NEXT_CODE.incrementAndGet(), policy, defaults); }
    FakeDomain(String id, int shareCode, KeyPolicy policy, JsonObject defaults) {
        this.id = id; this.shareCode = shareCode; this.policy = policy;
        this.defaults = defaults.deepCopy(); this.state = defaults.deepCopy();
        for (String key : policy.allKeys()) if (!policy.rule(key).excluded()) shareKeys.add(key);
    }
    public int shareCode() { return shareCode; }
    public List<String> shareKeys() { return shareKeys; }
    public String id() { return id; }
    public String displayName() { return "假域 " + id; }
    public int fragmentVersion() { return 1; }
    public KeyPolicy policy() { return policy; }
    public JsonObject snapshot() { return state.deepCopy(); }
    public JsonObject defaults() { return defaults.deepCopy(); }
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (sanitizer == null) return value;
        JsonElement result = sanitizer.apply(key, value);
        if (result == null) report.warn("假域丢弃了 " + key);
        return result;
    }
    public void apply(JsonObject next, CompatReport report) {
        applyCount++;
        if (mutateBeforeFailure) state = next.deepCopy();
        if (failOnApply != null) throw failOnApply;
        state = next.deepCopy();
    }
    public void restoreState(JsonObject old, CompatReport report) {
        if (mutateBeforeFailure) state = old.deepCopy();
        if (failOnRestore != null) {
            RuntimeException failure = failOnRestore;
            failOnRestore = null;
            throw failure;
        }
        state = old.deepCopy();
    }
}
