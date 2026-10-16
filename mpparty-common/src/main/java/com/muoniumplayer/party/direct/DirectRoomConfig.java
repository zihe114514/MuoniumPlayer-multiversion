package com.muoniumplayer.party.direct;

import yun.shan.mpsync.config.MpConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 直连房间托管侧的引擎配置。
 *
 * <p>直接用 {@link MpConfig} 的默认值（与服务器 mod 全新安装一致），只关掉两样
 * 在这个场景没有意义的东西：服务器频道（全服大房间，直连模式下「全服」就是这一个
 * JVM，没有第二个房间可去）与公开房列表（同理，发现页没有别人）。</p>
 *
 * <p>托管配置落盘在 {@code config/muonium/mpsync-direct.json}，房主可以照服务器
 * mod 的文档调同步阈值、automix 提前量、serverTokens 代解析等参数；文件不存在时
 * 用默认值（{@link MpConfig#load(Path)} 自带逐项夹取）。</p>
 */
public final class DirectRoomConfig {

    private DirectRoomConfig() {
    }

    /** 默认监听端口。内网穿透、局域网直连都用它；上一次用过的端口会记住。 */
    public static final int DEFAULT_PORT = 24500;

    /**
     * 装配一份托管配置：从 {@code file} 加载（不存在就用 {@link MpConfig} 默认值），
     * 再按直连场景收口。
     */
    public static MpConfig load(Path file) {
        MpConfig cfg = file != null && Files.isRegularFile(file)
                ? MpConfig.load(file)
                : new MpConfig();
        cfg.channelEnabled = false;
        cfg.publicRoomsEnabled = false;
        return cfg;
    }
}

