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
 * 汽水扫码「由外部取码工具出码」的静态回归测试。
 *
 * <p>汽水 Passport 的取码与轮询要 {@code a_bogus} / {@code msToken}，只能由官方安全组件在浏览器
 * 渲染进程里生成，Java 侧没有——所以码由外部工具出，模组只管拉起、读状态、收 cookie。由此
 * 得到一组必须钉住的不变量：</p>
 *
 * <ul>
 *   <li>工具在位 → 汽水与别的平台一样摆二维码；<b>只有</b>缺工具才退回只留 Cookie；</li>
 *   <li>缺工具时<b>直接说清楚</b>，绝不回落到那条没有签名的内置请求（它必然被上游拒绝，
 *       现象是「码出来了但永远扫不上」，比直接说缺工具难查得多——上一版 sidecar 就是这么黄的）；</li>
 *   <li>风控（2046）是「人在游戏外的验证窗口里处理」，既不是失败也不是等待扫码。</li>
 * </ul>
 *
 * <p>这些全是 {@code showDetail} / {@code primaryAction} 里几个平台条件表达式的取值，正是后续
 * 接新音源时最容易被顺手改回去的地方。不启动 Minecraft、不联网，只扫源码。</p>
 */
class AccountManagerQishuiLoginStaticTest {

    private static String accountManagerSource() throws IOException {
        Path dir = ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "screens", "ncm"));
        return new String(Files.readAllBytes(dir.resolve("AccountManagerOverlay.java")), StandardCharsets.UTF_8);
    }

    private static String qishuiServiceSource() throws IOException {
        Path dir = ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "ncm", "music"));
        return new String(Files.readAllBytes(dir.resolve("QishuiMusicService.java")), StandardCharsets.UTF_8);
    }

    private static String bridgeSource() throws IOException {
        Path dir = ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "ncm", "music"));
        return new String(Files.readAllBytes(dir.resolve("QishuiLoginBridge.java")), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("工具在位时汽水照常摆二维码，只有缺工具才退回只留 Cookie")
    void qrIsGatedOnBridgeAvailability() throws IOException {
        String source = accountManagerSource();
        assertTrue(source.contains("final boolean qishuiBridgeReady = platform == MusicPlatform.QISHUI"),
                "缺少「外部取码工具在位」这个开关");
        assertTrue(source.contains("&& QishuiLoginBridge.isAvailable()"),
                "开关没有真的去看工具在不在");
        assertTrue(source.contains("final boolean qishuiCookieOnly = platform == MusicPlatform.QISHUI "
                        + "&& !qishuiBridgeReady;"),
                "只留 Cookie 必须是「汽水 + 缺工具」，不能写死成汽水一律如此");
        assertTrue(source.contains("(!loggedIn || addAccountByQr) && !qishuiCookieOnly"),
                "qrVisible 没有跟着工具是否存在走");
    }

    @Test
    @DisplayName("自动取码列表里有汽水，但仍被 qrVisible 挡着")
    void autoStartQrIncludesQishuiButStaysGated() throws IOException {
        String source = accountManagerSource();
        int at = source.indexOf("startQrLogin(platform, token, addAccountByQr);");
        assertTrue(at > 0, "找不到 showDetail 末尾的自动取码调用");
        String condition = source.substring(Math.max(0, at - 400), at);
        assertTrue(condition.contains("MusicPlatform.QISHUI"), "汽水不在自动取码列表里");
        assertTrue(condition.contains("&& qrVisible)"), "自动取码没有跟着 qrVisible 走，缺工具时也会去取码");
    }

    @Test
    @DisplayName("缺工具时的文案给出具体放置路径，不只是「去网盘找」")
    void copyNamesTheExactPath() throws IOException {
        String source = accountManagerSource();
        assertTrue(source.contains("QishuiLoginBridge.requiredPath()"), "说明里没有给出 exe 该放的具体路径");
        assertTrue(source.contains("网盘"), "说明里没有给出获取工具的去处（网盘）");
        assertTrue(source.contains("QQ 群") || source.contains("QQ群"), "说明里没有给出获取工具的去处（QQ群）");
    }

    @Test
    @DisplayName("主按钮的 Cookie 分支带「且没有外部工具」，工具在时不抢扫码")
    void primaryButtonFallsBackOnlyWithoutTool() throws IOException {
        String source = accountManagerSource();
        assertTrue(source.contains("qishuiCookie ? \"Cookie 登录\""), "主按钮文案没有按汽水分支走");
        // 必须带上「 =」：否则会先命中 showDetail 里那个 qishuiCookieOnly。
        int at = source.indexOf("final boolean qishuiCookie =");
        assertTrue(at > 0, "找不到 primaryAction 的汽水分支");
        String declaration = source.substring(at, source.indexOf(";", at));
        assertTrue(declaration.contains("!QishuiLoginBridge.isAvailable()"),
                "主按钮的 Cookie 分支必须限定在「没有外部工具」时");
    }

    @Test
    @DisplayName("离开扫码页要通知工具收摊，但只转空闲、不杀进程")
    void leavingPageReleasesButDoesNotKill() throws IOException {
        String source = accountManagerSource();
        assertTrue(source.contains("QishuiMusicService.cancelQrLogin()"), "没有调用收摊");
        assertTrue(source.contains("} finally {"), "收摊必须放在 finally 里，否则异常路径会漏掉");
        // 收摊的<b>语义</b>变了：不是 kill，是让工具转空闲等复用。
        String service = qishuiServiceSource();
        int at = service.indexOf("public static void cancelQrLogin()");
        assertTrue(at > 0, "找不到 cancelQrLogin");
        String body = service.substring(at, service.indexOf('}', at));
        assertTrue(body.contains("QishuiLoginBridge.releaseSession()"),
                "结束会话必须走 releaseSession（转空闲等复用）");
        assertFalse(body.contains("QishuiLoginBridge.stop()"),
                "杀进程会让风控窗口里那次验证被作废，且下次取码要重等 88 MB 自解压");
    }

    @Test
    @DisplayName("取码优先复用已在跑的实例：每次重启都要重等 88 MB 自解压")
    void startReusesRunningInstance() throws IOException {
        String bridge = bridgeSource();
        assertTrue(bridge.contains("requestRefresh(dir)"), "start() 没有尝试复用已在跑的实例");
        assertTrue(bridge.contains("hasLiveInstance()"), "复用前没有判断那个实例是否还活着");
        assertTrue(bridge.contains("REUSE_WAIT_MILLIS"), "复用分支没有自己的等待时限");
    }

    @Test
    @DisplayName("缺工具直接报错说清楚，不回落到没有签名的内置请求")
    void noSilentFallback() throws IOException {
        String service = qishuiServiceSource();
        assertTrue(service.contains("汽水扫码需要外部取码工具"), "缺工具时没有给出明确说明");
        assertTrue(service.contains("QishuiLoginBridge.isAvailable()"), "取码前没有检查工具在不在");
        // 回落 = 还留着自己发请求的那一套。它们必须已经不在树上了。
        assertFalse(service.contains("PASSPORT_QR_ENDPOINT"), "还留着直连 Passport 取码的实现");
        assertFalse(service.contains("QR_SESSIONS"), "还留着本地二维码会话表");
    }

    @Test
    @DisplayName("风控 2046 映射成等待并照抄工具提示，不判成失败")
    void mfaIsNotAnError() throws IOException {
        String service = qishuiServiceSource();
        assertTrue(service.contains("case MFA:"), "checkQrCode 没有单独处理风控那一档");
        assertTrue(service.contains("QrLoginState.WAITING"), "风控应映射为等待，而不是失败");
        String source = accountManagerSource();
        assertTrue(source.contains("QishuiMusicService.getQrStatusHint()"),
                "等待文案要照抄工具的话，否则风控时会显示「等待扫码」骗人");
    }

    @Test
    @DisplayName("汽水的码由界面自己画：外部工具给的是链接，不是图片")
    void qishuiQrIsRenderedLocally() throws IOException {
        String source = accountManagerSource();
        int at = source.indexOf("boolean upstreamImage");
        assertTrue(at > 0, "找不到二维码渲染分支");
        String branch = source.substring(at, source.indexOf(";", at));
        assertFalse(branch.contains("MusicPlatform.QISHUI"),
                "汽水不该再走「上游给图」分支：那会把扫码链接当成 base64 去解码");
        assertTrue(branch.contains("MusicPlatform.QQ"), "QQ 那条裸 base64 分支不能被一起改掉");
    }
}
