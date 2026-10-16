package com.muoniumplayer.party.direct;

import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直连房间托管侧的 {@link MpPlatform}：把引擎的平台能力翻译成 TCP 会话表上的操作。
 *
 * <p>与 {@code LanMpPlatform}（集成服务端玩家列表天然提供 UUID/名字/断线）相对，
 * 这里没有 MC 服务器，三样东西都由会话表制造：成员在握手帧里自报 {@code uuid} 与
 * {@code playerName}，连接断开由 socket 层感知。引擎的其余逻辑完全不知道差别。</p>
 *
 * <p>线程：{@link #execute} 把任务落进 DirectServer 的引擎线程，满足引擎
 * 「handle 与 tick 同线程」的假设；{@code sendRaw} 可能从引擎线程之外被调到，
 * 写 socket 由每连接一把锁串行化。</p>
 */
public final class DirectMpPlatform implements MpPlatform {

    /** 一条已握手的直连会话。 */
    public interface Session {
        UUID uuid();

        String name();

        /** 发一帧 S2C；连接已断开时静默失败。 */
        void send(byte[] payload);

        /** 断开这条连接（密码不对、同 UUID 顶号时用）。 */
        void close();
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final java.util.function.Consumer<Runnable> engineExecutor;
    private final java.util.function.Consumer<String> localChat;

    public DirectMpPlatform(java.util.function.Consumer<Runnable> engineExecutor,
                            java.util.function.Consumer<String> localChat) {
        this.engineExecutor = engineExecutor;
        this.localChat = localChat;
    }

    // ── 会话表维护（DirectServer 在握手/断开时调用） ─────────────────────────

    public void register(Session session) {
        sessions.put(session.uuid(), session);
    }

    public void unregister(UUID uuid) {
        sessions.remove(uuid);
    }

    /**
     * 只在 {@code session} 仍是这个 UUID 的当前会话时才删除。
     *
     * <p>顶号场景（同 UUID 重连）：旧连接的 reader 晚一步感知断开，它的清理
     * 不能把新连接刚注册的会话踢掉——按对象条件删除，被顶掉的旧连接什么也不做。</p>
     *
     * @return 真的删除了（= 本连接就是当前会话）才返回 true
     */
    public boolean unregisterIfCurrent(UUID uuid, Session session) {
        if (uuid == null || session == null) return false;
        return sessions.remove(uuid, session);
    }

    public boolean isOnline(UUID uuid) {
        return uuid != null && sessions.containsKey(uuid);
    }

    // ── MpPlatform ──────────────────────────────────────────────────────────

    @Override
    public String nameOf(UUID uuid) {
        Session s = uuid == null ? null : sessions.get(uuid);
        return s == null ? null : s.name();
    }

    @Override
    public UUID uuidByName(String name) {
        if (name == null || name.isBlank()) return null;
        String want = name.trim();
        for (Session s : sessions.values()) {
            if (want.equalsIgnoreCase(s.name())) return s.uuid();
        }
        return null;
    }

    @Override
    public Collection<UUID> onlinePlayers() {
        return new ArrayList<>(sessions.keySet());
    }

    @Override
    public boolean canSend(UUID uuid) {
        // 会话表里的每一条都装了 mod（没有 mod 的客户端根本连不上直连端口），
        // 无须像 MC 通道那样再问一遍声明。
        return isOnline(uuid);
    }

    @Override
    public void sendRaw(UUID uuid, byte[] payload) {
        Session s = uuid == null ? null : sessions.get(uuid);
        if (s == null || payload == null) return;
        try {
            s.send(payload);
        } catch (Throwable ignored) {
            // 写失败 = 连接正在断开，reader 线程会把它请出会话表。
        }
    }

    @Override
    public void chat(UUID uuid, String text) {
        // 聊天栏镜像只有房主本机一个出口：成员都有 mod，notice/error 帧已经覆盖。
        if (uuid != null && localChat != null) localChat.accept(text);
    }

    @Override
    public void execute(Runnable task) {
        if (engineExecutor != null) engineExecutor.accept(task);
        else task.run();
    }

    @Override
    public void warn(String message, Throwable t) {
        org.slf4j.LoggerFactory.getLogger("muonium-direct").warn(message, t);
    }

    /** 在线会话快照（诊断用）。 */
    public List<Session> snapshot() {
        return new ArrayList<>(sessions.values());
    }
}
