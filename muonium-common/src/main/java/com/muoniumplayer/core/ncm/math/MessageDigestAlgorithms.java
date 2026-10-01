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

import java.security.MessageDigest;

/**
 * 来自 <cite>Java 加密架构标准算法名
 * 文档</cite> 的标准 {@link MessageDigest} 算法名。
 * <p>
 * 该类不可变且线程安全。
 * </p>
 * <ul>
 * <li>Java 8 及以上：SHA-224。</li>
 * <li>Java 9 及以上：SHA3-224、SHA3-256、SHA3-384、SHA3-512。</li>
 * </ul>
 *
 *     @see <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#MessageDigest">
 *      Java 8 加密架构标准算法名文档</a>
 *     @see <a href="https://docs.oracle.com/en/java/javase/11/docs/specs/security/standard-names.html#messagedigest-algorithms">
 *      Java 11 加密架构标准算法名文档</a>
 *     @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#messagedigest-algorithms">
 *      Java 17 加密架构标准算法名文档</a>
 *     @see <a href="https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html#messagedigest-algorithms">
 *      Java 21 加密架构标准算法名文档</a>
 *
 *     @see <a href="https://dx.doi.org/10.6028/NIST.FIPS.180-4">FIPS PUB 180-4</a>
 *     @see <a href="https://dx.doi.org/10.6028/NIST.FIPS.202">FIPS PUB 202</a>
 * @since 1.7
 */
public class MessageDigestAlgorithms {

    /**
     * RFC 1319 中定义的 MD2 消息摘要算法。
     */
    public static final String MD2 = "MD2";

    /**
     * RFC 1321 中定义的 MD5 消息摘要算法。
     */
    public static final String MD5 = "MD5";

    /**
     * FIPS PUB 180-2 中定义的 SHA-1 哈希算法。
     */
    public static final String SHA_1 = "SHA-1";

    /**
     * FIPS PUB 180-3 中定义的 SHA-224 哈希算法。
     * <p>
     * Oracle Java 8 中已包含。
     * </p>
     *
     * @since 1.11
     */
    public static final String SHA_224 = "SHA-224";

    /**
     * FIPS PUB 180-2 中定义的 SHA-256 哈希算法。
     */
    public static final String SHA_256 = "SHA-256";

    /**
     * FIPS PUB 180-2 中定义的 SHA-384 哈希算法。
     */
    public static final String SHA_384 = "SHA-384";

    /**
     * FIPS PUB 180-2 中定义的 SHA-512 哈希算法。
     */
    public static final String SHA_512 = "SHA-512";

    /**
     * FIPS PUB 180-4 中定义的 SHA-512 哈希算法。
     * <p>
     * 自 Oracle Java 9 起包含。
     * </p>
     *
     * @since 1.14
     */
    public static final String SHA_512_224 = "SHA-512/224";

    /**
     * FIPS PUB 180-4 中定义的 SHA-512 哈希算法。
     * <p>
     * 自 Oracle Java 9 起包含。
     * </p>
     *
     * @since 1.14
     */
    public static final String SHA_512_256 = "SHA-512/256";

    /**
     * FIPS PUB 202 中定义的 SHA3-224 哈希算法。
     * <p>
     * 自 Oracle Java 9 起包含。
     * </p>
     *
     * @since 1.11
     */
    public static final String SHA3_224 = "SHA3-224";

    /**
     * FIPS PUB 202 中定义的 SHA3-256 哈希算法。
     * <p>
     * 自 Oracle Java 9 起包含。
     * </p>
     *
     * @since 1.11
     */
    public static final String SHA3_256 = "SHA3-256";

    /**
     * FIPS PUB 202 中定义的 SHA3-384 哈希算法。
     * <p>
     * 自 Oracle Java 9 起包含。
     * </p>
     *
     * @since 1.11
     */
    public static final String SHA3_384 = "SHA3-384";

    /**
     * FIPS PUB 202 中定义的 SHA3-512 哈希算法。
     * <p>
     * 自 Oracle Java 9 起包含。
     * </p>
     *
     * @since 1.11
     */
    public static final String SHA3_512 = "SHA3-512";

    /**
     * 获取本类中定义的所有常量值。
     *
     * @return 本类中定义的所有常量值。
     * @since 1.11
     */
    public static String[] values() {
        // 此处不要使用常量数组，因为它可能被外部意外或有意地修改
        return new String[] {
            MD2, MD5, SHA_1, SHA_224, SHA_256, SHA_384,
            SHA_512, SHA_512_224, SHA_512_256, SHA3_224, SHA3_256, SHA3_384, SHA3_512
        };
    }

    private MessageDigestAlgorithms() {
        // 不可实例化。
    }

}
