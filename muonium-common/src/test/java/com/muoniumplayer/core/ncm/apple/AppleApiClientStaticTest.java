package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apple AMP API 客户端的容错纪律回归；可编程假 transport，零网络零磁盘。
 *
 * <p>与 Spotify 那份同族，差异只有认证模型：公开链路 401 重抓网页 token（这里用假
 * token 起步，重抓路径不打网络 —— 401 的公开分支在测试里只会打网络，因此只测账号
 * 分支的 401 与公开链路的 429/5xx）。</p>
 */
class AppleApiClientStaticTest {

    /** 可编程 transport：按请求 URL 形状决定回什么，并记录每一次收到的请求。 */
    private static final class FakeTransport implements AppleApiClient.Transport {
        final List<AppleApiClient.Request> seen = Collections.synchronizedList(new ArrayList<>());
        final List<AppleApiClient.Response> script = new ArrayList<>();
        final AtomicInteger publicCalls = new AtomicInteger();
        final AtomicInteger accountCalls = new AtomicInteger();

        @Override
        public AppleApiClient.Response send(AppleApiClient.Request request) {
            seen.add(request);
            if (request.url.contains("/v1/me")) accountCalls.incrementAndGet();
            else publicCalls.incrementAndGet();
            synchronized (script) {
                if (script.isEmpty()) {
                    throw new AssertionError("脚本耗尽：" + request.url);
                }
                return script.remove(0);
            }
        }

        void enqueue(int code, String body) {
            script.add(new AppleApiClient.Response(code, -1, body));
        }

        void enqueue(int code, long retryAfterSeconds, String body) {
            script.add(new AppleApiClient.Response(code, retryAfterSeconds, body));
        }

        AppleApiClient.Request lastRequest() {
            return seen.get(seen.size() - 1);
        }
    }

    private FakeTransport transport;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        AppleApiClient.setTransportForTest(transport);
        AppleTokenManager.putForTest("eyJhFakeFakeFakeFakeFakeFakeFakeFake", 0L);
        AppleConfigStore.putForTest("media-token-test", "cn", true, true);
        AppleApiClient.markAccountValid();
    }

    @AfterEach
    void tearDown() {
        AppleApiClient.resetTransportForTest();
        AppleTokenManager.clearForTest();
        AppleConfigStore.clearForTest();
        AppleApiClient.markAccountValid();
    }

    @Test
    @DisplayName("公开请求带 Bearer + Origin/Referer；账号请求额外带 Media-User-Token")
    void headersAreCorrect() throws IOException {
        transport.enqueue(200, "{\"data\": []}");
        transport.enqueue(200, "{\"data\": []}");
        AppleApiClient.getCatalog("/v1/catalog/cn/search?term=x");
        AppleApiClient.getAccount("/v1/me/library/playlists?limit=1");

        AppleApiClient.Request first = transport.seen.get(0);
        assertEquals("Bearer eyJhFakeFakeFakeFakeFakeFakeFakeFake",
                first.headers.get("Authorization"), "公开请求带网页端抓的 Bearer");
        assertEquals(AppleApiClient.HOMEPAGE_ORIGIN, first.headers.get("Origin"),
                "AMP 要求 Origin 伪装成网页播放器");
        assertFalse(first.headers.containsKey("Media-User-Token"),
                "公开请求绝不携带账号态");

        AppleApiClient.Request second = transport.seen.get(1);
        assertEquals("media-token-test", second.headers.get("Media-User-Token"),
                "账号请求把 cookies 里的 media-user-token 放进头");
    }

    @Test
    @DisplayName("未登录调账号端点：NOT_LOGGED_IN + reauthRequired，不打网络")
    void accountWithoutTokenFailsFast() {
        AppleConfigStore.putForTest("", "cn", true, true);
        AppleApiClient.AppleApiException exception = assertThrows(
                AppleApiClient.AppleApiException.class,
                () -> AppleApiClient.getAccount("/v1/me"));
        assertTrue(exception.reauthRequired);
        assertEquals(0, transport.seen.size(), "没凭据就别出门");
    }

    @Test
    @DisplayName("账号链路 401：标记账号失效并抛 reauthRequired（cookies 过期的归因）")
    void account401MarksAccountInvalid() {
        transport.enqueue(401, "{}");
        AppleApiClient.AppleApiException exception = assertThrows(
                AppleApiClient.AppleApiException.class,
                () -> AppleApiClient.getAccount("/v1/me/library/playlists"));
        assertTrue(exception.reauthRequired, "401 在账号链路上就是「重新导出 cookies」的信号");
        assertTrue(AppleApiClient.isAccountInvalid());
        assertEquals(1, transport.seen.size(), "cookies 过期不是重抓网页 token 能救的，不重放");
    }

    @Test
    @DisplayName("429 尊重 Retry-After 并重放一次；成功响应照常返回")
    void rateLimitRetriesOnce() throws IOException {
        transport.enqueue(429, 0, "{}");
        transport.enqueue(200, "{\"data\": []}");
        JsonObject payload = AppleApiClient.getCatalog("/v1/catalog/cn/search?term=x");
        assertEquals(2, transport.publicCalls.get(), "首击 429 + 重放一次 = 恰好两次");
        assertEquals(2, transport.seen.size());
        assertTrue(payload.has("data"));
    }

    @Test
    @DisplayName("5xx 有界重试一次；连吃两次则如实抛出")
    void serverErrorRetriesOnce() throws IOException {
        transport.enqueue(500, "{}");
        transport.enqueue(200, "{\"data\": []}");
        AppleApiClient.getCatalog("/v1/catalog/cn/search?term=x");
        assertEquals(2, transport.publicCalls.get());

        FakeTransport fatal = new FakeTransport();
        AppleApiClient.setTransportForTest(fatal);
        fatal.enqueue(502, "{}");
        fatal.enqueue(502, "{}");
        assertThrows(AppleApiClient.AppleApiException.class,
                () -> AppleApiClient.getCatalog("/v1/catalog/cn/search?term=x"));
        assertEquals(2, fatal.publicCalls.get(), "第三次不该出门");
    }

    @Test
    @DisplayName("非 2xx 的错误体带 message 时如实抛出")
    void errorBodySurfacesMessage() {
        transport.enqueue(403, "{\"error\": {\"message\": \"insufficient permissions\"}}");
        AppleApiClient.AppleApiException exception = assertThrows(
                AppleApiClient.AppleApiException.class,
                () -> AppleApiClient.getCatalog("/v1/catalog/cn/search?term=x"));
        assertFalse(exception.reauthRequired);
        assertEquals(403, exception.statusCode);
        assertTrue(exception.getMessage().contains("insufficient permissions"));
    }

    @Test
    @DisplayName("PACK 占位语义：sha256 为空 = 整包未发布，常量与镜像链自洽")
    void companionPackPlaceholders() {
        assertTrue(AppleCompanionDownload.PACK.fileName().endsWith(".zip"));
        assertTrue(AppleCompanionDownload.EXECUTABLE_ENTRY.endsWith("gamdl.exe"),
                "整包入口必须是 gamdl.exe（AppleGamdlBridge 与验证器都认它）");
        assertFalse(AppleCompanionDownload.MIRRORS.isEmpty(), "镜像链不能为空");
        for (String mirror : AppleCompanionDownload.MIRRORS) {
            assertTrue(mirror.endsWith("apple-companion/"),
                    "镜像路径必须落在 apple-companion/ 目录: " + mirror);
        }
    }
}
