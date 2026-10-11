package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「后端卡顿不得影响渲染」这条线。
 *
 * <p>这些约束用编译与运行都测不出来 —— 它们只在后端慢/不可用时才显形，而那正是最难复现的
 * 场景。所以这里用源码级断言把它们钉住：谁把阻塞调用挪回渲染路径、或把后端请求塞进
 * 每帧回调，这条测试就会红。</p>
 *
 * <p>不启动 Minecraft、不联网。</p>
 */
class QishuiThreadDecouplingStaticTest {

    private static String read(String relative) throws Exception {
        Path path = ProjectPaths.sourceFile(relative);
        assertTrue(Files.isRegularFile(path), "找不到源文件: " + relative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static final String MUSIC = "com/muoniumplayer/core/ncm/music/";
    private static final String SCREENS = "com/muoniumplayer/core/screens/ncm/";

    /** 取某个 {@code int} 常量声明的数值。 */
    private static int intConstant(String source, String constant) {
        java.util.regex.Matcher matcher = Pattern.compile(
                Pattern.quote(constant) + "\\s*=\\s*([0-9_]+)").matcher(source);
        assertTrue(matcher.find(), "找不到常量: " + constant);
        return Integer.parseInt(matcher.group(1).replace("_", ""));
    }

    /** 取包含某段文字的那一整行（用来断言声明式的数值关系，而不是只判断出现与否）。 */
    private static String lineContaining(String source, String needle) {
        for (String line : source.split("\n")) {
            if (line.contains(needle)) return line;
        }
        throw new AssertionError("找不到包含这段文字的行: " + needle);
    }

    /** 方法体粗切：从签名行到下一个同缩进的收尾大括号。够用来做「这段里有没有 X」的断言。 */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "找不到方法: " + signature);
        int brace = source.indexOf('{', at);
        assertTrue(brace > 0, "方法没有方法体: " + signature);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(brace, i + 1);
            }
        }
        throw new AssertionError("方法体没有收尾: " + signature);
    }

    @Test
    @DisplayName("来源卡片副标题（每帧求值）只读后端缓存的探活结论，绝不自己探测")
    void accountLineNeverProbes() throws Exception {
        String source = read(SCREENS + "MusicSourceOverlay.java");
        String body = methodBody(source, "private String accountLine(");
        assertFalse(body.contains("isAvailable()"),
                "accountLine 挂在每帧求值的文本 supplier 上，不能调会发健康检查的 isAvailable()；"
                        + "只读的 peekAvailable() 正是为此存在的");
        assertFalse(body.contains("backendHasCookie()"),
                "backendHasCookie() 读的是同一份缓存，但语义上是「探测过之后」，"
                        + "渲染路径统一用 peekHasCookie()");
        assertTrue(body.contains("peekAvailable()"),
                "渲染路径应该读只读缓存");
    }

    @Test
    @DisplayName("换源点击路径不做阻塞探活")
    void sourceSwitchNeverProbes() throws Exception {
        String source = read(MUSIC + "NeteaseMusicService.java");
        String body = methodBody(source, "void setCurrentPlatform(");
        assertFalse(body.contains("QishuiBackend.isAvailable()"),
                "setCurrentPlatform 是来源按钮的点击处理器，跑在主线程上；"
                        + "探活要发健康检查，必须交给后台的 ensureStartedForGame");
    }

    @Test
    @DisplayName("ensureStartedForGame 整体非阻塞：探测与 Cookie 同步都在后台线程里")
    void backendLifecycleIsAsync() throws Exception {
        String source = read(MUSIC + "QishuiBackendLifecycle.java");
        String entry = methodBody(source, "public static void ensureStartedForGame(");
        assertTrue(entry.contains("runAsync"),
                "入口必须把整段判定排到后台，否则客户端初始化线程要吃一次健康探测 + 两次 Cookie 同步");
        assertFalse(entry.contains("isAvailable()"),
                "探测不该在入口的调用线程上跑");
        // 真正的判定里必须有探测：它是拉起流程的一部分。
        String blocking = methodBody(source, "private static void ensureStartedForGameBlocking(");
        assertTrue(blocking.contains("isAvailable()"), "后台判定里应当探测后端健康");
    }

    @Test
    @DisplayName("会话库读盘不阻塞调用线程：渲染路径不会为「登没登录」等一次文件 I/O")
    void sessionStoreReadsAreNonBlocking() throws Exception {
        String source = read(MUSIC + "QishuiSessionStore.java");
        String snapshot = methodBody(source, "static Session snapshot(");
        assertFalse(snapshot.contains("synchronized"),
                "snapshot() 会在渲染路径被调用，不能进锁 —— 写路径持锁期间正在读写磁盘");
        assertFalse(snapshot.contains("ensureLoaded()\n"),
                "snapshot() 不能同步读盘；未加载时该排后台读并按空会话作答");
        assertTrue(snapshot.contains("loaded"),
                "snapshot() 应以 loaded 标志分叉出「后台读 + 先给空会话」");
        // 写入不得原地改集合：读路径不加锁且会遍历 cookie 表。
        assertTrue(source.contains("private static Data editableCopy()"),
                "写入要走「改副本 → 换引用」，否则读路径会撞并发修改");
        assertTrue(source.contains("Data copy()"), "Data 需要深拷贝");
    }

    @Test
    @DisplayName("汽水封面按用途降分辨率，不再拉 960 的整图")
    void coverPixelsAreBounded() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        Matcher matcher = Pattern.compile("COVER_PIXELS\\s*=\\s*(\\d+)").matcher(source);
        assertTrue(matcher.find(), "找不到 COVER_PIXELS");
        int pixels = Integer.parseInt(matcher.group(1));
        assertTrue(pixels <= 512,
                "COVER_PIXELS=" + pixels + "：封面最大只画到详情页 hero 那不到 200 像素，"
                        + "取值过大时解码内存与主线程贴图上传会成倍涨");
        assertFalse(source.contains("-resize:960:960"),
                "不该再有写死 960 的封面请求");
    }

    @Test
    @DisplayName("主页卡片分批构建：不在一次主线程 turn 里建完所有卡片")
    void homeCardsAreBuiltInBatches() throws Exception {
        String source = read(SCREENS + "panels/HomePanel.java");
        assertTrue(source.contains("buildPendingCards()"),
                "主页需要按帧补卡片的路径");
        assertTrue(source.contains("CARD_BUILD_BATCH"),
                "分批要有批次上限");
        String layout = methodBody(source, "private void layout(List<PlayList> displayedPlayLists,");
        assertTrue(layout.contains("this.pendingCards = new ArrayList<>(displayedPlayLists)"),
                "layout 应把卡片交给待建队列，而不是当场建完");
        assertFalse(layout.contains("new PlaylistWidget("),
                "layout 里不该直接构造卡片 —— 那就是「一口气建完」");
    }

    @Test
    @DisplayName("推荐歌单采样有整轮与整次两层预算，后端半死时也不会让首屏长时间转圈")
    void recommendSamplingIsBounded() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        assertTrue(source.contains("RECOMMEND_ROUND_TIMEOUT_MS"),
                "每一轮要有超时");
        assertTrue(source.contains("RECOMMEND_TOTAL_BUDGET_MS"),
                "所有轮加起来也要有上限，否则单轮预算会累加成几十秒");
        String round = methodBody(source, "private static int sampleDiscoverMixRound(");
        assertTrue(round.contains("deadline"),
                "八路并发应共享一份整轮预算，而不是每路各给一份超时");
        assertTrue(source.contains("warmDiscoverConnectionAsync"),
                "冷启动的建连开销要有预热通道，否则首屏会白等约 1.6 秒");
    }

    @Test
    @DisplayName("单轮预算必须大于单个请求超时，否则慢网络下永远等不到结果")
    void roundBudgetExceedsRequestTimeout() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        int requestTimeout = intConstant(source, "REQUEST_TIMEOUT_MS");
        // 单轮预算现在由 REQUEST_TIMEOUT_MS 推导（+余量）；这里把那条关系钉住。
        String roundDecl = lineContaining(source, "RECOMMEND_ROUND_TIMEOUT_MS =");
        assertTrue(roundDecl.contains("REQUEST_TIMEOUT_MS"),
                "单轮预算必须显式建立在请求超时之上。2026-10-11 真机踩过：预算写死 8 秒、"
                        + "请求超时 15 秒，慢网络下请求永远来不及返回，整轮被判空手 —— 主页全空");
        assertTrue(roundDecl.contains("+"),
                "单轮预算要给请求超时留一点余量，不能恰好相等");
        assertTrue(requestTimeout > 0, "找不到 REQUEST_TIMEOUT_MS");
    }

    @Test
    @DisplayName("第一轮空手不得终止采样，也不得把空结果写进主页缓存")
    void emptyFirstRoundIsNotFatal() throws Exception {
        String service = read(MUSIC + "QishuiMusicService.java");
        String sampling = methodBody(service, "public static List<PlayList> recommendPlaylists(int limit, Consumer<List<PlayList>> progress)");
        // 空手时继续下一轮，而不是 break：单轮空手在慢网络上是常态。
        assertTrue(sampling.contains("continue"),
                "一张都没拿到时应继续下一轮，而不是直接收手");
        assertFalse(sampling.contains("firstError"),
                "不再有「第一轮空手就放弃」这条终止路径 —— 它会把慢网络整个判成失败");

        // 主页侧：采样空手时不能缓存，否则 cache-first 分支会让它永远不再重试。
        String home = read(SCREENS + "panels/HomePanel.java");
        assertTrue(home.contains("if (!sampled.isEmpty())"),
                "空结果不得进缓存，否则下一次进主页被 cache-first 命中，永远不再重试");
    }

    @Test
    @DisplayName("封面 URL 在 template_prefix 缺失时兜底，不退回会 400 的裸路径")
    void coverUrlNeverFallsBackToBarePath() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        assertTrue(source.contains("COVER_TEMPLATE_PREFIX"),
                "缺少 template_prefix 时要有已知可用的兜底前缀");
        String body = methodBody(source, "private static String coverUrl(JsonObject image)");
        // 只要有 uri 就必须产出一个带 resize 后缀的地址，绝不能把裸 url 交出去。
        assertTrue(body.contains("COVER_TEMPLATE_PREFIX"),
                "uri 存在但 template_prefix 缺失时，必须用兜底前缀而不是回落到裸 url");
        assertTrue(body.indexOf("COVER_TEMPLATE_PREFIX") < body.indexOf("\"urls\""),
                "兜底前缀要在「读 urls 数组」之前生效，否则还会走到裸路径那条分支");
        assertTrue(read("com/muoniumplayer/core/utils/network/HttpUtils.java").contains("getResponseCode()"),
                "下载失败要按 HTTP 状态码区分可重试与不可重试：4xx 重复十次只会挂满线程抢带宽");
    }
}
