package com.muoniumplayer.configskija.model;

import com.muoniumplayer.configskija.model.SearchIndex.Document;
import com.muoniumplayer.configskija.model.SearchIndex.Metadata;
import com.muoniumplayer.configskija.model.SearchIndex.Scope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Captures labels/metadata on the UI thread; the index worker never calls host option getters. */
public final class SearchDocuments {
    private SearchDocuments() {}
    public record Owner(String id, String title, String description, boolean searchable, boolean modCard) {
        public Owner { Objects.requireNonNull(id, "owner id"); }
        public Owner(String id, String title) { this(id, title, null, true, true); }
    }
    /** path is a persistent property path, never a localized title or list position. */
    public record OptionData(String path, String nodeId, List<String> tags, boolean indexed, boolean keybind) {
        public OptionData { Objects.requireNonNull(path, "option path"); tags = List.copyOf(tags); }
        public static OptionData of(ConfigHost.Option option) { return new OptionData(option.id(), option.id(), List.of(), true, false); }
    }
    public record GroupData(boolean accordion, ConfigHost.Option head, boolean indexed, List<String> tags) {
        public GroupData { tags = List.copyOf(tags); }
        public static GroupData plain() { return new GroupData(false, null, true, List.of()); }
    }
    public interface SettingsAdapter {
        default OptionData option(ConfigHost.Category category, ConfigHost.Group group, ConfigHost.Option option) { return OptionData.of(option); }
        default GroupData group(ConfigHost.Category category, ConfigHost.Group group) { return GroupData.plain(); }
        /** Runtime display visibility: hidden options remain indexed, but cannot become visible search rows. */
        default boolean hidden(ConfigHost.Option option) { return false; }
        default String category(ConfigHost.Category category, ConfigHost.Group group) { return category.title(); }
        default String subcategory(ConfigHost.Category category, ConfigHost.Group group) { return group.title(); }
    }
    public static final SettingsAdapter DEFAULT = new SettingsAdapter() {};

    /** Row equality stays identity-based, matching the original SearchRow and node-owner map. */
    public static final class Row {
        private final Owner owner;
        private final String category, subcategory, groupLabel;
        private final Object payload;
        private final ConfigHost.Option head;
        private final List<ConfigHost.Option> body;
        private final boolean accordion;
        private final SettingsAdapter adapter;
        private Row(Owner owner, String category, String subcategory, String groupLabel, Object payload,
                    ConfigHost.Option head, List<ConfigHost.Option> body, boolean accordion, SettingsAdapter adapter) {
            this.owner = owner; this.category = category; this.subcategory = subcategory; this.groupLabel = groupLabel;
            this.payload = payload; this.head = head; this.body = List.copyOf(body); this.accordion = accordion; this.adapter = adapter;
        }
        public Owner owner() { return owner; }
        public String category() { return category; }
        public String subcategory() { return subcategory; }
        public String groupLabel() { return groupLabel; }
        public Object payload() { return payload; }
        public ConfigHost.Option head() { return head; }
        public List<ConfigHost.Option> body() { return body; }
        public boolean accordion() { return accordion; }
        public String globalHeading() { String title = owner.title == null ? "Other" : owner.title; return groupLabel == null ? title : title + " / " + groupLabel; }
    }
    public record NarrowedRow(Row row, List<ConfigHost.Option> body, boolean whole) {
        public NarrowedRow { body = List.copyOf(body); }
    }
    public record Subcategory(String name, List<Row> rows) { public Subcategory { rows = List.copyOf(rows); } }
    public record Category(String name, List<Subcategory> subcategories) { public Category { subcategories = List.copyOf(subcategories); } }

    public static final class RowIndex {
        private final Map<Object, Row> owners;
        private final List<Category> categories;
        private RowIndex(Map<Object, Row> owners, List<Category> categories) {
            this.owners = Collections.unmodifiableMap(new IdentityHashMap<>(owners)); this.categories = List.copyOf(categories);
        }
        public Row rowOf(Document<?> document) { return owners.get(document.payload()); }
        public Row rowOfPayload(Object payload) { return owners.get(payload); }
        public List<Category> categories() { return categories; }
        public Map<Row, List<Document<?>>> group(List<Document<?>> documents) {
            LinkedHashMap<Row, List<Document<?>>> grouped = new LinkedHashMap<>();
            for (Document<?> document : documents) grouped.computeIfAbsent(rowOf(document), ignored -> new ArrayList<>()).add(document);
            LinkedHashMap<Row, List<Document<?>>> immutable = new LinkedHashMap<>();
            grouped.forEach((row, hits) -> immutable.put(row, List.copyOf(hits)));
            return Collections.unmodifiableMap(immutable);
        }
        /** Run on the owning UI thread; visibility suppliers may be live host state. */
        public NarrowedRow narrow(Row row, List<Document<?>> hits) {
            if (row == null || hits.isEmpty()) return null;
            if (!row.accordion) return row.adapter.hidden(row.body.get(0)) ? null : new NarrowedRow(row, row.body, true);
            boolean whole = hits.stream().anyMatch(document -> document.payload() == row.payload || document.payload() == row.head);
            ArrayList<ConfigHost.Option> body = new ArrayList<>();
            if (whole) body.addAll(row.body);
            else for (Document<?> document : hits) if (document.payload() instanceof ConfigHost.Option option && identityContains(row.body, option)) body.add(option);
            boolean visible = body.stream().anyMatch(option -> !row.adapter.hidden(option)) || whole && row.head != null && !row.adapter.hidden(row.head);
            return visible ? new NarrowedRow(row, body, whole) : null;
        }
        public Map<String, List<NarrowedRow>> globalGroups(List<Document<?>> hits) {
            LinkedHashMap<String, List<NarrowedRow>> groups = new LinkedHashMap<>();
            group(hits).forEach((row, documents) -> {
                NarrowedRow narrowed = narrow(row, documents);
                if (narrowed != null) groups.computeIfAbsent(row.globalHeading(), ignored -> new ArrayList<>()).add(narrowed);
            });
            LinkedHashMap<String, List<NarrowedRow>> immutable = new LinkedHashMap<>();
            groups.forEach((key, rows) -> immutable.put(key, List.copyOf(rows)));
            return Collections.unmodifiableMap(immutable);
        }
    }
    public record SettingsSnapshot(List<Document<?>> documents, RowIndex rows) implements SearchIndex.Source {
        public SettingsSnapshot { documents = List.copyOf(documents); Objects.requireNonNull(rows, "row index"); }
    }
    private record Draft(Object payload, ConfigHost.Option head, List<ConfigHost.Option> body, boolean accordion) {}

    public static SettingsSnapshot settings(String ownerId, String modTitle, List<ConfigHost.Category> categories) { return settings(new Owner(ownerId, modTitle), categories, DEFAULT); }
    public static SettingsSnapshot settings(String ownerId, String modTitle, List<ConfigHost.Category> categories, SettingsAdapter adapter) { return settings(new Owner(ownerId, modTitle), categories, adapter); }
    public static SettingsSnapshot settings(Owner owner, List<ConfigHost.Category> categories, SettingsAdapter adapter) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(adapter, "adapter");
        ArrayList<Document<?>> documents = new ArrayList<>();
        LinkedHashMap<String, LinkedHashMap<String, List<Draft>>> groups = new LinkedHashMap<>();
        if (owner.searchable && owner.modCard) documents.add(mod(owner.id, owner.title, owner.description, null, null, List.of(), owner));
        for (ConfigHost.Category category : List.copyOf(categories)) for (ConfigHost.Group group : List.copyOf(category.groups())) {
            GroupData presentation = Objects.requireNonNull(adapter.group(category, group));
            if (!presentation.indexed) continue;
            String categoryName = adapter.category(category, group), subcategory = adapter.subcategory(category, group);
            ArrayList<ConfigHost.Option> rendered = new ArrayList<>();
            if (presentation.accordion && presentation.indexed) {
                documents.add(new Document<>(settingId(owner.id, group.id()), optionScopes(owner, false), new Metadata(nonblank(group.title()), group.id(), null, null, nonblank(categoryName), nonblank(subcategory), presentation.tags, nonblank(owner.title), nonblank(owner.description), group.id()), group));
            }
            for (ConfigHost.Option option : List.copyOf(group.options())) {
                OptionData data = Objects.requireNonNull(adapter.option(category, group, option));
                if (!data.indexed || presentation.accordion && option == presentation.head) continue;
                rendered.add(option);
                documents.add(new Document<>(settingId(owner.id, data.path), optionScopes(owner, data.keybind), new Metadata(nonblank(option.title()), nonblank(data.nodeId), nonblank(option.description()), presentation.accordion ? nonblank(group.title()) : null, nonblank(categoryName), nonblank(subcategory), data.tags, nonblank(owner.title), nonblank(owner.description), data.path), option));
            }
            List<Draft> bucket = groups.computeIfAbsent(categoryName, ignored -> new LinkedHashMap<>()).computeIfAbsent(subcategory, ignored -> new ArrayList<>());
            if (presentation.accordion) { if (!rendered.isEmpty()) bucket.add(new Draft(group, presentation.head, List.copyOf(rendered), true)); }
            else for (ConfigHost.Option option : rendered) bucket.add(new Draft(option, null, List.of(option), false));
        }
        groups.values().forEach(subcategories -> subcategories.entrySet().removeIf(entry -> entry.getValue().isEmpty()));
        groups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        IdentityHashMap<Object, Row> owners = new IdentityHashMap<>();
        ArrayList<Category> grouped = new ArrayList<>();
        groups.forEach((category, subcategories) -> {
            ArrayList<Subcategory> children = new ArrayList<>();
            subcategories.forEach((subcategory, drafts) -> {
                String label = groups.size() != 1 ? category : subcategories.size() > 1 ? subcategory : null;
                ArrayList<Row> rows = new ArrayList<>();
                for (Draft draft : drafts) {
                    Row row = new Row(owner, category, subcategory, label, draft.payload, draft.head, draft.body, draft.accordion, adapter);
                    rows.add(row); owners.put(row.payload, row);
                    if (row.head != null) owners.put(row.head, row);
                    row.body.forEach(option -> owners.put(option, row));
                }
                children.add(new Subcategory(subcategory, rows));
            });
            grouped.add(new Category(category, children));
        });
        return new SettingsSnapshot(documents, new RowIndex(owners, grouped));
    }
    private static Set<Scope> optionScopes(Owner owner, boolean keybind) {
        LinkedHashSet<Scope> scopes = new LinkedHashSet<>(); scopes.add(Scope.config(owner.id));
        if (owner.searchable) scopes.add(Scope.OPTIONS); if (keybind) scopes.add(Scope.KEYBINDS); return scopes;
    }
    public static String settingId(String ownerId, String path) { return Objects.requireNonNull(ownerId, "owner id") + "::" + Objects.requireNonNull(path, "path"); }
    public static Document<?> mod(String ownerId, String title, String description, String category, String subcategory, List<String> aliases, Object payload) {
        return new Document<>("mod::" + ownerId, Set.of(Scope.MODS), new Metadata(nonblank(title), ownerId, nonblank(description), null, nonblank(category), nonblank(subcategory), aliases, null, null, null), payload);
    }
    public static Document<?> keybind(String ownerId, String path, Metadata metadata, Object payload) { return new Document<>(settingId(ownerId, path), Set.of(Scope.KEYBINDS), metadata, payload); }
    public static Document<?> hud(String typeName, String id, Metadata metadata, Object payload) { return new Document<>("hud::" + typeName + "::" + id, Set.of(Scope.HUDS), metadata, payload); }
    public static RowIndex combineRows(List<RowIndex> indexes) {
        IdentityHashMap<Object, Row> owners = new IdentityHashMap<>(); ArrayList<Category> categories = new ArrayList<>();
        for (RowIndex index : indexes) { owners.putAll(index.owners); categories.addAll(index.categories); }
        return new RowIndex(owners, categories);
    }
    private static boolean identityContains(List<ConfigHost.Option> options, ConfigHost.Option sought) { for (ConfigHost.Option option : options) if (option == sought) return true; return false; }
    private static String nonblank(String text) { return text == null || SearchIndex.trimQuery(text).isEmpty() ? null : text; }
}
