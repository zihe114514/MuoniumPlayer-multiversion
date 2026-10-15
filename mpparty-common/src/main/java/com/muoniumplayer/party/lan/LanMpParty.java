package com.muoniumplayer.party.lan;

import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.platform.MpPlatform;

import java.nio.file.Path;

/**
 * 内嵌一起听房间引擎的生命周期管理：把服务端 core（{@code yun.shan.mpsync.MpParty}）
 * 挂进本 JVM 的集成服务端，让只装客户端 mod 的局域网主机也能开一起听房间。
 *
 * <p>专用服务器上本 mod 根本不加载（{@code environment: client} / {@code side=CLIENT} /
 * {@code dist=CLIENT}），所以这里的引擎只可能在局域网主机的集成服务端里活 ——
 * 「服务端 mod 只给服务器用」的分工保持不变。
 *
 * <h2>激活条件</h2>
 * 每次服务器启动时由各加载器桥接层调用 {@link #onServerStarting}，传入
 * 「这是不是本客户端自己的集成服务端」的判断结果。只有集成服务端才 init：
 * 连远程服时客户端 mod 里这条服务端栈完全静止，对游玩没有任何影响。
 * 纯单人不开局域网也照常 init：引擎对没有房间的世界无副作用（无 tick 逻辑、
 * 无网络帧），且方便主机在单人世界里先建好房再开 LAN。
 *
 * <h2>可重入性</h2>
 * 客户端会反复开关存档，{@link MpParty#init} / {@link MpParty#shutdown} 按
 * 服务端 mod 同款顺序成对调用；core 的 shutdown 清空全部房间状态、
 * init 重新装配配置与线程池，两边都无静态残留假设。存档切换即引擎重置 ——
 * 与专用服务器「一次进程一个引擎」不同，这里是「一次服务器会话一个引擎」。
 *
 * <h2>线程与回环</h2>
 * 主机自己作为房员的帧走真实的 localhost 连接：他的客户端发 C2S 到集成服务端，
 * 引擎的 S2C 经他自己的 ServerPlayer 连接回来。客户端已有的
 * {@code MpPartyClient}（hello 探测、房间 UI、播放桥接）一行不用改 ——
 * 它不关心 hello 是谁答的。
 */
public final class LanMpParty {

    private LanMpParty() {
    }

    private static volatile boolean active;

    /** 引擎是否正挂在当前的集成服务端上（仅诊断/日志用）。 */
    public static boolean isActive() {
        return active;
    }

    /**
     * 服务器启动。{@code integrated} 由桥接层判定（通常是
     * {@code server == Minecraft.getInstance().getSingleplayerServer()}）。
     * 引擎配置放在游戏目录的 {@code config/muonium/mpsync-lan.json}。
     *
     * <p>直连房间托管中时本方法直接放弃：局域网引擎与直连引擎都挂在同一个
     * {@code MpParty} 单例上，后 init 的会把前者整个顶掉（房间状态、配置全被重置）。
     * 直连房间的成员经穿透隧道进来，不依赖集成服务端；这时主机退出 MC 存档
     * （触发 {@link #onServerStopped}）也不该影响正在跑的直连房间，所以这里
     * 选择让 LAN 引擎让位，而不是把直连关掉。</p>
     */
    public static void onServerStarting(boolean integrated, MpPlatform platform, Path configDir) {
        active = false;
        if (!integrated) return;
        if (com.muoniumplayer.party.direct.MpDirect.isHosting()) {
            LanLog.info("[muonium-lan] 直连房间托管中，一起听引擎已由直连持有，跳过挂载");
            return;
        }
        active = true;
        MpParty.init(platform, configDir.resolve("mpsync-lan.json"));
        LanLog.info("[muonium-lan] 一起听房间引擎已挂到集成服务端（protocol v{}）", MpParty.protocol());
    }

    public static void onPlayerJoin(java.util.UUID uuid) {
        if (active) MpParty.onPlayerJoin(uuid);
    }

    public static void onPlayerQuit(java.util.UUID uuid) {
        if (active) MpParty.onPlayerQuit(uuid);
    }

    /** 每服务器 tick 一次；引擎未激活时是零开销直返。 */
    public static void tick() {
        if (active) MpParty.tick();
    }

    public static void onServerStopped() {
        if (!active) return;
        active = false;
        // 直连房间托管中时引擎归直连持有，不能在这里 shutdown——
        // 那会把正在跑的直连房间（成员还连着）一起杀掉。
        if (com.muoniumplayer.party.direct.MpDirect.isHosting()) {
            LanLog.info("[muonium-lan] 集成服务端已停，引擎由直连房间继续持有");
            return;
        }
        MpParty.shutdown();
        LanLog.info("[muonium-lan] 集成服务端已停，一起听房间引擎卸载");
    }

    /** C2S 帧入口：桥接层收到的服务端方向帧全转这里，未激活时丢弃。 */
    public static void onC2sFrame(java.util.UUID sender, byte[] payload) {
        if (active) MpParty.onPacket(sender, payload);
    }

    /** 简单的日志外观：避免各桥接层重复配 logger。 */
    static final class LanLog {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger("muonium-lan");

        private LanLog() {
        }

        static void info(String format, Object... args) {
            LOG.info(format, args);
        }

        static void warn(String message, Throwable t) {
            LOG.warn(message, t);
        }
    }
}
