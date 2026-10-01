package com.muoniumplayer.party;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 「这条直链实际交付多少字节」的最小探针：一次 HEAD 拿 {@code Content-Length}。
 *
 * <p>它只为一件事存在：一起听房间里<b>共用同一条解析出来的直链</b>，而非会员账号交回来的
 * 30/60 秒试听片段，网易云会在响应里自报「这条链接实际服务了多长」，酷狗 / QQ / LX 脚本这几路
 * 都不报。房间于是对这几路完全没有试听检测 —— 一次 HEAD 就能把它补上一半，代价是几 KB 的流量
 * 与最多一秒多的等待，而代解析本来就跑在后台线程上。</p>
 *
 * <p><b>拿不到就当没有。</b>本类没有任何「失败即坏」的出口：连不上、非 2xx、不给长度、
 * 预算用完、URL 不成形，一律返回 {@code -1}，调用方据此报「长度未知」，服务端按放行处理。
 * 一只半瞎的眼睛总好过没有，但绝不能因为估不出长度就把一条本来能播的链接判死 ——
 * 那才是整房白等一轮再跳过一首歌。</p>
 *
 * <p>不引宿主那套 {@code HttpUtils}：它返回的是 {@code InputStream}，读不到响应头里的长度，
 * 而 {@code AudioStreamHeaders} 是 {@code core.ncm.music} 的包内类。这里只要一个数，
 * 就用 JDK 自己那一层，顺带让本类可以在没有 Minecraft 的进程里跑（单元测试与工装都能碰）。</p>
 */
public final class MpStreamEstimate {

    private MpStreamEstimate() {
    }

    /**
     * 探测用的 UA。B 站那类按 UA 拦人的 CDN 需要浏览器串，其余主机无视它；
     * Java 默认的 {@code Java/17} 反而更容易被 CDN 直接掐掉。
     */
    private static final String PROBE_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    /** 最多跟几跳重定向；签名链接常见一跳，再多就是在赌这条链还活着。 */
    private static final int MAX_HOPS = 3;

    /**
     * 一次探测最少留多少时间；预算比这还小等于每次都失败。
     *
     * <p>公开它是因为调用方也要按这个数<b>提前收手</b>：层窗只剩两三百毫秒时，
     * 连发都不该发，直接报「长度未知」—— 探测只是给试听检测多加一只眼睛，
     * 不该把代解析那一层的窗吃光。
     */
    public static final long MIN_BUDGET_MS = 300L;

    /**
     * 问一次 HEAD 要字节数。
     *
     * @param budgetMs 整次探测的预算（连接 + 读响应头），到点就放弃并返回 {@code -1}。
     *                 调用方是代解析那一层，它头上压着服务端的层窗，所以这里必须是「一次上界」
     *                 而不是「每次最多 N 秒 × 跳数」—— 预算是<b>整个循环</b>的，逐跳只花剩下的一半。
     * @return 字节数；任何拿不准的情况都返回 {@code -1}
     */
    public static long headBytes(String url, long budgetMs) {
        String current = url == null ? "" : url.trim();
        if (!isHttp(current)) return -1L;
        long start = System.currentTimeMillis();
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            long left = budgetMs - (System.currentTimeMillis() - start);
            if (left < MIN_BUDGET_MS) return -1L;      // 预算见底：别把代解析的窗拖没
            Probe probe = probeOnce(current, (int) Math.max(MIN_BUDGET_MS, left / 2));
            if (probe.bytes >= 0L) return probe.bytes;
            if (probe.redirect == null) return -1L;     // 不是重定向，或者压根没拿到 Location
            current = probe.redirect;
            if (!isHttp(current)) return -1L;           // 跨协议跳转（http→https）不跟
        }
        return -1L;
    }

    /** 一次 HEAD 的结果：要么拿到字节数，要么拿到下一跳地址，要么两个都没有（失败）。 */
    private static final class Probe {
        final long bytes;
        final String redirect;

        Probe(long bytes, String redirect) {
            this.bytes = bytes;
            this.redirect = redirect;
        }
    }

    private static Probe probeOnce(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(false);   // 自己数跳数，别让 JDK 悄悄吃掉预算
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("User-Agent", PROBE_AGENT);
            connection.connect();
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                return new Probe(-1L, location == null || location.trim().isEmpty()
                        ? null : resolve(location, url));
            }
            if (code < 200 || code >= 300) return new Probe(-1L, null);
            long length = connection.getContentLengthLong();
            // 有的主机对 HEAD 不给长度（chunked 或干脆返回 -1），那就是估不出来。
            return new Probe(length > 0L ? length : -1L, null);
        } catch (Throwable failure) {
            return new Probe(-1L, null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** {@code Location} 可能是相对地址，按当前 URL 补全。 */
    private static String resolve(String location, String base) {
        try {
            return new URL(new URL(base), location.trim()).toExternalForm();
        } catch (Throwable malformed) {
            return null;
        }
    }

    private static boolean isHttp(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 字节数 ÷ 码率（kbps）= 毫秒。
     *
     * <p>码率是「这档音质标称多少 kbps」，不是文件里的真实码率，所以这是一<em>估</em>值，
     * 调用方必须把它标成估算值上报（服务端对估算值的试听阈值更宽）。参数不合法一律 0 =
     * 未知，绝不返回一个看起来像样但没根据的数。</p>
     */
    public static long durationMs(long bytes, int kbps) {
        if (bytes <= 0L || kbps <= 0) return 0L;
        // kbps = 每秒 1000 比特 → 毫秒 = 字节 × 8 ÷ kbps。字节数量级到几百 MB 也远不到 long 上限。
        return bytes * 8L / kbps;
    }
}
