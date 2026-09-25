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

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 简化常见 {@link MessageDigest} 操作的工具类。该类不可变且线程安全，但它创建的 MessageDigest 实例
 * 一般不保证线程安全。
 * <p>
 * {@link MessageDigestAlgorithms} 类提供了标准摘要算法的常量，可与 {@link #getDigest(String)} 方法以及
 * 其他需要摘要算法名称的方法配合使用。
 * </p>
 * <p>
 * 注意：本类为 Java 6 中的所有标准算法都提供了简写方法。这种方式每个算法都需要大量方法，
 * 很快就会变得难以维护。以下代码适用于所有算法：
 * </p>
 *
 * <pre>
 * import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_224;
 * ...
 * byte [] digest = new DigestUtils(SHA_224).digest(dataToDigest);
 * String hdigest = new DigestUtils(SHA_224).digestAsHex(new File("pom.xml"));
 * </pre>
 *
 * @see MessageDigestAlgorithms
 */
public class DigestUtils {

    /**
     * 包私有，仅供测试。
     */
    static final int BUFFER_SIZE = 1024;

    /**
     * 读取 InputStream 并返回数据的摘要。
     *
     * @param messageDigest 要使用的 MessageDigest（例如 MD5）
     * @param data          待摘要的数据
     * @return 摘要值
     * @throws IOException 从流读取时发生错误
     * @since 1.11（原为 private）
     */
    public static byte[] digest(final MessageDigest messageDigest, final InputStream data) throws IOException {
        return updateDigest(messageDigest, data).digest();
    }

    /**
     * 获取指定 {@code algorithm} 的 {@code MessageDigest}。
     *
     * @param algorithm 所请求的算法名称。参见
     *                  <a href="https://docs.oracle.com/javase/6/docs/technotes/guides/security/crypto/CryptoSpec.html#AppA">Java
     *                  加密架构参考指南的附录 A</a>，了解标准算法名称的相关信息。
     * @return 一个摘要实例。
     * @see MessageDigest#getInstance(String)
     * @throws IllegalArgumentException 捕获到 {@link NoSuchAlgorithmException} 时。
     */
    public static MessageDigest getDigest(final String algorithm) {
        try {
            return getMessageDigest(algorithm);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * 获取一个 MD5 MessageDigest。
     *
     * @return 一个 MD5 摘要实例。
     * @throws IllegalArgumentException 捕获到 {@link NoSuchAlgorithmException} 时，由于 MD5 是内置算法，理论上不应发生
     * @see MessageDigestAlgorithms#MD5
     */
    public static MessageDigest getMd5Digest() {
        return getDigest(MessageDigestAlgorithms.MD5);
    }

    /**
     * 获取指定 {@code algorithm} 的 {@code MessageDigest}。
     *
     * @param algorithm 所请求的算法名称。参见
     *                  <a href="https://docs.oracle.com/javase/6/docs/technotes/guides/security/crypto/CryptoSpec.html#AppA"> Java
     *                  加密架构参考指南的附录 A</a>，了解标准算法名称的相关信息。
     * @return 一个摘要实例。
     * @see MessageDigest#getInstance(String)
     * @throws NoSuchAlgorithmException 如果没有 Provider 支持该算法对应的 MessageDigestSpi 实现。
     */
    private static MessageDigest getMessageDigest(final String algorithm) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance(algorithm);
    }

    /**
     * 获取一个 SHA-1 摘要。
     *
     * @return 一个 SHA-1 摘要实例。
     * @throws IllegalArgumentException 捕获到 {@link NoSuchAlgorithmException} 时，由于 SHA-1 是内置算法，理论上不应发生
     * @see MessageDigestAlgorithms#SHA_1
     * @since 1.7
     */
    public static MessageDigest getSha1Digest() {
        return getDigest(MessageDigestAlgorithms.SHA_1);
    }

    /**
     * 获取一个 SHA-1 摘要。
     *
     * @return 一个 SHA-1 摘要实例。
     * @throws IllegalArgumentException 捕获到 {@link NoSuchAlgorithmException} 时
     * @deprecated (1.11) 请改用 {@link #getSha1Digest()}
     */
    @Deprecated
    public static MessageDigest getShaDigest() {
        return getSha1Digest();
    }

    /**
     * 计算 MD5 摘要，并以 16 元素 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据
     * @return MD5 摘要
     */
    public static byte[] md5(final byte[] data) {
        return getMd5Digest().digest(data);
    }

    /**
     * 计算 MD5 摘要，并以 16 元素 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据；使用 {@link StringUtils#getBytesUtf8(String)} 转为字节
     * @return MD5 摘要
     */
    public static byte[] md5(final String data) {
        return md5(StringUtils.getBytesUtf8(data));
    }

    /**
     * 计算 MD5 摘要，并以 32 字符的十六进制字符串返回。
     *
     * @param data 待摘要的数据
     * @return 以十六进制字符串表示的 MD5 摘要
     */
    public static String md5Hex(final String data) {
        return Hex.encodeHexString(md5(data));
    }

    /**
     * 计算 SHA-1 摘要，并以 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据
     * @return SHA-1 摘要
     * @deprecated (1.11) 请改用 {@link #sha1(byte[])}
     */
    @Deprecated
    public static byte[] sha(final byte[] data) {
        return sha1(data);
    }

    /**
     * 计算 SHA-1 摘要，并以 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据
     * @return SHA-1 摘要
     * @throws IOException 从流读取时发生错误
     * @since 1.4
     * @deprecated (1.11) 请改用 {@link #sha1(InputStream)}
     */
    @Deprecated
    public static byte[] sha(final InputStream data) throws IOException {
        return sha1(data);
    }

    /**
     * 计算 SHA-1 摘要，并以 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据
     * @return SHA-1 摘要
     * @deprecated (1.11) 请改用 {@link #sha1(String)}
     */
    @Deprecated
    public static byte[] sha(final String data) {
        return sha1(data);
    }

    /**
     * 计算 SHA-1 摘要，并以 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据
     * @return SHA-1 摘要
     * @since 1.7
     */
    public static byte[] sha1(final byte[] data) {
        return getSha1Digest().digest(data);
    }

    /**
     * 计算 SHA-1 摘要，并以 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据
     * @return SHA-1 摘要
     * @throws IOException 从流读取时发生错误
     * @since 1.7
     */
    public static byte[] sha1(final InputStream data) throws IOException {
        return digest(getSha1Digest(), data);
    }

    /**
     * 计算 SHA-1 摘要，并以 {@code byte[]} 返回。
     *
     * @param data 待摘要的数据；使用 {@link StringUtils#getBytesUtf8(String)} 转为字节
     * @return SHA-1 摘要
     */
    public static byte[] sha1(final String data) {
        return sha1(StringUtils.getBytesUtf8(data));
    }

    /**
     * 计算 SHA-1 摘要，并以十六进制字符串返回。
     *
     * @param data 待摘要的数据
     * @return 以十六进制字符串表示的 SHA-1 摘要
     * @since 1.7
     */
    public static String sha1Hex(final byte[] data) {
        return Hex.encodeHexString(sha1(data));
    }

    /**
     * 计算 SHA-1 摘要，并以十六进制字符串返回。
     *
     * @param data 待摘要的数据
     * @return 以十六进制字符串表示的 SHA-1 摘要
     * @throws IOException 从流读取时发生错误
     * @since 1.7
     */
    public static String sha1Hex(final InputStream data) throws IOException {
        return Hex.encodeHexString(sha1(data));
    }

    /**
     * 计算 SHA-1 摘要，并以十六进制字符串返回。
     *
     * @param data 待摘要的数据
     * @return 以十六进制字符串表示的 SHA-1 摘要
     * @since 1.7
     */
    public static String sha1Hex(final String data) {
        return Hex.encodeHexString(sha1(data));
    }
    /**
     * 计算 SHA-1 摘要，并以十六进制字符串返回。
     *
     * @param data 待摘要的数据
     * @return 以十六进制字符串表示的 SHA-1 摘要
     * @deprecated (1.11) 请改用 {@link #sha1Hex(byte[])}
     */
    @Deprecated
    public static String shaHex(final byte[] data) {
        return sha1Hex(data);
    }

    /**
     * 计算 SHA-1 摘要，并以十六进制字符串返回。
     *
     * @param data 待摘要的数据
     * @return 以十六进制字符串表示的 SHA-1 摘要
     * @throws IOException 从流读取时发生错误
     * @since 1.4
     * @deprecated (1.11) 请改用 {@link #sha1Hex(InputStream)}
     */
    @Deprecated
    public static String shaHex(final InputStream data) throws IOException {
        return sha1Hex(data);
    }

    /**
     * 计算 SHA-1 摘要，并以十六进制字符串返回。
     *
     * @param data 待摘要的数据
     * @return 以十六进制字符串表示的 SHA-1 摘要
     * @deprecated (1.11) 请改用 {@link #sha1Hex(String)}
     */
    @Deprecated
    public static String shaHex(final String data) {
        return sha1Hex(data);
    }

    /**
     * 读取 InputStream 并用数据更新摘要。
     *
     * @param digest      要使用的 MessageDigest（例如 MD5）
     * @param inputStream 待摘要的输入流
     * @return 摘要值
     * @throws IOException 从流读取时发生错误
     * @since 1.8
     */
    public static MessageDigest updateDigest(final MessageDigest digest, final InputStream inputStream) throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        int read = inputStream.read(buffer, 0, BUFFER_SIZE);

        while (read > -1) {
            digest.update(buffer, 0, read);
            read = inputStream.read(buffer, 0, BUFFER_SIZE);
        }

        return digest;
    }


    /**
     * 仅为保持二进制兼容性而保留。与旧版本一样不提供有用行为。
     *
     * @deprecated 自 1.11 起；仅用于保持二进制兼容性
     */
    @Deprecated
    public DigestUtils() {
    }

}
