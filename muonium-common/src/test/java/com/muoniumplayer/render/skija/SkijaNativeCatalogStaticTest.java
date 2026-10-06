package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skija 原生库「找得到、排得对、版本不对就拒」的不变量。
 *
 * <p>这一层是纯磁盘与字符串逻辑，所以这里的测试是<b>真跑</b>的：建临时目录、放文件、调
 * {@link SkijaNativeCatalog}，不看源码里的形状。唯一几组读源码的断言钉的是
 * 「跨文件必须一致的三件事」——版本串、加载器重试闸、提示层不许依赖 Skia——
 * 这三件事错了都不会在编译期报错，只会在玩家机器上变成「装了模组但没有界面」。
 *
 * <p>不能加载 {@code ConfigPaths}：它的静态块会真的搬工作目录里的旧配置文件。
 */
class SkijaNativeCatalogStaticTest {

    private static final String PINNED = SkijaNatives.PINNED_SKIJA_VERSION;

    // ------------------------------------------------------------------
    // 本机平台判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Android 与 Linux 必须分档：Skija 给两者发的是不同的包")
    void androidIsItsOwnPlatform() {
        // Android 的 JVM 报 os.name=Linux、os.arch=aarch64，光看字符串只会得到 linux/arm64，
        // 而那份是 glibc+libGL+fontconfig 构建，手机上必然装不上。
        assertEquals("android/arm64",
                SkijaNativeCatalog.mappedPlatform("Linux", "aarch64", Map.of(), true));
        assertEquals("android/arm64",
                SkijaNativeCatalog.mappedPlatform("Linux", "aarch64",
                        Map.of("ANDROID_ROOT", "/system"), false));
        // 真 Linux（桌面发行版、Termux-glibc）没有那两个 Android 痕迹 → linux/arm64
        assertEquals("linux/arm64",
                SkijaNativeCatalog.mappedPlatform("Linux", "aarch64", Map.of(), false));
        assertEquals("windows/x64",
                SkijaNativeCatalog.mappedPlatform("Windows 11", "amd64", Map.of(), false));
        assertEquals("macos/arm64",
                SkijaNativeCatalog.mappedPlatform("Mac OS X", "aarch64", Map.of(), false));
    }

    @Test
    @DisplayName("os.arch 的各种写法都收到 x64 / arm64 两档")
    void architectureAliases() {
        assertEquals("arm64", SkijaNativeCatalog.archOf("aarch64"));
        assertEquals("arm64", SkijaNativeCatalog.archOf("arm64-v8a"));
        assertEquals("arm64", SkijaNativeCatalog.archOf("Arm64"));
        assertEquals("x64", SkijaNativeCatalog.archOf("amd64"));
        assertEquals("x64", SkijaNativeCatalog.archOf("x86_64"));
        assertEquals("x64", SkijaNativeCatalog.archOf(""));
    }

    @Test
    @DisplayName("候选顺序：本机映射永远排第一，且不重复")
    void candidateOrderPutsMappedFirst() {
        List<String> ordered = SkijaNativeCatalog.candidatePlatforms("android/arm64");
        assertEquals("android/arm64", ordered.get(0));
        assertEquals(ordered.size(), ordered.stream().distinct().count(), "候选里不能出现重复平台");
        assertTrue(ordered.contains("linux/arm64"), "剩下的已知平台也要留在候选里兜底");
        // 映射出一个 Skija 没出过包的架构时，本机仍然排第一（只是后面没人命中它）
        assertEquals("linux/riscv64",
                SkijaNativeCatalog.candidatePlatforms("linux/riscv64").get(0));
    }

    @Test
    @DisplayName("资源路径判定与 Skija 平台 jar 的目录名一致")
    void resourcePathMatchesPlatformJars() {
        // 这一段是防「我们把 android 写成 android/arm64 之外的拼法」：Skija 的 jar 里就是
        // io/github/humbleui/skija/android/arm64/{libskija.so,skija.version}
        assertEquals("io/github/humbleui/skija/android/arm64",
                SkijaNativeCatalog.RESOURCE_ROOT + "/android/arm64");
        assertEquals("libskija.so", SkijaNativeCatalog.libraryNameFor("android/arm64"));
        assertEquals("libskija.so", SkijaNativeCatalog.libraryNameFor("linux/x64"));
        assertEquals("skija.dll", SkijaNativeCatalog.libraryNameFor("windows/x64"));
        assertEquals("libskija.dylib", SkijaNativeCatalog.libraryNameFor("macos/arm64"));
    }

    // ------------------------------------------------------------------
    // 磁盘伴生包发现
    // ------------------------------------------------------------------

    @Test
    @DisplayName("标准布局的伴生包要认出平台、版本、并排到最前")
    void findsStandardCompanionLayout() throws IOException {
        Path root = Files.createTempDirectory("muonium-skija-catalog");
        try {
            write(root, "linux/arm64/libskija.so", "ELF");
            write(root, "linux/arm64/" + SkijaNativeCatalog.VERSION_FILE, PINNED);

            SkijaNativeCatalog.Scan scan =
                    SkijaNativeCatalog.scan(root.toFile(), PINNED, "linux/arm64");
            assertEquals(1, scan.usable().size(), "该有一个可用候选：" + scan.usable());
            SkijaNativeCatalog.Candidate candidate = scan.usable().get(0);
            assertEquals("linux/arm64", candidate.platform());
            assertEquals(PINNED, candidate.version());
            assertTrue(candidate.describe().contains("linux/arm64"), "描述要能直接打给用户");
            assertTrue(scan.rejected().isEmpty(), "标准布局不该被拒：" + scan.rejected());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("用户多套一层目录、或改了目录名，仍然要找到（只是排序靠后）")
    void tolerantOfExtraNesting() throws IOException {
        Path root = Files.createTempDirectory("muonium-skija-nested");
        try {
            // 从 zip 解压时很容易多一层 muonium-skija-linux-arm64/
            write(root, "muonium-skija-linux-arm64/linux/arm64/libskija.so", "ELF");
            write(root, "muonium-skija-linux-arm64/linux/arm64/"
                    + SkijaNativeCatalog.VERSION_FILE, PINNED);
            assertEquals(1, SkijaNativeCatalog.scan(root.toFile(), PINNED, "linux/arm64")
                            .usable().size(),
                    "多套一层目录不能被当成没装");

            // 目录名被改掉：认不出平台，但版本对得上就仍然收录，交给加载试探决定
            Path other = Files.createTempDirectory("muonium-skija-renamed");
            try {
                write(other, "我随手起的名字/libskija.so", "ELF");
                write(other, "我随手起的名字/" + SkijaNativeCatalog.VERSION_FILE, PINNED);
                var usable = SkijaNativeCatalog.scan(other.toFile(), PINNED, "linux/arm64")
                        .usable();
                assertEquals(1, usable.size(), "认不出平台的候选也要收录，能不能用由加载决定");
                assertEquals("", usable.get(0).platform(), "认不出就该老实说认不出");
            } finally {
                deleteRecursively(other);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("版本对不上必须拒收：JNI 错配的症状比缺文件难懂十倍")
    void rejectsVersionMismatch() throws IOException {
        Path root = Files.createTempDirectory("muonium-skija-version");
        try {
            write(root, "linux/arm64/libskija.so", "ELF");
            write(root, "linux/arm64/" + SkijaNativeCatalog.VERSION_FILE, "0.99.9");

            SkijaNativeCatalog.Scan scan =
                    SkijaNativeCatalog.scan(root.toFile(), PINNED, "linux/arm64");
            assertTrue(scan.usable().isEmpty(), "版本不一致绝不能进候选");
            assertEquals(1, scan.rejected().size());
            assertTrue(scan.rejected().get(0).contains("0.99.9"),
                    "拒收理由要把两个版本号都说出来：" + scan.rejected());
            assertTrue(scan.rejected().get(0).contains(PINNED), "也要说本模组要哪一版");
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("没有 skija.version 的裸 so 一律拒收")
    void rejectsLibraryWithoutVersionMarker() throws IOException {
        Path root = Files.createTempDirectory("muonium-skija-noversion");
        try {
            write(root, "linux/arm64/libskija.so", "ELF");
            SkijaNativeCatalog.Scan scan =
                    SkijaNativeCatalog.scan(root.toFile(), PINNED, "linux/arm64");
            assertTrue(scan.usable().isEmpty(), "没有版本标记就不能确认它是给本版用的");
            assertTrue(scan.rejected().get(0).contains(SkijaNativeCatalog.VERSION_FILE),
                    "理由要指向缺的那个文件：" + scan.rejected());

            // 版本标记在上一级目录也要认（zip 根放 skija.version 的排版很常见）
            Path up = Files.createTempDirectory("muonium-skija-upversion");
            try {
                write(up, "linux/arm64/libskija.so", "ELF");
                write(up, "skija.version", PINNED);
                assertEquals(1, SkijaNativeCatalog.scan(up.toFile(), PINNED, "linux/arm64")
                        .usable().size(), "就近往上找版本标记");
            } finally {
                deleteRecursively(up);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("同 OS 不同架构排得比跨 OS 高，本机完全一致排最高")
    void scoresOrderCandidatesCorrectly() throws IOException {
        Path root = Files.createTempDirectory("muonium-skija-order");
        try {
            write(root, "linux/arm64/libskija.so", "ELF");
            write(root, "linux/arm64/" + SkijaNativeCatalog.VERSION_FILE, PINNED);
            write(root, "linux/x64/libskija.so", "ELF");
            write(root, "linux/x64/" + SkijaNativeCatalog.VERSION_FILE, PINNED);
            write(root, "macos/arm64/libskija.dylib", "ELF");
            write(root, "macos/arm64/" + SkijaNativeCatalog.VERSION_FILE, PINNED);

            var usable = SkijaNativeCatalog.scan(root.toFile(), PINNED, "linux/arm64")
                    .usable();
            assertEquals(3, usable.size());
            assertEquals("linux/arm64", usable.get(0).platform(), "本机的必须第一个试");
            assertEquals("linux/x64", usable.get(1).platform(), "同 OS 换架构次之（兼容层确实存在）");
            assertEquals("macos/arm64", usable.get(2).platform(), "跨 OS 的排在最后");
        } finally {
            deleteRecursively(root);
        }
    }

    // ------------------------------------------------------------------
    // 自管解压目录
    // ------------------------------------------------------------------

    @Test
    @DisplayName("解压目录按平台分层，回收只动 extracted 这一支")
    void extractedLayoutAndPrune() throws IOException {
        Path root = Files.createTempDirectory("muonium-skija-prune");
        try {
            File linuxDir = SkijaNativeCatalog.extractedDir(root.toFile(), PINNED, "linux/x64");
            File macosDir = SkijaNativeCatalog.extractedDir(root.toFile(), PINNED, "macos/arm64");
            assertFalse(linuxDir.equals(macosDir), "不同平台绝不能共用一个目录");
            assertTrue(linuxDir.getPath().replace('\\', '/').contains("/" + PINNED + "/extracted/"),
                    "解压目录必须在版本号之下的 extracted 里：" + linuxDir);

            linuxDir.mkdirs();
            macosDir.mkdirs();
            write(root, PINNED + "/extracted/linux/x64/libskija.so", "ELF");
            write(root, PINNED + "/extracted/macos/arm64/libskija.dylib", "ELF");
            // 用户自己丢的伴生包与 extracted 同根不同支，回收绝不能碰它
            write(root, "linux/arm64/libskija.so", "ELF");
            write(root, "linux/arm64/" + SkijaNativeCatalog.VERSION_FILE, PINNED);

            int removed = SkijaNativeCatalog.pruneExtracted(root.toFile(), PINNED, "linux/x64");
            assertEquals(1, removed, "只该收掉一个非在用平台");
            assertTrue(new File(linuxDir, "libskija.so").isFile(), "在用平台必须留着");
            assertFalse(new File(macosDir, "libskija.dylib").exists(), "非在用平台要清掉");
            assertTrue(root.resolve("linux/arm64/libskija.so").toFile().isFile(),
                    "用户手工丢进来的文件不在回收范围内");
            assertFalse(root.resolve(PINNED).resolve("extracted").resolve("macos").toFile().exists(),
                    "清空之后的 os 目录要一起收掉，别留空壳");
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("根目录不存在时扫描返回空结果而不是抛")
    void scanIsQuietWhenNothingInstalled() {
        SkijaNativeCatalog.Scan scan = SkijaNativeCatalog.scan(
                new File("Z:/这个目录不存在/muonium"), PINNED, "linux/arm64");
        assertTrue(scan.usable().isEmpty());
        assertTrue(scan.rejected().isEmpty());
        assertNotNull(scan, "扫描结果本身不能是 null");
    }

    @Test
    @DisplayName("命名层面就加载不了的平台，别混进候选制造假线索")
    void filtersPlatformsThisJvmCannotName() {
        // Linux/Android 的 JVM 只会按 libskija.so 去找，windows 那份叫 skija.dll
        assertTrue(SkijaNativeCatalog.loadableWith("linux/arm64", "libskija.so"));
        assertTrue(SkijaNativeCatalog.loadableWith("android/arm64", "libskija.so"));
        assertFalse(SkijaNativeCatalog.loadableWith("windows/x64", "libskija.so"),
                "让 classpath 里的 skija.dll 进候选，日志只会多一条永远不成立的「找不到 libskija.so」");
        assertTrue(SkijaNativeCatalog.loadableWith("windows/x64", "skija.dll"));
        assertTrue(SkijaNativeCatalog.loadableWith("macos/arm64", "libskija.dylib"));
        // 同名不同 ABI（linux 与 android 的 arm64）不由这里判，交给试加载
        assertTrue(SkijaNativeCatalog.loadableWith("linux/arm64", "libskija.so")
                && SkijaNativeCatalog.loadableWith("android/arm64", "libskija.so"));
    }

    // ------------------------------------------------------------------
    // 跨文件一致性：这三件事错了都不会在编译期报错
    // ------------------------------------------------------------------

    @Test
    @DisplayName("钉住的 Skija 版本在三处必须一致")
    void pinnedVersionMatchesBuildScripts() throws IOException {
        for (String file : List.of(Paths.get("fabric", "build.gradle").toString(),
                Paths.get(ProjectPaths.loaderDir().getFileName().toString(), "build.gradle").toString())) {
            String gradle = read(ProjectPaths.versionRoot().resolve(file));
            assertTrue(gradle.contains("skija-shared:" + PINNED),
                    file + " 里的 skija-shared 坐标必须与 SkijaNatives.PINNED_SKIJA_VERSION 一致（"
                            + PINNED + "）");
        }
        String companion = read(ProjectPaths.versionRoot().resolve("build.gradle"));
        assertTrue(companion.contains("def skijaVersion = '" + PINNED + "'"),
                "伴生包任务的 Skija 版本必须与主包钉的那版一致，否则用户下的包会被版本校验拒收");
        assertTrue(companion.contains("'android-arm64'"),
                "伴生包任务必须出安卓包：MG 渲染器 + 关闭 ANGLE 的组合是真机实测可用的");
    }

    @Test
    @DisplayName("伴生包的产物名与模组提示里的名字必须同一个说法")
    void companionPackageNameMatchesHint() {
        // 提示层把包名直接打给用户在屏幕上，用户会照着这个名字去搜——两处拼法不同就等于骗人
        for (String platform : List.of("linux/arm64", "android/arm64")) {
            String fromJava = new SkijaNatives.Status(false, platform, PINNED, null, null,
                    List.of(), List.of(), null, false).expectedPackageName();
            assertEquals("muonium-skija-" + platform.replace('/', '-') + "-" + PINNED + ".zip", fromJava);
        }
        assertEquals("muonium-skija-android-arm64-" + PINNED + ".zip",
                new SkijaNatives.Status(false, "android/arm64", PINNED, null, null,
                        List.of(), List.of(), null, false).expectedPackageName());
    }

    @Test
    @DisplayName("停用必须有重评窗口，且重评要连 SkijaNatives 的缓存一起清")
    void suspensionIsRecoverable() throws IOException {
        String host = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaHost.java")));
        assertTrue(host.contains("resumeAtNanos"),
                "停用要带上「什么时候允许再试」，否则装了伴生包不重启就永远是白屏");
        assertTrue(host.contains("SkijaNatives.reset()"),
                "重评必须同时清掉 SkijaNatives 缓存的失败结论，不然重试到的还是旧答案");
        assertTrue(host.contains("maybeResume()"),
                "beginFrame 要在停用分支里过一道重评");

        String natives = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaNatives.java")));
        assertTrue(natives.contains("public static synchronized void reset()"),
                "reset() 要存在且与 ensureLoaded 一样上锁");
        assertTrue(natives.contains("Boolean.TRUE.equals(loaded)"),
                "已经加载成功时 reset() 什么都不做：原生库退不掉，重置只会白试一次");
    }

    @Test
    @DisplayName("停用提示不许依赖 Skia：它要说的事正是 Skia 起不来")
    void noticeDrawsWithoutSkia() throws IOException {
        String notice = stripComments(read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "screens", "RenderLayerNotice.java"))));
        assertFalse(notice.contains("io.github.humbleui"),
                "RenderLayerNotice 不能引用 Skija：那正是要报告失败的层");
        assertFalse(notice.contains("SkijaGfx") || notice.contains("SkijaShapes")
                        || notice.contains("SkijaHost.beginFrame"),
                "提示层不能走 Skia 绘制链路");
        assertTrue(notice.contains("GuiGraphics"),
                "提示要走原版 GUI 提交，Skia 停用也照样能画");
        assertTrue(notice.contains("expectedPackageName()"),
                "提示必须把伴生包名字说出来，用户只有这一条路能找到东西");
        assertTrue(notice.contains("expectedDirectory()"),
                "提示必须把目标目录的绝对路径打出来——手机端相对路径等于没说");
        // 真机第一轮的教训：库找到了、版本也对、却加载被拒——这时再喊「下载伴生包」是把人往回指
        assertTrue(notice.contains("nativeFound()"),
                "提示要分得开「没找到库」与「找到了但加载被拒」");
        assertTrue(notice.contains("再下一遍伴生包没有用"),
                "加载被拒那种形状必须明说不用再下第二遍");

        // 加载器外壳：HUD 与 Screen 各挂一次（两个加载器都要）。HUD 那一路的形参名随代际不同
        // （老树 GuiGraphics 用 drawContext，26.x 的 extractor 用 extractor），只钉调用形状。
        String fabricEntry = read(ProjectPaths.fabric()
                .resolve(Paths.get("src", "main", "java", "com", "muoniumplayer", "MuoniumPlayerMod.java")));
        assertEquals(2, countCalls(fabricEntry),
                "fabric 外壳的 HUD 与 Screen 各要一次 onRenderLayerNotice");
        assertTrue(fabricEntry.contains(", false)"), "HUD 那条路要挂上提示");
        assertTrue(fabricEntry.contains(", true)"), "界面里也要挂上，且说满");

        String loaderEntry = read(ProjectPaths.loaderDir()
                .resolve(Paths.get("src", "main", "java", "com", "muoniumplayer", "loader",
                        ProjectPaths.loaderDir().getFileName().toString(),
                        loaderShellName() + ".java")));
        assertEquals(2, countCalls(loaderEntry),
                ProjectPaths.loaderDir().getFileName() + " 外壳的 HUD 与 Screen 各要一次 onRenderLayerNotice");
        assertTrue(loaderEntry.contains("event.getGuiGraphics(), false)")
                        && loaderEntry.contains("event.getGuiGraphics(), true)"),
                "forge/neoforge 两个挂点都从事件上拿原版绘制上下文");
    }

    @Test
    @DisplayName("启动时打运行时身份：非 Windows 的反馈不能再靠猜")
    void runtimeDiagnosticsAreLogged() throws IOException {
        String client = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "MuoniumPlayerClient.java")));
        assertTrue(client.contains("logRuntimeEnvironment()"),
                "init() 里要调用运行时身份打印");
        assertTrue(client.contains("os.name=") && client.contains("java.vm.name")
                        && client.contains("判定平台="),
                "打印内容要含 os.name / java.vm.name / 判定平台：Android 的 JVM 报 os.name=Linux，"
                        + "少了判定平台就无法区分真 Linux 与 Android");
        assertTrue(client.contains("伴生包目录="),
                "还要打伴生包目录：非 Windows 用户报「没有界面」时，"
                        + "分不出「包没放对地方」和「放了但版本不对」");
    }

    @Test
    @DisplayName("Android 与桌面走同一条加载链路，落点搬运那一维必须还在")
    void androidIsSupportedLikeDesktop() throws IOException {
        String natives = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaNatives.java")));
        // 2026-10-05 真机复测推翻了「安卓装不出上下文」的旧结论（真凶是 ANGLE），
        // 9-30 收口用的那道平台闸不许回来。
        assertTrue(!natives.contains("该平台暂不支持") && !natives.contains("本版不提供"),
                "安卓不得被硬闸短路：MG 渲染器 + 关闭 ANGLE 是实测可用的组合");
        assertTrue(natives.contains("stageRoots("),
                "链接器落点那一维必须在：Android 的命名空间不允许从外置存储 dlopen，"
                        + "伴生包解在配置目录里要靠搬到 libjvm 同树才能加载");
        assertTrue(natives.contains("libjvmDirectory()"),
                "落点列表要以 /proc/self/maps 里 libjvm.so 的实际位置为最强证据");
        assertTrue(natives.contains("stageLibrary("),
                "搬运要幂等：同尺寸不重写，20 秒一次的重评不能反复拷 34 MB");

        String catalog = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaNativeCatalog.java")));
        assertTrue(catalog.contains("is not accessible for the namespace"),
                "真机那条 dlopen 报错原文要留在注释里：下一轮迭代不能再把它当猜测推翻");
    }

    @Test
    @DisplayName("安卓的停用提示要说清可用组合：MG 渲染器 + 关闭 ANGLE + 伴生包")
    void androidNoticeGuidesToWorkingSetup() throws IOException {
        String notice = stripComments(read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "screens", "RenderLayerNotice.java"))));
        int androidBranch = notice.indexOf("startsWith(\"android\")");
        assertTrue(androidBranch >= 0, "提示层要单独认 Android");
        String androidText = notice.substring(androidBranch,
                notice.indexOf("if (!status.loaded()", androidBranch));
        assertTrue(androidText.contains("MobileGlues"),
                "要把实测可用的渲染器名字说出来");
        assertTrue(androidText.contains("ANGLE"),
                "「关闭 ANGLE」是能否装出上下文的关键，必须写在提示里");
        assertTrue(!androidText.contains("本版不提供"),
                "安卓是支持的，不许再说「不提供」");
    }

    @Test
    @DisplayName("许可文本随伴生包发布：Apache-2.0 与 BSD-3 都要能拿到全文")
    void licenseTextsExist() {
        Path licenses = ProjectPaths.versionRoot().resolve("licenses");
        for (String name : List.of("LICENSE-Skija.txt", "LICENSE-Skia.txt", "NOTICE-Skija.txt")) {
            File file = licenses.resolve(name).toFile();
            assertTrue(file.isFile(), "缺许可文本：" + file.getAbsolutePath());
            assertTrue(file.length() > 100, "许可文本不能是空壳：" + name);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 数 shell 里 onRenderLayerNotice( 的调用处数（定义在 client 里，外壳里只有调用）。 */
    private static int countCalls(String shellSource) {
        return shellSource.split("onRenderLayerNotice\\(", -1).length - 1;
    }

    /** forge/neoforge 外壳类名：目录名不会直接给出类名里的驼峰（NeoForge 的 F 是大写）。 */
    private static String loaderShellName() {
        return "forge".equals(ProjectPaths.loaderDir().getFileName().toString())
                ? "MuoniumPlayerForge"
                : "MuoniumPlayerNeoForge";
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative.replace('/', File.separatorChar));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        // 后序删除：先文件、后目录，任何 Java 版本都成立的写法
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException ignored)
                    throws IOException {
                Files.deleteIfExists(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
