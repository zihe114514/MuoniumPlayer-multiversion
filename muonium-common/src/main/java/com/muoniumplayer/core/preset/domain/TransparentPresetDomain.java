package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.TransparentMode;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TransparentPresetDomain implements PresetDomain {
    public static final String ID = "transparent";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.MOTION, "enabled", "compression", "width", "cameraFollows").build();
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList("enabled", "compression", "width", "cameraFollows"));
    public String id() { return ID; }
    public String displayName() { return "通透模式"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 5; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() { return TransparentMode.toJson(); }
    public JsonObject defaults() { return TransparentMode.defaultsJson(); }
    public void apply(JsonObject state, CompatReport report) {
        TransparentMode.applyAll(bool(state.get("enabled"), TransparentMode.isEnabled()),
                number(state.get("compression"), TransparentMode.compressionPercent()), number(state.get("width"), TransparentMode.widthPercent()),
                bool(state.get("cameraFollows"), TransparentMode.cameraFollows()));
    }
    static boolean bool(JsonElement value, boolean fallback) {
        try { return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback; }
        catch (RuntimeException failure) { return fallback; }
    }
    static float number(JsonElement value, float fallback) {
        try { float result = value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
            return Float.isFinite(result) ? result : fallback; }
        catch (RuntimeException failure) { return fallback; }
    }
}
