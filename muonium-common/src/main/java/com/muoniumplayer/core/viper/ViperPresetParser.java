package com.muoniumplayer.core.viper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ViPER 预设解析器：把四代「蝰蛇音效配置文件」统一归一化为 schema 2.1 语义单位。
 *
 * <p>支持的输入（与官方 App + 官方转换工具 {@code tools/convert_preset.py} 的覆盖范围一致）：</p>
 * <ol>
 *   <li><b>旧版 XML</b>（{@code <map>} + 数字 ID，如 {@code name="36868"}）——见 {@link ViperLegacyXml}；</li>
 *   <li><b>v1 扁平 JSON</b>（官方 App 早期导出：{@code masterEnabled} / {@code spkMasterEnabled} 命名空间、
 *       {@code ";"} 分隔的数组字符串）；</li>
 *   <li><b>schema 2 分组 JSON</b>（旧编码域，值仍是 App 的旧量纲）；</li>
 *   <li><b>schema 2.1 分组 JSON</b>（当前格式，语义单位）——原样保留并钳位。</li>
 * </ol>
 *
 * <p>处理规则逐条对应官方实现：旧编码 → 语义单位 的换算来自 App 的
 * {@code EffectPrefs.convertOldPresetFloat/Int}（{@link ViperPresetSchema.Conv}），
 * 缺省字段用 App 默认值补齐，越界值钳到 App 的量程。输出保证包含全部 24 个效果分组的完整字段，
 * 未知字段/未知分组无损透传（前向兼容）。</p>
 */
public final class ViperPresetParser {

    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String KEY_SPK_MASTER = "spkMasterEnabled";

    private ViperPresetParser() {
    }

    /**
     * 解析一份预设文本。
     *
     * @param text         文件内容（UTF-8 文本；容忍 BOM）
     * @param fallbackName 缺省名（通常是文件名去掉扩展名）
     * @throws IllegalArgumentException 内容为空、JSON 语法错误、或 XML 不是可识别的 ViPER 预设
     */
    public static ViperPreset parse(String text, String fallbackName) {
        String trimmed = text == null ? "" : stripBom(text).trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("预设内容为空");
        }
        if (trimmed.startsWith("<")) {
            if (!ViperLegacyXml.isLegacyPreset(trimmed)) {
                throw new IllegalArgumentException("XML 不是可识别的 ViPER 预设（缺少 36868 母开关）");
            }
            String name = resolveName(fallbackName, null);
            JsonObject body = buildFromFlat(ViperLegacyXml.toV1(trimmed), false, name, null);
            return new ViperPreset(name, body);
        }

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("预设 JSON 的顶层必须是对象");
            }
            root = parsed.getAsJsonObject();
        } catch (IllegalArgumentException already) {
            throw already;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("预设 JSON 解析失败：" + failure.getMessage(), failure);
        }

        double schema = readSchemaVersion(root);
        String name = resolveName(fallbackName, readString(root.get(ViperPreset.KEY_NAME)));
        Long createdAt = readLong(root.get(ViperPreset.KEY_CREATED_AT));

        JsonObject body;
        if (schema >= ViperPreset.SCHEMA_VERSION) {
            // 当前格式：已是语义单位，只做缺省补齐与钳位。
            body = buildFromGrouped(root, false, name, createdAt);
        } else if (schema >= 2.0) {
            // schema 2：分组形状但仍是旧编码域。
            body = buildFromGrouped(root, true, name, createdAt);
        } else {
            // v1 扁平格式（或没有 schemaVersion 的裸对象）。
            body = buildFromFlat(flatFromJson(root), root.has(KEY_SPK_MASTER), name, createdAt);
        }
        return new ViperPreset(name, body);
    }

    /** 导出为 schema 2.1 的 JSON 文本（与官方 App 导出的形状一致）。 */
    public static String toJsonText(ViperPreset preset) {
        return PRETTY.toJson(preset.json()) + "\n";
    }

    // —— 组装 ——

    /** v1 扁平映射（XML 或 v1 JSON）→ 归一化分组体。旧编码一律参与换算。 */
    private static JsonObject buildFromFlat(Map<String, Object> flat, boolean speaker,
                                            String name, Long createdAt) {
        JsonObject body = header(name, createdAt);
        List<ViperPresetSchema.Group> groups = ViperPresetSchema.groups();
        for (ViperPresetSchema.Group group : groups) {
            JsonObject output = new JsonObject();
            for (ViperPresetSchema.Field field : group.fields) {
                String sourceKey = speaker ? field.spkKey : field.v1Key;
                JsonElement raw = toElement(flat.get(sourceKey));
                output.add(field.key, normalizeField(field, raw, true));
            }
            body.add(group.key, output);
        }
        Object master = flat.get("masterEnabled");
        if (master instanceof Boolean) {
            body.addProperty("masterEnabled", (Boolean) master);
        }
        return body;
    }

    /** 分组 JSON（schema 2 / 2.1）→ 归一化分组体；{@code legacyEncoding} 决定是否换算。 */
    private static JsonObject buildFromGrouped(JsonObject root, boolean legacyEncoding,
                                               String name, Long createdAt) {
        JsonObject body = header(name, createdAt);
        List<ViperPresetSchema.Group> groups = ViperPresetSchema.groups();
        for (ViperPresetSchema.Group group : groups) {
            JsonElement groupElement = root.get(group.key);
            JsonObject source = groupElement != null && groupElement.isJsonObject()
                    ? groupElement.getAsJsonObject() : null;
            JsonObject output = new JsonObject();
            for (ViperPresetSchema.Field field : group.fields) {
                JsonElement raw = source == null ? null : source.get(field.key);
                output.add(field.key, normalizeField(field, raw, legacyEncoding));
            }
            if (source != null) {
                for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                    if (!hasField(group, entry.getKey())) {
                        output.add(entry.getKey(), entry.getValue().deepCopy());
                    }
                }
            }
            body.add(group.key, output);
        }
        // 根层未知键透传（如旧格式遗留的 masterEnabled；跳过已被头部接管的三个键）。
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (ViperPreset.KEY_SCHEMA_VERSION.equals(key)
                    || ViperPreset.KEY_NAME.equals(key)
                    || ViperPreset.KEY_CREATED_AT.equals(key)) {
                continue;
            }
            if (ViperPresetSchema.group(key) != null) {
                continue;
            }
            body.add(key, entry.getValue().deepCopy());
        }
        return body;
    }

    private static JsonObject header(String name, Long createdAt) {
        JsonObject body = new JsonObject();
        body.addProperty(ViperPreset.KEY_SCHEMA_VERSION, ViperPreset.SCHEMA_VERSION);
        if (name != null && !name.isEmpty()) {
            body.addProperty(ViperPreset.KEY_NAME, name);
        }
        if (createdAt != null) {
            body.addProperty(ViperPreset.KEY_CREATED_AT, createdAt);
        }
        return body;
    }

    // —— 单字段归一化 ——

    private static JsonElement normalizeField(ViperPresetSchema.Field field, JsonElement raw,
                                              boolean legacyEncoding) {
        switch (field.kind) {
            case BOOL: {
                Boolean value = readBool(raw);
                return value == null ? defaultOf(field) : new JsonPrimitive(value);
            }
            case INT: {
                Integer value = readInt(raw);
                if (value == null) {
                    return defaultOf(field);
                }
                int normalized = legacyEncoding ? convertInt(field.conv, value) : value;
                if (field.ranged()) {
                    normalized = (int) Math.max(field.min, Math.min(field.max, normalized));
                }
                return new JsonPrimitive(normalized);
            }
            case FLOAT: {
                Double value = readDouble(raw);
                if (value == null) {
                    return defaultOf(field);
                }
                double normalized = legacyEncoding ? convert(field.conv, value) : value;
                if (field.ranged()) {
                    normalized = Math.max(field.min, Math.min(field.max, normalized));
                }
                return new JsonPrimitive(normalized);
            }
            case STRING: {
                String value = readString(raw);
                return value == null ? defaultOf(field) : new JsonPrimitive(value);
            }
            case NULLABLE_LONG: {
                Long value = readLong(raw);
                return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
            }
            case INT_ARRAY:
                return normalizeIntArray(field, raw, legacyEncoding);
            case FLOAT_ARRAY:
            case DOUBLE_ARRAY:
                return normalizeFloatArray(field, raw, legacyEncoding);
            case BOOL_ARRAY:
                return normalizeBoolArray(field, raw);
            default:
                return defaultOf(field);
        }
    }

    private static JsonElement normalizeIntArray(ViperPresetSchema.Field field, JsonElement raw,
                                                 boolean legacyEncoding) {
        List<JsonElement> elements = readArrayElements(raw);
        if (elements == null) {
            return defaultOf(field);
        }
        JsonArray output = new JsonArray();
        for (JsonElement element : elements) {
            Integer value = readInt(element);
            int normalized = value == null ? 0 : (legacyEncoding ? convertInt(field.conv, value) : value);
            if (field.ranged()) {
                normalized = (int) Math.max(field.min, Math.min(field.max, normalized));
            }
            output.add(normalized);
        }
        return output;
    }

    private static JsonElement normalizeFloatArray(ViperPresetSchema.Field field, JsonElement raw,
                                                   boolean legacyEncoding) {
        List<JsonElement> elements = readArrayElements(raw);
        if (elements == null) {
            return defaultOf(field);
        }
        JsonArray output = new JsonArray();
        for (JsonElement element : elements) {
            Double value = readDouble(element);
            double normalized = value == null ? 0.0 : (legacyEncoding ? convert(field.conv, value) : value);
            if (field.ranged()) {
                normalized = Math.max(field.min, Math.min(field.max, normalized));
            }
            output.add(normalized);
        }
        return output;
    }

    private static JsonElement normalizeBoolArray(ViperPresetSchema.Field field, JsonElement raw) {
        List<JsonElement> elements = readArrayElements(raw);
        if (elements == null) {
            return defaultOf(field);
        }
        JsonArray output = new JsonArray();
        for (JsonElement element : elements) {
            output.add(readBoolArrayElement(element));
        }
        return output;
    }

    private static JsonElement defaultOf(ViperPresetSchema.Field field) {
        Object value = field.defaultValue;
        switch (field.kind) {
            case BOOL:
                return new JsonPrimitive((Boolean) value);
            case INT:
                return new JsonPrimitive((Integer) value);
            case FLOAT:
                return new JsonPrimitive((Double) value);
            case STRING:
                return new JsonPrimitive((String) value);
            case NULLABLE_LONG:
                return JsonNull.INSTANCE;
            case INT_ARRAY: {
                JsonArray array = new JsonArray();
                for (int element : (int[]) value) {
                    array.add(element);
                }
                return array;
            }
            case FLOAT_ARRAY:
            case DOUBLE_ARRAY: {
                JsonArray array = new JsonArray();
                for (double element : (double[]) value) {
                    array.add(element);
                }
                return array;
            }
            case BOOL_ARRAY: {
                JsonArray array = new JsonArray();
                for (boolean element : (boolean[]) value) {
                    array.add(element);
                }
                return array;
            }
            default:
                return JsonNull.INSTANCE;
        }
    }

    // —— 换算 ——

    /** 旧编码 → 语义单位（对应 App 的 {@code convertOldPresetFloat}）。 */
    private static double convert(ViperPresetSchema.Conv conv, double value) {
        switch (conv) {
            case DIV100:
                return value / 100.0;
            case DIV10:
                return value / 10.0;
            case DIV25:
                return value / 25.0;
            case DB:
                return value * Math.log(10.0) / 20.0;
            case RATIO:
                return -(value / 100.0);
            case MS:
                return value / 1000.0;
            case ADAPT:
                return Math.pow(4.0, value / 100.0);
            case PLUS15:
                return value + 15.0;
            case DEPTH:
                return value * 75.0 + 200.0;
            case LUFS_TARGET:
                return value / -10.0;
            case DS_STRENGTH:
                return 1.0 + value / 100.0 * 20.0;
            case MID_IMAGE:
                return value / 10.0 + 1.0;
            case EXCITER:
                return value / 100.0 * 5.6;
            case NONE:
            default:
                return value;
        }
    }

    /** 整数域的换算（{@code convertOldPresetInt}）。 */
    private static int convertInt(ViperPresetSchema.Conv conv, int value) {
        switch (conv) {
            case PLUS15:
                return value + 15;
            case DEPTH:
                return value * 75 + 200;
            default:
                return value;
        }
    }

    // —— 读取工具 ——

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static String resolveName(String fallback, String embedded) {
        if (embedded != null && !embedded.trim().isEmpty()) {
            return embedded.trim();
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        return "未命名";
    }

    private static double readSchemaVersion(JsonObject root) {
        JsonElement element = root.get(ViperPreset.KEY_SCHEMA_VERSION);
        if (element == null || !element.isJsonPrimitive()) {
            return 1.0;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return 1.0;
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        if (primitive.isString()) {
            try {
                return Double.parseDouble(primitive.getAsString().trim());
            } catch (NumberFormatException failure) {
                return 1.0;
            }
        }
        return 1.0;
    }

    private static Map<String, Object> flatFromJson(JsonObject root) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                flat.put(entry.getKey(), primitive.getAsBoolean());
            } else if (primitive.isNumber()) {
                flat.put(entry.getKey(), primitive.getAsNumber());
            } else {
                flat.put(entry.getKey(), primitive.getAsString());
            }
        }
        return flat;
    }

    private static JsonElement toElement(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static Boolean readBool(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble() != 0.0;
        }
        if (primitive.isString()) {
            return "true".equalsIgnoreCase(primitive.getAsString().trim());
        }
        return null;
    }

    private static Boolean readBoolArrayElement(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble() != 0.0;
        }
        String text = primitive.getAsString().trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private static Double readDouble(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        if (primitive.isString()) {
            try {
                return Double.parseDouble(primitive.getAsString().trim());
            } catch (NumberFormatException failure) {
                return null;
            }
        }
        return null;
    }

    private static Integer readInt(JsonElement element) {
        Double value = readDouble(element);
        return value == null ? null : (int) value.doubleValue();
    }

    private static Long readLong(JsonElement element) {
        Double value = readDouble(element);
        if (value == null) {
            return null;
        }
        long parsed = (long) value.doubleValue();
        return parsed >= 0 ? parsed : null;
    }

    private static String readString(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** 数组来源：JSON 数组，或 v1 的 {@code ";"} 分隔字符串；都不是返回 {@code null}。 */
    private static List<JsonElement> readArrayElements(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<JsonElement> list = new ArrayList<>(array.size());
            for (int index = 0; index < array.size(); index++) {
                list.add(array.get(index));
            }
            return list;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            List<JsonElement> list = new ArrayList<>();
            for (String token : element.getAsString().split(";", -1)) {
                if (!token.trim().isEmpty()) {
                    list.add(new JsonPrimitive(token.trim()));
                }
            }
            return list;
        }
        return null;
    }

    private static boolean hasField(ViperPresetSchema.Group group, String key) {
        for (ViperPresetSchema.Field field : group.fields) {
            if (field.key.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
