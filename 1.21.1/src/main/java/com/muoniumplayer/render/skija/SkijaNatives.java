package com.muoniumplayer.render.skija;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Skija 原生库加载器。
 *
 * <p>Skija 自带的加载流程（{@code Library.load()}）优先走 JPMS {@code ResourceFinder}
 * 去定位平台 jar 里的 {@code skija.version}，找不到时才退回类加载器资源查找。Fabric 的
 * Knot 类加载器不是模块化环境，这条链路是否可用无法靠读代码断定，所以本类提供两条路径：
 *
 * <ol>
 *   <li><b>默认路径</b>：直接调 {@code Library.load()}，让 Skija 自己找、自己解压到临时目录；</li>
 *   <li><b>确定性路径</b>：本类自己从 classpath 把 {@code skija.dll} / {@code icudtl.dat} /
 *       {@code skija.version} 解到固定目录，再通过 {@code -Dskija.library.path} 指给 Skija。</li>
 * </ol>
 *
 * <p>先试 1，失败则退到 2。{@link #mode()} 会报告最终走的是哪条，日志里能直接看到结论。
 *
 * <p><b>为什么必须自己管 icudtl.dat</b>：Skia 的文字整形（HarfBuzz + ICU 双向/断句）依赖
 * 这个 10 MB 的 ICU 数据文件，它必须和原生库在同一个目录里。只解 dll 不解它，
 * 单行西文能画出来、CJK 与双向文本会在整形阶段失败。
 *
 * <p><b>线程约束</b>：{@link #ensureLoaded()} 是 {@code synchronized} 的，任何线程都可以调，
 * 但只会真正执行一次。它不需要 GL 上下文——加载原生库和创建 GPU 上下文是两件事。
 */
public final class SkijaNatives {

    /** 平台 jar 里资源的包路径前缀，例如 {@code io/github/humbleui/skija/windows/x64}。 */
    private static final String RESOURCE_ROOT = "io/github/humbleui/skija";

    /** 覆盖解压目标目录；不设时用 {@code user.dir}，再退到 {@code java.io.tmpdir}。 */
    private static final String PROP_DIR = "muonium.skija.dir";

    /** Skija 自己认的「直接从这个目录加载」开关。 */
    private static final String PROP_SKIJA_LIBRARY_PATH = "skija.library.path";

    private static Boolean loaded;
    private static String mode = "未尝试";
    private static String failure;

    private SkijaNatives() {
    }

    /**
     * 确保 Skija 原生库已加载。可重复调用，只有第一次会做实际工作。
     *
     * @return true 表示可以安全使用任何 Skija 类
     */
    public static synchronized boolean ensureLoaded() {
        if (loaded != null) {
            return loaded;
        }

        StringBuilder log = new StringBuilder();

        // ---- 路径 1：Skija 默认流程 ----
        try {
            io.github.humbleui.skija.impl.Library.load();
            loaded = Boolean.TRUE;
            mode = "默认路径（Skija 自行定位并解压到临时目录）";
            failure = null;
            return true;
        } catch (Throwable defaultPathFailure) {
            log.append("默认路径失败: ")
                    .append(defaultPathFailure.getClass().getName())
                    .append(": ")
                    .append(defaultPathFailure.getMessage());
        }

        // ---- 路径 2：自己解压 + 显式指定目录 ----
        try {
            File dir = extractNatives();
            // 必须在再次调用 load() 之前设好；Library.load() 读的就是这个属性。
            System.setProperty(PROP_SKIJA_LIBRARY_PATH, dir.getAbsolutePath());
            io.github.humbleui.skija.impl.Library.load();
            loaded = Boolean.TRUE;
            mode = "确定性路径（自行解压到 " + dir.getAbsolutePath() + "）";
            failure = log.toString();
            return true;
        } catch (Throwable explicitPathFailure) {
            log.append(" | 确定性路径失败: ")
                    .append(explicitPathFailure.getClass().getName())
                    .append(": ")
                    .append(explicitPathFailure.getMessage());
            loaded = Boolean.FALSE;
            mode = "全部失败";
            failure = log.toString();
            return false;
        }
    }

    /** {@link #ensureLoaded()} 最终走通的是哪条路径，或失败说明。 */
    public static String mode() {
        return mode;
    }

    /** 失败详情；成功但降级到路径 2 时，这里保留路径 1 的失败原因。 */
    public static String failure() {
        return failure;
    }

    // ------------------------------------------------------------------
    // 确定性路径的实现
    // ------------------------------------------------------------------

    /**
     * 把当前平台的原生库、ICU 数据、版本标记解到一个固定目录。
     *
     * <p>目录名带版本号，升级 Skija 时不会复用旧文件；文件已存在且大小一致时跳过写入，
     * 因此只有第一次启动付出解压 20+ MB 的代价。
     */
    private static File extractNatives() throws IOException {
        String platform = platformPath();
        String version = readVersionResource(platform);
        File dir = new File(baseDir(), "muonium-skija/" + version);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建 Skija 原生库目录: " + dir);
        }

        // skija.version 必须存在，Skija 会读它做一致性校验。
        copyResource(platform + "/skija.version", new File(dir, "skija.version"));
        // 原生库文件名必须正好是 System.mapLibraryName("skija") 的结果，
        // 因为 Library._loadFromDir 就是按这个名字去拼路径的。
        copyResource(platform + "/" + System.mapLibraryName("skija"),
                new File(dir, System.mapLibraryName("skija")));
        // ICU 数据：不是所有平台构建都带，缺失不算错误。
        copyResourceIfPresent(platform + "/icudtl.dat", new File(dir, "icudtl.dat"));

        return dir;
    }

    private static String readVersionResource(String platform) throws IOException {
        URL url = findResource(platform + "/skija.version");
        if (url == null) {
            throw new IOException("classpath 里找不到 " + platform + "/skija.version"
                    + "，说明当前平台的 Skija native jar 没有被打包进来。"
                    + " 当前平台判定为 " + platform);
        }
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
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
        // 已经解压过且长度一致就不重复写：避免每次启动都拷 20+ MB，
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

    private static File baseDir() {
        String override = System.getProperty(PROP_DIR);
        if (override != null && !override.isBlank()) {
            return new File(override);
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank() && new File(userDir).isDirectory()) {
            return new File(userDir);
        }
        return new File(System.getProperty("java.io.tmpdir", "."));
    }

    // ------------------------------------------------------------------
    // 平台判定
    // ------------------------------------------------------------------

    /** 返回形如 {@code io/github/humbleui/skija/windows/x64} 的资源路径。 */
    static String platformPath() {
        return RESOURCE_ROOT + "/" + osName() + "/" + archName();
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "x64";
    }
}
