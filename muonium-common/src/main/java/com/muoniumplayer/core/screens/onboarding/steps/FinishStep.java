package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingMetrics;
import com.muoniumplayer.core.screens.onboarding.OnboardingStep;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 完成页，带实际设置结果的紧凑摘要。 */
public final class FinishStep implements OnboardingStepView {

    private static final double TITLE_H = 44;
    private static final double SUBTITLE_H = 20;
    private static final double NOTE_H = 14;
    /** 摘要卡有几行：账号 / 音源 / 主题 / 沉浸感。 */
    private static final int SUMMARY_ROWS = 4;

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
        OnboardingUi.drawText(OnboardingUi.metric().titleFont(), "一切就绪", x, titleY, OnboardingUi.textPrimary());
        OnboardingUi.drawText(OnboardingUi.metric().subtitleFont(), "MuoniumPlayer 已准备好陪你播放下一首。",
                x, titleY + TITLE_H - 6, OnboardingUi.textSecondary());

        double summaryY = titleY + TITLE_H - 6 + SUBTITLE_H + gapSubSummary;
        // 四张摘要卡：账号 / 音源 / 主题 / 沉浸感。走和前面几页同一套卡壳，
        // 这样「完成」这一屏不是另画一套摘要条，而是刚才那一路选下来的同一批卡片。
        double rowH = summaryRowHeight();
        double rowGap = Math.max(4, rowH * .14);
        String[] labels = {"账号", "音源", "主题", "沉浸感"};
        String[] values = {
                accountSummary(),
                controller.selectedPlatform().getDisplayName(),
                controller.selectedTheme().getDisplayName(),
                immersionSummary(controller)
        };
        OnboardingStep[] icons = {
                OnboardingStep.ACCOUNT, OnboardingStep.SOURCE,
                OnboardingStep.APPEARANCE, OnboardingStep.AUTOMIX
        };
        for (int i = 0; i < labels.length; i++) {
            renderSummaryCard(icons[i], labels[i], values[i], x,
                    summaryY + (rowH + rowGap) * i, width, rowH);
            if (summaryY + (rowH + rowGap) * (i + 1) > y + height) {
                // 内容带装不下四张就不往下画：被裁掉半张卡比少一张更难读。
                break;
            }
        }
        if (note) {
            OnboardingUi.drawText(OnboardingUi.metric().bodyFont(), "点击右下角开始使用，随时可以回到主界面继续调整。",
                    x, summaryY + (rowH + rowGap) * labels.length + gapNote, OnboardingUi.textMuted());
        }
    }

    /** 沉浸感那一行摘要：两项都开、只开一项、都没开。 */
    private static String immersionSummary(OnboardingController controller) {
        boolean acoustics = controller.acousticsEnabled();
        boolean ambient = controller.ambientEnabled();
        if (acoustics && ambient) return "声学 + 环境选曲";
        if (acoustics) return "场景声学";
        if (ambient) return "环境选曲";
        return "保持关闭";
    }

    /** 摘要卡的高度：跟排版档走，比正文卡矮一点（它只有一行）。 */
    static double summaryRowHeight() {
        return Math.max(26, OnboardingUi.metric().cardHeight * .5);
    }

    private static double blockHeight(double icon, double gapIconTitle, double gapSubSummary,
                                      double gapNote, boolean note) {
        double rowH = summaryRowHeight();
        double rowGap = Math.max(4, rowH * .14);
        double height = icon + gapIconTitle + TITLE_H + SUBTITLE_H + gapSubSummary
                + rowH * SUMMARY_ROWS + rowGap * (SUMMARY_ROWS - 1);
        return note ? height + gapNote + NOTE_H : height;
    }

    private static String accountSummary() {
        // 与账号页那条状态条同一份判定（六家全看），否则在管理器里登了汽水/Apple 的玩家
        // 会在最后一页看到「稍后登录」——而他明明刚登录过。
        return OnboardingUi.anyAccountLoggedIn() ? "已登录" : "稍后登录";
    }

    /**
     * 一行摘要卡：图标 + 标签 + 结果。
     *
     * <p>标签与结果之间的位置<b>量出来</b>，不是写死 {@code x + 74}：那只是「账号」两个字的宽度，
     * 换成「主题」或者标签字体一大一档，结果就往左压到标签上。结果本身再按右边界截断
     * （音源名「YouTube Music」配大字号时能撞到卡右边）。</p>
     */
    private static void renderSummaryCard(OnboardingStep step, String label, String value,
                                          double x, double y, double width, double rowH) {
        double[] content = OnboardingUi.card(x, y, width, rowH,
                OnboardingUi.CardState.IDLE, OnboardingUi.textMuted());
        OnboardingUi.drawStepIcon(step, content[0] + 8, content[1] + content[3] * .5, 14,
                OnboardingUi.textMuted());
        double labelX = content[0] + 24;
        OnboardingUi.drawText(OnboardingUi.metric().bodyFont(), label, labelX,
                content[1] + content[3] * .5 - OnboardingMetrics.lineHeight(
                        OnboardingUi.metric().bodyFont()) * .5,
                OnboardingUi.textMuted());
        double valueX = labelX + OnboardingUi.textWidth(OnboardingUi.metric().bodyFont(), label) + 12;
        // 结果靠右对齐到内容区右端：四行读下来是「标签一列、结果一列」，比跟在标签后面更好扫。
        double available = content[0] + content[2] - valueX;
        String fitted = OnboardingUi.ellipsize(OnboardingUi.metric().labelBold(), value, available);
        double valueW = OnboardingUi.textWidth(OnboardingUi.metric().labelBold(), fitted);
        OnboardingUi.drawText(OnboardingUi.metric().labelBold(), fitted,
                content[0] + content[2] - valueW,
                content[1] + content[3] * .5 - OnboardingMetrics.lineHeight(
                        OnboardingUi.metric().labelBold()) * .5,
                OnboardingUi.textPrimary());
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        return false;
    }
}
