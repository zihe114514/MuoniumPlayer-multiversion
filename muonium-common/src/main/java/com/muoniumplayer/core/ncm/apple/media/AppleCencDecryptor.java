package com.muoniumplayer.core.ncm.apple.media;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@link AppleCencDecryptor} 的盒遍历重构版:firstChild 用显式数据参数,
 * 去掉自引用占位;顶层遍历与递归查找全部走同一条路径。
 */
public final class AppleCencDecryptor {

    private AppleCencDecryptor() {
    }

    public record BoxView(String type, int start, int size) {
        public int bodyStart() {
            return start + 8;
        }

        public int end() {
            return start + size;
        }
    }

    public static List<BoxView> topLevel(byte[] data, int start, int end) {
        java.util.List<BoxView> out = new java.util.ArrayList<>();
        int pos = start;
        while (pos + 8 <= end) {
            long size = u32(data, pos);
            String type = typeAt(data, pos);
            if (size == 0) size = end - pos;
            if (size < 8) break;
            int boxEnd = (int) Math.min(pos + size, end);
            out.add(new BoxView(type, pos, boxEnd - pos));
            pos = boxEnd;
        }
        return out;
    }

    public static BoxView findDeep(byte[] data, int start, int end, String type) {
        for (BoxView box : topLevel(data, start, end)) {
            if (box.type().equals(type)) return box;
            if (box.type().equals("mdat")) continue;
            int innerStart = box.bodyStart();
            if (box.type().equals("stsd")) innerStart += 8;
            BoxView hit = findDeep(data, innerStart, box.end(), type);
            if (hit != null) return hit;
        }
        return null;
    }

    public static long decryptInPlace(byte[] data, byte[] key) throws IOException {
        if (key == null || key.length != 16) {
            throw new IOException("内容密钥必须 16 字节");
        }
        long decrypted = 0;
        List<BoxView> moofs = new java.util.ArrayList<>();
        List<BoxView> mdats = new java.util.ArrayList<>();
        int pos = 0;
        while (pos + 8 <= data.length) {
            long size = u32(data, pos);
            String type = typeAt(data, pos);
            if (size == 0) size = data.length - pos;
            if (size < 8) break;
            int boxEnd = (int) Math.min(pos + size, data.length);
            switch (type) {
                case "moof" -> moofs.add(new BoxView(type, pos, boxEnd - pos));
                case "mdat" -> mdats.add(new BoxView(type, pos, boxEnd - pos));
                case "moov" -> surgeryMoov(data, pos, boxEnd);
                default -> {
                }
            }
            pos = boxEnd;
        }
        if (moofs.size() != mdats.size() || moofs.isEmpty()) {
            throw new IOException("moof/mdat 数量不配对: " + moofs.size() + "/" + mdats.size());
        }
        for (int i = 0; i < moofs.size(); i++) {
            decrypted += decryptFragment(data, key, moofs.get(i), mdats.get(i));
        }
        return decrypted;
    }

    private static long decryptFragment(byte[] data, byte[] key, BoxView moof, BoxView mdat)
            throws IOException {
        BoxView traf = firstChild(data, moof, "traf");
        if (traf == null) throw new IOException("moof 缺 traf");
        BoxView senc = firstChild(data, traf, "senc");
        if (senc == null) throw new IOException("traf 缺 senc");
        int body = senc.bodyStart();
        int flags = (int) u24(data, body + 1);
        boolean hasSubsample = (flags & 0x2) != 0;
        int count = (int) u32(data, body + 4);
        int cursor = body + 8;
        int mdatCursor = mdat.bodyStart();
        int mdatEnd = mdat.end();
        long total = 0;
        for (int sample = 0; sample < count; sample++) {
            if (cursor + 8 > senc.end()) throw new IOException("senc IV 表截断");
            byte[] iv = new byte[16];
            System.arraycopy(data, cursor, iv, 0, 8);
            cursor += 8;
            int subCount = 1;
            if (hasSubsample) {
                if (cursor + 2 > senc.end()) throw new IOException("senc subsample 表截断");
                subCount = u16(data, cursor);
                cursor += 2;
            }
            for (int sub = 0; sub < subCount; sub++) {
                if (!hasSubsample) break;
                if (cursor + 6 > senc.end()) throw new IOException("senc 子记录截断");
                int clear = u16(data, cursor);
                int encLen = (int) u32(data, cursor + 2);
                cursor += 6;
                if (encLen == 0) {
                    mdatCursor += clear;
                    continue;
                }
                decryptRange(data, key, iv, mdatCursor + clear, encLen);
                mdatCursor += clear + encLen;
                total += encLen;
            }
            if (!hasSubsample) {
                throw new IOException("非 subsample senc 未支持");
            }
            if (mdatCursor > mdatEnd) throw new IOException("解密越过 mdat 边界");
        }
        return total;
    }

    private static void decryptRange(byte[] data, byte[] key, byte[] blockIv, int offset, int len)
            throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(blockIv));
            cipher.doFinal(data, offset, len, data, offset);
        } catch (Exception general) {
            if (general instanceof IOException io) throw io;
            throw new IOException("AES-CTR 解密失败", general);
        }
    }

    private static void surgeryMoov(byte[] data, int moofStart, int moofEnd) {
        for (BoxView box : topLevel(data, moofStart, moofEnd)) {
            if (box.type().equals("trak")) surgeryTrak(data, box.bodyStart(), box.end());
        }
    }

    private static void surgeryTrak(byte[] data, int start, int end) {
        for (BoxView box : topLevel(data, start, end)) {
            if (box.type().equals("mdia")) {
                for (BoxView inner : topLevel(data, box.bodyStart(), box.end())) {
                    if (inner.type().equals("minf")) {
                        for (BoxView deep : topLevel(data, inner.bodyStart(), inner.end())) {
                            if (deep.type().equals("stbl")) {
                                for (BoxView leaf : topLevel(data, deep.bodyStart(), deep.end())) {
                                    if (leaf.type().equals("stsd")) {
                                        surgeryStsd(data, leaf.bodyStart() + 8, leaf.end());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void surgeryStsd(byte[] data, int start, int end) {
        int scan = start;
        while (scan + 8 <= end) {
            long size = u32(data, scan);
            String type = typeAt(data, scan);
            if (size < 8) break;
            int boxEnd = (int) Math.min(scan + size, end);
            if (type.equals("encv")) {
                setType(data, scan, "mp4a");
                int probe = scan + 16;
                while (probe + 8 <= boxEnd) {
                    if (data[probe + 4] == 's' && data[probe + 5] == 'i'
                            && data[probe + 6] == 'n' && data[probe + 7] == 'f') {
                        setType(data, probe, "free");
                        break;
                    }
                    probe++;
                }
            }
            scan = boxEnd;
        }
    }

    private static BoxView firstChild(byte[] data, BoxView parent, String type) {
        for (BoxView box : topLevel(data, parent.bodyStart(), parent.end())) {
            if (box.type().equals(type)) return box;
        }
        return null;
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
        for (int i = 0; i < 4; i++) {
            d[pos + 4 + i] = (byte) newType.charAt(i);
        }
    }
}
