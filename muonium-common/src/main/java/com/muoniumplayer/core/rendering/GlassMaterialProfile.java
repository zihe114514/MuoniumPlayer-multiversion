package com.muoniumplayer.core.rendering;

import com.google.gson.JsonObject;

/** Replaces factory diffuse-glass values without touching unrelated or customized HUD fields. */
public final class GlassMaterialProfile {
    public static final int VERSION = 1;
    private GlassMaterialProfile() { }

    public static void migrate(JsonObject config) {
        if (number(config, "glassMaterialVersion", 0f) >= VERSION) return;
        // Old high blur / opaque tint would conceal the new lens entirely. Reset those once;
        // preserve already-clear values and all user edits after VERSION is recorded.
        if (!config.has("glassBlurSigma") || number(config,"glassBlurSigma",12f) > 1.5f)
            config.addProperty("glassBlurSigma", .75f);
        if (!config.has("glassTintStrength") || number(config,"glassTintStrength",.55f) > .25f)
            config.addProperty("glassTintStrength", .12f);
        replaceFactory(config, "glassEdgeGlow", .65f, .90f);
        // Smooth motion is part of the lens; the performance controls remain user-adjustable.
        if (!config.has("glassRealtimeCapture") || config.get("glassRealtimeCapture").isJsonPrimitive())
            config.addProperty("glassRealtimeCapture", true);
        if (!config.has("glassAutoDegrade") || config.get("glassAutoDegrade").isJsonPrimitive())
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
