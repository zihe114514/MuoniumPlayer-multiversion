package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.AudioInfo;
import com.muoniumplayer.core.audio.ffmpeg.CancellationToken;
import com.muoniumplayer.core.audio.output.StreamingPcmSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LiveStreamSessionTest {
    @Test void refusalDoesNotStopOrReplaceExistingPlayback() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        assertTrue(session.start("old", "Old", "https://radio.test/old", null));
        Player old = host.players.get(0);
        host.refusal = "一起听房间";
        assertFalse(session.start("new", "New", "https://radio.test/new", null));
        assertSame("old", session.activeStation()); assertFalse(old.closed);
        assertEquals(1, host.stops); assertEquals(List.of("一起听房间"), host.failures);
    }

    @Test void invalidOrMissingUriDoesNotStopCurrentStation() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        session.start("old", "Old", "https://radio.test/old", null);
        assertFalse(session.start("new", "New", "file:///tmp/list.m3u8", null));
        assertFalse(session.start("new", "New", null, null));
        assertEquals("Old", session.activeStationName()); assertFalse(host.players.get(0).closed);
        assertEquals(1, host.stops);
    }

    @Test void oldEndedAndCloseCallbacksCannotClearNewStation() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        session.start("old", "Old", "https://radio.test/old", null);
        Player old = host.players.get(0);
        session.start("new", "New", "https://radio.test/new.m3u8", null);
        Player current = host.players.get(1);
        old.listener.onStreamEnded(old, "late old failure"); old.closedCallback.run();
        host.flush();
        assertTrue(session.isActive()); assertSame("new", session.activeStation());
        assertEquals("New", session.activeStationName()); assertEquals(0, host.failures.size());
        assertTrue(old.closed); assertFalse(current.closed);
    }

    @Test void queuedOldStartedNotificationAndExternalCallbackAreDroppedAfterReplacement() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        AtomicInteger starts = new AtomicInteger(), ends = new AtomicInteger();
        session.start("old", "Old", "https://radio.test/old", listener(starts, ends));
        Player old = host.players.get(0); old.listener.onStarted(old);
        assertEquals(0, starts.get()); assertTrue(host.started.isEmpty());
        session.start("new", "New", "https://radio.test/new", null); host.flush();
        assertEquals(0, starts.get()); assertTrue(host.started.isEmpty()); assertTrue(host.failures.isEmpty());
    }

    @Test void failureClearsOnlyItsOwnerAndIsNotDeliveredAfterNewStartOrStop() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        AtomicInteger starts = new AtomicInteger(), ends = new AtomicInteger();
        session.start("old", "Old", "https://radio.test/old", listener(starts, ends));
        Player old = host.players.get(0); old.listener.onStreamEnded(old, "ended");
        assertFalse(session.isActive());
        session.start("new", "New", "https://radio.test/new", null); host.flush();
        assertEquals(0, ends.get()); assertTrue(host.failures.isEmpty());
        Player current = host.players.get(1); current.listener.onStreamEnded(current, "ended");
        session.stop(); host.flush(); assertTrue(host.failures.isEmpty());
    }

    @Test void failureNotificationAndOptionalListenerUseExplicitHostDispatchExactlyOnce() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        AtomicInteger starts = new AtomicInteger(), ends = new AtomicInteger();
        session.start("station", "Station", "https://radio.test/live", listener(starts, ends));
        Player player = host.players.get(0);
        player.listener.onStarted(player);
        assertEquals(0, starts.get()); host.flush();
        assertEquals(1, starts.get()); assertEquals(List.of("Station"), host.started);
        player.listener.onStreamEnded(player, "failure"); player.listener.onStreamEnded(player, "duplicate");
        assertEquals(0, ends.get()); host.flush();
        assertEquals(1, ends.get()); assertEquals(1, host.failures.size()); assertFalse(session.isActive());
        assertNull(session.activeStation()); assertEquals("", session.activeStationName());
    }

    @Test void stoppedPlayerCannotBeRevivedByDelayedStartedOrClosedCallback() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        session.start("station", "Station", "https://radio.test/live", null);
        Player player = host.players.get(0); session.stop(); session.stop();
        player.listener.onStarted(player); player.closedCallback.run(); host.flush();
        assertFalse(session.isActive()); assertFalse(session.isPlaying()); assertEquals(0, session.elapsedMillis());
        assertEquals(1, player.closeCalls); assertTrue(host.started.isEmpty());
    }

    @Test void pauseVolumeAndClockTargetOnlyTheActivePlayer() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        assertFalse(session.togglePause());
        session.start("station", "Station", "https://radio.test/live", null);
        Player player = host.players.get(0);
        assertEquals(.6f, player.volume); assertEquals(1234, session.elapsedMillis());
        assertTrue(session.togglePause()); assertTrue(player.paused); assertFalse(session.isPlaying());
        host.flush(); assertEquals(List.of(true), host.pauses);
        assertTrue(session.togglePause()); host.flush(); assertTrue(session.isPlaying());
        assertEquals(List.of(true, false), host.pauses);
        session.applyVolume(.2f); assertEquals(.2f, player.volume);
        session.stop(); session.applyVolume(.9f); assertEquals(.2f, player.volume);
    }

    @Test void queuedPauseToastDoesNotLeakIntoReplacement() {
        Host host = new Host(); LiveStreamSession.Controller session = new LiveStreamSession.Controller(host);
        session.start("old", "Old", "https://radio.test/old", null); session.togglePause();
        session.start("new", "New", "https://radio.test/new", null); host.flush();
        assertTrue(host.pauses.isEmpty()); assertEquals("New", session.activeStationName());
    }

    private static LiveStreamPlayer.Listener listener(AtomicInteger starts, AtomicInteger ends) {
        return new LiveStreamPlayer.Listener() {
            @Override public void onStarted(LiveStreamPlayer p) { starts.incrementAndGet(); }
            @Override public void onStreamEnded(LiveStreamPlayer p, String r) { ends.incrementAndGet(); }
        };
    }
    private static final class Host implements LiveStreamSession.Host {
        final ArrayDeque<Runnable> dispatched = new ArrayDeque<>();
        final List<Player> players = new ArrayList<>();
        final List<String> failures = new ArrayList<>(), started = new ArrayList<>();
        final List<Boolean> pauses = new ArrayList<>();
        int stops; String refusal;
        @Override public String refusal() { return refusal; }
        @Override public void stopOtherPlayback() { stops++; }
        @Override public float volume() { return .6f; }
        @Override public LiveStreamPlayer create(String uri, String name, float volume, LiveStreamPlayer.Listener listener) {
            Player player = new Player(uri, name, volume, listener); players.add(player); return player;
        }
        @Override public void dispatch(Runnable callback) { dispatched.add(callback); }
        @Override public void started(String name) { started.add(name); }
        @Override public void failed(String name, String reason) { failures.add(reason); }
        @Override public void pauseChanged(String name, boolean paused) { pauses.add(paused); }
        void flush() { while (!dispatched.isEmpty()) dispatched.remove().run(); }
    }
    private static final class Player extends LiveStreamPlayer {
        final Listener listener;
        boolean closed, paused;
        float volume;
        int closeCalls;
        Runnable closedCallback;
        Player(String uri, String name, float volume, Listener listener) {
            super(uri, name, volume, listener, new Services(new Backend() {
                @Override public DecoderSession open(String uri, CancellationToken token) { throw new AssertionError("manual player"); }
                @Override public StreamingPcmSink sink(AudioInfo info) { throw new AssertionError("manual player"); }
            }, () -> 1, System::nanoTime, 1, 0));
            this.listener = listener; this.volume = volume;
        }
        @Override public void start() { }
        @Override public void close() { if (!closed) { closed = true; closeCalls++; } }
        @Override public void setOnClosed(Runnable callback) { closedCallback = callback; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean isPaused() { return paused; }
        @Override public boolean isPlaying() { return !closed && !paused; }
        @Override public void pause() { paused = true; }
        @Override public void resume() { paused = false; }
        @Override public long elapsedMillis() { return 1234; }
        @Override public void setVolume(float volume) { this.volume = volume; }
    }
}
