package com.muoniumplayer.core.audio.ffmpeg;

import org.bytedeco.javacpp.Loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Installs this platform on first background use, then loads the JavaCPP audio and bounded video-cover bindings. */
public final class FfmpegRuntime {
    private static volatile boolean loaded;
    private static final boolean EMBEDDED_NATIVE = detectEmbeddedNative();
    private static volatile String platformOverride;
    private static volatile Path nativeDirectory;
    private static volatile IOException linkageFailure;
    private FfmpegRuntime() { }

    public static synchronized void ensureLoaded() throws IOException {
        if (loaded) return;
        if (linkageFailure != null) throw new IOException("FFmpeg native initialization failed; restart after repairing the audio dependency", linkageFailure);
        String name = Thread.currentThread().getName();
        if (name.equals("Render thread") || name.equals("Client thread") || name.startsWith("JSyn")
                || name.equals("muonium-openal-output")) {
            throw new IOException("FFmpeg native installation and loading require a background worker");
        }
        String platform = platform();
        // Installation must finish before any JavaCPP global class can poison its initializer.
        Path directory = EMBEDDED_NATIVE ? null : FfmpegNativeStaging.prepare(FfmpegNativeAssets.ensureInstalled(platform), platform);
        Properties properties = null;
        String oldLink = null;
        String oldPreload = null;
        try {
            if (platform.startsWith("android-")) {
                // 1.5.14 caches PLATFORM in Loader's initializer; select Android first.
                if (!platform.equals(System.getProperty("org.bytedeco.javacpp.platform"))) {
                    System.setProperty("org.bytedeco.javacpp.platform", platform);
                    platformOverride = platform;
                }
                if (!platform.equals(Loader.getPlatform())) {
                    throw new IOException("JavaCPP Loader was initialized before Android platform selection; "
                            + "select org.bytedeco.javacpp.platform=" + platform + " before JavaCPP initialization");
                }
            }
            properties = Loader.loadProperties();
            oldLink = properties.getProperty("platform.linkpath");
            oldPreload = properties.getProperty("platform.preloadpath");
            if (directory != null) {
                // Global class initializers call Loader.load() recursively. The verified
                // 1.5.14 cached Properties object must also see this temporary search path.
                properties.setProperty("platform.linkpath", prepend(directory, oldLink));
                properties.setProperty("platform.preloadpath", prepend(directory, oldPreload));
            }
            Loader.load(org.bytedeco.ffmpeg.global.avutil.class, properties, true);
            org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR);
            Loader.load(org.bytedeco.ffmpeg.global.swresample.class, properties, true);
            Loader.load(org.bytedeco.ffmpeg.global.avcodec.class, properties, true);
            Loader.load(org.bytedeco.ffmpeg.global.avformat.class, properties, true);
            Loader.load(org.bytedeco.ffmpeg.global.swscale.class, properties, true);
            int result = org.bytedeco.ffmpeg.global.avformat.avformat_network_init();
            if (result < 0) throw NativeErrors.failure("avformat_network_init", result);
            nativeDirectory = directory;
            loaded = true;
        } catch (LinkageError | RuntimeException e) {
            linkageFailure = new IOException("Cannot load JavaCPP FFmpeg for " + platform, e);
            throw linkageFailure;
        } finally {
            if (properties != null) {
                restore(properties, "platform.linkpath", oldLink);
                restore(properties, "platform.preloadpath", oldPreload);
            }
        }
    }

    /** Cover preparation shares the verified native cohort and all installation/owner constraints. */
    public static void ensureVideoLoaded() throws IOException { ensureLoaded(); }

    private static String prepend(Path directory, String old) {
        return directory.toAbsolutePath() + (old == null || old.isBlank() ? "" : java.io.File.pathSeparator + old);
    }
    private static void restore(Properties properties, String key, String value) {
        if (value == null) properties.remove(key); else properties.setProperty(key, value);
    }
    private static boolean detectEmbeddedNative() {
        try {
            String platform = platform();
            String jni = platform.startsWith("windows") ? "jniavutil.dll"
                    : platform.startsWith("macosx") ? "libjniavutil.dylib" : "libjniavutil.so";
            return FfmpegRuntime.class.getResource("/org/bytedeco/ffmpeg/" + platform + "/" + jni) != null;
        } catch (IOException unsupported) { return false; }
    }
    public static boolean hasEmbeddedNative() { return EMBEDDED_NATIVE; }
    public static boolean isLoaded() { return loaded; }
    public static Path nativeDirectory() { return nativeDirectory; }
    public static IOException linkageFailure() { return linkageFailure; }
    public static String platform() throws IOException {
        return detectPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                System.getenv(), Files.isRegularFile(Path.of("/system/build.prop")));
    }
    /** Persistent only for OpenJDK Android, whose JavaCPP default otherwise selects glibc. */
    public static String platformOverride() { return platformOverride; }

    static String detectPlatform(String osName, String arch, Map<String, String> env, boolean androidMarker) throws IOException {
        String os = osName.toLowerCase(Locale.ROOT);
        String cpu = switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IOException("Unsupported FFmpeg CPU architecture: " + arch);
        };
        if (os.contains("mac") || os.contains("darwin")) return "macosx-" + cpu;
        if (os.contains("win")) return "windows-" + cpu;
        if (!os.contains("linux") && !os.contains("android")) throw new IOException("Unsupported FFmpeg OS: " + osName);
        boolean android = androidMarker || os.contains("android")
                || nonBlank(env.get("ANDROID_ROOT")) || nonBlank(env.get("ANDROID_DATA"));
        return (android ? "android-" : "linux-") + cpu;
    }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
