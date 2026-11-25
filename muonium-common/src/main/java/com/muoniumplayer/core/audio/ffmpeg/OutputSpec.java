package com.muoniumplayer.core.audio.ffmpeg;

/** Output is always interleaved float32. Null layout / zero rate preserve the source. */
public record OutputSpec(int sampleRate, String channelLayout) {
    public OutputSpec {
        if (sampleRate < 0 || sampleRate > 768000) throw new IllegalArgumentException("Invalid output sample rate");
        if (channelLayout != null && (channelLayout.isBlank() || channelLayout.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Invalid channel layout");
        }
    }
    public static OutputSpec preserveSource() { return new OutputSpec(0, null); }
    public static OutputSpec stereo(int sampleRate) { return new OutputSpec(sampleRate, "stereo"); }
    public static OutputSpec mono(int sampleRate) { return new OutputSpec(sampleRate, "mono"); }
}
