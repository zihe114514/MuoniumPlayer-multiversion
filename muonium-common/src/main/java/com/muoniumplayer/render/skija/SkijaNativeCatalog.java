package com.muoniumplayer.render.skija;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「这台机器上到底有哪一份 Skija 原生库可以试」的查表逻辑。
 *
 * <h2>为什么要有这个类</h2>
 * 主包只带 {@code skija-windows-x64}（58 MB 的包体不能再线性涨），Linux / macOS
 * 用户的原生库来自<b>伴生包</b>：他们把 {@code muonium-skija-<platform>-<skija版本>.zip}
 * 解压到 {@code config/muonium/native/skija/} 下面。于是运行期面对的不是「一个路径」
 * 而是「一堆可能出现在各处、命名不一定标准、版本不一定对得上」的候选文件，
 * 需要有人把它们找出来、排序、校验，并把「为什么没用上」说清楚。
 *
 * <p>Android 单独成档（下面那套平台映射认它），伴生包照发：2026-10-05 真机复测推翻了
 * 「MobileGlues 装不出上下文」的旧结论——真凶是 ANGLE 报的桌面语义版本串，渲染器选
 * MobileGlues 并关闭 ANGLE 时 Skia 的 GL 互操作完整可用。这一层只负责把平台判对；
 * Android 上真正的坎在链接器命名空间，由下面的 stageRoots 一节处理。
 *
 * <p>这件事和「真正去 {@code System.load}」分开写，是因为加载是有副作用的（加载成功就卸不掉），
 * 而找候选必须是<b>能在单元测试里跑</b>的：本类不引用 {@code ConfigPaths}（它的静态块会真的
 * 迁移用户文件）、不引用任何 Skija 与 Minecraft 类，只吃 {@link File} 与字符串。
 *
 * <h2>为什么排序只是启发式，判定权在加载试探</h2>
 * Skija 的 {@code OperatingSystem} 枚举只有 {@code WINDOWS/LINUX/MACOS}，没有 ANDROID，
 * 而 Android 上的 JVM 报的是 {@code os.name=Linux}——光看字符串永远分不清
 * 「真正的 Linux」和「Android 里那个 Linux」。更可靠的判据是 ELF 自己：
 * {@code android/arm64} 那份要 {@code libandroid.so}/{@code liblog.so} 且没有任何
 * {@code GLIBC_*} 符号版本，{@code linux/arm64} 那份要 {@code libc.so.6}+{@code libGL.so.1}
 * +{@code libfontconfig.so.1}。两者在对方的系统里<b>必然</b> {@code UnsatisfiedLinkError}，
 * 所以「按本机映射排在前面 + 逐个试着加载」比任何字符串猜测都不会装错：
 * 猜错的候选只会换来一个可捕获的异常，不会「加载了错的库还活着」。
 *
 * <h2>为什么版本必须硬校验</h2>
 * JNI 方法名里写死了 Java 类名，{@code skija-shared} 与原生库版本不匹配时，
 * 失败点不在加载阶段，而在后面某一次具体方法调用上——症状是「原生库明明加载成功了，
 * 界面还是不出来」。所以候选目录里必须有一份 {@code skija.version}，且内容要等于
 * 主包编译时钉的那版；不等就直接拒掉，并把原因交给提示层说给人听。
 */
final class SkijaNativeCatalog {

    /** classpath 资源里 Skija 平台包的公共前缀。 */
    static final String RESOURCE_ROOT = "io/github/humbleui/skija";

    /**
     * 已知的 {@code <os>/<arch>} 平台标识。
     *
     * <p>顺序有讲究：Android 排在 Linux 同架构之前是不必要的（映射优先已经处理了本机），
     * 这里的顺序只决定「本机映射没命中时，按什么次序试 classpath 里剩下的平台包」。
     */
    static final List<String> KNOWN_PLATFORMS = List.of(
            "windows/x64", "linux/x64", "linux/arm64", "macos/x64", "macos/arm64",
            "android/arm64", "android/x64");

    /** 版本标记文件名，与 Skija 平台 jar 里那份同名。 */
    static final String VERSION_FILE = "skija.version";

    /** 三种原生库文件名。一个候选到底该叫什么，取决于它编译给哪个 OS 家族。 */
    private static final Set<String> LIBRARY_FILE_NAMES = Set.of(
            "libskija.so", "skija.dll", "libskija.dylib");

    /** 目录扫描的最大深度：伴生包正常只有 2~3 层，用户多套几层目录也要认出来。 */
    private static final int MAX_SCAN_DEPTH = 8;

    private SkijaNativeCatalog() {
    }

    // ------------------------------------------------------------------
    // 本机平台判定
    // ------------------------------------------------------------------

    /**
     * 本机平台标识，形如 {@code android/arm64}。
     *
     * <p>Android 必须单独成档：Skija 为它发的是 {@code android/arm64} 这个资源目录，
     * 而按 {@code os.name} 只会得到 {@code linux/arm64}，那份是 glibc 构建，手机上装不上。
     */
    static String mappedPlatform() {
        return mappedPlatform(System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                System.getenv(),
                new File("/system/build.prop").isFile());
    }

    /**
     * 判定逻辑本体，参数全部外部传入，方便单元测试把三种运行时喂进来。
     *
     * @param androidMarker {@code /system/build.prop} 是否存在——Android（含 Termux）一定有，
     *                      桌面 Linux 一定没有；再加上 {@code ANDROID_ROOT}/{@code ANDROID_DATA}
     *                      任一存在即认定 Android。
     */
    static String mappedPlatform(String osName, String osArch,
                                Map<String, String> env, boolean androidMarker) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = archOf(osArch);
        boolean windows = os.contains("win");
        boolean macos = os.contains("mac") || os.contains("darwin");
        if (windows) {
            return "windows/" + arch;
        }
        if (macos) {
            return "macos/" + arch;
        }
        // ANDROID_ROOT 是 Android 应用进程与 Termux 都有的环境变量；单独 build.prop 也够。
        boolean android = androidMarker
                || (env.get("ANDROID_ROOT") != null && !env.get("ANDROID_ROOT").isBlank())
                || (env.get("ANDROID_DATA") != null && !env.get("ANDROID_DATA").isBlank());
        return (android ? "android" : "linux") + "/" + arch;
    }

    /** 把 {@code os.arch} 收成 Skija 用的两档架构名。 */
    static String archOf(String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("armv8")) {
            return "arm64";
        }
        return "x64";
    }

    /** 平台标识里那段 OS；用于「只认得出文件名叫什么、认不出目录」的候选。 */
    static String osOf(String platform) {
        int slash = platform.indexOf('/');
        return slash < 0 ? platform : platform.substring(0, slash);
    }

    /** 某个平台该用的原生库文件名（伴生包按平台编译，文件名不会跨 OS 家族）。 */
    static String libraryNameFor(String platform) {
        return switch (osOf(platform)) {
            case "windows" -> "skija.dll";
            case "macos" -> "libskija.dylib";
            default -> "libskija.so";
        };
    }

    /**
     * 这份平台的库，眼前这个 JVM 在<b>文件命名层面</b>能不能加载。
     *
     * <p>{@code Skija._loadFromDir} 拼路径用的是{@code System.mapLibraryName("skija")}，
     * 也就是说 Linux/Android 的 JVM 只会去找 {@code libskija.so}。于是 classpath 里那份
     * {@code windows/x64/skija.dll} 无论怎么解都永远加载不了——真机日志里出现过一条
     * 「classpath 里找不到必需资源 io/github/humbleui/skija/windows/x64/libskija.so」，
     * 看着像在找缺的东西，其实那个组合根本不成立，纯噪音。这一层先把它筛掉。
     *
     * <p>只筛命名，不筛 ABI：{@code linux/arm64} 与 {@code android/arm64} 同名，
     * 谁能在本机加载由试加载决定（错的会抛 {@code UnsatisfiedLinkError}）。
     */
    static boolean loadableWith(String platform, String thisJvmLibraryName) {
        return libraryNameFor(platform).equals(thisJvmLibraryName);
    }

    /**
     * 候选尝试顺序：本机映射排第一，其余按 {@link #KNOWN_PLATFORMS}。
     *
     * <p>本机映射可能不在已知列表里（比如某个没见过 Skija 出包的平台），那时它仍然排第一，
     * 只是后面不会有任何候选命中它。
     */
    static List<String> candidatePlatforms() {
        String mapped = mappedPlatform();
        return candidatePlatforms(mapped);
    }

    static List<String> candidatePlatforms(String mapped) {
        List<String> ordered = new ArrayList<>();
        ordered.add(mapped);
        for (String known : KNOWN_PLATFORMS) {
            if (!known.equals(mapped)) {
                ordered.add(known);
            }
        }
        return ordered;
    }

    // ------------------------------------------------------------------
    // 链接器允许的落点（Android 上真正的坎）
    // ------------------------------------------------------------------

    /**
     * 「原生库放去哪儿 {@code dlopen} 才肯接」的候选目录，按可能性排序。
     *
     * <p>这一条是 2026-09-29 真机读出来的，不是推测：FCL（Android 16）上伴生包与内嵌包两条路
     * 都被找到、版本也校验过了，加载却停在同一句话——
     * <pre>
     * dlopen failed: library "/storage/emulated/0/FCL/.minecraft/.../libskija.so"
     *   needed or dlopened by "/data/data/com.tungsten.fcl/app_runtime/java/jre21/lib/server/libjvm.so"
     *   is not accessible for the namespace "clns-9"
     * </pre>
     * 也就是说 Android 的 linker namespace 不允许 JVM 从外置存储映射可执行文件——
     * 那半条路径（{@code /data/data/com.tungsten.fcl/app_runtime/java/jre21/lib/server/}）
     * 反而是它自己正在用的地方。所以这里列的全是「与 libjvm 同源」的位置：
     * libjvm 所在目录、{@code sun.boot.library.path}、{@code java.library.path}、
     * {@code LD_LIBRARY_PATH}、{@code java.io.tmpdir}。外置存储那条排最后——
     * 桌面 Linux 与 Windows 上游戏目录就是普通 ext4/NTFS，那里是合法且最顺手的落点。
     *
     * <p>调用方拿到的列表要逐个试（建目录→拷→{@code Library.load()}），成功即停；
     * 本函数不碰文件系统，所以能在单元测试里把三种运行时喂进来。
     *
     * @param libjvmDirectory 从 {@code /proc/self/maps} 里读到的 libjvm.so 所在目录，可为 null
     * @param configRoot      伴生包目录；非 Android 上它排最前
     * @param android         本机是否判定为 Android（{@link #mappedPlatform()} 的那一段）
     */
    static List<File> stageRoots(Map<String, String> properties, Map<String, String> env,
                                 String libjvmDirectory, File configRoot, boolean android) {
        List<File> ordered = new ArrayList<>();
        if (android) {
            addParentChain(ordered, libjvmDirectory);
            addAll(ordered, splitPath(properties.get("java.library.path"), File.pathSeparatorChar));
            addParentChain(ordered, properties.get("sun.boot.library.path"));
            addAll(ordered, splitPath(env.get("LD_LIBRARY_PATH"), ':'));
            addParentChain(ordered, properties.get("java.io.tmpdir"));
        }
        // 游戏配置目录：桌面是正解，Android 上是那条被拒的路径，所以 Android 放最后兜一手
        // （别的启动器/容器运行时也许真允许，代价只是一次多余的试加载）
        if (!android && configRoot != null) {
            ordered.add(configRoot);
        }
        addAll(ordered, splitPath(properties.get("java.library.path"), File.pathSeparatorChar));
        if (android && configRoot != null) {
            ordered.add(configRoot);
        }
        return dedup(ordered);
    }

    /** 把 {@code a/b/c} 这样的目录拆成 {@code a/b/c, a/b} 两级：命名空间允许的往往是上层那棵树。 */
    private static void addParentChain(List<File> out, String directory) {
        if (directory == null || directory.isBlank()) {
            return;
        }
        File current = new File(directory);
        for (int depth = 0; current != null && depth < 3; depth++) {
            if (current.isDirectory()) {
                out.add(current);
            }
            current = current.getParentFile();
        }
    }

    private static void addAll(List<File> out, List<File> more) {
        out.addAll(more);
    }

    /** 拆 {@code java.library.path} / {@code LD_LIBRARY_PATH} 这类拼接串。 */
    static List<File> splitPath(String joined, char separator) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<File> out = new ArrayList<>();
        for (String part : joined.split(java.util.regex.Pattern.quote(String.valueOf(separator)))) {
            if (!part.isBlank()) {
                out.add(new File(part));
            }
        }
        return out;
    }

    /** 去掉重复与不存在的目录，保持先出现的顺序。 */
    private static List<File> dedup(List<File> in) {
        List<File> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (File file : in) {
            if (file == null || !file.isDirectory()) {
                continue;
            }
            String key = file.getAbsolutePath();
            if (seen.add(key)) {
                out.add(new File(key));
            }
        }
        return out;
    }

    /**
     * 从 {@code /proc/self/maps} 的文本里找 libjvm.so 所在目录。
     *
     * <p>读它而不是猜包名：maps 里那一行就是「本进程实际从哪儿映射了 JVM」，
     * 那个目录所在的树必然在允许的命名空间里——这是我们能拿到的最强证据。
     * 解析不出（没有该行、格式变了）返回 null，调用方继续用别的信号。
     */
    static String libjvmDirectoryFromMaps(String mapsText) {
        if (mapsText == null || mapsText.isEmpty()) {
            return null;
        }
        for (String line : mapsText.split("\n")) {
            int pathStart = line.indexOf(" /");
            if (pathStart < 0) {
                continue;
            }
            String path = line.substring(pathStart + 1).trim();
            if (path.endsWith("/libjvm.so")) {
                return path.substring(0, path.length() - "/libjvm.so".length());
            }
        }
        return null;
    }

    /**
     * 某个落点下的暂存目录：{@code <root>/muonium-skija/<skija版本>/<platform>}。
     *
     * <p>挂在 mod 自己名的子目录下，回收与「这是谁写的」都不用猜。
     */
    static File stageDir(File root, String skijaVersion, String platform) {
        if (root == null) {
            return null;
        }
        return root.toPath()
                .resolve("muonium-skija")
                .resolve(safeSegment(skijaVersion))
                .resolve(safeSegment(platform.replace('/', File.separatorChar)))
                .toFile();
    }

    /**
     * 回收某个落点下 {@code muonium-skija} 里的非在用平台。
     *
     * <p>34 MB 一份，多平台、多版本堆在应用私有目录里没人清就说不过去了；
     * 与 {@link #pruneExtracted} 同一条底线：只动 {@code muonium-skija} 这一支自己写的东西。
     */
    static int pruneStaged(File root, String skijaVersion, String keepPlatform) {
        File base = new File(root, "muonium-skija");
        if (!base.isDirectory()) {
            return 0;
        }
        int removed = 0;
        File[] versions = base.listFiles();
        if (versions == null) {
            return 0;
        }
        for (File versionDir : versions) {
            if (!versionDir.isDirectory()) {
                continue;
            }
            if (!versionDir.getName().equals(safeSegment(skijaVersion))) {
                removed += deleteRecursively(versionDir) ? 1 : 0;
                continue;
            }
            File[] platforms = versionDir.listFiles();
            if (platforms == null) {
                continue;
            }
            for (File platformDir : platforms) {
                String osPart = platformDir.getName();
                File[] archDirs = platformDir.listFiles();
                if (!platformDir.isDirectory() || archDirs == null) {
                    continue;
                }
                for (File archDir : archDirs) {
                    String asPath = osPart + File.separator + archDir.getName();
                    if (archDir.isDirectory() && !asPath.equals(
                            safeSegment(keepPlatform.replace('/', File.separatorChar)))) {
                        removed += deleteRecursively(archDir) ? 1 : 0;
                    }
                }
                if (platformDir.list() != null && platformDir.list().length == 0) {
                    deleteRecursively(platformDir);
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 磁盘上的伴生包发现
    // ------------------------------------------------------------------

    /**
     * 一个候选原生库。
     *
     * @param platform 从路径推出来的 {@code <os>/<arch>}；推不出来时为空串
     * @param library  原生库文件本身
     * @param directory 它所在的目录，交给 {@code skija.library.path} 用的就是这个目录
     * @param version  就近读到的 {@code skija.version} 内容；读不到为 null
     * @param score    排序分，大的先试
     */
    record Candidate(String platform, File library, File directory, String version, int score) {

        /** 给人看的一行：平台 + 文件名 + 版本。提示层直接拿来拼文案。 */
        String describe() {
            String shownPlatform = platform.isEmpty() ? "未知平台" : platform;
            String shownVersion = version == null ? "无 " + VERSION_FILE : version;
            return shownPlatform + " · " + library.getName() + " · " + shownVersion;
        }
    }

    /** 一次扫描的结果：可用候选（已按优先级排序）与被拒原因。 */
    record Scan(List<Candidate> usable, List<String> rejected) {
    }

    /**
     * 在 {@code root} 下递归找原生库，按 {@code expectedVersion} 校验，按 {@code mapped} 排序。
     *
     * <p>刻意<b>不要求</b>目录结构规范：用户从 zip 里解压时很容易多套一层
     * {@code muonium-skija-android-arm64/}，甚至把 {@code android/arm64} 改成了别的名。
     * 认不出来只是排到后面，不是丢掉——真正能不能用由加载试探决定。
     */
    static Scan scan(File root, String expectedVersion, String mapped) {
        List<Candidate> usable = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (root == null || !root.isDirectory()) {
            return new Scan(usable, rejected);
        }
        for (File library : findLibraryFiles(root)) {
            File directory = library.getParentFile();
            if (directory == null) {
                continue;
            }
            String platform = inferPlatformFromPath(library);
            String version = readVersionNear(directory);
            if (version == null) {
                rejected.add(library.getAbsolutePath() + "：旁边找不到 " + VERSION_FILE
                        + "，无法确认它与本模组的 Skija 版本一致");
                continue;
            }
            if (expectedVersion != null && !expectedVersion.equals(version)) {
                rejected.add(library.getName() + "：伴生包是 Skija " + version
                        + "，本模组要 " + expectedVersion + "，请换成同平台的 "
                        + expectedVersion + " 包");
                continue;
            }
            usable.add(new Candidate(platform, library, directory, version,
                    score(platform, library.getName(), mapped)));
        }
        usable.sort(Comparator.comparingInt(Candidate::score).reversed());
        return new Scan(usable, rejected);
    }

    /** 排序分。差一个数量级一档，读日志时一眼能看出为什么选了这个而不是那个。 */
    private static int score(String inferredPlatform, String fileName, String mapped) {
        boolean fileNameMatchesOs = fileName.equals(libraryNameFor(mapped));
        if (inferredPlatform.equals(mapped)) {
            // 文件名还与 mapped 的 OS 家族一致 → 双重一致，最可信
            return fileNameMatchesOs ? 1000 : 900;
        }
        if (inferredPlatform.isEmpty()) {
            // 路径认不出来，但文件名至少说明它是给这个 OS 家族编译的
            return fileNameMatchesOs ? 500 : 400;
        }
        if (osOf(inferredPlatform).equals(osOf(mapped))) {
            // 同 OS 家族、不同架构：x64 的 JVM 上确实有 arm 兼容层，值得一试
            return 200;
        }
        return 100;
    }

    /** 递归收集原生库文件；深度有限制，符号链接不跟，点开头的自有暂存目录不跟，目录不可读就跳过而不是抛。 */
    private static List<File> findLibraryFiles(File root) {
        List<File> found = new ArrayList<>();
        collect(root, 0, found);
        return found;
    }

    private static void collect(File dir, int depth, List<File> out) {
        if (dir == null || depth > MAX_SCAN_DEPTH) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isFile() && LIBRARY_FILE_NAMES.contains(child.getName())) {
                out.add(child);
            } else if (child.isDirectory() && !child.getName().startsWith(".")
                    && !Files.isSymbolicLink(child.toPath())) {
                // 点开头的是本模组自己的暂存目录（.incoming-*：自动下载正在解压的目标），
                // 里面的半成品绝不能进候选——否则加载试探会在写到一半的文件上报错
                collect(child, depth + 1, out);
            }
        }
    }

    /**
     * 从路径尾段推平台标识，例如 {@code .../android/arm64/libskija.so} → {@code android/arm64}。
     *
     * <p>只认「相邻两段正好是某个已知 os 与 arch」这一种形状，推不出来返回空串——
     * 空串仍然会被扫描收录，只是排在后面。
     */
    static String inferPlatformFromPath(File library) {
        List<String> segments = new ArrayList<>();
        File dir = library.getParentFile();
        while (dir != null) {
            segments.add(dir.getName().toLowerCase(Locale.ROOT));
            dir = dir.getParentFile();
        }
        // segments 是从里往外的顺序，所以看 segments[0] 与 segments[1] 这一对
        if (segments.size() >= 2) {
            String guess = segments.get(1) + "/" + segments.get(0);
            if (KNOWN_PLATFORMS.contains(guess)) {
                return guess;
            }
            // 兼容 Termux/NDK 习惯写的架构名：aarch64 与 arm64 同义
            String normalized = segments.get(1) + "/" + archOf(segments.get(0));
            if (KNOWN_PLATFORMS.contains(normalized)) {
                return normalized;
            }
        }
        return "";
    }

    /** 就近读版本标记：本目录 → 上一级 → 上两级。zip 套了一层目录时靠这个兜住。 */
    static String readVersionNear(File directory) {
        File current = directory;
        for (int depth = 0; current != null && depth < 3; depth++, current = current.getParentFile()) {
            File marker = new File(current, VERSION_FILE);
            if (marker.isFile()) {
                try {
                    String text = Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                } catch (IOException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * classpath 兜底解压的落点：{@code <root>/<skija版本>/extracted/<platform>}。
     *
     * <p>「平台分一层」不是洁癖：平台包里一份 {@code libskija.so} 就有 30+ MB，
     * 平铺在一起既没法区分哪份在用，也没法回收。
     * {@code extracted} 这一层是<b>本模组自己的地盘</b>，回收只动它，绝不动用户自己丢进来的文件。
     */
    static File extractedDir(File root, String skijaVersion, String platform) {
        return root.toPath()
                .resolve(safeSegment(skijaVersion))
                .resolve("extracted")
                .resolve(safeSegment(platform.replace('/', File.separatorChar)))
                .toFile();
    }

    /**
     * 清掉 {@code extracted} 下非在用平台。
     *
     * <p>只动 {@code extracted} 这一层——那是 {@link #extractedDir} 自己写出来的目录树。
     * 用户手工丢进来的伴生包与它同根但不同支，永远不在回收范围内：删掉用户的东西
     * 代价是「他得重新下载 30 MB」，而这个代价不该由一次平台判定失误来付。
     *
     * @return 删掉的目录个数
     */
    static int pruneExtracted(File root, String skijaVersion, String keepPlatform) {
        Path extracted = root.toPath().resolve(safeSegment(skijaVersion)).resolve("extracted");
        File extractedDir = extracted.toFile();
        if (!extractedDir.isDirectory()) {
            return 0;
        }
        String keep = safeSegment(keepPlatform.replace('/', File.separatorChar));
        int removed = 0;
        File[] osDirs = extractedDir.listFiles();
        if (osDirs == null) {
            return 0;
        }
        for (File osDir : osDirs) {
            if (!osDir.isDirectory()) {
                continue;
            }
            File[] archDirs = osDir.listFiles();
            if (archDirs == null) {
                continue;
            }
            for (File archDir : archDirs) {
                if (!archDir.isDirectory()) {
                    continue;
                }
                String asPath = osDir.getName() + File.separator + archDir.getName();
                if (!asPath.equals(keep)) {
                    if (deleteRecursively(archDir)) {
                        removed++;
                    }
                }
            }
            // 平台目录清空之后把父 os 目录一起收掉，留下的空壳只会让人以为还装着什么
            File[] survivors = osDir.listFiles();
            if (survivors != null && survivors.length == 0) {
                deleteRecursively(osDir);
            }
        }
        return removed;
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        return file.delete();
    }

    /** 版本号与平台串要当目录名用，把分隔符之外的可疑字符换成下划线。 */
    private static String safeSegment(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '-'
                    || c == '_' || c == File.separatorChar;
            out.append(ok ? c : '_');
        }
        return out.toString();
    }
}
