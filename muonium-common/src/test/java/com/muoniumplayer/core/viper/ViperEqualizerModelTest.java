package com.muoniumplayer.core.viper;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.viper.dsp.MinPhaseIIRCoeffs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ViperEqualizerModelTest {

    @TempDir
    Path temp;

    private ViperPresetEditor editor(String json) {
        ViperPresetEditor editor = ViperPresetEditor.over(ViperPresetParser.parse(json, "EQ底稿"),
                "EQ底稿", new ViperPresetStore(temp.toFile()));
        editor.hooksForTest(() -> fail("quiet 不重载"), name -> fail("quiet 不选中"));
        return editor;
    }

    private ViperPresetEditor defaults() {
        return editor("{\"schemaVersion\":2.1}");
    }

    @Test
    void allBandTablesAreTheEngineTablesAndMetadataIsDefensive() {
        int[] supported = ViperEqualizerModel.supportedBandCounts();
        assertArrayEquals(new int[] {10, 15, 25, 31}, supported);
        supported[0] = 2;
        assertArrayEquals(new int[] {10, 15, 25, 31}, ViperEqualizerModel.supportedBandCounts());
        assertArrayEquals(new double[] {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000},
                MinPhaseIIRCoeffs.frequenciesForBandCount(10));
        assertArrayEquals(new double[] {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000},
                MinPhaseIIRCoeffs.frequenciesForBandCount(15));
        assertArrayEquals(new double[] {20, 31.5, 40, 50, 80, 100, 125, 160, 250, 315, 400, 500, 800,
                        1000, 1250, 1600, 2500, 3150, 4000, 5000, 8000, 10000, 12500, 16000, 20000},
                MinPhaseIIRCoeffs.frequenciesForBandCount(25));
        assertArrayEquals(new double[] {20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315,
                        400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000,
                        10000, 12500, 16000, 20000}, MinPhaseIIRCoeffs.frequenciesForBandCount(31));
        for (int count : ViperEqualizerModel.supportedBandCounts()) {
            MinPhaseIIRCoeffs coefficients = new MinPhaseIIRCoeffs();
            assertEquals(1, coefficients.updateCoeffs(count, 48000));
            double[] frequencies = MinPhaseIIRCoeffs.frequenciesForBandCount(count);
            assertEquals(count, frequencies.length);
            for (int band = 0; band < count; band++) {
                assertEquals(coefficients.getIndexFrequency(band), frequencies[band]);
                assertTrue(Double.isFinite(frequencies[band]) && frequencies[band] > 0);
                if (band > 0) assertTrue(frequencies[band] > frequencies[band - 1]);
            }
            frequencies[0] = -1;
            assertTrue(MinPhaseIIRCoeffs.frequenciesForBandCount(count)[0] > 0);
        }
        assertThrows(IllegalArgumentException.class, () -> MinPhaseIIRCoeffs.frequenciesForBandCount(12));
        assertArrayEquals(ViperEqualizerModel.supportedBandCounts(), ViperTuningCatalog.intChoices("equalizer", "bandCount"));
    }

    @Test
    void gettersUseIndependentArraysAndNeverTouchDiskOrWork() throws Exception {
        ViperPresetEditor editor = defaults();
        JsonObject before = editor.presetGroup("equalizer").deepCopy();
        ViperEqualizerModel model = new ViperEqualizerModel(editor);
        assertEquals(10, model.bandCount());
        assertFalse(model.enabled());
        assertArrayEquals(new double[10], model.gains());
        assertEquals("flat", model.matchingPresetId());
        double[] frequencies = model.frequencies();
        double[] gains = model.gains();
        frequencies[0] = -10;
        gains[0] = 5;
        assertEquals(31, model.frequencies()[0]);
        assertEquals(0, model.gains()[0]);
        assertEquals(before, editor.presetGroup("equalizer"));
        try (java.util.stream.Stream<Path> files = Files.list(temp)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void countChangeInterpolatesByActualLogFrequencyNotBandIndexOrRoundedLabels() {
        ViperPresetEditor editor = defaults();
        ViperEqualizerModel model = new ViperEqualizerModel(editor);
        double[] source = {-4, 4, 1, -2, 0, 2, 3, -1, 1, 0};
        model.setGainsQuiet(source);
        model.setEnabledQuiet(true);
        model.setBandCountQuiet(15);
        double expectedAt40 = -4 + 8 * Math.log(40.0 / 31.0) / Math.log(62.0 / 31.0);
        assertEquals(expectedAt40, model.gains()[1], 1e-12);
        double expectedAt63 = 4 + (1 - 4) * Math.log(63.0 / 62.0) / Math.log(125.0 / 62.0);
        assertEquals(expectedAt63, model.gains()[2], 1e-12, "十段第二点是62，不是63");
        assertEquals(-4, model.gains()[0], "频域外采用端点延伸");
        assertEquals(0, model.gains()[14]);
        assertTrue(model.enabled());
        assertEquals(15, editor.getArray(editor.groupIndex("equalizer"), editor.fieldIndex(editor.groupIndex("equalizer"), "bands")).length);
        assertTrue(editor.presetGroup("equalizer").get("presetId").isJsonNull());
    }

    @Test
    void everyBandCountTransitionPreservesLogLinearCurveAndEndpointValues() {
        for (int from : ViperEqualizerModel.supportedBandCounts()) {
            for (int to : ViperEqualizerModel.supportedBandCounts()) {
                ViperEqualizerModel model = new ViperEqualizerModel(defaults());
                model.setBandCountQuiet(from);
                double[] frequencies = model.frequencies();
                double[] gains = Arrays.stream(frequencies).map(frequency -> Math.log(frequency / 1000.0)).toArray();
                model.setGainsQuiet(gains);
                model.setBandCountQuiet(to);
                double[] actual = model.gains();
                double[] destination = model.frequencies();
                for (int band = 0; band < to; band++) {
                    double clampedFrequency = Math.max(frequencies[0], Math.min(frequencies[from - 1], destination[band]));
                    assertEquals(Math.log(clampedFrequency / 1000.0), actual[band], 1e-12, from + "->" + to);
                }
            }
        }
    }

    @Test
    void presetOnlyPatchesEqAndResamplesToCurrentCount() {
        ViperPresetEditor editor = editor("{\"schemaVersion\":2.1,\"equalizer\":{\"presetId\":42,\"future\":{\"nil\":null}},"
                + "\"convolver\":{\"enable\":true,\"kernelFile\":\"用户.irs\"},\"bass\":{\"gain\":3},\"futureGroup\":{\"gain\":8}}");
        Map<String, JsonObject> before = new java.util.LinkedHashMap<>();
        for (int group = 0; group < editor.groupCount(); group++) {
            if (!editor.groupKey(group).equals("equalizer")) {
                before.put(editor.groupKey(group), editor.presetGroup(editor.groupKey(group)).deepCopy());
            }
        }
        JsonObject unknown = editor.presetGroup("equalizer").getAsJsonObject("future").deepCopy();
        JsonObject unknownGroup = editor.presetGroup("futureGroup").deepCopy();
        ViperEqualizerModel model = new ViperEqualizerModel(editor);
        model.setBandCountQuiet(31);
        for (ViperEqPresets.Preset preset : ViperEqPresets.all()) {
            model.applyPresetQuiet(preset.id);
            assertTrue(model.enabled());
            assertEquals(31, model.bandCount());
            assertEquals(preset.id, model.matchingPresetId());
            assertTrue(editor.presetGroup("equalizer").get("presetId").isJsonNull());
            for (int band = 0; band < 31; band++) {
                assertEquals(interpolate(ViperEqPresets.baseFrequencies(), preset.gains(), model.frequencies()[band]),
                        model.gains()[band], 1e-12);
            }
        }
        for (Map.Entry<String, JsonObject> entry : before.entrySet()) {
            assertEquals(entry.getValue(), editor.presetGroup(entry.getKey()), "不能patch其它效果组");
        }
        assertEquals(unknown, editor.presetGroup("equalizer").get("future"));
        assertEquals(unknownGroup, editor.presetGroup("futureGroup"));
    }

    @Test
    void flattenKeepsEnabledAndCountAndGainInputIsDefensiveClamped() {
        ViperPresetEditor editor = defaults();
        ViperEqualizerModel model = new ViperEqualizerModel(editor);
        model.setBandCountQuiet(25);
        double[] values = new double[25];
        Arrays.fill(values, 99);
        model.setGainsQuiet(values);
        values[0] = -5;
        assertEquals(12, model.gains()[0]);
        model.setBandGainQuiet(1, -99);
        assertEquals(-12, model.gains()[1]);
        assertNull(model.matchingPresetId());
        model.setEnabledQuiet(true);
        model.flattenQuiet();
        assertTrue(model.enabled());
        assertEquals(25, model.bandCount());
        assertArrayEquals(new double[25], model.gains());
        assertEquals("flat", model.matchingPresetId());
        model.setEnabledQuiet(false);
        model.applyPresetQuiet("bass");
        model.setEnabledQuiet(false);
        model.flattenQuiet();
        assertFalse(model.enabled());
        assertEquals(25, model.bandCount());
    }

    @Test
    void invalidInputsDoNotResetOrPartiallyEditEq() {
        ViperPresetEditor editor = defaults();
        ViperEqualizerModel model = new ViperEqualizerModel(editor);
        model.applyPresetQuiet("vocal");
        JsonObject before = editor.presetGroup("equalizer").deepCopy();
        for (int count : new int[] {-1, 0, 9, 11, 20, 32}) {
            assertThrows(IllegalArgumentException.class, () -> model.setBandCountQuiet(count));
        }
        assertThrows(IllegalArgumentException.class, () -> model.applyPresetQuiet("unknown"));
        assertThrows(IllegalArgumentException.class, () -> model.applyPresetQuiet(null));
        assertThrows(IllegalArgumentException.class, () -> model.setGainsQuiet(null));
        assertThrows(IllegalArgumentException.class, () -> model.setGainsQuiet(new double[15]));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            double[] array = model.gains();
            array[9] = invalid;
            assertThrows(IllegalArgumentException.class, () -> model.setGainsQuiet(array));
            assertThrows(IllegalArgumentException.class, () -> model.setBandGainQuiet(0, invalid));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> model.setBandGainQuiet(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> model.setBandGainQuiet(10, 1));
        assertEquals(before, editor.presetGroup("equalizer"));
        editor.setNumberQuiet(editor.groupIndex("equalizer"), editor.fieldIndex(editor.groupIndex("equalizer"), "bandCount"), 12);
        JsonObject invalidCount = editor.presetGroup("equalizer").deepCopy();
        assertThrows(IllegalArgumentException.class, () -> new ViperEqualizerModel(editor));
        assertEquals(invalidCount, editor.presetGroup("equalizer"));
    }

    @Test
    void shortExistingArraysExposeZeroDefaultsWithoutSilentlyFlatteningKnownValues() {
        ViperPresetEditor editor = editor("{\"schemaVersion\":2.1,\"equalizer\":{\"bandCount\":15,\"bands\":[2,-3]}}");
        JsonObject before = editor.presetGroup("equalizer").deepCopy();
        ViperEqualizerModel model = new ViperEqualizerModel(editor);
        double[] expected = new double[15];
        expected[0] = 2;
        expected[1] = -3;
        assertArrayEquals(expected, model.gains());
        assertEquals(before, editor.presetGroup("equalizer"));
        editor.presetGroup("equalizer").getAsJsonArray("bands").add(Double.NaN);
        assertThrows(IllegalArgumentException.class, model::gains);
    }

    @Test
    void eightStartingPresetsAreImmutableOriginalAndUseTrueTenBandBase() {
        List<ViperEqPresets.Preset> presets = ViperEqPresets.all();
        assertEquals(8, presets.size());
        Set<String> expected = new HashSet<>(List.of("flat", "bass", "vocal", "pop", "rock", "jazz", "classical", "electronic"));
        Set<String> actual = new HashSet<>();
        for (ViperEqPresets.Preset preset : presets) {
            assertTrue(actual.add(preset.id));
            assertFalse(preset.name.isBlank());
            assertFalse(preset.description.isBlank());
            double[] gains = preset.gains();
            assertEquals(10, gains.length);
            for (double gain : gains) assertTrue(Double.isFinite(gain) && gain >= -12 && gain <= 5);
            double original = gains[0];
            gains[0] = 99;
            assertEquals(original, preset.gains()[0]);
        }
        assertEquals(expected, actual);
        assertThrows(UnsupportedOperationException.class, () -> presets.clear());
        assertArrayEquals(MinPhaseIIRCoeffs.frequenciesForBandCount(10), ViperEqPresets.baseFrequencies());
        double[] base = ViperEqPresets.baseFrequencies();
        base[0] = 31.5;
        assertEquals(31, ViperEqPresets.baseFrequencies()[0]);
        assertEquals(62, ViperEqPresets.baseFrequencies()[1]);
    }

    private double interpolate(double[] frequencies, double[] gains, double at) {
        if (at <= frequencies[0]) return gains[0];
        if (at >= frequencies[frequencies.length - 1]) return gains[gains.length - 1];
        for (int right = 1; right < frequencies.length; right++) {
            if (at <= frequencies[right]) {
                double fraction = Math.log(at / frequencies[right - 1])
                        / Math.log(frequencies[right] / frequencies[right - 1]);
                return gains[right - 1] + fraction * (gains[right] - gains[right - 1]);
            }
        }
        throw new AssertionError("no interpolation interval");
    }
}
