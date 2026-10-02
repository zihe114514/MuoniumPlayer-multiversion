package com.muoniumplayer.core.screens.ncm;

import lombok.Getter;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.gl.GL11;
import today.opai.api.features.ExtensionScreen;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.FoliaModelNotice;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.screens.GuiAnnouncements;
import com.muoniumplayer.core.screens.ncm.panels.ControlsBar;
import com.muoniumplayer.core.screens.ncm.panels.HomePanel;
import com.muoniumplayer.core.screens.ncm.panels.NavigateBar;
import com.muoniumplayer.core.screens.ncm.panels.NavigationLoadingOverlay;
import com.muoniumplayer.core.screens.ncm.panels.PlaylistPanel;
import com.muoniumplayer.core.screens.ncm.panels.PersonalFmPanel;
import com.muoniumplayer.core.screens.ncm.panels.PlayQueuePanel;
import com.muoniumplayer.core.utils.cursor.CursorUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.MpPartyOverlay;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import com.muoniumplayer.render.skija.SkijaGfx;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/16 19:47
 */
public class NCMScreen extends ExtensionScreen implements SharedConstants, SharedRenderingConstants {

    /** 统一的播放器外框线宽；内容裁剪使用同一尺寸，避免模糊污染外框。 */
    public static final double PLAYER_BORDER_THICKNESS = .8;

    /** 侧栏/控制栏等基础控件树是否已经在后台 HUD 帧预热过。 */
    private static boolean layoutPrewarmed;
    /** 预热后的第一次 initGui 可以复用这棵基础控件树；后续 markDirty 仍要完整重建。 */
    private static boolean skipNextLayoutAfterPrewarm;

    @Getter
    private static NCMScreen instance = new NCMScreen();

    float alpha = 0f;
    boolean closing = false;

    Panel basePanel = new Panel();

    @Getter
    NavigateBar playlistsPanel;

    RectWidget currentPanelBg = new RectWidget();

    /**
     * 换页转场的历史基准时长（毫秒）。实际时长还要除以用户配置的「界面切换动画速度」
     * （{@link HudConfig#playerUiSwitchSpeed}，默认 3.0），与 {@code CoverHeroTransition} 同一口径：
     * 默认观感约 173ms，把倍率调回 1.0 就是这条基准值。
     */
    private static final double PANEL_TRANSITION_MS = 520.0;
    /** 横向推入/推出的幅度（设计像素）。整页位移靠它交代「这一页是从哪边来的」。 */
    private static final double PANEL_SLIDE_DISTANCE = 26.0;
    /** 新页落位前残余的放大余量：给位移一点纵深，又不退回成原来那种「缩放淡入」。 */
    private static final double PANEL_ENTER_SCALE_OVERSHOOT = .02;

    /** 转场期间被换下去的那张旧页。它和新页<b>同时</b>画，见渲染里的换页段。 */
    NCMPanel prevAnimatingPanel = null;
    NCMPanel currentPanel = null;
    /** 旧页被换下去那一刻的不透明度。中途再切页时它小于 1，转场就从这里接着往下淡。 */
    float prevAnimatingPanelAlpha = 0f;
    /** 本次换页的进度：0 = 刚切页，1 = 已经停稳。没有旧页时它就是 1。 */
    private double panelTransitionT = 1.0;
    /** 这次换页是「后退」。位移方向要镜像，否则返回键看起来像又前进了一页。 */
    private boolean panelTransitionBackward;
    /**
     * 下一次换页按后退播。
     *
     * <p>历史栈里的条目是 {@link #actions} 存的 {@code Runnable}，它们不知道自己是被点出来的还是被
     * 返回键退出来的，所以方向只能由调用方先声明、换页时取走并清掉。</p>
     */
    private boolean pendingNavigationBackward;

    @Getter
    ControlsBar controlsBar;

    /** 底部“下一首播放”抽屉；与 controlsBar 一样由本屏幕直接渲染，不属于 basePanel。 */
    @Getter
    PlayQueuePanel playQueuePanel;

    public MusicLyricsPanel musicLyricsPanel = null;

    /** 主题色插值由 NCMTheme 负责；此渲染器只负责按钮起点的波浪与玻璃高光。 */
    private final ThemeTransitionRenderer themeTransitionRenderer = new ThemeTransitionRenderer();

    /**
     * 播放器尺寸通过真实布局边界动画，而不是在最外层叠加 GL scale。
     * 这样鼠标命中、Stencil 裁剪、Framebuffer 和文字换行始终共享同一坐标系。
     */
    private double animatedPanelScale = -1.0;
    private double panelWidth = -1.0;
    private double panelHeight = -1.0;

    /**
     * 加入歌单弹窗（模态覆盖层）。为 null 表示未打开。
     */
    public AddToPlaylistOverlay addToPlaylistOverlay = null;

    /** 双平台账号管理（含二维码登录二级页面）的模态覆盖层。 */
    public AccountManagerOverlay accountManagerOverlay = null;

    /** 二级音乐来源菜单：固定内容平台与 GD音乐台 聚合音源统一在此处管理。 */
    public MusicSourceOverlay musicSourceOverlay = null;

    /** 一起听房间面板。与来源/账号面板同层，仅在网易云官方源下可以打开。 */
    public ListenTogetherOverlay listenTogetherOverlay = null;

    /** 服务器一起听（mpsync 服务端 mod）的房间面板。与网易云一起听同层，互不相干。 */
    public MpPartyOverlay mpPartyOverlay = null;

    /** 主题调色板：主题按钮右键打开的二级菜单，实时预览主题颜色配置项。 */
    public ThemePaletteOverlay themePaletteOverlay = null;

    /** 界面字体选择：与主题调色板同层，换字体后整个界面（含本浮层）当帧生效。 */
    public FontPickerOverlay fontPickerOverlay = null;

    /** 全渠道歌单导入面板：侧栏「导入歌单」标题行「＋」打开，自动识别平台，全程匿名。 */
    public PlaylistImportOverlay playlistImportOverlay = null;

    /** 建一张本地歌单：只要一个名字，曲目之后从任意音源加进来。与导入面板互斥打开。 */
    public PlaylistCreateSheet playlistCreateSheet = null;

    /**
     * 歌词搜索弹窗：给正在放的这首歌手动挑一份歌词（四张源标签 + 候选列表 + 预览）。
     *
     * <p>它和导入面板是同一类东西 —— 带可编辑输入框的模态，所以键盘、滚轮、ESC 都按那套走。
     * 层级放在导入面板之上、账号管理之下：从侧栏打开它时不该被导入面板挡住。</p>
     */
    public LyricSearchOverlay lyricSearchOverlay = null;


    /** 对取消收藏等破坏性操作的二次确认层。 */
    public ConfirmationOverlay confirmationOverlay = null;

    /**
     * 「还没装 Folia 分析模型」的一次性提示层。
     *
     * <p>与二次确认同层、同样独占输入。只在<b>本次启动第一次打开播放器</b>时挂上去一次，
     * 判定与闸门都在 {@link FoliaModelNotice}。</p>
     */
    public ModelInstallNoticeOverlay modelInstallNoticeOverlay = null;

    /**
     * 表示是否需要重新布局, 当用户信息和用户歌单加载完设置为 true,
     * 然后会自动进行重新布局并设为 false
     */
    private boolean dirty = true;

    public NCMScreen() {

    }

    @Override
    public void initGui() {
        alpha = 0f;
        closing = false;

        this.checkDirty();
        PlayerIslandTransition.open(HudConfig.playerFadeSpeed);

        if (this.musicLyricsPanel != null)
            this.musicLyricsPanel.onInit();

        Keyboard.enableRepeatEvents(true);
        CursorUtils.setCursor(CursorUtils.ARROW);
    }

    public void markDirty() {
        this.dirty = true;
        skipNextLayoutAfterPrewarm = false;
    }

    /**
     * 在 HUD 已经稳定渲染后提前构建基础控件树。
     *
     * <p>只预热外框、侧栏、控制栏和队列抽屉，不调用 HomePanel#onInit，
     * 因此不会提前发起首页网络请求。打开播放器时 initGui 仍会创建内容页，
     * 但最重的一段控件树构造已经不在动画第一帧里。</p>
     */
    public static void prewarmLayout() {
        if (layoutPrewarmed) return;
        layoutPrewarmed = true;
        skipNextLayoutAfterPrewarm = true;
        getInstance().layout();
    }

    public void checkDirty() {
        if (this.dirty) {
            this.dirty = false;

            /*
             * 刷新帐号/歌单会重建侧栏和控制栏，但内容面板并不属于 basePanel，
             * 因此可以安全保留。此前这里无条件 new HomePanel()，导致用户在
             * 歌单详情、搜索结果等页面点击刷新后被强制送回主页。
             */
            NCMPanel panelBeforeLayout = this.currentPanel;
            if (skipNextLayoutAfterPrewarm) {
                skipNextLayoutAfterPrewarm = false;
            } else {
                this.layout();
            }

            if (panelBeforeLayout == null) {
                // 首次打开播放器仍默认进入主页。
                this.setCurrentPanel(new HomePanel());
            } else if (this.playlistsPanel != null) {
                // 新侧栏需要重新映射当前面板的选中状态；内容页面保持原样。
                this.playlistsPanel.selectCurrentPanel(panelBeforeLayout);
            }
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        PlayerIslandTransition.abort();
        // 提示层不跨「一次打开播放器」存活：下次打开时由 maybeShowModelNotice 重新决定要不要弹，
        // 而「本次启动已弹过」的闸门在 FoliaModelNotice 里，所以这里清掉不会导致重复提示。
        this.modelInstallNoticeOverlay = null;
    }

    public void layout() {
        this.basePanel.getChildren().clear();

        // 真玻璃的底色必须排在面板底色<em>之前</em>：它是「糊过的游戏画面」，下面那张
        // GENERIC_BACKGROUND 是压在它上面的主题色。顺序反了就等于拿实色盖住背景，
        // 玩家只会看到「开了琉璃什么也没变」。
        ThemeGlassBackdropWidget glassBackdrop = new ThemeGlassBackdropWidget();
        glassBackdrop.setBeforeRenderCallback(() -> glassBackdrop.setBounds(
                0, 0, glassBackdrop.getParentWidth(), glassBackdrop.getParentHeight()));
        this.basePanel.addChild(glassBackdrop);

        RoundedRectWidget bg = new RoundedRectWidget();
        bg.setBeforeRenderCallback(() -> {
            bg.setBounds(0, 0, bg.getParentWidth(), bg.getParentHeight());
            bg.setRadius(this.getPlayerCornerRadius());
            bg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            // 液态玻璃保留足够实体感，同时让游戏画面能轻微透入形成材质层次。
            // 真玻璃那一档再把这层按「玻璃浓度」压薄，让背后那张真画面透出来；
            // 浓度推到 1 时压薄系数正好是 1，也就是完全退回上面这个数。
            bg.setAlpha(GlassLook.tintedAlpha(GlassLook.Surface.PLAYER,
                    1.0f - NCMTheme.getLiquidGlassAmount() * 0.14f));
        });
        this.basePanel.addChild(bg);

        ThemeGlassWidget glassSurface = new ThemeGlassWidget();
        glassSurface.setBeforeRenderCallback(() ->
                glassSurface.setBounds(0, 0, glassSurface.getParentWidth(), glassSurface.getParentHeight()));
        this.basePanel.addChild(glassSurface);

        this.basePanel.setBeforeRenderCallback(() -> this.basePanel.center());

        this.playlistsPanel = new NavigateBar();
        this.basePanel.addChild(this.playlistsPanel);

        this.basePanel.addChild(this.currentPanelBg);

        this.currentPanelBg.setBeforeRenderCallback(() -> {
            double controlsHeight = Math.min(this.getPanelHeight(), Math.max(28.0, this.getPanelHeight() * .07));
            this.currentPanelBg.setBounds(
                    playlistsPanel.getWidth(),
                    0,
                    Math.max(0.0, this.currentPanelBg.getParentWidth() - playlistsPanel.getWidth()),
                    Math.max(0.0, this.getPanelHeight() - controlsHeight)
            );
            this.currentPanelBg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            // 这一层是<em>叠在 bg 之上</em>的第二张底，走内层那一档：两档共用一条曲线时，
            // 两张 0.6 的深色叠完就是 0.85，玩家管这个叫「外面是玻璃，页面里面一块黑斑」。
            this.currentPanelBg.setAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER,
                    1.0f - NCMTheme.getLiquidGlassAmount() * 0.24f));
        });

        this.controlsBar = new ControlsBar();
        this.controlsBar.onInit();

        this.playQueuePanel = new PlayQueuePanel();
        this.playQueuePanel.onInit();
    }

    public double getSpacing() {
        return 16.0;
    }

    public double getPlayerBorderThickness() {
        return PLAYER_BORDER_THICKNESS;
    }

    public double getPlayerCornerRadius() {
        return Math.max(8.0, Math.min(18.0, this.getPanelHeight() * .028));
    }

    public double getPanelWidth() {
        if (this.panelWidth > 0) return this.panelWidth;
        return this.getAvailablePanelWidth() * this.getEffectiveTargetScale();
    }

    public double getPanelHeight() {
        if (this.panelHeight > 0) return this.panelHeight;
        return this.getAvailablePanelHeight() * this.getEffectiveTargetScale();
    }

    public double getPanelX() {
        return RenderSystem.getWidth() * .5 - this.getPanelWidth() * .5;
    }

    public double getPanelY() {
        return RenderSystem.getHeight() * .5 - this.getPanelHeight() * .5;
    }

    private double getAvailablePanelWidth() {
        return Math.max(1.0, RenderSystem.getWidth() - this.getSpacing() * 2);
    }

    private double getAvailablePanelHeight() {
        return Math.max(1.0, RenderSystem.getHeight() - this.getSpacing() * 2);
    }

    /** 返回用户选择的真实尺寸；响应式子组件负责在较小面板中重新排版。 */
    private double getEffectiveTargetScale() {
        return NCMPlayerConfig.getPlayerScale();
    }

    private void updatePanelScaleAnimation() {
        double targetScale = this.getEffectiveTargetScale();
        if (this.animatedPanelScale < 0) {
            this.animatedPanelScale = targetScale;
        } else {
            this.animatedPanelScale = Interpolations.interpolate(this.animatedPanelScale, targetScale, .18f);
            if (Math.abs(this.animatedPanelScale - targetScale) < .0005) {
                this.animatedPanelScale = targetScale;
            }
        }

        this.panelWidth = this.getAvailablePanelWidth() * this.animatedPanelScale;
        this.panelHeight = this.getAvailablePanelHeight() * this.animatedPanelScale;
    }

    @Override
    public void drawScreen(int mX, int mY) {
        // 响应式布局：GuiScreen 打开期间 RenderGameOverlayEvent 不触发，width/height/scaleFactor
        // 停留在上一次游戏帧值。每帧刷新 ScaledResolution，保证窗口 resize / GUI Scale 变化后
        // 布局与鼠标坐标映射立即正确。
        RenderSystem.refreshResolution();
        // 所有播放器几何和过渡目标都必须在自适应布局中捕获。
        //在 updatePanelScaleAnimation()/tick() 把形变目标留在 Minecraft
        //GUI 坐标后进入它，而渲染变换期望自适应坐标。
        WindowAdaptiveScale.begin();
        this.updatePanelScaleAnimation();
        // 两条时钟互不接管：外层从岛展开/收回，内部主页 -> 全屏歌词页。
        PlayerIslandTransition.tick();
        CoverHeroTransition.tick(this.musicLyricsPanel != null);

        if (closing && PlayerIslandTransition.isFinished())
            api.displayScreen(null);

        alpha = (float) PlayerIslandTransition.contentAlpha();

        CursorUtils.resetOverride();

//        Shaders.GAUSSIAN_BLUR_SHADER.run(Collections.singletonList(() -> {
//            Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), hexColor(1, 1, 1, alpha));
//        }));

        this.checkDirty();

        // 「本次启动第一次打开播放器」的模型提示。放在这里而不是 initGui：岛屿展开动画跑完
        // 才算真的「打开了播放器」，在动画中间糊一张卡片会跟展开过程打架。内部自带
        // 「本次启动只弹一次」「确实还缺组件」「没有别的模态占着」三道闸，条件不满足时
        // 只是一次布尔判断。
        this.maybeShowModelNotice();

        int dWheel = Mouse.getDWheel();

        // —— GL 状态隔离（关键修复：播放器打开/关闭时半透明元素发白）——
        // 在界面边界压一层绘制状态与变换：内部有 push/pop 不配对的写法，
        // 压一层能把影响限制在这一屏之内。
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();

        // 复位到已知的 2D 状态，避免继承上一段绘制留下的混合模式与当前颜色。
        SkijaGfx.setBlendMode(io.github.humbleui.skija.BlendMode.SRC_OVER);
        SkijaGfx.setColor(1.0F, 1.0F, 1.0F, 1.0F);

        api.getGLStateManager().pushMatrix();
        double xScale = RenderSystem.getWidthNotScaled() / RenderSystem.getWidth();
        double yScale = RenderSystem.getHeightNotScaled() / RenderSystem.getHeight();
        double mouseX = mX / xScale;
        double mouseY = mY / yScale;
        api.getGLStateManager().scale(xScale, yScale, 1);

        // 弹窗为模态：滚轮只应作用于弹窗自己的列表，不能穿透到下层的 basePanel/controlsBar/歌词页。
        // 弹窗是居中绘制在歌单之上的，鼠标停在弹窗列表上时同样落在下层列表里，不拦截就会两层一起滚。
        // 队列抽屉同理：它盖在内容页上，滚它的时候下面的歌单必须停住。
        boolean queueCapturesWheel = this.playQueuePanel != null
                && this.playQueuePanel.capturesWheel(mouseX, mouseY);
        int baseDWheel = (isModalOverlayActive() || queueCapturesWheel) ? 0 : dWheel;
        int lyricsDWheel = isModalOverlayActive() ? 0 : dWheel;
        int queueDWheel = isModalOverlayActive() ? 0 : dWheel;

        double[] surface = PlayerIslandTransition.surfaceBounds();
        double surfaceX = surface[0];
        double surfaceY = surface[1];
        double surfaceWidth = surface[2];
        double surfaceHeight = surface[3];
        double surfaceRadius = surface[4];
        double contentScale = PlayerIslandTransition.contentScale();
        double surfaceCenterX = surfaceX + surfaceWidth * .5;
        double surfaceCenterY = surfaceY + surfaceHeight * .5;
        double targetCenterX = this.getPanelX() + this.getPanelWidth() * .5;
        double targetCenterY = this.getPanelY() + this.getPanelHeight() * .5;

        this.basePanel.setBounds(this.getPanelWidth(), this.getPanelHeight());
        this.basePanel.setPosition(this.getPanelX(), this.getPanelY());
        final double cornerRadius = this.getPlayerCornerRadius();

        // 表面必须在内容 alpha 变为非零之前存在。否则灵动岛形变的
        //首尾 10% 在几何上在动，视觉上却不可见。
        PlayerIslandTransition.renderSurface();

        // 低于 3.5% 时内容不可察觉。在此跳过整个组件树，避免
        //在形变首尾帧支付封面/歌词/队列的工作，
        //而那恰是音乐播放最可能挤压帧预算的时候。
        if (alpha > .035f) {
            // 裁剪必须在缩放矩阵外：surfaceBounds 已经是屏幕坐标；
            // 矩阵只负责把 target 面板内容压进这块 surface。
            StencilClipManager.beginClip(() -> roundedRect(surfaceX, surfaceY,
                    surfaceWidth, surfaceHeight, surfaceRadius, -1));

            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(surfaceCenterX, surfaceCenterY, 0);
            api.getGLStateManager().scale(contentScale, contentScale, 1);
            api.getGLStateManager().translate(-targetCenterX, -targetCenterY, 0);

            // 转场期间主界面内容整体微微放大（见 CoverHeroTransition#mainScale，最高 +1.6%）。
            // 放大而不是缩小：裁剪形状在上面已经录成面板矩形，缩小会让圆角内侧漏出游戏画面，
            // 放大只会被裁掉，永远不漏。飞行封面与歌词页不在这层矩阵里，各自走自己的缩放。
            api.getGLStateManager().pushMatrix();
            this.scaleAtPos(basePanel.getX() + basePanel.getWidth() * .5,
                    basePanel.getY() + basePanel.getHeight() * .5, CoverHeroTransition.mainScale());

            if (this.musicLyricsPanel == null || this.musicLyricsPanel.alpha <= .9f) {
                this.basePanel.setAlpha(alpha);
                this.basePanel.renderWidget(mouseX, mouseY, baseDWheel);
                // 在子组件/帧缓冲渲染后重新断言根圆角裁剪。
                StencilClipManager.restoreActiveClip();

                // 换页转场：一条真时钟，两页<b>同时</b>画。
                //
                // 原来这两支是 if / else if 串行的 —— 旧页淡完才轮到新页淡进来，中间必然露一帧空底，
                // 看着像「关一页再开一页」；而且两边的 alpha 都走 Interpolations 的指数逼近，既没有
                // 时长也没有缓动，默认速度倍率 3.0 下那个插值系数已经被钳到 .95，一两帧就吃光，
                // 整段动画实际上看不见。现在照 CoverHeroTransition 的写法：t 按帧间隔累加到 1，
                // 位移与不透明度读同一条 MOTION 曲线，旧页向左推出去、新页从右边推进来。
                if (this.prevAnimatingPanel != null) {
                    this.panelTransitionT = Math.min(1.0,
                            this.panelTransitionT + panelTransitionStepMillis() / panelTransitionMillis());
                }
                final double transitionEased = TransitionCurveCache.MOTION.at(this.panelTransitionT);
                final double slideDistance = PANEL_SLIDE_DISTANCE * (this.panelTransitionBackward ? -1.0 : 1.0);
                double panelCenterX = this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5;
                double panelCenterY = this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5;

                if (this.prevAnimatingPanel != null) {
                    // 中途再切一次页时，这张旧页可能还在自己淡进来的半路上。从它<b>当前</b>的不透明度
                    // 接着往下淡（prevAnimatingPanelAlpha 记的就是被换下去那一刻的值），而不是先跳回
                    // 全亮再淡 —— 后者连点两个歌单就会看见上一页闪一下。
                    this.prevAnimatingPanel.setAlpha(Math.min(this.basePanel.getAlpha(),
                            this.prevAnimatingPanelAlpha * (float) (1.0 - transitionEased)));
                    this.prevAnimatingPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                            this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                    // 位移必须裁在内容区里：旧页原来不裁，是因为它压根不位移；现在推出去 26 像素
                    // 不裁就会糊到左侧导航栏上。
                    StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(),
                            this.currentPanelBg.getY(), this.currentPanelBg.getWidth(),
                            this.currentPanelBg.getHeight(), -1));

                    api.getGLStateManager().pushMatrix();
                    api.getGLStateManager().translate(-slideDistance * transitionEased, 0.0, 0.0);
                    this.prevAnimatingPanel.renderWidget(mouseX, mouseY, baseDWheel);
                    api.getGLStateManager().popMatrix();

                    StencilClipManager.endClip();

                    if (this.panelTransitionT >= 1.0) {
                        this.prevAnimatingPanel = null;
                    }
                }

                if (this.currentPanel != null) {
                    this.currentPanel.setAlpha(Math.min(this.basePanel.getAlpha(), (float) transitionEased));
                    this.currentPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                            this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                    StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));

                    api.getGLStateManager().pushMatrix();
                    api.getGLStateManager().translate(slideDistance * (1.0 - transitionEased), 0.0, 0.0);
                    // 只留一点点放大余量：原来那 1.1→1.0 的整页缩放是「缩放淡入」显呆的另一半原因，
                    // 它和位移叠在一起会让文字在转场里发糊。2% 收到 1 只给纵深，不抢位移。
                    this.scaleAtPos(panelCenterX, panelCenterY,
                            1.0 + PANEL_ENTER_SCALE_OVERSHOOT * (1.0 - transitionEased));

                    this.currentPanel.renderWidget(mouseX, mouseY, baseDWheel);
                    api.getGLStateManager().popMatrix();

                    StencilClipManager.endClip();
                }

                // 接力封面画在两页之上、仍裁在内容区里。这层裁剪是最后一道保险，不是主要手段：
                // 真正保证画面不漏出去的是 PlaylistCoverHero 自己拒绝对不上视口的起落点（见它的
                // {@code viewport} 参数）。进度直接喂转场那条 eased，封面才和新页同一次落地。
                StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                        this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));
                PlaylistCoverHero.render(transitionEased);
                StencilClipManager.endClip();

                // 导航等待遮罩：盖在内容页之上，并且裁在内容区里 —— 失败提示可能比内容区还宽，
                // 不裁会糊到左侧导航栏或控制栏上。
                if (this.navigationLoading.isVisible()) {
                    StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                            this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));
                    this.navigationLoading.render(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                            this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), alpha);
                    StencilClipManager.restoreActiveClip();
                    StencilClipManager.endClip();
                }

                // 队列抽屉盖在内容页之上、控制栏之下，并且被裁剪在内容区域内，
                // 因此它的滑入滑出只会在播放器里发生，不会溢到外框上。
                if (this.playQueuePanel != null && this.playQueuePanel.isVisible()) {
                    this.playQueuePanel.setAlpha(alpha);
                    this.playQueuePanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                            this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());
                    StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                            this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));
                    this.playQueuePanel.renderWidget(mouseX, mouseY, queueDWheel);
                    StencilClipManager.restoreActiveClip();
                    StencilClipManager.endClip();
                }

                this.controlsBar.setAlpha(alpha);
                this.controlsBar.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY() + this.currentPanelBg.getHeight(), this.currentPanelBg.getWidth(), this.getPanelHeight() - this.currentPanelBg.getHeight());
                this.controlsBar.renderWidget(mouseX, mouseY, baseDWheel);
                StencilClipManager.restoreActiveClip();
            }
            api.getGLStateManager().popMatrix();
            PlayerIslandTransition.renderSurfaceDetail();
            StencilClipManager.endClip();

        } else {
            // 打开转场前 10% 与关闭转场最后一段内容完全透明。这里连 Stencil、矩阵和整棵
            // 控件树都跳过，低配设备上可以把这几帧的 GPU/CPU 工作直接归零。
            PlayerIslandTransition.renderSurfaceDetail();
        }

        if (this.musicLyricsPanel != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            // 压暗层读 scrimAlpha 而不是面板 alpha：它要领先内容一档（先把主页压下去，内容才好
            // 在暗底上浮现），面板 alpha 现在是 contentAlpha，比 scrim 晚一个时间窗。
            roundedRect(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                    cornerRadius, getColor(ColorType.GENERIC_BACKGROUND)
                            | ((int) (CoverHeroTransition.scrimAlpha() * 255)) << 24);
            this.musicLyricsPanel.onRender(mouseX, mouseY, basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(), lyricsDWheel);
            StencilClipManager.restoreActiveClip();
            StencilClipManager.endClip();

            if (this.musicLyricsPanel.shouldClose())
                this.musicLyricsPanel = null;
        }

        // 接力封面画在歌词页之上、裁剪之外：它飞行时要从控制栏一路压过压暗层与面板内容，
        // 起落两点都在面板矩形内，出界的只有那圈飞行阴影。
        CoverHeroTransition.render();

        // 波浪绘制在播放器内容之上、模态弹窗之下，并复用播放器圆角 Stencil。
        if (this.themeTransitionRenderer.isActive()) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.themeTransitionRenderer.renderWave(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), alpha);
            StencilClipManager.endClip();
        }
        // 加入歌单弹窗：渲染在面板之上、与 basePanel 同一坐标空间，作为模态覆盖层。
        if (this.addToPlaylistOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.addToPlaylistOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.addToPlaylistOverlay.setAlpha(alpha);
            this.addToPlaylistOverlay.renderWidget(mouseX, mouseY, dWheel);

            if (this.addToPlaylistOverlay.shouldClose())
                this.addToPlaylistOverlay = null;
            StencilClipManager.endClip();
        }

        // 音乐来源菜单是侧栏来源按钮的二级入口，阻止其下方歌单和搜索控件接收输入。
        if (this.musicSourceOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.musicSourceOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.musicSourceOverlay.setAlpha(alpha);
            this.musicSourceOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.musicSourceOverlay.shouldClose()) {
                this.musicSourceOverlay.dispose();
                this.musicSourceOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 一起听房间面板与来源菜单同层：进行中的房间靠后台线程推进状态，
        // 因此这里只负责布局与回收，不在渲染链路里做网络判断。
        if (this.listenTogetherOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.listenTogetherOverlay.setBounds(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight());
            this.listenTogetherOverlay.setAlpha(alpha);
            this.listenTogetherOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.listenTogetherOverlay.shouldClose()) {
                this.listenTogetherOverlay.dispose();
                this.listenTogetherOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 服务器一起听面板：房间状态由 MpPartyClient 的网络线程推进，
        // 这里同样只负责布局与回收，不在渲染链路里做网络判断。
        if (this.mpPartyOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.mpPartyOverlay.setBounds(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight());
            this.mpPartyOverlay.setAlpha(alpha);
            this.mpPartyOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.mpPartyOverlay.shouldClose()) {
                this.mpPartyOverlay.dispose();
                this.mpPartyOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 主题调色板与其它二级菜单同层，阻止下方歌单与控件接收输入。
        if (this.themePaletteOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.themePaletteOverlay.setBounds(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight());
            this.themePaletteOverlay.setAlpha(alpha);
            this.themePaletteOverlay.renderWidget(mouseX, mouseY, 0);
            if (this.themePaletteOverlay.shouldClose()) {
                this.themePaletteOverlay.dispose();
                this.themePaletteOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 字体选择与主题调色板同层：两者都是"改外观"的二级菜单，且互斥打开。
        if (this.fontPickerOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.fontPickerOverlay.setBounds(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight());
            this.fontPickerOverlay.setAlpha(alpha);
            this.fontPickerOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.fontPickerOverlay.shouldClose()) {
                this.fontPickerOverlay.dispose();
                this.fontPickerOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 汽水歌单导入面板：与字体选择同层的二级弹窗，含可编辑输入框，滚轮一并下发。
        if (this.playlistImportOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.playlistImportOverlay.setBounds(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight());
            this.playlistImportOverlay.setAlpha(alpha);
            this.playlistImportOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.playlistImportOverlay.shouldClose()) {
                this.playlistImportOverlay.dispose();
                this.playlistImportOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 建单页与导入面板同层：两者互斥、都是「往本机上落一张单子」的两种说法。
        if (this.playlistCreateSheet != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.playlistCreateSheet.setBounds(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight());
            this.playlistCreateSheet.setAlpha(alpha);
            this.playlistCreateSheet.renderWidget(mouseX, mouseY, dWheel);
            if (this.playlistCreateSheet.shouldClose()) {
                this.playlistCreateSheet.dispose();
                this.playlistCreateSheet = null;
            }
            StencilClipManager.endClip();
        }
        // 歌词搜索弹窗：两栏宽面板，同样有输入框，键盘/滚轮由它独占。
        //
        // 这一页的排版数值是照 folia 的 CSS px 抄来的（弹窗最大宽 1024、卡片高 92、正文 14），
        // 只有在「1 单位 = 1 CSS px」那一档里才成立。播放器自己的轴是它的对折（见
        // {@link WindowAdaptiveScale#designWidth()}），照抄的数值落在那条轴上就整体大一倍：
        // {@code max-w-5xl} 永远夹不到，只剩 {@code 85vh} 那一档在夹，接近正方形的弹窗被压成一条
        // 横幅。所以这一层单独换轴 —— 绕原点乘上倍率的倒数，边界与鼠标坐标一起乘上倍率。
        // 三者必须一起改：只换绘制不换命中，就是看得见点不着。
        //
        // 倍率不是写死的对折，而是用户那一档「弹窗界面缩放」——见
        // {@link WindowAdaptiveScale#lyricSearchUnitsPerLayout()}。那一层的 pf14 与播放器主界面
        // 的 pf14 是否一样大，全看这一档；放大是整层等比，弹窗内部与 folia 的比例一点没动。
        if (this.lyricSearchOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            final double unit = WindowAdaptiveScale.lyricSearchUnitsPerLayout();
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().scale(1.0 / unit, 1.0 / unit, 1);
            this.lyricSearchOverlay.setBounds(basePanel.getX() * unit, basePanel.getY() * unit,
                    basePanel.getWidth() * unit, basePanel.getHeight() * unit);
            this.lyricSearchOverlay.setAlpha(alpha);
            this.lyricSearchOverlay.renderWidget(mouseX * unit, mouseY * unit, dWheel);
            api.getGLStateManager().popMatrix();
            if (this.lyricSearchOverlay.shouldClose()) {
                this.lyricSearchOverlay.dispose();
                this.lyricSearchOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 账号管理始终位于最上层，并独占鼠标/滚轮输入。
        if (this.accountManagerOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.accountManagerOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.accountManagerOverlay.setAlpha(alpha);
            this.accountManagerOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.accountManagerOverlay.shouldClose()) {
                this.accountManagerOverlay.dispose();
                this.accountManagerOverlay = null;
            }
            StencilClipManager.endClip();
        }

        // 二次确认始终位于所有播放器内容之上，并独占鼠标/滚轮输入。
        if (this.confirmationOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.confirmationOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.confirmationOverlay.setAlpha(alpha);
            this.confirmationOverlay.renderWidget(mouseX, mouseY, 0);
            if (this.confirmationOverlay.shouldClose()) {
                this.confirmationOverlay = null;
            }
            StencilClipManager.endClip();
        }

        // 未安装分析模型的一次性提示：与二次确认同层，同样独占鼠标/滚轮输入。
        // 这组边框使用最终 basePanel 坐标。岛屿展开/收回期间内容被压进移动中的 surface，
        if (this.modelInstallNoticeOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.modelInstallNoticeOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.modelInstallNoticeOverlay.setAlpha(alpha);
            this.modelInstallNoticeOverlay.renderWidget(mouseX, mouseY, 0);
            if (this.modelInstallNoticeOverlay.shouldClose()) {
                this.modelInstallNoticeOverlay = null;
            }
            StencilClipManager.endClip();
        }

        // 这组边框使用最终 basePanel 坐标。岛屿展开/收回期间内容被压进移动中的 surface，
        // 如果这里照常绘制，屏幕上会出现一个提前停在最终播放器大小的静态落点边框。
        // 转场表面自己已有跟随几何的 outline；最终外框等动画完全停稳后再出现。
        if (!PlayerIslandTransition.isRunning()) {
            roundedOutline(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                    cornerRadius, getPlayerBorderThickness(), new Color(255, 255, 255,
                            Math.max(0, Math.min(255, (int) (alpha * 25)))));
            float glassAmount = NCMTheme.getLiquidGlassAmount()
                    * GlassLook.edgeGlow(GlassLook.Surface.PLAYER);
            if (glassAmount > 0.001f && GlassLook.amount(GlassLook.Surface.PLAYER) <= 0.001f) {
                int glassBorderAlpha = Math.max(0, Math.min(255, (int) (alpha * glassAmount * 88)));
                roundedOutlineGradient(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                        cornerRadius, .72,
                        new Color(92, 138, 196, glassBorderAlpha / 3),
                        new Color(255, 255, 255, glassBorderAlpha),
                        new Color(115, 164, 230, glassBorderAlpha / 2),
                        new Color(225, 244, 255, glassBorderAlpha));
                roundedRect(basePanel.getX() + cornerRadius * 1.25, basePanel.getY() + .75,
                        Math.max(1.0, basePanel.getWidth() - cornerRadius * 2.5), .7, .35,
                        1.0f, 1.0f, 1.0f, alpha * glassAmount * .30f);
            }
        }
        api.getGLStateManager().popMatrix();
        WindowAdaptiveScale.end();
        CursorUtils.setOverride();

        // 恢复投影矩阵与全部 GL 属性（替代原先只 enableTexture2D 的不完整恢复）。
        SkijaGfx.popMatrix();
        SkijaGfx.popAttrib();

    }

    /** 由主题按钮传入绝对 GUI 坐标，保证涟漪确实从按钮处开始。 */
    public void cycleThemeFrom(double originX, double originY) {
        NCMTheme.next();
        this.themeTransitionRenderer.begin(originX, originY, NCMTheme.getTargetColor(ColorType.ACCENT));
    }

    private final class ThemeGlassWidget extends AbstractWidget<ThemeGlassWidget> {
        private ThemeGlassWidget() {
            this.setClickable(false);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            if (GlassLook.amount(GlassLook.Surface.PLAYER) > 0.001f) return;
            if (TransitionPerformanceBudget.skipLiquidGlass()) return;
            // 边缘光滑块只在真玻璃铺上来的时候吃：没有真背景时这套高光就是「玻璃」本身，
            // 拿它去压暗会让原来那几款液态玻璃主题凭空变淡。
            float amount = NCMTheme.getLiquidGlassAmount() * this.getAlpha()
                    * GlassLook.edgeGlow(GlassLook.Surface.PLAYER);
            if (amount <= 0.001f) return;
            themeTransitionRenderer.renderLiquidGlassSurface(this.getX(), this.getY(),
                    this.getWidth(), this.getHeight(), getPlayerCornerRadius(), amount);
            // 折射压在高光之上：它是「玻璃表面」的效应，跟在底色后面会被那层深色压掉。
            GlassLook.paintEdge(GlassLook.Surface.PLAYER, this.getX(), this.getY(),
                    this.getWidth(), this.getHeight(), getPlayerCornerRadius(), this.getAlpha());
        }
    }

    /**
     * 真玻璃的底色层：把每帧从游戏画面采下来的那张图糊过，铺满整块面板。
     *
     * <p>刻意做成一个单独的不接收点击的控件，而不是在 {@link ThemeGlassWidget} 里顺手画：
     * 那个控件的高光必须压在主题底色<em>之上</em>，而这一层在底色<em>之下</em>，
     * 两者中间隔着 {@code bg}。合成一个控件就得在里面自己排两遍层序，反而读不出来。</p>
     *
     * <p>没有背景可采时（主题不是琉璃、采集失败、Skia 停用）这一层什么都不画，
     * 面板于是和改动前一模一样——它只加一层，永远不接管任何既有绘制。</p>
     */
    private final class ThemeGlassBackdropWidget extends AbstractWidget<ThemeGlassBackdropWidget> {
        private ThemeGlassBackdropWidget() {
            this.setClickable(false);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            GlassLook.paintFrost(GlassLook.Surface.PLAYER, this.getX(), this.getY(),
                    this.getWidth(), this.getHeight(), getPlayerCornerRadius(), this.getAlpha());
        }
    }
    public LoginRenderer loginRenderer = null;

    int currentActionPointer = 0;
    List<Runnable> actions = new ArrayList<>();

    /** 导航等待遮罩。见 {@link #navigateAsync}。 */
    private final NavigationLoadingOverlay navigationLoading = new NavigationLoadingOverlay();

    public void setCurrentPanel(NCMPanel panel) {
        this.innerSetCurrentPanel(panel, true);

        if (panel != null) {
            Runnable action = () -> this.innerSetCurrentPanel(panel, false);

            if (actions.isEmpty()) {
                currentActionPointer = 0;
                actions.add(action);
            } else {
                ++ currentActionPointer;

                while (actions.size() > currentActionPointer + 1)
                    actions.remove(actions.size() - 1);

                if (currentActionPointer < actions.size()) {
                    actions.set(currentActionPointer, action);
                } else {
                    actions.add(action);
                }
            }
        }
    }

    /**
     * 换页但<b>不新增历史记录</b>，而是覆盖当前这一条。
     *
     * <p>给"中转页"用：一个页面加载完就把用户送到别处时，历史里不该留下它。留下来的后果是按返回
     * 会退回那个中转页，它的 {@code onInit} 又重新加载一次再把人送走 —— 返回键看起来失效。</p>
     */
    public void replaceCurrentPanel(NCMPanel panel) {
        this.innerSetCurrentPanel(panel, true);
        if (panel == null) return;

        Runnable action = () -> this.innerSetCurrentPanel(panel, false);
        if (actions.isEmpty()) {
            currentActionPointer = 0;
            actions.add(action);
            return;
        }
        while (actions.size() > currentActionPointer + 1)
            actions.remove(actions.size() - 1);
        actions.set(currentActionPointer, action);
    }

    /**
     * 先在当前页面之上转一枚加载转子，在工作线程取数据，最后<b>一次</b>切到目标页面。
     *
     * <p>导航栏里「排行榜」「最近播放」这类入口的目标页面取决于网络结果（有内容才有页面可去），
     * 原来的做法是先切到一个空的中转面板显示"正在获取…"、数据回来再切一次 —— 用户看到两次页面
     * 淡入，历史栈里还多了一条回不去的记录。改成这样以后只有一次切换、一条历史。</p>
     *
     * @param label  给用户看的目标名，例如「排行榜」；只用于文案
     * @param loader <b>在工作线程</b>调用，返回要切去的面板；返回 {@code null} 表示没内容可展示，
     *               此时留在当前页面并给一条会自动消失的提示。它的 {@code onInit} 仍由主线程调用，
     *               所以 loader 里只该取数据、不要碰 GL
     */
    public void navigateAsync(String label, Supplier<NCMPanel> loader) {
        if (loader == null) return;
        // 等待期间忽略重复点击：连点会并发发出好几个同样的请求，最后还会互相覆盖切页。
        if (this.navigationLoading.isLoading()) return;

        final String targetName = label == null ? "" : label;
        this.navigationLoading.beginLoading(targetName);
        MultiThreadingUtil.runAsync(() -> {
            NCMPanel loaded = null;
            String failure = null;
            try {
                loaded = loader.get();
            } catch (Throwable throwable) {
                failure = throwable.getMessage();
                System.err.println("[Music] 导航加载失败（" + targetName + "）: " + throwable);
            }
            final NCMPanel panel = loaded;
            final String error = failure;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (panel != null) {
                    this.navigationLoading.clear();
                    this.setCurrentPanel(panel);
                    return;
                }
                this.navigationLoading.showMessage(error == null || error.trim().isEmpty()
                        ? (targetName.isEmpty() ? "暂无可显示内容" : targetName + "暂无可显示内容")
                        : "获取失败：" + error.trim());
            });
        });
    }

    /**
     * 立即返回前一个内容页。现有鼠标侧键导航使用
     * 同一历史，因此页面动画
     * 和生命周期与播放器其余部分保持一致。
     */
    public void reloadCurrentPanel() {
        if (this.currentPanel instanceof PlaylistPanel || this.currentPanel instanceof PersonalFmPanel) {
            this.currentPanel.onInit();
        }
    }
    private void innerSetCurrentPanel(NCMPanel panel, boolean shouldCallInit) {
        this.prevAnimatingPanelAlpha = this.currentPanel == null ? 0f : this.currentPanel.getAlpha();
        this.prevAnimatingPanel = this.currentPanel;
        this.currentPanel = panel;
        this.panelTransitionBackward = this.pendingNavigationBackward;
        this.pendingNavigationBackward = false;
        // 没有上一页、或者上一页几乎还没露脸（连点两个歌单时很常见），就没有什么可交代的：
        // 时钟直接给到终点，新页按停稳态画，不硬凑一次转场。
        this.panelTransitionT = this.prevAnimatingPanel == null || this.prevAnimatingPanelAlpha <= .02f
                ? 1.0 : 0.0;
        if (panel != null) {
            if (shouldCallInit)
                this.currentPanel.onInit();
            this.currentPanel.setAlpha(0);
        }
        if (this.playlistsPanel != null) {
            this.playlistsPanel.selectCurrentPanel(panel);
        }
    }

    /** 本次转场该走多久：基准时长除以用户的「界面切换动画速度」倍率。 */
    private static double panelTransitionMillis() {
        return PANEL_TRANSITION_MS / Math.max(.30, HudConfig.playerUiSwitchSpeed);
    }

    /**
     * 本帧推进的毫秒数。抄 {@code CoverHeroTransition#step}：{@code getFrameDeltaTime()} 的单位是
     * 10ms，并且它在帧间隔过大时会把值压小，所以卡顿时转场是整体变慢而不是跳过去 —— 一帧跨掉
     * 小半条曲线就是「一顿一顿」的由来。这里再夹一道 40ms 上限，低于 25fps 宁可慢也不要跳。
     */
    private static double panelTransitionStepMillis() {
        return Math.min(40.0, Math.max(0.0, RenderSystem.getFrameDeltaTime() * 10.0));
    }

    // 独立的二级面板仍可提供有用的退路。
    public void navigateBack() {
        // 两条出口（退历史、退回主页）都是后退。方向标志只能紧挨着真正那次换页设：
        // 它在 innerSetCurrentPanel 里被取走并清零，所以在「已经在主页、按了个空」这条
        // 什么都不做的分支上提前置位，会一路留到下一次前进，把该往右推的页推成往左。
        if (currentActionPointer > 0) {
            NCMPanel from = this.currentPanel;
            --currentActionPointer;
            this.pendingNavigationBackward = true;
            actions.get(currentActionPointer).run();
            this.handCoverBackIfNeeded(from);
            return;
        }

        /**
     * 打开“加入歌单”弹窗。由 MusicWidget 的「+」按钮调用。
     * 复用原项目 PlayList.addToList(musicId) → CloudMusicApi.playlistTracks("add", pid, musicId)。
     */
        if (!(this.currentPanel instanceof HomePanel)) {
            NCMPanel from = this.currentPanel;
            this.pendingNavigationBackward = true;
            this.setCurrentPanel(new HomePanel());
            this.handCoverBackIfNeeded(from);
        }
    }

    /**
     * 返回这一趟的封面接力：把详情页那张大封面交回主页里同一个歌单的卡片。
     *
     * <p>只有「详情页 → 主页」这一趟有落点可飞，而且必须等换页之后再交起点 —— 此刻才知道目标
     * 是不是主页；主页的卡片也要等它自己排版完才认得出该接住谁。目标主页可能把这张卡片滚在
     * 视口外，那没人认领落点，{@code PlaylistCoverHero} 会在第一帧自己收手。</p>
     */
    private void handCoverBackIfNeeded(NCMPanel from) {
        if (from instanceof PlaylistPanel && this.currentPanel instanceof HomePanel) {
            ((PlaylistPanel) from).handCoverBack();
        }
    }

    /**
     * 打开“加入歌单”弹窗。由 MusicWidget 的「+」按钮调用。
     * 复用原项目 PlayList.addToList(musicId) → CloudMusicApi.playlistTracks("add", pid, musicId)。
     */
    public void openAddToPlaylist(Music music) {
        openAddToPlaylist(music, null);
    }

    /** @param heroFrom 歌曲行那枚「+」在按下那一刻的矩形，飞进弹窗顶部那一行。 */
    public void openAddToPlaylist(Music music, SheetHeroRelay.Origin heroFrom) {
        if (music != null && music.isQQ()) {
            return; // QQ 曲目不可提交到网易云歌单。
        }
        // 酷狗曲目走酷狗歌单，前提是登录了——没登录时弹窗只会列出一片空白，不如直接提示。
        if (music != null && music.isKuGou() && !KuGouMusicService.isLoggedIn()) {
            openConfirmation("需要登录酷狗音乐", "加入歌单需要登录酷狗账号，请先在账号管理器里登录。",
                    "知道了", () -> {
                    });
            return;
        }
        this.addToPlaylistOverlay = new AddToPlaylistOverlay(music);
        this.addToPlaylistOverlay.claimHero(heroFrom);
        this.addToPlaylistOverlay.onInit();
    }

    /**
     * 本次启动第一次打开播放器、且确实还有分析组件没装时，挂上安装提示。
     *
     * <p>判定与「本次启动只弹一次」的闸门都在 {@link FoliaModelNotice} 里；这里只负责挑时机：
     * 岛屿展开动画已经跑完、没有别的模态占着、也不在全屏歌词页上。条件不满足时它只是一串
     * 布尔判断，所以每帧问一次是安全的。</p>
     */
    public void openConfirmation(String title, String message, String confirmText, Runnable onConfirm) {
        this.confirmationOverlay = new ConfirmationOverlay(title, message, confirmText, onConfirm);
        this.confirmationOverlay.onInit();
    }

    /** 打开主题调色板；重复打开时先回收上一次的实例，避免两层叠在一起。 */
    private void maybeShowModelNotice() {
        if (this.modelInstallNoticeOverlay != null) return;
        // 展开动画期间播放器表面还在动，这时弹一张居中卡片会跟着一起漂。
        if (PlayerIslandTransition.isRunning()) return;
        // 正在关闭的播放器不需要再来一张新卡片。
        if (this.closing) return;
        // 全屏歌词页上不打断；与其它模态浮层同理，一次只让一层独占输入。
        if (this.musicLyricsPanel != null) return;
        if (isModalOverlayActive()) return;
        if (!FoliaModelNotice.shouldShow()) return;
        this.modelInstallNoticeOverlay = new ModelInstallNoticeOverlay();
        this.modelInstallNoticeOverlay.onInit();
        FoliaModelNotice.markShownThisSession();
    }

    /**
     * 打开公告中心。
     *
     * <p>它是一个独立的 Screen 而不是播放器浮层：主菜单那颗按钮与 HUD 设置里的入口都承载不了浮层，
     * 三处共用同一个宿主，于是「关掉即整批已读」这一条行为只有一处实现，不必各写一遍。
     * 走 {@code setScreen} 而不是共享的 {@code displayScreen}：后者只收 {@code ExtensionScreen}。</p>
     */
    public void openAnnouncements() {
        GuiAnnouncements.open(this);
    }

    public void openAccountManager() {
        openAccountManager(null);
    }

    /**
     * 打开账号管理。
     *
     * @param heroFrom 触发它的那颗侧栏控件在<b>按下那一刻</b>的矩形，接力板从那儿飞进来；
     *                 没有起点就只走普通的进出场。必须在 {@code onInit()} 之前交到 ——
     *                 那之后弹窗骨架已经建好了。
     */
    public void openAccountManager(SheetHeroRelay.Origin heroFrom) {
        if (this.accountManagerOverlay != null) this.accountManagerOverlay.dispose();
        this.accountManagerOverlay = new AccountManagerOverlay();
        this.accountManagerOverlay.claimHero(heroFrom);
        this.accountManagerOverlay.onInit();
    }

    /** 打开主题调色板；重复打开时先回收上一次的实例，避免两层叠在一起。 */
    public void openThemePalette() {
        if (this.themePaletteOverlay != null) this.themePaletteOverlay.dispose();
        this.themePaletteOverlay = new ThemePaletteOverlay();
        this.themePaletteOverlay.onInit();
    }

    /** 打开界面字体选择；重复打开时先回收上一次的实例，避免两层叠在一起。 */
    public void openFontPicker() {
        if (this.fontPickerOverlay != null) this.fontPickerOverlay.dispose();
        this.fontPickerOverlay = new FontPickerOverlay();
        this.fontPickerOverlay.onInit();
    }

    /** 打开全渠道歌单导入面板（自动识别平台）；同样先回收旧实例，避免两层叠在一起。 */
    public void openPlaylistImport() {
        if (this.playlistCreateSheet != null) this.playlistCreateSheet.dispose();
        this.playlistCreateSheet = null;
        if (this.playlistImportOverlay != null) this.playlistImportOverlay.dispose();
        this.playlistImportOverlay = new PlaylistImportOverlay();
        this.playlistImportOverlay.onInit();
    }

    /** 打开建单页。它和导入面板回答的是同一个问题（「我要一张单子」），所以互斥打开。 */
    public void openPlaylistCreate() {
        openPlaylistSheet(new PlaylistCreateSheet());
    }

    /** 把建单页换成「重命名这一张」：同一份表单，落盘的动作不同（见 {@link PlaylistCreateSheet}）。 */
    public void openPlaylistRename(PlayList playlist) {
        if (playlist == null) return;
        openPlaylistSheet(new PlaylistCreateSheet(playlist));
    }

    private void openPlaylistSheet(PlaylistCreateSheet sheet) {
        if (this.playlistImportOverlay != null) this.playlistImportOverlay.dispose();
        this.playlistImportOverlay = null;
        if (this.playlistCreateSheet != null) this.playlistCreateSheet.dispose();
        this.playlistCreateSheet = sheet;
        this.playlistCreateSheet.onInit();
    }

    /**
     * 打开歌词搜索弹窗（二级界面），目标默认就是正在播的这一首。
     *
     * <p>它一次只服务一首歌：弹窗在构造时就把目标记死了，中途切歌不会让它变成另一首
     * 的搜索，所以重复打开一律换成新实例，而不是复用旧的。</p>
     */
    public void openLyricSearch() {
        openLyricSearch(CloudMusic.currentlyPlaying);
    }

    /**
     * 给指定的一首歌开手动选词弹窗（歌单行上的入口）。
     *
     * <p>目标不一定是正在播的那首，所以弹窗里的预览时钟会停住，钉词也只写这一首的配置。</p>
     */
    public void openLyricSearch(Music target) {
        if (target == null) return;
        if (this.lyricSearchOverlay != null) this.lyricSearchOverlay.dispose();
        this.lyricSearchOverlay = new LyricSearchOverlay(target);
        this.lyricSearchOverlay.onInit();
    }

    /** 打开音乐来源面板；重复打开时先回收上一次的实例，避免两层叠在一起。 */
    public void openMusicSourceManager() {
        openMusicSourceManager(null);
    }

    /** @param heroFrom 见 {@link #openAccountManager(SheetHeroRelay.Origin)}：侧栏那枚来源按钮的矩形。 */
    public void openMusicSourceManager(SheetHeroRelay.Origin heroFrom) {
        if (this.musicSourceOverlay != null) this.musicSourceOverlay.dispose();
        this.musicSourceOverlay = new MusicSourceOverlay();
        this.musicSourceOverlay.claimHero(heroFrom);
        this.musicSourceOverlay.onInit();
    }

    /** 打开一起听面板；重复打开时先回收上一次的实例，避免两层叠在一起。 */
    public void openListenTogether() {
        if (this.listenTogetherOverlay != null) this.listenTogetherOverlay.dispose();
        this.listenTogetherOverlay = new ListenTogetherOverlay();
        this.listenTogetherOverlay.onInit();
    }

    /** 打开服务器一起听（房间）面板；同样先回收上一次的实例。 */
    public void openMpParty() {
        if (this.mpPartyOverlay != null) this.mpPartyOverlay.dispose();
        this.mpPartyOverlay = new MpPartyOverlay();
        this.mpPartyOverlay.onInit();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {

        if (this.modelInstallNoticeOverlay != null) {
            // ESC 等价于「稍后再说」，不会顺手把播放器一起关掉。
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.modelInstallNoticeOverlay.handleEscape();
            }
            return;
        }

        if (this.confirmationOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.confirmationOverlay.cancel();
            }
            return;
        }

        if (this.themePaletteOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.themePaletteOverlay.handleEscape();
            }
            return;
        }

        if (this.fontPickerOverlay != null) {
            // ESC 抢在控件树之前：搜索框一旦聚焦，TextFieldWidget 会把每一个按键都当成
            // 自己处理掉（包括 ESC），那样这一层就再也关不掉了。其余按键照常下发，
            // 否则搜索框的输入和粘贴都像没反应。
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.fontPickerOverlay.handleEscape();
                return;
            }
            this.fontPickerOverlay.onKeyTypedReceived(typedChar, keyCode);
            return;
        }

        if (this.playlistImportOverlay != null) {
            // 与字体选择同理：输入框聚焦时会吞掉所有按键，ESC 必须抢在控件树之前处理，
            // 其余按键下发给输入框，否则打字与 Ctrl+V 粘贴都没有反应。
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.playlistImportOverlay.handleEscape();
                return;
            }
            this.playlistImportOverlay.onKeyTypedReceived(typedChar, keyCode);
            return;
        }

        if (this.playlistCreateSheet != null) {
            // 建单页的输入框同样会吞掉所有按键，ESC 要抢在控件树之前。
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.playlistCreateSheet.handleEscape();
                return;
            }
            this.playlistCreateSheet.onKeyTypedReceived(typedChar, keyCode);
            return;
        }

        if (this.lyricSearchOverlay != null) {
            // 同导入面板：ESC 必须抢在控件树之前，否则焦点在搜索框里时按 ESC 会先吃掉一层。
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.lyricSearchOverlay.handleEscape();
                return;
            }
            this.lyricSearchOverlay.onKeyTypedReceived(typedChar, keyCode);
            return;
        }

        if (this.musicSourceOverlay != null) {
            if (this.musicSourceOverlay.onKeyTypedReceived(typedChar, keyCode)) {
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.musicSourceOverlay.handleEscape();
            }
            return;
        }
        // 一起听的「加入房间」页有可编辑的邀请码输入框，必须先让控件树看到按键，
        // 否则粘贴和输入都会像没反应。
        if (this.listenTogetherOverlay != null) {
            if (this.listenTogetherOverlay.onKeyTypedReceived(typedChar, keyCode)) {
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.listenTogetherOverlay.handleEscape();
            }
            return;
        }
        // 服务器一起听的子页有可编辑的邀请码 / 玩家名 / 链接输入框，同样要先让控件树看到按键。
        if (this.mpPartyOverlay != null) {
            if (this.mpPartyOverlay.onKeyTypedReceived(typedChar, keyCode)) {
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.mpPartyOverlay.handleEscape();
            }
            return;
        }
        if (this.accountManagerOverlay != null) {
            // 账号覆盖层包含可编辑的 Cookie 字段。此前这个模态
            //在其组件树看到按键前就消费了每个键，因此
            //正常输入和 Ctrl+V 粘贴都看似无效。
            if (this.accountManagerOverlay.onKeyTypedReceived(typedChar, keyCode)) {
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.accountManagerOverlay.handleEscape();
            }
            return;
        }

        // 加入歌单弹窗为模态：仅处理 ESC，其余按键不穿透到下层面板。
        if (this.addToPlaylistOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE)
                this.addToPlaylistOverlay = null;
            return;
        }

        if (this.basePanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (this.currentPanel != null && this.currentPanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {

            // 先收起最上层的临时界面，和其它覆盖层的 ESC 行为保持一致。
            if (this.playQueuePanel != null && this.playQueuePanel.isOpen()) {
                this.playQueuePanel.setOpen(false);
                return;
            }

            if (this.musicLyricsPanel != null)
                this.musicLyricsPanel.close();
            else {
                closing = true;
                PlayerIslandTransition.close();
            }

        }

        // 与全局快捷键共用 CloudMusic.togglePlayPause()，两处的判空与暂停语义始终一致。
        if (keyCode == Keyboard.KEY_SPACE) {
            CloudMusic.togglePlayPause();
        }

    }

    /**
     * 是否有模态弹窗正在显示。点击链路（{@link #mouseClicked}）本来就是逐层 return 的模态处理，
     * 滚轮必须用同一套判断，否则弹窗里滚动会连带下层一起滚。
     */
    private boolean isModalOverlayActive() {
        return this.modelInstallNoticeOverlay != null
                || this.confirmationOverlay != null
                || this.themePaletteOverlay != null
                || this.fontPickerOverlay != null
                || this.playlistImportOverlay != null
                || this.playlistCreateSheet != null
                || this.lyricSearchOverlay != null
                || this.musicSourceOverlay != null
                || this.listenTogetherOverlay != null
                || this.mpPartyOverlay != null
                || this.accountManagerOverlay != null
                || this.addToPlaylistOverlay != null;
    }

    @Override
    public void mouseClicked(int mX, int mY, int mouseButton) {

        RenderSystem.refreshResolution();
        WindowAdaptiveScale.begin();
        double xScale = WindowAdaptiveScale.correctionX();
        double yScale = WindowAdaptiveScale.correctionY();
        double mouseX = mX / xScale;
        double mouseY = mY / yScale;
        WindowAdaptiveScale.end();

        if (this.modelInstallNoticeOverlay != null) {
            this.modelInstallNoticeOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.confirmationOverlay != null) {
            this.confirmationOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.themePaletteOverlay != null) {
            this.themePaletteOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.fontPickerOverlay != null) {
            this.fontPickerOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.playlistImportOverlay != null) {
            this.playlistImportOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.playlistCreateSheet != null) {
            this.playlistCreateSheet.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.lyricSearchOverlay != null) {
            // 这一层画在弹窗自己那条轴上（见 {@link #drawScreen} 里那次换轴），命中坐标必须
            // 一起乘上去，否则点的是卡片、命中的是卡片左上角那一小块。
            final double unit = WindowAdaptiveScale.lyricSearchUnitsPerLayout();
            this.lyricSearchOverlay.onMouseClickReceived(mouseX * unit, mouseY * unit, mouseButton);
            return;
        }

        if (this.musicSourceOverlay != null) {
            this.musicSourceOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }
        if (this.listenTogetherOverlay != null) {
            this.listenTogetherOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }
        if (this.mpPartyOverlay != null) {
            this.mpPartyOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }
        if (this.accountManagerOverlay != null) {
            this.accountManagerOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        // 加入歌单弹窗优先捕获鼠标，阻止点击穿透到下层歌单/歌曲列表。
        if (this.addToPlaylistOverlay != null) {
            this.addToPlaylistOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (musicLyricsPanel == null) {
            // 质量弹出窗在视觉上与歌曲列表重叠。在分发给列表之前
            //消费其完整边界，防止点击穿透。
            if (this.controlsBar.consumeQualityMenuClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            // 队列抽屉同样遮住了歌曲列表：它范围内的点击必须由它吃掉，否则会穿透触发播放。
            if (this.playQueuePanel != null && this.playQueuePanel.consumeClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            // 搜索联想下拉遮住了侧栏的歌单条目与音乐来源卡片，同样必须前置消费。
            // 光靠组件树内部「命中即 return」管不住：下面这三行是连续调用、没有早退，
            // 而 onMouseClickReceived 返回 void，谁也拿不到「已经被吃掉」这个信息。
            if (this.playlistsPanel != null
                    && this.playlistsPanel.consumeSuggestionClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            this.basePanel.onMouseClickReceived(mouseX, mouseY, mouseButton);

            if (this.currentPanel != null)
                this.currentPanel.onMouseClickReceived(mouseX, mouseY, mouseButton);

            this.controlsBar.onMouseClickReceived(mouseX, mouseY, mouseButton);

            // 前进
            if (mouseButton == 4) {

                // 是否最后
                if (currentActionPointer >= actions.size() - 1) {
                    currentActionPointer = actions.size() - 1;
                } else {
                    currentActionPointer ++;
                    actions.get(currentActionPointer).run();
                }

            }
            /** 播放器主题的颜色语义；具体颜色由 {@link NCMTheme} 提供。 */
            else if (mouseButton == 3) {
                this.navigateBack();
            }

        } else {
            this.musicLyricsPanel.mouseClicked(mouseX, mouseY, mouseButton);
        }

    }

    /** 播放器主题的颜色语义；具体颜色由 {@link NCMTheme} 提供。 */
    public enum ColorType {

        GENERIC_BACKGROUND("页面底色"),
        ELEMENT_BACKGROUND("控件底色"),
        ELEMENT_HOVER("控件悬停"),
        PRIMARY_TEXT("主要文字"),
        SECONDARY_TEXT("次要文字"),
        ACCENT("强调色"),
        ACCENT_HOVER("强调悬停"),
        INPUT_BACKGROUND("输入框底色"),
        NAVIGATION_BACKGROUND("导航底色"),
        BORDER("描边");

        private final String displayName;

        ColorType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return this.displayName;
        }

    }

    public static int getColor(ColorType type) {
        return NCMTheme.getColor(type);
    }
}
