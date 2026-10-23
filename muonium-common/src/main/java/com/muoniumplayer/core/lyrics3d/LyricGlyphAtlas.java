package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.TextGlow;
import com.muoniumplayer.render.skija.SkijaNatives;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D 世界歌词「现代化风格」的字形图集 —— 每个字符一格，Skija 高分辨率烘焙。
 *
 * <h2>为什么是逐字图集而不是整行贴图</h2>
 * 渲染编排器（{@link WorldLyricsRenderer#drawRow}）的逐字循环是行为核心：逐字淡入、
 * word pop 的按词回弹、入出场的每字落位都发生在每个字形自己的变换里。整行贴图会把
 *
 * <h2>烘焙与坐标口径</h2>
 * <p>每个字符用烘焙字体（{@link #bakeFont}，界面正文字体的
 * {@code LYRIC_SIZE * BAKE_SCALE = 50px} 派生档，见 {@link CFontRenderer#deriveAtSize}）
 * 在离屏 Skija 位图上光栅化；世界侧绘制时除回 {@link #BAKE_SCALE} 缩到设计像素 ——
 * 5 倍超采样，放大不糊、缩小发丝级抗锯齿。</p>
 *
 * <p>格子内部布局（烘焙像素）：四周 {@code pad} 留白，字位框（lineBox）从
 * {@code (pad, pad)} 开始 —— CFontRenderer 的绘制口径是「y 为行顶参考线、内部按
 * y-2 落基线」，所以传 {@code yTop = pad + 2} 让墨迹顶正好落在 pad。
 * 格子的 (inkX, inkY) = (pad, pad) 就是「字形顶点」在格内的位置，世界侧把
 * billboard 上的字形坐标 (x, y)（buildGlyphs 的字顶口径）对到这一格的 (inkX, inkY)。</p>
 *
 * <p>发光在栅格化时用 {@link TextGlow#drawOn}（三层高斯、加色混合）烘进同一张格子，
 * 四周按 {@link TextGlow#padding} 留白。<b>strength 在这里只传一次</b>——
 * docs/歌词发光-高斯重写.md 记过 Apple 样式双乘的前车之鉴。颜色烘死在像素里
 * （ink 用 inkRgb、glow 用 glowColorRgb），进出场 alpha 由世界侧顶点色承担。</p>
 *
 * <h2>页与淘汰</h2>
 * 页 LRU（按整页逐出，不回收半空页的碎片）。像素留在 Java 堆，纹理的创建/上传/删除
 * 经 {@link Host} 由树侧绑定本树 GL —— 本类纯 CPU、纯 Java，五树共享。</p>
 */
public final class LyricGlyphAtlas {

    /** 世界歌词设计字号（文字像素，billboard 内坐标口径），WorldLyricsState.LYRIC_SIZE。 */
    private static final float DESIGN_SIZE = WorldLyricsState.LYRIC_SIZE;

    /** 烘焙超采样倍率：50px 光栅化、绘制时 ÷5。 */
    public static final int BAKE_SCALE = 5;

    /** 页边长（烘焙像素）。CJK 50px 字形连发光留白约 150px 见方，一页约 36 格。 */
    public static final int PAGE_SIZE = 1024;

    /** 页上限（每页 4MB 显存，8 页 ≈ 32MB 封顶）。LRU 逐出。 */
    public static final int MAX_PAGES = 8;

    /** 无发光时的最小留白（烘焙像素）——防边缘裁切的最小余量。 */
    private static final int MIN_PAD_BAKE = 2 * BAKE_SCALE;

    /** 树侧纹理宿主；null = 现代档不可用（渲染层回退像素字）。 */
    public interface Host {
        /** 建一个 width×height 的空页（内容须为透明黑），返回树侧寻址句柄。 */
        Object createPage(int width, int height);

        /** 把一格像素上传到页的 (x,y)；pixelsAbgr 是「可直传 GL_BGRA/
         *  UNSIGNED_INT_8_8_8_8_REV」的 ARGB int（与项目 DynamicTexture 同口径）。 */
        void uploadCell(Object page, int x, int y, int w, int h, int[] pixelsArgb);

        /** 删页并释放显存。 */
        void deletePage(Object page);
    }

    private static volatile Host host;

    /** 烘焙字体（50px 派生档）。懒建；字体切换（{@link #onFontChanged}）后重建。 */
    private static volatile CFontRenderer bakeFont;

    private static final Object LOCK = new Object();
    private static final List<Page> PAGES = new ArrayList<>();
    /** 缓存键 → 格子。 */
    private static final Map<String, GlyphCell> CELLS = new LinkedHashMap<>(256);
    /** 字体世代：invalidateAll 自增，进缓存键防跨世代复用。 */
    private static int fontGeneration;

    /** 帧内烘焙预算：同一帧最多烘焙多少格（渲染线程帧内同步烘焙的掉帧闸）。 */
    private static final int BAKES_PER_FRAME_BUDGET = 4;
    /** 预算记账（nanoTime 值天然唯一，当帧标记用）。 */
    private static long bakeFrameEndNanos;
    private static int bakesThisFrame;
    /** 上次页逐出的时刻（防抖：刚逐出过就不再建新页）。 */
    private static long lastEvictNanos;

    private LyricGlyphAtlas() {
    }

    public static void setHost(Host h) {
        host = h;
    }

    /**
     * 图集是否可用（宿主装配 + 烘焙字体就绪）。绘光模式用它区分
     * 「预算内暂时没烘出来」（跳过该字，等下一帧补齐）与「图集整体不可用」
     * （回退像素字）—— 后者才该回退。
     */
    public static boolean isFunctional() {
        synchronized (LOCK) {
            ensureBakeFontLocked();
            return host != null && bakeFont != null && bakeFont.fontHeight > 0;
        }
    }

    public static Host host() {
        return host;
    }

    /** 一格字形。坐标全部是烘焙像素；世界侧除以 {@link #BAKE_SCALE} 得设计像素。 */
    public static final class GlyphCell {
        /** 树侧页句柄（Host.createPage 的返回值）。 */
        public final Object page;
        /** 格子在页内的像素框（UV = 框 ÷ PAGE_SIZE）。 */
        public final int x, y, w, h;
        /** 字形顶点在格内的位置 = 留白 (pad, pad)。世界侧：quad 左上 = (x - inkX, y - inkY)。 */
        public final int inkX, inkY;
        /** 字形 advance（烘焙像素）——绘制后 x 的前进量，与量宽同口径。 */
        public final float advance;
        /** 字位框高（烘焙像素，= 烘焙字体 lineBox），诊断用。 */
        public final int inkHeight;

        GlyphCell(Object page, int x, int y, int w, int h,
                  int inkX, int inkY, float advance, int inkHeight) {
            this.page = page;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.inkX = inkX;
            this.inkY = inkY;
            this.advance = advance;
            this.inkHeight = inkHeight;
        }
    }

    /** shelf 打包的一个页。包内可见供测试。 */
    static final class Page {
        final Object handle;
        final int width, height;
        /** shelf 游标：当前 shelf 的起始 y 与高度、下一格的 x。 */
        int shelfX;
        int shelfY;
        int shelfH;
        /** LRU 触点：最后一次有格子被取用的纳秒时间。 */
        long lastUseNanos;

        Page(Object handle, int width, int height) {
            this.handle = handle;
            this.width = width;
            this.height = height;
        }

        /**
         * 在 (w,h) 格子的位置上探一个落点，不真正占用；放不下返回 null。
         * 返回 {x, y}。真正推进游标由 {@link #commit} 做 —— 烘焙成功才占用，
         * 失败不留洞。
         */
        int[] probe(int w, int h) {
            if (w > width || h > height) return null;
            // 当前 shelf 放得下？
            if (shelfX + w <= width && shelfY + Math.max(shelfH, h) <= height) {
                return new int[] {shelfX, shelfY};
            }
            // 开新 shelf？
            if (shelfY + shelfH + h <= height) {
                return new int[] {0, shelfY + shelfH};
            }
            return null;
        }

        void commit(int x, int y, int w, int h) {
            if (y != shelfY) {
                // 开了新 shelf
                shelfY = y;
                shelfH = h;
                shelfX = w;
            } else {
                shelfX = x + w;
                if (shelfH < h) shelfH = h;
            }
        }
    }

    /**
     * 取一个字形格子，没有就烘焙。任何失败返回 null（渲染层回退像素字）。
     *
     * @param ch           字符
     * @param inkRgb       本体颜色（低 24 位，烘死进像素）
     * @param glowStrength 发光强度 0..1（0 = 不发光）
     * @param glowSpread   发光扩散倍率
     * @param glowColorRgb 发光颜色（低 24 位）
     */
    public static GlyphCell getGlyph(char ch, int inkRgb,
                                     float glowStrength, float glowSpread, int glowColorRgb) {
        Host h = host;
        if (h == null) return null;
        synchronized (LOCK) {
            ensureBakeFontLocked();
            CFontRenderer font = bakeFont;
            if (font == null || font.fontHeight <= 0) return null;

            String key = cacheKey(ch, inkRgb, glowStrength, glowSpread, glowColorRgb);
            GlyphCell cell = CELLS.get(key);
            if (cell != null) {
                touchLocked(cell);
                return cell;
            }

            // ---- 帧内烘焙预算 ----
            // 烘焙是 Skija 高斯模糊 + 像素读回 + 新页 4MB 分配，一格毫秒级。一行首次
            // 出现 = 一帧内 N 次连续烘焙，N 大时单帧掉到个位数 fps。同一帧超过预算
            // 后不再开新烘焙，返回 null（渲染层已有像素字回退），剩余字由后续帧逐步
            // 补齐 —— 字逐个“长”出来比一次卡死观感好得多。
            long now = System.nanoTime();
            if (bakeFrameEndNanos != now) {
                bakeFrameEndNanos = now;
                bakesThisFrame = 0;
            }
            if (bakesThisFrame >= BAKES_PER_FRAME_BUDGET) {
                return null;
            }
            bakesThisFrame++;

            GlyphCell made = bakeLocked(h, font, key, ch, inkRgb, glowStrength, glowSpread, glowColorRgb);
            if (made == null) return null;
            CELLS.put(key, made);
            return made;
        }
    }

    /**
     * 单字符 advance（设计像素、烘焙字体口径）。世界侧量宽用 —— 与逐字绘制完全同源，
     * 行宽 = Σ 单字 advance，不会漂移。字体不可用时返回 -1（调用方回退像素度量）。
     */
    public static float designAdvance(char ch) {
        synchronized (LOCK) {
            ensureBakeFontLocked();
            CFontRenderer font = bakeFont;
            if (font == null || font.fontHeight <= 0) return -1f;
            float w = (float) font.getStringWidthD(String.valueOf(ch));
            return w / BAKE_SCALE;
        }
    }

    /** 界面字体切换后调用（FontManager.retargetTextFonts / restoreBuiltinTextFonts）。 */
    public static void onFontChanged() {
        invalidateAll();
    }

    /** 整批作废（释放全部页纹理 + 清格子表 + 重建烘焙字体）。 */
    public static void invalidateAll() {
        Host h = host;
        List<Page> dead;
        synchronized (LOCK) {
            dead = new ArrayList<>(PAGES);
            PAGES.clear();
            CELLS.clear();
            bakeFont = null;
            fontGeneration++;
        }
        if (h != null) {
            for (Page p : dead) {
                try {
                    h.deletePage(p.handle);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== 内部 ====================

    /** 懒建烘焙字体：界面正文字体（pf25bold 槽）的 50px 派生档，随字体切换重建。 */
    private static void ensureBakeFontLocked() {
        if (bakeFont != null) return;
        CFontRenderer base = FontManager.pf25bold;
        if (base == null || base.fontHeight <= 0) return;
        try {
            bakeFont = base.deriveAtSize(DESIGN_SIZE * BAKE_SCALE);
        } catch (Throwable t) {
            bakeFont = null;
        }
    }

    /** 缓存键：字符 + 颜色 + 发光参数（量化防滑块抖动）+ 字体世代。 */
    static String cacheKey(char ch, int inkRgb, float glowStrength, float glowSpread, int glowColorRgb) {
        return ch
                + "|" + Integer.toHexString(inkRgb & 0xFFFFFF)
                + "|" + Math.round(clamp01(glowStrength) * 64f)
                + "|" + Math.round(clamp01(glowSpread / 1.8f) * 16f)
                + "|" + Integer.toHexString(glowColorRgb & 0xFFFFFF)
                + "|" + fontGeneration;
    }

    private static void touchLocked(GlyphCell cell) {
        long now = System.nanoTime();
        for (Page p : PAGES) {
            if (p.handle == cell.page) {
                p.lastUseNanos = now;
                return;
            }
        }
    }

    /** 烘焙一个字符。失败（Skija 缺位/页满/超尺寸/上传失败）返回 null。 */
    private static GlyphCell bakeLocked(Host h, CFontRenderer font, String key, char ch,
                                        int inkRgb, float glowStrength, float glowSpread, int glowColorRgb) {
        if (!SkijaNatives.ensureLoaded()) return null;

        // advance 与字位框：全部取自烘焙字体自己的度量（与 drawGlyphsOn 同一引擎），
        // 单位是烘焙像素（该字体 sizePx = 50 的 GUI 像素即烘焙像素）。
        float advanceBake = (float) font.getStringWidthD(String.valueOf(ch));
        if (advanceBake <= 0.01f) return null; // 空白/不可见字符不进图集
        int inkH = Math.max(1, font.lineBox());
        if (inkH <= 0) return null;

        // 发光留白（烘焙像素）。TextGlow.padding 的口径 = 3σ × 字号 × spread，
        // 传入的就是 50px 的烘焙字体 —— 出来直接是烘焙像素。
        int glowPad = glowStrength > 0.004f ? TextGlow.padding(font, glowSpread) : 0;
        int pad = Math.max(MIN_PAD_BAKE, glowPad);
        int cellW = (int) Math.ceil(advanceBake) + pad * 2;
        int cellH = inkH + pad * 2;
        if (cellW > PAGE_SIZE || cellH > PAGE_SIZE) return null;

        // ---- 占位（烘焙成功才提交，失败不留洞）----
        Page page = null;
        int cellX = 0, cellY = 0;
        for (Page p : PAGES) {
            int[] at = p.probe(cellW, cellH);
            if (at != null) {
                page = p;
                cellX = at[0];
                cellY = at[1];
                break;
            }
        }
        if (page == null) {
            // 防抖：刚逐出过页（<1s）说明工作集已超图集容量，再建页只会陷入
            // 「建 4MB 页 → 挤掉整页活格 → 又建页」的抖动。直接放弃这一格，
            // 渲染层回退像素字 —— 歌唱到哪补到哪，比来回砸显存好。
            if (PAGES.size() >= MAX_PAGES) {
                if (System.nanoTime() - lastEvictNanos < 1_000_000_000L) {
                    return null;
                }
                evictLocked();
                lastEvictNanos = System.nanoTime();
            }
            Object handle;
            try {
                handle = h.createPage(PAGE_SIZE, PAGE_SIZE);
            } catch (Throwable t) {
                return null;
            }
            if (handle == null) return null;
            page = new Page(handle, PAGE_SIZE, PAGE_SIZE);
            PAGES.add(page);
            int[] at = page.probe(cellW, cellH);
            if (at == null) { // 单格超页（不可能，前面已拦），防御
                try {
                    h.deletePage(handle);
                } catch (Throwable ignored) {
                }
                PAGES.remove(page);
                return null;
            }
            cellX = at[0];
            cellY = at[1];
        }

        // ---- 离屏栅格化（纯 CPU，不碰 GPU/Skija GPU Surface）----
        int[] pixelsArgb;
        try (Bitmap bitmap = new Bitmap()) {
            ImageInfo info = ImageInfo.makeN32Premul(cellW, cellH);
            if (!bitmap.allocPixels(info)) return null;
            Canvas canvas = new Canvas(bitmap);
            try {
                canvas.clear(0x00000000);
                // yTop = pad + 2：CFontRenderer 内部按 y-2 落基线，墨迹顶正好落在 pad。
                float yTop = pad + 2f;
                int argb = 0xFF000000 | (inkRgb & 0xFFFFFF);
                if (glowStrength > 0.004f) {
                    // 强度只传这一次（TextGlow 内部按层分配透明度）——不得再乘
                    TextGlow.drawOn(canvas, font, String.valueOf(ch),
                            pad, yTop, glowColorRgb, 1.0, glowStrength, glowSpread);
                }
                font.drawGlyphsOn(canvas, String.valueOf(ch), pad, yTop, argb, 255, 0f, null);
            } finally {
                canvas.close();
            }
            pixelsArgb = readPixelsArgb(bitmap, cellW, cellH);
        } catch (Throwable t) {
            return null;
        }
        if (pixelsArgb == null) return null;

        // ---- 上传 + 提交占位 ----
        try {
            h.uploadCell(page.handle, cellX, cellY, cellW, cellH, pixelsArgb);
        } catch (Throwable t) {
            return null;
        }
        page.commit(cellX, cellY, cellW, cellH);
        page.lastUseNanos = System.nanoTime();
        return new GlyphCell(page.handle, cellX, cellY, cellW, cellH,
                pad, pad, advanceBake, inkH);
    }

    /** LRU 逐出一页（最久未用的；本帧正用的页 lastUseNanos 新，不会被选中）。 */
    private static void evictLocked() {
        Page oldest = null;
        for (Page p : PAGES) {
            if (oldest == null || p.lastUseNanos < oldest.lastUseNanos) {
                oldest = p;
            }
        }
        if (oldest == null) return;
        PAGES.remove(oldest);
        Iterator<Map.Entry<String, GlyphCell>> it = CELLS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().page == oldest.handle) {
                it.remove();
            }
        }
        Host h = host;
        if (h != null) {
            try {
                h.deletePage(oldest.handle);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * N32 Premul 位图 → ARGB int[]（与项目 DynamicTexture 同口径：ARGB int 直配
     * GL_BGRA + UNSIGNED_INT_8_8_8_8_REV）。N32 在小端机上的内存序是 <b>B,G,R,A</b>
     * 字节流（探针实测：0x80FF4080 → [64,32,128,128]）且<b>预乘</b>，除回 alpha 转成
     * 直通 alpha（文字管线是 (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 混合，吃直通 alpha；
     * 预乘像素会出黑边）。
     */
    static int[] readPixelsArgb(Bitmap bitmap, int width, int height) {
        try {
            byte[] src = bitmap.readPixels();
            if (src == null || src.length < width * height * 4) return null;
            int[] out = new int[width * height];
            for (int i = 0; i < out.length; i++) {
                int o = i * 4;
                int b = src[o] & 0xFF;
                int g = src[o + 1] & 0xFF;
                int r = src[o + 2] & 0xFF;
                int a = src[o + 3] & 0xFF;
                if (a != 0 && a != 255) {
                    // 预乘 → 直通：除回 alpha（±1 的舍入误差肉眼不可见）
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

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
