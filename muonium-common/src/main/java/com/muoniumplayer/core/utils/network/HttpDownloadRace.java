package com.muoniumplayer.core.utils.network;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/**
 * 多路并发竞速下载：镜像按实测表现分两档<b>分批放量</b>——先开实测最快最稳的 A 档，
 * 15 秒观察窗内没人交卷再放 B 档；谁先完整通过校验就用谁，其余立刻掐断。
 *
 * <p>字体 / Skija 伴生包 / zstd / closure / 汽水后端五个下载器共用的下载引擎，取代以前
 * 「逐路串行回退」的镜像链走法。动机：串行模式下「连上了但极慢」的镜像不会被识别为失败
 * —— {@code readTimeout} 只管字节间空闲，一条 50 KB/s 的涓流可以合法地占住整次下载
 * 尝试几十分钟；竞速下它最多浪费一点头部流量，下载时长由最快的那条线决定。
 * 分批放量再省一层：A 档（实测快且稳的三条）足够交卷时，B 档（慢、抖、或可能已死的
 * 五条）一条线程都不起，头部流量也不花。</p>
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>A/B 分批</b>：A 档 = 实测最快的 R2、gh-proxy、自建分发站（2026-10-16 逐线
 *       3 次实测，见 {@link #isTierA}）；下载开始只起 A 档，观察窗
 *       {@link #STAGING_DELAY_MILLIS}（15 秒，{@code -Dmuonium.downloadStageMillis} 可调，
 *       0 = 全量竞速）内无人交卷才放 B 档；A 档全部失败也立即放量。<b>放量不回收</b>：
 *       A 档线程继续跑，绝不 interrupt/disconnect——它们可能正是慢热的那条快线。</li>
 *   <li><b>写入隔离</b>：每路写自己的临时文件（{@code <目标>.race<序号>}，序号=原始列表
 *       下标，与启动批次无关），冠军产生后原子改名成目标文件，其余路的临时文件各自在
 *       收尾时删掉。绝不并发写同一个文件。</li>
 *   <li><b>校验与各下载器旧的 {@code download()} 同一套</b>：连接 20 秒、读空闲 60 秒、
 *       HTTP 非 2xx 拒收、Content-Length 不符拒收、下载中越界拒收、收完字节数 + sha256
 *       双验。sha256 在接收时就地计算，不是收完再读一遍文件——13 MB 的二次读盘没有必要。</li>
 *   <li><b>进度只报领先路</b>：{@code onProgress} 收到的永远是「当前已收字节最多的那一路」
 *       换算的百分比（{@code leaderBytes} 只增不减），落后路的进度被丢弃——回调方（灵动岛）
 *       看到的数字单调递增，不会因为慢线先起步而回跳。节流（500ms 下限）仍在下载器侧，
 *       引擎每次过新字节都会尝试推，频率由节流器压住。</li>
 *   <li><b>失败语义</b>：<b>所有已启动的路</b>都失败才抛（每路错误一行摘要日志）；任何一路
 *       成功即成功。整轮重试预算（{@code MAX_ATTEMPTS} / 冷却）仍由调用方管，这里不管。</li>
 *   <li><b>断点续传有意放弃</b>：竞速下各路从零开始，旧「单一 .part + Range 续传」方案
 *       不再成立。gh-proxy 中途截断的场景由「换一路重拉」的并发能力兜住（别的路先到）；
 *       要回旧行为，{@code -Dmuonium.downloadRace=false}，各下载器里串行回退路径原样保留。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 每路一条守护线程（{@code muonium-download-race-N}）；{@link #fetch} 对每条<b>已启动</b>的
 * 线程 join 收尸，任何退出路径（成功 / 全败 / 中断）都不留活线程。冠军产生的瞬间其余路
 * 可能在阻塞的 {@code read()} 里，它们会在下一个字节到达、或读空闲超时（≤60s）后退出——
 * 文件层面不冲突（各写各的临时文件），不必为提前几秒的退场上中断手段。
 */
public final class HttpDownloadRace {

    /** 单路连接超时（与各下载器旧值一致）。 */
    static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    /** 单路读空闲超时（与各下载器旧值一致）：60 秒一个字节都没到才算这路挂了。 */
    static final int READ_TIMEOUT_MILLIS = 60_000;
    /** 最多跟 6 跳重定向（镜像会 30x 到 CDN）。 */
    private static final int MAX_REDIRECTS = 6;
    /** 临时文件后缀 + 槽位：{@code <目标>.race<N>}。 */
    static final String PART_SUFFIX = ".race";

    /**
     * A 档观察窗：A 档开跑后等这么久，无人交卷才放 B 档。2026-10-16 实测 8 线 3 次均值下，
     * A 档三条（R2 2.25 / gh-proxy 1.75 / 分发站 1.11 MB/s）拉 2~14MB 的文件都在 15 秒内
     * 有结论；B 档全是慢、抖或已死的线，窗口内空等它们没有收益。
     */
    static final long STAGING_DELAY_MILLIS = 15_000L;

    /** 总开关：显式 {@code -Dmuonium.downloadRace=false} 时调用方退回串行回退。 */
    public static final String PROP_ENABLED = "muonium.downloadRace";
    /** 观察窗覆盖：{@code -Dmuonium.downloadStageMillis=0} 即全量竞速。 */
    public static final String PROP_STAGE_MILLIS = "muonium.downloadStageMillis";

    private HttpDownloadRace() {
    }

    /** 竞速是否启用：默认开，只有显式 false 才关。调用方与静态测试都要读它。 */
    public static boolean enabled() {
        String value = System.getProperty(PROP_ENABLED);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /** 观察窗时长（毫秒）。默认 15 秒；显式设置（含 0）以设置为准。 */
    static long stagingDelayMillis() {
        String value = System.getProperty(PROP_STAGE_MILLIS);
        if (value == null || value.isBlank()) {
            return STAGING_DELAY_MILLIS;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException broken) {
            return STAGING_DELAY_MILLIS;
        }
    }

    /**
     * A 档判定：实测（2026-10-16，13.8MB ×3 次均值 2.25 / 1.75 / 1.11 MB/s，全成功率）
     * 快且稳的三条——R2 对象存储、gh-proxy 代理、自建分发站。三者分属三个互不相关的
     * 托管面（CF-R2 / GitHub 代理 / 独立 VPS），A 档同时全灭的概率很低。
     * 不在名单里的 URL 一律按 B 档处理（保守：没实测过的不当快线）。
     */
    static boolean isTierA(String url) {
        return url.startsWith("https://file.melodifymc.cc.cd/")
                || url.startsWith("https://gh-proxy.com/")
                || url.startsWith("http://muoniummc.cc.cd/");
    }

    /** 一次竞速的结果：冠军的 URL 与下到文件的总字节数（应与钉死值一致，校验过）。 */
    public record Winner(String url, long bytes) {
    }

    /**
     * 竞速下载一个文件。
     *
     * @param urls           完整 URL 列表（镜像前缀 + 文件名，调用方拼好）
     * @param expectedBytes  钉死的字节数；Content-Length 前缀、越界与终验都按它
     * @param expectedSha256 钉死的 64 位十六进制 sha256
     * @param target         正式落点；临时文件是 {@code target.race<N>}，冠军原子改名成它
     * @param onProgress     进度回调，参数是 0~100 的全局领先百分比；可为 null。
     *                       回调在竞速线程上执行，实现方自行保证线程安全（灵动岛接口本来就是）。
     * @return 冠军信息（哪条 URL 赢的、下了多少字节），日志用
     * @throws IOException 全部路都失败（或入参不合法）时；每路错误已逐行打进日志
     */
    public static Winner fetch(List<String> urls, long expectedBytes, String expectedSha256,
                               File target, IntConsumer onProgress) throws IOException {
        if (urls == null || urls.isEmpty()) {
            throw new IOException("镜像列表为空");
        }
        if (expectedBytes <= 0 || expectedSha256 == null || expectedSha256.length() != 64) {
            throw new IOException("钉死的字节数或 sha256 不合法");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("建不了下载目录：" + parent);
        }

        final int count = urls.size();
        List<AtomicReference<String>> failures = new ArrayList<AtomicReference<String>>(count);
        // 冠军闸：第一条完整通过校验的路 CAS 成功，其余路看到闸落下就退场。
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicLong leaderBytes = new AtomicLong(0L);
        AtomicReference<String> winnerUrl = new AtomicReference<>();
        // 每路的失败记录与线程在 spawn 时登记；join 只覆盖「实际启动过」的线程。
        final AtomicReference<String>[] failureRefs = new AtomicReference[count];
        final Thread[] allWorkers = new Thread[count];
        final List<Thread> started = new ArrayList<Thread>(count);

        for (int index = 0; index < count; index++) {
            final int slot = index;
            final String url = urls.get(index);
            final File part = partFile(target, slot);
            final AtomicReference<String> failure = new AtomicReference<>();
            failureRefs[index] = failure;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (settled.get()) {
                            failure.set("别路先到，本路未开跑");
                            return;
                        }
                        downloadOne(url, part, expectedBytes, expectedSha256,
                                settled, leaderBytes, onProgress);
                        // 这一路完整通过了校验。抢冠：抢到的人原子改名、记 URL；没抢到的退场。
                        if (settled.compareAndSet(false, true)) {
                            Files.move(part.toPath(), target.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                            winnerUrl.set(url);
                        } else {
                            failure.set("别路先到，本路到终点了也没用");
                        }
                    } catch (Throwable thrown) {
                        failure.set(thrown.getClass().getSimpleName()
                                + (thrown.getMessage() == null ? "" : (": " + thrown.getMessage())));
                    } finally {
                        deleteQuietly(part);
                    }
                }
            }, "muonium-download-race-" + (slot + 1));
            worker.setDaemon(true);
            allWorkers[index] = worker;
        }

        // A 档先行：只起实测快且稳的那几条。B 档线程此刻不创建、不占资源。
        boolean tierAPresent = false;
        boolean tierBPresent = false;
        for (int index = 0; index < count; index++) {
            if (isTierA(urls.get(index))) {
                allWorkers[index].start();
                started.add(allWorkers[index]);
                tierAPresent = true;
            } else {
                tierBPresent = true;
            }
        }
        if (!tierAPresent) {
            // 列表里没有一条 A 档（调用方给了未知镜像集）：直接全量竞速，不空等。
            tierBPresent = false;
            for (Thread worker : allWorkers) {
                worker.start();
                started.add(worker);
            }
        }

        // 观察窗：A 档跑 15 秒无人交卷（或 A 档已全部阵亡）就放 B 档，A 档继续跑不中断。
        // delay == 0 是「全量竞速」档位（PROP_STAGE_MILLIS 注释），语义是 A/B 同跑——
        // 以前整段被 delay > 0 挡住，B 档永远不起，实际反而只剩 A 档。
        long delay = stagingDelayMillis();
        if (tierBPresent && tierAPresent && delay == 0L) {
            for (int index = 0; index < count; index++) {
                if (!isTierA(urls.get(index)) && !allWorkers[index].isAlive() && !started.contains(allWorkers[index])) {
                    allWorkers[index].start();
                    started.add(allWorkers[index]);
                }
            }
        } else if (tierBPresent && tierAPresent && delay > 0L) {
            long deadline = System.currentTimeMillis() + delay;
            while (System.currentTimeMillis() < deadline) {
                if (settled.get()) {
                    break;
                }
                if (allStartedFailuresSettled(started, failureRefs, allWorkers)) {
                    // A 档全阵亡：等窗口没有意义，立即放量。
                    break;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!settled.get()) {
                System.out.println("[MuoniumPlayer/DownloadRace] A 档 " + delay + "ms 内无人交卷，放 B 档（A 档继续跑）");
            }
            for (int index = 0; index < count; index++) {
                if (!isTierA(urls.get(index)) && !allWorkers[index].isAlive() && !started.contains(allWorkers[index])) {
                    allWorkers[index].start();
                    started.add(allWorkers[index]);
                }
            }
        }

        try {
            for (Thread worker : started) {
                worker.join();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            settled.set(true);
            throw new IOException("竞速下载被中断", interrupted);
        }

        String winner = winnerUrl.get();
        if (winner == null) {
            for (int index = 0; index < count; index++) {
                if (allWorkers[index] != null && started.contains(allWorkers[index])) {
                    System.out.println("[MuoniumPlayer/DownloadRace] 源 " + (index + 1)
                            + "（" + hostOf(urls.get(index)) + "）：" + failureRefs[index].get());
                }
            }
            throw new IOException("全部已启动的 " + started.size() + " 条线路都失败了（详见上方日志）");
        }
        return new Winner(winner, expectedBytes);
    }

    /** 已启动的 A 档线程是否已经全部结束（无论成败）——是的话观察窗可以提前结束。 */
    private static boolean allStartedFailuresSettled(List<Thread> started,
                                                     AtomicReference<String>[] failureRefs,
                                                     Thread[] allWorkers) {
        for (Thread worker : started) {
            if (worker.isAlive()) {
                return false;
            }
        }
        // started 非空且全死 = A 档全阵亡（B 档还没起）。
        return !started.isEmpty();
    }

    /** 第 slot 路的临时文件：正式落点旁的 {@code <名>.race<slot>}。 */
    static File partFile(File target, int slot) {
        return new File(target.getAbsolutePath() + PART_SUFFIX + slot);
    }

    /**
     * 单路下载主体：建连 → 校验头 → 流式读（同步 sha256 + 领先路进度）→ 终验。
     * 通过后由调用方（{@link #fetch} 的工作线程）抢冠改名；这里只负责把文件拉对。
     */
    private static void downloadOne(String url, File part, long expectedBytes, String expectedSha256,
                                    AtomicBoolean settled, AtomicLong leaderBytes,
                                    IntConsumer onProgress) throws IOException {
        HttpURLConnection connection = openFollowing(URI.create(url).toURL(), 0);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > 0 && contentLength != expectedBytes) {
                throw new IOException("Content-Length 不符：得到 " + contentLength
                        + "，应为 " + expectedBytes);
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception broken) {
                throw new IOException("JVM 缺 SHA-256", broken);
            }
            long done = 0L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (settled.get()) {
                        // 别路先到：立刻停写停读。文件层不冲突（各写各的），下一行抛出即可。
                        throw new IOException("别路先到，本路中途退出");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    if (done > expectedBytes) {
                        throw new IOException("下超了：已收 " + done + " 字节，应为 " + expectedBytes);
                    }
                    reportProgress(done, leaderBytes, expectedBytes, onProgress);
                }
            }
            if (done != expectedBytes) {
                throw new IOException("字节数不符：收到 " + done + "，应为 " + expectedBytes);
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("sha256 不符：本路内容与发布包不一致（得到 " + actual + "）");
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 进度上报：本路字节尝试刷新 {@code leaderBytes}（只增不减），刷新成功（＝自己此刻
     * 就是全局领先者）才推一次回调。落后路到这里原样返回，进度永不回跳。
     */
    private static void reportProgress(long done, AtomicLong leaderBytes,
                                       long expectedBytes, IntConsumer onProgress) {
        if (onProgress == null) {
            return;
        }
        long current = leaderBytes.get();
        while (done > current) {
            if (leaderBytes.compareAndSet(current, done)) {
                onProgress.accept((int) Math.min(100L, done * 100L / expectedBytes));
                return;
            }
            current = leaderBytes.get();
        }
    }

    /** 建连接并自己跟重定向（镜像会 30x 到 CDN），超时与各下载器旧值一致。 */
    private static HttpURLConnection openFollowing(URL url, int depth) throws IOException {
        if (depth > MAX_REDIRECTS) {
            throw new IOException("重定向过多");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-DownloadRace/1.0");
        try {
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
        } catch (IOException failure) {
            connection.disconnect();
            throw failure;
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception broken) {
            return url;
        }
    }

    private static void deleteQuietly(File file) {
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
}
