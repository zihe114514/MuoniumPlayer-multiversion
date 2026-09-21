package com.muoniumplayer.core.update;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公告系统的纯逻辑与结构不变量。
 *
 * <p>与 {@code UpdateCheckStaticTest} 同一套办法：解析与值对象是真函数，其余几条读的是源码本身。
 * 刻意<b>不</b>碰 {@code AnnouncementChecker} 的持久化与网络入口 —— 那会在测试工作目录里读写
 * {@code config/muonium/announcements.json}，也会真的去拉远端清单。</p>
 */
class AnnouncementStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();
    private static final Path ROOT = ProjectPaths.versionRoot();

    /**
     * 只是清单查表用的样例键。本测试不核对版本常量与 gradle.properties 是否一致
     * （那是 {@code UpdateCheckStaticTest} 的活），所以四棵树里同一份文本都能跑。
     */
    private static final String SAMPLE_MC = "1.21.1";
    private static final String SAMPLE_LOADER = "neoforge";

    @Test
    @DisplayName("公告数组逐字段读出，缺选的字段退化成空串而不是缺失")
    void 公告解析() {
        String json = "{\"schema\":2,\"fallback\":{\"latest\":\"1.6.0\"},\"announcements\":["
                + "{\"id\":\"a-1\",\"date\":\"2026-09-21\",\"kind\":\"Feature\",\"title\":\"标题一\","
                + "\"body\":\"第一段\\n第二段\",\"link\":\"https://github.com/x/y\",\"linkText\":\"看看\"},"
                + "{\"id\":\"a-2\",\"title\":\"只有标题\"}"
                + "]}";

        UpdateManifest manifest = UpdateManifest.parse(json);
        assertNotNull(manifest);
        List<Announcement> list = manifest.announcements();
        assertEquals(2, list.size(), "数组顺序就是显示顺序，不做任何重排");

        Announcement first = list.get(0);
        assertEquals("a-1", first.id());
        assertEquals("2026-09-21", first.date());
        // 语气原样保留，大小写由界面归一化：清单里写 Feature 也是同一个语气。
        assertEquals("Feature", first.kind());
        assertEquals("第一段\n第二段", first.body(), "正文里的换行必须原样带出来，界面按它分段");
        assertEquals("https://github.com/x/y", first.link());
        assertEquals("看看", first.linkText());

        Announcement second = list.get(1);
        assertEquals("", second.body(), "缺字段要退化成空串，不能让调用方处处判 null");
        assertEquals("", second.kind());
    }

    @Test
    @DisplayName("坏公告一条条跳过，不连累整批；没有公告段也是合法清单")
    void 公告解析容错() {
        UpdateManifest mixed = UpdateManifest.parse("{\"fallback\":{\"latest\":\"1.6.0\"},"
                + "\"announcements\":[\"不是对象\","
                + "{\"title\":\"没有 id，记账记不到它\"},"
                + "{\"id\":\"only-blank\",\"title\":\"  \",\"body\":\"  \"},"
                + "{\"id\":\"ok\",\"title\":\"能用\"}]}");
        assertNotNull(mixed);
        List<Announcement> kept = mixed.announcements();
        assertEquals(1, kept.size(), "三条坏的都要被跳过，剩下那一条照常显示");
        assertEquals("ok", kept.get(0).id());

        // 重复 id：保留先出现的那条。已读记账必须稳定，两条抢同一个 id 是清单写错了。
        UpdateManifest duplicated = UpdateManifest.parse("{\"fallback\":{\"latest\":\"1.0\"},"
                + "\"announcements\":[{\"id\":\"dup\",\"title\":\"前\"},{\"id\":\"dup\",\"title\":\"后\"}]}");
        assertEquals(1, duplicated.announcements().size());
        assertEquals("前", duplicated.announcements().get(0).title());

        // schema 1 的老清单没有 announcements 段：必须是空表，而不是 null 或异常。
        UpdateManifest legacy = UpdateManifest.parse("{\"schema\":1,\"fallback\":{\"latest\":\"1.6.0\"}}");
        assertNotNull(legacy);
        assertTrue(legacy.announcements().isEmpty(), "老清单要能照常读出版本号，公告按空表处理");
        assertEquals("1.6.0", legacy.entryFor(SAMPLE_MC, SAMPLE_LOADER).latest());

        // 只有公告、没有 channels / fallback 也算一份可用清单（公告不该被更新段绑住）。
        UpdateManifest onlyAnnouncements = UpdateManifest.parse(
                "{\"announcements\":[{\"id\":\"x\",\"title\":\"只有公告\"}]}");
        assertNotNull(onlyAnnouncements, "没有更新段的清单仍要读得出公告");
        assertEquals(1, onlyAnnouncements.announcements().size());
        assertNull(onlyAnnouncements.entryFor(SAMPLE_MC, SAMPLE_LOADER),
                "只有公告的清单里查不到本 channel 时，不许造一个假版本号出来");

        // announcements 类型不对（写成对象或字符串）都只是「没有公告」，不能把整份清单判死。
        UpdateManifest wrongType = UpdateManifest.parse(
                "{\"fallback\":{\"latest\":\"1.6.0\"},\"announcements\":{\"id\":\"x\"}}");
        assertNotNull(wrongType);
        assertTrue(wrongType.announcements().isEmpty());
        assertEquals("1.6.0", wrongType.entryFor(SAMPLE_MC, SAMPLE_LOADER).latest());
    }

    @Test
    @DisplayName("公告条数有上限，外部输入撑不坏界面")
    void 公告条数上限() {
        StringBuilder json = new StringBuilder("{\"fallback\":{\"latest\":\"1.6.0\"},\"announcements\":[");
        for (int index = 0; index < UpdateManifest.MAX_ANNOUNCEMENTS + 40; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"id-").append(index).append("\",\"title\":\"t").append(index).append("\"}");
        }
        List<Announcement> kept = UpdateManifest.parse(json.append("]}").toString()).announcements();
        assertEquals(UpdateManifest.MAX_ANNOUNCEMENTS, kept.size(),
                "每一条都会在界面建一组组件，不设上限就等于让公开仓库上的一份文本决定建多少个");
    }

    @Test
    @DisplayName("公告列表是不可变表，且永不返回 null")
    void 公告列表不可变() {
        UpdateManifest manifest = UpdateManifest.parse(
                "{\"announcements\":[{\"id\":\"a\",\"title\":\"t\"}]}");
        List<Announcement> list = manifest.announcements();
        try {
            list.add(new Announcement("b", "", "", "t", "", "", ""));
            throw new AssertionError("解析结果会被守护线程发布给界面每帧读，必须不可变");
        } catch (UnsupportedOperationException expected) {
            // 正确表现
        }
        assertTrue(UpdateManifest.parse("{\"fallback\":{\"latest\":\"1.0\"}}").announcements().isEmpty());
    }

    @Test
    @DisplayName("值对象把两端空白清掉，null 一律当空串")
    void 值对象归一化() {
        Announcement announcement = new Announcement("  id  ", null, "  ", " 标题 ", null, " ", null);
        assertEquals("id", announcement.id());
        assertEquals("", announcement.date());
        assertEquals("", announcement.kind());
        assertEquals("标题", announcement.title());
        assertEquals("", announcement.body());
        assertEquals("", announcement.link());
        assertEquals("", announcement.linkText());
    }

    @Test
    @DisplayName("公告与更新读同一份清单、同一套源与同一份白名单")
    void 复用更新检查的基础设施() throws IOException {
        String checker = stripComments(read(source("AnnouncementChecker.java")));
        assertTrue(checker.contains("UpdateChecker.MANIFEST_URLS"),
                "源地址必须复用 UpdateChecker 那一份：抄一份出来，改域名时就会只改一边");
        assertTrue(checker.contains("UpdateChecker.isAllowedPage("),
                "链接白名单同样复用，公告链接不许自成一套规则");
        assertFalse(checker.contains("raw.githubusercontent"),
                "本文件里不该再出现任何清单地址字面量");
        assertTrue(checker.contains("SemVer.isNewer("),
                "多源之间按版本号取最高，与更新检查同一条规则");
    }

    @Test
    @DisplayName("网络只在守护线程上跑，界面里不做 IO")
    void 网络不在渲染线程() throws IOException {
        String checker = stripComments(read(source("AnnouncementChecker.java")));
        assertTrue(checker.contains("\"muonium-announcement-fetch\""),
                "检查必须跑在自己的线程上，线程名要能在栈里认出来");
        assertTrue(checker.contains("setDaemon(true)"),
                "必须是守护线程：网络卡住时不能拖着游戏退不出去");
        assertTrue(checker.contains("CONNECT_TIMEOUT_MILLIS") && checker.contains("READ_TIMEOUT_MILLIS"),
                "必须显式设超时，默认值会让启动期挂在一个不通的源上");
        assertTrue(checker.contains("FETCH_BUDGET_MILLIS"),
                "全部源一起拉的等待预算是给总量的，不能随源数线性涨");

        String overlay = stripComments(read(Paths.get("com", "muoniumplayer", "core", "screens",
                "ncm", "AnnouncementOverlay.java")));
        assertFalse(overlay.contains("HttpUtils"),
                "界面在渲染线程上跑，绝不能自己发网络请求");
        assertTrue(overlay.contains("KeyboardUtils.setClipboardString("),
                "浏览器打不开时要把链接复制进剪贴板，走项目统一的剪贴板通道");
    }

    @Test
    @DisplayName("已读记账单独一个文件，不与更新提示混写")
    void 记账独立落盘() throws IOException {
        String configPaths = read(Paths.get("com", "muoniumplayer", "core", "settings", "ConfigPaths.java"));
        assertTrue(configPaths.contains("ANNOUNCEMENTS = new File(CONFIG_DIRECTORY, \"announcements.json\")"),
                "公告状态要落在 config/muonium/announcements.json");
        assertTrue(configPaths.contains("ANNOUNCEMENTS_LOCAL"),
                "本地覆盖入口必须存在：否则改一条公告就得先推一次 GitHub 才看得到效果");

        String checker = stripComments(read(source("AnnouncementChecker.java")));
        assertTrue(checker.contains("ConfigPaths.ANNOUNCEMENTS"), "必须用上面那条路径读写自己的状态");
        assertFalse(checker.contains("ConfigPaths.UPDATE_NOTICE"),
                "不许把已读记录写进更新提示那个文件：一次写坏会把两件事一起丢掉");
        assertTrue(checker.contains("\"readIds\"") && checker.contains("MAX_READ_IDS"),
                "已读 id 必须落盘，并且要有条数上限，否则只增不减");
    }

    @Test
    @DisplayName("公告窗排在更新提示之后弹，且每次启动最多弹一次")
    void 弹出顺序与闸门() throws IOException {
        String client = stripComments(read(Paths.get("com", "muoniumplayer", "MuoniumPlayerClient.java")));
        int update = client.indexOf("GuiUpdateNotice.tickAutoShow(mc)");
        int announcements = client.indexOf("GuiAnnouncements.tickAutoShow(mc)");
        assertTrue(update > 0 && announcements > update,
                "公告必须排在更新提示之后调：前一屏还在时这里读到的当前界面不是主菜单，本 tick 跳过，天然排队");
        assertTrue(client.contains("AnnouncementChecker.beginFetch(MOD_VERSION, MC_VERSION, loaderId)"),
                "init() 里要起一次公告获取，参数与更新检查同一套");

        String host = stripComments(read(Paths.get("com", "muoniumplayer", "core", "screens",
                "GuiAnnouncements.java")));
        assertTrue(host.contains("AnnouncementChecker.markShownThisSession()"),
                "弹出来那一刻就要记账，否则回到主菜单会再弹一次");
        assertTrue(host.contains("renderWidget(mouseX, mouseY, Mouse.getDWheel())"),
                "滚轮必须转发：公告是条数不定的一批内容，不转发的话窄窗口里下面的几条永远看不到");
        assertTrue(host.contains("overlay.dispose()"),
                "宿主的 onClose 也要走 dispose，已读记账挂在收起动作上，从哪条路离开都得记账");
    }

    @Test
    @DisplayName("关掉即整批已读，锁不锁由更新提示管、公告不管")
    void 收起就是已读() throws IOException {
        String overlay = stripComments(read(Paths.get("com", "muoniumplayer", "core", "screens",
                "ncm", "AnnouncementOverlay.java")));
        assertTrue(overlay.contains("AnnouncementChecker.markAllRead()"),
                "公告窗的收起动作必须记账，这是「每次启动都弹」不再重复的唯一依据");
        assertTrue(overlay.contains("public void dispose()"),
                "记账要挂在 dispose 上：点遮罩走的是 NcmSheet 的 dispose，只挂按钮回调会漏掉那条路");
        assertFalse(overlay.contains("mustUpdate()") || overlay.contains("noteSkippedOnce("),
                "公告不参与更新提示那套次数额度 —— 公告没有「别停在有安全问题的版本上」这个理由");
    }

    @Test
    @DisplayName("主菜单那颗按钮走加载器事件，不引入 mixin")
    void 主菜单按钮不用mixin() throws IOException {
        String builder = stripComments(read(Paths.get("com", "muoniumplayer", "core", "screens",
                "TitleMenuAnnouncementButton.java")));
        assertTrue(builder.contains("Button.builder("), "按钮由原版 Button.builder 建，四版本同一套签名");
        assertTrue(builder.contains("instanceof TitleScreen"), "只挂主菜单，别的面板一概不动");
        assertEquals(1, countOf(builder, "getGuiScaledWidth"),
                "坐标算式只许有一处：两个加载器各写一遍，早晚会一个跟着原版按钮跑、一个不跟");

        String fabric = stripComments(read(ProjectPaths.fabric().resolve(Paths.get(
                "src", "main", "java", "com", "muoniumplayer", "MuoniumPlayerMod.java"))));
        assertTrue(fabric.contains("TitleMenuAnnouncementButton.create()"),
                "Fabric 侧必须在 AFTER_INIT 里挂上这颗按钮");
        assertTrue(fabric.contains("Screens.get"),
                "加组件走 fabric-screen-api 的活列表，不自己碰原版受保护方法");

        Path loader = loaderShell();
        String loaderSide = stripComments(read(loader));
        assertTrue(loaderSide.contains("onScreenInit"), "加载器侧外壳必须挂 ScreenEvent.Init.Post 监听");
        assertTrue(loaderSide.contains("TitleMenuAnnouncementButton.appliesTo("),
                "加载器侧也要先判「是不是主菜单」再挂");

        String mixins = read(ROOT.resolve(Paths.get("fabric", "src", "main", "resources",
                "muonium.mixins.json")));
        assertFalse(mixins.contains("TitleScreen"),
                "不为这颗按钮开 mixin 的头：defaultRequire=1 之下写错目标方法名会让玩家进不了游戏");
    }

    /** 两侧加载器的目录名不一样（1.20.1 是 forge），按存在的那个取。 */
    private static Path loaderShell() {
        // 不借 ProjectPaths.neoforge()：1.20.1 那棵树的帮助类里只有 forge()，本测试要四棵树同文。
        Path neo = ROOT.resolve(Paths.get("neoforge", "src", "main", "java", "com", "muoniumplayer",
                "loader", "neoforge", "MuoniumPlayerNeoForge.java"));
        if (Files.exists(neo)) {
            return neo;
        }
        return ROOT.resolve(Paths.get("forge", "src", "main", "java", "com", "muoniumplayer",
                "loader", "forge", "MuoniumPlayerForge.java"));
    }

    private static Path source(String fileName) {
        return SHARED_JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "update", fileName));
    }

    /** 相对共享源码根定位；已经是绝对路径的（加载器外壳那些）原样用。 */
    private static String read(Path path) throws IOException {
        path = path.isAbsolute() ? path : SHARED_JAVA.resolve(path);
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int hit = haystack.indexOf(needle, from);
            if (hit < 0) {
                return count;
            }
            count++;
            from = hit + needle.length();
        }
    }

    /** 注释里刻意写了「不能这么做」的反例说明，扫描前先去掉。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
