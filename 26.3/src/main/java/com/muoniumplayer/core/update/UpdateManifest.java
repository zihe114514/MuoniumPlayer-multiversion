package com.muoniumplayer.core.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    public static final int KNOWN_SCHEMA = 2;

    /**
     * 一次最多读几条公告。
     *
     * <p>纯粹是防外部输入把界面撑坏：清单是公开仓库上的纯文本，理论上能写多少条写多少条，
     * 而每一条都会在界面建一组组件。真要发更多条，说明该做分页而不是抬这个数。</p>
     */
    public static final int MAX_ANNOUNCEMENTS = 30;

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
    private final List<Announcement> announcements;

    private UpdateManifest(int schema, JsonObject channels, Entry fallback,
                           List<Announcement> announcements) {
        this.schema = schema;
        this.channels = channels;
        this.fallback = fallback;
        this.announcements = announcements;
    }

    /** 解析失败、内容不是对象、既没有 channels 也没有可用 fallback、也没有可用公告时返回 {@code null}。 */
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
            List<Announcement> announcements = readAnnouncements(root.get("announcements"));
            if (channels == null && fallback == null && announcements.isEmpty()) {
                return null;
            }
            return new UpdateManifest(schema, channels, fallback, announcements);
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

    /**
     * 公告列表，按清单数组里的顺序（约定是「最新的写在最前面」）。
     *
     * <p>永远不为 {@code null}，没有公告时是空表。返回的是不可变表：这份解析结果会被
     * 守护线程产出、界面每帧读，共享一个可变列表迟早出事。</p>
     */
    public List<Announcement> announcements() {
        return this.announcements;
    }

    /**
     * 读公告数组。
     *
     * <p>逐条容错：不是数组、元素不是对象、缺 id 或标题正文全空的条目一律跳过，一条坏的连累不到
     * 整批。id 重复时保留先出现的那条 —— 数组顺序就是显示顺序，重复本身就是清单写错了，
     * 但已读记账必须稳定，不能两条抢同一个 id。</p>
     */
    private static List<Announcement> readAnnouncements(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return Collections.emptyList();
        }
        List<Announcement> read = new ArrayList<Announcement>();
        Set<String> seen = new HashSet<String>();
        JsonArray array = element.getAsJsonArray();
        for (int index = 0; index < array.size() && read.size() < MAX_ANNOUNCEMENTS; index++) {
            JsonElement item = array.get(index);
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String id = childString(object, "id");
            String title = childString(object, "title");
            String body = childString(object, "body");
            if (id.isEmpty() || (title.isEmpty() && body.isEmpty())) {
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            read.add(new Announcement(id, childString(object, "date"), childString(object, "kind"),
                    title, body, childString(object, "link"), childString(object, "linkText")));
        }
        return Collections.unmodifiableList(read);
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
