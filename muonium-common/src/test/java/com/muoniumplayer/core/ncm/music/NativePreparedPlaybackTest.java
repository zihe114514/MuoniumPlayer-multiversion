package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.util.SampleLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end native PCM products consumed by the remaining deck adapter. No audio device needed. */
class NativePreparedPlaybackTest {
    @TempDir Path directory;

    @Test
    void preparedFloatWaveReadsAllSamplesAndNeverTreatsItsManifestAsPcm() throws Exception {
        float[] values = new float[12034];
        for (int i = 0; i < values.length; i++) values[i] = (i % 701 - 350) / 512.0f;
        Path source = directory.resolve("source.wav");
        Files.write(source, NativeAudioPreparationTest.wave(48000, 2, values));
        Path decoded = directory.resolve("prepared.wav");
        NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null);
        FloatSample sample = SampleLoader.loadStreamedFloatSample(decoded.toFile());
        try {
            assertEquals(values.length / 2, sample.getNumFrames());
            assertEquals(48000, sample.getFrameRate());
            for (int i = 0; i < values.length; i++) {
                assertEquals(values[i], (float) sample.readDouble(i), 0.0f, "interleaved sample " + i);
            }
            assertEquals(0.0, sample.readDouble(values.length));
            assertEquals(0.0, sample.readDouble(values.length + 4));
        } finally {
            sample.cleanUp();
        }
    }

    @Test
    void malformedPreparedWaveDoesNotLeakItsFileHandle() throws Exception {
        Path invalid = Files.write(directory.resolve("invalid.wav"), new byte[]{'R','I','F','F'});
        assertThrows(Exception.class, () -> SampleLoader.loadStreamedFloatSample(invalid.toFile()));
        Files.delete(invalid);
        assertFalse(Files.exists(invalid));
    }
}
