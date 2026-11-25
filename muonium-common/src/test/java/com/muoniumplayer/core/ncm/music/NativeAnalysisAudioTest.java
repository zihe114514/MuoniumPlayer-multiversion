package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class NativeAnalysisAudioTest {
    @TempDir Path directory;

    @Test void independentHandlesReadExactMonoWindowsInEitherOrder() throws Exception {
        int rate = 22050;
        float[] source = new float[rate * 2];
        for (int i = 0; i < source.length; i++) source[i] = (float) (0.4 * Math.sin(i * 0.17));
        Path file = directory.resolve("misleading.flac");
        Files.write(file, NativeAudioPreparationTest.wave(rate, 1, source));
        try (AnalysisAudio first = AnalysisAudioSource.open(file.toFile());
             AnalysisAudio second = AnalysisAudioSource.open(file.toFile())) {
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(rate, first.sampleRate());
            assertWindow(source, rate, 700, 20, first.readMono(700, 20));
            assertWindow(source, rate, 100, 20, second.readMono(100, 20));
            assertWindow(source, rate, 500, 20, first.readMono(500, 20));
            assertWindow(source, rate, 0, 20, first.readMono(0, 20));
        }
    }

    @Test void resampledSeekWindowsMatchTheSameMediaTimeline() throws Exception {
        int rate = 48000;
        float[] source = new float[rate * 2];
        for (int i = 0; i < source.length; i++) source[i] = (float) (0.4 * Math.sin(i * 0.071));
        Path file = directory.resolve("resample.wav");
        Files.write(file, NativeAudioPreparationTest.wave(rate, 1, source));
        float[] continuous;
        try (AnalysisAudio audio = AnalysisAudioSource.open(file.toFile())) {
            assertNotNull(audio);
            continuous = audio.readMono(0, 1000);
            assertEquals(22050, continuous.length);
        }
        try (AnalysisAudio audio = AnalysisAudioSource.open(file.toFile())) {
            assertNotNull(audio);
            float[] window = audio.readMono(350, 30);
            int startFrame = 350 * 22050 / 1000;
            assertEquals(30 * 22050 / 1000, window.length);
            for (int i = 0; i < window.length; i++) {
                assertEquals(continuous[startFrame + i], window[i], 1e-5f,
                        "Resampler phase must stay aligned to media time at frame " + (startFrame + i));
            }
        }
    }

    @Test void returnedWindowsDoNotExposeMutableCachedStorage() throws Exception {
        Path file = directory.resolve("mono.wav");
        float[] source = new float[22050];
        Arrays.fill(source, 0.125f);
        Files.write(file, NativeAudioPreparationTest.wave(22050, 1, source));
        try (AnalysisAudio audio = AnalysisAudioSource.open(file.toFile())) {
            assertNotNull(audio);
            float[] first = audio.readMono(0, 20);
            assertEquals(441, first.length);
            first[0] = 100;
            assertEquals(0.125f, audio.readMono(0, 20)[0]);
            assertEquals(0, audio.readMono(0, 0).length);
            assertEquals(0, audio.readMono(Long.MAX_VALUE, 100).length);
        }
    }

    @Test void closeReleasesNativeHandleAndFurtherReadsAreEmpty() throws Exception {
        Path file = directory.resolve("mono.wav");
        Files.write(file, NativeAudioPreparationTest.wave(22050, 1, new float[22050]));
        AnalysisAudio audio = AnalysisAudioSource.open(file.toFile());
        assertNotNull(audio);
        assertEquals(2205, audio.readMono(0, 100).length);
        audio.close();
        audio.close();
        assertEquals(0, audio.readMono(0, 100).length);
        assertTrue(Files.deleteIfExists(file));
    }

    @Test void wholeFileScanUsesNativeFloatFramesAndHandlesMonoAndStereo() throws Exception {
        for (int channels : new int[]{1, 2}) {
            float[] samples = new float[48000 * channels];
            for (int i = 0; i < samples.length; i++) samples[i] = (float) (0.2 * Math.sin(i * 0.1));
            Path file = directory.resolve("audio" + channels + ".bin");
            Files.write(file, NativeAudioPreparationTest.wave(48000, channels, samples));
            IntegratedLoudnessMeter expected = new IntegratedLoudnessMeter(48000);
            for (int frame = 0; frame < samples.length / channels; frame++) {
                assertTrue(expected.acceptFrame(samples[frame * channels],
                        channels == 2 ? samples[frame * channels + 1] : 0, channels));
            }
            var reference = expected.finish();
            assertNotNull(reference);
            var measurement = FfmpegAnalysisAudio.analyzeWhole(file.toFile());
            assertNotNull(measurement);
            assertEquals(48000, measurement.frames, "分析必须包含最后一个不足块长的尾块");
            assertEquals(48000, measurement.sampleRate);
            assertEquals(reference.integratedLufs, measurement.integratedLufs, 1e-5f);
            assertEquals(reference.samplePeak, measurement.samplePeak, 1e-7f);
        }
    }

    @Test void existingStereoLoudnessMeterDoesNotPretendToMeasureSurround() throws Exception {
        Path file = directory.resolve("surround.wav");
        Files.write(file, NativeAudioPreparationTest.wave(48000, 6, new float[48000 * 6]));
        assertNull(FfmpegAnalysisAudio.analyzeWhole(file.toFile()));
    }

    private static void assertWindow(float[] source, int rate, long start, long length, float[] actual) {
        int startFrame = (int) (start * rate / 1000);
        int frames = (int) (length * rate / 1000);
        assertEquals(frames, actual.length);
        for (int i = 0; i < frames; i++) {
            assertEquals(source[startFrame + i], actual[i], 1e-6f, "Window media frame " + (startFrame + i));
        }
    }
}
