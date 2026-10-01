package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bilibili 的字幕歌词。
 *
 * <p>B 站没有「歌词」这个概念，能用的就是 web 播放器那条字幕链路：</p>
 * <pre>
 *   GET /x/player/wbi/v2?bvid=…&amp;cid=…      （必须 WBI 签名，且必须登录）
 *       → data.subtitle.subtitles[] { lan, lan_doc, ai_type, subtitle_url }
 *   GET https:{subtitle_url}
 *       → { "body": [ { "from": 12.34, "to": 15.67, "content": "…" }, … ] }
 * </pre>
 *
 * <p><b>未登录时 {@code subtitles} 恒为 {@code []}</b> —— 官方文档明确写了这一点。所以这一层
 * 是登录态的下游功能：拿不到就返回 {@code null}，让调用方继续走
 * {@link AmllTtmlLyricService} 的按标题匹配兜底，绝不返回一份空 Lyric 把兜底也挡掉。</p>
 *
 * <p>{@code subtitle_url} 带 {@code auth_key}，是有时效的签名地址，因此只缓存转好的 LRC 文本，
 * 不缓存 URL 本身。</p>
 */
public final class BilibiliSubtitleService {

    private static final String PLAYER_WBI_ROOT = "https://api.bilibili.com/x/player/wbi";
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{2}):(\\d{2})\\.(\\d{3})$");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BilibiliSubtitleService() {
    }

    /** 一条字幕行：起止秒 + 文本。 */
    static final class SubtitleLine {
        final double from;
        final double to;
        final String content;

        SubtitleLine(double from, double to, String content) {
            this.from = from;
            this.to = to;
            this.content = content;
        }
    }

    /**
     * 取一首 B 站曲目当前分 P 的字幕，转成 LRC 文本。
     *
     * @return LRC 字符串；没有字幕、未登录或请求失败时返回空串。
     */
    public static String getLyricText(String sourceId) {
        BilibiliMusicService.Track track = BilibiliMusicService.resolveTrack(sourceId);
        if (track == null) return "";
        List<SubtitleLine> lines = fetchLines(track.bvid, track.cid);
        if (lines.isEmpty()) return "";
        StringBuilder lrc = new StringBuilder(lines.size() * 24);
        for (SubtitleLine line : lines) {
            if (line.content.isEmpty()) continue;
            lrc.append(formatTimestamp(line.from)).append(line.content).append('\n');
        }
        return lrc.toString().trim();
    }

    /** 拉字幕清单并下载内容；未登录或该视频没有字幕时返回空表。 */
    static List<SubtitleLine> fetchLines(String bvid, String cid) {
        if (bvid == null || bvid.trim().isEmpty()) return List.of();
        if (!BilibiliMusicService.isLoggedIn()) return List.of();
        try {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("bvid", bvid.trim());
            params.put("cid", cid == null ? "" : cid.trim());
            JsonObject response = BilibiliMusicService.wbiGet(PLAYER_WBI_ROOT, "/v2", params, true);
            BilibiliMusicService.requireSuccessful(response, "player/wbi/v2");
            // data.subtitle.subtitles 是数组而不是对象：写成 object() 会因为它不是 JsonObject
            // 返回 null，于是每条视频都「没有字幕」。
            String subtitleUrl = pickSubtitleUrl(BilibiliMusicService.array(
                    BilibiliMusicService.object(BilibiliMusicService.object(response, "data"), "subtitle"),
                    "subtitles"));
            if (subtitleUrl.isEmpty()) return List.of();
            return parseSubtitleBody(httpGetText(subtitleUrl));
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Subtitle failed for " + bvid + ": "
                    + BilibiliMusicService.describe(throwable));
            return List.of();
        }
    }

    /**
     * 从字幕清单里挑一条。
     *
     * <p>优先中文：B 站会为同一视频给出 {@code ai-zh}/{@code zh-CN} 等多种语言，音乐区绝大多数
     * 只有中文一条。没有中文时退回第一条 —— 一条英文字幕对中文用户也比没有强。</p>
     */
    static String pickSubtitleUrl(JsonArray subtitles) {
        if (subtitles == null || subtitles.isJsonNull()) return "";
        String first = "";
        for (JsonElement element : subtitles) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String url = normalizeSubtitleUrl(BilibiliMusicService.string(item, "subtitle_url"));
            if (url.isEmpty()) continue;
            if (first.isEmpty()) first = url;
            String lan = BilibiliMusicService.string(item, "lan").toLowerCase(Locale.ROOT);
            if (lan.contains("zh")) return url;
        }
        return first;
    }

    /** 字幕地址是 protocol-relative（以 {@code //} 开头），要补协议。 */
    static String normalizeSubtitleUrl(String value) {
        String url = BilibiliMusicService.nonBlank(value);
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }

    /** 解析 {@code {"body":[{"from","to","content"}…]}}。字段缺失或类型不对时逐条跳过。 */
    static List<SubtitleLine> parseSubtitleBody(String json) {
        List<SubtitleLine> lines = new ArrayList<>();
        String payload = BilibiliMusicService.nonBlank(json);
        if (payload.isEmpty()) return lines;
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            JsonArray body = BilibiliMusicService.array(root, "body");
            if (body == null || body.isJsonNull()) return lines;
            for (JsonElement element : body) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                double from = doubleValue(item, "from");
                double to = doubleValue(item, "to");
                String content = BilibiliMusicService.string(item, "content");
                if (content.isEmpty() || to < from) continue;
                lines.add(new SubtitleLine(from, to, content));
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Subtitle body parse failed: "
                    + BilibiliMusicService.describe(throwable));
            return List.of();
        }
        return lines;
    }

    /**
     * 秒 → {@code [mm:ss.SSS]}。
     *
     * <p>超过一小时也写成分钟数（{@code [75:12.340]}）而不是 {@code [hh:mm:ss]}：LRC 的
     * 时间戳规范只认两段，我们现有的解析器（{@code LyricParser}）也是按两段解析的，
     * 强行加小时位会让整行解析失败。</p>
     */
    static String formatTimestamp(double seconds) {
        double safe = Double.isFinite(seconds) && seconds > 0 ? seconds : 0;
        long totalTenths = Math.round(safe * 1000D);
        long minutes = totalTenths / 60_000L;
        long remaining = totalTenths % 60_000L;
        long secs = remaining / 1000L;
        long millis = remaining % 1000L;
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, secs, millis);
    }

    /** 从 LRC 文本反解回秒，供测试与将来的逐字对齐使用。 */
    static double parseTimestamp(String stamp) {
        String value = BilibiliMusicService.nonBlank(stamp).replace("[", "").replace("]", "");
        Matcher matcher = TIME_PATTERN.matcher(value);
        if (!matcher.matches()) return -1D;
        return Long.parseLong(matcher.group(1)) * 60D
                + Long.parseLong(matcher.group(2))
                + Long.parseLong(matcher.group(3)) / 1000D;
    }

    private static String httpGetText(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", BilibiliMusicService.USER_AGENT)
                        .header("Referer", BilibiliMusicService.REFERER)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }

    private static double doubleValue(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0D;
        try {
            return object.get(property).getAsDouble();
        } catch (Throwable ignored) {
            return 0D;
        }
    }
}
