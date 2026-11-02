package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.management.FontManager;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;

/**
 * 原版渲染样式的行级辉光源：把一行文字经界面字体的 Skia 通路
 * （{@link CFontRenderer#drawGlyphsOn} 自带的高斯模糊）画进小型离屏位图，
 * 产出光晕贴图 —— 管线语义与上游 2D 歌词的「文本 → mask → 模糊」一致，
 * 但作用域是<b>单行 bbox</b>：能量不出行，模糊半径相对字高有意义。
 *
 * <p>产物是直通 ABGR 像素（a&lt;&lt;24|b&lt;&lt;16|g&lt;&lt;8|r），宿主灌进
 * DynamicTexture 后以一个 quad 垫在文字下面（混合由宿主 RenderType 决定）。
 * 文字本体照常第二遍画 —— 晕永远垫底，字锐利。</p>
 *
 * <p>缓存按 (text|color|strength|spread) 键：逐字渐显期间前缀每 ~180ms 变
 * 一次，一次重烘 = 一次小位图光栅化（&lt;0.2ms），成本可忽略。渲染线程单线程。
 * 复用 {@link LyricGlyphAtlas} 的烘焙字体派生档（50px），位图分辨率取 2×
 * 上屏尺寸（模糊后缩回，晕边缘平滑）。</p>
 */
public final class RowGlowSprite {

    /** 上屏 2× 的离屏分辨率。 */
    private static final int SS = 2;

    private static Bitmap bitmap;
    private static Canvas canvas;
    private static int width, height;
    private static String cachedKey = "";
    private static int[] cachedPixels;
    private static CFontRenderer bakeFont;

    private RowGlowSprite() {
    }

    /**
     * 烘一行文字的光晕。返回直通 ABGR 像素（行主序、w×h），宿主负责灌纹理。
     * 文本传<b>已显现前缀</b>：逐字渐显期间光晕跟着已出的字走。
     *
     * @param glowColor 光晕颜色（低 24 位）
     * @param strength  发光强度 0..1（σ 与透明度都随它涨）
     * @param spread    扩散 0.5..1.8（σ 乘数，主半径）
     */
    public static int[] bake(String text,
                             int glowColor, float strength, float spread) {
        if (text == null || text.isEmpty() || strength <= 0.02f) {
            return null;
        }
        String key = text + "|" + Integer.toHexString(glowColor & 0xFFFFFF)
                + "|" + Math.round(strength * 64f)
                + "|" + Math.round(spread * 16f);
        if (key.equals(cachedKey) && cachedPixels != null) {
            return cachedPixels;
        }
        CFontRenderer font = ensureBakeFont();
        if (font == null) return null;

        // 行宽自量：bakeFont（2 倍空间口径）量的宽 ÷ SS = 设计像素。调用方传的
        // widthPx（MC 字体口径）只作上界参考 —— 两套字体 advance 不同，quad 宽度
        // 以 sprite 自己的为准，晕和字才能逐像素对上。
        textW2x = (float) font.getStringWidthD(text);
        float textH2x = (float) font.fontHeight;
        float widthPx2 = textW2x / SS;
        float heightPx2 = textH2x / SS;

        // 模糊外溢按 σ 预留：位图比字面四周大 PAD（2 倍空间），上屏 quad 同样放大，
        // 晕不被 bbox 裁边。
        float sigma = (float) (10.0 * (0.35 + 0.65 * strength) * Math.max(0.5, spread));
        int pad = (int) Math.ceil(sigma * 2.5) + 8;
        int pw = Math.max(8, ((int) Math.ceil(widthPx2) + pad * 2) * SS);
        int ph = Math.max(8, ((int) Math.ceil(heightPx2) + pad * 2) * SS);
        if (pw > 4096 || ph > 256) return null;

        try {
            if (bitmap == null || pw != width || ph != height) {
                close();
                Bitmap bmp = new Bitmap();
                if (!bmp.allocPixels(ImageInfo.makeN32Premul(pw, ph))) {
                    bmp.close();
                    return null;
                }
                bitmap = bmp;
                canvas = new Canvas(bmp);
                width = pw;
                height = ph;
            }
            canvas.clear(0x00000000);
            // 字面绘制：CFontRenderer 是 2 倍空间口径（x/y/fontHeight 都是），位图
            // 设备像素恰好 = 2 倍空间坐标（SS=2），pad 直接用。文字白色 —— 晕的
            // 颜色由宿主 quad 顶点色乘出。
            font.drawGlyphsOn(canvas, text, pad, pad,
                    0xFFFFFFFF, (int) (Math.min(1f, strength) * 255f), sigma, null);
            cachedPixels = readPixelsAbgr();
            cachedKey = key;
            return cachedPixels;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 光晕位图相对字面 bbox 的左上外扩（设计像素）：quad 对位用。 */
    public static float padPx(float strength, float spread) {
        float sigma = (float) (10.0 * (0.35 + 0.65 * strength) * Math.max(0.5, spread));
        return (int) Math.ceil(sigma * 2.5) + 8;
    }

    /** 位图宽（设计像素 = 2 倍空间 / SS）。 */
    public static int width() {
        return width / SS;
    }

    public static int height() {
        return height / SS;
    }

    /** 字面宽（设计像素，bakeFont 口径）—— 宿主 quad 对位的文字部分。 */
    public static float textWidth() {
        return textW2x / SS;
    }

    private static float textW2x;

    /** 界面正文字体的 50px 派生档（与 LyricGlyphAtlas 同款；随字体切换重建）。 */
    private static CFontRenderer ensureBakeFont() {
        CFontRenderer base = FontManager.pf25bold;
        if (base == null || base.fontHeight <= 0) return null;
        if (bakeFont == null) {
            try {
                bakeFont = base.deriveAtSize(WorldLyricsState.LYRIC_SIZE * 5);
            } catch (Throwable t) {
                return null;
            }
        }
        return bakeFont;
    }

    private static int[] readPixelsAbgr() {
        if (bitmap == null) return null;
        try {
            byte[] src = bitmap.readPixels();
            if (src == null || src.length < width * height * 4) return null;
            int[] out = new int[width * height];
            for (int i = 0, o = 0; i < out.length; i++, o += 4) {
                int b = src[o] & 0xFF, g = src[o + 1] & 0xFF,
                        r = src[o + 2] & 0xFF, a = src[o + 3] & 0xFF;
                if (a != 0 && a != 255) {
                    b = Math.min(255, (b * 255 + a / 2) / a);
                    g = Math.min(255, (g * 255 + a / 2) / a);
                    r = Math.min(255, (r * 255 + a / 2) / a);
                }
                out[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 释放原生位图与派生字体（渲染关闭 / 字体切换时调）。 */
    public static void close() {
        if (bitmap != null) {
            bitmap.close();
            bitmap = null;
        }
        if (canvas != null) {
            canvas.close();
            canvas = null;
        }
        bakeFont = null;
        cachedKey = "";
        cachedPixels = null;
        textW2x = 0;
    }
}
