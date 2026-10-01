package com.muoniumplayer.render.skija;

import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 几何规范化的静态测试：纯 Java 值类型，不需要 GL、不需要 Skija 原生库。
 *
 * <h2>存在的理由</h2>
 * 旧渲染层把矩形交给顶点插值，<b>反向的区间是合法输入</b>：{@code scale} 为负就沿该轴翻转采样，
 * 负的宽高各个着色器入口开头都有一段 {@code if (x > x + width) swap} 自己兜住。
 * Skia 的 {@link Rect#makeLTRB} 反过来是<b>断言式</b>的，{@code l > r} 或 {@code t > b}
 * 直接抛异常——实测就崩在
 * {@code Rect::makeLTRB expected t <= b, got 97.0 > 94.0}（表情精灵图第二行的格子）。
 *
 * <p>这类崩溃只在特定数据下触发（第一格永远正常），靠跑一遍客户端很难覆盖到，
 * 所以用单测把「渲染层必须容忍反向区间」这条钉死。
 */
class SkijaGeometryStaticTest {

    @Test
    @DisplayName("Skija 的 Rect.makeLTRB 对反向区间是抛异常的（这正是要兜住的前提）")
    void skijaRect对反向区间抛异常() {
        assertThrows(IllegalArgumentException.class, () -> Rect.makeLTRB(0f, 97f, 10f, 94f));
        assertThrows(IllegalArgumentException.class, () -> Rect.makeLTRB(10f, 0f, 5f, 10f));
    }

    @Test
    @DisplayName("正向 uv 原样通过，不做镜像")
    void 正向uv不镜像() {
        SkijaImages.Source src = SkijaImages.source(0.0, 0.0, 0.5, 0.25, 200, 400);
        assertFalse(src.flipX);
        assertFalse(src.flipY);
        assertEquals(0f, src.rect.getLeft());
        assertEquals(100f, src.rect.getRight());
        assertEquals(0f, src.rect.getTop());
        assertEquals(100f, src.rect.getBottom());
    }

    @Test
    @DisplayName("竖向反向的 uv 变成合法源矩形 + 竖向镜像（复现崩溃时的那组取值）")
    void 竖向反向uv转成镜像() {
        // 崩溃现场：texY*sheetHeight = 97、v*sheetHeight = 94。
        SkijaImages.Source src = SkijaImages.source(0.0, 97.0 / 400.0, 0.5, 94.0 / 400.0, 200, 400);
        assertTrue(src.flipY, "竖向区间反了，必须记为竖向镜像");
        assertFalse(src.flipX);
        assertTrue(src.rect.getTop() <= src.rect.getBottom(), "源矩形必须已经规范化");
        assertEquals(94f, src.rect.getTop(), 0.01f);
        assertEquals(97f, src.rect.getBottom(), 0.01f);
    }

    @Test
    @DisplayName("横向反向的 uv 变成合法源矩形 + 横向镜像")
    void 横向反向uv转成镜像() {
        SkijaImages.Source src = SkijaImages.source(0.5, 0.0, 0.25, 0.5, 200, 400);
        assertTrue(src.flipX);
        assertFalse(src.flipY);
        assertEquals(50f, src.rect.getLeft(), 0.01f);
        assertEquals(100f, src.rect.getRight(), 0.01f);
    }

    @Test
    @DisplayName("负的宽高换算成起点平移加正尺寸，与旧着色器入口的 swap 一致")
    void 负宽高的目标矩形被规范化() {
        Rect dst = SkijaImages.normalize(30, 40, -10, -20);
        assertEquals(20f, dst.getLeft());
        assertEquals(20f, dst.getTop());
        assertEquals(10f, dst.getWidth());
        assertEquals(20f, dst.getHeight());

        Rect plain = SkijaImages.normalize(5, 6, 7, 8);
        assertEquals(5f, plain.getLeft());
        assertEquals(7f, plain.getWidth());
    }

    @Test
    @DisplayName("圆角矩形同样容忍负宽高，并把负半径夹到 0")
    void 圆角矩形规范化() {
        RRect r = SkijaShapes.rrect(30, 40, -10, -20, -5);
        assertEquals(20f, r.getLeft());
        assertEquals(20f, r.getTop());
        assertEquals(10f, r.getWidth());
        assertEquals(20f, r.getHeight());
        for (float radius : r._radii) {
            assertTrue(radius >= 0f, "半径不能是负数，Skia 会画出空图形");
        }
    }
}
