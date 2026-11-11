package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外文 / 翻译热路径的离线回归：只验证缓存与布局，不启动 Minecraft 或 Skija。
 */
class WorldLyricsPerformanceRegressionTest {

    @Test
    @DisplayName("渲染逐字 scratch 复用对象但逐帧裁尾并重置颜色与姿态")
    void renderGlyphScratchReusesObjectsWithoutLeakingPreviousFrameState() {
        WorldLyricsState.SpawnedLine longLine = new WorldLyricsState.SpawnedLine(
                List.of("Hello world"), List.of("你好世界"), timesFor("你好世界"), false,
                0L, 1000L, -1, 0, 0, 0, 0, 0, timesFor("Hello world"));
        WorldLyricsState.PixelWidthOf width = WorldLyricsPerformanceRegressionTest::rawWidth;
        int[] firstGradient = {0xAA3311};
        WorldLyricsState.LyricGlyphs first = WorldLyricsState.buildGlyphsForRender(
                longLine, 5000L, 1f, false, false, width,
                false, false, null, firstGradient);
        WorldLyricsState.LyricGlyph firstGlyph = first.glyphs.get(0);
        assertEquals(0xAA3311, firstGlyph.rgbOverride);
        assertEquals(first.glyphs.size(), first.appearAtSize());

        WorldLyricsState.SpawnedLine shortLine = new WorldLyricsState.SpawnedLine(
                List.of("Go"), 0L, 1000L, 0, 0, 0, 0, 0, timesFor("Go"));
        WorldLyricsState.LyricGlyphs second = WorldLyricsState.buildGlyphsForRender(
                shortLine, 5000L, 1f, false, false, width,
                false, false, null, null);
        assertSame(first, second, "渲染 scratch 应跨行复用");
        assertSame(firstGlyph, second.glyphs.get(0), "可见字形对象应复用");
        assertEquals("Go".length(), second.glyphs.size(), "长句翻译的尾部不得残留");
        assertEquals(0, second.glyphs.get(0).rgbOverride, "上一帧渐变颜色不得残留");
        assertEquals(0f, second.glyphs.get(0).rot, 0f, "上一帧姿态不得残留");
        assertEquals(second.glyphs.size(), second.appearAtSize(), "时刻数组与字形必须一一对应");
    }

    @Test
    @DisplayName("绘光翻译微飞复用 Flight 并更新当前字形坐标")
    void renderTranslationFlightsReuseObjectsAndRefreshCoordinates() {
        WorldLyricsState.PixelWidthOf width = WorldLyricsPerformanceRegressionTest::rawWidth;
        WorldLyricsState.SpawnedLine firstLine = new WorldLyricsState.SpawnedLine(
                List.of("Hello"), List.of("A"), timesFor("A"), true,
                0L, 1000L, -1, 0, 0, 0, 0, 0, timesFor("Hello"));
        WorldLyricsState.LyricGlyphs first = WorldLyricsState.buildGlyphsForRender(
                firstLine, 5000L, 1f, false, false, width,
                false, true, null, null);
        HuiguangMotion.Flight firstFlight = first.huiguangFlights.get(5);
        assertEquals(-2.5f, firstFlight.fromX, 0f);

        WorldLyricsState.SpawnedLine secondLine = new WorldLyricsState.SpawnedLine(
                List.of("Go"), List.of("AB"), timesFor("AB"), true,
                0L, 1000L, -1, 0, 0, 0, 0, 0, timesFor("Go"));
        WorldLyricsState.LyricGlyphs second = WorldLyricsState.buildGlyphsForRender(
                secondLine, 5000L, 1f, false, false, width,
                false, true, null, null);
        HuiguangMotion.Flight secondFlight = second.huiguangFlights.get(2);
        assertSame(firstFlight, secondFlight, "翻译微飞应复用 scratch Flight");
        assertEquals(-5f, secondFlight.fromX, 0f, "复用 Flight 必须重写当前字形坐标");
        assertEquals(second.glyphs.size(), second.huiguangFlights.size(),
                "每个可见字形仍须与 Flight/null 占位一一对齐");
    }

    @Test
    @DisplayName("多语言字符宽度按字体世代复用，owner 或 generation 变化时完整失效")
    void textMetricsCacheSeparatesLanguagesAndInvalidates() {
        AtomicInteger calls = new AtomicInteger();
        WorldLyricsState.PixelWidthOf source = text -> {
            calls.incrementAndGet();
            return rawWidth(text);
        };
        WorldLyricsState.SpawnedLine line = new WorldLyricsState.SpawnedLine(
                List.of("Aあ한中"), 0L, 1000L, 0, 0, 0, 0, 0, new long[0]);
        Object owner = new Object();

        WorldLyricsState.PixelWidthOf cached = WorldLyricsState.cachedTextMetrics(line, source, owner, 7L);
        for (char c : "Aあ한中".toCharArray()) {
            assertTrue(cached.charWidth(c) > 0f, "字符必须能取得有效 advance: " + c);
        }
        int afterFirstPass = calls.get();
        for (char c : "Aあ한中".toCharArray()) {
            cached.charWidth(c);
        }
        assertEquals(afterFirstPass, calls.get(), "拉丁、假名、韩文、中文不得在同一字体世代重复量宽");
        assertSame(cached, WorldLyricsState.cachedTextMetrics(line, source, owner, 7L));

        Object newOwner = new Object();
        WorldLyricsState.PixelWidthOf ownerChanged =
                WorldLyricsState.cachedTextMetrics(line, source, newOwner, 7L);
        assertNotSame(cached, ownerChanged, "字体宿主更换后不能复用旧 advance");
        ownerChanged.charWidth('A');
        int afterOwnerChanged = calls.get();
        assertTrue(afterOwnerChanged > afterFirstPass);

        WorldLyricsState.PixelWidthOf generationChanged =
                WorldLyricsState.cachedTextMetrics(line, source, newOwner, 8L);
        assertNotSame(ownerChanged, generationChanged, "字体世代变化后不能复用旧 advance");
        generationChanged.charWidth('あ');
        assertTrue(calls.get() > afterOwnerChanged);
    }

    @Test
    @DisplayName("绘光单遍排版与原双遍回填在中日韩英混排下坐标逐位一致")
    void huiguangLayoutKeepsTheOldCoordinates() {
        List<String> lines = List.of("Hello 世界", "こんにちは 한국어");
        WorldLyricsState.PixelWidthOf width = WorldLyricsPerformanceRegressionTest::rawWidth;
        float[] expected = oldTwoPassLayout(lines, width, 91);
        float[] actual = WorldLyricsState.huiguangLayoutChars(lines, width, 91);
        assertArrayEquals(expected, actual, 0f,
                "单遍排版只能消除重复遍历，不能改变外文/翻译混排的坐标");
    }

    @Test
    @DisplayName("主行与翻译副行保留拉丁、假名、韩文、中文的逐字顺序")
    void multilingualTranslationGlyphsKeepEveryCharacter() {
        String[][] cases = {
                {"Hello world", "你好世界"},
                {"こんにちは 世界", "Hello world"},
                {"안녕하세요", "这是翻译"},
                {"Mixed 混合", "번역 translation"}
        };
        for (String[] pair : cases) {
            String main = pair[0];
            String translation = pair[1];
            long[] mainTimes = timesFor(main);
            long[] translationTimes = timesFor(translation);
            WorldLyricsState.SpawnedLine line = new WorldLyricsState.SpawnedLine(
                    List.of(main), List.of(translation), translationTimes, false,
                    0L, 1000L, -1, 0, 0, 0, 0, 0, mainTimes);

            WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                    line, 5000L, 1f, false, false,
                    WorldLyricsPerformanceRegressionTest::rawWidth,
                    false, false, null, null);
            String actual = glyphs.glyphs.stream()
                    .map(g -> String.valueOf(g.c))
                    .reduce("", String::concat);
            assertEquals(nonWhitespace(main) + nonWhitespace(translation), actual,
                    "翻译副行不能因脚本或缓存路径漏字: " + main + " / " + translation);
        }
    }

    private static long[] timesFor(String text) {
        int count = nonWhitespace(text).length();
        long[] times = new long[count];
        for (int i = 0; i < count; i++) times[i] = i * 100L;
        return times;
    }

    private static String nonWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) out.append(text.charAt(i));
        }
        return out.toString();
    }

    private static float rawWidth(String text) {
        float total = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') total += 4f;
            else if (c < 0x80) total += 5f;
            else if (c >= 0xAC00 && c <= 0xD7AF) total += 10f;
            else if (c >= 0x3040 && c <= 0x30FF) total += 9f;
            else total += 11f;
        }
        return total;
    }

    /**
     * 保留改动前的双遍算法作为测试金标准：第一遍写姿态，第二遍只回填每行的 x。
     */
    private static float[] oldTwoPassLayout(List<String> lines,
                                             WorldLyricsState.PixelWidthOf widthOf,
                                             int seed) {
        int count = 0;
        for (String line : lines) {
            for (int i = 0; i < line.length(); i++) {
                if (!Character.isWhitespace(line.charAt(i))) count++;
            }
        }
        float[] out = new float[Math.max(1, count) * 4];
        float rowPitch = WorldLyricsState.LYRIC_SIZE * 1.3f;
        int i4 = 0;
        float y = -(lines.size() * rowPitch) / 2f;
        for (String line : lines) {
            List<String> words = WorldLyricsState.splitWords(line);
            float[] wordScale = wordScales(words, seed, line);
            float x = 0f;
            for (int w = 0; w < words.size(); w++) {
                String word = words.get(w);
                float scale = wordScale[w];
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    if (Character.isWhitespace(c)) continue;
                    float adv = widthOf.widthOf(String.valueOf(c)) * scale;
                    out[i4++] = x - widthOf.widthOf(line) * 0f;
                    float jagAmp = scale >= 1.45f - 0.01f ? 0f
                            : (scale <= 0.62f + 0.01f ? 0.32f : 0.2f);
                    float jag = WorldLyricsState.seededJitter(i4 - 1, 0L, 83,
                            seed + line.hashCode()) * jagAmp * WorldLyricsState.LYRIC_SIZE;
                    out[i4++] = y + (1f - scale) * WorldLyricsState.LYRIC_SIZE * 0.32f + jag;
                    out[i4++] = 0f;
                    out[i4++] = scale;
                    x += adv;
                }
                if (w < words.size() - 1) x += widthOf.widthOf(" ") * 0.6f * scale;
            }
            y += rowPitch;
        }

        i4 = 0;
        for (String line : lines) {
            List<String> words = WorldLyricsState.splitWords(line);
            float[] wordScale = wordScales(words, seed, line);
            float lineW = 0f;
            for (int w = 0; w < words.size(); w++) {
                String word = words.get(w);
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    if (!Character.isWhitespace(c)) {
                        lineW += widthOf.widthOf(String.valueOf(c)) * wordScale[w];
                    }
                }
                if (w < words.size() - 1) lineW += widthOf.widthOf(" ") * 0.6f * wordScale[w];
            }
            float x = -lineW / 2f;
            for (int w = 0; w < words.size(); w++) {
                String word = words.get(w);
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    if (Character.isWhitespace(c)) continue;
                    out[i4 * 4] = x + 0f;
                    x += widthOf.widthOf(String.valueOf(c)) * out[i4 * 4 + 3];
                    i4++;
                }
                if (w < words.size() - 1) x += widthOf.widthOf(" ") * 0.6f * wordScale[w];
            }
        }
        return out;
    }

    private static float[] wordScales(List<String> words, int seed, String line) {
        float[] scales = new float[words.size()];
        int hero = -1;
        int heroLen = 1;
        for (int w = 0; w < words.size(); w++) {
            String word = words.get(w);
            boolean particle = word.length() == 1
                    && ("的了着吗呢吧啊呀哦嘛么之乎者也把被和与或是".indexOf(word.charAt(0)) >= 0
                    || isPunctuation(word.charAt(0)));
            if (particle) {
                scales[w] = 0.62f;
            } else {
                scales[w] = 0.85f
                        + (WorldLyricsState.seededJitter(w, 0L, 81, seed + line.hashCode()) * 0.5f + 0.5f)
                        * 0.35f;
                if (word.length() > heroLen) {
                    heroLen = word.length();
                    hero = w;
                }
            }
        }
        if (hero >= 0) scales[hero] = 1.45f;
        return scales;
    }

    private static boolean isPunctuation(char c) {
        return (c >= 0x2000 && c <= 0x206F) || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFF65)
                || "!?,.;:()[]{}<>-—…\"'‘’“”".indexOf(c) >= 0;
    }
}
