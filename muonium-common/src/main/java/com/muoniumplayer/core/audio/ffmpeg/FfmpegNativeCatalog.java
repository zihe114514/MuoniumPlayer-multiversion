package com.muoniumplayer.core.audio.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pinned native metadata. Slim lanes require measured local candidates and explicitly configured remote sources. */
public final class FfmpegNativeCatalog {
    public static final String JAVACPP_VERSION = "1.5.14";
    public static final String FFMPEG_VERSION = "8.1.2-1.5.14";
    public static final String PINNED_VERSION = FFMPEG_VERSION + "-media.1";
    public static final String DESCRIPTOR_RESOURCE = "/muonium/ffmpeg-native.properties";

    private static final List<String> MAVEN_MIRRORS = List.of(
            "https://repo.maven.apache.org/maven2/",
            "https://repo1.maven.org/maven2/");

    public record Archive(String artifact, String platform, long bytes, String sha256,
                          String companionFileName, List<String> sources) {
        public Archive(String artifact, String platform, long bytes, String sha256) {
            this(artifact, platform, bytes, sha256, null, List.of());
        }
        public Archive {
            sources = List.copyOf(sources);
            if (artifact == null || !Set.of("ffmpeg", "javacpp", "slim").contains(artifact)
                    || platform == null || !platform.matches("[a-z]+-(x86_64|arm64)")
                    || bytes <= 0 || bytes > 128L * 1024 * 1024
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid pinned native archive");
            }
            if (artifact.equals("slim")) {
                if (companionFileName == null || !companionFileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.(zip|jar)"))
                    throw new IllegalArgumentException("Slim archive requires an exact safe filename");
                for (String source : sources) {
                    java.net.URI uri = java.net.URI.create(source);
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                        throw new IllegalArgumentException("Slim archive sources must be explicit HTTPS URLs");
                }
            } else if (companionFileName != null || !sources.isEmpty()) {
                throw new IllegalArgumentException("Upstream classifier metadata cannot override its source");
            }
        }
        public String version() { return artifact.equals("javacpp") ? JAVACPP_VERSION : FFMPEG_VERSION; }
        public String fileName() { return companionFileName != null ? companionFileName : artifact + "-" + version() + "-" + platform + ".jar"; }
        public String mavenPath() {
            if (artifact.equals("slim")) throw new IllegalStateException("Slim assets have no upstream Maven coordinate");
            return "org/bytedeco/" + artifact + "/" + version() + "/" + fileName();
        }
        public List<String> urls() { return artifact.equals("slim") ? sources : MAVEN_MIRRORS.stream().map(root -> root + mavenPath()).toList(); }
        public String nativePrefix() {
            if (artifact.equals("slim")) return ""; // Candidate ZIPs contain flat native files.
            if (platform.startsWith("android-")) {
                return "lib/" + (platform.equals("android-arm64") ? "arm64-v8a" : "x86_64") + "/";
            }
            return "org/bytedeco/" + artifact + "/" + platform + "/";
        }
    }

    public record Pack(String platform, List<Archive> archives, Set<String> dependencies) {
        public Pack(String platform, List<Archive> archives) { this(platform, archives, Set.of()); }
        public Pack {
            archives = List.copyOf(archives);
            dependencies = Set.copyOf(dependencies);
            for (String name : dependencies) {
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,150}\\.(dll|dylib|so(\\.[0-9]+)*)")
                        || name.contains("avdevice") || name.contains("avfilter")
                        || name.contains("ffmpeg") || name.contains("ffprobe")) {
                    throw new IllegalArgumentException("Invalid slim native dependency: " + name);
                }
            }
            if (platform == null || !SUPPORTED.contains(platform)
                    || archives.stream().anyMatch(a -> !a.platform().equals(platform))) {
                throw new IllegalArgumentException("Native pack platform mismatch");
            }
            boolean stock = archives.size() == 2 && archives.stream().map(Archive::artifact)
                    .collect(java.util.stream.Collectors.toSet()).equals(Set.of("ffmpeg", "javacpp"));
            boolean slim = REQUIRES_SLIM.contains(platform) && (archives.isEmpty()
                    || (archives.size() == 1 && archives.get(0).artifact().equals("slim")));
            if ((!stock && !slim) || (!dependencies.isEmpty() && (stock || archives.isEmpty())))
                throw new IllegalArgumentException("Expected two upstream classifiers or one pinned slim archive");
        }
        /** Exact extraction allowlist; every listed library is required for a complete install. */
        public Set<String> files(Archive archive) {
            if (!archives.contains(archive)) throw new IllegalArgumentException("Archive is not part of this pack");
            if (archive.artifact().equals("slim")) {
                Set<String> files = new LinkedHashSet<>(coreFiles(platform));
                files.addAll(dependencies);
                return Set.copyOf(files);
            }
            Set<String> names = new LinkedHashSet<>();
            if (archive.artifact().equals("javacpp")) {
                names.add(library(platform, "jnijavacpp", ""));
                if (platform.startsWith("windows-")) names.addAll(WINDOWS_CRT);
            } else {
                String[] components = {"avutil", "swresample", "avcodec", "avformat", "swscale"};
                String[] versions = {"60", "6", "62", "62", "9"};
                for (int i = 0; i < components.length; i++) {
                    names.add(library(platform, components[i], versions[i]));
                    names.add(library(platform, "jni" + components[i], ""));
                }
                // Native imports checked against the pinned PE/ELF/Mach-O archives.
                if (platform.startsWith("windows-")) names.add("libwinpthread-1.dll");
                if (platform.equals("linux-x86_64")) names.addAll(Set.of("libva.so.2", "libva-drm.so.2", "libdrm.so.2"));
                if (platform.startsWith("macosx-")) names.add("libatomic.1.dylib");
                // linux-arm64 upstream does not bundle libva/libdrm/libudev; these remain host dependencies.
            }
            return Set.copyOf(names);
        }
        public Set<String> files() {
            if (archives.isEmpty()) return coreFiles(platform);
            Set<String> result = new LinkedHashSet<>();
            for (Archive archive : archives) result.addAll(files(archive));
            return Set.copyOf(result);
        }
    }

    private static Set<String> coreFiles(String platform) {
        Set<String> files = new LinkedHashSet<>();
        String[] components = {"avutil", "swresample", "avcodec", "avformat", "swscale"};
        String[] versions = {"60", "6", "62", "62", "9"};
        for (int i = 0; i < components.length; i++) {
            files.add(library(platform, components[i], versions[i]));
            files.add(library(platform, "jni" + components[i], ""));
        }
        files.add(library(platform, "jnijavacpp", ""));
        return Set.copyOf(files);
    }

    private static String library(String platform, String name, String version) {
        if (platform.startsWith("windows-")) return name + (version.isEmpty() ? "" : "-" + version) + ".dll";
        if (platform.startsWith("macosx-")) return "lib" + name + (version.isEmpty() ? "" : "." + version) + ".dylib";
        return "lib" + name + ".so" + (platform.startsWith("android-") || version.isEmpty() ? "" : "." + version);
    }

    private static final Set<String> SUPPORTED = Set.of(
            "windows-x86_64", "linux-x86_64", "linux-arm64", "macosx-x86_64", "macosx-arm64",
            "android-arm64", "android-x86_64");
    private static final Set<String> REQUIRES_SLIM = Set.of("linux-x86_64", "android-arm64");
    private static final Set<String> WINDOWS_CRT = Set.of(
            "api-ms-win-core-console-l1-1-0.dll",
            "api-ms-win-core-console-l1-2-0.dll",
            "api-ms-win-core-datetime-l1-1-0.dll",
            "api-ms-win-core-debug-l1-1-0.dll",
            "api-ms-win-core-errorhandling-l1-1-0.dll",
            "api-ms-win-core-file-l1-1-0.dll",
            "api-ms-win-core-file-l1-2-0.dll",
            "api-ms-win-core-file-l2-1-0.dll",
            "api-ms-win-core-handle-l1-1-0.dll",
            "api-ms-win-core-heap-l1-1-0.dll",
            "api-ms-win-core-interlocked-l1-1-0.dll",
            "api-ms-win-core-libraryloader-l1-1-0.dll",
            "api-ms-win-core-localization-l1-2-0.dll",
            "api-ms-win-core-memory-l1-1-0.dll",
            "api-ms-win-core-namedpipe-l1-1-0.dll",
            "api-ms-win-core-processenvironment-l1-1-0.dll",
            "api-ms-win-core-processthreads-l1-1-0.dll",
            "api-ms-win-core-processthreads-l1-1-1.dll",
            "api-ms-win-core-profile-l1-1-0.dll",
            "api-ms-win-core-rtlsupport-l1-1-0.dll",
            "api-ms-win-core-string-l1-1-0.dll",
            "api-ms-win-core-synch-l1-1-0.dll",
            "api-ms-win-core-synch-l1-2-0.dll",
            "api-ms-win-core-sysinfo-l1-1-0.dll",
            "api-ms-win-core-timezone-l1-1-0.dll",
            "api-ms-win-core-util-l1-1-0.dll",
            "api-ms-win-crt-conio-l1-1-0.dll",
            "api-ms-win-crt-convert-l1-1-0.dll",
            "api-ms-win-crt-environment-l1-1-0.dll",
            "api-ms-win-crt-filesystem-l1-1-0.dll",
            "api-ms-win-crt-heap-l1-1-0.dll",
            "api-ms-win-crt-locale-l1-1-0.dll",
            "api-ms-win-crt-math-l1-1-0.dll",
            "api-ms-win-crt-multibyte-l1-1-0.dll",
            "api-ms-win-crt-private-l1-1-0.dll",
            "api-ms-win-crt-process-l1-1-0.dll",
            "api-ms-win-crt-runtime-l1-1-0.dll",
            "api-ms-win-crt-stdio-l1-1-0.dll",
            "api-ms-win-crt-string-l1-1-0.dll",
            "api-ms-win-crt-time-l1-1-0.dll",
            "api-ms-win-crt-utility-l1-1-0.dll",
            "msvcp140.dll",
            "ucrtbase.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll");

    // Bytes and SHA-256 measured from the verified, unmodified Maven classifier JARs.
    private static final List<Pack> PACKS = List.of(
        new Pack("windows-x86_64", List.of(
                archive("ffmpeg", "windows-x86_64", 30441678L,
                        "67ebc3e6940add83b1b8a9dfd337a5c67556f2b72cb3225efe91a26874445c8e"),
                archive("javacpp", "windows-x86_64", 2031831L,
                        "69bb4b0322aa807199485a9bf394ffe2117bb9ef5c34762b7d6567d7053087b5"))),
        new Pack("linux-x86_64", List.of(
                archive("ffmpeg", "linux-x86_64", 26915834L,
                        "6d0f000c4ddede3b669aa7c5c585e9b71f69b7f621fed7ab28ec01045dc336d0"),
                archive("javacpp", "linux-x86_64", 47603L,
                        "1efab617735a44529bb85daaaefabe46c8686582989c46593c36569e9786fdaa"))),
        new Pack("linux-arm64", List.of(
                archive("ffmpeg", "linux-arm64", 26304735L,
                        "c8729978c862b0e2e5643ade1b41266c5ba8c34b791f41104e5794ad3cefd0bf"),
                archive("javacpp", "linux-arm64", 42779L,
                        "30f63f17bb05cba4bdf488b77a3d9785b2ec261d4d528cf219b6c7081d20d636"))),
        new Pack("macosx-x86_64", List.of(
                archive("ffmpeg", "macosx-x86_64", 23977215L,
                        "b5a8f5124fb2d04412bd01eb5ca469d153d1c396fd0c67a33d3f9a1501c94c0c"),
                archive("javacpp", "macosx-x86_64", 39995L,
                        "ce2e642c0317d08f08fc97ba44b2c18d2a22a85516fdec6b9854f3a930c5127b"))),
        new Pack("macosx-arm64", List.of(
                archive("ffmpeg", "macosx-arm64", 20582183L,
                        "af57468c0bb7b2e9c8c93547e6d599e2b4faa4117118555be92675850972420c"),
                archive("javacpp", "macosx-arm64", 38712L,
                        "75d755656d3ddafaa51c5d2d8d340c731a0bf2d742b033a37641a905aa2a7332"))),
        new Pack("android-arm64", List.of(
                archive("ffmpeg", "android-arm64", 23360403L,
                        "763bb56cd8a72140053312e73c1b30edc952d1d884a580fcc8f6eca18e838667"),
                archive("javacpp", "android-arm64", 153248L,
                        "c8912bf0f29c09a89ee09d5c4c3da5bee64fe0a73fd3ea59be4c56684b2b720a"))),
        new Pack("android-x86_64", List.of(
                archive("ffmpeg", "android-x86_64", 24825598L,
                        "6194a88faaa4c00d7c86cdfc93e45d81745ffb7072dc1d7879b7d49119611d10"),
                archive("javacpp", "android-x86_64", 156479L,
                        "22e4e6d9a7abdb4fb77b663bd700ef5fa429289e0f8bba946872a06303c7ba95"))));

    private static Archive archive(String artifact, String platform, long bytes, String sha256) {
        return new Archive(artifact, platform, bytes, sha256);
    }
    public static Set<String> platforms() { return SUPPORTED; }
    /** Default choices: two slim lanes stay pending until their verified candidate metadata is configured. */
    public static List<Pack> packs() {
        return PACKS.stream().map(pack -> REQUIRES_SLIM.contains(pack.platform())
                ? new Pack(pack.platform(), List.of()) : pack).toList();
    }
    /** Explicit stock metadata for secondary platforms and development audits; never an implicit slim fallback. */
    public static List<Pack> upstreamPacks() { return PACKS; }
    public static Pack packFor(String platform) {
        Properties defaults = platform != null && REQUIRES_SLIM.contains(platform) ? bundledDescriptor() : new Properties();
        return packFor(platform, defaults, System.getProperties());
    }

    /**
     * Resource keys: <platform>.fileName/bytes/sha256/urls/dependencies.
     * Explicit muonium.ffmpeg.slim.<platform>.<field> properties override individual fields, including empty urls.
     * Mirror order and the entire list are preserved; every mirror shares the archive's pinned bytes and SHA-256.
     */
    static Pack packFor(String platform, Properties defaults, Properties overrides) {
        if (platform == null) return null;
        if (REQUIRES_SLIM.contains(platform)) {
            String file = metadata(platform, "fileName", defaults, overrides);
            String bytes = metadata(platform, "bytes", defaults, overrides);
            String hash = metadata(platform, "sha256", defaults, overrides);
            String urls = metadata(platform, "urls", defaults, overrides);
            String dependencies = metadata(platform, "dependencies", defaults, overrides);
            urls = urls == null ? "" : urls;
            dependencies = dependencies == null ? "" : dependencies;
            if (file == null && bytes == null && hash == null && urls.isBlank() && dependencies.isBlank()) return new Pack(platform, List.of());
            if (file == null || bytes == null || hash == null) throw new IllegalArgumentException(
                    "Slim FFmpeg descriptor requires fileName, bytes and sha256 for " + platform + ": " + DESCRIPTOR_RESOURCE);
            List<String> sources = urls.isBlank() ? List.of() : java.util.Arrays.stream(urls.split(",", -1)).map(String::trim).toList();
            Set<String> required = dependencies.isBlank() ? Set.of() :
                    Set.copyOf(java.util.Arrays.stream(dependencies.split(",", -1)).map(String::trim).toList());
            try { return slimPack(platform, file, Long.parseLong(bytes), hash, sources, required); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid slim FFmpeg byte count", invalid); }
        }
        for (Pack pack : PACKS) if (pack.platform().equals(platform)) return pack;
        return null;
    }

    private static String metadata(String platform, String field, Properties defaults, Properties overrides) {
        String value = overrides.getProperty("muonium.ffmpeg.slim." + platform + "." + field);
        if (value == null) value = defaults.getProperty(platform + "." + field);
        return value == null ? null : value.trim();
    }

    private static Properties bundledDescriptor() {
        try (InputStream input = FfmpegNativeCatalog.class.getResourceAsStream(DESCRIPTOR_RESOURCE)) {
            return readDescriptor(input);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Cannot read FFmpeg native descriptor: " + DESCRIPTOR_RESOURCE, invalid);
        }
    }

    /** A missing packaged descriptor is a pending release, never fabricated metadata or an upstream slim fallback. */
    static Properties readDescriptor(InputStream input) throws IOException {
        Properties result = new Properties();
        if (input == null) return result;
        byte[] content = input.readNBytes(64 * 1024 + 1);
        if (content.length > 64 * 1024) throw new IOException("FFmpeg native descriptor exceeds size limit");
        result.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
        return result;
    }
    /** Only register measured metadata; an empty URL list deliberately means local companionDir only. */
    public static Pack slimPack(String platform, String fileName, long bytes, String sha256, List<String> sources) {
        return slimPack(platform, fileName, bytes, sha256, sources, Set.of());
    }
    /** Dependencies must come from the same verified archive and are mandatory when supplied. */
    public static Pack slimPack(String platform, String fileName, long bytes, String sha256,
                                List<String> sources, Set<String> dependencies) {
        return new Pack(platform, List.of(new Archive("slim", platform, bytes, sha256, fileName, sources)), dependencies);
    }
    private FfmpegNativeCatalog() { }
}
