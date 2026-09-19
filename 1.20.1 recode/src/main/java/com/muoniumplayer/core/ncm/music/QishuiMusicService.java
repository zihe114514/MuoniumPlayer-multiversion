package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import top.fpsmaster.music.QrCode;
import top.fpsmaster.music.QrLoginState;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 汽水音乐（Luna）内置音源。
 *
 * <p>搜索沿用 music-lib 的 Android 客户端请求形状；匿名播放与歌词走 qishui-api 已验证的
 * H5 {@code seo_track} 接口。登录使用官方 Passport 二维码，仅接受抖音 App 扫码，确认后把
 * Cookie 存在宿主自己的配置文件中。登录后优先请求带 Cookie 的 PC/播放信息接口，并继续只
 * 选择 AAC/M4A；加密样本在进入现有 AAC 解码器前于本机解密。</p>
 */
public final class QishuiMusicService {

    private static final String SEARCH_ENDPOINT = "https://api.qishui.com/luna/search/track";
    private static final String TRACK_ENDPOINT = "https://beta-luna.douyin.com/luna/h5/seo_track";
    private static final String PC_TRACK_ENDPOINT = "https://api.qishui.com/luna/pc/track_v2";
    private static final String PC_SEARCH_PLAYLIST_ENDPOINT = "https://api.qishui.com/luna/pc/search/playlist";
    private static final String PC_SEARCH_MIXED_ENDPOINT = "https://api.qishui.com/luna/pc/search/mixed";
    private static final String PC_ME_PLAYLISTS_ENDPOINT = "https://api.qishui.com/luna/pc/me/playlist";
    private static final String PC_ME_COLLECTION_ENDPOINT = "https://api.qishui.com/luna/pc/me/collection/mixed";
    private static final String LUNA_DISCOVER_ENDPOINT = "https://beta-luna.douyin.com/luna/discover";
    private static final String LUNA_DISCOVER_MIX_ENDPOINT = "https://beta-luna.douyin.com/luna/discover/mix";
    private static final String LUNA_PLAYLIST_DETAIL_ENDPOINT = "https://beta-luna.douyin.com/luna/playlist/detail";
    private static final String LUNA_RADIO_TRACKS_ENDPOINT = "https://beta-luna.douyin.com/luna/feed/radio/tracks";
    private static final String ACCOUNT_ENDPOINT = "https://api.qishui.com/luna/pc/me";
    private static final String PASSPORT_QR_ENDPOINT = "https://api.qishui.com/passport/web/get_qrcode/";
    private static final String PASSPORT_CHECK_ENDPOINT = "https://api.qishui.com/passport/web/check_qrconnect/";
    private static final String COVER_ROOT = "https://p3-luna.douyinpic.com/img/";
    private static final String ANDROID_USER_AGENT =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; "
                    + "Build/V417IR;tt-ok/3.12.13.19)";
    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final String PASSPORT_USER_AGENT = WEB_USER_AGENT;
    private static final String PC_USER_AGENT = "LunaPC/3.0.0(290101097)";
    private static final int REQUEST_TIMEOUT_MS = 15_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final Pattern SODA_WORD = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private static final Map<String, PendingQr> QR_SESSIONS = new ConcurrentHashMap<>();

    private QishuiMusicService() {
    }

    public static final class Track {
        public final String trackId;
        public final String title;
        public final String artist;
        public final String album;
        public final long durationMillis;
        public final String coverUrl;
        public final boolean vip;

        Track(String trackId, String title, String artist, String album, long durationMillis,
              String coverUrl, boolean vip) {
            this.trackId = trackId;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMillis = durationMillis;
            this.coverUrl = coverUrl;
            this.vip = vip;
        }
    }

    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final long bitrate;
        public final String playAuth;

        ResolveResult(String url, String format, long bitrate, String playAuth) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
            this.playAuth = playAuth;
        }
    }

    public static List<Track> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) return List.of();
        int effectiveLimit = Math.max(1, Math.min(50, limit));

        try {
            JsonObject response = getJson(buildSearchUrl(query.trim(), effectiveLimit),
                    ANDROID_USER_AGENT, "", Collections.emptyMap());
            JsonArray groups = array(response, "result_groups");
            if (groups == null) return List.of();

            List<Track> tracks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement groupElement : groups) {
                if (!groupElement.isJsonObject()) continue;
                JsonArray data = array(groupElement.getAsJsonObject(), "data");
                if (data == null) continue;
                for (JsonElement itemElement : data) {
                    if (!itemElement.isJsonObject()) continue;
                    JsonObject entity = object(itemElement.getAsJsonObject(), "entity");
                    JsonObject rawTrack = object(entity, "track");
                    String trackId = string(rawTrack, "id");
                    if (trackId.isEmpty() || !seen.add(trackId)) continue;
                    Track track = parseTrack(rawTrack);
                    if (track != null) tracks.add(track);
                    if (tracks.size() >= effectiveLimit) return tracks;
                }
            }
            return tracks;
        } catch (Throwable throwable) {
            System.err.println("[Music/Qishui] Search failed for " + query.trim() + ": "
                    + describe(throwable));
            return List.of();
        }
    }

    public static ResolveResult getSongUrl(String trackId, Quality requestedQuality) {
        String id = nonBlank(trackId);
        if (id.isEmpty()) return null;

        try {
            boolean authenticated = QishuiSessionStore.isLoggedIn();
            List<JsonObject> candidates = new ArrayList<>();
            if (authenticated) {
                JsonObject pc = fetchAuthenticatedTrack(id);
                if (pc != null) candidates.addAll(collectCandidates(pc));
            }
            try {
                JsonObject web = fetchTrack(id, authenticated);
                candidates.addAll(collectCandidates(web));
            } catch (Throwable webFailure) {
                if (candidates.isEmpty()) throw webFailure;
            }
            return selectAac(candidates, requestedQuality);
        } catch (Throwable throwable) {
            System.err.println("[Music/Qishui] Song URL failed for " + id + ": " + describe(throwable));
            return null;
        }
    }

    /** 汽水 H5 歌词已是逐字时间轴；转换为项目现有 QRC 解析器能识别的文本。 */
    public static List<LyricLine> getWordByWordLyrics(Music music) {
        if (music == null || !music.isQishui()) return Collections.emptyList();
        String trackId = nonBlank(music.getSourceId());
        if (trackId.isEmpty()) return Collections.emptyList();

        try {
            String content = lyricContent(fetchTrack(trackId, QishuiSessionStore.isLoggedIn()));
            if (content.isEmpty()) return Collections.emptyList();
            String qrc = sodaWordsToQrc(content);
            List<LyricLine> lines = new ArrayList<>();
            LyricParser.parseQrc(qrc, lines);
            return lines;
        } catch (Throwable throwable) {
            System.err.println("[Music/Qishui] Lyric failed for " + trackId + ": " + describe(throwable));
            return Collections.emptyList();
        }
    }
    public static QrCode createQrCode() {
        try {
            JsonResponse response = getJsonResponse(buildQrCreateUrl(), PASSPORT_USER_AGENT,
                    "", Collections.emptyMap());
            JsonObject data = object(response.body, "data");
            if (data.size() == 0) data = response.body;
            String token = string(data, "token");
            String content = string(data, "qrcode");
            if (token.isEmpty() || content.isEmpty()) {
                throw new IOException("汽水 Passport 未返回二维码");
            }
            long expiresAt = longValue(data, "expire_time");
            if (expiresAt > 0L && expiresAt < 10_000_000_000L) expiresAt *= 1000L;
            QR_SESSIONS.put(token, new PendingQr(response.cookieHeader, expiresAt, QrLoginState.WAITING));
            return new QrCode(token, content);
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("获取汽水登录二维码失败: " + describe(error), error);
        }
    }

    public static QrLoginState checkQrCode(QrCode qrCode) {
        if (qrCode == null || nonBlank(qrCode.getKey()).isEmpty()) return QrLoginState.ERROR;
        String token = qrCode.getKey().trim();
        PendingQr pending = QR_SESSIONS.get(token);
        if (pending != null && pending.expiresAt > 0L && System.currentTimeMillis() > pending.expiresAt) {
            QR_SESSIONS.remove(token);
            return QrLoginState.EXPIRED;
        }

        try {
            JsonResponse response = postFormResponse(buildQrCheckUrl(), qrCheckForm(token),
                    pending == null ? "" : pending.cookieHeader, PASSPORT_USER_AGENT);
            JsonObject data = object(response.body, "data");
            if (data.size() == 0) data = response.body;
            String sessionId = extractSessionIdFromCookieHeader(response.cookieHeader);
            String status = string(data, "status").toLowerCase(Locale.ROOT);
            String flow = string(data, "account_flow").toLowerCase(Locale.ROOT);
            int errorCode = (int) longValue(data, "error_code");
            if (flow.equals("verify") || errorCode == 2046) {
                QR_SESSIONS.remove(token);
                System.err.println("[Music/Qishui] Login requires upstream SMS verification for token "
                        + token.substring(0, Math.min(8, token.length())) + "...");
                return QrLoginState.ERROR;
            }
            if (!sessionId.isEmpty()) {
                Map<String, String> cookies = new LinkedHashMap<>();
                cookies.put("sessionid", sessionId);
                QishuiSessionStore.saveCookies(cookies);
                refreshProfile();
                QR_SESSIONS.remove(token);
                return QrLoginState.CONFIRMED;
            }

            QrLoginState state;
            if (errorCode == 7) {
                state = QrLoginState.WAITING;
            } else if ("confirmed".equals(status)) {
                state = QrLoginState.SCANNED;
            } else if ("scanned".equals(status) || "scan".equals(status)) {
                state = QrLoginState.SCANNED;
            } else if ("new".equals(status) || status.isEmpty()) {
                state = pending != null && pending.lastState == QrLoginState.SCANNED
                        ? QrLoginState.SCANNED : QrLoginState.WAITING;
            } else if ("expired".equals(status) || "canceled".equals(status)
                    || "cancelled".equals(status) || "refused".equals(status)) {
                state = QrLoginState.EXPIRED;
            } else if (errorCode != 0 || "error".equals(status) || "failed".equals(status)) {
                state = QrLoginState.ERROR;
            } else {
                state = QrLoginState.WAITING;
            }

            if (state == QrLoginState.EXPIRED) {
                QR_SESSIONS.remove(token);
            } else {
                QR_SESSIONS.put(token, new PendingQr(pending == null ? "" : pending.cookieHeader,
                        pending == null ? 0L : pending.expiresAt, state));
            }
            return state;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[Music/Qishui] QR check failed: " + describe(error));
            return QrLoginState.ERROR;
        }
    }

    public static boolean isLoggedIn() {
        return QishuiSessionStore.isLoggedIn();
    }

    /**
     * 本地留着凭据、但上游已经拒绝它。与「未登录」是两种状态：这种时候界面要说的是
     * 「Cookie 过期了，去重扫或重新导入」，而不是假装账号从来没登录过、把昵称头像全抹掉。
     */
    public static boolean isReauthRequired() {
        return QishuiSessionStore.isLoggedIn() && QishuiSessionStore.isReauthRequired();
    }

    public static String getAccountName() {
        QishuiSessionStore.Session session = QishuiSessionStore.snapshot();
        if (!session.loggedIn) return "未登录";
        if (!session.nickname.isEmpty()) return session.nickname;
        return "汽水音乐账号";
    }

    public static String getAccountId() {
        return QishuiSessionStore.snapshot().userId;
    }

    public static String getDouyinId() {
        return QishuiSessionStore.snapshot().douyinId;
    }

    public static String getAvatarUrl() {
        return QishuiSessionStore.snapshot().avatarUrl;
    }

    public static boolean isVipAccount() {
        return QishuiSessionStore.snapshot().vip;
    }

    public static boolean refreshProfile() {
        String cookie = QishuiSessionStore.getCookieHeader();
        if (cookie.isEmpty()) return false;
        try {
            JsonObject response = getJson(buildAccountUrl(), PC_USER_AGENT, cookie, pcHeaders());
            JsonObject info = object(response, "my_info");
            if (info.size() == 0) info = object(response, "user");
            if (info.size() == 0) info = response;
            String nickname = firstNonBlank(string(info, "nickname"), string(info, "name"));
            String userId = firstNonBlank(string(info, "id"), string(info, "user_id"));
            String douyinId = string(info, "douyin_id");
            String avatar = firstNonBlank(string(info, "avatar_url"), string(info, "avatar"));
            if (avatar.isEmpty()) avatar = firstUrl(info.get("avatar_thumb"));
            if (avatar.isEmpty()) avatar = firstUrl(info.get("avatar_uri"));
            boolean vip = truthy(info, "is_vip") || truthy(info, "isVip")
                    || !string(info, "vip_stage").isEmpty();
            QishuiSessionStore.saveProfile(userId, nickname, douyinId, avatar, vip,
                    string(info, "vip_stage"));
            QishuiSessionStore.markVerified();
            return true;
        } catch (Throwable error) {
            // 只有上游明确拒绝凭据时才标过期；网络抖动、超时、解析失败都不算，
            // 否则一次断网就会把好好登录着的账号踢成「需要重新登录」。
            if (looksLikeAuthRejection(error)) QishuiSessionStore.markReauthRequired();
            System.err.println("[Music/Qishui] Profile refresh failed: " + describe(error));
            return false;
        }
    }

    /**
     * 手动导入 Cookie 的结果。失败时也带一句能直接显示给用户的话，
     * 界面不需要自己去猜是哪一步崩的。
     */
    public static final class CookieImport {
        public final boolean success;
        public final String message;
        public final String nickname;
        public final boolean vip;

        private CookieImport(boolean success, String message, String nickname, boolean vip) {
            this.success = success;
            this.message = message;
            this.nickname = nickname;
            this.vip = vip;
        }

        private static CookieImport fail(String message) {
            return new CookieImport(false, message, "", false);
        }
    }

    /**
     * 用一份用户手上的 Cookie 文本换成本地会话。<b>必须在后台线程调用。</b>
     *
     * <p>顺序是刻意的：先解析、再判凭据名、然后拿它真打一次账号接口，三步全过才落盘。
     * 任何一步失败都原样返回，本地已有的登录态一个字都不会被动过——导入失败的代价
     * 不该是「把原来能用的账号弄没了」。
     */
    public static CookieImport importCookie(String rawInput) {
        Map<String, String> parsed = parseCookieInput(rawInput);
        if (parsed.isEmpty()) {
            return CookieImport.fail("没认出任何 Cookie 字段。可以粘完整的 Cookie 字符串，"
                    + "也可以只粘 sessionid=xxx");
        }
        if (!hasLoginCredential(parsed)) {
            return CookieImport.fail("里面没有登录凭据（sessionid / sid_tt / uid_tt / sid_guard 至少一个）。"
                    + "多半是只复制了匿名 cookie。");
        }
        String cookieHeader = joinCookieMap(parsed);
        try {
            JsonResponse response = getJsonResponse(buildAccountUrl(), PC_USER_AGENT,
                    cookieHeader, pcHeaders());
            JsonObject info = object(response.body, "my_info");
            if (info.size() == 0) info = object(response.body, "user");
            if (info.size() == 0) info = response.body;
            String nickname = firstNonBlank(string(info, "nickname"), string(info, "name"));
            if (nickname.isEmpty()) {
                return CookieImport.fail("账号接口没返回昵称，这份 Cookie 大概已经失效了。");
            }
            String userId = firstNonBlank(string(info, "id"), string(info, "user_id"));
            String douyinId = string(info, "douyin_id");
            String avatar = firstNonBlank(string(info, "avatar_url"), string(info, "avatar"));
            if (avatar.isEmpty()) avatar = firstUrl(info.get("avatar_thumb"));
            if (avatar.isEmpty()) avatar = firstUrl(info.get("avatar_uri"));
            boolean vip = truthy(info, "is_vip") || truthy(info, "isVip")
                    || !string(info, "vip_stage").isEmpty();

            // 上游可能在响应里轮换 sid_guard / sessionid，带回来就一并收下，
            // 不然存下去的那份下一秒就过期了。
            Map<String, String> merged = new LinkedHashMap<>(parsed);
            for (Map.Entry<String, String> entry
                    : parseCookieInput(response.cookieHeader).entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
            QishuiSessionStore.replaceCookies(merged);
            QishuiSessionStore.saveProfile(userId, nickname, douyinId, avatar, vip,
                    string(info, "vip_stage"));
            QishuiSessionStore.markVerified();
            return new CookieImport(true, "Cookie 登录成功", nickname, vip);
        } catch (Throwable error) {
            if (looksLikeAuthRejection(error)) {
                return CookieImport.fail("这份 Cookie 已被上游拒绝（过期、被顶下线或触发验证）。");
            }
            return CookieImport.fail("校验请求没打通：" + describe(error));
        }
    }

    /**
     * 容错解析。用户会从三个地方复制：浏览器 devtools 的 Cookie header 值、
     * 「Copy as cURL」里 -b 的那串、或者只盯着 sessionid 抄一条。参考实现
     * 的 normalizeQishuiCookieInput 也是同样思路（它还吃 JSON 数组形态），
     * 所以这里三种都要认。
     */
    static Map<String, String> parseCookieInput(String rawInput) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = nonBlank(rawInput).replace("\uFEFF", "");
        if (text.isEmpty()) return out;

        int colon = text.indexOf(':');
        if (text.regionMatches(true, 0, "Cookie", 0, 6) && colon > 0 && colon < 12) {
            text = text.substring(colon + 1);
        }
        int eq = text.toLowerCase(Locale.ROOT).indexOf("document.cookie");
        if (eq == 0) {
            int assign = text.indexOf('=');
            if (assign > 0) text = text.substring(assign + 1);
        }
        text = text.trim();

        // devtools / 插件导出的数组形态：[{"name":"sessionid","value":"..."}]
        if (text.startsWith("[")) {
            try {
                JsonElement parsedJson = JsonParser.parseString(text);
                if (parsedJson.isJsonArray()) {
                    for (JsonElement element : parsedJson.getAsJsonArray()) {
                        if (element == null || !element.isJsonObject()) continue;
                        JsonObject item = element.getAsJsonObject();
                        String name = nonBlank(string(item, "name"));
                        String value = nonBlank(string(item, "value"));
                        putCookie(out, name, value);
                    }
                }
            } catch (Throwable ignored) {
                // 当普通文本继续往下走，不因为一次猜错就报错。
            }
            if (!out.isEmpty()) return out;
        }

        for (String chunk : text.split("[;\r\n]+")) {
            String pair = chunk.trim();
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            putCookie(out, pair.substring(0, separator).trim(),
                    unquote(pair.substring(separator + 1).trim()));
        }
        return out;
    }

    /** 只收真正像 cookie 的键值对：属性名、空值、超长输入一律丢掉。 */
    private static void putCookie(Map<String, String> target, String name, String value) {
        if (target.size() >= 64) return;
        if (name.isEmpty() || value.isEmpty()) return;
        String lower = name.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "domain":
            case "path":
            case "expires":
            case "max-age":
            case "httponly":
            case "secure":
            case "samesite":
            case "partitioned":
                return;
            default:
                break;
        }
        if (value.length() > 4096) return;
        target.put(name, value);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** 判定「这份 cookie 算不算登录态」，与参考实现的 qishuiCookieHasLogin 同一组名字。 */
    static boolean hasLoginCredential(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return false;
        String[] names = {"sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "sid_guard"};
        for (String name : names) {
            if (!nonBlank(cookies.get(name)).isEmpty()) return true;
        }
        return false;
    }

    private static String joinCookieMap(Map<String, String> cookies) {
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (header.length() > 0) header.append("; ");
            header.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return header.length() == 0 ? "" : header.append(';').toString();
    }

    /** 上游拒绝凭据的两种表现：HTTP 401/403，或 200 里带 1000016。 */
    private static boolean looksLikeAuthRejection(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String message = nonBlank(cursor.getMessage());
            if (message.isEmpty()) continue;
            if (message.contains("HTTP 401") || message.contains("HTTP 403")
                    || message.contains("1000016")) {
                return true;
            }
        }
        return false;
    }

    public static void logout() {
        QR_SESSIONS.clear();
        QishuiSessionStore.clear();
    }

    public static void decryptDownloadedAudio(File file, String playAuth) throws IOException {
        if (file == null || playAuth == null || playAuth.trim().isEmpty()) return;
        QishuiAudioDecryptor.decryptInPlace(file.toPath(), playAuth);
    }
    /** 汽水发现页推荐歌单。接口为公开 App 流，返回卡片所需的最小元数据。 */
    public static List<PlayList> recommendPlaylists(int limit) {
        int target = Math.max(1, Math.min(120, limit));
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        String cursor = "";
        String sessionId = "";
        for (int page = 0; page < 4 && unique.size() < target; page++) {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("count", Math.min(50, target - unique.size()));
                if (!cursor.isEmpty()) payload.addProperty("cursor", cursor);
                if (!sessionId.isEmpty()) payload.addProperty("session_id", sessionId);
                JsonObject response = postJson(LUNA_DISCOVER_MIX_ENDPOINT, payload.toString(),
                        ANDROID_USER_AGENT, "", Collections.emptyMap());
                collectPlaylistResources(array(response, "inner_block"), unique, target);
                if (unique.isEmpty()) collectPlaylistResources(array(response, "blocks"), unique, target);
                String nextCursor = string(response, "next_cursor");
                String nextSession = string(response, "session_id");
                boolean hasMore = truthy(response, "has_more");
                if (!hasMore || nextCursor.isEmpty()
                        || (nextCursor.equals(cursor) && nextSession.equals(sessionId))) break;
                cursor = nextCursor;
                sessionId = nextSession;
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Recommend playlists failed: " + describe(error));
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** 汽水发现页电台。当前上游有时把电台放在 blocks，有时放在 discover/mix 的 inner_block。 */
    public static List<PlayList> recommendRadios(int limit) {
        int target = Math.max(1, Math.min(80, limit));
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("first_request", true);
            JsonObject response = postJson(LUNA_DISCOVER_ENDPOINT, payload.toString(),
                    ANDROID_USER_AGENT, "", Collections.emptyMap());
            collectRadioResources(array(response, "blocks"), unique, target);
            if (unique.isEmpty()) collectRadioResources(array(response, "inner_block"), unique, target);
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Recommend radios failed: " + describe(error));
        }
        return new ArrayList<>(unique.values());
    }

    /** PC 歌单搜索，失败时回退到混合搜索中的歌单分组。 */
    public static List<PlayList> searchPlaylists(String query, int limit) {
        String keyword = nonBlank(query);
        if (keyword.isEmpty()) return Collections.emptyList();
        int target = Math.max(1, Math.min(100, limit));
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        try {
            JsonObject response = getJson(buildPcSearchUrl(keyword, 0), PC_USER_AGENT,
                    QishuiSessionStore.getCookieHeader(), pcHeaders());
            collectPlaylistsFromSearch(response, unique, target);
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Playlist search failed: " + describe(error));
        }
        if (unique.isEmpty()) {
            try {
                JsonObject response = getJson(buildPcMixedSearchUrl(keyword, 0), PC_USER_AGENT,
                        QishuiSessionStore.getCookieHeader(), pcHeaders());
                collectPlaylistsFromSearch(response, unique, target);
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Mixed playlist search failed: " + describe(error));
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** 歌单详情。只保留 feed 中的曲目实体，视频/播客条目会被忽略。 */
    public static List<Music> playlistTracks(String playlistId, int limit) {
        String id = nonBlank(playlistId);
        if (id.isEmpty()) return Collections.emptyList();
        int target = Math.max(1, Math.min(500, limit));
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        String cursor = "";
        String sessionId = "";
        for (int page = 0; page < 12 && unique.size() < target; page++) {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("playlist_id", id);
                payload.addProperty("playlist_type", 0);
                payload.addProperty("count", Math.min(50, target - unique.size()));
                if (!cursor.isEmpty()) payload.addProperty("cursor", cursor);
                if (!sessionId.isEmpty()) payload.addProperty("session_id", sessionId);
                JsonObject response = postJson(LUNA_PLAYLIST_DETAIL_ENDPOINT, payload.toString(),
                        ANDROID_USER_AGENT, "", Collections.emptyMap());
                collectFeedTracks(array(response, "media_resources"), unique, target);
                String nextCursor = string(response, "next_cursor");
                String nextSession = string(response, "session_id");
                if (nextCursor.isEmpty()
                        || (nextCursor.equals(cursor) && nextSession.equals(sessionId))) break;
                cursor = nextCursor;
                sessionId = nextSession;
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Playlist detail failed for " + id + ": " + describe(error));
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static final Pattern PLAYLIST_ID_PATH = Pattern.compile("playlist/(\\d{6,})");
    private static final Pattern PLAYLIST_ID_PARAM = Pattern.compile("[?&](?:playlist_id|id)=(\\d{6,})");
    private static final Pattern EMBEDDED_LONG_ID = Pattern.compile("(\\d{10,})");
    private static final Pattern ANY_URL = Pattern.compile("https?://[^\\s\"'<>，。]+");

    /** 只用于跟随抖音短链的 302：不自动重定向，逐跳读 Location，命中歌单 ID 即返回。 */
    private static final HttpClient REDIRECT_NEVER_HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 从用户粘贴的文本里提取汽水歌单 ID。兼容：纯数字 ID、{@code .../playlist/123}、
     * {@code ?playlist_id=123}、{@code ?id=123}、分享口令里内嵌的一串长数字；对
     * 抖音短链（{@code v.douyin.com}、{@code douyin.com/s/}、{@code qishui.douyin.com}）
     * 会跟随一次 302 再用最终 URL 复提取。
     *
     * @return 歌单 ID；文本里找不到可用 ID 时返回 {@code null}
     */
    public static String extractPlaylistId(String input) {
        String text = nonBlank(input);
        if (text.isEmpty()) return null;

        String id = firstGroupId(PLAYLIST_ID_PATH, text);
        if (id != null) return id;
        id = firstGroupId(PLAYLIST_ID_PARAM, text);
        if (id != null) return id;
        if (text.matches("\\d{6,}")) return text;
        id = firstGroupId(EMBEDDED_LONG_ID, text);
        if (id != null) return id;

        Matcher urlMatcher = ANY_URL.matcher(text);
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            if (!isQishuiShortLink(url)) continue;
            String resolved = resolveRedirectPlaylistId(url);
            if (resolved != null) return resolved;
        }
        return null;
    }

    private static boolean isQishuiShortLink(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("douyin.com") || lower.contains("qishui");
    }

    private static String resolveRedirectPlaylistId(String url) {
        String current = url;
        for (int hop = 0; hop < 5 && current != null; hop++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(current))
                        .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                        .header("User-Agent", WEB_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,*/*")
                        .GET().build();
                HttpResponse<Void> response = REDIRECT_NEVER_HTTP.send(request,
                        HttpResponse.BodyHandlers.discarding());
                String location = response.headers().firstValue("location").orElse("");
                if (location.isEmpty()) return null;
                current = URI.create(current).resolve(location).toString();
                String id = firstGroupId(PLAYLIST_ID_PATH, current);
                if (id == null) id = firstGroupId(PLAYLIST_ID_PARAM, current);
                if (id != null) return id;
                if (!isQishuiShortLink(current)) return null;
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private static String firstGroupId(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 匿名导入一个汽水歌单：解析 ID 后请求一次歌单详情（不带 Cookie）拿标题/封面/曲目数，
     * 构造 platform=QISHUI 的 {@link PlayList} 引用返回。曲目列表不在此落地——UI 每次点开
     * 时由 {@link #playlistTracks(String, int)} 匿名向上游拉取，因此导入与登录、账号完全解耦。
     *
     * @throws IllegalArgumentException 文本里解析不到有效歌单 ID
     */
    public static PlayList importPlaylist(String input) {
        String id = extractPlaylistId(input);
        if (id == null) {
            throw new IllegalArgumentException(
                    "没识别出汽水歌单 ID，请粘贴歌单分享链接或直接输入数字 ID");
        }

        String title = "";
        String cover = "";
        int count = 0;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("playlist_id", id);
            payload.addProperty("playlist_type", 0);
            payload.addProperty("count", 1);
            JsonObject response = postJson(LUNA_PLAYLIST_DETAIL_ENDPOINT, payload.toString(),
                    ANDROID_USER_AGENT, "", Collections.emptyMap());
            JsonObject meta = object(response, "playlist");
            if (meta.size() == 0) meta = object(object(response, "data"), "playlist");
            title = firstNonBlank(string(meta, "title"), string(meta, "name"));
            cover = coverUrl(object(meta, "url_cover"));
            if (cover.isEmpty()) cover = firstUrl(meta.get("cover_url"));
            long declared = longValue(meta, "count_tracks");
            if (declared <= 0) declared = longValue(meta, "song_count");
            count = checkedInt(declared, "playlist count");
        } catch (Throwable error) {
            // 元数据拿不到不阻断导入：曲目会在点开时匿名再拉，只是标题先兜底成 ID。
            System.err.println("[Music/Qishui] Import metadata failed for " + id + ": "
                    + describe(error));
        }

        PlayList playlist = new PlayList(stableLong("qishui:playlist:" + id),
                title.isEmpty() ? "汽水歌单 " + id : title, cover, Math.max(0, count),
                0L, null, "", 0L);
        playlist.setPlatform(MusicPlatform.QISHUI);
        playlist.setPlatformPlaylistId(id);
        return playlist;
    }

    /** 电台歌曲。radio id 可以直接传入，也可使用 UI 的 radio: 前缀。 */
    public static List<Music> radioTracks(String radioId, int limit) {
        String id = nonBlank(radioId);
        if (id.startsWith("radio:")) id = id.substring("radio:".length()).trim();
        if (id.isEmpty()) return Collections.emptyList();
        int target = Math.max(1, Math.min(500, limit));
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        String cursor = "";
        for (int page = 0; page < 12 && unique.size() < target; page++) {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("radio_id", id);
                payload.addProperty("count", Math.min(50, target - unique.size()));
                if (!cursor.isEmpty()) payload.addProperty("cursor", cursor);
                JsonObject response = postJson(LUNA_RADIO_TRACKS_ENDPOINT, payload.toString(),
                        ANDROID_USER_AGENT, "", Collections.emptyMap());
                collectFeedTracks(array(response, "items"), unique, target);
                String nextCursor = firstNonBlank(string(response, "next_cursor"), string(response, "cursor"));
                if (nextCursor.isEmpty() || nextCursor.equals(cursor)) break;
                cursor = nextCursor;
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Radio tracks failed for " + id + ": " + describe(error));
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    public static List<PlayList> userPlaylists(int limit) {
        if (!QishuiSessionStore.isLoggedIn()) return Collections.emptyList();
        try {
            JsonObject response = getJson(buildPcMePlaylistsUrl(), PC_USER_AGENT,
                    QishuiSessionStore.getCookieHeader(), pcHeaders());
            return parsePlaylistArray(array(response, "playlists"), Math.max(1, Math.min(200, limit)));
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] User playlists failed: " + describe(error));
            return Collections.emptyList();
        }
    }

    public static List<PlayList> collectionPlaylists(int limit) {
        return parseCollectionPlaylists(fetchUserCollection(), limit);
    }

    public static List<Music> collectionTracks(int limit) {
        if (!QishuiSessionStore.isLoggedIn()) return Collections.emptyList();
        JsonObject response = fetchUserCollection();
        int target = Math.max(1, Math.min(500, limit));
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        JsonArray collections = array(response, "mixed_collections");
        if (collections != null) {
            for (JsonElement element : collections) {
                if (!element.isJsonObject()) continue;
                Music music = parseFeedTrack(element);
                if (music != null) unique.put(music.getSourceId(), music);
                if (unique.size() >= target) break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static JsonObject fetchUserCollection() {
        if (!QishuiSessionStore.isLoggedIn()) return new JsonObject();
        try {
            return getJson(buildPcMeCollectionUrl(), PC_USER_AGENT,
                    QishuiSessionStore.getCookieHeader(), pcHeaders());
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] User collection failed: " + describe(error));
            return new JsonObject();
        }
    }

    private static List<PlayList> parseCollectionPlaylists(JsonObject response, int limit) {
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        JsonArray collections = array(response, "mixed_collections");
        if (collections != null) {
            for (JsonElement element : collections) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                JsonObject raw = object(item, "playlist");
                if (raw.size() == 0) raw = object(object(item, "media"), "playlist");
                PlayList playlist = parsePlaylistCard(raw, object(item, "style"));
                if (playlist != null) unique.put(playlist.getPlatformPlaylistId(), playlist);
                if (unique.size() >= Math.max(1, Math.min(200, limit))) break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static void collectPlaylistResources(JsonArray blocks, Map<String, PlayList> output, int limit) {
        if (blocks == null) return;
        for (JsonElement blockElement : blocks) {
            if (!blockElement.isJsonObject() || output.size() >= limit) return;
            JsonObject block = blockElement.getAsJsonObject();
            JsonArray resources = array(block, "resources");
            if (resources == null) continue;
            for (JsonElement resourceElement : resources) {
                if (!resourceElement.isJsonObject() || output.size() >= limit) return;
                JsonObject resource = resourceElement.getAsJsonObject();
                JsonObject entity = object(resource, "entity");
                JsonObject raw = object(entity, "playlist");
                if (raw.size() == 0) raw = object(resource, "playlist");
                PlayList playlist = parsePlaylistCard(raw, object(resource, "style"));
                if (playlist != null) output.put(playlist.getPlatformPlaylistId(), playlist);
            }
        }
    }

    private static void collectRadioResources(JsonArray blocks, Map<String, PlayList> output, int limit) {
        if (blocks == null) return;
        for (JsonElement blockElement : blocks) {
            if (!blockElement.isJsonObject() || output.size() >= limit) return;
            JsonObject block = blockElement.getAsJsonObject();
            JsonArray resources = array(block, "resources");
            if (resources != null) {
                for (JsonElement resourceElement : resources) {
                    if (!resourceElement.isJsonObject() || output.size() >= limit) return;
                    JsonObject resource = resourceElement.getAsJsonObject();
                    JsonObject style = object(resource, "style");
                    JsonObject radio = object(object(resource, "entity"), "radio");
                    String type = string(resource, "type");
                    if (!"radio".equals(type) && radio.size() == 0) continue;
                    String id = firstNonBlank(string(resource, "resource_id"), string(radio, "radio_id"));
                    if (id.isEmpty()) continue;
                    String title = firstNonBlank(string(style, "title"), string(radio, "radio_name"),
                            string(resource, "title"));
                    if (title.isEmpty()) title = "汽水电台";
                    String cover = firstUrl(style.get("cover_url_list"));
                    if (cover.isEmpty()) cover = firstUrl(style.get("cover_url"));
                    String description = firstNonBlank(string(style, "desc"), string(radio, "description"));
                    PlayList playlist = new PlayList(stableLong("qishui:radio:" + id), title, cover,
                            0, 0L, null, description, 0L);
                    playlist.setPlatform(MusicPlatform.QISHUI);
                    playlist.setPlatformPlaylistId("radio:" + id);
                    output.put(id, playlist);
                }
            }
            collectRadioResources(array(block, "inner_block"), output, limit);
        }
    }

    private static void collectPlaylistsFromSearch(JsonObject response, Map<String, PlayList> output, int limit) {
        JsonArray groups = array(response, "result_groups");
        if (groups == null) return;
        for (JsonElement groupElement : groups) {
            if (!groupElement.isJsonObject() || output.size() >= limit) return;
            JsonObject group = groupElement.getAsJsonObject();
            JsonArray data = array(group, "data");
            if (data == null) continue;
            for (JsonElement itemElement : data) {
                if (!itemElement.isJsonObject() || output.size() >= limit) return;
                JsonObject item = itemElement.getAsJsonObject();
                JsonObject entity = object(item, "entity");
                JsonObject raw = object(entity, "playlist");
                if (raw.size() == 0) raw = object(item, "playlist");
                PlayList playlist = parsePlaylistCard(raw, object(item, "style"));
                if (playlist != null) output.put(playlist.getPlatformPlaylistId(), playlist);
            }
        }
    }

    private static List<PlayList> parsePlaylistArray(JsonArray array, int limit) {
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        if (array == null) return Collections.emptyList();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            PlayList playlist = parsePlaylistCard(element.getAsJsonObject(), new JsonObject());
            if (playlist != null) unique.put(playlist.getPlatformPlaylistId(), playlist);
            if (unique.size() >= limit) break;
        }
        return new ArrayList<>(unique.values());
    }

    private static PlayList parsePlaylistCard(JsonObject raw, JsonObject style) {
        if (raw == null || raw.size() == 0) return null;
        String id = firstNonBlank(string(raw, "id"), string(raw, "playlist_id"));
        String title = firstNonBlank(string(raw, "title"), string(raw, "name"), string(style, "title"));
        if (id.isEmpty() || title.isEmpty()) return null;
        String cover = coverUrl(object(raw, "url_cover"));
        if (cover.isEmpty()) cover = firstUrl(raw.get("cover_url"));
        if (cover.isEmpty()) cover = firstUrl(style.get("cover_url"));
        if (cover.isEmpty()) cover = firstUrl(style.get("cover_url_list"));
        int count = checkedInt(longValue(raw, "count_tracks"), "playlist count");
        if (count <= 0) count = checkedInt(longValue(raw, "track_count"), "playlist count");
        if (count <= 0) count = checkedInt(longValue(raw, "song_count"), "playlist count");
        String description = firstNonBlank(string(raw, "desc"), string(raw, "description"),
                string(style, "desc"));
        PlayList playlist = new PlayList(stableLong("qishui:playlist:" + id), title, cover,
                Math.max(0, count), 0L, null, description, 0L);
        playlist.setPlatform(MusicPlatform.QISHUI);
        playlist.setPlatformPlaylistId(id);
        return playlist;
    }

    private static void collectFeedTracks(JsonArray items, Map<String, Music> output, int limit) {
        if (items == null) return;
        for (JsonElement item : items) {
            if (output.size() >= limit) return;
            Music music = parseFeedTrack(item);
            if (music != null && !music.getSourceId().isEmpty()) output.put(music.getSourceId(), music);
        }
    }

    private static Music parseFeedTrack(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject item = element.getAsJsonObject();
        JsonObject entity = object(item, "entity");
        JsonObject wrapper = object(entity, "track_wrapper");
        JsonObject raw = object(wrapper, "track");
        if (raw.size() == 0) raw = object(entity, "track");
        if (raw.size() == 0) raw = object(item, "track");
        if (raw.size() == 0) raw = object(object(item, "media"), "track");
        if (raw.size() == 0) raw = item;
        Track track = parseTrack(raw);
        return track == null ? null : Music.fromQishuiTrack(track);
    }

    private static int checkedInt(long value, String name) {
        if (value <= 0L) return 0;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long stableLong(String value) {
        String safe = value == null ? "" : value;
        try {
            long parsed = Long.parseLong(safe);
            if (parsed > 0L) return parsed;
        } catch (NumberFormatException ignored) {
        }
        long hash = 1125899906842597L;
        for (int index = 0; index < safe.length(); index++) {
            hash = 31L * hash + safe.charAt(index);
        }
        return hash & Long.MAX_VALUE;
    }
    private static Track parseTrack(JsonObject rawTrack) {
        String trackId = string(rawTrack, "id");
        String title = string(rawTrack, "name");
        if (trackId.isEmpty() || title.isEmpty()) return null;

        String artist = joinArtists(array(rawTrack, "artists"));
        JsonObject album = object(rawTrack, "album");
        String albumName = string(album, "name");
        String cover = coverUrl(object(album, "url_cover"));
        long duration = longValue(rawTrack, "duration");
        if (duration > 0L && duration < 1000L) duration *= 1000L;
        JsonObject label = object(rawTrack, "label_info");
        boolean vip = truthy(label, "only_vip_playable");
        return new Track(trackId, title, artist, albumName, duration, cover, vip);
    }

    private static JsonObject fetchTrack(String trackId, boolean authenticated)
            throws IOException, InterruptedException {
        return getJson(TRACK_ENDPOINT + "?track_id=" + urlEncode(trackId) + "&device_platform=web",
                WEB_USER_AGENT, authenticated ? QishuiSessionStore.getCookieHeader() : "",
                Collections.emptyMap());
    }

    private static JsonObject fetchAuthenticatedTrack(String trackId) {
        if (!QishuiSessionStore.isLoggedIn()) return null;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("track_id", trackId);
            payload.addProperty("media_type", "track");
            payload.addProperty("queue_type", "favorite_track_playlist");
            payload.addProperty("scene_name", "library");
            return postJson(buildPcTrackUrl(), payload.toString(), PC_USER_AGENT,
                    QishuiSessionStore.getCookieHeader(), pcHeaders());
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Authenticated track request failed: " + describe(error));
            return null;
        }
    }

    private static JsonObject fetchPlayerInfo(String url) throws IOException, InterruptedException {
        return getJson(url, WEB_USER_AGENT, QishuiSessionStore.getCookieHeader(),
                Collections.emptyMap());
    }

    private static List<JsonObject> collectCandidates(JsonObject response) {
        List<JsonObject> candidates = new ArrayList<>();
        JsonObject player = object(response, "track_player");
        JsonElement videoModel = player.get("video_model");
        collectCandidateObjects(parseEmbeddedJson(videoModel), candidates, 0);
        String playerInfoUrl = firstNonBlank(string(player, "url_player_info"),
                string(player, "urlPlayerInfo"));
        if (!playerInfoUrl.isEmpty()) {
            try {
                collectCandidateObjects(fetchPlayerInfo(playerInfoUrl), candidates, 0);
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Player info request failed: " + describe(error));
            }
        }
        return candidates;
    }

    private static void collectCandidateObjects(JsonElement element, List<JsonObject> output, int depth) {
        if (element == null || element.isJsonNull() || depth > 8) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectCandidateObjects(child, output, depth + 1);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (!audioUrl(object).isEmpty()) output.add(object);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            collectCandidateObjects(entry.getValue(), output, depth + 1);
        }
    }
    private static ResolveResult selectAac(List<JsonObject> candidates, Quality requestedQuality) {
        boolean preferHigher = requestedQuality != Quality.STANDARD;
        JsonObject selected = null;
        long selectedScore = preferHigher ? Long.MIN_VALUE : Long.MAX_VALUE;
        long selectedDuration = 0L;
        for (JsonObject candidate : candidates) {
            String url = audioUrl(candidate);
            if (url.isEmpty()) continue;
            JsonObject meta = object(candidate, "video_meta");
            String codec = firstNonBlank(string(candidate, "codec_type"),
                    string(meta, "codec_type")).toLowerCase(Locale.ROOT);
            String format = firstNonBlank(string(candidate, "format"), string(candidate, "vtype"),
                    string(candidate, "Format"), string(meta, "format"),
                    string(meta, "vtype")).toLowerCase(Locale.ROOT);
            String mime = firstNonBlank(string(candidate, "mime_type"),
                    string(candidate, "MimeType"), string(meta, "mime_type")).toLowerCase(Locale.ROOT);
            boolean aac = codec.contains("aac") || format.contains("m4a") || format.contains("aac")
                    || mime.contains("audio/mp4") || url.toLowerCase(Locale.ROOT).contains("audio_mp4");
            if (!aac || format.contains("flac") || format.contains("wav")) continue;

            long bitrate = longValue(candidate, "bitrate");
            if (bitrate <= 0L) bitrate = longValue(candidate, "Bitrate");
            if (bitrate <= 0L) bitrate = longValue(candidate, "real_bitrate");
            if (bitrate <= 0L) bitrate = longValue(candidate, "br");
            if (bitrate <= 0L) bitrate = longValue(meta, "bitrate");
            if (bitrate <= 0L) bitrate = qualityScore(string(candidate, "quality"));
            long duration = longValue(candidate, "duration");
            if (duration <= 0L) duration = longValue(candidate, "video_duration");
            if (duration <= 0L) duration = longValue(meta, "duration");
            if (duration > 0L && duration < 1000L) duration *= 1000L;
            boolean fullStream = duration >= 60_000L;
            boolean selectedFull = selected != null && selectedDuration >= 60_000L;
            boolean better = selected == null || (fullStream && !selectedFull)
                    || (fullStream == selectedFull
                    && (preferHigher ? bitrate >= selectedScore : bitrate <= selectedScore));
            if (better) {
                selected = candidate;
                selectedScore = bitrate;
                selectedDuration = duration;
            }
        }
        if (selected == null) return null;

        String url = audioUrl(selected);
        String format = firstNonBlank(string(selected, "format"), string(selected, "Format"),
                string(selected, "vtype")).toLowerCase(Locale.ROOT);
        String normalized = format.contains("aac") && !format.contains("m4a") && !url.contains(".m4a")
                ? "aac" : "m4a";
        long bitrate = longValue(selected, "bitrate");
        if (bitrate <= 0L) bitrate = longValue(selected, "Bitrate");
        if (bitrate <= 0L) bitrate = longValue(selected, "real_bitrate");
        if (bitrate <= 0L) bitrate = longValue(selected, "br");
        return new ResolveResult(url, normalized, bitrate, playAuth(selected));
    }

    private static String audioUrl(JsonObject candidate) {
        String url = firstNonBlank(string(candidate, "main_url"), string(candidate, "backup_url"),
                string(candidate, "main_play_url"), string(candidate, "backup_play_url"),
                string(candidate, "MainPlayUrl"), string(candidate, "BackupPlayUrl"),
                string(candidate, "play_url"), string(candidate, "PlayURL"));
        if (!url.isEmpty()) return url;
        JsonArray urls = array(candidate, "url_list");
        if (urls != null) {
            for (JsonElement item : urls) if (item.isJsonPrimitive()) return item.getAsString();
        }
        return "";
    }

    private static String playAuth(JsonObject candidate) {
        String auth = firstNonBlank(string(candidate, "play_auth"), string(candidate, "PlayAuth"),
                string(candidate, "spade_a"), string(candidate, "SpadeA"));
        if (!auth.isEmpty()) return auth;
        JsonObject encrypt = object(candidate, "encrypt_info");
        return firstNonBlank(string(encrypt, "spade_a"), string(encrypt, "SpadeA"),
                string(encrypt, "play_auth"), string(encrypt, "PlayAuth"));
    }

    private static long qualityScore(String quality) {
        String value = nonBlank(quality).toLowerCase(Locale.ROOT);
        if (value.contains("hires") || value.contains("hi_res")) return 2000L;
        if (value.contains("lossless") || value.contains("flac") || value.contains("sq")) return 1500L;
        if (value.contains("higher") || value.contains("exhigh") || value.contains("highest")) return 320L;
        if (value.contains("standard") || value.contains("normal")) return 128L;
        return 0L;
    }

    private static JsonElement parseEmbeddedJson(JsonElement value) {
        JsonElement current = value;
        for (int depth = 0; depth < 3 && current != null && current.isJsonPrimitive()
                && current.getAsJsonPrimitive().isString(); depth++) {
            String text = current.getAsString();
            try {
                current = JsonParser.parseString(text);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return current;
    }

    private static String lyricContent(JsonObject response) {
        JsonObject seo = object(response, "seo_track");
        String content = string(object(seo, "lyric"), "content");
        if (!content.isEmpty()) return content;
        return string(object(response, "lyric"), "content");
    }

    private static String sodaWordsToQrc(String raw) {
        StringBuilder converted = new StringBuilder(raw.length() + 32);
        String[] lines = raw.replace("\r", "").split("\n");
        for (String line : lines) {
            int firstTag = line.indexOf('<');
            if (firstTag > 0) converted.append(line, 0, firstTag);
            Matcher matcher = SODA_WORD.matcher(line);
            boolean appended = false;
            while (matcher.find()) {
                String text = matcher.group(4);
                if (text.isEmpty()) continue;
                converted.append(text).append('(').append(matcher.group(1)).append(',')
                        .append(matcher.group(2)).append(')');
                appended = true;
            }
            if (appended) converted.append('\n');
        }
        return converted.toString();
    }
    private static String buildSearchUrl(String query, int limit) {
        StringBuilder url = new StringBuilder(SEARCH_ENDPOINT).append('?');
        append(url, "device_platform", "android");
        append(url, "os", "android");
        append(url, "ssmix", "a");
        append(url, "cdid", "46556f98-1720-4248-83da-62b74b60b46a");
        append(url, "channel", "xiaomi_8478_64");
        append(url, "aid", "386088");
        append(url, "app_name", "luna");
        append(url, "version_code", "100198030");
        append(url, "version_name", "19.8.0");
        append(url, "manifest_version_code", "100198030");
        append(url, "update_version_code", "100198030");
        append(url, "resolution", "1080*1920");
        append(url, "dpi", "480");
        append(url, "device_type", "ABR-AL80");
        append(url, "device_brand", "HUAWEI");
        append(url, "language", "zh");
        append(url, "os_api", "35");
        append(url, "os_version", "15");
        append(url, "ac", "wifi");
        append(url, "device_model", "ABR-AL80");
        append(url, "save_power", "0");
        append(url, "font_size", "1.00");
        append(url, "luna_first_launch_apk_type", "normal_apk");
        append(url, "diversion_channel_name", "xiaomi_8478_64");
        append(url, "is_car_play", "0");
        append(url, "battery", "0.99");
        append(url, "network_speed", "10156");
        append(url, "hybrid_version_code", "100198030");
        append(url, "tz_name", "Asia/Shanghai");
        append(url, "tz_offset", "28800");
        append(url, "luna_register_time", "1784311292");
        append(url, "package", "com.luna.music");
        append(url, "charge", "0");
        append(url, "luna_apk_type", "normal_apk");
        append(url, "output_device_type", "Phone");
        append(url, "volume", "1.00");
        append(url, "brightness", "0.08");
        append(url, "need_personal_recommend", "1");
        append(url, "is_teen_mode", "0");
        append(url, "sim_region", "cn");
        append(url, "android_device_type", "default");
        append(url, "iid", "2204957404569386");
        append(url, "device_id", "2204957404565290");
        append(url, "_rticket", String.valueOf(System.currentTimeMillis()));
        append(url, "q", query);
        append(url, "cursor", "0");
        append(url, "count", String.valueOf(limit));
        return url.toString();
    }
    private static String buildQrCreateUrl() {
        StringBuilder url = new StringBuilder(PASSPORT_QR_ENDPOINT).append('?');
        append(url, "passport_jssdk_version", "2.4.13");
        append(url, "passport_jssdk_type", "normal");
        append(url, "is_from_ttaccountsdk", "1");
        append(url, "aid", "386088");
        append(url, "next", "https://api.qishui.com");
        append(url, "need_logo", "false");
        append(url, "need_short_url", "false");
        append(url, "is_new_login", "1");
        return url.toString();
    }

    private static String buildQrCheckUrl() {
        StringBuilder url = new StringBuilder(PASSPORT_CHECK_ENDPOINT).append('?');
        append(url, "passport_jssdk_version", "2.4.13");
        append(url, "passport_jssdk_type", "normal");
        append(url, "is_from_ttaccountsdk", "1");
        append(url, "aid", "386088");
        append(url, "iid", "27960026095955");
        return url.toString();
    }

    private static Map<String, String> qrCheckForm(String token) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("need_logo", "false");
        form.put("need_short_url", "false");
        form.put("is_frontier", "true");
        form.put("token", token);
        form.put("is_new_login", "1");
        form.put("next", "https://api.qishui.com");
        return form;
    }

    private static String buildPcTrackUrl() {
        return buildPcUrl(PC_TRACK_ENDPOINT).toString();
    }

    private static String buildPcSearchUrl(String keyword, int cursor) {
        StringBuilder url = buildPcUrl(PC_SEARCH_PLAYLIST_ENDPOINT);
        appendPcSearchParams(url, keyword, cursor);
        return url.toString();
    }

    private static String buildPcMixedSearchUrl(String keyword, int cursor) {
        StringBuilder url = buildPcUrl(PC_SEARCH_MIXED_ENDPOINT);
        appendPcSearchParams(url, keyword, cursor);
        return url.toString();
    }

    private static String buildPcMePlaylistsUrl() {
        return buildPcUrl(PC_ME_PLAYLISTS_ENDPOINT).toString();
    }

    private static String buildPcMeCollectionUrl() {
        return buildPcUrl(PC_ME_COLLECTION_ENDPOINT).toString();
    }

    private static StringBuilder buildPcUrl(String endpoint) {
        long now = System.currentTimeMillis();
        String deviceId = String.valueOf(now);
        String iid = String.valueOf(now + 1L);
        StringBuilder url = new StringBuilder(endpoint).append('?');
        append(url, "aid", "386088");
        append(url, "app_name", "luna_pc");
        append(url, "region", "cn");
        append(url, "geo_region", "cn");
        append(url, "os_region", "cn");
        append(url, "sim_region", "");
        append(url, "device_id", deviceId);
        append(url, "cdid", "");
        append(url, "iid", iid);
        append(url, "version_name", "3.3.0");
        append(url, "version_code", "30030000");
        append(url, "channel", "official");
        append(url, "build_mode", "master");
        append(url, "network_carrier", "");
        append(url, "ac", "wifi");
        append(url, "tz_name", "Asia/Shanghai");
        append(url, "resolution", "");
        append(url, "device_platform", "windows");
        append(url, "device_type", "Windows");
        append(url, "os_version", "Windows 11");
        append(url, "fp", deviceId);
        return url;
    }

    private static void appendPcSearchParams(StringBuilder url, String keyword, int cursor) {
        append(url, "q", keyword);
        append(url, "cursor", String.valueOf(Math.max(0, cursor)));
        append(url, "search_id", "");
        append(url, "search_method", "input");
        append(url, "debug_params", "");
        append(url, "from_search_id", "");
        append(url, "search_scene", "");
    }

    private static String buildAccountUrl() {
        return ACCOUNT_ENDPOINT + "?aid=386088";
    }

    private static Map<String, String> pcHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        return headers;
    }

    private static void append(StringBuilder url, String key, String value) {
        if (url.charAt(url.length() - 1) != '?') url.append('&');
        url.append(urlEncode(key)).append('=').append(urlEncode(value));
    }
    private static JsonObject getJson(String url, String userAgent, String cookie,
                                      Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return getJsonResponse(url, userAgent, cookie, extraHeaders).body;
    }

    private static JsonResponse getJsonResponse(String url, String userAgent, String cookie,
                                                Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return execute(request(url, userAgent, cookie, extraHeaders).GET().build());
    }

    private static JsonObject postJson(String url, String body, String userAgent, String cookie,
                                       Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest request = request(url, userAgent, cookie, extraHeaders)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return execute(request).body;
    }

    private static JsonResponse postFormResponse(String url, Map<String, String> form,
                                                 String cookie, String userAgent)
            throws IOException, InterruptedException {
        HttpRequest request = request(url, userAgent, cookie, Collections.emptyMap())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                .build();
        return execute(request);
    }

    private static HttpRequest.Builder request(String url, String userAgent, String cookie,
                                               Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", userAgent);
        if (cookie != null && !cookie.trim().isEmpty()) builder.header("Cookie", cookie);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
        }
        return builder;
    }

    private static JsonResponse execute(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String text = new String(readLimited(response.body()), StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("汽水音乐 HTTP " + response.statusCode()
                    + (text.isEmpty() ? "" : ": " + text));
        }
        if (text.trim().isEmpty()) throw new IOException("汽水音乐返回了空响应");
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) throw new IOException("汽水音乐返回了非 JSON 对象");
        return new JsonResponse(parsed.getAsJsonObject(), cookieHeaderFromSetCookie(response));
    }

    private static String cookieHeaderFromSetCookie(HttpResponse<?> response) {
        StringBuilder header = new StringBuilder();
        for (String raw : response.headers().allValues("set-cookie")) {
            if (raw == null) continue;
            int separator = raw.indexOf(';');
            String pair = (separator < 0 ? raw : raw.substring(0, separator)).trim();
            if (pair.isEmpty()) continue;
            if (header.length() > 0) header.append("; ");
            header.append(pair);
        }
        return header.toString();
    }

    private static String extractSessionIdFromCookieHeader(String cookieHeader) {
        Matcher matcher = Pattern.compile("(?:^|[;,]\\s*)sessionid=([^;,\\s]+)",
                Pattern.CASE_INSENSITIVE).matcher(nonBlank(cookieHeader));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (encoded.length() > 0) encoded.append('&');
                encoded.append(urlEncode(entry.getKey())).append('=')
                        .append(urlEncode(entry.getValue()));
            }
        }
        return encoded.toString();
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BYTES) throw new IOException("汽水音乐响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
    private static String joinArtists(JsonArray artists) {
        if (artists == null) return "";
        List<String> names = new ArrayList<>();
        for (JsonElement element : artists) {
            if (element == null || !element.isJsonObject()) continue;
            String name = string(element.getAsJsonObject(), "name");
            if (!name.isEmpty()) names.add(name);
        }
        return String.join(" / ", names);
    }

    private static String coverUrl(JsonObject image) {
        if (image == null) return "";
        String uri = string(image, "uri");
        String templatePrefix = string(image, "template_prefix");
        if (!uri.isEmpty() && !templatePrefix.isEmpty()) {
            return COVER_ROOT + uri + "~" + templatePrefix + "-resize:960:960.png";
        }
        JsonArray urls = array(image, "urls");
        if (urls == null || urls.size() == 0) return "";
        String base = urls.get(0).isJsonNull() ? "" : urls.get(0).getAsString();
        if (!uri.isEmpty() && !base.contains(uri)) base += uri;
        return base;
    }

    private static String firstUrl(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) {
            String value = nonBlank(element.getAsString());
            return value.startsWith("http://") || value.startsWith("https://") ? value : "";
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String value = firstUrl(child);
                if (!value.isEmpty()) return value;
            }
            return "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String uri = string(object, "uri");
            JsonArray urls = array(object, "urls");
            if (urls != null && urls.size() > 0) {
                String base = firstUrl(urls.get(0));
                if (!base.isEmpty()) return uri.isEmpty() || base.contains(uri) ? base : base + uri;
            }
            for (String key : List.of("url_list", "urls", "url", "uri", "cover_url_list")) {
                String value = firstUrl(object.get(key));
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private static boolean truthy(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return false;
        try {
            return object.get(property).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static JsonObject object(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || !parent.get(property).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(property);
    }

    private static JsonArray array(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || !parent.get(property).isJsonArray()) return null;
        return parent.getAsJsonArray(property);
    }

    private static String string(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || parent.get(property).isJsonNull()) return "";
        try {
            return nonBlank(parent.get(property).getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || parent.get(property).isJsonNull()) return 0L;
        try {
            return parent.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(nonBlank(value), StandardCharsets.UTF_8);
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String describe(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null
                ? String.valueOf(throwable) : throwable.getMessage();
    }

    private static final class JsonResponse {
        final JsonObject body;
        final String cookieHeader;

        JsonResponse(JsonObject body, String cookieHeader) {
            this.body = body;
            this.cookieHeader = cookieHeader == null ? "" : cookieHeader;
        }
    }

    private static final class PendingQr {
        final String cookieHeader;
        final long expiresAt;
        final QrLoginState lastState;

        PendingQr(String cookieHeader, long expiresAt, QrLoginState lastState) {
            this.cookieHeader = cookieHeader == null ? "" : cookieHeader;
            this.expiresAt = expiresAt;
            this.lastState = lastState == null ? QrLoginState.WAITING : lastState;
        }
    }
}