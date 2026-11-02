package com.muoniumplayer.core.music.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * QQ musicu.fcg 请求签名（zzc 签名）。自 Cadence（原 music-api-0.1.1.jar，原 music-api jar 包）
 * 手翻，2026-11 吸收进仓；输出已对拍原 jar 黄金基准（.scratch/cadence-absorb/golden/golden.json）。
 */
public final class MuSign {
    private static final int[] PART1_INDEXES = {23, 14, 6, 36, 16, 7, 19};
    private static final int[] PART2_INDEXES = {16, 1, 32, 12, 19, 27, 8, 5};
    private static final int[] SCRAMBLE = {89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123,
            120, 64, 242, 133, 143, 161, 121, 179};

    private MuSign() {
    }

    public static String sign(String payload) {
        String hashHex;
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sha1.length * 2);
            for (byte b : sha1) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            hashHex = hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }

        StringBuilder part1 = new StringBuilder();
        for (int i : PART1_INDEXES) part1.append(hashHex.charAt(i));
        StringBuilder part2 = new StringBuilder();
        for (int i : PART2_INDEXES) part2.append(hashHex.charAt(i));

        byte[] part3 = new byte[SCRAMBLE.length];
        for (int i = 0; i < SCRAMBLE.length; i++) {
            int b = Integer.parseInt(hashHex.substring(i * 2, i * 2 + 2), 16);
            part3[i] = (byte) (SCRAMBLE[i] ^ b);
        }
        // Base64 里的 \ / + = 全部剔除（与上游 Regex("[\\\\/+=]") 语义一致）
        String b64 = Base64.getEncoder().encodeToString(part3).replaceAll("[\\\\/+=]", "");

        return ("zzc" + part1 + b64 + part2).toLowerCase(Locale.ROOT);
    }
}
