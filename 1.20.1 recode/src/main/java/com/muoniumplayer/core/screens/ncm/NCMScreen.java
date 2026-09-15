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
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.settings.HudConfig;
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

    float prevAnimatingPanelAlpha = 0f;
    NCMPanel prevAnimatingPanel = null;
    NCMPanel currentPanel = null;
    float curPanelAlphaAnimation = 0f;

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

    /** 对取消收藏等破坏性操作的二次确认层。 */
    public ConfirmationOverlay confirmationOverlay = null;

    /**
     * One-shot notice for a machine that has not installed the Folia analysis models yet.
     *
     * <p>It sits on the same layer as the confirmation overlay and owns input the same way, but
     * it is mounted at most once per launch: {@link FoliaModelNotice} owns both the decision and
     * the "already shown this session" gate.</p>
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
        // The notice layer does not survive a single opening of the player: the next open
        // decides again via maybeShowModelNotice, and the "already shown this launch" gate
        // lives in FoliaModelNotice, so clearing it here cannot cause a duplicate prompt.
        this.modelInstallNoticeOverlay = null;
    }

    public void layout() {
        this.basePanel.getChildren().clear();

        RoundedRectWidget bg = new RoundedRectWidget();
        bg.setBeforeRenderCallback(() -> {
            bg.setBounds(0, 0, bg.getParentWidth(), bg.getParentHeight());
            bg.setRadius(this.getPlayerCornerRadius());
            bg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            // 液态玻璃保留足够实体感，同时让游戏画面能轻微透入形成材质层次。
            bg.setAlpha(1.0f - NCMTheme.getLiquidGlassAmount() * 0.14f);
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
            this.currentPanelBg.setAlpha(1.0f - NCMTheme.getLiquidGlassAmount() * 0.24f);
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
        // All player geometry and transition targets must be captured in the adaptive layout.
        // Entering it after updatePanelScaleAnimation()/tick() left the morph target in Minecraft
        // GUI coordinates while the render transform expected adaptive coordinates.
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

        // The "first time the player is opened this launch" model notice. It is driven from
        // here rather than initGui because the island morph has to finish before the player
        // counts as open - dropping a card mid-morph would fight the opening animation. The gate
        // inside is "once per launch", "something really is missing" and "no other modal is
        // up", so when the conditions do not hold this is nothing but a few boolean checks.
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

        // The surface must exist before content alpha becomes non-zero. Otherwise the first and
        // last 10% of the island morph are geometrically moving but visually invisible.
        PlayerIslandTransition.renderSurface();

        // Below 3.5% the content is imperceptible. Skipping the whole widget tree here avoids
        // paying for covers/lyrics/queue work during the first and last frames of the morph,
        // which is exactly when music playback is most likely to pressure the frame budget.
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
                // Re-assert the root rounded clip after child/framebuffer rendering.
                StencilClipManager.restoreActiveClip();

                // 内容页切换的速度倍率由用户配置（HudConfig.playerUiSwitchSpeed，当前默认 3.0 =
                // 明显快于历史速度）。指数逼近系数必须钳在 .95 以下：到 1 就是一帧到位，再大反而越过目标。
                float alphaInterpolateSpeed = Math.min(.95f, .4f * HudConfig.playerUiSwitchSpeed);
                if (this.prevAnimatingPanel != null) {
                    this.prevAnimatingPanel.setAlpha(this.prevAnimatingPanelAlpha = Interpolations.interpolate(this.prevAnimatingPanelAlpha, 0f, alphaInterpolateSpeed));
                    this.prevAnimatingPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                    api.getGLStateManager().pushMatrix();
                    this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 0.9 + (this.prevAnimatingPanel.getAlpha() * 0.1));
                    this.prevAnimatingPanel.renderWidget(mouseX, mouseY, baseDWheel);
                    api.getGLStateManager().popMatrix();

                    if (this.prevAnimatingPanelAlpha <= 0.02f)
                        this.prevAnimatingPanel = null;
                } else if (this.currentPanel != null) {
                    curPanelAlphaAnimation = Interpolations.interpolate(curPanelAlphaAnimation, 1f, alphaInterpolateSpeed);
                    this.currentPanel.setAlpha(Math.min(this.basePanel.getAlpha(), curPanelAlphaAnimation));
                    this.currentPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                    StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));

                    api.getGLStateManager().pushMatrix();
                    this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 1.1 - (curPanelAlphaAnimation * 0.1));

                    this.currentPanel.renderWidget(mouseX, mouseY, baseDWheel);
                    api.getGLStateManager().popMatrix();

                    StencilClipManager.endClip();
                }

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

        // One-shot notice for missing analysis models: same layer as the confirmation overlay,
        // and it owns mouse/wheel input the same way.
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
            float glassAmount = NCMTheme.getLiquidGlassAmount();
            if (glassAmount > 0.001f) {
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
            if (TransitionPerformanceBudget.skipLiquidGlass()) return;
            float amount = NCMTheme.getLiquidGlassAmount() * this.getAlpha();
            if (amount <= 0.001f) return;
            themeTransitionRenderer.renderLiquidGlassSurface(this.getX(), this.getY(),
                    this.getWidth(), this.getHeight(), getPlayerCornerRadius(), amount);
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
     * Rebuilds the visible playlist panel after asynchronously loaded metadata
     * changes its row badges. Other panels are intentionally left untouched so
     * an account refresh cannot trigger duplicate home/search requests.
     */
    public void reloadCurrentPanel() {
        if (this.currentPanel instanceof PlaylistPanel || this.currentPanel instanceof PersonalFmPanel) {
            this.currentPanel.onInit();
        }
    }
    private void innerSetCurrentPanel(NCMPanel panel, boolean shouldCallInit) {
        this.prevAnimatingPanel = this.currentPanel;
        this.prevAnimatingPanelAlpha = 1.0f;
        this.currentPanel = panel;
        if (panel != null) {
            if (shouldCallInit)
                this.currentPanel.onInit();
            this.currentPanel.setAlpha(0);
            this.curPanelAlphaAnimation = 0f;
        }
        if (this.playlistsPanel != null) {
            this.playlistsPanel.selectCurrentPanel(panel);
        }
    }

    /**
     * Returns to the immediately preceding content page. The same history is
     * used by the existing mouse side-button navigation, so the page animation
     * and lifecycle remain consistent with the rest of the player.
     */
    public void navigateBack() {
        if (currentActionPointer > 0) {
            --currentActionPointer;
            actions.get(currentActionPointer).run();
            return;
        }

        // A standalone secondary panel can still offer a useful escape route.
        if (!(this.currentPanel instanceof HomePanel)) {
            this.setCurrentPanel(new HomePanel());
        }
    }

    /**
     * 打开“加入歌单”弹窗。由 MusicWidget 的「+」按钮调用。
     * 复用原项目 PlayList.addToList(musicId) → CloudMusicApi.playlistTracks("add", pid, musicId)。
     */
    public void openAddToPlaylist(Music music) {
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
        this.addToPlaylistOverlay.onInit();
    }

    /** Opens a modal confirmation without running the action until the user explicitly confirms. */
    public void openConfirmation(String title, String message, String confirmText, Runnable onConfirm) {
        this.confirmationOverlay = new ConfirmationOverlay(title, message, confirmText, onConfirm);
        this.confirmationOverlay.onInit();
    }

    /**
     * Mounts the model-install notice the first time the player is opened this launch, when
     * something really is missing.
     *
     * <p>The decision and the "shown once per launch" gate both live in {@link FoliaModelNotice};
     * this only picks the moment: the island morph has finished, no other modal is up, and the
     * player is not on the fullscreen lyrics page. When the conditions do not hold it is a short
     * chain of boolean checks, so asking every frame is safe.</p>
     */
    private void maybeShowModelNotice() {
        if (this.modelInstallNoticeOverlay != null) return;
        // The player surface is still moving during the morph; a centred card would drift with it.
        if (PlayerIslandTransition.isRunning()) return;
        // A player that is already closing does not need one more card.
        if (this.closing) return;
        // Do not interrupt the fullscreen lyrics page: like every other modal, only one layer
        // gets to own the input at a time.
        if (this.musicLyricsPanel != null) return;
        if (isModalOverlayActive()) return;
        if (!FoliaModelNotice.shouldShow()) return;
        this.modelInstallNoticeOverlay = new ModelInstallNoticeOverlay();
        this.modelInstallNoticeOverlay.onInit();
        FoliaModelNotice.markShownThisSession();
    }

    public void openAccountManager() {
        if (this.accountManagerOverlay != null) this.accountManagerOverlay.dispose();
        this.accountManagerOverlay = new AccountManagerOverlay();
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

    /** Opens the second-level source center without changing the current content page. */
    public void openMusicSourceManager() {
        if (this.musicSourceOverlay != null) this.musicSourceOverlay.dispose();
        this.musicSourceOverlay = new MusicSourceOverlay();
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
            // ESC means "later", and must not also close the player.
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
            // The account overlay contains editable Cookie fields. Previously this
            // modal consumed every key before its widget tree could see it, so both
            // normal typing and Ctrl+V paste appeared to do nothing.
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
            // The quality pop-up visually overlaps the song list. Consume its
            // full bounds before dispatching to the list to prevent click-through.
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

            // forward
            if (mouseButton == 4) {

                // is last
                if (currentActionPointer >= actions.size() - 1) {
                    currentActionPointer = actions.size() - 1;
                } else {
                    currentActionPointer ++;
                    actions.get(currentActionPointer).run();
                }

            }
            // go back
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
