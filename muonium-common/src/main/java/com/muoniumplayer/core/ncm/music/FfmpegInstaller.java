package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.nio.file.Files;

/**
 * Windows 上「一键安装 ffmpeg」的傻瓜流程：从托管仓库的镜像链下载伴生包
 * （见 {@link FfmpegPackageDownload}），校验后解压到 {@link ConfigPaths#BIN_DIR}
 * ——{@link FfmpegSupport} 的候选探测列表本来就有这一位，装完
 * {@link FfmpegSupport#forceReprobe()} 立刻复测，全程不用用户碰命令行、
 * 不用重启游戏。
 *
 * <p>整个流程跑在一个守护线程上。下载层自带的连接 / 读取超时兜住网络悬挂，
 * 校验（字节数 + sha256）与解压（zip-slip 防护）由 {@link FfmpegPackageDownload} 负责；
 * 复测会真的创建子进程跑 {@code ffmpeg -version}，结果比任何退出码都可信。</p>
 *
 * <p>状态只增不减（IDLE → RUNNING → SUCCESS/FAILED），界面每帧读 {@link #state()} 刷新文案即可，
 * 不需要回调：写发生在安装线程、读发生在渲染线程，全靠 volatile 字段，界面侧永远只做「读到什么画
 * 什么」。失败后允许重试（用户可能刚修好网络），成功后就不再来第二次。</p>
 */
public final class FfmpegInstaller {

    /** 还没开始装。 */
    public static final int IDLE = 0;
    /** 伴生包正在下载 / 校验 / 解压。 */
    public static final int RUNNING = 1;
    /** 装完且复测真的找到了 ffmpeg。 */
    public static final int SUCCESS = 2;
    /** 没装成（原因见 {@link #failureReason()}，界面照原样展示）。 */
    public static final int FAILED = 3;

    private static volatile int state = IDLE;
    /** 失败时给人看的短原因（已按界面文案的口吻写好），成功或未开始时为 {@code null}。 */
    private static volatile String failureReason;
    /** 本次安装开始的时间；界面拿去画进度省略号，IDLE 时无意义。 */
    private static volatile long startedAtMillis;
    /** 下载进度 0~100；-1 表示还没拿到有效进度（比如还在等镜像响应）。 */
    private static volatile int progressPercent = -1;

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

    /** 本次安装开始的时间戳，供界面画进度。 */
    public static long startedAtMillis() {
        return startedAtMillis;
    }

    /**
     * 下载进度 0~100，{@code -1} 表示未知（例如还在等第一条镜像响应）。
     * 由安装线程上的下载回调写入，界面线程只读。
     */
    public static int progressPercent() {
        return progressPercent;
    }

    /**
     * 开始安装。已在跑或已经成功就什么都不做；失败后可以再次调用（重试）。
     *
     * <p>不弹任何对话框、不切线程假设：调用点在渲染线程也没关系，真正的工作立刻交给守护线程。</p>
     */
    public static synchronized void start() {
        if (state == RUNNING || state == SUCCESS) return;
        state = RUNNING;
        failureReason = null;
        progressPercent = -1;
        startedAtMillis = System.currentTimeMillis();
        Thread thread = new Thread(FfmpegInstaller::runInstall, "muonium-ffmpeg-package-install");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runInstall() {
        File archive = null;
        try {
            File binDir = ConfigPaths.BIN_DIR;
            File parent = binDir.getParentFile();
            archive = (parent != null ? parent : binDir)
                    .toPath().resolve(FfmpegPackageDownload.PACK.fileName() + ".part").toFile();
            System.out.println("[MuoniumPlayer] ffmpeg install: fetching " + FfmpegPackageDownload.PACK.fileName()
                    + " (" + FfmpegPackageDownload.MIRRORS.size() + " mirrors)");
            FfmpegPackageDownload.fetchTo(archive, FfmpegInstaller::announceProgress);
            state = RUNNING;   // 回调只动 progressPercent，兜住状态被外部读到中间值的可能
            progressPercent = 100;
            System.out.println("[MuoniumPlayer] ffmpeg install: downloaded and verified, unpacking");
            FfmpegPackageDownload.unpack(archive, binDir);
            System.out.println("[MuoniumPlayer] ffmpeg install: unpacked, re-probing");
            // 复测会真的跑一次 ffmpeg -version：包校验过了但二进制能不能跑（缺 DLL 之类）
            // 只有它说了算。WinGet 时代就定下的规矩——退出码和文件存在都不是最终真相。
            if (FfmpegSupport.forceReprobe() != null) {
                finish(true, null);
            } else {
                finish(false, "包装好了但没检测到喵…重启游戏或「前往下载」");
            }
        } catch (Throwable throwable) {
            System.out.println("[MuoniumPlayer] ffmpeg package install failed: "
                    + FfmpegPackageDownload.describe(throwable));
            finish(false, "下载没成功喵…检查网络后重试，或「前往下载」");
        } finally {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive.toPath());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 下载进度写进来；RUNNING 之外的状态不动，避免复测/失败后的迟回调把状态行改乱。 */
    private static void announceProgress(int percent) {
        if (state != RUNNING) return;
        progressPercent = Math.max(0, Math.min(100, percent));
    }

    /** 落定终态。终态已定后不许再改。 */
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
}
