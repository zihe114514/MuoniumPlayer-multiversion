package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Apple Music AMP API 客户端（amp-api.music.apple.com）。
 *
 * <h3>认证双轨</h3>
 * <ul>
 *   <li><b>公开目录</b>（搜索/歌单/专辑/歌词预览）：只带网页端抓来的公开 Bearer
 *       （{@link AppleTokenManager}），无需登录。</li>
 *   <li><b>账号能力</b>（本人资料库歌单、逐词歌词）：同一枚 Bearer 外加
 *       {@code Media-User-Token} 头（用户 cookies 里带来的账号态）。</li>
 * </ul>
 *
 * <h3>容错纪律（与 {@code SpotifyWebApiClient} 同款四条）</h3>
 * <ul>
 *   <li><b>401 → 处理认证 → 重放一次</b>。公开链路 401 = 网页 token 被换，作废重抓再放；
 *       账号链路 401 = media-user-token 失效（cookies 过期），标记账号态失效引导重导。</li>
 *   <li><b>429 尊重 Retry-After</b>（封顶一次，长冷却直接放弃）。</li>
 *   <li><b>5xx 有界重试一次</b>。</li>
 *   <li><b>token 抓取单飞</b>在 {@link AppleTokenManager} 内部，这里不做。</li>
 * </ul>
 *
 * <p>HTTP 层走 {@link AppleHttp}（{@code java.net.http.HttpClient} 封装）：
 * amp-api 拒收 {@code HttpURLConnection} 的请求形态（稳定 401），HttpClient 直连即过；
 * 也不用 {@code HttpUtils}：需要读状态码做分流，与 Spotify 同一个理由。</p>
 */
public final class AppleApiClient {

    public static final String AMP_API_BASE = "https://amp-api.music.apple.com";
    public static final String HOMEPAGE_ORIGIN = "https://music.apple.com";

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    private static final long MAX_RETRY_AFTER_MILLIS = 10_000L;

    private static final Object ACCOUNT_INVALID_LOCK = new Object();

    /** media-user-token 已被远端拒绝（cookies 过期）。设置页读它给「重新导入」提示。 */
    private static volatile boolean accountInvalid;

    // ── 传输层（测试注入点） ─────────────────────────────────────────────────

    public static final class Request {
        public final String url;
        public final String method;
        public final Map<String, String> headers;

        Request(String url, String method, Map<String, String> headers) {
            this.url = url;
            this.method = method;
            this.headers = headers;
        }
    }

    public static final class Response {
        public final int code;
        public final long retryAfterSeconds;
        public final String body;

        public Response(int code, long retryAfterSeconds, String body) {
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
            this.body = body == null ? "" : body;
        }
    }

    public interface Transport {
        Response send(Request request) throws IOException;
    }

    private static volatile Transport transport = AppleApiClient::realSend;

    static Transport transport() {
        return transport;
    }

    static void setTransportForTest(Transport replacement) {
        transport = replacement;
    }

    static void resetTransportForTest() {
        transport = AppleApiClient::realSend;
    }

    // ── 对外入口 ─────────────────────────────────────────────────────────────

    /** 公开目录 GET。返回顶层 JSON（{@code data} / {@code results} / {@code next}）。 */
    public static JsonObject getCatalog(String pathWithQuery) throws IOException {
        return get("GET", AMP_API_BASE + pathWithQuery, false, 0);
    }

    /** 账号能力 GET（{@code /v1/me/...}）。media-user-token 失效抛 {@code reauthRequired} 异常。 */
    public static JsonObject getAccount(String pathWithQuery) throws IOException {
        return get("GET", AMP_API_BASE + pathWithQuery, true, 0);
    }

    /**
     * /v1/me/account 探测：cookies 里的账号态与订阅是否仍然有效。
     *
     * <p>端点与主流下载器的 account-info 同款（{@code /v1/me} 不存在，404 掩盖了
     * 「tokens 其实有效」的真像，E2E 实测踩中）；探测失败的原因写入 {@code reason}。</p>
     */
    public static AccountProbe probeAccount() {
        try {
            JsonObject payload = getAccount("/v1/me/account?meta=subscription&l=zh-Hans-CN");
            markAccountValid();
            boolean active = false;
            if (payload.has("meta") && payload.getAsJsonObject("meta").has("subscription")) {
                active = payload.getAsJsonObject("meta").getAsJsonObject("subscription")
                        .has("active")
                        && payload.getAsJsonObject("meta").getAsJsonObject("subscription")
                                .get("active").isJsonPrimitive()
                        && payload.getAsJsonObject("meta").getAsJsonObject("subscription")
                                .get("active").getAsBoolean();
            }
            return new AccountProbe(true, active, "");
        } catch (AppleApiException exception) {
            String reason = exception.reauthRequired
                    ? "media-user-token 被拒绝（cookies 已过期，请重新导出）"
                    : "AMP API 拒绝（" + exception.getMessage() + "）";
            System.err.println("[Music/Apple] 账号探测失败: " + reason);
            return new AccountProbe(false, false, reason);
        } catch (IOException exception) {
            String reason = "网络连接失败（稍后重试即可，cookies 本身可能没问题）："
                    + exception.getMessage();
            System.err.println("[Music/Apple] 账号探测网络失败: " + exception.getMessage());
            return new AccountProbe(false, false, reason);
        }
    }

    /** JSON 字符串字面量编码（stdin 参数用；只处理 ASCII 侧的转义，Gson 足够时优先 Gson）。 */
    static String jsonQuote(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** 账号探测的三态结果；失败时 {@code reason} 说明归因（网络 / 凭据）。 */
    public static final class AccountProbe {
        /** 账号态本身有效（media-user-token 被接受）。 */
        public final boolean valid;
        /** 该账号有有效的 Apple Music 订阅（完整版播放的硬前提）。 */
        public final boolean subscriptionActive;
        /** 失败归因；成功时为空串。 */
        public final String reason;

        AccountProbe(boolean valid, boolean subscriptionActive, String reason) {
            this.valid = valid;
            this.subscriptionActive = subscriptionActive;
            this.reason = reason;
        }
    }

    public static boolean isAccountInvalid() {
        return accountInvalid;
    }

    public static void markAccountValid() {
        accountInvalid = false;
    }

    private static void markAccountInvalid() {
        accountInvalid = true;
    }

    // ── 请求管线 ─────────────────────────────────────────────────────────────

    private static JsonObject get(String method, String url, boolean account, int attempt)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + AppleTokenManager.current());
        headers.put("Origin", HOMEPAGE_ORIGIN);
        headers.put("Referer", HOMEPAGE_ORIGIN + "/");
        if (account) {
            String mediaToken = AppleConfigStore.mediaUserToken();
            if (mediaToken.isEmpty()) {
                throw new AppleApiException(0, "NOT_LOGGED_IN", true);
            }
            headers.put("Media-User-Token", mediaToken);
        }

        Response response;
        try {
            response = transport().send(new Request(url, method, headers));
        } catch (IOException networkFailure) {
            // 连接级失败（Connection reset / 超时）不是「凭据无效」：CN 到 amp-api 与
            // 苹果首页的 TLS 会被间歇性 reset（实机日志同窗口出现过镜像 R2 的
            // Connection reset）。这类抖动有界重试一次；仍失败把原因如实带出去，
            // 让 UI 能区分「网络抖动稍后再试」与「cookies 失效请重导」。
            if (attempt == 0) {
                sleepQuietly(600L);
                try {
                    response = transport().send(new Request(url, method, headers));
                } catch (IOException secondFailure) {
                    throw new IOException("连接失败（已重试一次）: " + secondFailure.getMessage(),
                            secondFailure);
                }
            } else {
                throw new IOException("连接失败: " + networkFailure.getMessage(), networkFailure);
            }
        }

        if (response.code == 401) {
            if (attempt == 0) {
                if (account) {
                    // cookies 过期不是重抓网页 token 能救的：标记账号态失效，引导重新导入。
                    markAccountInvalid();
                    throw new AppleApiException(401, "MEDIA_USER_TOKEN_REJECTED", true);
                }
                AppleTokenManager.invalidate();
                return get(method, url, account, attempt + 1);
            }
            if (account) markAccountInvalid();
            throw new AppleApiException(401, "UNAUTHORIZED", account);
        }
        if (response.code == 429 && attempt == 0) {
            long waitMillis = response.retryAfterSeconds * 1000L;
            if (waitMillis >= 0 && waitMillis <= MAX_RETRY_AFTER_MILLIS) {
                sleepQuietly(waitMillis + 250L);
                return get(method, url, account, attempt + 1);
            }
        }
        if (response.code >= 500 && attempt == 0) {
            sleepQuietly(400L);
            return get(method, url, account, attempt + 1);
        }

        JsonObject payload = parseBody(response);
        if (response.code < 200 || response.code >= 300) {
            throw new AppleApiException(response.code, errorReason(payload, response.code), false);
        }
        return payload;
    }

    // ── 真实 HTTP ────────────────────────────────────────────────────────────

    private static Response realSend(Request request) throws IOException {
        return AppleHttp.send(request.method, request.url, request.headers, null,
                READ_TIMEOUT_MILLIS);
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

    static String errorReason(JsonObject payload, int code) {
        if (payload != null && payload.has("error")) {
            JsonElement error = payload.get("error");
            if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                return error.getAsJsonObject().get("message").getAsString();
            }
        }
        return "HTTP " + code;
    }

    static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** AMP API 抛错的标准形态；{@code reauthRequired} 为 true 时 UI 引导重新导入 cookies。 */
    public static final class AppleApiException extends RuntimeException {
        public final int statusCode;
        public final String reason;
        public final boolean reauthRequired;

        public AppleApiException(int statusCode, String reason, boolean reauthRequired) {
            super(reason);
            this.statusCode = statusCode;
            this.reason = reason;
            this.reauthRequired = reauthRequired;
        }
    }
}
