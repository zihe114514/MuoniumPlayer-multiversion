package com.muoniumplayer.core.ncm.music;

import repackage.org.kc7bfi.jflac.FLACDecoder;
import repackage.org.kc7bfi.jflac.PCMProcessor;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;
import repackage.org.kc7bfi.jflac.io.RandomFileInputStream;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.function.DoubleConsumer;

/**
 * 将 FLAC 在播放前解成 WAV。
 *
 * <p>FLAC 的旧播放路径是 {@code BufferedFloatSample}：缺窗口时由 JSyn 引擎线程
 * 同步驱动 jFLAC 解码。那条路径即使能正常播放，也会把切歌/首个窗口的解码抖动直接
 * 暴露给声卡。这里沿用 AAC/OGG 的边车策略，使 FLAC 也进入 RAF 预取环。</p>
 */
final class FlacAudioDecoder {
    private static final int WAV_HEADER_SIZE = 44;

    private FlacAudioDecoder() {
    }

    static File decodeToWav(File input, File destination, DoubleConsumer progress) throws IOException {
        if (input == null || !input.isFile()) {
            throw new IOException("FLAC input file does not exist");
        }
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Unable to replace incomplete FLAC decode cache: " + temporary.getName());
        }

        boolean completed = false;
        report(progress, 0.0);
        try (RandomFileInputStream stream = new RandomFileInputStream(input);
             FlacWaveWriter writer = new FlacWaveWriter(temporary)) {
            FLACDecoder decoder = new FLACDecoder(stream);
            decoder.addPCMProcessor(new PCMProcessor() {
                @Override
                public void processStreamInfo(StreamInfo info) {
                    try {
                        writer.begin(info);
                    } catch (IOException failure) {
                        throw new DecodeFailure(failure);
                    }
                }

                @Override
                public void processPCM(ByteData pcm) {
                    try {
                        writer.write(pcm);
                        report(progress, Math.min(0.99,
                                (double) decoder.getTotalBytesRead() / Math.max(1L, input.length())));
                    } catch (IOException failure) {
                        throw new DecodeFailure(failure);
                    }
                }
            });
            decoder.decode();
            if (decoder.getBadFrames() != 0) {
                throw new IOException("FLAC contains " + decoder.getBadFrames() + " invalid frame(s)");
            }
            writer.finish();
            completed = true;
        } catch (DecodeFailure failure) {
            throw failure.io;
        } finally {
            if (!completed && temporary.exists()) {
                temporary.delete();
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace decoded FLAC cache: " + destination.getName());
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Unable to finalize decoded FLAC cache: " + destination.getName());
        }
        report(progress, 1.0);
        return destination;
    }

    private static void report(DoubleConsumer progress, double value) {
        if (progress == null) return;
        try {
            progress.accept(Math.max(0.0, Math.min(1.0, value)));
        } catch (Throwable ignored) {
        }
    }

    private static final class DecodeFailure extends RuntimeException {
        private final IOException io;

        private DecodeFailure(IOException io) {
            super(io);
            this.io = io;
        }
    }

    private static final class FlacWaveWriter implements AutoCloseable {
        private final RandomAccessFile output;
        private int sampleRate = -1;
        private int channels = -1;
        private int bitsPerSample = -1;
        private long expectedSamples;
        private long dataBytes;
        private boolean finished;

        private FlacWaveWriter(File outputFile) throws IOException {
            output = new RandomAccessFile(outputFile, "rw");
            output.setLength(0L);
            output.write(new byte[WAV_HEADER_SIZE]);
        }

        private void begin(StreamInfo info) throws IOException {
            if (info == null || info.getSampleRate() <= 0 || info.getChannels() <= 0
                    || info.getChannels() > 8
                    || (info.getBitsPerSample() != 8 && info.getBitsPerSample() != 16
                    && info.getBitsPerSample() != 24)) {
                throw new IOException("Unsupported FLAC PCM format");
            }
            sampleRate = info.getSampleRate();
            channels = info.getChannels();
            bitsPerSample = info.getBitsPerSample();
            expectedSamples = info.getTotalSamples();
        }

        private void write(ByteData pcm) throws IOException {
            if (pcm == null || pcm.getLen() <= 0) return;
            if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
                throw new IOException("FLAC PCM arrived before STREAMINFO");
            }
            int bytesPerSample = (bitsPerSample + 7) / 8;
            int frameBytes = channels * bytesPerSample;
            if (frameBytes <= 0 || pcm.getLen() % frameBytes != 0) {
                throw new IOException("FLAC PCM frame has an invalid byte count");
            }
            long limit = PlaybackMemoryLimits.maxDecodedPcmBytes();
            if (dataBytes + pcm.getLen() > limit) {
                throw new IOException("解码中止：" + PlaybackMemoryLimits.describeOverLimit(dataBytes)
                        + "（避免游戏内存溢出）");
            }
            output.write(pcm.getData(), 0, pcm.getLen());
            dataBytes += pcm.getLen();
        }

        private void finish() throws IOException {
            if (finished) return;
            if (sampleRate <= 0 || channels <= 0 || dataBytes <= 0) {
                throw new IOException("FLAC stream did not decode to PCM audio");
            }
            if (dataBytes > 0xFFFFFFFFL - 36L) {
                throw new IOException("Decoded FLAC WAV exceeds the 4 GiB RIFF limit");
            }
            int blockAlign = channels * ((bitsPerSample + 7) / 8);
            long decodedSamples = dataBytes / blockAlign;
            if (expectedSamples > 0L && decodedSamples != expectedSamples) {
                throw new IOException("FLAC sample count mismatch: expected " + expectedSamples
                        + ", decoded " + decodedSamples);
            }
            long byteRate = (long) sampleRate * blockAlign;
            if (byteRate > 0xFFFFFFFFL) throw new IOException("Decoded FLAC byte rate is out of WAV range");
            output.seek(0L);
            writeAscii("RIFF");
            writeLittleEndianInt(36L + dataBytes);
            writeAscii("WAVEfmt ");
            writeLittleEndianInt(16L);
            writeLittleEndianShort(1);
            writeLittleEndianShort(channels);
            writeLittleEndianInt(sampleRate);
            writeLittleEndianInt(byteRate);
            writeLittleEndianShort(blockAlign);
            writeLittleEndianShort(bitsPerSample);
            writeAscii("data");
            writeLittleEndianInt(dataBytes);
            finished = true;
        }

        private void writeAscii(String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        private void writeLittleEndianShort(int value) throws IOException {
            output.write(value & 0xFF);
            output.write((value >>> 8) & 0xFF);
        }

        private void writeLittleEndianInt(long value) throws IOException {
            output.write((int) value & 0xFF);
            output.write((int) (value >>> 8) & 0xFF);
            output.write((int) (value >>> 16) & 0xFF);
            output.write((int) (value >>> 24) & 0xFF);
        }

        @Override
        public void close() throws IOException {
            try {
                if (!finished) output.setLength(0L);
            } finally {
                output.close();
            }
        }
    }
}
