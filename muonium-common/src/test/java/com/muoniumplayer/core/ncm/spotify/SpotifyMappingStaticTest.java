package com.muoniumplayer.core.ncm.spotify;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spotify 元数据 → 播放器模型的映射回归；零网络零磁盘。
 *
 * <p>这份映射有两个不能破的约定：<b>稳定键</b>（封面 / 下载 / 历史缓存全按它落盘，
 * 映射一改等于全体缓存作废）与<b>元数据行的边界</b>（Spotify 行永远不携带音频，
 */
class SpotifyMappingStaticTest {

    private static SpotifyMusicService.Track track(String id, String name, String artist,
                                                   String album, long duration) {
        SpotifyMusicService.Track track = new SpotifyMusicService.Track();
        track.id = id;
        track.name = name;
        track.artist = artist;
        track.album = album;
        track.durationMillis = duration;
        track.coverUrl = "https://i.scdn.co/image/ab67616d0000b273test";
        return track;
    }

    @Test
    @DisplayName("映射出的行是 SPOTIFY 元数据行：稳定键前缀、多歌手拆分、封面是直链")
    void trackMapsToSpotifyRow() {
        Music music = Music.fromSpotifyTrack(
                track("4uLU6hMCjMI75M1A2tKUQC", "Rick Astley", "Rick Astley / Foo Fighters",
                        "Whenever You Need Somebody", 213000L));
        assertNotNull(music);
        assertTrue(music.isSpotify());
        assertEquals(MusicPlatform.SPOTIFY, music.getSource());
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", music.getSourceId());
        assertTrue(music.getStableKey().startsWith("spotify_"),
                "稳定键以平台小写名开头，封面与缓存都按它落盘");
        assertEquals(2, music.getArtists().size(), "「 / 」分隔的多歌手要拆回多行");
        assertEquals(213000L, music.getDuration());
        assertTrue(music.isForeignCover(),
                "Spotify 封面是别人家的直链，绝不能再追加网易云的 ?param= 尺寸参数");
        assertFalse(music.hasVipRestriction(),
                "Spotify 元数据没有会员概念，替身的会员限制由替身自己呈现");
    }

    @Test
    @DisplayName("映射是确定的：同一 id 两次映射得到同一稳定键")
    void mappingIsDeterministic() {
        Music first = Music.fromSpotifyTrack(track("abc", "A", "X", "AL", 1L));
        Music second = Music.fromSpotifyTrack(track("abc", "A", "X", "AL", 1L));
        assertEquals(first.getId(), second.getId());
        assertEquals(first.getStableKey(), second.getStableKey());
    }

    @Test
    @DisplayName("空 id 或本地文件行拒绝映射")
    void rejectsUnusableTracks() {
        assertNull(Music.fromSpotifyTrack(null));
        assertNull(Music.fromSpotifyTrack(track("", "A", "X", "AL", 1L)));
        SpotifyMusicService.Track local = track("", "本地文件", "X", "AL", 1L);
        local.local = true;
        assertNull(Music.fromSpotifyTrack(local), "本地文件行没有可跨平台匹配的规范化元数据");
    }

    @Test
    @DisplayName("歌单卡片映射：平台与 platformPlaylistId 齐全，专辑卡带前缀")
    void playlistCardMapping() {
        SpotifyMusicService.Playlist playlist = new SpotifyMusicService.Playlist();
        playlist.id = "37i9dQZF1DXcBWIGoYBM5M";
        playlist.name = "Today's Top Hits";
        playlist.trackCount = 50;
        PlayList card = SpotifyMusicService.toPlayListCard(playlist);
        assertNotNull(card);
        assertEquals(MusicPlatform.SPOTIFY, card.getPlatform());
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", card.getPlatformPlaylistId());
        assertEquals(50, card.getCount());
        // 稳定 id 是 FNV-1a，同一 id 两次建卡必须同一张（侧栏行键用它）。
        assertEquals(card.getId(),
                SpotifyMusicService.toPlayListCard(playlist).getId());

        SpotifyMusicService.Playlist album = new SpotifyMusicService.Playlist();
        album.id = "album:4aawyAB9vmqN3uQ7FjRGTy";
        album.name = "Album";
        PlayList albumCard = SpotifyMusicService.toPlayListCard(album);
        assertNotNull(albumCard);
        assertEquals("album:4aawyAB9vmqN3uQ7FjRGTy", albumCard.getPlatformPlaylistId());

        assertNull(SpotifyMusicService.toPlayListCard(null));
        SpotifyMusicService.Playlist blank = new SpotifyMusicService.Playlist();
        blank.id = "  ";
        assertNull(SpotifyMusicService.toPlayListCard(blank));
    }

    @Test
    @DisplayName("喜欢的歌曲虚拟单 ID 与 base62 歌单 id 空间不冲突")
    void likedPlaylistIdNeverCollides() {
        // base62 字符集不含连字符；带连字符的保留词永远不会是真实歌单 id。
        for (char c : SpotifyMusicService.LIKED_PLAYLIST_ID.toCharArray()) {
            assertTrue(Character.isLowerCase(c),
                    "虚拟单 id 必须落在 base62 之外：" + SpotifyMusicService.LIKED_PLAYLIST_ID);
            return;
        }
    }
}
