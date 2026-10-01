package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;
import top.fpsmaster.music.QrCode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 汽水扫码登录的外部取码器桥：拉起 {@code QishuiCookieTool.exe --bridge}，靠共享目录里
 * 的 {@code state.json} 换状态，拿到 cookie 之后交给 {@link QishuiMusicService#importCookie}。
 *
 * <p><b>为什么必须绕一道外部进程</b>：汽水 Passport 的取码与轮询要 {@code a_bogus} /
 * {@code msToken}，这俩只能由官方安全组件 {@code bdms.js} 在浏览器渲染进程里生成，Java 侧
 * 复现不了（Rhino 那条路 2026-09-17 已实测不可行）。所以模组只负责「拉起 + 读状态 + 收 cookie」，
 * 一行签名逻辑都不碰。</p>
 *
 * <p><b>为什么是共享目录而不是 HTTP</b>：JVM 默认的 {@code ProxySelector} 会把 {@code 127.0.0.1}
 * 也送进系统代理，报出来的 {@code Connection refused} 与「工具没起来」长得一模一样。共享目录
 * 没有端口、没有代理、没有防火墙，代价只是两次磁盘读。上一版 sidecar（纯 HTTP）就是被这类
 * 假象拖垮的，这里不再重复。</p>
 *
 * <p><b>不做静默回落</b>：工具不在就明说缺什么、该放哪，绝不退回「内置层自己发一条没有签名的
 * 请求」——那必然被上游拒绝，而且现象是「二维码能出来但永远扫不上」，比直接说缺工具难查得多。</p>
 *
 * <p>协议与工具侧实现同源，见工具仓库的 {@code bridge-main.js}。</p>
 */
public final class QishuiLoginBridge {

    /** 工具 exe 的文件名前缀，大小写不敏感。 */
    private static final String TOOL_PREFIX = "qishuicookietool";
    /** 首次拉起要自解压 88 MB 到 %TEMP%，慢机器上二三十秒，这里给足。 */
    private static final long START_TIMEOUT_MILLIS = 60000L;
    /** 复用已在跑的实例时给的时间：不用自解压，出码是秒级的，给足一轮取码的余量即可。 */
    private static final long REUSE_WAIT_MILLIS = 30000L;
    /** 超过这么久没刷新 state.json 就当工具已经没了（正常每 2 秒一次心跳）。 */
    private static final long STALE_MILLIS = 20000L;

    public enum Phase {
        STARTING, WAITING, SCANNED, MFA, CONFIRMED, EXPIRED, ERROR, STOPPED, IDLE, UNKNOWN
    }

    /** 一次 {@code state.json} 的快照。字段全部只读，读不到就是空串 / 0。 */
    public static final class State {
        public final Phase phase;
        public final String token;
        public final String scanUrl;
        public final String cookie;
        public final String message;
        public final int errorCode;
        public final long updatedAt;
        /** 工具进程号。用来认出「上一次会话留下的、我们没有句柄的」那个实例。 */
        public final long pid;

        State(Phase phase, String token, String scanUrl, String cookie, String message,
              int errorCode, long updatedAt, long pid) {
            this.phase = phase;
            this.token = token;
            this.scanUrl = scanUrl;
            this.cookie = cookie;
            this.message = message;
            this.errorCode = errorCode;
            this.updatedAt = updatedAt;
            this.pid = pid;
        }

        /** 工具还活着吗：状态新鲜、或者已经停在终态（终态不需要再刷新）。 */
        public boolean isAlive() {
            if (phase == Phase.CONFIRMED || phase == Phase.EXPIRED || phase == Phase.ERROR
                    || phase == Phase.STOPPED) {
                return true;
            }
            return System.currentTimeMillis() - updatedAt < STALE_MILLIS;
        }
    }

    private static volatile Process process;
    private static volatile String lastMessage = "";

    private QishuiLoginBridge() {
    }

    /** 共享目录：工具往里写 {@code state.json}，模组往里写 {@code command.json}。 */
    public static File bridgeDirectory() {
        File dir = ConfigPaths.QISHUI_BRIDGE_DIR;
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** 工具在不在：扫 {@link ConfigPaths#BIN_DIR} 及其一层子目录。 */
    public static boolean isAvailable() {
        return findTool() != null;
    }

    /** 给界面用的「该放哪」提示，指向一个具体路径，不写「请自行放置」这种空话。 */
    public static String requiredPath() {
        return new File(ConfigPaths.BIN_DIR, "QishuiCookieTool.exe").getPath();
    }

    private static File findTool() {
        File found = scanDirectory(ConfigPaths.BIN_DIR);
        if (found != null) return found;
        File[] children = ConfigPaths.BIN_DIR.listFiles(File::isDirectory);
        if (children == null) return null;
        for (File child : children) {
            found = scanDirectory(child);
            if (found != null) return found;
        }
        return null;
    }

    private static File scanDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith(TOOL_PREFIX) && name.endsWith(".exe")) return file;
        }
        return null;
    }

    /**
     * 拉起工具并等到第一张二维码到手。<b>必须在后台线程调用</b>（会阻塞到出码或超时）。
     *
     * @return 二维码；{@link QrCode#getQrContent()} 是可直接编码的扫码链接
     */
    public static synchronized QrCode start() throws IOException, InterruptedException {
        File tool = findTool();
        if (tool == null) {
            throw new IOException("没有找到外部取码工具，把它放到 " + requiredPath());
        }
        File dir = bridgeDirectory();
        lastMessage = "";

        // 已经有一个活着的实例就直接复用：每次都重启等于每次都要等它把 88 MB 自解压到
        // %TEMP%（二三十秒起步），而复用只要一句 refresh，出码是秒级的。
        if (requestRefresh(dir)) {
            try {
                return awaitQrCode(dir, REUSE_WAIT_MILLIS);
            } catch (IOException reuseFailure) {
                // 那个实例其实已经僵死（新鲜度判据毕竟是启发式的）。别把失败抛给人，
                // 退回重新拉起一次；真拉不起来时下面那句 awaitQrCode 才会报错。
                System.err.println("[Music/Qishui] 复用外部取码工具失败，改为重新启动："
                        + reuseFailure.getMessage());
            }
        }

        stop();
        deleteQuietly(new File(dir, "state.json"));
        deleteQuietly(new File(dir, "command.json"));

        ProcessBuilder builder = new ProcessBuilder(tool.getAbsolutePath(), "--bridge",
                dir.getAbsolutePath());
        // 坑：环境里只要带了它，electron 就退化成纯 Node，require('electron') 返回路径字符串。
        builder.environment().remove("ELECTRON_RUN_AS_NODE");
        builder.directory(tool.getParentFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = builder.start();

        return awaitQrCode(dir, START_TIMEOUT_MILLIS);
    }

    /** 等到工具给出第一张二维码，或给出一个明确的失败原因。 */
    private static QrCode awaitQrCode(File dir, long timeoutMillis)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            State state = poll();
            if (state != null) {
                lastMessage = state.message;
                if (!state.scanUrl.isEmpty()) {
                    String key = state.token.isEmpty() ? state.scanUrl : state.token;
                    return new QrCode(key, state.scanUrl);
                }
                if (state.phase == Phase.ERROR || state.phase == Phase.EXPIRED) {
                    throw new IOException(state.message.isEmpty()
                            ? "外部取码工具启动失败" : state.message);
                }
            }
            Thread.sleep(300L);
        }
        throw new IOException("外部取码工具在 " + (timeoutMillis / 1000L) + " 秒内没有给出二维码"
                + (timeoutMillis == START_TIMEOUT_MILLIS ? "（首次启动要自解压，请稍等再试）" : ""));
    }

    /**
     * 请已在跑的实例再出一张码。返回 {@code false} 表示没有可复用的实例，得自己拉一个。
     *
     * <p>认「活着」靠的是<b>心跳新鲜度</b>而不是 {@link State#isAlive()}：后者把终态也算活着
     * （终态本来就不必再刷新），拿它判断复用会把一个已经退出的进程当成还在跑。</p>
     */
    private static boolean requestRefresh(File dir) {
        if (!hasLiveInstance()) return false;
        try {
            Files.write(new File(dir, "command.json").toPath(),
                    "{\"action\":\"refresh\"}".getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasLiveInstance() {
        Process running = process;
        if (running != null && running.isAlive()) return true;
        // 自己没有句柄也别急着重启：上一次会话（甚至上一次游戏）留下的实例可能还在空闲，
        // 它空闲时仍在心跳，凭 state 里的 pid 就能认出来。
        State state = poll();
        if (state == null || state.pid <= 0) return false;
        if (state.phase == Phase.STOPPED || state.phase == Phase.ERROR
                || state.phase == Phase.UNKNOWN) return false;
        if (System.currentTimeMillis() - state.updatedAt > STALE_MILLIS) return false;
        return pidAlive(state.pid);
    }

    private static boolean pidAlive(long pid) {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 读一次 {@code state.json}。文件不存在或解析不了返回 {@code null}（表示「还没状态」）。 */
    public static State poll() {
        File file = new File(bridgeDirectory(), "state.json");
        if (!file.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            State state = parseState(text);
            if (state != null && state.phase != Phase.UNKNOWN) lastMessage = state.message;
            return state;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * 把 {@code state.json} 的正文解成 {@link State}，解析不了返回 {@code null}。
     * <b>只给测试用</b>：协议解析是最容易在改字段时悄悄走偏的一环，得能不打盘就验。
     */
    static State parseState(String text) {
        try {
            String json = text == null ? "" : text;
            if (json.startsWith("\uFEFF")) json = json.substring(1);
            JsonObject body = JsonParser.parseString(json).getAsJsonObject();
            Phase phase = parsePhase(string(body, "phase"));
            return new State(phase,
                    string(body, "token"),
                    string(body, "scanUrl"),
                    string(body, "cookie"),
                    string(body, "message"),
                    (int) longValue(body, "errorCode"),
                    longValue(body, "updatedAt"),
                    longValue(body, "pid"));
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 工具最近一次给人看的话（「请用抖音 App 扫码」「请在弹出的窗口里完成验证」等）。 */
    public static String statusHint() {
        return lastMessage == null ? "" : lastMessage;
    }

    /**
     * 结束这一次扫码会话：<b>让工具转入空闲等复用，不杀进程。</b>
     *
     * <p>以前这里是直接 {@link #stop()} 的，于是每次登录（哪怕只是翻页回来）都要重新走一遍
     * 88 MB 自解压。工具空闲时会自己倒计时退出，不至于赖在后台。</p>
     *
     * <p>注意「离开页面」用这个方法就够了：工具侧空闲超时会收摊，<b>不需要</b>也不该在这里
     * 把它 kill 掉——风控那种「人还在窗口里操作」的时刻，一 kill 就等于把人的验证作废。</p>
     */
    public static synchronized void releaseSession() {
        try {
            Files.write(new File(bridgeDirectory(), "command.json").toPath(),
                    "{\"action\":\"ack\"}".getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // 写不出去也没关系：工具那边有空闲超时兜底。
        }
    }

    /**
     * 彻底收摊：先写 {@code command.json} 请它自己退，再给 5 秒宽限，超时才强杀。
     * 直接 {@code destroy()} 会留下自解压的临时目录与孤儿渲染进程。
     *
     * <p>只在「确定不再需要这个进程」时调用（例如复用判据认为它已经僵死、要重新拉一个）。
     * 日常结束一次会话请用 {@link #releaseSession()}。</p>
     */
    public static synchronized void stop() {
        try {
            File dir = bridgeDirectory();
            Files.write(new File(dir, "command.json").toPath(),
                    "{\"action\":\"stop\"}".getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // 写不进去也要继续：下面还有强杀兜底。
        }
        Process running = process;
        process = null;
        if (running == null) {
            // 没有句柄：那是上一次会话留下的实例，我们不该凭 pid 去 kill 陌生进程，
            // 上面那句 stop 命令已经足够让它自己退（它空闲时每 500 毫秒读一次命令）。
            return;
        }
        Thread reaper = new Thread(() -> {
            try {
                if (!running.waitFor(5L, TimeUnit.SECONDS)) running.destroy();
            } catch (Throwable ignored) {
                running.destroy();
            }
        });
        reaper.setDaemon(true);
        reaper.setName("muonium-qishui-bridge-reaper");
        reaper.start();
    }

    private static Phase parsePhase(String raw) {
        if (raw == null || raw.isEmpty()) return Phase.UNKNOWN;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "starting": return Phase.STARTING;
            case "waiting": return Phase.WAITING;
            case "scanned": return Phase.SCANNED;
            case "mfa": return Phase.MFA;
            case "confirmed": return Phase.CONFIRMED;
            case "expired": return Phase.EXPIRED;
            case "error": return Phase.ERROR;
            case "stopped": return Phase.STOPPED;
            case "idle": return Phase.IDLE;
            default: return Phase.UNKNOWN;
        }
    }

    private static String string(JsonObject body, String name) {
        if (body == null || !body.has(name) || body.get(name).isJsonNull()) return "";
        return body.get(name).getAsString();
    }

    private static long longValue(JsonObject body, String name) {
        if (body == null || !body.has(name) || body.get(name).isJsonNull()) return 0L;
        try {
            return (long) body.get(name).getAsDouble();
        } catch (Throwable throwable) {
            return 0L;
        }
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Throwable ignored) {
            // 删不掉不影响：工具每次启动都会整份覆盖 state.json。
        }
    }
}
