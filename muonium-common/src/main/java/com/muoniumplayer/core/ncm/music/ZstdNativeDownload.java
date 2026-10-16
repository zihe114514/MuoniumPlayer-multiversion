package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.utils.network.HttpDownloadRace;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 听歌打卡组件（zstd-jni slim 包）的按需下载器。
 *
 * <h2>为什么它不进主包</h2>
 * 全项目只有网易云播放历史上报（{@link NeteaseClientLogUploader}）一处用它：NCBL 协议要求
 * 记录块先 zstd 压缩再加密，压缩格式由网易服务端钉死，换任何别的压缩器都过不了关——
 * 所以这一份 2MB 的 JNI 包（自带五个平台的原生库）是协议刚需，不是随手可换的工具。
 * 但它的时效性是全部按需组件里最松的：打卡按天记账，晚几小时上报毫无区别。于是照
 * Closure 的模式伴生化：主包零依赖（反射桥见 {@link ZstdCompressor}），<b>用户登录网易云
 * 账号时</b>（{@code CloudMusic#loadNCM}，扫码 / Cookie / 启动自动恢复三条路都汇聚在那）
 * 触发下载；未就绪时上报轮次安静跳过，就绪后下一轮播放自然带上，无需任何手动操作。
 *
 * <h2>与其它三个下载器的异同</h2>
 * 同一套形状：五源镜像链、钉死字节数 + sha256、会话内尝试预算与冷却、后台守护线程、
 * 同镜像断点续传、进度喂灵动岛。不同：单文件免解压；就绪后懒加载反射桥（第一次压缩时
 * 才挂类加载器）；<b>没有等待钩子</b>——打卡晚一轮无感，不像歌词转译要阻塞脚本加载。
 */
public final class ZstdNativeDownload {

    /**
     * 下载镜像链，按 2026-10-16 逐线实测排序：A 档（R2 / gh-proxy / 自建分发站，先开）在前，
     * B 档（Pages / ghfast / gcore / ghproxy.net / raw，15 秒后放量）在后。分批逻辑见
     * {@link HttpDownloadRace}。
     */
    static final List<String> MIRRORS = List.of(
            "https://file.melodifymc.cc.cd/libs/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/",
            "http://muoniummc.cc.cd/dl/libs/",
            "https://melodifymc.cc.cd/dl/libs/",
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/libs/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/");

    /** 钉死的发布文件：forge189-slim 过滤版（Java 8 兼容、自带五平台原生库）。 */
    static final String FILE_NAME = "zstd-jni-1.5.6-9-forge189-slim.jar";
    static final long BYTES = 1_991_396L;
    static final String SHA256 = "1b65c7c44e2483864389f0a8a221c877b617f5ce4a1e86a9fe9604fd60ab499c";

    /** 关闭自动下载的开关（默认开，只有显式 {@code =false} 才关）。包级可见：静态测试要读它。 */
    static final String PROP_ENABLED = "muonium.netease.historyCodecDownload";

    /** 开发期替代源：直接用一个本地 jar 文件，不经网络。 */
    static final String PROP_LOCAL_JAR = "muonium.netease.historyCodecJar";

    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_COOLDOWN_NANOS = 3L * 60L * 1_000_000_000L;
    private static final String PART_SUFFIX = ".part";
    /** 灵动岛进度卡的刷新下限。 */
    private static final long NOTICE_THROTTLE_MILLIS = 500L;

    enum Phase {
        IDLE,
        DOWNLOADING,
        /** 已就位、校验通过；反射桥首次压缩时懒加载。 */
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

    /** 懒加载的反射桥；构建失败置为哑元不再重试（打卡保持跳过，日志有原因）。 */
    private static volatile ZstdCompressor compressor;
    private static volatile boolean compressorBroken;

    private ZstdNativeDownload() {
    }

    static State state() {
        return state;
    }

    /** 组件缓存目录，与转译器 jar 同一层（config/muonium/libs/）。 */
    public static File libCacheDir() {
        return new File(new File("config", "muonium"), "libs");
    }

    /** 已校验文件的正式落点。 */
    public static File cachedFile() {
        return new File(libCacheDir(), FILE_NAME);
    }

    /** 文件是否就位：只看字节数（sha256 在下载时校验过）。 */
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
     * 登录网易云账号时调（{@code CloudMusic#loadNCM}，任意线程）：就位或已在下载就返回，
     * 否则起一次后台下载。只做判断就走，绝不在这里等网络。
     */
    public static void ensureJar() {
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
        Thread worker = new Thread(ZstdNativeDownload::run, "MuoniumPlayer-ZstdNative");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 压缩入口用的就绪探测：可用返回压缩桥；未就绪返回 null（上报轮次安静跳过）。
     * 桥的构建在首次压缩时发生（打卡上报本来就在后台线程，不值得提前挂）。
     */
    public static ZstdCompressor compressor() {
        if (compressorBroken) {
            return null;
        }
        ZstdCompressor ready = compressor;
        if (ready != null) {
            return ready;
        }
        if (!isDownloaded()) {
            return null;
        }
        synchronized (LOCK) {
            if (compressor != null || compressorBroken) {
                return compressor;
            }
            try {
                compressor = ZstdCompressor.create(cachedFile());
            } catch (Throwable failure) {
                compressorBroken = true;
                System.out.println("[MuoniumPlayer/ZstdNative] 压缩桥构建失败，听歌打卡保持跳过："
                        + failure.getClass().getName() + ": " + failure.getMessage());
                return null;
            }
            return compressor;
        }
    }

    /**
     * 给日志与灵动岛失败卡的一句人话：组件没就绪时上报轮次为什么被跳过。
     * 语气是「暂缓」而不是「坏了」——打卡晚一轮没有任何损失。
     */
    public static String pendingMessage() {
        State now = state;
        if (now.phase() == Phase.DOWNLOADING) {
            return "听歌打卡组件还在下载（" + Math.max(0, now.percent()) + "%）";
        }
        return "听歌打卡组件未就绪（登录网易云账号时自动下载）";
    }

    /** 下载 → 校验 → 并入缓存目录。全程后台线程。 */
    private static void run() {
        try {
            Files.createDirectories(libCacheDir().toPath());
            state = new State(Phase.DOWNLOADING, 0, null);
            System.out.println("[MuoniumPlayer/ZstdNative] 开始自动下载听歌打卡组件（镜像链 "
                    + MIRRORS.size() + " 条，共 " + (BYTES / 1024 / 1024) + " MB）");
            announceProgress(0);
            fetch();
            state = new State(Phase.DONE, 100, null);
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showZstdNativeReady();
            System.out.println("[MuoniumPlayer/ZstdNative] 组件已就位 → " + cachedFile().getAbsolutePath()
                    + "，下一轮播放打卡自动恢复");
        } catch (Throwable failure) {
            String detail = safeMessage(failure);
            state = new State(Phase.FAILED, 0, detail);
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showZstdNativeFailure(detail);
            System.out.println("[MuoniumPlayer/ZstdNative] 自动下载失败："
                    + failure.getClass().getName() + ": " + failure.getMessage()
                    + "（冷却后自动重试；期间听歌打卡轮次安静跳过）");
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /**
     * 取文件到缓存。本地替代源优先；否则多路竞速下载（见 {@link HttpDownloadRace}），
     * 开关关掉时退回旧的逐路串行回退（带同镜像 Range 续传）。
     */
    private static void fetch() throws Exception {
        String localJar = System.getProperty(PROP_LOCAL_JAR);
        if (localJar != null && !localJar.isBlank()) {
            File local = new File(localJar);
            if (!local.isFile()) {
                throw new IOException("本地组件 jar 不存在：" + localJar);
            }
            verify(local);
            Files.copy(local.toPath(), cachedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        File part = new File(libCacheDir(), FILE_NAME + PART_SUFFIX);
        if (HttpDownloadRace.enabled()) {
            List<String> urls = new ArrayList<String>(MIRRORS.size());
            for (String mirror : MIRRORS) {
                urls.add(mirror + FILE_NAME);
            }
            HttpDownloadRace.fetch(urls, BYTES, SHA256, part, ZstdNativeDownload::announceProgress);
            verify(part);
            Files.move(part.toPath(), cachedFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        }
        Exception last = null;
        for (String mirror : MIRRORS) {
            try {
                // 同镜像先续传重试（最多 3 次，download 内部按 .part 已收字节挂 Range）：
                // gh-proxy 这类代理对大文件常在中途截断但支持 Range 续传。哈希不符视为
                // 已收前缀损坏，清掉 .part 从零来。
                for (int retry = 0; retry <= 3; retry++) {
                    try {
                        download(mirror + FILE_NAME, part);
                        break;
                    } catch (Exception attempt) {
                        if (retry == 3) {
                            throw attempt;
                        }
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

    /** 单次下载：读流与校验同步走，整百分比变化时喂一次灵动岛（带时间下限）。支持断点续传。 */
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
                    announceProgress(percent);
                }
            }
        }
        checkAgainst(part.length(), hex(digest.digest()));
    }

    /** 续传时跳过已收前缀（服务端已按 Range 从该字节开始发）。 */
    private static void digestSkip(InputStream input, long skip) throws IOException {
        while (skip > 0) {
            long jumped = input.skip(skip);
            if (jumped <= 0) {
                throw new IOException("续传前缀跳读失败：还剩 " + skip + " 字节");
            }
            skip -= jumped;
        }
    }

    /** 重定向自己跟（镜像会 30x 到 CDN），最多 6 跳。rangeFrom &gt; 0 时挂 Range 续传头。 */
    private static HttpURLConnection openFollowing(URL url, int depth, long rangeFrom) throws Exception {
        if (depth > 6) {
            throw new IOException("重定向过多");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-ZstdNative/1.0");
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
        com.muoniumplayer.core.rendering.DownloadDynamicIsland.showZstdNativeProgress(percent);
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
