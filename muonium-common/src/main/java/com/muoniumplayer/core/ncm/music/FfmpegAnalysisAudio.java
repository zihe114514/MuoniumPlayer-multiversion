package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.AudioInput;
import com.muoniumplayer.core.audio.ffmpeg.CancellationToken;
import com.muoniumplayer.core.audio.ffmpeg.FfmpegAudioDecoder;
import com.muoniumplayer.core.audio.ffmpeg.OutputSpec;
import com.muoniumplayer.core.audio.ffmpeg.PcmBlock;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;

/**
 * Offline analysis using the same JavaCPP decoder as file playback. Each instance owns its
 * demuxer, codec and resampler; no live JSyn state or external executable is consulted.
 * Windows are mono at 22050 Hz, limited to 60 seconds (~5 MiB). read/seek belong to the opening
 * background thread. close first cancels blocked native work, then releases the owned handle.
 */
final class FfmpegAnalysisAudio implements AnalysisAudio {
    private static final int RATE = 22_050;
    private static final long MAX_WINDOW_MILLIS = 60_000L;
    private static final float[] EMPTY = new float[0];
    private final CancellationToken cancellation;
    private final FfmpegAudioDecoder decoder;
    private final PcmBlock block;
    private boolean closed;
    private float[] cache;
    private long cacheStartFrame = -1;

    private FfmpegAnalysisAudio(FfmpegAudioDecoder decoder, CancellationToken cancellation) {
        this.decoder = decoder;
        this.cancellation = cancellation;
        this.block = new PcmBlock(4096, 1);
    }

    static FfmpegAnalysisAudio open(File source) {
        if (source == null || !source.isFile()) return null;
        CancellationToken token = new CancellationToken();
        try {
            return new FfmpegAnalysisAudio(FfmpegAudioDecoder.open(AudioInput.file(source.toPath()),
                    OutputSpec.mono(RATE), token), token);
        } catch (IOException | LinkageError failure) {
            System.err.println("[Analysis/Native] Unable to open " + source.getName() + ": " + failure);
            return null;
        }
    }

    @Override public int sampleRate() { return RATE; }

    @Override public synchronized float[] readMono(long startMillis, long lengthMillis) {
        if (closed || lengthMillis <= 0 || cancellation.isCancelled()
                || Thread.currentThread().isInterrupted()) return EMPTY;
        long start = Math.max(0, startMillis);
        if (start > Long.MAX_VALUE / RATE) return EMPTY;
        long startFrame = start * RATE / 1000;
        int frames = (int) (Math.min(lengthMillis, MAX_WINDOW_MILLIS) * RATE / 1000);
        if (frames == 0) return EMPTY;
        if (cache != null && startFrame >= cacheStartFrame) {
            long offset = startFrame - cacheStartFrame;
            if (offset <= cache.length && frames <= cache.length - offset) {
                return Arrays.copyOfRange(cache, (int) offset, (int) offset + frames);
            }
        }
        try {
            cancellation.throwIfCancelled();
            decoder.seek(Duration.ofMillis(start));
            float[] window = new float[frames];
            int copied = 0;
            while (copied < frames) {
                cancellation.throwIfCancelled();
                int read = decoder.read(block);
                if (read < 0) break;
                if (read == 0) throw new IOException("Native decoder made no progress");
                int count = Math.min(read, frames - copied);
                System.arraycopy(block.samples(), 0, window, copied, count);
                copied += count;
            }
            cache = copied == window.length ? window : Arrays.copyOf(window, copied);
            cacheStartFrame = startFrame;
            return cache.clone();
        } catch (IOException failure) {
            cache = null;
            cacheStartFrame = -1;
            return EMPTY;
        }
    }

    /** Existing BS.1770 meter supports mono/stereo; multichannel is explicitly left unmeasured. */
    static IntegratedLoudnessMeter.Measurement analyzeWhole(File source) {
        if (source == null || !source.isFile()) return null;
        CancellationToken token = new CancellationToken();
        try (FfmpegAudioDecoder decoder = FfmpegAudioDecoder.open(AudioInput.file(source.toPath()),
                OutputSpec.preserveSource(), token)) {
            int channels = decoder.info().outputChannels();
            if (channels > 2) return null;
            IntegratedLoudnessMeter meter = new IntegratedLoudnessMeter(decoder.info().outputSampleRate());
            PcmBlock block = new PcmBlock(4096, channels);
            int frames;
            while ((frames = decoder.read(block)) >= 0) {
                token.throwIfCancelled();
                if (frames == 0) throw new IOException("Native decoder made no progress");
                for (int frame = 0; frame < frames; frame++) {
                    float left = block.samples()[frame * channels];
                    float right = channels == 2 ? block.samples()[frame * channels + 1] : 0;
                    if (!meter.acceptFrame(left, right, channels)) return null;
                }
            }
            return meter.finish();
        } catch (IOException | LinkageError failure) {
            System.err.println("[Loudness/Native] Unable to scan " + source.getName() + ": " + failure);
            return null;
        }
    }

    @Override public void close() {
        cancellation.cancel();
        synchronized (this) {
            if (closed) return;
            closed = true;
            cache = null;
            decoder.close();
        }
    }
}
