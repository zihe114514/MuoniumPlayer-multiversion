package com.muoniumplayer.core.audio.ffmpeg;

import org.bytedeco.javacpp.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegAudioDecoderTest {
    @TempDir Path temp;
    @BeforeAll static void nativeRequired() throws Exception {
        requireNative();
    }
    static void requireNative() throws Exception {
        String platform = FfmpegRuntime.detectPlatform(System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getenv(), Files.isRegularFile(Path.of("/system/build.prop")));
        Assumptions.assumeTrue(java.util.Set.of("windows-x86_64", "linux-x86_64", "linux-arm64",
                "macosx-x86_64", "macosx-arm64", "android-arm64", "android-x86_64").contains(platform),
                "No published JavaCPP FFmpeg classifier for " + platform);
        // Installation/loading errors on supported platforms are failures, never skips.
        FfmpegRuntime.ensureLoaded();
    }
    private FfmpegAudioDecoder open(byte[] bytes, OutputSpec output) throws Exception {
        Path file = Files.createTempFile(temp, "fixture-", ".audio");
        Files.write(file, bytes);
        return FfmpegAudioDecoder.open(AudioInput.file(file), output, new CancellationToken());
    }
    @Test void enumeratesActualCapabilitiesAndVersions() throws Exception {
        var capabilities = NativeCapabilities.query();
        assertFalse(capabilities.version().isBlank());
        assertEquals(4, capabilities.libraryVersions().size());
        assertTrue(capabilities.audioDecoders().containsAll(List.of("pcm_s16le", "flac", "aac")));
        assertTrue(capabilities.demuxers().containsAll(List.of("wav", "mp3", "hls", "mpegts")));
        assertTrue(capabilities.inputProtocols().containsAll(List.of("http", "https", "tcp", "tls", "crypto", "file", "subfile")));
        assertFalse(capabilities.buildConfigurations().get("avformat").isBlank());
    }
    @Test void arbitraryBlocksPreserveAllStereoFramesIncludingTail() throws Exception {
        try (var decoder = open(AudioTestFixtures.pcm16(48000, 2, 10007), OutputSpec.preserveSource())) {
            assertEquals(48000, decoder.info().outputSampleRate());
            assertEquals(2, decoder.info().outputChannels());
            var block = new PcmBlock(37, 2);
            float[] same = block.samples();
            int position = 0;
            int count;
            while ((count = decoder.read(block)) != -1) {
                assertSame(same, block.samples());
                assertEquals(count, block.frames());
                assertEquals(position, block.startFrame());
                for (int f = 0; f < count; f++) for (int c = 0; c < 2; c++)
                    assertEquals(AudioTestFixtures.sample(position + f, c) / 32768f, block.samples()[f * 2 + c], 0f);
                position += count;
            }
            assertEquals(10007, position);
            assertEquals(0, block.frames());
            assertEquals(-1, decoder.read(block));
        }
    }
    @Test void floatPrecisionAndHeadroomSurviveWithoutPcm16Quantization() throws Exception {
        try (var decoder = open(AudioTestFixtures.float32(), OutputSpec.preserveSource())) {
            var block = new PcmBlock(20, 1);
            assertEquals(8, decoder.read(block));
            assertEquals(0.000000123f, block.samples()[2], 0f);
            assertEquals(-0.000000123f, block.samples()[3], 0f);
            assertEquals(1.25f, block.samples()[6], 0f);
            assertEquals(-1.25f, block.samples()[7], 0f);
            assertEquals(-1, decoder.read(block));
        }
    }
    @Test void preservesSixChannelLayoutAndChannelOrder() throws Exception {
        try (var decoder = open(AudioTestFixtures.pcm16(96000, 6, 101), OutputSpec.preserveSource())) {
            assertEquals(6, decoder.info().sourceChannels());
            assertEquals(6, decoder.info().outputChannels());
            assertEquals("5.1", decoder.info().sourceChannelLayout());
            assertEquals(0x3fL, decoder.outputChannelMask());
            var block = new PcmBlock(200, 6);
            assertEquals(101, decoder.read(block));
            for (int f = 0; f < 101; f++) for (int c = 0; c < 6; c++)
                assertEquals(AudioTestFixtures.sample(f, c) / 32768f, block.samples()[f * 6 + c], 0f);
        }
    }
    @Test void resamplesAndFullyDrainsTail() throws Exception {
        try (var decoder = open(AudioTestFixtures.pcm16(48000, 2, 48000), OutputSpec.mono(22050))) {
            assertEquals(22050, decoder.info().outputSampleRate());
            assertEquals(1, decoder.info().outputChannels());
            var block = new PcmBlock(17, 1);
            int total = 0, count;
            while ((count = decoder.read(block)) != -1) {
                total += count;
                for (int i = 0; i < count; i++) assertTrue(Float.isFinite(block.samples()[i]));
            }
            assertEquals(22050, total);
        }
    }
    @Test void seekAfterEofClearsDecoderAndResamplerAndTrimsExactly() throws Exception {
        try (var decoder = open(AudioTestFixtures.pcm16(48000, 2, 144000), OutputSpec.preserveSource())) {
            var block = new PcmBlock(4096, 2);
            while (decoder.read(block) != -1) { }
            decoder.seek(Duration.ofMillis(2234));
            assertTrue(decoder.read(block) > 0);
            assertEquals(107232, block.startFrame());
            assertEquals(AudioTestFixtures.sample(107232, 0) / 32768f, block.samples()[0], 0f);
            decoder.seek(Duration.ZERO);
            assertTrue(decoder.read(block) > 0);
            assertEquals(0, block.startFrame());
            assertEquals(AudioTestFixtures.sample(0, 1) / 32768f, block.samples()[1], 0f);
        }
    }
    @Test void compressedFormatsDecodeAndExposeTheirRealCodec() throws Exception {
        for (byte[] fixture : List.of(AudioTestFixtures.mp3(), AudioTestFixtures.flac(), AudioTestFixtures.segment0())) {
            try (var decoder = open(fixture, OutputSpec.preserveSource())) {
                assertTrue(decoder.info().sourceSampleRate() > 0);
                var block = new PcmBlock(31, decoder.info().outputChannels());
                long total = 0;
                int count;
                while ((count = decoder.read(block)) != -1) total += count;
                assertTrue(total >= 6000, decoder.info().codec());
            }
        }
    }
    @Test void cancelledOpenAndReadRetainInterruptSemantics() throws Exception {
        var cancelled = new CancellationToken();
        cancelled.cancel();
        assertThrows(InterruptedIOException.class, () -> FfmpegAudioDecoder.open(AudioInput.file(temp.resolve("absent")), OutputSpec.preserveSource(), cancelled));
        try (var decoder = open(AudioTestFixtures.pcm16(48000, 1, 20), OutputSpec.preserveSource())) {
            decoder.cancel();
            assertThrows(InterruptedIOException.class, () -> decoder.read(new PcmBlock(1, 1)));
        }
    }
    @Test void rejectsCrossThreadReadsAndRepeatedCloseIsHarmless() throws Exception {
        var decoder = open(AudioTestFixtures.pcm16(48000, 1, 20), OutputSpec.preserveSource());
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> assertThrows(IOException.class, () -> decoder.read(new PcmBlock(1, 1)))).get(5, TimeUnit.SECONDS);
            executor.submit(decoder::close).get(5, TimeUnit.SECONDS);
            decoder.close();
            assertThrows(IOException.class, () -> decoder.read(new PcmBlock(1, 1)));
        } finally { decoder.close(); executor.shutdownNow(); }
    }
    @Test void invalidInputReportsNativeErrorAndDoesNotPublishDecoder() throws Exception {
        IOException bad = assertThrows(IOException.class, () -> open(new byte[]{1, 2, 3}, OutputSpec.preserveSource()));
        assertTrue(bad.getMessage().contains("FFmpeg"));
        assertTrue(bad.getMessage().contains("avformat_open_input"));
    }
    @Test void nativeOptionConsumptionIsObservable() throws Exception {
        Path file = temp.resolve("input.wav");
        Files.write(file, AudioTestFixtures.pcm16(48000, 1, 10));
        var options = DecodeOptions.builder().option("not_a_real_native_option", "test").build();
        try (var decoder = FfmpegAudioDecoder.open(AudioInput.file(file).withOptions(options), OutputSpec.preserveSource(), new CancellationToken())) {
            assertEquals("test", decoder.info().unusedOptions().get("not_a_real_native_option"));
        }
    }
    @Test void exactFlacCountIsCheckedWithoutGuessingLossyDurations() throws Exception {
        for (long declared : new long[]{31, 33}) {
            assertThrows(IOException.class, () -> {
                try (var decoder = open(AudioTestFixtures.constantFlac(16, 2, declared), OutputSpec.preserveSource())) {
                    var block = new PcmBlock(10, 2);
                    while (decoder.read(block) != -1) { }
                }
            });
        }
        try (var decoder = open(AudioTestFixtures.constantFlac(16, 2, 0), OutputSpec.preserveSource())) {
            var block = new PcmBlock(7, 2);
            int count, total = 0;
            while ((count = decoder.read(block)) != -1) total += count;
            assertEquals(32, total);
        }
    }
    @Test void corruptFlacCrcOrHeaderAndTruncatedLastFrameFail() throws Exception {
        byte[] valid = AudioTestFixtures.constantFlac(16, 2, 32);
        byte[] crc = valid.clone(); crc[crc.length - 1] ^= 1;
        byte[] header = valid.clone(); header[48] ^= 1;
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 3);
        for (byte[] input : List.of(crc, header, truncated)) {
            assertThrows(IOException.class, () -> {
                try (var decoder = open(input, OutputSpec.preserveSource())) {
                    var block = new PcmBlock(20, decoder.info().outputChannels());
                    while (decoder.read(block) != -1) { }
                }
            });
        }
    }
    @Test void seekResampledOutputMatchesLinearDecodeAtMediaTime() throws Exception {
        byte[] bytes = AudioTestFixtures.pcm16(48000, 2, 240000);
        float expected;
        try (var decoder = open(bytes, OutputSpec.mono(22050))) {
            var block = new PcmBlock(121, 1);
            long target = 49259; // floor(2.234 * 22050), exercises rational-phase preroll
            expected = Float.NaN;
            while (decoder.read(block) != -1) {
                if (block.startFrame() <= target && block.startFrame() + block.frames() > target) {
                    expected = block.samples()[(int) (target - block.startFrame())];
                    break;
                }
            }
            assertTrue(Float.isFinite(expected));
            decoder.seek(Duration.ofMillis(2234));
            assertTrue(decoder.read(block) > 0);
            assertEquals(target, block.startFrame());
            assertEquals(expected, block.samples()[0], 0.00001f);
        }
    }
    @Test void preservesEightChannelsAndExposesNativeSpeakerMask() throws Exception {
        try (var decoder = open(AudioTestFixtures.pcm16(48000, 8, 11), OutputSpec.preserveSource())) {
            assertEquals(8, decoder.info().outputChannels());
            assertEquals(0x63fL, decoder.outputChannelMask());
            var block = new PcmBlock(17, 8);
            assertEquals(11, decoder.read(block));
            for (int f = 0; f < 11; f++) for (int c = 0; c < 8; c++)
                assertEquals(AudioTestFixtures.sample(f, c) / 32768f, block.samples()[f * 8 + c], 0f);
        }
    }
    @Test void explicitOutputLayoutUsesNativeMaskAndRejectsExcessChannels() throws Exception {
        byte[] bytes = AudioTestFixtures.pcm16(48000, 1, 40);
        try (var decoder = open(bytes, OutputSpec.stereo(0))) {
            assertEquals(48000, decoder.info().outputSampleRate());
            assertEquals(3, decoder.outputChannelMask());
            var block = new PcmBlock(100, 2);
            assertEquals(40, decoder.read(block));
        }
        assertThrows(IOException.class, () -> open(bytes, new OutputSpec(0, "hexadecagonal")));
    }
    @Test void allFourTrailingTagFixturesUseNativeBoundedReadAndRemainSeekable() throws Exception {
        byte[] audio = AudioTestFixtures.constantFlac(16, 2, 32);
        Path path = temp.resolve("音频 file,with,commas.dat");
        for (int length : new int[]{24, 40, 44, 128}) {
            byte[] tagged = LocalFileReadViewTest.withTag(audio, length);
            Files.write(path, tagged);
            // A deliberately unrelated suffix: the decision comes from native demux probing.
            AudioInput input = AudioInput.uri(path.toUri()).withOptions(DecodeOptions.defaults());
            try (var decoder = FfmpegAudioDecoder.open(input, OutputSpec.preserveSource(), new CancellationToken())) {
                assertEquals("flac", decoder.info().container());
                assertTrue(decoder.info().seekable());
                var block = new PcmBlock(7, 2);
                int count, frames = 0;
                while ((count = decoder.read(block)) != -1) {
                    for (int f = 0; f < count; f++) {
                        assertEquals(0x1234 / 32768f, block.samples()[f * 2], 0f);
                        assertEquals(-0x2345 / 32768f, block.samples()[f * 2 + 1], 0f);
                    }
                    frames += count;
                }
                assertEquals(32, frames);
                decoder.seek(Duration.ofNanos((16 * 1000000000L + 44099) / 44100));
                assertTrue(decoder.read(block) > 0);
                assertEquals(16, block.startFrame());
            }
            assertArrayEquals(tagged, Files.readAllBytes(path));
        }
    }
    @Test void tagViewStillRejectsCrcErrorsMissingOrExtraAudioAndWrongDeclaredCounts() throws Exception {
        var invalid = new ArrayList<byte[]>();
        invalid.add(AudioTestFixtures.constantFlac(16, 1, 32));
        invalid.add(AudioTestFixtures.constantFlac(16, 2, 31));
        invalid.add(AudioTestFixtures.constantFlac(16, 2, 33));
        invalid.add(AudioTestFixtures.constantFlac(16, 3, 32));
        byte[] corrupt = AudioTestFixtures.constantFlac(16, 2, 32);
        corrupt[corrupt.length - 1] ^= 1;
        invalid.add(corrupt);
        for (byte[] audio : invalid) for (int length : new int[]{24, 40, 44, 128}) {
            assertThrows(IOException.class, () -> {
                try (var decoder = open(LocalFileReadViewTest.withTag(audio, length), OutputSpec.preserveSource())) {
                    var block = new PcmBlock(9, 2);
                    while (decoder.read(block) != -1) { }
                }
            });
        }
    }
    @Test void fullTagWithUnknownFlacSampleCountDecodesAndRemainsUnmodified() throws Exception {
        byte[] tagged = LocalFileReadViewTest.withTag(AudioTestFixtures.constantFlac(16, 2, 0), 128);
        try (var decoder = open(tagged, OutputSpec.preserveSource())) {
            var block = new PcmBlock(5, 2);
            int count, total = 0;
            while ((count = decoder.read(block)) != -1) total += count;
            assertEquals(32, total);
        }
    }
}
