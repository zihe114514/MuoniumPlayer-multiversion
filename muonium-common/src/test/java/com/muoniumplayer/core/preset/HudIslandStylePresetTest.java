package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.preset.domain.HudPresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HudIslandStylePresetTest {
    @Test void 保留样式的分享ID不变() {
        HudPresetDomain domain = new HudPresetDomain();
        for (int id : new int[]{0, 2, 6, 7, 8}) {
            CompatReport report = new CompatReport();
            assertEquals(id, domain.sanitize("dynamicIslandStyle", new JsonPrimitive(id), report).getAsInt());
            assertEquals(0, report.warnings().size());
        }
    }

    @Test void 移除样式在预检阶段迁移并给出提示() {
        HudPresetDomain domain = new HudPresetDomain();
        PresetEngine engine = new PresetEngine(Collections.singletonList(domain));
        int before = HudConfig.dynamicIslandStyle;
        try {
            for (int current : new int[]{0, 2, 6, 7, 8}) {
                HudConfig.dynamicIslandStyle = current;
                for (int removed : new int[]{1, 3, 4, 5}) {
                    CompatReport report = engine.plan(preset(new JsonPrimitive(removed)), EnumSet.of(Facet.ISLAND));
                    assertFalse(report.hasFailures());
                    assertEquals(current == 2 ? 0 : 1, report.changedKeys());
                    assertEquals(0, report.unsupportedValues());
                    assertEquals(1, report.warnings().size());
                    assertEquals(current, HudConfig.dynamicIslandStyle, "预检不能修改当前配置");
                }
            }
        } finally { HudConfig.dynamicIslandStyle = before; }
    }

    @Test void 未知小数和错误类型仍保留当前样式() {
        PresetEngine engine = new PresetEngine(Collections.singletonList(new HudPresetDomain()));
        int before = HudConfig.dynamicIslandStyle;
        try {
            HudConfig.dynamicIslandStyle = 7;
            for (JsonPrimitive value : new JsonPrimitive[]{new JsonPrimitive(-1), new JsonPrimitive(9),
                    new JsonPrimitive(3.5), new JsonPrimitive("3")}) {
                CompatReport report = engine.plan(preset(value), EnumSet.of(Facet.ISLAND));
                assertFalse(report.hasFailures());
                assertEquals(0, report.changedKeys());
                assertEquals(1, report.unsupportedValues());
                assertEquals(0, report.warnings().size());
                assertEquals(7, HudConfig.dynamicIslandStyle);
            }
        } finally { HudConfig.dynamicIslandStyle = before; }
    }

    @Test void 辅助通知是排除的本机偏好且分享预设不覆盖它() {
        JsonObject before = HudConfig.toJson();
        try {
            HudPresetDomain domain = new HudPresetDomain();
            assertTrue(domain.policy().rule("islandAuxiliaryNotices").excluded());
            assertFalse(domain.shareKeys().contains("islandAuxiliaryNotices"));
            assertTrue(domain.policy().rule("islandAuxiliaryNotices").excludedReason().contains("本机偏好"));

            PresetEngine engine = new PresetEngine(Collections.singletonList(memoryHudDomain()));
            HudConfig.islandAuxiliaryNotices = true;
            JsonObject incomingValues = new JsonObject();
            incomingValues.addProperty("islandAuxiliaryNotices", false);
            CompatReport applied = engine.apply(preset(StylePreset.Base.DEFAULTS, incomingValues),
                    EnumSet.of(Facet.ISLAND));
            assertFalse(applied.hasFailures());
            assertTrue(HudConfig.islandAuxiliaryNotices, "外观预设不能重置本机辅助通知偏好");
            assertFalse(engine.capture(EnumSet.of(Facet.ISLAND)).get(HudPresetDomain.ID).values()
                    .has("islandAuxiliaryNotices"));
        } finally { HudConfig.applyJson(before); }
    }

    @Test void 辅助通知设置默认关闭且OneConfig默认不跟随当前值() throws Exception {
        HudSettingsModel.Entry notices = entry("辅助通知");
        assertEquals(HudSettingsModel.Kind.TOGGLE, notices.kind);
        assertFalse(notices.getToggleDefaultState());
        boolean before = HudConfig.islandAuxiliaryNotices;
        try {
            HudConfig.islandAuxiliaryNotices = true;
            assertTrue(notices.isOn());
            assertFalse(notices.getToggleDefaultState(), "OneConfig 的 default metadata 必须使用配置默认值，而不是 live value");
            String bridge = new String(java.nio.file.Files.readAllBytes(
                    com.muoniumplayer.testsupport.ProjectPaths.sourceFile(java.nio.file.Paths.get(
                            "com", "muoniumplayer", "core", "screens", "hud", "OneConfigHudBridge.java"))),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(bridge.contains("addMetadataReflective(property, \"default\", entry.getToggleDefaultState());"));
            assertFalse(bridge.contains("addMetadataReflective(property, \"default\", entry.isOn());"));
        } finally { HudConfig.islandAuxiliaryNotices = before; }
    }

    @Test void 音乐卡设置跟随五款样式及互动开关即时显示() {
        JsonObject before = HudConfig.toJson();
        try {
            HudSettingsModel.Entry interaction = entry("可交互展开");
            HudSettingsModel.Entry trigger = entry("展开方式");
            HudSettingsModel.Entry lyrics = entry("音乐卡 · 显示歌词");
            HudSettingsModel.Entry scale = entry("音乐卡缩放");
            assertEquals(HudSettingsModel.Kind.TOGGLE, interaction.kind);
            assertEquals(HudSettingsModel.Kind.CHOICE, trigger.kind);
            assertEquals(HudSetting.ISLAND_MINI_CARD_SCALE, scale.setting);
            HudConfig.dynamicIslandEnabled = true;
            for (int id : new int[]{0, 2, 6, 7, 8}) {
                HudConfig.dynamicIslandStyle = id;
                HudConfig.islandHoverExpand = true;
                assertTrue(interaction.isVisible(), "样式 " + id);
                assertTrue(trigger.isVisible(), "样式 " + id);
                assertTrue(lyrics.isVisible(), "样式 " + id);
                assertTrue(scale.isVisible(), "样式 " + id);
                HudConfig.islandHoverExpand = false;
                assertFalse(trigger.isVisible());
                assertFalse(interaction.isOn());
                assertTrue(scale.isVisible());
                assertTrue(lyrics.isVisible());
            }
            HudConfig.islandHoverExpand = true;
            HudConfig.dynamicIslandEnabled = false;
            for (HudSettingsModel.Entry control : new HudSettingsModel.Entry[]{interaction, trigger, lyrics, scale}) {
                assertFalse(control.isVisible());
            }
        } finally { HudConfig.applyJson(before); }
    }

    @Test void String选择经过配置与分享码套用后仍显示同一触发方式() throws Exception {
        JsonObject before = HudConfig.toJson();
        try {
            PresetDomain domain = memoryHudDomain();
            PresetEngine engine = new PresetEngine(Collections.singletonList(domain));
            CompactShareCode codec = new CompactShareCode(Collections.singletonList(domain));
            HudSettingsModel.Entry trigger = entry("展开方式");
            assertArrayEquals(new String[]{"打开聊天栏自动展开", "聊天栏点击灵动岛展开"}, trigger.getChoices());
            assertEquals(1, HudConfig.ISLAND_EXPAND_TRIGGER_MAX);
            HudConfig.dynamicIslandEnabled = true;
            HudConfig.islandHoverExpand = true;
            for (int id : new int[]{0, 2, 6, 7, 8}) {
                HudConfig.dynamicIslandStyle = id;
                for (int mode : new int[]{1, 0}) {
                    String selected = trigger.getChoices()[mode];
                    trigger.setChoice(selected);
                    assertEquals(mode, HudConfig.islandExpandTrigger);
                    assertEquals(selected, trigger.getValueText());
                    assertEquals(mode, HudConfig.toJson().get("islandExpandTrigger").getAsInt());
                    JsonObject captured = engine.capture(EnumSet.of(Facet.ISLAND)).get(HudPresetDomain.ID).values();
                    assertEquals(mode, captured.get("islandExpandTrigger").getAsInt());
                    JsonObject interactionValues = new JsonObject();
                    interactionValues.add("islandHoverExpand", captured.get("islandHoverExpand"));
                    interactionValues.add("islandExpandTrigger", captured.get("islandExpandTrigger"));
                    StylePreset decoded = codec.decode(codec.encode(preset(StylePreset.Base.CURRENT, interactionValues)));
                    HudConfig.islandExpandTrigger = 1 - mode;
                    CompatReport plan = engine.plan(decoded, EnumSet.of(Facet.ISLAND));
                    assertFalse(plan.hasFailures());
                    assertEquals(1, plan.changedKeys());
                    assertEquals(1 - mode, HudConfig.islandExpandTrigger, "预检不能写配置");
                    CompatReport applied = engine.apply(decoded, EnumSet.of(Facet.ISLAND));
                    assertFalse(applied.hasFailures());
                    assertEquals(1, applied.changedKeys());
                    assertEquals(mode, HudConfig.islandExpandTrigger);
                    assertEquals(selected, trigger.getValueText());
                }
            }
            trigger.setChoice("聊天栏点击灵动岛展开");
            trigger.setChoice("1");
            trigger.setChoice("不存在的触发方式");
            trigger.setChoice(null);
            assertEquals(1, HudConfig.islandExpandTrigger, "String CHOICE 不接受索引字符串或未知文案");
            HudConfig.applyJson(HudConfig.toJson());
            assertEquals("聊天栏点击灵动岛展开", trigger.getValueText());
            HudConfig.resetDynamicIslandAppearance();
            assertEquals("打开聊天栏自动展开", trigger.getValueText());
        } finally { HudConfig.applyJson(before); }
    }

    @Test void 分享码互动开关沿用169且触发方式只用170() throws Exception {
        PresetDomain domain = memoryHudDomain();
        CompactShareCode codec = new CompactShareCode(Collections.singletonList(domain));
        // 独立按已发布键号读回，不能让生产编码器与同一张错误键表互相证明。
        List<String> numberedKeys = new java.util.ArrayList<>(domain.shareKeys());
        numberedKeys.set(168, "wire169");
        numberedKeys.set(169, "wire170");
        PresetDomain numberedReader = new PresetDomain() {
            public String id() { return domain.id(); }
            public String displayName() { return domain.displayName(); }
            public int fragmentVersion() { return domain.fragmentVersion(); }
            public int shareCode() { return domain.shareCode(); }
            public List<String> shareKeys() { return numberedKeys; }
            public KeyPolicy policy() { return domain.policy(); }
            public JsonObject snapshot() { return domain.snapshot(); }
            public JsonObject defaults() { return domain.defaults(); }
            public void apply(JsonObject state, CompatReport report) { throw new AssertionError("只读编号探针"); }
        };
        for (boolean enabled : new boolean[]{false, true}) {
            for (int mode : new int[]{0, 1}) {
                JsonObject values = new JsonObject();
                values.addProperty("islandHoverExpand", enabled);
                values.addProperty("islandExpandTrigger", mode);
                String code = codec.encode(preset(StylePreset.Base.CURRENT, values));
                JsonObject numbered = new CompactShareCode(Collections.singletonList(numberedReader))
                        .decode(code).domains().get(HudPresetDomain.ID).values();
                assertEquals(enabled, numbered.get("wire169").getAsBoolean());
                assertEquals(mode, numbered.get("wire170").getAsInt());
                assertEquals(2, numbered.size());
            }
        }
    }

    @Test void 出厂基准的分享预设拒绝非法触发方式并保留当前选择() {
        JsonObject before = HudConfig.toJson();
        try {
            PresetEngine engine = new PresetEngine(Collections.singletonList(memoryHudDomain()));
            for (StylePreset.Base base : StylePreset.Base.values()) {
                for (JsonElement invalid : new JsonElement[]{new JsonPrimitive(-1), new JsonPrimitive(2),
                        new JsonPrimitive(0.5), new JsonPrimitive("1"), new JsonPrimitive(true),
                        new JsonObject(), com.google.gson.JsonNull.INSTANCE}) {
                    HudConfig.applyJson(HudConfig.defaultsJson());
                    HudConfig.islandHoverExpand = false;
                    HudConfig.islandExpandTrigger = 1;
                    JsonObject values = new JsonObject();
                    values.addProperty("islandHoverExpand", true);
                    values.add("islandExpandTrigger", invalid);
                    StylePreset incoming = preset(base, values);
                    CompatReport plan = engine.plan(incoming, EnumSet.of(Facet.ISLAND));
                    assertFalse(plan.hasFailures());
                    assertEquals(1, plan.unsupportedValues(), invalid.toString());
                    assertEquals(1, plan.changedKeys());
                    assertFalse(HudConfig.islandHoverExpand);
                    assertEquals(1, HudConfig.islandExpandTrigger);
                    CompatReport applied = engine.apply(incoming, EnumSet.of(Facet.ISLAND));
                    assertFalse(applied.hasFailures());
                    assertEquals(1, applied.unsupportedValues());
                    assertEquals(plan.changedKeys(), applied.changedKeys());
                    assertTrue(HudConfig.islandHoverExpand);
                    assertEquals(1, HudConfig.islandExpandTrigger, "非法分享枚举不能先钳到默认");
                }
            }
        } finally { HudConfig.applyJson(before); }
    }

    @Test void 出厂基准恢复默认触发方式且本地完整状态可还原互动两键() {
        JsonObject before = HudConfig.toJson();
        try {
            PresetEngine engine = new PresetEngine(Collections.singletonList(memoryHudDomain()));
            HudConfig.applyJson(HudConfig.defaultsJson());
            HudConfig.islandHoverExpand = false;
            HudConfig.islandExpandTrigger = 1;
            JsonObject saved = engine.captureState();
            CompatReport reset = engine.apply(preset(StylePreset.Base.DEFAULTS, new JsonObject()),
                    EnumSet.of(Facet.ISLAND));
            assertFalse(reset.hasFailures());
            assertEquals(2, reset.changedKeys());
            assertTrue(HudConfig.islandHoverExpand);
            assertEquals(0, HudConfig.islandExpandTrigger);
            CompatReport restore = engine.restoreState(saved);
            assertFalse(restore.hasFailures());
            assertFalse(HudConfig.islandHoverExpand);
            assertEquals(1, HudConfig.islandExpandTrigger);
            assertEquals("聊天栏点击灵动岛展开", entry("展开方式").getValueText());
        } finally { HudConfig.applyJson(before); }
    }

    @Test void 旧分享预设保留当前触发方式且未选灵动岛分面时不套用互动设置() throws Exception {
        JsonObject before = HudConfig.toJson();
        try {
            PresetDomain domain = memoryHudDomain();
            PresetEngine engine = new PresetEngine(Collections.singletonList(domain));
            CompactShareCode codec = new CompactShareCode(Collections.singletonList(domain));
            HudConfig.islandExpandTrigger = 1;
            HudConfig.islandHoverExpand = true;
            JsonObject legacy = new JsonObject();
            legacy.addProperty("islandHoverExpand", false);
            StylePreset incoming = codec.decode(codec.encode(preset(StylePreset.Base.CURRENT, legacy)));
            CompatReport skipped = engine.apply(incoming, EnumSet.of(Facet.LYRICS));
            assertFalse(skipped.hasFailures());
            assertEquals(0, skipped.changedKeys());
            assertTrue(HudConfig.islandHoverExpand);
            assertEquals(1, HudConfig.islandExpandTrigger);
            CompatReport applied = engine.apply(incoming, EnumSet.of(Facet.ISLAND));
            assertFalse(applied.hasFailures());
            assertEquals(1, applied.changedKeys());
            assertFalse(HudConfig.islandHoverExpand);
            assertEquals(1, HudConfig.islandExpandTrigger);
            assertFalse(engine.capture(EnumSet.of(Facet.LYRICS)).get(HudPresetDomain.ID).values()
                    .has("islandExpandTrigger"));
        } finally { HudConfig.applyJson(before); }
    }

    @Test void 音乐胶囊最大长度保留公开浮点字段与末尾枚举并以px显示() throws Exception {
        try (SavedState ignored = new SavedState()) {
            java.lang.reflect.Field field = HudConfig.class.getField("islandMusicCapsuleMaxWidth");
            assertEquals(float.class, field.getType(), "反射消费方读取的是原生 float 配置字段");
            assertTrue(java.lang.reflect.Modifier.isPublic(field.getModifiers()));
            assertTrue(java.lang.reflect.Modifier.isStatic(field.getModifiers()));
            assertFalse(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
            HudSettingsModel.Entry control = entry("音乐胶囊最大长度");
            HudSetting slider = HudSetting.ISLAND_MUSIC_CAPSULE_MAX_WIDTH;
            assertEquals(HudSettingsModel.Kind.SLIDER, control.kind);
            assertEquals(slider, control.setting);
            assertEquals("音乐胶囊最大长度", slider.getLabel());
            assertEquals(160.0f, slider.getMin());
            assertEquals(720.0f, slider.getMax());
            assertEquals(slider, HudSetting.values()[HudSetting.values().length - 1],
                    "已有按枚举序号存档的设置不能被插入新项后移");
            assertEquals(1, Collections.frequency(HudSettingsModel.declaredSettings(), slider));
            assertTrue(control.description.contains("短歌词"));
            assertTrue(control.description.contains("伸展"));
            assertTrue(control.description.contains("最大长度"));
            assertTrue(control.description.contains("滚动"));
            for (float value : new float[]{160.0f, 320.0f, 456.5f, 720.0f}) {
                field.setFloat(null, value);
                assertEquals(value, slider.getValue());
                assertEquals(String.format("%.1f px", value), HudSettingsModel.formatValue(slider, value));
            }
        }
    }

    @Test void 音乐胶囊最大长度只在启用的音乐胶囊样式显示且不依赖展开方式() {
        try (SavedState ignored = new SavedState()) {
            HudSettingsModel.Entry control = entry("音乐胶囊最大长度");
            HudSettingsModel.Entry baseWidth = entry("灵动岛基础宽度");
            HudSettingsModel.Entry cardScale = entry("音乐卡缩放");
            for (boolean enabled : new boolean[]{false, true}) {
                HudConfig.dynamicIslandEnabled = enabled;
                for (int id : new int[]{0, 2, 6, 7, 8}) {
                    HudConfig.dynamicIslandStyle = id;
                    for (boolean interaction : new boolean[]{false, true}) {
                        HudConfig.islandHoverExpand = interaction;
                        for (int trigger : new int[]{0, 1}) {
                            HudConfig.islandExpandTrigger = trigger;
                            assertEquals(enabled && id == 8, control.isVisible(),
                                    "样式 " + id + "，总开关 " + enabled + "，互动 " + interaction + "，触发方式 " + trigger);
                            assertEquals(enabled, baseWidth.isVisible(), "基础宽度仍是五款样式的独立设置");
                            assertEquals(enabled, cardScale.isVisible(), "展开音乐卡缩放仍适用于五款样式");
                        }
                    }
                }
            }
        }
    }

    @Test void 音乐胶囊最大长度浮点滑块有限值钳制且非有限值保留当前配置() {
        try (SavedState ignored = new SavedState()) {
            HudSetting slider = entry("音乐胶囊最大长度").setting;
            java.util.function.Consumer<Float> setter = slider::setValue;
            float[] inputs = {-Float.MAX_VALUE, 159.5f, 160.0f, 320.25f, 720.0f, 720.5f, Float.MAX_VALUE};
            float[] expected = {160.0f, 160.0f, 160.0f, 320.25f, 720.0f, 720.0f, 720.0f};
            HudConfig.dynamicIslandMaxWidth = 245.5f;
            HudConfig.islandMiniCardScale = 1.25f;
            HudConfig.dynamicIslandScale = 1.1f;
            for (int i = 0; i < inputs.length; i++) {
                setter.accept(Float.valueOf(inputs[i]));
                assertEquals(expected[i], slider.getValue(), "输入 " + inputs[i]);
                assertEquals(expected[i], HudConfig.islandMusicCapsuleMaxWidth);
                assertEquals(expected[i], HudConfig.toJson().get("islandMusicCapsuleMaxWidth").getAsFloat());
                assertEquals(245.5f, HudConfig.dynamicIslandMaxWidth);
                assertEquals(1.25f, HudConfig.islandMiniCardScale);
                assertEquals(1.1f, HudConfig.dynamicIslandScale);
            }
            for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                HudConfig.islandMusicCapsuleMaxWidth = 456.5f;
                setter.accept(Float.valueOf(invalid));
                assertEquals(456.5f, slider.getValue(), "非有限输入 " + invalid);
            }
            HudSetting.DYNAMIC_ISLAND_MAX_WIDTH.setValue(600.0f);
            HudSetting.ISLAND_MINI_CARD_SCALE.setValue(0.75f);
            HudSetting.DYNAMIC_ISLAND_SCALE.setValue(0.8f);
            assertEquals(600.0f, HudConfig.dynamicIslandMaxWidth);
            assertEquals(0.75f, HudConfig.islandMiniCardScale);
            assertEquals(0.8f, HudConfig.dynamicIslandScale);
            assertEquals(456.5f, slider.getValue(), "基础宽度、卡片或岛缩放不能反向覆盖胶囊最大长度");
            HudConfig.resetDynamicIslandAppearance();
            assertEquals(320.0f, slider.getValue());
        }
    }

    @Test void 音乐胶囊最大长度属于灵动岛分面且经过MPP2预检套用完整往返() throws Exception {
        try (SavedState ignored = new SavedState()) {
            PresetDomain domain = memoryHudDomain();
            KeyPolicy.Rule rule = domain.policy().rule("islandMusicCapsuleMaxWidth");
            assertNotNull(rule);
            assertEquals(Facet.ISLAND, rule.facet());
            assertFalse(rule.excluded());
            assertNull(rule.enumMax(), "胶囊最大长度是连续浮点值而不是枚举索引");
            assertEquals(320.0f, domain.defaults().get("islandMusicCapsuleMaxWidth").getAsFloat());
            PresetEngine engine = new PresetEngine(Collections.singletonList(domain));
            CompactShareCode codec = new CompactShareCode(Collections.singletonList(domain));
            HudConfig.dynamicIslandMaxWidth = 245.5f;
            HudConfig.islandMiniCardScale = 1.25f;
            for (float width : new float[]{160.0f, 320.0f, 456.5f, 720.0f}) {
                HudConfig.islandMusicCapsuleMaxWidth = width;
                JsonObject captured = engine.capture(EnumSet.of(Facet.ISLAND)).get(HudPresetDomain.ID).values();
                assertEquals(width, captured.get("islandMusicCapsuleMaxWidth").getAsFloat());
                assertFalse(engine.capture(EnumSet.of(Facet.LYRICS)).get(HudPresetDomain.ID).values()
                        .has("islandMusicCapsuleMaxWidth"));
                JsonObject widthValues = new JsonObject();
                widthValues.add("islandMusicCapsuleMaxWidth", captured.get("islandMusicCapsuleMaxWidth"));
                StylePreset shared = preset(StylePreset.Base.CURRENT, widthValues);
                String code = codec.encode(shared);
                assertTrue(code.startsWith("MPP2:"));
                StylePreset decoded = codec.decode(code);
                assertEquals(width, decoded.domains().get(HudPresetDomain.ID).values()
                        .get("islandMusicCapsuleMaxWidth").getAsFloat());
                HudConfig.islandMusicCapsuleMaxWidth = width == 320.0f ? 400.0f : 320.0f;
                float beforePlan = HudConfig.islandMusicCapsuleMaxWidth;
                CompatReport skipped = engine.apply(decoded, EnumSet.of(Facet.LYRICS));
                assertFalse(skipped.hasFailures());
                assertEquals(0, skipped.changedKeys());
                assertEquals(beforePlan, HudConfig.islandMusicCapsuleMaxWidth);
                CompatReport plan = engine.plan(decoded, EnumSet.of(Facet.ISLAND));
                assertFalse(plan.hasFailures());
                assertEquals(0, plan.unsupportedValues());
                assertEquals(1, plan.changedKeys());
                assertEquals(beforePlan, HudConfig.islandMusicCapsuleMaxWidth, "预检只能读配置");
                CompatReport applied = engine.apply(decoded, EnumSet.of(Facet.ISLAND));
                assertFalse(applied.hasFailures());
                assertEquals(plan.changedKeys(), applied.changedKeys());
                assertEquals(width, HudConfig.islandMusicCapsuleMaxWidth);
                assertEquals(245.5f, HudConfig.dynamicIslandMaxWidth);
                assertEquals(1.25f, HudConfig.islandMiniCardScale);
            }
        }
    }

    @Test void 音乐胶囊最大长度分享码只追加171且独立编号探针读回浮点值() throws Exception {
        PresetDomain domain = memoryHudDomain();
        assertEquals(170, domain.shareKeys().indexOf("islandMusicCapsuleMaxWidth"), "MPP2 键号从 1 起算");
        assertEquals(1, Collections.frequency(domain.shareKeys(), "islandMusicCapsuleMaxWidth"));
        assertEquals("islandHoverExpand", domain.shareKeys().get(168));
        assertEquals("islandExpandTrigger", domain.shareKeys().get(169));
        List<String> numberedKeys = new java.util.ArrayList<>(domain.shareKeys());
        numberedKeys.set(170, "wire171");
        PresetDomain numberedReader = new PresetDomain() {
            public String id() { return domain.id(); }
            public String displayName() { return domain.displayName(); }
            public int fragmentVersion() { return domain.fragmentVersion(); }
            public int shareCode() { return domain.shareCode(); }
            public List<String> shareKeys() { return numberedKeys; }
            public KeyPolicy policy() { return domain.policy(); }
            public JsonObject snapshot() { return domain.snapshot(); }
            public JsonObject defaults() { return domain.defaults(); }
            public void apply(JsonObject state, CompatReport report) { throw new AssertionError("只读编号探针"); }
        };
        CompactShareCode codec = new CompactShareCode(Collections.singletonList(domain));
        for (float width : new float[]{160.0f, 320.0f, 456.5f, 720.0f}) {
            JsonObject values = new JsonObject();
            values.addProperty("islandMusicCapsuleMaxWidth", width);
            String code = codec.encode(preset(StylePreset.Base.CURRENT, values));
            assertTrue(code.startsWith("MPP2:"));
            JsonObject numbered = new CompactShareCode(Collections.singletonList(numberedReader))
                    .decode(code).domains().get(HudPresetDomain.ID).values();
            assertEquals(1, numbered.size());
            assertEquals(width, numbered.get("wire171").getAsFloat(), "不能由同一张错误键表互相证明编号正确");
        }
    }

    @Test void 旧分享码缺少音乐胶囊最大长度时保留当前值且出厂基准可重置() throws Exception {
        try (SavedState ignored = new SavedState()) {
            PresetDomain domain = memoryHudDomain();
            PresetEngine engine = new PresetEngine(Collections.singletonList(domain));
            CompactShareCode codec = new CompactShareCode(Collections.singletonList(domain));
            HudConfig.applyJson(HudConfig.defaultsJson());
            HudConfig.islandMusicCapsuleMaxWidth = 456.5f;
            JsonObject legacy = new JsonObject();
            legacy.addProperty("dynamicIslandMaxWidth", 500.0f);
            StylePreset incoming = codec.decode(codec.encode(preset(StylePreset.Base.CURRENT, legacy)));
            CompatReport applied = engine.apply(incoming, EnumSet.of(Facet.ISLAND));
            assertFalse(applied.hasFailures());
            assertEquals(1, applied.changedKeys());
            assertEquals(500.0f, HudConfig.dynamicIslandMaxWidth);
            assertEquals(456.5f, HudConfig.islandMusicCapsuleMaxWidth, "旧分享码不能把独立胶囊宽度覆盖为基础宽度");

            JsonObject saved = engine.captureState();
            assertEquals(456.5f, saved.getAsJsonObject(HudPresetDomain.ID).get("islandMusicCapsuleMaxWidth").getAsFloat());
            CompatReport reset = engine.apply(preset(StylePreset.Base.DEFAULTS, new JsonObject()), EnumSet.of(Facet.ISLAND));
            assertFalse(reset.hasFailures());
            assertEquals(320.0f, HudConfig.islandMusicCapsuleMaxWidth);
            CompatReport restored = engine.restoreState(saved);
            assertFalse(restored.hasFailures());
            assertEquals(456.5f, HudConfig.islandMusicCapsuleMaxWidth);
            assertEquals(500.0f, HudConfig.dynamicIslandMaxWidth);
        }
    }

    @Test void 音乐卡缩放滑块写回会钳制且进入同一配置快照() {
        float before = HudConfig.islandMiniCardScale;
        try {
            HudSetting slider = entry("音乐卡缩放").setting;
            slider.setValue(0.1f);
            assertEquals(0.5f, HudConfig.islandMiniCardScale);
            slider.setValue(3.0f);
            assertEquals(2.0f, slider.getValue());
            slider.setValue(1.25f);
            assertEquals(1.25f, HudConfig.toJson().get("islandMiniCardScale").getAsFloat());
        } finally { HudConfig.islandMiniCardScale = before; }
    }

    private static final class SavedState implements AutoCloseable {
        private final JsonObject before = HudConfig.toJson();

        @Override public void close() { HudConfig.applyJson(before); }
    }

    /** 保留生产域的策略、分享编号和真实配置接缝，仅隔离运行时同步与 HUD 文件写入。 */
    private static PresetDomain memoryHudDomain() {
        HudPresetDomain domain = new HudPresetDomain();
        return new PresetDomain() {
            public String id() { return domain.id(); }
            public String displayName() { return domain.displayName(); }
            public int fragmentVersion() { return domain.fragmentVersion(); }
            public int shareCode() { return domain.shareCode(); }
            public List<String> shareKeys() { return domain.shareKeys(); }
            public KeyPolicy policy() { return domain.policy(); }
            public JsonObject snapshot() { return domain.snapshot(); }
            public JsonObject defaults() { return domain.defaults(); }
            public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
                return domain.sanitize(key, value, report);
            }
            public void apply(JsonObject state, CompatReport report) { HudConfig.applyJson(state); }
        };
    }

    private static HudSettingsModel.Entry entry(String label) {
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            if (!"灵动岛".equals(category.title)) continue;
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (label.equals(entry.getLabel())) return entry;
                }
            }
        }
        throw new AssertionError("缺少灵动岛设置：" + label);
    }

    private static StylePreset preset(StylePreset.Base base, JsonObject values) {
        return new StylePreset(PresetEngineTest.meta(), base, EnumSet.of(Facet.ISLAND), null,
                Collections.singletonMap(HudPresetDomain.ID, new DomainFragment(1, values)),
                StylePreset.Source.USER, null);
    }

    private static StylePreset preset(JsonPrimitive style) {
        JsonObject values = new JsonObject();
        values.add("dynamicIslandStyle", style);
        return new StylePreset(PresetEngineTest.meta(), StylePreset.Base.CURRENT, EnumSet.of(Facet.ISLAND),
                null, Collections.singletonMap(HudPresetDomain.ID, new DomainFragment(1, values)),
                StylePreset.Source.USER, null);
    }
}
