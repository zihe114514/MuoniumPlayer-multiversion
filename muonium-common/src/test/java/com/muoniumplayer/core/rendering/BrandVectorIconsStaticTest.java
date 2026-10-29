package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.ProjectPaths;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 品牌矢量标识的静态测试：CPU 光栅，不需要 GL、不需要启动客户端。
 *
 * <h2>存在的理由</h2>
 * 酷狗、哔哩哔哩、汽水这三枚标识是<b>手抄进源码的矢量数据</b>（{@code <path d>} 字符串 + 外层
 * {@code <g>} 的平移缩放），抄错一个数字不会报错 —— 只会安静地画成一团别的东西，
 * 或者整枚消失。而且这类错误只在特定的图标上出现，跑一遍客户端很难覆盖到。
 *
 * <p>所以这里钉四件事：<b>哪几家接了矢量</b>（其余各家必须老实回落到字形字标）、
 * <b>几何对不对</b>（长宽比 + 离屏光栅出的墨迹）、<b>y 轴翻没翻回来</b>，
 * 以及<b>哪一份素材配了哪一家</b> —— 那两份素材的文件名与内容接反过一次，
 * 见 {@link #theTwoAssetsAreNotSwapped()}。</p>
 */
class BrandVectorIconsStaticTest {

    /** 光栅边长：够看清「上重下轻」这种量级的不对称，又够把字形的连通块分开，还不至于慢。 */
    private static final int SIZE = 128;

    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded(),
                "Skija 原生库加载失败: " + SkijaNatives.failure());
    }

    @Test
    @DisplayName("只有酷狗、哔哩哔哩、汽水、Spotify、Apple 接了矢量标识，其余各家必须老实回落")
    void onlyTheThreeProvidersUseVectorMarks() {
        for (MusicPlatform platform : MusicPlatform.values()) {
            boolean expected = platform == MusicPlatform.KUGOU
                    || platform == MusicPlatform.BILIBILI
                    || platform == MusicPlatform.QISHUI
                    || platform == MusicPlatform.SPOTIFY
                    || platform == MusicPlatform.APPLE;
            assertEquals(expected, BrandVectorIcons.has(platform),
                    platform + " 的矢量标识接线状态不对：接了的家会盖掉字形字标，没接的家必须继续走字形");
        }
        // 网易云、QQ、YouTube 走的是图标字体里本来就有的官方字形，接矢量反而会把它盖掉。
    }

    @Test
    @DisplayName("归一化后最长边为 1，长宽比与各份素材显示出来的尺寸一致")
    void normalizedAspectMatchesTheSvgs() {
        assertAspect(MusicPlatform.KUGOU, 0.9834);
        // 哔哩哔哩是一条横长的字标，另两枚接近正方 —— 差这么远，拉进方格必然走形。
        assertAspect(MusicPlatform.BILIBILI, 2.2286);
        assertAspect(MusicPlatform.QISHUI, 0.8840);
        // Spotify 用的是 simple-icons 的 24 网格圆形路径，等比之后就是一枚正圆。
        assertAspect(MusicPlatform.SPOTIFY, 1.0);
        // Apple 的 simple-icons 路径是一枚撑满 24 网格的圆角方块，等比后同样是正方。
        assertAspect(MusicPlatform.APPLE, 1.0);
    }

    @Test
    @DisplayName("离屏光栅之后墨迹不为空，长宽比也没有被方格改掉")
    void rasterKeepsTheAspectAndLeavesInk() {
        for (MusicPlatform platform : new MusicPlatform[]{
                MusicPlatform.KUGOU, MusicPlatform.BILIBILI, MusicPlatform.QISHUI,
                MusicPlatform.SPOTIFY, MusicPlatform.APPLE}) {
            int[][] grid = raster(platform);
            double[] size = BrandVectorIcons.inkSizeFor(platform);
            assertNotNull(size, platform + " 没有归一化尺寸");

            int minX = SIZE, minY = SIZE, maxX = -1, maxY = -1, ink = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if (grid[y][x] == 0) {
                        continue;
                    }
                    ink++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
            assertTrue(ink > 200, platform + " 光栅之后几乎没有墨迹（" + ink + " 像素），路径多半抄坏了");

            // 方格是正方形且按等比 contain 放进去的，所以墨迹盒的长宽比应当原样保留。
            double rasterAspect = (maxX - minX + 1.0) / (maxY - minY + 1.0);
            assertEquals(size[0] / size[1], rasterAspect, 0.06,
                    platform + " 光栅后的长宽比与归一化尺寸不一致，说明不是等比 contain 进去的");
        }
    }

    @Test
    @DisplayName("哔哩哔哩字标的墨迹重心偏下：这是 y 轴翻回来了的证据")
    void bilibiliInkSitsLow() {
        // 「bilibili」的小写字母字碗都在下半部，朝上的只有 b、l 的细竖笔和 i 的点，本来就下重上轻：
        // 照着 SVG 的负 y 缩放画出来，墨迹重心在 0.597（0 = 最上、1 = 最下）；忘了翻回来是 0.403。
        // 另两枚竖向几乎对半（酷狗 0.497 / 0.503、汽水的音符 0.488 / 0.511），在噪声里量不出来，
        // 只能靠下面那条「源码里的 y 缩放必须带负号」兜住。
        double centroid = verticalCentroid(raster(MusicPlatform.BILIBILI));
        assertTrue(centroid > 0.55,
                "哔哩哔哩字标的墨迹重心应当偏下，现在偏上：" + centroid
                        + " —— 多半是 y 轴的负号丢了");
    }

    @Test
    @DisplayName("两份素材没有再接反：哔哩哔哩是多块字形的字标，汽水是单枚音符")
    void theTwoAssetsAreNotSwapped() {
        // 这两份素材的<b>文件名与内容接反过</b>：名字写着 qishui 的那份里面是 bilibili 字标、
        // 名字写着哔哩哔哩的那份才是汽水的音符，于是哔哩哔哩的位置上画出了汽水的标识。
        // 光栅出来数「互不相连的块」是最直接的判据：字标是 b i l i 重复两遍、12 块字形；
        // 音符只有 1 块。长宽比断言其实也能发现接反（0.88 ↔ 2.23），但它是从同一份数据算出来的，
        // 换错源之后会跟着一起变，只有这条「形状本身长什么样」的断言拦得住。
        int wordmark = components(raster(MusicPlatform.BILIBILI));
        assertTrue(wordmark >= 6,
                "哔哩哔哩那枚应当是多块字形的「bilibili」字标，现在只有 " + wordmark
                        + " 块 —— 素材接反了，这是汽水那枚音符");
        int note = components(raster(MusicPlatform.QISHUI));
        assertTrue(note <= 2,
                "汽水那枚应当是单枚图形（音符），现在有 " + note + " 块 —— 素材接反了，"
                        + "这是哔哩哔哩那枚字标");
    }

    @Test
    @DisplayName("没有 Skija 帧、方格为零、这家没有矢量数据时都不画，绝不留空白也不抛异常")
    void drawInRefusesToDrawWhenItCannot() {
        assertFalse(SkijaGfx.active(), "测试环境里不该有活动的 Skija 帧，否则下面几条断言没有意义");
        assertFalse(BrandVectorIcons.drawIn(MusicPlatform.KUGOU, 0, 0, 23, 23, 0xFFFFFFFF),
                "不在帧里应当返回 false，让调用方回落到字形字标");
        assertFalse(BrandVectorIcons.drawIn(MusicPlatform.KUGOU, 0, 0, 0, 23, 0xFFFFFFFF),
                "方格宽为 0 应当返回 false，不能拿 0 去除");
        assertFalse(BrandVectorIcons.drawIn(MusicPlatform.NETEASE, 0, 0, 23, 23, 0xFFFFFFFF),
                "这家的矢量数据根本不存在，应当返回 false 而不是抛异常");
        assertFalse(BrandVectorIcons.drawCentered(MusicPlatform.KUGOU, 10, 10, 23, 0xFFFFFFFF),
                "drawCentered 是 drawIn 的皮，不该有第二条路径绕过上面的判定");
    }

    @Test
    @DisplayName("y 轴朝向必须与数据源一致：描摹素材翻转，Spotify 的标准 SVG 不翻")
    void sourceKeepsTheNegativeYScaleAndTheSvgTransforms() throws IOException {
        String src = readSource();

        // 三份描摹素材的坐标系是 y 朝上，走 flipY 分支取负；Spotify 那份是标准 SVG 数据
        // （y 朝下），不翻 —— 别把它当成「漏了负号」改回去，改回去它就上下颠倒了。
        assertTrue(src.contains("art.flipY ? -art.scale : art.scale"),
                "翻转分支不见了：三份描摹 SVG 的 y 缩放必须取负，漏掉整枚标识会上下颠倒");
        assertFalse(src.contains("art.flipY ? art.scale : -art.scale"),
                "翻转条件写反了：真值分支必须是负 y，否则描摹素材整体上下颠倒");
        assertTrue(src.contains("new String[]{SPOTIFY_D}, 0f, 0f, 1f, false"),
                "Spotify 是标准 SVG 数据（y 朝下），必须 flipY=false");

        // 这三行抄的是三份 SVG 外层 <g> 的 transform，改错一位数就会画歪或画小。
        assertTrue(src.contains("167.457259f, 659.597685f, .062622f"),
                "酷狗的变换常量与 kugou.svg 的 <g> 不一致");
        assertTrue(src.contains("135.851572f, 691.068512f, .070152f"),
                "哔哩哔哩的变换常量与 qishui.svg（内含 bilibili 字标）的 <g> 不一致");
        assertTrue(src.contains("150.221997f, 669.063002f, .070459f"),
                "汽水的变换常量与 哔哩哔哩.svg（内含汽水音符）的 <g> 不一致");
    }

    // ──────────────────────────── 助手 ────────────────────────────

    private static void assertAspect(MusicPlatform platform, double expected) {
        double[] size = BrandVectorIcons.inkSizeFor(platform);
        assertNotNull(size, platform + " 的路径解析不出来（返回 null）");
        double longest = Math.max(size[0], size[1]);
        assertEquals(1.0, longest, 1e-6,
                platform + " 归一化之后最长边必须是 1，现在最长边是 " + longest);
        assertEquals(expected, size[0] / size[1], 0.005,
                platform + " 的长宽比与素材显示出来的尺寸不一致，路径字符串或变换常量抄错了");
    }

    /** 把归一化路径等比放进 SIZE x SIZE，返回墨迹覆盖矩阵。 */
    private static int[][] raster(MusicPlatform platform) {
        Path path = BrandVectorIcons.pathForTest(platform);
        assertNotNull(path, platform + " 没有可用的矢量路径");
        Rect bounds = path.getBounds();

        int[][] grid = new int[SIZE][SIZE];
        try (Surface surface = Surface.makeRasterN32Premul(SIZE, SIZE);
             Paint fill = new Paint().setColor(0xFF000000).setAntiAlias(false)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0xFFFFFFFF);

            // 与 drawIn 同一套：等比 contain 到方格中央，留 8px 不贴边方便观察。
            float w = Math.max(1e-6f, bounds.getWidth());
            float h = Math.max(1e-6f, bounds.getHeight());
            float k = Math.min((SIZE - 16.0f) / w, (SIZE - 16.0f) / h);
            float left = (SIZE - w * k) * .5f - bounds.getLeft() * k;
            float top = (SIZE - h * k) * .5f - bounds.getTop() * k;
            try (Path placed = path.makeScale(k, k).makeOffset(left, top)) {
                canvas.drawPath(placed, fill);
            }

            ImageInfo info = ImageInfo.makeN32Premul(SIZE, SIZE);
            try (Bitmap bitmap = new Bitmap()) {
                bitmap.allocPixels(info);
                assertTrue(surface.readPixels(bitmap, 0, 0), "readPixels 失败");
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        // 白底黑墨：红通道暗下去就算墨迹。
                        grid[y][x] = ((bitmap.getColor(x, y) >> 16) & 0xFF) < 128 ? 1 : 0;
                    }
                }
            }
        }
        return grid;
    }

    /**
     * 墨迹重心在<b>墨迹包围盒</b>里的相对高度：0 = 贴着包围盒上沿，1 = 贴着下沿。
     *
     * <p>用包围盒相对高度而不是画布分带，是为了不受「contain 之后留白多少」影响 ——
     * 横长的字标在方格里只占中间一条，按画布分带量出来的上下比例会被留白冲淡。</p>
     */
    private static double verticalCentroid(int[][] grid) {
        int minY = SIZE, maxY = -1;
        long sum = 0;
        int ink = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (grid[y][x] == 0) {
                    continue;
                }
                ink++;
                sum += y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        assertTrue(ink > 0, "光栅图上一点墨迹都没有");
        assertTrue(maxY > minY, "墨迹只有一行，量不出重心");
        return (sum / (double) ink - minY) / (maxY - minY);
    }

    /** 4 连通块的个数：多块 = 字标（一个个字母），一块 = 单枚图形。 */
    private static int components(int[][] grid) {
        boolean[][] seen = new boolean[SIZE][SIZE];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int count = 0;
        for (int y0 = 0; y0 < SIZE; y0++) {
            for (int x0 = 0; x0 < SIZE; x0++) {
                if (grid[y0][x0] == 0 || seen[y0][x0]) {
                    continue;
                }
                count++;
                seen[y0][x0] = true;
                queue.add(new int[]{x0, y0});
                while (!queue.isEmpty()) {
                    int[] current = queue.poll();
                    int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] step : steps) {
                        int nx = current[0] + step[0];
                        int ny = current[1] + step[1];
                        if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE) {
                            continue;
                        }
                        if (grid[ny][nx] == 0 || seen[ny][nx]) {
                            continue;
                        }
                        seen[ny][nx] = true;
                        queue.add(new int[]{nx, ny});
                    }
                }
            }
        }
        return count;
    }

    private static String readSource() throws IOException {
        return new String(Files.readAllBytes(ProjectPaths.sourceFile("com/muoniumplayer/core/rendering/BrandVectorIcons.java")),
                StandardCharsets.UTF_8);
    }
}
