package com.muoniumplayer.core.preset.model;

import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一份模板预设：元信息 + 声明包含的分面 + 各配置域的稀疏片段。
 *
 * <p><b>分面声明是权威</b>：片段里出现了、但不属于 {@link #facets()} 的键一律不套用 —— 预设说自己只管
 * 「歌词样式」，就不能顺手改掉别人的灵动岛。</p>
 *
 * <p>不可变；来源与所在文件只用于界面（能不能删、去哪个目录），不进序列化。</p>
 */
public final class StylePreset {

    /** 预设从哪来。决定界面上能做什么（内置不可删、备份不在列表里）。 */
    public enum Source {
        /** 随 mod 打包，只读。 */
        BUILTIN,
        /** 玩家自建或导入，存在 presets 目录里。 */
        USER,
        /** 套用前的自动备份。 */
        BACKUP
    }

    /**
     * 套用基准：所选分面里预设<b>没写到</b>的键怎么办。
     *
     * <ul>
     *   <li>{@link #DEFAULTS}：先把这些分面整体回到出厂值，再叠预设 —— 结果确定，不会混进玩家之前调过的
     *       残值。内置预设与「存为预设」都用它。</li>
     *   <li>{@link #CURRENT}：只改预设写到的键，其余保持现状 —— 给「只调一两项」的微调预设用。</li>
     * </ul>
     */
    public enum Base {
        DEFAULTS("defaults"),
        CURRENT("current");

        private final String id;

        Base(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Base byId(String id) {
            return DEFAULTS.id.equals(id) ? DEFAULTS : CURRENT;
        }
    }

    private final PresetMeta meta;
    private final Base base;
    private final Set<Facet> facets;
    private final List<String> unknownFacets;
    private final Map<String, DomainFragment> domains;
    private final Source source;
    private final File file;

    public StylePreset(PresetMeta meta, Base base, Set<Facet> facets, List<String> unknownFacets,
                       Map<String, DomainFragment> domains, Source source, File file) {
        this.meta = meta;
        this.base = base == null ? Base.CURRENT : base;
        this.facets = facets == null || facets.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Facet.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(facets));
        this.unknownFacets = unknownFacets == null ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(unknownFacets));
        this.domains = domains == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, DomainFragment>(domains));
        this.source = source == null ? Source.USER : source;
        this.file = file;
    }

    public PresetMeta meta() {
        return meta;
    }

    public Base base() {
        return base;
    }

    /** 预设声明包含的分面（本版本认得的那部分）。 */
    public Set<Facet> facets() {
        return facets;
    }

    /** 本版本认不出的分面 id（来自更新的版本）；只用于提示。 */
    public List<String> unknownFacets() {
        return unknownFacets;
    }

    /** 配置域 id → 片段。 */
    public Map<String, DomainFragment> domains() {
        return domains;
    }

    public Source source() {
        return source;
    }

    /** 所在文件；内置预设与尚未落盘的预设为 {@code null}。 */
    public File file() {
        return file;
    }

    public StylePreset withMeta(PresetMeta newMeta) {
        return new StylePreset(newMeta, base, facets, unknownFacets, domains, source, file);
    }

    public StylePreset located(Source newSource, File newFile) {
        return new StylePreset(meta, base, facets, unknownFacets, domains, newSource, newFile);
    }

    /** 声明的分面里有没有属于某一组的。 */
    public boolean touches(Facet.Group group) {
        for (Facet facet : facets) {
            if (facet.group() == group) {
                return true;
            }
        }
        return false;
    }
}
