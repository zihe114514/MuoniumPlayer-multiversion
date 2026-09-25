package com.muoniumplayer.party.net;

import com.muoniumplayer.party.MpHost;
import com.muoniumplayer.party.MpPartyClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 一起听通道的 Fabric 实现（26.2：负载注册表叫 serverbound/clientboundPlay）。
 *
 * <p>负载类型（{@link MpPayloads}）是原版 API，与 NeoForge 侧共用同一份共享源码；
 * 这里只换「怎么登记、怎么收发、怎么监听进出服务器」。
 */
public final class MpNetFabric implements MpHost {

    private static final MpNetFabric INSTANCE = new MpNetFabric();

    private MpNetFabric() {
    }

    /** 客户端初始化时调用一次；服务器没装一起听服务端 mod 时全程无副作用。 */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(MpPayloads.C2S.TYPE, MpPayloads.C2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MpPayloads.S2C.TYPE, MpPayloads.S2C.CODEC);

        MpPartyClient.bind(INSTANCE);

        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.S2C.TYPE,
                (payload, context) -> MpPartyClient.onFrame(payload.data()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> MpPartyClient.onWorldJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MpPartyClient.onDisconnect());
    }

    @Override
    public boolean canSend() {
        try {
            return ClientPlayNetworking.canSend(MpPayloads.C2S.TYPE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void send(byte[] frame) {
        ClientPlayNetworking.send(new MpPayloads.C2S(frame));
    }

    @Override
    public UUID selfUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }

    @Override
    public void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(message));
    }
}
