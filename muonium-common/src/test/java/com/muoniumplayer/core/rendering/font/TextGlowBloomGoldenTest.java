package com.muoniumplayer.core.rendering.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 晕格 HDR bloom 管线的<b>像素金标准</b>回归。
 *
 * <p>2026-11 的性能轮把 {@link TextGlow#gaussBlur} 改成「行连续 + 分段免 clamp +
 * scatter 纵 pass」，并把各 pass 的临时数组改成一次申请复用。这类改动最容易
 * 悄悄改变像素 —— 光晕是低频大面积的，普通断言看不出差异，用户却能一眼看出
 * 色块/接缝。</p>
 *
 * <p>这里内联一份<b>优化前的实现</b>（逐字照抄旧代码）作为金标准，逐像素比对
 * {@link TextGlow#bakeBloomGlowPixels} 的输出。任何后续优化只要动了像素，
 * 这个测试就会红 —— 那时必须显式决定「这次允许改变观感」，而不是默默漂移。</p>
 */
class TextGlowBloomGoldenTest {

    // ==================== 金标准：优化前的实现（逐字照抄，勿改） ====================

    private static final float THRESH = 0.13f;
    private static final float KNEE = 0.30f;
    private static final float S_PRE = 2.0f;
    private static final float S_M1 = 1.6f;
    private static final float S_M2 = 1.8f;
    private static final float S_M3 = 1.8f;
    private static final float S_UP1 = 0.6f;
    private static final float S_UP2 = 0.7f;
    private static final float S_CORE = 2.2f;
    private static final float GAIN_WIDE = 0.34f;
    private static final float GAIN_OUTER = 0.16f;
    private static final float GAIN_INNER = 0.38f;
    private static final float GAIN_CORE = 0.16f;
    private static final float WHITEN_INNER = 0.06f;
    private static final float WHITEN_CORE = 0.65f;
    private static final float SAT_OUTER = 1.14f;

    /** 旧 gaussBlur（列式纵 pass + 每采样 clamp + 每次 new 临时表）。 */
    private static void oldGaussBlur(float[] a, int w, int h, float sigma) {
        if (sigma <= 0.05f) {
            return;
        }
        int r = Math.max(1, (int) Math.ceil(sigma * 2.5f));
        float[] k = new float[2 * r + 1];
        float s2 = 2f * sigma * sigma;
        float sum = 0;
        for (int i = -r; i <= r; i++) {
            float v = (float) Math.exp(-i * i / s2);
            k[i + r] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        float[] t = new float[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int i = -r; i <= r; i++) {
                    int sx = Math.max(0, Math.min(w - 1, x + i));
                    acc += a[row + sx] * k[i + r];
                }
                t[row + x] = acc;
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float acc = 0;
                for (int i = -r; i <= r; i++) {
                    int sy = Math.max(0, Math.min(h - 1, y + i));
                    acc += t[sy * w + x] * k[i + r];
                }
                a[y * w + x] = Math.max(0f, acc);
            }
        }
    }

    private static float[] oldDown2(float[] s, int w, int h) {
        int dw = w / 2, dh = h / 2;
        float[] d = new float[dw * dh];
        for (int dy = 0; dy < dh; dy++) {
            int y0 = dy * 2, y1 = Math.min(h - 1, dy * 2 + 1);
            for (int dx = 0; dx < dw; dx++) {
                int x0 = dx * 2, x1 = Math.min(w - 1, dx * 2 + 1);
                d[dy * dw + dx] = (s[y0 * w + x0] + s[y0 * w + x1]
                        + s[y1 * w + x0] + s[y1 * w + x1]) * 0.25f;
            }
        }
        return d;
    }

    private static float[] oldUpTo(float[] s, int w, int h, int uw, int uh) {
        float[] d = new float[uw * uh];
        for (int y = 0; y < uh; y++) {
            float fy = (y + 0.5f) * h / (float) uh - 0.5f;
            int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(fy)));
            int y1 = Math.min(h - 1, y0 + 1);
            float ty = Math.max(0f, Math.min(1f, fy - y0));
            for (int x = 0; x < uw; x++) {
                float fx = (x + 0.5f) * w / (float) uw - 0.5f;
                int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(fx)));
                int x1 = Math.min(w - 1, x0 + 1);
                float tx = Math.max(0f, Math.min(1f, fx - x0));
                d[y * uw + x] = (s[y0 * w + x0] * (1 - tx) + s[y0 * w + x1] * tx) * (1 - ty)
                        + (s[y1 * w + x0] * (1 - tx) + s[y1 * w + x1] * tx) * ty;
            }
        }
        return d;
    }

    private static void oldAddInto(float[] d, float[] s) {
        for (int i = 0; i < d.length; i++) {
            d[i] += s[i];
        }
    }

    private static void oldGammaBack(float[] a) {
        for (int i = 0; i < a.length; i++) {
            float v = a[i];
            a[i] = (float) Math.pow(v < 0 ? 0 : v, 1f / 2.2f);
        }
    }

    private static void oldAddLayerTinted(float[] gR, float[] gG, float[] gB, float[] src,
                                          float gain, float lr, float lg, float lb,
                                          float whiten, float satK) {
        if (gain <= 0) {
            return;
        }
        float gray = 0.299f * lr + 0.587f * lg + 0.114f * lb;
        float tr = gray + (lr - gray) * satK;
        float tg = gray + (lg - gray) * satK;
        float tb = gray + (lb - gray) * satK;
        tr += (1f - tr) * whiten;
        tg += (1f - tg) * whiten;
        tb += (1f - tb) * whiten;
        for (int i = 0; i < gR.length; i++) {
            float v = src[i] * gain;
            gR[i] += v * tr;
            gG[i] += v * tg;
            gB[i] += v * tb;
        }
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private static float ing(int x, int y) {
        float v = 52.9829189f * fract(0.06711056f * x + 0.00583715f * y);
        return fract(v) - 0.5f;
    }

    /** 旧版 bakeBloomGlowPixels 全流程（照抄）。 */
    private static int[] oldBakeBloomGlowPixels(float[] mask, int w, int h, float fontPx,
                                                double spread, double strength, int lightRgb) {
        float clamped = clamp01f(strength);
        if (mask == null || w <= 0 || h <= 0 || mask.length < w * h || clamped <= .004f) {
            return null;
        }
        if ((lightRgb & 0xFFFFFF) == 0) {
            return null;
        }
        float k = (float) (fontPx / 50.0 * Math.max(.05, spread));
        float lr = ((lightRgb >> 16) & 255) / 255f;
        float lg = ((lightRgb >> 8) & 255) / 255f;
        float lb = (lightRgb & 255) / 255f;

        float[] b = new float[w * h];
        for (int i = 0; i < b.length; i++) {
            float v = Math.max(0f, mask[i]);
            if (v <= 1e-4f) {
                continue;
            }
            float hard = Math.max(v - THRESH, 0);
            float soft = KNEE * Math.max(0f, Math.min(v - THRESH + KNEE, 2 * KNEE));
            soft = soft * soft / (4 * KNEE + 1e-4f);
            b[i] = (float) Math.pow(Math.max(hard, soft), 2.2);
        }
        oldGaussBlur(b, w, h, S_PRE * k);
        int w1 = w / 2, h1 = h / 2, w2 = w1 / 2, h2 = h1 / 2, w3 = w2 / 2, h3 = h2 / 2;
        if (w3 < 1 || h3 < 1) {
            return null;
        }
        float[] m1 = oldDown2(b, w, h);
        oldGaussBlur(m1, w1, h1, S_M1 * k);
        float[] m2 = oldDown2(m1, w1, h1);
        oldGaussBlur(m2, w2, h2, S_M2 * k);
        float[] m3 = oldDown2(m2, w2, h2);
        oldGaussBlur(m3, w3, h3, S_M3 * k);
        float[] u = oldUpTo(m3, w3, h3, w2, h2);
        oldAddInto(u, m2);
        oldGaussBlur(u, w2, h2, S_UP1 * k);
        u = oldUpTo(u, w2, h2, w1, h1);
        oldAddInto(u, m1);
        oldGaussBlur(u, w1, h1, S_UP2 * k);
        u = oldUpTo(u, w1, h1, w, h);
        oldGammaBack(u);
        oldGammaBack(m3);
        oldGammaBack(m1);

        float[] gR = new float[w * h], gG = new float[w * h], gB = new float[w * h];
        oldAddLayerTinted(gR, gG, gB, u, GAIN_WIDE * clamped, lr, lg, lb, 0f, 1f);
        oldAddLayerTinted(gR, gG, gB, oldUpTo(m3, w3, h3, w, h),
                GAIN_OUTER * clamped, lr, lg, lb, 0f, SAT_OUTER);
        oldAddLayerTinted(gR, gG, gB, oldUpTo(m1, w1, h1, w, h),
                GAIN_INNER * clamped, lr, lg, lb, WHITEN_INNER, 1f);
        float[] core = new float[w * h];
        System.arraycopy(mask, 0, core, 0, w * h);
        oldGaussBlur(core, w, h, S_CORE * k);
        oldAddLayerTinted(gR, gG, gB, core, GAIN_CORE * clamped, lr, lg, lb, WHITEN_CORE, 1f);

        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x;
                float dither = ing(x, y) / 255f;
                float r = Math.max(0f, gR[i] + dither);
                float g = Math.max(0f, gG[i] + dither);
                float bl = Math.max(0f, gB[i] + dither);
                float mx = Math.max(r, Math.max(g, bl));
                if (mx <= 0.001f) {
                    out[i] = 0;
                    continue;
                }
                if (mx > 1f) {
                    r /= mx;
                    g /= mx;
                    bl /= mx;
                    mx = 1f;
                }
                int ri = Math.round(r / mx * 255f), gi = Math.round(g / mx * 255f),
                        bi = Math.round(bl / mx * 255f);
                int a = Math.round(mx * 255f);
                out[i] = (a << 24) | (Math.min(255, ri) << 16) | (Math.min(255, gi) << 8)
                        | Math.min(255, bi);
            }
        }
        return out;
    }

    private static float clamp01f(double v) {
        return (float) (v < 0 ? 0 : (v > 1 ? 1 : v));
    }

    // ==================== 测试 ====================

    /** 造一张「类字形」掩码：实心笔画块 + 抗锯齿边 + 大片留白（晕格 pad 区）。 */
    private static float[] makeMask(int w, int h, long seed) {
        float[] m = new float[w * h];
        Random rnd = new Random(seed);
        int strokes = 3 + rnd.nextInt(4);
        for (int s = 0; s < strokes; s++) {
            int cx = (int) (w * (0.25 + rnd.nextDouble() * 0.5));
            int cy = (int) (h * (0.25 + rnd.nextDouble() * 0.5));
            int hw = Math.max(2, w / (5 + rnd.nextInt(6)));
            int hh = Math.max(2, h / (5 + rnd.nextInt(6)));
            for (int y = Math.max(0, cy - hh); y < Math.min(h, cy + hh); y++) {
                for (int x = Math.max(0, cx - hw); x < Math.min(w, cx + hw); x++) {
                    // 边缘 1px 渐隐，模拟抗锯齿
                    float edge = Math.min(
                            Math.min(x - (cx - hw), (cx + hw) - x) / 2f,
                            Math.min(y - (cy - hh), (cy + hh) - y) / 2f);
                    m[y * w + x] = Math.min(1f, Math.max(m[y * w + x], Math.min(1f, edge)));
                }
            }
        }
        return m;
    }

    @Test
    @DisplayName("Ultra 晕格烘焙：优化后与优化前逐像素一致")
    void bloomBakeIsPixelIdentical() {
        // 尺寸覆盖奇/偶、非 2 的幂（真实晕格格宽由 advance + 2×pad 得到，全是奇数）
        int[][] sizes = {{96, 112}, {128, 112}, {154, 189}, {188, 215}, {61, 84}};
        int[] colors = {0xFFFFFF, 0xCC8844, 0x3C6EA0};
        double[] spreads = {0.5, 1.3, 3.0};
        double[] strengths = {0.05, 0.45, 1.0};

        int cases = 0;
        for (int[] wh : sizes) {
            int w = wh[0], h = wh[1];
            for (int ci = 0; ci < colors.length; ci++) {
                float[] mask = makeMask(w, h, 1000L * ci + w + h);
                double spread = spreads[(ci + w) % spreads.length];
                double strength = strengths[(ci + h) % strengths.length];
                float fontPx = 40f;

                int[] expected = oldBakeBloomGlowPixels(mask, w, h, fontPx, spread, strength,
                        colors[ci]);
                int[] actual = TextGlow.bakeBloomGlowPixels(mask, w, h, fontPx, spread, strength,
                        colors[ci]);
                assertNotNull(expected, "金标准不该失败: " + w + "x" + h);
                assertNotNull(actual, "新实现不该失败: " + w + "x" + h);
                assertEquals(expected.length, actual.length);
                assertArrayEquals(expected, actual,
                        "晕格像素漂移: " + w + "x" + h + " color=0x" + Integer.toHexString(colors[ci])
                                + " spread=" + spread + " strength=" + strength);
                cases++;
            }
        }
        assertEquals(15, cases, "覆盖数变了说明循环被改动");
    }

    @Test
    @DisplayName("bloom 晕格必须有可见能量（防空回归）")
    void bloomBakeProducesVisibleGlow() {
        int w = 128, h = 140;
        float[] mask = makeMask(w, h, 42L);
        // 满强度：光芯应到接近不透明
        int[] full = TextGlow.bakeBloomGlowPixels(mask, w, h, 40f, 1.3, 1.0, 0xFFFFFF);
        assertNotNull(full);
        int maxFull = 0, litFull = 0;
        for (int p : full) {
            int a = (p >>> 24) & 0xFF;
            maxFull = Math.max(maxFull, a);
            if (a > 4) {
                litFull++;
            }
        }
        assertTrue(maxFull > 200, "满强度光芯应接近不透明，实际 maxAlpha=" + maxFull);
        assertTrue(litFull > w * h / 16, "光晕面积太小: " + litFull + " px / " + (w * h));

        // 半强度（默认档）：能量按强度线性缩放，芯仍应明显可见但不该满
        int[] half = TextGlow.bakeBloomGlowPixels(mask, w, h, 40f, 1.3, 0.45, 0xFFFFFF);
        assertNotNull(half);
        int maxHalf = 0;
        for (int p : half) {
            maxHalf = Math.max(maxHalf, (p >>> 24) & 0xFF);
        }
        assertTrue(maxHalf > 80, "半强度光芯应可见，实际 maxAlpha=" + maxHalf);
        assertTrue(maxHalf < maxFull, "强度 0.45 的芯不该比满强度更亮");
    }
}
