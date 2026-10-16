package com.muoniumplayer.party;

import java.util.UUID;

/**
 * 与 Minecraft 打交道的那一层，由各版本工程自己的 {@code party/net/MpNet.java} 实现。
 *
 * <p>1.20.1 用裸 {@code PacketByteBuf} 通道，1.21.1 / 1.21.11 用 1.20.5+ 的
 * {@code CustomPayload}，26.2 用官方名的 {@code CustomPacketPayload}；聊天与玩家 UUID 的
 * 类名同样每个版本都不一样。把这些差异关在这个接口后面，
 * {@code com.muoniumplayer.party} 其余部分就是四个版本共用的同一份源码。
 */
public interface MpHost {

    /** 服务端是否声明了 mpsync 通道（即是否装了一起听服务端 mod）。 */
    boolean canSend();

    /** 发一帧 C2S（已经是 {@link MpProto#encode} 的产物）。 */
    void send(byte[] frame);

    /** 本地玩家 UUID；不在世界里时返回 null。 */
    UUID selfUuid();

    /**
     * 本地玩家名；拿不到就返回 {@code null}。
     *
     * <p>直连房间的会话表靠它建立：引擎没有 MC 连接可查名字，成员的显示名、
     * 邀请与踢人按名找人都要用。它随 hello 帧自报给引擎，服务器房间模式下
     * 服务端 mod 从 MC 连接里拿名字，这个字段被忽略。</p>
     */
    default String selfName() {
        return null;
    }

    /** 往聊天栏写一行反馈。 */
    void chat(String message);
}
