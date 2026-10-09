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
    @Test void legacyUserPerformanceChoicesSurviveFirstMigration() {
        // v1 的覆盖条件对正常布尔恒为真，老配置里用户已设的这两项在首次迁移时被无条件
        // 翻回出厂值（旧版手动关掉的「实时背景采集」在手机上被重新打开，正是卡死主因之一）。
        // v2 修复后：首次迁移只补缺失的字段，用户已设的一律保留。
        JsonObject json=new JsonObject();
        json.addProperty("glassRealtimeCapture",false); json.addProperty("glassAutoDegrade",true);
        GlassMaterialProfile.migrate(json);
        assertFalse(json.get("glassRealtimeCapture").getAsBoolean(),"首次迁移不得翻掉用户已设的值");
        assertTrue(json.get("glassAutoDegrade").getAsBoolean(),"首次迁移不得翻掉用户已设的值");
        assertEquals(GlassMaterialProfile.VERSION,json.get("glassMaterialVersion").getAsInt());
    }
    @Test void missingPerformanceFieldsGetFactoryDefaults() {
        // 缺失才补，补的是出厂默认（实时采集开、自动降载关）。手机档整体禁用真玻璃
        //（GlassMobileTier），这些字段在那边只是存档，不参与生效——默认值因此不需要平台感知。
        JsonObject json=new JsonObject();
        GlassMaterialProfile.migrate(json);
        assertTrue(json.get("glassRealtimeCapture").getAsBoolean());
        assertFalse(json.get("glassAutoDegrade").getAsBoolean());
        assertEquals(GlassMaterialProfile.VERSION,json.get("glassMaterialVersion").getAsInt());
    }
    @Test void liquidGlassAndCrystalBothUseRealBackgroundButOrdinaryThemesDoNot() {
        assertTrue(NCMTheme.ThemePreset.LIQUID_GLASS.isTrueGlass());
        assertTrue(NCMTheme.ThemePreset.CRYSTAL.isTrueGlass());
        assertFalse(NCMTheme.ThemePreset.OBSIDIAN_RED.isTrueGlass());
        assertFalse(NCMTheme.ThemePreset.AURORA_FROST.isTrueGlass());
    }
}
