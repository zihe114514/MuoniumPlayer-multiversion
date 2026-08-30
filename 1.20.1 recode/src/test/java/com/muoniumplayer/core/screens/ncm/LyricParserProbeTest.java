package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词解析离线探针。纯 CPU 逻辑，不需要 GL、不需要客户端实例、不需要账号，
 * 因此每次改完都能立刻跑，用来兜住移植过程中最容易被无声改坏的一层。
 *
 * <p>测试放在同包下是为了能调用 package-private 的
 * {@link LyricParser#parseTtmlClock(String)}。
 */
class LyricParserProbeTest {

    @Test
    void yrcKeepsPerWordTimingAndFoldsZeroDurationSyllables() {
        // [行起始,行时长] 后面跟 (词起始,词时长,0)词 ...
        String yrc = "[1000,2000](1000,500,0)Hel(1500,500,0)lo(0,0,0) (2000,1000,0)world\n";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseYrc(yrc, lines);

        assertEquals(1, lines.size());
        LyricLine line = lines.get(0);
        assertEquals(1000L, line.timestamp);
        assertEquals("Hello world", line.lyric);
        // (0,0,0) 的空格必须折进前一个词，而不是变成时间戳为 0 的假词，
        // 否则逐字扫词会在行首闪回。
        assertEquals(3, line.words.size());
        assertEquals("Hel", line.words.get(0).word);
        assertEquals("lo ", line.words.get(1).word);
        assertEquals("world", line.words.get(2).word);
        assertTrue(line.hasTimedWords());
        assertTrue(LyricParser.hasWordByWordTiming(lines));
    }

    @Test
    void yrcWordTimestampsAreAbsoluteAndMonotonic() {
        String yrc = "[5000,3000](5000,1000,0)a(6000,1000,0)b(7000,1000,0)c";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseYrc(yrc, lines);

        assertEquals(1, lines.size());
        List<LyricLine.Word> words = lines.get(0).words;
        assertEquals(3, words.size());
        long previous = Long.MIN_VALUE;
        for (LyricLine.Word word : words) {
            assertTrue(word.timestamp >= 5000L, "音节起始不应早于行首: " + word.timestamp);
            assertTrue(word.timestamp > previous, "音节时间必须单调递增");
            previous = word.timestamp;
        }
    }

    @Test
    void qrcParsesIntoTheSameRenderModelAsYrc() {
        // QRC: [行起始,行时长]文本(绝对起始,时长)...；开头的元数据行必须被跳过。
        String qrc = "[ti:song]\n[ar:artist]\n[1000,2000]Hel(1000,500)lo(1500,500) world(2000,1000)\n";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseQrc(qrc, lines);

        assertEquals(1, lines.size(), "元数据行不应产出歌词行");
        LyricLine line = lines.get(0);
        assertEquals(1000L, line.timestamp);
        assertTrue(line.hasTimedWords());
        assertTrue(line.lyric.contains("Hel"));
        assertTrue(line.lyric.contains("world"));
    }

    @Test
    void plainLrcHasNoWordTiming() {
        // 纯 LRC 没有逐字轴：hasWordByWordTiming 必须为 false，
        // 否则渲染层会去走逐字扫词分支、把整行当成一个词闪现。
        List<LyricLine> lines = new ArrayList<>();
        lines.add(new LyricLine(0L, "第一行"));
        lines.add(new LyricLine(1000L, "第二行"));
        assertFalse(LyricParser.hasWordByWordTiming(lines));
        assertFalse(lines.get(0).hasTimedWords());
    }

    @Test
    void ttmlClockAcceptsEveryFormThatShowsUpInTheWild() {
        assertEquals(12345L, LyricParser.parseTtmlClock("12.345"));
        assertEquals(163590L, LyricParser.parseTtmlClock("02:43.590"));
        assertEquals(3723400L, LyricParser.parseTtmlClock("1:02:03.4"));
        assertEquals(300L, LyricParser.parseTtmlClock("300ms"));
        assertEquals(12500L, LyricParser.parseTtmlClock("12.5s"));
        assertEquals(120000L, LyricParser.parseTtmlClock("2m"));
        assertEquals(3600000L, LyricParser.parseTtmlClock("1h"));
    }

    @Test
    void ttmlClockReturnsMinusOneInsteadOfZeroWhenUnparseable() {
        // -1 与 0 的区别是"这个 span 没有时间轴"与"这个 span 在第 0 毫秒"，
        // 混淆会让无轴的 span 全部堆到行首。
        assertEquals(-1L, LyricParser.parseTtmlClock(null));
        assertEquals(-1L, LyricParser.parseTtmlClock(""));
        assertEquals(-1L, LyricParser.parseTtmlClock("   "));
        assertEquals(-1L, LyricParser.parseTtmlClock("abc"));
        assertEquals(-1L, LyricParser.parseTtmlClock("--:--"));
    }

    @Test
    void ttmlParsesWordTimingTranslationAndBackgroundVocals() {
        String ttml = ""
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\">"
                + "<body><div>"
                + "<p begin=\"1.0s\" end=\"3.0s\">"
                + "<span begin=\"1.0s\" end=\"2.0s\">Hello</span>"
                + "<span begin=\"2.0s\" end=\"3.0s\"> world</span>"
                + "<span ttm:role=\"x-translation\">你好世界</span>"
                + "<span ttm:role=\"x-bg\"><span begin=\"3.0s\" end=\"3.5s\">ooh</span></span>"
                + "</p>"
                + "</div></body></tt>";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseTtml(ttml, lines);

        assertEquals(1, lines.size());
        LyricLine line = lines.get(0);
        assertEquals(1000L, line.timestamp);
        assertTrue(line.hasTimedWords(), "TTML 的 span 应产出逐字轴");
        assertEquals("你好世界", line.translationText);

        // x-bg 的和声排在主行最后一个词之后，应追加到词表末尾并标记 background。
        boolean sawBackground = false;
        for (LyricLine.Word word : line.words) {
            if (word.background) {
                sawBackground = true;
                assertEquals("ooh", word.word.trim());
            }
        }
        assertTrue(sawBackground, "x-bg 和声必须被标记为 background");
    }

    @Test
    void parsersAreNullAndEmptySafe() {
        List<LyricLine> lines = new ArrayList<>();
        lines.add(new LyricLine(0L, "残留数据"));

        LyricParser.parseYrc(null, lines);
        assertTrue(lines.isEmpty(), "解析入口必须先清空目标列表");

        lines.add(new LyricLine(0L, "残留数据"));
        LyricParser.parseQrc("", lines);
        assertTrue(lines.isEmpty());

        lines.add(new LyricLine(0L, "残留数据"));
        LyricParser.parseTtml("not xml at all", lines);
        assertTrue(lines.isEmpty());
    }
}
