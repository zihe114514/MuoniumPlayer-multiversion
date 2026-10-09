package com.muoniumplayer.core.viper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 一份已归一化的 ViPER 预设（schema 2.1，语义单位）。
 *
 * <p>内部直接持有一个 Gson {@link JsonObject}：这是刻意的选择——预设字段多达 120 余项、
 * 且官方格式仍在演进，用「JSON 直存」可以无损保留我们尚未认识的字段与分组（前向兼容），
 * 导出的文本也与官方 App 的形状一致。常用路径（主限幅链）通过
 * {@link #masterThreshold()} 这类访问器读取，带完整的缺省与类型保护。</p>
 *
 * <p>字段的权威定义见 {@link ViperPresetSchema}；解析入口见 {@link ViperPresetParser}。</p>
 */
public final class ViperPreset {

    /** 归一化输出的 schema 版本，与官方 App 的 {@code PRESET_SCHEMA_VERSION} 对齐。 */
    public static final double SCHEMA_VERSION = 2.1;

    static final String KEY_SCHEMA_VERSION = "schemaVersion";
    static final String KEY_NAME = "name";
    static final String KEY_CREATED_AT = "createdAt";

    private final String name;
    private final JsonObject body;

    ViperPreset(String name, JsonObject body) {
        this.name = name;
        this.body = body;
    }

    /** 预设名（文件内嵌名优先，其次导入时的文件名）。 */
    public String name() {
        return name;
    }

    /** 归一化后的 schema 2.1 预设体（可直接导出为 JSON）。 */
    public JsonObject json() {
        return body;
    }

    /** 取一个效果分组；不存在返回 {@code null}。 */
    public JsonObject group(String key) {
        JsonElement element = body.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** 是否带有旧格式的母开关（{@code masterEnabled} 根键；新格式不含，仅作信息保留）。 */
    public boolean legacyMasterEnabled() {
        return bool("masterEnabled", false);
    }

    /** 读一个根层布尔。 */
    public boolean bool(String rootKey, boolean fallback) {
        JsonElement element = body.get(rootKey);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsJsonPrimitive().isBoolean()
                    ? element.getAsBoolean()
                    : element.getAsString().equalsIgnoreCase("true");
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    /** 读一个组内数值字段（缺失或非数值时返回 {@code fallback}）。 */
    public double number(String groupKey, String fieldKey, double fallback) {
        JsonObject group = group(groupKey);
        if (group == null) {
            return fallback;
        }
        JsonElement element = group.get(fieldKey);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    /** 读一个组内字符串字段。 */
    public String text(String groupKey, String fieldKey, String fallback) {
        JsonObject group = group(groupKey);
        if (group == null) {
            return fallback;
        }
        JsonElement element = group.get(fieldKey);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    /** masterLimiter.threshold：限幅门限（语义域 0.3..1.0，1.0 表示只约束超过 1.0 的峰值）。 */
    public double masterThreshold() {
        return clamp(number("masterLimiter", "threshold", 1.0), 0.0, 1.0);
    }

    /** masterLimiter.outputVolume：输出音量（语义域 0.01..2.0，1.0 = 原样）。 */
    public double masterOutputVolume() {
        return clamp(number("masterLimiter", "outputVolume", 1.0), 0.0, 8.0);
    }

    /** masterLimiter.channelPan：声道平衡（语义域 -1..1，0 = 居中）。 */
    public double masterChannelPan() {
        return clamp(number("masterLimiter", "channelPan", 0.0), -1.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
