package com.muoniumplayer.party.direct;

import com.muoniumplayer.party.MpHost;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpProto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 直连房间的成员侧：一条到房主的 socket，实现 {@link MpHost}。
 *
 * <p>活跃时由 {@link MpHostRouter} 换绑成 {@link MpPartyClient} 的当前传输：
 * 帧收发与服务器房间完全同一条链路（hello 探测、房间 UI、播放桥接一行不改）——
 * 客户端不关心 hello 是谁答的。断开后路由自动换回 MC 通道。</p>
 *
 * <p>握手：连上后发一帧带 {@code auth} 的 hello（MpPartyClient 的探测帧可能先到，
 * 这里的握手帧独立成帧，密码只在这帧里）；房主校验不过会回错误帧并断开。</p>
 */
public final class DirectClient implements MpHost {

    /** 连接结果。 */
    public static final class ConnectResult {
        public final boolean ok;
        public final String error;

        private ConnectResult(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        static ConnectResult success() {
            return new ConnectResult(true, null);
        }

        static ConnectResult fail(String error) {
            return new ConnectResult(false, error);
        }
    }

    /** 建连 + 握手的总预算。 */
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    /** 读空闲上限：房主 ping 间隔 15s 的三个周期。超过判定房主已死，主动断开重走路由。 */
    private static final long STALE_TIMEOUT_NANOS = 45_000_000_000L;

    private final String host;
    private final int port;
    private final String auth;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private Thread readerThread;
    /** 连接终结时回调 MpDirect 回收会话状态（null = 独立测试用，不回调）。 */
    private final boolean reportToFacade;

    public DirectClient(String host, int port, String auth) {
        this(host, port, auth, true);
    }

    DirectClient(String host, int port, String auth, boolean reportToFacade) {
        this.host = host;
        this.port = port;
        this.auth = auth == null ? "" : auth;
        this.reportToFacade = reportToFacade;
    }

    /** 建连并启动 reader。失败时返回错误说明，不改路由状态。 */
    public ConnectResult connect() {
        // 身份缺失（标题界面 / 加载中）时拒绝连接：所有从标题界面加入的客户端都会
        // 派生出同一个兜底身份，互相顶号；进了世界之后引擎座位还用旧身份，
        // self() 永远匹配不上，控制全灰。提前一步把路挡住，提示也说得明白。
        MpHostRouter pre = MpHostRouter.instance();
        if (pre != null && (pre.mcSelfUuid() == null || isBlank(pre.mcSelfName()))) {
            return ConnectResult.fail("请先进入一个世界再加入直连房间");
        }
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(CONNECT_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream o = new BufferedOutputStream(s.getOutputStream());
            this.socket = s;
            this.out = o;
            this.running = true;

            // 握手帧：带上本机身份（uuid/playerName）与直连密码。身份来自 MC 通道那一侧——
            // 房主回环自连时它就是引擎成员列表里的名字与 UUID。
            o.write(MpProto.encode(handshakeFrame()));
            o.flush();

            // 等房主的 hello 回帧。不能假设它是第一帧：重连续座时房间快照可能抢在前面，
            // 引擎 tick 的时间心跳也可能恰好插进来——所以读到 hello 为止，中间的帧先收着，
            // 握手成功后原样重放给客户端链路（相当于它们早到了一点而已）。
            // hello 本身也在重放之列：dispatch 靠它置 serverProto / helloAcked——吞掉它
            // 客户端就会永远以为「服务端是旧版协议 v0」，v2 的页签与功能全部被禁。
            final java.util.List<byte[]> preHello = new java.util.ArrayList<>();
            com.google.gson.JsonObject hello = null;
            long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                byte[] reply = readFrame(in);
                if (reply == null) {
                    running = false;
                    closeQuietly(s);
                    return ConnectResult.fail("连接被对端关闭");
                }
                com.google.gson.JsonObject msg = MpProto.decode(reply);
                String op = msg == null ? "" : MpProto.str(msg, MpProto.OP, "");
                if (MpProto.S_ERROR.equals(op)) {
                    running = false;
                    closeQuietly(s);
                    return ConnectResult.fail(MpProto.str(msg, "message", "连接被拒绝"));
                }
                preHello.add(reply);
                if (MpProto.S_HELLO.equals(op)) {
                    hello = msg;
                    break;
                }
            }
            if (hello == null) {
                running = false;
                closeQuietly(s);
                return ConnectResult.fail("对端没有回应握手（端口通着但不是直连房间）");
            }
            s.setSoTimeout(0);

            final java.util.List<byte[]> replay = preHello;
            readerThread = new Thread(() -> {
                // 握手窗口里提前到达的帧补投给客户端——顺序保持原样。
                for (byte[] f : replay) {
                    if (!running) return;
                    MpPartyClient.markDirectSource();
                    try {
                        MpPartyClient.onFrame(f);
                    } finally {
                        MpPartyClient.clearDirectSource();
                    }
                }
                readLoop(s, in);
            }, "muonium-direct-client");
            readerThread.setDaemon(true);
            readerThread.start();
            return ConnectResult.success();
        } catch (SocketTimeoutException e) {
            running = false;
            closeQuietly(s);
            return ConnectResult.fail("连接超时");
        } catch (IOException e) {
            running = false;
            closeQuietly(s);
            return ConnectResult.fail("连不上：" + e.getMessage());
        }
    }

    private com.google.gson.JsonObject handshakeFrame() {
        com.google.gson.JsonObject m = MpProto.msg(MpProto.C_HELLO);
        m.addProperty("auth", auth);
        // 身份取自 MC 通道侧（此时路由还没切到直连，问它拿到的就是游戏里的真实身份）。
        MpHostRouter r = MpHostRouter.instance();
        if (r != null) {
            java.util.UUID u = r.mcSelfUuid();
            if (u != null) m.addProperty("uuid", u.toString());
            String n = r.mcSelfName();
            if (n != null && !n.isBlank()) m.addProperty("playerName", n);
        }
        return m;
    }

    private void readLoop(Socket s, InputStream in) {
        boolean everSeenPing = false;
        try {
            while (running) {
                // 读空闲超时：房主挂了（进程被杀/休眠，无 FIN/RST）时 read 永久阻塞，
                // 界面永远显示「已连接」。房主每 15 秒发一帧 ping，把超时设在三个周期
                // 之后（45 秒），一次网络抖动不会误杀。
                // 兼容闸：老版本房主从不发 ping（也没有任何空闲期下行帧）。在见到第一帧
                // S_PING 之前不启用空闲超时（退回旧版「永久阻塞」行为，宁可僵也不误踢）；
                // 见到过 ping 的新会话，超时判死是可靠的。
                int soTimeout = everSeenPing ? (int) (STALE_TIMEOUT_NANOS / 1_000_000L) : 0;
                s.setSoTimeout(soTimeout);
                byte[] frame = readFrame(in);
                if (frame == null) break;
                if (!everSeenPing) {
                    everSeenPing = frameContainsPing(frame);
                    if (everSeenPing) System.out.println("[MpParty/direct] 房主支持保活探测，启用空闲超时判定");
                }
                // 房主下发的帧原样进客户端：与服务器房间完全同一条链路。
                // directSource 标记放行 MpPartyClient 的接收闸门（直连活跃时
                // MC 通道的帧被丢弃，直连这一路必须放行）。
                MpPartyClient.markDirectSource();
                try {
                    MpPartyClient.onFrame(frame);
                } finally {
                    MpPartyClient.clearDirectSource();
                }
            }
        } catch (java.net.SocketTimeoutException idle) {
            System.err.println("[MpParty/direct] 45s 没有收到房主任何帧，判定房主已离线");
        } catch (Throwable ignored) {
        } finally {
            close();
        }
    }

    /** 探一帧的 op 是不是 S_PING（轻量：只为启用空闲超时闸，避免每帧完整解码）。 */
    private static boolean frameContainsPing(byte[] frame) {
        try {
            com.google.gson.JsonObject msg = MpProto.decode(frame);
            return msg != null && MpProto.S_PING.equals(MpProto.str(msg, MpProto.OP, ""));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 关闭连接并把路由换回 MC 通道。幂等。 */
    public void close() {
        boolean was = running;
        running = false;
        Socket s = socket;
        socket = null;
        closeQuietly(s);
        MpHostRouter r = MpHostRouter.instance();
        // 仅当路由当前指向<b>这条连接</b>才摘：孤儿连接（竞速败者/被顶掉者）迟到的
        // close 不能把新会话的路由摘掉——否则客户端发送静默改走 MC 通道，
        // 直连房间表现为「能收帧但点歌全失灵」。
        if (r != null && r.currentDirect() == this) r.detachDirect();
        if (reportToFacade) {
            // 先问 MpDirect「这条连接还是不是当前会话」：
            // 是 → 复位 active 后清客户端状态（守卫放行，一次到位）；
            // 不是（被新会话顶掉的旧连接迟到 close）→ 什么都不清，
            // 否则迟到的 onDisconnect 会把刚建好的新会话整个抹掉。
            if (MpDirect.onSessionClosed(this) && was) MpPartyClient.onDisconnect();
        } else if (was) {
            MpPartyClient.onDisconnect();
        }
    }

    /** 只解除路由绑定，不清客户端状态（关房路径里由 MpDirect 统一清）。 */
    public void detachQuietly() {
        running = false;
        Socket s = socket;
        socket = null;
        closeQuietly(s);
        MpHostRouter r = MpHostRouter.instance();
        if (r != null) r.detachDirect();
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed();
    }

    // ── MpHost ──────────────────────────────────────────────────────────────

    @Override
    public boolean canSend() {
        return isConnected();
    }

    @Override
    public void send(byte[] frame) {
        OutputStream o = out;
        Socket s = socket;
        if (o == null || s == null || !running) return;
        try {
            synchronized (this) {
                o.write(frame);
                o.flush();
            }
        } catch (Throwable e) {
            close();
        }
    }

    @Override
    public java.util.UUID selfUuid() {
        // 返回 MC 侧的真实身份：引擎成员列表里「我」就是按这个 uuid 建座的，
        // MpPartyClient.self()/isOwner() 也靠它匹配。返回 null 会让房主被当成
        // 普通成员（控制全灰），这里不能省。
        MpHostRouter r = MpHostRouter.instance();
        return r == null ? null : r.mcSelfUuid();
    }

    @Override
    public void chat(String message) {
        // 路由会把它转给 MC 通道的 chat（直连房间的反馈常在聊天栏）。
    }

    // ── 帧 I/O（与 DirectServer 同款） ──────────────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

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
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        return buf;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }
}
