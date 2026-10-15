package com.muoniumplayer.party.lan;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 内嵌引擎的 {@link MpPlatform}：Mojang 映射，覆盖全部五棵树
 * （Fabric 各版本 mojmap、1.20.1 Forge、NeoForge 1.21.1+ / 26.x）。
 *
 * <p>对应服务端仓的 {@code FabricPlatform} / {@code ForgePlatform} /
 * {@code NeoForgePlatform} —— 三份几乎相同的代码在这边合成一份，因为客户端仓
 * 五棵树共用 Mojang 名。两个真正版本相关的操作（能不能发、怎么发）由桥接层注入，
 * 与服务端仓「平台类收 Predicate/BiConsumer」的形状一致。
 */
public final class LanMpPlatform implements MpPlatform {

    private final MinecraftServer server;
    private final java.util.function.Predicate<ServerPlayer> canSend;
    private final java.util.function.BiConsumer<ServerPlayer, byte[]> sender;

    public LanMpPlatform(MinecraftServer server,
                         java.util.function.Predicate<ServerPlayer> canSend,
                         java.util.function.BiConsumer<ServerPlayer, byte[]> sender) {
        this.server = server;
        this.canSend = canSend;
        this.sender = sender;
    }

    private ServerPlayer player(UUID uuid) {
        if (server == null || uuid == null) return null;
        return server.getPlayerList().getPlayer(uuid);
    }

    @Override
    public String nameOf(UUID uuid) {
        ServerPlayer p = player(uuid);
        return p == null ? null : p.getName().getString();
    }

    @Override
    public UUID uuidByName(String name) {
        if (server == null || name == null || name.isBlank()) return null;
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p == null ? null : p.getUUID();
    }

    @Override
    public Collection<UUID> onlinePlayers() {
        List<UUID> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) out.add(p.getUUID());
        return out;
    }

    @Override
    public boolean canSend(UUID uuid) {
        ServerPlayer p = player(uuid);
        if (p == null) return false;
        try {
            return canSend.test(p);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void sendRaw(UUID uuid, byte[] payload) {
        ServerPlayer p = player(uuid);
        if (p == null || payload == null) return;
        sender.accept(p, payload);
    }

    @Override
    public void chat(UUID uuid, String text) {
        ServerPlayer p = player(uuid);
        if (p != null) p.sendSystemMessage(Component.literal(text));
    }

    @Override
    public void execute(Runnable task) {
        if (server == null) task.run();
        else server.execute(task);
    }

    @Override
    public void warn(String message, Throwable t) {
        LanMpParty.LanLog.warn(message, t);
    }
}
