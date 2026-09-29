package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.ProjectPaths;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folia 界面那批 lucide 图标的静态测试：CPU 光栅，不需要 GL、不需要启动客户端。
 *
 * <h2>存在的理由</h2>
 * {@link FoliaLucideIcons} 是脚本从 lucide 生成的，但<b>生成脚本本身会把图元改写</b>：
 * lucide 的图标里混着 {@code <circle>}、{@code <line>}、{@code <polyline>}，脚本要把它们换算成
 * path 数据，Skija 才吃得下。换算写错（圆弧的扫掠方向、rect 的圆角）不会报错，只会安静地
 * 画成一团别的东西；而 {@code palette} 那四个小色孔靠的是「既填充又描边」，漏掉描边它们就
 * 只剩半径 0.5 的点，肉眼几乎看不见。这类差别在真机上扫一眼很容易滑过去。
 *
 * <p>所以这里钉四件事：<b>每枚图标都真的有墨迹、且没有跑出 24 网格</b>、
 * <b>左右两枚 chevron 互为镜像</b>（数据错位时最灵敏的一根探针）、
 * <b>带填充图元的图标没被生成脚本丢掉那一半</b>，以及<b>产物与生成脚本同源</b>
 * —— 手改过生成文件、或者脚本换了 lucide 版本却没重跑，都会被这条拦下。</p>
 */
class FoliaLucideIconsStaticTest {

    /** 光栅边长 = 24 网格 × 4，够量清「该对称的两枚不对称了」这个量级的问题。 */
    private static final int SIZE = 96;

    /** 网格到光栅的倍数。 */
    private static final int SCALE = SIZE / (int) FoliaLucideIcons.GRID;

    /** 允许墨迹越出网格的量：描边中心压在格线上时，半个描边宽会露在外面。 */
    private static final double GRID_SLACK = 1.0;

    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded(),
                "Skija 原生库加载失败: " + SkijaNatives.failure());
    }

    @Test
    @DisplayName("每枚图标都有墨迹，且墨迹没有跑出 24 网格")
    void everyIconHasInkInsideTheGrid() {
        List<String> names = new ArrayList<>(FoliaLucideIcons.names());
        assertTrue(names.size() > 20, "图标表几乎是空的：" + names.size());
        for (String name : names) {
            Path path = FoliaLucideIcons.pathForTest(name);
            assertNotNull(path, name + " 的路径解析不出来，多半是 d 字符串坏了");

            int[][] grid = raster(path);
            // 量的是**光栅出来的那块墨**，不是 path.getBounds()。Skia 那个盒对圆弧给的是
            // 控制点盒，比真墨迹大一圈：向导新加的 radio 是两段 r=10 的大弧，控制点盒量出来
            // 是 left=-2.13 / right=26.13（看着像换算错了），而屏幕上的墨其实落在 2..22 里。
            // 这根钉子说的是「墨迹没跑出网格」，那就只能按像素量。
            Rect ink = inkBox(grid);
            assertTrue(ink.getWidth() > 0 && ink.getHeight() > 0, name + " 光栅之后没有一像素墨迹");
            assertTrue(ink.getLeft() >= -GRID_SLACK && ink.getTop() >= -GRID_SLACK
                            && ink.getRight() <= FoliaLucideIcons.GRID + GRID_SLACK
                            && ink.getBottom() <= FoliaLucideIcons.GRID + GRID_SLACK,
                    name + " 的墨迹跑到了 24 网格外（" + ink + "）—— 图元换算错了");

            int cells = 0;
            for (int[] row : grid) {
                for (int cell : row) {
                    cells += cell;
                }
            }
            assertTrue(cells > 60, name + " 光栅之后几乎没有墨迹（" + cells + " 像素）");
        }
    }

    /** 光栅格里那块墨的包围盒，换算回 24 网格单位（右下那条边算进去，所以 +1 格）。 */
    private static Rect inkBox(int[][] grid) {
        int left = SIZE;
        int right = -1;
        int top = SIZE;
        int bottom = -1;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (grid[y][x] == 0) {
                    continue;
                }
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top) {
            return Rect.makeLTRB(0, 0, 0, 0);
        }
        return Rect.makeLTRB((float) (left / (double) SCALE), (float) (top / (double) SCALE),
                (float) ((right + 1) / (double) SCALE), (float) ((bottom + 1) / (double) SCALE));
    }

    @Test
    @DisplayName("chevron-left 与 chevron-right 互为镜像")
    void chevronsAreMirrorsOfEachOther() {
        // 这两枚是唯一成对出现的方向图标，也是数据错位时最灵敏的探针：源文件里的坐标、
        // 圆弧方向、甚至「拿错了相邻图标」都会让它先失败。
        int[][] left = raster(FoliaLucideIcons.pathForTest(FoliaLucideIcons.CHEVRON_LEFT));
        int[][] right = raster(FoliaLucideIcons.pathForTest(FoliaLucideIcons.CHEVRON_RIGHT));
        int mismatch = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (left[y][x] != right[y][SIZE - 1 - x]) {
                    mismatch++;
                }
            }
        }
        double ratio = mismatch / (double) (SIZE * SIZE);
        assertTrue(ratio < 0.01,
                "两枚 chevron 不是镜像关系，差异像素占 " + (int) (ratio * 10000) / 10000f
                        + " —— 描边数据或缩放方向被动过");
    }

    @Test
    @DisplayName("不在 Skija 帧内、名字未知、方格为零都只返回 false，不抛异常")
    void drawRefusesToDrawWhenItCannot() {
        assertFalse(SkijaGfx.active(), "测试环境里不该有活动的 Skija 帧，否则下面几条断言没有意义");
        assertFalse(FoliaLucideIcons.draw(FoliaLucideIcons.CLOSE, 10, 10, 18, 0xFFFFFFFF),
                "不在帧里应当返回 false，让调用方回落到字形图标");
        assertFalse(FoliaLucideIcons.draw("没有这枚图标", 10, 10, 18, 0xFFFFFFFF),
                "未知图标名应当返回 false 而不是抛异常");
        assertFalse(FoliaLucideIcons.draw(null, 10, 10, 18, 0xFFFFFFFF),
                "null 名字不该顺着缓存查下去");
        assertFalse(FoliaLucideIcons.draw(FoliaLucideIcons.CLOSE, 10, 10, 0, 0xFFFFFFFF),
                "方格边长为 0 应当返回 false，不能拿 0 去除");
    }

    @Test
    @DisplayName("palette 的四个色孔仍带填充：漏掉那一半它们就只剩看不见的小点")
    void paletteKeepsItsFilledElements() throws IOException {
        String source = readGeneratedSource();
        assertTrue(source.contains("FILL.put(\"palette\", FD_PALETTE);"),
                "palette 的填充图元集合没了：那四个色孔在设计上是 fill=currentColor 的"
                        + "小圆点，只描边会把它们画得比网页上小一圈");
    }

    @Test
    @DisplayName("产物与生成脚本同源：图标清单与锁定的 lucide 版本都对得上")
    void generatedFileMatchesTheScript() throws IOException {
        String source = readGeneratedSource();
        String script = new String(Files.readAllBytes(
                        ProjectPaths.versionRoot().getParent().toAbsolutePath()
                                .resolve("tools").resolve("make_lucide_icons.py")),
                StandardCharsets.UTF_8);

        String version = script.replaceAll("(?s).*LUCIDE_VERSION = \"([0-9.]+)\".*", "$1");
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "没从脚本里读出 lucide 版本：" + version);
        assertTrue(source.contains("从 lucide " + version + " 生成"),
                "生成文件写的版本与脚本锁的版本不一致 —— 换过版本却没重跑脚本，"
                        + "图标几何就和 folia 对不上了");

        List<String> declared = new ArrayList<>();
        for (String line : script.split("\n")) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("^\\s{4}\\(\"([A-Z_0-9]+)\",\\s*\"([a-z0-9-]+)\"")
                            .matcher(line);
            if (matcher.find()) {
                declared.add(matcher.group(2));
            }
        }
        assertEquals(uniqueSorted(declared), uniqueSorted(FoliaLucideIcons.names()),
                "图标表与脚本的 ICONS 清单不一致 —— 手改过生成文件，或者脚本改过没重跑");
    }

    // ──────────────────────────── 助手 ────────────────────────────

    /** 按 lucide 的默认描边（网格内 2 个单位、圆头圆角）把 24 网格路径光栅出来。 */
    private static int[][] raster(Path path) {
        assertNotNull(path, "路径为 null，没法光栅");
        int[][] grid = new int[SIZE][SIZE];
        try (Surface surface = Surface.makeRasterN32Premul(SIZE, SIZE);
             Paint stroke = new Paint().setColor(0xFF000000).setAntiAlias(true)
                     .setMode(PaintMode.STROKE)
                     .setStrokeWidth((float) (FoliaLucideIcons.STROKE_WIDTH * SCALE))
                     .setStrokeCap(PaintStrokeCap.ROUND)
                     .setStrokeJoin(PaintStrokeJoin.ROUND)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0xFFFFFFFF);
            canvas.save();
            canvas.scale(SCALE, SCALE);
            canvas.drawPath(path, stroke);
            canvas.restore();

            ImageInfo info = ImageInfo.makeN32Premul(SIZE, SIZE);
            try (Bitmap bitmap = new Bitmap()) {
                bitmap.allocPixels(info);
                assertTrue(surface.readPixels(bitmap, 0, 0), "readPixels 失败");
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        grid[y][x] = ((bitmap.getColor(x, y) >> 16) & 0xFF) < 128 ? 1 : 0;
                    }
                }
            }
        }
        return grid;
    }

    /** 去重再排序：脚本里同一枚 lucide 图标可能被两个语义常量用。 */
    private static List<String> uniqueSorted(java.util.Collection<String> names) {
        Set<String> seen = new LinkedHashSet<>(names);
        List<String> out = new ArrayList<>(seen);
        java.util.Collections.sort(out);
        return out;
    }

    private static String readGeneratedSource() throws IOException {
        return new String(Files.readAllBytes(ProjectPaths.sharedJava()
                .resolve("com/muoniumplayer/core/rendering/FoliaLucideIcons.java")),
                StandardCharsets.UTF_8);
    }
}
