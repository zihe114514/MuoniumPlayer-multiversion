package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字形时间轴的离线探针（P2#10）。
 *
 * <p>这一层是 Folia {@code graphemeTiming.ts} 的 Java 对应物：字形切分走
 * {@link java.text.BreakIterator}（UAX #29），而不是手写「码点 + 组合记号」。断言刻意覆盖
 * emoji ZWJ 序列与代理对，因为手写实现恰恰会在这两类上出错，而它们真的会出现在歌词里。</p>
 */
class LyricGraphemeTimelineProbeTest {

    /** U+1F469 ZWJ U+1F469 ZWJ U+1F467：一个 emoji ZWJ 序列（家庭）。 */
    private static final String ZWJ_FAMILY =
            "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67";

    // ── 切分 ────────────────────────────────────────────────────────────────

    @Test
    void graphemesSplitByUnicodeGraphemeCluster() {
        assertEquals(Arrays.asList("a", "b", "c"), LyricGraphemeTimeline.graphemes("abc"));
        assertEquals(Arrays.asList("你", "好"), LyricGraphemeTimeline.graphemes("你好"));
        assertEquals(0, LyricGraphemeTimeline.graphemes("").size());
        assertEquals(0, LyricGraphemeTimeline.graphemes(null).size());
    }

    @Test
    void combiningMarksAndZwjEmojiCountAsOneGrapheme() {
        // 组合记号跟着基字走。这一条与运行时 JDK 无关，所有目标版本都必须是 1。
        assertEquals(1, LyricGraphemeTimeline.graphemes("e\u0301").size());
        // 代理对：一个码点、两个 char。同样与 JDK 无关。
        assertEquals(2, LyricGraphemeTimeline.graphemes("𝄞a").size());
        assertEquals(2, LyricGraphemeTimeline.count("𝄞a"));

        // emoji ZWJ 序列（家庭）：
        // 这里刻意用转义而不是直接嵌字——零宽连接符 U+200D 是不可见字符，任何一次「顺手清理
        // 不可见字符」的编辑都会把它删掉，字符串就退化成三个独立 emoji，断言随之莫名失败。
        //
        // 合成的字形数取决于**运行时 JDK 的 Unicode 数据版本**，不是本项目能控制的：
        //   · JDK 21 / 25（本仓库的 1.21.1 / 1.21.11 / 26.2）→ 1 个字形（GB11 规则）；
        //   · JDK 17（本仓库的 1.20.1）→ 5 个（每个 emoji 与每个零宽连接符各算一个）。
        // 所以这里断言的是两个<b>跨版本都成立</b>的契约，而不是写死 1：
        // ① 不退化到逐 UTF-16 单元切（那会切出 8 个）；② 落在 [1, 码点数] 区间内。
        // 这不是正确性问题：1.20.1 上 emoji 家庭只是被拆成几个字形，中文/日文/韩文不受影响。
        int clusters = LyricGraphemeTimeline.graphemes(ZWJ_FAMILY).size();
        int codePoints = ZWJ_FAMILY.codePointCount(0, ZWJ_FAMILY.length());
        assertTrue(clusters < ZWJ_FAMILY.length(),
                "不该退化成逐 UTF-16 单元切，得到 " + clusters + " 个字形");
        assertTrue(clusters >= 1 && clusters <= codePoints,
                "字形数必须落在 [1, 码点数=" + codePoints + "]，得到 " + clusters);
    }

    // ── 时间摊分 ────────────────────────────────────────────────────────────

    @Test
    void lineWithoutWordsSpreadsTheLineDurationEvenly() {
        LyricLine line = new LyricLine(1000L, "abc");
        line.duration = 900L;

        List<LyricGraphemeTimeline.Glyph> glyphs = LyricGraphemeTimeline.of(line);

        assertEquals(3, glyphs.size());
        assertEquals(1000L, glyphs.get(0).timestamp);
        assertEquals(1300L, glyphs.get(1).timestamp);
        assertEquals(1600L, glyphs.get(2).timestamp);
        // 摊出来的字形不属于任何一个音节。
        assertEquals(-1, glyphs.get(0).wordIndex);
        // 最后一个字形唱到行尾。
        assertEquals(1900L, glyphs.get(2).endTimestamp());
    }

    @Test
    void wordTimingsOverrideTheEvenSpread() {
        LyricLine line = new LyricLine(0L, "你好");
        line.duration = 1000L;
        line.words.add(new LyricLine.Word("你", 0L, 200L));
        line.words.add(new LyricLine.Word("好", 500L, 500L));

        List<LyricGraphemeTimeline.Glyph> glyphs = LyricGraphemeTimeline.of(line);

        assertEquals(2, glyphs.size());
        assertEquals(0, glyphs.get(0).timestamp);
        assertEquals(0, glyphs.get(0).wordIndex);
        assertEquals(500L, glyphs.get(1).timestamp);
        assertEquals(1, glyphs.get(1).wordIndex);
    }

    @Test
    void glyphRangeOfLocatesTheWordInsideTheLine() {
        LyricLine line = new LyricLine(0L, "你好世");
        line.duration = 3000L;
        LyricLine.Word first = new LyricLine.Word("你好", 0L, 1000L);
        LyricLine.Word second = new LyricLine.Word("世", 2000L, 1000L);
        line.words.add(first);
        line.words.add(second);

        int[] range = LyricGraphemeTimeline.glyphRangeOf(line, first);

        assertEquals(0, range[0]);
        assertEquals(2, range[1]);
    }

    @Test
    void charStartMillisFlattensGraphemeTimeBackToUtf16Units() {
        LyricLine.Word word = new LyricLine.Word("你好", 1000L, 1000L);
        long[] starts = LyricGraphemeTimeline.charStartMillis(null, word);

        assertEquals(2, starts.length);
        assertEquals(1000L, starts[0]);
        // 音节内按字形均摊（Folia 在没有更深一层音节表时就是这个行为）。
        assertEquals(1500L, starts[1]);
    }

    @Test
    void surrogatePairSharesOneTimestampAcrossItsTwoChars() {
        LyricLine.Word word = new LyricLine.Word("𝄞a", 2000L, 800L);
        long[] result = LyricGraphemeTimeline.charStartMillis(null, word);

        assertEquals(3, result.length, "两个字形、三个 char");
        assertEquals(2000L, result[0]);
        // 渲染层按 char 逐位取时刻，代理对的两个 char 必须拿到同一个值。
        assertEquals(2000L, result[1]);
        assertEquals(2400L, result[2]);
    }

    // ── 逐字提升量 ──────────────────────────────────────────────────────────

    @Test
    void fillCharLiftsGiveEachCharItsOwnGlyphStart() {
        LyricLine line = new LyricLine(1000L, "你好世");
        line.duration = 2000L;
        line.words.add(new LyricLine.Word("你好", 1000L, 1000L));
        line.words.add(new LyricLine.Word("世", 2000L, 1000L));

        double[] first = new double[2];
        Arrays.fill(first, -1.0);
        // lift 直接返回起始毫秒，便于断言"每个字拿到的是自己字形的时刻"。
        LyricGraphemeTimeline.fillCharLifts(line, 0, first, start -> start, -1.0);

        assertEquals(1000.0, first[0]);
        assertEquals(1500.0, first[1], "同一个音节内按字形均摊，纹波才细到一个字");

        double[] second = new double[1];
        Arrays.fill(second, -1.0);
        LyricGraphemeTimeline.fillCharLifts(line, 1, second, start -> start, -1.0);
        assertEquals(2000.0, second[0]);
    }

    @Test
    void fillCharLiftsUsesTheFallbackWhenThereIsNoGlyphData() {
        double[] out = new double[2];
        Arrays.fill(out, -1.0);

        LyricGraphemeTimeline.fillCharLifts(null, 0, out, start -> start, 7.0);

        assertEquals(7.0, out[0]);
        assertEquals(7.0, out[1]);
    }

    @Test
    void fillCharLiftsFillsEverySlotWhenTheLineHasNoGlyphs() {
        // 空行的字形表是空的：整个数组必须落到兜底值，不能停在上一帧的旧值上。
        LyricLine empty = new LyricLine(0L, "");

        double[] out = new double[4];
        Arrays.fill(out, -1.0);
        LyricGraphemeTimeline.fillCharLifts(empty, 0, out, start -> 42.0, 3.0);

        for (int index = 0; index < out.length; index++) {
            assertEquals(3.0, out[index], "第 " + index + " 格没被填过");
        }
    }

    @Test
    void fillCharLiftsStretchesTheLastValueOverUncoveredTailChars() {
        LyricLine line = new LyricLine(0L, "ab");
        line.duration = 1000L;

        // 目标数组比字形数长（理论上不该出现）：多出来的格子沿用最后一个值。
        double[] out = new double[4];
        Arrays.fill(out, -1.0);
        LyricGraphemeTimeline.fillCharLifts(line, -1, out, start -> 9.0, -1.0);

        assertEquals(9.0, out[0]);
        assertEquals(9.0, out[3], "尾部不能留在旧值上");
    }

    // ── 缓存 ────────────────────────────────────────────────────────────────

    @Test
    void glyphTimelineIsCachedAndInvalidatedByMarkDirty() {
        // 渲染层每帧都会调 of()，不缓存就等于每帧重建一次 BreakIterator。
        LyricLine line = new LyricLine(0L, "ab");
        List<LyricGraphemeTimeline.Glyph> first = LyricGraphemeTimeline.of(line);

        assertSame(first, LyricGraphemeTimeline.of(line));

        // 词表被解析层改写时必须一起失效，否则会读到过期时间轴。
        line.markDirty();
        assertNotSame(first, LyricGraphemeTimeline.of(line));
    }
}
