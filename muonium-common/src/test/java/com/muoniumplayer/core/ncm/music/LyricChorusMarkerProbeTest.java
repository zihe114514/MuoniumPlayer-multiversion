package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 副歌标记与强调曲线的离线探针（P2#12）。
 *
 * <p>这一层最容易被无声改坏：判错了只是"某首歌的发光位置不太对"，不报错。所以两条来源
 * （精确区间 / 文本频次）分别钉住，另外把「段号决定特效」这条<b>与 Folia 的有意差异</b>
 * 也钉成断言——随机抽特效会在重播时换样子，看起来像 bug。</p>
 */
class LyricChorusMarkerProbeTest {

    private static LyricLine line(long timestamp, String text) {
        return new LyricLine(timestamp, text);
    }

    private static LyricLine line(long timestamp, String text, long duration) {
        LyricLine line = new LyricLine(timestamp, text);
        line.duration = duration;
        return line;
    }

    // ── 文本频次兜底 ────────────────────────────────────────────────────────

    @Test
    void textHeuristicMarksTheMostRepeatedLine() {
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0L, "intro"),
                line(1000L, "副歌"),
                line(2000L, "verse"),
                line(3000L, "副歌"),
                line(4000L, "bridge"),
                line(5000L, "副歌")));

        assertEquals(3, LyricChorusMarker.markByText(lines));

        assertTrue(lines.get(1).isChorus);
        assertFalse(lines.get(2).isChorus);
        // 连续出现算同一段；中间夹了别的行就开新段，段号与特效一起推进。
        assertEquals(0, lines.get(1).chorusIndex);
        assertEquals(1, lines.get(3).chorusIndex);
        assertEquals(2, lines.get(5).chorusIndex);
        assertEquals(0, lines.get(1).chorusEffect);
        assertEquals(1, lines.get(3).chorusEffect);
        assertEquals(2, lines.get(5).chorusEffect);
    }

    @Test
    void adjacentRepeatsShareOneSegment() {
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0L, "别的"),
                line(1000L, "副歌"),
                line(2000L, "副歌"),
                line(3000L, "副歌")));

        assertEquals(3, LyricChorusMarker.markByText(lines));

        assertEquals(0, lines.get(1).chorusIndex);
        assertEquals(0, lines.get(2).chorusIndex);
        assertEquals(0, lines.get(3).chorusIndex, "连续三句是同一段副歌，不该被拆开");
    }

    @Test
    void textHeuristicDoesNothingWhenNothingRepeats() {
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0L, "第一句"),
                line(1000L, "第二句"),
                line(2000L, "第三句")));

        // 一句都没重复过就没有"高潮段"可言，宁可不标，也不随便指一行。
        assertEquals(0, LyricChorusMarker.markByText(lines));
        assertFalse(lines.get(0).isChorus);
    }

    @Test
    void singleCharacterInterjectionsDoNotBecomeAChorus() {
        // 单字语气词重复次数最多，但它不该被当成副歌（MIN_CHARS 按码点算）。
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0L, "哈"), line(1000L, "哈"), line(2000L, "哈"), line(3000L, "哈"),
                line(4000L, "只有一遍的句子")));

        assertEquals(0, LyricChorusMarker.markByText(lines));
    }

    @Test
    void interludeLinesNeverCountAsChorus() {
        // 间奏行的文本是固定重复的 "......"，把它算进频次会让人声部分永远选不出副歌。
        List<LyricLine> lines = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            LyricLine breakLine = line(index * 1000L, LyricTimelineSupport.INTERLUDE_TEXT, 500L);
            breakLine.isBreakLine = true;
            lines.add(breakLine);
        }
        lines.add(line(9000L, "真正的副歌"));
        lines.add(line(10000L, "真正的副歌"));

        assertEquals(2, LyricChorusMarker.markByText(lines));
        assertTrue(lines.get(5).isChorus);
    }

    @Test
    void effectsAreStableAcrossRuns() {
        // 与 Folia 的有意差异：那边每次都 Math.random() 抽特效，同一首歌重播会换样子。
        List<LyricLine> first = new ArrayList<>(Arrays.asList(
                line(0L, "副歌"), line(1000L, "别的"), line(2000L, "副歌")));
        List<LyricLine> second = new ArrayList<>(Arrays.asList(
                line(0L, "副歌"), line(1000L, "别的"), line(2000L, "副歌")));

        LyricChorusMarker.markByText(first);
        LyricChorusMarker.markByText(second);

        for (int index = 0; index < first.size(); index++) {
            assertEquals(first.get(index).isChorus, second.get(index).isChorus);
            assertEquals(first.get(index).chorusEffect, second.get(index).chorusEffect,
                    "第 " + index + " 行的特效必须每次一样");
        }
    }

    // ── 精确区间 ────────────────────────────────────────────────────────────

    @Test
    void exactRangesMarkEveryOverlappingLine() {
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0L, "a", 1000L),
                line(5000L, "b", 1000L),
                line(12000L, "c", 1000L)));
        List<long[]> ranges = Arrays.asList(new long[]{4500L, 7000L}, new long[]{11500L, 14000L});

        assertEquals(2, LyricChorusMarker.markByRanges(lines, ranges));

        assertFalse(lines.get(0).isChorus);
        assertTrue(lines.get(1).isChorus);
        assertEquals(0, lines.get(1).chorusIndex);
        assertTrue(lines.get(2).isChorus);
        assertEquals(1, lines.get(2).chorusIndex);
    }

    @Test
    void linesOutsideEveryRangeLeaveTheSegmentCounterReset() {
        // 区间之间夹的行不该被算进任何一段，也不该让后一段的段号错位。
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0L, "a", 100L),
                line(500L, "b", 100L),
                line(5000L, "c", 100L),
                line(9000L, "d", 100L)));
        List<long[]> ranges = Arrays.asList(new long[]{0L, 200L}, new long[]{8800L, 9200L});

        assertEquals(2, LyricChorusMarker.markByRanges(lines, ranges));

        assertEquals(0, lines.get(0).chorusIndex);
        assertFalse(lines.get(1).isChorus);
        assertFalse(lines.get(2).isChorus);
        assertEquals(1, lines.get(3).chorusIndex);
    }

    @Test
    void rangesWithNoOverlapReportZeroSoTheCallerCanFallBackToText() {
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(line(0L, "a", 100L)));
        assertEquals(0, LyricChorusMarker.markByRanges(lines, Arrays.asList(new long[]{90000L, 95000L})));
        assertEquals(0, LyricChorusMarker.markByRanges(lines, new ArrayList<long[]>()));
        assertEquals(0, LyricChorusMarker.markByRanges(null, Arrays.asList(new long[]{0L, 100L})));
    }

    @Test
    void chorusRangesAreReadFromBothFieldNamingSchemes() {
        JsonObject response = new JsonParser().parse("{\"chorus\":["
                + "{\"startTime\":1000,\"endTime\":2000},"
                + "{\"start\":3000,\"end\":4000}]}").getAsJsonObject();

        List<long[]> ranges = LyricChorusMarker.parseRanges(response);

        assertEquals(2, ranges.size());
        assertArrayEquals(new long[]{1000L, 2000L}, ranges.get(0));
        assertArrayEquals(new long[]{3000L, 4000L}, ranges.get(1));
    }

    @Test
    void chorusRangesAreAlsoReadFromTheDataWrapperAndBadRangesAreDropped() {
        JsonObject wrapped = new JsonParser()
                .parse("{\"data\":{\"chorus\":[{\"start\":1000,\"end\":2000}]}}").getAsJsonObject();
        assertEquals(1, LyricChorusMarker.parseRanges(wrapped).size());

        // end <= start 是坏区间，必须丢掉而不是产出一个零长段。
        JsonObject inverted = new JsonParser()
                .parse("{\"chorus\":[{\"start\":500,\"end\":400}]}").getAsJsonObject();
        assertTrue(LyricChorusMarker.parseRanges(inverted).isEmpty());

        assertTrue(LyricChorusMarker.parseRanges(null).isEmpty());
        assertTrue(LyricChorusMarker.parseRanges(new JsonObject()).isEmpty());
    }

    // ── 强调曲线 ────────────────────────────────────────────────────────────

    @Test
    void nonChorusLinesHaveNoEmphasisAtAll() {
        LyricLine plain = line(1000L, "x", 2000L);
        assertEquals(0.0, LyricChorusMarker.emphasis(plain, 1500L));
        assertEquals(0.0, LyricChorusMarker.emphasis(null, 1500L));
    }

    @Test
    void emphasisRisesAndFallsInsideTheLine() {
        LyricLine line = line(1000L, "x", 2000L);
        line.isChorus = true;
        line.chorusEffect = 0;

        // 首尾各留斜坡，避免副歌边缘那一句被硬切。
        assertEquals(0.0, LyricChorusMarker.emphasis(line, 1000L));
        assertTrue(LyricChorusMarker.emphasis(line, 2980L) < 0.2,
                "行尾必须淡出: " + LyricChorusMarker.emphasis(line, 2980L));
        assertTrue(LyricChorusMarker.emphasis(line, 2000L) > 0.7,
                "中段应有明显强调");
        // 行外不该冒出强调。
        assertEquals(0.0, LyricChorusMarker.emphasis(line, 5000L));
    }

    @Test
    void everyEffectStaysInRangeAndIsAlwaysPresent() {
        // 核心约定：换段号只换观感、不换有无。任何一档在中段都得有实际强调。
        for (int effect = 0; effect < LyricChorusMarker.EFFECT_COUNT; effect++) {
            LyricLine line = line(0L, "x", 1000L);
            line.isChorus = true;
            line.chorusEffect = effect;

            double weakest = Double.MAX_VALUE;
            for (double position = 100.0; position < 900.0; position += 10.0) {
                double value = LyricChorusMarker.emphasis(line, position);
                assertTrue(value >= 0.0 && value <= 1.0,
                        "第 " + effect + " 档的强调量必须落在 0..1: " + value);
                weakest = Math.min(weakest, value);
            }
            assertTrue(weakest > 0.15,
                    "第 " + effect + " 档中段强调过弱（" + weakest + "），看起来会像特效失效");
        }
    }
}
