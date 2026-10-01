package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用户在本机自建的歌单，落在 {@link ConfigPaths#LOCAL_PLAYLISTS}。
 *
 * <p>和 {@link ImportedPlaylistStore} 最大的分别是<b>曲目要整个存下来</b>：导入的歌单存的是
 * {@code 平台:歌单ID}，点开时拿这个 ID 回上游拉；自建歌单没有任何上游歌单可以拉回去，所以每一首
 * 歌都得自己带着「该去哪个平台、用哪个键取流」的信息。</p>
 *
 * <p><b>一张本地单可以混着放各个音源的歌。</b>这不是妥协，而是本来就成立的事实：{@link Music} 自己
 * 带着 {@code source}/{@code sourceId}/{@code sourceMid}，取流走的是逐曲的平台分支，所以播放
 * 一首混合单不需要任何「先切平台」的动作 —— 每首歌按自己的来源解析即可。</p>
 *
 * <p>本地单的 {@code id} 一律是<b>负数</b>。上游各平台的歌单 ID 全是正数，而 {@code id} 会进封面
 * 缓存路径、进各种按 ID 索引的表；用一个不可能撞上的符号区间，比再发明一套「命名空间」字段便宜，
 * 也不会因为哪天上游 ID 变成字符串就失效。</p>
 */
public final class LocalPlaylistStore {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static boolean loaded;
    private static final LinkedHashMap<Long, PlayList> PLAYLISTS = new LinkedHashMap<>();

    private LocalPlaylistStore() {
    }

    /** 建一张空的本地歌单并立刻落盘。重名不拦：用户想要两张都叫「跑步」的单子是合理需求。 */
    public static PlayList create(String name) {
        String title = name == null ? "" : name.trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("歌单名不能为空");
        }
        load();
        long id = nextId();
        // 8 个参数是 PlayList 的 @RequiredArgsConstructor：id、名字、封面、曲目数、播放数、
        // 创建者、描述、创建时间。本地单没有封面（用第一首歌的专辑封面顶上），也没有创建者。
        PlayList playlist = new PlayList(id, title, "", 0, 0L, null, "", System.currentTimeMillis());
        playlist.setPlatform(MusicPlatform.NETEASE);
        playlist.setPlatformPlaylistId("local:" + id);
        playlist.setLocalPlaylist(true);
        playlist.musics = new CopyOnWriteArrayList<>();
        // 这两个标志位是仓库里现成的「曲目已经在手上，别再回上游拉」的写法（见 searchMode 那条路径）：
        // getMusics / loadMusicsWithCallback 会在它们面前早返回，queryMusics 整条链根本不会被调到。
        playlist.musicsQueried = true;
        playlist.musicsLoaded = true;
        synchronized (LOCK) {
            PLAYLISTS.put(id, playlist);
            save();
        }
        return playlist;
    }

    /**
     * 改名。{@code PlayList.name} 是 final（它同时是 {@code @Data} 生成的必填构造器参数），
     * 所以改名只能换对象，不能原地改。
     *
     * @return 换出来的新对象；调用方要拿它去顶掉侧栏行与当前面板里持有的旧引用
     */
    public static PlayList rename(PlayList playlist, String name) {
        String title = name == null ? "" : name.trim();
        if (title.isEmpty() || playlist == null) return playlist;
        long id = playlist.getId();
        PlayList renamed;
        synchronized (LOCK) {
            if (!PLAYLISTS.containsKey(id)) return playlist;
            renamed = new PlayList(id, title, playlist.getCoverUrl(), playlist.getCount(),
                    playlist.getPlayCount(), playlist.getCreator(), playlist.getDescription(),
                    playlist.getCreateTime());
            renamed.setPlatform(playlist.getPlatform());
            renamed.setPlatformPlaylistId(playlist.getPlatformPlaylistId());
            renamed.setLocalPlaylist(true);
            // 同一个曲目列表实例直接接过来：面板正在按引用渲染它，换列表会让这一帧空一下。
            renamed.musics = playlist.musics;
            renamed.musicsQueried = true;
            renamed.musicsLoaded = true;
            PLAYLISTS.put(id, renamed);
            save();
        }
        return renamed;
    }

    public static void remove(PlayList playlist) {
        if (playlist == null) return;
        load();
        synchronized (LOCK) {
            if (PLAYLISTS.remove(playlist.getId()) == null) return;
            save();
        }
    }

    /** 全部本地歌单的快照，按创建顺序。 */
    public static List<PlayList> list() {
        load();
        synchronized (LOCK) {
            return new ArrayList<>(PLAYLISTS.values());
        }
    }

    public static boolean isLocal(PlayList playlist) {
        return playlist != null && playlist.isLocalPlaylist();
    }

    /**
     * 本机有没有任何自建歌单。
     *
     * <p>做成「只读不拷贝」而不是 {@code !list().isEmpty()}：它被歌曲行用来决定「加入歌单」那颗按钮
     * 画不画，一屏两百行、每帧一次。{@link #list()} 每次要 new 一个 ArrayList，那是不必要的垃圾。</p>
     */
    public static boolean hasAny() {
        load();
        synchronized (LOCK) {
            return !PLAYLISTS.isEmpty();
        }
    }

    /**
     * 往本地歌单里加一首。同一首（同平台同键）已经在里面时不重复加。
     *
     * @return 真的加进去了返回 true；这一首本来就在、或目标不是本地单，返回 false
     */
    public static boolean addTrack(PlayList playlist, Music music) {
        if (playlist == null || music == null || !playlist.isLocalPlaylist()) return false;
        load();
        boolean added;
        synchronized (LOCK) {
            if (!PLAYLISTS.containsKey(playlist.getId())) return false;
            if (indexOf(playlist.getMusics(), music) >= 0) return false;
            playlist.getMusics().add(music);
            added = true;
            save();
        }
        return added;
    }

    /** 从本地歌单里移除一首。只动本机，不碰任何上游。 */
    public static boolean removeTrack(PlayList playlist, Music music) {
        if (playlist == null || music == null || !playlist.isLocalPlaylist()) return false;
        synchronized (LOCK) {
            if (!PLAYLISTS.containsKey(playlist.getId())) return false;
            int at = indexOf(playlist.getMusics(), music);
            if (at < 0) return false;
            playlist.getMusics().remove(at);
            save();
            return true;
        }
    }

    /**
     * 这首歌在这个平台上的稳定键。
     *
     * <p>不能只用 {@code Music.id}：网易云/QQ 那种十几个数字的 id 在别的平台上是另一个意思。
     * 也不能先问 {@code getSourceId()}：它在源没给 sourceId 时<b>退化成
     * {@code String.valueOf(id)}</b>（见 {@code Music#getSourceId}），对 id=0 的那几档源
     * （B 站走 mid、GD 有的响应不带数字 id）所有歌会一律算出 {@code 平台:0} —— 去重的时候
     * 第二首歌就永远加不进这张单了。所以顺序是数字 id → mid → 真给过的 sourceId → 歌名+时长。</p>
     */
    static String identityOf(Music music) {
        MusicPlatform source = music.getSource();
        String platform = source == null ? "unknown" : source.name().toLowerCase(Locale.ROOT);
        if (music.getId() != 0L) {
            return platform + ":" + music.getId();
        }
        String mid = music.getSourceMid();
        if (mid != null && !mid.trim().isEmpty()) {
            return platform + ":mid:" + mid.trim();
        }
        String sourceId = music.getSourceId();
        if (sourceId != null && !sourceId.trim().isEmpty() && !"0".equals(sourceId.trim())) {
            return platform + ":" + sourceId.trim();
        }
        return platform + ":name:" + (music.getName() == null ? "" : music.getName())
                + ':' + music.getDuration();
    }

    private static int indexOf(List<Music> musics, Music target) {
        String key = identityOf(target);
        for (int i = 0; i < musics.size(); i++) {
            Music candidate = musics.get(i);
            if (candidate == target) return i;
            if (candidate != null && identityOf(candidate).equals(key)) return i;
        }
        return -1;
    }

    /** 负数、单调、和上游 ID 永不重叠。 */
    private static long nextId() {
        long candidate = -System.currentTimeMillis();
        while (PLAYLISTS.containsKey(candidate)) candidate--;
        return candidate;
    }

    // ════════════════════════════ 持久化 ════════════════════════════

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            PLAYLISTS.clear();
            try {
                JsonObject root = JsonConfigStorage.readObject(ConfigPaths.LOCAL_PLAYLISTS, GSON);
                if (root == null) return;
                JsonArray items = root.getAsJsonArray("playlists");
                if (items == null) return;
                for (JsonElement element : items) {
                    if (!element.isJsonObject()) continue;
                    PlayList playlist = deserializePlaylist(element.getAsJsonObject());
                    if (playlist != null) PLAYLISTS.put(playlist.getId(), playlist);
                }
            } catch (Throwable ignored) {
                // 读坏了就当本地没有自建歌单，不能因此把播放器启动卡住。
                PLAYLISTS.clear();
            }
        }
    }

    private static void save() {
        try {
            JsonArray items = new JsonArray();
            for (PlayList playlist : PLAYLISTS.values()) {
                items.add(serializePlaylist(playlist));
            }
            JsonObject root = new JsonObject();
            root.add("playlists", items);
            JsonConfigStorage.writeObject(ConfigPaths.LOCAL_PLAYLISTS, GSON, root);
        } catch (Throwable ignored) {
            // 写失败只影响下次启动还记不记得，不该让「加这首歌」这件事当场失败。
        }
    }

    private static JsonObject serializePlaylist(PlayList playlist) {
        JsonObject item = new JsonObject();
        item.addProperty("id", playlist.getId());
        item.addProperty("name", playlist.getName());
        item.addProperty("createTime", playlist.getCreateTime());
        JsonArray tracks = new JsonArray();
        List<Music> musics = playlist.getMusics();
        if (musics != null) {
            for (Music music : musics) {
                if (music != null) tracks.add(serializeTrack(music));
            }
        }
        item.add("tracks", tracks);
        return item;
    }

    /**
     * 一首歌 = Gson 眼里的普通字段 + 一小包 {@code _x}。
     *
     * <p>必须分开写：{@code source}/{@code sourceId}/{@code sourceMid} 以及各源那些私有键
     * （酷狗三个 hash、GD 三个 id）在 {@link Music} 上全是 {@code transient}，而 Gson 默认
     * <b>跳过 transient 字段</b>。直接 {@code toJsonTree(music)} 会得到一份看着完整、实际没有任何
     * 平台信息的 JSON，重启之后这些歌全都退化成「按网易云去取」。</p>
     *
     * <p>反过来，那些<b>不该</b>落盘的运行时状态也一律不写：一起听下发的临时链接、封面代表色、
     * 解灰结果、本次点播解析到的实际音质、一次性 play_auth。它们描述的是「上一次点播发生了什么」。</p>
     */
    /** 包内可见是为了让 {@code LocalPlaylistStoreTest} 能直接喂一首歌进去测往返；不给 public。 */
    static JsonObject serializeTrack(Music music) {
        JsonObject track = GSON.toJsonTree(music).getAsJsonObject();
        JsonObject x = new JsonObject();
        MusicPlatform source = music.getSource();
        x.addProperty("source", source == null ? "" : source.name().toLowerCase(Locale.ROOT));
        // getSourceId() 在源没给 sourceId 时会退化成 String.valueOf(id)。那不是一个真键：
        // 把它当 sourceId 存下来，读回来就成了「这首歌有 sourceId」的假事实，而下游那些
        // 「先按 sourceId 问」的链路（歌词、封面）会拿着网易云的编号去问别的平台。
        String sourceId = music.getSourceId();
        if (sourceId != null && !sourceId.equals(String.valueOf(music.getId()))) {
            addIfPresent(x, "sourceId", sourceId);
        }
        addIfPresent(x, "sourceMid", music.getSourceMid());
        addIfPresent(x, "cover", music.getExternalCoverUrl());
        addIfPresent(x, "gdPlatform", music.getGdPlatform());
        addIfPresent(x, "gdPicId", music.getGdPicId());
        addIfPresent(x, "gdLyricId", music.getGdLyricId());
        addIfPresent(x, "kugouHash", music.getKugouHash());
        addIfPresent(x, "kugouHash320", music.getKugouHash320());
        addIfPresent(x, "kugouHashLossless", music.getKugouHashLossless());
        addIfPresent(x, "kugouAlbumId", music.getKugouAlbumId());
        addIfPresent(x, "kugouAlbumAudioId", music.getKugouAlbumAudioId());
        addIfPresent(x, "kugouCoverTemplate", music.getKugouCoverTemplate());
        if (music.isCloudSong()) x.addProperty("cloudSong", true);
        track.add("_x", x);
        return track;
    }

    static Music deserializeTrack(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject track = element.getAsJsonObject();
        JsonObject x = track.has("_x") && track.get("_x").isJsonObject()
                ? track.getAsJsonObject("_x") : new JsonObject();
        track.remove("_x");
        Music music;
        try {
            music = GSON.fromJson(track, Music.class);
        } catch (Throwable malformed) {
            return null;
        }
        if (music == null) return null;
        music.setSource(resolvePlatform(string(x, "source")));
        music.setSourceId(string(x, "sourceId"));
        music.setSourceMid(string(x, "sourceMid"));
        music.setExternalCoverUrl(string(x, "cover"));
        music.setGdPlatform(string(x, "gdPlatform"));
        music.setGdPicId(string(x, "gdPicId"));
        music.setGdLyricId(string(x, "gdLyricId"));
        music.setKugouHash(string(x, "kugouHash"));
        music.setKugouHash320(string(x, "kugouHash320"));
        music.setKugouHashLossless(string(x, "kugouHashLossless"));
        music.setKugouAlbumId(string(x, "kugouAlbumId"));
        music.setKugouAlbumAudioId(string(x, "kugouAlbumAudioId"));
        music.setKugouCoverTemplate(string(x, "kugouCoverTemplate"));
        music.setCloudSong(x.has("cloudSong") && truthy(x, "cloudSong"));
        return music;
    }

    private static PlayList deserializePlaylist(JsonObject item) {
        if (!item.has("id") || item.get("id").isJsonNull()) return null;
        long id = item.get("id").getAsLong();
        // 只认负数：正数 id 属于上游歌单，混进来的话说明这份存档被别的文件污染了，跳过比猜安全。
        if (id >= 0L) return null;
        String name = string(item, "name");
        if (name.trim().isEmpty()) return null;
        long createTime = item.has("createTime") && !item.get("createTime").isJsonNull()
                ? item.get("createTime").getAsLong() : 0L;

        List<Music> musics = new CopyOnWriteArrayList<>();
        JsonArray tracks = item.getAsJsonArray("tracks");
        if (tracks != null) {
            for (JsonElement element : tracks) {
                Music music = deserializeTrack(element);
                if (music != null) musics.add(music);
            }
        }

        PlayList playlist = new PlayList(id, name, "", musics.size(), 0L, null, "", createTime);
        playlist.setPlatform(MusicPlatform.NETEASE);
        playlist.setPlatformPlaylistId("local:" + id);
        playlist.setLocalPlaylist(true);
        playlist.musics = musics;
        playlist.musicsQueried = true;
        playlist.musicsLoaded = true;
        return playlist;
    }

    private static void addIfPresent(JsonObject target, String key, String value) {
        if (value != null && !value.isEmpty()) target.addProperty(key, value);
    }

    private static MusicPlatform resolvePlatform(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return MusicPlatform.NETEASE;
        for (MusicPlatform platform : MusicPlatform.values()) {
            if (platform.name().toLowerCase(Locale.ROOT).equals(key)) return platform;
        }
        return MusicPlatform.NETEASE;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static boolean truthy(JsonObject object, String key) {
        try {
            return !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
