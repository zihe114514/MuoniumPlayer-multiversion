package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real production publisher/deque without GL, config writes or sleeps. */
class IslandNoticeQueueTest {
    private static final Class<?> ISLAND = DownloadDynamicIsland.class;
    private final Map<Field, Object> saved = new HashMap<>();
    private List<Object> savedQueue;
    private long savedElapsed;
    private long savedLastPresented;

    @BeforeEach
    void isolate() throws Exception {
        // Preserve static test state; none of the public publishers used here saves user settings.
        for (Field field : ISLAND.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())
                    && (field.getName().startsWith("notice") || field.getName().startsWith("transcode")
                    || field.getName().equals("transcoding"))) {
                field.setAccessible(true);
                saved.put(field, field.get(null));
            }
        }
        savedQueue = new ArrayList<>(queue());
        savedElapsed = clockField("elapsed");
        savedLastPresented = clockField("lastPresented");
        queue().clear();
        invoke("publishActiveNoticeLocked", new Class<?>[]{typeClass(), String.class, String.class,
                boolean.class, boolean.class, long.class}, type("NONE"), "", "", false, false, now());
        set("transcoding", false);
        set("transcodeProgressAdvancedAt", 0L);
    }

    @AfterEach
    void restore() throws Exception {
        for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        queue().clear();
        queue().addAll(savedQueue);
        setClockField("elapsed", savedElapsed);
        setClockField("lastPresented", savedLastPresented);
    }

    @Test
    void highPriorityEventsNeverCoalesceAndPeersRemainFifo() throws Exception {
        publish("REFRESH_SUCCESS", "ordinary");
        publish("PLAYBACK_ERROR", "error-1");
        publish("PLAYBACK_ERROR", "error-2");
        publish("NETWORK_ERROR", "error-3");
        publish("VOLUME", "volume");
        assertEquals("error-1", get("noticeTitle"));
        assertEquals(List.of("error-2", "error-3", "volume", "ordinary"), titles());
        finishCurrent();
        assertEquals("error-2", get("noticeTitle"));
        finishCurrent();
        assertEquals("error-3", get("noticeTitle"));
    }

    @Test
    void stateChangesKeepOtherChannelsButOnlyNewestValueOfTheirOwnChannel() throws Exception {
        publish("NETWORK_ERROR", "error");
        publish("VOLUME", "volume-old");
        publish("THEME", "theme");
        publish("VOLUME", "volume-new");
        assertEquals("error", get("noticeTitle"));
        assertEquals(List.of("theme", "volume-new"), titles());
    }

    @Test
    void interruptionResumesRemainingBudgetBeforeLaterOrdinaryPeers() throws Exception {
        publish("REFRESH_SUCCESS", "first");
        visible(600L);
        long remaining = ((Long)get("noticeBudgetMillis")) - clock().elapsed();
        publish("NETWORK_SUCCESS", "second");
        publish("NETWORK_ERROR", "urgent");
        assertEquals(List.of("first", "second"), titles());
        finishCurrent();
        assertEquals("first", get("noticeTitle"));
        assertEquals(remaining, get("noticeBudgetMillis"));
        assertEquals(0L, clock().elapsed());
        publish("VOLUME", "volume");
        assertEquals("first", get("noticeTitle")); // resumed card has a brief legibility guard
        finishCurrent();
        assertEquals("volume", get("noticeTitle"));
    }

    @Test
    void hiddenWallTimeNeverConsumesOrdinaryBudget() throws Exception {
        publish("REFRESH_SUCCESS", "unseen");
        invoke("advanceQueuedNotice", new Class<?>[]{long.class}, now() + 86_400_000L);
        assertEquals("unseen", get("noticeTitle"));
        assertEquals(0L, clock().elapsed());
    }

    @Test
    void identicalHighPriorityTypeStartsFreshVisibleBudget() throws Exception {
        publish("PLAYBACK_ERROR", "one");
        publish("PLAYBACK_ERROR", "two");
        finishCurrent();
        assertEquals("two", get("noticeTitle"));
        assertEquals(0L, clock().elapsed());
        assertTrue(((Long)get("noticeBudgetMillis")) >= 1_800L);
    }

    @Test
    void lastVolumeValueKeepsAtLeastHalfSecondOfReadableTime() throws Exception {
        DownloadDynamicIsland.showVolume(10);
        visible((Long)get("noticeBudgetMillis"));
        DownloadDynamicIsland.showVolume(80);
        assertTrue((Long)get("noticeBudgetMillis") - clock().elapsed() >= 500L);
        assertEquals("80%", get("noticeValue"));
    }

    @Test
    void cancellationDoesNotClearUnrelatedErrorsOrConfirmations() throws Exception {
        DownloadDynamicIsland.beginTranscode("song", "mp3", "wav");
        publish("NETWORK_SUCCESS", "confirmation");
        publish("NETWORK_ERROR", "urgent");
        DownloadDynamicIsland.cancelTranscode();
        assertEquals("urgent", get("noticeTitle"));
        assertEquals(List.of("confirmation"), titles());
        finishCurrent();
        assertEquals("confirmation", get("noticeTitle"));
    }

    @Test
    void cancelVisibleLoaderImmediatelyPromotesPendingMessage() throws Exception {
        DownloadDynamicIsland.beginTranscode("song", "mp3", "wav");
        publish("MODEL_INFERENCE", "another-loader", true, 0L, "MODEL_INFERENCE:other");
        DownloadDynamicIsland.cancelTranscode();
        assertEquals("another-loader", get("noticeTitle"));
    }

    @Test
    void completingOnePlaylistCannotRemoveAnotherPlaylistsLoader() throws Exception {
        publish("NETWORK_ERROR", "urgent");
        DownloadDynamicIsland.showPlaylistTrackAddInProgress("A");
        DownloadDynamicIsland.showPlaylistTrackAddInProgress("B");
        DownloadDynamicIsland.showPlaylistTrackAddSuccess("A");
        assertEquals(List.of("PLAYLIST_TRACK_ADDING:B", "PLAYLIST_TRACK_ADD_SUCCESS"), keys());
        assertEquals("urgent", get("noticeTitle"));
    }

    @Test
    void completingOneModelCannotRemoveAnotherModelsLoader() throws Exception {
        publish("NETWORK_ERROR", "urgent");
        DownloadDynamicIsland.beginModelInference("A", "model");
        DownloadDynamicIsland.beginModelInference("B", "model");
        DownloadDynamicIsland.finishModelInference("A", "model", 10L);
        assertEquals(List.of("MODEL_INFERENCE:B", "MODEL_INFERENCE_SUCCESS"), keys());
    }

    @Test
    void preemptingLoaderRetainsScopedIdentityForCompletion() throws Exception {
        publish("RECENT_PLAY_SUCCESS", "telemetry");
        DownloadDynamicIsland.showPlaylistTrackAddInProgress("A");
        assertEquals("PLAYLIST_TRACK_ADDING:A", get("noticeChannelKey"));
        DownloadDynamicIsland.showPlaylistTrackAddSuccess("A");
        assertFalse((Boolean)get("noticePersistent"));
        assertEquals("PLAYLIST_TRACK_ADD_SUCCESS", ((Enum<?>)get("noticeType")).name());
        assertTrue(keys().stream().noneMatch(k -> k.equals("PLAYLIST_TRACK_ADDING:A")));
    }

    @Test
    void differentOperationsOfSameTypeReceiveIndependentDeadline() throws Exception {
        DownloadDynamicIsland.beginModelInference("A", "model");
        set("noticeDeadlineAt", now() + 1_000L);
        DownloadDynamicIsland.beginModelInference("B", "model");
        long secondDeadline = (Long) field(queue().peekFirst(), "deadlineAt");
        invoke("completePersistentNotice", new Class<?>[]{typeClass(),typeClass(),String.class,String.class,String.class},
                type("MODEL_INFERENCE"), type("MODEL_INFERENCE_SUCCESS"), "done", "A", "MODEL_INFERENCE:A");
        finishCurrent();
        assertEquals("MODEL_INFERENCE:B", get("noticeChannelKey"));
        assertEquals(secondDeadline, get("noticeDeadlineAt"));
        assertEquals(0L, clock().elapsed());
    }

    @Test
    void queuedLoaderTimesOutOnceWithFeedbackAndNeverResurrects() throws Exception {
        publish("NETWORK_ERROR", "urgent");
        DownloadDynamicIsland.beginModelInference("A", "model");
        invoke("advanceQueuedNotice", new Class<?>[]{long.class}, now() + 31_000L);
        assertEquals(List.of("MODEL_INFERENCE_FAILURE"), queuedTypes());
        invoke("advanceQueuedNotice", new Class<?>[]{long.class}, now() + 32_000L);
        assertEquals(List.of("MODEL_INFERENCE_FAILURE"), queuedTypes());
        finishCurrent();
        assertEquals("MODEL_INFERENCE_FAILURE", ((Enum<?>)get("noticeType")).name());
        assertFalse((Boolean)get("noticePersistent"));
    }

    @Test
    void repeatedQueuedUpdatesRetainOriginalWatchdogDeadline() throws Exception {
        publish("NETWORK_ERROR", "urgent");
        DownloadDynamicIsland.beginModelInference("A", "model");
        long deadline = (Long)field(queue().peekFirst(), "deadlineAt");
        DownloadDynamicIsland.beginModelInference("A", "model-new");
        assertEquals(1, queue().size());
        assertEquals(deadline, field(queue().peekFirst(), "deadlineAt"));
    }

    @Test
    void finiteRealtimeTtlDoesNotRestartAfterInterruption() throws Exception {
        publish("AUTOMIX_BLENDING", "handover", false, 5_000L, "AUTOMIX_BLENDING");
        long expiry = (Long)get("noticeExpiresAt");
        publish("VOLUME", "volume");
        finishCurrent();
        assertEquals("handover", get("noticeTitle"));
        assertEquals(expiry, get("noticeExpiresAt"));
        invoke("advanceQueuedNotice", new Class<?>[]{long.class}, expiry + 1L);
        assertEquals("NONE", ((Enum<?>)get("noticeType")).name());
    }

    @Test
    void telemetryStartAndResultShareChannelsAndBurstIsBounded() throws Exception {
        publish("NETWORK_ERROR", "urgent");
        for (int i=0; i<100; i++) {
            DownloadDynamicIsland.showNeteaseUploadStarted("听歌历史上报");
            DownloadDynamicIsland.showRecentPlayUploadSuccess(1L);
            DownloadDynamicIsland.showNeteaseUploadStarted("听歌时长上报");
            DownloadDynamicIsland.showListeningDurationUploadSuccess(100, 1L);
        }
        assertEquals(List.of("RECENT_PLAY_SUCCESS", "LISTENING_DURATION_SUCCESS"), queuedTypes());
        assertEquals(List.of("telemetry:recent", "telemetry:duration"), keys());
    }

    @Test
    void visibleTelemetryResultReplacesUploadingInsteadOfReplayingStaleLoader() throws Exception {
        DownloadDynamicIsland.showNeteaseUploadStarted("听歌历史上报");
        DownloadDynamicIsland.showRecentPlayUploadSuccess(1L);
        assertEquals("RECENT_PLAY_SUCCESS", ((Enum<?>)get("noticeType")).name());
        assertTrue(queue().isEmpty());
        assertEquals(0L, clock().elapsed());
    }

    @Test
    void staleTranscodeFinishDoesNotStopNewOperation() throws Exception {
        DownloadDynamicIsland.beginTranscode("A", "mp3", "wav");
        DownloadDynamicIsland.beginTranscode("B", "mp3", "wav");
        DownloadDynamicIsland.finishTranscode("A", "mp3", "wav", 10L);
        assertTrue((Boolean)get("transcoding"));
        assertEquals("TRANSCODING:B", get("transcodeChannelKey"));
        assertTrue(keys().contains("TRANSCODING:B"));
    }

    @Test
    void concurrentNormalAndHighPublicationsDoNotLoseUniqueEvents() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int thread=0; thread<4; thread++) {
                final int worker=thread;
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int i=0; i<40; i++) publish(i%2 == 0 ? "NETWORK_ERROR" : "NETWORK_SUCCESS", worker + "-" + i);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> job : jobs) job.get(10L, TimeUnit.SECONDS);
            Set<String> unique = new HashSet<>(titles());
            unique.add((String)get("noticeTitle"));
            assertEquals(160, unique.size());
            assertEquals(159, queue().size());
            int previous=4;
            for (Object queued:queue()) {
                int rank=(Integer)field(field(queued,"priority"),"rank");
                assertTrue(rank <= previous);
                previous=rank;
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void staleTranscodeProgressAndCancellationCannotAffectNewTrack() throws Exception {
        DownloadDynamicIsland.beginTranscode("A", "mp3", "wav");
        DownloadDynamicIsland.beginTranscode("B", "mp3", "wav");
        DownloadDynamicIsland.updateTranscodeProgress("B", .40);
        DownloadDynamicIsland.updateTranscodeProgress("A", .90);
        DownloadDynamicIsland.cancelTranscode("A");
        assertTrue((Boolean)get("transcoding"));
        assertEquals(.40, (Double)get("transcodeProgress"), .0001);
        assertEquals("TRANSCODING:B", get("noticeChannelKey"));
    }

    @Test
    void gdFallbackDisplayNameDoesNotLeaveItsOriginalLoaderBehind() throws Exception {
        publish("NETWORK_ERROR", "urgent");
        DownloadDynamicIsland.showGdSourceResolving("GD音乐台 · NETEASE");
        DownloadDynamicIsland.showGdSourceResolved("已回退 酷我", "mp3");
        assertEquals(List.of("GD_SOURCE_SUCCESS", "GD_SOURCE_HEALTHY"), queuedTypes());
    }

    @Test
    void gdFailureGenericLabelStillCompletesSpecificSourceLoader() throws Exception {
        DownloadDynamicIsland.showGdSourceResolving("GD音乐台 · NETEASE");
        DownloadDynamicIsland.showGdSourceFailure("GD音乐台", "timeout");
        assertEquals("GD_SOURCE_ERROR", ((Enum<?>)get("noticeType")).name());
        assertFalse((Boolean)get("noticePersistent"));
        assertTrue(queue().isEmpty());
    }

    @Test
    void postDrawReceiptCreditsOnlyPresentedFramesOfCurrentRevision() throws Exception {
        publish("REFRESH_SUCCESS", "first");
        long revision=(Long)get("noticeRevision");
        receipt(revision,true,1_000L);
        receipt(revision,true,1_200L);
        assertEquals(200L,clock().elapsed());
        receipt(revision,false,1_300L); // hidden/download/Automix owns the next frame
        receipt(revision,true,20_000L);
        assertEquals(200L,clock().elapsed());
        publish("NETWORK_ERROR", "second");
        long current=(Long)get("noticeRevision");
        receipt(current,true,30_000L);
        receipt(revision,false,30_100L); // stale renderer cannot pause the new card either
        receipt(current,true,30_200L);
        assertEquals(200L,clock().elapsed());
    }

    private static void receipt(long revision,boolean presented,long now) throws Exception {
        invoke("recordNoticePresentation", new Class<?>[]{long.class,boolean.class,long.class},revision,presented,now);
    }

    private void publish(String type, String title) throws Exception {
        invoke("publishNotice", new Class<?>[]{typeClass(), String.class, String.class}, type(type), title, "value");
    }
    private void publish(String type, String title, boolean persistent, long ttl, String key) throws Exception {
        invoke("publishNotice", new Class<?>[]{typeClass(),String.class,String.class,boolean.class,long.class,String.class},
                type(type),title,"value",persistent,ttl,key);
    }
    private void finishCurrent() throws Exception {
        visible((Long)get("noticeBudgetMillis") + 1L);
        invoke("advanceQueuedNotice", new Class<?>[]{long.class}, now());
    }
    private void visible(long millis) throws Exception {
        IslandVisibleClock clock=clock();
        clock.pause();
        long tick=now();
        clock.record(tick);
        while (millis>0L) {
            long delta=Math.min(200L,millis);
            tick+=delta;
            clock.record(tick);
            millis-=delta;
        }
        clock.pause();
    }
    private static long now() { return System.currentTimeMillis(); }
    private static Class<?> typeClass() throws Exception { return Class.forName(ISLAND.getName() + "$IslandNoticeType"); }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object type(String name) throws Exception { return Enum.valueOf((Class)typeClass(),name); }
    private static Object invoke(String name, Class<?>[] types, Object...args) throws Exception {
        Method method=ISLAND.getDeclaredMethod(name,types);
        method.setAccessible(true);
        return method.invoke(null,args);
    }
    private static Object get(String name) throws Exception {
        Field field=ISLAND.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    private static void set(String name,Object value) throws Exception {
        Field field=ISLAND.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null,value);
    }
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    @SuppressWarnings("unchecked")
    private static Deque<Object> queue() throws Exception { return (Deque<Object>)get("NOTICE_QUEUE"); }
    private static IslandVisibleClock clock() throws Exception { return (IslandVisibleClock)get("NOTICE_VISIBLE_CLOCK"); }
    private static long clockField(String name) throws Exception { return (Long)field(clock(),name); }
    private static void setClockField(String name,long value) throws Exception {
        Field field=IslandVisibleClock.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(clock(),value);
    }
    private List<String> titles() throws Exception { return queueStrings("title"); }
    private List<String> keys() throws Exception { return queueStrings("channelKey"); }
    private List<String> queuedTypes() throws Exception { return queueStrings("type"); }
    private List<String> queueStrings(String name) throws Exception {
        List<String> result=new ArrayList<>();
        for (Object queued:queue()) result.add(field(queued,name).toString());
        return result;
    }
}
