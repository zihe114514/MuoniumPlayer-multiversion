package com.muoniumplayer.core.rendering;

import com.google.gson.JsonObject;

/** Replaces factory diffuse-glass values without touching unrelated or customized HUD fields. */
public final class GlassMaterialProfile {
    public static final int VERSION = 2;
    private GlassMaterialProfile() { }

    public static void migrate(JsonObject config) {
        float from = number(config, "glassMaterialVersion", 0f);
        if (from >= VERSION) return;
        if (from < 1f) {
            // Old high blur / opaque tint would conceal the new lens entirely. Reset those once;
            // preserve already-clear values and all user edits after VERSION is recorded.
            if (!config.has("glassBlurSigma") || number(config,"glassBlurSigma",12f) > 1.5f)
                config.addProperty("glassBlurSigma", .75f);
            if (!config.has("glassTintStrength") || number(config,"glassTintStrength",.55f) > .25f)
                config.addProperty("glassTintStrength", .12f);
            replaceFactory(config, "glassEdgeGlow", .65f, .90f);
        }
        // v1 的条件写错了：isJsonPrimitive() 对正常布尔恒为真，于是这两项在首次迁移时被
        // 无条件翻回出厂值——老配置里用户手动关过的「实时背景采集」就是被这一句翻开的，
        // 手机上重新拿回了每帧全屏重糊的最高耗组合。v2 修成原本的意图（注释写的
        // "remain user-adjustable"）：只在缺失时写出厂默认。
        // 手机上真玻璃整体由 GlassMobileTier 禁用，这两个字段在那边只是存档，不参与生效。
        if (!config.has("glassRealtimeCapture"))
            config.addProperty("glassRealtimeCapture", true);
        if (!config.has("glassAutoDegrade"))
            config.addProperty("glassAutoDegrade", false);
        config.addProperty("glassMaterialVersion", VERSION);
    }

    private static void replaceFactory(JsonObject config, String key, float oldValue, float newValue) {
        if (!config.has(key) || Math.abs(number(config, key, Float.NaN) - oldValue) < .00001f)
            config.addProperty(key, newValue);
    }

    private static float number(JsonObject config, String key, float fallback) {
        try { return config.has(key) ? config.get(key).getAsFloat() : fallback; }
        catch (RuntimeException malformed) { return fallback; }
    }
}
