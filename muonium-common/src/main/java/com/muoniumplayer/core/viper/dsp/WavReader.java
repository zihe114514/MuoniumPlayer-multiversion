// 逐行移植自 ViPERDSP/viper/utils/WavReader.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 上游为 header-only（ReadWavFile 静态函数 + WavData 结构）。本文件照抄其解析与解码逻辑，
// 含 16/24/32-bit 整型与 32-bit float、1/2/4 声道的处理。
package com.muoniumplayer.core.viper.dsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class WavReader {

    /** 等价替代上游 WavData 结构。 */
    public static final class WavData {
        public float[] samples;     // 交错排列的浮点样本
        public int frameCount;      // 帧数（每帧 channels 个样本）
        public int channels;        // 声道数（1/2/4）
        public int sampleRate;      // 采样率
    }

    private static final long K_MAX_SAMPLES = 4L * 1024L * 1024L;

    private WavReader() {
    }

    /**
     * 等价替代 {@code ReadWavFile(const char *path, WavData *out)}。
     *
     * @param path 输入 WAV 文件路径
     * @param out   输出结构，成功时填充 samples / frameCount / channels / sampleRate
     * @return 成功返回 true
     */
    public static boolean readWavFile(String path, WavData out) {
        if (path == null || out == null) {
            return false;
        }
        out.samples = null;
        out.frameCount = 0;
        out.channels = 0;
        out.sampleRate = 0;

        final byte[] data;
        try {
            data = Files.readAllBytes(Paths.get(path));
        } catch (IOException | RuntimeException e) {
            return false;
        }
        final int fileSize = data.length;
        if (fileSize < 12) {
            return false;
        }

        // RIFF / WAVE 标识。
        if (!match(data, 0, "RIFF") || !match(data, 8, "WAVE")) {
            return false;
        }

        int formatCode = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int blockAlign = 0;
        int sampleRate = 0;
        long dataOffset = 0;
        long dataSize = 0;
        boolean haveFormat = false;
        boolean haveData = false;

        int pos = 12;
        while ((long) pos + 8 <= fileSize) {
            final String chunkId = readFourCc(data, pos);
            final long chunkSize = readU32(data, pos + 4);
            final int chunkStart = pos + 8;
            final long chunkEnd = (long) chunkStart + chunkSize;
            if (chunkEnd < chunkStart || chunkEnd > fileSize) {
                return false;
            }

            if (chunkId.equals("fmt ")) {
                if (chunkSize < 16) {
                    return false;
                }
                final int readSize = (int) Math.min(chunkSize, 40);
                formatCode = readU16(data, chunkStart);
                channels = readU16(data, chunkStart + 2);
                sampleRate = (int) readU32(data, chunkStart + 4);
                blockAlign = readU16(data, chunkStart + 12);
                bitsPerSample = readU16(data, chunkStart + 14);

                if (formatCode == 0xFFFE) {
                    // WAVE_FORMAT_EXTENSIBLE
                    final byte[] guidTail = {
                            0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00,
                            0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71
                    };
                    if (chunkSize < 40 || readU16(data, chunkStart + 16) < 22
                            || !matchBytes(data, chunkStart + 28, guidTail)) {
                        return false;
                    }
                    formatCode = (int) readU32(data, chunkStart + 24);
                }
                haveFormat = true;
            } else if (chunkId.equals("data")) {
                dataOffset = chunkStart;
                dataSize = chunkSize;
                haveData = true;
            }

            long nextChunk = chunkEnd + (chunkSize & 1L);
            if (nextChunk > fileSize || nextChunk > Integer.MAX_VALUE) {
                return false;
            }
            pos = (int) nextChunk;
            if (haveFormat && haveData) {
                break;
            }
        }

        final boolean supportedChannels = channels == 1 || channels == 2 || channels == 4;
        final boolean isFloat = formatCode == 3;
        final boolean supportedFormat = formatCode == 1 || isFloat;
        final boolean supportedDepth =
                (isFloat && bitsPerSample == 32)
                        || (!isFloat && (bitsPerSample == 16 || bitsPerSample == 24
                        || bitsPerSample == 32));
        if (!haveFormat || !haveData || !supportedChannels || !supportedFormat
                || !supportedDepth || sampleRate == 0 || dataSize == 0) {
            return false;
        }

        final int bytesPerSample = bitsPerSample / 8;
        final int expectedAlign = channels * bytesPerSample;
        if (blockAlign != expectedAlign || dataSize % expectedAlign != 0) {
            return false;
        }
        final long frameCount64 = dataSize / expectedAlign;
        final long totalSamples64 = frameCount64 * channels;
        if (frameCount64 == 0 || frameCount64 > 0xFFFFFFFFL
                || totalSamples64 > K_MAX_SAMPLES
                || totalSamples64 > 0xFFFFFFFFL
                || totalSamples64 > Integer.MAX_VALUE) {
            return false;
        }
        final int frameCount = (int) frameCount64;
        final int totalSamples = (int) totalSamples64;
        if (dataOffset + dataSize > fileSize) {
            return false;
        }

        final float[] samples = new float[totalSamples];
        boolean decoded = false;
        if (isFloat) {
            if (bytesPerSample != 4) {
                return false;
            }
            for (int i = 0; i < totalSamples; i++) {
                samples[i] = Float.intBitsToFloat((int) readU32(data, (int) dataOffset + i * 4));
            }
            decoded = true;
        } else if (bitsPerSample == 16) {
            for (int i = 0; i < totalSamples; i++) {
                final int v = (short) readU16(data, (int) dataOffset + i * 2);
                samples[i] = (float) v / 32768.0F;
            }
            decoded = true;
        } else if (bitsPerSample == 24) {
            for (int i = 0; i < totalSamples; i++) {
                final int off = (int) dataOffset + i * 3;
                int value = (data[off] & 0xff)
                        | ((data[off + 1] & 0xff) << 8)
                        | ((data[off + 2] & 0xff) << 16);
                if ((value & 0x00800000) != 0) {
                    value |= 0xFF000000;
                }
                samples[i] = (float) value / 8388608.0F;
            }
            decoded = true;
        } else { // 32-bit int
            for (int i = 0; i < totalSamples; i++) {
                final int v = (int) readU32(data, (int) dataOffset + i * 4);
                samples[i] = (float) v / 2147483648.0F;
            }
            decoded = true;
        }

        if (!decoded) {
            return false;
        }
        out.samples = samples;
        out.frameCount = frameCount;
        out.channels = channels;
        out.sampleRate = sampleRate;
        return true;
    }

    private static boolean match(byte[] data, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (data[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchBytes(byte[] data, int offset, byte[] ref) {
        for (int i = 0; i < ref.length; i++) {
            if (data[offset + i] != ref[i]) {
                return false;
            }
        }
        return true;
    }

    private static String readFourCc(byte[] data, int offset) {
        final char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            c[i] = (char) (data[offset + i] & 0xff);
        }
        return new String(c);
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long readU32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }
}
