package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.render.skija.SkijaClip;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.types.RRect;

/**
 * SPlayer-Next 外观在播放器里的落地口径：尺寸换算、语义色与几种反复出现的表面画法。
 *
 * <h2>尺寸</h2>
 * SPlayer 的规格写的是 CSS 像素（UnoCSS 类名，如 {@code h-10.5}、{@code w-60}）。播放器面板在
 * 1920 宽窗口下约 900 逻辑单位宽，而 SPlayer 的参考窗口约 1440 CSS 像素宽，所以统一按
 * {@link #UNIT} 折算：{@code px(42)} 就是 SPlayer 一行导航的高度。
 *
 * <h2>颜色</h2>
 * SPlayer 的配色全部是「语义色 + 透明度」（{@code bg-primary/10}、{@code text-on-surface-variant/50}），
 * 这里一一对应到 {@link NCMScreen.ColorType}：surface → 页面底色、surfacePanel → 导航底色、
 * onSurface → 主要文字、onSurfaceVariant → 次要文字、primary → 强调色。随主题实时变化，不写死。
 *
 * <p>所有画法都直接落在 Skija 画布上（GPU），不经过 CPU 位图。</p>
 */
public final class SPlayerStyle {

    /** 1 CSS 像素对应的播放器逻辑单位。 */
    public static final double UNIT = 0.6;

    /** 侧栏宽（{@code w-60}）。 */
    public static final double SIDEBAR_WIDTH = px(240);
    /** 顶栏高（{@code h-16}）。 */
    public static final double HEADER_HEIGHT = px(64);
    /** 底部播放栏高（{@code h-20}）。 */
    public static final double BAR_HEIGHT = px(80);
    /** 侧栏 Logo 行高（{@code h-16}）。 */
    public static final double LOGO_HEIGHT = px(64);
    /** 侧栏导航行高（SMenu medium：{@code h-10.5}）。 */
    public static final double NAV_ROW_HEIGHT = px(42);
    /** 带封面的歌单行高（{@code h-13}）。 */
    public static final double NAV_COVER_ROW_HEIGHT = px(52);
    /** 导航行之间的缝（{@code gap-1}）。 */
    public static final double NAV_GAP = px(4);
    /** 圆角：{@code rounded-md / lg / xl}。 */
    public static final double RADIUS_MD = px(6);
    public static final double RADIUS_LG = px(8);
    public static final double RADIUS_XL = px(12);

    private SPlayerStyle() {
    }

    /** CSS 像素 → 逻辑单位。 */
    public static double px(double css) {
        return css * UNIT;
    }

    // ------------------------------------------------------------------
    // 语义色
    // ------------------------------------------------------------------

    /** 内容区底色（surface）。 */
    public static int surface() {
        return NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
    }

    /** 侧栏、播放栏、卡片底色（surfacePanel）。 */
    public static int panel() {
        return NCMScreen.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND);
    }

    /** 卡片底色（SCard 的 bg-surface-panel）。 */
    public static int card() {
        return NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
    }

    public static int onSurface() {
        return NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
    }

    public static int onSurfaceVariant() {
        return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    public static int primary() {
        return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
    }

    /** 主色实心按钮上的文字色（onPrimary）：按对比度从深浅两端里挑，保证可读。 */
    public static int onPrimary() {
        return NcmExpressivePalette.live().onPrimary() & 0xFFFFFF;
    }

    /** 下拉菜单/浮层底色（surfaceBright）：在悬停色上朝文字色抬一档。 */
    public static int bright() {
        return NcmExpressivePalette.mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), onSurface(), .18)
                & 0xFFFFFF;
    }

    /** 给 0xRRGGBB 配上透明度（0..1），再乘上组件自身的整体透明度。 */
    public static int a(int rgb, double alpha, float widgetAlpha) {
        double value = Math.max(0.0, Math.min(1.0, alpha * widgetAlpha));
        return ((int) Math.round(value * 255.0) << 24) | (rgb & 0xFFFFFF);
    }

    public static int a(int rgb, double alpha) {
        return a(rgb, alpha, 1f);
    }

    // ------------------------------------------------------------------
    // 字体：SPlayer 的 text-xs/sm/base/lg/xl/2xl/3xl
    // ------------------------------------------------------------------

    /** {@code text-xs}（12px）。 */
    public static CFontRenderer textXs(boolean bold) {
        return bold ? FontManager.pf14bold : FontManager.pf14;
    }

    /** {@code text-sm}（14px）。 */
    public static CFontRenderer textSm(boolean bold) {
        return bold ? FontManager.pf16bold : FontManager.pf16;
    }

    /** {@code text-base}（16px）。 */
    public static CFontRenderer textBase(boolean bold) {
        return bold ? FontManager.pf18bold : FontManager.pf18;
    }

    /** {@code text-lg}（18px）。 */
    public static CFontRenderer textLg() {
        return FontManager.pf20bold;
    }

    /** {@code text-xl}（20px）。 */
    public static CFontRenderer textXl() {
        return FontManager.pf24bold;
    }

    /** {@code text-2xl}（24px）。 */
    public static CFontRenderer text2xl() {
        return FontManager.pf28bold;
    }

    /** {@code text-3xl}（30px）。 */
    public static CFontRenderer text3xl() {
        return FontManager.pf34bold;
    }

    // ------------------------------------------------------------------
    // 画法
    // ------------------------------------------------------------------

    public static void fill(double x, double y, double width, double height, double radius, int argb) {
        if ((argb >>> 24) == 0 || width <= 0 || height <= 0) return;
        SkijaShapes.roundedRect(x, y, width, height, Math.min(radius, Math.min(width, height) * .5), argb);
    }

    /**
     * 1 像素级细描边，外沿与矩形边界齐平（CSS {@code border} 的口径：描边画在盒子里面）。
     */
    public static void stroke(double x, double y, double width, double height, double radius,
                              double thickness, int argb) {
        if ((argb >>> 24) == 0 || width <= 0 || height <= 0 || SkijaClip.recording()) return;
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        double half = thickness * .5;
        double r = Math.max(0.0, Math.min(radius, Math.min(width, height) * .5) - half);
        RRect rect = RRect.makeXYWH((float) (x + half), (float) (y + half),
                (float) (width - thickness), (float) (height - thickness), (float) r);
        canvas.drawRRect(rect, SkijaGfx.stroke(argb, thickness));
    }

    public static void circle(double centerX, double centerY, double radius, int argb) {
        if ((argb >>> 24) == 0 || radius <= 0) return;
        SkijaShapes.roundedRect(centerX - radius, centerY - radius, radius * 2, radius * 2, radius, argb);
    }

    /** 竖直/水平细线（分隔线、侧栏右边框）。 */
    public static void line(double x1, double y1, double x2, double y2, double thickness, int argb) {
        if ((argb >>> 24) == 0 || SkijaClip.recording()) return;
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        Paint paint = SkijaGfx.stroke(argb, thickness);
        paint.setStrokeCap(PaintStrokeCap.BUTT);
        canvas.drawLine((float) x1, (float) y1, (float) x2, (float) y2, paint);
    }

    /**
     * SCard：{@code bg-surface-panel + border border-primary/20}，悬停时（hoverable）描边提亮一档、
     * 底色叠一层极淡的主色，对应网页上 {@code hover:shadow-md} 带来的「浮起来」。
     */
    public static void card(double x, double y, double width, double height, double radius,
                            float hover, float alpha) {
        fill(x, y, width, height, radius, a(card(), 1.0, alpha));
        if (hover > 0f) {
            fill(x, y, width, height, radius, a(primary(), .035 * hover, alpha));
        }
        stroke(x, y, width, height, radius, px(1), a(primary(), .20 + .08 * hover, alpha));
    }

    /**
     * 一行文字垂直居中时传给 {@code drawString} 的 y。
     *
     * <p>按中日韩字身（「国」）的墨迹带居中，与 {@code NcmSheet#inkTop} 同一口径：同一行里
     * 中文、数字、拉丁混排时基线一致，不会因为某一行全是小写字母就往上飘。</p>
     */
    public static double centeredTextY(CFontRenderer font, double centerY) {
        GlyphInkMetrics ink = GlyphInkMetrics.of(font, '国');
        if (ink == null) return centerY - font.getStringHeight("国") * .5;
        return ink.alignCenterY(centerY);
    }

    /** 在 {@code (x, centerY)} 左对齐、垂直居中地画一行文字，超宽截断。 */
    public static void text(CFontRenderer font, String text, double x, double centerY, double maxWidth, int argb) {
        if ((argb >>> 24) == 0 || text == null || text.isEmpty()) return;
        String shown = maxWidth > 0 ? truncate(font, text, maxWidth) : text;
        font.drawString(shown, x, centeredTextY(font, centerY), argb);
    }

    /** 文字超宽时按宽度截断并加省略号（truncate）。 */
    public static String truncate(CFontRenderer font, String text, double maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (font.getStringWidthD(text) <= maxWidth) return text;
        String ellipsis = "…";
        double budget = maxWidth - font.getStringWidthD(ellipsis);
        if (budget <= 0) return "";
        return font.trim(text, budget) + ellipsis;
    }

    /** 两个颜色之间按比例混合，透明度取前者。 */
    public static int mix(int from, int to, double amount) {
        return NcmExpressivePalette.mix(from, to, amount);
    }

    /** 帧间平滑逼近：用于悬停/按下的渐变（SPlayer 的 {@code transition-colors duration-200}）。 */
    public static float approach(float value, float target) {
        float next = value + (target - value) * .28f;
        return Math.abs(next - target) < .004f ? target : next;
    }
}
