package com.muoniumplayer.party.net;

import com.muoniumplayer.party.MpProto;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 一起听通道在 Minecraft 26.2 上的两个负载。
 *
 * <p>26.x 不再混淆，所以这里写的是官方名（{@code CustomPacketPayload} / {@code StreamCodec} /
 * {@code net.minecraft.resources.Identifier}），1.20.1–1.21.11 那几份写的是对应的 Yarn 名。
 * 线上的字节是同一帧 {@link MpProto}，因此 1.20.1 的客户端与 26.2 的客户端可以在同一个房间里。
 */
public final class MpPayloads {

    private MpPayloads() {
    }

    public record C2S(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2S> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(MpProto.NAMESPACE, MpProto.C2S_PATH));
        public static final StreamCodec<FriendlyByteBuf, C2S> CODEC = CustomPacketPayload.codec(
                (value, buf) -> buf.writeBytes(value.data()), buf -> new C2S(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record S2C(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2C> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(MpProto.NAMESPACE, MpProto.S2C_PATH));
        public static final StreamCodec<FriendlyByteBuf, S2C> CODEC = CustomPacketPayload.codec(
                (value, buf) -> buf.writeBytes(value.data()), buf -> new S2C(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 把缓冲剩下的部分全部读走：帧内部自带长度前缀。 */
    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[Math.min(buf.readableBytes(), MpProto.MAX_FRAME)];
        buf.readBytes(data);
        return data;
    }
}
