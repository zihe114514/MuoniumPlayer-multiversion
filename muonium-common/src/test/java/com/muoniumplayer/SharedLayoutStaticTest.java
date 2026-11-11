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
            // --- 按键显示名：KeyMapping 是 MC 类，且取名字的 API 各代不同名；
            //     共享层只调 displayName()，七树各一份 ---
            "com/muoniumplayer/compat/mc/KeyMappingProbe.java",
            // --- 3D 世界歌词：相机/公告牌/桥全是 MC 触点，各树一份（共享层只放语义）---
            "com/muoniumplayer/compat/mc/CameraAccess.java",
            "com/muoniumplayer/compat/mc/WorldBillboard.java",
            "com/muoniumplayer/compat/mc/WorldLyricsBridge.java",
            "com/muoniumplayer/compat/mc/LyricGlyphAtlasBridge.java",
            // --- 场景声学：玩家环境采样是 MC 触点（水下/天光/降水/方块射线），各树一份 ---
            "com/muoniumplayer/compat/mc/ListenerProbe.java",
            // --- 世界声源：放置交互/状态判定与世界线框标记,MC 触点各树一份(七树) ---
            "com/muoniumplayer/compat/mc/SpeakerManager.java",
            "com/muoniumplayer/compat/mc/SpeakerRenderBridge.java",
            // --- 环境选曲：环境采样是 MC 触点（群系 tag/方块调色板/实体盒/天光），各树一份 ---
            "com/muoniumplayer/compat/mc/AmbientProbe.java",
            // --- 光影包适配：各树 RenderState/Shader API 差异大，探测器各树一份 ---
            "com/muoniumplayer/compat/mc/ShaderPackProbe.java",
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
            "com/muoniumplayer/core/screens/hud/HudSettingsModel.java"));

    /** 只在部分树存在的（mixin 注入点、各树网络通道、光影期直画通路）。 */
    private static final Set<String> OPTIONAL_MAIN_JAVA = new LinkedHashSet<String>(Arrays.asList(
            // --- MuoniumConfig 下载/更新提示块：原版 GUI 绘制，两代文字 API
            //     （GuiGraphics.drawString 与 GuiGraphicsExtractor.text）拼不成一份共享源码，
            //     七树各一份；本表只登记"存在即可"，不要求每棵树都有 ---
            "com/muoniumplayer/core/screens/MuoniumConfigNotice.java",
            "com/muoniumplayer/mixin/FramebufferMixin.java",
            "com/muoniumplayer/mixin/GlSurfaceMixin.java",
            "com/muoniumplayer/mixin/RenderTargetPresentMixin.java",
            "com/muoniumplayer/mixin/MouseScrollMixin.java",
            "com/muoniumplayer/mixin/MouseButtonStateMixin.java",
            "com/muoniumplayer/mixin/MusicManagerAccessor.java",
            "com/muoniumplayer/mixin/MusicManagerDuckMixin.java",
            // --- 26.x 光影期直画：renderpearl 类路径 + GameRenderer 注入点，仅 26.2/26.3 ---
            "com/muoniumplayer/compat/mc/LyricPresentRenderer.java",
            "com/muoniumplayer/compat/mc/LyricBlaze3DRenderer.java",
            "com/muoniumplayer/compat/mc/WorldLyricsHudDraw.java",
            // --- 场景声学调试：声场射线可视化，MC 渲染 API 触点各树一份（暂仅 26.3）---
            "com/muoniumplayer/compat/mc/AcousticsDebugLoader.java",
            "com/muoniumplayer/compat/mc/AcousticsRayDebugRenderer.java",
            "com/muoniumplayer/mixin/GameRendererRenderLevelMixin.java",
            "com/muoniumplayer/mixin/GameRendererAccessor.java",
            // --- 3D 歌词晕格加色：RenderType.create 包私有 accessor（仅 26.2/26.3）---
            "com/muoniumplayer/mixin/RenderTypeAccessors.java",
            // --- 同上的 1.21.10 世代形态：四参 create(String,int,RenderPipeline,
            //     CompositeState) 在 1.21.10 起也包私有，签名与 26.x 的
            //     create(String,RenderSetup) 不同形，无法与上一行共用一份（仅 1.21.10）---
            "com/muoniumplayer/mixin/RenderTypeCreateAccessor.java",
            "com/muoniumplayer/mixin/ProjectionMatrixBufferMixin.java",
            // --- 通透模式：果冻压缩注入点两代不同形 —— 26.x extract/submit 状态架构，
            //     1.20.1~1.21.11 的 EntityRenderDispatcher.render(...)（暂仅 26.3）---
            "com/muoniumplayer/mixin/TransparentEntityMixin.java",
            "com/muoniumplayer/mixin/TransparentCameraMixin.java",
            // --- 卡点运镜：相机/FOV 注入点两代不同形（26.x alignWithEntity/getFov vs
            //     1.21.11 前 setup；1.20.1 setRotation 无 roll），探针触 MC 状态（暂仅 26.3）---
            "com/muoniumplayer/mixin/CineCameraMixin.java",
            "com/muoniumplayer/mixin/CineFovMixin.java",
            "com/muoniumplayer/mixin/CineHudMixin.java",
            "com/muoniumplayer/compat/mc/CineProbe.java",
            // --- 陀螺模式：节拍步进的人物旋转。双段式树在 extract 给
            //     LivingEntityRenderState.bodyRot 加差值（名牌挂点纯 Y 偏移、名牌朝向
            //     另由相机四元数决定，都不吃这字段）；单段式树走 pose 括号，两代不同形 ---
            "com/muoniumplayer/mixin/SpinEntityMixin.java",
            // --- 陀螺模式·发包静默档：sendPosition 内 getYRot 读取替换，注入点逐树
            //     反汇编确认过形状（4~5 处读取点），七树同文 ---
            "com/muoniumplayer/mixin/SpinPacketMixin.java",
            // --- 陀螺模式·头乱晃（单段式）：包住 setupAnim 给头部件叠 yaw/pitch；
            //     双段式树不需要（头部差值直接进渲染态 yRot/xRot，见 SpinEntityMixin）---
            "com/muoniumplayer/mixin/SpinHeadMixin.java",
            // --- 3D 歌词「永远最上层」：renderLevel RETURN 注入点各代一份。
            //     1.20.1 的 Fabric LAST 落在 renderChunkDebugInfo（renderLevel 内部、
            //     Fabulous 半透明合成之前），水/玻璃会盖住歌词；这颗 mixin 把绘制
            //     挪到 renderLevel 真正返回之后（仅 1.20.1）---
            "com/muoniumplayer/mixin/LevelRendererLateStageMixin.java",
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
            "com/muoniumplayer/Port12110StaticTest.java",
            "com/muoniumplayer/render/skija/Port2612StaticTest.java",
            "com/muoniumplayer/core/rendering/animation/AnimationSpeedStaticTest.java",
            "com/muoniumplayer/core/settings/PlayerFadeSpeedProbeTest.java",
            "com/muoniumplayer/render/skija/Port121StaticTest.java",
            "com/muoniumplayer/render/skija/Port262StaticTest.java",
            "com/muoniumplayer/render/skija/Port263StaticTest.java",
            // 26.x present/NativeImage 接口不同于旧树，仅主开发树运行真实版本探针。
            "com/muoniumplayer/compat/mc/LyricPresentRendererUploadTest.java",
            "com/muoniumplayer/compat/mc/WorldBillboardCanvasPixelsTest.java",
            "com/muoniumplayer/render/skija/SkijaTrailGpuTest.java",
            "com/muoniumplayer/render/skija/RasterFrames.java"));

    // 仅铺设 Kotlin 源根，不迁移既有 Java 留守类；未来新增差异类需同步登记工具与本表。
    private static final Set<String> RETAINED_MAIN_KOTLIN = new LinkedHashSet<String>();
    private static final Set<String> RETAINED_TEST_KOTLIN = new LinkedHashSet<String>();

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
    @DisplayName("Kotlin 源根遵守留守清单，不得绕过 Java 共享治理")
    void kotlinRootsOnlyHoldRetainedFiles() throws IOException {
        checkOnlyExpected(ProjectPaths.versionKotlin(), "src/main/kotlin", RETAINED_MAIN_KOTLIN);
        checkOnlyExpected(ProjectPaths.versionRoot().resolve("src/test/kotlin"),
                "src/test/kotlin", RETAINED_TEST_KOTLIN);
    }

    @Test
    @DisplayName("共享与本树的 Java/Kotlin 源根没有跨语言同名类")
    void sourceRootsHaveNoCrossLanguageDuplicates() throws IOException {
        java.util.Map<String, Path> seen = new java.util.LinkedHashMap<String, Path>();
        List<String> duplicates = new ArrayList<String>();
        for (Path root : ProjectPaths.sourceRoots()) {
            for (Path file : filesUnder(root)) {
                if (!ProjectPaths.isSourceFile(file)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                String name = relative.substring(0, relative.lastIndexOf('.'));
                Path previous = seen.putIfAbsent(name, file);
                if (previous != null) {
                    duplicates.add(previous + " <-> " + file);
                }
            }
        }
        assertTrue(duplicates.isEmpty(), "Java/Kotlin 同名文件会冲突，必须只有一份归属：\n"
                + String.join("\n", duplicates));
    }

    @Test
    @DisplayName("共享 Kotlin 同样不许引用加载器符号（含 alias 与全限定名）")
    void sharedKotlinHasNoLoaderSymbols() throws IOException {
        List<String> offenders = new ArrayList<String>();
        for (Path file : filesUnder(ProjectPaths.sharedKotlin())) {
            String code = Files.readString(file, StandardCharsets.UTF_8);
            for (String symbol : LOADER_SYMBOLS) {
                if (code.contains(symbol)) {
                    offenders.add(file + " -> " + symbol);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "共享 Kotlin 仍须零加载器符号：\n"
                + String.join("\n", offenders));
    }

    @Test
    @DisplayName("两侧 Kotlin 编译、共享源码和 smoke 验收接线完整")
    void bothLoadersWireKotlinSupport() throws IOException {
        for (Path loader : Arrays.asList(ProjectPaths.fabric(), ProjectPaths.loaderDir())) {
            String build = Files.readString(loader.resolve("build.gradle"), StandardCharsets.UTF_8);
            assertTrue(build.contains("id 'org.jetbrains.kotlin.jvm'"), loader + " 未启用 Kotlin 编译");
            assertTrue(build.contains("id 'org.jetbrains.kotlin.plugin.lombok'"),
                    loader + " Kotlin 看不到同轮 Lombok 生成的 Java API");
            assertTrue(build.contains("apply from: rootProject.file('../gradle/kotlin-support.gradle')"),
                    loader + " 未挂共享 Kotlin 配置");
            assertTrue(!build.replaceAll("(?m)//[^\\r\\n]*", "").contains("classesDirs.singleFile"),
                    loader + " 仍假定只有一个编译输出目录，Java/Kotlin 混编会失败");
        }
        String settings = Files.readString(ProjectPaths.versionRoot().resolve("settings.gradle"),
                StandardCharsets.UTF_8);
        assertTrue(settings.contains("id 'org.jetbrains.kotlin.jvm' version '2.4.21'"));
        String properties = Files.readString(ProjectPaths.versionRoot().resolve("gradle.properties"),
                StandardCharsets.UTF_8);
        assertTrue(properties.contains("kotlin.stdlib.default.dependency=false"),
                "不能由插件再注入另一份标准库抬高旧 KFF 的编译基线");
        String support = Files.readString(ProjectPaths.repoRoot().resolve("gradle/kotlin-support.gradle"),
                StandardCharsets.UTF_8);
        for (String root : Arrays.asList("../muonium-common/src/main/kotlin",
                "../muonium-common/src/test/kotlin", "../mpparty-common/src/main/kotlin")) {
            assertTrue(support.contains(root), "Kotlin 配置漏挂 " + root);
        }
        assertTrue(support.contains("kotlinSmokeTest") && support.contains("jvmTarget.set"));
    }

    @Test
    @DisplayName("跨语言路径接口保留原 Java 扫描结果，不漏 Kotlin")
    void sourceEnumerationIncludesJavaAndKotlin() throws IOException {
        List<Path> files = ProjectPaths.allSourceFiles();
        assertTrue(files.containsAll(ProjectPaths.allJavaFiles()));
        assertTrue(ProjectPaths.sourceRoots().contains(ProjectPaths.sharedKotlin()));
        assertTrue(ProjectPaths.sourceRoots().contains(ProjectPaths.versionKotlin()));
        assertTrue(ProjectPaths.isSourceFile(Paths.get("Example.kt")));
        assertTrue(!ProjectPaths.isSourceFile(Paths.get("build.gradle.kts")));
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
