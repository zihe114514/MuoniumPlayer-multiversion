package com.muoniumplayer.core.ncm.music;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「后端明明收下了 Cookie，模组却报未接受」的回归测试。
 *
 * <p>2026-10-12 真机：游戏里导入 Cookie 恒报「本机签名后端未接受新账号，当前账号保持
 * 不变」，可后端日志里明明白白记着「Cookie 已更新」。根因是 qs-backend 所有响应都是
 * {@code JSON.stringify(obj, null, 1)} 美化输出——{@code {"ok": true}} 键值对之间有空格
 * ——而 {@code pushCookie} 用裸 {@code contains("\"ok\":true")} 匹配，永远对不上：
 * 推送其实成功了，返回值恒为 false。导入闸门把 false 当「后端拒绝」报给用户。
 * {@code isAvailable()} 为同一原因在 2026-10-11 修过一次（当时连健康检查都恒失败），
 * 这次用本地假后端把推送的几条路径一起钉住。</p>
 *
 * <p>不联网：HttpServer 只绑 127.0.0.1 的临时端口，Base URL 经系统属性指过去，测完还原。</p>
 */
class QishuiBackendPushTest {

    /** 与 server.mjs 的 JSON.stringify(obj, null, 1) 同形：冒号后有空格、一层缩进。 */
    private static final String PRETTY_NO_COOKIE =
            "{\n \"has_cookie\": false,\n \"cookie_len\": 0\n}";
    private static final String PRETTY_HAS_COOKIE =
            "{\n \"has_cookie\": true,\n \"cookie_len\": 430\n}";
    private static final String PRETTY_OK =
            "{\n \"ok\": true,\n \"cookie_len\": 433\n}";
    /** 后端对缺凭据 Cookie 的 400 响应（同样带空格）。 */
    private static final String PRETTY_REJECT =
            "{\n \"ok\": false,\n \"error\": \"Cookie 里没有登录凭据\"\n}";

    private HttpServer server;
    private String previousBase;

    /** GET /api/cookie 该回什么：默认「后端没有 Cookie」。 */
    private volatile String getStatus = PRETTY_NO_COOKIE;
    /** POST /api/cookie 该回什么：默认「接受」；置 400 可模拟后端拒绝。 */
    private volatile String postStatus = PRETTY_OK;
    private volatile int postCode = 200;

    private final List<String> pushedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startFakeBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/cookie", this::handleCookie);
        server.start();
        previousBase = System.getProperty("muonium.qishui.backend");
        System.setProperty("muonium.qishui.backend",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeBackend() {
        server.stop(0);
        if (previousBase == null) {
            System.clearProperty("muonium.qishui.backend");
        } else {
            System.setProperty("muonium.qishui.backend", previousBase);
        }
    }

    private void handleCookie(HttpExchange exchange) throws IOException {
        byte[] body;
        int code;
        if ("POST".equals(exchange.getRequestMethod())) {
            pushedBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            body = postStatus.getBytes(StandardCharsets.UTF_8);
            code = postCode;
        } else {
            body = getStatus.getBytes(StandardCharsets.UTF_8);
            code = 200;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    @DisplayName("覆盖推送(force)：后端美化 JSON 的 ok:true 必须被认作成功")
    void forcePushAcceptsPrettyJsonSuccess() throws Exception {
        assertTrue(QishuiBackend.pushCookie("sessionid_ss=abc; uid_tt_ss=def", true),
                "后端已回 {\"ok\": true}，推送却被判为失败——真机「后端未接受新账号」误报的来源");
        assertEquals(1, pushedBodies.size());
        // Gson 默认把 = 等字符转义成 \u003d，后端 JSON.parse 会还原，所以这里只断言字段在。
        assertTrue(pushedBodies.get(0).contains("\"cookie\":\"sessionid_ss"),
                "推送体里必须带着新 Cookie 本体");
        assertTrue(pushedBodies.get(0).contains("\"source\":\"mod-pairing\""),
                "推送要自报来源，后端日志靠它区分模组与登录工具");
    }

    @Test
    @DisplayName("配对推送(force=false)：后端缺 Cookie 时先查状态再落笔，状态判定同样容忍空格")
    void pairingPushChecksStatusThenPosts() throws Exception {
        assertTrue(QishuiBackend.pushCookie("sessionid_ss=abc", false),
                "后端缺 Cookie 时 force=false 应正常落笔——GET 状态也是美化 JSON，同样不能裸匹配");
        assertEquals(1, pushedBodies.size());
    }

    @Test
    @DisplayName("配对推送：后端已有 Cookie 时绝不覆盖——守卫本身也要能读懂美化 JSON")
    void pairingPushSkipsWhenBackendAlreadyHasCookie() throws Exception {
        getStatus = PRETTY_HAS_COOKIE;
        assertFalse(QishuiBackend.pushCookie("sessionid_ss=abc", false),
                "后端已有 Cookie 时 force=false 必须跳过（登录工具写入的凭据不能被模组顶掉）");
        assertTrue(pushedBodies.isEmpty(), "不该发出任何 POST");
    }

    @Test
    @DisplayName("后端 400 拒绝时推送判为失败")
    void rejectedPushReturnsFalse() throws Exception {
        postCode = 400;
        postStatus = PRETTY_REJECT;
        assertFalse(QishuiBackend.pushCookie("passport_csrf_token=x", true),
                "真拒绝（缺凭据被 400）必须如实报失败，不能和误报混为一谈");
    }
}
