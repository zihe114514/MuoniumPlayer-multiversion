package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.muoniumplayer.testsupport.ProjectPaths.sharedJava;
import com.muoniumplayer.testsupport.ProjectPaths;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「把 automix 的接缝画在进度条上」这条线的接线契约。
 *
 * <p>算术部分在 {@link AutomixCueMarkerTest} 里真跑，这里只管三件事：<b>读数只有一个来源</b>、
 * <b>三处绘制点都接上了</b>、以及<b>没有碰到拖进度条的命中判定</b>。第三条是这条线唯一可能
 * 伤害现有功能的地方——那三处拖动逻辑各自带一套坐标与 {@code prevMouse} 记账，新代码一旦
 * 插进判定链中间，症状是「进度条某些区段拖不动」，而那种问题在日常使用里很难被立刻发现。</p>
 *
 * <p>为什么用扫源码而不是真跑：这三处都要 Minecraft 客户端与 Skija 上下文才走得动。
 * 顺序断言读的是字符位置，而这里要钉的恰恰就是「谁在前谁在后」。</p>
 */
class AutomixCueMarkerStaticTest {

    private static final Path CLOUD_MUSIC = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "ncm", "music", "CloudMusic.java"));
    private static final Path SETTINGS = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "ncm", "music", "AutomixSettings.java"));
    private static final Path PAINTER = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "rendering", "AutomixCuePainter.java"));
    private static final Path HUD_WIDGET = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "widget", "impl", "MusicInfoWidget.java"));
    private static final Path CONTROLS = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "ControlsBar.java"));
    private static final Path LYRICS = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "MusicLyricsPanel.java"));
    private static final Path MODEL = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "hud", "HudSettingsModel.java"));
    private static final Path CLASSIC_EDITOR = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "hud", "GuiHudEditor.java"));
    private static final Path CLASSIC_LAYOUT = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "hud", "HudEditorLayout.java"));

    private static String read(Path path) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("读数只有 CloudMusic.automixCueNow() 这一个入口，且三处进度条各调一次")
    void singleSourceThreeSites() throws IOException {
        int calls = 0;
        Path[] sites = {HUD_WIDGET, CONTROLS, LYRICS};
        for (Path site : sites) {
            String body = read(site);
            assertEquals(1, count(body, "CloudMusic.automixCueNow();"),
                    site.getFileName() + " 应该恰好取一次本帧读数");
            assertTrue(body.contains("AutomixCuePainter.paint("),
                    site.getFileName() + " 没有把记号交给共用的绘制层，三处的画法会各自跑偏");
            calls += count(body, "CloudMusic.automixCueNow();");
        }
        assertEquals(3, calls, "这条线应该只有三处读者：游戏内信息条、播放页控制栏、歌词页");
    }

    @Test
    @DisplayName("记号画在拖拽判定之后，命中链一行未动")
    void drawingStaysBehindTheHitTest() throws IOException {
        String controls = read(CONTROLS);
        // 控制栏那处进度条是一个匿名子类：命中走 prevMouse + seekToMillis，画记号必须排在整块
        // 判定之后，否则新增语句会插进「按下—拖动—松开」那套记账中间。
        ordered(controls, "拖拽命中在前、记号在后",
                "CloudMusic.seekToMillis((float) (percent * total));",
                "drawAutomixCue(this.getX()");

        String lyrics = read(LYRICS);
        ordered(lyrics, "歌词页：填色画完再画记号，而命中判定仍在记号之后",
                "double fillWidth = progressBarWidth * perc;",
                "drawAutomixCue(elementsXOffset",
                "boolean hoveringProgressBar = progressBarDragging || this.isHovered(");

        String widget = read(HUD_WIDGET);
        ordered(widget, "信息条：进度填色之后才是记号",
                "if (playbackFillWidth > 0)",
                "drawAutomixCue(imgX + imgSize + imgSpacing");
    }

    @Test
    @DisplayName("取不到计划就不画：四道短路都在，顺序是先设置后线程")
    void getterShortCircuitsBeforeTouchingThePlayThread() throws IOException {
        // CloudMusic 有四千多行，"if (ExternalPlayback.isMirroring()) return null;" 这种句子
        // 在别人那里也出现过。所以先看这个方法自己那一块，再谈顺序。
        String getter = between(read(CLOUD_MUSIC),
                "public static AutomixCueMarker automixCueNow() {",
                "return ((PlayThread) thread).automixCueView();");
        ordered(getter, "关设置 → 关无缝 → 镜像态 → 拿不到播放线程",
                "if (!AutomixSettings.isCueMarkerEnabled() || !AutomixSettings.isEnabled()) return null;",
                "if (ExternalPlayback.isMirroring()) return null;",
                "if (!(thread instanceof PlayThread)) return null;");
    }

    @Test
    @DisplayName("先问进行中的交接、再问预备：进度条归哪一首决定取哪个位置")
    void playThreadReadsTheFadeBeforeTheArm() throws IOException {
        String view = between(read(CLOUD_MUSIC),
                "private AutomixCueMarker automixCueView() {",
                "private static long deckDurationMillis(AudioPlayer deck) {");
        ordered(view, "交接进行中读 incomingCueMillis，预备期读 fireMillis",
                "AutomixFade fade = activeFade;",
                "fade.plan.incomingCueMillis + fade.overlapMillis",
                "ArmedTrack track = armed;",
                "return new AutomixCueMarker(plan.fireMillis,");
        assertTrue(view.contains("if (!armStillValid(track.generation, track.queueRevision)) return null;"),
                "预备已经作废却仍然预报位置：那份计划描述的是一次不会发生的交接");
    }

    @Test
    @DisplayName("开关默认打开，且真的落盘")
    void toggleDefaultsOnAndPersists() throws IOException {
        String body = read(SETTINGS);
        assertTrue(body.contains("private static boolean cueMarker = true;"),
                "「切点标记」出厂应为打开：它只读一份已经算好的计划，不参与任何时序决策");
        assertTrue(body.contains("root.addProperty(\"cueMarker\", cueMarker);"), "写了字段却没落盘");
        assertTrue(body.contains("root.get(\"cueMarker\").getAsBoolean()"), "落盘了却没读回来");
        // 这个开关不改变规划结果，所以绝不能进计划缓存的键——进了会让全部已缓存计划无谓作废。
        String cache = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", "AutomixPlanCache.java")));
        assertEquals(-1, cache.indexOf("cueMarker"),
                "cueMarker 进了计划缓存的键：它不影响选点，进了会让所有已缓存计划白作废一次");
    }

    @Test
    @DisplayName("两个编辑器都有这一行，且经典编辑器的绘制与命中同序")
    void bothEditorsCarryTheRow() throws IOException {
        String model = read(MODEL);
        assertTrue(model.contains("toggle(\"切点标记\""),
                "HudSettingsModel 少了这一条：Folia 与进阶两版编辑器都是从它生成行的");
        assertTrue(model.indexOf("toggle(\"切点标记\"") < model.indexOf("choice(\"原版重叠时长\""),
                "条目顺序变了：它应当在「过渡动画」之后、「原版重叠时长」之前");

        String classic = read(CLASSIC_EDITOR);
        // 经典编辑器是手写的行表：绘制那一段带标签，命中那一段只按行号走。所以标签出现一次、
        // 动作接线出现一次，两者的相对位置要各自与邻居对齐。
        assertEquals(1, count(classic, "\"切点标记\""),
                "标签只在绘制段出现一次；命中段是按 rowY 走的匿名分支");
        assertEquals(1, count(classic, "setCueMarkerEnabled(!AutomixSettings.isCueMarkerEnabled())"),
                "命中段要么漏了这一行，要么接了两次");
        ordered(classic, "绘制的邻位与命中的邻位必须是同一对：都在过渡动画之后、重叠时长之前",
                "drawToggleRow(fr, \"过渡动画\"",
                "drawToggleRow(fr, \"切点标记\"",
                "AutomixSettings.setTransitionAnimationEnabled(enabled);",
                "setCueMarkerEnabled(!AutomixSettings.isCueMarkerEnabled())",
                "AutomixSettings.cycleOverlapSeconds();");

        String layout = read(CLASSIC_LAYOUT);
        assertTrue(layout.contains("colorRowHeight * 18"),
                "经典编辑器多了一行却没抬分段高度：这一页的滚动条会比内容短一格");
    }

    @Test
    @DisplayName("绘制层按分数画矩形，不依赖裁剪")
    void painterUsesRectanglesNotClipping() throws IOException {
        String body = read(PAINTER);
        assertTrue(body.contains("SkijaShapes.roundedRect("), "记号没走共用的几何层");
        // 只查真的用了裁剪没有，不查「stencil」这个词——这个类的注释里就在解释为什么不能用它。
        assertTrue(!body.contains("StencilClip") && !body.contains("enableStencil"),
                "这里动了模板裁剪：MC 1.8.9 主 framebuffer 没有模板缓冲，那在这棵树上不生效");
    }

    /** 取两个锚点之间那一段（含起点、不含终点），把顺序断言限定在一个方法体内。 */
    private static String between(String body, String start, String end) {
        int from = body.indexOf(start);
        assertTrue(from >= 0, "找不到方法起点：" + start);
        int to = body.indexOf(end, from);
        assertTrue(to > from, "找不到方法结尾：" + end);
        return body.substring(from, to);
    }

    private static void ordered(String body, String what, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int at = body.indexOf(needle);
            assertTrue(at >= 0, what + "：找不到 " + needle);
            assertTrue(at > previous, what + "：" + needle + " 的位置不对");
            previous = at;
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }
}
