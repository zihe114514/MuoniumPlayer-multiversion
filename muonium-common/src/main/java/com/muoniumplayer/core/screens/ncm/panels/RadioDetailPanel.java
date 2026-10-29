package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.DjRadioService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.dto.DjRadio;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 电台 / 播客详情页：hero 封面 + 元信息 + 订阅/播放全部，下面是节目列表。
 *
 * <p>节目在 {@link DjRadioService} 里已转成真实 songId 的 {@link Music}，所以列表行直接复用
 * {@link MusicWidget} —— 播放、红心、加入歌单、下一首播放都是现成行为；分页由滚动到底触发，
 * 与歌单页的 {@code buildPendingMusicWidgets} 同一条分帧构建路径。</p>
 */
public final class RadioDetailPanel extends NCMPanel {

    private static final double MARGIN = 12.0;
    private static final double HERO_TOP = 60.0;
    private static final double HERO_COVER = 96.0;
    private static final double HERO_TEXT_X = MARGIN + HERO_COVER + 12.0;
    private static final double LIST_TOP = HERO_TOP + HERO_COVER + 12.0;
    private static final int PAGE_SIZE = 30;

    private final DjRadio radio;

    private ScrollPanel list;
    /** 节目行的播放上下文：虚拟"搜索模式"列表，与发现页曲目列表同一形态。 */
    private final PlayList contextList;
    private final List<Music> programs = new ArrayList<>();
    private final List<MusicWidget> rowWidgets = new ArrayList<>();

    private volatile boolean programsLoading = true;
    private volatile boolean programsFailed;
    private volatile boolean complete;
    private volatile boolean loadingMore;
    private volatile boolean togglingSubscribe;

    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();
    private int accentColor, primaryTextColor, secondaryTextColor, hoverColor;
    private int pendingRowIndex;

    private boolean coverLoaded;
    private int coverRetryFrame = 19;

    public RadioDetailPanel(DjRadio radio) {
        this.radio = radio == null ? new DjRadio() : radio;
        this.contextList = JsonUtils.parse("{}", PlayList.class);
        this.contextList.setSearchMode(true);
        this.contextList.setMusics(new CopyOnWriteArrayList<>());
        this.contextList.setMusicsQueried(true);
        this.contextList.setMusicsLoaded(true);
    }

    @Override
    public void onInit() {
        renderLayout();
        loadDetail();
        loadPrograms(0);
    }

    // ── 布局 ─────────────────────────────────────────────────────────────────

    private void renderLayout() {
        getChildren().clear();
        rowWidgets.clear();
        pendingRowIndex = 0;

        attachBackButton();

        RoundedImageWidget cover = new RoundedImageWidget(this::getCoverLocation, 0, 0, 1.0, 1.0);
        addChild(cover);
        cover.setClickable(false)
                .fadeIn()
                .setLinearFilter(true);
        cover.setBeforeRenderCallback(() -> {
            updateCoverLoad();
            cover.setBounds(MARGIN, HERO_TOP, HERO_COVER, HERO_COVER).setRadius(6);
        });

        LabelWidget name = new LabelWidget(radio::displayName, FontManager.pf25bold);
        addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> name
                .setColor(this.primaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - HERO_TEXT_X - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(HERO_TEXT_X, HERO_TOP + 2));

        LabelWidget meta = new LabelWidget(this::metaText, FontManager.pf12);
        addChild(meta);
        meta.setClickable(false);
        meta.setBeforeRenderCallback(() -> meta
                .setColor(this.secondaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - HERO_TEXT_X - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(HERO_TEXT_X, HERO_TOP + 36));

        LabelWidget desc = new LabelWidget(this::descText, FontManager.pf12);
        addChild(desc);
        desc.setClickable(false);
        desc.setBeforeRenderCallback(() -> desc
                .setColor(this.secondaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - HERO_TEXT_X - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(HERO_TEXT_X, HERO_TOP + 56));

        RoundedButtonWidget btnPlayAll = new RoundedButtonWidget("播放全部", FontManager.pf16bold);
        addChild(btnPlayAll);
        btnPlayAll.setShouldOverrideMouseCursor(true);
        btnPlayAll.setRadius(3);
        btnPlayAll.setOnClickCallback((x, y, button) -> {
            if (button != 0 || programs.isEmpty()) return false;
            CloudMusic.play(new ArrayList<>(programs), 0);
            return true;
        });
        btnPlayAll.setBeforeRenderCallback(() -> {
            btnPlayAll.setBounds(72, 17);
            btnPlayAll.setPosition(HERO_TEXT_X, HERO_TOP + HERO_COVER - 17);
            btnPlayAll.setColor(this.accentColor);
            btnPlayAll.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        RoundedButtonWidget btnSubscribe = new RoundedButtonWidget(
                () -> radio.isSubscribed() ? "已订阅" : "订阅电台", FontManager.pf16bold);
        addChild(btnSubscribe);
        btnSubscribe.setShouldOverrideMouseCursor(true);
        btnSubscribe.setRadius(3);
        btnSubscribe.setOnClickCallback((x, y, button) -> {
            if (button != 0 || togglingSubscribe) return false;
            toggleSubscribe();
            return true;
        });
        btnSubscribe.setBeforeRenderCallback(() -> {
            btnSubscribe.setBounds(72, 17);
            btnSubscribe.setPosition(HERO_TEXT_X + 80, HERO_TOP + HERO_COVER - 17);
            if (radio.isSubscribed()) {
                // 已订阅：强调色只描边不铺底，与"取消收藏歌单"的确认语义同一档克制。
                btnSubscribe.setGlassPlate(GlassLook.Surface.PLAYER);
                btnSubscribe.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
                btnSubscribe.setColor(reAlpha(this.accentColor, 0f));
                btnSubscribe.setTextColor(this.accentColor);
            } else {
                btnSubscribe.setGlassPlate(GlassLook.Surface.PLAYER);
                btnSubscribe.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
                btnSubscribe.setColor(btnSubscribe.isHovering() ? this.hoverColor
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                btnSubscribe.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            }
        });

        ScrollPanel panel = new ScrollPanel();
        addChild(panel);
        this.list = panel;
        panel.setSpacing(2).setScrollStrength(40)
                .setAlignment(ScrollPanel.Alignment.VERTICAL);
        panel.setBeforeRenderCallback(() -> panel.setBounds(MARGIN, LIST_TOP,
                Math.max(1.0, getWidth() - MARGIN * 2.0),
                Math.max(1.0, getHeight() - LIST_TOP - MARGIN)));

        this.setBeforeRenderCallback(() -> {
            this.accentColor = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            this.primaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
            this.secondaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
            this.hoverColor = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            this.revealQueue.beginFrame();
            this.revealQueue.setAnimationsSuppressed(this.list != null && this.list.isScrollActive());
        });

        if (programsLoading && programs.isEmpty()) {
            PanelLoadingWidget spinner = new PanelLoadingWidget("正在获取节目…");
            addChild(spinner);
            spinner.setClickable(false);
            spinner.setBeforeRenderCallback(() -> spinner.setBounds(panel.getRelativeX(), panel.getRelativeY(),
                    Math.max(1.0, panel.getWidth()), Math.max(1.0, panel.getHeight())));
            return;
        }

        if (programs.isEmpty()) {
            final boolean retryable = programsFailed;
            PanelLoadingWidget notice = new PanelLoadingWidget(programsFailed
                    ? "获取节目失败，点击重试" : "这个电台还没有节目").setSpinning(false);
            addChild(notice);
            notice.setClickable(retryable);
            notice.setShouldOverrideMouseCursor(retryable);
            notice.setBeforeRenderCallback(() -> notice.setBounds(panel.getRelativeX(), panel.getRelativeY(),
                    Math.max(1.0, panel.getWidth()), Math.max(1.0, panel.getHeight())));
            if (retryable) {
                notice.setOnClickCallback((x, y, button) -> {
                    if (button != 0 || programsLoading) return false;
                    loadPrograms(0);
                    return true;
                });
            }
            return;
        }

        // 增量发布已解析好的节目行：重建布局时（详情信息到达）已发布的行不重播进场。
        this.pendingRowIndex = this.rowWidgets.size();
        buildPendingRows();
    }

    private void attachBackButton() {
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
    }

    private String metaText() {
        StringBuilder sb = new StringBuilder();
        if (radio.getDjName() != null && !radio.getDjName().isEmpty()) sb.append(radio.getDjName());
        if (radio.getCategoryName() != null && !radio.getCategoryName().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(radio.getCategoryName());
        }
        if (radio.getSubCount() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(radio.getSubCount()).append(" 人订阅");
        }
        if (radio.getProgramCount() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(radio.getProgramCount()).append(" 期");
        }
        if (sb.length() == 0) sb.append("电台 · 播客");
        return sb.toString();
    }

    private String descText() {
        String desc = radio.getDesc();
        return desc == null || desc.isEmpty() ? "" : desc;
    }

    // ── 数据 ─────────────────────────────────────────────────────────────────

    /** 详情只用来补订阅态与计数；拿不到就按卡片带来的信息展示，不阻塞列表。 */
    private void loadDetail() {
        MultiThreadingUtil.runAsync(() -> {
            try {
                DjRadio detail = DjRadioService.fetchRadioDetail(radio.getId());
                if (detail == null) return;
                MultiThreadingUtil.runOnMainThread(() -> {
                    radio.setSubscribed(detail.isSubscribed());
                    if (detail.getSubCount() > 0) radio.setSubCount(detail.getSubCount());
                    if (detail.getProgramCount() > 0) radio.setProgramCount(detail.getProgramCount());
                    if (detail.getDesc() != null && !detail.getDesc().isEmpty()) radio.setDesc(detail.getDesc());
                    // 标签文案是 supplier，下一帧自动更新，无需重建布局。
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void loadPrograms(int offset) {
        if (offset == 0) {
            programsLoading = true;
            programsFailed = false;
            programs.clear();
            rowWidgets.clear();
        } else {
            loadingMore = true;
        }
        MultiThreadingUtil.runAsync(() -> {
            List<Music> page = Collections.emptyList();
            boolean requestFailed = false;
            try {
                page = DjRadioService.parsePrograms(
                        CloudMusicApi.djProgramByRadio(radio.getId(), PAGE_SIZE, offset, false).toJsonObject());
            } catch (Throwable throwable) {
                requestFailed = offset == 0; // 翻页失败静默，本页先到先得
            }
            final List<Music> loaded = page;
            final boolean failedFirstPage = requestFailed;
            MultiThreadingUtil.runOnMainThread(() -> {
                programsLoading = false;
                loadingMore = false;
                if (failedFirstPage) {
                    programsFailed = true;
                    if (programs.isEmpty()) renderLayout();
                    return;
                }
                this.complete = loaded.size() < PAGE_SIZE;
                if (offset == 0) {
                    programs.clear();
                    contextList.getMusics().clear();
                }
                programs.addAll(loaded);
                contextList.getMusics().addAll(loaded);
                if (offset == 0) {
                    renderLayout();
                } else {
                    appendRows(loaded);
                }
            });
        });
    }

    private void toggleSubscribe() {
        if (!NeteaseMusicService.isLoggedIn(MusicPlatform.NETEASE)) {
            DownloadDynamicIsland.showModelOperationResult("订阅电台", "请先登录网易云账号", false);
            return;
        }
        togglingSubscribe = true;
        final boolean subscribe = !radio.isSubscribed();
        MultiThreadingUtil.runAsync(() -> {
            boolean ok = false;
            try {
                ok = CloudMusicApi.djSub(radio.getId(), subscribe).toJsonObject() != null;
            } catch (Throwable ignored) {
            }
            final boolean success = ok;
            MultiThreadingUtil.runOnMainThread(() -> {
                togglingSubscribe = false;
                if (success) radio.setSubscribed(subscribe);
                DownloadDynamicIsland.showModelOperationResult("订阅电台",
                        (subscribe ? "已订阅「" : "已取消订阅「") + radio.displayName() + "」"
                                + (success ? "" : "（请求失败）"),
                        success);
            });
        });
    }

    // ── 节目行 ───────────────────────────────────────────────────────────────

    /** 分帧构建：每帧最多 32 行，与歌单页同一档。 */
    private void buildPendingRows() {
        if (list == null) return;
        int end = Math.min(programs.size(), pendingRowIndex + 32);
        if (pendingRowIndex >= end) return;
        for (int index = pendingRowIndex; index < end; index++) {
            MusicWidget row = new MusicWidget(programs.get(index), contextList, index);
            row.setEntranceAnimation(this.revealQueue);
            rowWidgets.add(row);
            list.addChild(row);
        }
        list.invalidateLayout();
        pendingRowIndex = end;
    }

    /** 翻页追加：只把新行挂进列表，不重建整页 —— 老行的滚动位置与进场状态都保留。 */
    private void appendRows(List<Music> fresh) {
        if (list == null || fresh.isEmpty()) return;
        List<AbstractWidget<?>> rows = new ArrayList<>(fresh.size());
        for (int i = 0; i < fresh.size(); i++) {
            int index = programs.size() - fresh.size() + i;
            MusicWidget row = new MusicWidget(fresh.get(i), contextList, index);
            row.setEntranceAnimation(this.revealQueue);
            rowWidgets.add(row);
            rows.add(row);
        }
        list.addChild(rows);
        list.invalidateLayout();
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        this.revealQueue.beginFrame();
        this.revealQueue.setAnimationsSuppressed(this.list != null && this.list.isScrollActive());
        this.buildPendingRows();
        // 滚到底自动翻页：complete 之后不再发请求。
        if (!complete && !loadingMore && !programsLoading && !programs.isEmpty()
                && this.list != null && this.list.isScrolledToEnd()) {
            loadPrograms(programs.size());
        }
        super.onRender(mouseX, mouseY);
    }

    // ── 封面 ─────────────────────────────────────────────────────────────────

    private Location getCoverLocation() {
        return Location.of("muonium/textures/radio/dj/" + radio.getId() + "/cover.png");
    }

    private void updateCoverLoad() {
        if (coverLoaded) return;
        if (++coverRetryFrame < 20) return;
        coverRetryFrame = 0;
        if (TextureManager.getInstance().getTexture(getCoverLocation()) != null) {
            coverLoaded = true;
            return;
        }
        String url = radio.coverUrl();
        if (url.isEmpty()) return;
        Textures.downloadTextureAndLoadAsync(url + "?param=512y512", getCoverLocation());
        coverLoaded = true;
    }
}
