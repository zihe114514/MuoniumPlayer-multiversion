package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.codec.*;
import com.muoniumplayer.core.preset.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompactShareCodeTest {
    private FakeDomain look, audio;
    private CompactShareCode codec;
    @BeforeEach void setUp() {
        StringBuilder defaults = new StringBuilder("{\"glow\":0.5,\"style\":1,\"shown\":true,\"color\":-1,\"font\":\"builtin\",\"unit\":{\"id\":\"velvet_rose\",\"customColors\":{}},\"blank\":null");
        KeyPolicy.Builder policy = KeyPolicy.builder().facet(Facet.LYRICS, "glow", "style", "color", "font", "unit", "blank").facet(Facet.VISIBILITY, "shown").exclude("测试：排除项", "secret");
        String[] sliders = new String[40]; for (int i = 0; i < sliders.length; i++) { sliders[i] = "slider" + i; defaults.append(",\"slider").append(i).append("\":1.0"); }
        policy.facet(Facet.PANEL, sliders); defaults.append(",\"secret\":\"token\"}");
        look = new FakeDomain("hud", 1, policy.build(), PresetEngineTest.json(defaults.toString()));
        audio = new FakeDomain("acoustics", 7, KeyPolicy.builder().facet(Facet.ACOUSTICS, "enabled", "strength").build(), PresetEngineTest.json("{\"enabled\":false,\"strength\":100}"));
        codec = new CompactShareCode(Arrays.asList(look, audio));
    }
    @Test void 往返后套用结果与原预设一致() throws Exception {
        look.state.addProperty("glow", .82f); look.state.addProperty("style", 3); look.state.addProperty("shown", false); look.state.addProperty("color", 0xFFE04D);
        look.state.addProperty("font", "system:思源黑体"); look.state.add("unit", PresetEngineTest.json("{\"id\":\"aurora_tide\",\"customColors\":{\"ACCENT\":\"#54D6C1\"}}"));
        look.state.addProperty("slider7", 1.0115517f); look.state.addProperty("slider8", -.25f); audio.state.addProperty("enabled", true);
        StylePreset original = capture(EnumSet.of(Facet.LYRICS, Facet.VISIBILITY, Facet.PANEL, Facet.ACOUSTICS)); JsonObject authorLook = look.snapshot(), authorAudio = audio.snapshot();
        String code = codec.encode(original); StylePreset decoded = codec.decode("快来试试 " + codec.shareLine(original) + " 好看吗");
        assertEquals(original.facets(), decoded.facets()); assertEquals(StylePreset.Base.DEFAULTS, decoded.base()); assertEquals("测试外观", decoded.meta().name());
        look.state = look.defaults(); look.state.addProperty("glow", .1f); look.state.addProperty("slider3", 1.9f); audio.state.addProperty("strength", 40);
        assertFalse(new PresetEngine(Arrays.asList(look, audio)).apply(decoded, decoded.facets()).hasFailures()); assertSameState(authorLook, look.snapshot()); assertSameState(authorAudio, audio.snapshot()); assertTrue(code.length() < 200);
    }
    @Test void 等于出厂值的键一个字节都不写() throws Exception {
        look.state.addProperty("glow", .82f); StylePreset decoded = codec.decode(codec.encode(capture(EnumSet.of(Facet.LYRICS, Facet.PANEL))));
        assertEquals(Collections.singleton("glow"), decoded.domains().get("hud").values().keySet()); assertFalse(decoded.domains().containsKey("acoustics"));
    }
    @Test void 比v1短一个数量级() {
        for (int i = 0; i < 40; i += 2) look.state.addProperty("slider" + i, .3f + i * .01f); look.state.addProperty("glow", .82f);
        StylePreset preset = capture(EnumSet.of(Facet.LYRICS, Facet.VISIBILITY, Facet.PANEL, Facet.ACOUSTICS));
        assertTrue(codec.encode(preset).length() * 4 < ShareCode.encode(PresetJson.write(preset, false)).length());
    }
    @Test void 新版本多出的键在旧版本里记作不支持() throws Exception {
        FakeDomain newer = new FakeDomain("hud", 1, KeyPolicy.builder().facet(Facet.LYRICS, "glow", "fromTheFuture").build(), PresetEngineTest.json("{\"glow\":0.5,\"fromTheFuture\":0}"));
        newer.state.addProperty("glow", .9f); newer.state.addProperty("fromTheFuture", 7);
        PresetEngine newEngine = new PresetEngine(Collections.singletonList(newer));
        StylePreset preset = new StylePreset(meta(), StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS), null, newEngine.capture(EnumSet.of(Facet.LYRICS)), StylePreset.Source.USER, null);
        String code = new CompactShareCode(Collections.singletonList(newer)).encode(preset);
        FakeDomain older = new FakeDomain("hud", 1, KeyPolicy.builder().facet(Facet.LYRICS, "glow").build(), PresetEngineTest.json("{\"glow\":0.5}"));
        StylePreset decoded = new CompactShareCode(Collections.singletonList(older)).decode(code);
        CompatReport report = new PresetEngine(Collections.singletonList(older)).apply(decoded, decoded.facets()); assertEquals(.9, older.state.get("glow").getAsDouble(), 1e-6); assertEquals(1, report.unsupportedKeys());
    }
    @Test void 不认识的配置域整段跳过并提示() throws Exception {
        audio.state.addProperty("enabled", true); look.state.addProperty("glow", .2f);
        StylePreset decoded = new CompactShareCode(Collections.singletonList(look)).decode(codec.encode(capture(EnumSet.of(Facet.LYRICS, Facet.ACOUSTICS)))); assertTrue(decoded.domains().containsKey("code-7"));
    }
    @Test void 只改写到的键的预设不做差分() throws Exception {
        Map<String, DomainFragment> domains = new LinkedHashMap<>(); domains.put("hud", new DomainFragment(1, PresetEngineTest.json("{\"shown\":false,\"glow\":0.5}")));
        StylePreset decoded = codec.decode(codec.encode(new StylePreset(meta(), StylePreset.Base.CURRENT, EnumSet.of(Facet.VISIBILITY, Facet.LYRICS), null, domains, StylePreset.Source.USER, null)));
        assertEquals(StylePreset.Base.CURRENT, decoded.base()); assertEquals(2, decoded.domains().get("hud").values().size());
    }
    @Test void 坏码给出明确原因() {
        look.state.addProperty("glow", .82f); String code = codec.encode(capture(EnumSet.of(Facet.LYRICS))), body = code.substring(CompactShareCode.PREFIX.length());
        assertThrows(PresetFormatException.class, () -> codec.decode(CompactShareCode.PREFIX + body.substring(0, body.length() - 2)));
        char flipped = body.charAt(3) == 'A' ? 'B' : 'A'; assertThrows(PresetFormatException.class, () -> codec.decode(CompactShareCode.PREFIX + body.substring(0, 3) + flipped + body.substring(4)));
        assertThrows(PresetFormatException.class, () -> codec.decode(CompactShareCode.PREFIX + "0")); assertThrows(PresetFormatException.class, () -> codec.decode("没有码"));
        assertTrue(assertThrows(PresetFormatException.class, () -> codec.decode(CompactShareCode.PREFIX + Base62.encode(new byte[]{0x31, 0, 0, 0, 0}))).getMessage().contains("更新"));
    }
    @Test void base62往返含前导零() {
        Random random = new Random(42); for (int round = 0; round < 200; round++) {
            byte[] bytes = new byte[random.nextInt(40)]; random.nextBytes(bytes); if (bytes.length > 2 && round % 3 == 0) { bytes[0] = 0; bytes[1] = 0; }
            String text = Base62.encode(bytes); for (char c : text.toCharArray()) assertTrue(Base62.isDigit(c)); assertArrayEquals(bytes, Base62.decode(text));
        }
    }
    @Test void zigzag与varint边界() {
        for (long value : new long[]{0, 1, -1, 63, -64, 0xFFFFFFFFL, Integer.MIN_VALUE, Long.MAX_VALUE >> 2}) assertEquals(value, CompactShareCode.unzigzag(CompactShareCode.zigzag(value)));
        assertEquals(1, CompactShareCode.varintSize(127)); assertEquals(2, CompactShareCode.varintSize(128));
    }
    @Test void 小数只保留四位有效数字整数永远精确() throws Exception {
        look.state.addProperty("glow", .5258621f); look.state.addProperty("color", 0xFFE04D); look.state.addProperty("slider1", 280.68964f);
        JsonObject values = codec.decode(codec.encode(capture(EnumSet.of(Facet.LYRICS, Facet.PANEL)))).domains().get("hud").values();
        assertEquals(.5259f, values.get("glow").getAsFloat(), 1e-7f); assertEquals(0xFFE04D, values.get("color").getAsInt()); assertEquals(280.7f, values.get("slider1").getAsFloat(), 1e-4f);
    }
    @Test void 非压缩正文过大和浮点NaN拒绝() {
        look.state.addProperty("glow", Float.NaN); assertThrows(IllegalArgumentException.class, () -> codec.encode(capture(EnumSet.of(Facet.LYRICS))));
        byte[] oversized = new byte[65540]; oversized[0] = 0x20;
        assertThrows(PresetFormatException.class, () -> codec.decode(CompactShareCode.PREFIX + Base62.encode(oversized)));
    }
    private StylePreset capture(Set<Facet> facets) { return new StylePreset(meta(), StylePreset.Base.DEFAULTS, facets, null, new PresetEngine(Arrays.asList(look, audio)).capture(facets), StylePreset.Source.USER, null); }
    private static PresetMeta meta() { return new PresetMeta("user:t", "测试外观", "", "", "", "", ""); }
    private static void assertSameState(JsonObject expected, JsonObject actual) {
        Map<String, JsonElement> left = JsonPaths.flatten(expected, key -> true), right = JsonPaths.flatten(actual, key -> true); assertEquals(left.keySet(), right.keySet());
        for (Map.Entry<String, JsonElement> entry : left.entrySet()) assertTrue(close(entry.getValue(), right.get(entry.getKey())), entry.getKey() + " " + entry.getValue() + " vs " + right.get(entry.getKey()));
    }
    private static boolean close(JsonElement a, JsonElement b) {
        if (a != null && b != null && a.isJsonPrimitive() && b.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber()) {
            double x = a.getAsDouble(), y = b.getAsDouble(); return Math.abs(x - y) <= 1e-3 * Math.max(1e-6, Math.max(Math.abs(x), Math.abs(y)));
        }
        return PresetEngine.sameValue(a, b);
    }
}
