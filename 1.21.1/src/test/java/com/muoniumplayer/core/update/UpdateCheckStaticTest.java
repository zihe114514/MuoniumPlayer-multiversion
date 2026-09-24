package com.muoniumplayer.core.update;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 更新检查的纯逻辑与结构不变量。
 *
 * <p>全部离线：版本号比较、清单解析、下载页白名单都是纯函数，剩下几条读的是源码本身。
 * 刻意<b>不</b>碰 {@code UpdateChecker} 的持久化入口 —— 那会在测试工作目录里读写
 * {@code config/muonium/update_notice.json}。</p>
 */
class UpdateCheckStaticTest {

    private static final Path ROOT = ProjectPaths.versionRoot();
    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    private static final String MC_VERSION = "1.21.1";
    private static final String MOD_VERSION = "1.6.1";
    private static final String LOADER_ID = "neoforge";

    @Test
    @DisplayName("版本号按段比数字，不是字符串比较")
    void 版本号比较() {
        // 字符串比较会认为 1.10.0 比 1.9.0 旧，于是版本号一过 9 就再也提示不出来。
        assertTrue(SemVer.isNewer("1.10.0", "1.9.0"), "1.10.0 必须新于 1.9.0");
        assertTrue(SemVer.isNewer("1.4.0", "1.3.0"), "1.4.0 必须新于 1.3.0");
        assertTrue(SemVer.isNewer("2.0", "1.99.99"), "主版本优先");
        assertFalse(SemVer.isNewer("1.3.0", "1.3.0"), "同版本不算更新");
        assertFalse(SemVer.isNewer("1.2.9", "1.3.0"), "更旧的版本不算更新");
        assertEquals(0, SemVer.compare("1.3", "1.3.0"), "缺的段当 0：1.3 == 1.3.0");
        assertEquals(0, SemVer.compare("v1.3.0", "1.3.0"), "前缀 v 要忽略");

        // 预发布版：同主段时小于正式版，且本地是预发布版时不做检查。
        assertTrue(SemVer.isNewer("1.4.0", "1.4.0-rc1"), "正式版新于同号预发布版");
        assertTrue(SemVer.isPreRelease("1.3.0-dev"), "-dev 是预发布标记");
        assertTrue(SemVer.isPreRelease("1.3.0+local"), "+local 也算预发布标记");
        assertFalse(SemVer.isPreRelease(MOD_VERSION), "发布版本号里不能带预发布标记");

        // 坏输入不许抛异常：清单是远端文本，什么都可能出现。
        assertFalse(SemVer.isNewer(null, MOD_VERSION), "null 不算更新");
        assertFalse(SemVer.isNewer("", MOD_VERSION), "空串不算更新");
        assertFalse(SemVer.isNewer("nonsense", MOD_VERSION), "非数字版本号不算更新");
    }

    @Test
    @DisplayName("清单查表顺序：<mc>-<loader> → <mc> → fallback")
    void 清单解析与查表() {
        String json = "{\"schema\":1,"
                + "\"channels\":{"
                + "  \"" + MC_VERSION + "-" + LOADER_ID + "\":{\"latest\":\"1.5.0\",\"page\":\"https://github.com/x/y\",\"notes\":\"n\",\"critical\":true},"
                + "  \"" + MC_VERSION + "\":{\"latest\":\"1.4.0\"},"
                + "  \"9.9.9-fabric\":{\"latest\":\"9.9.9\"}"
                + "},"
                + "\"fallback\":{\"latest\":\"1.3.0\"}}";

        UpdateManifest manifest = UpdateManifest.parse(json);
        assertNotNull(manifest, "格式正确的清单必须解析成功");

        UpdateManifest.Entry exact = manifest.entryFor(MC_VERSION, LOADER_ID);
        assertNotNull(exact, "精确 channel 必须命中");
        assertEquals("1.5.0", exact.latest(), "应命中 <mc>-<loader> 那一条");
        assertEquals("n", exact.notes());
        assertTrue(exact.critical(), "critical 要读出来");

        UpdateManifest.Entry byMc = manifest.entryFor(MC_VERSION, "no-such-loader");
        assertNotNull(byMc);
        assertEquals("1.4.0", byMc.latest(), "找不到加载器时退到 <mc> 那一条");

        UpdateManifest.Entry fallback = manifest.entryFor("0.0.0", "nope");
        assertNotNull(fallback);
        assertEquals("1.3.0", fallback.latest(), "都找不到时退到 fallback");

        // 没有 latest 的条目当作不存在，继续往下一级查。
        UpdateManifest noLatest = UpdateManifest.parse(
                "{\"channels\":{\"" + MC_VERSION + "-" + LOADER_ID + "\":{\"page\":\"https://github.com/x\"}},"
                        + "\"fallback\":{\"latest\":\"1.3.0\"}}");
        assertNotNull(noLatest);
        assertEquals("1.3.0", noLatest.entryFor(MC_VERSION, LOADER_ID).latest(),
                "缺 latest 的条目必须被跳过");
    }

    @Test
    @DisplayName("坏清单一律退化成 null，不抛异常")
    void 清单解析容错() {
        assertNull(UpdateManifest.parse(null));
        assertNull(UpdateManifest.parse(""));
        assertNull(UpdateManifest.parse("   "));
        assertNull(UpdateManifest.parse("not json at all"));
        assertNull(UpdateManifest.parse("[1,2,3]"), "顶层不是对象");
        assertNull(UpdateManifest.parse("<html>404</html>"), "CDN 返回错误页也是常态");
        assertNull(UpdateManifest.parse("{}"), "既没有 channels 也没有 fallback");
        assertNull(UpdateManifest.parse("{\"channels\":\"oops\"}"), "channels 类型不对");

        // schema 比本地新时不能整份丢掉：未知字段忽略，已知字段照读。
        UpdateManifest future = UpdateManifest.parse(
                "{\"schema\":99,\"unknown\":{\"a\":1},\"fallback\":{\"latest\":\"2.0.0\"}}");
        assertNotNull(future, "schema 更高的清单仍要按已知字段读");
        assertEquals("2.0.0", future.entryFor(MC_VERSION, LOADER_ID).latest());
    }

    @Test
    @DisplayName("下载页白名单：只放行 https + 白名单域名")
    void 下载页白名单() {
        assertTrue(UpdateChecker.isAllowedPage("https://github.com/zihe114514/MuoniumPlayer-updates"));
        assertTrue(UpdateChecker.isAllowedPage("https://github.com/x/y/releases/tag/v1.4.0"));
        assertTrue(UpdateChecker.isAllowedPage("https://raw.githubusercontent.com/x/y/main/a.jar"),
                "子域必须放行");
        assertTrue(UpdateChecker.isAllowedPage("https://modrinth.com/mod/x"));
        assertTrue(UpdateChecker.isAllowedPage(UpdateChecker.DOWNLOAD_PAGE), "兜底页自己必须在白名单里");
        assertTrue(UpdateChecker.isAllowedPage("https://melodifymc.cc.cd/download"), "官网下载页");
        assertTrue(UpdateChecker.isAllowedPage("https://www.melodifymc.cc.cd/download"),
                "官网子域也要放行");
        assertFalse(UpdateChecker.isAllowedPage("https://melodifymc.cc.cd.evil.com/download"),
                "官网域名的后缀伪装不放行");

        assertFalse(UpdateChecker.isAllowedPage(null));
        assertFalse(UpdateChecker.isAllowedPage(""));
        assertFalse(UpdateChecker.isAllowedPage("http://github.com/x"), "http 不放行");
        assertFalse(UpdateChecker.isAllowedPage("https://evil.com/x"), "白名单外的域名");
        assertFalse(UpdateChecker.isAllowedPage("https://github.com.evil.com/x"),
                "后缀伪装：真正的域名是 evil.com");
        assertFalse(UpdateChecker.isAllowedPage("https://notgithub.com/x"),
                "必须整段域名相等或是子域，不能只看结尾");
        assertFalse(UpdateChecker.isAllowedPage("https://github.com@evil.com/x"),
                "userinfo 伎俩：@ 后面才是真 host");
        assertFalse(UpdateChecker.isAllowedPage("javascript:alert(1)"));
    }

    @Test
    @DisplayName("清单源不少于两个且全是 https")
    void 清单源冗余() {
        assertTrue(UpdateChecker.MANIFEST_URLS.length >= 2,
                "单一源挂掉就再也提示不了更新，必须有 CDN 兜底");
        for (String url : UpdateChecker.MANIFEST_URLS) {
            assertTrue(url.startsWith("https://"), "清单源必须是 https：" + url);
            assertTrue(url.endsWith("update.json"), "清单源指向的必须是 update.json：" + url);
        }
        assertEquals(3, UpdateChecker.MANIFEST_URLS.length,
                "三个源分属不同 CDN / 加速入口，少一个就少一层兜底；加源/删源时一起改这条");
        assertTrue(UpdateChecker.MANIFEST_URLS[0].contains("ghfast"),
                "ghfast 排第一位：它回源最快（max-age=300），别把 raw 挪回最前面");
        assertTrue(String.join("\n", UpdateChecker.MANIFEST_URLS).contains("ghfast"),
                "ghfast.top 是 raw 的加速入口（max-age=300），国内得靠它和 gcore");
        assertTrue(String.join("\n", UpdateChecker.MANIFEST_URLS).contains("raw.githubusercontent.com"),
                "raw 直连必须留着：两个加速入口都被墙时它是最后一条路");
        assertFalse(String.join("\n", UpdateChecker.MANIFEST_URLS).contains("jsdmirror"),
                "cdn.jsdmirror.com 已从源里去掉，别再挂回来");
        assertFalse(String.join("\n", UpdateChecker.MANIFEST_URLS).contains("cdn.jsdelivr.net"),
                "cdn.jsdelivr.net 已从源里去掉（对 @main 是 12 小时缓存，会给旧清单）");
    }

    @Test
    @DisplayName("多个源同时拉取时取版本号最高的那份")
    void 取最高版本() {
        UpdateManifest.Entry stale = new UpdateManifest.Entry("1.3.0", "https://example.invalid/old", "旧", false);
        UpdateManifest.Entry fresh = new UpdateManifest.Entry("1.4.11", "https://example.invalid/new", "新", true);

        // 与顺序无关：不管哪个源先答，都取高的那个。CDN 里的旧缓存不能把版本号拉低。
        assertEquals("1.4.11", UpdateChecker.pickNewest(stale, fresh).latest());
        assertEquals("1.4.11", UpdateChecker.pickNewest(fresh, stale).latest());

        // 要的是整条条目：page / notes / critical 都跟着最高版本走，不是只拼一个版本号。
        UpdateManifest.Entry picked = UpdateChecker.pickNewest(stale, fresh);
        assertEquals("https://example.invalid/new", picked.page());
        assertEquals("新", picked.notes());
        assertTrue(picked.critical());

        // 挂掉的源是 null，不能影响结果。
        assertEquals("1.4.11", UpdateChecker.pickNewest(null, fresh, null).latest());

        // 全挂 / 没有源 → null，调用方按「这一次没有结论」处理（不弹提示）。
        assertNull(UpdateChecker.pickNewest((UpdateManifest.Entry) null));
        assertNull(UpdateChecker.pickNewest());

        // 段级比较：1.10.0 比 1.9.0 新（字符串比较会反过来）。
        assertEquals("1.10.0", UpdateChecker.pickNewest(
                new UpdateManifest.Entry("1.9.0", "", "", false),
                new UpdateManifest.Entry("1.10.0", "", "", false)).latest());

        // 空版本号当作没有：别让它顶掉一份真实清单。
        assertEquals("1.4.11", UpdateChecker.pickNewest(
                new UpdateManifest.Entry("", "", "", false), fresh).latest());
    }

    @Test
    @DisplayName("MC_VERSION / MOD_VERSION 与 gradle.properties 一致")
    void 版本常量与构建脚本一致() throws IOException {
        String props = read(ROOT.resolve("gradle.properties"));
        assertTrue(props.contains("minecraft_version=" + MC_VERSION),
                "gradle.properties 的 minecraft_version 不是 " + MC_VERSION);
        assertTrue(props.contains("mod_version=" + MOD_VERSION),
                "gradle.properties 的 mod_version 不是 " + MOD_VERSION);

        String client = read(clientSource());
        assertTrue(client.contains("MC_VERSION = \"" + MC_VERSION + "\""),
                "MuoniumPlayerClient.MC_VERSION 必须与 gradle.properties 一致（更新清单按它查表）");
        assertTrue(client.contains("MOD_VERSION = \"" + MOD_VERSION + "\""),
                "MuoniumPlayerClient.MOD_VERSION 必须与 gradle.properties 一致（比它才知道有没有新版）");
    }

    @Test
    @DisplayName("更新检查挂在共享入口上：init 起检查，tick 里轮询是否该弹")
    void 更新检查已挂上() throws IOException {
        String client = stripComments(read(clientSource()));
        assertTrue(client.contains("UpdateChecker.beginCheck(MOD_VERSION, MC_VERSION, loaderId)"),
                "init() 必须起一次更新检查，且三个参数都要传");
        assertTrue(client.contains("GuiUpdateNotice.tickAutoShow(mc)"),
                "onClientTick 必须轮询「该不该弹更新提示」");
        assertTrue(client.contains("public static void setLoaderId("),
                "共享入口必须提供 setLoaderId，供两侧外壳登记加载器 id");
    }

    @Test
    @DisplayName("网络只在守护线程上打，界面里不做 IO")
    void 网络不在渲染线程() throws IOException {
        String checker = stripComments(read(updateSource("UpdateChecker.java")));
        assertTrue(checker.contains("\"muonium-update-check\""),
                "检查必须跑在自己的线程上，线程名要能在栈里认出来");
        assertTrue(checker.contains("setDaemon(true)"),
                "必须是守护线程：网络卡住时不能拖着游戏退不出去");
        assertTrue(checker.contains("CONNECT_TIMEOUT_MILLIS") && checker.contains("READ_TIMEOUT_MILLIS"),
                "必须显式设超时，默认值会让启动期挂在一个不通的源上");

        // 界面本体是 Skija 浮层，宿主只是个薄壳：这两条不变量都落在浮层上。
        String screen = stripComments(read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", "UpdateNoticeOverlay.java"))));
        assertFalse(screen.contains("HttpUtils"),
                "界面在渲染线程上跑，绝不能自己发网络请求");
        assertTrue(screen.contains("KeyboardUtils.setClipboardString("),
                "「复制下载链接」必须走项目统一的剪贴板通道，不能自己碰 GLFW");

        // 宿主薄壳只开帧、转发输入，绘制全部交给浮层；退回原版绘制栈就会重新踩空 alpha 的坑。
        String host = stripComments(read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "GuiUpdateNotice.java"))));
        assertFalse(host.contains("fontRendererObj") || host.contains("drawCenteredString(")
                        || host.contains("new GuiButton(") || host.contains("drawRect("),
                "更新提示的宿主不许再出现原版绘制调用");
    }

    @Test
    @DisplayName("两侧外壳都在 init 之前登记了加载器 id")
    void 两侧外壳都登记了加载器() throws IOException {
        Path fabricShell = ProjectPaths.fabric().resolve(Paths.get(
                "src", "main", "java", "com", "muoniumplayer", "MuoniumPlayerMod.java"));
        String fabric = stripComments(read(fabricShell));
        assertTrue(fabric.contains("setLoaderId(\"fabric\")"), "Fabric 外壳必须登记 fabric");
        assertTrue(fabric.indexOf("setLoaderId(") < fabric.indexOf("client.init()"),
                "登记必须在 init() 之前，否则第一次检查读到的是 unknown");

        String loaderSide = stripComments(read(ProjectPaths.neoforge().resolve(Paths.get(
                "src", "main", "java", "com", "muoniumplayer", "loader", "neoforge", "MuoniumPlayerNeoForge.java"))));
        assertTrue(loaderSide.contains("setLoaderId(\"" + LOADER_ID + "\")"),
                "加载器侧外壳必须登记 " + LOADER_ID);
        assertTrue(loaderSide.indexOf("setLoaderId(") < loaderSide.indexOf("getInstance().init()"),
                "登记必须在 init() 之前");
    }

    @Test
    @DisplayName("更新提示的状态单独一个配置文件")
    void 状态独立落盘() throws IOException {
        String configPaths = read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "settings", "ConfigPaths.java")));
        assertTrue(configPaths.contains("UPDATE_NOTICE"),
                "必须有独立的 UPDATE_NOTICE 路径");
        assertTrue(configPaths.contains("UPDATE_NOTICE = new File(CONFIG_DIRECTORY, \"update_notice.json\")"),
                "状态要落在 config/muonium/update_notice.json，读坏了也不影响别的设置");

        String checker = stripComments(read(updateSource("UpdateChecker.java")));
        assertTrue(checker.contains("ConfigPaths.UPDATE_NOTICE"),
                "UpdateChecker 必须用这个路径读写自己的状态");
        assertFalse(checker.contains("\"enabled\""),
                "更新检查不允许被关掉：配置里不该再有 enabled 开关");
    }

    @Test
    @DisplayName("「先不更新」有次数上限、连不上服务器有失败阈值，两个计数都落盘")
    void 先不更新与失败都有阈值() throws IOException {
        assertEquals(3, UpdateChecker.MAX_SKIP_COUNT, "同一个版本最多先不更新 3 次");
        assertEquals(3, UpdateChecker.MAX_FAILURE_COUNT, "连续 3 次启动都连不上就提醒用户检查网络");
        assertTrue(UpdateChecker.STATE_CHECK_FAILED < 0, "连不上服务器必须是一个独立的结论");

        String checker = stripComments(read(updateSource("UpdateChecker.java")));
        assertTrue(checker.contains("\"skipCount\"") && checker.contains("\"failureCount\""),
                "两个计数都必须落盘：只放内存等于每次重启都归零，限制形同虚设");
        assertTrue(checker.contains("\"skipCountVersion\""),
                "次数要按版本记账：清单出现更新的版本时额度必须重新给满");
        // 「服务器有回应、只是没有本 channel 的条目」不能算网络故障，否则一个没发布的
        // 渠道会把用户一路推到「检查网络」那个提示上。
        assertTrue(checker.contains("reachedServer"),
                "必须把「连没连上」与「清单里有没有本 channel」分开判断");

        String overlay = stripComments(read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", "UpdateNoticeOverlay.java"))));
        assertTrue(overlay.contains("UpdateChecker.mustUpdate()"),
                "锁死状态必须由界面读 mustUpdate() 决定");
        assertTrue(overlay.contains("UpdateChecker.noteSkippedOnce()"),
                "收起提示要真的记账，否则次数永远用不完");
        assertTrue(overlay.contains("UpdateChecker.retryCheck()"),
                "连不上服务器那一套界面必须能重试");
        // 更新检查不允许被关掉：界面上曾经有过一个「不再检查更新」的链接，已按要求移除。
        assertFalse(overlay.contains("不再检查更新"),
                "界面上不许再有「不再检查更新」这个出口");
        assertFalse(overlay.contains("UpdateChecker.disable()"),
                "「不再检查更新」的实现入口必须一起删掉");
        // 额度用尽后「前往下载」只开浏览器，不许顺手把提示收掉 —— 那正是「点一下就能进游戏」的出口。
        assertFalse(overlay.contains("escapeHatch"),
                "锁死状态不许再留「放开锁」的逃生阀，它就是绕开更新进游戏的那条路");
        assertTrue(overlay.contains("装好新版本再进游戏"),
                "锁死状态下点「前往下载」必须留在界面上，只把浏览器打开");
        // 公告必须另起一行：正文由「版本」「次数说明」「公告」三段组成，公告接在次数后面
        // 会被读成同一句话 —— 而且它是最长的一段，挤在一起最容易看漏。
        assertTrue(overlay.contains("PARAGRAPH_BREAK"),
                "正文要有显式的段落分隔常量，公告才能真的另起一行");
        assertTrue(overlay.contains("split(PARAGRAPH_BREAK"),
                "折行必须先按段落切分：换行符直接丢给 fitWidth 会被当成普通字符留在行里");
    }

    private static Path clientSource() {
        return SHARED_JAVA.resolve(Paths.get("com", "muoniumplayer", "MuoniumPlayerClient.java"));
    }

    private static Path updateSource(String fileName) {
        return SHARED_JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "update", fileName));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** 注释里刻意写了「不能这么做」的反例说明，扫描前先去掉。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
