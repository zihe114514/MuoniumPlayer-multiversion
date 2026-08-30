package com.muoniumplayer.party;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.CloudMusic;
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
     */
    private static void probeHello() {
        if (helloProbes >= HELLO_PROBE_LIMIT) return;
        JsonObject hello = MpProto.msg(MpProto.C_HELLO);
        hello.addProperty("proto", MpProto.VERSION);
        hello.addProperty("client", "muoniumplayer");
        // 只有真的发出去了才记一次配额。ticker 从 mod 初始化就开始跑，主菜单上每秒都会来一发，
        // 而那时 Fabric 的发送会直接抛 "Cannot send packets when not in game!" —— 旧写法照样扣数，
        // 于是在标题界面停留半分钟就把 30 次试探全烧掉了，真正进服之后一次招呼也发不出去。
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
        reset();
        if (had) MpPlayback.stop();
        notifyListeners();
    }

    private static int heartbeatCounter;

    private static void tick() {
        try {
            MpPlayback.tick();
            // 通道声明有时比 JOIN 事件晚到一点，那一次 hello 就白发了；这里按秒补发，
            // 直到服务端回话或者试探次数用完。
            if (!helloAcked) {
                if (available()) serverPresent = true;
                probeHello();
            }
            if (room == null) return;
            if (++heartbeatCounter < 5) return;
            heartbeatCounter = 0;
            JsonObject msg = MpProto.msg(MpProto.C_PROGRESS);
            msg.addProperty("pos", MpPlayback.localPositionMs());
            msg.addProperty("playing", MpPlayback.localPlaying());
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
                    MpNetease.Resolved resolved = MpNetease.resolve(song.getId());
                    if (resolved == null) {
                        delegateResolve(op, song, playNext, "本机拿不到播放链接");
                        return;
                    }
                    if (isTrialClip(resolved.servedMs, song.getDuration())) {
                        // 房间给所有人下发的就是这一条链接，试听片段会让整房都听不全，所以这条
                        // 链接不能上传 —— 但歌可以点：交给房间里的会员成员代为解析。
                        delegateResolve(op, song, playNext, "本机只解析到试听片段");
                        return;
                    }
                    sendTrack(op, song, resolved.url, resolved.servedMs, playNext);
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
     * @return true 表示本次起播已被房间消化（改成了点歌），调用方必须直接返回
     */
    public static boolean interceptStartPlayback(List<Music> songs, int startIdx) {
        if (applyingRemote || room == null) return false;
        Music song = pick(songs, startIdx);
        if (song != null && MpPlayback.isPartyMusic(song)) return false;   // 房间自己下发的那一首
        if (song == null) return true;
        return enqueueInstead(song);
    }

    /**
     * 播放器的「下一首播放」。
     *
     * @return true 表示已改成房间点歌
     */
    public static boolean interceptPlayNext(Music song) {
        if (applyingRemote || room == null) return false;
        if (song != null && MpPlayback.isPartyMusic(song)) return false;
        if (song == null) return true;
        return enqueueInstead(song);
    }

    /** 把一次本地起播/插播改成房间点歌，并给出提示。 */
    private static boolean enqueueInstead(Music song) {
        if (!canQueue(song)) {
            note("这首歌没有可以分享给房间的曲目信息，已忽略本次播放", true);
            return true;
        }
        // 先过通道这一关再报「已加入」。原来的顺序是先报成功、再由 upload() 里的
        // requireChannel() 失败 —— 于是聊天栏里连着出现「已加入一起听歌单：X」和
        // 「当前服务器未安装一起听服务端模组」，前一句是假的，而且掩盖了真正的故障。
        if (!requireChannel()) return true;
        queueNext(song);
        return true;
    }

    /** @return true 表示「下一首」已被房间消化 */
    public static boolean interceptNext() {
        if (!engaged()) return false;
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
            notifyListeners();
        } else if (MpProto.S_ROOM_STATE.equals(op)) {
            applySnapshot(msg);
        } else if (MpProto.S_QUEUE.equals(op)) {
            MpRoom r = room;
            if (r != null) {
                room = r.withQueue(MpProto.arr(msg, "queue"), MpProto.arr(msg, "history"));
                notifyListeners();
            }
        } else if (MpProto.S_PLAY.equals(op)) {
            MpTrack track = MpTrack.fromJson(MpProto.obj(msg, "track"));
            if (track == null) return;
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
            MpPlayback.resume(posBase);
            notifyListeners();
        } else if (MpProto.S_SEEK.equals(op)) {
            // 房主／管理员拖进度是明确命令：立刻执行，不受软硬阈值和冷却约束。
            MpSyncPolicy.markCommand();
            stamp(compensate(MpProto.num(msg, "pos", 0L), MpProto.num(msg, "serverTime", 0L)),
                    playing, MpProto.num(msg, "seq", 0L));
            MpPlayback.seek(posBase);
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
            MpPlayback.time(pos, dur, isPlaying);
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
        }
    }

    private static void applySnapshot(JsonObject msg) {
        MpRoom snapshot = MpRoom.fromJson(MpProto.obj(msg, "room"));
        room = snapshot;
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
        MpPlayback.play(track, track.url, pos, isPlaying);
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
     * 回应服务端的 {@code url_needed}：用本机会话重新解析一条直链。
     *
     * <p>两种情况下宁可<b>保持沉默</b>让别人来答：本机没有这个平台（根本解析不出来），以及
     * 解析出来的只是试听片段 —— 非会员账号替会员曲目应答，那条链接会成为整房共用的那一条。
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
                if (MpProto.SOURCE_NETEASE.equals(src)) {
                    long id;
                    try {
                        id = Long.parseLong(songId);
                    } catch (Throwable ignored) {
                        return;
                    }
                    MpNetease.Resolved resolved = MpNetease.resolve(id);
                    if (resolved == null) return;                     // 别的成员也许还能答
                    if (isTrialClip(resolved.servedMs, expected)) return;
                    sendUrlSupply(src, songId, resolved.url, resolved.servedMs);
                    return;
                }
                if (known == null) return;                            // 不认识这首歌，答不了
                // 用房间元数据重建曲目再解析。刻意不带 partyStreamUrl —— 带了 getPlayUrl()
                // 会把那条已经过期的链接原样返回。
                String url = resolveByPlayer(known.toMusic());
                if (url == null) return;
                sendUrlSupply(src, songId, url, 0L);
            }
        });
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
        try {
            h.send(MpProto.encode(msg));
            lastSendFailure = "";
            return true;
        } catch (Throwable throwable) {
            // 同一个原因只报一次。握手试探是每秒一发的，在主菜单上会连续几十次拿到
            // "Cannot send packets when not in game!"，逐条打出来只是把日志刷满。
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
