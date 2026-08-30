package com.muoniumplayer.party.net;

import com.muoniumplayer.party.MpHost;
import com.muoniumplayer.party.MpPartyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * 一起听通道的 NeoForge 实现。负载类型（{@link MpPayloads}）是原版 API，与 Fabric 侧共用同一份；
 * 这里只换「怎么登记、怎么收发、怎么监听进出服务器」。
 *
 * <p>{@code registrar(...).optional()} 是关键：不标 optional 的话，连没装一起听服务端 mod 的
 * 服务器都会因为通道协商失败而把客户端踢下线，而本 mod 的定位是「服务器没装也照常单机用」。
 *
 * <p>C2S 方向在客户端注册的 handler 是空实现：那一侧的负载只由服务端接收，
 * 这里登记只是为了取得发送权限（NeoForge 不登记就不允许发）。
 */
public final class MpNetNeoForge implements MpHost {

    private static final MpNetNeoForge INSTANCE = new MpNetNeoForge();

    /** 与 mpsync 服务端的协议版本；改协议时和 {@code MpProto} 一起动。 */
    private static final String PROTOCOL_VERSION = "1";

    private MpNetNeoForge() {
    }

    /** 客户端入口构造时调用一次；负载登记必须挂在 mod 总线上。 */
    public static void register(IEventBus modBus) {
        modBus.addListener(MpNetNeoForge::onRegisterPayloads);

        MpPartyClient.bind(INSTANCE);

        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> MpPartyClient.onWorldJoin());
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> MpPartyClient.onDisconnect());
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToServer(MpPayloads.C2S.ID, MpPayloads.C2S.CODEC,
                (payload, context) -> {
                    // 客户端不处理自己发出去的方向。
                });
        registrar.playToClient(MpPayloads.S2C.ID, MpPayloads.S2C.CODEC,
                (payload, context) -> MpPartyClient.onFrame(payload.data()));
    }

    @Override
    public boolean canSend() {
        try {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            return connection != null && connection.hasChannel(MpPayloads.C2S.ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void send(byte[] frame) {
        PacketDistributor.sendToServer(new MpPayloads.C2S(frame));
    }

    @Override
    public UUID selfUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }

    @Override
    public void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(message), false);
    }
}
