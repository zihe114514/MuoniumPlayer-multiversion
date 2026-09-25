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
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.AccountManagerOverlay;
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
import com.muoniumplayer.render.skija.SkijaGfx;
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
    private static final double MIN_READABLE_SCALE = 0.82;

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
            drawBackground(width, height);

            boolean welcome = step() == OnboardingStep.WELCOME;
            double left = welcome ? 0 : surfaceLeft(width);
            double top = welcome ? 0 : surfaceTop(height);
            double surfaceWidth = welcome ? width : surfaceWidth(width);
            double surfaceHeight = welcome ? height : surfaceHeight(height);
            if (!welcome) {
                drawSurface(left, top, surfaceWidth, surfaceHeight);
            }

            double contentX = welcome ? 0 : left + 52;
            double contentY = welcome ? 0 : top + 42;
            double contentWidth = welcome ? width : surfaceWidth - 104;
            double contentHeight = welcome ? Math.max(0.0, height - 180.0) : surfaceHeight - 178;
            renderStepWithTransition(contentX, contentY, contentWidth, contentHeight, mx, my);

            drawProgress(left + 58, top + surfaceHeight - 50, surfaceWidth - 116);
            drawNavigation(left, top, surfaceWidth, surfaceHeight, mx, my);

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

    private void drawBackground(double width, double height) {
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

    private void drawSurface(double left, double top, double width, double height) {
        OnboardingUi.roundedRect(left, top, width, height, 32, OnboardingUi.GLASS);
        OnboardingUi.roundedOutlineGradient(left, top, width, height, 32, 1.1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.WHITE, 26),
                OnboardingUi.awtColorWithAlpha(OnboardingUi.WHITE, 70),
                OnboardingUi.awtColorWithAlpha(OnboardingUi.BRAND_2, 58),
                OnboardingUi.awtColorWithAlpha(OnboardingUi.WHITE, 18));
        OnboardingUi.roundedGradient(left + 34, top, Math.min(180, width * .24), 2, 1,
                OnboardingUi.BRAND, OnboardingUi.BRAND_2);
    }

    private void drawProgress(double x, double y, double width) {
        double segment = width / (OnboardingStep.values().length - 1);
        for (int i = 0; i < OnboardingStep.values().length - 1; i++) {
            Rect.draw(x + i * segment, y - 1, segment, 2,
                    i < stepIndex ? OnboardingUi.GREEN : trackColor());
        }
        for (int i = 0; i < OnboardingStep.values().length; i++) {
            double cx = x + i * segment;
            boolean current = i == stepIndex;
            int color = current ? OnboardingUi.BLUE
                    : (i < stepIndex ? OnboardingUi.GREEN : dotColor());
            OnboardingUi.roundedRect(cx - (current ? 6 : 4), y - (current ? 6 : 4),
                    current ? 12 : 8, current ? 12 : 8, 8, color);
        }
    }

    private void drawNavigation(double left, double top, double width, double height,
                                double mouseX, double mouseY) {
        NavLayout nav = NavLayout.of(left, top, width, height);
        double buttonY = nav.buttonY;
        boolean hover = OnboardingUi.inside(mouseX, mouseY, nav.primaryX, buttonY, nav.primaryW, 44);
        OnboardingUi.roundedGradient(nav.primaryX, buttonY, nav.primaryW, 44, 22,
                hover ? OnboardingUi.BRAND_2 : OnboardingUi.BRAND,
                hover ? com.muoniumplayer.core.rendering.RGBA.color(255, 181, 107, 255) : OnboardingUi.BRAND_2);
        OnboardingUi.roundedOutline(nav.primaryX, buttonY, nav.primaryW, 44, 22, 1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.CYAN, 190));
        String label = nav.compact
                ? (stepIndex == OnboardingStep.values().length - 1 ? "完成" : (stepIndex == 0 ? "开始" : "下一步"))
                : (stepIndex == OnboardingStep.values().length - 1 ? "开始使用" : (stepIndex == 0 ? "开始设置" : "下一步"));
        OnboardingUi.drawText(FontManager.pf14bold, label,
                nav.primaryX + (nav.compact ? 14 : 20), buttonY + 15, OnboardingUi.WHITE);
        OnboardingUi.drawPartyIcon(PartyIcons.CHEVRON_RIGHT,
                nav.primaryX + nav.primaryW - (nav.compact ? 18 : 22), buttonY + 22,
                nav.compact ? 18 : 22, OnboardingUi.WHITE);

        if (stepIndex > 0) {
            boolean backHover = OnboardingUi.inside(mouseX, mouseY, nav.backX, buttonY, nav.backW, 40);
            int backColor = backHover ? OnboardingUi.WHITE : OnboardingUi.DIM;
            OnboardingUi.roundedRect(nav.backX, buttonY, nav.backW, 40, 20,
                    backHover ? OnboardingUi.GLASS_2 : OnboardingUi.GLASS);
            OnboardingUi.drawPartyIcon(PartyIcons.CHEVRON_LEFT,
                    nav.backX + nav.backW * .5, buttonY + 20,
                    nav.compact ? 18 : 22, backColor);
            if (!nav.compact) {
                OnboardingUi.drawText(FontManager.pf12bold, "上一步",
                        nav.backX + 34, buttonY + 16, backColor);
            }
        }
        if (stepIndex < OnboardingStep.values().length - 1) {
            boolean skipHover = OnboardingUi.inside(mouseX, mouseY, nav.skipX, buttonY, nav.skipW, 40);
            int skipColor = skipConfirm
                    ? OnboardingUi.VIOLET : (skipHover ? OnboardingUi.SOFT : OnboardingUi.DIM);
            OnboardingUi.roundedRect(nav.skipX, buttonY, nav.skipW, 40, 20,
                    skipHover || skipConfirm ? OnboardingUi.GLASS_2 : OnboardingUi.GLASS);
            OnboardingUi.drawPartyIcon(PartyIcons.CLOSE,
                    nav.skipX + (nav.compact ? nav.skipW * .5 : 12), buttonY + 20,
                    nav.compact ? 18 : 20, skipColor);
            if (!nav.compact) {
                OnboardingUi.drawText(FontManager.pf12, skipConfirm ? "再次点击确认跳过" : "跳过设置",
                        nav.skipX + 24, buttonY + 17, skipColor);
            }
        }
    }

    private static final class NavLayout {
        final boolean compact;
        final double primaryW;
        final double primaryX;
        final double backW;
        final double backX;
        final double skipW;
        final double skipX;
        final double buttonY;

        private NavLayout(boolean compact, double primaryW, double primaryX,
                          double backW, double backX, double skipW, double skipX,
                          double buttonY) {
            this.compact = compact;
            this.primaryW = primaryW;
            this.primaryX = primaryX;
            this.backW = backW;
            this.backX = backX;
            this.skipW = skipW;
            this.skipX = skipX;
            this.buttonY = buttonY;
        }

        static NavLayout of(double left, double top, double width, double height) {
            boolean compact = width < 520.0;
            double buttonY = top + height - 72.0;
            if (compact) {
                double primaryW = 112.0;
                double primaryX = left + width - primaryW - 24.0;
                double backW = 40.0;
                double backX = left + 24.0;
                double skipW = 40.0;
                double skipX = backX + backW + 10.0;
                return new NavLayout(true, primaryW, primaryX, backW, backX, skipW, skipX, buttonY);
            }
            double primaryW = 144.0;
            double primaryX = left + width - primaryW - 38.0;
            double backW = 100.0;
            double backX = left + 28.0;
            double skipW = 120.0;
            double skipX = primaryX - (skipConfirmWidth()) - 8.0;
            return new NavLayout(false, primaryW, primaryX, backW, backX, skipW, skipX, buttonY);
        }

        private static double skipConfirmWidth() {
            return 148.0;
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

        boolean welcome = step() == OnboardingStep.WELCOME;
        double left = welcome ? 0 : surfaceLeft(width);
        double top = welcome ? 0 : surfaceTop(height);
        double surfaceWidth = welcome ? width : surfaceWidth(width);
        double surfaceHeight = welcome ? height : surfaceHeight(height);
        NavLayout nav = NavLayout.of(left, top, surfaceWidth, surfaceHeight);
        double buttonY = nav.buttonY;

        if (OnboardingUi.inside(x, y, nav.primaryX, buttonY, nav.primaryW, 44)) {
            if (stepIndex == OnboardingStep.values().length - 1) {
                finish();
            } else {
                nextPage();
            }
            return;
        }
        if (stepIndex > 0 && OnboardingUi.inside(x, y, nav.backX, buttonY, nav.backW, 40)) {
            previousPage();
            return;
        }
        if (stepIndex < OnboardingStep.values().length - 1
                && OnboardingUi.inside(x, y, nav.skipX, buttonY, nav.skipW, 40)) {
            if (skipConfirm) {
                skip();
            } else {
                setSkipConfirm(true);
            }
            return;
        }

        if (welcome || isStepTransitionActive()) {
            return;
        }

        double contentX = left + 52;
        double contentY = top + 42;
        double contentWidth = surfaceWidth - 104;
        double contentHeight = surfaceHeight - 178;
        double scale = stepScale(contentWidth, contentHeight);
        double localMouseX = (x - contentX) / scale;
        double localMouseY = (y - contentY) / scale;
        steps[stepIndex].mouseClicked(this, 0.0, 0.0,
                contentWidth / scale, contentHeight / scale,
                localMouseX, localMouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (accountOverlay != null) {
            if (accountOverlay.onKeyTypedReceived(typedChar, keyCode)) return;
            if (keyCode == Keyboard.KEY_ESCAPE) accountOverlay.handleEscape();
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

    private static int trackColor() {
        return com.muoniumplayer.core.rendering.RGBA.color(42, 61, 98, 230);
    }

    private static int dotColor() {
        return com.muoniumplayer.core.rendering.RGBA.color(64, 87, 130, 255);
    }

    private static double surfaceLeft(double width) {
        return (width - surfaceWidth(width)) * .5;
    }

    private static double surfaceTop(double height) {
        return (height - surfaceHeight(height)) * .5;
    }

    private static double surfaceWidth(double width) {
        return Math.max(Math.min(width - 28, 1120), Math.min(width, 700));
    }

    private static double surfaceHeight(double height) {
        return Math.max(Math.min(height - 24, 700), Math.min(height, 540));
    }
}
