package com.muoniumplayer.core.ncm.lxsource;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三方库打包方式的两条铁律（这份在五棵树各跑一遍）。
 *
 * <p>背景见 docs/实现约定-第三方库打包.md。两条都是用户机器上炸出来的：
 *
 * <ol>
 *   <li><b>skija-shared / types 走 JarJar 嵌原 jar，不许 shadow 平铺</b>——
 *       平铺进主 jar 的类让主 jar 成为 JPMS 自动模块并导出 io.github.humbleui.types，
 *       与 atomchat 这类用 JarJar 嵌了独立 types 模块的 mod 一同装就 split package，
 *       modlauncher 建模块层时 ResolutionException 崩溃，游戏一行日志都没有（GitHub issue #4）。</li>
 *   <li><b>rhino 必须 relocate 到 muonium.rhino 前缀</b>——WorldEdit 7.3.8 把未 relocate 的
 *       rhino shade 进自己的 jar，与平铺的我们导出同一个 org.mozilla.javascript 包，
 *       同装即崩；「对方解包合并进自己模块」的形态 JarJar 去重管不到，只有 relocate 能共存。</li>
 * </ol>
 *
 * <p>断言只读 build.gradle 源文本，离线毫秒级；每条都附带「为什么」，
 * 让未来想改打包方式的人先撞上这段历史再动手。
 */
class ThirdPartyPackagingStaticTest {

    private static final Path LOADER_BUILD = ProjectPaths.loaderDir().resolve("build.gradle");
    private static final Path FABRIC_BUILD = ProjectPaths.fabric().resolve("build.gradle");

    @Test
    @DisplayName("skija-shared / types 走 JarJar：loader 侧声明了 jarJar 坐标与版本区间")
    void skijaTypes走JarJar() throws IOException {
        String build = read(LOADER_BUILD);
        // 两条 jarJar 坐标必须在（写成单引号 Gradle 字符串字面量；SkijaNativeCatalogStaticTest
        // 钉的版本号字面量在 fabric 侧，这里的坐标断言只管「嵌的方式」）。
        assertTrue(build.contains("jarJar 'io.github.humbleui:skija-shared:"),
                "skija-shared 必须走 jarJar 嵌原 jar（shadow 平铺会与别的 mod split package）");
        assertTrue(build.contains("jarJar('io.github.humbleui:types:") && build.contains("strictly '[0.1, 1.0)'"),
                "types 必须 jarJar 且带 strictly [0.1,1.0) 宽版本区间（与嵌旧版的 mod 共存靠它）");
        // embeddedLibs 里不许再出现这两个坐标：那是 shadow 平铺的死路
        assertFalse(build.contains("embeddedLibs 'io.github.humbleui:skija-shared"),
                "skija-shared 不许回 embeddedLibs 平铺（issue #4 的 split package 就是这么来的）");
        assertFalse(build.contains("embeddedLibs 'io.github.humbleui:types"),
                "types 不许回 embeddedLibs 平铺（issue #4 的 split package 就是这么来的）");
        // 发布 jar 侧必须挡掉平铺的类：jar 任务 exclude io/github/humbleui/**
        assertTrue(build.contains("exclude 'io/github/humbleui/**'"),
                "jar 任务必须 exclude io/github/humbleui/**：flatten 产物里的这两库类只准进 dev 类路径，不准进发布 jar");
    }

    @Test
    @DisplayName("rhino 全链 relocate：十个构建都不许再引用原包名坐标")
    void rhino全链relocate() throws IOException {
        String loader = read(LOADER_BUILD);
        String fabric = read(FABRIC_BUILD);
        // relocate 任务存在且双包树都改（classfile 是 rhino 的字节码生成器，漏了就是新 split package）
        assertTrue(loader.contains("relocate 'org.mozilla.javascript', 'muonium.rhino'")
                        && fabric.contains("relocate 'org.mozilla.javascript', 'muonium.rhino'"),
                "loader 与 fabric 两侧都必须有 rhino relocate 任务");
        assertTrue(loader.contains("relocate 'org.mozilla.classfile', 'muonium.rhino.classfile'")
                        && fabric.contains("relocate 'org.mozilla.classfile', 'muonium.rhino.classfile'"),
                "org.mozilla.classfile 也要 relocate：漏掉它就是新的 split package 隐患");
        // 原 rhino 坐标不许再直接挂到任何配置上（embeddedLibs / include 都算）——
        // 只允许出现在 relocateRhino 任务自己的 detachedConfiguration 里
        assertFalse(loader.replaceAll("(?m)^\\s*//.*$", "")
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .contains("embeddedLibs 'org.mozilla:rhino"),
                "loader 侧 embeddedLibs 不许直接引 rhino 坐标：必须走 relocateRhino 产物");
        assertFalse(fabric.replaceAll("(?m)^\\s*//.*$", "")
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .contains("include('org.mozilla:rhino"),
                "fabric 侧 include 不许直接嵌原 rhino：必须走 relocateRhino 产物");
        // 共享层源码只许 import muonium.rhino，不许再 import org.mozilla.javascript
        for (String f : List.of(
                "com/muoniumplayer/core/ncm/lxsource/LxBuffer.java",
                "com/muoniumplayer/core/ncm/lxsource/LxRuntime.java")) {
            String src = read(ProjectPaths.sourceFile(f));
            assertFalse(src.contains("org.mozilla.javascript"),
                    f + " 不许 import org.mozilla.javascript（改用 muonium.rhino 前缀）");
            assertTrue(src.contains("import muonium.rhino."),
                    f + " 应当 import muonium.rhino 前缀的 relocate 版 rhino");
        }
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path),
                java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
