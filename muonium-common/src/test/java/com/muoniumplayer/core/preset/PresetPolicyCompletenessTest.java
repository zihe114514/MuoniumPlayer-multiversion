package com.muoniumplayer.core.preset;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.cine.CineSettings;
import com.muoniumplayer.core.preset.codec.JsonPaths;
import com.muoniumplayer.core.preset.domain.*;
import com.muoniumplayer.core.preset.model.*;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig;
import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PresetPolicyCompletenessTest {
    @Test void HUD每个落盘键都已归类() {
        assertCovered("hud", HudPresetDomain.staticPolicy(), HudConfig.toJson());
        assertCovered("hud defaults", HudPresetDomain.staticPolicy(), HudConfig.defaultsJson());
    }
    @Test void Folia背景每个落盘键都已归类() {
        assertCovered("foliaBg", find("foliaBg").policy(), FoliaBackgroundConfig.toJson());
        assertCovered("folia defaults", find("foliaBg").policy(), FoliaBackgroundConfig.defaultsJson());
    }
    @Test void 卡点运镜每个落盘键都已归类() { assertCovered("cine", find("cine").policy(), CineSettings.defaultsJson()); }
    @Test void 其余配置域的出厂视图与策略一致() {
        for (String id : new String[]{"theme", "font", "transparent", "acoustics", "viper"}) assertCovered(id, find(id).policy(), find(id).defaults());
    }
    @Test void 每个分面都有配置域承接() {
        Set<Facet> covered = EnumSet.noneOf(Facet.class); for (PresetDomain domain : PresetDomains.all()) covered.addAll(domain.policy().facets());
        assertEquals(EnumSet.allOf(Facet.class), covered);
    }
    @Test void 配置域id唯一且只用简单字符() {
        Set<String> seen = new HashSet<>(); for (PresetDomain domain : PresetDomains.all()) {
            assertTrue(domain.id().matches("[A-Za-z0-9_-]{1,32}")); assertTrue(seen.add(domain.id())); assertTrue(domain.fragmentVersion() >= 1);
        }
    }
    @Test void 分面id唯一() {
        Set<String> seen = new HashSet<>(); for (Facet facet : Facet.values()) { assertTrue(seen.add(facet.id())); assertTrue(facet.id().matches("[a-z0-9_-]{1,32}")); }
    }
    private static void assertCovered(String label, KeyPolicy policy, JsonObject snapshot) {
        Set<String> keys = new LinkedHashSet<>(JsonPaths.flatten(snapshot, policy::isRegistered).keySet());
        Set<String> missing = new TreeSet<>(keys), stale = new TreeSet<>(policy.allKeys());
        missing.removeAll(policy.allKeys()); stale.removeAll(keys);
        assertTrue(missing.isEmpty(), label + " 未归类 " + missing); assertTrue(stale.isEmpty(), label + " 陈旧键 " + stale);
    }
    private static PresetDomain find(String id) { for (PresetDomain domain : PresetDomains.all()) if (domain.id().equals(id)) return domain; throw new AssertionError(id); }
}
