package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** Completion page with a compact summary of the actual setup result. */
public final class FinishStep implements OnboardingStepView {
    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.roundedRect(x, y + 40, 62, 62, 22,
                OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 40));
        OnboardingUi.drawPartyIcon(PartyIcons.CHECK, x + 31, y + 71, 40, OnboardingUi.GREEN);

        OnboardingUi.drawText(FontManager.pf40bold, "一切就绪", x, y + 155, OnboardingUi.WHITE);
        OnboardingUi.drawText(FontManager.pf20, "MuoniumPlayer 已准备好陪你播放下一首。",
                x, y + 198, OnboardingUi.SOFT);

        double summaryY = y + 248;
        renderSummary("账号", accountSummary(controller), x, summaryY, width);
        renderSummary("音源", controller.selectedPlatform().getDisplayName(), x, summaryY + 34, width);
        renderSummary("主题", controller.selectedTheme().getDisplayName(), x, summaryY + 68, width);

        OnboardingUi.drawText(FontManager.pf14, "点击右下角开始使用，随时可以回到主界面继续调整。",
                x, y + 348, OnboardingUi.DIM);
    }

    private static String accountSummary(OnboardingController controller) {
        boolean netease = NeteaseMusicService.isLoggedIn(com.muoniumplayer.core.ncm.music.MusicPlatform.NETEASE);
        boolean qq = NeteaseMusicService.isLoggedIn(com.muoniumplayer.core.ncm.music.MusicPlatform.QQ);
        boolean kugou = NeteaseMusicService.isLoggedIn(com.muoniumplayer.core.ncm.music.MusicPlatform.KUGOU);
        if (netease || qq || kugou) {
            return "已登录";
        }
        return "稍后登录";
    }

    private static void renderSummary(String label, String value,
                                      double x, double y, double width) {
        OnboardingUi.roundedRect(x, y, width, 28, 9, OnboardingUi.GLASS_2);
        OnboardingUi.drawText(FontManager.pf12, label, x + 12, y + 9, OnboardingUi.DIM);
        OnboardingUi.drawText(FontManager.pf12bold, value, x + 74, y + 9, OnboardingUi.WHITE);
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        return false;
    }
}
