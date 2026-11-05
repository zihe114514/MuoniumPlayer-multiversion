package com.muoniumplayer.core.preset;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.cine.CineSettings;
import com.muoniumplayer.core.preset.codec.JsonPaths;
import com.muoniumplayer.core.preset.domain.HudPresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomains;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig;
import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：每个配置域快照里的每一个键都必须在键策略里归类（分面或带理由排除），策略里也不许有陈旧的键。
 *
 * <p>这条测试是「新加的设置不会悄悄漏出预设、也不会悄悄把隐私带出去」的唯一保证。它在七棵版本树上
 * 各编译运行一次 —— {@code HudConfig} 是各树一份，哪棵树多了 / 少了键，就在那棵树上红。</p>
 *
 * <p>红了怎么修：去对应的 {@code *PresetDomain} 里把新键归到一个分面，或者 {@code exclude} 写明理由。
 * 不要为了变绿去删测试或者把键随手塞进某个分面。</p>
 */
class PresetPolicyCompletenessTest {

    @Test
    void HUD每个落盘键都已归类() {
        assertCovered("hud（HudConfig）", HudPresetDomain.staticPolicy(), HudConfig.toJson());
        assertCovered("hud 出厂值", HudPresetDomain.staticPolicy(), HudConfig.defaultsJson());
    }

    @Test
    void Folia背景每个落盘键都已归类() {
        KeyPolicy policy = find("foliaBg").policy();
        assertCovered("foliaBg（FoliaBackgroundConfig）", policy, FoliaBackgroundConfig.toJson());
        assertCovered("foliaBg 出厂值", policy, FoliaBackgroundConfig.defaultsJson());
    }

    @Test
    void 卡点运镜每个落盘键都已归类() {
        assertCovered("cine（CineSettings）", find("cine").policy(), CineSettings.defaultsJson());
    }

    @Test
    void 其余配置域的出厂视图与策略一致() {
        for (String id : new String[] {"theme", "font", "transparent", "acoustics", "viper"}) {
            PresetDomain domain = find(id);
            assertCovered(id, domain.policy(), domain.defaults());
        }
    }

    @Test
    void 每个分面都有配置域承接() {
        Set<Facet> covered = EnumSet.noneOf(Facet.class);
        for (PresetDomain domain : PresetDomains.all()) {
            covered.addAll(domain.policy().facets());
        }
        assertEquals(EnumSet.allOf(Facet.class), covered,
                "有分面没有任何配置域承接：勾了也不会生效");
    }

    @Test
    void 配置域id唯一且只用简单字符() {
        Set<String> seen = new HashSet<String>();
        for (PresetDomain domain : PresetDomains.all()) {
            assertTrue(domain.id().matches("[A-Za-z0-9_-]{1,32}"), domain.id());
            assertTrue(seen.add(domain.id()), "配置域 id 重复：" + domain.id());
            assertTrue(domain.fragmentVersion() >= 1);
        }
    }

    @Test
    void 分面id唯一() {
        Set<String> seen = new HashSet<String>();
        for (Facet facet : Facet.values()) {
            assertTrue(seen.add(facet.id()), "分面 id 重复：" + facet.id());
            assertTrue(facet.id().matches("[a-z0-9_-]{1,32}"), facet.id());
        }
    }

    private static void assertCovered(String label, KeyPolicy policy, JsonObject snapshot) {
        Set<String> keys = new LinkedHashSet<String>(JsonPaths.flatten(snapshot, policy::isRegistered).keySet());
        Set<String> missing = new TreeSet<String>(keys);
        missing.removeAll(policy.allKeys());
        Set<String> stale = new TreeSet<String>(policy.allKeys());
        stale.removeAll(keys);
        assertTrue(missing.isEmpty(), label + " 有没归类的键：" + missing
                + " —— 在对应的 *PresetDomain 里归到一个分面，或用 exclude 写明理由");
        assertTrue(stale.isEmpty(), label + " 的策略里登记了已经不存在的键：" + stale);
    }

    private static PresetDomain find(String id) {
        for (PresetDomain domain : PresetDomains.all()) {
            if (domain.id().equals(id)) {
                return domain;
            }
        }
        throw new AssertionError("没有配置域 " + id);
    }
}
