package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.update.UpdateChecker;
import com.muoniumplayer.core.utils.KeyboardUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 更新相关提示的界面本体，有两套内容：
 *
 * <ul>
 *   <li><b>有新版本</b>（{@link UpdateChecker#state()} 为 {@code STATE_OUTDATED}）—— 版本号、
 *       更新说明、前往下载；</li>
 *   <li><b>连不上服务器</b>（{@code STATE_CHECK_FAILED}）—— 连续多次启动都没拉到清单时提醒
 *       用户检查网络，并给一个「重试检查」。</li>
 * </ul>
 *
 * <p>与 {@link FfmpegNoticeOverlay} 同构，理由也一样：这个提示要在<b>主菜单</b>就弹出来，
 * 而主菜单上没有播放器界面可以承载浮层，所以做成一个能独立渲染的 {@link NCMPanel} ——
 * 宿主是个最薄的 {@code GuiUpdateNotice}，只负责开 Skija 帧、转发输入、收起后回到原界面。</p>
 *
 * <p><b>「有新版本」这一套还带一个额定额度</b>：这次提示<b>每次启动都会再弹</b> —— 额度决定的不是
 * 弹不弹，而是还能不能先不更新。同一个新版本最多先不更新 {@link UpdateChecker#MAX_SKIP_COUNT} 次，
 * 每一次收起提示（点「先不更新」或按 ESC）都用掉一格；用尽后 {@link UpdateChecker#mustUpdate()}
 * 成立，界面上不再有任何绕过的出口 —— 「先不更新」按钮不再出现，ESC 与点卡片外面也失效，
 * 只剩「前往下载」（它此时只打开浏览器，不会收起提示，见下）。清单换成更新的版本时额度重新给满。</p>
 *
 * <p>点卡片外面刻意<b>不</b>算一次「先不更新」：额度是有限资源，误触一下用掉一格太亏，
 * 想走就走按钮或 ESC。</p>
 *
 * <p><b>锁死状态下「前往下载」不收起提示</b> —— 这一点必须保住：提示一关用户就回到主菜单、
 * 也就能进游戏了，额度用尽等于白设。所以此时它只负责打开系统浏览器（打不开就把地址复制进
 * 剪贴板），提示留在屏幕上；用户下载、装好新版本、重启游戏之后自然就不再弹了。见
 * {@link #openDownloadPage()}。</p>
 */
public final class UpdateNoticeOverlay extends NCMPanel {

    /** 标题：两套内容各一份。 */
    private static final String UPDATE_HEADING = "MuoniumPlayer 有新版本啦喵~";
    private static final String FAILED_HEADING = "连不上更新服务器喵…";
    private static final String DOWNLOAD = "前往下载";
    private static final String COPY_LINK = "复制下载链接";
    private static final String COPY_PAGE_LINK = "复制更新页地址";
    /** 「先不更新」＝ 用掉一格额度收起提示。额度用尽后这个按钮不再出现。 */
    private static final String LATER = "先不更新";
    private static final String RETRY = "重试检查";
    private static final String OK = "我知道了";

    /**
     * 正文最多显示的行数；再长会在最后一行截断，卡片不允许长到顶出屏幕。
     *
     * <p>上限留得比较宽是因为正文本身含三段（当前/最新版本、还能先不更新几次、更新公告），
     * 而公告一动就是上百字；行数压太紧会把最该看的公告截掉。</p>
     */
    private static final int MAX_BODY_LINES = 8;

    /** 正文里的显式换行：次数说明与更新公告必须分成两行，见 {@link #buildBody()}。 */
    private static final String PARAGRAPH_BREAK = "\n";

    /** 卡片左右留白，标题、正文、底板、按钮共用同一套内缩，任何一处改动都不会各自跑偏。 */
    private static final double SIDE_INSET = 22.0;
    /** 卡片宽度的下限与上限：太窄放不下按钮，太宽一行字读起来要来回扫。 */
    private static final double CARD_MIN_WIDTH = 320.0;
    private static final double CARD_MAX_WIDTH = 460.0;
    /** 徽标与标题所在的高度带。 */
    private static final double HEADER_CENTER_Y = 28.0;
    /** 正文区上沿（正文文字的第一行基线所在处）。 */
    private static final double BODY_TOP = 52.0;
    /** 承载正文的圆角底板在文字四周留出的边距。 */
    private static final double BODY_PAD = 7.0;
    /** 按钮高度；下面那一行链接区的高度由字体决定。 */
    private static final double BUTTON_HEIGHT = 26.0;
    /** 分隔线到按钮之间的间距。 */
    private static final double BUTTONS_TOP_GAP = 9.0;
    /** 按钮与下面那行链接之间的间距。 */
    private static final double LINK_GAP = 10.0;
    private static final double CARD_BOTTOM_END_PAD = 12.0;

    /** 强调按钮（前往下载 / 重试检查）的底色，与「未安装分析模型」提示里的安装按钮同色。 */
    private static final int PRIMARY_COLOR = 0xD2913A;
    private static final int PRIMARY_HOVER_COLOR = 0xE5B24E;
    /** 复制链接 / 打不开浏览器 / 重试失败等一行反馈的颜色。 */
    private static final int STATUS_COLOR = 0xFFB74D;
    /** 文字链接悬停时的颜色。 */
    private static final int LINK_HOVER_COLOR = 0xFFD08A;
    /** 徽标底色：有更新用信息蓝，连不上服务器用警告琥珀（与 ffmpeg 提示的警告色同一族）。 */
    private static final int BADGE_INFO_COLOR = 0x3F7FBF;
    private static final int BADGE_WARN_COLOR = 0xD2762F;

    /** 本次提示是「连不上服务器」那一套（构造时定死：这个界面存在期间它不会变）。 */
    private final boolean networkFailure;
    /** 是否处于「跳过额度用尽 / 关键更新」的锁死状态，见类注释。 */
    private final boolean forcedUpdate;

    private boolean closing;
    /** 进出场那一条时钟，和 {@code NcmSheet} 那六个浮层共用同一个 {@link SheetClock}。 */
    private final SheetClock clock = new SheetClock();
    /** 遮罩与卡片共同读的那一档进度（已经过缓动）。 */
    private double presentation;
    private boolean resolved;

    /** 已经点了「重试检查」、正在等结论。 */
    private boolean retryPending;

    /** 点了「复制下载链接」、浏览器打不开、或重试又失败时的一行反馈。 */
    private String status;
    /** 按当前卡片宽度换行后的正文。 */
    private String[] bodyLines = new String[0];
    /** 上一次换行用的宽度；宽度没变就不重复换行（换行要逐字累加字形宽度）。 */
    private double wrappedForWidth = -1.0;
    /** 上一次换行用的原文。正文可能比界面晚到（检查在守护线程上跑），原文一变必须重排。 */
    private String wrappedSource;
    /** 上一次换行时字形是否已全部就绪；没就绪算出来的宽度是 0，必须下一帧重算。 */
    private boolean wrappedWithLoadedGlyphs;
    /** 标题是否已经降到小一号字体；只在需要变化时才换字体。 */
    private boolean titleCompact;

    private Panel dialog;
    private RoundedButtonWidget leftButton;
    private RoundedButtonWidget rightButton;
    /** 链接区的槽位，按加入顺序从左到右排；不出现的槽位每帧显式清掉尺寸。 */
    private final List<LinkSlot> linkSlots = new ArrayList<LinkSlot>();

    public UpdateNoticeOverlay() {
        this.networkFailure = UpdateChecker.state() == UpdateChecker.STATE_CHECK_FAILED;
        // 额度用尽这件事在界面存在期间不会变（跳过一次就收起了），所以只算一次。
        this.forcedUpdate = !this.networkFailure && UpdateChecker.mustUpdate();
    }

    /** 链接区的一个槽位：标签本身 + 这个槽位此刻该不该出现。 */
    private static final class LinkSlot {

        private final LabelWidget label;
        private final BooleanSupplier visible;

        private LinkSlot(LabelWidget label, BooleanSupplier visible) {
            this.label = label;
            this.visible = visible;
        }
    }

    /** 正在退场：还画得着，但 {@link NCMPanel} 会把它这一帧的输入整个吞掉。 */
    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public boolean shouldClose() {
        // 退场播完才回收，理由见 createDialog 里那条遮罩。
        return clock.isGone();
    }

    /** 收起提示，不记账。非锁死状态下「前往下载」、以及重试连上之后走这里。 */
    public void cancel() {
        if (resolved) return;
        resolved = true;
        closing = true;
        clock.requestDismiss();
    }

    /**
     * 「先不更新」「我知道了」以及 ESC 都走这里：收起提示，并且在「有更新」那一套里扣掉一格额度。
     *
     * <p>额度用尽时这里什么都不做 —— 那就是「必须更新」的落地点。弹不弹由宿主问
     * {@link UpdateChecker#shouldShow()}，而那个判断不看额度：额度管的是「还能不能先不更新」。</p>
     */
    public void dismissForNow() {
        if (forcedUpdate) return;
        if (!networkFailure) {
            UpdateChecker.noteSkippedOnce();
        }
        cancel();
    }

    public void handleEscape() {
        dismissForNow();
    }

    /**
     * 「必须更新」时任何关闭请求都该被拒绝。
     *
     * <p>宿主用它兜住其它关闭路径（原版 {@code Screen.onClose()}、别的代码直接调它）——
     * 我们自己的 ESC 走 {@link #handleEscape()}，但「关不关得成」不该只由那一条路径决定。</p>
     *
     * <p><b>没有例外通道</b>：这里曾经留过一个「浏览器打不开就放开锁」的逃生阀，但它正好就是
     * 用户绕开更新进游戏的出口，已经删掉。锁死时唯一的出路是把新版本装上。</p>
     */
    public boolean blocksClosing() {
        return forcedUpdate;
    }

    @Override
    public void onInit() {
        // 遮罩只压一半黑：底下就是主菜单，压太狠会让整屏发闷（这正是原实现的观感问题）。
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setClickable(false);
        mask.setColor(0x000000);
        // 遮罩跟着卡片同一条进度淡。写死 .45f 的话，卡片还没出现整屏已经先黑了，
        // 关掉时又是卡片啪一下没了、遮罩还黑着。
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .45));
        });
        // 时钟跑在根组件这一格：它先于所有子组件，遮罩和卡片于是读到同一个值。
        setBeforeRenderCallback(() -> presentation = TransitionCurveCache.MOTION.at(clock.tick()));

        // 点在卡片外面只是被这里吃掉（防止点到下层的原版界面）：额度是有限资源，
        // 误触一下就用掉一格太亏，想收起提示请走「先不更新」或 ESC。
        setOnClickCallback((x, y, button) -> button == 0);

        dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        // 高度由正文行数决定，所以尺寸每帧在渲染前重算：窗口变窄会让正文多一行，卡片必须跟着
        // 长高，否则按钮会压在正文上。重试的结论也在这里轮询。
        dialog.setBeforeRenderCallback(() -> layoutDialog());

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(11);
        background.setColor(getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        background.setBeforeRenderCallback(() -> background.setMargin(0));
        background.setGlassSheet(GlassLook.Surface.PLAYER);
        // 底板自己那层深色压薄：不压的话刚贴上去的糊背景仍然在它底下，弹窗读起来就是一块实色板。
        background.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));

        RoundedRectWidget badge = new RoundedRectWidget();
        dialog.addChild(badge);
        badge.setClickable(false);
        badge.setRadius(12);
        badge.setColor(networkFailure ? BADGE_WARN_COLOR : BADGE_INFO_COLOR);
        badge.setBeforeRenderCallback(() -> badge.setBounds(SIDE_INSET, 16, 24, 24));

        // Fontello 的字形随播放器内置，避免半角符号在缺字形时退化成方框。
        LabelWidget badgeIcon = new LabelWidget(
                networkFailure ? FontelloIcons.WARNING : FontelloIcons.LINK, FontManager.fontello18);
        dialog.addChild(badgeIcon);
        badgeIcon.setClickable(false);
        badgeIcon.setBeforeRenderCallback(() -> {
            badgeIcon.setColor(0xFFFFFF);
            badgeIcon.setPosition(SIDE_INSET + 12 - badgeIcon.getWidth() * .5,
                    HEADER_CENTER_Y - badgeIcon.getHeight() * .5 + 1);
        });

        LabelWidget headingLabel = new LabelWidget(networkFailure ? FAILED_HEADING : UPDATE_HEADING,
                FontManager.pf18bold);
        dialog.addChild(headingLabel);
        headingLabel.setClickable(false);
        headingLabel.setBeforeRenderCallback(() -> {
            String heading = networkFailure ? FAILED_HEADING : UPDATE_HEADING;
            double maxWidth = Math.max(1, dialog.getWidth() - SIDE_INSET - 52);
            // 窄窗口下宁可降一号字也不截断：标题就是这次提示的主语，看不全等于没提示。
            boolean compact = FontManager.pf18bold != null
                    && FontManager.pf18bold.getStringWidthD(heading) > maxWidth;
            if (compact != titleCompact) {
                titleCompact = compact;
                headingLabel.setFont(compact ? FontManager.pf14bold : FontManager.pf18bold);
            }
            headingLabel.setColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            headingLabel.setMaxWidth(maxWidth);
            headingLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            headingLabel.setPosition(SIDE_INSET + 40, HEADER_CENTER_Y - headingLabel.getHeight() * .5);
        });

        // 正文底板：整块圆角矩形垫在文字下面。它比正文标签先加入，所以画在文字下面。
        RoundedRectWidget bodySurface = new RoundedRectWidget();
        dialog.addChild(bodySurface);
        bodySurface.setClickable(false);
        bodySurface.setRadius(6);
        bodySurface.setBeforeRenderCallback(() -> {
            bodySurface.setBounds(SIDE_INSET, BODY_TOP - BODY_PAD,
                    Math.max(1, dialog.getWidth() - SIDE_INSET * 2), bodyBlockHeight() + BODY_PAD * 2);
            bodySurface.setColor(getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        // 正文每行一个 Label：这套字体渲染器没有多行绘制，换行结果由 wrapBody 每次布局时算好。
        // 用不到的行取空字符串而不是 setHidden —— 被隐藏的组件不再执行自己的回调，就再也显示不回来。
        for (int index = 0; index < MAX_BODY_LINES; index++) {
            final int lineIndex = index;
            LabelWidget bodyLabel = new LabelWidget(
                    () -> lineIndex < bodyLines.length ? bodyLines[lineIndex] : "", FontManager.pf14);
            dialog.addChild(bodyLabel);
            bodyLabel.setClickable(false);
            bodyLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            bodyLabel.setBeforeRenderCallback(() -> {
                // 宽度必须在渲染前从已经定过尺寸的卡片上取：onInit() 时卡片宽度还是 0，
                // 那时算出来的上限会被夹成 1px，而 TRIM_TO_WIDTH 在 1px 内连省略号都
                // 放不下，于是整行文字静默地什么都不画。
                bodyLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2 - BODY_PAD * 2));
                bodyLabel.setColor(getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                bodyLabel.setPosition(SIDE_INSET + BODY_PAD, BODY_TOP + lineIndex * lineHeight());
            });
        }

        // 复制链接 / 打不开浏览器 / 重试失败时的一行说明。这一行的高度恒被预留：否则它会一边长出来
        // 一边把按钮往下推，用户正要点按钮的时候按钮跑了。
        LabelWidget statusLabel = new LabelWidget(() -> status == null ? "" : status, FontManager.pf14);
        dialog.addChild(statusLabel);
        statusLabel.setClickable(false);
        statusLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        statusLabel.setBeforeRenderCallback(() -> {
            statusLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2 - BODY_PAD * 2));
            statusLabel.setColor(STATUS_COLOR);
            statusLabel.setPosition(SIDE_INSET + BODY_PAD, statusTop());
        });

        RectWidget divider = new RectWidget();
        dialog.addChild(divider);
        divider.setClickable(false);
        divider.setBeforeRenderCallback(() -> {
            divider.setBounds(SIDE_INSET, actionsTop(),
                    Math.max(1, dialog.getWidth() - SIDE_INSET * 2), .5);
            divider.setColor(getColor(NCMScreen.ColorType.BORDER));
        });

        leftButton = new RoundedButtonWidget(networkFailure ? OK : LATER, FontManager.pf12bold);
        dialog.addChild(leftButton);
        leftButton.setRadius(6);
        rightButton = new RoundedButtonWidget(networkFailure ? RETRY : DOWNLOAD, FontManager.pf12bold);
        dialog.addChild(rightButton);
        rightButton.setRadius(6);

        // 两个按钮共用一次宽度计算，保证左右对齐、中间间隙一致。
        leftButton.setBeforeRenderCallback(() -> {
            layoutActions();
            leftButton.setColor(leftButton.isHovering()
                    ? getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            leftButton.setTextColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        leftButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            dismissForNow();
            return true;
        });

        rightButton.setBeforeRenderCallback(() -> {
            layoutActions();
            rightButton.setColor(rightButton.isHovering() ? PRIMARY_HOVER_COLOR : PRIMARY_COLOR);
            rightButton.setTextColor(0xFFFFFF);
        });
        rightButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (networkFailure) {
                beginRetry();
            } else {
                openDownloadPage();
            }
            return true;
        });

        // 链接区。两套内容共用一个槽位列表：不出现的槽位宽度与命中区每帧都被清零。
        if (networkFailure) {
            addLink(COPY_PAGE_LINK, () -> true, () -> copyToClipboard(
                    UpdateChecker.DOWNLOAD_PAGE, "更新页地址已复制到剪贴板："));
        } else {
            addLink(COPY_LINK, () -> true, () -> copyToClipboard(
                    UpdateChecker.downloadPage(), "地址已复制到剪贴板："));
        }
    }

    /**
     * 往链接区加一个槽位。
     *
     * @param text    标签文字
     * @param visible 这个槽位此刻该不该出现（每帧问一次）
     * @param action  左键点击动作
     */
    private void addLink(String text, BooleanSupplier visible, Runnable action) {
        LabelWidget label = new LabelWidget(text, FontManager.pf12);
        dialog.addChild(label);
        label.setShouldOverrideMouseCursor(true);
        LinkSlot slot = new LinkSlot(label, visible);
        linkSlots.add(slot);
        label.setBeforeRenderCallback(() -> {
            layoutLinks();
            label.setColor(label.isHovering()
                    ? LINK_HOVER_COLOR
                    : getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        label.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
    }

    private void copyToClipboard(String text, String message) {
        KeyboardUtils.setClipboardString(text);
        status = message + text;
    }

    /** 「重试检查」：再拉一次清单。已经在跑时只提示，不叠线程。 */
    private void beginRetry() {
        if (!UpdateChecker.retryCheck()) {
            status = "上一次检查还在跑，稍等一下喵…";
            return;
        }
        retryPending = true;
        status = "正在重新检查…";
    }

    /**
     * 轮询重试的结论。
     *
     * <p>三种落点：还在跑（不动）、又失败（留在界面上把结论写出来）、连上了（收起提示）。
     * 最后一种要顺手把「本次启动已弹过」放开 —— 重试可能查到新版本，而当前这套组件是
     * 「连不上」那一套，装不下版本号与更新说明，得让宿主用新结论再弹一次。</p>
     */
    private void pollRetry() {
        if (!retryPending) return;
        int now = UpdateChecker.state();
        if (now == UpdateChecker.STATE_UNKNOWN) return;
        if (now == UpdateChecker.STATE_CHECK_FAILED) {
            retryPending = false;
            status = "还是连不上喵…检查一下网络、代理或 DNS 设置吧。";
            return;
        }
        UpdateChecker.allowNoticeAgain();
        cancel();
    }

    private void layoutDialog() {
        pollRetry();
        double width = Math.max(CARD_MIN_WIDTH, Math.min(CARD_MAX_WIDTH, getWidth() - 32));
        // 先按这个宽度把正文换行，再由行数决定卡片高度。
        wrapBody(Math.max(1, width - SIDE_INSET * 2 - BODY_PAD * 2));
        // 上限用「屏幕高度 - 28」而不是写死的数：这样卡片一定落在屏幕内。正常窗口下这个上限
        // 根本不会生效，只有极小的窗口才会压到正文行数。
        dialog.setBounds(width, Math.min(Math.max(170.0, contentHeight()),
                Math.max(170.0, getHeight() - 28)));
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1.0 - presentation) * 10.0);
        dialog.setAlpha((float) presentation);
    }

    private void layoutActions() {
        double total = Math.max(1, dialog.getWidth() - SIDE_INSET * 2);
        double top = buttonsTop();
        if (forcedUpdate) {
            // 跳过额度用尽的锁死状态：只留「前往下载」，按钮占满整行 —— 界面上没有别的出口。
            leftButton.setBounds(0, 0);
            rightButton.setBounds(total, BUTTON_HEIGHT);
            rightButton.setPosition(SIDE_INSET, top);
            return;
        }
        double gap = 8;
        double rightWidth = Math.max(104, Math.min(140, total * .56));
        double leftWidth = Math.max(1, total - gap - rightWidth);
        // 次要动作在左、主行动在右：读起来是「先看主行动」，鼠标也正好停在惯用一侧。
        leftButton.setBounds(leftWidth, BUTTON_HEIGHT);
        leftButton.setPosition(SIDE_INSET, top);
        rightButton.setBounds(rightWidth, BUTTON_HEIGHT);
        rightButton.setPosition(SIDE_INSET + leftWidth + gap, top);
    }

    /** 链接并排居中；不出现的槽位宽度按 0 算，自然就让开了。 */
    private void layoutLinks() {
        double available = Math.max(1, dialog.getWidth() - SIDE_INSET * 2);
        int visible = 0;
        double total = 0;
        for (LinkSlot slot : linkSlots) {
            if (!slot.visible.getAsBoolean()) continue;
            visible++;
            total += Math.max(0, slot.label.getWidth());
        }
        double spacing = 16;
        if (visible > 1 && total + spacing * (visible - 1) > available) {
            // 窄窗口下先压间距，再挤下去就只能重叠了。
            spacing = 8;
        }
        double width = total + spacing * Math.max(0, visible - 1);
        double left = Math.max(SIDE_INSET, dialog.getWidth() * .5 - width * .5);
        double y = linkTop();
        for (LinkSlot slot : linkSlots) {
            if (!slot.visible.getAsBoolean()) {
                // 出现过的链接会被上一次的边界留着，收起来时必须显式清掉，否则还能点到。
                slot.label.setBounds(0, 0);
                continue;
            }
            slot.label.setPosition(left, y);
            left += Math.max(0, slot.label.getWidth()) + spacing;
        }
    }

    private static double lineHeight() {
        return (FontManager.pf14 == null ? 13.0 : FontManager.pf14.getFontHeight()) + 3.0;
    }

    private double bodyBlockHeight() {
        return Math.max(lineHeight(), bodyLines.length * lineHeight());
    }

    /** 兜底说明那一行的高度恒被预留，见 statusLabel 的注释。 */
    private double statusTop() {
        return BODY_TOP + bodyBlockHeight() + BODY_PAD + 3.0;
    }

    private double actionsTop() {
        return statusTop() + lineHeight() + 4.0;
    }

    private double buttonsTop() {
        return actionsTop() + BUTTONS_TOP_GAP;
    }

    private double linkTop() {
        return buttonsTop() + BUTTON_HEIGHT + LINK_GAP;
    }

    /** 卡片为了装下正文、兜底说明、按钮与链接所需的高度。 */
    private double contentHeight() {
        return linkTop() + lineHeight() + CARD_BOTTOM_END_PAD;
    }

    /**
     * 正文：两套内容各写各的。
     *
     * <p>「有更新」那套在锁死状态下会多一句解释 —— 用户看不到「跳过」了，得知道为什么。</p>
     */
    private String buildBody() {
        if (networkFailure) {
            return "已经连续 " + UpdateChecker.failureCount() + " 次启动都没能获取版本清单。"
                    + "通常是网络、代理或 DNS 的问题，检查一下再点「重试检查」喵。";
        }
        StringBuilder body = new StringBuilder();
        body.append("当前 ").append(UpdateChecker.currentVersion())
                .append("，最新 ").append(UpdateChecker.latestVersion())
                .append("（").append(UpdateChecker.channel()).append("）。");
        if (forcedUpdate) {
            body.append("这个版本已经先不更新 ").append(UpdateChecker.usedSkips())
                    .append(" 次啦，这一次必须更新才能继续喵。");
        } else {
            body.append("这个版本还可以先不更新 ").append(UpdateChecker.remainingSkips())
                    .append(" 次喵。");
        }
        String notes = UpdateChecker.releaseNotes();
        if (notes != null && !notes.isEmpty()) {
            // 公告另起一行：它是独立的一段信息，接在「还能先不更新几次」后面会被读成同一句话，
            // 更长的那一段（公告）反而容易被看漏。
            body.append(PARAGRAPH_BREAK).append(notes);
        }
        return body.toString();
    }

    /**
     * 把正文按给定宽度换行。
     *
     * <p>与 {@link FfmpegNoticeOverlay} 一样按「宽度 + 是否已就绪」缓存，但这里多带一个原文：
     * 版本清单是在守护线程上拉的，界面有可能先弹出来、正文后到，只按宽度缓存会把第一次的
     * 空版本号一直留在屏幕上。</p>
     */
    private void wrapBody(double contentWidth) {
        String source = buildBody();
        boolean glyphsReady = FontManager.pf14 != null && FontManager.pf14.areGlyphsLoaded(source);
        if (!wrappedWithLoadedGlyphs || !source.equals(wrappedSource)
                || Math.abs(contentWidth - wrappedForWidth) >= .5) {
            wrappedForWidth = contentWidth;
            wrappedSource = source;
            wrappedWithLoadedGlyphs = glyphsReady;
            bodyLines = wrapParagraphs(source, contentWidth);
        }
    }

    /**
     * 折行：先按显式换行 {@link #PARAGRAPH_BREAK} 切成段落，每段各自按宽度折，再拼成行表。
     *
     * <p>必须自己切，不能把带换行符的整段丢给字体渲染器的 {@code fitWidth} —— 它的断点表里
     * 只有空白与标点，换行符会当成普通字符留在行里画出来（末尾多一个看不见的空位，
     * 公告也不会真的另起一行）。段落内部的折行仍然由它负责。</p>
     */
    private static String[] wrapParagraphs(String source, double contentWidth) {
        List<String> lines = new ArrayList<String>();
        for (String paragraph : source.split(PARAGRAPH_BREAK, -1)) {
            String text = paragraph.trim();
            if (text.isEmpty()) continue;
            String[] wrapped;
            try {
                wrapped = FontManager.pf14.fitWidth(text, contentWidth);
            } catch (Throwable ignored) {
                // 换行只是排版，任何异常都不该让一个提示窗打不开。
                wrapped = new String[]{text};
            }
            if (wrapped == null || wrapped.length == 0) wrapped = new String[]{text};
            for (String line : wrapped) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            lines.add(source);
        }
        if (lines.size() > MAX_BODY_LINES) {
            // 超长时截断最后一行：卡片高度有上限，宁可少显示也不能顶出屏幕。
            List<String> limited = new ArrayList<String>(lines.subList(0, MAX_BODY_LINES));
            limited.set(MAX_BODY_LINES - 1, FontManager.pf14.trim(
                    limited.get(MAX_BODY_LINES - 1), Math.max(1, contentWidth - 8)) + "…");
            lines = limited;
        }
        return lines.toArray(new String[0]);
    }

    /**
     * 「前往下载」：先尝试打开系统浏览器，打不开就把地址放进剪贴板并把说明显示出来。
     *
     * <p>先用反射走 {@code java.awt.Desktop}（和原版 {@code GuiScreen.openWebLink} 同一条路：
     * 反射是为了不在错误的时机把 AWT 类加载进来），失败再退到 LWJGL 的 {@code Sys.openURL}。
     * 地址一定在 {@link UpdateChecker#isAllowedPage(String)} 的白名单里，不会被清单牵去任意站点。</p>
     *
     * <p>锁死状态下<b>只打开浏览器，绝不收起提示</b>：提示一关用户就回到主菜单、也就能进游戏了，
     * 那样「额度用尽」形同虚设。浏览器打不开时把地址复制进剪贴板，同样留在界面上 —— 用户照样能
     * 自己粘到浏览器里下载。</p>
     */
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

    private static boolean isAllowedAndBrowsable(String page) {
        if (!UpdateChecker.isAllowedPage(page)) {
            return false;
        }
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
