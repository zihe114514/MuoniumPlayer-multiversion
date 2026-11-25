package com.muoniumplayer.core.audio.output;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One owned OpenAL output with a bounded float ring. The dedicated worker is the
 * sole native owner. Control calls never wait for the producer lock, so stop and
 * close can wake a producer blocked by a paused or stalled output.
 */
public final class OpenAlPcmSink implements StreamingPcmSink {
    static final int BLOCK_FRAMES = 1024;
    static final int RING_FRAMES = BLOCK_FRAMES * 8;
    private static final long CONTROL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long STALL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final int sampleRate;
    private final int channels;
    private final Object state = new Object();
    private final Object producer = new Object();
    private final float[] ring;
    private final float[] block;
    private final PcmOutputDriver driver;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Thread worker;
    private PcmOutputCapabilities capabilities;
    private int outputChannels;
    private boolean float32;
    private int readSample;
    private int writeSample;
    private int bufferedFrames;
    private int inFlightFrames;
    private int nativeFrames;
    private long generation;
    private long clearRequested;
    private long clearApplied;
    private long controlRequested;
    private long controlApplied;
    private boolean paused;
    private float gain = 1f;
    private boolean accepting = true;
    private boolean closing;
    private IOException failure;

    /** Requires ALC_EXT_thread_local_context; never changes the process context. */
    public static OpenAlPcmSink open(int sampleRate, int channels) throws IOException {
        return open(sampleRate, channels, new OpenAlOutputDriver());
    }

    static OpenAlPcmSink open(int sampleRate, int channels, PcmOutputDriver driver) throws IOException {
        if (sampleRate <= 0 || sampleRate > 768000) {
            throw new IllegalArgumentException("Invalid sample rate: " + sampleRate);
        }
        if (!PcmOutputCapabilities.validChannels(channels)) {
            throw new IllegalArgumentException("Expected 1/2/6/8 channels; remap the layout upstream");
        }
        OpenAlPcmSink sink = new OpenAlPcmSink(sampleRate, channels, Objects.requireNonNull(driver));
        sink.worker.start();
        try {
            if (!sink.ready.await(10, TimeUnit.SECONDS)) {
                sink.requestClose();
                throw new IOException("OpenAL output initialization timed out");
            }
        } catch (InterruptedException e) {
            sink.requestClose();
            Thread.currentThread().interrupt();
            throw interrupted("Interrupted while opening OpenAL output", e);
        }
        synchronized (sink.state) {
            if (sink.failure == null && !sink.closing) return sink;
        }
        // Initialization failure is published after owner-thread resource cleanup.
        throw sink.failure != null ? sink.failure : new IOException("OpenAL output closed during initialization");
    }

    private OpenAlPcmSink(int sampleRate, int channels, PcmOutputDriver driver) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.driver = driver;
        ring = new float[RING_FRAMES * channels];
        block = new float[BLOCK_FRAMES * channels];
        worker = new Thread(this::run, "muonium-openal-output");
        worker.setDaemon(true);
    }

    @Override public int sampleRate() { return sampleRate; }
    @Override public int channels() { return channels; }
    @Override public PcmOutputCapabilities capabilities() { return capabilities; }
    @Override public int outputChannels() { return outputChannels; }
    @Override public boolean usesFloat32() { return float32; }

    @Override
    public void write(float[] samples, int offset, int frames) throws IOException {
        Objects.requireNonNull(samples, "samples");
        if (offset < 0 || frames < 0 || offset > samples.length
                || (long) frames * channels > samples.length - offset) {
            throw new IndexOutOfBoundsException("PCM block does not contain complete frames");
        }
        synchronized (producer) {
            synchronized (state) {
                ensureWritable();
                long epoch = generation;
                int remaining = frames;
                while (remaining > 0) {
                    checkInterrupted();
                    ensureWritable();
                    if (generation != epoch) throw interrupted("PCM write cancelled by stop/flush", null);
                    if (clearApplied != clearRequested) { awaitState(0L); continue; }
                    int count = Math.min(remaining, RING_FRAMES - bufferedFrames);
                    if (count == 0) {
                        awaitState(0L);
                        continue;
                    }
                    int samplesToCopy = count * channels;
                    int first = Math.min(samplesToCopy, ring.length - writeSample);
                    System.arraycopy(samples, offset, ring, writeSample, first);
                    System.arraycopy(samples, offset + first, ring, 0, samplesToCopy - first);
                    writeSample = (writeSample + samplesToCopy) % ring.length;
                    bufferedFrames += count;
                    remaining -= count;
                    offset += samplesToCopy;
                    state.notifyAll();
                }
            }
        }
    }

    @Override public void setGain(float gain) throws IOException {
        if (!Float.isFinite(gain) || gain < 0f || gain > 1f) {
            throw new IllegalArgumentException("Gain must be finite and in [0,1]");
        }
        synchronized (state) {
            ensureOpen();
            this.gain = gain;
            awaitControl(++controlRequested);
        }
    }

    @Override public void flush() throws IOException {
        synchronized (state) {
            ensureOpen();
            generation++;
            clearRequested++;
            awaitControl(++controlRequested);
        }
    }

    @Override public void pause() throws IOException { control(true, false); }
    @Override public void resume() throws IOException { control(false, false); }
    @Override public void stop() throws IOException { control(false, true); }

    private void control(boolean pause, boolean stop) throws IOException {
        synchronized (state) {
            ensureOpen();
            paused = pause;
            if (stop) {
                accepting = false;
                generation++;
                clearRequested++;
            } else if (!pause) {
                accepting = true;
            }
            awaitControl(++controlRequested);
        }
    }

    private void awaitControl(long command) throws IOException {
        state.notifyAll();
        long until = System.nanoTime() + CONTROL_TIMEOUT_NANOS;
        while (controlApplied < command) {
            ensureOpen();
            awaitDeadline(until, "OpenAL control timed out");
        }
    }

    @Override
    public void drain() throws IOException {
        synchronized (state) {
            ensureWritable();
            if (paused) throw new IOException("Cannot drain paused OpenAL output");
            long epoch = generation;
            while (bufferedFrames + inFlightFrames + nativeFrames > 0) {
                ensureWritable();
                if (paused || generation != epoch) throw interrupted("PCM drain cancelled", null);
                awaitState(0L);
            }
            ensureWritable();
            if (paused || generation != epoch) throw interrupted("PCM drain cancelled", null);
        }
    }

    @Override public double latencySeconds() {
        synchronized (state) {
            return (bufferedFrames + inFlightFrames + nativeFrames) / (double) sampleRate;
        }
    }

    private void requestClose() {
        synchronized (state) {
            closing = true;
            accepting = false;
            generation++;
            state.notifyAll();
        }
    }

    @Override
    public void close() throws IOException {
        requestClose();
        boolean interrupted = Thread.interrupted();
        long until = System.nanoTime() + CONTROL_TIMEOUT_NANOS;
        try {
            for (;;) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) {
                    worker.interrupt();
                    throw new IOException("OpenAL owner did not finish native cleanup in time");
                }
                try {
                    if (finished.await(remaining, TimeUnit.NANOSECONDS)) break;
                } catch (InterruptedException e) {
                    // Cancellation of a decoder/writer must not skip native cleanup.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        synchronized (state) {
            if (failure != null) throw failure;
        }
    }

    private void run() {
        try {
            driver.open(sampleRate, channels);
            capabilities = driver.capabilities();
            outputChannels = driver.outputChannels();
            float32 = driver.usesFloat32();
            ready.countDown();
            long lastProgress = System.nanoTime();
            int previousNativeFrames = 0;
            for (;;) {
                synchronized (state) {
                    if (closing) break;
                    if (clearApplied != clearRequested) {
                        driver.clear();
                        bufferedFrames = inFlightFrames = nativeFrames = 0;
                        readSample = writeSample = 0;
                        clearApplied = clearRequested;
                        lastProgress = System.nanoTime();
                    }
                    if (controlApplied != controlRequested) {
                        driver.setGain(gain);
                        if (paused || !accepting) driver.pause(); else driver.resume();
                        controlApplied = controlRequested;
                        state.notifyAll();
                    }
                }
                int free = driver.freeBuffers();
                int queued = driver.queuedFrames();
                int frames = 0;
                synchronized (state) {
                    nativeFrames = queued;
                    if (!paused && accepting && free > 0 && bufferedFrames > 0) {
                        frames = Math.min(bufferedFrames, BLOCK_FRAMES);
                        int samples = frames * channels;
                        int first = Math.min(samples, ring.length - readSample);
                        System.arraycopy(ring, readSample, block, 0, first);
                        System.arraycopy(ring, 0, block, first, samples - first);
                        readSample = (readSample + samples) % ring.length;
                        bufferedFrames -= frames;
                        inFlightFrames = frames;
                    }
                    if (queued < previousNativeFrames || queued == 0 || paused || !accepting) {
                        lastProgress = System.nanoTime();
                    }
                    previousNativeFrames = queued;
                    if (System.nanoTime() - lastProgress > STALL_TIMEOUT_NANOS) {
                        throw new IOException("OpenAL output stopped consuming PCM (device unavailable)");
                    }
                    state.notifyAll();
                    if (frames == 0 && !closing && controlApplied == controlRequested) {
                        state.wait(2L);
                    }
                }
                if (frames > 0) {
                    driver.submit(block, frames);
                    lastProgress = System.nanoTime();
                    synchronized (state) {
                        nativeFrames = driver.queuedFrames();
                        inFlightFrames = 0;
                        state.notifyAll();
                    }
                }
            }
        } catch (Throwable e) {
            fail(e);
        } finally {
            try { driver.close(); } catch (Throwable e) { fail(e); }
            synchronized (state) {
                closing = true;
                accepting = false;
                bufferedFrames = inFlightFrames = nativeFrames = 0;
                state.notifyAll();
            }
            ready.countDown();
            finished.countDown();
        }
    }

    private void fail(Throwable cause) {
        IOException error = cause instanceof IOException ? (IOException) cause
                : new IOException("OpenAL output failed", cause);
        synchronized (state) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
            state.notifyAll();
        }
    }

    private void ensureOpen() throws IOException {
        if (failure != null) throw failure;
        if (closing) throw new IOException("OpenAL output is closed");
    }

    private void ensureWritable() throws IOException {
        ensureOpen();
        if (!accepting) throw interrupted("OpenAL output was stopped; resume before writing", null);
    }

    private void awaitDeadline(long until, String message) throws IOException {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) throw new IOException(message);
        awaitState(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private void awaitState(long millis) throws IOException {
        try { state.wait(millis); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted("PCM operation interrupted", e);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw interrupted("PCM write interrupted", null);
    }

    private static InterruptedIOException interrupted(String message, Throwable cause) {
        InterruptedIOException error = new InterruptedIOException(message);
        if (cause != null) error.initCause(cause);
        return error;
    }
}
