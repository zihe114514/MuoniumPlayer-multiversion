package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.party.ui.PartyIcons;

/** Appearance page built only from existing theme, font and animation settings. */
public final class AppearanceStep implements OnboardingStepView {
    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawText(FontManager.pf34bold, "找到你的风格", x, y + 22, OnboardingUi.WHITE);
        OnboardingUi.drawText(FontManager.pf14, "主题、字体和动画会实时预览，并使用现有样式配置。",
                x, y + 58, OnboardingUi.SOFT);

        NCMTheme.ThemePreset[] themes = NCMTheme.ThemePreset.values();
        int start = controller.selectedTheme().ordinal() - 1;
        for (int i = 0; i < 3; i++) {
            int index = Math.floorMod(start + i, themes.length);
            NCMTheme.ThemePreset theme = themes[index];
            double cardWidth = (width - 24) / 3;
            double cardX = x + i * (cardWidth + 12);
            double cardY = y + 95;
            boolean selected = theme == controller.selectedTheme();
            boolean hover = OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth, 110);
            OnboardingUi.roundedRect(cardX, cardY, cardWidth, 110, 16,
                    selected ? OnboardingUi.colorWithAlpha(theme.getColor(NCMScreen.ColorType.ACCENT), 60)
                            : (hover ? OnboardingUi.GLASS_2 : OnboardingUi.GLASS));
            OnboardingUi.roundedRect(cardX + 12, cardY + 12, cardWidth - 24, 42, 11,
                    theme.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            OnboardingUi.roundedRect(cardX + 12, cardY + 62, cardWidth - 24, 8, 4,
                    theme.getColor(NCMScreen.ColorType.ACCENT));
            OnboardingUi.drawPartyIcon(PartyIcons.TEXT_FORMAT, cardX + 24, cardY + 88, 22,
                    theme.getColor(NCMScreen.ColorType.ACCENT));
            OnboardingUi.drawText(FontManager.pf12bold, theme.getDisplayName(),
                    cardX + 42, cardY + 83, OnboardingUi.WHITE);
            OnboardingUi.drawText(FontManager.pf10bold, selected ? "SELECTED" : "PREVIEW",
                    cardX + 12, cardY + 97, selected ? OnboardingUi.CYAN : OnboardingUi.DIM);
        }

        double fontY = y + 235;
        OnboardingUi.drawText(FontManager.pf14bold, "字体", x, fontY, OnboardingUi.WHITE);
        UiFont font = controller.selectedFont();
        String name = font == null ? "内置字体" : font.getDisplayName();
        OnboardingUi.drawText(FontManager.pf12, name + " · 点击切换",
                x + 42, fontY + 1, OnboardingUi.DIM);
        OnboardingUi.roundedRect(x, fontY + 24, width, 46, 14, OnboardingUi.GLASS_2);
        OnboardingUi.roundedOutline(x, fontY + 24, width, 46, 14, 1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.LINE, 170));
        OnboardingUi.drawText(FontManager.pf18, UiFontLibrary.sampleText(),
                x + 16, fontY + 37, OnboardingUi.CYAN);

        double animationY = fontY + 96;
        OnboardingUi.drawText(FontManager.pf14bold, "界面动画", x, animationY, OnboardingUi.WHITE);
        renderStepper(controller, HudSetting.PLAYER_UI_SWITCH_SPEED,
                x, animationY + 24, width, mouseX, mouseY);
    }

    private static void renderStepper(OnboardingController controller, HudSetting setting,
                                      double x, double y, double width,
                                      double mouseX, double mouseY) {
        OnboardingUi.roundedRect(x, y, width, 48, 14, OnboardingUi.GLASS_2);
        OnboardingUi.roundedOutline(x, y, width, 48, 14, 1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.LINE, 165));
        OnboardingUi.drawText(FontManager.pf12bold, setting.getLabel(), x + 14, y + 10, OnboardingUi.SOFT);
        OnboardingUi.drawText(FontManager.pf14bold, String.format("%.2fx", controller.hudValue(setting)),
                x + width * 0.5 - 20, y + 10, OnboardingUi.WHITE);

        boolean minusHover = OnboardingUi.inside(mouseX, mouseY, x + 12, y + 26, 28, 18);
        boolean plusHover = OnboardingUi.inside(mouseX, mouseY, x + width - 40, y + 26, 28, 18);
        OnboardingUi.roundedRect(x + 12, y + 26, 28, 18, 9,
                minusHover ? OnboardingUi.colorWithAlpha(OnboardingUi.BLUE, 190) : OnboardingUi.GLASS);
        OnboardingUi.drawText(FontManager.pf12bold, "−", x + 22, y + 31, OnboardingUi.WHITE);
        OnboardingUi.roundedRect(x + width - 40, y + 26, 28, 18, 9,
                plusHover ? OnboardingUi.colorWithAlpha(OnboardingUi.BLUE, 190) : OnboardingUi.GLASS);
        OnboardingUi.drawText(FontManager.pf12bold, "+", x + width - 30, y + 31, OnboardingUi.WHITE);
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
        double cardWidth = (width - 24) / 3;
        for (int i = 0; i < 3; i++) {
            int index = Math.floorMod(start + i, themes.length);
            double cardX = x + i * (cardWidth + 12);
            if (OnboardingUi.inside(mouseX, mouseY, cardX, y + 95, cardWidth, 110)) {
                controller.selectTheme(themes[index]);
                return true;
            }
        }

        if (OnboardingUi.inside(mouseX, mouseY, x, y + 259, width, 46)) {
            controller.cycleFont();
            return true;
        }

        double animationY = y + 331;
        if (OnboardingUi.inside(mouseX, mouseY, x + 12, animationY + 50, 28, 18)) {
            controller.adjustHudSetting(HudSetting.PLAYER_UI_SWITCH_SPEED, -0.10f);
            return true;
        }
        if (OnboardingUi.inside(mouseX, mouseY, x + width - 40, animationY + 50, 28, 18)) {
            controller.adjustHudSetting(HudSetting.PLAYER_UI_SWITCH_SPEED, 0.10f);
            return true;
        }
        return false;
    }
}
