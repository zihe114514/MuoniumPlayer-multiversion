package com.muoniumplayer.core.utils.json;

import com.google.gson.*;
import lombok.experimental.UtilityClass;

import java.io.Reader;

/**
 * @since 2024/11/10 16:59
 */
@UtilityClass
public class JsonUtils {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public JsonObject toJsonObject(String json) {
        return gson.fromJson(json, JsonObject.class);
    }

    public JsonObject toJsonObject(Reader reader) {
        return gson.fromJson(reader, JsonObject.class);
    }

    public JsonObject toJsonObject(JsonElement element) {
        return gson.fromJson(element, JsonObject.class);
    }

    public JsonArray toJsonArray(String json) {
        return gson.fromJson(json, JsonArray.class);
    }

    public JsonArray toJsonArray(Reader reader) {
        return gson.fromJson(reader, JsonArray.class);
    }

    public JsonArray toJsonArray(JsonElement element) {
        return gson.fromJson(element, JsonArray.class);
    }

    public <T> T parse(String json, Class<? extends T> typeClass) {
        return gson.fromJson(json, typeClass);
    }

    public <T> T parse(Reader reader, Class<? extends T> typeClass) {
        return gson.fromJson(reader, typeClass);
    }

    public <T> T parse(JsonElement element, Class<? extends T> typeClass) {
        return gson.fromJson(element, typeClass);
    }

    public String toJsonString(JsonElement element) {
        return gson.toJson(element);
    }

    public String toJsonString(Object src) {
        return gson.toJson(src);
    }

    /**
     * 给定的 JsonObject 是否包含指定名称的字符串字段？
     */
    public static boolean isString(JsonObject jsonObject, String key) {
        return isJsonPrimitive(jsonObject, key) && jsonObject.getAsJsonPrimitive(key).isString();
    }

    /**
     * 给定的 JsonElement 是否为字符串？
     */
    public static boolean isString(JsonElement jsonElement) {
        return jsonElement.isJsonPrimitive() && jsonElement.getAsJsonPrimitive().isString();
    }

    public static boolean isBoolean(JsonObject jsonObject, String key) {
        return isJsonPrimitive(jsonObject, key) && jsonObject.getAsJsonPrimitive(key).isBoolean();
    }

    /**
     * 给定的 JsonObject 是否包含指定名称的数组字段？
     */
    public static boolean isJsonArray(JsonObject jsonObject, String key) {
        return hasField(jsonObject, key) && jsonObject.get(key).isJsonArray();
    }

    /**
     * 给定的 JsonObject 是否包含指定名称、且类型为基本类型（String、Java 基本类型或
     * Java 基本类型包装类）的字段？
     */
    public static boolean isJsonPrimitive(JsonObject jsonObject, String key) {
        return hasField(jsonObject, key) && jsonObject.get(key).isJsonPrimitive();
    }

    /**
     * 给定的 JsonObject 是否包含指定名称的字段？
     */
    public static boolean hasField(JsonObject jsonObject, String key) {
        return jsonObject != null && jsonObject.get(key) != null;
    }

    /**
     * 获取给定 JsonElement 的字符串值。若需要抛出错误信息，第二个参数应为该元素
     * 所在字段的名称。
     */
    public static String getString(JsonElement jsonElement, String key) {
        if (jsonElement.isJsonPrimitive()) {
            return jsonElement.getAsString();
        } else {
            throw new JsonSyntaxException("Expected " + key + " to be a string, was " + toString(jsonElement));
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的字符串值。
     */
    public static String getString(JsonObject jsonObject, String key) {
        if (jsonObject.has(key)) {
            return getString(jsonObject.get(key), key);
        } else {
            throw new JsonSyntaxException("Missing " + key + ", expected to find a string");
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的字符串值；若字段缺失则返回给定默认值。
     */
    public static String getString(JsonObject jsonObject, String key, String fallback) {
        return jsonObject.has(key) ? getString(jsonObject.get(key), key) : fallback;
    }

    /**
     * 获取给定 JsonElement 的布尔值。若需要抛出错误信息，第二个参数应为该元素
     * 所在字段的名称。
     */
    public static boolean getBoolean(JsonElement jsonElement, String key) {
        if (jsonElement.isJsonPrimitive()) {
            return jsonElement.getAsBoolean();
        } else {
            throw new JsonSyntaxException("Expected " + key + " to be a Boolean, was " + toString(jsonElement));
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的布尔值。
     */
    public static boolean getBoolean(JsonObject jsonObject, String key) {
        if (jsonObject.has(key)) {
            return getBoolean(jsonObject.get(key), key);
        } else {
            throw new JsonSyntaxException("Missing " + key + ", expected to find a Boolean");
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的布尔值；若字段缺失则返回给定默认值。
     */
    public static boolean getBoolean(JsonObject jsonObject, String key, boolean fallback) {
        return jsonObject.has(key) ? getBoolean(jsonObject.get(key), key) : fallback;
    }

    /**
     * 获取给定 JsonElement 的 float 值。若需要抛出错误信息，第二个参数应为该元素
     * 所在字段的名称。
     */
    public static float getFloat(JsonElement jsonElement, String key) {
        if (jsonElement.isJsonPrimitive() && jsonElement.getAsJsonPrimitive().isNumber()) {
            return jsonElement.getAsFloat();
        } else {
            throw new JsonSyntaxException("Expected " + key + " to be a Float, was " + toString(jsonElement));
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的 float 值。
     */
    public static float getFloat(JsonObject jsonObject, String key) {
        if (jsonObject.has(key)) {
            return getFloat(jsonObject.get(key), key);
        } else {
            throw new JsonSyntaxException("Missing " + key + ", expected to find a Float");
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的 float 值；若字段缺失则返回给定默认值。
     */
    public static float getFloat(JsonObject jsonObject, String key, float fallback) {
        return jsonObject.has(key) ? getFloat(jsonObject.get(key), key) : fallback;
    }

    /**
     * 获取给定 JsonElement 的整数值。若需要抛出错误信息，第二个参数应为该元素
     * 所在字段的名称。
     */
    public static int getInt(JsonElement jsonElement, String key) {
        if (jsonElement.isJsonPrimitive() && jsonElement.getAsJsonPrimitive().isNumber()) {
            return jsonElement.getAsInt();
        } else {
            throw new JsonSyntaxException("Expected " + key + " to be a Int, was " + toString(jsonElement));
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的整数值。
     */
    public static int getInt(JsonObject jsonObject, String key) {
        if (jsonObject.has(key)) {
            return getInt(jsonObject.get(key), key);
        } else {
            throw new JsonSyntaxException("Missing " + key + ", expected to find a Int");
        }
    }

    /**
     * 获取 JsonObject 上指定名称字段的整数值；若字段缺失则返回给定默认值。
     */
    public static int getInt(JsonObject jsonObject, String key, int fallback) {
        return jsonObject.has(key) ? getInt(jsonObject.get(key), key) : fallback;
    }

    /**
     * 将给定 JsonElement 作为 JsonObject 获取。若需要抛出错误信息，第二个参数应为
     * 该元素所在字段的名称。
     */
    public static JsonObject getJsonObject(JsonElement jsonElement, String key) {
        if (jsonElement.isJsonObject()) {
            return jsonElement.getAsJsonObject();
        } else {
            throw new JsonSyntaxException("Expected " + key + " to be a JsonObject, was " + toString(jsonElement));
        }
    }

    public static JsonObject getJsonObject(JsonObject base, String key) {
        if (base.has(key)) {
            return getJsonObject(base.get(key), key);
        } else {
            throw new JsonSyntaxException("Missing " + key + ", expected to find a JsonObject");
        }
    }

    /**
     * 获取 JsonObject 上指定名称的 JsonObject 字段；若字段缺失则返回给定默认值。
     */
    public static JsonObject getJsonObject(JsonObject jsonObject, String key, JsonObject fallback) {
        return jsonObject.has(key) ? getJsonObject(jsonObject.get(key), key) : fallback;
    }

    /**
     * 将给定 JsonElement 作为 JsonArray 获取。若需要抛出错误信息，第二个参数应为
     * 该元素所在字段的名称。
     */
    public static JsonArray getJsonArray(JsonElement jsonElement, String key) {
        if (jsonElement.isJsonArray()) {
            return jsonElement.getAsJsonArray();
        } else {
            throw new JsonSyntaxException("Expected " + key + " to be a JsonArray, was " + toString(jsonElement));
        }
    }

    /**
     * 获取 JsonObject 上指定名称的 JsonArray 字段。
     */
    public static JsonArray getJsonArray(JsonObject jsonObject, String key) {
        if (jsonObject.has(key)) {
            return getJsonArray(jsonObject.get(key), key);
        } else {
            throw new JsonSyntaxException("Missing " + key + ", expected to find a JsonArray");
        }
    }

    /**
     * 获取 JsonObject 上指定名称的 JsonArray 字段；若字段缺失则返回给定默认值。
     */
    public static JsonArray getJsonArray(JsonObject jsonObject, String key, JsonArray fallback) {
        return jsonObject.has(key) ? getJsonArray(jsonObject.get(key), key) : fallback;
    }

    /**
     * 获取给定 JsonElement 类型的可读描述。例如："a number (4)"
     */
    public static String toString(JsonElement jsonElement) {
        String s = org.apache.commons.lang3.StringUtils.abbreviateMiddle(String.valueOf(jsonElement), "...", 10);

        if (jsonElement == null) {
            return "null (missing)";
        } else if (jsonElement.isJsonNull()) {
            return "null (json)";
        } else if (jsonElement.isJsonArray()) {
            return "an array (" + s + ")";
        } else if (jsonElement.isJsonObject()) {
            return "an object (" + s + ")";
        } else {
            if (jsonElement.isJsonPrimitive()) {
                JsonPrimitive jsonprimitive = jsonElement.getAsJsonPrimitive();

                if (jsonprimitive.isNumber()) {
                    return "a number (" + s + ")";
                }

                if (jsonprimitive.isBoolean()) {
                    return "a boolean (" + s + ")";
                }
            }

            return s;
        }
    }

}
