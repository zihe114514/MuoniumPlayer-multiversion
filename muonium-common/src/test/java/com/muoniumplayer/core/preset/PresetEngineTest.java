package com.muoniumplayer.core.preset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.preset.model.PresetMeta;
import com.muoniumplayer.core.preset.model.StylePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预设引擎的合并语义：白名单、分面边界、两种基准、兼容判定、失败隔离。
 * 全用假配置域跑，不碰任何真实设置与文件。
 */
class PresetEngineTest {

    private FakeDomain hud;
    private FakeDomain nested;
    private PresetEngine engine;

    @BeforeEach
    void setUp() {
        KeyPolicy hudPolicy = KeyPolicy.builder()
                .facet(Facet.LYRICS, "glow", "style", "color")
                .facet(Facet.ISLAND, "islandScale")
                .facet(Facet.LAYOUT, "x")
                .exclude("测试：排除项", "secret")
                .enumMax("style", () -> 3)
                .build();
        hud = new FakeDomain("hud", hudPolicy, json("{\"glow\":0.5,\"style\":1,\"color\":\"white\","
                + "\"islandScale\":1.0,\"x\":0.25,\"secret\":\"token\"}"));
        KeyPolicy nestedPolicy = KeyPolicy.builder()
                .facet(Facet.BACKGROUND, "mode", "common.opacity", "common.dim")
                .build();
        nested = new FakeDomain("bg", nestedPolicy, json("{\"mode\":\"latent\",\"common\":{\"opacity\":0.75,\"dim\":false}}"));
        engine = new PresetEngine(Arrays.asList(hud, nested));
    }

    @Test
    void 捕获只出所选分面且永远不出排除项() {
        Map<String, DomainFragment> captured = engine.capture(EnumSet.of(Facet.LYRICS));
        assertEquals(1, captured.size(), "只有 hud 域涉及歌词分面");
        JsonObject values = captured.get("hud").values();
        assertEquals(3, values.size(), "歌词分面的三个键全量导出");
        assertTrue(values.has("glow") && values.has("style") && values.has("color"));
        assertFalse(values.has("secret"), "排除项绝不导出");
        assertFalse(values.has("islandScale"), "没选的分面不导出");
    }

    @Test
    void 嵌套域按点路径捕获并还原成嵌套() {
        Map<String, DomainFragment> captured = engine.capture(EnumSet.of(Facet.BACKGROUND));
        JsonObject values = captured.get("bg").values();
        assertEquals(0.75, values.getAsJsonObject("common").get("opacity").getAsDouble(), 1e-9);
    }

    @Test
    void 出厂基准先重置所选分面再叠预设() {
        hud.state = json("{\"glow\":0.9,\"style\":2,\"color\":\"red\",\"islandScale\":1.3,\"x\":0.9,\"secret\":\"token\"}");
        StylePreset preset = preset(StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS),
                fragment("hud", "{\"glow\":0.2}"));
        CompatReport report = engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertEquals(0.2, hud.state.get("glow").getAsDouble(), 1e-6, "预设写到的键取预设值");
        assertEquals(1, hud.state.get("style").getAsInt(), "预设没写的歌词键回到出厂值");
        assertEquals("white", hud.state.get("color").getAsString());
        assertEquals(1.3, hud.state.get("islandScale").getAsDouble(), 1e-6, "别的分面原样保留");
        assertEquals(0.9, hud.state.get("x").getAsDouble(), 1e-6);
        assertTrue(report.lines().isEmpty(), "没有任何提示：" + report.lines());
    }

    @Test
    void 当前基准只改写到的键() {
        hud.state = json("{\"glow\":0.9,\"style\":2,\"color\":\"red\",\"islandScale\":1.3,\"x\":0.9,\"secret\":\"token\"}");
        StylePreset preset = preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS),
                fragment("hud", "{\"glow\":0.2}"));
        engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertEquals(0.2, hud.state.get("glow").getAsDouble(), 1e-6);
        assertEquals(2, hud.state.get("style").getAsInt(), "没写到的键保持现状");
        assertEquals("red", hud.state.get("color").getAsString());
    }

    @Test
    void 没勾选的分面不动_片段里越界的分面也不动() {
        StylePreset preset = preset(StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS, Facet.ISLAND),
                fragment("hud", "{\"glow\":0.2,\"islandScale\":0.7,\"x\":0.1}"));
        engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertEquals(0.2, hud.state.get("glow").getAsDouble(), 1e-6);
        assertEquals(1.0, hud.state.get("islandScale").getAsDouble(), 1e-6, "灵动岛没勾选");
        assertEquals(0.25, hud.state.get("x").getAsDouble(), 1e-6, "布局不在预设声明的分面里，片段里写了也不认");
    }

    @Test
    void 排除项即使写进片段也不套用() {
        StylePreset preset = preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS),
                fragment("hud", "{\"secret\":\"stolen\"}"));
        engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertEquals("token", hud.state.get("secret").getAsString());
        assertEquals(0, hud.applyCount, "没有任何变化就不该写盘");
    }

    @Test
    void 未知键与形状不对与枚举越界都保留当前值并计数() {
        StylePreset preset = preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS),
                fragment("hud", "{\"fromTheFuture\":1,\"glow\":\"bright\",\"style\":9,\"color\":\"blue\"}"));
        CompatReport report = engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertEquals(1, report.unsupportedKeys());
        assertEquals(2, report.unsupportedValues(), "字符串塞进数字键 + 枚举超过本地上限");
        assertEquals(0.5, hud.state.get("glow").getAsDouble(), 1e-6);
        assertEquals(1, hud.state.get("style").getAsInt(), "枚举越界不交给钳位，保留当前值");
        assertEquals("blue", hud.state.get("color").getAsString(), "合法的照常套用");
        assertFalse(report.lines().isEmpty());
    }

    @Test
    void 净化丢弃的值保留当前并有提示() {
        hud.sanitizer = (key, value) -> "color".equals(key) ? null : value;
        StylePreset preset = preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS),
                fragment("hud", "{\"color\":\"evil\"}"));
        CompatReport report = engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertEquals("white", hud.state.get("color").getAsString());
        assertTrue(report.lines().contains("假域丢弃了 color"));
    }

    @Test
    void 浮点精度相同不算变化() {
        hud.state.addProperty("glow", 1.0115517f);
        StylePreset preset = preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS),
                fragment("hud", "{\"glow\":1.0115517}"));
        CompatReport report = engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertEquals(0, report.changedKeys());
        assertEquals(0, hud.applyCount);
    }

    @Test
    void 嵌套片段按点路径叠加() {
        StylePreset preset = preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.BACKGROUND),
                fragment("bg", "{\"common\":{\"dim\":true}}"));
        engine.apply(preset, EnumSet.of(Facet.BACKGROUND));
        assertTrue(nested.state.getAsJsonObject("common").get("dim").getAsBoolean());
        assertEquals(0.75, nested.state.getAsJsonObject("common").get("opacity").getAsDouble(), 1e-6,
                "同一对象里没写到的键保留");
    }

    @Test
    void 预检不写任何东西() {
        StylePreset preset = preset(StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS),
                fragment("hud", "{\"glow\":0.2}"));
        CompatReport report = engine.plan(preset, EnumSet.of(Facet.LYRICS));
        assertEquals(1, report.changedKeys());
        assertEquals(0, hud.applyCount);
        assertEquals(0.5, hud.state.get("glow").getAsDouble(), 1e-6);
    }

    @Test
    void 单个域失败不连累其它域() {
        hud.failOnApply = new IllegalStateException("炸了");
        StylePreset preset = preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS, Facet.BACKGROUND),
                fragment("hud", "{\"glow\":0.2}"), fragment("bg", "{\"mode\":\"nomand\"}"));
        CompatReport report = engine.apply(preset, EnumSet.of(Facet.LYRICS, Facet.BACKGROUND));
        assertTrue(report.hasFailures());
        assertEquals("nomand", nested.state.get("mode").getAsString(), "背景照常套用");
    }

    @Test
    void 不认识的配置域与分面只提示不报错() {
        Map<String, DomainFragment> domains = new LinkedHashMap<String, DomainFragment>();
        domains.put("future", new DomainFragment(1, json("{\"a\":1}")));
        StylePreset preset = new StylePreset(meta(), StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS),
                Arrays.asList("hologram"), domains, StylePreset.Source.USER, null);
        CompatReport report = engine.apply(preset, EnumSet.of(Facet.LYRICS));
        assertFalse(report.hasFailures());
        assertEquals(2, report.warnings().size(), report.warnings().toString());
    }

    @Test
    void 相同值比较按浮点精度() {
        assertTrue(PresetEngine.sameValue(new JsonPrimitive(0.1f), JsonParser.parseString("0.1")));
        assertFalse(PresetEngine.sameValue(new JsonPrimitive(0.1f), JsonParser.parseString("0.2")));
        assertTrue(PresetEngine.sameValue(new JsonPrimitive("a"), new JsonPrimitive("a")));
        assertFalse(PresetEngine.sameValue(null, new JsonPrimitive("a")));
    }

    // ==================== 小工具 ====================

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static Map.Entry<String, DomainFragment> fragment(String domain, String values) {
        return new java.util.AbstractMap.SimpleEntry<String, DomainFragment>(domain,
                new DomainFragment(1, json(values)));
    }

    @SafeVarargs
    private static StylePreset preset(StylePreset.Base base, Set<Facet> facets,
                                      Map.Entry<String, DomainFragment>... fragments) {
        Map<String, DomainFragment> domains = new LinkedHashMap<String, DomainFragment>();
        for (Map.Entry<String, DomainFragment> entry : fragments) {
            domains.put(entry.getKey(), entry.getValue());
        }
        return new StylePreset(meta(), base, facets, null, domains, StylePreset.Source.USER, null);
    }

    static PresetMeta meta() {
        return new PresetMeta("user:test", "测试预设", "", "", "", "", "");
    }
}
