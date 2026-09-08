package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingGreeting;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** Apple-inspired welcome page: one animated greeting, almost no copy. */
public final class WelcomeStep implements OnboardingStepView {
    private final OnboardingGreeting greeting = new OnboardingGreeting();

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        double centerX = x + width * 0.5;
        double greetingY = y + height * 0.40;
        greeting.render(centerX, greetingY, Math.min(width * 0.68, 620), 126, 1.0);

        OnboardingUi.drawText(FontManager.pf12bold, "MUONIUMPLAYER",
                centerX - 52, y + height * 0.18, OnboardingUi.DIM);
        OnboardingUi.drawPartyIcon(PartyIcons.NOTE, centerX - 66,
                y + height * 0.18 + 6, 24, OnboardingUi.CYAN);

        OnboardingUi.drawText(FontManager.pf40bold, "欢迎进入你的音乐空间",
                centerX - 168, y + height * 0.66, OnboardingUi.WHITE);
        OnboardingUi.drawText(FontManager.pf14, "几步完成账号、音源与外观设置。",
                centerX - 88, y + height * 0.75, OnboardingUi.SOFT);
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
