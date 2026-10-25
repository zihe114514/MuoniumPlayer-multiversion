package com.muoniumplayer.render.skija;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.muoniumplayer.core.settings.ConfigPaths;

/**
 * Skija 原生库加载器。
 *
 * <p>Skija 自带的加载流程（{@code Library.load()}）优先走 JPMS {@code ResourceFinder}
 * 去定位平台 jar 里的 {@code skija.version}，找不到时才退回类加载器资源查找。Fabric 的
 * Knot 类加载器不是模块化环境，这条链路是否可用无法靠读代码断定，所以本类提供多条路径，
 * 按代价从低到高依次尝试，任何一条走通就停：
 *
 * <ol>
 *   <li><b>Skija 默认路径</b>：直接调 {@code Library.load()}，让它自己找、自己解压到临时目录。
 *       主包已不内置任何平台的原生库（全平台走伴生包自动下载），这条通常只在用户用
 *       {@code skija.library.path} 自指目录时走通；</li>
 *   <li><b>伴生包目录</b>：{@code config/muonium/native/skija/} 下面用户自己解压出来的
 *       {@code libskija.so}/{@code skija.dll}/{@code libskija.dylib}。Linux / macOS / Android
 *       走这一条——主包不带这些平台的原生库（全带会让 jar 从 58 MB 涨到 121 MB，
 *       越过 GitHub release 的单文件上限，还把 Windows 用户也拖进翻倍的下载量里）；</li>
 *   <li><b>classpath 平台包</b>：本 mod 的（或将来某个加载环境塞进来的）{@code skija-<platform>}
 *       嵌套 jar，由本类自己解到 {@code <root>/<版本>/extracted/<platform>/} 再指给 Skija。</li>
 * </ol>
 *
 * <p>任何平台连一个磁盘候选都没有时，都会走一次<b>自动下载</b>
 * （{@link SkijaCompanionDownload}）：按本机判定平台从清单仓库拉同名伴生包、校验字节数与
 * sha256 后解到伴生包目录，装好即置「必须重启」的闸。下载全程在后台线程，渲染线程不等它。
 *
 * <p>候选的查找、排序与版本校验都在 {@link SkijaNativeCatalog} 里，本类只管「试」与「记状态」。
 *
 * <p><b>候选之外还有一维：落在哪个目录</b>。真机（FCL / Android 16）给出的第一条硬证据是
 * {@code dlopen failed: library "/storage/emulated/0/.../libskija.so" ... is not accessible
 * for the namespace "clns-9"}——Android 的 linker namespace 不允许 JVM 从外置存储映射可执行文件，
 * 而伴生包契约的落点恰恰就在那儿。所以每个候选先在它自己的位置试一次，不行就搬到
 * 「与 libjvm 同源」的目录再试：{@code /proc/self/maps} 里 libjvm.so 所在目录、
 * {@code sun.boot.library.path}、{@code java.library.path}、{@code LD_LIBRARY_PATH}、
 * {@code java.io.tmpdir}，最后才回到配置目录（桌面 Linux 与 Windows 上游戏目录就是普通文件系统，
 * 那里反而是正解）。搬运是幂等的：同尺寸就不重写，所以 20 秒一次的重评不会反复拷 34 MB。
 *
 * <p><b>为什么不用自己 {@code System.load} 试探</b>：Fabric 给每个嵌套 jar 独立类加载器，
 * {@code skija-shared} 与本 mod 不是同一个加载器。本类先 {@code System.load} 成功的那份库，
 * 到 {@code Library.load()}（另一个加载器）手里会变成
 * 「Native Library already loaded in another classloader」。所以试探统一走
 * {@code Library.load()}——它内部就是 {@code System.load}，异常照样能捕获，
 * 而加载器归属始终只有一个。（2026-09-29 的 dev2 实验包也顺手证实了另一件事：
 * 嵌套 jar 里的资源本类确实读得到，classpath 那条路在 Fabric 下不是死路。）
 *
 * <p><b>为什么必须自己管 icudtl.dat</b>：Skia 的文字整形（HarfBuzz + ICU 双向/断句）依赖
 * 这个 10 MB 的 ICU 数据文件，它必须和原生库在同一个目录里。只解 dll 不解它，
 * 单行西文能画出来、CJK 与双向文本会在整形阶段失败。注意 Skija 官方只在 Windows 平台包里
 * 带这份数据，别的平台包里没有——要不要额外补，只能在真机上量，不要在这里当已证。
 *
 * <p><b>线程约束</b>：{@link #ensureLoaded()} 是 {@code synchronized} 的，任何线程都可以调，
 * 但只会真正执行一次。它不需要 GL 上下文——加载原生库和创建 GPU 上下文是两件事。
 * 失败之后结论会缓存住（避免每帧重试刷日志），要重新评估由 {@link #reset()} 决定，
 * 调用点是 {@code SkijaHost} 的停用重评窗口。
 */
public final class SkijaNatives {

    /**
     * 主包编译时钉住的 Skija 版本，同时是「伴生包必须给的版本」。
     *
     * <p>JNI 符号写死类名，伴生包与本字段不一致时失败点会推迟到某个具体方法调用上，
     * 症状是「库加载成功了、界面还是不出来」——所以宁可在这里拒掉并把版本说出来。
     * 这三处（本字段、{@code fabric/build.gradle}、{@code neoforge/build.gradle} 里的
     * {@code io.github.humbleui:skija-*:} 坐标）由 {@code SkijaNativeCatalogStaticTest} 钉成一份，
     * 抬版本时漏一处会红。
     */
    public static final String PINNED_SKIJA_VERSION = "0.143.17";

    /** 伴生包的下载页提示文案用的下载地址（只给首页，不给会变的深链）。 */
    public static final String NATIVE_DOWNLOAD_PAGE = "https://melodifymc.cc.cd/";

    /** 覆盖解压目标目录；不设时统一落在 {@code config/muonium/native/skija}。 */
    private static final String PROP_DIR = "muonium.skija.dir";

    /** 用户直接指一个原生库文件，绕开一切目录约定（启动器与高级用户的口子）。 */
    private static final String PROP_NATIVE_FILE = "muonium.skija.native";

    /** Skija 自己认的「直接从这个目录加载」开关。 */
    private static final String PROP_SKIJA_LIBRARY_PATH = "skija.library.path";

    private static Boolean loaded;
    private static String mode = "未尝试";
    private static String failure;
    private static Status status = new Status(false, SkijaNativeCatalog.mappedPlatform(),
            PINNED_SKIJA_VERSION, null, null, List.of(), List.of(), null, false);

    private SkijaNatives() {
    }

    /**
     * 加载结果的完整交代，给提示层与日志用。
     *
     * @param loaded          是否已经可用
     * @param mappedPlatform  本机判定到的平台，例如 {@code android/arm64}
     * @param pinnedVersion   主包钉住的 Skija 版本
     * @param origin          走通的是哪条路径；失败时为最后一条尝试的路径
     * @param directory       生效的目录（可能为 null：走 Skija 默认路径时由它自己管临时目录）
     * @param attempted       每一条尝试的结论（含失败原因），按尝试顺序
     * @param rejectedOnDisk  磁盘上找到但被拒的候选与拒收原因
     * @param lastError       最后一次失败的异常描述
     * @param nativeFound     是否<b>找到并校验过</b>一份候选。与 {@code loaded} 分开是必要的：
     *                        Android 上真机出现过「候选找到了、版本也对，但链接器不许从那个目录
     *                        dlopen」——这时再喊「请下载伴生包」就是把人往回指第二遍
     */
    public record Status(boolean loaded, String mappedPlatform, String pinnedVersion,
                         String origin, File directory, List<String> attempted,
                         List<String> rejectedOnDisk, String lastError, boolean nativeFound) {

        /** 期望的伴生包文件名，提示层直接把它打给用户——他只需要搜这个名字。 */
        public String expectedPackageName() {
            return "muonium-skija-" + mappedPlatform.replace('/', '-') + "-" + pinnedVersion + ".zip";
        }

        /** 伴生包该解压到哪儿。绝对路径，因为手机上看相对路径等于没说。 */
        public String expectedDirectory() {
            return nativeRoot().getAbsolutePath();
        }
    }

    /** 最近一次（或首次）加载的结果。永远不会为 null。 */
    public static Status status() {
        return status;
    }

    /**
     * 是否已到「必须重启才能生效」：伴生包装好了、原生库仍未加载（重启由 GameRestart
     * 替用户自动完成）。{@code SkijaCompanionDownload} 是包级私有，跨包的询问
     * （自动重启布防、救援文案）统一走这个门面。加载已生效时恒 false。
     */
    public static boolean companionRestartPending() {
        return SkijaCompanionDownload.isRestartRequired();
    }

    /**
     * 「一个候选都没有」时提示层要说的整块文案。
     *
     * <p>自动下载上线之后这一段不能写死：同样是「缺库」，用户下一步动作差得很远——
     * 下载中什么都别做、装好了模组自动重启游戏生效、失败了才回手动补包。文案跟 {@link SkijaCompanionDownload} 的状态走；包名 / 下载页 /
     * 目标目录这三条硬信息在任何「需要用户动手」的分岔里都必须原样给出——静态测试钉着它们，
     * 那是用户唯一的自救路径。
     */
    public static List<String> nativeMissingLines() {
        Status current = status;
        List<String> lines = new ArrayList<>();
        SkijaCompanionDownload.State download = SkijaCompanionDownload.state();
        switch (download.phase()) {
            case DOWNLOADING -> {
                lines.add(download.percent() >= 0
                        ? "正在自动下载渲染原生库：" + download.percent() + "%"
                        : "正在自动下载渲染原生库…");
                lines.add("装好后模组会自动重启游戏使其生效，先等它下完");
            }
            case INSTALLING -> {
                lines.add("渲染原生库已下载，正在解压安装…");
                lines.add("装好后模组会自动重启游戏使其生效");
            }
            case DONE -> {
                // 装好即必须重启，而这一步模组替用户走：GameRestart 已布防时逐字播报
                // 倒计时/取消后的手动指引；拿不到启动命令、超过重启上限时 statusLine
                // 返回 null，回落到「自己重启一次」的手动文案。
                String restartLine = com.muoniumplayer.core.update.GameRestart.statusLine();
                if (restartLine != null) {
                    lines.add("渲染原生库已自动装好，需要重启游戏生效");
                    lines.add(restartLine);
                } else {
                    lines.add("渲染原生库已自动装好，必须重启游戏后才能生效");
                    lines.add("退出后重新进入即可，不需要手动放置任何文件");
                }
            }
            case FAILED -> {
                lines.add("自动下载失败：" + download.detail());
                addManualCompanionLines(lines, current);
            }
            default -> addManualCompanionLines(lines, current);
        }
        return lines;
    }

    /**
     * 自动下载过程此刻是否「看得见」：正在下载 / 正在安装 / 已装好待重启，三段都算。
     * 原版 GUI 提示层据此决定要不要把整块文案与进度条一直挂着——这个过程是「首次启动」
     * 的必经之路，缩成一行角标会让用户以为卡死了。
     */
    public static boolean nativeDownloadVisible() {
        SkijaCompanionDownload.Phase phase = SkijaCompanionDownload.state().phase();
        return phase == SkijaCompanionDownload.Phase.DOWNLOADING
                || phase == SkijaCompanionDownload.Phase.INSTALLING
                || phase == SkijaCompanionDownload.Phase.DONE;
    }

    /**
     * 自动下载进度（0~100）。「下载中但拿不到总长度」时是 -1，提示层画成来回扫的
     * 不定长动画；安装与完成阶段恒为 100。不可见时读它没有意义。
     */
    public static int nativeDownloadPercent() {
        return SkijaCompanionDownload.state().percent();
    }

    /** 缺库时的「手动补包」引导：包名、下载页、目标目录与自动重试承诺。 */
    private static void addManualCompanionLines(List<String> lines, Status current) {
        lines.add("需要伴生包：" + current.expectedPackageName());
        lines.add("下载页：" + NATIVE_DOWNLOAD_PAGE);
        lines.add("把压缩包里的文件夹整个解压到这里：");
        lines.add(current.expectedDirectory());
        lines.add("放好约 20 秒内自动重试，不需要重启游戏");
    }

    /**
     * 确保 Skija 原生库已加载。可重复调用，只有第一次会做实际工作。
     *
     * <p>形状是「先凑齐候选来源，再对每个候选试所有可能的落点」：
     * 落点这一维是 2026-09-29 真机逼出来的——Android 的 linker namespace 不允许 JVM 从外置存储
     * dlopen，同一份 {@code libskija.so} 拷进 {@code libjvm.so} 所在的那棵树就能加载。
     * 所以「找到过文件」和「加载成功」是两件事，提示层要分开说。
     *
     * @return true 表示可以安全使用任何 Skia 类
     */
    public static synchronized boolean ensureLoaded() {
        if (loaded != null) {
            return loaded;
        }

        String mapped = SkijaNativeCatalog.mappedPlatform();
        List<String> attempted = new ArrayList<>();
        List<String> rejectedOnDisk = new ArrayList<>();

        // ---- 1. Skija 默认流程（现在通常只有 skija.library.path 自指目录能走通）----
        if (attempt(null, "Skija 默认路径（自行定位平台包并解压到临时目录）", attempted)) {
            return adopt(null, "Skija 默认路径", mapped, attempted, rejectedOnDisk, true, null);
        }

        File root = nativeRoot();
        List<Source> sources = collectSources(root, mapped, attempted, rejectedOnDisk);

        // ---- 2. 每个候选：先在它自己的位置试，再逐个搬到「链接器允许的落点」----
        List<File> stageRoots = stageRoots(root, mapped);
        for (Source source : sources) {
            String label = source.label();
            if (attempt(source.directory(), label + "（原地）", attempted)) {
                return adopt(source.directory(), label, mapped, attempted, rejectedOnDisk, true, null);
            }
            for (File stageRoot : stageRoots) {
                File stage = SkijaNativeCatalog.stageDir(stageRoot, PINNED_SKIJA_VERSION, source.platform());
                if (stage == null || stage.equals(source.directory())) {
                    continue;
                }
                if (!stageLibrary(source.library(), stage, attempted, label)) {
                    continue;
                }
                if (attempt(stage, label + " → 暂存 " + stage.getAbsolutePath(), attempted)) {
                    int pruned = SkijaNativeCatalog.pruneStaged(stageRoot, PINNED_SKIJA_VERSION,
                            source.platform());
                    return adopt(stage, label + "（已暂存到 " + stageRoot.getAbsolutePath() + "）",
                            mapped, attempted, rejectedOnDisk, true, null, pruned);
                }
            }
        }

        // ---- 全部失败：把结论留下来，等 SkijaHost 的重评窗口 ----
        boolean foundSomething = !sources.isEmpty();
        if (!foundSomething) {
            // 一个候选都没有：这正是自动下载该出场的情形。候选找到了却加载不了的不在此列——
            // 那说明问题不在「有没有包」，重复下载同一个包没有意义（真机 2026-09-29 的教训）。
            SkijaCompanionDownload.onNoCandidates(mapped);
        }
        String summary = buildSummary(mapped, root, sources, attempted, rejectedOnDisk);
        String lastError = attempted.isEmpty() ? null : attempted.get(attempted.size() - 1);
        loaded = Boolean.FALSE;
        mode = "全部失败";
        failure = summary;
        status = new Status(false, mapped, PINNED_SKIJA_VERSION, null, null,
                List.copyOf(attempted), List.copyOf(rejectedOnDisk), lastError, foundSomething);
        return false;
    }

    /**
     * 一个候选原生库的来源。
     *
     * @param platform  这个库属于哪个平台，暂存目录按它分层
     * @param library   原生库文件本体
     * @param directory 直接可用的目录（交给 {@code skija.library.path}）
     * @param label     给人看的一句话，进日志与提示
     */
    private record Source(String platform, File library, File directory, String label) {
    }

    /**
     * 凑齐所有候选来源：JVM 参数指定的文件 → 伴生包目录里递归找到的 → classpath 里的平台包。
     *
     * <p>classpath 那一档只收「本 JVM 叫得出这个名字的文件」：Android 上 {@code mapLibraryName}
     * 是 {@code libskija.so}，而 {@code windows/x64} 那份叫 {@code skija.dll}——真机日志里
     * 那条「classpath 里找不到必需资源 io/github/humbleui/skija/windows/x64/libskija.so」
     * 就是把两者混起来产生的假线索，看着像在找缺失的东西，其实那个组合永远不可能成功。
     */
    private static List<Source> collectSources(File root, String mapped, List<String> attempted,
                                               List<String> rejectedOnDisk) {
        List<Source> sources = new ArrayList<>();

        String explicitFile = property(PROP_NATIVE_FILE);
        if (explicitFile != null) {
            File library = new File(explicitFile);
            File dir = library.getParentFile();
            if (library.isFile() && dir != null) {
                sources.add(new Source(mapped, library, dir,
                        "JVM 参数 " + PROP_NATIVE_FILE + " 指定的文件"));
            } else {
                attempted.add("JVM 参数 " + PROP_NATIVE_FILE + " 指向的文件不存在：" + explicitFile);
            }
        }

        SkijaNativeCatalog.Scan scan = SkijaNativeCatalog.scan(root, PINNED_SKIJA_VERSION, mapped);
        rejectedOnDisk.addAll(scan.rejected());
        for (SkijaNativeCatalog.Candidate candidate : scan.usable()) {
            sources.add(new Source(candidate.platform().isEmpty() ? mapped : candidate.platform(),
                    candidate.library(), candidate.directory(),
                    "伴生包候选 " + candidate.describe()));
        }

        String loadableName = System.mapLibraryName("skija");
        for (String platform : SkijaNativeCatalog.candidatePlatforms(mapped)) {
            if (!SkijaNativeCatalog.loadableWith(platform, loadableName)) {
                continue;
            }
            if (!hasPlatformResource(platform)) {
                continue;
            }
            File dir = SkijaNativeCatalog.extractedDir(root, PINNED_SKIJA_VERSION, platform);
            try {
                extractNatives(platform, dir);
            } catch (Throwable extractFailure) {
                attempted.add("classpath 平台包 " + platform + " 解压失败: "
                        + describe(extractFailure));
                continue;
            }
            sources.add(new Source(platform, new File(dir, loadableName), dir,
                    "classpath 平台包 " + platform));
        }
        return sources;
    }

    /** {@link #ensureLoaded()} 最终走通的是哪条路径，或失败说明。 */
    public static String mode() {
        return mode;
    }

    /** 失败详情；成功但降级到后面的路径时，这里保留前面各条的失败原因。 */
    public static String failure() {
        return failure;
    }

    /**
     * 允许重新评估一次加载。
     *
     * <p>存在的理由：主包不带任何平台的原生库（全走伴生包自动下载），但用户也可以手动装包、
     * 改配置目录、用 JVM 参数指文件，这些都可能发生在游戏已经跑起来之后。{@code loaded}
     * 一旦缓存成 false 就再也不会回头，于是「装好了但没重启」和「压根没装」在屏幕上长得一样。
     * {@code SkijaHost} 按退避窗口调本方法。
     *
     * <p>已经成功的情况下什么都不做——原生库加载进来就退不掉，重置缓存只会让下一次
     * 在已经可用的库上白试一次。
     */
    public static synchronized void reset() {
        if (Boolean.TRUE.equals(loaded)) {
            return;
        }
        loaded = null;
        mode = "未尝试";
        failure = null;
    }

    /** 原生库的落盘根目录：{@code muonium.skija.dir} 覆盖，否则 {@code config/muonium/native/skija}。 */
    public static File nativeRoot() {
        String override = property(PROP_DIR);
        if (override != null && !override.isBlank()) {
            return new File(override, "muonium-skija");
        }
        return ConfigPaths.SKIJA_NATIVE_ROOT;
    }

    private static boolean adopt(File directory, String origin, String mapped,
                                 List<String> attempted, List<String> rejected,
                                 boolean found, String lastError) {
        return adopt(directory, origin, mapped, attempted, rejected, found, lastError, 0);
    }

    private static boolean adopt(File directory, String origin, String mapped,
                                 List<String> attempted, List<String> rejected,
                                 boolean found, String lastError, int pruned) {
        // FALSE→TRUE 翻转 = 渲染层在会话中途复活的时刻（自动重启路线下，这条路
        // 只剩「手动放置伴生包 + 重评窗口捡起」一种来源）：无库窗口期构造的文字
        // 渲染器手里还是空字体数组（形状照画、文字全无），必须在这里安排一次重建。
        // 投回主线程：adopt 可能在重评（渲染线程）或下载线程上进，渲染器重建与
        // 绘制保持同线程。首次加载（null→TRUE）不触发——那时渲染器还没构造。
        boolean recovered = Boolean.FALSE.equals(loaded);
        loaded = Boolean.TRUE;
        // 原生库刚映射进进程、还没发生任何 GL 调用（首个 GL 调用是 SkijaHost 里的
        // DirectContext.makeGL），此刻是唯一安全的窗口：把 libskija 的 GL/EGL 导入
        // 重定向到启动器给 LWJGL 装的那扇门上（MobileGL 一类翻译层的 ID 空间与
        // 状态影子只有一扇门时才成立，详见 SkijaGlRedirector 的类注释）。
        // 失败只影响翻译层环境的互操作，桌面与其它渲染器不设触发条件、原样跳过。
        SkijaGlRedirector.afterLibraryLoad();
        mode = origin;
        // 前面几条为什么没走通要留着，出问题时一眼能看出是「版本不对」还是「落点不对」
        failure = attempted.isEmpty() ? null : String.join(" | ", attempted);
        status = new Status(true, mapped, PINNED_SKIJA_VERSION, origin, directory,
                List.copyOf(attempted), List.copyOf(rejected), lastError, found);
        if (recovered) {
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil
                    .runOnMainThread(() -> {
                        try {
                            com.muoniumplayer.core.management.FontManager.rebuildAllRenderers();
                            com.muoniumplayer.core.rendering.DownloadDynamicIsland
                                    .showRenderLayerHotReloaded();
                        } catch (Throwable rebuildFailure) {
                            System.out.println("[MuoniumPlayer/SkijaNatives] 热启用重建失败: "
                                    + describe(rebuildFailure));
                        }
                    });
        }
        // 任何一次跃迁到 TRUE 都拆自动重启的雷：原生库加载成功 = 重启标记的使命完成。
        // null→TRUE 覆盖「重启后正常启动」，FALSE→TRUE 覆盖「手动放包后重评热载成功」——
        // 两种都要删标记，否则计数会带着旧值活到下一次真的要重启的场景里。
        com.muoniumplayer.core.update.GameRestart.onNativesLoaded();
        if (attempted.size() > 1) {
            System.out.println("[MuoniumPlayer/SkijaNatives] 原生库最终走通：" + origin
                    + "；此前 " + (attempted.size() - 1) + " 条尝试未通过"
                    + (pruned > 0 ? "；回收了 " + pruned + " 个非在用平台目录" : ""));
        }
        return true;
    }

    /**
     * 把候选库拷进某个落点自己的子目录。已存在且大小一致就跳过，所以 20 秒一次的重评
     * 不会反复写 34 MB——只有真正换文件那一次才重拷。
     *
     * @return 拷成功（或本来就对）返回 true；落点不可写则记一条原因返回 false
     */
    private static boolean stageLibrary(File source, File stage, List<String> attempted, String label) {
        File target = new File(stage, source.getName());
        if (target.isFile() && target.length() == source.length()) {
            return true;
        }
        try {
            Files.createDirectories(stage.toPath());
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable failure) {
            attempted.add(label + " → 暂存到 " + stage.getAbsolutePath() + " 失败: "
                    + describe(failure));
            return false;
        }
    }

    /**
     * 试一条路径。{@code directory} 为 null 表示让 Skija 自己走默认定位。
     *
     * <p>失败一定要把异常文本记进 {@code attempted}：候选之间是「换一个再试」的关系，
     * 没有这份记录，最后只有一句「全部失败」，等于没排查材料。
     */
    private static boolean attempt(File directory, String label, List<String> attempted) {
        try {
            if (directory != null) {
                // 必须在调用之前设好；Library.load() 读的就是这个属性
                System.setProperty(PROP_SKIJA_LIBRARY_PATH, directory.getAbsolutePath());
            }
            io.github.humbleui.skija.impl.Library.load();
            attempted.add(label + "：成功");
            return true;
        } catch (Throwable t) {
            attempted.add(label + " 失败: " + describe(t));
            return false;
        }
    }

    private static String describe(Throwable t) {
        return t.getClass().getName() + ": " + t.getMessage();
    }

    /**
     * 把「本机判定 / 有哪些候选 / 试了哪些落点 / 各自为什么不行」压成一段能贴进反馈的话。
     *
     * <p>两种失败形状必须分开写，因为下一步动作完全相反：一个候选都没见到 → 去下载伴生包；
     * 见到了却全都加载失败 → 别再让人下载第二遍，那说明是落点问题（Android 的命名空间
     * 只允许从特定目录 dlopen），这一版已经自动往那些目录搬过了。
     */
    private static String buildSummary(String mapped, File root, List<Source> sources,
                                       List<String> attempted, List<String> rejected) {
        StringBuilder out = new StringBuilder();
        out.append("本机判定 ").append(mapped).append("，Skija ").append(PINNED_SKIJA_VERSION);
        if (!rejected.isEmpty()) {
            out.append(" | 目录里找到但被拒: ").append(String.join(" / ", rejected));
        }
        if (sources.isEmpty()) {
            out.append(" | ").append(root.getAbsolutePath())
                    .append(" 下面没有任何候选文件，需要伴生包 muonium-skija-")
                    .append(mapped.replace('/', '-')).append("-").append(PINNED_SKIJA_VERSION)
                    .append(".zip，解压到这个目录后重启游戏");
        } else {
            out.append(" | 候选已找到 ").append(sources.size()).append(" 份（")
                    .append(sources.get(0).label()).append("）但每个落点都被拒，")
                    .append("再下一遍伴生包没有用；落点试过 ")
                    .append(Math.max(0, attempted.size() - 1)).append(" 处");
        }
        if (!attempted.isEmpty()) {
            out.append(" | 已尝试: ").append(joinCapped(attempted, 8));
        }
        return out.toString();
    }

    /** 候选 × 落点是乘法关系，全拼进去能上千字，所以只留前若干条并说明还剩几条。 */
    private static String joinCapped(List<String> in, int cap) {
        if (in.size() <= cap) {
            return String.join(" | ", in);
        }
        return String.join(" | ", in.subList(0, cap))
                + " | …另有 " + (in.size() - cap) + " 条，完整记录在同前缀的日志行里";
    }

    // ------------------------------------------------------------------
    // 链接器允许的落点（Android 上真正的坎）
    // ------------------------------------------------------------------

    /** 「链接器允许的落点」列表，含从 {@code /proc/self/maps} 读到的 libjvm 所在目录。 */
    private static List<File> stageRoots(File configRoot, String mapped) {
        return SkijaNativeCatalog.stageRoots(systemProperties(), System.getenv(),
                libjvmDirectory(), configRoot, mapped.startsWith("android"));
    }

    /** 单元测试喂不进去真系统属性，所以这里显式取一份快照。 */
    private static Map<String, String> systemProperties() {
        Map<String, String> snapshot = new java.util.HashMap<>();
        for (String key : List.of("java.library.path", "sun.boot.library.path",
                "java.io.tmpdir", "user.home", "user.dir")) {
            snapshot.put(key, System.getProperty(key, ""));
        }
        return snapshot;
    }

    /**
     * 本进程实际从哪儿映射了 {@code libjvm.so}。
     *
     * <p>这是「哪个目录在链接器命名空间允许范围内」的最强证据：JVM 自己就在那棵树里跑。
     * 读不到（平台没有 {@code /proc/self/maps}、或格式变了）返回 null，其余信号继续用。
     */
    private static String libjvmDirectory() {
        try (java.util.stream.Stream<String> lines =
                     Files.lines(Path.of("/proc/self/maps"))) {
            return SkijaNativeCatalog.libjvmDirectoryFromMaps(
                    lines.filter(line -> line.endsWith("/libjvm.so")).findFirst().orElse(""));
        } catch (Throwable unreadable) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // classpath 解压
    // ------------------------------------------------------------------

    /**
     * 把指定平台的原生库、ICU 数据、版本标记解到一个独立目录。
     *
     * <p>目录名带 Skija 版本与平台，升级或换平台时不会复用旧文件；文件已存在且大小一致时
     * 跳过写入，因此只有第一次启动付出解压几十 MB 的代价。
     */
    private static void extractNatives(String platform, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建 Skija 原生库目录: " + dir);
        }
        String resourcePrefix = SkijaNativeCatalog.RESOURCE_ROOT + "/" + platform;

        // skija.version 必须存在，Skija 会读它做一致性校验，伴生包判据也用它。
        copyResource(resourcePrefix + "/" + SkijaNativeCatalog.VERSION_FILE,
                new File(dir, SkijaNativeCatalog.VERSION_FILE));
        // 原生库文件名必须正好是 System.mapLibraryName("skija") 的结果，
        // 因为 Library._loadFromDir 就是按这个名字去拼路径的。
        copyResource(resourcePrefix + "/" + System.mapLibraryName("skija"),
                new File(dir, System.mapLibraryName("skija")));
        // ICU 数据：不是所有平台构建都带，缺失不算错误。
        copyResourceIfPresent(resourcePrefix + "/icudtl.dat", new File(dir, "icudtl.dat"));
    }

    /** classpath 里有没有这个平台的原生库包（用版本标记当探针，它一定在平台包里）。 */
    private static boolean hasPlatformResource(String platform) {
        return findResource(SkijaNativeCatalog.RESOURCE_ROOT + "/" + platform + "/"
                + SkijaNativeCatalog.VERSION_FILE) != null;
    }

    private static void copyResource(String resource, File target) throws IOException {
        if (!copyResourceIfPresent(resource, target)) {
            throw new IOException("classpath 里找不到必需资源 " + resource);
        }
    }

    private static boolean copyResourceIfPresent(String resource, File target) throws IOException {
        URL url = findResource(resource);
        if (url == null) {
            return false;
        }
        // 已经解压过且长度一致就不重复写：避免每次启动都拷几十 MB，
        // 也避免覆盖一个正在被本进程 mmap 的 dll（Windows 上会直接失败）。
        long expected = contentLength(url);
        if (target.isFile() && expected >= 0 && target.length() == expected) {
            return true;
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp" + ProcessHandle.current().pid());
        try (InputStream in = url.openStream(); OutputStream out = Files.newOutputStream(tmp.toPath())) {
            in.transferTo(out);
        }
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    private static long contentLength(URL url) {
        try {
            java.net.URLConnection c = url.openConnection();
            c.setUseCaches(false);
            long n = c.getContentLengthLong();
            if (c instanceof java.io.Closeable closeable) {
                closeable.close();
            }
            return n;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    /**
     * 按几个类加载器依次找资源。
     *
     * <p>{@code skija-shared} 与平台 native jar 在 Fabric 下是两个独立的嵌套 jar，
     * 不保证由同一个加载器可见，所以这里把 Skija 自己的、本 mod 的、上下文的都试一遍。
     * 注意 {@code Library.class} 只是取类字面量，不会触发它的静态初始化，
     * 因此不会在设好 {@code skija.library.path} 之前意外触发加载。
     */
    private static URL findResource(String resource) {
        ClassLoader[] candidates = {
                io.github.humbleui.skija.impl.Library.class.getClassLoader(),
                SkijaNatives.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };
        for (ClassLoader cl : candidates) {
            if (cl == null) {
                continue;
            }
            URL url = cl.getResource(resource);
            if (url != null) {
                return url;
            }
        }
        return SkijaNatives.class.getResource("/" + resource);
    }

    /** 读一个可能被启动器置空的系统属性。 */
    private static String property(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : value;
    }

    /** 本机判定结果，例如 {@code android/arm64}。 */
    public static String mappedPlatform() {
        return SkijaNativeCatalog.mappedPlatform();
    }
}
