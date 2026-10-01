package com.muoniumplayer.core.screens.ncm;

import java.util.Arrays;

/** 全屏歌词面板的无状态视口、计时与尺寸计算。 */
public final class LyricsPanelGeometry {

    private LyricsPanelGeometry() {
    }

    /**
     * 全屏歌词页整体的进场缩放。
     *
     * <p><b>已停用，恒返回 1</b>：入场动画定稿为只走不透明度（{@link CoverHeroTransition#contentAlpha()}），
     * 不再「由小变大覆盖播放器」。曾经的曲线是 {@code .952 + alpha * .048} 的镜头推入，想要
     * 恢复任何整体缩放，只改这一个方法即可 —— 接力封面的落点换算（{@code setTarget} 与
     * {@code captureTarget}）两侧都经过它，恢复后落点自动跟着对齐，不会先错一帧。</p>
     *
     * <p>不变式依旧成立：{@code alpha == 1} 时严格返回 1（现在是无条件成立），封面交接那一帧
     * 不会跳。</p>
     */
    static double contentScale(float alpha) {
        return 1.0;
    }

    static double smoothKaraokeProgress(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    static double characterKaraokeProgress(double wordProgress, int characterIndex) {
        return smoothKaraokeProgress(wordProgress - characterIndex);
    }

    static double lyricLineSpacing() {
        return 20;
    }

    static double lyricAnchorFraction() {
        return .25;
    }

    /**
     * 歌词正文列的左边界。
     *
     * <p>左栏（封面、曲目信息）与歌词列的分界线；也是全屏歌词页上一切浮层不许越过的<b>硬边界</b>
     * ——越过一像素就是盖住歌词，那比字号小得多更糟。快捷面板（{@code LyricStyleQuickPanel}）
     * 的可用宽度就是从这里往前推的，所以两边共用这一个函数而不是各写一遍 {@code .48}。</p>
     */
    public static double lyricColumnLeft(double posX, double width) {
        return posX + width * .48;
    }

    static Viewport createViewport(double posX, double posY, double width, double height, double borderThickness) {
        double horizontalInset = Math.max(8.0, width * .04);
        double verticalInset = Math.max(borderThickness, Math.min(10.0, height * .018));
        double left = lyricColumnLeft(posX, width);
        double right = Math.max(left, posX + width - horizontalInset);
        double top = posY + verticalInset;
        double bottom = Math.max(top, posY + height - verticalInset);
        return new Viewport(left, top, right - left, bottom - top);
    }

    /**
     * 快捷面板的分列结果。
     *
     * <p>面板的行数是随配置变的（背景样式、配色模式、当前歌词样式都会增删行），一列排下来的高度
     * 可以轻易超过整个视口。这时候正确的做法是<b>分列</b>（面板变宽、变矮），而不是把整块面板
     * 连同文字一起等比缩小——缩小到能塞进去的时候，字已经没法看了。</p>
     */
    static final class ColumnPacking {
        /** 实际用到的列数，至少为 1。 */
        final int columns;
        /** 每行落在第几列，与入参行表等长。 */
        final int[] columnOf;
        /** 最高的那一列的高度（不含标题与面板内边距）。 */
        final double maxColumnHeight;
        /** 整块面板的宽度。 */
        final double width;

        private ColumnPacking(int columns, int[] columnOf, double maxColumnHeight, double width) {
            this.columns = columns;
            this.columnOf = columnOf;
            this.maxColumnHeight = maxColumnHeight;
            this.width = width;
        }
    }

    /**
     * 给快捷面板选列数并分列。
     *
     * <p>判据是<b>缩放需求量</b>：先试一列，装不下就试两列、三列……取第一个「一点都不用缩小」的
     * 列数就停（再加列只会让面板更宽，反而更容易碰到歌词列）；连允许的最大列数都要缩小的时候，
     * 取缩放需求最小的那一档，让调用方尽量少缩。列数还受可用宽度限制——分出来的面板不允许越过
     * {@link #lyricColumnLeft}。</p>
     *
     * @param heights         每行的高度，顺序即显示顺序
     * @param keepWithNext    该行是否必须和下一行同列（分节小标题不能落在列尾）
     * @param availableWidth  面板左边缘到歌词列之间可用的宽度
     * @param availableHeight 面板顶部到视口底部可用的高度
     * @param chromeHeight    标题与上下内边距占掉的固定高度
     * @param columnWidth     单列宽度
     * @param columnGap       列间距
     * @param maxColumns      允许的最大列数
     */
    static ColumnPacking packQuickPanel(double[] heights, boolean[] keepWithNext,
                                        double availableWidth, double availableHeight, double chromeHeight,
                                        double columnWidth, double columnGap, int maxColumns) {
        int limit = Math.max(1, Math.min(Math.max(1, maxColumns),
                maxColumnsThatFit(availableWidth, columnWidth, columnGap)));

        int chosen = 1;
        int[] chosenColumnOf = packRows(heights, keepWithNext, 1);
        double chosenTallest = tallestColumn(heights, chosenColumnOf);
        double chosenScore = Double.MAX_VALUE;

        for (int columns = 1; columns <= limit; columns++) {
            int[] columnOf = packRows(heights, keepWithNext, columns);
            if (columnOf == null) continue;   // 这一档在合法位置断不开，跳过
            double tallest = tallestColumn(heights, columnOf);
            double width = panelWidthFor(columns, columnWidth, columnGap);
            double score = Math.max((chromeHeight + tallest) / Math.max(1.0, availableHeight),
                    width / Math.max(1.0, availableWidth));
            if (score < chosenScore - 1.0e-6) {
                chosen = columns;
                chosenColumnOf = columnOf;
                chosenTallest = tallest;
                chosenScore = score;
            }
            if (score <= 1.0 + 1.0e-9) break;
        }
        return new ColumnPacking(chosen, chosenColumnOf, chosenTallest,
                panelWidthFor(chosen, columnWidth, columnGap));
    }

    /**
     * 把行按顺序分成 {@code columns} 列，返回每行的列号。
     *
     * <p>分割点取「让最高的那一列尽量矮」的那一组（线性划分的最优解）：面板要不要缩小、缩多少
     * 完全由最高的那一列决定，所以按行数平均、或按高度做单趟贪心都不够——贪心在最后一格上
     * 会明显失衡，白白多缩几个百分点。行数在几十以内，二维 DP 的开销可以忽略。</p>
     *
     * @return 每行的列号；给定列数下找不到合法分割（列数比可用断点还多）时返回 {@code null}
     */
    static int[] packRows(double[] heights, boolean[] keepWithNext, int columns) {
        int count = heights.length;
        int[] columnOf = new int[count];
        if (count == 0) return columnOf;
        int limit = Math.max(1, Math.min(columns, count));

        double[] prefix = new double[count + 1];
        for (int i = 0; i < count; i++) {
            prefix[i + 1] = prefix[i] + heights[i];
        }

        // best[c][i]：前 i 行分成 c 列的「最高列高」最优值；from[c][i] 记录最后一列的起点。
        double[][] best = new double[limit + 1][count + 1];
        int[][] from = new int[limit + 1][count + 1];
        for (int c = 0; c <= limit; c++) {
            Arrays.fill(best[c], Double.MAX_VALUE);
        }
        for (int i = 1; i <= count; i++) {
            best[1][i] = prefix[i];
        }
        for (int c = 2; c <= limit; c++) {
            for (int i = c; i <= count; i++) {
                for (int j = c - 1; j < i; j++) {
                    // 断点落在第 j-1 行之后；这一行要求与下一行同列时不能断。
                    if (keepWithNext != null && j > 0 && keepWithNext[j - 1]) continue;
                    if (best[c - 1][j] == Double.MAX_VALUE) continue;
                    double candidate = Math.max(best[c - 1][j], prefix[i] - prefix[j]);
                    if (candidate < best[c][i]) {
                        best[c][i] = candidate;
                        from[c][i] = j;
                    }
                }
            }
        }
        if (limit > 1 && best[limit][count] == Double.MAX_VALUE) return null;

        int end = count;
        for (int c = limit; c >= 1; c--) {
            int start = c == 1 ? 0 : from[c][end];
            for (int i = start; i < end; i++) {
                columnOf[i] = c - 1;
            }
            end = start;
        }
        return columnOf;
    }

    private static double tallestColumn(double[] heights, int[] columnOf) {
        double[] totals = new double[heights.length];
        double tallest = 0;
        for (int i = 0; i < heights.length; i++) {
            totals[columnOf[i]] += heights[i];
            tallest = Math.max(tallest, totals[columnOf[i]]);
        }
        return tallest;
    }

    private static double panelWidthFor(int columns, double columnWidth, double columnGap) {
        return columns * columnWidth + Math.max(0, columns - 1) * columnGap;
    }

    /** 可用宽度最多放得下几列；宽度未知（{@link Double#MAX_VALUE}）时按调用方的上限截断。 */
    private static int maxColumnsThatFit(double availableWidth, double columnWidth, double columnGap) {
        if (!(availableWidth > 0) || !(columnWidth > 0)) return 1;
        double stride = columnWidth + Math.max(0, columnGap);
        double columns = Math.floor((availableWidth + Math.max(0, columnGap)) / stride);
        if (!(columns >= 1)) return 1;
        return (int) Math.min(64, columns);
    }

    /**
     * 逐字高亮把当前字词以某个锚点放大（{@code currentWordScale}），布局坐标保持不变。
     *
     * <p>默认锚点是字词中心，但行首字词的左边缘正好压在歌词视口的裁剪边界上，按中心放大
     * 会有一半的放大量落到视口外被裁掉，表现为"首字被切掉一块"。这里把锚点约束到能让
     * 放大后的矩形完整留在 {@code [clipLeft, clipRight]} 内的区间：能用中心就用中心，
     * 贴边的字词则把锚点收拢到边界，只向视口内侧生长。</p>
     *
     * @param left     字词矩形在布局坐标系中的左边缘
     * @param width    字词矩形宽度（未放大）
     * @param scale    本帧的放大倍率，{@code <= 1} 时直接返回中心
     * @return 应当传给 translate/scale/translate 的 X 锚点
     */
    public static double clampScaleAnchorX(double left, double width, double scale, double clipLeft, double clipRight) {
        double center = left + width * .5;
        if (scale <= 1.0001 || width <= 0) return center;

        double right = left + width;
        // 放大后左边缘 = scale * left + anchor * (1 - scale)，要求 >= clipLeft；右边缘同理。
        double maxAnchor = (scale * left - clipLeft) / (scale - 1.0);
        double minAnchor = (scale * right - clipRight) / (scale - 1.0);

        // 放大后比视口还宽，无解：保持原来的中心放大行为。
        if (minAnchor > maxAnchor) return center;

        return Math.max(minAnchor, Math.min(maxAnchor, center));
    }

    /**
     * 全屏页封面的最大边长。
     *
     * <p>比"能放多大就放多大"小一号，是给封面下方那五行曲目信息腾的：字号是逻辑像素、
     * 不随面板缩放，封面每大一点都是从信息块和传送带那边借的。这两个系数配合
     * {@code MusicLyricsPanel} 里的封面中心（面板高度的 30%）一起定，改一个就要重算另一个。</p>
     */
    static double coverSizeMax(double width, double height) {
        return Math.min(height * .40, width * .34);
    }

    static String formatDuration(float totalMillis) {
        float totalSeconds = totalMillis / 1000;
        float hours = totalSeconds / 3600;
        float minutes = (totalSeconds % 3600) / 60;
        float seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        if ((int) hours > 0) {
            builder.append(String.format("%02d:", (int) hours));
        }
        builder.append(String.format("%02d:", (int) minutes));
        builder.append(String.format("%02d", (int) seconds));
        return builder.toString();
    }

    static final class Viewport {
        final double left;
        final double top;
        final double width;
        final double height;

        private Viewport(double left, double top, double width, double height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }
    }
}