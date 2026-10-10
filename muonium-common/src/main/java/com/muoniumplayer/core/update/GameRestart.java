package com.muoniumplayer.core.update;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaNatives;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 渲染原生库装好之后的<b>自动重启</b>：模组替用户把游戏关掉、再原样打开。
 *
 * <h2>为什么需要它</h2>
 * 原生库必须在全新的 JVM 里加载才干净可靠（热加载在装到一半的游戏里不可靠，见
 * {@code SkijaCompanionDownload} 的重启看门狗）。而「装好了」和「能用」之间原本隔着
 * 三次手动操作：看懂救援界面、关掉游戏、回启动器再点开始。现在这段路模组自己走完：
 * 等回主菜单 → 优雅关闭本进程 → 看守脚本用<b>捕获到的原启动命令</b>重新拉起。
 *
 * <h2>命令从哪来</h2>
 * {@code ProcessHandle.current().arguments()} 给出本进程完整的 argv（JVM 参数 +
 * 主类 + 游戏参数），配合 {@code command()} 的 java 可执行文件与 {@code user.dir}
 * 工作目录，逐 token 写进 {@code restart.args}（Java 9+ 的 {@code @argfile} 语法，
 * 1.20.1 起全平台最低都是 Java 17）。启动器塞给游戏的 -D 参数（含 natives 目录、
 * 资产索引）原样保留，重启后与手动重启一字不差。
 *
 * <h2>看守进程</h2>
 * 游戏进程没法复活自己——关掉的那一刻 JVM 已经没了。所以先写一个脱离本进程的看守
 * 脚本（Windows：{@code restart.cmd}，{@code start /min} 起独立控制台；
 * Unix：{@code restart.sh} + nohup），它等本 PID 消失后执行 {@code java @restart.args}。
 * 脚本与 argfile 留在 {@code config/muonium/restart/}：看守若被启动器的 Job Object 连带杀掉
 * （极少见），玩家双击它们也能完成同一次重启——文案里会给出路径。
 *
 * <h2>安全阀（四道）</h2>
 * <ol>
 *   <li><b>只在主菜单执行</b>：游戏世界里绝不自动关人——挂机挖矿被掐是事故。
 *       倒计时只在 {@code level == null} 时走；</li>
 *   <li><b>ESC ：倒计时期间按 ESC 本会话永久取消，救援文案转为手动重启指引
 *       （脚本与 argfile 仍在原地，双击即重启）；</li>
 *   <li><b>ESC 拆雷</b>：倒计时期间按 ESC 本会话永久取消，并删除标记让看守收手——
 *       取消后再关游戏也不会被拉起；脚本与 argfile 仍在原地，双击即重启；</li>
 *   <li><b>至多重启一次</b>：重启后的进程若原生库仍未加载成功，{@code arm} 读到标记计数
 *       已达上限，不再布防——看守不会、也不该第二次拉起游戏；</li>
 *   <li><b>崩溃循环闸</b>：{@code restart.marker} 记录已尝试次数与时刻，计数达上限不再
 *       自动重启（游戏可能因别的原因起不来，反复重启只会无限循环）；标记两小时陈旧后
 *       重新起算（用户已自行处理过，系统要能复活）。</li>
 * </ol>
 *
 * <h2>标记生命周期</h2>
 * 布防时落 marker（attempt 计数 + 时间戳）→ 重启后的进程加载成功那一刻
 * {@link #onNativesLoaded()}（SkijaNatives.adopt 的任何 TRUE 跃迁都会调）删除；
 * ESC 取消也删除（拆雷）。marker 还在 = 上一次重启没把库带活，arm 读到计数已达上限
 * 就不再布防；标记超过两小时视为陈旧，计数重新起算。看守只读标记、从不删标记。
 */
public final class GameRestart {

    /** 倒计时长度：够读完两行救援文案，又不至于让人忘了这回事。 */
    private static final long COUNTDOWN_NANOS = 15L * 1_000_000_000L;

    /** 看守等待游戏退出的上限（秒）：超时仍不退就当取消，脚本自己退出。 */
    private static final int WATCHER_TIMEOUT_SECONDS = 600;

    /** 自动重启的上限：一个「标记生命周期」内至多替用户重启一次，再不活就回手动指引。 */
    private static final int MAX_ATTEMPTS = 1;

    /** 标记的陈旧判定：两小时前的重启标记不再参与计数（用户已自行处理过，系统要能复活）。 */
    private static final long MARKER_STALE_MILLIS = 2L * 60L * 60L * 1000L;

    private static final String MARKER_FILE = "restart.marker";
    private static final String ARGS_FILE = "restart.args";
    private static final String SCRIPT_FILE = "restart.cmd";
    private static final String SCRIPT_FILE_UNIX = "restart.sh";

    enum Stage {
        /** 还没到该重启的时候（下载未完/尚未置重启闸）。 */
        NONE,
        /** 已布防：等主菜单 + 倒计时。 */
        ARMED,
        /** 用户 ESC 取消，本会话不再自动重启。 */
        CANCELLED,
        /** 已调用 stop()：进程即将退出，看守接管。 */
        STOPPING,
        /** 条件不满足无法布防（拿不到命令/超过尝试上限/非桌面平台）。 */
        UNAVAILABLE
    }

    private static volatile Stage stage = Stage.NONE;
    private static volatile long countdownEndNanos;
    private static volatile int previousSecond = -1;

    private GameRestart() {
    }

    /** 与字体/模型缓存同一约定：模组自有根 config/muonium（不借 ConfigPaths 的私有常量）。 */
    private static File cacheDir() {
        return new File(new File("config", "muonium"), "restart");
    }

    /** 是否已经布防（救援文案据此切换措辞）。 */
    public static boolean armed() {
        return stage == Stage.ARMED;
    }

    /** 救援文案的一行状态；不需要说话时返回 null。由 SkijaNatives.nativeMissingLines 拼接。 */
    public static String statusLine() {
        switch (stage) {
            case ARMED:
                long remaining = countdownEndNanos == 0L ? -1L
                        : (countdownEndNanos - System.nanoTime() + 999_999_999L) / 1_000_000_000L;
                if (remaining < 0L) {
                    return "正在等待回到主菜单后自动重启（按 ESC 取消）";
                }
                return "将在 " + Math.max(0L, remaining) + " 秒后自动重启游戏（按 ESC 取消）";
            case CANCELLED:
                return "已取消自动重启；退出后双击 " + scriptName() + " 即可重新启动";
            case STOPPING:
                return "正在关闭游戏，重启马上开始…";
            case UNAVAILABLE:
                return null; // 拿不到命令/超过上限：文案回落到原来的手动指引
            default:
                return null;
        }
    }

    private static String scriptName() {
        return isWindows() ? SCRIPT_FILE : SCRIPT_FILE_UNIX;
    }

    /** 渲染层加载成功的那一刻（SkijaNatives.adopt 的任何 TRUE 跃迁都会调）：删掉重启标记。 */
    public static void onNativesLoaded() {
        try {
            Files.deleteIfExists(markerFile().toPath());
        } catch (Throwable ignored) {
        }
    }

    private static File markerFile() {
        return new File(cacheDir(), MARKER_FILE);
    }

    /**
     * 每 tick 由 {@code MuoniumPlayerClient.tick} 调（五棵树各一处）：
     * 到点布防、走倒计时、ESC 取消、主菜单才真正 stop()。Skia 挂没挂都无关——
     * 这里是原版 tick，用的是原版键盘垫片。
     *
     * @param stop   关闭本游戏进程（trees 传 {@code () -> mc.stop()}，优雅退出、存档完整）
     * @param idle   当前是否安全（没有世界可被掐：{@code mc.level == null}）
     */
    public static void tick(Runnable stop, boolean idle) {
        try {
            // ① 布防检查：伴生包已装好、尚未生效 → 准备替用户重启
            if (stage == Stage.NONE && SkijaNatives.companionRestartPending()) {
                arm();
            }
            if (stage != Stage.ARMED) {
                return;
            }

            // ② 取消闸：ESC 在任何状态下都能按停这出戏
            if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
                stage = Stage.CANCELLED;
                // 拆雷：看守脚本见「没有标记」就不拉起游戏。不删标记的话用户 ESC 之后
                // 再关掉游戏，看守还会把游戏拉起来——那是违背取消意图的复活。
                try {
                    Files.deleteIfExists(markerFile().toPath());
                } catch (Throwable ignored) {
                }
                System.out.println("[MuoniumPlayer/GameRestart] 用户已取消自动重启（ESC）；"
                        + "脚本保留在 " + new File(cacheDir(), scriptName()).getAbsolutePath()
                        + "，想重启时双击它即可");
                return;
            }

            // ③ 只在主菜单倒计时：世界里的玩家不能被掐
            if (!idle) {
                countdownEndNanos = 0L;
                previousSecond = -1;
                return;
            }
            long now = System.nanoTime();
            if (countdownEndNanos == 0L) {
                countdownEndNanos = now + COUNTDOWN_NANOS;
                previousSecond = -1;
            }
            if (now < countdownEndNanos) {
                long seconds = Math.max(0L, (countdownEndNanos - now) / 1_000_000_000L);
                if ((int) seconds != previousSecond) {
                    previousSecond = (int) seconds;
                    // 每秒打一行日志；救援文案靠 statusLine 每帧现取
                    System.out.println("[MuoniumPlayer/GameRestart] 自动重启倒计时: " + seconds + "s");
                }
                return;
            }
            stage = Stage.STOPPING;
            System.out.println("[MuoniumPlayer/GameRestart] 自动重启：关闭游戏进程（看守已接管）");
            stop.run();
        } catch (Throwable failure) {
            // 自动重启永远是锦上添花：任何异常都不能把 tick 链路带下去
            if (stage == Stage.ARMED) {
                stage = Stage.UNAVAILABLE;
                System.out.println("[MuoniumPlayer/GameRestart] 自动重启失败，回落到手动指引："
                        + failure.getClass().getName() + ": " + failure.getMessage());
            }
        }
    }

    /** 布防：落 marker、写 argfile 与看守脚本、把看守派生成脱离本进程的独立进程。 */
    private static void arm() {
        cacheDir().mkdirs();
        int attempts = readAttempts() + 1;
        if (attempts > MAX_ATTEMPTS) {
            stage = Stage.UNAVAILABLE;
            System.out.println("[MuoniumPlayer/GameRestart] 已自动重启 " + readAttempts()
                    + " 次仍未加载成功，停止自动重试——请按救援界面的手动指引操作");
            return;
        }
        List<String> argv = currentCommand();
        if (argv == null) {
            stage = Stage.UNAVAILABLE;
            return;
        }
        try {
            writeMarker(attempts);
            writeArgFile(argv);
            writeWatcherScript(attempts);
            spawnWatcher();
            stage = Stage.ARMED;
            System.out.println("[MuoniumPlayer/GameRestart] 已布防自动重启（第 " + attempts + " 次尝试）："
                    + "回主菜单后 15 秒倒计时，ESC 取消");
        } catch (Throwable failure) {
            stage = Stage.UNAVAILABLE;
            System.out.println("[MuoniumPlayer/GameRestart] 布防失败（" + failure + "），"
                    + "回落到手动重启指引");
        }
    }

    /**
     * 捕获本进程的启动命令：java 可执行文件 + 完整 argv。
     *
     * <p>{@code ProcessHandle.arguments()} 在多数 JDK 上可用；个别精简运行时返回空
     * 或抛异常——那种情况不布防（返回 null），救援文案继续讲原来的「必须重启」。
     * 首项若恰好就是 java 可执行文件本身（某些平台把 argv[0] 也算进 arguments），
     * 与 {@code command()} 比对后去掉，避免 argfile 里把解释器写成第一个参数。</p>
     */
    private static List<String> currentCommand() {
        try {
            String exe = ProcessHandle.current().info().command().orElse(null);
            // arguments() 挂在 Info 上（返回 Optional<String[]>）：多数 JDK 要求同用户/
            // 有权限，个别精简运行时给空——那种情况不布防，走原来的手动指引。
            String[] args = ProcessHandle.current().info().arguments().orElse(null);
            if (exe == null || args == null || args.length == 0) {
                System.out.println("[MuoniumPlayer/GameRestart] 拿不到启动命令（运行时不支持进程自省），不自动重启");
                return null;
            }
            List<String> argv = new ArrayList<>();
            argv.add(exe);
            for (String arg : args) {
                if (argv.size() == 1 && exe.equalsIgnoreCase(arg)) {
                    continue;
                }
                argv.add(arg);
            }
            return argv;
        } catch (Throwable probeFailure) {
            return null;
        }
    }

    /** java 可执行路径 + 完整 argv 写成 @argfile（token 一行一个，Windows 路径转义反斜杠）。 */
    private static void writeArgFile(List<String> argv) throws IOException {
        StringBuilder argFile = new StringBuilder();
        for (String token : argv) {
            argFile.append(quoteArgFileToken(token)).append('\n');
        }
        Files.write(new File(cacheDir(), ARGS_FILE).toPath(),
                argFile.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** @argfile 的词法：空白用行分隔，内部引号/反斜杠转义，Windows 反斜杠翻倍。 */
    private static String quoteArgFileToken(String token) {
        if (isWindows()) {
            token = token.replace("\\", "\\\\");
        }
        token = token.replace("\"", "\\\"");
        if (token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0 || token.contains("\\\"")) {
            return "\"" + token + "\"";
        }
        return token;
    }

    /**
     * 看守脚本：等 PID 消失（或超时）→ 同工作目录 → {@code java @restart.args}。
     *
     * <p>Windows 用 {@code tasklist} 轮询（{@code timeout} 在无控制台的派生环境会失败，
     * 用 {@code ping} 打盹）；Unix 用 {@code kill -0}。脚本是纯文本，玩家可以自己读、
     * 自己改、自己双击——不藏黑盒。</p>
     */
    private static void writeWatcherScript(int attempt) throws IOException {
        long pid = ProcessHandle.current().pid();
        File root = cacheDir().getAbsoluteFile();
        String argsPath = new File(root, ARGS_FILE).getAbsolutePath();
        String markerPath = new File(root, MARKER_FILE).getAbsolutePath();
        String workingDir = new File(System.getProperty("user.dir", "."))
                .getAbsolutePath();
        StringBuilder script = new StringBuilder();
        if (isWindows()) {
            script.append("@echo off\r\n");
            script.append("rem MuoniumPlayer render-layer restart watch (attempt ").append(attempt).append(")\r\n");
            script.append("set /a waited=0\r\n");
            script.append(":loop\r\n");
            script.append("tasklist /FI \"PID eq ").append(pid).append("\" | find \" ").append(pid).append("\" >nul 2>&1\r\n");
            script.append("if errorlevel 1 goto launch\r\n");
            script.append("ping -n 2 127.0.0.1 >nul\r\n");
            script.append("set /a waited+=1\r\n");
            script.append("if ").append(WATCHER_TIMEOUT_SECONDS).append(" gtr %waited% goto loop\r\n");
            script.append("echo game did not exit in time, giving up>\r\n");
            script.append("exit /b 1\r\n");
            script.append(":launch\r\n");
            script.append("ping -n 4 127.0.0.1 >nul\r\n");
            script.append("if not exist \"").append(markerPath).append("\" exit /b 0\r\n");
            script.append("rem 标记绝不在此删除：它是防循环计数，由重启后的游戏加载成功时删除\r\n");
            script.append("cd /d \"").append(workingDir).append("\"\r\n");
            script.append("start \"\" /min \"").append(argsFirstTokenPath(argsPath)).append("\" @\"").append(argsPath).append("\"\r\n");
        } else {
            script.append("#!/bin/sh\r\n");
            script.append("# MuoniumPlayer render-layer restart watch (attempt ").append(attempt).append(")\r\n");
            script.append("waited=0\r\n");
            script.append("while kill -0 ").append(pid).append(" 2>/dev/null; do\r\n");
            script.append("  sleep 1\r\n");
            script.append("  waited=$((waited+1))\r\n");
            script.append("  [ $waited -ge ").append(WATCHER_TIMEOUT_SECONDS).append(" ] && exit 1\r\n");
            script.append("done\r\n");
            script.append("sleep 3\r\n");
            script.append("[ -f \"").append(markerPath).append("\" ] || exit 0\r\n");
            script.append("# 标记绝不在此删除：它是防循环计数，由重启后的游戏加载成功时删除\r\n");
            script.append("cd \"").append(workingDir).append("\"\r\n");
            script.append("\"").append(argsFirstTokenPath(argsPath)).append("\" @\"").append(argsPath).append("\"\r\n");
        }
        File scriptFile = new File(root, scriptName());
        try (PrintStream out = new PrintStream(Files.newOutputStream(scriptFile.toPath()),
                false, "UTF-8")) {
            out.print(script);
        }
        trySetExecutable(scriptFile);
    }

    /**
     * 从 argfile 的第一行取 java 可执行路径（writeArgFile 把 argv[0] 写在第一行）。
     * 这样看守脚本里写的是「解释器 + @argfile」的完整组合，不依赖 PATH。
     */
    private static String argsFirstTokenPath(String argsPath) {
        try {
            List<String> lines = Files.readAllLines(new File(argsPath).toPath(),
                    StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String first = lines.get(0).trim();
                if (first.startsWith("\"") && first.endsWith("\"") && first.length() > 1) {
                    first = first.substring(1, first.length() - 1).replace("\\\"", "\"");
                    if (isWindows()) {
                        first = first.replace("\\\\", "\\");
                    }
                }
                return first;
            }
        } catch (Throwable ignored) {
        }
        return isWindows() ? "java.exe" : "java";
    }

    private static void writeMarker(int attempt) throws IOException {
        StringBuilder marker = new StringBuilder();
        marker.append("pid=").append(ProcessHandle.current().pid()).append('\n');
        marker.append("attempt=").append(attempt).append('\n');
        marker.append("armedAtMs=").append(System.currentTimeMillis()).append('\n');
        Files.write(markerFile().toPath(), marker.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 读标记里的重启计数；标记不存在、读不出、或已陈旧（两小时）都按 0 算——系统要能复活。 */
    private static int readAttempts() {
        File marker = markerFile();
        if (!marker.isFile()) {
            return 0;
        }
        try {
            int attempt = 0;
            long armedAtMs = 0L;
            for (String line : Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("attempt=")) {
                    attempt = Integer.parseInt(line.substring("attempt=".length()).trim());
                } else if (line.startsWith("armedAtMs=")) {
                    armedAtMs = Long.parseLong(line.substring("armedAtMs=".length()).trim());
                }
            }
            if (armedAtMs > 0L && System.currentTimeMillis() - armedAtMs > MARKER_STALE_MILLIS) {
                return 0;
            }
            return attempt;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 派生看守进程：Windows 用 start 起独立最小化控制台；Unix 用 nohup 脱离会话。 */
    private static void spawnWatcher() throws IOException {
        File root = cacheDir().getAbsoluteFile();
        File scriptFile = new File(root, scriptName());
        List<String> launch = new ArrayList<>();
        if (isWindows()) {
            launch.add("cmd.exe");
            launch.add("/c");
            launch.add("start");
            launch.add("/min");
            launch.add("");
            launch.add(scriptFile.getAbsolutePath());
        } else {
            launch.add("sh");
            launch.add("-c");
            launch.add("nohup sh \"" + scriptFile.getAbsolutePath() + "\" >/dev/null 2>&1 &");
        }
        ProcessBuilder builder = new ProcessBuilder(launch);
        builder.directory(root);
        builder.redirectOutput(new File(root, "restart-watcher.log"));
        builder.redirectErrorStream(true);
        builder.start();
    }

    private static void trySetExecutable(File file) {
        try {
            file.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(java.util.Locale.ROOT).contains("windows");
    }
}
