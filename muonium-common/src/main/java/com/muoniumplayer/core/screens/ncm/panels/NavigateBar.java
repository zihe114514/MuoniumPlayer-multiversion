package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.BilibiliFavoriteService;
import com.muoniumplayer.core.ncm.music.BilibiliPlaylistService;
import com.muoniumplayer.core.ncm.music.BodianMusicService;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.PlaylistRefreshResult;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.ImportedPlaylistStore;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.core.ncm.music.NeteaseSearchSuggestService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.download.DownloadCatalog;
import com.muoniumplayer.core.rendering.BrandVectorIcons;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.AppStoreBrandIcons;
import com.muoniumplayer.core.rendering.SPlayerIcons;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.Textures;
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
import com.muoniumplayer.core.screens.ncm.SPlayerButton;
import com.muoniumplayer.core.screens.ncm.SPlayerStyle;
import com.muoniumplayer.core.screens.ncm.SegmentControl;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.SheetHeroRelay;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 */
public class NavigateBar extends NCMPanel {

    // 同一播放器重建时复用输入控件，文字、光标、选区、横向滚动与焦点一起保留。
    final TextFieldWidget searchField;
    private final String sourceIdentity;
    private final boolean sourceChanged;
    private final Map<String, SidebarLayoutMotion.Snapshot> previousGeometry;
    private final Map<String, AbstractWidget<?>> sidebarEntries = new LinkedHashMap<>();
    private final Map<String, SidebarLayoutMotion> relocations = new LinkedHashMap<>();
    private final Map<String, AbstractWidget<?>> previousSections;
    private final Map<String, AbstractWidget<?>> sidebarSections = new LinkedHashMap<>();
    private final List<RetiringSection> retiringSections = new ArrayList<>();
    private final double previousViewportTop, previousViewportBottom;

    private final Panel retirementLayer = new Panel() {
        @Override protected void beforeRenderChildren(double x, double y) {
            ScissorClipManager.begin(getX(), getY(), getWidth(), getHeight());
        }
        @Override protected void afterRenderChildren(double x, double y) {
            ScissorClipManager.end();
        }
    };
    /**
     * 网易云搜索同时支持单曲和歌单；QQ 内置音源层 目前只提供单曲。
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
                || platform == MusicPlatform.QISHUI || platform == MusicPlatform.BILIBILI
                || platform == MusicPlatform.SPOTIFY || platform == MusicPlatform.APPLE;
    }

    /**
     * 高亮条从一栏跳到另一栏的基准时长。默认动画速度档（3.0，即最快那一档）下实际走约 300ms。
     *
     * <p>写这个数的时候按<b>除完倍率</b>那一档估观感：{@link SheetClock#speed()} 的默认值就是
     * 上限，所以基础常量天然要写大三倍，否则「300ms」在真机上只有 100ms。</p>
     */
    /** 与右侧内容页共用同一档基准时长，避免选中条落后于内容页。 */
    private static final double INDICATOR_MS = 420.0;
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
    private final RoundedRectWidget selectionIndicator = new RoundedRectWidget() {
        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            int primary = SPlayerStyle.primary();
            SPlayerStyle.fill(this.getX(), this.getY(), this.getWidth(), this.getHeight(), SPlayerStyle.RADIUS_LG,
                    SPlayerStyle.a(primary, .10, alpha));
            double inset = Math.min(SPlayerStyle.px(8), this.getHeight() * .3);
            double bar = SPlayerStyle.px(3);
            SPlayerStyle.fill(this.getX(), this.getY() + inset, bar, Math.max(0, this.getHeight() - inset * 2),
                    bar * .5, SPlayerStyle.a(primary, 1.0, alpha));
        }
    };
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
    /**
     * 选中项缓存：高亮条每帧都要问「谁被选中」，原来每次全列表扫描。
     * 选中态只在离散时刻变化，写点必须调 {@link #invalidateSelectionCache()}。
     */
    private PlaylistItem cachedSelectedItem;
    private boolean selectionCacheDirty = true;
    /** SPlayer 顶栏：搜索、音源、账号与设置都挂在这里，见 {@link HeaderLayer}。 */
    private final HeaderLayer headerLayer;
    private final List<PlaylistItem> playlistItems = new CopyOnWriteArrayList<>();

    /**
     * 上一次侧栏摆出来的那一批行：签名 → 当时那个行控件。
     *
     * <p>侧栏不是就地更新的：账号歌单异步落地、换音源，走的都是 {@code NCMScreen#markDirty}
     * → {@code NCMScreen#layout} → {@code new NavigateBar()}，整棵控件树重建。所以新行天生
     * 「第一帧就画满」，而旧行则是凭空消失。留的不只是签名、连控件一起留，退场才有东西可演：
     * 被换下去的那几行交给新一棵侧栏继续画完（见 {@link #retiringRows}）。</p>
     *
     * <p>常驻行（主页/排行榜）两边签名相同，既不重亮也不退场 —— 它们根本没换。</p>
     *
     * <p>静态字段是刻意的：这一棵树整个生命周期里只会有一个侧栏活着，旧的连同它的字段一起被丢掉，
     * 跨重建能留下的只有类上的东西。</p>
     */
    private static Map<String, PlaylistItem> announcedRows = Collections.emptyMap();
    /** 侧栏装配过几次了 —— 第一次那一趟既不演进场也不演退场，见 {@link #addSidebarRow}。 */
    private static boolean rowsAnnounced = false;

    /**
     * 下一次整树重建是否静默跳过<b>行的</b>进场/退场动画。音源切换时置位，由构造函数取走。
     *
     * <p>换音源几乎会把整列行同时换掉（发现区、账号歌单、收藏歌单全变）。如果还按常规演
     * 「旧行退场 + 新行进场」，两批半透明文字会在 150~280ms 里叠在同一列上，读起来就是满屏残影 ——
     * 这正是换源时「上一层残留」的来源之一。这一趟行级全部静默，但换代并不是瞬移：
     * {@code handoffWholeColumn} 会把旧列<b>整块</b>留下演退场、新列整块滑入——
     * 动画从「几百个行级变换」收成「两个整块变换」，既连贯也不会糊。</p>
     */
    private static boolean silentNextBuild;
    /** 本次重建是否静默。构造时从静态位取走并清掉，不污染下一次。 */
    private final boolean silentBuild;
    /** 这一次重建摆出来的行，{@link #layout()} 末尾交还给 {@link #announcedRows}。 */
    private final Map<String, PlaylistItem> sidebarRows = new LinkedHashMap<>();
    /**
     * 错峰号由「这一帧第一次被看见」决定，和曲目行、首页卡片共用同一套（{@link PanelItemEntrance}）。
     * 侧栏本身是个 {@link ScrollPanel}，视口外的行整个不渲染，所以号要现场领而不是建页时发好。
     */
    private final PanelItemEntrance.RevealQueue entranceReveal = new PanelItemEntrance.RevealQueue();

    /** 被换下去、正在退场的行。它们挂在侧栏自己身上（不是列表容器），所以不占槽位、不推新行。 */
    private final List<PlaylistItem> retiringRows = new ArrayList<>();
    /** 退场起算时刻；{@code <= 0} 表示这一批还没开始（第一次推进回调时记，避免装配到渲染之间的空档算进时长）。 */
    private long retiringStartedAt;
    /** 退场基准毫秒。不除「界面切换动画速度」：进场那一条（{@link PanelItemEntrance}）也没除，两条必须同速才配成一对。 */
    private static final long RETIRE_MILLIS = 150L;
    /** 往左带出这一格这么多像素：新一列正占着同一格，只淡不移位会叠成一片认不出的字。 */
    private static final double RETIRE_SLIDE_X = 12.0;
    /** 逐行错开的间隔，与退场时长同量级：十二行也就多拖 132ms，不会变成排队等消失。 */
    private static final long RETIRE_STAGGER_MILLIS = 11L;
    private static final int RETIRE_STAGGER_LIMIT = 14;

    // ── 音源切换·整块交接（Material「fade-through」式） ─────────────────────
    //
    // 参照 Material 3 的 fade-through 过渡（material-components-android 的
    // FadeThroughProvider / MaterialFadeThrough，本注释里的数值都来自那份源码）：
    //  · 出与进<b>顺序执行</b>、共享同一条 0..1 时间线，交接点在 35%（FADE_THROUGH_THRESHOLD）；
    //  · 旧内容在 0%~35% 里淡出，新内容在 35%~100% 里淡入，任何时刻至多一块可见——
    //    没有两层内容同框的叠影，也没有「闪一下黑」的空档（新内容在交接点立即接管）；
    //  · 新内容同时从 92% 缩放回 100%（MaterialFadeThrough.DEFAULT_START_SCALE），
    //    配 emphasized 缓动 cubic-bezier(0.2,0,0,1)。
    // 竖列表里横移会读成「整列被拽了一下」（用户原话：脑血栓）；fade-through 的
    // 「快出 → 微落进入」不产生方向感，观感是内容自己完成了一次优雅的更替。
    //
    // 时长不取 Material 的 token（450ms 对播放器偏拖），取侧栏行级动画族同档：
    // 全程 260ms，其中旧列 0~91ms、新列 91~260ms——与 RETIRE_MILLIS(150) 同量级。
    private static final long COLUMN_HANDOFF_TOTAL_MS = 260L;
    /** 交接点占全程的比例，源码里的 FADE_THROUGH_THRESHOLD = 0.35f。 */
    private static final double COLUMN_HANDOFF_THRESHOLD = 0.35;
    /** 新列进场起点缩放，源码里的 DEFAULT_START_SCALE = 0.92f。 */
    private static final double COLUMN_HANDOFF_START_SCALE = 0.92;
    /** 新列从下方落回的像素数。取 PanelItemEntrance.DROP_DISTANCE(6) 同款——同族动画用同一档幅度。 */
    private static final double COLUMN_HANDOFF_DROP_PX = 6.0;

    /**
     * 音源切换时被换下去的<b>整列</b>。与逐行幽灵（{@link #retiringRows}）互斥：换源走整块交接，
     * 行级动画整趟静默（见 {@link #silentBuild}），所以同一时刻至多一个幽灵列。
     */
    private static final class ColumnGhost {
        final ScrollPanel panel;
        /** 退场起点的相对 x——每帧重钉用，不依赖装配那一帧的布局状态。 */
        final double relativeX;
        /** 旧列当前的不透明度。上一场交接可能演到一半就被连点打断，从这里续演而不是跳回全亮。 */
        final float startAlpha;
        /** 首次推进的时刻；0 = 还没起表（装配到首帧渲染之间的空档不算进时长）。 */
        long startedAt;

        ColumnGhost(ScrollPanel panel, double relativeX, float startAlpha) {
            this.panel = panel;
            this.relativeX = relativeX;
            this.startAlpha = startAlpha;
        }
    }

    private ColumnGhost columnGhost;
    /**
     * 本场交接时间线的起表时刻。旧列的 {@link ColumnGhost#startedAt} 是它的镜像（同一次赋值），
     * 供幽灵摘除后新列的后半段继续查表；{@code 0} 表示没有正在进行的交接。
     */
    private long columnHandoffStartedAt;

    // ── 搜索联想 ────────────────────────────────────────────────────────────
    /** 一条联想行的行高。 */
    private static final double SUGGEST_ROW_HEIGHT = 19.0;
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
    /** 搜索框底边（相对顶栏图层），联想面板挂在它下面。由搜索框自己的回调写入。 */
    private double searchBarBottom = 24.0;
    /** 搜索框左缘与宽度（相对顶栏图层），联想面板与它左对齐。 */
    private double searchBoxLeftValue;
    private double searchBoxWidthValue = 160.0;

    private double searchBoxLeft() {
        return this.searchBoxLeftValue;
    }

    private double searchBoxWidth() {
        return this.searchBoxWidthValue;
    }

    public NavigateBar() {
        this(null);
    }

    public NavigateBar(NavigateBar previous) {
        this.sourceIdentity = currentSourceIdentity();
        this.silentBuild = silentNextBuild;
        silentNextBuild = false;
        this.sourceChanged = previous != null && !this.sourceIdentity.equals(previous.sourceIdentity);
        this.searchField = previous == null ? new TextFieldWidget(FontManager.pf16) : previous.searchField;
        this.headerLayer = new HeaderLayer(previous == null ? null : previous.headerLayer);
        this.playlistSearchMode = previous != null && previous.playlistSearchMode;
        this.previousGeometry = previous == null ? Collections.emptyMap() : previous.captureGeometry();
        this.previousSections = previous == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(previous.sidebarSections);
        this.previousViewportTop = previous == null ? 0 : previous.playlistPanel.getY();
        this.previousViewportBottom = previous == null ? 0
                : previousViewportTop + previous.playlistPanel.getHeight();
        if (previous != null && !this.sourceChanged) {
            this.playlistPanel.actualScrollOffset = previous.playlistPanel.actualScrollOffset;
            this.playlistPanel.targetScrollOffset = previous.playlistPanel.targetScrollOffset;
            this.suggestDismissedFor = previous.suggestDismissedFor;
        }
        this.layout();
        // 行级动画整趟静默（silentBuild），换代的故事由这两个整块讲：旧列滑出淡出、
        // 新列滑入。必须排在 layout() 之后——retirementLayer 的视口回调要在装配完成后
        // 才有正确的 top。
        if (this.sourceChanged) this.handoffWholeColumn(previous);
        if (previous != null && previous.indicatorPlaced) {
            this.indicatorTop = previous.indicatorTop;
            this.indicatorBottom = previous.indicatorBottom;
            this.indicatorLeft = previous.indicatorLeft;
            this.indicatorWidth = previous.indicatorWidth;
            this.indicatorPresence = previous.indicatorPresence;
            this.indicatorPlaced = true;
            // 新树的第一帧从原来的高亮位置接续，不持有旧条目的整棵树。
            this.indicatorOwner = null;
        }
    }

    private static String currentSourceIdentity() {
        MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
        return platform.name() + (platform == MusicPlatform.GD ? ":" + GdStudioSourceSettings.getPlatform() : "");
    }

    /**
     * 让下一次侧栏重建静默换代：不演行进场、不演旧行退场。
     * 只给音源切换用（见 {@link #silentBuild}）；常驻的刷新/账号变化仍保留原有的进场动画。
     */
    public static void suppressNextBuildAnimations() {
        silentNextBuild = true;
    }

    private NCMScreen getScreen() {
        return NCMScreen.getInstance();
    }

    private Map<String, SidebarLayoutMotion.Snapshot> captureGeometry() {
        Map<String, SidebarLayoutMotion.Snapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, AbstractWidget<?>> entry : this.sidebarEntries.entrySet()) {
            AbstractWidget<?> widget = entry.getValue();
            if (widget.isHidden() || widget.getHeight() <= 0) continue;
            SidebarLayoutMotion motion = this.relocations.get(entry.getKey());
            double offset = motion == null ? 0 : motion.offset();
            if (widget instanceof PlaylistItem) offset += 6.0 * (1.0 - ((PlaylistItem) widget).entranceEased);
            result.put(entry.getKey(), new SidebarLayoutMotion.Snapshot(
                    widget.getRelativeY() + offset, widget.getWidgetAlpha()));
        }
        return result;
    }

    private void armRelocation(String key, AbstractWidget<?> widget, boolean enterIfNew) {
        // 静默换代：位置/透明度都直接落到最终值，不装位移与淡入变换。
        if (this.silentBuild) return;
        SidebarLayoutMotion.Snapshot old = this.previousGeometry.get(key);
        if (old == null && (!rowsAnnounced || !enterIfNew)) return;
        SidebarLayoutMotion motion = new SidebarLayoutMotion(old);
        this.relocations.put(key, motion);
        if (old == null) widget.setAlpha(0f);
        widget.setTransformations(() -> {
            motion.tick(System.currentTimeMillis(), widget.getRelativeY(), 900.0 / SheetClock.speed());
            widget.setAlpha(motion.alpha());
            SharedConstants.api.getGLStateManager().translate(0.0, motion.offset(), 0.0);
            if (motion.isFinished()) widget.setTransformations(null);
        });
    }

    private void addSidebarSection(String key, AbstractWidget<?> widget) {
        key = SidebarLayoutMotion.rowKey(key, this.sourceIdentity);
        this.sidebarSections.put(key, widget);
        this.sidebarEntries.put(key, widget);
        this.armRelocation(key, widget, true);
        this.playlistPanel.addChild(widget);
    }

    private static final class RetiringSection {
        final AbstractWidget<?> widget;
        final double absoluteY;
        final float alpha;
        RetiringSection(AbstractWidget<?> widget) {
            this.widget = widget;
            this.absoluteY = widget.getY();
            this.alpha = widget.getWidgetAlpha();
        }
    }

    private void retireSections() {
        for (Map.Entry<String, AbstractWidget<?>> entry : this.previousSections.entrySet()) {
            AbstractWidget<?> widget = entry.getValue();
            if (this.sidebarSections.containsKey(entry.getKey()) || widget.isHidden()
                    || widget.getY() + widget.getHeight() <= this.previousViewportTop
                    || widget.getY() >= this.previousViewportBottom) continue;
            RetiringSection section = new RetiringSection(widget);
            widget.setClickable(false);
            widget.setTransformations(null);
            widget.setParent(this.retirementLayer);
            this.retirementLayer.addChild(widget);
            this.retiringSections.add(section);
        }
        // 已交接的幽灵只有裁剪层临时持有；不能长期保留整棵旧侧栏。
        this.previousSections.clear();
    }

    /**
     * 把一行挂进侧栏，并且只在「上一次侧栏里没有这一行」时给它装一次进场。
     *
     * <p>侧栏走的是整树重建，所以判断新旧只能靠行签名，见 {@link #announcedRows}。</p>
     */
    private PlaylistItem addSidebarRow(String key, PlaylistItem item) {
        key = SidebarLayoutMotion.rowKey(key, this.sourceIdentity);
        final String navigationKey = key;
        // 侧栏入口的文案/面板类型并不唯一：排行榜和电台都可能落到 HomePanel，热搜/最近播放
        // 也都可能落到 NeteaseDiscoveryPanel。点击时把语义键交给目标页，异步加载完成后仍能
        // 精确恢复这一个高亮条，而不是按 instanceof 猜成“主页”。
        final Runnable originalAction = item.onClick;
        item.onClick = () -> {
            NCMScreen.getInstance().setPendingPanelNavigationKey(navigationKey);
            originalAction.run();
        };
        this.sidebarRows.put(key, item);
        this.sidebarEntries.put(key, item);
        item.setBounds(Math.max(1, this.getWidth() - 8), 16);
        // 第一次装配既不演进场也不演退场：那一趟是 prewarm 建的（{@code NCMScreen#prewarmLayout}
        // 之后紧跟的那次 checkDirty 会跳过重建，所以开界面用的就是这一棵）。用户还没见过任何一行，
        // 「新出现的行」和「被换下去的行」在这里都没有意义，而打开播放器自己有转场负责。
        if (!this.silentBuild && rowsAnnounced && !announcedRows.containsKey(key)) {
            item.armEntrance(this.entranceReveal);
        } else {
            this.armRelocation(key, item, false);
        }
        this.playlistPanel.addChild(item);
        return item;
    }

    /**
     * 把上一次有、这一次没有的行交给新侧栏继续画完退场。
     *
     * <p>照换页转场那条老路（{@code NCMScreen} 里的 {@code prevAnimatingPanel}）：旧的不是「消失」，
     * 而是被留住、和新的一起画，各自淡。原来这里只有后者 —— 换音源时旧那一列是<b>凭空不见</b>的。</p>
     *
     * <p>它们挂到侧栏自己身上而不是列表容器里：列表是自动堆叠的，多挂一行就会把新行往下顶一格。
     * 挂在自己身上就只是「按上一次的绝对 y 再画一遍」，不占槽位；点击也一并收掉，退场中的行不该还能按。</p>
     */
    private void retireRows(Collection<PlaylistItem> leaving) {
        if (leaving.isEmpty()) return;
        // 连点两次换音源：上一批还没淡完就又有新的要退。直接把还在半路的丢掉重记一批，
        // 而不是让它们各自带着旧起点继续 —— 否则越叠越多，最后满屏是透明的幽灵行。
        for (PlaylistItem stale : new ArrayList<>(this.retiringRows)) this.retirementLayer.getChildren().remove(stale);
        this.retiringRows.clear();
        this.retiringStartedAt = 0L;

        for (PlaylistItem row : leaving) {
            if (row == null || row.isHidden() || row.getWidgetAlpha() <= .01f
                    || row.getY() + row.getHeight() <= this.previousViewportTop
                    || row.getY() >= this.previousViewportBottom) continue;
            // 只存<b>绝对</b> y，相对位置留到每一帧再算。装配这一趟里 {@code this.getY()} 还不可信：
            // 新侧栏要等 {@code NCMScreen} 的 basePanel 在自己的渲染回调里 center() 才落到最终位置，
            // 而 retireRows 跑在 new NavigateBar() 里面 —— 拿这个还没摆正的原点去做减法，
            // 实测整批幽灵会往下错位一百多像素，退场演到了列表外面。
            row.retireAbsY = row.getY();
            row.retireAlpha = row.getWidgetAlpha();
            // 退场期间不能再被原来的进场回调写回亮度，否则两条动画会互相覆盖。
            row.entranceQueue = null;
            row.setClickable(false);
            row.setShouldOverrideMouseCursor(false);
            row.setTransformations(() -> {
                PanelItemEntrance.applyTransform(row.entranceEased,
                        row.getX() + row.getWidth() * .5, row.getY() + row.getHeight() * .5);
                // 再往左带一段。上下方向没有余地：新一列就占在它那一格上，两行字会正正叠成一片
                // 认不出的糊字（实测「Mu本地歌单」压住「最近播放」）。位移要和淡出用<b>同一条</b>
                // 先掉后缓的曲线 —— 按缓动值算的话，还画满的那头两帧恰好一点没挪开。
                SharedConstants.api.getGLStateManager().translate(-RETIRE_SLIDE_X * row.retireOut, 0.0, 0.0);
            });
            this.retiringRows.add(row);
            // 独立裁剪层排在新列表之前，不占列表槽位，也不会越过搜索栏/底部操作区。
            this.retirementLayer.addChild(row);
            row.setPosition(4, 0);
        }
    }

    /** 每帧推进退场：旧行向左淡出，旧标题淡出；演完就从裁剪层摘掉。 */
    private void advanceRetiringRows() {
        if (this.retiringRows.isEmpty() && this.retiringSections.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (this.retiringStartedAt <= 0L) this.retiringStartedAt = now;
        long elapsed = now - this.retiringStartedAt;

        for (int index = this.retiringRows.size() - 1; index >= 0; index--) {
            PlaylistItem row = this.retiringRows.get(index);
            // 裁剪层此时已经完成本帧布局，重新钉住原来的绝对位置。
            row.setPosition(4, row.retireAbsY - this.retirementLayer.getY());
            long delay = Math.min(index, RETIRE_STAGGER_LIMIT) * RETIRE_STAGGER_MILLIS;
            double progress = Math.max(0.0, Math.min(1.0, (elapsed - delay) / (double) RETIRE_MILLIS));
            // 保留退场起点的进场姿态，只向左淡出，不让旧行突然上下跳动。
            // 不透明度<b>先掉后缓</b>：线性淡出的头两三帧还接近满的，而那正是和新列叠在一起的时候。
            // 幂次取 .35 是让第一帧就落到一半以下，剩下的时间慢慢收干净 —— 残影只当余光里的一点动静。
            row.retireOut = Math.pow(Math.max(0.0, progress), .35);
            row.setAlpha((float) (row.retireAlpha * (1.0 - row.retireOut)));
            if (progress >= 1.0) {
                this.retiringRows.remove(index);
                this.retirementLayer.getChildren().remove(row);
            }
        }
        for (int index = this.retiringSections.size() - 1; index >= 0; index--) {
            RetiringSection section = this.retiringSections.get(index);
            double progress = Math.max(0.0, Math.min(1.0, elapsed / (double) RETIRE_MILLIS));
            double out = Math.pow(progress, .35);
            section.widget.setPosition(section.widget.getRelativeX(),
                    section.absoluteY - this.retirementLayer.getY());
            section.widget.setAlpha((float) (section.alpha * (1.0 - out)));
            if (progress >= 1.0) {
                this.retirementLayer.getChildren().remove(section.widget);
                this.retiringSections.remove(index);
            }
        }
    }

    // ── 音源切换·整块交接（Material「fade-through」式） ─────────────────────

    /**
     * 音源切换的视觉交接：旧歌单列<b>整块</b>按 fade-through 退场，新列整块接管。
     *
     * <p>行级动画在这趟被 {@link #silentBuild} 整体关掉——那是当年为了压「满屏幽灵行」
     * 的一刀切，但也把侧栏换代演成了一次原地瞬移。整块交接把逐行几百个动画收成
     * <b>两个</b>（一列出、一列入），既没有叠字残影，也有了时间结构。</p>
     *
     * <p>旧列整棵 {@link ScrollPanel} 在重建后本来就该被丢掉；这里不复制不快照，
     * 直接把它 reparent 进 {@link #retirementLayer} 继续画完最后一程。裁剪层自己的
     * scissor 保证它不会糊到搜索栏、快捷栏或账号行上。</p>
     */
    private void handoffWholeColumn(NavigateBar previous) {
        // 连点两次换源：上一场还没演完就来了新的一场。直接丢掉旧幽灵重记（与 retireRows
        // 的连点保护同一语义），别让两个半透明列叠在一起。
        this.dropColumnGhost();

        final ScrollPanel oldPanel = previous.playlistPanel;
        if (oldPanel == null || oldPanel.isHidden()) return;

        // 幽灵只演前半段（0~35%），不该再吃输入。递归关掉整棵树的点击与光标改写——
        // retireRows 对单行就是这么做的。
        disableInteractionRecursive(oldPanel);

        this.columnGhost = new ColumnGhost(oldPanel, oldPanel.getRelativeX(),
                Math.max(0f, Math.min(1f, oldPanel.getWidgetAlpha())));
        // 时间线在装配这一刻起表。幽灵按它查前半段，新列的进场 lambda 按它查后半段——
        // 两条变换共享同一条 0..1，交接点严丝合缝。
        this.columnHandoffStartedAt = System.currentTimeMillis();
        this.columnGhost.startedAt = this.columnHandoffStartedAt;
        // reparent 会由 addChild 里 setParent 完成；位置钉在层顶（层与列表视口同高同顶），
        // 之后每帧由 advanceColumnHandoff 按本帧的层坐标重钉。
        this.retirementLayer.addChild(oldPanel);
        oldPanel.setTransformations(null);

        // 新列按 fade-through 的后半段接管：alpha 0→1、92%→100% 微缩放、从下方 6px 落回。
        // 35% 之前它保持全透明——屏幕上任何时刻至多一块内容，交接点处旧列刚好交棒。
        this.playlistPanel.setAlpha(0f);
        this.playlistPanel.setTransformations(this::renderColumnEnter);
    }

    /**
     * 新列的后半段进场变换（挂在 playlistPanel 的 transformations 上）。
     *
     * <p>时间线查的是共享的 {@link #columnHandoffStartedAt}——旧列在 35% 交棒时被摘除，
     * 这条 lambda 从那一刻起才是可见的那块。位移只用「从下方 6px 落回」这一根竖向轴
     * （与行级进场 PanelItemEntrance 同向同幅度，竖列表不会产生横移的晃动感），
     * 缩放 92%→100% 绕列表中心，公式与 {@code PanelItemEntrance.applyTransform} 同款。</p>
     */
    private void renderColumnEnter() {
        final double take = columnHandoffTake();
        if (take >= 1.0) {
            this.playlistPanel.setTransformations(null);
            this.playlistPanel.setAlpha(1f);
            return;
        }
        // 还没到交接点：保持全透明（旧列正在台上）。判定放在 alpha 写入前，避免留下半透明残值。
        if (take <= COLUMN_HANDOFF_THRESHOLD) {
            this.playlistPanel.setAlpha(0f);
            return;
        }
        // 后半段（0.35~1.0）内的进度，再过一次三次缓出——与右侧内容页、sourceMotion、
        // 行级 FLIP 同一条 cubic-bezier(0.2,0,0,1) 的观感族（emphasized）。
        final double local = (take - COLUMN_HANDOFF_THRESHOLD) / (1.0 - COLUMN_HANDOFF_THRESHOLD);
        final double eased = 1.0 - Math.pow(1.0 - Math.max(0.0, Math.min(1.0, local)), 3.0);
        this.playlistPanel.setAlpha((float) eased);

        final double scale = COLUMN_HANDOFF_START_SCALE + (1.0 - COLUMN_HANDOFF_START_SCALE) * eased;
        final double centerX = this.playlistPanel.getX() + this.playlistPanel.getWidth() * .5;
        final double centerY = this.playlistPanel.getY() + this.playlistPanel.getHeight() * .5;
        SharedConstants.api.getGLStateManager().translate(0.0, COLUMN_HANDOFF_DROP_PX * (1.0 - eased), 0.0);
        SharedConstants.api.getGLStateManager().translate(centerX, centerY, 0.0);
        SharedConstants.api.getGLStateManager().scale(scale, scale, 1.0);
        SharedConstants.api.getGLStateManager().translate(-centerX, -centerY, 0.0);
    }

    /**
     * 整块交接的时钟推进，由侧栏自己的 beforeRenderCallback 每帧调一次。
     *
     * <p>fade-through 的前半段：旧列在 0~35% 时间里淡出（alpha = 起点值 × (1-p/0.35)，
     * 纯线性——fade-through 的语义就是「交接点之前干净利落地退完」，不需要缓动修饰），
     * 无位移。旧列压在新列底下（retirementLayer 的挂载顺序在 playlistPanel 之前），
     * 而新列在这段时间是全透明的，所以屏幕上任何时刻至多一块内容——顺序交接，
     * 没有叠影也没有空档。</p>
     */
    private void advanceColumnHandoff() {
        final ColumnGhost ghost = this.columnGhost;
        if (ghost == null) return;
        if (ghost.startedAt == 0L) ghost.startedAt = System.currentTimeMillis();
        final double take = columnHandoffTake(ghost);

        ghost.panel.setPosition(ghost.relativeX, 0);
        // 交接点之前线性退完：0..0.35 → alpha 从起点值收到 0。
        final double outProgress = Math.max(0.0, Math.min(1.0,
                take / COLUMN_HANDOFF_THRESHOLD));
        final float alphaOut = (float) (ghost.startAlpha * (1.0 - outProgress));
        if (alphaOut > 0.01f) {
            ghost.panel.setAlpha(alphaOut);
        }
        if (take >= COLUMN_HANDOFF_THRESHOLD || alphaOut <= 0.01f) {
            // 到交接点：旧列已不可见，立即摘除——新列此刻开始接管，中间没有空帧。
            this.dropColumnGhost();
        }
    }

    /**
     * 当前交接时间线进度 0..1。ghost 在场时按它自己的起表时刻算；幽灵退场后（后半段）
     * 由 handoff 时记录的统一时钟 {@link #columnHandoffStartedAt} 续走，两条变换共享同一条线。
     */
    private double columnHandoffTake() {
        final ColumnGhost ghost = this.columnGhost;
        if (ghost != null) return columnHandoffTake(ghost);
        if (this.columnHandoffStartedAt == 0L) return 1.0;
        return Math.max(0.0, Math.min(1.0,
                (System.currentTimeMillis() - this.columnHandoffStartedAt) / (double) COLUMN_HANDOFF_TOTAL_MS));
    }

    private static double columnHandoffTake(ColumnGhost ghost) {
        if (ghost.startedAt == 0L) ghost.startedAt = System.currentTimeMillis();
        return Math.max(0.0, Math.min(1.0,
                (System.currentTimeMillis() - ghost.startedAt) / (double) COLUMN_HANDOFF_TOTAL_MS));
    }

    /** 摘掉幽灵列：从裁剪层取下、清变换。旧树本来就要被 GC，不需要再归位什么。 */
    private void dropColumnGhost() {
        final ColumnGhost ghost = this.columnGhost;
        if (ghost == null) return;
        this.columnGhost = null;
        ghost.panel.setTransformations(null);
        this.retirementLayer.getChildren().remove(ghost.panel);
    }

    /** 幽灵列整个不能再吃输入：递归关点击与光标改写。滚轮同理——isClickable=false 之后 iterateChildrenDWheel 那条也进不来。 */
    private static void disableInteractionRecursive(AbstractWidget<?> widget) {
        widget.setClickable(false);
        widget.setShouldOverrideMouseCursor(false);
        for (AbstractWidget<?> child : widget.getChildren()) {
            disableInteractionRecursive(child);
        }
    }

    /**
     * 一张歌单在这一次侧栏里的签名。
     *
     * <p>平台必须写进来：网易云和酷狗完全可能给出同一个数字 id，签名撞上了换音源就演不出来 ——
     * 而换音源正是这次要治的两个症状之一。账号/收藏行用当前内容源；本地与导入行属于自己的存储身份，
     * 切换音源时它们仍是同一张歌单，不应重亮或退场。</p>
     */
    private static String playlistRowKey(String kind, PlayList playlist) {
        String owner = ("local".equals(kind) || "imported".equals(kind))
                ? String.valueOf(playlist.getPlatform()) + ':' + playlist.getPlatformPlaylistId()
                : currentSourceIdentity();
        return kind + ':' + owner + ':' + playlist.getId() + ':' + playlist.getName();
    }

    private void layout() {
        // 侧栏底（surfacePanel）+ 右缘一条 border-primary/10，与 SPlayer 默认布局一致。
        RectWidget bg = new RectWidget() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);
                double edge = this.getX() + this.getWidth() - SPlayerStyle.px(.5);
                SPlayerStyle.line(edge, this.getY(), edge, this.getY() + this.getHeight(), SPlayerStyle.px(1),
                        SPlayerStyle.a(SPlayerStyle.primary(), .10, NavigateBar.this.getAlpha()));
            }
        };
        this.addChild(bg);

        this.setBeforeRenderCallback(() -> {
            NCMScreen screen = NCMScreen.getInstance();
            double panelWidth = screen.getPanelWidth();
            // SPlayer w-60；面板很窄时按比例收，但不窄过能放下图标 + 两个字的宽度。
            double width = Math.max(Math.min(SPlayerStyle.SIDEBAR_WIDTH, panelWidth * .2), Math.min(92.0, panelWidth * .30));
            this.setBounds(width, Math.max(1.0, screen.getPanelHeight() - screen.getControlsHeight()));
            this.setPosition(0, 0);

            bg.setMargin(0);
            bg.setColor(this.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            // 侧栏这块底是<em>压在面板根底之上</em>的第二张底，走玻璃的内层那一档：
            // 它自己那套「液态玻璃减 0.22」只把 0.9 压到 0.68，叠上根底之后仍然是一块不透的黑斑，
            // 而且完全不理设置里的「玻璃浓度」滑块——玩家说的「页面内部有暗色部分」就是这么来的。
            bg.setAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER,
                    0.9f - NCMTheme.getLiquidGlassAmount() * 0.22f));

            // 高亮条的布局必须由侧栏来跑，不能挂在高亮条自己的 beforeRenderCallback 上：
            // AbstractWidget#renderWidget 在调用 beforeRenderCallback 之前就会因为 isHidden()
            // 直接返回（父组件的渲染循环也会跳过隐藏的子组件），而唯一能把它重新显示出来的代码
            // 就在那个回调里。于是首帧一旦因为列表还没完成布局而被判定为不可见，就会永久卡在隐藏
            // 状态——选中项的强调色高亮框再也不会出现。
            this.layoutSelectionIndicator();
            // 退场中的旧行同理：它们已经不属于任何列表，没人推进就会永远停在半透明上。
            // 退场由裁剪层自己的布局回调推进，确保这一帧的视口坐标已经就位。
            // 整块幽灵列（音源切换）同一条道理：层坐标就位后才重钉幽灵的位置。
            this.advanceColumnHandoff();
        });

        this.setOnKeyTypedCallback((character, keyCode) -> {

            if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_F)) {
                this.searchField.setFocused(true);
                this.searchField.getTextField().selectAll();
                return true;
            }

            return false;
        });

        // SPlayer 侧栏顶端是 Logo；搜索、音源、账号与设置都在右侧顶栏（HeaderLayer）。
        this.addChild(new LogoRow());
        this.headerLayer.build();

        // 先加入高亮条，再加入列表：渲染顺序即层次，高亮条要在条目文字下面。
        this.addChild(selectionIndicator);
        this.selectionIndicator.setClickable(false);

        this.addChild(this.retirementLayer);
        this.retirementLayer.setClickable(false);
        this.retirementLayer.setBeforeRenderCallback(() -> {
            double top = sidebarListTop();
            this.retirementLayer.setBounds(0, top, this.getWidth(),
                    Math.max(0, this.getHeight() - top - sidebarListBottomInset()));
            this.advanceRetiringRows();
        });
        this.addChild(playlistPanel);
        this.playlistPanel.setBeforeRenderCallback(() -> {
            this.playlistPanel.setMargin(0);
            double top = sidebarListTop();
            this.playlistPanel.setPosition(this.playlistPanel.getRelativeX(), top);
            // 账号与快捷按钮都去了顶栏，列表一直铺到侧栏底，只留 SPlayer 的 pb-3。
            this.playlistPanel.setBounds(this.playlistPanel.getWidth(),
                    Math.max(0, this.getHeight() - top - sidebarListBottomInset()));
        });

        this.playlistPanel.setSpacing(SPlayerStyle.NAV_GAP);


        {
            // 已经在默认主页时点「主页」不重建：新实例与当前页视觉完全一样，走一次换页只会把同一屏
            // 内容淡出再淡入，读起来就是一次「闪」。固定结果页（榜单/搜索/电台）仍照常切页。
            PlaylistItem item = new PlaylistItem("A", () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                    () -> "主页", () -> {
                        NCMScreen screen = NCMScreen.getInstance();
                        NCMPanel current = screen.getCurrentPanel();
                        if (current instanceof HomePanel && ((HomePanel) current).isDefaultHomePage()) return;
                        screen.setCurrentPanel(new HomePanel());
                    });
            item.setSelected(true);
            this.homeItem = item;
            invalidateSelectionCache();

            item.setShouldOverrideMouseCursor(true);

            this.addSidebarRow("nav:home", item);
        }

        boolean qqMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.QQ;
        boolean kugouMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.KUGOU;
        boolean youtubeMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.YOUTUBE;
        boolean bilibiliMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.BILIBILI;
        boolean qishuiMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.QISHUI;
        boolean bodianMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.BODIAN;
        boolean spotifyMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.SPOTIFY;
        boolean appleMode = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.APPLE;
        // GD 聚合源沿用网易云那一套入口（原来 neteaseMode 就是这个含义），这里只把自有入口的平台单独拆出来。
        // Spotify 沿用网易云的发现入口（它的搜索就是网易云同款形态），账号歌单走自己的快照。
        boolean neteaseMode = !qqMode && !kugouMode && !youtubeMode && !bilibiliMode && !qishuiMode && !bodianMode
                && !spotifyMode && !appleMode;
        {
            SectionHeader lblDiscovery = new SectionHeader(
                    qishuiMode ? "汽水音乐" : bodianMode ? "波点音乐" : bilibiliMode ? "哔哩哔哩" : youtubeMode ? "YouTube Music"
                            : kugouMode ? "酷狗音乐发现" : spotifyMode ? "Spotify"
                            : appleMode ? "Apple Music"
                            : qqMode ? "QQ 音乐发现" : "网易云发现", true);
            this.addSidebarSection("section:discovery", lblDiscovery);

            // 排行榜：网易云的榜单页最终展示的是 HomePanel 卡片墙，先取数据再一次切页（navigateAsync），
            // 否则会先闪一个空的中转页、历史栈里也会多出一条回不去的记录。
            // QQ 的榜单不一样 —— 它是真的留在 NeteaseDiscoveryPanel 里按歌曲行展示，所以照旧直接切页。
            // Spotify 没有对齐的榜单对象，发现区只留账号歌单；Apple 同理（v1 不做榜单 UI）。
            if (!youtubeMode && !bilibiliMode && !qishuiMode && !bodianMode && !spotifyMode) {
                this.addSidebarRow("nav:chart", new PlaylistItem(FontelloIcons.LEADERBOARD, FontManager.fontello16,
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
                    } else if (appleMode) {
                        // Apple 榜单:RSS feed 无需登录,一张卡片一个地区,点进由 chart: 懒加载。
                        NCMScreen.getInstance().navigateAsync("排行榜",
                                com.muoniumplayer.core.ncm.apple.chart.AppleChartsPanels::loadTopListsPanel);
                    } else {
                        NCMScreen.getInstance().navigateAsync("排行榜", NeteaseDiscoveryPanel::loadTopListsPanel);
                    }
                }));
            }
            if (qqMode) {
                // QQ 也有自己的热搜端点，和网易云一样在发现页里展示列表，保持直接切页。
                this.addSidebarRow("nav:hot", new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH, MusicPlatform.QQ))));
                this.addSidebarRow("nav:radio", new PlaylistItem("台", FontManager.pf12bold,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "电台", () -> NCMScreen.getInstance().navigateAsync(
                        "QQ 电台", RadioHubPanel::new)));
                this.addSidebarRow("nav:fm", new PlaylistItem(MusicBrandIcons.PERSONAL_FM,
                        FontManager.musicBrand16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "私人 FM", () -> NCMScreen.getInstance()
                        .setCurrentPanel(new PersonalFmPanel())));
            }
            if (qishuiMode) {
                this.addSidebarRow("nav:qishui-radio", new PlaylistItem("台", FontManager.pf12bold,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "电台", () -> NCMScreen.getInstance().navigateAsync(
                        "汽水电台", this::loadQishuiRadioPanel)));
            }
            if (kugouMode) {
                // 酷狗这三项和网易云同一形态：热搜在发现页里出列表，另外两项先取数据再一次切页。
                // 以前整块挂在 neteaseMode 下，所以切到酷狗后侧栏干脆没有这些入口。
                this.addSidebarRow("nav:hot", new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH, MusicPlatform.KUGOU))));
                this.addSidebarRow("nav:recent", new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "最近播放", () -> NCMScreen.getInstance()
                        .navigateAsync("最近播放", NeteaseDiscoveryPanel::loadKuGouRecentSongsPanel)).withSPlayerIcon(SPlayerIcons.HISTORY));
                this.addSidebarRow("nav:daily", new PlaylistItem("每", FontManager.pf12bold,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "每日推荐", () -> NCMScreen.getInstance()
                        .navigateAsync("每日推荐", NeteaseDiscoveryPanel::loadKuGouDailyPanel)));
                this.addSidebarRow("nav:fm", new PlaylistItem(MusicBrandIcons.PERSONAL_FM, FontManager.musicBrand16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "私人 FM", () -> NCMScreen.getInstance().setCurrentPanel(new PersonalFmPanel())));
            }
            if (neteaseMode) {
                // 热搜是真正在发现页里展示列表的，保持直接切页。
                this.addSidebarRow("nav:hot", new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH))));
                this.addSidebarRow("nav:recent", new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "最近播放", () -> NCMScreen.getInstance()
                        .navigateAsync("最近播放", NeteaseDiscoveryPanel::loadRecentSongsPanel)).withSPlayerIcon(SPlayerIcons.HISTORY));
                this.addSidebarRow("nav:radio", new PlaylistItem("台", FontManager.pf12bold,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "电台 · 播客", () -> NCMScreen.getInstance().navigateAsync(
                        "电台 · 播客", RadioHubPanel::new)));
                this.addSidebarRow("nav:digital-albums", PlaylistItem.withLucideIcon(FoliaLucideIcons.ALBUM,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "已购专辑", () -> NCMScreen.getInstance().navigateAsync(
                        "已购专辑", NeteaseDiscoveryPanel::loadDigitalAlbumsPanel)));
                this.addSidebarRow("nav:netease-cloud", PlaylistItem.withLucideIcon(FoliaLucideIcons.CLOUD_DOWNLOAD,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "音乐云盘", () -> NCMScreen.getInstance().navigateAsync(
                        "音乐云盘", NeteaseDiscoveryPanel::loadCloudSongsPanel)));
                this.addSidebarRow("nav:fm", new PlaylistItem(MusicBrandIcons.PERSONAL_FM, FontManager.musicBrand16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "私人 FM", () -> NCMScreen.getInstance().setCurrentPanel(new PersonalFmPanel())));
            }
            if (qishuiMode) {
                this.addSidebarRow("nav:qishui-recent", new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "最近播放", () -> NCMScreen.getInstance()
                        .navigateAsync("最近播放", NeteaseDiscoveryPanel::loadQishuiRecentSongsPanel)).withSPlayerIcon(SPlayerIcons.HISTORY));
                // 汽水发现流与私人 FM 同构（上报已听 → 无限续个性化流），复用同一面板。
                // 面板里的模式选择器在汽水下不生效——发现流没有模式参数，状态文案会说明。
                this.addSidebarRow("nav:qishui-fm", new PlaylistItem(MusicBrandIcons.PERSONAL_FM,
                        FontManager.musicBrand16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "发现流 FM", () -> NCMScreen.getInstance().setCurrentPanel(new PersonalFmPanel())));
            }
            if (spotifyMode) {
                // Spotify 的设备控制：遥控 / 接管账号里正在播放的 Spotify 客户端
                //（Web API 客户端控制，见 SpotifyConnectSheet 与 SpotifyConnectMirror）。
                this.addSidebarRow("nav:spotify-devices", new PlaylistItem("控", FontManager.pf12bold,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "设备控制", () -> NCMScreen.getInstance().openSpotifyConnectSheet()));
            }
        }
        // 「Mu本地歌单」独立成栏，排在账号歌单之上：这一栏里的单子只活在本机 —— 上面几张是用户
        // 自己新建的（连曲目一起存），下面几张是粘贴链接带进来的引用。标题行右侧那颗「＋」开建单页，
        // 建单页里另有一颗「从链接导入」：建单和导入回答的是同一个问题，不必各占一个入口。
        // 标题行右侧那颗「＋」开建单页（SPlayer「我的歌单」分组头的同款 26px 圆按钮）。
        SectionHeader importHeader = new SectionHeader("Mu本地歌单", true)
                .withAction(SPlayerIcons.PLUS, () -> NCMScreen.getInstance().openPlaylistCreate());
        this.addSidebarSection("section:local", importHeader);

        // 自建单排在前、导入引用排在后：前者是「我造的容器」，后者是「别人家的单子借个座」。
        // 两者点开都是 PlaylistPanel，右键都只动本机 —— 差别只在曲目是存下来的还是要回上游拉的。
        List<PlayList> localPlaylists = LocalPlaylistStore.list();
        List<PlayList> importedPlaylists = ImportedPlaylistStore.list();
        if (localPlaylists.isEmpty() && importedPlaylists.isEmpty()) {
            PlaylistItem hint = new PlaylistItem("·",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "点「＋」新建一张",
                    () -> NCMScreen.getInstance().openPlaylistCreate());
            hint.setShouldOverrideMouseCursor(true);
            this.addSidebarRow("hint:local-empty", hint);
        }
        for (PlayList local : localPlaylists) {
            final PlayList ready = local;
            PlaylistItem item = new PlaylistItem("D",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    ready::getName,
                    () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(ready)));
            item.setPlaylist(ready);
            item.setShouldOverrideMouseCursor(true);
            item.setOnSecondaryClick(() -> removeLocalPlaylist(ready));
            this.playlistItems.add(item);
            this.addSidebarRow(playlistRowKey("local", ready), item);
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
            this.addSidebarRow(playlistRowKey("imported", ready), item);
        }

        SectionHeader playlistsHeader = new SectionHeader("我的账号歌单", true);
        this.addSidebarSection("section:account", playlistsHeader);

        if (qishuiMode) {
            if (NeteaseMusicService.isLoggedIn(MusicPlatform.QISHUI)) {
                // 账号歌单不再独占一行跳到二级页：走快照异步加载，
                // 直接摊在下面「我的账号歌单」栏里（与 QQ / 酷狗一致）。
                PlaylistItem favorites = new PlaylistItem("C",
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "我收藏的歌曲", () -> NCMScreen.getInstance().navigateAsync(
                        "我收藏的歌曲", this::loadQishuiCollectionPanel));
                favorites.setShouldOverrideMouseCursor(true);
                this.addSidebarRow("nav:qishui-fav", favorites);
                // 云歌单建单入口：写请求经本机签名后端落在汽水账号里，建好直接进详情页。
                PlaylistItem cloudCreate = new PlaylistItem("+",
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "新建云歌单",
                        () -> NCMScreen.getInstance().openQishuiCloudPlaylistCreate());
                cloudCreate.setShouldOverrideMouseCursor(true);
                this.addSidebarRow("nav:qishui-cloud-create", cloudCreate);
            } else {
                PlaylistItem login = new PlaylistItem("·",
                        () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "登录可同步账号歌单",
                        () -> NCMScreen.getInstance().openAccountManager());
                login.setShouldOverrideMouseCursor(true);
                this.addSidebarRow("nav:qishui-login", login);
            }
        }

        if (qqMode && NeteaseMusicService.isLoggedIn(MusicPlatform.QQ)) {
            PlaylistItem qqCreate = new PlaylistItem("+",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "新建 QQ 音乐歌单",
                    () -> NCMScreen.getInstance().openPlaylistCreate());
            qqCreate.setShouldOverrideMouseCursor(true);
            this.addSidebarRow("nav:qq-playlist-create", qqCreate);
        }

        if (bodianMode && NeteaseMusicService.isLoggedIn(MusicPlatform.BODIAN)) {
            PlaylistItem bodianCreate = new PlaylistItem("+",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "新建波点云歌单",
                    () -> NCMScreen.getInstance().openPlaylistCreate());
            bodianCreate.setShouldOverrideMouseCursor(true);
            this.addSidebarRow("nav:bodian-playlist-create", bodianCreate);
        }

        // QQ / 酷狗 / B 站 / 汽水 / 波点 / Spotify / Apple 的账号歌单都是异步加载的快照；网易云继续使用现有 CloudMusic 缓存。
        List<PlayList> pl = bodianMode ? BodianMusicService.getUserPlaylistsSnapshot()
                : qqMode
                ? NeteaseMusicService.getQQUserPlaylistsSnapshot()
                : kugouMode ? KuGouMusicService.getUserPlaylistsSnapshot()
                : bilibiliMode ? BilibiliFavoriteService.getUserPlaylistsSnapshot()
                : qishuiMode ? QishuiMusicService.getUserPlaylistsSnapshot()
                : spotifyMode ? com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.getUserPlaylistsSnapshot()
                : appleMode ? com.muoniumplayer.core.ncm.apple.AppleMusicService.getUserPlaylistsSnapshot()
                : youtubeMode ? Collections.emptyList() : CloudMusic.playLists;

        if (qqMode && NeteaseMusicService.isLoggedIn(MusicPlatform.QQ)
                && !NeteaseMusicService.areQQUserPlaylistsLoaded()) {
            NeteaseMusicService.ensureQQUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }
        if (bodianMode && NeteaseMusicService.isLoggedIn(MusicPlatform.BODIAN)
                && !BodianMusicService.areUserPlaylistsLoaded()) {
            BodianMusicService.ensureUserPlaylistsAsync(() ->
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
        if (spotifyMode && NeteaseMusicService.isLoggedIn(MusicPlatform.SPOTIFY)
                && !com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.areUserPlaylistsLoaded()) {
            com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.ensureUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }
        if (appleMode && NeteaseMusicService.isLoggedIn(MusicPlatform.APPLE)
                && !com.muoniumplayer.core.ncm.apple.AppleMusicService.areUserPlaylistsLoaded()) {
            com.muoniumplayer.core.ncm.apple.AppleMusicService.ensureUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }

        if (pl != null && !pl.isEmpty()) {
            if (qqMode || kugouMode || bilibiliMode || qishuiMode || bodianMode || spotifyMode || appleMode) {
                for (PlayList playList : pl) {
                    PlaylistItem item = new PlaylistItem("D",
                            () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                            playList::getName,
                            () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                    item.setPlaylist(playList);
                    this.playlistItems.add(item);
                    item.setShouldOverrideMouseCursor(true);
                    this.addSidebarRow(playlistRowKey("account", playList), item);
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
                    this.addSidebarRow(playlistRowKey("account", playList), item);
                }
            }
        } else if (qqMode || kugouMode || bilibiliMode || qishuiMode || bodianMode || spotifyMode || appleMode) {
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
            } else if (bodianMode) {
                status = !NeteaseMusicService.isLoggedIn(MusicPlatform.BODIAN)
                        ? "登录波点音乐后加载歌单"
                        : (BodianMusicService.areUserPlaylistsLoading()
                        ? "正在加载波点歌单…" : "暂无可用波点歌单");
            } else if (spotifyMode) {
                status = !NeteaseMusicService.isLoggedIn(MusicPlatform.SPOTIFY)
                        ? "完成 Spotify 授权后加载歌单（音源浮层里登录）"
                        : (com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.areUserPlaylistsLoading()
                        ? "正在加载 Spotify 歌单…" : "暂无可用 Spotify 歌单");
            } else if (appleMode) {
                status = !NeteaseMusicService.isLoggedIn(MusicPlatform.APPLE)
                        ? "导入 Apple cookies 后加载歌单（音源浮层里登录）"
                        : (com.muoniumplayer.core.ncm.apple.AppleMusicService.areUserPlaylistsLoading()
                        ? "正在加载 Apple 歌单…" : "暂无可用 Apple 歌单");
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
            this.addSidebarRow("status:" + status, statusItem);
        }

        SectionHeader lblSubscribed = new SectionHeader("收藏歌单", true);

        // 收藏歌单是网易云独有的分组：QQ 与酷狗的收藏歌单都已经并入上面的"我的账号歌单"。
        lblSubscribed.setHidden(qqMode || kugouMode || youtubeMode || bilibiliMode || qishuiMode || bodianMode
                || spotifyMode || appleMode);
        this.addSidebarSection("section:subscribed", lblSubscribed);

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
                this.addSidebarRow(playlistRowKey("subscribed", playList), item);
            });
        }
        // 上一次有、这一次没有的那些行交给退场。签名要在装配<b>全部</b>做完之后才交还：只记到一半
        // 会让没记上的那批无辜重亮一次，或者反过来把该退的那批漏掉。
        // 静默换代（音源切换）时这一整段跳过：旧行直接随旧树一起丢掉，不再演退场。
        if (rowsAnnounced && !this.silentBuild) {
            List<PlaylistItem> leaving = new ArrayList<>();
            for (Map.Entry<String, PlaylistItem> previous : announcedRows.entrySet()) {
                if (!this.sidebarRows.containsKey(previous.getKey())) leaving.add(previous.getValue());
            }
            this.retireRows(leaving);
        }
        if (this.silentBuild) this.previousSections.clear();
        else this.retireSections();
        announcedRows = Collections.unmodifiableMap(new LinkedHashMap<>(this.sidebarRows));
        rowsAnnounced = true;

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
        this.headerLayer.addChild(this.suggestionDismissLayer);
        this.suggestionDismissLayer.setHidden(true);
        this.suggestionDismissLayer.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            this.dismissSuggestions();
            // 吃掉这一下：这次点击的意图是"关掉联想面板"，不能同时触发它盖住的条目。
            return true;
        });

        this.headerLayer.addChild(this.suggestionPanel);
        this.suggestionPanel.setHidden(true);

        RoundedRectWidget surface = new RoundedRectWidget();
        this.suggestionPanel.addChild(surface);
        surface.setClickable(false);
        surface.setBeforeRenderCallback(() -> {
            surface.setMargin(0);
            surface.setRadius(SPlayerStyle.RADIUS_LG);
            surface.setColor(SPlayerStyle.mix(SPlayerStyle.bright(), SPlayerStyle.onSurface(), .10));
            surface.setAlpha(this.suggestReveal);
        });

        RoundedRectWidget inner = new RoundedRectWidget();
        surface.addChild(inner);
        inner.setClickable(false);
        inner.setBeforeRenderCallback(() -> {
            inner.setMargin(SPlayerStyle.px(1));
            inner.setRadius(SPlayerStyle.RADIUS_LG - SPlayerStyle.px(1));
            inner.setColor(SPlayerStyle.bright());
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
        // 错峰号每帧重发：这一帧里第一次被看见的那几行才是同一批。放在这里（父组件的 onRender
        // 跑在子组件之前）和曲目列表那一条规则完全一致。
        this.entranceReveal.beginFrame();
        super.onRender(mouseX, mouseY);
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int mouseButton) {
        return false;
    }

    /**
     * 侧栏刷新按钮：按当前平台重拉「我的账号歌单」正在显示的那一路数据。
     *
     * <p>转圈状态挂在顶栏设置菜单的「刷新账号歌单」那一项上。
     *
     * <p>网易云沿用 CloudMusic 的防重入槽与同步刷新；QQ / 酷狗 / B站 / 汽水走各自的
     * 强制重拉入口——它们平时是「一局只拉一次」的快照，没有这颗按钮就再也不会更新。
     * 酷我 / 咪咕 / GD / 系统播放器这些模式的账号歌单栏兜底显示网易云，刷新也刷网易云。
     * YouTube 模式没有账号歌单，直接说明情况而不是让按钮无声无息。</p>
     */
    private void triggerSidebarRefresh(HeaderLayer btnRefresh) {
        MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
        if (platform == MusicPlatform.QQ) {
            startSidebarRefresh(btnRefresh, NeteaseMusicService.areQQUserPlaylistsLoading(),
                    () -> NeteaseMusicService.forceRefreshQQAccountAsync(
                            result -> completeSidebarRefresh(btnRefresh, result)));
        } else if (platform == MusicPlatform.KUGOU) {
            startSidebarRefresh(btnRefresh, KuGouMusicService.areUserPlaylistsLoading(),
                    () -> KuGouMusicService.forceRefreshUserPlaylistsAsync(
                            result -> completeSidebarRefresh(btnRefresh, result)));
        } else if (platform == MusicPlatform.BILIBILI) {
            startSidebarRefresh(btnRefresh, BilibiliFavoriteService.areUserPlaylistsLoading(),
                    () -> BilibiliFavoriteService.forceRefreshUserPlaylistsAsync(
                            result -> completeSidebarRefresh(btnRefresh, result)));
        } else if (platform == MusicPlatform.QISHUI) {
            startSidebarRefresh(btnRefresh, QishuiMusicService.areUserPlaylistsLoading(),
                    () -> QishuiMusicService.forceRefreshUserPlaylistsAsync(
                            result -> completeSidebarRefresh(btnRefresh, result)));
        } else if (platform == MusicPlatform.BODIAN) {
            startSidebarRefresh(btnRefresh, BodianMusicService.areUserPlaylistsLoading(),
                    () -> BodianMusicService.forceRefreshUserPlaylistsAsync(
                            result -> completeSidebarRefresh(btnRefresh, result)));
        } else if (platform == MusicPlatform.YOUTUBE) {
            DownloadDynamicIsland.showPlaylistRefreshFailure("YouTube 模式没有账号歌单可刷新");
        } else {
            refreshNeteaseFromSidebar(btnRefresh);
        }
    }

    /**
     * 各平台强刷入口共用的起手式：转起图标、报一枚进度提示，然后（若没有请求在路上）
     * 启动这一次刷新。已在刷新中时不重起请求——双击只会让灵动岛回到「正在刷新」，
     * 与网易云防重入槽被占住时的表现一致。
     */
    private void startSidebarRefresh(HeaderLayer btnRefresh, boolean alreadyRefreshing, Runnable starter) {
        btnRefresh.setSpinning(true);
        DownloadDynamicIsland.showPlaylistRefreshInProgress();
        if (!alreadyRefreshing) {
            starter.run();
        }
    }

    /** 平台强刷入口的统一收尾。服务回调可能落在后台线程，先切回主线程再动 UI。 */
    private void completeSidebarRefresh(HeaderLayer btnRefresh, PlaylistRefreshResult result) {
        MultiThreadingUtil.runOnMainThread(() -> {
            btnRefresh.setSpinning(false);
            if (result.isSuccess()) {
                NCMScreen.getInstance().markDirty();
                // 快照换新后重建可见的播放列表行：像网易云「网盘」徽标那样按数据出现的元素立刻到位。
                NCMScreen.getInstance().reloadCurrentPanel();
                DownloadDynamicIsland.showPlaylistRefreshSuccess(
                        result.getPlaylistCount(), result.getElapsedMillis());
            } else {
                DownloadDynamicIsland.showPlaylistRefreshFailure(result.getMessage());
            }
        });
    }

    /**
     * 网易云那一路：防重入槽 + 同步刷新跑在工作线程上，全部请求成功才换快照。
     * 这是刷新按钮最早支持的平台，行为原样保留。
     */
    private void refreshNeteaseFromSidebar(HeaderLayer btnRefresh) {
        if (!CloudMusic.beginNeteaseRefresh()) {
            btnRefresh.setSpinning(true);
            DownloadDynamicIsland.showPlaylistRefreshInProgress();
            return;
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

    /**
     * 账号行右端会员徽标的文案；当前平台没有会员身份时返回 {@code null}（徽标不亮）。
     *
     * <p>网易云沿用 {@link #isNeteaseVip()}；汽水按账号权益取「SVIP」（vip_stage 含 svip）
     * 与「VIP」（其余会员档），免费账号与未登录不亮——普通用户挂一枚「普通」标是噪音，
     * 账号身份这件事没有信息可说就不说。三个数据源全是 volatile 快照，每帧调用安全。</p>
     */
    private static String membershipBadgeText() {
        MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
        if (platform == MusicPlatform.NETEASE) return isNeteaseVip() ? "VIP" : null;
        if (platform == MusicPlatform.QISHUI) {
            return QishuiMusicService.membershipBadgeText();
        }
        if (platform == MusicPlatform.BODIAN && BodianMusicService.isVipAccount()) return "VIP";
        return null;
    }

    private static String sourceGlyph(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) return MusicBrandIcons.QQ_MUSIC;
        if (platform == MusicPlatform.YOUTUBE) return MusicBrandIcons.YOUTUBE;
        if (platform == MusicPlatform.BILIBILI) return MusicBrandIcons.BILIBILI_MONOGRAM;
        if (platform == MusicPlatform.QISHUI) return MusicBrandIcons.QISHUI_MONOGRAM;
        if (platform == MusicPlatform.SPOTIFY) return MusicBrandIcons.SPOTIFY_MONOGRAM;
        if (platform == MusicPlatform.BODIAN) return "点";
        return MusicBrandIcons.NETEASE_CLOUD_MUSIC;
    }

    private static CFontRenderer sourceFont(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) return FontManager.qqMusicIcon20;
        if (platform == MusicPlatform.YOUTUBE) return FontManager.youtubeIcon24;
        if (platform == MusicPlatform.BILIBILI) return FontManager.pf12bold;
        if (platform == MusicPlatform.QISHUI) return FontManager.pf12bold;
        if (platform == MusicPlatform.SPOTIFY) return FontManager.pf12bold;
        if (platform == MusicPlatform.BODIAN) return FontManager.pf12bold;
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
            ThemedTextureIconWidget appIcon = AppStoreBrandIcons.widget(this.platform,
                    sourceGlyph(this.platform), sourceFont(this.platform), 0, 0, SOURCE_ICON_BOX);
            this.platformIcon = appIcon != null ? appIcon : BrandVectorIcons.has(this.platform)
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
                this.platformIcon.setColor(appIcon != null ? 0xFFFFFFFF
                        : NeteaseMusicService.getCurrentPlatform() == this.platform
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

        // 联想词此前只对网易云开放，酷狗的输入框因此永远弹不出面板。现在各源都走
        // NeteaseSearchSuggestService，由它按当前平台选端点；这里只判断"当前源有没有联想"。
        MusicPlatform suggestPlatform = NeteaseMusicService.getCurrentPlatform();
        boolean suggestSource = suggestPlatform == MusicPlatform.NETEASE
                || suggestPlatform == MusicPlatform.KUGOU
                || suggestPlatform == MusicPlatform.QISHUI;
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
        // 收起层铺满顶栏以下的整个播放器（侧栏、内容区、播放栏）：坐标相对顶栏图层。
        NCMScreen screen = NCMScreen.getInstance();
        HeaderLayer header = this.headerLayer;
        this.suggestionDismissLayer.setBounds(Math.max(1.0, screen.getPanelWidth()),
                Math.max(1.0, screen.getPanelHeight() - header.getHeight()));
        this.suggestionDismissLayer.setPosition(this.getX() - header.getX(), header.getHeight());

        // 面板要比它盖住的歌单条目宽：条目自己占 x=4 到 width-4（见 PlaylistItem 的布局），
        // 而 hovering 是各组件按自己的矩形独立算的、兄弟之间不互相压制。面板要是只有 x=8 到 width-8，
        // 鼠标停在面板上时下面那一行照样算自己被悬停，强调色高亮就会从面板两侧各露出 4px。
        // 取 x=3 / width-6 刚好把条目整个盖住，还多出 1px 余量。
        double searchLeft = this.searchBoxLeft();
        double width = Math.max(SPlayerStyle.px(240), this.searchBoxWidth());
        double height = count * SUGGEST_ROW_HEIGHT + SUGGEST_PADDING * 2.0;
        this.suggestionPanel.setHidden(false);
        this.suggestionPanel.setBounds(width, height);
        // 轻微下滑入场：只动位置不动高度，因为 Panel 不裁剪子组件，压高度会让行画到面板外面。
        this.suggestionPanel.setPosition(searchLeft,
                this.searchBarBottom + SPlayerStyle.px(6) - 4.0 * (1f - this.suggestReveal));

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
     * <h2>面板状态是每帧变的：以按下时的快照为准（穿透修复）</h2>
     * 触屏时代的点击是「按下只记账、松手才分发」，中间隔着任意长的时间。而联想面板恰恰
     * 是全界面里状态最易变的消费者：异步联想结果到达时行数增减、{@code suggestReveal}
     * 入场动画每帧上移、提交搜索后立刻整体隐藏。于是出现时间窗——按下的瞬间面板还在
     * （用户看到并瞄准的就是联想面板），松手那一刻面板已经没了：本方法的可见性判断直接
     * 放行，同一次点击穿透到底下的歌单条目/音乐来源卡片。所以 {@link NCMScreen} 在按下时
     * 用 {@link #suggestionLayerVisibleNow()} 拍一份快照、松手分发时原样传回来：面板几何
     * 用松手时刻的（行还点得中，信息不过期），<b>可见性裁决只用按下时的</b>——按下面板
     * 挡着的那一下点击，永远归联想层消费。
     *
     * @param pressVisible 按下瞬间的 {@link #suggestionLayerVisibleNow()} 快照；即时路径
     *                     （手势层关闭）传按下即松手的现值即可
     * @return 是否吃掉了这次点击
     */
    public boolean consumeSuggestionClick(double mouseX, double mouseY, int mouseButton,
                                          boolean pressVisible, int pressHoveredRow) {
        if (!pressVisible) return false;
        boolean visibleNow = this.suggestionsVisible && !this.suggestionPanel.isHidden();
        if (!visibleNow) {
            // 面板此刻已经不在了：这一下仍然整体吃掉。按下面板挡着，用户的意图是点面板
            // 或关面板——一次点按的位移在阈值（8 GUI 像素）内，不可能在这段时间里合法地
            // 瞄准一个「刚从面板底下露出来的」目标，落到新旧哪套几何上都不许漏给下层
            // （评审修正：旧实现拿隐藏层的过期矩形做判定，窗口 resize 后会漏）。
            // 面板没了没有行可选，也不需要再收起——下一帧 layoutSuggestions 自己会收干净。
            return true;
        }

        if (this.isHovered(mouseX, mouseY, this.suggestionPanel.getX(), this.suggestionPanel.getY(),
                this.suggestionPanel.getWidth(), this.suggestionPanel.getHeight())) {
            if (mouseButton == 0) {
                // 行下标先按松手时刻的几何算（正常路径：按下面板在、松手面板也在，
                // 同一茬行直接命中）。面板内容在按下→松手之间被换过一茬（异步新结果
                // 到达）时按几何可能落不进任何一行——那时退回按下沿记录的悬停下标，
                // 用户瞄准的就是那一行。两路都没有才算「点了留白」，只吃掉不选中。
                int index = this.suggestionRowAt(mouseX, mouseY);
                if (index < 0 && pressHoveredRow >= 0) index = pressHoveredRow;
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

    /**
     * 联想遮挡层此刻是否可见（面板本体或收起层任一在场）。
     *
     * <p>给 {@code NCMScreen} 在按下沿拍快照用：触屏时代的松手分发与按下之间隔着
     * 任意长的时间，而联想面板恰恰是每帧都可能消失/变形的层——可见性必须以按下
     * 瞬间为准，否则「按下面板在、松手面板没了」的那一下点击会穿透到底下的歌单条目。
     * 渲染线程专用，只读两个布尔。</p>
     */
    public boolean suggestionLayerVisibleNow() {
        return (this.suggestionsVisible && !this.suggestionPanel.isHidden())
                || !this.suggestionDismissLayer.isHidden();
    }

    /**
     * 按下沿悬停的联想行下标（无悬停返回 -1）。坐标是调用方已换算好的<b>布局轴</b>。
     *
     * <p>与可见性快照同拍：面板内容在按下→松手之间被异步结果换过一茬时，松手时刻的
     * 几何命中会落空——用户瞄准的是按下时看到的那一行，用它兜底，点击才不会「吃了没反应」。</p>
     *
     * <p>坐标纪律（2026-11-04 评审修正）：本方法<b>绝不自己从裸 Mouse 重推坐标</b>——
     * 五棵树的 compat {@code Mouse.getY()} 是底原点 framebuffer 轴（LWJGL2 语义），
     * 自己换算必然翻错方向；必须复用 {@code NCMScreen.mouseClicked} 已经换算好的
     * 布局坐标（{@code mX / WindowAdaptiveScale.correctionX()} 同一条链）。</p>
     */
    public int suggestionHoveredRowAt(double mouseX, double mouseY) {
        if (!this.suggestionsVisible || this.suggestionPanel.isHidden()) return -1;
        return this.suggestionRowAt(mouseX, mouseY);
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
        final NCMScreen screen = NCMScreen.getInstance();
        final MusicPlatform searchPlatform = NeteaseMusicService.getCurrentPlatform();
        final boolean searchPlaylistMode = this.playlistSearchMode;
        this.dismissSuggestions();
        this.searchField.setFocused(false);

        this.playlistPanel.getChildren().forEach(child -> {
            if (child instanceof PlaylistItem) {
                ((PlaylistItem) child).setSelected(false);
            }
        });
        invalidateSelectionCache();

        final boolean bilibiliSearch = searchPlatform == MusicPlatform.BILIBILI;
        final boolean bodianSearch = searchPlatform == MusicPlatform.BODIAN;
        final boolean spotifySearch = searchPlatform == MusicPlatform.SPOTIFY;
        final boolean appleSearch = searchPlatform == MusicPlatform.APPLE;
        final boolean searchPlaylists = searchPlaylistMode
                && (searchPlatform == MusicPlatform.NETEASE || searchPlatform == MusicPlatform.KUGOU
                || searchPlatform == MusicPlatform.QISHUI || bilibiliSearch || bodianSearch || spotifySearch
                || appleSearch);
        if (searchPlaylists) {
            final long ticket = screen.beginContentRequest();
            final boolean kugouSearch = searchPlatform == MusicPlatform.KUGOU;
            final boolean qishuiSearch = searchPlatform == MusicPlatform.QISHUI;
            MultiThreadingUtil.runAsync(() -> {
                List<PlayList> results = bodianSearch
                        ? BodianMusicService.searchPlaylists(query, 50)
                        : bilibiliSearch
                        // B 站没有歌单库这个对象：搜的是稿件，再把其中带合集或分 P 数 >1 的筛成歌单。
                        ? BilibiliPlaylistService.searchPlaylists(query, 30)
                        : qishuiSearch
                        ? QishuiMusicService.searchPlaylists(query, 50)
                        : kugouSearch ? KuGouMusicService.searchPlaylists(query, 50)
                        : spotifySearch
                        // Spotify 的公开歌单也走关键词搜索；本人歌单与喜欢的歌另在发现页列出。
                        ? spotifyPlaylistSearch(query)
                        : appleSearch
                        // Apple 的 catalog 公开歌单走 AMP 关键词搜索；资料库歌单另在发现页列出。
                        ? applePlaylistSearch(query)
                        : searchNeteasePlaylists(query);
                String title = (bodianSearch ? "波点歌单搜索" : bilibiliSearch ? "B 站合集 / 分P搜索"
                        : qishuiSearch ? "汽水歌单搜索" : kugouSearch ? "酷狗歌单搜索"
                        : spotifySearch ? "Spotify 歌单搜索" : appleSearch ? "Apple 歌单搜索"
                        : "歌单搜索")
                        + " · " + query + " · " + results.size();
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!screen.isContentRequestCurrent(ticket)
                            || NeteaseMusicService.getCurrentPlatform() != searchPlatform) return;
                    NCMScreen.getInstance().setCurrentPanel(new HomePanel(results, title));
                });
            });
        } else {
            // 保留原有的歌曲搜索流程及其独立的临时播放列表。
            PlayList playList = JsonUtils.parse("{}", PlayList.class);
            playList.setSearchMode(true);
            // 只有搜索框产生的列表才走"起播后接最近播放"，发现页复用 searchMode 的那些列表不受影响。
            playList.setSearchResultList(true);
            playList.musics = new CopyOnWriteArrayList<>();
            PlaylistPanel panel = new PlaylistPanel(playList);
            screen.setCurrentPanel(panel);
            final long ticket = screen.beginContentRequest();
            MultiThreadingUtil.runAsync(() -> {
                List<Music> search = CloudMusic.search(query);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!screen.isContentRequestCurrent(ticket)
                            || NeteaseMusicService.getCurrentPlatform() != searchPlatform) return;
                    playList.musics.clear();
                    playList.musics.addAll(search);
                    panel.onInit();
                });
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

    /**
     * 删掉一张自建本地单（右键触发）。和上面那条的区别只在代价：导入的单子这里断的只是一个引用，
     * 本地单的曲目本来就只存在这份 JSON 里，删了就真没了。正打开着的那页不受影响 —— 它握着的是
     * {@link PlayList} 对象本身，曲目列表挂在对象上，不在这张表里也照样能放完。
     */
    private void removeLocalPlaylist(PlayList playlist) {
        LocalPlaylistStore.remove(playlist);
        NCMScreen.getInstance().markDirty();
        DownloadDynamicIsland.showModelOperationResult("本地歌单",
                "已删除「" + playlist.getName() + "」及其 "
                        + (playlist.getMusics() == null ? 0 : playlist.getMusics().size()) + " 首曲目", true);
    }

    /** 列表里当前被选中的条目，没有选中项时返回 {@code null}。 */
    private PlaylistItem findSelectedItem() {
        // 选中态只在 selectCurrentPanel / 行点击 / 搜索重置这几个离散时刻变化（各
        // setSelected 写点都会调 invalidateSelectionCache）。原来每帧全列表扫描，
        // 歌单多的账号（几百行）就是每帧几百次 instanceof。
        if (!this.selectionCacheDirty) return this.cachedSelectedItem;
        this.cachedSelectedItem = findSelectedItemSlow();
        this.selectionCacheDirty = false;
        return this.cachedSelectedItem;
    }

    private PlaylistItem findSelectedItemSlow() {
        for (AbstractWidget<?> child : this.playlistPanel.getChildren()) {
            if (child instanceof PlaylistItem && ((PlaylistItem) child).isSelected()) {
                return (PlaylistItem) child;
            }
        }
        return null;
    }

    /** 任何写选中态的地方都必须调它，让下一次 findSelectedItem 重新扫一遍。 */
    void invalidateSelectionCache() {
        this.selectionCacheDirty = true;
    }

    /**
     * 重新选中代表当前可见内容面板的侧边栏项。
     *
     * <p>不能只按面板类判断：排行榜、电台、热搜和最近播放会复用 HomePanel / DiscoveryPanel，
     * 但用户切的是不同栏目。语义键优先，旧的类型匹配只作为没有键的历史/外部面板退路。</p>
     */
    public void selectCurrentPanel(NCMPanel panel) {
        PlaylistItem selectedItem = null;
        String navigationKey = panel == null ? null : panel.getNavigationKey();
        if (navigationKey != null) {
            AbstractWidget<?> entry = this.sidebarEntries.get(navigationKey);
            if (entry instanceof PlaylistItem) {
                selectedItem = (PlaylistItem) entry;
            }
        }
        if (selectedItem == null && panel instanceof HomePanel) {
            // 只有真正的默认主页才能直接落到“主页”。固定结果页（排行榜/电台/搜索）由语义键负责，
            // 没有键时宁可不高亮，也不要误把结果页画成主页。
            if (((HomePanel) panel).isDefaultHomePage()) {
                selectedItem = this.homeItem;
            }
        } else if (selectedItem == null && panel instanceof PlaylistPanel) {
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
        invalidateSelectionCache();
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
                highlight.setRadius(SPlayerStyle.RADIUS_MD);
                highlight.setColor(SPlayerStyle.onSurface());
                highlight.setAlpha(this.isActive() ? .10f : 0f);
            });

            LabelWidget kind = new LabelWidget(this::kindText, FontManager.pf14);
            this.addChild(kind);
            kind.setClickable(false);
            kind.setBeforeRenderCallback(() -> {
                kind.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                kind.centerVertically();
                kind.setPosition(Math.max(0, this.getWidth() - kind.getWidth() - 5), kind.getRelativeY());
            });

            LabelWidget name = new LabelWidget(this::nameText, FontManager.pf16);
            this.addChild(name);
            name.setClickable(false);
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setBeforeRenderCallback(() -> {
                name.setColor(SPlayerStyle.onSurface());
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

    // ── SPlayer 外观：侧栏 Logo、分组标题、顶栏 ───────────────────────────────

    /** 侧栏列表的上沿：Logo 行（{@code h-16}）之下。 */
    private double sidebarListTop() {
        return Math.min(SPlayerStyle.LOGO_HEIGHT, this.getHeight() * .2);
    }

    /** 侧栏列表的下沿留白（{@code pb-3}）。 */
    private static double sidebarListBottomInset() {
        return SPlayerStyle.px(12);
    }

    /** 顶栏图层。由 {@link NCMScreen} 摆位、渲染并分发输入，见 {@link HeaderLayer}。 */
    public HeaderLayer getHeaderLayer() {
        return this.headerLayer;
    }

    /** 回到默认主页；已经在默认主页时不重建（与「主页」那一行同一口径）。 */
    private static void goHome() {
        NCMScreen screen = NCMScreen.getInstance();
        NCMPanel current = screen.getCurrentPanel();
        if (current instanceof HomePanel && ((HomePanel) current).isDefaultHomePage()) return;
        screen.setCurrentPanel(new HomePanel());
    }

    /**
     * 侧栏顶端的 Logo 行（SPlayer {@code SideBarLogo}）：圆形标记 + 主色字标，悬停微放大，点击回主页。
     * 标记是自己画的矢量（主色 30% 圆盘 + lucide music），不借用 SPlayer 的品牌标志。
     */
    private final class LogoRow extends Panel {
        private float hover;

        private LogoRow() {
            this.setShouldOverrideMouseCursor(true);
            this.setBeforeRenderCallback(() -> {
                this.setBounds(NavigateBar.this.getWidth(), sidebarListTop());
                this.setPosition(0, 0);
            });
            this.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                goHome();
                return true;
            });
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.hover = SPlayerStyle.approach(this.hover, this.isHovering() ? 1f : 0f);
            float alpha = this.getAlpha();
            CFontRenderer font = FontManager.pf24bold;
            String word = "MuoniumPlayer";
            double mark = SPlayerStyle.px(30);
            double gap = SPlayerStyle.px(8);
            double scale = 1.0 + .05 * this.hover;
            double groupWidth = (mark + gap + font.getStringWidthD(word)) * scale;
            double maxWidth = this.getWidth() - SPlayerStyle.px(16) * 2;
            boolean wordVisible = groupWidth <= maxWidth + 1;
            double centerY = this.getY() + this.getHeight() * .5;
            double startX = this.getX() + (this.getWidth() - (wordVisible ? groupWidth : mark * scale)) * .5;
            int primary = SPlayerStyle.primary();
            double markCenterX = startX + mark * scale * .5;
            SPlayerStyle.circle(markCenterX, centerY, mark * scale * .5, SPlayerStyle.a(primary, .30, alpha));
            SPlayerIcons.draw(SPlayerIcons.MUSIC, markCenterX, centerY, mark * scale * .55,
                    SPlayerStyle.a(primary, 1.0, alpha));
            if (wordVisible) {
                SkijaGfx.pushMatrix();
                try {
                    double textX = startX + (mark + gap) * scale;
                    SkijaGfx.translate(textX, centerY);
                    SkijaGfx.scale(scale, scale);
                    font.drawString(word, 0, SPlayerStyle.centeredTextY(font, 0), SPlayerStyle.a(primary, 1.0, alpha));
                } finally {
                    SkijaGfx.popMatrix();
                }
            }
        }
    }

    /**
     * 侧栏分组标题（SPlayer 侧栏分组头：{@code min-h-10 px-3 text-sm text-on-surface-variant/70}），
     * 组与组之间一条 {@code bg-on-surface/10} 的分隔线；可带一颗行尾圆形小按钮（「＋」新建）。
     */
    private static final class SectionHeader extends Panel {
        private final String title;
        private final boolean divider;
        private SPlayerButton action;

        private SectionHeader(String title, boolean divider) {
            this.title = title;
            this.divider = divider;
            this.setClickable(false);
            this.setBeforeRenderCallback(() -> {
                double inset = SPlayerStyle.px(12);
                this.setBounds(Math.max(1, this.getParentWidth() - inset * 2), this.headerHeight());
                this.setPosition(inset, this.getRelativeY());
            });
        }

        private double headerHeight() {
            return SPlayerStyle.px(40) + (this.divider ? SPlayerStyle.px(9) : 0);
        }

        private SectionHeader withAction(String icon, Runnable run) {
            this.setClickable(true);
            this.action = SPlayerButton.icon(icon, SPlayerButton.Variant.TERTIARY).iconSize(SPlayerStyle.px(14));
            this.action.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                run.run();
                return true;
            });
            this.action.setBeforeRenderCallback(() -> {
                double size = SPlayerStyle.px(26);
                double rowTop = this.divider ? SPlayerStyle.px(9) : 0;
                this.action.setBounds(this.getWidth() - size - SPlayerStyle.px(4),
                        rowTop + (SPlayerStyle.px(40) - size) * .5, size, size);
            });
            this.addChild(this.action);
            return this;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            double rowTop = this.getY();
            if (this.divider) {
                double lineY = this.getY() + SPlayerStyle.px(4);
                SPlayerStyle.line(this.getX() + SPlayerStyle.px(4), lineY,
                        this.getX() + this.getWidth() - SPlayerStyle.px(4), lineY,
                        SPlayerStyle.px(1), SPlayerStyle.a(SPlayerStyle.onSurface(), .10, alpha));
                rowTop += SPlayerStyle.px(9);
            }
            SPlayerStyle.text(SPlayerStyle.textSm(false), this.title, this.getX() + SPlayerStyle.px(12),
                    rowTop + SPlayerStyle.px(20), this.getWidth() - SPlayerStyle.px(48),
                    SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .70, alpha));
        }
    }

    /** 刷新账号歌单是否在进行：全局只有一份，侧栏重建后新顶栏照样知道。 */
    private static volatile boolean refreshSpinning;

    /**
     * SPlayer 顶栏（{@code NavHeader}）：左侧后退/前进、搜索框、音源胶囊、（有未读时）公告；
     * 右侧（有任务时）下载、账号胶囊、设置。设置是一枚下拉菜单，收着原来侧栏底部那排快捷按钮
     * （主题、调色、字体、播放器大小、刷新歌单、公告、下载）。
     *
     * <p>它<b>不是</b>侧栏的子控件：{@link NCMScreen} 把它摆在内容区正上方、画在内容页之后
     * （搜索联想要压住内容区），并在侧栏之前给它分发点击与按键。搜索框与联想面板的状态仍然
     * 归侧栏所有 —— 搜索逻辑只有一份，这里只是换了它们的挂载点。</p>
     */
    public final class HeaderLayer extends Panel {
        private boolean menuOpen;
        private float menuReveal;
        private double lastMouseX = -1e6;
        private double lastMouseY = -1e6;
        private SPlayerButton settingsButton;
        private final List<MenuEntry> menuEntries = new ArrayList<>();

        private HeaderLayer(HeaderLayer previous) {
            if (previous != null) {
                this.menuOpen = previous.menuOpen;
                this.menuReveal = previous.menuReveal;
            }
        }

        /** 当前按钮边长（SPlayer 40px；顶栏被压矮时跟着收）。 */
        private double buttonSize() {
            return Math.max(SPlayerStyle.px(24), Math.min(SPlayerStyle.px(40), this.getHeight() - SPlayerStyle.px(16)));
        }

        private double rowTop() {
            return (this.getHeight() - this.buttonSize()) * .5;
        }

        void setSpinning(boolean spinning) {
            refreshSpinning = spinning;
        }

        private void build() {
            final double pad = SPlayerStyle.px(12);
            final double gap = SPlayerStyle.px(12);

            SPlayerButton back = SPlayerButton.icon(SPlayerIcons.CHEVRON_LEFT, SPlayerButton.Variant.TERTIARY);
            back.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                NCMScreen.getInstance().navigateBack();
                return true;
            });
            back.setBeforeRenderCallback(() -> back.setBounds(pad, this.rowTop(), this.buttonSize(), this.buttonSize()));
            this.addChild(back);

            SPlayerButton forward = SPlayerButton.icon(SPlayerIcons.CHEVRON_RIGHT, SPlayerButton.Variant.TERTIARY);
            forward.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                NCMScreen.getInstance().navigateForward();
                return true;
            });
            forward.setBeforeRenderCallback(() -> forward.setBounds(
                    back.getRelativeX() + back.getWidth() + SPlayerStyle.px(8), this.rowTop(),
                    this.buttonSize(), this.buttonSize()));
            this.addChild(forward);

            final SearchBox searchBox = new SearchBox();
            searchBox.setBeforeRenderCallback(() -> {
                double left = forward.getRelativeX() + forward.getWidth() + gap;
                double available = this.getWidth() - left - this.rightClusterWidth() - this.sourcePillWidth()
                        - gap * 3 - pad;
                double width = Math.max(SPlayerStyle.px(140), Math.min(SPlayerStyle.px(280), available));
                searchBox.setBounds(left, this.rowTop(), width, this.buttonSize());
                NavigateBar.this.searchBarBottom = searchBox.getRelativeY() + searchBox.getHeight();
                NavigateBar.this.searchBoxLeftValue = searchBox.getRelativeX();
                NavigateBar.this.searchBoxWidthValue = searchBox.getWidth();
            });
            this.addChild(searchBox);
            searchBox.build();

            SourcePill source = new SourcePill();
            source.setBeforeRenderCallback(() -> source.setBounds(
                    searchBox.getRelativeX() + searchBox.getWidth() + gap, this.rowTop(),
                    this.sourcePillWidth(), this.buttonSize()));
            this.addChild(source);
            if (NavigateBar.this.sourceChanged) {
                SidebarLayoutMotion sourceMotion = new SidebarLayoutMotion(null);
                source.setAlpha(0f);
                source.setTransformations(() -> {
                    sourceMotion.tick(System.currentTimeMillis(), 0.0, 600.0 / SheetClock.speed());
                    source.setAlpha(sourceMotion.alpha());
                    SharedConstants.api.getGLStateManager().translate(0.0, sourceMotion.offset() * .5, 0.0);
                    if (sourceMotion.isFinished()) source.setTransformations(null);
                });
            }

            // 有未读公告时才出现，位置同 SPlayer 顶栏里那颗「有更新」按钮：紧跟在搜索区后面。
            SPlayerButton announcement = SPlayerButton.icon(SPlayerIcons.MEGAPHONE, SPlayerButton.Variant.TERTIARY)
                    .badge(AnnouncementChecker::hasUnread);
            announcement.setTooltip("公告");
            announcement.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                NCMScreen.getInstance().openAnnouncements();
                return true;
            });
            announcement.setBeforeRenderCallback(() -> announcement.setBounds(
                    source.getRelativeX() + source.getWidth() + gap, this.rowTop(),
                    this.buttonSize(), this.buttonSize()));
            this.addChild(announcement);

            this.settingsButton = SPlayerButton.icon(SPlayerIcons.SETTINGS, SPlayerButton.Variant.TERTIARY)
                    .active(() -> this.menuOpen)
                    .badge(() -> AnnouncementChecker.hasUnread() || refreshSpinning);
            this.settingsButton.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                this.menuOpen = !this.menuOpen;
                return true;
            });
            this.settingsButton.setBeforeRenderCallback(() -> this.settingsButton.setBounds(
                    this.getWidth() - pad - this.buttonSize(), this.rowTop(), this.buttonSize(), this.buttonSize()));
            this.addChild(this.settingsButton);

            AccountPill account = new AccountPill();
            account.setBeforeRenderCallback(() -> account.setBounds(
                    this.settingsButton.getRelativeX() - gap - account.preferredWidth(), this.rowTop(),
                    account.preferredWidth(), this.buttonSize()));
            this.addChild(account);

            SPlayerButton downloads = SPlayerButton.icon(SPlayerIcons.DOWNLOAD, SPlayerButton.Variant.TERTIARY)
                    .badge(() -> DownloadCatalog.activeTasks() > 0 || DownloadCatalog.waitingTasks() > 0);
            downloads.setTooltip("下载管理");
            downloads.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                NCMScreen.getInstance().openDownloadManager();
                return true;
            });
            downloads.setBeforeRenderCallback(() -> downloads.setBounds(
                    account.getRelativeX() - gap - this.buttonSize(), this.rowTop(),
                    this.buttonSize(), this.buttonSize()));
            this.addChild(downloads);

            // 显隐由父组件驱动：被 setHidden 的控件整帧跳过自己的回调，没法把自己再显示回来。
            this.setBeforeRenderCallback(() -> {
                announcement.setHidden(!AnnouncementChecker.hasUnread());
                downloads.setHidden(!DownloadCatalog.shouldShowEntry());
                NavigateBar.this.layoutSuggestions();
            });

            this.buildMenu();
        }

        /** 右侧那一簇（下载、账号、设置）占多宽，搜索框按剩下的宽度收。 */
        private double rightClusterWidth() {
            double gap = SPlayerStyle.px(12);
            double width = this.buttonSize() + gap + AccountPill.preferredWidthFor(this.buttonSize());
            if (DownloadCatalog.shouldShowEntry()) width += gap + this.buttonSize();
            if (AnnouncementChecker.hasUnread()) width += gap + this.buttonSize();
            return width;
        }

        private double sourcePillWidth() {
            CFontRenderer font = SPlayerStyle.textSm(false);
            return SPlayerStyle.px(12) + SPlayerStyle.px(16) + SPlayerStyle.px(6)
                    + font.getStringWidthD(NeteaseMusicService.getCurrentPlatform().getDisplayName())
                    + SPlayerStyle.px(6) + SPlayerStyle.px(12) + SPlayerStyle.px(12);
        }

        private void buildMenu() {
            this.menuEntries.clear();
            this.menuEntries.add(new MenuEntry(SPlayerIcons.PALETTE, () -> "切换主题 · " + NCMTheme.getCurrentName(),
                    () -> {
                        NCMScreen.getInstance().cycleThemeFrom(
                                this.settingsButton.getX() + this.settingsButton.getWidth() * .5,
                                this.settingsButton.getY() + this.settingsButton.getHeight() * .5);
                        DownloadDynamicIsland.showTheme(NCMTheme.getCurrentName());
                    }, false));
            this.menuEntries.add(new MenuEntry(SPlayerIcons.PIPETTE, () -> "主题调色板",
                    () -> NCMScreen.getInstance().openThemePalette(), false));
            this.menuEntries.add(new MenuEntry(SPlayerIcons.TYPOGRAPHY, () -> "界面字体",
                    () -> NCMScreen.getInstance().openFontPicker(), false));
            this.menuEntries.add(new MenuEntry("scaling",
                    () -> "播放器大小 · " + NCMPlayerConfig.getPlayerScalePercent() + "%",
                    () -> {
                        NCMPlayerConfig.cycleScale();
                        DownloadDynamicIsland.showPlayerScale(NCMPlayerConfig.getPlayerScalePercent());
                    }, false));
            this.menuEntries.add(new MenuEntry(SPlayerIcons.REFRESH,
                    () -> refreshSpinning ? "正在刷新歌单…" : "刷新账号歌单",
                    () -> triggerSidebarRefresh(this), false));
            this.menuEntries.add(new MenuEntry(SPlayerIcons.MEGAPHONE, () -> "公告",
                    () -> NCMScreen.getInstance().openAnnouncements(), true));
            this.menuEntries.add(new MenuEntry(SPlayerIcons.DOWNLOAD, () -> "下载管理",
                    () -> NCMScreen.getInstance().openDownloadManager(), false));
        }

        private double menuItemHeight() {
            return SPlayerStyle.px(32);
        }

        private double menuWidth() {
            CFontRenderer font = SPlayerStyle.textSm(false);
            double widest = 0;
            for (MenuEntry entry : this.menuEntries) {
                widest = Math.max(widest, font.getStringWidthD(entry.label.get()));
            }
            return Math.max(SPlayerStyle.px(176), widest + SPlayerStyle.px(14) + SPlayerStyle.px(8) * 2
                    + SPlayerStyle.px(8) + SPlayerStyle.px(4) * 2 + SPlayerStyle.px(10));
        }

        private double menuHeight() {
            int separators = 0;
            for (MenuEntry entry : this.menuEntries) if (entry.separatorBefore) separators++;
            return SPlayerStyle.px(4) * 2 + this.menuEntries.size() * this.menuItemHeight()
                    + separators * SPlayerStyle.px(5);
        }

        /** 菜单左上角（绝对坐标）：右缘对齐设置按钮右缘，挂在按钮下方 6px。 */
        private double menuX() {
            return this.settingsButton.getX() + this.settingsButton.getWidth() - this.menuWidth();
        }

        private double menuY() {
            return this.settingsButton.getY() + this.settingsButton.getHeight() + SPlayerStyle.px(6);
        }

        /** 命中的菜单项下标；没命中返回 -1。 */
        private int menuEntryAt(double mouseX, double mouseY) {
            double x = this.menuX();
            double y = this.menuY() + SPlayerStyle.px(4);
            double width = this.menuWidth();
            for (int index = 0; index < this.menuEntries.size(); index++) {
                MenuEntry entry = this.menuEntries.get(index);
                if (entry.separatorBefore) y += SPlayerStyle.px(5);
                if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY < y + this.menuItemHeight()) {
                    return index;
                }
                y += this.menuItemHeight();
            }
            return -1;
        }

        /**
         * 设置菜单打开时，整块界面上的点击都先归它：点中菜单项就执行并收起，点在别处只收起菜单
         * （SPlayer 的下拉菜单是模态的，点外面不会顺带触发底下的控件）。
         */
        public boolean consumeOpenMenuClick(double mouseX, double mouseY, int mouseButton) {
            if (!this.menuOpen) return false;
            int index = this.menuEntryAt(mouseX, mouseY);
            this.menuOpen = false;
            if (index >= 0 && mouseButton == 0) {
                this.menuEntries.get(index).action.run();
            }
            return true;
        }

        @Override
        public boolean onKeyTyped(char character, int keyCode) {
            if (this.menuOpen && keyCode == Keyboard.KEY_ESCAPE) {
                this.menuOpen = false;
                return true;
            }
            return super.onKeyTyped(character, keyCode);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
        }

        @Override
        protected void afterRenderChildren(double mouseX, double mouseY) {
            this.menuReveal = SPlayerStyle.approach(this.menuReveal, this.menuOpen ? 1f : 0f);
            if (this.menuReveal <= .01f || this.settingsButton == null) return;
            float alpha = this.getAlpha() * this.menuReveal;
            double x = this.menuX();
            double y = this.menuY() + SPlayerStyle.px(6) * (1f - this.menuReveal);
            double width = this.menuWidth();
            double height = this.menuHeight();
            // shadow-lg：GPU 上的高斯遮罩，不是预渲染位图。
            Canvas canvas = SkijaGfx.canvas();
            if (canvas != null) {
                canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH((float) x, (float) (y + SPlayerStyle.px(4)),
                        (float) width, (float) height, (float) SPlayerStyle.RADIUS_LG),
                        SkijaGfx.fillBlurred(SPlayerStyle.a(0x000000, .35, alpha), (float) SPlayerStyle.px(10)));
            }
            SPlayerStyle.fill(x, y, width, height, SPlayerStyle.RADIUS_LG, SPlayerStyle.a(SPlayerStyle.bright(), 1.0, alpha));
            SPlayerStyle.stroke(x, y, width, height, SPlayerStyle.RADIUS_LG, SPlayerStyle.px(1),
                    SPlayerStyle.a(SPlayerStyle.onSurface(), .08, alpha));
            int hovered = this.menuOpen ? this.menuEntryAt(this.lastMouseX, this.lastMouseY - SPlayerStyle.px(6) * (1f - this.menuReveal)) : -1;
            CFontRenderer font = SPlayerStyle.textSm(false);
            double rowY = y + SPlayerStyle.px(4);
            double inner = SPlayerStyle.px(4);
            for (int index = 0; index < this.menuEntries.size(); index++) {
                MenuEntry entry = this.menuEntries.get(index);
                if (entry.separatorBefore) {
                    double lineY = rowY + SPlayerStyle.px(2.5);
                    SPlayerStyle.line(x + SPlayerStyle.px(6), lineY, x + width - SPlayerStyle.px(6), lineY,
                            SPlayerStyle.px(1), SPlayerStyle.a(SPlayerStyle.onSurface(), .10, alpha));
                    rowY += SPlayerStyle.px(5);
                }
                double itemHeight = this.menuItemHeight();
                if (index == hovered) {
                    SPlayerStyle.fill(x + inner, rowY, width - inner * 2, itemHeight, SPlayerStyle.RADIUS_MD,
                            SPlayerStyle.a(SPlayerStyle.onSurface(), .12, alpha));
                }
                double centerY = rowY + itemHeight * .5;
                double iconX = x + inner + SPlayerStyle.px(8) + SPlayerStyle.px(7);
                boolean spinning = SPlayerIcons.REFRESH.equals(entry.icon) && refreshSpinning;
                if (spinning) {
                    SkijaGfx.pushMatrix();
                    try {
                        SkijaGfx.translate(iconX, centerY);
                        SkijaGfx.rotateZ((float) ((System.currentTimeMillis() % 900L) / 900.0 * 360.0));
                        SPlayerIcons.draw(entry.icon, 0, 0, SPlayerStyle.px(14), SPlayerStyle.a(SPlayerStyle.onSurface(), .6, alpha));
                    } finally {
                        SkijaGfx.popMatrix();
                    }
                } else {
                    SPlayerIcons.draw(entry.icon, iconX, centerY, SPlayerStyle.px(14),
                            SPlayerStyle.a(SPlayerStyle.onSurface(), .6, alpha));
                }
                double textX = iconX + SPlayerStyle.px(7) + SPlayerStyle.px(8);
                SPlayerStyle.text(font, entry.label.get(), textX, centerY, x + width - inner - textX - SPlayerStyle.px(14),
                        SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
                if (SPlayerIcons.MEGAPHONE.equals(entry.icon) && AnnouncementChecker.hasUnread()) {
                    SPlayerStyle.circle(x + width - inner - SPlayerStyle.px(12), centerY, SPlayerStyle.px(3.5),
                            SPlayerStyle.a(0xE05B66, 1.0, alpha));
                }
                rowY += itemHeight;
            }
        }

        /**
         * 搜索框（SPlayer {@code NavSearch}）：{@code h-10 rounded-full}，静止 {@code bg-on-surface/3 +
         * border-on-surface/15}，悬停底色 /10、边 /25；聚焦时边变主色并外扩一圈 {@code ring-primary/20}。
         * 里面装的是侧栏那份跨重建复用的输入框和「歌曲/歌单」切换。
         */
        private final class SearchBox extends Panel {
            private float hover;
            private float focus;
            private SegmentControl searchMode;
            private double modeWidth;

            private void build() {
                final SegmentControl.Spec spec = new SegmentControl.Spec();
                spec.inset = SPlayerStyle.px(3);
                spec.durationMs = 240.0;
                spec.font = FontManager.pf12bold;
                final double label = Math.max(FontManager.pf12bold.getStringWidthD("歌曲"),
                        FontManager.pf12bold.getStringWidthD("歌单"));
                this.modeWidth = 2 * (label + SPlayerStyle.px(14)) + spec.inset * 2;
                this.searchMode = SegmentControl.attach(this, () -> {
                    spec.height = Math.max(1, this.getHeight() - SPlayerStyle.px(10));
                    spec.radius = spec.height * .5;
                    spec.width = this.modeWidth;
                    spec.y = (this.getHeight() - spec.height) * .5;
                    spec.x = Math.max(0, this.getWidth() - this.modeWidth - SPlayerStyle.px(5));
                    return spec;
                }, () -> playlistSearchMode ? 1 : 0,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        new String[]{"", ""}, new String[]{"歌曲", "歌单"},
                        index -> {
                            playlistSearchMode = index == 1;
                            return true;
                        });

                this.addChild(searchField);
                searchField.setBeforeRenderCallback(() -> {
                    searchField.drawUnderline(false);
                    this.searchMode.setEnabled(supportsPlaylistSearch(NeteaseMusicService.getCurrentPlatform()));
                    double left = SPlayerStyle.px(16) + SPlayerStyle.px(16) + SPlayerStyle.px(8);
                    double reserve = this.modeWidth * this.searchMode.presence() + SPlayerStyle.px(8);
                    searchField.setBounds(left, 0, Math.max(1.0, this.getWidth() - left - reserve), this.getHeight());
                    searchField.setPlaceholder(supportsPlaylistSearch(NeteaseMusicService.getCurrentPlatform())
                            && this.searchMode.progress() > .5 ? "搜索歌单…" : "搜索歌曲、歌手或专辑");
                    searchField.setColor(SPlayerStyle.onSurface());
                    searchField.setDisabledTextColor(SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .40));
                });
                searchField.setOnKeyTypedCallback(NavigateBar.this::onSearchFieldKey);
            }

            @Override
            public void onRender(double mouseX, double mouseY) {
                boolean focused = searchField.isFocused();
                boolean hovered = this.isHovered(mouseX, mouseY, this.getX(), this.getY(), this.getWidth(), this.getHeight());
                this.hover = SPlayerStyle.approach(this.hover, hovered ? 1f : 0f);
                this.focus = SPlayerStyle.approach(this.focus, focused ? 1f : 0f);
                float alpha = this.getAlpha();
                double x = this.getX();
                double y = this.getY();
                double width = this.getWidth();
                double height = this.getHeight();
                double radius = height * .5;
                int onSurface = SPlayerStyle.onSurface();
                int primary = SPlayerStyle.primary();
                if (this.focus > 0f) {
                    double ring = SPlayerStyle.px(2);
                    SPlayerStyle.stroke(x - ring, y - ring, width + ring * 2, height + ring * 2, radius + ring,
                            ring, SPlayerStyle.a(primary, .20 * this.focus, alpha));
                }
                double fillLevel = .03 + .07 * Math.max(this.hover * (1f - this.focus), 0f) + .05 * this.focus;
                SPlayerStyle.fill(x, y, width, height, radius, SPlayerStyle.a(onSurface, fillLevel, alpha));
                int border = SPlayerStyle.mix(onSurface, primary, this.focus);
                double borderLevel = (.15 + .10 * this.hover) * (1f - this.focus) + this.focus;
                SPlayerStyle.stroke(x, y, width, height, radius, SPlayerStyle.px(1), SPlayerStyle.a(border, borderLevel, alpha));
                SPlayerIcons.draw(SPlayerIcons.SEARCH, x + SPlayerStyle.px(16) + SPlayerStyle.px(8), y + height * .5,
                        SPlayerStyle.px(16), SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .5, alpha));
            }
        }

        /** 音源胶囊：品牌标识 + 平台名 + 下拉箭头，点开音乐来源管理（SPlayer 顶栏的同位按钮是听歌识曲）。 */
        private final class SourcePill extends Panel {
            private float hover;
            private final AbstractWidget<?> brand;

            private SourcePill() {
                this.setShouldOverrideMouseCursor(true);
                MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
                ThemedTextureIconWidget appIcon = AppStoreBrandIcons.widget(platform,
                        sourceGlyph(platform), sourceFont(platform), 0, 0, SOURCE_ICON_BOX);
                this.brand = appIcon != null ? appIcon : BrandVectorIcons.has(platform)
                        ? new BrandIconWidget(platform, SOURCE_ICON_BOX)
                        : new LabelWidget(sourceGlyph(platform), sourceFont(platform));
                this.brand.setClickable(false);
                final boolean texture = appIcon != null;
                this.brand.setBeforeRenderCallback(() -> {
                    this.brand.setColor(texture ? 0xFFFFFFFF : platform.getBrandColor());
                    this.brand.setPosition(SPlayerStyle.px(12) + SPlayerStyle.px(8) - this.brand.getWidth() * .5,
                            this.getHeight() * .5 - this.brand.getHeight() * .5 + (texture ? 0 : .5));
                });
                this.addChild(this.brand);
                this.setOnClickCallback((x, y, button) -> {
                    if (button != 0) return false;
                    NCMScreen.getInstance().openMusicSourceManager(SheetHeroRelay.from(this.getX(), this.getY(),
                            this.getWidth(), this.getHeight(), this.getHeight() * .5,
                            () -> NeteaseMusicService.getCurrentPlatform().getBrandColor()));
                    return true;
                });
            }

            @Override
            public void onRender(double mouseX, double mouseY) {
                this.hover = SPlayerStyle.approach(this.hover, this.isHovering() ? 1f : 0f);
                float alpha = this.getAlpha();
                double x = this.getX();
                double y = this.getY();
                double height = this.getHeight();
                int onSurface = SPlayerStyle.onSurface();
                SPlayerStyle.fill(x, y, this.getWidth(), height, height * .5,
                        SPlayerStyle.a(onSurface, .05 + .05 * this.hover, alpha));
                CFontRenderer font = SPlayerStyle.textSm(false);
                double textX = x + SPlayerStyle.px(12) + SPlayerStyle.px(16) + SPlayerStyle.px(6);
                String name = NeteaseMusicService.getCurrentPlatform().getDisplayName();
                SPlayerStyle.text(font, name, textX, y + height * .5, 0, SPlayerStyle.a(onSurface, 1.0, alpha));
                SPlayerIcons.draw(SPlayerIcons.CHEVRON_DOWN,
                        textX + font.getStringWidthD(name) + SPlayerStyle.px(6) + SPlayerStyle.px(6), y + height * .5,
                        SPlayerStyle.px(12), SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), 1.0, alpha));
            }
        }
    }

    /** 设置菜单的一项。 */
    private static final class MenuEntry {
        final String icon;
        final Supplier<String> label;
        final Runnable action;
        final boolean separatorBefore;

        MenuEntry(String icon, Supplier<String> label, Runnable action, boolean separatorBefore) {
            this.icon = icon;
            this.label = label;
            this.action = action;
            this.separatorBefore = separatorBefore;
        }
    }

    /**
     * 账号胶囊（SPlayer {@code NavUser}）：{@code h-10 pl-1 pr-3 rounded-full bg-on-surface/6}，
     * 头像 {@code size-8} 圆形、昵称 {@code text-sm}（最多 7rem）、会员标、{@code size-3} 下拉箭头。
     * 点开账号管理。
     */
    private static final class AccountPill extends Panel {
        private float hover;
        private final RoundedImageWidget avatar;

        private AccountPill() {
            this.setShouldOverrideMouseCursor(true);
            this.avatar = new RoundedImageWidget(NeteaseMusicService::getAccountAvatarLocation, 0, 0, 0, 0);
            this.avatar.fadeIn();
            this.avatar.setClickable(false);
            this.avatar.setLinearFilter(true);
            this.avatar.setBeforeRenderCallback(() -> {
                double size = this.getHeight() - SPlayerStyle.px(8);
                this.avatar.setBounds(SPlayerStyle.px(4), SPlayerStyle.px(4), size, size);
                this.avatar.setRadius(size * .5);
            });
            this.addChild(this.avatar);
            AccountAvatar.textureOrNull();
            this.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                NCMScreen.getInstance().openAccountManager(SheetHeroRelay.from(this.getX(), this.getY(),
                        this.getWidth(), this.getHeight(), this.getHeight() * .5,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT)));
                return true;
            });
        }

        private static double nameMaxWidth() {
            return SPlayerStyle.px(112);
        }

        static double preferredWidthFor(double height) {
            CFontRenderer font = SPlayerStyle.textSm(false);
            String name = accountName();
            double width = SPlayerStyle.px(4) + (height - SPlayerStyle.px(8)) + SPlayerStyle.px(8)
                    + Math.min(nameMaxWidth(), font.getStringWidthD(name));
            String badge = membershipBadgeText();
            if (badge != null) width += SPlayerStyle.px(8) + vipWidth(badge);
            return width + SPlayerStyle.px(8) + SPlayerStyle.px(12) + SPlayerStyle.px(12);
        }

        double preferredWidth() {
            return preferredWidthFor(this.getHeight() > 0 ? this.getHeight() : SPlayerStyle.px(40));
        }

        private static double vipWidth(String badge) {
            return FontManager.pf12bold.getStringWidthD(badge) + SPlayerStyle.px(12);
        }

        private static String accountName() {
            String name = NeteaseMusicService.getAccountName(NeteaseMusicService.getCurrentPlatform());
            return name == null || name.trim().isEmpty() ? "未登录" : name;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.hover = SPlayerStyle.approach(this.hover, this.isHovering() ? 1f : 0f);
            float alpha = this.getAlpha();
            double x = this.getX();
            double y = this.getY();
            double height = this.getHeight();
            int onSurface = SPlayerStyle.onSurface();
            SPlayerStyle.fill(x, y, this.getWidth(), height, height * .5,
                    SPlayerStyle.a(onSurface, .06 + .04 * this.hover, alpha));
            double avatarSize = height - SPlayerStyle.px(8);
            double avatarCenterX = x + SPlayerStyle.px(4) + avatarSize * .5;
            SPlayerStyle.circle(avatarCenterX, y + height * .5, avatarSize * .5, SPlayerStyle.a(onSurface, .10, alpha));
            if (NeteaseMusicService.getAccountAvatarLocation() == null) {
                SPlayerIcons.draw(SPlayerIcons.USER_ROUND, avatarCenterX, y + height * .5, SPlayerStyle.px(16),
                        SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), 1.0, alpha));
            }
            CFontRenderer font = SPlayerStyle.textSm(false);
            String name = accountName();
            double cursor = x + SPlayerStyle.px(4) + avatarSize + SPlayerStyle.px(8);
            double nameWidth = Math.min(nameMaxWidth(), font.getStringWidthD(name));
            SPlayerStyle.text(font, name, cursor, y + height * .5, nameWidth + .5, SPlayerStyle.a(onSurface, 1.0, alpha));
            cursor += nameWidth;
            String badge = membershipBadgeText();
            if (badge != null) {
                cursor += SPlayerStyle.px(8);
                double badgeWidth = vipWidth(badge);
                double badgeHeight = SPlayerStyle.px(16);
                SPlayerStyle.fill(cursor, y + (height - badgeHeight) * .5, badgeWidth, badgeHeight, badgeHeight * .5,
                        SPlayerStyle.a(NcmSheet.VIP_COLOR, .18, alpha));
                SPlayerStyle.text(FontManager.pf12bold, badge, cursor + SPlayerStyle.px(6), y + height * .5, 0,
                        SPlayerStyle.a(NcmSheet.VIP_COLOR, 1.0, alpha));
                cursor += badgeWidth;
            }
            SPlayerIcons.draw(SPlayerIcons.CHEVRON_DOWN, cursor + SPlayerStyle.px(8) + SPlayerStyle.px(6), y + height * .5,
                    SPlayerStyle.px(12), SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), 1.0, alpha));
        }
    }

    /** 搜索框按键：Esc 先收联想再退焦点；上下键挑联想；Tab 补全；回车提交。原侧栏搜索框的语义原样保留。 */
    private boolean onSearchFieldKey(char character, int keyCode) {
        if (!this.searchField.isFocused()) return false;
        if (keyCode == Keyboard.KEY_ESCAPE) {
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
            String completion = this.highlightedSuggestionText();
            if (!completion.isEmpty()) {
                this.searchField.setText(completion);
                this.searchField.getTextField().setCursorPositionEnd();
                this.suggestHighlight = -1;
                return true;
            }
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            String highlighted = this.highlightedSuggestionText();
            if (!highlighted.isEmpty()) {
                this.searchField.setText(highlighted);
            }
            final String keyword = this.searchField.getText() == null ? "" : this.searchField.getText().trim();
            this.submitSearch(keyword);
        }
        return true;
    }

    /**
     * 侧栏的一行（SPlayer {@code SMenu} 菜单项）。
     *
     * <p>外观：{@code h-10.5 px-3 gap-3 rounded-lg text-sm}，静止 {@code text-on-surface/80}、
     * 悬停 {@code bg-on-surface/5}；选中态文字与图标变主色，底色与左侧竖条由侧栏共享的那一条
     * 高亮（{@link NavigateBar#selectionIndicator}）画，切换时才能滑过去。歌单行带封面时是
     * {@code h-13}，图标位换成 {@code size-9 rounded-md} 的封面。</p>
     */
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
         * 进场。侧栏是整树重建出来的（{@code NCMScreen#markDirty} → {@code new NavigateBar()}），
         * 所以「这一行是新来的」只能由 {@link NavigateBar#addSidebarRow} 按行签名判出来，判中了
         * 才调 {@link #armEntrance}。动作本身复用曲目行、首页卡片那同一套 {@link PanelItemEntrance}：
         * 从下方 6 逻辑像素落进来 + .97→1 缩放 + 淡入，280ms，逐行错峰。
         *
         * <p>位移与缩放全走矩阵，行的真实边界一动不动 —— 侧栏的高亮条、滚动位置、点击判定都按
         * 落位后的几何算，进场期间不会跟着抖。</p>
         */
        private final PanelItemEntrance entrance = new PanelItemEntrance();
        private PanelItemEntrance.RevealQueue entranceQueue;
        private double entranceEased = 1.0;
        /** 退场时要钉住的那一格（上一次渲染留下的绝对 y），见 {@link NavigateBar#retireRows}。 */
        private double retireAbsY;
        private float retireAlpha;
        /** 退场进度（0 = 还在原位画满，1 = 已经让开）：淡出与左移共用这一条，见 {@link NavigateBar#advanceRetiringRows}。 */
        private double retireOut;

        /**
         * 可选的右键（副键）动作。默认不设置时右键沿用旧行为——被吞掉、什么都不做。
         * 目前只由「导入的汽水歌单」条目用来移除本地引用。
         */
        @Getter
        @Setter
        private Runnable onSecondaryClick;

        /** 这一行用的 lucide 图标；null = 没有对应的矢量图标，退回原来的字形/贴图图标。 */
        private String splayerIcon;
        /** 「点＋新建」「登录后加载」这类提示行：没有图标，文字压暗一档。 */
        private final boolean hint;
        private float hover;
        private boolean coverRequested;

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

        private static PlaylistItem withLucideIcon(String icon, Supplier<Integer> iconColorSupplier,
                                                   Supplier<String> label, Runnable onClick) {
            return new PlaylistItem(null, FontManager.music18, null, iconColorSupplier, label, onClick, icon);
        }

        private PlaylistItem(String icon, CFontRenderer iconFont, Location iconLocation,
                             Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this(icon, iconFont, iconLocation, iconColorSupplier, label, onClick, null);
        }

        private PlaylistItem(String icon, CFontRenderer iconFont, Location iconLocation,
                             Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick,
                             String lucideIcon) {
            this.icon = icon;
            this.iconLocation = iconLocation;
            this.iconColorSupplier = iconColorSupplier;
            this.label = label;
            this.onClick = onClick;
            this.hint = "·".equals(icon);
            this.splayerIcon = splayerIconFor(icon, lucideIcon);
            this.setBeforeRenderCallback(() -> {
                this.updateEntrance();
                // 列表容器左右各让 px-3：行与 SPlayer 一样不顶到侧栏边。播放器缩放时按实时宽度扣。
                double inset = SPlayerStyle.px(12);
                this.setBounds(Math.max(1, this.getParentWidth() - inset * 2), this.rowHeight());
                this.setPosition(inset, this.getRelativeY());
            });

            // 选中背景由侧栏共享的那一条高亮条绘制（见 NavigateBar#layoutSelectionIndicator）。
            // 每个条目各画一块的话，切换栏目只能是一块消失、另一块出现，没有位移可动画。

            RoundedImageWidget cover = new RoundedImageWidget(
                    () -> this.showsCover() ? this.playlist.getCoverLocation() : null, 0, 0, 0, 0);
            cover.fadeIn();
            cover.setLinearFilter(true);
            cover.setClickable(false);
            cover.setBeforeRenderCallback(() -> {
                double size = SPlayerStyle.px(36);
                cover.setBounds(SPlayerStyle.px(12), (this.getHeight() - size) * .5, size, size);
                cover.setRadius(SPlayerStyle.RADIUS_MD);
                if (this.showsCover()) this.requestCover();
            });
            this.addChild(cover);

            // 没有对应 lucide 图标的入口（品牌字形、贴图图标）保留原来的图标控件。
            if (this.splayerIcon == null && !this.hint) {
                if (iconLocation != null) {
                    ThemedTextureIconWidget textureIcon = new ThemedTextureIconWidget(iconLocation, FontManager.music18,
                            0, 0, 12, 12);
                    this.addChild(textureIcon);
                    textureIcon.setBeforeRenderCallback(() -> {
                        textureIcon.setHidden(this.showsCover());
                        textureIcon.setColor(this.inkColor());
                        textureIcon.setPosition(SPlayerStyle.px(22) - textureIcon.getWidth() * .5,
                                this.getHeight() * .5 - textureIcon.getHeight() * .5);
                    });
                    textureIcon.setClickable(false);
                } else if (icon != null) {
                    LabelWidget glyphIcon = new LabelWidget(icon, iconFont == null ? FontManager.music18 : iconFont);
                    this.addChild(glyphIcon);
                    glyphIcon.setBeforeRenderCallback(() -> {
                        glyphIcon.setHidden(this.showsCover());
                        glyphIcon.setColor(this.inkColor());
                        glyphIcon.setPosition(SPlayerStyle.px(22) - glyphIcon.getWidth() * .5,
                                this.getHeight() * .5 - glyphIcon.getHeight() * .5);
                    });
                    glyphIcon.setClickable(false);
                }
            }

            this.setOnClickCallback(((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    this.selected = true;

                    this.onClick.run();

                    NCMScreen.getInstance().getPlaylistsPanel().playlistPanel.getChildren().stream()
                            .filter(it -> it instanceof PlaylistItem && it != this)
                            .forEach(it -> ((PlaylistItem) it).setSelected(false));
                    // 行点击改了选中态：高亮条的缓存下一帧要重扫。PlaylistItem 是 static
                    // 嵌套类，拿不到外层 this，走 PlaylistsPanel 持有的 NavigateBar 引用。
                    NCMScreen.getInstance().getPlaylistsPanel().invalidateSelectionCache();
                } else if (mouseButton == 1 && this.onSecondaryClick != null) {
                    this.onSecondaryClick.run();
                }

                return true;
            }));
        }

        /** 指定这一行的 lucide 图标（同一个旧字形在不同入口含义不同时用，如「最近播放」）。 */
        PlaylistItem withSPlayerIcon(String lucide) {
            this.splayerIcon = lucide;
            return this;
        }

        /**
         * 旧字形 → SPlayer 用的 lucide 图标。旧侧栏用 music 字体里的字母当图标（A 主页、D 歌单、
         * C 我喜欢、K 搜索/热搜），汉字当徽记（台、每、控）。
         */
        private static String splayerIconFor(String glyph, String lucide) {
            if (lucide != null) {
                if (FoliaLucideIcons.ALBUM.equals(lucide)) return SPlayerIcons.DISC;
                if (FoliaLucideIcons.CLOUD_DOWNLOAD.equals(lucide)) return SPlayerIcons.CLOUD;
                return SPlayerIcons.has(lucide) ? lucide : null;
            }
            if (glyph == null) return null;
            switch (glyph) {
                case "A": return SPlayerIcons.HOME;
                case "D": return SPlayerIcons.LIST_MUSIC;
                case "C": return SPlayerIcons.HEART;
                case "K": return SPlayerIcons.FLAME;
                case "台": return SPlayerIcons.PODCAST;
                case "每": return SPlayerIcons.CALENDAR_DAYS;
                case "控": return "cast";
                case "+": return SPlayerIcons.PLUS;
                default: break;
            }
            if (glyph.equals(FontelloIcons.LEADERBOARD)) return SPlayerIcons.TROPHY;
            if (glyph.equals(MusicBrandIcons.PERSONAL_FM)) return SPlayerIcons.RADIO;
            return null;
        }

        /** 歌单行有封面地址时按封面行画（SPlayer 侧栏「显示歌单封面」）。 */
        private boolean showsCover() {
            PlayList list = this.playlist;
            return list != null && list.getCoverUrl() != null && !list.getCoverUrl().trim().isEmpty();
        }

        private double rowHeight() {
            return this.showsCover() ? SPlayerStyle.NAV_COVER_ROW_HEIGHT : SPlayerStyle.NAV_ROW_HEIGHT;
        }

        /** 文字与图标同色：选中主色，平时 onSurface/80，提示行再压暗。 */
        private int inkColor() {
            if (this.selected) return SPlayerStyle.a(SPlayerStyle.primary(), 1.0, this.getAlpha());
            return SPlayerStyle.a(SPlayerStyle.onSurface(), this.hint ? .55 : .80, this.getAlpha());
        }

        /**
         * 封面只请求一次，口径与主页歌单卡片一致（{@code HomePanel} 的封面加载）：同一张封面
         * 落在同一个贴图位置，主页先下过的这里直接复用，反之亦然。
         */
        private void requestCover() {
            if (this.coverRequested) return;
            PlayList list = this.playlist;
            Location location = list.getCoverLocation();
            this.coverRequested = true;
            if (TextureManager.getInstance().getTexture(location) != null) return;
            String secureUrl = list.getCoverUrl().replace("http://", "https://");
            String requestUrl = list.getPlatform() == MusicPlatform.QQ || list.getPlatform() == MusicPlatform.QISHUI
                    || list.isSearchMode() ? secureUrl : secureUrl + "?param=256y256";
            Textures.downloadTextureAndLoadAsync(requestUrl, location);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.hover = SPlayerStyle.approach(this.hover, this.isHovering() ? 1f : 0f);
            float alpha = this.getAlpha();
            double x = this.getX();
            double y = this.getY();
            double width = this.getWidth();
            double height = this.getHeight();
            double centerY = y + height * .5;
            if (!this.selected && this.hover > 0f) {
                SPlayerStyle.fill(x, y, width, height, SPlayerStyle.RADIUS_LG,
                        SPlayerStyle.a(SPlayerStyle.onSurface(), .05 * this.hover, alpha));
            }
            int ink = this.inkColor();
            double left = x + SPlayerStyle.px(12);
            double textX;
            if (this.showsCover()) {
                double size = SPlayerStyle.px(36);
                if (TextureManager.getInstance().getTexture(this.playlist.getCoverLocation()) == null) {
                    SPlayerStyle.fill(left, centerY - size * .5, size, size, SPlayerStyle.RADIUS_MD,
                            SPlayerStyle.a(SPlayerStyle.onSurface(), .08, alpha));
                    SPlayerIcons.draw(SPlayerIcons.LIST_MUSIC, left + size * .5, centerY, size * .45,
                            SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .6, alpha));
                }
                textX = left + size + SPlayerStyle.px(12);
            } else if (this.hint) {
                textX = left;
            } else {
                double iconBox = SPlayerStyle.px(20);
                if (this.splayerIcon != null) {
                    SPlayerIcons.draw(this.splayerIcon, left + iconBox * .5, centerY, iconBox, ink);
                }
                textX = left + iconBox + SPlayerStyle.px(12);
            }
            String text;
            try {
                text = this.label.get();
            } catch (RuntimeException error) {
                // 标签来自异步快照（账号名、歌单名），一次读失败不能带崩整列侧栏；下一帧再读。
                text = "";
            }
            SPlayerStyle.text(SPlayerStyle.textSm(false), text, textX, centerY,
                    x + width - SPlayerStyle.px(12) - textX, ink);
        }

        /**
         * 装上一次性进场。只有被 {@link NavigateBar#addSidebarRow} 判定为「上一次没有的这一行」才会
         * 走到这里 —— 常驻行装了进场，异步数据落地那一刻整列会重亮一遍，那是闪不是入场。
         */
        PlaylistItem armEntrance(PanelItemEntrance.RevealQueue queue) {
            this.entranceQueue = queue;
            this.entranceEased = 0.0;
            this.setAlpha(0f);
            this.setTransformations(() -> PanelItemEntrance.applyTransform(this.entranceEased,
                    this.getX() + this.getWidth() * .5, this.getY() + this.getHeight() * .5));
            return this;
        }

        private void updateEntrance() {
            if (this.entranceQueue == null) return;
            if (!this.entrance.isStarted()) {
                this.entrance.begin(System.currentTimeMillis(), this.entranceQueue.nextDelayMillis());
            }
            if (this.entrance.isFinished()) return;

            this.entranceEased = this.entrance.tick();
            this.setAlpha((float) this.entranceEased);
            if (this.entrance.isFinished()) {
                // 演完就把变换摘掉：侧栏是常驻的一列，不该为已经停住的行一直付一次 pushMatrix。
                this.setTransformations(null);
            }
        }

    }

    /**
     * 调用文档中记载的网易云搜索歌单接口（type 1000）。
     */
    /** Spotify 公开歌单的关键词搜索，映射成本播放器的歌单卡片。 */
    private List<PlayList> spotifyPlaylistSearch(String keyword) {
        List<PlayList> results = new ArrayList<>();
        for (com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.Playlist playlist
                : com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.searchPlaylists(keyword, 30)) {
            PlayList card = com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.toPlayListCard(playlist);
            if (card != null) results.add(card);
        }
        return results;
    }

    /** Apple 公开歌单（catalog）的关键词搜索，映射成本播放器的歌单卡片。 */
    private List<PlayList> applePlaylistSearch(String keyword) {
        List<PlayList> results = new ArrayList<>();
        for (com.muoniumplayer.core.ncm.apple.AppleMusicService.Playlist playlist
                : com.muoniumplayer.core.ncm.apple.AppleMusicService.searchPlaylists(keyword, 25)) {
            PlayList card = com.muoniumplayer.core.ncm.apple.AppleMusicService.toPlayListCard(playlist);
            if (card != null) results.add(card);
        }
        return results;
    }

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
