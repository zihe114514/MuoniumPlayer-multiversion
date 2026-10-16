package com.muoniumplayer.core.management;

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
import com.muoniumplayer.core.utils.network.HttpDownloadRace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 完整界面字体（苹方常规/中黑）的自动下载器。
 *
 * <h2>为什么需要它</h2>
 * 两份全量中文字体各约 10 MB，打进主包要让十个发行渠道每个都背上约 17 MB（压缩后）。
 * 主包里因此只留<b>精简子集</b>（GB2312 全部汉字 + 假名 + 常用标点，每份约 2.2 MB），
 * 首启动够用；本类把完整版从公开的清单仓库拉到 {@code config/muonium/fonts/}，
 * 校验字节数与 sha256 后<b>热启用</b>——{@link FontManager#applyFullFontAssets(byte[], byte[])}
 * 在渲染线程上换掉所有渲染器回退链里的子集实例，不重启、不重建控件树。
 *
 * <h2>与 Skija 伴生包下载器（{@code SkijaCompanionDownload}）的异同</h2>
 * 同一套形状：三源镜像链、钉死字节数 + sha256、会话内尝试预算与冷却、后台守护线程、
 * 进度喂给灵动岛。两处刻意不同：
 * <ol>
 *   <li>字体是裸 ttf，免解压免暂存目录：先落 {@code .part}、整包校验通过后改名并入；</li>
 *   <li>装好即热启用，没有「必须重启游戏」的看门狗——渲染器实例与 GL 图集都不动，
 *       只换回退字体对象（见 {@code CFontRenderer.swapFallbackFont}）。</li>
 * </ol>
 *
 * <h2>触发条件（同时满足才下载）</h2>
 * <ol>
 *   <li>缓存目录里两份字体没有全部就位（存在但大小不符钉值视为未就位，会重下那一份）；</li>
 *   <li>没有被 {@value #PROP_ENABLED} 显式关掉；</li>
 *   <li>不在下载中，且本会话的尝试预算与冷却时间还允许。</li>
 * </ol>
 *
 * <h2>钉死字节数与 sha256</h2>
 * 换字体文件时三处必须一起改：清单仓库 {@code fonts/} 目录里的文件、本类的
 * {@link #PACKS}、主包资源里的精简子集（子集必须<b>小于</b>钉住的全量字节数，
 * 静态测试会盯）。{@code -Dmuonium.fonts.dir=...} 可从本地目录取包（离线核对用），
 * 校验判据与网络路径完全一致。
 */
public final class FontAssetDownload {

    /**
     * 下载镜像链，前五路指向公开的清单仓库 {@code fonts/} 目录（ghfast / gh-proxy / raw /
     * ghproxy.net / jsdelivr），后两路是官网系（Cloudflare）：Pages 直链与 R2 对象存储直链，
     * 同一批文件的两个镜像。未命中该文件的镜像会 404，竞速下只是那一路白跑。
     */
    static final List<String> MIRRORS = List.of(
            "https://file.melodifymc.cc.cd/fonts/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/fonts/",
            "http://muoniummc.cc.cd/dl/fonts/",
            "https://melodifymc.cc.cd/dl/fonts/",
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/fonts/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/fonts/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/fonts/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/fonts/");

    /** 关闭自动下载的开关（默认开，只有显式 {@code =false} 才关）。包级可见：静态测试要读它。 */
    static final String PROP_ENABLED = "muonium.fonts.autoDownload";

    /** 开发期替代源：从一个本地目录取字体文件，不经网络。 */
    static final String PROP_LOCAL_DIR = "muonium.fonts.dir";

    /** 同一会话内最多尝试几次：网络断了不该每次重评都打一次。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 两次尝试之间的冷却：给网络恢复留时间，也避免频繁重试刷日志。 */
    private static final long RETRY_COOLDOWN_NANOS = 3L * 60L * 1_000_000_000L;

    /** 下载中落盘的暂存后缀。加载侧只认正式文件名，半成品永远不会被读进去。 */
    private static final String PART_SUFFIX = ".part";

    /** 渲染线程重评钩子的节流：失败后的会话内重试由它驱动，而不是每次起游戏一次。 */
    private static final long REPOLL_INTERVAL_NANOS = 20L * 1_000_000_000L;

    /** 灵动岛进度卡的刷新下限：再快的镜像也不值得每 1% 都排一次队。 */
    private static final long NOTICE_THROTTLE_MILLIS = 500L;

    /** 一份完整字体：文件名 + 字节数 + sha256，三个都必须精确。 */
    record Pack(String fileName, long bytes, String sha256) {
    }

    /**
     * 两份完整字体，与清单仓库 {@code fonts/} 目录里实际托管的文件一致
     * （sha256 以仓库文件为准，发布时由静态测试对拍）。
     */
    static final List<Pack> PACKS = List.of(
            new Pack("pf_normal.ttf", 10_757_580L,
                    "4e8f76364b3473e758f39ccba589655edd279de70e3dfd8d62c63727c801f0db"),
            new Pack("pf_middleblack.ttf", 10_606_736L,
                    "ebf0c40a72eb8f1ebca9713853556e85276d3d227dde985cb24c1f5e9eca3d7c"));

    /** 下载状态机；日志与排查用（进度展示走灵动岛，不逐帧读它）。 */
    enum Phase {
        IDLE,
        DOWNLOADING,
        /** 全部就位、校验通过，等待渲染线程热启用。 */
        DONE,
        FAILED
    }

    record State(Phase phase, String detail) {
    }

    private static final Object LOCK = new Object();
    private static volatile State state = new State(Phase.IDLE, null);
    private static boolean inFlight;
    private static int attempts;
    private static long nextAttemptAtNanos;

    /** 全部就位后置位；渲染线程在 {@link #pollFromRenderThread()} 里取走并热启用。 */
    private static volatile boolean pendingSwap;
    /**
     * 下载线程上先行解析好的两个全量 {@code Font} 实例（已登记 Skija）。
     * 解析 20 MB 字体要几百毫秒，放下载线程做，渲染线程只做引用替换，
     * 热启用那一帧不掉帧。
     */
    private static volatile java.awt.Font[] pendingFonts;
    /** 本会话已完成热启用：同一会话里重复消费没有意义（字体缓存里已经是全量实例）。 */
    private static volatile boolean swappedThisSession;
    /** 渲染线程重评节流的锚点。 */
    private static volatile long lastPollNanos;
    /** 灵动岛进度卡节流。 */
    private static volatile long lastNoticeAtMillis = Long.MIN_VALUE;

    private FontAssetDownload() {
    }

    static State state() {
        return state;
    }

    /** 字体缓存目录，与模型目录（{@code config/muonium/models}）同一层级约定。 */
    public static File fontCacheDir() {
        return new File(new File("config", "muonium"), "fonts");
    }

    /** 某份字体在缓存目录里的正式落点。 */
    public static File cachedFile(String fileName) {
        return new File(fontCacheDir(), fileName);
    }

    /** 钉住的全量字节数；不在下载表里的文件名返回 -1。 */
    static long pinnedBytes(String fileName) {
        for (Pack pack : PACKS) {
            if (pack.fileName().equals(fileName)) {
                return pack.bytes();
            }
        }
        return -1L;
    }

    /**
     * 缓存里两份完整字体是否全部就位。判据只看字节数（启动路径上跑，20 MB 的
     * sha256 不值得每次启动都算）；内容完整性由下载时的整包 sha256 保证，
     * 「大小恰好对上但内容损坏」在现实中轮不到这条路径来防。
     */
    public static boolean isCacheComplete() {
        for (Pack pack : PACKS) {
            File file = cachedFile(pack.fileName());
            if (!file.isFile() || file.length() != pack.bytes()) {
                return false;
            }
        }
        return true;
    }

    /** 自动下载是否启用：默认启用，只有显式设成 false 才关。 */
    static boolean enabled() {
        String value = System.getProperty(PROP_ENABLED);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /**
     * 缓存没满时起一次后台下载。启动时由 {@link FontManager#init()} 调；
     * 失败后的会话内重试由 {@link #pollFromRenderThread()} 节流驱动。
     * 只做判断就走，绝不在这里等网络。
     */
    public static void ensureFonts() {
        if (!enabled() || isCacheComplete()) {
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
        Thread worker = new Thread(FontAssetDownload::run, "MuoniumPlayer-FontAsset");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 渲染线程每帧的钩子（挂在灵动岛的渲染入口上，五棵版本树零改动）：
     * <ol>
     *   <li>完整字体已就位且本会话还没启用 → 读出字节、{@link FontManager#applyFullFontAssets}
     *       热启用、灵动岛报一次「已就绪」；</li>
     *   <li>否则按 {@link #REPOLL_INTERVAL_NANOS} 节流地重评下载（网络恢复后的会话内重试）。</li>
     * </ol>
     */
    public static void pollFromRenderThread() {
        try {
            if (pendingSwap && !swappedThisSession) {
                pendingSwap = false;
                swappedThisSession = applySwapped();
            }
            long now = System.nanoTime();
            if (now - lastPollNanos >= REPOLL_INTERVAL_NANOS) {
                lastPollNanos = now;
                ensureFonts();
            }
        } catch (Throwable failure) {
            // 钩子挂在每帧渲染路径上，任何异常都不许向上冒——字体最多保持子集，
            // 下次启动走正常加载路径。
            System.out.println("[MuoniumPlayer/FontAsset] 渲染线程热启用失败，保持精简子集："
                    + failure);
            pendingSwap = false;
        }
    }

    /** 把下载线程解析好的全量实例交给 {@link FontManager} 热启用。 */
    private static boolean applySwapped() {
        java.awt.Font[] fonts = pendingFonts;
        if (fonts == null || fonts.length != 2) {
            return false;
        }
        boolean applied = FontManager.applyFullFontAssets(fonts[0], fonts[1]);
        if (!applied) {
            // 字体缓存还没填好（FontManager 尚未 init）：实例不消费，下一帧再试，
            // 否则这一份解析结果会被扔掉、本会话只能停在子集。
            return false;
        }
        pendingFonts = null;
        com.muoniumplayer.core.rendering.DownloadDynamicIsland.showFontAssetReady();
        System.out.println("[MuoniumPlayer/FontAsset] 完整字体已热启用（本会话内生僻字/繁体即刻可用）");
        return true;
    }

    /**
     * 下载线程的收尾：读出两份已校验的字体字节、解析成 AWT Font 并登记 Skija，
     * 然后置位待热启用。解析失败按整体失败处理（文件在磁盘上，冷却重试时
     * 只会重走这一步而不会重新下载）。
     */
    private static void preparePendingFonts() throws IOException {
        byte[] normal = Files.readAllBytes(cachedFile(PACKS.get(0).fileName()).toPath());
        byte[] bold = Files.readAllBytes(cachedFile(PACKS.get(1).fileName()).toPath());
        try {
            pendingFonts = new java.awt.Font[]{
                    FontManager.createRegisteredFont(normal),
                    FontManager.createRegisteredFont(bold)};
        } catch (Exception failure) {
            pendingFonts = null;
            throw new IOException("全量字体解析失败：" + failure, failure);
        }
        pendingSwap = true;
    }

    /**
     * 下载 → 校验 → 并入缓存目录 → 置位待热启用。逐份处理：先到先并入，
     * 单份失败不回滚另一份；任何一份最终没到齐算整体失败，冷却后重评时只补缺的。
     */
    private static void run() {
        try {
            Files.createDirectories(fontCacheDir().toPath());
            long totalBytes = 0L;
            long doneBytes = 0L;
            for (Pack pack : PACKS) {
                if (!needsFetch(pack)) {
                    doneBytes += pack.bytes();
                    continue;
                }
                totalBytes += pack.bytes();
            }
            if (totalBytes == 0L) {
                // 缓存在两次判断之间被补齐了（多开、或手动放进来的合法文件）
                state = new State(Phase.DONE, null);
                preparePendingFonts();
                return;
            }
            state = new State(Phase.DOWNLOADING, null);
            System.out.println("[MuoniumPlayer/FontAsset] 开始自动下载完整字体（镜像链 "
                    + MIRRORS.size() + " 条，共 " + (totalBytes / 1024 / 1024) + " MB）");
            announceProgress(0);
            for (Pack pack : PACKS) {
                if (!needsFetch(pack)) {
                    continue;
                }
                fetch(pack, doneBytes, totalBytes);
                doneBytes += pack.bytes();
            }
            state = new State(Phase.DONE, null);
            preparePendingFonts();
            System.out.println("[MuoniumPlayer/FontAsset] 完整字体已就位 → " + fontCacheDir().getAbsolutePath()
                    + "，等待渲染线程热启用");
        } catch (Throwable failure) {
            state = new State(Phase.FAILED, safeMessage(failure));
            com.muoniumplayer.core.rendering.DownloadDynamicIsland
                    .showFontAssetFailure(safeMessage(failure));
            System.out.println("[MuoniumPlayer/FontAsset] 自动下载失败："
                    + failure.getClass().getName() + ": " + failure.getMessage()
                    + "（冷却后自动重试；本次会话继续用精简子集）");
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /** 这一份要不要下：不存在、或大小与钉值不符（半成品/旧版本/损坏）都要下。 */
    private static boolean needsFetch(Pack pack) {
        File file = cachedFile(pack.fileName());
        return !file.isFile() || file.length() != pack.bytes();
    }

    /**
     * 取一份字体到缓存。本地替代源优先；否则按镜像链逐个试，任一成功即返回。
     * 每次尝试都完整校验（字节数 + sha256），校验不过算这次失败、换下一路。
     * 进度按「已收字节 / 全部待收字节」聚合推给灵动岛。
     */
    /**
     * 取字体到缓存目录。本地替代源优先；否则多路竞速下载（见 {@link HttpDownloadRace}），
     * 开关关掉时退回旧的逐路串行回退（带同镜像 Range 续传）。
     */
    private static void fetch(Pack pack, long baseBytes, long totalBytes) throws Exception {
        String localDir = System.getProperty(PROP_LOCAL_DIR);
        if (localDir != null && !localDir.isBlank()) {
            File local = new File(localDir, pack.fileName());
            if (!local.isFile()) {
                throw new IOException("本地字体目录里没有 " + pack.fileName() + "：" + localDir);
            }
            verify(local, pack);
            copyIntoPlace(local, pack);
            return;
        }
        File part = new File(fontCacheDir(), pack.fileName() + PART_SUFFIX);
        if (HttpDownloadRace.enabled()) {
            List<String> urls = new ArrayList<String>(MIRRORS.size());
            for (String mirror : MIRRORS) {
                urls.add(mirror + pack.fileName());
            }
            // 两份字体共享一张进度卡：领先路字节加上前几份的 baseBytes 再换算总百分比。
            HttpDownloadRace.fetch(urls, pack.bytes(), pack.sha256(), part, raceDone -> {
                long overall = Math.min(totalBytes, baseBytes + raceDone);
                announceProgress((int) Math.min(100L, overall * 100L / totalBytes));
            });
            copyIntoPlace(part, pack);
            return;
        }
        Exception last = null;
        for (String mirror : MIRRORS) {
            try {
                // 同镜像先续传重试（最多 3 次，download 内部按 .part 已收字节挂 Range）：
                // gh-proxy 这类代理对大文件常在中途截断但支持 Range 续传，接着拉就能拿全，
                // 不必整包重来、也不必急着换路。校验失败（哈希不符）说明已收前缀可疑，
                // 续传没有意义，清掉 .part 从零来。
                for (int retry = 0; retry <= 3; retry++) {
                    try {
                        download(pack, mirror + pack.fileName(), part, baseBytes, totalBytes);
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
                copyIntoPlace(part, pack);
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
    private static void download(Pack pack, String url, File part,
                                 long baseBytes, long totalBytes) throws Exception {
        long already = part.isFile() ? part.length() : 0L;
        if (already > pack.bytes()) {
            throw new IOException(".part 超长：已有 " + already + " 字节，应为 " + pack.bytes());
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
        if (contentLength > 0 && contentLength != pack.bytes() - already) {
            throw new IOException("Content-Length 不符：得到 " + contentLength
                    + "，应为 " + (pack.bytes() - already));
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
                if (already + done > pack.bytes()) {
                    throw new IOException("下超了：已收 " + (already + done) + " 字节，应为 " + pack.bytes());
                }
                int percent = (int) Math.min(100L, (baseBytes + already + done) * 100L / totalBytes);
                if (percent != shownPercent) {
                    shownPercent = percent;
                    announceProgress(percent);
                }
            }
        }
        checkAgainst(pack, part.length(), hex(digest.digest()));
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

    /** 校验通过后的并入：改名即生效（同目录同文件系统，原子性由 Rename 保证）。 */
    private static void copyIntoPlace(File verified, Pack pack) throws IOException {
        Files.move(verified.toPath(), cachedFile(pack.fileName()).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** 重定向自己跟（镜像会 30x 到 CDN），最多 6 跳，与 Skija/Folia 的下载器同款。rangeFrom &gt; 0 时挂 Range 续传头。 */
    private static HttpURLConnection openFollowing(URL url, int depth, long rangeFrom) throws Exception {
        if (depth > 6) {
            throw new IOException("重定向过多");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-FontAsset/1.0");
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

    /** 灵动岛进度卡：百分比变了、且距上一张超过节流下限才发。 */
    private static void announceProgress(int percent) {
        long now = System.currentTimeMillis();
        if (now - lastNoticeAtMillis < NOTICE_THROTTLE_MILLIS && percent < 100) {
            return;
        }
        lastNoticeAtMillis = now;
        com.muoniumplayer.core.rendering.DownloadDynamicIsland.showFontAssetProgress(percent);
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
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }
}
