package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词匹配的离线探针。纯 CPU 逻辑,不需要 GL、客户端实例或账号,每次改完都能立刻跑。
 *
 * <p>这一层最容易被无声改坏:归一化少一条规则只会让某类曲名悄悄搜不到,不会报任何错。所以这里把
 * 「哪些写法必须判为同一首」和「哪些写法必须判为不同版本」都钉成断言。</p>
 *
 * <p>测试放在同包下是为了能直接调用 package-private 的 {@link LyricMatchText} 与
 * {@link LyricMatchScore}。</p>
 */
class LyricMatchProbeTest {

    // ── 归一化 ─────────────────────────────────────────────────────────────

    @Test
    void traditionalTitlesNormalizeToTheSameFormAsSimplified() {
        assertEquals("爱", LyricMatchText.normalize("愛"));
        assertEquals("叶惠美", LyricMatchText.normalize("葉惠美"));
        // 港台区的繁体曲名/歌手必须能和简体写法对上，否则整类候选会被判 0 分拒掉。
        assertEquals(1.0, LyricMatchText.similarity("愛我別走", "爱我别走"));
        assertEquals(1.0, LyricMatchText.similarity("張學友", "张学友"));
        assertEquals(1.0, LyricMatchText.similarity("戀曲1990", "恋曲1990"));
    }

    @Test
    void kanaNormalizesToRomaji() {
        assertEquals("yorushika", LyricMatchText.normalize("ヨルシカ"));
        assertEquals("aimyon", LyricMatchText.normalize("あいみょん"));
        assertEquals(1.0, LyricMatchText.similarity("キセキ", "kiseki"));
        // 促音与长音符：ずっと → zutto、コーヒー → koohii
        assertEquals("zutto", LyricMatchText.normalize("ずっと"));
        assertEquals("koohii", LyricMatchText.normalize("コーヒー"));
        // 拗音必须整体吃掉两个字符，不能拆成 し + ゃ
        assertEquals("shabon", LyricMatchText.normalize("しゃぼん"));
    }

    @Test
    void normalizationStripsPunctuationSymbolsAndCase() {
        assertEquals("晴天", LyricMatchText.normalize("  晴天  "));
        assertEquals(1.0, LyricMatchText.similarity("Hello, World!", "hello world"));
        // 汉字不做罗马音转换（和 wanakana 一致），但日文汉字名里夹带的繁体字会被一并转简：
        // 師 → 师，所以「米津玄師」与「米津玄师」两种写法会归一化成同一个串，反而是跨平台命中的助力。
        assertEquals("米津玄师", LyricMatchText.normalize("米津玄師"));
        assertEquals(1.0, LyricMatchText.similarity("米津玄師", "米津玄师"));
    }

    // ── 标题版本标记 ───────────────────────────────────────────────────────

    @Test
    void titleNormalizationKeepsVersionMarkers() {
        // Live / Remix / 伴奏 是完全不同的时间轴，绝不能和原曲判成同一首。
        assertNotEquals(LyricMatchText.normalizeTitle("Song (Live ver.)"), LyricMatchText.normalizeTitle("Song"));
        assertTrue(LyricMatchText.similarity("Song (Live ver.)", "Song", true) < 0.65);
        assertTrue(LyricMatchText.similarity("Song (Remix)", "Song", true) < 0.65);
        assertTrue(LyricMatchText.similarity("晴天 (伴奏)", "晴天", true) < 0.65);
        assertTrue(LyricMatchText.similarity("Song (Instrumental)", "Song", true) < 0.65);
        // 但同为 Live 版时必须能互相命中。
        assertEquals(1.0, LyricMatchText.similarity("Song (Live ver.)", "Song (Live ver.)", true));
    }

    @Test
    void titleNormalizationDropsFeatAndDescriptiveBrackets() {
        assertEquals(1.0, LyricMatchText.similarity("晴天", "晴天", true));
        assertEquals(1.0, LyricMatchText.similarity("晴天 (feat. 五月天)", "晴天", true));
        assertEquals(1.0, LyricMatchText.similarity("晴天 ft. A", "晴天", true));
        // 「某某剧片尾曲」这类说明性括号对身份没有帮助，应当丢掉。
        assertEquals(1.0, LyricMatchText.similarity("晴天 (电视剧《xx》片尾曲)", "晴天", true));
    }

    // ── 歌手 ───────────────────────────────────────────────────────────────

    @Test
    void artistSplittingHandlesEverySeparator() {
        assertEquals(1, LyricMatchText.splitArtists("周杰伦").size());
        assertEquals(2, LyricMatchText.splitArtists("周杰伦 & 费玉清").size());
        assertEquals(2, LyricMatchText.splitArtists("周杰伦、费玉清").size());
        assertEquals(2, LyricMatchText.splitArtists("林俊杰;周杰伦").size());
        assertEquals(2, LyricMatchText.splitArtists("A feat. B").size());
        // featuring 必须先于 feat 匹配，否则会切出残留的 "uring"。
        List<String> featuring = LyricMatchText.splitArtists("A featuring B");
        assertEquals(2, featuring.size());
        assertFalse(featuring.contains("uring"));
    }

    @Test
    void artistSimilarityHandlesMainArtistAndCrossPlatformSpelling() {
        assertEquals(1.0, LyricMatchText.artistSimilarity("周杰伦", Arrays.asList("周杰倫")));
        // 本地没有歌手元数据时不能因此否决候选（Music 会用 "Unknown" 占位，调用方已过滤成空串）。
        assertEquals(1.0, LyricMatchText.artistSimilarity("", Arrays.asList("任何人")));
        // 多歌手写法顺序不同：命中主歌手就给 0.7 的加成。
        assertEquals(0.7, LyricMatchText.artistSimilarity("A feat. B", Arrays.asList("A")));
        // 完全对不上时必须低于打分门槛，否则会把别人的歌词贴上来。
        assertTrue(LyricMatchText.artistSimilarity("周杰伦", Arrays.asList("林俊杰")) < 0.5);
    }

    // ── 打分 ───────────────────────────────────────────────────────────────

    @Test
    void exactMatchScoresFullAndIsAccepted() {
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "周杰伦", "叶惠美", 269_000L),
                candidate("晴天", Arrays.asList("周杰伦"), "叶惠美", 269_000L));

        assertEquals(100, result.score);
        assertTrue(result.accepted());
    }

    @Test
    void durationGapRejectsDifferentEditionsOfTheSameSong() {
        LyricMatchScore.Target target = target("晴天", "周杰伦", "叶惠美", 269_000L);

        // 差 2s：前奏裁剪，仍然采用。
        assertTrue(LyricMatchScore.evaluate(target, candidate("晴天", Arrays.asList("周杰伦"), "叶惠美", 271_000L))
                .accepted());
        // 差 4s：正好落在 0.75 档，100 × 0.75 = 75，卡在门槛上。
        assertEquals(75, LyricMatchScore.evaluate(target, candidate("晴天", Arrays.asList("周杰伦"), "叶惠美", 273_000L))
                .score);
        // 差 20s：原曲 vs Remix / 加长版就是这个量级，必须拒绝，否则整行歌词的时间轴都会错位。
        assertFalse(LyricMatchScore.evaluate(target, candidate("晴天", Arrays.asList("周杰伦"), "叶惠美", 289_000L))
                .accepted());
    }

    @Test
    void liveEditionIsRejectedEvenThoughTheTitleLooksSimilar() {
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "周杰伦", "叶惠美", 269_000L),
                candidate("晴天 (Live)", Arrays.asList("周杰伦"), "叶惠美", 249_000L));

        assertFalse(result.titleMatched);
        assertFalse(result.accepted());
    }

    @Test
    void differentSongIsRejected() {
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "周杰伦", "叶惠美", 269_000L),
                candidate("稻香", Arrays.asList("周杰伦"), "魔杰座", 223_000L));

        assertFalse(result.titleMatched);
        assertFalse(result.accepted());
    }

    @Test
    void candidateWithoutAlbumOrDurationIsNotPenalised() {
        // AMLL 的搜索结果不带时长，专辑也是 Apple Music 的写法，两者都不该把正确候选压到门槛之下。
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "周杰伦", "叶惠美", 269_000L),
                candidate("晴天", Arrays.asList("周杰伦"), "", 0L));

        assertEquals(90, result.score);
        assertTrue(result.accepted());
    }

    @Test
    void mismatchedAlbumOnlyCostsHalfTheWeight() {
        // 跨平台的精选集/引进版专辑名对不上很常见，不该因此拒掉一首正确的歌。
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "周杰伦", "叶惠美", 269_000L),
                candidate("晴天", Arrays.asList("周杰伦"), "周杰伦精选集", 269_000L));

        assertEquals(85, result.score);
        assertTrue(result.accepted());
    }

    @Test
    void localSongWithoutArtistKeepsTheOldLenientBehaviour() {
        // 曲名对上、本地没有歌手信息时，旧实现是接受的；新模型必须保持这一点，不能变成更严的回归。
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "", "", 0L),
                candidate("晴天", Collections.<String>emptyList(), "", 0L));

        assertTrue(result.accepted());
    }

    @Test
    void wrongArtistAndWrongAlbumIsRejected() {
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "周杰伦", "叶惠美", 269_000L),
                candidate("晴天", Arrays.asList("林俊杰"), "她说", 269_000L));

        assertFalse(result.artistMatched);
        assertFalse(result.accepted());
    }

    @Test
    void traditionalChineseCandidateMatchesSimplifiedTarget() {
        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                target("晴天", "周杰伦", "叶惠美", 269_000L),
                candidate("晴天", Arrays.asList("周杰倫"), "葉惠美", 269_000L));

        assertEquals(100, result.score);
        assertTrue(result.accepted());
    }

    @Test
    void anyOfTheCandidateTitlesCanMatch() {
        // AMLL 的 musicNames 会同时收录正式名与「某某剧主题曲」这种说明，命中任意一个即可。
        LyricMatchScore.Candidate candidate = new LyricMatchScore.Candidate(
                Arrays.asList("Lemon", "日剧《非自然死亡》主题曲"),
                Arrays.asList("米津玄師"), "Lemon", 0L);

        LyricMatchScore.Result result = LyricMatchScore.evaluate(
                new LyricMatchScore.Target("Lemon", "米津玄師", "Lemon", 255_000L), candidate);

        assertTrue(result.accepted());
    }

    // ── 检索词 ─────────────────────────────────────────────────────────────

    @Test
    void structuredQueryUsesTitleArtistAlbum() {
        assertEquals("晴天 - 周杰伦 - 叶惠美",
                LyricMatchText.buildStructuredQuery("晴天", "周杰伦", "叶惠美"));
        assertEquals("晴天 - 周杰伦", LyricMatchText.buildStructuredQuery("晴天", "周杰伦", ""));
        assertEquals("晴天", LyricMatchText.buildStructuredQuery("晴天", "", ""));
        // 曲名里已经带了「歌手 - 」前缀时不要再写一遍歌手。
        assertEquals("晴天 - 周杰伦",
                LyricMatchText.buildStructuredQuery("周杰伦 - 晴天", "周杰伦", ""));
    }

    @Test
    void bracketDropIsOnlyForTheSearchFallback() {
        assertEquals("晴天", LyricMatchText.dropBrackets("晴天 (Live ver.)"));
        // 注意这是「放宽检索词」用的，匹配判定必须走 normalizeTitle，两者不能混用。
        assertTrue(LyricMatchText.similarity("晴天 (Live ver.)", "晴天", true) < 0.65);
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    private static LyricMatchScore.Target target(String title, String artist, String album, long durationMillis) {
        return new LyricMatchScore.Target(title, artist, album, durationMillis);
    }

    private static LyricMatchScore.Candidate candidate(String title, List<String> artists, String album,
                                                       long durationMillis) {
        return LyricMatchScore.Candidate.of(title, artists, album, durationMillis);
    }
}
