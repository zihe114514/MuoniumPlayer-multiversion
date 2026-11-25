package com.muoniumplayer.configskija.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/** Loader-neutral searchable corpus. Default matching and ordering follow the original search contract. */
public final class SearchIndex implements AutoCloseable {
    public static final int DEFAULT_SEARCH_DISTANCE = 2;
    public static final long REBUILD_DEBOUNCE_MILLIS = 250;

    public record Scope(String key) {
        public static final Scope MODS = new Scope("mods");
        public static final Scope OPTIONS = new Scope("options");
        public static final Scope KEYBINDS = new Scope("keybinds");
        public static final Scope HUDS = new Scope("huds");
        public Scope { Objects.requireNonNull(key, "scope key"); }
        public static Scope config(String id) { return new Scope("config:" + Objects.requireNonNull(id, "config id")); }
        public boolean isConfig() { return key.startsWith("config:"); }
    }

    public record Metadata(String title, String id, String description, String section, String category,
                           String subcategory, List<String> tags, String modTitle, String modDescription, String path) {
        public Metadata { tags = List.copyOf(tags); }
        public static Metadata title(String title) { return new Metadata(title, null, null, null, null, null, List.of(), null, null, null); }
        public String distinctSubcategory() { return Objects.equals(category, subcategory) ? null : subcategory; }
    }

    /** Payload equality is deliberately identity-based, even when a host uses Java records for options. */
    public static final class Document<T> {
        private final String id;
        private final Set<Scope> scopes;
        private final Metadata metadata;
        private final T payload;
        public Document(String id, Set<Scope> scopes, Metadata metadata, T payload) {
            this.id = Objects.requireNonNull(id, "document id");
            this.scopes = immutableSet(scopes);
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.payload = payload;
        }
        public String id() { return id; }
        public Set<Scope> scopes() { return scopes; }
        public Metadata metadata() { return metadata; }
        public T payload() { return payload; }
        public boolean contentEquals(Document<?> other) { return other != null && id.equals(other.id) && scopes.equals(other.scopes) && metadata.equals(other.metadata); }
        public boolean equivalentTo(Document<?> other) { return contentEquals(other) && payload == other.payload; }
        @Override public String toString() { return "SearchDocument(" + id + ")"; }
    }

    /** Called by the index worker. Capture host/UI data on its owning thread and return immutable snapshots. */
    @FunctionalInterface public interface Source {
        List<Document<?>> documents();
        default Set<Source> dependencies() { return Set.of(); }
    }

    /** Results are document IDs, ordered by this provider. Unknown/stale IDs are resolved away. */
    public interface Provider {
        int priority();
        boolean isAvailable();
        List<String> search(String query, Set<Scope> scopes);
        default void onCorpusUpdate(List<Document<?>> added, Set<String> removed) {}
    }

    public record Failure(String operation, Object participant, Throwable cause) {}
    public record Update(long revision, List<Document<?>> added, Set<String> removed) {
        public Update { added = List.copyOf(added); removed = immutableSet(removed); }
    }
    private record State(long revision, Map<String, Document<?>> documents) {}

    private final Object sourceLock = new Object(), providerLock = new Object(), scheduleLock = new Object();
    private final ReentrantLock rebuildLock = new ReentrantLock();
    private final List<Source> sources = new ArrayList<>();
    private final Set<Source> dirty = new LinkedHashSet<>();
    private final Map<Source, List<Document<?>>> produced = new HashMap<>();
    private final List<Provider> providers = new ArrayList<>();
    private final ScheduledExecutorService worker;
    private final boolean ownsWorker;
    private final Consumer<Failure> diagnostics;
    private final Provider defaultProvider = new Provider() {
        @Override public int priority() { return 0; }
        @Override public boolean isAvailable() { return true; }
        @Override public List<String> search(String query, Set<Scope> scopes) {
            return defaultSearch(state.documents, query, scopes, searchDistance).stream().map(Document::id).toList();
        }
    };
    private volatile State state = new State(0, Map.of());
    private volatile int searchDistance;
    private volatile boolean initialized, closed;
    private ScheduledFuture<?> scheduled;

    public SearchIndex() { this(DEFAULT_SEARCH_DISTANCE); }
    public SearchIndex(int distance) { this(distance, newWorker(), true, SearchIndex::logFailure); }
    /** The caller owns an injected worker. It is never shut down by this index. */
    public SearchIndex(int distance, ScheduledExecutorService worker, Consumer<Failure> diagnostics) { this(distance, worker, false, diagnostics); }
    private SearchIndex(int distance, ScheduledExecutorService worker, boolean ownsWorker, Consumer<Failure> diagnostics) {
        this.searchDistance = distance;
        this.worker = Objects.requireNonNull(worker, "worker");
        this.ownsWorker = ownsWorker;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        providers.add(defaultProvider);
    }
    private static ScheduledExecutorService newWorker() {
        return Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "muonium-config-search"); thread.setDaemon(true); return thread; });
    }
    private static void logFailure(Failure failure) {
        System.getLogger(SearchIndex.class.getName()).log(System.Logger.Level.WARNING, "Search " + failure.operation() + " failed", failure.cause());
    }
    private void report(String operation, Object participant, Throwable cause) {
        try { diagnostics.accept(new Failure(operation, participant, cause)); }
        catch (Throwable error) { logFailure(new Failure("diagnostic callback", null, error)); }
    }
    private void open() { if (closed) throw new IllegalStateException("search index is closed"); }
    public int searchDistance() { return searchDistance; }
    public void searchDistance(int distance) { open(); searchDistance = distance; }
    public long revision() { return state.revision; }
    public Map<String, Document<?>> documents() { return state.documents; }
    public Document<?> document(String id) { return state.documents.get(id); }
    public Set<String> documentIds() { return state.documents.keySet(); }
    public boolean initialized() { return initialized; }

    public boolean registerSource(Source source) {
        Objects.requireNonNull(source, "source"); open();
        synchronized (sourceLock) { if (sources.contains(source)) return false; sources.add(source); dirty.add(source); }
        schedule(); return true;
    }
    public boolean unregisterSource(Source source) {
        open();
        synchronized (sourceLock) { if (!sources.remove(source)) return false; dirty.remove(source); }
        schedule(); return true;
    }
    public void init() {
        open();
        synchronized (scheduleLock) { if (initialized) return; initialized = true; }
        invalidate();
    }
    public void invalidate(Source... invalidated) {
        open();
        synchronized (sourceLock) {
            if (invalidated.length == 0) dirty.addAll(sources);
            else for (Source source : invalidated) dirty.add(Objects.requireNonNull(source, "invalidated source"));
        }
        schedule();
    }
    private void schedule() {
        synchronized (scheduleLock) {
            if (!initialized || closed) return;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = worker.schedule(() -> { if (!closed) rebuildNow(); }, REBUILD_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Synchronous flush for integration/tests; production sources must still be safe on the calling thread. */
    public Update rebuildNow() {
        open(); rebuildLock.lock();
        try {
            open();
            List<Source> snapshot; Set<Source> expanded;
            synchronized (sourceLock) { snapshot = List.copyOf(sources); expanded = expandDirty(dirty, snapshot); }
            State previous = state;
            LinkedHashMap<String, Document<?>> built = new LinkedHashMap<>();
            ArrayList<Document<?>> added = new ArrayList<>();
            for (Source source : snapshot) {
                List<Document<?>> cached = produced.get(source), documents;
                if (cached != null && !expanded.contains(source)) documents = cached;
                else {
                    synchronized (sourceLock) { dirty.remove(source); }
                    try {
                        List<Document<?>> supplied = List.copyOf(source.documents());
                        ArrayList<Document<?>> stable = new ArrayList<>(supplied.size());
                        for (Document<?> document : supplied) {
                            Document<?> old = previous.documents.get(document.id());
                            stable.add(old != null && old.equivalentTo(document) ? old : document);
                        }
                        documents = List.copyOf(stable);
                    } catch (Throwable error) {
                        report("document source", source, error);
                        synchronized (sourceLock) { if (sources.contains(source)) dirty.add(source); }
                        if (cached == null) continue;
                        documents = cached;
                    }
                }
                produced.put(source, documents);
                for (Document<?> document : documents) {
                    if (built.putIfAbsent(document.id(), document) != null) {
                        report("duplicate document id", source, new IllegalArgumentException("duplicate document id: " + document.id()));
                    } else if (previous.documents.get(document.id()) != document) added.add(document);
                }
            }
            produced.keySet().retainAll(new HashSet<>(snapshot));
            LinkedHashSet<String> removed = new LinkedHashSet<>(previous.documents.keySet()); removed.removeAll(built.keySet());
            if (added.isEmpty() && removed.isEmpty()) return new Update(previous.revision, List.of(), Set.of());
            if (closed) return new Update(state.revision, List.of(), Set.of());
            state = new State(previous.revision + 1, Collections.unmodifiableMap(built));
            Update update = new Update(state.revision, added, removed);
            for (Provider provider : providerSnapshot()) {
                try { provider.onCorpusUpdate(update.added, update.removed); }
                catch (Throwable error) { report("provider update", provider, error); }
            }
            return update;
        } finally { rebuildLock.unlock(); }
    }
    private static Set<Source> expandDirty(Set<Source> invalidated, List<Source> sources) {
        LinkedHashSet<Source> result = new LinkedHashSet<>(invalidated);
        boolean changed;
        do {
            changed = false;
            for (Source source : sources) if (!result.contains(source) && source.dependencies().stream().anyMatch(result::contains)) changed |= result.add(source);
        } while (changed);
        return result;
    }

    public boolean registerProvider(Provider provider) {
        Objects.requireNonNull(provider, "provider"); open();
        synchronized (providerLock) {
            if (providers.contains(provider)) return false;
            providers.add(provider);
            providers.sort(Comparator.comparingInt(Provider::priority).reversed());
        }
        if (initialized) worker.execute(() -> {
            rebuildLock.lock();
            try {
                if (closed || !providerSnapshot().contains(provider) || state.documents.isEmpty()) return;
                try { provider.onCorpusUpdate(List.copyOf(state.documents.values()), Set.of()); }
                catch (Throwable error) { report("provider seed", provider, error); }
            } finally { rebuildLock.unlock(); }
        });
        return true;
    }
    public boolean unregisterProvider(Provider provider) {
        open();
        synchronized (providerLock) { return provider != defaultProvider && providers.remove(provider); }
    }
    private List<Provider> providerSnapshot() { synchronized (providerLock) { return List.copyOf(providers); } }
    private Provider selectedProvider() {
        for (Provider provider : providerSnapshot()) {
            try { if (provider.isAvailable()) return provider; }
            catch (Throwable error) { report("provider availability", provider, error); }
        }
        return defaultProvider;
    }

    public List<Document<?>> search(String query, Set<Scope> scopes) {
        return searchGrouped(query, scopes, document -> Boolean.TRUE).getOrDefault(Boolean.TRUE, List.of());
    }
    /** Groups retain first-hit order, including an empty group when its hits become stale mid-query. */
    public <K> Map<K, List<Document<?>>> searchGrouped(String query, Set<Scope> scopes, Function<Document<?>, K> grouper) {
        Objects.requireNonNull(query, "query"); Objects.requireNonNull(grouper, "grouper");
        Set<Scope> requested = immutableSet(scopes);
        if (closed || trimQuery(query).isEmpty()) return Map.of();
        Provider provider = selectedProvider();
        Map<K, List<Document<?>>> grouped;
        try { grouped = queryAndGroup(provider, query, requested, grouper); }
        catch (Throwable error) {
            report("provider query", provider, error);
            if (provider == defaultProvider) return Map.of();
            try { grouped = queryAndGroup(defaultProvider, query, requested, grouper); }
            catch (Throwable fallbackError) { report("default provider query", defaultProvider, fallbackError); return Map.of(); }
        }
        Map<String, Document<?>> current = state.documents;
        LinkedHashMap<K, List<Document<?>>> resolved = new LinkedHashMap<>();
        grouped.forEach((key, hits) -> {
            ArrayList<Document<?>> live = new ArrayList<>(hits.size());
            for (Document<?> document : hits) { Document<?> now = current.get(document.id()); if (now != null) live.add(now); }
            resolved.put(key, List.copyOf(live));
        });
        return Collections.unmodifiableMap(resolved);
    }
    private <K> Map<K, List<Document<?>>> queryAndGroup(Provider provider, String query, Set<Scope> scopes, Function<Document<?>, K> grouper) {
        Map<String, Document<?>> before = state.documents;
        List<Document<?>> hits;
        if (provider == defaultProvider) hits = defaultSearch(before, query, scopes, searchDistance);
        else {
            ArrayList<Document<?>> selected = new ArrayList<>();
            for (String id : Objects.requireNonNull(provider.search(query, scopes), "provider results")) {
                Document<?> document = before.get(id); if (document != null) selected.add(document);
            }
            hits = selected;
        }
        LinkedHashMap<K, List<Document<?>>> result = new LinkedHashMap<>();
        for (Document<?> document : hits) result.computeIfAbsent(grouper.apply(document), ignored -> new ArrayList<>()).add(document);
        return result;
    }
    public CompletableFuture<List<Document<?>>> searchAsync(String query, Set<Scope> scopes) {
        if (closed) return CompletableFuture.completedFuture(List.of());
        Set<Scope> requested = immutableSet(scopes);
        try { return CompletableFuture.supplyAsync(() -> search(query, requested), worker); }
        catch (RejectedExecutionException error) {
            if (closed) return CompletableFuture.completedFuture(List.of());
            return CompletableFuture.failedFuture(error);
        }
    }

    private static List<Document<?>> defaultSearch(Map<String, Document<?>> documents, String query, Set<Scope> scopes, int distance) {
        String trimmed = trimQuery(query);
        if (trimmed.isEmpty()) return List.of();
        boolean config = scopes.stream().anyMatch(Scope::isConfig), keybinds = scopes.contains(Scope.KEYBINDS);
        ArrayList<Document<?>> results = new ArrayList<>();
        for (Document<?> document : documents.values()) {
            if (Collections.disjoint(scopes, document.scopes())) continue;
            Metadata m = document.metadata();
            boolean hit;
            if (document.scopes().contains(Scope.MODS)) hit = matches(m.title, trimmed, distance) || anyTag(m.tags, trimmed, distance);
            else {
                hit = matches(m.title, trimmed, distance) || matches(m.description, trimmed, distance) || anyTag(m.tags, trimmed, distance);
                if (!hit && config && document.scopes().stream().anyMatch(Scope::isConfig)) hit = matches(m.category, trimmed, distance) || matches(m.subcategory, trimmed, distance) || matches(m.id, trimmed, distance);
                if (!hit && document.scopes().contains(Scope.HUDS)) hit = matches(m.category, trimmed, distance) || matches(m.subcategory, trimmed, distance) || matches(m.id, trimmed, distance) || matches(m.modTitle, trimmed, distance);
                if (!hit && keybinds && document.scopes().contains(Scope.KEYBINDS)) hit = matches(m.category, trimmed, distance) || matches(m.subcategory, trimmed, distance) || matches(m.id, trimmed, distance) || matches(m.path, trimmed, distance);
            }
            if (hit) results.add(document);
        }
        return List.copyOf(results);
    }
    private static boolean anyTag(List<String> tags, String query, int distance) { for (String tag : tags) if (matches(tag, query, distance)) return true; return false; }

    /** Same invariant case folding, UTF-16 edit units and five delimiters as searchMatches. No word-AND. */
    public static boolean matches(String text, String query, int distance) {
        Objects.requireNonNull(query, "query"); if (text == null) return false;
        String q = query.toLowerCase(Locale.ROOT), t = text.toLowerCase(Locale.ROOT);
        if (t.contains(q)) return true;
        if (distance <= 0) return false;
        if (levenshtein(t, q, distance) <= distance) return true;
        for (String word : tokenize(t)) if (levenshtein(word, q, distance) <= distance) return true;
        return false;
    }
    public static boolean matches(String text, String query) { return matches(text, query, DEFAULT_SEARCH_DISTANCE); }
    /** Tokenization does not split tabs, colons, commas, backslashes, camelCase, CJK or the query itself. */
    public static List<String> tokenize(String text) {
        Objects.requireNonNull(text, "text"); ArrayList<String> words = new ArrayList<>(); int start = 0;
        for (int at = 0; at <= text.length(); at++) if (at == text.length() || delimiter(text.charAt(at))) {
            if (at > start) words.add(text.substring(start, at)); start = at + 1;
        }
        return List.copyOf(words);
    }
    private static boolean delimiter(char c) { return c == ' ' || c == '.' || c == '_' || c == '-' || c == '/'; }
    public static String trimQuery(String query) {
        Objects.requireNonNull(query, "query"); int begin = 0, end = query.length();
        while (begin < end && whitespace(query.charAt(begin))) begin++;
        while (end > begin && whitespace(query.charAt(end - 1))) end--;
        return query.substring(begin, end);
    }
    private static boolean whitespace(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
    /** Returns the exact distance while within max, otherwise any result greater than max is sufficient. */
    public static int levenshtein(String a, String b, int max) {
        Objects.requireNonNull(a, "a"); Objects.requireNonNull(b, "b");
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length(); if (b.isEmpty()) return a.length();
        int exceeded = max == Integer.MAX_VALUE ? max : max + 1;
        if (Math.abs(a.length() - b.length()) > max) return exceeded;
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i; int rowMinimum = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
                rowMinimum = Math.min(rowMinimum, current[j]);
            }
            if (rowMinimum > max) return exceeded;
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }
    private static <T> Set<T> immutableSet(Collection<T> values) {
        LinkedHashSet<T> result = new LinkedHashSet<>(); for (T value : values) result.add(Objects.requireNonNull(value)); return Collections.unmodifiableSet(result);
    }
    @Override public void close() {
        synchronized (scheduleLock) { if (closed) return; closed = true; if (scheduled != null) scheduled.cancel(false); }
        rebuildLock.lock();
        try {
            synchronized (sourceLock) { sources.clear(); dirty.clear(); }
            synchronized (providerLock) { providers.clear(); }
            produced.clear(); state = new State(state.revision + 1, Map.of());
        } finally { rebuildLock.unlock(); }
        if (ownsWorker) worker.shutdownNow();
    }
}
