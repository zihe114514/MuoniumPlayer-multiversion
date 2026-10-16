package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.TextGlow;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行歌词 → GL 纹理的栅格化缓存（3D 世界歌词的「平滑字体」档）。
 *
 * <h2>为什么走离屏 Skia 位图 + MC 原生 DynamicTexture</h2>
 * 世界渲染事件发生在 Minecraft 自己的帧中段，Skija 的 DirectContext 正被主合成层占用；
 * 在那里另开 GPU Surface 会重排 Skia 内部的 GL 状态，与宿主帧的合成次序互踩
 * （{@code SkijaImages} 的类注释记录过同类坑）。所以文字在 CPU 离屏位图上画好、读回
 * int[]，走 MC 原生 {@code DynamicTexture}（TextureManager 注册、自动上传、按路径寻址），
 * 树侧的 RenderType 直接按路径采样 —— 与命名牌/地图同一条资源路径。
 *
 * <h2>发光怎么进纹理</h2>
 * 光晕在栅格化时用 {@link TextGlow#drawOn}（三层高斯、加色混合）直接画进位图，
 * 四周按 {@link TextGlow#padding} 留白 —— 世界侧一次贴图就带着光，不需要后处理。
 * <b>strength 在这里只传一次</b>（drawOn 的参数就是最终强度），不得再乘
 * —— docs/歌词发光-高斯重写.md 记过 Apple 样式双乘的前车之鉴。
 *
 * <h2>缓存与淘汰</h2>
 * 键 = 文本 + 字号 + 颜色 + 发光参数。LRU 上限 48 张（≈ 10MB 显存），
 * 行还在窗口里就持续复用，切歌/翻行时旧纹理被自然挤掉并从 TextureManager 注销。
 */
public final class LyricTextureCache {

    /** 纹理上限。每张平均 512×96 ≈ 0.2MB，48 张 ≈ 10MB 显存。 */
    private static final int MAX_ENTRIES = 48;

    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true);

    /** MC 纹理注册的回调（树侧装配：TextureManager.register(String, DynamicTexture)）。 */
    public interface TextureHost {
        /** 注册一张动态纹理，返回寻址用的纹理 id 字符串（MC 会自动拼出 ResourceLocation）。 */
        String register(java.awt.image.BufferedImage image, int width, int height);

        /** 注销（纹理淘汰时调用）。 */
        void unregister(String id);
    }

    /** 树侧纹理宿主；null 时整条 3D 歌词纹理路径不可用（渲染层退像素字兜底）。 */
    private static volatile TextureHost textureHost;

    /** 3D 歌词的栅格化字体（用户当前字体的 32px 派生档）。懒建。 */
    private static volatile CFontRenderer worldFont;

    private LyricTextureCache() {
    }

    /** 一张贴好的纹理。宽高是像素；世界尺寸由渲染层按宽高比换算。 */
    public static final class Entry {
        /** 树侧纹理 id（MC DynamicTexture 路径的尾段）。渲染层经 TextureHost 寻址。 */
        public final String textureId;
        public final int width;
        public final int height;
        /** 文字净宽/净高（不含发光留白），渲染层按它换算世界尺寸。 */
        public final int inkWidth;
        public final int inkHeight;
        /** 四周留白（像素）：四边形原点到文字墨迹的偏移。 */
        public final int pad;

        Entry(String textureId, int width, int height, int inkWidth, int inkHeight, int pad) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.inkWidth = inkWidth;
            this.inkHeight = inkHeight;
            this.pad = pad;
        }
    }

    /** 树侧启动时装配（1.21.1 的 MuoniumPlayerClient.init 里）。 */
    public static void setTextureHost(TextureHost host) {
        textureHost = host;
    }

    public static TextureHost textureHost() {
        return textureHost;
    }

    /**
     * 取一张行纹理，没有就栅格化。
     *
     * @param text         行文本（Split lines 拆分后的半句）
     * @param fontPx       字号（GUI 像素）
     * @param colorRgb     文字颜色（低 24 位）
     * @param glowStrength 发光强度 0..1（0 = 不发光）
     * @param glowSpread   发光扩散倍率
     * @param glowColorRgb 发光颜色（低 24 位）
     */
    public static Entry get(String text, float fontPx, int colorRgb,
                            float glowStrength, float glowSpread, int glowColorRgb) {
        TextureHost host = textureHost;
        if (host == null || text == null || text.isEmpty()) return null;
        String key = cacheKey(text, fontPx, colorRgb, glowStrength, glowSpread, glowColorRgb);
        synchronized (CACHE) {
            Entry cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        Entry made = rasterize(host, text, fontPx, colorRgb, glowStrength, glowSpread, glowColorRgb);
        if (made == null) return null;
        synchronized (CACHE) {
            CACHE.put(key, made);
            evictLocked();
        }
        return made;
    }

    /** 发光参数 / 字体 / 颜色变化后整批作废（设置 UI 拖滑块时调用）。 */
    public static void invalidateAll() {
        List<Entry> dead;
        synchronized (CACHE) {
            dead = new ArrayList<>(CACHE.values());
            CACHE.clear();
        }
        for (Entry e : dead) {
            dispose(e);
        }
    }

    private static String cacheKey(String text, float fontPx, int colorRgb,
                                   float glowStrength, float glowSpread, int glowColorRgb) {
        // strength/spread 量化（1/64、1/16），拖滑块的中间值不会每个都造一张
        return text
                + "|" + Math.round(fontPx * 4f)
                + "|" + Integer.toHexString(colorRgb)
                + "|" + Math.round(glowStrength * 64f)
                + "|" + Math.round(glowSpread * 16f)
                + "|" + Integer.toHexString(glowColorRgb);
    }

    private static Entry rasterize(TextureHost host, String text, float fontPx, int colorRgb,
                                   float glowStrength, float glowSpread, int glowColorRgb) {
        CFontRenderer font = worldFont();
        if (font == null || font.fontHeight <= 0) return null;

        int pad = glowStrength > 0.004f ? Math.max(2, TextGlow.padding(font, glowSpread)) : 2;
        int inkWidth = Math.max(1, (int) Math.ceil(font.getStringWidthD(text)));
        int inkHeight = Math.max(1, font.lineBox());
        int width = inkWidth + pad * 2;
        int height = inkHeight + pad * 2;
        if (width > 4096 || height > 512) {
            // 超长行放弃栅格化，渲染层跳过
            return null;
        }

        if (!com.muoniumplayer.render.skija.SkijaNatives.ensureLoaded()) return null;

        Surface surface = Surface.makeRasterN32Premul(width, height);
        if (surface == null) return null;
        Canvas canvas = surface.getCanvas();
        canvas.clear(0x00000000);

        // 基线 = pad + baselineOffset。CFontRenderer 的绘制口径正是「y 为基线」，
        // 与宿主帧一致，所以这里给同一口径的 y。
        float baseline = pad + font.getBaselineOffset();
        int argb = 0xFF000000 | (colorRgb & 0xFFFFFF);
        try {
            if (glowStrength > 0.004f) {
                TextGlow.drawOn(canvas, font, text, pad, baseline, glowColorRgb,
                        1.0, glowStrength, glowSpread);
            }
            font.drawGlyphsOn(canvas, text, pad, baseline, argb, 255, 0f, null);
        } finally {
            surface.close();
        }

        int[] pixels = readPixelsArgb(surface, width, height);
        if (pixels == null) return null;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        String textureId;
        try {
            textureId = host.register(image, width, height);
        } catch (Throwable t) {
            return null;
        }
        if (textureId == null) return null;
        return new Entry(textureId, width, height, inkWidth, inkHeight, pad);
    }

    /**
     * Skija N32 Premul 位图 → ARGB int[]（BufferedImage 口径）。
     * N32 内存序 R,G,B,A，按字节读出后要重组成 ARGB。
     */
    private static int[] readPixelsArgb(Surface surface, int width, int height) {
        try (Bitmap bitmap = new Bitmap()) {
            if (!bitmap.allocPixels(surface.getImageInfo())
                    || !surface.readPixels(bitmap, 0, 0)) {
                return null;
            }
            byte[] src = bitmap.readPixels();
            if (src == null || src.length < width * height * 4) return null;
            int[] out = new int[width * height];
            for (int i = 0; i < out.length; i++) {
                int o = i * 4;
                int r = src[o] & 0xFF;
                int g = src[o + 1] & 0xFF;
                int b = src[o + 2] & 0xFF;
                int a = src[o + 3] & 0xFF;
                out[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 3D 歌词栅格化字体：用户当前字体族（pf25bold 同族）的 32px 派生档。 */
    private static CFontRenderer worldFont() {
        CFontRenderer f = worldFont;
        if (f != null) return f;
        synchronized (LyricTextureCache.class) {
            if (worldFont == null) {
                CFontRenderer base = FontManager.pf25bold;
                if (base != null && base.font != null) {
                    try {
                        worldFont = new CFontRenderer(base.font, 32f);
                    } catch (Throwable t) {
                        worldFont = base;
                    }
                }
            }
            return worldFont;
        }
    }

    private static void evictLocked() {
        if (CACHE.size() <= MAX_ENTRIES) return;
        Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > MAX_ENTRIES && it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            it.remove();
            dispose(e.getValue());
        }
    }

    private static void dispose(Entry entry) {
        TextureHost host = textureHost;
        if (host != null && entry.textureId != null) {
            try {
                host.unregister(entry.textureId);
            } catch (Throwable ignored) {
            }
        }
    }
}
