package com.muoniumplayer.configskija.ui.search;

import com.muoniumplayer.configskija.model.SearchIndex;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.UiPage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Explicit page registry: query access is PageContext.query(), with no hidden global/thread-local state. */
public final class SearchPages {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public record Group(String id, String title, int order) {
        public Group { requireId(id); Objects.requireNonNull(title); }
    }
    /** Custom pages keep ownership of local queries just as currentPageSearchQuery does upstream. */
    public record Page(String id, String title, String icon, Group group, int order, boolean showInSidebar,
                       String searchPlaceholder, Set<SearchIndex.Scope> scopes, Function<PageContext, UiPage> content) {
        public Page { requireId(id); Objects.requireNonNull(title); Objects.requireNonNull(icon); Objects.requireNonNull(group); scopes = Set.copyOf(scopes); Objects.requireNonNull(content); }
        public Page(String id, String title, String icon, Group group, UiPage content) {
            this(id, title, icon, group, 0, true, null, Set.of(), ignored -> content);
        }
        public String query(PageContext context) { return context.query(); }
        public List<SearchIndex.Document<?>> search(SearchIndex index, PageContext context) { return index.search(query(context), scopes); }
        public UiPage open(PageContext context) { return Objects.requireNonNull(content.apply(context)); }
    }
    private final LinkedHashMap<String, Page> pages = new LinkedHashMap<>();
    public synchronized void register(Page page) {
        Objects.requireNonNull(page);
        if (pages.containsKey(page.id)) throw new IllegalArgumentException("Page already registered: " + page.id);
        pages.put(page.id, page);
    }
    public synchronized boolean unregister(String id) { return pages.remove(id) != null; }
    public synchronized Page byId(String id) { return pages.get(id); }
    public synchronized List<Page> all() {
        ArrayList<Page> result = new ArrayList<>(pages.values());
        result.sort(Comparator.comparingInt((Page page) -> page.group.order).thenComparing(page -> page.group.id).thenComparingInt(Page::order));
        return List.copyOf(result);
    }
    public List<Page> sidebar() { return all().stream().filter(Page::showInSidebar).toList(); }
    public static String query(PageContext context) { return context.query(); }
    private static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Namespaced page/group id required: " + id);
    }
}
