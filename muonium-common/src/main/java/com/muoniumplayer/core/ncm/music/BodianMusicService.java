package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.music.source.QrCode;
import com.muoniumplayer.core.music.source.QrLoginState;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.Quality;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 波点音乐公开客户端 API 适配。所有凭据均由 {@link BodianSessionStore} 单独保存。 */
public final class BodianMusicService {

    private static final String API = "https://bd-api.kuwo.cn/api/";
    private static final String LYRIC = "https://mlyric.kuwo.cn/mobi.s";
    private static final String USER_AGENT = "Dart/3.3 (dart:io)";
    private static final int MAX_PLAYLIST_TRACKS = 1000;
    private static final long USER_PLAYLIST_RETRY_MS = 15_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile List<PlayList> userPlaylists = Collections.emptyList();
    private static volatile boolean userPlaylistsLoaded;
    private static volatile boolean userPlaylistsLoading;
    private static volatile long userPlaylistsLastAttempt;
    private static volatile long userPlaylistsGeneration;
    private static volatile Set<String> likedTrackIds = Collections.emptySet();
    private static volatile boolean likedTrackIdsLoaded;
    private static volatile String likedTrackIdsOwner = "";
    private static volatile long likedTrackIdsGeneration;
    private static volatile boolean likedTrackIdsLoading;
    private static volatile long likedTrackIdsLoadToken;

    private BodianMusicService() {
    }

    public static final class Track {
        public final String id;
        public final String title;
        public final String artist;
        public final String album;
        public final String coverUrl;
        public final long durationMillis;
        public final boolean vip;

        Track(String id, String title, String artist, String album, String coverUrl,
              long durationMillis, boolean vip) {
            this.id = clean(id);
            this.title = clean(title);
            this.artist = clean(artist);
            this.album = clean(album);
            this.coverUrl = clean(coverUrl);
            this.durationMillis = Math.max(0L, durationMillis);
            this.vip = vip;
        }
    }

    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final int bitrate;
        public final long size;

        ResolveResult(String url, String format, int bitrate, long size) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
            this.size = size;
        }
    }

    private static final class PlaylistRef {
        final String id;
        final int source;
        final boolean virtual;
        PlaylistRef(String id, int source, boolean virtual) {
            this.id = id;
            this.source = source;
            this.virtual = virtual;
        }
    }

    private static final class ApiException extends RuntimeException {
        final int code;
        ApiException(int code, String message) { super(message); this.code = code; }
    }

    public static boolean isLoggedIn() {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        return session != null && session.isValid();
    }

    public static String getAccountName() {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        return session == null ? "未登录" : (session.nickname.isEmpty() ? "波点音乐账号" : session.nickname);
    }

    public static String getAccountId() {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        return session == null ? "" : session.uid;
    }

    public static String getAvatarUrl() {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        return session == null ? "" : session.avatarUrl;
    }

    public static boolean isVipAccount() {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        return session != null && session.vip;
    }

    public static void logout() {
        BodianSessionStore.clearSession();
        clearAccountCaches();
    }

    public static QrCode createQrCode() {
        JsonObject data = request("ucenter/login/qrCode", null, null, "GET", null, false);
        String key = string(data, "qrCode");
        if (key.isEmpty()) throw new IllegalStateException("波点音乐未返回登录二维码");
        String content = "https://bodian-oia.kuwo.cn/bodian/download.html?pageName=login_pc&pt=3&id=" + encode(key);
        return new QrCode(key, content);
    }

    public static QrLoginState checkQrCode(QrCode qrCode) {
        if (qrCode == null || blank(qrCode.getKey())) return QrLoginState.ERROR;
        try {
            JsonObject status = request("ucenter/login/qrCodeStatus", mapOf("qrCode", qrCode.getKey()),
                    null, "GET", null, false);
            QrLoginState state = qrState(integer(status, "status", -1));
            if (state != QrLoginState.CONFIRMED) return state;
            JsonObject body = new JsonObject();
            body.addProperty("authType", 10);
            body.addProperty("qrCode", qrCode.getKey());
            JsonObject login = request("ucenter/users/login", null, body, "POST", null, false);
            JsonObject user = object(login, "userInfo");
            JsonObject pay = object(login, "payInfo");
            String uid = first(string(login, "id"), string(login, "bid"), string(user, "id"));
            String token = string(login, "token");
            if (uid.isEmpty() || token.isEmpty()) return QrLoginState.ERROR;
            boolean vip = bool(pay, "isVip") || integer(pay, "vip", 0) > 0 || integer(pay, "paytype", 0) > 0;
            BodianSessionStore.setSession(new BodianSessionStore.Session(uid, token,
                    first(string(user, "nickName"), string(user, "nickname"), string(user, "name")),
                    first(string(user, "headImg"), string(user, "avatar"), string(user, "pic")), vip));
            clearAccountCaches();
            return QrLoginState.CONFIRMED;
        } catch (ApiException failure) {
            return qrFailureState(failure.code);
        } catch (Throwable failure) {
            System.err.println("[Music/Bodian] QR login check failed: " + failure.getMessage());
            return QrLoginState.ERROR;
        }
    }

    static QrLoginState qrState(int status) {
        switch (status) {
            case 0:
            case 1: return QrLoginState.WAITING;
            case 2: return QrLoginState.EXPIRED;
            case 3: return QrLoginState.CONFIRMED;
            default: return QrLoginState.ERROR;
        }
    }

    static QrLoginState qrFailureState(int code) {
        if (code == 11027) return QrLoginState.SCANNED;
        return code == -101 || code == 11012 ? QrLoginState.ERROR : QrLoginState.WAITING;
    }

    public static boolean switchAccount(BodianSessionStore.Account account) {
        if (account == null) return false;
        try {
            BodianSessionStore.Session candidate = account.toSession();
            request("service/playlist/userCreate", mapOf("userId", candidate.uid), null,
                    "GET", candidate, true);
            BodianSessionStore.activate(account);
            clearAccountCaches();
            return true;
        } catch (Throwable failure) {
            System.err.println("[Music/Bodian] Saved account validation failed: " + failure.getMessage());
            return false;
        }
    }

    public static List<BodianSessionStore.Account> getSavedAccounts() { return BodianSessionStore.accounts(); }
    public static boolean removeSavedAccount(BodianSessionStore.Account account) { return BodianSessionStore.remove(account); }

    public static List<Music> search(String keyword, int limit) {
        if (blank(keyword)) return Collections.emptyList();
        JsonObject data = request("search/music/list", mapOf("keyword", keyword.trim(), "pn", "0",
                "rn", String.valueOf(Math.max(1, Math.min(100, limit))), "correct", "1"), null,
                "GET", null, true);
        return tracksFrom(array(data, "resultList"));
    }

    public static List<PlayList> searchPlaylists(String keyword, int limit) {
        if (blank(keyword)) return Collections.emptyList();
        JsonObject data = request("search/playlist/list", mapOf("keyword", keyword.trim(), "pn", "1",
                "rn", String.valueOf(Math.max(1, Math.min(100, limit)))), null, "GET", null, true);
        JsonArray rows = array(data, "playLists");
        if (rows == null) rows = array(data, "resultList");
        return playlistsFrom(rows, 4);
    }

    /** 推荐歌单墙；登录后同一首页还会返回个性化歌单模块。 */
    public static List<PlayList> recommendPlaylists(int limit) {
        int maximum = Math.max(1, Math.min(100, limit));
        LinkedHashMap<String, PlayList> results = new LinkedHashMap<>();
        try {
            JsonObject data = request("service/home/module", mapOf("moduleId", "2"), null,
                    "GET", null, true);
            appendPlaylists(results, array(data, "songList"), 4);
            appendPlaylists(results, array(data, "playLists"), 4);
        } catch (Throwable failure) {
            System.err.println("[Music/Bodian] Public recommendations unavailable: " + failure.getMessage());
        }
        if (isLoggedIn()) {
            try {
                JsonObject home = request("service/home/module", mapOf("moduleId", "1"), null,
                        "GET", null, true);
                for (PlayList playlist : personalizedPlaylists(home)) {
                    results.putIfAbsent(playlist.getPlatformPlaylistId(), playlist);
                }
            } catch (Throwable failure) {
                System.err.println("[Music/Bodian] Personalized recommendations unavailable: " + failure.getMessage());
            }
        }
        List<PlayList> output = new ArrayList<>(results.values());
        return output.size() <= maximum ? output : new ArrayList<>(output.subList(0, maximum));
    }

    public static List<Music> personalizedTracks(int limit) {
        JsonObject data = request("service/music/recommendList", mapOf("pn", "1", "rn",
                String.valueOf(Math.max(1, Math.min(100, limit))), "select_card", "1"), null,
                "GET", null, true);
        return tracksFrom(array(data, "musicList"));
    }

    private static Map<String, String> mapOf(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    public static List<PlayList> getUserPlaylists() {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        return getUserPlaylists(session);
    }

    private static List<PlayList> getUserPlaylists(BodianSessionStore.Session session) {
        if (session == null || !session.isValid()) return Collections.emptyList();
        LinkedHashMap<String, PlayList> merged = new LinkedHashMap<>();
        JsonObject own = request("service/playlist/userCreate", mapOf("userId", session.uid), null,
                "GET", session, true);
        appendPlaylists(merged, array(own, "playLists"), 5);
        for (int page = 1; page <= 10; page++) {
            JsonObject collected = request("service/collect/4/list", mapOf("userId", session.uid,
                    "fromUid", session.uid, "pn", String.valueOf(page), "rn", "100"), null,
                    "GET", session, true);
            JsonArray rows = array(collected, "playLists");
            if (rows == null || rows.isEmpty()) break;
            for (PlayList playlist : playlistsFrom(rows, 4)) {
                playlist.setSubscribed(true);
                merged.putIfAbsent(playlist.getPlatformPlaylistId(), playlist);
            }
            if (rows.size() < 100) break;
        }
        JsonObject liked = request("service/playlist/fond", mapOf("userId", session.uid), null,
                "GET", session, true);
        String likedId = string(liked, "id");
        if (!likedId.isEmpty()) {
            PlayList favorites = createVirtualPlaylist("liked-" + likedId, "我喜欢的歌曲",
                    (int) longValue(liked, "musicCount", 0L), "波点音乐收藏歌曲");
            merged.putIfAbsent(favorites.getPlatformPlaylistId(), favorites);
        }
        return new ArrayList<>(merged.values());
    }

    public static List<PlayList> getUserPlaylistsSnapshot() {
        return new ArrayList<>(userPlaylists);
    }

    public static boolean areUserPlaylistsLoaded() {
        return userPlaylistsLoaded;
    }

    public static boolean areUserPlaylistsLoading() {
        return userPlaylistsLoading;
    }

    public static void ensureUserPlaylistsAsync(Runnable callback) {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        if (session == null || !session.isValid()) {
            clearUserPlaylists();
            if (callback != null) callback.run();
            return;
        }
        final long generation;
        synchronized (BodianMusicService.class) {
            long now = System.currentTimeMillis();
            if (userPlaylistsLoading) return;
            if (userPlaylistsLoaded) {
                if (callback != null) callback.run();
                return;
            }
            if (now - userPlaylistsLastAttempt < USER_PLAYLIST_RETRY_MS) return;
            userPlaylistsLoading = true;
            userPlaylistsLastAttempt = now;
            generation = userPlaylistsGeneration;
        }
        Thread worker = new Thread(() -> {
            boolean current = false;
            try {
                List<PlayList> loaded = getUserPlaylists(session);
                current = isCurrentPlaylistRequest(session, generation);
                if (current) {
                    userPlaylists = Collections.unmodifiableList(new ArrayList<>(loaded));
                    userPlaylistsLoaded = true;
                }
            } catch (Throwable failure) {
                System.err.println("[Music/Bodian] Account playlists failed: " + failure.getMessage());
            } finally {
                if (isCurrentPlaylistRequest(session, generation)) {
                    userPlaylistsLoading = false;
                }
                if (callback != null && (current || isCurrentPlaylistRequest(session, generation))) {
                    try { callback.run(); } catch (Throwable ignored) { }
                }
            }
        }, "Bodian Account");
        worker.setDaemon(true);
        worker.start();
    }

    public static void forceRefreshUserPlaylistsAsync(Consumer<PlaylistRefreshResult> callback) {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        if (session == null || !session.isValid()) {
            clearUserPlaylists();
            if (callback != null) callback.accept(PlaylistRefreshResult.failure(0L, "请先登录波点音乐"));
            return;
        }
        final long generation;
        synchronized (BodianMusicService.class) {
            if (userPlaylistsLoading) return;
            userPlaylistsLoading = true;
            userPlaylistsLastAttempt = System.currentTimeMillis();
            generation = userPlaylistsGeneration;
        }
        Thread worker = new Thread(() -> {
            long started = System.nanoTime();
            try {
                List<PlayList> loaded = getUserPlaylists(session);
                if (!isCurrentPlaylistRequest(session, generation)) return;
                userPlaylists = Collections.unmodifiableList(new ArrayList<>(loaded));
                userPlaylistsLoaded = true;
                if (callback != null) callback.accept(PlaylistRefreshResult.success(
                        (System.nanoTime() - started) / 1_000_000L, loaded.size()));
            } catch (Throwable failure) {
                if (callback != null && isCurrentPlaylistRequest(session, generation)) callback.accept(PlaylistRefreshResult.failure(
                        (System.nanoTime() - started) / 1_000_000L,
                        "波点歌单刷新失败：" + (failure.getMessage() == null ? "请求失败" : failure.getMessage())));
            } finally {
                if (isCurrentPlaylistRequest(session, generation)) userPlaylistsLoading = false;
            }
        }, "Bodian Account Refresh");
        worker.setDaemon(true);
        worker.start();
    }

    public static List<Music> playlistTracks(String playlistId, int limit) {
        String id = clean(playlistId);
        if (id.startsWith("home:")) return personalizedPlaylistTracks(id.substring("home:".length()), limit);
        PlaylistRef ref = parsePlaylistRef(playlistId);
        int maximum = Math.max(1, Math.min(MAX_PLAYLIST_TRACKS, limit));
        List<Music> result = new ArrayList<>();
        for (int page = 1; result.size() < maximum; page++) {
            JsonObject data = request("service/playlist/" + encode(ref.id) + "/musicList",
                    mapOf("pn", String.valueOf(page), "rn", String.valueOf(Math.min(100, maximum - result.size())),
                            "source", String.valueOf(ref.source)), null, "GET", null, true);
            JsonArray rows = array(data, "list");
            if (rows == null || rows.isEmpty()) break;
            int before = result.size();
            result.addAll(tracksFrom(rows));
            if (rows.size() < 100 || result.size() == before) break;
        }
        return result.size() <= maximum ? result : new ArrayList<>(result.subList(0, maximum));
    }

    private static List<Music> personalizedPlaylistTracks(String index, int limit) {
        if (!index.matches("0|[1-9][0-9]{0,5}")) {
            throw new IllegalArgumentException("无效的波点个性化歌单索引");
        }
        int maximum = Math.max(1, Math.min(MAX_PLAYLIST_TRACKS, limit));
        JsonObject data = request("service/home/aiPlaylistDetail",
                mapOf("index", index, "passRecName", ""), null, "GET", null, true);
        List<Music> tracks = tracksFrom(array(data, "musicList"));
        return tracks.size() <= maximum ? tracks : new ArrayList<>(tracks.subList(0, maximum));
    }

    public static PlayList createPlaylist(String name) {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        if (blank(name) || session == null || !session.isValid()) return null;
        JsonObject body = new JsonObject();
        body.addProperty("name", name.trim());
        body.addProperty("private", false);
        JsonObject data = request("service/playlist", null, body, "POST", session, true);
        PlayList result = parseCreatedPlaylist(data, name.trim());
        if (result != null && sameSession(BodianSessionStore.snapshot(), session)) clearUserPlaylists();
        return result;
    }

    public static boolean renamePlaylist(String playlistId, String name) {
        PlaylistRef ref = parsePlaylistRef(playlistId);
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        requireOwnedPlaylist(ref, session);
        if (blank(name)) return false;
        JsonObject info = request("service/playlist/info/" + encode(ref.id), mapOf("source", "5"),
                null, "GET", session, true);
        if (!sameSession(BodianSessionStore.snapshot(), session)) return false;
        JsonObject body = new JsonObject();
        body.addProperty("id", parsePositiveLong(ref.id, "波点歌单 ID"));
        body.addProperty("name", name.trim());
        body.addProperty("description", string(info, "description"));
        body.addProperty("pic", string(info, "pic"));
        JsonArray categories = array(info, "categoryList");
        body.add("categoryList", categories == null ? new JsonArray() : categories.deepCopy());
        request("service/playlist", null, body, "PUT", session, true);
        if (sameSession(BodianSessionStore.snapshot(), session)) clearUserPlaylists();
        return true;
    }

    public static boolean deletePlaylist(String playlistId) {
        PlaylistRef ref = parsePlaylistRef(playlistId);
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        requireOwnedPlaylist(ref, session);
        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(parsePositiveLong(ref.id, "波点歌单 ID"));
        body.add("playlistIds", ids);
        request("service/playlist", null, body, "DELETE", session, true);
        if (sameSession(BodianSessionStore.snapshot(), session)) clearUserPlaylists();
        return true;
    }

    public static boolean addTrackToPlaylist(String playlistId, String trackId) {
        return playlistTrackWrite("service/playlist/music", playlistId, trackId);
    }

    public static boolean removeTrackFromPlaylist(String playlistId, String trackId) {
        return playlistTrackWrite("service/playlist/music/delete", playlistId, trackId);
    }

    public static boolean setPlaylistCollected(String playlistId, boolean collected) {
        PlaylistRef ref = parsePlaylistRef(playlistId);
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        requireCollectedPlaylist(ref, session);
        JsonObject body = new JsonObject();
        body.addProperty("source", 4);
        JsonArray sourceIds = new JsonArray();
        sourceIds.add(parsePositiveLong(ref.id, "波点歌单 ID"));
        body.add("sourceId", sourceIds);
        body.addProperty("op", collected ? 1 : 2);
        body.addProperty("uid", parsePositiveLong(session.uid, "波点账号 ID"));
        body.addProperty("token", session.token);
        request("service/collect", null, body, "POST", session, true);
        return true;
    }

    private static boolean playlistTrackWrite(String endpoint, String playlistId, String trackId) {
        return playlistTrackWrite(endpoint, playlistId, trackId, null);
    }

    private static boolean playlistTrackWrite(String endpoint, String playlistId, String trackId,
                                              BodianSessionStore.Session session) {
        PlaylistRef ref = parsePlaylistRef(playlistId);
        if (session == null) session = BodianSessionStore.snapshot();
        requireOwnedPlaylist(ref, session);
        long playlistNumericId = parsePositiveLong(ref.id, "波点歌单 ID");
        long trackNumericId = parsePositiveLong(trackId, "波点歌曲 ID");
        JsonObject body = new JsonObject();
        body.addProperty("playListId", playlistNumericId);
        JsonArray ids = new JsonArray();
        ids.add(trackNumericId);
        body.add("musicIdList", ids);
        request(endpoint, null, body, "POST", session, true);
        if (sameSession(BodianSessionStore.snapshot(), session)) clearUserPlaylists();
        return true;
    }

    public static boolean setTrackLiked(String trackId, boolean liked) {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        if (session == null || !session.isValid() || blank(trackId)) return false;
        parsePositiveLong(trackId, "波点歌曲 ID");
        JsonObject data = request("service/playlist/fond", mapOf("userId", session.uid), null,
                "GET", session, true);
        String playlistId = string(data, "id");
        if (playlistId.isEmpty()) return false;
        if (!playlistTrackWrite(liked ? "service/playlist/music" : "service/playlist/music/delete",
                "pl-5-" + playlistId, trackId, session)) return false;
        noteTrackLikedLocal(session, trackId, liked);
        return true;
    }

    public static void ensureLikedTrackIdsLoaded() {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        if (session == null || !session.isValid()) return;
        final long generation;
        synchronized (BodianMusicService.class) {
            if (!likedTrackIdsOwner.equals(session.uid)) {
                likedTrackIds = Collections.emptySet();
                likedTrackIdsLoaded = false;
                likedTrackIdsOwner = session.uid;
                likedTrackIdsGeneration++;
                likedTrackIdsLoadToken++;
                likedTrackIdsLoading = false;
            }
            if (likedTrackIdsLoaded || likedTrackIdsLoading) return;
            generation = likedTrackIdsGeneration;
            likedTrackIdsLoading = true;
            final long loadToken = ++likedTrackIdsLoadToken;
            Thread worker = new Thread(() -> loadLikedTrackIds(session, generation, loadToken),
                    "Bodian Liked Songs");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void loadLikedTrackIds(BodianSessionStore.Session session, long generation, long loadToken) {
        try {
            JsonObject data = request("service/playlist/fond", mapOf("userId", session.uid), null,
                    "GET", session, true);
            String playlistId = string(data, "id");
            Set<String> ids = new LinkedHashSet<>();
            if (!playlistId.isEmpty()) {
                for (int page = 1; page <= 30; page++) {
                    JsonObject pageData = request("service/playlist/" + encode(playlistId) + "/musicList",
                            mapOf("pn", String.valueOf(page), "rn", "100", "source", "5"), null,
                            "GET", session, true);
                    JsonArray rows = array(pageData, "list");
                    if (rows == null || rows.isEmpty()) break;
                    for (JsonElement row : rows) {
                        if (row != null && row.isJsonObject()) {
                            String id = first(string(row.getAsJsonObject(), "id"),
                                    string(row.getAsJsonObject(), "rid"));
                            if (!id.isEmpty()) ids.add(id);
                        }
                    }
                    if (rows.size() < 100) break;
                }
            }
            synchronized (BodianMusicService.class) {
                if (likedTrackIdsGeneration == generation
                        && sameSession(BodianSessionStore.snapshot(), session)) {
                    likedTrackIds = Collections.unmodifiableSet(ids);
                    likedTrackIdsOwner = session.uid;
                    likedTrackIdsLoaded = true;
                }
            }
        } catch (Throwable failure) {
            System.err.println("[Music/Bodian] Liked-song index failed: " + failure.getMessage());
        } finally {
            synchronized (BodianMusicService.class) {
                if (likedTrackIdsLoadToken == loadToken) likedTrackIdsLoading = false;
            }
        }
    }

    public static boolean isTrackLikedLocal(String trackId) {
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        return session != null && session.uid.equals(likedTrackIdsOwner)
                && trackId != null && likedTrackIds.contains(trackId);
    }

    public static void noteTrackLikedLocal(String trackId, boolean liked) {
        if (blank(trackId)) return;
        BodianSessionStore.Session session = BodianSessionStore.snapshot();
        if (session == null || !session.isValid()) return;
        noteTrackLikedLocal(session, trackId, liked);
    }

    private static void noteTrackLikedLocal(BodianSessionStore.Session session, String trackId, boolean liked) {
        if (blank(trackId) || session == null || !session.isValid()) return;
        synchronized (BodianMusicService.class) {
            if (!sameSession(BodianSessionStore.snapshot(), session)) return;
            Set<String> updated = new LinkedHashSet<>(
                    session.uid.equals(likedTrackIdsOwner) ? likedTrackIds : Collections.emptySet());
            if (liked) updated.add(trackId); else updated.remove(trackId);
            likedTrackIds = Collections.unmodifiableSet(updated);
            likedTrackIdsOwner = session.uid;
            likedTrackIdsGeneration++;
        }
    }

    public static String getLyricText(String trackId) {
        if (blank(trackId)) return "";
        String plain = "type=lyric&req=2&lrcx=0&rid=" + trackId.trim()
                + "&songname=&artist=&corp=kuwo&fromchannel=bodian";
        String query = "f=bodian&q=" + encode(java.util.Base64.getEncoder()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(LYRIC + "?" + query))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return "";
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = object(root, "data");
            String content = data == null ? "" : string(data, "content");
            if (content.isEmpty()) return "";
            return new String(java.util.Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            System.err.println("[Music/Bodian] Lyric request failed: " + failure.getMessage());
            return "";
        }
    }

    public static ResolveResult getSongUrl(String trackId, Quality quality) {
        if (blank(trackId)) return null;
        int[] bitrates = bitrateLadder(quality);
        for (int bitrate : bitrates) {
            try {
                JsonObject data = request("play/music/v2/audioUrl", mapOf("devId", BodianSessionStore.deviceId(),
                        "musicId", trackId.trim(), "br", String.valueOf(bitrate), "freeSign", ""),
                        null, "GET", null, true);
                String format = string(data, "format").toLowerCase(java.util.Locale.ROOT);
                String url = first(string(data, "audioHttpsUrl"), string(data, "audioUrl"));
                if (!isHttpUrl(url) || !("mp3".equals(format) || "flac".equals(format))) continue;
                int actual = integer(data, "bitrate", bitrate);
                return new ResolveResult(url, format, actual, parseSize(string(data, "size")));
            } catch (ApiException unavailable) {
                if (unavailable.code == 20012 || unavailable.code == 20018 || unavailable.code == 20019) continue;
                if (unavailable.code == -101 || unavailable.code == 11012) return null;
                System.err.println("[Music/Bodian] Audio URL request failed: " + unavailable.getMessage());
                return null;
            } catch (Throwable failure) {
                System.err.println("[Music/Bodian] Audio URL request failed: " + failure.getMessage());
                return null;
            }
        }
        return null;
    }

    static int[] bitrateLadder(Quality quality) {
        if (quality == Quality.STANDARD) return new int[]{128};
        if (quality == Quality.HIGHER || quality == Quality.EXHIGH) return new int[]{20000, 320, 128};
        return new int[]{2000, 20000, 128};
    }

    static Track parseTrack(JsonObject wire) {
        if (wire == null) return null;
        String id = first(string(wire, "id"), string(wire, "rid"), string(wire, "musicRid"));
        if (id.startsWith("MUSIC_")) id = id.substring("MUSIC_".length());
        if (id.isEmpty()) return null;
        String artist = string(wire, "artist");
        JsonArray artists = array(wire, "artists");
        if (artists != null && !artists.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonElement item : artists) {
                if (item != null && item.isJsonObject()) {
                    String name = string(item.getAsJsonObject(), "name");
                    if (!name.isEmpty()) names.add(name);
                }
            }
            if (!names.isEmpty()) artist = String.join(" / ", names);
        }
        String cover = first(string(wire, "albumPic"), string(wire, "albumPic120"),
                string(wire, "cover"), string(wire, "pic"));
        cover = cover.replaceAll("(?i)\\.webp(?=$|[?#])", ".jpg");
        long seconds = longValue(wire, "duration", 0L);
        JsonObject pay = object(wire, "payInfo");
        boolean vip = integer(pay, "paytype", 0) > 0 || integer(pay, "vip", 0) > 0;
        return new Track(id, first(string(wire, "name"), string(wire, "songName")), artist,
                first(string(wire, "album"), string(object(wire, "albumInfo"), "name")),
                cover, seconds * 1000L, vip);
    }

    static PlayList parsePlaylist(JsonObject wire, int defaultSource) {
        if (wire == null) return null;
        String id = first(string(wire, "id"), string(wire, "playlistId"));
        String name = first(string(wire, "name"), string(wire, "title"));
        if (id.isEmpty() || name.isEmpty()) return null;
        int source = integer(wire, "sourceType", 0);
        if (source <= 0) source = integer(wire, "source", defaultSource);
        if (source <= 0) source = defaultSource;
        String cover = first(string(wire, "pic"), string(wire, "cover"), string(wire, "albumPic"));
        long count = longValue(wire, "musicCount", longValue(wire, "musicnum", longValue(wire, "total", 0L)));
        String owner = first(string(wire, "creatorName"), string(wire, "creator_name"), string(wire, "nickname"));
        String description = first(string(wire, "description"), string(wire, "intro"));
        PlayList playlist = new PlayList(playlistNumericId(id, source), name, cover, (int) Math.max(0, count), 0L,
                null, description, 0L);
        playlist.setPlatform(MusicPlatform.BODIAN);
        playlist.setPlatformPlaylistId("pl-" + source + "-" + id);
        playlist.setSubscribed(source == 4 && bool(wire, "collect"));
        return playlist;
    }

    static PlayList parseCreatedPlaylist(JsonObject data, String requestedName) {
        JsonObject wire = object(data, "playlist");
        if (wire == null) wire = data;
        String id = first(string(wire, "id"), string(wire, "playlistId"));
        if (id.isEmpty()) return null;
        PlayList parsed = parsePlaylist(wire, 5);
        if (parsed != null) return parsed;
        long numericId;
        try {
            numericId = Long.parseLong(id);
        } catch (NumberFormatException malformed) {
            return null;
        }
        if (numericId <= 0L) return null;
        PlayList playlist = new PlayList(numericId,
                first(string(wire, "name"), string(wire, "title"), requestedName),
                first(string(wire, "pic"), string(wire, "cover")),
                (int) Math.max(0L, longValue(wire, "musicCount", 0L)), 0L,
                null, first(string(wire, "description"), string(wire, "intro")), 0L);
        playlist.setPlatform(MusicPlatform.BODIAN);
        playlist.setPlatformPlaylistId("pl-5-" + id);
        return playlist;
    }

    private static List<PlayList> playlistsFrom(JsonArray rows, int source) {
        if (rows == null) return Collections.emptyList();
        List<PlayList> result = new ArrayList<>();
        for (JsonElement item : rows) {
            if (item != null && item.isJsonObject()) {
                PlayList playlist = parsePlaylist(item.getAsJsonObject(), source);
                if (playlist != null) result.add(playlist);
            }
        }
        return result;
    }

    private static void appendPlaylists(Map<String, PlayList> output, JsonArray rows, int source) {
        if (rows == null) return;
        for (JsonElement item : rows) {
            if (item == null || !item.isJsonObject()) continue;
            PlayList playlist = parsePlaylist(item.getAsJsonObject(), source);
            if (playlist != null) output.putIfAbsent(playlist.getPlatformPlaylistId(), playlist);
        }
    }

    static List<PlayList> personalizedPlaylists(JsonObject home) {
        JsonArray groups = array(home, "songList");
        if (groups == null || groups.isEmpty()) return Collections.emptyList();
        List<PlayList> result = new ArrayList<>();
        int fallbackIndex = 0;
        for (JsonElement item : groups) {
            if (item == null || !item.isJsonObject()) continue;
            JsonObject group = item.getAsJsonObject();
            String index = string(group, "id");
            if (!index.matches("0|[1-9][0-9]{0,5}")) index = String.valueOf(fallbackIndex);
            List<Music> preview = tracksFrom(array(group, "songs"));
            if (preview.isEmpty()) {
                fallbackIndex++;
                continue;
            }
            String title = first(string(group, "title"), "为你推荐 " + (fallbackIndex + 1));
            PlayList playlist = createVirtualPlaylist("home:" + index, title, preview.size(),
                    "波点音乐个性化推荐");
            playlist.setMusics(new java.util.concurrent.CopyOnWriteArrayList<>(preview));
            result.add(playlist);
            fallbackIndex++;
        }
        return result;
    }

    private static List<Music> tracksFrom(JsonArray rows) {
        if (rows == null) return Collections.emptyList();
        List<Music> result = new ArrayList<>();
        for (JsonElement item : rows) {
            if (item == null || !item.isJsonObject()) continue;
            Music music = Music.fromBodianTrack(parseTrack(item.getAsJsonObject()));
            if (music != null) result.add(music);
        }
        return result;
    }

    private static PlayList createVirtualPlaylist(String id, String name, int count, String description) {
        PlayList playlist = new PlayList(playlistNumericId(id, 5), name, "", count, 0L,
                null, description, 0L);
        playlist.setPlatform(MusicPlatform.BODIAN);
        playlist.setPlatformPlaylistId(id);
        return playlist;
    }

    private static PlaylistRef parsePlaylistRef(String value) {
        String id = clean(value);
        int source = 5;
        boolean virtual = false;
        if (id.startsWith("pl-")) {
            int split = id.indexOf('-', 3);
            if (split <= 3) throw new IllegalArgumentException("无效的波点歌单 ID");
            try {
                source = Integer.parseInt(id.substring(3, split));
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("无效的波点歌单来源", malformed);
            }
            id = id.substring(split + 1);
        } else if (id.startsWith("liked-")) {
            id = id.substring("liked-".length());
            source = 5;
            virtual = true;
        } else if (id.startsWith("home:")) {
            throw new IllegalArgumentException("个性化推荐卡不是可读取的服务端歌单");
        }
        parsePositiveLong(id, "波点歌单 ID");
        return new PlaylistRef(id, source, virtual);
    }

    private static void requireOwnedPlaylist(PlaylistRef ref, BodianSessionStore.Session session) {
        if (ref == null || ref.virtual || ref.source != 5 || session == null || !session.isValid()) {
            throw new IllegalArgumentException("只能修改已登录账号的波点自建歌单");
        }
    }

    private static void requireCollectedPlaylist(PlaylistRef ref, BodianSessionStore.Session session) {
        if (ref == null || ref.source != 4 || session == null || !session.isValid()) {
            throw new IllegalArgumentException("只能收藏或取消收藏公开波点歌单");
        }
    }

    private static long parsePositiveLong(String value, String label) {
        String text = clean(value);
        if (!text.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("无效的" + label);
        }
        try {
            long number = Long.parseLong(text);
            if (number <= 0L) throw new NumberFormatException("non-positive");
            return number;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("无效的" + label, failure);
        }
    }

    private static synchronized void clearAccountCaches() {
        userPlaylists = Collections.emptyList();
        userPlaylistsLoaded = false;
        userPlaylistsLoading = false;
        userPlaylistsLastAttempt = 0L;
        userPlaylistsGeneration++;
        likedTrackIds = Collections.emptySet();
        likedTrackIdsLoaded = false;
        likedTrackIdsOwner = "";
        likedTrackIdsGeneration++;
        likedTrackIdsLoading = false;
        likedTrackIdsLoadToken++;
    }

    private static synchronized void clearUserPlaylists() {
        userPlaylists = Collections.emptyList();
        userPlaylistsLoaded = false;
        userPlaylistsLoading = false;
        userPlaylistsLastAttempt = 0L;
        userPlaylistsGeneration++;
    }

    public static boolean isVirtualPlaylistId(String playlistId) {
        String id = clean(playlistId);
        return id.startsWith("home:") || id.startsWith("liked-");
    }

    private static boolean isCurrentPlaylistRequest(BodianSessionStore.Session session, long generation) {
        return userPlaylistsGeneration == generation && sameSession(BodianSessionStore.snapshot(), session);
    }

    private static JsonObject request(String path, Map<String, String> query, JsonObject body,
                                      String method, BodianSessionStore.Session override,
                                      boolean includeSession) {
        BodianSessionStore.Session session = override != null ? override
                : includeSession ? BodianSessionStore.snapshot() : null;
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null) params.putAll(query);
        if (includeSession && session != null && session.isValid()) {
            params.put("uid", session.uid);
            params.put("token", session.token);
        }
        StringBuilder url = new StringBuilder(API).append(path);
        if (!params.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        String device = BodianSessionStore.deviceId();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("plat", "win")
                .header("channel", "W1")
                .header("ver", "1.1.7")
                .header("svrver", "13")
                .header("api-ver", "application/json")
                .header("brand", "Windows")
                .header("net", "wifi")
                .header("devid", device)
                .header("qimei36", device)
                .header("Accept", "application/json");
        if (session != null && session.isValid()) {
            builder.header("uid", session.uid).header("token", session.token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("波点音乐 HTTP " + response.statusCode());
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            int code = integer(envelope, "code", -1);
            if (code != 200) {
                if ((code == -101 || code == 11012) && session != null
                        && sameSession(BodianSessionStore.snapshot(), session)) {
                    BodianSessionStore.clearSession();
                    clearAccountCaches();
                }
                throw new ApiException(code, first(string(envelope, "msg"), "API error " + code));
            }
            JsonObject data = object(envelope, "data");
            return data == null ? new JsonObject() : data;
        } catch (ApiException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("波点音乐请求被中断", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("波点音乐请求失败: " + failure.getMessage(), failure);
        }
    }

    private static boolean sameSession(BodianSessionStore.Session left, BodianSessionStore.Session right) {
        return left != null && right != null && left.uid.equals(right.uid) && left.token.equals(right.token);
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try { return object != null && object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try { return object != null && object.has(key) ? object.get(key).getAsLong() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key) {
        try { return object != null && object.has(key) && object.get(key).getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private static String first(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private static long playlistNumericId(String id, int source) {
        long numeric = parseLong(id);
        if (numeric != 0L || "0".equals(id)) return numeric;
        long hash = 0xcbf29ce484222325L;
        String key = source + ":" + id;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? Long.MIN_VALUE : hash;
    }

    static long parseSize(String value) {
        if (blank(value)) return 0L;
        try {
            String text = value.trim().toLowerCase(java.util.Locale.ROOT);
            double scale = text.endsWith("gb") ? 1024d * 1024d * 1024d
                    : text.endsWith("mb") ? 1024d * 1024d : text.endsWith("kb") ? 1024d : 1d;
            String number = text.replaceAll("(?i)\\s*[kmg]?b$", "").trim();
            return (long) (Double.parseDouble(number) * scale);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
