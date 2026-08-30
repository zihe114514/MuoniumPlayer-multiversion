package com.muoniumplayer.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 房间快照（{@code room_state}）的不可变客户端视图。
 *
 * <p>不可变是刻意的：渲染线程直接读 {@link MpPartyClient} 当前发布的那个实例，不加锁，
 * 中途来一帧更新也不会让界面遍历到「拆了一半」的列表。
 */
public final class MpRoom {

    public final String id;
    public final String code;
    public final String name;
    public final UUID owner;
    public final String repeat;
    public final int listeners;
    public final List<MpMember> members;
    /** 一起听歌单：待播的点歌请求，按播放顺序。 */
    public final List<MpTrack> queue;
    public final List<MpTrack> history;

    private MpRoom(String id, String code, String name, UUID owner, String repeat, int listeners,
                   List<MpMember> members, List<MpTrack> queue, List<MpTrack> history) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.owner = owner;
        this.repeat = repeat;
        this.listeners = listeners;
        this.members = Collections.unmodifiableList(new ArrayList<MpMember>(members));
        this.queue = Collections.unmodifiableList(new ArrayList<MpTrack>(queue));
        this.history = Collections.unmodifiableList(new ArrayList<MpTrack>(history));
    }

    public static MpRoom fromJson(JsonObject o) {
        if (o == null) return null;
        UUID owner = null;
        try {
            owner = UUID.fromString(MpProto.str(o, "owner", ""));
        } catch (Throwable ignored) {
        }
        List<MpMember> members = new ArrayList<MpMember>();
        JsonArray ma = MpProto.arr(o, "members");
        if (ma != null) {
            for (JsonElement e : ma) {
                if (!e.isJsonObject()) continue;
                MpMember m = MpMember.fromJson(e.getAsJsonObject());
                if (m != null) members.add(m);
            }
        }
        return new MpRoom(
                MpProto.str(o, "id", ""),
                MpProto.str(o, "code", ""),
                MpProto.str(o, "name", "一起听"),
                owner,
                MpProto.str(o, "repeat", "off"),
                (int) MpProto.num(o, "listeners", 0L),
                members,
                tracks(MpProto.arr(o, "queue")),
                tracks(MpProto.arr(o, "history")));
    }

    /** 只换歌单/历史（较轻的 {@code queue} 更新）。 */
    public MpRoom withQueue(JsonArray queueArr, JsonArray historyArr) {
        return new MpRoom(id, code, name, owner, repeat, listeners, members,
                queueArr == null ? queue : tracks(queueArr),
                historyArr == null ? history : tracks(historyArr));
    }

    static List<MpTrack> tracks(JsonArray a) {
        List<MpTrack> out = new ArrayList<MpTrack>();
        if (a == null) return out;
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            MpTrack t = MpTrack.fromJson(e.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    public MpMember member(UUID uuid) {
        if (uuid == null) return null;
        for (MpMember m : members) {
            if (m.uuid.equals(uuid)) return m;
        }
        return null;
    }

    public String repeatLabel() {
        if ("one".equals(repeat)) return "单曲循环";
        if ("all".equals(repeat)) return "列表循环";
        return "顺序播放";
    }
}
