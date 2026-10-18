package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseRecentPlaysService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
* 一个小巧可复用的网易发现界面。它刻意将服务商负载保留在边界处，并为可播放的歌曲集合打开现有的
* PlaylistPanel，从而使发现功能不会分叉出独立的播放器、歌词或下载流水线。
*/
public final class NeteaseDiscoveryPanel extends NCMPanel {

    public enum Page {
        HOT_SEARCH("热搜", "网易云实时热搜"),
        DIGITAL_ALBUMS("我的数字专辑", "已购买的数字专辑"),
        TOP_LISTS("排行榜", "网易云音乐榜单"),
        RECENT_SONGS("最近播放", "最近播放的歌曲");

        final String title;
        final String subtitle;

        Page(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    // ── 热搜榜（W1）视觉常量 ────────────────────────────────────────────
    // 单位是布局单位 L（设计单位 D = 2L），字号/间距口径对齐「视觉精修 + 性能」规范。
    /** 行高 22L：榜单行比原来的 28L 整宽按钮矮，一屏能多放三行。 */
    private static final double HOT_ROW_HEIGHT = 22.0;
    /** 行间距 2L：榜单行之间只要一条呼吸缝，不构成卡片分组感。 */
    private static final double HOT_ROW_SPACING = 2.0;
    /** 行圆角 6L。 */
    private static final double HOT_ROW_RADIUS = 6.0;
    /** 名次列：右对齐的小盒 x=4、宽 20L；1~3 名走强调色，其余次要色。 */
    private static final double HOT_RANK_X = 4.0;
    private static final double HOT_RANK_BOX_WIDTH = 20.0;
    private static final int HOT_ACCENT_RANK_LIMIT = 3;
    /** 关键词左缘 30L = 名次盒右缘 24L + 6L 缝；关键词与 meta 之间再留 8L。 */
    private static final double HOT_KEYWORD_X = 30.0;
    private static final double HOT_KEYWORD_META_GAP = 8.0;
    /** meta 右对齐到行右缘内 10L 处；最大宽度取 min(35% 行宽, 160L)。 */
    private static final double HOT_META_RIGHT_MARGIN = 10.0;
    private static final double HOT_META_MAX_WIDTH_RATIO = .35;
    private static final double HOT_META_MAX_WIDTH = 160.0;
    /**
     * hover 背景的指数逼近半衰期。45ms 时约 120ms 到 85%、160ms 到 92%，正好落在
     * 120~160ms 这一档；同时是实时毫秒驱动，掉帧时不会变成「一帧闪过去」。
     */
    private static final double HOT_HOVER_HALF_LIFE_MILLIS = 45.0;
    /** 逼近到这个距离内就直接归位，不为不可见的差值继续算。 */
    private static final float HOT_HOVER_EPSILON = .01f;
    /** 单帧时间上限：页面切走再切回时，别让积攒的毫秒把 hover 一步跳完。 */
    private static final long HOT_HOVER_MAX_DT_MILLIS = 100L;

    private final Page page;
    private final MusicPlatform platform;
    private volatile List<Entry> entries = Collections.emptyList();
    private volatile boolean loading;
    private volatile String status = "";
    /** 上一次请求是不是抛异常失败的：只有这种空态才可点重试。 */
    private volatile boolean failed;
    /** 热搜行「一件件落进来」的错峰发号器，见 {@link PanelItemEntrance.RevealQueue}。 */
    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();
    /**
     * 本帧主题色。{@code NCMTheme.getColor} 是全局同步入口，而热搜一屏有几十行、每行要读四种
     * 颜色；集中在这里一帧取一次，行渲染只做已缓存读值。主题过渡期间颜色本来就会逐帧变化，
     * 所以这些值每帧重取，行为与逐处取色一致。
     */
    private int accentColor, primaryTextColor, secondaryTextColor, hoverColor;

    public NeteaseDiscoveryPanel(Page page) {
        this(page, MusicPlatform.NETEASE);
    }

    /**
     * 共享发现界面：QQ 路由复用与现有网易页面相同的列表、排版和
     * 播放流水线，而非复制一套 UI。
     */
    public NeteaseDiscoveryPanel(Page page, MusicPlatform platform) {
        this.page = page == null ? Page.HOT_SEARCH : page;
        this.platform = platform == null ? MusicPlatform.NETEASE : platform;
    }

    @Override
    public void onInit() {
        // 顺序很关键：先把 loading 立起来再建布局。原来是先 renderLayout()（此时 loading 还是 false）
        // 再 loadPage()（在那里才置 true，但不会重建布局），于是列表区第一帧就是空的 ——
        // 表现就是"最近播放没有加载动画"，只有副标题那一行小字在动。
        loading = true;
        status = "正在获取…";
        renderLayout();
        loadPage();
    }

    private void renderLayout() {
        getChildren().clear();
        final double margin = 12.0;
        final boolean hotSearch = this.page == Page.HOT_SEARCH;

        LabelWidget title = new LabelWidget(() -> pageTitle(), FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(this.primaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, margin));

        LabelWidget subtitle = new LabelWidget(() -> status.isEmpty() ? pageSubtitle() : status, FontManager.pf12);
        addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(this.secondaryTextColor)
                .setMaxWidth(Math.max(1.0, getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, 39));

        ScrollPanel list = new ScrollPanel();
        addChild(list);
        // 热搜行间距 2L（榜单行之间只要一条缝）；其它页面保持原来的 6L，行为不变。
        list.setSpacing(hotSearch ? HOT_ROW_SPACING : 6);
        list.setScrollStrength(40);
        list.setAlignment(ScrollPanel.Alignment.VERTICAL);
        if (hotSearch) {
            // 热搜行等高，直接交给 ScrollPanel 的等高快速路径：每帧只排版视口附近的行，
            // 而不是为几十行做一次全量扫描（见 ScrollPanel#setUniformVerticalItemHeight）。
            list.setUniformVerticalItemHeight(HOT_ROW_HEIGHT);
        }
        list.setBeforeRenderCallback(() -> list.setBounds(margin, 60,
                Math.max(1.0, getWidth() - margin * 2.0), Math.max(1.0, getHeight() - 74)));

        // 面板回调跑在所有子控件之前：本帧要用的主题色、进场发号与滚动状态在这里一次算好。
        // NCMTheme.getColor 是全局同步入口，而热搜一屏几十行、每行四种颜色；逐行取会把锁次数
        // 放大到几百次，集中取一次之后行渲染只做已缓存读值。
        this.setBeforeRenderCallback(() -> {
            this.accentColor = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            this.primaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
            this.secondaryTextColor = NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
            this.hoverColor = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            this.revealQueue.beginFrame();
            this.revealQueue.setAnimationsSuppressed(list.isScrollActive());
        });

        if (loading && entries.isEmpty()) {
            // 和歌单页、主页用同一枚转子。原来这里只有一行不会动的"正在获取…"，
            // 看起来像页面卡住了而不是在等网络。
            PanelLoadingWidget spinner = new PanelLoadingWidget("正在获取" + pageTitle() + "…");
            addChild(spinner);
            spinner.setClickable(false);
            spinner.setBeforeRenderCallback(() -> spinner.setBounds(list.getRelativeX(), list.getRelativeY(),
                    Math.max(1.0, list.getWidth()), Math.max(1.0, list.getHeight())));
            return;
        }

        if (entries.isEmpty()) {
            if (hotSearch) {
                // 空态与失败态共用这枚控件：失败只留一行字（转子的含义是"还在忙"，配在终态上
                // 自相矛盾），整行可点重试；空态不可点，和原来一样只是提示。
                final boolean retryable = failed;
                final String rawNotice = status.isEmpty() ? "暂无可显示内容" : status;
                // 失败文案带异常信息时可能长到溢出页面；首帧拿到真实宽度后折一次并缓存，
                // 不在渲染回调里反复 trim。
                final String[] noticeText = {null};
                PanelLoadingWidget notice = new PanelLoadingWidget(() -> {
                    String text = noticeText[0];
                    if (text == null) {
                        text = FontManager.pf14.trim(rawNotice, Math.max(1.0, getWidth() - margin * 2.0));
                        noticeText[0] = text;
                    }
                    return text;
                }).setSpinning(false);
                addChild(notice);
                notice.setClickable(retryable);
                notice.setShouldOverrideMouseCursor(retryable);
                notice.setBeforeRenderCallback(() -> notice.setBounds(list.getRelativeX(), list.getRelativeY(),
                        Math.max(1.0, list.getWidth()), Math.max(1.0, list.getHeight())));
                if (retryable) {
                    notice.setOnClickCallback((x, y, button) -> {
                        if (button != 0 || loading) return false;
                        loadPage();
                        // 立刻重铺一次让转子当帧替掉失败文案；网络结果回来时那边还会再铺一次。
                        MultiThreadingUtil.runOnMainThread(this::renderLayout);
                        return true;
                    });
                }
                return;
            }
            // 其它页面保持原来的空态小字。
            LabelWidget empty = new LabelWidget(status.isEmpty() ? "暂无可显示内容" : status, FontManager.pf14bold);
            list.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> empty
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setBounds(Math.max(1.0, list.getWidth()), 22));
            return;
        }

        if (hotSearch) {
            // 重建整列时重新武装首屏那一档（首次加载、重试成功都会走到这里）。
            this.revealQueue.restart();
            for (Entry entry : entries) {
                HotSearchRow row = new HotSearchRow(entry);
                row.setEntranceAnimation(this.revealQueue);
                list.addChild(row);
            }
            return;
        }

        for (Entry entry : entries) {
            RoundedButtonWidget row = new RoundedButtonWidget(entry::getTitle, FontManager.pf14bold);
            list.addChild(row);
            row.setRadius(5);
            row.setGlassPlate(GlassLook.Surface.PLAYER);
            row.setOnClickCallback((x, y, button) -> {
                if (button != 0 || entry.action == null) return button == 0;
                entry.action.run();
                return true;
            });
            row.setBeforeRenderCallback(() -> {
                row.setBounds(Math.max(1.0, list.getWidth()), 28);
                row.setColor(row.isHovering() && entry.action != null
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                row.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
                row.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            LabelWidget detail = new LabelWidget(entry::getSubtitle, FontManager.pf12);
            row.addChild(detail);
            detail.setClickable(false);
            detail.setBeforeRenderCallback(() -> detail
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setMaxWidth(Math.max(1.0, row.getWidth() - 14))
                    .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                    .setPosition(7, Math.max(14, row.getHeight() - detail.getHeight() - 3)));
        }
    }

    /**
     * 行内文字的顶边：按字体的<b>行盒</b>居中，而不是按这串字的墨迹居中 —— 名次「1」和「12」
     * 墨迹宽度不同，逐串居中会让同一列文字的基线各走各的。字体还没解析出来时
     * {@code getFontHeight()} 是负数，那时退回行顶（与 {@code LyricSearchOverlay#textY} 同一条判据）。
     */
    private static double textY(double rowTop, double rowHeight, CFontRenderer font) {
        final double box = font == null ? 0.0 : font.getFontHeight();
        return rowTop + (rowHeight - (box > 0.0 ? box : rowHeight)) * .5;
    }

    /**
     * 热搜榜的一行：名次 / 关键词 / 右侧 meta 三栏，整行可点。
     *
     * <p>三串文字用字体直接画、不挂标签子控件：一屏几十行，每行三个 {@code LabelWidget} 就是
     * 上百个控件要走完整棵渲染树（隐藏检查、悬停判定、tooltip 遍历）。文字、折行结果和每帧
     * 要读的颜色都在行宽变化时算一次，{@code onRender} 里只剩算术与绘制。</p>
     *
     * <p>刻意不做常驻底板与玻璃 plate，也不加任何二次动效：hover 只推背景那一层 alpha，
     * 行高与三栏位置全程不动。</p>
     */
    private final class HotSearchRow extends AbstractWidget<HotSearchRow> {

        /** 名次 + 关键词与右侧 meta 各自用哪一档字体，对齐规范里的 14bold / 12。 */
        private final CFontRenderer denseFont = FontManager.pf14bold;
        private final CFontRenderer metaFont = FontManager.pf12;

        private final Entry entry;
        private final String rankText;
        private final String keywordRaw;
        private final String metaRaw;

        /** 只在行宽变化时重算：名次右对齐起点、关键词折行、meta 折行与右对齐起点。 */
        private double laidOutWidth = -1.0;
        private double rankTextWidth = -1.0;
        private double rankTextX = HOT_RANK_X;
        private double metaTextX = 0.0;
        private String keywordText = "";
        private String metaText = "";

        /** 与曲目行、主页卡片同一套进场（{@link PanelItemEntrance}）。 */
        private final PanelItemEntrance entrance = new PanelItemEntrance();
        private double entranceEased = 1.0;
        /** 第一次被渲染（= 刚滚进视口）时向发号器领一个错峰号。 */
        private PanelItemEntrance.RevealQueue entranceQueue;

        /** hover 背景的 0..1 强度；只作用在那一层 ELEMENT_HOVER 的 alpha 上。 */
        private float hoverProgress;
        private long lastHoverMillis;

        private HotSearchRow(Entry entry) {
            this.entry = entry;
            // 名次与两串文字在构建期定稿：渲染回调里不再拼字符串。
            this.rankText = entry.rank > 0 ? Integer.toString(entry.rank) : "";
            this.keywordRaw = entry.getTitle();
            this.metaRaw = entry.getSubtitle();
            this.setShouldOverrideMouseCursor(true);
            this.setOnClickCallback((x, y, button) -> {
                if (button != 0 || entry.action == null) return false;
                entry.action.run();
                return true;
            });
            this.setBeforeRenderCallback(() -> {
                // 只用 (宽,高) 重载：四参 setBounds 会把 ScrollPanel 每帧排好的滚动位置抹掉。
                this.setBounds(Math.max(1.0, this.getParentWidth()), HOT_ROW_HEIGHT);
                this.updateEntranceAnimation();
                this.ensureColumnsLaidOut();
                this.advanceHover();
            });
        }

        /**
         * 挂上发号器但不立刻起算：{@code ScrollPanel} 不渲染视口外的行，所以本行第一次被渲染
         * 就是它刚滚进视口的那一帧；建页时把号发完的话，下面的行在滚到之前早演完了。
         */
        void setEntranceAnimation(PanelItemEntrance.RevealQueue queue) {
            this.entranceQueue = queue;
            this.entranceEased = 0.0;
            this.setAlpha(0f);
            this.setTransformations(() -> PanelItemEntrance.applyTransform(this.entranceEased,
                    this.getX() + this.getWidth() * .5, this.getY() + this.getHeight() * .5));
        }

        private void updateEntranceAnimation() {
            if (!this.entrance.isStarted()) {
                if (this.entranceQueue == null) return;
                this.entrance.begin(System.currentTimeMillis(), this.entranceQueue.nextDelayMillis());
            }
            if (this.entrance.isFinished()) return;

            this.entranceEased = this.entrance.tick();
            this.setAlpha((float) this.entranceEased);
            if (this.entrance.isFinished()) {
                // 摘掉变换：一屏几十行不该为已经停住的行一直付一次 pushMatrix。
                this.setTransformations(null);
            }
        }

        private void finishEntranceAnimation() {
            this.entrance.finishImmediately();
            this.entranceEased = 1.0;
            this.setAlpha(1f);
            this.setTransformations(null);
        }

        /** 快速滚动时别把半透明的行再带回来。 */
        @Override
        public void onViewportExit() {
            if (!this.entrance.isFinished()) this.finishEntranceAnimation();
        }

        /** 页面被中断导航丢掉之前，把这行的进场状态收干净。 */
        @Override
        public void finishTransientAnimations() {
            this.finishEntranceAnimation();
            this.entranceQueue = null;
            super.finishTransientAnimations();
        }

        /**
         * 三栏位置只在行宽变化时重算。窗口缩放是唯一会改变行宽的路径；把它挡在每帧之外，
         * 滚动时就不会出现「每行每帧一次 fitWidth/trim」。
         */
        private void ensureColumnsLaidOut() {
            final double width = this.getWidth();
            if (width == this.laidOutWidth) return;
            this.laidOutWidth = width;

            if (this.rankTextWidth < 0.0) {
                this.rankTextWidth = this.denseFont.getStringWidthD(this.rankText);
            }
            // 名次右对齐到小盒（x=4 宽 20）的右缘；真到三位数时允许向左多占一两个像素。
            this.rankTextX = Math.max(0.0, HOT_RANK_X + HOT_RANK_BOX_WIDTH - this.rankTextWidth);

            // meta 先折到预算内、再量一次实际宽度，关键词据此给右侧留出 8L 的缝。
            final double metaBudget = Math.min(width * HOT_META_MAX_WIDTH_RATIO, HOT_META_MAX_WIDTH);
            this.metaText = this.metaFont.trim(this.metaRaw, metaBudget);
            final double metaWidth = this.metaFont.getStringWidthD(this.metaText);
            this.metaTextX = Math.max(HOT_KEYWORD_X + 1.0, width - HOT_META_RIGHT_MARGIN - metaWidth);
            final double keywordMaxWidth = Math.max(1.0,
                    this.metaTextX - HOT_KEYWORD_META_GAP - HOT_KEYWORD_X);
            this.keywordText = this.denseFont.trim(this.keywordRaw, keywordMaxWidth);
        }

        /**
         * hover 只推背景那一层 alpha：按实时毫秒做指数逼近（剩余距离每半衰期减半），与帧率
         * 无关，也不会碰行高与三栏位置 —— 鼠标划过去时文字不能跟着动。
         */
        private void advanceHover() {
            final long now = System.currentTimeMillis();
            final long elapsed = this.lastHoverMillis == 0L ? 16L
                    : Math.min(HOT_HOVER_MAX_DT_MILLIS, Math.max(1L, now - this.lastHoverMillis));
            this.lastHoverMillis = now;

            final float target = this.isHovering() ? 1f : 0f;
            if (this.hoverProgress == target) return;

            final double remain = Math.pow(0.5, elapsed / HOT_HOVER_HALF_LIFE_MILLIS);
            this.hoverProgress = (float) (target + (this.hoverProgress - target) * remain);
            if (Math.abs(target - this.hoverProgress) < HOT_HOVER_EPSILON) {
                this.hoverProgress = target;
            }
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            final float alpha = this.getAlpha();
            if (alpha <= .002f) return; // 进场第一帧整行不可见，省掉这一帧的三次文字绘制

            final double x = this.getX();
            final double y = this.getY();
            final double width = this.getWidth();
            final double height = this.getHeight();

            if (this.hoverProgress > .002f) {
                // hover 语言只有「底变亮」这一件事：色块 alpha 从 0 逼近 ELEMENT_HOVER，
                // 行高、圆角与三栏文字位置一律不动。
                this.roundedRect(x, y, width, height, HOT_ROW_RADIUS,
                        reAlpha(hoverColor, this.hoverProgress * alpha));
            }

            final double denseY = textY(y, height, this.denseFont);
            final int rankColor = this.entry.rank > 0 && this.entry.rank <= HOT_ACCENT_RANK_LIMIT
                    ? accentColor : secondaryTextColor;
            this.denseFont.drawString(this.rankText, x + this.rankTextX, denseY, reAlpha(rankColor, alpha));
            this.denseFont.drawString(this.keywordText, x + HOT_KEYWORD_X, denseY,
                    reAlpha(primaryTextColor, alpha));
            this.metaFont.drawString(this.metaText, x + this.metaTextX, textY(y, height, this.metaFont),
                    reAlpha(secondaryTextColor, alpha));
        }
    }

    private void loadPage() {
        loading = true;
        failed = false;
        status = "正在获取…";
        MultiThreadingUtil.runAsync(() -> {
            try {
                if (platform == MusicPlatform.QQ) {
                    loadQQPage();
                    return;
                }
                if (platform == MusicPlatform.KUGOU) {
                    loadKuGouPage();
                    return;
                }
                // 这三页是"中转"：取完数据就把用户送到别处，自己不显示内容。导航栏现在走
                // NCMScreen#navigateAsync 直达，正常不会再进到这里；仍然保留是因为它们还能被
                // 历史记录、外部调用重新实例化。切页用 replaceCurrentPanel —— 用 setCurrentPanel
                // 会把这个中转页留在历史里，按返回退回来它又会重新加载再把人送走。
                if (page == Page.TOP_LISTS) {
                    NCMPanel target = loadTopListsPanel();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (target == null) {
                            status = "排行榜暂无内容";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().replaceCurrentPanel(target);
                        }
                    });
                    return;
                }
                if (page == Page.DIGITAL_ALBUMS) {
                    NCMPanel target = loadDigitalAlbumsPanel();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (target == null) {
                            status = "暂无已购买数字专辑或登录状态已失效";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().replaceCurrentPanel(target);
                        }
                    });
                    return;
                }
                if (page == Page.RECENT_SONGS) {
                    NCMPanel target = loadRecentSongsPanel();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (target == null) {
                            status = "暂无最近播放记录或登录状态已失效";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().replaceCurrentPanel(target);
                        }
                    });
                    return;
                }

                List<Entry> loaded = requestEntries();
                entries = loaded;
                status = loaded.isEmpty() ? "未获取到内容或当前账号无权限" : "共 " + loaded.size() + " 项";
                loading = false;
                MultiThreadingUtil.runOnMainThread(this::renderLayout);
            } catch (Throwable throwable) {
                entries = Collections.emptyList();
                // 只有抛异常才算「失败」：空列表是数据侧的正常结果，不该给重试入口。
                failed = true;
                status = "获取失败：" + safeMessage(throwable);
                loading = false;
                MultiThreadingUtil.runOnMainThread(this::renderLayout);
            }
        });
    }

    private void loadQQPage() {
        if (page == Page.HOT_SEARCH) {
            List<String> keywords = com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.hotSearches(50);
            List<Entry> hot = new ArrayList<>();
            int rank = 1;
            for (String keyword : keywords) {
                final String selected = keyword;
                hot.add(new Entry(rank++, keyword, "点击搜索",
                        () -> openLegacySongSearch(selected)));
            }
            entries = hot;
            status = hot.isEmpty() ? "QQ 音乐热搜暂无内容" : "共 " + hot.size() + " 项";
            loading = false;
            MultiThreadingUtil.runOnMainThread(this::renderLayout);
            return;
        }
        if (page != Page.TOP_LISTS) {
            entries = Collections.emptyList();
            status = "该 QQ 功能暂未由当前数据源提供";
            loading = false;
            MultiThreadingUtil.runOnMainThread(this::renderLayout);
            return;
        }

        // 走到这里说明是被历史记录/外部调用重新实例化的旧入口：导航栏现在直达卡片墙
        // （loadQQTopListsPanel），这条按歌曲行展示的兜底保留着，取的是默认热歌榜。
        final List<Music> tracks = NeteaseMusicService.getQQTopTracks(100);
        List<Entry> loaded = new ArrayList<>();
        for (int index = 0; index < tracks.size(); index++) {
            final int playIndex = index;
            final Music track = tracks.get(index);
            if (track == null) continue;
            String artist = track.getArtistsName() == null ? "" : track.getArtistsName();
            String album = track.getAlbum() == null || track.getAlbum().getName() == null ? "" : track.getAlbum().getName();
            String subtitle = artist + (artist.isEmpty() || album.isEmpty() ? "" : " · ") + album;
            loaded.add(new Entry((index + 1) + ". " + track.getName(), subtitle,
                    () -> CloudMusic.play(tracks, playIndex)));
        }
        entries = loaded;
        status = loaded.isEmpty() ? "QQ 音乐排行榜暂无内容" : "QQ 音乐巅峰榜 · " + loaded.size() + " 首";
        loading = false;
        MultiThreadingUtil.runOnMainThread(this::renderLayout);
    }

    /**
     * 酷狗分支。
     *
     * <p>酷狗的热搜/榜单/最近播放都能落到同一套 {@code Entry} 列表上，所以不走 QQ 那种
     * 「整页替换成卡片墙」的路径——这里只填列表内容，布局仍由公共的 {@link #renderLayout} 负责。</p>
     */
    private void loadKuGouPage() {
        if (page == Page.TOP_LISTS) {
            NCMPanel target = loadKuGouTopListsPanel();
            MultiThreadingUtil.runOnMainThread(() -> {
                loading = false;
                if (target == null) {
                    status = "酷狗排行榜暂无内容";
                    renderLayout();
                } else {
                    NCMScreen.getInstance().replaceCurrentPanel(target);
                }
            });
            return;
        }
        if (page == Page.RECENT_SONGS) {
            NCMPanel target = loadKuGouRecentSongsPanel();
            MultiThreadingUtil.runOnMainThread(() -> {
                loading = false;
                if (target == null) {
                    status = "暂无最近播放记录或登录状态已失效";
                    renderLayout();
                } else {
                    NCMScreen.getInstance().replaceCurrentPanel(target);
                }
            });
            return;
        }
        if (page == Page.DIGITAL_ALBUMS) {
            // 酷狗没有「数字专辑」这个概念，用每日推荐顶上——它同样是「平台替用户挑的内容」。
            NCMPanel target = loadKuGouDailyPanel();
            MultiThreadingUtil.runOnMainThread(() -> {
                loading = false;
                if (target == null) {
                    status = "酷狗每日推荐暂无内容";
                    renderLayout();
                } else {
                    NCMScreen.getInstance().replaceCurrentPanel(target);
                }
            });
            return;
        }

        List<String> keywords = KuGouMusicService.hotSearch();
        List<Entry> hot = new ArrayList<>();
        int rank = 1;
        for (String keyword : keywords) {
            final String selected = keyword;
            hot.add(new Entry(rank++, keyword, "点击搜索",
                    () -> openKuGouSongSearch(selected)));
        }
        entries = hot;
        status = hot.isEmpty() ? "酷狗热搜暂无内容" : "酷狗实时热搜 · 共 " + hot.size() + " 项";
        loading = false;
        MultiThreadingUtil.runOnMainThread(this::renderLayout);
    }

    /** 酷狗热搜点击后按关键词搜索，与网易云那条路径保持一致的单结果列表体验。 */
    private static void openKuGouSongSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        MultiThreadingUtil.runAsync(() -> {
            List<Music> songs = KuGouMusicService.search(keyword.trim(), 100);
            MultiThreadingUtil.runOnMainThread(() -> openSongs("搜索 · " + keyword.trim(), songs));
        });
    }

    /** 酷狗排行榜卡片墙，与 {@link #loadQQTopListsPanel()} 同形。 */
    public static NCMPanel loadKuGouTopListsPanel() {
        List<PlayList> cards = KuGouMusicService.rankPlaylists();
        if (cards.isEmpty()) return null;
        return new HomePanel(cards, "酷狗排行榜 · " + cards.size());
    }

    /** 酷狗最近播放，与 {@link #loadRecentSongsPanel()} 同形。 */
    public static NCMPanel loadKuGouRecentSongsPanel() {
        List<Music> songs = KuGouMusicService.recentSongs(100);
        if (songs.isEmpty()) return null;
        return buildSongsPanel(songs, false);
    }

    /** 汽水最近播放，与 {@link #loadRecentSongsPanel()} 同形；条目与发现流同形，去重取最新。 */
    public static NCMPanel loadQishuiRecentSongsPanel() {
        List<Music> songs = QishuiMusicService.recentlyPlayedTracks(100);
        if (songs.isEmpty()) return null;
        return buildSongsPanel(songs, false);
    }

    /** 酷狗每日推荐。 */
    public static NCMPanel loadKuGouDailyPanel() {
        List<Music> songs = KuGouMusicService.dailyRecommend();
        if (songs.isEmpty()) return null;
        return buildSongsPanel(songs, false);
    }

    private String pageTitle() {
        if (platform == MusicPlatform.QQ && page == Page.TOP_LISTS) return "QQ 音乐排行榜";
        if (platform == MusicPlatform.QQ && page == Page.HOT_SEARCH) return "QQ 音乐热搜";
        if (platform == MusicPlatform.KUGOU) {
            switch (page) {
                case TOP_LISTS: return "酷狗排行榜";
                case HOT_SEARCH: return "酷狗热搜";
                case RECENT_SONGS: return "酷狗最近播放";
                case DIGITAL_ALBUMS: return "酷狗每日推荐";
                default: break;
            }
            return page.title;
        }
        return page.title;
    }

    private String pageSubtitle() {
        if (platform == MusicPlatform.QQ && page == Page.TOP_LISTS) return "QQ 音乐巅峰榜 · 复用播放器现有列表与播放样式";
        if (platform == MusicPlatform.QQ && page == Page.HOT_SEARCH) return "QQ 音乐实时热搜 · 点击直接搜索";
        if (platform == MusicPlatform.KUGOU) {
            switch (page) {
                case TOP_LISTS: return "酷狗榜单 · 数据来自酷狗官方排行";
                case HOT_SEARCH: return "酷狗实时热搜 · 点击直接搜索";
                case RECENT_SONGS: return "酷狗听歌历史 · 需要登录";
                case DIGITAL_ALBUMS: return "酷狗每日推荐 · 每天更新";
                default: break;
            }
            return page.subtitle;
        }
        return page.subtitle;
    }

    private List<Entry> requestEntries() {
        switch (page) {
            case HOT_SEARCH:
                return hotEntries();
            default:
                return Collections.emptyList();
        }
    }

    private List<Entry> hotEntries() {
        JsonObject root = CloudMusicApi.searchHotDetail().toJsonObject();
        JsonArray data = array(root, "data");
        List<Entry> result = new ArrayList<>();
        if (data == null) return result;
        int index = 1;
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String keyword = text(item, "searchWord", "first", "keyword");
            if (keyword.isEmpty()) continue;
            // 右侧 meta 优先用接口给的 content（热搜说明/热度文案）；缺了就退回 score 折算，
            // 再没有才显示「点击搜索」—— 那一栏总有东西可读，又不编造新数据。
            String meta = text(item, "content");
            if (meta.isEmpty()) {
                long score = number(item, "score");
                meta = score > 0L ? "热度 " + score : "点击搜索";
            }
            final String selected = keyword;
            result.add(new Entry(index++, keyword, meta, () -> openLegacySongSearch(selected)));
        }
        return result;
    }

    /**
     * 热搜快捷方式刻意复用与侧边栏相同的单结果列表搜索流程。
     */
    private static void openLegacySongSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        MultiThreadingUtil.runAsync(() -> {
            List<Music> songs = CloudMusic.search(keyword.trim());
            MultiThreadingUtil.runOnMainThread(() -> openSongs("搜索 · " + keyword.trim(), songs));
        });
    }

    private static CardPage digitalAlbumCards() {
        JsonObject root = CloudMusicApi.digitalAlbumPurchased(100, 0).toJsonObject();
        JsonArray data = nestedArray(root, "paidAlbums", "albums", "data");
        List<PlayList> cards = new ArrayList<>();
        Map<Long, Runnable> actions = new HashMap<>();
        if (data == null) return new CardPage(cards, actions);
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject product = element.getAsJsonObject();
            JsonObject album = object(product, "album");
            if (album == null) album = product;
            long id = number(album, "id");
            String name = text(album, "name", "albumName");
            String cover = text(album, "picUrl", "coverImgUrl", "coverUrl");
            if (id <= 0 || name.isEmpty()) continue;
            PlayList card = new PlayList(id, name, cover, (int) Math.min(Integer.MAX_VALUE, number(album, "size", "trackCount")),
                    0L, null, "数字专辑", 0L);
            card.setPlatform(com.muoniumplayer.core.ncm.music.MusicPlatform.NETEASE);
            cards.add(card);
            final long albumId = id;
            actions.put(id, () -> openAlbum(albumId, name));
        }
        return new CardPage(cards, actions);
    }

    private static CardPage topListCards() {
        JsonObject root = CloudMusicApi.topListDetail().toJsonObject();
        JsonArray data = nestedArray(root, "list", "data");
        if (data == null) data = nestedArray(CloudMusicApi.topListDetailV2().toJsonObject(), "list", "data");
        if (data == null) data = nestedArray(CloudMusicApi.topLists().toJsonObject(), "list", "data");
        List<PlayList> cards = new ArrayList<>();
        if (data == null) return new CardPage(cards, Collections.emptyMap());
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject list = element.getAsJsonObject();
            long id = number(list, "id");
            String name = text(list, "name");
            String cover = text(list, "coverImgUrl", "coverUrl", "picUrl");
            if (id <= 0 || name.isEmpty()) continue;
            cards.add(new PlayList(id, name, cover, (int) Math.min(Integer.MAX_VALUE, number(list, "trackCount")), 0L,
                    null, text(list, "description", "updateFrequency"), 0L));
        }
        return new CardPage(cards, Collections.emptyMap());
    }

    private static void openAlbum(long albumId, String albumName) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject root = CloudMusicApi.album(albumId).toJsonObject();
                JsonObject privilegeRoot = CloudMusicApi.albumPrivilege(albumId).toJsonObject();
                JsonArray songs = firstSongArray(root, "songs");
                JsonArray privileges = array(privilegeRoot, "privileges", "data");
                if (privileges == null) {
                    JsonObject data = object(privilegeRoot, "data");
                    privileges = array(data, "privileges", "songs", "data");
                }
                List<Music> parsed = parseSongs(songs, privileges);
                MultiThreadingUtil.runOnMainThread(() -> openSongs("专辑 · " + albumName, parsed));
            } catch (Throwable ignored) {
            }
        });
    }

    private static void openPlaylist(long playlistId, String name) {
        try {
            JsonObject item = new JsonObject();
            item.addProperty("id", playlistId);
            item.addProperty("name", name);
            item.addProperty("coverImgUrl", "");
            item.addProperty("trackCount", 0);
            NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(JsonUtils.parse(item, PlayList.class)));
        } catch (Throwable ignored) {
        }
    }

    public static void openSongs(String title, List<Music> songs) {
        openSongs(title, songs, true);
    }

    private static void openSongs(String title, List<Music> songs, boolean showBackButton) {
        // setCurrentPanel() 恰好只调用一次 onInit()。在此处再次调用它会
        // 每行都重复，导致最近播放页面渲染出重叠文字。
        NCMScreen.getInstance().setCurrentPanel(buildSongsPanel(songs, showBackButton));
    }

    /**
     * 只<b>构造</b>歌曲列表页而不切页。可以在工作线程调用（不碰 GL，{@code onInit} 由
     * {@code setCurrentPanel} 在主线程触发），所以导航栏那条"先取数据再一次切页"的路径能复用它。
     */
    private static PlaylistPanel buildSongsPanel(List<Music> songs, boolean showBackButton) {
        CloudMusic.currentPlaylistContext = null;
        PlayList playlist = JsonUtils.parse("{}", PlayList.class);
        playlist.setSearchMode(true);
        playlist.setMusics(new CopyOnWriteArrayList<>(songs == null ? Collections.<Music>emptyList() : songs));
        playlist.setMusicsQueried(true);
        playlist.setMusicsLoaded(true);
        return new PlaylistPanel(playlist, showBackButton);
    }

    /**
     * 以下三个工厂给 {@code NCMScreen#navigateAsync} 用：<b>在工作线程</b>取数据并直接返回目标页面，
     * 返回 {@code null} 表示没有内容可展示（调用方留在当前页面并给一条提示）。
     *
     * <p>它们存在的理由是这三个入口本来就不是"页面"，只是"取完数据去哪"的决策 ——
     * 原来靠一个空的中转面板承载这段等待，代价是两次页面切换加一条回不去的历史。</p>
     */
    public static NCMPanel loadTopListsPanel() {
        CardPage cards = topListCards();
        if (cards.items.isEmpty()) return null;
        return new HomePanel(cards.items, "排行榜 · " + cards.items.size());
    }

    /**
     * QQ 音乐榜单卡片墙。
     *
     * <p>原来 QQ 的"排行榜"只写死两个榜（26 / 4）并按歌曲行展示；这里改成把 QQ 自己那份榜单目录
     * （{@code fcg_myqq_toplist}，实测 25 个榜）铺成卡片，和网易云榜单页保持同一种形态。</p>
     *
     * <p>卡片不预取曲目：{@code platformPlaylistId} 写成 {@code toplist:<id>}，点进去由
     * {@code PlayList#queryMusics} 走 QQ 那条既有的懒加载路径，所以这一页只发一个请求。</p>
     */
    public static NCMPanel loadQQTopListsPanel() {
        List<com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.TopList> lists =
                com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.topLists();
        if (lists.isEmpty()) return null;
        List<PlayList> cards = new ArrayList<>();
        for (com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.TopList list : lists) {
            PlayList card = new PlayList(-3_000_000L - list.id, list.title, list.coverUrl, 0, 0L,
                    null, "QQ 音乐榜单", 0L);
            card.setPlatform(MusicPlatform.QQ);
            card.setPlatformPlaylistId("toplist:" + list.id);
            cards.add(card);
        }
        return new HomePanel(cards, "QQ 音乐榜单 · " + cards.size());
    }

    public static NCMPanel loadDigitalAlbumsPanel() {
        CardPage cards = digitalAlbumCards();
        if (cards.items.isEmpty()) return null;
        return new HomePanel(cards.items, "我的数字专辑 · " + cards.items.size(), cards.actions);
    }

    public static NCMPanel loadRecentSongsPanel() {
        JsonObject root = CloudMusicApi.recentSongs(100).toJsonObject();
        List<Music> songs = parseSongsFromRecentResponse(root);
        if (songs.isEmpty()) return null;
        return buildSongsPanel(songs, false);
    }


    /**
     * 最近播放的解析已经搬到 {@link NeteaseRecentPlaysService}：搜索结果起播也要用同一份数据，
     * 两处各写一份解析迟早会分叉。这里保留一层薄封装，本页的调用点不必关心它在哪。
     */
    private static List<Music> parseSongsFromRecentResponse(JsonObject root) {
        return NeteaseRecentPlaysService.parseRecentSongs(root, NeteaseRecentPlaysService.MAX_RECENT_SONGS);
    }

    private static List<Music> parseSongs(JsonArray songs) {
        return parseSongs(songs, null);
    }

    private static List<Music> parseSongs(JsonArray songs, JsonArray privileges) {
        if (songs == null) return Collections.emptyList();
        Map<Long, JsonObject> privilegesBySongId = new HashMap<>();
        if (privileges != null) {
            for (JsonElement privilegeElement : privileges) {
                if (!privilegeElement.isJsonObject()) continue;
                JsonObject privilege = privilegeElement.getAsJsonObject();
                long id = number(privilege, "id");
                if (id <= 0) id = number(privilege, "songId");
                if (id > 0) privilegesBySongId.put(id, privilege);
            }
        }

        List<Music> result = new ArrayList<>();
        for (int index = 0; index < songs.size(); index++) {
            JsonElement element = songs.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            JsonObject actual = object(song, "song");
            if (actual == null) actual = song;
            try {
                Music music = JsonUtils.parse(actual, Music.class);
                JsonObject privilege = privilegesBySongId.get(music.getId());
                if (privilege == null && privileges != null && index < privileges.size() && privileges.get(index).isJsonObject()) {
                    privilege = privileges.get(index).getAsJsonObject();
                }
                // 复用现有的最高音质 / VIP / 专辑徽章元数据路径。
                music.applyNeteaseMetadata(actual, privilege);
                result.add(music);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static JsonArray nestedArray(JsonObject root, String... names) {
        JsonArray direct = array(root, names);
        if (direct != null) return direct;
        JsonObject data = object(root, "data");
        direct = array(data, names);
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        return array(result, names);
    }

    private static JsonArray firstSongArray(JsonObject root, String firstKey) {
        JsonArray direct = array(root, firstKey);
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        if (result != null) {
            direct = array(result, firstKey, "songs");
            if (direct != null) return direct;
        }
        return null;
    }

    private static JsonArray array(JsonObject object, String... names) {
        if (object == null) return null;
        for (String name : names) {
            try {
                if (object.has(name) && object.get(name).isJsonArray()) return object.getAsJsonArray(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name) || !object.get(name).isJsonObject()) return null;
        return object.getAsJsonObject(name);
    }

    private static long number(JsonObject object, String... names) {
        if (object == null || names == null) return 0L;
        for (String name : names) {
            try {
                if (name != null && object.has(name) && !object.get(name).isJsonNull()) {
                    return object.get(name).getAsLong();
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static String text(JsonObject object, String... names) {
        if (object == null) return "";
        for (String name : names) {
            try {
                if (object.has(name) && !object.get(name).isJsonNull()) {
                    String value = object.get(name).getAsString();
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String artistText(JsonObject album) {
        String artist = text(object(album, "artist"), "name");
        if (!artist.isEmpty()) return artist;
        JsonArray artists = array(album, "artists");
        if (artists == null || artists.size() == 0 || !artists.get(0).isJsonObject()) return "";
        return text(artists.get(0).getAsJsonObject(), "name");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "网络请求失败" : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "网络请求失败" : message.trim();
    }

    private static final class CardPage {
        private final List<PlayList> items;
        private final Map<Long, Runnable> actions;

        private CardPage(List<PlayList> items, Map<Long, Runnable> actions) {
            this.items = items == null ? Collections.emptyList() : items;
            this.actions = actions == null ? Collections.emptyMap() : actions;
        }
    }

    private static final class Entry {
        /**
         * 热搜名次，从 1 开始；0 表示这条不是榜单条目 —— QQ 音乐排行榜那条兜底路径
         * 仍按歌曲行展示，不带名次列。
         */
        private final int rank;
        private final String title;
        private final String subtitle;
        private final Runnable action;

        /** 热搜条目：名次、关键词、右侧 meta 分开存，由 {@link HotSearchRow} 按三栏排版。 */
        private Entry(int rank, String title, String subtitle, Runnable action) {
            this.rank = rank;
            this.title = title == null || title.trim().isEmpty() ? "未命名" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.action = action;
        }

        /** 非榜单条目（QQ 榜单兜底歌曲行）：沿用原来的整宽按钮行。 */
        private Entry(String title, String subtitle, Runnable action) {
            this(0, title, subtitle, action);
        }

        private String getTitle() {
            return title;
        }

        private String getSubtitle() {
            return subtitle;
        }
    }
}
