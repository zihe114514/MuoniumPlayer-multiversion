package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spotify Web API 客户端的容错纪律回归；可编程假 transport，零网络零磁盘。
 *
 * <p>这四条纪律（401 刷新重放、Retry-After、5xx 有界重试、刷新单飞）全是「错了只在
 * 特定边界上咬人」的行为：access token 一小时过期一次、限流几小时撞一次。没有这些
 * 断言，一次「顺手简化重试逻辑」的改动要在很晚之后才被用户报上来。</p>
 */
class SpotifyWebApiClientStaticTest {

    /** 可编程 transport：按请求 URL 形状决定回什么，并记录每一次收到的请求。 */
    private static final class FakeTransport implements SpotifyWebApiClient.Transport {
        final List<SpotifyWebApiClient.Request> seen = Collections.synchronizedList(new ArrayList<>());
        /** 每次 send 按序弹出一个响应；弹完 stick 在最后一个上。 */
        final List<SpotifyWebApiClient.Response> script = new ArrayList<>();
        final AtomicInteger refreshCalls = new AtomicInteger();
        Runnable beforeRefreshResponse;

        SpotifyWebApiClient.Response respond(SpotifyWebApiClient.Request request) {
            if (request.url.contains("/api/token")) {
                refreshCalls.incrementAndGet();
                if (beforeRefreshResponse != null) beforeRefreshResponse.run();
            }
            synchronized (script) {
                if (script.isEmpty()) {
                    throw new AssertionError("脚本耗尽：" + request.url);
                }
                return script.remove(0);
            }
        }

        @Override
        public SpotifyWebApiClient.Response send(SpotifyWebApiClient.Request request) {
            seen.add(request);
            return respond(request);
        }

        void enqueue(int code, String body) {
            script.add(new SpotifyWebApiClient.Response(code, -1, body));
        }

        void enqueue(int code, long retryAfterSeconds, String body) {
            script.add(new SpotifyWebApiClient.Response(code, retryAfterSeconds, body));
        }

        List<SpotifyWebApiClient.Request> apiCalls() {
            List<SpotifyWebApiClient.Request> out = new ArrayList<>();
            for (SpotifyWebApiClient.Request request : seen) {
                if (request.url.contains("/api/token")) continue;
                out.add(request);
            }
            return out;
        }

        SpotifyWebApiClient.Request lastRefresh() {
            SpotifyWebApiClient.Request last = null;
            for (SpotifyWebApiClient.Request request : seen) {
                if (request.url.contains("/api/token")
                        && String.valueOf(request.formBody).contains("refresh_token=rt-")) {
                    last = request;
                }
            }
            return last;
        }
    }

    private FakeTransport transport;

    @AfterEach
    void tearDown() {
        SpotifyWebApiClient.resetTransportForTest();
        SpotifyAuthStore.clearForTest();
        SpotifyConfigStore.clearForTest();
    }

    private void login(long expiresIn) {
        SpotifyConfigStore.putForTest("test-client-id", "US");
        SpotifyAuthStore.putForTest("expired-access", "rt-original",
                System.currentTimeMillis() + expiresIn);
    }

    @Test
    @DisplayName("access token 未过期时直接使用，不发刷新请求")
    void freshTokenSkipsRefresh() throws IOException {
        login(60 * 60 * 1000L);
        transport = new FakeTransport();
        transport.enqueue(200, "{\"items\":[]}");
        SpotifyWebApiClient.setTransportForTest(transport);

        SpotifyWebApiClient.getJson("/me/tracks", null);

        assertEquals(0, transport.refreshCalls.get());
        assertEquals(1, transport.apiCalls().size());
        assertTrue(transport.apiCalls().get(0).headers.get("Authorization").endsWith("expired-access"));
    }

    @Test
    @DisplayName("401 强制刷新并重放一次，重放带上新 token")
    void unauthorizedRefreshesAndReplaysOnce() throws IOException {
        login(60 * 60 * 1000L); // token 本身没过期：401 是被远端拒绝，正是「强制刷新」要救的场景
        transport = new FakeTransport();
        transport.enqueue(401, "{\"error\":\"expired\"}");
        transport.enqueue(200,
                "{\"access_token\":\"new-access\",\"expires_in\":3600}");
        transport.enqueue(200, "{\"display_name\":\"Alice\"}");
        SpotifyWebApiClient.setTransportForTest(transport);

        JsonObject me = SpotifyWebApiClient.getJson("/me", null);

        assertEquals("Alice", me.get("display_name").getAsString());
        // 强制刷新恰好一次、API 调用恰好两次（首次 + 重放）。
        assertEquals(1, transport.refreshCalls.get());
        assertEquals(2, transport.apiCalls().size());
        assertEquals("Bearer new-access",
                transport.apiCalls().get(1).headers.get("Authorization"));
    }

    @Test
    @DisplayName("刷新响应没有轮换 refresh token 时保留旧值")
    void refreshWithoutRotationKeepsOldRefreshToken() throws IOException {
        login(-1000L);
        transport = new FakeTransport();
        transport.enqueue(200, "{\"access_token\":\"new-access\",\"expires_in\":3600}");
        transport.enqueue(200, "{}");
        SpotifyWebApiClient.setTransportForTest(transport);

        SpotifyWebApiClient.getJson("/search", null);

        assertEquals("rt-original",
                SpotifyAuthStore.snapshot().refreshToken,
                "Spotify 的刷新响应不一定带新的 refresh_token，缺席必须保留旧值");
    }

    @Test
    @DisplayName("刷新端点回 invalid_grant：清 token、标记重新授权并抛出")
    void invalidGrantClearsTokensAndMarksReauth() {
        login(-1000L);
        transport = new FakeTransport();
        transport.enqueue(400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad\"}");
        SpotifyWebApiClient.setTransportForTest(transport);

        SpotifyWebApiClient.SpotifyApiException thrown = assertThrows(
                SpotifyWebApiClient.SpotifyApiException.class,
                () -> SpotifyWebApiClient.getJson("/me/tracks", null));
        assertTrue(thrown.reauthRequired);
        assertTrue(SpotifyAuthStore.isReauthRequired());
        assertFalse(SpotifyAuthStore.snapshot().tokenReady());
        assertFalse(SpotifyAuthStore.snapshot().canRefresh(),
                "invalid_grant 意味着 refresh token 已死，必须清掉");
    }

    @Test
    @DisplayName("429 尊重 Retry-After 并只重试一次")
    void rateLimitHonorsRetryAfterOnce() throws Exception {
        login(60 * 60 * 1000L);
        transport = new FakeTransport();
        transport.enqueue(429, 1, "{\"error\":\"slow down\"}");
        transport.enqueue(200, "{\"items\":[]}");
        SpotifyWebApiClient.setTransportForTest(transport);

        long start = System.currentTimeMillis();
        SpotifyWebApiClient.getJson("/me/playlists", null);
        long waited = System.currentTimeMillis() - start;

        assertEquals(2, transport.apiCalls().size());
        assertTrue(waited >= 1000, "必须真的等了 Retry-After 声明的时长（实测 " + waited + "ms）");
    }

    @Test
    @DisplayName("超长 Retry-After 不等待直接报错，5xx 有界重试一次")
    void longCooldownGivesUpAndServerErrorRetriesOnce() {
        login(60 * 60 * 1000L);
        transport = new FakeTransport();
        transport.enqueue(429, 3600, "{\"error\":\"maintenance\"}");
        SpotifyWebApiClient.setTransportForTest(transport);

        SpotifyWebApiClient.SpotifyApiException thrown = assertThrows(
                SpotifyWebApiClient.SpotifyApiException.class,
                () -> SpotifyWebApiClient.getJson("/me/tracks", null));
        assertEquals(429, thrown.statusCode);
        assertEquals(1, transport.apiCalls().size(), "冷却期太长时不能把调用线程挂一小时");

        // 5xx：一次有界重试，第二次仍失败就如实抛出。
        transport.enqueue(503, "{\"error\":\"unavailable\"}");
        transport.enqueue(503, "{\"error\":\"unavailable\"}");
        transport.enqueue(503, "{\"error\":\"unavailable\"}");
        assertEquals(503, assertThrows(SpotifyWebApiClient.SpotifyApiException.class,
                () -> SpotifyWebApiClient.getJson("/me/tracks", null)).statusCode);
        assertEquals(3, transport.apiCalls().size(), "5xx 只重试一次（首次 + 一次）");
    }

    @Test
    @DisplayName("并发撞上过期：刷新单飞，只发一个刷新请求")
    void concurrentExpirySharesOneRefresh() throws Exception {
        login(-1000L);
        transport = new FakeTransport();
        // 刷新请求在路上停一下，把「多线程同时发现过期」这个窗口拉开。
        transport.beforeRefreshResponse = () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        };
        transport.enqueue(200, "{\"access_token\":\"shared-access\",\"expires_in\":3600}");
        transport.enqueue(200, "{\"items\":[]}");
        transport.enqueue(200, "{\"items\":[]}");
        transport.enqueue(200, "{\"items\":[]}");
        transport.enqueue(200, "{\"items\":[]}");
        SpotifyWebApiClient.setTransportForTest(transport);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<JsonObject>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit((Callable<JsonObject>) () ->
                        SpotifyWebApiClient.getJson("/me/tracks", null)));
            }
            for (Future<JsonObject> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, transport.refreshCalls.get(),
                "四个线程同时撞上过期，刷新请求只允许出一个（单飞 + 双查）");
    }

    @Test
    @DisplayName("刷新请求的形状：grant_type/refresh_token/client_id 三件齐全")
    void refreshRequestShape() throws IOException {
        login(-1000L);
        transport = new FakeTransport();
        transport.enqueue(200, "{\"access_token\":\"new-access\",\"expires_in\":3600}");
        transport.enqueue(200, "{}");
        SpotifyWebApiClient.setTransportForTest(transport);

        SpotifyWebApiClient.getJson("/me/tracks", null);

        SpotifyWebApiClient.Request refresh = transport.lastRefresh();
        Map<String, String> form = parseForm(refresh.formBody);
        assertEquals("refresh_token", form.get("grant_type"));
        assertEquals("rt-original", form.get("refresh_token"));
        assertEquals("test-client-id", form.get("client_id"),
                "PKCE 客户端的刷新请求必须带 client_id（没有 secret 可依赖）");
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int assign = pair.indexOf('=');
            if (assign <= 0) continue;
            out.put(urlDecode(pair.substring(0, assign)), urlDecode(pair.substring(assign + 1)));
        }
        return out;
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
