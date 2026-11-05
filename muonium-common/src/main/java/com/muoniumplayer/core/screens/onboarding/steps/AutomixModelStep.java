package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.FoliaModelManager;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingMetrics;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.party.ui.PartyIcons;

/** 首次运行设置期间显示的可选的智能混音模型安装器。 */
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

    private static final double BASE_ROW_H = 56;
    private static final double ROW_GAP = 11;
    private static final double CTA_W = 188;
    private static final double CTA_H = 42;
    private static final double CTA_GAP = 16;
    /** 主按钮与右边状态框之间的间距。 */
    private static final double NOTE_GAP = 14;
    /** 状态框的最小宽度：再窄就只剩一枚图标，那不如不画。 */
    private static final double NOTE_MIN_W = 96;

    /** 沉浸感那两张卡。 */
    private static final String[] IMMERSION_TITLES = {"场景声学", "环境选曲"};
    private static final String[] IMMERSION_NOTES = {
            "水下、室内、雨天各有自己的混响与闷响 · 默认关闭",
            "按周围环境的心情自动换歌（洞穴、森林、夜晚）· 默认关闭"
    };

    private volatile FoliaModelManager.Kind activeKind;
    private volatile String message = "模型是可选组件；不下载仍可使用原版智能淡化。";
    private volatile boolean messageError;
    private volatile boolean installingAll;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawHeader("AUTOMIX · IMMERSION", "让音乐跟着世界一起变",
                "Folia 在本机分析节拍与乐段；声学与环境选曲决定音乐要不要回应你所处的环境。", x, y);

        // 这一页有两组彼此独立的东西：三张模型卡（要下载、要点）和两张沉浸感开关卡。
        // 竖着排下来在常规档要多占 74 逻辑像素，主按钮会被挤出内容带 —— 而 854×480 正是
        // 真机最常用的一档。所以分成左右两栏：左栏模型（窄一点，它本来也只要一行字 + 一个按钮），
        // 右栏两张开关卡。窄到排不开两栏时（compact）才退回竖排，那时两张开关卡会被裁掉下半截，
        // 由内容带下沿那道渐隐交代 —— 但主按钮永远在第一屏。
        boolean twoColumns = OnboardingUi.metric().cardColumns() > 1;
        if (!twoColumns) {
            renderStacked(controller, x, y, width, height, mouseX, mouseY);
            return;
        }

        double gap = OnboardingUi.metric().cardGap * 1.5;
        double leftW = Math.max(0, (width - gap) * .62);
        double rightW = width - gap - leftW;
        renderModels(controller, x, y, leftW, height, mouseX, mouseY);
        renderImmersion(controller, x + leftW + gap, immersionY(y), rightW, mouseX, mouseY);
        renderActionBar(controller, x, y, width, height, mouseX, mouseY);
    }

    /** 紧凑档：竖着排，卡片都会被裁一点，但顺序不变。 */
    private void renderStacked(OnboardingController controller,
                               double x, double y, double width, double height,
                               double mouseX, double mouseY) {
        renderModels(controller, x, y, width, 0, mouseX, mouseY);
        // 竖排时沉浸感排在模型列<b>之后</b>：起点是模型列下沿再加一道栏间距。
        // （两栏时它才与模型卡共用顶线，见 render 里那条。）
        renderImmersion(controller, x, rowsBottom(y) + OnboardingUi.metric().cardGap,
                width, mouseX, mouseY);
        renderActionBar(controller, x, y, width, height, mouseX, mouseY);
    }

    /** 三张模型卡。 */
    private void renderModels(OnboardingController controller,
                              double x, double y, double width, double height,
                              double mouseX, double mouseY) {
        for (int i = 0; i < KINDS.length; i++) {
            FoliaModelManager.Kind kind = KINDS[i];
            double rowY = rowY(y, i);
            boolean installed = FoliaModelManager.isInstalled(kind);
            boolean active = activeKind == kind;
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, rowY, width, rowHeight());
            double buttonW = Math.min(92, Math.max(48, width * .3));

            // 外壳统一走 OnboardingUi.card：六页的卡从此是同一副长相，只有代表色不同。
            int accent = installed ? OnboardingUi.GREEN : OnboardingUi.BRAND_2;
            OnboardingUi.CardState state = installed ? OnboardingUi.CardState.SELECTED
                    : (hover ? OnboardingUi.CardState.HOVER : OnboardingUi.CardState.IDLE);
            double[] content = OnboardingUi.card(x, rowY, width, rowHeight(), state, accent);
            double textX = OnboardingUi.cardIcon(
                    installed ? FoliaLucideIcons.CHECK : LUCIDE[i],
                    installed ? PartyIcons.CHECK : FALLBACK[i],
                    content, Math.min(32, content[3] * .6), accent, state);
            OnboardingUi.cardText(content, textX, TITLES[i],
                    active ? FoliaModelManager.statusLabel(kind) : DESCRIPTIONS[i],
                    buttonW + 10, state);

            // 下载按钮压在卡右端，垂直居中。
            double buttonX = content[0] + content[2] - buttonW;
            double buttonH = Math.min(31, content[3]);
            double buttonY = content[1] + (content[3] - buttonH) * .5;
            OnboardingUi.roundedRect(buttonX, buttonY, buttonW, buttonH, buttonH * .5,
                    installed ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 22)
                            : OnboardingUi.colorWithAlpha(OnboardingUi.BRAND,
                                    active ? 30 : (hover ? 205 : 165)));
            OnboardingUi.drawCentered(OnboardingUi.metric().labelBold(),
                    installed ? "已装" : (active ? "下载中" : "下载"),
                    buttonX + buttonW * .5, buttonY + (buttonH - OnboardingMetrics.lineHeight(
                            OnboardingUi.metric().labelBold())) * .5,
                    installed ? OnboardingUi.GREEN : OnboardingUi.textPrimary());
        }
    }

    /**
     * 两张沉浸感开关卡：场景声学、环境选曲。
     *
     * <p>两张都是「总开关」粒度，不做分项（水下/室内/降水）——引导要的是「你要不要这个方向」，
     * 细项留给 HUD 设置。分项全默认开着，所以总开关一开就是完整效果。</p>
     */
    private void renderImmersion(OnboardingController controller,
                                 double x, double top, double width,
                                 double mouseX, double mouseY) {
        double cardH = immersionCardHeight();
        double gap = OnboardingUi.metric().cardGap;
        for (int i = 0; i < 2; i++) {
            double cardY = top + i * (cardH + gap);
            boolean on = i == 0 ? controller.acousticsEnabled() : controller.ambientEnabled();
            boolean hover = OnboardingUi.inside(mouseX, mouseY, x, cardY, width, cardH);
            int accent = i == 0 ? OnboardingUi.AQUA : OnboardingUi.VIOLET;
            OnboardingUi.CardState state = on ? OnboardingUi.CardState.SELECTED
                    : (hover ? OnboardingUi.CardState.HOVER : OnboardingUi.CardState.IDLE);
            double[] content = OnboardingUi.card(x, cardY, width, cardH, state, accent);
            double textX = OnboardingUi.cardIcon(
                    i == 0 ? FoliaLucideIcons.RUNTIME : FoliaLucideIcons.SPARKLE,
                    i == 0 ? PartyIcons.FRAME : PartyIcons.NOTE,
                    content, Math.min(28, content[3] * .6), accent, state);
            double switchW = Math.min(20, content[3] * .62) * 1.75;
            OnboardingUi.cardText(content, textX, IMMERSION_TITLES[i],
                    IMMERSION_NOTES[i], switchW + 10, state);
            OnboardingUi.cardSwitch(content, on, accent);
        }
    }

    /** 主行动条：按钮 + 右边状态框。 */
    private void renderActionBar(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY) {
        double actionY = ctaY(y, height);
        boolean allInstalled = allInstalled();
        double ctaW = ctaWidth(width);

        // 按钮被钉到底部时，卡片流会从它背后穿过去（427×240、640×360@70% 那两档）。
        // 先铺一块和面板同色的底板把背面盖住，再接缝上方一道渐隐——
        // 否则会出现「半张模型卡从按钮底下长出来」这种一眼就看出坏了的样子。
        if (actionY < contentBottom(y)) {
            double barTop = actionY - ACTION_BAR_PAD;
            double barBottom = y + height;
            OnboardingUi.roundedRect(x - 2, barTop, width + 4, barBottom - barTop,
                    OnboardingUi.CARD_RADIUS * .6f,
                    OnboardingUi.themed(com.muoniumplayer.core.screens.ncm.NCMScreen.ColorType
                            .GENERIC_BACKGROUND, .96f));
            OnboardingUi.verticalFade(x - 2, barTop - 16, width + 4, 16,
                    OnboardingUi.colorWithAlpha(OnboardingUi.BACKDROP, 0),
                    OnboardingUi.colorWithAlpha(OnboardingUi.BACKDROP, 210));
        }

        boolean actionHover = OnboardingUi.inside(mouseX, mouseY, x, actionY, ctaW, CTA_H);
        OnboardingUi.roundedGradient(x, actionY, ctaW, CTA_H, CTA_H * .5,
                allInstalled ? OnboardingUi.colorWithAlpha(OnboardingUi.GREEN, 180) : OnboardingUi.BRAND,
                allInstalled ? OnboardingUi.colorWithAlpha(OnboardingUi.AQUA, 190) : OnboardingUi.BRAND_2);
        if (actionHover) {
            OnboardingUi.roundedOutline(x, actionY, ctaW, CTA_H, CTA_H * .5, 1.3,
                    OnboardingUi.awtColorWithAlpha(OnboardingUi.textPrimary(), 150));
        }
        // 主行动按钮上带一枚图标：这一页只有这一个「大动作」，光靠一句字在七种字号里不够跳。
        OnboardingUi.drawIcon(allInstalled ? FoliaLucideIcons.CHECK : FoliaLucideIcons.AUTOMIX,
                allInstalled ? PartyIcons.CHECK : PartyIcons.SYNC,
                x + 22, actionY + CTA_H * .5, 17, OnboardingUi.textPrimary());
        // 按钮文案按按钮自己那一截量，放不下就改用短句（不是截断成「下载全部并启…」）。
        String cta = allInstalled ? "已就绪 · 启用 Folia"
                : (installingAll ? "正在安装全部组件" : "下载全部并启用 Folia");
        double ctaTextRight = x + ctaW - 12;
        if (!OnboardingUi.fits(OnboardingUi.metric().labelBold(), cta, ctaTextRight - (x + 38))) {
            cta = allInstalled ? "已就绪" : (installingAll ? "安装中" : "下载全部");
        }
        OnboardingUi.drawTextReserving(OnboardingUi.metric().labelBold(), cta,
                x + 38, actionY + 13, ctaTextRight, 0, OnboardingUi.textPrimary());

        double noteX = x + ctaW + NOTE_GAP;
        double noteW = Math.max(0, x + width - noteX);
        // 状态框窄到放不下文字时整块不画：一枚孤零零的图标说明不了任何事。
        if (noteW >= NOTE_MIN_W) {
            OnboardingUi.roundedRect(noteX, actionY, noteW, CTA_H, OnboardingUi.CARD_RADIUS - 2,
                    messageError ? OnboardingUi.colorWithAlpha(OnboardingUi.BRAND, 55) : OnboardingUi.surfaceHover());
            OnboardingUi.drawIcon(messageError ? FoliaLucideIcons.WARNING : FoliaLucideIcons.INFO,
                    messageError ? PartyIcons.WARNING : PartyIcons.INFO,
                    noteX + 19, actionY + CTA_H * .5, 18,
                    messageError ? OnboardingUi.BRAND_2 : OnboardingUi.textMuted());
            // 按框宽截断，不再固定切 42 个字符 —— 那个数在窄框上仍会溢出、在宽框上又白截。
            OnboardingUi.drawText(OnboardingUi.metric().bodyFont(),
                    OnboardingUi.ellipsize(OnboardingUi.metric().bodyFont(), message, noteW - 52),
                    noteX + 37, actionY + 14,
                    messageError ? OnboardingUi.BRAND_2 : OnboardingUi.textSecondary());
        }
    }

    /** 沉浸感每张卡的高度。跟排版档走，但比模型行矮一点（它们没有按钮）。 */
    static double immersionCardHeight() {
        return Math.max(46, OnboardingUi.metric().cardHeight - 4);
    }

    /** 沉浸感两张卡的起点：与模型卡<b>同一条顶线</b>（两栏排版时它们是并排的两列）。 */
    static double immersionY(double y) {
        return rowY(y, 0);
    }

    /** 沉浸感那两张卡占的总高（含中间那道缝）。 */
    static double immersionHeight() {
        return immersionCardHeight() * 2 + OnboardingUi.metric().cardGap;
    }

    /**
     * 这一页最后一行的下沿。
     *
     * <p>两栏排版时取两列里<b>更高的那一列</b>：模型列三张卡、沉浸感列两张卡，谁长听谁的。
     * 竖排（紧凑档）时两列首尾相接，所以是加起来的。</p>
     */
    static double contentBottom(double y) {
        double models = rowsBottom(y);
        double immersion = immersionY(y) + immersionHeight();
        boolean twoColumns = OnboardingUi.metric().cardColumns() > 1;
        if (twoColumns) {
            return Math.max(models, immersion);
        }
        // 竖排：沉浸感排在模型行之后，中间留一道栏间距。
        return models + OnboardingUi.metric().cardGap + immersionHeight();
    }

    static double rowY(double y, int index) {
        return y + OnboardingUi.metric().bodyOffset + index * (rowHeight() + ROW_GAP);
    }

    static double rowsBottom(double y) {
        return rowY(y, KINDS.length - 1) + rowHeight();
    }

    /** 模型行高度跟着排版档走，绘制与命中测试读同一份。 */
    static double rowHeight() {
        return Math.max(44, BASE_ROW_H + (OnboardingUi.metric().cardHeight - 56));
    }

    static int rowCount() {
        return KINDS.length;
    }

    /** 整页要占多高：三张模型卡 + 两张沉浸感卡 + 主行动条。 */
    static double contentHeight() {
        return contentBottom(0) + CTA_GAP + CTA_H;
    }

    /** 主行动按钮的宽度，随内容带收放。绘制与命中测试读同一份。 */
    static double ctaWidth(double width) {
        return Math.min(CTA_W, Math.max(96, width * .46));
    }

    /**
     * 主行动条的 y（相对页顶）。
     *
     * <p>钉在内容带<b>底部</b>，而不是跟在卡片流后面：这一页是全引导最高的一页
     * （三张模型卡 + 两张沉浸感卡 + 主按钮），在 427×240、640×360@70% 这类又宽又矮的
     * 档位上，卡片流会超到内容带外面去。主按钮是这一页唯一必须点得到的东西，
     * 所以它反着算——从带子底部往上留 {@link #CTA_BOTTOM_GAP}，卡片流超出的部分由
     * {@code clipStep} 裁掉（裁卡片只是「窗口太小」，丢掉主按钮才是「这页没法完成」）。</p>
     */
    static double ctaY(double y, double height) {
        double fromBottom = y + height - CTA_BOTTOM_GAP - CTA_H;
        // 卡片流本来就没那么长时（大屏），按钮跟着卡片流走，不悬在屏幕最底下。
        double afterContent = contentBottom(y) + CTA_GAP;
        return Math.min(fromBottom, afterContent);
    }

    /** 主按钮距内容带下沿的间距。 */
    private static final double CTA_BOTTOM_GAP = 10;
    /** 主按钮被钉到底部时，那块底板往上多留一点，免得按钮贴着卡片边缘。 */
    private static final double ACTION_BAR_PAD = 8;

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                double x, double y, double width, double height,
                                double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) return false;
        // 两栏时模型卡只占左栏，沉浸感卡在右栏；命中测试必须用与绘制的同一套栏宽，
        // 否则左右两栏会互相吃到对方的点击（点右边那张开关卡，落在左边模型卡的范围里）。
        boolean twoColumns = OnboardingUi.metric().cardColumns() > 1;
        double gap = OnboardingUi.metric().cardGap * 1.5;
        double leftW = twoColumns ? Math.max(0, (width - gap) * .62) : width;
        double rightX = twoColumns ? x + leftW + gap : x;
        double rightW = twoColumns ? width - gap - leftW : width;
        double rightTop = twoColumns ? y : immersionY(y);

        for (int i = 0; i < KINDS.length; i++) {
            if (OnboardingUi.inside(mouseX, mouseY, x, rowY(y, i), leftW, rowHeight())) {
                installOne(KINDS[i], false);
                return true;
            }
        }
        // 两张沉浸感开关卡：点整张卡都能翻，不必非得点中那枚胶囊。
        double immersionH = immersionCardHeight();
        double immersionTop = twoColumns ? immersionY(y) : rightTop;
        for (int i = 0; i < 2; i++) {
            double cardY = immersionTop + i * (immersionH + OnboardingUi.metric().cardGap);
            if (OnboardingUi.inside(mouseX, mouseY, rightX, cardY, rightW, immersionH)) {
                if (i == 0) {
                    controller.setAcousticsEnabled(!controller.acousticsEnabled());
                } else {
                    controller.setAmbientEnabled(!controller.ambientEnabled());
                }
                return true;
            }
        }
        if (OnboardingUi.inside(mouseX, mouseY, x, ctaY(y, height), ctaWidth(width), CTA_H)) {
            if (allInstalled()) {
                AutomixSettings.setEngine(AutomixSettings.Engine.FOLIA);
                message = "智能混音已启用。";
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
        message = "全部组件安装完成，智能混音已启用。";
        messageError = false;
    }

    private static boolean allInstalled() {
        for (FoliaModelManager.Kind kind : KINDS) {
            if (!FoliaModelManager.isInstalled(kind)) return false;
        }
        return true;
    }
}
