package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.party.ui.PartyIcons;

/**
 * 第一页：一句标题 + 四个能力胶囊，什么都不问。
 *
 * <p>那枚描出来的「hello」<b>不在这里</b>——它是压在整页之上的覆盖层
 * （{@code OnboardingOverlay}），收掉之后露出来的就是这一页。所以这一页只负责
 * 「你将要配的是什么」，不重复表演一遍动画。</p>
 *
 * <h2>为什么是「一坨」而不是四个百分比</h2>
 *
 * <p>原来四段各占一格（16% / 40% / 56% / 74%）：内容带一高，段与段就跟着一起摊开，
 * 大屏上四行小字散在面板四角，读起来不像一页。现在四段之间的间距是<b>固定值</b>，
 * 整块在内容带里垂直居中——大带子得到的是留白，不是被拉开的距离；小带子得到的是裁切，
 * 而固定间距顺手保证了「再小也不会叠字」（百分比那一版在 289×153 的设计空间里
 * 标题和副标题只差 25 px，是实打实会压上的）。</p>
 */
public final class WelcomeStep implements OnboardingStepView {

    private static final String[] TAGS = {"逐字歌词", "灵动岛通知", "一起听房间", "智能混音"};
    /**
     * 四枚胶囊各自那枚图标。
     *
     * <p>这一页是玩家看清「我装的是什么」的地方，四行字里夹四个圆角框，读起来像标签云；
     * 每枚前面加一个和播放器/Folia 设置同源的小图标，四件事各自有了形状。矢量画不出来时
     * 退回 Material 那几枚同类的（见 {@link OnboardingUi#drawIcon}）。</p>
     */
    private static final String[] TAG_ICONS = {
            FoliaLucideIcons.SUBTITLES, FoliaLucideIcons.ISLAND,
            FoliaLucideIcons.USER, FoliaLucideIcons.AUTOMIX
    };
    private static final String[] TAG_FALLBACK = {
            PartyIcons.TEXT_FORMAT, PartyIcons.INFO, PartyIcons.GROUP, PartyIcons.SYNC
    };

    /** 胶囊里图标占的宽度与它和文字之间的间距。宽度算的是实测文字 + 这一截。 */
    private static final double PILL_ICON_W = 14;
    private static final double PILL_ICON_GAP = 7;

    /** 行高与间距都是设计空间里的逻辑像素，不随窗口变。 */
    private static final double EYEBROW_H = 12;
    private static final double TITLE_H = 44;
    private static final double SUBTITLE_H = 16;
    private static final double PILL_H = 34;
    private static final double GAP_EYEBROW_TITLE = 26;
    private static final double GAP_TITLE_SUBTITLE = 18;
    private static final double GAP_SUBTITLE_PILLS = 42;
    private static final double PILL_PAD_X = 16;
    private static final double PILL_GAP_X = 10;
    private static final double PILL_GAP_Y = 8;
    /** 胶囊最多排两行；再挤就不如不画，这一页的主角是那行标题。 */
    private static final int PILL_ROWS = 2;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        double centerX = x + width * .5;
        double[] pillWidths = pillWidths();
        double pillsHeight = pillsHeight(pillWidths, width);

        double blockHeight = EYEBROW_H + GAP_EYEBROW_TITLE + TITLE_H + GAP_TITLE_SUBTITLE
                + SUBTITLE_H + GAP_SUBTITLE_PILLS + pillsHeight;
        // 块比带子高时贴着顶走（超出部分由内容带裁掉），不要算出负数把标题推到带外。
        double top = y + Math.max(0.0, (height - blockHeight) * .5);

        OnboardingUi.drawEyebrowCentered("MINECRAFT MUSIC CLIENT · 1.9.1", centerX, top);
        double titleY = top + EYEBROW_H + GAP_EYEBROW_TITLE;
        OnboardingUi.drawCentered(FontManager.pf40bold, "把音乐装进 Minecraft", centerX, titleY,
                OnboardingUi.textPrimary());
        double subtitleY = titleY + TITLE_H + GAP_TITLE_SUBTITLE;
        OnboardingUi.drawCentered(FontManager.pf14, "连接账号、选择音源、定制外观，再让 Automix 在本机理解节拍。",
                centerX, subtitleY, OnboardingUi.textSecondary());
        renderPills(pillWidths, centerX, subtitleY + SUBTITLE_H + GAP_SUBTITLE_PILLS, width);
    }

    /**
     * 每条胶囊按自己的内容实测多宽——写死 92 的话「逐字歌词」空一半、「智能混音」顶到边。
     * 内容 = 图标 + 间距 + 文字，左右各留 {@value #PILL_PAD_X}。
     */
    private static double[] pillWidths() {
        double[] widths = new double[TAGS.length];
        for (int i = 0; i < TAGS.length; i++) {
            widths[i] = PILL_ICON_W + PILL_ICON_GAP
                    + OnboardingUi.textWidth(FontManager.pf12bold, TAGS[i]) + PILL_PAD_X * 2;
        }
        return widths;
    }

    /** 一行排得下就一行，排不下就按两行算（胶囊是点缀，不该把内容带撑破）。 */
    private static double pillsHeight(double[] widths, double available) {
        return rowsFor(widths, available) > 1 ? PILL_H * PILL_ROWS + PILL_GAP_Y : PILL_H;
    }

    private static void renderPills(double[] widths, double centerX, double top, double available) {
        // 两行的时候按「前半 / 后半」分，比贪心排出来的 3+1 好看。
        int perRow = rowsFor(widths, available) > 1 ? (widths.length + 1) / 2 : widths.length;
        int rows = (widths.length + perRow - 1) / perRow;
        for (int row = 0; row < rows; row++) {
            int from = row * perRow;
            int to = Math.min(from + perRow, widths.length);
            double rowWidth = 0;
            for (int i = from; i < to; i++) {
                rowWidth += widths[i] + (i > from ? PILL_GAP_X : 0);
            }
            double cursorX = centerX - rowWidth * .5;
            double rowY = top + row * (PILL_H + PILL_GAP_Y);
            for (int i = from; i < to; i++) {
                renderPill(i, cursorX, rowY, widths[i], i == widths.length - 1);
                cursorX += widths[i] + PILL_GAP_X;
            }
        }
    }

    private static void renderPill(int index, double left, double top, double pillWidth,
                                   boolean highlight) {
        OnboardingUi.roundedRect(left, top, pillWidth, PILL_H, PILL_H * .5,
                highlight ? OnboardingUi.themed(NCMScreen.ColorType.ACCENT, .18f)
                        : OnboardingUi.surface());
        OnboardingUi.roundedOutline(left, top, pillWidth, PILL_H, PILL_H * .5, 1,
                highlight ? OnboardingUi.accent() : OnboardingUi.hairline());
        // 内容（图标 + 间距 + 文字）整组居中：胶囊宽就是「这一组 + 左右各 PILL_PAD_X」量出来的，
        // 所以内容起点正好落在 left + PILL_PAD_X 上，不必再算一遍居中。
        int contentColor = highlight ? OnboardingUi.textPrimary() : OnboardingUi.textSecondary();
        OnboardingUi.drawIcon(TAG_ICONS[index], TAG_FALLBACK[index],
                left + PILL_PAD_X + PILL_ICON_W * .5, top + PILL_H * .5, PILL_ICON_W,
                highlight ? OnboardingUi.accent() : OnboardingUi.textMuted());
        OnboardingUi.drawText(FontManager.pf12bold, TAGS[index],
                left + PILL_PAD_X + PILL_ICON_W + PILL_ICON_GAP, top + PILL_H * .5 - 5, contentColor);
    }

    /** 需要几行：一行放得下就是 1，否则按 {@value #PILL_ROWS} 行贪心排。 */
    private static int rowsFor(double[] widths, double available) {
        double row = 0;
        int rows = 1;
        for (double w : widths) {
            row += row > 0 ? PILL_GAP_X + w : w;
            if (row > available) {
                rows++;
                row = w;
            }
        }
        return Math.min(rows, PILL_ROWS);
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        return false;
    }
}
