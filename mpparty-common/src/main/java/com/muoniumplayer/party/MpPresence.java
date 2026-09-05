package com.muoniumplayer.party;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 全服在线态里的一名玩家（{@link MpProto#S_PRESENCE} 的一条）。
 *
 * <p>刻意和 {@link MpMember} 分开：在线态不限于自己所在的房间 —— 要先看得到「谁在听什么」，
 * 才能决定邀请谁。所以这份数据按玩家存在，进出房间都不影响。
 */
public final class MpPresence {

    public final UUID uuid;
    public final String name;
    /** 客户端令牌（{@code melodify} / {@code muoniumplayer} / 空）。 */
    public final String mod;
    /** 给玩家看的客户端名；服务端已经翻译过，没上报的是「未知客户端」。 */
    public final String modName;
    /** 两字母角标：{@code MP} / {@code Mu} / {@code ??}。 */
    public final String modBadge;
    public final String modVersion;
    /** 有没有已登录的音乐平台账号（= 能替房间解析会员曲）。 */
    public final boolean vip;
    public final boolean contributeVip;
    /** 正在听的曲目名；没在听是空串。 */
    public final String nowPlaying;
    public final String nowArtist;
    public final boolean localPlaying;
    /** 所在房间的名字；不在房间里是空串。 */
    public final String room;
    /** 有没有装支持一起听的播放器 mod。 */
    public final boolean hasClient;

    private MpPresence(UUID uuid, String name, String mod, String modName, String modBadge,
                       String modVersion, boolean vip, boolean contributeVip, String nowPlaying,
                       String nowArtist, boolean localPlaying, String room, boolean hasClient) {
        this.uuid = uuid;
        this.name = name;
        this.mod = mod;
        this.modName = modName;
        this.modBadge = modBadge;
        this.modVersion = modVersion;
        this.vip = vip;
        this.contributeVip = contributeVip;
        this.nowPlaying = nowPlaying;
        this.nowArtist = nowArtist;
        this.localPlaying = localPlaying;
        this.room = room;
        this.hasClient = hasClient;
    }

    public static MpPresence fromJson(JsonObject o) {
        if (o == null) return null;
        UUID uuid = null;
        try {
            uuid = UUID.fromString(MpProto.str(o, "uuid", ""));
        } catch (Throwable ignored) {
        }
        return new MpPresence(uuid,
                MpProto.str(o, "name", ""),
                MpProto.str(o, "mod", ""),
                MpProto.str(o, "modName", ""),
                MpProto.str(o, "modBadge", "??"),
                MpProto.str(o, "modVersion", ""),
                MpProto.bool(o, "vip", false),
                MpProto.bool(o, "contributeVip", false),
                MpProto.str(o, "nowPlaying", ""),
                MpProto.str(o, "nowArtist", ""),
                MpProto.bool(o, "localPlaying", false),
                MpProto.str(o, "room", ""),
                MpProto.bool(o, "hasClient", false));
    }

    /** 「MuoniumPlayer 1.3.0」；没上报过就只有客户端名。 */
    public String clientLabel() {
        String base = modName == null || modName.isEmpty() ? "未知客户端" : modName;
        if (modVersion == null || modVersion.isEmpty()) return base;
        return base + " " + modVersion;
    }

    /** 一行状态：在听什么 / 在哪个房间 / 空闲。 */
    public String stateLabel() {
        if (nowPlaying != null && !nowPlaying.isEmpty()) {
            String song = nowArtist == null || nowArtist.isEmpty()
                    ? nowPlaying : nowPlaying + " · " + nowArtist;
            return (localPlaying ? "正在听 " : "已暂停 ") + song;
        }
        if (room != null && !room.isEmpty()) return "在《" + room + "》";
        return hasClient ? "空闲" : "未装播放器";
    }
}
