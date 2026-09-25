package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 账号登录页；它打开既有的账号管理器覆盖层。 */
public final class AccountStep implements OnboardingStepView {
    private static final MusicPlatform[] PLATFORMS = {
            MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU
    };

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawText(FontManager.pf34bold, "连接账号", x, y + 22, OnboardingUi.WHITE);
        OnboardingUi.drawText(FontManager.pf14, "登录是可选的。我们会继续使用现有的本地账号系统。",
                x, y + 58, OnboardingUi.SOFT);

        boolean anyLogged = false;
        for (MusicPlatform platform : PLATFORMS) {
            anyLogged |= NeteaseMusicService.isLoggedIn(platform);
        }
        double statusY = y + 92;
        OnboardingUi.roundedRect(x, statusY, width, 42, 13, OnboardingUi.GLASS_2);
        OnboardingUi.roundedOutline(x, statusY, width, 42, 13, 1,
                OnboardingUi.awtColorWithAlpha(anyLogged ? OnboardingUi.GREEN : OnboardingUi.LINE, 175));
        OnboardingUi.roundedRect(x + 14, statusY + 14, 14, 14, 7,
                anyLogged ? OnboardingUi.GREEN : OnboardingUi.DIM);
        OnboardingUi.drawText(FontManager.pf12bold, anyLogged ? "已登录" : "未登录",
                x + 38, statusY + 16, anyLogged ? OnboardingUi.GREEN : OnboardingUi.DIM);
        OnboardingUi.drawText(FontManager.pf12, anyLogged ? "账号状态会实时更新" : "可以稍后在主界面登录",
                x + 92, statusY + 16, OnboardingUi.DIM);

        for (int i = 0; i < PLATFORMS.length; i++) {
            MusicPlatform platform = PLATFORMS[i];
            double rowY = statusY + 58 + i * 62;
            boolean logged = NeteaseMusicService.isLoggedIn(platform);
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, rowY, width, 48);
            OnboardingUi.roundedRect(x, rowY, width, 48, 14,
                    hover ? OnboardingUi.GLASS_2 : OnboardingUi.GLASS);
            OnboardingUi.roundedOutline(x, rowY, width, 48, 14, 1,
                    OnboardingUi.awtColorWithAlpha(platform.getBrandColor(), logged ? 210 : 105));
            OnboardingUi.roundedRect(x + 14, rowY + 12, 24, 24, 8,
                    OnboardingUi.colorWithAlpha(platform.getBrandColor(), 85));
            OnboardingUi.drawPlatformIcon(platform, x + 26, rowY + 24, 24, OnboardingUi.WHITE);
            OnboardingUi.drawText(FontManager.pf14bold, platform.getDisplayName(),
                    x + 52, rowY + 11, OnboardingUi.WHITE);
            OnboardingUi.drawText(FontManager.pf12,
                    logged ? NeteaseMusicService.getAccountName(platform) : "未登录 · 点击连接",
                    x + 52, rowY + 28, logged ? OnboardingUi.GREEN : OnboardingUi.DIM);
            OnboardingUi.drawPartyIcon(logged ? PartyIcons.CHECK : PartyIcons.CHEVRON_RIGHT,
                    x + width - 22, rowY + 24, 22,
                    logged ? OnboardingUi.GREEN : OnboardingUi.BLUE);
        }
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        for (int i = 0; i < PLATFORMS.length; i++) {
            if (OnboardingUi.inside(mouseX, mouseY, x, y + 150 + i * 62, width, 48)) {
                controller.openAccountManager();
                return true;
            }
        }
        return false;
    }
}
