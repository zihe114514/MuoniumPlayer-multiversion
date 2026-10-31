package com.muoniumplayer.core.ncm.apple;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apple Music 目录元数据 → 播放器模型的映射回归；零网络零磁盘。
 *
 * <p>与 Spotify 那份同族，但有一条额外的不能破的约定：<b>preview URL 要跟着行走</b>
 * —— 试听兜底时它省一次单曲详情请求，映射丢了它，兜底链路就会在失败末端再多挨一次
 * 网络往返。</p>
 */
class AppleMappingStaticTest {

    private static AppleMusicService.Track track(String id, String name, String artist,
                                                 String album, long durationMillis) {
        AppleMusicService.Track track = new AppleMusicService.Track();
        track.id = id;
        track.name = name;
        track.artist = artist;
        track.album = album;
        track.durationMillis = durationMillis;
        track.coverUrl = "https://is1-ssl.mzstatic.com/image/thumb/Music600x600bb.jpg";
        track.previewUrl = "https://audio-ssl.itunes.apple.com/preview.m4a";
        return track;
    }

    @Test
    @DisplayName("映射出的行是 APPLE 行：稳定键前缀、多歌手拆分、封面是直链")
    void trackMapsToAppleRow() {
        Music music = Music.fromAppleTrack(track("1440833098", "Never Gonna Give You Up",
                "Rick Astley / Foo Fighters", "Whenever You Need Somebody", 213000L));
        assertNotNull(music);
        assertTrue(music.isApple());
        assertEquals(MusicPlatform.APPLE, music.getSource());
        assertEquals("1440833098", music.getSourceId());
        assertTrue(music.getStableKey().startsWith("apple_"),
                "稳定键以平台小写名开头，封面与缓存都按它落盘");
        assertEquals(2, music.getArtists().size(), "「 / 」分隔的多歌手要拆回多行");
        assertEquals(213000L, music.getDuration());
        assertTrue(music.isForeignCover(),
                "Apple 封面是 mzstatic 直链，绝不能再追加网易云的 ?param= 尺寸参数");
        assertFalse(music.hasVipRestriction(),
                "Apple 目录元数据没有「本行会员受限」概念，试听归因在播放链路上呈现");
    }

    @Test
    @DisplayName("preview URL 跟着行走，试听兜底不再多挨一次单曲详情请求")
    void previewUrlRidesOnRow() {
        Music music = Music.fromAppleTrack(track("1440833098", "Never Gonna Give You Up",
                "Rick Astley", "Whenever You Need Somebody", 213000L));
        assertEquals("https://audio-ssl.itunes.apple.com/preview.m4a", music.getApplePreviewUrl());
        // 空 preview 也要是空串而不是 null（调用方直接 isEmpty 判断）。
        AppleMusicService.Track bare = new AppleMusicService.Track();
        bare.id = "1";
        bare.name = "x";
        Music bareMusic = Music.fromAppleTrack(bare);
        assertNotNull(bareMusic);
        assertEquals("", bareMusic.getApplePreviewUrl());
    }

    @Test
    @DisplayName("映射是确定的：同一 id 两次映射得到同一稳定键")
    void mappingIsDeterministic() {
        Music first = Music.fromAppleTrack(track("1440833098", "A", "X", "AL", 1L));
        Music second = Music.fromAppleTrack(track("1440833098", "A", "X", "AL", 1L));
        assertEquals(first.getId(), second.getId());
        assertEquals(first.getStableKey(), second.getStableKey());
    }

    @Test
    @DisplayName("空 id 与 null 行拒绝映射：映射出来只会是一行永远放不出声的废行")
    void nullAndEmptyIdRejected() {
        assertEquals(null, Music.fromAppleTrack(null));
        AppleMusicService.Track empty = new AppleMusicService.Track();
        empty.id = "  ";
        empty.name = "x";
        assertEquals(null, Music.fromAppleTrack(empty));
    }

    // ── 分享链接解析 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("歌曲链接：/song/<slug>/<id> 与 ?i=<id> 两种形态、裸数字直取")
    void songIdExtraction() {
        assertEquals("1440833098",
                AppleMusicService.extractSongId("https://music.apple.com/cn/song/never-gonna-give-you-up/1440833098"));
        assertEquals("1440833098",
                AppleMusicService.extractSongId("https://music.apple.com/cn/album/whatever/1234?i=1440833098"));
        assertEquals("1440833098", AppleMusicService.extractSongId("1440833098"));
        assertEquals("", AppleMusicService.extractSongId("https://music.apple.com/cn/playlist/x/pl.u-abc"));
    }

    @Test
    @DisplayName("歌单链接：pl. 前缀整段保留（含点与连字符），不吞进数字启发式")
    void playlistIdExtraction() {
        assertEquals("pl.u-eddBzZJ40Nr2qB",
                AppleMusicService.extractPlaylistId("https://music.apple.com/cn/playlist/top-100/pl.u-eddBzZJ40Nr2qB"));
        assertEquals("pl.u-eddBzZJ40Nr2qB", AppleMusicService.extractPlaylistId("pl.u-eddBzZJ40Nr2qB"));
        assertEquals("", AppleMusicService.extractPlaylistId("1440833098"));
    }

    @Test
    @DisplayName("专辑与资料库歌单链接")
    void albumAndLibraryExtraction() {
        assertEquals("1440833095",
                AppleMusicService.extractAlbumId("https://music.apple.com/cn/album/whenever/1440833095"));
        assertEquals("p.123456",
                AppleMusicService.extractLibraryPlaylistId("https://music.apple.com/cn/library/playlist/p.123456"));
    }

    // ── 伪 URL 协议 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("伪 URL 往返：resolve 与还原互为逆运算；前缀即协议")
    void pseudoUrlRoundTrip() {
        AppleConfigStore.putForTest("token", "cn", true, true);
        try {
            // 还原与常量：前缀改了等于改协议，CloudMusic 的分派要跟着改。
            assertEquals("1440833098",
                    ApplePlaybackResolver.songIdFromPseudoUrl("muonium-apple://song/1440833098"));
            assertEquals(null, ApplePlaybackResolver.songIdFromPseudoUrl("https://music.apple.com/x"));
            assertEquals(null, ApplePlaybackResolver.songIdFromPseudoUrl("muonium-apple://song/"));
        } finally {
            AppleConfigStore.clearForTest();
        }
    }

    @Test
    @DisplayName("引擎开关关掉时不给伪 URL（退化为纯元数据源 + 试听兜底）")
    void nativeEngineDisabledBlocksPseudoUrl() {
        AppleConfigStore.putForTest("token", "cn", false, true);
        try {
            assertEquals(null, ApplePlaybackResolver.resolvePseudoUrl("1440833098"));
        } finally {
            AppleConfigStore.clearForTest();
        }
    }
}
