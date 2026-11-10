package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * LRCLIB 兜底歌词源——镜像路径专用（外部播放器那首歌在曲库匹配不到时）。
 *
 * <p>为什么是它：LRCLIB（lrclib.net）免费、无 key、无账号，读接口只有宽松的限流；
 * 存的是逐行时间戳的 LRC（正好是我们逐字链路的下限形态——整行一个时间戳，
 * 行内没有逐字标签也能滚）。曲库三平台（网易云/酷狗/B站）搜不到的歌——小众
 *歌曲、外语歌、播客段落——这里有可观的命中率，Lyricify 系与 myrics-overlay
 * 都拿它当主力或兜底。刻意只做镜像路径：内部播放的歌词链路已经四层深，
 * 不缺这一个源，更不能让这个免费接口背上主链路的流量。</p>
 *
 * <p>匹配纪律与曲库那条一致：先精确端点（track+artist+album+duration，LRCLIB
 * 对 duration 要求 ±2 秒内），命中即可信；退到 search 端点时要过「时长吻合 +
 * 歌名一致」两道关，宁可没词也不配错词。逐行 LRC 解析交给
 * {@link LyricParser#parseLyricText}——它本来就吃 LRC 文本。</p>
 */
final class LrclibLyricsSource {

    private static final String API = "https://lrclib.net/api";
    /** 标识头是 LRCLIB 的礼貌要求：限流时它按 UA 放行/退避。 */
    private static final String USER_AGENT = "MuoniumPlayer/1.0 (Minecraft mod; +https://github.com/muoniumplayer)";

    private LrclibLyricsSource() {
    }

    /**
     * 拉一首的逐行同步歌词。会联网，<b>只能在后台线程调用</b>。
     *
     * @return 解好的歌词行；确定没有时返回 {@code null}（与 ExternalTrackMatcher
     *         的 null 约定同族：调用方按「无词」处理并记负缓存）
     */
    static List<LyricLine> resolve(String title, String artist, String album, long durationMs) {
        if (title == null || title.trim().isEmpty()) return null;
        String cleanTitle = title.trim();
        String cleanArtist = artist == null ? "" : artist.trim();
        String cleanAlbum = album == null ? "" : album.trim();
        try {
            // 第一选择：精确端点（四元组全给）。命中即可信，不再过 pick。
            if (!cleanArtist.isEmpty() && durationMs > 0L) {
                String url = API + "/get?track_name=" + urlEncode(cleanTitle)
                        + "&artist_name=" + urlEncode(cleanArtist)
                        + "&album_name=" + urlEncode(cleanAlbum)
                        + "&duration=" + (durationMs / 1000L);
                List<LyricLine> lines = fetchAndParse(url);
                if (lines != null && !lines.isEmpty()) return lines;
            }
            // 第二选择：search 端点（纯文本检索），结果要过时长与歌名两道关。
            String url = API + "/search?track_name=" + urlEncode(cleanTitle)
                    + (cleanArtist.isEmpty() ? "" : "&artist_name=" + urlEncode(cleanArtist));
            return searchPick(url, cleanTitle, durationMs);
        } catch (Throwable failure) {
            // 兜底源任何失败都只表现为「没词」，不进日志刷屏——主链路才是故事的主角。
            return null;
        }
    }

    /** search 结果里挑「同一首」：歌名一致 + 时长 ±2 秒（LRCLIB 自己的匹配精度）。 */
    private static List<LyricLine> searchPick(String url, String wantedTitle, long durationMs) {
        String wanted = wantedTitle.toLowerCase(Locale.ROOT).trim();
        JsonArray array = fetchArray(url);
        if (array == null) return null;
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (item == null || !item.isJsonObject()) continue;
            JsonObject track = item.getAsJsonObject();
            String trackName = text(track, "trackName");
            if (!trackName.toLowerCase(Locale.ROOT).trim().equals(wanted)) continue;
            long dur = (long) (decimal(track, "duration") * 1000.0);
            if (durationMs > 0L && Math.abs(dur - durationMs) > 2_000L) continue;
            // syncedLyrics 优先；没有逐行就退纯文本（仍能显示，只是不滚）。
            String lyrics = text(track, "syncedLyrics");
            if (lyrics.isEmpty()) lyrics = text(track, "plainLyrics");
            if (lyrics.isEmpty()) continue;
            List<LyricLine> lines = parse(lyrics);
            if (!lines.isEmpty()) return lines;
        }
        return null;
    }

    /** 精确端点（/get）：响应是单个对象；404 或没 synced 都算没词。 */
    private static List<LyricLine> fetchAndParse(String url) {
        JsonObject track = fetchObject(url);
        if (track == null) return null;
        String lyrics = text(track, "syncedLyrics");
        if (lyrics.isEmpty()) lyrics = text(track, "plainLyrics");
        if (lyrics.isEmpty()) return null;
        List<LyricLine> lines = parse(lyrics);
        return lines.isEmpty() ? null : lines;
    }

    /** LRC 文本 → LyricLine。复用全屏歌词页那个解析器（吃 LRC/增强 LRC）。 */
    private static List<LyricLine> parse(String lrcText) {
        try {
            return com.muoniumplayer.core.screens.ncm.LyricParser.parseLyricText(lrcText, "", "");
        } catch (Throwable failure) {
            return Collections.emptyList();
        }
    }

    private static JsonObject fetchObject(String url) {
        try {
            @SuppressWarnings("resource")
            java.io.InputStream stream = HttpUtils.downloadStream(url, 2);
            if (stream == null) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(4096);
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                String body = sb.toString().trim();
                if (body.isEmpty() || !body.startsWith("{")) return null;
                return JsonParser.parseString(body).getAsJsonObject();
            }
        } catch (Throwable failure) {
            return null;
        }
    }

    private static JsonArray fetchArray(String url) {
        try {
            @SuppressWarnings("resource")
            java.io.InputStream stream = HttpUtils.downloadStream(url, 2);
            if (stream == null) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(4096);
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                String body = sb.toString().trim();
                if (body.isEmpty() || !body.startsWith("[")) return null;
                return JsonParser.parseString(body).getAsJsonArray();
            }
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String text(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static double decimal(JsonObject root, String key) {
        try {
            JsonElement value = root.get(key);
            return value == null || value.isJsonNull() ? 0d : value.getAsDouble();
        } catch (Throwable ignored) {
            return 0d;
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Throwable failure) {
            return value;
        }
    }
}
