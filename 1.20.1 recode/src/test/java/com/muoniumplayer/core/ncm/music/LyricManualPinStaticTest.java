package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动钉歌词的纯判据：来源枚举、候选壳、以及钉词记忆的落盘与读取。
 *
 * <p>钉词是这一整套流程唯一的持久化产物，也是「自动匹配错了用户能自己纠」的全部凭据。它一旦读不回来
 * 或者读错了源，用户下一次开机就得重挑一遍，所以这里的用例覆盖到跨重启读写。真正的取词请求
 * （{@code LyricSearchService.fetchLyrics}）要联网，留给真机。</p>
 *
 * <p>用例会真的写到测试工作目录下的 {@code config/muonium/lyric_manual.json}，每个用例结束时
 * {@code clear} 掉自己的键——那才是「忘掉」的语义，不会往文件里留垃圾。与
 * {@link LyricSourceMemoryProbeTest} 同一套做法。</p>
 */
class LyricManualPinStaticTest {

    /** 把静态缓存清掉，模拟一次进程重启。 */
    private static void forgetInMemoryCache() throws Exception {
        Field field = LyricManualMemory.class.getDeclaredField("cache");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static LyricSearchHit hit(LyricSearchSource source, String trackId, String platform,
                                      JsonObject payload) {
        return new LyricSearchHit(source, trackId, platform, "晴天", "周杰伦", "叶惠美",
                269_000L, "http://p1.music.126.net/cover.jpg", 96, true, payload);
    }

    // ── 来源 ────────────────────────────────────────────────────────────────

    @Test
    void tabOrderAndLabelsMatchFolia() {
        assertEquals(Arrays.asList("网易云音乐", "AMLLDB", "QQ 音乐", "酷狗音乐"),
                Arrays.asList(LyricSearchSource.NETEASE.label, LyricSearchSource.AMLL.label,
                        LyricSearchSource.QQ.label, LyricSearchSource.KUGOU.label));
        // 配置文件里存的是 id，改名会让已有的钉词全读不出来，所以顺序与 id 都得钉住。
        assertEquals("netease", LyricSearchSource.NETEASE.id);
        assertEquals("amll", LyricSearchSource.AMLL.id);
        assertEquals("qq", LyricSearchSource.QQ.id);
        assertEquals("kugou", LyricSearchSource.KUGOU.id);
    }

    @Test
    void amllHasNoFreeTextSearchSoTheSearchBoxIsHiddenThere() {
        assertFalse(LyricSearchSource.AMLL.supportsManualSearch(),
                "AMLL 词库不接受自由检索词，界面那一档不给搜索框");
        assertTrue(LyricSearchSource.NETEASE.supportsManualSearch());
        assertTrue(LyricSearchSource.QQ.supportsManualSearch());
        assertTrue(LyricSearchSource.KUGOU.supportsManualSearch());
    }

    @Test
    void unknownSourceIdFallsBackToNetease() {
        assertEquals(LyricSearchSource.QQ, LyricSearchSource.fromId(" qq "));
        assertEquals(LyricSearchSource.NETEASE, LyricSearchSource.fromId("netease"));
        assertEquals(LyricSearchSource.NETEASE, LyricSearchSource.fromId("kuwo"));
        assertEquals(LyricSearchSource.NETEASE, LyricSearchSource.fromId(null));
    }

    // ── 候选壳 ──────────────────────────────────────────────────────────────

    @Test
    void coverUrlIsUpgradedToHttpsAndScoreIsClamped() {
        LyricSearchHit sample = hit(LyricSearchSource.NETEASE, "186016", "", null);
        assertEquals("https://p1.music.126.net/cover.jpg", sample.coverUrl);
        assertEquals(96, sample.score);
        // 越界分数、以及上游没给封面，都不该把界面搞出负数或者空指针。
        LyricSearchHit odd = new LyricSearchHit(LyricSearchSource.QQ, "7", "", "t", "a", "al",
                -1L, null, 4000, false, null);
        assertEquals(100, odd.score);
        assertEquals(0L, odd.durationMillis);
        assertEquals("", odd.coverUrl);
        assertEquals("", odd.durationText(), "时长未知的候选卡片不显示时长");
    }

    @Test
    void durationTextRoundsToMinutesAndSeconds() {
        assertEquals("4:29", new LyricSearchHit(LyricSearchSource.NETEASE, "1", "", "t", "a", "al",
                269_000L, "", 50, false, null).durationText());
        // 上游卡片就是 mm:ss 一路滚上去的，超过一小时也进分钟位。这里保持一致，
        // 顺手把「不额外加小时位」这个选择钉住：真出现一小时的 DJ 串烧时它显示 60:00。
        assertEquals("60:00", new LyricSearchHit(LyricSearchSource.NETEASE, "1", "", "t", "a", "al",
                3_600_000L, "", 50, false, null).durationText());
    }

    @Test
    void nullSourceInAHitDegradesToNeteaseInsteadOfThrowing() {
        LyricSearchHit sample = new LyricSearchHit(null, "1", "", "t", "a", "al",
                0L, "", 50, false, null);
        assertEquals(LyricSearchSource.NETEASE, sample.source);
    }

    // ── 钉词记忆 ────────────────────────────────────────────────────────────

    @Test
    void pinnedCandidateSurvivesAProcessRestartWithItsSourceAndId() throws Exception {
        String key = "probe:lyricmanual:restart";
        try {
            LyricManualMemory.write(key, hit(LyricSearchSource.KUGOU, "kg-123", "",
                    payload("kg-123", "secret-access-key")).toPin());
            forgetInMemoryCache();

            LyricManualMemory.Pin pin = LyricManualMemory.read(key);
            assertEquals(LyricSearchSource.KUGOU, pin.source, "取词要按钉住的那个源，读成网易云就全错了");
            assertEquals("kg-123", pin.trackId);
            assertEquals(269_000L, pin.durationMillis);
            // payload 存成嵌套对象，读回来还是同一份上游 JSON：酷狗靠里面的 accesskey 取词。
            JsonObject restored = pin.candidateJson();
            assertEquals("secret-access-key", restored.get("accesskey").getAsString());
        } finally {
            LyricManualMemory.clear(key);
            forgetInMemoryCache();
        }
        assertNull(LyricManualMemory.read(key));
    }

    @Test
    void clearingFallsBackToTheAutomaticChain() throws Exception {
        String key = "probe:lyricmanual:clear";
        LyricManualMemory.write(key, hit(LyricSearchSource.QQ, "qq-9", "", null).toPin());
        assertTrue(LyricManualMemory.has(key));
        LyricManualMemory.clear(key);
        forgetInMemoryCache();
        assertFalse(LyricManualMemory.has(key), "取消钉住之后必须回到自动加载链");
    }

    @Test
    void aPinWithoutAStableKeyOrTrackIdIsNotStoredAtAll() throws Exception {
        // 本地未入库的歌没有稳定键；没有候选 id 的钉词精确重取不了，存进去只会每次都失败。
        LyricManualMemory.write(null, hit(LyricSearchSource.NETEASE, "1", "", null).toPin());
        LyricManualMemory.write("   ", hit(LyricSearchSource.NETEASE, "1", "", null).toPin());
        LyricManualMemory.write("probe:lyricmanual:noid",
                new LyricManualMemory.Pin(LyricSearchSource.NETEASE, "", "", "t", "a", "al", 0L, ""));
        LyricManualMemory.write("probe:lyricmanual:nopayload", null);
        forgetInMemoryCache();
        assertFalse(LyricManualMemory.has("probe:lyricmanual:noid"));
        assertFalse(LyricManualMemory.has("probe:lyricmanual:nopayload"));
    }

    @Test
    void oversizedPayloadIsRefusedSoTheConfigFileStaysReadable() throws Exception {
        String key = "probe:lyricmanual:big";
        char[] filler = new char[9000];
        Arrays.fill(filler, 'x');
        try {
            LyricManualMemory.write(key, new LyricManualMemory.Pin(LyricSearchSource.KUGOU,
                    "kg-big", "", "t", "a", "al", 0L, new String(filler)));
            forgetInMemoryCache();
            assertNull(LyricManualMemory.read(key), "几十 KB 的 payload 不该进配置文件");
        } finally {
            LyricManualMemory.clear(key);
            forgetInMemoryCache();
        }
    }

    @Test
    void malformedEntriesAreSkippedWithoutLosingTheGoodOnes() throws Exception {
        // 手工编辑过的配置文件不能让整张表读不出来：坏一条丢一条。
        String key = "probe:lyricmanual:broken";
        try {
            LyricManualMemory.write(key, hit(LyricSearchSource.NETEASE, "186016", "", null).toPin());
            forgetInMemoryCache();
            java.io.File file = com.muoniumplayer.core.settings.ConfigPaths.LYRIC_MANUAL;
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String tampered = text.substring(0, text.length() - 1)
                    + ",\"broken\":{\"trackId\":\"\"}}";
            java.nio.file.Files.write(file.toPath(), tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            forgetInMemoryCache();
            assertEquals("186016", LyricManualMemory.read(key).trackId);
        } finally {
            LyricManualMemory.clear(key);
            forgetInMemoryCache();
        }
    }

    @Test
    void pinDescriptionReadsLikeTheStatusLine() {
        LyricManualMemory.Pin full = hit(LyricSearchSource.NETEASE, "186016", "", null).toPin();
        assertEquals("周杰伦 - 晴天", full.describe());
        // 上游没给歌名时退回 id，至少日志里能看出钉的是哪一条。
        assertEquals("186016", new LyricManualMemory.Pin(LyricSearchSource.NETEASE, "186016", "",
                "", "", "", 0L, "").describe());
        assertNull(full.candidateJson(), "网易云靠 songId 直接取词，没有 payload 时返回 null 而不是抛");
    }

    @Test
    void amllPlatformFieldRoundTrips() throws Exception {
        String key = "probe:lyricmanual:amll";
        try {
            LyricManualMemory.write(key, new LyricManualMemory.Pin(LyricSearchSource.AMLL,
                    "amll-77", "qq", "稻香", "周杰伦", "魔杰座", 223_000L, ""));
            forgetInMemoryCache();
            LyricManualMemory.Pin pin = LyricManualMemory.read(key);
            assertEquals(LyricSearchSource.AMLL, pin.source);
            assertEquals("qq", pin.platform, "AMLL 要知道这条词库条目是按哪个平台的 id 存的");
            assertEquals("", pin.payload, "没 payload 就别往文件里塞空壳");
        } finally {
            LyricManualMemory.clear(key);
            forgetInMemoryCache();
        }
    }

    // ── 默认检索词 ──────────────────────────────────────────────────────────

    @Test
    void theSearchBoxStartsFilledWithTitleArtistAlbum() {
        // 弹窗打开时框里就有词，是为了「进来就能直接回车」；用户改不改是他的判断。
        assertEquals("晴天 - 周杰伦 - 叶惠美", LyricSearchService.defaultQuery(sampleTrack()));
    }

    @Test
    void aTrackWithoutAlbumOrArtistsStillGivesAUsableQuery() {
        // 没在放歌的时候也会开这一页（用户手动检索），默认词给空串而不是 null。
        org.junit.jupiter.api.Assertions.assertNotNull(LyricSearchService.defaultQuery(null),
                "没有目标也得返回空串，不能把 null 塞进输入框");
        assertEquals("", LyricSearchService.defaultQuery(null));
        assertEquals("晴天", LyricSearchService.defaultQuery(bareTrack()));
    }

    /** 三件套齐全的网易云曲目。 */
    private static com.muoniumplayer.core.ncm.music.dto.Music sampleTrack() {
        java.util.List<com.muoniumplayer.core.ncm.music.dto.Artist> artists =
                new java.util.ArrayList<com.muoniumplayer.core.ncm.music.dto.Artist>();
        artists.add(new com.muoniumplayer.core.ncm.music.dto.Artist(1L, "周杰伦",
                java.util.Collections.emptyList(), java.util.Collections.emptyList()));
        com.muoniumplayer.core.ncm.music.dto.Album album =
                new com.muoniumplayer.core.ncm.music.dto.Album(1L, "叶惠美", "",
                        java.util.Collections.emptyList());
        return new com.muoniumplayer.core.ncm.music.dto.Music("晴天", "晴天", "", 186016L, artists,
                java.util.Collections.emptyList(), album, 269_000L, 0L, 0L,
                java.util.Collections.emptyList());
    }

    /** 没有歌手也没有专辑（本地文件与部分第三方源就是这样）。 */
    private static com.muoniumplayer.core.ncm.music.dto.Music bareTrack() {
        return new com.muoniumplayer.core.ncm.music.dto.Music("晴天", "晴天", "", 1L,
                java.util.Collections.emptyList(), java.util.Collections.emptyList(), null,
                269_000L, 0L, 0L, java.util.Collections.emptyList());
    }

    private static JsonObject payload(String id, String accessKey) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("accesskey", accessKey);
        return node;
    }
}
