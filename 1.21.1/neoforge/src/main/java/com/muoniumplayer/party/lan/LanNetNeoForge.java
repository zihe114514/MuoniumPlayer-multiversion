package com.muoniumplayer.party.lan;

import com.muoniumplayer.party.net.MpPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 内嵌引擎在 NeoForge（1.21.1 / 1.21.11 / 26.2 / 26.3）上的桥接。
 *
 * <p>C2S 的接收不新注册负载 —— 客户端栈在 {@code MpNetNeoForge} 里已经把
 * C2S 以 {@code playToServer} + 空 handler 的形状登记过了（登记是为了取得发送权）。
 * 这里做的事是把那个空 handler 换成有条件的真实入口：{@link #wire()} 由客户端
 * 入口在 {@code MpNetNeoForge.register} 之后调用，把 {@link LanMpParty#onC2sFrame}
 * 装进一个静态槽位，{@code MpNetNeoForge} 的 handler 改为查这个槽位。
 *
 * <p>所以本类的 C2S 部分是「被动」的：真正的分发点在 MpNetNeoForge 的 handler 里，
 * 那里必须先判 {@link LanMpParty#isActive()} 再转发（连远程服时槽位为空、引擎未激活，
 * 帧按原样忽略）。
 */
public final class LanNetNeoForge {

    private LanNetNeoForge() {
    }

    /** C2S 入口槽位：MpNetNeoForge 的 playToServer handler 在引擎激活时把帧送到这里。 */
    static volatile java.util.function.BiConsumer<java.util.UUID, byte[]> c2sSink;

    /** 客户端入口调用一次：接通 C2S 槽位 + 挂集成服务端生命周期事件。 */
    public static void register() {
        c2sSink = LanMpParty::onC2sFrame;

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(LanNetNeoForge::onServerStarting);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(LanNetNeoForge::onServerStopped);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(LanNetNeoForge::onPlayerLoggedIn);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(LanNetNeoForge::onPlayerLoggedOut);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(LanNetNeoForge::onServerTick);
    }

    /** 供 MpNetNeoForge 的 playToServer handler 调用；引擎未激活时返回 false（帧被忽略）。 */
    public static boolean dispatchC2s(java.util.UUID sender, byte[] payload) {
        if (!LanMpParty.isActive()) return false;
        java.util.function.BiConsumer<java.util.UUID, byte[]> sink = c2sSink;
        if (sink == null) return false;
        sink.accept(sender, payload);
        return true;
    }

    private static void onServerStarting(ServerStartingEvent event) {
        LanMpParty.onServerStarting(isIntegrated(event.getServer()),
                new LanMpPlatform(event.getServer(),
                        p -> canSendS2c(p),
                        (p, payload) -> sendS2c(p, payload)),
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("muonium"));
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        LanMpParty.onServerStopped();
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            LanMpParty.onPlayerJoin(sp.getUUID());
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            LanMpParty.onPlayerQuit(sp.getUUID());
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        LanMpParty.tick();
    }

    private static boolean isIntegrated(MinecraftServer server) {
        try {
            return server == net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        } catch (Throwable t) {
            LanMpParty.LanLog.warn("[muonium-lan] 判定集成服务端失败，引擎不激活", t);
            return false;
        }
    }

    private static boolean canSendS2c(ServerPlayer p) {
        try {
            // NeoForge 的通道协商是按连接算的；S2C 在客户端栈里以 optional 登记过，
            // 能拿到 ServerPlayer 就能发（失败会在 send 侧兜住）。
            return p != null && p.connection != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sendS2c(ServerPlayer p, byte[] payload) {
        try {
            PacketDistributor.sendToPlayer(p, new MpPayloads.S2C(payload));
        } catch (Throwable t) {
            LanMpParty.LanLog.warn("[muonium-lan] S2C 发送失败", t);
        }
    }

}
