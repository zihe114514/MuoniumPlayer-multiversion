package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.DjRadioService;
import com.muoniumplayer.core.ncm.music.dto.DjRadio;
import com.muoniumplayer.core.ncm.music.dto.Music;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DIFM 电台：按曲风（最嗨电音 / 古典电台 / 爵士电台）策展的连续播放频道。
 *
 * <p>频道卡左键 = 取该频道正在播的曲目列表直接开播（DIFM 返回的是普通歌曲，
 * 走现有播放管线）；右键 = 收藏 / 取消收藏。收藏的频道在「我的收藏」chip 里列出，
 * 再右键一次即取消。</p>
 */
public final class DifmPanel extends NCMPanel {

    /** 来源列表与 api-enhanced 文档一致：0 最嗨电音、1 古典电台、2 爵士电台。 */
    private static final String[][] SOURCES = {{"0", "最嗨电音"}, {"1", "古典电台"}, {"2", "爵士电台"}};

    private static final double MARGIN = 12.0;
    private static final double GRID_TOP = 68.0;

    private String selectedSource = "0";
    private boolean showCollected;
    private volatile List<DjRadio> channels = Collections.emptyList();
    private volatile boolean loading = true;
    private volatile boolean failed;
    private volatile String status = "正在获取…";
    private int loadGeneration;

    private ScrollPanel grid;
    private final List<RadioCardWidget> cardWidgets = new ArrayList<>();
    private List<DjRadio> pendingCards;
    private int pendingCardIndex;
    private static final int CARD_BUILD_BATCH = 32;

    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();
    private int accentColor, primaryTextColor, secondaryTextColor, hoverColor;

    public DifmPanel() {
        setNavigationKey("nav:radio");
    }

    @Override
    public void onInit() {
        loading = true;
        status = "正在获取…";
        renderLayout();
        load();
    }

    private void renderLayout() {
        getChildren().clear();
        cardWidgets.clear();
        pendingCards = null;

        LabelWidget title = new LabelWidget("DIFM 电台", FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(this.primaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(MARGIN, MARGIN));

        LabelWidget subtitle = new LabelWidget(
                () -> status.isEmpty() ? "左键开播 · 右键收藏频道" : status, FontManager.pf12);
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

        // 来源 chips + 我的收藏：收藏 chip 与来源 chip 互斥，选中其一。
        final double[] cursor = {MARGIN};
        for (final String[] source : SOURCES) {
            RoundedButtonWidget chip = new RoundedButtonWidget(source[1], FontManager.pf12);
            addChild(chip);
            chip.setShouldOverrideMouseCursor(true);
            chip.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (showCollected || !source[0].equals(selectedSource)) {
                    showCollected = false;
                    selectedSource = source[0];
                    load();
                }
                return true;
            });
            chip.setBeforeRenderCallback(() -> {
                RadioChips.layout(chip, source[1], cursor, RadioChips.CHIP_HEIGHT + 4.0 + MARGIN);
                RadioChips.paint(chip, !showCollected && source[0].equals(selectedSource),
                        this.accentColor, this.hoverColor);
            });
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
        list.setBeforeRenderCallback(() -> list.setBounds(MARGIN, GRID_TOP,
                Math.max(1.0, getWidth() - MARGIN * 2.0),
                Math.max(1.0, getHeight() - GRID_TOP - MARGIN)));

        this.setBeforeRenderCallback(() -> {
            this.accentColor = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            this.primaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
            this.secondaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
            this.hoverColor = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            this.revealQueue.beginFrame();
            this.revealQueue.setAnimationsSuppressed(this.grid != null && this.grid.isScrollActive());
            // chip 游标每帧归位（回调先于子控件跑）：不归位就是每帧累加，chip 漂出屏幕。
            cursor[0] = MARGIN;
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

        // 重建整列时重新武装首屏错峰档（与 NeteaseDiscoveryPanel 同一约定）：
        // beginFrame 只在上一帧发过号后关闭首屏让位，不 restart 的话重建后的
        // 首屏卡片拿不到 90ms 让位档，进场与整页转场叠帧。
        this.revealQueue.restart();
        this.pendingCards = new ArrayList<>(this.channels);
        this.pendingCardIndex = 0;
        buildPendingCards();
    }

    private void load() {
        final int generation = ++loadGeneration;
        final String sources = "[" + selectedSource + "]";
        this.loading = true;
        this.failed = false;
        this.status = "正在获取…";
        renderLayout();
        MultiThreadingUtil.runAsync(() -> {
            try {
                List<DjRadio> loaded = showCollected
                        ? DjRadioService.parseDifmChannels(
                                CloudMusicApi.djDifmSubscribedChannels(sources).toJsonObject())
                        : DjRadioService.parseDifmChannels(
                                CloudMusicApi.djDifmStyleChannels(sources).toJsonObject());
                String statusText = (showCollected ? "我的收藏 · " : "DIFM · ")
                        + (loaded.isEmpty() ? "暂无频道" : loaded.size() + " 个频道");
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

    /** 每帧推进卡片分帧构建：renderLayout 只铺首屏 32 张，其余由渲染帧补齐。 */
    @Override
    public void onRender(double mouseX, double mouseY) {
        buildPendingCards();
        super.onRender(mouseX, mouseY);
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
                        () -> playChannel(channel),
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

    /** 取频道正在播的曲目列表直接开播；拿不到曲目时给岛屿反馈。 */
    private void playChannel(DjRadio channel) {
        MultiThreadingUtil.runAsync(() -> {
            List<Music> tracks = Collections.emptyList();
            boolean requestFailed = false;
            try {
                // 优先用频道自己记录的来源（ categoryName 槽位，解析时写入）：收藏视图里
                // 点跨曲风收藏的频道时，页面 selectedSource 与频道真实来源对不上，
                // 按页面的 source 请求会拿空。
                int source = parseInt(channel.getCategoryName());
                if (source < 0) source = parseInt(selectedSource);
                tracks = DjRadioService.parseDifmTracks(CloudMusicApi
                        .djDifmPlayingTracks(source, channel.getId(), 20).toJsonObject());
            } catch (Throwable ignored) {
                requestFailed = true;
            }
            final List<Music> playable = tracks;
            final boolean failed = requestFailed;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (playable.isEmpty()) {
                    DownloadDynamicIsland.showModelOperationResult("DIFM · " + channel.displayName(),
                            failed ? "取频道曲目失败，请稍后重试" : "这个频道暂时拿不到曲目", false);
                    return;
                }
                CloudMusic.play(new ArrayList<>(playable), 0);
                DownloadDynamicIsland.showModelOperationResult("DIFM · " + channel.displayName(),
                        "正在播放频道曲目 · " + playable.size() + " 首", true);
            });
        });
    }

    private void toggleCollect(DjRadio channel) {
        final boolean subscribe = !showCollected;
        MultiThreadingUtil.runAsync(() -> {
            boolean ok = DjRadioService.setDifmChannelSubscribed(channel.getId(), subscribe);
            MultiThreadingUtil.runOnMainThread(() -> {
                DownloadDynamicIsland.showModelOperationResult("DIFM 收藏",
                        ok
                                ? (subscribe ? "已收藏「" : "已取消收藏「") + channel.displayName() + "」"
                                : "收藏请求失败",
                        ok);
                if (ok && showCollected) load(); // 收藏列表里取消后当场刷新
            });
        });
    }

    private static int parseInt(String value) {
        try {
            String trimmed = value == null ? "" : value.trim();
            // 空串 = 频道没带来源（收藏端点旧形状），返回 -1 让调用方退回页面选中值。
            if (trimmed.isEmpty()) return -1;
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
