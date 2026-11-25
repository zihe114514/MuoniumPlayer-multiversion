package com.muoniumplayer.configskija.avatar;

import com.muoniumplayer.configskija.host.avatar.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import javax.imageio.ImageIO;

/** Deterministic worker/client simulation. No actual player UUID, game, network, native loader or GL. */
public final class AvatarLoaderTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final UUID A = UUID.fromString("11111111-2222-4333-8444-555555555555"),
            B = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

    private static AvatarPixels pixels(int face, int hat) {
        int[] a = new int[64], b = new int[64];
        Arrays.fill(a, face);
        Arrays.fill(b, hat);
        return new AvatarPixels(a, b);
    }

    private static AvatarLoader.Result result(CompletableFuture<AvatarLoader.Result> future)
            throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        pixels();
        cache();
        deduplicate();
        profileFallback();
        web();
        timeouts();
        cancellation();
        accountChange();
        lateIdentity();
        rejectWorker();
        System.out.println("AvatarLoaderTest: " + checks + " assertions passed");
    }

    private static void pixels() throws Exception {
        check(AvatarPixels.fromAbgr(0xFF112233) == 0xFF332211, "old ABGR normalized to ARGB");
        check(
                AvatarPixels.composite(0x00FF0000, 0xFF123456) == 0xFF123456,
                "transparent hat leaves face");
        check(
                AvatarPixels.composite(0xFF336699, 0xFF001122) == 0xFF336699,
                "opaque hat replaces face");
        check(
                AvatarPixels.composite(0x80FF0000, 0xFF0000FF) == 0xFF80007F,
                "half alpha source-over truncation");
        check(AvatarPixels.composite(0x8000FF00, 0x00000000) == 0x8000FF00, "alpha over clear");
        int[] face = new int[64], hat = new int[64];
        for (int i = 0; i < 64; i++) face[i] = 0xFF000000 | i;
        AvatarPixels detached = new AvatarPixels(face, hat);
        Arrays.fill(face, 0);
        int[] head = detached.headArgb();
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
                check(
                        head[y * 32 + x] == (0xFF000000 | (y / 4) * 8 + x / 4),
                        "nearest 4x face placement");
        byte[] png = AvatarPng.encode(detached);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        try {
            check(decoded.getWidth() == 32 && decoded.getHeight() == 32, "32px PNG");
            check(decoded.getRGB(31, 31) == 0xFF00003F, "PNG channel/scale round trip");
        } finally {
            decoded.flush();
        }
        check(Arrays.equals(png, AvatarPng.validate(png)), "valid web PNG");
        boolean rejected = false;
        try {
            AvatarPng.validate(new byte[20]);
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "non-PNG rejected");
        for (int src : new int[] {0, 0x8000FF00, 0x40FF1201, 0xFFEEDDCC})
            for (int dst : new int[] {0, 0xFF123456, 0x400020FF}) {
                int expected = reference(src, dst);
                check(
                        AvatarPixels.composite(src, dst) == expected,
                        "original per-channel integer composition");
            }
    }

    private static int reference(int s, int d) {
        int sa = s >>> 24;
        if (sa == 0) return d;
        if (sa == 255) return s;
        int da = d >>> 24;
        if (da == 0) return s;
        int a = sa + da * (255 - sa) / 255;
        int r = ((s >> 16 & 255) * sa + (d >> 16 & 255) * da * (255 - sa) / 255) / a,
                g = ((s >> 8 & 255) * sa + (d >> 8 & 255) * da * (255 - sa) / 255) / a,
                b = ((s & 255) * sa + (d & 255) * da * (255 - sa) / 255) / a;
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static void cache() throws Exception {
        try (Harness h = new Harness()) {
            h.access.local = pixels(0xFF123456, 0);
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.CACHED_SKIN_ONLY)) {
                check(
                        loader.loadCached() == null && loader.profile() == null,
                        "cold reads nonblocking and empty");
                var first = result(loader.request());
                check(
                        first.ready() && first.source() == AvatarLoader.Source.LOCAL_SKIN,
                        "real local pixels win");
                check(
                        h.webCalls.get() == 0 && h.access.resolveCalls.get() == 0,
                        "cache-only makes no explicit resolution or web request");
                check(loader.profile().id().equals(A), "UUID snapshot published");
                byte[] bytes = loader.loadCached();
                check(
                        bytes == first.png() && loader.loadCached(A) == bytes,
                        "stable bytes for painter cache identity");
                var again = result(loader.request());
                check(
                        again.source() == AvatarLoader.Source.CACHE && again.png() == bytes,
                        "resident hit");
                check(h.access.localCalls.get() == 1, "cache hit skips texture copy");
                loader.invalidate();
                check(loader.loadCached() == null, "reload clears published PNG");
                check(result(loader.request()).ready(), "reload obtains skin again");
            }
        }
    }

    private static void deduplicate() throws Exception {
        try (Harness h = new Harness()) {
            h.access.resolved = new CompletableFuture<>();
            try (AvatarLoader loader =
                    new AvatarLoader(
                            h.access,
                            h.worker,
                            AvatarLoader.Mode.ORIGINAL_FALLBACK,
                            (id, cancelled) -> {
                                throw new AssertionError("No web needed");
                            },
                            new AvatarLoader.Timing(500, 1000))) {
                var first = loader.request();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (h.access.resolveCalls.get() == 0 && System.nanoTime() < until)
                    Thread.onSpinWait();
                var second = loader.request();
                h.access.resolved.complete(() -> pixels(0xFF102030, 0));
                var a = result(first);
                var b = result(second);
                check(
                        a.ready() && b.ready() && a.png() == b.png(),
                        "in-flight callers share stable UUID cache result");
                check(
                        h.access.resolveCalls.get() == 1,
                        "duplicate request does not repeat profile skin fetch");
            }
        }
    }

    private static void profileFallback() throws Exception {
        try (Harness h = new Harness()) {
            h.access.resolved =
                    CompletableFuture.completedFuture(() -> pixels(0xFFABCDEF, 0x60000000));
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.ORIGINAL_FALLBACK)) {
                var loaded = result(loader.request());
                check(
                        loaded.ready() && loaded.source() == AvatarLoader.Source.PROFILE_SKIN,
                        "profile result copied on client then encoded by worker");
                check(h.webCalls.get() == 0, "readable profile skin prevents web fallback");
                check(h.access.resolveCalls.get() == 1, "one profile resolve");
                check(h.access.wrongThread.get() == 0, "all access on client");
            }
        }
    }

    private static void web() throws Exception {
        try (Harness h = new Harness()) {
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.ORIGINAL_FALLBACK)) {
                var loaded = result(loader.request());
                check(
                        loaded.ready() && loaded.source() == AvatarLoader.Source.MC_HEADS,
                        "web only after unreadable game skin");
                check(
                        h.webCalls.get() == 1 && h.webId.get().equals(A),
                        "only exact current UUID passed to web");
                check(!h.webThread.get().equals(h.access.clientThread.get()), "network on worker");
                check(
                        AvatarWeb.uri(A)
                                .toString()
                                .equals(
                                        "https://mc-heads.net/avatar/11111111222243338444555555555555/32.png"),
                        "original UUID endpoint");
                check(
                        AvatarWeb.CONNECT_TIMEOUT_MS == 5000 && AvatarWeb.READ_TIMEOUT_MS == 5000,
                        "original network deadlines");
                check(
                        AvatarLoader.Timing.original().clientMillis() == 5000
                                && AvatarLoader.Timing.original().skinMillis() == 2000,
                        "original client/profile deadlines");
            }
        }
        try (Harness h = new Harness()) {
            h.access.id.set(null);
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.ORIGINAL_FALLBACK)) {
                check(
                        result(loader.request()).status() == AvatarLoader.Status.NO_PROFILE,
                        "no UUID is not a network lookup");
                check(h.webCalls.get() == 0, "no request without UUID");
            }
        }
    }

    private static void timeouts() throws Exception {
        try (Harness h = new Harness()) {
            h.access.resolved = new CompletableFuture<>();
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.ORIGINAL_FALLBACK)) {
                var r = result(loader.request());
                check(
                        r.ready() && r.source() == AvatarLoader.Source.MC_HEADS,
                        "2s phase timeout proceeds to web");
                check(
                        r.diagnostics().stream().anyMatch(s -> s.contains("TimeoutException")),
                        "profile timeout retained as evidence");
                check(!h.access.resolved.isCancelled(), "shared skin future never cancelled");
            }
        }
        try (Harness h = new Harness()) {
            h.access.delayLocal = true;
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.CACHED_SKIN_ONLY)) {
                var r = result(loader.request());
                check(
                        r.status() == AvatarLoader.Status.NO_READABLE_SKIN,
                        "client pixel timeout explicit");
                check(
                        r.diagnostics().stream().anyMatch(s -> s.contains("TimeoutException")),
                        "client timeout evidence");
                h.access.releaseLocal();
                check(loader.loadCached() == null, "late detached pixels cannot publish");
            }
        }
    }

    private static void cancellation() throws Exception {
        try (Harness h = new Harness()) {
            h.access.entered = new CountDownLatch(1);
            h.access.release = new CountDownLatch(1);
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.CACHED_SKIN_ONLY)) {
                var pending = loader.request();
                check(
                        h.access.entered.await(1, TimeUnit.SECONDS),
                        "native-read simulation reached client");
                loader.close();
                h.access.release.countDown();
                check(
                        result(pending).status() == AvatarLoader.Status.CANCELLED,
                        "closing cancels generation");
                check(loader.loadCached(A) == null, "no late publish after exit");
                check(
                        result(loader.request()).status() == AvatarLoader.Status.CANCELLED,
                        "closed loader rejects work");
                loader.close();
                check(!h.worker.isShutdown(), "caller executor ownership retained");
            }
        }
    }

    private static void accountChange() throws Exception {
        try (Harness h = new Harness()) {
            h.access.resolved = new CompletableFuture<>();
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.ORIGINAL_FALLBACK)) {
                var old = loader.request();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (h.access.resolveCalls.get() == 0 && System.nanoTime() < until)
                    Thread.onSpinWait();
                h.access.id.set(B);
                h.access.local = pixels(0xFF556677, 0);
                var next = result(loader.request());
                check(
                        next.ready() && next.profile().id().equals(B),
                        "account switch publishes current account");
                check(
                        result(old).status() == AvatarLoader.Status.CANCELLED,
                        "older generation cancelled");
                check(
                        loader.loadCached(A) == null && loader.loadCached(B) != null,
                        "UUID isolation");
            }
        }
    }

    private static void lateIdentity() throws Exception {
        try (Harness h = new Harness()) {
            h.access.delayIdentity = true;
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.CACHED_SKIN_ONLY)) {
                var pending = loader.request();
                loader.cancel();
                check(
                        result(pending).status() == AvatarLoader.Status.CANCELLED
                                || pending.isCancelled(),
                        "identity capture cancellation settled");
                h.access.releaseIdentity();
                check(
                        loader.loadCached() == null,
                        "late identity cannot restart closed generation");
            }
        }
    }

    private static void rejectWorker() throws Exception {
        try (Harness h = new Harness()) {
            h.worker.shutdown();
            try (AvatarLoader loader = h.loader(AvatarLoader.Mode.CACHED_SKIN_ONLY)) {
                check(
                        result(loader.request()).status() == AvatarLoader.Status.FAILED,
                        "rejected executor is visible");
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        final ExecutorService worker = Executors.newFixedThreadPool(2),
                client = Executors.newSingleThreadExecutor();
        final Fake access = new Fake(client);
        final AtomicInteger webCalls = new AtomicInteger();
        final AtomicReference<UUID> webId = new AtomicReference<>();
        final AtomicReference<Thread> webThread = new AtomicReference<>();

        AvatarLoader loader(AvatarLoader.Mode mode) {
            return new AvatarLoader(
                    access,
                    worker,
                    mode,
                    (id, cancelled) -> {
                        webCalls.incrementAndGet();
                        webId.set(id);
                        webThread.set(Thread.currentThread());
                        return AvatarPng.encode(pixels(0xFF993311, 0));
                    },
                    new AvatarLoader.Timing(200, 60));
        }

        public void close() throws Exception {
            if (access.release != null) access.release.countDown();
            access.releaseLocal();
            access.releaseIdentity();
            worker.shutdownNow();
            client.shutdownNow();
            worker.awaitTermination(1, TimeUnit.SECONDS);
            client.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private static final class Fake implements AvatarAccess {
        final ExecutorService client;
        final AtomicReference<Thread> clientThread = new AtomicReference<>();
        final AtomicReference<UUID> id = new AtomicReference<>(A);
        final AtomicInteger localCalls = new AtomicInteger(),
                resolveCalls = new AtomicInteger(),
                wrongThread = new AtomicInteger();
        volatile AvatarPixels local;
        volatile CompletableFuture<Supplier<AvatarPixels>> resolved =
                CompletableFuture.completedFuture(null);
        volatile boolean delayLocal, delayIdentity;
        volatile CountDownLatch entered, release;
        final List<Runnable> localLate = new CopyOnWriteArrayList<>(),
                identityLate = new CopyOnWriteArrayList<>();

        Fake(ExecutorService client) {
            this.client = client;
        }

        public boolean isClientThread() {
            return Thread.currentThread() == clientThread.get();
        }

        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            CompletableFuture<T> f = new CompletableFuture<>();
            Runnable run =
                    () -> {
                        clientThread.set(Thread.currentThread());
                        try {
                            f.complete(task.get());
                        } catch (Throwable error) {
                            f.completeExceptionally(error);
                        }
                    };
            if (delayIdentity) {
                identityLate.add(run);
                return f;
            }
            if (delayLocal && localCalls.get() == 0 && id.get() != null) {
                if (clientThread.get() != null) {
                    localLate.add(run);
                    return f;
                }
            }
            client.execute(run);
            return f;
        }

        private void check() {
            if (!isClientThread()) {
                wrongThread.incrementAndGet();
                throw new AssertionError("not client");
            }
        }

        public AvatarLoader.Profile profile() {
            check();
            UUID value = id.get();
            return value == null ? null : new AvatarLoader.Profile(value);
        }

        public AvatarPixels local(AvatarLoader.Profile expected) {
            check();
            localCalls.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            return local;
        }

        public CompletableFuture<Supplier<AvatarPixels>> resolve(AvatarLoader.Profile expected) {
            check();
            resolveCalls.incrementAndGet();
            return resolved;
        }

        void releaseLocal() {
            delayLocal = false;
            for (Runnable r : localLate) client.execute(r);
            localLate.clear();
        }

        void releaseIdentity() {
            delayIdentity = false;
            for (Runnable r : identityLate) client.execute(r);
            identityLate.clear();
        }
    }
}
