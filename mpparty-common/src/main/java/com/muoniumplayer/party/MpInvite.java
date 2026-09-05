package com.muoniumplayer.party;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 一条待处理邀请（{@link MpProto#S_INVITES}）。
 *
 * <p>v2 起邀请<b>不再写聊天栏</b>：服务端把它挂在被邀请人名下，客户端在「邀请」页里处理。
 * 理由是聊天栏留不住 —— 一条邀请被后面的消息顶掉之后就找不回来了，而且邀请码要手打。
 */
public final class MpInvite {

    public final String roomId;
    public final String code;
    public final String roomName;
    public final UUID from;
    public final String fromName;
    /** 发出时间（服务端时钟）。 */
    public final long at;

    private MpInvite(String roomId, String code, String roomName, UUID from, String fromName, long at) {
        this.roomId = roomId;
        this.code = code;
        this.roomName = roomName;
        this.from = from;
        this.fromName = fromName;
        this.at = at;
    }

    public static MpInvite fromJson(JsonObject o) {
        if (o == null) return null;
        UUID from = null;
        try {
            from = UUID.fromString(MpProto.str(o, "from", ""));
        } catch (Throwable ignored) {
        }
        String roomId = MpProto.str(o, "roomId", "");
        if (roomId.isEmpty()) return null;
        return new MpInvite(roomId,
                MpProto.str(o, "code", ""),
                MpProto.str(o, "roomName", "一起听"),
                from,
                MpProto.str(o, "fromName", ""),
                MpProto.num(o, "at", 0L));
    }

    public String headline() {
        String who = fromName == null || fromName.isEmpty() ? "有人" : fromName;
        return who + " 邀请你加入《" + roomName + "》";
    }
}
