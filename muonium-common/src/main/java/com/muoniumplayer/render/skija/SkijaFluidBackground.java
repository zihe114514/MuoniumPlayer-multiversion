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

    private static final int TEX_SIZE = 32;
    private static final float FADE_DUR = 0.6f;
    private static final String SHADER_ROOT = "/shaders/fluid/";
    private static final String[][] SHADER_FILES = {
            {"pixi_renderer.sksl", "pixi_renderer_fade.sksl"},
            {"meshgradient.sksl", "meshgradient.sksl"},
            {"classic.sksl", "classic_fade.sksl"}
    };
    private static final RuntimeEffect[][] EFFECTS = new RuntimeEffect[3][2];

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

    private static RuntimeEffect makeEffect(int style, boolean fade) {
        int safeStyle = normalizeStyle(style);
        int variant = fade ? 1 : 0;
        RuntimeEffect cached = EFFECTS[safeStyle][variant];
        if (cached != null) return cached;
        String file = SHADER_FILES[safeStyle][variant];
        cached = compileResource(SHADER_ROOT + file, file);
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
        return style >= STYLE_PIXI_RENDERER && style <= STYLE_CLASSIC
                ? style : STYLE_PIXI_RENDERER;
    }

    private final float bendDirection;
    private final long startNanos;
    private final Paint fluidPaint = new Paint();

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
                nowNanos, staticMode, style, saturationPct, alpha, audioDrive, Float.NaN);
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
                animationTimeSeconds);
    }

    /** Compatibility entry used by the raster shader tests; the supplied time is explicit. */
    public boolean render(Canvas canvas, float width, float height, Image cover,
                          String trackKey, float timeSeconds, long nowNanos,
                          int style, int saturationPct, float alpha, float audioDrive) {
        return renderInternal(canvas, null, 1f, width, height, cover, trackKey,
                nowNanos, false, style, saturationPct, alpha, audioDrive, timeSeconds);
    }

    private boolean renderInternal(Canvas canvas, DirectContext context, float uiScale,
                                   float width, float height, Image cover,
                                   String trackKey, long nowNanos, boolean staticMode,
                                   int style, int saturationPct, float alpha,
                                   float audioDrive, float explicitTime) {
        if (canvas == null || width <= .5f || height <= .5f || cover == null
                || alpha <= .001f) return false;

        int selectedStyle = normalizeStyle(style);
        if (selectedStyle != activeStyle) {
            activeStyle = selectedStyle;
            invalidateStatic();
        }
        if (staticMode != previousStaticMode) {
            previousStaticMode = staticMode;
            invalidateStatic();
        }
        setSaturation(saturationPct);
        fluidPaint.setAlphaf(clamp01(alpha));
        publishCover(cover, trackKey, nowNanos);
        if (currentShader == null) return false;

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
            drawFluid(canvas, width, height, time, fadeProgress(nowNanos), audio);
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
        fadeStartNanos = nowNanos;
        invalidateStatic();
    }

    private Shader makeProcessedShader(Image image) {
        return image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                SamplingMode.LINEAR, Matrix33.IDENTITY);
    }

    private Shader makeSourceShader(Image image) {
        int imageWidth = Math.max(1, image.getImageInfo().getWidth());
        int imageHeight = Math.max(1, image.getImageInfo().getHeight());
        Matrix33 matrix = new Matrix33(
                imageWidth / (float) TEX_SIZE, 0f, 0f,
                0f, imageHeight / (float) TEX_SIZE, 0f,
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
                drawFluid(offscreen.getCanvas(), width, height, time, 1f, audio);
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


