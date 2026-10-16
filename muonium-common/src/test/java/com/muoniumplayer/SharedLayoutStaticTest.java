package com.muoniumplayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.muoniumplayer.testsupport.ProjectPaths;

/**
 * 共享层的形状守卫（这份在五棵树各跑一遍）。
 *
 * <p>{@code muonium-common/} 是五棵树的同一份编译输入。「版本差异只留各树的 compat/ 与
 * mixin/」这条规矩能不能守住，取决于三件事有没有人半夜偷偷破：
 *
 * <ol>
 *   <li>同一个类在共享根和本树根各放一份 —— javac 会报重复类，但那要等到有人构建才发现，
 *       而且报错只说「类重复」，看不出是谁把文件搬回来的；</li>
 *   <li>共享层里出现 {@code net.fabricmc} / {@code net.neoforged} 这类加载器符号 ——
 *       那一刻起这份源码不再中立，另一棵树的另一个加载器编不过；</li>
 *   <li>留守清单悄悄长胖 —— 多一个留守文件就多一份手工同步的负担，必须是有意识地加，
 *       不能是「先复制一份出去，以后再收」。</li>
 * </ol>
 *
 * <p>名单来自 {@code tools/share-trees.py} 的分类结果：那个脚本负责搬，本测试负责事后不走样。
 * 要动这张表，就同时去 {@code muonium-common/SHARE-MANIFEST.md} 与脚本的 REASONS 里补一句
 * 「为什么不能共享」——三个地方一起改，正是为了让改这件事变得不划算。
 */
class SharedLayoutStaticTest {

    /** 五棵树都必须各自持有的版本差异层。 */
    private static final Set<String> REQUIRED_MAIN_JAVA = new LinkedHashSet<String>(Arrays.asList(
            // --- Minecraft / LWJGL 的 API 差异，本就该各树一份 ---
            "com/muoniumplayer/compat/gl/GL11.java",
            "com/muoniumplayer/compat/gl/GL14.java",
            "com/muoniumplayer/compat/lwjgl2/Display.java",
            "com/muoniumplayer/compat/lwjgl2/Keyboard.java",
            "com/muoniumplayer/compat/lwjgl2/Mouse.java",
            "com/muoniumplayer/compat/lwjgl2/Sys.java",
            "com/muoniumplayer/compat/mc/FontRenderer.java",
            "com/muoniumplayer/compat/mc/GuiButton.java",
            "com/muoniumplayer/compat/mc/GuiScreen.java",
            "com/muoniumplayer/compat/mc/GuiTextField.java",
            "com/muoniumplayer/compat/mc/ScaledResolution.java",
            "com/muoniumplayer/compat/mc/ScreenCapture.java",
            // --- 3D 世界歌词：相机/公告牌/桥全是 MC 触点，各树一份（共享层只放语义）---
            "com/muoniumplayer/compat/mc/CameraAccess.java",
            "com/muoniumplayer/compat/mc/WorldBillboard.java",
            "com/muoniumplayer/compat/mc/WorldLyricsBridge.java",
            "com/muoniumplayer/MuoniumPlayerClient.java",
            "com/muoniumplayer/render/skija/SkijaHost.java",
            "com/muoniumplayer/api/impl/GLStateManagerImpl.java",
            "com/muoniumplayer/api/impl/OpenAPIImpl.java",
            // --- 功能集还没对齐的（差异是历史欠账，不是 API 逼的）---
            "com/muoniumplayer/core/settings/HudConfig.java",
            "com/muoniumplayer/core/settings/HudSetting.java",
            "com/muoniumplayer/core/screens/ncm/CoverflowOverlay.java",
            // --- 26.x 改了取当前屏幕的方法名 ---
            "com/muoniumplayer/core/rendering/IslandAmbientStatus.java",
            "com/muoniumplayer/core/rendering/IslandVisibilityGate.java",
            "com/muoniumplayer/core/screens/GuiAnnouncements.java",
            "com/muoniumplayer/core/screens/GuiFfmpegNotice.java",
            "com/muoniumplayer/core/screens/GuiUpdateNotice.java",
            "com/muoniumplayer/core/screens/RenderLayerNotice.java",
            "com/muoniumplayer/core/screens/TitleMenuAnnouncementButton.java",
            "com/muoniumplayer/core/screens/hud/GuiHudEditor.java",
            "com/muoniumplayer/core/screens/hud/GuiHudEditorPro.java",
            "com/muoniumplayer/core/screens/hud/GuiHudEditorFolia.java",
            "com/muoniumplayer/core/screens/hud/HudEditorLayout.java",
            "com/muoniumplayer/core/screens/hud/HudSettingsModel.java"));

    /** 只在部分树存在的（mixin 注入点、各树网络通道）。 */
    private static final Set<String> OPTIONAL_MAIN_JAVA = new LinkedHashSet<String>(Arrays.asList(
            "com/muoniumplayer/mixin/FramebufferMixin.java",
            "com/muoniumplayer/mixin/GlSurfaceMixin.java",
            "com/muoniumplayer/mixin/MouseScrollMixin.java",
            "com/muoniumplayer/party/net/MpPayloads.java"));

    /** 五棵树各自的版本差异测试（ProjectPaths 例外，它已经收进共享层）。 */
    private static final Set<String> REQUIRED_TEST_JAVA = new LinkedHashSet<String>(Arrays.asList(
            "com/muoniumplayer/compat/lwjgl2/Lwjgl2KeycodeProbeTest.java",
            "com/muoniumplayer/core/update/UpdateCheckStaticTest.java",
            "com/muoniumplayer/render/skija/RenderLayerStaticTest.java"));

    /** 只在该版本才有的移植/探针测试。 */
    private static final Set<String> OPTIONAL_TEST_JAVA = new LinkedHashSet<String>(Arrays.asList(
            "com/muoniumplayer/LoaderDependencyStaticTest.java",
            "com/muoniumplayer/MultiLoaderStaticTest.java",
            "com/muoniumplayer/Port1211StaticTest.java",
            "com/muoniumplayer/core/rendering/animation/AnimationSpeedStaticTest.java",
            "com/muoniumplayer/core/screens/hud/FoliaPanelRasterProbeTest.java",
            "com/muoniumplayer/core/settings/PlayerFadeSpeedProbeTest.java",
            "com/muoniumplayer/render/skija/Port121StaticTest.java",
            "com/muoniumplayer/render/skija/Port262StaticTest.java",
            "com/muoniumplayer/render/skija/Port263StaticTest.java",
            "com/muoniumplayer/render/skija/RasterFrames.java"));

    private static final List<String> LOADER_SYMBOLS = Arrays.asList(
            "net.fabricmc.", "net.neoforged.", "net.minecraftforge.", "cpw.mods.");

    @Test
    @DisplayName("共享根与本树根没有同名文件 —— 类重复不该等到编译才发现")
    void noClassExistsInBothRoots() throws IOException {
        Set<Path> common = filesUnder(ProjectPaths.sharedJava());
        List<String> both = new ArrayList<String>();
        for (Path treeFile : filesUnder(ProjectPaths.versionJava())) {
            Path relative = ProjectPaths.versionJava().relativize(treeFile);
            if (common.contains(ProjectPaths.sharedJava().resolve(relative))) {
                both.add(relative.toString().replace('\\', '/'));
            }
        }
        assertTrue(both.isEmpty(), "这些类在共享层和本树各有一份，javac 会报重复类。"
                + "要么删掉本树那份回到共享层，要么整族搬回本树：\n" + String.join("\n", both));
    }

    @Test
    @DisplayName("本树 src/main/java 只许放版本差异层 —— 别把共享文件复制回来")
    void versionMainRootOnlyHoldsRetainedFiles() throws IOException {
        checkOnlyExpected(ProjectPaths.versionJava(), "src/main/java",
                union(REQUIRED_MAIN_JAVA, OPTIONAL_MAIN_JAVA));
    }

    @Test
    @DisplayName("本树 src/test/java 只许放版本差异测试")
    void versionTestRootOnlyHoldsRetainedTests() throws IOException {
        checkOnlyExpected(testJavaRoot(), "src/test/java",
                union(REQUIRED_TEST_JAVA, OPTIONAL_TEST_JAVA));
    }

    @Test
    @DisplayName("清单里标「五树都要」的文件，本树确实有一份")
    void requiredRetainedFilesArePresent() throws IOException {
        List<String> missing = new ArrayList<String>();
        for (String rel : REQUIRED_MAIN_JAVA) {
            if (!Files.isRegularFile(ProjectPaths.versionJava().resolve(Paths.get(rel)))) {
                missing.add(rel);
            }
        }
        for (String rel : REQUIRED_TEST_JAVA) {
            if (!Files.isRegularFile(testJavaRoot().resolve(Paths.get(rel)))) {
                missing.add(rel);
            }
        }
        assertTrue(missing.isEmpty(), "留守清单说这些文件各树都有一份，本树却没有 —— "
                + "是被搬进共享层了吗？那这张表要一起删：\n" + String.join("\n", missing));
    }

    @Test
    @DisplayName("共享层保持版本中立：不许出现加载器符号")
    void sharedLayerHasNoLoaderSymbols() throws IOException {
        List<String> offenders = new ArrayList<String>();
        for (Path file : filesUnder(ProjectPaths.sharedJava())) {
            String code = Files.readString(file, StandardCharsets.UTF_8);
            for (String symbol : LOADER_SYMBOLS) {
                if (code.contains("import " + symbol)) {
                    offenders.add(ProjectPaths.sharedJava().relativize(file) + " -> " + symbol);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "共享层一旦 import 加载器的类，另一个加载器那侧就编不过；"
                + "这类代码属于各树的 fabric/ 与 neoforge/ 外壳：\n" + String.join("\n", offenders));
    }

    @Test
    @DisplayName("共享层扛着大头，本树只剩差异层 —— 搬反了会立刻红")
    void sharedRootsCarryTheBulk() throws IOException {
        int sharedMain = filesUnder(ProjectPaths.sharedJava()).size();
        int treeMain = filesUnder(ProjectPaths.versionJava()).size();
        int sharedTest = filesUnder(commonTestJava()).size();
        assertTrue(sharedMain > 700, "共享层只有 " + sharedMain
                + " 个主源码文件，与「中立部分全部进共享」不符");
        assertTrue(treeMain <= REQUIRED_MAIN_JAVA.size() + OPTIONAL_MAIN_JAVA.size(),
                "本树版本差异层有 " + treeMain + " 个文件，超出留守清单");
        assertTrue(sharedTest > 90, "共享测试根只有 " + sharedTest + " 个文件，五树共用的断言不该这么少");
    }

    @Test
    @DisplayName("两个加载器都把共享根挂进了 sourceSets")
    void bothLoadersWireSharedRoots() throws IOException {
        List<String> loaders = new ArrayList<String>();
        loaders.add("fabric");
        loaders.add(ProjectPaths.loaderDir().getFileName().toString());
        for (String loader : loaders) {
            String build = Files.readString(ProjectPaths.versionRoot().resolve(loader)
                    .resolve("build.gradle"), StandardCharsets.UTF_8);
            assertTrue(build.contains("rootProject.file('../muonium-common/src/main/java')"),
                    loader + "/build.gradle 没挂共享主体根：版本差异层会被单独编译，共享代码根本不参与");
            assertTrue(build.contains("rootProject.file('../muonium-common/src/main/resources')"),
                    loader + "/build.gradle 没挂共享资源根：字体与纹理会找不到");
            if (loader.equals("fabric")) {
                assertTrue(build.contains("rootProject.file('../muonium-common/src/test/java')"),
                        "fabric/build.gradle 没挂共享测试根：五树共用的断言一条都不会跑");
            }
        }
    }

    @Test
    @DisplayName("字体只存在于共享层 —— 21.5 MB 五份重复就是当初要搬家的理由")
    void fontsLiveOnlyInSharedLayer() throws IOException {
        long inTree = filesUnder(ProjectPaths.versionResources()).stream()
                .filter(p -> p.toString().replace('\\', '/').contains("muonium/fonts/"))
                .count();
        assertEquals(0, inTree, "各树 src/main/resources 里还留着字体，共享层那份就没生效");
        long inShared = filesUnder(ProjectPaths.sharedResources()).stream()
                .filter(p -> p.toString().replace('\\', '/').contains("muonium/fonts/"))
                .count();
        assertTrue(inShared >= 15, "共享层的字体只有 " + inShared + " 个，字体会画不出来");
    }

    private static void checkOnlyExpected(Path root, String label, Set<String> allowed)
            throws IOException {
        List<String> unexpected = new ArrayList<String>();
        for (Path file : filesUnder(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (!allowed.contains(rel)) {
                unexpected.add(rel);
            }
        }
        assertTrue(unexpected.isEmpty(), label + " 里出现了不在留守清单中的文件："
                + String.join(", ", unexpected)
                + " —— 它要么本该进 muonium-common（复制回来的旧副本，删掉），"
                + "要么先登记进 SharedLayoutStaticTest 与 tools/share-trees.py 的 REASONS 并写明为什么不能共享");
    }

    private static Path testJavaRoot() {
        return ProjectPaths.versionRoot().resolve(Paths.get("src", "test", "java"));
    }

    private static Path commonTestJava() {
        return ProjectPaths.repoRoot().resolve("muonium-common")
                .resolve(Paths.get("src", "test", "java"));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new LinkedHashSet<String>(a);
        all.addAll(b);
        return all;
    }

    private static Set<Path> filesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return new LinkedHashSet<Path>();
        }
        List<Path> all = new ArrayList<Path>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                all.add(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return all.stream().collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
