package com.muoniumplayer.core.ncm.kugou;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 酷狗写操作用到的 AES/RSA 组合。
 *
 * <p>只有「删除歌单」一条链路需要它：上游要求请求体是 AES 密文，并在查询串里带一个
 * RSA 加密的密钥信封 {@code p}。其余接口要么明文 JSON，要么只签名。</p>
 *
 * <h3>密钥派生</h3>
 * <p>随机 6 位小写串作为 {@code key}，它的 MD5 前 16 个十六进制字符当 AES 密钥、
 * 后 16 个当 IV。<b>密钥与 IV 都是 16 字节的 ASCII</b>，不是把 MD5 当二进制用——
 * 这一点弄错会得到一段「长度正确但解不开」的密文。</p>
 *
 * <h3>为什么单独一个类</h3>
 * <p>这套东西和签名算法一样属于「协议事实、写错不报错只是失败」。集中在这里，
 * 配合 {@link KuGouSign} 的单元测试，改动的影响面才看得见。</p>
 */
final class KuGouCrypto {

    /**
     * 标准版客户端的 RSA 公钥（1024 位，X.509 SubjectPublicKeyInfo 的 base64）。
     *
     * <p>用于歌单删除的密钥信封 {@code p}。CSCC 上报走的是另一把 {@link #LITE_PUBLIC_KEY_BASE64}，
     * 两把不能互换——用错只是服务端解不开，加密本身不会报错。</p>
     */
    private static final String PUBLIC_KEY_BASE64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB";

    /**
     * 概念版（lite）公钥，CSCC 听歌上报的 {@code gen} 步骤用它。
     *
     * <p>名字里带 lite 但这里的用途固定是 CSCC，与「本项目只做标准版」不冲突：
     * 上报链路本身就走这个概念版密钥，换标准版那把会解不出会话。</p>
     */
    private static final String LITE_PUBLIC_KEY_BASE64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB";

    private static final String ALPHABET = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final SecureRandom RANDOM = new SecureRandom();

    private KuGouCrypto() {
    }

    /** 一次 AES 加密的结果：明文密钥（要交给 RSA 包起来）与 base64 密文（当请求体）。 */
    static final class AesPayload {
        final String key;
        final String str;

        AesPayload(String key, String str) {
            this.key = key;
            this.str = str;
        }
    }

    /**
     * 加密歌单操作的请求体。
     *
     * @param plain 明文 JSON
     * @return 永不为 {@code null}；加密失败返回明文密钥与空密文，调用方应判空后放弃
     */
    static AesPayload playlistAesEncrypt(String plain) {
        String key = randomKey();
        try {
            String digest = KuGouSign.md5(key);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(digest.substring(0, 16).getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(digest.substring(16, 32).getBytes(StandardCharsets.UTF_8)));
            byte[] encrypted = cipher.doFinal(KuGouSign.nonNull(plain).getBytes(StandardCharsets.UTF_8));
            return new AesPayload(key, Base64.getEncoder().encodeToString(encrypted));
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] AES 加密失败: " + throwable.getMessage());
            return new AesPayload(key, "");
        }
    }

    /** 解密歌单操作的响应。失败返回空串（调用方按「上游拒绝了」处理）。 */
    static String playlistAesDecrypt(String base64, String key) {
        if (KuGouSign.isBlank(base64) || KuGouSign.isBlank(key)) return "";
        String trimmed = base64.trim();
        // 偶发：上游返回明文 JSON（例如未登录时直接给错误对象）。
        if (trimmed.startsWith("{")) return trimmed;
        try {
            String digest = KuGouSign.md5(key);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(digest.substring(0, 16).getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(digest.substring(16, 32).getBytes(StandardCharsets.UTF_8)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(trimmed)), StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            return "";
        }
    }

    /**
     * RSA 加密（PKCS#1 v1.5），返回<b>小写十六进制</b>。
     *
     * <p>{@code RSA/ECB/PKCS1Padding} 对应上游的 {@code RSAES-PKCS1-V1_5}。
     * 不要换成 OAEP：填充不匹配时加密本身不会失败，只有服务端解不开，
     * 表现为一个语焉不详的 {@code 20006}。</p>
     *
     * <p>调用方负责转大写——上游的 {@code p} 参数是大写十六进制，而 Java 输出的默认是小写。</p>
     */
    static String rsaEncrypt(String plain) {
        return rsaEncrypt(plain, PUBLIC_KEY_BASE64);
    }

    /**
     * 用概念版公钥做 RSA 加密，返回<b>base64</b>（CSCC 的 {@code gen} 要求 base64 而不是十六进制）。
     */
    static String rsaEncryptLiteBase64(String plain) {
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(
                    Base64.getDecoder().decode(LITE_PUBLIC_KEY_BASE64));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(spec);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(KuGouSign.nonNull(plain).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 概念版 RSA 加密失败: " + throwable.getMessage());
            return "";
        }
    }

    /** 用指定公钥（base64）做 RSA/PKCS1 加密，返回小写十六进制。 */
    private static String rsaEncrypt(String plain, String publicKeyBase64) {
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(spec);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(KuGouSign.nonNull(plain).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(encrypted.length * 2);
            for (byte value : encrypted) {
                String part = Integer.toHexString(value & 0xFF);
                if (part.length() == 1) hex.append('0');
                hex.append(part);
            }
            return hex.toString();
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] RSA 加密失败: " + throwable.getMessage());
            return "";
        }
    }

    /**
     * AES-256-CBC 加密，密钥与 IV 都取 16 字符的 ASCII。
     *
     * <p>CSCC 的每一层都用它：会话响应用 {@code field2} 解，报文体用
     * {@code MD5(uuid+clienttime+field2+serverstr)} 解。</p>
     */
    static byte[] aesEncrypt(byte[] plain, String key16, String iv16) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key16.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv16.getBytes(StandardCharsets.UTF_8)));
            return cipher.doFinal(plain);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] AES-256 加密失败: " + throwable.getMessage());
            return null;
        }
    }

    /** {@link #aesEncrypt} 的逆操作，失败返回 {@code null}。 */
    static byte[] aesDecrypt(byte[] encrypted, String key16, String iv16) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key16.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv16.getBytes(StandardCharsets.UTF_8)));
            return cipher.doFinal(encrypted);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 6 位小写随机密钥，与上游 {@code util.js#randomString(6).toLowerCase()} 同构。 */
    private static String randomKey() {
        StringBuilder result = new StringBuilder(6);
        for (int index = 0; index < 6; index++) {
            result.append(Character.toLowerCase(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))));
        }
        return result.toString();
    }
}
