package com.muoniumplayer.core.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.muoniumplayer.core.update.SponsorNotice.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure JUnit: isolated sessions/stores only, never the runtime singleton or game config paths. */
class SponsorNoticeTest {
    @TempDir Path directory;

    @Test
    void missingFileCountsActualStartupsFromOneAndFirstThresholdIsFive() throws Exception {
        Path path = directory.resolve("prefs/sponsor_notice.json");
        for (long launch = 1; launch <= 5; launch++) {
            ManualWorker worker = new ManualWorker();
            Session session = newSession(path, worker);
            assertEquals(new Snapshot(0, 5, 0, false, false), session.snapshot());
            assertFalse(session.shouldShow());
            session.beginSession();
            assertFalse(session.snapshot().ready());
            assertFalse(session.markShownThisSession());
            worker.runAll();
            assertEquals(new Snapshot(launch, 5, 0, false, true), session.snapshot());
            assertEquals(launch == 5, session.shouldShow());
            assertFalse(session.snapshot().canHideForever());
            assertEquals(new State(launch, 5, 0, false), persisted(path));
        }
    }

    @Test
    void merelyCheckingEligibilityDoesNotClaimOrIncrementShownCount() throws Exception {
        Path path = seeded(new State(4, 5, 0, false));
        ManualWorker worker = new ManualWorker();
        Session session = started(path, worker);
        byte[] before = Files.readAllBytes(path);
        Snapshot presentation = session.snapshot();
        for (int i = 0; i < 50; i++) {
            assertTrue(session.shouldShow());
            assertEquals(presentation, session.snapshot());
        }
        assertEquals(0, worker.size());
        assertArrayEquals(before, Files.readAllBytes(path));
        assertFalse(presentation.canHideForever());
        assertTrue(session.markShownThisSession());
        assertEquals(1, session.snapshot().shownCount());
        assertTrue(session.snapshot().canHideForever());
        assertFalse(presentation.canHideForever(), "immutable pre-claim snapshot retains first-presentation flag");
        assertFalse(session.shouldShow());
        assertFalse(session.markShownThisSession());
        worker.runAll();
        assertEquals(new State(5, 5, 1, false), persisted(path));
    }

    @Test
    void nextTimePostponesFromFiveToThirteenAndSkipsThroughTwelve() throws Exception {
        Path path = seeded(new State(4, 5, 0, false));
        ManualWorker worker = new ManualWorker();
        Session first = started(path, worker);
        assertTrue(first.markShownThisSession());
        first.remindLater(); // Includes ESC and external UI removal; UI resolves once.
        assertEquals(13, first.snapshot().nextPromptLaunch());
        assertFalse(first.shouldShow());
        assertFalse(first.markShownThisSession());
        worker.runAll();
        assertEquals(new State(5, 13, 1, false), persisted(path));
        for (long launch = 6; launch <= 13; launch++) {
            Session next = started(path, new ManualWorker());
            assertEquals(launch, next.snapshot().launches());
            assertEquals(13, next.snapshot().nextPromptLaunch());
            assertEquals(1, next.snapshot().shownCount());
            assertTrue(next.snapshot().canHideForever());
            assertEquals(launch == 13, next.shouldShow());
        }
    }

    @Test
    void everyRepeatUsesCurrentLaunchPlusEightAndPreservesShownCount() throws Exception {
        Path path = seeded(new State(12, 13, 1, false));
        ManualWorker worker = new ManualWorker();
        Session repeat = started(path, worker);
        Snapshot beforeClaim = repeat.snapshot();
        assertTrue(beforeClaim.canHideForever());
        assertTrue(repeat.markShownThisSession());
        assertEquals(2, repeat.snapshot().shownCount());
        repeat.remindLater();
        assertEquals(21, repeat.snapshot().nextPromptLaunch());
        worker.runAll();
        assertEquals(new State(13, 21, 2, false), persisted(path));

        // Eligibility may be deferred by UI conditions; don't schedule from the OLD threshold.
        Path delayedPath = directory.resolve("delayed.json");
        seed(delayedPath, new State(19, 13, 2, false));
        ManualWorker delayedWorker = new ManualWorker();
        Session delayed = started(delayedPath, delayedWorker);
        assertTrue(delayed.snapshot().canHideForever());
        assertTrue(delayed.markShownThisSession());
        delayed.remindLater();
        delayedWorker.runAll();
        assertEquals(new State(20, 28, 3, false), persisted(delayedPath));
    }

    @Test
    void neverShowPersistsAndCountsEveryLaterStartupWithoutChangingThresholdOrShownCount() throws Exception {
        Path path = seeded(new State(12, 13, 1, false));
        ManualWorker worker = new ManualWorker();
        Session repeat = started(path, worker);
        assertTrue(repeat.snapshot().canHideForever());
        assertTrue(repeat.markShownThisSession());
        repeat.neverShow();
        repeat.remindLater(); // A stale dismissal cannot undo permanent suppression.
        assertEquals(new Snapshot(13, 13, 2, true, true), repeat.snapshot());
        worker.runAll();
        for (long launch = 14; launch <= 25; launch++) {
            ManualWorker nextWorker = new ManualWorker();
            Session next = started(path, nextWorker);
            assertEquals(new Snapshot(launch, 13, 2, true, true), next.snapshot());
            assertFalse(next.shouldShow());
            assertFalse(next.markShownThisSession());
            next.remindLater();
            next.neverShow();
            nextWorker.runAll();
            assertEquals(new State(launch, 13, 2, true), persisted(path));
        }
    }

    @Test
    void unmountedOrNotReadyCallbacksCannotChangePreferences() throws Exception {
        Path path = seeded(new State(4, 5, 0, false));
        ManualWorker worker = new ManualWorker();
        Session session = newSession(path, worker);
        session.remindLater();
        session.neverShow();
        assertEquals(new Snapshot(0, 5, 0, false, false), session.snapshot());
        session.beginSession();
        session.remindLater();
        session.neverShow();
        worker.runAll();
        session.remindLater();
        session.neverShow();
        assertEquals(new Snapshot(5, 5, 0, false, true), session.snapshot());
        assertTrue(session.shouldShow());
        assertEquals(0, worker.size());
        assertEquals(new State(5, 5, 0, false), persisted(path));
    }

    @Test
    void startupAndClaimAreOnceOnlyEvenWithConcurrentCallers() throws Exception {
        MemoryStore store = new MemoryStore(new State(4, 5, 0, false));
        ManualWorker worker = new ManualWorker();
        AtomicInteger created = new AtomicInteger();
        Session session = new Session(() -> { created.incrementAndGet(); return store; }, worker);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> startups = new ArrayList<>();
            for (int i = 0; i < 64; i++) startups.add(callers.submit(session::beginSession));
            for (Future<?> startup : startups) startup.get(5, TimeUnit.SECONDS);
            assertEquals(1, worker.size());
            assertEquals(0, created.get());
            worker.runAll();
            assertEquals(1, created.get());
            assertEquals(1, store.reads);
            assertEquals(List.of(new State(5, 5, 0, false)), store.writes);
            AtomicInteger claims = new AtomicInteger();
            List<Future<?>> mounts = new ArrayList<>();
            for (int i = 0; i < 64; i++) mounts.add(callers.submit(() -> {
                if (session.markShownThisSession()) claims.incrementAndGet();
            }));
            for (Future<?> mount : mounts) mount.get(5, TimeUnit.SECONDS);
            assertEquals(1, claims.get());
            session.beginSession();
            worker.runAll();
            assertEquals(1, store.reads);
            assertEquals(new Snapshot(5, 5, 1, false, true), session.snapshot());
            assertEquals(List.of(new State(5, 5, 0, false), new State(5, 5, 1, false)), store.writes);
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void queuedWritesAreCoalescedAndNeverResurrectFalseSuppression() {
        MemoryStore store = new MemoryStore(new State(12, 13, 1, false));
        ManualWorker worker = new ManualWorker();
        Session session = new Session(() -> store, worker);
        session.beginSession();
        worker.runAll();
        assertTrue(session.markShownThisSession());
        session.remindLater();
        session.neverShow();
        session.remindLater();
        assertEquals(1, worker.size());
        assertEquals(1, store.writes.size(), "UI operations must only queue disk work");
        worker.runAll();
        assertEquals(List.of(new State(13, 13, 1, false), new State(13, 21, 2, true)), store.writes);
    }

    @Test
    void readinessWaitsForReadAndStartupWriteAndIoNeverRunsOnCallerThread() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), allowRead = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1), allowWrite = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        ExecutorService worker = daemonWorker();
        Store store = new Store() {
            @Override public State read() throws IOException {
                assertNotSame(caller, Thread.currentThread());
                reading.countDown();
                await(allowRead);
                return new State(4, 5, 0, false);
            }
            @Override public void write(State state) throws IOException {
                assertNotSame(caller, Thread.currentThread());
                writing.countDown();
                await(allowWrite);
                assertEquals(new State(5, 5, 0, false), state);
            }
        };
        Session session = new Session(() -> {
            assertNotSame(caller, Thread.currentThread(), "even path/store creation belongs to the worker");
            return store;
        }, worker);
        try {
            session.beginSession();
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertFalse(session.snapshot().ready());
            assertFalse(session.shouldShow());
            assertFalse(session.markShownThisSession());
            session.beginSession();
            allowRead.countDown();
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            assertEquals(new Snapshot(5, 5, 0, false, false), session.snapshot());
            assertFalse(session.shouldShow());
            allowWrite.countDown();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(session.snapshot().ready());
            assertTrue(session.shouldShow());
        } finally {
            allowRead.countDown();
            allowWrite.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void changesDuringAnInflightWriteAreSerializedAfterIt() throws Exception {
        CountDownLatch writingClaim = new CountDownLatch(1), allowClaim = new CountDownLatch(1);
        List<State> writes = new ArrayList<>();
        ExecutorService worker = daemonWorker();
        Store store = new Store() {
            @Override public State read() { return new State(12, 13, 1, false); }
            @Override public void write(State state) throws IOException {
                if (state.shownCount() == 2 && !state.neverShow()) {
                    writingClaim.countDown();
                    await(allowClaim);
                }
                writes.add(state);
            }
        };
        Session session = new Session(() -> store, worker);
        try {
            session.beginSession();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(session.markShownThisSession());
            assertTrue(writingClaim.await(5, TimeUnit.SECONDS));
            session.remindLater();
            session.neverShow();
            assertTrue(session.snapshot().neverShow());
            allowClaim.countDown();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(new State(13, 13, 1, false), new State(13, 13, 2, false),
                    new State(13, 21, 2, true)), writes);
        } finally {
            allowClaim.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest(name = "invalid prefs {index}")
    @MethodSource("damagedJson")
    void damagedInputFailsClosedWithoutOverwritingOrResettingSuppression(String json) throws Exception {
        Path path = directory.resolve("damaged.json");
        byte[] original = json.getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);
        ManualWorker worker = new ManualWorker();
        Session session = newSession(path, worker);
        session.beginSession();
        worker.runAll();
        assertFalse(session.snapshot().ready());
        assertFalse(session.shouldShow());
        assertFalse(session.markShownThisSession());
        session.remindLater();
        session.neverShow();
        session.beginSession();
        worker.runAll();
        assertArrayEquals(original, Files.readAllBytes(path));
        assertOnlyStateFile(path);
    }

    static Stream<String> damagedJson() {
        String valid = "{\"schemaVersion\":1,\"launches\":12,\"nextPromptLaunch\":13,\"shownCount\":1,\"neverShow\":true}";
        return Stream.of("", "null", "[]", "{}", valid.substring(0, valid.length() - 1),
                valid + " {}", valid.replace("true", "\"true\""), valid.replace("true", "null"),
                valid.replace("\"launches\":12", "\"launches\":-1"),
                valid.replace("\"launches\":12", "\"launches\":12.5"),
                valid.replace("\"launches\":12", "\"launches\":12.0"),
                valid.replace("\"launches\":12", "\"launches\":1e2"),
                valid.replace("\"launches\":12", "\"launches\":\"12\""),
                valid.replace("\"launches\":12", "\"launches\":9223372036854775808"),
                valid.replace("\"nextPromptLaunch\":13", "\"nextPromptLaunch\":4"),
                valid.replace("\"shownCount\":1", "\"shownCount\":13"),
                valid.replace("\"shownCount\":1", "\"shownCount\":-1"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace(",\"neverShow\":true", ""),
                valid.replace(",\"nextPromptLaunch\":13", ""),
                valid.replace("\"schemaVersion\":1,", ""),
                valid.replace("true}", "true,\"neverShow\":false}"),
                valid.replace("true}", "true,\"extra\":{}}"),
                valid.replace("true}", "true,}"), valid.replace("true", "True"),
                "/* comment */" + valid, "{neverShow:true}",
                valid.replace("true}", "true,\"extra\":" + "[".repeat(200) + "0" + "]".repeat(200) + "}"),
                " ".repeat(MAX_STATE_BYTES + 1));
    }

    @Test
    void malformedUtf8IsRejectedWithoutReplacement() throws Exception {
        Path path = directory.resolve("utf8.json");
        byte[] malformed = {(byte) '{', (byte) '"', (byte) 0xC3, (byte) 0x28};
        Files.write(path, malformed);
        Session session = started(path, new ManualWorker());
        assertFalse(session.snapshot().ready());
        assertFalse(session.shouldShow());
        assertArrayEquals(malformed, Files.readAllBytes(path));
    }

    @Test
    void readFailureNeverWritesAndDoesNotRetryInSameSession() {
        MemoryStore store = new MemoryStore(new State(12, 13, 1, true));
        store.readFailure = true;
        ManualWorker worker = new ManualWorker();
        Session session = new Session(() -> store, worker);
        session.beginSession();
        worker.runAll();
        store.readFailure = false;
        session.beginSession();
        worker.runAll();
        assertFalse(session.snapshot().ready());
        assertFalse(session.shouldShow());
        assertEquals(1, store.reads);
        assertTrue(store.writes.isEmpty());
    }

    @Test
    void startupWriteFailureRetainsLoadedSuppressionAndDisablesSession() {
        MemoryStore store = new MemoryStore(new State(12, 13, 1, true));
        store.writeFailure = true;
        ManualWorker worker = new ManualWorker();
        Session session = new Session(() -> store, worker);
        session.beginSession();
        worker.runAll();
        assertEquals(new Snapshot(13, 13, 1, true, false), session.snapshot());
        assertFalse(session.shouldShow());
        assertFalse(session.markShownThisSession());
        store.writeFailure = false;
        session.beginSession();
        session.remindLater();
        worker.runAll();
        assertEquals(new State(12, 13, 1, true), store.stored);
        assertTrue(store.writes.isEmpty());
    }

    @Test
    void laterWriteFailureFailsClosedWithoutUndoingInMemoryNeverShowOrRetrying() {
        MemoryStore store = new MemoryStore(new State(12, 13, 1, false));
        ManualWorker worker = new ManualWorker();
        Session session = new Session(() -> store, worker);
        session.beginSession();
        worker.runAll();
        assertTrue(session.markShownThisSession());
        session.neverShow();
        store.writeFailure = true;
        worker.runAll();
        assertEquals(new Snapshot(13, 13, 2, true, false), session.snapshot());
        assertFalse(session.shouldShow());
        store.writeFailure = false;
        session.beginSession();
        session.remindLater();
        session.neverShow();
        worker.runAll();
        assertEquals(new State(13, 13, 1, false), store.stored);
        assertEquals(1, store.writes.size());
    }

    @Test
    void rejectedWorkerFailsClosedInsteadOfDoingInlineIo() {
        AtomicInteger reads = new AtomicInteger();
        Session session = new Session(() -> { reads.incrementAndGet(); return new MemoryStore(State.FRESH); },
                task -> { throw new RejectedExecutionException("worker unavailable"); });
        session.beginSession();
        session.beginSession();
        assertEquals(0, reads.get());
        assertFalse(session.snapshot().ready());
        assertFalse(session.shouldShow());
    }

    @Test
    void linkageFailureOnInitializationAndLaterSaveDisablesSessionWithoutResetting() {
        ManualWorker initializingWorker = new ManualWorker();
        Session initializing = new Session(() -> { throw new NoClassDefFoundError("missing dependency"); },
                initializingWorker);
        initializing.beginSession();
        initializingWorker.runAll();
        assertFalse(initializing.snapshot().ready());
        assertFalse(initializing.shouldShow());

        ManualWorker saveWorker = new ManualWorker();
        AtomicInteger writes = new AtomicInteger();
        Store store = new Store() {
            @Override public State read() { return new State(12, 13, 1, false); }
            @Override public void write(State state) {
                if (writes.incrementAndGet() > 1) throw new NoSuchMethodError("missing persistence API");
            }
        };
        Session saving = new Session(() -> store, saveWorker);
        saving.beginSession();
        saveWorker.runAll();
        assertTrue(saving.markShownThisSession());
        saving.neverShow();
        saveWorker.runAll();
        assertEquals(new Snapshot(13, 13, 2, true, false), saving.snapshot());
        assertFalse(saving.shouldShow());
        saving.beginSession();
        saveWorker.runAll();
        assertEquals(2, writes.get());
    }

    @Test
    void damagedFileArrivingBeforeSaveIsNotOverwrittenAndTemporaryFileIsRemoved() throws Exception {
        Path path = seeded(new State(12, 13, 1, false));
        ManualWorker worker = new ManualWorker();
        Session session = started(path, worker);
        assertTrue(session.markShownThisSession());
        session.neverShow();
        byte[] damagedSuppression = "{\"neverShow\":true,".getBytes(StandardCharsets.UTF_8);
        Files.write(path, damagedSuppression);
        worker.runAll();
        assertFalse(session.snapshot().ready());
        assertTrue(session.snapshot().neverShow());
        assertFalse(session.shouldShow());
        assertArrayEquals(damagedSuppression, Files.readAllBytes(path));
        assertOnlyStateFile(path);
    }

    @Test
    void validExternalSuppressionEditsAreNotOverwrittenEither() throws Exception {
        Path path = seeded(new State(12, 13, 1, false));
        FileStore store = new FileStore(path);
        assertEquals(new State(12, 13, 1, false), store.read());
        seed(path, new State(12, 13, 1, true));
        byte[] external = Files.readAllBytes(path);
        assertThrows(IOException.class, () -> store.write(new State(13, 13, 1, false)));
        assertArrayEquals(external, Files.readAllBytes(path));
        assertOnlyStateFile(path);
    }

    @Test
    void unreadableDirectoryAndUnwritableParentCannotBecomeReady() throws Exception {
        Path parent = directory.resolve("parent-file");
        Files.writeString(parent, "not a directory");
        Session badParent = started(parent.resolve("notice.json"), new ManualWorker());
        assertFalse(badParent.snapshot().ready());
        assertFalse(badParent.shouldShow());
        assertEquals("not a directory", Files.readString(parent));
        Path directoryState = Files.createDirectory(directory.resolve("directory-state"));
        Session badFile = started(directoryState, new ManualWorker());
        assertFalse(badFile.snapshot().ready());
        assertFalse(badFile.shouldShow());
        assertTrue(Files.isDirectory(directoryState));
    }

    @Test
    void fileStoreRequiresSuccessfulReadAndNeverOverwritesInvalidState() throws Exception {
        Path path = directory.resolve("store.json");
        FileStore store = new FileStore(path);
        assertThrows(IOException.class, () -> store.write(State.FRESH));
        assertFalse(Files.exists(path));
        Files.writeString(path, "{\"neverShow\":true}");
        assertThrows(IOException.class, store::read);
        assertThrows(IOException.class, () -> store.write(State.FRESH));
        assertEquals("{\"neverShow\":true}", Files.readString(path));
    }

    @Test
    void knownFieldsCanBeReorderedAndWhitespaceDoesNotChangeState() throws Exception {
        Path path = directory.resolve("reordered.json");
        Files.writeString(path, " \r\n{\n\"neverShow\" : true,\t\"shownCount\":1,"
                + "\"nextPromptLaunch\":13,\"launches\":12, \"schemaVersion\":1\n}\t");
        assertEquals(new State(12, 13, 1, true), persisted(path));
    }

    @Test
    void failedRereadRevokesStoresWritePermission() throws Exception {
        Path path = seeded(new State(12, 13, 1, true));
        FileStore store = new FileStore(path);
        store.read();
        Files.writeString(path, "{\"neverShow\":true}");
        assertThrows(IOException.class, store::read);
        assertThrows(IOException.class, () -> store.write(State.FRESH));
        assertEquals("{\"neverShow\":true}", Files.readString(path));
    }

    @Test
    void atomicFileReplacementRoundTripsAllFieldsAndLeavesNoTemporaryFiles() throws Exception {
        Path path = directory.resolve("prefs/notice.json");
        FileStore store = new FileStore(path);
        assertEquals(State.FRESH, store.read());
        State first = new State(12, 13, 1, false);
        store.write(first);
        assertEquals(first, persisted(path));
        State second = new State(13, 21, 2, true);
        store.write(second);
        assertEquals(second, persisted(path));
        assertTrue(Files.size(path) <= MAX_STATE_BYTES);
        assertOnlyStateFile(path);
    }

    @Test
    void terminalLaunchCountsSaturateWithoutWrappingOrReopeningPrompt() throws Exception {
        for (boolean never : new boolean[]{false, true}) {
            Path path = directory.resolve("max-" + never + ".json");
            seed(path, new State(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE - 1, never));
            for (int restart = 0; restart < 3; restart++) {
                ManualWorker worker = new ManualWorker();
                Session session = started(path, worker);
                assertEquals(new Snapshot(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1, never, true),
                        session.snapshot());
                assertFalse(session.shouldShow());
                assertFalse(session.markShownThisSession());
                session.remindLater();
                worker.runAll();
                assertEquals(new State(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1, never), persisted(path));
            }
        }
        Path saturatedShown = directory.resolve("max-shown.json");
        seed(saturatedShown, new State(Long.MAX_VALUE, 5, Long.MAX_VALUE, false));
        Session session = started(saturatedShown, new ManualWorker());
        assertEquals(Long.MAX_VALUE, session.snapshot().shownCount());
        assertFalse(session.shouldShow());
    }

    @Test
    void postpositionNearMaxSaturatesThresholdAndShownCountCannotWrap() throws Exception {
        Path path = seeded(new State(Long.MAX_VALUE - 3, Long.MAX_VALUE - 2, Long.MAX_VALUE - 3, false));
        ManualWorker worker = new ManualWorker();
        Session session = started(path, worker);
        assertTrue(session.shouldShow());
        assertTrue(session.markShownThisSession());
        session.remindLater();
        worker.runAll();
        assertEquals(new State(Long.MAX_VALUE - 2, Long.MAX_VALUE, Long.MAX_VALUE - 2, false), persisted(path));
        assertFalse(session.markShownThisSession());
        Session next = started(path, new ManualWorker());
        assertEquals(Long.MAX_VALUE - 1, next.snapshot().launches());
        assertFalse(next.shouldShow());
        Session terminal = started(path, new ManualWorker());
        assertEquals(Long.MAX_VALUE, terminal.snapshot().launches());
        assertFalse(terminal.shouldShow());
    }

    private Path seeded(State state) throws IOException {
        Path path = directory.resolve("notice.json");
        seed(path, state);
        return path;
    }

    private static void seed(Path path, State state) throws IOException {
        FileStore store = new FileStore(path);
        store.read();
        store.write(state);
    }

    private static State persisted(Path path) throws IOException {
        return new FileStore(path).read();
    }

    private static Session newSession(Path path, ManualWorker worker) {
        return new Session(() -> new FileStore(path), worker);
    }

    private static Session started(Path path, ManualWorker worker) {
        Session session = newSession(path, worker);
        session.beginSession();
        worker.runAll();
        return session;
    }

    private static void assertOnlyStateFile(Path path) throws IOException {
        try (Stream<Path> children = Files.list(path.getParent())) {
            assertEquals(List.of(path), children.toList());
        }
    }

    private static ExecutorService daemonWorker() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "test-sponsor-store");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static final class ManualWorker implements Executor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        int size() { return tasks.size(); }
        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    private static final class MemoryStore implements Store {
        private State stored;
        private final List<State> writes = new ArrayList<>();
        private int reads;
        private boolean readFailure;
        private boolean writeFailure;

        MemoryStore(State stored) { this.stored = stored; }
        @Override public State read() throws IOException {
            reads++;
            if (readFailure) throw new IOException("Simulated read failure");
            return stored;
        }
        @Override public void write(State state) throws IOException {
            if (writeFailure) throw new IOException("Simulated atomic write failure");
            stored = state;
            writes.add(state);
        }
    }
}
