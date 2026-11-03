package yun.shan.mpsync.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bukkit/Paper/Folia implementation of {@link MpPlatform}.
 *
 * <p><b>Folia threading model.</b> Folia splits the world into regions that tick in parallel;
 * there is no main thread, and a {@code Player}/{@code World} may only be touched from the
 * thread that owns it. This adapter therefore:
 * <ul>
 *   <li>keeps a thread-safe cache of online identities (updated from join/quit events, read
 *       from any thread) so it never has to query live {@code Player} objects off-thread;</li>
 *   <li>routes {@link #execute} onto the <em>global region scheduler</em> — the single
 *       logical thread the engine treats as its "main thread";</li>
 *   <li>performs every per-player operation ({@link #sendRaw}, {@link #chat}) on that
 *       player's own <em>entity scheduler</em>, the only thread allowed to touch them.</li>
 * </ul>
 */
public final class PaperPlatform implements MpPlatform {

    private final JavaPlugin plugin;

    /** uuid -> display name. Plugin-owned data: ordinary multithreading rules apply. */
    private final ConcurrentHashMap<UUID, String> online = new ConcurrentHashMap<>();

    private static final boolean HAS_GLOBAL_SCHEDULER = detectGlobalScheduler();

    public PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called from PlayerJoin/Quit (any region thread) to maintain the online cache. */
    public void noteOnline(UUID uuid, String name) {
        if (uuid != null && name != null) online.put(uuid, name);
    }

    /** Called from PlayerQuit (any region thread). */
    public void noteOffline(UUID uuid) {
        if (uuid != null) online.remove(uuid);
    }

    public List<String> onlineNames() {
        return new ArrayList<>(online.values());
    }

    private static boolean detectGlobalScheduler() {
        try {
            return Bukkit.class.getMethod("getGlobalRegionScheduler") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String nameOf(UUID uuid) {
        if (uuid == null) return null;
        String cached = online.get(uuid);
        if (cached != null) return cached;
        Player p = Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : null;
    }

    @Override
    public UUID uuidByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (var e : online.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        Player p = Bukkit.getPlayerExact(name);
        return p != null ? p.getUniqueId() : null;
    }

    @Override
    public Collection<UUID> onlinePlayers() {
        return new ArrayList<>(online.keySet());
    }

    @Override
    public boolean canSend(UUID uuid) {
        // Thread-safe hint only. The authoritative channel check runs on the entity thread
        // inside sendRaw, so we never read Player state from a foreign thread here.
        return uuid != null && online.containsKey(uuid);
    }

    @Override
    public void sendRaw(UUID uuid, byte[] payload) {
        if (uuid == null || payload == null) return;
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        // Hop onto the player's owning region thread before touching player state.
        p.getScheduler().run(plugin, task -> {
            if (p.getListeningPluginChannels().contains("mpsync:s2c")) {
                try {
                    p.sendPluginMessage(plugin, "mpsync:s2c", payload);
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.FINE, "[mpsync] send failed", t);
                }
            }
        }, null);
    }

    @Override
    public void chat(UUID uuid, String text) {
        if (uuid == null || text == null) return;
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        p.getScheduler().run(plugin, task -> p.sendMessage(Component.text(text)), null);
    }

    @Override
    public void execute(Runnable task) {
        if (HAS_GLOBAL_SCHEDULER) {
            // Folia / modern Paper: global region thread is the engine's single logical thread.
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void warn(String message, Throwable t) {
        plugin.getLogger().log(Level.WARNING, message, t);
    }
}
