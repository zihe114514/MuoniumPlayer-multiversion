package com.muoniumplayer.core.input;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
                        && source.contains("dispatchClick(mouseX, mouseY, mouseButton);"),
                "功能关闭时必须立即分发（一键回滚旧行为）");
        assertTrue(source.contains("public void mouseReleased(int mX, int mY, int state)"),
                "松手沿必须接管补发");
        assertTrue(source.contains("state == 0 && !TouchGestures.get().consumeTap()"),
                "左键必须经手势层结算：拖动吞掉、点按补发");
        assertTrue(source.contains("TouchGestures.get().advance(System.nanoTime()"),
                "drawScreen 头部必须推进手势层（消费者之前的唯一喂数点）");
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
        // ControlsBar 的进度条 seek 同时改成了松手沿点按。
        String controls = read(Paths.get("core", "screens", "ncm", "panels", "ControlsBar.java").toString());
        assertTrue(controls.contains("tapEdge") && controls.contains("lastPressWasDrag()"),
                "底栏进度条 seek 必须走松手沿 + 非拖动判定");
        // ThemePaletteOverlay 的滑条补上了按下即起手（延迟分发后 click 不再承载拖动起手）。
        String palette = read(Paths.get("core", "screens", "ncm", "ThemePaletteOverlay.java").toString());
        assertTrue(palette.contains("this.dragging = true;\n                applyFromMouse(mouseX);"),
                "主题滑条必须有按下即起手的横向拖动（不依赖 click 事件）");
    }
}
