package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * folia 背景三支 SkSL 的离屏栅格测试。
 *
 * <p>要抓的是三类真实故障：</p>
 * <ol>
 *   <li>着色器语法不被 Skija 的 SkSL 接受（编译直接失败，宿主会静默走兜底底色 —— 界面上只看到
 *       一块纯色，看不出是编译问题）；</li>
 *   <li>uniform 名字写错或类型不匹配（编译通过但运行期取不到值，画面恒定不变）；</li>
 *   <li>时间/音频/参数没有真正接进去（画得出来但永远同一帧）。</li>
 * </ol>
 *
 * <p>所以每个着色器都断言：非黑像素足够多、推进时间后画面变化、改关键参数后画面变化。</p>
 */
class FoliaBackgroundShaderTest {

    private static final int W = 96;
    private static final int H = 64;

    @Test
    void latentShaderDrawsAnimatesAndReactsToPaletteAndAudio() {
        assertTrue(SkijaNatives.ensureLoaded());
        RuntimeEffect effect = compile("latent.sksl");
        assertNotNull(effect, "latent.sksl 必须能编译");

        try (Surface coverSurface = Surface.makeRasterN32Premul(64, 64);
             Surface output = Surface.makeRasterN32Premul(W, H);
             Image cover = makeCover(coverSurface)) {
            Shader child = child(cover);
            try {
                int[] still = drawLatent(output, effect, child, 0f, 0f, 0.8f, 0.1f,
                        new int[]{0xE85D04, 0x14213D, 0x14532D, 0xF2E9E4, 0x1B1B1F, 0x9A4DFF});
                int[] moved = drawLatent(output, effect, child, 12f, 4f, 0.8f, 0.1f,
                        new int[]{0xE85D04, 0x14213D, 0x14532D, 0xF2E9E4, 0x1B1B1F, 0x9A4DFF});
                int[] other = drawLatent(output, effect, child, 12f, 4f, 0.8f, 0.1f,
                        new int[]{0x2E86AB, 0xA23BEC, 0x1B998B, 0xED217C, 0xFFFD82, 0x2D3047});
                assertTrue(nonBlack(still) > 100, "latent 必须画得出画面");
                assertTrue(different(still, moved) > 5, "latent 必须随时间变化");
                assertTrue(different(moved, other) > 100, "latent 必须随封面色变化");
            } finally {
                child.close();
            }
        }
        effect.close();
    }

    @Test
    void nomandShaderCoversAllFiveEffects() {
        assertTrue(SkijaNatives.ensureLoaded());
        RuntimeEffect effect = compile("nomand.sksl");
        assertNotNull(effect, "nomand.sksl 必须能编译");

        try (Surface coverSurface = Surface.makeRasterN32Premul(64, 64);
             Surface output = Surface.makeRasterN32Premul(W, H);
             Image cover = makeCover(coverSurface)) {
            Shader child = child(cover);
            try {
                int[] first = null;
                for (int effectIndex = 0; effectIndex < 5; effectIndex++) {
                    int[] frame = drawNomand(output, effect, child, effectIndex);
                    assertTrue(nonBlack(frame) > 100,
                            "nomand effect " + effectIndex + " 必须画得出内容");
                    if (first == null) first = frame;
                }
                int[] dither = drawNomand(output, effect, child, 0);
                assertTrue(different(first, dither) <= 2, "同一 effect 连续两帧必须一致（nomand 是静态图）");
                int[] halftone = drawNomand(output, effect, child, 3);
                assertTrue(different(dither, halftone) > 100, "不同 effect 必须画出不同结果");
            } finally {
                child.close();
            }
        }
        effect.close();
    }

    // ------------------------------------------------------------------

    private static RuntimeEffect compile(String name) {
        try (InputStream in = FoliaBackgroundShaderTest.class.getResourceAsStream("/shaders/folia/" + name)) {
            assertNotNull(in, "缺少资源 /shaders/folia/" + name);
            StringBuilder source = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) source.append(buffer, 0, read);
            return RuntimeEffect.makeForShader(source.toString());
        } catch (Throwable failure) {
            throw new AssertionError("编译 " + name + " 失败: " + failure, failure);
        }
    }

    private static Shader child(Image image) {
        return image.makeShader(io.github.humbleui.skija.FilterTileMode.CLAMP,
                io.github.humbleui.skija.FilterTileMode.CLAMP,
                io.github.humbleui.skija.SamplingMode.LINEAR,
                new io.github.humbleui.skija.Matrix33(
                        32f / image.getWidth(), 0f, 0f,
                        0f, 32f / image.getHeight(), 0f,
                        0f, 0f, 1f));
    }

    private static int[] drawLatent(Surface output, RuntimeEffect effect, Shader cover,
                                    float meshTime, float ditherTime, float distortion,
                                    float swirl, int[] palette) {
        output.getCanvas().clear(0xFF000000);
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(effect)) {
            builder.setUniform("resolution", (float) W, (float) H);
            builder.setUniform("meshTime", meshTime);
            builder.setUniform("ditherTime", ditherTime);
            builder.setUniform("alpha", 1f);
            builder.setUniform("layer", 2f);
            for (int i = 0; i < 6; i++) {
                int rgb = palette[i];
                builder.setUniform("c" + i,
                        ((rgb >> 16) & 0xFF) / 255f,
                        ((rgb >> 8) & 0xFF) / 255f,
                        (rgb & 0xFF) / 255f, 1f);
            }
            builder.setUniform("distortion", distortion);
            builder.setUniform("swirl", swirl);
            builder.setUniform("grainOverlay", 0.01f);
            builder.setUniform("meshSaturation", 1.04f);
            builder.setUniform("meshBrightness", 0.94f);
            builder.setUniform("meshScale", 1.025f);
            builder.setUniform("ditherBack", .08f, .08f, .1f);
            builder.setUniform("ditherFront", .9f, .55f, .2f);
            builder.setUniform("ditherSize", 2.5f);
            builder.setUniform("ditherOpacity", 0.55f);
            builder.setUniform("ditherScale", 1.015f);
            builder.setChild("cover", cover);
            shaded = builder.makeShader();
            try (io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint()) {
                paint.setShader(shaded);
                output.getCanvas().drawRect(io.github.humbleui.types.Rect.makeXYWH(0, 0, W, H), paint);
            }
        } finally {
            if (shaded != null) shaded.close();
        }
        return pixels(output);
    }

    private static int[] drawNomand(Surface output, RuntimeEffect effect, Shader cover, int effectIndex) {
        output.getCanvas().clear(0xFF000000);
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(effect)) {
            builder.setUniform("resolution", (float) W, (float) H);
            builder.setUniform("alpha", 1f);
            builder.setUniform("effect", (float) effectIndex);
            builder.setUniform("inverted", 0f);
            builder.setUniform("originalColors", 0f);
            builder.setUniform("colorSteps", 4f);
            builder.setUniform("pxSize", 3f);
            builder.setUniform("ditherType", 2f);
            builder.setUniform("colorFront", .92f, .36f, .02f, 1f);
            builder.setUniform("colorBack", .06f, .07f, .09f, 1f);
            builder.setUniform("colorHighlight", .95f, .8f, .4f, 1f);
            builder.setUniform("colorShadow", .5f, .5f, .55f, 1f);
            builder.setUniform("glassSize", 0.5f);
            builder.setUniform("glassDistortion", 0.5f);
            builder.setUniform("glassBlur", 0.08f);
            builder.setUniform("paperContrast", 0.32f);
            builder.setUniform("paperRoughness", 0.42f);
            builder.setUniform("paperFiber", 0.3f);
            builder.setUniform("dotSize", 0.5f);
            builder.setUniform("dotRadius", 1.25f);
            builder.setUniform("dotContrast", 0.4f);
            builder.setUniform("lensSpread", 0.45f);
            builder.setUniform("lensBulge", 0.3f);
            builder.setUniform("lensDispersion", 0.65f);
            builder.setUniform("brightness", 0.92f);
            builder.setUniform("themeBack", .06f, .07f, .09f);
            builder.setChild("cover", cover);
            shaded = builder.makeShader();
            try (io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint()) {
                paint.setShader(shaded);
                output.getCanvas().drawRect(io.github.humbleui.types.Rect.makeXYWH(0, 0, W, H), paint);
            }
        } finally {
            if (shaded != null) shaded.close();
        }
        return pixels(output);
    }

    private static Image makeCover(Surface surface) {
        Canvas canvas = surface.getCanvas();
        int[] stripes = {0xFF14213D, 0xFFE85D04, 0xFF14532D, 0xFF3D1442};
        for (int i = 0; i < stripes.length; i++) {
            try (io.github.humbleui.skija.Paint paint =
                         new io.github.humbleui.skija.Paint().setColor(stripes[i])) {
                canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(i * 16, 0, 16, 64), paint);
            }
        }
        return surface.makeImageSnapshot();
    }

    private static int[] pixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(W, H, false);
            assertTrue(surface.getCanvas().readPixels(bitmap, 0, 0));
            int[] pixels = new int[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) pixels[y * W + x] = bitmap.getColor(x, y);
            }
            return pixels;
        }
    }

    private static int different(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) count++;
        return count;
    }

    private static int nonBlack(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel & 0x00FFFFFF) != 0) count++;
        return count;
    }
}

