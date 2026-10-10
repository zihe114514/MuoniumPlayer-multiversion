package com.muoniumplayer.core.ncm.lxsource;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * 脚本转译器（Closure Compiler，剥掉 checkerframework 死重的 v20240317）的按需下载器。
 *
 * <h2>为什么它不进主包</h2>
 * 全项目只有 LX 自定义源的转译这一处用它，却是 12.6 MB 的压缩重——十个发行渠道每个
 * 背一份，只为一个「自定义源才用得上」的功能。现在主包零依赖（反射桥见
 * {@link ClosureEs5Backend}），<b>进游戏时</b>（{@code MuoniumPlayerExtension.init}，与完整
 * 字体、Skija 伴生包同一时机）就触发下载，用户真要用自定义源时多半已经就位；进自定义源
 * 界面（{@code MusicSourceOverlay#showLxSources}）再兜一次重评，接住启动时网络没好的会话。
 * 下载完成前转译环节整体让位、原始脚本直送 Rhino——{@code LxRuntime.load} 本来就有这条
 * 降级路，ES5 老脚本全程无感。
 *
 * <h2>与字体下载器（{@code FontAssetDownload}）的异同</h2>
 * 同一套形状：三源镜像链、钉死字节数 + sha256、会话内尝试预算与冷却、后台守护线程、
 * 进度喂灵动岛。两处不同：单文件、免解压；就绪后<b>懒加载</b>——第一次真正转译脚本时
 * 才建 {@link ClosureEs5Backend}，不用白付一次类加载。
 */
public final class ClosureCompilerDownload {

    /**
     * 下载镜像链，全部指向公开清单仓库 {@code libs/} 目录，与更新检查、Skija 伴生包、
     * 完整字体同一套三源（ghfast 代理 / raw / jsdelivr）。
     */
    static final List<String> MIRRORS = List.of(
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/libs/");

    /** 钉死的发布文件：剥掉 org/checkerframework/**（closure 对其零引用的死重）后的版本。 */
    static final String FILE_NAME = "closure-compiler-v20240317.jar";
    static final long BYTES = 13_752_766L;
    static final String SHA256 = "7e72f45647c6f0556569016da68cfbe6e7ce60ff4cb52ce28f20ea7b70d9a2f6";

    /** 关闭自动下载的开关（默认开，只有显式 {@code =false} 才关）。包级可见：静态测试要读它。 */
    static final String PROP_ENABLED = "muonium.lx.transpilerDownload";

    /** 开发期替代源：直接用一个本地 jar 文件，不经网络。 */
    static final String PROP_LOCAL_JAR = "muonium.lx.transpilerJar";

    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_COOLDOWN_NANOS = 3L * 60L * 1_000_000_000L;
    private static final String PART_SUFFIX = ".part";
    /** 灵动岛进度卡的刷新下限。 */
    private static final long NOTICE_THROTTLE_MILLIS = 500L;

    enum Phase {
        IDLE,
        DOWNLOADING,
        /** 已就位、校验通过；backend 首次使用时懒加载。 */
        DONE,
        FAILED
    }

    record State(Phase phase, int percent, String detail) {
    }

    private static final Object LOCK = new Object();
    private static volatile State state = new State(Phase.IDLE, 0, null);
    private static boolean inFlight;
    private static int attempts;
    private static long nextAttemptAtNanos;
    private static long lastNoticeAtMillis = Long.MIN_VALUE;

    /** 懒加载的反射桥；构建失败（文件损坏/类残缺）置为哑元不再重试。 */
    private static volatile ClosureEs5Backend backend;
    private static volatile boolean backendBroken;

    private ClosureCompilerDownload() {
    }

    static State state() {
        return state;
    }

    /** 转译器缓存目录，与字体缓存（fonts/）、模型缓存（models/）同一层级约定。 */
    public static File libCacheDir() {
        return new File(new File("config", "muonium"), "libs");
    }

    /** 已校验文件的正式落点。 */
    public static File cachedFile() {
        return new File(libCacheDir(), FILE_NAME);
    }

    /** 文件是否就位：只看字节数（sha256 在下载时校验过；启动/进页面路径不重算 13 MB 哈希）。 */
    public static boolean isDownloaded() {
        File file = cachedFile();
        return file.isFile() && file.length() == BYTES;
    }

    /** 自动下载是否启用：默认启用，只有显式设成 false 才关。 */
    static boolean enabled() {
        String value = System.getProperty(PROP_ENABLED);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /**
     * 用户进入自定义源界面时调（UI 线程）：就位或已在下载就返回，否则起一次后台下载。
     * 只做判断就走，绝不在这里等网络。
     */
    public static void ensureDownload() {
        if (!enabled() || isDownloaded()) {
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
        Thread worker = new Thread(ClosureCompilerDownload::run, "MuoniumPlayer-LxTranspiler");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 转译入口用的就绪探测：backend 可用返回它；未就绪返回 null
     * （转译环节整体让位）。构建失败会自愈成「哑元失败」而不是反复炸。
     */
    public static ClosureEs5Backend backend() {
        if (backendBroken) {
            return null;
        }
        ClosureEs5Backend ready = backend;
        if (ready != null) {
            return ready;
        }
        if (!isDownloaded()) {
            return null;
        }
        synchronized (LOCK) {
            if (backend != null || backendBroken) {
                return backend;
            }
            try {
                backend = ClosureEs5Backend.create(cachedFile());
            } catch (Throwable failure) {
                backendBroken = true;
                System.out.println("[MuoniumPlayer/LxTranspiler] 转译器加载失败，转译环节降级为原始脚本："
                        + failure.getClass().getName() + ": " + failure.getMessage());
                return null;
            }
            System.out.println("[MuoniumPlayer/LxTranspiler] 转译器已就绪（反射桥挂载完成）");
            return backend;
        }
    }

    /**
     * 给界面状态行的一句话（每帧被 UI 调，只读、绝不触发 backend 懒构建——那要几百毫秒，
     * 只能发生在转译线程上）。就绪/已下载返回 null（页面照常显示「已转译为 ES5」；
     * 已下载未构建的，下次转译时自然挂上），其余状态给「下载中 x% / 下载失败·稍后重试」。
     */
    public static String headlineSuffix() {
        if (backend != null || backendBroken || isDownloaded()) {
            return null;
        }
        State now = state;
        return switch (now.phase()) {
            case DOWNLOADING -> "转译器下载中 " + Math.max(0, now.percent()) + "%";
            case FAILED -> "转译器下载失败 · 稍后重试";
            default -> "转译器未就绪";
        };
    }

    /**
     * 转译环节让位时给日志/弹窗的一句人话（{@code LxRuntime} 会把它接在
     * 「转译失败，改用原始脚本：」后面，用户能看出这是暂态而不是脚本坏了）。
     */
    public static String pendingMessage() {
        State now = state;
        if (now.phase() == Phase.DOWNLOADING) {
            return "转译器还在下载（" + Math.max(0, now.percent()) + "%），先用原始脚本";
        }
        return "转译器未就绪（进入自定义源界面时自动下载），先用原始脚本";
    }

    /** 下载 → 校验 → 并入缓存目录。全程后台线程。 */
    private static void run() {
        try {
            Files.createDirectories(libCacheDir().toPath());
            state = new State(Phase.DOWNLOADING, 0, null);
            System.out.println("[MuoniumPlayer/LxTranspiler] 开始自动下载脚本转译器（镜像链 "
                    + MIRRORS.size() + " 条，共 " + (BYTES / 1024 / 1024) + " MB）");
            announceProgress(0);
            fetch();
            state = new State(Phase.DONE, 100, null);
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showLxTranspilerReady();
            System.out.println("[MuoniumPlayer/LxTranspiler] 转译器已就位 → "
                    + cachedFile().getAbsolutePath() + "，下次转译脚本时懒加载");
        } catch (Throwable failure) {
            String detail = safeMessage(failure);
            state = new State(Phase.FAILED, 0, detail);
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showLxTranspilerFailure(detail);
            System.out.println("[MuoniumPlayer/LxTranspiler] 自动下载失败："
                    + failure.getClass().getName() + ": " + failure.getMessage()
                    + "（冷却后自动重试；期间转译环节走原始脚本降级）");
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /**
     * 取文件到缓存。本地替代源优先；否则按镜像链逐个试，任一成功即返回。
     * 每次尝试都完整校验（字节数 + sha256），校验不过换下一路。
     */
    private static void fetch() throws Exception {
        String localJar = System.getProperty(PROP_LOCAL_JAR);
        if (localJar != null && !localJar.isBlank()) {
            File local = new File(localJar);
            if (!local.isFile()) {
                throw new IOException("本地转译器 jar 不存在：" + localJar);
            }
            verify(local);
            Files.copy(local.toPath(), cachedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Exception last = null;
        for (String mirror : MIRRORS) {
            File part = new File(libCacheDir(), FILE_NAME + PART_SUFFIX);
            try {
                // 同镜像先续传重试（最多 3 次，download 内部按 .part 已收字节挂 Range）：
                // gh-proxy 这类代理对大文件常在中途截断但支持 Range 续传，接着拉就能拿全，
                // 不必整包重来、也不必急着换路。内容校验不过时清掉 .part 从零再来。
                for (int retry = 0; retry <= 3; retry++) {
                    try {
                        download(mirror + FILE_NAME, part);
                        break;
                    } catch (Exception attempt) {
                        if (retry == 3) {
                            throw attempt;
                        }
                        // 校验失败（哈希不符）说明已收前缀可疑，续传没有意义，清掉从零来。
                        if (attempt instanceof IOException
                                && String.valueOf(attempt.getMessage()).contains("sha256")) {
                            Files.deleteIfExists(part.toPath());
                        }
                    }
                }
                verify(part);
                Files.move(part.toPath(), cachedFile().toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (Exception failure) {
                last = failure;
                try {
                    Files.deleteIfExists(part.toPath());
                } catch (Throwable ignored) {
                }
            }
        }
        throw last == null ? new IOException("没有可用的下载源") : last;
    }

    /** 单次下载：读流与校验同步走，整百分比变化时喂一次灵动岛（带时间下限）。 */
    private static void download(String url, File part) throws Exception {
        long already = part.isFile() ? part.length() : 0L;
        if (already > BYTES) {
            throw new IOException(".part 超长：已有 " + already + " 字节，应为 " + BYTES);
        }
        HttpURLConnection connection = openFollowing(URI.create(url).toURL(), 0, already);
        int code = connection.getResponseCode();
        // 续传被服务端拒绝（不认 Range）时它会给全量 200：把 .part 清掉按从头下处理。
        if (already > 0L && code == 200) {
            connection.disconnect();
            Files.deleteIfExists(part.toPath());
            connection = openFollowing(URI.create(url).toURL(), 0, 0L);
            code = connection.getResponseCode();
            already = 0L;
        }
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        if (already > 0L && code != 206) {
            throw new IOException("续传未生效：HTTP " + code);
        }
        long contentLength = connection.getContentLengthLong();
        if (contentLength > 0 && contentLength != BYTES - already) {
            throw new IOException("Content-Length 不符：得到 " + contentLength
                    + "，应为 " + (BYTES - already));
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long done = 0L;
        int shownPercent = -1;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(part, already > 0L)) {
            // 已收的前缀在续传时不再过哈希：整包 sha256 校验在收完后照做，
            // 前缀损坏（极小概率）会在那里被抓住、换下一路重来。
            digestSkip(input, already);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                if (already + done > BYTES) {
                    throw new IOException("下超了：已收 " + (already + done) + " 字节，应为 " + BYTES);
                }
                int percent = (int) Math.min(100L, (already + done) * 100L / BYTES);
                if (percent != shownPercent) {
                    shownPercent = percent;
                    announcePercent(percent);
                }
            }
        }
        checkAgainst(part.length(), hex(digest.digest()));
    }

    /** 续传时跳过已收前缀（服务端已按 Range 从该字节开始发）；请求头由 openFollowing 挂。 */
    private static void digestSkip(InputStream input, long skip) throws IOException {
        while (skip > 0) {
            long jumped = input.skip(skip);
            if (jumped <= 0) {
                throw new IOException("续传前缀跳读失败：还剩 " + skip + " 字节");
            }
            skip -= jumped;
        }
    }

    private static void announcePercent(int percent) {
        if (percent >= 0) {
            announceProgress(percent);
        }
    }

    /** 重定向自己跟（镜像会 30x 到 CDN），最多 6 跳，与 Skija/字体下载器同款。rangeFrom &gt; 0 时挂 Range 续传头。 */
    private static HttpURLConnection openFollowing(URL url, int depth, long rangeFrom) throws Exception {
        if (depth > 6) {
            throw new IOException("重定向过多");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-LxTranspiler/1.0");
        if (rangeFrom > 0L) {
            connection.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
        }
        int code = connection.getResponseCode();
        if (code >= 300 && code < 400) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("重定向没有 Location");
            }
            return openFollowing(new URL(url, location), depth + 1, rangeFrom);
        }
        return connection;
    }

    /** 本地文件路径的校验（开发期替代源用），与下载路径同一套判据。 */
    private static void verify(File file) throws IOException {
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
        checkAgainst(file.length(), hex(digest.digest()));
    }

    private static void checkAgainst(long bytes, String sha256) throws IOException {
        if (bytes != BYTES) {
            throw new IOException("大小不符：得到 " + bytes + " 字节，应为 " + BYTES);
        }
        if (!SHA256.equalsIgnoreCase(sha256)) {
            throw new IOException("sha256 不符：得到 " + sha256);
        }
    }

    /** 灵动岛进度卡：百分比变了、且距上一张超过节流下限才发。 */
    private static void announceProgress(int percent) {
        long now = System.currentTimeMillis();
        if (now - lastNoticeAtMillis < NOTICE_THROTTLE_MILLIS && percent < 100) {
            return;
        }
        lastNoticeAtMillis = now;
        com.muoniumplayer.core.rendering.DownloadDynamicIsland.showLxTranspilerProgress(percent);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }
}
