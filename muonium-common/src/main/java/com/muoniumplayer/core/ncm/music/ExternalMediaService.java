package com.muoniumplayer.core.ncm.music;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMTC 助手的进程管理：启动、轮询、命令下发、崩溃治理与状态上报。
 *
 * <p>三条硬规矩，都是这个项目里踩过的位置：</p>
 * <ul>
 *   <li><b>绝不在渲染线程碰进程与文件。</b>编译助手要跑 csc（秒级），启动进程要等
 *       {@code waitFor}，全部放在一条后台引导线程里做；界面只读 {@link #snapshot()}
 *       与 {@link #cachedState()} 这两个内存值。命令下发同理：主线程 tick 只往
 *       {@link #OUTBOX} 里塞一行字符串（{@link #sendLine(String)} 的全部成本是
 *       一次入队），真正的管道写发生在专职写线程 {@code Muonium-SMTC-Writer-*} 上。</li>
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

    // ── 管道写的防冻结三件套 ────────────────────────────────────────────────────
    //
    // 2.5.2 的事故形态：poll 线程持着 STDIN_LOCK 阻塞在 native write（子进程串行读
    // stdin、又被自己的 SMTC 调用拖住不读），渲染线程 tick 里 sendPublishAppId 等
    // 那把锁 —— 管道写满 = 画面永久冻结，音乐照放。三层解法（缺一层都会回到事故）：
    //   1. 主线程只入队（sendLine），写线程独占管道；
    //   2. OUTBOX 有界 + 按前缀丢弃：SMTC 是展示信息，丢帧不丢命；
    //   3. WRITER_STALL_MS 看门狗：写线程卡死 = 助手卡死，杀掉走既有重启/降级链。

    /** 命令队列上限。超了丢最旧：宁可 SMTC 信息慢半拍，也不让队列长到内存出事。 */
    private static final int OUTBOX_CAPACITY = 128;
    /** 写线程一次 native write 允许的最大时长；超时视为管道已死，杀助手走重启。 */
    private static final long WRITER_STALL_MS = 10_000L;
    /** 「pub 系」命令的合并前缀：队列满时丢最旧一条同类，新命令顶上（状态是最新即正确）。 */
    private static final String[] MERGE_PREFIXES = {
            "pub meta ", "pub state ", "pub tl ", "pub art ", "vol ", "use ", "list"};

    private static final Object LOCK = new Object();
    private static final Object STDIN_LOCK = new Object();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    /** 待写命令。写线程持 STDIN_LOCK 消费，其他线程只调用 sendLine 入队。 */
    private static final ArrayDeque<String> OUTBOX = new ArrayDeque<String>(OUTBOX_CAPACITY);
    private static Thread writerThread;
    /** 当前进程世代：每次 spawn 递增。写线程拿它识别「自己的进程」，换代即自停；发布器也拿它检测换代。 */
    private static volatile int generation;
    /** 写线程最近一次 native write 的起点；0 = 空闲。写线程自己更新，看门狗读。 */
    private static volatile long writerBusySinceMs;
    /** 写线程记录的最近一次管道异常；非 null 时入队侧直接丢命令，直到进程换代。 */
    private static volatile boolean writerBroken;

    private static Process process;
    private static BufferedWriter stdin;
    private static Thread bootThread;
    private static Thread readerThread;
    private static Thread pollThread;
    /** 用户是否要求镜像开着。与 {@code process != null} 区分开：崩溃后仍是"要求开着"。 */
    private static boolean desired;
    /**
     * 发布模式（把本模组自己的播放写成一条系统媒体会话）是否需要助手活着。
     * 镜像与发布共用同一个助手进程：两个愿望只要有一个在，进程就保持运行。
     */
    private static boolean publishDesired;
    private static int restartAttempts;
    private static volatile ExternalSnapshot latest = ExternalSnapshot.NONE;
    private static volatile boolean helperReady;
    private static volatile String helperFailure = "";
    private static volatile boolean probeFailed;
    /** 助手自报的协议版本；1 = 只读的老助手（无发布、无多会话挑选），2 = 完整版。 */
    private static volatile int helperVersion = 1;
    /** 最近一次命令结果，供界面给出「外部播放器不接受这条命令」的反馈；取一次清一次。 */
    private static volatile String pendingCommandResult;

    private ExternalMediaService() {
    }

    /** 看门狗随类加载起一次、终生跑：它是纯后台守护，不持任何进程引用。 */
    static {
        Thread watchdog = new Thread(ExternalMediaService::watchdogLoop,
                "Muonium-SMTC-Watchdog-" + THREAD_SEQUENCE.incrementAndGet());
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * 要求启动助手（镜像侧）。立刻返回，真正的探测/编译/起进程都在后台引导线程里做。
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

    /**
     * 发布侧对助手的愿望。镜像与发布共用同一进程：谁后到谁补一次引导，
     * 两个愿望都撤掉时进程才允许被收走。
     */
    public static void setPublishDesired(boolean value) {
        synchronized (LOCK) {
            if (publishDesired == value) return;
            publishDesired = value;
            if (value && process == null && !desired) {
                restartAttempts = 0;
                probeFailed = false;
                if (helperReady) {
                    spawnLocked();
                    return;
                }
                bootThread = new Thread(() -> boot("发布模式"),
                        "Muonium-SMTC-Boot-" + THREAD_SEQUENCE.incrementAndGet());
                bootThread.setDaemon(true);
                bootThread.start();
            }
        }
        if (!value) {
            // 两个愿望都没了才真的收进程；镜像还开着时这里什么都不做。
            if (!isMirrorDesired()) stop();
        }
    }

    /** 镜像侧是否还要求助手活着。只给 {@link #setPublishDesired(boolean)} 的收尾用。 */
    private static boolean isMirrorDesired() {
        synchronized (LOCK) {
            return desired;
        }
    }

    private static void boot(String reasonForUser) {
        SmtcTooling.Result result = SmtcTooling.ensure();
        synchronized (LOCK) {
            if (!desired && !publishDesired) return;
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
            final BufferedWriter startedStream = new BufferedWriter(new OutputStreamWriter(
                    started.getOutputStream(), StandardCharsets.UTF_8));
            stdin = startedStream;
            // 世代号先递增：写线程靠它认「自己的进程」，换代会自停。
            generation++;
            writerBroken = false;
            writerBusySinceMs = 0L;
            resetWriterStateLocked();
            final int bornGeneration = generation;
            writerThread = new Thread(() -> writeLoop(started, startedStream, bornGeneration),
                    "Muonium-SMTC-Writer-" + THREAD_SEQUENCE.incrementAndGet());
            writerThread.setDaemon(true);
            writerThread.start();
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
        if (line.contains("\"event\":\"pubcmd\"")) {
            // 发布端媒体键/浮窗拖进度：转交发布器，主线程 tick 里才落进 CloudMusic。
            SmtcPublisher.onHelperCommand(line);
            return;
        }
        if (line.contains("\"event\":\"cmd\"")) {
            pendingCommandResult = line;
            return;
        }
        if (line.contains("\"event\":\"ready\"")) {
            restartAttempts = 0;
            helperVersion = protocolVersion(line);
            System.out.println("[Music/SMTC] 助手协议 v" + helperVersion);
            return;
        }
        if (line.contains("\"event\":\"pub\"")) {
            // 发布端只报失败；这里记日志，already_running 转告发布器给用户一句人话。
            if (line.contains("\"ok\":false")) {
                System.err.println("[Music/SMTC] 发布端: " + line);
                if (line.contains("\"error\":\"already_running\"")) {
                    SmtcPublisher.onPublishDenied();
                }
            }
        }
    }

    /** 从 ready 行里抠出协议版本；老格式（没有 version）按 1 算。 */
    private static int protocolVersion(String readyLine) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(readyLine)
                    .getAsJsonObject();
            if (root.has("version")) return Math.max(1, root.get("version").getAsInt());
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static void onProcessEnded(Process ended) {
        synchronized (LOCK) {
            if (process != ended) return;   // 被更新的一次启动顶掉了，不处理
            process = null;
            stdin = null;
            generation++;   // 写线程看见换代即自停（旧管道已随进程消失）
            writerBroken = true;
            resetWriterStateLocked();
            latest = ExternalSnapshot.NONE;
            // 镜像与发布共用这条命：两个愿望都撤了才让它安静地走。
            if (!desired && !publishDesired) return;
            if (++restartAttempts > MAX_RESTARTS) {
                probeFailed = true;
                helperFailure = "系统媒体助手反复退出，已停止自动重启（共 " + MAX_RESTARTS + " 次）";
                desired = false;
                publishDesired = false;
                System.err.println("[Music/SMTC] " + helperFailure);
                return;
            }
        }
        sleep(RESTART_DELAY_MS);
        synchronized (LOCK) {
            if ((desired || publishDesired) && process == null) spawnLocked();
        }
    }

    private static boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /**
     * 把一条命令交给写线程。任何线程可调，含渲染线程——<b>这里绝不碰管道</b>。
     *
     * <p>队列满时的策略是「按前缀丢旧」：新来的 {@code pub tl}（或 {@code vol} 等）
     * 会先顶掉队里最旧的同前缀命令，SMTC 展示信息的语义本来就是「最新即正确」；
     * 整个队列都换不出空间（极端：满队全是命令回执类）才丢新命令本身。管道已断
     * （{@link #writerBroken}）时直接丢弃——助手换代前的命令对着死管道写没有意义。</p>
     */
    private static void sendLine(String line) {
        if (writerBroken) return;
        synchronized (STDIN_LOCK) {
            if (writerBroken || stdin == null) return;
            if (OUTBOX.size() >= OUTBOX_CAPACITY) dropOldestFor(line);
            OUTBOX.addLast(line);
            STDIN_LOCK.notifyAll();
        }
    }

    /** 唤醒可能还在等旧管道的写线程并清空遗留命令。不要求调用方持任何锁。 */
    private static void resetWriterStateLocked() {
        synchronized (STDIN_LOCK) {
            OUTBOX.clear();
            STDIN_LOCK.notifyAll();
        }
    }

    /** 队列满时腾一格：丢最旧的同前缀命令；没有同前缀才丢队首（poll 心跳是最可丢的）。 */
    private static void dropOldestFor(String incoming) {
        for (int i = 0; i < MERGE_PREFIXES.length; i++) {
            if (!startsWith(incoming, MERGE_PREFIXES[i])) continue;
            java.util.Iterator<String> it = OUTBOX.iterator();
            while (it.hasNext()) {
                if (startsWith(it.next(), MERGE_PREFIXES[i])) {
                    it.remove();
                    return;
                }
            }
            return;   // 同前缀一条都没有：只丢队首
        }
        OUTBOX.pollFirst();
    }

    private static boolean startsWith(String value, String prefix) {
        return value.length() >= prefix.length()
                && value.regionMatches(0, prefix, 0, prefix.length());
    }

    /**
     * 写线程主体：唯一允许做管道 IO 的地方。从 OUTBOX 取命令写自己那一代的流。
     *
     * <p>醒来的顺序是「先排空队列、再查世代」：stop() 会把 exit 插进队列后才换代，
     * 若先查世代，exit 就永远发不出去（stop 只能靠 destroyForcibly 强杀收尾）。
     * 换代后队列里理论上只剩 exit（spawn 前也会清队列）；极端竞争下混进新世代的
     * 命令也只是写进一条已死的流——write 抛异常，退休，无副作用。</p>
     *
     * <p>native write 卡住由看门狗（{@code watchdogLoop}）处理：这里只管写，
     * 卡死的表现是 {@link #writerBusySinceMs} 长时间非零，看门狗杀进程后
     * 管道断裂让本线程的 write 抛异常走退休路径。</p>
     */
    private static void writeLoop(Process own, BufferedWriter ownStream, int ownGeneration) {
        while (true) {
            String line;
            synchronized (STDIN_LOCK) {
                while (OUTBOX.isEmpty() && generation == ownGeneration) {
                    try {
                        STDIN_LOCK.wait();
                    } catch (InterruptedException interrupted) {
                        closeQuietly(ownStream);
                        return;
                    }
                }
                if (OUTBOX.isEmpty()) {   // 换代且无遗留：退休
                    closeQuietly(ownStream);
                    return;
                }
                line = OUTBOX.pollFirst();
            }
            try {
                writerBusySinceMs = System.currentTimeMillis();
                // 锁外写：write/flush 卡死时绝不能占着 STDIN_LOCK——入队侧还在用它。
                // ownStream 只属于本线程（换代才换流），没有并发写者。
                ownStream.write(line);
                ownStream.newLine();
                ownStream.flush();
            } catch (Throwable failure) {
                writerBroken = true;
                synchronized (STDIN_LOCK) {
                    STDIN_LOCK.notifyAll();
                }
                // 管道断多半是进程在退：交给 readLoop/onProcessEnded 收尾，不重复刷日志；
                // 只有进程还活着却写不进（不该发生）才留一条现场。
                if (own.isAlive()) {
                    System.err.println("[Music/SMTC] 写助手管道失败: " + failure);
                }
                closeQuietly(ownStream);
                return;
            } finally {
                writerBusySinceMs = 0L;
            }
            if (generation != ownGeneration) {   // 写完这批（含 stop 的 exit）就退休
                closeQuietly(ownStream);
                return;
            }
        }
    }

    /**
     * 看门狗：写线程一次 write 超过 {@link #WRITER_STALL_MS} 没回来，判管道已死，
     * 杀掉助手进程。杀掉后 readLoop 的 readLine 抛/返回 null，走既有
     * {@link #onProcessEnded} 重启治理（上限 3 次、超限降级），这条链早已存在，
     * 这里只是给它补一个「进程卡死」的触发器。
     */
    private static void watchdogLoop() {
        while (true) {
            sleep(1_000L);
            long busySince = writerBusySinceMs;
            if (busySince != 0L && System.currentTimeMillis() - busySince > WRITER_STALL_MS) {
                System.err.println("[Music/SMTC] 助手管道写入超时（"
                        + WRITER_STALL_MS + "ms），判定助手卡死，强制结束并重启");
                writerBroken = true;
                synchronized (STDIN_LOCK) {
                    STDIN_LOCK.notifyAll();
                }
                Process stuck = process;
                if (stuck != null) {
                    try {
                        stuck.destroyForcibly();
                    } catch (Throwable ignored) {
                        // 杀不掉也没关系：readLoop 迟早会因进程退出收尾。
                    }
                }
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

    // ── 发布通道（把本模组自己的播放写进系统媒体会话）─────────────────────────
    //
    // 与镜像命令共用同一条写线程管道；助手侧 stdin 读线程只收行入队，处理在
    // 工作线程上做（见 SmtcWatcher.cs 的 ProcessLoop），发布命令与 poll 之间
    // 不会再互相拖住。

    /**
     * 当前助手世代号。发布器拿它检测「助手换代」（崩溃重启/看门狗强杀）：
     * 换代后整套重推身份+元数据，不依赖增量推送的连续性。
     */
    public static int helperGeneration() {
        return generation;
    }

    /** 助手协议版本。1 = 老助手（发布与多会话挑选都不可用）。 */
    public static int helperProtocolVersion() {
        return helperVersion;
    }

    /**
     * 声明发布会话的身份（AUMID + 浮窗显示名 + 图标路径），必须在 {@link #sendPublishOn()}
     * 之前发：会话的 AUMID 在创建那一刻定型。AUMID 同时是镜像端「不镜像自己」的排除键，
     * 也是浮窗显示「MuoniumPlayer」而不是「未知应用」的注册表键来源。
     */
    public static void sendPublishAppId(String aumid, String display, String iconPath) {
        sendLine("pub appid {\"aumid\":" + jsonEscape(aumid)
                + ",\"display\":" + jsonEscape(display == null ? "" : display)
                + ",\"icon\":" + jsonEscape(iconPath == null ? "" : iconPath) + "}");
    }

    /** 打开发布会话。 */
    public static void sendPublishOn() {
        sendLine("pub on");
    }

    /** 收起发布会话（从系统浮窗里消失，会话本体保留以便下次秒开）。 */
    public static void sendPublishOff() {
        sendLine("pub off");
    }

    /** 推送曲目元数据。{@code json} 是 {@code {"title":..,"artist":..,"album":..,"albumArtist":..}} 形状。 */
    public static void sendPublishMeta(String json) {
        sendLine("pub meta " + json);
    }

    /** 推送播放状态：Playing / Paused / Stopped。 */
    public static void sendPublishState(String state) {
        sendLine("pub state " + state);
    }

    /** 推送时间轴（毫秒）。播放中建议每秒一次；助手侧只是更新状态仓，成本可忽略。 */
    public static void sendPublishTimeline(long positionMs, long durationMs) {
        sendLine("pub tl " + positionMs + " " + durationMs);
    }

    /** 推送封面文件路径（换歌时一次）。空串表示这一首没有封面。 */
    public static void sendPublishArt(String absolutePath) {
        sendLine("pub art " + jsonEscape(absolutePath == null ? "" : absolutePath));
    }

    /** 钉住镜像哪支会话（按 AUMID 完整匹配）；空串或 "-" 回到自动挑选。 */
    public static void sendUseSession(String aumid) {
        sendLine("use " + (aumid == null || aumid.trim().isEmpty() ? "-" : aumid.trim()));
    }

    /** 要一份系统里全部会话的快照（诊断用；结果进日志，由助手回 list 事件）。 */
    public static void sendListSessions() {
        sendLine("list");
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
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

    /**
     * 关闭镜像模式或游戏退出。幂等。
     *
     * <p>发布还要求着助手时（{@code publishDesired}）进程不收：镜像关了，
     * 媒体键还能控内部播放。两个愿望都撤了才真的退出。</p>
     *
     * <p>不再在调用线程上碰管道（2.5.2 事故的关屏变体）：exit 命令入队后由写线程
     * 发送。见 {@link #stop()} 的说明。</p>
     */
    public static void stop() {
        Process target;
        synchronized (LOCK) {
            desired = false;
            if (publishDesired) return;   // 愿望还在，进程留着
            target = process;
            if (target == null) return;
            process = null;
            stdin = null;
            latest = ExternalSnapshot.NONE;
        }
        synchronized (STDIN_LOCK) {
            OUTBOX.clear();
            // exit 插在最前：写线程下一次醒来先发 exit 再看见换代，然后退休关流。
            OUTBOX.addFirst("exit");
            STDIN_LOCK.notifyAll();
            generation++;   // 换代：写线程写完 exit 即退休，新命令不再被入队
            writerBroken = true;
        }
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
