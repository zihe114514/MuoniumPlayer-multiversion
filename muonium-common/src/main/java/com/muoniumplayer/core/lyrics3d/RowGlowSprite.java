package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.TextGlow;
import com.muoniumplayer.core.management.FontManager;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;

/**
 * 行级辉光源：把一行文字先光栅成<b>覆盖度掩码</b>，再交给多级金字塔管线
 * （{@link TextGlow#bakeRowGlowPixels}）做成光晕贴图。
 *
 * <p>作用域是<b>单行 bbox</b>：光能不出行，光晕半径相对字高有意义。三件事分开：
 * 光栅化（Skija，拿到干净的字形覆盖度）、金字塔与色调映射（纯 Java，可离线测）、
 * 上屏（宿主按各版本 API 灌纹理 + 画一个 quad）。</p>
 *
 * <p>产物是直通 alpha 的 ARGB（a&lt;&lt;24|r&lt;&lt;16|g&lt;&lt;8|b，rgb 存满强度光色），
 * 宿主灌进动态纹理后以一个 quad 垫在文字下面，加色混合 —— 光晕是「照亮」背景，
 * 而不是像 alpha 混合那样把背景压成灰雾。文字本体照常第二遍画，晕永远垫底。</p>
 *
 * <p>两个入口：{@link #bake} 从<b>文字</b>出发（原版像素字样式，这里自己光栅化）；
 * {@link #bakeFromMask} 吃<b>现成掩码</b>（现代渲染样式，字形已由渲染层烘好，
 * 与墨格逐像素同源，不再重复光栅化）。缓存按内容 + 参数 + 算法版本作键：逐字
 * 渐显期间前缀每 ~180ms 变一次，一次重烘 &= 一次小位图光栅化（&lt;0.2ms）。</p>
 */
public final class RowGlowSprite {

    /**
     * 缓存键的算法版本：管线或参数变动时 +1，防旧像素跨版本复用
     * （换算法后同一串文字的缓存键必须失效，否则会一直显示上一版的晕）。
     */
    private static final String CACHE_VERSION = "v2";

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
        String key = CACHE_VERSION + "|" + text + "|" + Integer.toHexString(glowColor & 0xFFFFFF)
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

        // 外扩按金字塔最外层的 3σ 预留（设计像素）。留白口径与烘焙管线共用同一个
        // 函数，两边不会漂移；位图比字面四周大 pad，上屏 quad 同样放大，晕不被裁边。
        int pad = maxPad(strength, spread);
        int pw = Math.max(8, ((int) Math.ceil(widthPx2) + pad * 2) * SS);
        int ph = Math.max(8, ((int) Math.ceil(heightPx2) + pad * 2) * SS);
        if (pw > 4096 || ph > 256) return null;

        try {
            if (bitmap == null || pw != width || ph != height) {
                closeBitmap();
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
            // 先把字形光栅成一张<b>纯覆盖度</b>的掩码（不模糊）：辛烷金字塔在
            // bakeRowGlowPixels 里自己做多级低通，这里再高斯一遍等于重复模糊、
            // 且会把金字塔最外层需要的细节一起抹掉。sigma 传 0 = 只有抗锯齿边缘。
            font.drawGlyphsOn(canvas, text, pad, pad, 0xFFFFFFFF, 255, 0f, null);
            float[] mask = readCoverage(pw, ph);
            if (mask == null) return null;

            int[] px = TextGlow.bakeRowGlowPixels(mask, pw, ph,
                    (float) font.sizePx, spread, strength, glowColor & 0xFFFFFF);
            if (px == null) return null;
            cachedPixels = px;
            cachedKey = key;
            return cachedPixels;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 现代渲染样式的行级辉光：直接吃现成的字形覆盖掩码（渲染层已有 Skija 超采样
     * 烘出的整行位图），不再重新光栅化一遍。掩码与墨格逐像素同源，晕和字严格对齐。
     *
     * @param mask     覆盖度（0..1），长 w×h
     * @param fontPx   掩码所属字号（像素）
     * @param padPx    掩码四周已经预留的空白（设计像素）；0 = 掩码紧贴字面
     */
    public static int[] bakeFromMask(float[] mask, int w, int h, float fontPx,
                                     float strength, float spread, int glowColor,
                                     float padPx) {
        if (mask == null || w <= 0 || h <= 0 || mask.length < w * h || strength <= 0.02f) {
            return null;
        }
        String key = CACHE_VERSION + "|m|" + w + "x" + h + "|" + maskHash(mask, w * h)
                + "|" + Integer.toHexString(glowColor & 0xFFFFFF)
                + "|" + Math.round(strength * 64f)
                + "|" + Math.round(spread * 16f);
        if (key.equals(cachedKey) && cachedPixels != null) {
            return cachedPixels;
        }
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, fontPx, spread, strength,
                glowColor & 0xFFFFFF);
        if (px == null) return null;
        width = w;
        height = h;
        // 掩码自带留白时，quad 宽度按掩码宽 / SS 反推，textW2x 存的是「掩码宽 - 两侧 pad」
        textW2x = Math.max(0f, (w / (float) SS - padPx * 2f)) * SS;
        cachedPixels = px;
        cachedKey = key;
        return cachedPixels;
    }

    /** 掩码指纹：只采样少量点，缓存键只需要区分「内容变了」而不是精确哈希。 */
    private static long maskHash(float[] mask, int n) {
        long h = 0xCBF29CE484222325L;
        int step = Math.max(1, n / 512);
        for (int i = 0; i < n; i += step) {
            h ^= (long) (mask[i] * 4096f);
            h *= 0x100000001B3L;
        }
        return h;
    }

    /** 光晕位图相对字面 bbox 的左上外扩（设计像素）：quad 对位用。 */
    public static float padPx(float strength, float spread) {
        return maxPad(strength, spread);
    }

    /** 留白统一由管线给（与烘焙同一口径），强度先折算进字号——强度只影响亮度不影响半径。 */
    private static int maxPad(float strength, float spread) {
        return Math.max(4, TextGlow.rowGlowPadding(WorldLyricsState.LYRIC_SIZE * SS, spread));
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

    /**
     * 从刚画好的位图取<b>覆盖度</b>掩码（0..1）。位图是全白字 + 抗锯齿边缘，
     * 所以 alpha 通道就是覆盖度，rgb 恒为白不用看 —— 取 alpha 归一即可。
     */
    private static float[] readCoverage(int w, int h) {
        if (bitmap == null) return null;
        try {
            byte[] src = bitmap.readPixels();
            if (src == null || src.length < w * h * 4) return null;
            float[] mask = new float[w * h];
            for (int i = 0, o = 3; i < mask.length; i++, o += 4) {
                mask[i] = (src[o] & 0xFF) / 255f;
            }
            return mask;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只释放光晕位图（换尺寸时用，保留派生字体）。 */
    private static void closeBitmap() {
        if (bitmap != null) {
            bitmap.close();
            bitmap = null;
        }
        if (canvas != null) {
            canvas.close();
            canvas = null;
        }
        width = 0;
        height = 0;
    }

    /** 释放原生位图与派生字体（渲染关闭 / 字体切换时调）。 */
    public static void close() {
        closeBitmap();
        bakeFont = null;
        cachedKey = "";
        cachedPixels = null;
        textW2x = 0;
    }
}
