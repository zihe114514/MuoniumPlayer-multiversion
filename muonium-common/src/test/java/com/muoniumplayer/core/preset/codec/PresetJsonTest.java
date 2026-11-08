package com.muoniumplayer.core.preset.codec;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.preset.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PresetJsonTest {
    @Test void 往返保留元信息分面与片段() throws Exception {
        Map<String, DomainFragment> domains = new LinkedHashMap<>(); domains.put("hud", new DomainFragment(1, JsonParser.parseString("{\"glow\":0.3,\"nested\":{\"a\":true}}").getAsJsonObject()));
        domains.put("future", new DomainFragment(3, JsonParser.parseString("{\"x\":1}").getAsJsonObject()));
        StylePreset preset = new StylePreset(new PresetMeta("user:1", "流光玻璃", "Cherry", "说明", "2026-11-06T00:00:00Z", "2.4.1", "26.3"),
                StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS, Facet.VIPER), Arrays.asList("hologram"), domains, StylePreset.Source.USER, null);
        for (boolean pretty : new boolean[]{true, false}) {
            StylePreset back = parse(PresetJson.write(preset, pretty)); assertEquals("流光玻璃", back.meta().name()); assertEquals("Cherry", back.meta().author()); assertEquals("26.3", back.meta().generatorMc());
            assertEquals(preset.facets(), back.facets()); assertEquals(preset.unknownFacets(), back.unknownFacets()); assertEquals(3, back.domains().get("future").version()); assertEquals(.3, back.domains().get("hud").values().get("glow").getAsDouble());
        }
    }
    @Test void 不是预设或来自更新版本都拒绝() {
        assertThrows(PresetFormatException.class, () -> parse("{\"format\":\"something.else\"}"));
        assertTrue(assertThrows(PresetFormatException.class, () -> parse("{\"format\":\"muonium.preset\",\"formatVersion\":99}")).getMessage().contains("更新"));
        for (String text : new String[]{"[1,2,3]", "{not json", ""}) assertThrows(PresetFormatException.class, () -> parse(text));
    }
    @Test void 深嵌套在解析之前就被拒掉() {
        assertTrue(assertThrows(PresetFormatException.class, () -> parse("{\"format\":\"muonium.preset\",\"domains\":" + "[".repeat(200000))).getMessage().contains("嵌套"));
    }
    @Test void 字符串里的括号不计入嵌套深度() { assertEquals(1, PresetLimits.nestingDepth("{\"a\":\"[[[[{{{{\\\"]]]\"}")); }
    @Test void 元信息清洗控制字符与方向符并按码点截断() throws Exception {
        String name = parse("{\"format\":\"muonium.preset\",\"meta\":{\"name\":\"\\u202Egnp.exe\\u0007" + "长".repeat(60) + "😀\"}}").meta().name();
        assertFalse(name.contains("\u202E")); assertFalse(name.contains("\u0007")); assertEquals(PresetLimits.MAX_NAME_CHARS, name.codePointCount(0, name.length()));
        assertEquals("未命名预设", parse("{\"format\":\"muonium.preset\",\"meta\":{}}").meta().name());
    }
    @Test void 超长文字值拒绝() { assertThrows(PresetFormatException.class, () -> parse("{\"format\":\"muonium.preset\",\"domains\":{\"hud\":{\"values\":{\"k\":\"" + "x".repeat(PresetLimits.MAX_VALUE_STRING_CHARS + 1) + "\"}}}}")); }
    @Test void 不合规的配置域id直接忽略() throws Exception {
        StylePreset preset = parse("{\"format\":\"muonium.preset\",\"domains\":{\"../evil\":{\"values\":{\"a\":1}},\"ok_1\":{\"values\":{\"a\":1}},\"no\":{\"values\":3}}}"); assertEquals(1, preset.domains().size()); assertTrue(preset.domains().containsKey("ok_1"));
    }
    @Test void 缺省基准是只改写到的键() throws Exception { assertEquals(StylePreset.Base.CURRENT, parse("{\"format\":\"muonium.preset\"}").base()); assertEquals(StylePreset.Base.CURRENT, parse("\uFEFF{\"format\":\"muonium.preset\"}").base()); }
    @Test void 缺字段叠加完整快照保留空对象和literal点键() {
        JsonObject before = JsonParser.parseString("{\"common\":{\"opacity\":0.7,\"empty\":{}},\"private.key\":\"local\"}").getAsJsonObject();
        JsonObject after = JsonPaths.overlay(before, Collections.singletonMap("common.opacity", JsonParser.parseString("0.2")));
        assertEquals("local", after.get("private.key").getAsString()); assertTrue(after.getAsJsonObject("common").has("empty")); assertEquals(.2, after.getAsJsonObject("common").get("opacity").getAsDouble());
    }
    private static StylePreset parse(String text) throws PresetFormatException { return PresetJson.parse(text, StylePreset.Source.USER, null); }
}
