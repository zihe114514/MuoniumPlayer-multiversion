package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** Guard native analysis ownership, bounded windows and streaming loudness scans. */
class FfmpegAnalysisAudioStaticTest {
    @Test void everyAnalysisFormatUsesTheNativeCore() throws IOException {
        String source = read("AnalysisAudioSource.java");
        assertTrue(source.contains("FfmpegAnalysisAudio.open(source)"));
        assertFalse(source.contains("PcmWindowReader.open"));
    }
    @Test void nativeAnalysisOwnsAnIndependentDecoderAndNeverStartsProcesses() throws IOException {
        String source = read("FfmpegAnalysisAudio.java");
        assertTrue(source.contains("FfmpegAudioDecoder.open(AudioInput.file(source.toPath())"));
        assertTrue(source.contains("OutputSpec.mono(RATE)"));
        assertFalse(source.contains("ProcessBuilder") || source.contains("Runtime.getRuntime"));
        assertFalse(source.contains("FloatSample") || source.contains("getAudioSample") || source.contains("PcmWindowReader"));
    }
    @Test void windowsAreBoundedAndSeekUsesMediaTime() throws IOException {
        String source = read("FfmpegAnalysisAudio.java");
        assertTrue(source.contains("MAX_WINDOW_MILLIS = 60_000L"));
        assertTrue(source.contains("decoder.seek(Duration.ofMillis(start))"));
        assertTrue(source.contains("Math.min(lengthMillis, MAX_WINDOW_MILLIS)"));
        assertTrue(source.contains("cancellation.throwIfCancelled()"));
    }
    @Test void closingCancelsBeforeReleasingAndWholeScanUsesStreamingFloats() throws IOException {
        String source = read("FfmpegAnalysisAudio.java");
        int close = source.indexOf("@Override public void close()");
        assertTrue(source.indexOf("cancellation.cancel()", close) < source.indexOf("synchronized (this)", close));
        assertTrue(source.contains("try (FfmpegAudioDecoder decoder"));
        assertTrue(source.contains("new PcmBlock(4096, channels)"));
        assertTrue(source.contains("meter.acceptFrame(left, right, channels)"));
    }
    private static String read(String file) throws IOException {
        return Files.readString(ProjectPaths.sharedJava().resolve("com/muoniumplayer/core/ncm/music/" + file));
    }
}
