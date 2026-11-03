package yun.shan.mpsync.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import yun.shan.mpsync.MpParty;

import java.util.UUID;

/**
 * Bukkit/Paper/Folia entry point for the server-side "一起听" (listen-together) engine.
 *
 * <p>Folia note: Folia has no single main thread. Every region ticks in parallel, so the
 * old {@code Bukkit.getScheduler().runTaskTimer(...)} contract is gone. This plugin drives
 * the engine from the <em>global region scheduler</em> instead — one logical thread that
 * replaces the legacy main thread for all room-state mutation. Player data is only ever
 * touched through {@link PaperPlatform}, which hops onto the owning entity's scheduler.
 */
public final class MusicPartyPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    private PaperPlatform platform;

    @Override
    public void onEnable() {
        this.platform = new PaperPlatform(this);
        MpParty.init(platform, this.getDataFolder().toPath().resolve("mpsync.json"));

        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "mpsync:s2c");
        this.getServer().getMessenger().registerIncomingPluginChannel(this, "mpsync:c2s", this);
        this.getServer().getPluginManager().registerEvents(this, this);

        // Folia/modern-Paper: drive the per-tick engine from the global region scheduler.
        // On legacy Spigot this falls back inside PaperPlatform.execute, but here we use the
        // region API directly because folia-supported: true guarantees it exists.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> MpParty.tick(), 1L, 1L);

        MpCommand cmd = new MpCommand(platform);
        if (this.getCommand("mp") != null) {
            this.getCommand("mp").setExecutor(cmd);
            this.getCommand("mp").setTabCompleter(cmd);
        }
        this.getLogger().info("MusicParty ready — protocol v2, channels mpsync:c2s / mpsync:s2c");
    }

    @Override
    public void onDisable() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this);
        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        MpParty.shutdown();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!"mpsync:c2s".equals(channel)) return;
        MpParty.onPacket(player.getUniqueId(), message);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        platform.noteOnline(uuid, p.getName());
        MpParty.onPlayerJoin(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        platform.noteOffline(uuid);
        MpParty.onPlayerQuit(uuid);
    }
}
