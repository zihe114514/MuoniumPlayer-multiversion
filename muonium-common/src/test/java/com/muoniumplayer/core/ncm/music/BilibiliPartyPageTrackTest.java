package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.party.MpTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.util.SourceGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B 站分P曲目从构造到进房间全链路的离线回归（issue #21）。
 *
 * <p>2.5.0 的分P曲目 id 是 {@code BV…:2}，冒号是房间协议身份键 {@code source:songId} 的
 * 分隔符，一起听客户端预检与服务端闸门都把含冒号的 id 当无效曲目挡掉 —— 只有 P1 能进房间。
 * 修法是生产端换成 {@code BV…_p2}，本测试把整条链钉住：</p>
 *
 * <ol>
 *   <li>{@code Music.fromBilibiliTrack} 产出的 {@code sourceId} 是房间安全格式；</li>
 *   <li>旧格式存量（本地歌单 / 播放历史里的 {@code BV…:2}）经 {@code MpTrack.roomSongId}
 *       归一后同样过闸；</li>
 *   <li>上行负载 {@code MpTrack.toJson} 里的 {@code songId} 与预检判定的是同一个值；</li>
 *   <li>服务端镜像 {@code SourceGuard.isValidSongId}（老服务端 1.4.x 同款代码）收下新格式。</li>
 * </ol>
 *
 * <p>不起 Minecraft、不联网；服务端部分用的是仓库内 {@code mpserver-core} 镜像，
 * 与已部署的服务端同源。</p>
 */
class BilibiliPartyPageTrackTest {

    private static final String BVID = "BV1xx411c7mD";

    private static BilibiliMusicService.Track pageTrack(int page) {
        return new BilibiliMusicService.Track(BVID, page, "测试分P", "UP主", 200_000L,
                "https://i0.hdslb.com/bfs/archive/cover.jpg", "", "");
    }

    @Test
    @DisplayName("fromBilibiliTrack 产出的 sourceId 无冒号，P1 与 P2+ 身份互不相同")
    void producedSourceIdIsRoomSafe() {
        com.muoniumplayer.core.ncm.music.dto.Music p1 =
                com.muoniumplayer.core.ncm.music.dto.Music.fromBilibiliTrack(pageTrack(1));
        com.muoniumplayer.core.ncm.music.dto.Music p2 =
                com.muoniumplayer.core.ncm.music.dto.Music.fromBilibiliTrack(pageTrack(2));

        assertEquals(BVID, p1.getSourceId());
        assertEquals(BVID + "_p2", p2.getSourceId());
        assertTrue(p2.getSourceId().indexOf(':') < 0, "分P曲目 id 里不许再出现冒号（房间身份键分隔符）");
        assertNotEquals(p1.getSourceId(), p2.getSourceId());
    }

    @Test
    @DisplayName("stableId 沿旧冒号格式派生：升级不把红心/封面缓存一夜换光")
    void stableIdStaysDerivedFromLegacyCacheKey() {
        com.muoniumplayer.core.ncm.music.dto.Music p2 =
                com.muoniumplayer.core.ncm.music.dto.Music.fromBilibiliTrack(pageTrack(2));
        // 与 Music.stableLong 同一套 FNV-1a（取正）：直接把期望值按旧格式算出来对。
        assertEquals(stableLong("bilibili:" + BVID + ":2"), p2.getId());
    }

    @Test
    @DisplayName("存量旧格式 id 经 roomSongId 归一后过服务端闸门（老服务端 1.4.x 同款校验）")
    void legacyStoredIdsNormalizeThroughTheServerGate() {
        com.muoniumplayer.core.ncm.music.dto.Music legacy =
                com.muoniumplayer.core.ncm.music.dto.Music.fromBilibiliTrack(pageTrack(2));
        legacy.setSourceId(BVID + ":2");   // 2.5.0 存档里的形态

        String normalized = MpTrack.roomSongId(legacy);
        assertEquals(BVID + "_p2", normalized);
        assertTrue(SourceGuard.isValidSongId(normalized), "归一后的 id 必须被服务端闸门收下");
        assertTrue(!SourceGuard.isValidSongId(BVID + ":2"), "旧格式本身必须被挡住（这正是 issue #21）");
    }

    @Test
    @DisplayName("新格式 id 直接过闸；源 id 与 toJson 上行的 songId 是同一个值")
    void newFormatPassesGateAndUploadCarriesTheSameId() {
        com.muoniumplayer.core.ncm.music.dto.Music p3 =
                com.muoniumplayer.core.ncm.music.dto.Music.fromBilibiliTrack(pageTrack(3));

        assertTrue(SourceGuard.isValidSongId(MpTrack.roomSongId(p3)),
                "新格式必须被服务端闸门收下，这是 issue #21 的验收点");

        JsonObject payload = MpTrack.toJson(p3, null, 0L, 0L);
        assertEquals(p3.getSourceId(), payload.get("songId").getAsString(),
                "上行负载的 songId 必须与预检判定的 id 同源，否则预检放行服务端仍拒");
    }

    @Test
    @DisplayName("端到端：分P经上行负载进服务端 Track.fromJson，身份键与 P1 不撞")
    void endToEndServerAcceptsThePageTrack() throws Exception {
        com.muoniumplayer.core.ncm.music.dto.Music p2 =
                com.muoniumplayer.core.ncm.music.dto.Music.fromBilibiliTrack(pageTrack(2));

        JsonObject payload = MpTrack.toJson(p2,
                "https://cn-tj-fx-01-04.bilivideo.com/upgcxcode/a.m4s",
                System.currentTimeMillis() + 600_000L, 200_000L);
        yun.shan.mpsync.config.MpConfig cfg = new yun.shan.mpsync.config.MpConfig();
        Track adopted = Track.fromJson(payload, cfg);

        assertEquals("bilibili", adopted.source);
        assertEquals(BVID + "_p2", adopted.songId);
        assertEquals("bilibili:" + BVID + "_p2", adopted.key());
        assertTrue(adopted.hasFreshUrl(), "直链必须被采纳，否则房间会回头向全房重新要一次链接");

        // P1 与 P2 的身份键不同 —— 否则去重会把第二首当重复拒掉。
        assertNotEquals(Track.fromJson(MpTrack.toJson(
                com.muoniumplayer.core.ncm.music.dto.Music.fromBilibiliTrack(pageTrack(1)),
                null, 0L, 0L), cfg).key(), adopted.key());
    }

    @Test
    @DisplayName("解析侧把新旧两种页码格式都读回同一首分P")
    void parseSourceIdReadsBothSuffixFormats() {
        BilibiliMusicService.DirectVideo modern =
                BilibiliMusicService.parseSourceId(BVID + "_p2");
        BilibiliMusicService.DirectVideo legacy =
                BilibiliMusicService.parseSourceId(BVID + ":2");

        assertEquals(BVID, modern.bvid);
        assertEquals(2, modern.page);
        assertEquals(BVID, legacy.bvid);
        assertEquals(2, legacy.page);
        // 垃圾输入不许抛异常，也不许吞掉 BVID 本体。
        BilibiliMusicService.DirectVideo junk =
                BilibiliMusicService.parseSourceId(BVID + "_p999999999999999999");
        assertEquals(BVID, junk.bvid);
        assertEquals(1, junk.page);
    }

    @Test
    @DisplayName("房间安全判据钉在客户端预检里：新格式过、旧格式挡、委托的是 SourceGuard")
    void clientPrecheckIsWiredToTheSharedGuard() throws Exception {
        // mpparty-common 不在 ProjectPaths 的两个源码根里，用 repoRoot 直接定位。
        java.nio.file.Path clientFile = com.muoniumplayer.testsupport.ProjectPaths.repoRoot()
                .resolve(java.nio.file.Paths.get("mpparty-common", "src", "main", "java",
                        "com", "muoniumplayer", "party", "MpPartyClient.java"));
        String client = new String(java.nio.file.Files.readAllBytes(clientFile),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(client.contains("private static boolean isUsableSongId(String id) {\n        return SourceGuard.isValidSongId(id);"),
                "预检必须委托给服务端镜像的 SourceGuard，不许再手抄一份会漂移的副本");
        assertTrue(client.contains("isUsableSongId(MpTrack.roomSongId(song))"),
                "预检判定前必须先归一分P旧格式 id");
    }

    /** 与 Music / MpTrack 内部同一套 FNV-1a 64 位，取正。 */
    private static long stableLong(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}
