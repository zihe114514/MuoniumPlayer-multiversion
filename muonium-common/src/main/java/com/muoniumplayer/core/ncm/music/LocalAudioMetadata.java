package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从本地音频文件的标签里读基础元数据：标题、歌手、专辑、时长、嵌入封面与嵌入歌词。
 *
 * <p>覆盖三种容器，与 {@link EmbeddedLyricsReader} 同一范围（ID3v2 / FLAC Vorbis Comment /
 * MP4 ilst），解析器结构也是同构扩展——那份文件只取歌词，这里把同一批标签里的
 * 身份字段和封面图一起拿走，一份文件只用打开一次。</p>
 *
 * <p><b>所有字段都是可选的</b>：标签残缺、编码陌生、图片截断都只影响「这一项没读到」，
 * 绝不让整次解析失败——扫描一个几百首的目录时，一首怪文件不能把整个导入卡死。
* 解析不出任何东西时调用方拿文件名当标题兜底（见 {@code LocalMusicLibrary}）。</p>
 *
 * <p>解析只读文件、绝不写：边车（AAC 解码的 WAV）由播放链路另管，且落点在模组缓存目录，
 * 用户的音乐目录一个字节都不会被动过。</p>
 */
final class LocalAudioMetadata {

    /** 一次解析的产物；任何一项都可能为空/为 0，调用方自行兜底。 */
    static final class Result {
        String title = "";
        String artist = "";
        String album = "";
        long durationMillis;
        byte[] coverImage;
        /** 嵌入歌词原文（LRC / YRC / 纯文本都可能），交 {@code LyricParser.parseLyricText} 嗅探。 */
        String lyrics = "";

        boolean hasAny() {
            return !title.isEmpty() || !artist.isEmpty() || !album.isEmpty()
                    || durationMillis > 0L || coverImage != null || !lyrics.isEmpty();
        }
    }
    private static final int MAX_TAG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_COVER_BYTES = 8 * 1024 * 1024;
    private static final String MP4_COVER_ATOM = "covr";
    private static final String MP4_LYRICS_ATOM = "\u00A9lyr";

    private LocalAudioMetadata() {
    }

    static Result read(File audioFile) {
        if (audioFile == null || !audioFile.isFile() || audioFile.length() < 12L) {
            return null;
        }
        try (RandomAccessFile input = new RandomAccessFile(audioFile, "r")) {
            byte[] header = new byte[12];
            input.readFully(header, 0, (int) Math.min(header.length, input.length()));
            if (matches(header, 0, "ID3")) {
                return readId3(input);
            }
            if (matches(header, 0, "fLaC")) {
                return readFlac(input);
            }
            if (matches(header, 4, "ftyp")) {
                return readM4a(input);
            }
        } catch (IOException | RuntimeException ignored) {
            // 元数据是可选的：坏标签只让这一首退化成「文件名当标题」，绝不让扫描失败。
        }
        return null;
    }

    // ════════════════════════════ ID3v2 ════════════════════════════

    private static Result readId3(RandomAccessFile input) throws IOException {
        input.seek(0L);
        byte[] header = new byte[10];
        input.readFully(header);
        int version = header[3] & 0xFF;
        if (version < 3 || version > 4) {
            return null;
        }
        int tagSize = synchsafeInt(header, 6);
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES || tagSize > input.length() - 10L) {
            return null;
        }
        byte[] tag = new byte[tagSize];
        input.readFully(tag);
        if ((header[5] & 0x80) != 0) {
            tag = removeUnsynchronisation(tag);
        }

        Result result = new Result();
        int offset = skipId3ExtendedHeader(tag, version, header[5] & 0xFF);
        while (offset + 10 <= tag.length) {
            String id = ascii(tag, offset, 4);
            if (id.trim().isEmpty()) {
                break;
            }
            int size = version == 4 ? synchsafeInt(tag, offset + 4) : bigEndianInt(tag, offset + 4);
            int frameStart = offset + 10;
            if (size <= 0 || frameStart + size > tag.length) {
                break;
            }
            int flags = (tag[offset + 9] & 0xFF);
            boolean compressedOrEncrypted = (flags & 0x0C) != 0;
            if (!compressedOrEncrypted) {
                applyId3Frame(result, id, tag, frameStart, size, version);
            }
            offset = frameStart + size;
        }
        return result;
    }

    private static void applyId3Frame(Result result, String id, byte[] tag,
                                      int frameStart, int size, int version) {
        switch (id) {
            case "TIT2":
                result.title = firstTextLine(decodeId3Text(tag, frameStart, size));
                break;
            case "TPE1":
                result.artist = firstTextLine(decodeId3Text(tag, frameStart, size));
                break;
            case "TALB":
                result.album = firstTextLine(decodeId3Text(tag, frameStart, size));
                break;
            case "TLEN": {
                long millis = parseLongSafe(firstTextLine(decodeId3Text(tag, frameStart, size)));
                if (millis > 0L) result.durationMillis = millis;
                break;
            }
            case "USLT":
                result.lyrics = decodeUslt(tag, frameStart, size);
                break;
            case "APIC":
                byte[] picture = decodeApic(tag, frameStart, size, version);
                if (picture != null && result.coverImage == null) {
                    result.coverImage = picture;
                }
                break;
            default:
                break;
        }
    }

    /** ID3 文本帧：首字节是编码，其余是文本；多值以 \0 分隔时只取第一个。 */
    private static String decodeId3Text(byte[] data, int offset, int length) {
        if (length < 1) return "";
        return decodeText(data, offset + 1, length - 1, data[offset] & 0xFF);
    }

    /** APIC（附图）：编码 + MIME(以 \0 结尾) + 图片类型(1B) + 描述(编码终止符) + 图像字节。 */
    private static byte[] decodeApic(byte[] data, int offset, int length, int version) {
        if (length < 4) return null;
        int encoding = data[offset] & 0xFF;
        int mimeEnd = indexOfZero(data, offset + 1, offset + length);
        if (mimeEnd < 0) return null;
        int mimeLength = mimeEnd - (offset + 1);
        String mime = ascii(data, offset + 1, Math.min(mimeLength, 24)).toLowerCase(Locale.ROOT);
        int pictureTypeAt = mimeEnd + 1;
        if (pictureTypeAt >= offset + length) return null;
        int descriptionStart = pictureTypeAt + 1;
        int textStart = encoding == 1 || encoding == 2
                ? skipDoubleZeroTerminator(data, descriptionStart, offset + length)
                : skipSingleZeroTerminator(data, descriptionStart, offset + length);
        if (textStart >= offset + length) return null;
        if (!mime.contains("png") && !mime.contains("jpeg") && !mime.contains("jpg")
                && !mime.contains("image")) {
            return null;
        }
        int imageLength = offset + length - textStart;
        if (imageLength < 64 || imageLength > MAX_COVER_BYTES) return null;
        byte[] image = new byte[imageLength];
        System.arraycopy(data, textStart, image, 0, imageLength);
        return image;
    }

    private static String decodeUslt(byte[] data, int offset, int length) {
        if (length < 5) return "";
        int encoding = data[offset] & 0xFF;
        int descriptionStart = offset + 4; // 编码 + ISO-639 语言代码
        int textStart = encoding == 1 || encoding == 2
                ? skipDoubleZeroTerminator(data, descriptionStart, offset + length)
                : skipSingleZeroTerminator(data, descriptionStart, offset + length);
        return textStart < offset + length
                ? decodeText(data, textStart, offset + length - textStart, encoding) : "";
    }

    private static int skipId3ExtendedHeader(byte[] tag, int version, int flags) {
        if ((flags & 0x40) == 0 || tag.length < 4) {
            return 0;
        }
        int size = version == 4 ? synchsafeInt(tag, 0) : bigEndianInt(tag, 0);
        if (size <= 0) {
            return 0;
        }
        int skipped = version == 3 ? size + 4 : size;
        return skipped > 0 && skipped < tag.length ? skipped : 0;
    }

    // ════════════════════════════ FLAC ════════════════════════════

    private static Result readFlac(RandomAccessFile input) throws IOException {
        Result result = new Result();
        input.seek(4L);
        boolean last = false;
        while (!last && input.getFilePointer() + 4L <= input.length()) {
            int blockHeader = input.readUnsignedByte();
            last = (blockHeader & 0x80) != 0;
            int type = blockHeader & 0x7F;
            int length = input.readUnsignedByte() << 16
                    | input.readUnsignedByte() << 8 | input.readUnsignedByte();
            if (length < 0 || input.getFilePointer() + length > input.length()) {
                break;
            }
            if (type == 0 && length >= 18) {
                // STREAMINFO：第 10 字节起是 33bit 总采样数（20 bit）+ 采样率（20 bit）。
                byte[] block = new byte[length];
                input.readFully(block);
                applyFlacStreamInfo(result, block);
            } else if (type == 4 && length <= MAX_TAG_BYTES) {
                byte[] block = new byte[length];
                input.readFully(block);
                applyVorbisComments(result, block);
            } else if (type == 6 && length <= MAX_COVER_BYTES) {
                byte[] block = new byte[length];
                input.readFully(block);
                byte[] picture = decodeFlacPicture(block);
                if (picture != null && result.coverImage == null) {
                    result.coverImage = picture;
                }
            } else {
                input.seek(input.getFilePointer() + length);
            }
        }
        return result.hasAny() ? result : null;
    }

    private static void applyFlacStreamInfo(Result result, byte[] block) {
        try {
            long sampleRate = ((block[10] & 0xFF) << 12) | ((block[11] & 0xFF) << 4)
                    | ((block[12] & 0xF0) >> 4);
            long totalSamples = ((block[13] & 0xFF) << 16)
                    | ((block[14] & 0xFF) << 8) | (block[15] & 0xFF);
            if (sampleRate > 0L && totalSamples > 0L) {
                result.durationMillis = totalSamples * 1000L / sampleRate;
            }
        } catch (ArrayIndexOutOfBoundsException ignored) {
            // STREAMINFO 比预期短：时长读不到就算了，标题歌手照常从 Vorbis 注释拿。
        }
    }

    private static void applyVorbisComments(Result result, byte[] block) {
        int offset = 0;
        if (block.length < 8) {
            return;
        }
        int vendorLength = littleEndianInt(block, offset);
        offset += 4;
        if (vendorLength < 0 || offset + vendorLength + 4 > block.length) {
            return;
        }
        offset += vendorLength;
        int count = littleEndianInt(block, offset);
        offset += 4;
        for (int index = 0; index < count && offset + 4 <= block.length; index++) {
            int length = littleEndianInt(block, offset);
            offset += 4;
            if (length < 0 || offset + length > block.length) {
                return;
            }
            String comment = new String(block, offset, length, StandardCharsets.UTF_8);
            offset += length;
            int separator = comment.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = comment.substring(0, separator).trim().toUpperCase(Locale.ROOT);
            String value = clean(comment.substring(separator + 1));
            if (value.isEmpty()) {
                continue;
            }
            switch (key) {
                case "TITLE":
                    if (result.title.isEmpty()) result.title = value;
                    break;
                case "ARTIST":
                case "ALBUMARTIST":
                    if (result.artist.isEmpty()) result.artist = value;
                    break;
                case "ALBUM":
                    if (result.album.isEmpty()) result.album = value;
                    break;
                case "LYRICS":
                case "UNSYNCEDLYRICS":
                case "SYNCEDLYRICS":
                case "LRC":
                case "LYRIC":
                    if (result.lyrics.isEmpty()) result.lyrics = value;
                    break;
                default:
                    break;
            }
        }
    }

    /** METADATA_BLOCK_PICTURE：类型(4B) + MIME 长度(4B) + MIME + 描述长度(4B) + 描述 + 宽高等 + 图像数据。 */
    private static byte[] decodeFlacPicture(byte[] block) {
        int offset = 0;
        int mimeLength = bigEndianInt(block, offset + 4);
        if (mimeLength <= 0 || offset + 8 + mimeLength > block.length) {
            return null;
        }
        String mime = ascii(block, offset + 8, Math.min(mimeLength, 24)).toLowerCase(Locale.ROOT);
        if (!mime.contains("png") && !mime.contains("jpeg") && !mime.contains("jpg")
                && !mime.contains("image")) {
            return null;
        }
        int afterMime = offset + 8 + mimeLength;
        int descriptionLength = bigEndianInt(block, afterMime);
        if (descriptionLength < 0) return null;
        int afterDescription = afterMime + 4 + descriptionLength;
        // 之后还有 16 字节固定字段（宽/高/色深/色数）。
        int dataLengthStart = afterDescription + 16;
        if (dataLengthStart + 4 > block.length) return null;
        int dataLength = bigEndianInt(block, dataLengthStart);
        if (dataLength <= 0 || dataLengthStart + 4 + dataLength > block.length) return null;
        byte[] image = new byte[dataLength];
        System.arraycopy(block, dataLengthStart + 4, image, 0, dataLength);
        return image;
    }

    // ════════════════════════════ MP4 / M4A ════════════════════════════

    private static Result readM4a(RandomAccessFile input) throws IOException {
        Result result = new Result();
        long durationMillis = scanM4aMoov(input, 0L, input.length(), 0, result);
        if (durationMillis > 0L && result.durationMillis <= 0L) {
            result.durationMillis = durationMillis;
        }
        return result.hasAny() ? result : null;
    }

    /** 返回从 mvhd 读到的时长；ilst 原子顺路填进 {@code result}。 */
    private static long scanM4aMoov(RandomAccessFile input, long start, long end,
                                    int depth, Result result) throws IOException {
        if (depth > 6 || start < 0L || end <= start || end > input.length()) {
            return 0L;
        }
        long position = start;
        long duration = 0L;
        while (position + 8L <= end) {
            input.seek(position);
            long size = input.readInt() & 0xFFFFFFFFL;
            String type = readAtomType(input);
            long headerSize = 8L;
            if (size == 1L) {
                if (position + 16L > end) {
                    break;
                }
                size = input.readLong();
                headerSize = 16L;
            } else if (size == 0L) {
                size = end - position;
            }
            if (size < headerSize || position + size > end) {
                break;
            }

            long contentStart = position + headerSize;
            long atomEnd = position + size;
            switch (type) {
                case "moov":
                case "udta":
                case "meta":
                case "ilst": {
                    long childStart = "meta".equals(type)
                            ? Math.min(atomEnd, contentStart + 4L) : contentStart;
                    long nested = scanM4aMoov(input, childStart, atomEnd, depth + 1, result);
                    if (nested > 0L && duration <= 0L) duration = nested;
                    break;
                }
                case "mvhd": {
                    long read = readMvhdDuration(input, contentStart, atomEnd);
                    if (read > 0L && duration <= 0L) duration = read;
                    break;
                }
                default:
                    if (MP4_COVER_ATOM.equals(type)) {
                        byte[] cover = readM4aDataAtom(input, contentStart, atomEnd, true);
                        if (cover != null && result.coverImage == null) result.coverImage = cover;
                    } else if (MP4_LYRICS_ATOM.equals(type)) {
                        String lyrics = readM4aTextAtom(input, contentStart, atomEnd);
                        if (result.lyrics.isEmpty()) result.lyrics = lyrics;
                    } else if ("\u00A9nam".equals(type)) {
                        String title = readM4aTextAtom(input, contentStart, atomEnd);
                        if (result.title.isEmpty()) result.title = title;
                    } else if ("\u00A9ART".equals(type) || "aART".equals(type)) {
                        String artist = readM4aTextAtom(input, contentStart, atomEnd);
                        if (result.artist.isEmpty()) result.artist = artist;
                    } else if ("\u00A9alb".equals(type)) {
                        String album = readM4aTextAtom(input, contentStart, atomEnd);
                        if (result.album.isEmpty()) result.album = album;
                    }
                    break;
            }
            position = atomEnd;
        }
        return duration;
    }

    /** mvhd 有 v0（32 位时标）与 v1（64 位时标）两种布局。 */
    private static long readMvhdDuration(RandomAccessFile input, long start, long end)
            throws IOException {
        if (end - start < 24L) return 0L;
        input.seek(start);
        int version = input.readUnsignedByte();
        input.skipBytes(3); // flags
        if (version == 1L) {
            if (end - start < 32L) return 0L;
            input.skipBytes(8); // creation + modification
            long timescale = input.readInt() & 0xFFFFFFFFL;
            long duration = input.readLong();
            return timescale > 0L && duration > 0L ? duration * 1000L / timescale : 0L;
        }
        input.skipBytes(8); // creation + modification
        long timescale = input.readInt() & 0xFFFFFFFFL;
        long duration = input.readInt() & 0xFFFFFFFFL;
        return timescale > 0L && duration > 0L ? duration * 1000L / timescale : 0L;
    }

    /** ilst 项的 data 原子：文本型返回解码文本，图片型返回字节；两种互斥由 {@code wantImage} 定。 */
    private static byte[] readM4aDataAtom(RandomAccessFile input, long start, long end,
                                          boolean wantImage) throws IOException {
        long position = start;
        while (position + 8L <= end) {
            input.seek(position);
            long size = input.readInt() & 0xFFFFFFFFL;
            String type = readAtomType(input);
            if (size < 8L || position + size > end) {
                return null;
            }
            if ("data".equals(type) && size > 16L) {
                input.seek(position + 8L);
                long dataType = input.readInt() & 0xFFFFFFFFL;
                input.skipBytes(4); // locale
                int payloadLength = (int) Math.min(MAX_COVER_BYTES, size - 16L);
                byte[] payload = new byte[payloadLength];
                input.readFully(payload);
                boolean image = dataType == 13 || dataType == 14; // 13=JPEG, 14=PNG
                if (wantImage == image) {
                    if (wantImage) {
                        return payload.length >= 64 ? payload : null;
                    }
                    String text = decodeM4aText(payload);
                    return text.isEmpty() ? null : payload;
                }
                return null;
            }
            position += size;
        }
        return null;
    }

    private static String readM4aTextAtom(RandomAccessFile input, long start, long end)
            throws IOException {
        byte[] payload = readM4aDataAtom(input, start, end, false);
        return payload == null ? "" : decodeM4aText(payload);
    }

    private static String decodeM4aText(byte[] text) {
        if (text.length == 0) {
            return "";
        }
        if (text.length >= 2 && ((text[0] & 0xFF) == 0xFE && (text[1] & 0xFF) == 0xFF
                || (text[0] & 0xFF) == 0xFF && (text[1] & 0xFF) == 0xFE)) {
            return clean(new String(text, StandardCharsets.UTF_16));
        }
        return clean(new String(text, StandardCharsets.UTF_8));
    }

    // ════════════════════════════ 共用小件 ════════════════════════════

    private static String firstTextLine(String text) {
        String cleaned = clean(text);
        int newline = cleaned.indexOf('\n');
        return newline >= 0 ? cleaned.substring(0, newline).trim() : cleaned;
    }

    private static long parseLongSafe(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    private static int indexOfZero(byte[] data, int start, int end) {
        for (int index = start; index < end && index < data.length; index++) {
            if (data[index] == 0) return index;
        }
        return -1;
    }

    private static int skipSingleZeroTerminator(byte[] data, int start, int end) {
        int at = indexOfZero(data, start, end);
        return at < 0 ? end : at + 1;
    }

    private static int skipDoubleZeroTerminator(byte[] data, int start, int end) {
        for (int index = start; index + 1 < end; index += 2) {
            if (data[index] == 0 && data[index + 1] == 0) {
                return index + 2;
            }
        }
        return end;
    }

    private static String decodeText(byte[] data, int offset, int length, int encoding) {
        if (length <= 0 || offset < 0 || offset + length > data.length) {
            return "";
        }
        Charset charset;
        switch (encoding) {
            case 1:
                charset = StandardCharsets.UTF_16;
                break;
            case 2:
                charset = StandardCharsets.UTF_16BE;
                break;
            case 3:
                charset = StandardCharsets.UTF_8;
                break;
            default:
                charset = StandardCharsets.ISO_8859_1;
                break;
        }
        return clean(new String(data, offset, length, charset));
    }

    private static byte[] removeUnsynchronisation(byte[] source) {
        List<Byte> cleaned = new ArrayList<>(source.length);
        for (int read = 0; read < source.length; read++) {
            cleaned.add(source[read]);
            if ((source[read] & 0xFF) == 0xFF && read + 1 < source.length && source[read + 1] == 0) {
                read++;
            }
        }
        byte[] result = new byte[cleaned.size()];
        for (int index = 0; index < cleaned.size(); index++) {
            result[index] = cleaned.get(index);
        }
        return result;
    }

    private static int synchsafeInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            return -1;
        }
        return (bytes[offset] & 0x7F) << 21 | (bytes[offset + 1] & 0x7F) << 14
                | (bytes[offset + 2] & 0x7F) << 7 | bytes[offset + 3] & 0x7F;
    }

    private static int bigEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            return -1;
        }
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8 | bytes[offset + 3] & 0xFF;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            return -1;
        }
        return bytes[offset] & 0xFF | (bytes[offset + 1] & 0xFF) << 8
                | (bytes[offset + 2] & 0xFF) << 16 | (bytes[offset + 3] & 0xFF) << 24;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            return "";
        }
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }

    private static String readAtomType(RandomAccessFile input) throws IOException {
        byte[] type = new byte[4];
        input.readFully(type);
        return new String(type, StandardCharsets.ISO_8859_1);
    }

    private static boolean matches(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((bytes[offset + index] & 0xFF) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').replace("\r\n", "\n").trim();
    }
}
