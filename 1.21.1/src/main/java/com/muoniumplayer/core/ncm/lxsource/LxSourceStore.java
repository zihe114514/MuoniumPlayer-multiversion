package com.muoniumplayer.core.ncm.lxsource;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 已导入的自定义源脚本，以及当前启用的是哪一个。
 *
 * <p>脚本正文按上游的格式存：{@code "gz_" + base64(deflate(utf8))}
 * （见 lx-music-desktop 的 {@code userApi/utils.ts}）。照抄这个格式不是为了省空间，
 * 是为了让两边的存档可以互相搬 —— 用户从 LX 的配置里拷一段过来，这边能直接读。</p>
 */
public final class LxSourceStore {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static boolean loaded;
    private static String activeId = "";
    private static final List<Entry> ENTRIES = new ArrayList<>();

    private LxSourceStore() {
    }

    /** 一条已导入的脚本记录。{@code script} 是压缩后的正文，不直接给外部用。 */
    public static final class Entry {
        public final String id;
        public final LxScriptInfo info;
        public final boolean allowUpdateAlert;
        private final String storedScript;

        Entry(String id, LxScriptInfo info, boolean allowUpdateAlert, String storedScript) {
            this.id = id;
            this.info = info;
            this.allowUpdateAlert = allowUpdateAlert;
            this.storedScript = storedScript;
        }

        public String getSource() {
            return inflateScript(storedScript);
        }
    }

    public static List<Entry> list() {
        load();
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(ENTRIES));
        }
    }

    public static String getActiveId() {
        load();
        synchronized (LOCK) {
            return activeId;
        }
    }

    public static Entry find(String id) {
        load();
        synchronized (LOCK) {
            for (Entry entry : ENTRIES) {
                if (entry.id.equals(id)) return entry;
            }
            return null;
        }
    }

    public static Entry getActive() {
        String id = getActiveId();
        return id.isEmpty() ? null : find(id);
    }

    /** 空字符串表示关闭自定义源。 */
    public static void setActiveId(String id) {
        load();
        synchronized (LOCK) {
            activeId = id == null ? "" : id;
            save();
        }
    }

    /**
     * @throws IllegalArgumentException 头注释缺失，或正文与已导入的某个脚本完全相同
     */
    public static Entry importScript(String rawSource) {
        LxScriptInfo info = LxScriptInfo.parse(rawSource);
        String stored = deflateScript(rawSource);
        load();
        synchronized (LOCK) {
            for (Entry entry : ENTRIES) {
                if (entry.storedScript.equals(stored)) {
                    throw new IllegalArgumentException("导入失败：内容与已有的源「" + entry.info.getName() + "」相同");
                }
            }
            String id = "user_api_" + Integer.toString((int) (Math.random() * 900 + 100)) + "_" + System.currentTimeMillis();
            Entry entry = new Entry(id, info, true, stored);
            ENTRIES.add(entry);
            save();
            return entry;
        }
    }

    public static void remove(String id) {
        load();
        synchronized (LOCK) {
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                if (ENTRIES.get(i).id.equals(id)) ENTRIES.remove(i);
            }
            if (activeId.equals(id)) activeId = "";
            save();
        }
    }

    public static void setAllowUpdateAlert(String id, boolean allow) {
        load();
        synchronized (LOCK) {
            for (int i = 0; i < ENTRIES.size(); i++) {
                Entry entry = ENTRIES.get(i);
                if (!entry.id.equals(id)) continue;
                ENTRIES.set(i, new Entry(entry.id, entry.info, allow, entry.storedScript));
            }
            save();
        }
    }

    // ════════════════════════════ 持久化 ════════════════════════════

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.LX_SOURCES;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                if (root.has("activeId") && !root.get("activeId").isJsonNull()) {
                    activeId = root.get("activeId").getAsString();
                }
                JsonArray scripts = root.getAsJsonArray("scripts");
                if (scripts == null) return;
                for (JsonElement element : scripts) {
                    JsonObject item = element.getAsJsonObject();
                    String stored = string(item, "script");
                    if (stored.isEmpty()) continue;
                    String id = string(item, "id");
                    if (id.isEmpty()) continue;
                    // 名字等元信息重新从正文解析：脚本更新后头注释可能变了，
                    // 而落盘的那份是旧的，以正文为准才不会在列表里显示成过期的版本号。
                    LxScriptInfo info;
                    try {
                        info = LxScriptInfo.parse(inflateScript(stored));
                    } catch (RuntimeException broken) {
                        continue;
                    }
                    boolean allow = !item.has("allowUpdateAlert") || item.get("allowUpdateAlert").getAsBoolean();
                    ENTRIES.add(new Entry(id, info, allow, stored));
                }
            } catch (Throwable ignored) {
                // 配置读坏了就当没有导入过任何源，不能因此阻断播放器启动。
            }
        }
    }

    private static void save() {
        try {
            File parent = ConfigPaths.LX_SOURCES.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("activeId", activeId);
            JsonArray scripts = new JsonArray();
            for (Entry entry : ENTRIES) {
                JsonObject item = new JsonObject();
                item.addProperty("id", entry.id);
                item.addProperty("name", entry.info.getName());
                item.addProperty("description", entry.info.getDescription());
                item.addProperty("version", entry.info.getVersion());
                item.addProperty("author", entry.info.getAuthor());
                item.addProperty("homepage", entry.info.getHomepage());
                item.addProperty("allowUpdateAlert", entry.allowUpdateAlert);
                item.addProperty("script", entry.storedScript);
                scripts.add(item);
            }
            root.add("scripts", scripts);
            JsonConfigStorage.writeObject(ConfigPaths.LX_SOURCES, GSON, root);
        } catch (Throwable ignored) {
            // 写失败只影响下次启动能不能记住，不该把当前操作也一起失败掉。
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String deflateScript(String raw) {
        return "gz_" + Base64.getEncoder().encodeToString(
                LxUtils.deflate(raw == null ? "" : raw).getBytes());
    }

    private static String inflateScript(String stored) {
        if (stored == null) return "";
        if (!stored.startsWith("gz_")) return stored;
        try {
            byte[] compressed = Base64.getDecoder().decode(stored.substring(3));
            return new String(LxUtils.inflate(LxBuffer.of(compressed)).getBytes(), StandardCharsets.UTF_8);
        } catch (Exception broken) {
            return "";
        }
    }
}