package com.muoniumplayer.party.direct;

import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpProto;
import yun.shan.mpsync.MpParty;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * 直连房间的门面：开房（托管）、加入（成员）、关房、邀请串拼解。
 *
 * <p>开房 = 本 JVM 起一个 {@link DirectServer} 挂引擎，然后本机的
 * {@code MpPartyClient} 走 127.0.0.1 回环连进来——房主自己也是成员，走与
 * 其他成员完全相同的链路（与局域网模式同哲学：客户端不关心 hello 是谁答的）。
 * 引擎的成员列表、播放桥接、automix 指派全部照常。</p>
 *
 * <p>加入 = 起一个 {@link DirectClient} 连到房主，活跃时由 {@link MpHostRouter}
 * 换绑成当前传输。断开时路由自动换回 MC 通道。</p>
 *
 * <p>邀请串：{@code 地址:端口#密码} 或 {@code 地址:端口#密码#房间码}（带房间码时
 * 成员连上后自动进房）。直连模式下「引擎就在房主的 JVM 里」，所以服务器房间的
 * 一切功能——点歌、投票、分层解析、automix——原样可用。</p>
 */
public final class MpDirect {

    private MpDirect() {
    }

    // ── 状态 ────────────────────────────────────────────────────────────────

    private static volatile DirectServer server;
    /** 房主本机那条回环连接。 */
    private static volatile DirectClient loopback;
    /** 成员侧的直连会话。 */
    private static volatile DirectClient client;
    /** 直连会话是否活跃（托管或加入任一）。 */
    private static volatile boolean active;
    /** 直连建连进行中（后台线程）：挡住第二次「加入」与建连窗口内的「开直连房间」。 */
    private static volatile boolean pendingJoin;
    /**
     * 会话代次：{@link #close()} 与其它全量清理点每执行一次就 +1。
     * 在途的 join 线程落地前核对它——期间发生过清理就放弃落地（僵尸会话防线）。
     */
    private static volatile long generation;
    /** 当前托管房间的密码；非空时 {@code MpPartyClient.fillProfile} 把它捎进 hello。 */
    private static volatile String hostAuth = "";
    /** 直连会话的密码（成员侧）；非空时 hello 帧带上。 */
    private static volatile String joinAuth = "";

    public static boolean isActive() {
        return active;
    }

    /**
     * 直连帧接收侧的会话存活校验（供 MpPartyClient.onFrame 闸门用）。
     *
     * <p>{@code active} 只能证明「有会话意图」，不能证明「这条带 directSource 标记的帧
     * 还属于一个活着的会话」：close()/onSessionClosed 都先复位 active 再关 socket，
     * readLoop 里的残余帧会在复位之后才投到。这里按「还有任何一条活会话」判——
     * 迟到的孤儿帧（会话已被顶掉或终结）据此按过期丢弃，不再污染刚清零的客户端状态。
     * 活会话期间恒为 true，不增加任何开销。</p>
     */
    public static boolean frameSinkLive() {
        DirectClient c = client;
        if (c != null && c.isConnected()) return true;
        DirectClient l = loopback;
        return l != null && l.isConnected();
    }

    public static boolean isHosting() {
        return server != null && server.isRunning();
    }

    public static boolean isJoined() {
        DirectClient c = client;
        return c != null && c.isConnected();
    }

    public static boolean isLoopback() {
        DirectClient l = loopback;
        return l != null && l.isConnected();
    }

    /**
     * 拼邀请串。{@code host} 为空时只给 {@code 端口#密码} 形态（UI 会在复制前填地址）；
     * 未托管返回空串。
     */
    public static String inviteString(String host) {
        DirectServer s = server;
        if (s == null || !s.isRunning()) return "";
        String h = host == null ? "" : host.trim();
        return h.isEmpty()
                ? s.listeningPort() + "#" + hostAuth
                : h + ":" + s.listeningPort() + "#" + hostAuth;
    }

    /** 当前托管房间的密码；未托管返回空串（供「单独复制密码」）。 */
    public static String hostPassword() {
        return isHosting() ? hostAuth : "";
    }

    /** 当前连到本机托管房间的成员数（不含房主回环）；未托管返回 0。 */
    public static int onlineMemberCount() {
        DirectServer s = server;
        if (s == null || !s.isRunning()) return 0;
        DirectClient l = loopback;
        int loop = l != null && l.isConnected() ? 1 : 0;
        return Math.max(0, s.platform().snapshot().size() - loop);
    }

    /** 当前托管房间的监听端口；未托管返回 0。 */
    public static int port() {
        DirectServer s = server;
        return s == null ? 0 : s.listeningPort();
    }

    /**
     * 回应房主的保活 ping。回执会让房主那侧的活性计时刷新——
     * 房间空闲期没有任何别的下行/上行，没有它，半开成员 90 秒后就会被误清。
     * 未在直连会话里（老客户端路径/帧串扰）时静默丢弃。
     *
     * <p>房主自己的回环连接（{@link #loopback}）也会收到 ping：它同样要回 pong，
     * 否则房主离开世界（退标题/被踢）后 C_PROGRESS 心跳停发，回环连接 90 秒就会被
     * stale 清扫误判，连锁触发 {@code onSessionClosed → server.stop()}——整个房间
     * 自毁、全体成员被断开。直连房间的设计语义是独立于游戏服务器存活的，这条必须保住。</p>
     */
    public static void sendPong(long timestamp) {
        com.google.gson.JsonObject pong = MpProto.msg(MpProto.C_PONG);
        pong.addProperty("t", timestamp);
        byte[] payload = MpProto.encode(pong);
        DirectClient c = client;
        if (c != null && c.isConnected()) {
            c.send(payload);
        }
        // 回环连接同样要续命（房主 = 这台机器自己既是服务端又是成员）。
        DirectClient l = loopback;
        if (l != null && l.isConnected()) {
            l.send(payload);
        }
    }

    // ── 开房（托管） ────────────────────────────────────────────────────────

    /**
     * 开一个直连房间：起引擎 + 回环自连，成功后自动建房。
     *
     * @param port          监听端口（记住到设置里）
     * @param onReady       房间就绪后的回调（UI 用它刷新页面）；可为 null
     * @return 错误说明；null = 成功
     */
    public static String hostRoom(int port, Runnable onReady) {
        if (isHosting()) return "直连房间已在托管中";
        if (isJoined()) return "已加入好友的直连房间，请先断开再开自己的房间";
        if (pendingJoin) return "正在连接好友的直连房间，请先等它完成或超时";
        if (port < 1024 || port > 65535) return "端口要在 1024–65535 之间";
        // 与官方一起听互斥（两套引擎都想独占播放器，与 LAN 互斥同一批理由）。
        if (com.muoniumplayer.core.ncm.music.ListenTogetherService.isActive()) {
            return "官方一起听正在进行，请先退出官方房间再开直连房间";
        }
        // 与局域网一起听互斥：两者共用同一个 MpParty 引擎单例，直连 init 会把 LAN
        // 正在跑的房间整体顶掉（成员列表、队列、播放全清空），且 LAN 的帧还会继续
        // 打进已被直连接管的引擎——必须先关一边。
        //
        // 但只挡「真的有房间在跑」：纯单人存档里 LAN 引擎也常驻激活（这是
        // LanMpParty 的设计，纯单人不开局域网也 init），如果连这种空挂状态都拒绝，
        // 玩家在单人世界里永远开不了直连房。引擎空挂时直连 init 本来就会重建单例，
        // 让位是安全的。
        if (com.muoniumplayer.party.lan.LanMpParty.isActive() && lanRoomsRunning()) {
            return "局域网一起听正在运行，请先关掉它再开直连房间";
        }

        // 引擎配置与直连设置同目录：config/muonium/。
        Path configDir = configDirectory();
        if (configDir == null) return "找不到配置目录";

        String auth = newPassword();
        DirectServer s = new DirectServer(port, auth, null);
        try {
            // 走 DirectRoomConfig 装配的收口配置（关频道/公开房——直连场景里
            // 「全服」就是这一个 JVM），落盘文件照常可调参。
            MpParty.init(s.platform(),
                    DirectRoomConfig.load(configDir.resolve("mpsync-direct.json")),
                    configDir.resolve("mpsync-direct.json"));
        } catch (Throwable t) {
            s.stop();
            return "引擎启动失败：" + t.getMessage();
        }
        try {
            s.start();
        } catch (IOException e) {
            MpParty.shutdown();
            com.muoniumplayer.party.lan.LanMpParty.onDirectReleased();
            if (e instanceof java.net.BindException) {
                return "端口 " + port + " 被占用，换一个试试";
            }
            return "监听失败：" + e.getMessage();
        }
        server = s;
        hostAuth = auth;
        try {
            com.muoniumplayer.core.ncm.music.DirectRoomSettings.setLastPort(port);
        } catch (Throwable ignored) {
        }

        // 房主回环自连：走与其他成员完全相同的握手/帧链路。
        DirectClient self = new DirectClient("127.0.0.1", port, auth);
        DirectClient.ConnectResult r = self.connect();
        if (!r.ok) {
            s.stop();
            MpParty.shutdown();
            server = null;
            hostAuth = "";
            com.muoniumplayer.party.lan.LanMpParty.onDirectReleased();
            return "本机回环连接失败：" + r.error;
        }
        // 服务端与回环会话都立住了，这一步才算真的切过去——此刻才扔服务器房间的
        // 残留（在房间里的先发离房帧）。开房失败时玩家还能留在原地。
        MpPartyClient.resetForDirectSession();
        loopback = self;
        MpHostRouter router = MpHostRouter.instance();
        if (router != null) router.attachDirect(self);
        active = true;
        // 等 S_HELLO 到达（客户端变 usable）再发建房帧；就绪后回调 UI。
        // 记下托管服务端与代次：这 5 秒等待窗里用户可能已经关房（close 会推进代次、
        // 摘除路由）。不核对的话，关房后 serverPresent() 会因 MC 通道的 available()
        // 立刻放行，这一帧名为「直连房间」的 C_CREATE 就发到了真实 MC 服务器上，
        // 凭空建出一个没人要的房间。
        final DirectServer myServer = s;
        final long myGeneration = generation;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 50 && !MpPartyClient.serverPresent(); i++) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (myGeneration != generation || server != myServer) return;
            sendCreate();
            if (onReady != null) onReady.run();
        }, "muonium-direct-create");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** 游戏配置目录（{@code config} 的父目录）；拿不到返回 null。 */
    private static Path configDirectory() {
        try {
            // 各版本的入口都会把工作目录设在游戏根目录；DirectRoomSettings 落在
            // config/muonium/ 下，从这里反推根目录最稳。
            java.io.File cfg = com.muoniumplayer.core.settings.ConfigPaths.DIRECT_ROOM
                    .getParentFile()      // config/muonium
                    .getParentFile();     // config
            if (cfg == null) return null;
            return cfg.getParentFile() != null
                    ? cfg.getParentFile().toPath()   // 游戏根目录
                    : cfg.toPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void sendCreate() {
        com.muoniumplayer.party.MpHost h = currentHost();
        if (h == null) return;
        // 建房帧走客户端链路：服务端房间模式下 MpPartyClient.createRoom 同款。
        com.google.gson.JsonObject m = MpProto.msg(MpProto.C_CREATE);
        m.addProperty("name", "直连房间");
        h.send(MpProto.encode(m));
    }

    private static com.muoniumplayer.party.MpHost currentHost() {
        MpHostRouter r = MpHostRouter.instance();
        return r == null ? null : r;
    }

    private static String newPassword() {
        // 8 位 base64ish：够防扫端口的路人，又不至于念不出来。
        char[] alphabet = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(alphabet[rng.nextInt(alphabet.length)]);
        return sb.toString();
    }

    /** 当前引擎里有没有活着的一起听房间（互斥检查用；引擎未初始化算没有）。 */
    private static boolean lanRoomsRunning() {
        try {
            yun.shan.mpsync.room.RoomManager m = MpParty.manager();
            if (m == null) return false;
            for (yun.shan.mpsync.room.Room r : m.rooms()) {
                return true;   // 有任何房间就算在跑（空房间引擎自己会在宽限期后回收）
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 加入（成员） ────────────────────────────────────────────────────────

    /**
     * 加入直连房间。{@code invite} 是 {@code 地址:端口#密码} 或
     * {@code 地址:端口#密码#房间码}；带房间码时连上后自动进房。
     *
     * <p>建连在<b>后台线程</b>做（TCP 超时 + 握手最长 16 秒，不能冻住渲染线程）；
     * 结束后回调 {@code done}——参数 null = 成功，非 null = 用户可读的错误说明。
     * 回调在主线程上执行。同步路径里只做守卫与格式校验（立即返回错误说明）。</p>
     */
    public static String joinRoom(String invite, java.util.function.Consumer<String> done) {
        if (isHosting()) return "本机正在托管直连房间，先关房再加入";
        if (isJoined()) return "已经加入直连房间，请先断开再加入别的房间";
        if (com.muoniumplayer.core.ncm.music.ListenTogetherService.isActive()) {
            return "官方一起听正在进行，请先退出官方房间再加入直连房间";
        }
        // 建连在后台跑（最长 16s），期间按钮仍可点：用占位挡住第二次「加入」
        // 与「开直连房间」——否则两条连接竞速互相覆盖，in-flight join 还会
        // 穿透 active 守卫把托管路由改绑到远端。
        synchronized (MpDirect.class) {
            if (pendingJoin) return "正在连接中，请稍候";
            String[] early = parseInvite(invite);
            if (early == null) return "邀请串格式不对，应为 地址:端口#密码";
            pendingJoin = true;
        }
        String[] parts = parseInvite(invite);
        if (parts == null) {
            pendingJoin = false;
            return "邀请串格式不对，应为 地址:端口#密码";
        }
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            pendingJoin = false;
            return "端口不对";
        }
        String auth = parts[2];
        String roomCode = parts.length > 3 ? parts[3] : null;

        // 代次号：join 在后台跑 16 秒，期间可能发生过 close（关房/状态清理）——
        // 落地时发现代次变了就放弃，不再置 client/active（僵尸会话的来源）。
        final long myGeneration = generation;

        Thread t = new Thread(() -> {
            DirectClient c = new DirectClient(host, port, auth);
            DirectClient.ConnectResult r = c.connect();
            try {
                // 建连期间用户可能已开成托管（hostRoom 的守卫看不到 in-flight join）：
                // 此时放弃这条连接，绝不能覆盖刚建好的托管路由。
                if (isHosting()) {
                    c.close();
                    finishJoin("已在本机开起直连房间，本次加入已取消", done);
                    return;
                }
                // 期间发生过 close（代次已推进）：本条连接是旧世界的遗腹子，
                // 直接丢弃——落地会造出没人管的僵尸会话。
                if (myGeneration != generation) {
                    c.close();
                    finishJoin("连接期间会话状态已变更，本次加入已取消", done);
                    return;
                }
                // 清理放在连接成功之后：连不上（地址错/密码错/网络不通）时不该把玩家
                // 现在的房间一起扔掉——失败了还能留在原地。
                if (!r.ok) {
                    finishJoin(r.error, done);
                    return;
                }
                MpPartyClient.resetForDirectSession();
                client = c;
                joinAuth = auth;
                MpHostRouter router = MpHostRouter.instance();
                if (router != null) router.attachDirect(c);
                active = true;
                if (roomCode != null && !roomCode.isBlank()) {
                    sendJoin(roomCode);
                }
                finishJoin(null, done);
            } finally {
                pendingJoin = false;
            }
        }, "muonium-direct-join");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** 建连结束（无论成败）：把结果送回主线程交给 UI。 */
    private static void finishJoin(String error, java.util.function.Consumer<String> done) {
        if (done == null) return;
        try {
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil
                    .runOnMainThread(() -> done.accept(error));
        } catch (Throwable ignored) {
            // 主线程派发不可用时降级为直接回调（测试环境）。
            done.accept(error);
        }
    }

    /** 解析邀请串；不合法返回 null。 */
    public static String[] parseInvite(String invite) {
        if (invite == null) return null;
        String s = invite.trim();
        if (s.isEmpty()) return null;
        String[] seg = s.split("#");
        if (seg.length < 2) return null;
        String hostPort = seg[0].trim();
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0 || colon == hostPort.length() - 1) return null;
        String host = hostPort.substring(0, colon).trim();
        String port = hostPort.substring(colon + 1).trim();
        if (host.isEmpty() || port.isEmpty()) return null;
        String auth = seg[1].trim();
        if (auth.isEmpty()) return null;
        String[] out = new String[seg.length > 3 ? 4 : 3];
        out[0] = host;
        out[1] = port;
        out[2] = auth;
        if (out.length > 3) out[3] = seg[3].trim();
        return out;
    }

    private static void sendJoin(String roomCode) {
        com.muoniumplayer.party.MpHost h = currentHost();
        if (h == null) return;
        com.google.gson.JsonObject m = MpProto.msg(MpProto.C_JOIN);
        m.addProperty("code", roomCode);
        h.send(MpProto.encode(m));
    }

    // ── 关房 / 断开 ─────────────────────────────────────────────────────────

    /**
     * 关掉直连房间（托管侧）：断开所有成员、停引擎、路由换回 MC 通道。
     *
     * <p>收尾无条件调一次 {@code MpPartyClient.onDisconnect()} 清客户端状态
     * （音乐、房间快照、播放劫持全部归零）。它幂等，与成员会话
     * {@code close()} 内部那次重复无害；而房主路径只有
     * {@code detachQuietly()}（刻意不清状态），没有这里这一下就一次都轮不到。</p>
     */
    public static void close() {
        DirectServer s = server;
        DirectClient l = loopback;
        server = null;
        loopback = null;
        hostAuth = "";
        // 先复位 active 再关会话：DirectClient.close() 里的 MpPartyClient.onDisconnect()
        // 带直连守卫，active 不复位它就空转。
        active = false;
        // 代次推进：在途的 join 线程落地前看到代次变了会主动放弃（僵尸会话防线）。
        generation++;
        // 先断成员侧会话（若有），再关服务端：回环连接属于服务端，由 stop() 一并清。
        DirectClient c = client;
        client = null;
        if (c != null) c.close();
        if (l != null) l.detachQuietly();
        if (s != null) s.stop();
        if (s != null) MpParty.shutdown();
        // 引擎归还给等着的 LAN 桥（纯单人场景被直连接管过，同一世界还活着）。
        if (s != null) com.muoniumplayer.party.lan.LanMpParty.onDirectReleased();
        joinAuth = "";
        MpHostRouter r = MpHostRouter.instance();
        if (r != null) r.detachDirect();
        // 收尾清客户端状态：无条件——房主路径没有任何其它调用点。
        MpPartyClient.onDisconnect();
    }

    // ── MpPartyClient.fillProfile 的钩子 ────────────────────────────────────

    /** hello 帧要带的直连密码；不活跃时返回空串（不追加字段）。 */
    public static String helloAuth() {
        String a = joinAuth;
        if (!a.isEmpty()) return a;
        return hostAuth;
    }

    // ── 设置代理（UI 用；DirectRoomSettings 在 muonium-common，直连包只依赖它） ──

    /** 上一次用的监听端口；没存过给 {@code def}。 */
    public static int lastPortOr(int def) {
        try {
            int p = com.muoniumplayer.core.ncm.music.DirectRoomSettings.lastPort();
            return p > 0 ? p : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    // ── 会话断开回收（DirectClient 在连接终结时回调） ───────────────────────

    /**
     * 成员侧直连会话终结（主动 close 或被动断线都会走到这里）。
     *
     * <p>无论哪种终结，都要把 {@code client} / {@code active} 复位、路由换回 MC 通道、
     * 客户端状态清零——否则成员掉线后 {@link #isActive()} 永远为 true：
     * {@code MpPartyClient.onWorldJoin()} / {@code onDisconnect()} 被它挡住，
     * 回到 MC 服务器既不探测服务器房间、残留状态也不清理。</p>
     *
     * <p>幂等：主动 {@link #close()} 路径先清了字段再走 {@code DirectClient.close()}，
     * 回调到这里时全部为 null / false，直接返回。</p>
     *
     * @return 这条会话<b>当时确实是当前会话</b>才返回 true；被新会话顶掉的旧连接
     *         返回 false（调用方据此决定要不要清客户端状态——不清，新会话正在用）。
     */
    static boolean onSessionClosed(DirectClient c) {
        boolean wasMember = c != null && c == client;
        boolean wasLoop = c != null && c == loopback;
        if (!wasMember && !wasLoop) return false;

        // 会话终结是一次全量清理点，与 close() 同样推进代次（类注释声明的不变量）：
        // 托管回环被动死亡后的在途 join 线程落地时，代次已变就会主动放弃，
        // 不至于把一个没人管的僵尸会话注册成新会话。
        generation++;
        if (wasMember) client = null;
        if (wasLoop) loopback = null;
        joinAuth = "";
        MpHostRouter r = MpHostRouter.instance();
        if (r != null) r.detachDirect();

        // 回环连接没了 = 托管服务端也完了（引擎随房主进程的直连会话而活）。
        if (wasLoop) {
            DirectServer s = server;
            server = null;
            hostAuth = "";
            if (s != null) s.stop();
            MpParty.shutdown();
            // 引擎归还会给等着的 LAN 桥（同一世界还在跑时）。
            com.muoniumplayer.party.lan.LanMpParty.onDirectReleased();
        }
        if (client == null && loopback == null) active = false;
        // 清客户端状态：房间快照、播放、automix 全套归零。直连守卫此时已经放行
        // （active 已复位），这次 onDisconnect 会真正执行。
        MpPartyClient.onDisconnect();
        return true;
    }
}
