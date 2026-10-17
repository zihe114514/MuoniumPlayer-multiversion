package com.muoniumplayer.core.rendering.font;

import lombok.Getter;
import lombok.SneakyThrows;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.utils.math.Mth;
import com.muoniumplayer.core.utils.other.StringUtils;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaText;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.FontMetrics;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.TextBlobBuilder;
import io.github.humbleui.skija.Typeface;

import java.awt.Color;
import java.awt.Font;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文字渲染器。对外的方法签名、度量口径、换行与截断规则全部保持不变，内部改由 Skia 绘制。
 *
 * <h2>旧实现做了什么，为什么要换</h2>
 * 旧实现自己造了一套字形缓存：用 Java2D 把每个字符单独画进 {@code BufferedImage}，
 * 异步上传到一张 2048² 的 {@code GL_ALPHA} 图集，绘制时逐字符发两个三角形。
 * 代价有三条：
 * <ol>
 *   <li><b>字形是异步生成的</b>。第一次遇到的字符当帧画不出来，所以整个 UI 层到处都要
 *       {@code areGlyphsLoaded(...)} 判断「这行字能不能画了」，画不了就推迟一帧重排。
 *       这是很多首帧跳动问题的根源。</li>
 *   <li><b>每个字号一张图集</b>。字体梯子上有几十档，几十张 2048² 图集就是上百 MB 显存。</li>
 *   <li>它依赖 {@code glTexEnvi(GL_MODULATE)}、{@code glBegin/glEnd}、显示列表——
 *       正是这次要清掉的固定管线。</li>
 * </ol>
 * Skia 自己管字形图集，度量是同步的、缓存是全局共享的，上面三条全部消失。
 * {@code areGlyphsLoaded} 于是恒为 true，调用方的重排分支自然不再触发。
 *
 * <h2>度量口径为什么要照抄</h2>
 * 布局代码（歌词逐字进度、图标居中、滚动区宽度、截断省略号）全部读这里的度量结果。
 * 只要口径变一点，整个界面都会错位，而这属于业务逻辑，不许改。所以下面三个量原封不动：
 * <ul>
 *   <li>{@link #fontHeight} 仍然是<b>2 倍空间</b>里的行盒高度，
 *       取主字体与所有回退字体中 {@code ascent + descent} 的最大值；</li>
 *   <li>{@link #getFontHeight()} 仍然是 {@code (fontHeight - 8.5) * .5} 这个经验公式；</li>
 *   <li>单字宽度仍然是「2 倍空间里的整数像素再除以 2」，也就是量化到 0.5 个 GUI 单位。
 *       不保留这个量化，图标类控件的居中会整体偏半像素。</li>
 * </ul>
 * 唯一刻意去掉的是旧实现给单字宽度额外加的 {@code max(0, -lsb)}：那是为了把字形画进
 * 一张刚好够大的临时位图而做的左侧留白补偿，被顺手算进了<b>步进</b>里。
 * Skia 不需要这块留白，把它算进步进反而是错的（会让带负左边距的字形后面多出空隙）。
 *
 * <h2>基线位置</h2>
 * 旧实现把字形位图的<b>左上角</b>放在 {@code (x, y - 2)}，位图内部的基线在
 * {@code (height + ascent - descent) / 2} 处。Skia 是按<b>基线</b>定位的，
 * 所以这里把同一个偏移量换算好（见 {@link #baselineOffset}），
 * 保证同样的 {@code (x, y)} 画出来的字在同一个位置。
 */
public class CFontRenderer implements Closeable, SharedConstants {

    /** 主字体（已按字号派生）。{@code GlyphInk} / {@code GlyphInkMetrics} 仍然读它做墨迹度量。 */
    public Font font;
    /** 回退字体（已按字号派生）。 */
    public Font[] fallBackFonts;
    public float sizePx;

    /** 未派生的原始 AWT Font 实例，用来向 {@link SkijaText} 查 Typeface。 */
    private Font sourceFont;
    private final TextBlobCache plainTextBlobs = new TextBlobCache(256, 16_384);
    private Font[] sourceFallbacks;

    /**
     * 行盒高度的锁定值，即 {@link #computeMetrics()} 里那个 {@code box}（2 倍空间）。
     * -1 表示不锁，按当前字体自己算。
     *
     * <p>换用户字体时必须锁。{@link #getFontHeight()} 是整个界面的行高与垂直居中口径，
     * 而任意一款系统字体的 ascent/descent 比例都和内置的 SF Pro 不同：跟着新字体走，
     * 换字体的那一帧所有列表行高、按钮内文位置、歌词行距会一起变——那不叫"换了字体"，
     * 那叫"换了一套布局"。锁住行盒之后，新字体自己的 ascent/descent 盒会被<b>居中放进</b>
     * 原来的行盒里（{@link #computeMetrics()} 现成的 baseline 公式就是干这个的），
     * 于是排版一动不动，只有字形换了。</p>
     */
    private int lineBoxOverride = -1;

    /**
     * 主字体是否描边加粗。
     *
     * <p>用户挑的字体族里很可能只有 Regular 一个字重（大量免费中文字体如此），而界面上
     * {@code pf*bold} 那一半渲染器要的是粗体。没有粗体文件时用 Skia 的 {@code setEmboldened}
     * 顶上，比"粗细完全一样、只有字号不同"更接近原来的信息层次。</p>
     *
     * <p>只作用在主字体上：回退字体是内置的 SF Pro / 苹方粗体，本来就是粗的，再加粗会糊。</p>
     */
    private boolean emboldenMain;

    private FontKerning fontKerning;

    /** 索引 0 是主字体，之后依次是回退字体。 */
    private io.github.humbleui.skija.Font[] skFonts = new io.github.humbleui.skija.Font[0];
    private Typeface[] skTypefaces = new Typeface[0];

    /** 从 {@code y - 2} 到基线的距离，GUI 单位。 */
    @Getter
    private float baselineOffset;

    /**
     * Oracle JDK 8 对 CFF（PostScript outline，即 .otf）字体存在 2x 缩放 bug：
     * deriveFont(size) 报告的 getSize2D() 正确，但实际 ascent/descent/advance 都被放成应有的 2 倍。
     * 这个修正只作用于 AWT 侧派生出来的 {@link #font}（{@code GlyphInk} 还在用它），
     * Skia 的光栅化器没有这个 bug，所以 Skia 字号一律用 {@link #sizePx}。
     */
    private static final Map<Font, Float> cffScaleFactorCache = new HashMap<>();
    private static final java.awt.image.BufferedImage cffMetricsImage =
            new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    private static final java.awt.Graphics2D cffMetricsGraphics =
            (java.awt.Graphics2D) cffMetricsImage.getGraphics();

    private static float cffScaleFactor(Font rawFont) {
        Float cached = cffScaleFactorCache.get(rawFont);
        if (cached != null) {
            return cached;
        }
        java.awt.FontMetrics fm = cffMetricsGraphics.getFontMetrics(rawFont.deriveFont(100f));
        float ratio = (fm.getAscent() + fm.getDescent()) / 100f;
        // 正常字体 ascent+descent ≈ 1.2~1.5 em；CFF 被 Oracle JDK 放大后 ≈ 2.5 em
        float factor = ratio > 2.0f ? 0.5f : 1.0f;
        cffScaleFactorCache.put(rawFont, factor);
        return factor;
    }

    public CFontRenderer(Font font, float sizePx) {
        this(font, sizePx, new Font[0]);
    }

    @SneakyThrows
    public CFontRenderer(Font font, float sizePx, Font... fallBackFonts) {
        // Configure main + fallback fonts before initializing Skia. Previously
        // every renderer built and discarded its main font and metrics twice.
        this.sizePx = sizePx;
        this.sourceFont = font;
        this.sourceFallbacks = fallBackFonts == null ? new Font[0] : fallBackFonts.clone();
        this.fallBackFonts = new Font[this.sourceFallbacks.length];

        for (int i = 0; i < this.sourceFallbacks.length; i++) {
            this.fallBackFonts[i] = this.sourceFallbacks[i]
                    .deriveFont(sizePx * 2 * cffScaleFactor(this.sourceFallbacks[i]));
        }

        init(font, sizePx);
    }

    public CFontRenderer(Font font, float sizePx, FontKerning fontKerning, Font... fallBackFonts) {
        this(font, sizePx, fallBackFonts);
        this.fontKerning = fontKerning;
    }

    private void init(Font font, float sizePx) {
        this.font = font.deriveFont(sizePx * 2 * cffScaleFactor(font));
        buildSkiaFonts();
    }

    /**
     * 建 Skia 侧的字体对象并算好度量。
     *
     * <p>字号直接用 {@link #sizePx}：旧实现是「按 2 倍字号栅格化、绘制时缩 0.5」，
     * 净效果就是 {@code sizePx} 这么大的字。Skia 自己按设备分辨率决定超采样，
     * 不需要也不应该再手工放大再缩小——那只会让字发虚。
     */
    private long metricsRevision;

    /** Changes whenever this renderer is retargeted to another font. */
    public long metricsRevision() {
        return metricsRevision;
    }

    private void buildSkiaFonts() {
        metricsRevision++;
        List<Typeface> typefaces = new ArrayList<>();
        List<io.github.humbleui.skija.Font> fonts = new ArrayList<>();

        Typeface main = SkijaText.typefaceFor(this.sourceFont);
        if (main != null) {
            typefaces.add(main);
            fonts.add(newFont(main, this.emboldenMain));
        }
        if (this.sourceFallbacks != null) {
            for (Font fallback : this.sourceFallbacks) {
                Typeface tf = SkijaText.typefaceFor(fallback);
                // 图标字体的「回退」就是它自己（见 FontManager.create 的最后一条 return），
                // 去重避免同一个 typeface 进两次，白白多一轮查表。
                if (tf != null && !typefaces.contains(tf)) {
                    typefaces.add(tf);
                    fonts.add(newFont(tf, false));
                }
            }
        }

        closeSkiaFonts();
        this.skTypefaces = typefaces.toArray(new Typeface[0]);
        this.skFonts = fonts.toArray(new io.github.humbleui.skija.Font[0]);
        this.glyphBlocks = new GlyphRef[256][];
        this.stringWidthMapD.clear();
        computeMetrics();
    }

    private io.github.humbleui.skija.Font newFont(Typeface typeface, boolean embolden) {
        io.github.humbleui.skija.Font f = new io.github.humbleui.skija.Font(typeface, this.sizePx);
        // 灰度抗锯齿：内容是画到一张全透明的离屏图层上再合成的，
        // 子像素 LCD 抗锯齿在透明背景上会渲染出彩色边缘。
        f.setEdging(FontEdging.ANTI_ALIAS);
        // 不做字形对齐（hinting），并允许子像素定位：
        // 旧实现用的是 Java2D 的 FRACTIONALMETRICS_ON，行为一致。
        f.setHinting(FontHinting.NONE);
        f.setSubpixel(true);
        // 关掉基线吸附。Skia 默认把基线 round 到整数设备像素，而 setSubpixel 只管水平方向，
        // 垂直方向仍是整像素——幅度小的纵向动画因此走成台阶。实测（GL 后端、Skia 143）一个
        // 设备像素内可分辨的垂直位置数：吸附时 1 个，关掉后 4 个（1/4 像素），水平垂直的口径
        // 这才一致。歌词逐字上浮全程只有 1.85px，吸附下 guiScale=2 只有 4 级台阶，肉眼就是
        // 一顿一顿；关掉后 15 级。
        // 代价：字形 mask 的缓存键多一维垂直相位，同一个字最多存 4 份。实际远低于 4 倍——
        // 同一帧里绝大多数文字的垂直相位相同，只有正在上浮的那行才会铺开。
        f.setBaselineSnapped(false);
        // 假粗只在"用户挑的字体族没有粗体文件"时打开（见 emboldenMain）。Skia 的做法是按字号
        // 比例描边，不会像整体放大那样把字撑宽，所以行内宽度只多出描边的那一点。
        if (embolden) {
            f.setEmboldened(true);
        }
        return f;
    }

    /**
     * 算 {@link #fontHeight} 与 {@link #baselineOffset}。
     *
     * <p>推导：旧实现字形位图高 {@code H = max(ascent + descent)}（2 倍空间），
     * 位图内基线在 {@code (H + ascent - descent) / 2}，位图左上角画在 {@code (x, y - 2)}，
     * 然后整体缩 0.5。于是基线的 GUI 坐标是
     * {@code (y - 2) + (H + ascentMain - descentMain) / 2 * 0.5}。
     * 把 2 倍空间的量换成 1 倍空间（都除以 2）就得到下面的式子。
     */
    private void computeMetrics() {
        if (this.skFonts.length == 0) {
            // 字体没解析出来。fontHeight 保持 -1，和旧实现「字形还没生成」时一致，
            // 调用方原有的兜底分支会接住。
            this.fontHeight = -1;
            this.baselineOffset = 0;
            return;
        }
        FontMetrics mainMetrics = this.skFonts[0].getMetrics();
        int mainAscent = (int) Math.ceil(-mainMetrics.getAscent());
        int mainDescent = (int) Math.ceil(mainMetrics.getDescent());

        int box = mainAscent + mainDescent;
        for (int i = 1; i < this.skFonts.length; i++) {
            FontMetrics m = this.skFonts[i].getMetrics();
            box = Math.max(box, (int) Math.ceil(-m.getAscent()) + (int) Math.ceil(m.getDescent()));
        }

        if (this.lineBoxOverride > 0) {
            // 锁定行盒：只替换 box 这一个量。下面的 baselineOffset 公式本来就是
            // "把主字体自己的 ascent/descent 盒居中放进 box"，于是新字体自动被垂直居中
            // 到原来那条行盒里，getFontHeight() / getHeight() 的返回值一字不改。
            box = this.lineBoxOverride;
        }

        // fontHeight 是 2 倍空间的量：旧实现的 AWT 字体是按 sizePx*2 派生的。
        this.fontHeight = box * 2.0;
        this.baselineOffset = (box + mainAscent - mainDescent) * 0.5f;
    }

    /**
     * 当前行盒（{@code fontHeight} 的一半，2 倍空间）。换字体前要先把它记下来，
     * 之后每次 {@link #retarget} 都传同一个值，界面排版才不会随字体漂移。
     *
     * @return 字体没解析出来时返回 -1
     */
    public int lineBox() {
        return this.fontHeight <= 0 ? -1 : (int) Math.round(this.fontHeight * .5);
    }

    /**
     * 换掉这个渲染器背后的字体，度量口径（行高、基线位置）保持不变。
     *
     * <p>这是"界面字体切换"的唯一入口。{@link #buildSkiaFonts()} 顺手把字形索引
     * （{@code glyphBlocks}）和字符串宽度缓存都清了，所以换完立刻生效，不需要重启。</p>
     *
     * <p><b>回退字体必须留着内置那一套。</b>用户挑的字体十有八九缺字（英文字体没有汉字、
     * 中文字体没有假名），缺的那一格由回退链接住，画出来是内置字体的字形而不是豆腐块。</p>
     *
     * @param main          新的主字体（未派生的原始实例，必须已在 {@code SkijaText} 登记过字节）
     * @param fallbacks     回退链，按顺序查
     * @param lockedLineBox {@link #lineBox()} 在换字体<b>之前</b>的返回值；&lt;=0 表示不锁
     * @param emboldenMain  主字体是否描边加粗（字体族没有粗体文件时用）
     * @param kerning       字间距表；换成别的字体时传 null——内置表是按 SF Pro 的字宽量的，
     *                      套到别的字体上会让字距忽宽忽窄
     */
    public void retarget(Font main, Font[] fallbacks, int lockedLineBox,
                         boolean emboldenMain, FontKerning kerning) {
        if (main == null) {
            return;
        }
        this.sourceFont = main;
        this.sourceFallbacks = fallbacks == null ? new Font[0] : fallbacks.clone();
        this.fontKerning = kerning;
        this.emboldenMain = emboldenMain;
        this.lineBoxOverride = lockedLineBox > 0 ? lockedLineBox : -1;

        this.font = main.deriveFont(this.sizePx * 2 * cffScaleFactor(main));
        this.fallBackFonts = new Font[this.sourceFallbacks.length];
        for (int i = 0; i < this.sourceFallbacks.length; i++) {
            this.fallBackFonts[i] = this.sourceFallbacks[i]
                    .deriveFont(this.sizePx * 2 * cffScaleFactor(this.sourceFallbacks[i]));
        }

        buildSkiaFonts();
    }

    /**
     * 派生一个<b>同一套字体族</b>（主字体 + 回退链 + 字距表 + 加粗设置）、
     * 不同字号的渲染器。3D 世界歌词的烘焙字体用：界面字体切到哪，烘焙就跟着哪。
     *
     * <p>与 {@link #retarget} 的区别：retarget 是「把这个渲染器换成另一款字体」，
     * 本方法是「用这套字体再造一个别的字号的渲染器」，原渲染器不动。新渲染器的
     * Skia 字体对象是独立重建的，与原实例互不共享缓存。</p>
     *
     * @param newSizePx 新字号（GUI 像素，与构造器同口径）
     */
    public CFontRenderer deriveAtSize(float newSizePx) {
        CFontRenderer derived = new CFontRenderer(this.sourceFont, newSizePx,
                this.fontKerning, this.sourceFallbacks);
        if (this.emboldenMain && derived.skFonts.length > 0) {
            // 构造器没带 embolden 语义（它由 retarget/buildSkiaFonts 读字段），这里手动对齐：
            // 主字体假粗是"字体族没有粗体文件"时的观感补偿，派生档必须一致，否则
            // 50px 烘焙字比界面正文细一档。
            derived.emboldenMain = true;
            derived.buildSkiaFonts();
        }
        return derived;
    }

    /**
     * 按<b>对象身份</b>把回退链里的某个字体换成另一个实例：完整字体包下载完成后的热启用入口。
     *
     * <p>和 {@link #retarget} 的分工：retarget 换主字体、锁行盒，是"用户换界面字体"的入口；
     * 本方法只认回退链里与 {@code expected} 同一个对象的槽位（子集→同款全量，度量一致，
     * 不需要锁行盒），主字体一概不动。渲染器实例保持不变，所有缓存过本实例的地方——
     * 预热的控件树、{@code GlyphInkMetrics} 的键——继续有效。</p>
     *
     * <p>字形索引与宽度缓存随 {@link #buildSkiaFonts()} 一并失效；已栅格化进图集的旧字形
     * 是同一款字体的同一批轮廓，不清理也不会画错，只是图集里的空洞无法回收。</p>
     *
     * @param expected    要被换掉的字体实例（主包里的精简子集那个 {@code Font}）
     * @param replacement 同一款字体的完整版实例，必须<b>先</b>在 {@code SkijaText} 登记过字节
     * @return 有槽位被换掉返回 true；回退链里没有这个实例则什么都不做并返回 false
     */
    public boolean swapFallbackFont(Font expected, Font replacement) {
        if (expected == null || replacement == null || expected == replacement) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < this.sourceFallbacks.length; i++) {
            if (this.sourceFallbacks[i] == expected) {
                this.sourceFallbacks[i] = replacement;
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        this.fallBackFonts = new Font[this.sourceFallbacks.length];
        for (int i = 0; i < this.sourceFallbacks.length; i++) {
            this.fallBackFonts[i] = this.sourceFallbacks[i]
                    .deriveFont(this.sizePx * 2 * cffScaleFactor(this.sourceFallbacks[i]));
        }
        buildSkiaFonts();
        return true;
    }

    /**
     * 用当前已登记的字体字节重建 Skia 侧的字体与度量。渲染层热启用的收尾动作。
     *
     * <p>无原生库窗口期构造的渲染器，{@link #buildSkiaFonts()} 拿到的 Typeface 全是 null，
     * skFonts 建出来是空数组——形状照画、文字全无。原生库就位后调这个方法：
     * typefaceFor 是懒解析且不缓存失败的，重跑即得；字形索引、宽度缓存与文本 blob
     * 随 {@code buildSkiaFonts} 一并失效，行盒按真字体重新计算。</p>
     *
     * @return 重建后至少解析出一个字体返回 true；仍是空（字节没登记/库仍不可用）返回 false
     */
    public boolean refreshSkiaFonts() {
        buildSkiaFonts();
        return this.skFonts.length > 0;
    }

    public static String stripControlCodes(String text) {
        char[] chars = text.toCharArray();
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\u00a7') {
                i++;
                continue;
            }
            f.append(c);
        }
        return f.toString();
    }

    public double fontHeight = -1;

    // ------------------------------------------------------------------
    // 字形解析与缓存
    // ------------------------------------------------------------------

    /**
     * 单个字符解析出来的结果：用第几个字体、字形号、以及量化后的步进。
     * {@code fontIndex < 0} 表示所有字体都没有这个字形。
     */
    private static final class GlyphRef {
        final int fontIndex;
        final short glyphId;
        final float advance;

        GlyphRef(int fontIndex, short glyphId, float advance) {
            this.fontIndex = fontIndex;
            this.glyphId = glyphId;
            this.advance = advance;
        }
    }

    /**
     * 按 256 字符一块惰性分配。整块 65536 项的数组每个渲染器要占几百 KB，
     * 而字体梯子上有几十个渲染器，实测只会用到其中很少几块。
     */
    private GlyphRef[][] glyphBlocks = new GlyphRef[256][];

    private GlyphRef glyphRef(char c) {
        int hi = (c >>> 8) & 0xFF;
        int lo = c & 0xFF;
        GlyphRef[] block = glyphBlocks[hi];
        if (block == null) {
            block = new GlyphRef[256];
            glyphBlocks[hi] = block;
        }
        GlyphRef ref = block[lo];
        if (ref == null) {
            ref = resolve(c);
            block[lo] = ref;
        }
        return ref;
    }

    private GlyphRef resolve(char c) {
        for (int i = 0; i < skTypefaces.length; i++) {
            short glyphId = skTypefaces[i].getUTF32Glyph(c);
            if (glyphId != 0) {
                float[] widths = skFonts[i].getWidths(new short[]{glyphId});
                float raw = widths.length > 0 ? widths[0] : 0f;
                // 和旧实现同样量化到 0.5 GUI 单位：旧实现在 2 倍空间里取整像素。
                float advance = (float) (Math.ceil(raw * 2.0) * 0.5);
                return new GlyphRef(i, glyphId, advance);
            }
        }
        return new GlyphRef(-1, (short) 0, 0f);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    public float drawString(String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float g = ((color >> 8) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float b = ((color) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float a = ((color >> 24) & 0xff) * RenderSystem.DIVIDE_BY_255;
        drawString(s, x, y, r, g, b, a);
        return (float) getStringWidthD(s);
    }

    public int drawStringWithShadow(String text, double x, double y, int color) {

        int a = (color >> 24) & 0xff;

        drawString(stripControlCodes(text), x + 1, y + 1, RGBA.color(0, 0, 0, a));
        drawString(text, x, y, color);

        return this.getStringWidth(text);
    }

    public void drawString(String s, double x, double y, Color color) {
        drawString(s, x, y, color.getRed() * RenderSystem.DIVIDE_BY_255, color.getGreen() * RenderSystem.DIVIDE_BY_255, color.getBlue() * RenderSystem.DIVIDE_BY_255, color.getAlpha());
    }

    public void drawCenteredStringVertical(String text, double x, double y, int color) {
        drawString(text, x, y - this.getFontHeight() * .5, color);
    }

    private int getColorCode(char c) {
        switch (c) {
            case '0': return 0x000000;
            case '1': return 0x0000AA;
            case '2': return 0x00AA00;
            case '3': return 0x00AAAA;
            case '4': return 0xAA0000;
            case '5': return 0xAA00AA;
            case '6': return 0xFFAA00;
            case '7': return 0xAAAAAA;
            case '8': return 0x555555;
            case '9': return 0x5555FF;
            case 'a': return 0x55FF55;
            case 'b': return 0x55FFFF;
            case 'c': return 0xFF5555;
            case 'd': return 0xFF55FF;
            case 'e': return 0xFFFF55;
            case 'f': return 0xFFFFFF;
            default: return Integer.MIN_VALUE; // 默认颜色，否则抛出异常
        }
    }

    /**
     * @return 旧实现在这里返回「所有字形是否都已就绪」。Skia 的度量与光栅化是同步的，
     *         不存在「这一帧还画不出来」的状态，所以恒为 true。
     *         调用方的推迟重排分支因此不再触发——那正是想要的结果。
     */
    public boolean drawString(String s, double x, double y, float r, float g, float b, float a) {
        float alpha = a;
        if (alpha > 1f) alpha = 1f;
        if (alpha < 0f) alpha = 0f;
        return drawGlyphs(s, x, y, SkijaGfx.argb(r, g, b, alpha), Math.round(alpha * 255f), 0f, null);
    }

    /**
     * 同样的一串字，但整体过一遍高斯模糊——一次调用就是一层光晕。
     *
     * <p>{@code sigma} 是模糊半径，单位与坐标一致（GUI 像素）。模糊作用在字形遮罩上，
     * 不开离屏图层；Skia 把模糊后的遮罩按 sigma 一起缓存进字形图集，所以同一个字
     * 第二帧起就是一次贴图。要真正的「光」还得把混合模式设成加色，
     * 那是调用方的事（见 {@code SkijaGfx.setBlendMode}）。
     *
     * @param argb  含 alpha 的颜色；这一层光晕的亮度就是它的 alpha
     * @param sigma 高斯 sigma；≤0 时退化成普通绘制
     */
    public void drawStringGlow(String s, double x, double y, int argb, double sigma) {
        drawGlyphs(s, x, y, argb, argb >>> 24, (float) Math.max(0.0, sigma), null);
    }

    /**
     * 同样的一串字，但整体用给定的 {@code shader} 填充。
     *
     * <p>为逐字扫光而加：一条水平线性渐变就能把「已唱 → 正在唱 → 未唱」的颜色与透明度
     * 变化压进<b>一次</b>绘制，羽化由 Skia 逐像素插值，不用像旧做法那样把同一个字形按窄带
     * 切成十几条各画一遍。shader 的坐标系与当前画布一致，所以调用方按屏幕坐标给渐变端点。
     *
     * <p>shader 的生命周期由调用方负责；这里只借用，不关闭。
     */
    public void drawStringShaded(String s, double x, double y, Shader shader) {
        if (shader == null) {
            return;
        }
        drawGlyphs(s, x, y, 0xFFFFFFFF, 255, 0f, shader);
    }

    /**
     * Builds only CPU text geometry on the render thread. No Canvas, drawing, texture
     * upload or animation state is required; the bounded blob cache owns the result.
     */
    public void prepareText(String text) {
        if (text == null || text.isEmpty() || text.length() > 1024
                || text.indexOf('\u00a7') >= 0 || skFonts.length == 0) return;
        if (plainTextBlobs.get(text) == null) {
            TextBlob blob = buildPlainBlob(text);
            if (blob != null) plainTextBlobs.put(text, blob);
        }
    }

    private boolean drawGlyphs(String s, double x, double y, int baseArgb, int alpha255,
                               float glowSigma, Shader shader) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return true;
        }
        return drawGlyphsOn(canvas, s, x, y, baseArgb, alpha255, glowSigma, shader);
    }

    /**
     * 同样的绘制语义，但画到<b>指定的</b>画布 —— 离屏栅格化（3D 世界歌词把行文字烘焙成
     * GL 纹理）用：那一帧的字不进宿主帧画布，而进一张离屏位图，再经 DynamicTexture 上传。
     *
     * <p>与 {@link #drawGlyphs} 唯一的差异是画布来源；blob 缓存、回退字体、颜色码、
     * 光晕全部同一条路径。</p>
     */
    public boolean drawGlyphsOn(Canvas canvas, String s, double x, double y, int baseArgb,
                                int alpha255, float glowSigma, Shader shader) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        if (canvas == null || skFonts.length == 0) {
            return true;
        }

        // Geometry is independent of position, tint, alpha, glow and shader.
        // Keep formatted text on the original path; plain labels and lyrics
        // can reuse the same native blob throughout their animations.
        if (s.length() <= 1024 && s.indexOf('\u00a7') < 0) {
            TextBlob blob = plainTextBlobs.get(s);
            if (blob == null) {
                blob = buildPlainBlob(s);
                if (blob != null) {
                    plainTextBlobs.put(s, blob);
                }
            }
            if (blob != null) {
                drawBlob(canvas, blob, x, y - 2.0, baseArgb, glowSigma, shader);
            }
            return true;
        }

        int argb = baseArgb;

        // 旧实现：translate(x, y - 2) 后按 0.5 缩放，字形位图左上角落在原点。
        // 这里换算成基线坐标，几何结果相同。
        double originY = y - 2.0;
        double lineStep = this.getHeight() + 2.0;  // 旧实现 2 倍空间里的 getHeight()*2 + 4

        RunAccumulator runs = new RunAccumulator(glowSigma, shader);
        double penX = 0;
        double lineY = 0;
        boolean inSel = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char nextChar = i + 1 < s.length() ? s.charAt(i + 1) : '\0';

            if (inSel) {
                inSel = false;
                if (c == 'r') {
                    runs.flushBlob(canvas, x, originY, argb);
                    argb = baseArgb;
                } else {
                    int colorCode = this.getColorCode(c);
                    if (colorCode != Integer.MIN_VALUE) {
                        runs.flushBlob(canvas, x, originY, argb);
                        argb = (alpha255 << 24) | (colorCode & 0xFFFFFF);
                    }
                }
                continue;
            }

            if (c == '\u00a7') {
                inSel = true;
                continue;
            }
            if (c == '\n') {
                lineY += lineStep;
                penX = 0;
                continue;
            }

            if (c == '（') c = '(';
            if (c == '）') c = ')';
            if (c == '・') c = '·';

            GlyphRef ref = glyphRef(c);
            if (ref.fontIndex >= 0) {
                runs.add(ref.fontIndex, ref.glyphId,
                        (float) penX, (float) (lineY + baselineOffset));
                penX += ref.advance;
            }

            // 添加字间距
            if (fontKerning != null && nextChar != '\0' && nextChar != '\u00a7' && nextChar != '\n') {
                penX += fontKerning.getKerning(c, nextChar, sizePx);
            }
        }

        runs.flushBlob(canvas, x, originY, argb);
        runs.close();

        return true;
    }

    private TextBlob buildPlainBlob(String text) {
        RunAccumulator runs = new RunAccumulator(0f, null);
        try {
            double penX = 0.0;
            double lineY = 0.0;
            double lineStep = this.getHeight() + 2.0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
                if (c == '\n') {
                    lineY += lineStep;
                    penX = 0.0;
                    continue;
                }
                if (c == '（') c = '(';
                if (c == '）') c = ')';
                if (c == '・') c = '·';
                GlyphRef ref = glyphRef(c);
                if (ref.fontIndex >= 0) {
                    runs.add(ref.fontIndex, ref.glyphId, (float) penX, (float) (lineY + baselineOffset));
                    penX += ref.advance;
                }
                if (fontKerning != null && next != '\0' && next != '\n') {
                    penX += fontKerning.getKerning(c, next, sizePx);
                }
            }
            return runs.buildBlob();
        } finally {
            runs.close();
        }
    }

    private static void drawBlob(Canvas canvas, TextBlob blob, double x, double y,
                                 int argb, float glowSigma, Shader shader) {
        Paint paint = shader != null ? SkijaGfx.fillShader(shader)
                : glowSigma > 0.01f ? SkijaGfx.fillBlurred(argb, glowSigma) : SkijaGfx.fill(argb);
        try {
            canvas.drawTextBlob(blob, (float) x, (float) y, paint);
        } finally {
            if (shader != null) {
                paint.setShader(null);
            }
        }
    }

    /**
     * 把逐字符的字形累积成 TextBlob。
     *
     * <p>为什么要累积而不是逐字符 {@code drawString}：一次绘制调用在 Skia 里要走一遍
     * 图元合批判断，逐字符发就是几十倍的开销。TextBlob 把「一串字形 + 各自的位置」
     * 打包成一个绘制图元，是 Skia 里画预排好的文本的标准做法。
     *
     * <p>换行（不同的 y）和字体切换（中英混排）都要拆成新的 run，
     * 但它们仍在同一个 blob 里；只有颜色变化（{@code §} 颜色码）才必须换 blob，
     * 因为颜色在 Paint 上而不在 blob 里。
     */
    private final class RunAccumulator {
        private final TextBlobBuilder builder = new TextBlobBuilder();
        private final List<Short> glyphs = new ArrayList<>();
        private final List<Float> xs = new ArrayList<>();
        private final float glowSigma;
        /** 非 null 时整串字形改用它填充，颜色码与光晕都让位（见 drawStringShaded）。 */
        private final Shader shader;
        private int runFontIndex = -1;
        private float runY;
        private boolean hasContent;

        RunAccumulator(float glowSigma, Shader shader) {
            this.glowSigma = glowSigma;
            this.shader = shader;
        }

        void add(int fontIndex, short glyphId, float x, float y) {
            if (runFontIndex != fontIndex || runY != y) {
                flushRun();
                runFontIndex = fontIndex;
                runY = y;
            }
            glyphs.add(glyphId);
            xs.add(x);
        }

        private void flushRun() {
            if (glyphs.isEmpty() || runFontIndex < 0) {
                glyphs.clear();
                xs.clear();
                return;
            }
            short[] g = new short[glyphs.size()];
            float[] px = new float[xs.size()];
            for (int i = 0; i < g.length; i++) {
                g[i] = glyphs.get(i);
                px[i] = xs.get(i);
            }
            builder.appendRunPosH(skFonts[runFontIndex], g, px, runY);
            hasContent = true;
            glyphs.clear();
            xs.clear();
        }

        TextBlob buildBlob() {
            flushRun();
            if (!hasContent) {
                return null;
            }
            hasContent = false;
            return builder.build();
        }

        void flushBlob(Canvas canvas, double originX, double originY, int argb) {
            try (TextBlob blob = buildBlob()) {
                if (blob != null) {
                    drawBlob(canvas, blob, originX, originY, argb, glowSigma, shader);
                }
            }
        }

        void close() {
            builder.close();
        }
    }

    public void drawCenteredString(String s, double x, double y, int color) {
        _drawCenteredString(s, x, y, color);
    }

    /**
     * @return 当字符串中的所有字符都已加载时返回 true
     */
    public boolean _drawCenteredString(String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float g = ((color >> 8) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float b = ((color) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float a = ((color >> 24) & 0xff) * RenderSystem.DIVIDE_BY_255;

        return drawString(s, (x - getStringWidthD(s) * .5), y, r, g, b, a);
    }

    public void drawCenteredStringWithShadow(String s, double x, double y, int color) {
        drawStringWithShadow(s, (x - getStringWidthD(s) * .5), y, color);
    }

    public void drawCenteredStringMultiLine(String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float g = ((color >> 8) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float b = ((color) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float a = ((color >> 24) & 0xff) * RenderSystem.DIVIDE_BY_255;

        double offsetY = y;
        for (String string : s.split("\n")) {
            drawString(string, (x - getStringWidthD(string) / 2.0), offsetY, r, g, b, a);
            offsetY += this.getFontHeight();
        }

    }

    /** 折叠后仍要保留的省略号。单独一份是因为线性路径每步都要量它，别重复建字符串。 */
    private static final String ELLIPSIS = "...";

    public String trim(String text, double width) {
        String name = text == null ? "" : text;

        // 响应式 UI 控件折叠时，内容区域为零/负值是合法的。
        // 这种情况下没有可绘制的有效子串。
        if (width <= 0.0 || name.isEmpty()) {
            return "";
        }

        if (this.getStringWidthD(name) > width) {
            // 绝大多数调用是「每帧把歌名折成一行」，走线性那一条；只有带控制码或换行的
            // 退回老路径。判定用两次 indexOf 就够 —— 别拿正则问，那是每帧每个标签一次。
            return name.indexOf('\u00a7') < 0 && name.indexOf('\n') < 0
                    ? trimLinear(name, width)
                    : trimByBackscan(name, width);
        }

        return name;
    }

    /**
     * 逐字符累加的折叠：一次量完全长，之后每退一个字符只减掉那一步的步进。
     *
     * <p>与 {@link #trimByBackscan} 判定的是同一件事 —— 让 {@code 前缀 + "..."} 放得下的<b>最长</b>
     * 前缀 —— 但不为每个候选前缀新建字符串，也就不再往字宽记忆表里塞 {@code n} 条一次性键。
     * 每帧折十几条标题时，那些键会把整张表顶到上限、逼它整表清空，然后本屏所有文本在同一帧里重量一遍：
     * 折行这件事自己把缓存打爆，正是打开长列表那一顿的来源。</p>
     *
     * <p>累加口径与 {@link #getStringWidthD(String)} 严格一致：{@code （}/{@code ）} 换成半角后才
     * 查字宽，而字距项 {@code getCharWidth(c1, c2)} 的第二个参数仍是<b>未替换</b>的原字符。取「最长
     * 放得下的前缀」也不需要单调性 —— 每个长度都真量了一遍，和从长往短扫到第一个放得下的是同一个结果。</p>
     */
    private String trimLinear(String name, double width) {
        final char[] chars = name.toCharArray();
        final int n = chars.length;

        // 省略号自身：三个点连排，最后一个点后面没有下一个字符。
        double ellipsisWidth = getCharWidth('.', '.') + getCharWidth('.', '.') + getCharWidth('.', '\0');

        // inner 是「前缀内部」的步进与字距之和，即量 [0, i+1) 这一段时相邻字符之间的那部分，
        // 不含末字符到第一个点的跨字步进 —— 那一项要单独加，因为它依赖省略号。
        double inner = 0.0;
        int bestFit = -1;             // 目前放得下的最长前缀长度，0 表示连一个字符都放不下
        for (int i = 0; i < n; i++) {
            char last = replaceForMeasurement(chars[i]);
            double total = inner + getCharWidth(last, '.') + ellipsisWidth;
            if (total <= width) {
                bestFit = i + 1;      // 顺着往后走，最后一次点亮就是「最长的那个放得下」
            }
            if (i + 1 < n) {
                // 下一个前缀多出来的一步：前字符按替换后的形，后字符用原字符量字距。
                inner += getCharWidth(last, chars[i + 1]);
            }
        }

        if (bestFit <= 0) {
            // 即使省略号也放不下；保持渲染器安全，什么都不画，
            // 而不是让子串索引变为负数。
            return "";
        }
        return name.substring(0, bestFit) + ELLIPSIS;
    }

    /** 与 {@link #getStringWidthD(String)} 同一套全角括号替换。 */
    private static char replaceForMeasurement(char ch) {
        if (ch == '（') return '(';
        if (ch == '）') return ')';
        return ch;
    }

    /**
     * 原来那条一步一 {@code substring} 的折叠，只留给带控制码或换行的文本。
     *
     * <p>这类串上「前缀的宽」不等于「前缀各字宽之和」的口径太多（{@code §} 会连带吃掉下一个字符，
     * 换行让宽度取最长行），逐字累加推不动，就让 {@link #getStringWidthD(String)} 自己一步步量。
     * 歌词与歌名里基本见不到这两种字符，这条路径平时不点亮。</p>
     */
    private String trimByBackscan(String name, double width) {
        int idx = name.length();
        while (idx > 0) {
            String substring = name.substring(0, idx);

            if (this.getStringWidthD(substring + ELLIPSIS) <= width) {
                return substring + ELLIPSIS;
            }

            idx--;
        }

        return "";
    }

    public int getStringWidth(String text) {
        return Mth.floor(getStringWidthD(text));
    }

    /**
     * 字宽记忆表。
     *
     * <p>上限是必须的：键是任意文本，而调用方里有「歌词逐字前缀」这类会随歌曲不断产生新串的用法，
     * 一局游戏放上百首歌就会稳定地往里堆几万条，且永远不会被回收。超过上限时整表清掉而不是做 LRU：
     * 这件事罕见到不值得为它维护访问顺序，清掉之后当前这一屏的文本会在同一帧内重新量测填回来。</p>
     */
    private static final int STRING_WIDTH_CACHE_LIMIT = 20_000;

    private final Map<String, Double> stringWidthMapD = new HashMap<>();

    /**
     * 旧实现里这是「字形是否已经异步生成完」的查询。Skia 的字形是同步取用的，
     * 所以恒为 true。保留方法是因为有十几处调用方拿它决定「这一帧先不排版」，
     * 恒真正好让那些分支直接走正常路径。
     */
    public boolean areGlyphsLoaded(String text) {
        return true;
    }

    public double getStringWidthD(String text) {
        Double f = this.stringWidthMapD.get(text);
        if (f != null)
            return f;

        boolean shouldntAdd = false;

        char[] c = stripControlCodes(text).toCharArray();
        double currentLine = 0;
        double maxPreviousLines = 0;
        for (int i = 0; i < c.length; i++) {
            char c1 = c[i];
            char c2 = i + 1 < c.length ? c[i + 1] : '\0';

            if (c1 == '\n') {
                maxPreviousLines = Math.max(currentLine, maxPreviousLines);
                currentLine = 0;
                continue;
            }

            if (c1 == '（')
                c1 = '(';

            if (c1 == '）')
                c1 = ')';

            float charWidth = getCharWidth(c1, c2);

            if (!shouldntAdd) {
                shouldntAdd = charWidth == 0;
            }

            currentLine += charWidth;
        }

        if (!shouldntAdd) {
            if (this.stringWidthMapD.size() >= STRING_WIDTH_CACHE_LIMIT) {
                this.stringWidthMapD.clear();
            }
            this.stringWidthMapD.put(text, Math.max(currentLine, maxPreviousLines));
        }

        return Math.max(currentLine, maxPreviousLines);
    }

    public int getHeight() {
        return (int) this.getFontHeight();
    }

    public double getFontHeight() {
        return (this.fontHeight - 8.5) * .5;
    }

    @Override
    public void close() {
        closeSkiaFonts();
        skTypefaces = new Typeface[0];
        glyphBlocks = new GlyphRef[256][];
        stringWidthMapD.clear();
        fontHeight = -1;
    }

    private void closeSkiaFonts() {
        // Font changes invalidate glyph IDs, offsets and kerning in every blob.
        plainTextBlobs.close();
        for (io.github.humbleui.skija.Font f : skFonts) {
            f.close();
        }
        skFonts = new io.github.humbleui.skija.Font[0];
    }

    public static int[] RGBIntToRGB(int in) {
        int red = in >> 8 * 2 & 0xFF;
        int green = in >> 8 & 0xFF;
        int blue = in & 0xFF;
        return new int[]{red, green, blue};
    }

    float getCharWidth(char ch) {
        return getCharWidth(ch, '\0');
    }

    public float getCharWidth(char ch, char nextChar) {
        if (skFonts.length == 0) {
            return .0f;
        }
        GlyphRef ref = glyphRef(ch);
        if (ref.fontIndex < 0) {
            return .0f;
        }

        float width = ref.advance;

        // 添加字间距
        if (fontKerning != null && nextChar != '\0') {
            width += fontKerning.getKerning(ch, nextChar, sizePx);
        }

        return width;
    }

    public double getStringHeight(String text) {
        return countSplitLines(text) * (getFontHeight() + 4) - 4;
    }

    /**
     * {@code text.split("\n").length}，但不建数组。
     *
     * <p>每帧每个标签都会经 {@code LabelWidget} 问一次行高，而一次正则 split 就是「编译模式 + 分配
     * 若干个子串」。这里逐字数换行，语义与 {@link String#split} 完全对齐：没有换行就是 1 段（空串也是
     * 1 段，因为 split 无匹配时原样返回）；尾部那些空段不算数，所以整串全是换行时为 0 段。</p>
     */
    static int countSplitLines(String text) {
        if (text.indexOf('\n') < 0) {
            return 1;
        }
        int end = text.length() - 1;
        while (end >= 0 && text.charAt(end) == '\n') {
            end--;                    // 丢掉尾部的空段
        }
        if (end < 0) {
            return 0;                 // 全是换行：所有段都是空的，一个都不留
        }
        int lines = 1;
        for (int i = 0; i <= end; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 将给定的字符串 trim 到给定的 width。
     * 如果有空格则从空格开始分割
     * 否则从最后一个字符开始
     * 所有的分割点详见 {@link #findLineBreak(String, int, double)} 中的 breakable
     */
    public String[] fitWidth(String text, double width) {
        List<String> lines = new ArrayList<>();

        int i = 0;
        while (i < text.length()) {
            int previousI = i;
            LineBreakResult result = findLineBreak(text, i, width);

            lines.add(text.substring(i, result.endIndex));

            i = result.nextStartIndex;

            // 避免无限循环
            if (i == previousI) {
                i++;
            }
        }

        return lines.toArray(new String[0]);
    }

    @Getter
    static final char[] breakableChars = new char['\uFFFF' + 1];
    static String breakable  =  " .。,，!！?？;；、";
    static String wrapStarts =  "(（「『{[【<";
    static String wrapEnds   =  ")）」』}]】>";
    static {
        for (char c : breakable.toCharArray()) {
            breakableChars[c] = 2;
        }

        for (char c : wrapStarts.toCharArray()) {
            breakableChars[c] = 3;
        }

        for (char c : wrapEnds.toCharArray()) {
            breakableChars[c] = 1;
        }
    }

    private int findMatchingOpenBracket(String text, int closeIndex, int startIndex) {
        char closeChar = text.charAt(closeIndex);
        int closeCharType = wrapEnds.indexOf(closeChar);
        if (closeCharType == -1) {
            return -1;
        }

        char openChar = wrapStarts.charAt(closeCharType);
        int depth = 1;

        for (int i = closeIndex - 1; i >= startIndex; i--) {
            char c = text.charAt(i);

            if (i > startIndex && text.charAt(i - 1) == '\247') {
                i--;
                continue;
            }

            if (c == closeChar) {
                depth++;
            } else if (c == openChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private LineBreakResult findLineBreak(String text, int startIndex, double maxWidth) {
        double currentWidth = 0;
        int lastBreakableIndex = -1;
        int lastBreakableIndexPriority = 0;
        boolean lastBreakableTrimThisChar = false;

        for (int i = startIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            char nextChar = i + 1 < text.length() ? text.charAt(i + 1) : '\0';

            if (c == '\247' && i + 1 < text.length()) {
                i++;
                continue;
            }

            if (c == '\n') {
                return new LineBreakResult(i, i + 1);
            }

            int breakableCharValue = breakableChars[c];
            if (breakableCharValue > 0) {
                if (breakableCharValue == 1) {
                    int openIndex = findMatchingOpenBracket(text, i, startIndex);
                    if (openIndex != -1 && openIndex == lastBreakableIndex) {
                        lastBreakableIndexPriority = 2;
                        lastBreakableIndex = i;
                        lastBreakableTrimThisChar = false;
                    } else if (breakableCharValue >= lastBreakableIndexPriority) {
                        lastBreakableIndexPriority = breakableCharValue;
                        lastBreakableIndex = i;
                    }
                } else if (breakableCharValue >= lastBreakableIndexPriority) {
                    lastBreakableIndexPriority = breakableCharValue;
                    lastBreakableIndex = i;
                    lastBreakableTrimThisChar = (c == ' ');
                }
            }

            double charWidth = getCharWidth(c, nextChar);

            if (currentWidth + charWidth > maxWidth) {
                if (breakableCharValue > 0 && breakableCharValue >= lastBreakableIndexPriority) {
                    return handleBreakAtCurrentChar(i, breakableCharValue, lastBreakableTrimThisChar);
                }

                if (lastBreakableIndex != -1) {
                    return handleBreakAtLastBreakable(text, lastBreakableIndex, lastBreakableTrimThisChar, startIndex, i);
                }

                if (i == startIndex) {
                    return new LineBreakResult(startIndex + 1, startIndex + 1);
                }
                return new LineBreakResult(i, i);
            }

            currentWidth += charWidth;
        }

        return new LineBreakResult(text.length(), text.length());
    }

    private LineBreakResult handleBreakAtCurrentChar(int index, int breakableCharValue, boolean trimThisChar) {
        if (trimThisChar) {
            return new LineBreakResult(index, index + 1);
        }

        if (breakableCharValue == 3) {
            return new LineBreakResult(index, index);
        }

        return new LineBreakResult(index + 1, index + 1);
    }

    private LineBreakResult handleBreakAtLastBreakable(String text, int lastBreakableIndex,
                                                       boolean trimThisChar, int startIndex, int currentIndex) {
        if (trimThisChar) {
            return new LineBreakResult(lastBreakableIndex, lastBreakableIndex + 1);
        }

        char lastBreakableChar = text.charAt(lastBreakableIndex);
        int lastBreakableCharValue = breakableChars[lastBreakableChar];

        if (lastBreakableCharValue == 3) {
            if (lastBreakableIndex == startIndex) {
                if (currentIndex == startIndex) {
                    return new LineBreakResult(startIndex + 1, startIndex + 1);
                }
                return new LineBreakResult(currentIndex, currentIndex);
            }
            return new LineBreakResult(lastBreakableIndex, lastBreakableIndex);
        }

        return new LineBreakResult(lastBreakableIndex + 1, lastBreakableIndex + 1);
    }

    private static class LineBreakResult {
        final int endIndex;
        final int nextStartIndex;

        LineBreakResult(int endIndex, int nextStartIndex) {
            this.endIndex = endIndex;
            this.nextStartIndex = nextStartIndex;
        }
    }

    public void drawStringWithBetterShadow(String text, double x, double y, int color) {
        drawString(text, x, y, color);
    }

    public void drawOutlineCenteredString(String text, double x, double y, int color, int outlineColor) {
        drawOutlineString(text, x - getStringWidthD(text) * .5, y, color, outlineColor);
    }

    public void drawOutlineString(String text, double x, double y, int color, int outlineColor) {
        String outlinetext = StringUtils.removeFormattingCodes(text);
        drawString(outlinetext, x + 0.5, y, outlineColor);
        drawString(outlinetext, x - 0.5, y, outlineColor);
        drawString(outlinetext, x, y + 0.5, outlineColor);
        drawString(outlinetext, x, y - 0.5, outlineColor);
        drawString(text, x, y, color);
    }

    public int getWidth(String text) {
        return this.getStringWidth(text);
    }

    public float drawString(String text, double x, double y, double scale, int color) {

        SkijaGfx.pushMatrix();

        SkijaGfx.translate(x, y);
        SkijaGfx.scale(scale, scale);

        float f = drawString(text, 0, 0, color);

        SkijaGfx.popMatrix();
        return f;
    }

}
