package com.muoniumplayer.core.music.source;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 网易云业务接口（跨平台 weapi 通道）。自 Cadence（原 music-api-0.1.1.jar，原 music-api jar 包）
 * 手翻，2026-11 吸收进仓。端点、参数、cookie 语义与原实现逐一对照。
 *
 * <p>登录态是一整条 cookie 串（{@code MUSIC_U=...} 形态），由宿主注入；
 * 扫码登录成功后从 {@code Set-Cookie} 捕获新 cookie 回写宿主。</p>
 */
public final class MuNeteaseApi {
    private static final Gson GSON = new Gson();
    private static final Pattern BASE64_NICK = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");

    private final MuHttp http = new MuHttp();
    private String cookie = "";

    public String getCookie() { return cookie; }

    public void setCookie(String value) { cookie = value == null ? "" : value; }

    public void clearLogin() { cookie = ""; }

    // ── 搜索 ───────────────────────────────────────────────────────────────

    public List<Track> search(String keyword, int limit, int offset) {
        Map<String, String> params = linkedStr("s", keyword, "type", "1",
                "limit", String.valueOf(limit), "offset", String.valueOf(offset), "total", "true");
        JsonObject data = weapi("/weapi/cloudsearch/get/web", params);
        JsonObject result = JsonExt.objOrNull(data, "result");
        JsonArray songs = result == null ? null : JsonExt.arrOrNull(result, "songs");
        List<Track> out = new ArrayList<>();
        if (songs == null) return out;
        for (JsonElement element : songs) {
            try {
                out.add(parseTrack(element.getAsJsonObject()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ── 取流 ───────────────────────────────────────────────────────────────

    public SongUrl getSongUrl(Track track, AudioQuality quality) {
        String level;
        switch (quality) {
            case STANDARD: level = "standard"; break;
            case HIGH: level = "exhigh"; break;
            case LOSSLESS: level = "lossless"; break;
            default: level = "lossless";
        }
        Map<String, String> params = linkedStr("ids", "[" + track.getId() + "]",
                "level", level, "encodeType", "flac");
        JsonObject data = weapi("/weapi/song/enhance/player/url/v1", params);
        JsonArray arr = JsonExt.arrOrNull(data, "data");
        if (arr != null && arr.size() > 0 && arr.get(0).isJsonObject()) {
            JsonObject item = arr.get(0).getAsJsonObject();
            JsonElement urlEl = item.get("url");
            String url = urlEl != null && !urlEl.isJsonNull() ? urlEl.getAsString() : null;
            if (url != null && !url.isEmpty()) {
                JsonElement typeEl = item.get("type");
                String type = typeEl != null && !typeEl.isJsonNull() ? typeEl.getAsString() : "";
                long size = longOr(item, "size", 0L);
                JsonObject trial = JsonExt.objOrNull(item, "freeTrialInfo");
                if (trial != null) {
                    long start = longOr(trial, "start", 0L);
                    long end = longOr(trial, "end", 0L);
                    return new SongUrl(url, quality, type, size, true, start * 1000L, end * 1000L, null);
                }
                return new SongUrl(url, quality, type, size, false, 0L, 0L, null);
            }
        }
        return SongUrl.unavailable(quality, "网易云：无可用音频");
    }

    // ── 歌词 ───────────────────────────────────────────────────────────────

    public Lyric getLyric(Track track) {
        Map<String, String> params = linkedStr("id", track.getId(), "lv", "-1",
                "kv", "-1", "tv", "-1", "yv", "-1");
        JsonObject data = weapi("/weapi/song/lyric", params);
        String lrc = "";
        JsonObject lrcObj = JsonExt.objOrNull(data, "lrc");
        if (lrcObj != null && lrcObj.get("lyric") != null && !lrcObj.get("lyric").isJsonNull()) {
            lrc = lrcObj.get("lyric").getAsString();
        }
        String tlyric = null;
        JsonObject tObj = JsonExt.objOrNull(data, "tlyric");
        if (tObj != null && tObj.get("lyric") != null && !tObj.get("lyric").isJsonNull()) {
            String value = tObj.get("lyric").getAsString();
            if (!value.isBlank()) tlyric = value;
        }
        String yrc = null;
        JsonObject yObj = JsonExt.objOrNull(data, "yrc");
        if (yObj != null && yObj.get("lyric") != null && !yObj.get("lyric").isJsonNull()) {
            String value = yObj.get("lyric").getAsString();
            if (!value.isBlank()) yrc = value;
        }
        return new Lyric(lrc, tlyric, yrc, LyricParser.INSTANCE.parse(lrc, tlyric, yrc));
    }

    // ── 扫码登录 ───────────────────────────────────────────────────────────

    public QrCode createQrCode() {
        JsonObject keyData = weapi("/weapi/login/qrcode/unikey",
                linkedStr("type", "1"));
        JsonElement unikeyEl = keyData.get("unikey");
        if (unikeyEl != null && !unikeyEl.isJsonNull()) {
            String unikey = unikeyEl.getAsString();
            return new QrCode(unikey, "https://music.163.com/login?codekey=" + unikey);
        }
        throw new MusicApiException("网易云：获取 unikey 失败", null);
    }

    public QrLoginState checkQrCode(QrCode qr) {
        Map<String, String> params = linkedStr("key", qr.getKey(), "type", "1");
        MuHttp.Response resp = http.postForm("https://music.163.com/weapi/login/qrcode/client/login",
                weapiForm(params), baseHeaders(), buildCookies(cookie));
        int code = -1;
        try {
            JsonObject json = JsonParser.parseString(resp.getBody()).getAsJsonObject();
            code = json.get("code") != null ? json.get("code").getAsInt() : -1;
        } catch (Exception ignored) {
        }
        if (code == 803) {
            captureCookies(resp.getSetCookies());
            return QrLoginState.CONFIRMED;
        }
        if (code == 802) return QrLoginState.SCANNED;
        if (code == 801) return QrLoginState.WAITING;
        if (code == 800) return QrLoginState.EXPIRED;
        return QrLoginState.ERROR;
    }

    // ── 账号 / 歌单 ────────────────────────────────────────────────────────

    public Long getLoginUid() {
        JsonObject data = weapi("/weapi/w/nuser/account/get", new LinkedHashMap<>());
        JsonObject account = JsonExt.objOrNull(data, "account");
        if (account != null && account.get("id") != null && !account.get("id").isJsonNull()) {
            return account.get("id").getAsLong();
        }
        return null;
    }

    public List<Track> getDailyRecommendSongs() {
        JsonObject data = weapi("/weapi/v3/discovery/recommend/songs", new LinkedHashMap<>());
        JsonObject inner = JsonExt.objOrNull(data, "data");
        JsonArray songs = inner == null ? null : JsonExt.arrOrNull(inner, "dailySongs");
        List<Track> out = new ArrayList<>();
        if (songs == null) return out;
        for (JsonElement element : songs) {
            try {
                out.add(parseTrack(element.getAsJsonObject()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public List<PlaylistBrief> getUserPlaylists(long uid, int limit, int offset) {
        Map<String, String> params = linkedStr("uid", String.valueOf(uid),
                "limit", String.valueOf(limit), "offset", String.valueOf(offset),
                "includeVideo", "true");
        JsonObject data = weapi("/weapi/user/playlist", params);
        JsonArray list = JsonExt.arrOrNull(data, "playlist");
        List<PlaylistBrief> out = new ArrayList<>();
        if (list == null) return out;
        for (JsonElement element : list) {
            try {
                JsonObject o = element.getAsJsonObject();
                JsonElement idEl = o.get("id");
                if (idEl == null || idEl.isJsonNull()) continue;
                String id = idEl.getAsString();
                String name = textOr(o, "name", "");
                String cover = null;
                JsonElement coverEl = o.get("coverImgUrl");
                if (coverEl != null && !coverEl.isJsonNull()) cover = coverEl.getAsString();
                int count = intOr(o, "trackCount", 0);
                out.add(new PlaylistBrief(id, name, cover, count));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public List<Track> getPlaylistTracks(String playlistId, int limit) {
        Map<String, String> params = linkedStr("id", playlistId,
                "n", String.valueOf(limit), "s", "8");
        JsonObject data = weapi("/weapi/v6/playlist/detail", params);
        JsonObject playlist = JsonExt.objOrNull(data, "playlist");
        JsonArray tracks = playlist == null ? null : JsonExt.arrOrNull(playlist, "tracks");
        List<Track> out = new ArrayList<>();
        if (tracks == null) return out;
        for (JsonElement element : tracks) {
            try {
                out.add(parseTrack(element.getAsJsonObject()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 通用 weapi 调用：返回响应体（供宿主扩展端点）。 */
    public RawResult call(String path, Map<String, String> params, String cookieOverride) {
        MuHttp.Response resp = postWeapi(path, params, buildCookies(cookieOverride != null ? cookieOverride : cookie));
        StringBuilder cookies = new StringBuilder();
        for (Map.Entry<String, String> entry : resp.getSetCookies().entrySet()) {
            if (cookies.length() > 0) cookies.append("; ");
            cookies.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return new RawResult(resp.getBody(), cookies.toString());
    }

    /** 通用 weapi 调用，返回已解析的 JSON（供宿主扩展端点）。 */
    public JsonObject callJson(String path, Map<String, String> params) {
        return weapi(path, params);
    }

    /** call() 的结果：响应体 + 本次响应回下的 cookie 串。 */
    public static final class RawResult {
        private final String body;
        private final String cookie;

        public RawResult(String body, String cookie) {
            this.body = body;
            this.cookie = cookie;
        }

        public String getBody() { return body; }

        public String getCookie() { return cookie; }
    }

    // ── weapi 内核 ─────────────────────────────────────────────────────────

    private JsonObject weapi(String path, Map<String, String> params) {
        MuHttp.Response resp = postWeapi(path, params, buildCookies(cookie));
        try {
            return JsonParser.parseString(resp.getBody()).getAsJsonObject();
        } catch (Exception e) {
            throw new MusicApiException("网易云：响应解析失败 (" + path + ')', e);
        }
    }

    private MuHttp.Response postWeapi(String path, Map<String, String> params,
                                           Map<String, String> cookies) {
        return http.postForm("https://music.163.com" + path, weapiForm(params),
                baseHeaders(), cookies);
    }

    private Map<String, String> weapiForm(Map<String, String> params) {
        String json = GSON.toJson(params);
        MuWeapi.Encrypted enc = MuWeapi.weapi(json);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("params", enc.getParams());
        form.put("encSecKey", enc.getEncSecKey());
        return form;
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://music.163.com");
        headers.put("Origin", "https://music.163.com");
        return headers;
    }

    private Map<String, String> buildCookies(String cookieString) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("os", "pc");
        base.put("appver", "8.9.70");
        if (cookieString != null) {
            for (String part : cookieString.split(";", -1)) {
                int eq = part.indexOf('=');
                String name = (eq < 0 ? part : part.substring(0, eq)).trim();
                String value = eq < 0 ? "" : part.substring(eq + 1).trim();
                if (!name.isEmpty()) base.put(name, value);
            }
        }
        return base;
    }

    private void captureCookies(Map<String, String> set) {
        Map<String, String> merged = buildCookies(cookie);
        merged.remove("os");
        merged.remove("appver");
        if (set != null) merged.putAll(set);
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (joined.length() > 0) joined.append("; ");
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        cookie = joined.toString();
    }

    private static Track parseTrack(JsonObject o) {
        JsonArray artistsArr = JsonExt.arrOrNull(o, "ar");
        if (artistsArr == null) artistsArr = JsonExt.arrOrNull(o, "artists");
        StringBuilder singers = new StringBuilder();
        if (artistsArr != null) {
            for (JsonElement element : artistsArr) {
                JsonElement name = element.getAsJsonObject().get("name");
                if (name != null && !name.isJsonNull()) {
                    if (singers.length() > 0) singers.append(" / ");
                    singers.append(name.getAsString());
                }
            }
        }
        JsonObject albumObj = JsonExt.objOrNull(o, "al");
        if (albumObj == null) albumObj = JsonExt.objOrNull(o, "album");
        int fee = intOr(o, "fee", 0);
        String id = o.get("id") != null && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
        String name = textOr(o, "name", "");
        String albumName = albumObj != null ? textOr(albumObj, "name", "") : "";
        JsonElement dtEl = o.get("dt") != null ? o.get("dt") : o.get("duration");
        long duration = dtEl != null && !dtEl.isJsonNull() ? dtEl.getAsLong() : 0L;
        String coverUrl = albumObj != null && albumObj.get("picUrl") != null && !albumObj.get("picUrl").isJsonNull()
                ? albumObj.get("picUrl").getAsString() : null;
        return new Track(MusicSource.NETEASE, id, null, name, singers.toString(), albumName,
                duration, coverUrl, fee != 0);
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    private static String textOr(JsonObject parent, String key, String fallback) {
        JsonElement element = parent.get(key);
        try {
            return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
        } catch (UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static int intOr(JsonObject parent, String key, int fallback) {
        JsonElement element = parent.get(key);
        try {
            return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static long longOr(JsonObject parent, String key, long fallback) {
        JsonElement element = parent.get(key);
        try {
            return element != null && !element.isJsonNull() ? element.getAsLong() : fallback;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static Map<String, String> linkedStr(String... pairs) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
