package yun.shan.mpsync.room;

/**
 * 房间的种类。
 *
 * <p>{@link #CHANNEL} 是全服唯一的公开大房间（「服务器频道」）：没有房主，谁都能进，谁都能点歌，
 * 歌单由<b>服务器管理员</b>管，切歌和踢人靠投票而不是靠某个人的权限。它和普通房间共用同一套
 * 播放引擎（时钟、队列、直链补给）—— 只有「谁说得上话」这件事不同。
 */
public enum RoomKind {
    ROOM,
    CHANNEL;

    public boolean channel() { return this == CHANNEL; }

    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
}