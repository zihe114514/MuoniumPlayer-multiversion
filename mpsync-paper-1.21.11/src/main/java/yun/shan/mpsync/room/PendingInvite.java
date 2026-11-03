package yun.shan.mpsync.room;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 一条待处理邀请。
 *
 * <p>为什么要独立于 {@code Room.invites}：房间里那份只是「这个人可以不用邀请码进来」的授权位，
 * 一个 UUID 加一个时间戳。而客户端要显示的是一张列表 —— 谁邀请的、哪个房间、什么时候、
 * 邀请码是什么，还要能一键接受或拒绝。需求明确要求邀请<b>不再往聊天框里塞</b>，那就必须有
 * 这份能被 GUI 渲染的数据。
 */
public final class PendingInvite {

    public final String roomId;
    public final String code;
    public final String roomName;
    public final UUID from;
    public final String fromName;
    public final long at = System.currentTimeMillis();

    public PendingInvite(String roomId, String code, String roomName, UUID from, String fromName) {
        this.roomId = roomId == null ? "" : roomId;
        this.code = code == null ? "" : code;
        this.roomName = roomName == null ? "" : roomName;
        this.from = from;
        this.fromName = fromName == null ? "" : fromName;
    }

    /** 邀请过期时间（毫秒）。太老的邀请留在列表里只会让人误点进一个早就散了的房间。 */
    public boolean expired(long ttlMs) {
        return ttlMs > 0L && System.currentTimeMillis() - at > ttlMs;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("roomId", roomId);
        o.addProperty("code", code);
        o.addProperty("roomName", roomName);
        o.addProperty("from", from == null ? "" : from.toString());
        o.addProperty("fromName", fromName);
        o.addProperty("at", at);
        return o;
    }
}