package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 听歌打卡组件（zstd-jni slim 包）按需下载的不变量：钉子、镜像、主包零依赖、接线。
 *
 * <p>这一份的协议背景必须钉在测试名里让人记住：NCBL 的记录块<b>必须</b>是标准 zstd 帧
 * （网易服务端钉死的格式），所以压缩器永远不能换实现——能动的只有「这份 JNI 包从哪来」。
 * 静态测试钉五件事：</p>
 * <ol>
 *   <li>{@link ZstdNativeDownload} 钉住的字节数与 sha256；</li>
 *   <li>五源镜像都指向清单仓库的 libs/ 目录；</li>
 *   <li>主包对 {@code com.github.luben.zstd} 零编译期依赖（只许反射桥里的字符串类名）；</li>
 *   <li>十份构建脚本里没有 zstd 的打包行（摘依赖要摘干净）；</li>
 *   <li>接线：登录触发下载、上报轮次有就绪门控、压缩走反射桥、灵动岛三张卡。</li>
 * </ol>
 */
class ZstdNativeDownloadStaticTest {

    @Test
    @DisplayName("钉子：文件名、字节数与 sha256 的形状")
    void pinnedArtifactShape() {
        assertEquals("zstd-jni-1.5.6-9-forge189-slim.jar", ZstdNativeDownload.FILE_NAME);
        assertTrue(ZstdNativeDownload.BYTES > 1_000_000L, "五平台 native 的 slim 包至少 1 MB 量级");
        assertEquals(64, ZstdNativeDownload.SHA256.length(), "sha256 必须是 64 位十六进制");
        assertTrue(ZstdNativeDownload.SHA256.chars().allMatch(c -> Character.digit(c, 16) >= 0),
                "sha256 只许十六进制字符");
        assertEquals("libs", ZstdNativeDownload.libCacheDir().getName(),
                "缓存与转译器 jar 同层（config/muonium/libs/）");
    }

    @Test
    @DisplayName("五条 GitHub 系镜像 + 两条 https 官网系 + 一条自建分发站，都指向 libs/ 目录")
    void mirrorsPointAtTheManifestRepository() {
        assertEquals(8, ZstdNativeDownload.MIRRORS.size(), "镜像条数：五条 GitHub 系 + Pages + R2 + 自建分发站");
        for (String mirror : ZstdNativeDownload.MIRRORS) {
            // 自建分发站是 http（无证书），靠 sha256 终验保证内容不被篡改；其余路必须 https。
            if (!mirror.startsWith("http://109.206.245.74/")) {
                assertTrue(mirror.startsWith("https://"), "必须 https：" + mirror);
            }
            assertTrue(mirror.endsWith("/libs/"), "目录要与仓库布局一致：" + mirror);
        }
        assertTrue(ZstdNativeDownload.MIRRORS.get(0).contains("ghfast.top"), "源 1 是 ghfast 代理");
        assertTrue(ZstdNativeDownload.MIRRORS.get(1).contains("gh-proxy.com"), "源 2 是 gh-proxy（直连实测最好）");
        assertTrue(ZstdNativeDownload.MIRRORS.get(2).contains("raw.githubusercontent.com"), "源 3 是原始地址");
        assertTrue(ZstdNativeDownload.MIRRORS.get(3).contains("ghproxy.net"), "源 4 是 ghproxy.net");
        assertTrue(ZstdNativeDownload.MIRRORS.get(4).contains("jsdelivr"), "源 5 是 CDN 兜底");
        // 两条官网系兜底不走 GitHub；Pages 在 site 仓库 dl/ 下逐字节镜像，R2 是 fenfa 桶直链。
        assertTrue(ZstdNativeDownload.MIRRORS.get(5).startsWith("https://melodifymc.cc.cd/dl/"), "源 6 是官网（Cloudflare Pages）直链兜底");
        assertTrue(ZstdNativeDownload.MIRRORS.get(6).startsWith("https://file.melodifymc.cc.cd/"), "源 7 是 R2 对象存储直链兜底");
        assertTrue(ZstdNativeDownload.MIRRORS.get(7).startsWith("http://109.206.245.74/"), "源 8 是自建分发站（独立于 Cloudflare 与 GitHub 的第三托管面）");
    }

    @Test
    @DisplayName("开关只认显式 false；清掉即恢复默认开")
    void propertyOnlyDisablesOnExplicitFalse() {
        try {
            System.clearProperty(ZstdNativeDownload.PROP_ENABLED);
            assertTrue(ZstdNativeDownload.enabled(), "默认必须是开");
            System.setProperty(ZstdNativeDownload.PROP_ENABLED, "true");
            assertTrue(ZstdNativeDownload.enabled());
            System.setProperty(ZstdNativeDownload.PROP_ENABLED, "false");
            assertFalse(ZstdNativeDownload.enabled(), "只有显式 false 才关");
        } finally {
            System.clearProperty(ZstdNativeDownload.PROP_ENABLED);
        }
    }

    @Test
    @DisplayName("主包零编译期依赖：com.github.luben.zstd 只许出现在反射桥的字符串里")
    void noCompileTimeDependencyAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path java : ProjectPaths.allJavaFiles()) {
            String source = new String(Files.readAllBytes(java), StandardCharsets.UTF_8);
            for (String line : source.split("\n", -1)) {
                String trimmed = line.trim();
                boolean isImport = trimmed.startsWith("import ") && trimmed.contains("com.github.luben");
                boolean isReflected = trimmed.contains("Class.forName");
                if (isImport && !isReflected) {
                    offenders.add(java.getFileName() + ": " + trimmed);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "zstd-jni 已经不进主包，谁也不许再 import 它（反射桥用字符串类名）：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("十份构建脚本里没有 zstd 的打包行")
    void buildScriptsDropZstdEntirely() throws IOException {
        List<String> offenders = new ArrayList<>();
        String[][] scripts = {
                {"1.20.1 recode", "fabric"}, {"1.20.1 recode", "forge"},
                {"1.21.1", "fabric"}, {"1.21.1", "neoforge"},
                {"1.21.11 recode", "fabric"}, {"1.21.11 recode", "neoforge"},
                {"26.2", "fabric"}, {"26.2", "neoforge"},
                {"26.3", "fabric"}, {"26.3", "neoforge"},
        };
        for (String[] pair : scripts) {
            Path gradle = ProjectPaths.repoRoot().resolve(
                    Paths.get(pair[0], pair[1], "build.gradle"));
            assertTrue(Files.isRegularFile(gradle), "构建脚本不存在: " + gradle);
            String text = new String(Files.readAllBytes(gradle), StandardCharsets.UTF_8);
            if (text.contains("zstd-jni")) {
                offenders.add(pair[0] + "/" + pair[1]);
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些构建脚本还留着 zstd 的打包配置（摘依赖要摘干净）：\n"
                        + String.join(", ", offenders));
    }

    @Test
    @DisplayName("接线：登录触发下载、上报有就绪门控、压缩走反射桥、灵动岛三张卡")
    void wiringIsInPlace() throws IOException {
        // 唯一触发点：loadNCM 是扫码/Cookie/启动自动恢复三条登录路的汇聚处
        String cloud = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "music", "CloudMusic.java")));
        int login = cloud.indexOf("Logged in as %s(%s)");
        int ensure = cloud.indexOf("ZstdNativeDownload.ensureJar();");
        assertTrue(login >= 0 && ensure > login,
                "登录成功（loadNCM 走到 Logged in as）后要触发组件下载");

        // 上报两轮都要有就绪门控：组件没就绪安静跳过，而不是每首歌弹失败卡
        String reporter = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                        "NeteasePlaybackHistoryReporter.java")));
        assertEquals(2, countOf(reporter, "if (ZstdNativeDownload.compressor() == null) {"),
                "开始上报与时长上报两轮都要有就绪门控");
        assertEquals(2, countOf(reporter, "ZstdNativeDownload.ensureJar();"),
                "门控里要顺手补一次触发（接住启动时网络没好的会话）");

        // 压缩必须走反射桥，桥里只许一个反射点（Zstd.compress(byte[])）
        String uploader = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                        "NeteaseClientLogUploader.java")));
        assertTrue(uploader.contains("ZstdNativeDownload.compressor()"),
                "compressZstd 必须向下载器要反射桥");
        assertFalse(uploader.contains("import com.github.luben"),
                "上传器不许 import zstd（主包零编译期依赖）");
        String bridge = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "music", "ZstdCompressor.java")));
        assertTrue(bridge.contains("\"compress\", byte[].class"),
                "反射面只许 Zstd.compress(byte[]) 一个点，别把桥写复杂");

        String island = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        assertTrue(island.contains("public static void showZstdNativeProgress(")
                        && island.contains("public static void showZstdNativeFailure(")
                        && island.contains("public static void showZstdNativeReady("),
                "进度/失败/就绪三张卡都要有公开入口");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
