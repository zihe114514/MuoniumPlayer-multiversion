package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
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
        OnboardingUi.roundedRect(x, statusY, width, STATUS_H, OnboardingUi.CARD_RADIUS - 2,
                OnboardingUi.surfaceHover());
        OnboardingUi.roundedOutline(x, statusY, width, STATUS_H, OnboardingUi.CARD_RADIUS - 2, 1,
                OnboardingUi.awtColorWithAlpha(anyLogged ? OnboardingUi.GREEN : OnboardingUi.hairline(), 175));
        // 状态用一个盾形图标而不是一个色点：这一条说的是「账号到底通不通」，
        // 色点要凑近了看才知道是绿是灰，图标一眼就读得出来。
        OnboardingUi.drawIcon(anyLogged ? FoliaLucideIcons.SHIELD : FoliaLucideIcons.INFO,
                anyLogged ? PartyIcons.CHECK : PartyIcons.INFO,
                x + 24, statusY + STATUS_H * .5, 18,
                anyLogged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
        OnboardingUi.drawText(OnboardingUi.metric().labelBold(), anyLogged ? "已登录" : "未登录",
                x + 42, statusY + 14, anyLogged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
        // 后半句的起点按前一句的实测宽度算，不再写死 +52 —— 「未登录」和「已登录」一样宽是巧合。
        double afterStatus = x + 42 + OnboardingUi.textWidth(OnboardingUi.metric().labelBold(),
                anyLogged ? "已登录" : "未登录") + 10;
        OnboardingUi.drawTextReserving(OnboardingUi.metric().bodyFont(),
                anyLogged ? "账号状态会实时更新" : "可以稍后在主界面登录",
                afterStatus, statusY + 14, x + width - 12, 0, OnboardingUi.textMuted());

        for (int i = 0; i < PLATFORMS.length; i++) {
            MusicPlatform platform = PLATFORMS[i];
            double rowY = rowY(y, i);
            boolean logged = NeteaseMusicService.isLoggedIn(platform);
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, rowY, width, rowHeight());
            OnboardingUi.roundedRect(x, rowY, width, rowHeight(), OnboardingUi.CARD_RADIUS,
                    hover ? OnboardingUi.surfaceHover() : OnboardingUi.surface());
            OnboardingUi.roundedOutline(x, rowY, width, rowHeight(), OnboardingUi.CARD_RADIUS,
                    logged ? 1.4 : 1,
                    OnboardingUi.awtColorWithAlpha(platform.getBrandColor(), logged ? 210 : 105));
            double box = 28;
            OnboardingUi.roundedRect(x + 13, rowY + (rowHeight() - box) * .5, box, box, box * .32,
                    OnboardingUi.colorWithAlpha(platform.getBrandColor(), 85));
            OnboardingUi.drawPlatformIcon(platform, x + 13 + box * .5, rowY + rowHeight() * .5, box,
                    OnboardingUi.textPrimary());
            OnboardingUi.drawText(OnboardingUi.metric().labelBold(),
                    OnboardingUi.ellipsize(OnboardingUi.metric().labelBold(), platform.getDisplayName(),
                            width - 52 - ROW_CHEVRON_RESERVE),
                    x + 52, rowY + 12, OnboardingUi.textPrimary());
            // 账号名可以很长（网易云的昵称没有长度上限），右边那枚对勾/箭头是定死的。
            // 按「卡片宽 − 左侧 52 − 右侧图标那一截」截断，别让它一路画到箭头身上。
            OnboardingUi.drawTextReserving(OnboardingUi.metric().bodyFont(),
                    logged ? NeteaseMusicService.getAccountName(platform) : "未登录 · 点击连接",
                    x + 52, rowY + 30, x + width - 22, ROW_CHEVRON_RESERVE,
                    logged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
            OnboardingUi.drawIcon(logged ? FoliaLucideIcons.CHECK : FoliaLucideIcons.CHEVRON_RIGHT,
                    logged ? PartyIcons.CHECK : PartyIcons.CHEVRON_RIGHT,
                    x + width - 22, rowY + rowHeight() * .5, 20,
                    logged ? OnboardingUi.GREEN : OnboardingUi.BLUE);
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
