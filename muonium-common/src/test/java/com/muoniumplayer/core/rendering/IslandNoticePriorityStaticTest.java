package com.muoniumplayer.core.rendering;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandNoticePriorityStaticTest {
    private static final Path ISLAND = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java"));

    @Test
    void schedulerHasFourExplicitPriorityLanes() throws Exception {
        String source = Files.readString(ISLAND);
        assertTrue(source.contains("enum NoticePriority"));
        assertTrue(source.contains("LOW(0), NORMAL(1), INSTANT(2), HIGH(3)"));
        assertTrue(source.contains("isHighPriorityNotice"));
        assertTrue(source.contains("enqueueQueuedNoticeLocked"));
        assertTrue(source.contains("notice.priority.rank"));
        assertTrue(source.contains("IslandNoticeRules.preempts"));
    }

    @Test
    void preemptedEventsKeepVisibleBudgetAndFiniteTtl() throws Exception {
        String source = Files.readString(ISLAND);
        assertTrue(source.contains("noticeExpiresAt"));
        assertTrue(source.contains("NOTICE_VISIBLE_CLOCK"));
        assertTrue(source.contains("preserveActiveQueuedNoticeLocked(now)"));
        assertTrue(source.contains("remainingVisibleMillis"));
        assertTrue(source.contains("restoreQueuedNoticeLocked(next,now)"));
        assertTrue(source.contains("sourceNoticeExpiresAt"));
        assertTrue(source.contains("noticeRevision != revision"));
    }

    @Test
    void cancellationIsScopedToTheOperationChannel() throws Exception {
        String source = Files.readString(ISLAND);
        int start = source.indexOf("public static void cancelTranscode()");
        int end = source.indexOf("/** 转码卡片", start);
        String cancel = source.substring(start, end);
        assertTrue(cancel.contains("dropQueuedOperationLocked(channelKey)"));
        assertTrue(cancel.contains("noticeChannelKey.equals(channelKey)"));
        assertTrue(!cancel.contains("NOTICE_QUEUE.clear()"));
    }

    @Test
    void completedPersistentOperationRemovesOnlyItsStaleLoader() throws Exception {
        String source = Files.readString(ISLAND);
        int start = source.indexOf("private static void completePersistentNotice");
        int end = source.indexOf("private static NoticePriority", start);
        String method = source.substring(start, end);
        assertTrue(method.contains("dropQueuedOperationLocked(channelKey)"));
        assertTrue(method.contains("noticeChannelKey.equals(channelKey)"));
    }

    @Test
    void watchdogAndPostRenderReceiptProtectAgainstSilentLoss() throws Exception {
        String source = Files.readString(ISLAND);
        assertTrue(source.contains("timedOut"));
        assertTrue(source.contains("timeoutNotice(waiting)"));
        assertTrue(source.contains("recordNoticePresentation(sourceNoticeRevision, presented, System.currentTimeMillis())"));
        assertTrue(source.contains("NOTICE_VISIBLE_CLOCK.pause()"));
        assertTrue(source.contains("completionVisibleClock.record(System.currentTimeMillis())"));
    }
}
