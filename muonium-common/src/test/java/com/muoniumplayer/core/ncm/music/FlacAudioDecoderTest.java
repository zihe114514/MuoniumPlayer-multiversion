package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlacAudioDecoderTest {
    private static final int BLOCK_SIZE = 16;
    private static final int CHANNELS = 2;
    private static final int SAMPLE_RATE = 44100;
    private static final int METADATA_BYTES = 42;

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 24})
    void completeAudioProducesExactPcmAndWaveHeader(int bits) throws Exception {
        byte[] wav = decode(flac(bits, 2, 2L * BLOCK_SIZE));
        assertWave(wav, bits, 2 * BLOCK_SIZE);
    }

    @ParameterizedTest
    @ValueSource(ints = {24, 40, 44, 128})
    void completeAudioWithTrailingTagIsNotMistakenForBadFrames(int tagBytes) throws Exception {
        byte[] audio = flac(16, 2, 2L * BLOCK_SIZE);
        byte[] tagged = Arrays.copyOf(audio, audio.length + tagBytes);
        Arrays.fill(tagged, audio.length, tagged.length, (byte) ' ');
        System.arraycopy("TAG".getBytes(StandardCharsets.US_ASCII), 0, tagged, audio.length, 3);
        assertWave(decode(tagged), 16, 2 * BLOCK_SIZE);
    }

    @Test
    void unknownSampleCountStillDecodesUntilPhysicalEof() throws Exception {
        assertWave(decode(flac(16, 2, 0L)), 16, 2 * BLOCK_SIZE);
    }

    @Test
    void missingLastFrameCannotPublishAShortenedSong() throws Exception {
        assertDecodeFails(flac(16, 1, 2L * BLOCK_SIZE));
    }

    @Test
    void truncatedLastFrameCannotPublishAShortenedSong() throws Exception {
        byte[] audio = flac(16, 2, 2L * BLOCK_SIZE);
        assertDecodeFails(Arrays.copyOf(audio, audio.length - 3));
    }

    @Test
    void decodedAudioCannotExceedDeclaredSampleCount() throws Exception {
        assertDecodeFails(flac(16, 2, 2L * BLOCK_SIZE - 1));
    }

    @Test
    void aRealFrameChecksumErrorStillFails() throws Exception {
        byte[] audio = flac(16, 2, 2L * BLOCK_SIZE);
        audio[audio.length - 1] ^= 1;
        assertDecodeFails(audio);
    }

    @Test
    void aRealFrameHeaderErrorStillFails() throws Exception {
        byte[] audio = flac(16, 2, 2L * BLOCK_SIZE);
        audio[METADATA_BYTES + 6] ^= 1;
        assertDecodeFails(audio);
    }

    @Test
    void metadataWithoutAudioCannotPublishAnEmptyWave() throws Exception {
        assertDecodeFails(flac(16, 0, 0L));
    }

    private byte[] decode(byte[] audio) throws Exception {
        Path input = directory.resolve("song.flac");
        Path output = directory.resolve("song.decoded.wav");
        Files.write(input, audio);
        List<Double> progress = new ArrayList<>();
        assertEquals(output.toFile(), FlacAudioDecoder.decodeToWav(input.toFile(), output.toFile(), progress::add));
        assertEquals(0.0, progress.get(0));
        assertEquals(1.0, progress.get(progress.size() - 1));
        assertArrayEquals(audio, Files.readAllBytes(input));
        assertFalse(Files.exists(directory.resolve("song.decoded.wav.part")));
        return Files.readAllBytes(output);
    }

    private void assertDecodeFails(byte[] audio) throws Exception {
        Path input = directory.resolve("song.flac");
        Path output = directory.resolve("song.decoded.wav");
        byte[] previousCache = {1, 2, 3};
        Files.write(input, audio);
        Files.write(output, previousCache);
        List<Double> progress = new ArrayList<>();
        assertThrows(IOException.class,
                () -> FlacAudioDecoder.decodeToWav(input.toFile(), output.toFile(), progress::add));
        assertArrayEquals(audio, Files.readAllBytes(input), "解码失败不得修改原音频");
        assertArrayEquals(previousCache, Files.readAllBytes(output), "失败不得覆盖已有的成品缓存");
        assertFalse(Files.exists(directory.resolve("song.decoded.wav.part")));
        assertFalse(progress.contains(1.0), "失败不得发布转码完成进度");
    }

    private static void assertWave(byte[] wav, int bits, int samples) {
        int frameBytes = CHANNELS * (bits / 8);
        assertEquals(44 + samples * frameBytes, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVEfmt ", new String(wav, 8, 8, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(wav.length - 8, header.getInt(4));
        assertEquals(1, header.getShort(20));
        assertEquals(CHANNELS, header.getShort(22));
        assertEquals(SAMPLE_RATE, header.getInt(24));
        assertEquals(SAMPLE_RATE * frameBytes, header.getInt(28));
        assertEquals(frameBytes, header.getShort(32));
        assertEquals(bits, header.getShort(34));
        assertEquals(samples * frameBytes, header.getInt(40));
        byte[] expected = new byte[samples * frameBytes];
        for (int sample = 0; sample < samples; sample++) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                int value = sampleValue(bits, channel);
                if (bits == 8) value += 128;
                for (int octet = 0; octet < bits / 8; octet++) {
                    expected[sample * frameBytes + channel * (bits / 8) + octet] = (byte) (value >>> (8 * octet));
                }
            }
        }
        assertArrayEquals(expected, Arrays.copyOfRange(wav, 44, wav.length));
    }

    // 完整的小型 FLAC：STREAMINFO + 两声道常量子帧，包含真实的帧头 CRC-8 与帧尾 CRC-16。
    // 每次生成输入，无需联网下载歌曲或把用户歌曲纳入测试资源。
    private static byte[] flac(int bits, int frames, long totalSamples) throws IOException {
        byte[] metadata = new byte[METADATA_BYTES];
        ByteBuffer info = ByteBuffer.wrap(metadata).order(ByteOrder.BIG_ENDIAN);
        info.put("fLaC".getBytes(StandardCharsets.US_ASCII));
        info.putInt(0x80000022); // 最后一个元数据块，34 字节 STREAMINFO。
        info.putShort((short) BLOCK_SIZE);
        info.putShort((short) BLOCK_SIZE);
        info.position(18);
        info.putLong(((long) SAMPLE_RATE << 44) | ((long) (CHANNELS - 1) << 41)
                | ((long) (bits - 1) << 36) | totalSamples);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(metadata);
        for (int index = 0; index < frames; index++) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            int depthCode = bits == 8 ? 1 : bits == 16 ? 4 : 6;
            frame.write(new byte[]{(byte) 0xff, (byte) 0xf8, 0x69,
                    (byte) (0x10 | (depthCode << 1)), (byte) index, BLOCK_SIZE - 1});
            frame.write(crc(frame.toByteArray(), 8, 0x07));
            for (int channel = 0; channel < CHANNELS; channel++) {
                frame.write(0); // 常量子帧，无 wasted bits。
                int value = sampleValue(bits, channel);
                for (int octet = bits / 8 - 1; octet >= 0; octet--) frame.write(value >>> (8 * octet));
            }
            int checksum = crc(frame.toByteArray(), 16, 0x8005);
            frame.write(checksum >>> 8);
            frame.write(checksum);
            frame.writeTo(output);
        }
        return output.toByteArray();
    }

    private static int sampleValue(int bits, int channel) {
        int left = bits == 8 ? 0x12 : bits == 16 ? 0x1234 : 0x123456;
        int right = bits == 8 ? -0x23 : bits == 16 ? -0x2345 : -0x234567;
        return channel == 0 ? left : right;
    }

    private static int crc(byte[] bytes, int bits, int polynomial) {
        int checksum = 0;
        int topBit = 1 << (bits - 1);
        int mask = (1 << bits) - 1;
        for (byte value : bytes) {
            checksum ^= (value & 0xff) << (bits - 8);
            for (int bit = 0; bit < 8; bit++) {
                checksum = ((checksum << 1) ^ ((checksum & topBit) != 0 ? polynomial : 0)) & mask;
            }
        }
        return checksum;
    }
}
