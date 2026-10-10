package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * qs-backend 的进程生命周期:随游戏而起、随游戏而停,只杀自己拉起的进程。
 *
 * <h2>起</h2>
 * 模组初始化时(异步线程)调用 {@link #ensureStartedForGame()}:
 * <ul>
 *   <li>安装目录里已有完整后端(伴生包装过)且 8787 无健康实例 → 用包内 node.exe
 *       隐藏拉起(输出重定向到 backend.log,Windows 上不会弹控制台窗);</li>
 *   <li>8787 已有健康后端(用户手动 start.bat 开的)→ <b>不重复拉起也不接管</b>,
 *       关机钩子不会杀它;</li>
 *   <li>还没安装伴生包 → 这里<b>不下载</b>(进游戏就拉 33MB 会惊吓用户),
 *       等用户真正点进汽水源时由 {@link QishuiCompanionDownload} 按需下载并拉起。</li>
 * </ul>
 *
 * <h2>停</h2>
 * JVM 关闭钩子里销毁由本类拉起的进程。仓库里没有任何 ClientStopping 钩子
 * (全量 grep 验证过),addShutdownHook 是唯一覆盖所有退出路径(正常退出、
 * GameRestart 重启、启动器杀进程)的办法。用户手动开的后端不受影响。
 */
public final class QishuiBackendLifecycle {

    private static volatile Process ownedProcess;
    /** 最近一次(任一组件)拉起后端的时间:15 秒启动窗口内别的组件不再重复拉起。 */
    private static volatile long lastSpawnAtMillis;

    private QishuiBackendLifecycle() {
    }

    static {
        // JVM 退出(任何路径)时收掉自己拉起的后端进程;只对自己有所有权。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process process = ownedProcess;
            if (process == null || !process.isAlive()) return;
            process.destroy();
            try {
                if (!process.waitFor(5L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }, "MuoniumPlayer-QishuiBackendShutdown"));
    }

    /** 模组初始化时调用一次:装好了就隐藏拉起,没装就留给按需下载链路。 */
    public static void ensureStartedForGame() {
        if (ownedProcess != null && ownedProcess.isAlive()) return; // 自己拉起的还活着
        // 已装但伴生包版本落后 → 后台热补(不阻塞启动,失败静默下次重试)。
        QishuiCompanionDownload.refreshIfNeeded();
        if (QishuiBackend.isAvailable()) {
            System.out.println("[MuoniumPlayer/QishuiBackend] 检测到 8787 已有健康后端,不重复拉起");
            // 已健康也要走一次双向 Cookie 同步:2026-10-11 真机踩过——后端是上一局
            // 留下/手动开的,模组侧没有 onBackendUp 回调,登录工具写进后端的 Cookie
            // 拉不回模组,账号歌单/红心全不可用。
            QishuiBackend.onBackendUp();
            return;
        }
        if (!QishuiCompanionDownload.isInstalled()) {
            // 进游戏即下载(用户指定):动态岛实时展示进度,失败自动重试,
            // 期间汽水源降级试听。装好后拉起进程。
            System.out.println("[MuoniumPlayer/QishuiBackend] 本机缺少汽水签名后端,进游戏即开始下载");
            QishuiCompanionDownload.onBackendMissing();
            return;
        }
        MultiThreadingUtil.runAsync(() -> {
            try {
                startOwned();
            } catch (Throwable error) {
                System.err.println("[MuoniumPlayer/QishuiBackend] 随游戏启动后端失败: " + error);
            }
        });
    }

    /** 拉起冷却:另一个组件刚拉起过(启动窗口内)就别再抢同一个端口。 */
    static boolean recentlySpawned() {
        return System.currentTimeMillis() - lastSpawnAtMillis < 15_000L;
    }

    static void noteSpawn() {
        lastSpawnAtMillis = System.currentTimeMillis();
    }

    /** 拉起并登记所有权;成功且健康时顺带把模组的 Cookie 推过去。
     *  路径全部取绝对形态——游戏进程 CWD 与模组文件解析目录不保证一致(真机踩过)。 */
    private static void startOwned() throws IOException {
        if (recentlySpawned() && QishuiCompanionDownload.isInstalled()) {
            // 并发场景:伴生包下载器刚拉起过,等它的健康检查跑完即可。
            System.out.println("[MuoniumPlayer/QishuiBackend] 另一组件刚拉起过后端,跳过本次重复拉起");
            return;
        }
        File rootAbs = QishuiCompanionDownload.installRoot().getAbsoluteFile();
        Process process = new ProcessBuilder(
                new File(rootAbs, "node.exe").getAbsolutePath(),
                new File(rootAbs, "server.mjs").getAbsolutePath())
                .directory(rootAbs)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(rootAbs, "backend.log")))
                .start();
        ownedProcess = process;
        noteSpawn();
        System.out.println("[MuoniumPlayer/QishuiBackend] 后端已随游戏拉起 pid=" + process.pid());
        // 等健康检查通过(首启要建 config,给 10 秒),再把游戏内的 Cookie 推过去配对。
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (QishuiBackend.isAvailable()) {
                QishuiBackend.onBackendUp();
                return;
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("[MuoniumPlayer/QishuiBackend] 后端进程已启动但健康检查未通过,"
                + "详见 " + new File(rootAbs, "backend.log"));
    }

    /** 伴生包下载器拉起的进程也归本类管(关游戏时一并收掉)。 */
    static void adoptOwnedProcess(Process process) {
        ownedProcess = process;
    }
}
