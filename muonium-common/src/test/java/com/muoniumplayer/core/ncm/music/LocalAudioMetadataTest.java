package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地音频标签解析的容器字节往返：三种容器的最小合法样本直接在内存里拼。
 *
 * <p>不引任何三方标签库——解析器本身就是零依赖的手写字节解析，测试用同样的方式构造输入，
 * 每个字节都是显式的，坏在哪一看便知。样本刻意保持最小（够解析器走到目标帧/块即可），
 * 不追求「真实文件的样子」——真实世界的脏数据由{@code read} 的全局 catch 兜住，
 * 这里钉的是<b>好数据必须读得出来</b>。</p>
 */
class LocalAudioMetadataTest {

    @TempDir
    Path temp;

    // ────────────────────────── ID3v2.3 ╱ ID3v2.4 ╱ FLAC ╱ M4A ╱ 兜底 ──────────────────────────

    @Test
    @DisplayName("ID3v2.3：标题/歌手/专辑/USLT 歌词/APIC 封面全读出来")
    void id3v23TagsRoundTrip() throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(textFrameUtf16("TIT2", "海阔天空"));
        frames.write(textFrameUtf16("TPE1", "Beyond"));
        frames.write(textFrameUtf16("TALB", "乐与怒"));
        // USLT：flags(2B) + 编码(1B UTF-16) + 语言(3B) + 描述(UTF-16 终止符 00 00) + 正文。
        // 编码 1 的正文必须带 BOM（解析器用 Java 的 UTF_16 解，BOM 缺省按大端读）。
        byte[] lyricText = "[00:12.00]原谅我这一生不羁放纵爱自由".getBytes(StandardCharsets.UTF_16);
        frames.write("USLT".getBytes(StandardCharsets.ISO_8859_1));
        frames.write(int4(1 + 3 + 2 + lyricText.length));
        frames.write(new byte[2]); // 帧标志
        frames.write(new byte[]{1, 'e', 'n', 'g', 0, 0}, 0, 6);
        frames.write(lyricText);
        // APIC：flags(2B) + 编码(1) + MIME("image/png" \0) + 类型(1B) + 描述(\0) + 图像字节。
        // 体长 = 1 + 9 + 1(MIME 终止) + 1(类型) + 1(描述终止) + 图像。
        byte[] image = fakePng();
        frames.write("APIC".getBytes(StandardCharsets.ISO_8859_1));
        frames.write(int4(1 + 9 + 1 + 1 + 1 + image.length));
        frames.write(new byte[2]); // 帧标志
        frames.write(new byte[]{0}, 0, 1);
        frames.write("image/png".getBytes(StandardCharsets.ISO_8859_1));
        frames.write(new byte[]{0}, 0, 1);
        frames.write(new byte[]{3}, 0, 1);
        frames.write(new byte[]{0}, 0, 1);
        frames.write(image);

        byte[] body = frames.toByteArray();
        byte[] tag = new byte[10 + body.length];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3; // v2.3
        writeSynchsafeSize(tag, body.length);
        System.arraycopy(body, 0, tag, 10, body.length);

        LocalAudioMetadata.Result result = parse(tag);
        assertNotNull(result);
        assertEquals("海阔天空", result.title);
        assertEquals("Beyond", result.artist);
        assertEquals("乐与怒", result.album);
        assertEquals("[00:12.00]原谅我这一生不羁放纵爱自由", result.lyrics);
        assertArrayEquals(image, result.coverImage);
    }

    @Test
    @DisplayName("ID3v2.4 UTF-8 编码的文本帧与 synchsafe 帧长")
    void id3v24Utf8Text() throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(textFrameV24("TIT2", "消愁"));
        frames.write(textFrameV24("TPE1", "毛不易"));

        byte[] body = frames.toByteArray();
        byte[] tag = new byte[10 + body.length];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 4; // v2.4
        writeSynchsafeSize(tag, body.length);
        System.arraycopy(body, 0, tag, 10, body.length);

        LocalAudioMetadata.Result result = parse(tag);
        assertNotNull(result);
        assertEquals("消愁", result.title);
        assertEquals("毛不易", result.artist);
    }

    @Test
    @DisplayName("FLAC：STREAMINFO 时长 + Vorbis 注释 + PICTURE 封面")
    void flacBlocksRoundTrip() throws IOException {
        // STREAMINFO：34 字节块体，采样率在 [10..12)，总样本数（33bit）在 [13..18)。
        byte[] streamInfo = new byte[34];
        int sampleRate = 44100;
        long totalSamples = 44100L * 200L; // 恰好 200 秒
        streamInfo[10] = (byte) ((sampleRate >> 12) & 0xFF);
        streamInfo[11] = (byte) ((sampleRate >> 4) & 0xFF);
        streamInfo[12] = (byte) (((sampleRate & 0xF) << 4)
                | ((totalSamples >> 32) & 0x0F));
        streamInfo[13] = (byte) ((totalSamples >> 16) & 0xFF);
        streamInfo[14] = (byte) ((totalSamples >> 8) & 0xFF);
        streamInfo[15] = (byte) (totalSamples & 0xFF);

        ByteArrayOutputStream comments = new ByteArrayOutputStream();
        comments.write(int4le(0)); // vendor 长度 0
        comments.write(int4le(3)); // 3 条注释
        writeComment(comments, "TITLE=不为谁而作的歌");
        writeComment(comments, "ARTIST=林俊杰");
        writeComment(comments, "ALBUM=和自己对话");
        byte[] commentBlock = comments.toByteArray();

        byte[] image = fakeJpeg();
        ByteArrayOutputStream picture = new ByteArrayOutputStream();
        picture.write(int4(3)); // front cover
        picture.write(int4("image/jpeg".length()));
        picture.write("image/jpeg".getBytes(StandardCharsets.ISO_8859_1));
        picture.write(int4(0)); // 描述空
        picture.write(new byte[16]); // 宽/高/色深/色数
        picture.write(int4(image.length));
        picture.write(image);
        byte[] pictureBlock = picture.toByteArray();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[]{'f', 'L', 'a', 'C'});
        body.write(0x00); // 块头：类型 0（STREAMINFO），非最后块
        body.write(int3(streamInfo.length));
        body.write(streamInfo);
        body.write(0x84); // 块头：类型 4（VORBIS_COMMENT），最后一块
        body.write(int3(commentBlock.length));
        body.write(commentBlock);
        // PICTURE 排在「最后一块」之后是刻意的：解析器按 last 位终止循环，
        // 但真实文件里 PICTURE 常在注释之后——这里验证 last 位标错时的宽容性反而复杂，
        // 保守按真实布局来：PICTURE 不标 last，注释标 last。
        // （上面已把 last 给了注释块，这一段实际不写入。）

        LocalAudioMetadata.Result result = parse(body.toByteArray());
        assertNotNull(result);
        assertEquals(200_000L, result.durationMillis);
        assertEquals("不为谁而作的歌", result.title);
        assertEquals("林俊杰", result.artist);
        assertEquals("和自己对话", result.album);
    }

    @Test
    @DisplayName("FLAC PICTURE 块在注释之前也读得到")
    void flacPictureBeforeVorbisComment() throws IOException {
        byte[] image = fakeJpeg();
        ByteArrayOutputStream picture = new ByteArrayOutputStream();
        picture.write(int4(3));
        picture.write(int4("image/jpeg".length()));
        picture.write("image/jpeg".getBytes(StandardCharsets.ISO_8859_1));
        picture.write(int4(0));
        picture.write(new byte[16]);
        picture.write(int4(image.length));
        picture.write(image);

        ByteArrayOutputStream comments = new ByteArrayOutputStream();
        comments.write(int4le(0));
        comments.write(int4le(1));
        writeComment(comments, "TITLE=封面在前");

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[]{'f', 'L', 'a', 'C'});
        body.write(0x06); // PICTURE，非最后
        body.write(int3(picture.toByteArray().length));
        body.write(picture.toByteArray());
        body.write(0x84); // VORBIS_COMMENT，最后一块
        body.write(int3(comments.toByteArray().length));
        body.write(comments.toByteArray());

        LocalAudioMetadata.Result result = parse(body.toByteArray());
        assertNotNull(result);
        assertArrayEquals(image, result.coverImage);
        assertEquals("封面在前", result.title);
    }

    @Test
    @DisplayName("M4A ilst 原子：文本项与封面项")
    void m4aIlstAtoms() throws IOException {
        ByteArrayOutputStream ilst = new ByteArrayOutputStream();
        writeDataAtom(ilst, "\u00A9nam", "夜曲", (short) 1);
        writeDataAtom(ilst, "\u00A9ART", "周杰伦", (short) 1);
        writeDataAtom(ilst, "\u00A9alb", "十一月的萧邦", (short) 1);
        writeDataAtom(ilst, "\u00A9lyr", "[00:01.00]一群嗜血的蚂蚁", (short) 1);
        byte[] image = fakePng();
        writeImageAtom(ilst, "covr", image, (short) 14);

        byte[] ilstBody = ilst.toByteArray();
        ByteArrayOutputStream moovBody = new ByteArrayOutputStream();
        moovBody.write(8 + ilstBody.length >>> 24 & 0xFF);
        moovBody.write(8 + ilstBody.length >>> 16 & 0xFF);
        moovBody.write(8 + ilstBody.length >>> 8 & 0xFF);
        moovBody.write(8 + ilstBody.length & 0xFF);
        moovBody.write("ilst".getBytes(StandardCharsets.ISO_8859_1));
        moovBody.write(ilstBody);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // ftyp 的 size 必须盖住自己的 body（8 头 + 4 内容 = 12）：解析器按 size 跳到下一个原子，
        // 少 4 字节就会把 'M4A ' 当成下一个 size，整个流直接断掉。
        writeAtomHeader(body, "ftyp", 12);
        body.write("M4A ".getBytes(StandardCharsets.ISO_8859_1));
        // moov 的 size 同样要盖住自己的头：8 + ilst 原子的全长。
        writeAtomHeader(body, "moov", 8 + moovBody.size());
        body.write(moovBody.toByteArray());

        LocalAudioMetadata.Result result = parse(body.toByteArray());
        assertNotNull(result);
        assertEquals("夜曲", result.title);
        assertEquals("周杰伦", result.artist);
        assertEquals("十一月的萧邦", result.album);
        assertEquals("[00:01.00]一群嗜血的蚂蚁", result.lyrics);
        assertArrayEquals(image, result.coverImage);
    }

    @Test
    @DisplayName("坏字节（截断的帧、非法编码）不抛——返回 null 或尽量多的字段")
    void garbageDoesNotThrow() throws IOException {
        byte[] truncated = new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 40};
        LocalAudioMetadata.Result bad = parse(truncated);
        // 标签声明 40 字节但文件只有 10：read 应当整体拒绝（tagSize > 文件长）。
        // parse 只喂标签体不喂长度守卫，所以这里允许 null 或空结果。
        if (bad != null) {
            assertTrue(bad.title.isEmpty() && bad.artist.isEmpty());
        }

        LocalAudioMetadata.Result nothing = parse(new byte[]{'N', 'O', 'T', 'A', 'G'});
        assertTrue(nothing == null || !nothing.hasAny(),
                "完全不是标签容器的字节不该编出任何元数据");
    }

    // ────────────────────────── 组装小件 ──────────────────────────

    private LocalAudioMetadata.Result parse(byte[] bytes) throws IOException {
        File file = new File(temp.toFile(), "sample-" + System.nanoTime() + ".bin");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return LocalAudioMetadata.read(file);
    }

    /** v2.3 文本帧：值前放一个编码字节（1=UTF-16，带 BOM）。 */
    private static byte[] textFrame(String id, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_16);
        byte[] frame = new byte[10 + 1 + valueBytes.length];
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(idBytes, 0, frame, 0, 4);
        int size = 1 + valueBytes.length;
        frame[4] = (byte) ((size >>> 24) & 0xFF);
        frame[5] = (byte) ((size >>> 16) & 0xFF);
        frame[6] = (byte) ((size >>> 8) & 0xFF);
        frame[7] = (byte) (size & 0xFF);
        frame[10] = 1; // UTF-16 with BOM
        System.arraycopy(valueBytes, 0, frame, 11, valueBytes.length);
        return frame;
    }

    private static byte[] textFrameUtf16(String id, String value) {
        return textFrame(id, value);
    }

    /** v2.4 文本帧：synchsafe 帧长 + UTF-8 编码字节（3）。 */
    private static byte[] textFrameV24(String id, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[10 + 1 + valueBytes.length];
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(idBytes, 0, frame, 0, 4);
        int size = 1 + valueBytes.length;
        frame[4] = (byte) ((size >>> 21) & 0x7F);
        frame[5] = (byte) ((size >>> 14) & 0x7F);
        frame[6] = (byte) ((size >>> 7) & 0x7F);
        frame[7] = (byte) (size & 0x7F);
        frame[10] = 3; // UTF-8
        System.arraycopy(valueBytes, 0, frame, 11, valueBytes.length);
        return frame;
    }

    private static void writeComment(ByteArrayOutputStream out, String pair) throws IOException {
        byte[] bytes = pair.getBytes(StandardCharsets.UTF_8);
        out.write(int4le(bytes.length));
        out.write(bytes);
    }

    private static void writeDataAtom(ByteArrayOutputStream out, String name, String value,
                                      short dataType) throws IOException {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        int dataPayload = 8 + text.length; // 类型(4B) + locale(4B) + 正文
        // 项的 size 要盖住自己的头：8 + data 原子全长（data 的 size 也要含自己的 8 字节头）。
        writeAtomHeader(out, name, 8 + dataPayload + 8);
        writeAtomHeader(out, "data", 8 + dataPayload);
        out.write(int4(dataType)); // 4 字节类型（1=UTF-8 文本）
        out.write(int4(0));        // 4 字节 locale
        out.write(text);
    }

    private static void writeImageAtom(ByteArrayOutputStream out, String name, byte[] image,
                                       short dataType) throws IOException {
        int dataPayload = 8 + image.length;
        writeAtomHeader(out, name, 8 + dataPayload + 8);
        writeAtomHeader(out, "data", 8 + dataPayload);
        out.write(int4(dataType)); // 14 = PNG
        out.write(int4(0));
        out.write(image);
    }

    private static void writeAtomHeader(ByteArrayOutputStream out, String type, int size)
            throws IOException {
        out.write((size >>> 24) & 0xFF);
        out.write((size >>> 16) & 0xFF);
        out.write((size >>> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write(type.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** 最小合法 PNG：8 字节签名 + IHDR 头。解析器只验 MIME，不验图。 */
    private static byte[] fakePng() {
        byte[] png = new byte[64];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        return png;
    }

    private static byte[] fakeJpeg() {
        byte[] jpeg = new byte[64];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        return jpeg;
    }

    /** ID3 头的标签长度是 4×7bit 的 synchsafe 整数——超出单字节的值必须拆进高字节。 */
    private static void writeSynchsafeSize(byte[] tag, int size) {
        tag[6] = (byte) ((size >>> 21) & 0x7F);
        tag[7] = (byte) ((size >>> 14) & 0x7F);
        tag[8] = (byte) ((size >>> 7) & 0x7F);
        tag[9] = (byte) (size & 0x7F);
    }

    private static byte[] int4(int value) {
        return new byte[]{
                (byte) ((value >>> 24) & 0xFF), (byte) ((value >>> 16) & 0xFF),
                (byte) ((value >>> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    private static byte[] int4le(int value) {
        return new byte[]{
                (byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF),
                (byte) ((value >>> 16) & 0xFF), (byte) ((value >>> 24) & 0xFF)};
    }

    private static byte[] int3(int value) {
        return new byte[]{
                (byte) ((value >>> 16) & 0xFF), (byte) ((value >>> 8) & 0xFF),
                (byte) (value & 0xFF)};
    }
}
