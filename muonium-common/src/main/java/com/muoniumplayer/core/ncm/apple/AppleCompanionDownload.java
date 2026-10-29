package com.muoniumplayer.core.ncm.apple;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Apple Music 伴生后端（gamdl 整包）的下载与安装 —— 汽水伴生后端（{@code
 * QishuiCompanionDownload}）的同款目录约定与状态机，压缩到本平台实际需要的子集。
 *
 * <h3>为什么是「整包」而不是运行时装 Python</h3>
 * <p>gamdl 是 Python 应用，但用户机器上不该出现 pip、venv、镜像源这些词。上游
 * {@code tools/build-apple-companion.ps1} 把内嵌 Python + 预装好的 gamdl 打成一个
 * 自包含 zip（PyInstaller onedir），这里只做：镜像梯队下载 → sha256 校验 → 解压 →
 * 真跑一次 {@code gamdl --version} 验证。用户点一下「一键安装」，其余全自动。</p>
 *
 * <h3>状态机与提示层的关系</h3>
 * <p>与汽水/ffmpeg 完全同一套纪律：全部字段 volatile，UI 每帧轮询 {@link #state()}，
 * 绝不回调、绝不阻塞渲染线程；失败原因写进 {@code detail}，由设置页如实呈现。</p>
 */
public final class AppleCompanionDownload {

    /**
     * 下载镜像链 —— 与汽水伴生包同一套（R2 直链 / 官网 / gh-proxy / ghfast / jsdelivr /
     * ghproxy.net / GitHub raw），整包发布到 {@code MuoniumPlayer-updates} 仓库的
     * {@code apple-companion/} 目录与 R2 桶。
     */
    static final List<String> MIRRORS = List.of(
            "https://file.melodifymc.cc.cd/apple-companion/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/apple-companion/",
            "http://muoniummc.cc.cd/dl/apple-companion/",
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/apple-companion/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/apple-companion/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/apple-companion/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/apple-companion/");

    /** 关闭自动下载的开关（默认开，只有显式 {@code =false} 才关）。 */
    static final String PROP_ENABLED = "muonium.apple.autoDownload";

    /** 开发期替代源：从一个本地目录取包，不经网络（打包脚本调试用）。 */
    static final String PROP_LOCAL_DIR = "muonium.apple.companionDir";

    /** 本会话内最多尝试几次。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 两次尝试之间的冷却。 */
    private static final long RETRY_COOLDOWN_NANOS = 3L * 60L * 1_000_000_000L;
    /** 暂存目录前缀。 */
    private static final String INCOMING_PREFIX = ".incoming-";

    /** 整包内 gamdl 可执行文件的固定位置（PyInstaller onedir 布局）。 */
    static final String EXECUTABLE_ENTRY = "gamdl/gamdl.exe";
    /** 启动器本体不至于小于 1MB；解压后做尺寸下限 sanity check。 */
    static final long EXECUTABLE_MIN_BYTES = 1L * 1024L * 1024L;
    /** `gamdl --version` 的验证超时。 */
    private static final long VERIFY_TIMEOUT_MILLIS = 30_000L;

    /**
     * 伴生包：文件名 + 字节数 + sha256，三个都必须精确。
     *
     * <p>1.0.2：1.0.1 + {@code _internal/amp_api.py} —— amp-api 按 TLS/JA3 指纹拒绝
     * Java 客户端（Java 25 的 HttpURLConnection 打任何端点稳定 401，逐头二分排除后
     * 确证；Python 同凭据同头 200），mod 的 probe/搜索/歌单/歌词改为子进程调用这份
     * 助手（Python 指纹放行）。1.0.1：入口 shim 加 {@code multiprocessing.freeze_support()}
     * —— 没有它，yt-dlp 的分片下载子进程带着 {@code --multiprocessing-fork} argv
     * 重启本 exe，click 报 "No such option" 导致任何真实下载必挂（真实 cookies E2E
     * 踩中）。整包由 {@code tools/build-apple-companion.ps1} 产出（确定性 zip：
     * SOURCE_DATE_EPOCH + 条目排序 + base_library.zip 重写），三处（PACK / 产物 /
     * 清单仓库）必须对齐，Apple 改版需要换 gamdl 时 bump 版本重跑脚本即可。</p> */
    static final Pack PACK = new Pack("apple-gamdl-companion-windows-x64-1.0.2.zip", 33_464_108L,
            "ca69de923f0997782aceadca658f21b226d2065c241cfdfe3ed886a3d1f64213");

    /** 伴生包元组。 */
    record Pack(String fileName, long bytes, String sha256) {
    }

    /** 下载状态机；提示层与日志照它说话。 */
    enum Phase {
        IDLE, DOWNLOADING, INSTALLING, VERIFYING, READY, FAILED
    }

    /** 状态快照：{@code percent} 0~100，-1 表示未知。 */
    record State(Phase phase, int percent, String detail) {
    }

    private static volatile State current = new State(Phase.IDLE, 0, "");
    private static volatile long lastAttemptNanos;
    /** 本会话已通过 {@code gamdl --version} 实测；重探用 {@link #resetVerified()}。 */
    private static volatile boolean verified;

    private AppleCompanionDownload() {
    }

    public static State state() {
        return current;
    }

    private static void update(Phase phase, int percent, String detail) {
        current = new State(phase, percent, detail == null ? "" : detail);
    }

    /** 平台门：整包只覆盖 Windows x64（Android 与其他桌面系统走手动路径降级）。 */
    public static boolean supportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    static File installRoot() {
        return ConfigPaths.APPLE_MUSIC_DIR;
    }

    private static File versionMarkerFile() {
        return new File(installRoot(), "pack.version");
    }

    /** 整包已解压在位（不看版本）。 */
    public static boolean isInstalled() {
        File exe = new File(installRoot(), EXECUTABLE_ENTRY);
        return exe.isFile() && exe.length() >= EXECUTABLE_MIN_BYTES;
    }

    /** 在位且版本标记与当前 pinned 包一致。 */
    public static boolean isUpToDate() {
        if (!isInstalled()) return false;
        try {
            String marker = new String(Files.readAllBytes(versionMarkerFile().toPath()),
                    StandardCharsets.UTF_8).trim();
            return marker.equals(PACK.fileName());
        } catch (IOException throwable) {
            return false;
        }
    }

    /** 本会话是否已实测可用（安装器装完验一次；桥接首次成功也记账）。 */
    public static boolean isVerified() {
        return verified;
    }

    /** 伴生是否可用：整包在位且版本一致且实测通过。 */
    public static boolean isReady() {
        return supportedPlatform() && isUpToDate() && verified;
    }

    /** 可用的 gamdl 可执行文件；不可用返回 null（设置页可选手动路径是另一条降级线）。 */
    public static File executableOrNull() {
        if (!supportedPlatform()) return null;
        if (isUpToDate()) {
            return new File(installRoot(), EXECUTABLE_ENTRY);
        }
        return null;
    }

    /** 后台静默确保安装：就绪 / 平台不符 / 未发布 / 冷却中 / 在跑都直接返回。 */
    public static void ensureInstalledAsync() {
        if (!shouldAutoInstall()) return;
        Thread thread = new Thread(AppleCompanionDownload::runInstallWithAttempts,
                "muonium-apple-companion-install");
        thread.setDaemon(true);
        thread.start();
    }

    /** 设置页「一键安装」强制入口：无视冷却，重新拉起安装线程。 */
    public static void startManualInstall() {
        if (!supportedPlatform()) {
            update(Phase.FAILED, -1, "仅支持 Windows；其他系统请在设置里手动指定 gamdl 路径");
            return;
        }
        if (PACK.sha256().isEmpty()) {
            update(Phase.FAILED, -1, "整包尚未发布：先运行 tools/build-apple-companion.ps1 并回填 PACK 常量");
            return;
        }
        Thread thread = new Thread(AppleCompanionDownload::runInstallWithAttempts,
                "muonium-apple-companion-install");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean shouldAutoInstall() {
        if (!supportedPlatform()) return false;
        if (isReady()) return false;
        if (PACK.sha256().isEmpty()) return false; // 上游还没产出首个整包。
        if (!"false".equalsIgnoreCase(System.getProperty(PROP_ENABLED, "true"))) return false;
        long now = System.nanoTime();
        if (now - lastAttemptNanos < RETRY_COOLDOWN_NANOS) return false;
        State snapshot = current;
        if (snapshot.phase() == Phase.DOWNLOADING || snapshot.phase() == Phase.INSTALLING
                || snapshot.phase() == Phase.VERIFYING) return false;
        return true;
    }

    private static void runInstallWithAttempts() {
        synchronized (AppleCompanionDownload.class) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                lastAttemptNanos = System.nanoTime();
                try {
                    if (runInstall()) return;
                } catch (Throwable throwable) {
                    System.err.println("[Music/Apple] 伴生安装第 " + attempt + " 次失败: " + throwable);
                    update(Phase.FAILED, -1, safeMessage(throwable));
                }
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(2_000L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            update(Phase.FAILED, -1, "连续 " + MAX_ATTEMPTS + " 次失败，稍后随会话自动重试或手动再装");
        }
    }

    /** 单次安装流水线。返回 true = 装好并验证通过。 */
    private static boolean runInstall() throws IOException {
        File root = installRoot();
        if (!root.isDirectory() && !root.mkdirs()) {
            update(Phase.FAILED, -1, "无法创建安装目录 " + root.getAbsolutePath());
            return false;
        }
        File packFile = new File(root, PACK.fileName() + ".part");
        try {
            update(Phase.DOWNLOADING, 0, "正在下载 " + PACK.fileName());
            fetchPack(packFile);
            update(Phase.INSTALLING, -1, "正在解压");
            File incoming = new File(root, INCOMING_PREFIX + Long.toHexString(System.nanoTime()));
            unpack(packFile, incoming);
            verifyExtracted(incoming);
            promote(incoming, root);
            writeVersionMarker();
            update(Phase.VERIFYING, -1, "正在验证 gamdl");
            if (!verifyExecutable(new File(root, EXECUTABLE_ENTRY))) {
                throw new IOException("gamdl --version 验证失败（整包损坏或缺少依赖）");
            }
            verified = true;
            update(Phase.READY, 100, "gamdl " + PACK.fileName());
            System.out.println("[Music/Apple] 伴生后端安装完成: " + PACK.fileName());
            return true;
        } finally {
            try {
                Files.deleteIfExists(packFile.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    // ── 下载（重定向跟随后 + 流式 sha256 + 字节数校验） ───────────────────────

    private static void fetchPack(File target) throws IOException {
        // 开发目录守卫必须判「属性真的设了且非空」：new File("") 解析为 CWD，
        // run 目录恰好是个目录 —— 空守卫会让正常用户的下载永远死在
        // 「开发目录 里没有 xxx.zip」，网络链路一次都跑不到（26.3 实机日志踩中）。
        String localDir = System.getProperty(PROP_LOCAL_DIR);
        if (localDir != null && !localDir.isBlank()) {
            File local = new File(localDir, PACK.fileName());
            if (local.isFile()) {
                Files.copy(local.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                verifyPackBytes(target);
                return;
            }
            throw new IOException("开发目录 " + localDir + " 里没有 " + PACK.fileName());
        }

        IOException lastError = null;
        for (String mirror : MIRRORS) {
            try {
                downloadFollowing(mirror + PACK.fileName(), target);
                verifyPackBytes(target);
                return;
            } catch (IOException throwable) {
                lastError = throwable;
                System.err.println("[Music/Apple] 镜像失败 " + mirror + ": " + throwable.getMessage());
            }
        }
        throw lastError != null ? lastError : new IOException("所有镜像均不可用");
    }

    private static void verifyPackBytes(File file) throws IOException {
        long actual = file.length();
        if (PACK.bytes() > 0 && actual != PACK.bytes()) {
            throw new IOException("整包字节数不符: 期望 " + PACK.bytes() + " 实际 " + actual);
        }
        if (!PACK.sha256().isEmpty()) {
            String digest = sha256Hex(file);
            if (!digest.equalsIgnoreCase(PACK.sha256())) {
                throw new IOException("整包 sha256 不符: 期望 " + PACK.sha256() + " 实际 " + digest);
            }
        }
    }

    private static void downloadFollowing(String url, File target) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            long total = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long readTotal = 0L;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    readTotal += read;
                    if (total > 0) {
                        update(Phase.DOWNLOADING,
                                (int) Math.min(99L, readTotal * 100L / total), "正在下载");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MuoniumPlayer-AppleCompanion/1.0");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("重定向缺少 Location: " + url);
                }
                url = location;
                continue;
            }
            if (code != 200) {
                connection.disconnect();
                throw new IOException("HTTP " + code);
            }
            return connection;
        }
        throw new IOException("重定向超过 6 跳: " + url);
    }

    private static String sha256Hex(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception throwable) {
            throw new IOException("sha256 计算失败", throwable);
        }
    }

    // ── 解压与验证 ───────────────────────────────────────────────────────────

    private static void unpack(File packFile, File incoming) throws IOException {
        if (!incoming.isDirectory() && !incoming.mkdirs()) {
            throw new IOException("无法创建暂存目录 " + incoming);
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(packFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(incoming, entry.getName());
                // zip-slip 防护：解出来的每个文件必须仍落在暂存目录里。
                if (!target.getCanonicalPath().startsWith(incoming.getCanonicalPath() + File.separator)) {
                    throw new IOException("整包含非法路径条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录 " + parent);
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static void verifyExtracted(File incoming) throws IOException {
        File exe = new File(incoming, EXECUTABLE_ENTRY);
        if (!exe.isFile() || exe.length() < EXECUTABLE_MIN_BYTES) {
            throw new IOException("整包缺少 " + EXECUTABLE_ENTRY + " 或尺寸异常");
        }
    }

    /** 暂存目录内容晋升到安装根（同名覆盖；gamdl 是短命进程，被映射锁住的概率可忽略）。 */
    private static void promote(File incoming, File root) throws IOException {
        File[] children = incoming.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(root, child.getName());
            if (child.isDirectory()) {
                copyTree(child, target);
            } else {
                moveOrReplace(child, target);
            }
        }
        deleteRecursively(incoming);
    }

    private static void copyTree(File source, File target) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("无法创建目录 " + target);
        }
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File childTarget = new File(target, child.getName());
            if (child.isDirectory()) {
                copyTree(child, childTarget);
            } else {
                moveOrReplace(child, childTarget);
            }
        }
    }

    private static void moveOrReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException throwable) {
            // 跨卷 / 目标被锁：退到复制 + 删源。目标被运行中的 gamdl 锁住时这里会失败，
            // 让上层按失败重试（下一次会话旧进程必已退出）。
            Files.copy(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(source.toPath());
        }
    }

    private static void deleteRecursively(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                } else {
                    try {
                        Files.deleteIfExists(child.toPath());
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        try {
            Files.deleteIfExists(directory.toPath());
        } catch (IOException ignored) {
        }
    }

    private static void writeVersionMarker() throws IOException {
        Files.write(versionMarkerFile().toPath(), PACK.fileName().getBytes(StandardCharsets.UTF_8));
    }

    /** 真跑一次 {@code gamdl --version}：整包损坏 / 缺 VC 运行库在这里现形。 */
    static boolean verifyExecutable(File executable) {
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "--version");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            Thread drain = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    while (in.read(buffer) != -1) {
                        // 丢弃内容：验证只关心退出码与超时。
                    }
                } catch (IOException ignored) {
                }
            }, "muonium-apple-gamdl-verify");
            drain.setDaemon(true);
            drain.start();
            boolean exited = process.waitFor(VERIFY_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Throwable throwable) {
            System.err.println("[Music/Apple] gamdl 验证失败: " + throwable);
            return false;
        }
    }

    /** 设置页的状态一句话。 */
    public static String statusLine() {
        if (!supportedPlatform()) return "当前系统不支持自动安装（仅 Windows），请手动指定 gamdl 路径";
        if (PACK.sha256().isEmpty()) return "整包尚未发布：先运行 tools/build-apple-companion.ps1";
        if (isReady()) return "gamdl 伴生已就绪（" + PACK.fileName() + "）";
        State snapshot = current;
        switch (snapshot.phase()) {
            case DOWNLOADING:
                return "正在下载伴生包 " + (snapshot.percent() >= 0 ? snapshot.percent() + "%" : "");
            case INSTALLING:
                return "正在解压伴生包";
            case VERIFYING:
                return "正在验证 gamdl";
            case FAILED:
                return "伴生安装失败：" + snapshot.detail();
            default:
                return isInstalled() ? "已安装待验证" : "未安装";
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
