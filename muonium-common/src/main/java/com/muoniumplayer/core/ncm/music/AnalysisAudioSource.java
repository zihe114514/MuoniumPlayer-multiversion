package com.muoniumplayer.core.ncm.music;

import java.io.File;

/** 每个分析请求拥有独立的原生解码器，WAV 输入也遵循同一所有权。 */
public final class AnalysisAudioSource {
    private AnalysisAudioSource() {}

    public static AnalysisAudio open(File source) {
        return FfmpegAnalysisAudio.open(source);
    }
}
