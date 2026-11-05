package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingMetrics;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 账号登录页；它打开既有的账号管理器覆盖层。 */
public final class AccountStep implements OnboardingStepView {
    /**
     * 这一页列出的三家。
     *
     * <p>只是「先给三个最常见的入口」，点击任意一行都会打开完整的账号管理器（那里有六家）。
     * 状态条判的是 {@link OnboardingUi#ACCOUNT_PLATFORMS} 那整张表，不是这三行——
     * 玩家可以从管理器里登汽水或 Apple，那也算登录过。</p>
     */
    private static final MusicPlatform[] PLATFORMS = {
            MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU
    };

    /** 状态条与三行平台卡的高度／间距。绘制与命中测试都读这几个数。 */
    private static final double STATUS_H = 42;
    private static final double BASE_ROW_H = 52;
    private static final double ROW_GAP = 10;
    private static final double ROWS_TOP_GAP = 16;
    /** 平台卡右侧那枚对勾/箭头占据的宽度（图标 20 再留点边），文字不许压过去。 */
    private static final double ROW_CHEVRON_RESERVE = 30;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawHeader("ACCOUNT", "连接账号",
                "登录是可选的。我们会继续使用现有的本地账号系统。", x, y);

        boolean anyLogged = OnboardingUi.anyAccountLoggedIn();
        double statusY = statusY(y);
        // 状态条走同一张卡的壳：它也是「一块有边界的区域」，过去它的圆角比下面的平台卡小 2，
        // 摆在一起看着像两套控件。
        double[] status = OnboardingUi.card(x, statusY, width, STATUS_H,
                anyLogged ? OnboardingUi.CardState.SELECTED : OnboardingUi.CardState.IDLE,
                anyLogged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
        // 状态用一个盾形图标而不是一个色点：这一条说的是「账号到底通不通」，
        // 色点要凑近了看才知道是绿是灰，图标一眼就读得出来。
        OnboardingUi.drawIcon(anyLogged ? FoliaLucideIcons.SHIELD : FoliaLucideIcons.INFO,
                anyLogged ? PartyIcons.CHECK : PartyIcons.INFO,
                status[0] + 10, status[1] + status[3] * .5, 18,
                anyLogged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
        OnboardingUi.drawText(OnboardingUi.metric().labelBold(), anyLogged ? "已登录" : "未登录",
                status[0] + 28, status[1] + status[3] * .5 - OnboardingMetrics.lineHeight(
                        OnboardingUi.metric().labelBold()) * .5,
                anyLogged ? OnboardingUi.GREEN : OnboardingUi.textMuted());

        for (int i = 0; i < PLATFORMS.length; i++) {
            MusicPlatform platform = PLATFORMS[i];
            double rowY = rowY(y, i);
            boolean logged = NeteaseMusicService.isLoggedIn(platform);
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, rowY, width, rowHeight());
            int brand = platform.getBrandColor();
            OnboardingUi.CardState state = logged ? OnboardingUi.CardState.SELECTED
                    : (hover ? OnboardingUi.CardState.HOVER : OnboardingUi.CardState.IDLE);
            double[] content = OnboardingUi.card(x, rowY, width, rowHeight(), state, brand);

            double box = Math.min(28, content[3] * .64);
            double iconCenterY = content[1] + content[3] * .5;
            OnboardingUi.roundedRect(content[0], iconCenterY - box * .5, box, box, box * .32,
                    OnboardingUi.colorWithAlpha(brand, 85));
            OnboardingUi.drawPlatformIcon(platform, content[0] + box * .5, iconCenterY, box,
                    OnboardingUi.textPrimary());

            double textX = content[0] + box + 12;
            OnboardingUi.cardText(content, textX, platform.getDisplayName(),
                    logged ? NeteaseMusicService.getAccountName(platform) : "未登录 · 点击连接",
                    ROW_CHEVRON_RESERVE, state);
            // 右侧那枚状态图标：登陆过是勾、没登录是箭头。它是「点进去」的方向指示，
            // 所以即使在选中态也画，位置固定在内容区右端。
            OnboardingUi.drawIcon(logged ? FoliaLucideIcons.CHECK : FoliaLucideIcons.CHEVRON_RIGHT,
                    logged ? PartyIcons.CHECK : PartyIcons.CHEVRON_RIGHT,
                    content[0] + content[2] - 8, iconCenterY, 18,
                    logged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
        }
    }

    /** 这一页的第一块内容（那条登录状态）落在六页共用的那根线上。 */
    static double statusY(double y) {
        return y + OnboardingUi.metric().bodyOffset;
    }

    static double statusHeight() {
        return STATUS_H;
    }

    /** 第 i 行平台卡的顶边。绘制与 {@link #mouseClicked} 共用这一个式子。 */
    static double rowY(double y, int index) {
        return statusY(y) + STATUS_H + ROWS_TOP_GAP + index * (rowHeight() + ROW_GAP);
    }

    /**
     * 一行平台卡的高度。
     *
     * <p>跟着排版档走：大屏每行更高（读起来松、也把纵向那半屏用起来），
     * 极小窗口压到 40 才塞得下三行。绘制与命中测试都调这一个方法。</p>
     */
    static double rowHeight() {
        return Math.max(40, BASE_ROW_H + (OnboardingUi.metric().cardHeight - 56) * .55);
    }

    static int rowCount() {
        return PLATFORMS.length;
    }

    /** 这一页要占多高（从内容带顶算起），给版式测试和「放不下先收装饰」用。 */
    static double contentHeight() {
        return OnboardingUi.metric().bodyOffset + STATUS_H + ROWS_TOP_GAP
                + PLATFORMS.length * rowHeight() + (PLATFORMS.length - 1) * ROW_GAP;
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        for (int i = 0; i < PLATFORMS.length; i++) {
            if (OnboardingUi.inside(mouseX, mouseY, x, rowY(y, i), width, rowHeight())) {
                controller.openAccountManager();
                return true;
            }
        }
        return false;
    }
}
