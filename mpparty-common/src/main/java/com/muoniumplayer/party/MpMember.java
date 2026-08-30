package com.muoniumplayer.party;

import com.google.gson.JsonObject;

import java.util.UUID;

/** 服务端报告的一名房间成员。 */
public final class MpMember {

    public final UUID uuid;
    public final String name;
    /** {@code owner} / {@code admin} / {@code member}。 */
    public final String role;
    public final boolean online;
    public final boolean listening;

    private MpMember(UUID uuid, String name, String role, boolean online, boolean listening) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
        this.online = online;
        this.listening = listening;
    }

    public static MpMember fromJson(JsonObject o) {
        if (o == null) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(MpProto.str(o, "uuid", ""));
        } catch (Throwable ignored) {
            return null;
        }
        return new MpMember(uuid,
                MpProto.str(o, "name", ""),
                MpProto.str(o, "role", "member"),
                MpProto.bool(o, "online", true),
                MpProto.bool(o, "listening", false));
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
}
