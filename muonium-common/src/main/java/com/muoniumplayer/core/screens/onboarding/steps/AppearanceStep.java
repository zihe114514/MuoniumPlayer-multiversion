package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.party.ui.PartyIcons;

/** 仅由既有主题、字体和动画设置构建的外观页。 */
public final class AppearanceStep implements OnboardingStepView {

    /** 一张主题卡：上面那块迷你播放器预览 + 下面名称与状态，六页里唯一会「变样」的一张卡。 */
    private static final double CARD_H = 126;
    /** 预览块在卡里的内缩与高度。 */
    private static final double PREVIEW_INSET = 10;
    private static final double PREVIEW_H = 58;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawHeader("APPEARANCE", "找到你的风格",
                "主题、字体和动画会实时预览，并使用现有样式配置。", x, y);

        NCMTheme.ThemePreset[] themes = NCMTheme.ThemePreset.values();
        int start = controller.selectedTheme().ordinal() - 1;
        double cardY = cardY(y);
        for (int i = 0; i < 3; i++) {
            NCMTheme.ThemePreset theme = themes[Math.floorMod(start + i, themes.length)];
            double cardX = cardX(x, width, i);
            boolean selected = theme == controller.selectedTheme();
            boolean hover = OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth(width), CARD_H);
            drawCard(controller, theme, cardX, cardY, cardWidth(width), selected, hover);
        }

        double fontY = cardY + CARD_H + 22;
        drawSectionTitle(FoliaLucideIcons.TYPOGRAPHY, PartyIcons.TEXT_FORMAT, "字体", x, fontY);
        UiFont font = controller.selectedFont();
        String name = font == null ? "内置字体" : font.getDisplayName();
        OnboardingUi.drawText(FontManager.pf12, name + " · 点击切换",
                x + 22 + OnboardingUi.textWidth(FontManager.pf14bold, "字体") + 12, fontY + 2,
                OnboardingUi.textMuted());
        double fontBoxY = fontY + 24;
        OnboardingUi.roundedRect(x, fontBoxY, width, 46, OnboardingUi.CARD_RADIUS - 1,
                OnboardingUi.surfaceHover());
        OnboardingUi.roundedOutline(x, fontBoxY, width, 46, OnboardingUi.CARD_RADIUS - 1, 1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.hairline(), 170));
        OnboardingUi.drawText(FontManager.pf18, UiFontLibrary.sampleText(),
                x + 16, fontBoxY + 13, OnboardingUi.CYAN);

        double animationY = fontBoxY + 46 + 26;
        drawSectionTitle(FoliaLucideIcons.SPEED, PartyIcons.SYNC, "界面动画", x, animationY);
        drawStepper(controller, HudSetting.PLAYER_UI_SWITCH_SPEED,
                x, animationY + 24, width, mouseX, mouseY);
    }

    /**
     * 一张主题卡。
     *
     * <p>卡里那块<b>迷你播放器</b>才是这一页的重点：玩家挑的就是「换上去长什么样」，
     * 而两行字加一条色带说明不了任何事。所以按这套主题的<b>真实配色</b>画一个缩小到
     * 1:6 的面板——面板底、封面方块、两行文字、一条走到三成六的进度条，选中那一张走到六成二。</p>
     *
     * <p>这些颜色<b>必须自己叠 alpha</b>：{@code ThemePreset.getColor} 回的是裸 RGB
     * （{@code 0x00RRGGBB}），直接当 ARGB 用等于「全透明」，一笔都不会画。这一页原来就是这么写的，
     * 于是三张卡的预览块整块是隐形的，玩家看到的是三个空壳。</p>
     */
    private static void drawCard(OnboardingController controller, NCMTheme.ThemePreset theme,
                                 double x, double y, double width, boolean selected, boolean hover) {
        OnboardingUi.roundedRect(x, y, width, CARD_H, OnboardingUi.CARD_RADIUS + 1,
                selected ? of(theme, NCMScreen.ColorType.ACCENT, .16f)
                        : (hover ? OnboardingUi.surfaceHover() : OnboardingUi.surface()));
        if (selected) {
            OnboardingUi.roundedOutline(x, y, width, CARD_H, OnboardingUi.CARD_RADIUS + 1, 1.6,
                    OnboardingUi.awtColorWithAlpha(of(theme, NCMScreen.ColorType.ACCENT, 1.0f), 235));
        }
        drawMiniPlayer(theme, x + PREVIEW_INSET, y + PREVIEW_INSET,
                width - PREVIEW_INSET * 2, selected);

        double labelY = y + PREVIEW_INSET + PREVIEW_H + 10;
        OnboardingUi.drawIcon(FoliaLucideIcons.PALETTE, PartyIcons.TEXT_FORMAT,
                x + 19, labelY + 9, 14, of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
        OnboardingUi.drawText(FontManager.pf12bold, theme.getDisplayName(),
                x + 32, labelY + 2, OnboardingUi.textPrimary());
        OnboardingUi.drawText(FontManager.pf10bold, selected ? "SELECTED" : "PREVIEW",
                x + 12, labelY + 20, selected ? OnboardingUi.CYAN : OnboardingUi.textMuted());
    }

    /** 卡里那块迷你播放器：面板底 + 封面 + 两行字 + 进度条，全部用这套主题自己的颜色。 */
    private static void drawMiniPlayer(NCMTheme.ThemePreset theme, double x, double y,
                                       double width, boolean selected) {
        OnboardingUi.roundedRect(x, y, width, PREVIEW_H, 11,
                of(theme, NCMScreen.ColorType.GENERIC_BACKGROUND, 1.0f));
        OnboardingUi.roundedOutline(x, y, width, PREVIEW_H, 11, 1,
                OnboardingUi.awtColorWithAlpha(
                        of(theme, NCMScreen.ColorType.BORDER, 1.0f), 120));

        double cover = 22;
        double inset = 8;
        OnboardingUi.roundedRect(x + inset, y + inset, cover, cover, 6,
                of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
        // 封面里那一枚音符用主题自己的主文字色，读起来才是「这块面板上的控件」。
        OnboardingUi.drawIcon(FoliaLucideIcons.NOTE, PartyIcons.NOTE,
                x + inset + cover * .5, y + inset + cover * .5,
                cover * .58f, of(theme, NCMScreen.ColorType.PRIMARY_TEXT, 1.0f));

        double textX = x + inset + cover + 8;
        double textW = Math.max(12, x + width - inset - 8 - textX);
        OnboardingUi.roundedRect(textX, y + inset + 2, textW * .72, 4, 2,
                of(theme, NCMScreen.ColorType.PRIMARY_TEXT, .9f));
        OnboardingUi.roundedRect(textX, y + inset + 11, textW * .44, 3, 1.5,
                of(theme, NCMScreen.ColorType.SECONDARY_TEXT, .6f));

        double barY = y + PREVIEW_H - inset - 3;
        OnboardingUi.roundedRect(x + inset, barY, width - inset * 2, 3, 1.5,
                of(theme, NCMScreen.ColorType.PRIMARY_TEXT, .2f));
        OnboardingUi.roundedRect(x + inset, barY,
                (width - inset * 2) * (selected ? .62 : .36), 3, 1.5,
                of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
        double knob = 6;
        OnboardingUi.roundedRect(x + inset + (width - inset * 2) * (selected ? .62 : .36) - knob * .5,
                barY - (knob - 3) * .5, knob, knob, knob * .5,
                of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
    }

    private static void drawSectionTitle(String lucide, String fallback, String title,
                                         double x, double y) {
        OnboardingUi.drawIcon(lucide, fallback, x + 7, y + 8, 14, OnboardingUi.textMuted());
        OnboardingUi.drawText(FontManager.pf14bold, title, x + 22, y, OnboardingUi.textPrimary());
    }

    private static void drawStepper(OnboardingController controller, HudSetting setting,
                                    double x, double y, double width,
                                    double mouseX, double mouseY) {
        OnboardingUi.roundedRect(x, y, width, 48, OnboardingUi.CARD_RADIUS - 1,
                OnboardingUi.surfaceHover());
        OnboardingUi.roundedOutline(x, y, width, 48, OnboardingUi.CARD_RADIUS - 1, 1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.hairline(), 165));
        OnboardingUi.drawText(FontManager.pf12bold, setting.getLabel(), x + 14, y + 10,
                OnboardingUi.textSecondary());
        // 数值居中：以前写死 x + width * .5 - 20，那是「3.00x」四个字符的半个宽度，
        // 换成 12.50x 或者别的字体就整个偏右。
        OnboardingUi.drawCentered(FontManager.pf14bold,
                String.format(java.util.Locale.ROOT, "%.2fx", controller.hudValue(setting)),
                x + width * .5, y + 10, OnboardingUi.textPrimary());

        drawStepButton(x + 12, y + 26, "-", mouseX, mouseY);
        drawStepButton(x + width - 40, y + 26, "+", mouseX, mouseY);
    }

    /** 加减按钮：只画，按下在哪由 {@link #mouseClicked} 用同一份坐标算。 */
    private static void drawStepButton(double x, double y, String glyph,
                                       double mouseX, double mouseY) {
        boolean hover = OnboardingUi.inside(mouseX, mouseY, x, y, 28, 18);
        OnboardingUi.roundedRect(x, y, 28, 18, 9,
                hover ? OnboardingUi.colorWithAlpha(OnboardingUi.BLUE, 190) : OnboardingUi.surface());
        OnboardingUi.drawCentered(FontManager.pf12bold, glyph, x + 14, y + 4,
                OnboardingUi.textPrimary());
    }

    /** 主题色 + alpha。见 {@link #drawCard} 里那段「裸 RGB 当 ARGB 用就整块透明」的说明。 */
    private static int of(NCMTheme.ThemePreset theme, NCMScreen.ColorType type, float alpha) {
        return RGBA.color(theme.getColor(type), alpha);
    }

    // 卡片与两段的几何只有这一份算法，绘制与命中测试都读它。
    // 以前 render 用 y + 95、mouseClicked 用 y + 95 各写一遍字面量，
    // 改一处忘一处就会出现「看得见却点不到」——上一轮在小窗口上正是这个症状。

    static double cardWidth(double width) {
        return (width - 24) / 3;
    }

    static double cardX(double x, double width, int index) {
        return x + index * (cardWidth(width) + 12);
    }

    static double cardY(double y) {
        return y + OnboardingUi.BODY_OFFSET;
    }

    static double cardHeight() {
        return CARD_H;
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }

        NCMTheme.ThemePreset[] themes = NCMTheme.ThemePreset.values();
        int start = controller.selectedTheme().ordinal() - 1;
        double cardY = cardY(y);
        for (int i = 0; i < 3; i++) {
            double cardX = cardX(x, width, i);
            if (OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth(width), CARD_H)) {
                controller.selectTheme(themes[Math.floorMod(start + i, themes.length)]);
                return true;
            }
        }

        double fontBoxY = cardY + CARD_H + 22 + 24;
        if (OnboardingUi.inside(mouseX, mouseY, x, fontBoxY, width, 46)) {
            controller.cycleFont();
            return true;
        }

        double stepperY = fontBoxY + 46 + 26 + 24;
        if (OnboardingUi.inside(mouseX, mouseY, x + 12, stepperY + 26, 28, 18)) {
            controller.adjustHudSetting(HudSetting.PLAYER_UI_SWITCH_SPEED, -0.10f);
            return true;
        }
        if (OnboardingUi.inside(mouseX, mouseY, x + width - 40, stepperY + 26, 28, 18)) {
            controller.adjustHudSetting(HudSetting.PLAYER_UI_SWITCH_SPEED, 0.10f);
            return true;
        }
        return false;
    }
}
