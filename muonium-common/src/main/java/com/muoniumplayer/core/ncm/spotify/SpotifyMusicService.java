package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.PlayList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spotify 的宿主适配层：Web API 的原始 JSON ↔ 播放器的 {@code Music}/{@code PlayList}。
 *
 * <h3>这个平台的边界要先说清楚</h3>
 * <p>Spotify 官方 Web API <b>没有任何音频出口</b>——2024-11 起 {@code preview_url} 从响应里
 * 彻底下线，连 30 秒试听都不再提供。所以这里产出的每一行都是<b>元数据行</b>：
 * 点播时 {@code Music#getPlayUrl()} 返回空，由跨平台换源流水线（{@code CrossSourceSwitcher}）
 * 也是官方条款下唯一做得成的形态。</p>
 *
 * <h3>「喜欢的歌曲」是一张虚拟歌单</h3>
 * {@code spotify-liked} 同一招：编一个 {@value #LIKED_PLAYLIST_ID} 当
 * {@code platformPlaylistId}，取曲目时翻译成 {@code /me/tracks}。歌单 id 都是 base62、
 * 不会撞上这个词。</p>
 */
public final class SpotifyMusicService {

    /** 虚拟歌单：用户喜欢的歌曲（{@code /me/tracks}）。见类注释。 */
    public static final String LIKED_PLAYLIST_ID = "liked";
    /** 专辑当歌单卡片时的前缀（与酷狗的 {@code album:} 约定一致）。 */
    public static final String ALBUM_PREFIX = "album:";

    /** 搜索单页上限。Spotify 端点上限 50，留出分页空间。 */
    public static final int SEARCH_LIMIT_MAX = 50;

    private SpotifyMusicService() {
    }

    // ── 模型 ────────────────────────────────────────────────────────────────

    /** 一条 Spotify 曲目的元数据。{@code artist} 用 " / " 分隔，方便映射时拆回多歌手。 */
    public static final class Track {
        public String id = "";
        public String name = "";
        public String artist = "";
        public String album = "";
        public String albumId = "";
        public long durationMillis;
        public String coverUrl = "";
        /** 本地文件（用户上传）行：没有可跨平台匹配的规范化元数据，直接跳过。 */
        public boolean local;
    }

    /** 一张 Spotify 歌单 / 专辑的卡片元数据。 */
    public static final class Playlist {
        public String id = "";
        public String name = "";
        public String coverUrl = "";
        public int trackCount;
        public String owner = "";
    }

    // ── 搜索 / 内容 ─────────────────────────────────────────────────────────

    /** 搜曲目。失败（含未登录）返回空列表，让 UI 与换源流水线按「没有结果」处理。 */
    public static List<Track> search(String keyword, int limit) {
        return search(keyword, limit, 0);
    }

    public static List<Track> search(String keyword, int limit, int offset) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        Map<String, String> query = baseSearchQuery(keyword, Math.max(1, Math.min(limit, SEARCH_LIMIT_MAX)));
        if (offset > 0) query.put("offset", String.valueOf(offset));
        query.put("type", "track");
        try {
            JsonObject payload = SpotifyWebApiClient.getJson("/search", query);
            return readTracks(payload, "tracks");
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            logFailure("搜索", throwable);
            return Collections.emptyList();
        }
    }

    /** 搜公开歌单（关键词）。换源流水线不用它；它是歌单搜索框的 SP 结果。 */
    public static List<Playlist> searchPlaylists(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        Map<String, String> query = baseSearchQuery(keyword, Math.max(1, Math.min(limit, SEARCH_LIMIT_MAX)));
        query.put("type", "playlist");
        try {
            JsonObject payload = SpotifyWebApiClient.getJson("/search", query);
            return readPlaylists(payload, "playlists");
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            logFailure("搜歌单", throwable);
            return Collections.emptyList();
        }
    }

    /**
     * 本人歌单 + 「喜欢的歌曲」虚拟单。liked 排最前，账号资料缺失时跳过它但绝不挡住真实歌单。
     */
    public static List<Playlist> userPlaylists(int limit) {
        List<Playlist> out = new ArrayList<>();
        try {
            JsonObject me = SpotifyWebApiClient.getJson("/me", null);
            SpotifyAuthStore.updateProfile(text(me, "display_name"), text(me, "id"),
                    firstImageUrl(me.getAsJsonArray("images")));
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            logFailure("账号资料", throwable);
        }
        Playlist liked = likedPlaylistCard();
        if (liked != null) out.add(liked);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("limit", String.valueOf(Math.max(1, Math.min(limit, 50))));
        try {
            JsonObject payload = SpotifyWebApiClient.getJson("/me/playlists", query);
            out.addAll(readPlaylists(payload, "items"));
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            logFailure("本人歌单", throwable);
        }
        return out;
    }

    /**
     * 取一串曲目。{@code platformPlaylistId} 三种形状：
     * {@value #LIKED_PLAYLIST_ID}（虚拟单）、{@code album:<id>}（专辑）、裸 base62（普通歌单）。
     */
    public static List<Track> playlistTracks(String platformPlaylistId, int max) {
        String id = platformPlaylistId == null ? "" : platformPlaylistId.trim();
        try {
            if (id.equals(LIKED_PLAYLIST_ID)) return likedTracks(Math.max(1, max));
            if (id.startsWith(ALBUM_PREFIX)) return albumTracks(id.substring(ALBUM_PREFIX.length()),
                    Math.max(1, max));
            return playlistTracksInternal(id, Math.max(1, max));
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            logFailure("取歌单曲目", throwable);
            return Collections.emptyList();
        }
    }

    /** 专辑当卡片：封面与曲目数来自专辑本体，曲目由 {@link #playlistTracks} 的前缀分支拉。 */
    public static Playlist albumCard(String albumId) {
        if (albumId == null || albumId.trim().isEmpty()) return null;
        try {
            JsonObject payload = SpotifyWebApiClient.getJson("/albums/" + albumId.trim(), null);
            Playlist card = new Playlist();
            card.id = ALBUM_PREFIX + albumId.trim();
            card.name = text(payload, "name");
            card.coverUrl = firstImageUrl(payload.getAsJsonArray("images"));
            card.trackCount = payload.has("total_tracks") && payload.get("total_tracks").isJsonPrimitive()
                    ? payload.get("total_tracks").getAsInt() : 0;
            card.owner = "Spotify 专辑";
            return card;
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            logFailure("专辑详情", throwable);
            return null;
        }
    }

    // ── 账号 ────────────────────────────────────────────────────────────────

    /** 本人歌单的侧栏快照（含「喜欢的歌曲」虚拟单）。语义与酷狗那份一致：异步加载 + 失败冷却。 */
    private static final Object PLAYLIST_LOCK = new Object();
    private static final long PLAYLIST_RETRY_DELAY_MS = 15_000L;
    private static volatile java.util.List<PlayList> userPlaylistsSnapshot = Collections.emptyList();
    private static volatile boolean userPlaylistsLoaded;
    private static volatile boolean userPlaylistsLoading;
    private static volatile long userPlaylistsLastAttempt;

    public static java.util.List<PlayList> getUserPlaylistsSnapshot() {
        return new ArrayList<>(userPlaylistsSnapshot);
    }

    public static boolean areUserPlaylistsLoaded() {
        return userPlaylistsLoaded;
    }

    public static boolean areUserPlaylistsLoading() {
        return userPlaylistsLoading;
    }

    /** 回调运行在后台线程；UI 调用方要自己切回主线程。 */
    public static void ensureUserPlaylistsAsync(final Runnable callback) {
        if (!isLoggedIn()) {
            clearPlaylistCache();
            if (callback != null) callback.run();
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (PLAYLIST_LOCK) {
            if (userPlaylistsLoading) return;
            if (userPlaylistsLoaded) {
                if (callback != null) callback.run();
                return;
            }
            // 失败后短时间内不重试：登录态失效时每次开面板都重打一次请求毫无意义。
            if (now - userPlaylistsLastAttempt < PLAYLIST_RETRY_DELAY_MS) return;
            userPlaylistsLoading = true;
            userPlaylistsLastAttempt = now;
        }
        Thread thread = new Thread(() -> {
            try {
                refreshUserPlaylists();
            } catch (Throwable throwable) {
                System.err.println("[Music/Spotify] 本人歌单加载失败: " + throwable.getMessage());
            } finally {
                synchronized (PLAYLIST_LOCK) {
                    userPlaylistsLoading = false;
                }
                if (callback != null) {
                    try {
                        callback.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "Spotify Account");
        thread.setDaemon(true);
        thread.start();
    }

    /** 强制重拉账号歌单（侧栏刷新按钮用）。结果在后台线程落快照。 */
    public static void refreshUserPlaylists() {
        List<PlayList> cards = new ArrayList<>();
        Playlist liked = likedPlaylistCard();
        if (liked != null) {
            PlayList card = toPlayListCard(liked);
            if (card != null) cards.add(card);
        }
        int offset = 0;
        while (offset < 500) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", "50");
            query.put("offset", String.valueOf(offset));
            JsonObject payload;
            try {
                payload = SpotifyWebApiClient.getJson("/me/playlists", query);
            } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
                logFailure("本人歌单", throwable);
                return; // 失败保留旧快照：上次的结果还在，别把侧栏刷空。
            }
            List<Playlist> page = readPlaylists(payload, "items");
            if (page.isEmpty()) break;
            for (Playlist playlist : page) {
                PlayList card = toPlayListCard(playlist);
                if (card != null) cards.add(card);
            }
            offset += page.size();
        }
        userPlaylistsSnapshot = cards;
        userPlaylistsLoaded = true;
    }

    private static void clearPlaylistCache() {
        userPlaylistsSnapshot = Collections.emptyList();
        userPlaylistsLoaded = false;
    }

    public static boolean isLoggedIn() {
        return SpotifyAuthStore.isLoggedIn();
    }

    public static String getAccountName() {
        SpotifyAuthStore.Session session = SpotifyAuthStore.snapshot();
        if (session.isReauthRequired() && !session.tokenReady()) return "授权已过期，请重新登录";
        if (!session.loggedIn) return "未登录";
        return session.accountName.isEmpty() ? "Spotify 账号" : session.accountName;
    }

    public static String getAvatarUrl() {
        return SpotifyAuthStore.snapshot().avatarUrl;
    }

    /** 登录状态一句话描述；登录页与账号卡共用。 */
    public static String describeStatus() {
        SpotifyAuthStore.Session session = SpotifyAuthStore.snapshot();
        if (session.isReauthRequired() && !session.canRefresh()) return "凭据已失效，请重新授权";
        if (session.isReauthRequired()) return "凭据疑似过期，稍后会自动重试；失败请重新授权";
        if (!session.loggedIn) return "未登录：粘贴 Client ID 并完成浏览器授权后，可浏览本人歌单与喜欢的歌";
        return "已登录：" + (session.accountName.isEmpty() ? "Spotify 账号" : session.accountName)
                + " · 播放始终由跨平台换源匹配";
    }

    /** 退出登录：清凭据与账号歌单快照。 */
    public static void logout() {
        SpotifyAuthStore.clear();
        clearPlaylistCache();
    }

    /** Client ID 是否已配置好（登录页据此决定先让用户粘贴还是直接开授权）。 */
    public static boolean isConfigured() {
        return SpotifyConfigStore.snapshot().configured();
    }

    /** 配置的一句话诊断；形状不对的 Client ID 在这里被拦下并说清原因。 */
    public static String describeConfig() {
        SpotifyConfigStore.Config config = SpotifyConfigStore.snapshot();
        if (config.clientId.isEmpty()) {
            return "未配置：先到 developer.spotify.com/dashboard 自建应用（勾选 Web API，"
                    + "Redirect URI 填 " + SpotifyConfigStore.REDIRECT_URI + "），再把 Client ID 粘进来";
        }
        if (!config.clientIdLooksValid()) return "Client ID 形状不对：应是一串约 32 位的字母数字";
        return "已配置 Client ID（market " + config.market + "）";
    }

    // ── 内部：端点与解析 ─────────────────────────────────────────────────────

    private static Map<String, String> baseSearchQuery(String keyword, int limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("q", keyword.trim());
        query.put("limit", String.valueOf(limit));
        // market 决定「该发行区域可见」的曲目是否出现；跟随配置，缺省 US。
        query.put("market", SpotifyConfigStore.snapshot().market);
        return query;
    }

    private static List<Track> likedTracks(int max) throws IOException {
        List<Track> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < max) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", String.valueOf(Math.min(50, max - out.size())));
            query.put("offset", String.valueOf(offset));
            JsonObject payload = SpotifyWebApiClient.getJson("/me/tracks", query);
            int page = readTracksInto(out, payload.getAsJsonArray("items"));
            if (page == 0) break;
            offset += page;
            if (offset >= 2000) break; // 安全阀：JSON 分页异常时别无限拉。
        }
        return out;
    }

    private static List<Track> playlistTracksInternal(String playlistId, int max) throws IOException {
        List<Track> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < max) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", String.valueOf(Math.min(100, max - out.size())));
            query.put("offset", String.valueOf(offset));
            JsonObject payload = SpotifyWebApiClient.getJson("/playlists/" + playlistId + "/tracks", query);
            int page = readTracksInto(out, payload.getAsJsonArray("items"));
            if (page == 0) break;
            offset += page;
            if (offset >= 2000) break;
        }
        return out;
    }

    private static List<Track> albumTracks(String albumId, int max) throws IOException {
        List<Track> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < max) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", String.valueOf(Math.min(50, max - out.size())));
            query.put("offset", String.valueOf(offset));
            JsonObject payload = SpotifyWebApiClient.getJson("/albums/" + albumId + "/tracks", query);
            int page = readAlbumTracksInto(out, payload, albumId, max - out.size());
            if (page == 0) break;
            offset += page;
            if (offset >= 2000) break;
        }
        return out;
    }

    private static Playlist likedPlaylistCard() {
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", "1");
            JsonObject payload = SpotifyWebApiClient.getJson("/me/tracks", query);
            Playlist card = new Playlist();
            card.id = LIKED_PLAYLIST_ID;
            card.name = "喜欢的歌曲";
            card.trackCount = payload.has("total") && payload.get("total").isJsonPrimitive()
                    ? payload.get("total").getAsInt() : 0;
            card.owner = "我";
            return card;
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            logFailure("喜欢的歌曲", throwable);
            return null;
        }
    }

    /** {@code /search} 的响应：{@code tracks.items} / {@code playlists.items}。 */
    private static List<Track> readTracks(JsonObject payload, String member) {
        if (payload == null || !payload.has(member)) return Collections.emptyList();
        JsonObject section = payload.getAsJsonObject(member);
        if (section == null || !section.has("items")) return Collections.emptyList();
        List<Track> out = new ArrayList<>();
        readTracksInto(out, section.getAsJsonArray("items"));
        return out;
    }

    /** 常规曲目分页条目：{@code item.track}。返回本页读到的条数（含被跳过的本地行）。 */
    private static int readTracksInto(List<Track> out, JsonArray items) {
        if (items == null) return 0;
        int count = 0;
        for (JsonElement element : items) {
            if (element == null || !element.isJsonObject()) continue;
            count++;
            JsonObject wrapper = element.getAsJsonObject();
            JsonObject track = wrapper.has("track") && wrapper.get("track").isJsonObject()
                    ? wrapper.getAsJsonObject("track") : wrapper;
            Track parsed = readTrack(track);
            if (parsed != null) out.add(parsed);
        }
        return count;
    }

    /** 专辑曲目端点不含 album/cover 对象，封面与专辑名从专辑本体补。 */
    private static int readAlbumTracksInto(List<Track> out, JsonObject payload, String albumId, int limit) {
        JsonArray items = payload == null ? null : payload.getAsJsonArray("items");
        if (items == null) return 0;
        JsonObject album = albumId == null || albumId.isEmpty() ? null : fetchAlbumForTracks(albumId);
        String albumName = album == null ? "" : text(album, "name");
        String cover = album == null ? "" : firstImageUrl(album.getAsJsonArray("images"));
        int count = 0;
        for (JsonElement element : items) {
            if (element == null || !element.isJsonObject()) continue;
            count++;
            if (out.size() >= limit && limit > 0) continue;
            Track parsed = readTrack(element.getAsJsonObject());
            if (parsed == null) continue;
            parsed.album = albumName;
            parsed.albumId = albumId == null ? "" : albumId;
            parsed.coverUrl = cover;
            out.add(parsed);
        }
        return count;
    }

    private static JsonObject fetchAlbumForTracks(String albumId) {
        try {
            return SpotifyWebApiClient.getJson("/albums/" + albumId, null);
        } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
            return null;
        }
    }

    /** 单条曲目对象 → 模型。本地文件与无 id 的行返回 null（没法跨平台匹配，留着只会添乱）。 */
    private static Track readTrack(JsonObject track) {
        if (track == null) return null;
        Track out = new Track();
        out.local = track.has("is_local") && track.get("is_local").isJsonPrimitive()
                && track.get("is_local").getAsBoolean();
        out.id = text(track, "id");
        if (out.local || out.id.isEmpty()) return null;
        out.name = text(track, "name");
        out.durationMillis = track.has("duration_ms") && track.get("duration_ms").isJsonPrimitive()
                ? track.get("duration_ms").getAsLong() : 0L;
        if (track.has("artists") && track.get("artists").isJsonArray()) {
            out.artist = joinArtistNames(track.getAsJsonArray("artists"));
        }
        if (track.has("album") && track.get("album").isJsonObject()) {
            JsonObject album = track.getAsJsonObject("album");
            out.album = text(album, "name");
            out.albumId = text(album, "id");
            out.coverUrl = firstImageUrl(album.getAsJsonArray("images"));
        }
        return out;
    }

    private static List<Playlist> readPlaylists(JsonObject payload, String member) {
        List<Playlist> out = new ArrayList<>();
        JsonArray items = null;
        if (payload != null && payload.has(member)) {
            JsonObject section = payload.getAsJsonObject(member);
            if (section != null && section.has("items")) items = section.getAsJsonArray("items");
        } else if (payload != null && payload.has("items")) {
            // /me/playlists 直接就是 {items: [...]}。
            items = payload.getAsJsonArray("items");
        }
        if (items == null) return out;
        for (JsonElement element : items) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String id = text(object, "id");
            if (id.isEmpty()) continue;
            Playlist card = new Playlist();
            card.id = id;
            card.name = text(object, "name");
            card.trackCount = object.has("tracks") && object.getAsJsonObject("tracks").has("total")
                    ? object.getAsJsonObject("tracks").get("total").getAsInt() : 0;
            card.coverUrl = firstImageUrl(object.getAsJsonArray("images"));
            if (object.has("owner") && object.get("owner").isJsonObject()) {
                card.owner = text(object.getAsJsonObject("owner"), "display_name");
            }
            out.add(card);
        }
        return out;
    }

    private static String joinArtistNames(JsonArray artists) {
        StringBuilder out = new StringBuilder();
        for (JsonElement element : artists) {
            if (element == null || !element.isJsonObject()) continue;
            String name = text(element.getAsJsonObject(), "name");
            if (name.isEmpty()) continue;
            if (out.length() > 0) out.append(" / ");
            out.append(name);
        }
        return out.toString();
    }

    private static String firstImageUrl(JsonArray images) {
        if (images == null || images.size() == 0) return "";
        JsonElement first = images.get(0);
        if (first == null || !first.isJsonObject()) return "";
        return text(first.getAsJsonObject(), "url");
    }

    private static String text(JsonObject object, String member) {
        try {
            if (object == null || !object.has(member) || object.get(member).isJsonNull()) return "";
            return object.get(member).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 失败记账。未登录与需重新授权是「预期内」的状态（UI 有引导位），其余如实进日志 ——
     * 搜索静默失败只会表现为「SP 什么都搜不到」，没有日志就是无头案。
     */
    private static void logFailure(String what, Throwable throwable) {
        if (throwable instanceof SpotifyWebApiClient.SpotifyApiException) {
            SpotifyWebApiClient.SpotifyApiException api = (SpotifyWebApiClient.SpotifyApiException) throwable;
            if (api.reauthRequired) {
                SpotifyAuthStore.markReauthRequired();
            }
        }
        System.err.println("[Music/Spotify] " + what + "失败: " + throwable.getMessage());
    }

    // ── PlayList 卡片适配 ────────────────────────────────────────────────────

    /** 把一张 Spotify 歌单 / 专辑映射成播放器的歌单卡片；曲目点开时再拉。 */
    public static PlayList toPlayListCard(Playlist playlist) {
        if (playlist == null || playlist.id == null || playlist.id.trim().isEmpty()) return null;
        String platformId = playlist.id.trim();
        PlayList card = new PlayList(stableId(platformId), nonBlank(playlist.name, "Spotify 歌单"),
                playlist.coverUrl, Math.max(0, playlist.trackCount), 0L, null, "", 0L);
        card.setPlatform(com.muoniumplayer.core.ncm.music.MusicPlatform.SPOTIFY);
        card.setPlatformPlaylistId(platformId);
        return card;
    }

    /** 由平台主键推稳定 long id（FNV-1a，与 {@code KuGouMusicService#stableId} 同式）。 */
    private static long stableId(String platformId) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : platformId.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String nonBlank(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }
}
