package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.lyrics3d.SonnetTypography.Glyph;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Kind;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Segment;
import com.muoniumplayer.core.screens.ncm.LyricGraphemeTimeline;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonnetProgramTest {
    @Test
    void realGraphemeStringsAndNonuniformSyllableTimingSurviveSegmentation() {
        LyricLine line = line(1000, 2000, "  A\u0301lpha, 世界 🎵!");
        line.words.add(new LyricLine.Word("A\u0301lpha", 1100, 500));
        line.words.add(new LyricLine.Word("世界", 1900, 800));
        line.words.add(new LyricLine.Word("🎵", 2850, 150));
        List<LyricGraphemeTimeline.Glyph> timeline = LyricGraphemeTimeline.of(line);

        List<Segment> segments = SonnetProgram.segments(line, 9000);

        assertLossless(line, segments);
        assertTimeline(timeline, glyphs(segments));
        Glyph accent = glyphs(segments).stream().filter(glyph -> glyph.text.equals("A\u0301")).findFirst().orElseThrow();
        assertEquals(1100.0, accent.startMs);
        assertEquals(1200.0, accent.endMs);
        Glyph chinese = glyphs(segments).stream().filter(glyph -> glyph.text.equals("世")).findFirst().orElseThrow();
        assertEquals(1900.0, chinese.startMs);
        assertEquals(2300.0, chinese.endMs);
        assertTrue(glyphs(segments).stream().anyMatch(glyph -> glyph.text.equals("🎵") && glyph.startMs == 2850.0));
    }

    @Test
    void exactMultiGraphemeParserWordsKeepTheirBoundariesIncludingLongWords() {
        LyricLine line = line(1000, 4500, "中华人民共和国欢迎你");
        line.words.add(new LyricLine.Word("中华人民共和国", 1100, 2100));
        line.words.add(new LyricLine.Word("欢迎", 3500, 900));
        line.words.add(new LyricLine.Word("你", 4700, 300));

        List<Segment> segments = SonnetProgram.segments(line, 20000);

        assertEquals(List.of("中华人民共和国", "欢迎", "你"), wordTexts(segments));
        assertLossless(line, segments);
        assertTimeline(LyricGraphemeTimeline.of(line), glyphs(segments));
    }

    @Test
    void singleGraphemeTimingSyllablesDoNotReplaceSemanticFallbackBoundaries() {
        String text = "在遥远的天空落下柔软的光照亮归途";
        List<String> syllables = LyricGraphemeTimeline.graphemes(text);
        LyricLine timed = line(1000, syllables.size() * 200L, text);
        for (int index = 0; index < syllables.size(); index++) {
            timed.words.add(new LyricLine.Word(syllables.get(index), 1000 + index * 200L, 90 + index));
        }
        LyricLine plain = line(timed.timestamp, timed.duration, text);

        List<Segment> segments = SonnetProgram.segments(timed, 30000);

        assertEquals(wordTexts(SonnetProgram.segments(plain, 30000)), wordTexts(segments));
        assertTrue(wordTexts(segments).size() > 1);
        assertTrue(wordTexts(segments).size() < syllables.size());
        assertTrue(wordTexts(segments).stream().anyMatch(part -> LyricGraphemeTimeline.count(part) > 1));
        assertLossless(timed, segments);
        assertTimeline(LyricGraphemeTimeline.of(timed), glyphs(segments));
    }

    @Test
    void unresolvedLongChineseRunsUseBoundedVisualChunksWithNoNineSegmentCap() {
        LyricLine line = line(1000, 5000, "在遥远的天空落下柔软的光照亮归途".repeat(3));

        List<Segment> segments = SonnetProgram.segments(line, 30000);

        assertTrue(wordTexts(segments).size() > 9);
        assertTrue(wordTexts(segments).stream().allMatch(part -> LyricGraphemeTimeline.count(part) <= 4));
        assertLossless(line, segments);
        assertTimeline(LyricGraphemeTimeline.of(line), glyphs(segments));
    }

    @Test
    void oneTtmlTimingSpanForAWholeChinesePhraseDoesNotMakeOneHero() {
        String text = "在遥远的天空落下柔软的光照亮归途";
        String ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
                + "<p begin=\"1s\" end=\"5s\"><span begin=\"1s\" end=\"5s\">"
                + text + "</span></p></div></body></tt>";
        List<LyricLine> parsed = new ArrayList<>();
        LyricParser.parseTtml(ttml, parsed);
        assertEquals(1, parsed.size());
        LyricLine line = parsed.get(0);
        assertEquals(1, line.words.size());

        List<Segment> segments = SonnetProgram.segments(line, 30000);

        assertTrue(wordTexts(segments).size() > 1);
        assertTrue(wordTexts(segments).stream().allMatch(part -> LyricGraphemeTimeline.count(part) <= 4));
        assertLossless(line, segments);
        assertTimeline(LyricGraphemeTimeline.of(line), glyphs(segments));
    }

    @Test
    void authoredTtmlSourceRetainsSupplementaryAndCombiningGlyphStringsAndActualTiming() {
        String han = "\uD840\uDC00";
        String accent = "e\u0301";
        String music = "\uD83C\uDFB5";
        String ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
                + "<p begin=\"1s\" end=\"6s\">"
                + "<span begin=\"1.2s\" end=\"1.9s\">" + han + "光</span>"
                + "<span begin=\"2.4s\" end=\"3.3s\">" + accent + "cho</span>"
                + "<span begin=\"4.1s\" end=\"4.45s\">" + music + "</span>"
                + "<span begin=\"4.8s\" end=\"5.7s\">尾声</span>"
                + "</p></div></body></tt>";
        List<LyricLine> parsed = new ArrayList<>();
        LyricParser.parseTtml(ttml, parsed);
        assertEquals(1, parsed.size());
        LyricLine line = parsed.get(0);
        assertEquals(han + "光" + accent + "cho" + music + "尾声", line.lyric);
        assertEquals(List.of(han + "光", accent + "cho", music, "尾声"),
                line.words.stream().map(word -> word.word).collect(Collectors.toList()));
        assertArrayEquals(new long[]{1200, 2400, 4100, 4800},
                line.words.stream().mapToLong(word -> word.timestamp).toArray());
        assertArrayEquals(new long[]{700, 900, 350, 900},
                line.words.stream().mapToLong(word -> word.duration).toArray());
        List<LyricGraphemeTimeline.Glyph> source = LyricGraphemeTimeline.of(line);

        List<Segment> segments = SonnetProgram.segments(line, 99999);

        assertLossless(line, segments);
        assertTimeline(source, glyphs(segments));
        assertTrue(glyphs(segments).stream().anyMatch(glyph -> glyph.text.equals(han) && glyph.startMs == 1200));
        assertTrue(glyphs(segments).stream().anyMatch(glyph -> glyph.text.equals(accent) && glyph.startMs == 2400));
        assertTrue(glyphs(segments).stream().anyMatch(glyph -> glyph.text.equals(music)
                && glyph.startMs == 4100 && glyph.endMs == 4450));
        assertSame(source, LyricGraphemeTimeline.of(line));
    }

    @Test
    void mixedCjkLatinEmojiWhitespaceAndPunctuationAreLossless() {
        String family = "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        LyricLine line = line(1000, 4200, "… \t中文，hello  世界! " + family + " e\u0301\u00a0fin。 \n");

        List<Segment> segments = SonnetProgram.segments(line, 7000);

        assertLossless(line, segments);
        assertTimeline(LyricGraphemeTimeline.of(line), glyphs(segments));
        assertTrue(segments.stream().anyMatch(segment -> segment.text.contains("hello") && segment.wordLike));
        assertTrue(glyphs(segments).stream().anyMatch(glyph -> glyph.text.length() > 1));
    }

    @Test
    void punctuationAttachesToPreviousSegmentWhileSpacesRemainSeparate() {
        LyricLine line = line(0, 1000, "  Hello, world! ");
        List<Segment> segments = SonnetProgram.segments(line, 1000);

        assertEquals(List.of("  ", "Hello,", " ", "world!", " "),
                segments.stream().map(segment -> segment.text).collect(Collectors.toList()));
        assertFalse(segments.get(0).wordLike);
        assertTrue(segments.get(1).wordLike);
        assertFalse(segments.get(2).wordLike);
        assertTrue(segments.get(3).wordLike);
        assertLossless(line, segments);
    }

    @Test
    void semanticWordsAreNeitherCadenzaMergedNorCappedAtNine() {
        String text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen";
        LyricLine line = line(0, 3000, text);
        List<Segment> segments = SonnetProgram.segments(line, 3000);

        assertEquals(15L, segments.stream().filter(segment -> segment.wordLike).count());
        assertEquals(29, segments.size());
        assertLossless(line, segments);
    }

    @Test
    void missingDurationUsesNextStartAndFourSecondsForLastLineWithoutMutatingInput() {
        LyricLine first = line(1000, 0, "abcd");
        LyricLine last = line(4500, 0, "end");
        List<LyricGraphemeTimeline.Glyph> cached = LyricGraphemeTimeline.of(first);

        SonnetProgram program = SonnetProgram.compile(List.of(first, last), "fallback");

        assertEquals(2, program.shots.size());
        assertEquals(4500.0, program.shots.get(0).endMs);
        assertEquals(8500.0, program.shots.get(1).endMs);
        List<Glyph> firstGlyphs = glyphs(program.shots.get(0).lines.get(0));
        assertEquals(1000.0, firstGlyphs.get(0).startMs);
        assertEquals(1875.0, firstGlyphs.get(0).endMs);
        assertEquals(4500.0, firstGlyphs.get(3).endMs);
        List<Glyph> lastGlyphs = glyphs(program.shots.get(1).lines.get(0));
        assertEquals(4500.0, lastGlyphs.get(0).startMs);
        assertEquals(8500.0, lastGlyphs.get(lastGlyphs.size() - 1).endMs);
        assertEquals(0L, first.duration);
        assertEquals(0L, last.duration);
        assertTrue(first.words.isEmpty());
        assertSame(cached, LyricGraphemeTimeline.of(first));
        assertTrue(cached.stream().allMatch(glyph -> glyph.timestamp == 1000L && glyph.duration == 0L));
    }

    @Test
    void finalSyllableControlsVisualEndButNextLineClipsTheShotOnly() {
        LyricLine first = line(1000, 500, "tail");
        first.words.add(new LyricLine.Word("tail", 3000, 1500));
        assertEquals(4500.0, SonnetProgram.compile(List.of(first), "tail").shots.get(0).endMs);
        LyricLine next = line(3500, 400, "chorus");
        next.isChorus = true;
        next.chorusIndex = 0;

        SonnetProgram program = SonnetProgram.compile(List.of(first, next), "tail");

        assertEquals(2, program.shots.size());
        assertEquals(3500.0, program.shots.get(0).endMs);
        List<Glyph> firstGlyphs = glyphs(program.shots.get(0).lines.get(0));
        assertEquals(4500.0, firstGlyphs.get(firstGlyphs.size() - 1).endMs);
        assertTimeline(LyricGraphemeTimeline.of(first), firstGlyphs);
        assertArrayEquals(new int[]{0}, program.shots.get(0).sourceLineIndices);
        assertArrayEquals(new int[]{1}, program.shots.get(1).sourceLineIndices);
    }

    @Test
    void unmappedBackingSyllableStillExtendsTheVisualEnd() {
        LyricLine line = line(1000, 500, "tail");
        line.words.add(new LyricLine.Word("tail", 1000, 500));
        LyricLine.Word backing = new LyricLine.Word("backing", 3000, 1500);
        backing.background = true;
        line.words.add(backing);

        SonnetProgram.Shot shot = SonnetProgram.compile(List.of(line), "backing").shots.get(0);

        assertEquals(4500.0, shot.endMs);
        assertLossless(line, shot.lines.get(0));
        assertTimeline(LyricGraphemeTimeline.of(line), glyphs(shot.lines.get(0)));
    }

    @Test
    void paragraphGapUsesMedianTimesTwoPointFiveAndBothClamps() {
        assertGapBoundary(100, 100, 100, 1249, 1250);
        assertGapBoundary(600, 600, 600, 1499, 1500);
        assertGapBoundary(2000, 2000, 2000, 3499, 3500);
    }

    @Test
    void paragraphGapMedianAveragesTheMiddlePairForEvenSamples() {
        SonnetProgram program = SonnetProgram.compile(linesWithGaps(100, 500, 1000, 1875), "median");
        assertEquals(paragraphOf(program, 0), paragraphOf(program, 3));
        assertNotEquals(paragraphOf(program, 3), paragraphOf(program, 4));
    }

    @Test
    void paragraphLineCapStillHoldsWhenLargestGapIsLateInTheDraft() {
        List<LyricLine> lines = regularLines(24, 1000);
        lines.get(11).duration = 100; // Largest gap would produce an oversized prefix upstream.
        SonnetProgram program = SonnetProgram.compile(lines, "late-gap");

        assertGroupBoundsAndCoverage(program, lines);
        assertTrue(program.shots.stream().anyMatch(shot -> !shot.id.startsWith("p0-")));
    }

    @Test
    void paragraphDurationCapSplitsLongContiguousDrafts() {
        List<LyricLine> lines = regularLines(5, 5000);
        for (LyricLine line : lines) line.duration = 4000;
        SonnetProgram program = SonnetProgram.compile(lines, "duration");

        assertGroupBoundsAndCoverage(program, lines);
        assertNotEquals(paragraphOf(program, 0), paragraphOf(program, 4));
    }

    @Test
    void shotsGroupAtMostFourLinesAndSixSecondsIncludingExactBoundary() {
        List<LyricLine> lines = regularLines(6, 1500);
        SonnetProgram program = SonnetProgram.compile(lines, "four-lines");
        assertEquals(2, program.shots.size());
        assertArrayEquals(new int[]{0, 1, 2, 3}, program.shots.get(0).sourceLineIndices);
        assertArrayEquals(new int[]{4, 5}, program.shots.get(1).sourceLineIndices);
        assertEquals(6000.0, program.shots.get(0).endMs - program.shots.get(0).startMs);
        assertGroupBoundsAndCoverage(program, lines);

        lines = regularLines(5, 2000);
        program = SonnetProgram.compile(lines, "six-seconds");
        assertArrayEquals(new int[]{0, 1, 2}, program.shots.get(0).sourceLineIndices);
        assertArrayEquals(new int[]{3, 4}, program.shots.get(1).sourceLineIndices);
        assertGroupBoundsAndCoverage(program, lines);
    }

    @Test
    void oneLongAuthoredLineRemainsIndivisibleAndLossless() {
        LyricLine line = line(1000, 20000, "one sustained line");
        SonnetProgram program = SonnetProgram.compile(List.of(line), "long-line");
        assertEquals(1, program.shots.size());
        assertEquals(21000.0, program.shots.get(0).endMs);
        assertArrayEquals(new int[]{0}, program.shots.get(0).sourceLineIndices);
        assertLossless(line, program.shots.get(0).lines.get(0));
    }

    @Test
    void allSevenShotKindsRemainInTheSeededPool() {
        EnumSet<Kind> seen = EnumSet.noneOf(Kind.class);
        LyricLine line = line(1000, 2400, "Hello, 世界!");
        for (int seed = 0; seed < 128; seed++) {
            seen.add(SonnetProgram.compile(List.of(line), "pool-" + seed).shots.get(0).kind);
        }
        assertEquals(EnumSet.allOf(Kind.class), seen);
    }

    @Test
    void breathOverridesAndParagraphBoundariesNeverRepeatAdjacentKinds() {
        List<LyricLine> lines = regularLines(18, 6000);
        for (LyricLine line : lines) {
            line.duration = 500;
            line.lyric = "breath";
        }
        for (int seed = 0; seed < 32; seed++) {
            SonnetProgram program = SonnetProgram.compile(lines, "breaths-" + seed);
            assertEquals(Kind.QUIET_TABLEAU, program.shots.get(0).kind);
            assertNoRepeats(program);
        }
        assertNoRepeats(SonnetProgram.compile(regularLines(60, 1000), "within-and-between"));
    }

    @Test
    void chorusOverrideAvoidsQuietTableauAndDoesNotReintroduceARepeat() {
        List<LyricLine> lines = List.of(line(1000, 2400, "Hello, 世界!"), line(5000, 2400, "Hello, 世界!"));
        for (int index = 0; index < lines.size(); index++) {
            lines.get(index).isChorus = true;
            lines.get(index).chorusIndex = index;
        }
        SonnetProgram program = SonnetProgram.compile(lines, "chorus-29");
        assertEquals(2, program.shots.size());
        assertEquals(Kind.TYPE_IMPACT, program.shots.get(0).kind);
        assertEquals(Kind.FRAGMENT_COLLAGE, program.shots.get(1).kind);
        assertTrue(program.shots.stream().allMatch(shot -> shot.chorus && shot.kind != Kind.QUIET_TABLEAU));
        assertNoRepeats(program);

        SonnetProgram.Shot shot = SonnetProgram.compile(List.of(lines.get(0)), "camera-8").shots.get(0);
        assertEquals(Kind.TYPE_IMPACT, shot.kind);
        assertEquals(1.4065882f, shot.zoom, 0.0000001f);
    }

    @Test
    void metadataChangesSplitChorusAndInterludeWithoutRenumberingSourceLines() {
        List<LyricLine> lines = regularLines(4, 1000);
        lines.get(1).isChorus = true;
        lines.get(1).chorusIndex = 0;
        lines.get(2).isChorus = true;
        lines.get(2).chorusIndex = 1;
        lines.get(3).isBreakLine = true;
        SonnetProgram program = SonnetProgram.compile(lines, "metadata");
        assertEquals(4, program.shots.size());
        for (int index = 0; index < lines.size(); index++) {
            assertEquals("p" + index + "-s0", program.shots.get(index).id);
            assertArrayEquals(new int[]{index}, program.shots.get(index).sourceLineIndices);
            assertEquals(lines.get(index).isChorus, program.shots.get(index).chorus);
        }
    }

    @Test
    void cameraMatchesUpstreamUnsignedHashFixturesForAllThreeZoomRanges() {
        List<LyricLine> lines = List.of(line(1000, 2400, "Hello, 世界!"));
        assertEquals(4185645150L, Integer.toUnsignedLong(SonnetTypography.hash("camera-6:0:0:camera")));
        assertCamera(SonnetProgram.compile(lines, "camera-1").shots.get(0), Kind.EDITORIAL_COLUMN,
                -0.052588236f, 0.045843136f, 1.4647058f, 0.012078431f);
        assertCamera(SonnetProgram.compile(lines, "camera-6").shots.get(0), Kind.POSTER_BLOCKS,
                -0.023647059f, 0.052980393f, 1.0971764f, 0.038117647f);
        assertCamera(SonnetProgram.compile(lines, "camera-8").shots.get(0), Kind.QUIET_TABLEAU,
                0.00882353f, 0.0052156863f, 1.2635294f, -0.026509805f);
        assertCamera(SonnetProgram.compile(lines, "sonnet-fixture").shots.get(0), Kind.MASK_REVEAL,
                -0.08223529f, -0.005764706f, 1.3107451f, -0.014901961f);
    }

    @Test
    void seeksUseAbsoluteTimeAndHoldThroughGapsInEitherDirection() {
        SonnetProgram program = SonnetProgram.compile(List.of(
                line(1000, 1000, "first"), line(7000, 1000, "second"), line(13000, 1000, "third")), "seeks");
        assertEquals(3, program.shots.size());
        assertEquals(0, program.shotAt(-1000));
        assertEquals(0, program.shotAt(1000));
        assertEquals(0, program.shotAt(6999.99));
        assertEquals(1, program.shotAt(7000));
        assertEquals(2, program.shotAt(13000));
        assertEquals(0, program.shotAt(2500));
        assertEquals(1, program.shotAt(12000));
        assertEquals(2, program.shotAt(Double.POSITIVE_INFINITY));
        assertEquals(0, program.shotAt(Double.NaN));
        assertEquals(-1, SonnetProgram.compile(List.of(), "empty").shotAt(0));
    }

    @Test
    void recompilationAndSeekingDoNotChangeShotsOrTransitions() {
        List<LyricLine> lines = regularLines(20, 1500);
        SonnetProgram first = SonnetProgram.compile(lines, "stable");
        first.shotAt(29000);
        first.shotAt(0);
        SonnetProgram second = SonnetProgram.compile(lines, "stable");
        assertEquals(first.shots.size(), second.shots.size());
        for (int index = 0; index < first.shots.size(); index++) {
            SonnetProgram.Shot a = first.shots.get(index);
            SonnetProgram.Shot b = second.shots.get(index);
            assertEquals(a.id, b.id);
            assertEquals(a.kind, b.kind);
            assertEquals(a.startMs, b.startMs);
            assertEquals(a.endMs, b.endMs);
            assertArrayEquals(a.sourceLineIndices, b.sourceLineIndices);
            assertEquals(a.cameraX, b.cameraX);
            assertEquals(a.cameraY, b.cameraY);
            assertEquals(a.zoom, b.zoom);
            assertEquals(a.rotationRad, b.rotationRad);
            assertEquals(a.transitionKind, b.transitionKind);
            assertTrue(a.transitionKind >= 0 && a.transitionKind <= 2);
        }
    }

    @Test
    void compiledTextAndCollectionsAreSnapshots() {
        LyricLine line = line(1000, 1000, "keep me");
        SonnetProgram program = SonnetProgram.compile(List.of(line), "snapshot");
        SonnetProgram.Shot shot = program.shots.get(0);
        line.lyric = "changed";
        line.timestamp = 9000;
        assertEquals(1000.0, shot.startMs);
        assertEquals("keep me", text(shot.lines.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> program.shots.clear());
        assertThrows(UnsupportedOperationException.class, () -> shot.lines.clear());
        assertThrows(UnsupportedOperationException.class, () -> shot.lines.get(0).clear());
    }

    private static LyricLine line(long start, long duration, String text) {
        LyricLine line = new LyricLine(start, text);
        line.duration = duration;
        return line;
    }

    private static List<LyricLine> regularLines(int count, long duration) {
        List<LyricLine> lines = new ArrayList<>();
        for (int index = 0; index < count; index++) lines.add(line(1000 + index * duration, duration, "lyric line " + index));
        return lines;
    }

    private static List<LyricLine> linesWithGaps(long... gaps) {
        List<LyricLine> lines = new ArrayList<>();
        long start = 1000;
        lines.add(line(start, 1000, "lyric line 0"));
        for (int index = 0; index < gaps.length; index++) {
            start += 1000 + gaps[index];
            lines.add(line(start, 1000, "lyric line " + (index + 1)));
        }
        return lines;
    }

    private static List<String> wordTexts(List<Segment> segments) {
        return segments.stream().filter(segment -> segment.wordLike)
                .map(segment -> segment.text).collect(Collectors.toList());
    }

    private static List<Glyph> glyphs(List<Segment> segments) {
        return segments.stream().flatMap(segment -> segment.glyphs.stream()).collect(Collectors.toList());
    }

    private static String text(List<Segment> segments) {
        return segments.stream().map(segment -> segment.text).collect(Collectors.joining());
    }

    private static void assertLossless(LyricLine line, List<Segment> segments) {
        assertEquals(line.lyric, text(segments));
        int cursor = 0;
        for (Segment segment : segments) {
            assertEquals(cursor, segment.startOffset);
            assertTrue(segment.endOffset > segment.startOffset);
            assertEquals(segment.text, line.lyric.substring(segment.startOffset, segment.endOffset));
            assertEquals(segment.text, segment.glyphs.stream().map(glyph -> glyph.text).collect(Collectors.joining()));
            cursor = segment.endOffset;
        }
        assertEquals(line.lyric.length(), cursor);
    }

    private static void assertTimeline(List<LyricGraphemeTimeline.Glyph> expected, List<Glyph> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            LyricGraphemeTimeline.Glyph a = expected.get(index);
            Glyph b = actual.get(index);
            assertEquals(a.text, b.text);
            assertEquals((double) a.timestamp, b.startMs);
            assertEquals(a.timestamp + (double) a.duration, b.endMs);
        }
    }

    private static String paragraphOf(SonnetProgram program, int sourceIndex) {
        for (SonnetProgram.Shot shot : program.shots) {
            for (int index : shot.sourceLineIndices) {
                if (index == sourceIndex) return shot.id.substring(0, shot.id.indexOf('-'));
            }
        }
        throw new AssertionError("Missing source line " + sourceIndex);
    }

    private static void assertGapBoundary(long... gaps) {
        SonnetProgram program = SonnetProgram.compile(linesWithGaps(gaps), "gaps");
        assertEquals(paragraphOf(program, 0), paragraphOf(program, gaps.length - 1));
        assertNotEquals(paragraphOf(program, gaps.length - 1), paragraphOf(program, gaps.length));
    }

    private static void assertGroupBoundsAndCoverage(SonnetProgram program, List<LyricLine> input) {
        Map<String, List<SonnetProgram.Shot>> paragraphs = new LinkedHashMap<>();
        List<Integer> indices = new ArrayList<>();
        for (SonnetProgram.Shot shot : program.shots) {
            assertTrue(shot.sourceLineIndices.length <= 4);
            assertTrue(shot.endMs - shot.startMs <= 6000.0);
            assertEquals(shot.sourceLineIndices.length, shot.lines.size());
            for (int index = 0; index < shot.sourceLineIndices.length; index++) {
                int sourceIndex = shot.sourceLineIndices[index];
                indices.add(sourceIndex);
                assertLossless(input.get(sourceIndex), shot.lines.get(index));
            }
            String paragraph = shot.id.substring(0, shot.id.indexOf('-'));
            paragraphs.computeIfAbsent(paragraph, ignored -> new ArrayList<>()).add(shot);
        }
        assertEquals(input.size(), indices.size());
        for (int index = 0; index < input.size(); index++) assertEquals(index, indices.get(index).intValue());
        for (List<SonnetProgram.Shot> paragraph : paragraphs.values()) {
            assertTrue(paragraph.stream().mapToInt(shot -> shot.sourceLineIndices.length).sum() <= 6);
            assertTrue(paragraph.get(paragraph.size() - 1).endMs - paragraph.get(0).startMs <= 18000.0);
        }
    }

    private static void assertNoRepeats(SonnetProgram program) {
        for (int index = 1; index < program.shots.size(); index++) {
            assertNotEquals(program.shots.get(index - 1).kind, program.shots.get(index).kind);
        }
    }

    private static void assertCamera(SonnetProgram.Shot shot, Kind kind, float x, float y, float zoom, float rotation) {
        assertEquals("p0-s0", shot.id);
        assertEquals(kind, shot.kind);
        assertEquals(x, shot.cameraX, 0.0000001f);
        assertEquals(y, shot.cameraY, 0.0000001f);
        assertEquals(zoom, shot.zoom, 0.0000001f);
        assertEquals(rotation, shot.rotationRad, 0.0000001f);
    }
}
