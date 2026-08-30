package com.muoniumplayer.party;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户粘贴的任何东西还原成网易云歌曲 id。
 *
 * <p>覆盖网易云真正会产出的分享格式：桌面端链接（{@code music.163.com/song?id=}）、
 * hash 路由（{@code /#/song?id=}）、移动端链接（{@code y.music.163.com/m/song?id=}）、
 * 163cn.tv 短链（只读一次重定向头，不下载页面）、带链接的整段分享文案，以及纯数字 id。
 */
public final class MpLink {

    private MpLink() {
    }

    private static final Pattern ID_PARAM = Pattern.compile("[?&#/]id=(\\d{3,})");
    private static final Pattern SONG_PATH = Pattern.compile("song/(\\d{3,})");
    private static final Pattern BARE_ID = Pattern.compile("^\\s*(\\d{5,})\\s*$");
    private static final Pattern SHORT_LINK =
            Pattern.compile("(https?://)?163cn\\.tv/[A-Za-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_URL = Pattern.compile("https?://[\\w./?=&#%\\-]+");

    /** @return 歌曲 id；文本里没有时返回 0 */
    public static long parseId(String text) {
        if (text == null) return 0L;
        String s = text.trim();
        if (s.isEmpty()) return 0L;

        Matcher bare = BARE_ID.matcher(s);
        if (bare.matches()) return parseLong(bare.group(1));

        Matcher id = ID_PARAM.matcher(s);
        if (id.find()) return parseLong(id.group(1));

        Matcher path = SONG_PATH.matcher(s);
        if (path.find()) return parseLong(path.group(1));

        return 0L;
    }

    /**
     * 解析分享文案，必要时跟一次 163cn.tv 短链重定向。
     *
     * <p>会走网络，必须在后台线程调用。
     */
    public static long resolveId(String text) {
        long direct = parseId(text);
        if (direct > 0) return direct;

        String shortUrl = firstMatch(SHORT_LINK, text);
        if (shortUrl == null) shortUrl = firstMatch(ANY_URL, text);
        if (shortUrl == null) return 0L;

        String location = shortUrl.startsWith("http") ? shortUrl : "https://" + shortUrl;
        long id = 0L;
        // 短链可能连跳（短链 → m.163 → 歌曲页）。
        for (int hop = 0; hop < 4 && id == 0L && location != null; hop++) {
            location = redirectOf(location);
            id = parseId(location);
        }
        return id;
    }

    private static String redirectOf(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36");
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            // 200 说明这已经是终点，直接拿请求 URL 本身去解析。
            return code >= 300 && code < 400 && location != null ? location : url;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstMatch(Pattern p, String text) {
        if (text == null) return null;
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
