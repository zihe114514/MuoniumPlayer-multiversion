package com.muoniumplayer.core.preset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.preset.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PresetEngineTest {
    private FakeDomain hud, nested;
    private PresetEngine engine;
    @BeforeEach void setUp() {
        hud = new FakeDomain("hud", KeyPolicy.builder().facet(Facet.LYRICS, "glow", "style", "color")
                .facet(Facet.ISLAND, "islandScale").facet(Facet.LAYOUT, "x").exclude("测试：排除项", "secret")
                .enumMax("style", () -> 3).build(), json("{\"glow\":0.5,\"style\":1,\"color\":\"white\",\"islandScale\":1.0,\"x\":0.25,\"secret\":\"token\"}"));
        nested = new FakeDomain("bg", KeyPolicy.builder().facet(Facet.BACKGROUND, "mode", "common.opacity", "common.dim").build(),
                json("{\"mode\":\"latent\",\"common\":{\"opacity\":0.75,\"dim\":false}}"));
        engine = new PresetEngine(Arrays.asList(hud, nested));
    }
    @Test void 捕获只出所选分面且永远不出排除项() {
        Map<String, DomainFragment> capture = engine.capture(EnumSet.of(Facet.LYRICS));
        assertEquals(1, capture.size()); assertEquals(3, capture.get("hud").values().size());
        assertFalse(capture.get("hud").values().has("secret")); assertFalse(capture.get("hud").values().has("islandScale"));
    }
    @Test void 嵌套域按点路径捕获并还原成嵌套() {
        assertEquals(.75, engine.capture(EnumSet.of(Facet.BACKGROUND)).get("bg").values().getAsJsonObject("common").get("opacity").getAsDouble());
    }
    @Test void 出厂基准先重置所选分面再叠预设() {
        hud.state = json("{\"glow\":0.9,\"style\":2,\"color\":\"red\",\"islandScale\":1.3,\"x\":0.9,\"secret\":\"token\"}");
        CompatReport report = engine.apply(preset(StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"glow\":0.2}")), EnumSet.of(Facet.LYRICS));
        assertEquals(.2, hud.state.get("glow").getAsDouble()); assertEquals(1, hud.state.get("style").getAsInt());
        assertEquals("white", hud.state.get("color").getAsString()); assertEquals(1.3, hud.state.get("islandScale").getAsDouble()); assertTrue(report.lines().isEmpty());
    }
    @Test void 当前基准只改写到的键() {
        hud.state.addProperty("style", 2); hud.state.addProperty("color", "red");
        engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"glow\":0.2}")), EnumSet.of(Facet.LYRICS));
        assertEquals(2, hud.state.get("style").getAsInt()); assertEquals("red", hud.state.get("color").getAsString());
    }
    @Test void 没勾选的分面不动_片段里越界的分面也不动() {
        engine.apply(preset(StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS, Facet.ISLAND), fragment("hud", "{\"glow\":0.2,\"islandScale\":0.7,\"x\":0.1}")), EnumSet.of(Facet.LYRICS));
        assertEquals(1, hud.state.get("islandScale").getAsInt()); assertEquals(.25, hud.state.get("x").getAsDouble());
    }
    @Test void 排除项即使写进片段也不套用() {
        engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"secret\":\"stolen\"}")), EnumSet.of(Facet.LYRICS));
        assertEquals("token", hud.state.get("secret").getAsString()); assertEquals(0, hud.applyCount);
    }
    @Test void 未知键与形状不对与枚举越界都保留当前值并计数() {
        CompatReport report = engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"fromTheFuture\":1,\"glow\":\"bright\",\"style\":9,\"color\":\"blue\"}")), EnumSet.of(Facet.LYRICS));
        assertEquals(1, report.unsupportedKeys()); assertEquals(2, report.unsupportedValues());
        assertEquals(.5, hud.state.get("glow").getAsDouble()); assertEquals(1, hud.state.get("style").getAsInt()); assertEquals("blue", hud.state.get("color").getAsString());
    }
    @Test void 净化丢弃的值保留当前并有提示() {
        hud.sanitizer = (key, value) -> "color".equals(key) ? null : value;
        CompatReport report = engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"color\":\"evil\"}")), EnumSet.of(Facet.LYRICS));
        assertEquals("white", hud.state.get("color").getAsString()); assertTrue(report.lines().contains("假域丢弃了 color"));
    }
    @Test void 浮点精度相同不算变化() {
        hud.state.addProperty("glow", 1.0115517f);
        assertEquals(0, engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"glow\":1.0115517}")), EnumSet.of(Facet.LYRICS)).changedKeys());
        assertEquals(0, hud.applyCount);
    }
    @Test void 嵌套片段按点路径叠加() {
        engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.BACKGROUND), fragment("bg", "{\"common\":{\"dim\":true}}")), EnumSet.of(Facet.BACKGROUND));
        assertTrue(nested.state.getAsJsonObject("common").get("dim").getAsBoolean()); assertEquals(.75, nested.state.getAsJsonObject("common").get("opacity").getAsDouble());
    }
    @Test void 预检不写任何东西() {
        assertEquals(1, engine.plan(preset(StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"glow\":0.2}")), EnumSet.of(Facet.LYRICS)).changedKeys());
        assertEquals(0, hud.applyCount); assertEquals(.5, hud.state.get("glow").getAsDouble());
    }
    @Test void 单个域失败回滚全部域_包括先写后失败的域() {
        nested.failOnApply = new IllegalStateException("炸了"); nested.mutateBeforeFailure = true;
        JsonObject beforeHud = hud.snapshot(), beforeNested = nested.snapshot();
        CompatReport report = engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS, Facet.BACKGROUND),
                fragment("hud", "{\"glow\":0.2}"), fragment("bg", "{\"mode\":\"nomand\"}")), EnumSet.of(Facet.LYRICS, Facet.BACKGROUND));
        assertTrue(report.hasFailures()); assertTrue(report.wasRolledBack()); assertFalse(report.rollbackFailed());
        assertEquals(0, report.changedKeys()); assertEquals(beforeHud, hud.state); assertEquals(beforeNested, nested.state);
    }
    @Test void 出厂基准下负数小数未知枚举都保留当前而不是默认() {
        for (double invalid : new double[]{-1, .5, 9}) {
            hud.state.addProperty("style", 2);
            CompatReport report = engine.apply(preset(StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"style\":" + invalid + "}")), EnumSet.of(Facet.LYRICS));
            assertEquals(2, hud.state.get("style").getAsInt()); assertEquals(1, report.unsupportedValues());
        }
    }
    @Test void 完整当前快照保留未知本地字段与空对象() {
        hud.state.add("extra", json("{\"empty\":{},\"literal.key\":\"private\"}"));
        JsonObject privateBefore = hud.state.getAsJsonObject("extra").deepCopy();
        engine.apply(preset(StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS), fragment("hud", "{\"glow\":0.2}")), EnumSet.of(Facet.LYRICS));
        assertEquals(privateBefore, hud.state.getAsJsonObject("extra")); assertFalse(engine.capture(EnumSet.of(Facet.LYRICS)).get("hud").values().has("extra"));
    }
    @Test void 完整本地capture_restore包含排除项且缺字段叠加现状() {
        JsonObject before = engine.captureState(); hud.state.addProperty("secret", "new"); hud.state.addProperty("glow", .9);
        CompatReport report = engine.restoreState(before); assertFalse(report.hasFailures()); assertEquals("token", hud.state.get("secret").getAsString());
        engine.restoreState(json("{\"hud\":{\"glow\":0.2}}")); assertEquals("token", hud.state.get("secret").getAsString()); assertEquals(1, hud.state.get("style").getAsInt());
    }
    @Test void 不认识的配置域与分面只提示不报错() {
        Map<String, DomainFragment> domains = new LinkedHashMap<>(); domains.put("future", new DomainFragment(1, json("{\"a\":1}")));
        CompatReport report = engine.apply(new StylePreset(meta(), StylePreset.Base.CURRENT, EnumSet.of(Facet.LYRICS), Arrays.asList("hologram"), domains, StylePreset.Source.USER, null), EnumSet.of(Facet.LYRICS));
        assertFalse(report.hasFailures()); assertEquals(2, report.warnings().size());
    }
    @Test void 相同值比较按浮点精度() {
        assertTrue(PresetEngine.sameValue(new JsonPrimitive(.1f), JsonParser.parseString("0.1"))); assertFalse(PresetEngine.sameValue(new JsonPrimitive(.1f), JsonParser.parseString("0.2")));
        assertTrue(PresetEngine.sameValue(new JsonPrimitive("a"), new JsonPrimitive("a"))); assertFalse(PresetEngine.sameValue(null, new JsonPrimitive("a")));
    }
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static Map.Entry<String, DomainFragment> fragment(String domain, String values) { return new java.util.AbstractMap.SimpleEntry<>(domain, new DomainFragment(1, json(values))); }
    @SafeVarargs private static StylePreset preset(StylePreset.Base base, Set<Facet> facets, Map.Entry<String, DomainFragment>... entries) {
        Map<String, DomainFragment> fragments = new LinkedHashMap<>(); for (Map.Entry<String, DomainFragment> entry : entries) fragments.put(entry.getKey(), entry.getValue());
        return new StylePreset(meta(), base, facets, null, fragments, StylePreset.Source.USER, null);
    }
    static PresetMeta meta() { return new PresetMeta("user:test", "测试预设", "", "", "", "", ""); }
}
