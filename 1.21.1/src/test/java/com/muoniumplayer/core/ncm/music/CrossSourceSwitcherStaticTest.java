package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.lxsource.LxSourceService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨平台换源的纯静态判据：打分、时长门槛、候选淘汰、排名与候选平台表。
 *
 * <p>这里只碰不依赖 Minecraft 运行期的纯函数 —— 流水线本体（搜索 / 验证 / 提前返回）
 * 全部要联网，留给真机。</p>
 */
public class CrossSourceSwitcherStaticTest {

    private static final double EPS = 1e-9;

    // ── 归一化与相似度 ──────────────────────────────────────────────────────

    @Test
    public void normalizeTextKeepsLettersDigitsAndDropsPunctuation() {
        assertEquals("夜曲", CrossSourceSwitcher.normalizeText("夜 曲"));
        assertEquals("yellowlive", CrossSourceSwitcher.normalizeText("Yellow (Live)"));
        assertEquals("featabc", CrossSourceSwitcher.normalizeText("feat.-A/B~C"));
        assertEquals("", CrossSourceSwitcher.normalizeText(null));
        assertEquals("", CrossSourceSwitcher.normalizeText("!!! ???"));
    }

    @Test
    public void similarityScoreIsLevenshteinRatioCappedAtOne() {
        assertEquals(1.0, CrossSourceSwitcher.similarityScore("晴天", "晴天"), EPS);
        // "yellow" vs "yellox"：距离 1，长度 6 → 5/6。括号等噪音已在归一化里剃掉。
        assertEquals(5.0 / 6.0, CrossSourceSwitcher.similarityScore("Yellow", "yellox"), EPS);
        assertEquals(0.0, CrossSourceSwitcher.similarityScore("abc", ""), EPS);
        // 编辑距离不小于较长串时记 0，不给负数留口子。
        assertEquals(0.0, CrossSourceSwitcher.similarityScore("aaaaa", "bbbbb"), EPS);
    }

    @Test
    public void songSimilarityWeightsNameOverArtistSevenThree() {
        // 曲名完全一致（1.0）、艺人不一致（0.0）→ 0.7
        assertEquals(0.7, CrossSourceSwitcher.songSimilarity("夜曲", "周杰伦", "夜曲", "林俊杰"), EPS);
        assertEquals(1.0, CrossSourceSwitcher.songSimilarity("夜曲", "", "夜曲", "周杰伦"), EPS);
        assertEquals(1.0, CrossSourceSwitcher.songSimilarity("夜曲", "周杰伦", "夜曲", ""), EPS);
        // 曲名彻底不像时艺人再像也是 0。
        assertEquals(0.0, CrossSourceSwitcher.songSimilarity("夜曲", "周杰伦", "听妈妈的话", "周杰伦"), EPS);
        // 双全一致 = 1.0，这是高置信提前返回的入口。
        assertEquals(1.0, CrossSourceSwitcher.songSimilarity("夜曲", "周杰伦", "夜曲", "周杰伦"), EPS);
    }

    // ── 时长门槛 ────────────────────────────────────────────────────────────

    @Test
    public void durationCloseAcceptsTenSecondsOrFifteenPercent() {
        long fourMin = 240_000L;
        // 4 分钟的歌：15% = 36 秒 > 10 秒下限，35 秒差 → 放行。
        assertTrue(CrossSourceSwitcher.isDurationClose(fourMin, fourMin + 35_000L));
        // 3 分钟的歌：15% = 27 秒 > 10 秒下限，26 秒差 → 放行。
        long threeMin = 180_000L;
        assertTrue(CrossSourceSwitcher.isDurationClose(threeMin, threeMin - 26_000L));
        // 短歌走绝对值下限：1 分钟的歌 15% 只有 9 秒，取 10 秒下限，11 秒差 → 拒绝；
        // 2 分钟的歌 15% = 18 秒，11 秒差 → 放行。
        assertFalse(CrossSourceSwitcher.isDurationClose(60_000L, 71_000L));
        assertTrue(CrossSourceSwitcher.isDurationClose(120_000L, 131_000L));
        // 任何一侧时长未知：闸门直接放行，交给打分线兜底。
        assertTrue(CrossSourceSwitcher.isDurationClose(0L, 9_999_999L));
        assertTrue(CrossSourceSwitcher.isDurationClose(9_999_999L, 0L));
    }

    // ── 候选录取（acceptanceScore 是唯一判据）──────────────────────────────

    @Test
    public void acceptanceRejectsDurationMismatchEvenAtPerfectSimilarity() {
        // 同名同艺人但时长差 90 秒 → 时长闸门淘汰，防「同名翻唱/现场版」。
        assertEquals(0.0, CrossSourceSwitcher.acceptanceScore(
                "夜曲", "周杰伦", 200_000L, "夜曲", "周杰伦", 290_000L), EPS);
    }

    @Test
    public void acceptanceRequiresHighScoreWhenDurationUnknown() {
        // GD 搜索结果没有时长：曲名一致艺人一致（score 1.0）→ 收。
        assertTrue(CrossSourceSwitcher.acceptanceScore(
                "夜曲", "周杰伦", 200_000L, "夜曲", "周杰伦", 0L) >= 0.85);
        // 时长未知且相似度不到 0.85 → 拒（防错配，宁可错杀）。
        assertEquals(0.0, CrossSourceSwitcher.acceptanceScore(
                "听妈妈的话", "周杰伦", 200_000L, "听妈妈的話(钢琴版)", "周杰伦", 0L), EPS);
    }

    @Test
    public void acceptanceKeepsMatchWhoseDifferenceIsPunctuationOnly() {
        double score = CrossSourceSwitcher.acceptanceScore(
                "夜曲（原版）", "周杰伦", 200_000L, "夜曲(原版)", "周杰倫", 205_000L);
        // 全角/半角括号与空白在归一化里被剃掉 → 曲名等价；时长差 5 秒在门槛内。
        // （注意：「(Live)」这类带字母的尾巴剃不掉符号但留着字母，会实打实拉低分 —— 与上游一致。）
        assertTrue(score > 0.0);
    }

    // ── 排名与高置信 ────────────────────────────────────────────────────────

    @Test
    public void candidatesSortByScoreThenByDurationDelta() {
        List<CrossSourceSwitcher.Candidate> candidates = new ArrayList<CrossSourceSwitcher.Candidate>();
        candidates.add(newTestCandidate(0.90, 4_000L));
        candidates.add(newTestCandidate(0.95, 9_000L));
        candidates.add(newTestCandidate(0.90, 1_000L));
        CrossSourceSwitcher.sortCandidates(candidates);
        assertEquals(0.95, candidates.get(0).score, EPS);
        assertEquals(1_000L, candidates.get(1).durationDeltaMillis);
        assertEquals(4_000L, candidates.get(2).durationDeltaMillis);
    }

    @Test
    public void highConfidenceNeedsScoreAndDurationBoth() {
        // 相似度 0.99 但时长差 4 秒 → 不算高置信，必须等全部源汇合。
        assertFalse(CrossSourceSwitcher.isHighConfidence(
                newTestCandidate(0.99, 4_000L, 204_000L), 200_000L));
        assertTrue(CrossSourceSwitcher.isHighConfidence(
                newTestCandidate(0.99, 2_000L, 202_000L), 200_000L));
        assertFalse(CrossSourceSwitcher.isHighConfidence(
                newTestCandidate(0.97, 0L, 200_000L), 200_000L));
        // 替身自己没有时长信息时只卡相似度线（时长未知的一侧由 0.85 打分线保护）。
        assertTrue(CrossSourceSwitcher.isHighConfidence(
                newTestCandidate(0.98, 0L, 0L), 200_000L));
    }

    // ── 候选平台表 ──────────────────────────────────────────────────────────

    @Test
    public void candidatePlatformsExcludeCurrentAndForbiddenSources() {
        // 会员闸门在这里全开 —— 本用例只钉「优先级 + 当前平台 + 禁入源」的选表逻辑，
        // 真实闸门要读各平台登录态，留给真机。
        Predicate<MusicPlatform> allOpen = platform -> true;
        List<MusicPlatform> fromNetease = CrossSourceSwitcher.candidatePlatforms(MusicPlatform.NETEASE, allOpen);
        assertFalse(fromNetease.contains(MusicPlatform.NETEASE));
        assertTrue(fromNetease.contains(MusicPlatform.QQ));
        assertTrue(fromNetease.contains(MusicPlatform.KUGOU));
        assertTrue(fromNetease.contains(MusicPlatform.GD));
        assertTrue(fromNetease.contains(MusicPlatform.BILIBILI));
        // 汽水在表内但垫底（上游对完整版有风控门）；YouTube 吃代理，不进候选。
        assertTrue(fromNetease.contains(MusicPlatform.QISHUI));
        assertFalse(fromNetease.contains(MusicPlatform.YOUTUBE));
        // 酷我 / 咪咕也在表里（自定义源渠道），位次紧跟酷狗；这里闸门全开所以必在。
        assertTrue(fromNetease.contains(MusicPlatform.KUWO));
        assertTrue(fromNetease.contains(MusicPlatform.MIGU));

        // 当前就在候选之外的平台上（YouTube）时，整张候选表都可用。
        assertEquals(7, CrossSourceSwitcher.candidatePlatforms(MusicPlatform.QISHUI, allOpen).size());
        assertEquals(8, CrossSourceSwitcher.candidatePlatforms(MusicPlatform.YOUTUBE, allOpen).size());
        // 优先级：网易云 > QQ > 酷狗 > 酷我 > 咪咕 > GD > B 站 > 汽水。LX 不是行候选而是通道：
        // 它覆盖的平台坐在各自位次上，天然排在 B 站前面（见 lxCovers 用例）。
        assertEquals(7, fromNetease.size());
        assertEquals(MusicPlatform.KUWO, fromNetease.get(2));
        assertEquals(MusicPlatform.MIGU, fromNetease.get(3));
        assertEquals(MusicPlatform.QISHUI, fromNetease.get(6));
    }

    @Test
    public void lxOnlyChannelsSurviveOnlyTheLxGate() {
        // 酷我 / 咪咕没有本家会员可查、也没有本家取流：唯一让它们进候选的闸门是
        // lxCovers（脚本声明了 kw/mg）。闸门替身只放行这两家时，它们的位次
        // 精确排在酷狗之后、GD 之前 —— 渠道内互备的优先级就长这样。
        Predicate<MusicPlatform> lxChannels = platform ->
                platform == MusicPlatform.KUWO || platform == MusicPlatform.MIGU;
        assertEquals(Arrays.asList(MusicPlatform.KUWO, MusicPlatform.MIGU),
                CrossSourceSwitcher.candidatePlatforms(MusicPlatform.KUGOU, lxChannels));
        // 当前平台自己也被剔：从酷我出发只剩咪咕。
        assertEquals(Collections.singletonList(MusicPlatform.MIGU),
                CrossSourceSwitcher.candidatePlatforms(MusicPlatform.KUWO, lxChannels));
        // 无头环境（LX 未启用）里真闸门对 kw/mg 恒关 —— 它们不进任何候选。
        if (!LxSourceService.isEnabled()) {
            assertFalse(CrossSourceSwitcher.hasVipAccess(MusicPlatform.KUWO));
            assertFalse(CrossSourceSwitcher.hasVipAccess(MusicPlatform.MIGU));
        }
    }

    @Test
    public void membershipGatedSourcesDropOutWithoutVip() {
        // 「哪个平台有会员」是查登录态的判据，这里用注入的闸门替身：只有 GD/B 站有权限。
        // 会员闸门只作用在选表这一步 —— 网易云/QQ/酷狗全灭时，候选表精确剩下免费的两家。
        Predicate<MusicPlatform> onlyFree = platform ->
                platform == MusicPlatform.GD || platform == MusicPlatform.BILIBILI;
        List<MusicPlatform> fromQQ = CrossSourceSwitcher.candidatePlatforms(MusicPlatform.QQ, onlyFree);
        assertEquals(Arrays.asList(MusicPlatform.GD, MusicPlatform.BILIBILI), fromQQ);

        // 免费平台永不受闸门影响：hasVipAccess 对 GD / B 站恒为 true（不查任何登录态）。
        assertTrue(CrossSourceSwitcher.hasVipAccess(MusicPlatform.GD));
        assertTrue(CrossSourceSwitcher.hasVipAccess(MusicPlatform.BILIBILI));

        // 唯一的免费候选就是当前平台时，它自己被剔除 —— 空表让自动触发直接不起跑。
        Predicate<MusicPlatform> onlyGd = platform -> platform == MusicPlatform.GD;
        assertEquals(Collections.singletonList(MusicPlatform.GD),
                CrossSourceSwitcher.candidatePlatforms(MusicPlatform.BILIBILI, onlyGd));
        assertTrue(CrossSourceSwitcher.candidatePlatforms(MusicPlatform.GD, onlyGd).isEmpty());
    }

    @Test
    public void lxChannelMappingDecidesWhichPlatformsItCovers() {
        // lxCovers 的骨架是 sourceKeyFor 的正反两次映射（无网络、无登录态）：
        // 只有能「平台→LX key→平台」来回的源，脚本解出来的曲目才拼得回可播的 Music。
        assertEquals("wy", LxSourceService.sourceKeyFor("NETEASE", ""));
        assertEquals("tx", LxSourceService.sourceKeyFor("QQ", ""));
        assertEquals("kg", LxSourceService.sourceKeyFor("KUGOU", ""));
        assertEquals("NETEASE", LxSourceService.platformForSourceKey("wy"));
        assertEquals("QQ", LxSourceService.platformForSourceKey("tx"));
        assertEquals("KUGOU", LxSourceService.platformForSourceKey("kg"));
        // 酷我 / 咪咕现在是可反查的渠道（宿主内置了它们的搜索，取流走脚本）；
        // GD 行选了 kuwo / migu 下游时同样落到这两家。
        assertEquals("kw", LxSourceService.sourceKeyFor("KUWO", ""));
        assertEquals("mg", LxSourceService.sourceKeyFor("MIGU", ""));
        assertEquals("KUWO", LxSourceService.platformForSourceKey("kw"));
        assertEquals("MIGU", LxSourceService.platformForSourceKey("mg"));
        assertEquals("kw", LxSourceService.sourceKeyFor("GD", "kuwo"));
        assertEquals("mg", LxSourceService.sourceKeyFor("GD", "migu"));
        assertNull(LxSourceService.sourceKeyFor("GD", "joox"));
        // B 站 / 汽水 / YouTube 没有 LX 概念。
        assertNull(LxSourceService.sourceKeyFor("BILIBILI", ""));
        assertNull(LxSourceService.sourceKeyFor("QISHUI", ""));
        assertNull(LxSourceService.sourceKeyFor("YOUTUBE", ""));
        // 无头环境里 LX 开关默认关、运行时不存在 —— lxCovers 必须恒 false，闸门退回本家会员。
        if (!LxSourceService.isEnabled()) {
            assertFalse(CrossSourceSwitcher.lxCovers(MusicPlatform.NETEASE));
        }
    }

    @Test
    public void levenshteinBasics() {
        assertEquals(0, CrossSourceSwitcher.levenshtein("abc", "abc"));
        assertEquals(3, CrossSourceSwitcher.levenshtein("abc", ""));
        assertEquals(3, CrossSourceSwitcher.levenshtein("", "abc"));
        assertEquals(1, CrossSourceSwitcher.levenshtein("kitten", "sitten"));
        assertEquals(3, CrossSourceSwitcher.levenshtein("kitten", "sitting"));
    }

    @Test
    public void trialClipIsIncomplete() {
        // 声明 4 分钟、只解出 60 秒 —— 典型的非会员试听片段。
        assertTrue(CrossSourceSwitcher.isIncompleteStream(240_000L, 60_000L));
        // 声明 4 分半、解出 30 秒试听。
        assertTrue(CrossSourceSwitcher.isIncompleteStream(270_000L, 30_000L));
        // 声明 2 分钟、解出 60 秒：比例 0.5、缺口 60 秒，同样算截断。
        assertTrue(CrossSourceSwitcher.isIncompleteStream(120_000L, 60_000L));
    }

    @Test
    public void completeOrUncertainStreamsAreNotIncomplete() {
        // 完整曲目的正常元数据误差（4:00 声明 vs 3:50 实测）不能触发。
        assertFalse(CrossSourceSwitcher.isIncompleteStream(240_000L, 230_000L));
        // 比例过线但缺口不到 30 秒：5 分钟声明 vs 3 分 40 秒实测（73%，缺口 80 秒）本来就过不了 70% 线；
        // 这里验证贴边情形 —— 2 分钟声明 vs 85 秒实测：70.8% 过线，放行。
        assertFalse(CrossSourceSwitcher.isIncompleteStream(120_000L, 85_000L));
        // 短歌（前奏 / 过场）低于 90 秒声明线：绝不判定。
        assertFalse(CrossSourceSwitcher.isIncompleteStream(60_000L, 20_000L));
        // 声明时长未知（GD 搜索 / 元数据缺失）没有参照系：不判。
        assertFalse(CrossSourceSwitcher.isIncompleteStream(0L, 60_000L));
        // 解码器没报出长度：不判。
        assertFalse(CrossSourceSwitcher.isIncompleteStream(240_000L, 0L));
        // 实测比声明长（版本差）：不是截断。
        assertFalse(CrossSourceSwitcher.isIncompleteStream(240_000L, 300_000L));
    }

    // ── 换源徽标回写 ────────────────────────────────────────────────────────

    @Test
    public void propagateUnblockLabelCopiesReplacementBadgeOntoRow() {
        // 行对象（面板 widget 绑定的老引用）本来没有徽标，替身是脚本解出来的流。
        Music row = toTestMusic(200_000L);
        Music replacement = toTestMusic(200_000L);
        replacement.setUnblockedSourceLabel("野花");
        CrossSourceSwitcher.propagateUnblockLabel(row, replacement);
        assertEquals("野花", row.getUnblockedSourceLabel());
    }

    @Test
    public void propagateUnblockLabelClearsStaleBadgeForOfficialStreams() {
        // 行对象自己那一跳留下的旧徽标必须被官方流替身抹掉：现在播的已经不是它解的流。
        Music row = toTestMusic(200_000L);
        row.setUnblockedSourceLabel("上一轮的旧标");
        Music replacement = toTestMusic(200_000L);
        CrossSourceSwitcher.propagateUnblockLabel(row, replacement);
        assertEquals("", row.getUnblockedSourceLabel());
        // 任何一侧为 null 都不该抛错（录取点都可能在异常路径上调用）。
        CrossSourceSwitcher.propagateUnblockLabel(null, replacement);
        CrossSourceSwitcher.propagateUnblockLabel(row, null);
    }

    private static CrossSourceSwitcher.Candidate newTestCandidate(double score, long durationDeltaMillis) {
        return new CrossSourceSwitcher.Candidate(toTestMusic(200_000L), MusicPlatform.QQ, score, durationDeltaMillis);
    }

    private static CrossSourceSwitcher.Candidate newTestCandidate(double score, long durationDeltaMillis,
                                                                  long candidateDurationMillis) {
        return new CrossSourceSwitcher.Candidate(toTestMusic(candidateDurationMillis), MusicPlatform.QQ,
                score, durationDeltaMillis);
    }

    /** 按 {@code Music#fromCadenceTrack} 使用的同一条显式构造器造一条最小曲目。 */
    private static Music toTestMusic(long durationMillis) {
        java.util.List<com.muoniumplayer.core.ncm.music.dto.Artist> artists =
                new ArrayList<com.muoniumplayer.core.ncm.music.dto.Artist>();
        artists.add(new com.muoniumplayer.core.ncm.music.dto.Artist(1L, "测试艺人",
                java.util.Collections.emptyList(), java.util.Collections.emptyList()));
        com.muoniumplayer.core.ncm.music.dto.Album album =
                new com.muoniumplayer.core.ncm.music.dto.Album(1L, "测试专辑", "",
                        java.util.Collections.emptyList());
        return new Music("测试曲名", "测试曲名", "", 1L, artists,
                java.util.Collections.emptyList(), album, durationMillis, 0L, 0L,
                java.util.Collections.emptyList());
    }
}
