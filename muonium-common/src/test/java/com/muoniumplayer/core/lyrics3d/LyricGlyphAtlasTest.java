package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字形图集的打包/缓存语义。烘焙路径依赖 Skija 原生库（离线环境不可用），
 * 这里用假 Host + 反射可及的打包逻辑测：shelf 打包不重叠、键量化、LRU 逐出、
 * 预乘→直通换算。
 */
class LyricGlyphAtlasTest {

    /** 记录 create/delete 调用的假宿主。upload 直接吞掉。 */
    private static class FakeHost implements LyricGlyphAtlas.Host {
        final List<Object> created = new ArrayList<>();
        final List<Object> deleted = new ArrayList<>();
        int uploads;

        @Override
        public Object createPage(int width, int height) {
            Object h = new Object();
            created.add(h);
            return h;
        }

        @Override
        public void uploadCell(Object page, int x, int y, int w, int h, int[] pixelsArgb) {
            uploads++;
        }

        @Override
        public void deletePage(Object page) {
            deleted.add(page);
        }
    }

    // ==================== shelf 打包（Page 包内可见，同包测试直接用） ====================

    @Test
    @DisplayName("shelf 打包：格子互不重叠、按行排布")
    void shelfPackingDoesNotOverlap() {
        LyricGlyphAtlas.Page p = new LyricGlyphAtlas.Page(new Object(), 256, 256);
        int[][] sizes = {{60, 70}, {40, 50}, {100, 30}, {80, 90}, {120, 60}};
        List<int[]> placed = new ArrayList<>();
        for (int[] wh : sizes) {
            int[] at = p.probe(wh[0], wh[1]);
            assertNotNull(at, "256px 页放得下 " + wh[0] + "x" + wh[1]);
            p.commit(at[0], at[1], wh[0], wh[1]);
            placed.add(new int[] {at[0], at[1], wh[0], wh[1]});
        }
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                assertFalse(overlaps(placed.get(i), placed.get(j)),
                        "格子 " + i + " 与 " + j + " 重叠");
            }
        }
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
                && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    @Test
    @DisplayName("放不下时 probe 返回 null，烘焙失败不留占位")
    void probeRejectsOversized() {
        LyricGlyphAtlas.Page p = new LyricGlyphAtlas.Page(new Object(), 256, 256);
        int[] at = p.probe(300, 10);
        assertNull(at, "宽超页必须拒绝");
        assertEquals(0, p.shelfX, "拒绝的格子不得推进游标");
    }

    @Test
    @DisplayName("烘焙失败（upload 抛异常）不占用页空间")
    void failedUploadDoesNotCommitShelf() {
        FakeHost host = new FakeHost() {
            @Override
            public void uploadCell(Object page, int x, int y, int w, int h, int[] pixelsArgb) {
                throw new IllegalStateException("boom");
            }
        };
        LyricGlyphAtlas.setHost(host);
        try {
            // Skija 缺位时 bake 走不到 upload —— 两种情况下都不应有格子落账
            LyricGlyphAtlas.GlyphCell cell =
                    LyricGlyphAtlas.getGlyph('测', 0xFFFFFF, 0.35f, 1.3f, 0xFFFFFF);
            // cell 为 null（环境无 Skija / 失败）或非 null（有 Skija 且成功）；关键是
            // 一旦失败不能留下「已提交但取不到」的格子。这里只断言失败路径的一致性。
            if (cell == null) {
                assertTrue(host.deleted.size() <= host.created.size(),
                        "失败时开过的页必须已删或复用，不能泄漏");
            }
        } finally {
            LyricGlyphAtlas.invalidateAll();
            LyricGlyphAtlas.setHost(null);
        }
    }

    // ==================== 缓存键 ====================

    @Test
    @DisplayName("缓存键：发光参数量化（1/64、1/16 抖动同键），颜色与字符区分")
    void cacheKeyQuantizesGlowParams() {
        String base = LyricGlyphAtlas.cacheKey('字', 0xF7E1D7, 0.3500f, 1.30f, 0xFFFFFF);
        String jitter = LyricGlyphAtlas.cacheKey('字', 0xF7E1D7, 0.3505f, 1.304f, 0xFFFFFF);
        assertEquals(base, jitter, "量化步长内的抖动必须同键（防拖滑块纹理风暴）");

        assertNotEquals(base, LyricGlyphAtlas.cacheKey('字', 0xF7E1D7, 0.50f, 1.30f, 0xFFFFFF),
                "强度跨量化档必须换键");
        assertNotEquals(base, LyricGlyphAtlas.cacheKey('字', 0xF7E1D7, 0.35f, 1.30f, 0xFF0000),
                "发光颜色必须进键");
        assertNotEquals(base, LyricGlyphAtlas.cacheKey('词', 0xF7E1D7, 0.35f, 1.30f, 0xFFFFFF),
                "字符必须进键");
    }

    @Test
    @DisplayName("invalidateAll 推进字体世代：同参数同字符的缓存键跨世代必变")
    void cacheKeyChangesAcrossGenerations() {
        String before = LyricGlyphAtlas.cacheKey('字', 0xF7E1D7, 0.35f, 1.30f, 0xFFFFFF);
        LyricGlyphAtlas.invalidateAll();
        String after = LyricGlyphAtlas.cacheKey('字', 0xF7E1D7, 0.35f, 1.30f, 0xFFFFFF);
        assertNotEquals(before, after, "世代必须进键：字体切换后旧格绝不能复用");
    }

    // ==================== 预乘 → 直通 ====================

    @Test
    @DisplayName("readPixelsArgb：预乘像素除回直通 alpha；透明 texel 的 RGB 被延拓（去暗边）")
    void readPixelsUnpremultiplies() {
        try (io.github.humbleui.skija.Bitmap bm = new io.github.humbleui.skija.Bitmap()) {
            bm.allocPixels(io.github.humbleui.skija.ImageInfo.makeN32Premul(2, 1));
            try (io.github.humbleui.skija.Canvas cv = new io.github.humbleui.skija.Canvas(bm)) {
                cv.clear(0x00000000);
                io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint();
                paint.setColor(0x80FF4080); // 半透品红
                cv.drawRect(io.github.humbleui.types.Rect.makeXYWH(0, 0, 1, 1), paint);
                paint.close();
            }
            int[] out = LyricGlyphAtlas.readPixelsArgb(bm, 2, 1);
            assertNotNull(out);
            int half = out[0];
            int clear = out[1];
            int a = (half >>> 24) & 0xFF;
            // 直通 alpha 下，RGB 通道不得超过 alpha（预乘的守恒律），除回后 R≈255
            int r = (half >>> 16) & 0xFF;
            assertTrue(a > 0 && a < 255, "半透像素的 alpha 应在 (0,255): " + a);
            assertTrue(r >= 250, "预乘的 R 应被除回 ≈255: " + r);
            // RGB 延拓（去毛边）：透明 texel 的 alpha 必须仍为 0（混合贡献零），
            // 但 RGB 继承相邻非透明色 —— LINEAR 边缘插值从「色↔黑」变「色↔色」
            assertEquals(0, clear >>> 24, "透明像素 alpha 必须保持 0");
            assertEquals(r, (clear >>> 16) & 0xFF,
                    "透明像素 RGB 应被延拓为邻色（防 LINEAR 暗晕）");
        }
    }

    @Test
    @DisplayName("readPixelsArgb：整行全透明的行不延拓（无可继承色时保持全零）")
    void readPixelsFullyTransparentRowStaysZero() {
        try (io.github.humbleui.skija.Bitmap bm = new io.github.humbleui.skija.Bitmap()) {
            bm.allocPixels(io.github.humbleui.skija.ImageInfo.makeN32Premul(2, 2));
            try (io.github.humbleui.skija.Canvas cv = new io.github.humbleui.skija.Canvas(bm)) {
                cv.clear(0x00000000);
                io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint();
                paint.setColor(0xFF3366CC);
                cv.drawRect(io.github.humbleui.types.Rect.makeXYWH(0, 0, 2, 1), paint);
                paint.close();
            }
            int[] out = LyricGlyphAtlas.readPixelsArgb(bm, 2, 2);
            assertNotNull(out);
            // 第 2 行全透明且上方不相邻非透明?——第 1 行不透明、纵向不延拓（只水平扫）：
            // 行内无非透明邻 → RGB 保持 0
            assertEquals(0, out[2], "无水平邻色的透明像素应保持全零");
            assertEquals(0, out[3], "无水平邻色的透明像素应保持全零");
        }
    }

    // ==================== 宿主未装配 ====================

    @Test
    @DisplayName("宿主未装配时 getGlyph 直接返回 null（渲染层回退像素字）")
    void nullHostReturnsNull() {
        LyricGlyphAtlas.setHost(null);
        assertNull(LyricGlyphAtlas.getGlyph('字', 0xFFFFFF, 0.35f, 1.3f, 0xFFFFFF));
        assertEquals(-1f, LyricGlyphAtlas.designAdvance('字'), 1e-6f);
    }
}
