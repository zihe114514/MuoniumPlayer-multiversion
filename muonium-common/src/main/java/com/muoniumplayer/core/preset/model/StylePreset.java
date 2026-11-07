package com.muoniumplayer.core.preset.model;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable preset. Declared facets, not the supplied values, authorize changes. */
public final class StylePreset {
    public enum Source { BUILTIN, USER, BACKUP }
    public enum Base {
        DEFAULTS("defaults"), CURRENT("current");
        private final String id;
        Base(String id) { this.id = id; }
        public String id() { return id; }
        public static Base byId(String id) { return DEFAULTS.id.equals(id) ? DEFAULTS : CURRENT; }
    }

    private final PresetMeta meta;
    private final Base base;
    private final Set<Facet> facets;
    private final List<String> unknownFacets;
    private final Map<String, DomainFragment> domains;
    private final Source source;
    private final File file;
    /** Private rollback data, only repository BACKUP records may hold it; never in public PresetJson/share codes. */
    private final JsonObject localState;

    public StylePreset(PresetMeta meta, Base base, Set<Facet> facets, List<String> unknownFacets,
                       Map<String, DomainFragment> domains, Source source, File file) {
        this(meta, base, facets, unknownFacets, domains, source, file, null);
    }

    private StylePreset(PresetMeta meta, Base base, Set<Facet> facets, List<String> unknownFacets,
                       Map<String, DomainFragment> domains, Source source, File file, JsonObject localState) {
        if (meta == null) throw new IllegalArgumentException("meta");
        this.meta = meta;
        this.base = base == null ? Base.CURRENT : base;
        this.facets = Collections.unmodifiableSet(facets == null || facets.isEmpty()
                ? EnumSet.noneOf(Facet.class) : EnumSet.copyOf(facets));
        this.unknownFacets = unknownFacets == null ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(unknownFacets));
        this.domains = domains == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, DomainFragment>(domains));
        this.source = source == null ? Source.USER : source;
        this.file = file;
        this.localState = this.source == Source.BACKUP && localState != null ? localState.deepCopy() : null;
    }

    public PresetMeta meta() { return meta; }
    public Base base() { return base; }
    public Set<Facet> facets() { return facets; }
    public List<String> unknownFacets() { return unknownFacets; }
    public Map<String, DomainFragment> domains() { return domains; }
    public Source source() { return source; }
    public File file() { return file; }
    public JsonObject localState() { return localState == null ? null : localState.deepCopy(); }
    public StylePreset withLocalState(JsonObject state) {
        if (source != Source.BACKUP) throw new IllegalStateException("完整本地状态仅供备份");
        return new StylePreset(meta, base, facets, unknownFacets, domains, source, file, state);
    }
    public StylePreset withMeta(PresetMeta newMeta) {
        return new StylePreset(newMeta, base, facets, unknownFacets, domains, source, file, localState);
    }
    public StylePreset located(Source newSource, File newFile) {
        return new StylePreset(meta, base, facets, unknownFacets, domains, newSource, newFile, localState);
    }
    public boolean touches(Facet.Group group) {
        for (Facet facet : facets) if (facet.group() == group) return true;
        return false;
    }
}
