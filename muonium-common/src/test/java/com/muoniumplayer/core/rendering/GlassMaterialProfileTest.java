package com.muoniumplayer.core.rendering;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlassMaterialProfileTest {
    @Test void oldFactoryDefaultsUpgradeAndUnrelatedSettingsSurvive() {
        JsonObject json=new JsonObject();
        json.addProperty("glassBlurSigma",12f); json.addProperty("glassTintStrength",.55f);
        json.addProperty("glassEdgeGlow",.65f); json.addProperty("glassSurfaceIsland",false);
        json.addProperty("normalScale",.97f);
        GlassMaterialProfile.migrate(json);
        assertEquals(.75f,json.get("glassBlurSigma").getAsFloat());
        assertEquals(.12f,json.get("glassTintStrength").getAsFloat());
        assertEquals(.90f,json.get("glassEdgeGlow").getAsFloat());
        assertFalse(json.get("glassSurfaceIsland").getAsBoolean());
        assertEquals(.97f,json.get("normalScale").getAsFloat());
        assertTrue(json.get("glassRealtimeCapture").getAsBoolean());
        assertFalse(json.get("glassAutoDegrade").getAsBoolean());
        assertEquals(GlassMaterialProfile.VERSION,json.get("glassMaterialVersion").getAsInt());
    }
    @Test void customConcentrationsAndExplicitZeroArePreservedAndMigrationIsIdempotent() {
        JsonObject json=new JsonObject();
        json.addProperty("glassBlurSigma",0f); json.addProperty("glassTintStrength",.22f);
        json.addProperty("glassEdgeGlow",0f);
        GlassMaterialProfile.migrate(json);
        assertEquals(0f,json.get("glassBlurSigma").getAsFloat());
        assertEquals(.22f,json.get("glassTintStrength").getAsFloat());
        assertEquals(0f,json.get("glassEdgeGlow").getAsFloat());
        json.addProperty("glassRealtimeCapture",false); json.addProperty("glassAutoDegrade",true);
        String saved=json.toString(); GlassMaterialProfile.migrate(json);
        assertEquals(saved,json.toString(),"new user choices must not be reset on every load");
    }
    @Test void oldOpaqueAndHeavilyBlurredChoicesCannotHideTheReplacementLens() {
        JsonObject json=new JsonObject();
        json.addProperty("glassBlurSigma",28f); json.addProperty("glassTintStrength",1f);
        GlassMaterialProfile.migrate(json);
        assertEquals(.75f,json.get("glassBlurSigma").getAsFloat());
        assertEquals(.12f,json.get("glassTintStrength").getAsFloat());
    }
    @Test void liquidGlassAndCrystalBothUseRealBackgroundButOrdinaryThemesDoNot() {
        assertTrue(NCMTheme.ThemePreset.LIQUID_GLASS.isTrueGlass());
        assertTrue(NCMTheme.ThemePreset.CRYSTAL.isTrueGlass());
        assertFalse(NCMTheme.ThemePreset.OBSIDIAN_RED.isTrueGlass());
        assertFalse(NCMTheme.ThemePreset.AURORA_FROST.isTrueGlass());
    }
}
