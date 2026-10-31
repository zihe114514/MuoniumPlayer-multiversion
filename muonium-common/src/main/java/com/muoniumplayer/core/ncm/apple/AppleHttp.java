package com.muoniumplayer.core.ncm.apple;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple 专属 HTTP 传输层：{@link java.net.http.HttpClient} 封装。
 *
 * <h2>为什么必须用它、不能用 {@code HttpURLConnection}</h2>
 * <p>amp-api.music.apple.com 拒收 {@code HttpURLConnection} 的请求（稳定 401，无 body；
 * 同一 token、同一头集合换传输即 200）。对照实验逐变量二分（tools/apple-native-probe）
 * 后定位：毒变量是该类的 HTTP 层本身，与 TLS 指纹无关 —— 同一枚默认 JDK TLS（SunJSSE）
 * 走裸 SSLSocket 手写 HTTP/1.1 全部 200，{@link HttpClient}（HTTP/1.1 与 HTTP/2）也全部 200。
 * 结论：Apple 侧识别的是这个 JDK 类发出的请求形态（帧细节/隐藏头），不是 ClientHello。</p>
 *
 * <p>推论：不需要任何 TLS 指纹伪装栈（Conscrypt/BouncyCastle 均不引入），
 * JDK 原生 HttpClient 即可，重点主机走 HTTP/2。</p>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>单例客户端，代理变化时重建（同 {@code AppleProxySupport} 的供给节奏）。</li>
 *   <li>请求头全显式（UA/Origin/Referer），不依赖默认值。</li>
 *   <li>body 上限保护：Apple 的 JSON 响应最大几 MB，超限读断。</li>
 * </ul>
 */
public final class AppleHttp {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** 响应体读取上限（Apple 最大的是 TTML 歌词页，几 MB 量级）。 */
    private static final long MAX_BODY_BYTES = 12L * 1024 * 1024;

    /** 与 {@code AppleTokenManager.Http}、amp_api.py 同款的桌面 Chrome UA。 */
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    private static volatile HttpClient client;
    private static volatile String clientProxySignature = "";

    private AppleHttp() {
    }

    /** 单例获取；代理设置变化时自动重建（签名对比，无锁竞态也只是多建一次）。 */
    static HttpClient client() {
        String proxyHostPort = proxySignature();
        HttpClient current = client;
        if (current == null || !proxyHostPort.equals(clientProxySignature)) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (proxyHostPort != null && !proxyHostPort.isEmpty()) {
                String[] parts = proxyHostPort.split(":");
                builder.proxy(ProxySelector.of(
                        new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))));
            } else {
                builder.proxy(ProxySelector.getDefault());
            }
            current = builder.build();
            client = current;
            clientProxySignature = proxyHostPort;
        }
        return current;
    }

    /** 播放管线(play.itunes/license)复用同一客户端与代理策略。 */
    public static HttpClient clientForPlay() {
        return client();
    }

    private static String proxySignature() {
        try {
            java.net.Proxy proxy = AppleProxySupport.currentProxyOrNull();
            if (proxy == null || proxy.address() == null) return "";
            return String.valueOf(proxy.address());
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 发送 GET，返回字符串 body。非 2xx 不抛（调用方读 status 分流，同 AppleApiClient 纪律）。 */
    public static AppleApiClient.Response get(String url, Map<String, String> headers,
                                              int readTimeoutMillis) throws IOException {
        return send("GET", url, headers, null, readTimeoutMillis);
    }

    /** 发送 JSON POST。 */
    public static AppleApiClient.Response postJson(String url, Map<String, String> headers,
                                                   String jsonBody, int readTimeoutMillis)
            throws IOException {
        return send("POST", url, headers, jsonBody, readTimeoutMillis);
    }

    static AppleApiClient.Response send(String method, String url,
                                        Map<String, String> headers, String body,
                                        int readTimeoutMillis) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(readTimeoutMillis, CONNECT_TIMEOUT_MILLIS)))
                .header("User-Agent", USER_AGENT)
                .GET();
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : body, StandardCharsets.UTF_8));
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        HttpResponse<byte[]> response;
        try {
            response = client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", interrupted);
        } catch (IOException network) {
            throw network;
        }

        byte[] raw = response.body() == null ? new byte[0] : response.body();
        if (raw.length > MAX_BODY_BYTES) {
            throw new IOException("响应体超过上限: " + raw.length);
        }
        long retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(-1L);
        return new AppleApiClient.Response(response.statusCode(), retryAfter,
                new String(raw, StandardCharsets.UTF_8));
    }

    /** 分页/目录请求常用的 query 串拼接（值会 URL 编码）。 */
    public static String query(String... pairs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (i > 0) out.append('&');
            out.append(pairs[i]).append('=')
                    .append(java.net.URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** 供测试：内存替身替换 client 的缝不开——测试走 AppleApiClient.Transport。 */
    static List<String> headerNamesForDiagnostics(Map<String, String> headers) {
        return headers == null ? List.of() : new java.util.ArrayList<>(headers.keySet());
    }

    /** 空 map 常量：无额外头的请求。 */
    static Map<String, String> noHeaders() {
        return new LinkedHashMap<>();
    }
}
