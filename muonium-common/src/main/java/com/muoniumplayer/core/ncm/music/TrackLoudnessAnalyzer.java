package com.muoniumplayer.core.ncm.music;

import java.io.File;

/** Opens the repository's existing Java decoders and scans a complete track once. */
final class TrackLoudnessAnalyzer {

    private TrackLoudnessAnalyzer() {
    }

    static IntegratedLoudnessMeter.Measurement analyze(File file) {
        if (file == null || !file.isFile()) return null;
        PcmWindowReader wav = PcmWindowReader.open(file);
        if (wav != null) {
            try (PcmWindowReader reader = wav) {
                if (reader.channels() > 2) return null;
                IntegratedLoudnessMeter meter = new IntegratedLoudnessMeter(reader.sampleRate());
                return reader.scan(meter) ? meter.finish() : null;
            }
        }
        return JavaDecoderAnalysisAudio.analyzeWhole(file);
    }
}
