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
}
