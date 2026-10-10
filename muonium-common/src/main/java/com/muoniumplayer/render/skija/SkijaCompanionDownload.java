package com.muoniumplayer.render.skija;

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
 * Skija 原生库（全平台，含 Windows）的「伴生包」自动下载器。
 *
 * <h2>为什么需要它</h2>
 * 主包不再内置任何平台的原生库（七个平台全塞进一个 jar 会让包体从 58 MB 涨到约 121 MB，
 * 越过 GitHub release 的单文件上限；瘦身轮之后连 Windows 那份 10.6 MB 也改为按需下载）。
 * 原生库以「伴生包」形式托管在公开清单仓库 {@code MuoniumPlayer-updates} 的
 * {@code skija-companion/} 目录，客户端在<b>磁盘上一个候选都没有</b>时自动下载本机平台的那一份、
 * 校验字节数与 sha256、解压到伴生包目录；装好后<b>必须重启游戏</b>才生效（重启看门狗置闸，
 * 见 {@link #restartRequired}），进度条与状态展示由提示层照 {@link #state()} 画。
 *
 * <h2>触发条件（同时满足才下载）</h2>
 * <ol>
 *   <li>{@link SkijaNatives#ensureLoaded()} 以「一个候选都没有」收场——候选找到了却加载不了
 *       的不在此列，那种情况重复下载同一个包没有意义（真机 2026-09-29 的教训）；</li>
 *   <li>本机判定平台在 {@link #PACKS} 里有对应包；</li>
 *   <li>没有被 {@value #PROP_ENABLED} 显式关掉；</li>
 *   <li>不在下载中，且本会话的尝试预算与冷却时间还允许（见 {@link #MAX_ATTEMPTS}）。</li>
 * </ol>
 *
 * <h2>钉死字节数与 sha256</h2>
 * 下载的是「会跑起来变成可执行代码的二进制」，和更新清单一样必须可校验：
 * {@link #PACKS} 每个平台都钉住字节数与 sha256，对不上换下一个镜像重试，全失败才放弃。
 * 换包时三处必须一起改：清单仓库里的 zip、本类的常量、{@code dist/skija-companion} 里的产物；
 * {@code tools/publish-skija-companion.ps1} 会交叉校验这三处，静态测试也会在产物漂移时报红。
 *
 * <h2>落点</h2>
 * 解到 {@link SkijaNatives#nativeRoot()}（{@code config/muonium/native/skija/}），布局与
 * 「用户手动解压伴生包」完全一致：{@code <os>/<arch>/{libskija.*, skija.version}}。
 * 解压先落在 {@code .incoming-<时间戳>/} 暂存目录，整包校验通过后才并入——扫描逻辑不看
 * 点开头的目录，所以写到一半的文件不会进加载试探。安卓上这个目录在外置存储、
 * 链接器不允许从那里 dlopen，那是 {@link SkijaNatives} 的暂存机制负责的事（搬去 libjvm
 * 同源目录再加载），本类不重复处理。
 *
 * <h2>开发期替代源</h2>
 * {@code -Dmuonium.skija.companionDir=...} 可从本地目录取包（例如构建产物
 * {@code dist/skija-companion}），校验（字节数 + sha256）与网络路径完全一致，方便离线核对。
 */
final class SkijaCompanionDownload {

    /**
     * 下载镜像链，全部指向公开的清单仓库——「清单 + 运行包」那座仓库的
     * {@code skija-companion/} 目录，与更新检查、Folia 运行包同一套三源
     * （ghfast 代理 / raw / jsdelivr）。未命中该文件的镜像会 404，循环继续往下一路试。
     */
    static final List<String> MIRRORS = List.of(
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/skija-companion/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/skija-companion/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/skija-companion/");

    /** 关闭自动下载的开关（默认开，只有显式 {@code =false} 才关）。包级可见：静态测试要读它。 */
    static final String PROP_ENABLED = "muonium.skija.autoDownload";

    /** 开发期替代源：从一个本地目录取包，不经网络。 */
    static final String PROP_LOCAL_DIR = "muonium.skija.companionDir";

    /** 同一会话内最多尝试几次：网络断了不该每 20 秒重评都打一次。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 两次尝试之间的冷却：给网络恢复留时间，也避免频繁重试刷日志。 */
    private static final long RETRY_COOLDOWN_NANOS = 3L * 60L * 1_000_000_000L;

    /** 暂存目录前缀。扫描层对「点开头」的目录一律不看，两边靠这个约定对齐。 */
    private static final String INCOMING_PREFIX = ".incoming-";

    /** 一个平台对应的一份伴生包：文件名 + 字节数 + sha256，三个都必须精确。 */
    record Pack(String platform, String fileName, long bytes, String sha256) {
    }

    /**
     * 七份伴生包，与 {@code dist/skija-companion} 的 {@code packageSkijaCompanion}
     * 产物同名。字节数与 sha256 以清单仓库里实际托管的文件为准（发布时由
     * {@code tools/publish-skija-companion.ps1} 交叉校验）。安卓两份 2026-10-05
     * 真机复测通过（MobileGlues 渲染器 + 关闭 ANGLE 的组合）。
     * Windows 那份 2026-10-10 起接管原「主包内置」的职责。
     */
    static final List<Pack> PACKS = List.of(
            new Pack("windows/x64", "muonium-skija-windows-x64-0.143.17.zip", 10_560_236L,
                    "1d74e9ac8c5249c9f9d833b3c9e02c542c88185f07901ab4f4f02b446ef2e747"),
            new Pack("android/arm64", "muonium-skija-android-arm64-0.143.17.zip", 11_944_117L,
                    "83e2341715365f5680afa35501913463bacad68e004e778df21eb710f79f5ca5"),
            new Pack("android/x64", "muonium-skija-android-x64-0.143.17.zip", 12_169_687L,
                    "50507d6cda88897c0aafa258cb63a348d6318e51c14593175f1157eee588a60a"),
            new Pack("linux/arm64", "muonium-skija-linux-arm64-0.143.17.zip", 13_091_040L,
                    "ce10e7aab2028e1eda5c04496e382239803d4ecb73c5015fc095aef3830a723b"),
            new Pack("linux/x64", "muonium-skija-linux-x64-0.143.17.zip", 13_339_715L,
                    "e9b3c500bde60538f4e7b810f604f630b6e1c82ac0624a75c185dc3e5cd4c1c1"),
            new Pack("macos/arm64", "muonium-skija-macos-arm64-0.143.17.zip", 10_797_393L,
                    "fa2999a335ea9ab6c2d83d61d2f7591726b259a92657f5c5bae7077f3d92bdeb"),
            new Pack("macos/x64", "muonium-skija-macos-x64-0.143.17.zip", 11_334_535L,
                    "b241d8c4783b0cd23e4abf6b9af134897c6dc1b4412a3ecac7db41c4fb21dde1"));

    /** 下载状态机；提示层照它决定说什么（「缺库」分四种处境，用户动作完全不同）。 */
    enum Phase {
        /** 没在下载（没触发、平台无包、或开关关掉）——提示层回到「手动补包」的旧文案。 */
        IDLE,
        /** 正在下载。 */
        DOWNLOADING,
        /** 下载完成，正在解压安装。 */
        INSTALLING,
        /** 装好了。等用户重启游戏后生效（见 {@link #restartRequired}）。 */
        DONE,
        /** 本会话内失败（重试预算没用完时冷却到了会再试）。 */
        FAILED
    }

    /**
     * 给提示层与日志的状态快照。
     *
     * @param percent 0~100；-1 表示总长度未知（拿不到 Content-Length）
     * @param detail  仅 FAILED 用：一句人话的失败原因
     */
    record State(Phase phase, int percent, String detail) {
    }

    private static final Object LOCK = new Object();
    private static volatile State state = new State(Phase.IDLE, 0, null);
    private static boolean inFlight;
    private static int attempts;
    private static long nextAttemptAtNanos;

    /**
     * 重启看门狗的闸：本会话里自动下载装好了伴生包、<b>必须重启游戏才能生效</b>。
     *
     * <p>装完后不走「热加载」：装到一半的游戏里，链接器命名空间、GL 上下文、字体管理器的
     * 状态都不是一张白纸，热加载能不能成全看运气——用户看到的却是「下载完了然后呢」。
     * 所以装好即置位本闸：下载线程不再立即重试、{@code SkijaHost} 的重评窗口对它让路，
     * 提示层挂出「必须重启游戏」。重启之后这是一次全新的 JVM，文件已在磁盘上，加载走
     * 首次启动的正常路径，本闸自然不存在。</p>
     */
    private static volatile boolean restartRequired;

    private SkijaCompanionDownload() {
    }

    /** 本会话是否已进入「装好了、等重启」状态。提示层与重评窗口都读它。 */
    static boolean isRestartRequired() {
        return restartRequired;
    }

    /** 当前（或最近一次）的下载状态；提示层每帧读它，永远不会为 null。 */
    static State state() {
        return state;
    }

    /** 本机平台对应的伴生包；表里没有的平台（如 Skija 没出包的 windows/arm64）返回 null。 */
    static Pack packFor(String mappedPlatform) {
        for (Pack pack : PACKS) {
            if (pack.platform().equals(mappedPlatform)) {
                return pack;
            }
        }
        return null;
    }

    /** 自动下载是否启用：默认启用，只有显式设成 false 才关。 */
    static boolean enabled() {
        String value = System.getProperty(PROP_ENABLED);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /**
     * {@link SkijaNatives#ensureLoaded()} 以「磁盘上一个候选都没有」收场时进来；
     * 决定要不要起一次后台下载。
     *
     * <p>渲染线程（重评路径）与下载线程（装完后的立刻重试）都可能调到本方法，
     * 但真正干活的那次一定在后台线程上——渲染线程只做一次判断就走，绝不在这里等网络。
     */
    static void onNoCandidates(String mappedPlatform) {
        if (!enabled()) {
            return;
        }
        Pack pack = packFor(mappedPlatform);
        if (pack == null) {
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
        Thread worker = new Thread(() -> run(pack), "MuoniumPlayer-SkijaCompanion");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 下载 → 校验 → 解压 → 校验 → 并入伴生包目录 → 置重启闸。
     *
     * <p>全程在后台线程。装好即置位 {@link #restartRequired}——所有平台一致：
     * 热加载在装到一半的游戏里不可靠（2026-10-07 收紧的规则），重启后走首次启动的
     * 正常加载路径。
     */
    private static void run(Pack pack) {
        try {
            File root = SkijaNatives.nativeRoot();
            Files.createDirectories(root.toPath());
            state = new State(Phase.DOWNLOADING, 0, null);
            log("开始自动下载 " + pack.fileName() + "（镜像链 " + MIRRORS.size() + " 条）");
            File archive = new File(root, pack.fileName() + ".part");
            fetch(pack, archive);

            state = new State(Phase.INSTALLING, 100, null);
            deleteStaleIncoming(root);
            File incoming = new File(root, INCOMING_PREFIX + Long.toHexString(System.nanoTime()));
            unzip(archive, incoming);
            verifyExtracted(incoming, pack);
            copyTree(incoming, root);
            deleteRecursively(incoming);
            Files.deleteIfExists(archive.toPath());

            state = new State(Phase.DONE, 100, null);
            // 重启看门狗：置闸、不热加载。提示层会一直挂着「必须重启游戏」。
            restartRequired = true;
            log("已自动装好 " + pack.fileName() + " → " + root.getAbsolutePath()
                    + "；必须重启游戏后生效，本会话不再尝试热加载");
        } catch (Throwable failure) {
            state = new State(Phase.FAILED, 0, safeMessage(failure));
            log("自动下载失败：" + describe(failure));
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /**
     * 取包到 {@code archive}。本地替代源优先；否则按镜像链逐个试，任一成功即返回。
     * 每次尝试都完整校验（字节数 + sha256），校验不过算这次失败、换下一路。
     */
    private static void fetch(Pack pack, File archive) throws Exception {
        String localDir = System.getProperty(PROP_LOCAL_DIR);
        if (localDir != null && !localDir.isBlank()) {
            File local = new File(localDir, pack.fileName());
            if (!local.isFile()) {
                throw new IOException("本地伴生包目录里没有 " + pack.fileName() + "：" + localDir);
            }
            verify(local, pack);
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

    /** 单次下载：读流与校验同步走，进度按整百分比推给 {@link #state()}。 */
    private static void download(Pack pack, String url, File archive) throws Exception {
        HttpURLConnection connection = openFollowing(URI.create(url).toURL(), 0);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        long total = connection.getContentLengthLong();
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
                int percent = total > 0L ? (int) Math.min(100L, done * 100L / total) : -1;
                if (percent != shown) {
                    shown = percent;
                    state = new State(Phase.DOWNLOADING, percent, null);
                }
            }
        }
        checkAgainst(pack, archive.length(), hex(digest.digest()));
    }

    /** 重定向自己跟（镜像会 30x 到 CDN），最多 6 跳，与 Folia 的下载器同款。 */
    private static HttpURLConnection openFollowing(URL url, int depth) throws Exception {
        if (depth > 6) {
            throw new IOException("重定向过多");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-Skija/1.0");
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

    /** 本地文件路径的校验（开发期替代源用），与下载路径同一套判据。 */
    private static void verify(File file, Pack pack) throws IOException {
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
        checkAgainst(pack, file.length(), hex(digest.digest()));
    }

    /** 整包校验：字节数与 sha256 都要对上钉住的值。 */
    private static void checkAgainst(Pack pack, long bytes, String sha256) throws IOException {
        if (bytes != pack.bytes()) {
            throw new IOException("大小不符：得到 " + bytes + " 字节，应为 " + pack.bytes());
        }
        if (!pack.sha256().equalsIgnoreCase(sha256)) {
            throw new IOException("sha256 不符：得到 " + sha256);
        }
    }

    /**
     * 并入前先核对包内结构：{@code <os>/<arch>/} 下的版本标记要等于主包钉的那版、
     * 原生库文件名要对该 OS 家族。防的是「上传错了包 / 包结构变了」——
     * 这两种错按 sha256 拦不住（包本身合法，只是不是我们要的），只能按内容校验。
     */
    private static void verifyExtracted(File incoming, Pack pack) throws IOException {
        String platform = pack.platform();
        String os = SkijaNativeCatalog.osOf(platform);
        String arch = platform.substring(platform.indexOf('/') + 1);
        File dir = new File(new File(incoming, os), arch);
        File marker = new File(dir, SkijaNativeCatalog.VERSION_FILE);
        if (!marker.isFile()) {
            throw new IOException("包内缺 " + os + "/" + arch + "/" + SkijaNativeCatalog.VERSION_FILE);
        }
        String version = Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim();
        if (!SkijaNatives.PINNED_SKIJA_VERSION.equals(version)) {
            throw new IOException("包内 Skija 版本是 " + version + "，本模组要 "
                    + SkijaNatives.PINNED_SKIJA_VERSION);
        }
        File library = new File(dir, SkijaNativeCatalog.libraryNameFor(platform));
        if (!library.isFile()) {
            throw new IOException("包内缺 " + library.getName());
        }
    }

    /** 清掉上一次失败/崩溃留下的暂存目录：扫描虽然不看它们，但 30 MB 一份不该堆着。 */
    private static void deleteStaleIncoming(File root) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(INCOMING_PREFIX)) {
                deleteRecursively(child);
            }
        }
    }

    /** 把暂存目录整棵树并入目标目录；同名文件覆盖——覆盖的正是我们要替换的那几个。 */
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

    /** 解压（自带 zip-slip 防护，与 Folia 的安装器同款形状）。 */
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

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    /** 状态里给用户看的一句人话；空消息退化成异常类名。 */
    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    /** 日志里带类名，排查「到底哪一步挂的」时比只有消息强。 */
    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    private static void log(String message) {
        System.out.println("[MuoniumPlayer/SkijaCompanionDownload] " + message);
    }
}
