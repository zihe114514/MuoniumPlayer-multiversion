package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置的 Bilibili 源适配器。
 *
 * 现行的 WBI 查询签名，因此<b>浏览与播放这条主链路无需登录即可工作</b>。</p>
 *
 * <p>登录态是可选的加分项：扫码登录（{@link BilibiliLogin}）拿到 {@code SESSDATA} 之后，
 * 本类会为需要账号的接口附上 {@code Cookie} 头，从而解锁三件事 —— 大会员的更高 AAC 码率、
 * 下载路径的 FLAC/杜比音轨、{@link BilibiliSubtitleService} 的字幕歌词。
 * 未登录时这三处一律安静降级，不影响匿名浏览。</p>
 */
public final class BilibiliMusicService {

    static final String API_ROOT = "https://api.bilibili.com/x/web-interface";
    static final String PLAYER_API_ROOT = "https://api.bilibili.com/x/player";
    /** 收藏夹相关接口挂在 v3 下，与稿件接口不同根。 */
    static final String FAV_API_ROOT = "https://api.bilibili.com/x/v3/fav";
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
    static final String REFERER = "https://www.bilibili.com/";
    private static final Pattern BVID_PATTERN = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern PAGE_PATTERN = Pattern.compile("(?:[?&]p=)([1-9][0-9]*)");
    private static final Pattern AV_PATTERN = Pattern.compile("(?i)(?:/|\\b)av([0-9]+)");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 25_000;
    private static final long WBI_KEY_TTL_MILLIS = 12L * 60L * 60L * 1000L;

    /**
     * DASH 音频流 id。B 站用 id 而不是 bandwidth 表达档位，且这两个 id 只出现在
     * {@code fnval} 打开了 4048 时：30251 是 Hi-Res FLAC，30250 是杜比全景声。
     */
    private static final int STREAM_ID_HIFI_FLAC = 30251;
    private static final int STREAM_ID_DOLBY_ATMOS = 30250;
    /** {@code fnval} 位掩码：80 是普通 DASH 音频，4048 额外打开 FLAC/杜比。 */
    private static final int FNVAL_STANDARD = 80;
    private static final int FNVAL_HIRES = 4048;
    private static final int QN_MAXIMUM = 127;

    /** AAC 大档位在 playurl 里报的 codecs 值；播放链路只认这一种。 */
    private static final String CODECS_AAC_LC = "mp4a.40.2";

    /** B 站公开的 WBI mixin-key 排列。它在密钥轮换期间保持稳定。 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile WbiKey cachedWbiKey;

    /**
     * 大会员判定结果。用 {@link Boolean} 而不是 {@code boolean}：{@code null} 表示「没查过」，
     * 而查询失败必须保持 {@code null}，否则一次网络抖动就会把这个账号永久钉成「非会员」，
     * 高质量档位再也回不来。
     */
    private static volatile Boolean vipAccountCache;
    /** 缓存归属的账号；换账号（含登出）时必须作废。 */
    private static volatile String vipAccountCacheOwner;

    private BilibiliMusicService() {
    }

    public static final class Track {
        public final String bvid;
        public final int page;
        public final String title;
        public final String artist;
        public final long durationMillis;
        public final String coverUrl;
        public final String aid;
        public final String cid;

        Track(String bvid, int page, String title, String artist, long durationMillis,
              String coverUrl, String aid, String cid) {
            this.bvid = bvid;
            this.page = page;
            this.title = title;
            this.artist = artist;
            this.durationMillis = durationMillis;
            this.coverUrl = coverUrl;
            this.aid = aid;
            this.cid = cid;
        }
    }

    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final long bitrate;

        ResolveResult(String url, String format, long bitrate) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
        }
    }

    public static List<Track> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) return List.of();
        int effectiveLimit = Math.max(1, Math.min(50, limit));
        String keyword = query.trim();

        try {
            DirectVideo direct = parseDirectVideo(keyword);
            if (direct != null) {
                Track track = loadVideo(direct.bvid, direct.page);
                return track == null ? List.of() : List.of(track);
            }
            return searchVideos(keyword, effectiveLimit);
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Search failed for " + keyword + ": "
                    + describe(throwable));
            return List.of();
        }
    }

    public static ResolveResult getSongUrl(String sourceId, Quality requestedQuality) {
        return resolve(sourceId, requestedQuality, false);
    }

    /**
     * 为<b>下载</b>解析音轨：允许返回 FLAC/杜比。
     *
     * <p>播放与下载分成两条是有原因的。{@link AacAudioDecoder} 是围绕 {@code fnval=80} 的
     * AAC-LC 分片 MP4 写的，只解析 {@code tfhd}/{@code trun} 的一个子集；把 30251(FLAC)
     * 或 30250(杜比) 塞进播放链路会直接解不出来。下载落盘后交给外部工具或后续解码器处理，
     * 所以只有这一条路径开放高解析档位。</p>
     */
    public static ResolveResult getDownloadUrl(String sourceId) {
        return resolve(sourceId, Quality.LOSSLESS, true);
    }

    private static ResolveResult resolve(String sourceId, Quality requestedQuality, boolean allowHires) {
        DirectVideo direct = parseSourceId(sourceId);
        if (direct == null) return null;

        try {
            Track track = loadVideo(direct.bvid, direct.page);
            if (track == null) return null;

            boolean hires = allowHires && queryVipAccount();
            StringBuilder url = new StringBuilder(PLAYER_API_ROOT)
                    .append("/playurl?fnval=").append(hires ? FNVAL_HIRES : FNVAL_STANDARD)
                    .append("&avid=").append(urlEncode(track.aid))
                    .append("&cid=").append(urlEncode(track.cid));
            if (hires) url.append("&qn=").append(QN_MAXIMUM);
            JsonObject response = getJson(url.toString(), hires);
            requireSuccessful(response, "playurl");
            JsonObject data = object(response, "data");
            JsonObject dash = object(data, "dash");

            if (hires) {
                ResolveResult hiresStream = pickHiresStream(dash, data);
                if (hiresStream != null) return hiresStream;
            }

            // 走到这里就是播放链路（或下载链路没能拿到高解析流）：只在 AAC-LC 里挑。
            JsonArray streams = array(dash, "audio");
            if (streams == null || streams.isJsonNull() || streams.size() == 0) {
                // 未登录请求付费档位时 B 站可能只回 durl；这条兜底保持和改造前一致的行为。
                ResolveResult durl = pickDurl(data);
                if (durl != null) return durl;
                throw new IllegalStateException("Bilibili response contains no DASH audio stream");
            }

            boolean preferHigherQuality = requestedQuality != Quality.STANDARD;
            JsonObject selected = null;
            long selectedBandwidth = preferHigherQuality ? Long.MIN_VALUE : Long.MAX_VALUE;
            JsonObject fallback = null;
            for (JsonElement element : streams) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject stream = element.getAsJsonObject();
                String streamUrl = firstNonBlank(string(stream, "baseUrl"), string(stream, "base_url"));
                if (streamUrl.isEmpty()) continue;
                if (fallback == null) fallback = stream;

                // 所有 HE-AAC 变体。优先选 AAC-LC，再退回第一个流。
                String codecs = string(stream, "codecs");
                long bandwidth = longValue(stream, "bandwidth");
                if (!CODECS_AAC_LC.equals(codecs)) continue;
                if (preferHigherQuality ? bandwidth >= selectedBandwidth : bandwidth <= selectedBandwidth) {
                    selected = stream;
                    selectedBandwidth = bandwidth;
                }
            }

            JsonObject chosen = selected == null ? fallback : selected;
            String urlValue = firstNonBlank(string(chosen, "baseUrl"), string(chosen, "base_url"));
            if (urlValue.isEmpty()) throw new IllegalStateException("Bilibili audio stream has no URL");
            long bandwidth = longValue(chosen, "bandwidth");
            // Bilibili 纯音频 DASH 是分片 MP4。返回 "mp4" 可让现有的
            // 字节级检测器和 AacAudioDecoder 继续负责真实容器。
            return new ResolveResult(urlValue, "mp4", bandwidth);
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Song URL failed for " + nonBlank(sourceId) + ": "
                    + describe(throwable));
            return null;
        }
    }

    /**
     * 按上游验证过的优先级挑一条高解析流：Hi-Res FLAC &gt; 杜比全景声 &gt; 普通 DASH 里 id 最大者。
     *
     * <p>只在下载路径调用。返回 {@code null} 表示这次响应里没有高解析档，
     * 调用方应当继续走 AAC 逻辑。</p>
     */
    private static ResolveResult pickHiresStream(JsonObject dash, JsonObject data) {
        if (dash != null) {
            JsonObject flac = object(object(dash, "flac"), "audio");
            if (flac != null && longValue(flac, "id") == STREAM_ID_HIFI_FLAC) {
                String urlValue = firstNonBlank(string(flac, "baseUrl"), string(flac, "base_url"));
                if (!urlValue.isEmpty()) return new ResolveResult(urlValue, "mp4", longValue(flac, "bandwidth"));
            }
            JsonArray dolby = array(dash, "dolby");
            if (dolby != null) {
                for (JsonElement element : dolby) {
                    if (element == null || !element.isJsonObject()) continue;
                    JsonObject stream = element.getAsJsonObject();
                    if (longValue(stream, "id") != STREAM_ID_DOLBY_ATMOS) continue;
                    String urlValue = firstNonBlank(string(stream, "baseUrl"), string(stream, "base_url"));
                    if (!urlValue.isEmpty()) return new ResolveResult(urlValue, "mp4", longValue(stream, "bandwidth"));
                }
            }
            // 普通 DASH 数组里 id 表达档位（30280=192k &gt; 30232=132k &gt; 30211=64k），
            // 比 bandwidth 稳定，因为同一档在不同 CDN 上的带宽会抖动。
            JsonArray streams = array(dash, "audio");
            JsonObject best = null;
            long bestId = -1L;
            if (streams != null) {
                for (JsonElement element : streams) {
                    if (element == null || !element.isJsonObject()) continue;
                    JsonObject stream = element.getAsJsonObject();
                    String urlValue = firstNonBlank(string(stream, "baseUrl"), string(stream, "base_url"));
                    long id = longValue(stream, "id");
                    if (urlValue.isEmpty() || id <= bestId) continue;
                    best = stream;
                    bestId = id;
                }
            }
            if (best != null && bestId > 0L) {
                return new ResolveResult(firstNonBlank(string(best, "baseUrl"), string(best, "base_url")),
                        "mp4", longValue(best, "bandwidth"));
            }
        }
        return pickDurl(data);
    }

    /** 非 DASH 的老式整段流（{@code durl}）兜底。 */
    private static ResolveResult pickDurl(JsonObject data) {
        JsonArray segments = array(data, "durl");
        if (segments == null || segments.isJsonNull() || segments.size() == 0) return null;
        JsonElement first = segments.get(0);
        if (first == null || !first.isJsonObject()) return null;
        String urlValue = firstNonBlank(string(first.getAsJsonObject(), "url"));
        return urlValue.isEmpty() ? null : new ResolveResult(urlValue, "mp4", 0L);
    }

    public static String sourceKey(String bvid, int page) {
        String id = nonBlank(bvid);
        return page > 1 ? id + ":" + page : id;
    }

    /**
     * 把一首曲目的 source id 解析成带 aid/cid 的 {@link Track}。
     *
     * <p>字幕歌词、收藏写入这些链路都需要 cid，而 {@link Music} 里只存 bvid+page
     * （往 source id 里塞 cid 会改掉 {@code getStableKey()}，让全部磁盘缓存失效）。
     * 所以统一在这里按需回查一次 {@code view}。</p>
     */
    static Track resolveTrack(String sourceId) {
        DirectVideo direct = parseSourceId(sourceId);
        if (direct == null) return null;
        try {
            return loadVideo(direct.bvid, direct.page);
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Track resolve failed for "
                    + nonBlank(sourceId) + ": " + describe(throwable));
            return null;
        }
    }

    private static List<Track> searchVideos(String keyword, int limit) throws Exception {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");
        JsonObject response = wbiGet(API_ROOT, "/wbi/search/type", params, false);
        requireSuccessful(response, "search");

        JsonObject data = object(response, "data");
        JsonArray results = array(data, "result");
        if (results == null || results.isJsonNull()) return List.of();

        List<Track> tracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement element : results) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject result = element.getAsJsonObject();
            String bvid = nonBlank(string(result, "bvid"));
            String title = cleanText(string(result, "title"));
            if (bvid.isEmpty() || title.isEmpty() || !seen.add(bvid)) continue;

            String artist = cleanText(string(result, "author"));
            long duration = parseDuration(string(result, "duration"));
            String cover = normalizeCover(string(result, "pic"));
            tracks.add(new Track(bvid, 1, title, artist, duration, cover,
                    string(result, "aid"), ""));
            if (tracks.size() >= limit) break;
        }
        return tracks;
    }

    private static Track loadVideo(String videoId, int page) throws Exception {
        JsonObject response = getJson(API_ROOT + "/view?" + viewIdQuery(videoId));
        requireSuccessful(response, "view");
        JsonObject data = object(response, "data");
        if (data == null) return null;

        // view 总是回规范化的 bvid，所以 av 号进来的话以响应为准，别把自己的入参当身份。
        String bvid = firstNonBlank(string(data, "bvid"), videoId);
        JsonObject selectedPage = selectPage(data, page);
        String title = cleanText(string(data, "title"));
        String part = cleanText(string(selectedPage, "part"));
        if (!part.isEmpty() && !part.equals(title)) title = title + " - " + part;
        String artist = cleanText(string(object(data, "owner"), "name"));
        long duration = longValue(selectedPage == null ? data : selectedPage, "duration") * 1000L;
        String cover = normalizeCover(string(data, "pic"));
        String aid = string(data, "aid");
        String cid = string(selectedPage == null ? data : selectedPage, "cid");
        if (aid.isEmpty() || cid.isEmpty()) return null;
        return new Track(bvid, page, title, artist, duration,
                cover, aid, cid);
    }

    /** {@code view} 的 id 参数：BV 号用 {@code bvid=}，AV 号用 {@code aid=}。 */
    static String viewIdQuery(String videoId) {
        String value = nonBlank(videoId);
        if (value.regionMatches(true, 0, "av", 0, 2) && value.substring(2).matches("[0-9]+")) {
            return "aid=" + urlEncode(value.substring(2));
        }
        return "bvid=" + urlEncode(value);
    }

    /** 把 {@code b23.tv/xxxx} 短链展开成规范链接。展开失败时原样返回，让 BV 正则去尽力。 */
    static String expandShortLink(String link) {
        String value = nonBlank(link);
        int index = value.indexOf("b23.tv/");
        if (index < 0) return value;
        String shortUrl = value.substring(index);
        int whitespace = firstIndexOfAny(shortUrl, " \t\r\n\"'");
        if (whitespace > 0) shortUrl = shortUrl.substring(0, whitespace);
        if (!shortUrl.startsWith("http")) shortUrl = "https://" + shortUrl;
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(shortUrl))
                            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                            .header("User-Agent", USER_AGENT)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String expanded = response.uri().toString();
            return expanded.isEmpty() ? value : value + " " + expanded;
        } catch (Throwable throwable) {
            return value;
        }
    }

    private static int firstIndexOfAny(String value, String characters) {
        for (int i = 0; i < value.length(); i++) {
            if (characters.indexOf(value.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static JsonObject selectPage(JsonObject data, int page) {
        JsonArray pages = array(data, "pages");
        if (pages == null || pages.isJsonNull() || pages.size() == 0) return null;
        for (JsonElement element : pages) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            if (longValue(candidate, "page") == page) return candidate;
        }
        throw new IllegalArgumentException("Bilibili video has no page " + page);
    }

    private static DirectVideo parseDirectVideo(String query) {
        String normalized = expandShortLink(query);
        Matcher bvidMatcher = BVID_PATTERN.matcher(normalized);
        if (bvidMatcher.find()) {
            return new DirectVideo(bvidMatcher.group(1), parsePage(normalized));
        }
        // 没有 BV 号时才认 AV 号：B 站的分享文案经常同时带 av 与 BV，那条规则要求 BV 优先。
        Matcher avMatcher = AV_PATTERN.matcher(normalized);
        return avMatcher.find()
                ? new DirectVideo("av" + avMatcher.group(1), parsePage(normalized)) : null;
    }

    /**
     * 把用户粘贴的任意文本归成一根稿件 id（BV 优先，其次 av），认不出来返回空串。
     *
     * <p>分享口令、完整带参数的链接、{@code b23.tv} 短链都走得通 —— 短链会先展开，因为
     * B 站的分享文案里经常只给短链。为什么要归一而不是把原文丢给 {@code view}：那只会换来
     * 一个必然失败的请求，报错里全是 URL 编码，谁都看不出是粘错了什么。</p>
     *
     */
    static String canonicalVideoId(String text) {
        DirectVideo direct = parseDirectVideo(text);
        return direct == null ? "" : direct.bvid;
    }

    private static int parsePage(String query) {
        Matcher pageMatcher = PAGE_PATTERN.matcher(query);
        if (!pageMatcher.find()) return 1;
        try {
            return Math.max(1, Integer.parseInt(pageMatcher.group(1)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static DirectVideo parseSourceId(String sourceId) {
        String value = nonBlank(sourceId);
        if (value.isEmpty()) return null;
        int separator = value.lastIndexOf(':');
        int page = 1;
        if (separator > 0) {
            try {
                page = Math.max(1, Integer.parseInt(value.substring(separator + 1)));
                value = value.substring(0, separator);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        Matcher matcher = BVID_PATTERN.matcher(value);
        if (matcher.find()) return new DirectVideo(matcher.group(1), page);
        return value.regionMatches(true, 0, "av", 0, 2) && value.substring(2).matches("[0-9]+")
                ? new DirectVideo(value, page) : null;
    }

    private static synchronized WbiKey wbiKey() throws Exception {
        WbiKey current = cachedWbiKey;
        if (current != null && current.expiresAt > System.currentTimeMillis()) return current;

        JsonObject response = getJson(API_ROOT + "/nav");
        JsonObject data = object(response, "data");
        JsonObject wbi = object(data, "wbi_img");
        String imgKey = fileNameWithoutExtension(string(wbi, "img_url"));
        String subKey = fileNameWithoutExtension(string(wbi, "sub_url"));
        String raw = imgKey + subKey;
        if (raw.length() < MIXIN_KEY_ENC_TAB.length) {
            throw new IllegalStateException("Bilibili returned an invalid WBI key");
        }

        WbiKey next = new WbiKey(mixinKey(raw),
                System.currentTimeMillis() + WBI_KEY_TTL_MILLIS);
        cachedWbiKey = next;
        return next;
    }

    /**
     * WBI 的 mixin key：把 {@code img_key + sub_key} 按 {@link #MIXIN_KEY_ENC_TAB} 逐位打乱，取前 32 个字符。
     *
     * <p>拆出来只为了一件事：这一步是纯字符串变换，出错时没有任何局部症状 —— 所有 WBI 接口
     * 一起返回 -403，而报错里看不出是签名写错了。有固定输入可对，就能在离线测试里钉住它。</p>
     */
    static String mixinKey(String raw) {
        StringBuilder mixed = new StringBuilder(32);
        for (int index : MIXIN_KEY_ENC_TAB) mixed.append(raw.charAt(index));
        return mixed.substring(0, 32);
    }

    private static JsonObject getJson(String url) throws Exception {
        return getJson(url, false);
    }

    /**
     * 发一次 JSON GET。
     *
     * @param useCookie 是否附上登录 Cookie。只有确实需要账号身份的接口才该传 {@code true}：
     *                  匿名接口带上 Cookie 会让 B 站按登录用户风控，反而更容易触发 -352。
     */
    static JsonObject getJson(String url, boolean useCookie) throws Exception {
        HttpResponse<String> response = HTTP.send(jsonRequest(url, useCookie),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static HttpRequest jsonRequest(String url, boolean useCookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Accept", "application/json");
        if (useCookie) {
            String cookie = BilibiliSessionStore.getCookieHeader();
            if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        }
        return builder.GET().build();
    }

    /**
     * 表单 POST（收藏夹写入这类带 {@code csrf} 的接口用）。
     *
     * <p>B 站的写接口一律要求 {@code application/x-www-form-urlencoded} + 登录 Cookie +
     * {@code csrf=bili_jct} 三者齐备，缺任一个分别对应 -400 / -101 / -111。</p>
     */
    static JsonObject postForm(String url, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        String cookie = BilibiliSessionStore.getCookieHeader();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        HttpResponse<String> response = HTTP.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                        StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** 供同包的登录/字幕/收藏服务复用的 WBI 签名 GET。 */
    static JsonObject wbiGet(String root, String path, TreeMap<String, String> params,
                             boolean useCookie) throws Exception {
        WbiKey key = wbiKey();
        TreeMap<String, String> signed = new TreeMap<>(params);
        signed.put("wts", Long.toString(System.currentTimeMillis() / 1000L));
        String canonical = canonicalQuery(signed);
        try {
            return getJson(root + path + "?" + canonical + "&w_rid=" + md5Hex(canonical + key.value),
                    useCookie);
        } catch (Exception exception) {
            // WBI key 的本地缓存是按时长过期的，B 站却可能提前轮换。命中 -403/w_rid 校验失败时
            // 作废缓存重试一次，比让用户看到「搜索暂时不可用」划算。
            if (!isWbiRejection(exception)) throw exception;
            cachedWbiKey = null;
            WbiKey refreshed = wbiKey();
            TreeMap<String, String> retried = new TreeMap<>(params);
            retried.put("wts", Long.toString(System.currentTimeMillis() / 1000L));
            String canonical2 = canonicalQuery(retried);
            return getJson(root + path + "?" + canonical2 + "&w_rid="
                    + md5Hex(canonical2 + refreshed.value), useCookie);
        }
    }

    private static boolean isWbiRejection(Throwable throwable) {
        String message = throwable == null ? "" : String.valueOf(throwable.getMessage());
        return message.contains("w_rid") || message.contains("-403") || message.contains("签名");
    }

    /** 播放链路只认 AAC，所以「能不能拿高解析」这件事只有下载路径需要问。 */
    static String cookieHeader() {
        return BilibiliSessionStore.getCookieHeader();
    }

    // ═════════════════════════════ 账号与登录态 ═════════════════════════════

    /** 扫码是否已经完成：只看本地有没有 {@code SESSDATA}，不发请求。 */
    public static boolean isLoggedIn() {
        return BilibiliSessionStore.isLoggedIn();
    }

    /** 当前账号的 mid（B 站 UID），未登录为空串。收藏夹与字幕接口都要拿它当参数。 */
    public static String getAccountId() {
        return BilibiliSessionStore.snapshot().userId;
    }

    public static String getAccountName() {
        BilibiliSessionStore.Session session = BilibiliSessionStore.snapshot();
        if (!session.loggedIn) return "未登录";
        if (!session.nickname.isEmpty()) return session.nickname;
        return "哔哩哔哩账号";
    }

    public static String getAvatarUrl() {
        return BilibiliSessionStore.snapshot().avatarUrl;
    }

    /** 扫码成功后拉一次 {@code nav}，把昵称/头像/会员写进凭据文件。失败时安静返回。 */
    public static void refreshProfile() {
        if (!isLoggedIn()) return;
        try {
            JsonObject data = object(getJson(API_ROOT + "/nav", true), "data");
            if (data == null) return;
            String mid = string(data, "mid");
            String name = firstNonBlank(string(data, "uname"), string(data, "nickname"));
            String face = normalizeCover(string(data, "face"));
            JsonObject vip = object(data, "vip");
            int vipType = (int) longValue(vip == null ? data : vip, "vipType");
            int vipStatus = (int) longValue(vip == null ? data : vip, "vipStatus");
            boolean isVip = longValue(data, "isLogin") == 1L && vipStatus == 1 && vipType > 0;
            invalidateVipCache();
            BilibiliSessionStore.saveProfile(mid, name, face, isVip, vipType,
                    isVip ? "大会员" : "");
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Profile failed: " + describe(throwable));
        }
    }

    /**
     * 会员标记是否为大会员。<b>只读本地快照，不发请求</b>，理由同
     * {@link QishuiMusicService#isVipAccount()}：账号面板每帧都会问一次，
     * 在这里挂一次 {@code nav} 就是每个呼吸周期堵一次渲染线程。
     *
     * <p>快照由扫码成功时的 {@link #refreshProfile()} 写入，因此它可能落后于
     * 上游的会员状态；取流前的判定用 {@link #queryVipAccount()}。</p>
     */
    public static boolean isVipAccount() {
        return BilibiliSessionStore.snapshot().vip;
    }

    /**
     * 取流前的会员判定：结果按账号缓存，直到登录态变化。
     *
     * <p>查询失败时返回 {@code false} 但<b>不写缓存</b>：否则一次超时就会把高解析档位
     * 永久钉死，直到用户手动退出重登。</p>
     */
    static boolean queryVipAccount() {
        if (!isLoggedIn()) return false;
        String owner = getAccountId();
        Boolean cached = vipAccountCache;
        if (cached != null && owner.equals(vipAccountCacheOwner)) return cached;
        try {
            JsonObject data = object(getJson(API_ROOT + "/nav", true), "data");
            if (data == null) return false;
            int vipType = (int) longValue(object(data, "vip"), "vipType");
            int vipStatus = (int) longValue(object(data, "vip"), "vipStatus");
            boolean isVip = longValue(data, "isLogin") == 1L && vipStatus == 1 && vipType > 0;
            vipAccountCache = isVip;
            vipAccountCacheOwner = owner;
            return isVip;
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] VIP check failed: " + describe(throwable));
            return false;
        }
    }

    /** 换账号或退出登录时作废会员缓存，避免新账号沿用旧账号的档位判断。 */
    static void invalidateVipCache() {
        vipAccountCache = null;
        vipAccountCacheOwner = "";
    }

    /** 退出登录：只清 B 站自己的凭据与缓存，不碰网易云/QQ 的。 */
    public static synchronized void logout() {
        BilibiliSessionStore.clear();
        invalidateVipCache();
        // 收藏夹列表与红心状态都是按账号缓存的，留着的话新账号登上来会先看到上一个人的。
        BilibiliFavoriteService.clearAccountCaches();
    }

    /** 扫码链路确认后由 {@link BilibiliLogin} 调用，把新凭据刷新成账号资料。 */
    static void onLoginConfirmed() {
        invalidateVipCache();
        refreshProfile();
    }

    static void requireSuccessful(JsonObject response, String operation) {
        if (response == null || !response.has("code")) {
            throw new IllegalStateException("Bilibili " + operation + " response is empty");
        }
        int code = 0;
        try {
            code = response.get("code").getAsInt();
        } catch (Throwable ignored) {
            throw new IllegalStateException("Bilibili " + operation + " response is invalid");
        }
        if (code != 0) {
            String message = string(response, "message");
            throw new IllegalStateException(message.isEmpty()
                    ? "Bilibili " + operation + " returned " + code : message);
        }
    }

    static String canonicalQuery(TreeMap<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(urlEncode(entry.getKey())).append('=')
                    .append(urlEncode(sanitizeWbiValue(entry.getValue())));
        }
        return query.toString();
    }

    private static String sanitizeWbiValue(String value) {
        return nonBlank(value).replace("!", "").replace("'", "")
                .replace("(", "").replace(")", "").replace("*", "");
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(nonBlank(value), StandardCharsets.UTF_8);
    }

    static String md5Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) result.append(String.format("%02x", item));
        return result.toString();
    }

    static String fileNameWithoutExtension(String url) {
        String value = nonBlank(url);
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    static String cleanText(String value) {
        String text = nonBlank(value).replaceAll("<[^>]+>", "");
        text = text.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return text.replace("&amp;", "&").trim();
    }

    static String normalizeCover(String value) {
        String cover = nonBlank(value);
        if (cover.startsWith("//")) return "https:" + cover;
        return cover.replace("http://", "https://");
    }

    private static long parseDuration(String value) {
        String duration = nonBlank(value);
        if (duration.isEmpty()) return 0L;
        if (!duration.contains(":")) {
            try {
                return Long.parseLong(duration) * 1000L;
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }

        String[] parts = duration.split(":");
        long seconds = 0L;
        for (String part : parts) {
            try {
                seconds = seconds * 60L + Long.parseLong(part.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return seconds * 1000L;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    static String string(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
        try {
            return nonBlank(object.get(property).getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    static long longValue(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0L;
        try {
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    static JsonObject object(JsonObject object, String property) {
        if (object == null || !object.has(property) || !object.get(property).isJsonObject()) return null;
        return object.getAsJsonObject(property);
    }

    static JsonArray array(JsonObject object, String property) {
        if (object == null || !object.has(property) || !object.get(property).isJsonArray()) return null;
        return object.getAsJsonArray(property);
    }

    static String describe(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null
                ? String.valueOf(throwable) : throwable.getMessage();
    }

    private static final class DirectVideo {
        private final String bvid;
        private final int page;

        private DirectVideo(String bvid, int page) {
            this.bvid = bvid;
            this.page = page;
        }
    }

    private static final class WbiKey {
        private final String value;
        private final long expiresAt;

        private WbiKey(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}