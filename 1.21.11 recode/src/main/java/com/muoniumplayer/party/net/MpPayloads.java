package com.muoniumplayer.party.net;

import com.muoniumplayer.party.MpProto;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 1.20.5+ 自定义负载 API 下的两个通道负载。
 *
 * <p>每个负载只装 {@link MpProto} 那一帧原始字节，codec 层不加任何结构，
 * 因此与 1.20.1（裸 {@code FriendlyByteBuf}）、26.2（官方名 API）和 Paper（插件消息）
 * 写到线上的字节完全相同 —— 房间因此可以跨版本。
 */
public final class MpPayloads {

    private MpPayloads() {
    }

    public record C2S(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2S> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MpProto.NAMESPACE, MpProto.C2S_PATH));
        public static final StreamCodec<FriendlyByteBuf, C2S> CODEC =
                CustomPacketPayload.codec((value, buf) -> buf.writeBytes(value.data()),
                        buf -> new C2S(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record S2C(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2C> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MpProto.NAMESPACE, MpProto.S2C_PATH));
        public static final StreamCodec<FriendlyByteBuf, S2C> CODEC =
                CustomPacketPayload.codec((value, buf) -> buf.writeBytes(value.data()),
                        buf -> new S2C(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 把缓冲剩下的部分全部读走：帧内部自带长度前缀。 */
    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[Math.min(buf.readableBytes(), MpProto.MAX_FRAME)];
        buf.readBytes(data);
        return data;
    }
}
