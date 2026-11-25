package com.muoniumplayer.core.ncm.music;

import java.io.File;

/** Opens the shared native decoder and scans a complete track once. */
final class TrackLoudnessAnalyzer {

    private TrackLoudnessAnalyzer() {
    }

    static IntegratedLoudnessMeter.Measurement analyze(File file) {
        if (file == null || !file.isFile()) return null;
        return FfmpegAnalysisAudio.analyzeWhole(file);
    }
}
