package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.BilibiliFavoriteService;
import com.muoniumplayer.core.ncm.music.BilibiliPlaylistService;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.ImportedPlaylistStore;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.core.ncm.music.NeteaseSearchSuggestService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.BrandVectorIcons;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.AccountAvatar;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NcmSheet;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.SegmentControl;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.SheetHeroRelay;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/16 22:00
 */
public class NavigateBar extends NCMPanel {

    TextFieldWidget searchField = new TextFieldWidget(FontManager.pf14bold);
    /**
     * 网易云搜索同时支持单曲和歌单；QQ Cadence 目前只提供单曲。
     */
    private boolean playlistSearchMode;
    ScrollPanel playlistPanel = new ScrollPanel();

    /**
     * 这个来源有没有「歌单」这一档搜索。
     *
     * <p>这件事原来被写了三遍而且互相对不上：决定可见性的名单含 BILIBILI，决定可点击的那份不含
     * （于是 B 站下这颗控件看着能点、点了没反应，而 B 站歌单搜索的分支其实早就写好了），
     * 输入框让宽那一份又只认网易云与酷狗（于是汽水和 B 站下它直接压在输入框文字上）。
     * 现在只有这一份，可见、可点、让宽三处全部从它推。</p>
     */
    private static boolean supportsPlaylistSearch(final MusicPlatform platform) {
        return platform == MusicPlatform.NETEASE || platform == MusicPlatform.KUGOU
                || platform == MusicPlatform.QISHUI || platform == MusicPlatform.BILIBILI;
    }

    /**
     * 高亮条从一栏跳到另一栏的基准时长。默认动画速度档（3.0，即最快那一档）下实际走约 300ms。
     *
     * <p>写这个数的时候按<b>除完倍率</b>那一档估观感：{@link SheetClock#speed()} 的默认值就是
     * 上限，所以基础常量天然要写大三倍，否则「300ms」在真机上只有 100ms。</p>
     */
    private static final double INDICATOR_MS = 900.0;
    /** 没有选中项时它淡出要多少毫秒（同样按除完那一档估：默认档下 60ms）。 */
    private static final double INDICATOR_PRESENCE_MS = 180.0;
    /** 单帧最多补这么多毫秒：与换页那条同一档，掉帧时整段变慢而不是一帧跨过小半条曲线。 */
    private static final double INDICATOR_MAX_FRAME_MS = 40.0;
    /** 过冲最多越过目标这么多逻辑像素：长距离跳转时 9.7% 的过冲会变成十几像素的过火。 */
    private static final double INDICATOR_OVERSHOOT_CAP = 5.0;

    /**
     * 侧栏选中项的高亮条。它是共享的一条，而不是每个条目自己画一块：只有共享的一条才能在切换栏目时
     * 从旧位置滑到新位置。放在 {@link #playlistPanel} 之前加入，因此绘制在条目文字之下。
     */
    private final RoundedRectWidget selectionIndicator = new RoundedRectWidget();
    /** 高亮条此刻摆在哪（相对侧栏）。每帧由「目标 + 残差 × (1 - 缓动)」算出来。 */
    private double indicatorTop, indicatorBottom, indicatorLeft, indicatorWidth;
    /**
     * 四条通道各自相对<b>当前目标</b>的残差，也就是这一趟的起点离目标有多远。
     *
     * <p>存残差而不是存起点，是为了让「列表滚动」这件事根本不需要处理：位置是相对目标算的，
     * 目标被滚动带着走，条子就跟着走。旧写法存的是绝对坐标，所以滚列表时高亮条在慢慢追自己
     * 那一行，追的过程就是一条肉眼可见的漂移。</p>
     */
    private double residualTop, residualBottom, residualLeft, residualWidth;
    /** 本趟进度 0..1。1 = 已经贴住目标。 */
    private double indicatorLegT = 1.0;
    /** 这一趟往下走：下边沿是前缘（快、带过冲），上边沿是后缘（慢），于是条子被拉长。 */
    private boolean indicatorLeadBottom;
    /** 淡入淡出 0..1。只服务「这一格没有选中项了」，所以位移期间恒为 1，不会落位之后还在变亮。 */
    private double indicatorPresence;
    /** 第一帧直接落位，避免高亮条从侧栏顶部飞进来。 */
    private boolean indicatorPlaced;
    /** 高亮条现在属于哪一行。换行才起新的一趟，滚动与改宽度都不算。 */
    private PlaylistItem indicatorOwner;
    /** 上一帧的时刻，用来把进度换算成真实毫秒。 */
    private long indicatorLastNanos;
    private PlaylistItem homeItem;
    /** 快捷操作条上那颗公告按钮。让位判断挂在父组件的渲染里，见 {@link #onRender}。 */
    private SidebarActionButton announcementButton;
    private final List<PlaylistItem> playlistItems = new CopyOnWriteArrayList<>();

    // ── 搜索联想 ────────────────────────────────────────────────────────────
    /** 一条联想行的行高。 */
    private static final double SUGGEST_ROW_HEIGHT = 17.0;
    /** 面板上下的内边距。 */
    private static final double SUGGEST_PADDING = 3.0;
    /**
     * 面板左右距侧栏边缘的距离。
     *
     * <p>必须小于歌单条目的 4：条目占 x=4 到 width-4，面板窄了就盖不住它，鼠标停在面板上时
     * 下面那一行的强调色高亮会从两侧露出来。</p>
     */
    private static final double SUGGEST_INSET = 3.0;
    /**
     * 停止输入多久之后才发请求。
     *
     * <p>没有这段静默就会「打一个字发一次请求」：打完「周杰伦」等于三次请求，前两次的结果还没回来
     * 就已经过期了。180ms 是连续打字的自然间隔之上、又不至于让人觉得卡的一档。</p>
     */
    private static final long SUGGEST_DEBOUNCE_MILLIS = 180L;

    private final Panel suggestionPanel = new Panel();
    /**
     * 面板打开时铺在搜索栏下方的"点空白处收起"层。
     *
     * <p>它的点击回调<b>返回 true 把这一下吃掉</b>。原先返回 false 想做成"顺手收起、点击照常落到
     * 下面的条目上"，但那正是点击穿透：明明是想关掉联想面板，结果连带切了歌单、开了音乐来源弹窗。
     * 项目里 {@code ControlsBar#consumeQualityMenuClick} 与 {@code PlayQueuePanel#consumeClick}
     * 处理同类遮挡时也都是整块吃掉，这里保持一致。</p>
     */
    private final Panel suggestionDismissLayer = new Panel();
    private final List<SuggestionRow> suggestionRows = new ArrayList<>();
    /** 当前展示的联想结果。只在渲染线程里换引用，异步结果统一经由服务的缓存读回来。 */
    private List<NeteaseSearchSuggestService.Suggestion> suggestions = Collections.emptyList();
    private String suggestQuery = "";
    private long suggestQueryChangedAt;
    /** 用户主动收起（Esc / 已提交搜索）之后，同一个关键词不再自动弹回来。 */
    private String suggestDismissedFor;
    private int suggestHighlight = -1;
    /** 0 → 1 的展开进度，只用于淡入与轻微下滑。 */
    private float suggestReveal;
    private boolean suggestionsVisible;
    /** 搜索框底边（相对侧栏），联想面板挂在它下面。由搜索框自己的回调写入。 */
    private double searchBarBottom = 24.0;

    public NavigateBar() {
        this.layout();
    }

    private void layout() {
        RectWidget bg = new RectWidget();
        this.addChild(bg);

        this.setBeforeRenderCallback(() -> {
            double panelWidth = NCMScreen.getInstance().getPanelWidth();
            double responsiveMinimum = Math.min(92.0, panelWidth * .30);
            this.setBounds(Math.max(panelWidth * .15, responsiveMinimum), NCMScreen.getInstance().getPanelHeight());
            this.setPosition(0, 0);

            bg.setMargin(0);
            bg.setColor(this.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            bg.setAlpha(0.9f - NCMTheme.getLiquidGlassAmount() * 0.22f);

            // 高亮条的布局必须由侧栏来跑，不能挂在高亮条自己的 beforeRenderCallback 上：
            // AbstractWidget#renderWidget 在调用 beforeRenderCallback 之前就会因为 isHidden()
            // 直接返回（父组件的渲染循环也会跳过隐藏的子组件），而唯一能把它重新显示出来的代码
            // 就在那个回调里。于是首帧一旦因为列表还没完成布局而被判定为不可见，就会永久卡在隐藏
            // 状态——选中项的强调色高亮框再也不会出现。
            this.layoutSelectionIndicator();
            // 联想面板同理必须由侧栏驱动：它在没有建议时要把自己隐藏起来，而隐藏之后自己的回调就不会
            // 再被调用，只有父组件的回调能把它显示回来。
            this.layoutSuggestions();
        });

        this.setOnKeyTypedCallback((character, keyCode) -> {

            if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_F)) {
                this.searchField.setFocused(true);
                this.searchField.getTextField().selectAll();
                return true;
            }

            return false;
        });

        RoundedRectWidget searchBar = new RoundedRectWidget();
        RoundedRectWidget searchBarFocusAnimation = new RoundedRectWidget();

        this.addChild(searchBarFocusAnimation);
        this.addChild(searchBar);

        searchBarFocusAnimation.setBeforeRenderCallback(() -> {
            if (!searchField.isFocused()) {
                searchBarFocusAnimation.setAlpha(0);
            } else {
                searchBarFocusAnimation.setAlpha(Interpolations.interpolate(searchBarFocusAnimation.getAlpha(), 1f, .3f));
                searchBarFocusAnimation.setRadius(4);
                searchBarFocusAnimation.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                searchBarFocusAnimation.setBounds(searchBar.getRelativeX(), searchBar.getRelativeY(), searchBar.getWidth(), searchBar.getHeight());
                searchBarFocusAnimation.expand(1 + 5 * (1 - searchBarFocusAnimation.getAlpha()));
            }
        });

        searchBar
//            .setShouldSetMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    searchBar.setAlpha(1f);
                    searchBar.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
                    searchBar.setMargin(8);
                    searchBar.setHeight(16);
                    this.searchBarBottom = searchBar.getRelativeY() + searchBar.getHeight();
                    searchBar.setRadius(3.5);
                });

        RoundedRectWidget searchBarBg = new RoundedRectWidget();
        searchBar.addChild(searchBarBg);

        searchBarBg.setBeforeRenderCallback(() -> {
            searchBarBg.setMargin(.5);
            searchBarBg.setAlpha(.6f);
            searchBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            searchBarBg.setRadius(searchBar.getRadius() - .5);
        });

        LabelWidget lblSearchIcon = new LabelWidget("K", FontManager.music14);
        searchBar.addChild(lblSearchIcon);

        lblSearchIcon.setBeforeRenderCallback(() -> {
            lblSearchIcon.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblSearchIcon.centerVertically();
            lblSearchIcon.setPosition(lblSearchIcon.getRelativeY(), lblSearchIcon.getRelativeY());
        });

        // 「歌曲 / 歌单」两半常驻可读，滑块从一侧滑到另一侧。原来那颗 pill 只写着当前这一头，
        // 要点下去才知道另一头是什么，而且文字、底色、占位提示三处各跳各的。
        final SegmentControl.Spec modeSpec = new SegmentControl.Spec();
        modeSpec.y = 1.0;
        modeSpec.height = 14;
        modeSpec.radius = 3.5;
        modeSpec.inset = 1.5;
        modeSpec.durationMs = 240.0;
        modeSpec.font = FontManager.pf10bold;
        // 两半等宽是滑块能滑的前提，所以宽度按两个词里较宽的那个算；只按「歌单」算的话，
        // 哪天「歌曲」比它宽就会溢出自己那一半。
        final double modeLabel = Math.max(FontManager.pf10bold.getStringWidthD("歌曲"),
                FontManager.pf10bold.getStringWidthD("歌单"));
        final double modeWidth = 2 * (modeLabel + 8.0) + modeSpec.inset * 2;
        final SegmentControl searchMode = SegmentControl.attach(searchBar, () -> {
            modeSpec.width = modeWidth;
            modeSpec.x = Math.max(0, searchBar.getWidth() - modeWidth - 1);
            return modeSpec;
        }, () -> playlistSearchMode ? 1 : 0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                new String[]{"", ""}, new String[]{"歌曲", "歌单"},
                index -> {
                    playlistSearchMode = index == 1;
                    return true;
                });

        searchBar.addChild(searchField);

        this.searchField.setPlaceholder("搜索歌曲...");

        this.searchField.setOnKeyTypedCallback((character, keyCode) -> {
            if (this.searchField.isFocused()) {
                if (keyCode == Keyboard.KEY_ESCAPE) {
                    // 联想面板开着时，第一次 Esc 只收起面板，第二次才真正退出搜索框。
                    if (this.suggestionsVisible) {
                        this.dismissSuggestions();
                    } else {
                        this.searchField.setFocused(false);
                    }
                    return true;
                }

                if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_UP) {
                    if (this.moveSuggestionHighlight(keyCode == Keyboard.KEY_DOWN ? 1 : -1)) {
                        return true;
                    }
                }

                if (keyCode == Keyboard.KEY_TAB) {
                    // Tab 只把高亮的联想词填进输入框，不直接搜 —— 方便在联想词基础上继续改。
                    String completion = this.highlightedSuggestionText();
                    if (!completion.isEmpty()) {
                        this.searchField.setText(completion);
                        this.searchField.getTextField().setCursorPositionEnd();
                        this.suggestHighlight = -1;
                        return true;
                    }
                }

                if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                    // 有高亮的联想词就用它，否则用输入框里的原文。
                    String highlighted = this.highlightedSuggestionText();
                    if (!highlighted.isEmpty()) {
                        this.searchField.setText(highlighted);
                    }
                    final String keyword = this.searchField.getText() == null ? "" : this.searchField.getText().trim();
                    this.submitSearch(keyword);
                }

                return true;
            }

            return false;
        });

        searchField.setBeforeRenderCallback(() -> {
            searchField.drawUnderline(false);
            searchField.setMargin(2);
            double xSpacing = lblSearchIcon.getRelativeX() + lblSearchIcon.getWidth() + 4;
            searchMode.setEnabled(supportsPlaylistSearch(NeteaseMusicService.getCurrentPlatform()));
            // 让宽跟着淡出程度走：这一格淡出去的同时输入框就长回来，不会留下「控件没了、框还是窄的」。
            double typeToggleReserve = (modeWidth + 3.0) * searchMode.presence();
            searchField.setBounds(xSpacing, searchField.getRelativeY(),
                    Math.max(1.0, searchBar.getWidth() - xSpacing - typeToggleReserve), searchField.getHeight());
            // 占位提示里的词跟着滑块走、过半才换：在点击那一刻就改会出现「词换了、滑块还在半路」。
            searchField.setPlaceholder(searchMode.progress() > .5 ? "搜索歌单..." : "搜索歌曲...");
            searchField.setColor(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            searchField.setDisabledTextColor(RenderSystem.reAlpha(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .4f));
//            Rect.draw(searchField.getX(), searchField.getY(), searchField.getWidth(), searchField.getHeight(), 0x800090ff);
        });
        // 单个二级入口取代了旧的“网易云/QQ 双按钮切换器”，同时还
        // 在不挤占这条导航栏空间的情况下接入了 GD Studio 聚合平台。
        Panel sourceSwitcher = new Panel();
        this.addChild(sourceSwitcher);
        sourceSwitcher.setBeforeRenderCallback(() -> {
            sourceSwitcher.setBounds(Math.max(1, this.getWidth() - 16), 24);
            sourceSwitcher.setPosition(8, searchBar.getRelativeY() + searchBar.getHeight() + 5);
        });

        RoundedButtonWidget sourceMenu = new RoundedButtonWidget(() -> {
            String provider = NeteaseMusicService.getCurrentPlatform().getDisplayName();
            return "音乐来源 · " + provider;
        }, FontManager.pf12bold);
        sourceSwitcher.addChild(sourceMenu);
        sourceMenu.setRadius(7);
        sourceMenu.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            // 起点必须在这一刻量：浮层里那一行的排版要到下一帧才有，而这颗按钮下一帧就会被
            // 悬停态改颜色、甚至被侧栏重建掉。
            NCMScreen.getInstance().openMusicSourceManager(SheetHeroRelay.from(sourceMenu.getX(),
                    sourceMenu.getY(), sourceMenu.getWidth(), sourceMenu.getHeight(), 7.0,
                    () -> NeteaseMusicService.getCurrentPlatform().getBrandColor()));
            return true;
        });
        sourceMenu.setBeforeRenderCallback(() -> {
            // 菜单位于一个普通 Panel 内部，因此要给它一个明确的点击区域。
            // 否则在导航栏重建后，它可能渲染出一个零尺寸的交互区域，
            // 导致无法从某个 GD 源切回
            // 官方源。
            sourceMenu.setBounds(Math.max(1, sourceSwitcher.getWidth()), sourceSwitcher.getHeight());
            sourceMenu.setPosition(0, 0);
            sourceMenu.setMargin(0);
            sourceMenu.setColor(sourceMenu.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            sourceMenu.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        // 先加入高亮条，再加入列表：渲染顺序即层次，高亮条要在条目文字下面。
        this.addChild(selectionIndicator);
        this.selectionIndicator.setClickable(false);

        this.addChild(playlistPanel);
        this.playlistPanel.setBeforeRenderCallback(() -> {
            this.playlistPanel.setMargin(0);
            double top = sourceSwitcher.getRelativeY() + sourceSwitcher.getHeight() + 7;
            this.playlistPanel.setPosition(this.playlistPanel.getRelativeX(), top);
            // 为紧凑快捷操作栏和账号入口预留底部空间。
            this.playlistPanel.setBounds(this.playlistPanel.getWidth(), Math.max(0, this.getHeight() - top - 70));
        });

        this.playlistPanel.setSpacing(4);


        {
            PlaylistItem item = new PlaylistItem("A", () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT), () -> "主页", () -> NCMScreen.getInstance().setCurrentPanel(new HomePanel()));
            item.setSelected(true);
            this.homeItem = item;

            item.setShouldOverrideMouseCursor(true);

            this.playlistPanel.addChild(item);
        }

        boolean qqMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.QQ;
        boolean kugouMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.KUGOU;
        boolean youtubeMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.YOUTUBE;
        boolean bilibiliMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.BILIBILI;
        boolean qishuiMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.QISHUI;
        // GD 聚合源沿用网易云那一套入口（原来 neteaseMode 就是这个含义），这里只把自有入口的平台单独拆出来。
        boolean neteaseMode = !qqMode && !kugouMode && !youtubeMode && !bilibiliMode && !qishuiMode;
        {
            LabelWidget lblDiscovery = new LabelWidget(
                    qishuiMode ? "汽水音乐" : bilibiliMode ? "哔哩哔哩" : youtubeMode ? "YouTube Music" : kugouMode ? "酷狗音乐发现"
                            : qqMode ? "QQ 音乐发现" : "网易云发现", FontManager.pf14bold);
            lblDiscovery.setClickable(false);
            lblDiscovery.setBeforeRenderCallback(() -> {
                lblDiscovery.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                lblDiscovery.setPosition(6, lblDiscovery.getRelativeY());
            });
            this.playlistPanel.addChild(lblDiscovery);

            // 排行榜：网易云的榜单页最终展示的是 HomePanel 卡片墙，先取数据再一次切页（navigateAsync），
            // 否则会先闪一个空的中转页、历史栈里也会多出一条回不去的记录。
            // QQ 的榜单不一样 —— 它是真的留在 NeteaseDiscoveryPanel 里按歌曲行展示，所以照旧直接切页。
            if (!youtubeMode && !bilibiliMode && !qishuiMode) {
                this.playlistPanel.addChild(new PlaylistItem(FontelloIcons.LEADERBOARD, FontManager.fontello16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "排行榜", () -> {
                    if (qqMode) {
                        // QQ 的榜单目录也是卡片墙（25 个榜），同样先取数据再一次切页。
                        NCMScreen.getInstance().navigateAsync("排行榜", NeteaseDiscoveryPanel::loadQQTopListsPanel);
                    } else if (kugouMode) {
                        // 酷狗榜单同样是卡片墙：先取数据再一次切页，避免先闪一个空的中转页。
                        NCMScreen.getInstance().navigateAsync("排行榜", () -> {
                            List<PlayList> ranks = KuGouMusicService.rankPlaylists();
                            return new HomePanel(ranks, "酷狗榜单 · " + ranks.size());
                        });
                    } else {
                        NCMScreen.getInstance().navigateAsync("排行榜", NeteaseDiscoveryPanel::loadTopListsPanel);
                    }
                }));
            }
            if (qqMode) {
                // QQ 也有自己的热搜端点，和网易云一样在发现页里展示列表，保持直接切页。
                this.playlistPanel.addChild(new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH, MusicPlatform.QQ))));
            }
            if (qishuiMode) {
                this.playlistPanel.addChild(new PlaylistItem("台", FontManager.pf12bold,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "电台", () -> NCMScreen.getInstance().navigateAsync(
                        "汽水电台", this::loadQishuiRadioPanel)));
            }
            if (kugouMode) {
                // 酷狗这三项和网易云同一形态：热搜在发现页里出列表，另外两项先取数据再一次切页。
                // 以前整块挂在 neteaseMode 下，所以切到酷狗后侧栏干脆没有这些入口。
                this.playlistPanel.addChild(new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH, MusicPlatform.KUGOU))));
                this.playlistPanel.addChild(new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "最近播放", () -> NCMScreen.getInstance()
                        .navigateAsync("最近播放", NeteaseDiscoveryPanel::loadKuGouRecentSongsPanel)));
                this.playlistPanel.addChild(new PlaylistItem("每", FontManager.pf12bold,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "每日推荐", () -> NCMScreen.getInstance()
                        .navigateAsync("每日推荐", NeteaseDiscoveryPanel::loadKuGouDailyPanel)));
                this.playlistPanel.addChild(new PlaylistItem(MusicBrandIcons.PERSONAL_FM, FontManager.musicBrand16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "私人 FM", () -> NCMScreen.getInstance().setCurrentPanel(new PersonalFmPanel())));
            }
            if (neteaseMode) {
                // 热搜是真正在发现页里展示列表的，保持直接切页。
                this.playlistPanel.addChild(new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH))));
                this.playlistPanel.addChild(new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "最近播放", () -> NCMScreen.getInstance()
                        .navigateAsync("最近播放", NeteaseDiscoveryPanel::loadRecentSongsPanel)));
                this.playlistPanel.addChild(new PlaylistItem(MusicBrandIcons.PERSONAL_FM, FontManager.musicBrand16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "私人 FM", () -> NCMScreen.getInstance().setCurrentPanel(new PersonalFmPanel())));
            }
        }
        // 「导入歌单」独立成栏，排在账号歌单之上：栏名即内容，一眼看得出哪来的歌单是本地粘贴
        // 导入的、哪些是登录账号带出来的。标题行右侧一颗「＋」是全渠道共用的导入入口——导入
        // 面板自己会识别平台，所以这里没必要每个平台各配一颗。
        Panel importHeader = new Panel();
        importHeader.setBeforeRenderCallback(() -> {
            importHeader.setBounds(Math.max(1, importHeader.getParentWidth() - 8), 16);
            importHeader.setPosition(4, importHeader.getRelativeY());
        });
        LabelWidget lblImported = new LabelWidget("导入歌单", FontManager.pf14bold);
        lblImported.setBeforeRenderCallback(() -> {
            lblImported.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblImported.setPosition(2, NcmSheet.inkTop(lblImported, importHeader.getHeight() * .5));
        });
        importHeader.addChild(lblImported);

        final Panel importHit = new Panel();
        // 「＋」是画出来的一枚记号（两条圆头短条交叉），既不是文字也不是图标字体：文字「+」看着
        // 就是个字、不成图标；party 图标字体最小一档是 15 逻辑像素的实心圆盘，压在一行墨迹高仅
        // 6.5 的栏名上必然显重。臂长按栏名自己的墨迹高来，换字体换字号都跟着走；两条短条的中心
        // 就是记号的中心，摆到行高的中点上，天然和 inkTop 那条基准重合。
        final RoundedRectWidget plusH = new RoundedRectWidget();
        final RoundedRectWidget plusV = new RoundedRectWidget();
        plusH.setClickable(false);
        plusV.setClickable(false);
        importHit.addChild(plusH);
        importHit.addChild(plusV);
        importHit.setShouldOverrideMouseCursor(true);
        importHit.setBeforeRenderCallback(() -> {
            // 命中区比记号大一圈：一颗「+」原样点起来太考验瞄准。
            importHit.setBounds(22, importHeader.getHeight());
            importHit.setPosition(importHeader.getWidth() - 26, 0);
            int color = NCMScreen.getColor(importHit.isHovering()
                    ? NCMScreen.ColorType.PRIMARY_TEXT : NCMScreen.ColorType.ACCENT);
            double arm = NcmSheet.labelInkHeight(lblImported) * 1.15;
            // 粗细不跟着臂长线性缩：Material 的 add 是 2/18 ≈ .11，照搬到 8 高的记号上只剩
            // 不到一个像素，画出来是虚的。给一个下限，宁可略粗也不要看不见。
            double thick = Math.max(1.35, arm * .18);
            double cx = importHit.getWidth() * .5;
            double cy = importHit.getHeight() * .5;
            plusH.setColor(color);
            plusV.setColor(color);
            plusH.setRadius(thick * .5);
            plusV.setRadius(thick * .5);
            plusH.setBounds(cx - arm * .5, cy - thick * .5, arm, thick);
            plusV.setBounds(cx - thick * .5, cy - arm * .5, thick, arm);
        });
        importHit.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().openPlaylistImport();
            return true;
        });
        importHeader.addChild(importHit);
        this.playlistPanel.addChild(importHeader);

        // 本地已导入的歌单不分平台展示：点开由 PlayList 按各自平台匿名拉曲目，右键移除本地引用。
        List<PlayList> importedPlaylists = ImportedPlaylistStore.list();
        if (importedPlaylists.isEmpty()) {
            PlaylistItem hint = new PlaylistItem("·",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "点右上角「＋」粘贴歌单链接",
                    () -> NCMScreen.getInstance().openPlaylistImport());
            hint.setShouldOverrideMouseCursor(true);
            this.playlistPanel.addChild(hint);
        }
        for (PlayList imported : importedPlaylists) {
            final PlayList ready = imported;
            PlaylistItem item = new PlaylistItem("D",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    ready::getName,
                    () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(ready)));
            item.setPlaylist(ready);
            item.setShouldOverrideMouseCursor(true);
            item.setOnSecondaryClick(() -> removeImportedPlaylist(ready));
            this.playlistItems.add(item);
            this.playlistPanel.addChild(item);
        }

        Panel playlistsHeader = new Panel();
        playlistsHeader.setBeforeRenderCallback(() -> {
            playlistsHeader.setBounds(Math.max(1, playlistsHeader.getParentWidth() - 8), 16);
            playlistsHeader.setPosition(4, playlistsHeader.getRelativeY());
        });
        LabelWidget lblPlaylists = new LabelWidget("我的账号歌单", FontManager.pf14bold);
        lblPlaylists.setBeforeRenderCallback(() -> {
            lblPlaylists.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 两栏标题同一套基准，否则「导入歌单」和「我的账号歌单」会互相错开半像素到几像素。
            lblPlaylists.setPosition(2, NcmSheet.inkTop(lblPlaylists, playlistsHeader.getHeight() * .5));
        });
        playlistsHeader.addChild(lblPlaylists);
        this.playlistPanel.addChild(playlistsHeader);

        if (qishuiMode) {
            if (NeteaseMusicService.isLoggedIn(MusicPlatform.QISHUI)) {
                // 账号歌单不再独占一行跳到二级页：走快照异步加载，
                // 直接摊在下面「我的账号歌单」栏里（与 QQ / 酷狗一致）。
                PlaylistItem favorites = new PlaylistItem("C",
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "我收藏的歌曲", () -> NCMScreen.getInstance().navigateAsync(
                        "我收藏的歌曲", this::loadQishuiCollectionPanel));
                favorites.setShouldOverrideMouseCursor(true);
                this.playlistPanel.addChild(favorites);
            } else {
                PlaylistItem login = new PlaylistItem("·",
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "登录可同步账号歌单",
                        () -> NCMScreen.getInstance().openAccountManager());
                login.setShouldOverrideMouseCursor(true);
                this.playlistPanel.addChild(login);
            }
        }

        // QQ / 酷狗 / B 站 / 汽水的账号歌单都是异步加载的快照；网易云继续使用现有 CloudMusic 缓存。
        List<PlayList> pl = qqMode
                ? NeteaseMusicService.getQQUserPlaylistsSnapshot()
                : kugouMode ? KuGouMusicService.getUserPlaylistsSnapshot()
                : bilibiliMode ? BilibiliFavoriteService.getUserPlaylistsSnapshot()
                : qishuiMode ? QishuiMusicService.getUserPlaylistsSnapshot()
                : youtubeMode ? Collections.emptyList() : CloudMusic.playLists;

        if (qqMode && NeteaseMusicService.isLoggedIn(MusicPlatform.QQ)
                && !NeteaseMusicService.areQQUserPlaylistsLoaded()) {
            NeteaseMusicService.ensureQQUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }
        if (kugouMode && NeteaseMusicService.isLoggedIn(MusicPlatform.KUGOU)
                && !KuGouMusicService.areUserPlaylistsLoaded()) {
            KuGouMusicService.ensureUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }
        if (bilibiliMode && NeteaseMusicService.isLoggedIn(MusicPlatform.BILIBILI)
                && !BilibiliFavoriteService.areUserPlaylistsLoaded()) {
            BilibiliFavoriteService.ensureUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }
        if (qishuiMode && NeteaseMusicService.isLoggedIn(MusicPlatform.QISHUI)
                && !QishuiMusicService.areUserPlaylistsLoaded()) {
            QishuiMusicService.ensureUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }

        // QQ 的创建/收藏歌单统一显示在“我的账号歌单”下，不再因为平台切换而隐藏整个入口。
        lblPlaylists.setHidden(false);
        if (pl != null && !pl.isEmpty()) {
            if (qqMode || kugouMode || bilibiliMode || qishuiMode) {
                for (PlayList playList : pl) {
                    PlaylistItem item = new PlaylistItem("D",
                            () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                            playList::getName,
                            () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                    item.setPlaylist(playList);
                    this.playlistItems.add(item);
                    item.setShouldOverrideMouseCursor(true);
                    this.playlistPanel.addChild(item);
                }
            } else {
                List<PlayList> playLists = pl.stream()
                        .filter(playList -> !playList.isSubscribed())
                        .collect(java.util.stream.Collectors.toList());
                for (int i = 0; i < playLists.size(); i++) {
                    PlayList playList = playLists.get(i);
                    PlaylistItem item = new PlaylistItem(i == 0 ? "C" : "D",
                            () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                            playList::getName,
                            () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                    item.setPlaylist(playList);
                    this.playlistItems.add(item);
                    item.setShouldOverrideMouseCursor(true);
                    this.playlistPanel.addChild(item);
                }
            }
        } else if (qqMode || kugouMode || bilibiliMode || qishuiMode) {
            String status;
            if (bilibiliMode) {
                status = !NeteaseMusicService.isLoggedIn(MusicPlatform.BILIBILI)
                        ? "登录哔哩哔哩后加载收藏夹"
                        : (BilibiliFavoriteService.areUserPlaylistsLoading()
                        ? "正在加载 B 站收藏夹…" : "暂无可用收藏夹");
            } else if (kugouMode) {
                status = !NeteaseMusicService.isLoggedIn(MusicPlatform.KUGOU)
                        ? "登录酷狗音乐后加载歌单"
                        : (KuGouMusicService.areUserPlaylistsLoading()
                        ? "正在加载酷狗歌单…" : "暂无可用酷狗歌单");
            } else if (qishuiMode) {
                status = !NeteaseMusicService.isLoggedIn(MusicPlatform.QISHUI)
                        ? "登录汽水音乐后加载歌单"
                        : (QishuiMusicService.areUserPlaylistsLoading()
                        ? "正在加载汽水歌单…" : "暂无可用汽水歌单");
            } else {
                status = !NeteaseMusicService.isLoggedIn(MusicPlatform.QQ)
                        ? "登录 QQ 音乐后加载歌单"
                        : (NeteaseMusicService.areQQUserPlaylistsLoading()
                        ? "正在加载 QQ 歌单…" : "暂无可用 QQ 歌单");
            }
            PlaylistItem statusItem = new PlaylistItem("·",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> status,
                    () -> NCMScreen.getInstance().openAccountManager());
            statusItem.setShouldOverrideMouseCursor(true);
            this.playlistPanel.addChild(statusItem);
        }

        LabelWidget lblSubscribed = new LabelWidget("收藏歌单", FontManager.pf14bold);
        lblSubscribed.setBeforeRenderCallback(() -> {
            lblSubscribed.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblSubscribed.setPosition(6, lblSubscribed.getRelativeY());
        });

        // 收藏歌单是网易云独有的分组：QQ 与酷狗的收藏歌单都已经并入上面的"我的账号歌单"。
        lblSubscribed.setHidden(qqMode || kugouMode || youtubeMode || bilibiliMode || qishuiMode);
        this.playlistPanel.addChild(lblSubscribed);

        if (neteaseMode && pl != null) {
            List<PlayList> subscribedPlaylists = pl.stream()
                    .filter(PlayList::isSubscribed)
                    .collect(java.util.stream.Collectors.toList());
            subscribedPlaylists.forEach(playList -> {
                PlaylistItem item = new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        playList::getName, () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                item.setPlaylist(playList);
                this.playlistItems.add(item);
                item.setShouldOverrideMouseCursor(true);
                this.playlistPanel.addChild(item);
            });
        }
        RoundedRectWidget accountButton = new RoundedRectWidget();
        this.addChild(accountButton);
        accountButton.setRadius(6);
        accountButton.setShouldOverrideMouseCursor(true);
        accountButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().openAccountManager(SheetHeroRelay.from(accountButton.getX(),
                    accountButton.getY(), accountButton.getWidth(), accountButton.getHeight(), 6.0,
                    () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT)));
            return true;
        });
        accountButton.setBeforeRenderCallback(() -> {
            accountButton.setBounds(Math.max(1, this.getWidth() - 16), 22);
            accountButton.setPosition(8, this.getHeight() - 27);
            accountButton.setColor(accountButton.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            accountButton.setAlpha(accountButton.isHovering() ? .85f : .38f);
        });

        RoundedImageWidget creatorAvatar = new RoundedImageWidget(this::getUserAvatarLocation, 0, 0, 0, 0);
        this.addChild(creatorAvatar);
        creatorAvatar.fadeIn();
        creatorAvatar.setClickable(false);
        creatorAvatar.setLinearFilter(true);
        this.loadAvatar();

        RoundedRectWidget quickActionsSurface = new RoundedRectWidget();
        this.addChild(quickActionsSurface);
        quickActionsSurface.setClickable(false);
        quickActionsSurface.setBeforeRenderCallback(() -> {
            // 保留原有的紧凑操作栏尺寸；只对字形渲染
            // 进行强化，使侧边栏布局和按钮尺寸保持不变。
            quickActionsSurface.setBounds(Math.max(1, this.getWidth() - 16), 32);
            quickActionsSurface.setPosition(8, this.getHeight() - 63);
            quickActionsSurface.setRadius(9);
            quickActionsSurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            quickActionsSurface.setAlpha(.90f);
        });

        SidebarActionButton btnTheme = new SidebarActionButton(SidebarActionIcon.THEME);
        SidebarActionButton btnFont = new SidebarActionButton(SidebarActionIcon.FONT);
        SidebarActionButton btnPlayerSize = new SidebarActionButton(SidebarActionIcon.SCALE);
        SidebarActionButton btnRefresh = new SidebarActionButton(SidebarActionIcon.REFRESH);
        // 公告排在最后：这四颗的位置用户已经用熟了，新来的那颗排到末尾比插进中间好。
        SidebarActionButton btnAnnouncement = new SidebarActionButton(SidebarActionIcon.ANNOUNCEMENT);
        btnAnnouncement.setUnreadBadge(() -> AnnouncementChecker.hasUnread());
        this.announcementButton = btnAnnouncement;
        quickActionsSurface.addChild(btnTheme, btnFont, btnPlayerSize, btnRefresh, btnAnnouncement);

        btnFont.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) {
                // 右键不做别的事，但也不放过去：这颗按钮下面就是账号行，穿透过去会打开账号管理。
                return mouseButton == 1;
            }
            NCMScreen.getInstance().openFontPicker();
            return true;
        });

        btnTheme.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0 && mouseButton != 1) return false;
            if (mouseButton == 1) {
                // 右键进入调色板：改的是当前主题的颜色配置项，不轮换主题。
                NCMScreen.getInstance().openThemePalette();
                return true;
            }
            NCMScreen.getInstance().cycleThemeFrom(
                    btnTheme.getX() + btnTheme.getWidth() * .5,
                    btnTheme.getY() + btnTheme.getHeight() * .5);
            DownloadDynamicIsland.showTheme(NCMTheme.getCurrentName());
            return true;
        });

        btnPlayerSize.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                NCMPlayerConfig.cycleScale();
            } else if (mouseButton == 1) {
                NCMPlayerConfig.resetScale();
            } else {
                return false;
            }
            DownloadDynamicIsland.showPlayerScale(NCMPlayerConfig.getPlayerScalePercent());
            return true;
        });

        btnRefresh.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return mouseButton == 1;
            if (!CloudMusic.beginNeteaseRefresh()) {
                btnRefresh.setSpinning(true);
                DownloadDynamicIsland.showPlaylistRefreshInProgress();
                return true;
            }

            btnRefresh.setSpinning(true);
            DownloadDynamicIsland.showPlaylistRefreshInProgress();
            MultiThreadingUtil.runAsync(() -> {
                CloudMusic.NeteaseRefreshResult result = CloudMusic.refreshNeteaseAccountData();
                MultiThreadingUtil.runOnMainThread(() -> {
                    try {
                        btnRefresh.setSpinning(false);
                        if (result.isSuccess()) {
                            NCMScreen.getInstance().markDirty();
                            // 云盘歌曲 ID 集合在
                            // 刷新期间异步加载。重建可见的播放列表行，以便
                            // newly available "网盘" badge is created immediately.
                            NCMScreen.getInstance().reloadCurrentPanel();
                            DownloadDynamicIsland.showPlaylistRefreshSuccess(
                                    result.getPlaylistCount(), result.getElapsedMillis());
                        } else {
                            DownloadDynamicIsland.showPlaylistRefreshFailure(result.getMessage());
                        }
                    } finally {
                        CloudMusic.endNeteaseRefresh();
                    }
                });
            });
            return true;
        });
        btnTheme.setBeforeRenderCallback(() -> {
            double actionWidth = railSlotWidth(quickActionsSurface.getWidth());
            btnTheme.setBounds(actionWidth, 28);
            btnTheme.setPosition(2, 2);
            btnTheme.setRadius(7);
            btnTheme.setColor(btnTheme.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        btnFont.setBeforeRenderCallback(() -> {
            double actionWidth = railSlotWidth(quickActionsSurface.getWidth());
            btnFont.setBounds(actionWidth, 28);
            btnFont.setPosition(4 + actionWidth, 2);
            btnFont.setRadius(7);
            btnFont.setColor(btnFont.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        btnPlayerSize.setBeforeRenderCallback(() -> {
            double actionWidth = railSlotWidth(quickActionsSurface.getWidth());
            btnPlayerSize.setBounds(actionWidth, 28);
            btnPlayerSize.setPosition(6 + actionWidth * 2, 2);
            btnPlayerSize.setRadius(7);
            btnPlayerSize.setColor(btnPlayerSize.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        btnRefresh.setBeforeRenderCallback(() -> {
            double actionWidth = railSlotWidth(quickActionsSurface.getWidth());
            btnRefresh.setBounds(actionWidth, 28);
            btnRefresh.setPosition(8 + actionWidth * 3, 2);
            btnRefresh.setRadius(7);
            // 这会刷新已登录的网易云账户及其歌单，这一操作
            // 与当前所选的搜索/内容提供方无关。
            btnRefresh.setAlpha(1f);
            btnRefresh.setColor(btnRefresh.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        btnAnnouncement.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) {
                // 右键什么都不做，但也不放过去：这颗按钮下面就是账号行，穿透过去会打开账号管理。
                return mouseButton == 1;
            }
            NCMScreen.getInstance().openAnnouncements();
            return true;
        });
        btnAnnouncement.setBeforeRenderCallback(() -> {
            double actionWidth = railSlotWidth(quickActionsSurface.getWidth());
            btnAnnouncement.setBounds(actionWidth, 28);
            btnAnnouncement.setPosition(10 + actionWidth * 4, 2);
            btnAnnouncement.setRadius(7);
            btnAnnouncement.setColor(btnAnnouncement.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        creatorAvatar.setBeforeRenderCallback(() -> {
            creatorAvatar.setBounds(16, 16);
            creatorAvatar.setPosition(12, this.getHeight() - 24);
            creatorAvatar.setRadius(7.25);
        });

        // 会员徽标：网易云已登录且是会员时出现在账号行右端。原来这条信息是主页左下角一行灰字，
        // 那个位置既压着内容网格、又和"我的账号"离得最远，看着像界面上多出来的一块。
        // 徽标先建：昵称的可用宽度要按它当帧的实际宽度让位，否则长昵称会压到徽标底下。
        final RoundedRectWidget vipPlate = new RoundedRectWidget();
        this.addChild(vipPlate);
        vipPlate.setClickable(false);
        final LabelWidget vipMark = new LabelWidget(PartyIcons.VIP, FontManager.partyIcon24);
        this.addChild(vipMark);
        vipMark.setClickable(false);
        final LabelWidget vipText = new LabelWidget("VIP", FontManager.pf10bold);
        this.addChild(vipText);
        vipText.setClickable(false);

        // 只写昵称，不再带「平台 · 」前缀：当前来源已经写在上面那颗「音乐来源 · XXX」按钮上，
        // 在这里再重复一遍只会把昵称挤成跑马灯 —— 侧栏就那么宽，右边还要给会员徽标让位。
        LabelWidget lblCreator = new LabelWidget(
                () -> NeteaseMusicService.getAccountName(NeteaseMusicService.getCurrentPlatform()),
                FontManager.pf14bold);
        this.addChild(lblCreator);
        lblCreator.setClickable(false);
        lblCreator.setBeforeRenderCallback(() -> {
            lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4,
                    creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
            lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            lblCreator.setMaxWidth(Math.max(1, this.getWidth() - lblCreator.getRelativeX() - 12
                    - (isNeteaseVip() ? vipPlate.getWidth() + 6 : 0)));
        });

        vipPlate.setBeforeRenderCallback(() -> {
            double width = 8.0 + VIP_ICON_BOX + 3.0 + FontManager.pf10bold.getStringWidthD("VIP") + 8.0;
            vipPlate.setRadius(VIP_PLATE_H * .5);
            vipPlate.setBounds(width, VIP_PLATE_H);
            vipPlate.setPosition(Math.max(0, this.getWidth() - 12 - width),
                    creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - VIP_PLATE_H * .5);
            // 底色由金色朝导航底色混出来。铺纯金会比旁边的昵称还抢眼 —— 身份标记不该压过账号本身。
            vipPlate.setColor(NcmSheet.mix(NCMScreen.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND),
                    NcmSheet.VIP_COLOR, .3));
            vipPlate.setAlpha(isNeteaseVip() ? 1f : 0f);
        });
        vipMark.setBeforeRenderCallback(() -> {
            vipMark.setColor(NcmSheet.VIP_COLOR);
            vipMark.setAlpha(isNeteaseVip() ? 1f : 0f);
            NcmSheet.placeIcon(vipMark, FontManager.partyIcon24,
                    vipPlate.getRelativeX() + 8.0 + VIP_ICON_BOX * .5,
                    vipPlate.getRelativeY() + VIP_PLATE_H * .5);
        });
        vipText.setBeforeRenderCallback(() -> {
            vipText.setColor(NcmSheet.VIP_COLOR);
            vipText.setAlpha(isNeteaseVip() ? 1f : 0f);
            vipText.setPosition(vipPlate.getRelativeX() + 8.0 + VIP_ICON_BOX + 3.0,
                    NcmSheet.inkTop(vipText, vipPlate.getRelativeY() + VIP_PLATE_H * .5));
        });

        // 联想面板最后加入：渲染顺序即层次，而 iterateChildrenMouseClick 是倒序遍历子组件的，
        // 所以最后加入的既画在最上面、也最先拿到点击。否则它下面的歌单条目会把点击抢走。
        this.buildSuggestionPanel();
    }

    /**
     * 搜索联想的下拉面板。
     *
     * <p>行是<b>预先建好的固定几个</b>，文案走 supplier 从 {@link #suggestions} 里按下标取 ——
     * 每帧按结果重建子组件会在渲染途中改动子列表，而且会让悬停/动画状态每帧丢一次。</p>
     */
    private void buildSuggestionPanel() {
        // 收起层先加入、面板后加入：倒序遍历时面板（连同它的行）先拿到点击，落在别处的点击才交给收起层。
        this.addChild(this.suggestionDismissLayer);
        this.suggestionDismissLayer.setHidden(true);
        this.suggestionDismissLayer.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            this.dismissSuggestions();
            // 吃掉这一下：这次点击的意图是"关掉联想面板"，不能同时触发它盖住的条目。
            return true;
        });

        this.addChild(this.suggestionPanel);
        this.suggestionPanel.setHidden(true);

        RoundedRectWidget surface = new RoundedRectWidget();
        this.suggestionPanel.addChild(surface);
        surface.setClickable(false);
        surface.setBeforeRenderCallback(() -> {
            surface.setMargin(0);
            surface.setRadius(4);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
            surface.setAlpha(this.suggestReveal);
        });

        RoundedRectWidget inner = new RoundedRectWidget();
        surface.addChild(inner);
        inner.setClickable(false);
        inner.setBeforeRenderCallback(() -> {
            inner.setMargin(.5);
            inner.setRadius(3.5);
            inner.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            // 完全不透明：面板底下就是歌单列表，留一点透明度会让下面的条目与它的悬停高亮透上来。
            inner.setAlpha(this.suggestReveal);
        });

        for (int index = 0; index < NeteaseSearchSuggestService.MAX_SUGGESTIONS; index++) {
            SuggestionRow row = new SuggestionRow(index);
            this.suggestionRows.add(row);
            this.suggestionPanel.addChild(row);
        }
    }

    /**
     * 快捷操作条第五颗按钮的「让位」判断。
     *
     * <p>必须放在父组件的渲染里而不是那颗按钮自己的回调里：被 {@code setHidden(true)} 的组件整帧
     * 跳过自己的回调，也就永远没有机会把自己再显示回来 —— 那样侧栏一收窄，公告图标就再也回不来了。</p>
     *
     * <p>让位的是最新加上的这颗：图标本身有 17.2 宽，五颗挤在窄侧栏上会互相叠边，而把四颗已经用惯
     * 的按钮挤扁是更糟的失败（同 {@code ControlsBar} 那条图标让位链的教训）。</p>
     */
    @Override
    public void onRender(double mouseX, double mouseY) {
        if (this.announcementButton != null) {
            this.announcementButton.setHidden(!railFitsAnnouncement(this.getWidth() - 16));
        }
        super.onRender(mouseX, mouseY);
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int mouseButton) {
        return false;
    }

    /**
     * 账号头像按需加载。
     *
     * <p>地址、贴图位置、节流全部交给 {@link AccountAvatar} —— 群唱样式的右侧头像用的是同一份。
     * 两处必须请出同一个地址：贴图位置是按账号定的，若一处要 32 px、另一处要 128 px，
     * 后下完的那张会盖掉前一张。</p>
     */
    private void loadAvatar() {
        AccountAvatar.textureOrNull();
    }

    private Location getUserAvatarLocation() {
        return NeteaseMusicService.getAccountAvatarLocation();
    }

    /**
     * 快捷操作条上一颗按钮的宽度。
     *
     * <p>左右各 2 像素留白、每两颗之间 2 像素，所以扣掉的是 {@code 4 + 2 * 颗数}。
     * 收成一个方法是因为五处 {@code beforeRenderCallback} 必须用同一个公式：其中任何一处
     * 落后一版，那颗按钮就会和邻居错开或者叠上去。</p>
     */
    private static double railSlotWidth(double railWidth) {
        int slots = railSlotCount(railWidth);
        // 左右各 2 像素留白，n 颗之间有 n-1 条 2 像素的缝。
        return Math.max(1, (railWidth - (4 + 2 * (slots - 1))) / slots);
    }

    /** 这一帧这条快捷操作条上有几颗按钮：窄到放不下第五颗时由公告让位。 */
    private static int railSlotCount(double railWidth) {
        return railFitsAnnouncement(railWidth) ? 5 : 4;
    }

    /**
     * 第五颗按钮放不放得下。
     *
     * <p>门槛取 18.5：图标底板宽 17.2，再窄邻居之间就会重叠，而这条按钮排是横向并排的，
     * 叠在一起的图标读起来就是「界面坏了」。侧栏收窄到 112 逻辑像素那一档正好落在门槛外侧，
     * 于是那一档只显示原来那四颗。</p>
     */
    private static boolean railFitsAnnouncement(double railWidth) {
        return (railWidth - 12) / 5.0 >= 18.5;
    }

    /** 会员徽标的胶囊高度与图标方格。方格必须是 partyIcon24 实际画出来的 12，填大了图标和字之间会空一块。 */
    private static final double VIP_PLATE_H = 15.0;
    private static final double VIP_ICON_BOX = 12.0;

    /**
     * 来源按钮上那枚矢量字标的方格。
     *
     * <p>和音源卡的圆盘（46 → 23）一样按「容器的一半」取，只是这一排本身小得多。
     * 不能照搬旧字形的高度：旧字标走 {@code pf12bold}，墨迹高只有 4.2，矢量按那个数画会小到看不见。</p>
     */
    private static final double SOURCE_ICON_BOX = 10.0;

    /**
     * 当前是不是「网易云 + 已登录 + 会员」。
     *
     * <p>三个条件缺一不可：QQ 与酷狗没有对应的会员字段，而 {@code CloudMusic.profile} 在切到别的
     * 来源之后仍然留着上一次的网易云资料，只看 profile 会让 QQ 来源下也挂着一枚网易云会员标。</p>
     */
    private static boolean isNeteaseVip() {
        return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.NETEASE
                && CloudMusic.profile != null && CloudMusic.profile.isVipMember();
    }

    private static String sourceGlyph(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) return MusicBrandIcons.QQ_MUSIC;
        if (platform == MusicPlatform.YOUTUBE) return MusicBrandIcons.YOUTUBE;
        if (platform == MusicPlatform.BILIBILI) return MusicBrandIcons.BILIBILI_MONOGRAM;
        if (platform == MusicPlatform.QISHUI) return MusicBrandIcons.QISHUI_MONOGRAM;
        return MusicBrandIcons.NETEASE_CLOUD_MUSIC;
    }

    private static CFontRenderer sourceFont(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) return FontManager.qqMusicIcon20;
        if (platform == MusicPlatform.YOUTUBE) return FontManager.youtubeIcon24;
        if (platform == MusicPlatform.BILIBILI) return FontManager.pf12bold;
        if (platform == MusicPlatform.QISHUI) return FontManager.pf12bold;
        return FontManager.musicBrand16;
    }

    private SourceButton createSourceButton(MusicPlatform platform) {
        SourceButton button = new SourceButton(platform);
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            boolean changed = NeteaseMusicService.getCurrentPlatform() != platform;
            // 该 setter 还会在选择官方源时清除任何残留的已持久化 GD 选择，
            // 即使可见源已经显示为网易云/QQ。
            NeteaseMusicService.setCurrentPlatform(platform);
            if (changed) {
                NCMScreen.getInstance().markDirty();
            }
            return true;
        });
        return button;
    }

    private void layoutSourceButton(SourceButton button, Panel parent, MusicPlatform platform, boolean right) {
        double gap = 3;
        double width = Math.max(1, (parent.getWidth() - gap) * .5);
        button.setBounds(width, parent.getHeight());
        button.setPosition(right ? width + gap : 0, 0);
    }

    private final class SourceButton extends Panel {
        private final MusicPlatform platform;
        private final RoundedRectWidget background = new RoundedRectWidget();
        private final AbstractWidget<?> platformIcon;
        private final LabelWidget label;

        private SourceButton(MusicPlatform platform) {
            this.platform = platform;
            this.setShouldOverrideMouseCursor(true);

            this.background.setClickable(false);
            this.addChild(this.background);
            this.background.setBeforeRenderCallback(() -> {
                boolean selected = NeteaseMusicService.getCurrentPlatform() == this.platform;
                this.background.setBounds(1, 1, Math.max(1, this.getWidth() - 2), Math.max(1, this.getHeight() - 2));
                this.background.setRadius(4.5);
                this.background.setColor(selected
                        ? this.platform.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                this.background.setAlpha(selected ? .96f : (this.isHovering() ? .45f : 0f));
            });

            // QQ 音乐使用其自有的服务商品牌字形。请勿复用 QQ 登录图标。
            // 有矢量标识的那三家（BrandVectorIcons.has）就用矢量，和圆盘、引导页是同一份数据；
            // 其余各家仍走各自的品牌字体字形。
            this.platformIcon = BrandVectorIcons.has(this.platform)
                    ? new BrandIconWidget(this.platform, SOURCE_ICON_BOX)
                    : new LabelWidget(sourceGlyph(this.platform), sourceFont(this.platform));
            this.platformIcon.setClickable(false);
            this.addChild(this.platformIcon);
            this.label = new LabelWidget(
                    () -> NavigateBar.this.getWidth() < 112 ? "" : this.platform.getDisplayName(),
                    FontManager.pf12bold);
            this.label.setClickable(false);
            this.addChild(this.label);
            this.platformIcon.setBeforeRenderCallback(() -> {
                boolean compact = NavigateBar.this.getWidth() < 112;
                double groupWidth = this.platformIcon.getWidth() + (compact ? 0 : 4 + this.label.getWidth());
                this.platformIcon.setColor(NeteaseMusicService.getCurrentPlatform() == this.platform
                        ? 0xFFFFFF : this.platform.getBrandColor());
                this.platformIcon.setPosition(this.getWidth() * .5 - groupWidth * .5,
                        this.getHeight() * .5 - this.platformIcon.getHeight() * .5 + 1);
            });
            this.label.setBeforeRenderCallback(() -> {
                this.label.setColor(NeteaseMusicService.getCurrentPlatform() == this.platform
                        ? 0xFFFFFF
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                this.label.setPosition(this.platformIcon.getRelativeX() + this.platformIcon.getWidth() + 4,
                        this.getHeight() * .5 - this.label.getHeight() * .5 + 1);
            });
        }
    }

    private enum SidebarActionIcon {
        THEME,
        FONT,
        SCALE,
        REFRESH,
        ANNOUNCEMENT
    }

    /** 快捷操作条上那枚未读小圆点的颜色。 */
    private static final int UNREAD_DOT_COLOR = 0xFFE05B66;

    /**
     * 紧凑的矢量图标按钮，在任意播放器缩放比例下都保持清晰可读。
     */
    private static final class SidebarActionButton extends RoundedRectWidget {
        private final SidebarActionIcon icon;
        private BooleanSupplier badge;
        private float hoverAnimation;
        private float pressAnimation;
        private volatile boolean spinning;
        private long lastClickAt;

        /**
         * lucide 的 24 网格在这颗按钮里占的边长。
         *
         * <p>定成 16 是为了和换掉之前的手绘几何同大：lucide 的字形在网格里自带约 2 个单位的内边距，
         * 实际墨迹约 13.3，正对着原来那圈 ±6.5 单位的作图范围，也就刚好落进 14.8 的那块内底板。</p>
         */
        private static final double LUCIDE_BOX = 16.0;

        private SidebarActionButton(SidebarActionIcon icon) {
            this.icon = icon;
            this.setShouldOverrideMouseCursor(true);
        }

        private void setSpinning(boolean spinning) {
            this.spinning = spinning;
        }

        /** 给这颗按钮挂一枚未读小圆点。判断每帧问一次，读完的那一刻就消失。 */
        private void setUnreadBadge(BooleanSupplier badge) {
            this.badge = badge;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            hoverAnimation = Interpolations.interpolate(hoverAnimation, isHovering() ? 1f : 0f, .22f);
            boolean recentlyPressed = System.currentTimeMillis() - lastClickAt < 170L;
            pressAnimation = Interpolations.interpolate(pressAnimation, recentlyPressed ? 1f : 0f, .30f);

            int base = NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND);
            int hover = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            setColor(mixColor(base, hover, .18f + hoverAnimation * .58f));
            super.onRender(mouseX, mouseY);

            double centerX = getX() + getWidth() * .5;
            double centerY = getY() + getHeight() * .5;
            double iconScale = 1.13 + hoverAnimation * .08 - pressAnimation * .055;
            float alpha = getAlpha() * (.92f + hoverAnimation * .08f);
            // 此处保持源颜色不透明。旧实现预先应用了 alpha
            // 然后又在每个图元内部应用了一次，导致字形在
            // 若干主题下明显淡出、融入深色操作栏。
            int accent = getIconAccentColor();
            int foreground = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);

            // 现在每个操作都带有持久的对比色图标底板和彩色的底部
            // 标记。这保留了紧凑的纯图标布局，同时使三个
            // 控件，在指针悬停之前即可立即辨认。
            roundedRect(centerX - 8.6, centerY - 8.6, 17.2, 17.2, 8.6,
                    RenderSystem.reAlpha(accent, alpha * (.16f + hoverAnimation * .16f)));
            roundedRect(centerX - 7.4, centerY - 7.4, 14.8, 14.8, 7.4,
                    RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND),
                            alpha * (.38f - hoverAnimation * .12f)));
            roundedRect(getX() + 4.0, getY() + getHeight() - 2.4,
                    Math.max(1.0, getWidth() - 8.0), 1.35, .675,
                    RenderSystem.reAlpha(accent, alpha * (.58f + hoverAnimation * .38f)));

            api.getGLStateManager().pushMatrix();
            scaleAtPos(centerX, centerY, iconScale);
            if (!drawLucideIcon(centerX, centerY, alpha, accent, foreground)) {
                renderHandDrawnIcon(centerX, centerY, alpha, accent, foreground);
            }
            api.getGLStateManager().popMatrix();

            if (badge != null && badge.getAsBoolean()) {
                // 未读小圆点压在图标底板上，所以先垫一圈底色把它从邻居图形里分离出来。
                double size = 5.4;
                double dotX = getX() + getWidth() - size - .5;
                double dotY = getY() - .5;
                roundedRect(dotX - .9, dotY - .9, size + 1.8, size + 1.8, (size + 1.8) * .5,
                        RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND),
                                Math.min(1f, alpha + .4f)));
                roundedRect(dotX, dotY, size, size, size * .5, RenderSystem.reAlpha(UNREAD_DOT_COLOR, alpha));
            }
        }

        /**
         * 走 {@link FoliaLucideIcons} 那套矢量管线：图标几何是 lucide 的原始路径数据，
         * 不再是这里手写的圆角矩形拼出来的。
         *
         * <p>主题用强调色、其余几颗用正文色。全染成强调色的话，按钮底下那条强调色底杠就白画了
         * —— 它靠的正是「图标与底杠不同色」才分得出来。</p>
         *
         * @return 真的画了才返回 true。不在 Skija 帧内、路径解析失败、或这颗按钮没有对应的
         *         lucide 字形都返回 false，由调用方退回 {@link #renderHandDrawnIcon}：
         *         界面不能因为一枚图标画不出来而空一块。
         */
        private boolean drawLucideIcon(double centerX, double centerY, float alpha,
                                       int accent, int foreground) {
            String name;
            int ink;
            if (icon == SidebarActionIcon.THEME) {
                name = FoliaLucideIcons.PALETTE;
                ink = accent;
            } else if (icon == SidebarActionIcon.FONT) {
                name = FoliaLucideIcons.TYPOGRAPHY;
                ink = foreground;
            } else if (icon == SidebarActionIcon.SCALE) {
                name = FoliaLucideIcons.SCALING;
                ink = foreground;
            } else if (icon == SidebarActionIcon.REFRESH) {
                name = FoliaLucideIcons.REFRESH;
                ink = spinning ? accent : foreground;
                if (spinning) {
                    // 只有转起来才转：绕图标中心自转，转速沿用原来那版环形图标的每 6 毫秒一度。
                    rotateAtPos(centerX, centerY,
                            (float) ((System.currentTimeMillis() / 6.0) % 360.0));
                }
            } else {
                return false;
            }
            return FoliaLucideIcons.draw(name, centerX, centerY, LUCIDE_BOX,
                    RenderSystem.reAlpha(ink, alpha));
        }

        /** 手绘兜底：矢量管线画不出来时退回换掉之前的那套几何。 */
        private void renderHandDrawnIcon(double centerX, double centerY, float alpha,
                                         int accent, int foreground) {
            if (icon == SidebarActionIcon.THEME) {
                renderThemeIcon(centerX, centerY, alpha, accent, foreground);
            } else if (icon == SidebarActionIcon.FONT) {
                renderFontIcon(centerX, centerY, alpha, accent, foreground);
            } else if (icon == SidebarActionIcon.SCALE) {
                renderScaleIcon(centerX, centerY, alpha, accent, foreground);
            } else if (icon == SidebarActionIcon.ANNOUNCEMENT) {
                renderAnnouncementIcon(centerX, centerY, alpha, accent, foreground);
            } else {
                renderRefreshIcon(centerX, centerY, alpha, accent, foreground);
            }
        }

        @Override
        public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
            lastClickAt = System.currentTimeMillis();
            return super.onMouseClicked(relativeX, relativeY, mouseButton);
        }

        private void renderThemeIcon(double x, double y, float alpha, int accent, int foreground) {
            // 四色调色板标记：更大的圆点和明亮的中心依然可读
            // 在深色与液态玻璃两种主题下均不依赖字体字形即可辨认。
            roundedRect(x - 2.15, y - 6.25, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(accent, alpha));
            roundedRect(x - 6.25, y - 2.15, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFF73B9FF, alpha));
            roundedRect(x + 1.95, y - 2.15, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFFFF86B8, alpha));
            roundedRect(x - 2.15, y + 1.95, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFF75E0B1, alpha));
            roundedRect(x - 1.05, y - 1.05, 2.1, 2.1, 1.05,
                    RenderSystem.reAlpha(foreground, alpha * .92f));
        }

        /**
         * 一枚带下划线的「A」，和字体选择浮层里的 {@code PartyIcons.TEXT_FORMAT} 是同一个符号。
         *
         * <p>画出来而不是用图标字体：同一条快捷操作条上另外三颗都是矢量图形，混一枚字形进来，
         * 描边粗细与光学重心都对不上邻居；而这条按钮会跟着播放器缩放走，矢量在任何倍率下都锐。</p>
         */
        private void renderFontIcon(double x, double y, float alpha, int accent, int foreground) {
            int ink = RenderSystem.reAlpha(foreground, alpha);
            // 两笔斜杠在字顶交汇：pill 两端是圆头，交汇处自然圆滑，不用另画一个尖。
            drawRotatedPill(x - 2.35, y - 0.55, 10.9, 1.75, -64.5f, ink);
            drawRotatedPill(x + 2.35, y - 0.55, 10.9, 1.75, 64.5f, ink);
            // 横画略窄于两笔的间距，免得端点戳出斜杠外侧。
            roundedRect(x - 2.45, y + 1.05, 4.9, 1.6, .8, ink);
            // 下划线用强调色：这条线是「文字」这个概念的落点，也让这颗按钮在条上认得出来。
            roundedRect(x - 6.3, y + 5.15, 12.6, 1.7, .85,
                    RenderSystem.reAlpha(accent, alpha * .95f));
        }

        private void renderRefreshIcon(double x, double y, float alpha, int accent, int foreground) {
            long now = System.currentTimeMillis();
            double rotation = spinning ? (now / 6.0) % 360.0 : -28.0;
            final int segments = 8;
            for (int segment = 0; segment < segments; segment++) {
                // 在圆环上留出两个缺口，使刷新轮廓清晰可辨。
                if (segment == 2 || segment == 6) continue;
                float segmentAlpha = alpha * (spinning
                        ? (.32f + .68f * (1f - segment / 8f))
                        : .86f);
                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(x, y, 0);
                api.getGLStateManager().rotate((float) (rotation + segment * 45.0), 0, 0, 1);
                roundedRect(-.85, -6.55, 1.7, 3.25, .85,
                        RenderSystem.reAlpha(spinning ? accent : foreground, segmentAlpha));
                api.getGLStateManager().popMatrix();
            }
            // 小箭头尖闭合这两个缺口，将圆环变成清晰的
            // 刷新符号，而非普通的加载转圈。
            drawRotatedPill(x - 4.25, y - 4.05, 4.45, 1.65, -42f,
                    RenderSystem.reAlpha(spinning ? accent : foreground, alpha));
            drawRotatedPill(x + 4.25, y + 4.05, 4.45, 1.65, 138f,
                    RenderSystem.reAlpha(spinning ? accent : foreground, alpha));
        }

        private void renderScaleIcon(double x, double y, float alpha, int accent, int foreground) {
            double left = x - 6.15;
            double top = y - 6.15;
            double right = x + 6.15;
            double bottom = y + 6.15;
            double thickness = 1.65;
            double arm = 4.7;
            int corner = RenderSystem.reAlpha(foreground, alpha);
            int highlight = RenderSystem.reAlpha(accent, alpha * .94f);

            roundedRect(left, top, arm, thickness, thickness * .5, highlight);
            roundedRect(left, top, thickness, arm, thickness * .5, highlight);
            roundedRect(right - arm, top, arm, thickness, thickness * .5, corner);
            roundedRect(right - thickness, top, thickness, arm, thickness * .5, corner);
            roundedRect(left, bottom - thickness, arm, thickness, thickness * .5, corner);
            roundedRect(left, bottom - arm, thickness, arm, thickness * .5, corner);
            roundedRect(right - arm, bottom - thickness, arm, thickness, thickness * .5, highlight);
            roundedRect(right - thickness, bottom - arm, thickness, arm, thickness * .5, highlight);
            roundedRect(x - 1.15, y - 1.15, 2.3, 2.3, 1.15,
                    RenderSystem.reAlpha(foreground, alpha * .92f));
        }

        /**
         * 一张贴着三条文字的公告纸。
         *
         * <p>画矢量而不是用 {@code PartyIcons.MAIL_UNREAD}：这条快捷操作条上另外四颗全是矢量图形，
         * 混一枚字形进来，描边粗细与光学重心都对不上邻居；而这条按钮会跟着播放器缩放走，
         * 矢量在任何倍率下都锐。</p>
         */
        private void renderAnnouncementIcon(double x, double y, float alpha, int accent, int foreground) {
            double left = x - 6.1;
            double top = y - 6.7;
            double width = 11.2;
            double height = 13.4;
            roundedRect(left, top, width, height, 2.3, RenderSystem.reAlpha(accent, alpha * .30f));
            // 三条文字线，最后一条短一截：读起来是「这里写着一段话」，而不是一块表格。
            int ink = RenderSystem.reAlpha(foreground, alpha * .92f);
            roundedRect(left + 2.0, top + 2.7, width - 4.0, 1.5, .75, ink);
            roundedRect(left + 2.0, top + 5.6, width - 4.0, 1.5, .75, ink);
            roundedRect(left + 2.0, top + 8.5, width - 6.6, 1.5, .75,
                    RenderSystem.reAlpha(foreground, alpha * .74f));
        }

        private int getIconAccentColor() {
            if (icon == SidebarActionIcon.FONT) return 0xFFB79BFF;
            if (icon == SidebarActionIcon.ANNOUNCEMENT) return 0xFFFFC061;
            if (icon == SidebarActionIcon.SCALE) return 0xFF67D8FF;
            if (icon == SidebarActionIcon.REFRESH) return 0xFF59E3A5;
            return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
        }

        private void drawRotatedPill(double centerX, double centerY, double width, double height,
                                     float rotation, int color) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate(rotation, 0, 0, 1);
            roundedRect(-width * .5, -height * .5, width, height, height * .5, color);
            api.getGLStateManager().popMatrix();
        }

        private int mixColor(int first, int second, float amount) {
            float t = Math.max(0f, Math.min(1f, amount));
            int a = (int) (((first >>> 24) & 255) * (1f - t) + ((second >>> 24) & 255) * t);
            int r = (int) (((first >>> 16) & 255) * (1f - t) + ((second >>> 16) & 255) * t);
            int g = (int) (((first >>> 8) & 255) * (1f - t) + ((second >>> 8) & 255) * t);
            int b = (int) ((first & 255) * (1f - t) + (second & 255) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    /**
     * 把高亮条摆到当前选中的条目上。
     *
     * <p>上下两条边读<b>同一条</b>真毫秒时钟、但不同的曲线：前缘走 {@code SPRING_LAND}
     * （30% 时长走完 90% 行程，在 60% 时长处越过目标 9.7% 再收回来），后缘走
     * {@code MOTION_LAGGING}（同一时刻才走到 37%）。于是条子先被拽长、前缘弹过一点、
     * 后缘跟上，最后精确落在行高上 —— 是「弹过去钉住」，不是旧写法那种「漂过去停下」。</p>
     *
     * <p>旧写法是 {@code Interpolations.interpolate} 的帧耦合指数，既没有时长也没有终点：
     * 尾巴无限逼近，最后几帧还在动；而且默认动画速度 3.0 会把前后缘的系数同时顶到 .95 的
     * 钳位上限，本该 1 : 0.52 的拉伸比被压成 1 : 0.82 —— 形变几乎看不见，只剩瞬移。
     * 连点两行时两段指数还会叠在一起，先窜后糊。</p>
     *
     * <p>高亮条不是列表的子组件（列表会把所有可见子组件重新排到垂直流里），所以它拿不到列表的裁剪，
     * 这里手动把它和列表视口求交：选中项滚出可见区域时高亮条跟着被裁掉，而不是画到列表外面去。</p>
     */
    private void layoutSelectionIndicator() {
        final long now = System.nanoTime();
        // 单帧最多补 40ms：与换页那条同一档。掉帧时整段变慢，而不是一帧跨过小半条曲线。
        final double frameMs = indicatorLastNanos == 0L ? 0.0
                : Math.min(INDICATOR_MAX_FRAME_MS, (now - indicatorLastNanos) / 1_000_000.0);
        indicatorLastNanos = now;

        PlaylistItem selected = findSelectedItem();
        double panelTop = this.playlistPanel.getRelativeY();
        double panelHeight = this.playlistPanel.getHeight();
        double panelBottom = panelTop + panelHeight;

        // 列表与条目要等到各自的 beforeRenderCallback 跑过一次才有真实宽高。在那之前不要落位，
        // 否则高亮条会把一组全 0 的坐标当成起点，之后再从侧栏左上角滑进来。
        boolean geometryReady = panelHeight > .5 && selected != null && selected.getWidth() > .5;

        if (geometryReady) {
            double targetTop = panelTop + selected.getRelativeY();
            double targetBottom = targetTop + selected.getHeight();
            double targetLeft = this.playlistPanel.getRelativeX() + selected.getRelativeX();
            double targetWidth = selected.getWidth();

            if (!indicatorPlaced) {
                indicatorTop = targetTop;
                indicatorBottom = targetBottom;
                indicatorLeft = targetLeft;
                indicatorWidth = targetWidth;
                indicatorPlaced = true;
                indicatorOwner = selected;
            } else if (selected != indicatorOwner) {
                // 换行才起新趟，并且以条子<b>此刻</b>的位置为起点：中途再点第三行时从半路上
                // 另起一趟，而不是跳回上一行重走一遍。
                indicatorOwner = selected;
                residualTop = indicatorTop - targetTop;
                residualBottom = indicatorBottom - targetBottom;
                residualLeft = indicatorLeft - targetLeft;
                residualWidth = indicatorWidth - targetWidth;
                indicatorLeadBottom = residualTop < 0;   // 目标在下方 → 下边沿是前缘
                indicatorLegT = 0.0;
            }
            // 同一行被滚动带着走时这里什么都不做：残差是相对目标算的，目标一动条子就跟着动。

            if (indicatorLegT < 1.0) {
                indicatorLegT = Math.min(1.0, indicatorLegT + frameMs / indicatorLegMillis());
            }
            final double lead = TransitionCurveCache.SPRING_LAND.at(indicatorLegT);
            final double trail = TransitionCurveCache.MOTION_LAGGING.at(indicatorLegT);
            indicatorTop = targetTop + capped(residualTop, indicatorLeadBottom ? trail : lead);
            indicatorBottom = targetBottom + capped(residualBottom, indicatorLeadBottom ? lead : trail);
            // 横向不过冲：一条左对齐的条子往左右各弹一下，读起来像错位而不是弹性。
            indicatorLeft = targetLeft + capped(residualLeft, trail);
            indicatorWidth = targetWidth + capped(residualWidth, trail);
        }

        indicatorPresence = ramp(indicatorPresence, geometryReady ? 1.0 : 0.0,
                frameMs, INDICATOR_PRESENCE_MS / SheetClock.speed());

        double top = Math.max(panelTop, indicatorTop);
        double bottom = Math.min(panelBottom, indicatorBottom);
        boolean visible = indicatorPlaced && indicatorPresence > .01
                && bottom - top > .5 && indicatorWidth > .5;
        // 隐藏是安全的：这个方法由侧栏的回调驱动，下一帧照样会跑到，可以再把它显示回来。
        this.selectionIndicator.setHidden(!visible);
        if (!visible) {
            return;
        }

        this.selectionIndicator
                .setBounds(indicatorWidth, bottom - top)
                .setPosition(indicatorLeft, top)
                .setRadius(4)
                // 选中项用主题强调色，而不是和悬停一样的中性灰，否则"选中"和"鼠标经过"看起来一样。
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setAlpha(this.getAlpha() * .22f * (float) indicatorPresence);
    }

    /**
     * 残差在这一帧的位移量：{@code residual * (1 - 缓动)}，并把过冲那段夹住。
     *
     * <p>缓动大于 1 才是过冲段，此时乘出来与残差反号，意思是条子越过了目标。越过幅度按像素
     * 封顶，不然跨四行跳转时 9.7% 的过冲就是十几像素，看着像没停住而不是有弹性。</p>
     */
    private static double capped(final double residual, final double ease) {
        double value = residual * (1.0 - ease);
        if (ease > 1.0 && Math.abs(value) > INDICATOR_OVERSHOOT_CAP) {
            return Math.copySign(INDICATOR_OVERSHOOT_CAP, value);
        }
        return value;
    }

    /** 高亮条这一趟真正走多少毫秒。默认动画速度档（3.0，也就是最快那一档）下约 300ms。 */
    private static double indicatorLegMillis() {
        return Math.max(1.0, INDICATOR_MS / SheetClock.speed());
    }

    /** 线性爬升到目标值，走真实毫秒。与 {@code SegmentControl#ramp} 同一口径。 */
    private static double ramp(final double value, final double target, final double deltaMs,
                               final double durationMs) {
        if (durationMs <= 0.0) return target;
        double step = deltaMs / durationMs;
        return target > value ? Math.min(target, value + step) : Math.max(target, value - step);
    }

    /**
     * 每帧驱动搜索联想：取词 → 防抖后异步请求 → 从缓存读回结果 → 摆放面板与行。
     *
     * <p>整个方法只做布局与状态推进，<b>不阻塞</b>：请求由
     * {@link NeteaseSearchSuggestService#prefetch(String)} 异步发出，读取走
     * {@link NeteaseSearchSuggestService#peek(String)} 的纯缓存查询。</p>
     *
     * <p>联想只对<b>官方网易云</b>生效：上游只有网易云有这个端点，QQ / 酷狗 / GD 聚合源都没有对应接口，
     * 所以在那些来源下这个面板永远不出现，它们的搜索行为一字未改。</p>
     */
    private void layoutSuggestions() {
        String text = this.searchField.getText() == null ? "" : this.searchField.getText().trim();
        if (!text.equals(this.suggestQuery)) {
            this.suggestQuery = text;
            this.suggestQueryChangedAt = System.currentTimeMillis();
            this.suggestHighlight = -1;
            // 换了词就不再沿用上一次的"已收起"，否则改完词面板弹不出来。
            if (this.suggestDismissedFor != null && !this.suggestDismissedFor.equals(text)) {
                this.suggestDismissedFor = null;
            }
        }

        // 联想词此前只对网易云开放，酷狗的输入框因此永远弹不出面板。现在两个源都走
        // NeteaseSearchSuggestService，由它按当前平台选端点；这里只判断"当前源有没有联想"。
        MusicPlatform suggestPlatform = NeteaseMusicService.getCurrentPlatform();
        boolean suggestSource = suggestPlatform == MusicPlatform.NETEASE
                || suggestPlatform == MusicPlatform.KUGOU;
        boolean eligible = suggestSource && !text.isEmpty() && this.searchField.isFocused()
                && !text.equals(this.suggestDismissedFor);

        if (eligible && System.currentTimeMillis() - this.suggestQueryChangedAt >= SUGGEST_DEBOUNCE_MILLIS) {
            NeteaseSearchSuggestService.prefetch(text);
        }
        this.suggestions = eligible
                ? NeteaseSearchSuggestService.peek(text)
                : Collections.<NeteaseSearchSuggestService.Suggestion>emptyList();

        int count = Math.min(this.suggestions.size(), this.suggestionRows.size());
        if (this.suggestHighlight >= count) this.suggestHighlight = -1;

        boolean visible = eligible && count > 0;
        this.suggestionsVisible = visible;
        if (!visible) {
            // 立刻隐藏而不是淡出：淡出期间面板仍然会抢走点击，那比少一个退场动画糟糕得多。
            this.suggestReveal = 0f;
            this.suggestionPanel.setHidden(true);
            this.suggestionDismissLayer.setHidden(true);
            for (SuggestionRow row : this.suggestionRows) row.setHidden(true);
            return;
        }

        this.suggestReveal = Interpolations.interpolate(this.suggestReveal, 1f, .4f);
        // 收起层铺满搜索栏以下的整条侧栏。它没有任何绘制（Panel 是隐形容器），只负责接一次点击。
        this.suggestionDismissLayer.setHidden(false);
        this.suggestionDismissLayer.setBounds(Math.max(1.0, this.getWidth()),
                Math.max(1.0, this.getHeight() - this.searchBarBottom));
        this.suggestionDismissLayer.setPosition(0, this.searchBarBottom);

        // 面板要比它盖住的歌单条目宽：条目自己占 x=4 到 width-4（见 PlaylistItem 的布局），
        // 而 hovering 是各组件按自己的矩形独立算的、兄弟之间不互相压制。面板要是只有 x=8 到 width-8，
        // 鼠标停在面板上时下面那一行照样算自己被悬停，强调色高亮就会从面板两侧各露出 4px。
        // 取 x=3 / width-6 刚好把条目整个盖住，还多出 1px 余量。
        double width = Math.max(1.0, this.getWidth() - SUGGEST_INSET * 2.0);
        double height = count * SUGGEST_ROW_HEIGHT + SUGGEST_PADDING * 2.0;
        this.suggestionPanel.setHidden(false);
        this.suggestionPanel.setBounds(width, height);
        // 轻微下滑入场：只动位置不动高度，因为 Panel 不裁剪子组件，压高度会让行画到面板外面。
        this.suggestionPanel.setPosition(SUGGEST_INSET,
                this.searchBarBottom + 3.0 - 4.0 * (1f - this.suggestReveal));

        for (int index = 0; index < this.suggestionRows.size(); index++) {
            SuggestionRow row = this.suggestionRows.get(index);
            if (index >= count) {
                row.setHidden(true);
                continue;
            }
            row.setHidden(false);
            row.setBounds(Math.max(1.0, width - SUGGEST_PADDING * 2.0), SUGGEST_ROW_HEIGHT - 1.0);
            row.setPosition(SUGGEST_PADDING, SUGGEST_PADDING + index * SUGGEST_ROW_HEIGHT);
        }
    }

    /**
     * 联想面板范围内的点击由它自己处理，并且不再往下传。
     *
     * <p>为什么不能只靠组件树的短路（{@code iterateChildrenMouseClick} 一命中就 return）：
     * {@link NCMScreen#mouseClicked} 会把同一次点击「依次」交给 basePanel、currentPanel、
     * controlsBar，中间没有早退；而 {@code onMouseClickReceived} 返回的是 void，上层根本拿不到
     * 「这一下已经被吃掉了」这个信息。项目里音质弹窗（{@code ControlsBar#consumeQualityMenuClick}）
     * 与队列抽屉（{@code PlayQueuePanel#consumeClick}）就是为同一个问题加的前置消费口，这里照办。</p>
     *
     * <p>命中判定走「面板与行自己的矩形」，不看 {@code isHovering()}：悬停状态是渲染时算出来的，
     * 依赖它就等于依赖「这一帧确实渲染过、而且没有被别的组件抢走悬停」，而这个前置口是在渲染之外
     * 被调用的。</p>
     *
     * @return 是否吃掉了这次点击
     */
    public boolean consumeSuggestionClick(double mouseX, double mouseY, int mouseButton) {
        if (!this.suggestionsVisible || this.suggestionPanel.isHidden()) return false;

        if (this.isHovered(mouseX, mouseY, this.suggestionPanel.getX(), this.suggestionPanel.getY(),
                this.suggestionPanel.getWidth(), this.suggestionPanel.getHeight())) {
            if (mouseButton == 0) {
                int index = this.suggestionRowAt(mouseX, mouseY);
                if (index >= 0) this.selectSuggestion(index);
            }
            // 落在面板留白（上下各 3 px）上的点击也要吃掉：它盖住的仍然是歌单条目。
            return true;
        }

        // 收起层的范围是搜索栏以下的整条侧栏。这里的点击意图是「关掉面板」，
        // 不能连带触发被它盖住的歌单条目或音乐来源卡片。
        if (!this.suggestionDismissLayer.isHidden() && this.isHovered(mouseX, mouseY,
                this.suggestionDismissLayer.getX(), this.suggestionDismissLayer.getY(),
                this.suggestionDismissLayer.getWidth(), this.suggestionDismissLayer.getHeight())) {
            this.dismissSuggestions();
            return true;
        }

        // 侧栏之外（右侧列表、底部控制条）没有任何视觉遮挡，那里的点击照常生效：
        // 输入框失焦后面板下一帧自己就会收起，不在这里额外记「这个词不再提示」。
        return false;
    }

    /** 命中的联想行下标；没命中返回负一。 */
    private int suggestionRowAt(double mouseX, double mouseY) {
        for (int index = 0; index < this.suggestionRows.size(); index++) {
            SuggestionRow row = this.suggestionRows.get(index);
            if (row.isHidden()) continue;
            if (this.isHovered(mouseX, mouseY, row.getX(), row.getY(),
                    row.getWidth(), row.getHeight())) {
                return index;
            }
        }
        return -1;
    }

    /** 选中第 {@code index} 条联想词：填进搜索框，然后走与回车完全同一条搜索路径。 */
    private void selectSuggestion(int index) {
        List<NeteaseSearchSuggestService.Suggestion> current = this.suggestions;
        if (current == null || index < 0 || index >= current.size()) return;
        NeteaseSearchSuggestService.Suggestion suggestion = current.get(index);
        String keyword = suggestion == null || suggestion.text == null ? "" : suggestion.text.trim();
        if (keyword.isEmpty()) return;
        this.searchField.setText(keyword);
        this.searchField.getTextField().setCursorPositionEnd();
        this.submitSearch(keyword);
    }

    /** 上/下键移动高亮。返回是否消费了这次按键（面板没开时不消费，方向键仍归输入框）。 */
    private boolean moveSuggestionHighlight(int delta) {
        if (!this.suggestionsVisible) return false;
        int count = Math.min(this.suggestions.size(), this.suggestionRows.size());
        if (count <= 0) return false;
        if (this.suggestHighlight < 0) {
            this.suggestHighlight = delta > 0 ? 0 : count - 1;
        } else {
            // 循环而不是在两端停住：只有八条，绕一圈比试探边界快。
            this.suggestHighlight = ((this.suggestHighlight + delta) % count + count) % count;
        }
        return true;
    }

    /** 当前高亮的联想词；没有高亮或面板没开时返回空串。 */
    private String highlightedSuggestionText() {
        if (!this.suggestionsVisible || this.suggestHighlight < 0
                || this.suggestHighlight >= this.suggestions.size()) {
            return "";
        }
        String text = this.suggestions.get(this.suggestHighlight).text;
        return text == null ? "" : text.trim();
    }

    /** 收起联想面板，并记住"这个词已经不想看建议了"。 */
    private void dismissSuggestions() {
        this.suggestDismissedFor = this.suggestQuery;
        this.suggestHighlight = -1;
        this.suggestionsVisible = false;
        this.suggestReveal = 0f;
        this.suggestionPanel.setHidden(true);
        this.suggestionDismissLayer.setHidden(true);
        for (SuggestionRow row : this.suggestionRows) row.setHidden(true);
    }

    /**
     * 执行一次搜索。
     *
     * <p>从原先内联在回车处理里的那段代码原样提出来，好让联想行点击走<b>完全同一条</b>路径 ——
     * 官方源的搜索行为一字未改：网易云的歌单模式仍然走 {@code cloudSearch} 的 1000 类型，
     * 歌曲模式仍然建一个 {@code searchMode + searchResultList} 的临时列表。</p>
     */
    private void submitSearch(final String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String query = keyword.trim();
        this.dismissSuggestions();
        this.searchField.setFocused(false);

        this.playlistPanel.getChildren().forEach(child -> {
            if (child instanceof PlaylistItem) {
                ((PlaylistItem) child).setSelected(false);
            }
        });

        MusicPlatform searchPlatform = NeteaseMusicService.getCurrentPlatform();
        final boolean bilibiliSearch = searchPlatform == MusicPlatform.BILIBILI;
        final boolean searchPlaylists = playlistSearchMode
                && (searchPlatform == MusicPlatform.NETEASE || searchPlatform == MusicPlatform.KUGOU
                || searchPlatform == MusicPlatform.QISHUI || bilibiliSearch);
        if (searchPlaylists) {
            final boolean kugouSearch = searchPlatform == MusicPlatform.KUGOU;
            final boolean qishuiSearch = searchPlatform == MusicPlatform.QISHUI;
            MultiThreadingUtil.runAsync(() -> {
                List<PlayList> results = bilibiliSearch
                        // B 站没有歌单库这个对象：搜的是稿件，再把其中带合集或分 P 数 >1 的筛成歌单。
                        ? BilibiliPlaylistService.searchPlaylists(query, 30)
                        : qishuiSearch
                        ? QishuiMusicService.searchPlaylists(query, 50)
                        : kugouSearch ? KuGouMusicService.searchPlaylists(query, 50)
                        : searchNeteasePlaylists(query);
                String title = (bilibiliSearch ? "B 站合集 / 分P搜索"
                        : qishuiSearch ? "汽水歌单搜索" : kugouSearch ? "酷狗歌单搜索" : "歌单搜索")
                        + " · " + query + " · " + results.size();
                MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().setCurrentPanel(
                        new HomePanel(results, title)));
            });
        } else {
            // 保留原有的歌曲搜索流程及其独立的临时播放列表。
            PlayList playList = JsonUtils.parse("{}", PlayList.class);
            playList.setSearchMode(true);
            // 只有搜索框产生的列表才走"起播后接最近播放"，发现页复用 searchMode 的那些列表不受影响。
            playList.setSearchResultList(true);
            playList.musics = new CopyOnWriteArrayList<>();
            PlaylistPanel panel = new PlaylistPanel(playList);
            NCMScreen.getInstance().setCurrentPanel(panel);
            MultiThreadingUtil.runAsync(() -> {
                List<Music> search = CloudMusic.search(query);
                playList.musics.addAll(search);
                panel.onInit();
            });
        }
    }

    private NCMPanel loadQishuiCollectionPanel() {
        List<Music> tracks = QishuiMusicService.collectionTracks(500);
        if (tracks.isEmpty()) return null;
        PlayList collection = new PlayList(-910_000_001L, "我收藏的歌曲", "", tracks.size(),
                0L, null, "汽水音乐收藏", 0L);
        collection.setPlatform(MusicPlatform.QISHUI);
        collection.setPlatformPlaylistId("collection");
        collection.setSearchMode(true);
        collection.setSearchResultList(true);
        collection.setMusics(new CopyOnWriteArrayList<>(tracks));
        collection.setMusicsQueried(true);
        collection.setMusicsLoaded(true);
        return new PlaylistPanel(collection, false);
    }

    private NCMPanel loadQishuiRadioPanel() {
        List<PlayList> radios = QishuiMusicService.recommendRadios(80);
        if (radios.isEmpty()) return null;
        return new HomePanel(radios, "汽水电台 · " + radios.size());
    }

    /** 移除一条本地导入的歌单引用（右键触发）；不影响上游，也不涉及账号。 */
    private void removeImportedPlaylist(PlayList playlist) {
        ImportedPlaylistStore.remove(playlist);
        NCMScreen.getInstance().markDirty();
        DownloadDynamicIsland.showModelOperationResult("导入歌单",
                "已移除「" + (playlist.getName() == null || playlist.getName().isEmpty()
                        ? playlist.getPlatformPlaylistId() : playlist.getName()) + "」", true);
    }

    /** 列表里当前被选中的条目，没有选中项时返回 {@code null}。 */
    private PlaylistItem findSelectedItem() {
        for (AbstractWidget<?> child : this.playlistPanel.getChildren()) {
            if (child instanceof PlaylistItem && ((PlaylistItem) child).isSelected()) {
                return (PlaylistItem) child;
            }
        }
        return null;
    }

    /**
     * 重新选中代表当前可见内容面板的侧边栏项。
     */
    public void selectCurrentPanel(NCMPanel panel) {
        PlaylistItem selectedItem = null;
        if (panel instanceof HomePanel) {
            selectedItem = this.homeItem;
        } else if (panel instanceof PlaylistPanel) {
            PlayList activePlaylist = ((PlaylistPanel) panel).playList;
            if (activePlaylist != null && !activePlaylist.isSearchMode()) {
                for (PlaylistItem item : this.playlistItems) {
                    if (activePlaylist.equals(item.getPlaylist())) {
                        selectedItem = item;
                        break;
                    }
                }
            }
        }

        if (this.homeItem != null) {
            this.homeItem.setSelected(this.homeItem == selectedItem);
        }
        for (PlaylistItem item : this.playlistItems) {
            item.setSelected(item == selectedItem);
        }
    }

    @Override
    public void onInit() {

    }

    /**
     * 一条搜索联想行。
     *
     * <p>行是固定几个、复用的，所以文案必须走 supplier 按下标去读当前的
     * {@link NavigateBar#suggestions}，而不是构造时把字符串塞进去。下标越界要返回空串 ——
     * 行的显隐由 {@link NavigateBar#layoutSuggestions()} 控制，但结果列表可能在两次布局之间被换掉。</p>
     */
    private final class SuggestionRow extends Panel {

        private final int index;

        private SuggestionRow(int index) {
            this.index = index;
            this.setShouldOverrideMouseCursor(true);

            // 高亮必须是一个独立的子组件，不能靠给整行设 alpha 来开关：
            // AbstractWidget#getAlpha 会把 alpha 逐层乘下去，行的 alpha 归零会把行里的文字一起抹掉。
            RoundedRectWidget highlight = new RoundedRectWidget();
            this.addChild(highlight);
            highlight.setClickable(false);
            highlight.setBeforeRenderCallback(() -> {
                highlight.setMargin(0);
                highlight.setRadius(2.5);
                highlight.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                highlight.setAlpha(this.isActive() ? .30f : 0f);
            });

            LabelWidget kind = new LabelWidget(this::kindText, FontManager.pf12);
            this.addChild(kind);
            kind.setClickable(false);
            kind.setBeforeRenderCallback(() -> {
                kind.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                kind.centerVertically();
                kind.setPosition(Math.max(0, this.getWidth() - kind.getWidth() - 5), kind.getRelativeY());
            });

            LabelWidget name = new LabelWidget(this::nameText, FontManager.pf12bold);
            this.addChild(name);
            name.setClickable(false);
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setBeforeRenderCallback(() -> {
                name.setColor(NCMScreen.getColor(this.isActive()
                        ? NCMScreen.ColorType.PRIMARY_TEXT : NCMScreen.ColorType.SECONDARY_TEXT));
                name.centerVertically();
                name.setPosition(6, name.getRelativeY());
                name.setMaxWidth(Math.max(1, this.getWidth() - 6 - kind.getWidth() - 10));
            });

            // 整行的 alpha 就是展开进度：文字与高亮都跟着淡入。
            this.setBeforeRenderCallback(() -> this.setAlpha(suggestReveal));

            // 正常路径是 NavigateBar#consumeSuggestionClick（在 NCMScreen 里前置消费、早退），
            // 这里留着组件树这一条只是兜底：两条路走同一个 selectSuggestion，行为一致。
            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0) return false;
                selectSuggestion(this.index);
                return true;
            });
        }

        /** 键盘高亮与鼠标悬停用同一套视觉：两者都表示"回车/点击会选这一条"。 */
        private boolean isActive() {
            return this.isHovering() || suggestHighlight == this.index;
        }

        private NeteaseSearchSuggestService.Suggestion current() {
            List<NeteaseSearchSuggestService.Suggestion> current = suggestions;
            return current == null || this.index >= current.size() ? null : current.get(this.index);
        }

        private String nameText() {
            NeteaseSearchSuggestService.Suggestion suggestion = this.current();
            return suggestion == null || suggestion.text == null ? "" : suggestion.text;
        }

        private String kindText() {
            NeteaseSearchSuggestService.Suggestion suggestion = this.current();
            return suggestion == null || suggestion.kind == null ? "" : suggestion.kind;
        }
    }

    public static class PlaylistItem extends Panel {

        String icon;
        Location iconLocation;
        Supplier<Integer> iconColorSupplier;
        Supplier<String> label;
        Runnable onClick;

        @Getter
        @Setter
        boolean selected = false;

        @Getter
        @Setter
        private PlayList playlist;

        /**
         * 可选的右键（副键）动作。默认不设置时右键沿用旧行为——被吞掉、什么都不做。
         * 目前只由「导入的汽水歌单」条目用来移除本地引用。
         */
        @Getter
        @Setter
        private Runnable onSecondaryClick;

        public PlaylistItem(String icon, Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this(icon, FontManager.music18, null, iconColorSupplier, label, onClick);
        }

        public PlaylistItem(String icon, CFontRenderer iconFont, Supplier<Integer> iconColorSupplier,
                            Supplier<String> label, Runnable onClick) {
            this(icon, iconFont, null, iconColorSupplier, label, onClick);
        }

        public PlaylistItem(Location iconLocation, Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this(null, FontManager.music18, iconLocation, iconColorSupplier, label, onClick);
        }

        private PlaylistItem(String icon, CFontRenderer iconFont, Location iconLocation,
                             Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this.icon = icon;
            this.iconLocation = iconLocation;
            this.iconColorSupplier = iconColorSupplier;
            this.label = label;
            this.onClick = onClick;
            this.setBeforeRenderCallback(() -> {
                // 该项两侧各内缩四个像素。播放器缩放时，将内缩量从
                // 其实时宽度中减去，使背景和文字都不会
                // 延伸到导航视口之外。
                this.setBounds(Math.max(1, this.getParentWidth() - 8), 16);
                this.setPosition(4, this.getRelativeY());
            });

            // 选中背景由侧栏共享的那一条高亮条绘制（见 NavigateBar#layoutSelectionIndicator）。
            // 每个条目各画一块的话，切换栏目只能是一块消失、另一块出现，没有位移可动画。

            AbstractWidget<?> iconWidget;
            if (iconLocation == null) {
                LabelWidget glyphIcon = new LabelWidget(icon, iconFont == null ? FontManager.music18 : iconFont);
                this.addChild(glyphIcon);
                glyphIcon.setBeforeRenderCallback(() -> {
                    glyphIcon.setColor(iconColorSupplier.get());
                    glyphIcon.centerVertically();
                    glyphIcon.setPosition(8, glyphIcon.getRelativeY()/* + .5*/);
                });
                glyphIcon.setClickable(false);
                iconWidget = glyphIcon;
            } else {
                ThemedTextureIconWidget textureIcon = new ThemedTextureIconWidget(iconLocation, FontManager.music18,
                        0, 0, 12, 12);
                this.addChild(textureIcon);
                textureIcon.setBeforeRenderCallback(() -> {
                    textureIcon.setColor(iconColorSupplier.get());
                    textureIcon.centerVertically();
                    textureIcon.setPosition(8, textureIcon.getRelativeY());
                });
                textureIcon.setClickable(false);
                iconWidget = textureIcon;
            }

            LabelWidget lbl = new LabelWidget(label, FontManager.pf14bold);
            this.addChild(lbl);

            lbl.setBeforeRenderCallback(() -> {
                lbl.centerVertically();
                lbl.setPosition(iconWidget.getRelativeX() + iconWidget.getWidth() + 4, lbl.getRelativeY());
                lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lbl.setMaxWidth(this.getWidth() - 8 - iconWidget.getWidth() - 12);
            });

            lbl.setClickable(false);

            this.setOnClickCallback(((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    this.selected = true;

                    this.onClick.run();

                    NCMScreen.getInstance().getPlaylistsPanel().playlistPanel.getChildren().stream()
                            .filter(it -> it instanceof PlaylistItem && it != this)
                            .forEach(it -> ((PlaylistItem) it).setSelected(false));
                } else if (mouseButton == 1 && this.onSecondaryClick != null) {
                    this.onSecondaryClick.run();
                }

                return true;
            }));
        }

    }

    /**
     * 调用文档中记载的网易云搜索歌单接口（type 1000）。
     */
    private List<PlayList> searchNeteasePlaylists(String keyword) {
        List<PlayList> results = new ArrayList<>();
        try {
            JsonObject response = CloudMusicApi.cloudSearch(keyword, CloudMusicApi.SearchType.Playlist).toJsonObject();
            if (response == null || !response.has("result") || !response.get("result").isJsonObject()) {
                return results;
            }
            JsonObject result = response.getAsJsonObject("result");
            JsonArray playlists = result.getAsJsonArray("playlists");
            if (playlists == null) {
                return results;
            }

            for (JsonElement element : playlists) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                try {
                    JsonObject object = element.getAsJsonObject();
                    if ((!object.has("coverImgUrl") || object.get("coverImgUrl").isJsonNull())
                            && object.has("picUrl") && !object.get("picUrl").isJsonNull()) {
                        object.addProperty("coverImgUrl", object.get("picUrl").getAsString());
                    }
                    if ((!object.has("playCount") || object.get("playCount").isJsonNull())
                            && object.has("playcount") && !object.get("playcount").isJsonNull()) {
                        object.addProperty("playCount", object.get("playcount").getAsLong());
                    }
                    PlayList playlist = JsonUtils.parse(object, PlayList.class);
                    if (playlist == null || playlist.getId() == 0L || playlist.getName() == null
                            || playlist.getName().trim().isEmpty() || playlist.getCoverUrl() == null
                            || playlist.getCoverUrl().trim().isEmpty()) {
                        continue;
                    }
                    playlist.setPlatform(MusicPlatform.NETEASE);
                    results.add(playlist);
                } catch (Throwable ignored) {
                    // 单个畸形项不能使其余搜索结果失效。
                }
            }
        } catch (Throwable throwable) {
            System.err.println("[Music] NetEase playlist search failed: " + throwable.getMessage());
        }
        return results;
    }

}
