package com.muoniumplayer.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/**
 * 一起听（MusicParty）线协议的客户端一份拷贝，必须与服务端 mod 的
 * {@code yun.shan.mpsync.net.Proto}（协议 v{@value #VERSION}）逐字节兼容。
 *
 * <p>一帧就是「varint 长度 + 一个 UTF-8 JSON 对象」。用 JSON 而不是紧凑二进制是刻意的：
 * 服务端 mod 与两个客户端播放器（Melodify / MuoniumPlayer）各自独立发版，
 * 未知字段必须能被忽略、新字段必须是追加式的。
 *
 * <p>本类不引用任何 Minecraft 类型，因此四个版本工程（1.20.1 / 1.21.1 / 1.21.11 / 26.2）
 * 共用同一份源码，版本差异只留在各自的 {@code party/net/MpNet.java} 里。
 */
public final class MpProto {

    private MpProto() {
    }

    /** 只有破坏性变更才 +1；追加字段不改版本号。 */
    public static final int VERSION = 1;

    public static final String NAMESPACE = "mpsync";
    public static final String C2S_PATH = "c2s";
    public static final String S2C_PATH = "s2c";

    /** 单帧上限（带长队列的房间快照也远低于此）。 */
    public static final int MAX_FRAME = 512 * 1024;

    /**
     * 平台令牌的缺省值。
     *
     * <p>协议里的 {@code source} 不是枚举而是自由令牌：服务端只转发点歌人自己解析出来的直链，
     * 它既不解析也不取流，所以不需要认识有哪些平台。缺省仍是 netease —— 老客户端和老服务端都
     * 不发这个字段。</p>
     */
    public static final String SOURCE_NETEASE = "netease";
    public static final String SOURCE_QQ = "qq";
    public static final String SOURCE_KUGOU = "kugou";
    public static final String SOURCE_GD = "gd";

    /** 与服务端 {@code Proto.isValidSource} 同款：小写字母、数字、下划线，1~16 位。 */
    public static boolean isValidSource(String source) {
        if (source == null || source.isEmpty() || source.length() > 16) return false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** 归一化成能上线的令牌；空的或不合法的一律退回 netease。 */
    public static String sourceOf(String raw) {
        if (raw == null) return SOURCE_NETEASE;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return isValidSource(s) ? s : SOURCE_NETEASE;
    }

    /** 曲目身份键，必须和服务端 {@code Track.key()} 一致。 */
    public static String trackKey(String source, String songId) {
        return sourceOf(source) + ":" + (songId == null ? "" : songId);
    }

    public static final String OP = "op";

    // ── 客户端 → 服务端 ──────────────────────────────────────────────────────
    public static final String C_HELLO      = "hello";
    public static final String C_CREATE     = "create_room";
    public static final String C_INVITE     = "invite";
    public static final String C_JOIN       = "join";
    public static final String C_LEAVE      = "leave";
    public static final String C_DISBAND    = "disband";
    public static final String C_ENQUEUE    = "enqueue";
    public static final String C_PLAY_NOW   = "play_now";
    public static final String C_PLAY_INDEX = "play_index";
    public static final String C_REMOVE     = "remove";
    public static final String C_MOVE       = "move";
    public static final String C_CLEAR      = "clear_queue";
    public static final String C_PAUSE      = "pause";
    public static final String C_RESUME     = "resume";
    public static final String C_SEEK       = "seek";
    public static final String C_NEXT       = "next";
    public static final String C_PREV       = "prev";
    public static final String C_REPEAT     = "repeat";
    public static final String C_SET_ROLE   = "set_role";
    public static final String C_KICK       = "kick";
    public static final String C_TRANSFER   = "transfer";
    public static final String C_PROGRESS   = "progress";
    public static final String C_SYNC       = "sync";
    public static final String C_URL_SUPPLY = "url_supply";

    // ── 服务端 → 客户端 ──────────────────────────────────────────────────────
    public static final String S_HELLO      = "hello";
    public static final String S_ROOM_STATE = "room_state";
    public static final String S_QUEUE      = "queue";
    public static final String S_MEMBERS    = "members";
    public static final String S_PLAY       = "play";
    public static final String S_PAUSE      = "pause";
    public static final String S_RESUME     = "resume";
    public static final String S_SEEK       = "seek";
    public static final String S_STOP       = "stop";
    public static final String S_TIME       = "time";
    public static final String S_INVITE     = "invite";
    public static final String S_LEFT       = "left";
    public static final String S_NOTICE     = "notice";
    public static final String S_ERROR      = "error";
    public static final String S_URL_NEEDED = "url_needed";

    public static JsonObject msg(String op) {
        JsonObject o = new JsonObject();
        o.addProperty(OP, op);
        return o;
    }

    /** varint 长度 + UTF-8 JSON。与服务端 {@code Proto.encode} 逐字节一致。 */
    public static byte[] encode(JsonObject payload) {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        byte[] head = varInt(bytes.length);
        byte[] out = new byte[head.length + bytes.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(bytes, 0, out, head.length, bytes.length);
        return out;
    }

    /**
     * 解一帧。
     *
     * <p>兼容没有 varint 头的裸 JSON（某些代理/协议转换会把负载原样递过来）：
     * 首字节是 '{' 时整个数组按 JSON 解析。
     *
     * @return 解析结果；帧不合法时返回 {@code null}
     */
    public static JsonObject decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            if (data[0] == '{') return parse(new String(data, StandardCharsets.UTF_8));
            int len = 0, shift = 0, i = 0;
            while (i < data.length) {
                byte b = data[i++];
                len |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) return null;
            }
            if (len <= 0 || len > MAX_FRAME || len > data.length - i) return null;
            return parse(new String(data, i, len, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject parse(String json) {
        JsonElement el = JsonParser.parseString(json);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /** Minecraft 风格 varint，手写以保证各平台产出同样的字节。 */
    private static byte[] varInt(int value) {
        byte[] tmp = new byte[5];
        int n = 0;
        int v = value;
        do {
            byte b = (byte) (v & 0x7F);
            v >>>= 7;
            if (v != 0) b |= (byte) 0x80;
            tmp[n++] = b;
        } while (v != 0);
        byte[] out = new byte[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    // ── 宽松取值（对端版本不同，任何字段都可能缺） ───────────────────────────

    public static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsString(); } catch (Throwable ignored) { return def; }
    }

    public static long num(JsonObject o, String key, long def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsLong(); } catch (Throwable ignored) { return def; }
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsBoolean(); } catch (Throwable ignored) { return def; }
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        return o.getAsJsonObject(key);
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        return o.getAsJsonArray(key);
    }
}
