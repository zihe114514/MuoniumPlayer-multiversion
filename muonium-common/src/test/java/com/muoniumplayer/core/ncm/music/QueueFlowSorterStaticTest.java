package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「一键排列」的<b>批次形态</b>：扫描在后台、可以取消、有上限，落排要带着它自己的依据。
 *
 * <p>算法本体由 {@code AutomixFlowPlannerTest} 真跑着测；落排那一步由
 * {@code QueueReorderIdentityTest} 真跑着测。这一类钉的是<b>结构</b>——那些一旦写错就不会报错、
 * 只会让用户在界面卡住几十秒或者队列被搅乱的事：</p>
 * <ol>
 *   <li>界面线程一次盘都不读。上一版整段扫描就写在按钮的点击回调里，几百首就是几百次读盘，
 *       表现是点下去整个界面冻住；</li>
 *   <li>后台那一趟必须是<b>分片</b>的。后台池只有 1..2 条线程，同一口池跑着交接用的预备解码，
 *       一口气读完几百首会把用户真正在等的那一次预备挤到后面；</li>
 *   <li>取消只能停止扫描，不能留下「排到一半」的队列；</li>
 *   <li>落排必须带着扫描时那份快照，否则一份过期的排布照样能落进队列。</li>
 * </ol>
 */
class QueueFlowSorterStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("按钮不再替用户读盘：界面只剩点按、看进度、取消三件事")
    void thePanelNeverPaysForTheScan() throws Exception {
        String panel = read(screens("ncm", "panels", "PlayQueuePanel.java"));
        assertFalse(panel.contains("sortQueueByFlow"),
                "点击回调里不许再有任何同步扫描");
        assertTrue(panel.contains("QueueFlowSorter.cancel()"), "跑着的时候这一击是取消");
        assertTrue(panel.contains("QueueFlowSorter.request()"), "空闲时才是新请求");
        assertTrue(panel.contains("\"取消\" : \"排列\""),
                "同一颗按钮两种意思要写在字上；否则用户按下去不知道自己在做什么");
        assertTrue(panel.contains("QueueFlowSorter.noticeSerial()"),
                "结论什么时候到货只有批次知道，界面靠序号重新计时，不能每帧刷满倒计时");
        String click = between(panel, "flowSort.setOnClickCallback", "LabelWidget flowSortText");
        assertFalse(click.contains("flowNoticeUntil"),
                "文案与倒计时都不该由点击那一路自己存——否则后台到货的那句结论永远显示不出来");

        String cloud = read(music("CloudMusic.java"));
        assertFalse(cloud.contains("public static String sortQueueByFlow"),
                "同步那一版要真的删掉，留一个没人调的入口等于留一条回退的路");
    }

    @Test
    @DisplayName("扫描分片、可取消、且只读不扫")
    void theScanIsChunkedCancellableAndReadOnly() throws Exception {
        String sorter = read(music("QueueFlowSorter.java"));
        assertTrue(sorter.contains("BackgroundWork.submit(batch)") && sorter.contains("BackgroundWork.submit(this)"),
                "第一批入队 + 每片结束重新入队：缺一半就退化成「一口气占住后台池」");
        assertTrue(sorter.contains("CHUNK_SONGS"), "一片多少首要写成常量，不能靠手感");
        assertTrue(sorter.contains("MAX_SONGS"), "一次最多看前多少首要有上限");
        assertTrue(sorter.contains("if (cancelled || running != this) return;"),
                "取消位与「还是不是当前这批」都要在片头查：迟到的分片不许覆盖新一轮的进度");

        // 一次排列不该触发整曲解码：那是预热（用户再按一次）的活儿。
        assertFalse(sorter.contains("AutomixEnvelope.ensure("),
                "批次里不许扫描整曲，只许读已经落盘的边车");
        assertFalse(sorter.contains("loadOrAnalyse"),
                "也不许起 Python 分析：几百首「顺手分析一下」会把后台池烧穿");
        assertTrue(sorter.contains("WARM_LIMIT"), "补料要有上限，剩下的一首首等它们自己被预备时补");

        // 落完排才让位：反过来中间那一瞬界面显示空闲，用户再按一次就两批同时改同一段队列。
        int finish = sorter.indexOf("finish();");
        int release = sorter.lastIndexOf("running == this) running = null;");   // 末尾那处是 finally 里的让位
        int guard = sorter.indexOf("if (running == batch) running = null;");
        assertTrue(finish > 0 && release > finish, "先落排、再让位（让位排在 finally 里）");
        assertTrue(sorter.indexOf("} finally {") > finish && sorter.indexOf("} finally {") < release,
                "让位必须排在 finally：中途抛出的一趟意外要是把 running 钉住了，按钮就永远停在「取消」");
        assertTrue(sorter.contains("resubmitted"),
                "重新入队的那一批不能被 finally 当成已完结，否则下一片还没跑就先被判定空闲");
        assertTrue(guard > 0 && guard < finish, "入队被拒时也要让位，但不许把「当前这批」记错");
    }

    @Test
    @DisplayName("落排带着它的依据：快照对不上就整个不改")
    void applyCarriesTheSnapshotThatJustifiesIt() throws Exception {
        String sorter = read(music("QueueFlowSorter.java"));
        assertTrue(sorter.contains("reorderQueuedNext(order, songs)"),
                "落排要交回扫描时那份快照，光交排布等于没交有效期");

        String cloud = read(music("CloudMusic.java"));
        String apply = between(cloud, "public static boolean reorderQueuedNext(int[] order, List<Music> expected)",
                "从用户队列里移除第");
        assertTrue(apply.contains("expected.size() != order.length"), "快照与排布必须一样长");
        assertTrue(apply.contains("queue.get(slot) != expected.get(i)"),
                "逐格比身份：只比长度挡不住「少一首又多一首」");
        assertTrue(apply.contains("size < order.length"),
                "允许只排前缀（有上限），所以旧的 size != order.length 那种「整段」假设要放开");
        assertTrue(apply.indexOf("expected.get(i)") < apply.indexOf("boolean[] seen"),
                "身份校验排在改动之前：先确认再动手，反过来就是先写坏一半");
        assertTrue(apply.contains("for (int i = 0; i < order.length; i++) queue.set"),
                "只覆盖扫过的那几格，第 n 格之后原位不动");
    }

    @Test
    @DisplayName("进阶模式关掉时，这一条等于不存在")
    void nothingHappensWhileAdvancedModeIsOff() throws Exception {
        String sorter = read(music("QueueFlowSorter.java"));
        String request = between(sorter, "public static synchronized String request()", "private static String startBatch");
        assertTrue(request.indexOf("isAdvancedModeEnabled()") < request.indexOf("getQueuedNextSongs"),
                "第一道闸就是总开关：关掉它连队列快照都不该读");
        assertFalse(request.contains("BackgroundWork.submit("),
                "入队排在闸门之后，别留一个「关着但每次点都提交一趟」的实现");
    }

    private static Path music(String name) {
        return ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music", name));
    }

    private static Path screens(String... parts) {
        java.util.List<String> path = new java.util.ArrayList<>();
        path.add("com");
        path.add("muoniumplayer");
        path.add("core");
        path.add("screens");
        for (String part : parts) path.add(part);
        return JAVA.resolve(Paths.get(String.join(java.io.File.separator, path)));
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "源码里找不到开头：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到结尾（在 " + from + " 之后）：" + to);
        return source.substring(start, end);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
