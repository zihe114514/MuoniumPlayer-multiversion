package com.muoniumplayer.core.viper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.viper.dsp.MultiBiquad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ViperDynamicEqModelTest {

    private static final String[] FIELDS = {"freqs", "qs", "gains", "thresholds", "attacks", "releases", "filterTypes"};

    @TempDir
    Path temp;

    private ViperPresetEditor editor(String json) {
        ViperPresetEditor editor = ViperPresetEditor.over(ViperPresetParser.parse(json, "动态底稿"),
                "动态底稿", new ViperPresetStore(temp.toFile()));
        editor.hooksForTest(() -> fail("quiet 不重载"), name -> fail("quiet 不选中"));
        return editor;
    }

    private ViperPresetEditor defaults() {
        return editor("{\"schemaVersion\":2.1}");
    }

    @Test
    void constructorAndGettersAreMemoryOnlyAndDoNotPatchShortArrays() throws Exception {
        ViperPresetEditor editor = editor("{\"schemaVersion\":2.1,\"dynamicEq\":{\"bandCount\":5,\"freqs\":[90],\"qs\":[2],"
                + "\"gains\":[-3],\"thresholds\":[-40],\"attacks\":[5],\"releases\":[80],\"filterTypes\":[5]}}");
        JsonObject before = editor.presetGroup("dynamicEq").deepCopy();
        ViperDynamicEqModel model = new ViperDynamicEqModel(editor);
        assertEquals(5, model.bandCount());
        assertEquals(90, model.value("freqs", 0));
        assertEquals(150, model.value("freqs", 1));
        assertEquals(400, model.value("freqs", 4));
        assertEquals(2, model.value("qs", 0));
        assertEquals(1.5, model.value("qs", 4));
        assertEquals(-3, model.value("gains", 0));
        assertEquals(0, model.value("gains", 4));
        assertEquals(-20, model.value("thresholds", 4));
        assertEquals(10, model.value("attacks", 4));
        assertEquals(100, model.value("releases", 4));
        assertEquals(0, model.filterType(0));
        assertEquals(0, model.filterType(4));
        assertFalse(model.enabled());
        assertEquals(before, editor.presetGroup("dynamicEq"));
        try (java.util.stream.Stream<Path> paths = Files.list(temp)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void everyCountWritesAllActiveArraysWithSafeSchemaDefaultsAndRetainsOtherData() {
        ViperPresetEditor editor = editor("{\"schemaVersion\":2.1,\"dynamicEq\":{\"future\":{\"nil\":null}},\"convolver\":{\"kernelFile\":\"用户.irs\"}}");
        JsonObject other = editor.presetGroup("convolver").deepCopy();
        JsonObject unknown = editor.presetGroup("dynamicEq").getAsJsonObject("future").deepCopy();
        ViperDynamicEqModel model = new ViperDynamicEqModel(editor);
        int group = editor.groupIndex("dynamicEq");
        for (int count = 1; count <= 10; count++) {
            model.setBandCountQuiet(count);
            assertEquals(count, model.bandCount());
            for (String field : FIELDS) {
                double[] values = editor.getArray(group, editor.fieldIndex(group, field));
                assertEquals(count, values.length, field);
                for (double value : values) assertTrue(Double.isFinite(value));
            }
            for (int band = 0; band < count; band++) {
                assertTrue(model.value("freqs", band) >= 20);
                assertTrue(model.value("qs", band) >= 0.5);
                assertTrue(model.value("attacks", band) >= 1);
                assertTrue(model.value("releases", band) >= 10);
                assertEquals(0, model.filterType(band));
            }
        }
        assertEquals(400, model.value("freqs", 9), "超过schema三段时延伸末默认值");
        assertEquals(1.5, model.value("qs", 9));
        assertEquals(-20, model.value("thresholds", 9));
        assertEquals(10, model.value("attacks", 9));
        assertEquals(100, model.value("releases", 9));
        assertEquals(unknown, editor.presetGroup("dynamicEq").get("future"));
        assertEquals(other, editor.presetGroup("convolver"));
    }

    @Test
    void valueWritesFillEveryArrayAndClampPerFieldWithoutChangingUnknowns() {
        ViperPresetEditor editor = editor("{\"schemaVersion\":2.1,\"dynamicEq\":{\"bandCount\":6,\"freqs\":[],\"qs\":[],"
                + "\"gains\":[],\"thresholds\":[],\"attacks\":[],\"releases\":[],\"filterTypes\":[],\"future\":13}}");
        ViperDynamicEqModel model = new ViperDynamicEqModel(editor);
        model.setValueQuiet("gains", 5, -3);
        int group = editor.groupIndex("dynamicEq");
        for (String field : FIELDS) assertEquals(6, editor.getArray(group, editor.fieldIndex(group, field)).length);
        model.setValueQuiet("freqs", 0, -2);
        model.setValueQuiet("freqs", 1, 99999);
        model.setValueQuiet("freqs", 2, 500.6);
        model.setValueQuiet("qs", 0, 0);
        model.setValueQuiet("qs", 1, 99);
        model.setValueQuiet("gains", 0, -99);
        model.setValueQuiet("gains", 1, 99);
        model.setValueQuiet("thresholds", 0, -999);
        model.setValueQuiet("thresholds", 1, 999);
        model.setValueQuiet("attacks", 0, 0);
        model.setValueQuiet("attacks", 1, 999);
        model.setValueQuiet("releases", 0, 0);
        model.setValueQuiet("releases", 1, 999);
        assertEquals(20, model.value("freqs", 0));
        assertEquals(20000, model.value("freqs", 1));
        assertEquals(501, model.value("freqs", 2));
        assertEquals(0.5, model.value("qs", 0));
        assertEquals(8, model.value("qs", 1));
        assertEquals(-12, model.value("gains", 0));
        assertEquals(12, model.value("gains", 1));
        assertEquals(-80, model.value("thresholds", 0));
        assertEquals(0, model.value("thresholds", 1));
        assertEquals(1, model.value("attacks", 0));
        assertEquals(100, model.value("attacks", 1));
        assertEquals(10, model.value("releases", 0));
        assertEquals(500, model.value("releases", 1));
        assertEquals(-3, model.value("gains", 5));
        assertEquals(13, editor.presetGroup("dynamicEq").get("future").getAsInt());
        model.setEnabledQuiet(true);
        assertTrue(model.enabled());
        assertEquals(6, model.bandCount());
    }

    @Test
    void oldFilterTypeCodesHaveCanonicalReadViewAndOnlyWritesChangeWork() {
        ViperPresetEditor editor = editor("{\"schemaVersion\":2.1,\"dynamicEq\":{\"filterTypes\":[5,6,7],\"qs\":[8,8,8]}}");
        JsonObject before = editor.presetGroup("dynamicEq").deepCopy();
        ViperDynamicEqModel model = new ViperDynamicEqModel(editor);
        assertEquals(0, model.filterType(0));
        assertEquals(1, model.filterType(1));
        assertEquals(2, model.filterType(2));
        assertEquals(8, model.value("qs", 0));
        assertEquals(1, model.value("qs", 1));
        assertEquals(1, model.value("qs", 2));
        assertEquals(before, editor.presetGroup("dynamicEq"), "getter不能静默改底稿");
        model.setValueQuiet("gains", 0, 2);
        assertArrayEquals(new double[] {0, 1, 2}, editor.getArray(editor.groupIndex("dynamicEq"),
                editor.fieldIndex(editor.groupIndex("dynamicEq"), "filterTypes")));
        assertArrayEquals(new double[] {8, 1, 1}, editor.getArray(editor.groupIndex("dynamicEq"),
                editor.fieldIndex(editor.groupIndex("dynamicEq"), "qs")));
    }

    @Test
    void shelfSwitchAndQWritesClampSlopeToHalfThroughOneWhilePeakAllowsEight() {
        ViperPresetEditor editor = defaults();
        ViperDynamicEqModel model = new ViperDynamicEqModel(editor);
        model.setValueQuiet("qs", 0, 8);
        assertEquals(8, model.value("qs", 0));
        model.setFilterTypeQuiet(0, 1);
        assertEquals(1, model.value("qs", 0));
        model.setValueQuiet("qs", 0, 5);
        assertEquals(1, model.value("qs", 0));
        model.setValueQuiet("qs", 0, -5);
        assertEquals(0.5, model.value("qs", 0));
        model.setFilterTypeQuiet(0, 2);
        assertEquals(0.5, model.value("qs", 0));
        model.setFilterTypeQuiet(0, 0);
        model.setValueQuiet("qs", 0, 8);
        assertEquals(8, model.value("qs", 0));
        assertThrows(IllegalArgumentException.class, () -> model.setFilterTypeQuiet(0, 6));
        assertThrows(IllegalArgumentException.class, () -> model.setValueQuiet("filterTypes", 0, 1.5));
    }

    @Test
    void shelfValuesGenerateFiniteCoefficientsInUnchangedEngineFormula() {
        ViperDynamicEqModel model = new ViperDynamicEqModel(defaults());
        model.setBandCountQuiet(1);
        for (int type : new int[] {1, 2}) {
            model.setFilterTypeQuiet(0, type);
            for (double q : new double[] {-99, 0.5, 0.75, 1, 8}) {
                model.setValueQuiet("qs", 0, q);
                assertTrue(model.value("qs", 0) >= 0.5 && model.value("qs", 0) <= 1);
                for (double gain : new double[] {-12, -5, 0, 5, 12}) {
                    model.setValueQuiet("gains", 0, gain);
                    double amplitude = Math.pow(10, gain / 40);
                    double radicand = (1 / amplitude + amplitude) * (1 / model.value("qs", 0) - 1) + 2;
                    assertTrue(radicand >= 0 && Double.isFinite(Math.sqrt(radicand)));
                    for (int rate : new int[] {44100, 48000}) {
                        for (int frequency : new int[] {20, 1000, 20000}) {
                            model.setValueQuiet("freqs", 0, frequency);
                            MultiBiquad filter = new MultiBiquad();
                            filter.refreshFilter(type == 1 ? MultiBiquad.FilterType.LOW_SHELF : MultiBiquad.FilterType.HIGH_SHELF,
                                    (float) gain, (float) model.value("freqs", 0), rate, (float) model.value("qs", 0), false);
                            for (int sample = 0; sample < 64; sample++) {
                                assertTrue(Float.isFinite(filter.processSample(sample == 0 ? 0.01f : 0)));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void invalidArgumentsAreRejectedWithoutPartialBulkMutation() {
        ViperPresetEditor editor = defaults();
        ViperDynamicEqModel model = new ViperDynamicEqModel(editor);
        JsonObject before = editor.presetGroup("dynamicEq").deepCopy();
        for (int count : new int[] {-1, 0, 11}) assertThrows(IllegalArgumentException.class, () -> model.setBandCountQuiet(count));
        assertThrows(IllegalArgumentException.class, () -> model.value("future", 0));
        assertThrows(IllegalArgumentException.class, () -> model.setValueQuiet("future", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> model.setValueQuiet(null, 0, 1));
        for (String field : FIELDS) {
            assertThrows(IndexOutOfBoundsException.class, () -> model.value(field, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> model.setValueQuiet(field, 3, 0));
            for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () -> model.setValueQuiet(field, 0, invalid));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> model.setFilterTypeQuiet(0, -1));
        assertThrows(IllegalArgumentException.class, () -> model.setFilterTypeQuiet(0, 3));
        assertEquals(before, editor.presetGroup("dynamicEq"));

        JsonArray malformed = editor.presetGroup("dynamicEq").getAsJsonArray("releases");
        malformed.add(Double.NaN);
        JsonObject invalidBulk = editor.presetGroup("dynamicEq").deepCopy();
        assertThrows(IllegalArgumentException.class, () -> model.setBandCountQuiet(10));
        assertThrows(IllegalArgumentException.class, () -> model.setValueQuiet("gains", 0, 2));
        assertEquals(invalidBulk, editor.presetGroup("dynamicEq"), "所有数组先验证，不能已有部分写入");
    }

    @Test
    void invalidCountOrUnknownFilterTypeNeverSilentlyResetsPreset() {
        ViperPresetEditor invalidCount = editor("{\"schemaVersion\":2.1,\"dynamicEq\":{\"bandCount\":0}}");
        JsonObject countBefore = invalidCount.presetGroup("dynamicEq").deepCopy();
        assertThrows(IllegalArgumentException.class, () -> new ViperDynamicEqModel(invalidCount));
        assertEquals(countBefore, invalidCount.presetGroup("dynamicEq"));
        ViperPresetEditor invalidFilter = editor("{\"schemaVersion\":2.1,\"dynamicEq\":{\"filterTypes\":[0,8,0]}}");
        JsonObject filterBefore = invalidFilter.presetGroup("dynamicEq").deepCopy();
        assertThrows(IllegalArgumentException.class, () -> new ViperDynamicEqModel(invalidFilter));
        assertEquals(filterBefore, invalidFilter.presetGroup("dynamicEq"));
    }

    @Test
    void editingOneModelCopyDoesNotShareDynamicArrays() {
        ViperPresetEditor editor = defaults();
        ViperPresetEditor copy = editor.copy();
        ViperDynamicEqModel original = new ViperDynamicEqModel(editor);
        ViperDynamicEqModel copied = new ViperDynamicEqModel(copy);
        copied.setBandCountQuiet(10);
        copied.setValueQuiet("gains", 0, -3);
        original.setValueQuiet("thresholds", 0, -50);
        assertEquals(3, original.bandCount());
        assertEquals(10, copied.bandCount());
        assertEquals(0, original.value("gains", 0));
        assertEquals(-3, copied.value("gains", 0));
        assertEquals(-50, original.value("thresholds", 0));
        assertEquals(-20, copied.value("thresholds", 0));
    }
}
