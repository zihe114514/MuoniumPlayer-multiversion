package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.DjRadioService;
import com.muoniumplayer.core.ncm.music.dto.DjRadio;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 广播电台：真实 FM 电台的浏览 / 收藏页。
 *
 * <p>诚实边界：广播电台是直播流，当前播放引擎是"整段下载再播"模型，播不了无限流 ——
 * 所以频道卡左键查询并展示当前节目信息（若上游给出的是常规歌曲则可直接播放，当前实现
 * 给出信息反馈），右键收藏/取消收藏。直播流播放需要流式引擎改造，属后续任务。</p>
 */
public final class BroadcastPanel extends NCMPanel {

    private static final double MARGIN = 12.0;
    private static final double GRID_TOP = 68.0;

    /** 空串 = 不筛分类（全部电台）；「我的收藏」是独立的一个 chip。 */
    private String selectedCategoryId = "";
    private boolean showCollected;
    private final List<String[]> categories = new ArrayList<>();
    private volatile List<DjRadio> channels = Collections.emptyList();
    private volatile boolean loading = true;
    private volatile boolean failed;
    private volatile String status = "正在获取…";
    private int loadGeneration;
    private boolean categoriesLoaded;

    private ScrollPanel grid;
    private final List<RadioCardWidget> cardWidgets = new ArrayList<>();
    private List<DjRadio> pendingCards;
    private int pendingCardIndex;
    private static final int CARD_BUILD_BATCH = 32;

    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();
    private int accentColor, primaryTextColor, secondaryTextColor, hoverColor;

    public BroadcastPanel() {
        setNavigationKey("nav:radio");
    }

    @Override
    public void onInit() {
        loading = true;
        status = "正在获取…";
        renderLayout();
        load();
        loadCategories();
    }

    private void renderLayout() {
        getChildren().clear();
        cardWidgets.clear();
        pendingCards = null;

        LabelWidget title = new LabelWidget("广播电台", FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(this.primaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(MARGIN, MARGIN));

        LabelWidget subtitle = new LabelWidget(
                () -> status.isEmpty() ? "左键查看当前节目 · 右键收藏电台" : status, FontManager.pf12);
        addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(this.secondaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(MARGIN, 39));

        RoundedButtonWidget btnBack = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        addChild(btnBack);
        btnBack.setShouldOverrideMouseCursor(true);
        btnBack.setGlassPlate(GlassLook.Surface.PLAYER)
                .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        btnBack.setBeforeRenderCallback(() -> {
            btnBack.setBounds(28, 22);
            btnBack.setPosition(Math.max(8, getWidth() - btnBack.getWidth() - 12), 8);
            btnBack.setRadius(6);
            btnBack.setColor(btnBack.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            btnBack.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        btnBack.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().navigateBack();
            return true;
        });

        final boolean withCategories = !showCollected && !categories.isEmpty();
        final double[] cursor = {MARGIN};
        if (withCategories) {
            RoundedButtonWidget allChip = new RoundedButtonWidget("全部", FontManager.pf12);
            addChild(allChip);
            allChip.setShouldOverrideMouseCursor(true);
            allChip.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (!selectedCategoryId.isEmpty()) {
                    selectedCategoryId = "";
                    load();
                }
                return true;
            });
            allChip.setBeforeRenderCallback(() -> {
                RadioChips.layout(allChip, "全部", cursor, RadioChips.CHIP_HEIGHT + 4.0 + MARGIN);
                RadioChips.paint(allChip, selectedCategoryId.isEmpty(), this.accentColor, this.hoverColor);
            });
            for (final String[] category : categories) {
                RoundedButtonWidget chip = new RoundedButtonWidget(category[1], FontManager.pf12);
                addChild(chip);
                chip.setShouldOverrideMouseCursor(true);
                chip.setOnClickCallback((x, y, button) -> {
                    if (button != 0) return false;
                    if (!category[0].equals(selectedCategoryId)) {
                        selectedCategoryId = category[0];
                        load();
                    }
                    return true;
                });
                chip.setBeforeRenderCallback(() -> {
                    RadioChips.layout(chip, category[1], cursor, RadioChips.CHIP_HEIGHT + 4.0 + MARGIN);
                    RadioChips.paint(chip, category[0].equals(selectedCategoryId),
                            this.accentColor, this.hoverColor);
                });
            }
        }
        RoundedButtonWidget collectedChip = new RoundedButtonWidget("我的收藏", FontManager.pf12);
        addChild(collectedChip);
        collectedChip.setShouldOverrideMouseCursor(true);
        collectedChip.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (!showCollected) {
                showCollected = true;
                load();
            }
            return true;
        });
        collectedChip.setBeforeRenderCallback(() -> {
            RadioChips.layout(collectedChip, "我的收藏", cursor, RadioChips.CHIP_HEIGHT + 4.0 + MARGIN);
            RadioChips.paint(collectedChip, showCollected, this.accentColor, this.hoverColor);
        });

        ScrollPanel list = new ScrollPanel();
        addChild(list);
        this.grid = list;
        list.setSpacing(RadioCardWidget.GRID_SPACING)
                .setScrollStrength(44)
                .setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL);
        final double gridTop = withCategories ? GRID_TOP + 24.0 : GRID_TOP;
        list.setBeforeRenderCallback(() -> list.setBounds(MARGIN, gridTop,
                Math.max(1.0, getWidth() - MARGIN * 2.0),
                Math.max(1.0, getHeight() - gridTop - MARGIN)));

        this.setBeforeRenderCallback(() -> {
            this.accentColor = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            this.primaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
            this.secondaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
            this.hoverColor = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            this.revealQueue.beginFrame();
            this.revealQueue.setAnimationsSuppressed(this.grid != null && this.grid.isScrollActive());
            RadioCardWidget.layoutGrid(Math.max(1.0, getWidth() - MARGIN * 2.0), this.cardWidgets);
        });

        if (loading && channels.isEmpty()) {
            PanelLoadingWidget spinner = new PanelLoadingWidget(status);
            addChild(spinner);
            spinner.setClickable(false);
            spinner.setBeforeRenderCallback(() -> spinner.setBounds(list.getRelativeX(), list.getRelativeY(),
                    Math.max(1.0, list.getWidth()), Math.max(1.0, list.getHeight())));
            return;
        }
        if (channels.isEmpty()) {
            final boolean retryable = failed;
            PanelLoadingWidget notice = new PanelLoadingWidget(
                    status.isEmpty() ? "暂无可显示内容" : status).setSpinning(false);
            addChild(notice);
            notice.setClickable(retryable);
            notice.setShouldOverrideMouseCursor(retryable);
            notice.setBeforeRenderCallback(() -> notice.setBounds(list.getRelativeX(), list.getRelativeY(),
                    Math.max(1.0, list.getWidth()), Math.max(1.0, list.getHeight())));
            if (retryable) {
                notice.setOnClickCallback((x, y, button) -> {
                    if (button != 0 || loading) return false;
                    load();
                    return true;
                });
            }
            return;
        }

        this.pendingCards = new ArrayList<>(this.channels);
        this.pendingCardIndex = 0;
        buildPendingCards();
    }

    /** 分类/地区目录只取一次；解析不出分类就不铺 rail，直接展示全部电台。 */
    private void loadCategories() {
        MultiThreadingUtil.runAsync(() -> {
            List<String[]> parsed = Collections.emptyList();
            try {
                JsonObject root = CloudMusicApi.broadcastCategoryRegion().toJsonObject();
                parsed = DjRadioService.parseBroadcastCategoryOrRegion(root, false);
            } catch (Throwable ignored) {
            }
            final List<String[]> result = parsed;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (categoriesLoaded) return;
                categoriesLoaded = true;
                if (result.isEmpty()) return;
                categories.clear();
                categories.addAll(result);
                renderLayout();
            });
        });
    }

    private void load() {
        final int generation = ++loadGeneration;
        this.loading = true;
        this.failed = false;
        this.status = "正在获取…";
        renderLayout();
        MultiThreadingUtil.runAsync(() -> {
            try {
                List<DjRadio> loaded;
                String statusText;
                if (showCollected) {
                    loaded = DjRadioService.parseBroadcastChannels(
                            CloudMusicApi.broadcastChannelCollectList().toJsonObject());
                    statusText = "我的收藏 · " + (loaded.isEmpty() ? "还没有收藏的电台" : loaded.size() + " 个电台");
                } else {
                    long categoryId = parseLong(selectedCategoryId);
                    loaded = DjRadioService.parseBroadcastChannels(
                            CloudMusicApi.broadcastChannelList(categoryId, 0, 30, 0).toJsonObject());
                    statusText = "广播电台 · " + (loaded.isEmpty() ? "暂无内容" : loaded.size() + " 个电台");
                }
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (generation != loadGeneration) return;
                    this.channels = loaded;
                    this.status = statusText;
                    this.loading = false;
                    renderLayout();
                });
            } catch (Throwable throwable) {
                String message = throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()
                        ? "网络请求失败" : throwable.getMessage().trim();
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (generation != loadGeneration) return;
                    this.channels = Collections.emptyList();
                    this.failed = true;
                    this.status = "获取失败：" + message;
                    this.loading = false;
                    renderLayout();
                });
            }
        });
    }

    private void buildPendingCards() {
        if (pendingCards == null) return;
        int total = pendingCards.size();
        int end = Math.min(total, pendingCardIndex + CARD_BUILD_BATCH);
        if (pendingCardIndex < end) {
            List<AbstractWidget<?>> batch = new ArrayList<>(end - pendingCardIndex);
            for (int index = pendingCardIndex; index < end; index++) {
                DjRadio channel = pendingCards.get(index);
                if (channel == null) continue;
                RadioCardWidget card = new RadioCardWidget(channel,
                        () -> showCurrentInfo(channel),
                        () -> toggleCollect(channel));
                card.setEntranceAnimation(this.revealQueue);
                this.cardWidgets.add(card);
                batch.add(card);
            }
            if (this.grid != null) {
                this.grid.addChild(batch);
                this.grid.invalidateLayout();
            }
            this.pendingCardIndex = end;
        }
        if (this.pendingCardIndex >= total) this.pendingCards = null;
    }

    /** 直播流暂不支持播放：查询当前节目信息给岛屿反馈，把能力边界讲清楚。 */
    private void showCurrentInfo(DjRadio channel) {
        MultiThreadingUtil.runAsync(() -> {
            String nowPlaying = "";
            boolean requestFailed = false;
            try {
                nowPlaying = DjRadioService.broadcastNowPlayingText(
                        CloudMusicApi.broadcastChannelCurrentInfo(channel.getId()).toJsonObject());
            } catch (Throwable ignored) {
                requestFailed = true;
            }
            final String info = nowPlaying;
            final boolean failed = requestFailed;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (failed) {
                    DownloadDynamicIsland.showModelOperationResult("广播电台 · " + channel.displayName(),
                            "查询节目信息失败，请稍后重试", false);
                    return;
                }
                DownloadDynamicIsland.showModelOperationResult("广播电台 · " + channel.displayName(),
                        info.isEmpty()
                                ? "直播流播放将在后续版本支持 · 暂无节目信息"
                                : "正在播： " + info + " · 直播流播放将在后续版本支持",
                        true);
            });
        });
    }

    private void toggleCollect(DjRadio channel) {
        final boolean collect = !showCollected;
        MultiThreadingUtil.runAsync(() -> {
            boolean ok = false;
            try {
                ok = CloudMusicApi.broadcastCollect(channel.getId(), collect).toJsonObject() != null;
            } catch (Throwable ignored) {
            }
            final boolean success = ok;
            MultiThreadingUtil.runOnMainThread(() -> {
                DownloadDynamicIsland.showModelOperationResult("广播电台收藏",
                        (collect ? "已收藏「" : "已取消收藏「") + channel.displayName() + "」"
                                + (success ? "" : "（请求失败）"),
                        success);
                if (success && showCollected) load();
            });
        });
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null || value.isEmpty() ? "0" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
