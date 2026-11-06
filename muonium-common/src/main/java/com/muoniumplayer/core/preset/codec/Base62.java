package com.muoniumplayer.core.preset.codec;

import java.math.BigInteger;

/**
 * base62（0-9 A-Z a-z）：只有字母数字，聊天软件不会把它拆开、转义或识别成链接。
 * 密度和 base64 几乎一样（每字符 5.95 位），又没有 {@code + / = - _} 这些容易被截断或转义的符号。
 *
 * <p>前导的零字节按「一个 '0' 一个零字节」单独记，与 base58 同一做法，往返不丢字节。</p>
 */
public final class Base62 {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(62);

    private Base62() {
    }

    public static String encode(byte[] bytes) {
        int zeros = 0;
        while (zeros < bytes.length && bytes[zeros] == 0) {
            zeros++;
        }
        StringBuilder out = new StringBuilder(bytes.length * 4 / 3 + 2);
        BigInteger value = new BigInteger(1, bytes);
        while (value.signum() > 0) {
            BigInteger[] division = value.divideAndRemainder(BASE);
            out.append(ALPHABET.charAt(division[1].intValue()));
            value = division[0];
        }
        for (int i = 0; i < zeros; i++) {
            out.append('0');
        }
        return out.reverse().toString();
    }

    /** @throws IllegalArgumentException 有不属于字母表的字符 */
    public static byte[] decode(String text) {
        int zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == '0') {
            zeros++;
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = zeros; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("非 base62 字符：" + text.charAt(i));
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
        int skip = magnitude.length > 0 && magnitude[0] == 0 ? 1 : 0; // 去掉 BigInteger 的符号位字节
        byte[] out = new byte[zeros + magnitude.length - skip];
        System.arraycopy(magnitude, skip, out, zeros, magnitude.length - skip);
        return out;
    }

    public static boolean isDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
