package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** Music source page. Selection is applied immediately and persisted by the controller. */
public final class SourceStep implements OnboardingStepView {
    private static final MusicPlatform[] PLATFORMS = {
            MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU, MusicPlatform.GD
    };

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawText(FontManager.pf34bold, "选择音源", x, y + 22, OnboardingUi.WHITE);
        OnboardingUi.drawText(FontManager.pf14, "选中的来源会立即应用到播放器并保存。",
                x, y + 58, OnboardingUi.SOFT);

        for (int i = 0; i < PLATFORMS.length; i++) {
            MusicPlatform platform = PLATFORMS[i];
            double cardWidth = (width - 12) * 0.5;
            double cardX = x + (i % 2) * (cardWidth + 12);
            double cardY = y + 94 + (i / 2) * 66;
            boolean selected = controller.selectedPlatform() == platform;
            boolean hover = OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth, 52);
            OnboardingUi.roundedRect(cardX, cardY, cardWidth, 52, 15,
                    selected ? OnboardingUi.colorWithAlpha(OnboardingUi.BLUE, 50)
                            : (hover ? OnboardingUi.GLASS_2 : OnboardingUi.GLASS));
            OnboardingUi.roundedOutline(cardX, cardY, cardWidth, 52, 15,
                    selected ? 1.6 : 1,
                    OnboardingUi.awtColorWithAlpha(selected ? OnboardingUi.BLUE : OnboardingUi.LINE,
                            selected ? 230 : 160));
            OnboardingUi.drawPlatformIcon(platform, cardX + 26, cardY + 26, 24,
                    selected ? OnboardingUi.CYAN : OnboardingUi.SOFT);
            OnboardingUi.drawText(FontManager.pf14bold, platform.getDisplayName(),
                    cardX + 48, cardY + 12, OnboardingUi.WHITE);
            OnboardingUi.drawText(FontManager.pf12, selected ? "当前使用" : "点击选择",
                    cardX + 16, cardY + 31, selected ? OnboardingUi.GREEN : OnboardingUi.DIM);
            if (selected) {
                OnboardingUi.drawPartyIcon(PartyIcons.CHECK,
                        cardX + cardWidth - 24, cardY + 26, 22, OnboardingUi.CYAN);
            }
        }

        OnboardingUi.drawText(FontManager.pf12, "GD 音乐台沿用现有的平台配置。",
                x, y + 254, OnboardingUi.DIM);
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        for (int i = 0; i < PLATFORMS.length; i++) {
            MusicPlatform platform = PLATFORMS[i];
            double cardWidth = (width - 12) * 0.5;
            double cardX = x + (i % 2) * (cardWidth + 12);
            double cardY = y + 94 + (i / 2) * 66;
            if (OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth, 52)) {
                controller.selectPlatform(platform);
                return true;
            }
        }
        return false;
    }
}
