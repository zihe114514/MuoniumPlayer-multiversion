package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「A-Z」快捷面板分列算法的探针。
 *
 * <p>面板的行数是随配置变的，最长的一档（「隐现」背景 + 自定义配色 + 带四条参数滑块的歌词样式）
 * 有三十行、八百多像素高。此前遇到这种配置只有一个手段：把整块面板连同文字等比缩小，缩到
 * {@code MIN_UI_SCALE} 就停手——可 0.62 只是「比原来好看一点」，一列八百多像素对上半屏的可视
 * 高度，缩到底也塞不进去，结果是字小得看不清、底部还要滚。现在改成先分列。</p>
 *
 * <p>这里钉住的是分列算法的硬性要求：
 * ① 最长的那一档在标准视口（窗口自适应的逻辑尺寸 960×540）里分两列就放得下，且<b>一点都不用
 * 缩小</b>；② 面板宽度不许越过歌词正文列的左边界；③ 栏目少的时候仍然是原来那一列，尺寸逐像素
 * 一致；④ 分节小标题不会被孤零零留在列尾。常量是 {@code LyricStyleQuickPanel} 里那份的副本，
 * 改一处就要同步另一处。</p>
 */
class LyricQuickPanelColumnsProbeTest {

    // ---- LyricStyleQuickPanel 的常量副本 ----
    private static final double MARGIN = 12.0;
    private static final double BUTTON_SIZE = 24.0;
    private static final double PANEL_WIDTH = 178.0;
    private static final double PANEL_PADDING = 10.0;
    private static final double TITLE_HEIGHT = 18.0;
    private static final double ROW_HEIGHT = 30.0;
    private static final double COLUMN_GAP = 8.0;
    private static final int MAX_COLUMNS = 3;
    private static final double PANEL_RIGHT_GAP = 8.0;
    private static final double PICK_ROW_HEIGHT = 26.0;
    private static final double SECTION_HEIGHT = 17.0;

    /** 标题 + 上下内边距，分列也省不掉的那部分高度。 */
    private static final double CHROME_HEIGHT = PANEL_PADDING * 2 + TITLE_HEIGHT;

    /** 窗口自适应开启时的标准逻辑视口（16:9）。 */
    private static final double VIEW_WIDTH = 960.0;
    private static final double VIEW_HEIGHT = 540.0;

    @Test
    @DisplayName("最长的一档栏目在标准视口里分两列放得下，且不需要任何缩小")
    void tallestConfigurationFitsInTwoColumnsWithoutScaling() {
        double[] heights = tallestConfiguration();
        boolean[] keepWithNext = sectionsAreSticky(heights.length, 3, 10, 15, 22, 26);

        LyricsPanelGeometry.ColumnPacking packing = LyricsPanelGeometry.packQuickPanel(
                heights, keepWithNext, availableWidth(), availableHeight(),
                CHROME_HEIGHT, PANEL_WIDTH, COLUMN_GAP, MAX_COLUMNS);

        assertNotNull(packing.columnOf);
        assertEquals(2, packing.columns, "最长的一档应该分两列，而不是靠缩小塞进一列");
        assertEquals(PANEL_WIDTH * 2 + COLUMN_GAP, packing.width, 1.0e-9);

        // 关键：两列的净高都在可用高度以内 —— 一个像素的缩放都不需要，字号保持 1.0。
        assertTrue(CHROME_HEIGHT + packing.maxColumnHeight <= availableHeight(),
                "分列后仍然装不下，说明列数没选够：" + (CHROME_HEIGHT + packing.maxColumnHeight)
                        + " > " + availableHeight());

        // 两列高度最优：851 分成两列的最均衡合法分割是 412 / 439。
        assertEquals(439.0, packing.maxColumnHeight, 1.0e-9,
                "分列应按「最高的一列尽量矮」来切，而不是按行数平均或单趟贪心");

        // 面板宽度必须留在歌词列左边，越过去就是盖住歌词。
        assertTrue(packing.width <= availableWidth(),
                "面板宽过了歌词列的左边界：" + packing.width + " > " + availableWidth());
    }

    @Test
    @DisplayName("歌词列左边界就是面板宽度的硬上限")
    void panelNeverCrossesTheLyricColumn() {
        assertEquals(VIEW_WIDTH * .48 - PANEL_RIGHT_GAP - MARGIN, availableWidth(), 1.0e-9);
        // 面板已经占掉 178，还剩将近三倍宽度可用；但列数上限是 3，不允许无限横向铺开。
        assertTrue(availableWidth() > PANEL_WIDTH);
    }

    @Test
    @DisplayName("栏目少的时候还是原来那一列，尺寸与不分列时逐像素一致")
    void shortConfigurationsStayInASingleColumn() {
        double[] heights = {PICK_ROW_HEIGHT, PICK_ROW_HEIGHT, PICK_ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT};
        double total = 3 * PICK_ROW_HEIGHT + 2 * ROW_HEIGHT;

        LyricsPanelGeometry.ColumnPacking packing = LyricsPanelGeometry.packQuickPanel(
                heights, new boolean[heights.length], availableWidth(), availableHeight(),
                CHROME_HEIGHT, PANEL_WIDTH, COLUMN_GAP, MAX_COLUMNS);

        assertEquals(1, packing.columns);
        assertEquals(PANEL_WIDTH, packing.width, 1.0e-9);
        assertEquals(total, packing.maxColumnHeight, 1.0e-9,
                "单列时列高就是所有行高之和，与加进分列之前完全一致");
    }

    @Test
    @DisplayName("一列刚好装得下就不分列，溢出一行才分")
    void splitsOnlyWhenOneColumnOverflows() {
        // 净高上限 = 486 - 38 = 448。
        double[] fits = filledRows(14, ROW_HEIGHT);              // 420
        assertEquals(1, columnsFor(fits));

        double[] overflows = filledRows(15, ROW_HEIGHT);         // 450 > 448
        assertEquals(2, columnsFor(overflows));
    }

    @Test
    @DisplayName("窗口太窄时不强行分列，宁可保持一列")
    void narrowViewportStaysInASingleColumn() {
        double narrowWidth = 640.0;
        double room = LyricsPanelGeometry.lyricColumnLeft(0, narrowWidth) - PANEL_RIGHT_GAP - MARGIN;
        double[] heights = tallestConfiguration();
        boolean[] keepWithNext = sectionsAreSticky(heights.length, 3, 10, 15, 22, 26);

        LyricsPanelGeometry.ColumnPacking packing = LyricsPanelGeometry.packQuickPanel(
                heights, keepWithNext, room, availableHeight(),
                CHROME_HEIGHT, PANEL_WIDTH, COLUMN_GAP, MAX_COLUMNS);

        assertEquals(1, packing.columns, "两列会越过歌词列，这一档只能保持单列");
        assertEquals(PANEL_WIDTH, packing.width, 1.0e-9);
    }

    @Test
    @DisplayName("分节小标题不会孤零零留在列尾")
    void sectionHeadersNeverEndAColumn() {
        double[] heights = {ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, SECTION_HEIGHT, ROW_HEIGHT};
        boolean[] keepWithNext = sectionsAreSticky(heights.length, 4);

        int[] columnOf = LyricsPanelGeometry.packRows(heights, keepWithNext, 2);
        assertNotNull(columnOf);
        // 断点必须在第 4 行（小标题）之前，而不是在它之后——「小标题在列尾、它管的那一行在下一列」
        // 正是这一条要挡住的形状。
        assertArrayEquals(new int[]{0, 0, 0, 1, 1, 1}, columnOf, "断点必须落在小标题之前");
        assertEquals(columnOf[4], columnOf[5], "小标题必须和它管的第一行同列");
    }

    @Test
    @DisplayName("列数同时受可用宽度与最大列数限制")
    void columnCountIsCapped() {
        double[] heights = filledRows(60, ROW_HEIGHT);

        LyricsPanelGeometry.ColumnPacking byWidth = LyricsPanelGeometry.packQuickPanel(
                heights, new boolean[heights.length], availableWidth(), availableHeight(),
                CHROME_HEIGHT, PANEL_WIDTH, COLUMN_GAP, MAX_COLUMNS);
        assertEquals(2, byWidth.columns, "960 宽的视口里只放得下两列");
        assertEquals(60 * ROW_HEIGHT / 2.0, byWidth.maxColumnHeight, 1.0e-9);

        LyricsPanelGeometry.ColumnPacking byCap = LyricsPanelGeometry.packQuickPanel(
                heights, new boolean[heights.length], Double.MAX_VALUE, availableHeight(),
                CHROME_HEIGHT, PANEL_WIDTH, COLUMN_GAP, MAX_COLUMNS);
        assertEquals(MAX_COLUMNS, byCap.columns, "宽度不受限时也只到 MAX_COLUMNS");
        assertEquals(60 * ROW_HEIGHT / 3.0, byCap.maxColumnHeight, 1.0e-9);
    }

    @Test
    @DisplayName("面板拿得到视口宽度：render 的调用点必须传进去")
    void quickPanelReceivesViewWidth() throws IOException {
        String panel = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", "MusicLyricsPanel.java")));
        assertTrue(panel.contains("styleQuickPanel.render(mouseX, mouseY, posX, posY, width, height, alpha)"),
                "少了视口宽度，面板就算不出「最宽能到哪」，分列会一直以为可以横着铺");
    }

    // ---- 构造行表 ----

    /**
     * 最长的那一档：「隐现」背景（含像素 + 流体两段参数与可读性叠色）+ 四条样式滑块的歌词样式。
     * 32 行，合计 851。
     */
    private static double[] tallestConfiguration() {
        return new double[]{
                PICK_ROW_HEIGHT, PICK_ROW_HEIGHT, PICK_ROW_HEIGHT,   // 歌词样式 / 歌词背景 / 主题配色
                SECTION_HEIGHT, PICK_ROW_HEIGHT, PICK_ROW_HEIGHT,    // 隐现背景 / 显示图层 / 颜色来源
                PICK_ROW_HEIGHT, PICK_ROW_HEIGHT, PICK_ROW_HEIGHT,   // 三个「隐现」开关
                ROW_HEIGHT,                                          // 可读性叠色透明度
                SECTION_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT,   // 像素背景四条
                SECTION_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT,   // 流体背景四条
                ROW_HEIGHT, ROW_HEIGHT,                              // 全屏溢光 / 逐字放大
                // 歌词同步 / 歌词来源 / 重新匹配 / 时间偏移
                SECTION_HEIGHT, PICK_ROW_HEIGHT, PICK_ROW_HEIGHT, ROW_HEIGHT,
                SECTION_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, ROW_HEIGHT, PICK_ROW_HEIGHT
        };
    }

    private static double[] filledRows(int count, double height) {
        double[] heights = new double[count];
        Arrays.fill(heights, height);
        return heights;
    }

    private static boolean[] sectionsAreSticky(int count, int... sections) {
        boolean[] keepWithNext = new boolean[count];
        for (int section : sections) {
            keepWithNext[section] = true;
        }
        return keepWithNext;
    }

    private static int columnsFor(double[] heights) {
        return LyricsPanelGeometry.packQuickPanel(heights, new boolean[heights.length],
                availableWidth(), availableHeight(), CHROME_HEIGHT, PANEL_WIDTH, COLUMN_GAP,
                MAX_COLUMNS).columns;
    }

    /** 与 {@code LyricStyleQuickPanel.availableWidth()} 同式：歌词列左边界再往前留一个空隙。 */
    private static double availableWidth() {
        return LyricsPanelGeometry.lyricColumnLeft(0, VIEW_WIDTH) - PANEL_RIGHT_GAP - MARGIN;
    }

    /** 与 {@code LyricStyleQuickPanel.availableHeight()} 同式：视口底边减掉上边距、按钮与面板起点。 */
    private static double availableHeight() {
        return VIEW_HEIGHT - MARGIN - (MARGIN + BUTTON_SIZE + 6);
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
