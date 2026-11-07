package com.muoniumplayer.core.preset.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Legacy MPP1: base64url(zlib(JSON)) and an eight-digit CRC32. */
public final class ShareCode {
    public static final String PREFIX = "MPP1:";
    public static final String SHORT_PREFIX = "MPP1R:";
    public static final String HEADER = "【MuoniumPlayer 模板预设】";
    private ShareCode() {}
    public static String encode(String compactJson) {
        if (compactJson == null) throw new IllegalArgumentException("预设内容为空");
        byte[] raw = compactJson.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw); deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) { int n = deflater.deflate(buffer); out.write(buffer, 0, n); }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray()) + "." + crcHex(raw);
        } finally { deflater.end(); }
    }
    public static String withHeader(String code, String name, String author) {
        StringBuilder header = new StringBuilder(HEADER);
        if (name != null && !name.isEmpty()) header.append(name);
        if (author != null && !author.isEmpty()) header.append(" · by ").append(author);
        return header.append('\n').append(code).toString();
    }
    public static boolean looksLikePreset(String text) {
        return text != null && (text.contains(PREFIX) || text.contains("MPP2:") || text.contains(SHORT_PREFIX)
                || text.contains("\"" + PresetJson.FORMAT + "\""));
    }
    public static String decode(String text) throws PresetFormatException {
        if (text == null || text.trim().isEmpty()) throw new PresetFormatException("剪贴板是空的");
        if (text.length() > PresetLimits.MAX_SHARE_TEXT_CHARS) throw new PresetFormatException("剪贴板里的内容过长，不像是分享码");
        int at = text.indexOf(PREFIX);
        if (at < 0) {
            if (text.contains(SHORT_PREFIX)) throw new PresetFormatException("这是在线短码，当前版本还不支持，请让对方发完整分享码");
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) return trimmed;
            throw new PresetFormatException("剪贴板里没有找到模板预设的分享码（以 MPP2: 开头）");
        }
        StringBuilder payload = new StringBuilder();
        String crc = null;
        for (int i = at + PREFIX.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (isBase64Url(c)) { payload.append(c); continue; }
            if (c == '.') {
                StringBuilder hex = new StringBuilder(8);
                for (int j = i + 1; j < text.length() && hex.length() < 8; j++) {
                    char h = text.charAt(j);
                    if (Character.isWhitespace(h)) continue;
                    if (Character.digit(h, 16) < 0) break;
                    hex.append(h);
                }
                crc = hex.toString();
            }
            break;
        }
        if (payload.length() == 0) throw new PresetFormatException("分享码是空的");
        if (crc == null || crc.length() != 8) throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
        byte[] compressed;
        try { compressed = Base64.getUrlDecoder().decode(payload.toString()); }
        catch (IllegalArgumentException failure) { throw new PresetFormatException("分享码里有无法识别的字符", failure); }
        byte[] raw = inflate(compressed);
        if (!crcHex(raw).equalsIgnoreCase(crc)) throw new PresetFormatException("分享码校验失败（可能被改动或复制不完整）");
        return new String(raw, StandardCharsets.UTF_8);
    }
    private static byte[] inflate(byte[] compressed) throws PresetFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(8192, Math.max(256, compressed.length * 3)));
            byte[] buffer = new byte[8192];
            int stalls = 0;
            while (!inflater.finished()) {
                int n;
                try { n = inflater.inflate(buffer); }
                catch (DataFormatException failure) { throw new PresetFormatException("分享码已损坏，无法解开", failure); }
                if (n == 0) {
                    if (inflater.finished()) break;
                    if (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 8)
                        throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
                    continue;
                }
                stalls = 0;
                if (out.size() + n > PresetLimits.MAX_INFLATED_BYTES) throw new PresetFormatException("分享码解开后过大，已拒绝读取");
                out.write(buffer, 0, n);
            }
            if (inflater.getRemaining() != 0) throw new PresetFormatException("分享码内容损坏（多余的压缩数据）");
            return out.toByteArray();
        } finally { inflater.end(); }
    }
    private static boolean isBase64Url(char c) { return Base62.isDigit(c) || c == '-' || c == '_'; }
    private static String crcHex(byte[] data) {
        CRC32 crc = new CRC32(); crc.update(data, 0, data.length);
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }
}
