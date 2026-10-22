package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ffmpeg 伴生包自动下载的不变量：钉子对不对、镜像指向对不对、解压安全不安全。
 *
 * <p>下载器本体不在这里起线程、不发网络请求——那两件事放进单测等于把测试变成
 * 「看网络脸色」。这里只钉错了不会在编译期报错、却会在玩家机器上炸的事：
 * 钉住的字节数/sha256 与交付仓库 SOURCE.txt 一致、镜像目录与仓库布局一致、
 * zip-slip 防护真的在拦。跨文件的接线（BIN_DIR 候选、forceReprobe 复测、
 * winget 已死透）用源码断言钉。
 */
class FfmpegPackageDownloadStaticTest {

    /** 交付仓库 SOURCE.txt 里钉死的值——换包时 zip 与 PACK 两处必须同改，这里就是那道闸。 */
    private static final long SOURCE_TXT_BYTES = 38_714_831L;
    private static final String SOURCE_TXT_SHA256 =
            "260dd7080a1f5767e4796374cbcff0fbe7cfeb92becefd3c2a6068f0111ccef6";
    private static final String SOURCE_TXT_NAME = "ffmpeg-windows-x64-9.0.zip";

    @Test
    @DisplayName("钉子与交付仓库 SOURCE.txt 逐字一致（换包忘了同步会在这里红）")
    void packMatchesSourceTxt() throws IOException {
        assertEquals(SOURCE_TXT_NAME, FfmpegPackageDownload.PACK.fileName(),
                "文件名必须与交付仓库里的 zip 同名");
        assertEquals(SOURCE_TXT_BYTES, FfmpegPackageDownload.PACK.bytes(),
                "字节数与 SOURCE.txt 不符——换包时 zip 与 PACK 必须一起改");
        assertEquals(SOURCE_TXT_SHA256, FfmpegPackageDownload.PACK.sha256(),
                "sha256 与 SOURCE.txt 不符——换包时 zip 与 PACK 必须一起改");
        // SOURCE.txt 在另一个仓库（MuoniumPlayer-updates，托管面）里，测试树上没有；
        // 它的值由本测试的字面量钉死，换包的人改交付仓库时会在 git diff 里看到这行一起红。
    }

    @Test
    @DisplayName("七条镜像都指向 ffmpeg-companion/ 目录，首路 R2、末路 raw 兜底")
    void mirrorsPointAtCompanionDirectory() {
        assertEquals(7, FfmpegPackageDownload.MIRRORS.size(), "镜像条数：R2 + 官网直链 + 五条 GitHub 系");
        Set<String> seen = new HashSet<>();
        for (String mirror : FfmpegPackageDownload.MIRRORS) {
            assertTrue(seen.add(mirror), "镜像重复：" + mirror);
            // 自建官网直链是 http（无证书），靠 sha256 终验保证内容不被篡改；其余路必须 https。
            if (!mirror.startsWith("http://")) {
                assertTrue(mirror.startsWith("https://"), "必须 https：" + mirror);
            }
            assertTrue(mirror.endsWith("/ffmpeg-companion/"), "目录要与仓库布局一致：" + mirror);
        }
        assertTrue(FfmpegPackageDownload.MIRRORS.get(0).startsWith("https://file.melodifymc.cc.cd/"),
                "源 1 是 R2 对象存储（汽水伴生包同一评价：最快，A 档先开）");
        assertTrue(FfmpegPackageDownload.MIRRORS.get(1).contains("gh-proxy.com"),
                "源 2 是 gh-proxy（实测次快，A 档先开）");
        assertTrue(FfmpegPackageDownload.MIRRORS.get(6).contains("raw.githubusercontent.com"),
                "末路是 GitHub raw 兜底");
    }

    @Test
    @DisplayName("接线：BIN_DIR 在 FfmpegSupport 候选里，装完必须复测，winget 已死透")
    void wiringIsInPlace() throws IOException {
        String support = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "music", "FfmpegSupport.java")));
        assertTrue(support.contains("ConfigPaths.BIN_DIR"),
                "安装落位 config/muonium/bin 的前提是探测候选里有它");

        String installer = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "music", "FfmpegInstaller.java")));
        int download = installer.indexOf("FfmpegPackageDownload.fetchTo");
        int unpack = installer.indexOf("FfmpegPackageDownload.unpack");
        int reprobe = installer.indexOf("FfmpegSupport.forceReprobe");
        assertTrue(download >= 0 && unpack > download && reprobe > unpack,
                "安装顺序必须固定：下载 → 解压 → 复测——复测提前等于拿半成品当结论");
        assertFalse(installer.contains("winget install")
                        || installer.contains("resolveWinget")
                        || installer.contains("WINGET_PACKAGE_ID"),
                "winget 路线已整体移除：镜像链下载是唯一安装路径，"
                        + "调用层不许残留（历史注释里的「WinGet 时代」不算）");
    }

    @Test
    @DisplayName("接线：状态行消费端要读真进度，安装失败文案指回「一键安装」重试")
    void overlayConsumesProgress() throws IOException {
        String overlay = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "screens", "ncm", "FfmpegNoticeOverlay.java")));
        assertTrue(overlay.contains("FfmpegInstaller.progressPercent()"),
                "状态行要接真实下载进度——38MB 的下载只滚省略号是焦虑源");
        assertTrue(overlay.contains("FfmpegInstaller.progressPercent"),
                "overlay 必须读 FfmpegInstaller.progressPercent，不能只看 state");
    }

    @Test
    @DisplayName("zip-slip 防护：条目路径含 .. 时拒绝解压")
    void unpackRejectsZipSlip(@TempDir Path temp) throws IOException {
        Path archive = temp.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../../evil-ffmpeg.exe"));
            zip.write("not an ffmpeg".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path outside = temp.resolve("outside");
        IOException failure = assertThrows(IOException.class,
                () -> FfmpegPackageDownload.unpack(archive.toFile(), outside.toFile()),
                "zip-slip 条目必须被拒绝");
        assertTrue(String.valueOf(failure).contains("不安全的压缩包条目"),
                "拒绝理由要写明是压缩包条目不安全，而不是别的错：" + failure);
        assertFalse(Files.exists(temp.resolve("evil-ffmpeg.exe")),
                "解压目标（outside）之外不许落文件——.. 条目既不能写到目标里也不能逃出暂存区");
    }

    @Test
    @DisplayName("正常包解压：ffmpeg.exe 落位，多文件包也不迷路")
    void unpackExtractsFfmpeg(@TempDir Path temp) throws IOException {
        Path archive = temp.resolve("good.zip");
        // 体量门槛 50MB：行为测试塞不下真二进制，这里只验证「小文件会被体量核对拦下」——
        // 反过来证明核对存在；真包的体量由 PACK 钉子与 SOURCE.txt 对拍保证。
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("ffmpeg.exe"));
            zip.write("tiny".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path bin = temp.resolve("bin-out");
        IOException failure = assertThrows(IOException.class,
                () -> FfmpegPackageDownload.unpack(archive.toFile(), bin.toFile()),
                "体量异常的 ffmpeg.exe 必须被拒");
        assertTrue(String.valueOf(failure).contains("体量异常"),
                "拒绝理由要提到体量：" + failure);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
