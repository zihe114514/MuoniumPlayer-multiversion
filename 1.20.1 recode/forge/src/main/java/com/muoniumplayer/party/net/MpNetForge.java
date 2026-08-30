package com.muoniumplayer.party.net;

import com.muoniumplayer.party.MpHost;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpProto;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

import java.util.UUID;

/**
 * 一起听通道的 Forge 实现（Minecraft 1.20.1）。
 *
 * <h2>为什么用 EventNetworkChannel 而不是 SimpleChannel</h2>
 * 线协议必须与 Fabric / Paper 侧逐字节一致：通道里就是一帧 {@link MpProto} 的裸字节，
 * 没有别的东西。Forge 的 {@code SimpleChannel} 会在负载前面加一个消息判别号
 * （{@code IndexedMessageCodec} 写的那个 index 字节），那一个字节就足以让
 * 服务端解不出帧。{@code EventNetworkChannel} 把 {@code FriendlyByteBuf} 原样交出来，
 * 正是这里需要的。
 *
 * <h2>为什么发包直接用原版 packet</h2>
 * 同理：{@code EventNetworkChannel} 只提供接收侧的原样投递，发送侧走
 * {@link ServerboundCustomPayloadPacket} 是唯一不会被加壳的路径。
 *
 * <h2>版本协商刻意全放开</h2>
 * 两个 accepted-versions 谓词都恒真：本 mod 的定位是「服务器没装服务端 mod 也照常单机用」，
 * 谓词收紧的话，连不带这个通道的普通服务器都会在登录握手阶段被 Forge 判为不兼容。
 */
public final class MpNetForge implements MpHost {

    public static final ResourceLocation C2S =
            new ResourceLocation(MpProto.NAMESPACE, MpProto.C2S_PATH);
    public static final ResourceLocation S2C =
            new ResourceLocation(MpProto.NAMESPACE, MpProto.S2C_PATH);

    /** 与 mpsync 服务端的协议版本；改协议时和 {@code MpProto} 一起动。 */
    private static final String PROTOCOL_VERSION = "1";

    private static final MpNetForge INSTANCE = new MpNetForge();

    private static EventNetworkChannel c2sChannel;
    private static EventNetworkChannel s2cChannel;

    private MpNetForge() {
    }

    /** 客户端入口构造时调用一次；服务器没装一起听服务端 mod 时全程无副作用。 */
    public static void register() {
        c2sChannel = channel(C2S);
        s2cChannel = channel(S2C);

        MpPartyClient.bind(INSTANCE);

        s2cChannel.addListener((NetworkEvent.ClientCustomPayloadEvent event) -> {
            FriendlyByteBuf buf = event.getPayload();
            if (buf != null) {
                // netty 缓冲在事件返回后即释放，必须先拷出来。
                byte[] data = new byte[Math.min(buf.readableBytes(), MpProto.MAX_FRAME)];
                buf.readBytes(data);
                MpPartyClient.onFrame(data);
            }
            // 不标已处理的话 Forge 会在日志里刷「payload 没人要」的警告。
            event.getSource().get().setPacketHandled(true);
        });

        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> MpPartyClient.onWorldJoin());
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> MpPartyClient.onDisconnect());
    }

    private static EventNetworkChannel channel(ResourceLocation id) {
        return NetworkRegistry.ChannelBuilder.named(id)
                .networkProtocolVersion(() -> PROTOCOL_VERSION)
                .clientAcceptedVersions(version -> true)
                .serverAcceptedVersions(version -> true)
                .eventNetworkChannel();
    }

    @Override
    public boolean canSend() {
        try {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            return connection != null && c2sChannel != null
                    && c2sChannel.isRemotePresent(connection.getConnection());
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void send(byte[] frame) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        connection.send(new ServerboundCustomPayloadPacket(
                C2S, new FriendlyByteBuf(Unpooled.wrappedBuffer(frame))));
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
