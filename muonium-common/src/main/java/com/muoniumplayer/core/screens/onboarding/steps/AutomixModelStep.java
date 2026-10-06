package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.FoliaModelManager;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 首次运行设置期间显示的可选 智能混音 模型安装器。 */
public final class AutomixModelStep implements OnboardingStepView {
    private static final FoliaModelManager.Kind[] KINDS = {
            FoliaModelManager.Kind.BEAT_THIS,
            FoliaModelManager.Kind.HTDEMUCS,
            FoliaModelManager.runtimeKind()
    };
    private static final String[] TITLES = {"Beat This!", "HTDemucs", "必要运行组件"};
    private static final String[] DESCRIPTIONS = {
            "分析节拍、速度与乐段位置 · 约 79 MB",
            "识别人声与伴奏结构 · 约 104 MB",
            "在本机执行模型推理 · 大小随系统变化"
    };

    static {
        // 安卓那行是 ONNX 运行时（约 12 MB），桌面是 Python 运行组件。
        if (KINDS[2] == FoliaModelManager.Kind.ONNX_RUNTIME) {
            DESCRIPTIONS[2] = "在本机执行模型推理 · 约 12 MB";
        }
    }
    /**
     * 三行各自那枚图标：三件事不是一回事，用同一枚「音符」等于告诉玩家「这三行差不多」。
     * Beat This! 是节拍（一条心跳波形）、HTDemucs 是把人声从伴奏里拆出来（一根魔杖）、
     * 必要运行组件是本机推理（一块芯片）。矢量画不出来时退回 Material 那几枚同类的。
     */
    private static final String[] LUCIDE = {
            FoliaLucideIcons.BEAT, FoliaLucideIcons.SEPARATION, FoliaLucideIcons.RUNTIME
    };
    private static final String[] FALLBACK = {
            PartyIcons.NOTE, PartyIcons.SYNC, PartyIcons.FRAME
    };

    private static final double ROW_H = 56;
    private static final double ROW_GAP = 11;
    private static final double CTA_W = 188;
    private static final double CTA_H = 42;
    private static final double CTA_GAP = 16;

    private volatile FoliaModelManager.Kind activeKind;
    private volatile String message = "模型是可选组件；不下载仍可使用原版智能淡化。";
    private volatile boolean messageError;
    private volatile boolean installingAll;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawHeader("AUTOMIX · LOCAL AI", "让每一次切歌踩在节拍上",
                "Folia 在你的电脑上分析节拍、人声和乐段；模型只保存在本机。", x, y);

        boolean allInstalled = allInstalled();
        for (int i = 0; i < KINDS.length; i++) {
            FoliaModelManager.Kind kind = KINDS[i];
            double rowY = rowY(y, i);
            boolean installed = FoliaModelManager.isInstalled(kind);
            boolean active = activeKind == kind;
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, rowY, width, ROW_H);

            OnboardingUi.roundedRect(x, rowY, width, ROW_H, OnboardingUi.CARD_RADIUS,
                    installed ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 22)
                            : (hover ? OnboardingUi.surfaceHover() : OnboardingUi.surface()));
            OnboardingUi.roundedOutline(x, rowY, width, ROW_H, OnboardingUi.CARD_RADIUS,
                    installed ? 1.4 : 1,
                    OnboardingUi.awtColorWithAlpha(installed ? OnboardingUi.GREEN : OnboardingUi.hairline(),
                            installed ? 175 : 150));
            double box = 32;
            OnboardingUi.roundedRect(x + 12, rowY + (ROW_H - box) * .5, box, box, box * .3,
                    OnboardingUi.colorWithAlpha(installed ? OnboardingUi.GREEN : OnboardingUi.BRAND, 34));
            OnboardingUi.drawIcon(installed ? FoliaLucideIcons.CHECK : LUCIDE[i],
                    installed ? PartyIcons.CHECK : FALLBACK[i],
                    x + 12 + box * .5, rowY + ROW_H * .5, box * .62f,
                    installed ? OnboardingUi.GREEN : OnboardingUi.BRAND_2);
            OnboardingUi.drawText(FontManager.pf14bold, TITLES[i], x + 56, rowY + 12,
                    OnboardingUi.textPrimary());
            OnboardingUi.drawText(FontManager.pf12,
                    active ? FoliaModelManager.statusLabel(kind) : DESCRIPTIONS[i],
                    x + 56, rowY + 31, active ? OnboardingUi.BRAND_2 : OnboardingUi.textMuted());

            double buttonW = 92;
            double buttonX = x + width - buttonW - 12;
            OnboardingUi.roundedRect(buttonX, rowY + (ROW_H - 31) * .5, buttonW, 31, 16,
                    installed ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 22)
                            : OnboardingUi.colorWithAlpha(OnboardingUi.BRAND,
                                    active ? 30 : (hover ? 205 : 165)));
            OnboardingUi.drawCentered(FontManager.pf12bold,
                    installed ? "已安装" : (active ? "下载中" : "下载"),
                    buttonX + buttonW * .5, rowY + (ROW_H - 31) * .5 + 8,
                    installed ? OnboardingUi.GREEN : OnboardingUi.textPrimary());
        }

        double actionY = rowsBottom(y) + CTA_GAP;
        boolean actionHover = OnboardingUi.inside(mouseX, mouseY, x, actionY, CTA_W, CTA_H);
        OnboardingUi.roundedGradient(x, actionY, CTA_W, CTA_H, CTA_H * .5,
                allInstalled ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 180) : OnboardingUi.BRAND,
                allInstalled ? OnboardingUi.colorWithAlpha(OnboardingUi.AQUA, 190) : OnboardingUi.BRAND_2);
        if (actionHover) {
            OnboardingUi.roundedOutline(x, actionY, CTA_W, CTA_H, CTA_H * .5, 1.3,
                    OnboardingUi.awtColorWithAlpha(OnboardingUi.textPrimary(), 150));
        }
        // 主行动按钮上带一枚图标：这一页只有这一个「大动作」，光靠一句字在七种字号里不够跳。
        OnboardingUi.drawIcon(allInstalled ? FoliaLucideIcons.CHECK : FoliaLucideIcons.AUTOMIX,
                allInstalled ? PartyIcons.CHECK : PartyIcons.SYNC,
                x + 22, actionY + CTA_H * .5, 17, OnboardingUi.textPrimary());
        OnboardingUi.drawText(FontManager.pf14bold,
                allInstalled ? "已就绪 · 启用 Folia"
                        : (installingAll ? "正在安装全部组件" : "下载全部并启用 Folia"),
                x + 38, actionY + 13, OnboardingUi.textPrimary());

        double noteX = x + CTA_W + 14;
        OnboardingUi.roundedRect(noteX, actionY, width - CTA_W - 14, CTA_H, OnboardingUi.CARD_RADIUS - 2,
                messageError ? OnboardingUi.colorWithAlpha(OnboardingUi.BRAND, 55) : OnboardingUi.surfaceHover());
        OnboardingUi.drawIcon(messageError ? FoliaLucideIcons.WARNING : FoliaLucideIcons.INFO,
                messageError ? PartyIcons.WARNING : PartyIcons.INFO,
                noteX + 19, actionY + CTA_H * .5, 18,
                messageError ? OnboardingUi.BRAND_2 : OnboardingUi.textMuted());
        OnboardingUi.drawText(FontManager.pf12, compactMessage(message), noteX + 37, actionY + 14,
                messageError ? OnboardingUi.BRAND_2 : OnboardingUi.textSecondary());

        OnboardingUi.drawText(FontManager.pf12,
                "稍后可在 HUD 设置 → 无缝切换 → 分析模型中安装、重试或更换目录。",
                x, actionY + CTA_H + 16, OnboardingUi.textMuted());
    }

    static double rowY(double y, int index) {
        return y + OnboardingUi.BODY_OFFSET + index * (ROW_H + ROW_GAP);
    }

    static double rowsBottom(double y) {
        return rowY(y, KINDS.length - 1) + ROW_H;
    }

    static double rowHeight() {
        return ROW_H;
    }

    static int rowCount() {
        return KINDS.length;
    }

    /** 整页要占多高：三行 + 主行动条 + 底下那句提示。 */
    static double contentHeight() {
        return rowsBottom(0) + CTA_GAP + CTA_H + 16 + 14;
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                double x, double y, double width, double height,
                                double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) return false;
        for (int i = 0; i < KINDS.length; i++) {
            if (OnboardingUi.inside(mouseX, mouseY, x, rowY(y, i), width, ROW_H)) {
                installOne(KINDS[i], false);
                return true;
            }
        }
        if (OnboardingUi.inside(mouseX, mouseY, x, rowsBottom(y) + CTA_GAP, CTA_W, CTA_H)) {
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
