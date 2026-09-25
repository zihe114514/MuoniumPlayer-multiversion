package com.muoniumplayer.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 服务端报告的一名房间成员。 */
public final class MpMember {

    public final UUID uuid;
    public final String name;
    /** {@code owner} / {@code admin} / {@code member}。 */
    public final String role;
    public final boolean online;
    public final boolean listening;

    // ── v2 ──────────────────────────────────────────────────────────────────
    /** 他自己按了「个人暂停」：只静音他，房间时钟照走。 */
    public final boolean selfPaused;
    /** 客户端令牌（{@code melodify} / {@code muoniumplayer}）；空 = 没上报过。 */
    public final String mod;
    /** 客户端展示名，服务端已翻译；没上报的是「未知客户端」。 */
    public final String modName;
    /** 两字母角标：{@code MP} / {@code Mu} / {@code ??}。 */
    public final String modBadge;
    public final String modVersion;
    /**
     * 有没有已登录的音乐平台账号。
     *
     * <p>叫 VIP 是因为房间真正关心的是「这个人能不能把会员曲解析成完整音频」，
     * 而客户端能给出的最可靠信号就是「这个平台的会话在」。
     */
    public final boolean vip;
    /** 愿意替别人解析（贡献会员解析能力）。 */
    public final boolean contributeVip;
    /** 他能解析的平台令牌。 */
    public final List<String> vipSources;
    /** 他本地正在听的曲目（个人暂停或还没开起来时会和房间当前曲目不一样）。 */
    public final String nowPlaying;

    // ── v3 ──────────────────────────────────────────────────────────────────
    /** 真会员的平台令牌（协议里的 {@code member}）。没下发就是空表，不替他假设。 */
    public final List<String> memberSources;
    /** 不开会员也拿得到完整流的平台令牌（协议里的 {@code resolvable}）。 */
    public final List<String> resolvableSources;
    /**
     * 这条成员档案里<b>有没有</b> automix 那一档。
     *
     * <p>{@code automixCapable == false} 单独看有两种意思：「他报了自己算不了」和「他压根没报」。
     * 把后者画成「不支持」是骗人的，所以界面得先看这一格再决定画不画。</p>
     */
    public final boolean automixReported;
    /** 他能不能替房间算下一首的交接点。只由服务端下发的档案决定，界面不许自己探。 */
    public final boolean automixCapable;
    /** 能力档位令牌：{@code "model"} / {@code "dsp"}；没上报是空串。 */
    public final String automixEngine;

    private MpMember(UUID uuid, String name, String role, boolean online, boolean listening,
                     boolean selfPaused, String mod, String modName, String modBadge,
                     String modVersion, boolean vip, boolean contributeVip,
                     List<String> vipSources, String nowPlaying,
                     List<String> memberSources, List<String> resolvableSources,
                     boolean automixReported, boolean automixCapable, String automixEngine) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
        this.online = online;
        this.listening = listening;
        this.selfPaused = selfPaused;
        this.mod = mod;
        this.modName = modName;
        this.modBadge = modBadge;
        this.modVersion = modVersion;
        this.vip = vip;
        this.contributeVip = contributeVip;
        this.vipSources = Collections.unmodifiableList(new ArrayList<String>(vipSources));
        this.nowPlaying = nowPlaying;
        this.memberSources = Collections.unmodifiableList(new ArrayList<String>(memberSources));
        this.resolvableSources = Collections.unmodifiableList(new ArrayList<String>(resolvableSources));
        this.automixReported = automixReported;
        this.automixCapable = automixCapable;
        this.automixEngine = automixEngine == null ? "" : automixEngine;
    }

    public static MpMember fromJson(JsonObject o) {
        if (o == null) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(MpProto.str(o, "uuid", ""));
        } catch (Throwable ignored) {
            return null;
        }
        List<String> sources = strings(MpProto.arr(o, "vipSources"));
        return new MpMember(uuid,
                MpProto.str(o, "name", ""),
                MpProto.str(o, "role", "member"),
                MpProto.bool(o, "online", true),
                MpProto.bool(o, "listening", false),
                MpProto.bool(o, "selfPaused", false),
                MpProto.str(o, "mod", ""),
                MpProto.str(o, "modName", ""),
                MpProto.str(o, "modBadge", ""),
                MpProto.str(o, "modVersion", ""),
                MpProto.bool(o, "vip", false),
                MpProto.bool(o, "contributeVip", false),
                sources,
                MpProto.str(o, "nowPlaying", ""),
                strings(MpProto.arr(o, "member")),
                strings(MpProto.arr(o, "resolvable")),
                // 「没这一格」与「这一格是 false」必须分得开，所以先问 has 再取值。
                o.has("automixCapable") || o.has("automixEngine"),
                MpProto.bool(o, "automixCapable", false),
                MpProto.str(o, "automixEngine", ""));
    }

    /** 协议里的平台令牌表。不是数组（老服务端根本没发）就给空表，不猜一个出来。 */
    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        for (JsonElement e : a) {
            try {
                String s = e.getAsString();
                if (s != null && !s.isEmpty()) out.add(s);
            } catch (Throwable ignored) {
                // 表里混进非字符串的元素（服务端改了口径）就当没有，别把整条成员档案作废。
            }
        }
        return out;
    }

    /** 房主与管理员职权完全相同。 */
    public boolean controls() {
        return "owner".equals(role) || "admin".equals(role);
    }

    public String roleLabel() {
        if ("owner".equals(role)) return "房主";
        if ("admin".equals(role)) return "管理员";
        return "成员";
    }

    /** 角标；没上报过的给 {@code ??}，不替他编一个。 */
    public String badge() {
        return modBadge == null || modBadge.isEmpty() ? "??" : modBadge;
    }

    /** 「MuoniumPlayer 1.3.0」，鼠标悬停用。 */
    public String clientLabel() {
        String base = modName == null || modName.isEmpty() ? "未知客户端" : modName;
        if (modVersion == null || modVersion.isEmpty()) return base;
        return base + " " + modVersion;
    }

    /** 一行状态：离线 / 个人暂停 / 在听什么 / 未在听。 */
    public String stateLabel() {
        if (!online) return "离线";
        if (selfPaused) return "个人暂停";
        if (nowPlaying != null && !nowPlaying.isEmpty()) return nowPlaying;
        return listening ? "在听" : "未在听";
    }

    /** 「他能替房间解析哪些平台」，给 VIP 徽标的悬停提示。 */
    public String vipLabel() {
        if (!vip) return "没有可解析的平台账号";
        String platforms = vipSources.isEmpty() ? "未知平台" : String.join("、", vipSources);
        return contributeVip ? "可解析：" + platforms + "（已开启贡献）" : "可解析：" + platforms;
    }

    /**
     * automix 能力的两三个字标记；<b>空串表示「不知道」</b>，界面这一格就该什么都不画。
     *
     * <p>刻意不回落到「不支持」：老服务端根本不下发这几个字段，把没上报画成不支持，
     * 房主就会去嫌一个其实算得出来的玩家。</p>
     *
     * <p>这里的字全部来自服务端下发的档案，一次字符串比较而已 —— 这条是给渲染帧用的，
     * 绝不能顺手去问 {@code RoomAutomix} 本机能不能算。</p>
     */
    public String automixLabel() {
        if (!automixReported) return "";
        if (!automixCapable) return "不支持";
        if ("model".equals(automixEngine)) return "模型";
        if ("dsp".equals(automixEngine)) return "信号处理";
        // 服务端认识而这里没登记的档位令牌：至少他自报了能算，不替他编一档。
        return "能算";
    }
}
