package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.render.skija.SkijaGfx;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * 桌面 OSD 逐字歌词的图层合成器 —— 和全屏歌词页用的是同一套做法。
 *
 * <h2>要解决的画质问题</h2>
 * 最早的 OSD 实现是「直接往屏幕上叠图层」：一个字先画一遍未唱底色，再画一圈 8 次偏移的光晕，
 * 再画一遍实心已唱色，最后按羽化条数（最多 10 条）把同一个字形又画 10 遍，
 * 每一遍用裁剪盒裁一条竖窄条。三个必然的后果：
 * <ol>
 *   <li><b>叠加层模糊</b>：同一个抗锯齿字形被 alpha 混合十几遍，
 *       边缘覆盖率按 {@code 1-(1-a)^k} 累积，软边被叠成又粗又实的一圈，字腔内部同时过曝。</li>
 *   <li><b>马赛克感</b>：裁剪盒是向外取整到整屏幕像素的，而 OSD 字号下每条羽化带
 *       还不到一个逻辑像素宽，取整后全部被撑成一两个像素并互相重叠，
 *       填涂前沿变成一排硬边竖块，前沿本身也只能按整像素跳。</li>
 *   <li><b>字形发虚</b>：逐字放大的非整数倍率直接作用在屏幕上，必然重采样。</li>
 * </ol>
 *
 * <h2>现在的做法</h2>
 * 一个字词只有「一次未唱底色 + 一次合成」两次绘制：字形与光晕画进一个<b>图层</b>，
 * 然后用一条横向 alpha 渐变以 {@code DST_IN} 把填涂进度乘上去，
 * 前沿是解析求值的、不受整像素约束。
 *
 * <p>与上一版相比，这里不再需要两张离屏帧缓冲纹理和一个自写的合成着色器：
 * {@code canvas.saveLayer} 就是「离屏一层再合回来」，而且是<b>按需大小</b>的，
 * 不像帧缓冲那样只能整屏分配。{@code superSample} 参数因此失去意义——
 * Skia 直接在设备分辨率上光栅化，本来就没有「先超采样再缩小」这一步。
 * 参数保留是为了不动调用方（那是业务代码）。
 *
 * <h2>兜底</h2>
 * 整段包了 {@code Throwable} 捕获，一旦出错就永久置 {@link #unavailable}，
 * 调用方会回到逐条羽化的兜底路径，画质回到改动之前，功能不会丢。
 */
final class KaraokeTextCompositor implements SharedConstants {

    /** 在图层的局部坐标系（左上角 0,0，单位与逻辑像素一致）里画字形与光晕。 */
    interface InkPainter {
        void paint();
    }

    /** 一旦在当前环境下抛错就永久禁用，避免每帧反复失败。 */
    private static boolean unavailable;

    static boolean isUnavailable() {
        return unavailable;
    }

    /**
     * 把一个字词合成到屏幕上。
     *
     * @param x             合成区左上角的屏幕（HUD 逻辑）坐标 X
     * @param y             合成区左上角的屏幕（HUD 逻辑）坐标 Y
     * @param logicalWidth  合成区的逻辑宽度
     * @param logicalHeight 合成区的逻辑高度
     * @param superSample   历史参数，Skia 下无意义（见类注释），保留以免改动调用方
     * @param inkRgb        墨色的 RGB。历史参数：旧实现要用它当清屏色，
     *                      否则字形边缘的抗锯齿会混进清屏色给彩色歌词描一圈白边。
     *                      图层天生是透明的，不存在这个问题
     * @param solidRight    实心已唱段的右边界（局部坐标）
     * @param frontRight    真实播放前沿（局部坐标）；{@code solidRight}~{@code frontRight} 之间是羽化渐变
     * @param painter       字形与光晕的绘制回调
     * @return {@code true} 表示已经合成完毕；{@code false} 表示这条路径不可用，调用方需要走兜底绘制
     */
    boolean composite(double x, double y, int logicalWidth, int logicalHeight, int superSample,
                      int inkRgb, double solidRight, double frontRight, InkPainter painter) {
        if (unavailable || logicalWidth <= 0 || logicalHeight <= 0 || painter == null) {
            return false;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return false;
        }

        try {
            double solid = Math.max(0.0, Math.min(logicalWidth, solidRight));
            double front = Math.max(solid, Math.min(logicalWidth, frontRight));

            Rect bounds = Rect.makeXYWH((float) x, (float) y,
                    (float) logicalWidth, (float) logicalHeight);
            int layer = canvas.saveLayer(bounds, null);
            try {
                // painter 按局部坐标绘制（左上角 0,0），所以先把原点搬过去。
                SkijaGfx.pushMatrix();
                try {
                    SkijaGfx.translate(x, y);
                    painter.paint();
                } finally {
                    SkijaGfx.popMatrix();
                }

                // 填涂遮罩。DST_IN 的效果是 Da *= Sa，且只作用在实际画到的那一块上，
                // 所以三段可以各画一次、互不干扰：
                //   [0, solid)        实心已唱段 —— alpha 乘 1，恒等变换，直接跳过
                //   [solid, front)    羽化段 —— 横向 1 → 0
                //   [front, width)    还没唱到 —— 整段乘 0，抹掉
                if (front - solid > .001) {
                    fadeOut(canvas, x + solid, y, front - solid, logicalHeight);
                }
                if (logicalWidth - front > .001) {
                    erase(canvas, x + front, y, logicalWidth - front, logicalHeight);
                }
            } finally {
                canvas.restoreToCount(layer);
            }
            return true;
        } catch (Throwable throwable) {
            unavailable = true;
            return false;
        }
    }

    /** 横向把图层 alpha 从 1 渐变到 0。 */
    private static void fadeOut(Canvas canvas, double x, double y, double width, double height) {
        try (Shader gradient = Shader.makeLinearGradient(
                (float) x, (float) y, (float) (x + width), (float) y,
                new int[]{0xFFFFFFFF, 0x00FFFFFF})) {
            Paint paint = SkijaGfx.fillShader(gradient);
            paint.setBlendMode(BlendMode.DST_IN);
            canvas.drawRect(Rect.makeXYWH((float) x, (float) y, (float) width, (float) height), paint);
            paint.setShader(null);
            paint.setBlendMode(SkijaGfx.blendMode());
        }
    }

    /** 把图层这一块的 alpha 乘成 0。 */
    private static void erase(Canvas canvas, double x, double y, double width, double height) {
        Paint paint = SkijaGfx.fill(0x00000000);
        paint.setBlendMode(BlendMode.DST_IN);
        canvas.drawRect(Rect.makeXYWH((float) x, (float) y, (float) width, (float) height), paint);
        paint.setBlendMode(SkijaGfx.blendMode());
    }
}
