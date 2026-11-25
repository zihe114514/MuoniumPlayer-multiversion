package com.muoniumplayer.core.audio.ffmpeg;

/** Caller-owned reusable block. Only frames * channels samples are valid after read. */
public final class PcmBlock {
    public static final int MAX_FRAMES = 65536;
    private final float[] samples;
    private final int channels;
    private int frames;
    private long startFrame;

    public PcmBlock(int capacityFrames, int channels) {
        if (capacityFrames < 1 || capacityFrames > MAX_FRAMES || channels < 1 || channels > 8) {
            throw new IllegalArgumentException("Expected 1..65536 frames and 1..8 channels");
        }
        this.channels = channels;
        samples = new float[capacityFrames * channels];
    }
    public float[] samples() { return samples; }
    public int channels() { return channels; }
    public int frames() { return frames; }
    public long startFrame() { return startFrame; }
    void startFrame(long value) { startFrame = value; }
    public int capacityFrames() { return samples.length / channels; }
    void frames(int value) { frames = value; }
}
