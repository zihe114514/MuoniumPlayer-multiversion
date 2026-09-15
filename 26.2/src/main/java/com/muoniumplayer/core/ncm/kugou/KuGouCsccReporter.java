package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * 酷狗的听歌时长上报（CSCC）。
 *
 * <p>这是酷狗侧唯一能证明「这首歌真的被听了多久」的通道，也是账号等级/成长值结算的依据。
 * 它和网易云那边「按本地计时后提交一个累计秒数」的模型不同：酷狗要求 <b>start / end 两个事件</b>
 * 成对上报，时长挂在 end 事件的 {@code duration} 字段上（毫秒）。</p>
 *
 * <h3>四步握手（每一步都是实测确认的）</h3>
 * <ol>
 *   <li>{@code /v3/qrydid} 用 mid/uuid/屏幕尺寸换一个 {@code deviceid}；</li>
 *   <li>{@code /v2/gen} 把 {@code uuid+field2+时间戳+deviceid} 用<b>概念版公钥</b> RSA 加密后提交，
 *       拿回一个用 {@code field2} 作 AES 密钥加密的会话；</li>
 *   <li>{@code /v2/post} 把事件字段拼成文本、deflate、再 AES 加密后提交；</li>
 *   <li>{@code /v2/post} 结束事件由 {@link #finish} 发。</li>
 * </ol>
 *
 * <h3>几个不能改的地方</h3>
 * <ul>
 *   <li>{@code sty} 必须是 {@code 手动}。实测 {@code sty=auto} 返回 {@code errcode=1203}。</li>
 *   <li>会话要按账号+设备缓存：每次上报都重新握手会显著变慢，而且 {@code /v2/gen} 是限流的。</li>
 *   <li><b>失败不重试</b>。超时也可能已经被记账，重试会重复计入时长。</li>
 * </ul>
 */
public final class KuGouCsccReporter {

    private static final String APPKEY = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final String VERSION = "10597";
    private static final String DEVICE_MODEL = "MuoniumPlayer";
    private static final String SYSTEM_VERSION = "9";
    private static final int SCREEN_WIDTH = 1920;
    private static final int SCREEN_HEIGHT = 1080;

    /** start 与 end 事件的 type_id 不同，写反了服务端不会报错，只是时长不计入。 */
    private static final String TYPE_ID_START = "20431";
    private static final String TYPE_ID_END = "4";

    private static final String FO3 = "3878,4320,4326,5747,5749,7229,7289,7863";

    /** 会话有效期。上游侧是 30 分钟，本地留短一点，避免踩到边界。 */
    private static final long SESSION_TTL_MILLIS = 20L * 60L * 1000L;

    private static final Object LOCK = new Object();
    private static Session cached;

    private KuGouCsccReporter() {
    }

    /**
     * 发一次「开始播放」事件。
     *
     * @param mixSongId 曲目的 {@code album_audio_id}；拿不到就直接放弃，这个字段是必填的
     */
    public static void start(String mixSongId) {
        if (!isUsable(mixSongId)) return;
        send(buildStartFields(mixSongId), mixSongId, null);
    }

    /**
     * 发一次「结束播放」事件，时长挂在 {@code duration} 上。
     *
     * @param playedMillis 实际播放毫秒数；非正数直接放弃（没有时长的 end 事件没有意义）
     */
    public static void finish(String mixSongId, long playedMillis) {
        if (!isUsable(mixSongId) || playedMillis <= 0L) return;
        send(buildEndFields(mixSongId, playedMillis), mixSongId, playedMillis);
    }

    /** CSCC 需要登录态与一个能当 mixsongid 的数字 id。 */
    private static boolean isUsable(String mixSongId) {
        if (!KuGouDevice.isLoggedIn()) return false;
        return !KuGouSign.isBlank(mixSongId) && isPositiveInteger(mixSongId.trim());
    }

    /** 上报只认数字 id；非数字（例如 hash）传上去会被服务端判为无效事件。 */
    private static boolean isPositiveInteger(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return false;
        }
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    // ── 事件字段 ────────────────────────────────────────────────────────────

    private static List<String> buildStartFields(String mixSongId) {
        List<String> fields = new ArrayList<String>();
        fields.add("type_id=" + TYPE_ID_START);
        fields.add("action=play");
        fields.add("fo3=" + FO3);
        fields.add("spt=0");
        fields.add("sty=手动");
        fields.add("mixsongid=" + mixSongId);
        fields.add("source=46");
        fields.add("ivar1=1");
        fields.add("fo=/专辑播放页");
        return fields;
    }

    private static List<String> buildEndFields(String mixSongId, long playedMillis) {
        List<String> fields = new ArrayList<String>();
        fields.add("type_id=" + TYPE_ID_END);
        fields.add("action=play");
        fields.add("fo3=" + FO3);
        fields.add("duration=" + Math.min(Integer.MAX_VALUE, playedMillis));
        fields.add("svar3=0");
        fields.add("svar2=0");
        fields.add("fo=我的音乐/主态/自建歌单/RU");
        fields.add("sty=手动");
        fields.add("state=完整播放");
        fields.add("mixsongid=" + mixSongId);
        fields.add("source=46");
        fields.add("ivar3=0");
        fields.add("type=1");
        fields.add("fs=1.0");
        fields.add("ivar1=1");
        return fields;
    }

    /** 补齐每个事件都带的设备字段。顺序被上游忽略，但保持和参考报文一致便于对照。 */
    private static void appendDeviceFields(List<String> fields, String uuid) {
        fields.add("mid=" + KuGouDevice.mid());
        fields.add("uuid=" + uuid);
        fields.add("ss1=1");
        fields.add("ss2=1");
        fields.add("sys=" + SYSTEM_VERSION);
        fields.add("mod=" + DEVICE_MODEL);
        fields.add("channelid=18");
        fields.add("ip=0.0.0.0");
        fields.add("net=1");
        fields.add("ver=" + VERSION);
        fields.add("gitversion=7aa8a76");
        fields.add("time=" + System.currentTimeMillis());
        fields.add("userid=" + KuGouDevice.userId());
        fields.add("ss3=" + KuGouSign.md5(UUID.randomUUID().toString()));
    }

    // ── 主流程 ──────────────────────────────────────────────────────────────

    private static void send(List<String> eventFields, String mixSongId, Long playedMillis) {
        try {
            String uuid = uuid();
            if (uuid == null) return;
            Session session = session(uuid);
            if (session == null) return;

            appendDeviceFields(eventFields, uuid);
            Packed packed = packEvent(session, uuid, eventFields);
            if (packed == null) return;

            if (!post(session, packed)) {
                // 会话可能已失效。丢掉缓存，下一次上报会重新握手——但这次不重试。
                synchronized (LOCK) {
                    cached = null;
                }
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] CSCC 上报失败: " + throwable);
        }
    }

    /** 一次上报的成品：密文，以及上游用来校验解压正文的**明文长度**。 */
    private static final class Packed {
        final byte[] cipher;
        final int plainLength;

        Packed(byte[] cipher, int plainLength) {
            this.cipher = cipher;
            this.plainLength = plainLength;
        }
    }

    /** 把事件字段打成上游要的那层「两行头 + deflate 正文」再整体 AES 加密。 */
    private static Packed packEvent(Session session, String uuid, List<String> eventFields) {
        byte[] eventBuffer = join(eventFields).getBytes(StandardCharsets.UTF_8);
        byte[] deflatedEvent = deflate(eventBuffer);
        if (deflatedEvent == null) return null;

        String line1 = joinTab("4", "1", uuid, "0", "3116", VERSION, "18", SYSTEM_VERSION,
                DEVICE_MODEL, uuid, KuGouDevice.mid(), "0", "000000000000000000000000000000000000");
        String line2 = joinTab(String.valueOf(eventBuffer.length), "10048", "0",
                String.valueOf(System.currentTimeMillis() / 1000L), "1", KuGouDevice.userId(), "0", "0");

        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        byte[] header = (line1 + "\r\n" + line2 + "\r\n").getBytes(StandardCharsets.UTF_8);
        plain.write(header, 0, header.length);
        plain.write(deflatedEvent, 0, deflatedEvent.length);

        byte[] wholePlain = plain.toByteArray();
        byte[] deflatedPlain = deflate(wholePlain);
        if (deflatedPlain == null) return null;

        String key = KuGouSign.md5(uuid + session.clientTime + session.field2 + session.serverStr);
        byte[] cipher = KuGouCrypto.aesEncrypt(deflatedPlain, key, uuid.substring(0, 16));
        return cipher == null ? null : new Packed(cipher, wholePlain.length);
    }

    private static boolean post(Session session, Packed packed) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("cookie", session.cookie);
        query.put("length", String.valueOf(packed.plainLength));
        query.put("appid", "3116");
        query.put("_t", String.valueOf(System.currentTimeMillis() / 1000L));
        query.put("sign", KuGouSign.md5(concatSignatureBase(query), packed.cipher));

        String url = KuGouHttp.D_KUGOU + "/v2/post?" + encode(query);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("kg-rec", "1");
        headers.put("User-Agent", "Android" + SYSTEM_VERSION + "-1070-" + VERSION + "-18-0-CsccPost-wifi");

        byte[] response = KuGouHttp.requestBare("POST", url, headers,
                new String(packed.cipher, StandardCharsets.ISO_8859_1), "application/octet-stream");
        if (response == null) return false;
        JsonObject root = KuGouHttp.parseText(new String(response, StandardCharsets.UTF_8));
        if (root == null) return false;
        return integer(root, "status") == 1 && integer(root, "errcode") == 0;
    }

    /** {@code MD5(sorted(k+v) + APPKEY ‖ 密文)} —— 前缀拼的是**字节**，不能先转成字符串。 */
    private static byte[] concatSignatureBase(Map<String, String> query) {
        List<String> keys = new ArrayList<String>(query.keySet());
        keys.remove("sign");
        java.util.Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (String key : keys) builder.append(key).append(KuGouSign.nonNull(query.get(key)));
        builder.append(APPKEY);
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── 会话 ────────────────────────────────────────────────────────────────

    private static final class Session {
        final String cookie;
        final String clientTime;
        final String serverStr;
        /** 本次握手随机生成的 32 位十六进制；它同时是会话解密的 AES 密钥，也参与报文体密钥派生。 */
        final String field2;
        final long expiresAt;

        Session(String cookie, String clientTime, String serverStr, String field2, long expiresAt) {
            this.cookie = cookie;
            this.clientTime = clientTime;
            this.serverStr = serverStr;
            this.field2 = field2;
            this.expiresAt = expiresAt;
        }
    }

    /** 取一个可用会话；缓存过期或没有时重新握手。任何一步失败返回 {@code null}。 */
    private static Session session(String uuid) {
        synchronized (LOCK) {
            if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached;
            cached = handshake(uuid);
            return cached;
        }
    }

    private static Session handshake(String uuid) {
        String deviceId = queryDeviceId(uuid);
        if (deviceId == null) return null;

        String field2 = randomHex32();
        String encrypted = KuGouCrypto.rsaEncryptLiteBase64(
                uuid + "\t" + field2 + "\t" + System.currentTimeMillis() + "\t" + deviceId);
        if (KuGouSign.isBlank(encrypted)) return null;

        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("s", encrypted);
        query.put("appid", "3116");
        String time = String.valueOf(System.currentTimeMillis() / 1000L);
        query.put("_t", time);
        query.put("sign", KuGouSign.md5("_t" + time + "appid3116s" + encrypted + APPKEY));

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("kg-rec", "1");
        headers.put("User-Agent", "Android" + SYSTEM_VERSION + "-1070-" + VERSION + "-18-0-CsccGen-wifi");

        byte[] response = KuGouHttp.requestBare("GET",
                KuGouHttp.D_KUGOU + "/v2/gen?" + encode(query), headers, null, null);
        if (response == null) return null;
        JsonObject root = KuGouHttp.parseText(new String(response, StandardCharsets.UTF_8));
        if (root == null) return null;
        String data = text(root, "data");
        if (KuGouSign.isBlank(data)) return null;

        byte[] sessionBytes;
        try {
            sessionBytes = KuGouCrypto.aesDecrypt(Base64.getDecoder().decode(data.trim()),
                    field2, uuid.substring(0, 16));
        } catch (Throwable badBase64) {
            return null;
        }
        if (sessionBytes == null) return null;

        JsonObject session = KuGouHttp.parseText(new String(sessionBytes, StandardCharsets.UTF_8));
        if (session == null) return null;
        String cookie = text(session, "cookie");
        String clientTime = text(session, "clienttime");
        String serverStr = text(session, "serverstr");
        if (KuGouSign.isBlank(cookie) || KuGouSign.isBlank(clientTime) || KuGouSign.isBlank(serverStr)) {
            return null;
        }
        return new Session(cookie, clientTime, serverStr, field2,
                System.currentTimeMillis() + SESSION_TTL_MILLIS);
    }

    /** 第一步：换 {@code deviceid}。 */
    private static String queryDeviceId(String uuid) {
        String body = "{\"machine\":\"" + DEVICE_MODEL + "\",\"mid\":\"" + KuGouDevice.mid()
                + "\",\"uuid\":\"" + uuid + "\",\"wh\":[" + SCREEN_WIDTH + "," + SCREEN_HEIGHT + "]}";
        String time = String.valueOf(System.currentTimeMillis() / 1000L);
        String sign = KuGouSign.md5("_t" + time + "appidand02pbKC7zn{4U*ydo2M1Rir" + body);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("kg-rec", "1");
        headers.put("User-Agent", "Android" + SYSTEM_VERSION + "-1070-" + VERSION + "-18-0-CsccGen-wifi");

        byte[] response = KuGouHttp.requestBare("POST", KuGouHttp.D_KUGOU
                + "/v3/qrydid?appid=and02&_t=" + time + "&sign=" + sign, headers, body,
                "application/octet-stream");
        if (response == null) return null;
        JsonObject root = KuGouHttp.parseText(new String(response, StandardCharsets.UTF_8));
        if (root == null) return null;
        String deviceId = text(object(root, "data"), "deviceid");
        return KuGouSign.isBlank(deviceId) ? null : deviceId;
    }

    /** 桌面端没有真实的设备 GUID，用持久化的那份。 */
    private static String uuid() {
        String guid = KuGouDevice.guid();
        if (KuGouSign.isBlank(guid)) return null;
        String normalized = guid.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "");
        return normalized.length() == 32 ? normalized : null;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private static byte[] deflate(byte[] input) {
        try {
            Deflater deflater = new Deflater();
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(64, input.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                if (written <= 0) break;
                output.write(buffer, 0, written);
            }
            deflater.end();
            return output.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String randomHex32() {
        StringBuilder builder = new StringBuilder(32);
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        for (byte value : bytes) {
            String part = Integer.toHexString(value & 0xFF);
            if (part.length() == 1) builder.append('0');
            builder.append(part);
        }
        return builder.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) builder.append('&');
            builder.append(parts.get(index));
        }
        return builder.toString();
    }

    private static String joinTab(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) builder.append('\t');
            builder.append(parts[index]);
        }
        return builder.toString();
    }

    private static String encode(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) builder.append('&');
            builder.append(KuGouSign.urlEncode(entry.getKey()))
                    .append('=')
                    .append(KuGouSign.urlEncode(entry.getValue()));
        }
        return builder.toString();
    }

    private static JsonObject object(JsonObject root, String name) {
        if (root == null || !root.has(name) || !root.get(name).isJsonObject()) return null;
        return root.getAsJsonObject(name);
    }

    private static String text(JsonObject root, String name) {
        if (root == null || !root.has(name) || root.get(name).isJsonNull()) return "";
        try {
            return root.get(name).getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int integer(JsonObject root, String name) {
        if (root == null || !root.has(name) || root.get(name).isJsonNull()) return -1;
        try {
            return root.get(name).getAsInt();
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
