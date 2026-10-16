package com.muoniumplayer.party.direct;

import com.muoniumplayer.party.MpHost;

import java.util.UUID;

/**
 * 双路传输路由：MC 通道与直连房间共用一个 {@link MpPartyClient}，但客户端只有
 * 「单当前通道」语义（{@code bind} 直接替换 host）。这一层把两路都收在手里，
 * 对外仍是一个 {@link MpHost}：直连活跃时帧走 socket，否则走 MC 通道。
 *
 * <p>各版本的 {@code MpNet} 初始化时调用 {@link #wrap} 把自己包进来再 bind，
 * {@link com.muoniumplayer.party.MpPartyClient} 其余代码不知道路由的存在。</p>
 */
public final class MpHostRouter implements MpHost {

    private static volatile MpHostRouter router;

    /** 各版本初始化时调用：把 MC 通道 host 包成路由并返回。幂等 —— 已包过就原样返回。 */
    public static synchronized MpHost wrap(MpHost mcHost) {
        if (mcHost instanceof MpHostRouter) return mcHost;
        MpHostRouter r = new MpHostRouter(mcHost);
        router = r;
        return r;
    }

    public static MpHostRouter instance() {
        return router;
    }

    private final MpHost mcHost;
    /** 直连会话（成员侧）；null = 直连未活跃，帧走 MC 通道。由 {@link DirectClient} 换绑。 */
    private volatile MpHost directHost;

    private MpHostRouter(MpHost mcHost) {
        this.mcHost = mcHost;
    }

    void attachDirect(MpHost direct) {
        this.directHost = direct;
    }

    void detachDirect() {
        this.directHost = null;
    }

    /** 当前路由指向的直连会话；null = 走 MC 通道（DirectClient.close 用它判「是不是我」）。 */
    MpHost currentDirect() {
        return directHost;
    }

    private MpHost current() {
        MpHost d = directHost;
        return d != null ? d : mcHost;
    }

    /** 只问 MC 通道侧的身份（直连握手用，不受路由当前指向影响）。 */
    UUID mcSelfUuid() {
        try {
            return mcHost == null ? null : mcHost.selfUuid();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 只问 MC 通道侧的名字（直连握手用）。 */
    String mcSelfName() {
        try {
            return mcHost == null ? null : mcHost.selfName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean canSend() {
        MpHost h = current();
        try {
            return h != null && h.canSend();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void send(byte[] frame) {
        MpHost h = current();
        if (h != null) h.send(frame);
    }

    @Override
    public UUID selfUuid() {
        MpHost h = current();
        try {
            return h == null ? null : h.selfUuid();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public String selfName() {
        MpHost h = current();
        try {
            return h == null ? null : h.selfName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void chat(String message) {
        // 反馈写进 MC 聊天栏：直连房间独立于游戏服务器存活，聊天栏是唯一常在的出口。
        MpHost h = mcHost;
        if (h != null) h.chat(message);
    }
}
