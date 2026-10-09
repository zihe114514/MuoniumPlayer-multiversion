package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Windows 上「一键安装 ffmpeg」的傻瓜流程：用系统自带的 winget 装 {@link FfmpegNotice#WINGET_PACKAGE_ID}
 * （Gyan 构建），装完立刻复测，全程不用用户碰命令行、不用重启游戏。
 *
 * <p>整个流程跑在一个守护线程上，这根线程就是<b>看门狗</b>：一边持续排空 winget 的输出（不排空的话
 * 管道缓冲区填满，winget 会堵在写上永远退不了），一边盯着总时长——超时就杀进程判失败。winget 由
 * ProcessBuilder 拉起且三个标准流都被重定向，Windows 上 JVM 会自动加 {@code CREATE_NO_WINDOW}，
 * 不会朝用户脸上闪黑框。</p>
 *
 * <p>进程退出后<b>无论退出码是什么</b>都先重新探测一遍 ffmpeg：winget 对「已经装过了」这类情况也报
 * 非 0，复测结果比退出码可信。装好的 ffmpeg 落在 {@code %LOCALAPPDATA%\Microsoft\WinGet\Links\}，
 * {@link FfmpegSupport} 的候选路径里本来就有它，所以复测不需要重启游戏。</p>
 *
 * <p>状态只增不减（IDLE → RUNNING → SUCCESS/FAILED），界面每帧读 {@link #state()} 刷新文案即可，
 * 不需要回调：写发生在安装线程、读发生在渲染线程，全靠 volatile 字段，界面侧永远只做「读到什么画
 * 什么」。失败后允许重试（用户可能刚修好网络），成功后就不再来第二次。</p>
 */
public final class FfmpegInstaller {

    /** 还没开始装。 */
    public static final int IDLE = 0;
    /** winget 正在下载安装。 */
    public static final int RUNNING = 1;
    /** 装完且复测真的找到了 ffmpeg。 */
    public static final int SUCCESS = 2;
    /** 没装成（原因见 {@link #failureReason()}，界面照原样展示）。 */
    public static final int FAILED = 3;

    /**
     * 总时长兜底。winget 下载 ffmpeg 安装包正常要几分钟，慢网络放宽到一刻钟；真到这一步多半是
     * 卡在了什么地方（源不可达、弹了等不到的确认），杀掉比永远挂着强。
     */
    private static final long OVERALL_TIMEOUT_MILLIS = 15L * 60L * 1_000L;

    private static volatile int state = IDLE;
    /** 失败时给人看的短原因（已按界面文案的口吻写好），成功或未开始时为 {@code null}。 */
    private static volatile String failureReason;
    /** 本次安装开始的时间；界面拿去画进度省略号，IDLE 时无意义。 */
    private static volatile long startedAtMillis;

    private FfmpegInstaller() {
    }

    /** 当前状态：{@link #IDLE} / {@link #RUNNING} / {@link #SUCCESS} / {@link #FAILED}。 */
    public static int state() {
        return state;
    }

    /** 最近一次失败的原因，可能为 {@code null}。 */
    public static String failureReason() {
        return failureReason;
    }

    /** 本次安装开始的时间戳，供界面画进度省略号。 */
    public static long startedAtMillis() {
        return startedAtMillis;
    }

    /**
     * 开始安装。已在跑或已经成功就什么都不做；失败后可以再次调用（重试）。
     *
     * <p>不弹任何对话框、不切线程假设：调用点在渲染线程也没关系，真正的工作立刻交给守护线程。
     * 找不到 winget 时同步判失败——这不用等进程，但也不算重，文件存在性检查而已。</p>
     */
    public static synchronized void start() {
        if (state == RUNNING || state == SUCCESS) return;
        final String winget = resolveWinget();
        if (winget == null) {
            state = FAILED;
            failureReason = "这台电脑上没找到 winget 喵…点「前往下载」手动装一个吧";
            return;
        }
        state = RUNNING;
        failureReason = null;
        startedAtMillis = System.currentTimeMillis();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runInstall(winget);
            }
        }, "muonium-ffmpeg-winget-install");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runInstall(String winget) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    winget, "install", "--id", FfmpegNotice.WINGET_PACKAGE_ID, "--exact", "--silent",
                    "--accept-package-agreements", "--accept-source-agreements");
            builder.redirectErrorStream(true);
            process = builder.start();
            FfmpegSupport.drainAsync(process.getInputStream());
            if (!process.waitFor(OVERALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                System.out.println("[MuoniumPlayer] winget install timed out, killing it");
                finish(false, "等了太久都没装完喵…点「前往下载」手动装吧");
                return;
            }
            int exit = process.exitValue();
            System.out.println("[MuoniumPlayer] winget install exit code: " + exit);
            // 退出码不是唯一真相：winget 对「已经装过了」也报非 0，所以一律以复测为准。
            if (FfmpegSupport.forceReprobe() != null) {
                finish(true, null);
            } else if (exit == 0) {
                finish(false, "winget 说装好了，但没检测到喵…重启游戏后再看看，或点「前往下载」手动装");
            } else {
                finish(false, "没装上喵（winget 退出码 0x" + Integer.toHexString(exit)
                        + "），点「前往下载」手动装吧");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(false, "安装被打断喵…点「前往下载」手动装吧");
        } catch (Throwable throwable) {
            System.out.println("[MuoniumPlayer] winget install failed to launch: " + throwable);
            finish(false, "安装没能启动喵…点「前往下载」手动装吧");
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(200L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }

    /** 落定终态。同步块挡住超时与异常两条路同时到终点的竞态，且终态已定后不许再改。 */
    private static synchronized void finish(boolean ok, String reason) {
        if (state != RUNNING) return;
        if (ok) {
            state = SUCCESS;
            failureReason = null;
            FfmpegNotice.noteProbeResult(FfmpegNotice.STATE_PRESENT);
        } else {
            state = FAILED;
            failureReason = reason;
        }
    }

    /**
     * 定位 winget 的应用执行别名。它固定落在 {@code %LOCALAPPDATA%\Microsoft\WindowsApps\winget.exe}；
     * 这个文件不存在就基本等于这台机器没装 winget（裸进程 PATH 在本进程里是启动时固定的，不够可靠，
     * 也不必再赌）。
     */
    private static String resolveWinget() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) return null;
        String trimmed = localAppData.trim();
        if (trimmed.isEmpty()) return null;
        File alias = new File(trimmed, "Microsoft\\WindowsApps\\winget.exe");
        return alias.isFile() ? alias.getPath() : null;
    }
}
