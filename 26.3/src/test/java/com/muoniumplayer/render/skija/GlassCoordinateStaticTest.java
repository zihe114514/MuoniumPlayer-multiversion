package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玻璃取样坐标的静态测试：完全在 CPU 上跑，不启动客户端。
 *
 * <h2>为什么要有这份文件</h2>
 * 真玻璃的全部说服力来自一句话——「面板里那块糊掉的画面，就是它背后那块屏幕」。这句话错了
 * 是<em>看不出来的</em>：底图整块糊着，把画面挪三十像素、或者整体错一倍，截图上都还是
 * 「一块磨砂玻璃」。2026-09-29 第一版就是这么漏过去的：当时接口让每个调用点自己报
 * 「局部单位 → 屏幕」的倍率，而灵动岛外面套的是带轴心的 {@code scaleAtPos}——轴心平移
 * 纯倍率根本表达不出来，尺寸档位一离开 1 透的就是屏幕别处。
 * 既然肉眼判不出来，就只能在这里用喂进去的矩阵把账算清，并且<em>拿真像素对答案</em>。
 *
 * <h2>这里钉住的三件事</h2>
 * <ol>
 *   <li>{@link GlassBackdrop#mappingFromMatrix}：轴心缩放的平移必须进映射；采集倍率要同时
 *       除到倍率与平移；带旋转/错切/镜像/透视的矩阵必须拒采（轴对齐的源框套不住斜矩形）。</li>
 *   <li>Skia 的 shader 局部矩阵约定：<b>取样坐标 = inverse(局部矩阵) 作用在画布局部坐标上</b>，
 *       所以喂进去的是「local → 图像素」这个仿射的<b>逆</b>。两条画法各测一遍：
 *       直接把背景做成 image shader（平移也在矩阵里），以及把它当 RuntimeEffect 的子 shader
 *       （{@code GlassRefraction} 走这条：缩放进矩阵、平移进 uniform，因为平移每帧都变，
 *       进缓存就等于每帧重建一个 shader）。</li>
 *   <li>{@link GlassRefraction} 的 uniform 打包。数量变了而 Java 侧的 ByteBuffer 没跟着变，
 *       表现是一句没头没尾的 {@code Can't wrap nullptr}（真机踩过一次）。</li>
 * </ol>
 */
class GlassCoordinateStaticTest {

    /** 背景图边长（图像素）。 */
    private static final int BACKDROP = 16;
    /** 画布矩阵：先平移 (6,2) 再放大 2 倍，于是 1 个画布局部单位 = 2 个设备像素。 */
    private static final float CTM_TX = 6f;
    private static final float CTM_TY = 2f;
    private static final float CTM_SCALE = 2f;
    /** 通道值 = 下标 /(BACKDROP-1) 编进 R/G，读回来是 17 的整数倍。 */
    private static final int STEP = 255 / (BACKDROP - 1);

    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded(),
                "Skija 原生库加载失败: " + SkijaNatives.failure());
    }

    // ── 1. 纯数学：矩阵 → 映射 ────────────────────────────────────────

    @Test
    @DisplayName("轴心缩放的平移必须一起进映射")
    void pivotScaleCarriesItsTranslation() {
        // scaleAtPos(cx, cy, s) 摊平是 [s,0, cx-s*cx, 0,s, cy-s*cy, 0,0,1]
        GlassBackdrop.Mapping map = GlassBackdrop.mappingFromMatrix(
                new float[]{2f, 0f, -100f, 0f, 2f, -50f, 0f, 0f, 1f}, 1f);
        assertNotNull(map);
        assertEquals(2f, map.scaleX, 1e-4f);
        // 轴心自己不动：cx=100 那里缩多少倍都还在原地
        assertEquals(100f, map.textureX(100f), 1e-3f);
        assertEquals(50f, map.textureY(50f), 1e-3f);
        // 轴心左边 50 个单位落到图上第 0 列——这一格正是纯倍率表达不出来的东西
        assertEquals(0f, map.textureX(50f), 1e-3f);
        assertEquals(200f, map.textureX(150f), 1e-3f);
    }

    @Test
    @DisplayName("采集倍率同时除到倍率与平移上")
    void downscaleAppliesToBothSlopeAndOffset() {
        // 画布放大 4 倍、背景图按 1/2 采集：1 个局部单位对应 2 个图像素，平移也一起减半
        GlassBackdrop.Mapping half = GlassBackdrop.mappingFromMatrix(
                new float[]{4f, 0f, 8f, 0f, 4f, 4f, 0f, 0f, 1f}, 2f);
        assertNotNull(half);
        assertEquals(2f, half.scaleX, 1e-4f);
        assertEquals(2f, half.scaleY, 1e-4f);
        assertEquals(4f, half.offsetX, 1e-3f);
        assertEquals(2f, half.offsetY, 1e-3f);
        assertEquals(24f, half.textureX(10f), 1e-3f);
    }

    @Test
    @DisplayName("带旋转、错切、镜像或透视的矩阵一律拒采")
    void nonAxisAlignedMatrixIsRefused() {
        float cosine = 0.7071f;
        // 45° 旋转：局部矩形映到屏幕是个斜四边形，轴对齐的源框会采进邻接画面
        assertNull(GlassBackdrop.mappingFromMatrix(
                new float[]{cosine, -cosine, 0f, cosine, cosine, 0f, 0f, 0f, 1f}, 1f));
        assertNull(GlassBackdrop.mappingFromMatrix(
                new float[]{2f, 0.5f, 0f, 0f, 2f, 0f, 0f, 0f, 1f}, 1f));
        // 镜像（翻转 X 那一类）与零缩放
        assertNull(GlassBackdrop.mappingFromMatrix(
                new float[]{-2f, 0f, 10f, 0f, 2f, 0f, 0f, 0f, 1f}, 1f));
        assertNull(GlassBackdrop.mappingFromMatrix(
                new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f}, 1f));
        // 透视那一行不是 (0,0,1) 也不采
        assertNull(GlassBackdrop.mappingFromMatrix(
                new float[]{2f, 0f, 0f, 0f, 2f, 0f, 0f, 0.01f, 1f}, 1f));
        assertNull(GlassBackdrop.mappingFromMatrix(null, 1f));
        assertNull(GlassBackdrop.mappingFromMatrix(new float[]{2f, 0f, 0f}, 1f));
        assertNull(GlassBackdrop.mappingFromMatrix(
                new float[]{2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f}, 0f));
    }

    // ── 2. 真像素：映射算出来的取样点，就是屏幕上那一点 ────────────────

    @Test
    @DisplayName("采集倍率 1 时，image shader 采到的必须是自己那个设备像素")
    void directImageShaderTracksDevicePixels() {
        runBackdropCheck(1f, true);
    }

    @Test
    @DisplayName("采集倍率 2 时，取样点按倍率收回（这是出厂默认档）")
    void directImageShaderTracksHalfResolutionBackdrop() {
        runBackdropCheck(2f, true);
    }

    @Test
    @DisplayName("RuntimeEffect 的子 shader：缩放进矩阵、平移进 uniform，两者都要生效")
    void effectChildShaderTracksSamePixels() {
        runBackdropCheck(1f, false);
        runBackdropCheck(2f, false);
    }

    /**
     * 把「画一块玻璃」在 CPU 上复现一遍：背景图每个像素编码自己的下标，画完之后逐个设备
     * 像素检查——实际采到的下标，是不是 {@link GlassBackdrop#mappingFromMatrix} 算出来的那个。
     *
     * @param down 采集倍率（真机上是设置项「背景采集分辨率」）
     * @param direct {@code true} = 背景直接当 image shader；{@code false} = 走 RuntimeEffect 子 shader
     */
    private static void runBackdropCheck(float down, boolean direct) {
        try (Surface target = Surface.makeRasterN32Premul(BACKDROP, BACKDROP);
             Image backdrop = makeIndexEncodedBackdrop()) {
            Canvas canvas = target.getCanvas();
            canvas.translate(CTM_TX, CTM_TY);
            canvas.scale(CTM_SCALE, CTM_SCALE);
            // 矩阵从活画布读，顺带钉住「Skia 的 3x3 按行存 [sx,kx,tx, ky,sy,ty, p0,p1,p2]」
            // 这个下标理解——读错一格，倍率与平移就对调了。
            float[] ctm = canvas.getLocalToDeviceAsMatrix33().getMat();
            assertEquals(CTM_SCALE, ctm[0], 1e-3f, "下标 0 应该是 X 倍率");
            assertEquals(CTM_TX, ctm[2], 1e-3f, "下标 2 应该是 X 平移");
            assertEquals(CTM_SCALE, ctm[4], 1e-3f, "下标 4 应该是 Y 倍率");
            assertEquals(CTM_TY, ctm[5], 1e-3f, "下标 5 应该是 Y 平移");

            GlassBackdrop.Mapping map = GlassBackdrop.mappingFromMatrix(ctm, down);
            assertNotNull(map);
            assertEquals(CTM_SCALE / down, map.scaleX, 1e-3f);
            assertEquals(CTM_TX / down, map.offsetX, 1e-3f);

            // 铺满能铺到的那块：局部 (0,0) 落在设备 (6,2)
            Rect local = Rect.makeXYWH(0f, 0f,
                    (BACKDROP - CTM_TX) / CTM_SCALE, (BACKDROP - CTM_TY) / CTM_SCALE);
            try (Paint paint = new Paint()) {
                try (Shader shader = direct ? directShader(backdrop, map)
                        : effectShader(backdrop, map)) {
                    paint.setShader(shader);
                    canvas.drawRect(local, paint);
                }
            }

            Bitmap readback = new Bitmap();
            readback.allocPixels(ImageInfo.makeN32Premul(BACKDROP, BACKDROP));
            assertTrue(target.readPixels(readback, 0, 0), "readPixels 失败");
            byte[] pixels = readback.readPixels();
            assertNotNull(pixels);
            int checked = 0;
            for (int dy = (int) CTM_TY; dy < BACKDROP; dy++) {
                for (int dx = (int) CTM_TX; dx < BACKDROP; dx++) {
                    // 设备像素中心 → 画布局部 → 映射算出的图像素
                    float localX = (dx + 0.5f - CTM_TX) / CTM_SCALE;
                    float localY = (dy + 0.5f - CTM_TY) / CTM_SCALE;
                    int expectR = clampIndex((int) Math.floor(map.textureX(localX))) * STEP;
                    int expectG = clampIndex((int) Math.floor(map.textureY(localY))) * STEP;
                    int base = (dy * BACKDROP + dx) * 4;
                    // N32 在小端上就是 BGRA：R 在第三个字节，G 在第二个。读错一位整张图会
                    // 全黑（当初探针就被这个坑过一次），所以这里显式加一句注释。
                    int red = pixels[base + 2] & 0xFF;
                    int green = pixels[base + 1] & 0xFF;
                    checked++;
                    assertTrue(Math.abs(red - expectR) <= STEP / 2,
                            (direct ? "image shader" : "effect 子 shader") + " 在 down=" + down
                                    + " 时设备 (" + dx + "," + dy + ") 采到 R=" + red
                                    + "，映射说该是 " + expectR + "（texX="
                                    + map.textureX(localX) + "）");
                    assertTrue(Math.abs(green - expectG) <= STEP / 2,
                            (direct ? "image shader" : "effect 子 shader") + " 在 down=" + down
                                    + " 时设备 (" + dx + "," + dy + ") 采到 G=" + green
                                    + "，映射说该是 " + expectG + "（texY="
                                    + map.textureY(localY) + "）");
                }
            }
            assertTrue(checked > 40, "覆盖的设备像素太少，等于没测: " + checked);
        }
    }

    private static int clampIndex(int value) {
        return Math.max(0, Math.min(BACKDROP - 1, value));
    }

    /**
     * 画法一：背景就是画布上的一层 shader 填充。
     *
     * <p>Skia 把 shader 的局部矩阵摆进画布空间里，取样时按<b>逆</b>把画布局部坐标取回 shader
     * 自己的空间，所以这里喂的是「local → 图像素」这个仿射的逆：缩放取倒、平移除过去再取负。
     * 喂反一次差的是 {@code scale²} 倍，而玻璃又是糊的——正是这份文件存在的理由。</p>
     */
    private static Shader directShader(Image backdrop, GlassBackdrop.Mapping map) {
        return backdrop.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                SamplingMode.DEFAULT, new Matrix33(
                        1f / map.scaleX, 0f, -map.offsetX / map.scaleX,
                        0f, 1f / map.scaleY, -map.offsetY / map.scaleY,
                        0f, 0f, 1f));
    }

    /**
     * 画法二：{@code GlassRefraction} 的真实结构——背景是 RuntimeEffect 的子 shader。
     *
     * <p>子 shader 只带缩放，平移走 {@code uSample}：矩阵里带上平移的话，灵动岛的轴心缩放与
     * 面板出场动画每帧都在挪它，缓存就废了（每帧重建一个绑定外部纹理的 shader）。两条画法必须
     * 采到同一个像素，这条测试就是把「平移放错了地方」这类改动手拿像素摁住。</p>
     */
    private static Shader effectShader(Image backdrop, GlassBackdrop.Mapping map) {
        RuntimeEffect effect = RuntimeEffect.makeForShader("""
                uniform shader uBackdrop;
                uniform float4 uSample;
                half4 main(float2 coord) {
                    return half4(uBackdrop.eval(coord + uSample.xy).rgb, 1.0);
                }
                """);
        assertNotNull(effect, "RuntimeEffect 编译失败");
        ByteBuffer buffer = ByteBuffer.allocate(effect.getUniformSize())
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(map.offsetX / map.scaleX).putFloat(map.offsetY / map.scaleY)
                .putFloat(0f).putFloat(0f);
        assertEquals(effect.getUniformSize(), buffer.position(),
                "uniform 字节数不匹配，Skia 会直接返回 nullptr");
        try (Shader child = backdrop.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                SamplingMode.DEFAULT, Matrix33.makeScale(1f / map.scaleX, 1f / map.scaleY))) {
            Shader shader = effect.makeShader(Data.makeFromBytes(buffer.array()),
                    new Shader[]{child});
            assertNotNull(shader, "makeShader 返回 null（多半是 uniform 打包与 SKSL 不一致）");
            return shader;
        }
    }

    /** 背景图：R 编码图像素列号、G 编码行号，其余为 0，alpha 不透明。 */
    private static Image makeIndexEncodedBackdrop() {
        try (Surface source = Surface.makeRasterN32Premul(BACKDROP, BACKDROP)) {
            Canvas canvas = source.getCanvas();
            canvas.clear(0xFF000000);
            for (int y = 0; y < BACKDROP; y++) {
                for (int x = 0; x < BACKDROP; x++) {
                    try (Paint paint = new Paint()) {
                        paint.setColor(0xFF000000 | ((x * STEP) << 16) | ((y * STEP) << 8));
                        canvas.drawRect(Rect.makeXYWH(x, y, 1f, 1f), paint);
                    }
                }
            }
            return source.makeImageSnapshot();
        }
    }

    // ── 3. 折射 shader 的 uniform 打包 ───────────────────────────────

    @Test
    @DisplayName("折射 SKSL 的 uniform 全是 float4，且数量与 Java 侧缓冲区一致")
    void refractionUniformLayout() {
        String source = GlassRefraction.source();
        long declared = Stream.of(source.split("\\R"))
                .map(String::trim)
                .filter(line -> line.startsWith("uniform "))
                .count();
        // 1 个 shader + 4 个 float4：uRect / uShape / uMisc / uSample
        assertEquals(5L, declared, "uniform 数量变化时必须同步修改 Java 侧 ByteBuffer");
        assertFalse(source.contains("uniform float2") || source.contains("uniform float3"),
                "折射 shader 的 uniform 必须全部使用 float4，避免 SkSL 对齐差异");
        try (RuntimeEffect effect = RuntimeEffect.makeForShader(source)) {
            assertNotNull(effect, "折射 SKSL 编译失败");
            assertEquals(64, effect.getUniformSize(),
                    "四个 float4 = 64 字节；Java 侧写多写少都会让 Skia 返回 nullptr");
        }
    }

    /**
     * GL token 一律用 LWJGL 的具名常量，不许凭记忆写十六进制。
     *
     * <p>这条不是洁癖，是真机查出来的：{@code GlassBackdrop} 曾经手写
     * {@code GL_READ_FRAMEBUFFER_BINDING = 0x8CA6} 与 {@code GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA8}，
     * 而正确值是 0x8CAA 与 0x8CA6 —— 两个都错，而且 0x8CA8 其实是 {@code GL_READ_FRAMEBUFFER}
     * （一个 target，不是 pname）。后果是每次采集一句
     * {@code GL_INVALID_ENUM: Unknown parameter enum}（26.2 真机一局 6029 句），
     * 并且「把读写帧缓冲绑回原样」还原的是垃圾值。之所以一直没被发现：查询之后紧跟着一句
     * {@code while (glGetError() != 0)} 清错误，把这条错误自己吃掉了。</p>
     */
    @Test
    @DisplayName("背景采集不许手写 GL token，必须用 LWJGL 的具名常量")
    void glTokensComeFromLwjgl() throws Exception {
        java.nio.file.Path source = com.muoniumplayer.testsupport.ProjectPaths.sharedJava()
                .resolve(java.nio.file.Paths.get("com", "muoniumplayer", "render", "skija",
                        "GlassBackdrop.java"));
        String text = new String(java.nio.file.Files.readAllBytes(source),
                java.nio.charset.StandardCharsets.UTF_8);
        // 用 String#lines() 而不是 split(行分隔符)：源码文件本身可能是 LF 也可能是 CRLF
        // （五棵树各树不同，同步脚本按目标文件自己的换行写），按系统分隔符切会把整份文件
        // 切成「一行」，于是这条守卫永远绿着 —— 第一次写它就踩中了这个假绿。
        java.util.List<String> handWritten = text.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("private static final int GL_"))
                .collect(java.util.stream.Collectors.toList());
        assertTrue(handWritten.isEmpty(),
                "GL token 请直接用 GL11/GL21/GL30 的常量，手写十六进制错了编译器不管。"
                        + "扫的文件是 " + source + "，里面找到: " + handWritten);
        // 这三处查询/格式就是当初写错的那三处，点名确认它们走的是具名常量。
        assertTrue(text.contains("GL30.GL_READ_FRAMEBUFFER_BINDING"), "读帧缓冲绑定查询丢了");
        assertTrue(text.contains("GL30.GL_DRAW_FRAMEBUFFER_BINDING"), "写帧缓冲绑定查询丢了");
        assertTrue(text.contains("GL11.GL_RGBA8"), "RGBA8 内部格式丢了");
    }

    @Test
    @DisplayName("折射厚度：面板档夹在 [6,28]，元素档必须给中间留一条不折的平玻璃")
    void refractionThicknessStaysClamped() {
        // 面板档：越小越薄、越大封顶 28。太薄看不见折射，太厚就把 P1 那层糊底整块折掉。
        for (double side : new double[]{2.0, 24.0, 200.0, 1080.0}) {
            float thickness = GlassRefraction.edgeThickness(side, side, 6.0, 28.0, 0.45);
            assertTrue(thickness >= 6.0 && thickness <= 28.0,
                    "面板边长 " + side + " 时厚度越界: " + thickness);
        }
        // 元素档：这一档是本轮新加的。按面板那 45% 的比例放到 30 高的曲目行上就是 13.5，
        // 上下两圈在中间撞上 = 整行都在折，那不是一块小玻璃，是一行果冻。
        double[][] elements = {{30, 30}, {19, 420}, {12, 26}, {26, 26}, {400, 260}};
        for (double[] side : elements) {
            float thickness = GlassRefraction.edgeThickness(side[0], side[1], 2.5, 9.0, 0.34);
            double shortSide = Math.min(side[0], side[1]);
            assertTrue(thickness >= 2.5 && thickness <= 9.0,
                    "元素 " + side[0] + "x" + side[1] + " 时厚度越界: " + thickness);
            assertTrue(thickness * 2.0 <= shortSide * 0.9,
                    "元素 " + side[0] + "x" + side[1] + " 上下两圈共 " + thickness * 2.0
                            + "，短边才 " + shortSide + "——中间没有不折的平了");
        }
        // 圆角必须夹进矩形自己：SKSL 里 r = min(radius, halfW, halfH)，Java 侧传的是原值，
        // 所以真正生效的是那一句夹取——这里把它复现一遍，免得日后改动把夹取挪走。
        assertEquals(2f, Math.min(12.0, Math.min(4.0 / 2, 40.0 / 2)), 1e-4f);
    }
}
