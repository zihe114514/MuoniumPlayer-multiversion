package com.muoniumplayer.configskija.ui.search;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.SearchDocuments;
import com.muoniumplayer.configskija.model.SearchIndex;
import com.muoniumplayer.configskija.ui.settings.SettingsSchema;
import java.util.List;
import java.util.Objects;

/** Optional bridge to the existing settings presentation schema; it never reads option values. */
public final class SettingsSearchAdapter implements SearchDocuments.SettingsAdapter {
    private final SettingsSchema schema;
    private final SearchDocuments.SettingsAdapter metadata;
    public SettingsSearchAdapter(SettingsSchema schema) { this(schema, SearchDocuments.DEFAULT); }
    public SettingsSearchAdapter(SettingsSchema schema, SearchDocuments.SettingsAdapter metadata) {
        this.schema = Objects.requireNonNull(schema); this.metadata = Objects.requireNonNull(metadata);
    }
    @Override public SearchDocuments.OptionData option(ConfigHost.Category category, ConfigHost.Group group, ConfigHost.Option option) {
        SearchDocuments.OptionData original = metadata.option(category, group, option);
        SettingsSchema.OptionStyle style = schema.option(option);
        return new SearchDocuments.OptionData(original.path(), original.nodeId(), original.tags(), original.indexed() && style.visualizer != null,
                original.keybind() || style.visualizer == SettingsSchema.Visualizer.KEYBIND);
    }
    @Override public SearchDocuments.GroupData group(ConfigHost.Category category, ConfigHost.Group group) {
        SettingsSchema.GroupStyle style = schema.group(group);
        SearchDocuments.GroupData original = metadata.group(category, group);
        return new SearchDocuments.GroupData(style.accordion(), style.head(), original.indexed(), original.tags());
    }
    @Override public boolean hidden(ConfigHost.Option option) { return metadata.hidden(option) || !schema.option(option).visible.get(); }
    @Override public String category(ConfigHost.Category category, ConfigHost.Group group) { return metadata.category(category, group); }
    @Override public String subcategory(ConfigHost.Category category, ConfigHost.Group group) { return metadata.subcategory(category, group); }
    /** Local fallback follows Property.matchesLocalSearch: any field, not concatenated words or query AND. */
    public static boolean matches(ConfigHost.Option option, String category, String subcategory, String query, int distance) {
        return any(query, distance, option.title(), option.id(), option.description(), category, subcategory);
    }
    public static boolean matches(ConfigHost.Option option, String category, String subcategory, String query) {
        return matches(option, category, subcategory, query, SearchIndex.DEFAULT_SEARCH_DISTANCE);
    }
    public static boolean any(String query, int distance, String... fields) {
        for (String field : fields) if (field != null && SearchIndex.matches(field, query, distance)) return true;
        return false;
    }
}
