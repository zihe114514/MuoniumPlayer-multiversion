package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.AccountManagerOverlay;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.onboarding.steps.AccountStep;
import com.muoniumplayer.core.screens.onboarding.steps.AppearanceStep;
import com.muoniumplayer.core.screens.onboarding.steps.AutomixModelStep;
import com.muoniumplayer.core.screens.onboarding.steps.FinishStep;
import com.muoniumplayer.core.screens.onboarding.steps.SourceStep;
import com.muoniumplayer.core.screens.onboarding.steps.WelcomeStep;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaClip;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import today.opai.api.features.ExtensionScreen;

import java.util.List;

/**
 * 独立的首次运行界面。既有播放器功能通过
 * 账号覆盖层、音乐服务、主题和字体配置系统复用。
 */
public final class OnboardingScreen extends ExtensionScreen
        implements SharedConstants, OnboardingController {

    private static final long STEP_TRANSITION_NANOS = 360_000_000L;
    private static final double STEP_DESIGN_WIDTH = 620.0;
    private static final double STEP_DESIGN_HEIGHT = 410.0;
    /**
     * 缩放下限。
     *
     * <p>原来是 0.82：那是「540 逻辑高的大屏」那一档的比例，不是可读性阈值。GUI Scale 3
     * （854×480 全屏是 284×160）下内容带只有 60 多逻辑高，0.82 会把设计稿撑到内容框的
     * 5 倍高，正文和按钮行叠成一片。文字在 0.55 已经接近糊的边界，再低只是让裁剪少一点，
     * 所以这里取 0.55，并由 {@link #clipStep} 兜住超出部分。</p>
     */
    private static final double MIN_READABLE_SCALE = 0.55;

    private final OnboardingStepView[] steps = {
            new WelcomeStep(), new AccountStep(), new SourceStep(),
            new AppearanceStep(), new AutomixModelStep(), new FinishStep()
    };

    private int stepIndex;
    private int previousStepIndex = -1;
    private int transitionDirection;
    private long transitionStartedAt = -1L;
    private double alpha;
    private long pageStartedAt = System.nanoTime();
    private long openedAt = pageStartedAt;
    private AccountManagerOverlay accountOverlay;
    /**
     * 抓来的那一帧游戏画面，铺在骨架后面。
     *
     * <p>播放器本体是靠液态玻璃让游戏「透」进来（面板 alpha 只掉 14%），引导不能这么办：
     * 它要把整屏当背景，而 Skia 画在自己的透明离屏上，透进来的只有窗口后头那块真实桌面。
     * 所以这里必须自己把游戏帧读回来，见 {@link OnboardingBackdrop}。</p>
     */
    private final OnboardingBackdrop backdrop = new OnboardingBackdrop();
    /**
     * 压在引导页之上的那层 hello。
     *
     * <p>只在<b>没放过</b>的时候才 new：这枚 SVG 有 7 KB 且解析一次要建整棵节点树，
     * 老玩家第二次进引导不该为它付这笔钱。</p>
     */
    private OnboardingOverlay overlay;
    private boolean skipConfirm;
    private MusicPlatform selectedPlatform;
    private NCMTheme.ThemePreset selectedTheme;
    private List<UiFont> fonts;
    private int selectedFontIndex;

    public OnboardingScreen() {
        UiFontLibrary.ensureScanned();
        selectedPlatform = NeteaseMusicService.getCurrentPlatform();
        selectedTheme = NCMTheme.getCurrent();
        fonts = UiFontLibrary.getCatalog();
        selectedFontIndex = 0;
        UiFont active = UiFontLibrary.getActive();
        for (int i = 0; i < fonts.size(); i++) {
            if (active != null && active.equals(fonts.get(i))) {
                selectedFontIndex = i;
                break;
            }
        }
        if (!OnboardingState.isIntroSeen()) {
            overlay = new OnboardingOverlay();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY) {
        RenderSystem.refreshResolution();
        RenderSystem.FIXED_SCALE = false;
        alpha = Interpolations.interpolate((float) alpha, 1f, .18f);

        double width = RenderSystem.getWidth();
        double height = RenderSystem.getHeight();
        double sx = RenderSystem.getWidthNotScaled() / width;
        double sy = RenderSystem.getHeightNotScaled() / height;
        double mx = mouseX / sx;
        double my = mouseY / sy;

        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().scale(sx, sy, 1);
        try {
            // 底：抓回来的那一帧游戏画面（糊过、压暗）。抓帧是异步的，到货之后它自己淡入，
            // 淡没满之前下面一直垫着官网那套黑曜石氛围底——两段之间是交叉溶，不是硬切。
            backdrop.prepare();
            backdrop.draw(width, height, (float) alpha, () -> drawStageBackdrop(width, height));

            // 骨架：面板位置、圆角、侧栏宽、底栏高全部取自播放器本体，
            // 所以按「完成」切到 NCMScreen 的那一下，界面一个像素都不跳。
            OnboardingChrome.Panel chrome = OnboardingChrome.of(width, height);
            OnboardingChrome.draw(chrome, (float) alpha);

            // 覆盖层还挂着的时候，正文拿不到鼠标：底下 hover 亮着会透过模糊层露出来，
            // 看着像界面在乱闪。
            boolean overlayBlocks = overlay != null && overlay.blocking();
            double hx = overlayBlocks ? -1 : mx;
            double hy = overlayBlocks ? -1 : my;

            Bands bands = Bands.of(chrome);
            clipStep(bands.x, bands.y, bands.width, bands.height);
            try {
                renderStepWithTransition(bands.x, bands.y, bands.width, bands.height, hx, hy);
                drawContentFade(bands, (float) alpha);
            } finally {
                SkijaClip.endClip();
            }

            drawRail(chrome, hx, hy);
            drawNavigation(NavLayout.of(chrome, skipConfirm), hx, hy);

            if (overlay != null) {
                overlay.draw(width, height, (float) alpha);
            }

            if (accountOverlay != null) {
                OnboardingUi.endStep();
                accountOverlay.setBounds(0, 0, width, height);
                accountOverlay.setAlpha((float) alpha);
                accountOverlay.renderWidget(mx, my, Mouse.getDWheel());
                if (accountOverlay.shouldClose()) {
                    accountOverlay.dispose();
                    accountOverlay = null;
                }
            }
        } finally {
            OnboardingUi.endStep();
            api.getGLStateManager().popMatrix();
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void renderStepWithTransition(double contentX, double contentY,
                                          double contentWidth, double contentHeight,
                                          double mouseX, double mouseY) {
        double progress = stepTransitionProgress();
        if (progress < 1.0 && previousStepIndex >= 0) {
            double eased = easeOutCubic(progress);
            double direction = transitionDirection >= 0 ? 1.0 : -1.0;
            double oldOffset = -direction * eased * 34.0;
            double newOffset = direction * (1.0 - eased) * 34.0;
            float oldAlpha = (float) (1.0 - eased);
            float newAlpha = (float) eased;
            double baseScale = stepScale(contentWidth, contentHeight);
            double oldScale = baseScale * (1.0 - 0.035 * eased);
            double newScale = baseScale * (0.965 + 0.035 * eased);

            renderStep(steps[previousStepIndex], contentX, contentY,
                    contentWidth, contentHeight, mouseX, mouseY,
                    oldOffset, oldScale, oldAlpha);
            renderStep(steps[stepIndex], contentX, contentY,
                    contentWidth, contentHeight, mouseX, mouseY,
                    newOffset, newScale, newAlpha);
            return;
        }

        double scale = stepScale(contentWidth, contentHeight);
        renderStep(steps[stepIndex], contentX, contentY,
                contentWidth, contentHeight, mouseX, mouseY,
                0.0, scale, 1.0f);
    }

    private void renderStep(OnboardingStepView step,
                            double contentX, double contentY,
                            double contentWidth, double contentHeight,
                            double mouseX, double mouseY,
                            double offsetX, double scale, float alpha) {
        double localWidth = contentWidth / scale;
        double localHeight = contentHeight / scale;
        double localMouseX = (mouseX - contentX - offsetX) / scale;
        double localMouseY = (mouseY - contentY) / scale;

        OnboardingUi.beginStep(alpha);
        SkijaGfx.pushMatrix();
        SkijaGfx.translate(contentX + offsetX, contentY);
        SkijaGfx.scale(scale, scale);
        step.render(this, 0.0, 0.0, localWidth, localHeight, localMouseX, localMouseY);
        SkijaGfx.popMatrix();
        OnboardingUi.endStep();
    }

    /**
     * 内容带下沿那道渐隐，只在正文真的被裁的时候出现。
     *
     * <p>可读性下限（{@link #MIN_READABLE_SCALE}）撑住字号之后，正文会比内容带高，多出来的一截
     * 由 {@link #clipStep} 裁掉——裁切本身是对的（叠字是「界面坏了」，裁切只是「窗口太小」），
     * 但「一张卡被齐刷刷切掉八像素」看着也像坏了。溶回面板底色之后，它读起来才是「下面还有」。</p>
     */
    private void drawContentFade(Bands bands, float alpha) {
        double drawn = STEP_DESIGN_HEIGHT * stepScale(bands.width, bands.height);
        if (drawn <= bands.height + 6.0) {
            return;
        }
        double height = Math.min(18.0, bands.height * .16);
        int base = NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
        OnboardingUi.verticalFade(bands.x, bands.y + bands.height - height, bands.width, height,
                RGBA.color(base, 0f), RGBA.color(base, (float) (alpha * .92)));
    }

    /**
     * 抓不到画面时的底：黑曜石 + 三团品牌光晕 + 52 px 网格。
     *
     * <p>骨架本身是半透明的（跟播放器一样让底透进来），所以这层得自己有点内容，
     * 不然引导页看着像没加载完。抓帧失败的原因已经由 {@code ScreenCapture} 与
     * {@code OnboardingBackdrop} 各打了一行日志。</p>
     */
    private void drawStageBackdrop(double width, double height) {
        Rect.draw(0, 0, width, height, OnboardingUi.BACKDROP);
        double t = (System.nanoTime() - openedAt) / 1_000_000_000.0;
        double breathe = .5 + .5 * Math.sin(t * .38);

        // 官网氛围：暖色品牌光晕、冷色 aqua/violet 逆光，
        //然后是克制的 52 px 网格，使界面保持可辨识的技术感。
        OnboardingUi.roundedRect(-width * .18, -height * .34, width * .72, height * .92, height * .46,
                216, 30, 58, (int) (22 + breathe * 13));
        OnboardingUi.roundedRect(width * .68, -height * .20, width * .48, height * .72, height * .36,
                95, 208, 230, (int) (10 + (1.0 - breathe) * 8));
        OnboardingUi.roundedRect(width * .26, height * .76, width * .54, height * .48, height * .24,
                142, 124, 255, 13);

        int grid = OnboardingUi.colorWithAlpha(OnboardingUi.WHITE, 7);
        for (double gx = 0; gx < width; gx += 52) Rect.draw(gx, 0, 1, height, grid);
        for (double gy = 0; gy < height; gy += 52) Rect.draw(0, gy, width, 1, grid);
        for (int i = 0; i < 7; i++) {
            double x = width * (.05 + i * .155) + Math.sin(t * .24 + i) * 8;
            double y = height * (.13 + (i % 3) * .29);
            OnboardingUi.roundedRect(x, y, 3, 3, 1.5,
                    OnboardingUi.colorWithAlpha(i % 2 == 0 ? OnboardingUi.BRAND_2 : OnboardingUi.AQUA,
                            52 + i * 5));
        }
    }

    /**
     * 侧栏里那一列步骤。
     *
     * <p>位置只由骨架决定，绘制与命中测试读同一份。做成<b>可点</b>的：引导里最常发生的事是
     * 「刚才那个音源选错了」，让玩家像用播放器一样点侧栏回去，而不是把「上一步」按四遍。</p>
     */
    private void drawRail(OnboardingChrome.Panel chrome, double mouseX, double mouseY) {
        Rail rail = Rail.of(chrome);
        OnboardingStep[] all = OnboardingStep.values();
        if (rail.collapsed) {
            // 只报「走到第几步」，列表整条不画。见 Rail#collapsed。
            OnboardingUi.drawText(FontManager.pf12bold,
                    "第 " + (stepIndex + 1) + " / " + all.length + " 步",
                    rail.x, rail.y, OnboardingUi.textPrimary());
            if (rail.fits(all[stepIndex].getTitle())) {
                OnboardingUi.drawText(FontManager.pf12, all[stepIndex].getTitle(),
                        rail.x, rail.y + 16, OnboardingUi.textSecondary());
            }
            return;
        }
        double radius = (rail.rowHeight - 4) * .5;
        for (int i = 0; i < all.length; i++) {
            OnboardingStep step = all[i];
            String title = step.getTitle();
            boolean current = i == stepIndex;
            boolean visited = i < stepIndex;
            boolean hover = !current && rail.hit(mouseX, mouseY, i);
            if (current) {
                // 播放器侧栏的选中态就是「一行圆角药丸压在列表下」，这里同一形状、同一相对尺寸。
                OnboardingUi.roundedRect(rail.x - 6, rail.top(i) + 2, rail.width + 12,
                        rail.rowHeight - 4, radius,
                        OnboardingUi.themed(NCMScreen.ColorType.ACCENT, .17f));
            } else if (hover) {
                OnboardingUi.roundedRect(rail.x - 6, rail.top(i) + 2, rail.width + 12,
                        rail.rowHeight - 4, radius, OnboardingUi.hairline());
            }

            // 图标底盘：以前这里只有一个小圆点，六行读下来只知道「有六步」，不知道每一步是什么。
            // 底盘与图标走 lucide（和播放器侧栏、Folia 设置弹窗同一批几何），矢量画不出来时
            // 退回 Material 字体字形，最差也只是少一枚图标。
            double box = rail.iconBox(title);
            double textX = rail.x + 12;
            if (box > 0) {
                double centerY = rail.centerY(i);
                OnboardingUi.roundedRect(rail.x, centerY - box * .5, box, box, box * .32,
                        current ? OnboardingUi.themed(NCMScreen.ColorType.ACCENT, .26f)
                                : OnboardingUi.themed(NCMScreen.ColorType.ELEMENT_BACKGROUND,
                                        visited ? .82f : .5f));
                if (current) {
                    OnboardingUi.roundedOutline(rail.x, centerY - box * .5, box, box, box * .32, 1.2,
                            OnboardingUi.awtColorWithAlpha(OnboardingUi.accent(), 210));
                }
                OnboardingUi.drawStepIcon(step, rail.x + box * .5, centerY, box * .62f,
                        current ? OnboardingUi.accent()
                                : OnboardingUi.themed(NCMScreen.ColorType.SECONDARY_TEXT,
                                        visited ? .8f : .5f));
                if (visited) {
                    // 走过的那一步在底盘右下角压一枚小勾：进度这件事要能一眼扫出来。
                    double badge = Math.max(8, box * .46);
                    OnboardingUi.roundedRect(rail.x + box - badge * .78, centerY + box * .5 - badge * .72,
                            badge, badge, badge * .5, OnboardingUi.GREEN);
                    OnboardingUi.drawCheckBadge(rail.x + box - badge * .28, centerY + box * .5 - badge * .22,
                            badge * .74, 0xFF0B0C11);
                }
                textX = rail.x + box + 9;
            }

            if (rail.fits(title)) {
                OnboardingUi.drawText(current ? FontManager.pf12bold : FontManager.pf12,
                        title, textX, rail.centerY(i) - 5,
                        current ? OnboardingUi.textPrimary()
                                : OnboardingUi.themed(NCMScreen.ColorType.SECONDARY_TEXT,
                                        visited ? .82f : .55f));
            }
        }
    }

    /**
     * 底栏里的导航：上一步 / 跳过 / 下一步。
     *
     * <p>这条带子就是播放器的 {@code ControlsBar} 那一条（同一条带、同一个高度），引导的按钮住在里面。
     * 填色与文字照播放器的写法来：主按钮 ACCENT 底 + PRIMARY_TEXT 字（{@code ControlsBar} 里那颗
     * {@code fmSkip} 就是这个配对），所以玩家在这一页把主题切成「瓷白墨」，按钮跟着翻，
     * 不会出现「浅底白字」。</p>
     */
    private void drawNavigation(NavLayout nav, double mouseX, double mouseY) {
        double buttonY = nav.buttonY;
        double buttonH = nav.buttonH;
        double radius = buttonH * .5;

        boolean hover = OnboardingUi.inside(mouseX, mouseY, nav.primaryX, buttonY, nav.primaryW, buttonH);
        OnboardingUi.roundedRect(nav.primaryX, buttonY, nav.primaryW, buttonH, radius,
                hover ? OnboardingUi.accentHover() : OnboardingUi.accent());
        String label = lastStep() ? "开始使用" : (stepIndex == 0 ? "开始设置" : "下一步");
        if (nav.compact) {
            OnboardingUi.drawPartyIcon(PartyIcons.CHEVRON_RIGHT,
                    nav.primaryX + nav.primaryW * .5, buttonY + buttonH * .5,
                    Math.min(18, buttonH - 6), OnboardingUi.textPrimary());
        } else {
            OnboardingUi.drawText(FontManager.pf12bold, label,
                    nav.primaryX + 16, buttonY + buttonH * .5 - 5, OnboardingUi.textPrimary());
            OnboardingUi.drawPartyIcon(PartyIcons.CHEVRON_RIGHT,
                    nav.primaryX + nav.primaryW - 14, buttonY + buttonH * .5,
                    Math.min(16, buttonH - 6), OnboardingUi.textPrimary());
        }

        if (stepIndex > 0 && nav.showBack) {
            boolean backHover = OnboardingUi.inside(mouseX, mouseY, nav.backX, buttonY, nav.backW, buttonH);
            int backColor = backHover ? OnboardingUi.textPrimary() : OnboardingUi.textSecondary();
            if (backHover) {
                OnboardingUi.roundedRect(nav.backX, buttonY, nav.backW, buttonH, radius,
                        OnboardingUi.surfaceHover());
            }
            OnboardingUi.drawPartyIcon(PartyIcons.CHEVRON_LEFT,
                    nav.backX + (nav.compact ? nav.backW * .5 : 14), buttonY + buttonH * .5,
                    Math.min(nav.compact ? 18 : 16, buttonH - 6), backColor);
            if (!nav.compact) {
                OnboardingUi.drawText(FontManager.pf12, "上一步",
                        nav.backX + 26, buttonY + buttonH * .5 - 5, backColor);
            }
        }
        if (!lastStep() && nav.showSkip) {
            boolean skipHover = OnboardingUi.inside(mouseX, mouseY, nav.skipX, buttonY, nav.skipW, buttonH);
            int skipColor = skipConfirm ? OnboardingUi.accent()
                    : (skipHover ? OnboardingUi.textPrimary() : OnboardingUi.textMuted());
            if (skipHover || skipConfirm) {
                OnboardingUi.roundedRect(nav.skipX, buttonY, nav.skipW, buttonH, radius,
                        OnboardingUi.surfaceHover());
            }
            OnboardingUi.drawPartyIcon(PartyIcons.CLOSE,
                    nav.skipX + (nav.compact ? nav.skipW * .5 : 12), buttonY + buttonH * .5,
                    Math.min(nav.compact ? 16 : 14, buttonH - 8), skipColor);
            if (!nav.skipCompact) {
                OnboardingUi.drawText(FontManager.pf12,
                        skipConfirm ? "再次点击确认跳过" : "跳过设置",
                        nav.skipX + 24, buttonY + buttonH * .5 - 5, skipColor);
            }
        }
    }

    private boolean lastStep() {
        return stepIndex == OnboardingStep.values().length - 1;
    }

    /** 侧栏里那列步骤的位置。 */
    private static final class Rail {
        final double x;
        final double y;
        final double width;
        final double rowHeight;
        /**
         * 六行塞不进侧栏了（面板只有几十逻辑高）。
         *
         * <p>这时候不画列表，改成在侧栏顶上写一行「第 3 / 6 步」：宁可少一个跳转入口，
         * 也不要让六行垂到底栏上——那一截会压在播放条里，看着像界面坏了。</p>
         */
        final boolean collapsed;

        private Rail(double x, double y, double width, double rowHeight, boolean collapsed) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.rowHeight = rowHeight;
            this.collapsed = collapsed;
        }

        static Rail of(OnboardingChrome.Panel chrome) {
            double gutter = clamp(chrome.sidebarWidth * .09, 7, 16);
            double top = chrome.y + clamp(chrome.height * .055, 10, 26);
            // 六行要连同上下留白一起塞进侧栏，所以行高跟着面板走，而不是写死一个「好看」的 34。
            double rowHeight = clamp((chrome.contentHeight() - (top - chrome.y) * 2) / 7.2, 18, 34);
            int steps = OnboardingStep.values().length;
            boolean collapsed = top + steps * rowHeight > chrome.controlsY() - 4;
            return new Rail(chrome.x + gutter + 6, top,
                    chrome.sidebarWidth - gutter * 2 - 12, rowHeight, collapsed);
        }

        double top(int index) {
            return y + index * rowHeight;
        }

        double centerY(int index) {
            return top(index) + rowHeight * .5;
        }

        /**
         * 这一行的标题放不放得下。
         *
         * <p>量的是<b>这一条标题自己</b>的宽度，不是「侧栏超过某个像素就写字」：
         * 854×480 开 GUI Scale 2（427×240 逻辑）是玩家真在用的档位，那时侧栏只有 92 宽、
         * 可用 63 宽——按一个固定阈值判就会整列都不写字，而「欢迎使用」四个字其实只要 30 不到。
         * 播放器侧栏也是逐条量的。</p>
         */
        boolean fits(String title) {
            return FontManager.pf12 != null
                    && FontManager.pf12.getStringWidthD(title) + 14 <= width;
        }

        /**
         * 这一行放得下多大的图标底盘；<b>0 表示这一档不画图标</b>。
         *
         * <p>三条退让按顺序来：① 行高矮到 22 以下（底盘里那枚字形会糊成一团）就不画；
         * ② 标题放得下、但「图标 + 标题」放不下，<b>收图标留标题</b>——侧栏存在的意义是
         * 「点回去」，图标只是让它好认；③ 标题本来就放不下（那时只剩图标这一种表达），
         * 那连图标也放不下才收。</p>
         */
        double iconBox(String title) {
            return railIconBoxFor(rowHeight, width,
                    OnboardingUi.textWidth(FontManager.pf12, title), fits(title));
        }

        boolean hit(double mouseX, double mouseY, int index) {
            return !collapsed
                    && OnboardingUi.inside(mouseX, mouseY, x - 10, top(index),
                    Math.max(24, width + 16), rowHeight);
        }
    }

    /**
     * 底栏里三枚按钮的位置。
     *
     * <p>纵向只由骨架的底栏决定（按钮在条里垂直居中、高度跟着条走），横向从右边往里排。
     * 上一轮那三处各写各的字面量——按钮 {@code 底-72}、进度轨 {@code 底-50}、内容
     * {@code height-180}——所以任何一档窗口都可能悄悄叠上；现在一条带子只有一个来源。</p>
     */
    private static final class NavLayout {
        final boolean compact;
        final boolean skipCompact;

        /** 这一档放得下「上一步」吗：放不下就整条不画，而不是叠到跳过上面。 */
        final boolean showBack;
        /** 同上，跳过。ESC 仍然跳过整段引导，所以藏掉它不算丢功能。 */
        final boolean showSkip;
        final double primaryW;
        final double primaryX;
        final double backW;
        final double backX;
        final double skipW;
        final double skipX;
        final double buttonY;
        final double buttonH;

        private NavLayout(boolean compact, boolean skipCompact, boolean showBack, boolean showSkip,
                          double primaryW, double primaryX,
                          double backW, double backX, double skipW, double skipX,
                          double buttonY, double buttonH) {
            this.compact = compact;
            this.skipCompact = skipCompact;
            this.showBack = showBack;
            this.showSkip = showSkip;
            this.primaryW = primaryW;
            this.primaryX = primaryX;
            this.backW = backW;
            this.backX = backX;
            this.skipW = skipW;
            this.skipX = skipX;
            this.buttonY = buttonY;
            this.buttonH = buttonH;
        }

        static NavLayout of(OnboardingChrome.Panel chrome, boolean confirmingSkip) {
            double barX = chrome.controlsX();
            double barWidth = chrome.controlsWidth();
            double barHeight = chrome.controlsHeight;
            double buttonH = clamp(barHeight - 10, 18, 30);
            double buttonY = chrome.controlsY() + (barHeight - buttonH) * .5;
            double pad = clamp(barWidth * .035, 6, 22);
            double gap = 8;
            double room = barWidth - pad * 2;
            // 「跳过设置」确认时会变成「再次点击确认跳过」，宽度跟着文案走。
            double skipLabelW = confirmingSkip ? 148 : 112;
            double primaryLabelW = 132;
            double backLabelW = 92;

            /*
             * 从「三条都带字」往下退，每退一级先牺牲谁是有讲究的：跳过是「要不要整段都不要」，
             * 字面意思最不该被一个 × 代替；上一步只有一个方向，一个左箭头谁都读得懂；
             * 主按钮是这一页唯一的动作，它最后才退成箭头。
             *
             * 退到三条都塞不下时，宁可整条不画跳过（ESC 仍然是跳过引导，功能没丢），
             * 也不能让两枚按钮叠在一起——叠了之后谁都不知道按下去会发生什么。
             * 176x89 那一档（284x160 的窗口再把播放器缩到 70%）就是这么算出来的。
             */
            boolean skipCompact = room < primaryLabelW + backLabelW + skipLabelW + gap * 2;
            boolean compact = room < primaryLabelW + backLabelW + buttonH + gap * 2;
            boolean backCompact = compact || room < primaryLabelW + backLabelW + gap * 2;
            double primaryW = compact ? Math.max(buttonH, 40) : primaryLabelW;
            double backW = backCompact ? buttonH : backLabelW;
            double skipW = skipCompact ? buttonH : skipLabelW;
            boolean showSkip = room >= primaryW + backW + skipW + gap * 2;
            boolean showBack = room >= primaryW + skipW + gap * 2
                    + (showSkip ? backW + gap : 0);
            if (!showBack && !showSkip) {
                // 连「一枚图标 + 主按钮」都放不下：这一档只留主按钮，导航靠侧栏与 ESC。
                primaryW = Math.min(primaryW, Math.max(buttonH, room));
            }
            double primaryX = barX + barWidth - pad - primaryW;
            double skipX = Math.max(barX + pad + (showBack ? backW + gap : 0),
                    primaryX - gap - skipW);
            double backX = barX + pad;
            return new NavLayout(compact, skipCompact, showBack, showSkip,
                    primaryW, primaryX, backW, backX, skipW, skipX, buttonY, buttonH);        }
    }

    /**
     * 内容带：步骤正文的矩形。
     *
     * <p>绘制与 {@code mouseClicked} 必须拿到同一个带子，否则小窗口里会出现
     * 「看得见却点不到」或反过来「点空白处触发了看不见的控件」。</p>
     */
    private static final class Bands {
        final double x;
        final double y;
        final double width;
        final double height;

        private Bands(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        static Bands of(OnboardingChrome.Panel chrome) {
            // 正文住在内容区里：侧栏右边、底栏上边。四周再留一点，别让文字贴着圆角。
            // 留白的下限按最窄那一档（256×144 的窗口再把播放器缩到 70%，内容区只剩 110 宽）定：
            // 两头各 12 会吃掉两成宽度，卡片就排不开了。
            double padX = clamp(chrome.contentWidth() * .05, 6, 34);
            double padTop = clamp(chrome.contentHeight() * .07, 8, 34);
            return new Bands(chrome.contentX() + padX, chrome.contentY() + padTop,
                    Math.max(24, chrome.contentWidth() - padX * 2),
                    Math.max(24, chrome.contentHeight() - padTop * 2));
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) return;
        RenderSystem.refreshResolution();
        RenderSystem.FIXED_SCALE = false;
        double width = RenderSystem.getWidth();
        double height = RenderSystem.getHeight();
        double sx = RenderSystem.getWidthNotScaled() / width;
        double sy = RenderSystem.getHeightNotScaled() / height;
        double x = mouseX / sx;
        double y = mouseY / sy;

        if (accountOverlay != null) {
            accountOverlay.onMouseClickReceived(x, y, mouseButton);
            return;
        }

        OnboardingChrome.Panel chrome = OnboardingChrome.of(width, height);

        if (overlay != null && !overlay.gone()) {
            // 覆盖层挂着的时候，这一记点击的含义是「我看完了，让开」，
            // 不是「点下面那个控件」。不吞掉的话，玩家随手一点就改到了底下的设置，
            // 而那时他连界面长什么样都还没看清。
            // 只发起收层，不就地拆层：320ms 的淡出是这段交接唯一的动效，
            // 当场 retireOverlay() 会让它变成硬切，dismiss() 也就白写了。
            overlay.dismiss();
            return;
        }

        NavLayout nav = NavLayout.of(chrome, skipConfirm);
        double buttonY = nav.buttonY;

        if (OnboardingUi.inside(x, y, nav.primaryX, buttonY, nav.primaryW, nav.buttonH)) {
            if (lastStep()) {
                finish();
            } else {
                nextPage();
            }
            return;
        }
        if (stepIndex > 0 && nav.showBack
                && OnboardingUi.inside(x, y, nav.backX, buttonY, nav.backW, nav.buttonH)) {
            previousPage();
            return;
        }
        if (!lastStep() && nav.showSkip
                && OnboardingUi.inside(x, y, nav.skipX, buttonY, nav.skipW, nav.buttonH)) {
            if (skipConfirm) {
                skip();
            } else {
                setSkipConfirm(true);
            }
            return;
        }

        // 侧栏那一列：引导里最常发生的事是「刚才那个音源选错了」，
        // 让玩家像用播放器一样点回去，而不是把「上一步」按四遍。
        Rail rail = Rail.of(chrome);
        for (int i = 0; i < OnboardingStep.values().length; i++) {
            if (rail.hit(x, y, i)) {
                goToStep(i);
                return;
            }
        }

        if (isStepTransitionActive()) {
            return;
        }

        // 命中测试用的是绘制那一遍算出来的同一个内容带：两边各写一套字面量的时候，
        // 小窗口里会点不到看得见的内容，或点到已经裁掉的控件。
        Bands bands = Bands.of(chrome);
        if (!OnboardingUi.inside(x, y, bands.x, bands.y, bands.width, bands.height)) {
            // 正文按可读性下限缩放后被 {@code clipStep} 裁进这条带子里，带外那一截玩家看不见。
            // 看不见却还能点（并且真的改了设置）比裁切本身糟得多，所以带外一律不当成内容点击。
            return;
        }
        double scale = stepScale(bands.width, bands.height);
        double localMouseX = (x - bands.x) / scale;
        double localMouseY = (y - bands.y) / scale;
        steps[stepIndex].mouseClicked(this, 0.0, 0.0,
                bands.width / scale, bands.height / scale,
                localMouseX, localMouseY, mouseButton);
    }

    /** 覆盖层收干净：落一次状态，此后这页就是纯粹的引导。 */
    private void retireOverlay() {
        if (overlay == null) {
            return;
        }
        overlay.close();
        overlay = null;
        OnboardingState.markIntroSeen();
    }

    /** 跳到指定那一页，转场方向按前后分别走。 */
    private void goToStep(int target) {
        if (target == stepIndex || target < 0 || target >= OnboardingStep.values().length) {
            return;
        }
        beginStepTransition(target > stepIndex ? 1 : -1);
        stepIndex = target;
        pageStartedAt = System.nanoTime();
        skipConfirm = false;
    }

    @Override
    public void updateScreen() {
        if (overlay == null) {
            return;
        }
        // 翻页不能在绘制中途发生，所以「描完了没有」这件事问在 tick 里。
        if (overlay.tick()) {
            retireOverlay();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (accountOverlay != null) {
            if (accountOverlay.onKeyTypedReceived(typedChar, keyCode)) return;
            if (keyCode == Keyboard.KEY_ESCAPE) accountOverlay.handleEscape();
            return;
        }
        if (overlay != null && !overlay.gone()) {
            // 覆盖层期间只认三个键：ESC 是「连引导一起不要」，回车/空格是「让开我看引导」，
            // 其余一概不进引导——不然玩家在描字还没走完时就按到了下面页里的输入框。
            // ESC 也在 skip 门禁里面（不然闸一按就穿，等于没加），没到点的时候这四条输入
            // 全都只是「被吃掉」：界面不动，但也不许穿到引导正文上去改设置。
            if (keyCode == Keyboard.KEY_ESCAPE) {
                if (overlay.gateOpen()) {
                    OnboardingManager.skipAll();
                }
            } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_SPACE) {
                overlay.dismiss();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (stepIndex == 0) {
                if (skipConfirm) skip();
                else setSkipConfirm(true);
            } else {
                previousPage();
            }
        }
    }

    @Override
    public void onGuiClosed() {
        if (accountOverlay != null) {
            accountOverlay.dispose();
            accountOverlay = null;
        }
        if (overlay != null) {
            overlay.close();
            overlay = null;
        }
        backdrop.close();
        for (OnboardingStepView step : steps) {
            step.close();
        }
    }

    @Override
    public OnboardingStep step() {
        return OnboardingStep.values()[stepIndex];
    }

    @Override
    public int stepIndex() {
        return stepIndex;
    }

    @Override
    public long openedAt() {
        return openedAt;
    }

    @Override
    public long pageStartedAt() {
        return pageStartedAt;
    }

    @Override
    public boolean isSkipConfirm() {
        return skipConfirm;
    }

    @Override
    public void setSkipConfirm(boolean value) {
        skipConfirm = value;
    }

    @Override
    public void nextPage() {
        beginStepTransition(1);
        stepIndex = Math.min(OnboardingStep.values().length - 1, stepIndex + 1);
        pageStartedAt = System.nanoTime();
        skipConfirm = false;
    }

    @Override
    public void previousPage() {
        beginStepTransition(-1);
        stepIndex = Math.max(0, stepIndex - 1);
        pageStartedAt = System.nanoTime();
        skipConfirm = false;
    }

    private void beginStepTransition(int direction) {
        previousStepIndex = stepIndex;
        transitionDirection = direction;
        transitionStartedAt = System.nanoTime();
    }

    private double stepTransitionProgress() {
        if (transitionStartedAt < 0L || previousStepIndex < 0) return 1.0;
        long age = System.nanoTime() - transitionStartedAt;
        if (age >= STEP_TRANSITION_NANOS) {
            previousStepIndex = -1;
            transitionStartedAt = -1L;
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, age / (double) STEP_TRANSITION_NANOS));
    }

    private boolean isStepTransitionActive() {
        return stepTransitionProgress() < 1.0;
    }

    private static double easeOutCubic(double value) {
        double inverted = 1.0 - value;
        return 1.0 - inverted * inverted * inverted;
    }

    private static double stepScale(double contentWidth, double contentHeight) {
        double available = Math.min(contentWidth / STEP_DESIGN_WIDTH,
                contentHeight / STEP_DESIGN_HEIGHT);
        // 低于此阈值文字变得不可读。保持字体可读，让
        //固定内容视口裁剪仅在异常小的 Minecraft 窗口上生效。
        return Math.min(1.0, Math.max(MIN_READABLE_SCALE, available));
    }

    @Override
    public void skip() {
        saveConfiguration();
        OnboardingManager.skip(this);
    }

    @Override
    public void finish() {
        saveConfiguration();
        OnboardingManager.finish(this);
    }

    @Override
    public void openAccountManager() {
        if (accountOverlay != null) accountOverlay.dispose();
        accountOverlay = new AccountManagerOverlay();
        accountOverlay.onInit();
    }

    @Override
    public MusicPlatform selectedPlatform() {
        return selectedPlatform;
    }

    @Override
    public void selectPlatform(MusicPlatform platform) {
        selectedPlatform = platform == null ? MusicPlatform.NETEASE : platform;
        if (selectedPlatform == MusicPlatform.GD && !GdStudioSourceSettings.isEnabled()) {
            List<GdStudioMusicService.Platform> options = GdStudioMusicService.getPlatforms();
            if (!options.isEmpty()) {
                GdStudioSourceSettings.setPlatform(options.get(0).key);
            }
        }
        NeteaseMusicService.setCurrentPlatform(selectedPlatform);
    }

    @Override
    public NCMTheme.ThemePreset selectedTheme() {
        return selectedTheme;
    }

    @Override
    public void selectTheme(NCMTheme.ThemePreset theme) {
        selectedTheme = theme == null ? NCMTheme.getCurrent() : theme;
        NCMTheme.setCurrent(selectedTheme);
    }

    @Override
    public UiFont selectedFont() {
        return fonts.isEmpty() ? UiFont.BUILTIN : fonts.get(selectedFontIndex);
    }

    @Override
    public void selectFont(UiFont font) {
        if (font == null || font.isBuiltin()) {
            selectedFontIndex = 0;
            UiFontLibrary.restoreBuiltin();
            return;
        }
        for (int i = 0; i < fonts.size(); i++) {
            if (font.equals(fonts.get(i))) {
                selectedFontIndex = i;
                UiFontLibrary.select(font);
                return;
            }
        }
    }

    @Override
    public void cycleFont() {
        if (fonts.isEmpty()) {
            UiFontLibrary.restoreBuiltin();
            return;
        }
        selectedFontIndex = (selectedFontIndex + 1) % fonts.size();
        UiFontLibrary.select(fonts.get(selectedFontIndex));
    }

    @Override
    public float hudValue(HudSetting setting) {
        return setting.getValue();
    }

    @Override
    public void adjustHudSetting(HudSetting setting, float delta) {
        setting.setValue(setting.getValue() + delta);
        HudConfig.save();
    }

    private void saveConfiguration() {
        NeteaseMusicService.setCurrentPlatform(selectedPlatform);
        NCMTheme.setCurrent(selectedTheme);
        if (fonts.isEmpty()) {
            UiFontLibrary.restoreBuiltin();
        } else {
            UiFontLibrary.select(fonts.get(selectedFontIndex));
        }
        HudConfig.save();
    }

    // ------------------------------------------------ 骨架之内的排布
    //
    // 面板外形（在哪、多大、圆角、侧栏宽、底栏高）由 OnboardingChrome 从播放器抄来；
    // 下面这几个只管「骨架之内怎么排」。老规矩：绘制、命中测试、内容高度三处必须读同一份——
    // 上一轮就是三处各写各的字面量（按钮「底-72」、进度轨「底-50」、内容「height-180」），
    // 所以「按钮压住进度条」这种错能一直留在屏幕上没人发现。

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    /**
     * 纯函数：侧栏那一行该放多大的图标底盘，0 = 这一档不画图标。
     *
     * <p>三条退让按顺序来：① 底盘小于 12 逻辑像素（里面的字形会糊成一团）就不画；
     * ② 标题放得下、但「图标 + 间距 9 + 标题」放不下，<b>收图标留标题</b>——侧栏存在的意义是
     * 「点回去」，图标只是让它好认；③ 标题本来就放不下（那时只剩图标一种表达），
     * 连图标也放不下才收。</p>
     *
     * <p>写成静态纯函数是为了在游戏外面钉：单元测试的 JVM 里没有字体，
     * {@code Rail#iconBox} 那条量文字的路走不通，只能把规则本身拿出来量。</p>
     */
    static double railIconBoxFor(double rowHeight, double railWidth,
                                 double titleTextWidth, boolean titleFits) {
        double box = clamp(rowHeight - 10, 0, 22);
        if (box < 12) {
            return 0;
        }
        if (titleFits && box + 9 + titleTextWidth > railWidth) {
            return 0;
        }
        if (!titleFits && box > railWidth) {
            return 0;
        }
        return box;
    }

    /**
     * 把内容裁进内容框里。
     *
     * <p>{@link #stepScale} 有可读性下限，所以极小窗口里内容仍然可能比框高——那时
     * <b>宁可裁掉看不见的下半部分，也不能让它盖到底栏和侧栏上</b>：叠字是「界面坏了」，
     * 裁切只是「窗口太小」。</p>
     */
    private static void clipStep(double x, double y, double width, double height) {
        SkijaClip.beginClip(() -> SkijaShapes.roundedRect(x, y, width, height, 0, 0xFFFFFFFF));
    }

    /**
     * 只给测试：某个逻辑窗口尺寸下，骨架与里面三条带各在哪儿。
     *
     * <p>这套几何是纯算术，不碰 Skija 也不碰 GL，所以能在<b>游戏外</b>把「任何一个 GUI Scale
     * 档位上都不叠」钉成测试。真机一屏只看得见一档（854×480 / Scale 3），另外几十档——
     * 尤其笔记本 1366×768 上 Scale 2 那种——以前只能靠人碰巧用到才发现叠字。</p>
     *
     * <p>下标的名字见 {@code OnboardingLayoutStaticTest} 那组同名常数。</p>
     */
    static double[] layoutForTest(double width, double height, double playerScale,
                                  boolean confirmingSkip) {
        OnboardingChrome.Panel chrome = OnboardingChrome.of(width, height, playerScale);
        Bands bands = Bands.of(chrome);
        NavLayout nav = NavLayout.of(chrome, confirmingSkip);
        Rail rail = Rail.of(chrome);
        return new double[]{bands.x, bands.y, bands.width, bands.height,
                nav.buttonY, nav.buttonH, nav.primaryX, nav.primaryW,
                nav.backX, nav.backW, nav.skipX, nav.skipW,
                nav.compact ? 1 : 0, nav.skipCompact ? 1 : 0,
                chrome.x, chrome.y, chrome.width, chrome.height,
                chrome.controlsY(), chrome.controlsHeight,
                chrome.contentX(), chrome.contentY(), chrome.contentWidth(), chrome.contentHeight(),
                rail.x, rail.y, rail.width, rail.rowHeight,
                rail.fits(OnboardingStep.values()[0].getTitle()) ? 1 : 0,
                nav.showBack ? 1 : 0, nav.showSkip ? 1 : 0, rail.collapsed ? 1 : 0};
    }
}