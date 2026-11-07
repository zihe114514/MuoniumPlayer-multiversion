package com.muoniumplayer.core.preset.codec;

import java.math.BigInteger;

/** MPP2 base62 alphabet; leading zero bytes are preserved. */
public final class Base62 {
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(62);
    private Base62() {}
    public static String encode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        int zeros = 0;
        while (zeros < bytes.length && bytes[zeros] == 0) zeros++;
        StringBuilder out = new StringBuilder(bytes.length * 4 / 3 + 2);
        BigInteger value = new BigInteger(1, bytes);
        while (value.signum() > 0) {
            BigInteger[] division = value.divideAndRemainder(BASE);
            out.append(ALPHABET.charAt(division[1].intValue()));
            value = division[0];
        }
        for (int i = 0; i < zeros; i++) out.append('0');
        return out.reverse().toString();
    }
    public static byte[] decode(String text) {
        int zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == '0') zeros++;
        BigInteger value = BigInteger.ZERO;
        for (int i = zeros; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("非 base62 字符：" + text.charAt(i));
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
        int skip = magnitude.length > 0 && magnitude[0] == 0 ? 1 : 0;
        byte[] out = new byte[zeros + magnitude.length - skip];
        System.arraycopy(magnitude, skip, out, zeros, magnitude.length - skip);
        return out;
    }
    public static boolean isDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
