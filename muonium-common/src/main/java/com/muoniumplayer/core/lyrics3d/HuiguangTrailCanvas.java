package com.muoniumplayer.core.lyrics3d;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;

/**
 * 绘光拖尾的 Skija 光栅化画布 —— 拖尾从「MC 顶点 quad 带（硬边锯齿、单像素
 * 宽度）」升级为 Skija Path 光栅化：圆头圆接、抗锯齿、三层辉光（外晕 blur +
 * 中间过渡 + 内芯白亮）、沿程渐变（尾透明 → 头满亮），质量对齐 folia 的
 * Canvas stroke（round cap/join）并超过其单色描边。
 *
 * <p>工作方式：渲染层在拖尾活跃时调 {@link #begin}（按句字面包围盒 + 超采样
 * 分配 CPU 位图），随后把每个飞行字的 21 点折线交给 {@link #strokeTrail}
 * （Skija Path 一次成笔、多层叠加），帧尾 {@link #readPixelsArgb} 产出直通
 * ARGB 像素交宿主上传纹理 —— 整块画布以一个 quad 覆盖句字面区域，一次收批。
 * 仅飞行窗口（约 1.15s）内每帧重绘，窗口外零成本。渲染线程单线程使用。</p>
 */
public final class HuiguangTrailCanvas {

    private HuiguangTrailCanvas() {
    }

    private static Bitmap bitmap;
    private static Canvas canvas;
    private static int width, height;      // 画布像素（含超采样）
    private static float planeW, planeH;   // 覆盖的字面区域尺寸（设计像素）
    private static float offX, offY;       // 字面区域左上角（字面坐标，中心原点）
    private static final float SS = 2f;    // 超采样倍率

    // 脏区跟踪（设备像素，帧间 8px 对齐）：本帧径迹实际覆盖的包围盒 —— 宿主
    // 只上传这一带，替代全画布读出+写入（拖尾通路的头号热点）。prev/cur 两块
    // 并集：画布尺寸复用时上一帧的径迹残留也在这一带（clear 已清，但上传侧
    // 必须把清零像素也带上，否则残留旧径迹）。
    private static int dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY;   // 本帧（含上帧并集）
    private static int prevMinX, prevMinY, prevMaxX, prevMaxY;       // 上帧（用于并集）

    /**
     * 开始一帧的拖尾光栅化。w/h 是要覆盖的字面区域尺寸（设计像素），
     * offX/offY 是该区域左上角的字面坐标（中心原点坐标系）。尺寸变化时重建。
     */
    public static boolean begin(float w, float h, float offX, float offY) {
        int pw = Math.max(8, (int) Math.ceil(w * SS));
        int ph = Math.max(8, (int) Math.ceil(h * SS));
        if (bitmap == null || pw != width || ph != height) {
            close();
            // 不能用 try-with-resources：Skija 的 Managed.close() 会立刻释放
            // 原生内存，块结束后 bitmap 已死，帧尾 readPixels 直接原生崩溃
            // （skija.dll EXCEPTION_ACCESS_VIOLATION，catch(Throwable) 拦不住）。
            // 所有权归静态字段，失败/异常路径手动 close。
            Bitmap bmp = new Bitmap();
            try {
                if (!bmp.allocPixels(ImageInfo.makeN32Premul(pw, ph))) {
                    bmp.close();
                    return false;
                }
                Canvas cv = new Canvas(bmp);
                cv.clear(0x00000000);
                bitmap = bmp;          // 接管所有权（close 时释放）
                canvas = cv;
                width = pw;
                height = ph;
            } catch (Throwable t) {
                bmp.close();
                bitmap = null;
                canvas = null;
                return false;
            }
        } else {
            canvas.clear(0x00000000);
        }
        planeW = w;
        planeH = h;
        HuiguangTrailCanvas.offX = offX;
        HuiguangTrailCanvas.offY = offY;
        // 尺寸重建（或首次）：画布全新清零，无上一帧残留，脏区归零待本帧径迹展开
        prevMinX = prevMinY = prevMaxX = prevMaxY = 0;
        dirtyMinX = dirtyMinY = dirtyMaxX = dirtyMaxY = 0;
        hasDirty = false;
        return true;
    }

    private static boolean hasDirty;

    /** 径迹折线画完后由 strokeTrail 内部登记：把该折线的包围盒并入本帧脏区。 */
    private static void markDirty(int minX, int minY, int maxX, int maxY) {
        if (!hasDirty) {
            dirtyMinX = minX;
            dirtyMinY = minY;
            dirtyMaxX = maxX;
            dirtyMaxY = maxY;
            hasDirty = true;
        } else {
            dirtyMinX = Math.min(dirtyMinX, minX);
            dirtyMinY = Math.min(dirtyMinY, minY);
            dirtyMaxX = Math.max(dirtyMaxX, maxX);
            dirtyMaxY = Math.max(dirtyMaxY, maxY);
        }
    }

    /**
     * 收帧：返回「上帧 ∪ 本帧」的脏区（设备像素，含 1px 呼吸余量），并滚动
     * prev=cur、清 cur —— 下帧宿主只需把这一带（含被 clear 归零的上一帧残留）
     * 写进纹理子矩形。xy/wh 由 out 携带（out[0..3] = x,y,w,h）。
     * @return 脏区像素面积（0 = 本帧无径迹）
     */
    public static int collectDirtyRect(int[] out) {
        int minX = dirtyMinX, minY = dirtyMinY, maxX = dirtyMaxX, maxY = dirtyMaxY;
        if (hasDirty && (prevMaxX > prevMinX)) {
            minX = Math.min(minX, prevMinX);
            minY = Math.min(minY, prevMinY);
            maxX = Math.max(maxX, prevMaxX);
            maxY = Math.max(maxY, prevMaxY);
        }
        if (!hasDirty) {
            // 本帧无径迹：若有上帧残留（还在屏上渐隐前），上帧带即本帧脏区
            if (prevMaxX > prevMinX) {
                out[0] = prevMinX;
                out[1] = prevMinY;
                out[2] = prevMaxX - prevMinX;
                out[3] = prevMaxY - prevMinY;
                prevMinX = prevMinY = prevMaxX = prevMaxY = 0;
                return out[2] * out[3];
            }
            return 0;
        }
        prevMinX = dirtyMinX;
        prevMinY = dirtyMinY;
        prevMaxX = dirtyMaxX;
        prevMaxY = dirtyMaxY;
        dirtyMinX = dirtyMinY = dirtyMaxX = dirtyMaxY = 0;
        hasDirty = false;
        out[0] = Math.max(0, minX);
        out[1] = Math.max(0, minY);
        out[2] = Math.min(width, maxX) - out[0];
        out[3] = Math.min(height, maxY) - out[1];
        if (out[2] <= 0 || out[3] <= 0) return 0;
        return out[2] * out[3];
    }

    public static float offX() {
        return offX;
    }

    public static float offY() {
        return offY;
    }

    public static float planeW() {
        return planeW;
    }

    public static float planeH() {
        return planeH;
    }

    public static float superSample() {
        return SS;
    }

    /**
     * 把一条 21 点径迹折线画进画布（folia trackLayer stroke 的精修版）：
     * Skija Path 一笔成型 + 三层辉光 + 沿程渐变。xs/ys 为字面坐标
     * （已由调用方叠加呼吸/绕行），内部转画布像素。
     */
    // 三层描边的常驻 Paint（渲染线程单线程）：原先每字每帧 new 3 个 Paint +
    // 3 次 PaintBuilder→Path snapshot（同一形状烘 3 个 Path）。这里 Paint 常驻、
    // Path 只 snapshot 一次三层共用 —— 飞行窗口内每帧每字省 5~6 个原生对象。
    private static final Paint GLOW_PAINT = new Paint();
    private static final Paint MID_PAINT = new Paint();
    private static final Paint CORE_PAINT = new Paint();

    static {
        for (Paint p : new Paint[] {GLOW_PAINT, MID_PAINT, CORE_PAINT}) {
            p.setAntiAlias(true);
            p.setMode(PaintMode.STROKE);
            p.setStroke(true);
            p.setStrokeCap(PaintStrokeCap.ROUND);
            p.setStrokeJoin(PaintStrokeJoin.ROUND);
        }
    }

    public static void strokeTrail(float[] xs, float[] ys, int count,
                                   int rgb, float alpha, float fontPx) {
        if (canvas == null || count < 2 || alpha <= 0.01f) return;
        float headX = 0f, headY = 0f, tailX = 0f, tailY = 0f;
        int ai = Math.min(255, (int) (Math.min(1f, alpha) * 255f));
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int argbFull = (ai << 24) | (r << 16) | (g << 8) | b;
        int argbMid = ((int) (ai * 0.8f) << 24) | (r << 16) | (g << 8) | b;
        int argbGlow = ((int) (ai * 0.35f) << 24) | (r << 16) | (g << 8) | b;
        int argbCore = ((int) (ai * 0.95f) << 24) | 0xFFFFFF;
        try (PathBuilder builder = new PathBuilder()) {
            for (int i = 0; i < count; i++) {
                float cx = (xs[i] - offX) * SS;
                float cy = (ys[i] - offY) * SS;
                if (i == 0) {
                    builder.moveTo(cx, cy);
                    tailX = cx;
                    tailY = cy;
                } else {
                    builder.lineTo(cx, cy);
                }
                headX = cx;
                headY = cy;
            }
            // 脏区登记：折线包围盒（字面→设备 px，扩描边半径 + blur 裕量）
            float strokePad = fontPx * 0.55f * SS + fontPx * 0.16f * SS * 2f + 2f;
            markDirty(
                    (int) Math.floor((Math.min(tailX, headX) - strokePad)),
                    (int) Math.floor((Math.min(tailY, headY) - strokePad)),
                    (int) Math.ceil((Math.max(tailX, headX) + strokePad)),
                    (int) Math.ceil((Math.max(tailY, headY) + strokePad)));
            // 沿程渐变：尾透明 → 头满亮。首尾重合（整帧纹丝未动）时轴向退化，
            // 退回无 shader 的平色描边。
            boolean flat = Math.abs(headX - tailX) < 0.01f
                    && Math.abs(headY - tailY) < 0.01f;
            try (Shader grad = flat ? null : Shader.makeLinearGradient(
                    tailX, tailY, headX, headY,
                    new int[] {0x00000000, argbFull}, new float[] {0f, 1f});
                 Path path = builder.snapshot()) {
                // 外晕：宽描边 + blur（folia bloom 的观感等价）
                GLOW_PAINT.setColor(argbGlow);
                GLOW_PAINT.setStrokeWidth(fontPx * 0.55f * SS);
                GLOW_PAINT.setShader(grad);
                GLOW_PAINT.setMaskFilter(MaskFilter.makeBlur(FilterBlurMode.NORMAL,
                        fontPx * 0.16f * SS));
                canvas.drawPath(path, GLOW_PAINT);
                // 中间层：中宽实描边（沿程渐变）
                MID_PAINT.setColor(argbMid);
                MID_PAINT.setStrokeWidth(fontPx * 0.18f * SS);
                MID_PAINT.setShader(grad);
                canvas.drawPath(path, MID_PAINT);
                // 内芯：细白亮线（能量感）
                CORE_PAINT.setColor(argbCore);
                CORE_PAINT.setStrokeWidth(fontPx * 0.07f * SS);
                CORE_PAINT.setShader(grad);
                canvas.drawPath(path, CORE_PAINT);
            }
        }
    }

    /**
     * 读出整块画布的直通 ARGB 像素（预乘 → 直通，文字管线口径）。
     * 每帧新分配（宿主按返回数组全量写纹理，任何复用/截断假设都是
     * 越界写 native 内存的隐患 —— 曾有复用缓冲大于画布把相邻纹理写黑的
     * 事故，这里不再优化）。
     */
    public static int[] readPixelsArgb() {
        if (bitmap == null) return null;
        try {
            byte[] srcBytes = bitmap.readPixels();
            if (srcBytes == null || srcBytes.length < width * height * 4) return null;
            int[] out = new int[width * height];
            for (int i = 0; i < out.length; i++) {
                int o = i * 4;
                int b = srcBytes[o] & 0xFF;
                int g = srcBytes[o + 1] & 0xFF;
                int r = srcBytes[o + 2] & 0xFF;
                int a = srcBytes[o + 3] & 0xFF;
                if (a != 0 && a != 255) {
                    r = Math.min(255, (r * 255 + a / 2) / a);
                    g = Math.min(255, (g * 255 + a / 2) / a);
                    b = Math.min(255, (b * 255 + a / 2) / a);
                }
                out[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读出整块画布像素，<b>同时</b>完成预乘→直通与 ARGB→ABGR 换位：
     * 输出 int 的字节序就是 NativeImage.setPixelRGBA 吃的内存序
     * (a<<24|b<<16|g<<8|r)，宿主写入循环退化成纯数组拷贝 —— 省掉宿主侧
     * 每像素三次位运算与移位（拖尾活跃帧的像素量在几十万到上百万级）。
     * 预乘除回直通与换位在同一次遍历里做，比「readPixelsArgb 再宿主换位」
     * 少一整遍逐像素扫描。
     */
    public static int[] readPixelsAbgr() {
        if (bitmap == null) return null;
        try {
            byte[] srcBytes = bitmap.readPixels();
            if (srcBytes == null || srcBytes.length < width * height * 4) return null;
            int[] out = new int[width * height];
            for (int i = 0, o = 0; i < out.length; i++, o += 4) {
                int b = srcBytes[o] & 0xFF;
                int g = srcBytes[o + 1] & 0xFF;
                int r = srcBytes[o + 2] & 0xFF;
                int a = srcBytes[o + 3] & 0xFF;
                if (a != 0 && a != 255) {
                    b = Math.min(255, (b * 255 + a / 2) / a);
                    g = Math.min(255, (g * 255 + a / 2) / a);
                    r = Math.min(255, (r * 255 + a / 2) / a);
                }
                // NativeImage 内存序 ABGR：setPixelRGBA 吃 (a<<24)|(b<<16)|(g<<8)|r
                out[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读出脏区（横带）的 ABGR 像素：只把 [x, x+w) × 全高的径迹覆盖带交宿主。
     * 每帧新分配（历史教训：复用缓冲越界写坏 native 内存 —— 这里坚持当帧分配，
     * 脏区典型只占全画布的一小半，分配量随 dirty-rect 同步下降）。
     * @return null = 画布未激活；否则长度 = w×h（band 尺寸，宿主按此全量写）
     */
    public static int[] readPixelsAbgrBand(int bandX, int bandY, int bandW, int bandH) {
        if (bitmap == null || bandW <= 0 || bandH <= 0) return null;
        try {
            byte[] srcBytes = bitmap.readPixels();
            if (srcBytes == null || srcBytes.length < width * height * 4) return null;
            int[] out = new int[bandW * bandH];
            // 只转换 band 内的行（预乘→直通+ABGR 换位按 band 面积，非全画布）
            for (int row = 0; row < bandH; row++) {
                int srcRow = (bandY + row) * width;
                int dstRow = row * bandW;
                for (int col = 0; col < bandW; col++) {
                    int o = (srcRow + bandX + col) * 4;
                    int b = srcBytes[o] & 0xFF;
                    int g = srcBytes[o + 1] & 0xFF;
                    int r = srcBytes[o + 2] & 0xFF;
                    int a = srcBytes[o + 3] & 0xFF;
                    if (a != 0 && a != 255) {
                        b = Math.min(255, (b * 255 + a / 2) / a);
                        g = Math.min(255, (g * 255 + a / 2) / a);
                        r = Math.min(255, (r * 255 + a / 2) / a);
                    }
                    out[dstRow + col] = (a << 24) | (b << 16) | (g << 8) | r;
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public static int canvasWidth() {
        return width;
    }

    public static int canvasHeight() {
        return height;
    }

    public static void close() {
        if (canvas != null) canvas.close();
        if (bitmap != null) bitmap.close();
        canvas = null;
        bitmap = null;
        width = height = 0;
    }
}
