package com.muoniumplayer.core.audio.ffmpeg;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Copies Android natives into the launcher's executable namespace before JavaCPP initialization. */
final class FfmpegNativeStaging {
    private FfmpegNativeStaging() { }

    static Path prepare(Path installed, String platform) throws IOException {
        if (!platform.startsWith("android-")) return installed;
        String jvm = "";
        try (var lines = Files.lines(Path.of("/proc/self/maps"))) {
            jvm = jvmDirectory(lines.filter(line -> line.endsWith("/libjvm.so")).findFirst().orElse(""));
        } catch (IOException ignored) { }
        return stage(installed, candidateRoots(System.getProperties().entrySet().stream()
                .filter(e -> e.getKey() instanceof String && e.getValue() instanceof String)
                .collect(java.util.stream.Collectors.toMap(e -> (String) e.getKey(), e -> (String) e.getValue())),
                System.getenv(), jvm), platform);
    }

    static List<Path> candidateRoots(Map<String, String> properties, Map<String, String> env, String jvm) {
        var paths = new LinkedHashSet<Path>();
        add(paths, jvm);
        split(paths, properties.get("sun.boot.library.path"), File.pathSeparator);
        split(paths, properties.get("java.library.path"), File.pathSeparator);
        split(paths, env.get("LD_LIBRARY_PATH"), ":");
        add(paths, env.get("FCL_NATIVEDIR"));
        add(paths, env.get("POJAV_NATIVEDIR"));
        add(paths, properties.get("java.io.tmpdir"));
        add(paths, properties.get("user.home"));
        return List.copyOf(paths);
    }
    private static void add(java.util.Set<Path> paths, String value) {
        if (value == null || value.isBlank() || value.startsWith("/storage/")
                || value.startsWith("/sdcard/") || value.startsWith("/mnt/media_rw/")) return;
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            String text = path.toString();
            if (!text.startsWith("/storage/") && !text.startsWith("/sdcard/") && !text.startsWith("/mnt/media_rw/")) paths.add(path);
        } catch (RuntimeException ignored) { }
    }
    private static void split(java.util.Set<Path> paths, String value, String separator) {
        if (value != null) for (String entry : value.split(java.util.regex.Pattern.quote(separator))) add(paths, entry);
    }
    static String jvmDirectory(String line) {
        int start = line.indexOf(" /");
        if (start < 0) return "";
        String path = line.substring(start + 1).trim();
        return path.endsWith("/libjvm.so") ? path.substring(0, path.length() - "/libjvm.so".length()) : "";
    }

    static Path stage(Path installed, List<Path> roots, String platform) throws IOException {
        List<Path> sources;
        try (var files = Files.list(installed)) {
            sources = files.filter(p -> p.getFileName().toString().endsWith(".so")
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
        if (sources.isEmpty()) throw new IOException("No installed Android FFmpeg libraries");
        MessageDigest digest = digest();
        for (Path source : sources) {
            digest.update(source.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(hash(source));
        }
        String identity = HexFormat.of().formatHex(digest.digest());
        IOException failure = new IOException("No writable launcher-private directory for Android FFmpeg natives");
        for (Path root : roots) {
            Path target = root.resolve("muonium-ffmpeg").resolve(platform).resolve(identity);
            try {
                Files.createDirectories(target);
                for (Path source : sources) {
                    Path file = target.resolve(source.getFileName());
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            && MessageDigest.isEqual(hash(source), hash(file))) continue;
                    Path incoming = Files.createTempFile(target, ".incoming-", ".part");
                    try {
                        Files.copy(source, incoming, StandardCopyOption.REPLACE_EXISTING);
                        if (!MessageDigest.isEqual(hash(source), hash(incoming))) throw new IOException("Native staging verification failed");
                        try { Files.move(incoming, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(incoming, file, StandardCopyOption.REPLACE_EXISTING); }
                    } finally { Files.deleteIfExists(incoming); }
                }
                return target;
            } catch (IOException problem) { failure.addSuppressed(problem); }
        }
        throw failure;
    }
    private static byte[] hash(Path file) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
