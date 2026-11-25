package com.muoniumplayer.core.audio.output;

/** Verified buffer layouts supported by this sink, never a physical speaker claim. */
public final class PcmOutputCapabilities {
    private final int pcm16Mask;
    private final int float32Mask;
    private final boolean sourceSpatialize;

    PcmOutputCapabilities(int pcm16Mask, int float32Mask, boolean sourceSpatialize) {
        this.pcm16Mask = pcm16Mask;
        this.float32Mask = float32Mask;
        this.sourceSpatialize = sourceSpatialize;
    }

    public boolean supportsChannels(int channels) {
        return supportsPcm16(channels) || supportsFloat32(channels);
    }

    public boolean supportsFloat32(int channels) {
        return validChannels(channels) && (float32Mask & (1 << channels)) != 0;
    }

    public boolean supportsPcm16(int channels) {
        return validChannels(channels) && (pcm16Mask & (1 << channels)) != 0;
    }

    public boolean supportsExplicitNonSpatialSource() { return sourceSpatialize; }

    public static boolean validChannels(int channels) {
        return channels == 1 || channels == 2 || channels == 6 || channels == 8;
    }
}
