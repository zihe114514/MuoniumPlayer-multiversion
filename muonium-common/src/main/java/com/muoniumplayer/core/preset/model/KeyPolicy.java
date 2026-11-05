package com.muoniumplayer.core.preset.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 一个配置域的键策略：每个落盘键要么归属一个分面，要么写明理由排除在预设之外。
 *
 * <p><b>白名单</b>：只有登记在某个分面下的键才会被导出、被套用；没登记的键（包括别人预设里塞进来的
 * 任何东西）一律忽略。完整性由守卫测试钉住 —— 配置域的快照里出现了没归类的键，测试就红，
 * 于是新加的设置不会悄悄漏出预设，也不会悄悄把账号之类的东西带出去。</p>
 *
 * <p>键是拍平后的点路径（{@code common.coverOpacity}）；登记了的键在拍平时视为叶子，
 * 哪怕它的值是对象（例如 ViPER 预设体整块随分享走）。</p>
 */
public final class KeyPolicy {

    /** 一个键的归属。 */
    public static final class Rule {
        private final String key;
        private final Facet facet;
        private final String excludedReason;
        private final IntSupplier enumMax;

        private Rule(String key, Facet facet, String excludedReason, IntSupplier enumMax) {
            this.key = key;
            this.facet = facet;
            this.excludedReason = excludedReason;
            this.enumMax = enumMax;
        }

        public String key() {
            return key;
        }

        /** 归属分面；排除项为 {@code null}。 */
        public Facet facet() {
            return facet;
        }

        public boolean excluded() {
            return facet == null;
        }

        /** 排除的理由（写给维护者看）；非排除项为 {@code null}。 */
        public String excludedReason() {
            return excludedReason;
        }

        /**
         * 枚举下标型键的本地上限；不是这类键为 {@code null}。
         *
         * <p>预设值超过本地上限时判「本版本不支持」并保留当前值，<b>不交给读档钳位</b>：钳位会把
         * 「新版第 12 种样式」静默变成第 11 种，玩家只会以为预设坏了。</p>
         */
        public IntSupplier enumMax() {
            return enumMax;
        }
    }

    private final Map<String, Rule> rules;

    private KeyPolicy(Map<String, Rule> rules) {
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<String, Rule>(rules));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 这个键的归属；没登记返回 {@code null}。 */
    public Rule rule(String key) {
        return rules.get(key);
    }

    /** 登记过（归属分面或明确排除）。 */
    public boolean isRegistered(String key) {
        return rules.containsKey(key);
    }

    /** 全部登记过的键（含排除项），按登记顺序。 */
    public Set<String> allKeys() {
        return rules.keySet();
    }

    /** 属于某个分面的键。 */
    public Set<String> keysOf(Facet facet) {
        Set<String> keys = new LinkedHashSet<String>();
        for (Rule rule : rules.values()) {
            if (rule.facet == facet) {
                keys.add(rule.key);
            }
        }
        return keys;
    }

    /** 本域涉及到的分面。 */
    public Set<Facet> facets() {
        EnumSet<Facet> facets = EnumSet.noneOf(Facet.class);
        for (Rule rule : rules.values()) {
            if (rule.facet != null) {
                facets.add(rule.facet);
            }
        }
        return facets;
    }

    /** 排除项：键 → 理由。 */
    public Map<String, String> excluded() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Rule rule : rules.values()) {
            if (rule.facet == null) {
                result.put(rule.key, rule.excludedReason);
            }
        }
        return result;
    }

    public static final class Builder {
        private final Map<String, Rule> rules = new LinkedHashMap<String, Rule>();

        private Builder() {
        }

        /** 把一批键归到一个分面。 */
        public Builder facet(Facet facet, String... keys) {
            if (facet == null) {
                throw new IllegalArgumentException("facet");
            }
            for (String key : keys) {
                put(new Rule(key, facet, null, null));
            }
            return this;
        }

        /** 把一批键排除在预设之外，并写明理由（理由必须非空：守卫测试与评审都靠它）。 */
        public Builder exclude(String reason, String... keys) {
            if (reason == null || reason.trim().isEmpty()) {
                throw new IllegalArgumentException("排除项必须写明理由");
            }
            for (String key : keys) {
                put(new Rule(key, null, reason, null));
            }
            return this;
        }

        /** 给一个已登记的键挂上枚举下标上限。 */
        public Builder enumMax(String key, IntSupplier max) {
            Rule existing = rules.get(key);
            if (existing == null || existing.facet == null) {
                throw new IllegalStateException("枚举上限只能挂在已归入分面的键上：" + key);
            }
            rules.put(key, new Rule(key, existing.facet, null, max));
            return this;
        }

        private void put(Rule rule) {
            if (rule.key == null || rule.key.isEmpty()) {
                throw new IllegalArgumentException("空键");
            }
            if (rules.containsKey(rule.key)) {
                throw new IllegalStateException("键重复登记（一个键只能属于一个分面）：" + rule.key);
            }
            rules.put(rule.key, rule);
        }

        public KeyPolicy build() {
            return new KeyPolicy(rules);
        }
    }
}
