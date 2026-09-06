package com.muoniumplayer.core.ncm.lxsource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * {@code lx.request} 背后的 HTTP 实现。
 *
 * <p>行为对齐 lx-music-desktop：那边用的是 needle，而 needle 的两个默认值直接决定脚本能不能用，
 * 必须照抄 ——</p>
 * <ul>
 *   <li><b>不跟随重定向</b>（needle 的 {@code follow_max} 默认 0）。很多取流脚本就是靠读 302 的
 *       {@code location} 拿最终直链的，自动跟随会让它们拿到一坨音频字节而不是链接。</li>
 *   <li><b>不主动声明 gzip</b>，但服务端真回了 {@code content-encoding} 就要解开。</li>
 * </ul>
 *
 * <p>响应体固定按 UTF-8 解成字符串（Node 的 {@code Buffer.toString()} 默认值），
 * GBK 接口会乱码 —— 上游同样如此，脚本作者是按上游的表现写的。</p>
 */
public final class LxHttp {

    /** needle 的 {@code response_timeout} 上限，脚本传再大也按这个夹。 */
    public static final int MAX_TIMEOUT_MS = 60_000;

    private LxHttp() {
    }

    public static final class Response {
        public int status;
        public String message = "";
        /** 已经拼成 JSON 文本的响应头，交给 JS 侧 {@code JSON.parse}，省掉一层对象映射。 */
        public String headersJson = "{}";
        public byte[] body = new byte[0];
    }

    /** 回调只会被调用一次：要么 error 非空，要么 response 非空。 */
    public interface Sink {
        void finished(String error, Response response);
    }

    /** 脚本手里的「取消函数」拿到的就是这个。 */
    public static final class Call {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            HttpURLConnection open = connection;
            if (open != null) open.disconnect();
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    public static Call send(final String method, final String url, final Map<String, String> headers,
                            final byte[] body, final int timeoutMs, Executor pool, final Sink sink) {
        final Call call = new Call();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Response response = new Response();
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    call.connection = connection;
                    if (call.isCancelled()) throw new IOException("Cancel request");

                    int timeout = timeoutMs > 0 ? Math.min(timeoutMs, MAX_TIMEOUT_MS) : MAX_TIMEOUT_MS;
                    connection.setConnectTimeout(timeout);
                    connection.setReadTimeout(timeout);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestMethod(method == null ? "GET" : method.toUpperCase(Locale.ROOT));
                    if (headers != null) {
                        for (Map.Entry<String, String> header : headers.entrySet()) {
                            if (header.getKey() == null || header.getValue() == null) continue;
                            connection.setRequestProperty(header.getKey(), header.getValue());
                        }
                    }
                    if (body != null && body.length > 0) {
                        connection.setDoOutput(true);
                        OutputStream out = connection.getOutputStream();
                        try {
                            out.write(body);
                        } finally {
                            out.close();
                        }
                    }

                    response.status = connection.getResponseCode();
                    String statusMessage = connection.getResponseMessage();
                    response.message = statusMessage == null ? "" : statusMessage;
                    response.headersJson = headersToJson(connection.getHeaderFields());
                    response.body = readBody(connection);
                    if (call.isCancelled()) throw new IOException("Cancel request");
                    sink.finished(null, response);
                } catch (Throwable failure) {
                    String message = failure.getMessage();
                    sink.finished(message == null || message.isEmpty()
                            ? failure.getClass().getSimpleName() : message, null);
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        });
        return call;
    }

    /** 4xx/5xx 也要把响应体交给脚本：接口把错误码写在 JSON 里是常态。 */
    private static byte[] readBody(HttpURLConnection connection) throws IOException {
        InputStream raw = null;
        try {
            try {
                raw = connection.getInputStream();
            } catch (IOException errorStatus) {
                raw = connection.getErrorStream();
            }
            if (raw == null) return new byte[0];
            String encoding = connection.getContentEncoding();
            if (encoding != null) {
                String normalized = encoding.trim().toLowerCase(Locale.ROOT);
                if (normalized.contains("gzip")) raw = new GZIPInputStream(raw);
                else if (normalized.contains("deflate")) raw = new InflaterInputStream(raw);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = raw.read(chunk)) > 0) {
                out.write(chunk, 0, read);
                // 音频直链有可能被脚本误当成 JSON 接口来请求，别让一次失误把内存吃光。
                if (out.size() > 8 * 1024 * 1024) break;
            }
            return out.toByteArray();
        } finally {
            if (raw != null) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已经读到的字节。
                }
            }
        }
    }

    /**
     * 响应头按 Node 的规则拼：键小写，{@code set-cookie} 保留数组，其余同名头用 ", " 连接。
     */
    private static String headersToJson(Map<String, List<String>> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (fields != null) {
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                String key = entry.getKey();
                // HttpURLConnection 把状态行放在 null 键下，那不是响应头。
                if (key == null) continue;
                List<String> values = entry.getValue() == null ? new ArrayList<String>() : entry.getValue();
                if (!first) sb.append(',');
                first = false;
                quote(sb, key.toLowerCase(Locale.ROOT)).append(':');
                if ("set-cookie".equalsIgnoreCase(key)) {
                    sb.append('[');
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) sb.append(',');
                        quote(sb, values.get(i));
                    }
                    sb.append(']');
                } else {
                    StringBuilder joined = new StringBuilder();
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) joined.append(", ");
                        joined.append(values.get(i) == null ? "" : values.get(i));
                    }
                    quote(sb, joined.toString());
                }
            }
        }
        return sb.append('}').toString();
    }

    private static StringBuilder quote(StringBuilder sb, String text) {
        sb.append('"');
        String value = text == null ? "" : text;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"');
    }

    static String utf8(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}