package com.muoniumplayer.core.lyrics3d;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.skija.PictureRecorder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * 绘光拖尾画布：同一套四层描边、圆头圆接、沿程渐变和 2× 超采样。
 * CPU 路径直接光栅化；GPU 路径只录制命令，宿主执行或失败后原样回放到位图。
 * 只在飞行窗口内工作，所有方法由渲染线程串行调用。
 */
public final class HuiguangTrailCanvas {

    private HuiguangTrailCanvas() {
    }

    private static Bitmap bitmap;
    private static Canvas bitmapCanvas;
    private static Canvas canvas;
    private static PictureRecorder recorder;
    private static boolean recording;
    private static boolean rasterReady;
    private static int bitmapWidth, bitmapHeight;
    private static int width, height;
    private static float planeW, planeH;
    private static float offX, offY;
    private static final float SS = 2f;

    /** CPU 光栅化入口；位图尺寸和本次命令录制尺寸独立记账。 */
    public static boolean begin(float w, float h, float x, float y) {
        if (!setBounds(w, h, x, y)) return false;
        abandonRecording();
        if (!ensureBitmap()) return false;
        canvas = bitmapCanvas;
        canvas.clear(0x00000000);
        rasterReady = true;
        return true;
    }

    /** 只录制路径，不光栅化、不读取像素；结束后宿主持有返回的 Picture。 */
    public static boolean beginRecording(float w, float h, float x, float y) {
        if (!setBounds(w, h, x, y)) return false;
        abandonRecording();
        try {
            if (recorder == null) recorder = new PictureRecorder();
            canvas = recorder.beginRecording(Rect.makeWH(width, height));
            recording = true;
            rasterReady = false;
            canvas.clear(0x00000000);
            return true;
        } catch (Throwable failure) {
            abandonRecording();
            return false;
        }
    }

    public static Picture finishRecording() {
        if (!recording || recorder == null) return null;
        try {
            return recorder.finishRecordingAsPicture();
        } finally {
            recording = false;
            canvas = null;
        }
    }

    /** GPU 失败后的同帧回退；重放原命令，轨迹和 alpha 不再计算第二次。 */
    public static boolean rasterize(Picture picture) {
        if (picture == null || recording || !ensureBitmap()) return false;
        bitmapCanvas.clear(0x00000000);
        bitmapCanvas.drawPicture(picture);
        rasterReady = true;
        return true;
    }

    private static boolean setBounds(float w, float h, float x, float y) {
        if (!Float.isFinite(w) || !Float.isFinite(h) || !Float.isFinite(x) || !Float.isFinite(y)
                || w <= 0f || h <= 0f || w * SS > 1600f || h * SS > 1600f) return false;
        width = Math.max(8, (int) Math.ceil(w * SS));
        height = Math.max(8, (int) Math.ceil(h * SS));
        planeW = w;
        planeH = h;
        offX = x;
        offY = y;
        return true;
    }

    private static boolean ensureBitmap() {
        if (width <= 0 || height <= 0) return false;
        if (bitmap != null && bitmapWidth == width && bitmapHeight == height) return true;
        closeBitmap();
        Bitmap made = new Bitmap();
        try {
            if (!made.allocPixels(ImageInfo.makeN32Premul(width, height))) {
                made.close();
                return false;
            }
            bitmapCanvas = new Canvas(made);
            bitmap = made;
            bitmapWidth = width;
            bitmapHeight = height;
            return true;
        } catch (Throwable failure) {
            made.close();
            bitmapCanvas = null;
            return false;
        }
    }

    private static void abandonRecording() {
        if (recording && recorder != null) {
            recorder.close();
            recorder = null;
        }
        recording = false;
        canvas = null;
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

    private static final Paint GLOW_PAINT = new Paint();
    private static final Paint MID_PAINT = new Paint();
    private static final Paint CORE_PAINT = new Paint();

    static {
        for (Paint paint : new Paint[] {GLOW_PAINT, MID_PAINT, CORE_PAINT}) {
            paint.setAntiAlias(true);
            paint.setMode(PaintMode.STROKE);
            paint.setStroke(true);
            paint.setStrokeCap(PaintStrokeCap.ROUND);
            paint.setStrokeJoin(PaintStrokeJoin.ROUND);
        }
    }

    /** 21 点轨迹的一笔四层描边；GPU 和 CPU 不分叉绘制参数。 */
    public static void strokeTrail(float[] xs, float[] ys, int count,
                                   int rgb, float alpha, float fontPx) {
        if (canvas == null || count < 2 || alpha <= 0.01f) return;
        float headX = 0f, headY = 0f, tailX = 0f, tailY = 0f;
        int ai = Math.min(255, (int) (Math.min(1f, alpha) * 255f));
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int argbFull = (ai << 24) | (r << 16) | (g << 8) | b;
        int argbMid = ((int) (ai * 0.85f) << 24) | (r << 16) | (g << 8) | b;
        int argbGlow = ((int) (ai * 0.30f) << 24) | (r << 16) | (g << 8) | b;
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
            boolean flat = Math.abs(headX - tailX) < 0.01f
                    && Math.abs(headY - tailY) < 0.01f;
            try (Shader grad = flat ? null : Shader.makeLinearGradient(
                    tailX, tailY, headX, headY,
                    new int[] {0x00000000, argbFull}, new float[] {0f, 1f});
                 Path path = builder.snapshot()) {
                GLOW_PAINT.setColor(argbGlow);
                GLOW_PAINT.setStrokeWidth(fontPx * 0.62f * SS);
                GLOW_PAINT.setShader(grad);
                GLOW_PAINT.setMaskFilter(null);
                canvas.drawPath(path, GLOW_PAINT);
                GLOW_PAINT.setColor(argbGlow);
                GLOW_PAINT.setStrokeWidth(fontPx * 0.40f * SS);
                canvas.drawPath(path, GLOW_PAINT);
                MID_PAINT.setColor(argbMid);
                MID_PAINT.setStrokeWidth(fontPx * 0.26f * SS);
                MID_PAINT.setShader(grad);
                canvas.drawPath(path, MID_PAINT);
                CORE_PAINT.setColor(argbCore);
                CORE_PAINT.setStrokeWidth(fontPx * 0.09f * SS);
                CORE_PAINT.setShader(grad);
                canvas.drawPath(path, CORE_PAINT);
            } finally {
                GLOW_PAINT.setShader(null);
                MID_PAINT.setShader(null);
                CORE_PAINT.setShader(null);
            }
        }
    }

    /** CPU 回退的直通 ARGB，数组尺寸始终恰好为实际画布。 */
    public static int[] readPixelsArgb() {
        return readPixels(false);
    }

    /** CPU 回退的直通 ABGR，与 NativeImage 的 RGBA 内存序一致。 */
    public static int[] readPixelsAbgr() {
        return readPixels(true);
    }

    private static int[] readPixels(boolean abgr) {
        if (!rasterReady || bitmap == null || recording || bitmapWidth != width || bitmapHeight != height) return null;
        try {
            byte[] bytes = bitmap.readPixels();
            if (bytes == null || bytes.length < width * height * 4) return null;
            int[] out = new int[width * height];
            for (int i = 0, o = 0; i < out.length; i++, o += 4) {
                int b = bytes[o] & 0xFF, g = bytes[o + 1] & 0xFF;
                int r = bytes[o + 2] & 0xFF, a = bytes[o + 3] & 0xFF;
                if (a != 0 && a != 255) {
                    b = Math.min(255, (b * 255 + a / 2) / a);
                    g = Math.min(255, (g * 255 + a / 2) / a);
                    r = Math.min(255, (r * 255 + a / 2) / a);
                }
                out[i] = abgr ? (a << 24) | (b << 16) | (g << 8) | r
                        : (a << 24) | (r << 16) | (g << 8) | b;
            }
            return out;
        } catch (Throwable failure) {
            return null;
        }
    }

    public static int canvasWidth() {
        return width;
    }

    public static int canvasHeight() {
        return height;
    }

    private static void closeBitmap() {
        if (bitmapCanvas != null) bitmapCanvas.close();
        if (bitmap != null) bitmap.close();
        bitmapCanvas = null;
        bitmap = null;
        bitmapWidth = bitmapHeight = 0;
        rasterReady = false;
    }

    public static void close() {
        abandonRecording();
        if (recorder != null) recorder.close();
        recorder = null;
        closeBitmap();
        width = height = 0;
    }
}
