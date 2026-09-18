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
 * 现行的 WBI 查询签名，因此该源无需向用户索取 cookie 即可工作。</p>
 */
public final class BilibiliMusicService {

    private static final String API_ROOT = "https://api.bilibili.com/x/web-interface";
    private static final String PLAYER_API_ROOT = "https://api.bilibili.com/x/player";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final Pattern BVID_PATTERN = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern PAGE_PATTERN = Pattern.compile("(?:[?&]p=)([1-9][0-9]*)");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 25_000;
    private static final long WBI_KEY_TTL_MILLIS = 12L * 60L * 60L * 1000L;

    /** Bilibili 公开的 WBI mixin-key 排列。它在密钥轮换期间保持稳定。 */
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
        DirectVideo direct = parseSourceId(sourceId);
        if (direct == null) return null;

        try {
            Track track = loadVideo(direct.bvid, direct.page);
            if (track == null) return null;

            JsonObject response = getJson(PLAYER_API_ROOT + "/playurl?fnval=80&avid="
                    + urlEncode(track.aid) + "&cid=" + urlEncode(track.cid));
            requireSuccessful(response, "playurl");
            JsonObject data = object(response, "data");
            JsonObject dash = object(data, "dash");
            JsonArray streams = array(dash, "audio");
            if (streams == null || streams.isJsonNull() || streams.size() == 0) {
                throw new IllegalStateException("Bilibili response contains no DASH audio stream");
            }

            boolean preferHigherQuality = requestedQuality != Quality.STANDARD;
            JsonObject selected = null;
            long selectedBandwidth = preferHigherQuality ? Long.MIN_VALUE : Long.MAX_VALUE;
            JsonObject fallback = null;
            for (JsonElement element : streams) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject stream = element.getAsJsonObject();
                String url = firstNonBlank(string(stream, "baseUrl"), string(stream, "base_url"));
                if (url.isEmpty()) continue;
                if (fallback == null) fallback = stream;

                // 所有 HE-AAC 变体。优先选 AAC-LC，再退回第一个流。
                String codecs = string(stream, "codecs");
                long bandwidth = longValue(stream, "bandwidth");
                if (!"mp4a.40.2".equals(codecs)) continue;
                if (preferHigherQuality ? bandwidth >= selectedBandwidth : bandwidth <= selectedBandwidth) {
                    selected = stream;
                    selectedBandwidth = bandwidth;
                }
            }

            JsonObject chosen = selected == null ? fallback : selected;
            String url = firstNonBlank(string(chosen, "baseUrl"), string(chosen, "base_url"));
            if (url.isEmpty()) throw new IllegalStateException("Bilibili audio stream has no URL");
            long bandwidth = longValue(chosen, "bandwidth");
            // Bilibili 纯音频 DASH 是分片 MP4。返回 "mp4" 可让现有的
            // 字节级检测器和 AacAudioDecoder 继续负责真实容器。
            return new ResolveResult(url, "mp4", bandwidth);
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Song URL failed for " + nonBlank(sourceId) + ": "
                    + describe(throwable));
            return null;
        }
    }

    public static String sourceKey(String bvid, int page) {
        String id = nonBlank(bvid);
        return page > 1 ? id + ":" + page : id;
    }

    private static List<Track> searchVideos(String keyword, int limit) throws Exception {
        WbiKey key = wbiKey();
        TreeMap<String, String> params = new TreeMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");
        params.put("wts", Long.toString(System.currentTimeMillis() / 1000L));
        String canonicalQuery = canonicalQuery(params);
        String wRid = md5Hex(canonicalQuery + key.value);
        JsonObject response = getJson(API_ROOT + "/wbi/search/type?" + canonicalQuery
                + "&w_rid=" + wRid);
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

    private static Track loadVideo(String bvid, int page) throws Exception {
        JsonObject response = getJson(API_ROOT + "/view?bvid=" + urlEncode(bvid));
        requireSuccessful(response, "view");
        JsonObject data = object(response, "data");
        if (data == null) return null;

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
        Matcher bvidMatcher = BVID_PATTERN.matcher(query);
        if (!bvidMatcher.find()) return null;
        String bvid = bvidMatcher.group(1);
        Matcher pageMatcher = PAGE_PATTERN.matcher(query);
        int page = 1;
        if (pageMatcher.find()) {
            try {
                page = Math.max(1, Integer.parseInt(pageMatcher.group(1)));
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        return new DirectVideo(bvid, page);
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
        return matcher.find() ? new DirectVideo(matcher.group(1), page) : null;
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

        StringBuilder mixed = new StringBuilder(32);
        for (int index : MIXIN_KEY_ENC_TAB) mixed.append(raw.charAt(index));
        WbiKey next = new WbiKey(mixed.substring(0, 32),
                System.currentTimeMillis() + WBI_KEY_TTL_MILLIS);
        cachedWbiKey = next;
        return next;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static void requireSuccessful(JsonObject response, String operation) {
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

    private static String canonicalQuery(TreeMap<String, String> params) {
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

    private static String urlEncode(String value) {
        return URLEncoder.encode(nonBlank(value), StandardCharsets.UTF_8);
    }

    private static String md5Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) result.append(String.format("%02x", item));
        return result.toString();
    }

    private static String fileNameWithoutExtension(String url) {
        String value = nonBlank(url);
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String cleanText(String value) {
        String text = nonBlank(value).replaceAll("<[^>]+>", "");
        text = text.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return text.replace("&amp;", "&").trim();
    }

    private static String normalizeCover(String value) {
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String string(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
        try {
            return nonBlank(object.get(property).getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0L;
        try {
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static JsonObject object(JsonObject object, String property) {
        if (object == null || !object.has(property) || !object.get(property).isJsonObject()) return null;
        return object.getAsJsonObject(property);
    }

    private static JsonArray array(JsonObject object, String property) {
        if (object == null || !object.has(property) || !object.get(property).isJsonArray()) return null;
        return object.getAsJsonArray(property);
    }

    private static String describe(Throwable throwable) {
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