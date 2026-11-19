package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.download.DownloadCatalog;
import com.muoniumplayer.core.download.DownloadCatalog.Group;
import com.muoniumplayer.core.download.DownloadCatalog.Item;
import com.muoniumplayer.core.download.DownloadCatalog.Status;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

import java.util.ArrayList;
import java.util.List;

/** Download inventory and controls for managed runtime components. */
public final class DownloadManagerOverlay extends NCMPanel {
    private static final double SIDE = 24.0;
    private static final double MAX_WIDTH = 700.0;
    private static final double MAX_HEIGHT = 680.0;
    private static final double HEADER = 116.0;
    private static final double FILTERS_HEIGHT = 112.0;
    private static final double ROW_HEIGHT = 78.0;
    private static final String[] FILTER_LABELS = {"全部", "已安装", "未安装", "任务中"};

    private final SheetClock clock = new SheetClock();
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private boolean closing;
    private double presentation;
    private int filter;
    private Group selectedGroup;
    private long refreshAt;
    private String renderedSignature = "";
    private ScrollPanel rowsHost;
    private ScrollPanel scroller;
    private List<Item> snapshot = DownloadCatalog.snapshot();

    @Override
    public void onInit() {
        RoundedRectWidget scrim = new RoundedRectWidget();
        addChild(scrim);
        scrim.setClickable(false).setColor(0x000000);
        scrim.setBeforeRenderCallback(() -> {
            scrim.setMargin(0);
            scrim.setAlpha((float) (presentation * .46));
        });
        setBeforeRenderCallback(() -> {
            presentation = TransitionCurveCache.MOTION.at(clock.tick());
            if (System.currentTimeMillis() >= refreshAt) {
                snapshot = DownloadCatalog.snapshot();
                refreshAt = System.currentTimeMillis() + 1000L;
            }
        });
        setOnClickCallback((x, y, button) -> {
            if (button == 0) close();
            return true;
        });

        Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> layoutDialog(dialog));
        RoundedRectWidget surface = NcmExpressive.surface(dialog, 0, 0, dialog::getWidth, dialog::getHeight,
                NcmExpressive.DIALOG_RADIUS, () -> NcmExpressivePalette.live().surface());
        surface.setGlassSheet(GlassLook.Surface.PLAYER);
        surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1));

        NcmExpressive.medallion(dialog, PartyIcons.FOLDER_OPEN, () -> 54, () -> 40, () -> 48,
                () -> NcmExpressivePalette.live().primaryContainer(), () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(dialog, () -> "COMPONENTS", FontManager.pf25,
                () -> 92, () -> 34, () -> Math.max(1, dialog.getWidth() - 160),
                () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(dialog, () -> "下载管理", FontManager.pf40bold,
                () -> SIDE, () -> 82, () -> Math.max(1, dialog.getWidth() - SIDE * 2 - 54),
                () -> NcmExpressivePalette.live().text());
        NcmExpressive.hitSurface(dialog, () -> dialog.getWidth() - 66, () -> 16, () -> 42, () -> 42, 21,
                () -> NcmExpressivePalette.live().container(), this::close);
        NcmExpressive.icon(dialog, PartyIcons.CLOSE, NcmSheet.ICON_M,
                () -> dialog.getWidth() - 45, () -> 37, () -> NcmExpressivePalette.live().muted());

        LabelWidget summary = new LabelWidget(this::summaryText, FontManager.pf25);
        dialog.addChild(summary);
        summary.setClickable(false).setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        summary.setBeforeRenderCallback(() -> summary
                .setColor(NcmExpressivePalette.live().muted())
                .setMaxWidth(Math.max(1, dialog.getWidth() - SIDE * 2))
                .setPosition(SIDE, HEADER - 14));

        addFilters(dialog);
        scroller = new ScrollPanel();
        dialog.addChild(scroller);
        scroller.setSpacing(7).setScrollStrength(32).setIndicatorInsideViewport();
        scroller.setBeforeRenderCallback(() -> {
            scroller.setBounds(dialog.getWidth() - SIDE * 2,
                    Math.max(1, dialog.getHeight() - HEADER - FILTERS_HEIGHT - 76));
            scroller.setPosition(SIDE, HEADER + FILTERS_HEIGHT);
            rebuildRowsIfNeeded(scroller);
        });
        NcmExpressive.label(dialog, () -> networkLine(), FontManager.pf25,
                () -> SIDE, () -> dialog.getHeight() - 23,
                () -> Math.max(1, dialog.getWidth() - SIDE * 2),
                () -> DownloadCatalog.waitingTasks() > 0
                        ? NcmExpressivePalette.live().warning() : NcmExpressivePalette.live().muted());
    }

    private void addFilters(Panel dialog) {
        for (int index = 0; index < FILTER_LABELS.length; index++) {
            final int slot = index;
            NcmExpressive.action(dialog, "", () -> FILTER_LABELS[slot], NcmExpressive.ActionStyle.TONAL,
                    () -> SIDE + slot * ((dialog.getWidth() - SIDE * 2) / FILTER_LABELS.length),
                    () -> HEADER + 4,
                    () -> Math.max(1, (dialog.getWidth() - SIDE * 2) / FILTER_LABELS.length - 4),
                    () -> 34.0, () -> filter = slot);
        }

        String[] labels = {"全部", "图形", "字体", "音频", "Folia", "工具"};
        Group[] groups = {null, Group.GRAPHICS, Group.FONTS, Group.AUDIO, Group.AUTOMIX, Group.TOOLS};
        for (int index = 0; index < labels.length; index++) {
            final int slot = index;
            int column = slot % 3;
            int row = slot / 3;
            NcmExpressive.action(dialog, "", () -> labels[slot], NcmExpressive.ActionStyle.NEUTRAL,
                    () -> SIDE + column * ((dialog.getWidth() - SIDE * 2) / 3.0),
                    () -> HEADER + 44 + row * 30,
                    () -> Math.max(1, (dialog.getWidth() - SIDE * 2) / 3.0 - 4),
                    () -> 26.0, () -> selectedGroup = groups[slot]);
        }
    }

    private void rebuildRowsIfNeeded(ScrollPanel host) {
        StringBuilder signature = new StringBuilder().append(filter).append(':').append(selectedGroup).append('|');
        for (Item item : snapshot) signature.append(item.id()).append('=').append(item.status()).append(';');
        String next = signature.toString();
        if (next.equals(renderedSignature) && rowsHost == host) return;
        renderedSignature = next;
        rowsHost = host;
        double offset = host.targetScrollOffset;
        host.getChildren().clear();
        List<Item> visible = filteredItems();
        if (visible.isEmpty()) {
            Panel empty = new Panel();
            host.addChild(empty);
            empty.setBounds(Math.max(1, host.getWidth()), 70);
            NcmExpressive.label(empty, () -> "没有符合筛选条件的组件", FontManager.pf32,
                    () -> 12, () -> 35, () -> Math.max(1, empty.getWidth() - 24),
                    () -> NcmExpressivePalette.live().muted());
        } else {
            for (Item item : visible) addRow(host, item);
        }
        host.scrollToOffsetImmediately(offset);
    }

    private void addRow(ScrollPanel host, Item item) {
        Panel row = new Panel();
        host.addChild(row);
        row.setBounds(Math.max(1, host.getWidth()), ROW_HEIGHT);
        RoundedRectWidget card = new RoundedRectWidget();
        row.addChild(card);
        card.setClickable(false).setRadius(NcmExpressive.CARD_RADIUS);
        card.setBeforeRenderCallback(() -> {
            card.setMargin(0);
            card.setBounds(row.getWidth(), row.getHeight());
            card.setColor(NcmExpressivePalette.live().container());
        });

        NcmExpressive.medallion(row, statusGlyph(item.status()),
                () -> 30, () -> 39, () -> 30,
                () -> NcmExpressivePalette.live().primaryContainer(),
                () -> statusColor(currentStatus(item)));
        LabelWidget title = new LabelWidget(item.name(), FontManager.pf28bold);
        row.addChild(title);
        title.setClickable(false).setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        title.setBeforeRenderCallback(() -> title
                .setColor(NcmExpressivePalette.live().containerInk())
                .setMaxWidth(Math.max(1, row.getWidth() - (item.canStart() ? 210 : 92)))
                .setPosition(54, 17));

        LabelWidget detail = new LabelWidget(() -> {
            Item current = currentItem(item.id());
            Item value = current == null ? item : current;
            return value.detail() + " · " + statusText(value.status()) + detailSuffix(value);
        }, FontManager.pf25);
        row.addChild(detail);
        detail.setClickable(false).setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        detail.setBeforeRenderCallback(() -> {
            Item current = currentItem(item.id());
            detail.setColor(statusColor(current == null ? item.status() : current.status()))
                    .setMaxWidth(Math.max(1, row.getWidth() - 110)).setPosition(54, 47);
        });
        detail.setTooltip(() -> item.location() == null ? "" : item.location());

        NcmExpressive.progress(row, () -> 54, () -> row.getHeight() - 5,
                () -> currentStatus(item) == Status.DOWNLOADING || currentStatus(item) == Status.WAITING_NETWORK
                        || currentStatus(item) == Status.INSTALLING ? Math.max(1, row.getWidth() - 70) : 0,
                () -> {
                    Item current = currentItem(item.id());
                    int progress = current == null ? item.progress() : current.progress();
                    return progress < 0 ? -1 : progress / 100.0;
                }, () -> currentStatus(item) == Status.DOWNLOADING || currentStatus(item) == Status.WAITING_NETWORK);

        NcmExpressive.action(row, "", () -> {
                    Item current = currentItem(item.id());
                    return current != null && current.canStart() ? current.actionLabel() : "";
                }, NcmExpressive.ActionStyle.TONAL,
                () -> row.getWidth() - 112, () -> 19,
                () -> {
                    Item current = currentItem(item.id());
                    return current != null && current.canStart() ? 96.0 : 0.0;
                }, () -> 40.0, () -> {
                    Item current = currentItem(item.id());
                    if (current != null) current.start();
                });
    }

    private Item currentItem(String id) {
        for (Item item : snapshot) if (item.id().equals(id)) return item;
        return null;
    }

    private Status currentStatus(Item item) {
        Item current = currentItem(item.id());
        return current == null ? item.status() : current.status();
    }

    private static String detailSuffix(Item item) {
        return item.optional() ? " · 可选" : " · 必需";
    }

    private List<Item> filteredItems() {
        List<Item> visible = new ArrayList<Item>();
        for (Item item : snapshot) {
            if (selectedGroup != null && selectedGroup != item.group()) continue;
            boolean include = filter == 0
                    || filter == 1 && (item.status() == Status.INSTALLED || item.status() == Status.RESTART_REQUIRED)
                    || filter == 2 && (item.status() == Status.NOT_INSTALLED || item.status() == Status.UNSUPPORTED)
                    || filter == 3 && (item.status() == Status.DOWNLOADING || item.status() == Status.WAITING_NETWORK
                            || item.status() == Status.INSTALLING || item.status() == Status.FAILED);
            if (include) visible.add(item);
        }
        return visible;
    }

    private void layoutDialog(Panel dialog) {
        double width = NcmExpressive.dialogWidth(this, MAX_WIDTH);
        double height = NcmExpressive.dialogHeight(this, MAX_HEIGHT);
        dialog.setBounds(width, height);
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1 - presentation) * 12);
        dialog.setAlpha((float) presentation);
    }

    private String summaryText() {
        int installed = 0, missing = 0, active = 0;
        for (Item item : snapshot) {
            if (item.status() == Status.INSTALLED || item.status() == Status.RESTART_REQUIRED) installed++;
            if (item.status() == Status.NOT_INSTALLED) missing++;
            if (item.status() == Status.DOWNLOADING || item.status() == Status.WAITING_NETWORK
                    || item.status() == Status.INSTALLING) active++;
        }
        return installed + " 已就绪 · " + missing + " 未安装 · " + active + " 个任务";
    }

    private String networkLine() {
        int waiting = DownloadCatalog.waitingTasks();
        if (waiting > 0) return waiting + " 个任务等待网络恢复 · 部分数据已保留，可继续下载";
        String failure = DownloadCatalog.lastNetworkFailure();
        return failure == null || failure.isEmpty() ? "组件状态每秒更新 · 下载完成后会校验并原子安装"
                : "最近网络异常：" + failure;
    }

    private static String statusText(Status status) {
        switch (status) {
            case INSTALLED: return "已安装 · 完整性由组件校验策略确认";
            case DOWNLOADING: return "正在下载";
            case WAITING_NETWORK: return "等待网络恢复 · 进度已保留";
            case INSTALLING: return "校验 / 安装中";
            case RESTART_REQUIRED: return "已安装 · 重启后生效";
            case FAILED: return "需要处理";
            case UNSUPPORTED: return "平台不支持";
            default: return "未安装";
        }
    }

    private static String statusGlyph(Status status) {
        if (status == Status.INSTALLED) return PartyIcons.CHECK;
        if (status == Status.FAILED) return PartyIcons.WARNING;
        if (status == Status.RESTART_REQUIRED) return PartyIcons.SYNC;
        if (status == Status.DOWNLOADING || status == Status.WAITING_NETWORK || status == Status.INSTALLING) {
            return PartyIcons.SYNC;
        }
        return PartyIcons.INFO;
    }

    private static int statusColor(Status status) {
        if (status == Status.INSTALLED) return NcmExpressivePalette.live().success();
        if (status == Status.FAILED) return NcmExpressivePalette.live().error();
        if (status == Status.RESTART_REQUIRED || status == Status.WAITING_NETWORK) {
            return NcmExpressivePalette.live().warning();
        }
        return NcmExpressivePalette.live().muted();
    }

    private void close() {
        if (closing) return;
        closing = true;
        clock.requestDismiss();
    }

    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public boolean shouldClose() {
        return clock.isGone();
    }

    public void handleEscape() {
        close();
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
        layout.dispatch(this, mouseX, mouseY, button,
                (x, y, input) -> super.onMouseClickReceived(x, y, input));
    }

    @Override
    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        layout.dispatch(this, mouseX, mouseY, dWheel,
                (x, y, input) -> super.onDWheelReceived(x, y, input));
    }
}
