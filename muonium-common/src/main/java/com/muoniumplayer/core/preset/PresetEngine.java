package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
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

/**
 * 模板预设的引擎：捕获（当前状态 → 片段）、预检、套用（片段 → 当前状态）。
 *
 * <p>只依赖 {@link PresetDomain} 接口，不碰任何具体设置类与 Minecraft —— 单测用假的配置域就能把
 * 合并、重置、过滤、兼容判定全部跑一遍。</p>
 *
 * <h3>套用一个配置域的算法</h3>
 * <ol>
 *   <li>{@code next = flatten(snapshot)}：当前完整状态；</li>
 *   <li>{@code base = defaults} 时，所选分面的全部键先回到出厂值；</li>
 *   <li>逐个叠上预设里的值，依次过：登记过？属于所选分面？形状对得上？枚举在本地上限内？
 *       {@link PresetDomain#sanitize} 放行？—— 任何一关不过就保留当前值并记进报告；</li>
 *   <li>与当前状态比，没变就不碰；变了才 {@link PresetDomain#apply} 整份交回设置类。</li>
 * </ol>
 * 预检与套用是同一条路径，只差最后一步写不写，所以预检看到的提示与真正发生的不会对不上。
 */
public final class PresetEngine {

    private final List<PresetDomain> domains;

    public PresetEngine(List<PresetDomain> domains) {
        this.domains = Collections.unmodifiableList(new ArrayList<PresetDomain>(domains));
    }

    public List<PresetDomain> domains() {
        return domains;
    }

    /** 所有配置域合起来涉及到的分面。 */
    public Set<Facet> availableFacets() {
        EnumSet<Facet> facets = EnumSet.noneOf(Facet.class);
        for (PresetDomain domain : domains) {
            facets.addAll(domain.policy().facets());
        }
        return facets;
    }

    /** 把当前状态里属于所选分面的全部键做成片段（只出白名单里的键）。 */
    public Map<String, DomainFragment> capture(Set<Facet> facets) {
        Map<String, DomainFragment> out = new LinkedHashMap<String, DomainFragment>();
        for (PresetDomain domain : domains) {
            KeyPolicy policy = domain.policy();
            Set<Facet> mine = intersect(facets, policy.facets());
            if (mine.isEmpty()) {
                continue;
            }
            Map<String, JsonElement> current = JsonPaths.flatten(domain.snapshot(), policy::isRegistered);
            Map<String, JsonElement> picked = new LinkedHashMap<String, JsonElement>();
            for (Map.Entry<String, JsonElement> entry : current.entrySet()) {
                KeyPolicy.Rule rule = policy.rule(entry.getKey());
                if (rule != null && !rule.excluded() && mine.contains(rule.facet())) {
                    picked.put(entry.getKey(), entry.getValue());
                }
            }
            if (!picked.isEmpty()) {
                out.put(domain.id(), new DomainFragment(domain.fragmentVersion(), JsonPaths.unflatten(picked)));
            }
        }
        return out;
    }

    /** 预检：算出会发生什么，什么都不改。 */
    public CompatReport plan(StylePreset preset, Set<Facet> selected) {
        return run(preset, selected, false);
    }

    /** 套用。调用方负责在此之前做好备份。 */
    public CompatReport apply(StylePreset preset, Set<Facet> selected) {
        return run(preset, selected, true);
    }

    private CompatReport run(StylePreset preset, Set<Facet> selected, boolean commit) {
        CompatReport report = new CompatReport();
        Set<Facet> effective = intersect(selected, preset.facets());
        for (String id : preset.domains().keySet()) {
            if (find(id) == null) {
                report.warn("预设里有这个版本不认识的配置「" + id + "」，已忽略");
            }
        }
        if (!preset.unknownFacets().isEmpty()) {
            report.warn("预设里有 " + preset.unknownFacets().size() + " 个这个版本不认识的分面，已忽略");
        }
        for (PresetDomain domain : domains) {
            KeyPolicy policy = domain.policy();
            Set<Facet> mine = intersect(effective, policy.facets());
            if (mine.isEmpty()) {
                continue;
            }
            try {
                Map<String, JsonElement> current = JsonPaths.flatten(domain.snapshot(), policy::isRegistered);
                Map<String, JsonElement> next = new LinkedHashMap<String, JsonElement>(current);
                if (preset.base() == StylePreset.Base.DEFAULTS) {
                    Map<String, JsonElement> defaults = JsonPaths.flatten(domain.defaults(), policy::isRegistered);
                    for (Facet facet : mine) {
                        for (String key : policy.keysOf(facet)) {
                            JsonElement value = defaults.get(key);
                            if (value != null) {
                                next.put(key, value);
                            }
                        }
                    }
                }
                DomainFragment fragment = preset.domains().get(domain.id());
                if (fragment != null) {
                    overlay(domain, fragment, mine, current, next, report);
                }
                int changes = countChanges(current, next);
                report.changed(domain.id(), changes);
                if (commit && changes > 0) {
                    domain.apply(JsonPaths.unflatten(next), report);
                }
            } catch (Throwable failure) {
                report.failure(domain.displayName(), failure);
            }
        }
        return report;
    }

    private static void overlay(PresetDomain domain, DomainFragment fragment, Set<Facet> mine,
                                Map<String, JsonElement> current, Map<String, JsonElement> next,
                                CompatReport report) {
        KeyPolicy policy = domain.policy();
        Map<String, JsonElement> incoming = JsonPaths.flatten(fragment.values(), policy::isRegistered);
        if (fragment.version() < domain.fragmentVersion()) {
            incoming = domain.migrate(incoming, fragment.version());
        }
        for (Map.Entry<String, JsonElement> entry : incoming.entrySet()) {
            String key = entry.getKey();
            KeyPolicy.Rule rule = policy.rule(key);
            if (rule == null) {
                report.unsupportedKey(domain.id(), key);
                continue;
            }
            if (rule.excluded() || !mine.contains(rule.facet())) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (!JsonPaths.sameShape(current.get(key), value) || !withinEnum(value, rule.enumMax())) {
                report.unsupportedValue(domain.id(), key);
                continue;
            }
            JsonElement clean = domain.sanitize(key, value, report);
            if (clean != null) {
                next.put(key, clean);
            }
        }
    }

    /** 枚举下标型键：超出本地上限的值本版本不支持（不交给读档钳位，见 {@code KeyPolicy.Rule#enumMax}）。 */
    private static boolean withinEnum(JsonElement value, IntSupplier max) {
        if (max == null || value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return true;
        }
        try {
            return value.getAsDouble() <= max.getAsInt();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static int countChanges(Map<String, JsonElement> current, Map<String, JsonElement> next) {
        int changes = 0;
        for (Map.Entry<String, JsonElement> entry : next.entrySet()) {
            if (!sameValue(current.get(entry.getKey()), entry.getValue())) {
                changes++;
            }
        }
        return changes;
    }

    /**
     * 两个值是否相同。数字按 float 精度比：内存里的字段是 float，预设里解析出来的是十进制文本，
     * 按 double 比会把「同一个值」判成变化（1.0115517f 与 1.0115517）。
     */
    static boolean sameValue(JsonElement a, JsonElement b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive left = a.getAsJsonPrimitive();
            JsonPrimitive right = b.getAsJsonPrimitive();
            if (left.isNumber() && right.isNumber()) {
                try {
                    return Float.compare(left.getAsFloat(), right.getAsFloat()) == 0;
                } catch (RuntimeException failure) {
                    return false;
                }
            }
        }
        return a.equals(b);
    }

    private PresetDomain find(String id) {
        for (PresetDomain domain : domains) {
            if (domain.id().equals(id)) {
                return domain;
            }
        }
        return null;
    }

    private static Set<Facet> intersect(Set<Facet> a, Set<Facet> b) {
        EnumSet<Facet> result = EnumSet.noneOf(Facet.class);
        if (a == null || b == null) {
            return result;
        }
        for (Facet facet : a) {
            if (b.contains(facet)) {
                result.add(facet);
            }
        }
        return result;
    }
}
