package com.muoniumplayer.configskija.ui.search;

import com.muoniumplayer.configskija.model.SearchDocuments;
import com.muoniumplayer.configskija.model.SearchIndex;
import com.muoniumplayer.configskija.ui.ConfigView;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.notifications.NotificationTypography;
import com.muoniumplayer.configskija.ui.pages.LibraryMods;
import com.muoniumplayer.configskija.ui.settings.EntryRows;
import com.muoniumplayer.configskija.ui.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Source global-results layout using the actual LibraryMods cards and SettingsPage entry renderer. */
public final class GlobalResultsRenderer implements SearchPage.Renderer {
    public static final int MOD_COLUMNS = 4;
    public static final double MOD_GAP = 19, LIST_GAP = 8, HEADER_SIZE = 11, MESSAGE_SIZE = 15;
    public static final int HEADER_WEIGHT = 500;

    /** Layout uses the host font's line metrics; the default follows the existing settings text contract. */
    public interface Typography {
        default double lineHeight(PageContext context, String text, double size, int weight) {
            return context.host() instanceof SettingsSchema.Provider provider ? provider.settingsSchema().lineHeight(context, size, weight >= 600) : size;
        }
        default double measure(PageContext context, String text, double size, int weight) {
            return context.painter() instanceof NotificationTypography weighted ? weighted.notificationMeasure(text, size, weight) : context.painter().measure(text, size, weight >= 600);
        }
        default void draw(PageContext context, String text, double x, double y, double size, int weight, int color) {
            if (context.painter() instanceof NotificationTypography weighted) weighted.notificationText(text, x, y, size, weight, color);
            else context.painter().text(text, x, y, size, weight >= 600, color);
        }
    }
    public static final Typography HOST_TYPOGRAPHY = new Typography() {};
    private final EntryRows settings;
    private final LibraryMods.CardRenderer mods;
    private final Typography typography;
    private String completedQuery;
    private Throwable reportedError;
    private boolean rowsActive;

    public GlobalResultsRenderer() { this(new EntryRows(), LibraryMods.cardRenderer(), HOST_TYPOGRAPHY); }
    /** Per-owner schema supplies the real owner toggle/own-switch exemption and native control capabilities. */
    public GlobalResultsRenderer(Function<SearchDocuments.Owner, SettingsSchema> schemas) { this(new EntryRows(schemas), LibraryMods.cardRenderer(), HOST_TYPOGRAPHY); }
    public GlobalResultsRenderer(EntryRows settings, LibraryMods.CardRenderer mods, Typography typography) {
        this.settings = Objects.requireNonNull(settings); this.mods = Objects.requireNonNull(mods); this.typography = Objects.requireNonNull(typography);
    }
    /** Same integer physical-pixel division as SearchResultsScreen; any remainder stays at the right edge. */
    public static double modCellWidth(double width, double density) {
        if (!Double.isFinite(width) || width < 0 || !Double.isFinite(density) || density <= 0) throw new IllegalArgumentException("Invalid global-search layout dimensions");
        long pixels = Math.round(width * density), gap = Math.round(MOD_GAP * density);
        return Math.max(0, (pixels - gap * (MOD_COLUMNS - 1)) / MOD_COLUMNS) / density;
    }
    public static double modGap(double density) {
        if (!Double.isFinite(density) || density <= 0) throw new IllegalArgumentException("Invalid global-search density");
        return Math.round(MOD_GAP * density) / density;
    }
    @Override public void searching(PageContext context) {
        suspendRows(context);
        context.scrollRange(0);
        message(context, context.host().translate("oneconfig.search.searching", "Searching..."));
    }
    @Override public void failed(PageContext context, Throwable error) {
        suspendRows(context);
        context.scrollRange(0);
        if (reportedError != error) { reportedError = error; context.host().error("搜索结果", error); }
        message(context, context.host().translate("oneconfig.search.failed", "Search failed. Please try again."));
    }
    @Override public void results(PageContext context, SearchPage.Results result) {
        Objects.requireNonNull(result);
        if (completedQuery != null && !completedQuery.equals(result.query())) { mods.leave(context); context.scroll(0); }
        completedQuery = result.query();
        Rect viewport = context.content();
        List<LibraryMods.Card> cards;
        try { cards = resolveCards(context, result.mods()); }
        catch (IllegalArgumentException error) { failed(context, error); return; }
        reportedError = null;
        if (result.empty()) {
            suspendRows(context);
            context.scrollRange(0);
            message(context, context.host().translate("oneconfig.search.no_results", "No results for \"%s\"", result.query()));
            return;
        }
        double density = context.host().physicalPerLayout();
        if (!Double.isFinite(density) || density <= 0) throw new IllegalStateException("Host physical-per-layout density is unavailable");
        double cellWidth = modCellWidth(viewport.w(), density), gap = modGap(density), y = viewport.y() - context.scroll();
        context.painter().save(); context.painter().clip(viewport, 0);
        boolean begun = false;
        try {
            settings.beginFrame(context, viewport); begun = true; rowsActive = true;
            if (!cards.isEmpty()) {
                y = header(context, context.host().translate("oneconfig.search.mods_header", "MODS"), viewport.x(), y, 0, 4);
                y += LIST_GAP;
                int count = (cards.size() + MOD_COLUMNS - 1) / MOD_COLUMNS;
                for (int row = 0; row < count; row++) {
                    for (int column = 0; column < MOD_COLUMNS && row * MOD_COLUMNS + column < cards.size(); column++) {
                        LibraryMods.Card card = cards.get(row * MOD_COLUMNS + column);
                        Rect bounds = new Rect(viewport.x() + column * (cellWidth + gap), y, cellWidth, LibraryMods.CARD_HEIGHT);
                        if (bounds.intersects(viewport)) mods.draw(context, card, bounds);
                    }
                    y += LibraryMods.CARD_HEIGHT;
                    // LazyColumn's 8dp plus the non-last mod row's extra 11dp equals 19dp.
                    if (row + 1 < count) y += MOD_GAP;
                }
                if (!result.groups().isEmpty()) y += LIST_GAP;
            }
            int groupIndex = 0;
            for (Map.Entry<String, List<SearchDocuments.NarrowedRow>> group : result.groups().entrySet()) {
                if (groupIndex++ > 0) y += LIST_GAP;
                y = header(context, group.getKey().toUpperCase(Locale.ROOT), viewport.x(), y, 8, 2);
                for (SearchDocuments.NarrowedRow row : group.getValue()) {
                    y += LIST_GAP;
                    double height = settings.render(context, row, new Rect(viewport.x(), y, viewport.w(), 0));
                    if (!Double.isFinite(height) || height < 0) throw new IllegalStateException("Setting entry returned an invalid measured height");
                    y += height;
                }
            }
            context.scrollRange(Math.max(0, y + context.scroll() - viewport.bottom()));
        } finally {
            try { if (begun) settings.endFrame(context); } finally { context.painter().restore(); context.content(viewport); }
        }
    }
    private List<LibraryMods.Card> resolveCards(PageContext context, List<SearchIndex.Document<?>> documents) {
        if (documents.isEmpty()) return List.of();
        List<LibraryMods.Card> resolved = new ArrayList<>();
        for (SearchIndex.Document<?> document : documents) {
            LibraryMods.Card card = mods.fromDocument(context, document).orElseThrow(() -> new IllegalArgumentException("Search document has no registered real ModCard: " + document.id()));
            resolved.add(card);
        }
        return List.copyOf(resolved);
    }
    private double header(PageContext context, String text, double x, double y, double top, double bottom) {
        double line = typography.lineHeight(context, text, HEADER_SIZE, HEADER_WEIGHT);
        if (!Double.isFinite(line) || line <= 0) throw new IllegalStateException("Invalid global-search header metrics");
        List<String> lines = wrap(context, text, context.content().w(), HEADER_SIZE, HEADER_WEIGHT);
        for (int i = 0; i < lines.size(); i++) typography.draw(context, lines.get(i), x, y + top + i * line, HEADER_SIZE, HEADER_WEIGHT, context.theme().secondary());
        return y + top + lines.size() * line + bottom;
    }
    private List<String> wrap(PageContext context, String text, double width, double size, int weight) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\n", -1)) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            int start = 0;
            while (start < paragraph.length()) {
                int end = start, word = -1;
                while (end < paragraph.length()) {
                    int next = paragraph.offsetByCodePoints(end, 1);
                    if (typography.measure(context, paragraph.substring(start, next), size, weight) > Math.max(1, width) && end > start) break;
                    if (Character.isWhitespace(paragraph.codePointAt(end))) word = next;
                    end = next;
                }
                if (end < paragraph.length() && word > start) end = word;
                lines.add(paragraph.substring(start, end).stripTrailing()); start = end;
            }
        }
        return lines;
    }
    private void message(PageContext context, String text) {
        Rect content = context.content(); double line = typography.lineHeight(context, text, MESSAGE_SIZE, 400);
        List<String> lines = wrap(context, text, content.w(), MESSAGE_SIZE, 400);
        double measured = 0; for (String part : lines) measured = Math.max(measured, typography.measure(context, part, MESSAGE_SIZE, 400));
        double x = content.x() + (content.w() - measured) / 2, y = content.y() + (content.h() - lines.size() * line) / 2;
        context.painter().save(); context.painter().clip(content, 0);
        try { for (String part : lines) { typography.draw(context, part, x, y, MESSAGE_SIZE, 400, context.theme().secondary()); y += line; } }
        finally { context.painter().restore(); }
    }
    private void suspendRows(PageContext context) {
        // An empty frame relinquishes old editors while retaining each owner's existing control state.
        mods.leave(context);
        if (!rowsActive) return;
        settings.beginFrame(context, context.content());
        try { } finally { settings.endFrame(context); }
        rowsActive = false;
    }
    @Override public boolean pointerDown(PageContext context, int button) { return rowsActive && settings.pointerDown(context, button); }
    @Override public void pointerUp(PageContext context, int button) { if (rowsActive) settings.pointerUp(context, button); }
    @Override public boolean pointerMove(PageContext context) { return rowsActive && settings.pointerMove(context); }
    @Override public boolean wheel(PageContext context, double delta) { return rowsActive && settings.wheel(context, delta); }
    @Override public boolean key(PageContext context, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { return rowsActive && settings.key(context, key, typed, control, shift, alt); }
    @Override public boolean rawKey(PageContext context, int code, boolean pressed) { return rowsActive && settings.rawKey(context, code, pressed); }
    @Override public boolean recording() { return rowsActive && settings.recording(); }
    @Override public void leave(PageContext context) { settings.leave(context); mods.leave(context); rowsActive = false; }
}
