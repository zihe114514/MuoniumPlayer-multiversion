package com.muoniumplayer.core.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class PresetSeamRoundTripTest {
    @Test void HudConfig读写投影互逆() {
        JsonObject before = HudConfig.toJson();
        try { HudConfig.applyJson(before.deepCopy()); assertEquals(before, HudConfig.toJson()); assertEquals(before.keySet(), HudConfig.defaultsJson().keySet()); }
        finally { HudConfig.applyJson(before); }
    }
    @Test void 灵动岛保留样式读写后仍使用原ID() {
        JsonObject before = HudConfig.toJson();
        try {
            for (int id : new int[]{0, 2, 6, 7, 8}) {
                JsonObject patch = new JsonObject();
                patch.addProperty("dynamicIslandStyle", id);
                HudConfig.applyJson(patch);
                assertEquals(id, HudConfig.dynamicIslandStyle);
                assertEquals(id, HudConfig.toJson().get("dynamicIslandStyle").getAsInt());
                HudConfig.applyJson(HudConfig.toJson());
                assertEquals(id, HudConfig.dynamicIslandStyle);
            }
        } finally { HudConfig.applyJson(before); }
    }
    @Test void 已移除的灵动岛样式读档和导出都迁移到紧凑状态() {
        JsonObject before = HudConfig.toJson();
        try {
            for (int id : new int[]{1, 3, 4, 5}) {
                HudConfig.dynamicIslandStyle = id;
                assertEquals(2, HudConfig.toJson().get("dynamicIslandStyle").getAsInt());
                JsonObject patch = new JsonObject();
                patch.addProperty("dynamicIslandStyle", id);
                HudConfig.applyJson(patch);
                assertEquals(2, HudConfig.dynamicIslandStyle);
            }
        } finally { HudConfig.applyJson(before); }
    }
    @Test void 音乐卡互动设置经过实际JSON存储往返(@TempDir Path temp) throws Exception {
        JsonObject before = HudConfig.toJson();
        Gson gson = new Gson();
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                for (int trigger : new int[]{0, 1}) {
                    HudConfig.islandHoverExpand = enabled;
                    HudConfig.islandExpandTrigger = trigger;
                    HudConfig.islandMiniCardScale = 1.25f;
                    HudConfig.islandMusicShowsLyric = false;
                    java.io.File file = temp.resolve("hud.json").toFile();
                    JsonConfigStorage.writeObject(file, gson, HudConfig.toJson());
                    HudConfig.islandHoverExpand = !enabled;
                    HudConfig.islandExpandTrigger = 1 - trigger;
                    HudConfig.islandMiniCardScale = 0.5f;
                    HudConfig.islandMusicShowsLyric = true;

                    HudConfig.applyJson(JsonConfigStorage.readObject(file, gson));

                    assertEquals(enabled, HudConfig.islandHoverExpand);
                    assertEquals(trigger, HudConfig.islandExpandTrigger);
                    assertEquals(1.25f, HudConfig.islandMiniCardScale);
                    assertFalse(HudConfig.islandMusicShowsLyric);
                }
            }
        } finally { HudConfig.applyJson(before); }
    }
    @Test void 旧配置复用互动开关键且缺少触发方式时保留当前值() {
        JsonObject before = HudConfig.toJson();
        try {
            HudConfig.resetDynamicIslandAppearance();
            JsonObject legacy = new JsonObject();
            legacy.addProperty("islandHoverExpand", false);
            HudConfig.applyJson(legacy);
            assertFalse(HudConfig.islandHoverExpand);
            assertEquals(0, HudConfig.islandExpandTrigger, "首次载入旧档沿用自动展开默认");

            HudConfig.islandExpandTrigger = 1;
            legacy.addProperty("islandHoverExpand", true);
            HudConfig.applyJson(legacy);
            assertTrue(HudConfig.islandHoverExpand);
            assertEquals(1, HudConfig.islandExpandTrigger, "局部套用旧状态不能覆盖已选点击展开");
            HudConfig.applyJson(new JsonObject());
            assertEquals(1, HudConfig.islandExpandTrigger);
        } finally { HudConfig.applyJson(before); }
    }
    @Test void 音乐卡触发方式本地读档钳制且错误类型保留当前值() {
        JsonObject before = HudConfig.toJson();
        try {
            JsonObject patch = new JsonObject();
            patch.addProperty("islandExpandTrigger", -1);
            HudConfig.applyJson(patch);
            assertEquals(0, HudConfig.islandExpandTrigger);
            patch.addProperty("islandExpandTrigger", 2);
            HudConfig.applyJson(patch);
            assertEquals(1, HudConfig.islandExpandTrigger);
            patch.add("islandExpandTrigger", new JsonObject());
            patch.addProperty("dynamicIslandScale", 1.1f);
            HudConfig.applyJson(patch);
            assertEquals(1, HudConfig.islandExpandTrigger);
            assertEquals(1.1f, HudConfig.dynamicIslandScale, "坏字段不能中断后续字段加载");
        } finally { HudConfig.applyJson(before); }
    }
    @Test void 灵动岛重置与出厂快照包含音乐卡四项默认且不动其他HUD() {
        JsonObject before = HudConfig.toJson();
        try {
            HudConfig.islandHoverExpand = false;
            HudConfig.islandExpandTrigger = 1;
            HudConfig.islandMiniCardScale = 1.75f;
            HudConfig.islandMusicShowsLyric = false;
            HudConfig.infoScale = 1.25f;

            HudConfig.resetDynamicIslandAppearance();

            assertTrue(HudConfig.islandHoverExpand);
            assertEquals(0, HudConfig.islandExpandTrigger);
            assertEquals(1.0f, HudConfig.islandMiniCardScale);
            assertTrue(HudConfig.islandMusicShowsLyric);
            assertEquals(1.25f, HudConfig.infoScale);
            JsonObject defaults = HudConfig.defaultsJson();
            JsonObject reset = HudConfig.toJson();
            for (String key : new String[]{"islandHoverExpand", "islandExpandTrigger",
                    "islandMiniCardScale", "islandMusicShowsLyric"}) {
                assertEquals(defaults.get(key), reset.get(key), key);
            }
            defaults.addProperty("islandExpandTrigger", 1);
            assertEquals(0, HudConfig.defaultsJson().get("islandExpandTrigger").getAsInt(),
                    "外部修改不能污染出厂快照");
        } finally { HudConfig.applyJson(before); }
    }
    @Test void 辅助通知设置支持JSON读写缺键合并与灵动岛默认重置() {
        JsonObject before = HudConfig.toJson();
        try {
            assertFalse(HudConfig.defaultsJson().get("islandAuxiliaryNotices").getAsBoolean());
            HudConfig.islandAuxiliaryNotices = true;
            assertTrue(HudConfig.toJson().get("islandAuxiliaryNotices").getAsBoolean());

            JsonObject patch = new JsonObject();
            patch.addProperty("islandAuxiliaryNotices", false);
            HudConfig.applyJson(patch);
            assertFalse(HudConfig.islandAuxiliaryNotices);
            assertFalse(HudConfig.toJson().get("islandAuxiliaryNotices").getAsBoolean());

            patch.addProperty("islandAuxiliaryNotices", true);
            HudConfig.applyJson(patch);
            assertTrue(HudConfig.islandAuxiliaryNotices);
            HudConfig.applyJson(new JsonObject());
            assertTrue(HudConfig.islandAuxiliaryNotices, "缺键的旧配置必须保留当前偏好");

            HudConfig.resetDynamicIslandAppearance();
            assertFalse(HudConfig.islandAuxiliaryNotices);
            assertFalse(HudConfig.toJson().get("islandAuxiliaryNotices").getAsBoolean());
            assertFalse(HudConfig.defaultsJson().get("islandAuxiliaryNotices").getAsBoolean());
        } finally { HudConfig.applyJson(before); }
    }
    @Test void 辅助通知配置与设置默认契约在每条版本树接线() throws Exception {
        String config = java.nio.file.Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "settings", "HudConfig.java")));
        assertTrue(config.contains("public static boolean islandAuxiliaryNotices = false;"));
        assertTrue(config.contains("islandAuxiliaryNotices = false;"), "灵动岛重置必须关闭辅助通知");
        assertTrue(config.contains("getBoolean(o, \"islandAuxiliaryNotices\", islandAuxiliaryNotices)"),
                "JSON 缺键必须使用当前值作为 fallback");
        assertTrue(config.contains("addProperty(\"islandAuxiliaryNotices\", islandAuxiliaryNotices)"),
                "JSON 导出必须写出当前布尔值");

        String settings = java.nio.file.Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "HudSettingsModel.java")));
        assertTrue(settings.contains("new Group(\"通知\", HudSettingsIcons.NOTE"));
        assertTrue(settings.contains("toggle(\"辅助通知\""));
        assertTrue(settings.contains("() -> HudConfig.islandAuxiliaryNotices"));
        assertTrue(settings.contains(".withToggleDefault(false)"));

        String bridge = java.nio.file.Files.readString(ProjectPaths.sharedJava().resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "OneConfigHudBridge.java")));
        assertTrue(bridge.contains("addMetadataReflective(property, \"default\", entry.getToggleDefaultState());"));
        assertFalse(bridge.contains("addMetadataReflective(property, \"default\", entry.isOn());"));
    }
    @Test void 音乐胶囊最大长度经过临时JSON文件往返且不混用基础宽度和音乐卡缩放(@TempDir Path temp) throws Exception {
        try (SavedState ignored = new SavedState()) {
            Gson gson = new Gson();
            java.io.File file = temp.resolve("hud.json").toFile();
            for (float width : new float[]{160.0f, 320.0f, 512.5f, 720.0f}) {
                HudConfig.islandMusicCapsuleMaxWidth = width;
                HudConfig.dynamicIslandMaxWidth = 245.5f;
                HudConfig.islandMiniCardScale = 1.25f;
                JsonConfigStorage.writeObject(file, gson, HudConfig.toJson());
                HudConfig.islandMusicCapsuleMaxWidth = 400.0f;
                HudConfig.dynamicIslandMaxWidth = 600.0f;
                HudConfig.islandMiniCardScale = 0.5f;

                JsonObject stored = JsonConfigStorage.readObject(file, gson);
                assertTrue(stored.get("islandMusicCapsuleMaxWidth").getAsJsonPrimitive().isNumber());
                assertEquals(width, stored.get("islandMusicCapsuleMaxWidth").getAsFloat());
                HudConfig.applyJson(stored);

                assertEquals(width, HudConfig.islandMusicCapsuleMaxWidth);
                assertEquals(width, HudConfig.toJson().get("islandMusicCapsuleMaxWidth").getAsFloat());
                assertEquals(245.5f, HudConfig.dynamicIslandMaxWidth);
                assertEquals(1.25f, HudConfig.islandMiniCardScale);
            }
        }
    }
    @Test void 旧配置缺少音乐胶囊最大长度时保留当前值而不是基础宽度或出厂值() {
        try (SavedState ignored = new SavedState()) {
            JsonObject legacy = HudConfig.defaultsJson();
            legacy.remove("islandMusicCapsuleMaxWidth");
            legacy.addProperty("dynamicIslandMaxWidth", 600.0f);
            legacy.addProperty("islandMiniCardScale", 0.75f);
            HudConfig.islandMusicCapsuleMaxWidth = 487.5f;

            HudConfig.applyJson(legacy);

            assertEquals(487.5f, HudConfig.islandMusicCapsuleMaxWidth);
            assertEquals(600.0f, HudConfig.dynamicIslandMaxWidth);
            assertEquals(0.75f, HudConfig.islandMiniCardScale);
            HudConfig.applyJson(new JsonObject());
            assertEquals(487.5f, HudConfig.islandMusicCapsuleMaxWidth, "局部空配置不能隐式重置新设置");
        }
    }
    @Test void 音乐胶囊最大长度出厂快照和灵动岛重置都是320且不动其他HUD() {
        try (SavedState ignored = new SavedState()) {
            HudConfig.islandMusicCapsuleMaxWidth = 540.5f;
            HudConfig.infoScale = 1.25f;
            JsonObject defaults = HudConfig.defaultsJson();
            assertEquals(320.0f, defaults.get("islandMusicCapsuleMaxWidth").getAsFloat());
            assertEquals(540.5f, HudConfig.islandMusicCapsuleMaxWidth, "读取默认快照不能修改实时配置");
            defaults.addProperty("islandMusicCapsuleMaxWidth", 720.0f);
            assertEquals(320.0f, HudConfig.defaultsJson().get("islandMusicCapsuleMaxWidth").getAsFloat(),
                    "调用方不能污染出厂快照");

            HudConfig.resetDynamicIslandAppearance();

            assertEquals(320.0f, HudConfig.islandMusicCapsuleMaxWidth);
            assertEquals(320.0f, HudConfig.toJson().get("islandMusicCapsuleMaxWidth").getAsFloat());
            assertEquals(1.25f, HudConfig.infoScale);
        }
    }
    @Test void 音乐胶囊最大长度JSON有限值钳制到160至720且保留小数() {
        try (SavedState ignored = new SavedState()) {
            float[] inputs = {-Float.MAX_VALUE, 0.0f, 159.5f, 160.0f, 320.25f, 720.0f, 720.5f, Float.MAX_VALUE};
            float[] expected = {160.0f, 160.0f, 160.0f, 160.0f, 320.25f, 720.0f, 720.0f, 720.0f};
            HudConfig.dynamicIslandMaxWidth = 420.0f;
            HudConfig.islandMiniCardScale = 1.4f;
            for (int i = 0; i < inputs.length; i++) {
                JsonObject patch = new JsonObject();
                patch.addProperty("islandMusicCapsuleMaxWidth", inputs[i]);
                HudConfig.applyJson(patch);
                assertEquals(expected[i], HudConfig.islandMusicCapsuleMaxWidth, "输入 " + inputs[i]);
                assertEquals(expected[i], HudConfig.toJson().get("islandMusicCapsuleMaxWidth").getAsFloat());
                assertEquals(420.0f, HudConfig.dynamicIslandMaxWidth);
                assertEquals(1.4f, HudConfig.islandMiniCardScale);
            }
        }
    }
    @Test void 音乐胶囊最大长度JSON非有限值保留当前值且不中断后续字段加载() {
        try (SavedState ignored = new SavedState()) {
            for (com.google.gson.JsonElement invalid : new com.google.gson.JsonElement[]{
                    new com.google.gson.JsonPrimitive(Float.NaN),
                    new com.google.gson.JsonPrimitive(Float.POSITIVE_INFINITY),
                    new com.google.gson.JsonPrimitive(Float.NEGATIVE_INFINITY),
                    new com.google.gson.JsonPrimitive("NaN"), new com.google.gson.JsonPrimitive("Infinity"),
                    new com.google.gson.JsonPrimitive("-Infinity"), new com.google.gson.JsonPrimitive("1e400")}) {
                HudConfig.islandMusicCapsuleMaxWidth = 456.75f;
                HudConfig.dynamicIslandScale = 1.0f;
                JsonObject patch = new JsonObject();
                patch.add("islandMusicCapsuleMaxWidth", invalid);
                patch.addProperty("dynamicIslandScale", 1.1f);

                HudConfig.applyJson(patch);

                assertEquals(456.75f, HudConfig.islandMusicCapsuleMaxWidth, invalid.toString());
                assertEquals(1.1f, HudConfig.dynamicIslandScale, "非有限值不能中断其余字段加载");
            }
        }
    }
    @Test void Folia背景读写投影互逆() {
        JsonObject before = FoliaBackgroundConfig.toJson();
        try { FoliaBackgroundConfig.applyJson(before.deepCopy()); assertEquals(before, FoliaBackgroundConfig.toJson()); assertEquals(before.keySet(), FoliaBackgroundConfig.defaultsJson().keySet()); }
        finally { FoliaBackgroundConfig.applyJson(before); }
    }

    private static final class SavedState implements AutoCloseable {
        private final JsonObject before = HudConfig.toJson();

        @Override public void close() { HudConfig.applyJson(before); }
    }
}
