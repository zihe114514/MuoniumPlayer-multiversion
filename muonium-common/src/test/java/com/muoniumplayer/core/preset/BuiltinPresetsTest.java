package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.muoniumplayer.core.preset.codec.JsonPaths;
import com.muoniumplayer.core.preset.domain.*;
import com.muoniumplayer.core.preset.model.*;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinPresetsTest {
    private static List<StylePreset> builtins() { return PresetRepository.standard().builtins(); }
    @Test void 内置预设齐全且顺序固定() {
        List<StylePreset> all = builtins(); assertEquals(2, all.size());
        assertEquals("builtin:default", all.get(0).meta().id()); assertEquals("默认", all.get(0).meta().name());
        assertEquals("builtin:minimal", all.get(1).meta().id()); assertEquals("简约", all.get(1).meta().name());
        for (StylePreset preset : all) { assertEquals(StylePreset.Source.BUILTIN, preset.source()); assertTrue(preset.unknownFacets().isEmpty()); assertFalse(preset.meta().description().isEmpty()); }
    }
    @Test void 默认就是全部分面回到出厂值() {
        StylePreset preset = builtins().get(0); assertEquals(StylePreset.Base.DEFAULTS, preset.base()); assertEquals(EnumSet.allOf(Facet.class), preset.facets()); assertTrue(preset.domains().isEmpty());
    }
    @Test void 简约只关掉全部显示开关() {
        StylePreset preset = builtins().get(1); assertEquals(StylePreset.Base.CURRENT, preset.base()); assertEquals(EnumSet.of(Facet.VISIBILITY), preset.facets());
        DomainFragment hud = preset.domains().get(HudPresetDomain.ID); assertNotNull(hud);
        assertEquals(HudPresetDomain.staticPolicy().keysOf(Facet.VISIBILITY), hud.values().keySet());
        for (JsonElement value : hud.values().asMap().values()) assertFalse(value.getAsBoolean());
    }
    @Test void 内置预设的每个键都在白名单里且属于声明的分面() {
        for (StylePreset preset : builtins()) for (Map.Entry<String, DomainFragment> entry : preset.domains().entrySet()) {
            PresetDomain domain = find(entry.getKey());
            for (String key : JsonPaths.flatten(entry.getValue().values(), domain.policy()::isRegistered).keySet()) {
                KeyPolicy.Rule rule = domain.policy().rule(key); assertNotNull(rule); assertFalse(rule.excluded()); assertTrue(preset.facets().contains(rule.facet()));
            }
        }
    }
    private static PresetDomain find(String id) { for (PresetDomain domain : PresetDomains.all()) if (domain.id().equals(id)) return domain; throw new AssertionError(id); }
}
