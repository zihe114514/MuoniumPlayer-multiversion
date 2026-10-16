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
 * 一起听通道的 Fabric 实现（1.20.5+ 的 {@code CustomPacketPayload} API）。
 *
 * <p>负载类型本身（{@link MpPayloads}）是原版 API，两个加载器共用；
 * 差别只在「怎么登记 codec、怎么收发、怎么监听进出服务器」这三件事上。
 *
 * <p>两个方向的负载类型都要在本端登记一次，否则发送/接收时 Fabric 找不到 codec。
 */
public final class MpNetFabric implements MpHost {

    private static final MpNetFabric INSTANCE = new MpNetFabric();

    private MpNetFabric() {
    }

    /** 客户端初始化时调用一次；服务器没装一起听服务端 mod 时全程无副作用。 */
    public static void register() {
        registerPayloadsCompat();

        MpPartyClient.bind(com.muoniumplayer.party.direct.MpHostRouter.wrap(INSTANCE));

        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.S2C.ID,
                (payload, context) -> MpPartyClient.onFrame(payload.data()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> MpPartyClient.onWorldJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MpPartyClient.onDisconnect());
    }

    /**
     * 同装服务端 mod（本客户端 mod 跑在同一台机器的同一 JVM 里当 LAN 主机）时，
     * 服务端 mod 可能已经登记过这两个 payload——注册表对重复登记直接抛
     * {@code IllegalArgumentException}，游戏在启动阶段就崩。这里吞掉那一次冲突：
     * payload 已经在表里，codec 相同（两侧同一份源码），跳过登记不影响收发。
     */
    private static void registerPayloadsCompat() {
        try {
            PayloadTypeRegistry.playC2S().register(MpPayloads.C2S.ID, MpPayloads.C2S.CODEC);
            PayloadTypeRegistry.playS2C().register(MpPayloads.S2C.ID, MpPayloads.S2C.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            System.out.println("[MpParty] mpsync payload 已由服务端 mod 登记，跳过重复登记（同装模式）");
        }
    }

    @Override
    public boolean canSend() {
        try {
            return ClientPlayNetworking.canSend(MpPayloads.C2S.ID);
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
    public String selfName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getName().getString();
    }

    @Override
    public void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(message), false);
    }
}
