package com.muoniumplayer.core.viper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调参编辑器的数据层验证（无头）：schema 全量可枚举、类型化读写与钳位、落盘 round-trip
 * 一致、组级重置回默认、另存为。存储指向临时目录，生效钩子替换为空操作——不碰用户 config、
 * 不启动全局引擎。
 */
class ViperPresetEditorTest {

    @TempDir
    Path temp;

    private ViperPresetEditor editorOverDefaults(String targetName) {
        ViperPresetEditor editor = ViperPresetEditor.over(
                ViperPresetParser.parse("{\"schemaVersion\":2.1}", targetName), targetName,
                new ViperPresetStore(temp.toFile()));
        editor.hooksForTest(() -> { }, name -> { });
        return editor;
    }

    private int indexOfGroup(ViperPresetEditor editor, String key) {
        for (int i = 0; i < editor.groupCount(); i++) {
            if (key.equals(editor.groupKey(i))) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfField(ViperPresetEditor editor, int groupIndex, String key) {
        for (int f = 0; f < editor.fieldCount(groupIndex); f++) {
            if (key.equals(editor.fieldKey(groupIndex, f))) {
                return f;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("默认底稿：24 组全量可枚举，全部字段可读出 schema 默认值")
    void defaultsAreComplete() {
        ViperPresetEditor editor = editorOverDefaults("测试底稿");
        assertTrue(editor.groupCount() >= 24, "官方 24 组应全在目录里");

        int gi = indexOfGroup(editor, "convolver");
        assertTrue(gi >= 0);
        int fi = indexOfField(editor, gi, "enable");
        assertTrue(fi >= 0);
        assertEquals(ViperFieldKind.BOOL, editor.fieldKind(gi, fi));
        assertFalse(editor.getBoolean(gi, fi, true), "默认应关闭");

        int mi = indexOfGroup(editor, "masterLimiter");
        int ti = indexOfField(editor, mi, "threshold");
        assertEquals(1.0, editor.getNumber(mi, ti, 0.0), 1e-9, "限幅门限默认 1.0");
        assertTrue(editor.fieldRanged(mi, ti));
        assertEquals(0.3, editor.fieldMin(mi, ti), 1e-9);
        assertEquals(1.0, editor.fieldMax(mi, ti), 1e-9);

        int bi = indexOfGroup(editor, "bass");
        int modeFi = indexOfField(editor, bi, "mode");
        assertNotNull(editor.fieldIntChoices(bi, modeFi), "低音模式应有档位表");
        assertEquals(ViperFieldKind.INT, editor.fieldKind(bi, modeFi));

        int eqi = indexOfGroup(editor, "equalizer");
        int bandsFi = indexOfField(editor, eqi, "bands");
        assertEquals(ViperFieldKind.DOUBLE_ARRAY, editor.fieldKind(eqi, bandsFi), "EQ 频段是数组");
        com.google.gson.JsonElement bands = editor.presetGroup(editor.groupKey(eqi)).get("bands");
        assertTrue(bands.isJsonArray());
        assertEquals(10, bands.getAsJsonArray().size(), "EQ 默认 10 段");
    }

    @Test
    @DisplayName("写入即按 schema 量程钳位；布尔与档位照常")
    void writesAreClamped() {
        ViperPresetEditor editor = editorOverDefaults("测试底稿");
        int bi = indexOfGroup(editor, "bass");
        int gainFi = indexOfField(editor, bi, "gain");

        editor.setNumberQuiet(bi, gainFi, 99.0);
        assertEquals(10.0, editor.getNumber(bi, gainFi, 0.0), 1e-9, "超上限钳到 10");
        editor.setNumberQuiet(bi, gainFi, -5.0);
        assertEquals(0.5, editor.getNumber(bi, gainFi, 0.0), 1e-9, "低下限钳到 0.5");

        int pi = indexOfGroup(editor, "psychoacousticBass");
        int cutoffFi = indexOfField(editor, pi, "cutoff");
        editor.setNumberQuiet(pi, cutoffFi, 300.0);
        assertEquals(150, editor.getInt(pi, cutoffFi, 0), "整数字段钳位并取整");

        editor.setBoolean(bi, indexOfField(editor, bi, "enable"), true);
        assertTrue(editor.getBoolean(bi, indexOfField(editor, bi, "enable"), false));
    }

    @Test
    @DisplayName("落盘 round-trip：写出的文件重新解析后与编辑值逐项一致")
    void roundTripThroughDisk() throws Exception {
        ViperPresetEditor editor = editorOverDefaults("测试预设");
        int ci = indexOfGroup(editor, "convolver");
        editor.setBoolean(ci, indexOfField(editor, ci, "enable"), true);
        editor.setString(ci, indexOfField(editor, ci, "kernelFile"), "至臻-沉浸环绕音效.irs");
        int mi = indexOfGroup(editor, "masterLimiter");
        editor.setNumber(mi, indexOfField(editor, mi, "outputVolume"), 1.4);

        editor.persist();

        File written = new File(temp.toFile(), "测试预设.json");
        assertTrue(written.isFile(), "目标预设文件应已写出");
        ViperPreset reloaded = ViperPresetParser.parse(
                new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8), "测试预设");

        com.google.gson.JsonObject convolver = reloaded.group("convolver");
        assertNotNull(convolver, "卷积器组应存在");
        assertTrue(convolver.get("enable").getAsBoolean(), "解析后卷积器应启用");
        assertEquals("至臻-沉浸环绕音效.irs", convolver.get("kernelFile").getAsString());
        assertEquals(1.4, reloaded.number("masterLimiter", "outputVolume", 0.0), 1e-9);
    }

    @Test
    @DisplayName("组级重置：改乱的字段回到 schema 默认")
    void resetGroupRestoresDefaults() {
        ViperPresetEditor editor = editorOverDefaults("测试底稿");
        int ri = indexOfGroup(editor, "reverb");
        int wetFi = indexOfField(editor, ri, "wet");
        editor.setNumberQuiet(ri, wetFi, 1.0);
        editor.resetGroup(ri);
        assertEquals(0.0, editor.getNumber(ri, wetFi, -1.0), 1e-9, "重置后湿声回默认 0");
    }

    @Test
    @DisplayName("另存为：切换目标、写出新文件（净化后的名字）")
    void saveAsWritesNewTarget() {
        ViperPresetEditor editor = editorOverDefaults("测试底稿");
        String used = editor.saveAs("我的 调参|预设");
        assertEquals("我的 调参_预设", used, "非法字符被净化");
        assertTrue(new File(temp.toFile(), used + ".json").isFile(), "新目标文件应已写出");
        assertEquals(used, editor.targetName(), "编辑目标切换到新预设");
    }
}
