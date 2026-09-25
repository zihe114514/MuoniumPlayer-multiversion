/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.muoniumplayer.core.ncm.math;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;


public class Hex implements BinaryEncoder, BinaryDecoder {

    /**
     * 默认字符集为 {@link StandardCharsets#UTF_8}。
     *
     * @since 1.7
     */
    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    /**
     * 用于把输出构建为十六进制。
     */
    private static final char[] DIGITS_LOWER = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    /**
     * 用于把输出构建为十六进制。
     */
    private static final char[] DIGITS_UPPER = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

    /**
     * 把表示十六进制值的字符数组转换为具有相同值的字节数组。
     * 返回数组长度为传入数组的一半，因为表示每个字节需要两个字符。
     * 如果传入的字符数组元素数为奇数，则抛出异常。
     *
     * @param data 包含十六进制数字的字符数组
     * @return 从给定字符数组解码出的、包含二进制数据的字节数组。
     * @throws DecoderException 当传入奇数个字符或非法字符时抛出
     */
    public static byte[] decodeHex(final char[] data) throws DecoderException {
        final byte[] out = new byte[data.length >> 1];
        decodeHex(data, out, 0);
        return out;
    }

    /**
     * 把表示十六进制值的字符数组转换为具有相同值的字节数组。
     * 返回数组长度为传入数组的一半，因为表示每个字节需要两个字符。
     * 如果传入的字符数组元素数为奇数，则抛出异常。
     *
     * @param data 包含十六进制数字的字符数组
     * @param out 用于存放从给定字符数组解码出的二进制数据的字节数组。
     * @param outOffset 在 {@code out} 中开始写入解码字节的位置。
     * @return 写入 {@code out} 的字节数。
     * @throws DecoderException 当传入奇数个字符或非法字符时抛出
     * @since 1.15
     */
    public static int decodeHex(final char[] data, final byte[] out, final int outOffset) throws DecoderException {
        final int len = data.length;
        if ((len & 0x01) != 0) {
            throw new DecoderException("Odd number of characters.");
        }
        final int outLen = len >> 1;
        if (out.length - outOffset < outLen) {
            throw new DecoderException("Output array is not large enough to accommodate decoded data.");
        }
        // 两个字符构成十六进制值。
        for (int i = outOffset, j = 0; j < len; i++) {
            int f = toDigit(data[j], j) << 4;
            j++;
            f |= toDigit(data[j], j);
            j++;
            out[i] = (byte) (f & 0xFF);
        }
        return outLen;
    }

    /**
     * 把表示十六进制值的 String 转换为具有相同值的字节数组。返回数组
     * 长度为传入 String 的一半，因为表示每个字节需要两个字符。
     * 如果传入的 String 元素数为奇数，则抛出异常。
     *
     * @param data 包含十六进制数字的 String
     * @return 从给定字符序列解码出的、包含二进制数据的字节数组。
     * @throws DecoderException 当传入奇数个字符或非法字符时抛出
     * @since 1.11
     */
    public static byte[] decodeHex(final String data) throws DecoderException {
        return decodeHex(data.toCharArray());
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的字符数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符。
     * 
     *
     * @param data 待转换为十六进制字符的 byte[]
     * @return 包含小写十六进制字符的 char[]
     */
    public static char[] encodeHex(final byte[] data) {
        return encodeHex(data, true);
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的字符数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符。
     * 
     *
     * @param data        待转换为十六进制字符的 byte[]
     * @param toLowerCase {@code true} 转小写，{@code false} 转大写
     * @return 按所选大小写包含十六进制字符的 char[]
     * @since 1.4
     */
    public static char[] encodeHex(final byte[] data, final boolean toLowerCase) {
        return encodeHex(data, toAlphabet(toLowerCase));
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的字符数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符。
     * 
     *
     * @param data     待转换为十六进制字符的 byte[]
     * @param toDigits 输出字母表（至少须含 16 个字符）
     * @return 从字母表中选取相应字符构成的 char[]。为获得最佳效果，应使用
     *         大写或小写的十六进制字母表。
     * @since 1.4
     */
    protected static char[] encodeHex(final byte[] data, final char[] toDigits) {
        final int dataLength = data.length;
        return encodeHex(data, 0, dataLength, toDigits, new char[dataLength << 1], 0);
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的字符数组。
     *
     * @param data 待转换为十六进制字符的 byte[]
     * @param dataOffset 在 {@code data} 中开始编码的位置
     * @param dataLen 从 {@code dataOffset} 起要编码的字节数
     * @param toLowerCase {@code true} 转小写，{@code false} 转大写
     * @return 从字母表中选取相应字符构成的 char[]。为获得最佳效果，应使用
     *         大写或小写的十六进制字母表。
     * @since 1.15
     */
    public static char[] encodeHex(final byte[] data, final int dataOffset, final int dataLen, final boolean toLowerCase) {
        return encodeHex(data, dataOffset, dataLen, toAlphabet(toLowerCase), new char[dataLen << 1], 0);
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的字符数组。
     *
     * @param data 待转换为十六进制字符的 byte[]
     * @param dataOffset 在 {@code data} 中开始编码的位置
     * @param dataLen 从 {@code dataOffset} 起要编码的字节数
     * @param toLowerCase {@code true} 转小写，{@code false} 转大写
     * @param out 用于存放从字母表中选出的相应字符的 char[]。
     * @param outOffset 在 {@code out} 中开始写入编码字符的位置。
     * @since 1.15
     */
    public static void encodeHex(final byte[] data, final int dataOffset, final int dataLen, final boolean toLowerCase, final char[] out, final int outOffset) {
        encodeHex(data, dataOffset, dataLen, toAlphabet(toLowerCase), out, outOffset);
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的字符数组。
     *
     * @param data 待转换为十六进制字符的 byte[]
     * @param dataOffset 在 {@code data} 中开始编码的位置
     * @param dataLen 从 {@code dataOffset} 起要编码的字节数
     * @param toDigits 输出字母表（至少须含 16 个字符）
     * @param out 用于存放从字母表中选出的相应字符的 char[]。
     * @param outOffset 在 {@code out} 中开始写入编码字符的位置。
     * @return 传入的 {@code out}。
     */
    private static char[] encodeHex(final byte[] data, final int dataOffset, final int dataLen, final char[] toDigits, final char[] out, final int outOffset) {
        // 两个字符构成十六进制值。
        for (int i = dataOffset, j = outOffset; i < dataOffset + dataLen; i++) {
            out[j++] = toDigits[(0xF0 & data[i]) >>> 4];
            out[j++] = toDigits[0x0F & data[i]];
        }
        return out;
    }

    /**
     * 把字节缓冲区按顺序转换为表示每个字节十六进制值的字符数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符。
     * 
     *
     * <p>将使用 {@link ByteBuffer#remaining()} 标识的全部字节；调用本方法后，
     * {@link ByteBuffer#remaining() remaining()} 的值将为零。</p>
     *
     * @param data 待转换为十六进制字符的字节缓冲区
     * @return 包含小写十六进制字符的 char[]
     * @since 1.11
     */
    public static char[] encodeHex(final ByteBuffer data) {
        return encodeHex(data, true);
    }

    /**
     * 把字节缓冲区按顺序转换为表示每个字节十六进制值的字符数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符。
     * 
     *
     * <p>将使用 {@link ByteBuffer#remaining()} 标识的全部字节；调用本方法后，
     * {@link ByteBuffer#remaining() remaining()} 的值将为零。</p>
     *
     * @param data        待转换为十六进制字符的字节缓冲区
     * @param toLowerCase {@code true} 转小写，{@code false} 转大写
     * @return 按所选大小写包含十六进制字符的 char[]
     * @since 1.11
     */
    public static char[] encodeHex(final ByteBuffer data, final boolean toLowerCase) {
        return encodeHex(data, toAlphabet(toLowerCase));
    }

    /**
     * 把字节缓冲区按顺序转换为表示每个字节十六进制值的字符数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符。
     * 
     *
     * <p>将使用 {@link ByteBuffer#remaining()} 标识的全部字节；调用本方法后，
     * {@link ByteBuffer#remaining() remaining()} 的值将为零。</p>
     *
     * @param byteBuffer 待转换为十六进制字符的字节缓冲区
     * @param toDigits   输出字母表（至少须含 16 个字符）
     * @return 从字母表中选取相应字符构成的 char[]。为获得最佳效果，应使用
     *         大写或小写的十六进制字母表。
     * @since 1.11
     */
    protected static char[] encodeHex(final ByteBuffer byteBuffer, final char[] toDigits) {
        return encodeHex(toByteArray(byteBuffer), toDigits);
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的 String。返回的
     * String 长度为传入数组的两倍，因为表示每个字节需要两个字符。
     *
     * @param data 待转换为十六进制字符的 byte[]
     * @return 包含小写十六进制字符的 String
     * @since 1.4
     */
    public static String encodeHexString(final byte[] data) {
        return new String(encodeHex(data));
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的 String。返回的
     * String 长度为传入数组的两倍，因为表示每个字节需要两个字符。
     *
     * @param data        待转换为十六进制字符的 byte[]
     * @param toLowerCase {@code true} 转小写，{@code false} 转大写
     * @return 按所选大小写包含十六进制字符的 String
     * @since 1.11
     */
    public static String encodeHexString(final byte[] data, final boolean toLowerCase) {
        return new String(encodeHex(data, toLowerCase));
    }

    /**
     * 把字节缓冲区按顺序转换为表示每个字节十六进制值的 String。返回的
     * String 长度为传入数组的两倍，因为表示每个字节需要两个字符。
     *
     * <p>将使用 {@link ByteBuffer#remaining()} 标识的全部字节；调用本方法后，
     * {@link ByteBuffer#remaining() remaining()} 的值将为零。</p>
     *
     * @param data 待转换为十六进制字符的字节缓冲区
     * @return 包含小写十六进制字符的 String
     * @since 1.11
     */
    public static String encodeHexString(final ByteBuffer data) {
        return new String(encodeHex(data));
    }

    /**
     * 把字节缓冲区按顺序转换为表示每个字节十六进制值的 String。返回的
     * String 长度为传入数组的两倍，因为表示每个字节需要两个字符。
     *
     * <p>将使用 {@link ByteBuffer#remaining()} 标识的全部字节；调用本方法后，
     * {@link ByteBuffer#remaining() remaining()} 的值将为零。</p>
     *
     * @param data        待转换为十六进制字符的字节缓冲区
     * @param toLowerCase {@code true} 转小写，{@code false} 转大写
     * @return 按所选大小写包含十六进制字符的 String
     * @since 1.11
     */
    public static String encodeHexString(final ByteBuffer data, final boolean toLowerCase) {
        return new String(encodeHex(data, toLowerCase));
    }

    /**
     * 把 boolean 转换为一个字母表。
     *
     * @param toLowerCase true 表示小写，false 表示大写。
     * @return 一个字母表。
     */
    private static char[] toAlphabet(final boolean toLowerCase) {
        return toLowerCase ? DIGITS_LOWER : DIGITS_UPPER;
    }

    /**
     * 把字节缓冲区转换为字节数组。将使用
     * {@link ByteBuffer#remaining()} 标识的全部字节。
     *
     * @param byteBuffer 字节缓冲区
     * @return 对应的 byte[]
     */
    private static byte[] toByteArray(final ByteBuffer byteBuffer) {
        final int remaining = byteBuffer.remaining();
        // 尽可能使用底层缓冲区
        if (byteBuffer.hasArray()) {
            final byte[] byteArray = byteBuffer.array();
            if (remaining == byteArray.length) {
                byteBuffer.position(remaining);
                return byteArray;
            }
        }
        // 复制字节
        final byte[] byteArray = new byte[remaining];
        byteBuffer.get(byteArray);
        return byteArray;
    }

    /**
     * 把一个十六进制字符转换为整数。
     *
     * @param ch    待转换为整数数字的字符
     * @param index 该字符在源中的位置
     * @return 一个整数
     * @throws DecoderException 当 ch 为非法十六进制字符时抛出
     */
    protected static int toDigit(final char ch, final int index) throws DecoderException {
        final int digit = Character.digit(ch, 16);
        if (digit == -1) {
            throw new DecoderException("Illegal hexadecimal character " + ch + " at index " + index);
        }
        return digit;
    }

    private final Charset charset;

    /**
     * 使用默认字符集名 {@link #DEFAULT_CHARSET} 创建新的 codec。
     */
    public Hex() {
        // 使用默认编码
        this.charset = DEFAULT_CHARSET;
    }

    /**
     * 使用给定的 Charset 创建新的 codec。
     *
     * @param charset 字符集。
     * @since 1.7
     */
    public Hex(final Charset charset) {
        this.charset = charset;
    }

    /**
     * 使用给定的字符集名创建新的 codec。
     *
     * @param charsetName 字符集名。
     * @throws java.nio.charset.UnsupportedCharsetException 如果指定字符集不可用
     * @since 1.4
     * @since 1.7 当指定字符集不可用时抛出 UnsupportedCharsetException
     */
    public Hex(final String charsetName) {
        this(Charset.forName(charsetName));
    }

    /**
     * 把表示十六进制值的字符字节数组转换为具有相同值的字节数组。
     * 返回数组长度为传入数组的一半，因为表示每个字节需要两个字符。
     * 如果传入的数组元素数为奇数，则抛出异常。
     *
     * @param array 包含十六进制数字的字符字节数组
     * @return 从给定字节数组（表示字符）解码出的、包含二进制数据的字节数组。
     * @throws DecoderException 当向本函数传入奇数个字符时抛出
     * @see #decodeHex(char[])
     */
    @Override
    public byte[] decode(final byte[] array) throws DecoderException {
        return decodeHex(new String(array, getCharset()).toCharArray());
    }

    /**
     * 把表示十六进制值的字符字节缓冲区转换为具有相同值的字节数组。
     * 返回数组长度为传入数组的一半，因为表示每个字节需要两个字符。
     * 如果传入的数组元素数为奇数，则抛出异常。
     *
     * <p>将使用 {@link ByteBuffer#remaining()} 标识的全部字节；调用本方法后，
     * {@link ByteBuffer#remaining() remaining()} 的值将为零。</p>
     *
     * @param buffer 包含十六进制数字的字符字节缓冲区
     * @return 从给定字节数组（表示字符）解码出的、包含二进制数据的字节数组。
     * @throws DecoderException 当向本函数传入奇数个字符时抛出
     * @see #decodeHex(char[])
     * @since 1.11
     */
    public byte[] decode(final ByteBuffer buffer) throws DecoderException {
        return decodeHex(new String(toByteArray(buffer), getCharset()).toCharArray());
    }

    /**
     * 把表示十六进制值的 String 或字符字节数组转换为具有相同值的字节
     * 数组。返回数组长度为传入 String 或数组的一半，因为表示每个字节需要两个字符。
     * 如果传入的数组元素数为奇数，则抛出异常。
     *
     * @param object 一个 String、ByteBuffer、byte[]，或包含十六进制数字的字符字节数组
     * @return 从给定字节数组（表示字符）解码出的、包含二进制数据的字节数组。
     * @throws DecoderException 当向本函数传入奇数个字符，或该对象不是
     *                          String 或 char[] 时抛出
     * @see #decodeHex(char[])
     */
    @Override
    public Object decode(final Object object) throws DecoderException {
        if (object instanceof String) {
            return decode(((String) object).toCharArray());
        }
        if (object instanceof byte[]) {
            return decode((byte[]) object);
        }
        if (object instanceof ByteBuffer) {
            return decode((ByteBuffer) object);
        }
        try {
            return decodeHex((char[]) object);
        } catch (final ClassCastException e) {
            throw new DecoderException(e.getMessage(), e);
        }
    }

    /**
     * 把字节数组按顺序转换为表示每个字节十六进制值的字符所对应的字节数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符。
     * 
     * <p>
     * 从十六进制字符到返回字节的转换，使用
     * {@link #getCharset()} 指定的字符集进行。
     * </p>
     *
     * @param array 待转换为十六进制字符的 byte[]
     * @return 包含小写十六进制字符对应字节的 byte[]
     * @since 1.7 当字符集名无效时不再抛出 IllegalStateException。
     * @see #encodeHex(byte[])
     */
    @Override
    public byte[] encode(final byte[] array) {
        return encodeHexString(array).getBytes(getCharset());
    }

    /**
     * 把字节缓冲区按顺序转换为表示每个字节十六进制值的字符所对应的字节数组。
     * 返回数组长度为传入数组的两倍，因为表示每个字节需要两个字符来
     * 表示每个字节。
     *
     * <p>从十六进制字符到返回字节的转换使用
     * {@link #getCharset()} 指定的字符集进行。</p>
     *
     * <p>将使用 {@link ByteBuffer#remaining()} 标识的全部字节；调用本方法后，
     * {@link ByteBuffer#remaining() remaining()} 的值将为零。</p>
     *
     * @param array 待转换为十六进制字符的字节缓冲区
     * @return 包含小写十六进制字符对应字节的 byte[]
     * @see #encodeHex(byte[])
     * @since 1.11
     */
    public byte[] encode(final ByteBuffer array) {
        return encodeHexString(array).getBytes(getCharset());
    }

    /**
     * 把 String 或字节数组按顺序转换为表示每个字节十六进制值的字符数组。
     * 返回数组长度为传入 String 或数组的两倍，因为表示每个字节
     * 需要两个字符。
     * <p>
     * 从十六进制字符到待编码字节的转换使用
     * {@link #getCharset()} 指定的字符集进行。
     * </p>
     *
     * @param object 待转换为十六进制字符的 String、ByteBuffer 或 byte[]
     * @return 包含小写十六进制字符的 char[]
     * @throws EncoderException 当给定对象不是 String 或 byte[] 时抛出
     * @see #encodeHex(byte[])
     */
    @Override
    public Object encode(final Object object) throws EncoderException {
        final byte[] byteArray;
        if (object instanceof String) {
            byteArray = ((String) object).getBytes(getCharset());
        } else if (object instanceof ByteBuffer) {
            byteArray = toByteArray((ByteBuffer) object);
        } else {
            try {
                byteArray = (byte[]) object;
            } catch (final ClassCastException e) {
                throw new EncoderException(e.getMessage(), e);
            }
        }
        return encodeHex(byteArray);
    }

    /**
     * 获取字符集。
     *
     * @return 字符集。
     * @since 1.7
     */
    public Charset getCharset() {
        return this.charset;
    }

    /**
     * 获取字符集名。
     *
     * @return 字符集名。
     * @since 1.4
     */
    public String getCharsetName() {
        return this.charset.name();
    }

    /**
     * 返回该对象的字符串表示，其中包含字符集名。
     *
     * @return 该对象的字符串表示。
     */
    @Override
    public String toString() {
        return super.toString() + "[charsetName=" + this.charset + "]";
    }
}
