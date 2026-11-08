package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.preset.codec.JsonPaths;
import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.preset.model.StylePreset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/** Main-thread preset engine: prepare every domain first, then commit or restore every attempted domain. */
public final class PresetEngine {
    private final List<PresetDomain> domains;
    public PresetEngine(List<PresetDomain> domains) {
        if (domains == null) throw new IllegalArgumentException("domains");
        Set<String> ids = new java.util.HashSet<String>();
        for (PresetDomain domain : domains)
            if (domain == null || !ids.add(domain.id())) throw new IllegalArgumentException("重复配置域");
        this.domains = Collections.unmodifiableList(new ArrayList<PresetDomain>(domains));
    }
    public List<PresetDomain> domains() { return domains; }
    public Set<Facet> availableFacets() {
        EnumSet<Facet> out = EnumSet.noneOf(Facet.class);
        for (PresetDomain domain : domains) out.addAll(domain.policy().facets());
        return out;
    }
    /** Shareable capture: only explicitly included keys. Local/private values never leave this boundary. */
    public Map<String, DomainFragment> capture(Set<Facet> facets) {
        Map<String, DomainFragment> out = new LinkedHashMap<String, DomainFragment>();
        for (PresetDomain domain : domains) {
            KeyPolicy policy = domain.policy();
            Set<Facet> mine = intersect(facets, policy.facets());
            if (mine.isEmpty()) continue;
            Map<String, JsonElement> picked = new LinkedHashMap<String, JsonElement>();
            for (Map.Entry<String, JsonElement> entry : JsonPaths.flatten(domain.snapshot(), policy::isRegistered).entrySet()) {
                KeyPolicy.Rule rule = policy.rule(entry.getKey());
                if (rule != null && !rule.excluded() && mine.contains(rule.facet()))
                    picked.put(entry.getKey(), entry.getValue());
            }
            if (!picked.isEmpty()) out.put(domain.id(), new DomainFragment(domain.fragmentVersion(), JsonPaths.unflatten(picked)));
        }
        return out;
    }
    /** Complete trusted local music settings, including exclusions. For Profiles/rollback ONLY, not sharing. */
    public JsonObject captureState() {
        JsonObject state = new JsonObject();
        for (PresetDomain domain : domains) state.add(domain.id(), domain.captureState().deepCopy());
        return state;
    }
    /** Complete local snapshot of only domains involved in these facets; never a shareable capture. */
    public JsonObject captureState(Set<Facet> facets) {
        JsonObject state = new JsonObject();
        for (PresetDomain domain : domains)
            if (!intersect(facets, domain.policy().facets()).isEmpty()) state.add(domain.id(), domain.captureState().deepCopy());
        return state;
    }

    /** Restore trusted local state transactionally; missing domains/fields retain current state. */
    public CompatReport restoreState(JsonObject state) {
        CompatReport report = new CompatReport();
        List<Change> changes = new ArrayList<Change>();
        if (state == null) return report;
        for (String id : state.keySet()) if (find(id) == null) report.warn("本版本不认识配置「" + id + "」，已保留当前状态");
        for (PresetDomain domain : domains) {
            JsonElement incoming = state.get(domain.id());
            if (incoming == null) continue;
            try {
                if (!incoming.isJsonObject()) throw new IllegalArgumentException("配置状态必须是对象");
                JsonObject before = domain.captureState().deepCopy();
                JsonObject next = JsonPaths.merge(before, incoming.getAsJsonObject());
                int count = countChanges(JsonPaths.flatten(before, domain.policy()::isRegistered),
                        JsonPaths.flatten(next, domain.policy()::isRegistered));
                if (!before.equals(next)) changes.add(new Change(domain, before, next, Math.max(1, count), true));
                report.changed(domain.id(), count);
            } catch (Throwable failure) { report.failure(domain.displayName(), failure); }
        }
        if (!report.hasFailures()) commit(changes, report);
        return report;
    }
    public CompatReport plan(StylePreset preset, Set<Facet> selected) { return run(preset, selected, false); }
    public CompatReport apply(StylePreset preset, Set<Facet> selected) { return run(preset, selected, true); }

    private CompatReport run(StylePreset preset, Set<Facet> selected, boolean apply) {
        if (preset == null) throw new IllegalArgumentException("preset");
        CompatReport report = new CompatReport();
        List<Change> changes = new ArrayList<Change>();
        Set<Facet> effective = intersect(selected, preset.facets());
        for (String id : preset.domains().keySet())
            if (find(id) == null) report.warn("预设里有这个版本不认识的配置「" + id + "」，已忽略");
        if (!preset.unknownFacets().isEmpty()) report.warn("预设里有 " + preset.unknownFacets().size() + " 个这个版本不认识的分面，已忽略");
        for (PresetDomain domain : domains) {
            KeyPolicy policy = domain.policy();
            Set<Facet> mine = intersect(effective, policy.facets());
            if (mine.isEmpty()) continue;
            try {
                JsonObject snapshot = domain.snapshot().deepCopy();
                Map<String, JsonElement> current = JsonPaths.flatten(snapshot, policy::isRegistered);
                Map<String, JsonElement> patch = new LinkedHashMap<String, JsonElement>();
                if (preset.base() == StylePreset.Base.DEFAULTS) {
                    Map<String, JsonElement> defaults = JsonPaths.flatten(domain.defaults(), policy::isRegistered);
                    for (Facet facet : mine) for (String key : policy.keysOf(facet)) {
                        JsonElement value = defaults.get(key);
                        if (value != null) patch.put(key, value);
                    }
                }
                DomainFragment fragment = preset.domains().get(domain.id());
                if (fragment != null) overlay(domain, fragment, mine, current, patch, report);
                Map<String, JsonElement> projected = new LinkedHashMap<String, JsonElement>(current);
                projected.putAll(patch);
                int count = countChanges(current, projected);
                report.changed(domain.id(), count);
                if (apply && count > 0) changes.add(new Change(domain, domain.captureState().deepCopy(),
                        JsonPaths.overlay(snapshot, patch), count, false));
            } catch (Throwable failure) { report.failure(domain.displayName(), failure); }
        }
        if (apply && !report.hasFailures()) commit(changes, report);
        return report;
    }
    private static void commit(List<Change> changes, CompatReport report) {
        List<Change> attempted = new ArrayList<Change>();
        for (Change change : changes) {
            attempted.add(change); // even a failing domain can already have changed memory/files
            int failuresBefore = report.failures().size();
            try {
                if (change.restore) change.domain.restoreState(change.next.deepCopy(), report);
                else change.domain.apply(change.next.deepCopy(), report);
            } catch (Throwable failure) { report.failure(change.domain.displayName(), failure); }
            if (report.failures().size() > failuresBefore) {
                boolean complete = true;
                for (int i = attempted.size() - 1; i >= 0; i--) {
                    Change old = attempted.get(i);
                    CompatReport rollback = new CompatReport();
                    try { old.domain.rollbackState(old.before.deepCopy(), rollback); }
                    catch (Throwable failure) { rollback.failure(old.domain.displayName(), failure); }
                    if (rollback.hasFailures()) {
                        complete = false;
                        for (String line : rollback.failures()) report.warn("回滚：" + line);
                    }
                }
                report.rolledBack(complete);
                return;
            }
        }
        for (Change change : changes) change.domain.completeTransaction();
    }
    private static void overlay(PresetDomain domain, DomainFragment fragment, Set<Facet> mine,
                                Map<String, JsonElement> current, Map<String, JsonElement> patch, CompatReport report) {
        KeyPolicy policy = domain.policy();
        Map<String, JsonElement> incoming = JsonPaths.flatten(fragment.values(), policy::isRegistered);
        if (fragment.version() < domain.fragmentVersion()) incoming = domain.migrate(incoming, fragment.version());
        for (Map.Entry<String, JsonElement> entry : incoming.entrySet()) {
            String key = entry.getKey();
            KeyPolicy.Rule rule = policy.rule(key);
            if (rule == null) { report.unsupportedKey(domain.id(), key); continue; }
            if (rule.excluded() || !mine.contains(rule.facet())) continue;
            JsonElement value = entry.getValue();
            JsonElement clean = null;
            if (!JsonPaths.sameShape(current.get(key), value) || !withinEnum(value, rule.enumMax()) || !finiteNumbers(value))
                report.unsupportedValue(domain.id(), key);
            else clean = domain.sanitize(key, value, report);
            if (clean != null) patch.put(key, clean.deepCopy());
            else if (current.containsKey(key)) patch.put(key, current.get(key));
            else patch.remove(key); // invalid values preserve CURRENT even with base:defaults
        }
    }
    private static boolean withinEnum(JsonElement value, IntSupplier max) {
        if (max == null) return true;
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try {
            double index = value.getAsDouble();
            return Double.isFinite(index) && index >= 0 && index == Math.rint(index) && index <= max.getAsInt();
        } catch (RuntimeException failure) { return false; }
    }
    private static boolean finiteNumbers(JsonElement value) {
        if (value == null || value.isJsonNull()) return true;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try { return Float.isFinite(value.getAsFloat()); } catch (RuntimeException failure) { return false; }
        }
        if (value.isJsonArray()) for (JsonElement item : value.getAsJsonArray()) if (!finiteNumbers(item)) return false;
        if (value.isJsonObject()) for (JsonElement item : value.getAsJsonObject().asMap().values()) if (!finiteNumbers(item)) return false;
        return true;
    }
    private static int countChanges(Map<String, JsonElement> current, Map<String, JsonElement> next) {
        int changes = 0;
        for (Map.Entry<String, JsonElement> entry : next.entrySet())
            if (!sameValue(current.get(entry.getKey()), entry.getValue())) changes++;
        return changes;
    }
    static boolean sameValue(JsonElement a, JsonElement b) {
        if (a == null || b == null) return a == b;
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive left = a.getAsJsonPrimitive(), right = b.getAsJsonPrimitive();
            if (left.isNumber() && right.isNumber()) {
                try { return Float.compare(left.getAsFloat(), right.getAsFloat()) == 0; }
                catch (RuntimeException failure) { return false; }
            }
        }
        return a.equals(b);
    }
    private PresetDomain find(String id) {
        for (PresetDomain domain : domains) if (domain.id().equals(id)) return domain;
        return null;
    }
    private static Set<Facet> intersect(Set<Facet> a, Set<Facet> b) {
        EnumSet<Facet> out = EnumSet.noneOf(Facet.class);
        if (a != null && b != null) for (Facet facet : a) if (b.contains(facet)) out.add(facet);
        return out;
    }
    private static final class Change {
        final PresetDomain domain;
        final JsonObject before, next;
        final int count;
        final boolean restore;
        Change(PresetDomain domain, JsonObject before, JsonObject next, int count, boolean restore) {
            this.domain = domain; this.before = before; this.next = next; this.count = count; this.restore = restore;
        }
    }
}
