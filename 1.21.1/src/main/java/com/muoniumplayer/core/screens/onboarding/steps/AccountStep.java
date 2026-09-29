package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 账号登录页；它打开既有的账号管理器覆盖层。 */
public final class AccountStep implements OnboardingStepView {
    private static final MusicPlatform[] PLATFORMS = {
            MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU
    };

    /** 状态条与三行平台卡的高度／间距。绘制与命中测试都读这几个数。 */
    private static final double STATUS_H = 42;
    private static final double ROW_H = 52;
    private static final double ROW_GAP = 10;
    private static final double ROWS_TOP_GAP = 16;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawHeader("ACCOUNT", "连接账号",
                "登录是可选的。我们会继续使用现有的本地账号系统。", x, y);

        boolean anyLogged = false;
        for (MusicPlatform platform : PLATFORMS) {
            anyLogged |= NeteaseMusicService.isLoggedIn(platform);
        }
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
        OnboardingUi.drawText(FontManager.pf12bold, anyLogged ? "已登录" : "未登录",
                x + 42, statusY + 14, anyLogged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
        OnboardingUi.drawText(FontManager.pf12,
                anyLogged ? "账号状态会实时更新" : "可以稍后在主界面登录",
                x + 42 + 52, statusY + 14, OnboardingUi.textMuted());

        for (int i = 0; i < PLATFORMS.length; i++) {
            MusicPlatform platform = PLATFORMS[i];
            double rowY = rowY(y, i);
            boolean logged = NeteaseMusicService.isLoggedIn(platform);
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, rowY, width, ROW_H);
            OnboardingUi.roundedRect(x, rowY, width, ROW_H, OnboardingUi.CARD_RADIUS,
                    hover ? OnboardingUi.surfaceHover() : OnboardingUi.surface());
            OnboardingUi.roundedOutline(x, rowY, width, ROW_H, OnboardingUi.CARD_RADIUS,
                    logged ? 1.4 : 1,
                    OnboardingUi.awtColorWithAlpha(platform.getBrandColor(), logged ? 210 : 105));
            double box = 28;
            OnboardingUi.roundedRect(x + 13, rowY + (ROW_H - box) * .5, box, box, box * .32,
                    OnboardingUi.colorWithAlpha(platform.getBrandColor(), 85));
            OnboardingUi.drawPlatformIcon(platform, x + 13 + box * .5, rowY + ROW_H * .5, box,
                    OnboardingUi.textPrimary());
            OnboardingUi.drawText(FontManager.pf14bold, platform.getDisplayName(),
                    x + 52, rowY + 12, OnboardingUi.textPrimary());
            OnboardingUi.drawText(FontManager.pf12,
                    logged ? NeteaseMusicService.getAccountName(platform) : "未登录 · 点击连接",
                    x + 52, rowY + 30, logged ? OnboardingUi.GREEN : OnboardingUi.textMuted());
            OnboardingUi.drawIcon(logged ? FoliaLucideIcons.CHECK : FoliaLucideIcons.CHEVRON_RIGHT,
                    logged ? PartyIcons.CHECK : PartyIcons.CHEVRON_RIGHT,
                    x + width - 22, rowY + ROW_H * .5, 20,
                    logged ? OnboardingUi.GREEN : OnboardingUi.BLUE);
        }
    }

    /** 这一页的第一块内容（那条登录状态）落在六页共用的那根线上。 */
    static double statusY(double y) {
        return y + OnboardingUi.BODY_OFFSET;
    }

    static double statusHeight() {
        return STATUS_H;
    }

    /** 第 i 行平台卡的顶边。绘制与 {@link #mouseClicked} 共用这一个式子。 */
    static double rowY(double y, int index) {
        return statusY(y) + STATUS_H + ROWS_TOP_GAP + index * (ROW_H + ROW_GAP);
    }

    static double rowHeight() {
        return ROW_H;
    }

    static int rowCount() {
        return PLATFORMS.length;
    }

    /** 这一页要占多高（从内容带顶算起），给版式测试和「放不下先收装饰」用。 */
    static double contentHeight() {
        return OnboardingUi.BODY_OFFSET + STATUS_H + ROWS_TOP_GAP
                + PLATFORMS.length * ROW_H + (PLATFORMS.length - 1) * ROW_GAP;
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        for (int i = 0; i < PLATFORMS.length; i++) {
            if (OnboardingUi.inside(mouseX, mouseY, x, rowY(y, i), width, ROW_H)) {
                controller.openAccountManager();
                return true;
            }
        }
        return false;
    }
}
