package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeFileFormatCompatibilityTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"mp3,mp3float,mp3", "aac,aac,aac", "m4a,aac,mp4", "vorbis,vorbis,ogg",
            "opus,opus,ogg", "alac,alac,mp4", "aiff,pcm_s24be,aiff", "wma,wmav2,asf"})
    void audioCodecAndContainerAreProvedByNativeProbeAndDecode(String fixture, String codec,
                                                               String container) throws Exception {
        Path source = directory.resolve("input-misleading.flac");
        byte[] original = NativeFileFixtures.audio(fixture);
        Files.write(source, original);
        var info = NativeAudioPreparation.probe(source.toFile());
        assertEquals(codec, info.codec());
        assertEquals(container, AudioContainerSupport.detectContainer(source.toFile()));
        assertEquals(48000, info.sourceSampleRate());
        assertEquals(2, info.sourceChannels());
        Path output = directory.resolve("decoded.wav");
        NativeAudioPreparation.decodeToWav(source.toFile(), output.toFile(), null);
        ByteBuffer pcm = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, pcm.getShort(20));
        assertTrue(pcm.getInt(44) > 1000);
        assertTrue(NativeAudioPreparation.isReusable(source.toFile(), output.toFile()));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void videoContainerUsesTheSelectedDefaultAudioTrack() throws Exception {
        Path source = directory.resolve("input.bin");
        Files.write(source, NativeFileFixtures.audio("multitrack"));
        var info = NativeAudioPreparation.probe(source.toFile());
        assertEquals("flac", info.codec());
        assertEquals(2, info.streamIndex());
        assertEquals(22050, info.sourceSampleRate());
        assertEquals(1, info.sourceChannels());
        assertEquals("mkv", AudioContainerSupport.detectContainer(source.toFile()));
        Path output = directory.resolve("decoded.wav");
        NativeAudioPreparation.decodeToWav(source.toFile(), output.toFile(), null);
        ByteBuffer pcm = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(22050, pcm.getInt(24));
        assertEquals(1, pcm.getShort(22));
        assertEquals(4410, pcm.getInt(44));
        try (AnalysisAudio analysis = AnalysisAudioSource.open(source.toFile())) {
            assertNotNull(analysis);
            assertEquals(441, analysis.readMono(50, 20).length);
        }
    }

    @ParameterizedTest
    @CsvSource({"aac,aac", "m4a,aac", "vorbis,vorbis", "opus,opus", "alac,alac"})
    void compressedInputsPublishCompleteReusableFloatCaches(String fixture, String codec) throws Exception {
        Path source = directory.resolve("compressed.data");
        Path output = directory.resolve("decoded.wav");
        byte[] original = NativeFileFixtures.audio(fixture);
        Files.write(source, original);
        assertEquals(codec, NativeAudioPreparation.probe(source.toFile()).codec());
        List<Double> progress = new ArrayList<>();
        assertEquals(output.toFile(),
                NativeAudioPreparation.decodeToWav(source.toFile(), output.toFile(), progress::add));
        assertEquals(0.0, progress.get(0));
        assertEquals(1.0, progress.get(progress.size() - 1));
        ByteBuffer pcm = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, pcm.getShort(20));
        assertEquals(32, pcm.getShort(34));
        assertEquals(48000, pcm.getInt(24));
        assertEquals(2, pcm.getShort(22));
        int frames = pcm.getInt(44);
        assertTrue(frames > 1000);
        assertEquals(frames * 2 * Float.BYTES, pcm.getInt(52));
        assertTrue(NativeAudioPreparation.isReusable(source.toFile(), output.toFile()));
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".part")));
        }
    }
}
