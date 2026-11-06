package com.muoniumplayer.core.input;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住触屏手势层在各个消费面的接线形状。手势识别器本身有真单元测试
 * （{@code TouchGesturesTest}）；这里守的是「哪条线接到了哪」——符号表尤其不能错：
 * ScrollPanel 与歌词页的滚动方向<b>相反</b>，接错一处就是「往上滑内容往下跑」。
 */
class TouchScrollWiringStaticTest {

    private static String read(String relative) throws Exception {
        Path path = ProjectPaths.sharedJava().resolve(
                Paths.get("com", "muoniumplayer").resolve(relative));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    void ncm延迟点击分发的接线完整() throws Exception {
        String source = read(Paths.get("core", "screens", "ncm", "NCMScreen.java").toString());
        assertTrue(source.contains("private void dispatchClick("),
                "原点击主体必须整体搬进 dispatchClick，回滚路径与松手补发共用同一段");
        assertTrue(source.contains("pendingClickActive") && source.contains("pendingClickButton"),
                "按下只记账（pendingClick 字段）");
        assertTrue(source.contains("if (TouchSettings.ENABLED) {")
                        && source.contains("dispatchClick(mouseX, mouseY, mouseButton,"),
                "功能关闭时必须立即分发（一键回滚旧行为）；联想可见性以按下快照传入");
        assertTrue(source.contains("public void mouseReleased(int mX, int mY, int state)"),
                "松手沿必须接管补发");
        assertTrue(source.contains("state == 0 && !TouchGestures.get().consumeTap()"),
                "左键必须经手势层结算：拖动吞掉、点按补发");
        assertTrue(source.contains("TouchGestures.get().advance(System.nanoTime()"),
                "drawScreen 头部必须推进手势层（消费者之前的唯一喂数点）");
        // 穿透修复（2026-11-04）：联想面板状态每帧会变，可见性必须以按下瞬间的快照裁决，
        // 否则「按下面板在、松手面板没了」的点击会从联想层漏到底下的歌单条目。
        assertTrue(source.contains("pendingClickSuggestionVisible"),
                "按下时必须拍联想可见性快照（pendingClickSuggestionVisible）");
        assertTrue(source.contains("suggestionLayerVisibleNow()"),
                "快照必须取自 NavigateBar#suggestionLayerVisibleNow");
    }

    @Test
    void scrollPanel拖动分支的符号与认领齐全() throws Exception {
        String source = read(Paths.get("core", "rendering", "ui", "container", "ScrollPanel.java").toString());
        assertTrue(source.contains("private void advanceTouchDrag()"),
                "ScrollPanel 必须有拖动滚动分支（一个类覆盖全部列表面）");
        assertTrue(source.contains("this.targetScrollOffset - dy"),
                "跟手符号必须与滚轮一致（offset 0 = 顶：target -= dy）");
        assertTrue(source.contains("Math.signum(velocity) * distance"),
                "甩动必须延续手指方向（target -= signum(v)·distance）");
        assertTrue(source.contains("gestures.claimedBy() == null") && source.contains("gestures.claim(this)"),
                "认领必须先查无人认领再独占");
        assertTrue(source.contains("this.alignment != Alignment.HORIZONTAL"),
                "横向排版（私人FM chips）本期不许认领纵向手势");
        assertTrue(source.contains("this.testHovered(gestures.startX(), gestures.startY())"),
                "认领条件必须包含「按下起点在本视口内」");
    }

    @Test
    void 歌词页符号与scrollPanel相反且点击走松手沿() throws Exception {
        String source = read(Paths.get("core", "screens", "ncm", "MusicLyricsPanel.java").toString());
        assertTrue(source.contains("scrollTarget += dy"),
                "歌词页拖动符号与滚轮一致而与 ScrollPanel 相反（+= dy）");
        assertTrue(source.contains("scrollTarget += Math.signum(velocity) * distance"),
                "歌词页甩动延续手指方向（+= signum(v)·distance）");
        assertTrue(source.contains("tapReleaseEdge"),
                "歌词行 seek 必须走松手沿点按判定（tapReleaseEdge）");
        assertTrue(source.contains("this.styleQuickPanel.advanceTouchDrag()"),
                "快捷面板的触摸滚动由歌词页驱动（先于歌词列认领）");
    }

    @Test
    void 触屏门禁与联想快照接线() throws Exception {
        String settings = read(Paths.get("core", "settings", "TouchSettings.java").toString());
        // 门禁（2026-11-04）：手势层存在的唯一理由是 FCL/Zalith 把触屏映射成左键。
        // 桌面 Windows/Linux/macOS 没有这层映射，必须整体关闭——否则队列排序要长按、
        // 全部点击有延迟，用户感知就是「坏了」。
        assertTrue(settings.contains("ENABLED = detectTouchRuntime()"),
                "总闸必须由启动期门禁判定，不是恒 true");
        for (String signal : new String[]{"pojav.path.minecraft", "FCL_NATIVEDIR",
                "FCL_VERSION_CODE", "POJAV_NATIVEDIR", "startsWith(\"Android\")"}) {
            assertTrue(settings.contains(signal), "门禁缺家族指纹 " + signal);
        }
        // 桌面上用户配置里的 enabled 不许把手势层重新打开。
        assertTrue(settings.contains("if (ENABLED) {"),
                "配置文件的 enabled 只允许在触屏会话里覆盖");

        String screen = read(Paths.get("core", "screens", "ncm", "NCMScreen.java").toString());
        String bar = read(Paths.get("core", "screens", "ncm", "panels", "NavigateBar.java").toString());
        // 快照两件套：可见性防穿透、悬停行防「点了没反应」（面板换茬后几何命中落空）。
        assertTrue(screen.contains("pendingClickSuggestionVisible")
                        && screen.contains("pendingClickSuggestionRow"),
                "按下沿必须拍联想可见性 + 悬停行两份快照");
        assertTrue(screen.contains("suggestionVisible, suggestionRow)"),
                "松手分发必须把两份快照都传给联想层");
        assertTrue(bar.contains("suggestionHoveredRowAt(double mouseX, double mouseY)"),
                "NavigateBar 必须提供按下沿悬停行探针（接收已换算的布局坐标）");
        assertTrue(screen.contains("suggestionHoveredRowAt(mouseX, mouseY)"),
                "悬停行快照必须用 mouseClicked 已换算的布局坐标");
        assertTrue(bar.contains("if (index < 0 && pressHoveredRow >= 0) index = pressHoveredRow;"),
                "几何命中落空时必须退回按下沿的悬停行");
        // 回归防线（2026-11-04 交叉评审）：五棵树的 compat Mouse.getY() 是底原点
        // framebuffer 轴，联想层自己从裸 Mouse 重推坐标必然翻错方向——命中必须复用
        // NCMScreen 已换算的布局坐标。
        assertFalse(bar.contains("compat.lwjgl2.Mouse"),
                "联想层禁止从裸 Mouse 重推坐标：getY() 是底原点 framebuffer 轴，"
                        + "重推必翻错；必须用 mouseClicked 已换算的布局坐标");
    }

    @Test
    void 队列抽屉长按抓取与滚动互斥() throws Exception {
        String source = read(Paths.get("core", "screens", "ncm", "panels", "PlayQueuePanel.java").toString());
        assertTrue(source.contains("maybeBeginDragByLongPress"),
                "行抓取排序必须由长按触发");
        assertTrue(source.contains("gestures.longPressElapsed()"),
                "长按边沿来自手势层（锁轴后永不再触发）");
        assertTrue(source.contains("!TouchSettings.ENABLED") && source.contains("beginDrag(this, relativeY)"),
                "手势层关闭时必须回退「点击即抓取」的旧行为");
        assertTrue(source.contains("PlayQueuePanel.this.draggingRow != null"),
                "行拖动期间 QueueList 的触摸滚动必须让位");
    }

    @Test
    void 横向拖动站点全部让位纵向手势() throws Exception {
        for (String relative : new String[]{
                Paths.get("core", "screens", "ncm", "VolumeControl.java").toString(),
                Paths.get("core", "screens", "ncm", "ViperOverlay.java").toString(),
                Paths.get("core", "screens", "ncm", "ThemePaletteOverlay.java").toString(),
                Paths.get("core", "screens", "ncm", "LyricStyleQuickPanel.java").toString(),
                Paths.get("core", "screens", "ncm", "MusicLyricsPanel.java").toString(),
        }) {
            String source = read(relative);
            assertTrue(source.contains("isVerticalDrag()"),
                    relative + " 必须对纵向手势让位（滚列表滑过滑条不得误触）");
        }
        // ControlsBar 的进度条现在支持按住拖动 seek：按下起点落在轨道/扩展触发带内才认领，
        // 拖动中按预览位置画，松手才结算。横向拖动在这里就是 seek，不是滚列表。
        String controls = read(Paths.get("core", "screens", "ncm", "panels", "ControlsBar.java").toString());
        assertTrue(controls.contains("progressDragging") && controls.contains("isProgressHit(mouseX, mouseY)"),
                "底栏进度条必须支持按住拖动 seek，且按下起点限定在轨道/触发带内");
        assertTrue(controls.contains("progressDragFraction") && controls.contains("CloudMusic.seekToMillis"),
                "拖动预览位置与松手结算的 seek 必须同时存在");
        // ThemePaletteOverlay 的滑条补上了按下即起手（延迟分发后 click 不再承载拖动起手）。
        String palette = read(Paths.get("core", "screens", "ncm", "ThemePaletteOverlay.java").toString());
        assertTrue(palette.contains("this.dragging = true;\n                applyFromMouse(mouseX);"),
                "主题滑条必须有按下即起手的横向拖动（不依赖 click 事件）");
    }
}
