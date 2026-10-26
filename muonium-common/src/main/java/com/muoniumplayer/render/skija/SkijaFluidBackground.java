/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * QPlayer fluid renderer adaptation:
 * https://github.com/TIMER-err/qplayer
 *
 * QPlayer is distributed under Apache-2.0. The compositor and the three shader algorithms are kept aligned with
 * qplayer-master.  The Minecraft host supplies a borrowed Skia Image instead
 * of QPlayer's encoded cover bytes; the cover is reduced to the same 32x32
 * working texture and the same AMLL adjustment chain is applied before the
 * shader is run.
 */
package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.ColorMatrix;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Random;

/** QPlayer's album-driven fluid lyric backdrop, rendered through Skija. */
public final class SkijaFluidBackground {

    public static final int STYLE_PIXI_RENDERER = 0;
    public static final int STYLE_MESH_GRADIENT = 1;
    public static final int STYLE_CLASSIC = 2;
    /** 本项目「Latent」双层：网格渐变 + 拜耳像素抖动，封面色驱动。 */
    public static final int STYLE_LATENT = 3;
    /** 本项目「Sora」星空：网格哈希星场，主题色驱动。 */
    public static final int STYLE_SORA = 4;
    /** 本项目「Nomand」：封面的拜耳抖动 / 半调网点两种像素化特效。 */
    public static final int STYLE_NOMAND = 5;

    private static final int STYLE_COUNT = 12;
    private static final int TEX_SIZE = 32;
    private static final float FADE_DUR = 0.6f;
    private static final String SHADER_ROOT = "/shaders/fluid/";
    // 6-11 是层叠形态：6 无（不编译 shader）、7 Latent 网格半、8 Latent 抖动半、
    // 9 Sora 叠加层、10/11 Nomand 两个特效拆层——复用同一批 .sksl 文件，靠
    // layer/effect uniform 分支。底层档位（用户可选）= 0-2 / 4-5 / 6 / 7 / 10 / 11；
    // 8-9 是叠加层内部代号，不进快捷面板的底层轮换表。
    private static final String[][] SHADER_FILES = {
            {"pixi_renderer.sksl", "pixi_renderer_fade.sksl"},
            {"meshgradient.sksl", "meshgradient.sksl"},
            {"classic.sksl", "classic_fade.sksl"},
            {"latent.sksl", "latent.sksl"},
            {"sora.sksl", "sora.sksl"},
            {"nomand.sksl", "nomand.sksl"},
            {null, null},                         // STYLE_NONE：没有自己的 shader
            {"latent.sksl", "latent.sksl"},       // STYLE_LATENT_MESH（layer=0）
            {"latent.sksl", "latent.sksl"},       // STYLE_LATENT_DITHER_LAYER（layer=1）
            {"sora.sksl", "sora.sksl"},           // STYLE_SORA_LAYER（layer=1）
            {"nomand.sksl", "nomand.sksl"},       // STYLE_NOMAND_DITHER（effect=0）
            {"nomand.sksl", "nomand.sksl"}        // STYLE_NOMAND_HALFTONE（effect=1）
    };
    private static final RuntimeEffect[][] EFFECTS = new RuntimeEffect[STYLE_COUNT][2];

    /** Fallback for an image that cannot be read back into the 32x32 working texture. */
    private static final String ADJUST_COVER_SKSL = """
            uniform shader source;

            half4 main(float2 coord) {
                half4 sampled = source.eval(coord);
                float3 rgb = sampled.rgb;
                rgb = (rgb - float3(.5)) * .4 + float3(.5);
                float gray = dot(rgb, float3(.3, .59, .11));
                rgb = mix(float3(gray), rgb, 3.0);
                rgb = (rgb - float3(.5)) * 1.7 + float3(.5);
                rgb *= .75;
                return half4(half3(clamp(rgb, 0., 1.)), sampled.a);
            }
            """;
    private static RuntimeEffect adjustEffect;

    /** Sora's warm white when no theme tint was supplied (raster tests). */
    private static final float[] FOLIA_FALLBACK_TINT = {.95f, .93f, .88f, .55f, .70f, 1f};

    /**
     * 编译失败的负缓存：shader 一旦损坏（或资源缺失），makeEffect 若每次都重试，
     * 渲染线程就会每帧付一次 SkSL 解析再画兜底色——把失败记成哨兵，之后直接
     * 快速失败，绘制方的 catch 接住画兜底，CPU 空转归零。
     */
    private static final RuntimeEffect FAILED_EFFECT = null;
    private static final boolean[] EFFECT_FAILED = new boolean[STYLE_COUNT * 2];

    private static RuntimeEffect makeEffect(int style, boolean fade) {
        int safeStyle = normalizeStyle(style);
        int variant = fade ? 1 : 0;
        int slot = safeStyle * 2 + variant;
        if (EFFECT_FAILED[slot]) {
            throw new IllegalStateException("fluid shader " + safeStyle + "/" + variant
                    + " failed to compile before; not retrying");
        }
        RuntimeEffect cached = EFFECTS[safeStyle][variant];
        if (cached != null) return cached;
        String file = SHADER_FILES[safeStyle][variant];
        if (file == null) throw new IllegalStateException("style " + safeStyle + " has no shader");
        try {
            cached = compileResource(SHADER_ROOT + file, file);
        } catch (Throwable failure) {
            EFFECT_FAILED[slot] = true;
            throw failure;
        }
        EFFECTS[safeStyle][variant] = cached;
        return cached;
    }

    private static RuntimeEffect compileResource(String path, String label) {
        try (InputStream in = SkijaFluidBackground.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("resource not found: " + path);
            StringBuilder source = new StringBuilder(4096);
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) source.append(buffer, 0, read);
            return RuntimeEffect.makeForShader(source.toString());
        } catch (Throwable failure) {
            throw new IllegalStateException("Failed to compile fluid shader " + label, failure);
        }
    }

    private static int normalizeStyle(int style) {
        return style >= STYLE_PIXI_RENDERER && style < BASE_STYLE_COUNT
                ? style : STYLE_PIXI_RENDERER;
    }

    private final float bendDirection;
    private final long startNanos;
    private final Paint fluidPaint = new Paint();

    /* Per-frame inputs the folia-port styles read: split audio bands and the theme tint. */
    private float foliaAudioLow;
    private float foliaAudioMid;
    private float[] foliaTint;

    private Rect fullRect;
    private float fullRectW = -1f;
    private float fullRectH = -1f;

    private int activeStyle = -1;
    private int activeSaturation = 100;
    private ColorFilter saturationFilter;

    /* Current cover. currentImage is borrowed from SkijaImages and is never closed here. */
    private Image currentImage;
    private Image currentRawTexture;
    private Image currentAdjustedTexture;
    private String currentKey;
    private Shader currentShader;
    private Shader currentAdjustedShader;
    private float[] currentAngles = new float[4];
    private AMLLMeshGradient.Data currentMesh;

    /* Previous cover, retained only for QPlayer's track-change cross-fade. */
    private Shader previousShader;
    private Shader previousAdjustedShader;
    private Image previousRawTexture;
    private Image previousAdjustedTexture;
    private float[] previousAngles;
    private AMLLMeshGradient.Data previousMesh;
    private long fadeStartNanos;

    /* QPlayer's battery-saving static frame cache. */
    private Image staticImage;
    private int staticW = -1;
    private int staticH = -1;
    private boolean previousStaticMode;

    public SkijaFluidBackground(long seed) {
        startNanos = System.nanoTime();
        bendDirection = (seed & 1L) == 0L ? 1f : -1f;
    }

    /**
     * Draw one complete QPlayer fluid frame into {@code canvas}.
     *
     * <p>{@code audioDrive} is an additional host input: at zero the shader is
     * exactly the QPlayer frame; positive values only add the requested live
     * envelope/beat response. Static mode intentionally freezes both time and
     * audio, just like QPlayer's battery-saving path.</p>
     */
    public boolean render(Canvas canvas, DirectContext context, float uiScale,
                          float width, float height, Image cover,
                          String trackKey, long nowNanos, boolean staticMode,
                          int style, int saturationPct, float alpha,
                          float audioDrive) {
        return renderInternal(canvas, context, uiScale, width, height, cover, trackKey,
                nowNanos, staticMode, style, saturationPct, alpha, audioDrive, Float.NaN,
                0f, 0f, null);
    }

    /**
     * Production entry with an externally accumulated animation clock.
     *
     * <p>The QPlayer shader itself is still unchanged when {@code audioDrive == 0};
     * the lyric host uses this overload to integrate audio into the clock smoothly.
     * That avoids changing a shader phase abruptly on every beat, which presents as
     * a brightness/geometry flash instead of a continuous acceleration.</p>
     */
    public boolean renderWithTime(Canvas canvas, DirectContext context, float uiScale,
                                  float width, float height, Image cover,
                                  String trackKey, float animationTimeSeconds,
                                  long nowNanos, boolean staticMode,
                                  int style, int saturationPct, float alpha,
                                  float audioDrive) {
        return renderInternal(canvas, context, uiScale, width, height, cover, trackKey,
                nowNanos, staticMode, style, saturationPct, alpha, audioDrive,
                animationTimeSeconds, 0f, 0f, null);
    }

    /**
     * Folia-style entry: additionally carries the split audio bands and the
     * theme tint the Sora starfield draws with.
     *
     * <p>{@code audioLow} / {@code audioMid} are the smoothed bass and mid-band
     * envelopes (0..1) the ported shaders use for their per-band response;
     * {@code tint} is six floats — particle RGB then accent RGB, each 0..1 — or
     * null to let Sora fall back to its built-in warm white. The QPlayer styles
     * ignore all three, so callers can pass them unconditionally.</p>
     */
    public boolean renderWithTime(Canvas canvas, DirectContext context, float uiScale,
                                  float width, float height, Image cover,
                                  String trackKey, float animationTimeSeconds,
                                  long nowNanos, boolean staticMode,
                                  int style, int saturationPct, float alpha,
                                  float audioDrive, float audioLow, float audioMid,
                                  float[] tint) {
        return renderInternal(canvas, context, uiScale, width, height, cover, trackKey,
                nowNanos, staticMode, style, saturationPct, alpha, audioDrive,
                animationTimeSeconds, audioLow, audioMid, tint);
    }

    /** Compatibility entry used by the raster shader tests; the supplied time is explicit. */
    public boolean render(Canvas canvas, float width, float height, Image cover,
                          String trackKey, float timeSeconds, long nowNanos,
                          int style, int saturationPct, float alpha, float audioDrive) {
        return renderInternal(canvas, null, 1f, width, height, cover, trackKey,
                nowNanos, false, style, saturationPct, alpha, audioDrive, timeSeconds,
                0f, 0f, null);
    }

    private boolean renderInternal(Canvas canvas, DirectContext context, float uiScale,
                                   float width, float height, Image cover,
                                   String trackKey, long nowNanos, boolean staticMode,
                                   int style, int saturationPct, float alpha,
                                   float audioDrive, float explicitTime,
                                   float audioLow, float audioMid, float[] tint) {
        if (canvas == null || width <= .5f || height <= .5f || alpha <= .001f) return false;

        // The layer stack may carry the whole picture (Sora over the "none"
        // base), so a missing cover must not bail out before the stack runs;
        // only the cover-driven styles themselves require one. The "none" base
        // paints the dark fallback color without any cover either.
        int resolvedStyle = normalizeStyle(style);
        boolean layerStackActive = layerStackActive();
        if (cover == null && !layerStackActive && resolvedStyle != STYLE_NONE) return false;

        int selectedStyle = resolvedStyle;
        if (selectedStyle != activeStyle) {
            activeStyle = selectedStyle;
            invalidateStatic();
        }
        if (staticMode != previousStaticMode) {
            previousStaticMode = staticMode;
            invalidateStatic();
        }
        setSaturation(saturationPct);
        foliaAudioLow = clamp01(audioLow);
        foliaAudioMid = clamp01(audioMid);
        // Sora 的主题色烘进了静态缓存帧：换主题（强调色变）必须重烘，否则
        // 星点停在旧色直到下一次切歌（review #4）。数组逐位比较避免每帧分配。
        if (!tintEquals(foliaTint, tint)) {
            foliaTint = tint;
            invalidateStatic();
        }
        fluidPaint.setAlphaf(clamp01(alpha));
        if (cover != null) publishCover(cover, trackKey, nowNanos);
        if (cover != null && currentShader == null && !layerStackActive
                && resolvedStyle != STYLE_NONE) return false;

        if (fullRect == null || fullRectW != width || fullRectH != height) {
            fullRect = Rect.makeXYWH(0, 0, width, height);
            fullRectW = width;
            fullRectH = height;
        }

        float audio = clamp01(audioDrive);
        float time = Float.isNaN(explicitTime)
                ? (nowNanos - startNanos) / 1_000_000_000f : explicitTime;

        // Same static-cache branch as QPlayer: render once at device resolution,
        // then only blit the cached frame until a cover/style/size setting changes.
        if (staticMode && context != null) {
            releasePrevious();
            int deviceW = Math.max(1, Math.round(width * Math.max(.01f, uiScale)));
            int deviceH = Math.max(1, Math.round(height * Math.max(.01f, uiScale)));
            if (staticImage == null || staticW != deviceW || staticH != deviceH) {
                buildStatic(context, deviceW, deviceH, time, audio);
            }
            if (staticImage != null) {
                fluidPaint.setAlphaf(clamp01(alpha));
                canvas.drawImageRect(staticImage, Rect.makeWH(deviceW, deviceH), fullRect,
                        SamplingMode.LINEAR, fluidPaint, true);
                return true;
            }
        }

        try {
            if (layerStackActive) {
                drawLayerStack(canvas, width, height, time, audio);
            } else {
                drawFluid(canvas, width, height, time, fadeProgress(nowNanos), audio);
            }
            return true;
        } catch (Throwable failure) {
            renderFallback(canvas, width, height);
            return false;
        }
    }

    private void setSaturation(int saturationPct) {
        int value = Math.max(0, Math.min(200, saturationPct));
        if (value == activeSaturation && (value == 100 || saturationFilter != null)) return;
        activeSaturation = value;
        if (saturationFilter != null) {
            saturationFilter.close();
            saturationFilter = null;
        }
        if (value != 100) saturationFilter = ColorFilter.makeMatrix(saturationMatrix(value / 100f));
        fluidPaint.setColorFilter(saturationFilter);
        invalidateStatic();
    }

    private void publishCover(Image cover, String trackKey, long nowNanos) {
        String key = trackKey == null || trackKey.isEmpty()
                ? "image@" + System.identityHashCode(cover) : trackKey;
        if (Objects.equals(key, currentKey) && currentShader != null && cover == currentImage) return;

        releasePrevious();
        previousShader = currentShader;
        previousAdjustedShader = currentAdjustedShader;
        previousRawTexture = currentRawTexture;
        previousAdjustedTexture = currentAdjustedTexture;
        previousAngles = currentShader == null ? null : currentAngles;
        previousMesh = currentShader == null ? null : currentMesh;

        currentImage = cover; // borrowed; ownership remains with TextureManager/SkijaImages
        currentKey = key;
        currentAngles = generateAngles(key);
        currentMesh = AMLLMeshGradient.create();

        ProcessedCover processed = preprocessCover(cover);
        if (processed != null) {
            currentRawTexture = processed.raw;
            currentAdjustedTexture = processed.adjusted;
            currentShader = makeProcessedShader(currentRawTexture);
            currentAdjustedShader = makeProcessedShader(currentAdjustedTexture);
        } else {
            currentRawTexture = null;
            currentAdjustedTexture = null;
            currentShader = makeSourceShader(cover);
            currentAdjustedShader = makeAdjustedShader(cover);
        }
        // The folia styles read the picture at full detail (dither cells and
        // gradient anchors need real pixels, not QPlayer's 32px blur); they get
        // a second, larger working texture downsampled once from the cover.
        if (activeStyle >= STYLE_LATENT) {
            releaseFoliaTexture();
            foliaTexture = makeFoliaTexture(cover);
            // 纹理创建失败（allocPixels 失败/异常）返回 null：跳过 shader 构建，
            // 走 drawFolia/drawOneLayer 里 foliaShader==null 的回退。这里若 NPE
            // 会穿透 publishCover 直达渲染线程（外层无 catch）。
            foliaShader = foliaTexture == null ? null : makeFoliaShader(foliaTexture);
        }
        fadeStartNanos = nowNanos;
        invalidateStatic();
    }

    /** FOLIA_TEX: large enough for 8x8 dither cells, small enough to re-make per track. */
    private static final int FOLIA_TEX = 256;

    private Image foliaTexture;
    private Shader foliaShader;

    private static Image makeFoliaTexture(Image source) {
        try {
            ImageInfo info = new ImageInfo(FOLIA_TEX, FOLIA_TEX,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            Bitmap thumb = new Bitmap();
            if (!thumb.allocPixels(info)) {
                thumb.close();
                return null;
            }
            try (Canvas canvas = new Canvas(thumb)) {
                canvas.drawImageRect(source,
                        Rect.makeWH(source.getWidth(), source.getHeight()),
                        Rect.makeWH(FOLIA_TEX, FOLIA_TEX), SamplingMode.LINEAR, null, true);
            }
            Image image = Image.makeFromBitmap(thumb);
            thumb.close();
            return image;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The folia shaders sample {@code cover.eval(uv * 32)} — the same 0..32
     * domain as the QPlayer 32px working texture. Skija's image-shader
     * localMatrix maps texture space to shader space (shader = M·texel), so
     * covering the 256px texture from the 0..32 domain needs M = 32/256 = .125;
     * the inverse scaling would squeeze every sample into the top-left corner
     * (verified with the striped-cover raster probe: 8x squeezed the whole
     * cover into the first stripe, .125 spreads all four evenly).
     */
    private Shader makeFoliaShader(Image image) {
        Matrix33 scale = new Matrix33(
                (float) TEX_SIZE / FOLIA_TEX, 0f, 0f,
                0f, (float) TEX_SIZE / FOLIA_TEX, 0f,
                0f, 0f, 1f);
        return image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                SamplingMode.LINEAR, scale);
    }

    private void releaseFoliaTexture() {
        if (foliaShader != null) {
            foliaShader.close();
            foliaShader = null;
        }
        if (foliaTexture != null) {
            foliaTexture.close();
            foliaTexture = null;
        }
    }

    private Shader makeProcessedShader(Image image) {
        return image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                SamplingMode.LINEAR, Matrix33.IDENTITY);
    }

    private Shader makeSourceShader(Image image) {
        int imageWidth = Math.max(1, image.getImageInfo().getWidth());
        int imageHeight = Math.max(1, image.getImageInfo().getHeight());
        // Skija's image-shader localMatrix maps texture space to shader space
        // (shader = M·texel), so the 0..32 eval domain needs M = 32/W — the
        // inverse had squeezed the whole cover into a 2px corner (raster-probed
        // together with makeFoliaShader; this path only runs when the 32px
        // preprocess fails, which is why it survived unnoticed).
        Matrix33 matrix = new Matrix33(
                (float) TEX_SIZE / imageWidth, 0f, 0f,
                0f, (float) TEX_SIZE / imageHeight, 0f,
                0f, 0f, 1f);
        return image.makeShader(FilterTileMode.MIRROR, FilterTileMode.MIRROR,
                SamplingMode.LINEAR, matrix);
    }

    private Shader makeAdjustedShader(Image image) {
        if (adjustEffect == null) adjustEffect = RuntimeEffect.makeForShader(ADJUST_COVER_SKSL);
        Shader source = makeSourceShader(image);
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(adjustEffect)) {
            builder.setChild("source", source);
            return builder.makeShader();
        } finally {
            source.close();
        }
    }

    private static final class ProcessedCover {
        final Image raw;
        final Image adjusted;

        ProcessedCover(Image raw, Image adjusted) {
            this.raw = raw;
            this.adjusted = adjusted;
        }
    }

    /** QPlayer's 32px working texture plus the AMLL contrast/saturation chain. */
    private static ProcessedCover preprocessCover(Image source) {
        if (source == null) return null;
        try {
            ImageInfo info = new ImageInfo(TEX_SIZE, TEX_SIZE,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            Bitmap thumb = new Bitmap();
            if (!thumb.allocPixels(info)) {
                thumb.close();
                return null;
            }
            Canvas canvas = new Canvas(thumb);
            canvas.drawImageRect(source,
                    Rect.makeWH(source.getWidth(), source.getHeight()),
                    Rect.makeWH(TEX_SIZE, TEX_SIZE), SamplingMode.LINEAR, null, true);
            byte[] pixels = thumb.readPixels();
            thumb.close();
            if (pixels == null || pixels.length < TEX_SIZE * TEX_SIZE * 4) return null;

            float[] rawRgb = new float[TEX_SIZE * TEX_SIZE * 3];
            int[] argb = new int[TEX_SIZE * TEX_SIZE];
            for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) {
                int r = pixels[i * 4] & 0xFF;
                int g = pixels[i * 4 + 1] & 0xFF;
                int b = pixels[i * 4 + 2] & 0xFF;
                int a = pixels[i * 4 + 3] & 0xFF;
                rawRgb[i * 3] = r;
                rawRgb[i * 3 + 1] = g;
                rawRgb[i * 3 + 2] = b;
                argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            // At 32px, four radius-2 passes approximate QPlayer/Pixi's
            // 5/10/20/40/80px full-stage blur chain.
            boxBlur(rawRgb, TEX_SIZE, TEX_SIZE, 2, 4);
            Image rawTexture = imageFromRgb(rawRgb);

            // QPlayer's AMLL mesh/classic input chain:
            // contrast .4 -> saturation 3 -> contrast 1.7 -> brightness .75 -> blur.
            float[] adjustedRgb = amllAdjust(argb);
            boxBlur(adjustedRgb, TEX_SIZE, TEX_SIZE, 2, 4);
            Image adjustedTexture = imageFromRgb(adjustedRgb);
            return new ProcessedCover(rawTexture, adjustedTexture);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Image imageFromRgb(float[] rgb) {
        byte[] pixels = new byte[TEX_SIZE * TEX_SIZE * 4];
        for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) {
            pixels[i * 4] = (byte) clampU8(rgb[i * 3]);
            pixels[i * 4 + 1] = (byte) clampU8(rgb[i * 3 + 1]);
            pixels[i * 4 + 2] = (byte) clampU8(rgb[i * 3 + 2]);
            pixels[i * 4 + 3] = (byte) 0xFF;
        }
        ImageInfo info = new ImageInfo(TEX_SIZE, TEX_SIZE,
                ColorType.RGBA_8888, ColorAlphaType.OPAQUE);
        return Image.makeRasterFromBytes(info, pixels, TEX_SIZE * 4L);
    }

    private static float[] amllAdjust(int[] argb) {
        float[] out = new float[argb.length * 3];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            float r = (p >> 16) & 0xFF;
            float g = (p >> 8) & 0xFF;
            float b = p & 0xFF;

            r = (r - 128f) * .4f + 128f;
            g = (g - 128f) * .4f + 128f;
            b = (b - 128f) * .4f + 128f;

            float gray = r * .3f + g * .59f + b * .11f;
            r = gray * -2f + r * 3f;
            g = gray * -2f + g * 3f;
            b = gray * -2f + b * 3f;

            r = ((r - 128f) * 1.7f + 128f) * .75f;
            g = ((g - 128f) * 1.7f + 128f) * .75f;
            b = ((b - 128f) * 1.7f + 128f) * .75f;

            out[i * 3] = r;
            out[i * 3 + 1] = g;
            out[i * 3 + 2] = b;
        }
        return out;
    }

    private static void boxBlur(float[] rgb, int width, int height, int radius, int passes) {
        float[] temp = new float[rgb.length];
        for (int pass = 0; pass < passes; pass++) {
            boxBlurHorizontal(rgb, temp, width, height, radius);
            boxBlurVertical(temp, rgb, width, height, radius);
        }
    }

    private static void boxBlurHorizontal(float[] source, float[] destination,
                                          int width, int height, int radius) {
        float divisor = 2f * radius + 1f;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float r = 0f, g = 0f, b = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int sampleX = Math.max(0, Math.min(width - 1, x + k));
                    int index = (y * width + sampleX) * 3;
                    r += source[index];
                    g += source[index + 1];
                    b += source[index + 2];
                }
                int output = (y * width + x) * 3;
                destination[output] = r / divisor;
                destination[output + 1] = g / divisor;
                destination[output + 2] = b / divisor;
            }
        }
    }

    private static void boxBlurVertical(float[] source, float[] destination,
                                        int width, int height, int radius) {
        float divisor = 2f * radius + 1f;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                float r = 0f, g = 0f, b = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int sampleY = Math.max(0, Math.min(height - 1, y + k));
                    int index = (sampleY * width + x) * 3;
                    r += source[index];
                    g += source[index + 1];
                    b += source[index + 2];
                }
                int output = (y * width + x) * 3;
                destination[output] = r / divisor;
                destination[output + 1] = g / divisor;
                destination[output + 2] = b / divisor;
            }
        }
    }

    private float fadeProgress(long nowNanos) {
        if (previousShader == null) return 1f;
        float duration = activeStyle == STYLE_MESH_GRADIENT ? .5f : FADE_DUR;
        float progress = (nowNanos - fadeStartNanos) / 1_000_000_000f / duration;
        float releaseAt = activeStyle == STYLE_MESH_GRADIENT ? 1.1f : 1f;
        if (progress >= releaseAt) {
            releasePrevious();
            return 1f;
        }
        if (progress <= 0f) return 0f;
        if (activeStyle == STYLE_MESH_GRADIENT) return progress;
        return .5f - .5f * (float) Math.cos(Math.PI * Math.min(1f, progress));
    }

    private void drawFluid(Canvas canvas, float width, float height,
                           float time, float fade, float audio) {
        if (activeStyle == STYLE_MESH_GRADIENT) {
            drawMeshGradient(canvas, width, height, time, fade, audio);
            return;
        }
        if (activeStyle == STYLE_NONE) {
            // 「无」底层：只铺兜底深色（静态缓存与动态路径共用这一段）。
            try (Paint basePaint = new Paint().setColor(0xFF0A0A0E)) {
                basePaint.setAlphaf(fluidPaint.getAlphaf());
                canvas.drawRect(fullRect, basePaint);
            }
            return;
        }
        if (activeStyle >= STYLE_LATENT) {
            drawFolia(canvas, width, height, time, audio);
            return;
        }

        boolean fading = previousShader != null && fade < 1f;
        Shader currentChild = activeStyle == STYLE_PIXI_RENDERER
                ? currentShader : currentAdjustedShader;
        Shader previousChild = activeStyle == STYLE_PIXI_RENDERER
                ? previousShader : previousAdjustedShader;
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(makeEffect(activeStyle, fading))) {
            builder.setUniform("resolution", width, height);
            builder.setUniform("time", time);
            builder.setUniform("audio", audio);
            builder.setChild("cover", currentChild);
            if (activeStyle == STYLE_PIXI_RENDERER) builder.setUniform("bend", bendDirection);
            if (activeStyle != STYLE_CLASSIC) builder.setUniform("angles", currentAngles);
            if (fading) {
                builder.setChild("coverPrev", previousChild);
                builder.setUniform("fade", fade);
                if (activeStyle != STYLE_CLASSIC) builder.setUniform("anglesPrev", previousAngles);
            }
            shaded = builder.makeShader();
            fluidPaint.setShader(shaded);
            canvas.drawRect(fullRect, fluidPaint);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    private void drawMeshGradient(Canvas canvas, float width, float height,
                                  float time, float fade, float audio) {
        // QPlayer keeps the previous mesh underneath until fade progress reaches 1.1.
        boolean fading = previousAdjustedShader != null && previousMesh != null;
        if (fading) drawMeshState(canvas, previousMesh, previousAdjustedShader,
                width, height, time, audio, 1f);
        drawMeshState(canvas, currentMesh, currentAdjustedShader,
                width, height, time, audio, fading ? fade : 1f);
    }

    private void drawMeshState(Canvas canvas, AMLLMeshGradient.Data mesh,
                               Shader cover, float width, float height,
                               float time, float audio, float alpha) {
        if (mesh == null || cover == null || alpha <= 0f) return;
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(
                makeEffect(STYLE_MESH_GRADIENT, false))) {
            builder.setUniform("time", time);
            builder.setUniform("audio", audio);
            builder.setUniform("alpha", alpha);
            builder.setChild("cover", cover);
            shaded = builder.makeShader();
            fluidPaint.setShader(shaded);
            Canvas._nDrawVertices(canvas._ptr, 0, mesh.points(width, height), null,
                    mesh.textureCoordinates, mesh.indices, BlendMode.SRC_OVER.ordinal(), fluidPaint._ptr);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    /**
     * The ported 本项目 styles: one full-screen SkSL each, cover child where
     * the style is image-driven, plus the split audio bands and Sora's theme tint.
     *
     * <p>These styles keep their own per-track continuity (their uniforms are
     * stateless in time), so a track change rides the same draw path without the
     * QPlayer cross-fade chain — the fade for them is the lyric host's own alpha.</p>
     */
    private void drawFolia(Canvas canvas, float width, float height,
                           float time, float audio) {
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(
                makeEffect(activeStyle, false))) {
            builder.setUniform("resolution", width, height);
            builder.setUniform("time", time);
            builder.setUniform("audio", audio);
            // Latent/Nomand read the split bands; Sora folds everything into the
            // host-integrated clock. Uniforms a shader doesn't declare are left
            // unset rather than pushed to a name it doesn't know.
            if (activeStyle != STYLE_SORA) {
                builder.setUniform("audioLow", foliaAudioLow);
                builder.setUniform("audioMid", foliaAudioMid);
            }
            Shader child = foliaShader != null ? foliaShader : currentAdjustedShader;
            if (activeStyle != STYLE_SORA && child != null) builder.setChild("cover", child);
            if (activeStyle == STYLE_SORA) {
                float[] tint = foliaTint != null && foliaTint.length >= 6
                        ? foliaTint : FOLIA_FALLBACK_TINT;
                builder.setUniform("particleColor", tint[0], tint[1], tint[2]);
                builder.setUniform("particleAccent", tint[3], tint[4], tint[5]);
            }
            // Nomand 的特效选择与层叠路径共用一张表：拆层底层 10/11 钉 0/1，
            // 融合形态按曲目种子。漏推会让「半调网点」画出抖动（review #1）。
            pushNomandEffect(builder, activeStyle);
            // 层化 uniform：融合经典形态（含这里可达的拆层底层与「无」兜底）各自
            // 的语义——Latent 整体=2（融合），网格半=0；Nomand/Sora 经典=0。
            // 层不透明度=1：整页淡入淡出仍由 fluidPaint 承担。
            setLayerUniforms(builder, activeStyle, 1f);
            shaded = builder.makeShader();
            fluidPaint.setShader(shaded);
            canvas.drawRect(fullRect, fluidPaint);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    /**
     * 按 shader 语义推 layer/alpha 两个层化 uniform。
     *
     * <p>集中在一个方法里是因为这两个名字在三个 shader 里的语义互有差异
     * （latent 的 layer 是「画哪一半」，nomand 的 effect 才选特效、layer 只
     * 分经典/叠加，sora 的 layer 分黑底/透明底）——分散写极易漂移。</p>
     */
    private static void setLayerUniforms(RuntimeEffectBuilder builder, int style,
                                         float layerAlpha) {
        if (style == STYLE_LATENT) builder.setUniform("layer", 2f);
        else if (style == STYLE_LATENT_MESH) builder.setUniform("layer", 0f);
        else if (style == STYLE_LATENT_DITHER_LAYER) builder.setUniform("layer", 1f);
        else if (style == STYLE_SORA_LAYER) builder.setUniform("layer", 1f);
        else builder.setUniform("layer", 0f);
        builder.setUniform("alpha", layerAlpha);
    }

    /**
     * Nomand 的 effect uniform 唯一入口：拆层底层 10/11 钉死抖动/半调，
     * 融合形态按曲目种子选。drawFolia 与 drawOneLayer 都走这里，保证
     * 非层叠与层叠两条路径的特效选择永不漂移。
     */
    private void pushNomandEffect(RuntimeEffectBuilder builder, int style) {
        if (style == STYLE_NOMAND_DITHER) builder.setUniform("effect", 0f);
        else if (style == STYLE_NOMAND_HALFTONE) builder.setUniform("effect", 1f);
        else if (style == STYLE_NOMAND) builder.setUniform("effect", activeNomandEffect());
    }

    /** Sora tint 的逐位比较；null 与 null 相等，长度不同视为变化。 */
    private static boolean tintEquals(float[] a, float[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Float.floatToIntBits(a[i]) != Float.floatToIntBits(b[i])) return false;
        }
        return true;
    }

    /**
     * Nomand's two effects in one shader, picked per track: the dithering
     * portrait for even track seeds, the halftone-dot one for odd — stable for a
     * given song, varied across the library, and no extra setting to explain.
     */
    private float activeNomandEffect() {
        if (currentKey == null) return 0f;
        return (seedFor(currentKey) & 1L) == 0L ? 0f : 1f;
    }

    // ==================== 层叠组合（对齐 本项目的多层合成） ====================
    //
    // folia 原版的背景是单槽多层：LatentBackground = mesh gradient（不透明）→
    // dithering（mixBlendMode: soft-light + tuning.ditheringOpacity）→ 底色遮罩；
    // SoraBackground 的星点是预乘 alpha additive。这里把「底层（单选）+ 叠加层
    // （多选，各自不透明度）」落成 Skija 的逐层 drawRect：每层一个 shader，
    // 混合模式与不透明度走 Paint——正是 folia 里 CSS mix-blend-mode + opacity
    // 的对应物。位定义与 HudConfig.fullscreenLyricBackgroundLayers 一致。

    /** 叠加层位：Latent 抖动（folia LatentBackground 的第二层，soft-light）。 */
    public static final int LAYER_BIT_LATENT_DITHER = 1;
    /** 叠加层位：Sora 星空（folia SoraBackground，加色星点）。 */
    public static final int LAYER_BIT_SORA = 2;

    /** 底层风格：无——只画主题深色底，把整幅画面交给叠加层（星空 + 抖动的组合）。 */
    public static final int STYLE_NONE = 6;
    /** 底层风格：Latent 网格半（shader layer=0），供「隐现整体」拆层后当底层。 */
    public static final int STYLE_LATENT_MESH = 7;
    /** 叠加层代号：Latent 抖动半（shader layer=1，透明底）。 */
    public static final int STYLE_LATENT_DITHER_LAYER = 8;
    /** 叠加层代号：Sora 星空（shader layer=1，预乘透明）。 */
    public static final int STYLE_SORA_LAYER = 9;
    /** 底层风格：Nomand 抖动特效（shader effect=0 的拆层形态）。 */
    public static final int STYLE_NOMAND_DITHER = 10;
    /** 底层风格：Nomand 半调网点特效（shader effect=1 的拆层形态）。 */
    public static final int STYLE_NOMAND_HALFTONE = 11;

    private static final int BASE_STYLE_COUNT = 12;
    /** 叠加层各自的不透明度（0..1，由宿主在渲染前写入；顺序对应位掩码 bit0/bit1）。 */
    private float layerLatentDitherAlpha = .55f;
    private float layerSoraAlpha = .8f;
    private Paint stackPaint;

    private static int normalizeBaseStyle(int style) {
        return style >= STYLE_PIXI_RENDERER && style < BASE_STYLE_COUNT
                ? style : STYLE_PIXI_RENDERER;
    }

    /** 是否有任何叠加层开启。由宿主（QPlayerFluidLyricBackground）每帧同步。 */
    public void setLayerStack(int layerBits, float latentDitherAlpha, float soraAlpha) {
        int bits = Math.max(0, Math.min(3, layerBits));
        if (bits != this.layerBits) {
            this.layerBits = bits;
            invalidateStatic();
        }
        float dither = clamp01(latentDitherAlpha);
        float sora = clamp01(soraAlpha);
        if (dither != this.layerLatentDitherAlpha || sora != this.layerSoraAlpha) {
            this.layerLatentDitherAlpha = dither;
            this.layerSoraAlpha = sora;
            // 不透明度进的是 shader uniform（层内 alpha），静态缓存帧必须重烘。
            invalidateStatic();
        }
    }

    private int layerBits;

    private boolean layerStackActive() {
        return layerBits != 0;
    }

    /**
     * 层叠绘制：底层（单选）→ Latent 抖动（soft-light）→ Sora 星空（加色）。
     *
     * <p>层序与 folia 的 LatentBackground 一致（mesh → dithering soft-light →
     * 遮罩），Sora 在其后加入是为了让星点像原版那样浮在合成结果上而不是被
     * soft-light 吞掉。任何一层抛错只丢那一层，整页不黑（对齐 renderInternal
     * 的兜底哲学，但更细粒度）。</p>
     */
    private void drawLayerStack(Canvas canvas, float width, float height,
                                float time, float audio) {
        int base = normalizeBaseStyle(activeStyle);
        // 融合形态的底层在层叠模式下自动换成拆分层，避免同一效果画两遍：
        // Latent 整体→Latent 网格；Sora 整体→无（星空由叠加层承担）；
        // Nomand 整体→按曲目种子的对应特效层。
        if (base == STYLE_LATENT) base = STYLE_LATENT_MESH;
        else if (base == STYLE_SORA) base = STYLE_NONE;
        else if (base == STYLE_NOMAND) {
            base = activeNomandEffect() < .5f ? STYLE_NOMAND_DITHER : STYLE_NOMAND_HALFTONE;
        }

        if (stackPaint == null) stackPaint = new Paint();

        // ---- 底层 ----
        if (base == STYLE_NONE) {
            // folia 的 theme.backgroundColor 对应这里的深色兜底（与
            // renderFallback/模糊封面遮罩同款色），让星空/抖动有东西可叠。
            try (Paint basePaint = new Paint().setColor(0xFF0A0A0E)) {
                basePaint.setAlphaf(fluidPaint.getAlphaf());
                canvas.drawRect(fullRect, basePaint);
            }
        } else if (base <= STYLE_CLASSIC) {
            // QPlayer 三算法有各自的组装契约（pixi 的 bend/angles 与原图 child、
            // AMLL 网格的顶点 UV 路径、classic 的 adjusted child），drawOneLayer
            // 的通用组装覆盖不了——底层直接复用 drawFluid 的原生路径（fade 在
            // 层叠模式下无关紧要：切歌淡化只属于 QPlayer 单风格形态）。fade 恒 1
            // 意味着 fadeProgress 不会跑，切歌后的上一份网格会整曲滞留且每帧
            // 白画一遍——层叠模式下先把 previous 释放掉（淡化本来就不参与）。
            releasePrevious();
            try {
                drawFluid(canvas, width, height, time, 1f, audio);
            } catch (Throwable ignored) {
                // 底层失败时至少把底色铺上，叠加层还有得叠。
                try (Paint basePaint = new Paint().setColor(0xFF0A0A0E)) {
                    basePaint.setAlphaf(fluidPaint.getAlphaf());
                    canvas.drawRect(fullRect, basePaint);
                }
            }
        } else {
            try {
                drawOneLayer(canvas, base, width, height, time, audio, 1f,
                        BlendMode.SRC_OVER);
            } catch (Throwable ignored) {
                // 底层失败时至少把底色铺上，叠加层还有得叠。
                try (Paint basePaint = new Paint().setColor(0xFF0A0A0E)) {
                    basePaint.setAlphaf(fluidPaint.getAlphaf());
                    canvas.drawRect(fullRect, basePaint);
                }
            }
        }

        // ---- Latent 抖动层（soft-light，folia 的 mixBlendMode 对应物）----
        if ((layerBits & LAYER_BIT_LATENT_DITHER) != 0 && layerLatentDitherAlpha > .002f) {
            try {
                drawOneLayer(canvas, STYLE_LATENT_DITHER_LAYER, width, height, time, audio,
                        layerLatentDitherAlpha, BlendMode.SOFT_LIGHT);
            } catch (Throwable ignored) {
                // 单层失败不拖累整体。
            }
        }

        // ---- Sora 星空层（加色，folia 星点的预乘 additive 对应物）----
        if ((layerBits & LAYER_BIT_SORA) != 0 && layerSoraAlpha > .002f) {
            try {
                drawOneLayer(canvas, STYLE_SORA_LAYER, width, height, time, audio,
                        layerSoraAlpha, BlendMode.PLUS);
            } catch (Throwable ignored) {
                // 同上。
            }
        }
    }

    private void drawOneLayer(Canvas canvas, int style, float width, float height,
                              float time, float audio, float layerAlpha, BlendMode blend) {
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(makeEffect(style, false))) {
            builder.setUniform("resolution", width, height);
            builder.setUniform("time", time);
            builder.setUniform("audio", audio);
            // Latent/Nomand 读分段频段；Sora 全部折进宿主积分过的 time。
            if (style != STYLE_SORA && style != STYLE_SORA_LAYER) {
                builder.setUniform("audioLow", foliaAudioLow);
                builder.setUniform("audioMid", foliaAudioMid);
            }
            Shader child = foliaShader != null ? foliaShader : currentAdjustedShader;
            if (style != STYLE_SORA && style != STYLE_SORA_LAYER && child != null) {
                builder.setChild("cover", child);
            }
            if (style == STYLE_SORA_LAYER) {
                float[] tint = foliaTint != null && foliaTint.length >= 6
                        ? foliaTint : FOLIA_FALLBACK_TINT;
                builder.setUniform("particleColor", tint[0], tint[1], tint[2]);
                builder.setUniform("particleAccent", tint[3], tint[4], tint[5]);
            }
            // 层化 uniform 走统一的语义表（见 setLayerUniforms）；Nomand 特效
            // 同样走唯一入口，与 drawFolia 的非栈路径保持一致。
            setLayerUniforms(builder, style, layerAlpha);
            pushNomandEffect(builder, style);

            shaded = builder.makeShader();
            stackPaint.setShader(shaded);
            // alpha 只乘一次：slider 走 shader 的 alpha uniform，paint 只承担
            // 整页淡入淡出（fluidPaint 的 alphaf）。两处都乘会出现 slider²
            // （review 探针 nomandLayerAlphaDoubleMultProbe 抓到的正是这个）。
            stackPaint.setAlphaf(clamp01(fluidPaint.getAlphaf()));
            stackPaint.setBlendMode(blend);
            canvas.drawRect(fullRect, stackPaint);
        } finally {
            stackPaint.setShader(null);
            stackPaint.setBlendMode(BlendMode.SRC_OVER);
            if (shaded != null) shaded.close();
        }
    }

    private void buildStatic(DirectContext context, int width, int height,
                             float time, float audio) {
        invalidateStatic();
        try (Surface offscreen = Surface.makeRenderTarget(context, false,
                ImageInfo.makeN32Premul(width, height))) {
            Rect oldRect = fullRect;
            fullRect = Rect.makeWH(width, height);
            float oldAlpha = fluidPaint.getAlphaf();
            fluidPaint.setAlphaf(1f);
            try {
                // 层叠模式烘整帧（底 + 叠加层），非层叠走单风格路径——静态帧
                // 的内容必须与动态帧逐像素一致，否则省电模式会「丢层」。
                if (layerStackActive()) {
                    drawLayerStack(offscreen.getCanvas(), width, height, time, audio);
                } else {
                    drawFluid(offscreen.getCanvas(), width, height, time, 1f, audio);
                }
            } finally {
                fluidPaint.setAlphaf(oldAlpha);
                fullRect = oldRect;
            }
            staticImage = offscreen.makeImageSnapshot();
            staticW = width;
            staticH = height;
        } catch (Throwable ignored) {
            invalidateStatic();
        }
    }

    private void invalidateStatic() {
        if (staticImage != null) {
            staticImage.close();
            staticImage = null;
        }
        staticW = -1;
        staticH = -1;
    }

    /** Drop every resource that belongs to the old GL/Skia context. */
    public void invalidateGpuContext() {
        releasePrevious();
        closeCurrentShaders();
        releaseFoliaTexture();
        if (currentRawTexture != null) {
            currentRawTexture.close();
            currentRawTexture = null;
        }
        if (currentAdjustedTexture != null) {
            currentAdjustedTexture.close();
            currentAdjustedTexture = null;
        }
        invalidateStatic();
        // The borrowed Minecraft image is also context-bound. It is reacquired by
        // QPlayerFluidLyricBackground on the first frame after the context returns.
        currentImage = null;
        currentKey = null;
        currentMesh = null;
        fadeStartNanos = 0L;
    }

    /** Clear the cover/transition state when the lyric page is recreated. */
    public void reset() {
        releasePrevious();
        closeCurrentShaders();
        releaseFoliaTexture();
        if (currentRawTexture != null) currentRawTexture.close();
        if (currentAdjustedTexture != null) currentAdjustedTexture.close();
        currentShader = null;
        currentAdjustedShader = null;
        currentRawTexture = null;
        currentAdjustedTexture = null;
        currentImage = null;
        currentKey = null;
        currentMesh = null;
        fadeStartNanos = 0L;
        invalidateStatic();
    }

    public void dispose() {
        reset();
        if (saturationFilter != null) {
            saturationFilter.close();
            saturationFilter = null;
        }
        if (stackPaint != null) {
            stackPaint.close();
            stackPaint = null;
        }
        fluidPaint.close();
    }

    private void closeCurrentShaders() {
        if (currentShader != null) {
            currentShader.close();
            currentShader = null;
        }
        if (currentAdjustedShader != null) {
            currentAdjustedShader.close();
            currentAdjustedShader = null;
        }
    }

    private void releasePrevious() {
        if (previousShader != null) {
            previousShader.close();
            previousShader = null;
        }
        if (previousAdjustedShader != null) {
            previousAdjustedShader.close();
            previousAdjustedShader = null;
        }
        if (previousRawTexture != null) {
            previousRawTexture.close();
            previousRawTexture = null;
        }
        if (previousAdjustedTexture != null) {
            previousAdjustedTexture.close();
            previousAdjustedTexture = null;
        }
        previousAngles = null;
        previousMesh = null;
    }

    private static float[] generateAngles(String key) {
        Random random = new Random(seedFor(key));
        float[] angles = new float[4];
        for (int i = 0; i < angles.length; i++) {
            angles[i] = random.nextFloat() * (float) (Math.PI * 2.0);
        }
        return angles;
    }

    private static long seedFor(String key) {
        long seed = 0x9E3779B97F4A7C15L;
        if (key != null) {
            for (int i = 0; i < key.length(); i++) {
                seed ^= key.charAt(i);
                seed *= 0x100000001B3L;
                seed ^= seed >>> 32;
            }
        }
        return seed;
    }

    private static ColorMatrix saturationMatrix(float sat) {
        float inverse = 1f - sat;
        float r = .213f * inverse;
        float g = .715f * inverse;
        float b = .072f * inverse;
        return new ColorMatrix(
                r + sat, g, b, 0f, 0f,
                r, g + sat, b, 0f, 0f,
                r, g, b + sat, 0f, 0f,
                0f, 0f, 0f, 1f, 0f);
    }

    private static int clampU8(float value) {
        return value <= 0f ? 0 : value >= 255f ? 255 : (int) (value + .5f);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }

    private static void renderFallback(Canvas canvas, float width, float height) {
        try (Paint fallback = new Paint().setColor(0xFF0A0A0E)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, width, height), fallback);
        }
    }
}


