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

/**
 * 用户从汽水音乐分享链接匿名导入的歌单引用，持久化在 {@link ConfigPaths#QISHUI_PLAYLISTS}。
 *
 * <p>只存引用（歌单 ID + 标题 + 封面 + 曲目数），不存曲目：点开时由
 * {@link QishuiMusicService#playlistTracks(String, int)} 匿名向上游拉取，因此导入天然跟随
 * 上游变化，落盘体积极小。</p>
 *
 * <p>与 {@link QishuiSessionStore} 的登录凭据完全解耦：未登录也能导入，退出登录也不会清掉已
 * 导入的歌单。读写范式对齐 {@code LxSourceStore}——一个 JSON 文件存一个列表，懒加载、失败兜底，
 * 绝不因为一份坏存档阻断播放器启动。</p>
 */
public final class QishuiLocalPlaylistStore {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static boolean loaded;
    /** 以汽水产品侧歌单 ID 为键，保持导入顺序。 */
    private static final LinkedHashMap<String, PlayList> PLAYLISTS = new LinkedHashMap<>();

    private QishuiLocalPlaylistStore() {
    }

    /** 返回全部已导入歌单的快照（每次点开都会匿名重新拉曲目）。 */
    public static List<PlayList> list() {
        load();
        synchronized (LOCK) {
            return new ArrayList<>(PLAYLISTS.values());
        }
    }

    public static boolean contains(String playlistId) {
        String id = playlistId == null ? "" : playlistId.trim();
        if (id.isEmpty()) return false;
        load();
        synchronized (LOCK) {
            return PLAYLISTS.containsKey(id);
        }
    }

    /**
     * 落盘一条导入的歌单引用。同一歌单 ID 已存在时刷新其标题/封面/曲目数并返回 false；
     * 新增时返回 true。
     *
     * @throws IllegalArgumentException 传入的不是汽水歌单，或缺少可用的歌单 ID
     */
    public static boolean add(PlayList playlist) {
        if (playlist == null || playlist.getPlatform() != MusicPlatform.QISHUI) {
            throw new IllegalArgumentException("只能导入汽水音乐歌单");
        }
        String id = playlist.getPlatformPlaylistId();
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("歌单缺少汽水 ID");
        }
        load();
        synchronized (LOCK) {
            boolean isNew = !PLAYLISTS.containsKey(id);
            PLAYLISTS.put(id, playlist);
            save();
            return isNew;
        }
    }

    public static void remove(String playlistId) {
        String id = playlistId == null ? "" : playlistId.trim();
        if (id.isEmpty()) return;
        load();
        synchronized (LOCK) {
            if (PLAYLISTS.remove(id) == null) return;
            save();
        }
    }

    // ════════════════════════════ 持久化 ════════════════════════════

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.QISHUI_PLAYLISTS;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                JsonArray items = root.getAsJsonArray("playlists");
                if (items == null) return;
                for (JsonElement element : items) {
                    if (!element.isJsonObject()) continue;
                    PlayList playlist = deserialize(element.getAsJsonObject());
                    if (playlist != null) PLAYLISTS.put(playlist.getPlatformPlaylistId(), playlist);
                }
            } catch (Throwable ignored) {
                // 读坏了就当没有导入过任何歌单，不能因此阻断播放器启动。
                PLAYLISTS.clear();
            }
        }
    }

    private static void save() {
        try {
            File file = ConfigPaths.QISHUI_PLAYLISTS;
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
        item.addProperty("playlistId", playlist.getPlatformPlaylistId());
        item.addProperty("name", playlist.getName());
        item.addProperty("cover", playlist.getCoverUrl());
        item.addProperty("count", playlist.getCount());
        return item;
    }

    private static PlayList deserialize(JsonObject item) {
        String id = string(item, "playlistId");
        if (id.isEmpty()) return null;
        long stableId = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsLong() : 0L;
        String name = string(item, "name");
        String cover = string(item, "cover");
        int count = item.has("count") && !item.get("count").isJsonNull() ? item.get("count").getAsInt() : 0;
        PlayList playlist = new PlayList(stableId, name, cover, Math.max(0, count), 0L, null,
                "", 0L);
        playlist.setPlatform(MusicPlatform.QISHUI);
        playlist.setPlatformPlaylistId(id);
        return playlist;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
