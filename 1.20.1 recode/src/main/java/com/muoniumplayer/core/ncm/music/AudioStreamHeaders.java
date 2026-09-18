package com.muoniumplayer.core.ncm.music;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 实际抓取第三方音频流时，按主机所需的请求头。
 *
 * <p>大多数提供方（网易云、JOOX、QQ）会把 CDN 链接发给任意客户端。Bilibili 的
 * {@code *.bilivideo.com} CDN 是例外：除非请求携带浏览器 User-Agent <em>且</em>
 * bilibili Referer，否则返回 HTTP 403，这就是为什么解析出的链接看似有效、
 * 却在下载播放时失败。</p>
 */
final class AudioStreamHeaders {

    private static final String BROWSER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String BILIBILI_REFERER = "https://www.bilibili.com";

    private AudioStreamHeaders() {
    }

    /** 返回流主机所要求的请求头；不需要请求头时为 {@code null}。 */
    static Map<String, String> forUrl(String url) {
        String host = host(url);
        if (host.isEmpty()) return null;
        if (isBilibiliHost(host)) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", BROWSER_AGENT);
            headers.put("Referer", BILIBILI_REFERER);
            headers.put("Origin", BILIBILI_REFERER);
            return headers;
        }
        return null;
    }

    private static boolean isBilibiliHost(String host) {
        return host.endsWith("bilivideo.com")
                || host.endsWith("bilivideo.cn")
                || host.endsWith("bilibili.com")
                || host.endsWith("akamaized.net");
    }

    private static String host(String url) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) value = value.substring(schemeEnd + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        return value;
    }
}
