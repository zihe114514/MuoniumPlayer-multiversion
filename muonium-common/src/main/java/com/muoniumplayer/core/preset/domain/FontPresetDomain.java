package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Collections;
import java.util.List;

/** Id only; font files never travel. Preset application finishes synchronously without changing cold-font policy. */
public final class FontPresetDomain implements PresetDomain {
    public static final String ID = "font";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.FONT, "id").build();
    private static final List<String> SHARE_KEYS = Collections.singletonList("id");
    public String id() { return ID; }
    public String displayName() { return "界面字体"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 4; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() { JsonObject out = new JsonObject(); out.addProperty("id", UiFontLibrary.activeId()); return out; }
    public JsonObject defaults() { JsonObject out = new JsonObject(); out.addProperty("id", UiFontLibrary.defaultFontId()); return out; }
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            report.unsupportedValue(ID, key); return null;
        }
        String problem = UiFontLibrary.unavailableReason(value.getAsString());
        if (problem != null) { report.warn(problem); return null; }
        return value;
    }
    public JsonObject captureState() {
        if (UiFontLibrary.isBusy()) throw new IllegalStateException("字体正在切换，请完成后再套用预设");
        return snapshot();
    }
    public void apply(JsonObject state, CompatReport report) {
        JsonElement id = state.get("id");
        if (id == null || !id.isJsonPrimitive()) return;
        String problem = UiFontLibrary.applyPresetId(id.getAsString());
        if (problem != null) throw new IllegalStateException(problem);
    }
}
