package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * SMTC 助手的定位与现场编译。
 *
 * <p>SMTC 是 WinRT API，JVM 里没有稳妥的调用路子：用 JNA 手算 COM vtable 偏移一旦写错，
 * 崩掉的是整个游戏进程，不是抛一个能被 try/catch 兜住的异常。而 Windows 自带的
 * .NET Framework 4.x 能直接吃 {@code C:\Windows\System32\WinMetadata} 里的 winmd 元数据。
 * 所以本模组只带一份 C# 源码（{@code muonium/external/SmtcWatcher.cs}），第一次需要它时
 * 用系统自带的 {@code csc.exe} 现场编成 exe，之后作为常驻子进程通信。</p>
 *
 * <p>这么切还有一个决定性好处：助手的任何失败半径都被限制在「镜像不可用」这一格子里，
 * 而不是玩家的整局游戏。子进程崩了、被安全软件拦了、编译没过，全部只影响这一个功能。</p>
 *
 * <p>与 {@link FfmpegSupport} 同一条思路：找不到不是错误，但结论要能原样报给用户，
 * 因为「开着开关却没有效果」是最难自查的一种坏状态。这里把每一步的失败原因都编成枚举，
 * 设置页直接读它。结果与失败原因一起缓存；编译失败只缓存 5 分钟，因为用户在游戏期间
 * 装了新的 .NET 或换了 Windows 版本都可能让它下一次就过。</p>
 */
final class SmtcTooling {

    /** 随 jar 发布的 C# 源码资源路径。 */
    private static final String SOURCE_RESOURCE = "muonium/external/SmtcWatcher.cs";
    private static final String HELPER_NAME = "MuoniumSmtcWatcher.exe";
    private static final String STAMP_NAME = "MuoniumSmtcWatcher.build";

    private static final long COMPILE_TIMEOUT_SECONDS = 90L;
    private static final long MISSING_RECHECK_MILLIS = 5L * 60L * 1_000L;
    /** csc 报错是中文且带换行，只把开头这些留进给用户的理由里。 */
    private static final int MAX_ERROR_SNIPPET = 220;

    /** 助手不可用的原因。设置页与日志共用这一份，避免出现两种说法。 */
    enum Availability {
        /** 已就绪（编译过，或缓存里的产物与源码哈希一致）。 */
        READY,
        /** 非 Windows：SMTC 本身就只存在于 Windows。 */
        NOT_WINDOWS,
        /** 没找到 csc.exe（既不在 .NET Framework 目录，也没有用户自备的）。 */
        NO_COMPILER,
        /** 没找到 WinRT 元数据目录或其中缺少需要的 winmd。 */
        NO_METADATA,
        /** jar 里读不到那份 C# 源码（打包出问题时才会这样）。 */
        NO_SOURCE,
        /** 源码与产物不一致，但编译没通过。理由里带 csc 的原始报错。 */
        COMPILE_FAILED,
        /** 还没探测过。 */
        UNKNOWN
    }

    /** 一次探测的结论。不可变，供任意线程读。 */
    static final class Result {
        final Availability availability;
        final File executable;
        final String reason;

        Result(Availability availability, File executable, String reason) {
            this.availability = availability;
            this.executable = executable;
            this.reason = reason == null ? "" : reason;
        }

        static Result ready(File exe) {
            return new Result(Availability.READY, exe, "");
        }

        static Result failed(Availability availability, String reason) {
            return new Result(availability, null, reason);
        }

        boolean isReady() {
            return availability == Availability.READY && executable != null;
        }
    }

    private static final Object LOCK = new Object();
    private static Result cached;
    private static long nextProbeAtMillis;

    private SmtcTooling() {
    }

    /**
     * 拿到可用的助手。会真的去编译（第一次约 1-3 秒），因此<b>不允许在渲染线程调用</b>；
     * 渲染线程只用 {@link #cachedState()}。
     */
    static Result ensure() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (cached != null && cached.isReady()) return cached;
            if (cached != null && now < nextProbeAtMillis) return cached;
            Result result = probe();
            cached = result;
            if (!result.isReady()) nextProbeAtMillis = now + MISSING_RECHECK_MILLIS;
            return result;
        }
    }

    /**
     * 只回报上一次探测的结论，绝不触发编译。供 HUD 设置页与岛上的状态标签每帧读取。
     */
    static Result cachedState() {
        synchronized (LOCK) {
            return cached == null ? new Result(Availability.UNKNOWN, null, "") : cached;
        }
    }

    /** 让下一次 {@link #ensure()} 重新走完整探测；设置页的「重新探测」按钮用。 */
    static void invalidateCache() {
        synchronized (LOCK) {
            cached = null;
            nextProbeAtMillis = 0L;
        }
    }

    private static Result probe() {
        if (!isWindows()) {
            return Result.failed(Availability.NOT_WINDOWS, "系统媒体控制接口只存在于 Windows");
        }
        File directory = ConfigPaths.SMTC_DIR;
        if (directory == null) {
            return Result.failed(Availability.NO_SOURCE, "配置目录不可用");
        }
        File exe = new File(directory, HELPER_NAME);
        File source = new File(directory, "MuoniumSmtcWatcher.cs");

        byte[] embedded = readResource();
        if (embedded == null) {
            return Result.failed(Availability.NO_SOURCE, "模组包里缺少 " + SOURCE_RESOURCE);
        }
        String hash = sha256(embedded);

        // 产物与源码对得上就直接用，一次 csc 都不起。
        if (exe.isFile() && hash.equals(readStamp(directory))) {
            return Result.ready(exe);
        }

        File compiler = findCompiler();
        if (compiler == null) {
            return Result.failed(Availability.NO_COMPILER,
                    "没找到系统自带的 C# 编译器 csc.exe（可在启动参数里加 -Dmuonium.csc=<路径> 指定）");
        }
        List<String> metadata = findMetadata();
        if (metadata == null) {
            return Result.failed(Availability.NO_METADATA,
                    "没找到 Windows 的 WinRT 元数据（System32\\WinMetadata\\Windows.Media.winmd）");
        }
        if (!writeIfChanged(source, embedded)) {
            return Result.failed(Availability.NO_SOURCE, "写不出源码文件：" + source.getAbsolutePath());
        }

        String error = compile(compiler, source, exe, metadata);
        if (error != null) {
            // 删掉半成品：留着会被下一次启动当成可用产物。
            // noinspection ResultOfMethodCallIgnored
            exe.delete();
            return Result.failed(Availability.COMPILE_FAILED, error);
        }
        writeStamp(directory, hash);
        System.out.println("[Music/SMTC] 系统媒体助手已编译到 " + exe.getAbsolutePath());
        return Result.ready(exe);
    }

    /**
     * @return {@code null} 表示编译成功；否则是给用户看的一行理由（含 csc 的原始报错开头）。
     */
    private static String compile(File compiler, File source, File target, List<String> references) {
        List<String> command = new ArrayList<String>();
        command.add(compiler.getAbsolutePath());
        command.add("-nologo");
        command.add("-target:exe");
        command.add("-platform:anycpu");
        command.add("-out:" + target.getAbsolutePath());
        for (String reference : references) {
            command.add("-r:" + reference);
        }
        command.add(source.getAbsolutePath());

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(target.getParentFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = readFully(process.getInputStream());
            if (!process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                return "csc.exe 编译超时（" + COMPILE_TIMEOUT_SECONDS + " 秒）";
            }
            if (process.exitValue() != 0) {
                return snippet(output);
            }
            return target.isFile() ? null : "csc 报成功但没产出 exe";
        } catch (Throwable failure) {
            return "编译助手时出错：" + failure.getClass().getSimpleName() + " " + nullToEmpty(failure.getMessage());
        }
    }

    /**
     * csc 的候选位置。
     *
     * <p>写死一条路径是不够的：64 位与 32 位各有一份，且 .NET 主次版本号会变。顺序是
     * 用户显式指定 → 游戏目录内的私有位置 → 两个框架目录 → 按版本倒序扫一遍，
     * 这样机器上装了多个 .NET 时用的是最新那份。</p>
     */
    private static File findCompiler() {
        List<File> candidates = new ArrayList<File>();
        addIfPresent(candidates, System.getProperty("muonium.csc"));
        addIfPresent(candidates, System.getenv("MUONIUM_CSC"));
        addIfPresent(candidates, new File(ConfigPaths.BIN_DIR, "csc.exe").getPath());

        String windir = System.getenv("WINDIR");
        if (windir == null || windir.trim().isEmpty()) windir = "C:\\Windows";
        addIfPresent(candidates, windir + "\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe");
        addIfPresent(candidates, windir + "\\Microsoft.NET\\Framework\\v4.0.30319\\csc.exe");

        // 极少数机器上只有更高版本号目录（v4.0.30319 之外的历史遗留布局），倒序扫一遍兜底。
        File framework = new File(windir + "\\Microsoft.NET\\Framework64");
        File[] children = framework.isDirectory() ? framework.listFiles() : null;
        if (children != null) {
            for (int i = children.length - 1; i >= 0; i--) {
                File nested = new File(children[i], "csc.exe");
                addIfPresent(candidates, nested.getPath());
            }
        }
        for (File candidate : candidates) {
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    /**
     * 编译需要的引用：winmd 元数据 + {@code System.Runtime} facade。
     *
     * <p>少了 facade 会报「类型 System.Attribute 未在引用的程序中定义」——winmd 里的属性签名
     * 指向它。facade 的实际文件名带着版本号目录，所以按 glob 扫，不写死：实测把
     * {@code v4.0_4.0.0.0__b03f5f7f11d50a3a} 这一串写死会因为公钥标记不同而指错文件。</p>
     *
     * @return 引用列表；元数据缺失时返回 {@code null}
     */
    private static List<String> findMetadata() {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.trim().isEmpty()) windir = "C:\\Windows";
        File directory = new File(windir + "\\System32\\WinMetadata");
        File media = new File(directory, "Windows.Media.winmd");
        File foundation = new File(directory, "Windows.Foundation.winmd");
        // 封面走 Windows.Storage.Streams（Thumbnail 是 RandomAccessStreamReference），
        // 所以这份元数据也是硬依赖 —— 三份缺任何一份都编不出来，报 NO_METADATA。
        File storage = new File(directory, "Windows.Storage.winmd");
        if (!media.isFile() || !foundation.isFile() || !storage.isFile()) return null;

        List<String> references = new ArrayList<String>();
        references.add(foundation.getAbsolutePath());
        references.add(media.getAbsolutePath());
        references.add(storage.getAbsolutePath());
        String facade = findSystemRuntimeFacade(windir);
        if (facade != null) references.add(facade);
        return references;
    }

    private static String findSystemRuntimeFacade(String windir) {
        File root = new File(windir + "\\Microsoft.NET\\assembly\\GAC_MSIL\\System.Runtime");
        File[] versions = root.isDirectory() ? root.listFiles() : null;
        if (versions == null) return null;
        for (File version : versions) {
            File facade = new File(version, "System.Runtime.dll");
            if (facade.isFile()) return facade.getAbsolutePath();
        }
        return null;
    }

    private static void addIfPresent(List<File> candidates, String path) {
        if (path == null || path.trim().isEmpty()) return;
        File file = new File(path.trim());
        if (!candidates.contains(file)) candidates.add(file);
    }

    private static byte[] readResource() {
        InputStream stream = SmtcTooling.class.getClassLoader().getResourceAsStream(SOURCE_RESOURCE);
        if (stream == null) return null;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    private static boolean writeIfChanged(File target, byte[] content) {
        try {
            if (target.isFile() && java.util.Arrays.equals(readIfExists(target), content)) {
                return true;
            }
            if (!mkdirs(target.getParentFile())) return false;
            Files.write(target.toPath(), content);
            return true;
        } catch (Throwable failure) {
            System.err.println("[Music/SMTC] 写出 C# 源码失败: " + failure);
            return false;
        }
    }

    private static byte[] readIfExists(File file) {
        try {
            return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readStamp(File directory) {
        byte[] bytes = readIfExists(new File(directory, STAMP_NAME));
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static void writeStamp(File directory, String hash) {
        try {
            mkdirs(directory);
            Files.write(new File(directory, STAMP_NAME).toPath(), hash.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // 戳记丢了只是下次启动多编一次，不影响正确性。
        }
    }

    private static boolean mkdirs(File directory) {
        return directory != null && (directory.isDirectory() || directory.mkdirs());
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            // 理论上到不了这里；真到了就用长度当戳记，宁可每次重编也别把功能判死。
            return Integer.toString(content.length);
        }
    }

    private static String readFully(InputStream stream) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), localeCharset());
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * csc 的报错走的是系统控制台编码（中文 Windows 上是 GBK），不是 UTF-8。
     *
     * <p>按 UTF-8 解会把整段中文解成一串替换符，用户照着这行去搜错误码什么都搜不到。
     * 这里显式取 {@code file.encoding}，取不到再退回 GBK。</p>
     */
    private static java.nio.charset.Charset localeCharset() {
        try {
            String encoding = System.getProperty("sun.stdout.encoding");
            if (encoding == null) encoding = System.getProperty("file.encoding");
            if (encoding != null && !encoding.trim().isEmpty()) {
                return java.nio.charset.Charset.forName(encoding.trim());
            }
        } catch (Throwable ignored) {
            // 未知字符集名：用默认平台编码。
        }
        return java.nio.charset.Charset.defaultCharset();
    }

    private static String snippet(String output) {
        String text = nullToEmpty(output).replace('\r', ' ').replace('\n', ' ').trim();
        while (text.contains("  ")) text = text.replace("  ", " ");
        if (text.isEmpty()) return "csc.exe 编译失败（没有输出）";
        if (text.length() <= MAX_ERROR_SNIPPET) return text;
        return text.substring(0, MAX_ERROR_SNIPPET) + "…";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void closeQuietly(OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }
}
