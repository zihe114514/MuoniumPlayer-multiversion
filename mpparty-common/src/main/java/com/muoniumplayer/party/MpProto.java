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

    /**
     * 只有破坏性变更才 +1；追加字段不改版本号。
     *
     * <p>v2 的新帧全是追加式的，老服务端照样能跑；这个数字之所以还是要 +1，是给客户端一个
     * 「这台服务端认不认识在线态 / 公开房 / 频道 / 投票」的判据 —— 界面要靠它决定那几页显不显示。
     */
    public static final int VERSION = 2;

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
    /**
     * 整张歌单灌进房间队列，服务端把这一批全部落在<b>最低优先级档</b>（排在插队与排队歌之后）。
     *
     * <p>负载：{@code playlist}（歌单名）+ {@code tracks}（曲目对象数组，形状同 {@link #C_ENQUEUE}
     * 的 {@code track}，但<b>不带</b> {@code url}）。与服务端 {@code Proto.C_ENQUEUE_BATCH} 同名同形。</p>
     *
     * <p>为什么要单独一帧，而不是循环发 {@link #C_ENQUEUE}：服务端每收到一条点歌就重发<b>整份</b>
     * 歌单快照，还会给每条没有直链的曲目当场向全房要一次链接。一首一首点时那无所谓，一次灌一百首
     * 就是上百帧全量快照加一百次真取流 —— 所以批量在服务端只做一次广播，链接交给它的前瞻窗按需问。</p>
     *
     * <p>追加式改动：老服务端不认识这个 op，只会回一句「操作失败」，所以客户端要靠
     * {@code hello.playlistBatchLimit} 判断能不能发（见 {@link MpPartyClient#canPlaylist()}）。</p>
     */
    public static final String C_ENQUEUE_BATCH = "enqueue_batch";
    /**
     * 撤回某张歌单<b>还没播</b>的那批（{@code playlist} = 当初灌进来时带的那个名字）。
     *
     * <p>它是 {@link #C_ENQUEUE_BATCH} 的退路。少了这一帧，灌完 100 首想收回来只剩两个选择：
     * 一首一首点「移除」（一百次全量广播），或者「清空歌单」把别人排的歌一起清掉 —— 两个都不是
     * 用户按「播放歌单」时想干的事。服务端会再按 requester 判一次权，普通成员撤不动别人的批次。</p>
     */
    public static final String C_CLEAR_PLAYLIST = "clear_playlist";
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
    /**
     * 对 {@link #S_URL_NEEDED} 的<b>弃权</b>：「这一层我不答，别再等我的窗口」。
     *
     * <p>负载：{@code entryId} + {@code tier}（必须是服务端当前那一层，对不上服务端按过期帧丢弃）
     * + {@code source}/{@code songId} + {@code reason}。与服务端 {@code Proto.C_URL_DECLINE} 同名同形。</p>
     *
     * <p>为什么不能像以前那样「解不出就闭嘴」：以前全房并行竞速，沉默只是没人理你；
     * 现在服务端按层串行问人，被点名的这一层里沉默等于让整房白等一个层窗口。
     * 老服务端不认识这一帧，会回一句「不支持的操作」，所以只在收到过带 {@code entryId}/{@code tier}
     * 的新帧之后才发（见 {@code MpPartyClient#declineUrl}）。</p>
     */
    public static final String C_URL_DECLINE = "url_decline";
    /**
     * 跨平台救援的答卷：把队列里那一格换成<b>另一平台的同一首歌</b>。
     *
     * <p>负载 {@code entryId} + {@code track}（形状同 {@link #C_ENQUEUE}）。服务端只认这首歌的
     * <b>点歌人</b>（或管理员）发来的替换，位置、档位与点歌人都保持不变，换完按新平台重新走一遍
     * 分层。判据由 {@link #S_URL_NEEDED} 的 {@code mine} 与 {@code tier} 提供，别猜。</p>
     */
    public static final String C_TRACK_REPLACE = "track_replace";

    /**
     * {@link #S_SWITCH_RESCUE} 的<b>弃权</b>：「我这台换不到，别等我的窗，去问下一台」。
     *
     * <p>负载 {@code entryId} + {@code reason}（只进服务端日志）。服务端只在「这一格当前派的就是
     * 本机」时才改派，所以迟到的、或者自己压根没被派过的弃权都会被丢掉 —— 发多了不会改坏状态。
     * 不发这一帧的代价是实打实的：服务端要为这台白等满一个救援窗（默认 12 秒）才换下一台，
     * 而本机其实一开始就知道自己做不了（开关关着、这首歌刚搜过一遍）。</p>
     */
    public static final String C_SWITCH_DECLINE = "switch_decline";

    // ── v2 新增 C2S ──────────────────────────────────────────────────────────
    /** 自报身份：客户端名/版本、能解析哪些平台、是否愿意贡献会员解析能力。 */
    public static final String C_PROFILE        = "profile";
    /** 请求一份全服在线快照（谁在线、在听什么）。 */
    public static final String C_PRESENCE       = "presence";
    /** 请求公开房间列表。 */
    public static final String C_ROOMS          = "rooms";
    /** 把自己的房间设为公开 / 私密。 */
    public static final String C_SET_PUBLIC     = "set_public";
    /** 请求待处理邀请列表。 */
    public static final String C_INVITES        = "invites";
    public static final String C_INVITE_ACCEPT  = "invite_accept";
    public static final String C_INVITE_DECLINE = "invite_decline";
    /** 个人暂停 / 恢复：只静音自己，房间时钟照走。 */
    public static final String C_SELF_PAUSE     = "self_pause";
    /** 加入服务器频道（全服公开大房间）。 */
    public static final String C_CHANNEL_JOIN   = "channel_join";
    /** 发起投票：{@code kind} = {@link #VOTE_SKIP} / {@link #VOTE_KICK}。 */
    public static final String C_VOTE_START     = "vote_start";
    /** 对进行中的投票表态。 */
    public static final String C_VOTE_CAST      = "vote_cast";
    /**
     * 上报交接计划：当前这一首放完时怎么交给下一首。
     *
     * <p>分析只能在客户端做（音频在各机本地），但「唱到第几毫秒」必须全房统一，所以由
     * <b>服务端指派的那一台</b>算一份、服务端存下并广播，其余成员直接执行。
     * 字段见 {@code RoomAutomix#encode}。</p>
     *
     * <p>上报者不再是房主：服务端按「点歌人 → 房主 → 任何能算的人」指派
     * （见 {@link #S_ANALYZE_ASSIGN}），被指派者用本帧交卷。</p>
     */
    public static final String C_AUTOMIX_PLAN   = "automix_plan";
    /** 被指派人放弃这次分析，请服务端当场改派下一台（不必干等到超时）。 */
    public static final String C_ANALYZE_DECLINE = "analyze_decline";

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
    /**
     * 服务端向本机重新要一条直链。字段见 {@code yun.shan.mpsync.net.Proto.S_URL_NEEDED}：
     * {@code source}/{@code songId}/{@code name} + {@code entryId}/{@code tier}/
     * {@code deadlineMs}/{@code mine}。后四个都是追加式的，老服务端一个都不发。
     */
    public static final String S_URL_NEEDED = "url_needed";
    /**
     * 全房没人答得上这一首，服务端单播<b>问点歌人</b>：能不能换到别的平台的同一首歌。
     *
     * <p>负载：{@code entryId} + 原曲目的 {@code source}/{@code songId}/{@code name}/
     * {@code artist}/{@code duration} + {@code deadlineMs}。答卷是 {@link #C_TRACK_REPLACE}；
     * 换不到就什么都不发，服务端在总闸到点时跳过这首歌。</p>
     */
    public static final String S_SWITCH_RESCUE = "switch_rescue";
    /**
     * 服务端指派本机去算这一首的交接计划。
     *
     * <p>{@code entryId} = 要算的是哪一首「下一首」，{@code attempt} = 第几轮指派，
     * {@code deadlineMs} = 这个时刻前不交卷就改派下一台；{@code 0} = 没设期限（服务端已取消超时），
     * 那就一直算下去，不被「等太久」打断。</p>
     */
    public static final String S_ANALYZE_ASSIGN = "analyze_assign";
    /**
     * 服务端告诉全房「这一首由谁在算」。
     *
     * <p>{@code analyst} 为空串表示房间里没有任何一台能算 —— 本机据此不再挂着等计划。
     * 其余情况本机也能知道该不该自己动手：{@code analyst} 等于自己的 UUID 就是被指派了。</p>
     */
    public static final String S_ANALYZE_STATE  = "analyze_state";

    // ── v2 新增 S2C ──────────────────────────────────────────────────────────
    /** 全服在线玩家 + 正在听 + 客户端角标 + VIP。 */
    public static final String S_PRESENCE   = "presence";
    /** 公开房间列表（含服务器频道）。 */
    public static final String S_ROOMS      = "rooms";
    /** 待处理邀请列表。v2 起邀请不再写聊天栏。 */
    public static final String S_INVITES    = "invites";
    /** 进行中／刚结束的投票状态与倒计时。 */
    public static final String S_VOTE       = "vote";
    /** 封禁通知：原因 + 剩余时长。 */
    public static final String S_BANNED     = "banned";

    // ── 枚举式令牌 ───────────────────────────────────────────────────────────

    /** 房间类型。频道是全服共用的大房间，没有房主，按服务器管理员判权。 */
    public static final String KIND_ROOM    = "room";
    public static final String KIND_CHANNEL = "channel";

    /** 投票类型。 */
    public static final String VOTE_SKIP = "skip";
    public static final String VOTE_KICK = "kick";

    /**
     * {@code url_needed.tier}：服务端正在问的是哪一层，与服务端 {@code Room.ASK_*} 一一对应。
     *
     * <p>层号就是<b>可信度顺序</b>：越小说明答上来的那条链越稳（真会员 &gt; 贡献的脚本 &gt;
     * 只登录的平台 &gt; 点歌人自己 &gt; 装了该平台的 &gt; 全房）。客户端用它做两件事：
     * 弃权时把层号原样带回去（对不上服务端的当前层就是过期帧），以及判断「这一层值不值得我
     * 跑一次跨平台搜索」——只有 {@link #TIER_REQUESTER} 或 {@code mine} 为真时换来的版本
     * 才发得动 {@link #C_TRACK_REPLACE}。</p>
     *
     * <p>{@link #TIER_UNKNOWN} 不是协议里的值，是客户端给「老服务端压根不发 tier」准备的哨兵：
     * 见到它就什么都别追加，行为与改造前逐字一致。</p>
     */
    public static final int TIER_SERVER_TOKEN = 0;
    public static final int TIER_MEMBER = 1;
    public static final int TIER_CONTRIBUTED_LX = 2;
    public static final int TIER_RESOLVABLE = 3;
    public static final int TIER_REQUESTER = 4;
    public static final int TIER_INSTALLED = 5;
    public static final int TIER_EVERYONE = 6;
    public static final int TIER_UNKNOWN = -1;

    /** 客户端令牌。服务端拿它决定成员列表里显示的角标（MP / Mu / ??）。 */
    public static final String CLIENT_ID = "muoniumplayer";
    /** 给玩家看的客户端名。 */
    public static final String CLIENT_NAME = "MuoniumPlayer";

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
     * <p>兼容没有 varint 头的裸 JSON（某些代理/协议转换会把负载原样递过来）：单看首字节并不
     * 可靠——长度为 123 的 varint 字节是 0x7B，恰好也是 '{'，所以带头的合法帧首字节也可能是
     * '{'。改用「试解析」区分：裸 JSON 能整段解析成功，而带头帧不能（给 '{...}' 对象前面补一个
     * 长度字节会变成 '{{...}'，永远不是合法 JSON），因此这里解析失败就意味着「落回 varint 分帧」。
     *
     * @return 解析结果；帧不合法时返回 {@code null}
     */
    public static JsonObject decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            if (data[0] == '{') {
                JsonObject bare = tryParse(new String(data, StandardCharsets.UTF_8));
                if (bare != null) return bare;
            }
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

    /** 不抛异常的解析：负载不合法时返回 {@code null}。 */
    private static JsonObject tryParse(String json) {
        try {
            return parse(json);
        } catch (Throwable ignored) {
            return null;
        }
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
