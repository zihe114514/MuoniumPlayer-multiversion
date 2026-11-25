package com.muoniumplayer.core.ncm.music;

import java.io.File;

/** Every analysis request owns an independent native decoder, including WAV inputs. */
final class AnalysisAudioSource {
    private AnalysisAudioSource() {}

    static AnalysisAudio open(File source) {
        return FfmpegAnalysisAudio.open(source);
    }
}
