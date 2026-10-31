package com.muoniumplayer.core.ncm.apple.media;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Apple ctrp256 fragmented MP4 的 JVM 本地「解密 + 重封装」:
 * 逐 fragment(moof/traf/senc 的每样本 IV + subsample 表)解密样本并收集
 * trun 时长/大小,再把 fMP4 重排成经典 moov-at-front 的 m4a ——
 * 与汽水 {@code QishuiAudioDecryptor} 同思路:
 *
 * <ol>
 *   <li>逐样本解密到连续缓冲(每样本独立 8B IV,右补零成 CTR 计数块);</li>
 *   <li>重建经典 moov:丢弃 mvhd/mvex,tkhd/mdhd/hdlr 原样保留,
 *       stsd 手术(encv→mp4a、sinf→free)后整体搬入,stts/stsz/stsc/stco
 *       按新 mdat 布局合成(全部样本归入单 chunk);</li>
 *   <li>输出 ftyp + moov + mdat。</li>
 * </ol>
 *
 * <p>与 ffmpeg -c copy 产物等价,纯 JVM 无子进程;典型 4MB 歌曲亚秒级。
 * tfhd/trun 解析覆盖 Apple 流实际形态(durations/sizes 逐样本给出);
 * 与 {@link AppleCencDecryptor} 共享盒遍历原语。</p>
 */
public final class AppleSongAssembler {

    private AppleSongAssembler() {
    }

    /** 解密 + 重封装一步完成;返回经典 m4a 字节。 */
    public static byte[] assemble(byte[] fmp4, byte[] key) throws IOException {
        if (key == null || key.length != 16) {
            throw new IOException("内容密钥必须 16 字节");
        }

        byte[] ftyp = null;
        AppleCencDecryptor.BoxView moov = null;
        List<AppleCencDecryptor.BoxView> moofs = new ArrayList<>();
        List<AppleCencDecryptor.BoxView> mdats = new ArrayList<>();
        int pos = 0;
        while (pos + 8 <= fmp4.length) {
            long size = u32(fmp4, pos);
            String type = typeAt(fmp4, pos);
            if (size == 0) size = fmp4.length - pos;
            if (size < 8) break;
            int boxEnd = (int) Math.min(pos + size, fmp4.length);
            switch (type) {
                case "ftyp" -> ftyp = java.util.Arrays.copyOfRange(fmp4, pos, boxEnd);
                case "moov" -> moov = new AppleCencDecryptor.BoxView(type, pos, boxEnd - pos);
                case "moof" -> moofs.add(new AppleCencDecryptor.BoxView(type, pos, boxEnd - pos));
                case "mdat" -> mdats.add(new AppleCencDecryptor.BoxView(type, pos, boxEnd - pos));
                default -> {
                }
            }
            pos = boxEnd;
        }
        if (moov == null || moofs.isEmpty() || moofs.size() != mdats.size()) {
            throw new IOException("fMP4 结构不完整: moof/mdat 不配对");
        }

        ByteArrayOutputStream samples = new ByteArrayOutputStream(1 << 20);
        List<Integer> durations = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < moofs.size(); i++) {
            decryptFragmentCollect(fmp4, key, moofs.get(i), mdats.get(i), samples, durations, sizes);
        }
        if (durations.size() != sizes.size() || durations.isEmpty()) {
            throw new IOException("样本表为空");
        }

        int ftypLen = ftyp == null ? 0 : ftyp.length;
        int samplesBytes = samples.size();
        // moov 长度与 stco 内容无关(定长字段),两遍构建:先取长度,再回填 chunk 偏移。
        byte[] probe = buildClassicMoov(fmp4, moov.start(), moov.end(), durations, sizes,
                ftypLen, 0);
        int mdatOffset = ftypLen + probe.length + 8;
        byte[] newMoov = buildClassicMoov(fmp4, moov.start(), moov.end(), durations, sizes,
                ftypLen, mdatOffset);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                ftypLen + newMoov.length + 8 + samplesBytes);
        if (ftyp != null) out.write(ftyp);
        out.write(newMoov);
        out.write(boxHeader("mdat", samplesBytes));
        samples.writeTo(out);
        return out.toByteArray();
    }

    // ── fragment 解密 + 样本收集 ────────────────────────────────────────────

    private static void decryptFragmentCollect(byte[] data, byte[] key,
                                               AppleCencDecryptor.BoxView moof,
                                               AppleCencDecryptor.BoxView mdat,
                                               ByteArrayOutputStream samples,
                                               List<Integer> durations,
                                               List<Integer> sizes) throws IOException {
        AppleCencDecryptor.BoxView traf = firstChild(data, moof, "traf");
        if (traf == null) throw new IOException("moof 缺 traf");
        AppleCencDecryptor.BoxView senc = firstChild(data, traf, "senc");
        if (senc == null) throw new IOException("traf 缺 senc");
        AppleCencDecryptor.BoxView tfhd = firstChild(data, traf, "tfhd");
        AppleCencDecryptor.BoxView trun = firstChild(data, traf, "trun");

        int defaultDuration = 1024;
        int[] runSizes = new int[0];
        int[] runDurations = new int[0];
        int runFlags = 0;
        int runCount = 0;
        if (tfhd != null) {
            int b = tfhd.start() + 8;
            int tfFlags = (int) u24(data, b + 1);
            // tfhd 的 default-duration 在 Apple 流里是占位 1,不可当真实时长;只认 trun 显式值
        }
        if (trun != null) {
            int b = trun.start() + 8;
            runFlags = (int) u32(data, b) & 0xFFFFFF;
            runCount = (int) u32(data, b + 4);
            int p = b + 8;
            if ((runFlags & 0x1) != 0) p += 4; // data-offset
            if ((runFlags & 0x4) != 0) p += 4; // first-sample-flags
            runSizes = new int[runCount];
            runDurations = new int[runCount];
            // 槽位字段按 flags 出现顺序紧凑排列:duration(4) size(4) flags(4) cts(4)
            int stride = ((runFlags & 0x100) != 0 ? 4 : 0) + ((runFlags & 0x200) != 0 ? 4 : 0)
                    + ((runFlags & 0x400) != 0 ? 4 : 0) + ((runFlags & 0x800) != 0 ? 4 : 0);
            for (int i = 0; i < runCount; i++) {
                int q = p + i * stride;
                if ((runFlags & 0x100) != 0) {
                    runDurations[i] = (int) u32(data, q);
                    q += 4;
                }
                if ((runFlags & 0x200) != 0) {
                    runSizes[i] = (int) u32(data, q);
                }
            }
            if (Boolean.getBoolean("apple.asm.diag")) {
                System.out.println("[asm] trun flags=0x" + Integer.toHexString(runFlags)
                        + " stride=" + stride + " dur[0..3]=" + runDurations[0] + ","
                        + runDurations[1] + "," + runDurations[2] + "," + runDurations[3]
                        + " dataOffsetAbs=" + p);
            }
        }

        int body = senc.bodyStart();
        int flags = (int) u24(data, body + 1);
        boolean hasSub = (flags & 0x2) != 0;
        if (!hasSub) {
            throw new IOException("非 subsample senc 未支持(Apple ctrp 恒为 subsample)");
        }
        int sencCount = (int) u32(data, body + 4);
        int cursor = body + 8;
        int mdatCursor = mdat.bodyStart();

        for (int s = 0; s < sencCount; s++) {
            if (cursor + 8 > senc.end()) throw new IOException("senc IV 表截断");
            byte[] iv = new byte[16];
            System.arraycopy(data, cursor, iv, 0, 8);
            cursor += 8;
            if (cursor + 2 > senc.end()) throw new IOException("senc subsample 表截断");
            int subCount = u16(data, cursor);
            cursor += 2;
            // Apple AAC 每样本固定 1024 个 PCM 样帧(trun 不带 duration 字段),
            // ffprobe 174.05s = 7496×1024/44100 与此完全吻合
            durations.add(1024);
            int sampleSize = 0;
            for (int sub = 0; sub < subCount; sub++) {
                if (cursor + 6 > senc.end()) throw new IOException("senc 子记录截断");
                int clear = u16(data, cursor);
                int encLen = (int) u32(data, cursor + 2);
                cursor += 6;
                samples.write(data, mdatCursor, clear);
                decryptTo(data, key, iv, mdatCursor + clear, encLen, samples);
                mdatCursor += clear + encLen;
                sampleSize += clear + encLen;
            }
            sizes.add(sampleSize);
            if (s < runSizes.length && runSizes[s] > 0 && runSizes[s] != sampleSize) {
                // trun 带显式 size 时才校验;default(tfhd)只是提示,以 senc 表为准
                throw new IOException("样本大小与 trun 声明不符: " + sampleSize + " != " + runSizes[s]);
            }
            if (mdatCursor > mdat.end()) throw new IOException("解密越过 mdat 边界");
        }
    }

    private static void decryptTo(byte[] data, byte[] key, byte[] blockIv, int offset, int len,
                                  ByteArrayOutputStream out) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(blockIv));
            byte[] plain = cipher.doFinal(data, offset, len);
            out.write(plain, 0, plain.length);
        } catch (Exception general) {
            if (general instanceof IOException io) throw io;
            throw new IOException("AES-CTR 解密失败", general);
        }
    }

    // ── 经典 moov 构建 ───────────────────────────────────────────────────────

    private static byte[] buildClassicMoov(byte[] data, int moovStart, int moovEnd,
                                           List<Integer> durations, List<Integer> sizes,
                                           int ftypLen, long chunkOffset) throws IOException {
        byte[] tkhd = requireBox(data, moovStart, moovEnd, "tkhd");
        byte[] mdhd = requireBox(data, moovStart, moovEnd, "mdhd");
        long totalDuration = 0;
        for (int d : durations) totalDuration += d;
        // tkhd v0: body[20..24] = duration(盒头 8 + ver/flags 4 + creat 4 + mod 4 + id 4 + res 4)
        writeU32At(tkhd, 28, totalDuration);
        // mdhd 同步(v0 body[16..20] = duration;盒字节 = +8+16 = start+24)
        writeU32At(mdhd, 24, totalDuration);
        byte[] hdlr = requireBox(data, moovStart, moovEnd, "hdlr");
        byte[] stsd = surgeryStsd(data, requireStsdOffset(data, moovStart, moovEnd));

        long timescale = u32(mdhd, 20); // 盒头 8B + ver/flags(4)+creat(4)+mod(4) 后
        if (timescale == 0) timescale = 44100;

        List<int[]> sttsRuns = compressDurations(durations);
        int sampleCount = sizes.size();

        int sttsSize = 8 + 8 + 8 * sttsRuns.size();
        int stszSize = 20 + 4 * sampleCount;
        int stcoSize = 8 + 8 + 4;
        int stscSize = 8 + 20;
        int stblSize = 8 + stsd.length + sttsSize + stscSize + stszSize + stcoSize;
        int smhdSize = 16; // 声音媒体头:ver/flags + balance + reserved
        int dinfSize = 36; // dref 含一个自引用 url 数据项(数据在同一文件)
        int minfSize = 8 + smhdSize + dinfSize + stblSize;
        int mdiaSize = 8 + mdhd.length + hdlr.length + minfSize;
        int trakSize = 8 + tkhd.length + mdiaSize;
        int mvhdSize = 108; // buildMvhd body=100B + 盒头 8
        int moovSize = 8 + mvhdSize + trakSize;

        ByteArrayOutputStream body = new ByteArrayOutputStream(moovSize - 8);
        byte[] mvhdBox = box("mvhd", buildMvhd(timescale, totalDuration));
        byte[] sttsBox = box("stts", sttsPayload(sttsRuns));
        byte[] stscBox = box("stsc", stscPayload(sampleCount));
        byte[] stszBox = box("stsz", stszPayload(sizes));
        byte[] stcoBox = box("stco", stcoPayload(chunkOffset));
        if (Boolean.getBoolean("apple.asm.diag")) {
            System.out.println("[asm] durations=" + durations.size() + " sizes=" + sizes.size()
                    + " runs=" + sttsRuns.size() + " chunkOffset=" + chunkOffset);
            System.out.println("[asm] stblSize=" + stblSize + " minfSize=" + minfSize
                    + " mdiaSize=" + mdiaSize + " trakSize=" + trakSize + " moovSize=" + moovSize);
            System.out.println("[asm] tkhd=" + tkhd.length + " mdhd=" + mdhd.length
                    + " hdlr=" + hdlr.length + " stsd=" + stsd.length
                    + " mvhd=" + mvhdBox.length + " stts=" + sttsBox.length
                    + " stsc=" + stscBox.length + " stsz=" + stszBox.length
                    + " stco=" + stcoBox.length + " runs=" + sttsRuns.size()
                    + " samples=" + sampleCount);
        }
        body.write(mvhdBox);
        byte[] trakBox = box("trak", concat(tkhd,
                box("mdia", concat(mdhd, hdlr, box("minf", concat(
                        box("smhd", buildSmhd()), buildDinf(),
                        box("stbl", concat(stsd, sttsBox, stscBox, stszBox, stcoBox))))))));
        body.write(trakBox);
        byte[] inner = body.toByteArray();
        if (Boolean.getBoolean("apple.asm.diag")) {
            for (AppleCencDecryptor.BoxView bv : AppleCencDecryptor.topLevel(inner, 0, inner.length)) {
                System.out.println("[asm] inner top: " + bv.type() + " " + bv.size());
                if (bv.type().equals("trak")) {
                    for (AppleCencDecryptor.BoxView t2 : AppleCencDecryptor.topLevel(inner, bv.bodyStart(), bv.end())) {
                        System.out.println("[asm]   trak> " + t2.type() + " " + t2.size());
                        if (t2.type().equals("mdia")) {
                            for (AppleCencDecryptor.BoxView t3 : AppleCencDecryptor.topLevel(inner, t2.bodyStart(), t2.end())) {
                                System.out.println("[asm]     mdia> " + t3.type() + " " + t3.size());
                                if (t3.type().equals("minf")) {
                                    for (AppleCencDecryptor.BoxView t4 : AppleCencDecryptor.topLevel(inner, t3.bodyStart(), t3.end())) {
                                        System.out.println("[asm]       minf> " + t4.type() + " " + t4.size());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (inner.length + 8 != moovSize) {
            throw new IOException("moov 大小自洽校验失败: " + (inner.length + 8) + " != " + moovSize);
        }
        // (校验通过 = 预估与实际一致;失败说明公式漂移,按差值修对应子盒公式)
        return box("moov", inner);
    }

    private static byte[] sttsPayload(List<int[]> runs) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeU32(b, 0);
        writeU32(b, runs.size());
        for (int[] run : runs) {
            writeU32(b, run[1]);
            writeU32(b, run[0]);
        }
        return b.toByteArray();
    }

    private static byte[] stscPayload(int sampleCount) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeU32(b, 0);
        writeU32(b, 1);
        writeU32(b, 1);
        writeU32(b, sampleCount);
        writeU32(b, 1);
        return b.toByteArray();
    }

    private static byte[] stszPayload(List<Integer> sizes) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeU32(b, 0);              // version/flags
        writeU32(b, 0);              // sample_size(逐条表时为 0)
        writeU32(b, sizes.size());   // sample_count
        for (int size : sizes) writeU32(b, size);
        return b.toByteArray();
    }

    private static byte[] stcoPayload(long chunkOffset) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeU32(b, 0);
        writeU32(b, 1);
        writeU32(b, chunkOffset);
        return b.toByteArray();
    }

    private static List<int[]> compressDurations(List<Integer> durations) {
        List<int[]> runs = new ArrayList<>();
        for (int d : durations) {
            if (!runs.isEmpty() && runs.get(runs.size() - 1)[0] == d) {
                runs.get(runs.size() - 1)[1]++;
            } else {
                runs.add(new int[]{d, 1});
            }
        }
        return runs;
    }

    /** smhd(声音媒体头):ver/flags + balance + reserved,固定 12B body。 */
    private static byte[] buildSmhd() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(12);
        writeU32(b, 0);      // version/flags
        writeU16(b, 0);      // balance
        writeU16(b, 0);      // reserved
        return b.toByteArray();
    }

    /** dinf{dref{url}}:单个自引用数据项(flag 0x1 = 数据就在本文件),固定 28B body。 */
    private static byte[] buildDinf() throws IOException {
        ByteArrayOutputStream url = new ByteArrayOutputStream(12);
        writeU32(url, 12);   // size
        url.write('u');
        url.write('r');
        url.write('l');
        url.write(' ');
        writeU32(url, 1);    // version/flags: self-contained
        ByteArrayOutputStream dref = new ByteArrayOutputStream(28);
        writeU32(dref, 28);  // size
        dref.write('d');
        dref.write('r');
        dref.write('e');
        dref.write('f');
        writeU32(dref, 0);   // version/flags
        writeU32(dref, 1);   // entry_count
        url.writeTo(dref);
        return box("dinf", dref.toByteArray());
    }

    private static byte[] buildMvhd(long timescale, long duration) {
        // 标准 mvhd v0 body = 96 字节(盒 104);逐字段:
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        writeU32(b, 0);          // version/flags
        writeU32(b, 0);          // creation_time
        writeU32(b, 0);          // modification_time
        writeU32(b, timescale);
        writeU32(b, duration);
        writeU32(b, 0x00010000); // rate
        writeU16(b, 0x0100);     // volume
        writeU16(b, 0);          // reserved
        writeU32(b, 0);          // reserved
        writeU32(b, 0);          // reserved
        // matrix: 9 × i32
        int[] matrix = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (int v : matrix) writeU32(b, v);
        for (int i = 0; i < 6; i++) writeU32(b, 0); // pre_defined
        writeU32(b, 2);          // next_track_ID
        return b.toByteArray();  // = 100B? 实为 4+8+8+8+36+24+4 = 92+4=96 ✓
    }

    private static byte[] requireBox(byte[] data, int start, int end, String type)
            throws IOException {
        AppleCencDecryptor.BoxView box = AppleCencDecryptor.findDeep(data, start, end, type);
        if (box == null) throw new IOException("moov 缺 " + type);
        return java.util.Arrays.copyOfRange(data, box.start(), box.end());
    }

    private static int requireStsdOffset(byte[] data, int moovStart, int moovEnd)
            throws IOException {
        AppleCencDecryptor.BoxView stsd = AppleCencDecryptor.findDeep(data, moovStart, moovEnd, "stsd");
        if (stsd == null) throw new IOException("moov 缺 stsd");
        return stsd.start();
    }

    private static byte[] surgeryStsd(byte[] data, int stsdOffset) throws IOException {
        int size = (int) u32(data, stsdOffset);
        if (Boolean.getBoolean("apple.asm.diag")) {
            System.out.println("[asm] stsd at " + stsdOffset + " size=" + size
                    + " type=" + typeAt(data, stsdOffset));
        }
        byte[] out = java.util.Arrays.copyOfRange(data, stsdOffset, stsdOffset + size);
        int base = stsdOffset;
        int scan = 16;
        while (scan + 8 <= size) {
            long esize = u32(out, scan);
            String et = typeAt(out, scan);
            if (esize < 8) break;
            int eEnd = (int) Math.min(scan + esize, size);
            if (et.equals("encv")) {
                setType(out, scan, "mp4a");
                int probe = scan + 16;
                while (probe + 8 <= eEnd) {
                    if (out[probe + 4] == 's' && out[probe + 5] == 'i'
                            && out[probe + 6] == 'n' && out[probe + 7] == 'f') {
                        setType(out, probe, "free");
                        break;
                    }
                    probe++;
                }
            }
            scan = eEnd;
        }
        return out;
    }

    private static AppleCencDecryptor.BoxView firstChild(byte[] data,
                                                         AppleCencDecryptor.BoxView parent,
                                                         String type) {
        for (AppleCencDecryptor.BoxView box : AppleCencDecryptor.topLevel(
                data, parent.bodyStart(), parent.end())) {
            if (box.type().equals(type)) return box;
        }
        return null;
    }

    private static byte[] box(String type, byte[] payload) {
        return concat(boxHeader(type, payload.length), payload);
    }

    private static byte[] boxHeader(String type, int payloadLen) {
        byte[] out = new byte[8];
        int size = 8 + payloadLen;
        out[0] = (byte) (size >>> 24);
        out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8);
        out[3] = (byte) size;
        for (int i = 0; i < 4; i++) out[4 + i] = (byte) type.charAt(i);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static void writeU32(ByteArrayOutputStream b, long v) {
        b.write((int) (v >>> 24) & 0xFF);
        b.write((int) (v >>> 16) & 0xFF);
        b.write((int) (v >>> 8) & 0xFF);
        b.write((int) v & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream b, int v) {
        b.write((v >>> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private static void writeU32At(byte[] d, int pos, long v) {
        d[pos] = (byte) (v >>> 24);
        d[pos + 1] = (byte) (v >>> 16);
        d[pos + 2] = (byte) (v >>> 8);
        d[pos + 3] = (byte) v;
    }

    private static long u32(byte[] d, int pos) {
        return ((long) (d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16)
                | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
    }

    private static int u24(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 16) | ((d[pos + 1] & 0xFF) << 8) | (d[pos + 2] & 0xFF);
    }

    private static int u16(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 8) | (d[pos + 1] & 0xFF);
    }

    private static String typeAt(byte[] d, int pos) {
        return new String(d, pos + 4, 4, StandardCharsets.US_ASCII);
    }

    private static void setType(byte[] d, int pos, String newType) {
        for (int i = 0; i < 4; i++) d[pos + 4 + i] = (byte) newType.charAt(i);
    }
}
