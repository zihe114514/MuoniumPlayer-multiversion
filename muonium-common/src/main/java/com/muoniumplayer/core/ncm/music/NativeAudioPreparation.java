package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.AudioInfo;
import com.muoniumplayer.core.audio.ffmpeg.AudioInput;
import com.muoniumplayer.core.audio.ffmpeg.CancellationToken;
import com.muoniumplayer.core.audio.ffmpeg.FfmpegAudioDecoder;
import com.muoniumplayer.core.audio.ffmpeg.OutputSpec;
import com.muoniumplayer.core.audio.ffmpeg.PcmBlock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.DoubleConsumer;

/**
 * File adapter for the shared JavaCPP FFmpeg decoder. All work, including cache validation,
 * belongs on preparation/analysis workers, never the client, render or audio callback thread.
 * Decode preserves source rate, channels and float32 headroom. JSyn temporarily consumes the
 * disk WAV; its existing downstream channel policy is outside this adapter.
 *
 * A cache is a single RIFF file: float32 PCM plus a bounded mnat metadata chunk containing
 * version, source path/size/mtime/SHA-256, selected track/layout and PCM SHA-256. A unique .part
 * is fully written, closed and verified before atomic replacement. No separate manifest can
 * commit halfway. Failure/cancellation only removes this attempt's temporary file, never source
 * or an existing destination. Source content is checked again before publishing.
 */
final class NativeAudioPreparation {
    static final String CACHE_VERSION = "javacpp-8.1.2-1.5.14-f32-v1";
    static final long MAX_PCM_BYTES = Integer.MAX_VALUE - 65_536L; // legacy JSyn signed chunk size
    private static final int HEADER_BYTES = 56; // RIFF, fmt(16), fact(4), data
    private static final byte[] FLOAT_SUBTYPE = {3, 0, 0, 0, 0, 0, 16, 0,
            (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113};
    private static final int MAX_MANIFEST_BYTES = 65_536;

    private NativeAudioPreparation() {}

    static AudioInfo probe(File source) throws IOException {
        requireWorker();
        requireSource(source);
        CancellationToken token = new CancellationToken();
        try (FfmpegAudioDecoder decoder = FfmpegAudioDecoder.open(AudioInput.file(source.toPath()),
                OutputSpec.preserveSource(), token)) {
            return decoder.info();
        }
    }

    static File decodeToWav(File source, File destination, DoubleConsumer progress) throws IOException {
        return decodeToWav(source, destination, progress, new CancellationToken());
    }

    static File decodeToWav(File source, File destination, DoubleConsumer progress,
                            CancellationToken token) throws IOException {
        requireWorker();
        requireSource(source);
        if (destination == null) throw new IOException("Missing decoded audio destination");
        Path output = destination.toPath().toAbsolutePath().normalize();
        if (source.getCanonicalFile().toPath().equals(destination.getCanonicalFile().toPath())
                || (Files.exists(output) && Files.isSameFile(source.toPath(), output))) {
            throw new IOException("Decoded output must not overwrite its source");
        }
        token.throwIfCancelled();
        SourceIdentity identity = SourceIdentity.read(source, token);
        Files.createDirectories(output.getParent());
        Path candidate = Files.createTempFile(output.getParent(), output.getFileName() + ".", ".part");
        try {
            report(progress, 0);
            Properties manifest = identity.properties();
            try (FfmpegAudioDecoder decoder = FfmpegAudioDecoder.open(AudioInput.file(source.toPath()),
                    OutputSpec.preserveSource(), token);
                 FloatWaveWriter writer = new FloatWaveWriter(candidate, decoder.info(), decoder.outputChannelMask())) {
                AudioInfo info = decoder.info();
                PcmBlock block = new PcmBlock(4096, info.outputChannels());
                int frames;
                while ((frames = decoder.read(block)) >= 0) {
                    token.throwIfCancelled();
                    if (frames == 0) throw new IOException("Native decoder made no progress");
                    writer.write(block.samples(), frames);
                    if (info.durationMicros() > 0) {
                        report(progress, Math.min(0.99,
                                writer.frames * 1_000_000.0 / info.outputSampleRate() / info.durationMicros()));
                    }
                }
                token.throwIfCancelled();
                writer.finish(manifest);
            }
            if (!identity.equals(SourceIdentity.read(source, token))) {
                throw new IOException("Audio source changed during decode");
            }
            if (!validate(candidate.toFile(), identity, token)) {
                throw new IOException("Native decoded audio failed complete artifact validation");
            }
            token.throwIfCancelled();
            // Refuse a non-atomic replacement rather than risk destroying the previous product.
            try {
                Files.move(candidate, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Decoded cache filesystem does not support atomic publication", failure);
            }
            report(progress, 1);
            return destination;
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    static boolean isReusable(File source, File decoded) {
        if (source == null || decoded == null || !source.isFile() || !decoded.isFile()) return false;
        try {
            requireWorker();
            CancellationToken token = new CancellationToken();
            return validate(decoded, SourceIdentity.read(source, token), token);
        } catch (IOException | IllegalArgumentException failure) {
            return false;
        }
    }

    /** Read origin identity without trusting a filename; used only after managed-directory checks. */
    static File originalSource(File decoded) {
        try {
            String path = inspect(decoded, new CancellationToken(), false).getProperty("source.path");
            return path == null ? null : new File(path);
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
    }

    private static boolean validate(File decoded, SourceIdentity source, CancellationToken token)
            throws IOException {
        Properties metadata = inspect(decoded, token, true);
        Properties expected = source.properties();
        for (String key : expected.stringPropertyNames()) {
            if (!expected.getProperty(key).equals(metadata.getProperty(key))) return false;
        }
        return true;
    }

    /** Accept only complete products of this adapter, not arbitrary WAV-looking headers. */
    private static Properties inspect(File decoded, CancellationToken token, boolean checkPcm)
            throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(decoded, "r")) {
            if (file.length() < HEADER_BYTES + 8 || file.readInt() != 0x52494646) throw badWave();
            if (uint(file) + 8 != file.length() || file.readInt() != 0x57415645
                    || file.readInt() != 0x666d7420) throw badWave();
            long fmtSize = uint(file);
            int format = ushort(file);
            if ((fmtSize != 16 || format != 3) && (fmtSize != 40 || format != 0xfffe)) throw badWave();
            int channels = ushort(file);
            long rate = uint(file);
            long byteRate = uint(file);
            int alignment = ushort(file);
            int bits = ushort(file);
            if (channels < 1 || channels > 8 || rate < 1 || rate > 768000 || bits != 32
                    || alignment != channels * 4 || byteRate != rate * alignment) throw badWave();
            long channelMask = 0;
            if (format == 0xfffe) {
                if (ushort(file) != 22 || ushort(file) != 32) throw badWave();
                channelMask = uint(file);
                if (Long.bitCount(channelMask) != channels) throw badWave();
                byte[] subtype = new byte[16];
                file.readFully(subtype);
                if (!java.util.Arrays.equals(subtype, FLOAT_SUBTYPE)) throw badWave();
            } else if (channels > 2) {
                throw badWave(); // surround products must retain their real speaker layout
            }
            if (file.readInt() != 0x66616374 || uint(file) != 4) throw badWave();
            long frames = uint(file);
            if (file.readInt() != 0x64617461) throw badWave();
            long bytes = uint(file);
            long dataOffset = file.getFilePointer();
            if (frames < 1 || bytes != frames * alignment || bytes > MAX_PCM_BYTES
                    || dataOffset + bytes + 8 > file.length()) throw badWave();
            MessageDigest digest = sha256();
            if (checkPcm) {
                byte[] buffer = new byte[65536];
                long remaining = bytes;
                while (remaining > 0) {
                    token.throwIfCancelled();
                    int count = (int) Math.min(remaining, buffer.length);
                    file.readFully(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
            } else {
                file.seek(dataOffset + bytes);
            }
            if (file.readInt() != 0x6d6e6174) throw badWave(); // mnat
            long metaSize = uint(file);
            if (metaSize < 1 || metaSize > MAX_MANIFEST_BYTES
                    || file.getFilePointer() + metaSize + (metaSize & 1) != file.length()) throw badWave();
            byte[] metadata = new byte[(int) metaSize];
            file.readFully(metadata);
            if ((metaSize & 1) != 0 && file.read() != 0) throw badWave();
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(metadata));
            if (!CACHE_VERSION.equals(properties.getProperty("version"))
                    || !Long.toString(frames).equals(properties.getProperty("frames"))
                    || !Long.toString(rate).equals(properties.getProperty("rate"))
                    || !Integer.toString(channels).equals(properties.getProperty("channels"))
                    || !Long.toString(dataOffset).equals(properties.getProperty("data.offset"))
                    || (format == 0xfffe && !Long.toString(channelMask).equals(properties.getProperty("channel.mask")))
                    || (checkPcm && !HexFormat.of().formatHex(digest.digest())
                            .equals(properties.getProperty("pcm.sha256")))) throw badWave();
            return properties;
        }
    }

    private static IOException badWave() { return new IOException("Incomplete or invalid native PCM cache"); }
    private static long uint(RandomAccessFile f) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()));
    }
    private static int ushort(RandomAccessFile f) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(f.readShort()));
    }
    private static void requireWorker() throws IOException {
        String name = Thread.currentThread().getName();
        if (name.equals("Render thread") || name.equals("Client thread") || name.startsWith("JSyn")) {
            throw new IOException("File audio preparation and integrity checks require a background worker");
        }
    }
    private static void requireSource(File source) throws IOException {
        if (source == null || !source.isFile() || source.length() == 0) {
            throw new IOException("Missing or empty file audio input");
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void report(DoubleConsumer progress, double value) {
        if (progress == null) return;
        try { progress.accept(value); }
        catch (RuntimeException failure) {
            System.err.println("[Audio/Native] Progress listener failed: " + failure);
        }
    }

    private record SourceIdentity(String path, long size, long modified, String sha256) {
        static SourceIdentity read(File source, CancellationToken token) throws IOException {
            long size = source.length();
            long modified = source.lastModified();
            MessageDigest hash = NativeAudioPreparation.sha256();
            try (InputStream input = Files.newInputStream(source.toPath())) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    token.throwIfCancelled();
                    if (count > 0) hash.update(buffer, 0, count);
                }
            }
            if (source.length() != size || source.lastModified() != modified) {
                throw new IOException("Audio source changed while fingerprinting");
            }
            return new SourceIdentity(source.getCanonicalPath(), size, modified,
                    HexFormat.of().formatHex(hash.digest()));
        }
        Properties properties() {
            Properties p = new Properties();
            p.setProperty("version", CACHE_VERSION);
            p.setProperty("source.path", path);
            p.setProperty("source.size", Long.toString(size));
            p.setProperty("source.modified", Long.toString(modified));
            p.setProperty("source.sha256", sha256);
            return p;
        }
    }

    /** The only WAV writer used by all legacy codec facades; buffer size never scales with track length. */
    private static final class FloatWaveWriter implements AutoCloseable {
        private final RandomAccessFile file;
        private final AudioInfo info;
        private final long channelMask;
        private final boolean extensible;
        private final int headerBytes;
        private final MessageDigest digest = sha256();
        private final ByteBuffer buffer;
        private long frames;

        FloatWaveWriter(Path destination, AudioInfo info, long channelMask) throws IOException {
            if (info.outputChannels() < 1 || info.outputChannels() > 8
                    || info.outputSampleRate() < 1 || info.outputSampleRate() > 768000) {
                throw new IOException("Unsupported native PCM dimensions");
            }
            this.info = info;
            this.channelMask = channelMask;
            extensible = info.outputChannels() > 2 || (channelMask != 0
                    && channelMask != (info.outputChannels() == 1 ? 4 : 3));
            if (extensible && (channelMask == 0 || (channelMask >>> 32) != 0
                    || Long.bitCount(channelMask) != info.outputChannels())) {
                throw new IOException("Legacy disk PCM adapter requires an explicit WAVE speaker mask");
            }
            headerBytes = extensible ? 80 : HEADER_BYTES;
            buffer = ByteBuffer.allocate(4096 * info.outputChannels() * 4).order(ByteOrder.LITTLE_ENDIAN);
            file = new RandomAccessFile(destination.toFile(), "rw");
            try {
                file.setLength(headerBytes);
                file.seek(headerBytes);
            } catch (IOException failure) {
                file.close();
                throw failure;
            }
        }
        void write(float[] samples, int count) throws IOException {
            int channels = info.outputChannels();
            long bytes = (long) count * channels * 4;
            if (count <= 0 || count > 4096 || count * channels > samples.length
                    || (frames + count) * channels * 4 > MAX_PCM_BYTES) {
                throw new IOException("Native PCM exceeds the legacy disk adapter limit");
            }
            buffer.clear();
            for (int index = 0; index < count * channels; index++) {
                if (!Float.isFinite(samples[index])) throw new IOException("Non-finite decoded PCM");
                buffer.putFloat(samples[index]);
            }
            file.write(buffer.array(), 0, (int) bytes);
            digest.update(buffer.array(), 0, (int) bytes);
            frames += count;
        }
        void finish(Properties manifest) throws IOException {
            if (frames == 0) throw new IOException("No audio samples decoded");
            manifest.setProperty("data.offset", Integer.toString(headerBytes));
            manifest.setProperty("channel.mask", Long.toString(channelMask));
            manifest.setProperty("frames", Long.toString(frames));
            manifest.setProperty("rate", Integer.toString(info.outputSampleRate()));
            manifest.setProperty("channels", Integer.toString(info.outputChannels()));
            manifest.setProperty("layout", info.outputChannelLayout());
            manifest.setProperty("codec", info.codec());
            manifest.setProperty("stream", Integer.toString(info.streamIndex()));
            manifest.setProperty("pcm.sha256", HexFormat.of().formatHex(digest.digest()));
            ByteArrayOutputStream metadata = new ByteArrayOutputStream();
            manifest.store(metadata, "Muonium native audio cache");
            byte[] payload = metadata.toByteArray();
            if (payload.length > MAX_MANIFEST_BYTES) throw new IOException("Audio manifest too large");
            file.writeBytes("mnat");
            file.writeInt(Integer.reverseBytes(payload.length));
            file.write(payload);
            if ((payload.length & 1) != 0) file.write(0);
            long length = file.length();
            long dataBytes = frames * info.outputChannels() * 4;
            file.seek(0);
            ByteBuffer header = ByteBuffer.allocate(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R','I','F','F'}).putInt((int) (length - 8));
            header.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(extensible ? 40 : 16);
            header.putShort((short) (extensible ? 0xfffe : 3)).putShort((short) info.outputChannels());
            header.putInt(info.outputSampleRate()).putInt(info.outputSampleRate() * info.outputChannels() * 4);
            header.putShort((short) (info.outputChannels() * 4)).putShort((short) 32);
            if (extensible) {
                header.putShort((short) 22).putShort((short) 32).putInt((int) channelMask).put(FLOAT_SUBTYPE);
            }
            header.put(new byte[]{'f','a','c','t'}).putInt(4).putInt((int) frames);
            header.put(new byte[]{'d','a','t','a'}).putInt((int) dataBytes);
            file.write(header.array());
            file.getFD().sync();
        }
        @Override public void close() throws IOException { file.close(); }
    }
}
