package com.muoniumplayer.core.rendering.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级辉光烘焙管线的性质钉。
 *
 * <p>与 {@link TextGlowBloomBakeTest} 同理，钉性质不钉像素：σ/gain 是观感参数，
 * 钉死像素会让任何微调都变成测试改动。这里钉的是「一旦破画面就错」的性质 ——
 * 能量随强度单调、色相不被提白吃掉、直通 alpha 口径成立、光能确实铺得比字宽。</p>
 */
class TextGlowRowGlowBakeTest {

    /** 一条横排亮条（模拟一行字：宽扁，与真实行级掩码同形）。 */
    private static float[] rowMask(int w, int h) {
        float[] m = new float[w * h];
        int y0 = (int) (h * 0.35f), y1 = (int) (h * 0.65f);
        int x0 = (int) (w * 0.15f), x1 = (int) (w * 0.85f);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                m[y * w + x] = 1f;
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
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0x88BBFF);
        assertNotNull(px, "正常输入必须产出");
        assertEquals(w * h, px.length);

        assertNull(TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 0.002, 0x88BBFF),
                "strength≈0 时直接跳过");
        assertNull(TextGlow.bakeRowGlowPixels(null, w, h, 30f, 1.3, 1.0, 0x88BBFF));
        assertNull(TextGlow.bakeRowGlowPixels(mask, 0, h, 30f, 1.3, 1.0, 0x88BBFF));
        // 黑光色 = 不发光
        assertNull(TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0));
    }

    @Test
    @DisplayName("能量单调：strength 越大晕越亮")
    void energyMonotonicInStrength() {
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);
        long weak = alphaSum(TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 0.3, 0x88BBFF));
        long strong = alphaSum(TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0x88BBFF));
        assertTrue(strong > weak, "全强度晕的能量应高于弱档: " + strong + " vs " + weak);
        assertTrue(weak > 0, "弱档也必须有光（指数色调映射不会归零）");
    }

    @Test
    @DisplayName("指数色调映射：字芯趋向白，外圈保留光色")
    void coreWhitensOuterKeepsHue() {
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0xFF3020);

        // 字芯（掩码内部）应接近白：三通道都被提上来
        int cx = w / 2, cy = h / 2;
        int core = px[cy * w + cx];
        int cr = (core >> 16) & 255, cg = (core >> 8) & 255, cb = core & 255;
        assertTrue(cg > 120 && cb > 120, "字芯应被提白: r=" + cr + " g=" + cg + " b=" + cb);

        // 整张图仍以 R 主导（红光没有被全局洗白）
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            b += p & 255;
        }
        assertTrue(r > g && r > b, "红光的晕整体应 R 主导: r=" + r + " g=" + g + " b=" + b);
    }

    @Test
    @DisplayName("光能横向铺开：晕越过掩码左右边界（金字塔确实把光扩出去）")
    void energySpreadsBeyondMaskEdges() {
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);   // 掩码只覆盖 x∈[0.15w, 0.85w)
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0x88BBFF);
        int y = h / 2;
        // 掩码正外沿（15% 处再往外一点）必须有非零光
        int outside = px[y * w + Math.max(0, (int) (w * 0.08f))];
        assertTrue((outside >>> 24) > 0, "晕应越过掩码边界向外扩散");
    }

    @Test
    @DisplayName("直通 alpha 口径：全透明 texel 为 0，非零 texel 有满强度通道")
    void straightAlphaConvention() {
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0x88BBFF);
        for (int p : px) {
            int a = (p >>> 24) & 255;
            if (a == 0) {
                assertEquals(0, p & 0xFFFFFF, "全透明 texel 必须 (0,0,0,0)");
                continue;
            }
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            assertTrue(Math.max(r, Math.max(g, b)) == 255,
                    "直通 alpha 下 rgb 应满强度: " + Integer.toHexString(p));
        }
    }

    @Test
    @DisplayName("无抖动：同输入两次结果逐字节相同（管线不含任何随机/有序抖动）")
    void deterministicNoDither() {
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);
        int[] a = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 0.7, 0x88BBFF);
        int[] b = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 0.7, 0x88BBFF);
        assertNotNull(a);
        assertNotNull(b);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "第 " + i + " 个 texel 两次烘焙不一致");
        }
    }

    @Test
    @DisplayName("能量有界：金字塔不得饱和整张画布（gain 收敛的核心约束）")
    void energyStaysBounded() {
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, 1.3, 1.0, 0xFFFFFF);
        assertNotNull(px);

        // 上行链 gain ≥ 1 时不收敛：每级放大一点，最后整张画布推进 1-exp 的
        // 饱和区，光晕退化成实心白块（探针实拍到的形态）。这里钉住「饱和
        // 像素必须是少数」—— 光晕的本质是字周围一圈亮，不是一格白板。
        int saturated = 0;
        for (int p : px) {
            if (((p >>> 24) & 255) >= 250) saturated++;
        }
        double ratio = saturated / (double) px.length;
        assertTrue(ratio < 0.20,
                "饱和像素占比过高（光晕退化成实心块）: " + String.format("%.1f%%", ratio * 100));

        // 反过来也不能全暗：字芯必须真的亮起来
        assertTrue(saturated > 0, "字芯应当过曝发白（指数色调映射的高能区）");
    }

    @Test
    @DisplayName("扩散单调：spread 越大光晕铺得越开")
    void spreadWidensFalloff() {
        int w = 256, h = 64;
        float[] mask = rowMask(w, h);

        // 量「远离字面中心的外圈」能量占比：spread 越大，边缘处的相对亮度越高
        double near = outerEnergyRatio(mask, w, h, 0.4);
        double far = outerEnergyRatio(mask, w, h, 1.2);
        assertTrue(far > near * 1.2,
                "spread 增大后外圈应明显变亮: near=" + near + " far=" + far);
    }

    /** 远离字面中心一半宽度之外的 alpha 占比。 */
    private static double outerEnergyRatio(float[] mask, int w, int h, double spread) {
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, 30f, spread, 1.0, 0xFFFFFF);
        if (px == null) return 0;
        int mid = w / 2;
        long outer = 0, total = 0;
        for (int x = 0; x < w; x++) {
            int a = (px[(h / 2) * w + x] >>> 24) & 255;
            total += a;
            if (Math.abs(x - mid) > w * 0.30) outer += a;
        }
        return total == 0 ? 0 : outer / (double) total;
    }

    @Test
    @DisplayName("极端输入不炸：细长/全零/1×1")
    void extremeInputsSurvive() {
        // 极扁：行级真实比例，短边 8px 时金字塔级数必须自动收敛
        float[] thin = new float[512 * 8];
        for (int x = 0; x < 512; x++) thin[4 * 512 + x] = 1f;
        assertNotNull(TextGlow.bakeRowGlowPixels(thin, 512, 8, 30f, 1.0, 1.0, 0xFFFFFF));

        float[] zero = new float[64 * 64];
        int[] empty = TextGlow.bakeRowGlowPixels(zero, 64, 64, 30f, 1.0, 1.0, 0xFFFFFF);
        if (empty != null) {
            assertEquals(0, alphaSum(empty), "全零掩码不应凭空产出能量");
        }

        float[] one = {1f};
        TextGlow.bakeRowGlowPixels(one, 1, 1, 30f, 1.0, 1.0, 0xFFFFFF);   // 不抛即过
    }

    @Test
    @DisplayName("留白随字号与扩散单调增长，且与金字塔层级同量级")
    void paddingMonotonic() {
        int base = TextGlow.rowGlowPadding(30f, 1.3);
        assertTrue(base > 0, "留白必须为正");
        assertTrue(TextGlow.rowGlowPadding(30f, 2.0) > base, "扩散越大留白越大");
        assertTrue(TextGlow.rowGlowPadding(60f, 1.3) > base, "字号越大留白越大");
        // 7 级金字塔的等效半径应是字号的数倍，而不是几十倍（σ 直接相加的口径会溢出）
        assertTrue(base < 30f * 20, "留白不应离谱（口径错会算出十几倍字号）: " + base);
    }
}
