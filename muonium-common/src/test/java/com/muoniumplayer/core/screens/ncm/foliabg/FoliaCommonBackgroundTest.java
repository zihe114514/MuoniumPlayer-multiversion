package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「通用 / Common」背景的离屏栅格测试。
 *
 * <p>钉住四件容易回归的事：</p>
 * <ol>
 *   <li>几何层真的在动（clock 0 与 5 的画面必须不同），否则 CSS 关键帧的相位算错；</li>
 *   <li>{@code staticMode} 下只留纯色底 —— 与「关几何 + 关暗角」逐像素相同（封面关闭时）；</li>
 *   <li>同 seed 的两次实例逐像素一致（确定性 PRNG 的刻意偏离，见 {@link FoliaCommonBackground}）；</li>
 *   <li>暗角开关必须真的改变画面，且 {@code commonDisableGeometric} 不等于关暗角
 *       （folia 的 {@code disableGeometricBackground} 只隐藏形状与粒子，暗角由
 *       {@code disableVignette} 单独控制，GeometricBackground.tsx:154-213，规格 §4.1/§4.6）。</li>
 * </ol>
 *
 * <p>另有参数分布探针：照抄 folia 的抽取顺序后，15 个形状与 20 颗光点仍必须落在
 * §4.4.1 给的区间内。</p>
 */
class FoliaCommonBackgroundTest {

    private static final int W = 480;
    private static final int H = 270;

    private static final FoliaTheme THEME = FoliaTheme.of(0x101014, 0xF4F4F5, 0x71717A, 0xEA580C, false);
    private static final float ALPHA = 1f;

    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded(), "Skija 原生库必须可用");
    }

    @BeforeEach
    void resetConfig() {
        FoliaBackgroundConfig.resetAll();
    }

    @AfterEach
    void restoreConfig() {
        FoliaBackgroundConfig.resetAll();
    }

    // ------------------------------------------------------------------

    @Test
    void geometryLayerAnimatesWithTheClock() {
        // 只有几何层会产生帧差：关封面，保持几何与暗角开启。
        FoliaBackgroundConfig.commonUseCoverColorBg = false;
        FoliaBackgroundConfig.commonDisableGeometric = false;
        FoliaBackgroundConfig.commonDisableVignette = false;
        FoliaBackgroundConfig.staticMode = false;

        FoliaCommonBackground background = new FoliaCommonBackground(1234L);
        int[] atZero = render(background, 0.0);
        int[] atFive = render(background, 5.0);

        int changed = differentPixels(atZero, atFive);
        assertTrue(changed > 200, "clock 0 与 5 必须画出不同画面（实际差异 " + changed + " 像素）");
    }

    @Test
    void staticModeEqualsDisabledGeometryAndVignette() {
        // 封面关闭时 staticMode 只剩纯色遮罩；「关几何 + 关暗角」也必须正好等于它。
        FoliaBackgroundConfig.commonUseCoverColorBg = false;

        FoliaBackgroundConfig.staticMode = true;
        int[] frozen = render(new FoliaCommonBackground(77L), 3.0);

        FoliaBackgroundConfig.staticMode = false;
        FoliaBackgroundConfig.commonDisableGeometric = true;
        FoliaBackgroundConfig.commonDisableVignette = true;
        int[] bare = render(new FoliaCommonBackground(77L), 3.0);

        assertArrayEquals(frozen, bare, "staticMode 与「关几何 + 关暗角」必须逐像素一致");

        // 纯色遮罩就是 theme.background 全不透明。
        assertEquals(0xFF000000 | (THEME.background & 0xFFFFFF), frozen[0],
                "禁用几何与暗角后角落应是主题底色");
    }

    @Test
    void disableGeometricKeepsTheVignette() {
        FoliaBackgroundConfig.commonUseCoverColorBg = false;
        FoliaBackgroundConfig.staticMode = false;

        FoliaBackgroundConfig.commonDisableGeometric = true;
        FoliaBackgroundConfig.commonDisableVignette = false;
        int[] withVignette = render(new FoliaCommonBackground(9L), 1.0);

        FoliaBackgroundConfig.commonDisableVignette = true;
        int[] withoutVignette = render(new FoliaCommonBackground(9L), 1.0);

        // 角上只有暗角会压暗画面：disableGeometric 不负责关暗角。
        assertTrue(luminance(withVignette[0]) < luminance(withoutVignette[0]),
                "disableGeometric 时暗角仍应存在（角落应变暗）："
                        + luminance(withVignette[0]) + " vs " + luminance(withoutVignette[0]));
        assertTrue(differentPixels(withVignette, withoutVignette) > 1000,
                "暗角必须覆盖可见面积");
    }

    @Test
    void vignetteFadesInFromFortyPercentRadius() {
        FoliaBackgroundConfig.commonUseCoverColorBg = false;
        FoliaBackgroundConfig.staticMode = false;
        FoliaBackgroundConfig.commonDisableGeometric = true;
        FoliaBackgroundConfig.commonDisableVignette = false;

        int[] frame = render(new FoliaCommonBackground(3L), 0.0);
        // 中心（半径 40% 以内）必须完全不透明、不被暗角染黑。
        int center = frame[(H / 2) * W + W / 2];
        assertEquals(0xFF000000 | (THEME.background & 0xFFFFFF), center,
                "40% 半径以内暗角必须是纯透明");
        // 角落必须显著变暗，但不是全黑（alpha .6 的黑叠在底色上）。
        double corner = luminance(frame[0]);
        assertTrue(corner < luminance(center) * 0.75,
                "角落应明显暗于中心：" + corner + " vs " + luminance(center));
        assertTrue(corner > 0.0, "暗角是 60% 黑，不应把角落压成全黑");
    }

    @Test
    void sameSeedProducesIdenticalFrames() {
        FoliaBackgroundConfig.commonUseCoverColorBg = false;
        FoliaBackgroundConfig.staticMode = false;

        FoliaCommonBackground first = new FoliaCommonBackground(0xC0FFEEL);
        FoliaCommonBackground second = new FoliaCommonBackground(0xC0FFEEL);
        assertArrayEquals(render(first, 2.75), render(second, 2.75),
                "同 seed 的两个实例在同一 clock 必须逐像素一致");

        FoliaCommonBackground other = new FoliaCommonBackground(0xBADF00DL);
        assertTrue(differentPixels(render(first, 2.75), render(other, 2.75)) > 100,
                "不同 seed 应给出不同布局");
    }

    @Test
    void generatedParametersStayInsideTheReferenceRanges() {
        FoliaCommonBackground background = new FoliaCommonBackground(42L);
        int iconSlots = 0;
        for (int index = 0; index < FoliaCommonBackground.SHAPE_COUNT; index++) {
            // {type, iconSlot, initialX, initialY, size, duration, delay, opacity, rotation}
            float[] shape = background.shapeParameters(index);
            assertTrue(shape[0] >= 0f && shape[0] <= 3f, "形状类型 0..3: " + shape[0]);
            assertTrue(shape[2] >= 0f && shape[2] <= 100f, "initialX 0..100%: " + shape[2]);
            assertTrue(shape[3] >= 0f && shape[3] <= 100f, "initialY 0..100%: " + shape[3]);
            assertTrue(shape[4] >= 40f && shape[4] <= 140f, "size 40..140px: " + shape[4]);
            assertTrue(shape[6] >= 0f && shape[6] <= 5f, "delay 0..5s: " + shape[6]);
            assertTrue(shape[7] >= 0.11f && shape[7] <= 0.19f, "opacity .11...19: " + shape[7]);
            assertTrue(shape[8] >= 0f && shape[8] < 360f, "rotation 0..360: " + shape[8]);
            // 图标槽形状时长 20..40s（GeometricBackground.tsx:249）；其余 30..60s。
            if (shape[1] >= 0f) {
                iconSlots++;
                assertTrue(shape[1] <= 1f, "iconSlot 份额 0..1: " + shape[1]);
                assertTrue(shape[5] >= 20f && shape[5] <= 40f, "图标槽 duration 20..40s: " + shape[5]);
            } else {
                assertTrue(shape[5] >= 30f && shape[5] <= 60f, "duration 30..60s: " + shape[5]);
            }
        }
        assertTrue(iconSlots <= 6, "图标槽最多 6 个: " + iconSlots);
        assertEquals(15, FoliaCommonBackground.SHAPE_COUNT);
        assertEquals(20, FoliaCommonBackground.PARTICLE_COUNT);

        Set<Float> types = new HashSet<Float>();
        for (int index = 0; index < FoliaCommonBackground.PARTICLE_COUNT; index++) {
            float[] particle = background.particleParameters(index);
            assertTrue(particle[0] >= 1f && particle[0] <= 5f, "粒子 size 1..5px: " + particle[0]);
            assertTrue(particle[1] >= 0f && particle[1] <= 100f, "粒子 left 0..100%");
            assertTrue(particle[2] >= 0f && particle[2] <= 100f, "粒子 top 0..100%");
            assertTrue(particle[3] >= 0f && particle[3] <= 0.3f, "粒子 opacity 0...3: " + particle[3]);
            assertTrue(particle[4] >= 15f && particle[4] <= 35f, "粒子 duration 15..35s: " + particle[4]);
            assertTrue(particle[5] >= 0f && particle[5] <= 10f, "粒子 delay 0..10s: " + particle[5]);
        }
        for (int index = 0; index < FoliaCommonBackground.SHAPE_COUNT; index++) {
            types.add(background.shapeParameters(index)[0]);
        }
        assertTrue(types.size() >= 3, "15 个形状应覆盖大部分类型: " + types);
    }

    @Test
    void coverLayerOnlyDrawsWhenEnabledAndTheShaderExists() {
        FoliaBackgroundConfig.staticMode = false;
        FoliaBackgroundConfig.commonDisableGeometric = true;
        FoliaBackgroundConfig.commonDisableVignette = true;

        // 关封面：纯色底。
        FoliaBackgroundConfig.commonUseCoverColorBg = false;
        int[] plain = render(new FoliaCommonBackground(5L), 0.0, null);

        // 开封面但没有着色器：仍是纯色底（不能画半透明遮罩）。
        FoliaBackgroundConfig.commonUseCoverColorBg = true;
        int[] withoutShader = render(new FoliaCommonBackground(5L), 0.0, null);
        assertArrayEquals(plain, withoutShader, "coverShader 为 null 时不得启用封面遮罩路径");

        // 有封面着色器：流体层必须真的画出来，且遮罩只剩 commonCoverOpacity。
        try (Surface coverSurface = Surface.makeRasterN32Premul(32, 32)) {
            Canvas coverCanvas = coverSurface.getCanvas();
            int[] stripes = {0xFF14213D, 0xFFE85D04, 0xFF14532D, 0xFF3D1442};
            for (int i = 0; i < stripes.length; i++) {
                try (Paint paint = new Paint()) {
                    paint.setColor(stripes[i]);
                    coverCanvas.drawRect(Rect.makeXYWH(i * 8, 0, 8, 32), paint);
                }
            }
            try (Image cover = coverSurface.makeImageSnapshot()) {
                assertNotNull(cover, "封面快照必须成功");
                try (Shader coverShader = FoliaSkSL.childShader(cover)) {
                    assertNotNull(coverShader, "封面子着色器必须创建成功");
                    int[] fluid = render(new FoliaCommonBackground(5L), 0.0, coverShader);
                    assertTrue(differentPixels(plain, fluid) > 1000, "封面色流体必须改变画面");
                }
            }
        }
    }

    @Test
    void bandsDriveOnlyTheMatchingShapeType() {
        FoliaBackgroundConfig.commonUseCoverColorBg = false;
        FoliaBackgroundConfig.staticMode = false;
        FoliaBackgroundConfig.commonDisableVignette = true;

        // 用多个 clock 让全部形状都进入可见相位，再比较「全静音」与「只有 treble」。
        // cross 绑的是 treble 而不是 vocal：若把 vocal 误接给 cross，这条差分就会归零。
        float[] silence = new float[5];
        float[] trebleOnly = {0f, 0f, 0f, 0f, 255f};
        float[] vocalOnly = {0f, 0f, 0f, 255f, 0f};
        int trebleChanged = 0;
        int vocalChanged = 0;
        for (int i = 0; i < 12; i++) {
            double clock = i * 1.9;
            trebleChanged += differentPixels(
                    render(new FoliaCommonBackground(11L), clock, null, silence),
                    render(new FoliaCommonBackground(11L), clock, null, trebleOnly));
            vocalChanged += differentPixels(
                    render(new FoliaCommonBackground(11L), clock, null, silence),
                    render(new FoliaCommonBackground(11L), clock, null, vocalOnly));
        }
        assertTrue(trebleChanged > 200, "treble 必须改变 cross 的缩放（实际差异 " + trebleChanged + "）");
        assertTrue(vocalChanged <= trebleChanged / 4,
                "vocal 只给图标槽用，不应驱动任何形状（vocal=" + vocalChanged
                        + " treble=" + trebleChanged + "）");
    }

    @Test
    void alphaIsAppliedToTheWholeLayerNotPerPrimitive() {
        // CSS 的 opacity 是组透明度：整层先合成、再乘 alpha。这里用「关几何 + 关暗角」
        // 把画面压成单一纯色，再验证 alpha=.5 的合成结果确实是「半透明的主题底色」。
        FoliaBackgroundConfig.commonUseCoverColorBg = false;
        FoliaBackgroundConfig.staticMode = false;
        FoliaBackgroundConfig.commonDisableGeometric = true;
        FoliaBackgroundConfig.commonDisableVignette = true;

        try (Surface surface = Surface.makeRasterN32Premul(W, H)) {
            surface.getCanvas().clear(0x00000000);
            new FoliaCommonBackground(1L).render(surface.getCanvas(), W, H, THEME, 0.5f,
                    0.0, null, null);
            int[] pixels = pixels(surface);
            int pixel = pixels[(H / 2) * W + W / 2];
            int alpha = (pixel >>> 24) & 0xFF;
            assertTrue(alpha >= 120 && alpha <= 136, "整层 alpha .5 应得约 128: " + alpha);
            assertEquals((THEME.background >> 16) & 0xFF, (pixel >> 16) & 0xFF, 2, "红色通道");
            assertEquals((THEME.background >> 8) & 0xFF, (pixel >> 8) & 0xFF, 2, "绿色通道");
            assertEquals(THEME.background & 0xFF, pixel & 0xFF, 2, "蓝色通道");
        }
    }

    // ------------------------------------------------------------------

    private static int[] render(FoliaCommonBackground background, double clock) {
        return render(background, clock, null, null);
    }

    private static int[] render(FoliaCommonBackground background, double clock, Shader coverShader) {
        return render(background, clock, coverShader, null);
    }

    private static int[] render(FoliaCommonBackground background, double clock,
                                Shader coverShader, float[] bands) {
        try (Surface surface = Surface.makeRasterN32Premul(W, H)) {
            surface.getCanvas().clear(0x00000000);
            background.render(surface.getCanvas(), W, H, THEME, ALPHA, clock, bands, coverShader);
            return pixels(surface);
        }
    }

    private static int[] pixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(W, H, false);
            assertTrue(surface.getCanvas().readPixels(bitmap, 0, 0), "readPixels 失败");
            int[] pixels = new int[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) pixels[y * W + x] = bitmap.getColor(x, y);
            }
            return pixels;
        }
    }

    private static int differentPixels(int[] left, int[] right) {
        int count = 0;
        for (int i = 0; i < left.length; i++) if (left[i] != right[i]) count++;
        return count;
    }

    private static double luminance(int argb) {
        return 0.2126 * ((argb >> 16) & 0xFF)
                + 0.7152 * ((argb >> 8) & 0xFF)
                + 0.0722 * (argb & 0xFF);
    }
}

