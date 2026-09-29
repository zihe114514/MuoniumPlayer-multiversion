package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 音乐源页。选择立即应用并由控制器持久化。 */
public final class SourceStep implements OnboardingStepView {
    private static final MusicPlatform[] PLATFORMS = {
            MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU,
            MusicPlatform.YOUTUBE, MusicPlatform.BILIBILI, MusicPlatform.QISHUI, MusicPlatform.GD
    };

    /** 两列卡：行高、列间距、卡片圆角。绘制与命中测试读同一份。 */
    private static final double CARD_H = 56;
    private static final double CARD_GAP = 12;
    private static final double NOTE_GAP = 18;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawHeader("SOURCES", "选择音源",
                "选中的来源会立即应用到播放器并保存。", x, y);

        for (int i = 0; i < PLATFORMS.length; i++) {
            MusicPlatform platform = PLATFORMS[i];
            double cardX = cardX(x, width, i);
            double cardY = cardY(y, i);
            boolean selected = controller.selectedPlatform() == platform;
            boolean hover = OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth(width), CARD_H);
            OnboardingUi.roundedRect(cardX, cardY, cardWidth(width), CARD_H, OnboardingUi.CARD_RADIUS,
                    selected ? OnboardingUi.colorWithAlpha(OnboardingUi.BLUE, 50)
                            : (hover ? OnboardingUi.surfaceHover() : OnboardingUi.surface()));
            OnboardingUi.roundedOutline(cardX, cardY, cardWidth(width), CARD_H,
                    OnboardingUi.CARD_RADIUS, selected ? 1.6 : 1,
                    OnboardingUi.awtColorWithAlpha(selected ? OnboardingUi.BLUE : OnboardingUi.hairline(),
                            selected ? 230 : 160));

            // 徽标压在一块底盘上：七家的品牌图形一大一小（网易云是个圆、bilibili 是一对耳朵），
            // 直接按同一个尺寸画，读起来就是「这七张卡没对齐」。
            double box = 30;
            OnboardingUi.roundedRect(cardX + 13, cardY + (CARD_H - box) * .5, box, box, box * .32,
                    OnboardingUi.colorWithAlpha(selected ? OnboardingUi.BRAND_2
                            : platform.getBrandColor(), selected ? 60 : 42));
            OnboardingUi.drawPlatformIcon(platform, cardX + 13 + box * .5, cardY + CARD_H * .5, box,
                    selected ? OnboardingUi.CYAN : OnboardingUi.textSecondary());
            OnboardingUi.drawText(FontManager.pf14bold, platform.getDisplayName(),
                    cardX + 52, cardY + 12, OnboardingUi.textPrimary());
            drawStatusDot(selected, cardX + 52, cardY + 34);
            OnboardingUi.drawText(FontManager.pf12, selected ? "当前使用" : "点击选择",
                    cardX + 62, cardY + 30, selected ? OnboardingUi.GREEN : OnboardingUi.textMuted());
            if (selected) {
                OnboardingUi.drawIcon(FoliaLucideIcons.CHECK, PartyIcons.CHECK,
                        cardX + cardWidth(width) - 24, cardY + CARD_H * .5, 20, OnboardingUi.CYAN);
            }
        }

        double noteY = cardY(y, PLATFORMS.length) + NOTE_GAP;
        OnboardingUi.drawIcon(FoliaLucideIcons.INFO, PartyIcons.INFO, x + 7, noteY + 6, 13,
                OnboardingUi.textMuted());
        OnboardingUi.drawText(FontManager.pf12,
                "YouTube 与哔哩哔哩无需登录；汽水音乐需用外部工具（网盘 / QQ群）获取 Cookie 登录；GD 沿用现有配置。",
                x + 22, noteY, OnboardingUi.textMuted());
    }

    /** 「当前使用」前面那颗小圆点：选中是绿的、未选是灰的，和文字同一基线。 */
    private static void drawStatusDot(boolean selected, double x, double y) {
        double dot = 3;
        OnboardingUi.roundedRect(x, y - dot, dot * 2, dot * 2, dot,
                selected ? OnboardingUi.GREEN : OnboardingUi.themed(
                        com.muoniumplayer.core.screens.ncm.NCMScreen.ColorType.SECONDARY_TEXT, .4f));
    }

    static double cardWidth(double width) {
        return (width - CARD_GAP) * .5;
    }

    static double cardX(double x, double width, int index) {
        return x + (index % 2) * (cardWidth(width) + CARD_GAP);
    }

    static double cardY(double y, int index) {
        return y + OnboardingUi.BODY_OFFSET + (index / 2) * (CARD_H + CARD_GAP);
    }

    static double cardHeight() {
        return CARD_H;
    }

    static int cardCount() {
        return PLATFORMS.length;
    }

    /** 整页要占多高（含最后那句说明），版式测试与「放不下先收装饰」用它。 */
    static double contentHeight() {
        return OnboardingUi.BODY_OFFSET
                + ((PLATFORMS.length + 1) / 2) * (CARD_H + CARD_GAP) - CARD_GAP + NOTE_GAP + 14;
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        for (int i = 0; i < PLATFORMS.length; i++) {
            if (OnboardingUi.inside(mouseX, mouseY, cardX(x, width, i), cardY(y, i),
                    cardWidth(width), CARD_H)) {
                controller.selectPlatform(PLATFORMS[i]);
                return true;
            }
        }
        return false;
    }
}
