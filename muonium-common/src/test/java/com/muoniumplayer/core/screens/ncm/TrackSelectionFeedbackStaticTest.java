package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

class TrackSelectionFeedbackStaticTest {
    private static String read(String relative) throws Exception {
        return Files.readString(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/" + relative)));
    }

    @Test void barUsesOneVisualSnapshotAndDoesNotSeekTheOutgoingSongWhilePreparing() throws Exception {
        String bar = read("core/screens/ncm/panels/ControlsBar.java");
        assertTrue(bar.contains("presentation = CloudMusic.playbackPresentation()"));
        assertTrue(bar.contains("presentation.displayTrack.getName()"));
        assertTrue(bar.contains("Music displayed = presentation.displayTrack"));
        assertTrue(bar.contains("Music playing = presentation.displayTrack"));
        int seek = bar.indexOf("CloudMusic.seekToMillis(");
        int loading = bar.lastIndexOf("if (presentation.preparing)", seek);
        assertTrue(loading > 0 && bar.substring(loading, seek).contains("return;"));
        assertTrue(bar.contains("if (presentation.preparing) return"));
        int fillStart = bar.indexOf("private final class ProgressStrip");
        int fillEnd = bar.indexOf("private final class VolumePopover", fillStart);
        assertTrue(fillStart >= 0 && fillEnd > fillStart);
        String fill = bar.substring(fillStart, fillEnd);
        int preparing = fill.indexOf("if (presentation.preparing)");
        assertTrue(preparing >= 0 && fill.indexOf("return;", preparing) < fill.indexOf("double fraction"),
                "准备中只画等待轨并提前返回，不画旧歌的进度");
        assertFalse(fill.contains("!CloudMusic.hasPlaybackClock()"), "恢复态要展示冻结进度，不要求音频时钟");
        assertTrue(bar.contains("selectionMotion.offset()"));
        assertTrue(bar.contains("selectionMotion.contentAlpha()"));
    }

    @Test void rowHighlightsTheRequestedSongAndClearsOldHighlightEvenWhileHovered() throws Exception {
        String row = read("core/screens/ncm/panels/MusicWidget.java");
        assertTrue(row.contains("boolean selected = presentation.isSelected(music)"));
        assertTrue(row.contains("rrPlayingIndicator.setAlpha((float) selectionEmphasis)"));
        assertTrue(row.contains("!selected && this.isHovering()"));
        assertFalse(row.contains("else if (this.isHovering())"));
        assertTrue(row.contains("selectionMotion.sweep()"));
        assertTrue(row.contains("selectionMotion.pulse(bar)"));
        assertFalse(row.contains("selectionBadge"), "播放行不再显示独立的正在播放标签");
        assertFalse(row.contains("CloudMusic.currentlyPlaying.equals(music)"));
    }

    @Test void hudNeverPairsTheNewTitleWithOldLyricsOrOldPosition() throws Exception {
        String hud = read("core/widget/impl/MusicInfoWidget.java");
        assertTrue(hud.contains("Music playingMusic = presentation.displayTrack"));
        assertTrue(hud.contains("!presentation.preparing && this.turnComposerIntoLyric.getValue()"));
        int preparing = hud.indexOf("if (presentation.preparing) {");
        int progress = hud.indexOf("double totalTimeMillis = CloudMusic.durationMsNow();", preparing);
        assertTrue(preparing >= 0 && progress > preparing);
        assertFalse(hud.substring(preparing, progress).contains("CloudMusic.hasPlaybackClock()"),
                "准备态仍独立展示，非准备态的冻结进度不能被音频时钟清零");
        assertTrue(hud.contains("准备中 · 原曲继续播放"));
    }

    @Test void renderSnapshotCannotChangeAudioSessionOrPerformNetworkWork() throws Exception {
        String cloud = read("core/ncm/music/CloudMusic.java");
        int start = cloud.indexOf("public static PlaybackPresentation playbackPresentation()");
        int end = cloud.indexOf("private static List<Music> selectedPlaybackQueue()", start);
        assertTrue(start > 0 && end > start);
        String snapshot = cloud.substring(start, end);
        assertFalse(snapshot.contains("beginNewSession("));
        assertFalse(snapshot.contains("currentlyPlaying ="));
        assertFalse(snapshot.contains("loadMusicCover("));
        assertFalse(snapshot.contains("getPlayUrl("));
        assertFalse(snapshot.contains("synchronized"));
        assertTrue(snapshot.contains("owner.isSessionUsable(selection.session)"));
    }
}
