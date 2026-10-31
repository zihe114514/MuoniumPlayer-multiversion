package com.muoniumplayer.core.ncm.apple.protocol;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Widevine L3 许可证协议的最小 JVM 实现:构造 license challenge、解析 license 响应、
 * 提取内容密钥。协议消息全部为 proto2 wire format,这里手写所需的子集
 * (编码只写显式字段,解码按 tag 跳过未知字段,保证对上游 schema 演进宽容)。
 *
 * <h3>涉及的协议消息(字段号见 license_protocol.proto)</h3>
 * <ul>
 *   <li>{@code ClientIdentification}:设备身份(来自 .wvd 的 client_id blob,原样透传)。</li>
 *   <li>{@code LicenseRequest}(field 1=client_id 或 8=encrypted_client_id,
 *       2=ContentIdentification{1=WidevinePsshData{1=pssh_data,2=license_type,3=request_id}},
 *       3=type NEW,4=request_time,6=protocol_version 21,7=key_control_nonce)。</li>
 *   <li>{@code SignedMessage}:type=LICENSE_REQUEST(1) 包 LicenseRequest;
 *       响应侧 type=LICENSE(2),msg=License{3=KeyContainer[]},signature=HMAC-SHA256,
 *       session_key=RSA-OAEP(设备私钥)包裹的会话密钥。</li>
 *   <li>密钥派生:AES-CMAC 计数器流(ENCRYPTION 16 字节 / AUTHENTICATION 2×32 字节)。</li>
 * </ul>
 *
 * <h3>密码原语</h3>
 * <p>全部 JDK 标准件:RSA-SHA1(PSS 编码的 challenge 签名按 pywidevine 用
 * {@code SHA1withRSA}(PKCS#1 v1.5)——Widevine 服务器接受该形态;会话密钥解包用
 * RSA/ECB/OAEPWithSHA-1AndMGF1Padding;密钥派生用 AES-CMAC(自实现 60 行);
 * 响应签名校验 HmacSHA256;KeyContainer 内容密钥本身是明文(仅由会话密钥层保护)。</p>
 *
 * <p>设备数据(.wvd v2)解析:{@code "WVD" magic + version(=2) + type + security_level
 * + flags[1] + privKeyLen(u16) + privKey(PEM/PKCS#8) + clientIdLen(u16) + clientId blob}。</p>
 */
public final class AppleWidevineCdm {

    /** ISO-BMFF 里 Widevine 的 system id。 */
    public static final String WIDEVINE_SYSTEM_ID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed";

    // ── 会话态 ──────────────────────────────────────────────────────────────

    /** 一次 license 交换的上下文:request_id → 派生输入(license_request 原文)。 */
    private final Map<String, byte[]> pendingContexts = new LinkedHashMap<>();

    private final byte[] clientBlob;
    private final PrivateKey privateKey;
    private final int sessionNumber;

    // ── 设备加载 ─────────────────────────────────────────────────────────────

    /** 从 .wvd v2 文件字节构造。设备证书/私钥不落日志。 */
    public static AppleWidevineCdm fromWvd(byte[] wvd, int sessionNumber) throws IOException {
        if (wvd.length < 8 || wvd[0] != 'W' || wvd[1] != 'V' || wvd[2] != 'D') {
            throw new IOException("wvd 文件头不是 WVD");
        }
        int version = wvd[3] & 0xFF;
        if (version != 2) {
            throw new IOException("wvd 版本不支持: " + version);
        }
        // 4=type, 5=security_level, 6=flags(1 字节), 之后 u16 长度+私钥、u16 长度+client_id
        int offset = 6; // flags 字段长度为 1,offset 指向 private_key_len 前
        offset += 1; // flags
        int privLen = ((wvd[offset] & 0xFF) << 8) | (wvd[offset + 1] & 0xFF);
        offset += 2;
        byte[] privDer = new byte[privLen];
        System.arraycopy(wvd, offset, privDer, 0, privLen);
        offset += privLen;
        int cidLen = ((wvd[offset] & 0xFF) << 8) | (wvd[offset + 1] & 0xFF);
        offset += 2;
        byte[] clientBlob = new byte[cidLen];
        System.arraycopy(wvd, offset, clientBlob, 0, cidLen);

        PrivateKey key = parsePkcs8OrPkcs1(privDer);
        return new AppleWidevineCdm(clientBlob, key, sessionNumber);
    }

    private static PrivateKey parsePkcs8OrPkcs1(byte[] der) throws IOException {
        // PKCS#8 前导 30 82 .. 02 01 00 02..;PKCS#1 前导 30 82 .. 02 01 01(或 02 01 00 是 RSA 密钥的第一字段,需试解)
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception pkcs8Failure) {
            // PKCS#1 RSAPrivateKey → 手工包装成 PKCS#8
            try {
                byte[] version = {0x02, 0x01, 0x00};           // INTEGER 0 (pkcs8 version)
                byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                        (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00}; // rsaEncryption
                byte[] octet = derEncode(0x04, der);
                ByteArrayOutputStream pkcs8 = new ByteArrayOutputStream();
                pkcs8.write(version);
                pkcs8.write(algorithm);
                pkcs8.write(octet);
                byte[] body = pkcs8.toByteArray();
                byte[] wrapped = derEncode(0x30, body);
                return KeyFactory.getInstance("RSA")
                        .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(wrapped));
            } catch (Exception pkcs1Failure) {
                IOException failure = new IOException("wvd 私钥解析失败");
                failure.addSuppressed(pkcs8Failure);
                failure.addSuppressed(pkcs1Failure);
                throw failure;
            }
        }
    }

    private static byte[] derEncode(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(len)) / 8 + 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((len >> (8 * i)) & 0xFF);
            }
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    public AppleWidevineCdm(byte[] clientBlob, PrivateKey privateKey, int sessionNumber) {
        this.clientBlob = clientBlob.clone();
        this.privateKey = privateKey;
        this.sessionNumber = sessionNumber;
    }

    // ── Challenge 构造 ───────────────────────────────────────────────────────

    /**
     * 构造 license challenge(SignedMessage{type=LICENSE_REQUEST})。
     *
     * @param psshInitData WidevinePsshData(CENC header)原文 —— moov/pssh 盒或 m3u8 key
     *                     URI base64 解出的 init data
     */
    public byte[] buildLicenseChallenge(byte[] psshInitData, long requestTimeSeconds)
            throws Exception {
        SecureRandom random = new SecureRandom();
        // Android 设备形态的 request_id:4 随机字节 + 4 零字节 + 8B LE 会话计数,
        // 以 32 字符大写 hex 的 ASCII 字符串放进 pssh_data —— 服务器按此形态校验并回显。
        byte[] requestRaw = new byte[16];
        random.nextBytes(requestRaw);
        requestRaw[4] = 0;
        requestRaw[5] = 0;
        requestRaw[6] = 0;
        requestRaw[7] = 0;
        requestRaw[8] = 1; // 8B LE 会话计数=1,其余清零(对齐参考实现的计数形态)
        requestRaw[9] = 0;
        requestRaw[10] = 0;
        requestRaw[11] = 0;
        requestRaw[12] = 0;
        requestRaw[13] = 0;
        requestRaw[14] = 0;
        requestRaw[15] = 0;
        byte[] requestId = hex(requestRaw).toUpperCase().getBytes(StandardCharsets.US_ASCII);

        // LicenseRequest
        ByteArrayOutputStream lr = new ByteArrayOutputStream();
        lr.write(fieldBytes(1, clientBlob)); // client_id
        // content_id { widevine_pssh_data }
        ByteArrayOutputStream psshData = new ByteArrayOutputStream();
        psshData.write(fieldBytes(1, psshInitData)); // pssh_data
        psshData.write(varintField(2, 1));           // license_type = STREAMING
        psshData.write(fieldBytes(3, requestId));    // request_id(Android hex 串形态)
        ByteArrayOutputStream contentId = new ByteArrayOutputStream();
        contentId.write(fieldBytes(1, psshData.toByteArray())); // widevine_pssh_data
        lr.write(fieldBytes(2, contentId.toByteArray()));
        lr.write(varintField(3, 1));                              // type = NEW
        lr.write(varintField(4, requestTimeSeconds));             // request_time
        lr.write(varintField(6, 21));                             // protocol_version = 2.1
        lr.write(varintField(7, 1 + random.nextInt(Integer.MAX_VALUE - 1))); // key_control_nonce
        byte[] licenseRequest = lr.toByteArray();

        // SignedMessage{type, msg, signature=RSASSA-PSS(SHA1, MGF1-SHA1, salt=20)(msg)}
        // 参考实现用 PSS-PKCS1 盐=摘要长度;v1.5 签名会被验签拒绝。
        Signature signer = Signature.getInstance("RSASSA-PSS");
        signer.setParameter(new java.security.spec.PSSParameterSpec(
                "SHA-1", "MGF1", java.security.spec.MGF1ParameterSpec.SHA1, 20,
                java.security.spec.PSSParameterSpec.TRAILER_FIELD_BC));
        signer.initSign(privateKey);
        signer.update(licenseRequest);
        byte[] signature = signer.sign();

        ByteArrayOutputStream sm = new ByteArrayOutputStream();
        sm.write(varintField(1, 1)); // type = LICENSE_REQUEST
        sm.write(fieldBytes(2, licenseRequest));
        sm.write(fieldBytes(3, signature));
        byte[] signed = sm.toByteArray();

        pendingContexts.clear();
        pendingContexts.put(hex(requestId), licenseRequest);
        return signed;
    }

    // ── License 解析 ─────────────────────────────────────────────────────────

    /**
     * 解析 license 响应(SignedMessage{type=LICENSE}),返回 CONTENT 类型密钥的
     * kid→key 十六进制表。校验 HmacSHA256 响应签名。
     */
    public Map<String, String> parseLicense(byte[] signedMessage) throws Exception {
        PbMessage sm = PbMessage.parse(signedMessage);
        int type = sm.varint(1, 0);
        if (type != 2) { // LICENSE
            throw new IOException("license 响应类型错误: " + type);
        }
        byte[] msg = sm.bytesField(2);
        byte[] signature = sm.bytesField(3);
        byte[] sessionKeyWrapped = sm.bytesField(4);
        byte[] oemCore = sm.bytesField(9);

        PbMessage license = PbMessage.parse(msg);

        // License.id.request_id(field 1 → LicenseIdentification{1=request_id})
        PbMessage licenseId = license.message(1);
        byte[] requestId = licenseId == null ? null : licenseId.bytesField(1);
        byte[] context = requestId == null ? null : pendingContexts.remove(hex(requestId));
        if (context == null && pendingContexts.size() == 1) {
            // 响应不带 request_id(或字段被服务器裁剪)时,单挂起上下文直接取用。
            context = pendingContexts.values().iterator().next();
            pendingContexts.clear();
        }
        if (context == null) {
            throw new IOException("license 响应与本次 challenge 不匹配(request_id)");
        }

        // 会话密钥解包:RSA-OAEP-SHA1
        Cipher oaep = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        oaep.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] sessionKey = oaep.doFinal(sessionKeyWrapped);

        // 派生密钥 —— 上下文里的长度字段是「位」:ENCRYPTION=128,AUTHENTICATION=512。
        byte[] encContext = deriveContext("ENCRYPTION", context, 128);
        byte[] macContext = deriveContext("AUTHENTICATION", context, 512);
        byte[] encKey = cmac(sessionKey, concat(new byte[]{1}, encContext));
        byte[] macKeyServer = cmac(sessionKey, concat(new byte[]{1}, macContext));
        byte[] macKeyServerPart2 = cmac(sessionKey, concat(new byte[]{2}, macContext));
        byte[] fullMacKeyServer = concat(macKeyServer, macKeyServerPart2);

        // 响应签名校验:HmacSHA256(oemCore || msg)
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(fullMacKeyServer, "HmacSHA256"));
        if (oemCore != null && oemCore.length > 0) hmac.update(oemCore);
        hmac.update(msg);
        byte[] expected = hmac.doFinal();
        if (!MessageDigest.isEqual(expected, signature)) {
            throw new IOException("license 响应签名校验失败");
        }

        // KeyContainer 列表(field 3 repeated):取 type=CONTENT(2) 的容器,用 encKey +
        // 容器自带 IV 做 AES-CBC 解密并去 PKCS7 填充,得到真正的 16 字节内容密钥。
        Map<String, String> keys = new LinkedHashMap<>();
        for (PbMessage container : license.repeated(3)) {
            int keyType = container.varint(4, 0);
            if (keyType != 2) continue; // CONTENT
            byte[] kid = container.bytesField(1);
            byte[] iv = container.bytesField(2);
            byte[] wrapped = container.bytesField(3);
            if (kid == null || wrapped == null || wrapped.length != 32) {
                continue; // 形态异常的容器跳过,不让单个坏块拖垮整张 license
            }
            if (iv == null || iv.length == 0) {
                iv = new byte[16];
            }
            byte[] key = pkcs7Unpad(aesCbcDecrypt(encKey, iv, wrapped));
            if (key.length != 16) {
                throw new IOException("内容密钥解密后不是 16 字节: " + key.length);
            }
            keys.put(hex(kid), hex(key)); // kid -> key
        }
        if (keys.isEmpty()) {
            throw new IOException("license 响应里没有 CONTENT 密钥");
        }
        return keys;
    }

    private static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] data) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IOException("内容密钥 AES-CBC 解密失败", e);
        }
    }

    private static byte[] pkcs7Unpad(byte[] data) throws IOException {
        if (data.length == 0) throw new IOException("PKCS7 去填充:空数据");
        int pad = data[data.length - 1] & 0xFF;
        if (pad < 1 || pad > 16 || pad > data.length) {
            throw new IOException("PKCS7 填充非法: " + pad);
        }
        for (int i = data.length - pad; i < data.length; i++) {
            if ((data[i] & 0xFF) != pad) throw new IOException("PKCS7 填充字节不一致");
        }
        byte[] out = new byte[data.length - pad];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    private static byte[] deriveContext(String label, byte[] message, int keySizeBits) {
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        byte[] sizeBytes = {(byte) (keySizeBits >>> 24), (byte) (keySizeBits >>> 16),
                (byte) (keySizeBits >>> 8), (byte) keySizeBits};
        byte[] out = new byte[labelBytes.length + 1 + message.length + 4];
        int pos = 0;
        System.arraycopy(labelBytes, 0, out, pos, labelBytes.length);
        pos += labelBytes.length;
        out[pos++] = 0x00;
        System.arraycopy(message, 0, out, pos, message.length);
        pos += message.length;
        System.arraycopy(sizeBytes, 0, out, pos, 4);
        return out;
    }

    /** AES-CMAC(RFC 4493),仅用于 Widevine 密钥派生。 */
    static byte[] cmac(byte[] key, byte[] message) throws Exception {
        SecretKey aesKey = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey);
        // 子密钥
        byte[] l = cipher.doFinal(new byte[16]);
        byte[] k1 = shiftAndConditional(l);
        byte[] k2 = shiftAndConditional(k1);
        // 分块
        int blocks = (message.length + 15) / 16;
        if (blocks == 0) blocks = 1;
        byte[] last;
        boolean lastComplete = message.length > 0 && message.length % 16 == 0;
        if (lastComplete) {
            last = xor(tail(message, (blocks - 1) * 16, 16), k1);
        } else {
            byte[] padded = java.util.Arrays.copyOf(
                    tail(message, (blocks - 1) * 16, message.length - (blocks - 1) * 16), 16);
            padded[message.length - (blocks - 1) * 16] = (byte) 0x80;
            last = xor(padded, k2);
        }
        byte[] x = new byte[16];
        for (int i = 0; i < blocks - 1; i++) {
            byte[] block = tail(message, i * 16, 16);
            x = cipher.doFinal(xor(x, block));
        }
        return cipher.doFinal(xor(x, last));
    }

    private static byte[] shiftAndConditional(byte[] input) {
        byte[] out = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int v = (input[i] & 0xFF);
            out[i] = (byte) ((v << 1) | carry);
            carry = (v & 0x80) != 0 ? 1 : 0;
        }
        if (carry != 0) {
            out[15] ^= (byte) 0x87;
        }
        return out;
    }

    private static byte[] tail(byte[] src, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) (a[i] ^ b[i]);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ── proto2 wire format 子集 ──────────────────────────────────────────────

    /** 极简 proto 消息视图:只索引所需字段,未知 tag 跳过。 */
    public static final class PbMessage {
        private final byte[] data;
        private final Map<Integer, List<byte[]>> fields = new LinkedHashMap<>();

        private PbMessage(byte[] data) {
            this.data = data;
        }

        public static PbMessage parse(byte[] wire) throws IOException {
            PbMessage message = new PbMessage(wire);
            int pos = 0;
            while (pos < wire.length) {
                long tag = readVarint(wire, pos);
                pos += varintSize(wire, pos);
                int fieldNumber = (int) (tag >>> 3);
                int wireType = (int) (tag & 0x07);
                if (wireType == 0) { // varint
                    long value = readVarint(wire, pos);
                    pos += varintSize(wire, pos);
                    // 以 8 字节小端存 varint 值,便于统一取用
                    byte[] raw = new byte[8];
                    for (int i = 0; i < 8; i++) raw[i] = (byte) (value >>> (8 * i));
                    message.fields.computeIfAbsent(fieldNumber, k -> new ArrayList<>()).add(raw);
                } else if (wireType == 2) { // length-delimited
                    long length = readVarint(wire, pos);
                    pos += varintSize(wire, pos);
                    int len = (int) length;
                    if (len < 0 || pos + len > wire.length) throw new IOException("proto 长度越界");
                    byte[] chunk = new byte[len];
                    System.arraycopy(wire, pos, chunk, 0, len);
                    pos += len;
                    message.fields.computeIfAbsent(fieldNumber, k -> new ArrayList<>()).add(chunk);
                } else if (wireType == 5) { // fixed32
                    pos += 4;
                } else if (wireType == 1) { // fixed64
                    pos += 8;
                } else {
                    throw new IOException("不支持的 wire type: " + wireType);
                }
            }
            return message;
        }

        public int varint(int field, int fallback) {
            List<byte[]> chunks = fields.get(field);
            if (chunks == null || chunks.isEmpty()) return fallback;
            long value = 0;
            for (int i = 0; i < 8; i++) value |= ((chunks.get(0)[i]) & 0xFFL) << (8 * i);
            return (int) value;
        }

        public byte[] bytesField(int field) {
            List<byte[]> chunks = fields.get(field);
            return chunks == null || chunks.isEmpty() ? null : chunks.get(0);
        }

        public PbMessage message(int field) throws IOException {
            byte[] raw = bytesField(field);
            return raw == null ? null : PbMessage.parse(raw);
        }

        public List<PbMessage> repeated(int field) throws IOException {
            List<PbMessage> out = new ArrayList<>();
            List<byte[]> chunks = fields.get(field);
            if (chunks != null) {
                for (byte[] chunk : chunks) out.add(PbMessage.parse(chunk));
            }
            return out;
        }
    }

    private static byte[] fieldBytes(int fieldNumber, byte[] value) {
        return concat(varint((fieldNumber << 3) | 2L), concat(varint(value.length), value));
    }

    private static byte[] varintField(int fieldNumber, long value) {
        return concat(varint((fieldNumber << 3) | 0L), varint(value));
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while ((v & ~0x7F) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static long readVarint(byte[] data, int offset) throws IOException {
        long value = 0;
        int shift = 0;
        int pos = offset;
        while (true) {
            if (pos >= data.length) throw new IOException("varint 截断");
            byte b = data[pos++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 63) throw new IOException("varint 过长");
        }
    }

    private static int varintSize(byte[] data, int offset) throws IOException {
        int pos = offset;
        while (pos < data.length && (data[pos] & 0x80) != 0) pos++;
        return pos - offset + 1;
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    /** PSSH 重建:裸 16 字节 KID → 标准 Widevine cenc init data(box 内的 WidevinePsshData)。 */
    public static byte[] reconstructPsshInitData(byte[] kidOrFull) throws IOException {
        if (kidOrFull.length > 30) {
            return kidOrFull; // 已是完整 init data
        }
        if (kidOrFull.length != 16) {
            throw new IllegalArgumentException("PSSH 数据长度异常: " + kidOrFull.length);
        }
        ByteArrayOutputStream psshData = new ByteArrayOutputStream();
        psshData.write(varintField(1, 1));          // algorithm = AESCTR(正式字段号是 1;
                                                    // 曾经错写成 2,解析器跳过杂散字段才侥幸可用)
        psshData.write(fieldBytes(2, kidOrFull));   // key_ids
        return psshData.toByteArray();
    }

    /** 供测试:暴露 BigInteger 形态的私钥模数(CMAC 向量与签名向量校验用)。 */
    BigInteger privateKeyModulus() {
        return privateKey instanceof java.security.interfaces.RSAPrivateKey rsa
                ? rsa.getModulus() : BigInteger.ZERO;
    }
}
