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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/**
 * QQ 音乐业务接口。自 Cadence（原 music-api-0.1.1.jar，原 music-api jar 包）手翻，
 * 2026-11 吸收进仓。端点、参数、cookie 语义与原实现逐一对照。
 *
 * <p>登录态：{@code musicid + musicKey} 由宿主注入（{@link #setMusicid}/{@link #setMusicKey}），
 * QQ 扫码走 {@code ptlogin2 → graph.qq.com OAuth → QQConnectLogin.QQLogin} 换取；
 * 凭证以 cookie（uin/qm_keyst）与 comm（qq/authst）双通道随请求发送。</p>
 */
public final class MuQQApi {
    private static final Gson GSON = new Gson();

    private static final Pattern PTUI_CB = Pattern.compile("ptuiCB\\((.*?)\\)");
    private static final Pattern PTUI_ARG = Pattern.compile("'((?:\\\\.|[^'])*)'");
    private static final Pattern SIGX_RE = Pattern.compile("[?&]ptsigx=([^&]+)");
    private static final Pattern UIN_RE = Pattern.compile("[?&]uin=([^&]+)");
    private static final Pattern CODE_RE = Pattern.compile("[?&]code=([^&]+)");

    private final MuHttp http = new MuHttp();
    private final String guid = String.valueOf(1_000_000_000L + new Random().nextLong(9_000_000_000L));
    private final LinkedHashMap<String, String> loginJar = new LinkedHashMap<>();
    private final AtomicLong nonceCounter = new AtomicLong();

    private String musicid = "";
    private String musicKey = "";

    public String getMusicid() { return musicid; }

    public void setMusicid(String value) { musicid = value == null ? "" : value; }

    public String getMusicKey() { return musicKey; }

    public void setMusicKey(String value) { musicKey = value == null ? "" : value; }

    public boolean getLoggedIn() { return !musicid.isEmpty() && !musicKey.isEmpty(); }

    public void clearLogin() {
        musicid = "";
        musicKey = "";
        loginJar.clear();
    }

    // ── 凭证续期（music.login.LoginServer/Login，与登录同端点） ─────────────

    /**
     * 刷新结果。成功时携带新 musickey 与全套续期材料；失败带 reason。
     */
    public static final class RefreshResult {
        public final boolean ok;
        public final String musicid;
        public final String musicKey;
        public final String refreshKey;
        public final String refreshToken;
        public final String openid;
        public final String unionid;
        public final long expiredAt;
        public final String reason;

        private RefreshResult(boolean ok, JsonObject data, String reason) {
            this.ok = ok;
            this.musicid = ok ? textOrEmpty(data, "musicid") : "";
            this.musicKey = ok ? textOrEmpty(data, "musickey") : "";
            this.refreshKey = ok ? textOrEmpty(data, "refresh_key") : "";
            this.refreshToken = ok ? textOrEmpty(data, "refresh_token") : "";
            this.openid = ok ? textOrEmpty(data, "openid") : "";
            this.unionid = ok ? textOrEmpty(data, "unionid") : "";
            this.expiredAt = ok ? longOr(data, "expired_at", 0L) : 0L;
            this.reason = reason;
        }

        static RefreshResult success(JsonObject data) {
            return new RefreshResult(true, data, null);
        }

        static RefreshResult failure(String reason) {
            return new RefreshResult(false, null, reason);
        }
    }

    /**
     * 用续期材料换新凭证。param 形状按 login_type 分支（规格对齐 QQMusicApi
     * modules/login.py refresh_credential）：
     * <ul>
     *   <li>type 1（QQ 扫码）：openid/refresh_token/str_musicid/musickey/unionid/refresh_key/loginMode=2</li>
     *   <li>type 2（微信）：openid/access_token/refresh_token/expired_in/musicid/musickey/refresh_key/loginMode=2</li>
     * </ul>
     * 过期码 1000/104401/104400 走业务错误（refresh 本身拒绝），不重试。
     */
    public RefreshResult refreshCredential(String musicidValue, String musicKeyValue,
                                           String refreshKey, String refreshToken,
                                           String openid, String unionid, int loginType,
                                           long expiredAt) {
        if (musicidValue == null || musicidValue.isEmpty() || musicKeyValue == null || musicKeyValue.isEmpty()) {
            return RefreshResult.failure("缺少 musicid/musickey");
        }
        Map<String, Object> param = new LinkedHashMap<>();
        if (loginType == 1) {
            param.put("openid", openid == null ? "" : openid);
            param.put("refresh_token", refreshToken == null ? "" : refreshToken);
            param.put("str_musicid", musicidValue);
            param.put("musickey", musicKeyValue);
            param.put("unionid", unionid == null ? "" : unionid);
            param.put("refresh_key", refreshKey == null ? "" : refreshKey);
        } else if (loginType == 2) {
            param.put("openid", openid == null ? "" : openid);
            param.put("access_token", "");
            param.put("refresh_token", refreshToken == null ? "" : refreshToken);
            param.put("expired_in", expiredAt);
            param.put("musicid", musicidValue);
            param.put("musickey", musicKeyValue);
            param.put("refresh_key", refreshKey == null ? "" : refreshKey);
        } else {
            // loginType 未知（旧档案只有 id/key）：按 QQ 通道形状试一把，str_musicid 兜底。
            param.put("openid", openid == null ? "" : openid);
            param.put("refresh_token", refreshToken == null ? "" : refreshToken);
            param.put("str_musicid", musicidValue);
            param.put("musicid", musicidValue);
            param.put("musickey", musicKeyValue);
            param.put("unionid", unionid == null ? "" : unionid);
            param.put("refresh_key", refreshKey == null ? "" : refreshKey);
        }
        param.put("loginMode", 2);

        Map<String, Object> comm = buildComm(new LinkedHashMap<>());
        comm.put("tmeLoginType", loginType == 0 ? 1 : loginType);

        JsonObject body = new JsonObject();
        body.add("comm", GSON.toJsonTree(comm));
        JsonObject request = new JsonObject();
        request.addProperty("module", "music.login.LoginServer");
        request.addProperty("method", "Login");
        request.add("param", GSON.toJsonTree(param));
        body.add("req", request);
        String payload = GSON.toJson(body);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("_", loginNonce());
        query.put("sign", MuSign.sign(payload));
        MuHttp.Response resp = http.postJson("https://u.y.qq.com/cgi-bin/musics.fcg", payload,
                query, linkedStr("Referer", "https://y.qq.com/"), credentialCookies());
        try {
            JsonObject json = JsonParser.parseString(resp.getBody()).getAsJsonObject();
            JsonObject req = json.getAsJsonObject("req");
            if (req == null) return RefreshResult.failure("响应缺少 req");
            int code = req.get("code") != null ? req.get("code").getAsInt() : -1;
            if (code != 0) {
                return RefreshResult.failure("刷新被拒 code=" + code);
            }
            JsonObject data = JsonExt.objOrNull(req, "data");
            if (data == null || textOrEmpty(data, "musickey").isEmpty()) {
                return RefreshResult.failure("刷新响应缺少新凭证");
            }
            return RefreshResult.success(data);
        } catch (Exception e) {
            return RefreshResult.failure("刷新响应解析失败: " + e.getMessage());
        }
    }

    // ── 搜索 / 歌单 / 榜单 ─────────────────────────────────────────────────

    public List<Track> search(String keyword, int limit, int page) {
        Map<String, Object> param = linked(
                "num_per_page", limit, "page_num", page,
                "search_type", 0, "query", keyword, "grp", 1);
        Map<String, Object> overrideComm = linked("ct", "6", "cv", "80600", "tmeAppID", "qqmusic");
        JsonObject data = musicu("music.search.SearchCgiService", "DoSearchForQQMusicDesktop",
                param, false, null, "music.search.SearchCgiService.DoSearchForQQMusicDesktop",
                overrideComm);
        JsonObject body = JsonExt.objOrNull(data, "body");
        JsonObject song = body == null ? null : JsonExt.objOrNull(body, "song");
        JsonArray list = song == null ? null : JsonExt.arrOrNull(song, "list");
        List<Track> out = new ArrayList<>();
        if (list == null) return out;
        MuLog.info("QQ search '" + keyword + "': got " + list.size() + " 首");
        for (JsonElement element : list) {
            try {
                Track track = parseTrack(element.getAsJsonObject());
                if (track != null) out.add(track);
            } catch (Exception ignored) {
                // 单行解析失败跳过，不拖垮整个结果
            }
        }
        return out;
    }

    public List<MusicPlaylist> getRecommendPlaylists(int size) {
        Map<String, Object> param = linked("From", 0, "Size", size);
        Map<String, Object> overrideComm = linked("ct", "24", "cv", "0");
        JsonObject data = musicu("music.playlist.PlaylistSquare", "GetRecommendFeed",
                param, false, null, "req", overrideComm);
        JsonArray list = JsonExt.arrOrNull(data, "List");
        List<MusicPlaylist> out = new ArrayList<>();
        if (list == null) return out;
        for (JsonElement element : list) {
            try {
                JsonObject item = element.getAsJsonObject();
                JsonObject playlist = JsonExt.objOrNull(item, "Playlist");
                JsonObject basic = playlist == null ? null : JsonExt.objOrNull(playlist, "basic");
                if (basic == null) continue;
                JsonObject cover = JsonExt.objOrNull(basic, "cover");
                JsonElement tid = basic.get("tid");
                if (tid == null || tid.isJsonNull()) continue;
                String id = tid.getAsString();
                String title = textOrEmpty(basic, "title");
                JsonElement coverEl = cover != null && cover.get("medium_url") != null && !cover.get("medium_url").isJsonNull()
                        ? cover.get("medium_url")
                        : cover != null ? cover.get("small_url") : null;
                String coverUrl = coverEl != null && !coverEl.isJsonNull() ? coverEl.getAsString() : null;
                int songCnt = intOr(basic, "song_cnt", 0);
                out.add(new MusicPlaylist(MusicSource.QQ, id, title, coverUrl, songCnt, ""));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public List<Track> getPlaylistTracks(String dissid, int num) {
        long disstid;
        try {
            disstid = Long.parseLong(dissid);
        } catch (NumberFormatException e) {
            disstid = 0L;
        }
        Map<String, Object> param = linked("disstid", disstid, "onlysong", 0,
                "song_begin", 0, "song_num", num);
        Map<String, Object> overrideComm = linked("ct", "24", "cv", "0");
        JsonObject data = musicu("music.srfDissInfo.DissInfo", "CgiGetDiss",
                param, false, null, "req", overrideComm);
        JsonArray list = JsonExt.arrOrNull(data, "songlist");
        List<Track> out = new ArrayList<>();
        if (list == null) return out;
        for (JsonElement element : list) {
            try {
                out.add(parseTrack(element.getAsJsonObject()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public List<Track> getToplist(int topId, int num) {
        Map<String, Object> param = linked("topId", topId, "offset", 0, "num", num);
        JsonObject data = musicu("music.musicToplist.Toplist", "GetDetail",
                param, false, null, null, null);
        JsonArray list = JsonExt.arrOrNull(data, "songInfoList");
        List<Track> out = new ArrayList<>();
        if (list == null) return out;
        MuLog.info("QQ toplist " + topId + ": " + list.size() + " 首");
        for (JsonElement element : list) {
            try {
                out.add(parseTrack(element.getAsJsonObject()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ── 取流 ───────────────────────────────────────────────────────────────

    /** 文件档位候选（与原实现一致的顺序即降级顺序）。 */
    private static final class QCand {
        final String sizeKey;
        final String prefix;
        final String ext;
        final AudioQuality q;

        QCand(String sizeKey, String prefix, String ext, AudioQuality q) {
            this.sizeKey = sizeKey;
            this.prefix = prefix;
            this.ext = ext;
            this.q = q;
        }
    }

    /**
     * 取流候选表。2026-11 补齐 OGG 三档（O801=SQ 640 / O800=HQ 320 / O600=HQ 192）——
     * QQ 已大量停发 MP3 320，缺 OGG 时 HIGH 档会直接掉 128。OGG 走普通 UrlGetVkey
     * 明文通道（规格对齐 QQMusicApi SongFileType 表），不碰加密 GetEVkey/mflac 链。
     */
    private static final QCand[] ALL_CANDS = {
            new QCand("size_flac", "F000", "flac", AudioQuality.LOSSLESS),
            new QCand("size_new_5", "O801", "ogg", AudioQuality.LOSSLESS),
            new QCand("size_new_3", "O800", "ogg", AudioQuality.HIGH),
            new QCand("size_192ogg", "O600", "ogg", AudioQuality.HIGH),
            new QCand("size_320mp3", "M800", "mp3", AudioQuality.HIGH),
            new QCand("size_128mp3", "M500", "mp3", AudioQuality.STANDARD),
            new QCand("size_96aac", "C400", "m4a", AudioQuality.STANDARD),
    };

    public SongUrl getSongUrl(Track track, AudioQuality quality) {
        String mid = track.getMid();
        if (mid == null || mid.isEmpty()) {
            throw new MusicApiException("QQ：缺少 songmid", null);
        }

        // 单曲详情：拿 file 表（media_mid / 各档 size）
        Map<String, String> dquery = linkedStr("songmid", mid, "platform", "yqq", "format", "json");
        MuHttp.Response detail = http.get("https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg",
                dquery, linkedStr("Referer", "https://y.qq.com/"), credentialCookies());
        JsonObject song = null;
        try {
            JsonObject root = JsonParser.parseString(detail.getBody()).getAsJsonObject();
            JsonArray data = JsonExt.arrOrNull(root, "data");
            if (data != null && data.size() > 0 && data.get(0).isJsonObject()) {
                song = data.get(0).getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        if (song == null) {
            return SongUrl.unavailable(quality, "获取歌曲信息失败", -1);
        }

        JsonObject file = JsonExt.objOrNull(song, "file");
        String mediaMid = mid;
        if (file != null && file.get("media_mid") != null && !file.get("media_mid").isJsonNull()) {
            String value = file.get("media_mid").getAsString();
            if (value != null && !value.isEmpty()) mediaMid = value;
        }
        int songType = intOr(song, "type", 0);

        // 「从请求档位开始向后」的语义对齐原 jar 的 subList：起点是表中**第一个与请求档
        // 同级**的候选（原 jar switch 映射 LOSSLESS→0 / HIGH→1 / STANDARD→2，即同档首条），
        // 之后**全部**参与（含更低档的兜底）。
        // 2026-11-02 探针实证：非 VIP 曲 file 表只有 M500/192ogg/M800，请求 LOSSLESS
        // 时若把低于 LOSSLESS 的候选全部 continue 掉，会误报「无可用音频」——
        // 正确行为是 F000/O801 落空后继续试 M800/M500/C400，取到第一个 purl 非空的档。
        // 注意不能用「第一个 ordinal >= 请求档」当起点：那会让 STANDARD 请求从 F000
        // 开始（标准档用户被偷偷升档，非 VIP 直接 104003）。同档首条才是对齐语义。
        int startIdx = ALL_CANDS.length;
        for (int i = 0; i < ALL_CANDS.length; i++) {
            if (ALL_CANDS[i].q == quality) {
                startIdx = i;
                break;
            }
        }
        if (startIdx == ALL_CANDS.length) {
            // 请求档在表中无同档候选（防御）：按 >= 档位首条兜底。
            for (int i = 0; i < ALL_CANDS.length; i++) {
                if (ALL_CANDS[i].q.ordinal() >= quality.ordinal()) {
                    startIdx = i;
                    break;
                }
            }
        }
        JsonArray sizeNew = file != null ? JsonExt.arrOrNull(file, "size_new") : null;
        List<QCand> cands = new ArrayList<>();
        for (int i = startIdx; i < ALL_CANDS.length; i++) {
            QCand cand = ALL_CANDS[i];
            if (file == null) continue;
            long size;
            if (cand.sizeKey.startsWith("size_new_")) {
                int idx = Integer.parseInt(cand.sizeKey.substring("size_new_".length()));
                JsonElement el = sizeNew != null && idx < sizeNew.size() ? sizeNew.get(idx) : null;
                size = el != null && !el.isJsonNull() ? el.getAsLong() : 0L;
            } else {
                JsonElement sizeEl = file.get(cand.sizeKey);
                size = sizeEl != null && !sizeEl.isJsonNull() ? sizeEl.getAsLong() : 0L;
            }
            if (size > 0L) cands.add(cand);
        }
        // file 表整表为空的兜底：无信息可用时按请求档位给一个纯降级序列（vkey 层自己会拒）。
        if (cands.isEmpty() && file != null) {
            for (int i = Math.max(startIdx, ALL_CANDS.length - 2); i < ALL_CANDS.length; i++) {
                cands.add(ALL_CANDS[i]);
            }
        }
        if (cands.isEmpty()) {
            return SongUrl.unavailable(quality, "无可用音频", -1);
        }

        List<String> songmids = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        List<Integer> songtypes = new ArrayList<>();
        for (QCand cand : cands) {
            songmids.add(mid);
            filenames.add(cand.prefix + mediaMid + '.' + cand.ext);
            songtypes.add(songType);
        }
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("guid", guid);
        param.put("songmid", songmids);
        param.put("filename", filenames);
        param.put("songtype", songtypes);
        param.put("uin", musicid.isEmpty() ? "0" : musicid);
        param.put("loginflag", 1);
        param.put("platform", "20");

        JsonObject data = vkeyRequest(param);
        JsonArray infos = JsonExt.arrOrNull(data, "midurlinfo");
        String sip = "https://dl.stream.qqmusic.qq.com/";
        JsonArray sips = JsonExt.arrOrNull(data, "sip");
        if (sips != null && sips.size() > 0 && !sips.get(0).isJsonNull()) {
            String value = sips.get(0).getAsString();
            if (value != null) sip = value;
        }
        if (sip.startsWith("http://")) sip = "https://" + sip.substring("http://".length());

        for (int i = 0; i < cands.size(); i++) {
            String purl = null;
            int stepResult = -1;
            if (infos != null && i < infos.size() && infos.get(i).isJsonObject()) {
                JsonObject info = infos.get(i).getAsJsonObject();
                JsonElement purlEl = info.get("purl");
                if (purlEl != null && !purlEl.isJsonNull()) purl = purlEl.getAsString();
                JsonElement resEl = info.get("result");
                if (resEl != null && !resEl.isJsonNull()) stepResult = resEl.getAsInt();
            }
            if (purl != null && !purl.isEmpty()) {
                QCand cand = cands.get(i);
                MuLog.info("QQ vkey " + mid + ": " + cand.prefix + " ok");
                return new SongUrl(sip + purl, cand.q, cand.ext, 0L, false, 0L, 0L, null,
                        stepResult, cand.prefix);
            }
        }

        int result = -1;
        boolean needBuy = false;
        if (infos != null && infos.size() > 0 && infos.get(0).isJsonObject()) {
            JsonObject info0 = infos.get(0).getAsJsonObject();
            JsonElement resultEl = info0.get("result");
            if (resultEl != null && !resultEl.isJsonNull()) result = resultEl.getAsInt();
            JsonElement buyEl = info0.get("pneedbuy");
            if (buyEl != null && !buyEl.isJsonNull()) needBuy = buyEl.getAsInt() == 1;
        }
        // result 码分级（QQMusicApi download.md 码表）：104003 无权限 / 104004 VKey 失败 /
        // 104013 设备受限；其余按付费语义归因。文案供播放失败归因直读。
        String reason;
        switch (result) {
            case SongUrl.RESULT_NO_PERMISSION:
                reason = "需要绿钻会员（或无版权）";
                break;
            case SongUrl.RESULT_VKEY_FAILED:
                reason = "VKey 获取失败（可重试）";
                break;
            case SongUrl.RESULT_DEVICE_LIMIT:
                reason = "登录设备已达上限，请在 QQ 音乐 App 内移除设备";
                break;
            default:
                reason = !needBuy && !track.getVip() ? "暂时无法播放" : "需要 QQ 音乐会员/付费";
        }
        MuLog.info("QQ vkey " + mid + ": 无 purl, result=" + result + " → " + reason);
        return SongUrl.unavailable(quality, reason, result);
    }

    // ── 歌词 ───────────────────────────────────────────────────────────────

    public Lyric getLyric(Track track) {
        String mid = track.getMid();
        if (mid == null || mid.isEmpty()) {
            throw new MusicApiException("QQ：缺少 songmid", null);
        }
        Map<String, String> query = linkedStr("songmid", mid, "format", "json",
                "nobase64", "1", "g_tk", "5381");
        MuHttp.Response resp = http.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                query, linkedStr("Referer", "https://y.qq.com/"), null);
        String body = resp.getBody().trim();
        String jsonText = body.startsWith("{") ? body
                : beforeLast(after(body, '('), ')');
        JsonObject json;
        try {
            json = JsonParser.parseString(jsonText).getAsJsonObject();
        } catch (Exception e) {
            json = null;
        }
        if (json == null) {
            return new Lyric("", null, null, new ArrayList<>());
        }
        // 请求带 nobase64=1：响应是明文 LRC，不走 Base64 解码（实测 2026-11-02，
        // 探针拿到 [ti:晴天] 明文头）。decodeBase64 只留给未带该参数的调用形态。
        String lrc = textField(json, "lyric");
        if (lrc == null) lrc = decodeBase64Field(json, "lyric");
        if (lrc == null) lrc = "";
        String trans = textField(json, "trans");
        if (trans == null) trans = decodeBase64Field(json, "trans");
        return new Lyric(lrc, trans, null, LyricParser.INSTANCE.parse(lrc, trans, null));
    }

    /** 直接读明文字段；缺失/空返回 null。 */
    private static String textField(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) return null;
        String raw = element.getAsString();
        return (raw == null || raw.isBlank()) ? null : raw;
    }

    /** Base64 形态兜底解码（未带 nobase64 参数的响应）；失败返回 null。 */
    private static String decodeBase64Field(JsonObject json, String field) {
        String raw = textField(json, field);
        if (raw == null) return null;
        try {
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }


    // ── 用户信息 ───────────────────────────────────────────────────────────

    public QQUserInfo getUserInfo() {
        if (!getLoggedIn()) return null;
        Map<String, String> query = linkedStr("ct", "20", "cv", "4747474",
                "cid", "205360838", "userid", musicid);
        MuHttp.Response resp = http.get("https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",
                query, linkedStr("Referer", "https://y.qq.com/"), credentialCookies());
        String body = resp.getBody();
        String text = (!body.startsWith("callback(") && !body.contains(")("))
                ? body : beforeLast(after(body, '('), ')');
        JsonObject creator = null;
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonObject data = JsonExt.objOrNull(json, "data");
            creator = data == null ? null : JsonExt.objOrNull(data, "creator");
        } catch (Exception ignored) {
        }
        String nick = creator != null && creator.get("nick") != null && !creator.get("nick").isJsonNull()
                ? creator.get("nick").getAsString() : null;
        if (nick != null && nick.matches("^[A-Za-z0-9+/]+={0,2}$") && nick.length() % 4 == 0) {
            // 昵称疑似 Base64：解码后全为可打印字符才采用
            try {
                String decoded = new String(Base64.getDecoder().decode(nick), StandardCharsets.UTF_8);
                boolean printable = !decoded.isBlank();
                if (printable) {
                    for (int i = 0; i < decoded.length(); i++) {
                        char c = decoded.charAt(i);
                        if (c < ' ' || c == 0x7F) {
                            printable = false;
                            break;
                        }
                    }
                }
                if (printable) nick = decoded;
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (nick == null || nick.isEmpty()) nick = "QQ用户" + musicid;
        String headpic = creator != null && creator.get("headpic") != null && !creator.get("headpic").isJsonNull()
                ? creator.get("headpic").getAsString() : null;
        String avatar = (headpic != null && !headpic.isBlank())
                ? headpic : "https://q1.qlogo.cn/g?b=qq&nk=" + musicid + "&s=100";
        return new QQUserInfo(musicid, nick, avatar);
    }

    // ── QQ 扫码登录 ────────────────────────────────────────────────────────

    public QrCode createQrCode() {
        loginJar.clear();
        Map<String, String> query = linkedStr("appid", "716027609", "e", "2", "l", "M",
                "s", "3", "d", "72", "v", "4", "t", "0." + loginNonce(),
                "daid", "383", "pt_3rd_aid", "100497308");
        http.get("https://ssl.ptlogin2.qq.com/ptqrshow", query,
                linkedStr("Referer", "https://xui.ptlogin2.qq.com/"), loginJar);
        String qrsig = loginJar.get("qrsig");
        if (qrsig == null) {
            throw new MusicApiException("QQ：获取 qrsig 失败", null);
        }
        return new QrCode(qrsig, "");
    }

    public QrLoginState checkQrCode(QrCode qr) {
        Map<String, String> args = linkedStr(
                "u1", "https://graph.qq.com/oauth2.0/login_jump",
                "ptqrtoken", String.valueOf(MuHash33.hash33(qr.getKey(), 0)),
                "ptredirect", "0", "h", "1", "t", "1", "g", "1", "from_ui", "1",
                "ptlang", "2052", "action", "0-0-" + loginNonce(),
                "js_ver", "20102616", "js_type", "1", "login_sig", "",
                "pt_uistyle", "40", "aid", "716027609", "daid", "383",
                "pt_3rd_aid", "100497308", "has_onekey", "1");
        MuHttp.Response resp = http.get("https://ssl.ptlogin2.qq.com/ptqrlogin", args,
                linkedStr("Referer", "https://xui.ptlogin2.qq.com/"), loginJar);
        Matcher cb = PTUI_CB.matcher(resp.getBody());
        if (!cb.find()) return QrLoginState.ERROR;
        String sigx = cb.group(1);

        List<String> argsList = new ArrayList<>();
        Matcher argMatcher = PTUI_ARG.matcher(sigx);
        while (argMatcher.find()) {
            argsList.add(argMatcher.group(1).replace("\\'", "'").replace("\\\\", "\\"));
        }
        if (argsList.isEmpty()) return QrLoginState.ERROR;
        String state = argsList.get(0);
        switch (state) {
            case "0": {
                String url = argsList.size() > 2 ? argsList.get(2) : null;
                if (url == null) return QrLoginState.ERROR;
                Matcher sigxMatcher = SIGX_RE.matcher(url);
                if (!sigxMatcher.find()) return QrLoginState.ERROR;
                String sigxValue = sigxMatcher.group(1);
                Matcher uinMatcher = UIN_RE.matcher(url);
                if (!uinMatcher.find()) return QrLoginState.ERROR;
                String uin = uinMatcher.group(1);
                try {
                    authorizeQq(uin, sigxValue);
                    return QrLoginState.CONFIRMED;
                } catch (Exception e) {
                    MuLog.error("QQ 授权失败", e);
                    return QrLoginState.ERROR;
                }
            }
            case "65":
                return QrLoginState.EXPIRED;
            case "66":
                return QrLoginState.WAITING;
            case "67":
                return QrLoginState.SCANNED;
            default:
                return QrLoginState.ERROR;
        }
    }

    private void authorizeQq(String uin, String sigx) {
        Map<String, String> checkSig = linkedStr(
                "uin", uin, "pttype", "1", "service", "ptqrlogin", "nodirect", "0",
                "ptsigx", sigx, "s_url", "https://graph.qq.com/oauth2.0/login_jump",
                "ptlang", "2052", "ptredirect", "100", "aid", "716027609", "daid", "383",
                "j_later", "0", "low_login_hour", "0", "regmaster", "0",
                "pt_login_type", "3", "pt_aid", "0", "pt_aaid", "16", "pt_light", "0",
                "pt_3rd_aid", "100497308");
        http.get("https://ssl.ptlogin2.graph.qq.com/check_sig", checkSig,
                linkedStr("Referer", "https://xui.ptlogin2.qq.com/"), loginJar);
        String pSkey = loginJar.get("p_skey");
        if (pSkey == null) {
            throw new MusicApiException("QQ：获取 p_skey 失败", null);
        }
        Map<String, String> form = linkedStr(
                "response_type", "code", "client_id", "100497308",
                "redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
                "scope", "get_user_info,get_app_friends", "state", "state", "switch", "",
                "from_ptlogin", "1", "src", "1", "update_auth", "1", "openapi", "1010_1030",
                "g_tk", String.valueOf(MuHash33.hash33(pSkey, 5381)),
                "auth_time", loginNonce(), "ui", UUID.randomUUID().toString());
        MuHttp.Response authorize = http.postForm("https://graph.qq.com/oauth2.0/authorize",
                form, new LinkedHashMap<>(), loginJar);
        String code = followForCode(authorize);
        Map<String, Object> param = linked("code", code);
        Map<String, Object> extraComm = linked("tmeLoginType", 2);
        JsonObject data = musicu("QQConnectLogin.LoginServer", "QQLogin",
                param, false, extraComm, null, null);
        musicid = textOrEmpty(data, "musicid");
        musicKey = textOrEmpty(data, "musickey");
        if (!getLoggedIn()) {
            throw new MusicApiException("QQ：登录换取凭证失败", null);
        }
        // 续期材料随登录捕获（2026-11 补齐）：QQLogin 响应若带 refresh 字段则暂存，
        // 宿主 persistLogin 时读走落盘；不带则保持空串（续期链路对空材料有保护）。
        lastLoginRefreshKey = textOrEmpty(data, "refresh_key");
        lastLoginRefreshToken = textOrEmpty(data, "refresh_token");
        lastLoginOpenid = textOrEmpty(data, "openid");
        lastLoginUnionid = textOrEmpty(data, "unionid");
        lastLoginExpiredAt = longOr(data, "expired_at", 0L);
        lastLoginType = 1; // QQ 扫码通道
    }

    // 最近一次 QQLogin 成功响应里的续期材料（宿主 persistLogin 消费后无残留意义）。
    private String lastLoginRefreshKey = "";
    private String lastLoginRefreshToken = "";
    private String lastLoginOpenid = "";
    private String lastLoginUnionid = "";
    private long lastLoginExpiredAt = 0L;
    private int lastLoginType = 0;

    public String consumeLastLoginRefreshKey() {
        String v = lastLoginRefreshKey;
        lastLoginRefreshKey = "";
        return v;
    }

    public String consumeLastLoginRefreshToken() {
        String v = lastLoginRefreshToken;
        lastLoginRefreshToken = "";
        return v;
    }

    public String consumeLastLoginOpenid() {
        String v = lastLoginOpenid;
        lastLoginOpenid = "";
        return v;
    }

    public String consumeLastLoginUnionid() {
        String v = lastLoginUnionid;
        lastLoginUnionid = "";
        return v;
    }

    public long consumeLastLoginExpiredAt() {
        long v = lastLoginExpiredAt;
        lastLoginExpiredAt = 0L;
        return v;
    }

    public int consumeLastLoginType() {
        int v = lastLoginType;
        lastLoginType = 0;
        return v;
    }

    private String followForCode(MuHttp.Response start) {
        String loc = start.header("Location");
        String body = start.getBody();
        MuLog.info("QQ authorize resp: status=" + start.getStatus() + ", firstLoc='"
                + abbreviate(loc == null ? "" : loc, 220) + "'");
        String code = extractCode(loc);
        if (code != null) return code;
        for (int hop = 0; loc != null && hop < 6; hop++) {
            String url = loc.startsWith("http") ? loc : "https://graph.qq.com" + loc;
            MuHttp.Response r = http.get(url, new LinkedHashMap<>(),
                    linkedStr("Referer", "https://graph.qq.com/"), loginJar);
            body = r.getBody();
            loc = r.header("Location");
            code = extractCode(loc);
            if (code != null) return code;
            code = extractCode(body);
            if (code != null) return code;
        }
        Matcher title = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE)
                .matcher(body);
        String detail = title.find() ? title.group(1) : "";
        throw new MusicApiException("QQ：授权跳转未取得 code" + (detail.isEmpty() ? "" : "（" + detail + "）"), null);
    }

    // ── musicu RPC 内核 ────────────────────────────────────────────────────

    private JsonObject musicu(String module, String method, Map<String, Object> param,
                              boolean sign, Map<String, Object> extraComm, String reqKey,
                              Map<String, Object> overrideComm) {
        Map<String, Object> comm = overrideComm != null ? overrideComm : buildComm(extraComm);
        if (reqKey == null) reqKey = "req";
        JsonObject body = new JsonObject();
        body.add("comm", GSON.toJsonTree(comm));
        JsonObject request = new JsonObject();
        request.addProperty("module", module);
        request.addProperty("method", method);
        request.add("param", GSON.toJsonTree(param));
        body.add(reqKey, request);
        String payload = GSON.toJson(body);
        Map<String, String> query = new LinkedHashMap<>();
        String url;
        if (sign) {
            query.put("_", loginNonce());
            query.put("sign", MuSign.sign(payload));
            url = "https://u.y.qq.com/cgi-bin/musics.fcg";
        } else {
            url = "https://u.y.qq.com/cgi-bin/musicu.fcg";
        }
        MuHttp.Response resp = http.postJson(url, payload, query,
                linkedStr("Referer", "https://y.qq.com/"), credentialCookies());
        JsonObject json;
        try {
            json = JsonParser.parseString(resp.getBody()).getAsJsonObject();
        } catch (Exception e) {
            throw new MusicApiException("QQ：响应解析失败 (" + module + '.' + method + ')', e);
        }
        if (json == null) {
            throw new MusicApiException("QQ：响应解析失败 (" + module + '.' + method + ')', null);
        }
        JsonObject reqObj = json.getAsJsonObject(reqKey);
        if (reqObj == null) {
            throw new MusicApiException("QQ：响应缺少 " + reqKey, null);
        }
        int code = reqObj.get("code") != null ? reqObj.get("code").getAsInt() : -1;
        if (code != 0) {
            throw new MusicApiException("QQ：接口返回 code=" + code + " (" + module + '.' + method + ')', null);
        }
        JsonObject data = JsonExt.objOrNull(reqObj, "data");
        return data != null ? data : new JsonObject();
    }

    private JsonObject vkeyRequest(Map<String, Object> param) {
        MuLog.info("QQ vkey req: cookies=" + credentialCookies().keySet()
                + ", keystPrefix='" + abbreviate(musicKey, 6) + "' guid=" + guid);
        JsonObject body = new JsonObject();
        JsonObject request = new JsonObject();
        request.addProperty("module", "vkey.GetVkeyServer");
        request.addProperty("method", "CgiGetVkey");
        request.add("param", GSON.toJsonTree(param));
        body.add("req_0", request);
        Map<String, String> query = linkedStr("format", "json", "platform", "yqq.json",
                "needNewCode", "0", "data", GSON.toJson(body));
        MuHttp.Response resp = http.get("https://u.y.qq.com/cgi-bin/musicu.fcg", query,
                linkedStr("Referer", "https://y.qq.com/"), credentialCookies());
        try {
            JsonObject json = JsonParser.parseString(resp.getBody()).getAsJsonObject();
            JsonObject req0 = JsonExt.objOrNull(json, "req_0");
            JsonObject data = req0 == null ? null : JsonExt.objOrNull(req0, "data");
            return data != null ? data : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private Map<String, Object> buildComm(Map<String, Object> extra) {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("ct", "11");
        comm.put("cv", "12080008");
        comm.put("v", "12080008");
        comm.put("tmeAppID", "qqmusic");
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("uin", musicid.isEmpty() ? "0" : musicid);
        if (getLoggedIn()) {
            comm.put("qq", musicid);
            comm.put("authst", musicKey);
        }
        if (extra != null) comm.putAll(extra);
        return comm;
    }

    private Map<String, String> credentialCookies() {
        if (!getLoggedIn()) return new LinkedHashMap<>();
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("uin", musicid);
        cookies.put("qqmusic_uin", musicid);
        cookies.put("qm_keyst", musicKey);
        cookies.put("qqmusic_key", musicKey);
        return cookies;
    }

    private String loginNonce() {
        return String.valueOf(System.currentTimeMillis() + nonceCounter.getAndIncrement());
    }

    private static Track parseTrack(JsonObject o) {
        JsonElement midEl = o.get("mid") != null ? o.get("mid") : o.get("songmid");
        String mid = midEl != null && !midEl.isJsonNull() ? midEl.getAsString() : null;
        JsonElement idEl = o.get("id") != null ? o.get("id") : o.get("songid");
        String id = idEl != null && !idEl.isJsonNull() && idEl.getAsString() != null
                ? idEl.getAsString() : (mid != null ? mid : "");

        String singers = "";
        JsonArray singerArr = JsonExt.arrOrNull(o, "singer");
        if (singerArr != null) {
            List<String> names = new ArrayList<>();
            for (JsonElement element : singerArr) {
                JsonElement name = element.getAsJsonObject().get("name");
                if (name != null && !name.isJsonNull()) names.add(name.getAsString());
            }
            singers = String.join(" / ", names);
        }

        JsonObject album = JsonExt.objOrNull(o, "album");
        String albumName = "";
        if (album != null && album.get("name") != null && !album.get("name").isJsonNull()) {
            albumName = album.get("name").getAsString();
        } else {
            JsonElement alt = o.get("albumname");
            if (alt != null && !alt.isJsonNull()) albumName = alt.getAsString();
        }

        String albumPmid = null;
        if (album != null) {
            JsonElement pmid = album.get("pmid");
            if (pmid != null && !pmid.isJsonNull()) albumPmid = pmid.getAsString();
            if (albumPmid == null) {
                JsonElement albumMid = album.get("mid");
                if (albumMid != null && !albumMid.isJsonNull()) albumPmid = albumMid.getAsString();
            }
        }
        if (albumPmid == null) {
            JsonElement albumMid = o.get("albummid");
            if (albumMid != null && !albumMid.isJsonNull()) albumPmid = albumMid.getAsString();
        }

        long interval = longOr(o, "interval", 0L);
        boolean vip = false;
        JsonObject pay = JsonExt.objOrNull(o, "pay");
        if (pay != null && pay.get("pay_play") != null && !pay.get("pay_play").isJsonNull()) {
            vip = pay.get("pay_play").getAsInt() == 1;
        }

        JsonElement nameEl = o.get("name") != null ? o.get("name")
                : o.get("title") != null ? o.get("title") : o.get("songname");
        String name = nameEl != null && !nameEl.isJsonNull() ? nameEl.getAsString() : "";

        String coverUrl = albumPmid != null
                ? "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumPmid + ".jpg" : null;
        return new Track(MusicSource.QQ, id, mid, name, singers, albumName,
                interval * 1000L, coverUrl, vip);
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    private static String extractCode(String text) {
        if (text == null) return null;
        Matcher m = CODE_RE.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String after(String s, char c) {
        int idx = s.indexOf(c);
        return idx < 0 ? s : s.substring(idx + 1);
    }

    private static String beforeLast(String s, char c) {
        int idx = s.lastIndexOf(c);
        return idx < 0 ? s : s.substring(0, idx);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String textOrEmpty(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && !element.isJsonNull() ? element.getAsString() : "";
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

    private static Map<String, Object> linked(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, String> linkedStr(String... pairs) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
