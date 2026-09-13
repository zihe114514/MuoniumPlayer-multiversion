package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingGreeting;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;

/** Apple-inspired welcome page: one animated greeting, almost no copy. */
public final class WelcomeStep implements OnboardingStepView {
    private final OnboardingGreeting greeting = new OnboardingGreeting();

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        double centerX = x + width * 0.5;
        double greetingY = y + height * 0.38;
        greeting.render(centerX, greetingY, Math.min(width * 0.68, 620), 122, 1.0);

        OnboardingUi.drawEyebrow("MINECRAFT MUSIC CLIENT · 1.4.10", centerX - 126, y + height * 0.13);
        OnboardingUi.drawText(FontManager.pf40bold, "把音乐装进 Minecraft", centerX - 174,
                y + height * 0.64, OnboardingUi.WHITE);
        OnboardingUi.drawText(FontManager.pf14, "连接账号、选择音源、定制外观，再让 Automix 在本机理解节拍。",
                centerX - 207, y + height * 0.74, OnboardingUi.SOFT);

        String[] tags = {"逐字歌词", "灵动岛通知", "一起听房间", "智能混音"};
        double total = 4 * 92 + 3 * 9;
        for (int i = 0; i < tags.length; i++) {
            double tagX = centerX - total * .5 + i * 101;
            OnboardingUi.roundedRect(tagX, y + height * .83, 92, 28, 14,
                    OnboardingUi.colorWithAlpha(i == 3 ? OnboardingUi.BRAND : OnboardingUi.WHITE,
                            i == 3 ? 28 : 13));
            OnboardingUi.roundedOutline(tagX, y + height * .83, 92, 28, 14, 1,
                    OnboardingUi.awtColorWithAlpha(i == 3 ? OnboardingUi.BRAND_2 : OnboardingUi.WHITE,
                            i == 3 ? 90 : 28));
            OnboardingUi.drawText(FontManager.pf12bold, tags[i], tagX + 17,
                    y + height * .83 + 9, i == 3 ? OnboardingUi.BRAND_2 : OnboardingUi.SOFT);
        }
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        return false;
    }

    @Override
    public void close() {
        greeting.close();
    }
}
