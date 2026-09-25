package com.muoniumplayer.core.ncm.music;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMTC 助手的进程管理：启动、轮询、命令下发、崩溃治理与状态上报。
 *
 * <p>三条硬规矩，都是这个项目里踩过的位置：</p>
 * <ul>
 *   <li><b>绝不在渲染线程碰进程与文件。</b>编译助手要跑 csc（秒级），启动进程要等
 *       {@code waitFor}，全部放在一条后台引导线程里做；界面只读 {@link #snapshot()}
 *       与 {@link #cachedState()} 这两个内存值。</li>
 *   <li><b>读不到就是读不到。</b>没有会话时快照里 {@code session=false} 一路传到界面，
 *       不做任何"看起来也能播"的替代时间轴。外部那套 LyricSync 为了测试方便在 SMTC
 *       不可用时退到内部模拟时间轴，玩家看到的是歌词在滚、系统其实根本没读到 ——
 *       这类假信号比没功能更坏。</li>
 *   <li><b>失败半径限制在本功能内。</b>助手进程崩了最多让镜像变成不可用，重试三次仍失败
 *       就不再自动重启，等用户在设置页手动点「重新探测」。不自动重启超过上限是因为
 *       若助手被安全软件一启动就杀，无限重启会变成一个静默吃 CPU 的循环。</li>
 * </ul>
 */
public final class ExternalMediaService {

    /**
     * 轮询间隔。
     *
     * <p>500ms 而不是外部那套的 100ms：进度平滑由 {@link CloudMusic} 里那套墙钟外推
     * （{@code smoothPositionMs}）负责，它本来就是为"音频时钟只在解码回调里前进、两次
     * 回调之间完全冻结"设计的。喂给它一个 500ms 一次的阶梯，输出的仍然是逐帧连续的
     * 位置。轮得更密只会让助手多跑，不会让歌词更准。</p>
     */
    private static final long POLL_INTERVAL_MS = 500L;
    /** 超过这个时间没收到新快照就视为助手失联；比两个轮询周期宽，容忍一次 GC 停顿。 */
    private static final long STALE_MS = 3_000L;
    private static final int MAX_RESTARTS = 3;
    private static final long RESTART_DELAY_MS = 2_000L;
    /** 等待进程自己退出时的上限，超过就强杀。游戏关闭路径上不能卡住。 */
    private static final long EXIT_WAIT_MS = 1_500L;

    private static final Object LOCK = new Object();
    private static final Object STDIN_LOCK = new Object();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private static Process process;
    private static BufferedWriter stdin;
    private static Thread bootThread;
    private static Thread readerThread;
    private static Thread pollThread;
    /** 用户是否要求镜像开着。与 {@code process != null} 区分开：崩溃后仍是"要求开着"。 */
    private static boolean desired;
    private static int restartAttempts;
    private static volatile ExternalSnapshot latest = ExternalSnapshot.NONE;
    private static volatile boolean helperReady;
    private static volatile String helperFailure = "";
    private static volatile boolean probeFailed;
    /** 最近一次命令结果，供界面给出「外部播放器不接受这条命令」的反馈；取一次清一次。 */
    private static volatile String pendingCommandResult;

    private ExternalMediaService() {
    }

    /**
     * 要求启动助手。立刻返回，真正的探测/编译/起进程都在后台引导线程里做。
     *
     * @param reasonForUser 触发来源，只进日志（"设置项切换""进世界""重新探测"）
     */
    public static void requestStart(String reasonForUser) {
        synchronized (LOCK) {
            if (desired) return;
            desired = true;
            restartAttempts = 0;
            probeFailed = false;
            if (helperReady) {
                spawnLocked();
                return;
            }
            bootThread = new Thread(() -> boot(reasonForUser),
                    "Muonium-SMTC-Boot-" + THREAD_SEQUENCE.incrementAndGet());
            bootThread.setDaemon(true);
            bootThread.start();
        }
    }

    private static void boot(String reasonForUser) {
        SmtcTooling.Result result = SmtcTooling.ensure();
        synchronized (LOCK) {
            if (!desired) return;
            if (result.isReady()) {
                helperReady = true;
                helperFailure = "";
                System.out.println("[Music/SMTC] 助手就绪（触发原因: " + reasonForUser + "）");
                spawnLocked();
            } else {
                helperReady = false;
                probeFailed = true;
                helperFailure = result.reason.isEmpty() ? result.availability.name() : result.reason;
                System.err.println("[Music/SMTC] 助手不可用: " + result.availability + " " + helperFailure);
            }
        }
    }

    /** 只在持有 {@link #LOCK} 时调用。 */
    private static void spawnLocked() {
        SmtcTooling.Result result = SmtcTooling.cachedState();
        if (!result.isReady()) {
            probeFailed = true;
            helperFailure = result.reason.isEmpty() ? result.availability.name() : result.reason;
            return;
        }
        File executable = result.executable;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath());
            builder.directory(executable.getParentFile());
            builder.redirectErrorStream(false);
            Process started = builder.start();
            process = started;
            stdin = new BufferedWriter(new OutputStreamWriter(
                    started.getOutputStream(), StandardCharsets.UTF_8));
            readerThread = new Thread(() -> readLoop(started),
                    "Muonium-SMTC-Reader-" + THREAD_SEQUENCE.incrementAndGet());
            readerThread.setDaemon(true);
            readerThread.start();
            pollThread = new Thread(ExternalMediaService::pollLoop,
                    "Muonium-SMTC-Poll-" + THREAD_SEQUENCE.incrementAndGet());
            pollThread.setDaemon(true);
            pollThread.start();
            System.out.println("[Music/SMTC] 助手已启动: " + executable.getName());
        } catch (Throwable failure) {
            probeFailed = true;
            helperFailure = "启动助手进程失败：" + failure.getClass().getSimpleName();
            System.err.println("[Music/SMTC] 启动助手失败: " + failure);
        }
    }

    private static void pollLoop() {
        while (isAlive()) {
            sendLine("poll");
            sleep(POLL_INTERVAL_MS);
        }
    }

    private static void readLoop(Process target) {
        BufferedReader reader = null;
        try {
            // 助手显式按 UTF-8 无 BOM 输出（曲名里一定有非 ASCII），这里也必须显式按
            // UTF-8 读：Windows 上默认编码是 GBK，用默认编码解会把曲名解成一串替换符。
            reader = new BufferedReader(new InputStreamReader(
                    target.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null && isAlive()) {
                handle(line.trim());
            }
        } catch (Throwable ignored) {
            // 进程被我们主动杀掉时这里必然抛，不是错误。
        } finally {
            closeQuietly(reader);
            onProcessEnded(target);
        }
    }

    private static void handle(String line) {
        if (line.isEmpty() || !line.startsWith("{")) return;
        long now = System.currentTimeMillis();
        if (line.contains("\"event\":\"state\"")) {
            ExternalSnapshot parsed = ExternalSnapshot.parseState(line, now);
            // 解失败时 parseState 给 NONE（session=false）。这比把一个明显不完整的行
            // 当成"没有会话"更好：宁可这一拍不更新，也不要让界面闪一下空。
            if (parsed.hasSession() || line.contains("\"session\":false")) latest = parsed;
            return;
        }
        if (line.contains("\"event\":\"cmd\"")) {
            pendingCommandResult = line;
            return;
        }
        if (line.contains("\"event\":\"ready\"")) {
            restartAttempts = 0;
        }
    }

    private static void onProcessEnded(Process ended) {
        synchronized (LOCK) {
            if (process != ended) return;   // 被更新的一次启动顶掉了，不处理
            process = null;
            stdin = null;
            latest = ExternalSnapshot.NONE;
            if (!desired) return;
            if (++restartAttempts > MAX_RESTARTS) {
                probeFailed = true;
                helperFailure = "系统媒体助手反复退出，已停止自动重启（共 " + MAX_RESTARTS + " 次）";
                desired = false;
                System.err.println("[Music/SMTC] " + helperFailure);
                return;
            }
        }
        sleep(RESTART_DELAY_MS);
        synchronized (LOCK) {
            if (desired && process == null) spawnLocked();
        }
    }

    private static boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    private static void sendLine(String line) {
        synchronized (STDIN_LOCK) {
            BufferedWriter writer = stdin;
            if (writer == null) return;
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (Throwable ignored) {
                // 进程正在退出时写失败是常态；退出由 readerLoop 负责收尾。
            }
        }
    }

    /**
     * 向外部播放器发一条传输命令。
     *
     * <p>异步：命令结果由助手回在 stdout，界面下一次读 {@link #drainCommandResult()} 时
     * 才拿得到。刻意不等返回值——一次 WinRT 往返最坏能到几百毫秒，而这条路径的调用方是
     * 玩家点击按钮那一刻，卡在那里比"没有反馈"更糟。</p>
     *
     * @param key play / pause / toggle / next / prev / stop
     */
    public static void sendCommand(String key) {
        sendLine("cmd " + key);
    }

    /** 请求外部播放器跳到某个位置；能力位不允许时调用方自己先判断（见 {@link ExternalSnapshot#canSeek()}）。 */
    public static void seekTo(long positionMs) {
        if (positionMs < 0L) positionMs = 0L;
        sendLine("seek " + positionMs);
    }

    /**
     * 把外部播放器那一格音量写进 Windows 音量合成器（0..1）。
     *
     * <p>这不是 SMTC 的能力：系统媒体会话上没有任何音量成员，能落到「某个程序的音量」的
     * 只有 Core Audio 会话，所以这条走的是助手里的另一套接口。助手只有在能把手上的
     * AUMID 与某条会话对上时才写；对不上就原样回 {@code volok:false}，绝不退回去动主音量。</p>
     *
     * <p>调用方是玩家拖滑块那一刻，不在每帧路径上：节流与合并由
     * {@link ExternalPlayback#onClientTick(long)} 负责。</p>
     */
    public static void sendVolume(float level) {
        float clamped = Math.max(0f, Math.min(1f, level));
        sendLine("vol " + String.format(java.util.Locale.ROOT, "%.4f", clamped));
    }

    /**
     * 取出并清空最近一次命令结果。
     *
     * @return 原始 JSON 行；没有新结果时返回 {@code null}
     */
    public static String drainCommandResult() {
        String value = pendingCommandResult;
        pendingCommandResult = null;
        return value;
    }

    /** 当前快照。永不为 null；没有会话时 {@link ExternalSnapshot#hasSession()} 为 false。 */
    public static ExternalSnapshot snapshot() {
        return latest;
    }

    /** 快照是否仍在有效期内（助手活着且确实在回话）。 */
    public static boolean isSnapshotFresh() {
        return latest.isFresh(System.currentTimeMillis(), STALE_MS);
    }

    /** 助手进程是否活着。 */
    public static boolean isRunning() {
        return isAlive();
    }

    /**
     * 只报当前状态，不触发任何探测。给设置页与 HUD 标签每帧调用。
     *
     * <p>标签必须把"为什么没有效果"说清楚：玩家看到「开了却没反应」时，要能自己分清是
     * 没装编译器、播放器不注册 SMTC，还是它压根没在播。</p>
     */
    public static String statusLabel() {
        if (!ExternalPlayback.supportsPlatform()) return "仅 Windows";
        SmtcTooling.Result tooling = SmtcTooling.cachedState();
        switch (tooling.availability) {
            case NOT_WINDOWS:
                return "仅 Windows";
            case UNKNOWN:
                return "未启用";
            case NO_COMPILER:
            case NO_METADATA:
            case NO_SOURCE:
            case COMPILE_FAILED:
                return "助手不可用 · " + tooling.reason;
            default:
                break;
        }
        if (probeFailed && !isAlive()) return "助手异常 · " + helperFailure;
        if (!isAlive()) return "助手未启动";
        if (latest.hasSession()) return "已接管 · " + appLabel(latest.app());
        if (!isSnapshotFresh()) return "助手无响应";
        return "系统里没有正在播放的媒体";
    }

    /**
     * 把 SMTC 的 {@code SourceAppUserModelId} 缩短成能放进一行标签的名字。
     *
     * <p>它长这样：{@code Chrome!Chrome}、{@code {6D809377-6AF0-444B-8957-A311F677BC3E}\\...网易云音乐...}。
     * 直接显示会吃掉整条状态行的宽度。</p>
     */
    static String appLabel(String rawApp) {
        if (rawApp == null || rawApp.trim().isEmpty()) return "外部播放器";
        String value = rawApp.trim();
        int brace = value.indexOf('}');
        if (brace >= 0 && brace + 1 < value.length()) value = value.substring(brace + 1);
        int slash = Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'));
        if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
        int bang = value.indexOf('!');
        if (bang > 0) value = value.substring(0, bang);
        // SMTC 给的常常是进程名：CloudMusic.exe 去掉后缀就是网易云客户端的目录名，读得通。
        int dot = value.lastIndexOf('.');
        if (dot > 0 && value.length() - dot <= 5) value = value.substring(0, dot);
        value = value.trim();
        if (value.isEmpty()) return "外部播放器";
        return value.length() > 18 ? value.substring(0, 18) + "…" : value;
    }

    /** 用户在设置页点「重新探测」：丢掉缓存的失败结论，再走一遍完整启动。 */
    public static void retry() {
        stop();
        synchronized (LOCK) {
            probeFailed = false;
            helperFailure = "";
            helperReady = false;
            restartAttempts = 0;
        }
        SmtcTooling.invalidateCache();
        requestStart("重新探测");
    }

    /** 关闭镜像模式或游戏退出。幂等。 */
    public static void stop() {
        Process target;
        BufferedWriter writer;
        synchronized (LOCK) {
            desired = false;
            target = process;
            writer = stdin;
            process = null;
            stdin = null;
            latest = ExternalSnapshot.NONE;
        }
        if (writer != null) {
            synchronized (STDIN_LOCK) {
                try {
                    writer.write("exit");
                    writer.newLine();
                    writer.flush();
                } catch (Throwable ignored) {
                }
                closeQuietly(writer);
            }
        }
        if (target == null) return;
        try {
            if (!target.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                target.destroy();
            }
        } catch (Throwable ignored) {
            target.destroyForcibly();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
