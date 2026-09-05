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

    // ── v2 ──────────────────────────────────────────────────────────────────
    /** {@link MpProto#KIND_ROOM} / {@link MpProto#KIND_CHANNEL}。 */
    public final String kind;
    /** 服务器频道：全服共用的大房间，没有房主，按服务器管理员判权。 */
    public final boolean channel;
    /** 公开房间：会出现在「发现」页，不用邀请码就能进。 */
    public final boolean open;
    /** 单个成员同时能挂在歌单上的曲目数（0 = 不限）。频道里是 3。 */
    public final int perMemberCap;
    /** 进行中／刚结束的投票。 */
    public final List<MpVote> votes;

    private MpRoom(String id, String code, String name, UUID owner, String repeat, int listeners,
                   List<MpMember> members, List<MpTrack> queue, List<MpTrack> history,
                   String kind, boolean channel, boolean open, int perMemberCap, List<MpVote> votes) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.owner = owner;
        this.repeat = repeat;
        this.listeners = listeners;
        this.members = Collections.unmodifiableList(new ArrayList<MpMember>(members));
        this.queue = Collections.unmodifiableList(new ArrayList<MpTrack>(queue));
        this.history = Collections.unmodifiableList(new ArrayList<MpTrack>(history));
        this.kind = kind;
        this.channel = channel;
        this.open = open;
        this.perMemberCap = perMemberCap;
        this.votes = Collections.unmodifiableList(new ArrayList<MpVote>(votes));
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
        String kind = MpProto.str(o, "kind", MpProto.KIND_ROOM);
        return new MpRoom(
                MpProto.str(o, "id", ""),
                MpProto.str(o, "code", ""),
                MpProto.str(o, "name", "一起听"),
                owner,
                MpProto.str(o, "repeat", "off"),
                (int) MpProto.num(o, "listeners", 0L),
                members,
                tracks(MpProto.arr(o, "queue")),
                tracks(MpProto.arr(o, "history")),
                kind,
                // channel 字段和 kind 各来一遍是服务端的冗余，取其一为真即可。
                MpProto.bool(o, "channel", MpProto.KIND_CHANNEL.equals(kind)),
                // 服务端用的键是 public（不是 open）。
                MpProto.bool(o, "public", false),
                (int) MpProto.num(o, "perMemberCap", 0L),
                votesOf(MpProto.arr(o, "votes")));
    }

    /** 只换歌单/历史（较轻的 {@code queue} 更新）。 */
    public MpRoom withQueue(JsonArray queueArr, JsonArray historyArr) {
        return new MpRoom(id, code, name, owner, repeat, listeners, members,
                queueArr == null ? queue : tracks(queueArr),
                historyArr == null ? history : tracks(historyArr),
                kind, channel, open, perMemberCap, votes);
    }

    /**
     * 只换投票列表。
     *
     * <p>{@code S_VOTE} 是单独一帧（投票期间每秒都会来更新倒计时），不该为它重建整个房间快照
     * —— 那会让成员列表和歌单每秒换一个新对象，界面上正在滚动的列表会跟着抖。
     */
    public MpRoom withVotes(List<MpVote> newVotes) {
        return new MpRoom(id, code, name, owner, repeat, listeners, members, queue, history,
                kind, channel, open, perMemberCap, newVotes);
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

    static List<MpVote> votesOf(JsonArray a) {
        List<MpVote> out = new ArrayList<MpVote>();
        if (a == null) return out;
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            MpVote v = MpVote.fromJson(e.getAsJsonObject());
            if (v != null) out.add(v);
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

    public String kindLabel() {
        if (channel) return "服务器频道";
        return open ? "公开房间" : "私密房间";
    }

    public int onlineCount() {
        int n = 0;
        for (MpMember m : members) {
            if (m.online) n++;
        }
        return n;
    }

    public int vipCount() {
        int n = 0;
        for (MpMember m : members) {
            if (m.vip) n++;
        }
        return n;
    }

    /** 第一个还没结束的投票；没有就返回 null。界面一次只展示一张投票卡。 */
    public MpVote openVote() {
        for (MpVote v : votes) {
            if (!v.closed) return v;
        }
        return votes.isEmpty() ? null : votes.get(votes.size() - 1);
    }
}
