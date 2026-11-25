package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.AudioInfo;
import com.muoniumplayer.core.audio.ffmpeg.CancellationToken;
import com.muoniumplayer.core.audio.ffmpeg.FfmpegAudioDecoder;
import com.muoniumplayer.core.audio.ffmpeg.PcmBlock;
import com.muoniumplayer.core.audio.output.OpenAlPcmSink;
import com.muoniumplayer.core.audio.output.StreamingPcmSink;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Bounded live URI playback: common FFmpeg decoder to common OpenAL sink, without a disk audio cache.
 * The radio worker alone opens, reads and closes decoder sessions. Controls only change state,
 * cancel a token and interrupt that worker; they never free native objects or wait for network IO.
 * Pausing discards the current connection/output; resuming opens the live edge, without timeshift.
 * Radio remains outside EngineOutputTap, so it is not injected into voice-chat broadcast.
 *
 * All Listener and onClosed callbacks originate on this player's daemon worker, without the
 * control lock. LiveStreamSession dispatches its UI and optional external listeners to main.
 * A successful sink submission proves acceptance, not physical hardware playback.
 */
public class LiveStreamPlayer {
    private final Object control = new Object();
    private final String streamUrl;
    private final String stationName;
    private final Listener listener;
    private final Services services;
    private final long startedAtNanos;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile boolean playing;
    private volatile float volume;
    private volatile long decodedFrameCount;
    private Thread worker;
    private Attempt activeAttempt;
    private long generation;
    private Runnable onClosed;
    private boolean finished;
    private boolean stopRequested;

    public interface Listener {
        /** First PCM block was accepted by the sink; worker callback, once per player. */
        void onStarted(LiveStreamPlayer player);
        /** Unexpected terminal failure, after cleanup; worker callback, at most once. */
        void onStreamEnded(LiveStreamPlayer player, String reason);
    }

    interface DecoderSession extends AutoCloseable {
        AudioInfo info();
        int read(PcmBlock block) throws IOException;
        @Override void close() throws IOException;
    }

    interface Backend {
        DecoderSession open(String uri, CancellationToken cancellation) throws IOException;
        StreamingPcmSink sink(AudioInfo info) throws IOException;
    }

    record Services(Backend backend, DoubleSupplier focusGain, LongSupplier nanoTime,
                    int maxFailures, long retryMillis) {
        Services {
            Objects.requireNonNull(backend);
            Objects.requireNonNull(focusGain);
            Objects.requireNonNull(nanoTime);
            if (maxFailures < 1 || retryMillis < 0) throw new IllegalArgumentException("Invalid retry policy");
        }
    }

    private static final class NativeBackend implements Backend {
        @Override public DecoderSession open(String uri, CancellationToken token) throws IOException {
            token.throwIfCancelled();
            var input = RadioStreamPolicy.input(uri);
            token.throwIfCancelled();
            FfmpegAudioDecoder decoder = FfmpegAudioDecoder.open(input, RadioStreamPolicy.output(), token);
            return new DecoderSession() {
                @Override public AudioInfo info() { return decoder.info(); }
                @Override public int read(PcmBlock block) throws IOException { return decoder.read(block); }
                @Override public void close() { decoder.close(); }
            };
        }
        @Override public StreamingPcmSink sink(AudioInfo info) throws IOException {
            return OpenAlPcmSink.open(info.outputSampleRate(), info.outputChannels());
        }
    }

    public LiveStreamPlayer(String streamUrl, String stationName, float initialVolume, Listener listener) {
        this(streamUrl, stationName, initialVolume, listener,
                new Services(new NativeBackend(), FocusVolumeDucker::gain, System::nanoTime,
                        RadioStreamPolicy.MAX_CONSECUTIVE_FAILURES, RadioStreamPolicy.RETRY_MILLIS));
    }

    LiveStreamPlayer(String streamUrl, String stationName, float initialVolume, Listener listener, Services services) {
        this.streamUrl = RadioStreamPolicy.uri(streamUrl).toASCIIString();
        this.stationName = stationName == null ? "" : stationName;
        this.volume = RadioStreamPolicy.volume(initialVolume);
        this.listener = listener;
        this.services = Objects.requireNonNull(services);
        this.startedAtNanos = services.nanoTime().getAsLong();
    }

    public void start() {
        synchronized (control) {
            if (!closed) ensureWorker();
        }
    }

    private void ensureWorker() {
        if (worker != null) return;
        worker = new Thread(this::runLoop, "MuoniumPlayer-LiveStream");
        worker.setDaemon(true);
        worker.start();
    }

    public void pause() {
        synchronized (control) {
            if (closed || paused) return;
            paused = true;
            generation++;
            cancelAttempt();
        }
    }

    public void resume() {
        synchronized (control) {
            if (closed || !paused) return;
            paused = false;
            generation++;
            control.notifyAll();
        }
    }

    public boolean isPaused() { return paused; }
    /** Logical playback after first acceptance; buffering is not a hardware playback measurement. */
    public boolean isPlaying() { return !closed && !paused && playing; }
    public boolean isClosed() { return closed; }
    public String getStationName() { return stationName; }

    /** Session elapsed time includes connecting, pauses and reconnects; never a media duration. */
    public long elapsedMillis() {
        return Math.max(0L, (services.nanoTime().getAsLong() - startedAtNanos) / 1_000_000L);
    }

    /** Decoded PCM block count for diagnostics, not compressed frames or a playback clock. */
    public long decodedFrameCount() { return decodedFrameCount; }
    public void setVolume(float value) { volume = RadioStreamPolicy.volume(value); }
    public float getVolume() { return volume; }

    /** Installed before start; late registration after termination is ignored. */
    public void setOnClosed(Runnable callback) {
        synchronized (control) {
            if (!finished) onClosed = callback;
        }
    }

    /** Nonblocking, idempotent cancellation. Cleanup and callbacks remain on the worker. */
    public void close() {
        synchronized (control) {
            if (closed) return;
            stopRequested = true;
            closed = true;
            paused = false;
            generation++;
            cancelAttempt();
            // Closing before start still has one well-defined callback origin.
            ensureWorker();
            control.notifyAll();
        }
    }

    private void cancelAttempt() {
        if (activeAttempt != null) {
            activeAttempt.token.cancel();
            if (worker != null) worker.interrupt();
        }
        control.notifyAll();
    }

    private record Attempt(long generation, CancellationToken token) { }

    private Attempt nextAttempt() throws InterruptedException {
        synchronized (control) {
            while (paused && !closed) {
                try { control.wait(); }
                catch (InterruptedException interrupted) {
                    if (!closed && !paused) throw interrupted;
                }
            }
            if (closed) return null;
            // Control interrupts belong to the previous generation, not the new connection.
            Thread.interrupted();
            activeAttempt = new Attempt(generation, new CancellationToken());
            return activeAttempt;
        }
    }

    private boolean current(Attempt attempt) {
        synchronized (control) {
            return !closed && !paused && generation == attempt.generation && !attempt.token.isCancelled();
        }
    }

    private void runLoop() {
        int failures = 0;
        boolean announcedStart = false;
        String terminalReason = null;
        try {
            for (;;) {
                Attempt attempt = nextAttempt();
                if (attempt == null) break;
                DecoderSession decoder = null;
                StreamingPcmSink sink = null;
                Throwable failure = null;
                boolean cleanupFailed = false;
                try {
                    decoder = services.backend().open(streamUrl, attempt.token);
                    if (current(attempt)) {
                        AudioInfo info = decoder.info();
                        sink = services.backend().sink(info);
                        PcmBlock block = new PcmBlock(RadioStreamPolicy.BLOCK_FRAMES, info.outputChannels());
                        while (current(attempt)) {
                            int frames = decoder.read(block);
                            if (!current(attempt)) break;
                            if (frames == -1) throw new EOFException("Live input ended");
                            if (frames <= 0 || frames > block.capacityFrames()) throw new IOException("Invalid PCM frame count");
                            decodedFrameCount++;
                            applyOutputGain(block.samples(), frames * block.channels());
                            sink.write(block.samples(), 0, frames);
                            if (!current(attempt)) break;
                            failures = 0;
                            playing = true;
                            if (!announcedStart) {
                                announcedStart = true;
                                if (listener != null) callback(() -> listener.onStarted(this));
                            }
                        }
                    }
                } catch (IOException | RuntimeException | LinkageError problem) {
                    failure = problem;
                } finally {
                    // A pause/close interrupt must not abort the sink's owner-thread cleanup wait.
                    Thread.interrupted();
                    Throwable sinkFailure = closeOwned(sink);
                    Throwable decoderFailure = closeOwned(decoder);
                    cleanupFailed = sinkFailure != null || decoderFailure != null;
                    if (failure == null) failure = sinkFailure != null ? sinkFailure : decoderFailure;
                    synchronized (control) {
                        if (activeAttempt == attempt) activeAttempt = null;
                    }
                }
                if (cleanupFailed) {
                    terminalReason = "直播音频资源释放失败";
                    break;
                }
                if (!current(attempt)) continue;
                if (failure == null) continue;
                boolean interrupted = failure instanceof InterruptedIOException && !(failure instanceof SocketTimeoutException);
                if (interrupted || failure instanceof RuntimeException || failure instanceof LinkageError
                        || RadioStreamPolicy.channelGone(failure) || ++failures >= services.maxFailures()) {
                    terminalReason = interrupted ? "直播播放已中断" : RadioStreamPolicy.failureReason(failure);
                    break;
                }
                synchronized (control) {
                    if (current(attempt) && services.retryMillis() > 0) control.wait(services.retryMillis());
                }
            }
        } catch (InterruptedException interrupted) {
            synchronized (control) {
                if (!stopRequested) terminalReason = "直播播放已中断";
            }
        } finally {
            finish(terminalReason);
        }
    }

    /** Gain is applied before every submission, including the first; no EngineOutputTap is used. */
    private void applyOutputGain(float[] samples, int validSamples) {
        double focus = services.focusGain().getAsDouble();
        float gain = volume * (Double.isFinite(focus) ? (float) Math.max(0d, Math.min(1d, focus)) : 0f);
        for (int i = 0; i < validSamples; i++) samples[i] *= gain;
    }

    private static Throwable closeOwned(AutoCloseable owned) {
        if (owned == null) return null;
        for (;;) {
            try { owned.close(); return null; }
            catch (InterruptedIOException interrupted) {
                if (!Thread.interrupted()) return interrupted;
                // OpenAL has already requested owner cleanup; wait for that same owner again.
            } catch (Exception | LinkageError failure) { return failure; }
        }
    }

    private void finish(String reason) {
        Runnable closedCallback;
        boolean reportFailure;
        synchronized (control) {
            if (finished) return;
            finished = true;
            closed = true;
            paused = false;
            playing = false;
            activeAttempt = null;
            reportFailure = !stopRequested && reason != null;
            closedCallback = onClosed;
            onClosed = null;
        }
        try {
            try {
                if (reportFailure && listener != null) callback(() -> listener.onStreamEnded(this, reason));
            } finally {
                if (closedCallback != null) callback(closedCallback);
            }
        } finally { terminated.countDown(); }
    }

    private static void callback(Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException ignored) { /* A subscriber cannot take ownership of audio cleanup. */ }
    }

    boolean awaitTermination(long timeoutMillis) throws InterruptedException {
        return terminated.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
