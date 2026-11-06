package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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

/**
 * 蝰蛇音效（viper/settings.json + 当前预设的内容，JamesDSP 混响是其中一组）。
 *
 * <p>视图：{@code enabled} + {@code preset}（当前预设名）+ {@code presetData}（那一份预设的归一化内容，
 * 整块当叶子）。对方本机未必有同名预设，所以内容必须随分享一起走；落盘时同名同内容复用、同名不同内容
 * 另存，绝不覆盖对方自己的预设（见 {@link ViperRuntime#importPresetJson}）。</p>
 *
 * <p>卷积核（.irs/.wav）与 DDC（.vdc）是二进制文件，不随分享走：预设里只留文件名，本机没有就提示。
 * 外来的文件引用只认单个文件名 —— 处理器解析时会接受绝对路径，不拦就能让别人的预设去读任意文件。</p>
 */
public final class ViperPresetDomain implements PresetDomain {

    public static final String ID = "viper";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.VIPER, "enabled", "preset", "presetData")
            .build();

    /**
     * 分享码键表（下标 + 1 = 编号）。<b>只许在末尾追加</b>，已发布的位置永不改、永不复用，
     * 见 {@link PresetDomain#shareKeys()}；排除项不编号。
     */
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            /*   1 */ "enabled", "preset", "presetData"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "蝰蛇音效";
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public int shareCode() {
        return 8;
    }

    @Override
    public List<String> shareKeys() {
        return SHARE_KEYS;
    }

    @Override
    public KeyPolicy policy() {
        return POLICY;
    }

    @Override
    public JsonObject snapshot() {
        ViperRuntime.syncFromDisk();
        JsonObject view = new JsonObject();
        view.addProperty("enabled", ViperRuntime.isEnabled());
        String name = ViperRuntime.activePresetName();
        JsonObject data = name == null ? null : ViperRuntime.exportPresetJson(name);
        if (data != null) {
            // 预设体里的名字以「设置里引用它的名字」为准：文件内嵌名可能与文件名不同，
            // 套用时靠这个名字确认「这份内容就是这个预设的」。
            data.addProperty("name", name);
        }
        view.add("preset", name == null ? JsonNull.INSTANCE : new JsonPrimitive(name));
        view.add("presetData", data == null ? JsonNull.INSTANCE : data);
        return view;
    }

    @Override
    public JsonObject defaults() {
        JsonObject view = new JsonObject();
        view.addProperty("enabled", false);
        view.add("preset", JsonNull.INSTANCE);
        view.add("presetData", JsonNull.INSTANCE);
        return view;
    }

    /**
     * 分享码里的预设体只留「与空预设不同」的字段：对方导入时 {@link ViperPresetParser} 会把缺的分组与字段
     * 按 schema 默认值补齐，结果与完整内容一致。一份内置预设的完整体有二十多组，压完往往只剩一两组。
     */
    @Override
    public JsonElement compactValue(String key, JsonElement value) {
        if (!"presetData".equals(key) || value == null || !value.isJsonObject()) {
            return value;
        }
        JsonObject full = value.getAsJsonObject();
        JsonObject defaults;
        try {
            defaults = ViperPresetParser.parse("{\"schemaVersion\":" + ViperPreset.SCHEMA_VERSION + "}", "default").json();
        } catch (RuntimeException failure) {
            return value;
        }
        JsonObject minimal = new JsonObject();
        minimal.addProperty("schemaVersion", ViperPreset.SCHEMA_VERSION);
        JsonElement name = full.get("name");
        if (name != null) {
            minimal.add("name", name.deepCopy());
        }
        for (java.util.Map.Entry<String, JsonElement> entry : full.entrySet()) {
            String group = entry.getKey();
            if ("schemaVersion".equals(group) || "name".equals(group) || "createdAt".equals(group)) {
                continue;
            }
            JsonElement actual = entry.getValue();
            JsonElement fallback = defaults.get(group);
            if (actual != null && actual.isJsonObject() && fallback != null && fallback.isJsonObject()) {
                JsonObject diff = new JsonObject();
                for (java.util.Map.Entry<String, JsonElement> field : actual.getAsJsonObject().entrySet()) {
                    if (!field.getValue().equals(fallback.getAsJsonObject().get(field.getKey()))) {
                        diff.add(field.getKey(), field.getValue().deepCopy());
                    }
                }
                if (diff.size() > 0) {
                    minimal.add(group, diff);
                }
            } else if (actual != null && !actual.equals(fallback)) {
                minimal.add(group, actual.deepCopy());
            }
        }
        return minimal;
    }

    @Override
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (!"presetData".equals(key) || value == null || !value.isJsonObject()) {
            return value;
        }
        JsonObject data = value.getAsJsonObject().deepCopy();
        checkFileRef(data, "convolver", "kernelFile", false, report);
        checkFileRef(data, "ddc", "device", true, report);
        return data;
    }

    /** 文件引用只认单个文件名；不安全的清空，本机没有的提示。 */
    private static void checkFileRef(JsonObject data, String group, String field, boolean ddc, CompatReport report) {
        JsonElement groupJson = data.get(group);
        if (groupJson == null || !groupJson.isJsonObject()) {
            return;
        }
        JsonElement ref = groupJson.getAsJsonObject().get(field);
        if (ref == null || !ref.isJsonPrimitive()) {
            return;
        }
        String name = ref.getAsString().trim();
        if (name.isEmpty()) {
            return;
        }
        String label = ddc ? "DDC 校正文件" : "卷积核";
        if (!isBareFileName(name)) {
            groupJson.getAsJsonObject().addProperty(field, "");
            report.warn("蝰蛇预设里的" + label + "是一个路径而不是文件名，出于安全已清除");
            return;
        }
        if (!localFileExists(name, ddc)) {
            report.warn(label + "「" + name + "」本机没有（文件不随分享），这一项效果不会生效");
        }
    }

    static boolean isBareFileName(String name) {
        return !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.indexOf(':') < 0 && name.indexOf('\0') < 0 && name.equals(new File(name).getName());
    }

    private static boolean localFileExists(String name, boolean ddc) {
        if (ddc) {
            String fileName = name.endsWith(".vdc") ? name : name + ".vdc";
            return new File(new File(ConfigPaths.VIPER_DIR, "ddc"), fileName).isFile()
                    || new File(ConfigPaths.VIPER_DIR, fileName).isFile();
        }
        return new File(new File(ConfigPaths.VIPER_DIR, "kernels"), name).isFile()
                || new File(ConfigPaths.VIPER_DIR, name).isFile();
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        boolean enabled = TransparentPresetDomain.bool(state.get("enabled"), false);
        JsonElement presetJson = state.get("preset");
        String name = presetJson != null && presetJson.isJsonPrimitive() ? presetJson.getAsString().trim() : null;
        if (name != null && name.isEmpty()) {
            name = null;
        }
        String target = null;
        if (name != null) {
            String wanted = ViperPresetStore.sanitizeName(name);
            JsonElement data = state.get("presetData");
            if (data != null && data.isJsonObject() && name.equals(stringOf(data.getAsJsonObject().get("name")))) {
                try {
                    target = ViperRuntime.importPresetJson(data.getAsJsonObject(), name);
                    if (!target.equals(wanted)) {
                        report.warn("本机已有同名的蝰蛇预设「" + wanted + "」，分享来的这一份另存为「" + target + "」");
                    }
                } catch (IllegalArgumentException unreadable) {
                    report.warn("蝰蛇预设「" + wanted + "」的内容读不出来：" + unreadable.getMessage());
                    target = ViperRuntime.hasPreset(wanted) ? wanted : null;
                }
            } else if (ViperRuntime.hasPreset(wanted)) {
                target = wanted;
            } else {
                report.warn("蝰蛇预设「" + wanted + "」本机没有，音效保持未选择预设");
            }
        }
        ViperRuntime.applyState(enabled, target);
    }

    private static String stringOf(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
