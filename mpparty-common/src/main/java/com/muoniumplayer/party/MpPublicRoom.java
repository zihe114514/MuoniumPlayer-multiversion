package com.muoniumplayer.party;

import com.google.gson.JsonObject;

/**
 * 公开房间列表里的一条（{@link MpProto#S_ROOMS}）。
 *
 * <p>服务端刻意不在这份数据里带直链和完整歌单：它会发给房间外的所有人，他们只需要判断
 * 「这个房间在放什么、有多少人、要不要进」。
 */
public final class MpPublicRoom {

    public final String id;
    public final String code;
    public final String name;
    /** {@link MpProto#KIND_ROOM} / {@link MpProto#KIND_CHANNEL}。 */
    public final String kind;
    public final boolean channel;
    /** 房主名；频道没有房主，服务端给的是「服务器」。 */
    public final String ownerName;
    public final int members;
    public final int listeners;
    public final int queue;
    public final boolean playing;
    public final String nowPlaying;
    public final boolean full;

    private MpPublicRoom(String id, String code, String name, String kind, boolean channel,
                         String ownerName, int members, int listeners, int queue,
                         boolean playing, String nowPlaying, boolean full) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.channel = channel;
        this.ownerName = ownerName;
        this.members = members;
        this.listeners = listeners;
        this.queue = queue;
        this.playing = playing;
        this.nowPlaying = nowPlaying;
        this.full = full;
    }

    public static MpPublicRoom fromJson(JsonObject o) {
        if (o == null) return null;
        return new MpPublicRoom(
                MpProto.str(o, "id", ""),
                MpProto.str(o, "code", ""),
                MpProto.str(o, "name", "一起听"),
                MpProto.str(o, "kind", MpProto.KIND_ROOM),
                MpProto.bool(o, "channel", false),
                MpProto.str(o, "ownerName", ""),
                (int) MpProto.num(o, "members", 0L),
                (int) MpProto.num(o, "listeners", 0L),
                (int) MpProto.num(o, "queue", 0L),
                MpProto.bool(o, "playing", false),
                MpProto.str(o, "nowPlaying", ""),
                MpProto.bool(o, "full", false));
    }

    public String kindLabel() {
        return channel ? "服务器频道" : "公开房间";
    }

    /** 一行副标题：房主 · 人数 · 歌单长度。 */
    public String metaLabel() {
        StringBuilder sb = new StringBuilder();
        if (ownerName != null && !ownerName.isEmpty()) sb.append(ownerName).append(" · ");
        sb.append(members).append(" 人");
        if (listeners != members) sb.append("（在听 ").append(listeners).append("）");
        if (queue > 0) sb.append(" · 歌单 ").append(queue);
        if (full) sb.append(" · 已满");
        return sb.toString();
    }

    public String nowLabel() {
        if (nowPlaying == null || nowPlaying.isEmpty()) return "空闲";
        return (playing ? "正在放 " : "已暂停 ") + nowPlaying;
    }
}
