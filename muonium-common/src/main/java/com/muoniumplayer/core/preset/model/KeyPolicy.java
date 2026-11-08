package com.muoniumplayer.core.preset.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/** Explicit whitelist; every persistent key is either assigned or excluded with a reason. */
public final class KeyPolicy {
    public static final class Rule {
        private final String key, excludedReason;
        private final Facet facet;
        private final IntSupplier enumMax;
        private Rule(String key, Facet facet, String excludedReason, IntSupplier enumMax) {
            this.key = key; this.facet = facet; this.excludedReason = excludedReason; this.enumMax = enumMax;
        }
        public String key() { return key; }
        public Facet facet() { return facet; }
        public boolean excluded() { return facet == null; }
        public String excludedReason() { return excludedReason; }
        public IntSupplier enumMax() { return enumMax; }
    }
    private final Map<String, Rule> rules;
    private KeyPolicy(Map<String, Rule> rules) {
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<String, Rule>(rules));
    }
    public static Builder builder() { return new Builder(); }
    public Rule rule(String key) { return rules.get(key); }
    public boolean isRegistered(String key) { return rules.containsKey(key); }
    public Set<String> allKeys() { return rules.keySet(); }
    public Set<String> keysOf(Facet facet) {
        Set<String> keys = new LinkedHashSet<String>();
        for (Rule rule : rules.values()) if (rule.facet == facet) keys.add(rule.key);
        return keys;
    }
    public Set<Facet> facets() {
        EnumSet<Facet> facets = EnumSet.noneOf(Facet.class);
        for (Rule rule : rules.values()) if (rule.facet != null) facets.add(rule.facet);
        return facets;
    }
    public Map<String, String> excluded() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Rule rule : rules.values()) if (rule.facet == null) out.put(rule.key, rule.excludedReason);
        return out;
    }
    public static final class Builder {
        private final Map<String, Rule> rules = new LinkedHashMap<String, Rule>();
        private Builder() {}
        public Builder facet(Facet facet, String... keys) {
            if (facet == null) throw new IllegalArgumentException("facet");
            for (String key : keys) put(new Rule(key, facet, null, null));
            return this;
        }
        public Builder exclude(String reason, String... keys) {
            if (reason == null || reason.trim().isEmpty()) throw new IllegalArgumentException("排除项必须写明理由");
            for (String key : keys) put(new Rule(key, null, reason, null));
            return this;
        }
        public Builder enumMax(String key, IntSupplier max) {
            Rule rule = rules.get(key);
            if (rule == null || rule.excluded() || max == null) throw new IllegalStateException("枚举上限只能挂在已归入分面的键上：" + key);
            rules.put(key, new Rule(key, rule.facet, null, max));
            return this;
        }
        private void put(Rule rule) {
            if (rule.key == null || rule.key.isEmpty()) throw new IllegalArgumentException("空键");
            if (rules.containsKey(rule.key)) throw new IllegalStateException("键重复登记（一个键只能属于一个分面）：" + rule.key);
            rules.put(rule.key, rule);
        }
        public KeyPolicy build() { return new KeyPolicy(rules); }
    }
}
