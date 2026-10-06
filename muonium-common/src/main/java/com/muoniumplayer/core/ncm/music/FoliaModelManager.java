package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 下载并校验两个 Folia 分析模型，外加可选的 Python 运行时。 */
public final class FoliaModelManager {
    public enum Kind {
        BEAT_THIS("Beat This!", "beat_this.onnx", 83_077_778L,
                "c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd", false),
        HTDEMUCS("HTDemucs", "htdemucs.onnx", 108_644_650L,
                "099b5be76c1f6922124d07f850250f39d1f33f254a0b8cc90f4ec0dfd0912329", false),
        RUNTIME("必要组件", null, 0L, null, true),
        /**
         * ONNX Runtime 的 Java 运行时——<b>安卓专用</b>（桌面保持 Python 路线）。
         * 解包内容 = onnxruntime-android AAR 抽包：classes.jar + arm64-v8a 的两个 .so。
         * 构建脚本：{@code tools/build-folia-onnx-runtime.ps1}。
         */
        ONNX_RUNTIME("ONNX 运行时", null, 0L, null, false);

        final String label;
        final String file;
        final long bytes;
        final String sha256;
        final boolean runtime;
        Kind(String label, String file, long bytes, String sha256, boolean runtime) {
            this.label=label; this.file=file; this.bytes=bytes; this.sha256=sha256; this.runtime=runtime;
        }
    }

    public interface Progress { void onProgress(long done, long total); }
    public interface Callback { void done(boolean ok, String detail); }

    /**
     * 下载镜像链。两个模型（beat_this / htdemucs）在上面三路 folia-models 仓库；
     * 安卓 ONNX 运行时 zip 托管在更新清单仓库（MuoniumPlayer-updates），
     * 后三路与 {@code UpdateChecker} 同款三源（ghfast 代理 / raw / jsdelivr）。
     * 未命中该文件的镜像会 404，循环会继续往下一路试（见 {@link #downloadBytes}）。
     */
    private static final String[] MIRRORS = {
            "https://hf-mirror.com/HUAI4236/folia-models/resolve/main/",
            "https://huggingface.co/HUAI4236/folia-models/resolve/main/",
            "https://github.com/AZURE-HUAI/folia-models/releases/download/weights-v1/",
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/"
    };
    /**
     * 安卓 ONNX 运行时 zip 的字节数与 sha256，由 {@code tools/build-folia-onnx-runtime.ps1}
     * 打印。这两个值必须与镜像上的 zip 精确一致，改版本时一起重跑脚本、一起更新。
     */
    private static final long ANDROID_ZIP_BYTES = 12_287_062L;
    private static final String ANDROID_ZIP_SHA256 =
            "389a074e6783a0cc97f05cf2d7fe20711f624c0365160ce9328362f9e4fc6de0";
    private static final File DEFAULT_DIR = new File(new File("config", "muonium"), "models");
    private static volatile File modelsDir = initialDirectory();
    private static volatile File cachedPython;
    private static volatile File cachedPythonRuntime;
    /**
     * 解析失败也要记住，否则「没装运行组件」这条最常见的路径会每次都去扫一遍目录树。
     *
     * <p>只记成功是不够的：{@link #isInstalled(Kind)} 对 RUNTIME 直接问 {@link #pythonExecutable()}，
     * 而 profile 的缓存键、模型可用性判定、提示弹窗都会调它。未安装时每次都 miss，
     * 等于每次分析都做一次递归遍历。</p>
     */
    private static volatile boolean cachedPythonResolved;
    private static final Map<Kind, Double> ACTIVE_DOWNLOADS = new ConcurrentHashMap<Kind, Double>();
    private static final Set<Kind> ACTIVE_INSTALLS = ConcurrentHashMap.newKeySet();

    private static File initialDirectory() {
        String stored = AutomixSettings.getModelsDirectory();
        return stored == null || stored.isEmpty() ? DEFAULT_DIR : new File(stored).getAbsoluteFile();
    }

    private FoliaModelManager() {}

    public static File directory() { return modelsDir; }
    public static File defaultDirectory() { return DEFAULT_DIR; }

    public static void setDirectory(File dir) {
        if (dir == null) return;
        modelsDir = dir.getAbsoluteFile();
        invalidatePythonCache();
        AutomixSettings.setModelsDirectory(modelsDir.getAbsolutePath());
    }

    public static String chooseDirectory() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(modelsDir);
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择 Folia 模型目录");
        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            setDirectory(chooser.getSelectedFile());
            return "模型位置: " + modelsDir.getAbsolutePath();
        }
        return "模型位置未更改";
    }

    public static void resetDirectory() {
        modelsDir = DEFAULT_DIR;
        invalidatePythonCache();
        AutomixSettings.setModelsDirectory("");
    }

    public static String directoryLabel() { return "模型位置: " + modelsDir.getAbsolutePath(); }

    public static File path(Kind kind) {
        if (kind == Kind.RUNTIME) return new File(modelsDir, "runtime");
        if (kind == Kind.ONNX_RUNTIME) return onnxRuntimeDir();
        return new File(modelsDir, kind.file);
    }

    /** ONNX Runtime Java 运行时的解包目录（{@link Kind#ONNX_RUNTIME}，下载目标）。 */
    public static File onnxRuntimeDir() {
        return new File(modelsDir, "onnx-rt");
    }

    /**
     * 运行包三件套实际所在的目录：优先 {@code onnx-rt/}，其次模型根目录。
     *
     * <p>手动解压时很容易把三个文件直接放到 {@code models/} 根（而不是再建一层
     * {@code onnx-rt/}），两种布局都认，免得「文件明明在」却报未安装。
     * 每个目录内的查找还会再看一层子目录（见 {@link #runtimeFile}）。</p>
     */
    static File resolveOnnxRuntimeDir() {
        File canonical = onnxRuntimeDir();
        if (hasRuntimeFiles(canonical)) return canonical;
        if (hasRuntimeFiles(modelsDir)) return modelsDir;
        return canonical;
    }

    private static boolean hasRuntimeFiles(File dir) {
        return runtimeFile(dir, "classes.jar") != null
                && runtimeFile(dir, "libonnxruntime.so") != null
                && runtimeFile(dir, "libonnxruntime4j_jni.so") != null;
    }

    /** 本机是否安卓（判定来自 {@code com.muoniumplayer.render.skija} 的平台映射）。 */
    public static boolean isAndroidPlatform() {
        return com.muoniumplayer.render.skija.SkijaNatives.mappedPlatform().startsWith("android");
    }

    /**
     * 运行组件是否就绪：安卓看 ONNX 运行时，桌面看 Python。
     *
     * <p>推理引擎按平台各走一条：两端引擎不同（手机上是进程内 ONNX Runtime，
     * 桌面是随包管理的 Python 子进程），所以这里没有「任一即可」——桌面上留着一份
     * 用不上的 ONNX 目录不该让提示层闭嘴。
     */
    public static boolean runtimeReady() {
        return isAndroidPlatform() ? isInstalled(Kind.ONNX_RUNTIME) : isInstalled(Kind.RUNTIME);
    }

    /**
     * 提示层应该展示的运行组件 Kind：安卓展示 ONNX 运行时（Python 装不了），
     * 桌面展示 Python（久经考验的主路径）。
     */
    public static Kind runtimeKind() {
        return isAndroidPlatform() ? Kind.ONNX_RUNTIME : Kind.RUNTIME;
    }

    /**
     * 是否走进程内 ONNX 推理：<b>只有安卓</b>。
     *
     * <p>桌面保持 Python 路线不变——Python 运行组件在桌面上久经考验，而桌面 JVM
     * 与 ORT 的 Windows 原生库存在 MSVC 运行库版本兼容问题（上游
     * onnxruntime#24287：ORT 1.21+ 用新工具链编译，JVM 自带旧 VC++ 运行库时
     * DllMain 失败），换引擎只会平添一类只影响桌面的故障。安卓上 Python 跑不起来，
     * ONNX 是唯一选项。
     */
    public static boolean preferOnnxInference() {
        return isAndroidPlatform();
    }

    public static boolean isInstalled(Kind kind) {
        if (kind == Kind.ONNX_RUNTIME) return isOnnxRuntimeInstalled();
        if (kind == Kind.RUNTIME) {
            File python = pythonExecutable();
            return python != null && python.isFile();
        }
        File file = path(kind);
        return file.isFile() && (kind.bytes <= 0L || file.length() == kind.bytes);
    }

    private static boolean isOnnxRuntimeInstalled() {
        if (!isAndroidPlatform()) return false;
        return hasRuntimeFiles(resolveOnnxRuntimeDir());
    }

    /**
     * 安卓 ONNX 运行包的缺失清单；空字符串 = 三件套齐全。
     * 目录缺失、文件不全、多套一层子目录都从同一套查找判定（见 {@link #runtimeFile}）。
     */
    static String onnxRuntimeStatus() {
        File dir = resolveOnnxRuntimeDir();
        if (dir == null || !dir.isDirectory()) return "目录不存在";
        StringBuilder missing = new StringBuilder();
        for (String name : new String[] {"classes.jar", "libonnxruntime.so", "libonnxruntime4j_jni.so"}) {
            if (runtimeFile(dir, name) == null) {
                if (missing.length() > 0) missing.append("、");
                missing.append("缺 ").append(name);
            }
        }
        return missing.toString();
    }

    /**
     * 给日志：模型推理当前的可用性快照。排查「为什么没走模型推理」就看这一行——
     * 三个前置（权重、运行组件、表现模式）里任意一个不满足都会静默退回信号处理。
     */
    public static String inferenceReadiness() {
        StringBuilder text = new StringBuilder();
        text.append(isAndroidPlatform() ? "android" : "desktop");
        text.append(" · Beat This!=").append(isInstalled(Kind.BEAT_THIS) ? "有" : "无");
        text.append(" · HTDemucs=").append(isInstalled(Kind.HTDEMUCS) ? "有" : "无");
        text.append(" · 运行时=");
        if (isAndroidPlatform()) {
            String status = onnxRuntimeStatus();
            text.append(status.isEmpty() ? "就绪" : "未就绪(").append(status).append(')');
        } else {
            text.append(isInstalled(Kind.RUNTIME) ? "就绪" : "未就绪(Python 未装)");
        }
        text.append(" · 表现模式=").append(AutomixSettings.isPerformanceModeEnabled() ? "开" : "关");
        return text.toString();
    }

    /**
     * 在运行时目录里找一个文件；找不到就再往下一层子目录看一眼。
     *
     * <p>解压工具常会「多套一层文件夹」（右键解压到同名目录），那种布局下三件套在
     * {@code onnx-rt/<子目录>/} 里。安装判定与 {@link FoliaOnnx} 的加载找同一份文件，
     * 两边用同一套查找，才不会出现「设置页说已安装、加载说找不到」。
     */
    static File runtimeFile(File dir, String name) {
        if (dir == null) return null;
        File direct = new File(dir, name);
        if (direct.isFile()) return direct;
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                File nested = new File(child, name);
                if (nested.isFile()) return nested;
            }
        }
        return null;
    }

    /**
     * 安卓 ONNX 运行时 zip 的条目（名字/字节数/sha256 由构建脚本
     * {@code tools/build-folia-onnx-runtime.ps1} 产出）。
     *
     * <p>版本必须 ≥ 1.21：1.20 的引擎加载不了 HTDemucs 导出（{@code /dft_istft}
     * 的 DFT 算子在旧版形状推断里报 {@code is_onesided and inverse}）；这里选
     * 1.29.0，与 Python 运行组件里那份 onnxruntime 同版本，两个推理路径对着同一份
     * 模型行为。安卓上 ORT 走 bionic（NDK 编译），不受桌面那份 MSVC 运行库兼容问题影响。
     */
    private static OnnxEntry onnxEntry() {
        return new OnnxEntry("folia-onnx-android-arm64.zip", ANDROID_ZIP_BYTES, ANDROID_ZIP_SHA256);
    }

    private record OnnxEntry(String file, long bytes, String sha256) {}

    public static String statusLabel(Kind kind) {
        Double progress = ACTIVE_DOWNLOADS.get(kind);
        if (progress != null) return kind.label + " · 下载中 " + (int) Math.round(progress * 100.0) + "%";
        if (ACTIVE_INSTALLS.contains(kind)) return kind.label + " · 正在安装";
        return kind.label + " · " + (isInstalled(kind) ? "已安装" : "未安装");
    }

    public static void downloadAsync(Kind kind, Callback callback) {
        MultiThreadingUtil.runAsync(() -> {
            String detail; boolean ok;
            ACTIVE_DOWNLOADS.put(kind, 0.0);
            ACTIVE_INSTALLS.remove(kind);
            DownloadDynamicIsland.beginDownload();
            DownloadDynamicIsland.updateSpeed(kind.label + " · 准备连接");
            try {
                download(kind, (done, total) -> {
                    double progress = total > 0L ? done / (double) total : 0.0;
                    ACTIVE_DOWNLOADS.put(kind, Math.max(0.0, Math.min(1.0, progress)));
                    DownloadDynamicIsland.updateProgress(progress);
                    DownloadDynamicIsland.updateSpeed(kind.label + " · "
                            + formatBytes(done) + (total > 0L ? " / " + formatBytes(total) : ""));
                });
                ACTIVE_DOWNLOADS.put(kind, 1.0);
                ACTIVE_INSTALLS.add(kind);
                DownloadDynamicIsland.finishDownload();
                detail = kind.label + " 已安装"; ok = true;
            } catch (Throwable failure) {
                DownloadDynamicIsland.cancelDownload();
                detail = kind.label + " 安装失败: " + safeMessage(failure); ok = false;
            } finally {
                ACTIVE_DOWNLOADS.remove(kind);
                ACTIVE_INSTALLS.remove(kind);
            }
            if (callback != null) callback.done(ok, detail);
        });
    }

    public static void download(Kind kind, Progress progress) throws Exception {
        // 运行组件的两条腿按平台互斥：安卓是 ONNX 运行时（Python 在手机上不存在），
        // 桌面是 Python 运行组件。少了这道闸，安卓会把 os.name=Linux 匹配到
        // linux-x64 的 Python 包下下来（纯浪费流量），桌面则会下到用不上的安卓包。
        if (isAndroidPlatform() && kind == Kind.RUNTIME) {
            throw new IllegalStateException("Python 运行组件只有桌面版；安卓请安装 ONNX 运行时");
        }
        if (!isAndroidPlatform() && kind == Kind.ONNX_RUNTIME) {
            throw new IllegalStateException("ONNX 运行时只在安卓上使用；桌面请安装必要组件（Python）");
        }
        if (!modelsDir.exists() && !modelsDir.mkdirs() && !modelsDir.isDirectory()) {
            throw new IllegalStateException("cannot create " + modelsDir);
        }
        if (kind == Kind.ONNX_RUNTIME) {
            OnnxEntry entry = onnxEntry();
            File archive = new File(modelsDir, entry.file() + ".part");
            downloadBytes(entry.file(), archive, entry.bytes(), entry.sha256(), progress);
            File unpacked = new File(modelsDir, "onnx-rt.part");
            deleteTree(unpacked);
            unzip(archive, unpacked);
            Files.deleteIfExists(archive.toPath());
            File target = onnxRuntimeDir();
            deleteTree(target);
            try { Files.move(unpacked.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception failure) { deleteTree(unpacked); throw failure; }
            return;
        }
        if (kind == Kind.RUNTIME) {
            RuntimeEntry runtime = runtimeEntry();
            File archive = new File(modelsDir, runtime.file + ".part");
            downloadBytes(runtime.file, archive, runtime.bytes, runtime.sha256, progress);
            File unpacked = new File(modelsDir, "runtime.part");
            deleteTree(unpacked);
            unzip(archive, unpacked);
            Files.deleteIfExists(archive.toPath());
            File runtimeDir = path(Kind.RUNTIME);
            deleteTree(runtimeDir);
            try { Files.move(unpacked.toPath(), runtimeDir.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception failure) { deleteTree(unpacked); throw failure; }
            // 运行组件刚落地，之前缓存的「找不到 python」必须作废，否则装完仍显示未安装。
            invalidatePythonCache();
            return;
        }
        File part = new File(modelsDir, kind.file + ".part");
        downloadBytes(kind.file, part, kind.bytes, kind.sha256, progress);
        Files.move(part.toPath(), path(kind).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void downloadBytes(String name, File part, long expectedBytes, String expectedHash,
                                      Progress progress) throws Exception {
        Exception last = null;
        for (String mirror : MIRRORS) {
            try {
                URL url = URI.create(mirror + name).toURL();
                HttpURLConnection connection = openFollowing(url, 0);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                long total = connection.getContentLengthLong();
                if (expectedBytes > 0L && total > 0L && total != expectedBytes) total = expectedBytes;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024]; long done = 0L; int read;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read); digest.update(buffer, 0, read); done += read;
                        if (progress != null) progress.onProgress(done, total > 0L ? total : expectedBytes);
                    }
                }
                if (expectedBytes > 0L && part.length() != expectedBytes) throw new IllegalStateException("size mismatch");
                String actual = hex(digest.digest());
                if (expectedHash != null && !expectedHash.equalsIgnoreCase(actual)) throw new IllegalStateException("sha256 mismatch");
                return;
            } catch (Exception failure) {
                last = failure;
                try { Files.deleteIfExists(part.toPath()); } catch (Throwable ignored) {}
            }
        }
        throw last == null ? new IllegalStateException("no mirror") : last;
    }

    private static HttpURLConnection openFollowing(URL url, int depth) throws Exception {        if (depth > 6) throw new IllegalStateException("too many redirects");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000); connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-Folia/1.0");
        int code = connection.getResponseCode();
        if (code >= 300 && code < 400) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) throw new IllegalStateException("redirect without location");
            return openFollowing(new URL(url, location), depth + 1);
        }
        return connection;
    }

    /**
     * 运行组件目录换代时作废 python 解析结果。
     *
     * <p>缓存键是「运行组件目录」这个路径，因此换目录、删模型、重装运行组件都必须显式作废；
     * 其余时刻它一直是热的。别把它做成「每次都重扫」——{@link #isInstalled(Kind)} 会从
     * profile 缓存键、模型可用性判定和提示弹窗三条热路径进来。</p>
     */
    private static void invalidatePythonCache() {
        cachedPython = null;
        cachedPythonRuntime = null;
        cachedPythonResolved = false;
    }

    /**
     * 解析出的 Python 可执行文件；未安装运行组件时为 {@code null}。
     *
     * <p>命中与未命中都缓存：未安装是本项目最常见的状态，而 {@code null} 结果每次都要
     * 走一遍 {@link #findPython(File, String, int)} 的递归目录遍历。缓存按
     * {@code cachedPythonRuntime}（即解析时所用的运行组件目录）而不是按文件是否仍存在来判断，
     * 因为目录被替换之后旧的那份 {@code File} 依然可能指向一个同名的旧文件。</p>
     */
    public static File pythonExecutable() {
        // 安卓没有 Python 运行组件：不解析、不扫目录。就算目录里躺着一份被误下载的
        // linux-x64 包（旧版本曾把 aarch64 错判成 linux-x64），也不当它是可用的。
        if (isAndroidPlatform()) return null;
        File runtime = path(Kind.RUNTIME);
        if (cachedPythonResolved && runtime.equals(cachedPythonRuntime)) return cachedPython;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String executable = os.contains("win") ? "python.exe" : "python3";
        File direct = new File(runtime, executable);
        File resolved = direct.isFile() ? direct : findPython(runtime, executable, 0);
        cachedPython = resolved;
        cachedPythonRuntime = runtime;
        cachedPythonResolved = true;
        return resolved;
    }

    private static File findPython(File directory, String executable, int depth) {
        if (directory == null || depth > 4 || !directory.isDirectory()) return null;
        File[] children = directory.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile() && executable.equalsIgnoreCase(child.getName())) return child;
        }
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName().toLowerCase(Locale.ROOT);
            if (name.contains("__pycache__") || name.contains("site-packages")) continue;
            File found = findPython(child, executable, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
    /**
     * 桌面 Python 运行组件的下载条目。
     *
     * <p>安卓上没有这个组件（走 {@link Kind#ONNX_RUNTIME}），所以在安卓上报错而不是
     * 落进任何分支——这里如果用 {@code os.arch.contains("64")} 判断，
     * aarch64 也会命中 linux-x64，手机就会下到 x86-64 的包。
     */
    private static RuntimeEntry runtimeEntry() {
        if (isAndroidPlatform()) {
            throw new IllegalStateException("安卓不存在 Python 运行组件");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.contains("x86_64") || arch.contains("amd64");
        if (os.contains("win") && x64) return new RuntimeEntry("folia-runtime-win32-x64.zip", 35_633_855L, "50a5829ac928071ab2eecaf2667e5dab9c3af2b4b44fb5bb4b7acfb9863cdacd");
        if (os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64"))) return new RuntimeEntry("folia-runtime-darwin-arm64.zip", 44_380_755L, "f8313150254d699396b4ff4c9289bdd2864ba3268bd137e5aeb17963be318cde");
        if (os.contains("linux") && x64) return new RuntimeEntry("folia-runtime-linux-x64.zip", 60_509_680L, "cf1347f5621fed74f8b2dea243ea1365824ac77ab52ddc2183ca8a77180b60da");
        throw new IllegalStateException("unsupported platform " + os + "/" + arch);
    }

    private static final class RuntimeEntry {
        final String file; final long bytes; final String sha256;
        RuntimeEntry(String file,long bytes,String sha256){this.file=file;this.bytes=bytes;this.sha256=sha256;}
    }

    private static void unzip(File archive, File target) throws Exception {
        if (!target.exists() && !target.mkdirs() && !target.isDirectory()) throw new IllegalStateException("cannot create runtime");
        String root = target.getCanonicalPath() + File.separator;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(archive)))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(target, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IllegalStateException("unsafe archive entry");
                if (entry.isDirectory()) out.mkdirs();
                else {
                    File parent = out.getParentFile(); if (parent != null) parent.mkdirs();
                    try (FileOutputStream output = new FileOutputStream(out)) {
                        int read; while ((read = zip.read(buffer)) >= 0) output.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return Math.max(0L, bytes) + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static void deleteAll() {
        invalidatePythonCache();
        for (Kind kind : Kind.values()) {
            File target = path(kind);
            if (kind == Kind.RUNTIME || kind == Kind.ONNX_RUNTIME) {
                // 两者都是目录树；Files.deleteIfExists 删不掉非空目录，必须走递归删除。
                deleteTree(target);
            } else {
                try { Files.deleteIfExists(target.toPath()); } catch (Throwable ignored) {}
                try { Files.deleteIfExists(new File(modelsDir, kind.file + ".part").toPath()); } catch (Throwable ignored) {}
            }
        }
        // 手动解压到模型根目录的那份运行包也一并清掉（resolveOnnxRuntimeDir 认这种布局）。
        for (String name : new String[] {"classes.jar", "libonnxruntime.so", "libonnxruntime4j_jni.so"}) {
            try { Files.deleteIfExists(new File(modelsDir, name).toPath()); } catch (Throwable ignored) {}
        }
    }

    public static String scanKnownFolders() {
        String home = System.getProperty("user.home", "");
        File[] roots = { new File(home, "Downloads"), new File(home, "下载"), new File("."), new File(home, "Desktop") };
        int found = 0;
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            found += scan(root, 0);
        }
        return found == 0 ? "没有找到匹配的 Folia 模型" : "已导入或登记 " + found + " 个模型文件";
    }

    private static int scan(File root, int depth) {
        if (depth > 3) return 0;
        File[] files = root.listFiles(); if (files == null) return 0;
        int found = 0;
        for (File file : files) {
            if (file.isDirectory()) found += scan(file, depth + 1);
            else if (file.isFile()) {
                for (Kind kind : Kind.values()) {
                    if (kind.file != null && kind.file.equalsIgnoreCase(file.getName()) && verify(file, kind)) {
                        try {
                            File target = path(kind);
                            File parent = target.getParentFile();
                            if (parent != null) parent.mkdirs();
                            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            if (kind == Kind.RUNTIME) invalidatePythonCache();
                            found++;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
        return found;
    }

    private static boolean verify(File file, Kind kind) {
        if (kind.bytes <= 0L || file.length() != kind.bytes || kind.sha256 == null) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file))) {
                byte[] buf = new byte[64 * 1024]; int read;
                while ((read = in.read(buf)) >= 0) digest.update(buf, 0, read);
            }
            return kind.sha256.equalsIgnoreCase(hex(digest.digest()));
        } catch (Throwable ignored) { return false; }
    }

    private static void deleteTree(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child);
        }
        try { Files.deleteIfExists(file.toPath()); } catch (Throwable ignored) {}
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
