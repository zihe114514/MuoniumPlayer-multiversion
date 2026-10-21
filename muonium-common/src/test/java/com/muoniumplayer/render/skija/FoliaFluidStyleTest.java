package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本项目 移植风格（Latent / Sora / Nomand）的离屏栅格测试。
 *
 * <p>要求每种风格都真的画得出来（大部分像素非黑）、随宿主积分的时间动起来、
 * 对分段频段（Latent/Nomand）与主题色（Sora）有可见响应。shader 编译失败
 * 会让 render 走 fallback 底色路径，nonBlack 断言当场抓住。</p>
 */
class FoliaFluidStyleTest {

    @Test
    void foliaStylesDrawAnimateAndReactToBandsAndTint() {
        assertTrue(SkijaNatives.ensureLoaded());
        int[] styles = {
                SkijaFluidBackground.STYLE_LATENT,
                SkijaFluidBackground.STYLE_SORA,
                SkijaFluidBackground.STYLE_NOMAND,
        };
        try (Surface coverSurface = Surface.makeRasterN32Premul(32, 32);
             Surface output = Surface.makeRasterN32Premul(96, 64);
             Image cover = makeCover(coverSurface)) {
            for (int style : styles) {
                SkijaFluidBackground fluid = new SkijaFluidBackground(4321L + style);
                try {
                    float[] tint = {.92f, .50f, .35f, .95f, .80f, .40f};
                    int[] still = draw(fluid, output, cover, style, 0f, 0f, 0f, 0f, tint);
                    int[] moved = draw(fluid, output, cover, style, 8f, 0f, 0f, 0f, tint);
                    int[] reacting = draw(fluid, output, cover, style, 8f, .8f, .7f, .5f, tint);
                    int[] otherTint = draw(fluid, output, cover, style, 8f, .8f, .7f, .5f,
                            new float[]{.30f, .85f, .90f, .15f, .40f, .95f});
                    // 分段频段的非对称探针：两条支线各自与零频段帧比。对称输入
                    // 检不出 low/mid 互换（宿主接线最易犯的错），也检不出振幅减半。
                    int[] lowOnly = draw(fluid, output, cover, style, 8f, .8f, .9f, 0f, tint);
                    int[] midOnly = draw(fluid, output, cover, style, 8f, .8f, 0f, .9f, tint);
                    assertTrue(nonBlackPixels(still) > 100,
                            "style " + style + " must draw its background (changed="
                                    + differentPixels(still, moved) + ")");
                    int changed = differentPixels(still, moved);
                    assertTrue(changed > 5,
                            "style " + style + " must animate as time advances (changed="
                                    + changed + ")");
                    int lowDiff = differentPixels(moved, lowOnly);
                    int midDiff = differentPixels(moved, midOnly);
                    // Sora 不读分段频段（全部折进宿主积分过的 time），0 差异是正确的。
                    if (style != SkijaFluidBackground.STYLE_SORA) {
                        assertTrue(lowDiff > 100,
                                "style " + style + " must react to the low band alone ("
                                        + lowDiff + ")");
                        assertTrue(midDiff > 100,
                                "style " + style + " must react to the mid band alone ("
                                        + midDiff + ")");
                        assertTrue(differentPixels(lowOnly, midOnly) > 100,
                                "style " + style + " must treat low and mid bands differently");
                    }
                    if (style != SkijaFluidBackground.STYLE_SORA) continue;
                    assertTrue(differentPixels(reacting, otherTint) > 100,
                            "style " + style + " must react to the theme tint");
                } finally {
                    fluid.dispose();
                }
            }
        }
    }

    @Test
    void layerStackCombinesBaseAndOverlayLayers() {
        assertTrue(SkijaNatives.ensureLoaded());
        try (Surface coverSurface = Surface.makeRasterN32Premul(32, 32);
             Surface output = Surface.makeRasterN32Premul(96, 64);
             Image cover = makeCover(coverSurface)) {
            float[] tint = {.92f, .50f, .35f, .95f, .80f, .40f};
            SkijaFluidBackground fluid = new SkijaFluidBackground(99L);
            try {
                // 无底层 + 两层全开：整幅画面来自叠加层，必须自己画出内容。
                fluid.setLayerStack(
                        SkijaFluidBackground.LAYER_BIT_LATENT_DITHER
                                | SkijaFluidBackground.LAYER_BIT_SORA, .8f, .9f);
                int[] noneBase = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_NONE, 4f, .5f, .6f, .4f, tint);
                assertTrue(nonBlackPixels(noneBase) > 100,
                        "none base + overlays must still paint a full picture");

                // Latent 网格底层 + 抖动叠加：与「隐现整体」融合形态不同帧。
                // 注意这只证明叠加层参与了合成（混合模式换成 SRC_OVER 也过）；
                // soft-light 本身由 shader 内单元行为保证，这里不重复钉。
                fluid.setLayerStack(SkijaFluidBackground.LAYER_BIT_LATENT_DITHER, .6f, .0f);
                int[] stacked = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_LATENT_MESH, 4f, .5f, .6f, .4f, tint);
                fluid.setLayerStack(0, .6f, .0f);
                int[] fused = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_LATENT, 4f, .5f, .6f, .4f, tint);
                assertTrue(differentPixels(stacked, fused) > 20,
                        "stacked mesh+dither must differ from the fused classic draw");
                assertTrue(nonBlackPixels(stacked) > 100,
                        "stacked latent draw must keep its content");

                // 非层叠路径（layers=0）下两个 Nomand 拆层底层必须画出不同特效：
                // drawFolia 漏推 effect uniform 时两者会逐像素相同（review #1）。
                int[] ditherBase = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_NOMAND_DITHER, 4f, .5f, .6f, .4f, tint);
                int[] halftoneBase = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_NOMAND_HALFTONE, 4f, .5f, .6f, .4f, tint);
                assertTrue(differentPixels(ditherBase, halftoneBase) > 20,
                        "nomand dither and halftone bases must draw different effects");
                assertTrue(nonBlackPixels(ditherBase) > 100
                                && nonBlackPixels(halftoneBase) > 100,
                        "both nomand split bases must paint content");

                // 叠加层不透明度 0 等价于只画底层。
                fluid.setLayerStack(SkijaFluidBackground.LAYER_BIT_LATENT_DITHER, 0f, 0f);
                int[] zeroAlpha = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_LATENT_MESH, 4f, .5f, .6f, .4f, tint);
                fluid.setLayerStack(0, 0f, 0f);
                int[] baseOnly = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_LATENT_MESH, 4f, .5f, .6f, .4f, tint);
                assertTrue(differentPixels(zeroAlpha, baseOnly) <= 2,
                        "zero-opacity overlays must not change the frame");

                // 星空层叠加在 Nomand 半调底层上：两层内容都可见。
                fluid.setLayerStack(SkijaFluidBackground.LAYER_BIT_SORA, 0f, .9f);
                int[] halftoneStars = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_NOMAND_HALFTONE, 4f, .5f, .6f, .4f, tint);
                assertTrue(nonBlackPixels(halftoneStars) > 100,
                        "halftone base + sora overlay must paint");
                fluid.setLayerStack(0, 0f, 0f);
                int[] halftoneOnly = draw(fluid, output, cover,
                        SkijaFluidBackground.STYLE_NOMAND_HALFTONE, 4f, .5f, .6f, .4f, tint);
                assertTrue(differentPixels(halftoneStars, halftoneOnly) > 2,
                        "sora overlay must visibly add stars over the base");
            } finally {
                fluid.dispose();
            }
        }
    }

    private static int[] draw(SkijaFluidBackground fluid, Surface output, Image cover,
                              int style, float time, float audio, float audioLow,
                              float audioMid, float[] tint) {
        output.getCanvas().clear(0xFF000000);
        assertTrue(fluid.renderWithTime(output.getCanvas(), null, 1f,
                96f, 64f, cover, "folia-" + style, time, System.nanoTime(),
                false, style, 100, 1f, audio, audioLow, audioMid, tint));
        return pixels(output);
    }

    private static Image makeCover(Surface surface) {
        Canvas canvas = surface.getCanvas();
        // Four vertical stripes: the Nomand dither branch warps the sample
        // position by only ~0.012 uv at audio 0, so a flat two-tone cover never
        // crosses a quantization boundary and would look frozen. Stripes put a
        // color edge near most dither cells.
        int[] stripes = {0xFF14213D, 0xFFE85D04, 0xFF14532D, 0xFF3D1442};
        for (int i = 0; i < stripes.length; i++) {
            try (io.github.humbleui.skija.Paint paint =
                         new io.github.humbleui.skija.Paint().setColor(stripes[i])) {
                canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(i * 8, 0, 8, 32), paint);
            }
        }
        return surface.makeImageSnapshot();
    }

    private static int[] pixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(96, 64, false);
            assertTrue(surface.getCanvas().readPixels(bitmap, 0, 0));
            int[] pixels = new int[96 * 64];
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 96; x++) pixels[y * 96 + x] = bitmap.getColor(x, y);
            }
            return pixels;
        }
    }

    private static int differentPixels(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) count++;
        return count;
    }

    private static int nonBlackPixels(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel & 0x00FFFFFF) != 0) count++;
        return count;
    }
}
