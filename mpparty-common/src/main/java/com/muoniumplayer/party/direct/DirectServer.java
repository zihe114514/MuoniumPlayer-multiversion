package com.muoniumplayer.party.direct;

import com.muoniumplayer.party.MpProto;
import yun.shan.mpsync.MpParty;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 直连房间的托管侧：一个 {@link ServerSocket} + 会话表 + 引擎驱动线程。
 *
 * <p>帧格式与 MC 通道完全一致（varint 长度 + UTF-8 JSON，{@link MpProto} 编解码），
 * 所以成员侧的 {@code MpPartyClient} 不知道自己在跟 socket 说话；引擎（
 * {@code yun.shan.mpsync.MpParty}）经 {@link DirectMpPlatform} 挂进来，与局域网
 * 模式同构。三方对话：</p>
 *
 * <ol>
 *   <li>成员连入 → 首帧必须是带正确 {@code auth} 的 hello，密码在<b>传输层</b>校验
 *       （帧进引擎前），不对就回错误帧并断开；</li>
 *   <li>握手通过 → 注册会话（同 UUID 的旧连接顶掉）→ {@code onPlayerJoin} 复座/入座
 *       → 之后该连接上的每一帧原样转 {@code MpParty.onPacket}（引擎内部自己跳线程）；</li>
 *   <li>连接断开 → {@code onPlayerQuit}，房间席位保留等重连（引擎现有机制）。</li>
 * </ol>
 *
 * <p>线程模型：一个单线程 executor 既跑 50ms 一次的 {@code MpParty.tick()}，也承接
 * {@code platform.execute}（引擎假设 handle 与 tick 同线程）；每连接一个 reader 线程
 * 只负责读，不碰引擎状态。写方向经 {@code DirectMpPlatform.sendRaw} 由每连接一把锁
 * 串行化。</p>
 */
public final class DirectServer {

    /** 一条已握手的成员连接。 */
    private static final class Conn implements DirectMpPlatform.Session {
        final Socket socket;
        final UUID uuid;
        final String name;
        final OutputStream out;
        volatile boolean open = true;
        /** 上一次收到该成员任何一帧的时刻；服务端用它摘除半开的幽灵席位。 */
        volatile long lastFrameNanos = System.nanoTime();

        Conn(Socket socket, UUID uuid, String name, OutputStream out) {
            this.socket = socket;
            this.uuid = uuid;
            this.name = name;
            this.out = out;
        }

        @Override
        public UUID uuid() {
            return uuid;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void send(byte[] payload) {
            // 可能从引擎线程之外被调到：一把锁串行化，防止两帧交叠成乱码。
            synchronized (this) {
                if (!open) return;
                try {
                    out.write(payload);
                    out.flush();
                    // 注意：这里<b>不能</b>刷新 lastFrameNanos。半开连接（成员拔网线）
                    // 的 write 在 TCP 缓冲填满前几乎总是「成功」，而服务端每 15 秒
                    // 发一帧 ping——写成功就续期的话，过期清扫（见 tickLoop 的
                    // STALE_TIMEOUT_NANOS）永远不触发，幽灵席位要等 TCP 重传失败
                    // （可达十几分钟）才被摘。活性只认<b>收到</b>的帧（serveLoop）。
                } catch (Throwable ignored) {
                    // 写失败 = 连接已死（半开：成员掉线无 FIN/RST，reader 还阻塞在
                    // readFrame 上）。必须当场 close socket——它会立刻唤醒 reader 走
                    // finally 清理（unregister + onPlayerQuit），否则幽灵成员永远挂在
                    // 成员列表里，url_needed 每层都白等它的窗口，房间也永不回收。
                    close();
                }
            }
        }

        /** 引擎线程问：这个连接多久没有给出任何活性证据了（收帧或发帧都算）。 */
        boolean staleFor(long nanos) {
            return System.nanoTime() - lastFrameNanos > nanos;
        }

        @Override
        public void close() {
            open = false;
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 同一地址连续握手失败几次后冷却多久（防密码爆破）。 */
    private static final int AUTH_FAIL_LIMIT = 3;
    private static final long AUTH_FAIL_COOLDOWN_MS = 30_000L;
    /** 握手读首帧的等待上限。 */
    private static final int HANDSHAKE_TIMEOUT_MS = 8_000;
    /** 保活 ping 的间隔（纳秒）。 */
    private static final long PING_INTERVAL_NANOS = 15_000_000_000L;
    /** 连接被判定半开的读/写静默上限（纳秒）。三个 ping 周期足够穿过一次网络抖动。 */
    private static final long STALE_TIMEOUT_NANOS = 90_000_000_000L;

    private final int port;
    private final String auth;
    private final DirectMpPlatform platform;
    /** 引擎线程：既是 tick 的节拍器，也是 platform.execute 的执行器（见 tickLoop）。 */
    private Thread engineThread;
    /** 所有引擎侧任务（帧处理、join/quit、platform.execute）都进这条队列，由 tickLoop 清空。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> tasks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private final AtomicLong tickCount = new AtomicLong();

    public DirectServer(int port, String auth,
                        java.util.function.Consumer<String> localChat) {
        this.port = port;
        this.auth = auth == null ? "" : auth;
        this.platform = new DirectMpPlatform(this::onEngineTask, localChat);
    }

    public DirectMpPlatform platform() {
        return platform;
    }

    public boolean isRunning() {
        return running;
    }

    /** 邀请串里用的密码（开房时生成的那一份）。 */
    public String authForInvite() {
        return auth;
    }

    /** 实际监听端口（与构造参数一致）。 */
    public int listeningPort() {
        return port;
    }

    /** 启动监听并开始引擎 tick。端口被占时抛 {@link BindException}。 */
    public void start() throws IOException {
        // 一律监听全部网卡：同一局域网的好友用内网 IP 直连，公网好友经 frp
        // （本机回连 127.0.0.1）进来——两条路都通，走哪条由邀请串里的地址决定。
        // 安全面由房间密码（握手必查）+ 3 次失败冷却兜住。
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", port);
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(addr, 16);
        } catch (IOException e) {
            try {
                ss.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
        serverSocket = ss;
        running = true;
        engineThread = new Thread(this::tickLoop, "muonium-direct-engine");
        engineThread.setDaemon(true);
        engineThread.start();
        acceptThread = new Thread(this::acceptLoop, "muonium-direct-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** 关房：断开所有成员、停引擎、关监听。幂等。 */
    public void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (Throwable ignored) {
            }
        }
        for (DirectMpPlatform.Session s : platform.snapshot()) {
            ((Conn) s).close();
        }
        if (engineThread != null) engineThread.interrupt();
    }

    private void onEngineTask(Runnable task) {
        if (!running) return;
        tasks.offer(task);
    }

    /**
     * 引擎线程的主循环：50ms 一次引擎 tick，间隙里清空 {@code tasks} 队列。
     *
     * <p>{@code platform.execute} 不直接往 executor 提交（那会排在永不返回的本循环后面），
     * 而是入 {@link #tasks}，由这里统一跑——这正是「handle 与 tick 同线程」的实现方式。</p>
     */
    private void tickLoop() {
        long last = System.nanoTime();
        long lastPingNanos = System.nanoTime();
        while (running && !Thread.currentThread().isInterrupted()) {
            // 先把攒下的任务清完（帧处理、join/quit），再问一次时钟。
            Runnable task;
            while (running && (task = tasks.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    platform.warn("[muonium-direct] engine task failed", t);
                }
            }
            if (!running) return;
            long now = System.nanoTime();
            if ((now - last) / 1_000_000L >= 50L) {
                last = now;
                try {
                    MpParty.tick();
                    tickCount.incrementAndGet();
                } catch (Throwable t) {
                    platform.warn("[muonium-direct] engine tick failed", t);
                }
            }
            // 保活：每 15 秒给所有成员发一帧 ping。两个作用——
            // ① 成员把任何下行帧当「房主还活着」，空闲期也能感知房主死亡；
            // ② 客户端回 pong（或任何帧）会刷新 lastFrameNanos，让下面的
            //    过期清扫能摘掉「拔网线无 FIN/RST」的半开幽灵席位。
            // 老客户端不认识 ping（dispatch 静默忽略未知 op），但它们的 C_PROGRESS
            // 心跳每 5 秒一帧，本来就在刷新活性，清扫不受影响。
            if (now - lastPingNanos >= PING_INTERVAL_NANOS) {
                lastPingNanos = now;
                byte[] ping = MpProto.encode(MpProto.msg(MpProto.S_PING));
                for (DirectMpPlatform.Session s : platform.snapshot()) {
                    if (s instanceof Conn) ((Conn) s).send(ping);
                }
            }
            // 读/写都超过窗口没动静的连接按半开处理：close 会唤醒阻塞在 readFrame
            // 上的 reader 走 finally 清理（unregister + onPlayerQuit）。
            for (DirectMpPlatform.Session s : platform.snapshot()) {
                if (s instanceof Conn && ((Conn) s).staleFor(STALE_TIMEOUT_NANOS)) {
                    // warn 只收 message+Throwable：把成员名并进消息。
                    platform.warn("[muonium-direct] member " + ((Conn) s).name
                            + " 90s 无任何帧，按断线清理", null);
                    ((Conn) s).close();
                }
            }
            try {
                // 托管期间这个循环常驻：2ms 忙轮询每秒醒 500 次；50ms 的 tick 粒度下
                // 睡 5ms 完全够用，把空转的唤醒频率砍掉 60%。
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket ss = serverSocket;
            if (ss == null) return;
            Socket socket;
            try {
                socket = ss.accept();
            } catch (SocketException e) {
                return; // 监听已关
            } catch (IOException e) {
                continue;
            }
            Thread reader = new Thread(() -> serve(socket), "muonium-direct-conn");
            reader.setDaemon(true);
            reader.start();
        }
    }

    /** 一条新连接的完整生命周期：握手 → 转发帧 → 断开清理。 */
    private void serve(Socket socket) {
        UUID uuid = null;
        Conn conn = null;
        try {
            socket.setTcpNoDelay(true);
            // keepAlive 兜底半开连接：成员拔网线/休眠后，TCP 探测终会让 reader 的
            // read 抛错退出（而不是永久阻塞），走 finally 清理会话。
            try {
                socket.setKeepAlive(true);
            } catch (Throwable ignored) {
            }
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            // 首帧必须是带正确密码的 hello。密码校验在传输层：帧根本不进引擎。
            byte[] frame = readFrame(in);
            com.google.gson.JsonObject hello = frame == null ? null : MpProto.decode(frame);
            String op = hello == null ? "" : MpProto.str(hello, MpProto.OP, "");
            if (!MpProto.C_HELLO.equals(op)) {
                reject(out, "直连房间只收一起听客户端");
                return;
            }
            String given = MpProto.str(hello, "auth", "");
            if (!constantTimeEquals(given, auth)) {
                reject(out, "密码不对");
                noteAuthFail(socket);
                return;
            }
            if (!consumeAuthFailCredit(socket)) {
                reject(out, "失败次数过多，稍后再试");
                return;
            }
            uuid = sessionUuid(hello);
            if (uuid == null) {
                reject(out, "握手帧缺少身份");
                return;
            }
            String name = MpProto.str(hello, "playerName", "").trim();
            if (name.isEmpty()) name = "玩家" + Short.toUnsignedInt((short) uuid.hashCode()) % 10000;

            socket.setSoTimeout(0);
            conn = new Conn(socket, uuid, name, out);
            final byte[] handshake = frame;
            final UUID memberId = uuid;
            // 同 UUID 的旧连接顶掉：断线重连时旧 reader 可能还没感知断开。
            DirectMpPlatform.Session old = null;
            for (DirectMpPlatform.Session s : platform.snapshot()) {
                if (s.uuid().equals(memberId)) old = s;
            }
            if (old != null) ((Conn) old).close();
            platform.register(conn);
            tasks.offer(() -> {
                // 先处理握手帧（回 S_HELLO + 首批快照），再走断线重连续座——
                // 顺序反了的话，重连成员会先收到一份房间快照，客户端握手只认
                // S_HELLO，首帧对不上就报「对端不是直连房间」。
                MpParty.onPacket(memberId, handshake);
                MpParty.onPlayerJoin(memberId);
            });
            serveLoop(socket, in, conn, uuid);
        } catch (Throwable ignored) {
        } finally {
            // 按「本次连接对象」条件删除，不能按 UUID 无条件删——
            // 顶号场景下旧连接的 finally 若无条件 unregister，会把刚注册的
            // 新连接从会话表里踢掉（成员表现为「连着却聋了」）。
            // 只有自己的会话还在表里时才清理 + 通知引擎离席；已被顶掉就什么都不做。
            final Conn self = conn;
            if (uuid != null && self != null && platform.unregisterIfCurrent(uuid, self)) {
                final UUID id = uuid;
                tasks.offer(() -> {
                    try {
                        MpParty.onPlayerQuit(id);
                    } catch (Throwable ignored) {
                    }
                });
            }
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void serveLoop(Socket socket, InputStream in, Conn conn, UUID uuid) throws IOException {
        while (running && conn.open) {
            byte[] frame = readFrame(in);
            if (frame == null) return; // 连接关闭或帧非法
            // 任何来帧都是对端活着的证据——半开清扫以它为准。
            conn.lastFrameNanos = System.nanoTime();
            // 引擎线程在跑 tickLoop；onPacket 内部自己经 platform.execute 回引擎线程，
            // 所以这里入队即可，不直接碰引擎状态。
            tasks.offer(() -> {
                try {
                    MpParty.onPacket(uuid, frame);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    // ── 帧 I/O（与 MpProto 的 varint 分帧一致） ─────────────────────────────

    /** 读一帧；连接关闭返回 null，超长/坏帧返回 null 并由调用方断开。 */
    private static byte[] readFrame(InputStream in) throws IOException {
        int len = 0, shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) return null;
        }
        if (len <= 0 || len > MpProto.MAX_FRAME) return null;
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        return out;
    }

    private static void reject(OutputStream out, String reason) {
        try {
            com.google.gson.JsonObject m = MpProto.msg(MpProto.S_ERROR);
            m.addProperty("message", reason);
            out.write(MpProto.encode(m));
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    // ── 防爆破记分（按远端地址） ─────────────────────────────────────────────

    /** 失败计数 + 最后一次失败的墙钟毫秒：冷却判断需要后者。 */
    private static final class AuthFail {
        final AtomicInteger count = new AtomicInteger();
        volatile long lastFailMs;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, AuthFail> AUTH_FAILS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String remoteKey(Socket socket) {
        try {
            return socket.getInetAddress().getHostAddress();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void noteAuthFail(Socket socket) {
        // 顺手清一遍过期条目（冷却已过的地址没必要一直留着），防止被扫端口缓慢撑大。
        long now = System.currentTimeMillis();
        AUTH_FAILS.values().removeIf(f -> now - f.lastFailMs >= AUTH_FAIL_COOLDOWN_MS);
        AuthFail f = AUTH_FAILS.computeIfAbsent(remoteKey(socket), k -> new AuthFail());
        f.lastFailMs = now;
        f.count.incrementAndGet();
    }

    private static boolean consumeAuthFailCredit(Socket socket) {
        AuthFail f = AUTH_FAILS.get(remoteKey(socket));
        if (f == null) return true;
        // 满 3 次：只有距最后一次失败过了冷却窗才给新机会（清零放行）。
        // 之前这里拿「当前纪元毫秒 - 30_000 > 0」做判断——恒真，冷却从未生效。
        if (f.count.get() >= AUTH_FAIL_LIMIT
                && System.currentTimeMillis() - f.lastFailMs >= AUTH_FAIL_COOLDOWN_MS) {
            f.count.set(0);
            return true;
        }
        // 未满限就放行，但**不扣减**：扣减等于让成功登录抵消历史失败，
        // 攻击者可以「失败两次、成功一次」无限循环。放行不记账，满限才管。
        return f.count.get() < AUTH_FAIL_LIMIT;
    }

    // ── 身份 ────────────────────────────────────────────────────────────────

    /**
     * 会话 UUID：优先用握手帧自报的 {@code uuid}（成员的 MC UUID，
     * 引擎的成员列表/邀请/踢人都按它），没有或非法就按名字派生（老客户端）。
     */
    static UUID sessionUuid(com.google.gson.JsonObject hello) {
        String raw = MpProto.str(hello, "uuid", "").trim();
        if (!raw.isEmpty()) {
            try {
                return UUID.fromString(raw);
            } catch (Throwable ignored) {
            }
        }
        // 老客户端不带 uuid：按名字派生一个稳定 UUID（v3 名字空间）。
        String name = MpProto.str(hello, "playerName", "unknown").trim();
        return UUID.nameUUIDFromBytes(("muonium-direct:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
