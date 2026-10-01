package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.lxsource.LxSourceService;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷我音乐内置搜索，逐字段照抄上游 lx-music-desktop 的
 * {@code src/renderer/utils/musicSdk/kw/musicSearch.js}。
 *
 * <h3>为什么宿主要有这一份</h3>
 * <p>自定义源（LX 脚本）在上游只有 musicUrl / lyric / pic 三个动作，<b>搜索从来不走脚本</b>，
 * 走的是应用内置的 musicSdk。用户在脚本里声明了 kw 渠道后，酷我就成了一个真实可播的
 * 换源候选，但宿主此前既没有酷我平台也没有酷我搜索 —— 跨渠道换源就没有候选可挑。
 * 这一份就是那块缺失的搜索：结果与脚本 search 扩展同形状（内置 musicSearch 的 hit 结构），
 * 统一从 {@link Music#fromLxSearchHit} 拼成曲目。</p>
 *
 * <p><b>只有搜索</b>：酷我渠道的取流完全交给自定义源（宿主没有内置的 kw 取流 SDK），
 * 所以拼出来的 {@code Music} 在 {@code getPlayUrl} 里除了脚本那一跳之外直接返回 null。</p>
 */
public final class KuwoMusicService {

    /** 上游 musicSearch 原样的查询串；一个参数都没动。 */
    private static final String SEARCH_URL =
            "http://search.kuwo.cn/r.s?client=kt&all=%s&pn=%d&rn=%d"
                    + "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1"
                    + "&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8"
                    + "&rformat=json&vermerge=1&mobi=1&issubtitle=1";
    private static final int DEFAULT_LIMIT = 30;
    /** 上游的重试上限：retryNum > 2。 */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(6_000L);
    /** N_MINFO 里的档位描述：level:(\w+),bitrate:(\d+),format:(\w),size:([\w.]+)。 */
    private static final Pattern M_INFO =
            Pattern.compile("level:(\\w+),bitrate:(\\d+),format:(\\w),size:([\\w.]+)");
    private static final Pattern ENTITY_DECIMAL = Pattern.compile("&#(\\d+);");
    private static final Pattern ENTITY_HEX = Pattern.compile("&#x([0-9a-fA-F]+);");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 上游 kw/pic.js 的取图接口：响应体本身就是封面直链文本，不是 JSON。 */
    private static final String PIC_URL =
            "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=%s";
    /** 同一首的封面只问一次（拿不到也记住，免得每张纹理刷新都打一遍）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> PIC_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private KuwoMusicService() {
    }

    /**
     * 酷我封面（对应上游 kw/pic.js 的 getPic）。酷我搜索响应里<b>没有</b>封面
     * （上游 musicSearch 也是固定 {@code img: null}），播放时的封面靠这单点查询拿。
     *
     * <p>网络调用，只能在后台线程（封面线程）上调。失败返回空串并缓存失败。</p>
     */
    public static String getPicUrl(String songmid) {
        String id = nonNull(songmid).trim();
        if (id.isEmpty()) return "";
        String cached = PIC_CACHE.get(id);
        if (cached != null) return cached;
        String url = "";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            String.format(java.util.Locale.ROOT, PIC_URL, id)))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            url = pickPicUrl(response.body());
        } catch (Throwable failure) {
            System.err.println("[Music/Kuwo] 封面查询失败：" + failure.getMessage());
        }
        PIC_CACHE.put(id, url);
        return url;
    }

    /** 上游判据 {@code /^http/.test(body)}：响应体以 http 开头才算拿到链接。 */
    static String pickPicUrl(String body) {
        String trimmed = nonNull(body).trim();
        return trimmed.startsWith("http") ? trimmed : "";
    }

    /**
     * 搜一次酷我。任何失败都返回空列表 —— 调用方（换源流水线 / 搜索分发）按空处理。
     *
     * @param keyword 搜索词（曲名 + 艺人）
     * @param limit   每页条数；上游默认 30
     */
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
                // 上游守卫：`!result || (TOTAL !== '0' && SHOW === '0')` —— 明明有总量却一条
                // 都没展示（接口抽风的形状），整次作废重试。
                if (result == null
                        || (!"0".equals(string(result, "TOTAL")) && "0".equals(string(result, "SHOW")))) {
                    continue;
                }
                List<Music> list = handleResult(result.get("abslist"));
                if (list == null) continue; // N_MINFO 缺失：上游同样整页作废重试
                return list;
            } catch (Throwable failure) {
                System.err.println("[Music/Kuwo] 搜索失败（第 " + (attempt + 1) + " 次）："
                        + failure.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private static JsonObject request(String keyword, int page, int limit)
            throws IOException, InterruptedException {
        String url = String.format(java.util.Locale.ROOT, SEARCH_URL,
                encode(keyword), Math.max(0, page - 1), limit);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("酷我 HTTP " + response.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    /**
     * 上游 handleResult 的直译：MUSICRID 去前缀、N_MINFO 必须存在、字段全部解码。
     *
     * @return null 表示整页作废（上游 {@code return null} 的语义，调用方据此重试）
     */
    static List<Music> handleResult(JsonElement rawList) {
        if (rawList == null || !rawList.isJsonArray()) return Collections.emptyList();
        List<Music> result = new ArrayList<>();
        for (JsonElement element : rawList.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject info = element.getAsJsonObject();
            String songId = string(info, "MUSICRID").replace("MUSIC_", "");
            if (songId.isEmpty()) continue;
            if (string(info, "N_MINFO").isEmpty()) {
                System.err.println("[Music/Kuwo] N_MINFO is undefined");
                return null; // 上游：整个结果作废，由 search 重试
            }
            JsonObject hit = new JsonObject();
            hit.addProperty("name", decodeName(string(info, "SONGNAME")));
            hit.addProperty("singer", formatSinger(decodeName(string(info, "ARTIST"))));
            hit.addProperty("source", "kw");
            hit.addProperty("songmid", songId);
            hit.addProperty("albumId", decodeName(string(info, "ALBUMID")));
            hit.addProperty("albumName", info.has("ALBUM") ? decodeName(string(info, "ALBUM")) : "");
            hit.addProperty("interval", formatPlayTime(parseIntOr(string(info, "DURATION"), -1)));
            // 上游固定 img: null —— 搜索响应里没有封面，别拿别的平台的图凑。
            Music music = Music.fromLxSearchHit(LxSourceService.SearchHit.of(hit, "kw"));
            if (music != null) result.add(music);
        }
        return result;
    }

    /** N_MINFO 里的可用档位（供上层显示音质角标用；解析口径与上游 regExps.mInfo 一致）。 */
    static List<String> qualitiesOf(String nMinfo) {
        List<String> types = new ArrayList<>();
        for (String piece : nonNull(nMinfo).split(";")) {
            Matcher matcher = M_INFO.matcher(piece);
            if (!matcher.find()) continue;
            if ("4000".equals(matcher.group(2))) types.add("flac24bit");
            else if ("2000".equals(matcher.group(2))) types.add("flac");
            else if ("320".equals(matcher.group(2))) types.add("320k");
            else if ("128".equals(matcher.group(2))) types.add("128k");
        }
        Collections.reverse(types); // 上游 types.reverse()：高档在前
        return types;
    }

    /**
     * 上游 {@code decodeName}（DOMParser 解 HTML 实体）的等价实现：
     * 数字实体 + 常用命名实体。解不出来的原样保留，不吞字。
     */
    static String decodeName(String raw) {
        String value = nonNull(raw);
        if (value.isEmpty()) return "";
        Matcher decimal = ENTITY_DECIMAL.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (decimal.find()) {
            decimal.appendReplacement(sb, Matcher.quoteReplacement(toChar(Integer.parseInt(decimal.group(1)))));
        }
        decimal.appendTail(sb);
        Matcher hex = ENTITY_HEX.matcher(sb.toString());
        StringBuffer out = new StringBuffer();
        while (hex.find()) {
            hex.appendReplacement(out, Matcher.quoteReplacement(toChar(Integer.parseInt(hex.group(1), 16))));
        }
        hex.appendTail(out);
        return out.toString()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }

    private static String toChar(int codePoint) {
        try {
            return new String(Character.toChars(codePoint));
        } catch (IllegalArgumentException broken) {
            return "";
        }
    }

    /** 上游 kw/util.js：formatSinger = 把 & 换成顿号。 */
    static String formatSinger(String singer) {
        return nonNull(singer).replace("&", "、");
    }

    /** 上游 formatPlayTime：mm:ss，双零给 "--/--"（fromLxSearchHit 会把它读成时长 0）。 */
    static String formatPlayTime(int seconds) {
        if (seconds < 0) return "--/--";
        int m = seconds / 60;
        int s = seconds % 60;
        if (m == 0 && s == 0) return "--/--";
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    private static String encode(String value) {
        // JS 的 encodeURIComponent 空格是 %20，URLEncoder 是 +；换回去保持同形。
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(nonNull(value).trim());
        } catch (NumberFormatException broken) {
            return fallback; // 上游 Number.isNaN → interval 0 → "--/--"
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString().trim();
        } catch (Throwable notText) {
            return String.valueOf(object.get(key)).trim();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
