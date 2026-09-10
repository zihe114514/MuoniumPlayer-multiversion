package com.muoniumplayer.core.ncm.music;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 汽水加密 MP4 的本机 AES-CTR 解密，输入只来自当前账号获准播放的曲目。 */
final class QishuiAudioDecryptor {

    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_SAMPLE_COUNT = 200_000;
    private static final int MAX_CHUNK_COUNT = 200_000;
    private static final int MAX_STSC_ENTRIES = 20_000;
    private static final Set<String> ENCRYPTED_BOXES = Set.of(
            "senc", "saio", "saiz", "sinf", "schi", "tenc", "schm", "frma");
    private static final Set<String> CONTAINER_BOXES = Set.of(
            "moov", "trak", "mdia", "minf", "stbl", "stsd");

    private QishuiAudioDecryptor() {
    }

    static void decryptInPlace(Path source, String playAuth) throws IOException {
        if (source == null || playAuth == null || playAuth.trim().isEmpty()) return;
        long size = Files.size(source);
        if (size <= 0L || size > MAX_FILE_BYTES) {
            throw new IOException("汽水加密音频大小异常: " + size);
        }
        byte[] encrypted = Files.readAllBytes(source);
        byte[] clear = decryptMp4(encrypted, resolveKey(playAuth));
        Path temporary = source.resolveSibling(source.getFileName() + ".decrypted");
        Files.write(temporary, clear);
        try {
            Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String resolveKey(String playAuth) throws IOException {
        String raw = playAuth == null ? "" : playAuth.trim();
        if (isHexKey(raw)) return raw.toLowerCase(Locale.ROOT);
        try {
            byte[] encoded = Base64.getDecoder().decode(raw);
            String decoded = decryptSpade(encoded).trim();
            if (isHexKey(decoded)) return decoded.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
        }
        throw new IOException("汽水 play_auth 无法解析为 AES-128 密钥");
    }

    private static boolean isHexKey(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{32}");
    }

    private static String decryptSpade(byte[] encoded) {
        if (encoded == null || encoded.length < 3) return "";
        int padding = (unsigned(encoded[0]) ^ unsigned(encoded[1]) ^ unsigned(encoded[2])) - 48;
        if (padding < 0 || encoded.length < padding + 2) return "";
        byte[] innerInput = Arrays.copyOfRange(encoded, 1, encoded.length - padding);
        byte[] decoded = decryptSpadeInner(innerInput);
        if (decoded.length == 0) return "";
        int skip = decodeBase36(unsigned(decoded[0]));
        int messageLength = encoded.length - padding - 2;
        int end = 1 + messageLength - skip;
        if (end < 1 || end > decoded.length) return "";
        return new String(decoded, 1, end - 1, StandardCharsets.UTF_8);
    }

    private static byte[] decryptSpadeInner(byte[] input) {
        byte[] result = new byte[input.length];
        byte[] prefix = new byte[input.length + 2];
        prefix[0] = (byte) 0xFA;
        prefix[1] = 0x55;
        System.arraycopy(input, 0, prefix, 2, input.length);
        for (int index = 0; index < result.length; index++) {
            int value = (unsigned(input[index]) ^ unsigned(prefix[index]))
                    - Integer.bitCount(index) - 21;
            while (value < 0) value += 0xFF;
            result[index] = (byte) value;
        }
        return result;
    }

    private static int decodeBase36(int value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'z') return value - 'a' + 10;
        return 0xFF;
    }

    private static byte[] decryptMp4(byte[] data, String hexKey) throws IOException {
        Box ftyp = findBox(data, "ftyp", 0, data.length);
        Box moov = requireBox(findBox(data, "moov", 0, data.length), "moov");
        Box trak = requireBox(findBox(data, "trak", moov.dataStart, moov.end), "trak");
        Box mdia = requireBox(findBox(data, "mdia", trak.dataStart, trak.end), "mdia");
        Box minf = requireBox(findBox(data, "minf", mdia.dataStart, mdia.end), "minf");
        Box stbl = requireBox(findBox(data, "stbl", minf.dataStart, minf.end), "stbl");
        Box stsd = requireBox(findBox(data, "stsd", stbl.dataStart, stbl.end), "stsd");
        Box stsz = requireBox(findBox(data, "stsz", stbl.dataStart, stbl.end), "stsz");
        Box stsc = requireBox(findBox(data, "stsc", stbl.dataStart, stbl.end), "stsc");
        Box stco = requireBox(findBox(data, "stco", stbl.dataStart, stbl.end), "stco");
        Box mdat = requireBox(findBox(data, "mdat", 0, data.length), "mdat");
        Box senc = findBox(data, "senc", stbl.dataStart, stbl.end);
        if (senc == null) senc = findBox(data, "senc", moov.dataStart, moov.end);
        requireBox(senc, "senc");

        int[] sampleSizes = parseSampleSizes(data, stsz);
        List<StscEntry> stscEntries = parseSampleToChunk(data, stsc);
        int chunkCount = checkedCount(readU32(data, stco.dataStart + 4), MAX_CHUNK_COUNT,
                "stco chunk");
        if (stco.dataStart + 8L + chunkCount * 4L > stco.end) {
            throw new IOException("stco 数据不完整");
        }
        byte[][] ivs = parseSenc(data, senc, sampleSizes.length);
        long payloadBytes = 0L;
        for (int sampleSize : sampleSizes) payloadBytes += sampleSize;
        if (payloadBytes > mdat.size - 8L) throw new IOException("汽水音频样本超出 mdat 范围");

        byte[] key = hexToBytes(hexKey);
        List<byte[]> samples = new ArrayList<>(sampleSizes.length);
        int sampleOffset = toInt(mdat.dataStart, "mdat offset");
        for (int index = 0; index < sampleSizes.length; index++) {
            int sampleSize = sampleSizes[index];
            if (sampleOffset + (long) sampleSize > data.length) {
                throw new IOException("汽水音频样本数据不完整");
            }
            samples.add(decryptCtr(data, sampleOffset, sampleSize, key, ivs[index]));
            sampleOffset += sampleSize;
        }
        rejectUnsupportedAudioCodec(data, stsd);

        List<byte[]> decryptedSamples = new ArrayList<>(samples);
        int ftypSize = ftyp == null ? 0 : ftyp.size;
        byte[] dummyMoov = processBoxTree(data, moov, 0L,
                new Context(sampleSizes, stscEntries, chunkCount));
        long newMdatOffset = ftypSize + dummyMoov.length + 16L;
        if (newMdatOffset > Integer.MAX_VALUE) throw new IOException("汽水音频偏移过大");
        byte[] cleanMoovData = processBoxTree(data, moov, newMdatOffset,
                new Context(sampleSizes, stscEntries, chunkCount));
        byte[] cleanMoov = box("moov", cleanMoovData);
        byte[] mdatData = join(decryptedSamples);
        byte[] cleanMdat = box("mdat", mdatData);

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                ftypSize + cleanMoov.length + cleanMdat.length);
        if (ftyp != null) output.write(data, toInt(ftyp.offset, "ftyp offset"), ftyp.size);
        output.write(cleanMoov);
        output.write(cleanMdat);
        return output.toByteArray();
    }

    private static void rejectUnsupportedAudioCodec(byte[] data, Box stsd) throws IOException {
        byte[] bytes = Arrays.copyOfRange(data, toInt(stsd.dataStart, "stsd offset"), toInt(stsd.end, "stsd end"));
        String ascii = new String(bytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if (!ascii.contains("mp4a") && !ascii.contains("enca")) {
            throw new IOException("汽水加密容器不是受支持的 AAC/M4A");
        }
    }

    private static int[] parseSampleSizes(byte[] data, Box stsz) throws IOException {
        int constantSize = (int) readU32(data, stsz.dataStart + 4);
        int sampleCount = checkedCount(readU32(data, stsz.dataStart + 8), MAX_SAMPLE_COUNT,
                "stsz sample");
        int[] sizes = new int[sampleCount];
        if (constantSize != 0) {
            Arrays.fill(sizes, constantSize);
            return sizes;
        }
        long required = stsz.dataStart + 12L + sampleCount * 4L;
        if (required > stsz.end) throw new IOException("stsz 样本表不完整");
        for (int index = 0; index < sampleCount; index++) {
            sizes[index] = checkedInt(readU32(data, stsz.dataStart + 12L + index * 4L), "sample size");
        }
        return sizes;
    }

    private static List<StscEntry> parseSampleToChunk(byte[] data, Box stsc) throws IOException {
        int count = checkedCount(readU32(data, stsc.dataStart + 4), MAX_STSC_ENTRIES, "stsc entry");
        if (stsc.dataStart + 8L + count * 12L > stsc.end) throw new IOException("stsc 表不完整");
        List<StscEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long offset = stsc.dataStart + 8L + index * 12L;
            entries.add(new StscEntry((int) readU32(data, offset),
                    (int) readU32(data, offset + 4), (int) readU32(data, offset + 8)));
        }
        return entries;
    }

    private static byte[][] parseSenc(byte[] data, Box senc, int sampleCount) throws IOException {
        int count = checkedCount(readU32(data, senc.dataStart + 4), MAX_SAMPLE_COUNT, "senc sample");
        if (senc.dataStart + 8L + count * 8L > senc.end) throw new IOException("senc IV 表不完整");
        if (count < sampleCount) throw new IOException("senc IV 数量少于音频样本数量");
        byte[][] ivs = new byte[count][16];
        for (int index = 0; index < count; index++) {
            System.arraycopy(data, (int) (senc.dataStart + 8L + index * 8L), ivs[index], 0, 8);
        }
        return ivs;
    }

    private static byte[] decryptCtr(byte[] data, int offset, int length, byte[] key, byte[] iv)
            throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data, offset, length);
        } catch (Exception error) {
            throw new IOException("汽水音频 AES-CTR 解密失败", error);
        }
    }

    private static byte[] processBoxTree(byte[] data, Box parent, long newMdatOffset, Context context)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long position = parent.dataStart;
        if ("stsd".equals(parent.type)) {
            if (parent.dataStart + 8L > parent.end) throw new IOException("stsd FullBox 头部不完整");
            output.write(data, toInt(parent.dataStart, "stsd offset"), 8);
            position += 8L;
        }
        while (position < parent.end) {
            if (position + 8L > parent.end) {
                output.write(data, (int) position, (int) (parent.end - position));
                break;
            }
            long rawSize = readU32(data, position);
            long typeOffset = position + 4L;
            String type = ascii(data, typeOffset, typeOffset + 4L);
            if (rawSize < 8L || rawSize > parent.end - position || rawSize > Integer.MAX_VALUE) {
                output.write(data, (int) position, (int) (parent.end - position));
                break;
            }
            int size = (int) rawSize;
            Box child = new Box(position, size, type, position + 8L, position + size);
            if (ENCRYPTED_BOXES.contains(type)) {
                position += size;
                continue;
            }
            if ("enca".equals(type)) {
                byte[] inner = processBoxTree(data, child, newMdatOffset, context);
                output.write(box("mp4a", inner));
                position += size;
                continue;
            }
            if ("stco".equals(type)) {
                int[] offsets = calculateChunkOffsets(context.sampleSizes, context.stscEntries,
                        context.chunkCount, newMdatOffset);
                output.write(box("stco", updateStco(data, child, offsets)));
                position += size;
                continue;
            }
            if (CONTAINER_BOXES.contains(type)) {
                byte[] inner = processBoxTree(data, child, newMdatOffset, context);
                output.write(box(type, inner));
                position += size;
                continue;
            }
            output.write(data, (int) position, size);
            position += size;
        }
        return output.toByteArray();
    }

    private static int[] calculateChunkOffsets(int[] sampleSizes, List<StscEntry> entries,
                                                int chunkCount, long baseOffset) throws IOException {
        int[] offsets = new int[chunkCount];
        long current = baseOffset;
        int sampleIndex = 0;
        for (int chunk = 1; chunk <= chunkCount; chunk++) {
            if (current > Integer.MAX_VALUE) throw new IOException("汽水音频 chunk 偏移过大");
            offsets[chunk - 1] = (int) current;
            int samplesPerChunk = 0;
            for (int index = 0; index < entries.size(); index++) {
                StscEntry currentEntry = entries.get(index);
                StscEntry nextEntry = index + 1 < entries.size() ? entries.get(index + 1) : null;
                if (chunk >= currentEntry.firstChunk
                        && (nextEntry == null || chunk < nextEntry.firstChunk)) {
                    samplesPerChunk = currentEntry.samplesPerChunk;
                    break;
                }
            }
            for (int sample = 0; sample < samplesPerChunk; sample++) {
                if (sampleIndex < sampleSizes.length) {
                    current += sampleSizes[sampleIndex];
                    if (current > Integer.MAX_VALUE) throw new IOException("汽水音频 chunk 偏移过大");
                }
                sampleIndex++;
            }
        }
        return offsets;
    }

    private static byte[] updateStco(byte[] data, Box stco, int[] offsets) throws IOException {
        int count = checkedCount(readU32(data, stco.dataStart + 4), MAX_CHUNK_COUNT, "stco chunk");
        if (offsets.length < count) throw new IOException("stco 偏移数量不足");
        ByteArrayOutputStream output = new ByteArrayOutputStream(8 + count * 4);
        output.write(data, toInt(stco.dataStart, "stco offset"), 8);
        for (int index = 0; index < count; index++) writeU32(output, offsets[index]);
        return output.toByteArray();
    }

    private static Box findBox(byte[] data, String type, long start, long end) {
        long position = start;
        while (position + 8L <= end) {
            long size = readU32(data, position);
            if (size == 0L || size < 8L || size > end - position || size > Integer.MAX_VALUE) {
                return null;
            }
            String currentType = ascii(data, position + 4L, position + 8L);
            if (type.equals(currentType)) {
                return new Box(position, (int) size, currentType, position + 8L, position + size);
            }
            position += size;
        }
        return null;
    }

    private static Box requireBox(Box box, String name) throws IOException {
        if (box == null) throw new IOException("汽水加密容器缺少 " + name + " box");
        return box;
    }

    private static long readU32(byte[] data, long offset) {
        int index = (int) offset;
        if (index < 0 || index + 4 > data.length) return 0L;
        return ((long) (data[index] & 0xFF) << 24)
                | ((long) (data[index + 1] & 0xFF) << 16)
                | ((long) (data[index + 2] & 0xFF) << 8)
                | (long) (data[index + 3] & 0xFF);
    }

    private static String ascii(byte[] data, long start, long end) {
        return new String(data, (int) start, (int) (end - start), StandardCharsets.US_ASCII);
    }

    private static int checkedCount(long value, int maximum, String name) throws IOException {
        if (value <= 0L || value > maximum) throw new IOException(name + " 数量异常: " + value);
        return (int) value;
    }

    private static int checkedInt(long value, String name) throws IOException {
        if (value < 0L || value > Integer.MAX_VALUE) throw new IOException(name + " 超出范围");
        return (int) value;
    }

    private static int toInt(long value, String name) throws IOException {
        return checkedInt(value, name);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] output = new byte[hex.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return output;
    }

    private static byte[] box(String type, byte[] payload) throws IOException {
        if (payload.length > Integer.MAX_VALUE - 8) throw new IOException("MP4 box 过大");
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 8);
        writeU32(output, payload.length + 8);
        output.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(payload);
        return output.toByteArray();
    }

    private static void writeU32(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static byte[] join(List<byte[]> parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] output = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, output, offset, part.length);
            offset += part.length;
        }
        return output;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static final class Box {
        final long offset;
        final int size;
        final String type;
        final long dataStart;
        final long end;

        Box(long offset, int size, String type, long dataStart, long end) {
            this.offset = offset;
            this.size = size;
            this.type = type;
            this.dataStart = dataStart;
            this.end = end;
        }
    }

    private static final class StscEntry {
        final int firstChunk;
        final int samplesPerChunk;
        final int sampleDescriptionId;

        StscEntry(int firstChunk, int samplesPerChunk, int sampleDescriptionId) {
            this.firstChunk = firstChunk;
            this.samplesPerChunk = samplesPerChunk;
            this.sampleDescriptionId = sampleDescriptionId;
        }
    }

    private static final class Context {
        final int[] sampleSizes;
        final List<StscEntry> stscEntries;
        final int chunkCount;

        Context(int[] sampleSizes, List<StscEntry> stscEntries, int chunkCount) {
            this.sampleSizes = sampleSizes;
            this.stscEntries = stscEntries;
            this.chunkCount = chunkCount;
        }
    }
}