package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.ConfigPaths;
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
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ffmpeg 伴生包的下载与落位：把托管仓库 {@code MuoniumPlayer-updates} 的
 * {@code ffmpeg-companion/} 安装包取到本机，解压到 {@link ConfigPaths#BIN_DIR}
 * ——那正是 {@link FfmpegSupport} 候选探测列表的第 3 位，解压完
 * {@link FfmpegSupport#forceReprobe()} 一测就能用，不需要重启游戏。
 *
 * <h2>包从哪来</h2>
 * 包内只有一个 {@code ffmpeg.exe}：Gyan Doshi 的官方 Windows 构建（9.0 essentials，
 * 版本串 {@code ffmpeg version 9.0-essentials_build-www.gyan.dev}），二进制未做任何
 * 修改，只是去掉了 mod 用不到的 ffplay / ffprobe（仓库里没有任何 ffprobe 调用点），
 * 体积从 100 MB 出头压到 38 MB。来源、许可证（GPL v3）与校验值见交付仓库
 * {@code ffmpeg-companion/SOURCE.txt}。
 *
 * <h2>镜像链</h2>
 * 与汽水后端伴生包（{@code QishuiCompanionDownload}）同一套七条镜像：R2 直链、
 * gh-proxy、官网直链、ghfast、jsdelivr、ghproxy.net、GitHub raw 兜底。
 * {@link HttpDownloadRace} 会按梯队竞速，谁先校验通过用谁。
 *
 * <h2>校验与安全</h2>
 * 下载的是「会以本机权限执行的可执行文件」，按更新清单同一标准：字节数与 sha256
 * 钉死在 {@link #PACK}（{@link HttpDownloadRace#fetch} 在接收流时同步计算校验），
 * 长度或哈希对不上直接判失败、绝不解压。解压带 zip-slip 防护（与汽水安装器同款形状），
 * 落位后再核对 ffmpeg.exe 真的在、体量合理。
 *
 * <h2>换包流程（两处必须一起改）</h2>
 * 交付仓库里的 zip、本类的 {@link #PACK}——SOURCE.txt 与静态测试
 * {@code FfmpegPackageDownloadStaticTest} 各自把两面对齐钉死。
 */
final class FfmpegPackageDownload {

    /**
     * 下载镜像链，前五路与汽水伴生包同一套（ghfast / gh-proxy / raw / ghproxy.net /
     * jsdelivr），首路是 R2 对象存储直链（Cloudflare，公网出口免费）。官网直链
     * （{@code muoniummc.cc.cd/dl/}）排在 R2 之后作第二梯队。
     */
    static final List<String> MIRRORS = List.of(
            "https://file.melodifymc.cc.cd/ffmpeg-companion/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/ffmpeg-companion/",
            "http://muoniummc.cc.cd/dl/ffmpeg-companion/",
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/ffmpeg-companion/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/ffmpeg-companion/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/ffmpeg-companion/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/ffmpeg-companion/");

    /** 开发期替代源：从一个本地目录取包，不经网络（与汽水的同名属性同款）。 */
    static final String PROP_LOCAL_DIR = "muonium.ffmpeg.companionDir";

    /** 伴生包：文件名 + 字节数 + sha256，三个都必须精确（换包时与交付仓库同改）。 */
    record Pack(String fileName, long bytes, String sha256) {
    }

    /** ffmpeg-windows-x64-9.0.zip：Gyan 9.0 essentials 去掉 ffplay/ffprobe 的重打包。 */
    static final Pack PACK = new Pack("ffmpeg-windows-x64-9.0.zip", 38_714_831L,
            "260dd7080a1f5767e4796374cbcff0fbe7cfeb92becefd3c2a6068f0111ccef6");

    /** 解压后 ffmpeg.exe 的体量下限：真二进制约 98 MB，低于这个数只可能是半成品。 */
    private static final long MIN_FFMPEG_BYTES = 50L * 1024L * 1024L;

    private FfmpegPackageDownload() {
    }

    /**
     * 取包到 {@code archive}：本地替代源优先，否则多路竞速下载（见 {@link HttpDownloadRace}），
     * 竞速关掉时退回旧的逐路串行回退。字节数 + sha256 全验，通过即落盘。
     *
     * @param progress 0~100 的整型进度回调，在下载线程上执行；可为 {@code null}
     */
    static void fetchTo(File archive, IntConsumer progress) throws Exception {
        String localDir = System.getProperty(PROP_LOCAL_DIR);
        if (localDir != null && !localDir.isBlank()) {
            File local = new File(localDir.trim(), PACK.fileName());
            if (!local.isFile()) {
                throw new IOException("本地 ffmpeg 伴生包目录里没有 " + PACK.fileName() + "：" + localDir);
            }
            checkAgainst(PACK, local.length(), sha256(local));
            Files.copy(local.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (progress != null) progress.accept(100);
            return;
        }
        List<String> urls = new ArrayList<String>(MIRRORS.size());
        for (String mirror : MIRRORS) {
            urls.add(mirror + PACK.fileName());
        }
        if (HttpDownloadRace.enabled()) {
            HttpDownloadRace.fetch(urls, PACK.bytes(), PACK.sha256(), archive, progress);
            return;
        }
        Exception last = null;
        for (String url : urls) {
            try {
                download(url, archive, progress);
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

    /** 单次下载：读流与流式校验同步走，进度按整百分比推进。失败也断开连接。 */
    private static void download(String url, File archive, IntConsumer progress) throws Exception {
        HttpURLConnection connection = openFollowing(URI.create(url).toURL());
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long total = connection.getContentLengthLong();
            if (total > 0 && total != PACK.bytes()) {
                throw new IOException("大小不符（Content-Length " + total + "，应为 " + PACK.bytes() + "）");
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
                    if (done > PACK.bytes()) {
                        throw new IOException("下超了：已收 " + done + " 字节，应为 " + PACK.bytes());
                    }
                    if (progress != null) {
                        int percent = (int) Math.min(100L, done * 100L / PACK.bytes());
                        if (percent != shown) {
                            shown = percent;
                            progress.accept(percent);
                        }
                    }
                }
            }
            checkAgainst(PACK, archive.length(), hex(digest.digest()));
        } finally {
            connection.disconnect();
        }
    }

    /** 重定向自己跟（镜像会 30x 到 CDN），最多 6 跳。与汽水下载器同款。 */
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
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-FfmpegCompanion/1.0");
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

    /**
     * 解压伴生包到 {@code binDir}（zip-slip 防护，与汽水安装器同款形状），落位后核对
     * ffmpeg.exe 真的在、体量合理。包内只有一个 ffmpeg.exe，多出来的条目照常解压——
     * 将来往包里加 LICENSE 之类的文件不用改这里。
     */
    static void unpack(File archive, File binDir) throws Exception {
        if (!binDir.exists() && !binDir.mkdirs() && !binDir.isDirectory()) {
            throw new IOException("无法创建安装目录: " + binDir);
        }
        String root = binDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(binDir, entry.getName());
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
        File ffmpeg = new File(binDir, "ffmpeg.exe");
        if (!ffmpeg.isFile() || ffmpeg.length() < MIN_FFMPEG_BYTES) {
            throw new IOException("解压后 ffmpeg.exe 缺失或体量异常：" + binDir.getAbsolutePath());
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

    /** 日志前缀与 FfmpegSupport / FfmpegInstaller 同族。 */
    static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }
}
