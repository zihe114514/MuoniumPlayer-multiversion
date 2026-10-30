package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.mc.Gui;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.mc.GuiTextField;
import com.muoniumplayer.compat.mc.ScaledResolution;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.gl.GL11;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.muoniumplayer.render.skija.SkijaClip;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;

/**
 * HUD 设置界面（内置）。
 *
 * <p>条目全部来自 {@link HudSettingsModel}，OneConfig 桥也从同一份声明构建，所以不会出现
 * 「某一边少一项」。呈现是「侧边栏分类 + 右侧卡片分组 + 每行带说明」的双栏布局，也就是
 * Windows 11 设置、macOS 系统设置与 VS Code 设置面板共同的那套结构：每屏只需要滚一小段，
 * 而且每一项都能带一句「这到底影响什么」。</p>
 *
 * <p>三个刻意的工程约束，改动这个类之前请先读完：</p>
 * <ol>
 *   <li><b>渲染与命中测试共用同一份行矩形列表。</b>每帧先 {@link #layoutRows} 把当前可见的行算成
 *   {@link Row}，之后画图与鼠标判定都只读它。绘制一套坐标、{@code mouseClicked} 里再抄
 *   一套是最容易出错的做法（加一行忘了同步偏移，点击就会错位到相邻项），这里不允许。</li>
 *   <li><b>面板矩形只能从 {@link #panelBounds()} 拿。</b>面板可以被拖动、可以折叠、入场时还带一段
 *   位移动画，位置不再是一个常量表达式。任何地方自己算一遍都会在动画中的那几帧和真实位置错开。</li>
 *   <li><b>动画只允许改「颜色 / 透明度 / 偏移」，不允许改命中矩形。</b>唯一的例外是面板位移与滚动，
 *   它们都被折进 {@code panelBounds()} 与 {@link #displayScroll}，绘制与命中读到的是同一个值。
 *   特别地，入场动画不用 {@code glScale} —— {@code GL_SCISSOR} 是屏幕坐标，缩放会让裁剪区错位。</li>
 * </ol>
 *
 * <p>面板默认靠右摆放：屏幕左侧要留给两块 HUD 的实时预览与拖拽。
 * 拖动标题栏可以把它挪到任意位置（会被记进配置），双击标题栏或按标题栏上的复位按钮回到默认位置。
 * 屏幕太窄时面板会自动收窄，也可以用折叠按钮把它收成一条，露出下面的 HUD。</p>
 */
public class GuiHudEditorPro extends GuiScreen {

    // ==================== 面板尺寸 ====================

    private static final int PANEL_MARGIN = 12;
    private static final int PANEL_MAX_W = 604;
    private static final int PANEL_MAX_H = 492;
    private static final int HEADER_H = 52;
    private static final int FOOTER_H = 34;
    private static final int NAV_W = 156;
    private static final int NAV_ROW_H = 36;
    /** 侧栏行高被压到这一档以下就不再硬挤，改为侧栏内部滚动（矮窗口下每类仍然可点）。 */
    private static final int NAV_ROW_MIN_H = 28;
    private static final int COLLAPSED_H = 46;

    private static final int CONTENT_PAD = 14;
    private static final int SEARCH_H = 22;
    private static final int GROUP_HEADER_H = 24;
    private static final int ROW_H = 44;
    /** 说明折成两行的条目行高。 */
    private static final int ROW_H_TWO_LINES = 54;
    private static final int CARD_GAP = 8;
    private static final int CARD_RADIUS = 10;
    private static final int EMPTY_STATE_H = 78;

    private static final int TRACK_W = 116;
    private static final int VALUE_W = 48;
    private static final int SWITCH_W = 38;
    private static final int SWITCH_H = 20;
    private static final int SWATCH_W = 32;
    private static final int SWATCH_H = 20;

    private static final int THEME_CHIP_SIZE = 22;
    private static final int THEME_CHIP_GAP = 5;

    private static final int PICKER_W = 210;
    private static final int PICKER_H = 190;
    private static final int SV_W = 186;
    private static final int SV_H = 88;
    private static final int HUE_H = 10;
    private static final int HEX_INPUT_W = 86;
    private static final int HEX_INPUT_H = 18;

    /** CHOICE 下拉浮层：条目高（带说明）与窄条目高（只有选项名）。 */
    private static final int DROP_ITEM_H = 34;
    private static final int DROP_ITEM_COMPACT_H = 24;
    private static final int DROP_MAX_VISIBLE = 7;
    private static final int DROP_MIN_W = 168;
    private static final int DROP_MAX_W = 268;
    /** 下拉浮层进场时长：比取色器略快一档，它是「点一下就要看到」的东西。 */
    private static final double DROP_MS = 140.0;
    /** 搜索结果跳转后目标行的高亮脉冲时长。 */
    private static final long FLASH_MS = 900L;

    private static final int INFO_BASE_W = 230;
    private static final int INFO_BASE_H = 56;

    /** 入场时面板从「低 10 像素」升上来。位移量刻意小：大幅度滑入在只有半秒的界面里显得拖沓。 */
    private static final int INTRO_RISE = 10;
    /** 切分类时内容从行进方向那一侧滑入的幅度。只作用于绘制，不动命中矩形。 */
    private static final int CONTENT_SLIDE = 14;
    /** 逐块落位时额外往下掉的几个像素：配合淡入才有「落进来」，而不是一句「淡出来」。 */
    private static final int CONTENT_DROP = 6;

    // ==================== 动效时长 ====================
    //
    // 这一页以前所有通道都是 {@code Interpolations.interpolate}：那是「每帧走剩下的
    // 几成」，没有「这段动画多长」这个概念，也完全不理玩家的「界面切换动画速度」那一档。
    // 现在统一成定长补间（{@link #motion}），时长一律除以 {@link SheetClock#speed()} —— 和换页、
    // 浮层、分段滑块、侧栏高亮条走同一个旋钮。默认 3.0 档下这些数字实际都被除了 3。

    /** 悬停：跟手的那一档，太长会显得按钮黏。 */
    private static final double HOVER_MS = 150.0;
    /** 开关旋钮走完一次行程。 */
    private static final double KNOB_MS = 200.0;
    /** 搜索框聚焦（底色 + 下划线 + 光标）。 */
    private static final double FOCUS_MS = 160.0;
    /**
     * 侧栏高亮条换行的行程。播放器侧栏那一族用的是同一个数字同一条款曲线（越过目标再收回来），
     * 这一页和它是同一个应用里的同一套动效语言，不该漂成两档。
     */
    private static final double INDICATOR_MS = 420.0;
    /** 确认框与取色器这类「凭空出现的一层」的到场时长。 */
    private static final double POP_MS = 170.0;

    /**
     * 内容逐块落位的窗口排布：起点、间距、单块行程、并档上限。
     *
     * <p>照 {@code NcmSheet} 那一族的卡位比例（页眉之后开始、每档 4% 行程 50%），只是这一页的
     * 「块」是一张卡而不是一个浮层。超过上限的序号并到最后一档一起到场 —— 宁可少一点错峰，
     * 也不能让长列表底部的卡永远排在窗口外面。</p>
     */
    private static final double ROW_FIRST = .26;
    private static final double ROW_STEP = .04;
    private static final double ROW_TRAVEL = .54;
    private static final int ROW_STAGGER_LIMIT = 12;
    /** 侧栏行的错峰起点比内容列早半档：这一列是「导航」，它先站好，内容再落。 */
    private static final double NAV_FIRST = .12;
    private static final double NAV_STEP = .035;
    private static final double NAV_TRAVEL = .40;
    private static final int NAV_STAGGER_LIMIT = 8;
    /** 页眉与页脚各自的时间窗（在 {@link #page} 这条 t 上开的洞）。 */
    private static final double HEADER_FIRST = .04;
    private static final double HEADER_TRAVEL = .40;
    private static final double FOOTER_FIRST = .52;
    /** 底板（含阴影与那圈高光边）走完自己那一段的时机：整条 t 的前这么多。 */
    private static final double PLATE_TRAVEL = .34;

    /** 双击标题栏复位的判定窗口（毫秒）。 */
    private static final long DOUBLE_CLICK_MS = 320L;

    // ==================== 配色 ====================

    private static final int PANEL_BG = 0xF2141922;
    private static final int PANEL_EDGE = 0x24FFFFFF;
    private static final int NAV_BG = 0x22000000;
    private static final int NAV_HOVER = 0x14FFFFFF;
    private static final int NAV_SELECTED = 0x22FFFFFF;
    private static final int CARD_BG = 0x12FFFFFF;
    private static final int CARD_EDGE = 0x0EFFFFFF;
    private static final int ROW_HOVER = 0x1AFFFFFF;
    private static final int TEXT_PRIMARY = 0xFFF2F5FA;
    private static final int TEXT_SECONDARY = 0xFF98A3B4;
    private static final int TEXT_TERTIARY = 0xFF6E7A8C;
    private static final int TRACK_BG = 0x36FFFFFF;
    private static final int SWITCH_OFF = 0x33FFFFFF;
    private static final int BUTTON_BG = 0x1FFFFFFF;
    private static final int BUTTON_HOVER = 0x38FFFFFF;

    // ==================== 一行的几何 ====================

    /** 一条可见的行。分组标题与条目共用这个结构，{@code entry == null} 即为分组标题或空态。 */
    private static final class Row {
        final HudSettingsModel.Entry entry;
        final String title;
        /** 分组标题左侧的图标码位；条目行没有图标，为 {@code null}。 */
        final String icon;
        final int accent;
        final int x;
        final int y;
        final int w;
        final int h;
        /** 分组里的位置。卡片背景本身由 {@link #cards} 统一画，这里只用来决定要不要画分隔线。 */
        final boolean firstInCard;
        final boolean lastInCard;
        /** 搜索无结果时的那一行：画法完全不同（大图标 + 居中提示）。 */
        final boolean emptyState;
        /** 属于第几张卡 —— 进场时它要和自己的卡同一档到场，不能各走各的。 */
        final int slot;

        Row(HudSettingsModel.Entry entry, String title, String icon, int accent,
            int x, int y, int w, int h, boolean firstInCard, boolean lastInCard, boolean emptyState,
            int slot) {
            this.entry = entry;
            this.title = title;
            this.icon = icon;
            this.accent = accent;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.firstInCard = firstInCard;
            this.lastInCard = lastInCard;
            this.emptyState = emptyState;
            this.slot = slot;
        }

        boolean isGroupHeader() {
            return entry == null;
        }
    }

    /**
     * 一个分组的卡片背景。
     *
     * <p>为什么要单独一层：之前是每行自己画一次满矩形、首尾行再叠一个同色圆角矩形，于是首尾那
     * 20 像素高的带子被画了两遍，半透明白叠加成明显更亮的色块，看起来像渲染残留。卡片一次画完、
     * 行只负责画分隔线与内容，颜色就只叠一层，圆角也不会出现「中间方角、首尾圆角」的割裂感。</p>
     */
    private static final class Card {
        final int x;
        final int y;
        final int w;
        final int h;

        Card(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Card> cards = new ArrayList<Card>();
    private int contentHeight;

    // ==================== 状态 ====================

    private int categoryIndex;
    private int[] scrollByCategory;
    private int searchScroll;
    /** 侧边栏选中指示器的当前 y，向目标插值，切分类时是滑过去而不是跳过去。 */
    private float navIndicator = -1f;
    private boolean collapsed;

    private GuiTextField searchInput;
    private String searchQuery = "";

    /** 当前展开下拉的 CHOICE 条目；{@code null} 即没有展开。按条目身份比较（声明是静态常量）。 */
    private HudSettingsModel.Entry openChoice;
    /** 下拉浮层的到场进度（0..1），每帧无条件推进，与取色器同一条纪律。 */
    private float choiceGate;
    /** 搜索结果跳转后要高亮脉冲的那一行；{@code flashDeadline} 过去后失效。 */
    private HudSettingsModel.Entry flashEntry;
    private long flashDeadline;
    /** 侧栏放不下全部分类时的内部滚动（像素）。 */
    private int navScroll;

    private HudSetting draggingSlider;
    private int draggingSliderTrackX;
    /**
     * 右下角缩放角标。非 null = 正在拖拽（面板尺寸跟着鼠标走）；悬浮时也记下矩形做高亮。
     * 收起态不画也不命中（收起面板只有一条标题栏，没有可缩放的内容区）。
     */
    private boolean resizingPanel;
    private boolean resizeGripHover;
    private int resizePressW;
    private int resizePressH;
    private int resizePressX;
    private int resizePressY;
    /**
     * 滑条数值的「点击直接输入」。非空 = 正在以文本方式编辑该滑条（值文本框已聚焦，
     * 渲染层把那一行画成输入态）；提交/失焦后置回 null。同一时刻最多一个。
     */
    private HudSetting editingSliderValue;
    private GuiTextField sliderValueInput;
    private HudSettingsModel.ColorTarget editingColor = HudSettingsModel.ColorTarget.NONE;
    private float pickerHue;
    private float pickerSaturation;
    private float pickerBrightness;
    private boolean draggingSaturationBrightness;
    private boolean draggingHue;
    private GuiTextField hexColorInput;
    private boolean settingsDirty;
    private boolean resetConfirmationVisible;

    /** 两块叠加层的点选/拖动/边缘缩放/吸附状态机。渲染读它的几何，判定也读它的几何。 */
    private final HudOverlayStage stage = new HudOverlayStage();
    /** 快捷配置小窗的到场进度。 */
    private float popGate;
    /** 快捷小窗里「整体缩放」那条滑条的拖动中标志与轨道原点；歌词宽高滑条复用 draggingSlider。 */
    private boolean draggingPopoverScale;
    private int popoverScaleTrackX;
    /** 选中块的呼吸脉冲与其归属（变了就重起一段）。 */
    private HudOverlayStage.Kind pulsedSelection = HudOverlayStage.Kind.NONE;
    private final Channel selectionPulseChannel = new Channel();

    /** 面板自身的拖动。和两块 HUD 的拖动互斥（面板在最上层，命中它就不会落到 HUD 上）。 */
    private boolean draggingPanel;
    /** 窗口自适应模式临时替换 Screen#width/height 时保存的原值。 */
    private int savedAdaptiveWidth;
    private int savedAdaptiveHeight;
    private int panelDragOffX;
    private int panelDragOffY;
    private long lastHeaderClick;

    // ==================== 动画状态 ====================

    /**
     * 绘制时统一乘上的不透明度。
     *
     * <p>由 {@link #drawRoundedRect}、{@link #drawText} 与 {@link #drawIcon} 直接读，而不是让每个
     * 调用点自己传 —— 面板入场淡入、内容淡入涉及上百个绘制调用，手动传参漏一处就会出现「有一块
     * 不跟着淡入」的破口。用完必须在 {@code finally} 里恢复 1，否则会泄漏到后面的 HUD 预览上。</p>
     */
    private float layerAlpha = 1f;

    /**
     * 外壳时钟：整页进场、退场，以及页眉 / 侧栏 / 页脚在它上面各自开一个时间窗。
     *
     * <p>只有这一条 t。进场没播完就按 ESC 时，各层从自己当前的位置原路退回，不需要另写一套
     * 退场编排 —— 这也是这一页以前做不到的事（它只有一个只会单调逼近 1 的 {@code introProgress}，
     * 所以关闭是「直接没有」）。</p>
     */
    private final SheetClock page = SheetClock.cascade();
    /**
     * 内容列表自己的时钟。换分类、进出搜索都重播它一次，而外壳不跟着重放。
     *
     * <p>为什么不并进 {@link #page}：这一页是个可以待很久的界面，切分类是它最主要的一个动作。
     * 让整页（含侧栏）跟着每次换分类重进场，看起来就像界面被重建了一遍。</p>
     */
    private final SheetClock list = SheetClock.cascade();

    /** 面板这一帧该有多实（0..1）。既做淡入也做位移，位移折进 {@link #panelBounds()} 所以裁剪区跟着走。 */
    private float plate = 1f;
    /**
     * 两条时钟这一帧的<b>原始</b> t。错峰窗口必须开在它上面而不是开在缓动后的进度上：
     * 缓动过的值已经不等间距，按序号平移窗口会让越靠后的块走得越慢。
     */
    private double pageFade;
    private double listFade;

    /**
     * 确认框与取色器这两层「凭空出现的板子」的到场进度。
     *
     * <p>每帧无条件推进（而不是只在要画它的那一帧才第一次见到这个键），否则通道创建时就落在
     * 目标上，永远看不到那 170 毫秒。</p>
     */
    private float dialogGate;
    private float pickerGate;

    private boolean introStarted;

    /**
     * 这一次内容重播从哪一侧进场：{@code +1} 往下一个分类（新的从右边来）、{@code -1} 往上、
     * {@code 0} 首次到场不偏。方向由新旧分类的下标差决定，和播放器换页同一套读法。
     */
    private int listDirection;
    private int contentOwner = Integer.MIN_VALUE;

    /**
     * 定长补间的通道表：键是部件名（悬停、按下、聚焦……），值记住这一段从哪起、到哪去、什么时候开始。
     *
     * <p>表里只会攒下几十个键（按钮 + 侧栏行 + 条目 + 色卡），随界面生命周期一起释放，不需要清理。</p>
     */
    private final Map<String, Channel> channels = new HashMap<String, Channel>();
    /**
     * 条目行的动画状态：{@code [0]} 悬停、{@code [1]} 开关位置、{@code [2]} 滑块把手放大。
     *
     * <p>按 {@code Entry} 的身份存：条目声明是静态常量，身份天然稳定，不需要额外的 id。
     * 三个值各自的 {@link Channel} 存在 {@link #rowChannels} 里 —— 通道要的是「从当前值重新起一段」，
     * 光有数值做不到。</p>
     */
    private final Map<HudSettingsModel.Entry, float[]> rowProgress =
            new IdentityHashMap<HudSettingsModel.Entry, float[]>();
    private final Map<HudSettingsModel.Entry, Channel[]> rowChannels =
            new IdentityHashMap<HudSettingsModel.Entry, Channel[]>();

    /**
     * 平滑滚动用的显示位置。
     *
     * <p>{@link #layoutRows} 用它而不是用 {@link #scroll()} 的整数目标值，滚轮才会是滑过去的。
     * 它只在 {@code drawScreen} 里变，所以 {@code mouseClicked} 读到的是上一帧绘制时的同一个值，
     * 命中与所见依然一致。</p>
     */
    private float displayScroll;
    private int scrollOwner = Integer.MIN_VALUE;

    /** 每个图标字号的 em 方格度量缓存，见 {@link #drawIcon}。 */
    private static final Map<CFontRenderer, GlyphInkMetrics> GRID_CACHE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    // ==================== 生命周期 ====================

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (scrollByCategory == null || scrollByCategory.length != HudSettingsModel.categories().length) {
            scrollByCategory = new int[HudSettingsModel.categories().length];
        }
        // 上次停留在哪个分类就回到哪里：反复调同一页的用户不该每次都从「歌词样式」重新点起。
        categoryIndex = Math.max(0, Math.min(HudSettingsModel.categories().length - 1,
                HudConfig.hudEditorLastCategory));
        ensureSearchInput();
        ensureHexColorInput();
        // initGui 在窗口缩放时也会被调用。入场动画只在真正打开时播一次，否则每次改分辨率都要重新淡入。
        if (!introStarted) {
            introStarted = true;
            page.restart();
            list.restart();
        }
    }

    @Override
    public void onGuiClosed() {
        if (settingsDirty) {
            HudConfig.save();
            settingsDirty = false;
        }
        // 记住停在哪一页。save() 每次全量落盘，这里只有换页时才值得写。
        if (HudConfig.hudEditorLastCategory != clampCategory()) {
            HudConfig.hudEditorLastCategory = clampCategory();
            HudConfig.save();
        }
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================== 主绘制 ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 圆角矩形着色器会关掉 alpha test。这个界面是叠在真实游戏画面上渲染的，任何泄漏的 GL 状态
        // 都会带进 Forge 覆盖层和下一帧世界（树叶/玻璃变透明、计分板发虚）。整段绘制包在一次状态边界里。
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        boolean adaptive = false;
        try {
            RenderSystem.refreshResolution();
            adaptive = beginAdaptiveLayout();
            if (adaptive) {
                mouseX = WindowAdaptiveScale.toLayoutXForced(mouseX);
                mouseY = WindowAdaptiveScale.toLayoutYForced(mouseY);
                // 自适应分支里全部几何（面板 + HUD 预览段）统一按布局轴书写，这里一次性
                // 把布局单位放大到 canvas 的 GUI 逻辑坐标（与 NCMScreen 同方向同因子）：
                // 布局 × correction = GUI 逻辑，再由 canvas 的 guiScale 上屏。于是「每布局
                // 单位的物理像素」= pixel/布局宽，恒定、与 GUI Scale 档位无关。
                SharedConstants.api.getGLStateManager().pushMatrix();
                SharedConstants.api.getGLStateManager().scale(
                        WindowAdaptiveScale.correctionX(), WindowAdaptiveScale.correctionY(), 1);
            }
            CFontRenderer fr = FontManager.pf12;
            if (fr == null) return;

            pageFade = page.tick();
            listFade = list.tick();
            // 底板只占这条 t 的前三分之一：它先落定，侧栏与内容才有「往一块儿聚」的感觉。
            // 退场是同一串窗口的倒放 —— 内容先撤，底板最后走。
            plate = (float) TransitionCurveCache.MOTION.at(
                    SheetClock.window(pageFade, 0.0, PLATE_TRAVEL));
            dialogGate = motion("dialog.in", resetConfirmationVisible ? 1f : 0f, POP_MS);
            pickerGate = motion("picker.in",
                    editingColor != HudSettingsModel.ColorTarget.NONE ? 1f : 0f, POP_MS);
            choiceGate = motion("choice.pop", openChoice != null ? 1f : 0f, DROP_MS);
            popGate = motion("wpop.in", stage.hasSelection() ? 1f : 0f, POP_MS);
            if (page.isGone()) {
                // 退场播完才真的把界面收掉：这一帧只剩一个全透明的面板，画它没有意义。
                closeScreen();
                return;
            }
            // 退场途中这一层已经不再接受任何输入：拖动、滚动、点控件都不该再改状态。
            boolean retiring = page.isDismissing();

            MusicInfoWidget info = MuoniumPlayerExtension.getInstance().musicInfo;
            MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;

            // 面板尺寸与位置无关，先更新拖动再取矩形，这一帧的绘制与命中就都是新位置。
            if (!retiring) updatePanelDrag(mouseX, mouseY, panelWidth(), panelHeight());
            int[] panel = panelBounds();
            int panelX = panel[0];
            int panelY = panel[1];
            int panelW = panel[2];
            int panelH = panel[3];
            // 缩放角标拖拽：改的是尺寸，取矩形前推进，本帧绘制与命中立即用新尺寸。
            if (!retiring) updatePanelResize(mouseX, mouseY, panelX, panelY);

            if (!DownloadDynamicIsland.isDownloadActive()) DownloadDynamicIsland.renderEditorPreview();

            int wheel = Mouse.getDWheel();
            if (retiring || resetConfirmationVisible) wheel = 0;
            boolean overPanel = isInside(mouseX, mouseY, panelX, panelY, panelW, panelH);
            boolean overPicker = editingColor != HudSettingsModel.ColorTarget.NONE
                    && isInside(mouseX, mouseY, pickerX(panelX, panelW), pickerY(panelY, panelH), PICKER_W, PICKER_H);
            boolean overPopover = stage.hasSelection()
                    && isInside(mouseX, mouseY, widgetPopoverX(), widgetPopoverY(),
                            WIDGET_POPOVER_W, widgetPopoverH());
            boolean uiBusy = retiring || resetConfirmationVisible || overPanel || overPicker
                    || overPopover || draggingPanel;

            if (!retiring && !resetConfirmationVisible) {
                updateColorPickerWhileDragging(mouseX, mouseY, panelX, panelY, panelW, panelH);
                updateSliderWhileDragging(mouseX);
            }

            boolean lmb = !retiring && !resetConfirmationVisible && !draggingPanel && Mouse.isButtonDown(0);

            // ==== 两块 HUD 的实时预览与舞台交互（面板之外的区域） ====
            //
            // HUD 预览段与真实渲染同轴：widget 的位置/尺寸走布局轴（getLayoutWidth/Height，
            // 与 GUI Scale 无关），本段矩阵也是布局轴（上面的 correction），两边的「布局
            // 单位 → 物理」换算完全一致 —— 预览框、把手、快捷小窗的命中与所见必然重合。
            // 鼠标已在分支头换算成布局坐标，直接用。
            int hudSpaceWidth = (int) RenderSystem.getLayoutWidth();
            int hudSpaceHeight = (int) RenderSystem.getLayoutHeight();

            int infoW = (int) (INFO_BASE_W * HudConfig.infoScale);
            int infoH = (int) (INFO_BASE_H * HudConfig.infoScale);
            int infoX = (int) (HudConfig.infoX * (hudSpaceWidth - infoW));
            int infoY = (int) (HudConfig.infoY * (hudSpaceHeight - infoH));
            int lyricW = (int) (HudConfig.lyricWidth * HudConfig.lyricScale);
            int lyricH = (int) (HudConfig.lyricHeight * HudConfig.lyricScale);
            int lyricX = (int) (HudConfig.lyricX * (hudSpaceWidth - lyricW));
            int lyricY = (int) (HudConfig.lyricY * (hudSpaceHeight - lyricH));
            int[] infoRect = { infoX, infoY, infoW, infoH };
            int[] lyricRect = { lyricX, lyricY, lyricW, lyricH };
            // 舞台自己判定点选/拖动/缩放并写回 HudConfig；松手有改动时才落一次盘。
            // 鼠标坐标已在上面的 adaptive 分支换算成布局轴，与 rect 同轴。
            boolean stageDirty = stage.update(lmb && !uiBusy, uiBusy ? 0 : wheel, mouseX, mouseY,
                    infoRect, lyricRect, new int[] { INFO_BASE_W, INFO_BASE_H }, hudSpaceWidth, hudSpaceHeight);
            if (stageDirty) HudConfig.save();

            if (hasLiveInfoPreview()) {
                renderLiveInfoPreview(info);
            } else {
                drawInfoPlaceholder(fr, infoX, infoY, infoW, infoH);
            }
            if (hasLiveLyricsPreview()) {
                renderLiveLyricsPreview(lyrics, lyricX, lyricY);
            } else {
                drawLyricsPlaceholder(fr, lyricX, lyricY, lyricW, lyricH);
            }
            drawStageOverlays(fr, infoRect, lyricRect, mouseX, mouseY);

            // ==== 设置面板 ====
            layerAlpha = plate;
            try {
                drawPanel(fr, panelX, panelY, panelW, panelH, mouseX, mouseY, wheel, overPanel);
            } finally {
                layerAlpha = 1f;
            }

            if (editingColor != HudSettingsModel.ColorTarget.NONE) {
                float outer = pushAlpha(pickerGate);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(0f, slideY(pickerGate));
                try {
                    drawColorPicker(fr, panelX, panelY, panelW, panelH, mouseX, mouseY);
                } finally {
                    SkijaGfx.popMatrix();
                    layerAlpha = outer;
                }
            }

            // CHOICE 下拉浮层：画在列表之上、取色器之下。收起的过程中仍按最后一份几何画淡出。
            if (openChoice != null || choiceGate > 0.01f) {
                float outer = pushAlpha(choiceGate);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(0f, slideY(choiceGate));
                try {
                    drawChoiceDropdown(fr, panelX, panelY, panelW, panelH, mouseX, mouseY);
                } finally {
                    SkijaGfx.popMatrix();
                    layerAlpha = outer;
                }
            }

            // 吸附参考线：中心线 / 屏幕边缘 / 两块之间的对齐，由舞台每帧给出。
            for (int[] guide : stage.guides) {
                Gui.drawRect(guide[0], guide[1], guide[0] + guide[2], guide[1] + guide[3], 0x558DDCFF);
            }

            super.drawScreen(mouseX, mouseY, partialTicks);
            if (resetConfirmationVisible) {
                float outer = pushAlpha(dialogGate);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(0f, slideY(dialogGate));
                try {
                    drawResetConfirmation(fr, mouseX, mouseY);
                } finally {
                    SkijaGfx.popMatrix();
                    layerAlpha = outer;
                }
            }

            // 选中叠加层的快捷配置小窗：画在最上层，与它配套的舞台交互在 uiBusy 里已让位。
            if (stage.hasSelection() || popGate > 0.01f) {
                float outer = pushAlpha(popGate);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(0f, slideY(popGate));
                try {
                    drawWidgetPopover(fr, mouseX, mouseY);
                } finally {
                    SkijaGfx.popMatrix();
                    layerAlpha = outer;
                }
            }
        } finally {
            layerAlpha = 1f;
            endScissor();
            if (adaptive) {
                SharedConstants.api.getGLStateManager().popMatrix();
            }
            restoreAdaptiveLayout(adaptive);
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    /** 进入窗口自适应布局：临时把 Screen 的宽高换成由物理窗口推导出的逻辑宽高。 */
    private boolean beginAdaptiveLayout() {
        RenderSystem.refreshResolution();
        // 强制布局轴（不理会「窗口自适应缩放」开关）：编辑器是固定设计界面，且真实 HUD
        // 覆盖层已无条件按布局轴渲染 —— 预览必须同轴，否则开关关闭时预览与实物差一个
        // correction 因子。坐标换算配套用 toLayout*Forced（同样跳过开关闸）。
        WindowAdaptiveScale.beginForced();
        if (!WindowAdaptiveScale.isTransforming()) return false;
        savedAdaptiveWidth = width;
        savedAdaptiveHeight = height;
        width = WindowAdaptiveScale.layoutWidth();
        height = WindowAdaptiveScale.layoutHeight();
        return true;
    }

    private void restoreAdaptiveLayout(boolean active) {
        if (!active) return;
        width = savedAdaptiveWidth;
        height = savedAdaptiveHeight;
        WindowAdaptiveScale.end();
    }

    /**
     * 面板初始尺寸：全设备统一的窗口占比，而不是固定像素。
     *
     * <p>布局空间（{@code min(物理宽,1920)/2} 那条轴）里，一单位的物理大小随设备大幅变化：
     * 1080p 桌面 ≈1 物理像素、4K/高分本 ≈2、手机横屏外接 ≈1.2——固定 604×492 在老笔记本上占
     * 宽 88%、在窗口化小屏上 94%，玩家看到的就是「比例巨大」。占比驱动的尺寸让面板在任何
     * 设备上的初始观感一致：横屏恒占窗口宽的 46%、高的 62%（1080p 桌面 ≈ 880×620 物理像素，
     * 观感克制且和旧版同档）；竖屏（高 &gt; 宽 ×1.3，手机竖持）转窄高形态，占宽 46%、高 86%。
     * 绝对上限/下限仍在：超小窗口不小于最小可用宽度，超大屏不无限铺开。</p>
     */
    private static final int PANEL_MIN_W = 340;
    private static final int PANEL_MIN_H = 320;

    /** 用户拖过缩放角标的尺寸占比上限（窗口宽/高的 88%）：再大内容命中就开始贴屏边。 */
    private static final float PANEL_MAX_SCALE = 0.88f;

    private int panelWidth() {
        boolean portrait = height > width * 1.3f;
        int ideal = (int) (width * 0.46f);
        int cap = portrait ? 460 : PANEL_MAX_W;
        int auto = Math.max(Math.min(PANEL_MIN_W, width - PANEL_MARGIN * 2),
                Math.min(cap, Math.min(ideal, width - PANEL_MARGIN * 2)));
        // 用户拽过角标：按当前窗口折算比例，钳在「最小可用」与「88% 窗口宽」之间。
        // 哨兵 -1 = 没拽过，走公式默认值。
        if (HudConfig.hudEditorPanelScaleW >= 0f) {
            int userW = Math.round(HudConfig.hudEditorPanelScaleW * width);
            return Math.max(Math.min(PANEL_MIN_W, width - PANEL_MARGIN * 2),
                    Math.min(userW, width - PANEL_MARGIN * 2));
        }
        return auto;
    }

    private int panelHeight() {
        if (collapsed) return COLLAPSED_H;
        boolean portrait = height > width * 1.3f;
        if (HudConfig.hudEditorPanelScaleH >= 0f) {
            int userH = Math.round(HudConfig.hudEditorPanelScaleH * height);
            return Math.max(Math.min(PANEL_MIN_H, height - PANEL_MARGIN * 2),
                    Math.min(userH, height - PANEL_MARGIN * 2));
        }
        if (portrait) {
            // 竖屏下面板是纵向长列表，高度跟随窗口（无绝对上限），竖持手机上占高 86%。
            return Math.max(Math.min(PANEL_MIN_H, height - PANEL_MARGIN * 2),
                    Math.min(height * 86 / 100, height - PANEL_MARGIN * 2));
        }
        return Math.max(Math.min(PANEL_MIN_H, height - PANEL_MARGIN * 2),
                Math.min(PANEL_MAX_H, Math.min((int) (height * 0.72f), height - PANEL_MARGIN * 2)));
    }

    /**
     * 面板的当前矩形 {@code [x, y, w, h]}。<b>唯一的位置来源</b>，绘制与命中测试都必须走它。
     *
     * <p>位置有三种情况：没被拖过（{@link HudConfig#hudEditorPanelX} 是哨兵负值）走默认摆位；
     * 拖过就按归一化比例还原；再叠上入场动画的位移。存归一化比例而不是绝对像素，是为了换分辨率后
     * 面板仍在屏内 —— 绝对像素在小窗口下会整块跑到屏幕外，再也点不到。</p>
     */
    private int[] panelBounds() {
        int panelW = panelWidth();
        int panelH = panelHeight();
        int maxX = Math.max(0, width - panelW);
        int maxY = Math.max(0, height - panelH);
        int panelX;
        int panelY;
        if (!panelMoved()) {
            panelX = maxX > PANEL_MARGIN ? maxX - PANEL_MARGIN : maxX;
            panelY = collapsed ? Math.min(maxY, PANEL_MARGIN) : (height - panelH) / 2;
        } else {
            panelX = Math.round(HudConfig.hudEditorPanelX * maxX);
            panelY = Math.round(HudConfig.hudEditorPanelY * maxY);
        }
        panelX = Math.max(0, Math.min(maxX, panelX));
        panelY = Math.max(0, Math.min(maxY, panelY));
        if (plate < 1f) {
            panelY += Math.round((1f - plate) * INTRO_RISE);
        }
        return new int[] { panelX, panelY, panelW, panelH };
    }

    private static boolean panelMoved() {
        return HudConfig.hudEditorPanelX >= 0f && HudConfig.hudEditorPanelY >= 0f;
    }

    /** 标题栏可拖动区的高度。折叠时整个面板就是标题栏。 */
    private int headerHeight(int panelH) {
        return Math.min(collapsed ? COLLAPSED_H : HEADER_H, panelH);
    }

    private void updatePanelDrag(int mouseX, int mouseY, int panelW, int panelH) {
        if (!draggingPanel) return;
        if (!Mouse.isButtonDown(0)) {
            draggingPanel = false;
            HudConfig.save();
            return;
        }
        float[] normalized = normalizePanelPosition(width, height, panelW, panelH,
                mouseX - panelDragOffX, mouseY - panelDragOffY);
        HudConfig.hudEditorPanelX = normalized[0];
        HudConfig.hudEditorPanelY = normalized[1];
    }

    /**
     * 把「想要的左上角」钳到屏内，再归一化成 0..1。
     *
     * <p>先钳位后归一化，面板就永远不可能被拖出屏幕 —— 一旦标题栏出界，用户就再也抓不回来，
     * 只能去改配置文件。抽成静态方法是为了能离线验证这个不变式（拖动路径本身要 LWJGL 的
     * {@code Mouse}，进不了单测）。</p>
     */
    static float[] normalizePanelPosition(int screenW, int screenH, int panelW, int panelH,
                                          int desiredX, int desiredY) {
        int maxX = Math.max(0, screenW - panelW);
        int maxY = Math.max(0, screenH - panelH);
        int nx = Math.max(0, Math.min(maxX, desiredX));
        int ny = Math.max(0, Math.min(maxY, desiredY));
        return new float[] { maxX > 0 ? nx / (float) maxX : 0f, maxY > 0 ? ny / (float) maxY : 0f };
    }

    // ==================== 右下角缩放角标 ====================

    /** 角标的可视/命中大小。命中带往外多 4px，圆角附近的盲区也能抓住。 */
    private static final int GRIP_SIZE = 14;
    private static final int GRIP_HIT_EXPAND = 4;

    /** 缩放角标的矩形（右下角，贴面板内缘）。收起态与入场动画中不提供。 */
    private int[] resizeGripRect(int panelX, int panelY, int panelW, int panelH) {
        if (collapsed || plate < 1f) return null;
        return new int[] { panelX + panelW - GRIP_SIZE - 5,
                panelY + panelH - GRIP_SIZE - 5, GRIP_SIZE, GRIP_SIZE };
    }

    /** 鼠标是否落在角标命中区（矩形外扩 GRIP_HIT_EXPAND）。 */
    private boolean overResizeGrip(int[] grip, int mouseX, int mouseY) {
        return grip != null && isInside(mouseX, mouseY,
                grip[0] - GRIP_HIT_EXPAND, grip[1] - GRIP_HIT_EXPAND,
                grip[2] + GRIP_HIT_EXPAND * 2, grip[3] + GRIP_HIT_EXPAND * 2);
    }

    /**
     * 角标拖拽推进：按住期间把「按下时尺寸 + 鼠标位移」折算成窗口占比写回配置。
     * 钳制链：PANEL_MIN_* 保证最小可用 → 屏幕减边距防出屏 → 88% 窗口占比封顶。
     * 松手（含鼠标丢失的兜底）落一次盘。
     */
    private void updatePanelResize(int mouseX, int mouseY, int panelX, int panelY) {
        if (!resizingPanel) return;
        if (!Mouse.isButtonDown(0)) {
            resizingPanel = false;
            HudConfig.save();
            return;
        }
        int wantW = resizePressW + (mouseX - resizePressX);
        int wantH = resizePressH + (mouseY - resizePressY);
        int maxW = Math.min((int) (width * PANEL_MAX_SCALE), width - PANEL_MARGIN * 2);
        int maxH = Math.min((int) (height * PANEL_MAX_SCALE), height - PANEL_MARGIN * 2);
        wantW = Math.max(Math.min(PANEL_MIN_W, width - PANEL_MARGIN * 2), Math.min(wantW, maxW));
        wantH = Math.max(Math.min(PANEL_MIN_H, height - PANEL_MARGIN * 2), Math.min(wantH, maxH));
        HudConfig.hudEditorPanelScaleW = width > 0 ? wantW / (float) width : -1f;
        HudConfig.hudEditorPanelScaleH = height > 0 ? wantH / (float) height : -1f;
    }

    /** 尺寸读数气泡（拖拽时的 "W × H" 提示），与 HudOverlayStage 的尺寸气泡同语言。 */
    private void drawResizeBubble(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH) {
        String text = panelW + " × " + panelH;
        int w = fr.getStringWidth(text) + 16;
        int x = panelX + panelW - w;
        int y = panelY + panelH - 46;
        drawSoftShadow(x, y, w, 22, 8);
        drawRoundedRect(x, y, w, 22, 8, 0xE8222A38);
        drawText(fr, text, x + 8, y + 7, 0xFFE7ECF4);
    }

    /** 角标本体：三条斜杠把手，悬浮/拖拽中亮起。返回命中矩形供点击判定复用。 */
    private int[] drawResizeGrip(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                                 int mouseX, int mouseY) {
        int[] grip = resizeGripRect(panelX, panelY, panelW, panelH);
        if (grip == null) return null;
        resizeGripHover = resizingPanel || overResizeGrip(grip, mouseX, mouseY);
        int color = resizeGripHover ? 0xCCEAF3FF : 0x55FFFFFF;
        // 三条 45° 斜杠，从角向内排——窗口缩放角标的通用视觉语言。
        for (int i = 0; i < 3; i++) {
            double off = 3.0 + i * 4.0;
            double gx = grip[0] + grip[2] - off;
            double gy = grip[1] + grip[3] - off;
            drawRoundedRect(gx - 1, gy - 6, 2, 7, 1, color);
            drawRoundedRect(gx - 6, gy - 1, 7, 2, 1, color);
        }
        return grip;
    }

    /** 屏幕很窄时侧边栏会挤掉内容列，这时把它收成一条只有图标的窄栏。 */
    private int navWidth(int panelW) {
        return panelW - NAV_W < 210 ? 44 : NAV_W;
    }

    private void drawPanel(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                           int mouseX, int mouseY, int wheel, boolean overPanel) {
        drawSoftShadow(panelX, panelY, panelW, panelH, 16);
        // 真玻璃：先铺一层糊过的游戏画面，再把面板底色按「玻璃浓度」压薄。三套编辑器共用
        // 同一个生效面开关——它们本来就是同一件事的三种画法，分开开关只会出现
        // 「同一台机器上两个设置页长得不一样」。铺不上就一位不改，走原来那层实色底。
        boolean frosted = GlassLook.paintFrost(GlassLook.Surface.EDITORS,
                panelX, panelY, panelW, panelH, 16, layerAlpha);
        drawRoundedRect(panelX, panelY, panelW, panelH, 16,
                frosted ? GlassLook.tintedArgb(GlassLook.Surface.EDITORS, PANEL_BG) : PANEL_BG);
        if (frosted) {
            GlassLook.paintEdge(GlassLook.Surface.EDITORS, panelX, panelY, panelW, panelH, 16, layerAlpha);
        }
        // 顶部一条高光边：纯色圆角矩形在游戏画面上会显得「贴」上去，一道 1px 亮边就有玻璃的厚度感。
        drawRoundedRect(panelX + 6, panelY, panelW - 12, 1, 1, PANEL_EDGE);

        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        HudSettingsModel.Category active = categories[clampCategory()];

        float headerOuter = pushAlpha(gate(HEADER_FIRST, HEADER_TRAVEL));
        try {
            drawHeader(fr, panelX, panelY, panelW, panelH, active, mouseX, mouseY);
        } finally {
            layerAlpha = headerOuter;
        }
        if (collapsed) {
            return;
        }

        int navW = navWidth(panelW);
        int bodyY = panelY + HEADER_H;
        int bodyH = panelH - HEADER_H - FOOTER_H;
        drawNav(fr, panelX, bodyY, navW, bodyH, categories, mouseX, mouseY);

        int contentX = panelX + navW;
        int contentW = panelW - navW;
        // 搜索框是内容列的第一块，它跟着列表那条时钟走，换分类时和底下的卡一起重播。
        float searchOuter = pushAlpha(revealOf(0));
        try {
            drawSearch(fr, contentX, bodyY + 8, contentW, active.accent, mouseX, mouseY);
        } finally {
            layerAlpha = searchOuter;
        }

        int listY = bodyY + 8 + SEARCH_H + 8;
        int listH = Math.max(24, bodyY + bodyH - listY);

        // 切分类或进出搜索时，滚动位置与内容淡入都要重新起算，否则会看到上一个分类的滚动残影。
        int owner = scrollKey();
        if (owner != scrollOwner) {
            scrollOwner = owner;
            displayScroll = scroll();
        }
        if (owner != contentOwner) {
            // 方向从「上一个内容归属」的下标差推出来：往下一个分类走新的从右边来，往上是左边，
            // 进出搜索（那一条 key 是 -1）也走同一条判据。第一次到场没有「上一个」，就直落。
            listDirection = contentOwner == Integer.MIN_VALUE ? 0 : Integer.compare(owner, contentOwner);
            contentOwner = owner;
            list.restart();
        }

        layoutRows(contentX, listY, contentW, active);
        int maxScroll = Math.max(0, contentHeight - listH);
        if (wheel != 0 && overPanel && mouseX < contentX) {
            // 侧栏也吃滚轮：矮窗口下侧栏放不下全部分类时靠它滚动，行高不必再被挤扁。
            navScroll = clampNavScroll(navScroll + (wheel > 0 ? -32 : 32));
        } else if (wheel != 0 && overPanel && mouseX >= contentX) {
            setScroll(scroll() + (wheel > 0 ? -32 : 32), maxScroll);
        } else {
            setScroll(scroll(), maxScroll);
        }
        displayScroll = Interpolations.interpolate(displayScroll, scroll(), 19f);
        if (Math.abs(displayScroll - scroll()) < 0.4f) displayScroll = scroll();
        // 用更新后的 displayScroll 再排一次：本帧画的与下一次点击命中的是同一份矩形。
        layoutRows(contentX, listY, contentW, active);

        beginScissor(contentX, listY, contentW, listH);
        float outerAlpha = layerAlpha;
        try {
            // 一块一块地落位，而不是整列一起淡：卡与它自己的行共用同一个序号，
            // 否则会出现「字已经到了、底下的板还在路上」。
            for (int index = 0; index < cards.size(); index++) {
                Card card = cards.get(index);
                if (card.y + card.h < listY - 4 || card.y > listY + listH + 4) continue;
                float reveal = revealOf(index);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(slideX(reveal), slideY(reveal));
                try {
                    layerAlpha = outerAlpha * reveal;
                    drawRoundedRect(card.x, card.y, card.w, card.h, CARD_RADIUS, CARD_BG);
                    drawRoundedRect(card.x + CARD_RADIUS, card.y, card.w - CARD_RADIUS * 2, 1, 1, CARD_EDGE);
                } finally {
                    SkijaGfx.popMatrix();
                }
            }
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                if (row.y + row.h < listY - 4 || row.y > listY + listH + 4) continue;
                float reveal = revealOf(row.slot);
                float dx = slideX(reveal);
                float dy = slideY(reveal);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(dx, dy);
                try {
                    layerAlpha = outerAlpha * reveal;
                    // 鼠标坐标反向补偿：悬停高亮跟着滑进来的视觉走，命中矩形本身不动。
                    drawRow(fr, row, mouseX - Math.round(dx), mouseY - Math.round(dy));
                } finally {
                    SkijaGfx.popMatrix();
                }
            }
        } finally {
            layerAlpha = outerAlpha;
            endScissor();
        }

        if (maxScroll > 0) {
            int trackTop = listY + 2;
            int trackH = listH - 4;
            int thumbH = Math.max(24, trackH * listH / Math.max(1, contentHeight));
            float thumbY = trackTop + (trackH - thumbH) * clamp01(displayScroll / maxScroll);
            drawRoundedRect(contentX + contentW - 7, trackTop, 3, trackH, 2, 0x22FFFFFF);
            drawRoundedRect(contentX + contentW - 8, thumbY, 5, thumbH, 3, 0x66FFFFFF);
        }

        float footerOuter = pushAlpha(gate(FOOTER_FIRST, 1.0 - FOOTER_FIRST));
        try {
            drawFooter(fr, panelX, panelY + panelH - FOOTER_H, panelW, mouseX, mouseY);
        } finally {
            layerAlpha = footerOuter;
        }

        // 右下角缩放角标：最后画（盖在 footer/滚动条上），拖拽中带尺寸读数气泡。
        int[] grip = drawResizeGrip(fr, panelX, panelY, panelW, panelH, mouseX, mouseY);
        if (resizingPanel && grip != null) {
            drawResizeBubble(fr, panelX, panelY, panelW, panelH);
        }
    }

    // ==================== 头部 / 侧栏 / 搜索 / 底栏 ====================

    private void drawHeader(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                            HudSettingsModel.Category active, int mouseX, int mouseY) {
        int headerH = headerHeight(panelH);
        boolean overHeader = isInside(mouseX, mouseY, panelX, panelY, panelW, headerH);
        float gripGlow = named("header.grip", overHeader || draggingPanel);
        // 把手图标而不是一句「可拖动」提示：标题栏只有 52 像素高，文字挤不进去，
        // 而六点栅格是各家窗口/列表统一的「这里能抓」的记号。
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.DRAG_HANDLE,
                panelX + 15, panelY + headerH / 2.0,
                mix(0x40FFFFFF, 0xA6FFFFFF, gripGlow));

        int titleY = panelY + (collapsed ? 12 : 16);
        drawText(FontManager.pf14bold, "音乐 HUD 设置", panelX + 28, titleY, TEXT_PRIMARY);
        drawText(fr, collapsed ? "已折叠 · 点右上角展开" : "实时预览 · 自动保存 · 拖动标题栏可移动",
                panelX + 28, titleY + 14, TEXT_SECONDARY);

        int[] toggle = collapseButton(panelX, panelY, panelW, panelH);
        drawGhostButton("header.toggle", fr, collapsed ? HudSettingsIcons.EXPAND : HudSettingsIcons.COLLAPSE,
                null, toggle, mouseX, mouseY, TEXT_PRIMARY, active.accent);
        if (collapsed) return;

        if (OneConfigHudBridge.isAvailable()) {
            int[] oneConfig = oneConfigButton(panelX, panelY, panelW, panelH);
            drawGhostButton("header.oneconfig", fr, HudSettingsIcons.MODERN_UI, "OneConfig",
                    oneConfig, mouseX, mouseY, TEXT_PRIMARY, active.accent);
        }

        if (panelMoved()) {
            int[] restore = restorePositionButton(panelX, panelY, panelW, panelH);
            drawGhostButton("header.home", fr, HudSettingsIcons.RESTORE, null,
                    restore, mouseX, mouseY, TEXT_SECONDARY, active.accent);
        }

        drawRoundedRect(panelX + 14, panelY + HEADER_H - 1, panelW - 28, 1, 1, 0x1AFFFFFF);
    }

    /** 标题栏上那一排 22×22 按钮的纵向位置：按标题栏实际高度居中，折叠时也不会贴边。 */
    private int buttonRowY(int panelY, int panelH) {
        return panelY + (headerHeight(panelH) - 22) / 2;
    }

    private int[] collapseButton(int panelX, int panelY, int panelW, int panelH) {
        return new int[] { panelX + panelW - 14 - 22, buttonRowY(panelY, panelH), 22, 22 };
    }

    /** OneConfig 可用时才存在；点击后注册并打开 OneConfig 的 MuoniumPlayer HUD 页。 */
    private int[] oneConfigButton(int panelX, int panelY, int panelW, int panelH) {
        int w = ghostWidth(FontManager.pf12, "OneConfig");
        return new int[] { panelX + panelW - 14 - 22 - 6 - w, buttonRowY(panelY, panelH), w, 22 };
    }

    /** 只在面板被拖动过之后出现，用来把它送回默认摆位。 */
    private int[] restorePositionButton(int panelX, int panelY, int panelW, int panelH) {
        int[] anchorButton = OneConfigHudBridge.isAvailable()
                ? oneConfigButton(panelX, panelY, panelW, panelH)
                : collapseButton(panelX, panelY, panelW, panelH);
        return new int[] { anchorButton[0] - 6 - 22, anchorButton[1], 22, 22 };
    }

    /** 图标 + 文字的胶囊按钮宽度。绘制与命中都从这里取，改字号也不会两边错开。 */
    private static int ghostWidth(CFontRenderer fr, String label) {
        int text = fr == null || label == null ? 0 : fr.getStringWidth(label);
        return 22 + text + 12;
    }

    /**
     * 侧边栏每行的高度。
     *
     * <p>放不下时先按可用高度均分，但不再无限压缩：低于 {@link #NAV_ROW_MIN_H} 就固定在那一档，
     * 多出来的部分靠侧栏内部滚动（{@link #navScroll}）解决 —— 18 像素的行连图标都画不全，
     * 「都能看见但都看不清」不算是可用。</p>
     */
    private static int navRowHeight(int navH, int count) {
        if (count <= 0) return NAV_ROW_H;
        int available = navH - 12;
        if (count * NAV_ROW_H <= available) return NAV_ROW_H;
        return Math.max(NAV_ROW_MIN_H, available / count);
    }

    /** 侧栏内容总高超出可视区时的最大滚动量；放得下时恒为 0。 */
    private int navMaxScroll(int navH, int count, int rowH) {
        return Math.max(0, 6 + count * rowH - (navH - 6));
    }

    private int clampNavScroll(int value) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int rowH = navRowHeight(panelHeight() - HEADER_H - FOOTER_H, categories.length);
        return Math.max(0, Math.min(navMaxScroll(panelHeight() - HEADER_H - FOOTER_H,
                categories.length, rowH), value));
    }

    private void drawNav(CFontRenderer fr, int navX, int navY, int navW, int navH,
                         HudSettingsModel.Category[] categories, int mouseX, int mouseY) {
        drawRoundedRect(navX, navY, navW, navH, 0, NAV_BG);
        boolean compact = navW < 100;
        int rowH = navRowHeight(navH, categories.length);
        boolean twoLines = rowH >= 32;
        navScroll = Math.max(0, Math.min(navMaxScroll(navH, categories.length, rowH), navScroll));
        int navTop = navY + 6 - navScroll;

        int selected = clampCategory();
        float targetY = navTop + selected * rowH;
        // 橡皮筋：30% 行程就走完 90%，在 60% 处越过目标再收回来 —— 「弹过去钉住」而不是「漂过去停下」，
        // 和播放器侧栏那条高亮是同一档手感。第一次见到这个键时通道直接落在目标上，不会从 0 飞过来。
        navIndicator = motion("nav.indicator", targetY, INDICATOR_MS, TransitionCurveCache.SPRING_LAND);
        // 过冲要钳在侧栏里：最后那一行往下弹 40 多像素，条子就会压到分隔线底下的页脚上。
        navIndicator = Math.max(navY + 6f, Math.min(navY + navH - rowH + 2f, navIndicator));

        // 指示器先画，条目图标与文字画在它上面：切分类时看到的是一块背景滑过去。
        float plateOuter = pushAlpha(navGate(selected));
        try {
            drawRoundedRect(navX + 6, navIndicator, navW - 12, rowH - 4, 9, NAV_SELECTED);
            drawRoundedRect(navX + 6, navIndicator + Math.max(3, rowH / 4.5),
                    3, Math.max(4, rowH - 20), 2, categories[selected].accent);
        } finally {
            layerAlpha = plateOuter;
        }

        for (int index = 0; index < categories.length; index++) {
            HudSettingsModel.Category category = categories[index];
            float reveal = navGate(index);
            if (reveal <= 0.002f) continue;
            int drop = Math.round((1f - reveal) * CONTENT_DROP);
            float rowOuter = pushAlpha(reveal);
            SkijaGfx.pushMatrix();
            SkijaGfx.translate(0f, drop);
            try {
                drawNavRow(fr, category, index, selected, compact, twoLines,
                        navX, navTop, navW, rowH, navH, mouseX, mouseY - drop);
            } finally {
                SkijaGfx.popMatrix();
                layerAlpha = rowOuter;
            }
        }
    }

    /** 侧栏的一行：底色、图标、标题与项数。纵向偏移由调用方用矩阵叠上，命中矩形本身不动。 */
    private void drawNavRow(CFontRenderer fr, HudSettingsModel.Category category, int index,
                            int selected, boolean compact, boolean twoLines,
                            int navX, int navTop, int navW, int rowH, int navH, int mouseX, int mouseY) {
        int rowY = navTop + index * rowH;
        if (rowY + rowH < navTop - 4 || rowY > navTop + navH + 4) return;
        boolean hovered = isInside(mouseX, mouseY, navX + 6, rowY, navW - 12, rowH - 4);
        float hp = named("nav." + index, hovered && index != selected);
        if (hp > 0.02f) {
            drawRoundedRect(navX + 6, rowY, navW - 12, rowH - 4, 9, fade(NAV_HOVER, hp));
        }
        boolean current = index == selected;
        // 图标替代原来的色点：色点只能表达「这一类是紫色的」，图标能表达「这一类是歌词」。
        double iconCx = compact ? navX + navW / 2.0 : navX + 21;
        drawIcon(FontManager.settingsIcon32, category.icon, iconCx, rowY + (rowH - 4) / 2.0,
                current ? category.accent : mix(0xFF7E8A9C, category.accent, hp));
        if (compact) return;
        int titleColor = current ? TEXT_PRIMARY : mix(TEXT_SECONDARY, TEXT_PRIMARY, hp);
        if (twoLines) {
            drawText(FontManager.pf12bold, category.title, navX + 36, rowY + 9, titleColor);
            drawText(fr, category.entryCount() + " 项", navX + 36, rowY + 21, TEXT_TERTIARY);
        } else {
            drawText(FontManager.pf12bold, category.title, navX + 36, rowY + (rowH - 4) / 2 - 5, titleColor);
        }
    }

    private void drawSearch(CFontRenderer fr, int x, int y, int w, int accent, int mouseX, int mouseY) {
        int boxX = x + CONTENT_PAD;
        int boxW = w - CONTENT_PAD * 2;
        ensureSearchInput();
        boolean focused = searchInput.isFocused();
        float searchFocus = motion("search.focus", focused ? 1f : 0f, FOCUS_MS);

        float hp = named("search.box", isInside(mouseX, mouseY, boxX, y, boxW, SEARCH_H));
        drawRoundedRect(boxX, y, boxW, SEARCH_H, SEARCH_H * 0.5,
                mix(mix(0x18FFFFFF, 0x22FFFFFF, hp), 0x2BFFFFFF, searchFocus));
        // 聚焦下划线从中间向两侧展开，而不是整条突然出现：这是最省事又能明确表达「焦点在这里」的动效。
        int lineW = Math.round((boxW - 20) * searchFocus);
        if (lineW > 1) {
            drawRoundedRect(boxX + (boxW - lineW) / 2.0, y + SEARCH_H - 1.5, lineW, 1.5, 1, fade(accent, 0.85f));
        }
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.SEARCH, boxX + 13, y + SEARCH_H / 2.0,
                mix(TEXT_TERTIARY, TEXT_SECONDARY, Math.max(hp, searchFocus)));

        String text = searchInput.getText();
        boolean hasText = !text.isEmpty();
        if (hasText) {
            int[] clear = searchClearButton(x, y, w);
            float cp = named("search.clear", isInside(mouseX, mouseY, clear[0], clear[1], clear[2], clear[3]));
            drawRoundedRect(clear[0], clear[1], clear[2], clear[3], clear[3] * 0.5, fade(0x33FFFFFF, 0.35f + cp * 0.65f));
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CLOSE,
                    clear[0] + clear[2] / 2.0, clear[1] + clear[3] / 2.0,
                    mix(TEXT_SECONDARY, TEXT_PRIMARY, cp));
        }

        int textLeft = boxX + 24;
        int textRight = boxX + boxW - (hasText ? 26 : 10);
        searchInput.xPosition = textLeft;
        searchInput.yPosition = y + 6;
        searchInput.width = Math.max(10, textRight - textLeft);
        searchInput.height = SEARCH_H - 8;
        if (!hasText && !focused) {
            drawText(fr, "搜索设置项…", textLeft, y + 8, TEXT_TERTIARY);
        } else {
            drawText(fr, fr.trim(text, Math.max(10, textRight - textLeft)), textLeft, y + 8, TEXT_PRIMARY);
            if (focused) {
                double caretX = textLeft + 1 + Math.min(fr.getStringWidthD(text), textRight - textLeft);
                // 光标跟着 searchFocus 淡入，避免刚点上去就闪一下。
                drawRoundedRect(caretX, y + 6, 1, SEARCH_H - 12, 1, fade(0xFFEAF3FF, searchFocus));
            }
        }
    }

    /** 搜索框右端的清空按钮，只在有输入时存在；绘制与命中共用这一份矩形。 */
    private int[] searchClearButton(int contentX, int searchY, int contentW) {
        int boxX = contentX + CONTENT_PAD;
        int boxW = contentW - CONTENT_PAD * 2;
        return new int[] { boxX + boxW - 22, searchY + 3, 16, 16 };
    }

    private void drawFooter(CFontRenderer fr, int panelX, int footerY, int panelW,
                            int mouseX, int mouseY) {
        drawRoundedRect(panelX + 14, footerY, panelW - 28, 1, 1, 0x1AFFFFFF);
        int[] reset = resetButton(panelX, footerY, panelW);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.INFO, panelX + 21, footerY + 17, TEXT_TERTIARY);
        String placement = "信息栏 " + pct(HudConfig.infoX, HudConfig.infoY, HudConfig.infoScale)
                + "   歌词 " + pct(HudConfig.lyricX, HudConfig.lyricY, HudConfig.lyricScale);
        double room = Math.max(20, reset[0] - (panelX + 30) - 10);
        drawText(fr, fr.trim(placement, room), panelX + 30, footerY + 13, TEXT_TERTIARY);

        drawGhostButton("footer.reset", fr, HudSettingsIcons.RESTORE, "恢复默认",
                reset, mouseX, mouseY, TEXT_PRIMARY, 0xFFE58B7B);
    }

    private int[] resetButton(int panelX, int footerY, int panelW) {
        int w = ghostWidth(FontManager.pf12, "恢复默认");
        return new int[] { panelX + panelW - 16 - w, footerY + 7, w, 21 };
    }

    // ==================== 行布局（渲染与命中共用） ====================

    /**
     * 把当前分类（或搜索结果）算成一串行矩形与分组卡片。
     *
     * <p>这是整个界面唯一的坐标来源：绘制读它，{@link #mouseClicked} 也读它。纵向起点用
     * {@link #displayScroll} 而不是滚动目标值，滚轮才有惯性；两边读的是同一个字段，所以
     * 滚动过程中点击也不会错位。</p>
     */
    private void layoutRows(int contentX, int listY, int contentW, HudSettingsModel.Category active) {
        rows.clear();
        cards.clear();
        int rowX = contentX + CONTENT_PAD;
        int rowW = contentW - CONTENT_PAD * 2 - 4;
        int cursor = listY - Math.round(displayScroll);
        int top = cursor;

        String query = searchQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            for (int g = 0; g < active.groups.length; g++) {
                cursor = appendGroup(active.groups[g], active.title, active.accent,
                        rowX, rowW, cursor, query, false);
            }
        } else {
            boolean any = false;
            for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
                for (HudSettingsModel.Group group : category.groups) {
                    int before = rows.size();
                    cursor = appendGroup(group, category.title, category.accent,
                            rowX, rowW, cursor, query, true);
                    if (rows.size() > before) any = true;
                }
            }
            if (!any) {
                rows.add(new Row(null, "没有匹配「" + searchQuery.trim() + "」的设置项",
                        HudSettingsIcons.SEARCH, active.accent,
                        rowX, cursor, rowW, EMPTY_STATE_H, true, true, true, cards.size()));
                cursor += EMPTY_STATE_H;
            }
        }
        contentHeight = cursor - top + 6;
    }

    /** 追加一个分组（标题行 + 一张卡片里的若干条目）。返回新的纵向游标。搜索模式下没有命中的条目会整组跳过。 */
    private int appendGroup(HudSettingsModel.Group group, String categoryTitle, int accent,
                            int rowX, int rowW, int cursor, String query, boolean searching) {
        List<HudSettingsModel.Entry> visible = new ArrayList<HudSettingsModel.Entry>();
        for (HudSettingsModel.Entry entry : group.entries) {
            if (entry.isVisible() && (!searching || entry.matches(query))) visible.add(entry);
        }
        if (visible.isEmpty()) return cursor;

        // 这一组的行落在哪张卡上，在卡片真正入表之前就能确定：cards 只在本方法末尾追加。
        int slot = cards.size();
        String title = searching ? categoryTitle + " · " + group.title : group.title;
        rows.add(new Row(null, title, group.icon, accent, rowX, cursor, rowW, GROUP_HEADER_H,
                false, false, false, slot));
        cursor += GROUP_HEADER_H;

        int cardTop = cursor;
        for (int index = 0; index < visible.size(); index++) {
            HudSettingsModel.Entry entry = visible.get(index);
            // 行高跟内容走：说明折成两行的条目给 54px（第二行 y+33 起、12px 字底缘 y+45，
            // 44px 的行会把第二行压到分隔线上——频谱这类长说明被裁得最狠）；
            // 单行说明维持 44。取色/下拉等控件垂直居中不受影响。
            int h = entry.kind == HudSettingsModel.Kind.THEME_GRID ? themeGridHeight() + 14
                    : (entry.description != null && rowW > 0
                        && FontManager.pf12 != null
                        && FontManager.pf12.getStringWidth(entry.description) > (rowW - CONTENT_PAD * 2 - controlWidthOf(entry)) ? ROW_H_TWO_LINES : ROW_H);
            rows.add(new Row(entry, entry.getLabel(), null, accent, rowX, cursor, rowW, h,
                    index == 0, index == visible.size() - 1, false, slot));
            cursor += h;
        }
        cards.add(new Card(rowX, cardTop, rowW, cursor - cardTop));
        return cursor + CARD_GAP;
    }

    private void drawRow(CFontRenderer fr, Row row, int mouseX, int mouseY) {
        if (row.emptyState) {
            drawIcon(FontManager.settingsIcon40, row.icon, row.x + row.w / 2.0, row.y + 26, 0x59FFFFFF);
            drawCenteredText(FontManager.pf12bold, row.title, row.x + row.w / 2.0, row.y + 44, TEXT_SECONDARY);
            drawCenteredText(fr, "换个关键词，或者按 ESC 清空搜索", row.x + row.w / 2.0, row.y + 58, TEXT_TERTIARY);
            return;
        }
        if (row.isGroupHeader()) {
            drawIcon(FontManager.settingsIcon24, row.icon, row.x + 9, row.y + GROUP_HEADER_H / 2.0 + 1, row.accent);
            drawText(FontManager.pf12bold, row.title, row.x + 21, row.y + 8, TEXT_SECONDARY);
            return;
        }

        boolean hovered = isInside(mouseX, mouseY, row.x, row.y, row.w, row.h);
        boolean dragging = row.entry.kind == HudSettingsModel.Kind.SLIDER
                && draggingSlider == row.entry.setting;
        float[] state = progressOf(row.entry);
        Channel[] chan = channelsOf(row.entry);
        state[0] = motion(chan[0], hovered || dragging ? 1f : 0f, HOVER_MS);
        state[2] = motion(chan[2], dragging ? 1f : (hovered ? 0.6f : 0f), HOVER_MS);
        float hp = state[0];

        // 分隔线画在卡片上、悬停高亮之下：卡片背景已经由 drawPanel 一次画完，这里不再补背景，
        // 否则半透明白会叠成更亮的一条带子（这正是之前首尾行看起来有色块的原因）。
        if (!row.firstInCard) {
            drawRoundedRect(row.x + 12, row.y, row.w - 24, 1, 1, fade(0x14FFFFFF, 1f - hp));
        }
        if (hp > 0.01f) {
            // 内缩 2px：卡片自己是 10px 圆角，高亮如果画满整行，方角会顶出卡片的圆角。
            drawRoundedRect(row.x + 2, row.y + 1, row.w - 4, row.h - 2, CARD_RADIUS - 3, fade(ROW_HOVER, hp));
            int barH = Math.round((row.h - 16) * hp);
            if (barH > 1) {
                drawRoundedRect(row.x + 3, row.y + (row.h - barH) / 2.0, 2, barH, 1, fade(row.accent, hp));
            }
        }

        if (row.entry.kind == HudSettingsModel.Kind.THEME_GRID) {
            drawText(FontManager.pf12bold, row.entry.getLabel(), row.x + 14, row.y + 9, TEXT_PRIMARY);
            drawThemeChips(row.x + 14, row.y + 24, mouseX, mouseY);
            return;
        }

        int controlLeft = controlLeft(row);
        drawText(FontManager.pf12bold, row.entry.getLabel(), row.x + 14, row.y + 8, TEXT_PRIMARY);
        String description = row.entry.description;
        if (description != null && !description.isEmpty()) {
            double available = Math.max(20, controlLeft - (row.x + 14) - 10);
            // 两行收住：窄面板下说明被单行硬截比看不全更难受；第二行仍放不下才省略号。
            String[] lines = wrapToTwoLines(fr, description, available);
            drawText(fr, lines[0], row.x + 14, row.y + 21, mix(TEXT_TERTIARY, TEXT_SECONDARY, hp));
            if (lines[1] != null) {
                drawText(fr, lines[1], row.x + 14, row.y + 33, mix(TEXT_TERTIARY, TEXT_SECONDARY, hp));
            }
        }

        // 搜索跳转后的定位脉冲：一条 accent 亮带从整行扫出再淡掉，指明「你要的那一条在这里」。
        if (row.entry == flashEntry) {
            float pulse = flashPulse();
            if (pulse > 0.01f) {
                drawRoundedRect(row.x + 2, row.y + 1, row.w - 4, row.h - 2, CARD_RADIUS - 3,
                        fade(row.accent, 0.28f * pulse));
                drawRoundedRect(row.x + 3, row.y + 1, 2, row.h - 2, 1, fade(row.accent, pulse));
            }
        }

        int centerY = row.y + row.h / 2;
        int rowRight = row.x + row.w - 14;
        switch (row.entry.kind) {
            case SLIDER:
                drawSliderControl(fr, row.entry.setting, rowRight, centerY, row.accent, state[2]);
                break;
            case TOGGLE: {
                state[1] = motion(chan[1], row.entry.isOn() ? 1f : 0f, KNOB_MS);
                drawSwitch(rowRight - SWITCH_W, centerY - SWITCH_H / 2, state[1], row.accent, hp);
                break;
            }
            case COLOR:
                drawSwatch(rowRight - SWATCH_W, centerY - SWATCH_H / 2, row.entry.getColor(),
                        editingColor == row.entry.colorTarget, hp);
                break;
            case CHOICE: {
                String value = row.entry.getValueText();
                int pillW = choicePillWidth(fr, value);
                boolean open = openChoice == row.entry;
                drawRoundedRect(rowRight - pillW, centerY - 10, pillW, 20, 10,
                        open ? mix(BUTTON_HOVER, 0x4AFFFFFF, 0.5f) : mix(BUTTON_BG, BUTTON_HOVER, hp));
                drawText(fr, fr.trim(value, pillW - 30), rowRight - pillW + 11, centerY - 3, TEXT_PRIMARY);
                // 展开时箭头绕自身中心翻 180°（朝下变朝上）：方向本身就是「再点一下会收起」的提示。
                double chevCx = rowRight - 11 + hp * 1.5;
                int chevColor = mix(TEXT_SECONDARY, TEXT_PRIMARY, Math.max(hp, open ? 1f : 0f));
                if (open) {
                    SkijaGfx.pushMatrix();
                    SkijaGfx.translate(chevCx, centerY);
                    SkijaGfx.rotateZ(180f);
                    SkijaGfx.translate(-chevCx, -centerY);
                    try {
                        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT,
                                chevCx, centerY, chevColor);
                    } finally {
                        SkijaGfx.popMatrix();
                    }
                } else {
                    drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT,
                            chevCx, centerY, chevColor);
                }
                break;
            }
            case ACTION: {
                int w = actionButtonWidth(fr, row.entry.getLabel());
                drawRoundedRect(rowRight - w, centerY - 10, w, 20, 10, mix(BUTTON_BG, BUTTON_HOVER, hp));
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.REFRESH,
                        rowRight - w + 14, centerY, mix(TEXT_SECONDARY, TEXT_PRIMARY, hp));
                drawText(fr, "执行", rowRight - w + 23, centerY - 3, TEXT_PRIMARY);
                break;
            }
            default:
                break;
        }
        // 手机档锁死行：整行罩一层底色纱（文字、控件一起变灰），点击在 activateRow 已挡。
        if (row.entry.isLocked()) {
            drawRoundedRect(row.x + 2, row.y + 1, row.w - 4, row.h - 2, CARD_RADIUS - 3,
                    0x96141A22);
        }
    }

    /** 该条目右侧控件的估算宽度（与 controlLeft 同一口径），用于判断说明是否折行。 */
    private static int controlWidthOf(HudSettingsModel.Entry entry) {
        switch (entry.kind) {
            case SLIDER:
                return VALUE_W + TRACK_W;
            case TOGGLE:
                return SWITCH_W;
            case COLOR:
                return SWATCH_W;
            case CHOICE:
                return choicePillWidth(FontManager.pf12, entry.getValueText());
            case ACTION:
                return actionButtonWidth(FontManager.pf12, entry.getLabel());
            default:
                return 0;
        }
    }

    /** 控件区的左边界。说明文字要在它之前收住，否则长句子会压到控件底下。 */
    private int controlLeft(Row row) {
        int rowRight = row.x + row.w - 14;
        switch (row.entry.kind) {
            case SLIDER:
                return rowRight - VALUE_W - TRACK_W - 12;
            case TOGGLE:
                return rowRight - SWITCH_W - 12;
            case COLOR:
                return rowRight - SWATCH_W - 12;
            case CHOICE:
                return rowRight - choicePillWidth(FontManager.pf12, row.entry.getValueText()) - 12;
            case ACTION:
                return rowRight - actionButtonWidth(FontManager.pf12, row.entry.getLabel()) - 12;
            default:
                return rowRight;
        }
    }

    private static int choicePillWidth(CFontRenderer fr, String value) {
        int textWidth = fr == null || value == null ? 0 : fr.getStringWidth(value);
        return Math.max(62, Math.min(148, textWidth + 34));
    }

    private static int actionButtonWidth(CFontRenderer fr, String label) {
        return 60;
    }

    private void drawSliderControl(CFontRenderer fr, HudSetting setting, int rowRight,
                                   int centerY, int accent, float knobProgress) {
        if (editingSliderValue == setting && sliderValueInput != null
                && sliderValueInput.isFocused()) {
            // 输入态：原数值文本换成输入框。文字与光标手绘（hexColorInput 同款范式——
            // compat 文本框只当状态容器，渲染全部自己来），位置与正常值文本同锚。
            int inputW = VALUE_W + 8;
            int inputX = rowRight - inputW;
            ensureSliderValueInput();
            sliderValueInput.xPosition = inputX + 4;
            sliderValueInput.yPosition = centerY - 7;
            sliderValueInput.width = inputW - 8;
            sliderValueInput.height = 14;
            drawRoundedRect(inputX, centerY - 9, inputW, 18, 4, 0x2E46526B);
            drawRoundedRect(inputX, centerY - 9, inputW, 18, 4, 0x4E5FD3E8);
            drawText(fr, sliderValueInput.getText(), inputX + 5, centerY - 3, TEXT_PRIMARY);
            if (sliderValueInput.isFocused()) {
                int caretX = inputX + 6 + fr.getStringWidth(sliderValueInput.getText());
                drawRoundedRect(caretX, centerY - 5, 1, 10, 1, 0xFFEAF3FF);
            }
            drawTrackAndKnob(fr, setting, rowRight, centerY, accent, knobProgress,
                    trackX(rowRight), true);
            return;
        }
        String value = HudSettingsModel.formatValue(setting, setting.getValue());
        drawText(fr, value, rowRight - fr.getStringWidth(value), centerY - 3,
                mix(TEXT_SECONDARY, TEXT_PRIMARY, knobProgress));

        int trackX = trackX(rowRight);
        drawTrackAndKnob(fr, setting, rowRight, centerY, accent, knobProgress, trackX, false);
    }

    /** 轨道 + 把手。输入态下轨道照画但把手降透明度：此刻数值由文本框主导。 */
    private void drawTrackAndKnob(CFontRenderer fr, HudSetting setting, int rowRight,
                                  int centerY, int accent, float knobProgress,
                                  int trackX, boolean inputMode) {
        double trackY = centerY - 2;
        float span = setting.getMax() - setting.getMin();
        float progress = span <= 0f ? 0f : clamp01((setting.getValue() - setting.getMin()) / span);
        double fillW = TRACK_W * progress;
        drawRoundedRect(trackX, trackY, TRACK_W, 4, 2, TRACK_BG);
        if (fillW > 0.5) {
            drawRoundedRect(trackX, trackY, fillW, 4, 2, inputMode ? fade(accent, .55f) : accent);
        }
        // 把手悬停/拖动时略微放大，并把光晕铺开：这是「可以抓住它」最直接的反馈。
        double knobX = trackX + fillW;
        double radius = 5.0 + 1.7 * knobProgress;
        if (knobProgress > 0.02f) {
            drawRoundedRect(knobX - radius - 3, trackY + 2 - radius - 3, (radius + 3) * 2, (radius + 3) * 2,
                    radius + 3, fade(accent, 0.24f * knobProgress));
        }
        drawRoundedRect(knobX - radius, trackY + 2 - radius + 1, radius * 2, radius * 2, radius, 0x55000000);
        drawRoundedRect(knobX - radius, trackY + 2 - radius, radius * 2, radius * 2, radius,
                inputMode ? 0xCCF6F8FC : 0xFFF6F8FC);
    }

    private static int trackX(int rowRight) {
        return rowRight - VALUE_W - TRACK_W;
    }

    private void drawSwitch(int x, int y, float progress, int accent, float hover) {
        drawRoundedRect(x, y, SWITCH_W, SWITCH_H, SWITCH_H * 0.5,
                mix(mix(SWITCH_OFF, 0x45FFFFFF, hover), accent, progress));
        int knob = SWITCH_H - 6;
        // 拇指按进度平移而不是二选一跳位，配合轨道颜色插值就是 iOS/Windows 11 那种切换手感。
        double knobX = x + 3 + (SWITCH_W - knob - 6) * progress;
        drawRoundedRect(knobX, y + 3.5, knob, knob, knob * 0.5, 0x40000000);
        drawRoundedRect(knobX, y + 3, knob + 2.0 * hover * progress, knob, knob * 0.5, 0xFFF8FAFD);
    }

    private void drawSwatch(int x, int y, Color color, boolean selected, float hover) {
        drawRoundedRect(x - 1, y - 1, SWATCH_W + 2, SWATCH_H + 2, 8,
                selected ? 0xFFFFD978 : mix(0x33FFFFFF, 0x7AFFFFFF, hover));
        drawRoundedRect(x, y, SWATCH_W, SWATCH_H, 7,
                (color == null ? 0x9AA6B8 : color.getRGB()) | 0xFF000000);
        if (hover > 0.05f || selected) {
            // 图标颜色按底色亮度取：色块本身就是用户选的颜色，写死深色在深紫上会看不见。
            int ink = contrastOn(color == null ? 0xFF9AA6B8 : color.getRGB() | 0xFF000000);
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.COLORIZE,
                    x + SWATCH_W / 2.0, y + SWATCH_H / 2.0, fade(ink, Math.max(hover, selected ? 1f : 0f)));
        }
    }

    /**
     * 图标胶囊按钮。{@code label} 为 {@code null} 时只画图标（22×22 的方按钮）。
     *
     * <p>{@code key} 用来存悬停进度，必须每帧稳定 —— 用常量字符串，别拼可变内容。</p>
     */
    private void drawGhostButton(String key, CFontRenderer fr, String glyph, String label,
                                 int[] box, int mouseX, int mouseY, int tint, int accent) {
        int x = box[0];
        int y = box[1];
        int w = box[2];
        int h = box[3];
        float hp = named(key, isInside(mouseX, mouseY, x, y, w, h));
        drawRoundedRect(x, y, w, h, h * 0.5, mix(BUTTON_BG, BUTTON_HOVER, hp));
        if (hp > 0.02f) {
            // 悬停时沿上沿描一道 accent：只靠背景变亮，在半透明面板上几乎看不出来。
            drawRoundedRect(x + h * 0.35, y, w - h * 0.7, 1, 1, fade(accent, hp * 0.9f));
        }
        int color = mix(tint, 0xFFFFFFFF, hp * 0.6f);
        if (label == null || label.isEmpty()) {
            drawIcon(FontManager.settingsIcon24, glyph, x + w / 2.0, y + h / 2.0, color);
        } else {
            drawIcon(FontManager.settingsIcon24, glyph, x + 13, y + h / 2.0, color);
            drawText(fr, label, x + 22, y + h / 2.0 - 3.5, color);
        }
    }

    // ==================== 主题色卡 ====================

    private int themeChipColumns() {
        return 9;
    }

    private int themeGridHeight() {
        int count = NCMTheme.ThemePreset.values().length;
        int lines = Math.max(1, (count + themeChipColumns() - 1) / themeChipColumns());
        return lines * (THEME_CHIP_SIZE + THEME_CHIP_GAP) + 2;
    }

    private void drawThemeChips(int x, int y, int mouseX, int mouseY) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        NCMTheme.ThemePreset current = NCMTheme.getCurrent();
        for (int i = 0; i < presets.length; i++) {
            int chipX = x + (i % themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            boolean selected = presets[i] == current;
            boolean lockedTheme = !NCMTheme.isSelectable(presets[i]);
            float hp = named("theme." + i, isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE));
            if (!lockedTheme && (selected || hp > 0.02f)) {
                drawRoundedRect(chipX - 2, chipY - 2, THEME_CHIP_SIZE + 4, THEME_CHIP_SIZE + 4, 9,
                        selected ? 0xFFF4F7FB : fade(0x88A9BCD6, hp));
            }
            drawRoundedRect(chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE, 7,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            drawRoundedRect(chipX + 5, chipY + 5, THEME_CHIP_SIZE - 10, THEME_CHIP_SIZE - 10, 5,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT));
            if (lockedTheme) {
                // 手机档：真玻璃主题芯片压灰，点它没有反应（activateRow 里已挡）。
                drawRoundedRect(chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE, 7, 0x96141A22);
            }
            if (selected) {
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHECK,
                        chipX + THEME_CHIP_SIZE / 2.0, chipY + THEME_CHIP_SIZE / 2.0,
                        contrastOn(0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT)));
            }
        }
    }

    /** 命中测试与 {@link #drawThemeChips} 共用同一套网格推导。 */
    private int themeChipIndexAt(int mouseX, int mouseY, int x, int y) {
        int count = NCMTheme.ThemePreset.values().length;
        for (int i = 0; i < count; i++) {
            int chipX = x + (i % themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            if (isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE)) return i;
        }
        return -1;
    }

    // ==================== 取色器 ====================

    private int pickerX(int panelX, int panelW) {
        int navW = navWidth(panelW);
        return panelX + navW + Math.max(6, (panelW - navW - PICKER_W) / 2);
    }

    private int pickerY(int panelY, int panelH) {
        return panelY + Math.max(HEADER_H + 6, (panelH - PICKER_H) / 2);
    }

    /** 取色器标题：每个 ColorTarget 一句。 */
    private static String pickerTitle(HudSettingsModel.ColorTarget target) {
        switch (target) {
            case CURRENT: return "当前歌词颜色";
            case WORLD_LYRICS: return "3D 歌词文字颜色";
            default: return "普通歌词颜色";
        }
    }

    private void drawColorPicker(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                                 int mouseX, int mouseY) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        drawSoftShadow(x, y, PICKER_W, PICKER_H, 13);
        drawRoundedRect(x, y, PICKER_W, PICKER_H, 13, 0xF71A202A);
        drawRoundedRect(x + 6, y, PICKER_W - 12, 1, 1, PANEL_EDGE);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.COLORIZE, x + 20, y + 17, 0xFFB6C4D8);
        drawText(FontManager.pf12bold,
                pickerTitle(editingColor),
                x + 29, y + 13, TEXT_PRIMARY);
        drawGhostButton("picker.close", fr, HudSettingsIcons.CLOSE, null,
                pickerCloseButton(x, y), mouseX, mouseY, TEXT_SECONDARY, 0xFFE58B7B);

        int svX = x + 12;
        int svY = y + 34;
        drawSaturationBrightnessArea(svX, svY);
        int hueY = svY + SV_H + 12;
        drawHueSlider(svX, hueY);

        Color preview = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        drawRoundedRect(x + 12, hueY + 24, 28, HEX_INPUT_H, 7, preview.getRGB() | 0xFF000000);

        ensureHexColorInput();
        int inputX = x + 48;
        int inputY = hueY + 24;
        hexColorInput.xPosition = inputX + 6;
        hexColorInput.yPosition = inputY + 4;
        hexColorInput.width = HEX_INPUT_W - 12;
        hexColorInput.height = HEX_INPUT_H - 8;
        drawRoundedRect(inputX, inputY, HEX_INPUT_W, HEX_INPUT_H, 7,
                hexColorInput.isFocused() ? 0x33FFFFFF : 0x1FFFFFFF);
        drawText(fr, hexColorInput.getText(), inputX + 7, inputY + 5, TEXT_PRIMARY);
        if (hexColorInput.isFocused()) {
            int caretX = inputX + 8 + fr.getStringWidth(hexColorInput.getText());
            drawRoundedRect(caretX, inputY + 4, 1, HEX_INPUT_H - 8, 1, 0xFFEAF3FF);
        }
        drawText(fr, "输入 HEX", inputX + HEX_INPUT_W + 9, inputY + 5, TEXT_TERTIARY);
    }

    private int[] pickerCloseButton(int pickerX, int pickerY) {
        return new int[] { pickerX + PICKER_W - 30, pickerY + 6, 20, 20 };
    }

    private void drawSaturationBrightnessArea(int x, int y) {
        int hueRgb = Color.HSBtoRGB(pickerHue, 1.0f, 1.0f) | 0xFF000000;
        Gui.drawRect(x, y, x + SV_W, y + SV_H, hueRgb);
        for (int col = 0; col < SV_W; col++) {
            int alpha = (int) (255.0f * (1.0f - col / (float) (SV_W - 1)));
            Gui.drawRect(x + col, y, x + col + 1, y + SV_H, alpha << 24 | 0xFFFFFF);
        }
        for (int rowIndex = 0; rowIndex < SV_H; rowIndex++) {
            int alpha = (int) (255.0f * (rowIndex / (float) (SV_H - 1)));
            Gui.drawRect(x, y + rowIndex, x + SV_W, y + rowIndex + 1, alpha << 24);
        }
        drawPickerMarker(x + Math.round(pickerSaturation * (SV_W - 1)),
                y + Math.round((1.0f - pickerBrightness) * (SV_H - 1)));
    }

    private void drawHueSlider(int x, int y) {
        for (int col = 0; col < SV_W; col++) {
            Gui.drawRect(x + col, y, x + col + 1, y + HUE_H,
                    Color.HSBtoRGB(col / (float) (SV_W - 1), 1.0f, 1.0f) | 0xFF000000);
        }
        int markerX = x + Math.round(pickerHue * (SV_W - 1));
        Gui.drawRect(markerX - 1, y - 2, markerX + 2, y + HUE_H + 2, 0xFF000000);
        Gui.drawRect(markerX, y - 1, markerX + 1, y + HUE_H + 1, 0xFFFFFFFF);
    }

    private static void drawPickerMarker(int x, int y) {
        Gui.drawRect(x - 3, y - 3, x + 4, y - 2, 0xFF000000);
        Gui.drawRect(x - 3, y + 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 3, y - 3, x - 2, y + 4, 0xFF000000);
        Gui.drawRect(x + 3, y - 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 2, y - 2, x + 3, y - 1, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y + 2, x + 3, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y - 2, x - 1, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x + 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
    }

    // ==================== 鼠标 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = WindowAdaptiveScale.toLayoutXForced(mouseX);
            mouseY = WindowAdaptiveScale.toLayoutYForced(mouseY);
        }
        try {
            mouseClickedImpl(mouseX, mouseY, button);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    private void mouseClickedImpl(int mouseX, int mouseY, int button) throws IOException {
        if (page.isDismissing()) return;
        if (resetConfirmationVisible) {
            if (button == 0) handleResetConfirmationClick(mouseX, mouseY);
            return;
        }
        // 任何一次左键点击都先结算输入中的滑条数值（点击本身就是输入框外的落点，
        // 语义 = 提交）。activateRow 里点回轨道/值文本的分支随后会按需重新进入输入态。
        if (button == 0) commitSliderValueEdit();
        if (button != 0) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }

        int[] panel = panelBounds();
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];

        // 下拉展开时它是最顶层：点选项就选，点其它任何地方先收起并吞掉这一下 ——
        // 否则收起的那次点击会穿透到下面的行，把一个「只是想关掉」的动作变成一次误改。
        if (openChoice != null) {
            int[] drop = choiceDropdownBounds(FontManager.pf12, panelX, panelY, panelW, panelH);
            if (drop != null && isInside(mouseX, mouseY, drop[0], drop[1], drop[2], drop[3])) {
                int item = choiceItemAt(mouseX, mouseY, drop);
                if (item >= 0) {
                    String[] choices = openChoice.getChoices();
                    if (item < choices.length) {
                        openChoice.setChoice(choices[item]);
                        HudConfig.save();
                    }
                }
            }
            openChoice = null;
            return;
        }

        if (editingColor != HudSettingsModel.ColorTarget.NONE
                && handlePickerClick(mouseX, mouseY, button, panelX, panelY, panelW, panelH)) {
            return;
        }

        // 快捷配置小窗：点选项/滑条/按钮都吃掉这一下；点其它任何地方收起选中态。
        if (stage.hasSelection() || popGate > 0.01f) {
            int[] pop = widgetPopoverBounds();
            if (isInside(mouseX, mouseY, pop[0], pop[1], pop[2], pop[3])) {
                handleWidgetPopoverClick(mouseX, mouseY, pop);
                return;
            }
        }

        if (!isInside(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
            // 点击面板之外：关掉取色器与搜索焦点，剩下的交给 HUD 舞台（在 drawScreen 里按鼠标状态处理）。
            editingColor = HudSettingsModel.ColorTarget.NONE;
            openChoice = null;
            if (searchInput != null) searchInput.setFocused(false);
            return;
        }

        int[] toggle = collapseButton(panelX, panelY, panelW, panelH);
        if (isInside(mouseX, mouseY, toggle[0], toggle[1], toggle[2], toggle[3])) {
            collapsed = !collapsed;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            draggingSlider = null;
            return;
        }

        if (!collapsed) {
            if (OneConfigHudBridge.isAvailable()) {
                int[] oneConfig = oneConfigButton(panelX, panelY, panelW, panelH);
                if (isInside(mouseX, mouseY, oneConfig[0], oneConfig[1], oneConfig[2], oneConfig[3])) {
                    switchToOneConfig();
                    return;
                }
            }
            if (panelMoved()) {
                int[] home = restorePositionButton(panelX, panelY, panelW, panelH);
                if (isInside(mouseX, mouseY, home[0], home[1], home[2], home[3])) {
                    resetPanelPosition();
                    return;
                }
            }
        }

        // 右下角缩放角标：先于标题栏拖动区判定（它在面板最角落，宽高命中带互不相交，
        // 但收起态面板太矮、角标已隐藏）。命中即进入拖拽，尺寸跟着鼠标走到松手落盘。
        int[] grip = resizeGripRect(panelX, panelY, panelW, panelH);
        if (grip != null && overResizeGrip(grip, mouseX, mouseY)) {
            resizingPanel = true;
            resizePressW = panelW;
            resizePressH = panelH;
            resizePressX = mouseX;
            resizePressY = mouseY;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            draggingSlider = null;
            if (searchInput != null) searchInput.setFocused(false);
            return;
        }

        // 标题栏剩下的空白处即拖动区。放在按钮判定之后，按钮才不会被拖动吞掉。
        if (isInside(mouseX, mouseY, panelX, panelY, panelW, headerHeight(panelH))) {
            long now = System.currentTimeMillis();
            if (now - lastHeaderClick < DOUBLE_CLICK_MS) {
                lastHeaderClick = 0L;
                resetPanelPosition();
                return;
            }
            lastHeaderClick = now;
            draggingPanel = true;
            panelDragOffX = mouseX - panelX;
            panelDragOffY = mouseY - panelY;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (searchInput != null) searchInput.setFocused(false);
            return;
        }
        if (collapsed) return;

        int[] reset = resetButton(panelX, panelY + panelH - FOOTER_H, panelW);
        if (isInside(mouseX, mouseY, reset[0], reset[1], reset[2], reset[3])) {
            resetConfirmationVisible = true;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            draggingSlider = null;
            return;
        }

        int navW = navWidth(panelW);
        int bodyY = panelY + HEADER_H;
        int bodyH = panelH - HEADER_H - FOOTER_H;
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        if (mouseX < panelX + navW) {
            // 行高与滚动偏移必须和 drawNav 用同一套算法，否则窗口矮的时候点击会落到别的分类上。
            int navRowH = navRowHeight(bodyH, categories.length);
            int navMax = Math.max(0, Math.min(navMaxScroll(bodyH, categories.length, navRowH), navScroll));
            for (int index = 0; index < categories.length; index++) {
                int rowY = bodyY + 6 - navMax + index * navRowH;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, navW - 12, navRowH - 4)) {
                    categoryIndex = index;
                    // 搜索结果是全分类平铺的虚拟页：从它点侧栏回某个分类，搜索自然结束。
                    // 「跨分类找同一个词」由搜索页本身承担，不需要在这里保留关键词。
                    editingColor = HudSettingsModel.ColorTarget.NONE;
                    clearSearch();
                    return;
                }
            }
            return;
        }

        int contentX = panelX + navW;
        int contentW = panelW - navW;
        int searchY = bodyY + 8;
        ensureSearchInput();
        if (!searchInput.getText().isEmpty()) {
            int[] clear = searchClearButton(contentX, searchY, contentW);
            if (isInside(mouseX, mouseY, clear[0], clear[1], clear[2], clear[3])) {
                clearSearch();
                return;
            }
        }
        if (isInside(mouseX, mouseY, contentX + CONTENT_PAD, searchY, contentW - CONTENT_PAD * 2, SEARCH_H)) {
            searchInput.setFocused(true);
            searchInput.mouseClicked(mouseX, mouseY, button);
            return;
        }
        searchInput.setFocused(false);

        int listY = searchY + SEARCH_H + 8;
        int listH = Math.max(24, bodyY + bodyH - listY);
        if (!isInside(mouseX, mouseY, contentX, listY, contentW, listH)) return;

        layoutRows(contentX, listY, contentW, categories[clampCategory()]);
        boolean searching = !searchQuery.trim().isEmpty();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.isGroupHeader()) continue;
            if (!isInside(mouseX, mouseY, row.x, row.y, row.w, row.h)) continue;
            if (searching) {
                // 搜索结果点击 = 跳转：到它所属的分类页、滚到它面前、亮一下。
                // 在结果页里直接拨开关看似更快，但用户会因此搞不清「我现在改的是哪一页的哪一项」。
                jumpToEntry(row.entry, contentX, listY, contentW);
            } else {
                activateRow(row, mouseX, mouseY);
            }
            return;
        }
    }

    /**
     * 从搜索结果跳到条目所在的分类页：切分类、清搜索、把滚动定位到目标行附近，
     * 并让该行做一次高亮脉冲。定位用目标滚动值直接落位，不再从 0 惯性滑过去 ——
     * 用户刚点过它，知道自己在找什么，动画反而挡路。
     */
    private void jumpToEntry(HudSettingsModel.Entry target, int contentX, int listY, int contentW) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int targetCategory = clampCategory();
        for (int c = 0; c < categories.length; c++) {
            for (HudSettingsModel.Group group : categories[c].groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry == target) {
                        targetCategory = c;
                        break;
                    }
                }
            }
        }
        clearSearch();
        categoryIndex = targetCategory;
        // 借 layoutRows 算出目标行在未滚动状态下的纵坐标，再反推滚动目标。
        float savedScroll = displayScroll;
        displayScroll = 0f;
        layoutRows(contentX, listY, contentW, categories[targetCategory]);
        displayScroll = savedScroll;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.entry == target) {
                scrollByCategory[targetCategory] = Math.max(0, row.y - listY - 24);
                displayScroll = scrollByCategory[targetCategory];
                break;
            }
        }
        flashEntry = target;
        flashDeadline = System.currentTimeMillis() + FLASH_MS;
    }

    /** 快捷小窗内的点击：宽高滑条（复用行内滑条的拖动状态）、整体缩放滑条、居中与复位。 */
    private void handleWidgetPopoverClick(int mouseX, int mouseY, int[] pop) {
        HudOverlayStage.Kind kind = stage.selected();
        boolean lyrics = kind == HudOverlayStage.Kind.LYRICS;
        int pad = 14;
        int right = pop[0] + pop[2] - pad;
        int trackX = right - VALUE_W - TRACK_W;
        int y = pop[1] + 34;
        if (lyrics) {
            if (tryBeginPopoverSlider(HudSetting.OSD_WIDTH, mouseX, mouseY, y, trackX)) return;
            y += 30;
            if (tryBeginPopoverSlider(HudSetting.OSD_HEIGHT, mouseX, mouseY, y, trackX)) return;
            y += 30;
        }
        // 整体缩放滑条。
        int sy = y + 12;
        if (mouseX >= trackX - 8 && mouseX <= trackX + TRACK_W + 8
                && mouseY >= sy - 8 && mouseY <= sy + 18) {
            draggingPopoverScale = true;
            popoverScaleTrackX = trackX;
            setPopoverScaleFromMouse(mouseX, trackX, kind);
            return;
        }
        y += 34;
        int btnW = (pop[2] - pad * 2 - 8) / 2;
        int[] center = { pop[0] + pad, y, btnW, 22 };
        int[] reset = { pop[0] + pad + btnW + 8, y, btnW, 22 };
        if (isInside(mouseX, mouseY, center[0], center[1], center[2], center[3])) {
            if (kind == HudOverlayStage.Kind.INFO) {
                HudConfig.infoX = 0.5f;
            } else {
                HudConfig.lyricX = 0.5f;
            }
            HudConfig.save();
            return;
        }
        if (isInside(mouseX, mouseY, reset[0], reset[1], reset[2], reset[3])) {
            if (kind == HudOverlayStage.Kind.INFO) {
                HudConfig.infoX = 0.02f;
                HudConfig.infoY = 0.02f;
                HudConfig.infoScale = 1.0f;
            } else {
                HudConfig.lyricX = 0.5f;
                HudConfig.lyricY = 0.85f;
                HudConfig.lyricScale = 1.0f;
                HudConfig.lyricWidth = 450.0f;
                HudConfig.lyricHeight = 120.0f;
                HudSettingsModel.syncLyricViewportModuleValues();
            }
            HudConfig.save();
            return;
        }
    }

    /** 小窗里宽高滑条的按下：命中轨道附近才开始拖（与设置页行内滑条同一条防误触规则）。 */
    private boolean tryBeginPopoverSlider(HudSetting setting, int mouseX, int mouseY, int y, int trackX) {
        int sy = y + 12;
        if (mouseX >= trackX - 8 && mouseX <= trackX + TRACK_W + 8
                && mouseY >= sy - 8 && mouseY <= sy + 18) {
            draggingSlider = setting;
            draggingSliderTrackX = trackX;
            setSliderFromMouse(setting, mouseX, trackX);
            return true;
        }
        return false;
    }

    private void setPopoverScaleFromMouse(int mouseX, int trackX, HudOverlayStage.Kind kind) {
        float progress = clamp01((mouseX - trackX) / (float) TRACK_W);
        float value = HudConfig.SCALE_MIN + (HudConfig.SCALE_MAX - HudConfig.SCALE_MIN) * progress;
        if (kind == HudOverlayStage.Kind.INFO) {
            HudConfig.infoScale = HudConfig.clampScale(value);
        } else {
            HudConfig.lyricScale = HudConfig.clampScale(value);
        }
        settingsDirty = true;
    }

    private void resetPanelPosition() {
        HudConfig.hudEditorPanelX = -1.0f;
        HudConfig.hudEditorPanelY = -1.0f;
        HudConfig.save();
        draggingPanel = false;
    }

    private void clearSearch() {
        if (searchInput != null) {
            searchInput.setText("");
            searchInput.setFocused(false);
        }
        searchQuery = "";
        searchScroll = 0;
    }

    private void activateRow(Row row, int mouseX, int mouseY) {
        // 手机档锁死行（真玻璃材质整组）：点击与滑条拖动一律无效，渲染层把它画成灰的。
        if (row.entry.isLocked()) return;
        switch (row.entry.kind) {
            case SLIDER: {
                int trackX = trackX(row.x + row.w - 14);
                // 只有点在轨道附近才动数值。之前整行都算命中，于是点标签或说明文字会把滑块
                // 直接甩到最小值 —— 一个很容易误触又不可撤销的操作。
                if (mouseX < trackX - 8 || mouseX > trackX + TRACK_W + 8) {
                    // 轨道左侧是数值文本：点它进入「直接输入」而不是拖动。
                    beginSliderValueEdit(row.entry.setting);
                    break;
                }
                if (editingSliderValue != null) {
                    // 输入途中按回轨道 = 放弃输入改拖动，先落一次盘再交接。
                    commitSliderValueEdit();
                }
                draggingSlider = row.entry.setting;
                draggingSliderTrackX = trackX;
                setSliderFromMouse(row.entry.setting, mouseX, trackX);
                break;
            }
            case TOGGLE:
            case ACTION:
                row.entry.activate();
                break;
            case CHOICE:
                // 展开下拉而不是盲切下一档：选项多于一档时，「点一下循环」意味着用户永远
                // 不知道还有哪些档位、当前是第几档。同一行再点一次收起。
                openChoice = openChoice == row.entry ? null : row.entry;
                break;
            case COLOR:
                beginColorEdit(row.entry);
                break;
            case THEME_GRID: {
                int chip = themeChipIndexAt(mouseX, mouseY, row.x + 14, row.y + 24);
                if (chip >= 0) {
                    NCMTheme.ThemePreset preset = NCMTheme.ThemePreset.values()[chip];
                    if (!NCMTheme.isSelectable(preset)) break;
                    DownloadDynamicIsland.showTheme(NCMTheme.setCurrent(preset) == null
                            ? NCMTheme.getCurrentName() : preset.getDisplayName());
                }
                break;
            }
            default:
                break;
        }
    }

    private boolean handlePickerClick(int mouseX, int mouseY, int button,
                                      int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        int[] close = pickerCloseButton(x, y);
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (hexColorInput != null) hexColorInput.setFocused(false);
            return true;
        }
        int svX = x + 12;
        int svY = y + 34;
        int hueY = svY + SV_H + 12;
        int inputX = x + 48;
        int inputY = hueY + 24;
        ensureHexColorInput();
        if (isInside(mouseX, mouseY, inputX, inputY, HEX_INPUT_W, HEX_INPUT_H)) {
            hexColorInput.setFocused(true);
            hexColorInput.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        hexColorInput.setFocused(false);
        if (isInside(mouseX, mouseY, svX, svY, SV_W, SV_H)) {
            draggingSaturationBrightness = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        if (isInside(mouseX, mouseY, svX, hueY, SV_W, HUE_H)) {
            draggingHue = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        return false;
    }

    // ==================== CHOICE 下拉浮层 ====================

    /**
     * 下拉浮层的矩形 {@code [x, y, w, h, itemH, visible]}，右对齐锚在那一行的药丸下方；
     * 放不下就向上翻。{@code null} 表示找不到锚行（条目被条件显隐收走了），那一帧不画也不吃点击。
     */
    private int[] choiceDropdownBounds(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH) {
        if (openChoice == null) return null;
        Row anchor = null;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).entry == openChoice) {
                anchor = rows.get(index);
                break;
            }
        }
        if (anchor == null) return null;
        String[] choices = openChoice.getChoices();
        boolean hinted = openChoice.hasChoiceHints();
        int w = DROP_MIN_W;
        for (String choice : choices) {
            if (choice == null) continue;
            w = Math.max(w, fr.getStringWidth(choice) + 58);
        }
        w = Math.min(DROP_MAX_W, w);
        int itemH = hinted ? DROP_ITEM_H : DROP_ITEM_COMPACT_H;
        int visible = Math.min(choices.length, DROP_MAX_VISIBLE);
        int h = visible * itemH + 8;
        int bodyY = panelY + HEADER_H;
        int bodyBottom = panelY + panelH - FOOTER_H;
        int x = Math.max(panelX + 8, Math.min(panelX + panelW - 8 - w, anchor.x + anchor.w - w));
        int y = anchor.y + anchor.h + 4;
        // 面板里放不下就向上翻：锚行上面那段高度足够时直接翻，不足时按可用空间再压可见条数。
        if (y + h > bodyBottom) {
            int upY = anchor.y - 4 - h;
            if (upY >= bodyY) {
                y = upY;
            } else {
                int fit = Math.max(1, (bodyBottom - anchor.y - 12) / itemH);
                if (fit < visible) {
                    visible = fit;
                    h = visible * itemH + 8;
                }
                y = Math.min(anchor.y + anchor.h + 4, bodyBottom - h);
            }
        }
        return new int[] { x, y, w, h, itemH, visible };
    }

    /** 下拉里第几项在这个 y 上；返回 {@code -1} 表示点在浮层空白处。 */
    private int choiceItemAt(int mouseX, int mouseY, int[] drop) {
        int inner = mouseY - drop[1] - 4;
        if (inner < 0) return -1;
        int index = inner / drop[4];
        if (index >= drop[5]) return -1;
        return index;
    }

    private void drawChoiceDropdown(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                                    int mouseX, int mouseY) {
        int[] drop = choiceDropdownBounds(fr, panelX, panelY, panelW, panelH);
        if (drop == null) return;
        String[] choices = openChoice == null ? new String[0] : openChoice.getChoices();
        String[] hints = openChoice == null ? new String[0] : openChoice.getChoiceHints();
        String current = openChoice == null ? null : openChoice.getValueText();
        int x = drop[0];
        int y = drop[1];
        int w = drop[2];
        int h = drop[3];
        int itemH = drop[4];
        int visible = drop[5];

        drawSoftShadow(x, y, w, h, 12);
        drawRoundedRect(x, y, w, h, 12, 0xF71A202A);
        drawRoundedRect(x + 6, y, w - 12, 1, 1, PANEL_EDGE);

        beginScissor(x, y, w, h);
        try {
            for (int index = 0; index < choices.length && index < visible; index++) {
                String choice = choices[index];
                int itemY = y + 4 + index * itemH;
                boolean isCurrent = choice.equals(current);
                boolean hovered = isInside(mouseX, mouseY, x + 4, itemY, w - 8, itemH);
                float hp = named("drop." + index, hovered && !isCurrent);
                if (isCurrent) {
                    drawRoundedRect(x + 4, itemY, w - 8, itemH, 8, 0x26FFFFFF);
                } else if (hp > 0.02f) {
                    drawRoundedRect(x + 4, itemY, w - 8, itemH, 8, fade(ROW_HOVER, hp));
                }
                int ink = isCurrent ? TEXT_PRIMARY : mix(TEXT_SECONDARY, TEXT_PRIMARY, hp);
                drawText(FontManager.pf12bold, fr.trim(choice, w - 40), x + 14, itemY + (itemH - 12) / 2 - 1, ink);
                if (isCurrent) {
                    drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHECK,
                            x + w - 16, itemY + itemH / 2.0, 0xFF8FD4A8);
                }
                if (hints != null && index < hints.length && hints[index] != null && !hints[index].isEmpty()) {
                    double room = w - 28;
                    drawText(fr, fr.trim(hints[index], room), x + 14, itemY + itemH - 13, TEXT_TERTIARY);
                }
            }
        } finally {
            endScissor();
        }
        if (choices.length > visible) {
            // 条数太多被裁掉时给一个明确的「下面还有」记号，而不是无声截断。
            drawCenteredText(fr, "… 共 " + choices.length + " 项",
                    x + w / 2.0, y + h - 11, TEXT_TERTIARY);
        }
    }

    /** 搜索跳转定位脉冲的剩余强度（1 → 0），过期后自动清引用。 */
    private float flashPulse() {
        long now = System.currentTimeMillis();
        if (now >= flashDeadline) {
            flashEntry = null;
            return 0f;
        }
        return clamp01((flashDeadline - now) / (float) FLASH_MS);
    }

    /**
     * 把说明文字按宽度贪心折成至多两行；第二行放不下时以省略号收尾。
     * 返回数组的第二项可以为 {@code null}（一句话就装得下）。
     *
     * <p>切割下标只能取「按宽度逐字扫描」得出的原文位置，不能拿 {@code fr.trim} 的返回长度
     * 充数 —— trim 装不下时会补省略号，返回串可能比原文还长（省略号是额外字符），拿它当
     * {@code substring} 的下标就出了 {@code StringIndexOutOfBounds}（手机档窄面板必崩）。</p>
     */
    private static String[] wrapToTwoLines(CFontRenderer fr, String text, double width) {
        // 先按宽度扫描原文，得出「能完整装进一行的最大字符数」。省略号的位置交给 trim。
        int fitChars = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (fr.getStringWidth(text.substring(0, i)) > width) break;
            fitChars = i;
        }
        if (fitChars >= text.length()) return new String[] { text, null };
        // 第一行贪心装词：按空格找最后一个装得下的断点，找得到就在词间断行。
        int lastSpace = -1;
        for (int i = 0; i <= fitChars; i++) {
            if (text.charAt(i) == ' ') lastSpace = i;
        }
        int cut = lastSpace > 0 ? lastSpace : Math.max(1, fitChars);
        String first = text.substring(0, cut).trim();
        String rest = text.substring(cut).trim();
        if (rest.isEmpty()) return new String[] { fr.trim(first, width), null };
        return new String[] { first, fr.trim(rest, width) };
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = WindowAdaptiveScale.toLayoutXForced(mouseX);
            mouseY = WindowAdaptiveScale.toLayoutYForced(mouseY);
        }
        try {
            mouseReleasedImpl(mouseX, mouseY, state);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    private void mouseReleasedImpl(int mouseX, int mouseY, int state) {
        if (state == 0 && (draggingSaturationBrightness || draggingHue
                || draggingSlider != null || settingsDirty)) {
            HudConfig.save();
            settingsDirty = false;
        }
        if (state == 0 && draggingPanel) {
            draggingPanel = false;
            HudConfig.save();
        }
        if (state == 0 && resizingPanel) {
            resizingPanel = false;
            HudConfig.save();
        }
        draggingSaturationBrightness = false;
        draggingHue = false;
        draggingSlider = null;
        draggingPopoverScale = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    private void updateSliderWhileDragging(int mouseX) {
        if (draggingSlider != null && Mouse.isButtonDown(0)) {
            setSliderFromMouse(draggingSlider, mouseX, draggingSliderTrackX);
            // 快捷小窗里的宽高直接改 lyricWidth/Height：换行缓存按宽度分桶，会自动重排。
            if (draggingSlider == HudSetting.OSD_WIDTH || draggingSlider == HudSetting.OSD_HEIGHT) {
                HudSettingsModel.syncLyricViewportModuleValues();
            }
        }
        if (draggingPopoverScale && Mouse.isButtonDown(0)) {
            setPopoverScaleFromMouse(mouseX, popoverScaleTrackX, stage.selected());
        }
    }

    private void setSliderFromMouse(HudSetting setting, int mouseX, int trackX) {
        float progress = clamp01((mouseX - trackX) / (float) TRACK_W);
        setting.setValue(setting.getMin() + (setting.getMax() - setting.getMin()) * progress);
        settingsDirty = true;
    }

    private void updateColorPickerWhileDragging(int mouseX, int mouseY,
                                                int panelX, int panelY, int panelW, int panelH) {
        if (!Mouse.isButtonDown(0)) return;
        if (draggingSaturationBrightness || draggingHue) {
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
        }
    }

    private void applyPickerAt(int mouseX, int mouseY, int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        int svX = x + 12;
        int svY = y + 34;
        int hueY = svY + SV_H + 12;
        if (draggingSaturationBrightness) {
            pickerSaturation = clamp01((mouseX - svX) / (float) (SV_W - 1));
            pickerBrightness = 1.0f - clamp01((mouseY - svY) / (float) (SV_H - 1));
        }
        if (draggingHue) {
            pickerHue = clamp01((mouseX - svX) / (float) (SV_W - 1));
        }
        Color selected = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        applySelectedColor(selected);
        if (hexColorInput != null && !hexColorInput.isFocused()) {
            hexColorInput.setText(toHex(selected));
        }
    }

    private void beginColorEdit(HudSettingsModel.Entry entry) {
        editingColor = entry.colorTarget;
        Color color = entry.getColor();
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        ensureHexColorInput();
        hexColorInput.setText(toHex(color));
        hexColorInput.setFocused(false);
    }

    /** 把颜色写回当前正在编辑的那一项。声明里的 setter 只改内存，落盘在鼠标松开时统一做。 */
    private void applySelectedColor(Color selected) {
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry.kind == HudSettingsModel.Kind.COLOR && entry.colorTarget == editingColor) {
                        entry.setColor(selected);
                        settingsDirty = true;
                        return;
                    }
                }
            }
        }
    }

    // ==================== 键盘 ====================

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (page.isDismissing()) return;
        if (resetConfirmationVisible) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
                resetConfirmationVisible = false;
            }
            return;
        }
        // 下拉展开时 ESC 先收它：这是一层临时浮层，语义上它比「关掉整个界面」更近。
        if (openChoice != null && keyCode == Keyboard.KEY_ESCAPE) {
            openChoice = null;
            return;
        }
        // 其次是叠加层选中态：ESC = 只退出「编辑这一块」，再按一次才退整个界面。
        if (stage.hasSelection() && keyCode == Keyboard.KEY_ESCAPE) {
            stage.clearSelection();
            return;
        }
        // 滑条数值输入：回车提交（立即落盘），ESC 丢弃，其余交给文本框实时写回。
        if (editingSliderValue != null && sliderValueInput != null && sliderValueInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER
                    || keyCode == Keyboard.KEY_ESCAPE) {
                commitSliderValueEdit();
                return;
            }
            if (sliderValueInput.textboxKeyTyped(typedChar, keyCode)) {
                Float parsed = parseSliderInput(sliderValueInput.getText());
                if (parsed != null) {
                    editingSliderValue.setValue(parsed);
                    settingsDirty = true;
                }
            }
            return;
        }
        if (editingColor != HudSettingsModel.ColorTarget.NONE && hexColorInput != null
                && hexColorInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                hexColorInput.setFocused(false);
                if (settingsDirty) {
                    HudConfig.save();
                    settingsDirty = false;
                }
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                hexColorInput.setFocused(false);
                return;
            }
            if (hexColorInput.textboxKeyTyped(typedChar, keyCode)) {
                applyHexColorInput();
            }
            return;
        }
        if (searchInput != null && searchInput.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                clearSearch();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                searchInput.setFocused(false);
                return;
            }
            if (searchInput.textboxKeyTyped(typedChar, keyCode)) {
                searchQuery = searchInput.getText();
                searchScroll = 0;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
            beginRetire();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /**
     * 请求关闭：先播完退场，再由 {@link #drawScreen} 真的把界面回收掉。
     *
     * <p>直接 {@code setScreen(null)} 的话这一页是「啪一下消失」—— 进场有动画、退场没有，
     * 是这类界面最常见的失衡。两条时钟一起退，内容先撤、底板最后走。</p>
     */
    private void beginRetire() {
        if (page.isDismissing()) return;
        page.requestDismiss();
        list.requestDismiss();
    }

    private void applyHexColorInput() {
        if (hexColorInput == null) return;
        Color selected = parseHexColor(hexColorInput.getText());
        if (selected == null) return;
        float[] hsb = Color.RGBtoHSB(selected.getRed(), selected.getGreen(), selected.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        applySelectedColor(selected);
    }

    private static Color parseHexColor(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        if (!normalized.matches("[0-9a-fA-F]{6}")) return null;
        try {
            return new Color(Integer.parseInt(normalized, 16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ==================== 滑条数值直接输入 ====================

    /**
     * 点击滑条右端的数值文本进入输入态。再点一次同一条不重新清空（保留已敲到一半的
     * 内容），点别的滑条则把输入焦点整体挪过去。
     */
    private void beginSliderValueEdit(HudSetting setting) {
        if (editingSliderValue == setting && sliderValueInput != null
                && sliderValueInput.isFocused()) return;
        commitSliderValueEdit();
        ensureSliderValueInput();
        editingSliderValue = setting;
        sliderValueInput.setText(formatSliderInput(setting.getValue()));
        sliderValueInput.setFocused(true);
        sliderValueInput.setCursorPosition(sliderValueInput.getText().length());
    }

    /**
     * 结束输入：解析文本写回（超界交给 {@code HudSetting.setValue} 的钳制），失败则
     * 静默丢弃 —— 输入框回到正常显示。任何退出路径（回车/点外面/开取色器）都会走到这。
     */
    private void commitSliderValueEdit() {
        if (editingSliderValue == null) return;
        HudSetting setting = editingSliderValue;
        editingSliderValue = null;
        if (sliderValueInput != null) sliderValueInput.setFocused(false);
        Float parsed = parseSliderInput(sliderValueInput == null ? null : sliderValueInput.getText());
        if (parsed == null) return;
        if (parsed != setting.getValue()) {
            setting.setValue(parsed);
            settingsDirty = true;
            HudConfig.save();
        }
    }

    /** 输入框初值：不带单位的裸数字（单位在正常渲染里已有，敲的时候它是负担）。 */
    private static String formatSliderInput(float value) {
        float rounded = Math.round(value * 100f) / 100f;
        if (rounded == Math.rint(rounded)) return String.valueOf((int) rounded);
        return String.valueOf(rounded);
    }

    /**
     * 解析输入：接受裸数字与带 % 的百分数。百分数按滑条区间换算（58% 在 0.60–1.35 的
     * 滑条上是 1.047），裸数字就是设置本值 —— 两种都合法，肉眼读什么就敲什么。
     */
    private static Float parseSliderInput(String text) {
        if (text == null) return null;
        String normalized = text.trim();
        if (normalized.isEmpty()) return null;
        try {
            if (normalized.endsWith("%")) {
                String bare = normalized.substring(0, normalized.length() - 1).trim();
                if (!bare.matches("-?[0-9]+(\\.[0-9]+)?")) return null;
                return Float.parseFloat(bare) / 100f;
            }
            if (!normalized.matches("-?[0-9]+(\\.[0-9]+)?")) return null;
            return Float.parseFloat(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ==================== 恢复默认确认 ====================

    private void drawResetConfirmation(CFontRenderer fr, int mouseX, int mouseY) {
        // 压暗层走的是原版绘制管线，吃不到 layerAlpha，也不跟着那趟矩阵走 —— 想让它跟着淡就得自己乘。
        Gui.drawRect(0, 0, width, height, fade(0x6D000000, dialogGate));
        int[] box = resetDialogBounds();
        drawSoftShadow(box[0], box[1], box[2], box[3], 13);
        drawRoundedRect(box[0], box[1], box[2], box[3], 13, 0xF41A202A);
        drawRoundedRect(box[0] + 6, box[1], box[2] - 12, 1, 1, PANEL_EDGE);
        drawRoundedRect(box[0] + 16, box[1] + 18, 22, 22, 11, 0x33D96B53);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.WARNING,
                box[0] + 27, box[1] + 29, 0xFFE8836A);
        drawText(FontManager.pf12bold, "恢复默认设置？", box[0] + 46, box[1] + 20, TEXT_PRIMARY);
        drawText(fr, "歌词、灵动岛与封面的外观会恢复为默认值。", box[0] + 17, box[1] + 50, TEXT_SECONDARY);
        drawText(fr, "HUD 的位置与缩放不受影响。", box[0] + 17, box[1] + 64, TEXT_TERTIARY);

        int[] cancel = resetDialogCancel(box);
        int[] confirm = resetDialogConfirm(box);
        float cancelHover = named("dialog.cancel",
                isInside(mouseX, mouseY, cancel[0], cancel[1], cancel[2], cancel[3]));
        float confirmHover = named("dialog.confirm",
                isInside(mouseX, mouseY, confirm[0], confirm[1], confirm[2], confirm[3]));
        drawRoundedRect(cancel[0], cancel[1], cancel[2], cancel[3], 9, mix(BUTTON_BG, BUTTON_HOVER, cancelHover));
        drawRoundedRect(confirm[0], confirm[1], confirm[2], confirm[3], 9,
                mix(0xFFD94F4D, 0xFFF2756B, confirmHover));
        drawCenteredText(fr, "取消", cancel[0] + cancel[2] / 2.0, cancel[1] + 8, TEXT_PRIMARY);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.RESTORE,
                confirm[0] + 16, confirm[1] + confirm[3] / 2.0, 0xFFFFFFFF);
        drawText(fr, "恢复默认", confirm[0] + 26, confirm[1] + 8, 0xFFFFFFFF);
    }

    private int[] resetDialogBounds() {
        int w = Math.min(320, Math.max(244, width - 32));
        int h = 132;
        return new int[] { (width - w) / 2, Math.max(18, (height - h) / 2), w, h };
    }

    private int[] resetDialogCancel(int[] box) {
        return new int[] { box[0] + box[2] - 74 - 108 - 18, box[1] + box[3] - 34, 74, 23 };
    }

    private int[] resetDialogConfirm(int[] box) {
        return new int[] { box[0] + box[2] - 108 - 10, box[1] + box[3] - 34, 108, 23 };
    }

    private void handleResetConfirmationClick(int mouseX, int mouseY) {
        int[] box = resetDialogBounds();
        int[] confirm = resetDialogConfirm(box);
        if (isInside(mouseX, mouseY, confirm[0], confirm[1], confirm[2], confirm[3])) {
            HudConfig.resetLyricAppearance();
            HudConfig.resetDynamicIslandAppearance();
            HudConfig.resetCoverAppearance();
            HudConfig.resetPlayerAnimation();
            HudSettingsModel.syncAnimatedCoverModuleValue();
            try {
                MuoniumPlayerExtension.getInstance().musicLyrics.loadHudEditorSettings();
            } catch (Throwable ignored) {
                // 歌词组件还没就绪时无所谓：HudConfig 已经是新值，下次加载会跟上。
            }
            settingsDirty = false;
            HudConfig.save();
        }
        resetConfirmationVisible = false;
    }

    // ==================== HUD 舞台渲染与快捷小窗 ====================

    /** 选中块的标识色（与虚线框那套区分开：实线 + 把手 = 「正在编辑这一块」）。 */
    private static final int INFO_ACCENT = 0xFF7DD3FC;
    private static final int LYRICS_ACCENT = 0xFFA78BFA;
    /** 快捷配置小窗宽度；高度随内容（歌词 4 行控件、信息卡 3 行）由 {@link #widgetPopoverH()} 定。 */
    private static final int WIDGET_POPOVER_W = 208;

    private static int accentOf(HudOverlayStage.Kind kind) {
        return kind == HudOverlayStage.Kind.INFO ? INFO_ACCENT : LYRICS_ACCENT;
    }

    private static String titleOf(HudOverlayStage.Kind kind) {
        return kind == HudOverlayStage.Kind.INFO ? "歌曲信息" : "桌面歌词";
    }

    /** 从配置算某块叠加层当前的屏幕矩形（布局轴，与 drawScreen 的 HUD 段同一套公式）。 */
    private int[] widgetRect(HudOverlayStage.Kind kind) {
        if (kind == HudOverlayStage.Kind.INFO) {
            int w = (int) (INFO_BASE_W * HudConfig.infoScale);
            int h = (int) (INFO_BASE_H * HudConfig.infoScale);
            return new int[] { (int) (HudConfig.infoX * (RenderSystem.getLayoutWidth() - w)),
                    (int) (HudConfig.infoY * (RenderSystem.getLayoutHeight() - h)), w, h };
        }
        int w = (int) (HudConfig.lyricWidth * HudConfig.lyricScale);
        int h = (int) (HudConfig.lyricHeight * HudConfig.lyricScale);
        return new int[] { (int) (HudConfig.lyricX * (RenderSystem.getLayoutWidth() - w)),
                (int) (HudConfig.lyricY * (RenderSystem.getLayoutHeight() - h)), w, h };
    }

    private int widgetPopoverH() {
        return stage.selected() == HudOverlayStage.Kind.INFO ? 118 : 150;
    }

    private int widgetPopoverX() {
        return widgetPopoverBounds()[0];
    }

    private int widgetPopoverY() {
        return widgetPopoverBounds()[1];
    }

    private int[] widgetPopoverBounds() {
        if (!stage.hasSelection()) return new int[] { 0, 0, WIDGET_POPOVER_W, widgetPopoverH() };
        // 小窗与它锚定的 widget 同处布局轴：widgetRect 是布局坐标，屏幕跨度也必须是布局宽高
        // （width/height 此时是 GUI 逻辑或布局取决于调用点，直接用会跨轴）。
        return HudOverlayStage.popoverBounds(widgetRect(stage.selected()),
                WIDGET_POPOVER_W, widgetPopoverH(),
                (int) RenderSystem.getLayoutWidth(), (int) RenderSystem.getLayoutHeight());
    }

    /**
     * 两块叠加层的编辑态绘制：未选中画虚线框 + 名称；选中画实线描边 + 八个把手 + 尺寸气泡。
     * 几何全部来自舞台，这里只负责像素。
     */
    private void drawStageOverlays(CFontRenderer fr, int[] infoRect, int[] lyricRect, int mouseX, int mouseY) {
        drawHudSelection(fr, "歌曲信息", infoRect[0], infoRect[1], infoRect[2], infoRect[3],
                stage.selected() == HudOverlayStage.Kind.INFO ? INFO_ACCENT : 0x667DD3FC);
        drawHudSelection(fr, "桌面歌词", lyricRect[0], lyricRect[1], lyricRect[2], lyricRect[3],
                stage.selected() == HudOverlayStage.Kind.LYRICS ? LYRICS_ACCENT : 0x66A78BFA);

        if (!stage.hasSelection()) return;
        int[] r = stage.selected() == HudOverlayStage.Kind.INFO ? infoRect : lyricRect;
        int accent = accentOf(stage.selected());
        // 选中瞬间描边先亮一下再稳住：一次呼吸说明「点到了」，之后安静下来不抢预览的戏。
        if (stage.selected() != pulsedSelection) {
            pulsedSelection = stage.selected();
            selectionPulseChannel.from = selectionPulseChannel.current = 1f;
            selectionPulseChannel.to = 0f;
            selectionPulseChannel.startNanos = System.nanoTime();
        }
        float pulse = motion(selectionPulseChannel, 0f, 900.0);
        if (pulse > 0.02f) {
            drawRoundedRect(r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, 6, fade(accent, 0.30f * pulse));
        }
        // 实线描边：四条 1px 矩形拼一圈，比虚线「重」一档，表达「这一块正在被编辑」。
        drawRoundedRect(r[0], r[1], r[2], 1.5, 1, accent);
        drawRoundedRect(r[0], r[1] + r[3] - 1.5, r[2], 1.5, 1, accent);
        drawRoundedRect(r[0], r[1], 1.5, r[3], 1, accent);
        drawRoundedRect(r[0] + r[2] - 1.5, r[1], 1.5, r[3], 1, accent);
        for (int[] handle : stage.handleRects(r)) {
            boolean hovered = isInside(mouseX, mouseY, handle[0], handle[1], handle[2], handle[3]);
            drawRoundedRect(handle[0] - 1, handle[1] - 1, handle[2] + 2, handle[3] + 2, 5, 0xE6141922);
            drawRoundedRect(handle[0], handle[1], handle[2], handle[3], 4, hovered ? accent : 0xFFF2F5FA);
        }

        if (stage.bubble != null) {
            // 尺寸读数气泡：拖把手时贴着选中块上沿中央，颜色跟块的 accent。
            int textW = fr.getStringWidth(stage.bubble);
            int chipW = textW + 18;
            int chipX = Math.max(4, Math.min((int) RenderSystem.getLayoutWidth() - chipW - 4, r[0] + (r[2] - chipW) / 2));
            int chipY = Math.max(4, r[1] - 24);
            drawRoundedRect(chipX, chipY, chipW, 18, 9, 0xE61A202A);
            drawText(FontManager.pf12bold, stage.bubble, chipX + 9, chipY + 5, accent);
        }
    }

    /** 选中块旁的快捷配置小窗：宽度/高度（歌词）或整体缩放（信息卡）+ 居中/复位两个动作。 */
    private void drawWidgetPopover(CFontRenderer fr, int mouseX, int mouseY) {
        if (!stage.hasSelection() && popGate <= 0.01f) return;
        int[] pop = widgetPopoverBounds();
        HudOverlayStage.Kind kind = stage.selected();
        int accent = accentOf(kind);
        boolean lyrics = kind == HudOverlayStage.Kind.LYRICS;

        drawSoftShadow(pop[0], pop[1], pop[2], pop[3], 13);
        drawRoundedRect(pop[0], pop[1], pop[2], pop[3], 13, 0xF71A202A);
        drawRoundedRect(pop[0] + 6, pop[1], pop[2] - 12, 1, 1, PANEL_EDGE);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.RULER, pop[0] + 18, pop[1] + 16, accent);
        drawText(FontManager.pf12bold, titleOf(kind) + " · 快捷调整", pop[0] + 27, pop[1] + 12, TEXT_PRIMARY);

        int pad = 14;
        int right = pop[0] + pop[2] - pad;
        int trackX = right - VALUE_W - TRACK_W;
        int y = pop[1] + 34;
        if (lyrics) {
            drawPopoverSlider(fr, HudSetting.OSD_WIDTH, trackX, right, y, accent, mouseX, mouseY);
            y += 30;
            drawPopoverSlider(fr, HudSetting.OSD_HEIGHT, trackX, right, y, accent, mouseX, mouseY);
            y += 30;
        }
        // 整体缩放：信息卡唯一可调的尺寸维度；歌词也提供（与滚轮同一条钳制）。
        drawText(fr, "整体缩放", pop[0] + pad, y + 5, TEXT_SECONDARY);
        String scaleText = String.format("%.2f×",
                kind == HudOverlayStage.Kind.INFO ? HudConfig.infoScale : HudConfig.lyricScale);
        drawText(fr, scaleText, right - fr.getStringWidth(scaleText), y + 5, TEXT_PRIMARY);
        int sy = y + 16;
        drawRoundedRect(trackX, sy + 3, TRACK_W, 4, 2, TRACK_BG);
        float span = HudConfig.SCALE_MAX - HudConfig.SCALE_MIN;
        float value = (kind == HudOverlayStage.Kind.INFO ? HudConfig.infoScale : HudConfig.lyricScale);
        float progress = clamp01((value - HudConfig.SCALE_MIN) / span);
        double fillW = TRACK_W * progress;
        if (fillW > 0.5) drawRoundedRect(trackX, sy + 3, fillW, 4, 2, accent);
        drawRoundedRect(trackX + fillW - 5, sy - 1, 10, 10, 5, 0xFFF6F8FC);
        y += 34;

        int btnW = (pop[2] - pad * 2 - 8) / 2;
        int[] center = { pop[0] + pad, y, btnW, 22 };
        int[] reset = { pop[0] + pad + btnW + 8, y, btnW, 22 };
        drawGhostButton("wpop.center", fr, HudSettingsIcons.MOVE, "水平居中",
                center, mouseX, mouseY, TEXT_PRIMARY, accent);
        drawGhostButton("wpop.reset", fr, HudSettingsIcons.RESTORE, "复位",
                reset, mouseX, mouseY, TEXT_SECONDARY, 0xFFE58B7B);
    }

    /** 快捷小窗里的一条滑条（标签 + 值 + 轨道），样式与设置页行内滑条一致。 */
    private void drawPopoverSlider(CFontRenderer fr, HudSetting setting, int trackX, int right,
                                   int y, int accent, int mouseX, int mouseY) {
        int labelW = fr.getStringWidth(setting.getLabel());
        drawText(fr, setting.getLabel(), trackX - 10 - labelW, y, TEXT_SECONDARY);
        String value = HudSettingsModel.formatValue(setting, setting.getValue());
        drawText(fr, value, right - fr.getStringWidth(value), y, TEXT_PRIMARY);
        int sy = y + 12;
        boolean dragging = draggingSlider == setting;
        boolean hovered = isInside(mouseX, mouseY, trackX - 8, sy - 8, TRACK_W + 16, 20);
        float span = setting.getMax() - setting.getMin();
        float progress = span <= 0f ? 0f : clamp01((setting.getValue() - setting.getMin()) / span);
        drawRoundedRect(trackX, sy + 3, TRACK_W, 4, 2, TRACK_BG);
        double fillW = TRACK_W * progress;
        if (fillW > 0.5) drawRoundedRect(trackX, sy + 3, fillW, 4, 2, accent);
        double knobX = trackX + fillW;
        double radius = 5.0 + (dragging ? 1.7 : hovered ? 1.0 : 0.0);
        if (hovered || dragging) {
            drawRoundedRect(knobX - radius - 3, sy + 5 - radius - 3, (radius + 3) * 2, (radius + 3) * 2,
                    radius + 3, fade(accent, 0.24f));
        }
        drawRoundedRect(knobX - radius, sy + 5 - radius, radius * 2, radius * 2, radius, 0xFFF6F8FC);
    }

    private boolean hasLiveInfoPreview() {
        return CloudMusic.currentlyPlaying != null && CloudMusic.hasLiveTrack();
    }

    private boolean hasLiveLyricsPreview() {
        return CloudMusic.hasLiveTrack() && !CloudMusic.lyrics.isEmpty();
    }

    private void renderLiveInfoPreview(MusicInfoWidget info) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
            int[] rect = widgetRect(HudOverlayStage.Kind.INFO);
            info.renderEditorPreview(rect[0], rect[1], HudConfig.infoScale);
        } finally {
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void renderLiveLyricsPreview(MusicLyricsWidget lyrics, int x, int y) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
            lyrics.renderEditorPreview(x, y, HudConfig.lyricScale);
        } finally {
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void drawInfoPlaceholder(CFontRenderer fr, int x, int y, int w, int h) {
        drawSoftShadow(x, y, w, h, 10);
        drawRoundedRect(x, y, w, h, 10, 0xC52B3543);
        drawRoundedRect(x + 12, y + 17, 26, 26, 7, 0x554C617B);
        drawIcon(FontManager.settingsIcon32, HudSettingsIcons.NOTE, x + 25, y + 30, 0xCCD5DEEC);
        drawText(FontManager.pf12bold, "歌曲信息", x + 49, y + 17, 0xFFE7ECF4);
        drawText(fr, "播放歌曲后实时展示", x + 49, y + 33, TEXT_SECONDARY);
    }

    private void drawLyricsPlaceholder(CFontRenderer fr, int x, int y, int w, int h) {
        drawSoftShadow(x, y, w, h, 12);
        drawRoundedRect(x, y, w, h, 12, 0x8C31283F);
        int center = x + w / 2;
        drawCenteredText(FontManager.pf12bold, "桌面歌词", center, y + Math.max(8, h / 2 - 13), 0xFFDCCFFF);
        drawCenteredText(fr, "平滑动画与逐字染色预览", center, y + Math.max(23, h / 2 + 3), 0xFF9C91B8);
    }

    private void drawHudSelection(CFontRenderer fr, String title, int x, int y, int w, int h, int accent) {
        drawDashedBorder(x, y, w, h, accent);
        int labelY = Math.max(14, y - 5);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.MOVE, x + 13, labelY + 4, accent);
        drawText(fr, title, x + 21, labelY, 0xFFE8EDF6);
    }

    // ==================== 外部界面切换 ====================

    /** 切到 OneConfig 的真实 UI，并把选择记到配置里。 */
    private void switchToOneConfig() {
        HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_ONECONFIG;
        HudConfig.save();
        OneConfigHudBridge.open();
    }

    // ==================== 杂项 ====================

    private int clampCategory() {
        int count = HudSettingsModel.categories().length;
        return Math.max(0, Math.min(count - 1, categoryIndex));
    }

    /** 滚动 / 淡入的归属键：搜索态是 {@code -1}，否则是分类下标。切换它就重新起算动画。 */
    private int scrollKey() {
        return searchQuery.trim().isEmpty() ? clampCategory() : -1;
    }

    /** 搜索模式下所有分类共用一个滚动位置：结果是一张平铺的列表，跟分类无关。 */
    private int scroll() {
        if (!searchQuery.trim().isEmpty()) return searchScroll;
        if (scrollByCategory == null) return 0;
        return scrollByCategory[clampCategory()];
    }

    private void setScroll(int value, int maxScroll) {
        int clamped = Math.max(0, Math.min(maxScroll, value));
        if (!searchQuery.trim().isEmpty()) {
            searchScroll = clamped;
            return;
        }
        if (scrollByCategory != null) scrollByCategory[clampCategory()] = clamped;
    }

    private void ensureSearchInput() {
        if (searchInput == null) {
            searchInput = new GuiTextField(1, fontRendererObj, 0, 0, 100, 12);
            searchInput.setEnableBackgroundDrawing(false);
            searchInput.setMaxStringLength(28);
        }
    }

    private void ensureHexColorInput() {
        if (hexColorInput == null) {
            hexColorInput = new GuiTextField(0, fontRendererObj, 0, 0, HEX_INPUT_W - 12, HEX_INPUT_H - 8);
            hexColorInput.setEnableBackgroundDrawing(false);
            hexColorInput.setMaxStringLength(7);
        }
    }

    private void ensureSliderValueInput() {
        if (sliderValueInput == null) {
            sliderValueInput = new GuiTextField(2, fontRendererObj, 0, 0, 64, 12);
            sliderValueInput.setEnableBackgroundDrawing(false);
            sliderValueInput.setMaxStringLength(9);
        }
    }

    // ==================== 动画工具 ====================
    //
    // 这一页以前只有两种动：帧率相关的指数逼近（{@code Interpolations}）和干脆没有（切分类、
    // 确认框、取色器）。前者读起来是「先窜一下然后慢慢糊住」，而且完全不理玩家的动画速度档位；
    // 后者让最主要的动作（换分类）看起来像界面被重建了一遍。现在一律换成真毫秒定长补间，
    // 曲线与播放器换页、浮层共用 {@link TransitionCurveCache}。

    /** 一段定长过渡的状态：从哪个值起、到哪个值去、什么时候起的、现在走到哪。 */
    private static final class Channel {
        float from;
        float to;
        float current;
        long startNanos;
    }

    /**
     * 定长补间。方向中途反转时从<b>当前值</b>重新起一段完整的定长补间，而不是接着剩余时间跑 ——
     * 这正是 CSS {@code transition} 的行为，也是「悬停还没淡完就移开」不跳变的条件。
     *
     * <p>第一次见到某个键时直接把通道落在目标上：侧栏高亮条、开关旋钮都不该在打开界面的那一帧
     * 从 0 飞过来。</p>
     */
    private float motion(Channel chan, float target, double durationMs,
                         TransitionCurveCache.CachedBezier curve) {
        long now = System.nanoTime();
        double span = Math.max(1.0, durationMs / SheetClock.speed());
        double elapsed = (now - chan.startNanos) / 1_000_000.0;
        chan.current = elapsed >= span ? chan.to
                : (float) (chan.from + (chan.to - chan.from) * curve.at(elapsed / span));
        if (target != chan.to) {
            chan.from = chan.current;
            chan.to = target;
            chan.startNanos = now;
        }
        return chan.current;
    }

    private float motion(Channel chan, float target, double durationMs) {
        return motion(chan, target, durationMs, TransitionCurveCache.MOTION);
    }

    /** 按名字取一条 {@code 0..1} 的通道，见 {@link #channels}。 */
    private float motion(String key, float target, double durationMs,
                         TransitionCurveCache.CachedBezier curve) {
        Channel chan = channels.get(key);
        if (chan == null) {
            chan = new Channel();
            chan.from = chan.to = chan.current = target;
            chan.startNanos = System.nanoTime();
            channels.put(key, chan);
            return target;
        }
        return motion(chan, target, durationMs, curve);
    }

    private float motion(String key, float target, double durationMs) {
        return motion(key, target, durationMs, TransitionCurveCache.MOTION);
    }

    /**
     * 命名部件的悬停进度。
     *
     * <p>{@code key} 必须每帧稳定，否则每帧都会从头开始动。</p>
     */
    private float named(String key, boolean active) {
        return motion(key, active ? 1f : 0f, HOVER_MS);
    }

    /**
     * 条目行的三条通道：{@code [0]} 悬停、{@code [1]} 开关旋钮、{@code [2]} 滑块把手。
     * 与 {@link #rowProgress} 一一对应，见那里的说明。
     *
     * <p>旋钮通道在第一次见到这个条目时就落在它当前的状态上：否则每次打开界面都会看到所有
     * 已开启的开关一起从左滑到右。</p>
     */
    private Channel[] channelsOf(HudSettingsModel.Entry entry) {
        Channel[] chans = rowChannels.get(entry);
        if (chans == null) {
            chans = new Channel[] { new Channel(), new Channel(), new Channel() };
            chans[1].from = chans[1].to = chans[1].current = entry.isOn() ? 1f : 0f;
            rowChannels.put(entry, chans);
        }
        return chans;
    }

    /** 条目行的动画状态，见 {@link #rowProgress}。真正的「走到哪了」在 {@link #channelsOf} 那三条通道里。 */
    private float[] progressOf(HudSettingsModel.Entry entry) {
        float[] state = rowProgress.get(entry);
        if (state == null) {
            state = new float[] { 0f, 0f, 0f };
            rowProgress.put(entry, state);
        }
        return state;
    }

    /**
     * 把 {@link #layerAlpha} 临时压到 {@code outer * gate}，返回进入前的值。
     *
     * <p>调用方必须在 {@code finally} 里还回去：这一层的淡入涉及几十个绘制调用，漏一处就是
     * 「有一块不跟着淡」的破口。</p>
     */
    private float pushAlpha(float gate) {
        float outer = layerAlpha;
        layerAlpha = outer * clamp01(gate);
        return outer;
    }

    /** 在 {@link #pageFade} 这条 t 上开一个缓动过的时间窗。 */
    private float gate(double first, double travel) {
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(pageFade, first,
                Math.min(first + travel, 1.0)));
    }

    /** 侧栏第 {@code index} 行的到场进度。行与行等间距错峰，超过上限的并到最后一档。 */
    private float navGate(int index) {
        double first = NAV_FIRST + Math.min(index, NAV_STAGGER_LIMIT) * NAV_STEP;
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(pageFade, first,
                Math.min(first + NAV_TRAVEL, 1.0)));
    }

    /**
     * 内容列第 {@code slot} 块（一张卡连同它里面的行）这一帧该有多实。
     *
     * <p>窗口右端必须钉在 1.0 以内：第 12 档的起点是 .74，加上 .54 的行程就是 1.28，而这条 t
     * 最多只走到 1.0 —— 那一块就会永久停在半透明、偏几个像素的位置上，正是「切到某个分类
     * 底下有几张卡一直是虚的」那种查起来最费劲的错。</p>
     */
    private float revealOf(int slot) {
        double first = ROW_FIRST + Math.min(slot, ROW_STAGGER_LIMIT) * ROW_STEP;
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(listFade, first,
                Math.min(first + ROW_TRAVEL, 1.0)));
    }

    /** 进场那一段横向偏移：往下一个分类时新的从右边来，往上一个是左边；首次到场不偏。 */
    private float slideX(float gate) {
        return listDirection * (1f - gate) * CONTENT_SLIDE;
    }

    /** 进场那一段下坠：和淡入一起才有「落进来」，只淡入就是一块布亮起。 */
    private float slideY(float gate) {
        return (1f - gate) * CONTENT_DROP;
    }

    /** 把颜色的 alpha 按比例压低（保留原有 alpha 作为上限）。 */
    private static int fade(int color, float alpha) {
        int base = (color >>> 24) & 0xFF;
        int a = Math.round(base * clamp01(alpha));
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    /** 在两个 ARGB 之间线性插值，alpha 也一起插。 */
    private static int mix(int from, int to, float t) {
        float k = clamp01(t);
        int a = channel(from, 24) + Math.round((channel(to, 24) - channel(from, 24)) * k);
        int r = channel(from, 16) + Math.round((channel(to, 16) - channel(from, 16)) * k);
        int g = channel(from, 8) + Math.round((channel(to, 8) - channel(from, 8)) * k);
        int b = channel(from, 0) + Math.round((channel(to, 0) - channel(from, 0)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int color, int shift) {
        return (color >>> shift) & 0xFF;
    }

    /**
     * 在给定底色上可读的墨色：亮底给近黑、暗底给近白。
     *
     * <p>用 Rec.601 的感知亮度而不是三通道平均：纯蓝（平均 85）其实看起来比纯绿（平均 85）暗得多，
     * 按平均值挑会在蓝色主题上给出对比度不足的深色图标。</p>
     */
    private static int contrastOn(int background) {
        float luma = (channel(background, 16) * 0.299f
                + channel(background, 8) * 0.587f
                + channel(background, 0) * 0.114f) / 255f;
        return luma > 0.58f ? 0xFF101720 : 0xFFF4F7FB;
    }

    // ==================== 绘制原语 ====================

    /**
     * 画一枚图标，按 em 方格居中。
     *
     * <p>为什么不按字形自己的墨迹盒居中：这份图标字体保留了 Material 24 网格里的原始位置，
     * 细长的 chevron 与满格的实心图形墨迹高度差好几倍，各自居中会让 chevron 被「撑」到和实心块
     * 一样大。改成量 {@link HudSettingsIcons#LOCATOR}（一枚满格实心方块）的墨迹盒 —— 它就等于
     * em 方格相对 {@code drawString} 原点的位置 —— 再让这个方格居中，所有图标于是落在与 Material
     * 设计一致的对齐网格上。量测按字号缓存，量不出来就退回行盒居中（位置略偏，但图标仍在）。</p>
     */
    private void drawIcon(CFontRenderer font, String glyph, double centerX, double centerY, int color) {
        if (font == null || glyph == null || glyph.isEmpty()) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        GlyphInkMetrics square = emSquare(font);
        double x;
        double y;
        if (square != null) {
            x = square.alignCenterX(centerX);
            y = square.alignCenterY(centerY);
        } else {
            x = centerX - font.getStringWidthD(glyph) * .5;
            y = centerY - font.getFontHeight() * .5;
        }
        font.drawString(glyph, x, y, tinted);
    }

    private static GlyphInkMetrics emSquare(CFontRenderer font) {
        synchronized (GRID_CACHE) {
            if (GRID_CACHE.containsKey(font)) {
                return GRID_CACHE.get(font);
            }
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, HudSettingsIcons.LOCATOR.charAt(0));
        synchronized (GRID_CACHE) {
            GRID_CACHE.put(font, measured);
        }
        return measured;
    }

    private void drawRoundedRect(double x, double y, double w, double h, double radius, int color) {
        if (w <= 0 || h <= 0) return;
        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;
        if (alpha <= 0.002f) return;
        SkijaShapes.roundedRect(x, y, w, h, Math.min(radius, Math.min(w, h) * 0.5),
                SkijaGfx.argb(((color >>> 16) & 0xFF) / 255.0F, ((color >>> 8) & 0xFF) / 255.0F,
                        (color & 0xFF) / 255.0F, alpha));
        SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
    }

    private void drawSoftShadow(int x, int y, int w, int h, int radius) {
        drawRoundedRect(x + 1, y + 2, w, h, radius, 0x30000000);
        drawRoundedRect(x + 2, y + 3, w, h, radius, 0x18000000);
    }

    private void drawText(CFontRenderer font, String text, double x, double y, int color) {
        if (font == null || text == null) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        font.drawString(text, x, y, tinted);
    }

    private void drawCenteredText(CFontRenderer font, String text, double centerX, double y, int color) {
        if (font == null || text == null) return;
        drawText(font, text, centerX - font.getStringWidthD(text) * .5, y, color);
    }

    /** 当前压了几层裁剪。{@link #endScissor()} 要靠它做到「多余的 end 不出错」。 */
    private int scissorDepth;

    /**
     * 进入一块矩形裁剪区。
     *
     * <p>旧实现要自己把 GUI 坐标乘上缩放因子、再把 y 翻转成 OpenGL 的左下原点，
     * 才能调 {@code glScissor}；还要提前钳位，否则负宽高会让 GL 抛 1281。
     * Skia 的 {@code clipRect} 直接吃当前坐标系（也就是 GUI 坐标），
     * 缩放与翻转都由宿主层一次性处理掉了，这里只剩下「宽高非法时不裁」这一条业务判断。
     */
    private void beginScissor(int x, int y, int w, int h) {
        // 裁剪层无条件压一层，好和 endScissor 配对。宽高非法时退化成「不裁剪」，
        // 与旧实现关掉 GL_SCISSOR_TEST 的效果一致——画多一点比整块内容凭空消失容易发现。
        if (w <= 0 || h <= 0) {
            // 宽高非法时退化成「全画布不裁」：编辑器全程在布局括号内，用布局轴访问器
            // 把语义钉死，不依赖括号状态。
            SkijaClip.scissorBegin(0, 0, (int) RenderSystem.getLayoutWidth(),
                    (int) RenderSystem.getLayoutHeight());
        } else {
            SkijaClip.scissorBegin(x, y, w, h);
        }
        scissorDepth++;
    }

    /** 退出裁剪区。多调用不会出错：旧实现的 {@code glDisable(GL_SCISSOR_TEST)} 也是幂等的。 */
    private void endScissor() {
        if (scissorDepth > 0) {
            scissorDepth--;
            SkijaClip.scissorEnd();
        }
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static void drawDashedBorder(int x, int y, int w, int h, int color) {
        int seg = 6;
        int gap = 4;
        for (int i = x; i < x + w; i += seg + gap) {
            Gui.drawRect(i, y, Math.min(i + seg, x + w), y + 1, color);
            Gui.drawRect(i, y + h - 1, Math.min(i + seg, x + w), y + h, color);
        }
        for (int i = y; i < y + h; i += seg + gap) {
            Gui.drawRect(x, i, x + 1, Math.min(i + seg, y + h), color);
            Gui.drawRect(x + w - 1, i, x + w, Math.min(i + seg, y + h), color);
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String pct(float x, float y, float scale) {
        return (int) (x * 100) + "%, " + (int) (y * 100) + "%, " + (int) (scale * 100) + "%";
    }
}
