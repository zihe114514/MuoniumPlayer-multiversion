package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ThemePresetDomain implements PresetDomain {
    public static final String ID = "theme";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.THEME, "theme", "colorSource")
            .enumMax("colorSource", () -> HudConfig.THEME_COLOR_SOURCE_MAX).build();
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList("theme", "colorSource"));
    public String id() { return ID; }
    public String displayName() { return "主题配色"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 2; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() { return NCMTheme.presetView(); }
    public JsonObject defaults() { return NCMTheme.defaultPresetView(); }
    public JsonObject captureState() { return NCMTheme.capturePresetState(); }
    public void restoreState(JsonObject state, CompatReport report) { NCMTheme.restorePresetState(state); }
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (!"theme".equals(key)) return value;
        JsonElement id = value != null && value.isJsonObject() ? value.getAsJsonObject().get("id") : null;
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
            report.warn("预设里的主题没写 id，保留当前主题"); return null;
        }
        NCMTheme.ThemePreset theme = NCMTheme.byIdOrNull(id.getAsString());
        if (theme == null || !NCMTheme.isSelectable(theme)) {
            report.warn("主题「" + id.getAsString() + "」在本版本或当前设备不可用，保留当前主题"); return null;
        }
        // Opaque policy leaf still has a nested whitelist: no arbitrary private fields in export/import.
        JsonObject clean = new JsonObject();
        clean.addProperty("id", theme.getId());
        JsonElement colors = value.getAsJsonObject().get("customColors");
        if (colors != null && colors.isJsonObject()) {
            JsonObject allowed = new JsonObject();
            for (MapEntry entry : colorEntries(colors.getAsJsonObject())) allowed.add(entry.key, entry.value);
            clean.add("customColors", allowed);
        }
        return clean;
    }
    private static List<MapEntry> colorEntries(JsonObject colors) {
        List<MapEntry> out = new java.util.ArrayList<MapEntry>();
        for (java.util.Map.Entry<String, JsonElement> entry : colors.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) continue;
            try {
                com.muoniumplayer.core.screens.ncm.NCMScreen.ColorType type = com.muoniumplayer.core.screens.ncm.NCMScreen.ColorType.valueOf(entry.getKey());
                String text = entry.getValue().getAsString();
                if (text.matches("#?[0-9a-fA-F]{6}")) out.add(new MapEntry(type.name(), new com.google.gson.JsonPrimitive(text)));
            } catch (RuntimeException ignored) {}
        }
        return out;
    }
    private static final class MapEntry {
        final String key; final JsonElement value;
        MapEntry(String key, JsonElement value) { this.key = key; this.value = value; }
    }
    public void apply(JsonObject state, CompatReport report) {
        String problem = NCMTheme.applyPresetView(state);
        if (problem != null) throw new IllegalStateException(problem);
    }
}
