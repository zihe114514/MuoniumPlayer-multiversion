package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 用户用「粘贴分享链接」跨平台导入的歌单引用，持久化在 {@link ConfigPaths#IMPORTED_PLAYLISTS}。
 *
 * <p>只存引用（平台 + 歌单 ID + 标题 + 封面 + 曲目数），不存曲目：点开时由
 * {@link PlayList#queryMusics()} 按平台匿名向上游拉取，所以导入天然跟随上游变化，落盘体积极小，
 * 也和任何账号凭据文件解耦——未登录也能导入，退出登录不会丢导入的歌单。</p>
 *
 * <p>键是 {@code 平台名:平台歌单ID} 而不是裸歌单 ID：网易云、QQ、汽水的 ID 都是十几个数字，
 * 不同平台间随时可能撞号，撞上的后果是两个平台的链接互相覆盖本地引用。</p>
 *
 * <p>首次运行时若新文件不存在，会把旧版汽水的 {@link ConfigPaths#QISHUI_PLAYLISTS} 合并进来；
 * 只在「新文件还不存在」时做一次，之后删除导入项不会因为旧存档而复活。</p>
 */
public final class ImportedPlaylistStore {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static boolean loaded;
    /** 以 {@code 平台名:平台歌单ID} 为键，保持导入顺序。 */
    private static final LinkedHashMap<String, PlayList> PLAYLISTS = new LinkedHashMap<>();

    private ImportedPlaylistStore() {
    }

    /** 返回全部已导入歌单的快照（点开时才匿名向上游拉曲目）。 */
    public static List<PlayList> list() {
        load();
        synchronized (LOCK) {
            return new ArrayList<>(PLAYLISTS.values());
        }
    }

    /** 只返回某一平台导入的歌单。 */
    public static List<PlayList> list(MusicPlatform platform) {
        List<PlayList> result = new ArrayList<>();
        if (platform == null) return result;
        for (PlayList playlist : list()) {
            if (playlist.getPlatform() == platform) result.add(playlist);
        }
        return result;
    }

    public static boolean contains(PlayList playlist) {
        String key = keyOf(playlist);
        if (key == null) return false;
        load();
        synchronized (LOCK) {
            return PLAYLISTS.containsKey(key);
        }
    }

    /**
     * 落盘一条导入的歌单引用。同一平台同一歌单已存在时刷新其标题/封面/曲目数并返回 false；
     * 新增时返回 true。
     *
     * @throws IllegalArgumentException 传入的歌单缺平台或平台歌单 ID，无法定位上游
     */
    public static boolean add(PlayList playlist) {
        String key = keyOf(playlist);
        if (key == null) {
            throw new IllegalArgumentException("导入的歌单缺少平台或平台歌单 ID");
        }
        load();
        synchronized (LOCK) {
            boolean isNew = !PLAYLISTS.containsKey(key);
            PLAYLISTS.put(key, playlist);
            save();
            return isNew;
        }
    }

    /** 按歌单自身（平台 + 平台歌单 ID）移除本地引用；不影响上游，也不涉及账号。 */
    public static void remove(PlayList playlist) {
        String key = keyOf(playlist);
        if (key == null) return;
        remove(key);
    }

    public static void remove(String key) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.isEmpty()) return;
        load();
        synchronized (LOCK) {
            if (PLAYLISTS.remove(normalized) == null) return;
            save();
        }
    }

    /** 存储键：{@code 平台名:平台歌单ID}；缺平台或缺 ID 时返回 {@code null}。 */
    public static String keyOf(PlayList playlist) {
        if (playlist == null || playlist.getPlatform() == null) return null;
        String id = playlist.getPlatformPlaylistId();
        if (id == null || id.trim().isEmpty()) return null;
        return playlist.getPlatform().name().toLowerCase(Locale.ROOT) + ":" + id.trim();
    }

    // ════════════════════════════ 持久化 ════════════════════════════

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                if (ConfigPaths.IMPORTED_PLAYLISTS.isFile()) {
                    readInto(ConfigPaths.IMPORTED_PLAYLISTS);
                    return;
                }
                // 旧版只有汽水一个渠道，存档键是裸歌单 ID；迁移即全部归到 qishui 名下。
                if (ConfigPaths.QISHUI_PLAYLISTS.isFile()) {
                    readInto(ConfigPaths.QISHUI_PLAYLISTS);
                    save();
                }
            } catch (Throwable ignored) {
                // 读坏了就当没有导入过任何歌单，不能因此阻断播放器启动。
                PLAYLISTS.clear();
            }
        }
    }

    private static void readInto(File file) throws java.io.IOException {
        JsonObject root = JsonConfigStorage.readObject(file, GSON);
        if (root == null) return;
        JsonArray items = root.getAsJsonArray("playlists");
        if (items == null) return;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            PlayList playlist = deserialize(element.getAsJsonObject());
            String key = keyOf(playlist);
            if (key != null) PLAYLISTS.put(key, playlist);
        }
    }

    private static void save() {
        try {
            File file = ConfigPaths.IMPORTED_PLAYLISTS;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonArray items = new JsonArray();
            for (PlayList playlist : PLAYLISTS.values()) {
                items.add(serialize(playlist));
            }
            JsonObject root = new JsonObject();
            root.add("playlists", items);
            JsonConfigStorage.writeObject(file, GSON, root);
        } catch (Throwable ignored) {
            // 写失败只影响下次启动能不能记住，不该把当前操作也一起失败掉。
        }
    }

    private static JsonObject serialize(PlayList playlist) {
        JsonObject item = new JsonObject();
        // 连同派生的 long id 一起存：还原时直接用，避免把 stableLong 逻辑复制一份到此处而失去一致。
        item.addProperty("id", playlist.getId());
        item.addProperty("platform", playlist.getPlatform().name().toLowerCase(Locale.ROOT));
        item.addProperty("playlistId", playlist.getPlatformPlaylistId());
        item.addProperty("name", playlist.getName());
        item.addProperty("cover", playlist.getCoverUrl());
        item.addProperty("count", playlist.getCount());
        return item;
    }

    private static PlayList deserialize(JsonObject item) {
        String id = string(item, "playlistId");
        if (id.isEmpty()) return null;
        MusicPlatform platform = resolvePlatform(string(item, "platform"));
        if (platform == null) return null;
        long stableId = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsLong() : 0L;
        String name = string(item, "name");
        String cover = string(item, "cover");
        int count = item.has("count") && !item.get("count").isJsonNull() ? item.get("count").getAsInt() : 0;
        PlayList playlist = new PlayList(stableId, name, cover, Math.max(0, count), 0L, null,
                "", 0L);
        playlist.setPlatform(platform);
        playlist.setPlatformPlaylistId(id);
        return playlist;
    }

    /** 旧汽水存档没有 platform 字段，缺省时归到 QISHUI。 */
    private static MusicPlatform resolvePlatform(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return MusicPlatform.QISHUI;
        for (MusicPlatform platform : MusicPlatform.values()) {
            if (platform.name().toLowerCase(Locale.ROOT).equals(key)) return platform;
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
