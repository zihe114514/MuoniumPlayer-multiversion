package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Split lines（两句挤一行拆分）的离线探针。
 */
class SplitLinesTest {

    private static LyricLine line(String text, long timestamp, long duration) {
        LyricLine line = new LyricLine(timestamp, text);
        line.duration = duration;
        return line;
    }

    @Test
    void splitsSlashSeparatedLineIntoTwo() {
        LyricLine source = line("上半句 / 下半句", 10_000L, 4_000L);
        source.translationText = "first / second";

        java.util.List<LyricLine> out = SplitLines.split(source, true);

        assertEquals(2, out.size());
        LyricLine a = out.get(0);
        LyricLine b = out.get(1);
        assertEquals("上半句", a.lyric);
        assertEquals("下半句", b.lyric);
        assertEquals(10_000L, a.timestamp);
        // 时间轴按字符比例分摊：上半句 3 字 / 全 6 字 = 一半
        assertEquals(12_000L, b.timestamp);
        assertEquals("first", a.translationText);
        // 翻译整条跟第一半句（拆分成功时各自拿各的；这里两段都拆出来了）
        assertEquals("second", b.translationText);
    }

    @Test
    void secondHalfStartsWhenFirstEnds() {
        LyricLine source = line("AAAAAAAAAA / B", 0L, 11_000L);
        java.util.List<LyricLine> out = SplitLines.split(source, true);
        assertEquals(2, out.size());
        assertEquals(out.get(0).timestamp + out.get(0).duration, out.get(1).timestamp);
    }

    @Test
    void disabledToggleReturnsOriginalLine() {
        LyricLine source = line("上半句 / 下半句", 100L, 1000L);
        java.util.List<LyricLine> out = SplitLines.split(source, false);
        assertEquals(1, out.size());
        assertEquals(source, out.get(0));
    }

    @Test
    void lineWithoutSeparatorPassesThrough() {
        LyricLine source = line("普通一行歌词", 100L, 1000L);
        java.util.List<LyricLine> out = SplitLines.split(source, true);
        assertEquals(1, out.size());
        assertEquals(source, out.get(0));
    }

    @Test
    void fullWidthSlashAlsoSplits() {
        LyricLine source = line("前半／后半", 0L, 2_000L);
        java.util.List<LyricLine> out = SplitLines.split(source, true);
        assertEquals(2, out.size());
        assertEquals("前半", out.get(0).lyric);
        assertEquals("后半", out.get(1).lyric);
    }

    @Test
    void doubleSlashCountsAsOneSeparator() {
        LyricLine source = line("前半 // 后半", 0L, 2_000L);
        java.util.List<LyricLine> out = SplitLines.split(source, true);
        assertEquals(2, out.size());
        assertEquals("前半", out.get(0).lyric);
        assertEquals("后半", out.get(1).lyric);
    }

    @Test
    void leadingOrTrailingSeparatorDoesNotSplit() {
        LyricLine slashLeading = line("/ 开头是斜杠", 0L, 2_000L);
        assertTrue(SplitLines.split(slashLeading, true).size() == 1);

        LyricLine slashOnly = line("////", 0L, 2_000L);
        assertTrue(SplitLines.split(slashOnly, true).size() == 1);
    }

    @Test
    void wordsAreAssignedByOwnership() {
        LyricLine source = line("AAAA / BB", 0L, 2_000L);
        source.words.add(new LyricLine.Word("AAAA", 0L, 1_000L));
        source.words.add(new LyricLine.Word(" ", 1_000L, 100L));
        source.words.add(new LyricLine.Word("BB", 1_100L, 900L));

        java.util.List<LyricLine> out = SplitLines.split(source, true);
        assertEquals(2, out.size());
        // 词按「起始字符位置」归属："AAAA" 起于 0（前半句），" " 起于 4（分隔符前，
        // 仍在 [0,5) 前半句区间），"BB" 起于 7（后半句区间）。
        assertEquals(2, out.get(0).words.size());
        assertEquals("AAAA", out.get(0).words.get(0).word);
        assertEquals(1, out.get(1).words.size());
        assertEquals("BB", out.get(1).words.get(0).word);
    }

    @Test
    void sidecarWithoutSeparatorFollowsFirstHalfOnly() {
        LyricLine source = line("上半句 / 下半句", 0L, 2_000L);
        source.translationText = "整句翻译没有分隔符";

        java.util.List<LyricLine> out = SplitLines.split(source, true);
        assertEquals(2, out.size());
        assertEquals("整句翻译没有分隔符", out.get(0).translationText);
        assertNull(out.get(1).translationText);
    }

    @Test
    void separatorFinderRequiresContentOnBothSides() {
        assertEquals(-1, SplitLines.findSeparator("/ 开头"));
        assertEquals(-1, SplitLines.findSeparator("结尾 /"));
        assertEquals(-1, SplitLines.findSeparator("没有分隔符"));
        assertTrue(SplitLines.findSeparator("a / b") >= 0);
        // 全角
        assertTrue(SplitLines.findSeparator("前／后") >= 0);
        assertFalse(SplitLines.findSeparator(null) >= 0);
    }
}
