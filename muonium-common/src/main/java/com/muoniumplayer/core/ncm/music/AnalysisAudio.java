package com.muoniumplayer.core.ncm.music;

import java.io.Closeable;

/**
 * Bounded mono PCM windows for offline analysis. Open/read run on a background owner thread;
 * implementations own their decoder and never read the live player's sample window.
 * Float samples retain decoder precision and headroom; they are not quantized to PCM16.
 * Invalid requests, cancellation and decode failures return an empty window. Callers must close.
 */
interface AnalysisAudio extends Closeable {
    int sampleRate();
    float[] readMono(long startMillis, long lengthMillis);
    @Override void close();
}
