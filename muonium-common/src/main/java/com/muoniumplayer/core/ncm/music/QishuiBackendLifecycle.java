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
 *   <li>安装目录里已有完整后端(伴生包装过)且 8787 无健康实例 → 用包内运行时
 *       隐藏拉起(输出重定向到 backend.log,Windows 上不会弹控制台窗),
 *       并带 {@code --parent-pid <本 JVM>} 让后端自己盯住游戏进程;</li>
 *   <li>8787 已有健康后端(用户手动 start.bat 开的)→ <b>不重复拉起也不接管</b>,
 *       关机钩子不会杀它;</li>
 *   <li>8787 上挂着的是<b>上一局留下的孤儿</b>(带看门狗、父进程已不存在)→ 收掉
 *       并用当前游戏进程重新拉起,避免「后端活着但归属上一局」的中间态;</li>
 *   <li>还没安装伴生包 → 进游戏即按需下载(动态岛展示进度),装好后拉起。</li>
 * </ul>
 *
 * <h2>停</h2>
 * 三条独立的收尾路径,任意一条生效即可,不再有「游戏关了后端还在」的漏网:
 * <ol>
 *   <li><b>JVM 关闭钩子</b>销毁由本类拉起的进程(正常退出、GameRestart 重启);</li>
 *   <li><b>后端自己的看门狗</b>({@code --parent-pid} / {@code --watchdog-stdin}):
 *       连 JVM 被强杀(任务管理器结束任务、启动器清场)也照样退出——这是关闭钩子
 *       覆盖不到的那一半,也是 1.2.0 起才有的兜底;</li>
 *   <li><b>下次进游戏的孤儿清扫</b>({@link #sweepOrphans}):万一前两条都没赶上
 *       (比如后端版本太老、没带看门狗),下一局开局把它收掉再重新拉起。</li>
 * </ol>
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

    /**
     * 调用一次:装好了就隐藏拉起,没装就留给按需下载链路。
     *
     * <p><b>本方法不阻塞</b>:调用点在客户端初始化线程上(以及玩家点来源按钮的那一下),
     * 而它内部要做健康探测(有超时)与双向 Cookie 同步(两次 HTTP)。这些全部放进后台线程,
     * 调用方拿到的是一次立即返回。判定与拉起的状态机本身是幂等的,晚几十毫秒无影响。</p>
     */
    public static void ensureStartedForGame() {
        MultiThreadingUtil.runAsync(QishuiBackendLifecycle::ensureStartedForGameBlocking);
    }

    /** 真正的启动判定。只允许后台线程调用——探测、Cookie 同步、进程等待都在这里。 */
    private static void ensureStartedForGameBlocking() {
        if (ownedProcess != null && ownedProcess.isAlive()) return; // 自己拉起的还活着
        // 已装但伴生包版本落后 → 后台热补(不阻塞启动,失败静默下次重试)。
        QishuiCompanionDownload.refreshIfNeeded();
        // 安装目录保养:启用上次热补腾挪的 .new、按清单收掉旧版残留(改名前后的运行时、
        // 上游删掉的脚本)。删不掉的(旧实例还占着的 exe)下一局再收——所以必须每局跑。
        QishuiCompanionDownload.maintainInstallRoot();
        // 上面的后台启动判定必须以这一刻的新鲜探活为准，不能把上次的 false/true 缓存
        // 当成当前监听状态；否则会漏掉刚启动的服务，或对已在运行的后端重复拉起。
        QishuiBackend.invalidateProbe();
        if (QishuiBackend.isAvailable()) {
            if (sweepOrphans()) {
                // 上一局的孤儿刚被收掉,8787 已空——这一局要重新拉起自己的实例。
                startOwnedQuietly();
                return;
            }
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
        startOwnedQuietly();
    }

    /**
     * 清理「上一局遗留、父进程已死」的孤儿后端;杀了返回 true,没杀返回 false。
     *
     * <p>判据是后端自报的 {@code parent_pid}:带看门狗拉起的实例会报出它的父进程
     * (上一局的游戏 JVM)。父进程不存在 = 它本该随上一局退出却没退——要么是强杀时
     * 看门狗也没赶上,要么是它压根还是不带看门狗的旧版本(那种实例 parent_pid 报 0,
     * 这里不动它,按「用户手动开的」尊重处理)。</p>
     *
     * <p>只杀 127.0.0.1 上自己报出来的这个 pid,并且动手前再核一次「它确实没有父进程」
     * ——探测缓存最长 30 秒,期间父进程完全可能又起来了,不能拿旧结论去杀活进程。</p>
     */
    private static boolean sweepOrphans() {
        long backendPid = QishuiBackend.peekBackendPid();
        long parentPid = QishuiBackend.peekParentPid();
        if (backendPid <= 0 || parentPid <= 0 || !QishuiBackend.peekWatchdog()) {
            // 老版本后端(没有这些自报字段)或手动启动(parent_pid=0):不接管。
            return false;
        }
        if (ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
            // 父进程还活着——那是本机另一个游戏实例/进程的正常后端,不是孤儿。
            return false;
        }
        ProcessHandle orphan = ProcessHandle.of(backendPid).orElse(null);
        if (orphan == null || !orphan.isAlive()) return false;
        // 二次确认父进程仍然不存在(探测结论可能已过期),避免误杀刚被新实例接管的进程。
        if (ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) return false;
        System.out.println("[MuoniumPlayer/QishuiBackend] 收掉上一局遗留的后端 pid=" + backendPid
                + "(父进程 " + parentPid + " 已不存在)");
        boolean killed = orphan.destroy();
        if (!killed) {
            orphan.destroyForcibly();
        }
        // 等端口真正放开再让调用方拉起新实例——不然新进程会撞上「8787 被占用」，
        // 首启那 10 秒健康检查窗口就白等了。用无副作用的探活轮询，最多 5 秒。
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && QishuiBackend.probeAlive()) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        QishuiBackend.invalidateProbe();
        QishuiBackendLifecycle.lastSpawnAtMillis = 0L; // 让本次拉起不受 15 秒冷却影响
        return true;
    }

    /** {@link #startOwned()} 的吞异常包装:失败要记日志,但不能打断调用它的启动链路。 */
    private static void startOwnedQuietly() {
        try {
            startOwned();
        } catch (Throwable error) {
            String detail = "启动异常：" + error.getMessage();
            QishuiCompanionDownload.markBackendFailed(detail);
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendFailure(detail);
            System.err.println("[MuoniumPlayer/QishuiBackend] 随游戏启动后端失败: " + error);
        }
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
        File rootAbs = installRootAbs();
        QishuiCompanionDownload.markBackendStarting();
        ownedProcess = spawn(rootAbs);
        noteSpawn();
        System.out.println("[MuoniumPlayer/QishuiBackend] 后端已随游戏拉起 pid=" + ownedProcess.pid());
        // 等健康检查通过(首启要建 config,给 10 秒),再把游戏内的 Cookie 推过去配对。
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (QishuiBackend.probeAlive()) {
                QishuiBackend.onBackendUp();
                if (QishuiBackend.peekAvailable()) {
                    QishuiCompanionDownload.markBackendRunning();
                } else {
                    QishuiCompanionDownload.markBackendFailed("同步账号状态时后端失去响应");
                }
                return;
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                QishuiCompanionDownload.markBackendFailed("启动等待被中断");
                return;
            }
        }
        QishuiCompanionDownload.markBackendFailed("进程已启动但健康检查未通过");
        com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendFailure("进程已启动但健康检查未通过");
        System.err.println("[MuoniumPlayer/QishuiBackend] 后端进程已启动但健康检查未通过,"
                + "详见 " + new File(rootAbs, "backend.log"));
    }

    private static File installRootAbs() {
        return QishuiCompanionDownload.installRoot().getAbsoluteFile();
    }

    /**
     * 用安装目录里的运行时把 {@code server.mjs} 拉起来。
     *
     * <p>两条看门狗标志都带上,与模组侧的所有权登记互补:关闭钩子负责正常退出,
     * 看门狗负责钩子跑不到的那些死法(强杀、崩溃)。{@code --parent-pid} 传的是本 JVM,
     * 后端每 2 秒核一次它在不在;stdin 管道则作为第二条通道,父进程一消失立刻 EOF。</p>
     *
     * <p><b>路径必须绝对</b>:游戏进程的 CWD 与模组文件操作的解析目录不一定是同一个,
     * 相对的 {@code directory()} 会让 CreateProcess 报「系统找不到指定的文件」(真机踩过)。</p>
     */
    static Process spawn(File rootAbs) throws IOException {
        File runtime = QishuiCompanionDownload.findRuntime(rootAbs);
        if (runtime == null) {
            throw new IOException("安装目录里没有 Node 运行时: " + rootAbs.getAbsolutePath());
        }
        File server = new File(rootAbs, "server.mjs");
        if (!server.isFile()) {
            throw new IOException("server.mjs 不存在: " + server.getAbsolutePath());
        }
        ProcessBuilder builder = new ProcessBuilder(
                runtime.getAbsolutePath(),
                server.getAbsolutePath(),
                "--watchdog-stdin",
                "--parent-pid", Long.toString(ProcessHandle.current().pid()))
                .directory(rootAbs)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(rootAbs, "backend.log")));
        builder.environment().put("QISHUI_CLIENT_DIR", "");
        return builder.start();
    }

    /** 伴生包下载器拉起的进程也归本类管(关游戏时一并收掉)。 */
    static void adoptOwnedProcess(Process process) {
        ownedProcess = process;
    }
}
