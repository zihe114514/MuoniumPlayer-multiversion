package com.muoniumplayer.core.rendering.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ultra 档 HDR bloom 晕格烘焙管线的性质钉（探针 _scratch/glowprobe 五轮收敛的移植）。
 *
 * <p>为什么钉性质而不钉像素：管线的 σ/gain 是探针目检定案的观感参数，钉死像素
 * 会让任何微调都变成测试改动；这里钉的是「必须永远成立的物理性质」——能量单调、
 * 色相保持、直通 alpha 口径、无 NaN。它们一旦破，画面上就是晕消失/变色/碎屏。</p>
 */
class TextGlowBloomBakeTest {

    /** 圆盘掩码（中心亮斑，边缘渐隐）—— bright-pass 有内容的最小输入。 */
    private static float[] discMask(int w, int h) {
        float[] m = new float[w * h];
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.3f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / r;
                m[y * w + x] = Math.max(0f, 1f - d);
            }
        }
        return m;
    }

    private static long alphaSum(int[] px) {
        long sum = 0;
        for (int p : px) sum += (p >>> 24) & 255;
        return sum;
    }

    @Test
    @DisplayName("基础产出：尺寸对、strength=0 不烘焙、非法输入返回 null")
    void basicContract() {
        int w = 96, h = 64;
        float[] mask = discMask(w, h);
        int[] px = TextGlow.bakeBloomGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0x88BBFF);
        assertNotNull(px, "正常输入必须产出");
        assertEquals(w * h, px.length);

        assertNull(TextGlow.bakeBloomGlowPixels(mask, w, h, 30f, 1.3, 0.002, 0x88BBFF),
                "strength≈0 时直接跳过");
        assertNull(TextGlow.bakeBloomGlowPixels(null, w, h, 30f, 1.3, 1.0, 0x88BBFF));
        // 黑光色 = 不发光：直接回 null（调用方落旧路径，旧路径加色下黑光无贡献）
        assertNull(TextGlow.bakeBloomGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0));
    }

    @Test
    @DisplayName("能量单调：strength 越大晕越亮")
    void energyMonotonicInStrength() {
        int w = 96, h = 64;
        float[] mask = discMask(w, h);
        long weak = alphaSum(TextGlow.bakeBloomGlowPixels(mask, w, h, 30f, 1.3, 0.4, 0x88BBFF));
        long strong = alphaSum(TextGlow.bakeBloomGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0x88BBFF));
        assertTrue(strong > weak * 2, "全强度晕的能量应显著高于弱档: " + strong + " vs " + weak);
    }

    @Test
    @DisplayName("色相保持：红光的晕 R 通道主导，不被白化")
    void huePreserved() {
        int w = 96, h = 64;
        float[] mask = discMask(w, h);
        int[] px = TextGlow.bakeBloomGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0xFF3020);
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            b += p & 255;
        }
        assertTrue(r > b * 3 && r > g * 3, "红光的晕应 R 主导: r=" + r + " g=" + g + " b=" + b);
    }

    @Test
    @DisplayName("直通 alpha 口径：alpha>0 的 texel 必有满强度通道（GL 加色贡献无损）")
    void straightAlphaConvention() {
        int w = 96, h = 64;
        float[] mask = discMask(w, h);
        int[] px = TextGlow.bakeBloomGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0x88BBFF);
        for (int p : px) {
            int a = (p >>> 24) & 255;
            if (a == 0) {
                assertEquals(0, p & 0xFFFFFF, "全透明 texel 必须 (0,0,0,0)（边缘延拓的前提）");
                continue;
            }
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            assertTrue(Math.max(r, Math.max(g, b)) == 255,
                    "直通 alpha 下 rgb 应满强度（除回 max 通道）: " + Integer.toHexString(p));
        }
    }

    @Test
    @DisplayName("极端输入不炸：细长掩码、全零掩码、1×1")
    void extremeInputsSurvive() {
        float[] thin = new float[8 * 512];
        for (int x = 0; x < 512; x++) thin[4 * 512 + x] = 1f;
        assertNotNull(TextGlow.bakeBloomGlowPixels(thin, 8, 512, 30f, 1.0, 1.0, 0xFFFFFF));

        float[] zero = new float[64 * 64];
        int[] empty = TextGlow.bakeBloomGlowPixels(zero, 64, 64, 30f, 1.0, 1.0, 0xFFFFFF);
        if (empty != null) {
            assertEquals(0, alphaSum(empty), "全零掩码不应凭空产出能量");
        }

        float[] one = {1f};
        TextGlow.bakeBloomGlowPixels(one, 1, 1, 30f, 1.0, 1.0, 0xFFFFFF);   // 不抛即过
    }

    @Test
    @DisplayName("宽晕等效 σ 随字号与扩散单调，pad 口径比 High 档宽")
    void wideSigmaMonotonic() {
        float base = TextGlow.bloomWideSigma(30f, 1.3);
        assertTrue(TextGlow.bloomWideSigma(30f, 2.0) > base, "扩散越大晕越宽");
        assertTrue(TextGlow.bloomWideSigma(60f, 1.3) > base, "字号越大晕越宽");
        assertTrue(TextGlow.glowPadding(null, 1.3, TextGlow.GLOW_TIER_ULTRA) == 0);
        assertTrue(base > 0);
    }

    @Test
    @DisplayName("管线版本是正整数（晕格缓存键携带，防跨版本复用旧格）")
    void pipelineVersionPresent() {
        assertTrue(TextGlow.BLOOM_PIPELINE_VERSION >= 1);
        assertEquals(3, TextGlow.GLOW_BAKE_SCALE, "晕格烘焙域倍率：低频光形 3× 足够");
    }
}
