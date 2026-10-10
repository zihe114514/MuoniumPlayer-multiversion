package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「首次打开播放器会卡一下」的预热链路离线契约测试。
 *
 * <p>首开的卡顿来自三笔只付一次的成本：内容页类加载与首次构造、图标纹理在渲染线程上
 * 的同步解码上传、以及（未在本轮处理）的首页卡片重建波。预热把它们挪进 HUD 空闲期，
 * 而「预热必须永远无副作用」是这整条链路的安全前提——它每局游戏都会自动执行一次，
 * 一旦有人往里加了网络请求或同步解码，受害的不只是帧率，还有本来不该发生的流量。
 * 渲染类一加载就要 GL 上下文，跑不起来，所以按本项目惯例钉源码契约。</p>
 */
class FirstOpenPrewarmStaticTest {

    private static final Path HOME = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "HomePanel.java"));
    private static final Path ICONS = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "PlayerIconAssets.java"));
    private static final Path SCREEN = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "NCMScreen.java"));

    @Test
    @DisplayName("主页预热走固定结果页分支，不发任何网络请求且自身容错")
    void homePanelPrewarmIsSideEffectFree() throws IOException {
        String body = read(HOME);
        String prewarm = between(body, "public static void prewarm()", "private static String welcomeTextFor");
        assertTrue(prewarm.contains("new HomePanel(") && prewarm.contains(".onInit()"),
                "预热没有真的构造并初始化主页实例：类加载与控件构造仍然落在首开第一帧");
        assertTrue(prewarm.contains(".layoutPlaceholder("),
                "预热漏了占位转子：冷缓存首开最先看到的就是它，PanelLoadingWidget 是首开才加载的类");
        assertTrue(prewarm.contains("catch (Throwable"),
                "预热没有整体容错：HUD 帧里的异常会顺着 prewarmLayout 影响游戏本体");
        for (String forbidden : new String[]{"loadNeteaseHome", "loadQQHome", "loadKuGouHome",
                "loadQishuiHome", "loadBilibiliHome", "CloudMusicApi", "runAsync", "downloadTexture"}) {
            assertTrue(!prewarm.contains(forbidden),
                    "预热里出现了 " + forbidden + "：预热每局自动执行一次，任何网络副作用都不允许");
        }
    }

    @Test
    @DisplayName("onInit 的固定结果页分支仍然先 return——预热的无网络保证依赖这个结构")
    void homePanelFixedBranchStaysNetworkFree() throws IOException {
        String body = read(HOME);
        String onInit = between(body, "public void onInit()", "public static void prewarm()");
        int fixed = onInit.indexOf("fixedPlaylists != null");
        assertTrue(fixed >= 0, "onInit 找不到固定结果页分支");
        int firstDispatch = indexOfAny(onInit, "loadQQHome()", "loadKuGouHome()", "loadNeteaseHome()");
        assertTrue(firstDispatch < 0 || onInit.indexOf("return;", fixed) < firstDispatch,
                "固定结果页分支不再提前 return：prewarm 会顺着推荐加载发出网络请求");
    }

    @Test
    @DisplayName("图标预热在后台线程解码、限额队列上传，失败只放弃收益")
    void iconPrewarmStaysOffTheRenderThread() throws IOException {
        String body = read(ICONS);
        String prewarm = between(body, "public static void prewarm()", "private static void prewarmIcon");
        for (String icon : new String[]{"FAVORITE", "PLAYLIST", "HISTORY", "PERSONAL_FM",
                "PLAY_MODE_SEQUENTIAL", "PLAY_MODE_RANDOM", "PLAY_MODE_SINGLE_LOOP"}) {
            assertTrue(prewarm.contains("prewarmIcon(" + icon + ")"),
                    "prewarmIcon(" + icon + ") 没被调用：这枚图标仍会走首帧同步解码");
        }
        String worker = between(body, "private static void prewarmIcon", "private static final", false);
        assertTrue(worker.contains("MultiThreadingUtil.runAsync"),
                "图标预热没有走后台线程：jar 读取 + ImageIO 解码又回到了渲染线程");
        assertTrue(worker.contains("Textures.loadTextureAsyncly"),
                "预热没有走限额上传队列：一批解码完的图标可以吃满一整帧");
        assertTrue(!worker.contains("new DynamicTexture("),
                "预热里出现了同步构造 DynamicTexture 的写法：这正是要把渲染线程解放出来的那件事");
        assertTrue(worker.contains("PENDING_PREWARM.remove"),
                "失败没有解除排队闸门：一张缺失的图标会永远占着去重位，后续重试全部失效");
        assertTrue(worker.contains("catch (Throwable"),
                "单张图标失败没有容错：缺一张资源会中断其余图标的预热");
    }

    @Test
    @DisplayName("两段预热都挂在 prewarmLayout 里，且保持一次性闸门")
    void screenPrewarmWiresBothStages() throws IOException {
        String body = read(SCREEN);
        String prewarm = between(body, "public static void prewarmLayout()", "public void checkDirty()");
        assertTrue(prewarm.contains("if (layoutPrewarmed) return;"),
                "一次性闸门没了：每帧 HUD 都会重复整套预热");
        assertTrue(prewarm.contains("HomePanel.prewarm();") && prewarm.contains("PlayerIconAssets.prewarm();"),
                "prewarmLayout 没有同时接入主页预热与图标预热");
        assertTrue(prewarm.indexOf("getInstance().layout()") < prewarm.indexOf("HomePanel.prewarm();"),
                "主页预热排在基础布局之前：NavigateBar 还没建好就预热内容页，顺序乱了对首开没有任何好处");
    }

    @Test
    @DisplayName("预热清单里的七张图标都必须真实存在于打包资源")
    void prewarmedIconsExistInResources() {
        for (String icon : new String[]{"favorite", "playlist", "history", "personal-fm",
                "play-mode-sequential", "play-mode-random", "play-mode-single-loop"}) {
            Path png = ProjectPaths.resourceFile(Paths.get(
                    "muonium", "textures", "player", "icons", icon + ".png"));
            assertTrue(Files.exists(png), "图标资源缺失：" + png
                    + "——预热会静默失败，控件每帧退字体兜底（正是类注释里钉过的那类回归）");
        }
    }

    private static int indexOfAny(String source, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int at = source.indexOf(needle);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static String between(final String source, final String from, final String until) {
        return between(source, from, until, true);
    }

    /** {@code requireEnd=false} 用于「到文件里下一个方法声明为止」的截取，终点可以不存在。 */
    private static String between(final String source, final String from, final String until,
                                  boolean requireEnd) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点 " + from);
        int end = source.indexOf(until, start + from.length());
        assertTrue(!requireEnd || end > start, "找不到终点 " + until);
        return end > start ? source.substring(start, end) : source.substring(start);
    }

    /** 读源码并<b>剥掉注释</b>：实现自己的注释里就写着被禁的写法，不剥会自己把自己钉红。 */
    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }
}
