package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * update/spawn 依赖 CloudMusic 全局态与相机，这里测可独立断言的几何与文字工具。
 */
class WorldLyricsMathTest {

    // ==================== 视线方向（spawn 定点用） ====================

    @Test
    void yawZeroLooksAlongPlusZ() {
        double[] dir = WorldLyricsState.yawPitchDirectionStatic(0.0, 0.0);
        assertEquals(0.0, dir[0], 1e-9);
        assertEquals(0.0, dir[1], 1e-9);
        assertEquals(1.0, dir[2], 1e-9);
    }

    @Test
    void yawNinetyLooksAlongMinusX() {
        // MC 口径：yaw 增加向左转；yaw=90 朝 -X
        double[] dir = WorldLyricsState.yawPitchDirectionStatic(90.0, 0.0);
        assertEquals(-1.0, dir[0], 1e-9);
        assertEquals(0.0, dir[2], 1e-9);
    }

    @Test
    void pitchDownLooksDown() {
        // MC 口径：pitch 增加向下看
        double[] dir = WorldLyricsState.yawPitchDirectionStatic(0.0, 90.0);
        assertEquals(-1.0, dir[1], 1e-9);
    }

    @Test
    void directionIsUnitLength() {
        double[] dir = WorldLyricsState.yawPitchDirectionStatic(37.0, -12.5);
        double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        assertEquals(1.0, len, 1e-9);
    }


    @Test
    void hashRandIsDeterministicInRange() {
        float a = WorldLyricsState.hashRand(42, 1000L, 1, 7);
        float b = WorldLyricsState.hashRand(42, 1000L, 1, 7);
        assertEquals(a, b, 1e-7f, "同一帧同一字必须取同一个值（发光层与本体一致）");
        assertTrue(a >= -1f && a <= 1f);
    }

    @Test
    void hashRandDiffersPerCharacterAndFrame() {
        assertTrue(WorldLyricsState.hashRand(0, 1L, 1, 7) != WorldLyricsState.hashRand(1, 1L, 1, 7));
        assertTrue(WorldLyricsState.hashRand(0, 1L, 1, 7) != WorldLyricsState.hashRand(0, 2L, 1, 7));
    }

    @Test
    void wiggleFrameQuantizesToLowFps() {
        // fps=8：0..125ms 内是同一帧
        long f0 = WorldLyricsState.wiggleFrame(0L, 8.0);
        long f1 = WorldLyricsState.wiggleFrame(124L, 8.0);
        long f2 = WorldLyricsState.wiggleFrame(126L, 8.0);
        assertEquals(f0, f1);
        assertTrue(f2 > f1);
        assertEquals(0L, WorldLyricsState.wiggleFrame(0L, 8.0));
    }

    @Test
    void wiggleAmountsScaleWithAmount() {
        WorldLyricsState.LyricGlyph g = new WorldLyricsState.LyricGlyph('字', 0f, 0f, 1f, 3, 1f);
        float[] half = WorldLyricsState.wiggleOf(g, 7, 100L, 0.5);
        float[] full = WorldLyricsState.wiggleOf(g, 7, 100L, 1.0);
        // 同一相位下，幅度 1.0 的偏移必须是 0.5 的两倍
        assertEquals(half[0] * 2f, full[0], 1e-5f);
        assertEquals(half[2] * 2f, full[2], 1e-5f);
    }


    @Test
    void splitWordsGroupsCjkInPairs() {
        List<String> words = WorldLyricsState.splitWords("岁月变迁是这世界定的规矩");
        // 12 个字 → 6 组，每组 2 字
        assertEquals(6, words.size());
        assertEquals("岁月", words.get(0));
        assertEquals("规矩", words.get(5));
    }

    @Test
    void splitWordsKeepsEnglishWordsWhole() {
        List<String> words = WorldLyricsState.splitWords("Don't stop the music");
        assertEquals(4, words.size());
        assertEquals("Don't", words.get(0));
        assertEquals("music", words.get(3));
    }

    @Test
    void splitWordsSplitsMixedScript() {
        // 英文后面跟中文：分开
        List<String> words = WorldLyricsState.splitWords("hello世界");
        assertEquals(2, words.size());
        assertEquals("hello", words.get(0));
        assertEquals("世界", words.get(1));
    }

    @Test
    void wrapBreaksLongLineAtEstimatedWidth() {
        // 60 个中文字 × 6px = 360px > 200 → 至少拆两行
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 60; i++) big.append('字');
        List<String> lines = WorldLyricsState.wrap(big.toString(), 200f);
        assertTrue(lines.size() >= 2, "长行应换行，实际 " + lines.size() + " 行");
        // 拼回去内容不丢（trim 后）
        StringBuilder joined = new StringBuilder();
        for (String s : lines) joined.append(s);
        assertEquals(big.toString(), joined.toString());
    }

    @Test
    void wrapKeepsShortLineWhole() {
        List<String> lines = WorldLyricsState.wrap("短句", 200f);
        assertEquals(1, lines.size());
        assertEquals("短句", lines.get(0));
    }


    @Test
    void charTimesWithoutWordTimingAveragesAcrossWords() {
        com.muoniumplayer.core.screens.ncm.LyricLine line =
                new com.muoniumplayer.core.screens.ncm.LyricLine(1000L, "岁月变迁");
        long[] times = WorldLyricsState.charTimesOf(line, 3000L);
        // 4 个字 → 4 个时间点（2 词 × 每词 2 字）
        assertEquals(4, times.length);
        // span = min(dur*0.75, 词数*330) = min(1500, 660) = 660ms → 两词时间 [0, 330]
        assertEquals(0L, times[0]);
        assertEquals(0L, times[1]);
        assertEquals(330L, times[2]);
        assertEquals(330L, times[3]);
    }

    @Test
    void charTimesWithWordTimingUsesRealTimes() {
        com.muoniumplayer.core.screens.ncm.LyricLine line =
                new com.muoniumplayer.core.screens.ncm.LyricLine(1000L, "你好");
        line.words.add(new com.muoniumplayer.core.screens.ncm.LyricLine.Word("你", 1200L, 300L));
        line.words.add(new com.muoniumplayer.core.screens.ncm.LyricLine.Word("好", 1500L, 400L));
        long[] times = WorldLyricsState.charTimesOf(line, 3000L);
        assertEquals(2, times.length);
        // 相对句起始：1200-1000=200、1500-1000=500
        assertEquals(200L, times[0]);
        assertEquals(500L, times[1]);
    }


    @Test
    void buildGlyphsRevealsCharactersProgressively() {
        WorldLyricsState.Floating f = new WorldLyricsState.Floating(
                List.of("你好"), 0L, 1000L, 0, 0, 0, 0, 0, new long[0]);
        WorldLyricsState.PixelWidthOf width = s -> 12f;
        // 句子刚开始：还没到第一个字的出现点，0 个字形
        WorldLyricsState.LyricGlyphs early =
                WorldLyricsState.buildGlyphs(f, 0L, 1f, false, width);
        assertTrue(early.glyphs.isEmpty());
        // 句尾：全部出现
        WorldLyricsState.LyricGlyphs late =
                WorldLyricsState.buildGlyphs(f, 1500L, 1f, false, width);
        assertEquals(2, late.glyphs.size());
        assertEquals('你', late.glyphs.get(0).c);
        assertEquals('好', late.glyphs.get(1).c);
        // 逐字淡入中：先出现的字 alpha 更高
        WorldLyricsState.LyricGlyphs mid =
                WorldLyricsState.buildGlyphs(f, 650L, 1f, false, width);
        if (mid.glyphs.size() == 2) {
            assertTrue(mid.glyphs.get(0).alpha > mid.glyphs.get(1).alpha,
                    "先唱到的字应更亮");
        }
    }

    @Test
    void buildGlyphsWordPopScalesNewlySungWord() {
        WorldLyricsState.Floating f = new WorldLyricsState.Floating(
                List.of("你好"), 0L, 1000L, 0, 0, 0, 0, 0,
                new long[] {0L, 500L});
        WorldLyricsState.PixelWidthOf width = s -> 12f;
        // word pop 开：第一字刚唱到（elapsed=80ms，t=0.5）应带 pop>1
        WorldLyricsState.LyricGlyphs g =
                WorldLyricsState.buildGlyphs(f, 80L, 1f, true, width);
        assertTrue(g.glyphs.size() >= 1);
        assertTrue(g.glyphs.get(0).pop > 1f, "刚唱到的词应放大弹出，实际 " + g.glyphs.get(0).pop);
        // 还没唱到的第二个字不出现
        assertTrue(g.glyphs.size() == 1);
    }


    @Test
    void textLayoutConstantsMatch() {
        assertEquals(0xF7E1D7, WorldLyricsState.TEXT_RGB, "暖白偏粉的字色");
        assertEquals(10f, WorldLyricsState.LYRIC_SIZE, 1e-6f);
        assertEquals(11f, WorldLyricsState.LINE_H, 1e-6f);
        assertEquals(0.4f, WorldLyricsState.FADE_SECONDS, 1e-6f);
        assertEquals(0.6, WorldLyricsState.KEEP_AWAY_MIN_RATIO, 1e-6);
        assertEquals(0.03f, WorldLyricsState.PX_TO_WORLD, 1e-6f);
        assertEquals(6.5f, WorldLyricsState.DISTANCE_NORM, 1e-6f);
    }
}
