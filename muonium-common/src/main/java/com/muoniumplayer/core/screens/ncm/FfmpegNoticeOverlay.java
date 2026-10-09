package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.FfmpegInstaller;
import com.muoniumplayer.core.ncm.music.FfmpegNotice;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.utils.KeyboardUtils;

import java.net.URI;

/**
 * 「您的电脑似乎没有 ffmpeg 喵~」提示的界面本体。
 *
 * <p>它与播放器里的模态浮层（{@link ConfirmationOverlay}、{@link ModelInstallNoticeOverlay}）
 * 是同一套 Skija 组件：遮罩、圆角卡片、主题色、悬停态、模态输入独占全部复用。之所以要单独
 * 做成一个能独立渲染的 {@link NCMPanel} 而不是像那两个一样挂在 {@code NCMScreen} 上，是因为
 * 这个提示要在<b>主菜单</b>就弹出来 —— 那时播放器界面还没打开，没有宿主浮层可用。
 * 对应地有个最薄的宿主 {@code GuiFfmpegNotice}：它只负责开 Skija 帧、把窗口尺寸与鼠标键盘
 * 转给这里、以及提示收起后回到原来的界面。</p>
 *
 * <p>在此之前这里是「原版 GuiScreen 画底板 + GuiGraphics.drawString 画文字」的混合实现，
 * 而标题/正文用的是 {@code 0xFFFFFF} 这类<b>不含 alpha 通道</b>的颜色常量。1.21 的原版文字
 * 链路会把颜色当成 ARGB 直接取高字节作透明度（{@code Font}/{@code GuiGraphics} 里都没有
 * 「alpha 为 0 就当不透明」的兜底），于是标题、正文、「永不展示」画出来全透明，界面上只剩
 * 两个按钮的文字（按钮标签走的是另一条 ActiveTextCollector 链路，颜色是带 alpha 的）。
 * 改用 Skija 之后文字与播放器界面走同一条 {@code CFontRenderer} 链路，颜色一律带 alpha，
 * 不再有这种分歧。</p>
 *
 * <p>出口也按「傻瓜操作」重排过：Windows 上的主行动是「一键安装喵！」——交给 {@link FfmpegInstaller}
 * 用系统自带的 winget 自动装，状态行滚着进度省略号，界面侧的看门狗每帧瞄一眼安装状态，装好后把成功
 * 文案多留一秒半再自动收起（立刻消失会让人怀疑有没有真装上）。winget 装的 ffmpeg 落在 WinGet Links
 * 目录，探测逻辑本来就覆盖那里，装完立刻生效、不用重启游戏。「前往下载」退居次要出口：winget 不在
 * 或装失败时，还能去官网手动下。三个出口都不改动任何设置；打不开浏览器就把地址塞进剪贴板。
 * ESC 与点遮罩等价于「我才不管呢喵」。</p>
 */
public final class FfmpegNoticeOverlay extends NCMPanel {

    /** 标题：整段提示的主语。 */
    private static final String HEADING = "您的电脑似乎没有ffmpeg喵~要不要安装一个喵？！";
    /** 正文：解释不装会怎样。 */
    private static final String BODY =
            "当然如果不安装也是可以使用的喵！ 但是会影响一些功能喵！ 例如动态封面，B站解析可能会受到影响的喵喵喵";
    private static final String NEVER_SHOW = "永不展示";
    private static final String DOWNLOAD = "前往下载";
    private static final String INSTALL = "一键安装喵！";
    private static final String IGNORE = "我才不管呢喵！";

    /** 空闲时状态行上的指引：解释「一键安装」是什么、要联网，免得用户以为点了就会弹窗广告。 */
    private static final String HINT_IDLE = "点「一键安装喵！」用 winget 自动装好，装完立即生效（需要联网）";
    private static final String STATUS_INSTALLING = "正在用 winget 下载安装 ffmpeg 喵";
    private static final String STATUS_SUCCESS = "装好了喵！已经检测到 ffmpeg，动态封面可以用了";

    /** 正文最多显示的行数；再长会在最后一行截断，卡片不允许长到顶出屏幕。 */
    private static final int MAX_BODY_LINES = 5;

    /** 卡片左右留白，标题、正文、底板、按钮共用同一套内缩，任何一处改动都不会各自跑偏。 */
    private static final double SIDE_INSET = 22.0;
    /** 卡片宽度的下限与上限：太窄放不下按钮，太宽一行字读起来要来回扫。 */
    private static final double CARD_MIN_WIDTH = 300.0;
    private static final double CARD_MAX_WIDTH = 430.0;
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
    /** 按钮与「永不展示」之间的间距。 */
    private static final double LINK_GAP = 8.0;
    private static final double CARD_BOTTOM_PAD = 12.0;

    /** 强调按钮（一键安装喵！）的底色，与「未安装分析模型」提示里的安装按钮同色。 */
    private static final int PRIMARY_COLOR = 0xD2913A;
    private static final int PRIMARY_HOVER_COLOR = 0xE5B24E;
    /** 安装进行中主按钮压暗一档：看得出「在干活」，又不至于像坏了。 */
    private static final int PRIMARY_RUNNING_COLOR = 0xB07E38;
    /** 安装成功的状态行颜色；唯一一处跳出橙色系的地方，让「成了」和「出事了」一眼分得开。 */
    private static final int SUCCESS_COLOR = 0x9FD48A;
    /** 异常与进行中共用的状态行颜色（打不开浏览器、安装失败等）。 */
    private static final int STATUS_COLOR = 0xFFB74D;
    /** 链接悬停时的颜色（我才不管呢喵 / 永不展示）。 */
    private static final int LINK_HOVER_COLOR = 0xFFD08A;

    private boolean closing;
    /** 进出场那一条时钟，和 {@code NcmSheet} 那六个浮层共用同一个 {@link SheetClock}。 */
    private final SheetClock clock = new SheetClock();
    /** 遮罩与卡片共同读的那一档进度（已经过缓动）。 */
    private double presentation;
    private boolean resolved;

    /** 浏览器打不开时显示的兜底说明（地址已复制到剪贴板）；非空时状态行优先显示它。 */
    private String status;
    /** 界面侧看门狗：第一次看到「装好了」的时刻，据此在短暂展示后自动收起。-1 表示还没见到。 */
    private long successSeenAt = -1L;
    /** 按当前卡片宽度换行后的正文。 */
    private String[] bodyLines = new String[0];
    /** 上一次换行用的宽度；宽度没变就不重复换行（换行要逐字累加字形宽度）。 */
    private double wrappedForWidth = -1.0;
    /** 上一次换行时字形是否已全部就绪；没就绪算出来的宽度是 0，必须下一帧重算。 */
    private boolean wrappedWithLoadedGlyphs;
    /** 标题是否已经降到小一号字体；只在需要变化时才换字体。 */
    private boolean titleCompact;

    /** 正在退场：还画得着，但 {@link NCMPanel} 会把它这一帧的输入整个吞掉。 */
    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public boolean shouldClose() {
        // 退场播完才回收。原来是 closing 一置位下一帧就把这一层抹掉，所以「打开有淡入、
        // 关掉啪一下没了」。
        return clock.isGone();
    }

    /** 「我才不管呢喵」、点遮罩、ESC 都走这里：收起提示，本次启动不再弹（由 FfmpegNotice 记）。 */
    public void cancel() {
        if (resolved) return;
        resolved = true;
        closing = true;
        clock.requestDismiss();
    }

    public void handleEscape() {
        cancel();
    }

    @Override
    public void onInit() {
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setClickable(false);
        mask.setColor(0x000000);
        // 遮罩和卡片同一条进度：原来它是写死的 .5f，于是卡片还没出现整屏已经先黑了。
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .5f));
        });
        // 时钟在根组件这一格里推进：它跑在所有子组件之前，遮罩和卡片于是读到同一个值。
        setBeforeRenderCallback(() -> presentation = TransitionCurveCache.MOTION.at(clock.tick()));

        // 点在卡片外面等价于「我才不管呢喵」，并且必须被这里吃掉：我们的宿主是一个独立的
        // Screen，落不到别处，但这个回调仍然要拦下右键以外的点击，避免往下透传。
        setOnClickCallback((x, y, button) -> {
            if (button == 0) {
                cancel();
                return true;
            }
            return false;
        });

        Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        // 高度由正文行数决定，所以尺寸每帧在渲染前重算：窗口变窄会让正文多一行，卡片必须跟着
        // 长高，否则按钮会压在正文上。顺带跑界面侧的安装看门狗，见 tickInstallWatchdog。
        dialog.setBeforeRenderCallback(() -> {
            layoutDialog(dialog);
            tickInstallWatchdog();
        });

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
        badge.setColor(0xD87857);
        badge.setBeforeRenderCallback(() -> badge.setBounds(SIDE_INSET, 16, 24, 24));

        // Fontello 的警告字形随播放器内置，避免半角感叹号在缺字形时退化成方框。
        LabelWidget warningIcon = new LabelWidget(FontelloIcons.WARNING, FontManager.fontello18);
        dialog.addChild(warningIcon);
        warningIcon.setClickable(false);
        warningIcon.setBeforeRenderCallback(() -> {
            warningIcon.setColor(0xFFFFFF);
            warningIcon.setPosition(SIDE_INSET + 12 - warningIcon.getWidth() * .5,
                    HEADER_CENTER_Y - warningIcon.getHeight() * .5 + 1);
        });

        LabelWidget headingLabel = new LabelWidget(HEADING, FontManager.pf18bold);
        dialog.addChild(headingLabel);
        headingLabel.setClickable(false);
        headingLabel.setBeforeRenderCallback(() -> {
            double maxWidth = Math.max(1, dialog.getWidth() - SIDE_INSET - 52);
            // 窄窗口下宁可降一号字也不截断：标题就是这次提示的主语，看不全等于没提示。
            boolean compact = FontManager.pf18bold != null
                    && FontManager.pf18bold.getStringWidthD(HEADING) > maxWidth;
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
                // 放不下，于是整行文字静默地什么都不画（正文曾经就是这样消失的）。
                bodyLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2 - BODY_PAD * 2));
                bodyLabel.setColor(getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                bodyLabel.setPosition(SIDE_INSET + BODY_PAD, BODY_TOP + lineIndex * lineHeight());
            });
        }

        // 状态行：平时是傻瓜指引，装的时候滚进度，结束报结果。这一行的高度恒被预留：否则它会一边
        // 长出来一边把按钮往下推，用户正要点按钮的时候按钮跑了。
        LabelWidget statusLabel = new LabelWidget(this::statusText, FontManager.pf14);
        dialog.addChild(statusLabel);
        statusLabel.setClickable(false);
        statusLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        statusLabel.setBeforeRenderCallback(() -> {
            // 宽度必须在渲染前从已经定过尺寸的卡片上取：onInit() 时卡片宽度还是 0，
            // 那时算出来的上限会被夹成 1px，而 TRIM_TO_WIDTH 在 1px 内连省略号都
            // 放不下，于是整行文字静默地什么都不画（正文曾经就是这样消失的）。
            statusLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2 - BODY_PAD * 2));
            statusLabel.setColor(statusColor());
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

        RoundedButtonWidget downloadButton = new RoundedButtonWidget(DOWNLOAD, FontManager.pf12bold);
        dialog.addChild(downloadButton);
        downloadButton.setRadius(6);
        downloadButton.setGlassRim(GlassLook.Surface.PLAYER);
        RoundedButtonWidget installButton = new RoundedButtonWidget(INSTALL, FontManager.pf12bold);
        dialog.addChild(installButton);
        installButton.setRadius(6);
        installButton.setGlassRim(GlassLook.Surface.PLAYER);

        // 两个按钮共用一次宽度计算，保证左右对齐、中间间隙一致。
        downloadButton.setBeforeRenderCallback(() -> {
            layoutActions(dialog, downloadButton, installButton);
            downloadButton.setColor(downloadButton.isHovering()
                    ? getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            downloadButton.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            downloadButton.setTextColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        downloadButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            openDownloadPage();
            return true;
        });

        installButton.setBeforeRenderCallback(() -> {
            layoutActions(dialog, downloadButton, installButton);
            boolean running = FfmpegInstaller.state() == FfmpegInstaller.RUNNING;
            installButton.setColor(running ? PRIMARY_RUNNING_COLOR
                    : installButton.isHovering() ? PRIMARY_HOVER_COLOR : PRIMARY_COLOR);
            installButton.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            installButton.setTextColor(0xFFFFFF);
        });
        installButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            // 正在装就别点出第二个安装进程；FfmpegInstaller.start() 自己也会挡，这里挡是为了
            // 连点时连「状态行闪一下」都不发生。
            if (FfmpegInstaller.state() == FfmpegInstaller.RUNNING) return true;
            // 清掉浏览器兜底说明之类的一次性文案，状态行交还给安装流程自己的输出。
            status = null;
            successSeenAt = -1L;
            FfmpegInstaller.start();
            return true;
        });

        // 「我才不管呢喵」与「永不展示」是两个低调出口，放同一行：左边本次不弹，右边永久不弹。
        // 它们降级成链接是刻意的——傻瓜流程要把视线留给「一键安装」。
        LabelWidget ignoreLink = new LabelWidget(IGNORE, FontManager.pf12);
        dialog.addChild(ignoreLink);
        ignoreLink.setShouldOverrideMouseCursor(true);
        ignoreLink.setBeforeRenderCallback(() -> {
            ignoreLink.setColor(ignoreLink.isHovering()
                    ? LINK_HOVER_COLOR
                    : getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            ignoreLink.setPosition(SIDE_INSET, linkTop());
        });
        ignoreLink.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            cancel();
            return true;
        });

        // 「永不展示」是这一屏唯一的持久化出口，收在右下角。
        LabelWidget neverShowLabel = new LabelWidget(NEVER_SHOW, FontManager.pf12);
        dialog.addChild(neverShowLabel);
        neverShowLabel.setShouldOverrideMouseCursor(true);
        neverShowLabel.setBeforeRenderCallback(() -> {
            neverShowLabel.setColor(neverShowLabel.isHovering()
                    ? LINK_HOVER_COLOR
                    : getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 宽度是上一帧量出来的：文本内容固定，第一帧之后就是定值。
            neverShowLabel.setPosition(
                    Math.max(SIDE_INSET, dialog.getWidth() - SIDE_INSET - neverShowLabel.getWidth()),
                    linkTop());
        });
        neverShowLabel.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            FfmpegNotice.setNeverShow();
            cancel();
            return true;
        });
    }

    private void layoutDialog(Panel dialog) {
        double width = Math.max(CARD_MIN_WIDTH, Math.min(CARD_MAX_WIDTH, getWidth() - 32));
        // 先按这个宽度把正文换行，再由行数决定卡片高度。
        wrapBody(Math.max(1, width - SIDE_INSET * 2 - BODY_PAD * 2));
        // 上限用「屏幕高度 - 28」而不是写死的数：这样卡片一定落在屏幕内。正常窗口下这个上限
        // 根本不会生效，只有极小的窗口才会压到正文行数。
        dialog.setBounds(width, Math.min(Math.max(160.0, contentHeight()),
                Math.max(160.0, getHeight() - 28)));
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1.0 - presentation) * 10.0);
        dialog.setAlpha((float) presentation);
    }

    private void layoutActions(Panel dialog, RoundedButtonWidget download, RoundedButtonWidget install) {
        double total = Math.max(1, dialog.getWidth() - SIDE_INSET * 2);
        double gap = 8;
        double installWidth = Math.max(118, Math.min(150, total * .52));
        double downloadWidth = Math.max(1, total - gap - installWidth);
        double top = buttonsTop();
        // 「前往下载」在左、「一键安装喵！」在右：主行动放惯用手一侧，鼠标停过去最顺手。
        download.setBounds(downloadWidth, BUTTON_HEIGHT);
        download.setPosition(SIDE_INSET, top);
        install.setBounds(installWidth, BUTTON_HEIGHT);
        install.setPosition(SIDE_INSET + downloadWidth + gap, top);
    }

    private static double lineHeight() {
        return (FontManager.pf14 == null ? 13.0 : FontManager.pf14.getFontHeight()) + 3.0;
    }

    /**
     * 状态行文字：每帧由 Label 的取值回调读一次。来源只有两个——浏览器兜底说明（一次性事件，
     * {@code status} 非空就优先它）和安装流程的当前状态。安装线程只写 volatile 字段，这里只读，
     * 跨线程不需要任何锁。
     */
    private String statusText() {
        if (status != null) return status;
        int state = FfmpegInstaller.state();
        if (state == FfmpegInstaller.RUNNING) return STATUS_INSTALLING + progressDots();
        if (state == FfmpegInstaller.SUCCESS) return STATUS_SUCCESS;
        if (state == FfmpegInstaller.FAILED) {
            String reason = FfmpegInstaller.failureReason();
            return reason != null ? reason : "没装上喵…点「前往下载」手动装吧";
        }
        return HINT_IDLE;
    }

    /** 状态行颜色：成功是唯一的绿色系，失败与进行中用橙色，空闲指引跟着主题的次要文字色。 */
    private int statusColor() {
        if (status != null) return STATUS_COLOR;
        int state = FfmpegInstaller.state();
        if (state == FfmpegInstaller.SUCCESS) return SUCCESS_COLOR;
        if (state == FfmpegInstaller.RUNNING || state == FfmpegInstaller.FAILED) return STATUS_COLOR;
        return getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    /** 按 0.4 秒一颗滚动的省略号；不加的话「正在安装」静得像卡死了。 */
    private static String progressDots() {
        long elapsed = System.currentTimeMillis() - FfmpegInstaller.startedAtMillis();
        int count = (int) ((elapsed / 400L) % 4L);
        if (count <= 0) return "";
        StringBuilder dots = new StringBuilder(count);
        for (int i = 0; i < count; i++) dots.append('…');
        return dots.toString();
    }

    /**
     * 界面侧的看门狗：每帧瞄一眼安装状态。装好后把成功文案多留一秒半再自动收起——立刻消失会让人
     * 怀疑有没有真装上；失败则停在原地，让用户改走「前往下载」。收起走 {@link #cancel()}，
     * 与「我才不管呢喵」同一条路。
     */
    private void tickInstallWatchdog() {
        if (resolved) return;
        if (FfmpegInstaller.state() != FfmpegInstaller.SUCCESS) return;
        if (successSeenAt < 0L) {
            successSeenAt = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - successSeenAt >= 1600L) {
            cancel();
        }
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
        return linkTop() + lineHeight() + CARD_BOTTOM_PAD;
    }

    /**
     * 把正文按给定宽度换行。
     *
     * <p>字形是按需上传的，没上传的字宽度为 0 —— 那样换行会把整段算成一行。项目里其它需要测量
     * 文字的地方（{@link ConfirmationOverlay}、{@link ModelInstallNoticeOverlay}）也是这个套路：
     * 字形没齐之前每帧重算，照常绘制会把字形逐步上传，齐了之后再按宽度缓存结果。</p>
     */
    private void wrapBody(double contentWidth) {
        boolean glyphsReady = FontManager.pf14 != null && FontManager.pf14.areGlyphsLoaded(BODY);
        if (!wrappedWithLoadedGlyphs || Math.abs(contentWidth - wrappedForWidth) >= .5) {
            wrappedForWidth = contentWidth;
            wrappedWithLoadedGlyphs = glyphsReady;
            String[] lines;
            try {
                lines = FontManager.pf14.fitWidth(BODY, contentWidth);
            } catch (Throwable ignored) {
                // 换行只是排版，任何异常都不该让一个提示窗打不开。
                lines = new String[]{BODY};
            }
            if (lines == null || lines.length == 0) lines = new String[]{BODY};
            if (lines.length > MAX_BODY_LINES) {
                String[] limited = new String[MAX_BODY_LINES];
                System.arraycopy(lines, 0, limited, 0, MAX_BODY_LINES);
                limited[MAX_BODY_LINES - 1] = FontManager.pf14.trim(
                        limited[MAX_BODY_LINES - 1], Math.max(1, contentWidth - 8)) + "…";
                lines = limited;
            }
            bodyLines = lines;
        }
    }

    /**
     * 「前往下载」：先尝试打开系统浏览器，打不开就把地址放进剪贴板并把说明显示出来。
     *
     * <p>先用反射走 {@code java.awt.Desktop}（和原版 {@code GuiScreen.openWebLink} 同一条路：
     * 反射是为了不在错误的时机把 AWT 类加载进来），失败再退到 LWJGL 的 {@code Sys.openURL}。</p>
     */
    private void openDownloadPage() {
        if (browse(FfmpegNotice.DOWNLOAD_URL)) {
            cancel();
            return;
        }
        // 打不开浏览器（无桌面环境 / 被沙箱拦住）时把地址塞进剪贴板，别让用户照着抄。
        KeyboardUtils.setClipboardString(FfmpegNotice.DOWNLOAD_URL);
        status = "打不开浏览器喵…地址已复制到剪贴板：" + FfmpegNotice.DOWNLOAD_URL;
    }

    private static boolean browse(String url) {
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop").invoke(null);
            desktopClass.getMethod("browse", URI.class).invoke(desktop, new URI(url));
            return true;
        } catch (Throwable ignored) {
        }
        try {
            return com.muoniumplayer.compat.lwjgl2.Sys.openURL(url);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
