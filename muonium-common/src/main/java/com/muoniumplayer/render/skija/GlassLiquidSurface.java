package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.*;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * LiquidGlass-master's screen-space lens, ported to SkSL. The exponential sampling
 * curve and directional gain are adapted from OverShifted/LiquidGlass (MIT,
 * Copyright (c) 2026 Sepehr Kalanaki). See LICENSE-LIQUIDGLASS.txt in the jar.
 *
 * The production silhouette remains the widget's real rounded rectangle. A
 * normalized superellipse is available for reference-image tests; intersecting
 * that silhouette with a round rectangle would leave uncovered corners in the UI.
 * No blue wash, additive Fresnel, animated grain or second refraction pass.
 */
public final class GlassLiquidSurface {
    private static final String TAG = "[MuoniumPlayer/LiquidGlass] ";
    private static final String SKSL = """
            uniform shader uBackdrop;
            uniform float4 uRect;
            uniform float4 uShape;  // radius, superellipse power (0 = widget), strength, opacity
            uniform float4 uCurve;  // a, b, c, d
            uniform float4 uMisc;   // falloff power, noise, directional gain, edge width
            uniform float4 uSample; // image offset xy, image scale zw

            float sdSuperellipse(float2 p, float n) {
                float2 a = abs(p);
                float numerator = pow(a.x, n) + pow(a.y, n) - 1.0;
                float denominator = n * sqrt(pow(a.x, 2.0*n-2.0)
                                           + pow(a.y, 2.0*n-2.0)) + 0.00001;
                return numerator / denominator;
            }

            float sdRoundBox(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - (halfSize - float2(radius));
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            }

            float glassFalloff(float depth) {
                return 1.0 - uCurve.y * pow(uCurve.z * 2.718281828459045,
                                           -uCurve.w * depth - uCurve.x);
            }

            float rand(float2 p) {
                return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
            }

            half4 main(float2 coord) {
                float2 halfSize = max(uRect.zw * 0.5, float2(0.001));
                float2 center = uRect.xy + halfSize;
                float2 local = coord - center;
                float2 p = local / halfSize;
                float radius = clamp(uShape.x, 0.0, min(halfSize.x, halfSize.y));
                float d;
                if (uShape.y > 0.0) {
                    d = sdSuperellipse(p, max(2.001, uShape.y));
                } else {
                    // sdRoundBox works in device pixels; p is normalized only for the
                    // superellipse and angular edge-light calculation. Mixing p with pixel
                    // halfSize makes the whole panel appear as one giant lens (severe stretch).
                    d = sdRoundBox(local, halfSize, radius) / min(halfSize.x, halfSize.y);
                }
                if (d > 0.0) { return half4(0.0); }
                float depth = max(0.0, -d);
                // Same non-linear contraction as the reference, not a blurred flat fill.
                float lens = pow(max(0.0001, glassFalloff(depth)), uMisc.x);
                lens = mix(1.0, lens, uShape.z);
                float2 samplePoint = center + local * lens;
                half4 sampled = uBackdrop.eval(samplePoint * uSample.zw + uSample.xy);
                float3 rgb = float3(sampled.rgb) / max(float(sampled.a), 0.0001);
                // Reference gl_FragCoord / local UV are bottom-up; Skia is top-down.
                // Avoid atan(0,0) and replace reversed smoothstep by its defined equivalent.
                float angle = length(p) > 0.00001 ? atan(-p.y, p.x) : 0.0;
                float glow = sin(angle - 0.5);
                float edge = 1.0 - smoothstep(0.0, max(0.0001, uMisc.w), depth);
                rgb *= 1.0 + glow * uMisc.z * edge;
                float2 device = coord * uSample.zw + uSample.xy;
                rgb += (rand(device * 0.001) - 0.5) * uMisc.y;
                float alpha = clamp(uShape.w, 0.0, 1.0);
                return half4(half3(clamp(rgb, float3(0.0), float3(1.0))) * half(alpha), half(alpha));
            }
            """;

    private static RuntimeEffect effect;
    private static boolean unavailable;
    private static Shader cachedSource;
    private static Image cachedImage;

    private GlassLiquidSurface() { }

    private static RuntimeEffect effect() {
        if (effect == null && !unavailable) {
            try {
                effect = RuntimeEffect.makeForShader(SKSL);
                if (effect.getUniformSize() != 80) {
                    throw new IllegalStateException("Expected 80 uniform bytes, got " + effect.getUniformSize());
                }
            } catch (Throwable failure) {
                unavailable = true;
                System.err.println(TAG + "Lens shader unavailable; using readable plain UI, NOT frosted glass: " + failure);
            }
        }
        return unavailable ? null : effect;
    }

    /** A failed shader/capture must not leave the ordinary UI with its opaque fill removed. */
    public static boolean available() {
        Canvas canvas = SkijaGfx.canvas();
        return effect() != null && canvas != null && GlassBackdrop.mapping(canvas) != null;
    }

    public static boolean draw(double x, double y, double width, double height,
                               double radius, float strength, float opacity, float sigma) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || effect() == null || !validRect(x, y, width, height, radius)
                || !Float.isFinite(strength) || !Float.isFinite(opacity)
                || !(opacity > 0.002f)) return false;
        GlassBackdrop.Mapping map = GlassBackdrop.mapping(canvas);
        if (map == null) return false;
        Image backdrop = sigma > 0.004f ? GlassBackdrop.blurred(sigma) : GlassBackdrop.image();
        if (backdrop == null) backdrop = GlassBackdrop.image();
        if (backdrop == null) return false;
        try {
            if (cachedImage != backdrop || cachedSource == null) {
                closeSource();
                cachedSource = backdrop.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                        SamplingMode.LINEAR, null);
                cachedImage = backdrop;
            }
            drawOn(canvas, cachedSource, (float)x, (float)y, (float)width, (float)height,
                    (float)radius, map.scaleX, map.scaleY, map.offsetX, map.offsetY,
                    strength, opacity, 0f, 1f, 0.006f,
                    directionalGain(), 0.06f);
            return true;
        } catch (Throwable failure) {
            unavailable = true;
            System.err.println(TAG + "Lens drawing failed; plain-UI fallback: " + failure);
            return false;
        }
    }

    /** Shared by the production GPU path and raster pixel tests. Coordinates map explicitly to image pixels. */
    static void drawOn(Canvas canvas, Shader source, float x, float y, float width, float height,
                       float radius, float sx, float sy, float tx, float ty, float strength,
                       float opacity, float superellipse, float power, float noise, float glow, float edge) {
        if (!validRect(x, y, width, height, radius) || !Float.isFinite(opacity)
                || !Float.isFinite(strength) || opacity <= 0.002f) return;
        RuntimeEffect program = effect();
        if (program == null) throw new IllegalStateException("Liquid lens effect unavailable");
        ByteBuffer bytes = ByteBuffer.allocate(program.getUniformSize()).order(ByteOrder.nativeOrder());
        bytes.putFloat(x).putFloat(y).putFloat(width).putFloat(height);
        bytes.putFloat(radius).putFloat(superellipse).putFloat(clamp01(strength)).putFloat(clamp01(opacity));
        bytes.putFloat(0.7f).putFloat(2.3f).putFloat(5.2f).putFloat(6.9f);
        bytes.putFloat(power).putFloat(noise).putFloat(glow).putFloat(edge);
        bytes.putFloat(tx).putFloat(ty).putFloat(sx).putFloat(sy);
        try (Data data = Data.makeFromBytes(bytes.array());
             Shader lens = program.makeShader(data, new Shader[]{source});
             Paint paint = new Paint().setShader(lens).setBlendMode(BlendMode.SRC_OVER).setAntiAlias(true)) {
            int save = canvas.save();
            try {
                if (superellipse <= 0f) canvas.clipRRect(RRect.makeXYWH(x, y, width, height, radius), true);
                canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    static boolean validRect(double x, double y, double width, double height, double radius) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width)
                && Double.isFinite(height) && Double.isFinite(radius) && width > 0.0 && height > 0.0;
    }

    private static float directionalGain() {
        float glow = com.muoniumplayer.core.settings.HudConfig.glassEdgeGlow;
        return Float.isFinite(glow) ? .30f * Math.max(0f, Math.min(1.5f, glow)) / .90f : .30f;
    }

    private static float clamp01(float x) { return Math.max(0f, Math.min(1f, x)); }

    /** Called before the captured images / GL context are destroyed. Effect survives context reload. */
    public static void release() {
        closeSource();
        unavailable = false;
    }

    private static void closeSource() {
        try {
            if (cachedSource != null) cachedSource.close();
        } catch (Throwable failure) {
            System.err.println(TAG + "Old source view released after context loss: " + failure);
        } finally {
            cachedSource = null;
            cachedImage = null;
        }
    }

    public static boolean compiles() { return effect() != null; }
    public static String source() { return SKSL; }
}
