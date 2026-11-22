package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline wiring guards for QQ entry points and account actions. */
class QQActionWiringStaticTest {
    private static final Path JAVA = ProjectPaths.sharedJava();

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(JAVA.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String method(String source, String signature, String nextSignature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + signature);
        int end = source.indexOf(nextSignature, start + signature.length());
        assertTrue(end > start, "missing method boundary after: " + signature);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("QQ 侧栏 exposes both private FM and radio hub")
    void qqDiscoveryHasFmAndRadioEntries() throws IOException {
        String source = read("com/muoniumplayer/core/screens/ncm/panels/NavigateBar.java");
        String qq = method(source, "if (qqMode) {", "if (qishuiMode) {");
        assertTrue(qq.contains("nav:fm") && qq.contains("new PersonalFmPanel()"));
        assertTrue(qq.contains("nav:radio") && qq.contains("RadioHubPanel::new"));
    }

    @Test
    @DisplayName("QQ private FM does not depend on a NetEase profile")
    void qqFmUsesQqAuthentication() throws IOException {
        String source = read("com/muoniumplayer/core/ncm/music/PersonalFmManager.java");
        String request = method(source, "private static void requestBatch(",
                "private static void requestKuGouBatch(");
        assertTrue(request.contains("platform == MusicPlatform.QQ"));
        assertTrue(request.contains("isLoggedIn(MusicPlatform.QQ)"));
        assertTrue(request.contains("platform != MusicPlatform.QQ"));
        assertTrue(request.contains("CloudMusic.profile == null"));

        String panel = read("com/muoniumplayer/core/screens/ncm/panels/PersonalFmPanel.java");
        assertTrue(panel.contains("private boolean treeQQ"));
        assertTrue(panel.contains("this.treeQishui || this.treeQQ"),
                "QQ must not display NetEase's unrelated six FM modes");
        assertTrue(panel.contains("if (this.treeQQ) return;"),
                "QQ must not display NetEase scene groups on its FM page");
    }

    @Test
    @DisplayName("QQ Add-to-playlist loads and displays QQ account playlists")
    void addPlaylistOverlayLoadsQqTargets() throws IOException {
        String overlay = read("com/muoniumplayer/core/screens/ncm/AddToPlaylistOverlay.java");
        String sourceList = method(overlay, "private List<PlayList> playListsForSource()", "private void submitToPlaylist(");
        assertTrue(sourceList.contains("music.isQQ()"));
        assertTrue(sourceList.contains("getQQWritableUserPlaylistsSnapshot()"));
        assertTrue(overlay.contains("ensureQQUserPlaylistsAsync"));
        assertTrue(overlay.contains("updateQQPlaylistTrack"));

        String service = read("com/muoniumplayer/core/ncm/music/NeteaseMusicService.java");
        String writable = method(service, "public static List<PlayList> getQQWritableUserPlaylistsSnapshot()",
                "public static boolean areQQUserPlaylistsLoaded()");
        assertTrue(writable.contains("playlist.isSubscribed()) continue"));
        assertTrue(writable.contains("getPlatformPlaylistId()"));
    }

    @Test
    @DisplayName("QQ write actions use the source song ID, not the player's synthetic stable ID")
    void qqWritesUseOriginalSongId() throws IOException {
        String source = read("com/muoniumplayer/core/ncm/music/NeteaseMusicService.java");
        String add = method(source, "public static boolean updateQQPlaylistTrack(",
                "public static boolean setQQLike(");
        String like = method(source, "public static boolean setQQLike(",
                "private static long parseQQSongId(");
        String parser = method(source, "private static long parseQQSongId(",
                "private static long parseQQPlaylistId(");
        assertTrue(add.contains("parseQQSongId(music)"));
        assertTrue(like.contains("parseQQSongId(music)"));
        assertTrue(parser.contains("music.getSourceId()"));
        assertTrue(!parser.contains("music.getId()"));
    }

    @Test
    @DisplayName("QQ favorite writes use the raw QQ song ID")
    void qqLikeUsesOriginalSongId() throws IOException {
        String source = read("com/muoniumplayer/core/ncm/music/NeteaseMusicService.java");
        String like = method(source, "public static boolean setQQLike(",
                "private static long parseQQSongId(");
        assertTrue(like.contains("parseQQSongId(music)"));
        assertTrue(like.contains("music.getSourceMid()"));
        assertTrue(!like.contains("setLike(songId, music.getSourceId(), liked)"));
        String widget = read("com/muoniumplayer/core/screens/ncm/panels/LikeButton.java");
        assertTrue(widget.contains("if (music.isQQ()) return NeteaseMusicService.setQQLike(music, liked)"));
    }
}
