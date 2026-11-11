package yun.shan.mpsync.platform;

import java.util.Collection;
import java.util.UUID;

/**
 * Everything the version- and platform-independent room engine needs from the host
 * server. One implementation per target (Fabric 1.20.1 / 1.21.1 / 1.21.11 / 26.2,
 * Paper 1.20.1 / 1.21.1 / 1.21.11 / 26.2); nothing else in {@code mpsync} touches a
 * Minecraft or Bukkit class.
 *
 * <p>Only raw bytes cross this boundary: the wire frame ({@link yun.shan.mpsync.net.Proto})
 * is identical on every version, which is what lets a 1.20.1 client talk to a 1.21.1
 * server through ViaVersion — the adapter merely wraps the same bytes in whatever
 * custom-payload shape its Minecraft version wants.
 */
public interface MpPlatform {

    /** Display name of an online player, or {@code null} when they are offline/unknown. */
    String nameOf(UUID uuid);

    /** Online lookup by (exact, case-insensitive) name. */
    UUID uuidByName(String name);

    /** Every player currently online. */
    Collection<UUID> onlinePlayers();

    /**
     * Whether this player's connection advertised the S2C channel. Used as a hint only:
     * a client reached through a protocol translator may fail to advertise, so
     * {@link yun.shan.mpsync.net.Net} also treats "has sent us a frame" as capable.
     */
    boolean canSend(UUID uuid);

    /** Deliver one S2C frame. Must be a no-op when the player is gone. */
    void sendRaw(UUID uuid, byte[] payload);

    /** Plain chat line (the vanilla-visible mirror of a room event). */
    void chat(UUID uuid, String text);

    /** Run on the main/server thread — room state is single-threaded. */
    void execute(Runnable task);

    default void warn(String message, Throwable t) { }

    /**
     * 这个在线玩家是不是服务器管理员（可选实现）。
     *
     * <p>默认 false：那时 core 只能在他敲过一次 {@code /mp} 之后才认得他（见
     * {@code RoomManager#noteOperator}），而客户端只在握手时读一次 {@code hello.serverAdmin} ——
     * 握手发生在进服时、敲指令之前，于是频道里管理员的界面按钮（直接切歌、直接踢人…）一直
     * 不出现。能便宜拿到权限的平台（Paper）实现它，握手时下发的 serverAdmin 就是对的，客户端
     * 不用改。权限 API 各版本变过的加载器可以不实现，行为与以前一样。</p>
     */
    default boolean isOperator(UUID uuid) { return false; }
}
