package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingMetrics;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 音乐源页。选择立即应用并由控制器持久化。 */
public final class SourceStep implements OnboardingStepView {
    private static final MusicPlatform[] PLATFORMS = {
            MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU,
            MusicPlatform.YOUTUBE, MusicPlatform.BILIBILI, MusicPlatform.QISHUI, MusicPlatform.GD
    };

    /** 两列排得下所需的最小内容带宽：再窄就退成一列。 */
    private static final double MIN_TWO_COLUMN_WIDTH = 380;

    /** 两列卡：行高、列间距、卡片圆角。绘制与命中测试读同一份。 */
    private static final double BASE_CARD_H = 56;
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
            double cardY = cardY(y, width, i);
            boolean selected = controller.selectedPlatform() == platform;
            boolean hover = OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth(width), cardHeight());
            OnboardingUi.roundedRect(cardX, cardY, cardWidth(width), cardHeight(), OnboardingUi.CARD_RADIUS,
                    selected ? OnboardingUi.colorWithAlpha(OnboardingUi.BLUE, 50)
                            : (hover ? OnboardingUi.surfaceHover() : OnboardingUi.surface()));
            OnboardingUi.roundedOutline(cardX, cardY, cardWidth(width), cardHeight(),
                    OnboardingUi.CARD_RADIUS, selected ? 1.6 : 1,
                    OnboardingUi.awtColorWithAlpha(selected ? OnboardingUi.BLUE : OnboardingUi.hairline(),
                            selected ? 230 : 160));

            // 徽标压在一块底盘上：七家的品牌图形一大一小（网易云是个圆、bilibili 是一对耳朵），
            // 直接按同一个尺寸画，读起来就是「这七张卡没对齐」。
            double box = 30;
            OnboardingUi.roundedRect(cardX + 13, cardY + (cardHeight() - box) * .5, box, box, box * .32,
                    OnboardingUi.colorWithAlpha(selected ? OnboardingUi.BRAND_2
                            : platform.getBrandColor(), selected ? 60 : 42));
            OnboardingUi.drawPlatformIcon(platform, cardX + 13 + box * .5, cardY + cardHeight() * .5, box,
                    selected ? OnboardingUi.CYAN : OnboardingUi.textSecondary());
            OnboardingUi.drawText(OnboardingUi.metric().labelBold(),
                    OnboardingUi.ellipsize(OnboardingUi.metric().labelBold(), platform.getDisplayName(),
                            cardWidth(width) - 52 - 30),
                    cardX + 52, cardY + 12, OnboardingUi.textPrimary());
            drawStatusDot(selected, cardX + 52, cardY + 34);
            OnboardingUi.drawText(OnboardingUi.metric().bodyFont(), selected ? "当前使用" : "点击选择",
                    cardX + 62, cardY + 30, selected ? OnboardingUi.GREEN : OnboardingUi.textMuted());
            if (selected) {
                OnboardingUi.drawIcon(FoliaLucideIcons.CHECK, PartyIcons.CHECK,
                        cardX + cardWidth(width) - 24, cardY + cardHeight() * .5, 20, OnboardingUi.CYAN);
            }
        }

        double noteY = cardY(y, width, PLATFORMS.length) + NOTE_GAP;
        OnboardingUi.drawIcon(FoliaLucideIcons.INFO, PartyIcons.INFO, x + 7, noteY + 6, 13,
                OnboardingUi.textMuted());
        // 这句说明有 60 来字，窄档下比整条内容带还宽 —— 按可用宽截断，
        // 而不是让它一路画到面板外边（那才是「文字重叠 / 溢出」最直观的一处）。
        OnboardingUi.drawTextReserving(OnboardingUi.metric().bodyFont(),
                "YouTube 与哔哩哔哩无需登录；汽水音乐需用外部工具（网盘 / QQ群）获取 Cookie 登录；GD 沿用现有配置。",
                x + 22, noteY, x + width, 0, OnboardingUi.textMuted());
    }

    /** 「当前使用」前面那颗小圆点：选中是绿的、未选是灰的，和文字同一基线。 */
    private static void drawStatusDot(boolean selected, double x, double y) {
        double dot = 3;
        OnboardingUi.roundedRect(x, y - dot, dot * 2, dot * 2, dot,
                selected ? OnboardingUi.GREEN : OnboardingUi.themed(
                        com.muoniumplayer.core.screens.ncm.NCMScreen.ColorType.SECONDARY_TEXT, .4f));
    }

    /**
     * 卡片宽：常规是两列，内容带窄到 {@link #MIN_TWO_COLUMN_WIDTH} 以下时退成一列。
     *
     * <p>两列是为了在同一屏里放下七家；但每一列本身要装得下「图标 + 名称」才算数——
     * 内容带只剩 170 宽时，一列只有 79，连「YouTube Music」都放不下，读起来是七个被截断的词。
     * 那时改成一列，卡片变宽、能滚出七行（多出来的高度由内容带的渐隐交代）。</p>
     */
    static double cardWidth(double width) {
        return columnsFor(width) == 1 ? width : (width - CARD_GAP) * .5;
    }

    static int columnsFor(double width) {
        return width < MIN_TWO_COLUMN_WIDTH ? 1 : 2;
    }

    static double cardX(double x, double width, int index) {
        return x + (index % columnsFor(width)) * (cardWidth(width) + CARD_GAP);
    }

    static double cardY(double y, double width, int index) {
        return y + OnboardingUi.metric().bodyOffset
                + (index / columnsFor(width)) * (cardHeight() + CARD_GAP);
    }

    /** 卡片高度跟着排版档走，绘制与命中测试读同一份。 */
    static double cardHeight() {
        return Math.max(44, BASE_CARD_H + (OnboardingUi.metric().cardHeight - 56));
    }

    static int cardCount() {
        return PLATFORMS.length;
    }

    /** 整页要占多高（含最后那句说明），版式测试与「放不下先收装饰」用它。 */
    static double contentHeight(double width) {
        int columns = columnsFor(width);
        int rows = (PLATFORMS.length + columns - 1) / columns;
        return OnboardingUi.metric().bodyOffset
                + rows * (cardHeight() + CARD_GAP) - CARD_GAP + NOTE_GAP + 14;
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        for (int i = 0; i < PLATFORMS.length; i++) {
            if (OnboardingUi.inside(mouseX, mouseY, cardX(x, width, i), cardY(y, width, i),
                    cardWidth(width), cardHeight())) {
                controller.selectPlatform(PLATFORMS[i]);
                return true;
            }
        }
        return false;
    }
}
