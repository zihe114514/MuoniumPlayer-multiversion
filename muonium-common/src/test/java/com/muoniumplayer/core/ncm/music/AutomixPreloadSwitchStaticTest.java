package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * automix <b>预备期</b>与 LX 自定义源、跨平台换源之间接线的离线回归。
 *
 * <p>保护的是一个很容易在重构中悄悄退回原样的行为：开着自定义源时，「下一曲」的取流与换源必须
 * 发生在预备阶段。一旦预备阶段不跑，搜索匹配就推迟到队列真轮到这首歌、由 {@code playSong}
 * 现搜现验 —— 用户听到的就是切歌前几秒空白加一次硬切，automix 整轮白预备。而这个退化没有任何
 * 异常、没有任何日志，只能靠断言钉住。</p>
 *
 * <p>本类只读源码，不启动 Minecraft、音频设备或网络；{@code LxSourceService} 的两个新方法只按
 * 文本契约检查，绝不调用 —— 它们会真的去起用户的脚本。</p>
 */
class AutomixPreloadSwitchStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();

    // ════════════════════════ 预备期的「无流」这一路必须换源 ════════════════════════

    @Test
    @DisplayName("armTrack 取不到流时必须跑换源救援，而不是静默放弃这一轮预备")
    void armingWithoutStreamRunsTheSwitchPipeline() throws IOException {
        String armTrack = region(cloudMusic(),
                "private void armTrack(Music song", "private int armTrialSwitches;");

        assertTrue(armTrack.contains("armNoStreamRescue(song, index, armGeneration, armRevision);"),
                "预备期取不到流时不做换源，搜索匹配就会推迟到 playSong，automix 白预备");
        assertTrue(armTrack.contains("if (playUrl == null) {"),
                "「无流」必须是一个独立的分叉：静默 return 与被删掉的合并条件等价");
        assertFalseSilentBail(armTrack);

        String rescue = region(cloudMusic(),
                "private void armNoStreamRescue(", "private void armCrossSourceRescue(");
        assertTrue(rescue.contains("armCrossSourceRescue("),
                "无流救援必须落到与试听救援同一个身体上，别长成第二份流水线");
    }

    /** 曾经的那一行合并写法：{@code playUrl == null} 与「预备作废」共用一个静默 return。 */
    private static void assertFalseSilentBail(String armTrack) {
        assertTrue(!armTrack.contains("if (playUrl == null || !armStillValid"),
                "「无流」与「预备过期」不能合在一个静默 return 里 —— 换源救援正是挂在「无流」那半支上");
    }

    @Test
    @DisplayName("救援成功只改队列格并作废旧计划，不改播放中的东西")
    void rescueReplacesTheQueueSlotAndInvalidatesTheStalePlan() throws IOException {
        String body = region(cloudMusic(),
                "private void armCrossSourceRescue(", "private long estimateLastVocalEndMillis");

        assertTrue(body.contains("CrossSourceSwitcher.findReplacementForAuto(song, CloudMusic.quality);"),
                "预备期跑的是与 playSong 同一条自动流水线，包括每首每进程最多一次的额度守卫");
        assertTrue(body.contains("queue.set(index, replacement.music);"),
                "换到的替身要写回队列那一格，否则出声的还是原来那首");
        assertTrue(body.contains("CloudMusic.queueRevision++;"),
                "接下来要放的内容变了，必须作废按旧格做出的计划与预备预算");
        assertTrue(body.contains("CrossSourceSwitcher.propagateUnblockLabel(song, replacement.music);"),
                "替身是脚本解出来的流时，徽标要回写到面板绑着的那个行对象上");
    }

    // ════════════════════════ 脚本没起完时不许烧掉这次机会 ════════════════════════

    @Test
    @DisplayName("脚本仍在 LOADING 时预备期不换源：那次唯一机会不能烧在脚本没起完上")
    void armingSkipsRescueWhileScriptIsStillBooting() throws IOException {
        String rescue = region(cloudMusic(),
                "private void armNoStreamRescue(", "private void armCrossSourceRescue(");

        int guard = rescue.indexOf("LxSourceService.isLoading()");
        int run = rescue.indexOf("armCrossSourceRescue(");
        assertTrue(guard >= 0, "无流救援必须先问脚本是不是还在起");
        assertTrue(run > guard,
                "候选表里靠脚本解 VIP 的那几格此刻必然解不出来，跑一遍会把 AUTO_ATTEMPTED 烧光，"
                        + "等真能解的时候反而不再换 —— 比不救更糟");
        assertTrue(rescue.substring(guard, run).contains("return;"),
                "还在起就要在这一轮直接退出，把这首歌留给下一轮预备与 playSong");
    }

    @Test
    @DisplayName("试听片段那一路的触发与文案一字不动")
    void trialClipPathKeepsItsTriggerAndMessage() throws IOException {
        String armTrack = region(cloudMusic(),
                "private void armTrack(Music song", "private int armTrialSwitches;");
        String all = cloudMusic();

        assertTrue(armTrack.contains("armTrialClipRescue(song, index, armGeneration, armRevision);"),
                "第四条触发（预备期试听）不能被这次改动挪走");
        assertTrue(all.contains("\"下一曲在该平台只有试听片段\""),
                "试听那一路的提示文案是既成契约");
        String trial = region(all, "private void armTrialClipRescue(", "private void armNoStreamRescue(");
        assertTrue(trial.contains("armCrossSourceRescue(song, index, armGeneration, armRevision,")
                        && trial.contains("下一曲在该平台只有试听片段"),
                "试听救援应当是共用身体的一个薄包装，不留第二份实现");
    }

    // ════════════════════════ 就绪等待：预备放宽，主线程不许 ════════════════════════

    @Test
    @DisplayName("预备期在解析之前等脚本就绪，且等待时长由剩余跑道裁剪")
    void preloadWaitsForTheCustomSourceBeforeResolving() throws IOException {
        String armTrack = region(cloudMusic(),
                "private void armTrack(Music song", "private int armTrialSwitches;");

        int wait = armTrack.indexOf(".awaitReady(readyWaitMs)");
        int resolve = armTrack.indexOf("song.getPlayUrl()");
        assertTrue(wait >= 0, "预备链路必须显式等脚本就绪");
        assertTrue(resolve > wait, "等待必须排在解析之前 —— 排在后面等于没等");

        String budget = region(cloudMusic(),
                "private long lxArmReadyWaitMillis()", "private void armTrialClipRescue(");
        assertTrue(budget.contains("if (!com.muoniumplayer.core.ncm.lxsource.LxSourceService"
                        + ".isEnabled()) return 0L;"),
                "没启用脚本是常态，这种时候一秒都不该等");
        assertTrue(budget.contains(".isLoading()) return 0L;"),
                "脚本没在起（已就绪 / 已失败）都不该等");
        assertTrue(budget.contains("runway <= 0L) return 0L;"),
                "等完已经来不及解析下载解码的等待没有意义，那种情况本来就该退回普通切歌");
        assertTrue(budget.contains("Math.min(LX_ARM_READY_WAIT_CEILING_MS, runway)"),
                "等待要取上限与跑道的小者");
    }

    @Test
    @DisplayName("等待上限必须小于脚本自身的初始化时限，且主线程那 8 秒不许放大")
    void waitBudgetsStayInsideTheRuntimeInitWindow() throws IOException {
        long ceiling = longLiteral(cloudMusic(), "LX_ARM_READY_WAIT_CEILING_MS = ");
        long initTimeout = longLiteral(lxRuntime(), "INIT_TIMEOUT_MS = ");
        assertTrue(ceiling > 0L && initTimeout > 0L, "两个时限必须还在");
        assertTrue(ceiling < initTimeout,
                "预备的等待上限超过了脚本发 inited 的时限，等到的只会是失败："
                        + ceiling + " >= " + initTimeout);

        String service = lxSourceService();
        assertTrue(service.contains("private static final long WAIT_READY_MS = 8_000L;"),
                "解析链路里那一跳的 8 秒是给主线程/点击链路用的，预备要等就去调 awaitReady，"
                        + "别把这条预算整体放大");
        assertTrue(service.indexOf("public static boolean awaitReady(long timeoutMs)") > 0,
                "预备用的有界等待必须由服务自己提供，别让 CloudMusic 去摸 runtime");
    }

    @Test
    @DisplayName("有界等待不得阻塞在未启用、已失败或已停止的运行时上")
    void awaitReadyShortCircuitsWhenThereIsNothingToWaitFor() throws IOException {
        String service = lxSourceService();
        String body = region(service,
                "public static boolean awaitReady(long timeoutMs)", "public static String statusLabel()");

        assertTrue(body.contains("if (!isEnabled()) return false;"), "没启用脚本时不得有任何等待");
        assertTrue(body.contains("if (current == null) return false;"), "没有可用脚本时不得等待");
        assertTrue(body.contains("if (current.getState() == LxRuntime.State.READY) return true;"),
                "已就绪要立刻返回，不该再走一次 latch 等待");
        assertTrue(body.contains("if (current.getState() != LxRuntime.State.LOADING) return false;"),
                "已失败 / 已停止是既成事实，等下去只会白占这条后台线程");
        assertTrue(body.contains("ensureStarted();"),
                "还没拉起来时要顺手拉起来，否则预备期永远等不到就绪");

        String loading = region(service, "public static boolean isLoading()", "public static boolean awaitReady(");
        assertTrue(loading.contains("if (!isEnabled()) return false;"),
                "没启用脚本时 isLoading 必须为 false，否则预备期为等不到答案的东西做判断");
        assertTrue(loading.contains("LxRuntime.State.LOADING"), "isLoading 只认「正在起」这一种状态");
    }

    @Test
    @DisplayName("主线程播放路径（playSong）不得新增任何为脚本就绪而起的等待")
    void playSongNeverWaitsForTheCustomSource() throws IOException {
        String playSong = region(cloudMusic(),
                "private boolean playSong(Music song) {", "private boolean adoptCrossSourceReplacement");

        assertTrue(!playSong.contains("awaitReady("), "playSong 链的一头可能是游戏主线程");
        // 只许「查一下在不在起」（isLoading 布尔查询，即时返回），不许出现任何阻塞等待：
        // 取流这件事 playSong 永远只吃 Music.getPlayUrl() 的返回值。
        assertTrue(!playSong.contains("awaitReady"), "播放路径不许阻塞等脚本就绪");
        assertTrue(!playSong.contains(".awaitReady("), "playSong 不摸 runtime 的 latch");
    }

    @Test
    @DisplayName("playSong 的换源触发在脚本 LOADING 时让路且不记账")
    void playSongSwitchDefersWhileTheCustomSourceIsLoading() throws IOException {
        String playSong = region(cloudMusic(),
                "private boolean playSong(Music song) {", "private boolean adoptCrossSourceReplacement");

        // 防线读的是 isLoading() 的布尔结果（查询不是等待），与 armNoStreamRescue 同一套约定：
        // 脚本还没起完时不跑流水线 —— 那一跑就是白烧这首歌 10 分钟内的唯一自动换源机会。
        assertTrue(playSong.contains("LxSourceService.isLoading()"),
                "playSong 的换源触发缺 isLoading 防线，脚本 LOADING 期的第一次点击会烧掉机会");
        assertEquals(2, playSong.split("LxSourceService.isLoading\\(\\)", -1).length - 1,
                "两级触发（取流失败 / 试听救援）都要有防线");
    }

    // ════════════════════════════════ 取数辅助 ════════════════════════════════

    private static String cloudMusic() throws IOException {
        return read(music("CloudMusic.java"));
    }

    private static String lxSourceService() throws IOException {
        return read(lxsource("LxSourceService.java"));
    }

    private static String lxRuntime() throws IOException {
        return read(lxsource("LxRuntime.java"));
    }

    /** 取 {@code from} 到 {@code to} 之间的那段源码（含 from，不含 to）。 */
    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到终点：" + to + "（起点之后）");
        return source.substring(start, end);
    }

    /** 读一个 {@code 22_000L} 形式的长整型字面量。 */
    private static long longLiteral(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "找不到常量：" + marker);
        int start = at + marker.length();
        int end = start;
        while (end < source.length() && (Character.isDigit(source.charAt(end))
                || source.charAt(end) == '_' || source.charAt(end) == 'L')) {
            end++;
        }
        String text = source.substring(start, end).replace("_", "").replace("L", "");
        assertTrue(!text.isEmpty(), "常量为空：" + marker);
        return Long.parseLong(text);
    }

    private static Path music(String file) {
        return ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music", file));
    }

    private static Path lxsource(String file) {
        return ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "lxsource", file));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
