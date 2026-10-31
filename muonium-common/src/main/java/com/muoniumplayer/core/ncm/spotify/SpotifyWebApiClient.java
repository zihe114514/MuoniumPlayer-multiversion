package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 *
 * <h3>四条容错纪律（全部有静测钉住）</h3>
 * <ul>
 *   <li><b>401 → 强制刷新 → 重放一次</b>。access token 一小时一换，游戏开一下午必然撞上
 *       一次边界；撞上时用户不该看到「播放失败」。</li>
 *   <li><b>429 尊重 Retry-After</b>，但只重试一次、等待封顶（长冷却不值得让 UI 卡着）。</li>
 *   <li><b>5xx 有界重试一次</b>。瞬时抖动吞掉，连续失败如实抛出。</li>
 *   <li><b>刷新单飞</b>：多个线程同时发现 token 过期时，只允许一个刷新请求出门，
 *       其余等它落地后复查 —— 没有这层，一次过期会打出 N 个刷新请求，其中 N-1 个
 *       会因为 token 轮换互相作废。</li>
 * </ul>
 *
 * <p>{@code invalid_grant} 是唯一的不可恢复错误：refresh token 已被吊销（用户在
 * Spotify 后台撤了授权、或超过 365 天没用）。此时清掉 token、标记需要重新授权，
 * 让 UI 引导用户再走一遍浏览器流程，而不是反复拿死凭据撞墙。</p>
 *
 * <p>HTTP 层不用 {@code HttpUtils}：那条路在非 2xx 时直接抛、不暴露响应头，而这里的
 * 容错恰恰要读状态码（401/429/5xx 分流）与 {@code Retry-After} 头，错误体里的
 * {@code error} 字段也常是唯一有信息量的东西。</p>
 */
public final class SpotifyWebApiClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    /** Retry-After 的等待封顶：更长的冷却期直接放弃，别让调用线程挂着。 */
    private static final long MAX_RETRY_AFTER_MILLIS = 10_000L;

    private static final Object REFRESH_LOCK = new Object();

    // ── 传输层（测试注入点） ─────────────────────────────────────────────────

    /** 一次 HTTP 请求的完整描述。 */
    public static final class Request {
        public final String url;
        public final String method;
        public final Map<String, String> headers;
        /** application/x-www-form-urlencoded 的请求体；GET/无体请求为 null。已编码。 */
        public final String formBody;
        /** application/json 的请求体（Spotify Connect 的 PUT/POST 端点用）；无体请求为 null。 */
        public final String jsonBody;

        Request(String url, String method, Map<String, String> headers, String formBody) {
            this(url, method, headers, formBody, null);
        }

        Request(String url, String method, Map<String, String> headers,
                String formBody, String jsonBody) {
            this.url = url;
            this.method = method;
            this.headers = headers;
            this.formBody = formBody;
            this.jsonBody = jsonBody;
        }

        /** 本请求要带出去的请求体；null 表示无体。 */
        String body() {
            return jsonBody != null ? jsonBody : formBody;
        }

        String contentType() {
            return jsonBody != null
                    ? "application/json; charset=utf-8"
                    : "application/x-www-form-urlencoded";
        }
    }

    /** 一次 HTTP 响应的最小切片：状态码、重试提示与响应体。 */
    public static final class Response {
        public final int code;
        /** {@code Retry-After} 头（秒）；没有时为 -1。 */
        public final long retryAfterSeconds;
        public final String body;

        public Response(int code, long retryAfterSeconds, String body) {
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
            this.body = body == null ? "" : body;
        }
    }

    /** 传输层抽象：真实实现是 {@link #realSend}，静测替换成假的可编程 transport。 */
    public interface Transport {
        Response send(Request request) throws IOException;
    }

    private static volatile Transport transport = SpotifyWebApiClient::realSend;

    static Transport transport() {
        return transport;
    }

    static void setTransportForTest(Transport replacement) {
        transport = replacement;
    }

    static void resetTransportForTest() {
        transport = SpotifyWebApiClient::realSend;
    }

    // ── 对外入口 ─────────────────────────────────────────────────────────────

    /**
     * 带 Bearer 的 GET（API 的全部读端点）。401 自动刷新并重放一次；429 / 5xx 按上面的纪律重试。
     *
     * @param path 以 {@code /} 开头的 API 路径（如 {@code /search}）
     * @param query 可为 null 的 query 参数（值在此编码）
     */
    public static JsonObject getJson(String path, Map<String, String> query) throws IOException {
        return requestWithAuth("GET", SpotifyConfigStore.API_BASE + path, query, null, null, 0);
    }

    /**
     * 带 Bearer 的 PUT（Spotify Connect 的转移/播放/暂停/seek/音量都是 PUT）。
     * {@code jsonBody} 为 null 时发一个 {@code Content-Length: 0} 的空体。
     */
    public static JsonObject putJson(String path, Map<String, String> query,
                                     JsonObject jsonBody) throws IOException {
        return requestWithAuth("PUT", SpotifyConfigStore.API_BASE + path, query, null,
                jsonBody == null ? null : jsonBody.toString(), 0);
    }

    /** 无体的带 Bearer PUT（{@code /me/player/pause} 这类端点不接受也不需要请求体）。 */
    public static JsonObject put(String path, Map<String, String> query) throws IOException {
        return putJson(path, query, null);
    }

    /** 无体的带 Bearer POST（{@code /me/player/next}、{@code /previous}）。 */
    public static JsonObject post(String path, Map<String, String> query) throws IOException {
        return requestWithAuth("POST", SpotifyConfigStore.API_BASE + path, query, null, null, 0);
    }

    /** 不带认证的 form POST：token 端点（授权码交换与刷新）专用。 */
    public static JsonObject postForm(String url, Map<String, String> form) throws IOException {
        Response response = sendWithBoundedRetry(new Request(url, "POST",
                new LinkedHashMap<>(), formBody(form)), 0);
        JsonObject payload = parseBody(response);
        if (response.code < 200 || response.code >= 300) {
            throw new SpotifyApiException(response.code, errorReason(payload, response.code),
                    "invalid_grant".equals(errorReason(payload, response.code)));
        }
        return payload;
    }

    // ── 认证管线 ─────────────────────────────────────────────────────────────

    private static JsonObject requestWithAuth(String method, String url,
                                              Map<String, String> query, String formBody,
                                              String jsonBody, int attempt) throws IOException {
        SpotifyAuthStore.Session session = SpotifyAuthStore.snapshot();
        if (session.isReauthRequired() && !session.tokenReady()) {
            throw new SpotifyApiException(0, "REAUTH_REQUIRED", true);
        }
        String token = currentAccessToken(session);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        String urlWithQuery = appendQuery(url, query);

        Response response = transport().send(new Request(urlWithQuery, method, headers,
                formBody, jsonBody));

        if (response.code == 401) {
            // access token 失效（过期/被轮换/远端吊销）。强制刷新一次再重放；
            // 重放仍 401 就不是「该刷新了」而是凭据已死，转重新授权。
            if (attempt == 0) {
                invalidateAccessToken();
                return requestWithAuth(method, url, query, formBody, jsonBody, attempt + 1);
            }
            SpotifyAuthStore.markReauthRequired();
            throw new SpotifyApiException(401, "REAUTH_REQUIRED", true);
        }
        if (response.code == 429 && attempt == 0) {
            long waitMillis = retryAfterMillis(response);
            if (waitMillis >= 0 && waitMillis <= MAX_RETRY_AFTER_MILLIS) {
                sleepQuietly(waitMillis + 250L);
                return requestWithAuth(method, url, query, formBody, jsonBody, attempt + 1);
            }
        }
        if (response.code >= 500 && attempt == 0) {
            sleepQuietly(400L);
            return requestWithAuth(method, url, query, formBody, jsonBody, attempt + 1);
        }

        JsonObject payload = parseBody(response);
        if (response.code < 200 || response.code >= 300) {
            throw new SpotifyApiException(response.code,
                    errorReason(payload, response.code), false);
        }
        return payload;
    }

    /**
     * 取一枚此刻可用的 access token；过期就刷新。
     *
     * <p>单飞的结构在这里：先查一遍快照，不够新才进锁；进了锁<b>再查一遍</b> ——
     * 另一个线程可能刚刷完出来。刷新本身只发生在双查都失败之后。</p>
     */
    private static String currentAccessToken(SpotifyAuthStore.Session session) throws IOException {
        if (session.tokenReady()) return session.accessToken;
        if (!session.canRefresh()) {
            throw new SpotifyApiException(0, "NOT_LOGGED_IN", session.isReauthRequired());
        }
        synchronized (REFRESH_LOCK) {
            SpotifyAuthStore.Session latest = SpotifyAuthStore.snapshot();
            if (latest.tokenReady()) return latest.accessToken;
            if (!latest.canRefresh()) {
                throw new SpotifyApiException(0, "NOT_LOGGED_IN", latest.isReauthRequired());
            }
            refreshTokens(latest.refreshToken);
            return SpotifyAuthStore.snapshot().accessToken;
        }
    }

    /** 只把内存里的 access token 作废（401 重放前调），refresh token 不动。 */
    private static void invalidateAccessToken() {
        SpotifyAuthStore.saveRefreshedToken("", 0L, null);
    }

    /**
     * 用 refresh token 换一对新 token。
     *
     * <p>Spotify 刷新成功时<b>可能不回新的 refresh_token</b>；{@link SpotifyAuthStore#saveTokenResponse}
     * 对缺席字段保留旧值，所以这里整份交给它落。</p>
     */
    private static void refreshTokens(String refreshToken) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", SpotifyConfigStore.clientId());
        Response response = sendWithBoundedRetry(new Request(
                SpotifyConfigStore.ACCOUNTS_BASE + SpotifyConfigStore.TOKEN_PATH, "POST",
                new LinkedHashMap<>(), formBody(form)), 0);
        JsonObject payload = parseBody(response);
        if (response.code < 200 || response.code >= 300) {
            String reason = errorReason(payload, response.code);
            if ("invalid_grant".equals(reason)) {
                SpotifyAuthStore.markReauthRequired();
                throw new SpotifyApiException(response.code, "REAUTH_REQUIRED", true);
            }
            throw new SpotifyApiException(response.code, reason, false);
        }
        SpotifyAuthStore.saveTokenResponse(payload);
    }

    /** 429 / 5xx 的有界重试（最多一次）在 form 端点也要有：token 服务一样会抖。 */
    private static Response sendWithBoundedRetry(Request request, int attempt) throws IOException {
        Response response = transport().send(request);
        if (attempt == 0 && (response.code == 429 || response.code >= 500)) {
            long waitMillis = response.code == 429 ? retryAfterMillis(response) : 400L;
            if (response.code != 429 || (waitMillis >= 0 && waitMillis <= MAX_RETRY_AFTER_MILLIS)) {
                sleepQuietly(Math.max(waitMillis, 400L) + 250L);
                return sendWithBoundedRetry(request, attempt + 1);
            }
        }
        return response;
    }

    // ── 真实 HTTP ────────────────────────────────────────────────────────────

    private static Response realSend(Request request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url).openConnection();
        connection.setRequestMethod(request.method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        if (request.headers != null) {
            for (Map.Entry<String, String> entry : request.headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        String body = request.body();
        if (body != null) {
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", request.contentType());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
        } else if (!"GET".equalsIgnoreCase(request.method)
                && !"HEAD".equalsIgnoreCase(request.method)) {
            // 无体的 PUT/POST 也要把 Content-Length: 0 说出去：不声明长度的请求体
            // 有的网关会一直等到超时才肯处理。
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setFixedLengthStreamingMode(0);
            try (OutputStream ignored = connection.getOutputStream()) {
            }
        }
        int code = connection.getResponseCode();
        long retryAfter = -1L;
        String retryHeader = connection.getHeaderField("Retry-After");
        if (retryHeader != null && !retryHeader.trim().isEmpty()) {
            try {
                retryAfter = Long.parseLong(retryHeader.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String stream = readStream(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        return new Response(code, retryAfter, stream);
    }

    private static String readStream(InputStream raw) throws IOException {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } finally {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
        }
        return out.toString();
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    static JsonObject parseBody(Response response) {
        String body = response.body == null ? "" : response.body.trim();
        if (body.isEmpty()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Throwable ignored) {
            return new JsonObject();
        }
    }

    /** 错误体里最有信息量的字段：{@code {"error": "invalid_grant", ...}} 或嵌套的 description。 */
    static String errorReason(JsonObject payload, int code) {
        if (payload != null && payload.has("error")) {
            JsonElement error = payload.get("error");
            if (error.isJsonPrimitive()) return error.getAsString();
            if (error.isJsonObject() && error.getAsJsonObject().has("message")
                    && !error.getAsJsonObject().get("message").isJsonNull()) {
                return error.getAsJsonObject().get("message").getAsString();
            }
        }
        return "HTTP " + code;
    }

    private static long retryAfterMillis(Response response) {
        if (response.retryAfterSeconds < 0) return -1L;
        return response.retryAfterSeconds * 1000L;
    }

    static String appendQuery(String url, Map<String, String> query) {
        if (query == null || query.isEmpty()) return url;
        StringBuilder out = new StringBuilder(url);
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            out.append(first ? '?' : '&');
            first = false;
            out.append(urlEncode(entry.getKey())).append('=')
                    .append(urlEncode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return out.toString();
    }

    static String formBody(Map<String, String> form) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!first) out.append('&');
            first = false;
            out.append(urlEncode(entry.getKey())).append('=')
                    .append(urlEncode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return out.toString();
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            return "";
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** API 抛错的标准形态；{@code reauthRequired} 为 true 时 UI 引导重新授权。 */
    public static final class SpotifyApiException extends RuntimeException {
        public final int statusCode;
        public final String reason;
        public final boolean reauthRequired;

        public SpotifyApiException(int statusCode, String reason, boolean reauthRequired) {
            super(reason);
            this.statusCode = statusCode;
            this.reason = reason;
            this.reauthRequired = reauthRequired;
        }
    }
}
