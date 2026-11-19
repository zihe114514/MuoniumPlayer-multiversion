package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.update.UpdateChecker;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.party.ui.PartyIcons;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 主菜单更新提示的 Skija 本体；宿主只转发尺寸、输入与关闭结果。
 *
 * <p>普通更新可无限暂缓；关键更新每个版本最多暂缓
 * {@link UpdateChecker#MAX_SKIP_COUNT} 次。只有按钮与 ESC 消费一次额度，遮罩只吞点击。
 * 额度用尽后只有下载主行动，打开浏览器或复制地址也不能收起提示。</p>
 */
public final class UpdateNoticeOverlay extends NCMPanel {

    private static final String UPDATE_HEADING = "新版本，准备就绪";
    private static final String FAILED_HEADING = "暂时连不上更新服务";
    private static final String DOWNLOAD = "前往下载";
    private static final String COPY_LINK = "复制下载链接";
    private static final String COPY_PAGE_LINK = "复制更新页地址";
    private static final String LATER = "先不更新";
    private static final String RETRY = "重试检查";
    private static final String OK = "我知道了";
    private static final String PARAGRAPH_BREAK = "\n";

    /** 只限制首屏的期望高度，不再截掉清单里的更新说明；其余内容可滚动。 */
    private static final int MAX_BODY_LINES = 8;
    private static final double SIDE_INSET = 24.0;
    private static final double CARD_MAX_WIDTH = 636.0;
    private static final double BODY_TOP = 148.0;
    private static final double BODY_PAD = 16.0;
    private static final double VERSION_HEIGHT = 108.0;
    private static final double NOTES_TOP = 178.0;
    private static final double POLICY_HEIGHT = 34.0;
    private static final double STATUS_HEIGHT = 38.0;
    private static final double LINK_HEIGHT = 30.0;
    private static final double FOOTER_HEIGHT = POLICY_HEIGHT + 8 + STATUS_HEIGHT + 12
            + NcmExpressive.BUTTON_HEIGHT + 6 + LINK_HEIGHT + 14;

    /** 提示类型与锁定状态在这一实例存在期间不变，重试得到新结论时由宿主重建。 */
    private final boolean networkFailure;
    private final boolean forcedUpdate;
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final SheetClock clock = new SheetClock();
    private double presentation;
    private boolean closing;
    private boolean resolved;
    private boolean retryPending;
    private String status;

    private Panel dialog;
    private Panel header;
    private ScrollPanel bodyViewport;
    private Panel bodyContent;
    private Panel policyPanel;
    private Panel statusPanel;
    private final List<LabelWidget> bodyLabels = new ArrayList<>();
    private final List<LinkSlot> linkSlots = new ArrayList<>();
    private String[] bodyLines = new String[0];
    private String[] policyLines = new String[0];
    private String[] statusLines = new String[0];
    private double wrappedForWidth = -1;
    private String wrappedSource;
    private boolean wrappedWithLoadedGlyphs;
    private CFontRenderer wrappedFont;
    private long wrappedFontRevision = -1;
    private double captionWidth = -1;
    private String captionPolicy;
    private String captionStatus;
    private CFontRenderer captionFont;
    private long captionFontRevision = -1;
    private boolean captionsWithLoadedGlyphs;

    private static final class LinkSlot {
        private final Panel hitArea;
        private final LabelWidget label;
        private final BooleanSupplier visible;

        private LinkSlot(Panel hitArea, LabelWidget label, BooleanSupplier visible) {
            this.hitArea = hitArea;
            this.label = label;
            this.visible = visible;
        }
    }

    public UpdateNoticeOverlay() {
        networkFailure = UpdateChecker.state() == UpdateChecker.STATE_CHECK_FAILED;
        forcedUpdate = !networkFailure && UpdateChecker.mustUpdate();
    }

    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public boolean shouldClose() {
        return clock.isGone();
    }

    /** 下载（未锁定时）与重试成功可以收起，不消费暂缓额度。 */
    public void cancel() {
        if (forcedUpdate || resolved) return;
        resolved = true;
        closing = true;
        clock.requestDismiss();
    }

    /** 包括退场期间重复 ESC 在内，一次收起请求最多扣一次额度。 */
    public void dismissForNow() {
        if (forcedUpdate || resolved) return;
        if (!networkFailure) UpdateChecker.noteSkippedOnce();
        cancel();
    }

    public void handleEscape() {
        dismissForNow();
    }

    public boolean blocksClosing() {
        return forcedUpdate;
    }

    /**
     * 独立宿主传入的滚轮量是 0，沿现有 compat 累加器读一次；未来宿主显式转发时不再读它。
     * 控件树不分发同一格滚轮多次，退场时只清掉输入，不让它留给回到的主菜单。
     */
    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        int wheel = dWheel == 0 ? Mouse.getDWheel() : dWheel;
        layout.render(this, mouseX, mouseY, wheel, (x, y, input) -> {
            super.renderWidget(x, y, 0);
            if (!closing && input != 0 && bodyViewport != null) bodyViewport.forwardWheel(input);
        });
    }

    @Override
    public void onMouseClickReceived(double mouseX, double mouseY, int button) {
        layout.dispatch(this, mouseX, mouseY, button, (x, y, input) -> super.onMouseClickReceived(x, y, input));
    }

    @Override
    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        layout.dispatch(this, mouseX, mouseY, dWheel, (x, y, input) -> super.onDWheelReceived(x, y, input));
    }

    @Override
    public void onInit() {
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setClickable(false).setColor(0x000000);
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .48));
        });
        setBeforeRenderCallback(() -> presentation = TransitionCurveCache.MOTION.at(clock.tick()));
        // 遮罩绝不收起、绝不记账，也不把点击传给下层主菜单。
        setOnClickCallback((x, y, button) -> true);

        dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(this::layoutDialog);
        RoundedRectWidget background = NcmExpressive.surface(dialog, 0, 0,
                dialog::getWidth, dialog::getHeight, NcmExpressive.DIALOG_RADIUS,
                () -> NcmExpressivePalette.live().surface());
        background.setGlassSheet(GlassLook.Surface.PLAYER);
        background.setBeforeRenderCallback(() -> {
            background.setMargin(0);
            background.setColor(NcmExpressivePalette.live().surface());
            background.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1));
        });

        header = new Panel();
        dialog.addChild(header);
        header.setClickable(false);
        NcmExpressive.header(header, networkFailure ? PartyIcons.CLOUD_OFF : PartyIcons.SYNC,
                () -> networkFailure ? "更新检查" : "MuoniumPlayer 更新",
                () -> networkFailure ? FAILED_HEADING : UPDATE_HEADING, null);

        bodyViewport = new ScrollPanel();
        dialog.addChild(bodyViewport);
        bodyViewport.setAlignment(ScrollPanel.Alignment.VERTICAL).setSpacing(0)
                .setScrollStrength(34).setIndicatorInsideViewport();
        bodyContent = new Panel();
        bodyViewport.addChild(bodyContent);
        bodyContent.setClickable(false);
        if (!networkFailure) createVersionCard();

        NcmExpressive.surface(bodyContent, () -> 0, () -> networkFailure ? 0 : notesTop(),
                bodyViewport::getWidth, () -> bodyBlockHeight() + BODY_PAD * 2,
                NcmExpressive.CARD_RADIUS, () -> NcmExpressivePalette.live().container());

        // 页脚不随正文滚动。两行反馈恒预留，不因复制/重试结果到达而移动按钮。
        policyPanel = new Panel();
        dialog.addChild(policyPanel);
        policyPanel.setClickable(false).setTooltip(this::buildPolicy);
        for (int index = 0; index < 2; index++) {
            final int line = index;
            NcmExpressive.label(policyPanel,
                    () -> line < policyLines.length ? policyLines[line] : "", NcmExpressive::captionFont,
                    () -> 0, () -> 8 + line * 17,
                    this::contentWidth, this::policyInk);
        }
        statusPanel = new Panel();
        dialog.addChild(statusPanel);
        statusPanel.setClickable(false);
        statusPanel.setTooltip(() -> status == null ? "" : status);
        NcmExpressive.surface(statusPanel, 0, 0, statusPanel::getWidth, statusPanel::getHeight, 12,
                this::statusSurface);
        NcmExpressive.icon(statusPanel, PartyIcons.INFO, NcmSheet.ICON_S,
                () -> 16, () -> STATUS_HEIGHT * .5, this::statusInk);
        for (int index = 0; index < 2; index++) {
            final int line = index;
            NcmExpressive.label(statusPanel,
                    () -> line < statusLines.length ? statusLines[line] : "", NcmExpressive::captionFont,
                    () -> 32, () -> 10 + line * 17, () -> Math.max(1, statusPanel.getWidth() - 44),
                    this::statusInk);
        }

        if (!forcedUpdate) {
            NcmExpressive.action(dialog, "", () -> networkFailure ? OK : LATER,
                    NcmExpressive.ActionStyle.TONAL, () -> SIDE_INSET, this::buttonsTop,
                    this::secondaryActionWidth, () -> NcmExpressive.BUTTON_HEIGHT, this::dismissForNow);
        }
        NcmExpressive.action(dialog, networkFailure ? PartyIcons.SYNC : PartyIcons.LINK,
                () -> networkFailure ? (retryPending ? "正在检查…" : RETRY) : DOWNLOAD,
                NcmExpressive.ActionStyle.PRIMARY,
                () -> dialog.getWidth() - SIDE_INSET - primaryActionWidth(), this::primaryActionTop,
                this::primaryActionWidth, () -> NcmExpressive.BUTTON_HEIGHT,
                () -> { if (networkFailure) beginRetry(); else openDownloadPage(); });

        if (networkFailure) {
            addLink(COPY_PAGE_LINK, () -> true, () -> copyToClipboard(
                    UpdateChecker.DOWNLOAD_PAGE, "更新页地址已复制到剪贴板："));
        } else {
            addLink(COPY_LINK, () -> true, () -> copyToClipboard(
                    UpdateChecker.downloadPage(), "地址已复制到剪贴板："));
        }
    }

    private void createVersionCard() {
        NcmExpressive.surface(bodyContent, 0, 0, bodyViewport::getWidth, this::versionHeight,
                NcmExpressive.CARD_RADIUS, () -> NcmExpressivePalette.live().container());
        NcmExpressive.label(bodyContent, () -> "当前版本", NcmExpressive::captionFont,
                () -> 20, () -> 25, this::versionColumnWidth,
                () -> NcmExpressivePalette.live().muted());
        LabelWidget current = NcmExpressive.label(bodyContent,
                () -> versionText(UpdateChecker.currentVersion()), NcmExpressive::titleFont,
                () -> 20, () -> 68, this::versionColumnWidth,
                () -> NcmExpressivePalette.live().containerInk());
        current.setTooltip(UpdateChecker::currentVersion);
        NcmExpressive.icon(bodyContent, () -> stackVersions() ? "" : PartyIcons.CHEVRON_RIGHT, NcmSheet.ICON_L,
                () -> bodyViewport.getWidth() * .5, () -> 62,
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(bodyContent, () -> UpdateChecker.isCritical() ? "关键更新" : "最新版本",
                NcmExpressive::captionFont, () -> stackVersions() ? 20 : bodyViewport.getWidth() * .5 + 30,
                () -> stackVersions() ? 117 : 25, this::versionColumnWidth,
                () -> NcmExpressivePalette.live().muted());
        LabelWidget latest = NcmExpressive.label(bodyContent,
                () -> versionText(UpdateChecker.latestVersion()), NcmExpressive::titleFont,
                () -> stackVersions() ? 20 : bodyViewport.getWidth() * .5 + 30,
                () -> stackVersions() ? 158 : 68, this::versionColumnWidth,
                () -> NcmExpressivePalette.live().containerInk());
        latest.setTooltip(UpdateChecker::latestVersion);
        NcmExpressive.label(bodyContent, () -> "当前渠道 · " + UpdateChecker.channel(),
                NcmExpressive::captionFont, () -> 2, () -> versionHeight() + 20,
                () -> Math.max(1, bodyViewport.getWidth() - 12), () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(bodyContent, () -> "更新说明", NcmExpressive::labelFont,
                () -> 2, () -> versionHeight() + 50, bodyViewport::getWidth, () -> NcmExpressivePalette.live().text());
    }

    private static String versionText(String version) {
        return version == null || version.isEmpty() ? "获取中…" : version;
    }

    private void addLink(String text, BooleanSupplier visible, Runnable action) {
        Panel hitArea = new Panel();
        dialog.addChild(hitArea);
        hitArea.setShouldOverrideMouseCursor(true);
        LabelWidget label = NcmExpressive.label(hitArea, () -> text, NcmExpressive::captionFont,
                () -> 14, () -> LINK_HEIGHT * .5,
                () -> Math.max(1, hitArea.getWidth() - 28), () -> {
                    NcmExpressivePalette palette = NcmExpressivePalette.live();
                    return NcmExpressivePalette.readableColor(
                            hitArea.isHovering() ? palette.primaryHover() : palette.primary(), palette.surface());
                });
        linkSlots.add(new LinkSlot(hitArea, label, visible));
        hitArea.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
    }

    private void layoutDialog() {
        pollRetry();
        double width = NcmExpressive.dialogWidth(this, CARD_MAX_WIDTH);
        wrapBody(Math.max(1, width - SIDE_INSET * 2 - BODY_PAD * 2 - 8));
        wrapCaptions(Math.max(1, width - SIDE_INSET * 2));
        double preferredBody = (networkFailure ? 0 : notesTop(Math.max(1, width - SIDE_INSET * 2))) + BODY_PAD * 2
                + Math.max(lineHeight(), Math.min(MAX_BODY_LINES, bodyLines.length) * lineHeight());
        double preferredFooter = footerHeight(Math.max(1, width - SIDE_INSET * 2));
        double height = NcmExpressive.dialogHeight(this,
                BODY_TOP + preferredBody + 12 + preferredFooter);
        dialog.setBounds(width, height);
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1 - presentation) * 12);
        dialog.setAlpha((float) presentation);

        // 极矮窗口先让出页眉，保住底部操作；正常尺寸保持探针的分类页眉与独立标题。
        header.setBounds(width, height);
        double headerHeight = NcmExpressive.headerHeight(header);
        boolean showHeader = height >= headerHeight + 8 + preferredFooter + 32;
        header.setHidden(!showHeader);
        header.setPosition(0, 0);
        double top = showHeader ? headerHeight + 8 : 8;
        bodyViewport.setBounds(contentWidth(), Math.max(0, footerTop() - top - 12));
        bodyViewport.setPosition(SIDE_INSET, top);
        // 行高在 ScrollPanel 排版前就定好，首帧与字形/原文重排都不会沿用上一帧的滚动范围。
        bodyContent.setBounds(contentWidth(), (networkFailure ? 0 : notesTop())
                + bodyBlockHeight() + BODY_PAD * 2);
        policyPanel.setBounds(contentWidth(), POLICY_HEIGHT);
        policyPanel.setPosition(SIDE_INSET, footerTop());
        boolean showPolicy = buttonsTop() >= footerTop() + POLICY_HEIGHT + 8;
        policyPanel.setHidden(!showPolicy);
        statusPanel.setBounds(contentWidth(), STATUS_HEIGHT);
        statusPanel.setPosition(SIDE_INSET, footerTop() + POLICY_HEIGHT + 8);
        statusPanel.setHidden(status == null || status.isEmpty()
                || buttonsTop() < statusPanel.getRelativeY() + STATUS_HEIGHT + 12);
        layoutLinks();
    }

    private void layoutLinks() {
        double left = SIDE_INSET;
        for (LinkSlot slot : linkSlots) {
            boolean visible = slot.visible.getAsBoolean();
            slot.hitArea.setHidden(!visible);
            if (!visible) {
                slot.hitArea.setBounds(0, 0);
                slot.label.setBounds(0, 0);
                continue;
            }
            // 每帧按真实 caption 字宽定命中区，不依赖上一帧 LabelWidget 的边界。
            CFontRenderer font = NcmExpressive.captionFont();
            double width = Math.min(contentWidth(), font == null ? 140 : font.getStringWidthD(slot.label.getLabel()) + 28);
            slot.hitArea.setBounds(width, LINK_HEIGHT);
            slot.hitArea.setPosition(Math.max(left, (dialog.getWidth() - width) * .5), linkTop());
            left += width + 12;
        }
    }

    private double contentWidth() {
        return Math.max(1, dialog.getWidth() - SIDE_INSET * 2);
    }

    private boolean stackVersions() {
        return bodyViewport.getWidth() < 360;
    }

    private double versionHeight() {
        return stackVersions() ? 198 : VERSION_HEIGHT;
    }

    private static double notesTop(double width) {
        return NOTES_TOP + (width < 360 ? 90 : 0);
    }

    private double notesTop() {
        return notesTop(bodyViewport.getWidth());
    }

    private double versionColumnWidth() {
        return Math.max(1, stackVersions() ? bodyViewport.getWidth() - 40 : bodyViewport.getWidth() * .5 - 50);
    }

    private double footerTop() {
        return Math.max(8, dialog.getHeight() - footerHeight(contentWidth()));
    }

    private double footerHeight(double width) {
        return FOOTER_HEIGHT + (stackActions(width) ? NcmExpressive.BUTTON_HEIGHT + 8 : 0);
    }

    private boolean stackActions(double width) {
        return !forcedUpdate && width < 320;
    }

    private double buttonsTop() {
        // 窄窗口主次按钮纵排，保持真实字级；极矮时先舍弃正文/反馈而不是盖在动作上。
        double extra = stackActions(contentWidth()) ? NcmExpressive.BUTTON_HEIGHT + 8 : 0;
        return Math.max(0, dialog.getHeight() - 14 - LINK_HEIGHT - 6
                - NcmExpressive.BUTTON_HEIGHT - extra);
    }

    private double primaryActionTop() {
        return buttonsTop() + (stackActions(contentWidth()) ? NcmExpressive.BUTTON_HEIGHT + 8 : 0);
    }

    private double linkTop() {
        return primaryActionTop() + NcmExpressive.BUTTON_HEIGHT + 6;
    }

    private double secondaryActionWidth() {
        return stackActions(contentWidth()) ? contentWidth()
                : Math.min(156, Math.max(0, (contentWidth() - 12) * .5));
    }

    private double primaryActionWidth() {
        return forcedUpdate || stackActions(contentWidth()) ? contentWidth()
                : Math.min(196, Math.max(0, contentWidth() - secondaryActionWidth() - 12));
    }

    private static double lineHeight() {
        return Math.max(20, FontManager.pf32 == null ? 20 : FontManager.pf32.getFontHeight() + 8);
    }

    private double bodyBlockHeight() {
        return Math.max(lineHeight(), bodyLines.length * lineHeight());
    }

    private int policyInk() {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        return !networkFailure && UpdateChecker.isCritical() ? palette.warning() : palette.muted();
    }

    private int statusSurface() {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        return NcmExpressivePalette.mix(palette.surface(), networkFailure ? palette.warning() : palette.primary(), .08);
    }

    private int statusInk() {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        return NcmExpressivePalette.readableColor(palette.muted(), statusSurface());
    }

    /** 版本、渠道与策略有独立层级；正文只放真实清单说明，显式段落分别折行。 */
    private String buildBody() {
        if (networkFailure) {
            return "已经连续 " + UpdateChecker.failureCount() + " 次启动都没能获取版本清单。"
                    + PARAGRAPH_BREAK + "通常是网络、代理或 DNS 的问题，检查一下再点「重试检查」喵。";
        }
        String notes = UpdateChecker.releaseNotes();
        return notes == null || notes.trim().isEmpty() ? "这个版本暂未提供更新说明。" : notes;
    }

    private String buildPolicy() {
        if (networkFailure) return "更新检查暂时失败，不会改变当前安装的版本。你可以稍后重试。";
        if (forcedUpdate) {
            return "关键更新 · 已先不更新 " + UpdateChecker.usedSkips()
                    + " 次，额度已用完。装好新版本再进游戏。";
        }
        if (UpdateChecker.isCritical()) {
            return "关键更新 · 这个版本还可以先不更新 " + UpdateChecker.remainingSkips() + " 次。";
        }
        return "普通更新 · 这次更新不强制，可以稍后安装。";
    }

    /** 原文、宽度、字形就绪与字体度量共同作键；异步清单与换字体都能立即重排。 */
    private void wrapBody(double contentWidth) {
        String source = buildBody();
        CFontRenderer font = NcmExpressive.bodyFont();
        boolean glyphsReady = font != null && font.areGlyphsLoaded(source);
        long revision = font == null ? -1 : font.metricsRevision();
        if (!wrappedWithLoadedGlyphs || glyphsReady != wrappedWithLoadedGlyphs
                || !source.equals(wrappedSource) || Math.abs(contentWidth - wrappedForWidth) >= .5
                || font != wrappedFont || revision != wrappedFontRevision) {
            wrappedForWidth = contentWidth;
            wrappedSource = source;
            wrappedWithLoadedGlyphs = glyphsReady;
            wrappedFont = font;
            wrappedFontRevision = revision;
            bodyLines = wrapParagraphs(source, contentWidth);
            while (bodyLabels.size() < bodyLines.length) {
                final int line = bodyLabels.size();
                LabelWidget label = NcmExpressive.label(bodyContent,
                        () -> line < bodyLines.length ? bodyLines[line] : "", NcmExpressive::bodyFont,
                        () -> BODY_PAD,
                        () -> (networkFailure ? 0 : notesTop()) + BODY_PAD + (line + .5) * lineHeight(),
                        () -> Math.max(1, bodyViewport.getWidth() - BODY_PAD * 2 - 8),
                        () -> NcmExpressivePalette.live().containerInk());
                bodyLabels.add(label);
            }
        }
    }

    private void wrapCaptions(double width) {
        String policy = buildPolicy();
        String feedback = status == null ? "" : status;
        CFontRenderer font = NcmExpressive.captionFont();
        boolean ready = font != null && font.areGlyphsLoaded(policy) && font.areGlyphsLoaded(feedback);
        long revision = font == null ? -1 : font.metricsRevision();
        if (!captionsWithLoadedGlyphs || ready != captionsWithLoadedGlyphs
                || !policy.equals(captionPolicy) || !feedback.equals(captionStatus)
                || Math.abs(width - captionWidth) >= .5 || font != captionFont || revision != captionFontRevision) {
            captionWidth = width;
            captionPolicy = policy;
            captionStatus = feedback;
            captionFont = font;
            captionFontRevision = revision;
            captionsWithLoadedGlyphs = ready;
            policyLines = captionLines(policy, width, font);
            statusLines = captionLines(feedback, Math.max(1, width - 44), font);
        }
    }

    private static String[] captionLines(String source, double width, CFontRenderer font) {
        String[] lines = wrapParagraphs(source, width, font);
        if (lines.length <= 2) return lines;
        return new String[]{lines[0], font == null ? lines[1] : font.trim(lines[1] + "…", width)};
    }

    private static String[] wrapParagraphs(String source, double contentWidth) {
        return wrapParagraphs(source, contentWidth, NcmExpressive.bodyFont());
    }

    private static String[] wrapParagraphs(String source, double contentWidth, CFontRenderer font) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : source.split(PARAGRAPH_BREAK, -1)) {
            String text = paragraph.trim();
            if (text.isEmpty()) continue;
            String[] wrapped = NcmExpressive.wrap(font, text, Math.max(1, contentWidth));
            if (wrapped.length == 0) lines.add(text);
            else for (String line : wrapped) lines.add(line);
        }
        return lines.toArray(new String[0]);
    }

    private void copyToClipboard(String text, String message) {
        KeyboardUtils.setClipboardString(text);
        status = message + text;
    }

    /** 重试只有 UpdateChecker 的判重入口能发起；等待期间不叠检查线程。 */
    private void beginRetry() {
        if (resolved) return;
        if (retryPending || !UpdateChecker.retryCheck()) {
            status = "上一次检查还在跑，稍等一下喵…";
            return;
        }
        retryPending = true;
        status = "正在重新检查…";
    }

    private void pollRetry() {
        if (!retryPending || resolved) return;
        int now = UpdateChecker.state();
        if (now == UpdateChecker.STATE_UNKNOWN) return;
        if (now == UpdateChecker.STATE_CHECK_FAILED) {
            retryPending = false;
            status = "还是连不上喵…检查一下网络、代理或 DNS 设置吧。";
            return;
        }
        retryPending = false;
        UpdateChecker.allowNoticeAgain();
        cancel();
    }

    /** 打开浏览器失败时复制白名单地址；锁定状态无论成功失败都留在当前提示。 */
    private void openDownloadPage() {
        String page = UpdateChecker.downloadPage();
        if (isAllowedAndBrowsable(page)) {
            if (forcedUpdate) {
                status = "已在浏览器里打开下载页喵，装好新版本再进游戏吧。";
                return;
            }
            cancel();
            return;
        }
        KeyboardUtils.setClipboardString(page);
        status = forcedUpdate
                ? "打不开浏览器喵…地址已复制到剪贴板，装好新版本再进游戏：" + page
                : "打不开浏览器喵…地址已复制到剪贴板，去浏览器里粘一下吧：" + page;
    }

    /** 沿用已有浏览器兼容链路，不引入新的宿主或加载器调用。 */
    private static boolean isAllowedAndBrowsable(String page) {
        if (!UpdateChecker.isAllowedPage(page)) return false;
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop").invoke(null);
            desktopClass.getMethod("browse", URI.class).invoke(desktop, new URI(page));
            return true;
        } catch (Throwable ignored) {
        }
        try {
            return com.muoniumplayer.compat.lwjgl2.Sys.openURL(page);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
