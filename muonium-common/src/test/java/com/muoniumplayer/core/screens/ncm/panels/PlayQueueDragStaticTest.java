package com.muoniumplayer.core.screens.ncm.panels;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待播抽屉里「按住一行拖来排序」的离线契约测试。
 *
 * <p>报上来的症状是<b>拖到一半，手里那张卡片被裁掉了</b>。根因是拖动坐标夹错了范围：{@code dragY}
 * 是内容坐标，行落在屏幕上的位置还要减掉列表的滚动量，所以只夹内容范围的话，边缘自动滚动一把内容
 * 挪走（或者指针冲得比滚动快），行就跑到视口之外 —— 轻则被 scissor 削掉半张，重则被判定成
 * 「完全滚出视口」整行隐藏。这一条是纯算术，所以{@link #clampDragToViewport} 是真跑的，
 * 从 1 行到 14 行、视口从一行到整个列表、指针从极上到极下全扫一遍。</p>
 *
 * <p>其余几条（裁剪矩形随抬起量外扩、松手回落期间不许把行重新削掉、被拖的行永远不隐藏、
 * 边缘自动滚动按毫秒而不是按帧）只能读源码：这些类一加载就要建 GL 上下文。</p>
 */
class PlayQueueDragStaticTest {

    private static final Path PANEL = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "PlayQueuePanel.java"));

    @Test
    @DisplayName("被拖的那一行始终留在视口里，指针甩出界也只是被边缘钉住")
    void draggedRowNeverLeavesTheViewport() {
        double rowHeight = PlayQueuePanel.ROW_HEIGHT;
        double slot = PlayQueuePanel.SLOT_HEIGHT;
        int checked = 0;
        for (int rowCount = 1; rowCount <= 14; rowCount++) {
            double contentHigh = Math.max(0.0, (rowCount - 1) * slot);
            double contentHeight = rowCount * slot;
            for (double viewport = slot; viewport <= contentHeight + slot; viewport += 7.0) {
                double maxScroll = Math.max(0.0, contentHeight - viewport);
                for (double scroll = 0.0; scroll <= maxScroll + .001; scroll += Math.max(1.0, maxScroll / 6.0)) {
                    for (double desired = -900.0; desired <= 900.0; desired += 37.0) {
                        double clamped = PlayQueuePanel.clampDragToViewport(
                                desired, scroll, viewport, contentHigh, 0.0);
                        String where = "行数=" + rowCount + " 视口=" + viewport + " 滚动=" + scroll
                                + " 指针=" + desired + " → " + clamped;
                        // 顶边不许跑到视口上沿之外。
                        assertTrue(clamped >= scroll - 1.0e-6, "行被拖到视口上方被裁掉了：" + where);
                        // 整行不许沉到视口下沿之外（顶边最多到 viewport - rowHeight）。
                        assertTrue(clamped <= scroll + viewport - rowHeight + 1.0e-6,
                                "行被拖到视口下方被裁掉了：" + where);
                        // 下标换算用的还是内容坐标，越界就会把行插到队列外面。
                        assertTrue(clamped >= -1.0e-6 && clamped <= contentHigh + 1.0e-6,
                                "夹出了内容范围：" + where);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 5000, "这一轮实际只检查了 " + checked + " 种组合，覆盖面不够");
    }

    @Test
    @DisplayName("指针在可视范围内时行严格 1:1 跟着手，不被夹偏")
    void dragFollowsThePointerInsideTheFreeRange() {
        double slot = PlayQueuePanel.SLOT_HEIGHT;
        double contentHigh = 9 * slot;
        // 视口五行、滚到中间：这一段里指针给什么就该是什么，夹取不该提前介入。
        double scroll = 3 * slot;
        double viewport = 5 * slot;
        for (double desired = scroll + 1.0; desired < scroll + viewport - PlayQueuePanel.ROW_HEIGHT - 1.0;
             desired += 5.0) {
            assertEquals(desired, PlayQueuePanel.clampDragToViewport(desired, scroll, viewport, contentHigh, 0.0),
                    1.0e-9, "自由行程里被夹动了：手感会变成「拖不动」");
        }
        // 抬起时的放大余量要留出来，否则放大出来的那一圈正好被裁剪矩形削掉。
        double overhang = 1.2;
        assertEquals(scroll + overhang,
                PlayQueuePanel.clampDragToViewport(scroll - 500.0, scroll, viewport, contentHigh, overhang),
                1.0e-9, "上边界没有按放大余量留白");
    }

    @Test
    @DisplayName("裁剪矩形随抬起量外扩，松手回落期间也不收回")
    void clipRectTracksTheLiftUntilTheRowSettles() throws IOException {
        String body = read(PANEL);
        String clip = between(body, "protected void beforeRenderChildren", "protected void afterRenderChildren");
        assertTrue(clip.contains("dragOverhangX") && clip.contains("dragOverhangY"),
                "列表的裁剪矩形又写死成自己的框：提起来放大的那一行左右各被削掉约 3 像素");
        // 外扩量在父面板里已经按抽屉内边距夹过一次，这里再夹一道只会掩盖上层的错。
        assertTrue(body.contains("Math.min(DRAWER_PADDING,"),
                "外扩没有以抽屉内边距封顶：行会画出抽屉外面");
        String settling = between(body, "double lifted = 0.0;", "dragOverhangY =");
        assertTrue(settling.contains("for (QueueRow row : this.rows)") && settling.contains("this.fadingRows"),
                "外扩量又改成只看正在被拖的那一行：松手瞬间 draggingRow 清空，回落中的行会被重新削掉一截");
        assertFalse(body.contains("draggingRow == null ? 0.0 :"),
                "外扩量按 draggingRow 取：松手那一帧就归零了");
    }

    @Test
    @DisplayName("手里那一行永远可见、永远吃点击")
    void draggingRowIsNeverHidden() throws IOException {
        String body = read(PANEL);
        String layout = between(body, "private void applyRowLayout", "private boolean matchesRows");
        assertTrue(layout.contains("row.setHidden(offscreen && !dragging)"),
                "被拖的行还会被 offscreen 判成隐藏：整张卡片凭空消失");
        assertTrue(layout.contains("(fullyInside || dragging)"),
                "被拖的行在边界上会失去命中框：拖到边缘就断手");
    }

    @Test
    @DisplayName("边缘自动滚动按毫秒走，放大比例只有一份常量")
    void edgeScrollIsTimeBasedAndScaleIsShared() throws IOException {
        String body = read(PANEL);
        String drag = between(body, "private void updateDrag", "private static double edgeStrength");
        assertTrue(drag.contains("DRAG_AUTO_SCROLL_PPS * frameMs"),
                "自动滚动又回到每帧固定像素：220fps 与 60fps 之间手感差三倍多");
        assertFalse(drag.contains("scrollTarget -= 2.5") || drag.contains("scrollTarget += 2.5"),
                "还留着按帧的 2.5 像素");
        assertTrue(between(body, "this.setTransformations", "this.setOnClickCallback")
                        .contains("1.0 + DRAG_LIFT_SCALE * this.lift"),
                "放大比例与裁剪外扩用的不是同一个常量：改了比例就会重新被削");
        // .045 只许出现在 DRAG_LIFT_SCALE 那一行常量上。
        assertEquals(1, count(body, ".045"), "还有别处写死了放大比例");
    }

    private static int count(final String source, final String needle) {
        int total = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }

    private static String between(final String source, final String from, final String until) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点 " + from);
        int end = source.indexOf(until, start);
        assertTrue(end > start, "找不到终点 " + until);
        return source.substring(start, end);
    }

    /** 读源码并<b>剥掉注释</b>：实现自己的注释里就写着被禁的写法，不剥会自己把自己钉红。 */
    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }
}
