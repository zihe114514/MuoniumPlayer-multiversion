package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodianMusicServiceTest {

    @Test
    void mapsQrStatusContract() {
        assertEquals(com.muoniumplayer.core.music.source.QrLoginState.WAITING,
                BodianMusicService.qrState(0));
        assertEquals(com.muoniumplayer.core.music.source.QrLoginState.SCANNED,
                BodianMusicService.qrFailureState(11027));
        assertEquals(com.muoniumplayer.core.music.source.QrLoginState.WAITING,
                BodianMusicService.qrState(1));
        assertEquals(com.muoniumplayer.core.music.source.QrLoginState.EXPIRED,
                BodianMusicService.qrState(2));
        assertEquals(com.muoniumplayer.core.music.source.QrLoginState.CONFIRMED,
                BodianMusicService.qrState(3));
        assertEquals(com.muoniumplayer.core.music.source.QrLoginState.ERROR,
                BodianMusicService.qrState(99));
    }

    @Test
    void parsesTrackAliasesAndConvertsDuration() {
        JsonObject wire = new JsonObject();
        wire.addProperty("id", "MUSIC_123");
        wire.addProperty("name", "测试歌曲");
        wire.addProperty("duration", 231);
        wire.addProperty("albumPic", "https://img.example.test/cover.webp");
        JsonArray artists = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("name", "歌手 A");
        JsonObject second = new JsonObject();
        second.addProperty("name", "歌手 B");
        artists.add(first);
        artists.add(second);
        wire.add("artists", artists);
        JsonObject payInfo = new JsonObject();
        payInfo.addProperty("paytype", 1);
        wire.add("payInfo", payInfo);

        BodianMusicService.Track track = BodianMusicService.parseTrack(wire);

        assertNotNull(track);
        assertEquals("123", track.id);
        assertEquals("测试歌曲", track.title);
        assertEquals("歌手 A / 歌手 B", track.artist);
        assertEquals(231_000L, track.durationMillis);
        assertEquals("https://img.example.test/cover.jpg", track.coverUrl);
        assertTrue(track.vip);
    }

    @Test
    void rejectsTrackWithoutSourceId() {
        JsonObject wire = new JsonObject();
        wire.addProperty("name", "没有 ID 的歌曲");
        assertNull(BodianMusicService.parseTrack(wire));
    }

    @Test
    void keepsPlaylistStableIdsDistinctAcrossSources() {
        JsonObject own = new JsonObject();
        own.addProperty("id", "home-1");
        own.addProperty("name", "自建歌单");
        own.addProperty("musicCount", 2);
        JsonObject collected = own.deepCopy();
        collected.addProperty("name", "收藏歌单");

        PlayList ownPlaylist = BodianMusicService.parsePlaylist(own, 5);
        PlayList collectedPlaylist = BodianMusicService.parsePlaylist(collected, 4);

        assertNotNull(ownPlaylist);
        assertNotNull(collectedPlaylist);
        assertEquals("pl-5-home-1", ownPlaylist.getPlatformPlaylistId());
        assertEquals("pl-4-home-1", collectedPlaylist.getPlatformPlaylistId());
        assertFalse(ownPlaylist.getId() == collectedPlaylist.getId());
    }

    @Test
    void fallsBackToSourceWhenSourceTypeIsMissingOrZero() {
        JsonObject wire = new JsonObject();
        wire.addProperty("id", "4123");
        wire.addProperty("name", "来源回退");
        wire.addProperty("sourceType", 0);
        wire.addProperty("source", 4);

        PlayList playlist = BodianMusicService.parsePlaylist(wire, 5);

        assertNotNull(playlist);
        assertEquals("pl-4-4123", playlist.getPlatformPlaylistId());
    }

    @Test
    void parsesCreateReceiptThatContainsOnlyAnId() {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("id", "987654");

        PlayList playlist = BodianMusicService.parseCreatedPlaylist(receipt, "新歌单");

        assertNotNull(playlist);
        assertEquals("新歌单", playlist.getName());
        assertEquals("pl-5-987654", playlist.getPlatformPlaylistId());
    }

    @Test
    void separatesVirtualLikedPlaylistFromWritableCloudPlaylist() {
        assertTrue(BodianMusicService.isVirtualPlaylistId("liked-123"));
        assertTrue(BodianMusicService.isVirtualPlaylistId("home:module-1"));
        assertTrue(BodianMusicService.isVirtualPlaylistId("home:0"));
        assertFalse(BodianMusicService.isVirtualPlaylistId("pl-5-123"));
    }

    @Test
    void parsesPersonalizedGroupsFromSongListAndKeepsThemLazyLoadable() {
        JsonObject home = new JsonObject();
        JsonArray groups = new JsonArray();
        JsonObject group = new JsonObject();
        group.addProperty("id", 0);
        group.addProperty("title", "每日推荐");
        JsonArray songs = new JsonArray();
        JsonObject previewTrack = new JsonObject();
        previewTrack.addProperty("id", 12345);
        previewTrack.addProperty("name", "预览曲目");
        previewTrack.addProperty("artist", "测试歌手");
        songs.add(previewTrack);
        group.add("songs", songs);
        groups.add(group);
        home.add("songList", groups);

        java.util.List<PlayList> playlists = BodianMusicService.personalizedPlaylists(home);

        assertEquals(1, playlists.size());
        PlayList playlist = playlists.get(0);
        assertEquals("home:0", playlist.getPlatformPlaylistId());
        assertEquals("每日推荐", playlist.getName());
        assertEquals(1, playlist.musics.size());
        assertEquals("12345", playlist.musics.get(0).getSourceId());
        assertFalse(playlist.musicsQueried);
        assertFalse(playlist.musicsLoaded);
    }

    @Test
    void rejectsMalformedPersonalizedPlaylistIndicesBeforeRequesting() {
        assertThrows(IllegalArgumentException.class,
                () -> BodianMusicService.playlistTracks("home:not-an-index", 30));
    }

    @Test
    void choosesTheDocumentedAudioQualityFallbacks() {
        assertArrayEquals(new int[]{128}, BodianMusicService.bitrateLadder(Quality.STANDARD));
        assertArrayEquals(new int[]{20000, 320, 128},
                BodianMusicService.bitrateLadder(Quality.HIGHER));
        assertArrayEquals(new int[]{2000, 20000, 128},
                BodianMusicService.bitrateLadder(Quality.LOSSLESS));
        assertArrayEquals(new int[]{2000, 20000, 128},
                BodianMusicService.bitrateLadder(Quality.HIRES));
    }

    @Test
    void parsesBinarySizeUnitsIncludingGigabytes() {
        assertEquals(2_684_354_560L, BodianMusicService.parseSize("2.5GB"));
    }
}
