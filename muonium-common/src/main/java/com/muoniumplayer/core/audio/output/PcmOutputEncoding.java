package com.muoniumplayer.core.audio.output;

import java.nio.ByteBuffer;

/** Final output conversion only; no resampling, channel-matrix guessing or allocation. */
final class PcmOutputEncoding {
    private PcmOutputEncoding() {}

    static float normalized(float sample) {
        return Float.isFinite(sample) ? Math.max(-1f, Math.min(1f, sample)) : 0f;
    }

    static short pcm16(float sample) {
        float value = normalized(sample);
        return (short) Math.max(-32768, Math.min(32767, Math.round(value * 32768f)));
    }

    static void encode(float[] input, int frames, int channels, boolean duplicateMono,
                       boolean float32, ByteBuffer destination) {
        destination.clear();
        for (int i = 0, n = frames * channels; i < n; i++) {
            float value = Float.isFinite(input[i]) ? input[i] : 0f;
            if (float32) {
                destination.putFloat(value);
                if (duplicateMono) destination.putFloat(value);
            } else {
                short encoded = pcm16(value);
                destination.putShort(encoded);
                if (duplicateMono) destination.putShort(encoded);
            }
        }
        destination.flip();
    }
}
