package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrackLoudnessNormalizationCacheTest {

    @TempDir
    Path tempDirectory;

    @Test
    void measurementCacheSurvivesMemoryEvictionAndInvalidatesChangedAudio() throws Exception {
        Path cacheDirectory = tempDirectory.resolve("cache");
        Path audioFile = tempDirectory.resolve("track.wav");
        Files.write(audioFile, new byte[]{1, 2, 3, 4});
        TrackLoudnessNormalization.setCacheDirectoryForTests(cacheDirectory.toFile());
        try {
            TrackLoudnessNormalization.Measurement expected =
                    new TrackLoudnessNormalization.Measurement(-18.5f, .91f);
            TrackLoudnessNormalization.cache(audioFile.toFile(), expected);
            TrackLoudnessNormalization.clearMemoryForTests();

            TrackLoudnessNormalization.Measurement loaded =
                    TrackLoudnessNormalization.lookup(audioFile.toFile());
            assertEquals(expected.integratedLufs, loaded.integratedLufs, .0001f);
            assertEquals(expected.samplePeak, loaded.samplePeak, .0001f);

            Files.write(audioFile, new byte[]{4, 3, 2, 1});
            Files.setLastModifiedTime(audioFile, FileTime.fromMillis(System.currentTimeMillis() + 2_000L));
            assertNull(TrackLoudnessNormalization.lookup(audioFile.toFile()));
        } finally {
            TrackLoudnessNormalization.setCacheDirectoryForTests(null);
        }
    }

    @Test
    void gainIsLimitedByBoostAndMeasuredSamplePeak() {
        assertEquals(1.0f, TrackLoudnessNormalization.gainFor(-18.0f, 1.0f), .0001f);
        assertEquals(0.5f, TrackLoudnessNormalization.gainFor(-12.0f, 2.0f), .0001f);
        assertEquals((float) Math.pow(10.0, 12.0 / 20.0),
                TrackLoudnessNormalization.gainFor(-40.0f, .1f), .0001f);
    }

    @Test
    void automixRatioUsesOnlyTheGainThatSurvivesTheDeckAmplitudeCeiling() {
        assertEquals(2.0f, AudioPlayer.effectiveNormalizationRatio(.25f, 2.0f, 1.0f), .0001f);
        assertEquals(1.0f, AudioPlayer.effectiveNormalizationRatio(1.0f, 2.0f, 1.0f), .0001f);
        assertEquals(.5f, AudioPlayer.effectiveNormalizationRatio(.25f, .5f, 2.0f), .0001f);
    }
}
