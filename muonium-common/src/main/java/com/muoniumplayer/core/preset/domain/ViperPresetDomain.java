package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.viper.ViperPreset;
import com.muoniumplayer.core.viper.ViperPresetParser;
import com.muoniumplayer.core.viper.ViperPresetStore;
import com.muoniumplayer.core.viper.ViperRuntime;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** ViPER state and shareable schema body. Local paths/private unknown fields never travel. */
public final class ViperPresetDomain implements PresetDomain {
    public static final String ID = "viper";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.VIPER, "enabled", "preset", "presetData").build();
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList("enabled", "preset", "presetData"));
    private String importedForTransaction;
    public String id() { return ID; }
    public String displayName() { return "蝰蛇音效"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 8; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() {
        JsonObject state = ViperRuntime.presetStateView();
        JsonElement data = state.get("presetData");
        if (data != null && data.isJsonObject()) state.add("presetData", sanitizeData(data.getAsJsonObject(), new CompatReport()));
        return state;
    }
    public JsonObject captureState() { return ViperRuntime.presetStateView(); }
    public JsonObject defaults() {
        JsonObject state = new JsonObject();
        state.addProperty("enabled", false);
        state.add("preset", JsonNull.INSTANCE); state.add("presetData", JsonNull.INSTANCE);
        return state;
    }
    public JsonElement compactValue(String key, JsonElement value) {
        if (!"presetData".equals(key) || value == null || !value.isJsonObject()) return value;
        JsonObject full = sanitizeData(value.getAsJsonObject(), new CompatReport());
        JsonObject defaults = defaultBody(), minimal = new JsonObject();
        minimal.addProperty("schemaVersion", ViperPreset.SCHEMA_VERSION);
        if (full.has("name")) minimal.add("name", full.get("name").deepCopy());
        for (Map.Entry<String, JsonElement> entry : full.entrySet()) {
            if ("name".equals(entry.getKey()) || "schemaVersion".equals(entry.getKey()) || "createdAt".equals(entry.getKey())) continue;
            JsonElement actual = entry.getValue(), fallback = defaults.get(entry.getKey());
            if (actual.isJsonObject() && fallback != null && fallback.isJsonObject()) {
                JsonObject diff = new JsonObject();
                for (Map.Entry<String, JsonElement> field : actual.getAsJsonObject().entrySet())
                    if (!field.getValue().equals(fallback.getAsJsonObject().get(field.getKey()))) diff.add(field.getKey(), field.getValue().deepCopy());
                if (diff.size() > 0) minimal.add(entry.getKey(), diff);
            } else if (!actual.equals(fallback)) minimal.add(entry.getKey(), actual.deepCopy());
        }
        return minimal;
    }
    private static JsonObject defaultBody() {
        return ViperPresetParser.parse("{\"schemaVersion\":" + ViperPreset.SCHEMA_VERSION + "}", "default").json();
    }
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if ("presetData".equals(key) && value != null && value.isJsonObject()) {
            try { return sanitizeData(value.getAsJsonObject(), report); }
            catch (RuntimeException failure) { report.warn("蝰蛇预设体无效，保留当前音效"); return null; }
        }
        if ("preset".equals(key) && value != null && value.isJsonPrimitive()) {
            String name = value.getAsString();
            if (!name.equals(ViperPresetStore.sanitizeName(name))) {
                report.warn("蝰蛇预设名不是安全文件名，保留当前选择"); return null;
            }
        }
        return value;
    }
    /** Nested whitelist comes from existing parser defaults, not another DSP schema. */
    private static JsonObject sanitizeData(JsonObject input, CompatReport report) {
        JsonObject schema = defaultBody();
        JsonObject normalized = ViperPresetParser.parse(input.toString(), "分享的预设").json();
        JsonObject clean = new JsonObject();
        clean.addProperty("schemaVersion", ViperPreset.SCHEMA_VERSION);
        if (input.has("name") && input.get("name").isJsonPrimitive())
            clean.addProperty("name", ViperPresetStore.sanitizeName(input.get("name").getAsString()));
        for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonElement actual = normalized.get(entry.getKey());
            if (actual == null || !actual.isJsonObject()) continue;
            JsonObject group = new JsonObject();
            for (String field : entry.getValue().getAsJsonObject().keySet()) {
                JsonElement value = actual.getAsJsonObject().get(field);
                if (value != null) group.add(field, value.deepCopy());
            }
            clean.add(entry.getKey(), group);
        }
        checkFileRef(clean, "convolver", "kernelFile", false, report);
        checkFileRef(clean, "ddc", "device", true, report);
        return clean;
    }
    private static void checkFileRef(JsonObject data, String groupName, String field, boolean ddc, CompatReport report) {
        JsonObject group = data.has(groupName) && data.get(groupName).isJsonObject() ? data.getAsJsonObject(groupName) : null;
        JsonElement ref = group == null ? null : group.get(field);
        if (ref == null || !ref.isJsonPrimitive()) return;
        String name = ref.getAsString().trim();
        if (name.isEmpty()) return;
        String label = ddc ? "DDC 校正文件" : "卷积核";
        if (!isBareFileName(name)) {
            group.addProperty(field, "");
            report.warn("蝰蛇预设里的" + label + "是路径，出于安全已清除");
        } else if (!localFileExists(name, ddc)) report.warn(label + "「" + name + "」本机没有（文件不随分享）");
    }
    static boolean isBareFileName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0
                && name.indexOf('\\') < 0 && name.indexOf(':') < 0 && name.indexOf('\0') < 0 && name.equals(new File(name).getName());
    }
    private static boolean localFileExists(String name, boolean ddc) {
        String fileName = ddc && !name.endsWith(".vdc") ? name + ".vdc" : name;
        return safeFile(new File(ConfigPaths.VIPER_DIR, ddc ? "ddc" : "kernels"), fileName) || safeFile(ConfigPaths.VIPER_DIR, fileName);
    }
    private static boolean safeFile(File directory, String name) {
        File file = new File(directory, name);
        try { return file.isFile() && !java.nio.file.Files.isSymbolicLink(file.toPath())
                && file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()); }
        catch (java.io.IOException failure) { return false; }
    }
    public void apply(JsonObject state, CompatReport report) {
        importedForTransaction = null;
        boolean enabled = TransparentPresetDomain.bool(state.get("enabled"), false);
        JsonElement ref = state.get("preset");
        String name = ref != null && ref.isJsonPrimitive() ? ref.getAsString().trim() : null;
        if (name != null && name.isEmpty()) name = null;
        String target = name;
        if (name != null) {
            JsonElement data = state.get("presetData");
            if (data != null && data.isJsonObject() && data.getAsJsonObject().has("name")
                    && name.equals(data.getAsJsonObject().get("name").getAsString())) {
                boolean existed = ViperRuntime.hasPreset(name);
                target = ViperRuntime.importPresetJson(sanitizeData(data.getAsJsonObject(), report), name);
                if (!existed || !target.equals(name)) importedForTransaction = target;
                if (!target.equals(name)) report.warn("本机有不同的同名蝰蛇预设，另存为「" + target + "」");
            } else if (!ViperRuntime.hasPreset(name)) {
                report.warn("蝰蛇预设「" + name + "」本机没有，保留当前选择");
                target = ViperRuntime.activePresetName();
            }
        }
        ViperRuntime.applyState(enabled, target);
    }
    public void restoreState(JsonObject state, CompatReport report) {
        JsonElement ref = state.get("preset");
        String name = ref != null && ref.isJsonPrimitive() ? ref.getAsString() : null;
        // Restore the original selection directly. Never re-import a sanitized old body under a new name.
        ViperRuntime.applyState(TransparentPresetDomain.bool(state.get("enabled"), false), name);
        if (importedForTransaction != null) {
            ViperRuntime.removeImportedPresetJson(importedForTransaction);
            importedForTransaction = null;
        }
    }
    public void completeTransaction() { importedForTransaction = null; }
}
