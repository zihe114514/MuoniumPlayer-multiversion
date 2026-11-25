package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NativeAudioPreparationTest {
    @TempDir Path directory;

    @Test void nativeProbeIgnoresSuffixAndRejectsFakeSignature() throws Exception {
        Path audio = directory.resolve("actually.wav.mp3");
        Files.write(audio, wave(48000, 1, new float[]{0.125f, -0.25f, 0.5f, -0.75f}));
        assertEquals("wav", AudioContainerSupport.detectContainer(audio.toFile()));
        Path fake = directory.resolve("fake.mp3");
        Files.writeString(fake, "ID3 followed by non audio bytes");
        assertNull(AudioContainerSupport.detectContainer(fake.toFile()));
    }

    @Test void retainsFloatPrecisionAndHeadroomWithoutPcm16Quantization() throws Exception {
        float[] samples = {1.0f / (1 << 24), -1.0f / (1 << 25), 0.1234567f, 1.25f, -1.5f, -0.0f};
        Path source = directory.resolve("source.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, wave(96000, 2, samples));
        NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null);
        ByteBuffer pcm = ByteBuffer.wrap(Files.readAllBytes(decoded)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(96000, pcm.getInt(24));
        assertEquals(2, pcm.getShort(22));
        for (int index = 0; index < samples.length; index++) {
            assertEquals(Float.floatToRawIntBits(samples[index]), pcm.getInt(56 + index * 4));
        }
        assertTrue(NativeAudioPreparation.isReusable(source.toFile(), decoded.toFile()));
        assertEquals(source.toFile().getCanonicalFile(), NativeAudioPreparation.originalSource(decoded.toFile()));
    }

    @Test void sourceHashInvalidatesSameSizeAndMtimeReplacement() throws Exception {
        Path source = directory.resolve("same.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, wave(48000, 1, new float[]{0.1f, 0.2f, 0.3f, 0.4f}));
        NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null);
        FileTime time = Files.getLastModifiedTime(source);
        Files.write(source, wave(48000, 1, new float[]{0.2f, 0.2f, 0.3f, 0.4f}));
        Files.setLastModifiedTime(source, time);
        assertFalse(NativeAudioPreparation.isReusable(source.toFile(), decoded.toFile()));
    }

    @Test void pcmCorruptionAndTruncationCannotPassCacheValidation() throws Exception {
        Path source = directory.resolve("source.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, wave(48000, 1, new float[]{0.1f, 0.2f, 0.3f, 0.4f}));
        NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null);
        byte[] bytes = Files.readAllBytes(decoded);
        bytes[56] ^= 1;
        Files.write(decoded, bytes);
        assertFalse(NativeAudioPreparation.isReusable(source.toFile(), decoded.toFile()));
        Files.write(decoded, Arrays.copyOf(bytes, bytes.length - 1));
        assertFalse(NativeAudioPreparation.isReusable(source.toFile(), decoded.toFile()));
    }

    @Test void oldCacheVersionCannotBeReused() throws Exception {
        Path source = directory.resolve("source.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, wave(48000, 1, new float[]{0.1f, 0.2f, 0.3f, 0.4f}));
        NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null);
        byte[] bytes = Files.readAllBytes(decoded);
        byte[] version = NativeAudioPreparation.CACHE_VERSION.getBytes(StandardCharsets.US_ASCII);
        boolean changed = false;
        for (int index = 0; index <= bytes.length - version.length; index++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, index, index + version.length), version)) {
                bytes[index + version.length - 1] = '0';
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        Files.write(decoded, bytes);
        assertFalse(NativeAudioPreparation.isReusable(source.toFile(), decoded.toFile()));
    }

    @Test void failedDecodePreservesSourceAndOldProduct() throws Exception {
        Path source = directory.resolve("broken.mp3");
        Path decoded = directory.resolve("decoded.wav");
        byte[] original = "Not a valid audio stream".getBytes(StandardCharsets.US_ASCII);
        byte[] old = {1, 2, 3, 4};
        Files.write(source, original);
        Files.write(decoded, old);
        assertThrows(IOException.class,
                () -> NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(old, Files.readAllBytes(decoded));
        assertNoPartial();
    }

    @Test void cancellationDuringDecodeKeepsPreviousProductAndSource() throws Exception {
        Path source = directory.resolve("source.wav");
        Path decoded = directory.resolve("decoded.wav");
        byte[] original = wave(48000, 1, new float[48000]);
        byte[] old = {1, 2, 3, 4};
        Files.write(source, original);
        Files.write(decoded, old);
        CancellationToken token = new CancellationToken();
        assertThrows(InterruptedIOException.class,
                () -> NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(),
                        progress -> { if (progress > 0) token.cancel(); }, token));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(old, Files.readAllBytes(decoded));
        assertNoPartial();
    }

    @Test void interruptionIsPreservedAndCannotPublish() throws Exception {
        Path source = directory.resolve("source.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, wave(48000, 1, new float[]{0.1f, 0.2f}));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(decoded));
        } finally { Thread.interrupted(); }
    }

    @Test void sourceChangedDuringDecodeCannotPublishStaleIdentity() throws Exception {
        Path source = directory.resolve("source.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, wave(48000, 1, new float[48000]));
        AtomicBoolean changed = new AtomicBoolean();
        assertThrows(IOException.class,
                () -> NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), progress -> {
                    if (progress > 0 && changed.compareAndSet(false, true)) {
                        try { Files.setLastModifiedTime(source, FileTime.fromMillis(1)); }
                        catch (IOException failure) { throw new AssertionError(failure); }
                    }
                }));
        assertTrue(changed.get());
        assertFalse(Files.exists(decoded));
        assertNoPartial();
    }

    @Test void sourceCannotBeItsOwnDestination() throws Exception {
        Path source = directory.resolve("source.wav");
        byte[] original = wave(48000, 1, new float[]{0.1f, 0.2f});
        Files.write(source, original);
        assertThrows(IOException.class,
                () -> NativeAudioPreparation.decodeToWav(source.toFile(), source.toFile(), null));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void renderThreadCannotStartFingerprintingOrDecode() throws Exception {
        Path source = directory.resolve("source.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, wave(48000, 1, new float[]{0.1f, 0.2f}));
        var result = new java.util.concurrent.CompletableFuture<Void>();
        Thread thread = new Thread(() -> {
            try {
                assertThrows(IOException.class,
                        () -> NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null));
                assertThrows(IOException.class, () -> NativeAudioPreparation.probe(source.toFile()));
                assertFalse(NativeAudioPreparation.isReusable(source.toFile(), decoded.toFile()));
                result.complete(null);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }, "Render thread");
        thread.start();
        result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        thread.join();
        assertFalse(Files.exists(decoded));
        assertNoPartial();
    }

    @Test void surroundLayoutIsPreservedAsAWaveExtensibleSpeakerMask() throws Exception {
        int channels = 6;
        int mask = 0x60f; // FL FR FC LFE SL SR: 5.1(side), not the default 5.1(back).
        float[] samples = new float[channels * 32];
        for (int i = 0; i < samples.length; i++) samples[i] = i / 1024.0f;
        ByteBuffer input = ByteBuffer.allocate(68 + samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        input.put(new byte[]{'R','I','F','F'}).putInt(input.capacity() - 8);
        input.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(40);
        input.putShort((short) 0xfffe).putShort((short) channels).putInt(48000).putInt(48000 * channels * 4);
        input.putShort((short) (channels * 4)).putShort((short) 32);
        input.putShort((short) 22).putShort((short) 32).putInt(mask);
        input.put(new byte[]{3, 0, 0, 0, 0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113});
        input.put(new byte[]{'d','a','t','a'}).putInt(samples.length * 4);
        for (float sample : samples) input.putFloat(sample);
        Path source = directory.resolve("surround.wav");
        Path decoded = directory.resolve("decoded.wav");
        Files.write(source, input.array());
        NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null);
        ByteBuffer output = ByteBuffer.wrap(Files.readAllBytes(decoded)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xfffe, Short.toUnsignedInt(output.getShort(20)));
        assertEquals(mask, output.getInt(40));
        assertEquals("5.1(side)", NativeAudioPreparation.probe(decoded.toFile()).sourceChannelLayout());
        for (int i = 0; i < samples.length; i++) assertEquals(samples[i], output.getFloat(80 + i * 4));
        assertTrue(NativeAudioPreparation.isReusable(source.toFile(), decoded.toFile()));
    }

    private void assertNoPartial() throws IOException {
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    static byte[] wave(int rate, int channels, float[] interleaved) {
        if (interleaved.length % channels != 0) throw new IllegalArgumentException();
        ByteBuffer bytes = ByteBuffer.allocate(44 + interleaved.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'R','I','F','F'}).putInt(bytes.capacity() - 8);
        bytes.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16);
        bytes.putShort((short) 3).putShort((short) channels).putInt(rate).putInt(rate * channels * 4);
        bytes.putShort((short) (channels * 4)).putShort((short) 32);
        bytes.put(new byte[]{'d','a','t','a'}).putInt(interleaved.length * 4);
        for (float sample : interleaved) bytes.putFloat(sample);
        return bytes.array();
    }
}
