package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 「隐现」背景的两组分片着色器。
 *
 * <p>参考实现使用 Paper Design 的 MeshGradient + Dithering，GLSL 代码是 Apache-2.0。
 * 本项目没有 WebGL/DOM，所以这里保留算法，改写为 Skia RuntimeEffect (SkSL)，并固定为
 * 6 个颜色、4x4 Bayer 抖动和 warp 形状，避免 SkSL 数组 uniform 的平台差异。</p>
 */
public final class SkijaLatentEffects {

    private static final String MESH_SKSL = """
            uniform float4 uRect;
            uniform float4 uMisc; // x=time, y=distortion, z=swirl
            uniform float4 c0;
            uniform float4 c1;
            uniform float4 c2;
            uniform float4 c3;
            uniform float4 c4;
            uniform float4 c5;

            float2 getPosition(float i, float t) {
                float a = i * .37;
                float b = .6 + fract(i / 3.) * .9;
                float c = .8 + fract((i + 1.) / 4.);
                float x = sin(t * b + a);
                float y = cos(t * c + a * 1.5);
                return .5 + .5 * float2(x, y);
            }

            float weightAt(float2 uv, float2 pos) {
                float dist = length(uv - pos);
                dist = pow(dist, 3.5);
                return 1. / (dist + 1e-3);
            }

            half4 main(float2 coord) {
                float2 size = max(uRect.zw, float2(.0001));
                float2 uv = (coord - uRect.xy) / size;
                float t = .5 * (uMisc.x + 41.5);

                float radius = smoothstep(0., 1., length(uv - .5));
                float center = 1. - radius;
                uv.x += uMisc.y * center * sin(t + .4 * smoothstep(0., 1., uv.y))
                        * cos(.2 * t + 2.4 * smoothstep(0., 1., uv.y));
                uv.x += uMisc.y * center * .5 * sin(t + .8 * smoothstep(0., 1., uv.y))
                        * cos(.2 * t + 4.8 * smoothstep(0., 1., uv.y));
                uv.y += uMisc.y * center * cos(t + 2. * smoothstep(0., 1., uv.x));
                uv.y += uMisc.y * center * .5 * cos(t + 4. * smoothstep(0., 1., uv.x));

                float2 rotated = uv - .5;
                float angle = 3. * uMisc.z * radius;
                float ca = cos(-angle);
                float sa = sin(-angle);
                uv = float2(rotated.x * ca - rotated.y * sa,
                        rotated.x * sa + rotated.y * ca) + .5;

                float w0 = weightAt(uv, getPosition(0., t));
                float w1 = weightAt(uv, getPosition(1., t));
                float w2 = weightAt(uv, getPosition(2., t));
                float w3 = weightAt(uv, getPosition(3., t));
                float w4 = weightAt(uv, getPosition(4., t));
                float w5 = weightAt(uv, getPosition(5., t));
                float total = max(1e-4, w0 + w1 + w2 + w3 + w4 + w5);

                float3 color = (c0.rgb * w0 + c1.rgb * w1 + c2.rgb * w2
                        + c3.rgb * w3 + c4.rgb * w4 + c5.rgb * w5) / total;
                return half4(half3(clamp(color, 0., 1.)), 1.);
            }
            """;

    private static final String DITHER_SKSL = """
            uniform float4 uRect;
            uniform float4 uMisc; // x=time, y=pixel size
            uniform float4 cFront;
            uniform float4 cBack;

            float bayer4(int x, int y) {
                int index = int(mod(float(y), 4.)) * 4 + int(mod(float(x), 4.));
                if (index == 0) return 0. / 16.;
                if (index == 1) return 8. / 16.;
                if (index == 2) return 2. / 16.;
                if (index == 3) return 10. / 16.;
                if (index == 4) return 12. / 16.;
                if (index == 5) return 4. / 16.;
                if (index == 6) return 14. / 16.;
                if (index == 7) return 6. / 16.;
                if (index == 8) return 3. / 16.;
                if (index == 9) return 11. / 16.;
                if (index == 10) return 1. / 16.;
                if (index == 11) return 9. / 16.;
                if (index == 12) return 15. / 16.;
                if (index == 13) return 7. / 16.;
                if (index == 14) return 13. / 16.;
                return 5. / 16.;
            }

            half4 main(float2 coord) {
                float2 size = max(uRect.zw, float2(.0001));
                float2 p = (coord - uRect.xy) * .003;
                float t = .5 * uMisc.x;

                // Paper Design 的 warp 是 5 级谐波叠加；只保留前 3 级会出现大块平色区域。
                for (float i = 1.; i < 6.; i += 1.) {
                    p.x += .6 / i * cos(i * 2.5 * p.y + t);
                    p.y += .6 / i * cos(i * 1.5 * p.x + t);
                }

                float shape = .15 / max(.001, abs(sin(t - p.y - p.x)));
                shape = smoothstep(.02, 1., shape);

                float px = max(1., uMisc.y);
                int bx = int(floor((coord.x - uRect.x) / px));
                int by = int(floor((coord.y - uRect.y) / px));
                float dither = bayer4(bx, by) - .5;
                float result = step(.5, shape + dither);
                float3 color = mix(cBack.rgb, cFront.rgb, result);
                return half4(half3(color), 1.);
            }
            """;


    private static RuntimeEffect meshEffect;
    private static RuntimeEffect ditherEffect;
    private static boolean unavailable;
    private static boolean warned;

    private SkijaLatentEffects() {
    }

    /** 纯源码测试入口：确认 uniform 布局仍与 Java ByteBuffer 的 16 字节步长一致。 */
    static String meshShaderSource() {
        return MESH_SKSL;
    }

    static String ditherShaderSource() {
        return DITHER_SKSL;
    }

    public static boolean draw(double x, double y, double width, double height,
                               float meshTime, float ditherTime,
                               float distortion, float swirl, float ditherSize,
                               float ditherOpacity, float alpha, int[] palette) {
        int ditherBack = palette != null ? colorAt(palette, 2, 0x1A202C) : 0x1A202C;
        int ditherFront = palette != null ? colorAt(palette, 0, 0x7C9CFF) : 0x7C9CFF;
        return draw(x, y, width, height, meshTime, ditherTime, distortion, swirl, ditherSize,
                ditherOpacity, alpha, ditherBack, ditherFront, palette, true, true);
    }

    public static boolean draw(double x, double y, double width, double height,
                               float meshTime, float ditherTime,
                               float distortion, float swirl, float ditherSize,
                               float ditherOpacity, float alpha,
                               int ditherBack, int ditherFront, int[] meshPalette,
                               boolean showDithering, boolean showMesh) {
        return draw(SkijaGfx.canvas(), x, y, width, height, meshTime, ditherTime,
                distortion, swirl, ditherSize, ditherOpacity, alpha,
                ditherBack, ditherFront, meshPalette, showDithering, showMesh);
    }

    static boolean draw(Canvas canvas, double x, double y, double width, double height,
                        float meshTime, float ditherTime,
                        float distortion, float swirl, float ditherSize,
                        float ditherOpacity, float alpha, int[] palette) {
        int ditherBack = palette != null ? colorAt(palette, 2, 0x1A202C) : 0x1A202C;
        int ditherFront = palette != null ? colorAt(palette, 0, 0x7C9CFF) : 0x7C9CFF;
        return draw(canvas, x, y, width, height, meshTime, ditherTime, distortion, swirl,
                ditherSize, ditherOpacity, alpha, ditherBack, ditherFront, palette, true, true);
    }

    static boolean draw(Canvas canvas, double x, double y, double width, double height,
                        float meshTime, float ditherTime,
                        float distortion, float swirl, float ditherSize,
                        float ditherOpacity, float alpha,
                        int ditherBack, int ditherFront, int[] meshPalette,
                        boolean showDithering, boolean showMesh) {
        if (canvas == null || width <= 0.5 || height <= 0.5 || alpha <= .001f
                || (!showDithering && !showMesh)
                || (showMesh && (meshPalette == null || meshPalette.length < 6))
                || !ensureCompiled()) {
            return false;
        }

        try {
            if (showMesh) {
                drawMesh(canvas, x, y, width, height, meshTime, distortion, swirl, alpha,
                        colorAt(meshPalette, 0, 0x7C9CFF),
                        colorAt(meshPalette, 1, 0xC084FC),
                        colorAt(meshPalette, 2, 0x38BDF8),
                        colorAt(meshPalette, 3, 0xFB7185),
                        colorAt(meshPalette, 4, 0x34D399),
                        colorAt(meshPalette, 5, 0xFBBF24));
            }
            if (showDithering) {
                drawDither(canvas, x, y, width, height, ditherTime, ditherSize,
                        showMesh ? ditherOpacity : 1f, alpha, showMesh,
                        ditherFront, ditherBack);
            }
            return true;
        } catch (Throwable failure) {
            unavailable = true;
            if (!warned) {
                warned = true;
                System.out.println("[MuoniumPlayer/Latent] shader 绘制不可用，已回退模糊封面: "
                        + failure);
            }
            return false;
        }
    }

    private static boolean ensureCompiled() {
        if (unavailable) return false;
        if (meshEffect != null && ditherEffect != null) return true;
        try {
            meshEffect = RuntimeEffect.makeForShader(MESH_SKSL);
            ditherEffect = RuntimeEffect.makeForShader(DITHER_SKSL);
            return true;
        } catch (Throwable failure) {
            unavailable = true;
            if (!warned) {
                warned = true;
                System.out.println("[MuoniumPlayer/Latent] SkSL 编译失败，已回退模糊封面: "
                        + failure);
            }
            return false;
        }
    }

    private static void drawMesh(Canvas canvas, double x, double y, double width, double height,
                                 float time, float distortion, float swirl, float alpha,
                                 int c0, int c1, int c2, int c3, int c4, int c5) {
        ByteBuffer buffer = ByteBuffer.allocate(8 * 16).order(ByteOrder.LITTLE_ENDIAN);
        putRect(buffer, x, y, width, height);
        buffer.putFloat(time).putFloat(distortion).putFloat(swirl).putFloat(0f);
        putRgb(buffer, c0);
        putRgb(buffer, c1);
        putRgb(buffer, c2);
        putRgb(buffer, c3);
        putRgb(buffer, c4);
        putRgb(buffer, c5);
        try (Data data = Data.makeFromBytes(buffer.array());
             Shader shader = meshEffect.makeShader(data, new Shader[0])) {
            Paint paint = SkijaGfx.fillShader(shader);
            paint.setAlphaf(clamp01(alpha));
            canvas.drawRect(rect(x, y, width, height), paint);
            paint.setShader(null);
        }
    }

    private static void drawDither(Canvas canvas, double x, double y, double width, double height,
                                   float time, float pixelSize, float opacity, float alpha,
                                   boolean softLight, int front, int back) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * 16).order(ByteOrder.LITTLE_ENDIAN);
        putRect(buffer, x, y, width, height);
        float devicePixelSize = (float) (Math.max(.5f, pixelSize) * SkijaHost.guiScale());
        buffer.putFloat(time).putFloat(devicePixelSize).putFloat(0f).putFloat(0f);
        putRgb(buffer, front);
        putRgb(buffer, back);
        try (Data data = Data.makeFromBytes(buffer.array());
             Shader shader = ditherEffect.makeShader(data, new Shader[0])) {
            Paint paint = SkijaGfx.fillShader(shader);
            // 混合模式只在流体层存在时使用 soft-light；单独显示像素层时保持正常合成，
            // 这样不会把像素底色压成一块块黑色。
            paint.setBlendMode(softLight ? BlendMode.SOFT_LIGHT : SkijaGfx.blendMode());
            paint.setAlphaf(clamp01(alpha * opacity));
            canvas.drawRect(rect(x, y, width, height), paint);
            paint.setShader(null);
            paint.setBlendMode(SkijaGfx.blendMode());
        }
    }

    private static Rect rect(double x, double y, double width, double height) {
        return Rect.makeXYWH((float) x, (float) y, (float) width, (float) height);
    }

    private static void putRect(ByteBuffer buffer, double x, double y,
                                double width, double height) {
        buffer.putFloat((float) x).putFloat((float) y)
                .putFloat((float) width).putFloat((float) height);
    }

    private static void putRgb(ByteBuffer buffer, int rgb) {
        buffer.putFloat(((rgb >>> 16) & 0xFF) / 255f)
                .putFloat(((rgb >>> 8) & 0xFF) / 255f)
                .putFloat((rgb & 0xFF) / 255f)
                .putFloat(1f);
    }

    private static int colorAt(int[] colors, int index, int fallback) {
        return index < colors.length ? colors[index] & 0xFFFFFF : fallback;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
