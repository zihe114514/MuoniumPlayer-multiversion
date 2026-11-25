package com.muoniumplayer.core.audio.output;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class OpenAlPcmSinkTest {
    @Test void ownsEveryDriverCallOnOneDedicatedThreadAndClosesOnce() throws Exception {
        FakeDriver driver = new FakeDriver();
        OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver);
        sink.write(new float[32], 0, 16);
        sink.drain();
        sink.close();
        sink.close();
        assertNotEquals(Thread.currentThread(), driver.owner);
        assertEquals(1, driver.closes);
        assertEquals(1, driver.opens);
        assertEquals(0, sink.latencySeconds());
        assertThrows(IOException.class, () -> sink.write(new float[2], 0, 1));
    }

    @Test void copiesCallerDataAndReusesOneUploadBlockAcrossRingWrap() throws Exception {
        FakeDriver driver = new FakeDriver();
        try (OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver)) {
            sink.pause();
            float[] first = {0.125f, -0.25f, 0.5f, -0.75f};
            sink.write(first, 0, 2);
            java.util.Arrays.fill(first, 99f);
            sink.resume();
            float[] wrapped = new float[24000];
            for (int i = 0; i < wrapped.length; i++) wrapped[i] = i / 24000f;
            sink.write(wrapped, 0, wrapped.length / 2);
            sink.drain();
            assertEquals(0.125f, driver.received.get(0));
            assertEquals(-0.25f, driver.received.get(1));
            assertEquals(0.5f, driver.received.get(2));
            assertEquals(-0.75f, driver.received.get(3));
            for (int i = 0; i < wrapped.length; i++) assertEquals(wrapped[i], driver.received.get(i + 4));
            assertEquals(1, driver.inputIdentities.size(), "Worker must reuse its preallocated block");
            assertTrue(driver.maximumPending <= 4);
        }
    }

    @Test void stopWakesBoundedWriterAndResumeStartsAFreshQueue() throws Exception {
        FakeDriver driver = new FakeDriver();
        try (OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver)) {
            sink.pause();
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread writer = blockedWriter(sink, result);
            await(() -> sink.latencySeconds() >= OpenAlPcmSink.RING_FRAMES / 48000.0);
            assertTrue(writer.isAlive(), "Queue backpressure must actually block");
            assertEquals(OpenAlPcmSink.RING_FRAMES / 48000.0, sink.latencySeconds(), 1e-9);
            sink.stop();
            writer.join(3000);
            assertFalse(writer.isAlive());
            assertInstanceOf(InterruptedIOException.class, result.get());
            assertEquals(0, sink.latencySeconds());
            assertThrows(InterruptedIOException.class, () -> sink.write(new float[2], 0, 1));
            sink.resume();
            sink.write(new float[] {0.25f, -0.25f}, 0, 1);
            sink.drain();
            assertEquals(List.of(0.25f, -0.25f), driver.received);
        }
    }

    @Test void closeWakesWriterWhilePausedAndDoesNotNeedProducerLock() throws Exception {
        FakeDriver driver = new FakeDriver();
        OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver);
        sink.pause();
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread writer = blockedWriter(sink, result);
        await(() -> sink.latencySeconds() >= OpenAlPcmSink.RING_FRAMES / 48000.0);
        sink.close();
        writer.join(3000);
        assertFalse(writer.isAlive());
        assertInstanceOf(IOException.class, result.get());
        assertEquals(1, driver.closes);
    }

    @Test void interruptedWriteRetainsFlagAndCloseStillWaitsForCleanup() throws Exception {
        FakeDriver driver = new FakeDriver();
        OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver);
        sink.pause();
        AtomicReference<Throwable> result = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { sink.write(new float[(OpenAlPcmSink.RING_FRAMES + 1) * 2], 0, OpenAlPcmSink.RING_FRAMES + 1); }
            catch (Throwable e) { result.set(e); }
            try { sink.close(); } catch (IOException e) { result.set(e); }
            flag.set(Thread.currentThread().isInterrupted());
        });
        writer.start();
        await(() -> sink.latencySeconds() >= OpenAlPcmSink.RING_FRAMES / 48000.0);
        writer.interrupt();
        writer.join(3000);
        assertFalse(writer.isAlive());
        assertInstanceOf(InterruptedIOException.class, result.get());
        assertEquals(Boolean.TRUE, flag.get());
        assertEquals(1, driver.closes);
    }

    @Test void flushCancelsOldWriteAndPreservesPauseAndGain() throws Exception {
        FakeDriver driver = new FakeDriver();
        try (OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver)) {
            sink.pause();
            sink.setGain(0.125f);
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread writer = blockedWriter(sink, result);
            await(() -> sink.latencySeconds() >= OpenAlPcmSink.RING_FRAMES / 48000.0);
            sink.flush();
            writer.join(3000);
            assertFalse(writer.isAlive());
            assertInstanceOf(InterruptedIOException.class, result.get());
            assertTrue(driver.paused);
            assertEquals(0.125f, driver.gain);
            assertEquals(0, sink.latencySeconds());
            sink.write(new float[] {0.5f, -0.5f}, 0, 1);
            assertThrows(IOException.class, sink::drain);
            sink.resume();
            sink.drain();
            assertEquals(List.of(0.5f, -0.5f), driver.received);
        }
    }

    @Test void outputFailureWakesBlockedProducerAndAlwaysCloses() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.failSubmit = true;
        OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver);
        assertThrows(IOException.class, () -> {
            sink.write(new float[40000], 0, 20000);
            sink.drain();
        });
        assertThrows(IOException.class, sink::close);
        assertEquals(1, driver.closes);
    }

    @Test void stoppingAWaitingDrainIsCancellationRatherThanNaturalCompletion() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.consume = false;
        try (OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver)) {
            sink.write(new float[2048], 0, 1024);
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread drain = new Thread(() -> {
                try { sink.drain(); } catch (Throwable e) { result.set(e); }
            }, "test-pcm-drain");
            drain.setDaemon(true);
            drain.start();
            await(() -> drain.getState() == Thread.State.WAITING);
            sink.stop();
            drain.join(3000);
            assertFalse(drain.isAlive());
            assertInstanceOf(InterruptedIOException.class, result.get());
        }
    }

    @Test void stalledDeviceFailsInsteadOfHangingProducerForever() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.consume = false;
        OpenAlPcmSink sink = OpenAlPcmSink.open(48000, 2, driver);
        IOException failure = assertThrows(IOException.class,
                () -> sink.write(new float[40000], 0, 20000));
        assertTrue(failure.getMessage().contains("stopped consuming"));
        assertThrows(IOException.class, sink::close);
        assertEquals(1, driver.closes);
    }

    @Test void failedInitializationIsCleanedBeforeOpenReturns() {
        FakeDriver driver = new FakeDriver();
        driver.failOpen = true;
        assertThrows(IOException.class, () -> OpenAlPcmSink.open(48000, 2, driver));
        assertEquals(1, driver.closes);
    }

    @Test void validatesFramesRatesChannelsGainAndStoppedDrain() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> OpenAlPcmSink.open(0, 2, new FakeDriver()));
        assertThrows(IllegalArgumentException.class, () -> OpenAlPcmSink.open(48000, 5, new FakeDriver()));
        FakeDriver driver = new FakeDriver();
        try (OpenAlPcmSink sink = OpenAlPcmSink.open(96000, 6, driver)) {
            assertEquals(96000, sink.sampleRate());
            assertEquals(6, sink.channels());
            assertEquals(6, sink.outputChannels());
            assertTrue(sink.usesFloat32());
            assertThrows(IndexOutOfBoundsException.class, () -> sink.write(new float[5], 0, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> sink.write(new float[6], 1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> sink.write(new float[6], 0, Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> sink.setGain(Float.NaN));
            assertThrows(IllegalArgumentException.class, () -> sink.setGain(-0.1f));
            assertThrows(IllegalArgumentException.class, () -> sink.setGain(1.1f));
            sink.stop();
            assertThrows(InterruptedIOException.class, sink::drain);
        }
    }

    @Test void capabilityMasksDoNotInventLayoutsOrPhysicalHardware() {
        PcmOutputCapabilities caps = new PcmOutputCapabilities((1 << 1) | (1 << 2) | (1 << 6), 1 << 2, false);
        assertTrue(caps.supportsChannels(1));
        assertTrue(caps.supportsChannels(2));
        assertTrue(caps.supportsChannels(6));
        assertFalse(caps.supportsChannels(8));
        assertFalse(caps.supportsChannels(4));
        assertFalse(caps.supportsChannels(34));
        assertFalse(caps.supportsFloat32(6));
        assertTrue(caps.supportsPcm16(6));
    }

    private static Thread blockedWriter(OpenAlPcmSink sink, AtomicReference<Throwable> result) {
        Thread writer = new Thread(() -> {
            try { sink.write(new float[(OpenAlPcmSink.RING_FRAMES + 1) * sink.channels()], 0, OpenAlPcmSink.RING_FRAMES + 1); }
            catch (Throwable e) { result.set(e); }
        }, "test-pcm-producer");
        writer.setDaemon(true);
        writer.start();
        return writer;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= until) fail("Condition did not become true");
            Thread.sleep(1);
        }
    }

    static final class FakeDriver implements PcmOutputDriver {
        Thread owner;
        volatile boolean paused;
        boolean consume = true;
        boolean failOpen;
        boolean failSubmit;
        int opens;
        int closes;
        int channels;
        int pending;
        int pendingFrames;
        int maximumPending;
        volatile float gain = 1f;
        final List<Float> received = new ArrayList<>();
        final Set<float[]> inputIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
        private void check() { assertEquals(owner, Thread.currentThread()); }
        @Override public void open(int rate, int channels) throws IOException {
            owner = Thread.currentThread(); opens++; this.channels = channels;
            if (failOpen) throw new IOException("test open failure");
        }
        @Override public PcmOutputCapabilities capabilities() { check(); return new PcmOutputCapabilities(326, 326, true); }
        @Override public int outputChannels() { check(); return channels; }
        @Override public boolean usesFloat32() { check(); return true; }
        @Override public int freeBuffers() {
            check();
            if (!paused && consume) { pending = 0; pendingFrames = 0; }
            return 4 - pending;
        }
        @Override public void submit(float[] samples, int frames) throws IOException {
            check();
            if (failSubmit) throw new IOException("test upload failure");
            assertTrue(pending < 4);
            pending++; pendingFrames += frames; maximumPending = Math.max(maximumPending, pending);
            inputIdentities.add(samples);
            for (int i = 0; i < frames * channels; i++) received.add(samples[i]);
        }
        @Override public int queuedFrames() { check(); return pendingFrames; }
        @Override public void setGain(float gain) { check(); this.gain = gain; }
        @Override public void pause() { check(); paused = true; }
        @Override public void resume() { check(); paused = false; }
        @Override public void clear() { check(); pending = pendingFrames = 0; }
        @Override public void close() { check(); closes++; }
    }
}
