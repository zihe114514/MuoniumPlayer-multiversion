package com.muoniumplayer.core.music.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置音源层传输层。自 Cadence（原 music-api-0.1.1.jar，原 jar 的 http 包 包）手翻，
 * 2026-11 吸收进仓。同步 {@code HttpURLConnection} 实现，语义保持与原实现一致：
 *
 * <ul>
 *   <li>连接 10s / 读取 20s 超时；不跟随重定向；缺省 UA 为 Chrome 123 桌面串；</li>
 *   <li>GET/POST query 与 form 均按 UTF-8 urlencode；Cookie 由调用方以 map 传入拼接；</li>
 *   <li>4xx/5xx 不抛异常：读 errorStream，status 与 body 原样交给调用方处置；</li>
 *   <li>{@link Response#getSetCookies()} 从响应头解析 {@code Set-Cookie} 的 name=value
 *       （丢弃属性段），懒解析。</li>
 * </ul>
 */
public final class MuHttp {
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    /** 响应。{@code body} 为 UTF-8 解码结果，{@code bytes} 为原始字节。 */
    public static final class Response {
        private final int status;
        private final String body;
        private final byte[] bytes;
        private final Map<String, List<String>> headers;
        private Map<String, String> setCookies;

        public Response(int status, String body, byte[] bytes, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.bytes = bytes;
            this.headers = headers;
        }

        public int getStatus() { return status; }

        public String getBody() { return body; }

        public byte[] getBytes() { return bytes; }

        public Map<String, List<String>> getHeaders() { return headers; }

        /** 大小写不敏感取首个响应头；不存在返回 null。 */
        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    return values != null && !values.isEmpty() ? values.get(0) : null;
                }
            }
            return null;
        }

        /** 解析 {@code Set-Cookie} 头为 name→value map（丢弃 Path/Domain 等属性段）。 */
        public Map<String, String> getSetCookies() {
            Map<String, String> cached = setCookies;
            if (cached != null) return cached;
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String name = entry.getKey();
                if (name == null || !"set-cookie".equalsIgnoreCase(name)) continue;
                List<String> values = entry.getValue();
                if (values == null) continue;
                for (String raw : values) {
                    String pair = raw.contains(";") ? raw.substring(0, raw.indexOf(';')) : raw;
                    int eq = pair.indexOf('=');
                    if (eq < 0) continue;
                    String cname = pair.substring(0, eq).trim();
                    String cvalue = eq + 1 <= pair.length() ? pair.substring(eq + 1).trim() : "";
                    if (!cname.isEmpty() && !cvalue.isEmpty()) out.put(cname, cvalue);
                }
            }
            setCookies = out;
            return out;
        }
    }

    public Response get(String url, Map<String, String> query, Map<String, String> headers,
                        Map<String, String> cookies) {
        String full = query.isEmpty() ? url : url + '?' + encodeForm(query);
        return send(full, "GET", null, null, headers, cookies);
    }

    public Response postForm(String url, Map<String, String> form, Map<String, String> headers,
                             Map<String, String> cookies) {
        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        return send(url, "POST", body, "application/x-www-form-urlencoded", headers, cookies);
    }

    public Response postJson(String url, String json, Map<String, String> query,
                             Map<String, String> headers, Map<String, String> cookies) {
        String full = query.isEmpty() ? url : url + '?' + encodeForm(query);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return send(full, "POST", body, "application/json", headers, cookies);
    }

    private static Response send(String url, String method, byte[] body, String contentType,
                                 Map<String, String> headers, Map<String, String> cookies) {
        // 宿主调用点允许传 null 表示「无头/无 cookie」（原 jar 的 Kotlin @NotNull 在签名上
        // 禁止 null，手翻版走防御路线：null 视同空 map，上游形状变化时不炸 NPE）。
        Map<String, String> safeHeaders = headers != null ? headers : new LinkedHashMap<>();
        Map<String, String> safeCookies = cookies != null ? cookies : new LinkedHashMap<>();
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (!safeHeaders.containsKey("User-Agent")) {
                conn.setRequestProperty("User-Agent", DEFAULT_UA);
            }
            for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (!safeCookies.isEmpty()) {
                StringBuilder cookie = new StringBuilder();
                for (Map.Entry<String, String> entry : safeCookies.entrySet()) {
                    if (cookie.length() > 0) cookie.append("; ");
                    cookie.append(entry.getKey()).append('=').append(entry.getValue());
                }
                conn.setRequestProperty("Cookie", cookie.toString());
            }
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = conn.getResponseCode();
            byte[] bytes = readBody(conn, status);
            LinkedHashMap<String, List<String>> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                if (entry.getKey() != null) respHeaders.put(entry.getKey(), entry.getValue());
            }
            conn.disconnect();
            return new Response(status, new String(bytes, StandardCharsets.UTF_8), bytes, respHeaders);
        } catch (IOException e) {
            throw new MusicApiException("HTTP 请求失败: " + url, e);
        }
    }

    private static byte[] readBody(HttpURLConnection conn, int status) {
        boolean ok = status >= 200 && status < 400;
        try (InputStream in = ok ? conn.getInputStream() : conn.getErrorStream()) {
            return in != null ? readAll(in) : new byte[0];
        } catch (IOException e) {
            try (InputStream err = conn.getErrorStream()) {
                return err != null ? readAll(err) : new byte[0];
            } catch (IOException ignored) {
                return new byte[0];
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String encodeForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 所有业务 API 共享一个实例即可（无状态，超时/UA 常量）。 */
    public MuHttp() {
    }

    /** 静态工具集合，供业务层复用查询串编码。 */
    public static final class Util {
        private Util() {
        }

        public static String encodeQuery(Map<String, String> params) {
            return encodeForm(params);
        }

        public static Map<String, String> emptyCookies() {
            return Collections.emptyMap();
        }
    }
}
