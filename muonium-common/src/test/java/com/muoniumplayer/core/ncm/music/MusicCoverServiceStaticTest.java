package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态封面优先链的纯静态判据：哪些曲目值得查、网易云落空后还问不问本平台、
 * 跨平台匹配到的 songId 怎么挑与怎么记。
 *
 * <p>这里只碰不依赖 Minecraft 运行期的纯函数 —— 取址本体（搜索 / 动态封面端点 / ffmpeg）
 * 全部要联网，留给真机。</p>
 */
public class MusicCoverServiceStaticTest {

    // ── 第 0 级：这首歌值不值得查 ───────────────────────────────────────────

    @Test
    public void neteaseTracksNeedARealSongId() {
        assertTrue(MusicCoverService.supportsDynamicCover(music(MusicPlatform.NETEASE, 123L, 0L)));
        assertFalse(MusicCoverService.supportsDynamicCover(music(MusicPlatform.NETEASE, 0L, 0L)));
        assertFalse(MusicCoverService.supportsDynamicCover(music(MusicPlatform.NETEASE, -1L, 0L)));
    }

    @Test
    public void kuGouTracksNeedTheirHashPrimaryKey() {
        // hash 是酷狗取流与歌词的同一个主键，没有它说明这条 Music 不是从酷狗接口来的。
        Music bare = music(MusicPlatform.KUGOU, 500L, 0L);
        assertFalse(MusicCoverService.supportsDynamicCover(bare));
        assertFalse(MusicCoverService.supportsDynamicCover(withKugouHash(bare, "   ")));
        assertTrue(MusicCoverService.supportsDynamicCover(withKugouHash(bare, "abc123")));
    }

    @Test
    public void everyOtherPlatformIsAllowedThroughToNeteaseLookup() {
        // 优先链的第 1 级对所有平台开放：网易云那一级落空时还有静态封面兜底，最坏只是白问一次。
        for (MusicPlatform platform : Arrays.asList(MusicPlatform.QQ, MusicPlatform.KUWO,
                MusicPlatform.MIGU, MusicPlatform.GD, MusicPlatform.BILIBILI, MusicPlatform.QISHUI,
                MusicPlatform.YOUTUBE)) {
            assertTrue(MusicCoverService.supportsDynamicCover(music(platform, 777L, 0L)),
                    platform.name() + " 应该被放行去问网易云");
        }
        assertFalse(MusicCoverService.supportsDynamicCover(null));
    }

    // ── 第 2 级：本平台有没有原生动态封面可回退 ────────────────────────────

    @Test
    public void onlyKuGouHasAPlatformNativeDynamicCover() {
        assertTrue(MusicCoverService.hasPlatformNativeDynamicCover(music(MusicPlatform.KUGOU, 1L, 0L)));
        assertFalse(MusicCoverService.hasPlatformNativeDynamicCover(music(MusicPlatform.NETEASE, 1L, 0L)));
        assertFalse(MusicCoverService.hasPlatformNativeDynamicCover(music(MusicPlatform.QQ, 1L, 0L)));
        assertFalse(MusicCoverService.hasPlatformNativeDynamicCover(music(MusicPlatform.QISHUI, 1L, 0L)));
        assertFalse(MusicCoverService.hasPlatformNativeDynamicCover(null));
    }

    // ── 跨平台匹配：从网易云搜索结果里挑一条 ──────────────────────────────

    @Test
    public void picksTheNeteaseMatchThatPassesTheScoringGate() {
        Music current = track(MusicPlatform.QQ, 9001L, "夜曲", "周杰伦", 227_000L);
        List<Music> results = Collections.singletonList(
                track(MusicPlatform.NETEASE, 180L, "夜曲", "周杰伦", 225_000L));
        assertEquals(180L, MusicCoverService.pickNeteaseMatch(current, results, "夜曲", "周杰伦"));
    }

    @Test
    public void rejectsCandidatesWhoseDurationIsFarOff() {
        // 时长闸门是防「同名翻唱 / 现场版」的主闸门，与自动换源共用 acceptanceScore。
        Music current = track(MusicPlatform.QQ, 9001L, "夜曲", "周杰伦", 227_000L);
        List<Music> results = Collections.singletonList(
                track(MusicPlatform.NETEASE, 180L, "夜曲", "周杰伦", 60_000L));
        assertEquals(0L, MusicCoverService.pickNeteaseMatch(current, results, "夜曲", "周杰伦"));
    }

    @Test
    public void ignoresResultsThatAreNotTaggedNetease() {
        // 这张表存在的唯一理由是真的 songId；带别的平台标记的曲目喂给动态封面端点只会白问。
        Music current = track(MusicPlatform.QQ, 9001L, "夜曲", "周杰伦", 227_000L);
        List<Music> results = Arrays.asList(
                track(MusicPlatform.KUGOU, 180L, "夜曲", "周杰伦", 225_000L),
                track(MusicPlatform.GD, 181L, "夜曲", "周杰伦", 225_000L));
        assertEquals(0L, MusicCoverService.pickNeteaseMatch(current, results, "夜曲", "周杰伦"));
    }

    @Test
    public void keepsTheHighestScoringMatch() {
        Music current = track(MusicPlatform.QQ, 9001L, "夜曲", "周杰伦", 227_000L);
        // 第二条曲名一致但艺人不一致（0.7），第一条双全一致（1.0）→ 取前者。
        List<Music> results = Arrays.asList(
                track(MusicPlatform.NETEASE, 111L, "夜曲", "周杰伦", 226_000L),
                track(MusicPlatform.NETEASE, 222L, "夜曲", "林俊杰", 226_000L));
        assertEquals(111L, MusicCoverService.pickNeteaseMatch(current, results, "夜曲", "周杰伦"));
        // 反序也一样：排名而不是响应顺序决定录取。
        List<Music> reversed = new ArrayList<Music>(results);
        Collections.reverse(reversed);
        assertEquals(111L, MusicCoverService.pickNeteaseMatch(current, reversed, "夜曲", "周杰伦"));
    }

    @Test
    public void toleratesEmptyOrNullInputs() {
        Music current = track(MusicPlatform.QQ, 9001L, "夜曲", "周杰伦", 227_000L);
        assertEquals(0L, MusicCoverService.pickNeteaseMatch(current, null, "夜曲", "周杰伦"));
        assertEquals(0L, MusicCoverService.pickNeteaseMatch(current, Collections.emptyList(), "夜曲", ""));
        assertEquals(0L, MusicCoverService.pickNeteaseMatch(current,
                Collections.singletonList((Music) null), "夜曲", "周杰伦"));
        assertEquals(0L, MusicCoverService.pickNeteaseMatch(null,
                Collections.singletonList(track(MusicPlatform.NETEASE, 1L, "夜曲", "周杰伦", 1L)),
                "夜曲", "周杰伦"));
    }

    @Test
    public void skipsTheResultThatIsTheTrackItself() {
        // stableKey 撞车的自匹配没有意义（网易云曲目本来就走本尊 id，不查这张表）。
        Music current = track(MusicPlatform.NETEASE, 180L, "夜曲", "周杰伦", 227_000L);
        List<Music> results = Collections.singletonList(current);
        assertEquals(0L, MusicCoverService.pickNeteaseMatch(current, results, "夜曲", "周杰伦"));
    }

    // ── 匹配结论缓存：0 也是有效结论 ──────────────────────────────────────

    @Test
    public void cachesNegativeMatchConclusionsToo() {
        MusicCoverService.clearCachesForTests();
        String key = "qq_12345";
        assertNull(MusicCoverService.cachedNeteaseCoverId(key));
        MusicCoverService.rememberNeteaseCoverId(key, 0L);
        assertEquals(Long.valueOf(0L), MusicCoverService.cachedNeteaseCoverId(key));
        MusicCoverService.rememberNeteaseCoverId(key, 678L);
        assertEquals(Long.valueOf(678L), MusicCoverService.cachedNeteaseCoverId(key));
        MusicCoverService.clearCachesForTests();
        assertNull(MusicCoverService.cachedNeteaseCoverId(key));
    }

    @Test
    public void ignoresBlankCacheKeysWithoutTouchingTheTable() {
        MusicCoverService.clearCachesForTests();
        MusicCoverService.rememberNeteaseCoverId(null, 42L);
        MusicCoverService.rememberNeteaseCoverId("", 42L);
        assertNull(MusicCoverService.cachedNeteaseCoverId(null));
        assertNull(MusicCoverService.cachedNeteaseCoverId(""));
    }

    // ── 测试脚手架 ─────────────────────────────────────────────────────────

    /** 只带平台与 id 的最小曲目，用于纯判据（不进打分）。 */
    private static Music music(MusicPlatform platform, long id, long durationMillis) {
        return track(platform, id, "测试曲名", "测试艺人", durationMillis);
    }

    private static Music withKugouHash(Music music, String hash) {
        music.setKugouHash(hash);
        return music;
    }

    /** 按 {@code Music#fromCadenceTrack} 使用的同一条显式构造器造一条可打分的曲目。 */
    private static Music track(MusicPlatform platform, long id, String name, String artist,
                              long durationMillis) {
        List<Artist> artists = new ArrayList<Artist>();
        artists.add(new Artist(1L, artist, Collections.emptyList(), Collections.emptyList()));
        Album album = new Album(1L, "测试专辑", "", Collections.emptyList());
        Music music = new Music(name, name, "", id, artists, Collections.emptyList(), album,
                durationMillis, 0L, 0L, Collections.emptyList());
        music.setSource(platform);
        return music;
    }
}
