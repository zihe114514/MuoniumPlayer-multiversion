package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词搜索弹窗预览块的纯时间轴判据（本项目 {@code LyricPreviewPanel} 的离线对照）。
 *
 * <p>预览块看着只是一句词，其实有四条规则叠在一起：哪句是「当前」、两句之间有空隙时留谁在屏上、
 * 第二行是翻译还是下一句、以及这一行的 key（决定要不要重播入场动画）。这四条全是纯函数，
 * 不需要真机也不需要联网，所以钉在这里。渲染与动画时钟那半部分照例留给真机。</p>
 *
 * <p>下面的毫秒都是真毫秒：{@link LyricLine#timestamp} 与 {@link LyricLine#duration} 的单位本来就是
 * 毫秒，别按秒写。</p>
 */
class LyricSearchPreviewStaticTest {

    private static LyricLine line(long startMillis, long durationMillis, String text) {
        LyricLine line = new LyricLine(startMillis, text);
        line.duration = durationMillis;
        return line;
    }

    private static LyricLine translated(long startMillis, long durationMillis, String text,
                                        String translation) {
        LyricLine line = line(startMillis, durationMillis, text);
        line.translationText = translation;
        return line;
    }

    // ── 当前行 ──────────────────────────────────────────────────────────────

    @Test
    void activeLineCoversItsWholeDurationInclusive() {
        List<LyricLine> lines = Collections.singletonList(line(1000L, 4000L, "第一句"));
        // 起点、终点都算「还在唱」——folia 的判据是 timestamp <= t <= timestamp + duration。
        assertEquals(0, LyricSearchOverlay.latestActiveIndex(lines, 1000L));
        assertEquals(0, LyricSearchOverlay.latestActiveIndex(lines, 2500L));
        assertEquals(0, LyricSearchOverlay.latestActiveIndex(lines, 5000L));
        assertEquals(-1, LyricSearchOverlay.latestActiveIndex(lines, 5001L));
        assertEquals(-1, LyricSearchOverlay.latestActiveIndex(lines, 999L));
    }

    @Test
    void zeroDurationLineOnlyMatchesItsOwnInstant() {
        // 时长缺失（有些源的行没有结束时间）按 0 处理：只有正好落在那一毫秒才算活跃。
        List<LyricLine> lines = Collections.singletonList(line(3000L, 0L, "点到为止"));
        assertEquals(0, LyricSearchOverlay.latestActiveIndex(lines, 3000L));
        assertEquals(-1, LyricSearchOverlay.latestActiveIndex(lines, 3001L));
    }

    @Test
    void negativeDurationNeverWidensTheWindow() {
        // 解析器理论上不会给负时长，但一条脏数据不该把这一行变成「永远活跃」。
        List<LyricLine> lines = Collections.singletonList(line(1000L, -5000L, "脏数据"));
        assertEquals(0, LyricSearchOverlay.latestActiveIndex(lines, 1000L));
        assertEquals(-1, LyricSearchOverlay.latestActiveIndex(lines, 1001L));
    }

    @Test
    void overlappingLinesPickTheLatestStartedOne() {
        List<LyricLine> lines = Arrays.asList(
                line(0L, 10_000L, "长音"),
                line(4000L, 2000L, "后开始的"));
        // 倒着找：重叠时后开始的那条赢，和 folia 的 findLatestActiveLineIndex 一致。
        assertEquals(1, LyricSearchOverlay.latestActiveIndex(lines, 5000L));
        assertEquals(0, LyricSearchOverlay.latestActiveIndex(lines, 3000L));
    }

    // ── 两句怎么配 ──────────────────────────────────────────────────────────

    @Test
    void displayPairsTheActiveLineWithTheFollowingOne() {
        List<LyricLine> lines = Arrays.asList(
                line(0L, 2000L, "一"),
                line(2000L, 2000L, "二"),
                line(4000L, 2000L, "三"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 1000L);
        assertSame(lines.get(0), pair[0]);
        assertSame(lines.get(1), pair[1]);
    }

    @Test
    void lastLineHasNoNext() {
        List<LyricLine> lines = Arrays.asList(line(0L, 2000L, "一"), line(2000L, 2000L, "二"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 2500L);
        assertSame(lines.get(1), pair[0]);
        assertNull(pair[1], "最后一句后面没有下一句");
    }

    @Test
    void gapKeepsTheLineThatJustFinished() {
        // 两句之间有空隙（前奏、间奏、纯留白）：屏幕不能闪白，刚唱完那句得留着，
        // 下一行取即将开始的那一条。这是 folia resolveDisplay 的第二级找法。
        List<LyricLine> lines = Arrays.asList(
                line(0L, 1000L, "唱完了"),
                line(60_000L, 2000L, "还没到"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 30_000L);
        assertSame(lines.get(0), pair[0]);
        assertSame(lines.get(1), pair[1]);
    }

    @Test
    void beforeTheSongStartsNothingIsCurrentButTheFirstLineIsNext() {
        List<LyricLine> lines = Arrays.asList(
                line(5000L, 2000L, "第一句"),
                line(8000L, 2000L, "第二句"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 0L);
        assertNull(pair[0], "整首还没开唱，当前行为空");
        assertSame(lines.get(0), pair[1]);
    }

    @Test
    void afterTheLastLineEndsItStaysOnScreen() {
        List<LyricLine> lines = Arrays.asList(
                line(0L, 1000L, "第一句"),
                line(2000L, 1000L, "唱到结尾"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 999_999L);
        assertSame(lines.get(1), pair[0], "唱完了就把最后一句留在屏上");
        assertNull(pair[1]);
    }

    // ── 第二行 ──────────────────────────────────────────────────────────────

    @Test
    void translationWinsOverTheNextLine() {
        List<LyricLine> lines = Arrays.asList(
                translated(0L, 2000L, "青花瓷", "Blue and White Porcelain"),
                line(2000L, 2000L, "下一句"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 1000L);
        assertEquals("Blue and White Porcelain",
                LyricSearchOverlay.subLineText(lines, pair[0], pair[1]));
        assertTrue(LyricSearchOverlay.subIsTranslation(lines, pair[0]), "翻译行要走 emerald 字色");
    }

    @Test
    void withoutTranslationTheSecondLineIsTheNextLyric() {
        List<LyricLine> lines = Arrays.asList(line(0L, 2000L, "一"), line(2000L, 2000L, "二"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 1000L);
        assertEquals("二", LyricSearchOverlay.subLineText(lines, pair[0], pair[1]));
        assertFalse(LyricSearchOverlay.subIsTranslation(lines, pair[0]), "预告下一句是 zinc 字色");
    }

    @Test
    void blankTranslationCountsAsNoTranslation() {
        // 上游给过翻译字段但内容是空白（酷狗与 AMLL 都有这种行），不能显示成一行空气。
        List<LyricLine> lines = Arrays.asList(
                translated(0L, 2000L, "一", "   "),
                line(2000L, 2000L, "二"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 1000L);
        assertEquals("二", LyricSearchOverlay.subLineText(lines, pair[0], pair[1]));
        assertFalse(LyricSearchOverlay.subIsTranslation(lines, pair[0]));
    }

    @Test
    void lastLineWithoutNextShowsNothingBelowIt() {
        List<LyricLine> lines = Collections.singletonList(line(0L, 2000L, "孤句"));
        LyricLine[] pair = LyricSearchOverlay.resolveDisplay(lines, 1000L);
        assertEquals("", LyricSearchOverlay.subLineText(lines, pair[0], pair[1]));
    }

    @Test
    void beforeTheSongStartsTheSecondLinePreviewsFromTheTop() {
        // 当前行为空时按第一行套同一条规则：有翻译显示第一行翻译，否则显示第二句。
        List<LyricLine> withTranslation = Arrays.asList(
                translated(5000L, 2000L, "一", "One"),
                line(8000L, 2000L, "二"));
        assertEquals("One", LyricSearchOverlay.subLineText(withTranslation, null,
                withTranslation.get(0)));

        List<LyricLine> plain = Arrays.asList(line(5000L, 2000L, "一"), line(8000L, 2000L, "二"));
        assertEquals("二", LyricSearchOverlay.subLineText(plain, null, plain.get(0)));
        assertTrue(LyricSearchOverlay.subIsTranslation(withTranslation, null));
        assertFalse(LyricSearchOverlay.subIsTranslation(plain, null));
    }

    @Test
    void subLineTextToleratesEmptyAndSingleLineLists() {
        assertEquals("", LyricSearchOverlay.subLineText(Collections.emptyList(), null, null));
        assertFalse(LyricSearchOverlay.subIsTranslation(Collections.emptyList(), null));
        List<LyricLine> only = Collections.singletonList(line(0L, 0L, "只有一句"));
        assertEquals("", LyricSearchOverlay.subLineText(only, null, only.get(0)),
                "只有一句且没翻译时不给第二行留半句话");
    }
}
