package com.muoniumplayer.party.lan;

import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 内嵌引擎在 Forge 1.20.1 上的桥接（mojmap + FML 事件）。
 *
 * <p>C2S 接收：客户端栈在 {@code MpNetForge} 里建过 {@code c2sChannel}
 * （{@code EventNetworkChannel}），由这里的 {@link #register()} 把服务端方向
 * 的监听加到同一条通道上（收 C2S 包走 {@code ClientCustomPayloadEvent}，
 * 按「包的来向」命名，见 MpNetForge 类注释的方向表）。
 * 引擎未激活时监听里第一行就返回。
 */
public final class LanNetForge {

    private LanNetForge() {
    }

    /** 由 1.20.1 Forge 入口在 {@code MpNetForge.register()} 之后调用。 */
    public static void register() {
        com.muoniumplayer.party.net.MpNetForge.addServerListener(LanNetForge::onC2s);

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(LanNetForge::onServerStarting);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(LanNetForge::onServerStopped);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(LanNetForge::onPlayerLoggedIn);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(LanNetForge::onPlayerLoggedOut);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(LanNetForge::onServerTick);
    }

    private static void onC2s(ServerPlayer sender, byte[] data) {
        LanMpParty.onC2sFrame(sender.getUUID(), data);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        LanMpParty.onServerStarting(isIntegrated(event.getServer()),
                new LanMpPlatform(event.getServer(),
                        p -> p != null && p.connection != null,
                        LanNetForge::sendS2c),
                FMLPaths.CONFIGDIR.get().resolve("muonium"));
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

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) LanMpParty.tick();
    }

    private static boolean isIntegrated(MinecraftServer server) {
        try {
            return server == net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        } catch (Throwable t) {
            LanMpParty.LanLog.warn("[muonium-lan] 判定集成服务端失败，引擎不激活", t);
            return false;
        }
    }

    private static void sendS2c(ServerPlayer p, byte[] payload) {
        try {
            p.connection.send(new ClientboundCustomPayloadPacket(
                    new ResourceLocation(com.muoniumplayer.party.MpProto.NAMESPACE,
                            com.muoniumplayer.party.MpProto.S2C_PATH),
                    new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload))));
        } catch (Throwable t) {
            LanMpParty.LanLog.warn("[muonium-lan] S2C 发送失败", t);
        }
    }
}
