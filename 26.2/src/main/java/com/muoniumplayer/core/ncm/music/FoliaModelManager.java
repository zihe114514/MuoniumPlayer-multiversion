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

/** Downloads and verifies the two Folia analysis models plus the optional Python runtime. */
public final class FoliaModelManager {
    public enum Kind {
        BEAT_THIS("Beat This!", "beat_this.onnx", 83_077_778L,
                "c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd", false),
        HTDEMUCS("HTDemucs", "htdemucs.onnx", 108_644_650L,
                "099b5be76c1f6922124d07f850250f39d1f33f254a0b8cc90f4ec0dfd0912329", false),
        RUNTIME("必要组件", null, 0L, null, true);

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

    private static final String[] MIRRORS = {
            "https://hf-mirror.com/HUAI4236/folia-models/resolve/main/",
            "https://huggingface.co/HUAI4236/folia-models/resolve/main/",
            "https://github.com/AZURE-HUAI/folia-models/releases/download/weights-v1/"
    };
    private static final File DEFAULT_DIR = new File(new File("config", "muonium"), "models");
    private static volatile File modelsDir = initialDirectory();
    private static volatile File cachedPython;
    private static volatile File cachedPythonRuntime;
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
        cachedPython = null;
        cachedPythonRuntime = null;
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
        cachedPython = null;
        cachedPythonRuntime = null;
        AutomixSettings.setModelsDirectory("");
    }

    public static String directoryLabel() { return "模型位置: " + modelsDir.getAbsolutePath(); }

    public static File path(Kind kind) {
        if (kind == Kind.RUNTIME) return new File(modelsDir, "runtime");
        return new File(modelsDir, kind.file);
    }

    public static boolean isInstalled(Kind kind) {
        if (kind == Kind.RUNTIME) {
            File python = pythonExecutable();
            return python != null && python.isFile();
        }
        File file = path(kind);
        return file.isFile() && (kind.bytes <= 0L || file.length() == kind.bytes);
    }

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
        if (!modelsDir.exists() && !modelsDir.mkdirs() && !modelsDir.isDirectory()) {
            throw new IllegalStateException("cannot create " + modelsDir);
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

    private static HttpURLConnection openFollowing(URL url, int depth) throws Exception {
        if (depth > 6) throw new IllegalStateException("too many redirects");
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

    public static File pythonExecutable() {
        File runtime = path(Kind.RUNTIME);
        File cached = cachedPython;
        if (cached != null && runtime.equals(cachedPythonRuntime) && cached.isFile()) return cached;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String executable = os.contains("win") ? "python.exe" : "python3";
        File direct = new File(runtime, executable);
        File resolved = direct.isFile() ? direct : findPython(runtime, executable, 0);
        if (resolved != null) {
            cachedPython = resolved;
            cachedPythonRuntime = runtime;
        }
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
    private static RuntimeEntry runtimeEntry() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && arch.contains("64")) return new RuntimeEntry("folia-runtime-win32-x64.zip", 35_633_855L, "50a5829ac928071ab2eecaf2667e5dab9c3af2b4b44fb5bb4b7acfb9863cdacd");
        if (os.contains("mac") && arch.contains("aarch64")) return new RuntimeEntry("folia-runtime-darwin-arm64.zip", 44_380_755L, "f8313150254d699396b4ff4c9289bdd2864ba3268bd137e5aeb17963be318cde");
        if (os.contains("linux") && arch.contains("64")) return new RuntimeEntry("folia-runtime-linux-x64.zip", 60_509_680L, "cf1347f5621fed74f8b2dea243ea1365824ac77ab52ddc2183ca8a77180b60da");
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
        for (Kind kind : Kind.values()) {
            File target = path(kind);
            if (kind == Kind.RUNTIME) deleteTree(target); else {
                try { Files.deleteIfExists(target.toPath()); } catch (Throwable ignored) {}
                try { Files.deleteIfExists(new File(modelsDir, kind.file + ".part").toPath()); } catch (Throwable ignored) {}
            }
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
