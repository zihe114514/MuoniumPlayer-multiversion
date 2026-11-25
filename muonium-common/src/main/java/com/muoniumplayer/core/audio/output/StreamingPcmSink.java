package com.muoniumplayer.core.audio.output;

import java.io.IOException;

/**
 * Bounded music output, independent of decoding, networking, JSyn and Minecraft.
 * Input is float32, interleaved in the canonical OpenAL layout: mono; L,R;
 * 5.1(side) L,R,C,LFE,SL,SR; 7.1 L,R,C,LFE,BL,BR,SL,SR.
 * 5.1(back) and other layouts must be explicitly remapped upstream (for example by swresample).
 */
public interface StreamingPcmSink extends AutoCloseable {
    int sampleRate();
    int channels();
    PcmOutputCapabilities capabilities();
    /** Actual OpenAL buffer channels; mono may be duplicated to non-spatial stereo. */
    int outputChannels();
    boolean usesFloat32();

    /**
     * Copies complete frames before returning; the caller may reuse its array.
     * offset counts samples, frameCount counts frames. A single producer owns
     * stream ordering; calls are serialized. Backpressure is interruptible.
     * stop/close cancel a blocked write; no caller buffer is retained.
     */
    void write(float[] interleaved, int sampleOffset, int frameCount) throws IOException;
    /** Linear output gain in [0,1]; caller applies volume/focus policy exactly once. */
    void setGain(float gain) throws IOException;
    void pause() throws IOException;
    void resume() throws IOException;
    /** Discards queued audio and cancels writes. Call resume before writing again. */
    void stop() throws IOException;
    /** Discard queued audio/cancel pending writes, preserving pause and accepting state. */
    void flush() throws IOException;
    /** Waits for all accepted frames to be consumed; rejected while paused/stopped. */
    void drain() throws IOException;
    /** Upper estimate including the bounded Java queue and OpenAL queue. */
    double latencySeconds();
    /** Idempotent; wakes writers and releases native objects on their owner thread. */
    @Override void close() throws IOException;
}
