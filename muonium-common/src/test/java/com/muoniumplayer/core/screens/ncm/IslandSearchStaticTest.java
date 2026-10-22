package com.muoniumplayer.core.screens.ncm;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灵动岛快捷搜索栏的离线契约测试。
 *
 * <p>两条腿：纯逻辑断言直接调 {@code IslandSearchModel} 的静态纯函数（防抖窗口、
 * 形变插值、revision gate）；接线断言按 {@code IslandNoticeChannelStaticTest} 的
 * {@code body()} 惯例读源码 —— 岛侧钩子的调用序、透明 Screen 的底线（不画背景）、
 * 五棵树的按键注册（{@link ProjectPaths#allJavaFiles()} 全仓扫描，只扫一个根会绿着少扫）。</p>
 */
class IslandSearchStaticTest {

    private static final Path MODEL = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "IslandSearchModel.java"));
    private static final Path TRANSITION = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "IslandSearchTransition.java"));
    private static final Path OVERLAY = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "IslandSearchOverlay.java"));

    // ------------------------------------------------------------------ 纯逻辑：防抖

    @Test
    @DisplayName("防抖：停笔不足窗口不发，够窗口才发")
    void debounceWindow() {
        assertTrue(IslandSearchModel.isSearchDue(1_000L, 700L, "周杰伦", 300L),
                "停笔 300ms 应当到期");
        assertFalse(IslandSearchModel.isSearchDue(1_000L, 900L, "周杰伦", 300L),
                "停笔 200ms 不应到期");
        assertFalse(IslandSearchModel.isSearchDue(1_000L, 700L, "", 300L),
                "空关键词永不发");
        assertFalse(IslandSearchModel.isSearchDue(1_000L, 700L, "   ", 300L),
                "纯空白视同空关键词");
    }

    @Test
    @DisplayName("防抖消费：同词不重发，回车绕过防抖")
    void debounceConsume() {
        // consumeDueSearch / consumeImmediateSearch 是静态状态机——按时间线推一遍。
        IslandSearchModel.onInputChanged("晴天", 1_000L);
        assertNull(IslandSearchModel.consumeDueSearch(1_100L), "窗口内不发");
        assertEquals("晴天", IslandSearchModel.consumeDueSearch(1_400L), "到期后发一次");
        assertNull(IslandSearchModel.consumeDueSearch(2_000L), "同词不重发");
        assertEquals("晴天", IslandSearchModel.consumeImmediateSearch(" 晴天 "), "回车同词也重发");
        IslandSearchModel.reset();
    }

    @Test
    @DisplayName("清空输入立即撤结果，不占防抖窗口")
    void emptyInputClearsImmediately() {
        IslandSearchModel.onInputChanged("晴天", 1_000L);
        IslandSearchModel.consumeDueSearch(1_400L);
        IslandSearchModel.onInputChanged("", 1_500L);
        assertTrue(IslandSearchModel.results().isEmpty(), "清空后结果必须撤下");
        assertFalse(IslandSearchModel.isAwaitingResults(), "清空后不得再有在途请求语义");
        assertNull(IslandSearchModel.consumeDueSearch(9_999L), "空词后续帧也不发");
        IslandSearchModel.reset();
    }

    // ------------------------------------------------------------------ 纯逻辑：revision gate

    @Test
    @DisplayName("过期结果落地被丢弃：旧 ticket 不覆盖新结果")
    void staleTicketDropped() throws Exception {
        IslandSearchModel.onInputChanged("A", 1_000L);
        long first = IslandSearchModel.beginRequest();
        IslandSearchModel.onInputChanged("AB", 1_100L);
        long second = IslandSearchModel.beginRequest();
        assertTrue(second != first, "新请求必须换新 ticket");

        IslandSearchModel.deliverResults(second, List.of());
        long revision = IslandSearchModel.resultsRevision();
        IslandSearchModel.deliverResults(first, List.of());
        assertEquals(revision, IslandSearchModel.resultsRevision(),
                "过期 ticket 落地不得推进结果代次");
        IslandSearchModel.reset();
    }

    // ------------------------------------------------------------------ 纯逻辑：形变插值

    @Test
    @DisplayName("矩形插值：中心对齐，端点精确")
    void lerpBoundsEndpointsAndMidpoint() {
        double[] island = {10.0, 10.0, 40.0, 16.0, 8.0};   // 中心 (30, 18)
        double[] capsule = {0.0, 0.0, 100.0, 56.0, 14.0};  // 中心 (50, 28)
        double[] atZero = IslandSearchModel.lerpBounds(island, capsule, 0.0);
        assertEquals(island[0], atZero[0], 1e-9, "f=0 回到起点 x");
        assertEquals(island[2], atZero[2], 1e-9, "f=0 回到起点宽");
        double[] atOne = IslandSearchModel.lerpBounds(island, capsule, 1.0);
        assertEquals(capsule[3], atOne[3], 1e-9, "f=1 到达目标高");
        double[] atHalf = IslandSearchModel.lerpBounds(island, capsule, 0.5);
        assertEquals(40.0, atHalf[0] + atHalf[2] * .5, 1e-9, "半程中心 x");
        assertEquals(23.0, atHalf[1] + atHalf[3] * .5, 1e-9, "半程中心 y");
        double[] clamped = IslandSearchModel.lerpBounds(island, capsule, 1.7);
        assertEquals(capsule[2], clamped[2], 1e-9, "越界 fraction 被钳到 1");
    }

    // ------------------------------------------------------------------ 接线：岛侧钩子

    @Test
    @DisplayName("打开必须先接管岛再捕获锚点？——不：捕获要在接管之前（实时轮廓还活着）")
    void hookOrdering() throws IOException {
        String open = body(read(TRANSITION), "static boolean open(");
        int take = open.indexOf("beginPlayerSurfaceTransition");
        int anchor = open.indexOf("captureStart");
        assertTrue(take >= 0, "open 必须调 DownloadDynamicIsland.beginPlayerSurfaceTransition");
        assertTrue(anchor >= 0, "open 必须捕获锚点");
        assertTrue(anchor < take, "捕获锚点要在接管岛之前（实时轮廓还活着）");

        String close = body(read(TRANSITION), "static void close()");
        assertTrue(close.contains("preparePlayerAbsorb"), "close 必须先预置吸收");
        String tick = body(read(TRANSITION), "static void tick()");
        int beginAbsorb = tick.indexOf("beginPlayerAbsorbImpact");
        assertTrue(beginAbsorb >= 0, "收起落定要触发岛的撞击回弹");
        assertTrue(tick.indexOf("state = IDLE") < beginAbsorb, "撞击在复位之后");

        assertTrue(body(read(TRANSITION), "static void abort()")
                .contains("cancelPlayerSurfaceTransition"), "abort 必须把岛立即还回去");
    }

    /** 播放器转场（守卫的另一半住在 FORK 之外的共享层）。 */
    private static final Path PLAYER_TRANSITION = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "PlayerIslandTransition.java"));

    @Test
    @DisplayName("播放器转场忙时拒绝打开")
    void openGuardsOnPlayerTransition() throws IOException {
        String open = body(read(TRANSITION), "static boolean open(");
        assertTrue(open.contains("PlayerIslandTransition.isBusy()"),
                "open 必须先问播放器转场忙不忙");
        // 守卫语义落在 PlayerIslandTransition.isBusy 上：忙 = 动画中或播放器正在收尾。
        String busy = body(read(PLAYER_TRANSITION), "public static boolean isBusy()");
        assertTrue(busy.contains("state != IDLE"), "isBusy 要覆盖全部动画态");
    }

    // ------------------------------------------------------------------ 接线：透明 Screen 底线

    @Test
    @DisplayName("搜索浮层不画任何背景暗幕（游戏画面要一直可见）")
    void overlayNeverDrawsBackground() throws IOException {
        String source = read(OVERLAY);
        assertFalse(source.contains("drawDefaultBackground"),
                "调了 drawDefaultBackground 会把整屏压黑，透明浮层的前提就没了");
        assertTrue(source.contains("doesGuiPauseGame"), "必须声明不暂停游戏");
        assertTrue(body(read(OVERLAY), "boolean doesGuiPauseGame()").contains("false"),
                "doesGuiPauseGame 必须返回 false");
    }

    @Test
    @DisplayName("结果行必须复用 MusicWidget（三键行为白得）")
    void rowsReuseMusicWidget() throws IOException {
        String source = read(OVERLAY);
        assertTrue(source.contains("new MusicWidget("), "结果行直接用 MusicWidget");
        assertTrue(source.contains("IslandSearchModel.resultPlaylist()"),
                "行挂的必须是 searchResultList 外壳（playFromSearchSelection 靠它生效）");
        String model = read(MODEL);
        assertTrue(model.contains("setSearchResultList(true)"),
                "外壳必须标 searchResultList");
        assertTrue(model.contains("setSearchMode(true)"), "外壳必须标 searchMode");
    }

    @Test
    @DisplayName("搜索请求必须走工作线程 + 主线程 revision 校验")
    void searchAsyncWithGate() throws IOException {
        String submit = body(read(OVERLAY), "static void submitSearch(");
        assertTrue(submit.contains("MultiThreadingUtil.runAsync"), "搜索在工作线程");
        assertTrue(submit.contains("MultiThreadingUtil.runOnMainThread"), "落地回主线程");
        assertTrue(submit.contains("deliverResults"), "落地走模型的 revision 校验");
    }

    @Test
    @DisplayName("输入即搜：防抖消费接在 drawScreen 主循环里")
    void typingTriggersSearch() throws IOException {
        String draw = body(read(OVERLAY), "void drawScreen(int mouseX, int mouseY)");
        assertTrue(draw.contains("consumeDueSearch"), "每帧要问防抖到期没有");
        assertTrue(draw.contains("IslandSearchTransition.tick()"),
                "转场时钟必须在读几何之前推进");
    }

    // ------------------------------------------------------------------ 五树接线
    //
    // ProjectPaths.allJavaFiles() 只扫「共享层 + 跑测试的这一棵树」，看不见另外四棵树的
    // FORK 文件；跨树契约直接在仓库根上逐树逐文件读（路径由本测试自己钉）。

    /** 五棵版本树的名字。新树进来要同时补这里与 SHARE-MANIFEST。 */
    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.11 recode", "26.2", "26.3"
    };

    @Test
    @DisplayName("五棵树的 MuoniumPlayerClient 都注册并消费 keyQuickSearch")
    void allTreesRegisterQuickSearch() throws IOException {
        Path repo = ProjectPaths.repoRoot();
        int register = 0;
        int consume = 0;
        for (String tree : TREES) {
            Path client = repo.resolve(Paths.get(tree, "src", "main", "java",
                    "com", "muoniumplayer", "MuoniumPlayerClient.java"));
            assertTrue(Files.isRegularFile(client), "MuoniumPlayerClient 不存在: " + client);
            String source = read(client);
            if (source.contains("key.muonium.quick_search")) register++;
            if (source.contains("keyQuickSearch.consumeClick()")) consume++;
        }
        assertEquals(5, register, "五棵树都要注册 key.muonium.quick_search");
        assertEquals(5, consume, "五棵树都要在 tick 里消费 keyQuickSearch");
    }

    @Test
    @DisplayName("五棵树的 HudConfig 都有 islandSearchEnabled 的 load/save")
    void allTreesCarryConfig() throws IOException {
        Path repo = ProjectPaths.repoRoot();
        int declared = 0;
        int loaded = 0;
        int saved = 0;
        for (String tree : TREES) {
            Path config = repo.resolve(Paths.get(tree, "src", "main", "java",
                    "com", "muoniumplayer", "core", "settings", "HudConfig.java"));
            assertTrue(Files.isRegularFile(config), "HudConfig 不存在: " + config);
            String source = read(config);
            if (source.contains("public static boolean islandSearchEnabled")) declared++;
            if (source.contains("islandSearchEnabled = getBoolean(o, \"islandSearchEnabled\"")) loaded++;
            if (source.contains("o.addProperty(\"islandSearchEnabled\"")) saved++;
        }
        assertEquals(5, declared, "五棵树都要有字段声明");
        assertEquals(5, loaded, "五棵树都要 load");
        assertEquals(5, saved, "五棵树都要 save");
    }

    @Test
    @DisplayName("五棵树的 lang 文件都带 quick_search 翻译")
    void allTreesCarryLang() throws IOException {
        int en = 0;
        int zh = 0;
        Path repo = ProjectPaths.repoRoot();
        for (String tree : new String[]{"1.20.1 recode", "1.21.1", "1.21.11 recode", "26.2", "26.3"}) {
            for (String lang : new String[]{"en_us.json", "zh_cn.json"}) {
                Path file = repo.resolve(Paths.get(tree, "src", "main", "resources",
                        "assets", "muonium", "lang", lang));
                assertTrue(Files.isRegularFile(file), "lang 文件不存在: " + file);
                String source = read(file);
                if (source.contains("key.muonium.quick_search")) {
                    if (lang.startsWith("en")) en++; else zh++;
                }
            }
        }
        assertEquals(5, en, "五棵树 en_us 都要有 key.muonium.quick_search");
        assertEquals(5, zh, "五棵树 zh_cn 都要有 key.muonium.quick_search");
    }

    // ------------------------------------------------------------------ 工具

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "签名后面没有方法体: " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("方法体没有闭合: " + signature);
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
