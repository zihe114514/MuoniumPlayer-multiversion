package com.muoniumplayer.configskija.ui.search;

import com.muoniumplayer.configskija.model.SearchIndex;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Debounced-corpus queries with stale completion rejection. Delivery is explicitly marshalled to UI. */
public final class SearchSession implements AutoCloseable {
    public record Result(String query, long corpusRevision, List<SearchIndex.Document<?>> documents) {
        public Result { documents = List.copyOf(documents); }
    }
    private final SearchIndex index;
    private final Executor ui;
    private long ticket;
    private boolean closed;
    public SearchSession(SearchIndex index, Executor ui) { this.index = Objects.requireNonNull(index); this.ui = Objects.requireNonNull(ui); }
    public void submit(String query, Set<SearchIndex.Scope> scopes, Consumer<Result> completed, Consumer<Throwable> failed) {
        Objects.requireNonNull(completed); Objects.requireNonNull(failed);
        final long request;
        synchronized (this) { if (closed) return; request = ++ticket; }
        index.searchAsync(query, scopes).whenComplete((hits, error) -> ui.execute(() -> {
            synchronized (SearchSession.this) { if (closed || request != ticket) return; }
            if (error != null) failed.accept(error);
            else {
                // Resolve once more when delivered: a profile/locale/source update may have occurred meanwhile.
                ArrayList<SearchIndex.Document<?>> current = new ArrayList<>();
                for (SearchIndex.Document<?> document : hits) { SearchIndex.Document<?> live = index.document(document.id()); if (live != null) current.add(live); }
                completed.accept(new Result(query, index.revision(), current));
            }
        }));
    }
    public synchronized void cancel() { ticket++; }
    @Override public synchronized void close() { closed = true; ticket++; }
}
