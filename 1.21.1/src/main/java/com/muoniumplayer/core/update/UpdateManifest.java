package com.muoniumplayer.core.update;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 远端版本清单（{@code update.json}）的解析结果。
 *
 * <p>清单放在一个<b>公开</b>仓库里，代码仓库是私有的：客户端必须能匿名读到清单，
 * 而 raw.githubusercontent.com 对私有仓库要带 token。清单格式与查表顺序见
 * {@code tools/update-manifest/README.md}。</p>
 *
 * <p>解析全程容错：坏 JSON、缺字段、类型不对、schema 比本地新，一律退化成
 * {@code null} 或缺省值，绝不抛给调用方 —— 更新提示失败的正确表现是「什么都不提示」，
 * 而不是在启动路径上炸一个异常。</p>
 */
public final class UpdateManifest {

    /** 本地认识的清单版本。清单声明得更高时仍然按已知字段读，未知字段忽略。 */
    public static final int KNOWN_SCHEMA = 1;

    private static final Gson GSON = new Gson();

    /** 一个「Minecraft 版本 + 加载器」组合上的最新发布。 */
    public static final class Entry {

        private final String latest;
        private final String page;
        private final String notes;
        private final boolean critical;

        public Entry(String latest, String page, String notes, boolean critical) {
            this.latest = latest == null ? "" : latest.trim();
            this.page = page == null ? "" : page.trim();
            this.notes = notes == null ? "" : notes.trim();
            this.critical = critical;
        }

        /** 远端声明的最新版本号，例如 {@code 1.4.0}。 */
        public String latest() {
            return this.latest;
        }

        /** 下载页。可能为空，也可能不在白名单里，由 {@code UpdateChecker} 兜底。 */
        public String page() {
            return this.page;
        }

        /** 更新说明，可为空。 */
        public String notes() {
            return this.notes;
        }

        /** true 时界面上不提供「跳过这个版本」。 */
        public boolean critical() {
            return this.critical;
        }
    }

    private final int schema;
    private final JsonObject channels;
    private final Entry fallback;

    private UpdateManifest(int schema, JsonObject channels, Entry fallback) {
        this.schema = schema;
        this.channels = channels;
        this.fallback = fallback;
    }

    /** 解析失败、内容不是对象、既没有 channels 也没有可用 fallback 时返回 {@code null}。 */
    public static UpdateManifest parse(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            JsonObject root = GSON.fromJson(trimmed, JsonObject.class);
            if (root == null) {
                return null;
            }
            int schema = KNOWN_SCHEMA;
            JsonElement schemaElement = root.get("schema");
            if (schemaElement != null && schemaElement.isJsonPrimitive()) {
                try {
                    schema = schemaElement.getAsInt();
                } catch (RuntimeException notANumber) {
                    schema = KNOWN_SCHEMA;
                }
            }
            JsonObject channels = childObject(root, "channels");
            Entry fallback = readEntry(childObject(root, "fallback"));
            if (channels == null && fallback == null) {
                return null;
            }
            return new UpdateManifest(schema, channels, fallback);
        } catch (Throwable malformed) {
            return null;
        }
    }

    public int schema() {
        return this.schema;
    }

    /**
     * 查表顺序：{@code <mc>-<loader>} → {@code <mc>} → {@code fallback}。
     *
     * <p>两级 key 的意义：多加载器单仓下同一个 MC 版本会发两个 jar，通常两边同时发布
     * （写 {@code <mc>} 一条就够），但偶尔会只修其中一侧（这时写细的那条 key）。</p>
     */
    public Entry entryFor(String mcVersion, String loaderId) {
        Entry entry = null;
        if (this.channels != null) {
            if (notBlank(mcVersion) && notBlank(loaderId)) {
                entry = readEntry(childObject(this.channels, mcVersion.trim() + "-" + loaderId.trim()));
            }
            if (entry == null && notBlank(mcVersion)) {
                entry = readEntry(childObject(this.channels, mcVersion.trim()));
            }
        }
        return entry != null ? entry : this.fallback;
    }

    private static Entry readEntry(JsonObject object) {
        if (object == null) {
            return null;
        }
        String latest = childString(object, "latest");
        if (latest.isEmpty()) {
            // 没有 latest 的条目没有任何意义：宁可当作「这一条不存在」去查下一级。
            return null;
        }
        return new Entry(latest, childString(object, "page"), childString(object, "notes"),
                childBoolean(object, "critical"));
    }

    private static JsonObject childObject(JsonObject parent, String key) {
        if (parent == null || key == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String childString(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            String value = element.getAsString();
            return value == null ? "" : value.trim();
        } catch (RuntimeException notAString) {
            return "";
        }
    }

    private static boolean childBoolean(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return false;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
