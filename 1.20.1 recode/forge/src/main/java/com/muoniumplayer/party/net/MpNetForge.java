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
import net.minecraft.server.level.ServerPlayer;
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
 * <h2>监听的事件类型按「包的来向」命名，方向别搞反</h2>
 * Forge 47 的 {@code NetworkDirection} 命名指的是<b>包从哪来</b>，不是在哪处理：
 * 收到 S2C 包（{@code PLAY_TO_CLIENT}）时 {@code NetworkInstance.dispatch} 投递的是
 * {@code ServerCustomPayloadEvent}（「来自服务器的包」），收到 C2S 包（
 * {@code PLAY_TO_SERVER}）投递的是 {@code ClientCustomPayloadEvent}（「来自客户端的包」）。
 * 客户端监听 S2C 必须收 {@code ServerCustomPayloadEvent}——收 {@code ClientCustomPayloadEvent}
 * 的话事件类型对不上、监听器永远不触发，{@code dispatch} 返回未处理，vanilla 就把每一帧
 * S2C 当「Unknown custom packet identifier」丢掉（C2S 反向正好配对，所以才会出现
 * 「建房正常、同步全瞎」的单向失聪）。两侧用 {@code newEventChannel} 同一注册入口。
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

        MpPartyClient.bind(com.muoniumplayer.party.direct.MpHostRouter.wrap(INSTANCE));

        // S2C 包（PLAY_TO_CLIENT）投递的事件类是 ServerCustomPayloadEvent ——
        // 事件名里的 Server 说的是「包从服务端来」，见类注释的方向表。
        s2cChannel.addListener((NetworkEvent.ServerCustomPayloadEvent event) -> {
            FriendlyByteBuf buf = event.getPayload();
            if (buf != null) {
                // netty 缓冲在事件返回后即释放，必须先拷出来。
                byte[] data = new byte[Math.min(buf.readableBytes(), MpProto.MAX_FRAME)];
                buf.readBytes(data);
                MpPartyClient.onFrame(data);
            }
            // 不标已处理的话 vanilla 会把这一帧当未知包再走一遍并刷警告。
            event.getSource().get().setPacketHandled(true);
        });

        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> MpPartyClient.onWorldJoin());
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> MpPartyClient.onDisconnect());
    }

    private static EventNetworkChannel channel(ResourceLocation id) {
        return NetworkRegistry.newEventChannel(id,
                () -> PROTOCOL_VERSION,
                version -> true,
                version -> true);
    }

    /**
     * 给 C2S 通道加一个服务端方向的监听（内嵌引擎用，见 {@code lan/LanNetForge}）。
     *
     * <p>两个方向投递的事件类按<b>包的来向</b>命名（见类注释的方向表）：
     * 集成服务端收 C2S 包，投递的是 {@code ClientCustomPayloadEvent}（「来自客户端的包」）。
     * 只在客户端入口里被调（本 mod 不加载到专用服务器）。</p>
     */
    public static void addServerListener(java.util.function.BiConsumer<ServerPlayer, byte[]> sink) {
        c2sChannel.addListener((NetworkEvent.ClientCustomPayloadEvent event) -> {
            FriendlyByteBuf buf = event.getPayload();
            if (buf != null) {
                byte[] data = new byte[Math.min(buf.readableBytes(), MpProto.MAX_FRAME)];
                buf.readBytes(data);
                ServerPlayer sender = event.getSource().get().getSender();
                if (sender != null) sink.accept(sender, data);
            }
            event.getSource().get().setPacketHandled(true);
        });
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
