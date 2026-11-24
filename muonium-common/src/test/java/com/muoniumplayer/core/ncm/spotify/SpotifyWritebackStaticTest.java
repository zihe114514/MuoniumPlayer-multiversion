package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyWritebackStaticTest {

    private static final class FakeTransport implements SpotifyWebApiClient.Transport {
        final List<SpotifyWebApiClient.Request> requests = new ArrayList<>();
        int responseCode = 201;

        @Override
        public SpotifyWebApiClient.Response send(SpotifyWebApiClient.Request request) {
            requests.add(request);
            if (request.url.contains("/me/tracks")) {
                return new SpotifyWebApiClient.Response(200, -1,
                        "{\"items\":[{\"track\":{\"id\":\"liked123\",\"name\":\"Liked\","
                                + "\"duration_ms\":1000,\"artists\":[{\"name\":\"Artist\"}],"
                                + "\"album\":{\"name\":\"Album\",\"id\":\"album1\",\"images\":[]}}}],\"total\":1}");
            }
            if (request.url.contains("/me/top/tracks")) {
                return new SpotifyWebApiClient.Response(200, -1,
                        "{\"items\":[{\"id\":\"top123\",\"name\":\"Top\",\"duration_ms\":1000,"
                                + "\"artists\":[{\"name\":\"Artist\"}],\"album\":{\"name\":\"Album\","
                                + "\"id\":\"album1\",\"images\":[]}}]}");
            }
            if (request.url.contains("/me/player/recently-played")) {
                return new SpotifyWebApiClient.Response(200, -1,
                        "{\"items\":[{\"track\":{\"id\":\"recent123\",\"name\":\"Recent\","
                                + "\"duration_ms\":1000,\"artists\":[{\"name\":\"Artist\"}],"
                                + "\"album\":{\"name\":\"Album\",\"id\":\"album1\",\"images\":[]}}}]}");
            }
            return new SpotifyWebApiClient.Response(responseCode, -1, "{}");
        }
    }

    private final FakeTransport transport = new FakeTransport();

    @AfterEach
    void tearDown() {
        SpotifyWebApiClient.resetTransportForTest();
        SpotifyAuthStore.clearForTest();
        SpotifyConfigStore.clearForTest();
        SpotifyMusicService.clearForTest();
    }

    private void login(String scope) {
        SpotifyConfigStore.putForTest("test-client-id", "US");
        SpotifyAuthStore.putForTest("", "seed", 0L);
        JsonObject token = new JsonObject();
        token.addProperty("access_token", "fresh-access");
        token.addProperty("refresh_token", "refresh-token");
        token.addProperty("expires_in", 3600L);
        token.addProperty("scope", scope);
        SpotifyAuthStore.saveTokenResponse(token);
        SpotifyWebApiClient.setTransportForTest(transport);
    }

    @Test
    @DisplayName("添加到喜欢列表使用官方 library URI endpoint 与最小权限")
    void likedSongsUseLibraryEndpoint() throws IOException {
        login("user-library-modify");

        SpotifyMusicService.addTrackToPlaylist(SpotifyMusicService.LIKED_PLAYLIST_ID, "track123");

        SpotifyWebApiClient.Request request = transport.requests.get(0);
        assertEquals("PUT", request.method);
        assertTrue(request.url.startsWith(SpotifyConfigStore.API_BASE + "/me/library?"));
        assertTrue(request.url.contains("spotify%3Atrack%3Atrack123"));
        assertEquals(null, request.jsonBody);
    }

    @Test
    @DisplayName("添加到可编辑歌单使用迁移后的 /items JSON endpoint")
    void playlistsUseItemsEndpoint() throws IOException {
        login("playlist-modify-private playlist-modify-public");
        SpotifyMusicService.setWritablePlaylistIdsForTest("playlist123");

        SpotifyMusicService.addTrackToPlaylist("playlist123", "track123");

        SpotifyWebApiClient.Request request = transport.requests.get(0);
        assertEquals("POST", request.method);
        assertTrue(request.url.endsWith("/playlists/playlist123/items"));
        assertTrue(request.jsonBody.contains("\"uris\":[\"spotify:track:track123\"]"));
    }

    @Test
    @DisplayName("缺少写入 scope 时在发请求前要求重新授权")
    void missingScopeStopsBeforeRequest() {
        login("user-library-read");

        assertThrows(SpotifyMusicService.SpotifyWritePermissionException.class,
                () -> SpotifyMusicService.addTrackToPlaylist(SpotifyMusicService.LIKED_PLAYLIST_ID, "track123"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    @DisplayName("写请求收到 5xx 后不自动重放，避免重复添加")
    void writeDoesNotReplayServerFailure() {
        login("playlist-modify-private playlist-modify-public");
        SpotifyMusicService.setWritablePlaylistIdsForTest("playlist123");
        transport.responseCode = 503;

        assertThrows(SpotifyWebApiClient.SpotifyApiException.class,
                () -> SpotifyMusicService.addTrackToPlaylist("playlist123", "track123"));
        assertEquals(1, transport.requests.size());
    }

    @Test
    @DisplayName("未列入本人可写缓存的歌单 ID 在发请求前被拒绝")
    void unknownPlaylistIsRejected() {
        login("playlist-modify-private playlist-modify-public");
        SpotifyMusicService.setWritablePlaylistIdsForTest("playlist123");

        assertThrows(IllegalArgumentException.class,
                () -> SpotifyMusicService.addTrackToPlaylist("someoneElsesPlaylist", "track123"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    @DisplayName("Spotify 主页把热门、喜欢和最近播放合成歌曲推荐流，不返回账号歌单")
    void homeRecommendationsAreTrackFeed() {
        login("user-library-read user-top-read user-read-recently-played");

        List<PlayList> shelves = SpotifyMusicService.accountHomeShelves();

        assertEquals(1, shelves.size());
        assertEquals("为你精选歌曲", shelves.get(0).getName());
        assertEquals("spotify-for-you", shelves.get(0).getPlatformPlaylistId());
        assertEquals(3, shelves.get(0).musics.size());
        assertTrue(shelves.get(0).isSearchMode());
    }

    @Test
    @DisplayName("OAuth scope 包含歌单与喜欢列表写入权限")
    void oauthRequestsWriteScopes() {
        List<String> scopes = new ArrayList<>();
        Collections.addAll(scopes, SpotifyConfigStore.SCOPES);

        assertTrue(scopes.contains("playlist-modify-private"));
        assertTrue(scopes.contains("playlist-modify-public"));
        assertTrue(scopes.contains("user-library-modify"));

        login(String.join(" ", scopes));
        assertTrue(SpotifyAuthStore.snapshot().hasPlaylistModifyScopes());
        assertTrue(SpotifyAuthStore.snapshot().hasLibraryModifyScope());
    }
}
