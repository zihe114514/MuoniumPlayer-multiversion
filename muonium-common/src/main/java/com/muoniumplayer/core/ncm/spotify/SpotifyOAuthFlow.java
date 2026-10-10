package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.utils.other.BrowserUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spotify 的 OAuth 2.0 + PKCE 授权流。
 *
 * <p>桌面端没有后端可以保管 client secret，所以走<b>授权码 + PKCE</b>（Spotify 官方为
 * 移动 / 桌面应用指定的那套）：本地起一个一次性 HTTP 服务接住浏览器跳回来的 {@code code}，
 * 用 {@code code_verifier} 去换 token。全程不出现 secret，落盘的只有 token 本身。</p>
 *
 * <p>流程分三步，UI 逐段给反馈：</p>
 * <ol>
 *   <li>{@link #beginLogin()} —— 生成 verifier/challenge/state，起回调服务，返回授权 URL；</li>
 *   <li>用户在浏览器里授权，Spotify 跳回 {@code 127.0.0.1:47478/callback}，服务校验
 *       state 后拿住 code；</li>
 *   <li>后台线程拿 code + verifier 换 token，落盘后再拉一次 {@code /me} 补账号资料，
 *       状态推进到 {@link #DONE}。UI 轮询 {@link #pollStatus()} 画进度。</li>
 * </ol>
 *
 * <p>回调服务是<b>一次性</b>的：收到 code（或用户点了拒绝、或 5 分钟没人回来）就关掉。
 * 它不该在用户整个游戏会话里一直监听一个端口。</p>
 */
public final class SpotifyOAuthFlow {

    /** 授权流当前所处的阶段；UI 按它画提示文案。 */
    public enum Status { IDLE, WAITING_BROWSER, EXCHANGING, DONE, FAILED }

    private static final long CALLBACK_TIMEOUT_MILLIS = 5 * 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Object LOCK = new Object();
    /** 本次授权流的 PKCE verifier；code 换 token 时必须带上同一条。 */
    private static String pendingVerifier = "";
    /** 防 CSRF：浏览器跳回来时 query 里的 state 必须与发出时一致。 */
    private static String pendingState = "";
    private static HttpServer callbackServer;
    private static java.util.Timer watchdog;

    private static volatile Status status = Status.IDLE;
    private static volatile String statusMessage = "";
    private static final AtomicReference<Throwable> lastError = new AtomicReference<>();

    private SpotifyOAuthFlow() {
    }

    public static Status currentStatus() {
        return status;
    }

    public static String statusMessage() {
        return statusMessage;
    }

    /** 每次查询只消费一次的失败详情（UI 弹完就清）。 */
    public static Throwable consumeError() {
        return lastError.getAndSet(null);
    }

    /**
     * 开始一次授权。成功返回要在浏览器里打开的授权 URL；回调服务已经就位。
     *
     * @throws IOException 端口被占（多半是上一次流程卡着没退，或另一个游戏实例同时开授权）
     */
    public static String beginLogin() throws IOException {
        synchronized (LOCK) {
            stopServerLocked();
            byte[] raw = new byte[64];
            RANDOM.nextBytes(raw);
            pendingVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            byte[] stateRaw = new byte[16];
            RANDOM.nextBytes(stateRaw);
            pendingState = Base64.getUrlEncoder().withoutPadding().encodeToString(stateRaw);

            callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", callbackPort()), 0);
            callbackServer.createContext("/callback", SpotifyOAuthFlow::handleCallback);
            // 守护线程：HTTP 处理器里的换 token 请求不该拖住游戏退出。
            callbackServer.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Spotify OAuth Callback");
                thread.setDaemon(true);
                return thread;
            }));
            callbackServer.start();
            scheduleWatchdog();

            status = Status.WAITING_BROWSER;
            statusMessage = "等待浏览器授权…";
            return buildAuthorizeUrl(pendingState, pendingVerifier);
        }
    }

    /** 用户中途关掉授权页后由 UI 调：把回调服务收掉，状态归零。 */
    public static void cancel() {
        synchronized (LOCK) {
            stopServerLocked();
            pendingVerifier = "";
            pendingState = "";
            status = Status.IDLE;
            statusMessage = "";
        }
    }

    // ── 纯函数：授权 URL 与 PKCE ─────────────────────────────────────────────

    /** 拼授权 URL。state/verifier 由调用方持有，换 token 时要原样带回去。 */
    static String buildAuthorizeUrl(String state, String codeVerifier) {
        StringBuilder url = new StringBuilder(SpotifyConfigStore.ACCOUNTS_BASE)
                .append(SpotifyConfigStore.AUTHORIZE_PATH)
                .append("?response_type=code")
                .append("&client_id=").append(urlEncode(SpotifyConfigStore.clientId()))
                .append("&redirect_uri=").append(urlEncode(SpotifyConfigStore.REDIRECT_URI))
                .append("&state=").append(urlEncode(state))
                .append("&code_challenge_method=S256")
                .append("&code_challenge=").append(urlEncode(pkceChallenge(codeVerifier)))
                .append("&scope=").append(urlEncode(SpotifyConfigStore.scope()));
        return url.toString();
    }

    /**
     * PKCE 的 S256 challenge：{@code BASE64URL(SHA256(ASCII(verifier)))}。
     *
     * <p>包成独立纯函数是为了拿 RFC 7636 附录 B 的官方向量做回归——challenge 算错时
     * token 端点只会回一句笼统的 {@code invalid_grant}，查起来没有方向。</p>
     */
    static String pkceChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Throwable throwable) {
            // JVM 不可能没有 SHA-256；真发生了，授权流报失败而不是发一个错误的 challenge。
            throw new IllegalStateException("PKCE challenge 不可用", throwable);
        }
    }

    /** 回调端口独立成常量供诊断文案引用；与 {@link SpotifyConfigStore#REDIRECT_URI} 必须一致。 */
    public static int callbackPort() {
        return 47478;
    }

    // ── 回调服务 ─────────────────────────────────────────────────────────────

    private static void handleCallback(HttpExchange exchange) {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String state = query.get("state");
            // 一次性消费：state 对不上（CSRF 或重放的旧链接）当作完全没见过这次回调。
            boolean stateOk;
            synchronized (LOCK) {
                stateOk = !pendingState.isEmpty() && pendingState.equals(state);
                if (stateOk) {
                    pendingState = "";
                    stopWatchdogLocked();
                }
            }
            if (!stateOk) {
                respond(exchange, 400, "授权回调校验失败（state 不匹配），请回到游戏中重新发起授权。");
                return;
            }

            String error = query.get("error");
            if (error != null && !error.trim().isEmpty()) {
                finishFailed("授权被取消或拒绝：" + error);
                respond(exchange, 200, "授权未完成（" + error + "）。可以回到游戏中重新发起。");
                return;
            }
            String code = query.get("code");
            if (code == null || code.trim().isEmpty()) {
                finishFailed("回调里没有授权码");
                respond(exchange, 400, "回调缺少 code 参数，请回到游戏中重新发起授权。");
                return;
            }

            respond(exchange, 200, "授权成功！回到游戏即可，本页面可以关掉了。");
            // verifier 在提交前先取快照：执行器异步跑起来之后，cancel() 可能已经把它清掉。
            final String authorizationCode = code.trim();
            final String verifier = pendingVerifier;
            synchronized (LOCK) {
                status = Status.EXCHANGING;
                statusMessage = "正在换取 token…";
            }
            // 交给回调服务的守护执行器跑：这一步是两次网络往返（换 token + 拉 /me）。
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Spotify OAuth Exchange");
                thread.setDaemon(true);
                return thread;
            }).submit(() -> exchangeCode(authorizationCode, verifier));
        } catch (Throwable throwable) {
            finishFailed(throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage());
            lastError.set(throwable);
        } finally {
            exchange.close();
        }
    }

    /**
     * 用授权码换 token 并完成登录（落盘凭据 + 拉账号资料）。
     *
     * <p>单独成方法（不起服务、不发浏览器）让静测可以直接对 token 端点的请求形状做断言。</p>
     */
    static void exchangeCode(String code, String codeVerifier) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", SpotifyConfigStore.REDIRECT_URI);
            form.put("client_id", SpotifyConfigStore.clientId());
            form.put("code_verifier", codeVerifier);
            JsonObject payload = SpotifyWebApiClient.postForm(
                    SpotifyConfigStore.ACCOUNTS_BASE + SpotifyConfigStore.TOKEN_PATH, form);
            SpotifyAuthStore.saveTokenResponse(payload);
            fetchProfile();
            synchronized (LOCK) {
                status = Status.DONE;
                statusMessage = "已登录：" + SpotifyAuthStore.snapshot().accountName;
            }
        } catch (Throwable throwable) {
            lastError.set(throwable);
            finishFailed("换 token 失败：" + (throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage()));
        } finally {
            synchronized (LOCK) {
                stopServerLocked();
                pendingVerifier = "";
            }
        }
    }

    /** 换到 token 后拉一次 {@code /me}；失败不影响登录状态本身。 */
    private static void fetchProfile() {
        try {
            JsonObject me = SpotifyWebApiClient.getJson("/me", null);
            String name = me.has("display_name") && !me.get("display_name").isJsonNull()
                    ? me.get("display_name").getAsString() : "";
            String id = me.has("id") && !me.get("id").isJsonNull() ? me.get("id").getAsString() : "";
            String avatar = "";
            if (me.has("images") && me.get("images").isJsonArray() && me.getAsJsonArray("images").size() > 0) {
                com.google.gson.JsonElement first = me.getAsJsonArray("images").get(0);
                if (first != null && first.isJsonObject() && first.getAsJsonObject().has("url")
                        && !first.getAsJsonObject().get("url").isJsonNull()) {
                    avatar = first.getAsJsonObject().get("url").getAsString();
                }
            }
            SpotifyAuthStore.updateProfile(name, id, avatar);
        } catch (Throwable throwable) {
            System.err.println("[Music/Spotify] 账号资料拉取失败（不影响登录）: " + throwable);
        }
    }

    private static void finishFailed(String message) {
        status = Status.FAILED;
        statusMessage = message;
        synchronized (LOCK) {
            stopServerLocked();
        }
    }

    private static void respond(HttpExchange exchange, int code, String message) throws IOException {
        byte[] body = ("<html><body style=\"font-family:sans-serif;background:#121212;color:#fff;"
                + "display:grid;place-items:center;height:100vh\"><p style=\"max-width:32em;text-align:center\">"
                + message + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void scheduleWatchdog() {
        stopWatchdogLocked();
        watchdog = new java.util.Timer("Spotify OAuth Watchdog", true);
        watchdog.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    if (status == Status.WAITING_BROWSER) {
                        stopServerLocked();
                        status = Status.FAILED;
                        statusMessage = "授权超时：五分钟内没有等到浏览器跳回，请重新发起";
                    }
                }
            }
        }, CALLBACK_TIMEOUT_MILLIS);
    }

    private static void stopWatchdogLocked() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    private static void stopServerLocked() {
        stopWatchdogLocked();
        if (callbackServer != null) {
            try {
                callbackServer.stop(0);
            } catch (Throwable ignored) {
            }
            callbackServer = null;
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            int assign = pair.indexOf('=');
            if (assign <= 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, assign), StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(pair.substring(assign + 1), StandardCharsets.UTF_8.name()));
            } catch (UnsupportedEncodingException impossible) {
                return out;
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            return "";
        }
    }
}
