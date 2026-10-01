package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStep;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 完成页，带实际设置结果的紧凑摘要。 */
public final class FinishStep implements OnboardingStepView {

    private static final double TITLE_H = 44;
    private static final double SUBTITLE_H = 20;
    private static final double ROW_H = 28;
    private static final double ROW_GAP = 6;
    private static final double NOTE_H = 14;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        // 这一页什么都不问，只把结果念一遍，所以整块居中读起来比贴着顶更像「到此为止」。
        //
        // 念的东西得挑：内容带只有两百多逻辑高的小窗口上，贴着顶排的「大对勾 + 标题 + 摘要 + 提示」
        // 会把最没用的那截（对勾与最后那句提示）留在框里，把真正要看的三行摘要裁掉。
        // 所以放不下时先收图标与间距，再干脆不写那句提示——按钮就在那儿，不必再说一遍。
        double icon = 62;
        double gapIconTitle = 30;
        double gapSubSummary = 26;
        double gapNote = 18;
        boolean note = true;
        double block = blockHeight(icon, gapIconTitle, gapSubSummary, gapNote, note);
        if (block > height) {
            icon = 44;
            gapIconTitle = 16;
            gapSubSummary = 16;
            gapNote = 12;
            block = blockHeight(icon, gapIconTitle, gapSubSummary, gapNote, note);
        }
        if (block > height) {
            note = false;
            block = blockHeight(icon, gapIconTitle, gapSubSummary, gapNote, false);
        }
        double top = y + Math.max(0.0, (height - block) * .5);

        OnboardingUi.roundedRect(x, top, icon, icon, icon * .35,
                OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 40));
        // 底盘描一道边：这一页是整段引导的最后一屏，那块绿色在浅主题下会淡到读不出边界。
        OnboardingUi.roundedOutline(x, top, icon, icon, icon * .35, 1.2,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.GREEN, 150));
        OnboardingUi.drawIcon(FoliaLucideIcons.CHECK, PartyIcons.CHECK,
                x + icon * .5, top + icon * .5 + 1, icon * .56, OnboardingUi.GREEN);

        double titleY = top + icon + gapIconTitle;
        OnboardingUi.drawText(FontManager.pf40bold, "一切就绪", x, titleY, OnboardingUi.textPrimary());
        OnboardingUi.drawText(FontManager.pf20, "MuoniumPlayer 已准备好陪你播放下一首。",
                x, titleY + TITLE_H - 6, OnboardingUi.textSecondary());

        double summaryY = titleY + TITLE_H - 6 + SUBTITLE_H + gapSubSummary;
        renderSummary(OnboardingStep.ACCOUNT, "账号", accountSummary(controller), x, summaryY, width);
        renderSummary(OnboardingStep.SOURCE, "音源", controller.selectedPlatform().getDisplayName(),
                x, summaryY + ROW_H + ROW_GAP, width);
        renderSummary(OnboardingStep.APPEARANCE, "主题", controller.selectedTheme().getDisplayName(),
                x, summaryY + (ROW_H + ROW_GAP) * 2, width);
        if (note) {
            OnboardingUi.drawText(FontManager.pf14, "点击右下角开始使用，随时可以回到主界面继续调整。",
                    x, summaryY + (ROW_H + ROW_GAP) * 2 + ROW_H + gapNote, OnboardingUi.textMuted());
        }
    }

    private static double blockHeight(double icon, double gapIconTitle, double gapSubSummary,
                                      double gapNote, boolean note) {
        double height = icon + gapIconTitle + TITLE_H + SUBTITLE_H + gapSubSummary
                + ROW_H * 3 + ROW_GAP * 2;
        return note ? height + gapNote + NOTE_H : height;
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

    /**
     * 一行摘要：图标 + 标签 + 结果。
     *
     * <p>标签与结果之间的位置<b>量出来</b>，不是写死 {@code x + 74}：那只是「账号」两个字的宽度，
     * 换成「主题」或者标签字体一大一档，结果就往左压到标签上。图标走侧栏同一套
     * （{@link OnboardingUi#drawStepIcon}），这一页念的三件事和左边那一列是同一件事。</p>
     */
    private static void renderSummary(OnboardingStep step, String label, String value,
                                      double x, double y, double width) {
        OnboardingUi.roundedRect(x, y, width, ROW_H, 9, OnboardingUi.surfaceHover());
        OnboardingUi.drawStepIcon(step, x + 20, y + ROW_H * .5, 14, OnboardingUi.textMuted());
        OnboardingUi.drawText(FontManager.pf12, label, x + 34, y + 9, OnboardingUi.textMuted());
        OnboardingUi.drawText(FontManager.pf12bold, value,
                x + 34 + OnboardingUi.textWidth(FontManager.pf12, label) + 12, y + 9,
                OnboardingUi.textPrimary());
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        return false;
    }
}
