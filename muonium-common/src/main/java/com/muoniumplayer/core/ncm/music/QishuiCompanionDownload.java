package com.muoniumplayer.core.ncm.music;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 汽水签名后端（qs-backend）的「伴生包」自动下载器与托管启动。
 *
 * <h2>为什么需要它</h2>
 * 汽水 {@code /luna/pc/*} 接口的应用级签名（{@code X-Helios}/{@code X-Medusa}）只有
 * 官方客户端自带的 {@code bdms.node} 算得出来，Java 侧复现不了。签名这件事被抽成
 * 本机服务 qs-backend（Node），由它代表模组完成「拼 URL → 签名 → 带 Cookie 转发」。
 * 服务本体（含官方 Node 运行时与桥接器，约 33 MB）以「伴生包」形式托管在公开清单
 * 仓库 {@code MuoniumPlayer-updates} 的 {@code qishui-companion/} 目录：客户端在
 * <b>本机没有可用的后端</b>时自动下载、校验字节数与 sha256、解压到
 * {@code config/muonium/qishui-backend/} 并作为独立进程拉起——与 Skija 原生库
 * 伴生包（{@link com.muoniumplayer.render.skija.SkijaCompanionDownload}）同一套链路，
 * 区别只在生效方式：后端是<b>独立进程</b>，不需要重启游戏，装好即探即用。
 *
 * <h2>与 sidecar 教训的边界</h2>
 * 之前一版汽水 sidecar 因「失败后静默回落」当夜回滚。本类遵守同一条红线：
 * <b>只负责把包装好、把进程拉起来、把结果说清楚</b>；启动失败、健康检查不过、
 * Cookie 缺失都会落到 {@link #state()} 与日志里由提示层呈现，绝不替用户假装成功。
 * 后端进程本身独立于游戏存续，玩家关掉 start.bat 窗口即停。
 *
 * <h2>触发条件（同时满足才下载）</h2>
 * <ol>
 *   <li>{@link QishuiBackend#isAvailable()} 为假（本机 8787 无健康后端）；</li>
 *   <li>安装目录里没有完整的 server.mjs + node.exe（有则只尝试直接拉起，不重复下载）；</li>
 *   <li>没有被 {@value #PROP_ENABLED} 显式关掉；</li>
 *   <li>不在下载中，且本会话的尝试预算与冷却时间还允许。</li>
 * </ol>
 *
 * <h2>钉死字节数与 sha256</h2>
 * 下载的是「会以本机权限执行的可执行运行时」，与更新清单同一标准：字节数与 sha256
 * 钉死在 {@link #PACK}，解压后再做内容级核对（server.mjs 存在、node.exe 体量合理）。
 * 换包时三处必须一起改：清单仓库里的 zip、本类常量、发布产物；
 * 换包后旧版本号留着也行，{@code installMarker} 只记当前版本。
 */
public final class QishuiCompanionDownload {

    /** 下载镜像链，与 Skija 伴生包同一套三源（ghfast 代理 / raw / jsdelivr 等）。 */
    static final List<String> MIRRORS = List.of(
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/qishui-companion/");

    /** 关闭自动下载的开关（默认开，只有显式 {@code =false} 才关）。 */
    static final String PROP_ENABLED = "muonium.qishui.autoDownload";

    /** 开发期替代源：从一个本地目录取包，不经网络。 */
    static final String PROP_LOCAL_DIR = "muonium.qishui.companionDir";

    /** 本会话内最多尝试几次。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 两次尝试之间的冷却。 */
    private static final long RETRY_COOLDOWN_NANOS = 3L * 60L * 1_000_000_000L;
    /** 暂存目录前缀。 */
    private static final String INCOMING_PREFIX = ".incoming-";

    /** 伴生包：文件名 + 字节数 + sha256，三个都必须精确。 */
    record Pack(String fileName, long bytes, String sha256) {
    }

    /** 1.0.0：Node v22.23.3 运行时 + qs-backend 脚本 + 解密器 + start.bat。
     *  1.0.1：server.mjs 补 Cookie 自动同步三件套（约定路径自动导入、config.json
     *  热重读、/api/cookie/full 反向导出）——修复「登录工具写了 Cookie 但游戏后端
     *  一直 1000016 登录失效」的断链。运行时 node.exe 与 1.0.0 相同。
     *  1.1.0：/api/stream 支持 ?quality=&lt;kbps&gt; 选档（≥目标的最小档，缺省最高），
     *  缓存键按档位区分；电台曲目改走 PC 签名代理优先。 */
    static final Pack PACK = new Pack("qishui-backend-windows-x64-1.1.0.zip", 33_013_552L,
            "d8f95d4db391be9cfd2c11b15382802a7dcb7dbca5bf7df3c2fc5715f207bd8b");

    /** 下载状态机；提示层与日志照它说话。 */
    enum Phase {
        IDLE, DOWNLOADING, INSTALLING, STARTING, RUNNING, FAILED
    }

    /** 状态快照：{@code percent} 0~100，-1 表示未知。 */
    record State(Phase phase, int percent, String detail) {
    }

    private static final Object LOCK = new Object();
    private static volatile State state = new State(Phase.IDLE, 0, null);
    private static boolean inFlight;
    private static int attempts;
    private static long nextAttemptAtNanos;

    private QishuiCompanionDownload() {
    }

    public static State state() {
        return state;
    }

    /** 给音源选择卡的醒目提示行:下载/安装/失败时非空,常态为空串。公开给 UI 包用。 */
    public static String backendInstallStatusLine() {
        State snapshot = state;
        return switch (snapshot.phase()) {
            case DOWNLOADING -> "正在下载签名后端 " + Math.max(0, snapshot.percent()) + "%…";
            case INSTALLING -> "后端下载完成，正在安装…";
            case STARTING -> "后端安装完成，正在启动…";
            case FAILED -> "后端安装失败" + (snapshot.detail() == null || snapshot.detail().isEmpty() ? "" : "(" + snapshot.detail() + ")") + " · 自动重试中，期间仅试听";
            default -> "";
        };
    }

    static boolean enabled() {
        String value = System.getProperty(PROP_ENABLED);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /** 伴生包安装根：{@code config/muonium/qishui-backend/}，与 zip 内布局对齐。 */
    static File installRoot() {
        return new File(new File("config", "muonium"), "qishui-backend");
    }

    /** 安装目录是否已有完整的后端文件（脚本 + 运行时）。 */
    static boolean isInstalled() {
        File root = installRoot();
        File server = new File(root, "server.mjs");
        File node = new File(root, "node.exe");
        return server.isFile() && server.length() > 10_000L
                && node.isFile() && node.length() > 50_000_000L;
    }

    private static File versionMarker() {
        return new File(installRoot(), "pack.version");
    }

    /** 安装目录是否已是当前 {@link #PACK} 版本；缺标记的旧安装视为待升级。 */
    static boolean isUpToDate() {
        if (!isInstalled()) return false;
        try {
            return PACK.fileName().equals(new String(Files.readAllBytes(versionMarker().toPath()),
                    StandardCharsets.UTF_8).trim());
        } catch (IOException missing) {
            return false;
        }
    }

    private static void writeVersionMarker() {
        try {
            Files.write(versionMarker().toPath(), PACK.fileName().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 标记写失败顶多让下次启动多做一次热补，不致命。
        }
    }

    /**
     * 后台热补：已安装但 pack.version 落后于当前 {@link #PACK} 时，拉新包把脚本层
     * 换上去（1.0.1 的 Cookie 同步修复就是这么送达存量安装的）。
     *
     * <p>与首次安装的三点不同：<b>绝不阻塞启动</b>（旧脚本当场就能用，新脚本下次
     * 拉起生效）、<b>失败不弹提示</b>（下次启动重试）、node.exe 被运行中的后端占用时
     * 只跳过该文件（脚本层才是热补目标，运行时本身没换版本）。</p>
     */
    static void refreshIfNeeded() {
        if (!isInstalled() || isUpToDate()) return;
        synchronized (LOCK) {
            if (inFlight) return;
            inFlight = true;
        }
        Thread worker = new Thread(() -> {
            try {
                File root = installRoot();
                log("检测到后端伴生包待升级 → " + PACK.fileName());
                File archive = new File(root.getParentFile(), PACK.fileName() + ".part");
                fetch(PACK, archive);
                deleteStaleIncoming(root.getParentFile());
                File incoming = new File(root.getParentFile(), INCOMING_PREFIX + Long.toHexString(System.nanoTime()));
                unzip(archive, incoming);
                verifyExtracted(incoming);
                copyTreeTolerant(new File(incoming, "qishui-backend"), root);
                deleteRecursively(incoming);
                Files.deleteIfExists(archive.toPath());
                if (!new File(root, "server.mjs").isFile()) {
                    throw new IOException("热补后 server.mjs 缺失：" + root.getAbsolutePath());
                }
                writeVersionMarker();
                log("后端伴生包已热补到 " + PACK.fileName() + "（正在运行的旧进程下次拉起换新）");
            } catch (Throwable failure) {
                log("后端伴生包后台升级失败（下次启动重试）：" + describe(failure));
            } finally {
                synchronized (LOCK) {
                    inFlight = false;
                }
            }
        }, "MuoniumPlayer-QishuiBackendRefresh");
        worker.setDaemon(true);
        worker.start();
    }

    /** 容错复制：单个文件被占用（比如正在运行的 node.exe）只记日志不中断整树。 */
    private static void copyTreeTolerant(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                Files.createDirectories(target.toPath());
                copyTreeTolerant(child, target);
            } else {
                try {
                    Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException busy) {
                    log("热补跳过被占用的文件：" + target.getName());
                }
            }
        }
    }

    /**
     * 后端不可用时的入口：装好的就拉起，没装的就下载后拉起。
     * 所有判断在调用线程做，下载/解压/启动在后台线程。
     */
    static void onBackendMissing() {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            if (inFlight || attempts >= MAX_ATTEMPTS) {
                return;
            }
            long now = System.nanoTime();
            if (now < nextAttemptAtNanos) {
                return;
            }
            attempts++;
            inFlight = true;
            nextAttemptAtNanos = now + RETRY_COOLDOWN_NANOS;
        }
        Thread worker = new Thread(() -> run(), "MuoniumPlayer-QishuiBackend");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 下载（缺包时）→ 校验 → 解压 → 内容核对 → 拉起进程 → 等健康检查。
     * 全程后台线程。
     */
    private static void run() {
        try {
            File root = installRoot();
            if (!isInstalled()) {
                Files.createDirectories(root.toPath());
                state = new State(Phase.DOWNLOADING, 0, null);
                announceProgress(0);
                log("开始自动下载 " + PACK.fileName() + "（镜像链 " + MIRRORS.size() + " 条）");
                File archive = new File(root.getParentFile(), PACK.fileName() + ".part");
                fetch(PACK, archive);
                state = new State(Phase.INSTALLING, 100, null);
                com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendProgress(100);
                deleteStaleIncoming(root.getParentFile());
                File incoming = new File(root.getParentFile(), INCOMING_PREFIX + Long.toHexString(System.nanoTime()));
                unzip(archive, incoming);
                verifyExtracted(incoming);
                // zip 根目录就是 qishui-backend/：把 incoming/qishui-backend 整个并成
                // config/muonium/qishui-backend/。曾把目标写成 root.getParentFile()——
                // node.exe/server.mjs 全被装到了 config/muonium/ 根下，装好后
                // startBackend 找不到 node.exe 报 CreateProcess error=2（真机踩过）。
                // 上一版错误安装曾把后端文件散落在 config/muonium/ 根下，装前清掉。
                deleteLegacyStrays(root.getParentFile());
                copyTree(new File(incoming, "qishui-backend"), root);
                deleteRecursively(incoming);
                Files.deleteIfExists(archive.toPath());
                // 装完即验最终落点:上面那次 copyTree 目标写错一层时,verifyExtracted
                // (只查 incoming)拦不住,必须在最终路径上再核一遍。
                if (!new File(root, "node.exe").isFile()
                        || !new File(root, "server.mjs").isFile()) {
                    throw new IOException("安装落点校验失败：" + root.getAbsolutePath()
                            + " 下缺 node.exe/server.mjs");
                }
                log("已装好 " + PACK.fileName() + " → " + root.getAbsolutePath());
                writeVersionMarker();
            }
            state = new State(Phase.STARTING, 100, null);
            Process process = startBackend(root);
            if (process != null) QishuiBackendLifecycle.adoptOwnedProcess(process);
            if (awaitHealthy(15_000L)) {
                state = new State(Phase.RUNNING, 100, null);
                log("后端已就绪 " + QishuiBackend.baseUrl());
                com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendReady();
                QishuiBackend.onBackendUp();
            } else {
                state = new State(Phase.FAILED, 0, "后端进程已拉起但健康检查未通过");
                com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendFailure(state.detail());
                log(state.detail());
            }
        } catch (Throwable failure) {
            state = new State(Phase.FAILED, 0, safeMessage(failure));
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendFailure(safeMessage(failure));
            log("自动安装失败：" + describe(failure));
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /** 用包内 node.exe 把 server.mjs 拉成独立进程；输出重定向到 backend.log 供排查。
     *  已有健康后端时不重复拉起返回 null；进程所有权交给 {@link QishuiBackendLifecycle} 登记。
     *  <b>路径必须绝对</b>：游戏进程的 CWD 与模组文件操作的解析目录（游戏运行目录）不一定是
     *  同一个，相对的 {@code directory()} 会让 CreateProcess 直接报「系统找不到指定的文件」
     *  （真机 2026-10-11 的教训）。 */
    private static Process startBackend(File root) throws IOException {        if (QishuiBackend.isAvailable() || QishuiBackendLifecycle.recentlySpawned()) {
            log("8787 已有健康后端或另一组件刚拉起，不重复拉起");
            return null;
        }
        File rootAbs = root.getAbsoluteFile();
        File node = new File(rootAbs, "node.exe");
        File server = new File(rootAbs, "server.mjs");
        File logFile = new File(rootAbs, "backend.log");
        if (!node.isFile()) {
            throw new IOException("node.exe 不存在: " + node.getAbsolutePath());
        }
        ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), server.getAbsolutePath())
                .directory(rootAbs)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        builder.environment().put("QISHUI_CLIENT_DIR", "");
        Process process = builder.start();
        QishuiBackendLifecycle.noteSpawn();
        log("后端进程已拉起 pid=" + process.pid() + " → " + rootAbs.getAbsolutePath());
        return process;
    }

    /** 等健康检查通过；期间每 500ms 探一次。 */
    private static boolean awaitHealthy(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (QishuiBackend.isAvailable()) return true;
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 取包到 {@code archive}：本地替代源优先，否则镜像链逐个试（字节数 + sha256 全验）。 */
    private static void fetch(Pack pack, File archive) throws Exception {
        String localDir = System.getProperty(PROP_LOCAL_DIR);
        if (localDir != null && !localDir.isBlank()) {
            File local = new File(localDir, pack.fileName());
            if (!local.isFile()) {
                throw new IOException("本地伴生包目录里没有 " + pack.fileName() + "：" + localDir);
            }
            checkAgainst(pack, local.length(), sha256(local));
            Files.copy(local.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Exception last = null;
        for (String mirror : MIRRORS) {
            try {
                download(pack, mirror + pack.fileName(), archive);
                return;
            } catch (Exception failure) {
                last = failure;
                try {
                    Files.deleteIfExists(archive.toPath());
                } catch (Throwable ignored) {
                }
            }
        }
        throw last == null ? new IOException("没有可用的下载源") : last;
    }

    /** 单次下载：读流与校验同步走，进度按整百分比推进。 */
    private static void download(Pack pack, String url, File archive) throws Exception {
        HttpURLConnection connection = openFollowing(URI.create(url).toURL());
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        if (total > 0 && total != pack.bytes()) {
            throw new IOException("大小不符（Content-Length " + total + "，应为 " + pack.bytes() + "）");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long done = 0L;
        int shown = -1;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                if (pack.bytes() > 0 && done > pack.bytes()) {
                    throw new IOException("下超了：已收 " + done + " 字节，应为 " + pack.bytes());
                }
                if (pack.bytes() > 0) {
                    int percent = (int) Math.min(100L, done * 100L / pack.bytes());
                    if (percent != shown) {
                        shown = percent;
                        state = new State(Phase.DOWNLOADING, percent, null);
                        announceProgress(percent);
                    }
                }
            }
        }
        checkAgainst(pack, archive.length(), hex(digest.digest()));
    }

    /** 重定向自己跟（镜像会 30x 到 CDN），最多 6 跳。 */
    private static HttpURLConnection openFollowing(URL url) throws Exception {
        return openFollowing(url, 0);
    }

    private static HttpURLConnection openFollowing(URL url, int depth) throws Exception {
        if (depth > 6) {
            throw new IOException("重定向过多");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-QishuiBackend/1.0");
        int code = connection.getResponseCode();
        if (code >= 300 && code < 400) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("重定向没有 Location");
            }
            return openFollowing(new URL(url, location), depth + 1);
        }
        return connection;
    }

    /** 并入前核对包内结构：server.mjs、node.exe、解密器三件套必须在，体量要合理。 */
    private static void verifyExtracted(File incoming) throws IOException {
        File root = new File(incoming, "qishui-backend");
        File server = new File(root, "server.mjs");
        File node = new File(root, "node.exe");
        File decryptor = new File(root, "qishui-audio-decryptor" + File.separator + "track-decryptor.js");
        if (!server.isFile() || server.length() < 10_000L) {
            throw new IOException("包内缺 server.mjs 或体量异常");
        }
        if (!node.isFile() || node.length() < 50_000_000L) {
            throw new IOException("包内缺 node.exe 或体量异常");
        }
        if (!decryptor.isFile()) {
            throw new IOException("包内缺解密器 track-decryptor.js");
        }
    }

    /** 清掉上一次失败留下的暂存目录。 */
    private static void deleteStaleIncoming(File parent) {
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(INCOMING_PREFIX)) {
                deleteRecursively(child);
            }
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                Files.createDirectories(target.toPath());
                copyTree(child, target);
            } else {
                Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 解压（自带 zip-slip 防护，与 Skija 安装器同款形状）。 */
    private static void unzip(File archive, File target) throws Exception {
        if (!target.exists() && !target.mkdirs() && !target.isDirectory()) {
            throw new IOException("无法创建暂存目录: " + target);
        }
        String root = target.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(target, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new IOException("不安全的压缩包条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream output = new FileOutputStream(out)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Throwable ignored) {
        }
    }

    private static void checkAgainst(Pack pack, long bytes, String sha256) throws IOException {
        if (bytes != pack.bytes()) {
            throw new IOException("大小不符：得到 " + bytes + " 字节，应为 " + pack.bytes());
        }
        if (!pack.sha256().equalsIgnoreCase(sha256)) {
            throw new IOException("sha256 不符：得到 " + sha256);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("本机 JRE 没有 SHA-256", impossible);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    /** 进度推送节流:动态岛是同频道替换式更新,别每个字节都推一次。 */
    private static long lastNoticeAtMillis;

    private static void announceProgress(int percent) {
        long now = System.currentTimeMillis();
        if (now - lastNoticeAtMillis < 400L && percent < 100) {
            return;
        }
        lastNoticeAtMillis = now;
        com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendProgress(percent);
    }

    /** 清掉错误安装散落在 config/muonium/ 根下的后端文件(只认名单,不误伤)。 */
    private static void deleteLegacyStrays(File configRoot) {
        String[] strayNames = {"node.exe", "server.mjs", "mssdk-bridge.mjs", "start.bat",
                "README.txt", "config.note.txt"};
        for (String name : strayNames) {
            try {
                Files.deleteIfExists(new File(configRoot, name).toPath());
            } catch (Throwable ignored) {
            }
        }
        deleteRecursively(new File(configRoot, "qishui-audio-decryptor"));
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    private static void log(String message) {
        System.out.println("[MuoniumPlayer/QishuiCompanion] " + message);
    }
}
