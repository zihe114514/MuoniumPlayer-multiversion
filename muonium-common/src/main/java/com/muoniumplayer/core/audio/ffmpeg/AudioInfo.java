package com.muoniumplayer.core.audio.ffmpeg;

import java.util.Map;

/** Native-free immutable metadata. durationMicros is -1 when unknown. */
public record AudioInfo(int streamIndex, String codec, String container,
                        int sourceSampleRate, int sourceChannels, String sourceChannelLayout,
                        int outputSampleRate, int outputChannels, String outputChannelLayout,
                        long durationMicros, boolean seekable, Map<String, String> unusedOptions) {
    public AudioInfo { unusedOptions = Map.copyOf(unusedOptions); }
}
