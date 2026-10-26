package com.muoniumplayer.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.ncm.music.CrossSourceSwitcher;
import com.muoniumplayer.core.ncm.music.PlatformAccess;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ListenTogetherService;
import com.muoniumplayer.core.ncm.music.ListenTogetherSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.RoomAutomix;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 服务端房间（MusicParty / mpsync）在 MuoniumPlayer 侧的客户端。
 *
 * <p>分工：本类持有通道、房间快照与时钟，从不自己出声；真正的播放交给
 * {@link MpPlayback}，那一层复用播放器现有的下载/解码/播放链路。
 * 与 Minecraft 有关的一切（通道注册、玩家 UUID、聊天）都在 {@link MpHost} 后面，
 * 由各版本工程自己实现，所以本包在 1.20.1 / 1.21.1 / 1.21.11 / 26.2 上是同一份源码。
 *
 * <p>不限播放源：点歌方用本机对应平台的会话解析出直链，连同元数据一起上传；服务端只转发、
 * 只管时钟，从不自己抓流。因为播放本身只是「下载这条链接再按字节头认容器」，与来源平台无关，
 * 所以不存在「某个成员放不了这个源」的情况 —— 本机压根没装那个平台也照样能听。
 *
 * <p>线程：{@link #onFrame(byte[])} 可能在网络线程上被调用，因此所有状态都是 volatile，
 * 任何要碰播放器/界面的动作都经 {@code MultiThreadingUtil} 转到主线程。
 */
public final class MpPartyClient {

    private MpPartyClient() {
    }

    // ── 状态 ─────────────────────────────────────────────────────────────────

    private static volatile MpHost host;

    private static volatile MpRoom room;
    private static volatile MpTrack currentTrack;
    private static volatile boolean playing;
    private static volatile long durationMs;

    /** 位置基准 + 打戳的本地时刻，{@link #positionMs()} 据此外推。 */
    private static volatile long posBase;
    private static volatile long posStamp;
    /** 已应用的播放指令序号，用来丢弃乱序帧。 */
    private static volatile long appliedSeq = -1L;

    private static volatile boolean serverPresent;
    /**
     * 服务端已经回过 {@code hello}。
     *
     * <p>不能只靠 {@link #available()}（即 {@code ClientPlayNetworking.canSend}）判断服务端在不在：
     * 那个方法问的是「服务器有没有<b>声明</b>它收得下这个通道」。Fabric 服务端会声明，
     * <b>Paper 服务端不会</b> —— Bukkit 的 {@code registerIncomingPluginChannel} 不往客户端发
     * {@code minecraft:register}。于是在 Paper 上 canSend 永远是 false，握手永远发不出去，
     * 界面会一直显示「这台服务器没装服务端 mod」。所以 hello 改成无条件试探，
     * 收到回复才算数。</p>
     */
    private static volatile boolean helloAcked;
    /** 已经试探过几次；没人应答就停手，别一直往普通服务器上打包。 */
    private static volatile int helloProbes;
    private static volatile int serverProto;
    private static volatile boolean membersCanEnqueue = true;
    private static volatile boolean waitingUrl;

    /**
     * 一条 HEAD 的预算。拿不到就当「长度未知」，绝不因为估不出时长而把一条本来能播的链接废掉。
     *
     * <p>压到 1.2 秒是照着<b>最窄的那一格</b>定的：服务端一个层窗默认 3 秒，代解析这一路在
     * 取流之后只剩这一格。真跑超时了也只是少一只识破试听片段的眼睛。</p>
     */
    private static final long ESTIMATE_BUDGET_MS = 1_200L;

    /** 正在跑跨平台救援的队列格，防止服务端因队列变动把同一首反复问过来。 */
    private static final java.util.Set<Long> RESCUE_IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 只防泄漏，不防正确性：救援一格的窗最长 12 秒，64 格早滚完了。 */
    private static final int RESCUE_SET_LIMIT = 64;

    /**
     * 本机这次进房有没有提示过「我在替房间解析什么」。
     *
     * <p>贡献会员现在是默认开的，玩家第一次被问到时应该知道自己被排在了「先被问到」那一档，
     * 以及能不能关掉。只在<b>进房那一次</b>说：快照会因为重连、转让房主、重新同步反复到来。</p>
     */
    private static volatile boolean contributionHintShown;

    // ── v2：服务端能力（hello 下发） ──────────────────────────────────────────
    //
    // 这些都由服务端说了算，客户端不自己推断：同一个房间里 modernMP 和 MuoniumPlayer
    // 必须显示同样的按钮、用同样的票数门槛，否则会出现「他那边能投我这边没有」。

    /** 我是不是这台服务器的管理员（频道里的直接控制权来自这里）。 */
    private static volatile boolean serverAdmin;
    private static volatile boolean channelEnabled;
    private static volatile String channelName = "服务器频道";
    private static volatile String channelCode = "";
    /** 频道里单人能挂在歌单上的曲目数。 */
    private static volatile int channelMaxPerMember = 3;
    private static volatile boolean publicRoomsEnabled = true;
    /** 投票窗口，进度条要用。 */
    private static volatile long voteWindowMs = 30_000L;
    /** 服主配了平台 Token：没有会员的人也能点会员曲，界面应该把这件事说出来。 */
    private static volatile boolean serverResolve;

    /**
     * 「播放歌单」这台服务端认不认。
     *
     * <p>取的是 {@code hello.playlistBatchLimit}：{@code >0} 才会出现那颗按钮，{@code 0}（以及
     * 老服务端根本不发这个字段时 {@link MpProto#num} 的缺省 0）就是没有这个功能。<b>绝不能</b>
     * 让界面出现一颗按下去只换来聊天栏一句 error 的按钮 —— 那是把一个协议版本问题伪装成用户操作。</p>
     */
    private static volatile int playlistBatchLimit;
    /** 灌单是不是只有房主/管理员能做（服务端配置，界面只负责把按钮置灰）。 */
    private static volatile boolean playlistAdminOnly = true;

    // ── v2：封禁 ─────────────────────────────────────────────────────────────
    private static volatile boolean banned;
    private static volatile String banReason = "";
    private static volatile String banRemaining = "";

    /**
     * 个人暂停：只静音自己，房间时钟照走，别人不受影响。
     *
     * <p>它必须挡住<b>所有</b>开声路径（换歌、恢复、拖进度、心跳对齐），漏一条就会偷偷出声。
     * 恢复时服务端会单独补一帧 {@code resync} 的 play，那一帧才清掉这个标记。
     */
    private static volatile boolean selfPaused;

    // ── v2：全服态 ───────────────────────────────────────────────────────────
    private static volatile List<MpPresence> presenceList = Collections.emptyList();
    private static volatile List<MpPublicRoom> publicRooms = Collections.emptyList();
    private static volatile List<MpInvite> pendingInvites = Collections.emptyList();
    private static volatile int onlineCount;

    /**
     * 上一次有人「关心在线态」的时刻。
     *
     * <p>在线态只能靠轮询（服务端不会主动推全服快照），而绝大多数时间没人在看那一页。
     * 界面每帧调一次 {@link #touchPresence()}，这里据此决定要不要继续每 5 秒问一次 ——
     * 关掉界面就自动停，不给服务端白发包。
     */
    private static volatile long presenceInterest;

    private static volatile String lastMessage = "";
    private static volatile boolean lastMessageIsError;

    private static volatile String inviteCode = "";
    private static volatile String inviteRoomName = "";
    private static volatile String inviteFrom = "";

    /**
     * 时钟偏移估计与漂移纠偏策略都搬到了 {@link MpSyncPolicy}。
     *
     * <p>原来这里放的是「有史以来最小的 {@code 本地时间 - 服务端时间}」，坏网时会把抖动整段
     * 加进位置，再配上一个 1.5 秒的硬跳阈值，结果是抖动本身就能触发硬跳，跳完又要重新缓冲 ——
     * 进度来回跳。现在偏移用滑动窗口，纠偏分三档并带确认次数和冷却。</p>
     */

    /** 正在应用服务端帧：本地拦截据此放行自己触发的起播，不然会把回声当成用户操作又发回去。 */
    private static volatile boolean applyingRemote;

    private static volatile long lastBlockHint;

    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<Runnable>();

    static {
        // 交接计划要交给服务端广播给全房。核心播放层不反向依赖一起听这一层，
        // 所以上报通道从这里注入 —— 房间之外 RoomAutomix 根本走不到 planWith。
        //
        // 本机能不能算由服务端指派决定（点歌人优先 → 房主 → 任何能算的人），
        // 所以这里回报的只是「算不算得出来」，不再有房主身份的门槛。
        RoomAutomix.setPlanSink(new RoomAutomix.PlanSink() {
            @Override
            public void publish(long entryId, JsonObject plan) {
                reportAutomixPlan(entryId, plan);
            }

            @Override
            public void decline(long entryId) {
                reportAutomixDecline(entryId);
            }
        });
    }

    private static volatile ScheduledExecutorService ticker;

    // ── 注册 ─────────────────────────────────────────────────────────────────

    /** 由各版本的 {@code party/net/MpNet} 在客户端初始化时调用一次。 */
    public static synchronized void bind(MpHost newHost) {
        host = newHost;
        if (ticker != null) return;
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "mp-party");
                thread.setDaemon(true);
                return thread;
            }
        });
        ticker = service;
        service.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** 进入世界：向服务端打招呼（没装服务端 mod 时什么都不会发生）。 */
    public static void onWorldJoin() {
        // 直连房间活跃时不探测 MC 通道：帧走 socket，这 worlds join 与它无关，
        // 探测只会把 hello 打到第三方服务器上。
        if (com.muoniumplayer.party.direct.MpDirect.isActive()) return;
        helloAcked = false;
        helloProbes = 0;
        serverPresent = available();
        probeHello();
    }

    /**
     * 发一次握手。无条件发：普通服务器收到不认识的自定义负载会直接忽略，没有副作用，
     * 而这是 Paper 服务端唯一能被发现的途径（见 {@link #helloAcked}）。
     *
     * <p>这里的「无条件」指的是<b>不要求服务端先声明通道</b>（{@link #available()}）——
     * 那恰恰是 Paper 上永远为 false 的东西。它并不表示「任何时候都能发」：调用方必须先
     * 确认本机确实在一个世界里，否则发送端会当场抛 {@code IllegalStateException}。
     * tick 的补发路径已经用 {@link #inGame()} 挡好；{@link #onWorldJoin()} 是刚进服的
     * 明确信号，那时连接一定在，不必再挡。</p>
     */
    private static void probeHello() {
        if (helloProbes >= HELLO_PROBE_LIMIT) return;
        JsonObject hello = MpProto.msg(MpProto.C_HELLO);
        // hello 直接带上完整身份：服务端吸收之后紧接着发出的房间快照里就已经有 Mod 与 VIP，
        // 不必等第一次 profile / 心跳。
        fillProfile(hello);
        // 只有真的发出去了才记一次配额。调用方已经挡过「不在世界里」的情况，但一秒一格的
        // 补发循环与连接状态之间总有缝隙：这一发万一刚好撞上掉线，发送端会直接抛
        // "Cannot send packets when not in game!" —— 旧写法照样扣数，于是在标题界面停留
        // 半分钟就把 30 次试探全烧掉，真正进服之后一次招呼也发不出去。这一层是兜底。
        if (sendQuiet(hello)) helloProbes++;
    }

    /**
     * 试探上限。本类的 tick 是 1 秒一次，所以 30 次 ≈ 30 秒的发现窗口 ——
     * 与 modernMP（心跳 5 秒 × 6 次）刻意取成同一个量级：同一台服务器对三个客户端
     * 必须在差不多的时间内、以同样的方式变成「可用」，否则会出现「A 能进房 B 说没装」。
     */
    private static final int HELLO_PROBE_LIMIT = 30;

    public static void onDisconnect() {
        // 直连房间活跃时不清理：玩家退出第三方 MC 服务器会触发 MC 通道的 DISCONNECT
        // 事件，但直连房间独立于游戏服务器存活（引擎在客户端 JVM 里）——
        // 清掉它就是把正在放的一起听一起杀掉。关房/断开时 MpDirect 会自己调一次本方法。
        if (com.muoniumplayer.party.direct.MpDirect.isActive()) return;
        boolean had = room != null;
        clearServerScopeState();
        reset();
        if (had) MpPlayback.stop();
        notifyListeners();
    }

    /**
     * 清掉「这一台服务器的」全部客户端状态。
     *
     * <p>两条路径共用：MC 通道断开（{@link #onDisconnect}），以及切进直连会话前
     * （{@link #resetForDirectSession}）。直连的 S_HELLO 到达后会覆写其中大半字段
     * （proto / serverAdmin / channelEnabled / serverResolve / channelCode…），清掉是
     * 纯收益；但 <b>banned 不会被任何直连帧覆写</b> —— S_HELLO 分支不碰它，不清的话
     * 上一个服务器的封禁横幅会整段挂进直连会话。公开房间列表、待处理邀请与在线态
     * 同理：直连配置关掉了公开房，这些数据在直连期间不会再被刷新。</p>
     */
    private static void clearServerScopeState() {
        serverPresent = false;
        helloAcked = false;
        helloProbes = 0;
        serverProto = 0;
        serverAdmin = false;
        channelEnabled = false;
        channelCode = "";
        publicRoomsEnabled = true;
        serverResolve = false;
        banned = false;
        banReason = "";
        banRemaining = "";
        presenceList = Collections.emptyList();
        publicRooms = Collections.emptyList();
        pendingInvites = Collections.emptyList();
        // v1 的邀请横幅三兄弟与 v2 的待处理邀请列表同口径：换服务器/切直连会话时一起作废
        //（旧房间的邀请码在新的会话里 join 必然失败）。主动离房（S_LEFT→reset）不清这两者——
        // 离开 A 房间不等于拒绝 B 房间的邀请。
        inviteCode = "";
        inviteRoomName = "";
        inviteFrom = "";
        onlineCount = 0;
        // 这是「这台服务器的」交叠开关，换服务器必须清掉：新服务端会在它自己的 hello 里
        // 重新下发，收不到（老服务端）就保持 0 = 不交叠。
        RoomAutomix.configure(0L);
        // 分析任务同样跟着房间走：断开之后本机不再是任何房间的被指派者，也不再是房主。
        // 两样都要清 —— 只要还留着「我是被指派的」，下一次进房就会抢着算一份没人要的计划。
        RoomAutomix.setAssigned(false, 0L);
        RoomAutomix.setNobodyCanAnalyze(false);
        RoomAutomix.setOwner(false);
    }

    private static int heartbeatCounter;
    private static int presenceCounter;
    /** 上一次真正发出在线态查询的时刻：面板打开时的「立即补一轮」判据。 */
    private static volatile long lastPresencePull;

    private static void tick() {
        try {
            MpPlayback.tick();
            // 通道声明有时比 JOIN 事件晚到一点，那一次 hello 就白发了；这里按秒补发，
            // 直到服务端回话或者试探次数用完。
            //
            // 补发之前先过一道「在不在世界里」：ticker 从 mod 初始化就在跑，而标题界面 /
            // 连接中 / 掉线画面上根本没有能发出去的连接（本机发送端会当场抛
            // "Cannot send packets when not in game!"）。失败不计配额，因此不加这道门就会
            // 以每秒一次的频率一直白抛、白构造 JSON，直到真的进服为止 —— 实测在标题界面
            // 停留 5 分钟，就是三百多次。日志里那行「[MpParty] 发送失败」正是它刷出来的。
            if (!helloAcked && inGame()) {
                if (available()) serverPresent = true;
                probeHello();
            }
            if (!usable()) return;
            // 在线态那三帧问的是「整台服务器」的快照，跟着 250ms 的心跳走等于白烧带宽 ——
            // 单独一个一秒的计数器，而且只在界面确实在看的时候才问（见 presenceInterest）。
            if (v2() && ++presenceCounter >= 20) {
                presenceCounter = 0;
                lastPresencePull = System.currentTimeMillis();
                if (System.currentTimeMillis() - presenceInterest < 10_000L) {
                    sendQuiet(MpProto.msg(MpProto.C_PRESENCE));
                    if (room == null) sendQuiet(MpProto.msg(MpProto.C_ROOMS));
                    sendQuiet(MpProto.msg(MpProto.C_INVITES));
                }
            }
            if (++heartbeatCounter < 5) return;
            heartbeatCounter = 0;
            // 心跳<b>不再</b>因为「不在房间里」就不发：全服在线态那一页（谁在听什么）就靠它。
            // 顺带把身份也捎上 —— 登录了一个新平台、或者刚打开贡献开关，下一发心跳就生效。
            JsonObject msg = MpProto.msg(MpProto.C_PROGRESS);
            msg.addProperty("pos", MpPlayback.localPositionMs());
            msg.addProperty("playing", MpPlayback.localPlaying());
            fillProfile(msg);
            sendQuiet(msg);
        } catch (Throwable ignored) {
        }
    }

    // ── 读接口 ───────────────────────────────────────────────────────────────

    /** 当前服务器是否装了一起听服务端 mod。 */
    public static boolean available() {
        MpHost h = host;
        if (h == null) return false;
        try {
            return h.canSend();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean serverPresent() {
        return helloAcked || serverPresent || available();
    }

    /** 能不能往服务端发东西：通道被声明了，或者服务端已经回过话。 */
    private static boolean usable() {
        return helloAcked || available();
    }

    /**
     * 本机现在是不是处在一个世界里（标题界面、连接中、掉线画面都是 {@code false}）。
     *
     * <p>刻意<b>不</b>借道 {@link #available()}：那个问的是「服务端声明了 mpsync 通道吗」，
     * 在 Paper 这类不声明通道的服务端上，进服之后它照样是 false —— 一个是问「能不能发」，
     * 一个是问「有没有连接」，在这里要的答案完全不同。</p>
     *
     * <p>{@link MpHost#selfUuid()} 就是现成的答案：各版本的实现无一例外都是
     * {@code mc.player == null ? null : mc.player.getUUID()}，玩家实体不在就等于不在世界里。</p>
     */
    private static boolean inGame() {
        MpHost h = host;
        if (h == null) return false;
        try {
            return h.selfUuid() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int serverProtocol() {
        return serverProto;
    }

    public static boolean inRoom() {
        return room != null;
    }

    public static MpRoom room() {
        return room;
    }

    public static MpTrack currentTrack() {
        return currentTrack;
    }

    public static boolean playing() {
        return playing;
    }

    public static long durationMs() {
        return durationMs;
    }

    public static boolean waitingUrl() {
        return waitingUrl;
    }

    public static boolean membersCanEnqueue() {
        return membersCanEnqueue;
    }

    public static String lastMessage() {
        return lastMessage;
    }

    public static boolean lastMessageIsError() {
        return lastMessageIsError;
    }

    public static String inviteCode() {
        return inviteCode;
    }

    public static String inviteRoomName() {
        return inviteRoomName;
    }

    public static String inviteFrom() {
        return inviteFrom;
    }

    /** 一起听歌单 —— 待播的点歌请求（不在房间时为空）。 */
    public static List<MpTrack> queue() {
        MpRoom r = room;
        return r == null ? Collections.<MpTrack>emptyList() : r.queue;
    }

    public static List<MpTrack> history() {
        MpRoom r = room;
        return r == null ? Collections.<MpTrack>emptyList() : r.history;
    }

    public static List<MpMember> members() {
        MpRoom r = room;
        return r == null ? Collections.<MpMember>emptyList() : r.members;
    }

    /** 房间位置，在两个服务端时钟帧之间外推。 */
    public static long positionMs() {
        long pos = playing ? posBase + (System.currentTimeMillis() - posStamp) : posBase;
        if (pos < 0L) pos = 0L;
        long dur = durationMs;
        return dur > 0L && pos > dur ? dur : pos;
    }

    public static UUID selfUuid() {
        MpHost h = host;
        return h == null ? null : h.selfUuid();
    }

    public static MpMember self() {
        MpRoom r = room;
        UUID uuid = selfUuid();
        return r == null || uuid == null ? null : r.member(uuid);
    }

    /** 房主或管理员 —— 这两个角色职权完全相同。 */
    public static boolean isController() {
        MpMember me = self();
        return me != null && me.controls();
    }

    public static boolean isOwner() {
        MpRoom r = room;
        UUID uuid = selfUuid();
        return r != null && uuid != null && uuid.equals(r.owner);
    }

    public static void addListener(Runnable r) {
        if (r != null) LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    // ── 读接口：v2 ───────────────────────────────────────────────────────────

    /** 这台服务端认不认识 v2（在线态 / 公开房 / 频道 / 投票）。 */
    public static boolean v2() {
        return usable() && serverProto >= 2;
    }

    public static boolean isServerAdmin() {
        return serverAdmin;
    }

    public static boolean channelEnabled() {
        return channelEnabled;
    }

    public static String channelName() {
        return channelName == null || channelName.isEmpty() ? "服务器频道" : channelName;
    }

    public static int channelMaxPerMember() {
        return channelMaxPerMember;
    }

    public static boolean publicRoomsEnabled() {
        return publicRoomsEnabled;
    }

    public static long voteWindowMs() {
        return voteWindowMs;
    }

    /** 服主配了平台 Token，没有会员的人也能点会员曲。 */
    public static boolean serverResolve() {
        return serverResolve;
    }

    public static boolean isBanned() {
        return banned;
    }

    public static String banReason() {
        return banReason;
    }

    public static String banRemaining() {
        return banRemaining;
    }

    public static boolean selfPaused() {
        return selfPaused;
    }

    /** 当前在的是不是服务器频道。 */
    public static boolean inChannel() {
        MpRoom r = room;
        return r != null && r.channel;
    }

    /** 我在这个房间里有没有直接控制权（频道里 = 服务器管理员）。 */
    public static boolean hasControl() {
        MpRoom r = room;
        if (r == null) return false;
        if (r.channel) return serverAdmin;
        MpMember me = self();
        return me != null && me.controls();
    }

    public static List<MpPresence> presence() {
        return presenceList;
    }

    public static int onlineCount() {
        return onlineCount;
    }

    public static List<MpPublicRoom> publicRooms() {
        return publicRooms;
    }

    public static List<MpInvite> pendingInvites() {
        return pendingInvites;
    }

    /** 当前进行中／刚结束的投票；没有返回 null。 */
    public static MpVote openVote() {
        MpRoom r = room;
        return r == null ? null : r.openVote();
    }

    /**
     * 「我正在看在线态」。界面每帧调一次，{@link #tick()} 据此决定要不要继续轮询。
     *
     * <p>为什么不是「打开界面时订阅、关闭时退订」：界面可能被 ESC、切屏、房间解散等好几条
     * 路径关掉，任何一条漏了退订就会永久轮询下去。用时间戳自然过期，漏不了。
     */
    public static void touchPresence() {
        long now = System.currentTimeMillis();
        // 面板开着就算「正在看」：大厅统计、邀请角标、在线页、发现页全靠这一句续命。
        presenceInterest = now;
        // 立即性：轮询周期 20 秒太久，「在线」页/邀请页开着时要看到别人进出房间、
        // 收到邀请能马上变出来。距上次拉取超过 5 秒就把计数器顶满，下一跳立刻补一轮；
        // 平时（计数未满）不影响原有的 20 秒节流。
        if (now - lastPresencePull >= 5_000L) {
            presenceCounter = Math.max(presenceCounter, 19);
        }
    }

    // ── 写接口 ───────────────────────────────────────────────────────────────

    public static void createRoom(String name) {
        if (officialRoomConflict() || directRoomConflict()) return;
        JsonObject m = MpProto.msg(MpProto.C_CREATE);
        if (name != null && !name.trim().isEmpty()) m.addProperty("name", name.trim());
        send(m);
    }

    public static void joinRoom(String code) {
        if (officialRoomConflict() || directRoomConflict()) return;
        JsonObject m = MpProto.msg(MpProto.C_JOIN);
        m.addProperty("code", code == null ? "" : code.trim());
        send(m);
    }

    /**
     * 与官方一起听（网易云/酷狗）互斥：两套引擎都想独占播放器，同时开会互相拉扯
     * （官方 worker 每秒差分本地播放并整表回写房间，MC 房间的 S_PLAY 又改写本地播放）。
     * 入口挡死并写明原因。
     */
    private static boolean officialRoomConflict() {
        if (!ListenTogetherService.isActive()) return false;
        note("官方一起听正在进行：先退出官方房间，再使用服务器房间", true);
        return true;
    }

    /**
     * 直连会话中挡住「服务器房间」页的创建/加入。
     *
     * <p>直连活跃时发送路由已指向本机直连引擎（MpHostRouter.current()=socket），
     * 这里再点「创建房间」不是无效操作，而是会在同一个引擎里凭空开出第二个房间：
     * 自动建房帧（MpDirect.sendCreate）稍后被「已在房间中」拒掉，用户看到的就成了
     * 一个名字、密码、端口都对不上直连邀请串的房间，且关房时被连带清掉。与
     * officialRoomConflict 同位加闸——房间的创建权归直连会话自己的建房流程。</p>
     */
    private static boolean directRoomConflict() {
        if (!com.muoniumplayer.party.direct.MpDirect.isActive()) return false;
        note("直连房间正在进行：房间操作请到直连房间页进行", true);
        return true;
    }

    public static void leaveRoom() {
        send(MpProto.msg(MpProto.C_LEAVE));
    }

    public static void disband() {
        send(MpProto.msg(MpProto.C_DISBAND));
    }

    public static void sync() {
        send(MpProto.msg(MpProto.C_SYNC));
    }

    public static void invite(String playerName) {
        JsonObject m = MpProto.msg(MpProto.C_INVITE);
        m.addProperty("target", playerName == null ? "" : playerName.trim());
        send(m);
    }

    /** 授予/撤销管理员 —— 管理员与房主职权相同。 */
    public static void setAdmin(String playerName, boolean admin) {
        JsonObject m = MpProto.msg(MpProto.C_SET_ROLE);
        m.addProperty("target", playerName == null ? "" : playerName.trim());
        m.addProperty("role", admin ? "admin" : "member");
        send(m);
    }

    public static void kick(String playerName) {
        JsonObject m = MpProto.msg(MpProto.C_KICK);
        m.addProperty("target", playerName == null ? "" : playerName.trim());
        send(m);
    }

    public static void transferOwner(String playerName) {
        JsonObject m = MpProto.msg(MpProto.C_TRANSFER);
        m.addProperty("target", playerName == null ? "" : playerName.trim());
        send(m);
    }

    /** 加入服务器频道（全服公开大房间）。 */
    public static void joinChannel() {
        if (!requireChannel()) return;
        if (!channelEnabled) {
            note("这台服务器没有开启服务器频道", true);
            return;
        }
        send(MpProto.msg(MpProto.C_CHANNEL_JOIN));
    }

    /** 把自己的房间设为公开 / 私密。 */
    public static void setPublic(boolean open) {
        if (!requireChannel()) return;
        JsonObject m = MpProto.msg(MpProto.C_SET_PUBLIC);
        m.addProperty("public", open);
        send(m);
    }

    public static void requestPresence() {
        if (v2()) sendQuiet(MpProto.msg(MpProto.C_PRESENCE));
    }

    public static void requestPublicRooms() {
        if (v2()) sendQuiet(MpProto.msg(MpProto.C_ROOMS));
    }

    public static void requestInvites() {
        if (v2()) sendQuiet(MpProto.msg(MpProto.C_INVITES));
    }

    public static void acceptInvite(String roomId) {
        if (!requireChannel()) return;
        JsonObject m = MpProto.msg(MpProto.C_INVITE_ACCEPT);
        if (roomId != null && !roomId.isEmpty()) m.addProperty("roomId", roomId);
        send(m);
    }

    public static void declineInvite(String roomId) {
        if (!requireChannel()) return;
        JsonObject m = MpProto.msg(MpProto.C_INVITE_DECLINE);
        if (roomId != null && !roomId.isEmpty()) m.addProperty("roomId", roomId);
        send(m);
    }

    /**
     * 个人暂停 / 恢复。
     *
     * <p>本地先落地再上报，不等服务端回话：按下去就该立刻静音，否则用户会以为按钮没反应而连按。
     * 恢复时不在本地擅自起播 —— 要等服务端补的那一帧权威位置，否则会从一个过期位置开始放，
     * 又得纠偏一次。
     */
    public static void setSelfPause(boolean paused) {
        if (!requireChannel()) return;
        // 标记只在「进入暂停」这一下置起来；解除交给服务端那帧 play（分发里见 resync 就清），
        // 否则下一发 S_TIME 心跳会抢在权威位置之前、从本地暂停点开声，进度先跳一下再被纠正。
        if (paused) selfPaused = true;
        if (paused) MpPlayback.pause(positionMs());
        JsonObject m = MpProto.msg(MpProto.C_SELF_PAUSE);
        m.addProperty("paused", paused);
        send(m);
        notifyListeners();
    }

    public static void toggleSelfPause() {
        setSelfPause(!selfPaused);
    }

    /** 发起切歌投票（频道里非管理员按「下一首」走的就是这条）。 */
    public static void startSkipVote() {
        if (!requireChannel()) return;
        JsonObject m = MpProto.msg(MpProto.C_VOTE_START);
        m.addProperty("kind", MpProto.VOTE_SKIP);
        send(m);
    }

    public static void startKickVote(UUID target) {
        if (!requireChannel()) return;
        if (target == null) return;
        JsonObject m = MpProto.msg(MpProto.C_VOTE_START);
        m.addProperty("kind", MpProto.VOTE_KICK);
        m.addProperty("target", target.toString());
        send(m);
    }

    public static void castVote(String voteId, boolean agree) {
        if (!requireChannel()) return;
        JsonObject m = MpProto.msg(MpProto.C_VOTE_CAST);
        if (voteId != null && !voteId.isEmpty()) m.addProperty("voteId", voteId);
        m.addProperty("agree", agree);
        send(m);
    }

    /** 立刻把身份重新报一次（打开/关闭贡献开关、登录了新平台之后调用）。 */
    public static void reportProfile() {
        if (!usable()) return;
        JsonObject m = MpProto.msg(MpProto.C_PROFILE);
        fillProfile(m);
        sendQuiet(m);
    }

    /**
     * 填入这台客户端的自报信息。
     *
     * <p><b>三档身份分开报，不再混为一谈。</b>以前只有一个 {@code vip}，填的其实是登录态，
     * 于是「登录了但不是会员」的成员会被服务端当成最可能答上的人问到，而他答出来必然是试听片段
     * —— 整房的求助窗口白等一轮。现在：
     * <ul>
     *   <li>{@code vip} —— 该平台会话在。语义与老版本一致，服务端仍拿它做兜底；</li>
     *   <li>{@code member} —— 该平台<b>真会员</b>。查不到档位的平台（QQ 的上游就没给会员字段、
     *       YouTube Music 同理）宁可两边都不出现，也不谎报一个猜出来的值；</li>
     *   <li>{@code resolvable} —— 本机现在能给出<b>完整版</b>音频的平台，含「这个平台本来就不卖
     *       会员」与「靠 LX 脚本能解」两路，判据与换源闸门同一份代码；</li>
     *   <li>{@code viaLx} —— {@code resolvable} 的子集：只有靠本机导入的 LX 自定义源才解得出的平台。</li>
     * </ul>
     *
     * <p>取用<b>零网络</b>：本方法在房间心跳（每 5 秒）里跑，任何一次查询都不许发请求，
     * 会员档位读的都是登录时就落在内存/凭证里的快照。LX 的渠道键（wy/tx/kg/kw/mg）在这里
     * 已经翻成房间认识的平台令牌 —— 自定义源是本机的私事，服务端不必认识那套词表。
     *
     * <p><b>只报平台名，不报任何凭据。</b>cookie 一律留在本机 —— 需要时是本机解析出一条带签名的
     * 临时直链再上传（{@code url_supply}），账号本身永远不出这台机器。
     *
     * <p>{@code MOD_VERSION} / {@code MC_VERSION} 是 {@code static final String} 常量，javac 会
     * 直接内联进本类，所以这里不会在运行期加载 {@code MuoniumPlayerClient}（那个类碰 Minecraft，
     * 而本方法跑在 mp-party 线程上）。
     */
    private static void fillProfile(JsonObject m) {
        m.addProperty("proto", MpProto.VERSION);
        m.addProperty("client", MpProto.CLIENT_ID);
        m.addProperty("modName", MpProto.CLIENT_NAME);
        m.addProperty("modVersion", MuoniumPlayerClient.MOD_VERSION + " / MC " + MuoniumPlayerClient.MC_VERSION);
        m.addProperty("contributeVip", contributeVip());
        m.addProperty("contributeLx", contributeLx());
        JsonArray vip = new JsonArray();
        JsonArray member = new JsonArray();
        JsonArray resolvable = new JsonArray();
        JsonArray viaLx = new JsonArray();
        for (MusicPlatform platform : MusicPlatform.values()) {
            try {
                String token = MpTrack.token(platform);
                if (PlatformAccess.loggedIn(platform)) vip.add(token);
                if (Boolean.TRUE.equals(PlatformAccess.memberTier(platform))) member.add(token);
                if (CrossSourceSwitcher.hasVipAccess(platform)) resolvable.add(token);
                if (CrossSourceSwitcher.lxCovers(platform)) viaLx.add(token);
            } catch (Throwable ignored) {
                // 某个平台的会话状态查询抛了，不该拖垮整条心跳。
            }
        }
        m.add("vip", vip);
        m.add("member", member);
        m.add("resolvable", resolvable);
        m.add("viaLx", viaLx);
        // 房间交接计划的分析能力。服务端据此指派「谁来算这一首的交接点」——
        // 优先点歌人，其次房主，再次房间里任何一台自报能算的。
        //
        // 为什么必须自报、不能由服务端猜：这件事完全在本机进程里 —— 有没有那套分析代码、
        // 装没装模型、音频解码器能不能用。服务端没有音频，也没有这些信息。
        m.addProperty("automixCapable", RoomAutomix.capable());
        m.addProperty("automixEngine", RoomAutomix.engine());
        // 本机有没有「换到别的平台的同一首歌」那条流水线。服务端全层落空之后要不要专门问一句
        // 点歌人，看的就是这一位 —— 没有这条流水线的人（modernMP 只有各平台自己的解析链路）
        // 收到那一句只会让整房多等一个救援窗，什么也换不来，所以宁可不问。
        m.addProperty("switchRescue", CrossSourceSwitcher.rescueCapable());
        Music now = localNowPlaying();
        m.addProperty("nowPlaying", now == null ? "" : safe(now.getName()));
        m.addProperty("nowArtist", now == null ? "" : safe(now.getArtistsName()));
        m.addProperty("localPlaying", MpPlayback.localPlaying());
        // 直连房间的身份与鉴权（纯加法，服务器房间模式下服务端 mod 忽略未知字段）：
        // 引擎没有 MC 连接可查名字，会话表靠自报的 uuid/playerName 建立；
        // auth 只在直连会话活跃时带上（服务器房间的 hello 里永远没有这个字段）。
        UUID self = selfUuidSafe();
        m.addProperty("uuid", self == null ? "" : self.toString());
        String selfName = selfNameSafe();
        m.addProperty("playerName", selfName == null ? "" : selfName);
        String auth = directAuth();
        if (!auth.isEmpty()) m.addProperty("auth", auth);
    }

    private static UUID selfUuidSafe() {
        try {
            return host == null ? null : host.selfUuid();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String selfNameSafe() {
        try {
            return host == null ? null : host.selfName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String directAuth() {
        try {
            return com.muoniumplayer.party.direct.MpDirect.helloAuth();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 本机此刻在听什么（不限于房间曲目：全服在线态要的是「他在听什么」）。 */
    private static Music localNowPlaying() {
        try {
            return CloudMusic.currentlyPlaying;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 是否愿意替别人解析（贡献会员解析能力）。默认开，账号凭证仍然一步都不出本机。 */
    public static boolean contributeVip() {
        try {
            return ListenTogetherSettings.isPartyContributeVip();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 是否愿意让本机<b>用 LX 自定义源</b>替房间解歌（与贡献本家会员互相独立）。
     *
     * <p>关掉它只影响「替别人答」这一路：本机自己播放时照常使用自定义源。
     */
    public static boolean contributeLx() {
        try {
            return ListenTogetherSettings.isPartyContributeLx();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 平台 → 协议里的 source 令牌。必须和服务端认识的那套一致。
     *
     * <p>以前这里只认 QQ / 酷狗 / GD，其余一律落到 {@code netease} —— 于是 B 站、汽水、YouTube
     * 的登录态会被报成「我是网易云的人」，服务端的分层求助就把本该问别人家的歌问到网易云贡献者
     * 头上。现在直接复用 {@link MpTrack#token}：它就是曲目上报用的那套令牌，两处必须同源，
     * 否则「上报我能解什么」和「这条曲目是哪个平台」会各说各话。
     */
    private static String sourceOf(MusicPlatform platform) {
        return MpTrack.token(platform);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static void pause() {
        send(MpProto.msg(MpProto.C_PAUSE));
    }

    public static void resume() {
        send(MpProto.msg(MpProto.C_RESUME));
    }

    public static void next() {
        send(MpProto.msg(MpProto.C_NEXT));
    }

    public static void prev() {
        send(MpProto.msg(MpProto.C_PREV));
    }

    public static void clearQueue() {
        send(MpProto.msg(MpProto.C_CLEAR));
    }

    public static void seek(long ms) {
        JsonObject m = MpProto.msg(MpProto.C_SEEK);
        m.addProperty("pos", Math.max(0L, ms));
        send(m);
    }

    public static void setRepeat(String mode) {
        JsonObject m = MpProto.msg(MpProto.C_REPEAT);
        m.addProperty("mode", mode == null ? "off" : mode);
        send(m);
    }

    public static void playEntry(long entryId) {
        JsonObject m = MpProto.msg(MpProto.C_PLAY_INDEX);
        m.addProperty("entryId", entryId);
        send(m);
    }

    public static void removeEntry(long entryId) {
        JsonObject m = MpProto.msg(MpProto.C_REMOVE);
        m.addProperty("entryId", entryId);
        send(m);
    }

    public static void moveEntry(long entryId, int to) {
        JsonObject m = MpProto.msg(MpProto.C_MOVE);
        m.addProperty("entryId", entryId);
        m.addProperty("to", to);
        send(m);
    }

    /** 点歌：追加到一起听歌单末尾。 */
    public static void requestSong(Music song) {
        upload(song, MpProto.C_ENQUEUE, false);
    }

    /** 「下一首播放」：房主/管理员真的插到下一首，普通成员仍然只是追加。 */
    public static void queueNext(Music song) {
        upload(song, MpProto.C_ENQUEUE, true);
    }

    /** 房主/管理员的自由切歌。 */
    public static void playNow(Music song) {
        upload(song, MpProto.C_PLAY_NOW, false);
    }

    /** 用一段网易云分享文案/链接/ID 点歌。 */
    public static void requestSongByLink(String link) {
        fromLink(link, MpProto.C_ENQUEUE);
    }

    /** 用一段网易云分享文案/链接/ID 直接切歌（房主/管理员）。 */
    public static void playNowByLink(String link) {
        fromLink(link, MpProto.C_PLAY_NOW);
    }

    // ── 播放歌单：把一整张单子排进房间 ────────────────────────────────────────
    //
    // 与一首一首点歌是三处不同，每一处都是必要的：
    //   ① 只发<b>元数据</b>，不在本机逐首取流。一首一首点时点歌人顺手把自己解得出的直链带上是
    //      省事；一次灌 100 首再这么做，就是拿自己的账号（或者房间里那几个会员的账号）按着一张
    //      单子猛抽一遍，而其中大部分要等几十分钟才轮到，那时链接早过期了。链接改由服务端按
    //      前瞻窗逐首按需向房间要（{@code Room#resolveLookahead}）。
    //   ② 分帧发。单帧有上限，而一张 500 首的单子在本机是一次就凑得齐的。
    //   ③ <b>永不打断正在播的那一首</b>：这里没有任何 playNow 路径，整批全部落最低优先级档。

    /** 一帧带多少首。服务端一帧封顶 200，这里留足余量又不至于把帧拼得太碎。 */
    private static final int BATCH_CHUNK = 50;

    /**
     * 这台服务端认不认识「播放歌单」，以及<b>当前这个人</b>能不能按那颗按钮。
     *
     * <p>三个条件缺一不可，而且每一个都可能单独不成立：</p>
     * <ul>
     *   <li>{@code playlistBatchLimit > 0}：老服务端不发这个字段（取 0），服主也可以把它配成 0
     *       明确关掉。这时候按钮必须<b>不出现</b>，而不是出现之后按出一句 error —— 界面不能把
     *       「服务端版本不够」伪装成「你这个操作不行」。</li>
     *   <li>在房间里，且<b>不是</b>服务器频道：频道是全服共用的，那里一个人倒进一百首会把所有人的
     *       排队压到几个小时之后，而频道本来就有「人均 3 首」这一档在管着逐首点歌。</li>
     *   <li>权限：服务端配了 {@code playlistAdminOnly}（默认开）时只有房主/管理员能灌。
     *       这一条只影响按钮的 enabled，不影响它显不显示 —— 看得见但按不动，别人才知道功能是有的、
     *       只是这台服务器把它收给了管理组。</li>
     * </ul>
     */
    public static boolean canPlaylist() {
        // 先过「出现」那一关再看权限。两条判据分开写迟早会分叉 —— 而分叉的表征是「按钮出现了但
        // 按下去没反应」或者「服务端明明支持却整颗看不见」，两种都最难查。
        if (!playlistSupported()) return false;
        return !playlistAdminOnly || hasControl();
    }

    /** 这颗按钮该不该出现（不看权限，只看功能存不存在）。 */
    public static boolean playlistSupported() {
        return room != null && playlistBatchLimit > 0 && !inChannel();
    }

    /**
     * 把一张歌单排进房间：先确保曲目真的在手上，再分帧发。
     *
     * <p>一律走 {@link PlayList#loadMusicsWithCallback} 而不是直接读 {@code getMusics()}：
     * 那个 getter 顺手会启动一次上游查询并<b>立刻返回当下有的那一点</b>。灌一张还在加载中的单子
     * 却只排进前 12 首，比报一句错难查得多 —— 界面上看起来一切都对，只是「歌单没灌完」。
     * 曲目已经在手上时（本地单、点开过的账号单）它自己就立刻回调，所以这里不多等一轮。</p>
     *
     * @return true 表示这个请求被房间接下了（不代表已经发出去 —— 单子可能还在下载）
     */
    public static boolean requestPlaylist(PlayList playlist) {
        if (playlist == null) return false;
        if (!canPlaylist()) {
            note(inChannel() ? "服务器频道不支持播放整张歌单，请一首一首点"
                    : playlistBatchLimit <= 0 ? "当前服务器未开启播放歌单功能"
                    : "这个房间只有房主与管理员能播放整张歌单", true);
            return true;
        }
        if (!requireChannel()) return true;
        final String label = playlistTitle(playlist);
        note("正在读取歌单《" + label + "》…", false);
        try {
            playlist.loadMusicsWithCallback(new PlayList.MusicsLoadedCallback() {
                @Override
                public void onMusicsLoaded(List<Music> musics) {
                    // 回调可能在查询线程上跑，而 sendBatch 自己会再转一次后台线程，这里不额外处理。
                    sendBatch(musics, label, false);
                }
            });
        } catch (Throwable throwable) {
            note("读取这张歌单失败了：" + throwable, true);
        }
        return true;
    }

    /**
     * 从一张歌单里<b>随机抽 {@code count} 首</b>排进房间。
     *
     * <p>为什么成员协作场景需要它：账号歌单动辄几百上千首，整张灌进去会被单次上限截断，
     * 而且排在队尾的那几百首多半永远轮不到 —— 「随机 50」给出的正是「这张单今天听这些」的
     * 体验，一次投放轻、别人点歌也还有位置。洗牌只发生在客户端内存里，协议与服务端
     * 都不感知「随机」这件事（{@code enqueue_batch} 收到的就是普通一批）。</p>
     *
     * <p>长单子的曲目表可能分批到位：{@code loadMusicsWithCallback} 在没拉完时回调的只是
     * <b>当下已有</b>的那一段，随机抽样也就只在那段里抽 —— 这对「随机」语义是可接受的退化
     * （抽到的是歌单头部的一段随机子集，而不是全单均匀抽样），不值得为它把整张千首单子拉完。</p>
     *
     * @param count 想抽多少首；超过单次上限时会被 {@code playlistBatchLimit} 截断（发送侧提示语会说明）
     */
    public static boolean requestPlaylistShuffled(PlayList playlist, int count) {
        if (playlist == null) return false;
        if (count <= 0) return requestPlaylist(playlist);
        if (!canPlaylist()) {
            note(inChannel() ? "服务器频道不支持播放整张歌单，请一首一首点"
                    : playlistBatchLimit <= 0 ? "当前服务器未开启播放歌单功能"
                    : "这个房间只有房主与管理员能播放整张歌单", true);
            return true;
        }
        if (!requireChannel()) return true;
        final String label = playlistTitle(playlist);
        note("正在读取歌单《" + label + "》，准备随机抽 " + Math.min(count, playlistBatchLimit) + " 首…", false);
        try {
            playlist.loadMusicsWithCallback(new PlayList.MusicsLoadedCallback() {
                @Override
                public void onMusicsLoaded(List<Music> musics) {
                    // 回调可能在查询线程上跑：在这里把整段拷一份再洗，不动原列表的顺序 ——
                    // 播放器侧栏还按原顺序展示这张单子。
                    List<Music> pool = new ArrayList<Music>(musics == null ? Collections.<Music>emptyList() : musics);
                    Collections.shuffle(pool);
                    List<Music> picked = pool.size() > count ? pool.subList(0, count) : pool;
                    sendBatch(new ArrayList<Music>(picked), label, true);
                }
            });
        } catch (Throwable throwable) {
            note("读取这张歌单失败了：" + throwable, true);
        }
        return true;
    }

    private static String playlistTitle(PlayList playlist) {
        // 和选单页显示的是同一个标签（含同一套 80 字裁剪），否则「已排 N 首」与「撤回」会各自
        // 比着一个不同的字符串，长名单子就会出现「看得见、撤不动」。
        return MpRoomPlaylists.labelOf(playlist);
    }

    /**
     * 分帧把这批歌排进房间，全部落在「歌单待播」那一档。
     *
     * <p>客户端也按 {@code playlistBatchLimit} 截一次：服务端会截，但让它截意味着我们要多发
     * 十几帧注定只被读前半段的负载。上限取<b>两边较小</b>的那个，谁的配置 tighter 就用谁的。</p>
     */
    private static void sendBatch(final List<Music> songs, final String label) {
        sendBatch(songs, label, false);
    }

    /**
     * 分帧把这批歌排进房间，全部落在「歌单待播」那一档。
     *
     * <p>客户端也按 {@code playlistBatchLimit} 截一次：服务端会截，但让它截意味着我们要多发
     * 十几帧注定只被读前半段的负载。上限取<b>两边较小</b>的那个，谁的配置 tighter 就用谁的。</p>
     *
     * @param shuffled 这批是不是「随机抽出来的」——只影响提示语：随机批次说「已随机排入 N 首」，
     *                 整张灌的提示保留「按单次上限截断」那半句，两种动作的意外不同
     */
    private static void sendBatch(final List<Music> songs, final String label, final boolean shuffled) {
        if (songs == null || songs.isEmpty()) {
            note("歌单《" + label + "》是空的，什么也没排", true);
            return;
        }
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                List<Music> usable = new ArrayList<Music>();
                for (Music song : songs) {
                    if (!canQueue(song)) continue;   // 拿不出平台主键的条目服务端本来就会拒
                    usable.add(song);
                }
                if (usable.isEmpty()) {
                    note("歌单《" + label + "》里没有能分享给房间的曲目", true);
                    return;
                }
                int limit = playlistBatchLimit > 0 ? Math.min(playlistBatchLimit, usable.size())
                        : usable.size();
                int sent = 0;
                for (int from = 0; from < limit; from += BATCH_CHUNK) {
                    int to = Math.min(limit, from + BATCH_CHUNK);
                    JsonObject m = MpProto.msg(MpProto.C_ENQUEUE_BATCH);
                    m.addProperty("playlist", label);
                    JsonArray tracks = new JsonArray();
                    for (int i = from; i < to; i++) {
                        // url 传空串：见上面第 ① 条，链接不由点歌人一次性备齐。
                        tracks.add(MpTrack.toJson(usable.get(i), "", 0L, 0L));
                    }
                    m.add("tracks", tracks);
                    send(m);
                    sent += to - from;
                }
                note(shuffled
                        ? "已从《" + label + "》随机排入 " + sent + " 首到房间"
                        : (sent < usable.size()
                        ? "已把歌单《" + label + "》的前 " + sent + " 首排进房间（这张单有 "
                                + usable.size() + " 首，按单次上限截断）"
                        : "已把歌单《" + label + "》的 " + sent + " 首排进房间"), false);
            }
        });
    }

    /** 房间是否正在接管播放：为真时播放器不得自己起播。 */
    public static boolean blocksLocalPlayback() {
        return room != null;
    }

    /**
     * 这首歌能不能进房间歌单。
     *
     * <p>不再限定网易云：房间转发的是点歌人自己解析出来的直链，任何本机能解析的平台都行。
     * 挡住的只有拿不出可用平台主键的条目。</p>
     */
    public static boolean canQueue(Music song) {
        return song != null && isUsableSongId(song.getSourceId());
    }

    /** 与服务端 {@code SourceGuard.isValidSongId} 同款：非空、≤64、不含分隔符与空白。 */
    private static boolean isUsableSongId(String id) {
        if (id == null) return false;
        String s = id.trim();
        if (s.isEmpty() || s.length() > 64 || s.equals("0")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == ':' || c == '"' || c == '\\') return false;
        }
        return true;
    }

    private static void fromLink(final String link, final String op) {
        if (!requireChannel()) return;
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                long id = MpLink.resolveId(link);
                if (id <= 0L) {
                    note("无法识别网易云链接或歌曲 ID", true);
                    return;
                }
                Music song = MpNetease.songById(id);
                if (song == null) {
                    note("获取歌曲信息失败", true);
                    return;
                }
                upload(song, op, false);
            }
        });
    }

    /**
     * 本机解析取流链接，再把「元数据 + 链接」一起上传。
     *
     * <p>链接必须由客户端解析：它是按解析者自己的平台会话签出来的，服务端一个会话都没有。
     *
     * <p>网易云单独走 {@link MpNetease#resolve}，因为它会顺带报出「这条链接实际能取到多长的
     * 音频」，据此才能识别会员试听片段；其他平台走播放器通用的 {@link Music#getPlayUrl()}，
     * 拿不到这个长度，试听保护对它们失效（未知按放行处理）。
     */
    private static void upload(final Music song, final String op, final boolean playNext) {
        if (song == null || !requireChannel()) return;
        if (!isUsableSongId(song.getSourceId())) {
            note("这首歌没有可用的曲目 ID，无法点歌", true);
            return;
        }
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                if (song.isNetease() && song.getId() > 0L) {
                    // 本家这一跳留着，不是因为它比脚本好，而是因为它会报「这条链接实际服务了
                    // 多长」—— 只有拿得到这个长度才识破得了 30/60 秒试听片段，而服务端采纳别人
                    // 应答的那条链路也按这个数拒试听。会员本家解出的完整版仍然优先于脚本解出的
                    // 「长度未知」的链接。
                    MpNetease.Resolved resolved = MpNetease.resolve(song.getId());
                    if (resolved != null && !isTrialClip(resolved.servedMs, song.getDuration())) {
                        sendTrack(op, song, resolved.url, resolved.servedMs, playNext);
                        return;
                    }
                    // 本家给不出完整版（没登录、不是会员、只解到试听片段）时先问自己的自定义源：
                    // 用户导入并启用它就是为了用它取流，房间里有没有会员跟这一首能不能放无关。
                    String viaCustom = customSourceUrl(song);
                    if (viaCustom != null) {
                        // 脚本不报「这条链接实际多长」，估一个并报成估算值（见 estimateServedMs）；
                        // 什么都不报的话，服务端连「这是不是 30 秒试听」都无从判断。
                        long est = estimateServedMs(song, viaCustom, song.getDuration());
                        sendTrack(op, song, viaCustom, est, playNext, est > 0L);
                        return;
                    }
                    // 问别人之前先在<b>本机</b>换一次平台：这条流水线是用户自己装的，
                    // 一台机器上十几秒的搜索比问遍全房便宜，而换到了就没有任何人需要为此答一次。
                    if (switchLocally(op, song, playNext)) return;
                    delegateResolve(op, song, playNext,
                            resolved == null ? "本机拿不到播放链接" : "本机只解析到试听片段");
                    return;
                }
                String url = resolveByPlayer(song);
                if (url == null) {
                    if (switchLocally(op, song, playNext)) return;
                    delegateResolve(op, song, playNext, "本机拿不到播放链接");
                    return;
                }
                // 其他平台不报「链接实际时长」，只能估一个（估不出来就传 0 = 未知，服务端按放行处理）。
                // 不估的代价是实打实的：非会员在酷狗拿到的试听片段，会作为整房共用的那一条链接
                // 一路畅通地放给所有人听 30 秒。
                long est = estimateServedMs(song, url, song.getDuration());
                sendTrack(op, song, url, est, playNext, est > 0L);
            }
        });
    }

    /**
     * 非网易云平台的取流：走播放器自己的解析链路。
     *
     * <p>{@link MpNetease#resolve} 刻意避开这条路（它在官方源失败后会接第三方解灰源，那种链接对
     * 别人的机器不一定可用），但 QQ / 酷狗 / GD 本来就只有这条路可走 —— 共享直链模型下这是可以
     * 接受的取舍：链接不通时房间会走 {@code url_needed} 向别的成员再要一条。</p>
     */
    private static String resolveByPlayer(Music song) {
        try {
            Tuple<String, String> resolved = song.getPlayUrl();
            if (resolved == null) return null;
            String url = resolved.getA();
            return url == null || url.trim().isEmpty() ? null : url;
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 解析取流链接失败 " + song.getSourceId() + "：" + throwable);
            return null;
        }
    }

    /**
     * 本机解析不出完整音频时，仍然把这首歌点进房间，只是<b>不带链接</b>。
     *
     * <p>这是「非会员点歌 → 房间里的会员代为解析」那条路的起点。服务端收到没有链接的曲目后会立刻
     * 静默向全房发 {@code url_needed}；每个客户端在 {@link #supplyUrl} 里只有解析出完整音频才应答，
     * 非会员会主动沉默，所以最终被采纳的一定是某个会员成员的链接。房间里确实没人是会员时，这首歌
     * 会在等待窗口结束后被跳过，并由服务端在聊天栏说明原因。
     *
     * <p>刻意不在这里判断「房间里有没有会员」：客户端看不到别人的会员状态，问一句比猜一句可靠。
     */
    private static void delegateResolve(String op, Music song, boolean playNext, String why) {
        note(why + "，已把《" + song.getName() + "》交给房间里的别人代为解析", false);
        sendTrack(op, song, "", 0L, playNext);

        // 广播问别人的<b>同时</b>，本机把自己还没试过的那一路（自定义源）跑完 —— 谁先给出完整流
        // 谁应答，服务端那一关照旧会拒掉试听片段，所以两条一起跑不会把差的顶上去。
        // 为什么不等求助超时再试：那一轮空白是全房一起听的，歌都还没出声；先答先受益。
        final String src = MpTrack.token(song.getSource());
        final String id = song.getSourceId();
        final String title = song.getName();
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                String url = customSourceUrl(song);
                if (url == null) return;
                long est = estimateServedMs(song, url, song.getDuration());
                sendUrlSupply(src, id, url, est, est > 0L, 0L);
                note("本机用自定义源补上了《" + title + "》的播放地址", false);
            }
        });
    }

    /**
     * 求助房间之前，先在<b>本机</b>换一次平台：换到了就以换到的那一版点进房间，换不到才交给别人。
     *
     * <p>排在「问全房」前面有两个理由：这条流水线本来就在本机开着（用户装了脚本、打了自动换源），
     * 而一次本机搜索比一轮分层便宜得多 —— 分层是六层窗 + 别人机器上的往返，换源只是自己跑一遍。
     * 更重要的是<b>换到之后这一首根本不需要任何人参与</b>：{@code track_add} 带着可用直链入队时，
     * 服务端采纳它、不问任何人。</p>
     *
     * <p>{@code allowInRoom = true} 放开的是「在房间里不许偷偷换一首」那一道闸：这里换的是
     * <b>要点进房间的那一首</b>，服务端把换到的版本收进队列，全房听到的就是这一版，不存在本机
     * 脱队。网易云 / 酷狗的官方一起听照旧拦死（那两个房间按曲目本体或 hash 同步，本机的队列
     * 不受我们控制）。{@code AUTO_ATTEMPTED} 与十分钟负缓存对这条路同样生效：本机刚搜过一遍
     * 还没找到，服务端再把同一首派回来也搜不出什么，那一格由 {@link #switchDecline} 当场回绝。</p>
     *
     * @return true = 换到了，并且已经按替换后的曲目入队
     */
    private static boolean switchLocally(String op, Music song, boolean playNext) {
        if (!CrossSourceSwitcher.canAutoSwitch(song, true)) return false;
        CrossSourceSwitcher.Match match;
        try {
            match = CrossSourceSwitcher.findReplacementForAuto(song, CloudMusic.quality);
        } catch (Throwable failure) {
            System.err.println("[MpParty] 点歌前的本机换源异常 " + song.getSourceId() + "：" + failure);
            return false;
        }
        String url = match == null || match.url == null ? null : match.url.getA();
        if (match == null || match.music == null || url == null || url.trim().isEmpty()) return false;
        url = url.trim();
        // 替身那一版多半来自脚本或另一平台，报不出「实际服务多长」，估一个并报成估算值：
        // 参照时长取<b>替身自己</b>声明的那一个（服务端拿它与新曲目的 duration 比）。
        long est = estimateServedMs(match.music, url, match.music.getDuration());
        sendTrack(op, match.music, url, est, playNext, est > 0L);
        note("本机没有《" + song.getName() + "》的完整版，已按 " + match.platformLabel
                + " 的同名版本点进房间", false);
        return true;
    }

    private static void sendTrack(String op, Music song, String url, long servedMs, boolean playNext) {
        sendTrack(op, song, url, servedMs, playNext, false);
    }

    private static void sendTrack(String op, Music song, String url, long servedMs, boolean playNext,
                                  boolean estimated) {
        JsonObject m = MpProto.msg(op);
        m.add("track", MpTrack.toJson(song, url, urlDeadline(), servedMs, estimated));
        if (playNext) m.addProperty("next", true);
        send(m);
    }

    /** 各平台的签名链接一般能活 20 分钟上下，这里报一个保守的截止时间。 */
    private static long urlDeadline() {
        return System.currentTimeMillis() + 18 * 60 * 1000L;
    }

    // ── 本地控制拦截 ─────────────────────────────────────────────────────────
    //
    // 播放器自己的播放/暂停/上一首/下一首/进度全都先过这里。在房间里传输控制权归服务端：
    // 房主/管理员按一下就变成一条房间指令，普通成员按一下则被挡住并给出提示，
    // 而不是让他一个人从全房进度里脱队。

    /**
     * 撤回这张歌单还没播的那批。
     *
     * <p>正在播的那一首即使也属于这张单，服务端也不会动它 —— 那已经是一半人在听的歌了，
     * 撤走它等于给全房硬切一刀。</p>
     */
    public static void clearPlaylist(String playlist) {
        if (playlist == null || playlist.isEmpty() || !requireChannel()) return;
        JsonObject m = MpProto.msg(MpProto.C_CLEAR_PLAYLIST);
        m.addProperty("playlist", playlist);
        send(m);
    }

    /**
     * 队列里现在挂着哪几张歌单（按第一次出现的顺序）。
     *
     * <p>选单页用它决定一行显示「排入房间」还是「已在队列里 · 撤回」—— 一张已经灌过的单子再显示
     * 成可以重复灌，就是在邀请用户把同一批歌再排一遍（服务端会逐条拒掉，但那是一屏的「跳过 N 首」）。</p>
     */
    public static List<String> playlistLabelsInQueue() {
        List<String> out = new ArrayList<String>();
        for (MpTrack track : queue()) {
            if (!track.fromPlaylist() || out.contains(track.playlist)) continue;
            out.add(track.playlist);
        }
        return out;
    }

    /** 这张歌单在队列里还挂着多少首没播。 */
    public static int pendingOfPlaylist(String playlist) {
        if (playlist == null || playlist.isEmpty()) return 0;
        int n = 0;
        for (MpTrack track : queue()) if (playlist.equals(track.playlist)) n++;
        return n;
    }

    /**
     * 播放器要起播一条列表。
     *
     * <p>在房间里这就是一次<b>点歌</b>：排到已经排着的歌后面。它不是插队 —— 用户点一行只是想听这首，
     * 而房间里可能已经有好几个人在排队，把每一行点击都变成「下一首播放」会让他们全部被挤到后面去。
     * 参考实现 modernMP 也只把那枚派对按钮才发的 {@code queueNext} 挂在按钮上，普通行点击不插队。</p>
     *
     * @return true 表示本次起播已被房间消化（改成了点歌），调用方必须直接返回
     */
    public static boolean interceptStartPlayback(List<Music> songs, int startIdx) {
        if (applyingRemote || room == null) return false;
        Music song = pick(songs, startIdx);
        if (song != null && MpPlayback.isPartyMusic(song)) return false;   // 房间自己下发的那一首
        if (song == null) return true;
        return enqueueInstead(song, false);
    }

    /**
     * 播放器的「下一首播放」。
     *
     * <p>带插队意图发给房间：服务端只对房主/管理员认这个标志，普通成员会被降级成追加
     * （见 {@code Room#enqueue}），所以这里不必自己判断权限。</p>
     *
     * @return true 表示已改成房间点歌
     */
    public static boolean interceptPlayNext(Music song) {
        if (applyingRemote || room == null) return false;
        if (song != null && MpPlayback.isPartyMusic(song)) return false;
        if (song == null) return true;
        return enqueueInstead(song, true);
    }

    /**
     * 把一次本地起播/插播改成房间点歌，并给出提示。
     *
     * @param playNext true = 用户明确按了「下一首播放」，带插队意图；false = 普通点歌，排队尾
     */
    private static boolean enqueueInstead(Music song, boolean playNext) {
        if (!canQueue(song)) {
            note("这首歌没有可以分享给房间的曲目信息，已忽略本次播放", true);
            return true;
        }
        // 先过通道这一关再报「已加入」。原来的顺序是先报成功、再由 upload() 里的
        // requireChannel() 失败 —— 于是聊天栏里连着出现「已加入一起听歌单：X」和
        // 「当前服务器未安装一起听服务端模组」，前一句是假的，而且掩盖了真正的故障。
        if (!requireChannel()) return true;
        if (playNext) queueNext(song);
        else requestSong(song);
        return true;
    }

    /** @return true 表示「下一首」已被房间消化 */
    public static boolean interceptNext() {
        if (!engaged()) return false;
        // 频道里没有房主，普通成员按「下一首」不是被拒绝，而是发起一次切歌投票。
        // 这一步必须在 requireControl 之前：否则本地就把它拦掉了，投票压根发不出去。
        if (inChannel() && !hasControl()) {
            startSkipVote();
            return true;
        }
        if (!requireControl()) return true;
        next();
        return true;
    }

    /** @return true 表示「上一首」已被房间消化 */
    public static boolean interceptPrev() {
        if (!engaged()) return false;
        if (!requireControl()) return true;
        prev();
        return true;
    }

    /** 0 = 未拦截；1 = 已请求暂停；2 = 已请求继续；3 = 拦截但被拒绝（普通成员）。 */
    public static int interceptToggleState() {
        if (!engaged()) return 0;
        if (!requireControl()) return 3;
        if (playing) {
            pause();
            return 1;
        }
        resume();
        return 2;
    }

    /** @return true 表示进度调整已被房间消化 */
    public static boolean interceptSeekBy(float deltaMillis) {
        if (!engaged()) return false;
        if (!hasControl()) {
            noteThrottled("一起听房间中：只有房主或管理员可以调整播放进度", true);
            return true;
        }
        long dur = durationMs;
        long target = positionMs() + (long) deltaMillis;
        if (target < 0L) target = 0L;
        if (dur > 0L && target > dur - 1000L) target = Math.max(0L, dur - 1000L);
        seek(target);
        return true;
    }

    /**
     * 进度条被拖到某个绝对位置（控制栏与歌词页的进度条走的都是这一条，而不是相对量）。
     *
     * @return true 表示进度调整已被房间消化，调用方不得再动本地播放器
     */
    public static boolean interceptSeekTo(float absoluteMillis) {
        if (!engaged()) return false;
        if (!hasControl()) {
            noteThrottled("一起听房间中：只有房主或管理员可以调整播放进度", true);
            return true;
        }
        long target = (long) absoluteMillis;
        if (target < 0L) target = 0L;
        // 与 interceptSeekBy 用同一条钳位：房间时长以服务端下发的为准，末尾留 1 秒，
        // 免得刚 seek 过去就立刻触发「本曲结束」再切歌。
        long dur = durationMs;
        if (dur > 0L && target > dur - 1000L) target = Math.max(0L, dur - 1000L);
        seek(target);
        return true;
    }

    /** 播放模式键 → 房间循环模式（off → one → all）。@return true 表示已被房间消化 */
    public static boolean interceptPlayModeCycle() {
        if (!engaged()) return false;
        if (!requireControl()) return true;
        MpRoom r = room;
        String cur = r == null || r.repeat == null ? "off" : r.repeat;
        String next = "off".equals(cur) ? "one" : ("one".equals(cur) ? "all" : "off");
        setRepeat(next);
        return true;
    }

    /** 在房间里、房间有曲目、且当前不在应用服务端帧。 */
    private static boolean engaged() {
        return !applyingRemote && room != null && currentTrack != null;
    }

    private static boolean requireControl() {
        // 频道里控制权归服务器管理员（他的房间角色仍是 member，isController 看不出来）。
        if (hasControl()) return true;
        noteThrottled(inChannel() ? "只有服务器管理员可以控制频道的播放"
                : "只有房主或管理员可以控制一起听的播放", true);
        return false;
    }

    private static void noteThrottled(String text, boolean error) {
        long now = System.currentTimeMillis();
        if (now - lastBlockHint < 3000L) return;
        lastBlockHint = now;
        note(text, error);
    }

    private static Music pick(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) return null;
        int idx = startIdx < 0 ? 0 : startIdx;
        if (idx >= songs.size()) idx = songs.size() - 1;
        return songs.get(idx);
    }

    static void beginRemote() {
        applyingRemote = true;
    }

    static void endRemote() {
        applyingRemote = false;
    }

    // ── 收帧 ─────────────────────────────────────────────────────────────────

    /** 由 {@code MpNet} 对每一帧 S2C 调用；可以在任意线程上调用。 */
    public static void onFrame(byte[] data) {
        // 接收侧闸门：直连会话活跃时，MC 通道来的 S2C 一律丢弃。路由只管发送侧，
        // 接收是各树 MpNet 直接回调本方法——不拦的话，玩家连着服务器的同时开直连，
        // 两个房间的快照会互相覆盖（UI 在两个房间之间跳变）。
        // 直连 socket 的帧由 DirectClient 回调进来，带 directSource 标记放行。
        boolean direct = directSource.get();
        if (com.muoniumplayer.party.direct.MpDirect.isActive() && !direct) return;
        // 带标记的直连帧还要过一道会话存活校验：close()/onSessionClosed 先复位 active
        // 再关 socket，readLoop 里的最后一两帧可能在那之后才投到——此时闸门两个条件都
        // 不成立，帧会穿透进 dispatch，把刚被 onDisconnect 清零的 room/时钟覆写成一份
        // 死房间快照。会话已终结（这条连接不再是当前 client/回环）的迟到帧按过期丢弃。
        if (direct && !com.muoniumplayer.party.direct.MpDirect.frameSinkLive()) return;
        JsonObject msg = MpProto.decode(data);
        if (msg == null) return;
        applyingRemote = true;
        try {
            dispatch(msg);
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 处理帧失败：" + throwable);
        } finally {
            applyingRemote = false;
        }
    }

    /** 直连 socket 送来的帧标记：直连活跃时 MC 通道的帧被闸门丢弃，这一路放行。 */
    private static final ThreadLocal<Boolean> directSource = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 由 {@code DirectClient} 在投递直连帧前打标记（见 {@link #onFrame} 的闸门）。 */
    public static void markDirectSource() {
        directSource.set(Boolean.TRUE);
    }

    public static void clearDirectSource() {
        directSource.set(Boolean.FALSE);
    }

    private static void dispatch(JsonObject msg) {
        String op = MpProto.str(msg, MpProto.OP, "");

        if (MpProto.S_HELLO.equals(op)) {
            serverPresent = true;
            helloAcked = true;
            serverProto = (int) MpProto.num(msg, "proto", 1L);
            membersCanEnqueue = MpProto.bool(msg, "membersCanEnqueue", true);
            // 同步策略由服务端统一下发，保证同一房间里所有客户端（含 modernMP）口径一致；
            // 老服务端不发这些字段，configure 会原样保留本地默认值。
            MpSyncPolicy.configure(
                    MpProto.num(msg, "softDriftMs", 0L),
                    MpProto.num(msg, "hardDriftMs", 0L),
                    MpProto.num(msg, "resyncCooldownMs", 0L),
                    (int) MpProto.num(msg, "driftConfirmHits", 0L),
                    MpProto.num(msg, "clockGraceMs", -1L));
            // ── v2：能力位。老服务端不发这些字段，取到的就是本地默认值，
            //        界面据此把频道/发现/投票那几处直接隐藏。
            serverAdmin = MpProto.bool(msg, "serverAdmin", false);
            channelEnabled = MpProto.bool(msg, "channelEnabled", false);
            channelName = MpProto.str(msg, "channelName", channelName);
            channelCode = MpProto.str(msg, "channelCode", channelCode);
            channelMaxPerMember = (int) MpProto.num(msg, "channelMaxPerMember", channelMaxPerMember);
            publicRoomsEnabled = MpProto.bool(msg, "publicRoomsEnabled", false);
            voteWindowMs = MpProto.num(msg, "voteWindowMs", voteWindowMs);
            serverResolve = MpProto.bool(msg, "serverResolve", false);
            // 「播放歌单」的能力位。老服务端不发 → 取 0 → 那颗按钮根本不出现，
            // 不会出现「按下去服务端回一句不认识这个 op」。
            playlistBatchLimit = (int) MpProto.num(msg, "playlistBatchLimit", 0L);
            playlistAdminOnly = MpProto.bool(msg, "playlistAdminOnly", true);
            // 房间内无缝交接的提前量；0 = 没开（老服务端不发这个字段，取到的就是 0）。
            // 这就是「房间里要不要交叠」的开关：服务端提前换歌、客户端提前预解码，
            // 两边用的是同一个数，所以各端交叠点一致。
            // 先复位「本条连接已见过指派」：新连接刚建立，这台服务端还没派过任何活。
            // 若不复位，上一条连接里 onDisconnect 的 setAssigned(false,0L) 会把它锁成真，
            // 重连到老服务端（从不指派）时房主兜底就永远失效 —— 房主不再自己算，整房退化成硬切。
            RoomAutomix.resetAssignmentSeen();
            RoomAutomix.configure(MpProto.num(msg, "automixLeadMs", 0L));
            // 本机是不是房主要跟着身份走 —— 但分析权现在由服务端指派决定，房主身份只在
            // 老服务端（它不会指派任何人）那里作兜底。
            MpPlayback.refreshRoomRole();
            // 服务端刚认识我们，把身份补报一次：它紧接着发出的房间快照里就已经有 Mod 与 VIP。
            // 这份 profile 同时带着分析能力位，服务端据此决定要不要把分析派到本机。
            reportProfile();
            notifyListeners();
        } else if (MpProto.S_ANALYZE_ASSIGN.equals(op)) {
            // 服务端点名让本机算这一首的交接计划。这是「谁点的歌就用谁的电脑先算」那套
            // 指派的结果 —— 被点到的可能是点歌人、房主，也可能是房间里借来的第三台。
            long entryId = MpProto.num(msg, "entryId", 0L);
            System.out.println("[MpParty] 被指派分析交接计划 entry=" + entryId
                    + "（第 " + MpProto.num(msg, "attempt", 0L) + " 次）");
            RoomAutomix.setAssigned(true, entryId);
            // 指派到得可能晚于本次预备，这一下负责把还没启动的那一次唤起来：
            // 预备链路每秒都在跑，awaitingPlan 变 false 之后下一轮自然接上。
        } else if (MpProto.S_ANALYZE_STATE.equals(op)) {
            // 服务端告诉全房「这一首由谁在算」。analyst 为空 = 没有人能算，
            // 此时不必再挂着一个永远等不到的计划。
            String analyst = MpProto.str(msg, "analyst", "");
            UUID me = selfUuid();
            boolean mine = analyst != null && !analyst.isEmpty()
                    && me != null && analyst.equals(me.toString());
            long entryId = MpProto.num(msg, "entryId", 0L);
            if (mine) {
                RoomAutomix.setAssigned(true, entryId);
            } else {
                RoomAutomix.setAssigned(false, entryId);
                RoomAutomix.setNobodyCanAnalyze(analyst == null || analyst.isEmpty());
            }
        } else if (MpProto.S_ROOM_STATE.equals(op)) {
            applySnapshot(msg);
            MpRoomPrefetch.onQueue(queue());
        } else if (MpProto.S_QUEUE.equals(op)) {
            MpRoom r = room;
            if (r != null) {
                room = r.withQueue(MpProto.arr(msg, "queue"), MpProto.arr(msg, "history"));
                notifyListeners();
            }
            // 服务端把「下一首」连直链一起预告过来，好让本机在它轮到之前就下载解码完。
            // 队列一改这里就重来一次，所以队首变了会自然换成新的那一首。
            // next 里可能还带着房主算出的交接计划（plan），一起交给播放层。
            JsonObject staged = MpProto.obj(msg, "next");
            MpPlayback.stageNext(MpTrack.fromJson(staged), MpProto.obj(staged, "plan"));
            MpRoomPrefetch.onQueue(queue());
        } else if (MpProto.S_PLAY.equals(op)) {
            MpTrack track = MpTrack.fromJson(MpProto.obj(msg, "track"));
            if (track == null) return;
            // resync = 服务端单独补给我一份权威位置（个人暂停恢复时用），不是新曲目。
            // 「立即同步播放进度」就落在这里：见到它就清掉个人暂停并直接跳到那个位置，
            // 不用等下一发心跳。普通 play（换歌广播）同样把暂停落地 —— 帧里带的就是权威
            // 位置与权威播放态；留着标记不清，resync 帧万一没来（服务端重启丢了会话）就会
            // 永久静默卡死，而后果只是「自动恢复播放」，轻得多。
            if (MpProto.bool(msg, "resync", false) || MpProto.bool(msg, "playing", true))
                selfPaused = false;
            long seq = MpProto.num(msg, "seq", 0L);
            long pos = compensate(MpProto.num(msg, "pos", 0L), MpProto.num(msg, "serverTime", 0L));
            startTrack(track, pos, MpProto.bool(msg, "playing", true), seq);
        } else if (MpProto.S_PAUSE.equals(op)) {
            MpSyncPolicy.markCommand();
            stamp(MpProto.num(msg, "pos", positionMs()), false, MpProto.num(msg, "seq", 0L));
            MpPlayback.pause(posBase);
            notifyListeners();
        } else if (MpProto.S_RESUME.equals(op)) {
            MpSyncPolicy.markCommand();
            stamp(compensate(MpProto.num(msg, "pos", positionMs()), MpProto.num(msg, "serverTime", 0L)),
                    true, MpProto.num(msg, "seq", 0L));
            if (!selfPaused) MpPlayback.resume(posBase);
            notifyListeners();
        } else if (MpProto.S_SEEK.equals(op)) {
            // 房主／管理员拖进度是明确命令：立刻执行，不受软硬阈值和冷却约束。
            MpSyncPolicy.markCommand();
            stamp(compensate(MpProto.num(msg, "pos", 0L), MpProto.num(msg, "serverTime", 0L)),
                    playing, MpProto.num(msg, "seq", 0L));
            if (!selfPaused) MpPlayback.seek(posBase);
            notifyListeners();
        } else if (MpProto.S_STOP.equals(op)) {
            currentTrack = null;
            playing = false;
            posBase = 0L;
            posStamp = System.currentTimeMillis();
            durationMs = 0L;
            MpPlayback.stop();
            notifyListeners();
        } else if (MpProto.S_TIME.equals(op)) {
            boolean isPlaying = MpProto.bool(msg, "playing", false);
            long dur = MpProto.num(msg, "duration", durationMs);
            long pos = compensate(MpProto.num(msg, "pos", 0L), MpProto.num(msg, "serverTime", 0L));
            durationMs = dur;
            stampSmooth(pos, isPlaying);
            // 交给播放层的仍是服务端的权威位置（未经平滑）：那边自己有三档判定，
            // 喂它一个被磨过的值只会让判定失真。
            if (!selfPaused) MpPlayback.time(pos, dur, isPlaying);
        } else if (MpProto.S_INVITE.equals(op)) {
            inviteCode = MpProto.str(msg, "code", "");
            inviteRoomName = MpProto.str(msg, "roomName", "");
            inviteFrom = MpProto.str(msg, "fromName", "");
            note(inviteFrom + " 邀请你加入《" + inviteRoomName + "》，输入 /mp join " + inviteCode + " 加入", false);
            notifyListeners();
        } else if (MpProto.S_NOTICE.equals(op)) {
            // 服务端已经在聊天栏镜像过一遍，这里只更新状态文本，避免同一句话出现两次。
            lastMessage = MpProto.str(msg, "msg", "");
            lastMessageIsError = false;
            notifyListeners();
        } else if (MpProto.S_ERROR.equals(op)) {
            lastMessage = MpProto.str(msg, "msg", "");
            lastMessageIsError = true;
            notifyListeners();
        } else if (MpProto.S_LEFT.equals(op)) {
            reset();
            MpPlayback.stop();
            notifyListeners();
        } else if (MpProto.S_URL_NEEDED.equals(op)) {
            supplyUrl(MpProto.str(msg, "source", MpProto.SOURCE_NETEASE),
                    MpProto.str(msg, "songId", ""),
                    MpProto.num(msg, "entryId", 0L),
                    (int) MpProto.num(msg, "tier", MpProto.TIER_UNKNOWN),
                    MpProto.num(msg, "deadlineMs", 0L));
        } else if (MpProto.S_SWITCH_RESCUE.equals(op)) {
            // 全房都答不上，服务端单播问点歌人「能不能换到别的平台的同一首歌」。
            supplyRescue(msg);
        } else if (MpProto.S_PRESENCE.equals(op)) {
            presenceList = presenceOf(MpProto.arr(msg, "players"));
            onlineCount = (int) MpProto.num(msg, "online", presenceList.size());
            notifyListeners();
        } else if (MpProto.S_ROOMS.equals(op)) {
            publicRooms = roomsOf(MpProto.arr(msg, "rooms"));
            publicRoomsEnabled = MpProto.bool(msg, "publicRoomsEnabled", publicRoomsEnabled);
            channelEnabled = MpProto.bool(msg, "channelEnabled", channelEnabled);
            channelCode = MpProto.str(msg, "channelCode", channelCode);
            notifyListeners();
        } else if (MpProto.S_INVITES.equals(op)) {
            pendingInvites = invitesOf(MpProto.arr(msg, "invites"));
            notifyListeners();
        } else if (MpProto.S_VOTE.equals(op)) {
            applyVote(MpVote.fromJson(MpProto.obj(msg, "vote")));
        } else if (MpProto.S_BANNED.equals(op)) {
            banned = true;
            banReason = MpProto.str(msg, "reason", "未说明");
            banRemaining = MpProto.str(msg, "remaining", "");
            // 封禁横幅由界面显示；这里仍写一行聊天，纯服务端玩家也得知道自己被封了。
            note("你的一起听功能已被封禁（" + banReason
                    + (banRemaining.isEmpty() ? "" : "，剩余 " + banRemaining) + "）", true);
        } else if (MpProto.S_PING.equals(op)) {
            // 直连保活的探测帧。回 pong 让房主确认本机还活着（老客户端不回也不影响——
            // 它们的 C_PROGRESS 心跳本来就在刷新房主那侧的活性记录）。
            com.muoniumplayer.party.direct.MpDirect.sendPong(MpProto.num(msg, "t", 0L));
        }
    }

    private static List<MpPresence> presenceOf(JsonArray a) {
        List<MpPresence> out = new ArrayList<MpPresence>();
        if (a == null) return Collections.unmodifiableList(out);
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            MpPresence p = MpPresence.fromJson(e.getAsJsonObject());
            if (p != null) out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<MpPublicRoom> roomsOf(JsonArray a) {
        List<MpPublicRoom> out = new ArrayList<MpPublicRoom>();
        if (a == null) return Collections.unmodifiableList(out);
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            MpPublicRoom r = MpPublicRoom.fromJson(e.getAsJsonObject());
            if (r != null) out.add(r);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<MpInvite> invitesOf(JsonArray a) {
        List<MpInvite> out = new ArrayList<MpInvite>();
        if (a == null) return Collections.unmodifiableList(out);
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            MpInvite inv = MpInvite.fromJson(e.getAsJsonObject());
            if (inv != null) out.add(inv);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 把单独一帧投票合并进房间快照。
     *
     * <p>只换 votes 那一份列表（{@link MpRoom#withVotes}）而不重建整个快照：投票期间这一帧每秒
     * 都会来更新倒计时，重建快照会让成员列表和歌单每秒换一个新对象，界面上正在滚的列表会跟着抖。
     */
    private static void applyVote(MpVote vote) {
        if (vote == null) return;
        MpRoom r = room;
        if (r == null) return;
        List<MpVote> merged = new ArrayList<MpVote>();
        boolean replaced = false;
        for (MpVote v : r.votes) {
            if (v.voteId.equals(vote.voteId)) {
                merged.add(vote);
                replaced = true;
            } else if (!v.closed) {
                merged.add(v);
            }
        }
        if (!replaced) merged.add(vote);
        room = r.withVotes(merged);
        notifyListeners();
    }

    private static void applySnapshot(JsonObject msg) {
        MpRoom snapshot = MpRoom.fromJson(MpProto.obj(msg, "room"));
        room = snapshot;
        hintContributionOnce();
        // 快照里也带着「下一首」（服务端把它放在 room 对象里）：中途进房与断线重连的人
        // 拿到快照的那一刻就该开始预解码，而不是等下一次队列广播。下一首里同样可能带计划。
        JsonObject staged = MpProto.obj(MpProto.obj(msg, "room"), "next");
        MpPlayback.stageNext(MpTrack.fromJson(staged), MpProto.obj(staged, "plan"));
        // 快照是身份的权威来源（房主可能刚转让过），分析权跟着它刷新。
        MpPlayback.refreshRoomRole();
        JsonObject playback = MpProto.obj(msg, "playback");
        waitingUrl = MpProto.bool(playback, "waitingUrl", false);

        if (playback == null) {
            notifyListeners();
            return;
        }
        String state = MpProto.str(playback, "state", "idle");
        MpTrack track = MpTrack.fromJson(MpProto.obj(playback, "track"));
        long seq = MpProto.num(playback, "seq", 0L);

        if (track == null || "idle".equals(state)) {
            if (currentTrack != null) {
                currentTrack = null;
                playing = false;
                durationMs = 0L;
                MpPlayback.stop();
            }
            notifyListeners();
            return;
        }

        long pos = compensate(MpProto.num(playback, "pos", 0L), MpProto.num(playback, "serverTime", 0L));
        boolean isPlaying = "playing".equals(state);
        // 同一首歌按 source+songId 判：跨平台房间里两个平台的数字 id 会撞车。
        boolean sameTrack = currentTrack != null && currentTrack.key().equals(track.key());

        if (!sameTrack || seq > appliedSeq) {
            startTrack(track, pos, isPlaying, seq);
        } else {
            stamp(pos, isPlaying, seq);
            // 与 S_TIME 心跳一致：个人暂停期间不许被房间快照重新开声。这一分支之前漏了守卫，
            // 而 setSelfPause 会让服务端无条件 broadcastState（收听人数变了），于是一按个人暂停
            // 就被下一帧 room_state 当场解除 —— 按钮还亮着却照常出声。
            if (!selfPaused) MpPlayback.time(pos, durationMs, isPlaying);
            notifyListeners();
        }
    }

    /**
     * 进房那一次说清本机在替房间做什么，只说一次。
     *
     * <p>「贡献会员」与「贡献自定义源」现在都是默认开的，而这两件事烧的是这台机器的账号配额与
     * 脚本额度。玩家第一次被排在「先被问到」那一档时应该知道自己可以关，也要知道关掉的代价是
     * 整房多等几层窗 —— 一句话讲完，不弹窗。</p>
     */
    private static void hintContributionOnce() {
        if (contributionHintShown) return;
        contributionHintShown = true;
        boolean vip = contributeVip();
        boolean lx = contributeLx();
        if (!vip && !lx) {
            note("一起听：本机没有向房间贡献任何解析能力，别人点的会员曲只能等房间里的会员播放；"
                    + "想帮就点一起听面板右上角的「贡献会员解析」/「贡献自定义源」那两颗胶囊", false);
            return;
        }
        String what = vip && lx ? "会员身份与自定义源" : (vip ? "会员身份" : "自定义源");
        note("一起听：本机默认用" + what + "替房间里的别人解析完整音频"
                + "（只上传带签名、有时效的临时地址，账号不出本机；不想帮就点面板右上角那两颗胶囊关掉）", false);
    }

    private static void startTrack(MpTrack track, long pos, boolean isPlaying, long seq) {
        if (seq < appliedSeq) return;            // 过期帧：已经有更新的指令生效了
        currentTrack = track;
        durationMs = track.durationMs;
        stamp(pos, isPlaying, seq);
        if (track.url == null || track.url.trim().isEmpty()) {
            // 服务端还在收集新链接，拿到后会重发 play。
            waitingUrl = true;
            notifyListeners();
            return;
        }
        waitingUrl = false;
        // 换歌是明确命令：清掉漂移确认计数并开静默期，接下来几秒在解析／下载，落后不算脱节。
        MpSyncPolicy.markCommand();
        // 房间内的交叠交接：本机早就把这一首（服务端预告过的那一首）解析、下载、解码完，
        // 交接不该再走一遍起播 —— 那会把刚叠上的尾巴整段掐掉。
        if (!selfPaused && MpPlayback.handoverToRoomFrame(track, pos, isPlaying)) {
            notifyListeners();
            return;
        }
        // 个人暂停期间只更新房间时钟，不开声。界面照常显示曲目和进度，恢复时才真的起播。
        if (!selfPaused) MpPlayback.play(track, track.url, pos, isPlaying);
        notifyListeners();
    }

    private static void stamp(long pos, boolean isPlaying, long seq) {
        posBase = Math.max(0L, pos);
        posStamp = System.currentTimeMillis();
        playing = isPlaying;
        if (seq > appliedSeq) appliedSeq = seq;
    }

    /**
     * 心跳专用的时钟落地：把房间时钟朝服务端位置<b>收敛</b>而不是直接覆盖。
     *
     * <p>这条时钟服务于 UI（进度条、覆盖层文本）和「起播完成后对齐到哪里」。心跳每 2 秒来一次，
     * 到达时间本身是抖的，直接覆盖会让进度读数每两秒横跳一下 —— 用户看到的「进度来回跳」有一半
     * 是这个。差得不多时只走 1/4 的距离，几次心跳就贴上去了，同时把抖动磨平；差得离谱（换歌、
     * 房主拖了进度而命令帧丢了）才整体重设。</p>
     */
    private static void stampSmooth(long pos, boolean isPlaying) {
        long target = Math.max(0L, pos);
        // 播放状态翻转、还没有曲目、或者时钟根本没起来：没有可收敛的基准，直接落地。
        if (isPlaying != playing || currentTrack == null || posStamp == 0L) {
            stamp(target, isPlaying, appliedSeq);
            return;
        }
        long own = positionMs();
        long diff = target - own;
        long abs = diff < 0L ? -diff : diff;
        if (abs > MpSyncPolicy.hardDriftMs()) {
            stamp(target, isPlaying, appliedSeq);
            return;
        }
        stamp(own + diff / 4, isPlaying, appliedSeq);
    }

    /** 把服务端报告的位置修正到「现在」，细节见 {@link MpSyncPolicy#compensate}。 */
    private static long compensate(long pos, long serverTime) {
        return MpSyncPolicy.compensate(pos, serverTime);
    }

    /**
     * 回应服务端的 {@code url_needed}：用本机的一切手段重新解析一条<b>完整</b>音频的直链。
     *
     * <p>答不上就当场<b>弃权</b>（{@link #declineUrl}），不再像以前那样保持沉默。老规矩「解不出就
     * 闭嘴」是给<b>并行竞速</b>写的 —— 反正没人等，闭嘴不花钱；服务端改成按层串行问人之后，
     * 沉默等于让被点名的这一层白等一个层窗口（默认 3 秒），而这一层的窗走不完就轮不到下一层，
     * 整房跟着一起等。每一句弃权都带一个「为什么」，服务端收齐就立刻往下问。
     *
     * <p>本家链路之外还要<b>补问一次自定义源</b>。以前网易云这一路只走 {@link MpNetease#resolve}
     * （挑它是因为它会报出「这条链接实际能取到多长」，据此才识破试听片段），代价是绕过了
     * {@code Music.getPlayUrl()} 里排在最前的自定义源那一跳；再叠上「非会员拿到试听就沉默」，
     * 结果就是房间里那个<b>没有会员、但脚本明明能解</b>的人跟着一起闭嘴 —— 全房等一轮超时，
     * 然后这首歌被跳过。这一跳补上之后，代解析的判据才是真的「谁能解出来」，而不是「谁是会员」。
     *
     * <p><b>换平台的救援不在这里跑。</b>同一份 LX 脚本往往声明了五六个渠道，别的渠道上大概率有
     * 这首歌 —— 但换渠道就是换曲目身份，得走 {@code track_replace}，而服务端只认<b>点歌人</b>
     * （或管理员）发的替换，别人换过去只会吃一句拒绝，那一趟搜索纯属白烧额度。就算发了，整条跨平台
     * 流水线要跑十几秒，塞进一个 3 秒的层窗必然把这一层拖没。所以这一路只弃权，救援留给全层落空之后
     * 服务端单播来的 {@code switch_rescue} —— 那里给的是 12 秒的窗，而且只问一个人。
     *
     * @param entryId    服务端问的是队列里哪一格；应答与弃权都原样带回去。0 = 老服务端不发这一位
     * @param tier       服务端当前在问第几层（见 {@link MpProto} 的 {@code TIER_*}）；
     *                   {@link MpProto#TIER_UNKNOWN} = 老服务端，此时一帧新的都不追加
     * @param deadlineMs 这一层什么时候换人。唯一的用处：给那一次可选的 HEAD 探长度<b>留出预算</b>
     *                   （见 {@link #estimateServedMs(Music, String, long, long)}）。
     *                   不拿它掐断任何一次在途解析 —— 迟到的合格答案服务端照样按层号采纳
     */
    private static void supplyUrl(final String source, final String songId,
                                  final long entryId, final int tier, final long deadlineMs) {
        if (songId == null || songId.trim().isEmpty()) return;
        final String src = MpProto.sourceOf(source);
        final MpTrack known = trackOf(src, songId);
        if (MpTrack.platformOf(src) == null) {
            // 本机压根没有这个播放源 —— 这一格对这台机器是死题，说一声让别人接。
            declineUrl(entryId, tier, src, songId, "no-platform");
            return;
        }
        final long expected = known == null ? 0L : known.durationMs;

        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                // 用房间元数据重建曲目。刻意不带 partyStreamUrl —— 带了 getPlayUrl()
                // 会把那条已经过期的链接原样返回。
                Music target = known == null ? null : known.toMusic();
                String why = "no-metadata";
                if (MpProto.SOURCE_NETEASE.equals(src)) {
                    long id = parseSongId(songId);
                    if (id <= 0L) {
                        declineUrl(entryId, tier, src, songId, "netease-id");
                        return;
                    }
                    MpNetease.Resolved resolved = MpNetease.resolve(id);
                    boolean usable = resolved != null && !isTrialClip(resolved.servedMs, expected);
                    if (usable) {
                        sendUrlSupply(src, songId, resolved.url, resolved.servedMs, false, entryId);
                        return;
                    }
                    why = resolved == null ? "netease-failed" : "netease-trial";
                    // 本家给不出完整版（没登录 / 不是会员 / 只解到试听）。脚本元数据不在这条
                    // 分支的已知曲目里，所以按 id 补查一次元数据才有办法问脚本 —— 这一步只在
                    // 「本家已经失败」之后发生，正常会员路径一次网络都不多烧。
                    if (target == null && contributeLx()) target = MpNetease.songById(id);
                }
                String url = target == null ? null : customSourceUrl(target);
                if (url == null) {
                    // 脚本没答上才是 lx 那一路的原因；压根没曲目元数据可问，就是上面那句。
                    declineUrl(entryId, tier, src, songId, target == null ? why : lxReason(src));
                    return;
                }
                // 脚本不报「这条链接实际多长」，估一个并报成估算值；估不出（HEAD 拿不到字节数
                // 或者本机说不清码率）就传 0 = 未知，服务端按放行处理。
                long est = estimateServedMs(target, url, expected, deadlineMs);
                sendUrlSupply(src, songId, url, est, est > 0L, entryId);
            }
        });
    }

    /** 房间下发的 songId 不一定能当数字用（酷狗是 hash）；解不出来返回 0。 */
    private static long parseSongId(String songId) {
        try {
            return Long.parseLong(songId.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 只用本机的 LX 自定义源解这一首，拿不到返回 null。
     *
     * <p>{@code contributeLx} 关掉之后这里一律沉默：替房间解出来的那条链接是全房共用的，
     * 用户没同意贡献就不能把脚本的调用量记到他头上去答别人。本机自己播放不受这个开关影响
     * （那条路走的是 {@code Music.getPlayUrl()}）。
     */
    private static String customSourceUrl(Music song) {
        if (song == null || !contributeLx()) return null;
        try {
            Tuple<String, String> resolved = song.resolveLxPlayUrl();
            if (resolved == null) return null;
            String url = resolved.getA();
            return url == null || url.trim().isEmpty() ? null : url.trim();
        } catch (Throwable ignored) {
            // 脚本没起、没声明这个源、抛错 —— 都当「答不上」，别把心跳与求助链路带崩。
            return null;
        }
    }

    /**
     * 交一条直链上去。{@code entryId} 是服务端问的那一格，原样带回它才对得上号；
     * 0 = 这一条不是应答某一帧 {@code url_needed} 的（点歌时并行补答就是这种），
     * 服务端退回按 {@code source + songId} 认。
     */
    private static void sendUrlSupply(String source, String songId, String url, long servedMs,
                                      boolean estimated, long entryId) {
        JsonObject m = MpProto.msg(MpProto.C_URL_SUPPLY);
        m.addProperty("source", source);
        m.addProperty("songId", songId);
        m.addProperty("url", url);
        m.addProperty("urlExpiresAt", urlDeadline());
        if (servedMs > 0L) {
            m.addProperty("urlDuration", servedMs);
            if (estimated) m.addProperty("urlDurationEstimated", true);
        }
        if (entryId > 0L) m.addProperty("entryId", entryId);
        sendQuiet(m);
    }

    /**
     * 弃权：「这一层我不答，别再等我的窗」。
     *
     * <p>{@code entryId} 与 {@code tier} 任一缺失，说明对面是不认识这一帧的老服务端 —— 发过去
     * 只会换回一句「不支持的操作」打在聊天栏里，所以这里干脆闭嘴：那一层由服务端自己等满窗口，
     * 行为与改造前逐字一致。这就是这套分层改动的兼容口，判据用「服务端有没有先把新字段发过来」，
     * 不猜版本号。</p>
     *
     * <p>{@code reason} 给人看，服务端不据此分支（它对上层的 {@code tier} 才是判据），
     * 所以写错、写漏都不会把链路带坏；但写清了渠道键，日志里才分得出「这台没这个渠道」
     * 和「这个渠道当场死了」。</p>
     */
    private static void declineUrl(long entryId, int tier, String source, String songId, String reason) {
        if (entryId <= 0L || tier < 0) return;
        JsonObject m = MpProto.msg(MpProto.C_URL_DECLINE);
        m.addProperty("entryId", entryId);
        m.addProperty("tier", tier);
        m.addProperty("source", source);
        m.addProperty("songId", songId);
        m.addProperty("reason", reason == null ? "" : reason);
        sendQuiet(m);
    }

    /**
     * 脚本那一路的弃权原因，带上<b>本机脚本用哪个渠道键称呼这个平台</b>。
     *
     * <p>一份脚本常常同时声明 wy/tx/kg/kw/mg 五六个渠道，光一句「lx 失败」分不出三种完全不同的
     * 情况：用户不让贡献、这台压根没这个渠道、渠道当场没解出来。这三种在玩家那边是三件事，
     * 在服务端日志里也应该是三行。</p>
     */
    private static String lxReason(String sourceToken) {
        if (!contributeLx()) return "lx-off";
        String key = channelKeyOf(sourceToken);
        return key == null ? "no-lx-channel" : "lx-" + key + "-failed";
    }

    /** 平台令牌 → 本机脚本的渠道键；映射不到（B 站 / 汽水 / 本机没这个平台）返回 null。 */
    private static String channelKeyOf(String sourceToken) {
        try {
            return CrossSourceSwitcher.lxChannelKey(MpTrack.platformOf(sourceToken));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 给「不报实际长度」的那几路估一个长度：HEAD 拿字节数 ÷ 本机判定的码率。
     *
     * <p>为什么要估：整个房间共用同一条直链，非会员解出来的 30 秒试听片段一旦被采纳，就是全房
     * 听 30 秒。网易云会在响应里报「这条链接实际服务了多长」，酷狗 / QQ / 脚本这几路都不报 ——
     * 于是它们原本<b>永远不会</b>被试听检测拦下，这是共享直链模型一直欠着的一块。估一个数报出去，
     * 检测就有一只半瞎的眼睛总好过完全没有。</p>
     *
     * <p>估出来的一律标成估算值，服务端对估算值的阈值放宽（0.5 而不是实测那一路的 0.8），
     * 因为猜码率是有误差的；而且误差要往「估长了」那一边偏（见 {@link #declaredKbps}）——
     * 估短了会把完整版判成试听片段，代价是整房白等一轮再跳过一首本来能放的歌。</p>
     *
     * @param expectedMs 曲目声明的时长；没有参照系就不估（返回 0）
     */
    private static long estimateServedMs(Music song, String url, long expectedMs) {
        return estimateServedMs(song, url, expectedMs, 0L);
    }

    /**
     * 同上，但把这一次 HEAD 压进<b>服务端留给这一层的窗</b>。
     *
     * <p>{@code deadlineMs} 为 0 表示没有窗（点歌那一跳，没人等）。有窗时剩余时间连最小预算都不到
     * 就干脆不发：估不出来只是少一只识破试听片段的眼睛，而把这一层的窗吃光是把整房往后拖。
     * 探测本身另有 {@link #ESTIMATE_BUDGET_MS} 的上限，两个取小的那个才动手。</p>
     */
    private static long estimateServedMs(Music song, String url, long expectedMs, long deadlineMs) {
        if (song == null || url == null || expectedMs <= 0L) return 0L;
        long budget = ESTIMATE_BUDGET_MS;
        if (deadlineMs > 0L) {
            long left = deadlineMs - System.currentTimeMillis();
            if (left < MpStreamEstimate.MIN_BUDGET_MS) return 0L;
            budget = Math.min(budget, left);
        }
        int kbps = declaredKbps(song);
        if (kbps <= 0) return 0L;
        long bytes = MpStreamEstimate.headBytes(url, budget);
        return MpStreamEstimate.durationMs(bytes, kbps);
    }

    /**
     * 本机认为这条链接交付的是什么码率（kbps），说不清就返回 0 = 别估。
     *
     * <p>读的是解析时已经落在 {@link Music#getPlaybackQuality()} 上的那一档，不再发任何请求。
     * 无损族按 400kbps 计是<b>故意往低压</b>的：真实 FLAC 在 700~1100kbps，把码率当小会让估出来的
     * 时长偏长，也就是宁可漏判一条试听片段，也不误杀一条完整版。</p>
     */
    private static int declaredKbps(Music song) {
        switch (song.getPlaybackQuality()) {
            case LOSSLESS:
                return 400;
            case HQ:
                return 320;
            case STANDARD:
                return 128;
            case UNKNOWN:
            default:
                return 0;
        }
    }

    /**
     * 服务端全层落空之后单播给点歌人的那一句：「你本机能不能换到别的平台的同一首歌」。
     *
     * <p>走的是播放器里现成的那套跨平台换源流水线（并行搜索 + 相似度打分 + 时长闸门 + 取流验证），
     * 换到的那一首用 {@code track_replace} 交回去。服务端改的是<b>队列里那一格</b>再广播，
     * 全房一起换 —— 与「本机偷偷换一首」不是一件事，这也是 {@link CrossSourceSwitcher#canAutoSwitch}
     * 允许在房间里跑这一条的理由（它挡的是脱队，这里没有脱队可言）。
     *
     * <p>换不到就<b>什么都不发</b>：这首歌由服务端在总闸到点时跳过，并在聊天栏说明原因。
     * 同一格只跑一次（{@link #RESCUE_IN_FLIGHT}），因为流水线一次就是十几秒，而服务端可能
     * 因为队列变动把同一首再问一遍。</p>
     */
    private static void supplyRescue(JsonObject msg) {
        final long entryId = MpProto.num(msg, "entryId", 0L);
        final String src = MpProto.sourceOf(MpProto.str(msg, "source", MpProto.SOURCE_NETEASE));
        final String songId = MpProto.str(msg, "songId", "");
        final long deadline = MpProto.num(msg, "deadlineMs", 0L);
        // 服务端现在派的是「房间里最可能换成功的那台」，收件人常常不是点歌人：MP 玩家点的歌
        // 只有装了脚本的 Mu 救得回来。这一位由服务端说清，本机据此换文案 —— 替别人搜一首
        // 烧的是自己的额度，玩家得知道自己为什么忽然多了几条搜索。
        final boolean mine = MpProto.bool(msg, "mine", false);
        if (entryId <= 0L || songId.isEmpty() || !inRoom()) return;
        // 队列快照通常已经认识这首歌；不认得时这一帧的键名与曲目对象一致，直接复用那套映射。
        MpTrack known = trackOf(src, songId);
        final MpTrack track = known != null ? known : MpTrack.fromJson(msg);
        if (track == null || MpTrack.platformOf(track.source) == null) return;
        if (RESCUE_IN_FLIGHT.size() > RESCUE_SET_LIMIT) RESCUE_IN_FLIGHT.clear();
        if (!RESCUE_IN_FLIGHT.add(Long.valueOf(entryId))) return;
        final Music origin = track.toMusic();
        final String display = track.display();
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    runRescue(entryId, origin, deadline, display, mine);
                } finally {
                    RESCUE_IN_FLIGHT.remove(Long.valueOf(entryId));
                }
            }
        });
    }

    /** 流水线的实体：找到就交 {@code track_replace}，找不到就一句说明白。只能在后台线程上调。 */
    private static void runRescue(long entryId, Music origin, long deadline, String display,
                                  boolean mine) {
        // 每一条「这台做不了」都要当场说一声：服务端为收到派单的这一首把总闸多放宽了一个救援窗，
        // 沉默等于让它把这一窗白等完才去问下一台，而那些机器里可能正好有一台换得到。
        if (!CrossSourceSwitcher.canAutoSwitch(origin, true)) {
            System.out.println("[MpParty] 救援跳过《" + display
                    + "》：换源开关关着、没有够得着的候选平台，或这首歌刚搜过一遍/已有失败结论");
            switchDecline(entryId, "switch-off");
            return;
        }
        CrossSourceSwitcher.Match match;
        try {
            match = CrossSourceSwitcher.findReplacementForAuto(origin, CloudMusic.quality);
        } catch (Throwable failure) {
            System.err.println("[MpParty] 救援流水线异常 entry=" + entryId + "：" + failure);
            switchDecline(entryId, "pipeline-failed");
            return;
        }
        String url = match == null || match.url == null ? null : match.url.getA();
        if (match == null || match.music == null || url == null || url.trim().isEmpty()) {
            note(mine ? "没在别的平台找到《" + display + "》能播的版本"
                    : "没替《" + display + "》在本机找到别的平台的可播版本", true);
            switchDecline(entryId, "no-candidate");
            return;
        }
        url = url.trim();
        if (deadline > 0L && System.currentTimeMillis() > deadline) {
            // 服务端多半已经把这一格跳掉了，替换帧会被判「不在队列里」。仍然要说清楚：
            // 本机确实换到了，玩家自己再点一次就有 —— 一句「没找到」反而是假话。
            note("换到了《" + display + "》的 " + match.platformLabel + " 版本，但房间已经跳过这首歌了", true);
            return;
        }
        // 判过窗再估：过点了连替换帧都不发，那次 HEAD 就是纯浪费。
        // 参照时长取**替身自己**声明的那一个，不是原曲的：服务端拿 urlDuration 与这一格的
        // duration 比，而替换之后这一格的 duration 就是替身的（跨平台的同一首歌常差一两秒，
        // 拿原曲当参照会在边界上判错方向）。
        long est = estimateServedMs(match.music, url, match.music.getDuration(), deadline);
        JsonObject m = MpProto.msg(MpProto.C_TRACK_REPLACE);
        m.addProperty("entryId", entryId);
        m.add("track", MpTrack.toJson(match.music, url, urlDeadline(), est, est > 0L));
        if (send(m)) note(mine ? "已把《" + display + "》换成 " + match.platformLabel + " 版本"
                : "已替房间把《" + display + "》换成 " + match.platformLabel + " 版本", false);
    }

    /**
     * 回一句「这台换不到」，让服务端立刻改派下一台。
     *
     * <p>{@code entryId} 缺失说明对面是不认这一帧的老服务端，闭嘴即可 —— 那一台由服务端等满
     * 窗口再换，行为只是慢一点。老服务端收到不认识的 op 会往聊天栏回一句「不支持的操作」，
     * 所以这里走 {@link #sendQuiet}。</p>
     */
    private static void switchDecline(long entryId, String reason) {
        if (entryId <= 0L) return;
        JsonObject m = MpProto.msg(MpProto.C_SWITCH_DECLINE);
        m.addProperty("entryId", entryId);
        m.addProperty("reason", reason == null ? "" : reason);
        sendQuiet(m);
    }

    /**
     * 在当前曲目和歌单里找这首歌，用于拿到房间认定的时长和完整元数据。
     *
     * <p>按 {@code source + songId} 匹配，不能只看 songId —— 匹配错了会把 A 平台的链接续签给
     * B 平台的曲目。</p>
     */
    private static MpTrack trackOf(String source, String songId) {
        String key = MpProto.trackKey(source, songId);
        MpTrack cur = currentTrack;
        if (cur != null && cur.key().equals(key)) return cur;
        for (MpTrack t : queue()) {
            if (t.key().equals(key)) return t;
        }
        return null;
    }

    /**
     * 解出来的链接是不是只有试听片段。
     *
     * <p>两个时长都来自网易云自己（歌曲长度 vs 链接实际提供的长度），不用额外请求；
     * 80% 的阈值容忍不同转码之间的尾部差异。其他平台不报后者（传 0），这条保护对它们不生效 ——
     * 这是共享直链模型的既有代价。
     *
     * <p>实测流时长超过 90 秒就不再判试听：servedMs 与 trackMs 来自两个不同的接口，同名不同
     * 版本可以差出 20% 以上，纯比例判据会把一条完整版对着偏长的元数据误判成试听 —— 而误判是
     * 确定性的，房间里每个会员都会在同一个判定上弃权，全层落空后再被换源救援改成别的平台。
     * 真实试听固定 30/60 秒量级，90 秒以上的流必然是完整歌。与服务端 {@code Track.isTrialClip}
     * 的豁免线同源，两端不一致会出现「客户端放行、服务端拒收」的劈叉。
     */
    private static final long TRIAL_CLIP_SERVED_FLOOR_MS = 90_000L;

    private static boolean isTrialClip(long servedMs, long trackMs) {
        if (servedMs <= 0L || trackMs <= 0L) return false;   // 未知就放行
        if (servedMs > TRIAL_CLIP_SERVED_FLOOR_MS) return false;
        return servedMs < trackMs * 0.8;
    }

    // ── 发帧 ─────────────────────────────────────────────────────────────────

    private static boolean requireChannel() {
        if (usable()) return true;
        note("当前服务器未安装一起听服务端模组", true);
        return false;
    }

    /**
     * 把本机算出的交接计划报给服务端，由它广播给全房。
     *
     * <p>房间里的交接点必须全房一致，所以分析收归服务端指定的那一台，其余人执行。
     * <b>上报者不要求是房主</b> —— 服务端可能把这件事派给点歌人，也可能派给房间里任意一台
     * 装了模型的机器；这里只做一次「本机确实是当前被指派者」的本地判断，省掉一个注定被丢弃
     * 的网络往返。发失败不重试 —— 这一首退回「等计划 / 不交叠」，下一首会再走一遍同样的流程。</p>
     *
     * <p>调用方是播放线程上的预备任务（那边正在读已解码缓存做分析），而网络发送只在主线程
     * 安全，所以这里派发过去；指派状态也在那一刻重新确认一次，分析期间房间可能换了人算。</p>
     */
    private static void reportAutomixPlan(final long entryId, final JsonObject plan) {
        if (entryId <= 0L || plan == null) return;
        if (room == null || !RoomAutomix.assignedToUs()) return;
        MultiThreadingUtil.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (room == null || !RoomAutomix.assignedToUs()) return;
                JsonObject m = MpProto.msg(MpProto.C_AUTOMIX_PLAN);
                m.addProperty("entryId", entryId);
                m.add("plan", plan);
                if (sendQuiet(m)) {
                    System.out.println("[MpParty] 已上报房间交接计划 entry=" + entryId + " " + plan);
                }
            }
        });
    }

    /**
     * 告诉服务端「本机算不出这一首」，请它当场改派下一台。
     *
     * <p>没有这一帧的话，每问到一台算不出的机器都要白等一个指派超时 —— 而这类机器
     * （曲目太短、音频拉不到、模型缺失）恰恰是常见的。走主线程的理由与上报计划相同。</p>
     */
    private static void reportAutomixDecline(final long entryId) {
        if (entryId <= 0L) return;
        if (room == null) return;
        MultiThreadingUtil.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (room == null) return;
                JsonObject m = MpProto.msg(MpProto.C_ANALYZE_DECLINE);
                m.addProperty("entryId", entryId);
                if (sendQuiet(m)) {
                    System.out.println("[MpParty] 已放弃本次分析 entry=" + entryId + "，等服务端改派");
                }
            }
        });
    }

    private static boolean send(JsonObject msg) {
        if (!usable()) {
            note("当前服务器未安装一起听服务端模组", true);
            return false;
        }
        return sendQuiet(msg);
    }

    private static boolean sendQuiet(JsonObject msg) {
        MpHost h = host;
        if (h == null) return false;
        // 不在世界里就没有可发之处：本机发送端会当场抛 "Cannot send packets when not in game!"。
        // 这里是所有发送路径的唯一出口，挡在这里等于把「压根没有连接」这件事一次收敛 ——
        // 除了 tick 的每秒试探，掉线那一瞬间正好撞上来的心跳、在线态查询、计划上报
        // 走的是同一条路，都会静默失败而不是抛给日志。
        if (!inGame()) return false;
        try {
            h.send(MpProto.encode(msg));
            lastSendFailure = "";
            return true;
        } catch (Throwable throwable) {
            // 同一个原因只报一次。挡过上一条之后，走到这里已经是真的异常了。
            String reason = String.valueOf(throwable);
            if (!reason.equals(lastSendFailure)) {
                lastSendFailure = reason;
                System.err.println("[MpParty] 发送失败：" + reason);
            }
            return false;
        }
    }

    /** 上一次发送失败的原因，用于去重日志。 */
    private static volatile String lastSendFailure = "";

    // ── 杂项 ─────────────────────────────────────────────────────────────────

    private static void reset() {
        room = null;
        currentTrack = null;
        playing = false;
        posBase = 0L;
        posStamp = System.currentTimeMillis();
        durationMs = 0L;
        appliedSeq = -1L;
        waitingUrl = false;
        selfPaused = false;
        // 邀请横幅刻意不在这里清：S_LEFT（主动离房）也会走到 reset，而「离开了 A 房间」
        // 不等于「不想去 B 房间」——收到过的邀请仍然有效，清掉等于砍掉玩家的一键加入入口
        // （v2 的 pendingInvites 同样不在离房路径清，两条链路口径一致）。真正的清理点是
        // clearServerScopeState()：换服务器/切直连会话时旧房间的邀请全部作废。
        // 离房：收走队列里多出来的那一格与已预置的下一首，别让它跟着本机自己的队列继续播。
        // 交叠开关本身不动 —— 它来自服务端 hello，跟房间无关。
        // 播放的停止由调用方负责（onDisconnect / resetForDirectSession 各自判断 had）。
        MpPlayback.clearRoomAutomix();
        MpSyncPolicy.reset();
        contributionHintShown = false;      // 下次进房重新说一次
    }

    /**
     * 显式清理服务器房间的客户端状态（直连开房 / 加入前调用）。
     *
     * <p>与 {@link #onDisconnect()} 的区别：这一份<b>不经过直连守卫</b>——那条守卫
     * 挡的是「MC 通道断开事件不许杀直连会话」，而这里是要在切到直连之前把上一个
     * 房间（可能是服务器房间）的残留主动扔掉。不清理的话，{@code room} 等字段会
     * 继续显示一个已经不再参与的服务器房间。</p>
     *
     * <p>如果本机在服务器房间里，会先发一帧离房（让服务端把人请出去、别的成员看到
     * 离开提示），再清本机状态。直连房间自己（{@code MpDirect.isActive()} 为 true）
     * 时不做任何事——那次的清理由直连会话自己的生命周期负责。</p>
     *
     * <p>清理范围与 {@link #onDisconnect()} 对齐（{@link #clearServerScopeState()}）：
     * 只清房间不足以甩掉上一个服务器的痕迹 —— 封禁横幅、公开房间列表、待处理邀请
     * 都不属于「房间」，却会整段挂进直连会话（公开房被直连配置关掉后不会再被刷新，
     * 封禁位没有任何直连帧会覆写）。</p>
     */
    public static void resetForDirectSession() {
        if (com.muoniumplayer.party.direct.MpDirect.isActive()) return;
        boolean had = room != null;
        if (had) {
            // 通知服务端离房（fire-and-forget：发出去了就行，不等回执）。
            // 必须在 clearServerScopeState 之前发：那时通道还指向 MC 服务器。
            sendQuiet(MpProto.msg(MpProto.C_LEAVE));
        }
        clearServerScopeState();
        reset();
        if (had) MpPlayback.stop();
        notifyListeners();
    }

    static void note(String text, boolean error) {
        if (text == null || text.trim().isEmpty()) return;
        lastMessage = text;
        lastMessageIsError = error;
        final MpHost h = host;
        final String line = "[一起听] " + text;
        if (h != null) {
            MultiThreadingUtil.runOnMainThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        h.chat(line);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        notifyListeners();
    }

    private static void notifyListeners() {
        List<Runnable> snapshot = new ArrayList<Runnable>(LISTENERS);
        for (Runnable r : snapshot) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 诊断用：一行房间状态，便于在没有界面的情况下确认同步是否在跑。 */
    public static String diag() {
        MpRoom r = room;
        MpTrack t = currentTrack;
        StringBuilder sb = new StringBuilder();
        sb.append("channel=").append(available());
        sb.append(" hello=").append(helloAcked).append('/').append(helloProbes);
        sb.append(" room=").append(r == null ? "-" : r.code);
        sb.append(" role=").append(self() == null ? "-" : self().roleLabel());
        sb.append(" track=").append(t == null ? "-" : t.display());
        sb.append(" pos=").append(positionMs()).append('/').append(durationMs);
        sb.append(" playing=").append(playing);
        sb.append(" localPos=").append(MpPlayback.localPositionMs());
        sb.append(" localPlaying=").append(MpPlayback.localPlaying());
        sb.append(" mode=").append(CloudMusic.playMode);
        return sb.toString();
    }
}
