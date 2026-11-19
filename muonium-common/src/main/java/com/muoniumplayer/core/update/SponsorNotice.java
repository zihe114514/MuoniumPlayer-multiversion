package com.muoniumplayer.core.update;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-only sponsorship reminder bookkeeping; no Minecraft, UI or network dependencies.
 *
 * <p>Call {@link #beginSession()} at mod startup, not when opening the player. It is idempotent
 * for this JVM. Reads, launch increments and writes run on one daemon worker; {@code ready}
 * becomes true only after the startup increment has been persisted. Missing state starts at
 * zero (no inferred historical launches). Invalid/unreadable state or a failed write disables
 * the reminder for this session, without resetting the file or its suppression preference.</p>
 *
 * <p>The first threshold is launch 5. Eligibility never claims a presentation. At the actual
 * UI mount, capture {@link #snapshot()} BEFORE calling {@link #markShownThisSession()}: its
 * {@code canHideForever()} describes whether this is a repeat presentation. A successful claim
 * increments {@code shownCount} and prevents any further mount this JVM. The UI offers permanent
 * suppression on repeats and resolves each dismissal once; ESC/external removal must call
 * {@link #remindLater()}, which schedules the current launch + 8. This class never opens UI.</p>
 *
 * <p>Writes are asynchronous and coalesced. Normal JVM shutdown drains the daemon worker for up
 * to three seconds; abrupt termination or blocked storage can still lose a pending UI choice.
 * Unsupported atomic replacement fails
 * closed instead of falling back to truncation. Counts saturate at {@link Long#MAX_VALUE}; that
 * terminal launch cannot show a notice, avoiding an unrepresentable +8 interval wrapping around.
 * The store detects external changes before replacement, but is not a cross-process CAS/lock.</p>
 */
public final class SponsorNotice {
    static final long FIRST_PROMPT_LAUNCH = 5;
    static final long POSTPONE_LAUNCHES = 8;
    static final int MAX_STATE_BYTES = 4096;
    private static final System.Logger LOGGER = System.getLogger(SponsorNotice.class.getName());

    /** Immutable read-side publication. Repeat availability must be read before the mount claim. */
    public record Snapshot(long launches, long nextPromptLaunch, long shownCount,
                           boolean neverShow, boolean ready) {
        public boolean canHideForever() {
            return shownCount >= 1;
        }
    }

    // Path resolution must also occur on the worker: ConfigPaths' class initializer migrates files.
    private static final class RuntimeSession {
        private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "muonium-sponsor-state");
            thread.setDaemon(true);
            return thread;
        });
        private static final Session INSTANCE = new Session(
                () -> new FileStore(ConfigPaths.SPONSOR_NOTICE.toPath()), WORKER);

        static {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    WORKER.shutdown();
                    try {
                        if (!WORKER.awaitTermination(3, TimeUnit.SECONDS)) {
                            LOGGER.log(System.Logger.Level.WARNING, "Sponsor state shutdown drain timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, "muonium-sponsor-state-shutdown"));
            } catch (IllegalStateException | SecurityException unavailable) {
                LOGGER.log(System.Logger.Level.WARNING, "Sponsor state shutdown drain unavailable", unavailable);
            }
        }
    }

    private SponsorNotice() {
    }

    public static void beginSession() {
        RuntimeSession.INSTANCE.beginSession();
    }

    public static Snapshot snapshot() {
        return RuntimeSession.INSTANCE.snapshot();
    }

    public static boolean shouldShow() {
        return RuntimeSession.INSTANCE.shouldShow();
    }

    /** Call only at a real mount; false means the UI must not mount this reminder. */
    public static boolean markShownThisSession() {
        return RuntimeSession.INSTANCE.markShownThisSession();
    }

    public static void remindLater() {
        RuntimeSession.INSTANCE.remindLater();
    }

    public static void neverShow() {
        RuntimeSession.INSTANCE.neverShow();
    }

    static record State(long launches, long nextPromptLaunch, long shownCount, boolean neverShow) {
        static final State FRESH = new State(0, FIRST_PROMPT_LAUNCH, 0, false);

        State {
            if (launches < 0 || nextPromptLaunch < FIRST_PROMPT_LAUNCH
                    || shownCount < 0 || shownCount > launches) {
                throw new IllegalArgumentException("Invalid sponsor notice counts");
            }
        }

        Snapshot snapshot(boolean ready) {
            return new Snapshot(launches, nextPromptLaunch, shownCount, neverShow, ready);
        }
    }

    interface Store {
        State read() throws IOException;
        void write(State state) throws IOException;
    }

    /** One isolated JVM session. The supplied executor must be a serialized background worker. */
    static final class Session {
        private final Object lock = new Object();
        private final Supplier<Store> storeFactory;
        private final Executor worker;
        private volatile Snapshot current = State.FRESH.snapshot(false);
        private State state = State.FRESH;
        private Store store;
        private boolean started;
        private boolean shown;
        private boolean failed;
        private boolean saveScheduled;
        private boolean dirty;

        Session(Supplier<Store> storeFactory, Executor worker) {
            this.storeFactory = Objects.requireNonNull(storeFactory);
            this.worker = Objects.requireNonNull(worker);
        }

        void beginSession() {
            synchronized (lock) {
                if (started) return;
                started = true;
                try {
                    worker.execute(this::initialize);
                } catch (RuntimeException failure) {
                    failClosed(failure);
                }
            }
        }

        Snapshot snapshot() {
            return current;
        }

        boolean shouldShow() {
            synchronized (lock) {
                return eligible();
            }
        }

        private boolean eligible() {
            return current.ready() && !failed && !shown && !state.neverShow()
                    && state.launches() >= state.nextPromptLaunch()
                    && state.launches() < Long.MAX_VALUE;
        }

        boolean markShownThisSession() {
            synchronized (lock) {
                if (!eligible()) return false;
                shown = true;
                state = new State(state.launches(), state.nextPromptLaunch(),
                        addSaturated(state.shownCount(), 1), state.neverShow());
                current = state.snapshot(true);
                requestSave();
                return true;
            }
        }

        void remindLater() {
            synchronized (lock) {
                if (!current.ready() || !shown || state.neverShow()) return;
                state = new State(state.launches(), addSaturated(state.launches(), POSTPONE_LAUNCHES),
                        state.shownCount(), false);
                current = state.snapshot(true);
                requestSave();
            }
        }

        void neverShow() {
            synchronized (lock) {
                if (!current.ready() || !shown || state.neverShow()) return;
                state = new State(state.launches(), state.nextPromptLaunch(), state.shownCount(), true);
                current = state.snapshot(true);
                requestSave();
            }
        }

        private void initialize() {
            try {
                store = Objects.requireNonNull(storeFactory.get());
                State loaded = store.read();
                State launched = new State(addSaturated(loaded.launches(), 1),
                        loaded.nextPromptLaunch(), loaded.shownCount(), loaded.neverShow());
                synchronized (lock) {
                    state = launched;
                    current = state.snapshot(false);
                }
                store.write(launched);
                synchronized (lock) {
                    current = state.snapshot(true);
                }
            } catch (IOException | RuntimeException | LinkageError failure) {
                failClosed(failure);
            }
        }

        private void requestSave() {
            dirty = true;
            if (saveScheduled) return;
            saveScheduled = true;
            try {
                worker.execute(this::savePending);
            } catch (RuntimeException failure) {
                failClosed(failure);
            }
        }

        private void savePending() {
            try {
                while (true) {
                    State pending;
                    synchronized (lock) {
                        if (failed || !dirty) {
                            saveScheduled = false;
                            return;
                        }
                        dirty = false;
                        pending = state;
                    }
                    store.write(pending);
                }
            } catch (IOException | RuntimeException | LinkageError failure) {
                failClosed(failure);
            }
        }

        private void failClosed(Throwable failure) {
            synchronized (lock) {
                failed = true;
                dirty = false;
                saveScheduled = false;
                current = state.snapshot(false);
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "Sponsor reminder disabled for this session; local state was not reset", failure);
        }
    }

    private static long addSaturated(long value, long amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }

    /** Strict bounded JSON and atomic same-directory replacement; only accessed by its worker. */
    static final class FileStore implements Store {
        private final Path path;
        private byte[] expectedBytes;
        private boolean readSuccessfully;

        FileStore(Path path) {
            this.path = Objects.requireNonNull(path).toAbsolutePath();
        }

        @Override
        public State read() throws IOException {
            readSuccessfully = false;
            byte[] bytes = readBytes();
            State loaded = bytes == null ? State.FRESH : decode(bytes);
            expectedBytes = bytes;
            readSuccessfully = true;
            return loaded;
        }

        @Override
        public void write(State state) throws IOException {
            if (!readSuccessfully) throw new IOException("Sponsor state was not successfully read");
            byte[] bytes = encode(state);
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), "sponsor-notice-", ".tmp");
            try {
                try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                // Preserve both damaged suppression and valid manual edits arriving during this JVM.
                if (!Arrays.equals(expectedBytes, readBytes())) {
                    throw new IOException("Sponsor state changed outside this session");
                }
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                expectedBytes = bytes;
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        private byte[] readBytes() throws IOException {
            if (Files.isSymbolicLink(path)) throw new IOException("Sponsor state must not be a symbolic link");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_STATE_BYTES + 1);
                if (bytes.length > MAX_STATE_BYTES) throw new IOException("Sponsor state is too large");
                return bytes;
            } catch (NoSuchFileException missing) {
                return null;
            }
        }

        private static byte[] encode(State state) {
            String json = "{\"schemaVersion\":1,\"launches\":" + state.launches()
                    + ",\"nextPromptLaunch\":" + state.nextPromptLaunch()
                    + ",\"shownCount\":" + state.shownCount()
                    + ",\"neverShow\":" + state.neverShow() + "}\n";
            return json.getBytes(StandardCharsets.UTF_8);
        }

        private static final Pattern FIELD = Pattern.compile(
                "\"(schemaVersion|launches|nextPromptLaunch|shownCount|neverShow)\""
                        + "[ \\t\\r\\n]*:[ \\t\\r\\n]*(true|false|0|[1-9][0-9]{0,18})");

        private static State decode(byte[] bytes) throws IOException {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            // Deliberately only the flat, exact schema we write. No nested/unknown values, lenient JSON,
            // recursion, coercion or dependency on the game's loader-specific Gson version.
            int cursor = skipWhitespace(json, 0);
            if (cursor == json.length() || json.charAt(cursor++) != '{') {
                throw new IOException("Sponsor state must be an object");
            }
            Set<String> keys = new HashSet<>();
            long launches = -1, threshold = -1, shownCount = -1;
            boolean neverShow = false;
            Matcher field = FIELD.matcher(json);
            while (true) {
                cursor = skipWhitespace(json, cursor);
                field.region(cursor, json.length());
                if (!field.lookingAt()) throw new IOException("Invalid sponsor state field");
                String key = field.group(1), value = field.group(2);
                if (!keys.add(key)) throw new IOException("Duplicate sponsor state field");
                try {
                    switch (key) {
                        case "schemaVersion" -> {
                            if (!value.equals("1")) throw new IOException("Unknown sponsor state schema");
                        }
                        case "launches" -> launches = Long.parseLong(value);
                        case "nextPromptLaunch" -> threshold = Long.parseLong(value);
                        case "shownCount" -> shownCount = Long.parseLong(value);
                        case "neverShow" -> {
                            if (!value.equals("true") && !value.equals("false")) {
                                throw new IOException("Invalid suppression flag");
                            }
                            neverShow = value.equals("true");
                        }
                        default -> throw new IOException("Unknown sponsor state field");
                    }
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid sponsor count", invalid);
                }
                cursor = skipWhitespace(json, field.end());
                if (cursor == json.length()) throw new IOException("Incomplete sponsor state");
                char separator = json.charAt(cursor++);
                if (separator == '}') break;
                if (separator != ',') throw new IOException("Invalid sponsor state separator");
            }
            if (keys.size() != 5 || skipWhitespace(json, cursor) != json.length()) {
                throw new IOException("Incomplete sponsor state or trailing input");
            }
            try {
                return new State(launches, threshold, shownCount, neverShow);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid sponsor state", invalid);
            }
        }

        private static int skipWhitespace(String text, int cursor) {
            while (cursor < text.length()) {
                char character = text.charAt(cursor);
                if (character != ' ' && character != '\t' && character != '\r' && character != '\n') break;
                cursor++;
            }
            return cursor;
        }
    }
}
