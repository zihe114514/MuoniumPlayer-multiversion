package com.muoniumplayer.party.lan;

import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.platform.MpPlatform;
import yun.shan.mpsync.room.RoomManager;

import java.nio.file.Path;
import java.util.UUID;

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
 * <h2>世界主人 = 服务器管理员</h2>
 * 引擎里「服务器频道」只听服务器管理员的（拖进度、直接切歌…），而专用服务器上靠的是 {@code /mp}
 * 指令拿到的 OP 标志 —— 集成服务端里既没注册 {@code /mp}，也就没人被登记成管理员，频道谁都
 * 控制不了（拖进度被拦、「下一首」变成投票、不足 2 人又投不了）。这里把世界主人登记成管理员，
 * 见 {@link #hostUuid}。
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

    /** 见 {@link #heldByDirect} 字段注释。 */
    private static volatile boolean heldByDirect;

    /**
     * 这个集成服务端的主人（世界主人）：本次服务器会话里第一个加入的玩家。他就是服务器管理员。
     *
     * <p>为什么不用 {@code MinecraftServer.isSingleplayerOwner}：它的参数在 1.21.11 从
     * {@code GameProfile} 换成了 {@code NameAndId}，而本文件五树共用。「第一个加入的人」不依赖任何
     * 版本 API，在集成服务端上又恒成立：单人世界是主人进世界时才启动集成服务端；开局域网也必须
     * 先进世界，别人只能在之后加入；主人一退，集成服务端就停了。也不用客户端的
     * {@code selfUuid()}：服务端处理加入时本机的玩家实体通常还没建好。</p>
     *
     * <p>登记发生在服务端的加入事件里，早于他的客户端发来的握手：握手里的 {@code serverAdmin}
     * 因此一开始就是 true，界面上就是管理员视角。</p>
     */
    private static volatile UUID hostUuid;

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
     *
     * <p>让位时<b>仍然记下本次挂载参数</b>（{@link #rememberMount}）并置
     * {@link #heldByDirect}：否则主机在直连托管中切换了存档，新世界的装配参数
     * 会被丢掉，等直连释放时 {@link #onDirectReleased} 拿着的还是旧世界的死参数，
     * 新世界的局域网一起听就再也挂不回来了（除非重进存档）。</p>
     */
    public static void onServerStarting(boolean integrated, MpPlatform platform, Path configDir) {
        active = false;
        hostUuid = null;   // 新的服务器会话：第一个加入的人才是这次的主人
        if (!integrated) {
            // 远程服务器的桥接调用：不持有任何挂载参数，把上一个世界的痕迹清掉。
            heldByDirect = false;
            lastPlatform = null;
            lastConfigDir = null;
            return;
        }
        if (com.muoniumplayer.party.direct.MpDirect.isHosting()) {
            heldByDirect = true;
            rememberMount(platform, configDir, integrated);
            // warn 而不是 info：加入者在这台主机上会一直显示「服务端没有应答握手」，
            // 主机自己的日志里得能查到原因 —— 引擎不是没装，是被直连托管占着。
            LanLog.warn("[muonium-lan] 直连房间托管中，一起听引擎已由直连持有，本世界的局域网一起听让位；关闭直连房间后自动恢复");
            return;
        }
        active = true;
        heldByDirect = false;
        rememberMount(platform, configDir, integrated);
        MpParty.init(platform, configDir.resolve("mpsync-lan.json"));
        LanLog.info("[muonium-lan] 一起听房间引擎已挂到集成服务端（protocol v{}）", MpParty.protocol());
    }

    public static void onPlayerJoin(UUID uuid) {
        // 只在集成服务端上认主人（lastPlatform 只在集成服务端挂载时才记下）。引擎正被直连持有时也要认：
        // 直连释放后重挂引擎（onDirectReleased）要靠它把主人重新登记成管理员。
        if (hostUuid == null && lastPlatform != null && uuid != null) hostUuid = uuid;
        if (active && !directOwnsEngine()) {
            MpParty.onPlayerJoin(uuid);
            grantHostAdmin(uuid);
        }
    }

    /** 这个人是世界主人就登记成服务器管理员（见 {@link #hostUuid}）。 */
    private static void grantHostAdmin(UUID uuid) {
        if (uuid == null || !uuid.equals(hostUuid)) return;
        RoomManager manager = MpParty.manager();
        if (manager != null) manager.noteOperator(uuid, true);
    }

    /** 世界主人（诊断 / 测试用）。 */
    public static UUID hostUuid() {
        return hostUuid;
    }

    public static void onPlayerQuit(UUID uuid) {
        if (active && !directOwnsEngine()) MpParty.onPlayerQuit(uuid);
    }

    /** 每服务器 tick 一次；引擎未激活时是零开销直返。 */
    public static void tick() {
        // 引擎被直连接管后（纯单人放行场景），LAN 桥必须整体闭嘴：再 tick 就是
        // 第二条线程并发驱动引擎（心跳翻倍、Room.tick 双执行、交接点可能连跳两首），
        // LAN 成员的帧也会打进直连引擎然后有去无回。
        if (!active || directOwnsEngine()) return;
        // 直连释放/接管的交接瞬间，引擎单例可能正在被另一条线程（直连引擎线程）收拾——
        // 这里跑在 MC 集成服的 tick 线程上，一次并发碰房间的异常不许炸进服务器 tick 事件。
        // 引擎内部每房 tick 已有自己的捕获（RoomManager.tick 逐房 try/catch），这一层兜
        // 的是「manager 刚被置空/替换」这种房间之外的窄窗口。
        try {
            MpParty.tick();
        } catch (Throwable t) {
            LanLog.warn("[muonium-lan] 引擎 tick 异常（已吞掉，下一 tick 重试）", t);
        }
    }

    public static void onServerStopped() {
        hostUuid = null;
        if (!active) {
            // 引擎正被直连持有（或从未挂载）：这个世界的挂载参数随世界一起作废。
            // 不清的话 lastPlatform 指向已死的 server 对象，直连释放时 onDirectReleased
            // 会拿它把引擎重挂到一个已经不存在的世界上。直连房间本身不受影响 ——
            // 它的成员经穿透隧道进来，不依赖集成服务端。
            if (lastPlatform != null) {
                lastPlatform = null;
                lastConfigDir = null;
                heldByDirect = false;
            }
            return;
        }
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
    public static void onC2sFrame(UUID sender, byte[] payload) {
        if (active && !directOwnsEngine()) MpParty.onPacket(sender, payload);
    }

    /** 引擎当前是否由直连房间托管（LAN 桥让位的判据，与 onServerStarting 同源）。 */
    private static boolean directOwnsEngine() {
        return com.muoniumplayer.party.direct.MpDirect.isHosting();
    }

    // ── 引擎归还：直连接管（纯单人放行场景）释放后，把引擎重新装配回 LAN ──

    /** 最近一次 onServerStarting 的装配参数：直连释放后按它重挂引擎。 */
    private static volatile MpPlatform lastPlatform;
    private static volatile java.nio.file.Path lastConfigDir;
    private static volatile boolean lastIntegrated;

    /** 由 {@link #onServerStarting} 记下装配参数（直连释放后重挂用）。 */
    static void rememberMount(MpPlatform platform, Path configDir, boolean integrated) {
        lastPlatform = platform;
        lastConfigDir = configDir;
        lastIntegrated = integrated;
    }

    /**
     * 直连房间释放引擎后调用：LAN 桥还激活着（同一个世界没退），但引擎已被
     * 直连 shutdown——此时按原装配参数重挂一次，局域网一起听立刻恢复，
     * 不用重进世界。engineGone 由 MpDirect 的会话回收路径触发。
     *
     * <p>两种等待者都能被唤醒：正常挂载后让位的（{@code active} 为真），以及
     * 「直连托管中换过存档」、从未在新世界挂上过的（{@code heldByDirect} 为真、
     * {@code active} 为假但参数是新的）。世界已经停了的（{@code lastPlatform}
     * 被 {@link #onServerStopped} 清掉）自然什么都不做。</p>
     */
    public static void onDirectReleased() {
        if (lastPlatform == null) return;
        if (com.muoniumplayer.party.direct.MpDirect.isHosting()) return;
        if (!active && !heldByDirect) return;
        try {
            MpParty.init(lastPlatform, lastConfigDir);
            active = true;
            heldByDirect = false;
            // 重挂是一个全新的引擎，管理员登记是空的：主人还在线就把他重新登记上。
            // 这里可能不在服务端线程（由直连的会话回收路径触发），引擎状态只许在引擎线程上改，所以经 execute 过去。
            MpPlatform p = lastPlatform;
            UUID host = hostUuid;
            if (host != null && p != null) {
                p.execute(() -> {
                    if (p.onlinePlayers().contains(host)) grantHostAdmin(host);
                });
            }
            LanLog.info("[muonium-lan] 直连房间已释放，一起听引擎已重挂回集成服务端");
        } catch (Throwable t) {
            LanLog.warn("[muonium-lan] 引擎重挂失败（局域网一起听不可用，重进世界可恢复）", t);
        }
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

        static void warn(String message) {
            LOG.warn(message);
        }

        static void warn(String message, Throwable t) {
            LOG.warn(message, t);
        }
    }
}
