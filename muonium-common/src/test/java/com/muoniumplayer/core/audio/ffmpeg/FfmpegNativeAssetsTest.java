package com.muoniumplayer.core.audio.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.muoniumplayer.core.audio.ffmpeg.FfmpegNativeAssets.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Small original-filename fixtures exercise installation without a native classpath or public network. */
@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class FfmpegNativeAssetsTest {
    @TempDir Path temp;
    private static final String LINUX = "linux-x86_64";
    private static final String ANDROID = "android-arm64";
    private static final String EMPTY_HASH = "0".repeat(64);

    private record Fixture(FfmpegNativeCatalog.Pack pack, Path archives) { }

    private Fixture fixture(String platform) throws Exception { return fixture(platform, entries -> { }); }
    private Fixture fixture(String platform, Consumer<Map<String, byte[]>> modify) throws Exception {
        Path directory = Files.createTempDirectory(temp, "archives-");
        List<FfmpegNativeCatalog.Archive> descriptors = new ArrayList<>();
        var shape = new FfmpegNativeCatalog.Pack(platform, List.of(
                new FfmpegNativeCatalog.Archive("ffmpeg", platform, 1, EMPTY_HASH),
                new FfmpegNativeCatalog.Archive("javacpp", platform, 1, EMPTY_HASH)));
        for (var archive : shape.archives()) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            for (String name : shape.files(archive).stream().sorted().toList()) {
                entries.put(archive.nativePrefix() + name, (platform + ":" + name).getBytes(StandardCharsets.UTF_8));
            }
            if (archive.artifact().equals("ffmpeg")) {
                entries.put(archive.nativePrefix() + "ffmpeg", new byte[] {1});
                entries.put(archive.nativePrefix() + "ffprobe", new byte[] {2});
                entries.put(archive.nativePrefix() + "libavfilter.so", new byte[] {3});
                entries.put(archive.nativePrefix() + "libavdevice.so", new byte[] {4});
                entries.put(archive.nativePrefix() + "libswscale.so", new byte[] {5});
                entries.put("org/bytedeco/ffmpeg/global/avcodec.class", new byte[] {6});
                entries.put("META-INF/native-image/native-config.json", new byte[] {7});
                modify.accept(entries);
            }
            Path file = directory.resolve(archive.fileName());
            try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            descriptors.add(new FfmpegNativeCatalog.Archive(archive.artifact(), platform, Files.size(file), hash(file)));
        }
        return new Fixture(new FfmpegNativeCatalog.Pack(platform, descriptors), directory);
    }

    private Path root() { return temp.resolve("native").resolve(FfmpegNativeCatalog.PINNED_VERSION); }
    private FfmpegNativeAssets.Installer installer(List<Fixture> fixtures, FfmpegNativeAssets.Transport transport) {
        return new FfmpegNativeAssets.Installer(this::root, fixtures.stream().map(Fixture::pack).toList(),
                transport, () -> null, () -> true, System::nanoTime);
    }
    private FfmpegNativeAssets.Transport copies(List<Fixture> fixtures, AtomicInteger calls) {
        Map<String, Path> originals = new HashMap<>();
        for (Fixture fixture : fixtures) for (var archive : fixture.pack().archives()) {
            originals.put(archive.fileName(), fixture.archives().resolve(archive.fileName()));
        }
        return (archive, destination, progress) -> {
            calls.incrementAndGet();
            assertTrue(originals.containsKey(archive.fileName()), "Only the selected fixture platform can be requested");
            Files.copy(originals.get(archive.fileName()), destination);
            progress.accept(100);
        };
    }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static Set<String> names(Path directory) throws IOException {
        try (var paths = Files.list(directory)) { return paths.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()); }
    }
    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Fixture cancelled");
        }
    }
    private static void ready(FfmpegNativeAssets.Installer installer) {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (installer.snapshot().phase() != READY) Thread.sleep(5);
        });
    }

    @Test void cacheSurvivesNewInstallerWithoutFurtherTransportAndContainsOnlyAllowlistedFiles() throws Exception {
        Fixture fixture = fixture(LINUX);
        AtomicInteger calls = new AtomicInteger();
        var installer = installer(List.of(fixture), copies(List.of(fixture), calls));
        assertEquals(IDLE, installer.snapshot().phase());
        Path result = installer.ensureInstalled(LINUX);
        assertEquals(root().resolve(LINUX), result);
        assertEquals(2, calls.get());
        Set<String> expected = new java.util.HashSet<>(fixture.pack().files());
        expected.add(FfmpegNativeAssets.MANIFEST);
        assertEquals(expected, names(result));
        assertEquals(READY, installer.snapshot().phase());
        assertEquals(100, installer.snapshot().percent());
        assertEquals(result, installer.snapshot().directory());
        var offline = installer(List.of(fixture), (a, p, progress) -> fail("Cache reuse must not download"));
        assertEquals(result, offline.ensureInstalled(LINUX));
        assertEquals(result, offline.ensureInstalled(LINUX));
        assertEquals(READY, offline.snapshot().phase());
    }

    @Test void onlyRequestedPlatformIsFetchedAndAndroidAbiPrefixIsFlattened() throws Exception {
        Fixture linux = fixture(LINUX);
        Fixture android = fixture(ANDROID);
        List<String> requested = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var copy = copies(List.of(linux, android), calls);
        var installer = installer(List.of(linux, android), (archive, target, progress) -> {
            requested.add(archive.platform());
            copy.fetch(archive, target, progress);
        });
        Path result = installer.ensureInstalled(ANDROID);
        assertEquals(List.of(ANDROID, ANDROID), requested);
        assertEquals(11, android.pack().files().size());
        assertTrue(Files.isRegularFile(result.resolve("libavcodec.so")));
        assertTrue(Files.isRegularFile(result.resolve("libjnijavacpp.so")));
        assertFalse(Files.exists(result.resolve("lib")));
        assertFalse(Files.exists(root().resolve(LINUX)));
    }

    @ParameterizedTest @ValueSource(strings = {"windows-x86_64", "linux-arm64", "macosx-arm64", "macosx-x86_64", "android-x86_64"})
    void eachOtherClassifierUsesItsOwnExactNativePrefixAndDependencyAllowlist(String platform) throws Exception {
        Fixture fixture = fixture(platform);
        var installer = installer(List.of(fixture), copies(List.of(fixture), new AtomicInteger()));
        Path result = installer.ensureInstalled(platform);
        for (String name : fixture.pack().files()) assertTrue(Files.isRegularFile(result.resolve(name)), name);
        assertFalse(Files.exists(result.resolve("org")));
        assertFalse(Files.exists(result.resolve("lib")));
        assertEquals(fixture.pack().files().size() + 1, names(result).size());
    }

    @Test void localOriginalArchivesAreVerifiedAndNeverUseTransport() throws Exception {
        Fixture fixture = fixture(LINUX);
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(fixture.pack()),
                (a, p, progress) -> fail("Explicit local source must not use the network"),
                fixture::archives, () -> true, System::nanoTime);
        assertEquals(root().resolve(LINUX), installer.ensureInstalled(LINUX));
    }

    @Test void corruptLocalOriginalDoesNotFallBackToNetworkOrAnotherPlatform() throws Exception {
        Fixture fixture = fixture(LINUX);
        Path archive = fixture.archives().resolve(fixture.pack().archives().get(0).fileName());
        byte[] data = Files.readAllBytes(archive);
        data[data.length / 2] ^= 1;
        Files.write(archive, data);
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(fixture.pack()),
                (a, p, progress) -> fail("Bad local source cannot cause network fallback"),
                fixture::archives, () -> true, System::nanoTime);
        assertTrue(assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX)).getMessage().contains("SHA-256"));
        assertEquals(FAILED, installer.snapshot().phase());
        assertFalse(Files.exists(root().resolve(LINUX)));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void transportCannotBypassPinnedLengthOrHash(boolean truncate) throws Exception {
        Fixture fixture = fixture(LINUX);
        var installer = installer(List.of(fixture), (archive, target, progress) -> {
            byte[] data = Files.readAllBytes(fixture.archives().resolve(archive.fileName()));
            if (truncate) data = java.util.Arrays.copyOf(data, data.length - 1);
            else data[data.length / 2] ^= 1;
            Files.write(target, data);
        });
        IOException error = assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX));
        assertTrue(error.getMessage().contains(truncate ? "length" : "SHA-256"));
        assertEquals(FAILED, installer.snapshot().phase());
        assertFalse(Files.exists(root().resolve(LINUX)));
        assertTrue(names(root()).stream().noneMatch(n -> n.startsWith(".incoming-")));
    }

    @ParameterizedTest @ValueSource(strings = {"../escape.dll", "/tmp/escape.dll", "C:/escape.dll", "lib/../escape.dll", "lib\\escape.dll", "lib//escape.dll", "./escape.dll"})
    void rejectsTraversalEvenInUnselectedZipEntries(String badPath) throws Exception {
        Fixture fixture = fixture(LINUX, entries -> entries.put(badPath, new byte[] {99}));
        var installer = installer(List.of(fixture), copies(List.of(fixture), new AtomicInteger()));
        IOException error = assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX));
        assertTrue(error.getMessage().contains("ZIP path"));
        assertFalse(Files.exists(root().resolve(LINUX)));
        assertFalse(Files.exists(temp.resolve("escape.dll")));
    }

    @Test void rejectsMissingJniLibrary() throws Exception {
        Fixture fixture = fixture(LINUX, entries -> entries.remove("org/bytedeco/ffmpeg/linux-x86_64/libjniavcodec.so"));
        var installer = installer(List.of(fixture), copies(List.of(fixture), new AtomicInteger()));
        assertTrue(assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX)).getMessage().contains("Incomplete"));
        assertEquals(FAILED, installer.snapshot().phase());
        assertFalse(Files.exists(root().resolve(LINUX)));
    }

    @Test void rejectsMissingJavacppArchiveBeforeAnyInstallCommit() throws Exception {
        Fixture fixture = fixture(LINUX);
        var installer = installer(List.of(fixture), (archive, target, progress) -> {
            if (archive.artifact().equals("javacpp")) throw new IOException("Missing JavaCPP archive");
            Files.copy(fixture.archives().resolve(archive.fileName()), target);
        });
        assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX));
        assertFalse(Files.exists(root().resolve(LINUX)));
    }

    @Test void rejectsZipBombBeforeExtractingUnselectedLargeEntry() throws Exception {
        Fixture fixture = fixture(LINUX, entries -> entries.put("ignored/bomb.bin", new byte[2 * 1024 * 1024]));
        var installer = installer(List.of(fixture), copies(List.of(fixture), new AtomicInteger()));
        assertTrue(assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX)).getMessage().contains("expansion limit"));
        assertFalse(Files.exists(root().resolve(LINUX)));
    }

    @Test void sameLengthNativeCorruptionAndForeignExtraFilesInvalidateCache() throws Exception {
        Fixture fixture = fixture(LINUX);
        AtomicInteger calls = new AtomicInteger();
        var installer = installer(List.of(fixture), copies(List.of(fixture), calls));
        Path directory = installer.ensureInstalled(LINUX);
        Path nativeFile = directory.resolve("libjniavcodec.so");
        byte[] data = Files.readAllBytes(nativeFile);
        data[0] ^= 1;
        Files.write(nativeFile, data);
        installer.ensureInstalled(LINUX);
        assertEquals(4, calls.get());
        assertNotEquals(data[0], Files.readAllBytes(nativeFile)[0]);
        Files.write(directory.resolve("foreign-os.dll"), new byte[] {1});
        installer.ensureInstalled(LINUX);
        assertEquals(6, calls.get());
        assertFalse(Files.exists(directory.resolve("foreign-os.dll")));
    }

    @Test void badReplacementPreservesPreviousCompleteCacheByteForByte() throws Exception {
        Fixture original = fixture(LINUX);
        var first = installer(List.of(original), copies(List.of(original), new AtomicInteger()));
        Path directory = first.ensureInstalled(LINUX);
        Map<String, String> before = hashes(directory);
        Fixture replacement = fixture(LINUX, entries -> entries.put("../evil", new byte[] {1}));
        var second = installer(List.of(replacement), copies(List.of(replacement), new AtomicInteger()));
        assertThrows(IOException.class, () -> second.ensureInstalled(LINUX));
        assertEquals(before, hashes(directory));
        assertEquals(directory, first.ensureInstalled(LINUX));
    }

    @Test void ownerCancellationLeavesOldInstallationUsableAndCleansStaging() throws Exception {
        Fixture original = fixture(LINUX);
        var first = installer(List.of(original), copies(List.of(original), new AtomicInteger()));
        Path directory = first.ensureInstalled(LINUX);
        Map<String, String> before = hashes(directory);
        Fixture replacement = fixture(LINUX, entries -> entries.put("META-INF/updated.txt", new byte[] {1}));
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        var cancelled = installer(List.of(replacement), (a, p, progress) -> {
            downloading.countDown();
            await(neverReleased);
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> owner = executor.submit(() -> {
                try { cancelled.ensureInstalled(LINUX); fail("Cancelled install cannot become ready"); }
                catch (IOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
                finally { exited.countDown(); }
            });
            await(downloading);
            assertTrue(owner.cancel(true));
            await(exited);
            assertEquals(FAILED, cancelled.snapshot().phase());
            assertEquals(before, hashes(directory));
            assertTrue(names(root()).stream().noneMatch(n -> n.startsWith(".incoming-")));
            assertEquals(directory, first.ensureInstalled(LINUX));
        } finally { executor.shutdownNow(); }
    }

    @Test void simultaneousCallersShareOneTwoArchiveInstallation() throws Exception {
        Fixture fixture = fixture(LINUX);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(8);
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var copy = copies(List.of(fixture), calls);
        var installer = installer(List.of(fixture), (archive, target, progress) -> {
            downloading.countDown();
            await(release);
            copy.fetch(archive, target, progress);
        });
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Path>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                entered.countDown();
                return installer.ensureInstalled(LINUX);
            }));
            await(downloading);
            await(entered);
            assertEquals(DOWNLOADING, installer.snapshot().phase());
            for (int i = 0; i < 100; i++) assertEquals(DOWNLOADING, installer.snapshot().phase());
            assertEquals(0, calls.get(), "Status polling does not start another request");
            release.countDown();
            for (Future<Path> future : futures) assertEquals(root().resolve(LINUX), future.get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void cancellingWaiterDoesNotCancelSharedOwner() throws Exception {
        Fixture fixture = fixture(LINUX);
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterStopped = new CountDownLatch(1);
        var copy = copies(List.of(fixture), new AtomicInteger());
        var installer = installer(List.of(fixture), (a, p, progress) -> {
            downloading.countDown();
            await(release);
            copy.fetch(a, p, progress);
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Path> owner = executor.submit(() -> installer.ensureInstalled(LINUX));
            await(downloading);
            Future<?> waiter = executor.submit(() -> {
                waiterStarted.countDown();
                try { installer.ensureInstalled(LINUX); }
                catch (IOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
                finally { waiterStopped.countDown(); }
            });
            await(waiterStarted);
            waiter.cancel(true);
            await(waiterStopped);
            assertEquals(DOWNLOADING, installer.snapshot().phase());
            release.countDown();
            assertEquals(root().resolve(LINUX), owner.get(5, TimeUnit.SECONDS));
            assertEquals(READY, installer.snapshot().phase());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void cooldownAndFiniteAutomaticAttemptsCanBeResetByExplicitAsyncRetry() throws Exception {
        Fixture fixture = fixture(LINUX);
        AtomicLong clock = new AtomicLong(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean(true);
        CountDownLatch retryStarted = new CountDownLatch(1);
        CountDownLatch retryRelease = new CountDownLatch(1);
        var copy = copies(List.of(fixture), new AtomicInteger());
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(fixture.pack()), (a, p, progress) -> {
            calls.incrementAndGet();
            if (fail.get()) throw new IOException("Fixture failure");
            retryStarted.countDown();
            await(retryRelease);
            copy.fetch(a, p, progress);
        }, () -> null, () -> true, clock::get);
        assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX));
        for (int i = 0; i < 10; i++) assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX));
        assertEquals(1, calls.get());
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(21));
            assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX));
        }
        assertEquals(3, calls.get(), "Automatic failures have a finite attempt budget");
        fail.set(false);
        installer.retryAsync();
        try {
            await(retryStarted);
            for (int i = 0; i < 100; i++) installer.retryAsync();
        } finally { retryRelease.countDown(); }
        ready(installer);
        assertEquals(5, calls.get());
    }

    @Test void disabledAutomaticDownloadStillAllowsVerifiedCacheButDoesNotFetchMissingCache() throws Exception {
        Fixture fixture = fixture(LINUX);
        var disabled = new FfmpegNativeAssets.Installer(this::root, List.of(fixture.pack()),
                (a, p, progress) -> fail("Disabled download must not invoke transport"),
                () -> null, () -> false, System::nanoTime);
        assertTrue(assertThrows(IOException.class, () -> disabled.ensureInstalled(LINUX)).getMessage().contains("disabled"));
        assertFalse(Files.exists(root().resolve(LINUX)));
        var first = installer(List.of(fixture), copies(List.of(fixture), new AtomicInteger()));
        first.ensureInstalled(LINUX);
        assertEquals(root().resolve(LINUX), disabled.ensureInstalled(LINUX));
    }

    @Test void invalidPlatformNeverCreatesAnInstallRootOrCallsTransport() throws Exception {
        Fixture fixture = fixture(LINUX);
        var installer = installer(List.of(fixture), (a, p, progress) -> fail("Invalid platform cannot request archives"));
        for (String platform : new String[] {null, "windows-arm64", "../linux-x86_64", "linux-x86", ""}) {
            assertThrows(IOException.class, () -> installer.ensureInstalled(platform));
            assertEquals(UNSUPPORTED, installer.snapshot().phase());
            assertNull(installer.snapshot().directory());
        }
        installer.retryAsync();
        assertFalse(Files.exists(root()));
    }

    @Test void interruptedDirectorySwapIsRecoveredWithoutDownloading() throws Exception {
        Fixture fixture = fixture(LINUX);
        var first = installer(List.of(fixture), copies(List.of(fixture), new AtomicInteger()));
        Path target = first.ensureInstalled(LINUX);
        Files.move(target, root().resolve(".previous-" + LINUX), StandardCopyOption.ATOMIC_MOVE);
        var recovered = installer(List.of(fixture), (a, p, progress) -> fail("Recoverable complete install cannot download"));
        assertEquals(target, recovered.ensureInstalled(LINUX));
        assertTrue(Files.isRegularFile(target.resolve("libjnijavacpp.so")));
        assertFalse(Files.exists(root().resolve(".previous-" + LINUX)));
    }

    @Test void catalogIncludesSevenPinnedTwoClassifierPacksAndNoUnpublishedSlimAssets() {
        assertEquals(7, FfmpegNativeCatalog.platforms().size());
        assertEquals(7, FfmpegNativeCatalog.packs().size());
        assertNull(FfmpegNativeCatalog.packFor("windows-arm64"));
        assertEquals(2, FfmpegNativeCatalog.packs().stream().filter(p -> p.archives().isEmpty()).count());
        for (var pack : FfmpegNativeCatalog.upstreamPacks()) {
            assertEquals(2, pack.archives().size());
            assertTrue(pack.files().size() >= 9);
            for (var archive : pack.archives()) {
                assertEquals(pack.platform(), archive.platform());
                assertTrue(archive.bytes() > 1);
                assertTrue(archive.sha256().matches("[0-9a-f]{64}"));
                assertTrue(archive.fileName().endsWith("-" + pack.platform() + ".jar"));
                assertTrue(archive.urls().size() >= 2);
                for (String url : archive.urls()) assertTrue(url.startsWith("https://") && url.endsWith(archive.mavenPath()));
            }
            assertTrue(pack.files().stream().noneMatch(n -> n.contains("avdevice") || n.contains("avfilter")
                    || n.contains("ffmpeg") || n.contains("ffprobe") || n.endsWith(".class")));
        }
    }

    private Fixture slimFixture(String platform) throws Exception {
        Path directory = Files.createTempDirectory(temp, "slim-");
        String fileName = "muonium-ffmpeg-" + FfmpegNativeCatalog.PINNED_VERSION + "-" + platform + "-slim.zip";
        Path archive = directory.resolve(fileName);
        var pending = new FfmpegNativeCatalog.Pack(platform, List.of());
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String name : pending.files().stream().sorted().toList()) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(("slim:" + name).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("LICENSE.txt"));
            zip.write("fixture".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new Fixture(FfmpegNativeCatalog.slimPack(platform, fileName, Files.size(archive), hash(archive), List.of()), directory);
    }

    @ParameterizedTest @ValueSource(strings = {LINUX, ANDROID})
    void singleSlimArchiveInstallsExactlyNineFlatNativesFromLocalCompanion(String platform) throws Exception {
        Fixture fixture = slimFixture(platform);
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(fixture.pack()),
                (a, p, progress) -> fail("Local slim candidate must not access remote stock archives"),
                fixture::archives, () -> true, System::nanoTime);
        Path result = installer.ensureInstalled(platform);
        assertEquals(11, fixture.pack().files().size());
        assertEquals(10, names(result).size());
        assertEquals(READY, installer.snapshot().phase());
        assertTrue(fixture.pack().archives().get(0).urls().isEmpty(), "Local candidates do not imply a published URL");
        assertEquals(result, installer.ensureInstalled(platform));
    }

    @Test void singleSlimArchiveUsesOneVerifiedTransportRequest() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        AtomicInteger calls = new AtomicInteger();
        var installer = installer(List.of(fixture), copies(List.of(fixture), calls));
        installer.ensureInstalled(LINUX);
        assertEquals(1, calls.get());
        assertEquals(READY, installer.snapshot().phase());
    }

    @Test void pendingSlimMetadataFailsExplicitlyWithoutUpstreamFallback() throws Exception {
        var pending = new FfmpegNativeCatalog.Pack(LINUX, List.of());
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(pending),
                (a, p, progress) -> fail("Missing slim metadata must not cause upstream download"),
                () -> null, () -> true, System::nanoTime);
        assertTrue(assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX)).getMessage().contains("metadata is not configured"));
        assertEquals(FAILED, installer.snapshot().phase());
        assertFalse(Files.exists(root()));
    }

    @Test void measuredCandidateCanBeConfiguredAfterPendingFailureWithoutRestartOrCooldown() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        var selected = new java.util.concurrent.atomic.AtomicReference<>(new FfmpegNativeCatalog.Pack(LINUX, List.of()));
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(selected.get()),
                (a, p, progress) -> fail("Late-configured local slim does not use network"),
                fixture::archives, () -> true, System::nanoTime, p -> selected.get());
        assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX));
        selected.set(fixture.pack());
        assertEquals(root().resolve(LINUX), installer.ensureInstalled(LINUX));
        assertEquals(READY, installer.snapshot().phase());
    }

    @Test void systemPropertiesRequirePinnedSlimMetadataAndExplicitUrls() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        var archive = fixture.pack().archives().get(0);
        String prefix = "muonium.ffmpeg.slim." + LINUX + ".";
        Map<String, String> prior = new HashMap<>();
        for (String key : List.of("fileName", "bytes", "sha256", "urls", "dependencies")) {
            prior.put(prefix + key, System.getProperty(prefix + key));
            System.clearProperty(prefix + key);
        }
        try {
            assertTrue(FfmpegNativeCatalog.packFor(LINUX, new java.util.Properties(), System.getProperties()).archives().isEmpty());
            System.setProperty(prefix + "fileName", archive.fileName());
            assertThrows(IllegalArgumentException.class, () -> FfmpegNativeCatalog.packFor(LINUX, new java.util.Properties(), System.getProperties()));
            System.setProperty(prefix + "bytes", Long.toString(archive.bytes()));
            System.setProperty(prefix + "sha256", archive.sha256());
            var localOnly = FfmpegNativeCatalog.packFor(LINUX, new java.util.Properties(), System.getProperties());
            assertEquals(1, localOnly.archives().size());
            assertEquals(List.of(), localOnly.archives().get(0).urls());
            System.setProperty(prefix + "urls", "https://fixture.example.invalid/slim.zip");
            assertEquals(List.of("https://fixture.example.invalid/slim.zip"), FfmpegNativeCatalog.packFor(LINUX, new java.util.Properties(), System.getProperties()).archives().get(0).urls());
            System.setProperty(prefix + "urls", "http://fixture.example.invalid/slim.zip");
            assertThrows(IllegalArgumentException.class, () -> FfmpegNativeCatalog.packFor(LINUX, new java.util.Properties(), System.getProperties()));
        } finally { restore(prior); }
    }

    @Test void nativeRootAndAutoDownloadOverridesUseTestOwnedDirectoryWithoutTouchingConfig() {
        Map<String, String> prior = new HashMap<>();
        prior.put(FfmpegNativeAssets.PROP_NATIVE_ROOT, System.getProperty(FfmpegNativeAssets.PROP_NATIVE_ROOT));
        prior.put(FfmpegNativeAssets.PROP_AUTO_DOWNLOAD, System.getProperty(FfmpegNativeAssets.PROP_AUTO_DOWNLOAD));
        try {
            System.setProperty(FfmpegNativeAssets.PROP_NATIVE_ROOT, temp.resolve("owned").toString());
            System.setProperty(FfmpegNativeAssets.PROP_AUTO_DOWNLOAD, "false");
            assertEquals(temp.resolve("owned").resolve(FfmpegNativeCatalog.PINNED_VERSION), FfmpegNativeAssets.versionRoot());
            assertFalse(FfmpegNativeAssets.enabled());
            assertFalse(Files.exists(temp.resolve("owned")));
        } finally { restore(prior); }
    }

    @Test void declaredSlimDependencyIsMandatoryRatherThanSilentlyDropped() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        var archive = fixture.pack().archives().get(0);
        var pack = FfmpegNativeCatalog.slimPack(LINUX, archive.fileName(), archive.bytes(), archive.sha256(),
                List.of(), Set.of("libssl.so.3"));
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(pack),
                (a, p, progress) -> fail("Local dependency fixture cannot download"),
                fixture::archives, () -> true, System::nanoTime);
        assertTrue(assertThrows(IOException.class, () -> installer.ensureInstalled(LINUX)).getMessage().contains("Incomplete"));
        assertFalse(Files.exists(root().resolve(LINUX)));
    }

    private static List<String> mirrorUrls() {
        return List.of("https://mirror-a.example.invalid/ffmpeg/slim.zip",
                "https://mirror-b.example.invalid/ffmpeg/slim.zip",
                "https://mirror-c.example.invalid/ffmpeg/slim.zip");
    }

    private static java.util.Properties descriptor(Fixture fixture, List<String> urls) throws Exception {
        var archive = fixture.pack().archives().get(0);
        String prefix = fixture.pack().platform() + ".";
        String text = "# Public metadata only; fixture URLs never access a network.\n"
                + prefix + "fileName=" + archive.fileName() + "\n"
                + prefix + "bytes=" + archive.bytes() + "\n"
                + prefix + "sha256=" + archive.sha256() + "\n"
                + prefix + "urls=" + String.join(", ", urls) + "\n";
        return FfmpegNativeCatalog.readDescriptor(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void packagedDescriptorKeepsAllMirrorUrlsWithOnePinnedArchiveIdentity() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        var defaults = descriptor(fixture, mirrorUrls());
        var original = fixture.pack().archives().get(0);
        var pack = FfmpegNativeCatalog.packFor(LINUX, defaults, new java.util.Properties());
        assertEquals(1, pack.archives().size());
        var selected = pack.archives().get(0);
        assertEquals(original.fileName(), selected.fileName());
        assertEquals(original.bytes(), selected.bytes());
        assertEquals(original.sha256(), selected.sha256());
        assertEquals(mirrorUrls(), selected.urls());
        assertThrows(UnsupportedOperationException.class, () -> selected.urls().clear());
        defaults.setProperty(LINUX + ".urls", "https://later.example.invalid/slim.zip");
        assertEquals(mirrorUrls(), selected.urls(), "Parsed packs retain their complete mirror list");
    }

    @Test void explicitDeveloperOverridesReplaceOnlySpecifiedDescriptorFields() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        var defaults = descriptor(fixture, mirrorUrls());
        var overrides = new java.util.Properties();
        String prefix = "muonium.ffmpeg.slim." + LINUX + ".";
        overrides.setProperty(prefix + "fileName", "developer-slim.zip");
        var selected = FfmpegNativeCatalog.packFor(LINUX, defaults, overrides).archives().get(0);
        assertEquals("developer-slim.zip", selected.fileName());
        assertEquals(fixture.pack().archives().get(0).bytes(), selected.bytes());
        assertEquals(fixture.pack().archives().get(0).sha256(), selected.sha256());
        assertEquals(mirrorUrls(), selected.urls(), "Unspecified mirror lists inherit the packaged descriptor");
        var replacement = List.of("https://developer-a.example.invalid/slim.zip", "https://developer-b.example.invalid/slim.zip");
        overrides.setProperty(prefix + "urls", String.join(",", replacement));
        assertEquals(replacement, FfmpegNativeCatalog.packFor(LINUX, defaults, overrides).archives().get(0).urls());
        assertEquals(String.join(", ", mirrorUrls()), defaults.getProperty(LINUX + ".urls"));
        overrides.setProperty(prefix + "urls", "");
        assertTrue(FfmpegNativeCatalog.packFor(LINUX, defaults, overrides).archives().get(0).urls().isEmpty(),
                "An explicitly empty developer override selects local companion archives only");
    }

    @Test void absentOrUnregisteredDescriptorLeavesSlimPendingAndPartialDescriptorIsRejected() throws Exception {
        var missing = FfmpegNativeCatalog.readDescriptor(null);
        assertTrue(FfmpegNativeCatalog.packFor(LINUX, missing, new java.util.Properties()).archives().isEmpty());
        Fixture android = slimFixture(ANDROID);
        var defaults = descriptor(android, mirrorUrls());
        assertTrue(FfmpegNativeCatalog.packFor(LINUX, defaults, new java.util.Properties()).archives().isEmpty());
        defaults.setProperty(LINUX + ".fileName", "pending-slim.zip");
        var error = assertThrows(IllegalArgumentException.class,
                () -> FfmpegNativeCatalog.packFor(LINUX, defaults, new java.util.Properties()));
        assertTrue(error.getMessage().contains(FfmpegNativeCatalog.DESCRIPTOR_RESOURCE));
        assertTrue(error.getMessage().contains("fileName, bytes and sha256"));
    }

    @Test void bundledDescriptorSizeIsBounded() {
        assertThrows(IOException.class, () -> FfmpegNativeCatalog.readDescriptor(
                new java.io.ByteArrayInputStream(new byte[64 * 1024 + 1])));
    }

    @Test void descriptorMirrorsReachTransportIntactAndMirrorChangesReuseExistingVerifiedCache() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        var defaults = descriptor(fixture, mirrorUrls());
        var selected = FfmpegNativeCatalog.packFor(LINUX, defaults, new java.util.Properties());
        AtomicInteger calls = new AtomicInteger();
        var installer = new FfmpegNativeAssets.Installer(this::root, List.of(selected), (archive, target, progress) -> {
            calls.incrementAndGet();
            assertEquals(mirrorUrls(), archive.urls());
            assertEquals(fixture.pack().archives().get(0).bytes(), archive.bytes());
            assertEquals(fixture.pack().archives().get(0).sha256(), archive.sha256());
            Files.copy(fixture.archives().resolve(archive.fileName()), target);
        }, () -> null, () -> true, System::nanoTime);
        Path installed = installer.ensureInstalled(LINUX);
        Map<String, String> before = hashes(installed);
        assertEquals(1, calls.get());
        defaults.setProperty(LINUX + ".urls", "https://broken-a.example.invalid/slim.zip,https://broken-b.example.invalid/slim.zip");
        var changedMirrors = FfmpegNativeCatalog.packFor(LINUX, defaults, new java.util.Properties());
        var offline = new FfmpegNativeAssets.Installer(this::root, List.of(changedMirrors),
                (archive, target, progress) -> fail("A verified cache must not contact even a broken mirror"),
                () -> null, () -> true, System::nanoTime);
        assertEquals(installed, offline.ensureInstalled(LINUX));
        assertEquals(before, hashes(installed));
    }

    @Test void badMirrorContentsCannotReplacePreviousVerifiedSlimCache() throws Exception {
        Fixture fixture = slimFixture(LINUX);
        var first = new FfmpegNativeAssets.Installer(this::root, List.of(fixture.pack()),
                (archive, target, progress) -> fail("Initial cache uses the local fixture"),
                fixture::archives, () -> true, System::nanoTime);
        Path installed = first.ensureInstalled(LINUX);
        Map<String, String> before = hashes(installed);
        var defaults = descriptor(fixture, mirrorUrls());
        defaults.setProperty(LINUX + ".fileName", "replacement-slim.zip");
        var replacement = FfmpegNativeCatalog.packFor(LINUX, defaults, new java.util.Properties());
        var broken = new FfmpegNativeAssets.Installer(this::root, List.of(replacement), (archive, target, progress) -> {
            assertEquals(mirrorUrls(), archive.urls());
            byte[] corrupted = Files.readAllBytes(fixture.archives().resolve(fixture.pack().archives().get(0).fileName()));
            corrupted[corrupted.length / 2] ^= 1;
            assertEquals(archive.bytes(), corrupted.length);
            Files.write(target, corrupted);
        }, () -> null, () -> true, System::nanoTime);
        assertTrue(assertThrows(IOException.class, () -> broken.ensureInstalled(LINUX)).getMessage().contains("SHA-256"));
        assertEquals(FAILED, broken.snapshot().phase());
        assertEquals(before, hashes(installed));
        assertEquals(installed, first.ensureInstalled(LINUX));
    }

    private static void restore(Map<String, String> properties) {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }

    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) result.put(path.getFileName().toString(), hash(path));
        }
        return result;
    }
}
