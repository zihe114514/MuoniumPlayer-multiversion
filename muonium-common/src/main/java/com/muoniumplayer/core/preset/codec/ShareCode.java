package com.muoniumplayer.core.preset.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 分享码 v1：{@code MPP1:<base64url(zlib(紧凑 JSON))>.<CRC32 八位十六进制>}。
 *
 * <ul>
 *   <li><b>能在聊天里活下来</b>：base64url 只有字母数字与 {@code -_}；解析时跳过中间的空白（有的聊天软件会
 *       折行），结尾的 {@code .} + 八位校验既是终止符也是截断检测，后面再跟什么聊天内容都不影响。</li>
 *   <li><b>不信任输入</b>：先量长度，解压时设上限（防压缩炸弹），最后核对 CRC，任何一步失败都给一句
 *       玩家看得懂的原因。</li>
 *   <li>前缀 {@code MPP1R:} 预留给将来的在线短码（MC 聊天 256 字符放不下完整分享码）。</li>
 * </ul>
 */
public final class ShareCode {

    public static final String PREFIX = "MPP1:";
    /** 预留：在线短码。本版本识别到只提示「需要更新」。 */
    public static final String SHORT_PREFIX = "MPP1R:";
    /** 复制到剪贴板时加在分享码上面的那一行可读头。 */
    public static final String HEADER = "【MuoniumPlayer 模板预设】";

    private ShareCode() {
    }

    /** 把紧凑 JSON 编成分享码（不带可读头）。 */
    public static String encode(String compactJson) {
        byte[] raw = compactJson.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
            return PREFIX + payload + "." + crcHex(raw);
        } finally {
            deflater.end();
        }
    }

    /** 带一行可读头：聊天里一眼看得出这是什么、谁做的。 */
    public static String withHeader(String code, String name, String author) {
        StringBuilder header = new StringBuilder(HEADER);
        if (name != null && !name.isEmpty()) {
            header.append(name);
        }
        if (author != null && !author.isEmpty()) {
            header.append(" · by ").append(author);
        }
        return header.append('\n').append(code).toString();
    }

    /** 这段文字里看起来有没有分享码（v1 / v2）或预设 JSON（只做便宜的判断，给按钮状态用）。 */
    public static boolean looksLikePreset(String text) {
        if (text == null) {
            return false;
        }
        return text.contains(PREFIX) || text.contains("MPP2:") || text.contains(SHORT_PREFIX)
                || text.contains("\"" + PresetJson.FORMAT + "\"");
    }

    /**
     * 从一段文字里取出预设 JSON 文本：可以是带头带尾的分享码，也可以直接是预设 JSON。
     *
     * @throws PresetFormatException 带一句给玩家看的原因
     */
    public static String decode(String text) throws PresetFormatException {
        if (text == null || text.trim().isEmpty()) {
            throw new PresetFormatException("剪贴板是空的");
        }
        if (text.length() > PresetLimits.MAX_SHARE_TEXT_CHARS) {
            throw new PresetFormatException("剪贴板里的内容过长，不像是分享码");
        }
        int at = text.indexOf(PREFIX);
        if (at < 0) {
            if (text.contains(SHORT_PREFIX)) {
                throw new PresetFormatException("这是在线短码，当前版本还不支持，请让对方发完整分享码");
            }
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                return trimmed;
            }
            throw new PresetFormatException("剪贴板里没有找到模板预设的分享码（以 MPP2: 开头）");
        }

        StringBuilder payload = new StringBuilder();
        String crc = null;
        int i = at + PREFIX.length();
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (isBase64Url(c)) {
                payload.append(c);
                continue;
            }
            if (c == '.') {
                StringBuilder hex = new StringBuilder(8);
                for (int j = i + 1; j < text.length() && hex.length() < 8; j++) {
                    char h = text.charAt(j);
                    if (Character.isWhitespace(h)) {
                        continue;
                    }
                    if (Character.digit(h, 16) < 0) {
                        break;
                    }
                    hex.append(h);
                }
                crc = hex.toString();
            }
            break;
        }
        if (payload.length() == 0) {
            throw new PresetFormatException("分享码是空的");
        }
        if (crc == null || crc.length() != 8) {
            throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
        }

        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(payload.toString());
        } catch (IllegalArgumentException failure) {
            throw new PresetFormatException("分享码里有无法识别的字符", failure);
        }
        byte[] raw = inflate(compressed);
        if (!crcHex(raw).equalsIgnoreCase(crc)) {
            throw new PresetFormatException("分享码校验失败（可能被改动或复制不完整）");
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static byte[] inflate(byte[] compressed) throws PresetFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, compressed.length * 3));
            byte[] buffer = new byte[8192];
            int stalls = 0;
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(buffer);
                } catch (DataFormatException failure) {
                    throw new PresetFormatException("分享码已损坏，无法解开", failure);
                }
                if (n == 0) {
                    if (inflater.finished()) {
                        break;
                    }
                    // 没有进展：缺输入就是截断；连续几次既不缺输入也不出数据，也当损坏处理，绝不空转。
                    if (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 8) {
                        throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
                    }
                    continue;
                }
                stalls = 0;
                if (out.size() + n > PresetLimits.MAX_INFLATED_BYTES) {
                    throw new PresetFormatException("分享码解开后过大，已拒绝读取");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static boolean isBase64Url(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    private static String crcHex(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }
}
