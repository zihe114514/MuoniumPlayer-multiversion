package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.FfmpegInstaller;
import com.muoniumplayer.core.ncm.music.FfmpegNotice;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.party.ui.PartyIcons;

import java.net.URI;

/**
 * Windows 缺少 FFmpeg 时的 Skija 提示；宿主只负责尺寸、输入与退场后的返回。
 * 安装与复测由 FfmpegInstaller 的后台流程拥有，关闭提示不会取消安装。
 */
public final class FfmpegNoticeOverlay extends NCMPanel {

    private static final String HEADING = "为音乐补齐解码能力";
    private static final String BODY =
            "不装也能正常听歌喵！只有动态封面会退回静态图；B站解析和无缝交接有纯 Java 兜底，只是慢一点点喵~";
    private static final String NEVER_SHOW = "永不展示";
    private static final String DOWNLOAD = "前往下载";
    private static final String INSTALL = "一键安装喵！";
    private static final String IGNORE = "我才不管呢喵！";
    private static final String HINT_IDLE = "点「一键安装喵！」自动下载安装，需要联网（约 38MB）";
    private static final String STATUS_CONNECTING = "正在下载 ffmpeg 喵";
    private static final String STATUS_DOWNLOADING = "正在下载 ffmpeg 喵 {0}%";
    private static final String STATUS_INSTALLING = "下载完成，正在安装喵";
    private static final String STATUS_SUCCESS = "装好了喵！已经检测到 ffmpeg，动态封面可以用了";

    private static final double SIDE_INSET = 24.0;
    private static final double CARD_MAX_WIDTH = 624.0;
    private static final double FOOTER_HEIGHT = 112.0;
    private static final int MAX_BODY_LINES = 5;

    private boolean closing;
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final SheetClock clock = new SheetClock();
    private double presentation;
    private boolean resolved;
    private String status;
    private long successSeenAt = -1L;

    private String[] bodyLines = new String[0];
    private String wrappedBody;
    private double wrappedForWidth = -1.0;
    private long wrappedBodyRevision = -1L;
    private boolean wrappedWithLoadedGlyphs;
    private String[] statusLines = new String[0];
    private String wrappedStatus;
    private double wrappedStatusWidth = -1.0;
    private long wrappedStatusRevision = -1L;
    private boolean wrappedStatusGlyphs;

    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public boolean shouldClose() {
        return clock.isGone();
    }

    /** 只收起本次提示；安装线程及启动探测闸门仍由各自的拥有者维护。 */
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
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        layout.render(this, mouseX, mouseY, dWheel, (x, y, wheel) -> {
            super.renderWidget(x, y, 0);
            if (wheel != 0 && !isDismissing()) super.onDWheelReceived(x, y, wheel);
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
        mask.setClickable(false);
        mask.setColor(0x000000);
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .48));
        });
        // 一帧只推一次；异步安装完成与 resize 都不重开进场时钟。
        setBeforeRenderCallback(() -> presentation = TransitionCurveCache.MOTION.at(clock.tick()));
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
        RoundedRectWidget background = NcmExpressive.surface(dialog, 0, 0,
                dialog::getWidth, dialog::getHeight, NcmExpressive.DIALOG_RADIUS,
                () -> NcmExpressivePalette.live().surface());
        background.setGlassSheet(GlassLook.Surface.PLAYER);
        background.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1));

        // 矮窗口收紧页眉，不缩放字体；宿主的绘制与命中继续使用同一布局轴。
        NcmExpressive.medallion(dialog, PartyIcons.ALBUM, () -> 55,
                () -> compact() ? 36 : 55, () -> compact() ? 46 : 62,
                () -> NcmExpressivePalette.live().primaryContainer(),
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(dialog, () -> "解码支持 / FFmpeg", FontManager.pf25,
                () -> compact() ? 89 : 102, () -> compact() ? 35 : 54,
                () -> Math.max(1, dialog.getWidth() - (compact() ? 169 : 182)),
                () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(dialog, this::headingText, FontManager.pf40bold,
                () -> SIDE_INSET, () -> compact() ? 74 : 115,
                () -> innerWidth(dialog), () -> NcmExpressivePalette.live().text());
        NcmExpressive.hitSurface(dialog, () -> dialog.getWidth() - 66,
                () -> compact() ? 15 : 34, () -> 42, () -> 42, 21,
                () -> NcmExpressivePalette.live().container(), this::cancel);
        NcmExpressive.icon(dialog, PartyIcons.CLOSE, NcmSheet.ICON_M,
                () -> dialog.getWidth() - 45, () -> compact() ? 36 : 55,
                () -> NcmExpressivePalette.live().muted());

        // 说明独立滚动，页脚始终可点。原版宿主不转发滚轮，因此另给显式翻阅按钮。
        ScrollPanel scroller = new ScrollPanel();
        dialog.addChild(scroller);
        scroller.setSpacing(0).setScrollStrength(30).setIndicatorInsideViewport();
        Panel content = new Panel();
        scroller.addChild(content);
        content.setClickable(false);
        scroller.setBeforeRenderCallback(() -> {
            scroller.setBounds(dialog.getWidth(), Math.max(1, dialog.getHeight() - headerHeight() - footerHeight(dialog)));
            scroller.setPosition(0, headerHeight());
            content.setBounds(dialog.getWidth(), cardTop() + cardHeight(content) + 12);
        });
        for (int index = 0; index < MAX_BODY_LINES; index++) {
            final int line = index;
            NcmExpressive.label(content, () -> line < bodyLines.length ? bodyLines[line] : "",
                    FontManager.pf32, () -> SIDE_INSET, () -> 12 + line * lineHeight(),
                    () -> innerWidth(dialog), () -> NcmExpressivePalette.live().muted());
        }

        NcmExpressive.surface(content, () -> SIDE_INSET, this::cardTop,
                () -> innerWidth(dialog), () -> cardHeight(content), NcmExpressive.CARD_RADIUS,
                () -> NcmExpressivePalette.live().container());
        NcmExpressive.label(content, this::cardHeading, FontManager.pf28bold,
                () -> SIDE_INSET + 20, () -> cardTop() + 23,
                () -> innerWidth(dialog) - 40, () -> NcmExpressivePalette.live().containerInk());
        NcmExpressive.label(content, this::progressText, FontManager.pf50bold,
                () -> SIDE_INSET + 20, () -> cardTop() + 57,
                () -> innerWidth(dialog) - 108, this::statusColor);
        NcmExpressive.loading(content, () -> dialog.getWidth() - SIDE_INSET - 42,
                () -> cardTop() + 58, () -> running() ? 40 : 0,
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.medallion(content, PartyIcons.INFO,
                () -> dialog.getWidth() - SIDE_INSET - 42, () -> cardTop() + 57,
                () -> FfmpegInstaller.state() == FfmpegInstaller.IDLE ? 48 : 0,
                () -> NcmExpressivePalette.live().primaryContainer(),
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.medallion(content, PartyIcons.CHECK,
                () -> dialog.getWidth() - SIDE_INSET - 42, () -> cardTop() + 57,
                () -> FfmpegInstaller.state() == FfmpegInstaller.SUCCESS ? 48 : 0,
                () -> NcmExpressivePalette.live().primaryContainer(),
                () -> NcmExpressivePalette.live().success());
        NcmExpressive.medallion(content, PartyIcons.WARNING,
                () -> dialog.getWidth() - SIDE_INSET - 42, () -> cardTop() + 57,
                () -> FfmpegInstaller.state() == FfmpegInstaller.FAILED ? 48 : 0,
                () -> NcmExpressivePalette.live().primaryContainer(),
                () -> NcmExpressivePalette.live().error());
        Panel statusContent = new Panel();
        content.addChild(statusContent);
        statusContent.setClickable(false);
        statusContent.setBeforeRenderCallback(() -> {
            while (statusContent.getChildren().size() < statusLines.length) {
                final int line = statusContent.getChildren().size();
                NcmExpressive.label(statusContent, () -> line < statusLines.length ? statusLines[line] : "",
                        FontManager.pf25, () -> 0, () -> 9 + line * captionHeight(),
                        () -> statusContent.getWidth(), this::statusColor);
            }
            statusContent.setBounds(innerWidth(dialog) - (running() ? 40 : 104), statusLines.length * captionHeight());
            statusContent.setPosition(SIDE_INSET + 20, cardTop() + (running() ? 80 : 46));
        });
        NcmExpressive.progress(content, () -> SIDE_INSET + 20,
                () -> cardTop() + cardHeight(content) - 16,
                () -> running() ? innerWidth(dialog) - 40 : 0, this::downloadProgress, this::running);

        NcmExpressive.action(dialog, PartyIcons.LINK, () -> DOWNLOAD, NcmExpressive.ActionStyle.TONAL,
                () -> SIDE_INSET, () -> actionTop(dialog),
                () -> showInstallActions() ? actionWidth(dialog) : 0,
                () -> showInstallActions() ? NcmExpressive.BUTTON_HEIGHT : 0, this::openDownloadPage);
        NcmExpressive.action(dialog, PartyIcons.SYNC,
                () -> FfmpegInstaller.state() == FfmpegInstaller.FAILED ? "重试安装" : INSTALL,
                NcmExpressive.ActionStyle.PRIMARY,
                () -> stacked(dialog) ? SIDE_INSET : dialog.getWidth() - SIDE_INSET - actionWidth(dialog),
                () -> actionTop(dialog) + (stacked(dialog) ? NcmExpressive.BUTTON_HEIGHT + 10 : 0),
                () -> showInstallActions() ? actionWidth(dialog) : 0,
                () -> showInstallActions() ? NcmExpressive.BUTTON_HEIGHT : 0, this::startInstall);
        NcmExpressive.action(dialog, "", () -> "稍后查看", NcmExpressive.ActionStyle.TONAL,
                () -> dialog.getWidth() - SIDE_INSET - Math.min(188, innerWidth(dialog)),
                () -> actionTop(dialog),
                () -> running() ? Math.min(188, innerWidth(dialog)) : 0,
                () -> running() ? NcmExpressive.BUTTON_HEIGHT : 0, this::cancel);
        addReadControls(dialog, scroller, content);

        LabelWidget ignore = NcmExpressive.label(dialog,
                () -> running() ? "关闭提示不会取消后台安装" : FfmpegInstaller.state() == FfmpegInstaller.SUCCESS
                        ? "约 2.6 秒后自动收起 · 无需重启" : IGNORE,
                FontManager.pf25, () -> SIDE_INSET, () -> dialog.getHeight() - 26,
                () -> innerWidth(dialog) - (showInstallActions() ? 88 : 0),
                () -> NcmExpressivePalette.live().muted());
        ignore.setClickable(true).setShouldOverrideMouseCursor(true);
        ignore.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            cancel();
            return true;
        });
        LabelWidget never = NcmExpressive.rightAligned(dialog, () -> showInstallActions() ? NEVER_SHOW : "",
                FontManager.pf25, () -> dialog.getWidth() - SIDE_INSET,
                () -> dialog.getHeight() - 26, () -> 80,
                () -> NcmExpressivePalette.live().muted());
        never.setClickable(true).setShouldOverrideMouseCursor(true);
        dialog.setBeforeRenderCallback(() -> {
            layoutDialog(dialog);
            tickInstallWatchdog();
            never.setClickable(showInstallActions()).setShouldOverrideMouseCursor(showInstallActions());
        });
        never.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            FfmpegNotice.setNeverShow();
            cancel();
            return true;
        });
    }

    private void layoutDialog(Panel dialog) {
        double width = NcmExpressive.dialogWidth(this, CARD_MAX_WIDTH);
        dialog.setBounds(width, NcmExpressive.dialogHeight(this, 584));
        wrapBody(Math.max(1, width - SIDE_INSET * 2));
        wrapStatus(Math.max(1, width - SIDE_INSET * 2 - (running() ? 40 : 104)));
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1.0 - presentation) * 12.0);
        dialog.setAlpha((float) presentation);
    }

    private void addReadControls(Panel dialog, ScrollPanel scroller, Panel content) {
        NcmExpressive.action(dialog, PartyIcons.CHEVRON_LEFT, () -> "上页", NcmExpressive.ActionStyle.NEUTRAL,
                () -> SIDE_INSET, () -> dialog.getHeight() - footerHeight(dialog) + 4,
                () -> readReserve(dialog) > 0 ? 80 : 0,
                () -> readReserve(dialog) > 0 ? 28 : 0,
                () -> scroller.scrollToOffsetImmediately(scroller.targetScrollOffset - Math.max(32, scroller.getHeight() * .8)));
        NcmExpressive.action(dialog, PartyIcons.CHEVRON_RIGHT, () -> "下页", NcmExpressive.ActionStyle.NEUTRAL,
                () -> dialog.getWidth() - SIDE_INSET - 80, () -> dialog.getHeight() - footerHeight(dialog) + 4,
                () -> readReserve(dialog) > 0 ? 80 : 0,
                () -> readReserve(dialog) > 0 ? 28 : 0,
                () -> scroller.scrollToOffsetImmediately(scroller.targetScrollOffset + Math.max(32, scroller.getHeight() * .8)));
    }

    private boolean compact() { return getHeight() < 540; }
    private double headerHeight() { return compact() ? 96 : 144; }
    private double cardTop() { return bodyLines.length * lineHeight() + 12; }
    private double cardHeight(Panel content) { return Math.max(running() ? 132 : 112,
            (running() ? 104 : 72) + statusLines.length * captionHeight()); }
    private static double innerWidth(Panel dialog) { return Math.max(1, dialog.getWidth() - SIDE_INSET * 2); }
    private static boolean stacked(Panel dialog) { return dialog.getWidth() < 380; }
    private double baseFooterHeight(Panel dialog) { return stacked(dialog) && showInstallActions() ? 172 : FOOTER_HEIGHT; }
    private double readReserve(Panel dialog) {
        return cardTop() + cardHeight(dialog) + 12 > dialog.getHeight() - headerHeight() - baseFooterHeight(dialog) ? 36 : 0;
    }
    private double footerHeight(Panel dialog) { return baseFooterHeight(dialog) + readReserve(dialog); }
    private double actionTop(Panel dialog) { return dialog.getHeight() - baseFooterHeight(dialog) + 12; }
    private static double actionWidth(Panel dialog) { return stacked(dialog) ? innerWidth(dialog) : Math.max(1, (innerWidth(dialog) - 12) * .5); }
    private static double lineHeight() { return Math.max(24, FontManager.pf32 == null ? 24 : FontManager.pf32.getFontHeight() + 6); }
    private static double captionHeight() { return Math.max(18, FontManager.pf25 == null ? 18 : FontManager.pf25.getFontHeight() + 5); }
    private boolean running() { return FfmpegInstaller.state() == FfmpegInstaller.RUNNING; }
    private boolean showInstallActions() { return !running() && FfmpegInstaller.state() != FfmpegInstaller.SUCCESS; }

    private String headingText() {
        int state = FfmpegInstaller.state();
        if (state == FfmpegInstaller.RUNNING) return "正在安装 FFmpeg";
        if (state == FfmpegInstaller.SUCCESS) return "FFmpeg 已准备好";
        if (state == FfmpegInstaller.FAILED) return "这次安装没有完成";
        return HEADING;
    }

    private String bodyText() {
        int state = FfmpegInstaller.state();
        if (state == FfmpegInstaller.RUNNING) return "关闭提示不会取消后台安装，装好后无需重启。";
        if (state == FfmpegInstaller.SUCCESS) return "已经重新检测到可用的 FFmpeg，动态封面可以用了。";
        if (state == FfmpegInstaller.FAILED) return "仍可正常听歌。检查网络后重试安装，或前往官网下载。";
        return BODY;
    }

    private String cardHeading() {
        int state = FfmpegInstaller.state();
        if (state == FfmpegInstaller.RUNNING) return FfmpegInstaller.progressPercent() >= 100 ? "安装与复测" : "正在下载安装包";
        if (state == FfmpegInstaller.SUCCESS) return "安装完成";
        if (state == FfmpegInstaller.FAILED) return "可以重新尝试";
        return "一键下载并安装";
    }

    private String progressText() {
        if (!running()) return "";
        int percent = FfmpegInstaller.progressPercent();
        return percent < 0 ? "连接中" : percent >= 100 ? "复测中" : percent + "%";
    }

    private double downloadProgress() {
        int percent = FfmpegInstaller.progressPercent();
        return percent < 0 ? -1 : percent / 100.0;
    }

    private void startInstall() {
        if (FfmpegInstaller.state() == FfmpegInstaller.RUNNING) return;
        status = null;
        successSeenAt = -1L;
        FfmpegInstaller.start();
    }

    /** 浏览器兜底事件优先；安装状态只消费后台线程发布的真实结论。 */
    private String statusText() {
        if (status != null) return status;
        int state = FfmpegInstaller.state();
        if (state == FfmpegInstaller.RUNNING) {
            int percent = FfmpegInstaller.progressPercent();
            if (percent < 0) return STATUS_CONNECTING + progressDots();
            if (percent >= 100) return STATUS_INSTALLING + progressDots();
            return STATUS_DOWNLOADING.replace("{0}", Integer.toString(percent));
        }
        if (state == FfmpegInstaller.SUCCESS) return STATUS_SUCCESS;
        if (state == FfmpegInstaller.FAILED) {
            String reason = FfmpegInstaller.failureReason();
            return reason != null ? reason : "没装上喵…重试或「前往下载」";
        }
        return HINT_IDLE;
    }

    private int statusColor() {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        if (status != null) return palette.warning();
        int state = FfmpegInstaller.state();
        if (state == FfmpegInstaller.SUCCESS) return palette.success();
        if (state == FfmpegInstaller.FAILED) return palette.error();
        if (state == FfmpegInstaller.RUNNING) return palette.containerInk();
        return palette.muted();
    }

    private static String progressDots() {
        long elapsed = System.currentTimeMillis() - FfmpegInstaller.startedAtMillis();
        int count = (int) ((elapsed / 400L) % 4L);
        if (count <= 0) return "";
        StringBuilder dots = new StringBuilder(count);
        for (int i = 0; i < count; i++) dots.append('…');
        return dots.toString();
    }

    /** SUCCESS 由安装器完成真正复测后发布；保留 2.6 秒确认感再走同一退场出口。 */
    private void tickInstallWatchdog() {
        if (resolved) return;
        if (FfmpegInstaller.state() != FfmpegInstaller.SUCCESS) return;
        if (successSeenAt < 0L) {
            successSeenAt = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - successSeenAt >= 2600L) {
            cancel();
        }
    }

    private void wrapBody(double width) {
        String source = bodyText();
        CFontRenderer font = FontManager.pf32;
        long revision = font == null ? -1 : font.metricsRevision();
        boolean ready = font != null && font.areGlyphsLoaded(source);
        if (!wrappedWithLoadedGlyphs || !source.equals(wrappedBody)
                || revision != wrappedBodyRevision || Math.abs(width - wrappedForWidth) >= .5) {
            bodyLines = wrap(font, source, width, MAX_BODY_LINES);
            wrappedBody = source;
            wrappedForWidth = width;
            wrappedBodyRevision = revision;
            wrappedWithLoadedGlyphs = ready;
        }
    }

    private void wrapStatus(double width) {
        String source = statusText();
        CFontRenderer font = FontManager.pf25;
        long revision = font == null ? -1 : font.metricsRevision();
        boolean ready = font != null && font.areGlyphsLoaded(source);
        if (!wrappedStatusGlyphs || !source.equals(wrappedStatus)
                || revision != wrappedStatusRevision || Math.abs(width - wrappedStatusWidth) >= .5) {
            statusLines = wrap(font, source, width, Integer.MAX_VALUE);
            wrappedStatus = source;
            wrappedStatusWidth = width;
            wrappedStatusRevision = revision;
            wrappedStatusGlyphs = ready;
        }
    }

    private static String[] wrap(CFontRenderer font, String source, double width, int maximum) {
        if (font == null) return new String[]{source};
        String[] lines = NcmExpressive.wrap(font, source, width);
        if (lines == null || lines.length == 0) return new String[]{source};
        if (lines.length <= maximum) return lines;
        String[] limited = new String[maximum];
        System.arraycopy(lines, 0, limited, 0, maximum);
        limited[maximum - 1] = font.trim(limited[maximum - 1], Math.max(1, width - 8)) + "…";
        return limited;
    }

    private void openDownloadPage() {
        if (browse(FfmpegNotice.DOWNLOAD_URL)) {
            cancel();
            return;
        }
        KeyboardUtils.setClipboardString(FfmpegNotice.DOWNLOAD_URL);
        status = "打不开浏览器喵，下载地址已复制到剪贴板";
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
