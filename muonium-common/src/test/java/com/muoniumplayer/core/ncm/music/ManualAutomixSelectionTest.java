package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 真跑后台准备的取消/失败发布以及淡化数值；不打开网络、Minecraft 或音频设备。 */
class ManualAutomixSelectionTest {
    private Class<?> ownerType;
    private Class<?> selectionType;
    private Object owner;
    private PlaybackSession session;
    private List<Music> oldQueue;
    private long oldGeneration;
    private Thread oldPlayThread;
    private Music oldPlaying;

    @BeforeEach
    void setUp() throws Exception {
        ownerType = Class.forName(CloudMusic.class.getName() + "$PlayThread");
        selectionType = Class.forName(ownerType.getName() + "$ManualSelection");
        oldQueue = CloudMusic.playList;
        oldPlayThread = CloudMusic.playThread;
        oldPlaying = CloudMusic.currentlyPlaying;
        oldGeneration = CloudMusic.generation.get();
        List<Music> queue = new ArrayList<>();
        CloudMusic.playList = queue;
        Constructor<?> constructor = ownerType.getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        owner = constructor.newInstance(queue, 0);
        session = new PlaybackSession(CloudMusic.generation.incrementAndGet(), "test", 1L);
        set(owner, "session", session);
    }

    @AfterEach
    void tearDown() throws Exception {
        call(owner, "cancelManualSelection");
        CloudMusic.playList = oldQueue;
        CloudMusic.playThread = oldPlayThread;
        CloudMusic.currentlyPlaying = oldPlaying;
        CloudMusic.generation.set(oldGeneration);
        Thread.interrupted();
    }

    @Test
    void cancelledBeforeWorkerStartsDoesNotResolveAnything() throws Exception {
        Music song = new StubSong() {
            @Override public Tuple<String, String> getPlayUrl() { fail("cancelled request resolved a URL"); return null; }
        };
        Object selection = selection(song);
        set(owner, "manualSelection", selection);
        call(owner, "cancelManualSelection");
        prepare(selection);
        assertNull(get(selection, "ready"));
        assertNull(get(selection, "failure"));
        assertTrue(session.isActive());
    }

    @Test
    void failureIsPublishedWithoutReplacingOldQueueOrSession() throws Exception {
        RuntimeException failure = new IllegalStateException("controlled resolver failure");
        Music song = new StubSong() {
            @Override public Tuple<String, String> getPlayUrl() { throw failure; }
        };
        List<Music> playingQueue = CloudMusic.playList;
        Object selection = selection(song);
        set(owner, "manualSelection", selection);
        prepare(selection);
        assertSame(failure, get(selection, "failure"));
        assertNull(get(selection, "ready"));
        assertSame(playingQueue, CloudMusic.playList);
        assertSame(session, get(owner, "session"));
        assertTrue(session.isActive());
    }

    @Test
    void lateResolverThatIgnoresInterruptCannotPublishOverNewSelection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Music song = new StubSong() {
            @Override public Tuple<String, String> getPlayUrl() {
                entered.countDown();
                boolean waiting = true;
                while (waiting) {
                    try { release.await(); waiting = false; }
                    catch (InterruptedException ignored) { /* simulate a non-cooperative provider */ }
                }
                throw new IllegalStateException("obsolete result");
            }
        };
        Object first = selection(song);
        Object latest = selection(new StubSong());
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { prepare(first); } catch (Throwable failure) { workerFailure.set(failure); }
        });
        worker.setDaemon(true);
        set(first, "worker", worker);
        set(owner, "manualSelection", first);
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            call(owner, "cancelManualSelection");
            set(owner, "manualSelection", latest);
        } finally {
            release.countDown();
            worker.join(2000L);
        }
        assertFalse(worker.isAlive());
        assertNull(workerFailure.get());
        assertNull(get(first, "ready"));
        assertNull(get(first, "failure"));
        assertSame(latest, get(owner, "manualSelection"));
        assertTrue(session.isActive());
    }

    @Test
    void invalidatedPlaybackSessionCannotStartResolution() throws Exception {
        Object selection = selection(new StubSong() {
            @Override public Tuple<String, String> getPlayUrl() { fail("invalid session resolved a URL"); return null; }
        });
        set(owner, "manualSelection", selection);
        session.invalidate();
        prepare(selection);
        assertNull(get(selection, "failure"));
        assertNull(get(selection, "ready"));
    }

    @Test
    void manualFadeStartsAtBeginningWithSpectralHandoverWithoutAnalysis() throws Exception {
        Method method = ownerType.getDeclaredMethod("manualSelectionPlan");
        method.setAccessible(true);
        AutomixPlan plan = (AutomixPlan) method.invoke(null);
        assertEquals(0L, plan.incomingCueMillis);
        assertEquals(1_200L, plan.overlapMillis);
        assertEquals(0f, plan.incomingGain(0));
        assertEquals(1f, plan.outgoingGain(0));
        assertEquals(1f, plan.incomingGain(1));
        assertEquals(0f, plan.outgoingGain(1), 1e-6);
        for (int i = 0; i <= 100; i++) {
            double progress = i / 100.0;
            double incoming = plan.incomingGain(progress);
            double outgoing = plan.outgoingGain(progress);
            double hump = Math.sin(progress * Math.PI);
            assertEquals(Math.pow(10.0, -1.25 * hump * hump / 10.0),
                    incoming * incoming + outgoing * outgoing, 1e-6);
            assertEquals(0f, plan.outgoingVocalDepth(progress));
            if (progress > .20) {
                assertTrue(plan.outgoingMidDb(progress) < 0f);
                assertTrue(plan.outgoingHighDb(progress) < 0f);
            }
            if (progress < .20) assertTrue(plan.incomingBassDb(progress) < 0f);
            if (progress > .20) assertTrue(plan.outgoingBassDb(progress) <= 0f);
            assertEquals(0f, plan.incomingVocalDepth(progress));
            assertEquals(1f, plan.outgoingTempo(progress, plan.overlapMillis));
            assertEquals(1f, plan.outgoingPitch(progress, plan.overlapMillis));
        }
    }

    @Test
    void presentationImmediatelyShowsSelectionWithoutChangingAudibleSessionAndRollsBack() throws Exception {
        Music audible = new StubSong();
        Music selected = new StubSong();
        CloudMusic.currentlyPlaying = audible;
        CloudMusic.playThread = (Thread) owner;
        Object selection = selection(selected);
        set(owner, "manualSelection", selection);
        PlaybackPresentation preview = CloudMusic.playbackPresentation();
        assertSame(selected, preview.displayTrack);
        assertSame(audible, preview.audibleTrack);
        assertTrue(preview.preparing);
        assertSame(audible, CloudMusic.currentlyPlaying);
        assertSame(session, get(owner, "session"));
        call(owner, "cancelManualSelection");
        PlaybackPresentation restored = CloudMusic.playbackPresentation();
        assertSame(audible, restored.displayTrack);
        assertFalse(restored.preparing);
    }

    @Test
    void invalidOrSupersededSelectionCannotRemainHighlighted() throws Exception {
        CloudMusic.playThread = (Thread) owner;
        Music first = new StubSong();
        Music latest = new StubSong();
        set(owner, "manualSelection", selection(first));
        assertSame(first, CloudMusic.playbackPresentation().displayTrack);
        set(owner, "manualSelection", selection(latest));
        assertSame(latest, CloudMusic.playbackPresentation().displayTrack);
        session.invalidate();
        assertFalse(CloudMusic.playbackPresentation().preparing);
        assertSame(CloudMusic.currentlyPlaying, CloudMusic.playbackPresentation().displayTrack);
    }

    private Object selection(Music song) throws Exception {
        Constructor<?> constructor = selectionType.getDeclaredConstructor(List.class, int.class,
                boolean.class, PlaybackSession.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new ArrayList<>(Collections.singletonList(song)), 0, true, session);
    }

    private void prepare(Object selection) throws Exception {
        Method method = ownerType.getDeclaredMethod("prepareManualSelection", selectionType);
        method.setAccessible(true);
        method.invoke(owner, selection);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static class StubSong extends Music {
        StubSong() {
            super("manual-test", "manual-test", "", 991L, Collections.emptyList(), Collections.emptyList(),
                    new Album(991L, "test", "", Collections.emptyList()),
                    180_000L, 0L, 0L, Collections.emptyList());
        }
        @Override public Tuple<String, String> getPlayUrl() { throw new AssertionError("unexpected resolution"); }
    }
}
