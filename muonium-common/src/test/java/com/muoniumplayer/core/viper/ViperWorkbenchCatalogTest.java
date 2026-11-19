package com.muoniumplayer.core.viper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViperWorkbenchCatalogTest {

    @Test
    void everyRegisteredGroupHasOneClearCategoryAndDescription() {
        int total = 0;
        for (ViperPresetSchema.Group group : ViperPresetSchema.groups()) {
            assertNotNull(ViperWorkbenchCatalog.category(group.key));
            assertFalse(ViperWorkbenchCatalog.description(group.key).isBlank());
            total++;
        }
        assertEquals(25, total);
        assertEquals(ViperWorkbenchCatalog.Category.SPACE, ViperWorkbenchCatalog.category("jdspReverb"));
        assertEquals(ViperWorkbenchCatalog.Category.DEVICE, ViperWorkbenchCatalog.category("ddc"));
        assertThrows(IllegalArgumentException.class, () -> ViperWorkbenchCatalog.category("invented"));
    }

    @Test
    void conversionsRoundTripAllFiniteRangedSchemaFields() {
        for (ViperPresetSchema.Group group : ViperPresetSchema.groups()) {
            for (ViperPresetSchema.Field field : group.fields) {
                if (!field.ranged()) continue;
                for (double stored : new double[]{field.min, (field.min + field.max) * .5, field.max}) {
                    double display = ViperWorkbenchCatalog.toDisplay(group.key, field.key, stored);
                    assertEquals(stored, ViperWorkbenchCatalog.fromDisplay(group.key, field.key, display), 1e-8,
                            group.key + "." + field.key);
                }
            }
        }
    }

    @Test
    void outputGainAndDynamicTimeAreNotMislabelledAsEqPreampOrSeconds() {
        assertEquals(-6.020599913, ViperWorkbenchCatalog.toDisplay("masterLimiter", "outputVolume", .5), 1e-8);
        assertEquals(.5, ViperWorkbenchCatalog.fromDisplay("masterLimiter", "outputVolume", -6.020599913), 1e-8);
        assertEquals(20, ViperWorkbenchCatalog.toDisplay("fetCompressor", "attack", .020), 1e-9);
        assertEquals("ms 配置", ViperWorkbenchCatalog.unit("fetCompressor", "attack"));
        assertEquals(20, ViperWorkbenchCatalog.toDisplay("dynamicEq", "attacks", 20), 0);
        assertEquals("ms", ViperWorkbenchCatalog.unit("dynamicEq", "attacks"));
        assertEquals(-9, ViperWorkbenchCatalog.toDisplay("jdspReverb", "erefwet", -9), 0);
        assertTrue(ViperWorkbenchCatalog.fieldHint("masterLimiter", "outputVolume").contains("不是 EQ" )
                || ViperWorkbenchCatalog.description("masterLimiter").contains("不是 EQ"));
        assertEquals(50, ViperWorkbenchCatalog.toDisplay("reverb", "dry", .5), 0);
        assertTrue(ViperWorkbenchCatalog.fieldHint("reverb", "dry").contains("乘 2"));
        assertTrue(ViperWorkbenchCatalog.fieldHint("fetCompressor", "ratio").contains("不能解释"));
    }

    @Test
    void nonfiniteValuesAreRejectedAndFrequencyScalesAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> ViperWorkbenchCatalog.toDisplay("equalizer", "bands", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ViperWorkbenchCatalog.fromDisplay("masterLimiter", "outputVolume", Double.POSITIVE_INFINITY));
        assertTrue(ViperWorkbenchCatalog.logarithmic("bass", "frequency"));
        assertFalse(ViperWorkbenchCatalog.logarithmic("bass", "gain"));
        assertFalse(ViperWorkbenchCatalog.logarithmic("diffSurround", "lpCutoff"));
    }
}
