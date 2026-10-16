package com.muoniumplayer.party.net;

import com.muoniumplayer.party.MpHost;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpProto;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 一起听通道在 Minecraft 1.20.1 上的实现：1.20.5 之前的自定义负载 API 就是裸
 * {@code FriendlyByteBuf} 通道。
 *
 * <p>线上的字节由 {@link MpProto} 产出，因此与 1.21.x / 26.2 / Paper 完全一致 ——
 * 这也是「1.20.1 客户端能进 1.21.11 服务器的房间」的原因。
 */
public final class MpNetFabric implements MpHost {

    public static final ResourceLocation C2S = new ResourceLocation(MpProto.NAMESPACE, MpProto.C2S_PATH);
    public static final ResourceLocation S2C = new ResourceLocation(MpProto.NAMESPACE, MpProto.S2C_PATH);

    private static final MpNetFabric INSTANCE = new MpNetFabric();

    private MpNetFabric() {
    }

    /** 客户端初始化时调用一次；服务器没装一起听服务端 mod 时全程无副作用。 */
    public static void register() {
        MpPartyClient.bind(com.muoniumplayer.party.direct.MpHostRouter.wrap(INSTANCE));

        ClientPlayNetworking.registerGlobalReceiver(S2C, (client, handler, buf, responseSender) -> {
            // netty 缓冲返回即释放，必须先拷出来。
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            MpPartyClient.onFrame(data);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> MpPartyClient.onWorldJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MpPartyClient.onDisconnect());
    }

    @Override
    public boolean canSend() {
        try {
            return ClientPlayNetworking.canSend(C2S);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void send(byte[] frame) {
        ClientPlayNetworking.send(C2S, new FriendlyByteBuf(Unpooled.wrappedBuffer(frame)));
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
