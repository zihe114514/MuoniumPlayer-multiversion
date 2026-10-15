package com.muoniumplayer.party.lan;

import com.muoniumplayer.party.MpProto;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * 内嵌引擎在 Fabric 1.20.1 上的桥接（裸 custom-payload 通道 API，mojmap）。
 *
 * <p>与 1.21+ 的 {@code LanNetFabric} 结构相同，差别只在负载形状：
 * 1.20.5 之前没有 {@code CustomPacketPayload}，C2S/S2C 都是
 * {@code ResourceLocation + FriendlyByteBuf}，注册表也按端天然分离 ——
 * 这也是唯一一棵「主机同装客户端 mod + 服务端 mod 不冲突」的树
 * （现在不需要同装了：本桥接就是主机的服务端栈）。
 *
 * <p>S2C 发送走原版 {@link ClientboundCustomPayloadPacket}，与服务端仓
 * 1.20.1fabric 的 {@code MpNet} 同款，避免 Fabric 再包一层。
 */
public final class LanNetFabric1201 {

    private LanNetFabric1201() {
    }

    /** 客户端 init 时调用一次：注册服务端方向接收器 + 挂生命周期。 */
    public static void register() {
        ResourceLocation c2s = new ResourceLocation(MpProto.NAMESPACE, MpProto.C2S_PATH);

        ServerPlayNetworking.registerGlobalReceiver(c2s, (server, player, handler, buf, responseSender) -> {
            // netty 缓冲返回即释放，先拷出来；帧自带 varint 长度，剩余部分是垃圾。
            byte[] data = new byte[Math.min(buf.readableBytes(), MpProto.MAX_FRAME)];
            buf.readBytes(data);
            LanMpParty.onC2sFrame(player.getUUID(), data);
        });

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
                p -> {
                    // 1.20.1 没有按通道的 canSend 探测；通道是裸 ResourceLocation，
                    // 声明发生在 minecraft:register 里且客户端栈必然声明过 ——
                    // 有玩家对象就认为可达（与服务端仓 canSend 的宽松兜底一致）。
                    return p != null;
                },
                LanNetFabric1201::sendS2c);
    }

    private static void sendS2c(ServerPlayer p, byte[] payload) {
        try {
            p.connection.send(new ClientboundCustomPayloadPacket(
                    new ResourceLocation(MpProto.NAMESPACE, MpProto.S2C_PATH),
                    new FriendlyByteBuf(Unpooled.wrappedBuffer(payload))));
        } catch (Throwable t) {
            LanMpParty.LanLog.warn("[muonium-lan] S2C 发送失败", t);
        }
    }

    private static java.nio.file.Path configDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("muonium");
    }
}
