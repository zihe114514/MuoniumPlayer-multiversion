package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class MusicCapsuleLyricsTest {
    @BeforeAll
    static void fonts() { MusicCardRenderTest.fonts(); }

    private static LyricLine timed(String text) {
        LyricLine line = new LyricLine(10_000L, text);
        line.duration = text.length() * 600L;
        for (int i = 0; i < text.length(); i++) {
            line.words.add(new LyricLine.Word(text.substring(i, i + 1), 10_000L + i * 600L, 450L));
        }
        return line;
    }

    @Test
    void capsuleOnlyGrowsToItsOwnMaximumAndNeverOverflowsTheScreen() {
        assertEquals(176.0, DynamicIslandLayoutCalculator.musicRowWidth(250, 320, 130, 1, 800));
        assertEquals(240.0, DynamicIslandLayoutCalculator.musicRowWidth(250, 320, 240, 1, 800));
        assertEquals(320.0, DynamicIslandLayoutCalculator.musicRowWidth(250, 320, 520, 1, 800));
        assertEquals(180.0, DynamicIslandLayoutCalculator.musicRowWidth(720, 180, 520, 1, 800),
                "maximum wins even when the base slider exceeds it");
        assertEquals((220.0 - 24.0) / 1.35,
                DynamicIslandLayoutCalculator.musicRowWidth(250, 320, 520, 1.35, 220), 1e-8);
        assertEquals(320.0, DynamicIslandLayoutCalculator.musicRowWidth(250, Double.NaN, 520, 1, 800));
    }

    @Test
    void truncatedSyllableStartsOneRevealAndTheTailNeverWraps() {
        LyricLine line = timed("就是爱你爱着你");
        MusicCapsuleLyrics lyrics = new MusicCapsuleLyrics();
        lyrics.prepare(line, line.lyric, FontManager.pf16);
        double viewport = FontManager.pf16.getStringWidthD("就是爱你爱");
        assertEquals(0.0, lyrics.offset(viewport, 9_999L));
        assertEquals(0.0, lyrics.offset(viewport, 12_999L));
        assertEquals(0.0, lyrics.offset(viewport, 13_000L), "do not scroll before the clipped syllable starts");
        assertTrue(lyrics.offset(viewport, 13_080L) > 0.0);
        double previous = 0.0;
        for (long position = 10_000L; position <= 16_000L; position += 4L) {
            double offset = lyrics.offset(viewport, position);
            assertTrue(offset + 1e-8 >= previous, "no backwards tick or restart");
            assertTrue(offset <= lyrics.width() - viewport + 1e-8);
            previous = offset;
        }
        double tail = lyrics.width() - viewport;
        assertEquals(tail, lyrics.offset(viewport, 16_000L), 1e-8);
        assertEquals(tail, lyrics.offset(viewport, 200_000L), 1e-8);
        assertEquals(0.0, lyrics.offset(viewport, 11_000L), "backwards seek uses the new clock without stale offset");
        assertEquals(0.0, lyrics.offset(lyrics.width(), 16_000L));
    }

    @Test
    void singingGapsAndAStillClockCannotAdvanceTheViewport() {
        LyricLine line = timed("就是爱你爱着你");
        MusicCapsuleLyrics lyrics = new MusicCapsuleLyrics();
        lyrics.prepare(line, line.lyric, FontManager.pf16);
        double viewport = FontManager.pf16.getStringWidthD("就是爱你爱");
        assertEquals(lyrics.offset(viewport, 13_200L), lyrics.offset(viewport, 13_590L));
        for (int frame = 0; frame < 1000; frame++) {
            assertEquals(lyrics.offset(viewport, 13_080L), lyrics.offset(viewport, 13_080L));
        }
    }

    @Test
    void repeatedTextUsesEachNewLinesAbsoluteTiming() {
        LyricLine first = timed("就是爱你爱着你");
        LyricLine second = new LyricLine(30_000L, first.lyric);
        second.duration = first.duration;
        for (LyricLine.Word word : first.words) {
            second.words.add(new LyricLine.Word(word.word, word.timestamp + 20_000L, word.duration));
        }
        MusicCapsuleLyrics lyrics = new MusicCapsuleLyrics();
        double viewport = FontManager.pf16.getStringWidthD("就是爱你爱");
        lyrics.prepare(first, first.lyric, FontManager.pf16);
        assertTrue(lyrics.offset(viewport, 14_000L) > 0.0);
        lyrics.prepare(second, second.lyric, FontManager.pf16);
        assertEquals(0.0, lyrics.offset(viewport, 30_000L));
        assertTrue(lyrics.offset(viewport, 33_080L) > 0.0);
    }

    @Test
    void fullTextPlacementIncludesSpacesPunctuationAndTrimmedIndentation() {
        LyricLine line = new LyricLine(10_000L, "  爱 你，爱着你  ");
        line.words.add(new LyricLine.Word("爱", 10_000L, 400L));
        line.words.add(new LyricLine.Word("你", 10_600L, 400L));
        line.words.add(new LyricLine.Word("爱", 11_200L, 400L));
        line.words.add(new LyricLine.Word("着你", 11_800L, 800L));
        MusicCapsuleLyrics lyrics = new MusicCapsuleLyrics();
        lyrics.prepare(line, line.lyric.trim(), FontManager.pf16);
        double viewport = FontManager.pf16.getStringWidthD("爱 你，爱");
        assertEquals(0.0, lyrics.offset(viewport, 11_800L));
        assertTrue(lyrics.offset(viewport, 11_880L) > 0.0);
        assertEquals(lyrics.width() - viewport, lyrics.offset(viewport, 20_000L), 1e-8);
    }

    @Test
    void trailingPunctuationIsFullyRevealedOnceAfterTheLastSungWord() {
        LyricLine line = new LyricLine(10_000L, "就是爱你爱着你！");
        LyricLine base = timed("就是爱你爱着你");
        line.words.addAll(base.words);
        MusicCapsuleLyrics lyrics = new MusicCapsuleLyrics();
        lyrics.prepare(line, line.lyric, FontManager.pf16);
        double viewport = FontManager.pf16.getStringWidthD("就是爱你爱");
        assertEquals(FontManager.pf16.getStringWidthD(base.lyric) - viewport,
                lyrics.offset(viewport, 14_050L), 1e-8);
        assertEquals(lyrics.width() - viewport, lyrics.offset(viewport, 14_210L), 1e-8);
        assertEquals(lyrics.width() - viewport, lyrics.offset(viewport, 200_000L), 1e-8);
    }

    @Test
    void plainLrcUsesOneBoundedLineRevealWithoutInventedWordTimings() {
        LyricLine line = new LyricLine(10_000L, "就是爱你爱着你");
        line.duration = 4200L;
        MusicCapsuleLyrics lyrics = new MusicCapsuleLyrics();
        lyrics.prepare(line, line.lyric, FontManager.pf16);
        double viewport = lyrics.width() * .6;
        assertEquals(0.0, lyrics.offset(viewport, 12_000L));
        assertTrue(lyrics.offset(viewport, 13_000L) > 0.0);
        assertEquals(lyrics.width() - viewport, lyrics.offset(viewport, 14_200L), 1e-8);
        assertEquals(lyrics.width() - viewport, lyrics.offset(viewport, 100_000L), 1e-8);
        assertFalse(line.hasTimedWords());
        assertTrue(line.words.isEmpty());
    }

    @Test
    void fontRevisionAndTimelineChangesInvalidateBoundedMeasurements() {
        LyricLine line = timed("就是爱你爱着你");
        MusicCapsuleLyrics lyrics = new MusicCapsuleLyrics();
        lyrics.prepare(line, line.lyric, FontManager.pf12bold);
        double small = lyrics.width();
        lyrics.prepare(line, line.lyric, FontManager.pf16);
        assertTrue(lyrics.width() > small);
        double viewport = FontManager.pf16.getStringWidthD("就是爱你爱");
        assertTrue(lyrics.offset(viewport, 13_080L) > 0.0);
        line.words.set(5, new LyricLine.Word("着", 15_000L, 450L));
        line.markDirty();
        lyrics.prepare(line, line.lyric, FontManager.pf16);
        assertEquals(0.0, lyrics.offset(viewport, 13_080L));
        lyrics.prepare(null, "新歌名", FontManager.pf16);
        assertEquals(0.0, lyrics.offset(10.0, 200_000L));
    }

    @Test
    void lineSwitchCurveHasSoftMonotonicEndpoints() {
        assertEquals(0.0, MusicCapsuleLyrics.ease(0.0));
        assertEquals(1.0, MusicCapsuleLyrics.ease(1.0));
        assertEquals(.5, MusicCapsuleLyrics.ease(.5), 1e-8);
        assertTrue(MusicCapsuleLyrics.ease(.01) < .00002);
        double previous = 0.0;
        for (int i = 0; i <= 360; i++) {
            double value = MusicCapsuleLyrics.ease(i / 360.0);
            assertTrue(value >= previous - 1e-8);
            previous = value;
        }
    }
}
