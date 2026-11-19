package com.muoniumplayer.core.viper;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void metadataLookupReturnsMinusOneForUnknownKeys() {
        ViperPresetEditor editor = editorOverDefaults("底稿");
        int group = editor.groupIndex("equalizer");
        assertTrue(group >= 0);
        assertEquals("bands", editor.fieldKey(group, editor.fieldIndex(group, "bands")));
        assertEquals(-1, editor.groupIndex("futureGroup"));
        assertEquals(-1, editor.groupIndex(null));
        assertEquals(-1, editor.fieldIndex(group, "futureField"));
        assertEquals(-1, editor.fieldIndex(-1, "enable"));
        assertEquals(-1, editor.fieldIndex(editor.groupCount(), "enable"));
    }

    @Test
    void quietWritesEnforceKindsAndRejectNonFiniteValuesWithoutMutation() {
        ViperPresetEditor editor = editorOverDefaults("底稿");
        int eq = editor.groupIndex("equalizer");
        int bands = editor.fieldIndex(eq, "bands");
        int enable = editor.fieldIndex(eq, "enable");
        int count = editor.fieldIndex(eq, "bandCount");
        int nullable = editor.fieldIndex(eq, "presetId");
        int convolver = editor.groupIndex("convolver");
        int kernel = editor.fieldIndex(convolver, "kernelFile");
        JsonObject originalEq = editor.presetGroup("equalizer").deepCopy();

        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> editor.setNumberQuiet(eq, count, invalid));
            assertThrows(IllegalArgumentException.class, () -> editor.setArrayQuiet(eq, bands, new double[] {1, invalid}));
            assertThrows(IllegalArgumentException.class, () -> editor.setArrayElementQuiet(eq, bands, 0, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> editor.setBooleanQuiet(eq, bands, true));
        assertThrows(IllegalArgumentException.class, () -> editor.setNumberQuiet(eq, enable, 1));
        assertThrows(IllegalArgumentException.class, () -> editor.setStringQuiet(eq, count, "15"));
        assertThrows(IllegalArgumentException.class, () -> editor.setArrayQuiet(eq, enable, new double[] {1}));
        assertThrows(IllegalArgumentException.class, () -> editor.getArray(eq, enable));
        assertThrows(IllegalArgumentException.class, () -> editor.setNullQuiet(eq, enable));
        assertThrows(IllegalArgumentException.class, () -> editor.setStringQuiet(convolver, kernel, null));
        assertThrows(IllegalArgumentException.class, () -> editor.setNumberQuiet(eq, nullable, -1));
        assertThrows(IllegalArgumentException.class, () -> editor.setNumberQuiet(eq, count, Double.MAX_VALUE));
        assertEquals(originalEq, editor.presetGroup("equalizer"));

        editor.setBooleanQuiet(eq, enable, true);
        editor.setStringQuiet(convolver, kernel, "自选.irs");
        editor.setNumberQuiet(eq, nullable, 17);
        assertEquals(17, editor.getInt(eq, nullable, -1));
        editor.setNullQuiet(eq, nullable);
        assertTrue(editor.presetGroup("equalizer").get("presetId").isJsonNull());
        assertFalse(Files.exists(temp.resolve("底稿.json")), "quiet 不能落盘");
    }

    @Test
    void numericArraysClampEveryElementRoundIntegersAndDefensivelyCopy() {
        ViperPresetEditor editor = editorOverDefaults("底稿");
        int eq = editor.groupIndex("equalizer");
        int bands = editor.fieldIndex(eq, "bands");
        double[] source = {-99, 1.25, 99};
        editor.setArrayQuiet(eq, bands, source);
        source[1] = 6;
        assertArrayEquals(new double[] {-12, 1.25, 12}, editor.getArray(eq, bands));
        double[] view = editor.getArray(eq, bands);
        view[0] = 7;
        assertEquals(-12, editor.getArray(eq, bands)[0]);
        editor.setArrayElementQuiet(eq, bands, 1, -99);
        assertArrayEquals(new double[] {-12, -12, 12}, editor.getArray(eq, bands));
        assertThrows(IndexOutOfBoundsException.class, () -> editor.setArrayElementQuiet(eq, bands, 3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> editor.setArrayElementQuiet(eq, bands, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> editor.setArrayQuiet(eq, bands, null));

        int mbc = editor.groupIndex("multibandCompressor");
        int crossovers = editor.fieldIndex(mbc, "crossovers");
        editor.setArrayQuiet(mbc, crossovers, new double[] {-4, 500.6, 99999});
        assertArrayEquals(new double[] {30, 501, 16000}, editor.getArray(mbc, crossovers));
        int dynamic = editor.groupIndex("dynamicEq");
        int q = editor.fieldIndex(dynamic, "qs");
        editor.setArrayQuiet(dynamic, q, new double[] {0, 1.25, 99});
        assertArrayEquals(new double[] {0.5, 1.25, 8}, editor.getArray(dynamic, q));
    }

    @Test
    void booleanArraysHaveSeparateTypedDefensiveApi() {
        ViperPresetEditor editor = editorOverDefaults("底稿");
        int group = editor.groupIndex("multibandCompressor");
        int field = editor.fieldIndex(group, "bandEnables");
        boolean[] source = {false, true, false};
        editor.setBooleanArrayQuiet(group, field, source);
        source[0] = true;
        boolean[] view = editor.getBooleanArray(group, field);
        assertArrayEquals(new boolean[] {false, true, false}, view);
        view[0] = true;
        editor.setBooleanArrayElementQuiet(group, field, 1, false);
        assertArrayEquals(new boolean[] {false, false, false}, editor.getBooleanArray(group, field));
        assertThrows(IllegalArgumentException.class, () -> editor.getArray(group, field));
        assertThrows(IllegalArgumentException.class, () -> editor.setArrayQuiet(group, field, new double[] {1}));
        assertThrows(IllegalArgumentException.class, () -> editor.setBooleanArrayQuiet(group, field, null));
        int numeric = editor.fieldIndex(group, "crossovers");
        assertThrows(IllegalArgumentException.class, () -> editor.getBooleanArray(group, numeric));
        assertThrows(IllegalArgumentException.class, () -> editor.setBooleanArrayQuiet(group, numeric, new boolean[] {true}));
        assertThrows(IndexOutOfBoundsException.class, () -> editor.setBooleanArrayElementQuiet(group, field, 3, false));
        assertFalse(Files.exists(temp.resolve("底稿.json")));
    }

    @Test
    void groupResetRestoresEveryKnownKindAndPreservesUnknowns() {
        ViperPresetEditor editor = editorOverDefaults("底稿");
        for (int group = 0; group < editor.groupCount(); group++) {
            JsonObject body = editor.presetGroup(editor.groupKey(group));
            JsonObject unknown = new JsonObject();
            unknown.add("nil", JsonNull.INSTANCE);
            body.add("futureField", unknown);
            for (ViperPresetSchema.Field field : ViperPresetSchema.groups().get(group).fields) {
                body.add(field.key, JsonNull.INSTANCE);
            }
            editor.resetGroupQuiet(group);
            JsonObject defaults = ViperPresetParser.parse("{\"schemaVersion\":2.1}", "默认")
                    .group(editor.groupKey(group));
            for (ViperPresetSchema.Field field : ViperPresetSchema.groups().get(group).fields) {
                assertEquals(defaults.get(field.key), body.get(field.key), editor.groupKey(group) + "." + field.key);
            }
            assertEquals(unknown, body.get("futureField"));
        }
        int eq = editor.groupIndex("equalizer");
        int bands = editor.fieldIndex(eq, "bands");
        editor.setArrayQuiet(eq, bands, new double[] {4});
        editor.resetFieldQuiet(eq, bands);
        assertArrayEquals(new double[10], editor.getArray(eq, bands));
        editor.setArrayElementQuiet(eq, bands, 0, 3);
        editor.resetFieldQuiet(eq, bands);
        assertArrayEquals(new double[10], editor.getArray(eq, bands), "不能污染 schema 默认数组");
        assertFalse(Files.exists(temp.resolve("底稿.json")));
    }

    @Test
    void copyOwnsNormalizedJsonAndKeepsHooksAndTargetWithoutReadingDisk() throws Exception {
        ViperPreset source = ViperPresetParser.parse("{\"schemaVersion\":2.1,\"futureRoot\":{\"nil\":null}}", "底稿");
        source.group("bass").addProperty("gain", 99);
        AtomicInteger reload = new AtomicInteger();
        ViperPresetEditor editor = ViperPresetEditor.over(source, "底稿", new ViperPresetStore(temp.toFile()));
        editor.hooksForTest(reload::incrementAndGet, name -> fail("既有目标不应选中"));
        source.group("bass").addProperty("gain", 1);
        assertEquals(10, editor.getNumber(editor.groupIndex("bass"), editor.fieldIndex(editor.groupIndex("bass"), "gain"), 0));

        int eq = editor.groupIndex("equalizer");
        int bands = editor.fieldIndex(eq, "bands");
        editor.setArrayQuiet(eq, bands, new double[] {2, 3});
        ViperPresetEditor copy = editor.copy();
        copy.setArrayElementQuiet(eq, bands, 0, 5);
        editor.setArrayElementQuiet(eq, bands, 1, 6);
        assertArrayEquals(new double[] {2, 6}, editor.getArray(eq, bands));
        assertArrayEquals(new double[] {5, 3}, copy.getArray(eq, bands));
        assertEquals(editor.targetName(), copy.targetName());
        assertFalse(Files.exists(temp.resolve("底稿.json")));
        copy.persistChecked();
        assertEquals(1, reload.get(), "copy 保留替换过的 hook");
        JsonObject written = ViperPresetParser.parse(Files.readString(temp.resolve("底稿.json")), "底稿").json();
        assertTrue(written.getAsJsonObject("futureRoot").get("nil").isJsonNull());
        assertArrayEquals(new double[] {2, 6}, editor.getArray(eq, bands), "后台保存不改界面副本");

        Path notDirectory = temp.resolve("不是目录");
        Files.writeString(notDirectory, "sentinel");
        ViperPresetEditor memoryOnly = ViperPresetEditor.over(source, "内存", new ViperPresetStore(notDirectory.toFile()));
        assertDoesNotThrow(memoryOnly::copy, "纯内存构造/copy 不读取 store");
        assertEquals("sentinel", Files.readString(notDirectory));
    }

    @Test
    void checkedFailureDoesNotSelectReloadChangeTargetOrConfirmScratch() throws Exception {
        Path occupied = temp.resolve("不是目录");
        Files.writeString(occupied, "旧内容");
        ViperPresetEditor editor = ViperPresetEditor.over(
                ViperPresetParser.parse("{\"schemaVersion\":2.1}", ViperPresetEditor.SCRATCH_NAME),
                ViperPresetEditor.SCRATCH_NAME, new ViperPresetStore(occupied.toFile()));
        AtomicInteger hooks = new AtomicInteger();
        editor.hooksForTest(hooks::incrementAndGet, name -> hooks.incrementAndGet());
        assertThrows(IOException.class, editor::persistChecked);
        assertThrows(IOException.class, () -> editor.saveAsChecked("另存"));
        assertTrue(editor.isScratch());
        assertEquals(ViperPresetEditor.SCRATCH_NAME, editor.targetName());
        assertDoesNotThrow(editor::persist);
        assertEquals(ViperPresetEditor.SCRATCH_NAME, editor.saveAs("兼容另存"));
        assertEquals(0, hooks.get());
        assertEquals("旧内容", Files.readString(occupied));
    }

    @Test
    void checkedSaveAsUpdatesEmbeddedNameNormalizedWorkAndUnknownNulls() throws Exception {
        ViperPreset preset = ViperPresetParser.parse(
                "{\"schemaVersion\":2.1,\"name\":\"原内嵌名\",\"future\":null,"
                        + "\"equalizer\":{\"future\":{\"nil\":null}}}", "原目标");
        ViperPresetEditor editor = ViperPresetEditor.over(preset, "原目标", new ViperPresetStore(temp.toFile()));
        AtomicReference<String> selected = new AtomicReference<>();
        editor.hooksForTest(() -> fail("另存只选中"), selected::set);
        editor.presetGroup("bass").addProperty("gain", 99);
        String used = editor.saveAsChecked("我的:另存|预设");
        assertEquals("我的_另存_预设", used);
        ViperPreset written = ViperPresetParser.parse(Files.readString(temp.resolve(used + ".json")), "fallback");
        assertEquals(used, written.name());
        assertEquals(used, written.json().get("name").getAsString());
        assertTrue(written.json().get("future").isJsonNull());
        assertTrue(written.group("equalizer").getAsJsonObject("future").get("nil").isJsonNull());
        assertEquals(10, editor.getNumber(editor.groupIndex("bass"), editor.fieldIndex(editor.groupIndex("bass"), "gain"), 0));
        assertEquals(used, selected.get());
        assertEquals(used, editor.targetName());
        assertFalse(editor.isScratch());
        assertThrows(IllegalArgumentException.class, () -> editor.saveAsChecked(" "));
        assertEquals(used, editor.targetName());
    }

    @Test
    void successfulPersistReplacesWorkWithNormalizedCopy() throws Exception {
        ViperPresetEditor editor = editorOverDefaults("底稿");
        JsonObject previous = editor.presetGroup("bass");
        previous.addProperty("gain", 99);
        previous.add("futureNull", JsonNull.INSTANCE);
        editor.persistChecked();
        assertNotSame(previous, editor.presetGroup("bass"));
        assertEquals(10, editor.getNumber(editor.groupIndex("bass"), editor.fieldIndex(editor.groupIndex("bass"), "gain"), 0));
        assertTrue(editor.presetGroup("bass").get("futureNull").isJsonNull());
    }

    @Test
    void scratchNamesAvoidExistingJsonXmlAndOccupiedDirectories() throws Exception {
        Files.writeString(temp.resolve("自定义.json"), "用户旧预设");
        Files.writeString(temp.resolve("自定义（2）.xml"), "用户XML");
        Files.createDirectory(temp.resolve("自定义（3）.JSON"));
        ViperPresetEditor editor = ViperPresetEditor.newScratch(new ViperPresetStore(temp.toFile()));
        editor.hooksForTest(() -> { }, name -> { });
        assertEquals("自定义（4）", editor.targetName());
        assertTrue(editor.isScratch());
        assertEquals(editor.targetName(), editor.copy().targetName());
        assertTrue(editor.copy().isScratch());
        editor.persistChecked();
        assertFalse(editor.isScratch());
        assertFalse(editor.copy().isScratch());
        assertEquals("用户旧预设", Files.readString(temp.resolve("自定义.json")));
        assertEquals("用户XML", Files.readString(temp.resolve("自定义（2）.xml")));
    }

    @Test
    void scratchCopiesCanCommitBeforeAcknowledgementAndNeverShareQuietChanges() throws Exception {
        ViperPresetEditor editor = ViperPresetEditor.newScratch(new ViperPresetStore(temp.toFile()));
        AtomicInteger selections = new AtomicInteger();
        AtomicInteger reloads = new AtomicInteger();
        editor.hooksForTest(reloads::incrementAndGet, name -> selections.incrementAndGet());
        int eq = editor.groupIndex("equalizer");
        int bands = editor.fieldIndex(eq, "bands");
        editor.setArrayElementQuiet(eq, bands, 0, 1);
        ViperPresetEditor first = editor.copy();
        editor.setArrayElementQuiet(eq, bands, 0, 2);
        ViperPresetEditor second = editor.copy();
        editor.setArrayElementQuiet(eq, bands, 0, 3);
        first.persistChecked();
        second.persistChecked();
        assertEquals(1, selections.get(), "同一新建会话只首次选中");
        assertEquals(1, reloads.get());
        assertTrue(editor.isScratch(), "UI 尚未接收确认");
        assertEquals(3, editor.getArray(eq, bands)[0]);
        assertEquals(2, new ViperPresetStore(temp.toFile()).load(editor.targetName()).group("equalizer")
                .getAsJsonArray("bands").get(0).getAsDouble());
        editor.acknowledgeCommit(second);
        assertFalse(editor.isScratch());
        assertEquals(3, editor.getArray(eq, bands)[0], "ack 不能覆盖更晚的quiet值");

        ViperPresetEditor different = ViperPresetEditor.newScratch(new ViperPresetStore(temp.toFile()));
        different.hooksForTest(() -> { }, name -> { });
        different.acknowledgeCommit(first);
        assertTrue(different.isScratch(), "目标不匹配时忽略确认");
        ViperPresetEditor uncommitted = different.copy();
        different.acknowledgeCommit(uncommitted);
        assertTrue(different.isScratch(), "失败/未保存副本不能确认");
    }

    @Test
    void saveAsNewKeepsOriginalFileAlignsNameAndRetainsOwnershipForLaterCopies() throws Exception {
        ViperPresetStore store = new ViperPresetStore(temp.toFile());
        store.saveChecked(ViperPresetParser.parse("{\"schemaVersion\":2.1,\"equalizer\":{\"bands\":[1]}}", "原稿"));
        byte[] original = Files.readAllBytes(temp.resolve("原稿.json"));
        ViperPresetEditor editor = ViperPresetEditor.over(store.load("原稿"), "原稿", store);
        AtomicReference<String> selected = new AtomicReference<>();
        AtomicInteger reloads = new AtomicInteger();
        editor.hooksForTest(reloads::incrementAndGet, selected::set);
        int eq = editor.groupIndex("equalizer");
        int bands = editor.fieldIndex(eq, "bands");
        editor.setArrayElementQuiet(eq, bands, 0, 3);
        editor.presetGroup("equalizer").add("futureNull", JsonNull.INSTANCE);
        assertEquals("新名_预设", editor.saveAsNewChecked("新名|预设"));
        assertEquals("新名_预设", selected.get());
        assertEquals("新名_预设", editor.targetName());
        assertFalse(editor.isScratch());
        assertEquals(0, reloads.get());
        ViperPreset newPreset = store.load("新名_预设");
        assertEquals("新名_预设", newPreset.name());
        assertEquals("新名_预设", newPreset.json().get("name").getAsString());
        assertTrue(newPreset.group("equalizer").get("futureNull").isJsonNull());
        assertEquals(3, newPreset.group("equalizer").getAsJsonArray("bands").get(0).getAsDouble());
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("原稿.json")));

        ViperPresetEditor copy = editor.copy();
        copy.setArrayElementQuiet(eq, bands, 0, 4);
        copy.persistChecked();
        assertEquals(1, reloads.get());
        assertEquals(4, store.load("新名_预设").group("equalizer").getAsJsonArray("bands").get(0).getAsDouble());
        assertEquals(3, editor.getArray(eq, bands)[0]);
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("原稿.json")));
        byte[] saved = Files.readAllBytes(temp.resolve("新名_预设.json"));
        assertThrows(IOException.class, () -> editor.saveAsNewChecked("新名|预设"));
        assertArrayEquals(saved, Files.readAllBytes(temp.resolve("新名_预设.json")), "重复另存不能覆盖同名文件");
        assertEquals(1, reloads.get());
    }

    @Test
    void saveAsNewRejectsJsonXmlDirectoriesAndCaseVariantsWithoutTargetOrHookChanges() throws Exception {
        ViperPresetEditor editor = ViperPresetEditor.newScratch(new ViperPresetStore(temp.toFile()));
        AtomicInteger hooks = new AtomicInteger();
        editor.hooksForTest(hooks::incrementAndGet, name -> hooks.incrementAndGet());
        String originalTarget = editor.targetName();
        JsonObject originalEq = editor.presetGroup("equalizer");
        String[] occupied = {"JsonName.json", "XmlName.xml", "UpperJson.JSON", "UpperXml.XML", "DirJson.JSON", "DirXml.xml", "净化_名.json"};
        String[] requested = {"jsonname", "xmlname", "upperjson", "upperxml", "dirjson", "dirxml", "净化|名"};
        for (int index = 0; index < occupied.length; index++) {
            Path target = temp.resolve(occupied[index]);
            if (index == 4 || index == 5) {
                Files.createDirectory(target);
                Files.writeString(target.resolve("sentinel"), "目录原内容");
            } else {
                Files.writeString(target, "同名原内容");
            }
            String requestedName = requested[index];
            assertThrows(IOException.class, () -> editor.saveAsNewChecked(requestedName));
            assertEquals(originalTarget, editor.targetName());
            assertSame(originalEq, editor.presetGroup("equalizer"));
            assertTrue(editor.isScratch());
            assertEquals(0, hooks.get());
            if (Files.isDirectory(target)) {
                assertEquals("目录原内容", Files.readString(target.resolve("sentinel")));
            } else {
                assertEquals("同名原内容", Files.readString(target));
            }
        }
        try (java.util.stream.Stream<Path> paths = Files.list(temp)) {
            assertEquals(occupied.length, paths.count(), "拒绝占名不能留下临时文件");
        }
    }

    @Test
    void saveAsNewWriteFailureKeepsOriginalWorkAndSkipsHooks() throws Exception {
        Path occupied = temp.resolve("不是目录");
        Files.writeString(occupied, "旧内容");
        ViperPresetEditor editor = ViperPresetEditor.over(
                ViperPresetParser.parse("{\"schemaVersion\":2.1}", ViperPresetEditor.SCRATCH_NAME),
                ViperPresetEditor.SCRATCH_NAME, new ViperPresetStore(occupied.toFile()));
        AtomicInteger hooks = new AtomicInteger();
        editor.hooksForTest(hooks::incrementAndGet, name -> hooks.incrementAndGet());
        JsonObject original = editor.presetGroup("equalizer");
        assertThrows(IOException.class, () -> editor.saveAsNewChecked("新目标"));
        assertEquals(ViperPresetEditor.SCRATCH_NAME, editor.targetName());
        assertTrue(editor.isScratch());
        assertSame(original, editor.presetGroup("equalizer"));
        assertEquals(0, hooks.get());
        assertEquals("旧内容", Files.readString(occupied));
        assertThrows(IllegalArgumentException.class, () -> editor.saveAsNewChecked(null));
        assertThrows(IllegalArgumentException.class, () -> editor.saveAsNewChecked(" "));
        assertEquals(0, hooks.get());
    }

    @Test
    void saveAsNewSelectFailureReportsSavedFileButKeepsOriginalTargetAndDraft() throws Exception {
        ViperPresetEditor editor = ViperPresetEditor.newScratch(new ViperPresetStore(temp.toFile()));
        AtomicInteger selects = new AtomicInteger();
        IllegalStateException hookFailure = new IllegalStateException("设置写入失败");
        editor.hooksForTest(() -> fail("另存不能重载"), name -> {
            selects.incrementAndGet();
            throw hookFailure;
        });
        String originalTarget = editor.targetName();
        JsonObject original = editor.presetGroup("bass");
        original.addProperty("gain", 99);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> editor.saveAsNewChecked("已保存未选用"));
        assertSame(hookFailure, failure.getCause());
        assertTrue(failure.getMessage().contains("已保存未选用"));
        assertTrue(failure.getMessage().contains("已保存"));
        assertTrue(failure.getMessage().contains("选用失败"));
        assertEquals(1, selects.get());
        assertEquals(originalTarget, editor.targetName());
        assertTrue(editor.isScratch());
        assertSame(original, editor.presetGroup("bass"));
        assertEquals(99, original.get("gain").getAsDouble(), "选用失败不丢失原工作稿");
        ViperPreset saved = new ViperPresetStore(temp.toFile()).load("已保存未选用");
        assertNotNull(saved);
        assertEquals(10, saved.number("bass", "gain", 0), "新文件归一化后仍留存");
        assertEquals("已保存未选用", saved.name());
        assertFalse(Files.exists(temp.resolve(originalTarget + ".json")));
        editor.hooksForTest(() -> { }, name -> { });
        assertDoesNotThrow(editor::persistChecked, "失败后原新建会话仍可提交原目标");
        assertEquals(originalTarget, editor.targetName());
    }

    @Test
    void checkedRuntimeHookSetterIsMemoryOnlyAndCopiesInheritHooks() throws Exception {
        Path occupied = temp.resolve("不能读写的store");
        Files.writeString(occupied, "sentinel");
        ViperPresetEditor editor = ViperPresetEditor.over(ViperPresetParser.parse("{\"schemaVersion\":2.1}", "原稿"),
                "原稿", new ViperPresetStore(occupied.toFile()));
        AtomicInteger hooks = new AtomicInteger();
        editor.hooksForTest(hooks::incrementAndGet, name -> hooks.incrementAndGet());
        java.lang.reflect.Field reloadField = ViperPresetEditor.class.getDeclaredField("reloadHook");
        java.lang.reflect.Field selectField = ViperPresetEditor.class.getDeclaredField("selectHook");
        reloadField.setAccessible(true);
        selectField.setAccessible(true);
        Object oldReload = reloadField.get(editor);
        Object oldSelect = selectField.get(editor);
        JsonObject before = editor.presetGroup("equalizer");
        assertDoesNotThrow(editor::useCheckedRuntimeHooks);
        assertNotSame(oldReload, reloadField.get(editor));
        assertNotSame(oldSelect, selectField.get(editor));
        assertEquals(0, hooks.get(), "setter不能立即运行任何钩子");
        assertSame(before, editor.presetGroup("equalizer"));
        assertEquals("原稿", editor.targetName());
        assertEquals("sentinel", Files.readString(occupied));
        ViperPresetEditor copy = editor.copy();
        assertSame(reloadField.get(editor), reloadField.get(copy));
        assertSame(selectField.get(editor), selectField.get(copy));
        assertEquals("sentinel", Files.readString(occupied));
    }

    @Test
    void scratchFirstCommitRejectsExternalCollisionAndLaterCommitRejectsExternalChanges() throws Exception {
        ViperPresetEditor editor = ViperPresetEditor.newScratch(new ViperPresetStore(temp.toFile()));
        AtomicInteger hooks = new AtomicInteger();
        editor.hooksForTest(hooks::incrementAndGet, name -> hooks.incrementAndGet());
        Path file = temp.resolve(editor.targetName() + ".json");
        Files.writeString(file, "外部占名");
        assertThrows(IOException.class, editor::persistChecked);
        assertEquals("外部占名", Files.readString(file));
        assertEquals(0, hooks.get());
        assertTrue(editor.isScratch());
        Files.delete(file);
        editor.persistChecked();
        assertEquals(1, hooks.get());
        ViperPresetEditor copy = editor.copy();
        Files.writeString(file, "外部更改");
        assertThrows(IOException.class, copy::persistChecked);
        assertEquals(1, hooks.get());
        assertEquals("外部更改", Files.readString(file));
    }
}
