package com.muoniumplayer.core.ncm.music;

import repackage.net.sourceforge.jaad.aac.AACException;
import repackage.net.sourceforge.jaad.aac.Decoder;
import repackage.net.sourceforge.jaad.aac.SampleBuffer;
import repackage.net.sourceforge.jaad.adts.ADTSDemultiplexer;
import repackage.net.sourceforge.jaad.mp4.MP4Container;
import repackage.net.sourceforge.jaad.mp4.api.AudioTrack;
import repackage.net.sourceforge.jaad.mp4.api.Frame;
import repackage.net.sourceforge.jaad.mp4.api.Movie;
import repackage.net.sourceforge.jaad.mp4.api.Track;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 把通常以 ADTS（.aac）、ISO-BMFF/M4A（.m4a）以及含音频的 MP4/DASH（.m4s）形式返回的 AAC 流
 * 解码为标准的 16 位 PCM WAV 缓存。
 * 不含可解码 AAC 音轨的文件将按失败处理（fail closed）。
 *
 * <h3>两条解码路径</h3>
 * <p>首选外部 ffmpeg（原生 SIMD、多核解码，本项目 {@link FfmpegSupport} 已在用），拿不到
 * ffmpeg 或它失败时退回内置的 JAAD 纯 Java 解码器。两条路径产出完全同构的 16 位 PCM WAV，
 * 上层（{@code RafSampleLoader} 流式读）分不出区别；ffmpeg 这条路只是把「与曲长成正比的
 * 等待」压短一档 —— 汽水每首新歌都要过这一趟，用户是直接等着的。</p>
 */
final class AacAudioDecoder {
    private static final int WAV_HEADER_SIZE = 44;

    /** ffmpeg 转码的挂钟上限。超时按失败处理并退回 JAAD，绝不吊住取流线程。 */
    private static final long FFMPEG_TIMEOUT_MILLIS = 90_000L;

    private AacAudioDecoder() {
    }

    interface ProgressListener {
        void onProgress(double progress);
    }

    static File decodeToWav(File input, String container, File destination) throws IOException {
        return decodeToWav(input, container, destination, null);
    }

    static File decodeToWav(File input, String container, File destination,
                            ProgressListener progressListener) throws IOException {
        if (input == null || !input.isFile()) {
            throw new IOException("AAC input file does not exist");
        }
        if (!"aac".equals(container) && !"m4a".equals(container) && !"mp4".equals(container)) {
            throw new IOException("Unsupported AAC container: " + container);
        }

        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Unable to replace incomplete AAC decode cache: " + temporary.getName());
        }

        boolean completed = false;
        File decoderInput = input;
        reportProgress(progressListener, 0.0);
        // 快路径：外部 ffmpeg 直接转 16 位 PCM WAV。它支持 ADTS 与 ISO-BMFF 两种输入，
        // 且是原生解码；失败（没装、超时、载荷异常）一律安静退回下面的 JAAD 路径，
        // 由那条路给出正式的成败结论。
        if (decodeWithFfmpeg(input, temporary, progressListener)) {
            completed = true;
            reportProgress(progressListener, 1.0);
        }
        if (!completed) {
            try (PcmWaveWriter writer = new PcmWaveWriter(temporary)) {
                if ("aac".equals(container)) {
                    decodeAdts(input, writer, progressListener);
                } else {
                    // 部分 JOOX M4A 文件包含旧版 QuickTime "tags" atom。JAAD 的 MP4
                    // 解复用器会错误计算该 atom 实际消耗的长度，从而无限期寻址。
                    // 仅把这个元数据 atom 重标记为等大小的 free atom，可保留每一个
                    // 音频字节和每个采样偏移，同时让内嵌的 AAC 解码器
                    // 正常处理该流。
                    decoderInput = sanitizeJaadIncompatibleMetadata(input, destination);
                    decodeIsoBaseMedia(decoderInput, writer, progressListener);
                }
                writer.finish();
                reportProgress(progressListener, 1.0);
                completed = true;
            } catch (AACException e) {
                throw new IOException("AAC decoder rejected the audio stream", e);
            } finally {
                if (!decoderInput.equals(input) && decoderInput.exists() && !decoderInput.delete()) {
                    System.err.println("[Music] Unable to remove temporary sanitized AAC input: " + decoderInput.getName());
                }
                if (!completed && temporary.exists()) {
                    temporary.delete();
                }
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace decoded AAC cache: " + destination.getName());
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Unable to finalize decoded AAC cache: " + destination.getName());
        }
        return destination;
    }

    private static void decodeAdts(File input, PcmWaveWriter writer,
                                   ProgressListener progressListener) throws IOException, AACException {
        try (ProgressInputStream raw = new ProgressInputStream(new FileInputStream(input), input.length(), progressListener);
             BufferedInputStream stream = new BufferedInputStream(raw)) {
            ADTSDemultiplexer demultiplexer = new ADTSDemultiplexer(stream);
            Decoder decoder = new Decoder(demultiplexer.getDecoderSpecificInfo());
            SampleBuffer buffer = new SampleBuffer();
            while (true) {
                try {
                    decoder.decodeFrame(demultiplexer.readNextFrame(), buffer);
                } catch (EOFException endOfStream) {
                    break;
                }
                writer.write(buffer);
                reportProgress(progressListener, raw.getProgress());
            }
        }
    }

    /**
     * 转码 M4A 文件中第一条可解码的 AAC 音轨。
     * DRM 保护和非 AAC 文件按失败处理，以便调用方请求标准 MP3 降级。
     */
    private static void decodeIsoBaseMedia(File input, PcmWaveWriter writer,
                                           ProgressListener progressListener) throws IOException, AACException {
        try (RandomAccessFile file = new RandomAccessFile(input, "r")) {
            MP4Container container = new MP4Container(file);
            Movie movie = container.getMovie();
            List<Track> tracks = movie == null ? null : movie.getTracks(AudioTrack.AudioCodec.AAC);
            if (tracks == null || tracks.isEmpty() || !(tracks.get(0) instanceof AudioTrack)) {
                throw new IOException("ISO-BMFF file does not contain a supported AAC audio track");
            }

            AudioTrack track = (AudioTrack) tracks.get(0);
            Decoder decoder = new Decoder(track.getDecoderSpecificInfo());
            SampleBuffer buffer = new SampleBuffer();
            boolean decodedFromSampleTable = false;
            while (track.hasMoreFrames()) {
                Frame frame = track.readNextFrame();
                if (frame == null || frame.getData() == null || frame.getData().length == 0) {
                    throw new IOException("ISO-BMFF AAC track contains an invalid audio frame");
                }
                decoder.decodeFrame(frame.getData(), buffer);
                writer.write(buffer);
                decodedFromSampleTable = true;
                long endOffset = frame.getOffset() + frame.getSize();
                reportProgress(progressListener, input.length() <= 0L
                        ? 0.0 : (double) endOffset / (double) input.length());
            }

            // Bilibili DASH 音频是分片 MP4（.m4s）：初始 moov atom 中
            // 采样表为空，而 AAC 帧由 moof/trun 分片描述。
            // JAAD 能从 moov 读取 AAC 解码器配置，但读不到这些分片。
            if (!decodedFromSampleTable) {
                decodeFragmentedMp4Aac(file, decoder, writer, progressListener);
            }
        }
    }

    /**
     * 解码未加密的分片 MP4 AAC 流，例如 Bilibili 纯音频 DASH .m4s
     * 载荷。我们有意只支持 Bilibili 常用的 tfhd/trun 子集，
     * 对畸形/加密分片按失败处理，而不是输出一个卡死的转码结果。
     */
    private static void decodeFragmentedMp4Aac(RandomAccessFile file, Decoder decoder, PcmWaveWriter writer,
                                               ProgressListener progressListener) throws IOException, AACException {
        long length = file.length();
        long offset = 0L;
        int decodedFrames = 0;
        while (offset + 8L <= length) {
            Mp4Box box = readMp4Box(file, offset, length);
            if (box == null) break;
            if ("moof".equals(box.type)) {
                Mp4Box mediaData = findFollowingMediaDataBox(file, box.end, length);
                if (mediaData == null) {
                    throw new IOException("Fragmented MP4 moof has no following mdat payload");
                }
                decodedFrames += decodeMovieFragment(file, box, mediaData, decoder, writer, progressListener, length);
            }
            offset = box.end;
        }
        if (decodedFrames <= 0) {
            throw new IOException("ISO-BMFF file has neither sample-table nor DASH AAC frames");
        }
    }

    private static Mp4Box findFollowingMediaDataBox(RandomAccessFile file, long offset, long length)
            throws IOException {
        long cursor = offset;
        while (cursor + 8L <= length) {
            Mp4Box box = readMp4Box(file, cursor, length);
            if (box == null) return null;
            if ("mdat".equals(box.type)) return box;
            // 下一个分片在媒体数据之前开始：切勿误把它当作采样读取。
            if ("moof".equals(box.type)) return null;
            cursor = box.end;
        }
        return null;
    }

    private static int decodeMovieFragment(RandomAccessFile file, Mp4Box movieFragment, Mp4Box mediaData,
                                           Decoder decoder, PcmWaveWriter writer,
                                           ProgressListener progressListener, long fileLength)
            throws IOException, AACException {
        int decodedFrames = 0;
        long cursor = movieFragment.dataStart;
        while (cursor + 8L <= movieFragment.end) {
            Mp4Box child = readMp4Box(file, cursor, movieFragment.end);
            if (child == null) break;
            if ("traf".equals(child.type)) {
                decodedFrames += decodeTrackFragment(file, child, movieFragment, mediaData, decoder, writer,
                        progressListener, fileLength);
            }
            cursor = child.end;
        }
        return decodedFrames;
    }

    private static int decodeTrackFragment(RandomAccessFile file, Mp4Box trackFragment, Mp4Box movieFragment,
                                           Mp4Box mediaData, Decoder decoder, PcmWaveWriter writer,
                                           ProgressListener progressListener, long fileLength)
            throws IOException, AACException {
        int defaultSampleSize = -1;
        boolean defaultBaseIsMoof = false;
        long nextSampleOffset = mediaData.dataStart;
        int decodedFrames = 0;

        long cursor = trackFragment.dataStart;
        while (cursor + 8L <= trackFragment.end) {
            Mp4Box child = readMp4Box(file, cursor, trackFragment.end);
            if (child == null) break;
            if ("tfhd".equals(child.type)) {
                TfhdDefaults defaults = readTfhdDefaults(file, child);
                defaultSampleSize = defaults.defaultSampleSize;
                defaultBaseIsMoof = defaults.defaultBaseIsMoof;
            } else if ("trun".equals(child.type)) {
                TrunResult result = decodeTrackRun(file, child, movieFragment, mediaData, defaultSampleSize,
                        defaultBaseIsMoof, nextSampleOffset, decoder, writer, progressListener, fileLength);
                nextSampleOffset = result.nextSampleOffset;
                decodedFrames += result.decodedFrames;
            }
            cursor = child.end;
        }
        return decodedFrames;
    }

    private static TfhdDefaults readTfhdDefaults(RandomAccessFile file, Mp4Box box) throws IOException {
        file.seek(box.dataStart);
        int flags = readFullBoxFlags(file, box);
        requireRemaining(file, box.end, 4L, "tfhd track id");
        readUnsignedInt(file); // track_ID；Bilibili 音频 .m4s 只包含一个音频 traf。
        if ((flags & 0x000001) != 0) {
            requireRemaining(file, box.end, 8L, "tfhd base data offset");
            file.readLong();
        }
        if ((flags & 0x000002) != 0) {
            requireRemaining(file, box.end, 4L, "tfhd sample description index");
            readUnsignedInt(file);
        }
        if ((flags & 0x000008) != 0) {
            requireRemaining(file, box.end, 4L, "tfhd default sample duration");
            readUnsignedInt(file);
        }
        int defaultSampleSize = -1;
        if ((flags & 0x000010) != 0) {
            requireRemaining(file, box.end, 4L, "tfhd default sample size");
            long size = readUnsignedInt(file);
            defaultSampleSize = size > Integer.MAX_VALUE ? -1 : (int) size;
        }
        return new TfhdDefaults(defaultSampleSize, (flags & 0x020000) != 0);
    }

    private static TrunResult decodeTrackRun(RandomAccessFile file, Mp4Box box, Mp4Box movieFragment,
                                              Mp4Box mediaData, int defaultSampleSize, boolean defaultBaseIsMoof,
                                              long fallbackSampleOffset, Decoder decoder, PcmWaveWriter writer,
                                              ProgressListener progressListener, long fileLength)
            throws IOException, AACException {
        file.seek(box.dataStart);
        int flags = readFullBoxFlags(file, box);
        requireRemaining(file, box.end, 4L, "trun sample count");
        long rawSampleCount = readUnsignedInt(file);
        if (rawSampleCount <= 0L || rawSampleCount > 200_000L) {
            throw new IOException("Fragmented MP4 has an invalid trun sample count");
        }
        int sampleCount = (int) rawSampleCount;

        long sampleOffset = fallbackSampleOffset;
        if ((flags & 0x000001) != 0) {
            requireRemaining(file, box.end, 4L, "trun data offset");
            int relativeOffset = file.readInt();
            long baseOffset = defaultBaseIsMoof ? movieFragment.start : movieFragment.start;
            sampleOffset = baseOffset + relativeOffset;
        }
        if ((flags & 0x000004) != 0) {
            requireRemaining(file, box.end, 4L, "trun first sample flags");
            readUnsignedInt(file);
        }

        boolean hasSampleDuration = (flags & 0x000100) != 0;
        boolean hasSampleSize = (flags & 0x000200) != 0;
        boolean hasSampleFlags = (flags & 0x000400) != 0;
        boolean hasCompositionOffset = (flags & 0x000800) != 0;
        List<Integer> sampleSizes = new ArrayList<>(sampleCount);
        long totalSampleBytes = 0L;

        // 在寻址到 mdat 之前先读完整个 trun 表。把表解析与帧读取混在一起，
        // 会让 RandomAccessFile 离开 trun，这正是表被截断错误的根因。
        for (int index = 0; index < sampleCount; index++) {
            if (hasSampleDuration) {
                requireRemaining(file, box.end, 4L, "trun sample duration");
                readUnsignedInt(file);
            }
            int sampleSize = defaultSampleSize;
            if (hasSampleSize) {
                requireRemaining(file, box.end, 4L, "trun sample size");
                long size = readUnsignedInt(file);
                sampleSize = size > Integer.MAX_VALUE ? -1 : (int) size;
            }
            if (hasSampleFlags) {
                requireRemaining(file, box.end, 4L, "trun sample flags");
                readUnsignedInt(file);
            }
            if (hasCompositionOffset) {
                requireRemaining(file, box.end, 4L, "trun composition offset");
                readUnsignedInt(file);
            }
            if (sampleSize <= 0 || totalSampleBytes > Integer.MAX_VALUE - (long) sampleSize) {
                throw new IOException("Fragmented MP4 AAC sample has an invalid size");
            }
            sampleSizes.add(sampleSize);
            totalSampleBytes += sampleSize;
        }
        if (sampleOffset < mediaData.dataStart || sampleOffset > mediaData.end - totalSampleBytes) {
            throw new IOException("Fragmented MP4 AAC samples are outside the mdat payload");
        }

        SampleBuffer buffer = new SampleBuffer();
        for (Integer sampleSize : sampleSizes) {
            byte[] frame = new byte[sampleSize];
            file.seek(sampleOffset);
            file.readFully(frame);
            decoder.decodeFrame(frame, buffer);
            writer.write(buffer);
            sampleOffset += sampleSize;
            reportProgress(progressListener, fileLength <= 0L ? 0.0 : (double) sampleOffset / (double) fileLength);
        }
        return new TrunResult(sampleOffset, sampleCount);
    }
    private static int readFullBoxFlags(RandomAccessFile file, Mp4Box box) throws IOException {
        requireRemaining(file, box.end, 4L, "full box header");
        file.readUnsignedByte(); // 版本
        return (file.readUnsignedByte() << 16) | (file.readUnsignedByte() << 8) | file.readUnsignedByte();
    }

    private static void requireRemaining(RandomAccessFile file, long end, long amount, String field) throws IOException {
        if (amount < 0L || file.getFilePointer() > end - amount) {
            throw new IOException("Truncated fragmented MP4 " + field);
        }
    }

    private static Mp4Box readMp4Box(RandomAccessFile file, long start, long boundary) throws IOException {
        if (start < 0L || start + 8L > boundary) return null;
        file.seek(start);
        long size = readUnsignedInt(file);
        String type = readBoxType(file);
        long headerSize = 8L;
        if (size == 1L) {
            if (start + 16L > boundary) return null;
            size = file.readLong();
            headerSize = 16L;
        } else if (size == 0L) {
            size = boundary - start;
        }
        if (size < headerSize || size > boundary - start) return null;
        return new Mp4Box(start, start + headerSize, start + size, type);
    }

    private static final class Mp4Box {
        private final long start;
        private final long dataStart;
        private final long end;
        private final String type;

        private Mp4Box(long start, long dataStart, long end, String type) {
            this.start = start;
            this.dataStart = dataStart;
            this.end = end;
            this.type = type;
        }
    }

    private static final class TfhdDefaults {
        private final int defaultSampleSize;
        private final boolean defaultBaseIsMoof;

        private TfhdDefaults(int defaultSampleSize, boolean defaultBaseIsMoof) {
            this.defaultSampleSize = defaultSampleSize;
            this.defaultBaseIsMoof = defaultBaseIsMoof;
        }
    }

    private static final class TrunResult {
        private final long nextSampleOffset;
        private final int decodedFrames;

        private TrunResult(long nextSampleOffset, int decodedFrames) {
            this.nextSampleOffset = nextSampleOffset;
            this.decodedFrames = decodedFrames;
        }
    }

    /**
     * 仅当文件包含 JAAD 有问题的旧版 {@code udta/tags} 元数据 atom 时，才生成一份临时 MP4/M4A 副本。
     * 该 atom 仅为元数据，因此把其类型替换为
     * {@code free} 后，ISO-BMFF 布局、媒体数据和所有 chunk 偏移都保持不变。
     */
    private static File sanitizeJaadIncompatibleMetadata(File input, File destination) throws IOException {
        List<Long> tagsBoxes = new ArrayList<>();
        try (RandomAccessFile source = new RandomAccessFile(input, "r")) {
            collectLegacyTagsBoxes(source, 0L, source.length(), false, 0, tagsBoxes);
        }
        if (tagsBoxes.isEmpty()) {
            return input;
        }

        File sanitized = new File(destination.getParentFile(), destination.getName() + ".jaad-input");
        Files.copy(input.toPath(), sanitized.toPath(), StandardCopyOption.REPLACE_EXISTING);
        boolean completed = false;
        try (RandomAccessFile output = new RandomAccessFile(sanitized, "rw")) {
            for (Long boxOffset : tagsBoxes) {
                output.seek(boxOffset + 4L);
                output.write(new byte[]{'f', 'r', 'e', 'e'});
            }
            completed = true;
            return sanitized;
        } finally {
            if (!completed && sanitized.exists()) {
                sanitized.delete();
            }
        }
    }

    private static void collectLegacyTagsBoxes(RandomAccessFile file, long start, long end,
                                               boolean insideUserData, int depth,
                                               List<Long> tagsBoxes) throws IOException {
        if (depth > 16 || start < 0L || end < start || end > file.length()) {
            return;
        }
        long offset = start;
        while (offset + 8L <= end) {
            file.seek(offset);
            long size = readUnsignedInt(file);
            String type = readBoxType(file);
            long headerSize = 8L;
            if (size == 1L) {
                if (offset + 16L > end) return;
                size = file.readLong();
                headerSize = 16L;
            } else if (size == 0L) {
                size = end - offset;
            }
            if (size < headerSize || size > end - offset) {
                return;
            }

            if (insideUserData && "tags".equals(type)) {
                tagsBoxes.add(offset);
            } else if (isMp4ContainerBox(type)) {
                collectLegacyTagsBoxes(file, offset + headerSize, offset + size,
                        insideUserData || "udta".equals(type), depth + 1, tagsBoxes);
            }
            offset += size;
        }
    }

    private static long readUnsignedInt(RandomAccessFile file) throws IOException {
        return ((long) file.readUnsignedByte() << 24)
                | ((long) file.readUnsignedByte() << 16)
                | ((long) file.readUnsignedByte() << 8)
                | (long) file.readUnsignedByte();
    }

    private static String readBoxType(RandomAccessFile file) throws IOException {
        byte[] value = new byte[4];
        file.readFully(value);
        return new String(value, "ISO-8859-1");
    }

    private static boolean isMp4ContainerBox(String type) {
        return "moov".equals(type) || "trak".equals(type) || "mdia".equals(type)
                || "minf".equals(type) || "stbl".equals(type) || "edts".equals(type)
                || "dinf".equals(type) || "udta".equals(type);
    }

    /**
     * 外部 ffmpeg 快路径：把输入（ADTS 或 ISO-BMFF）直接转成 16 位 PCM WAV。
     *
     * <p>{@code -vn -map a:0?} 只要音轨、没有音轨时不报错；{@code -f wav} 显式指定容器
     * （输出名是 {@code .part}，ffmpeg 推不出格式）。输出先落 {@code .part}，成功且头有效
     * 才由调用方改名——半成品绝不冒充成品。</p>
     *
     * <p>刻意<b>不</b>提前 {@code process.destroy()}：{@code waitFor} 之后再 destroy 是常规姿势，
     * 但超时那一支必须 destroy，否则子进程会一直挂着，这就是这里分两支的原因。</p>
     *
     * @return true 表示 {@code output} 已经是一份可用的 WAV；false 表示这条快路径没走通
     *         （没装 ffmpeg、超时、非零退出、输出不合格），调用方应退回 JAAD
     */
    private static boolean decodeWithFfmpeg(File input, File output, ProgressListener progressListener) {
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
            // 单线程：这是一次短转码，不该在游戏运行时把核心占满。原生解码本身已经比
            // JAAD 快得多，多线程收益有限而抢占明显。
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
            // stderr 必须异步排空：管道写满时子进程会卡死（项目里踩过这个坑）。
            drainQuietly(process.getErrorStream());
            reportProgress(progressListener, 0.5);   // 进度语义只有「在跑」：不动 UI 比乱跳好

            if (!process.waitFor(FFMPEG_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy();
                // SIGTERM 对卡死的 ffmpeg 不保证退出：延迟强杀，避免残留僵尸子进程占着文件句柄。
                try {
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                System.err.println("[Music] ffmpeg transcode timed out, falling back to JAAD: "
                        + input.getName());
                return false;
            }
            if (process.exitValue() != 0) {
                return false;   // 载荷异常/缺音轨：交给 JAAD 给出正式结论
            }
            // 「能播」的判据与 JAAD 那条路一致：写够头 + 有实质数据 + 头是真 WAV。
            if (!output.isFile() || output.length() <= WAV_HEADER_SIZE) {
                return false;
            }
            if (!"wav".equals(AudioContainerSupport.detectContainer(output))) {
                return false;
            }
            return true;
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

    /** 把子进程的 stderr 排空到丢弃，避免管满卡死。每次转码一条——频度低（一首 AAC 一次），不值得养常驻池。 */
    private static void drainQuietly(InputStream stream) {
        if (stream == null) return;
        Thread drain = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try (InputStream input = stream) {
                while (input.read(buffer) >= 0) {
                    // 丢弃：ffmpeg 的 -v error 输出只在排障时需要，失败已由退出码表达。
                }
            } catch (Throwable ignored) {
            }
        }, "muonium-ffmpeg-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private static void reportProgress(ProgressListener progressListener, double progress) {
        if (progressListener != null) {
            progressListener.onProgress(Math.max(0.0, Math.min(1.0, progress)));
        }
    }

    private static final class ProgressInputStream extends FilterInputStream {
        private final long totalBytes;
        private final ProgressListener listener;
        private long readBytes;
        private long lastReportedBytes = -1L;

        private ProgressInputStream(FileInputStream input, long totalBytes, ProgressListener listener) {
            super(input);
            this.totalBytes = totalBytes;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                readBytes++;
                reportIfNeeded();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                readBytes += count;
                reportIfNeeded();
            }
            return count;
        }

        private void reportIfNeeded() {
            if (listener == null || totalBytes <= 0L) return;
            if (readBytes == totalBytes || readBytes - lastReportedBytes >= Math.max(4096L, totalBytes / 100L)) {
                lastReportedBytes = readBytes;
                reportProgress(listener, (double) readBytes / (double) totalBytes);
            }
        }

        private double getProgress() {
            return totalBytes <= 0L ? 0.0 : (double) readBytes / (double) totalBytes;
        }
    }
    private static final class PcmWaveWriter implements AutoCloseable {
        private final RandomAccessFile output;
        private int sampleRate = -1;
        private int channels = -1;
        private int bitsPerSample = -1;
        private long dataBytes;
        private boolean finished;

        private PcmWaveWriter(File outputFile) throws IOException {
            output = new RandomAccessFile(outputFile, "rw");
            output.setLength(0L);
            output.write(new byte[WAV_HEADER_SIZE]);
        }

        private void write(SampleBuffer buffer) throws IOException {
            if (buffer == null || buffer.getData() == null || buffer.getData().length == 0) {
                return;
            }
            int frameSampleRate = buffer.getSampleRate();
            int frameChannels = buffer.getChannels();
            int frameBits = buffer.getBitsPerSample();
            if (frameSampleRate <= 0 || frameChannels <= 0 || frameChannels > 2 || frameBits != 16) {
                throw new IOException("Unsupported decoded AAC PCM format: " + frameSampleRate + "Hz, "
                        + frameChannels + " channels, " + frameBits + " bits");
            }
            if (sampleRate < 0) {
                sampleRate = frameSampleRate;
                channels = frameChannels;
                bitsPerSample = frameBits;
            } else if (sampleRate != frameSampleRate || channels != frameChannels || bitsPerSample != frameBits) {
                throw new IOException("AAC stream changed PCM format while decoding");
            }

            byte[] data = buffer.getData();
            if (data.length % 2 != 0) {
                throw new IOException("Decoded AAC frame has an invalid PCM byte count");
            }
            if (buffer.isBigEndian()) {
                for (int index = 0; index < data.length; index += 2) {
                    byte first = data[index];
                    data[index] = data[index + 1];
                    data[index + 1] = first;
                }
            }
            if (dataBytes + data.length > 0xFFFFFFFFL) {
                throw new IOException("Decoded AAC WAV exceeds the 4 GiB RIFF limit");
            }
            // 第三方链接可能指向长达数小时的上传。应在解码阶段中止，而不是
            // 填满缓存后再让播放器那次巨大的堆分配失败。
            long pcmLimit = PlaybackMemoryLimits.maxDecodedPcmBytes();
            if (dataBytes + data.length > pcmLimit) {
                throw new IOException("解码中止：" + PlaybackMemoryLimits.describeOverLimit(dataBytes)
                        + "（避免游戏内存溢出）");
            }
            output.write(data);
            dataBytes += data.length;
        }

        private void finish() throws IOException {
            if (finished) {
                return;
            }
            if (sampleRate <= 0 || channels <= 0 || dataBytes <= 0) {
                throw new IOException("AAC stream did not decode to PCM audio");
            }

            int blockAlign = channels * (bitsPerSample / 8);
            long byteRate = (long) sampleRate * blockAlign;
            if (byteRate > Integer.MAX_VALUE) {
                throw new IOException("Decoded AAC byte rate is out of WAV range");
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
            writeLittleEndianShort(bitsPerSample);
            writeAscii("data");
            writeLittleEndianInt(dataBytes);
            finished = true;
        }

        private void writeAscii(String value) throws IOException {
            output.write(value.getBytes("US-ASCII"));
        }

        private void writeLittleEndianShort(int value) throws IOException {
            output.write(value & 0xFF);
            output.write((value >>> 8) & 0xFF);
        }

        private void writeLittleEndianInt(long value) throws IOException {
            output.write((int) (value & 0xFF));
            output.write((int) ((value >>> 8) & 0xFF));
            output.write((int) ((value >>> 16) & 0xFF));
            output.write((int) ((value >>> 24) & 0xFF));
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }
}
