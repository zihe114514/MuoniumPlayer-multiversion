package com.muoniumplayer.core.audio.ffmpeg;

import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.network.HttpDownloadRace;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs verified native archives before the first JavaCPP initialization. No native classes are referenced.
 * All blocking entry points belong to a preparation worker, never a client/render/audio callback.
 */
public final class FfmpegNativeAssets {
    public enum Phase { IDLE, DOWNLOADING, INSTALLING, READY, FAILED, UNSUPPORTED }
    public record Snapshot(Phase phase, int percent, String platform, String detail, Path directory) { }

    public static final String PROP_COMPANION_DIR = "muonium.ffmpeg.companionDir";
    public static final String PROP_NATIVE_ROOT = "muonium.ffmpeg.nativeRoot";
    public static final String PROP_AUTO_DOWNLOAD = "muonium.ffmpeg.autoDownload";
    static final String MANIFEST = "installed.tsv";
    static final long MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 512;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(20);

    private static final Installer DEFAULT = new Installer(FfmpegNativeAssets::versionRoot,
            FfmpegNativeCatalog.packs(), FfmpegNativeAssets::fetchRemote,
            () -> propertyPath(PROP_COMPANION_DIR), FfmpegNativeAssets::enabled, System::nanoTime, FfmpegNativeCatalog::packFor);

    /**
     * Blocking, idempotent preparation-worker operation. Only the supplied classifier is considered.
     * Returns a flat directory containing four FFmpeg libraries, their four JNI bindings, JavaCPP and dependencies.
     * The caller may now configure JavaCPP preloadpath/linkpath and initialize native classes without a restart.
     */
    public static Path ensureInstalled(String platform) throws IOException { return DEFAULT.ensureInstalled(platform); }
    /** Pure in-memory polling: no filesystem access, native initialization, or automatic retries. */
    public static Snapshot snapshot() { return DEFAULT.snapshot(); }
    /** Explicit UI retry of the last requested platform; bounded worker and queue, no JavaCPP initialization. */
    public static void retryAsync() { DEFAULT.retryAsync(); }

    private static Path propertyPath(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : Path.of(value.trim());
    }
    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP_AUTO_DOWNLOAD, "true").trim());
    }
    static Path versionRoot() {
        Path override = propertyPath(PROP_NATIVE_ROOT);
        Path root = override == null ? ConfigPaths.FFMPEG_NATIVE_ROOT.toPath() : override;
        return root.resolve(FfmpegNativeCatalog.PINNED_VERSION);
    }

    private static final class Workers {
        static final ThreadPoolExecutor RETRY = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread worker = new Thread(task, "MuoniumPlayer-FFmpegPreparation");
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        static final ScheduledThreadPoolExecutor DEADLINE = new ScheduledThreadPoolExecutor(1, task -> {
            Thread worker = new Thread(task, "MuoniumPlayer-FFmpegDownloadDeadline");
            worker.setDaemon(true);
            return worker;
        });
        static { DEADLINE.setRemoveOnCancelPolicy(true); }
    }

    private static void fetchRemote(FfmpegNativeCatalog.Archive archive, Path target,
                                    IntConsumer progress) throws IOException {
        if (archive.urls().isEmpty()) throw new IOException("No published slim FFmpeg source configured; use "
                + PROP_COMPANION_DIR + " or explicit muonium.ffmpeg.slim." + archive.platform() + ".urls");
        // HttpDownloadRace retries transient failures indefinitely; put a finite budget around this operation.
        Thread owner = Thread.currentThread();
        Object guard = new Object();
        boolean[] running = {true};
        var deadline = Workers.DEADLINE.schedule(() -> {
            synchronized (guard) { if (running[0]) owner.interrupt(); }
        }, 3, TimeUnit.MINUTES);
        try {
            HttpDownloadRace.fetch(archive.urls(), archive.bytes(), archive.sha256(), target.toFile(), progress);
        } finally {
            synchronized (guard) { running[0] = false; deadline.cancel(false); }
        }
    }

    @FunctionalInterface
    interface Transport {
        void fetch(FfmpegNativeCatalog.Archive archive, Path target, IntConsumer progress) throws IOException;
    }

    /** Instance seam keeps fixtures isolated from user configuration and production state. Root includes version. */
    static final class Installer {
        private final Supplier<Path> root;
        private final Map<String, FfmpegNativeCatalog.Pack> packs;
        private final java.util.function.Function<String, FfmpegNativeCatalog.Pack> resolver;
        private final Map<String, Slot> slots = new HashMap<>();
        private final Transport transport;
        private final Supplier<Path> companionDir;
        private final BooleanSupplier enabled;
        private final LongSupplier clock;
        private final AtomicBoolean retryQueued = new AtomicBoolean();
        private volatile Snapshot state = new Snapshot(Phase.IDLE, 0, null, null, null);

        Installer(Supplier<Path> root, List<FfmpegNativeCatalog.Pack> packs, Transport transport,
                  Supplier<Path> companionDir, BooleanSupplier enabled, LongSupplier clock) {
            this(root, packs, transport, companionDir, enabled, clock, null);
        }
        Installer(Supplier<Path> root, List<FfmpegNativeCatalog.Pack> packs, Transport transport,
                  Supplier<Path> companionDir, BooleanSupplier enabled, LongSupplier clock,
                  java.util.function.Function<String, FfmpegNativeCatalog.Pack> resolver) {
            this.root = root;
            Map<String, FfmpegNativeCatalog.Pack> indexed = new HashMap<>();
            for (var pack : packs) {
                if (indexed.put(pack.platform(), pack) != null) throw new IllegalArgumentException("Duplicate platform");
                slots.put(pack.platform(), new Slot());
            }
            this.packs = Map.copyOf(indexed);
            this.resolver = resolver == null ? this.packs::get : resolver;
            this.transport = transport;
            this.companionDir = companionDir;
            this.enabled = enabled;
            this.clock = clock;
        }
        Snapshot snapshot() { return state; }

        Path ensureInstalled(String platform) throws IOException {
            FfmpegNativeCatalog.Pack pack;
            try { pack = platform == null ? null : resolver.apply(platform); }
            catch (IllegalArgumentException invalid) {
                state = new Snapshot(Phase.FAILED, 0, platform, invalid.getMessage(), null);
                throw new IOException("Invalid slim FFmpeg metadata: " + invalid.getMessage(), invalid);
            }
            if (pack == null) {
                state = new Snapshot(Phase.UNSUPPORTED, 0, platform, "No pinned FFmpeg classifier for this platform", null);
                throw new IOException("Unsupported FFmpeg native platform: " + platform);
            }
            Slot slot = slots.get(platform);
            CompletableFuture<Path> flight;
            boolean owner;
            int attemptsBefore;
            synchronized (slot) {
                owner = slot.flight == null;
                if (owner && !identity(pack).equals(slot.identity)) {
                    slot.identity = identity(pack);
                    slot.attempts = 0;
                    slot.failure = null;
                    slot.nextAttempt = 0;
                }
                attemptsBefore = slot.attempts;
                if (owner) slot.flight = new CompletableFuture<>();
                flight = slot.flight;
            }
            if (!owner) return await(flight);
            try {
                checkCancelled();
                if (pack.archives().isEmpty()) throw new IOException("Slim FFmpeg descriptor metadata is not configured for "
                        + platform + "; missing " + FfmpegNativeCatalog.DESCRIPTOR_RESOURCE + " entry or explicit pinned development metadata");
                Path version = root.get().toAbsolutePath().normalize();
                ensureDirectory(version);
                Path directory = version.resolve(platform);
                // Also serialize separate game processes using the same installation root.
                Path lockPath = version.resolve("." + platform + ".lock");
                try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = channel.lock()) {
                    Path result = install(pack, slot, flight, version, directory);
                    state = new Snapshot(Phase.READY, 100, platform, null, result);
                    synchronized (slot) { slot.attempts = 0; slot.failure = null; }
                    flight.complete(result);
                    return result;
                }
            } catch (IOException | RuntimeException failure) {
                IOException error = failure instanceof IOException io ? io : new IOException("Cannot prepare FFmpeg natives", failure);
                synchronized (slot) {
                    if (slot.failure == null || slot.attempts > attemptsBefore) {
                        slot.failure = error;
                        slot.nextAttempt = clock.getAsLong() + RETRY_NANOS;
                    }
                }
                Snapshot old = state;
                state = new Snapshot(Phase.FAILED, 0, platform, error.getMessage(),
                        platform.equals(old.platform()) ? old.directory() : null);
                flight.completeExceptionally(error);
                throw error;
            } catch (Error fatal) {
                flight.completeExceptionally(fatal);
                throw fatal;
            } finally {
                synchronized (slot) { if (slot.flight == flight) slot.flight = null; }
            }
        }

        private Path install(FfmpegNativeCatalog.Pack pack, Slot slot, CompletableFuture<Path> flight,
                             Path version, Path directory) throws IOException {
            Path previous = version.resolve(".previous-" + pack.platform());
            // Recover a crash between the two directory renames. Never load hidden staging/backup trees.
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                rejectLink(previous);
                atomicMove(previous, directory);
            }
            if (complete(directory, pack)) return directory;
            checkCancelled();
            if (!enabled.getAsBoolean()) throw new IOException("FFmpeg native auto-download is disabled (" + PROP_AUTO_DOWNLOAD + "=false)");
            synchronized (slot) {
                if (slot.failure != null && (slot.attempts >= MAX_ATTEMPTS || clock.getAsLong() < slot.nextAttempt)) {
                    throw new IOException("FFmpeg native retry is cooling down or exhausted; use retryAsync()", slot.failure);
                }
                slot.attempts++;
            }
            state = new Snapshot(Phase.DOWNLOADING, 0, pack.platform(), null, directory);
            Path work = Files.createTempDirectory(version, ".incoming-" + pack.platform() + "-");
            try {
                Path staged = Files.createDirectory(work.resolve("installed"));
                List<Path> archives = new ArrayList<>();
                long total = pack.archives().stream().mapToLong(FfmpegNativeCatalog.Archive::bytes).sum();
                long done = 0;
                Path local = companionDir.get();
                for (var archive : pack.archives()) {
                    checkCancelled();
                    Path target = work.resolve(archive.fileName());
                    if (local != null) {
                        Path source = local.resolve(archive.fileName());
                        // Exact original filename only, no platform scanning and no remote fallback for a bad local source.
                        verifyArchive(source, archive);
                        copyBounded(source, target, archive.bytes());
                    } else {
                        long completed = done;
                        transport.fetch(archive, target, percent -> {
                            synchronized (slot) {
                                if (slot.flight != flight || flight.isDone()) return;
                                Snapshot old = state;
                                if (old.phase() != Phase.DOWNLOADING || !pack.platform().equals(old.platform())) return;
                                int value = (int) ((completed + archive.bytes() * Math.max(0, Math.min(100, percent)) / 100) * 90 / total);
                                state = new Snapshot(Phase.DOWNLOADING, Math.max(old.percent(), value), pack.platform(), null, directory);
                            }
                        });
                    }
                    verifyArchive(target, archive);
                    archives.add(target);
                    done += archive.bytes();
                }
                checkCancelled();
                state = new Snapshot(Phase.INSTALLING, 90, pack.platform(), null, directory);
                Set<String> extracted = new HashSet<>();
                long[] expanded = {0};
                for (int i = 0; i < archives.size(); i++) {
                    extract(archives.get(i), pack, pack.archives().get(i), staged, extracted, expanded);
                }
                if (!extracted.equals(pack.files())) throw new IOException("Incomplete FFmpeg native release");
                writeManifest(staged, pack);
                if (!complete(staged, pack)) throw new IOException("FFmpeg staging verification failed");
                checkCancelled();
                // No target mutation before both archives and the complete staged install have been verified.
                if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) deleteTree(previous);
                boolean movedOld = false;
                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    rejectLink(directory);
                    atomicMove(directory, previous);
                    movedOld = true;
                }
                try {
                    atomicMove(staged, directory);
                } catch (IOException failure) {
                    if (movedOld) {
                        try { atomicMove(previous, directory); }
                        catch (IOException restore) { failure.addSuppressed(restore); }
                    }
                    throw failure;
                }
                deleteQuietly(previous);
                return directory;
            } finally {
                deleteQuietly(work);
            }
        }

        void retryAsync() {
            String platform = state.platform();
            Slot slot = platform == null ? null : slots.get(platform);
            if (slot == null || !retryQueued.compareAndSet(false, true)) return;
            synchronized (slot) {
                if (slot.flight != null) { retryQueued.set(false); return; }
                slot.attempts = 0;
                slot.failure = null;
                slot.nextAttempt = 0;
            }
            try {
                Workers.RETRY.execute(() -> {
                    try { ensureInstalled(platform); }
                    catch (IOException ignored) { /* ensureInstalled already published the failure. */ }
                    finally { retryQueued.set(false); }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                retryQueued.set(false);
            }
        }
    }

    private static final class Slot {
        CompletableFuture<Path> flight;
        String identity;
        int attempts;
        long nextAttempt;
        IOException failure;
    }

    private static Path await(CompletableFuture<Path> flight) throws IOException {
        try { return flight.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for FFmpeg preparation");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException("FFmpeg preparation failed", failed.getCause());
        }
    }

    private static void extract(Path source, FfmpegNativeCatalog.Pack pack, FfmpegNativeCatalog.Archive archive,
                                Path staged, Set<String> extracted, long[] expanded) throws IOException {
        Set<String> names = new HashSet<>();
        Set<String> wanted = pack.files(archive);
        try (ZipFile zip = new ZipFile(source.toFile())) {
            if (zip.size() > MAX_ZIP_ENTRIES) throw new IOException("Too many native archive entries");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled();
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                safeZipPath(path);
                if (!names.add(path)) throw new IOException("Duplicate ZIP path: " + path);
                if (entry.isDirectory()) continue;
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size < 0 || size > MAX_ENTRY_BYTES || compressed < 0
                        || (size > 1024 * 1024 && size > Math.max(1, compressed) * 200)
                        || size > MAX_EXPANDED_BYTES - expanded[0]) {
                    throw new IOException("Native archive expansion limit exceeded");
                }
                expanded[0] += size;
                if (!path.startsWith(archive.nativePrefix())) continue;
                String name = path.substring(archive.nativePrefix().length());
                if (!wanted.contains(name)) continue;
                if (!extracted.add(name)) throw new IOException("Duplicate native library: " + name);
                Path target = staged.resolve(name);
                if (!target.getParent().equals(staged)) throw new IOException("Native library escaped staging");
                CRC32 crc = new CRC32();
                long written = 0;
                try (InputStream input = zip.getInputStream(entry);
                     var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        written += count;
                        if (written > size || written > MAX_ENTRY_BYTES) throw new IOException("Native ZIP entry grew past declared size");
                        output.write(buffer, 0, count);
                        crc.update(buffer, 0, count);
                    }
                }
                if (written != size || written == 0 || crc.getValue() != entry.getCrc()) throw new IOException("Corrupt native ZIP entry: " + name);
            }
        }
    }

    private static void safeZipPath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 512 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) {
            throw new IOException("Unsafe native ZIP path");
        }
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(".") || parts[i].equals("..") || (parts[i].isEmpty() && i != parts.length - 1)) {
                throw new IOException("Unsafe native ZIP path: " + path);
            }
        }
    }

    private static void verifyArchive(Path path, FfmpegNativeCatalog.Archive archive) throws IOException {
        rejectLink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != archive.bytes()) {
            throw new IOException("Native archive length mismatch: " + archive.fileName());
        }
        if (!hash(path).equals(archive.sha256())) throw new IOException("Native archive SHA-256 mismatch: " + archive.fileName());
    }

    private static void copyBounded(Path source, Path target, long expected) throws IOException {
        try (InputStream input = Files.newInputStream(source);
             var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled();
                written += count;
                if (written > expected) throw new IOException("Local native archive changed during copy");
                output.write(buffer, 0, count);
            }
            if (written != expected) throw new IOException("Local native archive truncated during copy");
        }
    }

    private static String identity(FfmpegNativeCatalog.Pack pack) {
        StringBuilder text = new StringBuilder("muonium-ffmpeg-native-v1\t").append(pack.platform());
        for (var archive : pack.archives()) text.append('\t').append(archive.fileName()).append(':')
                .append(archive.bytes()).append(':').append(archive.sha256());
        text.append('\t').append(String.join(",", pack.files().stream().sorted().toList()));
        return text.toString();
    }

    private static void writeManifest(Path directory, FfmpegNativeCatalog.Pack pack) throws IOException {
        StringBuilder text = new StringBuilder(identity(pack)).append('\n');
        for (String name : pack.files().stream().sorted().toList()) {
            Path file = directory.resolve(name);
            text.append(name).append('\t').append(Files.size(file)).append('\t').append(hash(file)).append('\n');
        }
        Files.writeString(directory.resolve(MANIFEST), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static boolean complete(Path directory, FfmpegNativeCatalog.Pack pack) throws IOException {
        try {
            rejectLink(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
            Path manifest = directory.resolve(MANIFEST);
            rejectLink(manifest);
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > MAX_MANIFEST_BYTES) return false;
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(identity(pack))) return false;
            Set<String> seen = new HashSet<>();
            long total = 0;
            Set<String> expected = pack.files();
            for (int i = 1; i < lines.size(); i++) {
                checkCancelled();
                String[] fields = lines.get(i).split("\t", -1);
                if (fields.length != 3 || !expected.contains(fields[0]) || !seen.add(fields[0])
                        || !fields[2].matches("[0-9a-f]{64}")) return false;
                long size = Long.parseLong(fields[1]);
                if (size <= 0 || size > MAX_ENTRY_BYTES || size > MAX_EXPANDED_BYTES - total) return false;
                total += size;
                Path file = directory.resolve(fields[0]);
                rejectLink(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != size || !hash(file).equals(fields[2])) return false;
            }
            if (!seen.equals(expected)) return false;
            Set<String> actual = new HashSet<>();
            try (var children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    if (actual.size() > expected.size()) return false;
                    actual.add(child.getFileName().toString());
                }
            }
            seen.add(MANIFEST);
            return actual.equals(seen);
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException | IllegalArgumentException corrupt) {
            return false;
        }
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            long done = 0;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled();
                done += count;
                if (done > MAX_ENTRY_BYTES) throw new IOException("Native file exceeds verification limit");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("FFmpeg native preparation cancelled");
    }
    private static void ensureDirectory(Path directory) throws IOException {
        // Refuse links in the owned root and its existing ancestors; no staging writes through symlinks.
        for (Path cursor = directory; cursor != null; cursor = cursor.getParent()) rejectLink(cursor);
        Files.createDirectories(directory);
    }
    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Native install path is a symbolic link: " + path);
    }
    private static void atomicMove(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }
    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        // walk does not follow symbolic links. All callers pass a fixed backup or our own temporary directory.
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(folder);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    private static void deleteQuietly(Path path) {
        try { deleteTree(path); }
        catch (IOException ignored) { /* Hidden leftovers are never used as native candidates. */ }
    }
    private FfmpegNativeAssets() { }
}
