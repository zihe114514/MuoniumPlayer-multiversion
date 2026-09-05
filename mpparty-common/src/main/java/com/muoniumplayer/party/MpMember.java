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

    private MpMember(UUID uuid, String name, String role, boolean online, boolean listening,
                     boolean selfPaused, String mod, String modName, String modBadge,
                     String modVersion, boolean vip, boolean contributeVip,
                     List<String> vipSources, String nowPlaying) {
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
    }

    public static MpMember fromJson(JsonObject o) {
        if (o == null) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(MpProto.str(o, "uuid", ""));
        } catch (Throwable ignored) {
            return null;
        }
        List<String> sources = new ArrayList<String>();
        JsonArray arr = MpProto.arr(o, "vipSources");
        if (arr != null) {
            for (JsonElement e : arr) {
                try {
                    String s = e.getAsString();
                    if (s != null && !s.isEmpty()) sources.add(s);
                } catch (Throwable ignored) {
                }
            }
        }
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
                MpProto.str(o, "nowPlaying", ""));
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
}
