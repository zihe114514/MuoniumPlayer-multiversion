package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.music.dto.PlayList;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全渠道歌单导入：粘贴任意一段分享文案，自动识别平台、自动解析歌单。
 *
 * 「链接 → 歌单元数据」解析，不需要登录、不选来源渠道。这里只在两处比上游收紧：
 * 识别出的平台必须落在本项目已接线的渠道里（YouTube 没有歌单对象，直接拒），
 * 以及抓到的元数据只落成引用——曲目点开时才由 {@link PlayList#queryMusics()}
 * 按平台懒加载，导入这一步永远不碰曲库。</p>
 *
 * <p>元数据抓取失败不阻断导入：标题兜底成「平台名 + ID」，曲目照样能点开拉取。
 * 上游接口对匿名请求的字段完整性没有承诺，为一行歌单名让整个链接失效得不偿失。</p>
 */
public final class PlaylistImportService {

    /** 识别结果：平台 + 归一化后的输入（分享文案里挑出的那条链接或裸 ID）。 */
    public static final class Detection {
        public final MusicPlatform platform;
        public final String cleaned;

        Detection(MusicPlatform platform, String cleaned) {
            this.platform = platform;
            this.cleaned = cleaned;
        }
    }

    private static final Pattern NETEASE_ID = Pattern.compile(
            "(?:playlist|album|song)[/?=&#]id=(\\d+)|(?:playlist|album|song)/(\\d+)");
    private static final Pattern QQ_PLAYLIST_PATH = Pattern.compile(
            "(?:playlist|songlist)/(\\d{5,})");
    private static final Pattern QQ_PLAYLIST_QUERY = Pattern.compile(
            "(?:disstid|tid|id)=(\\d{5,})");
    private static final Pattern KUGOU_SPECIAL = Pattern.compile("special/single/(\\d+)");
    private static final Pattern KUGOU_SONGLIST = Pattern.compile("songlist/(gcid_[A-Za-z0-9]+)");
    private static final Pattern BV_ID = Pattern.compile("(BV[0-9A-Za-z]{5,})");
    private static final Pattern AV_ID = Pattern.compile("av(\\d{2,})");
    private static final Pattern DIGITS = Pattern.compile("\\d{6,}");

    private static final String QQ_DETAIL_ENDPOINT =
            "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg";
    private static final String KUGOU_DETAIL_ENDPOINT =
            "http://mobilecdn.kugou.com/api/v3/special/song";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.0.0 Safari/537.36";
    private static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; PMT110) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(8_000))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private PlaylistImportService() {
    }

    /**
     * 解析一段任意来源的分享文案并归一成本地引用（不落盘）。
     *
     * <p>裸数字 ID 走长度启发式猜平台，猜错时按 {@link #retryPlatform} 换源再试一次：
     * 网易云 8-9 位、汽水 10-12 位、QQ 13+ 位的区间存在重叠，而一次失败重跑的代价
     * 只是多一个请求，让用户手打 ID 时踩中重叠区的概率不该由他来买单。</p>
     *
     * @throws IllegalArgumentException 识别不出平台、平台不支持导入、或链接里没有可用的歌单 ID
     */
    public static PlayList importPlaylist(String rawInput) {
        Detection detection = detect(rawInput);
        if (detection.platform == MusicPlatform.QISHUI) {
            // 汽水自己那条链已带重定向解析与兜底，不在外层再套一层重试。
            return QishuiMusicService.importPlaylist(detection.cleaned);
        }
        try {
            return resolve(detection.platform, detection.cleaned);
        } catch (IllegalArgumentException unsupported) {
            throw unsupported;
        } catch (Throwable first) {
            MusicPlatform second = retryPlatform(detection.platform, detection.cleaned);
            if (second == detection.platform) {
                throw new IllegalArgumentException("导入失败：" + message(first));
            }
            try {
                return resolve(second, detection.cleaned);
            } catch (Throwable secondFailure) {
                throw new IllegalArgumentException("导入失败：" + message(first)
                        + "（按" + second.getDisplayName() + "重试也失败了）");
            }
        }
    }

    private static PlayList resolve(MusicPlatform platform, String cleaned) {
        switch (platform) {
            case NETEASE:
                return importNetease(cleaned);
            case QQ:
                return importQQ(cleaned);
            case KUGOU:
                return importKugou(cleaned);
            case BILIBILI:
                return importBilibili(cleaned);
            case YOUTUBE:
                return importYouTube(cleaned);
            default:
                throw new IllegalArgumentException("该平台暂不支持导入歌单");
        }
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text.trim();
    }

    // ════════════════════════════ 平台识别 ════════════════════════════

    /**
     * 从分享文案里识别平台。
     *
     * <p>域名标记优先于一切：QQ 与网易云的裸数字 ID 长度区间重叠（都是 8-13 位），
     * 纯靠数字猜必错无疑，所以只有链接解析不出域名时才退到数字长度启发式。</p>
     */
    public static Detection detect(String rawInput) {
        String text = rawInput == null ? "" : rawInput.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("请先粘贴歌单分享链接");
        String link = firstLink(text);
        String haystack = (link.isEmpty() ? text : link).toLowerCase(Locale.ROOT);

        if (containsAny(haystack, "youtube.com", "youtu.be", "music.youtube")) {
            // 旧注释写的是「上游 music-lib 没有对等的匿名歌单接口」，这个前提已经不成立：
            // browse + VL 前缀匿名就能拿到 100 曲行 + 分页（2026-09-23 实测，
            String source = link.isEmpty() ? text : link;
            String listId = YouTubeMusicService.normalizePlaylistId(source);
            if (!listId.isEmpty()) return new Detection(MusicPlatform.YOUTUBE, listId);
            // 单首视频的链接也收：它进不了「歌单」，但可以变成「这首歌的电台」。
            String videoId = YouTubeMusicService.normalizeVideoId(source);
            if (!videoId.isEmpty()) {
                return new Detection(MusicPlatform.YOUTUBE, RADIO_PREFIX + videoId);
            }
            throw new IllegalArgumentException(
                    "这是 YouTube 的链接，但里面既没有歌单编号（?list=…）也没有视频编号（?v=…）");
        }
        if (containsAny(haystack, "bilibili.com", "b23.tv") || BV_ID.matcher(text).find()
                || AV_ID.matcher(haystack).find()) {
            return new Detection(MusicPlatform.BILIBILI, text);
        }
        if (containsAny(haystack, "kugou.com") || haystack.contains("gcid_")) {
            return new Detection(MusicPlatform.KUGOU, text);
        }
        if (containsAny(haystack, "y.qq.com", "music.taihe", "c.y.qq.com")) {
            return new Detection(MusicPlatform.QQ, text);
        }
        if (containsAny(haystack, "163.com", "music.163", "netease")) {
            return new Detection(MusicPlatform.NETEASE, text);
        }
        if (containsAny(haystack, "qishui", "music.douyin.com", "snssdk", "amemv")
                || text.contains("汽水")) {
            return new Detection(MusicPlatform.QISHUI, text);
        }
        if (link.startsWith("http")) {
            throw new IllegalArgumentException("识别不出这是哪个平台的歌单链接，换一条试试");
        }
        return new Detection(guessPlatformByDigits(text), text);
    }

    /** 裸数字兜底：长度分档 + 失败换源重试共用这套优先级。 */
    private static MusicPlatform guessPlatformByDigits(String text) {
        Matcher matcher = DIGITS.matcher(text.replaceAll("\\s", ""));
        String longest = "";
        while (matcher.find()) {
            if (matcher.group().length() > longest.length()) longest = matcher.group();
        }
        if (longest.isEmpty()) throw new IllegalArgumentException("没识别出歌单 ID，请粘贴完整分享链接");
        if (longest.length() >= 13) return MusicPlatform.QQ;      // QQ 数字 ID 是 13 位起
        if (longest.length() >= 10) return MusicPlatform.QISHUI;  // 汽水产品侧 ID 10-12 位
        return MusicPlatform.NETEASE;                             // 网易云 8-9 位居多
    }

    /** 从「复制打开APP」式文案里挑出 http(s) 链接；没有链接返回空串。 */
    private static String firstLink(String text) {
        int start = text.indexOf("http");
        if (start < 0) return "";
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                && text.charAt(end) != '】' && text.charAt(end) != '，' && text.charAt(end) != '。') {
            end++;
        }
        return text.substring(start, end);
    }

    private static boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    // ════════════════════════════ 网易云 ════════════════════════════

    private static PlayList importNetease(String text) {
        String id = firstGroup(text, NETEASE_ID);
        if (id == null) id = longestDigits(text);
        if (id == null) throw new IllegalArgumentException("没识别出网易云歌单 ID，请粘贴歌单链接或数字 ID");
        long numeric;
        try {
            numeric = Long.parseLong(id);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("网易云歌单 ID 不合法：" + id);
        }

        String title = "";
        String cover = "";
        int count = 0;
        try {
            // s=8 + n=1：只要歌单头部信息，不让 playlistTrackAll 把全量曲目详情拉一遍——
            // 导入这一步只存引用，曲目点开时自然会再拉。
            HashMap<String, Object> data = new HashMap<>();
            data.put("id", numeric);
            data.put("n", 1);
            data.put("s", 8);
            JsonObject response = RequestUtil.createRequest(
                    "/api/v6/playlist/detail", data, OptionsUtil.createOptions()).toJsonObject();
            JsonObject playlist = response == null ? null : response.getAsJsonObject("playlist");
            if (playlist != null) {
                title = text(playlist, "name");
                cover = text(playlist, "coverImgUrl");
                count = intOf(playlist, "trackCount", "playCount");
            }
        } catch (Throwable error) {
            System.err.println("[Music/Import] Netease metadata failed for " + id + ": " + error);
        }
        PlayList result = new PlayList(numeric, title.isEmpty() ? "网易云歌单 " + id : title,
                cover, Math.max(0, count), 0L, null, "", 0L);
        result.setPlatform(MusicPlatform.NETEASE);
        result.setPlatformPlaylistId(id);
        return result;
    }

    /**
     * 换源重试时的备选平台：网易云与汽水、QQ 的裸数字 ID 长度区间重叠，猜错时按长度就近换。
     *
     * <p>链接能解析出域名的场景里平台是确定的，猜错只发生在裸数字上，所以非数字兜底
     * （酷狗 / B 站）不换源 —— 把一条 B 站链接拿去问网易云只会得到另一个无意义的报错。</p>
     */
    static MusicPlatform retryPlatform(MusicPlatform tried, String text) {
        String digits = longestDigits(text == null ? "" : text.replaceAll("\\s", ""));
        int length = digits == null ? 0 : digits.length();
        if (tried == MusicPlatform.KUGOU || tried == MusicPlatform.BILIBILI) return tried;
        if (length >= 13) return tried == MusicPlatform.QQ ? MusicPlatform.QISHUI : MusicPlatform.QQ;
        if (length >= 10) return tried == MusicPlatform.QISHUI ? MusicPlatform.NETEASE : MusicPlatform.QISHUI;
        return tried == MusicPlatform.NETEASE ? MusicPlatform.QQ : MusicPlatform.NETEASE;
    }

    // ════════════════════════════ QQ 音乐 ════════════════════════════

    private static PlayList importQQ(String text) {
        String id = null;
        Matcher path = QQ_PLAYLIST_PATH.matcher(text.toLowerCase(Locale.ROOT));
        if (path.find()) id = path.group(1);
        if (id == null) {
            Matcher query = QQ_PLAYLIST_QUERY.matcher(text.toLowerCase(Locale.ROOT));
            while (query.find()) {
                // fragment 里 #/playlist/detail?id=xxx 与页面里其他 id 并存时，取第一个 playlist 语境命中。
                id = query.group(1);
                break;
            }
        }
        if (id == null && text.trim().matches("\\d{5,}")) id = text.trim();
        if (id == null) throw new IllegalArgumentException("没识别出 QQ 音乐歌单 ID，请粘贴歌单分享链接");

        String title = "";
        String cover = "";
        int count = 0;
        try {
            StringBuilder url = new StringBuilder(QQ_DETAIL_ENDPOINT).append("?ext=5&type=1&json=1")
                    .append("&disstid=").append(id)
                    .append("&g_tk=5381&loginUin=0&hostUin=0&notice=0&platform=yqq")
                    .append("&inCharset=utf-8&outCharset=utf-8");
            String body = get(url.toString(), DESKTOP_USER_AGENT, "https://y.qq.com/");
            JsonObject payload = asObject(stripJsonp(body));
            JsonArray cdlist = payload == null ? null : payload.getAsJsonArray("cdlist");
            if (cdlist != null && cdlist.size() > 0 && cdlist.get(0).isJsonObject()) {
                JsonObject cd = cdlist.get(0).getAsJsonObject();
                title = text(cd, "dissname");
                cover = text(cd, "logo");
                count = intOf(cd, "songnum");
            }
        } catch (Throwable error) {
            System.err.println("[Music/Import] QQ metadata failed for " + id + ": " + error);
        }
        PlayList result = new PlayList(stableLong("QQ:playlist:" + id),
                title.isEmpty() ? "QQ 音乐歌单 " + id : title, cover, Math.max(0, count),
                0L, null, "", 0L);
        result.setPlatform(MusicPlatform.QQ);
        result.setPlatformPlaylistId(id);
        return result;
    }

    // ════════════════════════════ 酷狗 ════════════════════════════

    private static PlayList importKugou(String text) {
        String normalized = text.trim();
        String id;
        String platformId;
        Matcher songlist = KUGOU_SONGLIST.matcher(normalized);
        Matcher special = KUGOU_SPECIAL.matcher(normalized);
        if (songlist.find()) {
            id = songlist.group(1);
            // 非数字的 gcid_ 是云端歌单主键，取曲目端点不同——前缀协议见 KuGouMusicService 类注释。
            platformId = "collection:" + id;
        } else if (special.find()) {
            id = special.group(1);
            platformId = id;
        } else if (normalized.matches("\\d{4,}")) {
            id = normalized;
            platformId = normalized;
        } else {
            throw new IllegalArgumentException("没识别出酷狗歌单，请粘贴 special/single 或 songlist 链接");
        }

        String title = "";
        String cover = "";
        int count = 0;
        try {
            StringBuilder url = new StringBuilder(KUGOU_DETAIL_ENDPOINT)
                    .append("?specialid=").append(URLEncoder.encode(id, StandardCharsets.UTF_8))
                    .append("&page=1&pagesize=10&version=9108&area_code=1");
            JsonObject payload = asObject(get(url.toString(), MOBILE_USER_AGENT, null));
            JsonObject data = payload == null ? null : asObject(payload.get("data"));
            if (data != null) {
                title = text(data, "specialname");
                cover = text(data, "imgurl").replace("{size}", "300");
                count = intOf(data, "total", "songcount");
            }
        } catch (Throwable error) {
            System.err.println("[Music/Import] KuGou metadata failed for " + id + ": " + error);
        }
        PlayList result = new PlayList(stableLong("kugou:playlist:" + platformId),
                title.isEmpty() ? "酷狗歌单 " + id : title, cover, Math.max(0, count),
                0L, null, "", 0L);
        result.setPlatform(MusicPlatform.KUGOU);
        result.setPlatformPlaylistId(platformId);
        return result;
    }

    // ════════════════════════════ 哔哩哔哩 ════════════════════════════

    private static PlayList importBilibili(String text) {
        List<PlayList> playlists = BilibiliPlaylistService.playlistsOfVideo(text);
        if (playlists.isEmpty()) {
            throw new IllegalArgumentException("这条 B 站稿件没有可导入的歌单形态（无合集、且不是多 P）");
        }
        // playlistsOfVideo 已按「合集优先、其次多 P 全集」排好序，取第一条即最优形态。
        return playlists.get(0);
    }

    /**
     * YouTube 歌单 / 电台。
     *
     * <p>两种形态共用一条渠道：{@code PL…} 这类是歌单，{@code radio:<videoId>} 是
     * 「以某首为种子的电台」，前缀编码在 platformPlaylistId 里、由
     * {@link PlayList#queryMusics()} 分派——与汽水电台同一套做法。</p>
     *
     * <p>导入这一步只存引用：电台只要一个能显示的标题和封面，就单独问一次 {@code player}
     * 那一跳（很小），不去拉整个电台面板（实测 10 MB）。</p>
     */
    private static PlayList importYouTube(String cleaned) {
        String text = cleaned == null ? "" : cleaned.trim();
        if (text.startsWith(RADIO_PREFIX)) {
            String videoId = text.substring(RADIO_PREFIX.length()).trim();
            if (videoId.isEmpty()) throw new IllegalArgumentException("电台种子视频 id 是空的");
            YouTubeMusicService.Track seed = YouTubeMusicService.getVideo(videoId);
            String title = seed == null || seed.title.isEmpty()
                    ? "YouTube 电台 · " + videoId : "「" + seed.title + "」的电台";
            String cover = seed == null ? "" : nonBlankText(seed.coverUrl);
            PlayList radio = new PlayList(stableLong("youtube:radio:" + videoId),
                    title, cover, 0, 0L, null, "以这一首为种子的自动电台", 0L);
            radio.setPlatform(MusicPlatform.YOUTUBE);
            radio.setPlatformPlaylistId(text);
            return radio;
        }

        String id = YouTubeMusicService.normalizePlaylistId(text);
        if (id.isEmpty()) throw new IllegalArgumentException("没识别出 YouTube 歌单编号");
        String title = "";
        String cover = "";
        int count = 0;
        try {
            YouTubeMusicService.Playlist head = YouTubeMusicService.getPlaylist(id, 1);
            title = nonBlankText(head.name);
            cover = nonBlankText(head.coverUrl);
            count = Math.max(0, head.declaredCount);
        } catch (Throwable error) {
            System.err.println("[Music/Import] YouTube metadata failed for " + id + ": " + error);
        }
        PlayList result = new PlayList(stableLong("youtube:playlist:" + id),
                title.isEmpty() ? "YouTube 歌单 " + id : title, cover, count, 0L, null, "", 0L);
        result.setPlatform(MusicPlatform.YOUTUBE);
        result.setPlatformPlaylistId(id);
        return result;
    }

    /** YouTube 电台的 platformPlaylistId 前缀，与汽水那份 {@code radio:} 同形。 */
    private static final String RADIO_PREFIX = "radio:";

    /** 服务层回来的字符串可能为 null，落进 PlayList 前统一成空串。 */
    private static String nonBlankText(String value) {
        return value == null ? "" : value.trim();
    }

    // ════════════════════════════ HTTP 与 JSON 小工具 ════════════════════════════

    private static String get(String url, String userAgent, String referer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(10_000))
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", userAgent)
                .GET();
        if (referer != null) builder.header("Referer", referer);
        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** JSONP（如 {@code jsonpgz({...});}）归一成 JSON 文本。 */
    private static String stripJsonp(String body) {
        String text = body == null ? "" : body.trim();
        int start = text.indexOf('(');
        int end = text.lastIndexOf(')');
        if (start >= 0 && end > start) return text.substring(start + 1, end);
        return text;
    }

    private static JsonObject asObject(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) return element.getAsJsonObject();
        // QQ 的 JSONP 内层与部分端点会把 JSON 再包一层字符串，这里统一展开一次。
        try {
            return asObject(element.getAsString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject asObject(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String text(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static int intOf(JsonObject object, String... keys) {
        if (object == null) return 0;
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                try {
                    return object.get(key).getAsInt();
                } catch (Throwable ignored) {
                    // 上游有时把数量写成字符串，跳过这枚键继续试下一枚。
                }
            }
        }
        return 0;
    }

    private static String firstGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String value = matcher.group(group);
                if (value != null && !value.isEmpty()) return value;
            }
        }
        return null;
    }

    private static String longestDigits(String text) {
        Matcher matcher = DIGITS.matcher(text);
        String longest = "";
        while (matcher.find()) {
            if (matcher.group().length() > longest.length()) longest = matcher.group();
        }
        return longest.isEmpty() ? null : longest;
    }

    /** 与 QishuiMusicService / KuGouMusicService 同一套 FNV-1a：字符串主键折叠成稳定 long。 */
    static long stableLong(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}
