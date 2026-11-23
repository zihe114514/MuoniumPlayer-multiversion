package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.Navigation;
import com.muoniumplayer.configskija.model.SearchIndex;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;
import static com.muoniumplayer.configskija.ui.settings.SettingsSchema.*;

/** Settings and favorites, laid out in the original Shell's dp coordinate space. */
public final class SettingsPage implements UiPage {
    private record RowTarget(ConfigHost.Option option, Rect bounds) {}
    private final boolean favorites;
    private final SettingsSchema supplied;
    private final TextInputController localInput = new TextInputController();
    private final SettingsMotion motion = new SettingsMotion();
    private final SettingsControls controls = new SettingsControls(this);
    private final Map<String, Boolean> expanded = new HashMap<>();
    private final List<RowTarget> rows = new ArrayList<>();
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private Rect rootViewport, listViewport, clip;
    private boolean dragging, disposed, transaction, ownerLayer;
    private long epoch, dragSequence;
    private String routeKey = "";
    SettingsSchema schema = SettingsSchema.DEFAULT;
    private static final class Field { final TextEdit edit = new TextEdit(); String source = ""; boolean seen; }
    public SettingsPage(boolean favorites) { this(favorites, null); }
    public SettingsPage(boolean favorites, SettingsSchema presentation) { this.favorites = favorites; this.supplied = presentation; }

    @Override public void render(PageContext ctx) {
        disposed = false;
        if (!(ctx instanceof TextInputController.TextInput)) localInput.beginFrame(ctx);
        schema = supplied != null ? supplied : ctx.host() instanceof Provider provider ? Objects.requireNonNull(provider.settingsSchema()) : SettingsSchema.DEFAULT;
        String key = ctx.route().key();
        if (!key.equals(routeKey)) { input(ctx).blur(ctx); controls.routeChanged(ctx); finish(ctx, false); routeKey = key; epoch++; }
        fields.values().forEach(f -> f.seen = false); rows.clear(); controls.beginFrame();
        Rect offered = ctx.content();
        if (rootViewport == null || !offered.equals(listViewport)) rootViewport = offered;
        Rect root = rootViewport;
        List<ConfigHost.Category> categories = favorites ? ctx.host().favoriteCategories() : ctx.host().categories();
        String query = ctx.query() == null ? "" : ctx.query().trim();
        ConfigHost.Category selected = selected(categories, ctx.route().category());
        double top = root.y();
        if (query.isEmpty() && categories.size() > 1) {
            double x = root.x(), y = top;
            for (ConfigHost.Category category : categories) {
                double width = ctx.painter().measure(category.title(), 14, false) + 24;
                if (x > root.x() && x + width > root.right()) { x = root.x(); y += 41; }
                Rect chip = new Rect(x, y, width, 33); boolean checked = category == selected;
                double on = spring(ctx, "settings.chip." + category.id(), checked ? 1 : 0);
                int background = SettingsDraw.hover(ctx, chip) ? SettingsDraw.over(Theme.alpha(ctx.accent(), .5), ctx.theme().chip()) : ctx.theme().chip();
                background = SettingsDraw.over(Theme.alpha(ctx.accent(), on), background);
                SettingsDraw.frame(ctx, chip, 8, background, ctx.theme().border());
                SettingsDraw.center(ctx, category.title(), chip, 14, false, Theme.mix(ctx.theme().text(), ctx.theme().accentText(), on));
                ctx.hit("settings.category." + category.id(), chip, () -> { controls.routeChanged(ctx); ctx.navigate(new Navigation.Route(favorites ? Navigation.Page.FAVORITES : Navigation.Page.SETTINGS, category.id())); }, "");
                x += width + 8;
            }
            top = y + 33 + 19;
        }
        listViewport = new Rect(root.x(), top, root.w(), Math.max(0, root.bottom() - top)); ctx.content(listViewport); clip = listViewport;
        List<ConfigHost.Category> shown = categories.stream().filter(c -> !query.isEmpty() || c == selected).filter(c -> c.groups().stream().anyMatch(g -> displayGroup(ctx, c, g, query))).toList();
        double y = top - ctx.scroll(); int count = 0;
        ctx.painter().save(); ctx.painter().clip(listViewport, 0);
        try {
            for (ConfigHost.Category category : shown) {
                if (!query.isEmpty() && shown.size() > 1) { y = heading(ctx, category.title(), y, 11, ctx.theme().secondary()); }
                List<ConfigHost.Group> groups = category.groups().stream().filter(g -> displayGroup(ctx, category, g, query)).toList();
                for (ConfigHost.Group group : groups) {
                    List<ConfigHost.Option> options = visible(ctx, category, group, query); GroupStyle style = schema.group(group);
                    if (style.accordion()) y = accordion(ctx, group, style, options, y);
                    else {
                        if (!group.title().isBlank() && (groups.size() > 1 || !group.title().equalsIgnoreCase("General"))) y = heading(ctx, group.title(), y, 22, ctx.theme().text());
                        for (ConfigHost.Option option : options) { OptionStyle os = schema.option(option); double height = rowHeight(ctx, option, os, listViewport.w(), false); Rect row = new Rect(root.x(), y, root.w(), height); drawRow(ctx, option, os, row, false); y += height + 8; count++; }
                    }
                    count += style.accordion() ? Math.max(1, options.size()) : 0;
                }
            }
            if (count == 0) {
                String message = query.isEmpty() ? "No settings available." : "No settings match \"" + query + "\"";
                SettingsDraw.center(ctx, message, listViewport, 14, false, ctx.theme().secondary());
            }
        } finally { ctx.painter().restore(); clip = listViewport; }
        ctx.scrollRange(Math.max(0, y + ctx.scroll() - top - (count > 0 ? 8 : 0) - listViewport.h()));
        if (!(ctx instanceof TextInputController.TextInput)) localInput.endFrame(ctx);
        fields.values().forEach(f -> { if (!f.seen) f.edit.focused = false; });
        fields.values().removeIf(f -> !f.seen);
    }
    void beginRows(PageContext ctx, SettingsSchema presentation, Rect viewport) {
        disposed = false; schema = presentation; rootViewport = listViewport = clip = viewport;
        fields.values().forEach(f -> f.seen = false); rows.clear(); controls.beginFrame();
    }
    double renderEntry(PageContext ctx, com.muoniumplayer.configskija.model.SearchDocuments.NarrowedRow narrowed, Rect offered) {
        com.muoniumplayer.configskija.model.SearchDocuments.Row row = narrowed.row();
        List<ConfigHost.Option> body = narrowed.body().stream().filter(o -> o != row.head() && Boolean.TRUE.equals(schema.option(o).visible.get())).toList();
        if (!row.accordion()) {
            if (body.isEmpty()) return 0; ConfigHost.Option option = body.get(0); OptionStyle style = schema.option(option);
            double height = rowHeight(ctx, option, style, offered.w(), false); drawRow(ctx, option, style, new Rect(offered.x(), offered.y(), offered.w(), height), false); return height;
        }
        if (body.isEmpty() && (row.head() == null || !Boolean.TRUE.equals(schema.option(row.head()).visible.get()))) return 0;
        ConfigHost.Group group = row.payload() instanceof ConfigHost.Group actual ? actual : new ConfigHost.Group(row.owner().id() + "/" + row.category() + "/" + row.subcategory(), row.subcategory(), row.body());
        GroupStyle provided = schema.group(group); GroupStyle style = new GroupStyle(true, provided.collapsed(), row.head(), provided.description(), provided.icon());
        Rect saved = listViewport; listViewport = new Rect(offered.x(), saved.y(), offered.w(), saved.h());
        try { return accordion(ctx, group, style, body, offered.y()) - offered.y() - 8; }
        finally { listViewport = saved; }
    }
    void endRows() { fields.values().forEach(f -> { if (!f.seen) f.edit.focused = false; }); fields.values().removeIf(f -> !f.seen); }
    void suspendRows(PageContext ctx) { finish(ctx, true); controls.routeChanged(ctx); disposed = true; fields.values().forEach(f -> f.edit.focused = false); rows.clear(); }
    private static ConfigHost.Category selected(List<ConfigHost.Category> categories, String route) {
        return categories.stream().filter(c -> c.id().equalsIgnoreCase(route == null ? "" : route) || c.title().equalsIgnoreCase(route == null ? "" : route)).findFirst().orElse(categories.isEmpty() ? null : categories.get(0));
    }
    private List<ConfigHost.Option> visible(PageContext ctx, ConfigHost.Category category, ConfigHost.Group group, String query) {
        return group.options().stream().filter(o -> !schema.group(group).accordion() || o != schema.group(group).head()).filter(o -> Boolean.TRUE.equals(schema.option(o).visible.get())).filter(o -> matches(o, category, group, query, searchDistance(ctx)) || accordionMatches(schema.group(group), group, query, searchDistance(ctx))).toList();
    }
    private boolean displayGroup(PageContext ctx, ConfigHost.Category category, ConfigHost.Group group, String query) {
        if (!visible(ctx, category, group, query).isEmpty()) return true;
        GroupStyle style = schema.group(group); ConfigHost.Option head = style.head();
        return style.accordion() && head != null && Boolean.TRUE.equals(schema.option(head).visible.get())
            && (query.isEmpty() || matches(head, category, group, query, searchDistance(ctx)) || accordionMatches(style, group, query, searchDistance(ctx)));
    }
    private static boolean accordionMatches(GroupStyle style, ConfigHost.Group group, String query, int distance) {
        if (!style.accordion() || query.isBlank()) return false;
        return SearchIndex.matches(group.title(), query, distance) || SearchIndex.matches(group.id(), query, distance)
            || SearchIndex.matches(style.description(), query, distance) || style.head() != null && (
                SearchIndex.matches(style.head().title(), query, distance) || SearchIndex.matches(style.head().id(), query, distance)
                || SearchIndex.matches(style.head().description(), query, distance));
    }
    private static int searchDistance(PageContext ctx) { return Math.max(0, (int)ctx.preferences().number("searchDistance", SearchIndex.DEFAULT_SEARCH_DISTANCE)); }
    public static boolean matches(ConfigHost.Option option, ConfigHost.Category category, ConfigHost.Group group, String query) { return matches(option, category, group, query, SearchIndex.DEFAULT_SEARCH_DISTANCE); }
    public static boolean matches(ConfigHost.Option option, ConfigHost.Category category, ConfigHost.Group group, String query, int distance) {
        String q = SearchIndex.trimQuery(query);
        if (q.isEmpty()) return true;
        return SearchIndex.matches(option.title(), q, distance) || SearchIndex.matches(option.id(), q, distance)
            || SearchIndex.matches(option.description(), q, distance) || SearchIndex.matches(category.title(), q, distance)
            || SearchIndex.matches(group.title(), q, distance);
    }
    private double heading(PageContext ctx, String text, double y, double size, int color) {
        double h = schema.lineHeight(ctx, size, false); if (new Rect(listViewport.x(), y, listViewport.w(), h + 10).intersects(listViewport)) ctx.painter().text(text, listViewport.x(), y + 8, size, false, color); return y + 8 + h + 2 + 8;
    }
    private double accordion(PageContext ctx, ConfigHost.Group group, GroupStyle style, List<ConfigHost.Option> options, double y) {
        boolean open = expanded.computeIfAbsent(group.id(), id -> !style.collapsed() && (style.head() == null || Boolean.TRUE.equals(style.head().value())));
        double textWidth = listViewport.w() - 32 - 16 - 12 - (style.head() == null ? 0 : 80) - (style.icon().isBlank() ? 0 : 44);
        double labelHeight = labelHeight(ctx, group.title(), style.description(), textWidth, false), headerHeight = 24 + Math.max(labelHeight, style.icon().isBlank() ? 21 : 32);
        double column = (listViewport.w() - 8) / 2; List<List<ConfigHost.Option>> grid = new ArrayList<>(); ConfigHost.Option pending = null;
        for (ConfigHost.Option option : options) {
            OptionStyle os = schema.option(option); boolean wide = ctx.painter().measure(option.title(), 15, false) + reserved(option, os) > column;
            if (wide) { if (pending != null) { grid.add(List.of(pending)); pending = null; } grid.add(List.of(option)); }
            else if (pending == null) pending = option; else { grid.add(List.of(pending, option)); pending = null; }
        }
        if (pending != null) grid.add(List.of(pending));
        double body = 12; for (List<ConfigHost.Option> line : grid) { boolean wide = line.size() == 1 && ctx.painter().measure(line.get(0).title(), 15, false) + reserved(line.get(0), schema.option(line.get(0))) > column; double w = wide ? listViewport.w() : column; body += line.stream().mapToDouble(o -> rowHeight(ctx, o, schema.option(o), w, true)).max().orElse(0) + 8; } if (!grid.isEmpty()) body -= 8;
        double factor = Geometry.clamp(motion.value(ctx, "settings.accordion." + group.id(), open ? 1 : 0, 400, 1, .001), 0, 1);
        Rect card = new Rect(listViewport.x(), y, listViewport.w(), headerHeight + body * factor);
        if (card.intersects(listViewport)) {
            boolean previousOwnerLayer = ownerLayer; ownerLayer = schema.locked();
            ctx.painter().layer(card, ownerLayer ? .45 : 1);
            try {
            card(ctx, card, 1, true);
            Rect header = new Rect(card.x(), card.y(), card.w(), headerHeight);
            ctx.painter().layer(header, style.head() != null && !style.head().enabled() ? .65 : 1);
            try {
            String desc = Objects.requireNonNullElse(style.description(), "");
            double tx = card.x() + 16; if (!style.icon().isBlank()) { ctx.painter().icon(style.icon(), tx, y + (headerHeight - 32) / 2, 32, ctx.theme().text()); tx += 44; }
            label(ctx, group.title(), desc, tx, y + (headerHeight - labelHeight) / 2, textWidth, false, 1);
            if (!schema.locked()) hit(ctx, "settings.accordion." + group.id(), header, () -> expanded.put(group.id(), !expanded.get(group.id())), "");
            double angle = spring(ctx, "settings.accordion.arrow." + group.id(), open ? 180 : 90);
            SettingsDraw.chevron(ctx, card.right() - 22, y + headerHeight / 2, 12, angle, ctx.theme().secondary());
            if (style.head() != null) { ConfigHost.Option head = style.head(); Rect toggle = new Rect(card.right() - 112, y + (headerHeight - 21) / 2, 66, 21); controls.toggle(ctx, head, schema.option(head), toggle, value -> { write(ctx, head, value); expanded.put(group.id(), value); }); }
            } finally { ctx.painter().restore(); }
            Rect saved = clip; clip = intersection(clip, new Rect(card.x(), y + headerHeight, card.w(), body * factor));
            ctx.painter().save(); ctx.painter().clip(clip, 0);
            try {
                Rect reverseBorder = new Rect(card.x(), y + headerHeight, card.w(), body * factor);
                ctx.painter().gradientOutline(reverseBorder, ctx.theme().radius(16), 1, ctx.theme().border() & 0xffffff, ctx.theme().border());
                double by = y + headerHeight - body * (1 - factor);
                for (List<ConfigHost.Option> line : grid) { boolean wide = line.size() == 1 && ctx.painter().measure(line.get(0).title(), 15, false) + reserved(line.get(0), schema.option(line.get(0))) > column; double w = wide ? card.w() : column;
                    double h = line.stream().mapToDouble(o -> rowHeight(ctx, o, schema.option(o), w, true)).max().orElse(0);
                    for (int i = 0; i < line.size(); i++) { ConfigHost.Option option = line.get(i); drawRow(ctx, option, schema.option(option), new Rect(card.x() + i * (column + 8), by, w, h), true); } by += h + 8;
                }
            } finally { ctx.painter().restore(); clip = saved; }
            } finally { ctx.painter().restore(); ownerLayer = previousOwnerLayer; }
        }
        return y + card.h() + 8;
    }
    private double reserved(ConfigHost.Option option, OptionStyle style) {
        double trailing = switch(style.visualizer) { case SLIDER -> 96; case RANGE_SLIDER, INHERITABLE_SLIDER -> 192; default -> 0; };
        return (trailing > 0 ? 300 + trailing + 48 : 108) + (style.icon.isBlank() ? 0 : 44);
    }
    private boolean stacked(PageContext ctx, ConfigHost.Option option, OptionStyle style, double width) { return style.fullWidth || ctx.painter().measure(option.title(), 15, false) + reserved(option, style) > width; }
    private double labelHeight(PageContext ctx, String title, String description, double width, boolean nested) {
        double h = SettingsDraw.wrap(ctx.painter(), title, Math.max(1, width), 15, false).size() * schema.lineHeight(ctx, 15, false);
        if (!nested && description != null && !description.isEmpty()) h += 2 + SettingsDraw.wrap(ctx.painter(), description, Math.max(1, width), 13, false).size() * schema.lineHeight(ctx, 13, false);
        return h;
    }
    private double rowHeight(PageContext ctx, ConfigHost.Option option, OptionStyle style, double width, boolean nested) {
        double controlWidth = controls.width(ctx, option, style, Math.max(1, width - 32));
        double ch = controls.height(ctx, option, style, Math.max(1, width - 32));
        if (style.visualizer == Visualizer.INFO) return (nested ? 0 : 24) + controls.infoHeight(ctx, option, width - 32);
        boolean stack = stacked(ctx, option, style, width); double labelWidth = width - 32 - (style.icon.isBlank() ? 0 : 44) - (stack ? style.fullWidth && showActions(ctx, option) ? 36 : 0 : controlWidth + (showActions(ctx, option) ? 56 : 16));
        double lh = Math.max(style.icon.isBlank() ? 0 : 32, labelHeight(ctx, option.title(), option.description(), labelWidth, nested));
        return (nested ? stack ? 8 : 0 : 24) + (stack ? lh + 10 + ch : Math.max(lh, ch));
    }
    private boolean showActions(PageContext ctx, ConfigHost.Option option) { return ctx.preferences().flag("showOptionActionButtons", true) && available(option); }
    private void drawRow(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, boolean nested) {
        if (!r.intersects(clip)) return;
        boolean available = available(option); double alpha = (schema.locked() && !ownerLayer && !option.id().equals(schema.lockExemptId()) ? .45 : 1) * (option.enabled() ? 1 : .65);
        ctx.painter().layer(r, alpha);
        try { alpha = 1;
        if (!nested) card(ctx, r, alpha); rows.add(new RowTarget(option, intersection(r, clip)));
        if (style.visualizer == Visualizer.INFO) { controls.info(ctx, option, style, new Rect(r.x() + 16, r.y() + (nested ? 0 : 12), r.w() - 32, r.h() - (nested ? 0 : 24)), alpha); return; }
        double controlWidth = controls.width(ctx, option, style, r.w() - 32), ch = controls.height(ctx, option, style, r.w() - 32); boolean stack = stacked(ctx, option, style, r.w());
        double padding = nested ? 0 : 12, labelWidth = r.w() - 32 - (style.icon.isBlank() ? 0 : 44) - (stack ? style.fullWidth && showActions(ctx, option) ? 36 : 0 : controlWidth + (showActions(ctx, option) ? 56 : 16));
        double lh = Math.max(style.icon.isBlank() ? 0 : 32, labelHeight(ctx, option.title(), option.description(), labelWidth, nested));
        double ly = stack ? r.y() + padding : r.y() + (r.h() - lh) / 2;
        double tx = r.x() + 16; if (!style.icon.isBlank()) { ctx.painter().icon(style.icon, tx, ly + (lh - 32) / 2, 32, SettingsDraw.opacity(ctx.theme().text(), alpha)); tx += 44; }
        label(ctx, option.title(), option.description(), tx, ly, Math.max(1, labelWidth), nested, alpha);
        double cy = stack ? ly + lh + 10 : r.y() + (r.h() - ch) / 2;
        Rect cr = new Rect(r.right() - 16 - controlWidth, cy, controlWidth, ch);
        if (style.fullWidth) cr = new Rect(r.x() + 16, cy, r.w() - 32, ch);
        controls.render(ctx, option, style, cr, alpha);
        Rect actions = new Rect(style.fullWidth ? r.right() - 40 : cr.x() - (stack ? 36 : 40), style.fullWidth ? ly : cy + (ch - 24) / 2, 24, 24);
        boolean show = showActions(ctx, option);
        if (show && SettingsDraw.hover(ctx, r)) { ctx.painter().icon("dots-vertical", actions.x() + 4, actions.y() + 4, 16, ctx.theme().secondary()); }
        if (show) hit(ctx, "settings.actions." + option.id(), actions, () -> ctx.overlay(new OptionMenu(this, option, actions, false)), "More actions. Right-click option as shortcut.");
        if (nested && option.description() != null && !option.description().isEmpty()) ctx.tooltip("settings.description." + option.id(), intersection(new Rect(tx, ly, Math.max(1, labelWidth), lh), clip), option.description());
        if (!available) ctx.tooltip("settings.disabled." + option.id(), intersection(r, clip), reason(option));
        } finally { ctx.painter().restore(); }
    }
    private void label(PageContext ctx, String title, String desc, double x, double y, double width, boolean nested, double alpha) {
        double h = SettingsDraw.text(ctx, schema, title, x, y, width, 15, false, SettingsDraw.opacity(ctx.theme().text(), alpha));
        if (!nested && desc != null && !desc.isEmpty()) SettingsDraw.text(ctx, schema, desc, x, y + h + 2, width, 13, false, SettingsDraw.opacity(ctx.theme().secondary(), alpha));
    }
    private void card(PageContext ctx, Rect r, double alpha) { card(ctx, r, alpha, false); }
    private void card(PageContext ctx, Rect r, double alpha, boolean accordion) {
        int border = SettingsDraw.opacity(ctx.theme().border(), alpha); ctx.painter().rect(r, ctx.theme().radius(16), SettingsDraw.opacity(ctx.theme().card(), alpha));
        ctx.painter().gradientOutline(r, ctx.theme().radius(16), 1, border, border & 0xffffff);
        ctx.painter().save(); ctx.painter().clip(r, ctx.theme().radius(16));
        try { ctx.painter().vignette(r, r.x() + r.w() / 2, accordion ? r.y() : r.y() + r.h() / 2, accordion ? r.w() * .8 : Math.min(r.w(), r.h()) * .9, SettingsDraw.opacity(ctx.theme().text(), alpha)); }
        finally { ctx.painter().restore(); }
    }

    boolean available(ConfigHost.Option option) { return !disposed && Boolean.TRUE.equals(schema.option(option).visible.get()) && option.enabled() && (!schema.locked() || option.id().equals(schema.lockExemptId())); }
    String reason(ConfigHost.Option option) { String reason = option.disabledReason().get(); return reason.isEmpty() && schema.locked() ? "This mod is switched off." : reason; }
    void write(PageContext ctx, ConfigHost.Option option, Object value) { if (available(option)) { if (transaction) option.set(value); else ctx.edit(option, value); } }
    void beginTransaction(PageContext ctx, ConfigHost.Option option) { if (transaction) throw new IllegalStateException("Settings transaction already active"); ctx.beginEdit(option); transaction = true; }
    void commitTransaction(PageContext ctx) { if (transaction) { transaction = false; ctx.commitEdit(); } }
    void cancelTransaction(PageContext ctx) { if (transaction) { transaction = false; ctx.cancelEdit(); } }
    void hit(PageContext ctx, String id, Rect bounds, Runnable action, String tooltip) { Rect r = intersection(bounds, clip == null ? ctx.shell() : clip); if (r.w() > 0 && r.h() > 0) ctx.contentHit(id, r, action, tooltip); }
    void optionHit(PageContext ctx, ConfigHost.Option option, String suffix, Rect bounds, Runnable action) { if (available(option)) hit(ctx, "settings." + suffix + "." + option.id(), bounds, () -> { if (available(option)) { input(ctx).blur(ctx); action.run(); } }, ""); }
    static Rect intersection(Rect a, Rect b) { double x = Math.max(a.x(), b.x()), y = Math.max(a.y(), b.y()); return new Rect(x, y, Math.max(0, Math.min(a.right(), b.right()) - x), Math.max(0, Math.min(a.bottom(), b.bottom()) - y)); }
    void drag(PageContext ctx, ConfigHost.Option option, BiConsumer<Double, Double> update) { drag(ctx, option, update, () -> {}); }
    void drag(PageContext ctx, ConfigHost.Option option, BiConsumer<Double, Double> update, Runnable completed) {
        if (!available(option)) return; finish(ctx, true); beginTransaction(ctx, option); dragging = true; long ticket = epoch, sequence = ++dragSequence;
        BiConsumer<Double, Double> guarded = (x, y) -> { if (!dragging || disposed || ticket != epoch || sequence != dragSequence) return; if (!available(option)) { finish(ctx, false); return; } update.accept(x, y); };
        ctx.startDrag(guarded, () -> { if (dragging && ticket == epoch && sequence == dragSequence) { completed.run(); finish(ctx, true); } }); guarded.accept(ctx.mouseX(), ctx.mouseY());
    }
    double spring(PageContext ctx, String key, double target) { return motion.value(ctx, key, target, 1500, 1, .01); }
    void captureDone() { controls.stopCapture(); }
    void withClip(Rect bounds, Runnable draw) { Rect saved = clip; clip = intersection(clip, bounds); try { draw.run(); } finally { clip = saved; } }
    String fieldText(String id, String value) { Field field = fields.get(id); return field != null && (field.edit.focused || Objects.equals(field.source, value)) ? field.edit.text() : value; }
    boolean fieldFocused(String id) { Field field = fields.get(id); return field != null && field.edit.focused; }
    private void finish(PageContext ctx, boolean commit) { if (!dragging) return; dragging = false; dragSequence++; if (commit) commitTransaction(ctx); else { cancelTransaction(ctx); controls.cancelDrags(); } }
    void field(PageContext ctx, ConfigHost.Option option, String id, Rect bounds, String value, String placeholder, UnaryOperator<String> filter, Consumer<String> changed) { field(ctx, option, id, bounds, value, placeholder, filter, changed, null); }
    void field(PageContext ctx, ConfigHost.Option option, String id, Rect bounds, String value, String placeholder, UnaryOperator<String> filter, Consumer<String> changed, Consumer<String> ended) {
        TextInputController.Style style = new TextInputController.Style(id.startsWith("settings.text.") ? 14 : 13);
        style.multiline = id.startsWith("settings.text.") && schema.option(option).multiline;
        field(ctx, option, id, bounds, value, placeholder, filter, changed, ended, style);
    }
    void field(PageContext ctx, ConfigHost.Option option, String id, Rect bounds, String value, String placeholder, UnaryOperator<String> filter, Consumer<String> changed, Consumer<String> ended, TextInputController.Style style) {
        Field f = fields.computeIfAbsent(id, unused -> new Field()); f.seen = true;
        f.edit.preserveSingleLineBreaks(true); f.edit.multiline(style.multiline); f.edit.limit(style.maxLength);
        if (!f.edit.focused && !Objects.equals(f.source, value)) { f.edit.text(value); f.source = value; }
        style.enabled = available(option); style.placeholder = placeholder; style.filter = filter;
        style.path = schema.option(option).visualizer == Visualizer.FILE || schema.option(option).visualizer == Visualizer.FILE_LIST;
        style.lineHeight = schema.lineHeight(ctx, style.fontSize, false); style.clip = intersection(clip == null ? ctx.shell() : clip, style.clip == null ? ctx.shell() : style.clip);
        Consumer<String> onChange = text -> { if (available(option)) changed.accept(text); };
        Runnable onBlur = () -> { if (ended != null && available(option)) ended.accept(f.edit.text()); };
        if (ctx instanceof TextInputController.TextInput input) input.bareTextField(id, f.edit, bounds, style, onChange, onBlur);
        else localInput.field(ctx, id, f.edit, bounds, style, onChange, onBlur);
    }
    TextInputController input(PageContext ctx) { return ctx instanceof TextInputController.TextInput input ? input.textInputController() : localInput; }
    /** Fallback integration for hosts forwarding pointer movement directly to SettingsPage. */
    public boolean pointerMove(PageContext ctx) { return input(ctx).pointerMove(ctx); }

    @Override public boolean pointerDown(PageContext ctx, int button) {
        if (input(ctx).pointerDown(ctx, button)) return true;
        if (controls.pointerDown(ctx, button)) return true;
        if (button != 1) return false;
        for (int i = rows.size() - 1; i >= 0; i--) { RowTarget row = rows.get(i); if (row.bounds().contains(ctx.mouseX(), ctx.mouseY()) && available(row.option())) { ctx.overlay(new OptionMenu(this, row.option(), new Rect(ctx.mouseX(), ctx.mouseY(), 0, 0), true)); return true; } }
        return false;
    }
    @Override public void pointerUp(PageContext ctx, int button) { input(ctx).pointerUp(ctx, button); controls.pointerUp(ctx, button); }
    @Override public boolean wheel(PageContext ctx, double delta) { return input(ctx).wheel(ctx, delta) || controls.wheel(ctx, delta); }
    @Override public boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { if (input(ctx).key(ctx, key, typed, control, shift, alt)) return true; if (dragging && key == ConfigView.Key.ESCAPE) { finish(ctx, false); return true; } return controls.key(ctx, key, typed, control, shift, alt); }
    public boolean recording() { return controls.recording(); }
    /** Feed actual platform codes, including releases, before shell shortcuts when a keybind is recording. */
    public boolean rawKey(PageContext ctx, int code, boolean pressed) { return controls.rawKey(ctx, code, pressed); }
    @Override public void leave(PageContext ctx) { input(ctx).blur(ctx); controls.routeChanged(ctx); finish(ctx, true); fields.values().forEach(f -> f.edit.focused = false); rows.clear(); epoch++; disposed = true; }
}
