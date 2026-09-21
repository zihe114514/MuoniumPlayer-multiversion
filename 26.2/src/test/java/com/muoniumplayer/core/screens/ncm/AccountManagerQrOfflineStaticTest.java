package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汽水「二维码下线、只留 Cookie 登录」的静态回归测试。
 *
 * <p>汽水的 Passport 扫码链路坏在上游，账号面板里那张码已经换不来任何凭据，所以界面改成：
 * 二维码整块不渲染、不进页面就自动取码的列表、主按钮直接进 Cookie 页，并写明「需要外部工具
 * （网盘 / QQ群）获取 Cookie」。这些都不是一次改完就能高枕无忧的不变量——{@code showDetail}
 * 里那几个平台条件表达式正是后续接新音源时最容易被顺手改回去的地方，所以在这里钉住。</p>
 *
 * <p>不启动 Minecraft、不联网；只扫 {@link AccountManagerOverlay} 的源码。</p>
 */
class AccountManagerQrOfflineStaticTest {

    private static String accountManagerSource() throws IOException {
        Path dir = ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "screens", "ncm"));
        return new String(Files.readAllBytes(dir.resolve("AccountManagerOverlay.java")), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("qrVisible 排除汽水：未登录也不再摆二维码")
    void qrIsGatedOffForQishui() throws IOException {
        String source = accountManagerSource();
        assertTrue(source.contains("final boolean qishuiCookieOnly = platform == MusicPlatform.QISHUI;"),
                "缺少汽水专属开关");
        assertTrue(source.contains("(!loggedIn || addAccountByQr) && !qishuiCookieOnly"),
                "qrVisible 没有把汽水排除——二维码会重新出现");
    }

    @Test
    @DisplayName("进页面自动取码的平台列表里没有汽水")
    void autoStartQrSkipsQishui() throws IOException {
        String source = accountManagerSource();
        int at = source.indexOf("startQrLogin(platform, token, addAccountByQr);");
        assertTrue(at > 0, "找不到 showDetail 末尾的自动取码调用");
        String condition = source.substring(Math.max(0, at - 400), at);
        assertFalse(condition.contains("MusicPlatform.QISHUI"),
                "汽水仍在自动取码列表里：进页面就会去拉一张扫不出登录的二维码");
    }

    @Test
    @DisplayName("未登录汽水的文案是外部工具说明，不再引导扫码")
    void copyPointsAtExternalTool() throws IOException {
        String source = accountManagerSource();
        assertTrue(source.contains("汽水音乐登录需要外部工具"), "详情页缺少外部工具说明");
        assertTrue(source.contains("网盘"), "说明里没有给出获取工具的去处（网盘）");
        assertTrue(source.contains("QQ 群") || source.contains("QQ群"), "说明里没有给出获取工具的去处（QQ群）");
        assertFalse(source.contains("重新扫码，或导入"), "Cookie 过期提示仍在引导重新扫码");
    }

    @Test
    @DisplayName("汽水未登录时主按钮是 Cookie 登录，点了不再触发取码")
    void primaryButtonGoesToCookiePage() throws IOException {
        String source = accountManagerSource();
        assertTrue(source.contains("qishuiCookie ? \"Cookie 登录\""), "主按钮文案没有按汽水分支走");
        int at = source.indexOf("final boolean qishuiCookie");
        int fallback = source.indexOf("startQrLogin(platform, pageGeneration.get());", at);
        assertTrue(at > 0 && fallback > at, "找不到 primaryAction 的汽水分支");
        String handler = source.substring(at, fallback);
        // 兜住 if/else 链的顺序：Cookie 分支必须排在「!requestRunning → startQrLogin」兜底之前，
        // 否则点主按钮仍会先走取码。
        int cookie = handler.indexOf("showQishuiCookieLogin()");
        assertTrue(cookie >= 0, "汽水主按钮没有接进 Cookie 页");
        assertTrue(handler.indexOf("else if (!requestRunning)") > cookie,
                "分支顺序颠倒：兜底的 startQrLogin 抢在了 Cookie 分支前面");
    }
}
