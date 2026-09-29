package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.BrandVectorIcons;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.Map;

/** 新手引导模块共享的低层绘制助手。 */
public final class OnboardingUi {
    // token 镜像官网（stile/assets/css/style.css）：黑曜石背景、
    //低 alpha 玻璃、暖红/橙品牌渐变和克制的 aqua/violet 强调。
    public static final int BACKDROP = RGBA.color(14, 16, 22, 255);
    public static final int GLASS = RGBA.color(25, 27, 35, 248);
    public static final int GLASS_2 = RGBA.color(38, 41, 51, 250);
    public static final int WHITE = RGBA.color(238, 240, 245, 255);
    public static final int SOFT = RGBA.color(180, 187, 202, 255);
    public static final int DIM = RGBA.color(138, 145, 164, 255);
    public static final int LINE = RGBA.color(255, 255, 255, 28);
    public static final int BRAND = RGBA.color(216, 30, 58, 255);
    public static final int BRAND_2 = RGBA.color(255, 120, 72, 255);
    public static final int BLUE = BRAND;
    public static final int CYAN = BRAND_2;
    public static final int AQUA = RGBA.color(95, 208, 230, 255);
    public static final int VIOLET = RGBA.color(142, 124, 255, 255);
    public static final int GREEN = RGBA.color(112, 214, 151, 255);

    /** {@link #drawEyebrow} 里文字相对那条 22 px 短横的起点偏移（短横 22 + 间距 9）。 */
    private static final double EYEBROW_TEXT_X = 31;

    // ------------------------------------------------ 跟着主题走的那一档
    //
    // 上面那批常量是官网暗色写死的 RGB。引导换成播放器的骨架之后，正文若继续用它们，
    // 「瓷白墨」这套浅色主题下就是一堆白字压在浅色面板上——而玩家恰恰就是在这一页挑主题的，
    // 挑完立刻就能看见。所以正文一律走下面这几个。
    //
    // 它们是<b>方法</b>不是常量：{@code NCMScreen.getColor} 每帧回的是<b>过渡中</b>的颜色
    // （换主题有 950ms 插值），存进 static final 等于把主题色冻在类加载那一刻，
    // 玩家换完主题要重启才看得到，那比原来那套写死的暗色还糟。

    public static int textPrimary() {
        return themed(NCMScreen.ColorType.PRIMARY_TEXT, 1.0f);
    }

    public static int textSecondary() {
        return themed(NCMScreen.ColorType.SECONDARY_TEXT, 1.0f);
    }

    /** 比次要文字再淡一档：占位符、提示行。主题里没有第四个灰，所以从 SECONDARY_TEXT 折。 */
    public static int textMuted() {
        return themed(NCMScreen.ColorType.SECONDARY_TEXT, .72f);
    }

    public static int surface() {
        return themed(NCMScreen.ColorType.ELEMENT_BACKGROUND, .97f);
    }

    public static int surfaceHover() {
        return themed(NCMScreen.ColorType.ELEMENT_HOVER, .98f);
    }

    public static int hairline() {
        return themed(NCMScreen.ColorType.BORDER, .11f);
    }

    public static int input() {
        return themed(NCMScreen.ColorType.INPUT_BACKGROUND, 1.0f);
    }

    public static int accent() {
        return themed(NCMScreen.ColorType.ACCENT, 1.0f);
    }

    public static int accentHover() {
        return themed(NCMScreen.ColorType.ACCENT_HOVER, 1.0f);
    }

    /**
     * 主题色 + 一个 alpha，折回一个 ARGB。
     *
     * <p>必须自己拼 alpha：主题色只带 RGB（{@code ACCENT} 就是 {@code 0x00RRGGBB}），
     * 直接交给 Skia 会当成「全透明」而一笔都不画。播放器的控件树里 color 与 alpha 本来是
     * 两个 setter，这里只是把它们并回一个数。</p>
     */
    public static int themed(NCMScreen.ColorType type, float alpha) {
        return RGBA.color(NCMScreen.getColor(type), alpha);
    }

    private static final Map<CFontRenderer, GlyphInkMetrics> PARTY_EM_SQUARE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();
    private static float stepAlpha = 1.0f;

    private OnboardingUi() {
    }

    public static void beginStep(float alpha) {
        stepAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
    }

    public static void endStep() {
        stepAlpha = 1.0f;
    }

    private static int modulate(int color) {
        return SkijaGfx.mulAlpha(color, stepAlpha);
    }

    private static Color modulate(Color color) {
        if (color == null) return null;
        int argb = SkijaGfx.argb(color);
        int modulated = modulate(argb);
        return new Color((modulated >>> 16) & 255, (modulated >>> 8) & 255,
                modulated & 255, (modulated >>> 24) & 255);
    }

    public static void roundedRect(double x, double y, double width, double height,
                                   double radius, int argb) {
        SkijaShapes.roundedRect(x, y, width, height, radius, modulate(argb));
    }

    public static void roundedRect(double x, double y, double width, double height,
                                   double radius, int r, int g, int b, int a) {
        SkijaShapes.roundedRect(x, y, width, height, radius, RGBA.color(r, g, b, a));
    }

    public static void roundedOutline(double x, double y, double width, double height,
                                      double radius, double thickness, int argb) {
        SkijaShapes.roundedOutline(x, y, width, height, radius, thickness, modulate(argb));
    }

    public static void roundedOutline(double x, double y, double width, double height,
                                      double radius, double thickness, Color color) {
        SkijaShapes.roundedOutline(x, y, width, height, radius, thickness,
                modulate(color).getRGB());
    }

    public static void roundedOutlineGradient(double x, double y, double width, double height,
                                              double radius, double thickness,
                                              Color bottomLeft, Color topLeft,
                                              Color bottomRight, Color topRight) {
        SkijaShapes.roundedOutlineGradient(x, y, width, height, radius, thickness,
                modulate(bottomLeft), modulate(topLeft),
                modulate(bottomRight), modulate(topRight));
    }

    public static void roundedGradient(double x, double y, double width, double height,
                                       double radius, int startArgb, int endArgb) {
        SkijaShapes.roundedRectGradient(x, y, width, height, radius,
                modulate(startArgb), modulate(endArgb), true);
    }

    /**
     * 竖向渐隐：顶透到底，用在内容带下沿，给「被裁掉的那一截」一个说法。
     *
     * <p>和 {@link #roundedGradient} 只差方向（那条恒为横向），所以不并进去。</p>
     */
    public static void verticalFade(double x, double y, double width, double height,
                                    int topArgb, int bottomArgb) {
        SkijaShapes.roundedRectGradient(x, y, width, height, 0,
                modulate(topArgb), modulate(bottomArgb), false);
    }

    public static void drawEyebrow(String text, double x, double y) {
        roundedRect(x, y + 6, 22, 1, .5, colorWithAlpha(BRAND_2, 155));
        drawText(FontManager.pf12bold, text, x + EYEBROW_TEXT_X, y, BRAND_2);
    }

    // ------------------------------------------------ 每页顶部那三行
    //
    // 六页过去各写各的：账号页与音源页没有 eyebrow、标题写在 +22；Automix 页有 eyebrow、标题在
    // +31、正文在 +103；外观页正文在 +95。于是「翻页」这个动作本身就在上下跳，而每页的标题
    // 看起来像三种不同的东西。下面这一组常量是六页共用的表头，正文一律从 BODY_OFFSET 起。

    /** eyebrow（那条 22 px 短横 + 英文小标）相对页顶的偏移。 */
    public static final double EYEBROW_OFFSET = 4;
    /** 大标题相对页顶的偏移。 */
    public static final double TITLE_OFFSET = 30;
    /** 副标题相对页顶的偏移。 */
    public static final double SUBTITLE_OFFSET = 72;
    /** 正文第一行的 y 偏移：六页同一个数，翻页时内容不上下跳。 */
    public static final double BODY_OFFSET = 96;

    /** 卡片圆角：以前 13 / 14 / 15 / 16 在一页里同时出现。 */
    public static final double CARD_RADIUS = 15;

    /**
     * 画一页的表头：eyebrow + 标题 + 副标题。
     *
     * <p>{@code y} 是内容带的顶，正文自己从 {@code y + }{@link #BODY_OFFSET} 起排。</p>
     */
    public static void drawHeader(String eyebrow, String title, String subtitle,
                                  double x, double y) {
        if (eyebrow != null && !eyebrow.isEmpty()) {
            drawEyebrow(eyebrow, x, y + EYEBROW_OFFSET);
        }
        drawText(FontManager.pf34bold, title, x, y + TITLE_OFFSET, textPrimary());
        drawText(FontManager.pf14, subtitle, x, y + SUBTITLE_OFFSET, textSecondary());
    }

    /**
     * 整条 eyebrow（短横 + 间距 + 文字）以 {@code centerX} 居中。
     *
     * <p>{@link #drawEyebrow} 的 x 是<b>左端</b>，所以居中要减掉的是「短横那 31 加上量出来的
     * 文字宽」，而不是文字宽的一半。</p>
     */
    public static void drawEyebrowCentered(String text, double centerX, double y) {
        double width = EYEBROW_TEXT_X + textWidth(FontManager.pf12bold, text);
        drawEyebrow(text, centerX - width * .5, y);
    }

    /** 一行文字实测多宽。字体或文字缺失时给 0，让调用方的算式照常成立。 */
    public static double textWidth(CFontRenderer font, String text) {
        if (font == null || text == null) {
            return 0.0;
        }
        return font.getStringWidthD(text);
    }

    /**
     * 以 {@code centerX} 为中心画一行字。
     *
     * <p>以前各处写死 {@code centerX - 174} 这种「大概是一半宽」的数：换字体、改文案、
     * 或者中英文混排一变长，它就整体往左偏，而且偏得很有规律——一页里三行各偏各的，
     * 看着像版式在飘。居中这件事只有量出来这一种正确做法。</p>
     */
    public static void drawCentered(CFontRenderer font, String text, double centerX,
                                    double y, int color) {
        drawText(font, text, centerX - textWidth(font, text) * .5, y, color);
    }

    public static void drawText(CFontRenderer font, String text, double x, double y, int color) {
        if (font != null && text != null) {
            font.drawString(text, x, y, modulate(color));
        }
    }

    public static void drawIcon(String icon, CFontRenderer font, double x, double y, int color) {
        if (font != null && icon != null) {
            font.drawString(icon, x, y, modulate(color));
        }
    }

    private static CFontRenderer iconFont(double box) {
        if (box <= 28.0) return FontManager.partyIcon24;
        if (box <= 36.0) return FontManager.partyIcon32;
        if (box <= 48.0) return FontManager.partyIcon40;
        return FontManager.partyIcon56;
    }

    private static GlyphInkMetrics partyEmSquare(CFontRenderer font) {
        if (font == null) return null;
        synchronized (PARTY_EM_SQUARE) {
            if (PARTY_EM_SQUARE.containsKey(font)) return PARTY_EM_SQUARE.get(font);
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, PartyIcons.FRAME.charAt(0));
        synchronized (PARTY_EM_SQUARE) {
            PARTY_EM_SQUARE.put(font, measured);
        }
        return measured;
    }

    public static void drawPartyIcon(String icon, double centerX, double centerY,
                                     double box, int color) {
        if (icon == null || icon.isEmpty()) return;
        CFontRenderer font = iconFont(box);
        GlyphInkMetrics square = partyEmSquare(font);
        if (square == null) {
            drawIcon(icon, font, centerX - font.getStringWidthD(icon) * .5,
                    centerY - font.getHeight() * .5, color);
            return;
        }
        drawIcon(icon, font, square.alignCenterX(centerX), square.alignCenterY(centerY), color);
    }

    public static void drawBrandIcon(String icon, CFontRenderer font,
                                     double centerX, double centerY, int color) {
        if (icon == null || icon.isEmpty() || font == null) return;
        GlyphInkMetrics ink = GlyphInkMetrics.of(font, icon.charAt(0));
        if (ink == null) {
            drawIcon(icon, font, centerX - font.getStringWidthD(icon) * .5,
                    centerY - font.getHeight() * .5, color);
            return;
        }
        drawIcon(icon, font, ink.alignCenterX(centerX), ink.alignCenterY(centerY), color);
    }

    public static boolean inside(double mouseX, double mouseY,
                                 double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public static int colorWithAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    public static Color awtColorWithAlpha(int color, int alpha) {
        return new Color((color >>> 16) & 255, (color >>> 8) & 255, color & 255,
                Math.max(0, Math.min(255, alpha)));
    }

    public static void drawPlatformIcon(MusicPlatform platform,
                                         double centerX, double centerY, double box, int color) {
        // 有矢量字标的三家先走矢量：和音源卡、账号头像位共用同一份数据，尺寸同样折 box 的一半。
        // 不在帧内或路径解析失败时它返回 false，继续往下走字形字标。
        if (BrandVectorIcons.drawCentered(platform, centerX, centerY,
                box * BrandVectorIcons.MARK_RATIO, color)) {
            return;
        }
        if (platform == MusicPlatform.NETEASE) {
            drawBrandIcon(MusicBrandIcons.NETEASE_CLOUD_MUSIC, FontManager.musicBrand18,
                    centerX, centerY, color);
        } else if (platform == MusicPlatform.QQ) {
            drawBrandIcon(MusicBrandIcons.QQ_MUSIC, FontManager.qqMusicIcon20,
                    centerX, centerY, color);
        } else if (platform == MusicPlatform.KUGOU) {
            drawBrandIcon(MusicBrandIcons.KUGOU_MONOGRAM, FontManager.musicBrand18,
                    centerX, centerY, color);
        } else if (platform == MusicPlatform.YOUTUBE) {
            drawBrandIcon(MusicBrandIcons.YOUTUBE, FontManager.youtubeIcon24,
                    centerX, centerY, color);
        } else if (platform == MusicPlatform.BILIBILI) {
            drawBrandIcon(MusicBrandIcons.BILIBILI_MONOGRAM, FontManager.pf18bold,
                    centerX, centerY, color);
        } else if (platform == MusicPlatform.QISHUI) {
            drawBrandIcon(MusicBrandIcons.QISHUI_MONOGRAM, FontManager.pf18bold,
                    centerX, centerY, color);
        } else {
            drawPartyIcon(PartyIcons.RADIO, centerX, centerY, box, color);
        }
    }

    /**
     * lucide 矢量图标（与播放器侧栏、Folia 设置弹窗同一批几何，见 {@code tools/make_lucide_icons.py}）。
     *
     * <p>返回 false 表示这一枚<b>没画出来</b>：不在 Skija 帧内、名字没登记、或路径解析失败。
     * 调用方要自己兜底——和 {@link #drawPlatformIcon} 同一套约定：矢量优先，退路是字体字形，
     * 最差也只是「这里少一枚图标」，而不是「这一页画崩了」。向导是玩家进游戏看到的第一个东西，
     * 它不许因为一枚图标没数据而留一块空洞。</p>
     */
    public static boolean drawLucide(String name, double centerX, double centerY,
                                     double box, int color) {
        return name != null && box > 0
                && FoliaLucideIcons.draw(name, centerX, centerY, box, modulate(color));
    }

    /**
     * 一枚图标：先画 lucide 矢量，画不出来再拿 {@code fallback} 那枚字体字形顶上。
     *
     * <p>向导里所有图标都走这一个入口。矢量那套在没有 Skija 的机器上会整层退掉，
     * 那时宁可字形对不上，也不要六页里所有图标位置同时开洞。</p>
     */
    public static void drawIcon(String lucide, String fallback, double centerX, double centerY,
                                double box, int color) {
        if (drawLucide(lucide, centerX, centerY, box, color)) {
            return;
        }
        if (fallback != null) {
            drawPartyIcon(fallback, centerX, centerY, box, color);
        }
    }

    /** 侧栏六个条目各自那枚 lucide 图标（欢迎复用 {@code sparkles}）。 */    static String stepLucide(OnboardingStep step) {
        switch (step) {
            case ACCOUNT: return FoliaLucideIcons.USER;
            case SOURCE: return FoliaLucideIcons.SOURCES;
            case APPEARANCE: return FoliaLucideIcons.PALETTE;
            case AUTOMIX: return FoliaLucideIcons.AUTOMIX;
            case FINISH: return FoliaLucideIcons.DONE;
            default: return FoliaLucideIcons.SPARKLE;
        }
    }

    /**
     * 某个步骤的条目图标：先画矢量，画不出来再退回 {@link #stepIcon} 那套 Material 字体字形。
     *
     * <p>侧栏、完成页摘要、以及任何「这一步是干什么的」的地方都走这一个入口，
     * 免得同一枚「连接账号」在侧栏是 lucide、到摘要行变成另一套笔画粗细。</p>
     */
    public static void drawStepIcon(OnboardingStep step, double centerX, double centerY,
                                    double box, int color) {
        if (drawLucide(stepLucide(step), centerX, centerY, box, color)) {
            return;
        }
        drawPartyIcon(stepIcon(step), centerX, centerY, box, color);
    }

    /**
     * 一枚「做完了」的勾：lucide 的 {@code check}，画不出来时退回 Material 那枚。
     *
     * <p>侧栏走过的那一步在底盘右下角压它，完成页的摘要行也用它。单独开一个入口是为了
     * 让「同一件事在两个地方长得一样」这件事只有一处实现。</p>
     */
    public static void drawCheckBadge(double centerX, double centerY, double box, int color) {
        if (drawLucide(FoliaLucideIcons.CHECK, centerX, centerY, box, color)) {
            return;
        }
        drawPartyIcon(PartyIcons.CHECK, centerX, centerY, box, color);
    }

    public static String stepIcon(OnboardingStep step) {
        switch (step) {
            case ACCOUNT: return PartyIcons.PERSON;
            case SOURCE: return PartyIcons.RADIO;
            case APPEARANCE: return PartyIcons.TEXT_FORMAT;
            case AUTOMIX: return PartyIcons.SYNC;
            case FINISH: return PartyIcons.CHECK;
            default: return PartyIcons.NOTE;
        }
    }
}
