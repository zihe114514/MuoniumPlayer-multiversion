package com.muoniumplayer.core.music.source;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 weapi 加密。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）；
 * 输出已对拍原 jar 黄金基准（.scratch/cadence-absorb/golden/golden.json）。
 */
public final class MuWeapi {
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final BigInteger RSA_MODULUS = new BigInteger(
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7",
            16);
    private static final BigInteger RSA_EXPONENT = new BigInteger("010001", 16);
    private static final SecureRandom RANDOM = new SecureRandom();

    private MuWeapi() {
    }

    /** weapi 加密结果：{@code params}（二次 AES 后 Base64）与 {@code encSecKey}（RSA 后 hex）。 */
    public static final class Encrypted {
        private final String params;
        private final String encSecKey;

        public Encrypted(String params, String encSecKey) {
            this.params = params;
            this.encSecKey = encSecKey;
        }

        public String getParams() { return params; }

        public String getEncSecKey() { return encSecKey; }
    }

    /** 随机 secKey 的 weapi 加密。 */
    public static Encrypted weapi(String plain) {
        return weapi(plain, randomKey(16));
    }

    /** 指定 16 字节 secKey 的 weapi 加密（黄金基准对拍入口）。 */
    public static Encrypted weapi(String plain, String secKeyOverride) {
        String secKey = secKeyOverride != null ? secKeyOverride : randomKey(16);
        String first = aesCbcBase64(plain, PRESET_KEY);
        String params = aesCbcBase64(first, secKey);
        String encSecKey = rsaEncrypt(secKey);
        return new Encrypted(params, encSecKey);
    }

    private static String aesCbcBase64(String text, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("weapi AES 失败", e);
        }
    }

    private static String rsaEncrypt(String text) {
        byte[] reversed = new StringBuilder(text).reverse().toString().getBytes(StandardCharsets.UTF_8);
        BigInteger cipher = new BigInteger(1, reversed).modPow(RSA_EXPONENT, RSA_MODULUS);
        String hex = cipher.toString(16);
        StringBuilder padded = new StringBuilder(256);
        for (int i = hex.length(); i < 256; i++) padded.append('0');
        padded.append(hex);
        return padded.toString();
    }

    private static String randomKey(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
