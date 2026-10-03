package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

/** 音频设备外验证接线顺序；异步状态和增益曲线由 ManualAutomixSelectionTest 真跑。 */
class ManualAutomixSelectionStaticTest {
    private String source() throws Exception {
        return Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", "CloudMusic.java")));
    }

    @Test
    void selectionIsOfferedBeforeStoppingTheOldThread() throws Exception {
        String entry = region(source(), "private static void startPlaybackList(", "public static boolean isPersonalFmActive()");
        assertTrue(entry.indexOf("requestManualSelection(") < entry.indexOf("stopExistingPlayThread()"));
        String request = region(source(), "private boolean requestManualSelection(", "private boolean manualSelectionCurrent(");
        assertTrue(request.contains("AutomixSettings.isEnabled()"));
        assertTrue(request.contains("MpPartyClient.inRoom()"));
        assertTrue(request.contains("NeteaseListenTogetherService.isActive()"));
        assertTrue(request.contains("KuGouListenTogetherService.isActive()"));
        assertTrue(request.contains("worker.start()"));
        assertFalse(request.contains("join("));
        assertFalse(request.contains("getPlayUrl()"));
        assertFalse(request.contains("player.close()"));
        assertFalse(request.contains("beginNewSession("));
    }

    @Test
    void readinessNeedsNoBeatStemOrEnvelopeAnalysis() throws Exception {
        String prepare = region(source(), "private void prepareManualSelection(", "private static AutomixPlan manualSelectionPlan()");
        assertTrue(prepare.contains("new AudioPlayer(file)"));
        assertTrue(prepare.contains("deck.setFadeGain(0.0f)"));
        assertTrue(prepare.contains("deck.prepareStart(0f)"));
        assertTrue(prepare.contains("selection.ready = track"));
        assertFalse(prepare.contains("AutomixPlanner.plan("));
        assertFalse(prepare.contains("warmAdvancedEnvelope"));
        assertFalse(prepare.contains("attachVocalMaterial"));
        assertFalse(prepare.contains("BackgroundWork"));
        assertFalse(prepare.contains("initializePlayer("));
    }

    @Test
    void pendingSelectionHasPriorityAndPauseDoesNotAutoResume() throws Exception {
        String tick = region(source(), "private void waitForPlaybackCompletion()", "private void handlePlaybackCompletion()");
        assertTrue(tick.indexOf("tryManualSelectionHandover(") < tick.indexOf("tryManualNextHandover("));
        assertTrue(tick.indexOf("tryManualSelectionHandover(") < tick.indexOf("if (!playing.get()) break"));
        assertTrue(tick.contains("if (manualSelection != null) {"));
        String handover = region(source(), "private boolean tryManualSelectionHandover(", "private void cancelManualSelection()");
        assertTrue(handover.contains("if (!outgoing.isFinished() && outgoing.isPausing()) return false"));
        assertTrue(handover.contains("cancelArmedTrack()"));
        assertFalse(handover.contains("stopCurrentPlayback()"));
    }

    @Test
    void ownershipMovesOnlyAfterSuccessfulStartAndOldFadeIsReleased() throws Exception {
        String commit = region(source(), "long overlapOverrideMillis, ManualSelection selection)", "private void driveAutomixFade()");
        int start = commit.indexOf("incoming.startPrepared()");
        int accepted = commit.indexOf("if (incoming.isPausing())");
        int previousFade = commit.indexOf("if (selection != null) finishAutomixFade()");
        int queue = commit.indexOf("playList = songs");
        int session = commit.indexOf("CloudMusic.beginNewSession(track.song)");
        assertTrue(start >= 0 && accepted > start && previousFade > accepted);
        assertTrue(queue > previousFade && session > queue);
        assertTrue(commit.contains("manualSelection != selection"));
        assertTrue(commit.contains("selection.ready = null"));
        String cancel = region(source(), "private void cancelPlayback()", "private boolean playListChanged()");
        assertTrue(cancel.contains("cancelManualSelection()"));
        String run = region(source(), "public void run()", "private boolean shouldContinuePlayback()");
        assertTrue(run.substring(run.indexOf("finally {")).contains("cancelManualSelection()"));
    }

    @Test
    void pendingSearchQueueAndCacheWritersAreHandledSeparatelyFromPlayback() throws Exception {
        String cloud = source();
        assertTrue(cloud.contains("final List<Music> installedQueue = selectedPlaybackQueue()"));
        assertTrue(cloud.contains("selectedPlaybackQueue() != installedQueue"));
        String cache = region(cloud, "static File cachedAudioFile(", "private static File cachedAudioFileLocked(");
        assertTrue(cache.contains("synchronized (audioPreparationLock(song, playUrl))"));
        assertTrue(cache.indexOf("if (!downloadIfMissing)") < cache.indexOf("synchronized"));
        String decode = region(cloud, "static File resolvePlayableAudioFile(", "private static File resolvePlayableAudioFileLocked(");
        assertTrue(decode.contains("synchronized (audioPreparationLock(song, sourceFile.getAbsolutePath()))"));
    }

    @Test
    void manualNextUsesTheSameDspAndRestoresTheSongBeginning() throws Exception {
        String cloud = source();
        String handover = region(cloud, "long overlapOverrideMillis, ManualSelection selection)",
                "private void driveAutomixFade()");
        assertTrue(handover.contains("manualTransition ? manualSelectionPlan() : track.plan"));
        assertTrue(handover.contains("if (selection == null || !track.plan.manualQuickMix) incoming.prepareStart(0f)"));
        assertTrue(handover.indexOf("incoming.resetAutomixProcessing()")
                < handover.indexOf("incoming.startPrepared()"));
        assertTrue(handover.indexOf("outgoing.enableManualTransitionProcessing()")
                > handover.indexOf("if (incoming.isPausing())"));
        assertTrue(cloud.contains("fade.plan.outgoingMidDb(progress)"));
        assertTrue(cloud.contains("fade.plan.outgoingHighDb(progress)"));
        String audio = Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", "AudioPlayer.java")));
        String cue = region(audio, "private float cueTo(", "private void primeStreamWindow(");
        assertTrue(cue.contains("this.player.cue(safeMillis / 1000F)"));
        assertFalse(cue.contains("if (safeMillis > 0.0f)"));
        String enable = region(audio, "void enableManualTransitionProcessing()", "private void attachAutomixChain()");
        assertTrue(enable.contains("!this.automixChain.isAttachedTo(this.player)"));
        assertFalse(enable.contains("resetTo("));
        assertFalse(enable.contains("resetDepthTo("));
        String attach = region(audio, "private void attachAutomixChain()", "private int channelCount()");
        assertTrue(attach.contains("if (!AutomixSettings.isEnabled()) return"));
        assertTrue(attach.contains("!this.manualTransitionProcessing"));
    }

    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, to);
        return source.substring(start, end);
    }
}
