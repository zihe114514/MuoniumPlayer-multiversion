package com.muoniumplayer.core.preset.codec;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.PresetMeta;
import com.muoniumplayer.core.preset.model.StylePreset;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预设文件格式 v1：往返、读旧写新、未知内容保留、不可信输入的拒绝与清洗。 */
class PresetJsonTest {

    @Test
    void 往返保留元信息分面与片段() throws Exception {
        Map<String, DomainFragment> domains = new LinkedHashMap<String, DomainFragment>();
        domains.put("hud", new DomainFragment(1, JsonParser.parseString("{\"glow\":0.3,\"nested\":{\"a\":true}}")
                .getAsJsonObject()));
        domains.put("future", new DomainFragment(3, JsonParser.parseString("{\"x\":1}").getAsJsonObject()));
        StylePreset preset = new StylePreset(
                new PresetMeta("user:1", "流光玻璃", "Cherry", "说明", "2026-11-06T00:00:00Z", "2.4.1", "26.3"),
                StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS, Facet.VIPER), Arrays.asList("hologram"),
                domains, StylePreset.Source.USER, null);

        for (boolean pretty : new boolean[] {true, false}) {
            StylePreset back = PresetJson.parse(PresetJson.write(preset, pretty), StylePreset.Source.USER, null);
            assertEquals("流光玻璃", back.meta().name());
            assertEquals("Cherry", back.meta().author());
            assertEquals("26.3", back.meta().generatorMc());
            assertEquals(StylePreset.Base.DEFAULTS, back.base());
            assertEquals(EnumSet.of(Facet.LYRICS, Facet.VIPER), back.facets());
            assertEquals(Arrays.asList("hologram"), back.unknownFacets(), "认不出的分面原样保留，转存再分享不丢");
            assertEquals(3, back.domains().get("future").version(), "认不出的配置域也保留");
            assertEquals(0.3, back.domains().get("hud").values().get("glow").getAsDouble(), 1e-9);
        }
    }

    @Test
    void 不是预设或来自更新版本都拒绝() {
        assertThrows(PresetFormatException.class, () -> parse("{\"format\":\"something.else\"}"));
        PresetFormatException newer = assertThrows(PresetFormatException.class,
                () -> parse("{\"format\":\"muonium.preset\",\"formatVersion\":99}"));
        assertTrue(newer.getMessage().contains("更新"), newer.getMessage());
        assertThrows(PresetFormatException.class, () -> parse("[1,2,3]"));
        assertThrows(PresetFormatException.class, () -> parse("{not json"));
        assertThrows(PresetFormatException.class, () -> parse(""));
    }

    @Test
    void 深嵌套在解析之前就被拒掉() {
        StringBuilder deep = new StringBuilder("{\"format\":\"muonium.preset\",\"domains\":");
        for (int i = 0; i < 200_000; i++) {
            deep.append('[');
        }
        PresetFormatException error = assertThrows(PresetFormatException.class, () -> parse(deep.toString()));
        assertTrue(error.getMessage().contains("嵌套"), error.getMessage());
    }

    @Test
    void 字符串里的括号不计入嵌套深度() {
        assertEquals(1, PresetLimits.nestingDepth("{\"a\":\"[[[[{{{{\\\"]]]\"}"));
    }

    @Test
    void 元信息清洗控制字符与方向符并按码点截断() throws Exception {
        // JSON 转义写法：解析后才是真正的 RLO 与 BEL 字符。
        String evilName = "\\u202Egnp.exe\\u0007" + repeat("长", 60) + "😀";
        StylePreset preset = parse("{\"format\":\"muonium.preset\",\"meta\":{\"name\":\"" + evilName + "\"}}");
        String name = preset.meta().name();
        assertFalse(name.contains("\u202E"), "RLO 会把显示顺序倒过来，必须去掉");
        assertFalse(name.contains("\u0007"));
        assertEquals(PresetLimits.MAX_NAME_CHARS, name.codePointCount(0, name.length()));
        StylePreset unnamed = parse("{\"format\":\"muonium.preset\",\"meta\":{}}");
        assertEquals("未命名预设", unnamed.meta().name());
    }

    @Test
    void 超长文字值拒绝() {
        String big = repeat("x", PresetLimits.MAX_VALUE_STRING_CHARS + 1);
        assertThrows(PresetFormatException.class, () -> parse("{\"format\":\"muonium.preset\","
                + "\"domains\":{\"hud\":{\"v\":1,\"values\":{\"k\":\"" + big + "\"}}}}"));
    }

    @Test
    void 不合规的配置域id直接忽略() throws Exception {
        StylePreset preset = parse("{\"format\":\"muonium.preset\",\"domains\":{"
                + "\"../evil\":{\"values\":{\"a\":1}},\"ok_1\":{\"values\":{\"a\":1}},\"no\":{\"values\":3}}}");
        assertEquals(1, preset.domains().size());
        assertTrue(preset.domains().containsKey("ok_1"));
    }

    @Test
    void 缺省基准是只改写到的键() throws Exception {
        assertEquals(StylePreset.Base.CURRENT, parse("{\"format\":\"muonium.preset\"}").base(),
                "没写 base 的手写预设不能把整个分面重置掉");
        JsonObject withBom = new JsonObject();
        withBom.addProperty("format", "muonium.preset");
        assertEquals(StylePreset.Base.CURRENT, parse("\uFEFF" + withBom).base());
    }

    private static StylePreset parse(String text) throws PresetFormatException {
        return PresetJson.parse(text, StylePreset.Source.USER, null);
    }

    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            out.append(s);
        }
        return out.toString();
    }
}
