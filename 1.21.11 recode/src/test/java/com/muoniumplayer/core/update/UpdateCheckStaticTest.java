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

    private static final String MC_VERSION = "1.21.11";
    private static final String MOD_VERSION = "1.4.8";
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
        assertTrue(UpdateChecker.isAllowedPage(UpdateChecker.HOME_PAGE), "兜底页自己必须在白名单里");

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

        Path gui = SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "GuiUpdateNotice.java"));
        String screen = stripComments(read(gui));
        assertFalse(screen.contains("HttpUtils"),
                "界面在渲染线程上跑，绝不能自己发网络请求");
        assertTrue(screen.contains("setClipboardString("),
                "「复制下载链接」必须走原版剪贴板通道（GuiScreen#setClipboardString）");
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
        assertTrue(checker.contains("\"enabled\""),
                "必须有一个可关掉整个更新检查的开关");
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
