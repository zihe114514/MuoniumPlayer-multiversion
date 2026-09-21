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
    /** 超过这么久没刷新 state.json 就当工具已经没了（正常每 2 秒一写）。 */
    private static final long STALE_MILLIS = 20000L;

    public enum Phase {
        STARTING, WAITING, SCANNED, MFA, CONFIRMED, EXPIRED, ERROR, STOPPED, UNKNOWN
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

        State(Phase phase, String token, String scanUrl, String cookie, String message,
              int errorCode, long updatedAt) {
            this.phase = phase;
            this.token = token;
            this.scanUrl = scanUrl;
            this.cookie = cookie;
            this.message = message;
            this.errorCode = errorCode;
            this.updatedAt = updatedAt;
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
        stop();
        File tool = findTool();
        if (tool == null) {
            throw new IOException("没有找到外部取码工具，把它放到 " + requiredPath());
        }
        File dir = bridgeDirectory();
        deleteQuietly(new File(dir, "state.json"));
        deleteQuietly(new File(dir, "command.json"));
        lastMessage = "";

        ProcessBuilder builder = new ProcessBuilder(tool.getAbsolutePath(), "--bridge",
                dir.getAbsolutePath());
        // 坑：环境里只要带了它，electron 就退化成纯 Node，require('electron') 返回路径字符串。
        builder.environment().remove("ELECTRON_RUN_AS_NODE");
        builder.directory(tool.getParentFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = builder.start();

        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
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
        throw new IOException("外部取码工具在 " + (START_TIMEOUT_MILLIS / 1000L)
                + " 秒内没有给出二维码（首次启动要自解压，请稍等再试）");
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
                    longValue(body, "updatedAt"));
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 工具最近一次给人看的话（「请用抖音 App 扫码」「请在弹出的窗口里完成验证」等）。 */
    public static String statusHint() {
        return lastMessage == null ? "" : lastMessage;
    }

    /**
     * 让工具退出：先写 {@code command.json} 请它自己收摊，再给 5 秒宽限，超时才强杀。
     * 直接 {@code destroy()} 会留下自解压的临时目录与孤儿渲染进程。
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
        if (running == null) return;
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
