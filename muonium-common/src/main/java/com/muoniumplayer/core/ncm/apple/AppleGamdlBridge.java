package com.muoniumplayer.core.ncm.apple;

import com.muoniumplayer.core.ncm.music.AudioContainerSupport;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gamdl 伴生桥接：把「伪 URL」兑换成完整版本地音频文件，收编进播放缓存键位。
 *
 * <h3>单次兑换的流水线</h3>
 * <ol>
 *   <li>可执行文件：伴生整包在位（{@link AppleCompanionDownload#executableOrNull}）或
 *       用户手动路径（{@link ApplePlaybackResolver#manualExecutableOrNull}）；</li>
 *   <li>凭据：cookies.txt（{@link AppleCookieSupport}）；缺一样立即返回 null，绝不重试 ——
 *       上层 {@code CloudMusic} 的 catch 会把这次点播推进换源替身链，用户无感；</li>
 *   <li>gamdl 子进程：{@code --cookies-path <cookies> --output-path <工作目录>
 *       --song-codec aac-web <歌曲页面 URL>}。选 aac-web 是因为它是唯一不需要
 *       Wrapper/CDM 的档位，订阅 cookies 即可交付 AAC 256；</li>
 *   <li>产物校验：扫工作目录里最大的 .m4a，字节级容器探测必须认得；</li>
 *   <li>收编：移进 {@code MUSIC_CACHE/<cacheKey>.m4a} —— 从此这首在缓存查找、容量淘汰、
 *       automix 预取里与任何平台下载的文件同权。</li>
 * </ol>
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li><b>绝不在渲染线程触发</b>：调用点全部在 Play Thread / 预取后台线程；</li>
 *   <li><b>同曲单飞</b>：按 cacheKey 加锁，预取与实播并发命中同一首时后到者等待；</li>
 *   <li><b>工作目录放 {@link ConfigPaths#APPLE_MUSIC_DIR}</b> 而不是 MUSIC_CACHE：
 *       容量淘汰按文件名分组扫 MUSIC_CACHE，半成品混进去会被当成可淘汰音频；</li>
 *   <li><b>失败如实记账</b>：stdout 尾巴截进 {@link #lastFailureDetail()}，设置页能看见
 *       gamdl 到底说了什么（cookies 过期 / 未订阅 / Apple 改版，三种归因完全不同）。</li>
 * </ul>
 */
public final class AppleGamdlBridge {

    /** 单曲兑换的宽限上限：gamdl 要走 Apple 授权 + 下载 + 解封装，慢源一分钟出头。 */
    private static final long DOWNLOAD_TIMEOUT_MILLIS = 180_000L;
    /** 收编门槛：完整单曲不可能小于 512KB（30 秒试听都有 ~500KB）。 */
    private static final long MIN_OUTPUT_BYTES = 512L * 1024L;
    /** 失败详情里保留的输出尾巴长度。 */
    private static final int FAILURE_TAIL_CHARS = 1200;

    private static final Map<String, Object> KEY_LOCKS = new ConcurrentHashMap<>();
    private static volatile String lastFailureDetail = "";

    private AppleGamdlBridge() {
    }

    /**
     * 确保 {@code MUSIC_CACHE/<cacheKey>.m4a} 在位；返回 null = 兑换失败（调用方落替身链）。
     *
     * <p>缓存命中检查由调用方（{@code cachedAudioFileLocked}）在分派前做过，这里只负责
     * 「未命中 → 产出 → 收编」。{@code quality} 只用于日志与状态行：aac-web 是单一固定档。</p>
     */
    public static File ensureLocalAudioFile(File musicCacheDir, String cacheKey,
                                            Music song, com.muoniumplayer.core.ncm.music.Quality quality) {
        String songId = song == null ? "" : song.getSourceId();
        if (cacheKey == null || cacheKey.isEmpty() || songId.isEmpty()) return null;
        File executable = AppleCompanionDownload.executableOrNull();
        if (executable == null) executable = ApplePlaybackResolver.manualExecutableOrNull();
        if (executable == null) {
            recordFailure("gamdl 可执行文件不可用（伴生未安装且未配置手动路径）");
            return null;
        }
        File cookies = AppleCookieSupport.cookiesFileOrNull();
        if (cookies == null) {
            recordFailure("cookies.txt 未导入（Apple 设置页粘贴 music.apple.com 的 cookies）");
            return null;
        }

        Object keyLock = KEY_LOCKS.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (keyLock) {
            File target = new File(musicCacheDir, cacheKey + ".m4a");
            if (target.isFile() && target.length() > MIN_OUTPUT_BYTES) {
                return target;
            }
            File workdir = new File(new File(ConfigPaths.APPLE_MUSIC_DIR, "work"),
                    cacheKey + "-" + Long.toHexString(System.nanoTime()));
            try {
                if (!workdir.isDirectory() && !workdir.mkdirs()) {
                    recordFailure("无法创建工作目录 " + workdir);
                    return null;
                }
                String command = AppleMusicService.songWebUrl(songId);
                System.out.println("[Music/Apple] gamdl 兑换开始: " + song.getName()
                        + " (" + songId + ", " + (quality == null ? "?" : quality.displayName()) + ")");
                // 视觉反馈（实机反馈：首播完全没有「准备中」动画）：灵动岛持久卡 +
                // 主界面下载面板 indeterminate。渲染线程纪律不变——这里在 Play/预取线程。
                beginUiFeedback(song);
                ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(),
                        "-n",
                        "--cookies-path", cookies.getAbsolutePath(),
                        "--output-path", workdir.getAbsolutePath(),
                        // gamdl 3.9.1 的选项名（--song-codec 在该版本不存在，README 未跟上）。
                        "--song-codec-priority", "aac-web",
                        command);
                builder.directory(workdir);
                builder.redirectErrorStream(true);
                // 两条环境纪律（E2E 实测踩中）：
                // ① PYTHONIOENCODING：Windows 默认 GBK 会把曲名/日志里的非 ASCII 打歪；
                // ② 宿主的 SOCKS 代理（ALL_PROXY 等）会让 gamdl 内的 httpx 因缺
                //    pysocks 直接崩（整包为体积没带它），CN 镜像与 amp-api 都不需要代理
                //    —— 用户真要走代理应配在 Apple 设置页，由 mod 自己的 HTTP 层消费。
                builder.environment().put("PYTHONIOENCODING", "utf-8");
                for (String proxyVar : new String[]{"ALL_PROXY", "all_proxy", "HTTP_PROXY",
                        "http_proxy", "HTTPS_PROXY", "https_proxy"}) {
                    if (builder.environment().get(proxyVar) != null) {
                        builder.environment().remove(proxyVar);
                    }
                }
                Process process = builder.start();
                StringBuilder outputTail = new StringBuilder();
                Thread drain = new Thread(() -> drainOutput(process, outputTail),
                        "muonium-gamdl-output");
                drain.setDaemon(true);
                drain.start();
                boolean exited = process.waitFor(DOWNLOAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (!exited) {
                    process.destroyForcibly();
                    recordFailure("gamdl 超时（" + (DOWNLOAD_TIMEOUT_MILLIS / 1000L) + "s）");
                    failCard(song);
                    return null;
                }
                if (process.exitValue() != 0) {
                    recordFailure("gamdl 退出码 " + process.exitValue() + "：" + tailOf(outputTail));
                    failCard(song);
                    return null;
                }
                File produced = largestM4a(workdir);
                if (produced == null) {
                    recordFailure("gamdl 未产出音频文件：" + tailOf(outputTail));
                    failCard(song);
                    return null;
                }
                String container = AudioContainerSupport.detectContainer(produced);
                if (!AudioContainerSupport.isSupportedContainer(container)) {
                    recordFailure("产物容器校验失败（字节不像 m4a，可能仍是 DRM 载荷）");
                    failCard(song);
                    return null;
                }
                if (!produced.getParentFile().getCanonicalPath()
                        .equals(workdir.getCanonicalPath())) {
                    // 输出可能带子目录（模板分层）；平铺到工作目录根再收编。
                    File flattened = new File(workdir, produced.getName());
                    Files.move(produced.toPath(), flattened.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    produced = flattened;
                }
                Files.move(produced.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                lastFailureDetail = "";
                System.out.println("[Music/Apple] gamdl 兑换完成: " + target.getName()
                        + " (" + (target.length() / 1024L) + " KB)");
                com.muoniumplayer.core.rendering.DownloadDynamicIsland
                        .showAppleGamdlResolved(song.getName());
                return target;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordFailure("兑换被中断");
                failCard(song);
                return null;
            } catch (Throwable throwable) {
                recordFailure("兑换异常: " + throwable.getMessage());
                System.err.println("[Music/Apple] gamdl 兑换失败 " + songId + ": " + throwable);
                failCard(song);
                return null;
            } finally {
                endUiFeedback();
                deleteRecursively(workdir);
            }
        }
    }

    /** 最近一次失败的归因尾巴；设置页与状态行直接引用。 */
    public static String lastFailureDetail() {
        return lastFailureDetail;
    }

    /** 桥接整体状态一句话；设置页与源卡片共用。 */
    public static String describeStatus() {
        if (!AppleConfigStore.snapshot().gamdlEnabled) return "gamdl 完整播放已在设置里关闭";
        if (AppleCompanionDownload.isReady()) {
            return AppleCookieSupport.cookiesFileOrNull() == null
                    ? "伴生就绪，等待导入 cookies"
                    : "gamdl 完整播放已就绪";
        }
        if (ApplePlaybackResolver.manualExecutableOrNull() != null) {
            return "使用手动 gamdl 路径";
        }
        return "伴生未就绪：" + AppleCompanionDownload.statusLine();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private static void drainOutput(Process process, StringBuilder tail) {
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                synchronized (tail) {
                    tail.append(chunk);
                    int overflow = tail.length() - FAILURE_TAIL_CHARS * 4;
                    if (overflow > 0) {
                        tail.delete(0, overflow);
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束时流关闭是正常路径。
        }
    }

    private static String tailOf(StringBuilder tail) {
        synchronized (tail) {
            String text = tail.toString().trim();
            return text.length() <= FAILURE_TAIL_CHARS ? text
                    : text.substring(text.length() - FAILURE_TAIL_CHARS);
        }
    }

    /** 找工作目录里最大的 .m4a（gamdl 默认模板可能带子目录，递归扫）。 */
    private static File largestM4a(File directory) {
        File[] candidates = directory.listFiles(File::isDirectory);
        File best = null;
        if (candidates != null) {
            for (File child : candidates) {
                File found = largestM4a(child);
                if (found != null && (best == null || found.length() > best.length())) {
                    best = found;
                }
            }
        }
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT)
                .endsWith(".m4a"));
        if (files != null) {
            File localBest = Arrays.stream(files)
                    .max(Comparator.comparingLong(File::length))
                    .orElse(null);
            if (localBest != null && localBest.length() >= MIN_OUTPUT_BYTES
                    && (best == null || localBest.length() > best.length())) {
                best = localBest;
            }
        }
        return best != null && best.length() >= MIN_OUTPUT_BYTES ? best : null;
    }

    private static void recordFailure(String detail) {
        lastFailureDetail = detail;
        System.err.println("[Music/Apple] " + detail);
    }

    /** 失败收尾卡：归因取最近一次 recordFailure；灵动岛失败卡由「将尝试换源」接力。 */
    private static void failCard(com.muoniumplayer.core.ncm.music.dto.Music song) {
        try {
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showAppleGamdlFailure(
                    song == null ? "" : song.getName(), lastFailureDetail);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 兑换期间的 UI 反馈（Play/预取线程调用，全部是 volatile 写 + 灵动岛发布，渲染安全）。
     *
     * <p>主界面下载面板复用 {@code musicInfo.downloading/Progress}：gamdl 的子进程输出
     * 不解析进度（日志格式随版本漂移，解析错反而跳变），给 0.5 让 indeterminate 转子
     * 转起来 —— 用户要的是「在动」的确认，不是精确百分比。灵动岛持久卡在
     * {@link #endUiFeedback} 里收尾。</p>
     */
    private static void beginUiFeedback(com.muoniumplayer.core.ncm.music.dto.Music song) {
        try {
            var ext = com.muoniumplayer.core.MuoniumPlayerExtension.getInstance();
            if (ext != null && ext.musicInfo != null) {
                ext.musicInfo.downloading = true;
                ext.musicInfo.downloadProgress = 0.5;
                ext.musicInfo.downloadSpeed = "gamdl 准备中";
            }
            com.muoniumplayer.core.rendering.DownloadDynamicIsland
                    .showAppleGamdlPreparing(song == null ? "" : song.getName());
        } catch (Throwable ignored) {
            // 反馈是锦上添花：主类没起（测试环境）或字段未就绪绝不能打断兑换本身。
        }
    }

    /** 收掉下载面板的 indeterminate 态；灵动岛卡片由成功/失败方法各自收尾。 */
    private static void endUiFeedback() {
        try {
            var ext = com.muoniumplayer.core.MuoniumPlayerExtension.getInstance();
            if (ext != null && ext.musicInfo != null) {
                ext.musicInfo.downloading = false;
            }
        } catch (Throwable ignored) {
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
}
