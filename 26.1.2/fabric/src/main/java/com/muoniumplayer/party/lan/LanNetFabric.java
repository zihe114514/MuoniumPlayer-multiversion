package com.muoniumplayer.party.lan;

import com.muoniumplayer.party.net.MpPayloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 内嵌引擎在 Fabric 26.2 / 26.3 上的桥接（官方名不混淆，注册表方法改叫
 * {@code serverboundPlay}/{@code clientboundPlay}，负载字段叫 {@code TYPE}）。
 *
 * <p>与 1.21.x 的 {@code LanNetFabric} 结构相同，差别只在 API 名。负载类型
 * 客户端栈已在 {@code MpNetFabric.register()} 里为发送权登记过；这里注册的是
 * 服务端方向的接收器，客户端 init 时注册无副作用 —— 集成服务器不存在时收不到
 * 任何帧，存在时（开局域网）这些 handler 就是内嵌引擎的入口。
 */
public final class LanNetFabric {

    private LanNetFabric() {
    }

    /** 客户端 init 时调用一次：注册服务端方向 C2S 接收器 + 挂生命周期。 */
    public static void register() {
        // C2S 的 codec 表由先跑的 MpNetFabric.register() 登记过（同一张 serverboundPlay
        // 注册表不允许重复登记，会直接抛 IllegalArgumentException 崩在启动），这里只挂接收器。
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.C2S.TYPE, (payload, context) ->
                LanMpParty.onC2sFrame(context.player().getUUID(), payload.data()));

        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                LanMpParty.onServerStarting(isIntegrated(server), platform(server), configDir()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LanMpParty.onServerStopped());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                LanMpParty.onPlayerJoin(handler.getPlayer().getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LanMpParty.onPlayerQuit(handler.getPlayer().getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server -> LanMpParty.tick());
    }

    private static boolean isIntegrated(MinecraftServer server) {
        try {
            return server == net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        } catch (Throwable t) {
            LanMpParty.LanLog.warn("[muonium-lan] 判定集成服务端失败，引擎不激活", t);
            return false;
        }
    }

    private static LanMpPlatform platform(MinecraftServer server) {
        return new LanMpPlatform(server,
                LanNetFabric::canSendS2c,
                LanNetFabric::sendS2c);
    }

    private static boolean canSendS2c(ServerPlayer p) {
        try {
            return ServerPlayNetworking.canSend(p, MpPayloads.S2C.TYPE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sendS2c(ServerPlayer p, byte[] payload) {
        try {
            ServerPlayNetworking.send(p, new MpPayloads.S2C(payload));
        } catch (Throwable t) {
            LanMpParty.LanLog.warn("[muonium-lan] S2C 发送失败", t);
        }
    }

    private static java.nio.file.Path configDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("muonium");
    }
}
