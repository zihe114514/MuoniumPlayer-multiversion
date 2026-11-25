package com.muoniumplayer.configskija.host.avatar;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Resident UUID cache. The UI only reads published bytes; client pixel reads and worker encoding are separate. */
public final class AvatarLoader implements AutoCloseable {
    public enum Mode {
        CACHED_SKIN_ONLY,
        ORIGINAL_FALLBACK
    }

    public enum Status {
        READY,
        NO_PROFILE,
        NO_READABLE_SKIN,
        FAILED,
        CANCELLED
    }

    public enum Source {
        CACHE,
        LOCAL_SKIN,
        PROFILE_SKIN,
        MC_HEADS,
        NONE
    }

    public record Profile(UUID id) {
        public Profile {
            Objects.requireNonNull(id);
        }
    }

    /** PNG arrays are stable read-only cache values: do not mutate them or clone them per frame. */
    public record Result(
            Profile profile, Status status, Source source, byte[] png, List<String> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean ready() {
            return status == Status.READY && png != null;
        }
    }

    public record Timing(long clientMillis, long skinMillis) {
        public Timing {
            if (clientMillis <= 0 || skinMillis <= 0)
                throw new IllegalArgumentException("Positive avatar deadlines required");
        }

        public static Timing original() {
            return new Timing(5000, 2000);
        }
    }

    @FunctionalInterface
    public interface Web {
        byte[] fetch(UUID id, BooleanSupplier cancelled) throws Exception;
    }

    private record Cached(byte[] png, Source source) {}

    private static final int CACHE_LIMIT = 64;
    private final Object lock = new Object();
    private final AvatarAccess access;
    private final ExecutorService worker;
    private final Mode mode;
    private final Web web;
    private final Timing timing;
    private final LinkedHashMap<UUID, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final Set<CompletableFuture<?>> identityTasks = new HashSet<>();
    private volatile Profile profile;
    private volatile Result last =
            new Result(null, Status.NO_PROFILE, Source.NONE, null, List.of());
    private volatile boolean closed;
    private volatile long generation;
    private Job active;

    private static final class Job {
        final Profile profile;
        final long generation;
        final CompletableFuture<Result> result = new CompletableFuture<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Future<?> running;
        volatile CompletableFuture<?> clientTask;

        Job(Profile profile, long generation) {
            this.profile = profile;
            this.generation = generation;
        }
    }

    public static AvatarLoader create(ExecutorService worker) {
        return create(worker, Mode.CACHED_SKIN_ONLY);
    }

    public static AvatarLoader create(ExecutorService worker, Mode mode) {
        return new AvatarLoader(
                new AvatarSkinAccess(), worker, mode, new AvatarWeb(), Timing.original());
    }

    /** Test/host seam; the caller owns the worker, and close never shuts it down. */
    public AvatarLoader(
            AvatarAccess access, ExecutorService worker, Mode mode, Web web, Timing timing) {
        this.access = Objects.requireNonNull(access);
        this.worker = Objects.requireNonNull(worker);
        this.mode = Objects.requireNonNull(mode);
        this.web = Objects.requireNonNull(web);
        this.timing = Objects.requireNonNull(timing);
    }

    /** Most recently observed local account, never authlib data. Non-blocking on any thread. */
    public Profile profile() {
        return profile;
    }

    public Result lastResult() {
        return last;
    }

    public byte[] loadCached() {
        Profile p = profile;
        return p == null ? null : loadCached(p.id);
    }

    public byte[] loadCached(UUID id) {
        synchronized (lock) {
            if (closed || id == null) return null;
            Cached value = cache.get(id);
            return value == null ? null : value.png;
        }
    }

    /** Schedules an identity capture; never waits on the client/render thread. */
    public CompletableFuture<Result> request() {
        CompletableFuture<Profile> identity;
        synchronized (lock) {
            if (closed)
                return CompletableFuture.completedFuture(
                        cancelled(profile, "Avatar loader is closed"));
            try {
                identity = access.submit(() -> closed ? null : access.profile());
            } catch (RuntimeException error) {
                return CompletableFuture.completedFuture(failure(profile, error));
            }
            identityTasks.add(identity);
        }
        identity.orTimeout(timing.clientMillis, TimeUnit.MILLISECONDS);
        return identity.handle(
                        (p, error) -> {
                            synchronized (lock) {
                                identityTasks.remove(identity);
                            }
                            if (error != null)
                                return CompletableFuture.completedFuture(
                                        closed || identity.isCancelled()
                                                ? cancelled(p, "Identity capture cancelled")
                                                : failure(p, error));
                            return begin(p);
                        })
                .thenCompose(future -> future);
    }

    private CompletableFuture<Result> begin(Profile selected) {
        synchronized (lock) {
            if (closed)
                return CompletableFuture.completedFuture(
                        cancelled(selected, "Avatar loader is closed"));
            if (!Objects.equals(profile, selected)) {
                generation++;
                cancelActive("Local profile changed");
                profile = selected;
            }
            if (selected == null) {
                last = new Result(null, Status.NO_PROFILE, Source.NONE, null, List.of());
                return CompletableFuture.completedFuture(last);
            }
            Cached cached = cache.get(selected.id);
            if (cached != null) {
                last = new Result(selected, Status.READY, Source.CACHE, cached.png, List.of());
                return CompletableFuture.completedFuture(last);
            }
            if (active != null && !active.cancelled.get() && active.profile.equals(selected))
                return active.result;
            Job job = new Job(selected, generation);
            active = job;
            try {
                job.running = worker.submit(() -> run(job));
            } catch (RejectedExecutionException failure) {
                active = null;
                job.result.complete(failure(selected, failure));
            }
            return job.result;
        }
    }

    private void run(Job job) {
        List<String> diagnostics = new ArrayList<>();
        AvatarPixels pixels = null;
        Source source = Source.NONE;
        byte[] png = null;
        try {
            if (access.isClientThread())
                throw new IllegalStateException("Avatar worker must not run on the client thread");
            check(job);
            pixels = tryClient(job, () -> access.local(job.profile), diagnostics, "local skin");
            if (pixels != null) source = Source.LOCAL_SKIN;
            if (pixels == null && mode == Mode.ORIGINAL_FALLBACK) {
                CompletableFuture<Supplier<AvatarPixels>> skin =
                        tryClient(
                                job,
                                () -> access.resolve(job.profile),
                                diagnostics,
                                "profile skin start");
                if (skin != null) {
                    Supplier<AvatarPixels> read = null;
                    try {
                        read = skin.get(timing.skinMillis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    } catch (ExecutionException | TimeoutException failed) {
                        diagnostics.add("profile skin: " + describe(failed));
                    }
                    check(job);
                    if (read != null) {
                        Supplier<AvatarPixels> task = read;
                        pixels = tryClient(job, task, diagnostics, "profile skin pixels");
                        if (pixels != null) source = Source.PROFILE_SKIN;
                    }
                }
            }
            check(job);
            if (pixels != null) png = AvatarPng.encode(pixels);
            else if (mode == Mode.ORIGINAL_FALLBACK && current(job)) {
                png =
                        AvatarPng.validate(
                                web.fetch(
                                        job.profile.id,
                                        () ->
                                                !valid(job)
                                                        || Thread.currentThread().isInterrupted()));
                source = Source.MC_HEADS;
            }
            check(job);
            if (png != null && !current(job)) {
                finish(job, cancelled(job.profile, "Local profile changed before publishing"));
                return;
            }
            Result result =
                    new Result(
                            job.profile,
                            png == null ? Status.NO_READABLE_SKIN : Status.READY,
                            png == null ? Source.NONE : source,
                            png,
                            diagnostics);
            synchronized (lock) {
                if (valid(job) && png != null) {
                    cache.put(job.profile.id, new Cached(png, source));
                    while (cache.size() > CACHE_LIMIT) {
                        Iterator<UUID> keys = cache.keySet().iterator();
                        keys.next();
                        keys.remove();
                    }
                }
            }
            finish(job, result);
        } catch (InterruptedException | CancellationException error) {
            Thread.currentThread().interrupt();
            finish(job, cancelled(job.profile, "Avatar request cancelled"));
        } catch (Exception error) {
            diagnostics.add(describe(error));
            finish(job, new Result(job.profile, Status.FAILED, Source.NONE, null, diagnostics));
        }
    }

    private boolean current(Job job) throws InterruptedException {
        Profile actual = tryClient(job, access::profile, new ArrayList<>(), "profile check");
        return job.profile.equals(actual) && valid(job);
    }

    private <T> T tryClient(Job job, Supplier<T> read, List<String> diagnostics, String stage)
            throws InterruptedException {
        check(job);
        CompletableFuture<T> future = access.submit(() -> valid(job) ? read.get() : null);
        job.clientTask = future;
        try {
            return future.get(timing.clientMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            future.cancel(false);
            throw interrupted;
        } catch (ExecutionException | TimeoutException failed) {
            future.cancel(false);
            diagnostics.add(stage + ": " + describe(failed));
            return null;
        } catch (CancellationException cancelled) {
            check(job);
            diagnostics.add(stage + ": cancelled");
            return null;
        } finally {
            if (job.clientTask == future) job.clientTask = null;
        }
    }

    private boolean valid(Job job) {
        return !closed
                && !job.cancelled.get()
                && !job.result.isDone()
                && job.generation == generation
                && job.profile.equals(profile);
    }

    private void check(Job job) {
        if (!valid(job) || Thread.currentThread().isInterrupted())
            throw new CancellationException("Stale avatar generation");
    }

    private void finish(Job job, Result result) {
        synchronized (lock) {
            if (active == job) active = null;
            if (valid(job)) {
                last = result;
                job.result.complete(result);
            } else job.result.complete(cancelled(job.profile, "Stale avatar generation"));
        }
    }

    private static Result failure(Profile p, Throwable error) {
        return new Result(p, Status.FAILED, Source.NONE, null, List.of(describe(error)));
    }

    private static Result cancelled(Profile p, String reason) {
        return new Result(p, Status.CANCELLED, Source.NONE, null, List.of(reason));
    }

    private static String describe(Throwable failure) {
        while (failure instanceof ExecutionException || failure instanceof CompletionException) {
            if (failure.getCause() == null) break;
            failure = failure.getCause();
        }
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    private void cancelActive(String reason) {
        Job job = active;
        active = null;
        if (job == null) return;
        job.cancelled.set(true);
        CompletableFuture<?> client = job.clientTask;
        if (client != null) client.cancel(false);
        Future<?> running = job.running;
        if (running != null) running.cancel(true);
        job.result.complete(cancelled(job.profile, reason));
    }

    /** Cancels this generation without clearing already completed UUID cache entries. */
    public void cancel() {
        synchronized (lock) {
            generation++;
            cancelActive("Avatar request cancelled");
            for (CompletableFuture<?> task : List.copyOf(identityTasks)) task.cancel(false);
            identityTasks.clear();
        }
    }

    /** Resource reload/account refresh: cancel pending work and discard completed PNGs. */
    public void invalidate() {
        synchronized (lock) {
            cancel();
            cache.clear();
            last = new Result(profile, Status.NO_READABLE_SKIN, Source.NONE, null, List.of());
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            cancelActive("Avatar loader closed");
            for (CompletableFuture<?> task : List.copyOf(identityTasks)) task.cancel(false);
            identityTasks.clear();
            cache.clear();
            profile = null;
            last = cancelled(null, "Avatar loader closed");
        }
    }
}
