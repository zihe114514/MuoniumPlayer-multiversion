package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the authenticated-source home/search/heart wiring without starting Minecraft or networking. */
class AuthenticatedSourceHomeStaticTest {

    private static String read(String relative) throws IOException {
        Path file = ProjectPaths.sharedJava().resolve(relative.replace('/', java.io.File.separatorChar));
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("主页展示 YouTube、Spotify 与 Apple 的推荐歌曲，而非账号歌单集合")
    void authenticatedHomeProvidersAreWired() throws IOException {
        String home = read("com/muoniumplayer/core/screens/ncm/panels/HomePanel.java");
        assertTrue(home.contains("loadYouTubeHome()") && home.contains("accountRecommendations(60)"));
        assertTrue(home.contains("loadSpotifyHome()") && home.contains("accountHomeShelves()"));
        assertTrue(home.contains("recommendationSongShelves()") && home.contains("AppleChartsService")
                && home.contains("chartTracks("));
        assertTrue(home.contains("singleTrackFeed(displayedPlayLists)")
                && home.contains("new MusicWidget(this.pendingHomeTracks.get(index)")
                && home.contains("ScrollPanel.Alignment.VERTICAL"),
                "Recommendation results must render as playable song rows on home, not a playlist card");
        assertFalse(home.contains("getUserPlaylistsSnapshot()"), "Account playlists must not be merged into home recommendations");
        assertFalse(home.contains("refreshUserPlaylists()"), "Home recommendation loading must not refresh the account sidebar library");
    }

    @Test
    @DisplayName("三家海外音源都接入搜索或收藏入口")
    void sourceActionsAreWired() throws IOException {
        String navigation = read("com/muoniumplayer/core/screens/ncm/panels/NavigateBar.java");
        String heart = read("com/muoniumplayer/core/screens/ncm/panels/LikeButton.java");
        assertTrue(navigation.contains("MusicPlatform.SPOTIFY") && navigation.contains("MusicPlatform.APPLE"));
        assertTrue(heart.contains("SpotifyMusicService.setTrackLiked"));
        assertTrue(heart.contains("YouTubeMusicService.setTrackLiked"));
        assertTrue(heart.contains("AppleMusicService.setTrackLiked"));
    }

    @Test
    @DisplayName("Spotify 主页最近播放权限与登录授权清单保持一致")
    void spotifyRecentPlaybackScopeIsDeclared() throws IOException {
        String config = read("com/muoniumplayer/core/ncm/spotify/SpotifyConfigStore.java");
        String auth = read("com/muoniumplayer/core/ncm/spotify/SpotifyAuthStore.java");
        assertTrue(config.contains("user-read-recently-played"));
        assertTrue(auth.contains("hasRecentPlaybackScope"));
    }
}
