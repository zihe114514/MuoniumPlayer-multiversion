package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.PlayList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple Music 的宿主适配层：AMP API 的原始 JSON ↔ 播放器的 {@code Music}/{@code PlayList}。
 *
 * <h3>这个平台的边界要先说清楚</h3>
 * <p>Apple 的目录能力（搜索/歌单/榜单/歌词）是公开的；但<b>完整音轨是 FairPlay DRM 保护</b>，
 * 官方接口只有 30 秒 preview。所以这里产出的行是「半元数据行」：点播先走本机引擎
 * （{@code AppleNativeEngine}，用户自备订阅 cookies）拉完整版，拿不到由换源流水线找替身，
 * 最后才落 preview 兜底。与 Spotify 同理，Apple 行<b>绝不进换源候选表</b>——它没有完整流
 * 可以给别人换。</p>
 *
 * <h3>platformPlaylistId 的形状约定</h3>
 * <ul>
 *   <li>{@code pl.…}：catalog 公开歌单（Apple 分享链接里的形态）。</li>
 *   <li>{@code p.数字}：本人资料库歌单（{@code /v1/me/library/playlists}）。</li>
 *   <li>{@code album:<id>}：专辑当歌单卡片（与酷狗/Spotify 的 {@code album:} 约定一致）。</li>
 *   <li>{@value #LIBRARY_SONGS_PLAYLIST_ID}：资料库歌曲虚拟单（对应 Spotify 的「喜欢的歌」）。</li>
 * </ul>
 */
public final class AppleMusicService {

    /** 虚拟歌单：资料库歌曲（{@code /v1/me/library/songs}）。 */
    public static final String LIBRARY_SONGS_PLAYLIST_ID = "library-songs";
    /** 专辑当歌单卡片时的前缀。 */
    public static final String ALBUM_PREFIX = "album:";

    /** AMP 单次搜索每种资源的上限留 25；请求量留给分页。 */
    public static final int SEARCH_LIMIT_MAX = 25;

    private static final Pattern ARTWORK_SIZE = Pattern.compile("\\{w\\}x\\{h\\}");
    private static final Pattern SONG_ID_IN_LINK = Pattern.compile("[?&]i=(\\d+)");
    private static final Pattern SONG_PATH = Pattern.compile("/song/[^/]+/(\\d+)");
    private static final Pattern ALBUM_PATH = Pattern.compile("/album/[^/]+/(\\d+)");
    private static final Pattern PLAYLIST_PATH = Pattern.compile("/playlists?/[^/?#]+/(pl\\.[A-Za-z0-9._-]+)");
    private static final Pattern LIBRARY_PLAYLIST_PATH =
            Pattern.compile("/library/playlist/(p\\.\\d+)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private AppleMusicService() {
    }

    /**
     * amp-api 传输：纯 Java 直连（HttpClient）。amp_api.py 助手已退役，
     * 失败即按该能力的空值语义返回（搜索空表/歌词空串/卡片 null），
     * 由调用方按「无数据」画，不再回落 Python。</p>
     */
    private static <T> T viaTransport(java.util.function.Supplier<T> direct) {
        try {
            T result = direct.get();
            if (result != null) return result;
        } catch (RuntimeException throwable) {
            System.err.println("[Music/Apple] Java 直连失败: " + throwable.getMessage());
        }
        return null;
    }

    // ── 模型 ────────────────────────────────────────────────────────────────

    /** 一条 Apple 曲目的元数据。{@code artist} 用 " / " 分隔，方便映射时拆回多歌手。 */
    public static final class Track {
        public String id = "";
        public String name = "";
        public String artist = "";
        public String album = "";
        public String albumId = "";
        public long durationMillis;
        public String coverUrl = "";
        /** 官方 30 秒试听地址；preview 兜底播放与试听按钮用。 */
        public String previewUrl = "";
    }

    /** 一张 Apple 歌单 / 专辑的卡片元数据。 */
    public static final class Playlist {
        public String id = "";
        public String name = "";
        public String coverUrl = "";
        public int trackCount;
        public String owner = "";
    }

    // ── 搜索 / 内容 ─────────────────────────────────────────────────────────

    /**
     * 搜曲目。失败（含网络被墙）返回空列表，让 UI 与换源流水线按「没有结果」处理。
     *
     * <p>纯 Java 直连(HttpClient)。历史上的 TLS 指纹结论已被推翻:毒变量是
     * HttpURLConnection 的请求形态,换 HttpClient 即过(tools/apple-native-probe 实测)。</p>
     */
    public static List<Track> search(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        return viaTransport(() -> {
            try {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("term", keyword.trim());
                query.put("types", "songs");
                query.put("limit", String.valueOf(Math.max(1, Math.min(limit, SEARCH_LIMIT_MAX))));
                JsonObject payload = AppleApiClient.getCatalog(catalogPath("/search", query));
                List<Track> tracks = readSearchTracks(payload, "songs");
                return tracks.isEmpty() ? null : tracks;
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 搜索失败: " + throwable.getMessage());
                return null;
            }
        });
    }



    /** 搜公开歌单（关键词）。歌单搜索框的 Apple 结果。助手优先，同 {@link #search}。 */
    public static List<Playlist> searchPlaylists(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        // 助手没有 searchPlaylists 子命令：catalog 歌单搜索对 Java 也 401（TLS 指纹），
        // v1 暂以「搜歌曲」代替出现概率最高的需求，歌单搜索框在助手缺失时才走直连。
        Map<String, String> query = new LinkedHashMap<>();
        query.put("term", keyword.trim());
        query.put("types", "playlists");
        query.put("limit", String.valueOf(Math.max(1, Math.min(limit, SEARCH_LIMIT_MAX))));
        try {
            JsonObject payload = AppleApiClient.getCatalog(catalogPath("/search", query));
            return readSearchPlaylists(payload, "playlists");
        } catch (IOException | AppleApiClient.AppleApiException throwable) {
            logFailure("搜歌单", throwable);
            return Collections.emptyList();
        }
    }

    /**
     * 取一串曲目。{@code platformPlaylistId} 四种形状：
     * {@value #LIBRARY_SONGS_PLAYLIST_ID}（资料库虚拟单）、{@code p.数字}（资料库歌单）、
     * {@code album:<id>}（专辑）、{@code pl.…}（catalog 公开歌单）。
     * 全部走助手（Python 指纹），助手缺失才回退 Java 直连。
     */
    public static List<Track> playlistTracks(String platformPlaylistId, int max) {
        String id = platformPlaylistId == null ? "" : platformPlaylistId.trim();
        // 各 viaHelper 分支已各自捕获降级（助手→直连→空表），这里只做形状分派。
        if (id.equals(LIBRARY_SONGS_PLAYLIST_ID)) return librarySongsViaHelper(LIBRARY_SONGS_PLAYLIST_ID, max);
        if (id.startsWith("p.")) return libraryPlaylistTracksViaHelper(id, Math.max(1, max));
        if (id.startsWith(ALBUM_PREFIX)) {
            return albumTracksViaHelper(id.substring(ALBUM_PREFIX.length()));
        }
        return catalogPlaylistTracksViaHelper(id, Math.max(1, max));
    }

    private static List<Track> librarySongsViaHelper(String playlistId, int max) {
        return viaTransport(() -> {
            try {
                List<Track> tracks = librarySongs(max);
                return tracks.isEmpty() ? null : tracks;
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 资料库歌曲失败: " + throwable.getMessage());
                return null;
            }
        });
    }

    private static List<Track> libraryPlaylistTracksViaHelper(String playlistId, int max) {
        return viaTransport(() -> {
            try {
                List<Track> tracks = libraryPlaylistTracks(playlistId, max);
                return tracks.isEmpty() ? null : tracks;
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 资料库歌单曲目失败: " + throwable.getMessage());
                return null;
            }
        });
    }

    private static List<Track> albumTracksViaHelper(String albumId) {
        return viaTransport(() -> {
            try {
                List<Track> tracks = albumTracks(albumId, Integer.MAX_VALUE);
                return tracks.isEmpty() ? null : tracks;
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 专辑曲目失败: " + throwable.getMessage());
                return null;
            }
        });
    }

    private static List<Track> catalogPlaylistTracksViaHelper(String playlistId, int max) {
        return viaTransport(() -> {
            try {
                List<Track> tracks = catalogPlaylistTracks(playlistId, max);
                return tracks.isEmpty() ? null : tracks;
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 歌单曲目失败: " + throwable.getMessage());
                return null;
            }
        });
    }

    /** 专辑当卡片：封面与曲目数来自专辑本体，曲目由 {@link #playlistTracks} 的前缀分支拉。 */
    public static Playlist albumCard(String albumId) {
        if (albumId == null || albumId.trim().isEmpty()) return null;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("extend", "extendedAssetUrls");
        try {
            JsonObject payload = AppleApiClient.getCatalog(
                    catalogPath("/catalog/" + AppleConfigStore.storefront() + "/albums/"
                            + albumId.trim(), query));
            JsonArray data = payload.getAsJsonArray("data");
            if (data == null || data.size() == 0) return null;
            return readPlaylistCard(data.get(0).getAsJsonObject(), "Apple Music 专辑");
        } catch (IOException | AppleApiClient.AppleApiException throwable) {
            System.err.println("[Music/Apple] Java 专辑卡片失败: " + throwable.getMessage());
        }
        return null;
    }

    /** catalog 公开歌单当卡片（链接导入用）；资料库歌单走账号快照，不经过这里。 */
    public static Playlist playlistCard(String playlistId) {
        String id = playlistId == null ? "" : playlistId.trim();
        if (id.isEmpty()) return null;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("extend", "extendedAssetUrls");
        try {
            JsonObject payload = AppleApiClient.getCatalog(
                    catalogPath("/catalog/" + AppleConfigStore.storefront() + "/playlists/" + id, query));
            JsonArray data = payload.getAsJsonArray("data");
            if (data == null || data.size() == 0) return null;
            return readPlaylistCard(data.get(0).getAsJsonObject(), "Apple Music 歌单");
        } catch (IOException | AppleApiClient.AppleApiException throwable) {
            logFailure("歌单详情", throwable);
            return null;
        }
    }

    // ── 歌词 / 试听 ─────────────────────────────────────────────────────────

    /**
     * 拉一首歌的 TTML 歌词（逐词/逐音节，Apple 官方形态）。助手优先（TLS 指纹），
     * 直连降级同 {@link #search}。
     *
     * <p>账号态（media-user-token）在时拿到的是逐词版；游客态经常拿不到 {@code ttml}
     * 字段，返回空串由调用方落到 AMLL DB 兜底。返回原始 TTML 字符串，由
     * {@code LyricParser.parseTtml} 统一进时间轴。</p>
     */
    public static String ttml(String songId) {
        String id = songId == null ? "" : songId.trim();
        if (id.isEmpty()) return "";
        return viaTransport(() -> {
            try {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("extend", "extendedAssetUrls");
                query.put("include", "lyrics");
                JsonObject payload = AppleApiClient.getCatalog(
                        catalogPath("/catalog/" + AppleConfigStore.storefront() + "/songs/" + id, query));
                JsonArray data = payload.getAsJsonArray("data");
                if (data == null || data.size() == 0) return null;
                JsonObject relationships = data.get(0).getAsJsonObject().getAsJsonObject("relationships");
                if (relationships == null) return null;
                JsonArray lyrics = relationships.getAsJsonArray("lyrics");
                if (lyrics == null) return null;
                JsonObject lyricsRelation = lyrics.size() > 0 ? lyrics.get(0).getAsJsonObject() : null;
                JsonArray lyricData = lyricsRelation == null ? null
                        : lyricsRelation.getAsJsonArray("data");
                if (lyricData == null) return null;
                for (JsonElement element : lyricData) {
                    if (element == null || !element.isJsonObject()) continue;
                    JsonObject attributes = element.getAsJsonObject().getAsJsonObject("attributes");
                    String ttml = attributes == null ? "" : text(attributes, "ttml");
                    if (!ttml.isEmpty()) return ttml;
                }
                return null;
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 歌词失败: " + throwable.getMessage());
                return null;
            }
        });
    }

    /** 单曲详情：preview 兜底播放要的 30 秒试听地址。助手优先，拿不到返回空串。 */
    public static String previewUrl(String songId) {
        String id = songId == null ? "" : songId.trim();
        if (id.isEmpty()) return "";
        return viaTransport(() -> {
            try {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("extend", "extendedAssetUrls");
                JsonObject payload = AppleApiClient.getCatalog(
                        catalogPath("/catalog/" + AppleConfigStore.storefront() + "/songs/" + id, query));
                JsonArray data = payload.getAsJsonArray("data");
                if (data == null || data.size() == 0) return null;
                String url = previewOf(data.get(0).getAsJsonObject());
                return url.isEmpty() ? null : url;
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 试听地址失败: " + throwable.getMessage());
                return null;
            }
        });
    }


    // ── 分享链接解析 ─────────────────────────────────────────────────────────

    /** 从分享链接/裸 id 提取歌曲 id；认不出返回空串。 */
    public static String extractSongId(String input) {
        return extract(input, SONG_PATH, SONG_ID_IN_LINK, DIGITS);
    }

    /** 从分享链接/裸 id 提取专辑 id。 */
    public static String extractAlbumId(String input) {
        return extract(input, ALBUM_PATH, null, DIGITS);
    }

    /** 从分享链接/裸 id 提取 catalog 歌单 id（{@code pl.…}）。 */
    public static String extractPlaylistId(String input) {
        return extract(input, PLAYLIST_PATH, null, PL_ID);
    }

    /** 从资料库歌单链接提取 id（{@code p.数字}）。 */
    public static String extractLibraryPlaylistId(String input) {
        return extract(input, LIBRARY_PLAYLIST_PATH, null, P_ID);
    }

    private static final Pattern PL_ID = Pattern.compile("pl\\.[A-Za-z0-9._-]+");
    private static final Pattern P_ID = Pattern.compile("p\\.\\d+");

    private static String extract(String input, Pattern path, Pattern query, Pattern bare) {
        if (input == null) return "";
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "";
        if (bare.matcher(trimmed).matches()) return trimmed;
        if (query != null) {
            Matcher queryMatch = query.matcher(trimmed);
            if (queryMatch.find()) return queryMatch.group(1);
        }
        Matcher pathMatch = path.matcher(trimmed);
        if (pathMatch.find()) return pathMatch.group(1);
        return "";
    }

    // ── 账号 ────────────────────────────────────────────────────────────────

    /** 本人歌单的侧栏快照（资料库歌曲虚拟单排最前）。语义与 Spotify 那份一致：异步加载 + 失败冷却。 */
    private static final Object PLAYLIST_LOCK = new Object();
    private static final long PLAYLIST_RETRY_DELAY_MS = 15_000L;
    private static volatile List<PlayList> userPlaylistsSnapshot = Collections.emptyList();
    private static volatile boolean userPlaylistsLoaded;
    private static volatile boolean userPlaylistsLoading;
    private static volatile long userPlaylistsLastAttempt;

    public static List<PlayList> getUserPlaylistsSnapshot() {
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
            // 失败后短时间内不重试：cookies 失效时每次开面板都重打一次请求毫无意义。
            if (now - userPlaylistsLastAttempt < PLAYLIST_RETRY_DELAY_MS) return;
            userPlaylistsLoading = true;
            userPlaylistsLastAttempt = now;
        }
        Thread thread = new Thread(() -> {
            try {
                refreshUserPlaylists();
            } catch (Throwable throwable) {
                System.err.println("[Music/Apple] 本人歌单加载失败: " + throwable.getMessage());
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
        }, "Apple Account");
        thread.setDaemon(true);
        thread.start();
    }

    /** 强制重拉账号歌单（侧栏刷新按钮用）。结果在后台线程落快照。助手优先。 */
    public static void refreshUserPlaylists() {
        List<PlayList> cards = new ArrayList<>();
        // 资料库歌曲虚拟单排最前（与 Spotify「喜欢的歌」同位）；没有账号态时整个流程不进门。
        Playlist librarySongs = new Playlist();
        librarySongs.id = LIBRARY_SONGS_PLAYLIST_ID;
        librarySongs.name = "资料库歌曲";
        librarySongs.owner = "我";
        cards.add(toPlayListCard(librarySongs));

        int offset = 0;
        while (offset < 500) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", "100");
            query.put("offset", String.valueOf(offset));
            JsonObject payload;
            try {
                payload = AppleApiClient.getAccount(catalogPath("/v1/me/library/playlists", query));
            } catch (IOException | AppleApiClient.AppleApiException throwable) {
                System.err.println("[Music/Apple] Java 本人歌单失败: " + throwable.getMessage());
                break;
            }
            List<Playlist> page = readResourcePlaylists(payload);
            if (page.isEmpty()) break;
            for (Playlist playlist : page) {
                PlayList card = toPlayListCard(playlist);
                if (card != null) cards.add(card);
            }
            offset += page.size();
        }
        userPlaylistsSnapshot = cards;
        userPlaylistsLoaded = true;
        AppleApiClient.markAccountValid();
    }

    private static void clearPlaylistCache() {
        userPlaylistsSnapshot = Collections.emptyList();
        userPlaylistsLoaded = false;
    }

    public static boolean isLoggedIn() {
        return AppleCookieSupport.isLoggedIn() && !AppleApiClient.isAccountInvalid();
    }

    public static String getAccountName() {
        if (AppleCookieSupport.cookiesFileOrNull() == null) return "未登录";
        if (AppleApiClient.isAccountInvalid()) return "cookies 已失效，请重新导入";
        return "Apple Music 账号";
    }

    public static String getAvatarUrl() {
        return ""; // Apple 不提供账号头像；UI 走字母头像兜底。
    }

    /** 登录状态一句话描述；登录页与账号卡共用。 */
    public static String describeStatus() {
        if (AppleCookieSupport.cookiesFileOrNull() == null) {
            return "未登录：粘贴 music.apple.com 的 cookies.txt 后，可浏览本人资料库歌单与逐词歌词";
        }
        if (AppleApiClient.isAccountInvalid()) return "cookies 已失效，请重新导出并导入";
        return "已导入 cookies · 完整版本机解密播放 → 跨平台换源 → 30 秒试听兜底";
    }

    /** 退出登录：清 cookies 与账号歌单快照（偏好与 storefront 保留）。 */
    public static void logout() {
        AppleCookieSupport.clear();
        clearPlaylistCache();
    }

    /** cookies 是否已导入（配置层面，区分于 isLoggedIn 的有效性判断）。 */
    public static boolean isConfigured() {
        return AppleCookieSupport.cookiesFileOrNull() != null;
    }

    // ── 内部：端点与解析 ─────────────────────────────────────────────────────

    /** catalog 路径拼 query；路径含 {@code {sf}} 时已在调用处替换好。 */
    private static String catalogPath(String path, Map<String, String> query) {
        StringBuilder out = new StringBuilder(path);
        if (query != null && !query.isEmpty()) {
            boolean first = !path.contains("?");
            for (Map.Entry<String, String> entry : query.entrySet()) {
                out.append(first ? '?' : '&');
                first = false;
                out.append(urlEncode(entry.getKey())).append('=')
                        .append(urlEncode(entry.getValue() == null ? "" : entry.getValue()));
            }
        }
        return out.toString();
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            return "";
        }
    }

    /** catalog 公开歌单曲目：单页 300 内直接拿，超出跟随 next 翻页。 */
    private static List<Track> catalogPlaylistTracks(String playlistId, int max) throws IOException {
        List<Track> out = new ArrayList<>();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("extend", "extendedAssetUrls");
        JsonObject payload = AppleApiClient.getCatalog(catalogPath(
                "/catalog/" + AppleConfigStore.storefront() + "/playlists/" + playlistId, query));
        JsonArray data = payload.getAsJsonArray("data");
        if (data == null || data.size() == 0) return out;
        JsonObject relationships = data.get(0).getAsJsonObject().getAsJsonObject("relationships");
        if (relationships == null) return out;
        readTracksInto(out, relationships.getAsJsonArray("tracks"));
        String next = text(relationships, "next");
        while (!next.isEmpty() && out.size() < max) {
            JsonObject page = AppleApiClient.getCatalog(next.startsWith("/") ? next : "/" + next);
            JsonArray pageData = page.getAsJsonArray("data");
            if (pageData == null || pageData.size() == 0) break;
            int before = out.size();
            readTracksInto(out, pageData);
            next = text(page, "next");
            if (out.size() == before) break;
        }
        return out;
    }

    /** 资料库歌单曲目。library 曲目优先用 playParams.catalogId（catalog id 形态更稳）。 */
    private static List<Track> libraryPlaylistTracks(String playlistId, int max) throws IOException {
        List<Track> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < max) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", String.valueOf(Math.min(100, max - out.size())));
            query.put("offset", String.valueOf(offset));
            JsonObject payload = AppleApiClient.getAccount(catalogPath(
                    "/v1/me/library/playlists/" + playlistId + "/tracks", query));
            JsonArray data = payload.getAsJsonArray("data");
            if (data == null || data.size() == 0) break;
            int before = out.size();
            readTracksInto(out, data);
            offset += out.size() - before;
            if (out.size() == before) break;
            if (offset >= 2000) break; // 安全阀：JSON 分页异常时别无限拉。
        }
        return out;
    }

    /** 资料库歌曲虚拟单。 */
    private static List<Track> librarySongs(int max) throws IOException {
        List<Track> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < max) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", String.valueOf(Math.min(100, max - out.size())));
            query.put("offset", String.valueOf(offset));
            JsonObject payload = AppleApiClient.getAccount(catalogPath("/v1/me/library/songs", query));
            JsonArray data = payload.getAsJsonArray("data");
            if (data == null || data.size() == 0) break;
            int before = out.size();
            readTracksInto(out, data);
            offset += out.size() - before;
            if (out.size() == before) break;
            if (offset >= 2000) break;
        }
        return out;
    }

    /** 专辑曲目端点不含 albumName，封面与专辑名从专辑本体补。 */
    private static List<Track> albumTracks(String albumId, int max) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("extend", "extendedAssetUrls");
        JsonObject payload = AppleApiClient.getCatalog(catalogPath(
                "/catalog/" + AppleConfigStore.storefront() + "/albums/" + albumId, query));
        JsonArray data = payload.getAsJsonArray("data");
        if (data == null || data.size() == 0) return new ArrayList<>();
        JsonObject album = data.get(0).getAsJsonObject();
        String albumName = text(album.getAsJsonObject("attributes"), "name");
        String cover = artworkUrl(album.getAsJsonObject("attributes"));
        JsonObject relationships = album.getAsJsonObject("relationships");
        List<Track> out = new ArrayList<>();
        if (relationships == null) return out;
        int before = out.size();
        readTracksInto(out, relationships.getAsJsonArray("tracks"));
        for (int index = before; index < out.size(); index++) {
            Track track = out.get(index);
            if (track.album.isEmpty()) track.album = albumName;
            if (track.coverUrl.isEmpty()) track.coverUrl = cover;
            if (track.albumId.isEmpty()) track.albumId = albumId;
        }
        return out;
    }

    /** {@code /search} 的响应：{@code results.songs.data} / {@code results.playlists.data}。 */
    private static List<Track> readSearchTracks(JsonObject payload, String member) {
        JsonArray data = searchSectionData(payload, member);
        List<Track> out = new ArrayList<>();
        if (data == null) return out;
        for (JsonElement element : data) {
            Track parsed = readTrack(element == null ? null : element.getAsJsonObject());
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    private static List<Playlist> readSearchPlaylists(JsonObject payload, String member) {
        JsonArray data = searchSectionData(payload, member);
        List<Playlist> out = new ArrayList<>();
        if (data == null) return out;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            Playlist card = readPlaylistCard(element.getAsJsonObject(), "Apple Music 歌单");
            if (card != null) out.add(card);
        }
        return out;
    }

    private static JsonArray searchSectionData(JsonObject payload, String member) {
        if (payload == null || !payload.has("results")) return null;
        JsonObject results = payload.getAsJsonObject("results");
        if (!results.has(member)) return null;
        JsonObject section = results.getAsJsonObject(member);
        return section == null ? null : section.getAsJsonArray("data");
    }

    /** {@code /v1/me/library/playlists} 的响应：顶层 {@code data[]}。 */
    private static List<Playlist> readResourcePlaylists(JsonObject payload) {
        List<Playlist> out = new ArrayList<>();
        JsonArray data = payload == null ? null : payload.getAsJsonArray("data");
        if (data == null) return out;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            Playlist card = readPlaylistCard(element.getAsJsonObject(), "我的 Apple 歌单");
            if (card != null) out.add(card);
        }
        return out;
    }

    /** 单条曲目资源 → 模型。library 行优先取 catalog id；无 id 的行丢弃。 */
    private static Track readTrack(JsonObject resource) {
        if (resource == null) return null;
        Track out = new Track();
        out.id = catalogIdOf(resource);
        if (out.id.isEmpty()) return null;
        JsonObject attributes = resource.getAsJsonObject("attributes");
        if (attributes == null) return null;
        out.name = text(attributes, "name");
        if (out.name.isEmpty()) return null;
        out.artist = text(attributes, "artistName");
        out.album = text(attributes, "albumName");
        out.albumId = text(attributes, "albumId");
        out.durationMillis = attributes.has("durationInMillis")
                && attributes.get("durationInMillis").isJsonPrimitive()
                ? attributes.get("durationInMillis").getAsLong() : 0L;
        out.coverUrl = artworkUrl(attributes);
        out.previewUrl = previewOf(resource);
        return out;
    }

    /**
     * 曲目主键。catalog 行直接是数字 id；library 行是库内 id，能匹配到 catalog 时
     * {@code playParams.catalogId} 会带上真正的 catalog id——换源与 preview 都认它。
     */
    private static String catalogIdOf(JsonObject resource) {
        String id = text(resource, "id");
        if (id.startsWith("i.")) {
            JsonObject attributes = resource.getAsJsonObject("attributes");
            JsonObject playParams = attributes == null ? null
                    : attributes.getAsJsonObject("playParams");
            String catalogId = playParams == null ? "" : text(playParams, "catalogId");
            return catalogId.isEmpty() ? "" : catalogId;
        }
        return id;
    }

    private static String previewOf(JsonObject resource) {
        JsonObject attributes = resource == null ? null : resource.getAsJsonObject("attributes");
        if (attributes == null) return "";
        JsonArray previews = attributes.getAsJsonArray("previews");
        if (previews != null && previews.size() > 0 && previews.get(0).isJsonObject()) {
            String url = text(previews.get(0).getAsJsonObject(), "url");
            if (!url.isEmpty()) return url;
        }
        JsonObject extended = attributes.getAsJsonObject("extendedAssetUrls");
        return extended == null ? "" : text(extended, "previewUrl");
    }

    private static Playlist readPlaylistCard(JsonObject resource, String ownerFallback) {
        if (resource == null) return null;
        String id = text(resource, "id");
        if (id.isEmpty()) return null;
        JsonObject attributes = resource.getAsJsonObject("attributes");
        Playlist card = new Playlist();
        card.id = id;
        card.name = attributes == null ? "" : text(attributes, "name");
        if (card.name.isEmpty()) return null;
        card.owner = attributes == null ? "" : firstNonEmpty(text(attributes, "curatorName"),
                text(attributes, "artistName"), ownerFallback);
        card.coverUrl = artworkUrl(attributes);
        card.trackCount = attributes != null && attributes.has("trackCount")
                && attributes.get("trackCount").isJsonPrimitive()
                ? attributes.get("trackCount").getAsInt() : 0;
        return card;
    }

    private static void readTracksInto(List<Track> out, JsonArray data) {
        if (data == null) return;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            Track parsed = readTrack(element.getAsJsonObject());
            if (parsed != null) out.add(parsed);
        }
    }

    /** 封面模板地址换成 600×600；模板缺失返回空串。 */
    private static String artworkUrl(JsonObject attributes) {
        if (attributes == null) return "";
        JsonObject artwork = attributes.getAsJsonObject("artwork");
        String template = artwork == null ? "" : text(artwork, "url");
        if (template.isEmpty()) return "";
        return ARTWORK_SIZE.matcher(template).replaceAll("600x600");
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
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
     * 失败记账。账号失效（reauthRequired）是「预期内」的状态（UI 有引导位），其余如实进日志 ——
     * 搜索静默失败只会表现为「Apple 什么都搜不到」，没有日志就是无头案。
     */
    private static void logFailure(String what, Throwable throwable) {
        System.err.println("[Music/Apple] " + what + "失败: " + throwable.getMessage());
    }

    // ── PlayList 卡片适配 ────────────────────────────────────────────────────

    /** 把一张 Apple 歌单 / 专辑映射成播放器的歌单卡片；曲目点开时再拉。 */
    public static PlayList toPlayListCard(Playlist playlist) {
        if (playlist == null || playlist.id == null || playlist.id.trim().isEmpty()) return null;
        String platformId = playlist.id.trim();
        PlayList card = new PlayList(stableId(platformId), nonBlank(playlist.name, "Apple 歌单"),
                playlist.coverUrl, Math.max(0, playlist.trackCount), 0L, null, "", 0L);
        card.setPlatform(MusicPlatform.APPLE);
        card.setPlatformPlaylistId(platformId);
        return card;
    }

    /** 由平台主键推稳定 long id（FNV-1a，与 {@code SpotifyMusicService#stableId} 同式）。 */
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
