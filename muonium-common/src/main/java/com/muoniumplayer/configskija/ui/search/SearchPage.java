package com.muoniumplayer.configskija.ui.search;

import com.muoniumplayer.configskija.model.SearchDocuments;
import com.muoniumplayer.configskija.model.SearchIndex;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.UiPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Global search integration point. Existing card/settings renderers retain ownership of all drawing. */
public final class SearchPage implements UiPage, AutoCloseable {
    public static final Set<SearchIndex.Scope> GLOBAL_SCOPES = Set.of(SearchIndex.Scope.MODS, SearchIndex.Scope.OPTIONS);
    public record Results(String query, List<SearchIndex.Document<?>> mods, Map<String, List<SearchDocuments.NarrowedRow>> groups) {
        public Results {
            Objects.requireNonNull(query); mods = List.copyOf(mods);
            LinkedHashMap<String,List<SearchDocuments.NarrowedRow>> copy = new LinkedHashMap<>();
            groups.forEach((heading, rows) -> copy.put(Objects.requireNonNull(heading), List.copyOf(rows)));
            groups = Collections.unmodifiableMap(copy);
        }
        public boolean empty() { return mods.isEmpty() && groups.isEmpty(); }
    }
    public interface Renderer {
        void searching(PageContext context);
        void results(PageContext context, Results results);
        void failed(PageContext context, Throwable error);
        default boolean pointerDown(PageContext context, int button) { return false; }
        default void pointerUp(PageContext context, int button) {}
        default boolean wheel(PageContext context, double delta) { return false; }
        default boolean key(PageContext context, com.muoniumplayer.configskija.ui.ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { return false; }
        default boolean pointerMove(PageContext context) { return false; }
        default boolean rawKey(PageContext context, int code, boolean pressed) { return false; }
        default boolean recording() { return false; }
        default void leave(PageContext context) {}
    }
    private final SearchIndex index;
    private final SearchSession session;
    private final Renderer renderer;
    private SearchDocuments.RowIndex rows;
    private List<SearchIndex.Document<?>> hits = List.of();
    private String requested;
    private long requestedRevision = -1, generation;
    private int requestedDistance = Integer.MIN_VALUE;
    private String searchedQuery;
    private boolean loading, disposed;
    private Throwable failure;
    private PageContext lastContext;
    /** Default global page uses the existing Library card and shared setting-entry implementations. */
    public SearchPage(SearchIndex index, SearchDocuments.RowIndex rows, Executor ui) { this(index, rows, ui, new GlobalResultsRenderer()); }
    public SearchPage(SearchIndex index, SearchDocuments.RowIndex rows, Executor ui, Renderer renderer) {
        this.index = Objects.requireNonNull(index); this.rows = Objects.requireNonNull(rows); this.session = new SearchSession(index, ui); this.renderer = Objects.requireNonNull(renderer);
    }
    /** Update together with the immutable settings document snapshot, on the owning UI thread. */
    public void rows(SearchDocuments.RowIndex rows) { this.rows = Objects.requireNonNull(rows); requestedRevision = -1; generation++; session.cancel(); }
    @Override public void render(PageContext context) {
        if (disposed) return;
        lastContext = context;
        String query = Objects.requireNonNullElse(context.query(), "");
        int distance = (int)context.preferences().number("searchDistance", SearchIndex.DEFAULT_SEARCH_DISTANCE);
        if (index.searchDistance() != distance) index.searchDistance(distance);
        long revision = index.revision();
        if (!query.equals(requested) || revision != requestedRevision || distance != requestedDistance) {
            requested = query; requestedRevision = revision; requestedDistance = distance; loading = true; failure = null; long ticket = ++generation;
            session.submit(query, GLOBAL_SCOPES, result -> { if (ticket == generation && !disposed) { hits = result.documents(); searchedQuery = result.query(); loading = false; } }, error -> { if (ticket == generation && !disposed) { failure = error; loading = false; } });
        }
        if (failure != null) { renderer.failed(context, failure); return; }
        if (loading && searchedQuery == null) { renderer.searching(context); return; }
        ArrayList<SearchIndex.Document<?>> mods = new ArrayList<>();
        for (SearchIndex.Document<?> document : hits) if (document.scopes().contains(SearchIndex.Scope.MODS) && rows.rowOf(document) == null) mods.add(document);
        renderer.results(context, new Results(searchedQuery == null ? query : searchedQuery, mods, rows.globalGroups(hits)));
    }
    @Override public boolean pointerDown(PageContext context, int button) { return searchedQuery != null && failure == null && renderer.pointerDown(context, button); }
    @Override public void pointerUp(PageContext context, int button) { renderer.pointerUp(context, button); }
    @Override public boolean wheel(PageContext context, double delta) { return renderer.wheel(context, delta); }
    @Override public boolean key(PageContext context, com.muoniumplayer.configskija.ui.ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { return renderer.key(context, key, typed, control, shift, alt); }
    public boolean pointerMove(PageContext context) { return renderer.pointerMove(context); }
    public boolean rawKey(PageContext context, int code, boolean pressed) { return renderer.rawKey(context, code, pressed); }
    @Override public boolean recording() { return renderer.recording(); }
    @Override public void leave(PageContext context) { generation++; session.cancel(); requested = null; requestedRevision = -1; renderer.leave(context); }
    @Override public void close() { disposed = true; generation++; session.close(); hits = List.of(); if (lastContext != null) { renderer.leave(lastContext); lastContext = null; } }
}
