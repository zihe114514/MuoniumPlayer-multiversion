package com.muoniumplayer.core.ncm.lxsource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code lx.utils} 背后的实现：{@code crypto} 与 {@code zlib} 两组。
 *
 * <p>语义按 lx-music-desktop 的 preload 对齐 —— 那边直接转发给 Node 的 {@code crypto}/{@code zlib}，
 * 所以这里要按 <b>Node 的默认值</b>来选参数，而不是按 Java 的习惯：AES 的 CBC/ECB 在 Node 里默认
 * 补 PKCS7（等于 Java 的 PKCS5Padding），RSA 那条走的是 {@code RSA_NO_PADDING} 且明文要先左补零
 * 到 128 字节。任何一处按 Java 习惯来都能跑通，但算出来的密文对不上上游接口的签名。</p>
 */
public final class LxUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private LxUtils() {
    }

    // ════════════════════════════ crypto ════════════════════════════

    public static String md5(String text) {
        return hex(digest("MD5", text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8)));
    }

    public static LxBuffer randomBytes(int size) {
        byte[] out = new byte[Math.max(0, Math.min(size, 1 << 16))];
        RANDOM.nextBytes(out);
        return LxBuffer.of(out);
    }

    /**
     * {@code aesEncrypt(buffer, mode, key, iv)}。
     *
     * @param mode Node 的算法名，例如 {@code aes-128-cbc} / {@code aes-128-ecb}
     */
    public static LxBuffer aesEncrypt(Object data, String mode, Object key, Object iv) throws Exception {
        String algorithm = mode == null ? "aes-128-cbc" : mode.trim().toLowerCase(Locale.ROOT);
        String[] parts = algorithm.split("-");
        String blockMode = parts.length >= 3 ? parts[2] : "cbc";
        int keyBits = parts.length >= 2 ? parseInt(parts[1], 128) : 128;

        String transformation;
        boolean needsIv = true;
        if ("ecb".equals(blockMode)) {
            transformation = "AES/ECB/PKCS5Padding";
            needsIv = false;
        } else if ("ctr".equals(blockMode)) {
            transformation = "AES/CTR/NoPadding";
        } else if ("cfb".equals(blockMode)) {
            transformation = "AES/CFB/NoPadding";
        } else if ("ofb".equals(blockMode)) {
            transformation = "AES/OFB/NoPadding";
        } else {
            transformation = "AES/CBC/PKCS5Padding";
        }

        byte[] keyBytes = bytesOf(key);
        int expected = keyBits / 8;
        if (keyBytes.length != expected) {
            // Node 在长度不符时直接抛 "Invalid key length"，这里跟着抛：静默截断会算出
            // 一串看起来正常、上游却拒绝的密文，比报错难查得多。
            throw new IllegalArgumentException("Invalid key length: " + keyBytes.length + " (need " + expected + ")");
        }
        Cipher cipher = Cipher.getInstance(transformation);
        SecretKeySpec secret = new SecretKeySpec(keyBytes, "AES");
        if (needsIv) {
            byte[] ivBytes = bytesOf(iv);
            if (ivBytes.length != 16) {
                byte[] padded = new byte[16];
                System.arraycopy(ivBytes, 0, padded, 0, Math.min(16, ivBytes.length));
                ivBytes = padded;
            }
            cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec(ivBytes));
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, secret);
        }
        return LxBuffer.of(cipher.doFinal(bytesOf(data)));
    }

    /**
     * {@code rsaEncrypt(buffer, publicKeyPem)}。
     *
     * <p>左补零到 128 字节 + 无填充，这两点都不是可选项：网易云的 linuxapi/eapi 脚本就是按
     * 上游那一行 {@code Buffer.concat([Buffer.alloc(128 - buffer.length), buffer])} 写的。</p>
     */
    public static LxBuffer rsaEncrypt(Object data, String publicKeyPem) throws Exception {
        byte[] plain = bytesOf(data);
        if (plain.length < 128) {
            byte[] padded = new byte[128];
            System.arraycopy(plain, 0, padded, 128 - plain.length, plain.length);
            plain = padded;
        }
        String base64 = publicKeyPem == null ? "" : publicKeyPem
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, factory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(base64))));
        return LxBuffer.of(cipher.doFinal(plain));
    }

    // ════════════════════════════ zlib ════════════════════════════

    public static LxBuffer inflate(Object data) throws Exception {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytesOf(data));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int read = inflater.inflate(chunk);
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                    continue;
                }
                out.write(chunk, 0, read);
            }
            return LxBuffer.of(out.toByteArray());
        } finally {
            inflater.end();
        }
    }

    public static LxBuffer deflate(Object data) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(bytesOf(data));
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int written = deflater.deflate(chunk);
                if (written <= 0) break;
                out.write(chunk, 0, written);
            }
            return LxBuffer.of(out.toByteArray());
        } finally {
            deflater.end();
        }
    }

    // ════════════════════════════ 内部 ════════════════════════════

    /** 脚本传进来的可能是 Buffer、字符串或 JS 数组，统一取字节。 */
    static byte[] bytesOf(Object value) {
        if (value == null) return new byte[0];
        if (value instanceof LxBuffer) return ((LxBuffer) value).getBytes();
        if (value instanceof byte[]) return (byte[]) value;
        return LxBuffer.from(value, "utf8").getBytes();
    }

    static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (Exception impossible) {
            throw new IllegalStateException(algorithm + " unavailable", impossible);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}