package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VTT / awlrc 两条新解析链与「精确键优先」对齐的离线探针。
 *
 * <p>这一层的特点是<b>错了也不报错</b>：格式嗅探多认了一种写法，只会让某类本地歌词悄悄按错的
 * 时间轴显示；对齐策略回退了一档，只会让某一行挂上别人的翻译。所以断言都钉在具体数值上。</p>
 */
class LyricParserFormatProbeTest {

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    // ── WEBVTT ──────────────────────────────────────────────────────────────

    @Test
    void vttWithInlineTimingsBuildsRealWordTimeline() {
        String vtt = "WEBVTT\n\n00:01.500 --> 00:03.000\n<00:01.500>Hel<00:02.000>lo";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseVtt(vtt, "", "", lines);

        assertEquals(1, lines.size(), "WEBVTT 头那一段不该产出歌词行");
        LyricLine line = lines.get(0);
        assertEquals(1500L, line.timestamp);
        assertEquals("Hello", line.lyric);
        assertEquals(2, line.words.size());
        assertEquals("Hel", line.words.get(0).word);
        assertEquals(1500L, line.words.get(0).timestamp);
        assertEquals(500L, line.words.get(0).duration);
        assertEquals(2000L, line.words.get(1).timestamp);
        // 最后一个音节唱到提示块的结束时刻。
        assertEquals(1000L, line.words.get(1).duration);
        assertTrue(LyricParser.hasWordByWordTiming(lines));
    }

    @Test
    void vttWithoutInlineTimingsStaysLineLevel() {
        String vtt = "WEBVTT\n\n00:01.000 --> 00:04.000\nHello world";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseVtt(vtt, "", "", lines);

        assertEquals(1, lines.size());
        assertEquals("Hello world", lines.get(0).lyric);
        // 本项目对"假逐字"的态度是明确的：宁可行级，也不按字数伪造一份看起来像逐字的均分词表。
        assertTrue(lines.get(0).words.isEmpty());
        assertFalse(LyricParser.hasWordByWordTiming(lines));
        // VTT 自带 end，比普通 LRC 靠下一行推出来的时长准。
        assertEquals(3000L, lines.get(0).duration);
    }

    @Test
    void vttStyleAndSpeakerTagsAreStripped() {
        String vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v 歌手><c.yellow>Hello</c>";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseVtt(vtt, "", "", lines);

        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).lyric);
    }

    @Test
    void vttCueNumberAndNoteBlocksAreSkipped() {
        String vtt = "WEBVTT\n\nNOTE 这是注释\n\n1\n00:00:01.000 --> 00:00:02.000\n第一句\n\n"
                + "2\n00:00:03.000 --> 00:00:04.000\n第二句";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseVtt(vtt, "", "", lines);

        assertEquals(2, lines.size());
        assertEquals("第一句", lines.get(0).lyric);
        assertEquals(1000L, lines.get(0).timestamp);
        assertEquals("第二句", lines.get(1).lyric);
        assertEquals(3000L, lines.get(1).timestamp);
    }

    // ── awlrc ───────────────────────────────────────────────────────────────

    @Test
    void awlrcParsesRelativeSyllableOffsetsAgainstTheLineStart() {
        // 行头只有起始时间；音节里的第一个数是"相对行首"的毫秒偏移。
        String awlrc = "[00:01.500]<0,500>Hel<500,500>lo";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseAwlrc(awlrc, "", "", lines);

        assertEquals(1, lines.size());
        LyricLine line = lines.get(0);
        assertEquals(1500L, line.timestamp);
        assertEquals("Hello", line.lyric);
        assertEquals(2, line.words.size());
        assertEquals(1500L, line.words.get(0).timestamp);
        assertEquals(2000L, line.words.get(1).timestamp);
        assertTrue(LyricParser.hasWordByWordTiming(lines));
    }

    @Test
    void awlrcCursorOnlyMovesForwardSoBackwardsSyllablesCannotRewind() {
        // 上游偶尔写出倒退或零时长的音节标记。让迟到的时间戳生效会把扫光拖回去，
        // 所以解析层用一个只前进的游标把它们修掉。
        String awlrc = "[00:02.000]<0,500>a<200,500>b<100,500>c";
        List<LyricLine> lines = new ArrayList<>();
        LyricParser.parseAwlrc(awlrc, "", "", lines);

        assertEquals(1, lines.size());
        List<LyricLine.Word> words = lines.get(0).words;
        assertEquals(3, words.size());
        long previous = Long.MIN_VALUE;
        for (LyricLine.Word word : words) {
            assertTrue(word.timestamp >= previous, "音节时间必须单调不减: " + word.timestamp);
            assertTrue(word.duration >= 1L, "零时长音节必须被抬到至少 1ms");
            previous = word.timestamp;
        }
        assertEquals(2000L, words.get(0).timestamp);
        assertEquals(2500L, words.get(1).timestamp);
        assertEquals(3000L, words.get(2).timestamp);
    }

    // ── 格式嗅探 ────────────────────────────────────────────────────────────

    @Test
    void parseLyricTextPrefersTheAwlrcContainerOverTheDegradedBody() {
        // 洛雪导出时正文只是给不认识容器的播放器看的降级视图，容器才是权威数据。
        // 逐字轨至少要两个计时音节，才会被 hasWordByWordTiming 认成真正的逐字歌词。
        String awlrc = "[00:01.000]<0,500>逐<500,500>字";
        String body = "[00:01.000]降级正文";
        String content = body + "\n\n[awlrc:lrc:" + b64(body) + ",awlrc:" + b64(awlrc) + "]";

        List<LyricLine> lines = LyricParser.parseLyricText(content, "", "");

        assertEquals(1, lines.size());
        assertEquals("逐字", lines.get(0).lyric);
        assertEquals(2, lines.get(0).words.size());
        assertTrue(LyricParser.hasWordByWordTiming(lines));
    }

    @Test
    void parseLyricTextSniffsVttAndAwlrcWithoutAnyHint() {
        List<LyricLine> vtt = LyricParser.parseLyricText(
                "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello", "", "");
        assertEquals(1, vtt.size());
        assertEquals("Hello", vtt.get(0).lyric);

        List<LyricLine> awlrc = LyricParser.parseLyricText("[00:00:01.000]<0,500>逐<500,500>字", "", "");
        assertEquals(1, awlrc.size());
        assertEquals("逐字", awlrc.get(0).lyric);
        assertTrue(LyricParser.hasWordByWordTiming(awlrc));
    }

    @Test
    void plainLrcStillGoesThroughThePlainPath() {
        // 嗅探新增了三种格式之后，普通 LRC 必须仍然走原来那条路。
        List<LyricLine> lines = LyricParser.parseLyricText("[00:01.000]第一句\n[00:03.000]第二句", "", "");

        assertEquals(2, lines.size());
        assertEquals("第一句", lines.get(0).lyric);
        assertEquals(1000L, lines.get(0).timestamp);
        assertEquals(2000L, lines.get(0).duration);
        assertTrue(lines.get(0).words.isEmpty());
    }

    // ── 时间戳 ──────────────────────────────────────────────────────────────

    @Test
    void vttTimestampsAcceptAllThreeShapes() {
        assertEquals(1500L, LyricParser.parseVttTimestamp("00:01.500"));
        assertEquals(1500L, LyricParser.parseVttTimestamp("01.500"));
        assertEquals(3_661_250L, LyricParser.parseVttTimestamp("01:01:01.250"));
        assertEquals(-1L, LyricParser.parseVttTimestamp("nope"));
        assertEquals(-1L, LyricParser.parseVttTimestamp(null));
    }

    @Test
    void awlrcFractionIsRawMillisecondsNotDecimalDigits() {
        assertEquals(1500L, LyricParser.parseAwlrcTimestamp("00:01", "500"));
        // 关键差异：小数段是"原样毫秒"，[00:01.5] 是 1005ms 而不是 1500ms。
        // 读成百分秒会把整类行整体前移最多 900 毫秒。
        assertEquals(1005L, LyricParser.parseAwlrcTimestamp("00:01", "5"));
        assertEquals(3_601_007L, LyricParser.parseAwlrcTimestamp("01:00:01", "7"));
    }

    // ── 精确键优先（P2#13） ─────────────────────────────────────────────────

    @Test
    void exactKeysWinAndDisableTheNearestNeighbourFallback() {
        List<LyricLine> main = new ArrayList<>();
        main.add(new LyricLine(1000L, "a"));
        main.add(new LyricLine(2000L, "b"));
        main.add(new LyricLine(3000L, "c"));

        // 侧车里有两条精确键（1000 / 3000），另有一条 2900 落在 2000 的 ±1s 邻域内。
        LyricParser.applyQrcSidecar("[00:01.000]A\n[00:03.000]C\n[00:02.900]X", main, true);

        assertEquals("A", main.get(0).translationText);
        assertEquals("C", main.get(2).translationText);
        // 只要存在精确键，就说明两条轨本来就是同一套时间轴；此时 2000 没有译文是真的没有，
        // 用最近邻把 2900 那条安到它头上就是"给这一行挂上了下一行的翻译"。
        assertNull(main.get(1).translationText);
    }

    @Test
    void nearestNeighbourStillRescuesAFullyShiftedSidecar() {
        List<LyricLine> main = new ArrayList<>();
        main.add(new LyricLine(1000L, "a"));

        // 一条精确键都命中不了 → 时间戳整体错位 → 最近邻是唯一可行的对齐方式。
        LyricParser.applyQrcSidecar("[00:01.400]A", main, true);

        assertEquals("A", main.get(0).translationText);
    }

    @Test
    void romanizationSideDoesNotOverwriteTranslation() {
        List<LyricLine> main = new ArrayList<>();
        LyricLine line = new LyricLine(1000L, "a");
        line.translationText = "已有翻译";
        main.add(line);

        LyricParser.applyQrcSidecar("[00:01.000]romaji", main, false);

        assertEquals("已有翻译", line.translationText);
        assertEquals("romaji", line.romanizationText);
    }
}
