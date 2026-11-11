package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.TextGlow;
import com.muoniumplayer.core.management.FontManager;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行级辉光源：把一行文字先光栅成<b>覆盖度掩码</b>，再交给多级金字塔管线
 * （{@link TextGlow#bakeRowGlowPixels}）做成光晕贴图。
 *
 * <p>作用域是<b>单行 bbox</b>：光能不出行，光晕半径相对字高有意义。三件事分开：
 * 光栅化（Skija，拿到干净的字形覆盖度）、金字塔与色调映射（纯 Java，可离线测）、
 * 上屏（宿主按各版本 API 灌纹理 + 画一个 quad）。</p>
 *
 * <p>两个入口：{@link #bake} 从文字出发；{@link #bakeFromMask} 吃现成掩码。
 * 活跃行、淡出行和预摆行共用有界 LRU，缓存命中时同时恢复该行的尺寸元数据，
 * 不会为同帧不同的行反复光栅化、重跑金字塔。</p>
 */
public final class RowGlowSprite {

    /** 像素算法版本；缓存策略变化不改变像素，不推进此版本。 */
    private static final String CACHE_VERSION = "v2";
    private static final int SS = 2;
    private static final int CACHE_ENTRIES = 8;
    /** int 像素和可选 float 掩码合计最多 16 MiB，不含唯一的光栅暂存位图。 */
    private static final int CACHE_PIXEL_BUDGET = 4 * 1024 * 1024;
    private static final Map<String, CachedSprite> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static int cachedPixelCount;
    private static int atlasFontGeneration = -1;

    private static Bitmap bitmap;
    private static Canvas canvas;
    private static int bitmapWidth, bitmapHeight;
    /** 最近一次成功取出的产物尺寸，与光栅暂存位图尺寸分开。 */
    private static int width, height;
    private static float textW2x;
    private static CFontRenderer bakeFont;
    private static CFontRenderer bakeFontBase;

    private static final class CachedSprite {
        final int[] pixels;
        final float[] mask;
        final int width, height;
        final float textW2x;

        CachedSprite(int[] pixels, float[] mask, int width, int height, float textW2x) {
            this.pixels = pixels;
            this.mask = mask;
            this.width = width;
            this.height = height;
            this.textW2x = textW2x;
        }

        int pixelCount() {
            return this.pixels.length + (this.mask == null ? 0 : this.mask.length);
        }
    }

    private RowGlowSprite() {
    }

    /** 烘一行已显现前缀；像素格式和原金字塔管线保持一致。 */
    public static int[] bake(String text, int glowColor, float strength, float spread) {
        if (text == null || text.isEmpty() || strength <= 0.02f) return null;
        CFontRenderer font = ensureBakeFont();
        if (font == null) return null;
        String key = CACHE_VERSION + "|" + atlasFontGeneration + "|" + text
                + "|" + Integer.toHexString(glowColor & 0xFFFFFF)
                + "|" + Math.round(strength * 64f) + "|" + Math.round(spread * 16f);
        CachedSprite cached = CACHE.get(key);
        if (cached != null) return select(cached);

        float textWidth = (float) font.getStringWidthD(text);
        float widthPx2 = textWidth / SS;
        float heightPx2 = (float) font.fontHeight / SS;
        int pad = maxPad(strength, spread);
        int pw = Math.max(8, ((int) Math.ceil(widthPx2) + pad * 2) * SS);
        int ph = Math.max(8, ((int) Math.ceil(heightPx2) + pad * 2) * SS);
        if (pw > 4096 || ph > 256) return null;

        try {
            if (bitmap == null || pw != bitmapWidth || ph != bitmapHeight) {
                closeBitmap();
                Bitmap bmp = new Bitmap();
                if (!bmp.allocPixels(ImageInfo.makeN32Premul(pw, ph))) {
                    bmp.close();
                    return null;
                }
                bitmap = bmp;
                canvas = new Canvas(bmp);
                bitmapWidth = pw;
                bitmapHeight = ph;
            }
            canvas.clear(0x00000000);
            // 只画覆盖度，不重复模糊；位置、字号与像素算法不变。
            font.drawGlyphsOn(canvas, text, pad, pad, 0xFFFFFFFF, 255, 0f, null);
            float[] mask = readCoverage(pw, ph);
            if (mask == null) return null;
            int[] px = TextGlow.bakeRowGlowPixels(mask, pw, ph,
                    font.sizePx, spread, strength, glowColor & 0xFFFFFF);
            if (px == null) return null;
            return remember(key, px, null, pw, ph, textWidth);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 现成掩码入口；命中时不重复跑金字塔，也不借用别行的尺寸。 */
    public static int[] bakeFromMask(float[] mask, int w, int h, float fontPx,
                                     float strength, float spread, int glowColor,
                                     float padPx) {
        long count = (long) w * h;
        if (mask == null || w <= 0 || h <= 0 || count > mask.length || strength <= 0.02f) return null;
        int n = (int) count;
        String key = CACHE_VERSION + "|m|" + w + "x" + h + "|" + maskHash(mask, n)
                + "|" + Integer.toHexString(glowColor & 0xFFFFFF)
                + "|" + Math.round(strength * 64f) + "|" + Math.round(spread * 16f)
                + "|" + Float.floatToIntBits(fontPx) + "|" + Float.floatToIntBits(padPx);
        CachedSprite cached = CACHE.get(key);
        // 指纹只作索引，完整比较防止采样碰撞把改过的笔画误判成缓存命中。
        if (cached != null && cached.mask != null
                && Arrays.equals(mask, 0, n, cached.mask, 0, cached.mask.length)) {
            return select(cached);
        }
        int[] px = TextGlow.bakeRowGlowPixels(mask, w, h, fontPx, spread, strength,
                glowColor & 0xFFFFFF);
        if (px == null) return null;
        float textWidth = Math.max(0f, (w / (float) SS - padPx * 2f)) * SS;
        return remember(key, px, mask, w, h, textWidth);
    }

    private static int[] select(CachedSprite sprite) {
        width = sprite.width;
        height = sprite.height;
        textW2x = sprite.textW2x;
        return sprite.pixels;
    }

    private static int[] remember(String key, int[] pixels, float[] mask,
                                  int w, int h, float textWidth) {
        int count = w * h;
        long weight = (long) pixels.length + (mask == null ? 0 : count);
        CachedSprite sprite = new CachedSprite(pixels,
                mask == null || weight > CACHE_PIXEL_BUDGET ? null : Arrays.copyOf(mask, count),
                w, h, textWidth);
        if (weight <= CACHE_PIXEL_BUDGET) {
            CachedSprite old = CACHE.put(key, sprite);
            if (old != null) cachedPixelCount -= old.pixelCount();
            cachedPixelCount += sprite.pixelCount();
            Iterator<CachedSprite> it = CACHE.values().iterator();
            while (CACHE.size() > CACHE_ENTRIES || cachedPixelCount > CACHE_PIXEL_BUDGET) {
                cachedPixelCount -= it.next().pixelCount();
                it.remove();
            }
        }
        return select(sprite);
    }

    private static long maskHash(float[] mask, int n) {
        long hash = 0xCBF29CE484222325L;
        int step = Math.max(1, n / 512);
        for (int i = 0; i < n; i += step) {
            hash ^= (long) (mask[i] * 4096f);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    public static float padPx(float strength, float spread) {
        return maxPad(strength, spread);
    }

    private static int maxPad(float strength, float spread) {
        return Math.max(4, TextGlow.rowGlowPadding(WorldLyricsState.LYRIC_SIZE * SS, spread));
    }

    public static int width() {
        return width / SS;
    }

    public static int height() {
        return height / SS;
    }

    public static float textWidth() {
        return textW2x / SS;
    }

    /** 界面正文 50px 派生档，字体替换和热接入回退链都必须失效。 */
    private static CFontRenderer ensureBakeFont() {
        CFontRenderer base = FontManager.pf25bold;
        if (base == null || base.fontHeight <= 0) return null;
        int generation = LyricGlyphAtlas.fontGeneration();
        if (atlasFontGeneration != generation || (bakeFont != null && bakeFontBase != base)) {
            close();
            atlasFontGeneration = generation;
        }
        if (bakeFont == null) {
            try {
                bakeFont = base.deriveAtSize(WorldLyricsState.LYRIC_SIZE * 5);
                bakeFontBase = base;
            } catch (Throwable t) {
                return null;
            }
        }
        return bakeFont;
    }

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

    private static void closeBitmap() {
        if (canvas != null) {
            canvas.close();
            canvas = null;
        }
        if (bitmap != null) {
            bitmap.close();
            bitmap = null;
        }
        bitmapWidth = 0;
        bitmapHeight = 0;
    }

    /** 释放缓存与原生资源；由字体变更的公共入口统一调用。 */
    public static void close() {
        closeBitmap();
        if (bakeFont != null) bakeFont.close();
        bakeFont = null;
        bakeFontBase = null;
        CACHE.clear();
        cachedPixelCount = 0;
        atlasFontGeneration = -1;
        width = 0;
        height = 0;
        textW2x = 0;
    }
}
