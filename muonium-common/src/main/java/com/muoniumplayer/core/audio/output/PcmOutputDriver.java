package com.muoniumplayer.core.audio.output;

import java.io.IOException;

/** Every method is called only by the sink's native owner thread. */
interface PcmOutputDriver extends AutoCloseable {
    void open(int sampleRate, int channels) throws IOException;
    PcmOutputCapabilities capabilities();
    int outputChannels();
    boolean usesFloat32();
    int freeBuffers() throws IOException;
    void submit(float[] samples, int frameCount) throws IOException;
    int queuedFrames() throws IOException;
    void setGain(float gain) throws IOException;
    void pause() throws IOException;
    void resume() throws IOException;
    void clear() throws IOException;
    @Override void close() throws IOException;
}
