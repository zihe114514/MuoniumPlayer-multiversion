package yun.shan.mpsync.net;

import com.google.gson.JsonObject;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single place that writes S2C frames, bound once to a {@link MpPlatform}.
 *
 * <p>Static on purpose: exactly one server exists per JVM, and the room engine calls this
 * from deep inside {@code Room} without threading a context object through every method.
 *
 * <h2>Cross-version delivery</h2>
 * A vanilla-protocol client announces the channels it listens on, and the platform's
 * {@code canSend} reflects that announcement. A client reached through a protocol
 * translator (a 1.20.1 player on a 1.21.x server) sometimes fails that check even though
 * its frames arrive fine, so any player we have <em>received</em> a frame from is
 * remembered as capable and is written to regardless.
 */
public final class Net {

    private Net() {}

    private static volatile MpPlatform platform;

    /** Players we have received a C2S frame from — proof the client mod is there. */
    private static final Set<UUID> SPOKEN = ConcurrentHashMap.newKeySet();

    public static void bind(MpPlatform p) {
        platform = p;
        SPOKEN.clear();
    }

    public static MpPlatform platform() { return platform; }

    /** Called by the adapter for every inbound frame. */
    public static void markCapable(UUID uuid) {
        if (uuid != null) SPOKEN.add(uuid);
    }

    public static void forget(UUID uuid) {
        if (uuid != null) SPOKEN.remove(uuid);
    }

    public static String nameOf(UUID uuid) {
        MpPlatform p = platform;
        String name = p == null || uuid == null ? null : p.nameOf(uuid);
        return name == null ? "玩家" : name;
    }

    public static UUID uuidByName(String name) {
        MpPlatform p = platform;
        return p == null || name == null || name.isBlank() ? null : p.uuidByName(name);
    }

    public static boolean canSend(UUID uuid) {
        MpPlatform p = platform;
        if (p == null || uuid == null) return false;
        if (SPOKEN.contains(uuid)) return true;
        try {
            return p.canSend(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void send(UUID uuid, JsonObject msg) {
        MpPlatform p = platform;
        if (p == null || uuid == null || msg == null || !canSend(uuid)) return;
        try {
            p.sendRaw(uuid, Proto.encode(msg));
        } catch (Throwable t) {
            p.warn("[mpsync] send failed", t);
        }
    }

    public static void notice(UUID uuid, String text) {
        JsonObject m = Proto.msg(Proto.S_NOTICE);
        m.addProperty("msg", text == null ? "" : text);
        send(uuid, m);
        chat(uuid, "[一起听] " + text);
    }

    public static void error(UUID uuid, String text) {
        String msg = text == null ? "操作失败" : text;
        JsonObject m = Proto.msg(Proto.S_ERROR);
        m.addProperty("msg", msg);
        send(uuid, m);
        chat(uuid, "[一起听] " + msg);
    }

    /** Chat mirror: a room stays usable (and debuggable) from a vanilla client. */
    public static void chat(UUID uuid, String text) {
        MpPlatform p = platform;
        if (p == null || uuid == null || text == null) return;
        try {
            p.chat(uuid, text);
        } catch (Throwable ignored) {
            // A player who left mid-broadcast is not an error.
        }
    }

    public static void chatAll(Collection<UUID> uuids, String text) {
        if (uuids == null) return;
        for (UUID uuid : uuids) chat(uuid, text);
    }

    public static void warn(String message, Throwable t) {
        MpPlatform p = platform;
        if (p != null) p.warn(message, t);
    }
}
