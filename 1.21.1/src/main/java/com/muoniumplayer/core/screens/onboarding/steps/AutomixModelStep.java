package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.FoliaModelManager;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** Optional 智能混音 model installer shown during first-run setup. */
public final class AutomixModelStep implements OnboardingStepView {
    private static final FoliaModelManager.Kind[] KINDS = {
            FoliaModelManager.Kind.BEAT_THIS,
            FoliaModelManager.Kind.HTDEMUCS,
            FoliaModelManager.Kind.RUNTIME
    };
    private static final String[] TITLES = {"Beat This!", "HTDemucs", "必要运行组件"};
    private static final String[] DESCRIPTIONS = {
            "分析节拍、速度与乐段位置 · 约 79 MB",
            "识别人声与伴奏结构 · 约 104 MB",
            "在本机执行模型推理 · 大小随系统变化"
    };

    private volatile FoliaModelManager.Kind activeKind;
    private volatile String message = "模型是可选组件；不下载仍可使用原版智能淡化。";
    private volatile boolean messageError;
    private volatile boolean installingAll;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawEyebrow("AUTOMIX · LOCAL AI", x, y + 5);
        OnboardingUi.drawText(FontManager.pf34bold, "让每一次切歌踩在节拍上", x, y + 31, OnboardingUi.WHITE);
        OnboardingUi.drawText(FontManager.pf12, "Folia 在你的电脑上分析节拍、人声和乐段；模型只保存在本机。",
                x, y + 69, OnboardingUi.SOFT);

        boolean allInstalled = allInstalled();
        for (int i = 0; i < KINDS.length; i++) {
            FoliaModelManager.Kind kind = KINDS[i];
            double rowY = y + 103 + i * 67;
            boolean installed = FoliaModelManager.isInstalled(kind);
            boolean active = activeKind == kind;
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, rowY, width, 55);

            OnboardingUi.roundedRect(x, rowY, width, 55, 15,
                    installed ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 22)
                            : (hover ? OnboardingUi.GLASS_2 : OnboardingUi.GLASS));
            OnboardingUi.roundedOutline(x, rowY, width, 55, 15, installed ? 1.4 : 1,
                    OnboardingUi.awtColorWithAlpha(installed ? OnboardingUi.GREEN : OnboardingUi.LINE,
                            installed ? 175 : 150));
            OnboardingUi.roundedRect(x + 13, rowY + 11, 34, 34, 11,
                    OnboardingUi.colorWithAlpha(installed ? OnboardingUi.GREEN : OnboardingUi.BRAND, 34));
            OnboardingUi.drawPartyIcon(installed ? PartyIcons.CHECK
                            : (active ? PartyIcons.HOURGLASS : PartyIcons.NOTE),
                    x + 30, rowY + 28, 24, installed ? OnboardingUi.GREEN : OnboardingUi.BRAND_2);
            OnboardingUi.drawText(FontManager.pf14bold, TITLES[i], x + 59, rowY + 10, OnboardingUi.WHITE);
            OnboardingUi.drawText(FontManager.pf12, active ? FoliaModelManager.statusLabel(kind) : DESCRIPTIONS[i],
                    x + 59, rowY + 31, active ? OnboardingUi.BRAND_2 : OnboardingUi.DIM);

            double buttonW = 92;
            double buttonX = x + width - buttonW - 12;
            OnboardingUi.roundedRect(buttonX, rowY + 12, buttonW, 31, 16,
                    installed ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 22)
                            : OnboardingUi.colorWithAlpha(OnboardingUi.BRAND, active ? 30 : (hover ? 205 : 165)));
            OnboardingUi.drawText(FontManager.pf12bold,
                    installed ? "已安装" : (active ? "下载中" : "下载"),
                    buttonX + (installed ? 27 : 30), rowY + 11 + 11, installed ? OnboardingUi.GREEN : OnboardingUi.WHITE);
        }

        double actionY = y + 314;
        boolean actionHover = OnboardingUi.inside(mouseX, mouseY, x, actionY, 188, 42);
        OnboardingUi.roundedGradient(x, actionY, 188, 42, 21,
                allInstalled ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 180) : OnboardingUi.BRAND,
                allInstalled ? OnboardingUi.colorWithAlpha(OnboardingUi.AQUA, 190) : OnboardingUi.BRAND_2);
        if (actionHover) {
            OnboardingUi.roundedOutline(x, actionY, 188, 42, 21, 1.3,
                    OnboardingUi.awtColorWithAlpha(OnboardingUi.WHITE, 150));
        }
        OnboardingUi.drawText(FontManager.pf14bold,
                allInstalled ? "已就绪 · 启用 Folia" : (installingAll ? "正在安装全部组件" : "下载全部并启用 Folia"),
                x + 19, actionY + 14, OnboardingUi.WHITE);

        OnboardingUi.roundedRect(x + 202, actionY, width - 202, 42, 13,
                messageError ? OnboardingUi.colorWithAlpha(OnboardingUi.BRAND, 55) : OnboardingUi.GLASS_2);
        OnboardingUi.drawPartyIcon(messageError ? PartyIcons.WARNING : PartyIcons.INFO,
                x + 221, actionY + 21, 20, messageError ? OnboardingUi.BRAND_2 : OnboardingUi.DIM);
        OnboardingUi.drawText(FontManager.pf12, compactMessage(message), x + 239, actionY + 14,
                messageError ? OnboardingUi.BRAND_2 : OnboardingUi.SOFT);

        OnboardingUi.drawText(FontManager.pf12,
                "稍后可在 HUD 设置 → 无缝切换 → 分析模型中安装、重试或更换目录。",
                x, y + 376, OnboardingUi.DIM);
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                double x, double y, double width, double height,
                                double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) return false;
        for (int i = 0; i < KINDS.length; i++) {
            double rowY = y + 103 + i * 67;
            if (OnboardingUi.inside(mouseX, mouseY, x, rowY, width, 55)) {
                installOne(KINDS[i], false);
                return true;
            }
        }
        if (OnboardingUi.inside(mouseX, mouseY, x, y + 314, 188, 42)) {
            if (allInstalled()) {
                AutomixSettings.setEngine(AutomixSettings.Engine.FOLIA);
                message = "智能混音 已启用。";
                messageError = false;
            } else if (!installingAll && activeKind == null) {
                installingAll = true;
                message = "正在准备模型下载，请保持网络连接。";
                messageError = false;
                installNextMissing();
            }
            return true;
        }
        return false;
    }

    private void installOne(FoliaModelManager.Kind kind, boolean chained) {
        if (kind == null || FoliaModelManager.isInstalled(kind)) {
            if (chained) installNextMissing();
            return;
        }
        if (activeKind != null) return;
        activeKind = kind;
        message = "下载后会校验文件大小与 SHA-256。";
        messageError = false;
        FoliaModelManager.downloadAsync(kind, (ok, detail) -> {
            activeKind = null;
            message = detail;
            messageError = !ok;
            if (chained && ok) installNextMissing();
            else if (chained) installingAll = false;
        });
    }

    private void installNextMissing() {
        for (FoliaModelManager.Kind kind : KINDS) {
            if (!FoliaModelManager.isInstalled(kind)) {
                installOne(kind, true);
                return;
            }
        }
        installingAll = false;
        AutomixSettings.setEngine(AutomixSettings.Engine.FOLIA);
        message = "全部组件安装完成，智能混音 已启用。";
        messageError = false;
    }

    private static String compactMessage(String value) {
        if (value == null) return "";
        return value.length() <= 42 ? value : value.substring(0, 41) + "…";
    }

    private static boolean allInstalled() {
        for (FoliaModelManager.Kind kind : KINDS) {
            if (!FoliaModelManager.isInstalled(kind)) return false;
        }
        return true;
    }
}
