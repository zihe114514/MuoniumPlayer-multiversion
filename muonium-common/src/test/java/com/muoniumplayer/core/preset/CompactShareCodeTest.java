package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.codec.Base62;
import com.muoniumplayer.core.preset.codec.JsonPaths;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.codec.PresetJson;
import com.muoniumplayer.core.preset.codec.ShareCode;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.preset.model.PresetMeta;
import com.muoniumplayer.core.preset.model.StylePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分享码 v2：往返、只写改过的项、长度、向前兼容、以及对坏码的拒绝。 */
class CompactShareCodeTest {

    private FakeDomain look;
    private FakeDomain audio;
    private CompactShareCode codec;

    @BeforeEach
    void setUp() {
        StringBuilder defaults = new StringBuilder("{\"glow\":0.5,\"style\":1,\"shown\":true,\"color\":-1,"
                + "\"font\":\"builtin\",\"unit\":{\"id\":\"velvet_rose\",\"customColors\":{}},\"blank\":null");
        KeyPolicy.Builder policy = KeyPolicy.builder()
                .facet(Facet.LYRICS, "glow", "style", "color", "font", "unit", "blank")
                .facet(Facet.VISIBILITY, "shown")
                .exclude("测试：排除项", "secret");
        // 再造 40 个浮点键，模拟一套真实外观里的大量滑条。
        String[] sliders = new String[40];
        for (int i = 0; i < sliders.length; i++) {
            sliders[i] = "slider" + i;
            defaults.append(",\"slider").append(i).append("\":1.0");
        }
        policy.facet(Facet.PANEL, sliders);
        defaults.append(",\"secret\":\"token\"}");
        look = new FakeDomain("hud", 1, policy.build(), PresetEngineTest.json(defaults.toString()));
        audio = new FakeDomain("acoustics", 7, KeyPolicy.builder()
                .facet(Facet.ACOUSTICS, "enabled", "strength").build(),
                PresetEngineTest.json("{\"enabled\":false,\"strength\":100}"));
        codec = new CompactShareCode(Arrays.asList(look, audio));
    }

    @Test
    void 往返后套用结果与原预设一致() throws Exception {
        look.state.addProperty("glow", 0.82f);
        look.state.addProperty("style", 3);
        look.state.addProperty("shown", false);
        look.state.addProperty("color", 0xFFE04D);
        look.state.addProperty("font", "system:思源黑体");
        look.state.add("unit", PresetEngineTest.json("{\"id\":\"aurora_tide\",\"customColors\":{\"ACCENT\":\"#54D6C1\"}}"));
        look.state.addProperty("slider7", 1.0115517f);
        look.state.addProperty("slider8", -0.25f);
        audio.state.addProperty("enabled", true);
        StylePreset original = capture(EnumSet.of(Facet.LYRICS, Facet.VISIBILITY, Facet.PANEL, Facet.ACOUSTICS));
        JsonObject authorLook = look.snapshot();
        JsonObject authorAudio = audio.snapshot();

        String code = codec.encode(original);
        StylePreset decoded = codec.decode("快来试试 " + codec.shareLine(original) + " 好看吗");
        assertEquals(original.facets(), decoded.facets());
        assertEquals(StylePreset.Base.DEFAULTS, decoded.base());
        assertEquals("测试外观", decoded.meta().name());

        // 另一位玩家的状态各不相同：套用后必须与作者一模一样。
        look.state = look.defaults();
        look.state.addProperty("glow", 0.1f);
        look.state.addProperty("slider3", 1.9f);
        audio.state = PresetEngineTest.json("{\"enabled\":false,\"strength\":40}");
        PresetEngine engine = new PresetEngine(Arrays.asList(look, audio));
        CompatReport report = engine.apply(decoded, decoded.facets());
        assertFalse(report.hasFailures());
        assertSameState(authorLook, look.snapshot());
        assertSameState(authorAudio, audio.snapshot());
        assertTrue(code.length() < 200, "改了 9 项（含一个主题对象与一个字体名）的分享码：" + code.length() + " 字 " + code);
    }

    @Test
    void 等于出厂值的键一个字节都不写() throws Exception {
        look.state.addProperty("glow", 0.82f);
        StylePreset preset = capture(EnumSet.of(Facet.LYRICS, Facet.PANEL));
        StylePreset decoded = codec.decode(codec.encode(preset));
        JsonObject values = decoded.domains().get("hud").values();
        assertEquals(Collections.singleton("glow"), values.keySet(), "只有 glow 改过：" + values);
        assertFalse(decoded.domains().containsKey("acoustics"), "没选的分面不出现");
    }

    @Test
    void 比v1短一个数量级() {
        for (int i = 0; i < 40; i += 2) {
            look.state.addProperty("slider" + i, 0.3f + i * 0.01f);
        }
        look.state.addProperty("glow", 0.82f);
        StylePreset preset = capture(EnumSet.of(Facet.LYRICS, Facet.VISIBILITY, Facet.PANEL, Facet.ACOUSTICS));
        String v2 = codec.encode(preset);
        String v1 = ShareCode.encode(PresetJson.write(preset, false));
        assertTrue(v2.length() * 4 < v1.length(), "v2 " + v2.length() + " 字，v1 " + v1.length() + " 字");
    }

    @Test
    void 新版本多出的键在旧版本里记作不支持() throws Exception {
        FakeDomain newer = new FakeDomain("hud", 1, KeyPolicy.builder()
                .facet(Facet.LYRICS, "glow", "fromTheFuture").build(),
                PresetEngineTest.json("{\"glow\":0.5,\"fromTheFuture\":0}"));
        newer.state.addProperty("glow", 0.9f);
        newer.state.addProperty("fromTheFuture", 7);
        CompactShareCode newerCodec = new CompactShareCode(Collections.<com.muoniumplayer.core.preset.domain.PresetDomain>singletonList(newer));
        StylePreset preset = new StylePreset(meta(), StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS), null,
                new PresetEngine(Collections.<com.muoniumplayer.core.preset.domain.PresetDomain>singletonList(newer))
                        .capture(EnumSet.of(Facet.LYRICS)), StylePreset.Source.USER, null);
        String code = newerCodec.encode(preset);

        FakeDomain older = new FakeDomain("hud", 1, KeyPolicy.builder().facet(Facet.LYRICS, "glow").build(),
                PresetEngineTest.json("{\"glow\":0.5}"));
        StylePreset decoded = new CompactShareCode(Collections.<com.muoniumplayer.core.preset.domain.PresetDomain>singletonList(older)).decode(code);
        CompatReport report = new PresetEngine(Collections.<com.muoniumplayer.core.preset.domain.PresetDomain>singletonList(older))
                .apply(decoded, decoded.facets());
        assertEquals(0.9, older.state.get("glow").getAsDouble(), 1e-6, "认识的键照常套用");
        assertEquals(1, report.unsupportedKeys(), "多出来的那一个记作不支持");
    }

    @Test
    void 不认识的配置域整段跳过并提示() throws Exception {
        audio.state.addProperty("enabled", true);
        look.state.addProperty("glow", 0.2f);
        String code = codec.encode(capture(EnumSet.of(Facet.LYRICS, Facet.ACOUSTICS)));
        StylePreset decoded = new CompactShareCode(Collections.<com.muoniumplayer.core.preset.domain.PresetDomain>singletonList(look)).decode(code);
        assertEquals(0.2, decoded.domains().get("hud").values().get("glow").getAsDouble(), 1e-6);
        assertTrue(decoded.domains().containsKey("code-7"), "不认识的域留占位，引擎会提示");
    }

    @Test
    void 只改写到的键的预设不做差分() throws Exception {
        Map<String, DomainFragment> domains = new LinkedHashMap<String, DomainFragment>();
        domains.put("hud", new DomainFragment(1, PresetEngineTest.json("{\"shown\":false,\"glow\":0.5}")));
        StylePreset preset = new StylePreset(meta(), StylePreset.Base.CURRENT, EnumSet.of(Facet.VISIBILITY, Facet.LYRICS),
                null, domains, StylePreset.Source.USER, null);
        StylePreset decoded = codec.decode(codec.encode(preset));
        assertEquals(StylePreset.Base.CURRENT, decoded.base());
        assertEquals(2, decoded.domains().get("hud").values().size(), "base=current 时写到的都要保留，哪怕等于默认");
    }

    @Test
    void 坏码给出明确原因() {
        look.state.addProperty("glow", 0.82f);
        String code = codec.encode(capture(EnumSet.of(Facet.LYRICS)));
        String body = code.substring(CompactShareCode.PREFIX.length());
        assertThrows(PresetFormatException.class, () -> codec.decode(CompactShareCode.PREFIX + body.substring(0, body.length() - 2)));
        char flipped = body.charAt(3) == 'A' ? 'B' : 'A';
        String tampered = CompactShareCode.PREFIX + body.substring(0, 3) + flipped + body.substring(4);
        assertThrows(PresetFormatException.class, () -> codec.decode(tampered));
        assertThrows(PresetFormatException.class, () -> codec.decode(CompactShareCode.PREFIX + "0"));
        assertThrows(PresetFormatException.class, () -> codec.decode("没有码"));
        // 版本号写成 3：来自更新的版本。
        byte[] future = {(byte) 0x31, 0, 0, 0, 0};
        PresetFormatException newer = assertThrows(PresetFormatException.class,
                () -> codec.decode(CompactShareCode.PREFIX + Base62.encode(future)));
        assertTrue(newer.getMessage().contains("更新"), newer.getMessage());
    }

    @Test
    void base62往返含前导零() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            byte[] bytes = new byte[random.nextInt(40)];
            random.nextBytes(bytes);
            if (bytes.length > 2 && round % 3 == 0) {
                bytes[0] = 0;
                bytes[1] = 0;
            }
            String text = Base62.encode(bytes);
            for (char c : text.toCharArray()) {
                assertTrue(Base62.isDigit(c), "只出字母数字：" + text);
            }
            assertArrayEquals(bytes, Base62.decode(text));
        }
    }

    @Test
    void zigzag与varint边界() {
        for (long value : new long[] {0, 1, -1, 63, -64, 0xFFFFFFFFL, Integer.MIN_VALUE, Long.MAX_VALUE >> 2}) {
            assertEquals(value, CompactShareCode.unzigzag(CompactShareCode.zigzag(value)));
        }
        assertEquals(1, CompactShareCode.varintSize(127));
        assertEquals(2, CompactShareCode.varintSize(128));
    }

    // ==================== 小工具 ====================

    private StylePreset capture(java.util.Set<Facet> facets) {
        PresetEngine engine = new PresetEngine(Arrays.asList(look, audio));
        return new StylePreset(meta(), StylePreset.Base.DEFAULTS, facets, null, engine.capture(facets),
                StylePreset.Source.USER, null);
    }

    private static PresetMeta meta() {
        return new PresetMeta("user:t", "测试外观", "", "", "", "", "");
    }

    private static void assertSameState(JsonObject expected, JsonObject actual) {
        Map<String, JsonElement> left = JsonPaths.flatten(expected, key -> true);
        Map<String, JsonElement> right = JsonPaths.flatten(actual, key -> true);
        assertEquals(left.keySet(), right.keySet());
        for (Map.Entry<String, JsonElement> entry : left.entrySet()) {
            assertTrue(close(entry.getValue(), right.get(entry.getKey())),
                    entry.getKey() + "：期望 " + entry.getValue() + "，实际 " + right.get(entry.getKey()));
        }
    }

    /** 小数在分享码里保留 4 位有效数字：相对误差 1e-3 以内算同一个值；其余类型必须完全相同。 */
    private static boolean close(JsonElement a, JsonElement b) {
        if (a != null && b != null && a.isJsonPrimitive() && b.isJsonPrimitive()
                && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber()) {
            double x = a.getAsDouble();
            double y = b.getAsDouble();
            return Math.abs(x - y) <= 1e-3 * Math.max(1e-6, Math.max(Math.abs(x), Math.abs(y)));
        }
        return PresetEngine.sameValue(a, b);
    }

    @Test
    void 小数只保留四位有效数字整数永远精确() throws Exception {
        look.state.addProperty("glow", 0.5258621f);
        look.state.addProperty("color", 0xFFE04D);
        look.state.addProperty("slider1", 280.68964f);
        StylePreset decoded = codec.decode(codec.encode(capture(EnumSet.of(Facet.LYRICS, Facet.PANEL))));
        JsonObject values = decoded.domains().get("hud").values();
        assertEquals(0.5259f, values.get("glow").getAsFloat(), 1e-7f);
        assertEquals(0xFFE04D, values.get("color").getAsInt(), "颜色是整数，一位都不能差");
        assertEquals(280.7f, values.get("slider1").getAsFloat(), 1e-4f);
    }
}
