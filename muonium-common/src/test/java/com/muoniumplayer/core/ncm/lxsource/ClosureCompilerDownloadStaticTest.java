package com.muoniumplayer.core.ncm.lxsource;

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
 * 脚本转译器按需下载的不变量：钉子对不对、镜像指向对不对、主包是不是零依赖、接线是不是齐的。
 *
 * <p>与字体/伴生包的静态测试同一路数。这里钉的几件事错了都不会在编译期报红、却会在
 * 玩家机器上出事：</p>
 * <ol>
 *   <li>{@link ClosureCompilerDownload} 钉住的字节数与 sha256（与清单仓库 libs/ 对拍）；</li>
 *   <li>镜像 URL 指向公开清单仓库的 libs/ 目录；</li>
 *   <li><b>主包对 closure 零编译期依赖</b>——所有 Java 源里只许出现字符串形式（反射桥），
 *       谁要是写回 import，摘依赖就白做了、测试编译期就会红；</li>
 *   <li>十份构建脚本（五棵树 × fabric/forge）里没有 closure 的打包行；</li>
 *   <li>接线：进自定义源界面要触发下载、运行状态行要有转译器状态、灵动岛要有三张卡。</li>
 * </ol>
 */
class ClosureCompilerDownloadStaticTest {

    @Test
    @DisplayName("钉子：文件名、字节数与 sha256 的形状")
    void pinnedArtifactShape() {
        assertEquals("closure-compiler-v20240317.jar", ClosureCompilerDownload.FILE_NAME);
        assertTrue(ClosureCompilerDownload.BYTES > 5_000_000L, "closure 至少 5 MB 量级");
        assertEquals(64, ClosureCompilerDownload.SHA256.length(), "sha256 必须是 64 位十六进制");
        assertTrue(ClosureCompilerDownload.SHA256.chars().allMatch(c -> Character.digit(c, 16) >= 0),
                "sha256 只许十六进制字符");
        assertEquals("muonium", ClosureCompilerDownload.libCacheDir().getParentFile().getName(),
                "缓存目录与 fonts/models 同一层级约定");
        assertEquals("config", ClosureCompilerDownload.libCacheDir().getParentFile()
                .getParentFile().getName(), "根挂在 config/ 下");
        assertEquals("libs", ClosureCompilerDownload.libCacheDir().getName());
    }

    @Test
    @DisplayName("三条镜像都是 https、都指向清单仓库的 libs/ 目录")
    void mirrorsPointAtTheManifestRepository() {
        assertEquals(3, ClosureCompilerDownload.MIRRORS.size(), "镜像条数与字体/伴生包同一套策略");
        for (String mirror : ClosureCompilerDownload.MIRRORS) {
            assertTrue(mirror.startsWith("https://"), "必须 https：" + mirror);
            assertTrue(mirror.endsWith("/libs/"), "目录要与仓库布局一致：" + mirror);
            assertTrue(mirror.contains("MuoniumPlayer-updates"), "指向公开清单仓库：" + mirror);
        }
        assertTrue(ClosureCompilerDownload.MIRRORS.get(0).contains("ghfast.top"), "源 1 是国内可达的代理");
        assertTrue(ClosureCompilerDownload.MIRRORS.get(1).contains("raw.githubusercontent.com"), "源 2 是原始地址");
        assertTrue(ClosureCompilerDownload.MIRRORS.get(2).contains("jsdelivr"), "源 3 是 CDN 兜底");
    }

    @Test
    @DisplayName("开关只认显式 false；清掉即恢复默认开")
    void propertyOnlyDisablesOnExplicitFalse() {
        try {
            System.clearProperty(ClosureCompilerDownload.PROP_ENABLED);
            assertTrue(ClosureCompilerDownload.enabled(), "默认必须是开");
            System.setProperty(ClosureCompilerDownload.PROP_ENABLED, "true");
            assertTrue(ClosureCompilerDownload.enabled());
            System.setProperty(ClosureCompilerDownload.PROP_ENABLED, "false");
            assertFalse(ClosureCompilerDownload.enabled(), "只有显式 false 才关");
        } finally {
            System.clearProperty(ClosureCompilerDownload.PROP_ENABLED);
        }
    }

    @Test
    @DisplayName("主包零编译期依赖：全部 Java 源里不许有 com.google.javascript 的 import")
    void noCompileTimeDependencyAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path java : ProjectPaths.allJavaFiles()) {
            String source = new String(Files.readAllBytes(java), StandardCharsets.UTF_8);
            for (String line : source.split("\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ") && trimmed.contains("com.google.javascript")) {
                    offenders.add(java.getFileName() + ": " + trimmed);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "closure 已经不进主包，谁也不许再 import 它（反射桥用字符串类名）：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("十份构建脚本里没有 closure 的打包行")
    void buildScriptsDropClosureEntirely() throws IOException {
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
            if (text.contains("closure-compiler")) {
                offenders.add(pair[0] + "/" + pair[1]);
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些构建脚本还留着 closure 的打包/剥壳配置（摘依赖要摘干净）：\n"
                        + String.join(", ", offenders));
    }

    @Test
    @DisplayName("接线：进游戏触发下载、页面兜重评、状态行可见、灵动岛三张卡、反射桥懒加载")
    void wiringIsInPlace() throws IOException {
        // 主触发：进游戏（与完整字体、Skija 伴生包同一时机），别等用户进自定义源页
        String extension = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "MuoniumPlayerExtension.java")));
        int lxInit = extension.indexOf("MultiThreadingUtil.runAsync(LxSourceService::init);");
        int ensure = extension.indexOf("ClosureCompilerDownload.ensureDownload();");
        assertTrue(lxInit >= 0 && ensure > lxInit,
                "进游戏就要触发转译器下载（MuoniumPlayerExtension.init，紧挨 LxSourceService 启动）");

        String overlay = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "screens", "ncm", "MusicSourceOverlay.java")));
        int page = overlay.indexOf("private void showLxSources() {");
        int trigger = overlay.indexOf("ClosureCompilerDownload.ensureDownload();", page);
        assertTrue(page >= 0 && trigger > page,
                "进自定义源页还要兜一次重评（接住启动时网络没好的会话）");
        assertTrue(overlay.contains("ClosureCompilerDownload.headlineSuffix()"),
                "运行状态行要带转译器下载状态，用户才分得清「脚本坏了」和「转译器在路上」");

        String downloader = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "lxsource", "ClosureCompilerDownload.java")));
        assertTrue(downloader.contains("static ClosureEs5Backend backend()"),
                "backend 要懒加载（下载完成 ≠ 立刻挂类加载器）");

        String island = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        assertTrue(island.contains("public static void showLxTranspilerProgress(")
                        && island.contains("public static void showLxTranspilerFailure(")
                        && island.contains("public static void showLxTranspilerReady("),
                "进度/失败/就绪三张卡都要有公开入口");

        String runtime = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "ncm", "lxsource", "LxRuntime.java")));
        int transpile = runtime.indexOf("LxTranspiler.toEs5(rawSource);");
        int fallback = runtime.indexOf("改用原始脚本");
        assertTrue(transpile >= 0 && fallback > transpile,
                "转译失败回退原始脚本的降级路必须保留——转译器未就绪时全靠它");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
