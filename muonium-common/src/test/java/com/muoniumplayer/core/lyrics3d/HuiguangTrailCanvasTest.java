package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.render.skija.SkijaNatives;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 绘光拖尾 Skija 画布的往返测试：begin → strokeTrail → readPixelsArgb。
 *
 * <p>曾在这里抓住过一次 P0 事故：begin 用 try-with-resources 持有 Bitmap，
 * 块结束即 close（Skija Managed 立即释放原生内存），帧尾 readPixels 直接
 * skija.dll EXCEPTION_ACCESS_VIOLation——编译过、其余 95 测试全绿也挡不住，
 * 因为当时没有任何测试碰过这个类。本类就是那个测试。</p>
 */
class HuiguangTrailCanvasTest {

    @AfterAll
    static void cleanup() {
        HuiguangTrailCanvas.close();
    }

    /** begin 分配 + 尺寸复用 + 尺寸变化重建，画布始终可读（所有权不死）。 */
    @Test
    void beginStrokeReadRoundTrip() {
        assertTrue(SkijaNatives.ensureLoaded(), "Skija native 应可用（test classpath 已带）");

        assertTrue(HuiguangTrailCanvas.begin(64f, 32f, -32f, -16f));
        assertEquals(128, HuiguangTrailCanvas.canvasWidth());
        assertEquals(64, HuiguangTrailCanvas.canvasHeight());
        assertEquals(64f, HuiguangTrailCanvas.planeW(), 1e-4f);
        assertEquals(-32f, HuiguangTrailCanvas.offX(), 1e-4f);

        // 一条横穿画布中线的 5 点折线（字面坐标）
        float[] xs = {-24f, -12f, 0f, 12f, 24f};
        float[] ys = {0f, 0f, 0f, 0f, 0f};
        HuiguangTrailCanvas.strokeTrail(xs, ys, 5, 0xFF8800, 1f, 10f);

        int[] px = HuiguangTrailCanvas.readPixelsArgb();
        assertNotNull(px, "readPixelsArgb 不应返回 null —— 位图必须仍活着");
        assertEquals(HuiguangTrailCanvas.canvasWidth() * HuiguangTrailCanvas.canvasHeight(), px.length);

        // 折线中点应有实墨（alpha>0），角落应透明
        int mid = px[(32 * 128) + 64];
        assertTrue((mid >>> 24) > 0, "折线中点应有覆盖（实测 " + (mid >>> 24) + "）");
        assertEquals(0, px[0] >>> 24, "画布角落应为空");
    }

    /** 尺寸复用路径（begin 两次同尺寸）也必须保持可读 —— 复用时 clear 不换对象。 */
    @Test
    void beginSameSizeStaysReadable() {
        assertTrue(HuiguangTrailCanvas.begin(64f, 32f, -32f, -16f));
        assertTrue(HuiguangTrailCanvas.begin(64f, 32f, -32f, -16f));
        float[] xs = {-20f, 20f};
        float[] ys = {0f, 0f};
        HuiguangTrailCanvas.strokeTrail(xs, ys, 2, 0x00AAFF, 0.8f, 10f);
        int[] px = HuiguangTrailCanvas.readPixelsArgb();
        assertNotNull(px);
        int center = px[(32 * 128) + 64];
        assertTrue((center >>> 24) > 0, "复用画布后描边应可见");
    }

    /** close 后 begin 重建；strokeTrail 在未 begin 时静默安全。 */
    @Test
    void closeAndRebegin() {
        HuiguangTrailCanvas.close();
        assertEquals(0, HuiguangTrailCanvas.canvasWidth());
        float[] xs = {-4f, 4f};
        float[] ys = {0f, 0f};
        assertDoesNotThrow(() -> HuiguangTrailCanvas.strokeTrail(xs, ys, 2, 0xFFFFFF, 1f, 10f));
        assertTrue(HuiguangTrailCanvas.begin(16f, 16f, -8f, -8f));
        HuiguangTrailCanvas.strokeTrail(xs, ys, 2, 0xFFFFFF, 1f, 10f);
        assertNotNull(HuiguangTrailCanvas.readPixelsArgb());
    }

    /**
     * 画布缩小时复用缓冲必须缩到精确 w×h：宿主按返回数组长度全量写
     * NativeImage，缓冲若大于画布会越界写穿 native 内存、殃及相邻纹理
     * （游戏内表现：拖尾/光晕/星芒整块变黑 —— 一次真实事故的回归钉）。
     */
    @Test
    void readPixelsBufferShrinksExactly() {
        assertTrue(HuiguangTrailCanvas.begin(200f, 100f, -100f, -50f));
        int big = HuiguangTrailCanvas.readPixelsArgb().length;
        assertEquals(400 * 200, big);

        // 换小画布（换行后的 active 行）：缓冲必须跟着缩，不能只增不减
        assertTrue(HuiguangTrailCanvas.begin(64f, 32f, -32f, -16f));
        int[] small = HuiguangTrailCanvas.readPixelsArgb();
        assertNotNull(small);
        assertEquals(128 * 64, small.length,
                "复用缓冲必须精确等于当前画布 w×h（宿主按此全量写纹理）");
    }
}
