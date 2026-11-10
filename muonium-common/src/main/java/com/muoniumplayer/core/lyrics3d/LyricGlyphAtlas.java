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
 * 这些全部焊死。现代化风格的参照实现（CjkFont 口径）同样是逐字图集。
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
 * <p>发光走<b>晕/墨分离</b>：墨格（{@link #getInkCell}）只烘字本体、保持锐利；
 * 晕格（{@link #getGlowCell}）只烘高斯光形（{@link TextGlow#drawGlowOn}），渲染层
 * 把晕格 quad 画在墨格之下、以<b>加色</b>混合 —— 光晕真正「照亮」背景而不是像
 * alpha 混合那样把背景压成灰雾。两格以同一 (x,y) 锚点对齐，pad 差就是光晕外圈。
 * <b>strength 在这里只传一次</b>——
 * docs/歌词发光-高斯重写.md 记过 Apple 样式双乘的前车之鉴。颜色烘死在像素里
 * （ink 用 inkRgb、glow 用 glowColorRgb），进出场 alpha 由世界侧顶点色承担。</p>
 *
 * <h2>页与淘汰</h2>
 * <p>格子 shelf-packing 进 1024² 的页，最多 {@link #MAX_PAGES}
 * 页 LRU（按整页逐出，不回收半空页的碎片）。像素留在 Java 堆，纹理的创建/上传/删除
 * 经 {@link Host} 由树侧绑定本树 GL —— 本类纯 CPU、纯 Java，五树共享。</p>
 */
public final class LyricGlyphAtlas {

    /** 世界歌词设计字号（文字像素，billboard 内坐标口径），WorldLyricsState.LYRIC_SIZE。 */
    private static final float DESIGN_SIZE = WorldLyricsState.LYRIC_SIZE;

    /** 烘焙超采样倍率（默认 5×）：运行时可变（画质档位），setter 触发全图集重建。 */
    private static volatile int bakeScale = 5;

    /** 当前烘焙超采样倍率（运行时档位）。热路径每帧每字除一次 —— 读 volatile int。 */
    public static int bakeScale() {
        return bakeScale;
    }

    /** 无发光时的最小留白（烘焙像素）——防边缘裁切的最小余量，随档派生。 */
    private static int minPadBake() {
        return 2 * bakeScale;
    }

    /**
     * 设置烘焙超采样档位（画质档：4=流畅 / 5=标准 / 6=高清 / 7=极致）。
     * 档位变化全量作废图集（含烘焙字体与 advance 缓存）——切档瞬间第一句
     * 字形按帧预算逐格"长出"，与字体切换同一通路，不渐进混合两种分辨率。
     * 同值调用零成本。
     */
    public static void setBakeScale(int scale) {
        int s = Math.max(3, Math.min(8, scale));
        if (s == bakeScale) return;
        bakeScale = s;
        invalidateAll();
    }

    /**
     * 应用「渲染画质」设置：0 = 自动（由树侧按帧缓冲高度解析后传入具体倍率，
     * 4~7 = 手动档直通）。设置界面循环切换与存档加载都走这里 —— 图集只认
     * 最终倍率，不感知"自动/手动"的区分。
     */
    public static void applyQualityTier(int tier) {
        // tier==0 的自动解析由调用方（树侧设置 UI / 帧头同步）完成：
        // 共享层不做分辨率读取（MC 依赖只在版本树的 compat 层），传入具体倍率。
        if (tier >= 4) {
            setBakeScale(tier);
        }
    }

    /** 兼容保留：默认档常量口径（新代码请用 {@link #bakeScale()}）。 */
    public static final int BAKE_SCALE = 5;

    /** 页边长（烘焙像素）。CJK 50px 字形连发光留白约 150px 见方，一页约 36 格。 */
    public static final int PAGE_SIZE = 1024;

    /** 页上限（每页 4MB 显存，8 页 ≈ 32MB 封顶）。LRU 逐出。 */
    public static final int MAX_PAGES = 8;

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
    private static volatile int fontGeneration;

    /** Font/quality epoch shared with dependent sprite and upload caches. */
    public static int fontGeneration() {
        return fontGeneration;
    }

    /**
     * 帧内烘焙预算：同一帧最多烘焙多少格 + 多少毫秒（渲染线程帧内同步烘焙的
     * 掉帧闸）。
     *
     * <p>这三个数字是<b>实测标定</b>的，不是拍的（本机 CJK 50px 墨格：
     * 光栅化 + readPixels ≈ 0.58ms/格；Ultra 晕格 bloom 管线 ≈ 8ms/格）。</p>
     */
    private static final int BAKES_PER_FRAME_BUDGET = 4;
    private static final long BAKE_NANOS_BUDGET = 8_000_000L;
    /**
     * 墨格的独立预算（比晕格宽，但<b>受毫秒闸硬约束</b>）。
     *
     * <p>墨格是<b>不可缺</b>的字形本体：返回 null 时宿主会逐字回退像素字
     * （非绘光档）或静默跳过（绘光档），两种都是肉眼可见的闪烁/吞字 —— 比
     * 掉一帧难看得多。晕格则可以安全丢弃（只丢光晕、不丢字），所以晕格没有
     * 独立加宽。</p>
     *
     * <p><b>为什么从 24 格 / 24ms 收到 8 格 / 5ms</b>：24 格的额度是按旧代码
     * 「单格约 1ms」估的，实测 CJK 墨格 0.58ms 时 24 格仍是 14ms —— 换句首帧
     * 一次性烘完一句生字，单帧直接掉到 ~20fps。这正是「外文歌 + 开翻译换句时
     * 大幅掉帧」的主因：副行整句都是新字形。8 格 × 0.58ms ≈ 4.6ms 落在 5ms
     * 闸内；30 字的一句摊到 4 帧补齐（~66ms），完全淹没在换句的逐字飞行动画
     * （700ms）里，肉眼不可辨。毫秒闸兜住更慢的机器/更大的字号。</p>
     */
    private static final int INK_BAKES_PER_FRAME_BUDGET = 8;
    private static final long INK_BAKE_NANOS_BUDGET = 5_000_000L;
    /**
     * 预算记账。帧边界由 {@link #beginFrame()} 显式重置 —— <b>不能用 nanoTime
     * 当帧标记</b>：之前写的是「每次取格现读 nanoTime，与上次不相同就认为换了
     * 一帧」，而 nanoTime 每次调用天然唯一，条件恒真 → 计数每格都清零，
     * 预算形同虚设（换句首帧一次性同步烘几十格，掉帧尖峰）。
     */
    private static int inkBakesThisFrame;
    private static long inkBakeNanosThisFrame;
    private static int bakesThisFrame;
    private static long bakeNanosThisFrame;
    /** 上次页逐出的时刻（防抖：刚逐出过就不再建新页）。 */
    private static long lastEvictNanos;

    private LyricGlyphAtlas() {
    }

    public static void setHost(Host h) {
        host = h;
    }

    /**
     * 帧头调用（渲染线程，每帧一次）：重置帧内烘焙预算计数。
     *
     * <p>必须由渲染层在每帧开始时显式调用 —— 预算记账没有别的可靠帧边界。
     * 之前的实现拿 {@code System.nanoTime()} 当帧身份（「值与上次不同就是新
     * 帧」），而它每次调用天然唯一，条件恒真，于是每取一格都清零计数：
     * {@link #BAKES_PER_FRAME_BUDGET} 与毫秒闸从未真正生效，换句首帧会把整句
     * 几十格一次性同步烘完（每格含 5× 超采样重采样 + 高斯 bloom），掉帧尖峰。</p>
     *
     * <p>幂等且廉价（两次赋值），重复调用只是提前放行预算。</p>
     */
    public static void beginFrame() {
        synchronized (LOCK) {
            bakesThisFrame = 0;
            bakeNanosThisFrame = 0;
            inkBakesThisFrame = 0;
            inkBakeNanosThisFrame = 0;
        }
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

    /** 一格字形。坐标全部是烘焙像素；世界侧除以 {@link GlyphCell#scale} 得设计像素。 */
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
        /** 本格烘焙域倍率：墨格 = bakeScale（5× 超采样）；晕格 = 3×（HDR bloom 低频域）。
         * 世界侧 quad 尺寸按各自 scale 折算 —— 晕/墨两格域不同，绝不能共用一个除数。 */
        public final int scale;

        GlyphCell(Object page, int x, int y, int w, int h,
                  int inkX, int inkY, float advance, int inkHeight, int scale) {
            this.page = page;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.inkX = inkX;
            this.inkY = inkY;
            this.advance = advance;
            this.inkHeight = inkHeight;
            this.scale = scale;
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
        /** 格间空隙（烘焙像素）：邻格间距，UV 半像素内缩之外的渗色双保险。 */
        static final int CELL_GAP = 2;

        int[] probe(int w, int h) {
            if (w > width || h > height) return null;
            // 当前 shelf 放得下？（格间空隙计入占用）
            if (shelfX + w + CELL_GAP <= width && shelfY + Math.max(shelfH, h + CELL_GAP) <= height) {
                return new int[] {shelfX, shelfY};
            }
            // 开新 shelf？
            if (shelfY + shelfH + h + CELL_GAP <= height) {
                return new int[] {0, shelfY + shelfH};
            }
            return null;
        }

        void commit(int x, int y, int w, int h) {
            if (y != shelfY) {
                // 开了新 shelf
                shelfY = y;
                shelfH = h + CELL_GAP;
                shelfX = w + CELL_GAP;
            } else {
                shelfX = x + w + CELL_GAP;
                if (shelfH < h + CELL_GAP) shelfH = h + CELL_GAP;
            }
        }
    }

    /**
     * 取一个字形格子（墨格：只含字本体 + 抗锯齿边，无光晕），没有就烘焙。
     * 任何失败返回 null（渲染层回退像素字）。发光由 {@link #getGlowCell}
     * 的独立晕格承担 —— 晕/墨分离后墨格不再烘光，字保持锐利。
     *
     * @param ch           字符
     * @param inkRgb       本体颜色（低 24 位，烘死进像素）
     * @param glowStrength 发光强度 0..1（0 = 不发光；仍进键，切开关即刻生效）
     * @param glowSpread   发光扩散倍率
     * @param glowColorRgb 发光颜色（低 24 位）
     * @deprecated 晕/墨分离后墨格不再携带发光参数；保留旧签名兼容像素回退路径，
     *              新调用请传 0 强度或改用 {@link #getInkCell}。
     */
    @Deprecated
    public static GlyphCell getGlyph(char ch, int inkRgb,
                                     float glowStrength, float glowSpread, int glowColorRgb) {
        return getInkCell(ch, inkRgb);
    }

    /**
     * 取墨格（只含字本体，无光晕）。颜色烘死进像素，进出场 alpha 由世界侧
     * 顶点色承担。发光一律走 {@link #getGlowCell}。
     */
    public static GlyphCell getInkCell(char ch, int inkRgb) {
        Host h = host;
        if (h == null) return null;
        synchronized (LOCK) {
            ensureBakeFontLocked();
            CFontRenderer font = bakeFont;
            if (font == null || font.fontHeight <= 0) return null;

            String key = inkKey(ch, inkRgb);
            GlyphCell cell = CELLS.get(key);
            if (cell != null) {
                touchLocked(cell);
                return cell;
            }

            // ---- 帧内烘焙预算（墨格用独立宽额度，见 INK_BAKES_PER_FRAME_BUDGET）----
            if (inkBakesThisFrame >= INK_BAKES_PER_FRAME_BUDGET
                    || inkBakeNanosThisFrame >= INK_BAKE_NANOS_BUDGET) {
                return null;
            }
            inkBakesThisFrame++;
            long inkBakeStart = System.nanoTime();

            GlyphCell made = bakeInkLocked(h, font, key, ch, inkRgb);
            inkBakeNanosThisFrame += System.nanoTime() - inkBakeStart;
            if (made == null) return null;
            CELLS.put(key, made);
            return made;
        }
    }

    /**
     * 取独立晕格（只含高斯光形，无字本体）。渲染层把晕格 quad 画在墨格之下、
     * 以<b>加色</b>混合 —— 加色只增不减，是「真发光不糊」的混合模型（详见
     * TextGlow.drawGlowOn 注释）。发光关闭（strength≈0）返回 null，调用方
     * 零开销跳过晕层。
     *
     * <p>晕格与墨格共用 PAGES 池与烘焙预算；晕格 pad 大（3σ₀×spread），
     * 大扩散档一格可到 ~120 烘焙px，页消耗约为墨格的 3~4 倍 —— 8 页 LRU
     * 在「一行 20 字 × 双色档」的最坏组合下仍够（墨 ~10 格/页 + 晕 ~3 格/页）。
     */
    public static GlyphCell getGlowCell(char ch, float glowStrength, float glowSpread,
                                        int glowColorRgb, int inkRgb, int tier) {
        if (glowStrength <= 0.004f) return null;
        Host h = host;
        if (h == null) return null;
        synchronized (LOCK) {
            ensureBakeFontLocked();
            CFontRenderer font = bakeFont;
            if (font == null || font.fontHeight <= 0) return null;

            String key = glowKey(ch, glowStrength, glowSpread, glowColorRgb, inkRgb, tier);
            GlyphCell cell = CELLS.get(key);
            if (cell != null) {
                touchLocked(cell);
                return cell;
            }

            // ---- 帧内烘焙预算（与墨格共享同一预算池，帧边界由 beginFrame 给）----
            if (bakesThisFrame >= BAKES_PER_FRAME_BUDGET || bakeNanosThisFrame >= BAKE_NANOS_BUDGET) {
                return null;
            }
            bakesThisFrame++;
            long bakeStart = System.nanoTime();

            GlyphCell made = bakeGlowLocked(h, font, key, ch, glowStrength, glowSpread,
                    glowColorRgb, inkRgb, tier);
            bakeNanosThisFrame += System.nanoTime() - bakeStart;
            if (made == null) return null;
            CELLS.put(key, made);
            return made;
        }
    }

    /**
     * 单字符 advance（设计像素、烘焙字体口径）。世界侧量宽用 —— 与逐字绘制完全同源，
     * 行宽 = Σ 单字 advance，不会漂移。字体不可用时返回 -1（调用方回退像素度量）。
     */
    /** 单字 advance 缓存（char → 设计像素）：designAdvance 是渲染层每帧每字
     * 一次的热路径（量宽/布局/画布包围盒都要它），原实现每次进同步锁做一次
     * String.valueOf + 字体排版 —— 布局引擎对单字也有可观的固定开销，且锁
     * 在烘焙进行时会挡住渲染线程。缓存键用 char（10 位 BMP + 常用增补面直接
     * 索引 HashMap），字体切换时随 invalidateAll 一起清。 */
    /** 渲染线程的无锁读快照（双检：miss 才进锁）。ConcurrentHashMap 承载 ——
     * 渲染线程读、烘焙线程写天然安全，消除热路径上的锁竞争。 */
    private static final java.util.concurrent.ConcurrentHashMap<Character, Float> ADVANCE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(512);

    public static float designAdvance(char ch) {
        Float cached = ADVANCE_CACHE.get(ch);          // 渲染线程无锁快路径
        if (cached != null) return cached;
        synchronized (LOCK) {                           // miss 才进锁（烘焙侧持锁时才竞争）
            cached = ADVANCE_CACHE.get(ch);
            if (cached != null) return cached;
            ensureBakeFontLocked();
            CFontRenderer font = bakeFont;
            if (font == null || font.fontHeight <= 0) return -1f;
            float w = (float) font.getStringWidthD(String.valueOf(ch));
            float design = w / bakeScale;
            if (ADVANCE_CACHE.size() < 8192) {
                ADVANCE_CACHE.put(ch, design);
            }
            return design;
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
            ADVANCE_CACHE.clear();
            bakeFont = null;
            glowFont = null;
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

    /** 懒建烘焙字体：界面正文字体（pf25bold 槽）的 {@code DESIGN_SIZE × bakeScale} 派生档，
     * 随字体切换或画质档切换重建（档位变化时 bakeFont 已被 invalidateAll 清空）。 */
    private static void ensureBakeFontLocked() {
        if (bakeFont != null) return;
        CFontRenderer base = FontManager.pf25bold;
        if (base == null || base.fontHeight <= 0) return;
        try {
            bakeFont = base.deriveAtSize(DESIGN_SIZE * bakeScale);
        } catch (Throwable t) {
            bakeFont = null;
        }
    }

    /** HDR bloom 晕格的独立烘焙字体（{@code DESIGN_SIZE × GLOW_BAKE_SCALE} 3× 低频域）。 */
    private static volatile CFontRenderer glowFont;

    private static void ensureGlowFontLocked() {
        if (glowFont != null) return;
        CFontRenderer base = FontManager.pf25bold;
        if (base == null || base.fontHeight <= 0) return;
        try {
            glowFont = base.deriveAtSize(DESIGN_SIZE * TextGlow.GLOW_BAKE_SCALE);
        } catch (Throwable t) {
            glowFont = null;
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

    /** 墨格键（晕/墨分离后墨格只依赖字与本体色）。"i|" 前缀防与晕格键相撞。 */
    static String inkKey(char ch, int inkRgb) {
        return "i|" + ch
                + "|" + Integer.toHexString(inkRgb & 0xFFFFFF)
                + "|" + fontGeneration;
    }

    /**
     * 晕格键。spread 上限 3.0（16 档量化 → 每档 0.167）；strength 64 档。
     * 光色烘死进晕格像素。
     *
     * <p><b>墨色只进 Ultra 档的键</b>。Ultra 走 HDR bloom 管线
     * （{@link #bakeBloomLocked}），它只用字形<b>覆盖掩码</b> + 光色，墨色根本不
     * 参与像素生成 —— 键里带墨色会让「每个字墨色都不同」的场景（封面渐变！
     * 每字一个 rgbOverride）把同一笔画烘成几十份逐像素相同的晕格。实测一句
     * 30 字、单格烘焙 ~10ms，等于每次换句多烧 300ms 的同步烘焙。Standard/High
     * 档走 {@link TextGlow#drawGlowOn} 的分层高斯，内层亮环会混墨色回收，
     * 所以墨色必须留在那两个档的键里。</p>
     */
    static String glowKey(char ch, float glowStrength, float glowSpread, int glowColorRgb,
                          int inkRgb, int tier) {
        return "g|" + ch
                + "|" + Math.round(clamp01(glowStrength) * 64f)
                + "|" + Math.round(clamp01(glowSpread / 3.0f) * 16f)
                + "|" + Integer.toHexString(glowColorRgb & 0xFFFFFF)
                // Ultra 走纯掩码 bloom，墨色不参与像素 —— 不进键（见方法注释）
                + (tier == TextGlow.GLOW_TIER_ULTRA
                        ? "" : "|" + Integer.toHexString(inkRgb & 0xFFFFFF))
                + "|" + tier
                + "|v" + TextGlow.BLOOM_PIPELINE_VERSION
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

    /**
     * 烘焙一个<b>墨格</b>（只含字本体，无光晕）。失败返回 null。
     * 晕/墨分离：墨格 pad 回到防裁切最小余量，字形边缘由抗锯齿灰阶直接到
     * 格外 —— 晕的可见成分全在独立晕格里。
     */
    private static GlyphCell bakeInkLocked(Host h, CFontRenderer font, String key, char ch,
                                           int inkRgb) {
        if (!SkijaNatives.ensureLoaded()) return null;
        float advanceBake = (float) font.getStringWidthD(String.valueOf(ch));
        if (advanceBake <= 0.01f) return null;
        int inkH = Math.max(1, font.lineBox());
        if (inkH <= 0) return null;

        int pad = minPadBake();
        int cellW = (int) Math.ceil(advanceBake) + pad * 2;
        int cellH = inkH + pad * 2;
        if (cellW > PAGE_SIZE || cellH > PAGE_SIZE) return null;

        int[] at = probeOrMakePageLocked(h, cellW, cellH);
        if (at == null) return null;
        Page page = PAGES.get(at[2]);
        int cellX = at[0], cellY = at[1];

        // ---- 离屏栅格化：只画墨迹 ----
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
                font.drawGlyphsOn(canvas, String.valueOf(ch), pad, yTop, argb, 255, 0f, null);
            } finally {
                canvas.close();
            }
            pixelsArgb = readPixelsArgb(bitmap, cellW, cellH);
        } catch (Throwable t) {
            return null;
        }
        if (pixelsArgb == null) return null;

        try {
            h.uploadCell(page.handle, cellX, cellY, cellW, cellH, pixelsArgb);
        } catch (Throwable t) {
            return null;
        }
        page.commit(cellX, cellY, cellW, cellH);
        page.lastUseNanos = System.nanoTime();
        return new GlyphCell(page.handle, cellX, cellY, cellW, cellH,
                pad, pad, advanceBake, inkH, bakeScale);
    }

    /**
     * 烘焙一个<b>晕格</b>。Ultra 档走 HDR bloom 管线（独立 3× 低频域，见
     * {@link #bakeBloomLocked}）；其余档走 Skija 分层高斯（5× 域）。两条路径
     * 失败互相兜底 —— 新管线在任何环境下炸掉都只降级回旧观感，不丢晕。
     *
     * <p><b>Ultra 档的回退路径不吃墨色</b>（传 0 = 无回收，各层都用光色）：
     * Ultra 的缓存键不含 {@code inkRgb}（见 {@link #glowKey}，bloom 管线只用
     * 覆盖掩码 + 光色）。若回退时仍按墨色回收内层亮环，同一格就会被不同墨色
     * 复用 → 屏幕上是错的色相。回退本身是「bloom 管线已失败」的兜底，观感降级
     * 可接受，但缓存键必须与像素保持一致。</p>
     */
    private static GlyphCell bakeGlowLocked(Host h, CFontRenderer font, String key, char ch,
                                            float glowStrength, float glowSpread, int glowColorRgb,
                                            int inkRgb, int tier) {
        if (tier == TextGlow.GLOW_TIER_ULTRA) {
            GlyphCell bloom = bakeBloomLocked(h, ch, glowStrength, glowSpread, glowColorRgb);
            if (bloom != null) {
                return bloom;
            }
            // 新管线失败（掩码/内存/异常）：回退旧分层烘焙。墨色传 0 —— 键里
            // 没有墨色，像素就不能依赖墨色（见方法注释）。
            return bakeGlowLegacyLocked(h, font, key, ch, glowStrength, glowSpread,
                    glowColorRgb, 0, tier);
        }
        return bakeGlowLegacyLocked(h, font, key, ch, glowStrength, glowSpread,
                glowColorRgb, inkRgb, tier);
    }

    /**
     * Ultra 档晕格：HDR bloom 管线（{@link TextGlow#bakeBloomGlowPixels}）。
     * 独立 3× 烘焙域（晕是低频光形，3× 足够且 mip 链可运行、格小烘焙快）——
     * 与墨格 5× 域不同，世界侧按 {@code cell.scale} 各自折算。
     */
    private static GlyphCell bakeBloomLocked(Host h, char ch,
                                             float glowStrength, float glowSpread, int glowColorRgb) {
        if (!SkijaNatives.ensureLoaded()) return null;
        ensureGlowFontLocked();
        CFontRenderer font = glowFont;
        if (font == null || font.fontHeight <= 0) return null;
        float advanceBake = (float) font.getStringWidthD(String.valueOf(ch));
        if (advanceBake <= 0.01f) return null;
        int inkH = Math.max(1, font.lineBox());
        if (inkH <= 0) return null;

        int glowPad = TextGlow.glowPadding(font, glowSpread, TextGlow.GLOW_TIER_ULTRA);
        int pad = Math.max(2 * TextGlow.GLOW_BAKE_SCALE, glowPad);
        int cellW = (int) Math.ceil(advanceBake) + pad * 2;
        int cellH = inkH + pad * 2;
        if (cellW > PAGE_SIZE || cellH > PAGE_SIZE) return null;

        // ---- 字形覆盖掩码（白字 premul 的 alpha 通道即覆盖度）----
        float[] mask;
        try (Bitmap bitmap = new Bitmap()) {
            if (!bitmap.allocPixels(ImageInfo.makeN32Premul(cellW, cellH))) return null;
            Canvas canvas = new Canvas(bitmap);
            try {
                canvas.clear(0x00000000);
                font.drawGlyphsOn(canvas, String.valueOf(ch), pad, pad + 2f, 0xFFFFFFFF, 255, 0f, null);
            } finally {
                canvas.close();
            }
            byte[] src = bitmap.readPixels();
            if (src == null || src.length < cellW * cellH * 4) return null;
            mask = new float[cellW * cellH];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = (src[i * 4 + 3] & 0xFF) / 255f;
            }
        } catch (Throwable t) {
            return null;
        }

        int[] pixelsArgb = TextGlow.bakeBloomGlowPixels(mask, cellW, cellH, font.sizePx,
                glowSpread, glowStrength, glowColorRgb & 0xFFFFFF);
        if (pixelsArgb == null) return null;
        dilateTransparentRgb(pixelsArgb, cellW, cellH);

        int[] at = probeOrMakePageLocked(h, cellW, cellH);
        if (at == null) return null;
        Page page = PAGES.get(at[2]);
        try {
            h.uploadCell(page.handle, at[0], at[1], cellW, cellH, pixelsArgb);
        } catch (Throwable t) {
            return null;
        }
        page.commit(at[0], at[1], cellW, cellH);
        page.lastUseNanos = System.nanoTime();
        return new GlyphCell(page.handle, at[0], at[1], cellW, cellH,
                pad, pad, advanceBake, inkH, TextGlow.GLOW_BAKE_SCALE);
    }

    /**
     * 旧分层烘焙路径（TextGlow.drawGlowOn 的 Skija 高斯分层，5× 域）—— Standard/High 档主路，
     * 也是 Ultra 新管线的兜底。
     */
    private static GlyphCell bakeGlowLegacyLocked(Host h, CFontRenderer font, String key, char ch,
                                                  float glowStrength, float glowSpread, int glowColorRgb,
                                                  int inkRgb, int tier) {
        if (!SkijaNatives.ensureLoaded()) return null;
        float advanceBake = (float) font.getStringWidthD(String.valueOf(ch));
        if (advanceBake <= 0.01f) return null;
        int inkH = Math.max(1, font.lineBox());
        if (inkH <= 0) return null;

        int glowPad = TextGlow.glowPadding(font, glowSpread, tier);
        int pad = Math.max(minPadBake(), glowPad);
        int cellW = (int) Math.ceil(advanceBake) + pad * 2;
        int cellH = inkH + pad * 2;
        if (cellW > PAGE_SIZE || cellH > PAGE_SIZE) return null;

        int[] at = probeOrMakePageLocked(h, cellW, cellH);
        if (at == null) return null;
        Page page = PAGES.get(at[2]);
        int cellX = at[0], cellY = at[1];

        // ---- 离屏栅格化：只画光形 ----
        int[] pixelsArgb;
        try (Bitmap bitmap = new Bitmap()) {
            ImageInfo info = ImageInfo.makeN32Premul(cellW, cellH);
            if (!bitmap.allocPixels(info)) return null;
            Canvas canvas = new Canvas(bitmap);
            try {
                canvas.clear(0x00000000);
                float yTop = pad + 2f;
                TextGlow.drawGlowOn(canvas, font, String.valueOf(ch),
                        pad, yTop, glowColorRgb & 0xFFFFFF, inkRgb & 0xFFFFFF,
                        glowStrength, glowSpread, tier);
            } finally {
                canvas.close();
            }
            pixelsArgb = readPixelsArgb(bitmap, cellW, cellH);
        } catch (Throwable t) {
            return null;
        }
        if (pixelsArgb == null) return null;

        try {
            h.uploadCell(page.handle, cellX, cellY, cellW, cellH, pixelsArgb);
        } catch (Throwable t) {
            return null;
        }
        page.commit(cellX, cellY, cellW, cellH);
        page.lastUseNanos = System.nanoTime();
        return new GlyphCell(page.handle, cellX, cellY, cellW, cellH,
                pad, pad, advanceBake, inkH, bakeScale);
    }

    /**
     * 页内探测 +（必要时）建新页。返回 {x, y, pageIndex}（pageIndex = 落点页在
     * PAGES 里的下标，新建页在末尾）。放不下/防抖放弃返回 null。
     */
    private static int[] probeOrMakePageLocked(Host h, int cellW, int cellH) {
        for (int i = 0; i < PAGES.size(); i++) {
            Page p = PAGES.get(i);
            int[] at = p.probe(cellW, cellH);
            if (at != null) {
                return new int[] {at[0], at[1], i};
            }
        }
        if (PAGES.size() >= MAX_PAGES) {
            // 防抖：刚逐出过页（<1s）说明工作集已超图集容量，再建页只会陷入
            // 「建 4MB 页 → 挤掉整页活格 → 又建页」的抖动。直接放弃这一格，
            // 渲染层回退像素字 —— 歌唱到哪补到哪，比来回砸显存好。
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
        Page page = new Page(handle, PAGE_SIZE, PAGE_SIZE);
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
        return new int[] {at[0], at[1], PAGES.size() - 1};
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
     *
     * <p>去毛边（RGB 延拓）：全透明 texel 存 (0,0,0,0)，LINEAR 采样在边缘半
     * 像素内与它插值 → 暗色晕。这里给 a==0 的 texel 从水平方向相邻非透明 texel
     * 延拓 RGB（alpha 保持 0）——插值结果从「颜色↔黑」变成「颜色↔同色零透明
     * 度」，边缘晕消失。发光格的 pad 全是光色渐隐，延拓色 = 光色，语义正确。
     * 两遍扫描（左→右、右→左）保证两侧边缘都覆盖。</p>
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
            dilateTransparentRgb(out, width, height);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 透明 texel 的 RGB 从水平相邻非透明 texel 延拓（alpha 不变）。渲染线程单线程。 */
    private static void dilateTransparentRgb(int[] px, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int lastRgb = 0;
            // 左→右
            for (int x = 0; x < w; x++) {
                int i = row + x;
                if ((px[i] >>> 24) > 0) {
                    lastRgb = px[i] & 0xFFFFFF;
                } else if (lastRgb != 0) {
                    px[i] = lastRgb;
                }
            }
            // 右→左（只填仍为 0 的，不动左向已填充的）
            lastRgb = 0;
            for (int x = w - 1; x >= 0; x--) {
                int i = row + x;
                if ((px[i] >>> 24) > 0) {
                    lastRgb = px[i] & 0xFFFFFF;
                } else if ((px[i] & 0xFFFFFF) == 0 && lastRgb != 0) {
                    px[i] = lastRgb;
                }
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
