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
     * 引导里算「账号通不通」要看的那几家。
     *
     * <p>必须和 {@code AccountManagerOverlay} 那张账号表是同一份名单（那里三行六格：
     * 网易云 / QQ / 酷狗、汽水 / 哔哩哔哩、Apple）。只轮询前三家的话，在账号管理器里登录了
     * 汽水、哔哩哔哩或 Apple 的玩家回到引导页仍然看到「未登录」，完成页摘要也写「稍后登录」——
     * 而他刚刚就在这个界面里登录过。</p>
     *
     * <p>YouTube 与 Spotify 不在这张表里：前者免登录（匿名就能搜能播），后者是元数据源、
     * 登录态只影响搜索与本人歌单，两者都不该把一个「还没有账号」的玩家判成没配好。</p>
     */
    public static final MusicPlatform[] ACCOUNT_PLATFORMS = {
            MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU,
            MusicPlatform.QISHUI, MusicPlatform.BILIBILI, MusicPlatform.APPLE
    };

    /** 这几家里有没有任一已登录。账号页那条状态条与完成页摘要共用，免得两处各写一份清单。 */
    public static boolean anyAccountLoggedIn() {
        for (MusicPlatform platform : ACCOUNT_PLATFORMS) {
            if (com.muoniumplayer.core.ncm.music.NeteaseMusicService.isLoggedIn(platform)) {
                return true;
            }
        }
        return false;
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
    /**
     * 当前这一页的排版档。
     *
     * <p>六页的签名是 {@code render(controller, x, y, width, height, mouseX, mouseY)}，为了不把
     * 「字号」再塞成第五个参数（那会牵动六个文件与全部调用点），档位挂在绘制上下文里，
     * 由 {@link OnboardingScreen} 在每次 {@code render} 前后成对设置。和 {@link #stepAlpha}
     * 是同一套写法、同一个生命周期，所以转场时新旧两页各自带着自己那一档画，不会串。</p>
     */
    private static OnboardingMetrics metric = OnboardingMetrics.forTier(OnboardingMetrics.Tier.REGULAR);

    private OnboardingUi() {
    }

    public static void beginMetric(OnboardingMetrics metrics) {
        metric = metrics == null ? OnboardingMetrics.forTier(OnboardingMetrics.Tier.REGULAR) : metrics;
    }

    /** 恢复成常规档。{@code OnboardingScreen} 那条 {@code finally} 里调，和 {@code endStep} 成对。 */
    public static void endMetric() {
        metric = OnboardingMetrics.forTier(OnboardingMetrics.Tier.REGULAR);
    }

    /** 当前这一页该用的排版档。六页从这里取字号与间距，不再各自写 {@code pf12} / {@code +52}。 */
    public static OnboardingMetrics metric() {
        return metric;
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
     * <p>{@code y} 是内容带的顶，正文自己从 {@link #metric()}{@code .bodyOffset} 起排。
     * 字号与三段偏移全部取自当前档位：大屏标题升到 pf40/pf50，极小窗口收到 pf20 并把副标题整段收掉
     * —— 副标题那一长句在 272 宽的带子里本来就画不全，留着它只会压到下面的卡片上。</p>
     */
    public static void drawHeader(String eyebrow, String title, String subtitle,
                                  double x, double y) {
        OnboardingMetrics m = metric();
        if (eyebrow != null && !eyebrow.isEmpty() && m.tier.showsSubtitle()) {
            drawEyebrow(eyebrow, x, y + m.eyebrowOffset);
        }
        drawText(m.titleFont(), title, x, y + m.titleOffset, textPrimary());
        if (subtitle != null && !subtitle.isEmpty() && m.tier.showsSubtitle()) {
            drawText(m.subtitleFont(), subtitle, x, y + m.subtitleOffset, textSecondary());
        }
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
     * 这段文字在 {@code available} 宽里放不放得下。
     *
     * <p>卡片里的文字位置过去是写死的（{@code x + 52}、{@code x + width * .5}），而卡片宽度是
     * 跟着容器缩的——窄到某个程度，两段文字就<b>自己压到自己身上</b>。所以凡是「一段字的右边
     * 还有另一段字 / 一个按钮」的地方，都先问一句这里放不放得下，放不下就走降级：
     * 收掉副标题、缩短文案、或者整段不画。宁可少一行字，也不许两行叠在一起。</p>
     */
    public static boolean fits(CFontRenderer font, String text, double available) {
        return available > 0 && textWidth(font, text) <= available;
    }

    /**
     * 把 {@code text} 截到 {@code maxWidth} 里，超出部分换成省略号。
     *
     * <p>按<b>实测宽度</b>逐字退，不是按字符数：中文一个字 12 逻辑像素、英文一个字母 6 个，
     * 按字数切会让「YouTube Music」这种串被切得只剩一半却还留着三个字的空。</p>
     *
     * <p>退到最后一个字符都放不下时返回空串——调用方据此整段不画，而不是画一个孤零零的「…」。</p>
     */
    public static String ellipsize(CFontRenderer font, String text, double maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        // 字体不在位时量不出宽度，也就没法截。返回空串而不是原样放行：
        // 原样放行在真机上等于「这行一定会画到边界外」，而没有字体的那一帧
        // 本来也画不出任何东西，收掉它不会少看到什么。
        if (font == null) {
            return "";
        }
        if (font.getStringWidthD(text) <= maxWidth) {
            return text;
        }
        double ellipsis = font.getStringWidthD("…");
        double budget = maxWidth - ellipsis;
        if (budget <= 0) {
            return "";
        }
        int end = text.length();
        while (end > 0 && font.getStringWidthD(text.substring(0, end)) > budget) {
            end--;
        }
        // 退到一半正好停在代理对中间时再退一格，免得切出一个孤立的高位代理画成方块。
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end <= 0 ? "" : text.substring(0, end) + "…";
    }

    /**
     * 画一行「左边一段、右边还有东西」的文字，并保证它们不相撞。
     *
     * @param rightReserve 右边要留出来的宽度（按钮、图标、另一段文字）
     * @return 真的画了才 true；空间不够（截完什么都不剩）时返回 false，调用方可以改走别的表达
     */
    public static boolean drawTextReserving(CFontRenderer font, String text,
                                            double x, double y, double width, double rightReserve,
                                            int color) {
        String fitted = ellipsize(font, text, width - rightReserve);
        if (fitted.isEmpty()) {
            return false;
        }
        drawText(font, fitted, x, y, color);
        return true;
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

    // ------------------------------------------------ 统一的卡片
    //
    // 六页过去各画各的：音源页是两列网格、账号页是整宽三行、Automix 页是整宽三行带按钮、
    // 完成页是一行摘要条。它们的内缩、圆角、选中态、hover 亮度都不一样，翻页时右侧那一块
    // 整片在换形状 —— 玩家读起来像「换了一个界面」，而不是「同一页换了个话题」。
    //
    // 下面这一组把「一张卡」定死：底色 / 描边 / 圆角 / 选中态 / 悬停 / 图标底盘 / 两行文字的
    // 位置，全部只在这里算一次。六页只决定「这一页有几张卡、每张写什么」。

    /** 一张卡的三种状态。 */
    public enum CardState {
        /** 没选中、没悬停。 */
        IDLE,
        /** 鼠标悬停，但没选中。 */
        HOVER,
        /** 选中（当前值）。 */
        SELECTED,
        /** 不可选（手机档的真玻璃主题）。灰掉，点了不生效。 */
        LOCKED
    }

    /**
     * 画一张卡的外壳，返回它内部「内容区」的矩形。
     *
     * <p>返回 {@code double[]}{@code {contentX, contentY, contentWidth, contentHeight}}：
     * 卡里的图标与文字都从这四个数推，不再各自写 {@code x + 13} / {@code rowY + 12} 这种字面量。
     * 卡高变（跟排版档走）时里面的东西会自动跟着居中，不会像过去那样「卡变高了，字还贴在上沿」。</p>
     *
     * @param accent 这张卡的代表色；选中与悬停时用它描边，{@code IDLE} 时只用于图标
     */
    public static double[] card(double x, double y, double width, double height,
                                CardState state, int accent) {
        if (state == null) {
            state = CardState.IDLE;
        }
        double radius = CARD_RADIUS;
        int fill;
        int border;
        double borderWidth;
        switch (state) {
            case SELECTED:
                // 选中态：一小片强调色底 + 一道实描边。不整块填满强调色——
                // 六张卡里同时出现两三个整块色，那一页就只剩颜色没有内容了。
                fill = colorWithAlpha(accent, 42);
                border = colorWithAlpha(accent, 225);
                borderWidth = 1.6;
                break;
            case HOVER:
                fill = surfaceHover();
                border = colorWithAlpha(accent, 150);
                borderWidth = 1.1;
                break;
            case LOCKED:
                fill = surface();
                border = hairline();
                borderWidth = 1;
                break;
            default:
                fill = surface();
                border = hairline();
                borderWidth = 1;
                break;
        }
        roundedRect(x, y, width, height, radius, fill);
        roundedOutline(x, y, width, height, radius, borderWidth, border);
        // 内缩：卡高越大内缩越多，大屏上卡片不会「一张大纸上一堆小字挤在左上角」。
        double inset = clampVar(height * .16, 8, 18);
        return new double[]{x + inset, y + inset, width - inset * 2, height - inset * 2};
    }

    /**
     * 画卡里的图标底盘 + 图标，返回底盘右侧文字该从哪儿起。
     *
     * @param box 底盘边长
     * @param content {@link #card} 返回的那个内容区
     */
    public static double cardIcon(String lucide, String fallback, double[] content,
                                  double box, int accent, CardState state) {
        double centerY = content[1] + content[3] * .5;
        double alphaScale = state == CardState.LOCKED ? .45f : 1f;
        roundedRect(content[0], centerY - box * .5, box, box, box * .3,
                colorWithAlpha(accent, (int) ((state == CardState.SELECTED ? 86 : 42) * alphaScale)));
        drawIcon(lucide, fallback, content[0] + box * .5, centerY, box * .62,
                state == CardState.LOCKED ? textMuted()
                        : (state == CardState.SELECTED ? accent : textSecondary()));
        return content[0] + box + 12;
    }

    /**
     * 卡里的两行字：主标题 + 副标题。
     *
     * <p>两行都按内容区右边界截断，主标题也不会压到卡片右边的角标（{@code rightReserve}）。
     * 大字号档上主标题自带的字身比行距高，所以基线用 {@code textH * .5} 现算，不写死 +12。</p>
     *
     * @param rightReserve 右侧要留出来的宽度（勾选角标、按钮、数值）
     * @return 副标题真的画了才 true（紧凑档或放不下时它是被收掉的）
     */
    public static boolean cardText(double[] content, double textX, String title, String subtitle,
                                   double rightReserve, CardState state) {
        com.muoniumplayer.core.rendering.font.CFontRenderer titleFont = metric().labelBold();
        com.muoniumplayer.core.rendering.font.CFontRenderer subFont = metric().bodyFont();
        double available = content[0] + content[2] - textX - rightReserve;
        boolean hasSubtitle = metric().tier.showsCardDetail() && subtitle != null && !subtitle.isEmpty();
        int titleColor = state == CardState.LOCKED ? textMuted()
                : (state == CardState.SELECTED ? textPrimary() : textSecondary());
        int subColor = state == CardState.SELECTED ? textSecondary() : textMuted();

        double titleH = OnboardingMetrics.lineHeight(titleFont);
        double subH = OnboardingMetrics.lineHeight(subFont);
        double total = hasSubtitle ? titleH + subH : titleH;
        // 两行整组在内容区里垂直居中；只有一行时也居中，而不是贴着上沿。
        double top = content[1] + (content[3] - total) * .5;

        drawText(titleFont, ellipsize(titleFont, title, available), textX, top, titleColor);
        if (hasSubtitle) {
            drawText(subFont, ellipsize(subFont, subtitle, available), textX, top + titleH, subColor);
        }
        return hasSubtitle;
    }

    /**
     * 卡右端那枚勾选角标（选中时才画）。
     *
     * <p>单独一个入口，是因为三处（音源、账号、Automix）过去各画各的：有的是勾、有的是箭头、
     * 有的干脆没有。选中态在整页里只该有一种长相。</p>
     */
    public static void cardSelectedBadge(double[] content, int accent, double box) {
        double centerX = content[0] + content[2] - box * .5;
        double centerY = content[1] + content[3] * .5;
        double radius = box * .5;
        roundedRect(centerX - box * .5, centerY - box * .5, box, box, radius,
                colorWithAlpha(accent, 210));
        drawCheckBadge(centerX, centerY, box * .62, 0xFF0B0C11);
    }

    /** 卡里的开关（沉浸感那一页用）：一枚胶囊，开是强调色 + 圆点在右。 */
    public static void cardSwitch(double[] content, boolean on, int accent) {
        double h = Math.min(20, content[3] * .62);
        double w = h * 1.75;
        double x = content[0] + content[2] - w;
        double y = content[1] + (content[3] - h) * .5;
        roundedRect(x, y, w, h, h * .5, on ? colorWithAlpha(accent, 225) : surfaceHover());
        if (!on) {
            roundedOutline(x, y, w, h, h * .5, 1, hairline());
        }
        double knob = h - 4;
        double knobX = on ? x + w - knob - 2 : x + 2;
        roundedRect(knobX, y + 2, knob, knob, knob * .5, on ? 0xFFFFFFFF : textMuted());
    }

    private static double clampVar(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
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
