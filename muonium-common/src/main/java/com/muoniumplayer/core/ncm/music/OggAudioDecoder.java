package com.muoniumplayer.core.ncm.music;

import repackage.com.jcraft.jogg.Packet;
import repackage.com.jcraft.jogg.Page;
import repackage.com.jcraft.jogg.StreamState;
import repackage.com.jcraft.jogg.SyncState;
import repackage.com.jcraft.jorbis.Block;
import repackage.com.jcraft.jorbis.Comment;
import repackage.com.jcraft.jorbis.DspState;
import repackage.com.jcraft.jorbis.Info;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 OGG Vorbis（QQ 音乐 O801/O800/O600 取流档，以及本地 .ogg）解码为 16 位 PCM WAV 缓存。
 *
 * <h3>两条解码路径（与 {@link AacAudioDecoder} 同构）</h3>
 * <p>首选外部 ffmpeg（原生 SIMD；本项目分发体系 {@link FfmpegSupport} 伴生包自带，实测 9.0
 * essentials build 含 vorbis 解码器），拿不到 ffmpeg 或它失败时退回内置 jorbis 纯 Java 解码器
 * （{@code repackage.com.jcraft}，2026-11-04 自 jorbis 0.0.17 sources 收编，33 个源文件含
 * jogg 容器类）。两条路径产出同构的 16 位 PCM WAV，上层分不出区别。</p>
 *
 * <p>jorbis 兜底为什么必须有：OGG 是 QQ 现在 HQ 档的存量交付格式（MP3 320 大面积停发），
 * 「没装伴生包」不能等价于「QQ 音乐没有音质」。</p>
 */
final class OggAudioDecoder {
    private static final int WAV_HEADER_SIZE = 44;

    /** ffmpeg 转码的挂钟上限，与 AAC 路径同一纪律：绝不吊住取流线程。 */
    private static final long FFMPEG_TIMEOUT_MILLIS = 90_000L;

    private OggAudioDecoder() {
    }

    interface ProgressListener {
        void onProgress(double progress);
    }

    static File decodeToWav(File input, File destination) throws IOException {
        return decodeToWav(input, destination, null);
    }

    static File decodeToWav(File input, File destination, ProgressListener progressListener) throws IOException {
        if (input == null || !input.isFile()) {
            throw new IOException("OGG input file does not exist");
        }
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Unable to replace incomplete OGG decode cache: " + temporary.getName());
        }

        boolean completed = false;
        reportProgress(progressListener, 0.0);
        // 快路径：外部 ffmpeg 直接转 16 位 PCM WAV；失败安静退回 jorbis。
        if (decodeWithFfmpeg(input, temporary)) {
            completed = true;
            reportProgress(progressListener, 1.0);
        }
        if (!completed) {
            try (VorbisPcmWriter writer = new VorbisPcmWriter(temporary)) {
                decodeWithJorbis(input, writer, progressListener);
                writer.finish();
                reportProgress(progressListener, 1.0);
                completed = true;
            } finally {
                if (!completed && temporary.exists()) {
                    temporary.delete();
                }
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace decoded OGG cache: " + destination.getName());
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Unable to finalize decoded OGG cache: " + destination.getName());
        }
        return destination;
    }

    /** 外部 ffmpeg 快路径：OGG Vorbis 直接转 16 位 PCM WAV。语义与 AacAudioDecoder 同款。 */
    private static boolean decodeWithFfmpeg(File input, File output) {
        String executable = FfmpegSupport.executable();
        if (executable == null) {
            return false;
        }
        Process process = null;
        try {
            List<String> command = new ArrayList<String>();
            command.add(executable);
            command.add("-hide_banner");
            command.add("-v");
            command.add("error");
            command.add("-nostdin");
            command.add("-y");
            command.add("-threads");
            command.add("1");
            command.add("-i");
            command.add(input.getAbsolutePath());
            command.add("-vn");
            command.add("-map");
            command.add("a:0?");
            command.add("-c:a");
            command.add("pcm_s16le");
            command.add("-f");
            command.add("wav");
            command.add(output.getAbsolutePath());

            process = new ProcessBuilder(command).start();
            drainQuietly(process.getErrorStream());
            if (!process.waitFor(FFMPEG_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy();
                try {
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                System.err.println("[Music] ffmpeg OGG transcode timed out, falling back to jorbis: "
                        + input.getName());
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            if (!output.isFile() || output.length() <= WAV_HEADER_SIZE) {
                return false;
            }
            return "wav".equals(AudioContainerSupport.detectContainer(output));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    /** jorbis 纯 Java 兜底：逐页 sync → 逐包 block → PCM 直写 WAV。 */
    private static void decodeWithJorbis(File input, VorbisPcmWriter writer,
                                         ProgressListener progressListener) throws IOException {
        try (InputStream stream = new BufferedInputStream(new FileInputStream(input), 1 << 16)) {
            SyncState sync = new SyncState();
            StreamState streamState = new StreamState();
            Info info = new Info();
            Comment comment = new Comment();
            DspState dsp = new DspState();
            Block block = new Block(dsp);

            sync.init();
            // ① 头部段：前三个包 identification / comment / setup
            byte[] buffer = new byte[8192];
            int headerPackets = 0;
            while (headerPackets < 3) {
                int read = stream.read(buffer);
                if (read < 0) {
                    throw new EOFException("OGG stream ended before all Vorbis headers");
                }
                int fill = sync.buffer(read);
                System.arraycopy(buffer, 0, sync.data, fill, read);
                sync.wrote(read);
                while (headerPackets < 3) {
                    Page page = new Page();
                    int pageResult = sync.pageout(page);
                    if (pageResult == 0) break;               // 需要更多数据
                    if (pageResult == -1) continue;           // 损坏段，跳过
                    streamState.pagein(page);
                    while (headerPackets < 3) {
                        Packet packet = new Packet();
                        int packetResult = streamState.packetout(packet);
                        if (packetResult == 0) break;
                        if (packetResult == -1) continue;
                        if (info.synthesis_headerin(comment, packet) < 0) {
                            throw new IOException("OGG stream is not Vorbis audio (header rejected)");
                        }
                        headerPackets++;
                    }
                }
            }
            if (info.rate <= 0 || info.channels <= 0) {
                throw new IOException("OGG header lacks usable PCM format");
            }
            writer.begin(info.rate, info.channels);
            dsp.synthesis_init(info);
            block.init(dsp);

            // ② 音频段：整流整包解码，float planar PCM 交给 writer
            long totalBytes = input.length();
            long consumedBytes = 0;
            float[][][] pcmHolder = new float[1][][];
            int[] indexHolder = new int[1];
            long decodedSamples = 0;
            while (true) {
                int read = stream.read(buffer);
                if (read >= 0) {
                    consumedBytes += read;
                    int fill = sync.buffer(read);
                    System.arraycopy(buffer, 0, sync.data, fill, read);
                    sync.wrote(read);
                }
                while (true) {
                    Page page = new Page();
                    int pageResult = sync.pageout(page);
                    if (pageResult == 0) break;
                    if (pageResult == -1) continue;
                    streamState.pagein(page);
                    while (true) {
                        Packet packet = new Packet();
                        int packetResult = streamState.packetout(packet);
                        if (packetResult == 0) break;
                        if (packetResult == -1) continue;
                        if (block.synthesis(packet) == 0) {
                            // jorbis 的 PCM 取出是拉模式：blockin 入队，pcmout 分批取，
                            // read(n) 消费。一次 blockin 可能产出多批。
                            if (dsp.synthesis_blockin(block) == 0) {
                                int samples;
                                while ((samples = dsp.synthesis_pcmout(pcmHolder, indexHolder)) > 0) {
                                    writer.write(pcmHolder[0], info.channels, samples);
                                    decodedSamples += samples;
                                    dsp.synthesis_read(samples);
                                }
                            }
                        }
                    }

                }
                if (read < 0) {
                    // 流结束而 sync 还没报 eof：按已解码量定论。
                    if (decodedSamples == 0) {
                        throw new IOException("jorbis decoded zero samples (corrupt or non-Vorbis OGG)");
                    }
                    return;
                }
                if (progressListener != null && totalBytes > 0) {
                    reportProgress(progressListener, Math.min(0.95,
                            0.05 + 0.9 * (double) consumedBytes / (double) totalBytes));
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("jorbis failed to decode OGG stream", e);
        }
    }

    private static void reportProgress(ProgressListener listener, double progress) {
        if (listener != null) {
            try {
                listener.onProgress(progress);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void drainQuietly(InputStream stream) {
        if (stream == null) return;
        Thread drain = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                while (stream.read(buffer) >= 0) {
                    // 排空即可
                }
            } catch (Throwable ignored) {
            }
        }, "muonium-ffmpeg-ogg-stderr");
        drain.setDaemon(true);
        drain.start();
    }

    /**
     * OGG 专用的 16 位 LE PCM WAV 写出器。与 AAC 路径的 PcmWaveWriter 产出同构
     * （RIFF 头在 finish 时回填），但输入是 jorbis 的 planar float PCM——采样率/声道
     * 来自 identification 头（先于首帧可知），所以 begin() 二段初始化而不是首帧推断。
     */
    private static final class VorbisPcmWriter implements AutoCloseable {
        private final RandomAccessFile output;
        private int sampleRate;
        private int channels;
        private long dataBytes;
        private boolean finished;

        private VorbisPcmWriter(File outputFile) throws IOException {
            this.output = new RandomAccessFile(outputFile, "rw");
            this.output.setLength(0L);
            this.output.write(new byte[WAV_HEADER_SIZE]);
            this.sampleRate = -1;
            this.channels = -1;
        }

        /** 头部段解码完成时调用：OGG 的采样率/声道来自 identification 头，先于首帧。 */
        void begin(int rate, int channelCount) throws IOException {
            if (rate <= 0 || channelCount <= 0 || channelCount > 2) {
                throw new IOException("Unsupported OGG PCM format: " + rate + "Hz, " + channelCount + " channels");
            }
            this.sampleRate = rate;
            this.channels = channelCount;
        }

        void write(float[][] channelPcm, int channelCount, int sampleCount) throws IOException {
            if (channelPcm == null || channelCount <= 0 || channelCount > 2 || sampleCount <= 0) {
                return;
            }
            int frameSize = channelCount * 2;
            byte[] data = new byte[sampleCount * frameSize];
            int offset = 0;
            for (int i = 0; i < sampleCount; i++) {
                for (int ch = 0; ch < channelCount; ch++) {
                    float v = channelPcm[ch][i];
                    if (v > 1.0f) v = 1.0f;
                    if (v < -1.0f) v = -1.0f;
                    int intSample = (int) (v * 32767.0f);
                    data[offset++] = (byte) intSample;
                    data[offset++] = (byte) (intSample >> 8);
                }
            }
            if (dataBytes + data.length > 0xFFFFFFFFL) {
                throw new IOException("Decoded OGG WAV exceeds the 4 GiB RIFF limit");
            }
            long pcmLimit = PlaybackMemoryLimits.maxDecodedPcmBytes();
            if (dataBytes + data.length > pcmLimit) {
                throw new IOException("解码中止：" + PlaybackMemoryLimits.describeOverLimit(dataBytes)
                        + "（避免游戏内存溢出）");
            }
            output.write(data);
            dataBytes += data.length;
        }

        void finish() throws IOException {
            if (finished) {
                return;
            }
            if (sampleRate <= 0 || channels <= 0 || dataBytes <= 0) {
                throw new IOException("OGG stream did not decode to PCM audio");
            }
            int blockAlign = channels * 2;
            long byteRate = (long) sampleRate * blockAlign;
            if (byteRate > Integer.MAX_VALUE) {
                throw new IOException("Decoded OGG byte rate is out of WAV range");
            }
            output.seek(0L);
            writeAscii("RIFF");
            writeLittleEndianInt(36L + dataBytes);
            writeAscii("WAVE");
            writeAscii("fmt ");
            writeLittleEndianInt(16L);
            writeLittleEndianShort(1);
            writeLittleEndianShort(channels);
            writeLittleEndianInt(sampleRate);
            writeLittleEndianInt(byteRate);
            writeLittleEndianShort(blockAlign);
            writeLittleEndianShort(16);
            writeAscii("data");
            writeLittleEndianInt(dataBytes);
            finished = true;
        }

        @Override
        public void close() throws IOException {
            output.close();
        }

        private void writeAscii(String value) throws IOException {
            for (int i = 0; i < value.length(); i++) {
                output.write(value.charAt(i));
            }
        }

        private void writeLittleEndianInt(long value) throws IOException {
            output.write((int) (value & 0xFF));
            output.write((int) ((value >> 8) & 0xFF));
            output.write((int) ((value >> 16) & 0xFF));
            output.write((int) ((value >> 24) & 0xFF));
        }

        private void writeLittleEndianShort(int value) throws IOException {
            output.write(value & 0xFF);
            output.write((value >> 8) & 0xFF);
        }
    }
}
