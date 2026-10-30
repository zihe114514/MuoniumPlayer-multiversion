package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.DjRadioService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.dto.DjRadio;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电台 · 播客主页：四个内容分区（推荐 / 分类 / 排行榜 / 我的）+ 页内搜索 + 封面卡片网格，
 * 以及 DIFM 电台与广播电台两个次级入口。
 *
 * <p>骨架照 {@link NeteaseDiscoveryPanel}（标题 + 状态行 + ScrollPanel + 转子 + 空态/重试 +
 * 页内异步），卡片网格用 {@code VERTICAL_WITH_HORIZONTAL_FILL} 让 {@link ScrollPanel} 自动换行，
 * 列宽由 {@link RadioCardWidget#layoutGrid} 每帧按视口宽分发。颜色每帧在 beforeRender 里取，
 * 与封面取色主题的逐帧过渡保持一致。</p>
 */
public final class RadioHubPanel extends NCMPanel {

    private enum Tab {
        RECOMMEND("推荐"), CATEGORY("分类"), TOPLIST("排行榜"), MINE("我的");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final double MARGIN = 12.0;
    private static final double TAB_ROW_Y = 42.0;
    private static final double CHIP_HEIGHT = 17.0;
    private static final double GRID_TOP = 68.0;
    /** 分类 tab 多一行分类 rail（高 17 + 行距）。 */
    private static final double GRID_TOP_WITH_CATEGORIES = 92.0;

    private Tab currentTab = Tab.RECOMMEND;
    private volatile List<DjRadio> cards = Collections.emptyList();
    private volatile boolean loading = true;
    private volatile boolean failed;
    private volatile String status = "正在获取…";
    /** 每次加载 +1；异步结果回来时对不上号就丢弃，防止过期响应覆盖新页面。 */
    private int loadGeneration;

    private final List<String[]> categories = new ArrayList<>();
    /**
     * 分类目录的不可变快照（{@code ensureCategories} 解析结果的原样带回）。后台线程读
     * 这一份，不碰 {@link #categories}（那只属于主线程），两侧就不会撞 CME。
     */
    private volatile List<String[]> categoriesSnapshot;
    private String selectedCategoryId = "";

    private volatile boolean searchMode;
    private String searchKeyword = "";

    private ScrollPanel grid;
    private TextFieldWidget searchField;
    private final List<RadioCardWidget> cardWidgets = new ArrayList<>();
    private List<DjRadio> pendingCards;
    private int pendingCardIndex;
    private static final int CARD_BUILD_BATCH = 32;

    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();
    private int accentColor, primaryTextColor, secondaryTextColor, hoverColor;

    public RadioHubPanel() {
        setNavigationKey("nav:radio");
    }

    @Override
    public void onInit() {
        loading = true;
        status = "正在获取…";
        renderLayout();
        loadTab(Tab.RECOMMEND);
    }

    // ── 布局 ─────────────────────────────────────────────────────────────────

    private void renderLayout() {
        getChildren().clear();
        cardWidgets.clear();
        pendingCards = null;

        LabelWidget title = new LabelWidget("电台 · 播客", FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(this.primaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0 - 220.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(MARGIN, MARGIN));

        attachSearchField();

        // 分区 chips：四个内容 tab + 视觉上隔开一点的 DIFM / 广播两个次级入口。
        // 游标从左往右走，每个 chip 的宽度在 beforeRender 里按文案实测量 —— 字体图集
        // 晚于首帧就绪时第一帧先按估算宽摆位，下一帧自然校正。
        // 游标在面板级 beforeRender 里每帧复位（回调先于所有子控件跑），chip 排版
        // 因此是"每帧从固定起点重排"而不是"在上一帧的累计值上再推一格"——后者会
        // 让整排 chip 以每帧一个身位的速度向右漂出屏幕。
        final double[] chipCursor = {MARGIN};
        final Tab[] tabs = Tab.values();
        for (int tabIndex = 0; tabIndex < tabs.length; tabIndex++) {
            final Tab tab = tabs[tabIndex];
            final boolean lastTab = tabIndex == tabs.length - 1;
            RoundedButtonWidget chip = new RoundedButtonWidget(tab.label, FontManager.pf12);
            addChild(chip);
            chip.setShouldOverrideMouseCursor(true);
            chip.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (this.searchMode || tab != this.currentTab) loadTab(tab);
                return true;
            });
            chip.setBeforeRenderCallback(() -> {
                layoutChip(chip, tab.label, chipCursor, TAB_ROW_Y);
                paintChip(chip, !this.searchMode && tab == this.currentTab);
                // 最后一个 tab 多推进一段：DIFM/广播两个次级入口与内容 tab 之间的视觉间隔
                // 由排版游标自己携带，不靠 renderLayout 的一次性赋值（那在每帧复位后失效）。
                if (lastTab) chipCursor[0] += 14.0;
            });
        }
        // 入口 chip 与 tab chip 共用同一条每帧复位的游标：DIFM/广播前留 14px 视觉间隔。
        attachEntryChip(chipCursor, "DIFM", () -> NCMScreen.getInstance().setCurrentPanel(new DifmPanel()));
        attachEntryChip(chipCursor, "广播", () -> NCMScreen.getInstance().setCurrentPanel(new BroadcastPanel()));

        // 分类 rail：只属于分类 tab，占一行横向滚动条；NCMPanel 的滚轮兜底会跳过
        // 横向滚动区，页面滚轮不受影响。
        final boolean withCategories = !this.searchMode && this.currentTab == Tab.CATEGORY
                && !this.categories.isEmpty();
        if (withCategories) {
            attachCategoryRail();
        }

        ScrollPanel list = new ScrollPanel();
        addChild(list);
        this.grid = list;
        list.setSpacing(RadioCardWidget.GRID_SPACING)
                .setScrollStrength(44)
                .setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL);
        final double gridTop = withCategories ? GRID_TOP_WITH_CATEGORIES : GRID_TOP;
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
            // chip 游标每帧归位：chip 的 beforeRender 每帧都跑一次排版，游标不归位
            // 就是每帧累加一次，整排 chip 会漂出屏幕。复位后由第一个 tab chip 从头
            // 推进；入口 chip 前的 14px 间隔由最后一个 tab chip 的排版补上。
            chipCursor[0] = MARGIN;
            // 网格视口宽 = 面板宽 - 页边距：列宽在这一帧分给所有卡片，ScrollPanel 稍后摆位。
            RadioCardWidget.layoutGrid(Math.max(1.0, getWidth() - MARGIN * 2.0), this.cardWidgets);
        });

        if (loading && this.cards.isEmpty()) {
            PanelLoadingWidget spinner = new PanelLoadingWidget(status);
            addChild(spinner);
            spinner.setClickable(false);
            spinner.setBeforeRenderCallback(() -> spinner.setBounds(list.getRelativeX(), list.getRelativeY(),
                    Math.max(1.0, list.getWidth()), Math.max(1.0, list.getHeight())));
            return;
        }

        if (this.cards.isEmpty()) {
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
                    if (searchMode) submitSearch(searchKeyword);
                    else loadTab(currentTab);
                    return true;
                });
            }
            return;
        }

        // 卡片分帧构建：与主页网格同一条 32/帧的批处理。
        // 重建整列时重新武装首屏错峰档（与 NeteaseDiscoveryPanel 同一约定）：
        // beginFrame 只在上一帧发过号后关闭首屏让位，不 restart 的话重建后的
        // 首屏卡片拿不到 90ms 让位档，进场与整页转场叠帧。
        this.revealQueue.restart();
        this.pendingCards = new ArrayList<>(this.cards);
        this.pendingCardIndex = 0;
        buildPendingCards();
    }

    private void attachSearchField() {
        TextFieldWidget field = new TextFieldWidget(FontManager.pf14bold);
        addChild(field);
        this.searchField = field;
        field.setPlaceholder("搜索电台 / 播客…");
        // 搜索结果页重建布局时把关键词回填：结果到达/切 tab 都会重跑 renderLayout，
        // 不回填的话搜索完成后输入框是空的，改关键词必须全量重输。
        if (searchMode && !searchKeyword.isEmpty()) field.setText(searchKeyword);
        field.setShouldOverrideMouseCursor(true);
        field.setOnKeyTypedCallback((character, keyCode) -> {
            if (!field.isFocused()) return false;
            if (keyCode == Keyboard.KEY_ESCAPE) {
                field.setFocused(false);
                return true;
            }
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                field.setFocused(false);
                submitSearch(field.getText() == null ? "" : field.getText().trim());
                return true;
            }
            return true;
        });
        field.setBeforeRenderCallback(() -> {
            field.drawUnderline(false);
            field.setBounds(210.0, 20.0);
            field.setPosition(Math.max(MARGIN, getWidth() - MARGIN - 210.0), 10);
        });
    }

    /** DIFM / 广播两个次级入口 chip：外观与分区 tab 同语言，点击切到各自的页面。 */
    private void attachEntryChip(double[] cursor, String label, Runnable open) {
        RoundedButtonWidget chip = new RoundedButtonWidget(label, FontManager.pf12);
        addChild(chip);
        chip.setShouldOverrideMouseCursor(true);
        chip.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            open.run();
            return true;
        });
        chip.setBeforeRenderCallback(() -> {
            layoutChip(chip, label, cursor, TAB_ROW_Y);
            paintChip(chip, false);
        });
    }

    private void attachCategoryRail() {
        ScrollPanel rail = new ScrollPanel();
        addChild(rail);
        rail.setSpacing(8)
                .setScrollStrength(30)
                .setAlignment(ScrollPanel.Alignment.HORIZONTAL);
        rail.setBeforeRenderCallback(() -> rail.setBounds(MARGIN, GRID_TOP - 2.0,
                Math.max(1.0, getWidth() - MARGIN * 2.0), CHIP_HEIGHT));

        // 选中态按类目 id 判断；点击已选中的类目不再重发请求。
        // rail 是 HORIZONTAL ScrollPanel：横向摆位由 alignChildren 按滚动偏移每帧计算，
        // chip 这里只定宽度与配色，绝不能 setPosition —— 钉回去横向滚动就永远不动。
        for (final String[] category : categories) {
            RoundedButtonWidget chip = new RoundedButtonWidget(category[1], FontManager.pf12);
            rail.addChild(chip);
            chip.setShouldOverrideMouseCursor(true);
            chip.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (!category[0].equals(selectedCategoryId)) {
                    selectedCategoryId = category[0];
                    loadTab(Tab.CATEGORY);
                }
                return true;
            });
            chip.setBeforeRenderCallback(() -> {
                double textWidth = FontManager.pf12.getStringWidthD(category[1]);
                chip.setBounds(Math.max(28.0, textWidth + 16.0), RadioChips.CHIP_HEIGHT);
                paintChip(chip, category[0].equals(selectedCategoryId));
            });
        }
    }

    /** chip 排版与配色走 {@link RadioChips}：与 DIFM / 广播页共用同一套视觉口径。 */
    private void layoutChip(RoundedButtonWidget chip, String label, double[] cursor, double y) {
        RadioChips.layout(chip, label, cursor, y);
    }

    private void paintChip(RoundedButtonWidget chip, boolean selected) {
        RadioChips.paint(chip, selected, this.accentColor, this.hoverColor);
    }

    // ── 数据加载 ─────────────────────────────────────────────────────────────

    private void loadTab(Tab tab) {
        this.currentTab = tab;
        this.searchMode = false;
        final int generation = ++loadGeneration;
        this.loading = true;
        this.failed = false;
        this.status = "正在获取…";
        renderLayout();

        MultiThreadingUtil.runAsync(() -> {
            try {
                List<DjRadio> loaded;
                String statusText;
                switch (tab) {
                    case CATEGORY: {
                        ensureCategories(generation);
                        // 分类目录读本地快照（ensureCategories 带回的解析结果），不跨线程读
                        // categories 主线程集合。快照为空说明目录还没回来，退回默认分类。
                        List<String[]> cats = categoriesSnapshot;
                        String cateId = selectedCategoryId.isEmpty() && cats != null && !cats.isEmpty()
                                ? cats.get(0)[0] : selectedCategoryId;
                        List<DjRadio> result = DjRadioService.parseRadios(
                                CloudMusicApi.djRecommendByCategory(cateId).toJsonObject());
                        String cateName = categoryNameFromSnapshot(cats, cateId);
                        statusText = (cateName.isEmpty() ? "分类推荐" : "分类 · " + cateName)
                                + (result.isEmpty() ? " · 暂无内容" : " · " + result.size() + " 个电台");
                        loaded = result;
                        break;
                    }
                    case TOPLIST: {
                        List<DjRadio> hot = DjRadioService.parseRadios(
                                CloudMusicApi.djToplist(1, 60, 0).toJsonObject());
                        List<DjRadio> merged = mergeRadios(hot, DjRadioService.parseRadios(
                                CloudMusicApi.djPaygift(24, 0).toJsonObject()));
                        statusText = "热门电台 · " + merged.size();
                        loaded = merged;
                        break;
                    }
                    case MINE: {
                        if (!NeteaseMusicService.isLoggedIn(MusicPlatform.NETEASE)) {
                            publish(generation, Collections.emptyList(), "登录网易云账号后可查看订阅与最近收听");
                            return;
                        }
                        List<DjRadio> subscribed = DjRadioService.parseRadios(
                                CloudMusicApi.djSublist(50, 0).toJsonObject());
                        List<DjRadio> recent = DjRadioService.parseRadios(
                                CloudMusicApi.recordRecentDjRadio(50).toJsonObject());
                        List<DjRadio> merged = mergeRadios(subscribed, recent);
                        statusText = "我的订阅 " + subscribed.size() + " · 最近收听 " + recent.size();
                        loaded = merged;
                        break;
                    }
                    default: {
                        // 推荐：个性推荐（登录）+ 精选电台；两者都空时退回热门电台兜底。
                        List<DjRadio> personalized = DjRadioService.parseRadios(
                                CloudMusicApi.djPersonalizeRecommend(30).toJsonObject());
                        List<DjRadio> merged = mergeRadios(personalized, DjRadioService.parseRadios(
                                CloudMusicApi.djRecommend().toJsonObject()));
                        if (merged.isEmpty()) {
                            merged = DjRadioService.parseRadios(
                                    CloudMusicApi.djHot(40, 0).toJsonObject());
                        }
                        statusText = "为你推荐 · " + merged.size();
                        loaded = merged;
                        break;
                    }
                }
                publish(generation, loaded, statusText);
            } catch (Throwable throwable) {
                publishFailure(generation, throwable);
            }
        });
    }

    /** 分类 tab：分类目录只取一次；结果到达时若本页还活着就补铺 rail。 */
    private void ensureCategories(int generation) {
        // 竞态要点：categories 只在主线程读写。后台线程自己 fetch 并解析（ensureCategories
        // 本来就跑在后台），把解析结果原样带回来，绝不跨线程读 categories —— 之前在后台
        // 读 categories.get(0)/categoryName 会与主线程的 clear()+addAll() 撞出 CME。
        if (categoriesSnapshot != null) return;
        JsonObject root = CloudMusicApi.djCategoryList().toJsonObject();
        List<String[]> parsed = DjRadioService.parseCategories(root);
        MultiThreadingUtil.runOnMainThread(() -> {
            if (generation != loadGeneration || parsed.isEmpty()) return;
            categories.clear();
            categories.addAll(parsed);
            categoriesSnapshot = parsed;
            renderLayout();
        });
    }

    private String categoryName(String cateId) {
        for (String[] category : categories) {
            if (category[0].equals(cateId)) return category[1];
        }
        return "";
    }

    /** 后台线程版：只在 ensureCategories 带回的快照里查，不碰主线程集合。 */
    private static String categoryNameFromSnapshot(List<String[]> snapshot, String cateId) {
        if (snapshot == null) return "";
        for (String[] category : snapshot) {
            if (category[0].equals(cateId)) return category[1];
        }
        return "";
    }

    private void submitSearch(String keyword) {
        if (keyword.isEmpty()) {
            // 空关键词回车 = 退出搜索回到当前分区。
            loadTab(currentTab);
            return;
        }
        this.searchMode = true;
        this.searchKeyword = keyword;
        final int generation = ++loadGeneration;
        this.loading = true;
        this.failed = false;
        this.status = "正在搜索…";
        renderLayout();
        MultiThreadingUtil.runAsync(() -> {
            try {
                List<DjRadio> result = DjRadioService.searchRadios(keyword);
                publish(generation, result, "搜索 · " + keyword
                        + (result.isEmpty() ? " · 没有找到相关电台" : " · " + result.size() + " 个结果"));
            } catch (Throwable throwable) {
                publishFailure(generation, throwable);
            }
        });
    }

    private void publish(int generation, List<DjRadio> loaded, String statusText) {
        MultiThreadingUtil.runOnMainThread(() -> {
            if (generation != loadGeneration) return;
            this.cards = loaded == null ? Collections.emptyList() : loaded;
            this.status = statusText == null ? "" : statusText;
            this.loading = false;
            renderLayout();
        });
    }

    private void publishFailure(int generation, Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
                || throwable.getMessage().trim().isEmpty()
                ? "网络请求失败" : throwable.getMessage().trim();
        MultiThreadingUtil.runOnMainThread(() -> {
            if (generation != loadGeneration) return;
            this.cards = Collections.emptyList();
            this.failed = true;
            this.status = "获取失败：" + message;
            this.loading = false;
            renderLayout();
        });
    }

    /** 按 id 去重合并多段列表，保持先到先得的顺序（订阅在前、最近收听在后）。 */
    private static List<DjRadio> mergeRadios(List<DjRadio>... groups) {
        Map<Long, DjRadio> merged = new LinkedHashMap<>();
        for (List<DjRadio> group : groups) {
            if (group == null) continue;
            for (DjRadio radio : group) {
                if (radio != null && radio.getId() > 0L) merged.putIfAbsent(radio.getId(), radio);
            }
        }
        return new ArrayList<>(merged.values());
    }

    // ── 卡片 ─────────────────────────────────────────────────────────────────

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
                DjRadio radio = pendingCards.get(index);
                if (radio == null) continue;
                RadioCardWidget card = new RadioCardWidget(radio, () -> openRadio(radio), null);
                card.setGridContainer(this.grid);
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

    private void openRadio(DjRadio radio) {
        if (radio == null) return;
        switch (radio.getKind() == null ? DjRadio.Kind.DJ_RADIO : radio.getKind()) {
            case DIFM_CHANNEL:
                NCMScreen.getInstance().setCurrentPanel(new DifmPanel());
                break;
            case BROADCAST_CHANNEL:
                NCMScreen.getInstance().setCurrentPanel(new BroadcastPanel());
                break;
            default:
                NCMScreen.getInstance().setCurrentPanel(new RadioDetailPanel(radio));
                break;
        }
    }
}
