package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.lxsource.LxSourceService;
import com.muoniumplayer.core.ncm.math.DigestUtils;
import com.muoniumplayer.core.ncm.music.dto.Music;

import java.io.IOException;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 咪咕音乐内置搜索，逐字段照抄上游 lx-music-desktop 的
 * {@code src/renderer/utils/musicSdk/mg/musicSearch.js}。
 *
 * <p>与 {@link KuwoMusicService} 同一动机：自定义源声明了 mg 渠道后咪咕就是一个真实
 * 可播的换源候选，而宿主缺的正是这块搜索。签名（{@link #createSignature}）、请求头、
 * {@code code === '000000'} 判定、{@code resultList} 的数组套数组形状、按 copyrightId
 * 去重、封面相对路径补 {@code d.musicapp.migu.cn} 前缀，全部与上游一致。</p>
 *
 * <p><b>只有搜索</b>：咪咕渠道的取流完全交给自定义源（脚本按 copyrightId 要流）。</p>
 */
public final class MiguMusicService {

    private static final String SEARCH_URL_HEAD =
            "https://jadeite.migu.cn/music_search/v3/search/searchAll"
                    + "?isCorrect=0&isCopyright=1"
                    + "&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0"
                    + "%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0"
                    + "%2C%22lyricSong%22%3A0%7D"
                    + "&pageSize=";
    private static final String SEARCH_URL_MID = "&text=";
    private static final String SEARCH_URL_TAIL = "&sort=0&sid=USS";
    /** 上游 createSignature 里写死的设备号与盐。 */
    static final String DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20";
    private static final String SIGNATURE_MD5 = "6cdc72a439cef99a3418d2a78aa28c73";
    private static final String SIGN_APP_TAIL = "yyapp2d16148780a1dcc7408e06336b98cfd50";
    private static final int DEFAULT_LIMIT = 20;
    /** 上游：retryNum > 3。 */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(6_000L);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private MiguMusicService() {
    }

    public static List<Music> search(String keyword, int limit) {
        return search(keyword, 1, limit);
    }

    public static List<Music> search(String keyword, int page, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        String text = keyword.trim();
        int size = limit <= 0 ? DEFAULT_LIMIT : limit;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                JsonObject result = request(text, page, size);
                // 上游：`!result || result.code !== '000000'` 即失败。
                if (result == null || !"000000".equals(string(result, "code"))) {
                    String info = result == null ? "空响应" : string(result, "info");
                    if (attempt == MAX_ATTEMPTS - 1) {
                        System.err.println("[Music/Migu] 搜索失败：" + info);
                    }
                    continue;
                }
                JsonObject songData = object(result, "songResultData");
                return filterData(songData.get("resultList"));
            } catch (Throwable failure) {
                System.err.println("[Music/Migu] 搜索异常（第 " + (attempt + 1) + " 次）："
                        + failure.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private static JsonObject request(String keyword, int page, int limit)
            throws IOException, InterruptedException {
        String time = String.valueOf(System.currentTimeMillis());
        Signature signature = createSignature(time, keyword);
        // 查询串里满是 %22/%7B 这类字面百分号，绝不能过 String.format（会被当格式符）。
        String url = SEARCH_URL_HEAD + limit + SEARCH_URL_MID + encode(keyword)
                + "&pageNo=" + page + SEARCH_URL_TAIL;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("uiVersion", "A_music_3.6.1")
                .header("deviceId", signature.deviceId)
                .header("timestamp", time)
                .header("sign", signature.sign)
                .header("channel", "0146921")
                .header("User-Agent", "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) "
                        + "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("咪咕 HTTP " + response.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    /** 上游 createSignature：MD5(text + 盐 + yyapp 尾串 + deviceId + time)。 */
    static Signature createSignature(String time, String text) {
        String sign = DigestUtils.md5Hex(text + SIGNATURE_MD5 + SIGN_APP_TAIL + DEVICE_ID + time);
        return new Signature(sign, DEVICE_ID);
    }

    /** 签名结果（sign + deviceId 要同时给到查询头）。 */
    static final class Signature {
        final String sign;
        final String deviceId;

        Signature(String sign, String deviceId) {
            this.sign = sign;
            this.deviceId = deviceId;
        }
    }

    /**
     * 上游 filterData 的直译：resultList 是「数组的数组」，逐项要求 songId 与 copyrightId，
     * 按 copyrightId 去重，产出与内置 musicSearch 同形状的 hit 交给统一拼装。
     */
    static List<Music> filterData(JsonElement resultList) {
        if (resultList == null || !resultList.isJsonArray()) return Collections.emptyList();
        List<Music> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement group : resultList.getAsJsonArray()) {
            if (!group.isJsonArray()) continue;
            for (JsonElement element : group.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject data = element.getAsJsonObject();
                String songId = string(data, "songId");
                String copyrightId = string(data, "copyrightId");
                if (songId.isEmpty() || copyrightId.isEmpty() || !ids.add(copyrightId)) continue;
                JsonObject hit = new JsonObject();
                hit.addProperty("name", string(data, "name"));
                hit.addProperty("singer", formatSingerName(data.get("singerList")));
                hit.addProperty("source", "mg");
                hit.addProperty("songmid", songId);
                hit.addProperty("copyrightId", copyrightId);
                hit.addProperty("albumId", string(data, "albumId"));
                hit.addProperty("albumName", string(data, "album"));
                hit.addProperty("interval", formatPlayTime(parseIntOr(string(data, "duration"), -1)));
                hit.addProperty("img", resolveImg(data));
                Music music = Music.fromLxSearchHit(LxSourceService.SearchHit.of(hit, "mg"));
                if (music != null) list.add(music);
            }
        }
        return list;
    }

    /** 上游：img3 || img2 || img1；相对路径补 http://d.musicapp.migu.cn 前缀。 */
    static String resolveImg(JsonObject data) {
        String img = firstNonEmpty(string(data, "img3"), string(data, "img2"), string(data, "img1"));
        if (img.isEmpty()) return "";
        if (img.startsWith("http://") || img.startsWith("https://")) return img;
        return "http://d.musicapp.migu.cn" + img;
    }

    /** 上游 musicSdk/utils.js formatSingerName：取 singerList 每项的 name，顿号相连再解码。 */
    static String formatSingerName(JsonElement singerList) {
        if (singerList == null || !singerList.isJsonArray()) return "";
        List<String> names = new ArrayList<>();
        for (JsonElement element : singerList.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            String name = string(element.getAsJsonObject(), "name");
            if (!name.isEmpty()) names.add(name);
        }
        return KuwoMusicService.decodeName(String.join("、", names));
    }

    /** 上游 formatPlayTime（与酷我同一实现，语义一致：0/无效给 "--/--"）。 */
    static String formatPlayTime(int seconds) {
        if (seconds < 0) return "--/--";
        int m = seconds / 60;
        int s = seconds % 60;
        if (m == 0 && s == 0) return "--/--";
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return (int) Double.parseDouble(value); // 上游字段有时是浮点秒
        } catch (NumberFormatException broken) {
            return fallback;
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return new JsonObject();
        return parent.getAsJsonObject(key);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString().trim();
        } catch (Throwable notText) {
            return String.valueOf(object.get(key)).trim();
        }
    }
}
