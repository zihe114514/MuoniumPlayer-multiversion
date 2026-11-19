package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.FoliaModelManager;
import com.muoniumplayer.core.ncm.music.FoliaModelNotice;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.List;

/**
 * 首次打开播放器时的本地分析组件提示。
 * 清单与启动闸门来自 FoliaModelNotice，安装沿用缺失组件的串行后台队列。
 */
public final class ModelInstallNoticeOverlay extends NCMPanel {

    private static final FoliaModelManager.Kind[] KINDS = {
            FoliaModelManager.Kind.BEAT_THIS,
            FoliaModelManager.Kind.HTDEMUCS,
            // 安卓展示 ONNX 运行时（Python 装不了），桌面展示 Python。
            FoliaModelManager.runtimeKind()
    };
    private static final String INFO =
            "智能过渡在本机分析节拍并分离人声。\n"
                    + "不安装也能正常播放，切歌时退回原版智能淡化。";
    private static final String TITLE = "安装 Folia 分析模型";
    private static final String LATER = "稍后再说";
    private static final String INSTALL = "全部安装";
    private static final String INSTALLING = "正在安装…";
    private static final double SIDE_INSET = 24.0;
    private static final double CARD_MAX_WIDTH = 650.0;
    private static final double CARD_MAX_HEIGHT = 740.0;
    private static final double HEADER_HEIGHT = 104.0;
    private static final double ROW_HEIGHT = 84.0;
    private static final double ROW_GAP = 10.0;
    private static final int MAX_INFO_LINES = 5;

    private boolean closing;
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final SheetClock clock = new SheetClock();
    private double presentation;
    private boolean resolved;

    /** 下载回调在后台线程上发布；关闭浮层不会中断队列。 */
    private volatile boolean installing;
    private volatile boolean statusError;
    private volatile String status = "下载后会校验文件大小与 SHA-256，失败会自动切换镜像重试。";

    private String[] infoLines = new String[0];
    private double wrappedForWidth = -1.0;
    private long wrappedInfoRevision = -1L;
    private boolean wrappedWithLoadedGlyphs;
    private String[] statusLines = new String[0];
    private String wrappedStatus;
    private double wrappedStatusWidth = -1.0;
    private long wrappedStatusRevision = -1L;
    private boolean wrappedStatusGlyphs;
    private final boolean[] ready = new boolean[KINDS.length];
    private final String[] rowStatus = new String[KINDS.length];
    private final String[][] rowStatusLines = new String[KINDS.length][];
    private final double[] rowHeights = new double[KINDS.length];
    private String[] pathLines = new String[0];

    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public boolean shouldClose() {
        return clock.isGone();
    }

    /** 只收起界面；不取消安装，不改变设置或本次启动的提示闸门。 */
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
        dialog.setBeforeRenderCallback(() -> layoutDialog(dialog));
        RoundedRectWidget background = NcmExpressive.surface(dialog, 0, 0,
                dialog::getWidth, dialog::getHeight, NcmExpressive.DIALOG_RADIUS,
                () -> NcmExpressivePalette.live().surface());
        background.setGlassSheet(GlassLook.Surface.PLAYER);
        background.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1));

        NcmExpressive.medallion(dialog, PartyIcons.ALBUM, () -> 46,
                () -> 33, () -> 44,
                () -> NcmExpressivePalette.live().primaryContainer(),
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(dialog, () -> "Folia / 本地节拍分析", FontManager.pf25,
                () -> 80, () -> 33,
                () -> Math.max(1, dialog.getWidth() - 154),
                () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(dialog, this::titleText, FontManager.pf40bold,
                () -> SIDE_INSET, () -> 78,
                () -> innerWidth(dialog), () -> NcmExpressivePalette.live().text());
        NcmExpressive.hitSurface(dialog, () -> dialog.getWidth() - 66,
                () -> 12, () -> 42, () -> 42, 21,
                () -> NcmExpressivePalette.live().container(), this::cancel);
        NcmExpressive.icon(dialog, PartyIcons.CLOSE, NcmSheet.ICON_M,
                () -> dialog.getWidth() - 45, () -> 33,
                () -> NcmExpressivePalette.live().muted());

        // 放不下时只滚内容，操作区固定在卡片底部。单一内容节点在布局前定高，避免首帧错排。
        ScrollPanel scroller = new ScrollPanel();
        dialog.addChild(scroller);
        scroller.setSpacing(0).setScrollStrength(30).setIndicatorInsideViewport();
        Panel content = new Panel();
        scroller.addChild(content);
        content.setClickable(false);
        scroller.setBeforeRenderCallback(() -> {
            scroller.setBounds(dialog.getWidth(), Math.max(1, dialog.getHeight() - headerHeight() - footerHeight(dialog)));
            scroller.setPosition(0, headerHeight());
            content.setBounds(dialog.getWidth(), contentHeight());
        });
        for (int index = 0; index < MAX_INFO_LINES; index++) {
            final int line = index;
            NcmExpressive.label(content, () -> line < infoLines.length ? infoLines[line] : "",
                    FontManager.pf32, () -> SIDE_INSET, () -> lineHeight() * (.5 + line),
                    () -> innerWidth(dialog), () -> NcmExpressivePalette.live().muted());
        }
        for (int index = 0; index < KINDS.length; index++) {
            final int row = index;
            final FoliaModelManager.Kind kind = KINDS[index];
            NcmExpressive.surface(content, () -> SIDE_INSET, () -> rowTop(row),
                    () -> innerWidth(dialog), () -> rowHeight(row), NcmExpressive.CARD_RADIUS,
                    () -> NcmExpressivePalette.live().container());
            NcmExpressive.medallion(content, PartyIcons.CHECK,
                    () -> SIDE_INSET + 33, () -> rowTop(row) + rowHeight(row) * .5,
                    () -> ready[row] ? 42 : 0,
                    () -> NcmExpressivePalette.live().primaryContainer(),
                    () -> NcmExpressivePalette.live().success());
            NcmExpressive.medallion(content, kind == FoliaModelManager.Kind.BEAT_THIS ? PartyIcons.ALBUM : PartyIcons.FOLDER_OPEN,
                    () -> SIDE_INSET + 33, () -> rowTop(row) + rowHeight(row) * .5,
                    () -> ready[row] ? 0 : 42,
                    () -> NcmExpressivePalette.live().primaryContainer(),
                    () -> NcmExpressivePalette.live().primary());
            NcmExpressive.label(content, () -> rowTitle(kind),
                    FontManager.pf28bold, () -> SIDE_INSET + 66, () -> rowTop(row) + 20,
                    () -> innerWidth(dialog) - 82, () -> NcmExpressivePalette.live().containerInk());
            NcmExpressive.label(content,
                    () -> rowDetail(kind),
                    FontManager.pf25, () -> SIDE_INSET + 66, () -> rowTop(row) + 40,
                    () -> innerWidth(dialog) - 82, () -> NcmExpressivePalette.live().muted());
            // 百分比仅由管理器标签提供；它没有数值进度 API，不解析标签或臆造进度。
            Panel rowDetails = new Panel();
            content.addChild(rowDetails);
            rowDetails.setClickable(false);
            rowDetails.setBeforeRenderCallback(() -> {
                String[] lines = rowStatusLines[row];
                int count = lines == null ? 0 : lines.length;
                while (rowDetails.getChildren().size() < count) {
                    final int line = rowDetails.getChildren().size();
                    NcmExpressive.label(rowDetails,
                            () -> rowStatusLines[row] != null && line < rowStatusLines[row].length ? rowStatusLines[row][line] : "",
                            FontManager.pf25, () -> 0, () -> captionHeight() * (.5 + line),
                            rowDetails::getWidth,
                            () -> ready[row] ? NcmExpressivePalette.live().success() : NcmExpressivePalette.live().muted());
                }
                rowDetails.setBounds(Math.max(1, innerWidth(dialog) - 82), count * captionHeight());
                rowDetails.setPosition(SIDE_INSET + 66, rowTop(row) + 56);
            });
        }
        NcmExpressive.label(content, () -> "仅补充缺失组件 · 沿用当前模型目录", FontManager.pf25,
                () -> SIDE_INSET, () -> pathTop() - 12,
                () -> innerWidth(dialog), () -> NcmExpressivePalette.live().muted());
        Panel pathDetails = new Panel();
        content.addChild(pathDetails);
        pathDetails.setClickable(false);
        pathDetails.setBeforeRenderCallback(() -> {
            while (pathDetails.getChildren().size() < pathLines.length) {
                final int line = pathDetails.getChildren().size();
                NcmExpressive.label(pathDetails, () -> line < pathLines.length ? pathLines[line] : "",
                        FontManager.pf25, () -> 0, () -> captionHeight() * (.5 + line), pathDetails::getWidth,
                        () -> NcmExpressivePalette.live().muted());
            }
            pathDetails.setBounds(innerWidth(dialog), pathLines.length * captionHeight());
            pathDetails.setPosition(SIDE_INSET, pathTop());
        });

        NcmExpressive.surface(content, () -> SIDE_INSET, this::statusTop,
                () -> innerWidth(dialog), this::statusHeight, NcmExpressive.CARD_RADIUS,
                () -> NcmExpressivePalette.live().container());
        NcmExpressive.icon(content, () -> statusError ? PartyIcons.WARNING : allReady() ? PartyIcons.CHECK : PartyIcons.INFO,
                NcmSheet.ICON_M, () -> SIDE_INSET + 22,
                () -> statusTop() + (statusHeight() - (installing ? 18 : 0)) * .5,
                () -> statusError ? NcmExpressivePalette.live().error() : allReady()
                        ? NcmExpressivePalette.live().success() : NcmExpressivePalette.live().primary());
        Panel statusDetails = new Panel();
        content.addChild(statusDetails);
        statusDetails.setClickable(false);
        statusDetails.setBeforeRenderCallback(() -> {
            while (statusDetails.getChildren().size() < statusLines.length) {
                final int line = statusDetails.getChildren().size();
                NcmExpressive.label(statusDetails, () -> line < statusLines.length ? statusLines[line] : "",
                        FontManager.pf25, () -> 0, () -> captionHeight() * (.5 + line), statusDetails::getWidth,
                        () -> statusError ? NcmExpressivePalette.live().error() : allReady()
                                ? NcmExpressivePalette.live().success() : NcmExpressivePalette.live().muted());
            }
            double textHeight = statusLines.length * captionHeight();
            statusDetails.setBounds(Math.max(1, innerWidth(dialog) - 58), textHeight);
            statusDetails.setPosition(SIDE_INSET + 42,
                    statusTop() + (statusHeight() - (installing ? 18 : 0) - textHeight) * .5);
        });
        NcmExpressive.progress(content, () -> SIDE_INSET + 16,
                () -> statusTop() + statusHeight() - 14,
                () -> installing ? innerWidth(dialog) - 32 : 0, () -> -1, () -> installing);

        NcmExpressive.action(dialog, "", () -> installing ? "稍后查看" : allReady() ? "完成" : LATER,
                NcmExpressive.ActionStyle.TONAL, () -> SIDE_INSET,
                () -> actionTop(dialog), () -> allReady() && !installing ? innerWidth(dialog) : actionWidth(dialog),
                () -> NcmExpressive.BUTTON_HEIGHT, this::cancel);
        NcmExpressive.action(dialog, PartyIcons.SYNC,
                () -> installing ? INSTALLING : statusError ? "重新安装" : INSTALL,
                NcmExpressive.ActionStyle.PRIMARY,
                () -> stacked(dialog) ? SIDE_INSET : dialog.getWidth() - SIDE_INSET - actionWidth(dialog),
                () -> actionTop(dialog) + (stacked(dialog) ? NcmExpressive.BUTTON_HEIGHT + 10 : 0),
                () -> allReady() && !installing ? 0 : actionWidth(dialog),
                () -> allReady() && !installing ? 0 : NcmExpressive.BUTTON_HEIGHT, this::installMissing);
        addReadControls(dialog, scroller);
        NcmExpressive.label(dialog,
                () -> installing ? "关闭提示不会取消后台安装" : "一次只下载一个组件 · 仅补充缺失项",
                FontManager.pf25, () -> SIDE_INSET, () -> dialog.getHeight() - 16,
                () -> innerWidth(dialog), () -> NcmExpressivePalette.live().muted());
    }

    private void layoutDialog(Panel dialog) {
        // 尺寸只读适配器给本浮层的虚拟视口；缩放与输入逆变换由宿主统一处理。
        double width = NcmExpressive.dialogWidth(this, CARD_MAX_WIDTH);
        dialog.setBounds(width, dialog.getHeight());
        wrapInfo(innerWidth(dialog));
        wrapStatus(Math.max(1, innerWidth(dialog) - 58));
        for (int index = 0; index < KINDS.length; index++) {
            FoliaModelManager.Kind kind = KINDS[index];
            ready[index] = installedQuietly(kind);
            rowStatus[index] = FoliaModelManager.statusLabel(kind);
            rowStatusLines[index] = wrap(FontManager.pf25, rowStatus[index], Math.max(1, innerWidth(dialog) - 82), Integer.MAX_VALUE);
            rowHeights[index] = Math.max(ROW_HEIGHT, 66 + rowStatusLines[index].length * captionHeight());
        }
        pathLines = wrap(FontManager.pf25, FoliaModelManager.directoryLabel(), innerWidth(dialog), Integer.MAX_VALUE);
        // 先按真实折行/安装状态定内容高，再定视口。正常两行组件无需翻页，长错误只滚正文。
        double preferred = headerHeight() + contentHeight() + baseFooterHeight(dialog);
        dialog.setBounds(width, NcmExpressive.dialogHeight(this, Math.min(CARD_MAX_HEIGHT, preferred)));
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1.0 - presentation) * 12.0);
        dialog.setAlpha((float) presentation);
    }

    private void addReadControls(Panel dialog, ScrollPanel scroller) {
        // 宿主先读走滚轮；按钮让被裁的资源行与失败详情仍可完整翻阅。
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

    private static double headerHeight() { return HEADER_HEIGHT; }
    private static double innerWidth(Panel dialog) { return Math.max(1, dialog.getWidth() - SIDE_INSET * 2); }
    private static boolean stacked(Panel dialog) { return dialog.getWidth() < 380; }
    private double baseFooterHeight(Panel dialog) { return stacked(dialog) && !(allReady() && !installing) ? 148 : 88; }
    private double readReserve(Panel dialog) {
        return contentHeight() > dialog.getHeight() - headerHeight() - baseFooterHeight(dialog) + .5 ? 36 : 0;
    }
    private double footerHeight(Panel dialog) { return baseFooterHeight(dialog) + readReserve(dialog); }
    private double actionTop(Panel dialog) { return dialog.getHeight() - baseFooterHeight(dialog) + 6; }
    private static double actionWidth(Panel dialog) { return stacked(dialog) ? innerWidth(dialog) : (innerWidth(dialog) - 12) * .5; }
    private static double lineHeight() { return Math.max(24, FontManager.pf32 == null ? 24 : FontManager.pf32.getFontHeight() + 6); }
    private static double captionHeight() { return Math.max(18, FontManager.pf25 == null ? 18 : FontManager.pf25.getFontHeight() + 5); }
    private double rowHeight(int index) { return rowHeights[index] > 0 ? rowHeights[index] : ROW_HEIGHT; }
    private double rowTop(int index) {
        double top = infoLines.length * lineHeight() + 12;
        for (int row = 0; row < index; row++) top += rowHeight(row) + ROW_GAP;
        return top;
    }
    private double pathTop() { return rowTop(KINDS.length) + 22; }
    private double statusTop() { return pathTop() + Math.max(1, pathLines.length) * captionHeight() + 12; }
    private double statusHeight() { return Math.max(50, 20 + Math.max(1, statusLines.length) * captionHeight()) + (installing ? 18 : 0); }
    private double contentHeight() { return statusTop() + statusHeight() + 16; }
    private boolean allReady() {
        for (boolean value : ready) if (!value) return false;
        return true;
    }

    private String titleText() {
        if (installing) return "正在安装分析组件";
        if (statusError) return "模型下载未完成";
        if (allReady()) return "分析组件已准备好";
        return TITLE;
    }

    private static String rowTitle(FoliaModelManager.Kind kind) {
        if (kind == FoliaModelManager.Kind.BEAT_THIS) return "Beat This!";
        if (kind == FoliaModelManager.Kind.HTDEMUCS) return "HTDemucs";
        return runtimeTitle(kind);
    }

    private static String rowDetail(FoliaModelManager.Kind kind) {
        if (kind == FoliaModelManager.Kind.BEAT_THIS) return "beat_this.onnx · 79.2 MiB";
        if (kind == FoliaModelManager.Kind.HTDEMUCS) return "htdemucs.onnx · 103.6 MiB · 桌面还需 Python 运行组件";
        return runtimeDetail(kind);
    }

    private static String runtimeTitle(FoliaModelManager.Kind kind) {
        return kind == FoliaModelManager.Kind.ONNX_RUNTIME ? "ONNX 运行时" : "必要组件（Python）";
    }

    private static String runtimeDetail(FoliaModelManager.Kind kind) {
        return kind == FoliaModelManager.Kind.ONNX_RUNTIME ? "Android · 进程内 ONNX 推理" : "当前桌面平台 · Python 运行组件";
    }

    /** 安装所有还缺的组件，一次只下一个；成功后重新读取缺失清单。 */
    private void installMissing() {
        if (installing) return;
        if (FoliaModelNotice.missing().isEmpty()) {
            status = "所有组件都已就绪。";
            statusError = false;
            cancel();
            return;
        }
        installing = true;
        statusError = false;
        status = "正在准备下载，请保持网络连接。";
        installNext();
    }

    private void installNext() {
        List<FoliaModelManager.Kind> pending = FoliaModelNotice.missing();
        if (pending.isEmpty()) {
            installing = false;
            status = "全部组件安装完成，智能混音已经可用。";
            statusError = false;
            return;
        }
        FoliaModelManager.downloadAsync(pending.get(0), (ok, detail) -> {
            status = detail;
            statusError = !ok;
            if (ok) installNext();
            else installing = false;
        });
    }

    private static boolean installedQuietly(FoliaModelManager.Kind kind) {
        try {
            return FoliaModelManager.isInstalled(kind);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void wrapInfo(double width) {
        CFontRenderer font = FontManager.pf32;
        long revision = font == null ? -1 : font.metricsRevision();
        boolean ready = font != null && font.areGlyphsLoaded(INFO);
        if (!wrappedWithLoadedGlyphs || revision != wrappedInfoRevision || Math.abs(width - wrappedForWidth) >= .5) {
            infoLines = wrap(font, INFO, width, MAX_INFO_LINES);
            wrappedForWidth = width;
            wrappedInfoRevision = revision;
            wrappedWithLoadedGlyphs = ready;
        }
    }

    private void wrapStatus(double width) {
        String source = status == null ? "" : status;
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
        if (font == null) return source == null ? new String[]{""} : source.split("\\n", -1);
        String[] lines = NcmExpressive.wrap(font, source, width);
        if (lines == null || lines.length == 0) return new String[]{source};
        if (lines.length <= maximum) return lines;
        String[] limited = new String[maximum];
        System.arraycopy(lines, 0, limited, 0, maximum);
        limited[maximum - 1] = font.trim(limited[maximum - 1], Math.max(1, width - 8)) + "…";
        return limited;
    }
}
