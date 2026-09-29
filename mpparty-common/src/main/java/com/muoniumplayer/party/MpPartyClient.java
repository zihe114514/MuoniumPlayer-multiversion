package com.muoniumplayer.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.ncm.music.CrossSourceSwitcher;
import com.muoniumplayer.core.ncm.music.PlatformAccess;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ListenTogetherSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.RoomAutomix;
import com.muoniumplayer.core.ncm.music.dto.Music;
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
        boolean had = room != null;
        serverPresent = false;
        helloAcked = false;
        helloProbes = 0;
        // 这几份是「这台服务器的」状态，换服务器必须清干净，否则上一台的公开房间列表、
        // 待处理邀请和封禁横幅会跟着显示在新服务器上。
        serverProto = 0;
        serverAdmin = false;
        channelEnabled = false;
        publicRoomsEnabled = true;
        serverResolve = false;
        banned = false;
        banReason = "";
        banRemaining = "";
        presenceList = Collections.emptyList();
        publicRooms = Collections.emptyList();
        pendingInvites = Collections.emptyList();
        onlineCount = 0;
        // 这是「这台服务器的」交叠开关，换服务器必须清掉：新服务端会在它自己的 hello 里
        // 重新下发，收不到（老服务端）就保持 0 = 不交叠。
        RoomAutomix.configure(0L);
        // 分析任务同样跟着房间走：断开之后本机不再是任何房间的被指派者，也不再是房主。
        // 两样都要清 —— 只要还留着「我是被指派的」，下一次进房就会抢着算一份没人要的计划。
        RoomAutomix.setAssigned(false, 0L);
        RoomAutomix.setNobodyCanAnalyze(false);
        RoomAutomix.setOwner(false);
        reset();
        if (had) MpPlayback.stop();
        notifyListeners();
    }

    private static int heartbeatCounter;
    private static int presenceCounter;

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
        presenceInterest = System.currentTimeMillis();
    }

    // ── 写接口 ───────────────────────────────────────────────────────────────

    public static void createRoom(String name) {
        JsonObject m = MpProto.msg(MpProto.C_CREATE);
        if (name != null && !name.trim().isEmpty()) m.addProperty("name", name.trim());
        send(m);
    }

    public static void joinRoom(String code) {
        JsonObject m = MpProto.msg(MpProto.C_JOIN);
        m.addProperty("code", code == null ? "" : code.trim());
        send(m);
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
        selfPaused = paused;
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
        Music now = localNowPlaying();
        m.addProperty("nowPlaying", now == null ? "" : safe(now.getName()));
        m.addProperty("nowArtist", now == null ? "" : safe(now.getArtistsName()));
        m.addProperty("localPlaying", MpPlayback.localPlaying());
    }

    /** 本机此刻在听什么（不限于房间曲目：全服在线态要的是「他在听什么」）。 */
    private static Music localNowPlaying() {
        try {
            return CloudMusic.currentlyPlaying;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 是否愿意替别人解析（贡献会员解析能力）。默认关，必须玩家显式打开。 */
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
                        // 脚本不报实际长度，传 0 = 未知，服务端按放行处理。
                        sendTrack(op, song, viaCustom, 0L, playNext);
                        return;
                    }
                    delegateResolve(op, song, playNext,
                            resolved == null ? "本机拿不到播放链接" : "本机只解析到试听片段");
                    return;
                }
                String url = resolveByPlayer(song);
                if (url == null) {
                    delegateResolve(op, song, playNext, "本机拿不到播放链接");
                    return;
                }
                // 其他平台不报「链接实际时长」，传 0 = 未知，服务端按放行处理。
                sendTrack(op, song, url, 0L, playNext);
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
        note(why + "，已把《" + song.getName() + "》交给房间里的会员成员代为解析", false);
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
                sendUrlSupply(src, id, url, 0L);
                note("本机用自定义源补上了《" + title + "》的播放地址", false);
            }
        });
    }

    private static void sendTrack(String op, Music song, String url, long servedMs, boolean playNext) {
        JsonObject m = MpProto.msg(op);
        m.add("track", MpTrack.toJson(song, url, urlDeadline(), servedMs));
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
        if (!isController()) {
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
        if (!isController()) {
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
        if (isController()) return true;
        noteThrottled("只有房主或管理员可以控制一起听的播放", true);
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
            // 不用等下一发心跳。
            if (MpProto.bool(msg, "resync", false)) selfPaused = false;
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
                    MpProto.str(msg, "songId", ""));
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
            MpPlayback.time(pos, durationMs, isPlaying);
            notifyListeners();
        }
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
     * <p>三种情况下宁可<b>保持沉默</b>让别人来答：本机没有这个平台（根本解析不出来）、重建不出
     * 这首歌的元数据、以及解析出来的只是试听片段 —— 非会员账号替会员曲目应答，那条链接会成为
     * 整房共用的那一条。
     *
     * <p>本家链路之外还要<b>补问一次自定义源</b>。以前网易云这一路只走 {@link MpNetease#resolve}
     * （挑它是因为它会报出「这条链接实际能取到多长」，据此才识破试听片段），代价是绕过了
     * {@code Music.getPlayUrl()} 里排在最前的自定义源那一跳；再叠上「非会员拿到试听就沉默」，
     * 结果就是房间里那个<b>没有会员、但脚本明明能解</b>的人跟着一起闭嘴 —— 全房等一轮超时，
     * 然后这首歌被跳过。这一跳补上之后，代解析的判据才是真的「谁能解出来」，而不是「谁是会员」。
     */
    private static void supplyUrl(final String source, final String songId) {
        if (songId == null || songId.trim().isEmpty()) return;
        final String src = MpProto.sourceOf(source);
        final MpTrack known = trackOf(src, songId);
        if (MpTrack.platformOf(src) == null) return;      // 本机没有这个播放源
        final long expected = known == null ? 0L : known.durationMs;

        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                // 用房间元数据重建曲目。刻意不带 partyStreamUrl —— 带了 getPlayUrl()
                // 会把那条已经过期的链接原样返回。
                Music target = known == null ? null : known.toMusic();
                if (MpProto.SOURCE_NETEASE.equals(src)) {
                    long id = parseSongId(songId);
                    if (id > 0L) {
                        MpNetease.Resolved resolved = MpNetease.resolve(id);
                        boolean usable = resolved != null && !isTrialClip(resolved.servedMs, expected);
                        if (usable) {
                            sendUrlSupply(src, songId, resolved.url, resolved.servedMs);
                            return;
                        }
                        // 本家给不出完整版（没登录 / 不是会员 / 只解到试听）。脚本元数据不在这条
                        // 分支的已知曲目里，所以按 id 补查一次元数据才有办法问脚本 —— 这一步只在
                        // 「本家已经失败」之后发生，正常会员路径一次网络都不多烧。
                        if (target == null && contributeLx()) target = MpNetease.songById(id);
                    }
                }
                String url = target == null ? null : customSourceUrl(target);
                if (url == null) return;                                // 沉默，让别人答
                sendUrlSupply(src, songId, url, 0L);
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

    private static void sendUrlSupply(String source, String songId, String url, long servedMs) {
        JsonObject m = MpProto.msg(MpProto.C_URL_SUPPLY);
        m.addProperty("source", source);
        m.addProperty("songId", songId);
        m.addProperty("url", url);
        m.addProperty("urlExpiresAt", urlDeadline());
        if (servedMs > 0L) m.addProperty("urlDuration", servedMs);
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
     */
    private static boolean isTrialClip(long servedMs, long trackMs) {
        if (servedMs <= 0L || trackMs <= 0L) return false;   // 未知就放行
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
        // 离房：收走队列里多出来的那一格与已预置的下一首，别让它跟着本机自己的队列继续播。
        // 交叠开关本身不动 —— 它来自服务端 hello，跟房间无关。
        MpPlayback.clearRoomAutomix();
        MpSyncPolicy.reset();
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
