package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.codec.JsonPaths;
import com.muoniumplayer.core.preset.domain.HudPresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomains;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.preset.model.StylePreset;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置预设是纯数据，这里替它们把关：能解析、键全在白名单里、属于声明的分面；
 * 以及「默认」「简约」各自的语义不被改坏。
 */
class BuiltinPresetsTest {

    private static List<StylePreset> builtins() {
        return PresetRepository.standard().builtins();
    }

    @Test
    void 内置预设齐全且顺序固定() {
        List<StylePreset> builtins = builtins();
        assertEquals(2, builtins.size(), "index.json 里列的预设应全部能读出来");
        assertEquals("builtin:default", builtins.get(0).meta().id());
        assertEquals("默认", builtins.get(0).meta().name());
        assertEquals("builtin:minimal", builtins.get(1).meta().id());
        assertEquals("简约", builtins.get(1).meta().name());
        for (StylePreset preset : builtins) {
            assertEquals(StylePreset.Source.BUILTIN, preset.source());
            assertTrue(preset.unknownFacets().isEmpty(), preset.meta().name() + " 声明了不存在的分面");
            assertFalse(preset.meta().description().isEmpty(), preset.meta().name() + " 缺说明");
        }
    }

    @Test
    void 默认就是全部分面回到出厂值() {
        StylePreset preset = builtins().get(0);
        assertEquals(StylePreset.Base.DEFAULTS, preset.base());
        assertEquals(EnumSet.allOf(Facet.class), preset.facets(), "新加的分面也要进「默认」");
        assertTrue(preset.domains().isEmpty(), "「默认」不写任何值，全靠出厂值");
    }

    @Test
    void 简约只关掉全部显示开关() {
        StylePreset preset = builtins().get(1);
        assertEquals(StylePreset.Base.CURRENT, preset.base(), "只动显示开关，不能把别的样式冲掉");
        assertEquals(EnumSet.of(Facet.VISIBILITY), preset.facets());
        DomainFragment hud = preset.domains().get(HudPresetDomain.ID);
        assertNotNull(hud);
        JsonObject values = hud.values();
        assertEquals(HudPresetDomain.staticPolicy().keysOf(Facet.VISIBILITY), values.keySet(),
                "显示开关分面新加了键，「简约」也要把它关掉");
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            assertFalse(entry.getValue().getAsBoolean(), entry.getKey() + " 在「简约」里必须是关");
        }
    }

    @Test
    void 内置预设的每个键都在白名单里且属于声明的分面() {
        for (StylePreset preset : builtins()) {
            for (Map.Entry<String, DomainFragment> entry : preset.domains().entrySet()) {
                PresetDomain domain = find(entry.getKey());
                KeyPolicy policy = domain.policy();
                for (String key : JsonPaths.flatten(entry.getValue().values(), policy::isRegistered).keySet()) {
                    KeyPolicy.Rule rule = policy.rule(key);
                    assertNotNull(rule, preset.meta().name() + " 用了没登记的键 " + entry.getKey() + "." + key);
                    assertFalse(rule.excluded(), preset.meta().name() + " 用了排除项 " + key);
                    assertTrue(preset.facets().contains(rule.facet()),
                            preset.meta().name() + " 的 " + key + " 不在它声明的分面里，套用时会被忽略");
                }
            }
        }
    }

    private static PresetDomain find(String id) {
        for (PresetDomain domain : PresetDomains.all()) {
            if (domain.id().equals(id)) {
                return domain;
            }
        }
        throw new AssertionError("内置预设引用了不存在的配置域：" + id);
    }
}
