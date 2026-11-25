package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.AudioInfo;
import com.muoniumplayer.core.audio.ffmpeg.CancellationToken;
import com.muoniumplayer.core.audio.ffmpeg.PcmBlock;
import com.muoniumplayer.core.audio.output.PcmOutputCapabilities;
import com.muoniumplayer.core.audio.output.StreamingPcmSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class LiveStreamPlayerTest {
    private static final AudioInfo INFO = new AudioInfo(0, "fake", "fake", 48000, 2, "stereo",
            48000, 2, "stereo", -1, false, Map.of());
    private final List<LiveStreamPlayer> players = new ArrayList<>();
    private final AtomicReference<Double> focus = new AtomicReference<>(1d);
    private final AtomicLong clock = new AtomicLong(100_000_000L);

    @AfterEach void stopWorkers() throws Exception {
        for (LiveStreamPlayer player : players) player.close();
        for (LiveStreamPlayer player : players) assertTrue(player.awaitTermination(3000), "radio worker must finish");
    }

    @Test void startedFollowsAcceptedBlockAndCallbacksHaveWorkerOrigin() throws Exception {
        Backend backend = new Backend();
        Plan plan = backend.add();
        List<String> events = new CopyOnWriteArrayList<>();
        plan.sink.onWrite = () -> events.add("accepted");
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicInteger started = new AtomicInteger();
        LiveStreamPlayer player = player(backend, new LiveStreamPlayer.Listener() {
            @Override public void onStarted(LiveStreamPlayer p) {
                events.add("started"); callbackThread.set(Thread.currentThread()); started.incrementAndGet();
            }
            @Override public void onStreamEnded(LiveStreamPlayer p, String reason) { fail(reason); }
        }, 3, 0);
        AtomicInteger closed = new AtomicInteger();
        player.setOnClosed(() -> { assertSame(plan.owner, Thread.currentThread()); closed.incrementAndGet(); });
        player.start();
        await(plan.reading);
        assertEquals(0, started.get());
        plan.data(3);
        until(() -> started.get() == 1);
        assertEquals(List.of("accepted", "started"), events);
        assertSame(plan.owner, callbackThread.get());
        assertNotSame(Thread.currentThread(), callbackThread.get());
        player.close();
        assertTrue(player.awaitTermination(3000));
        player.close(); player.start();
        assertEquals(1, started.get()); assertEquals(1, closed.get());
        assertSame(plan.owner, plan.closedBy);
        assertSame(plan.owner, plan.sink.closedBy);
    }

    @Test void failedFirstSubmissionDoesNotAnnounceStartAndFailureIsExactlyOnce() throws Exception {
        Backend backend = new Backend(); Plan plan = backend.add();
        plan.sink.writeFailure = new IOException("fake output rejection");
        AtomicInteger started = new AtomicInteger(), ended = new AtomicInteger(), closed = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(started, ended), 1, 0);
        player.setOnClosed(closed::incrementAndGet);
        plan.data(1); player.start();
        assertTrue(player.awaitTermination(3000));
        assertEquals(0, started.get()); assertEquals(1, ended.get()); assertEquals(1, closed.get());
        assertTrue(plan.closed); assertTrue(plan.sink.closed); assertTrue(player.isClosed());
        player.close(); assertEquals(1, closed.get());
    }

    @Test void closeCancelsBlockedReadWithoutFreeingItOnCaller() throws Exception {
        Backend backend = new Backend(); Plan plan = backend.add();
        AtomicInteger ended = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(new AtomicInteger(), ended), 2, 0);
        player.start(); await(plan.reading);
        long before = System.nanoTime(); player.close();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500, "close must only signal");
        assertTrue(player.awaitTermination(3000));
        assertTrue(plan.token.isCancelled()); assertEquals(0, ended.get());
        assertSame(plan.owner, plan.closedBy); assertSame(plan.owner, plan.sink.closedBy);
    }

    @Test void closeCancelsBlockedOpenWithoutCrossThreadRelease() throws Exception {
        Backend backend = new Backend(); backend.openGate = new CountDownLatch(1);
        LiveStreamPlayer player = player(backend, null, 2, 0);
        player.start(); await(backend.openEntered); player.close();
        assertTrue(player.awaitTermination(3000));
        assertTrue(backend.openToken.isCancelled()); assertEquals(1, backend.opens.get());
        assertEquals(0, backend.sinks.get());
    }

    @Test void pauseDiscardsConnectionAndResumeReopensWithoutRepeatingStarted() throws Exception {
        Backend backend = new Backend(); Plan first = backend.add(), second = backend.add();
        AtomicInteger started = new AtomicInteger(), ended = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(started, ended), 3, 0);
        first.data(2); player.start(); until(() -> started.get() == 1);
        player.pause(); until(() -> first.closed);
        assertTrue(player.isPaused()); assertFalse(player.isClosed()); assertFalse(player.isPlaying());
        assertEquals(1, backend.opens.get()); assertTrue(first.token.isCancelled());
        second.data(2); player.resume(); until(() -> second.sink.writes.get() == 1);
        assertEquals(2, backend.opens.get()); assertEquals(1, started.get()); assertEquals(0, ended.get());
        assertTrue(player.isPlaying());
        player.close(); assertTrue(player.awaitTermination(3000));
        assertSame(first.owner, first.closedBy); assertSame(second.owner, second.closedBy);
    }

    @Test void pauseBeforeStartAndRapidPauseResumeDoNotEndThePlayer() throws Exception {
        Backend backend = new Backend(); Plan first = backend.add(), second = backend.add();
        LiveStreamPlayer player = player(backend, null, 3, 0);
        player.pause(); player.start();
        assertEquals(0, backend.opens.get());
        player.resume(); await(first.reading);
        player.pause(); player.resume();
        await(second.reading);
        assertTrue(first.closed); assertFalse(player.isClosed());
    }

    @Test void controlsWakeRetryWaitWithoutTurningPauseIntoFailure() throws Exception {
        Backend backend = new Backend(); Plan first = backend.add(), second = backend.add();
        first.eof();
        LiveStreamPlayer player = player(backend, null, 3, 60_000);
        player.start(); until(() -> first.closed);
        player.pause(); player.resume(); await(second.reading);
        assertFalse(player.isClosed()); assertEquals(2, backend.opens.get());
    }

    @Test void consecutiveEmptyEofRetriesAreBounded() throws Exception {
        Backend backend = new Backend();
        for (int i = 0; i < 3; i++) backend.add().eof();
        AtomicInteger ended = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(new AtomicInteger(), ended), 3, 0);
        player.start(); assertTrue(player.awaitTermination(3000));
        assertEquals(3, backend.opens.get()); assertEquals(1, ended.get());
        assertTrue(backend.plans.stream().allMatch(p -> p.closed && p.sink.closed));
    }

    @Test void acceptedAudioResetsConsecutiveFailureBudget() throws Exception {
        Backend backend = new Backend(); backend.add().eof();
        Plan recovered = backend.add(); recovered.data(1); recovered.eof(); backend.add().eof();
        AtomicInteger started = new AtomicInteger(), ended = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(started, ended), 2, 0);
        player.start(); assertTrue(player.awaitTermination(3000));
        assertEquals(3, backend.opens.get()); assertEquals(1, started.get()); assertEquals(1, ended.get());
    }

    @Test void concurrentControlsCannotPublishMoreThanOneTerminalOrCrossFree() throws Exception {
        Backend backend = new Backend(); backend.dynamic = true; Plan first = backend.add();
        AtomicInteger ended = new AtomicInteger(), closed = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(new AtomicInteger(), ended), 3, 0);
        player.setOnClosed(closed::incrementAndGet); player.start(); await(first.reading);
        CountDownLatch go = new CountDownLatch(1);
        Runnable controls = () -> {
            try { go.await(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            for (int i = 0; i < 100; i++) { player.pause(); player.resume(); }
            player.close();
        };
        Thread a = new Thread(controls, "fake-radio-ui-a"), b = new Thread(controls, "fake-radio-ui-b");
        a.start(); b.start(); go.countDown(); a.join(3000); b.join(3000);
        assertFalse(a.isAlive()); assertFalse(b.isAlive()); assertTrue(player.awaitTermination(3000));
        assertEquals(1, closed.get()); assertEquals(0, ended.get()); assertTrue(player.isClosed());
        for (Plan plan : backend.plans) {
            if (plan.owner != null) { assertTrue(plan.closed); assertSame(plan.owner, plan.closedBy); }
        }
    }

    @Test void channelGoneFailsFastAndDoesNotExposeSignedUrl() throws Exception {
        Backend backend = new Backend(); backend.openFailure = new IOException("Server returned 404 Not Found https://radio.test/?secret=value");
        AtomicReference<String> reason = new AtomicReference<>();
        LiveStreamPlayer player = player(backend, new LiveStreamPlayer.Listener() {
            @Override public void onStarted(LiveStreamPlayer p) { fail(); }
            @Override public void onStreamEnded(LiveStreamPlayer p, String r) { reason.set(r); }
        }, 12, 0);
        player.start(); assertTrue(player.awaitTermination(3000));
        assertEquals(1, backend.opens.get()); assertTrue(reason.get().contains("404/410"));
        assertFalse(reason.get().contains("secret")); assertFalse(reason.get().contains("https://"));
    }

    @Test void firstAndLaterBlocksApplyVolumeAndCurrentFocusOnlyToValidSamples() throws Exception {
        Backend backend = new Backend(); Plan plan = backend.add();
        LiveStreamPlayer player = player(backend, null, 2, 0);
        focus.set(.25d); player.setVolume(.8f);
        plan.data(2); player.start(); until(() -> plan.sink.writes.get() == 1);
        assertArrayEquals(new float[]{.2f, .2f, .2f, .2f}, plan.sink.last, 1e-6f);
        assertEquals(9f, plan.lastBlock.samples()[4], "unused buffer tail is not multiplied");
        focus.set(.5d); player.setVolume(.4f); plan.data(1);
        until(() -> plan.sink.writes.get() == 2);
        assertArrayEquals(new float[]{.2f, .2f}, plan.sink.last, 1e-6f);
        player.setVolume(Float.NaN); plan.data(1); until(() -> plan.sink.writes.get() == 3);
        assertArrayEquals(new float[]{0f, 0f}, plan.sink.last);
        assertEquals(0f, player.getVolume());
    }

    @Test void manyBlocksReuseOneBoundedPcmBlockAndDoNotSpoolTheStream() throws Exception {
        Backend backend = new Backend(); Plan plan = backend.add();
        for (int i = 0; i < 2000; i++) plan.data(1);
        LiveStreamPlayer player = player(backend, null, 2, 0);
        player.start(); until(() -> plan.sink.writes.get() == 2000);
        assertEquals(1, plan.distinctBlocks.get());
        assertEquals(RadioStreamPolicy.BLOCK_FRAMES, plan.lastBlock.capacityFrames());
        assertEquals(2000, player.decodedFrameCount());
        assertEquals("https://radio.test/live.m3u8?token=kept", backend.receivedUri);
    }

    @Test void cancellingBlockedSinkWriteClosesDecoderAndSinkOnWorker() throws Exception {
        Backend backend = new Backend(); Plan plan = backend.add(); plan.sink.writeGate = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger(), ended = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(started, ended), 2, 0);
        plan.data(1); player.start(); await(plan.sink.writeEntered); player.close();
        assertTrue(player.awaitTermination(3000));
        assertEquals(0, started.get()); assertEquals(0, ended.get());
        assertSame(plan.owner, plan.closedBy); assertSame(plan.owner, plan.sink.closedBy);
    }

    @Test void cleanupFailureDoesNotOpenAnotherOutputAndCallbacksStillFinish() throws Exception {
        Backend backend = new Backend(); Plan plan = backend.add(); plan.sink.closeFailure = new IOException("cleanup"); plan.eof();
        AtomicInteger ended = new AtomicInteger(), closed = new AtomicInteger();
        LiveStreamPlayer player = player(backend, listener(new AtomicInteger(), ended), 3, 0);
        player.setOnClosed(closed::incrementAndGet); player.start(); assertTrue(player.awaitTermination(3000));
        assertEquals(1, backend.opens.get()); assertEquals(1, ended.get()); assertEquals(1, closed.get());
        assertTrue(plan.closed);
    }

    @Test void closeBeforeStartUsesWorkerAndLateCloseHookCannotReviveCallbacks() throws Exception {
        Backend backend = new Backend(); AtomicInteger closed = new AtomicInteger(); AtomicReference<Thread> origin = new AtomicReference<>();
        LiveStreamPlayer player = player(backend, null, 2, 0);
        player.setOnClosed(() -> { origin.set(Thread.currentThread()); closed.incrementAndGet(); });
        player.close(); assertTrue(player.awaitTermination(3000));
        player.setOnClosed(() -> fail("late callback")); player.close(); player.start();
        assertNotSame(Thread.currentThread(), origin.get()); assertEquals(1, closed.get()); assertEquals(0, backend.opens.get());
    }

    @Test void listenerExceptionCannotSuppressCloseNotification() throws Exception {
        Backend backend = new Backend(); backend.add().eof(); AtomicInteger closed = new AtomicInteger();
        LiveStreamPlayer player = player(backend, new LiveStreamPlayer.Listener() {
            @Override public void onStarted(LiveStreamPlayer p) { throw new IllegalStateException("subscriber"); }
            @Override public void onStreamEnded(LiveStreamPlayer p, String r) { throw new IllegalStateException("subscriber"); }
        }, 1, 0);
        player.setOnClosed(closed::incrementAndGet); player.start(); assertTrue(player.awaitTermination(3000));
        assertEquals(1, closed.get());
    }

    @Test void elapsedClockIncludesPauseAndDoesNotDependOnWallClock() {
        Backend backend = new Backend(); LiveStreamPlayer player = player(backend, null, 2, 0);
        clock.addAndGet(5_000_000_000L); player.pause();
        assertEquals(5000, player.elapsedMillis()); clock.addAndGet(2_000_000_000L);
        assertEquals(7000, player.elapsedMillis());
    }

    private LiveStreamPlayer player(Backend backend, LiveStreamPlayer.Listener listener, int retries, long delay) {
        LiveStreamPlayer player = new LiveStreamPlayer("https://radio.test/live.m3u8?token=kept", "Station", 1f,
                listener, new LiveStreamPlayer.Services(backend, () -> focus.get(), clock::get, retries, delay));
        players.add(player); return player;
    }
    private static LiveStreamPlayer.Listener listener(AtomicInteger started, AtomicInteger ended) {
        return new LiveStreamPlayer.Listener() {
            @Override public void onStarted(LiveStreamPlayer p) { started.incrementAndGet(); }
            @Override public void onStreamEnded(LiveStreamPlayer p, String reason) { ended.incrementAndGet(); }
        };
    }
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS), "timed out"); }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "timed out");
    }
    private static InterruptedIOException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt(); return new InterruptedIOException("fake cancelled");
    }

    private static final class Backend implements LiveStreamPlayer.Backend {
        final List<Plan> plans = new CopyOnWriteArrayList<>();
        final AtomicInteger opens = new AtomicInteger(), sinks = new AtomicInteger();
        final CountDownLatch openEntered = new CountDownLatch(1);
        volatile CancellationToken openToken;
        volatile String receivedUri;
        volatile IOException openFailure;
        volatile CountDownLatch openGate;
        boolean dynamic;
        Plan current;
        Plan add() { Plan plan = new Plan(); plans.add(plan); return plan; }
        @Override public LiveStreamPlayer.DecoderSession open(String uri, CancellationToken token) throws IOException {
            receivedUri = uri; openToken = token; int index = opens.getAndIncrement(); openEntered.countDown();
            try { if (openGate != null) openGate.await(); }
            catch (InterruptedException e) { throw interrupted(e); }
            token.throwIfCancelled();
            if (openFailure != null) throw openFailure;
            if (index >= plans.size() && dynamic) add();
            if (index >= plans.size()) throw new IOException("No fake connection left");
            current = plans.get(index); current.owner = Thread.currentThread(); current.token = token;
            return current;
        }
        @Override public StreamingPcmSink sink(AudioInfo info) { sinks.incrementAndGet(); return current.sink; }
    }
    private static final class Plan implements LiveStreamPlayer.DecoderSession {
        final BlockingQueue<Integer> chunks = new LinkedBlockingQueue<>();
        final CountDownLatch reading = new CountDownLatch(1);
        final Sink sink = new Sink();
        final AtomicInteger distinctBlocks = new AtomicInteger();
        volatile Thread owner, closedBy;
        volatile boolean closed;
        volatile PcmBlock lastBlock;
        CancellationToken token;
        void data(int frames) { chunks.add(frames); }
        void eof() { chunks.add(-1); }
        @Override public AudioInfo info() { return INFO; }
        @Override public int read(PcmBlock block) throws IOException {
            assertSame(owner, Thread.currentThread());
            if (lastBlock != block) distinctBlocks.incrementAndGet(); lastBlock = block;
            reading.countDown();
            try {
                int frames = chunks.take(); token.throwIfCancelled();
                if (frames > 0) { Arrays.fill(block.samples(), 9f); Arrays.fill(block.samples(), 0, frames * block.channels(), 1f); }
                return frames;
            } catch (InterruptedException e) { throw interrupted(e); }
        }
        @Override public void close() { assertSame(owner, Thread.currentThread()); closedBy = Thread.currentThread(); closed = true; }
    }
    private static final class Sink implements StreamingPcmSink {
        final AtomicInteger writes = new AtomicInteger();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        volatile float[] last;
        volatile boolean closed;
        volatile Thread closedBy;
        CountDownLatch writeGate;
        IOException writeFailure, closeFailure;
        Runnable onWrite;
        @Override public int sampleRate() { return 48000; }
        @Override public int channels() { return 2; }
        @Override public PcmOutputCapabilities capabilities() { return null; }
        @Override public int outputChannels() { return 2; }
        @Override public boolean usesFloat32() { return true; }
        @Override public void write(float[] pcm, int offset, int frames) throws IOException {
            writeEntered.countDown();
            try { if (writeGate != null) writeGate.await(); }
            catch (InterruptedException e) { throw interrupted(e); }
            if (writeFailure != null) throw writeFailure;
            last = Arrays.copyOfRange(pcm, offset, offset + frames * channels());
            if (onWrite != null) onWrite.run(); writes.incrementAndGet();
        }
        @Override public void setGain(float gain) { fail("radio gain is applied to each valid PCM block"); }
        @Override public void pause() { fail("radio controls reconnect instead of cross-thread sink calls"); }
        @Override public void resume() { fail("radio controls reconnect instead of cross-thread sink calls"); }
        @Override public void stop() { fail("radio controls reconnect instead of cross-thread sink calls"); }
        @Override public void flush() { fail("radio controls reconnect instead of cross-thread sink calls"); }
        @Override public void drain() { fail("live EOF must not drain an old connection"); }
        @Override public double latencySeconds() { return 0; }
        @Override public void close() throws IOException {
            closedBy = Thread.currentThread(); closed = true; if (closeFailure != null) throw closeFailure;
        }
    }
}
