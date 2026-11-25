package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.input.TouchGestures;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AutomixCueMarker;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPresentation;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.ListenTogetherService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.rendering.AutomixCuePainter;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.SPlayerIcons;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.CoverHeroTransition;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.SPlayerButton;
import com.muoniumplayer.core.screens.ncm.SPlayerStyle;
import com.muoniumplayer.core.screens.ncm.PlayerIslandTransition;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TrackSelectionMotion;
import com.muoniumplayer.core.screens.ncm.VolumeControl;
import com.muoniumplayer.core.viper.ViperRuntime;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.party.MpPartyClient;

import java.util.List;


/**
 */
public class ControlsBar extends NCMPanel {

    private PlaybackPresentation presentation = new PlaybackPresentation(null, null, 0L);
    private final TrackSelectionMotion selectionMotion = new TrackSelectionMotion();
    /** 主界面稳定后分帧预热全屏歌词页，避免点击瞬间同步量整首歌词。 */
    private int lyricPrewarmStableFrames;
    private String lyricPrewarmTrackKey;

    private boolean qualityMenuOpen;
    /**
     * 展开/收起的动画进度，0 为完全收起、1 为完全展开。由音质按钮的每帧回调推进：按钮常驻可见，
     * 而弹出层收起后会被隐藏、回调不再执行，进度放在弹出层里就没有帧可以播完收起动画。
     */
    private double qualityMenuAnimation;
    /** 上一帧的时刻。音质菜单和待播抽屉一样按实时毫秒推进，不再用帧耦合的指数逼近。 */
    private long qualityMenuLastFrameNanos;
    private RoundedRectWidget qualityMenuBackground;
    private RoundedRectWidget[] qualityOptions;

    /**
     * 弹出菜单的排版常量，单位都是「GUI 缩放 1 时的逻辑像素」，摆布局时再乘缩放。
     *
     * <p>两列而不是八行一列：一列要把整条页脚上方都占满，展开时盖住进度条一大截；两列只有四行，
     * 横向又正好落在按钮自己那一片，视觉上是一块小方板而不是一条横幅。</p>
     */
    private static final int QUALITY_COLUMNS = 2;
    private static final double QUALITY_OPTION_HEIGHT = 20.0;
    private static final double QUALITY_MENU_PADDING = 3.0;
    private static final double QUALITY_COLUMN_GAP = 3.0;
    private static final double QUALITY_ROW_INSET = 6.0;
    private static final double QUALITY_ICON_SIZE = 12.0;
    private static final double QUALITY_ICON_GAP = 5.0;
    private static final double QUALITY_CHECK_SIZE = 10.0;
    private static final double QUALITY_CHECK_GAP = 4.0;
    /** 基准毫秒，运行时还要除以「界面切换动画速度」倍率（默认 3.0，也就是实际 200/120ms）。 */
    private static final double QUALITY_OPEN_MS = 600.0;
    private static final double QUALITY_CLOSE_MS = 360.0;
    /** 一帧最多折算这么多毫秒：只挡切后台回来这类断档，正常卡顿照常按实时走。 */
    private static final double QUALITY_MAX_FRAME_MS = 40.0;

    /**
     * 这一档用哪枚等级图标。
     *
     * <p>前四档是一条递增的信号阶梯（一格、两格、三格、满格），说的就是「码率一档比一档高」；
     * 后四档不再是码率而是<b>另一种东西</b>——Hi-Res 是采样规格、环绕/沉浸是渲染出来的声场、
     * 母带是来源级别——所以各自用自己那一枚，硬塞进阶梯反而会说谎。</p>
     *
     * <p>包内可见是给 {@code QualityMenuStaticTest} 的：那份契约要真的把八个档位各要一枚图标，
     * 而不是在源码里数字符串。</p>
     */
    static String qualityTierIcon(Quality quality) {
        if (quality == null) return FoliaLucideIcons.SIGNAL;
        switch (quality) {
            case STANDARD: return FoliaLucideIcons.SIGNAL_LOW;
            case HIGHER: return FoliaLucideIcons.SIGNAL_MEDIUM;
            case EXHIGH: return FoliaLucideIcons.SIGNAL_HIGH;
            case LOSSLESS: return FoliaLucideIcons.SIGNAL;
            case HIRES: return FoliaLucideIcons.AUDIO_LINES;
            case JYEFFECT: return FoliaLucideIcons.WAVES;
            case SKY: return FoliaLucideIcons.ORBIT;
            case JYMASTER: return FoliaLucideIcons.CROWN;
            default: return FoliaLucideIcons.SIGNAL;
        }
    }

    /** 一行选项需要的宽度：档位图标 + 间距 + 文字 + 勾选位。勾选位无论选没选中都留，切档时列宽才不跳。 */
    private static double qualityRowWidth(String label) {
        return QUALITY_ROW_INSET + QUALITY_ICON_SIZE + QUALITY_ICON_GAP
                + FontManager.pf12bold.getStringWidthD(label)
                + QUALITY_CHECK_GAP + QUALITY_CHECK_SIZE + QUALITY_ROW_INSET;
    }

    /** 顶边进度条与音量浮层：它们伸进内容区下沿，点击要在分发给内容区之前截住。 */
    private ProgressStrip progressStrip;
    private VolumePopover volumePopover;

    /** automix 接缝记号在这条进度条上的平滑，见 {@link AutomixCueMarker.Glide}。 */
    private final AutomixCueMarker.Glide automixCueGlide = new AutomixCueMarker.Glide();
    /** 上一次画记号时归属的那一首；换歌时让记号吸附而不是从上一首飘过来。 */
    private long automixCueSongId = Long.MIN_VALUE;

    /** 弹出层在动画彻底结束前必须继续渲染，否则收起会变成瞬间消失。 */
    private boolean isQualityMenuVisible() {
        return qualityMenuOpen || qualityMenuAnimation > .004;
    }

    /** 音质菜单当前是否打开（NCMScreen.layout() 重建控件时用来迁移状态）。 */
    public boolean isQualityMenuOpen() {
        return qualityMenuOpen;
    }

    /**
     * 重建后恢复「菜单是开着的」这一状态。
     *
     * <p>layout() 每次都会 {@code new ControlsBar()}，用户正开着音质菜单时点一下刷新，
     * 菜单就会无端合上。这里只补状态翻转（含酷狗档位刷新），不做动画回放——重建那一帧
     * 直接呈现打开态最不打扰。动画字段 {@code qualityMenuAnimation} 从 0 起步时
     * {@link #isQualityMenuVisible()} 已经为 true（qualityMenuOpen 短路），不会闪没。</p>
     */
    public void reopenQualityMenu() {
        if (this.qualityMenuOpen) return;
        refreshKuGouQualityOptions();
        this.qualityMenuOpen = true;
    }

    /**
     * 把 automix 的接缝画在这条进度条上：一根竖线是「从这里开始切」，一段半透明带子是
     * 「这一段两首叠在一起」。
     *
     * <p>数据只有这一个来源 {@link CloudMusic#automixCueNow()}，它返回 {@code null} 的几种情况
     * （没预备、预备作废、镜像态、设置里关掉）这里一律<b>不补一个猜测位置</b>：没在交叠的场合
     * 画一条线，等于向玩家预报一次不会发生的混音。</p>
     *
     * <p>带一层平滑，因为计划在真正交接之前会被换掉（整曲包络到货后的回头重算、拖过切点时
     * 顶住交接的那次重算）；换歌那一瞬不做补间，直接吸附。</p>
     */
    private void drawAutomixCue(double barX, double barY, double barWidth, double barHeight, double playbackFraction) {
        AutomixCueMarker cue = CloudMusic.automixCueNow();
        Music track = CloudMusic.currentlyPlaying;
        long songId = track == null ? Long.MIN_VALUE : track.getId();
        if (songId != automixCueSongId) {
            automixCueSongId = songId;
            automixCueGlide.reset();
        }
        if (cue == null) {
            automixCueGlide.reset();
            return;
        }
        AutomixCuePainter.paint(barX, barY, barWidth, barHeight,
                automixCueGlide.update(cue), cue.fading, getAlpha());
    }

    /**
     * 当前这首酷狗曲<b>实际能取</b>的宿主音质；{@code null} 表示还不知道，或这一首不是酷狗。
     *
     * <p>为什么不能同步算：档位可用性要打一次 {@code get_res_privilege/lite}，而音质菜单是
     * 每帧渲染的——在主线程等网络，表现就是「每次点开菜单游戏卡一下」。所以只在<b>打开菜单</b>
     * 那一刻异步补一次，补到之前一律按「全都可用」画。宁可多点一次才发现没有这一档，
     * 也不能为了置灰把界面卡住。</p>
     *
     * <p>{@link #kuGouQualityOptionsKey} 记下这份结论属于哪一首：换歌之后必须作废，
     * 否则会把上一首的可用性糊在新歌上——臻品系列本来就只有少数曲目有。</p>
     */
    private volatile List<Quality> kuGouQualityOptions;
    private volatile String kuGouQualityOptionsKey;

    /** 打开音质菜单时补一次「这一首有哪些档」；不是酷狗就把上一次的结论清掉。 */
    private void refreshKuGouQualityOptions() {
        final Music playing = CloudMusic.currentlyPlaying;
        if (playing == null || !playing.isKuGou()) {
            kuGouQualityOptions = null;
            kuGouQualityOptionsKey = "";
            return;
        }
        final String key = playing.getStableKey();
        if (key == null || key.isEmpty()) return;
        if (kuGouQualityOptions != null && key.equals(kuGouQualityOptionsKey)) return;
        // 换歌了就把上一次的结论立刻作废，而不是等新探测回来才覆盖：留着的话，新歌的菜单会在
        // 探测那段路上先被上一首的可用性置灰一次——那是个假结论。
        kuGouQualityOptions = null;
        kuGouQualityOptionsKey = key;
        MultiThreadingUtil.runAsync(() -> {
            try {
                List<Quality> options = KuGouMusicService.availableHostQualities(playing);
                // 回来时可能已经换歌了：按对象身份认，别把上一首的结论写给这一首。
                if (options.isEmpty() || CloudMusic.currentlyPlaying != playing) return;
                kuGouQualityOptions = options;
                kuGouQualityOptionsKey = key;
            } catch (Throwable throwable) {
                System.err.println("[ControlsBar] 酷狗音质可用性查询失败: " + throwable.getMessage());
            }
        });
    }

    /**
     * 这一档现在能不能选。
     *
     * <p>三个条件缺一不可：已经探到、<b>这份结果就是当前这首歌的</b>、结果里不含这一档。
     * 还没探到（{@code null}）一律放行，探到别的歌时也不许拿过来用——置灰是<b>结论</b>，
     * 不能拿「还不知道」或者「另一首歌的知道」当结论。</p>
     */
    private boolean isQualityUnavailable(Quality option) {
        List<Quality> options = kuGouQualityOptions;
        if (options == null) return false;
        Music playing = CloudMusic.currentlyPlaying;
        String key = playing == null ? null : playing.getStableKey();
        if (key == null || !key.equals(kuGouQualityOptionsKey)) return false;
        return !options.contains(option);
    }

    /** 平滑线性进度，让展开的起步与收尾都不生硬。 */
    private static double smoothProgress(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    public boolean consumeQualityMenuClick(double mouseX, double mouseY, int mouseButton) {
        // 音量浮层与进度条命中带都压在内容区下沿上；它们的输入走每帧轮询，这里只负责不让这一下
        // 同时落到底下的歌曲行（否则拖音量会顺手点播一首歌）。
        if (this.volumePopover != null && this.volumePopover.covers(mouseX, mouseY)) return true;
        if (this.progressStrip != null && this.progressStrip.covers(mouseX, mouseY)) return true;
        if (!qualityMenuOpen || qualityMenuBackground == null) return false;
        boolean withinMenu = mouseX >= qualityMenuBackground.getX() && mouseX <= qualityMenuBackground.getX() + qualityMenuBackground.getWidth()
                && mouseY >= qualityMenuBackground.getY() && mouseY <= qualityMenuBackground.getY() + qualityMenuBackground.getHeight();
        if (!withinMenu) return false;
        this.onMouseClickReceived(mouseX, mouseY, mouseButton);
        return true;
    }

    public ControlsBar() {
    }

    /** 中间传输簇各按钮的边长（SPlayer：两侧 38、播放键 44）。 */
    private static final double TRANSPORT_SIZE = SPlayerStyle.px(38);
    private static final double PLAY_SIZE = SPlayerStyle.px(44);
    private static final double TRANSPORT_GAP = SPlayerStyle.px(10);
    /** 右侧工具条按钮（SButton size="large"）。 */
    private static final double TOOL_SIZE = SPlayerStyle.px(40);
    private static final double TOOL_GAP = SPlayerStyle.px(4);

    /** 中间传输簇的右缘（相对播放栏）：右侧工具条与左侧曲目信息都按它让位。 */
    private double transportRight;
    private double transportLeft;
    /** 右侧工具条最左边一颗的左缘。 */
    private double toolbarLeft;
    /** 静音前的音量，点一下音量键恢复。 */
    private static float volumeBeforeMute = .6f;

    /** 当前曲目能不能操作（不在准备中、有播放时钟、有曲目）。 */
    private boolean hasPlayableTrack() {
        return !presentation.preparing && CloudMusic.currentlyPlaying != null;
    }

    /**
     * 一起听入口能不能用。两种一起听共用这一颗：网易云官方一起听（依赖网易云登录态）与服务器房间
     * （依赖服务端 mod），面板里再分流。都用不上时按钮置灰、不响应。
     */
    private static boolean listenTogetherUsable() {
        MusicPlatform currentPlatform = NeteaseMusicService.getCurrentPlatform();
        boolean netease = currentPlatform == MusicPlatform.NETEASE;
        // 酷狗也有官方房间实现，入口条件与网易云同形。注意这里<b>只看音源、不看登录态</b>：
        // 没登录时这颗按钮照样要能点，人才看得到面板里那句「去登录」。
        boolean kugou = currentPlatform == MusicPlatform.KUGOU;
        // 服务器装了一起听服务端时，即使当前音源没有官方房间也要让入口可点：房间面板本身要能进去看状态。
        return netease || kugou || MpPartyClient.serverPresent();
    }

    /** 打开全屏歌词页：封面与「歌词」按钮共用，起点取小封面的几何，转场才从封面飞出去。 */
    private void openLyricsFrom(RoundedImageWidget cover) {
        if (presentation.preparing) return;
        if (CloudMusic.currentlyPlaying == null) return;
        // 起点必须在造面板之前交出去：面板构造函数会兜底启动转场时钟，那之后再报
        // 起点就晚了一帧，封面会从上一次的位置起飞。
        CoverHeroTransition.open(cover.getX(), cover.getY(), cover.getWidth(), cover.getRadius());
        NCMScreen.getInstance().musicLyricsPanel = MusicLyricsPanel.takePrewarmed(CloudMusic.currentlyPlaying);
    }

    private static String playModeIcon(CloudMusic.PlayMode mode) {
        if (CloudMusic.isPersonalFmActive()) return SPlayerIcons.INFINITY;
        if (mode == null) return SPlayerIcons.PLAY_ORDER;
        switch (mode) {
            case Random: return SPlayerIcons.SHUFFLE;
            case LoopSingle: return SPlayerIcons.REPEAT_ONE;
            case LoopInList: return SPlayerIcons.REPEAT;
            case Sequential:
            default: return SPlayerIcons.PLAY_ORDER;
        }
    }

    @Override
    public void onInit() {
        // 底：surfacePanel + 顶边一条 border-primary/10（SPlayer footer 的 border-t），频谱仍画在底上。
        RectWidget bg = new RectWidget() {
            @Override public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);
                // After the bar background, before any labels/buttons; inherits player clip and motion.
                com.muoniumplayer.core.rendering.MusicSpectrumRenderer.renderPlayer(
                        getX(), getY(), getWidth(), getHeight(), getAlpha());
                SPlayerStyle.line(getX(), getY() + SPlayerStyle.px(.5), getX() + getWidth(), getY() + SPlayerStyle.px(.5),
                        SPlayerStyle.px(1), SPlayerStyle.a(SPlayerStyle.primary(), .10, getAlpha()));
            }
        };

        this.addChild(bg);

        RoundedImageWidget playingCover = new RoundedImageWidget(() -> {
            Music displayed = presentation.displayTrack;
            return displayed == null ? null : CloudMusic.preferredCoverLocation(displayed,
                    displayed.getSmallCoverLocation());
        }, 0, 0, 0, 0) {
            private float hover;

            @Override public void onRender(double mouseX, double mouseY) {
                int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
                // 新封面未到也显示目标曲目的独立占位，不能拿旧封面冒充新歌。
                roundedRect(getX(), getY(), getWidth(), getHeight(), getRadius(),
                        SPlayerStyle.a(SPlayerStyle.onSurface(), .08, getAlpha()));
                if (presentation.preparing) {
                    for (int dot = 0; dot < 3; dot++) roundedRect(
                            getX() + getWidth() * .5 - 5 + dot * 4, getY() + getHeight() * .5 - 1,
                            2, 2, 1, reAlpha(accent, (float) (getAlpha() * (.4 + .6 * selectionMotion.pulse(dot)))));
                }
                super.onRender(mouseX, mouseY);
                // Exactly one cooperative preparation slice per idle frame. Hover can start
                // it sooner, but never adds a second slice or competes with the player entrance.
                if (!presentation.preparing && presentation.displayTrack != null
                        && NCMScreen.getInstance().musicLyricsPanel == null
                        && !PlayerIslandTransition.isRunning() && !CoverHeroTransition.isRunning()) {
                    String trackKey = presentation.displayTrack.getStableKey();
                    if (!trackKey.equals(lyricPrewarmTrackKey)) {
                        lyricPrewarmTrackKey = trackKey;
                        lyricPrewarmStableFrames = 0;
                    }
                    lyricPrewarmStableFrames = Math.min(6, lyricPrewarmStableFrames + 1);
                    if (lyricPrewarmStableFrames >= 6 || this.testHovered(mouseX, mouseY)) {
                        MusicLyricsPanel.prewarm(presentation.displayTrack);
                    }
                } else {
                    lyricPrewarmTrackKey = null;
                    lyricPrewarmStableFrames = 0;
                }
                // SPlayer：悬停时封面压暗 40% 并浮出一枚向上的箭头 —— 点它展开全屏播放页。
                this.hover = SPlayerStyle.approach(this.hover,
                        this.isHovering() && presentation.displayTrack != null ? 1f : 0f);
                if (this.hover > 0f) {
                    roundedRect(getX(), getY(), getWidth(), getHeight(), getRadius(),
                            SPlayerStyle.a(0x000000, .40 * this.hover, getAlpha()));
                    SPlayerIcons.draw(SPlayerIcons.CHEVRON_UP, getX() + getWidth() * .5, getY() + getHeight() * .5,
                            SPlayerStyle.px(24), SPlayerStyle.a(0xFFFFFF, this.hover, getAlpha()));
                }
                double light = selectionMotion.burst() * .6 + selectionMotion.settledGlow() * .65;
                if (light > .01) roundedOutline(getX(), getY(), getWidth(), getHeight(), getRadius(), .8,
                        new java.awt.Color(reAlpha(accent, (float) (getAlpha() * light)), true));
            }
        };

        this.addChild(playingCover);

        // 隐藏小封面这件事必须挂在 bg 的每帧回调上：被 setHidden 的组件自己的 beforeRenderCallback
        // 不再执行，写在 playingCover 上就再也取消不掉隐藏。bg 永远可见，而且它是第一个子组件、
        // 每帧都排在 playingCover 之前，是这条链上唯一安全的挂点。
        bg.setBeforeRenderCallback(() -> {
            presentation = CloudMusic.playbackPresentation();
            selectionMotion.update(presentation.displayKey(), presentation.requestNanos,
                    presentation.preparing, System.nanoTime(), SheetClock.speed());

            bg.setMargin(0);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            // 底栏这块底同样压在面板根底之上，属于内层：不接玻璃的话，整页从上到下就它最黑，
            // 玩家会读成「玻璃只做了上半截」。setFillAlpha 只动这张底，bg 后面的封面与控件不跟。
            bg.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER,
                    .95f - NCMTheme.getLiquidGlassAmount() * .22f));
            playingCover.setHidden(CoverHeroTransition.hidesSmallCover());
        });

        playingCover.setTransformations(() -> {
            double centerX = playingCover.getX() + playingCover.getWidth() * .5;
            double centerY = playingCover.getY() + playingCover.getHeight() * .5;
            double scale = .94 + .06 * selectionMotion.reveal();
            api.getGLStateManager().translate(centerX, centerY + selectionMotion.offset() * .3, 0);
            api.getGLStateManager().scale(scale, scale, 1);
            api.getGLStateManager().translate(-centerX, -centerY, 0);
        });
        playingCover
                .setLinearFilter(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    // TrackInfo 封面：size-14 rounded-lg，左边距 px-3。
                    double size = Math.min(SPlayerStyle.px(56), this.getHeight() - SPlayerStyle.px(12));
                    playingCover
                            .setClickable(presentation.displayTrack != null && !presentation.preparing)
                            .setShouldOverrideMouseCursor(!presentation.preparing)
                            .setAlpha(selectionMotion.contentAlpha())
                            .setBounds(SPlayerStyle.px(12), (this.getHeight() - size) * .5, size, size)
                            .setRadius(SPlayerStyle.RADIUS_LG);
                })
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    openLyricsFrom(playingCover);
                    return true;
                });

        // ── 中间：播放顺序 / 上一首 / 播放 / 下一首 / 一起听 ───────────────────────
        SPlayerButton playMode = SPlayerButton.icon(() -> playModeIcon(CloudMusic.playMode), SPlayerButton.Variant.GHOST)
                .primaryTint(true)
                .disabled(CloudMusic::isPersonalFmActive)
                .iconSize(SPlayerStyle.px(20));
        SPlayerButton prev = SPlayerButton.icon(SPlayerIcons.SKIP_BACK, SPlayerButton.Variant.GHOST)
                .primaryTint(true)
                .disabled(() -> !hasPlayableTrack() || CloudMusic.isPersonalFmActive())
                .iconSize(SPlayerStyle.px(20));
        SPlayerButton playPause = SPlayerButton.icon(
                        // 镜像态下出声的是外部播放器，player 恒为 null：图标一律问「现在有没有在响」，
                        // 它同时覆盖内部暂停与外部暂停两种情况。
                        () -> CloudMusic.isAudibleNow() ? SPlayerIcons.PAUSE : SPlayerIcons.PLAY,
                        SPlayerButton.Variant.SECONDARY)
                .disabled(() -> presentation.displayTrack == null && !presentation.preparing)
                .iconSize(SPlayerStyle.px(22));
        SPlayerButton next = SPlayerButton.icon(SPlayerIcons.SKIP_FORWARD, SPlayerButton.Variant.GHOST)
                .primaryTint(true)
                .disabled(() -> !hasPlayableTrack())
                .iconSize(SPlayerStyle.px(20));
        SPlayerButton listenTogether = SPlayerButton.icon(SPlayerIcons.USERS, SPlayerButton.Variant.GHOST)
                .primaryTint(true)
                .active(() -> ListenTogetherService.isActive() || MpPartyClient.inRoom())
                .disabled(() -> !listenTogetherUsable())
                .iconSize(SPlayerStyle.px(20));
        this.addChild(playMode, prev, playPause, next, listenTogether);

        playPause.setOnClickCallback((x, y, i) -> {
            // 走 CloudMusic.togglePlayPause() 而不是直接动 player：一起听房间里传输控制权
            // 归服务端，那条路径上有房间拦截（房主/管理员变成房间指令，普通成员被挡住）。
            CloudMusic.togglePlayPause();
            return true;
        });
        prev.setOnClickCallback((x, y, i) -> {
            // 直播没有上一首：按了只提示，不吞掉点击的反馈。
            if (com.muoniumplayer.core.ncm.music.LiveStreamSession.isActive()) {
                DownloadDynamicIsland.showModelOperationResult("广播电台",
                        "广播电台没有上一首/下一首，切台请在广播页点选", true);
                return true;
            }
            if (CloudMusic.hasPlaybackClock() && CloudMusic.currentlyPlaying != null)
                CloudMusic.prev();
            return true;
        });
        next.setOnClickCallback((x, y, i) -> {
            // 直播没有下一首：同上一首，给一句说明而不是静默吞掉。
            if (com.muoniumplayer.core.ncm.music.LiveStreamSession.isActive()) {
                DownloadDynamicIsland.showModelOperationResult("广播电台",
                        "广播电台没有上一首/下一首，切台请在广播页点选", true);
                return true;
            }
            if (CloudMusic.hasPlaybackClock() && CloudMusic.currentlyPlaying != null)
                CloudMusic.next();
            return true;
        });
        playMode.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton == 0) {
                if (!CloudMusic.isPersonalFmActive()) CloudMusic.cyclePlayMode();
            } else if (mouseButton == 1) {
                // 右键切换无缝交接，就在它所应用的模式旁边。
                boolean enabled = !AutomixSettings.isEnabled();
                AutomixSettings.setEnabled(enabled);
                DownloadDynamicIsland.showAutomixToggle(enabled, enabled
                        ? String.format(java.util.Locale.ROOT, "重叠 %.1fs · 下一首将提前解码",
                                AutomixSettings.getOverlapSeconds())
                        : "切歌恢复为原有的直接切换");
            }
            return true;
        });
        // 说明跟着当前模式走：这颗按钮既是状态显示（当前是什么顺序）也是切换入口，
        // 只写「切换播放顺序」的话用户看不出现在是哪一档；右键的无缝交接不写永远没人发现。
        playMode.setTooltip(() -> CloudMusic.isPersonalFmActive()
                ? "私人FM（播放顺序不可用）"
                : "播放顺序：" + CloudMusic.playMode.getDisplayName() + "（右键无缝交接）");
        listenTogether.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().openListenTogether();
            return true;
        });
        // 房间开着时与点亮状态同步加个「进行中」，静态时只报功能名。
        listenTogether.setTooltip(() -> ListenTogetherService.isActive() || MpPartyClient.inRoom()
                ? "一起听（进行中）"
                : "一起听");

        // ── 顶边进度条 ─────────────────────────────────────────────────────────
        ProgressStrip progress = new ProgressStrip();
        this.progressStrip = progress;
        // ── 曲目信息（歌名 + 喜欢 / 歌手） ───────────────────────────────────────
        TrackText trackText = new TrackText(playingCover);
        this.addChild(trackText);

        SPlayerButton like = SPlayerButton.icon(SPlayerIcons.HEART, SPlayerButton.Variant.GHOST)
                .primaryTint(true)
                .solidIcon(() -> presentation.displayTrack != null && LikeButton.isLiked(presentation.displayTrack))
                .iconSize(SPlayerStyle.px(18));
        like.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            Music music = presentation.displayTrack;
            if (music == null || presentation.preparing) return true;
            LikeButton.toggleLiked(music, null, NCMScreen.getInstance()::openConfirmation);
            return true;
        });
        like.setTooltip(() -> presentation.displayTrack != null && LikeButton.isLiked(presentation.displayTrack)
                ? "取消喜欢" : "喜欢");
        this.addChild(like);

        // ── 右侧工具条：时间 / 音质 / 音效 / 音量 / 歌词 / 待播 ───────────────────
        TimeChip timeChip = new TimeChip();
        this.addChild(timeChip);

        QualityChip qualitySelector = new QualityChip();
        this.addChild(qualitySelector);
        qualitySelector.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                // 打开的那一次顺手去问一次「这一首有哪些档」。这个请求只能挂在用户动作上
                // 异步发：菜单是每帧渲染的，放在渲染回调里就是每帧一次网络往返。
                if (!qualityMenuOpen) refreshKuGouQualityOptions();
                // 只翻转状态：是否渲染交给动画进度判断，收起时弹出层要留到动画播完。
                qualityMenuOpen = !qualityMenuOpen;
            }
            return true;
        });

        SPlayerButton viperToggle = SPlayerButton.icon(SPlayerIcons.SLIDERS, SPlayerButton.Variant.GHOST)
                .active(() -> ViperRuntime.isEnabled()
                        || com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings.isEnabled())
                .primaryTint(false)
                .iconSize(SPlayerStyle.px(20));
        viperToggle.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().openViperEffects();
            return true;
        });
        // ViPER 或场景声学任一开启时点亮；环境选曲不是输出音效，不参与这个指示。
        viperToggle.setTooltip(() -> ViperRuntime.isEnabled()
                || com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings.isEnabled()
                ? "音效工作台（ViPER · 图形 EQ · 场景声学 · 音效开启中）"
                : "音效工作台（ViPER · 图形 EQ · 场景声学）");
        this.addChild(viperToggle);

        SPlayerButton volume = SPlayerButton.icon(() -> {
            float level = CloudMusic.displayVolume();
            return level <= .001f ? SPlayerIcons.VOLUME_X : level < .5f ? SPlayerIcons.VOLUME_1 : SPlayerIcons.VOLUME_2;
        }, SPlayerButton.Variant.GHOST).iconSize(SPlayerStyle.px(20));
        volume.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            // SPlayer：点音量键是静音/恢复，滑条在悬停弹出的那块浮层里。
            float level = CloudMusic.getVolume();
            if (level > .001f) {
                volumeBeforeMute = level;
                CloudMusic.setVolume(0f, true);
            } else {
                CloudMusic.setVolume(Math.max(.05f, volumeBeforeMute), true);
            }
            return true;
        });
        volume.setTooltip(() -> "音量（右键滑条切换播放时压掉游戏音乐 · "
                + (com.muoniumplayer.core.ncm.music.VanillaMusicDucker.isEnabled() ? "开" : "关") + "）");
        this.addChild(volume);

        SPlayerButton lyrics = SPlayerButton.icon(SPlayerIcons.CAPTIONS, SPlayerButton.Variant.GHOST)
                .disabled(() -> !hasPlayableTrack())
                .iconSize(SPlayerStyle.px(20));
        lyrics.setTooltip("歌词");
        lyrics.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            openLyricsFrom(playingCover);
            return true;
        });
        this.addChild(lyrics);

        // 下一首播放列表：用 list-music（SPlayer 播放栏同位按钮）。抽屉开着时按 tertiary 的底色点亮。
        SPlayerButton queueToggle = SPlayerButton.icon(SPlayerIcons.LIST_MUSIC, SPlayerButton.Variant.GHOST)
                .active(() -> {
                    PlayQueuePanel panel = NCMScreen.getInstance().getPlayQueuePanel();
                    return panel != null && panel.isOpen();
                })
                .primaryTint(false)
                .iconSize(SPlayerStyle.px(20));
        queueToggle.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            PlayQueuePanel queuePanel = NCMScreen.getInstance().getPlayQueuePanel();
            if (queuePanel != null) queuePanel.toggle();
            return true;
        });
        // 数量与抽屉里的列表读同一份（PlayQueueRoomBridge），说明里带上省得点开数。
        queueToggle.setTooltip(() -> {
            int queued = PlayQueueRoomBridge.displayQueuedCount();
            return queued > 0 ? "下一首播放列表 · 待播 " + queued + " 首" : "下一首播放列表";
        });
        this.addChild(queueToggle);

        // 待播数量贴在图标右上角：作为图标的子组件，继承它的透明度与显隐。
        // 房间模式下数的是一起听歌单，与抽屉一致。
        LabelWidget queueCount = new LabelWidget(() -> {
            int queued = PlayQueueRoomBridge.displayQueuedCount();
            return queued > 0 ? String.valueOf(Math.min(99, queued)) : "";
        }, FontManager.pf10bold);
        queueToggle.addChild(queueCount);
        queueCount.setClickable(false);
        queueCount.setBeforeRenderCallback(() -> queueCount
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setPosition(queueToggle.getWidth() - queueCount.getWidth() * .5 - SPlayerStyle.px(4), -SPlayerStyle.px(1)));

        VolumePopover volumePopover = new VolumePopover(volume);
        this.volumePopover = volumePopover;

        // 播放栏自己的每帧回调：按 SPlayer 的 grid [1fr auto 1fr] 排三段。
        // 左段贴左、右段贴右、中段居中；面板窄到右段压进中段时，右段从「时间」开始逐个让位。
        this.setBeforeRenderCallback(() -> {
            this.advanceQualityMenu();
            double width = this.getWidth();
            double height = this.getHeight();
            double centerY = height * .5;

            double clusterWidth = TRANSPORT_SIZE * 4 + PLAY_SIZE + TRANSPORT_GAP * 4 + SPlayerStyle.px(4) * 2;
            double clusterLeft = width * .5 - clusterWidth * .5;
            double cursor = clusterLeft;
            for (SPlayerButton button : new SPlayerButton[]{playMode, prev, playPause, next, listenTogether}) {
                double size = button == playPause ? PLAY_SIZE : TRANSPORT_SIZE;
                if (button == playPause) cursor += SPlayerStyle.px(4);
                button.setBounds(cursor, centerY - size * .5, size, size);
                cursor += size + (button == playPause ? SPlayerStyle.px(4) : 0) + TRANSPORT_GAP;
            }
            this.transportLeft = clusterLeft;
            this.transportRight = clusterLeft + clusterWidth;

            // 右段从右往左：待播、歌词、音量、音效、音质、时间。
            double right = width - SPlayerStyle.px(12);
            SPlayerButton[] tools = {queueToggle, lyrics, volume, viperToggle};
            for (SPlayerButton tool : tools) {
                right -= TOOL_SIZE;
                tool.setBounds(right, centerY - TOOL_SIZE * .5, TOOL_SIZE, TOOL_SIZE);
                right -= TOOL_GAP;
            }
            double chipHeight = SPlayerStyle.px(24);
            double qualityWidth = qualitySelector.preferredWidth();
            right -= SPlayerStyle.px(4) + qualityWidth;
            qualitySelector.setBounds(right, centerY - chipHeight * .5, qualityWidth, chipHeight);
            double timeWidth = timeChip.preferredWidth();
            double timeLeft = right - SPlayerStyle.px(8) - timeWidth;
            boolean timeFits = timeLeft > this.transportRight + SPlayerStyle.px(16);
            timeChip.setHidden(!timeFits);
            timeChip.setBounds(timeLeft, centerY - chipHeight * .5, timeWidth, chipHeight);
            this.toolbarLeft = timeFits ? timeLeft : right;
            boolean qualityFits = right > this.transportRight + SPlayerStyle.px(8);
            qualitySelector.setHidden(!qualityFits && !qualityMenuOpen);
            viperToggle.setHidden(viperToggle.getRelativeX() < this.transportRight + SPlayerStyle.px(4));
            if (!qualityFits) this.toolbarLeft = viperToggle.isHidden() ? volume.getRelativeX() : viperToggle.getRelativeX();

            // 把待播按钮的右边缘交给抽屉，抽屉据此右对齐 —— 点开之后视线不用再横穿半屏去找它。
            PlayQueuePanel queuePanel = NCMScreen.getInstance().getPlayQueuePanel();
            if (queuePanel != null) {
                queuePanel.setAnchorRight(queueToggle.getX() + queueToggle.getWidth());
            }

            // 喜欢按钮紧跟在歌名后面（TrackInfo 的 title-trailing）。
            double likeSize = SPlayerStyle.px(28);
            like.setHidden(presentation.displayTrack == null);
            like.setBounds(trackText.titleRight() + SPlayerStyle.px(2),
                    trackText.titleCenterY() - likeSize * .5, likeSize, likeSize);
            like.setAlpha(selectionMotion.contentAlpha());
        });

        // 进度条与浮层最后加入：画在所有控件之上、也最先拿到点击。
        this.addChild(progress);
        this.addChild(volumePopover);
        this.buildQualityMenu(qualitySelector);
    }

    /**
     * 曲目信息两行：{@code font-bold text-base} 的歌名、{@code text-sm text-on-surface-variant}
     * 的歌手（· 专辑）。宽度一直让到中间传输簇左侧，过长截断。准备新歌时副标题改成主色的提示。
     */
    private final class TrackText extends AbstractWidget<TrackText> {
        private final RoundedImageWidget cover;
        private double titleRight;
        private double titleCenterY;

        private TrackText(RoundedImageWidget cover) {
            this.cover = cover;
            this.setClickable(false);
        }

        double titleRight() {
            return this.titleRight;
        }

        double titleCenterY() {
            return this.titleCenterY;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            ControlsBar bar = ControlsBar.this;
            double left = this.cover.getRelativeX() + this.cover.getWidth() + SPlayerStyle.px(12);
            double limit = Math.max(0.0, bar.transportLeft - SPlayerStyle.px(24) - left);
            this.setBounds(left, 0, limit, bar.getHeight());
            float alpha = this.getAlpha() * selectionMotion.contentAlpha();
            double offset = selectionMotion.offset();
            double centerY = bar.getHeight() * .5;
            CFontRenderer titleFont = SPlayerStyle.textBase(true);
            CFontRenderer subFont = SPlayerStyle.textSm(false);
            Music playing = presentation.displayTrack;
            String title = playing == null ? "未在播放" : presentation.displayTrack.getName();
            String sub;
            if (playing == null) {
                sub = "选一首歌开始吧";
            } else if (presentation.preparing) {
                sub = "准备播放 · " + playing.getArtistsName() + " · 原曲继续播放";
            } else {
                // 专辑可以是 null（单曲、电台、以及一起听下发的曲目都可能没有专辑），
                // 那时只显示歌手，不要为了拼一个「 - 」把整条播放条崩掉。
                String album = playing.getAlbum() == null ? null : playing.getAlbum().getName();
                sub = album == null || album.isEmpty() ? playing.getArtistsName()
                        : playing.getArtistsName() + " - " + album;
            }
            double likeReserve = playing == null ? 0 : SPlayerStyle.px(32);
            double titleWidth = Math.min(titleFont.getStringWidthD(title), Math.max(0.0, limit - likeReserve));
            this.titleCenterY = centerY - SPlayerStyle.px(10) + offset;
            this.titleRight = left + titleWidth;
            double x = this.getX();
            SPlayerStyle.text(titleFont, title, x, this.getY() + this.titleCenterY, titleWidth + .5,
                    SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
            SPlayerStyle.text(subFont, sub, x, this.getY() + centerY + SPlayerStyle.px(11) + offset, limit,
                    presentation.preparing ? SPlayerStyle.a(SPlayerStyle.primary(), 1.0, alpha)
                            : SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), 1.0, alpha));
        }
    }

    /**
     * 「02:55 / 03:43」时间标签（SPlayer {@code PlayerTimeInfo}）：{@code text-xs text-on-surface-variant
     * tabular-nums}，悬停一块 {@code bg-on-surface/8} 的圆角底。直播显示已收听时长 + 主色 LIVE。
     */
    private final class TimeChip extends AbstractWidget<TimeChip> {
        private float hover;

        private TimeChip() {
            this.setClickable(false);
        }

        private String text() {
            if (presentation.preparing) return "准备中";
            if (com.muoniumplayer.core.ncm.music.LiveStreamSession.isActive()) {
                return formatDuration((float) com.muoniumplayer.core.ncm.music.LiveStreamSession.elapsedMillis()) + " · LIVE";
            }
            float total = (float) CloudMusic.durationMsNow();
            if (total <= 0.0f) return "00:00 / 00:00";
            return formatDuration((float) CloudMusic.positionMsNow()) + " / " + formatDuration(total);
        }

        double preferredWidth() {
            return SPlayerStyle.textXs(false).getStringWidthD(this.text()) + SPlayerStyle.px(12);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.hover = SPlayerStyle.approach(this.hover,
                    this.isHovered(mouseX, mouseY, getX(), getY(), getWidth(), getHeight()) ? 1f : 0f);
            float alpha = this.getAlpha();
            if (this.hover > 0f) {
                SPlayerStyle.fill(getX(), getY(), getWidth(), getHeight(), SPlayerStyle.RADIUS_MD,
                        SPlayerStyle.a(SPlayerStyle.onSurface(), .08 * this.hover, alpha));
            }
            boolean live = com.muoniumplayer.core.ncm.music.LiveStreamSession.isActive();
            SPlayerStyle.text(SPlayerStyle.textXs(false), this.text(), getX() + SPlayerStyle.px(6),
                    getY() + getHeight() * .5, 0,
                    SPlayerStyle.a(live ? SPlayerStyle.primary() : SPlayerStyle.onSurfaceVariant(), 1.0, alpha));
        }
    }

    /**
     * 音质标签（SPlayer {@code QualityControl}）：{@code px-2 py-1 rounded-md border text-xs}，
     * 边框 {@code border-on-surface-variant/30}，悬停 /60。点开向上的两列档位菜单。
     */
    private final class QualityChip extends AbstractWidget<QualityChip> {
        private float hover;

        private QualityChip() {
            this.setShouldOverrideMouseCursor(true);
        }

        private String text() {
            return formatQuality(CloudMusic.quality);
        }

        double preferredWidth() {
            return Math.max(SPlayerStyle.px(36), SPlayerStyle.textXs(false).getStringWidthD(this.text()) + SPlayerStyle.px(16));
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.hover = SPlayerStyle.approach(this.hover, this.isHovering() || qualityMenuOpen ? 1f : 0f);
            float alpha = this.getAlpha();
            int variant = SPlayerStyle.onSurfaceVariant();
            SPlayerStyle.stroke(getX(), getY(), getWidth(), getHeight(), SPlayerStyle.RADIUS_MD, SPlayerStyle.px(1),
                    SPlayerStyle.a(variant, .30 + .30 * this.hover, alpha));
            CFontRenderer font = SPlayerStyle.textXs(false);
            String text = this.text();
            double textWidth = font.getStringWidthD(text);
            SPlayerStyle.text(font, text, getX() + (getWidth() - textWidth) * .5, getY() + getHeight() * .5,
                    getWidth(), SPlayerStyle.a(variant, 1.0, alpha));
        }
    }

    /**
     * 顶边进度条（SPlayer 播放栏的 {@code SSlider}：贴在播放栏上沿、垂直居中于那条边，
     * {@code track-height 3}、悬停才出 12px 的拇指，带时间气泡）。
     *
     * <p>点按即跳转；按住拖动时拇指跟手、松手才跳（SPlayer 的 {@code @drag-end}），拖动过程不
     * 反复 seek 打断音频。命中闸门与旧进度条一致：模态浮层压着、纵向滚动手势、准备新歌时都不响应。</p>
     */
    private final class ProgressStrip extends AbstractWidget<ProgressStrip> {
        private boolean pressed;
        private boolean dragging;
        private double dragFraction;
        private float hover;

        /** 进度条命中带压在内容区下沿上：那里的点击是给进度条的（跳转走每帧轮询）。 */
        boolean covers(double mouseX, double mouseY) {
            return !presentation.preparing && this.isHovered(mouseX, mouseY, getX(), getY(), getWidth(), getHeight());
        }

        private ProgressStrip() {
            this.setShouldOverrideMouseCursor(true);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            ControlsBar bar = ControlsBar.this;
            // 命中带：边线以上只伸 4（压着内容区的那半截越窄越好），以下 7。
            this.setBounds(0, -SPlayerStyle.px(4), bar.getWidth(), SPlayerStyle.px(11));
            float alpha = this.getAlpha();
            double x = this.getX();
            double width = this.getWidth();
            double lineY = bar.getY();
            double track = SPlayerStyle.px(3);
            int onSurface = SPlayerStyle.onSurface();
            int primary = SPlayerStyle.primary();

            boolean hovered = this.testHovered(mouseX, mouseY);
            boolean leftDown = Mouse.isButtonDown(0);
            boolean modalOpen = NCMScreen.getInstance().isModalOverlayActive();

            if (presentation.preparing) {
                this.pressed = false;
                this.dragging = false;
                SPlayerStyle.fill(x, lineY - track * .5, width, track, track * .5, SPlayerStyle.a(onSurface, .12, alpha));
                // 不把旧歌的 01:14 显示成新歌进度，也不让点击这根等待轨去 seek 旧歌。
                double travel = .5 - .5 * Math.cos(selectionMotion.travel() * Math.PI * 2);
                double span = width * .24;
                SPlayerStyle.fill(x + (width - span) * travel, lineY - track * .5, span, track, track * .5,
                        SPlayerStyle.a(primary, .8, alpha));
                return;
            }

            float total = (float) CloudMusic.durationMsNow();
            boolean seekable = CloudMusic.hasPlaybackClock() && total > 0.0f;
            double pointer = Math.max(0.0, Math.min(1.0, (mouseX - x) / Math.max(1.0, width)));
            if (modalOpen || TouchGestures.get().isVerticalDrag()) {
                // 模态浮层（歌词选词、建单、导入等）压在界面上时这一帧的鼠标状态不属于播放条：
                // 点遮罩的那一下会同时落在这里，把底下的进度条当成拖到起点 seek 到 0。
                this.pressed = false;
                this.dragging = false;
            } else if (leftDown) {
                if (!this.pressed && hovered && seekable) {
                    this.pressed = true;
                }
                if (this.pressed) {
                    this.dragging = true;
                    this.dragFraction = pointer;
                }
            } else if (this.pressed) {
                this.pressed = false;
                this.dragging = false;
                // 统一走 CloudMusic.seekToMillis()：里面既有一起听房间的拦截，
                // 也负责 seek 后按真实音频时钟对齐歌词与进度。
                if (seekable) CloudMusic.seekToMillis((float) (this.dragFraction * total));
            }

            this.hover = SPlayerStyle.approach(this.hover, hovered || this.dragging ? 1f : 0f);
            // 显示不过音频时钟闸：恢复态（上次会话的快照）没有可操作的时钟，但冻结的进度照样要画出来；
            // 只有「能不能拖」才问时钟。
            double fraction = this.dragging ? this.dragFraction
                    : total > 0.0f ? Math.max(0.0, Math.min(1.0, CloudMusic.positionMsNow() / total)) : 0.0;
            SPlayerStyle.fill(x, lineY - track * .5, width, track, 0, SPlayerStyle.a(onSurface, .12, alpha));
            if (fraction > 0) {
                SPlayerStyle.fill(x, lineY - track * .5, width * fraction, track, 0, SPlayerStyle.a(primary, 1.0, alpha));
            }
            // automix 的接缝画在这条进度条上（记号本身不可点）。
            drawAutomixCue(x, lineY - track * .5, width, track,
                    seekable ? CloudMusic.positionMsNow() / total : -1.0);

            if (this.hover > 0f && seekable) {
                double thumb = SPlayerStyle.px(12) * (.6 + .4 * this.hover);
                double thumbX = x + width * fraction;
                SPlayerStyle.circle(thumbX, lineY, thumb * .5, SPlayerStyle.a(primary, this.hover, alpha));
                // 时间气泡：bg-surface-bright rounded-lg text-xs，跟着指针（拖动时跟着拇指）。
                double bubbleFraction = this.dragging ? this.dragFraction : pointer;
                String label = formatDuration((float) (bubbleFraction * total));
                CFontRenderer font = SPlayerStyle.textXs(true);
                double bubbleWidth = font.getStringWidthD(label) + SPlayerStyle.px(16);
                double bubbleHeight = SPlayerStyle.px(22);
                double bubbleX = Math.max(x, Math.min(x + width - bubbleWidth, x + width * bubbleFraction - bubbleWidth * .5));
                double bubbleY = lineY - SPlayerStyle.px(10) - bubbleHeight;
                SPlayerStyle.fill(bubbleX, bubbleY, bubbleWidth, bubbleHeight, SPlayerStyle.RADIUS_LG,
                        SPlayerStyle.a(SPlayerStyle.bright(), this.hover, alpha));
                SPlayerStyle.text(font, label, bubbleX + SPlayerStyle.px(8), bubbleY + bubbleHeight * .5, 0,
                        SPlayerStyle.a(onSurface, this.hover, alpha));
            }
        }
    }

    /**
     * 音量浮层：悬停音量键时在它正上方弹出一块 {@code bg-surface-bright rounded-lg} 的小板，
     * 里面是与全屏页共用的 {@link VolumeControl}（拖动、右键压游戏音乐都在它里面）。
     * 鼠标留在键或板上、或正在拖动时保持展开。
     */
    private final class VolumePopover extends AbstractWidget<VolumePopover> {
        private final SPlayerButton anchor;
        private final VolumeControl control = new VolumeControl();
        private float reveal;
        private boolean engaged;

        /** 浮层展开时它的矩形归它：点在上面不能落到底下的歌曲行。 */
        boolean covers(double mouseX, double mouseY) {
            return this.reveal > .3f && this.isHovered(mouseX, mouseY, getX(), getY(), getWidth(), getHeight());
        }

        private VolumePopover(SPlayerButton anchor) {
            this.anchor = anchor;
            this.setClickable(false);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            double width = SPlayerStyle.px(200);
            double height = SPlayerStyle.px(40);
            double localX = this.anchor.getRelativeX() + this.anchor.getWidth() * .5 - width * .5;
            localX = Math.max(SPlayerStyle.px(8), Math.min(ControlsBar.this.getWidth() - width - SPlayerStyle.px(8), localX));
            double localY = -height - SPlayerStyle.px(8);
            this.setBounds(localX, localY, width, height);
            boolean overAnchor = this.anchor.isHovering();
            boolean overPanel = this.reveal > .5f && this.isHovered(mouseX, mouseY, getX(), getY() - SPlayerStyle.px(4),
                    getWidth(), getHeight() + SPlayerStyle.px(12));
            boolean modal = NCMScreen.getInstance().isModalOverlayActive();
            boolean open = !modal && (overAnchor || overPanel || this.engaged);
            this.reveal = SPlayerStyle.approach(this.reveal, open ? 1f : 0f);
            if (this.reveal <= .01f) {
                this.engaged = false;
                return;
            }
            float alpha = this.getAlpha() * this.reveal;
            double y = getY() + SPlayerStyle.px(6) * (1f - this.reveal);
            SPlayerStyle.fill(getX(), y, width, height, SPlayerStyle.RADIUS_LG, SPlayerStyle.a(SPlayerStyle.bright(), 1.0, alpha));
            SPlayerStyle.stroke(getX(), y, width, height, SPlayerStyle.RADIUS_LG, SPlayerStyle.px(1),
                    SPlayerStyle.a(SPlayerStyle.onSurface(), .08, alpha));
            CFontRenderer font = SPlayerStyle.textXs(false);
            String percent = Math.round(CloudMusic.displayVolume() * 100) + "%";
            double percentWidth = font.getStringWidthD("100%");
            double sliderX = getX() + SPlayerStyle.px(10);
            double sliderWidth = width - SPlayerStyle.px(20) - percentWidth - SPlayerStyle.px(8);
            this.engaged = this.control.render(mouseX, mouseY, sliderX, y + height * .5, sliderWidth, alpha)
                    && Mouse.isButtonDown(0);
            SPlayerStyle.text(font, percent, getX() + width - SPlayerStyle.px(10) - percentWidth, y + height * .5, 0,
                    SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
        }
    }

    /**
     * 推进音质菜单的展开/收起。挂在播放栏自己的每帧回调上：标签在窄面板下会被隐藏，
     * 被隐藏的控件不再执行回调，动画挂在它身上就会停在半路。
     */
    private void advanceQualityMenu() {
        long clockNow = System.nanoTime();
        double frameMs = qualityMenuLastFrameNanos == 0L ? 0.0
                : Math.min(QUALITY_MAX_FRAME_MS, (clockNow - qualityMenuLastFrameNanos) / 1_000_000.0);
        qualityMenuLastFrameNanos = clockNow;
        double animationDuration = Math.max(1.0,
                (qualityMenuOpen ? QUALITY_OPEN_MS : QUALITY_CLOSE_MS) / SheetClock.speed());
        qualityMenuAnimation = Math.max(0.0, Math.min(1.0, qualityMenuAnimation
                + (qualityMenuOpen ? frameMs : -frameMs) / animationDuration));
        // 隐藏状态只在这里统一维护：被隐藏的组件不再执行自己的回调，没法自己复活。
        boolean menuVisible = isQualityMenuVisible();
        if (qualityMenuBackground != null) qualityMenuBackground.setHidden(!menuVisible);
        if (qualityOptions != null) {
            for (RoundedRectWidget menuOption : qualityOptions) {
                if (menuOption != null) menuOption.setHidden(!menuVisible);
            }
        }
    }

    /**
     * 音质菜单：从音质标签向上长出的一块 {@code bg-surface-bright} 小板（SPlayer {@code SPopselect}），
     * 两列八档。选中档主色字 + 勾，悬停 {@code bg-on-surface/12}。探到「这一首没有这一档」时淡下去。
     */
    private void buildQualityMenu(QualityChip qualitySelector) {
        final Quality[] selectableQualities = Quality.values();
        final int qualityRows = (selectableQualities.length + QUALITY_COLUMNS - 1) / QUALITY_COLUMNS;
        // 每一列按自己最宽的那一行定宽，两列不必一样宽：「沉浸音质」和「母带」差着一倍。
        final double[] qualityColumnWidths = new double[QUALITY_COLUMNS];
        for (int index = 0; index < selectableQualities.length; index++) {
            int column = index % QUALITY_COLUMNS;
            qualityColumnWidths[column] = Math.max(qualityColumnWidths[column],
                    qualityRowWidth(formatQuality(selectableQualities[index])));
        }

        // 菜单声明在所有控件之后，因此它在底栏之上渲染并命中测试。它向上增长，因此从不覆盖紧凑控件。
        qualityMenuBackground = new RoundedRectWidget() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);
                SPlayerStyle.stroke(getX(), getY(), getWidth(), getHeight(), SPlayerStyle.RADIUS_LG, SPlayerStyle.px(1),
                        SPlayerStyle.a(SPlayerStyle.onSurface(), .08, this.getAlpha()));
            }
        };
        this.addChild(qualityMenuBackground);
        qualityMenuBackground
                .setClickable(false)
                .setHidden(true)
                // 那圈「音质」选项是从标签里长出来的一块小玻璃：玻璃主题下它背后就是这一帧采进来的画面，
                // 所以贴真背景 + 薄折射 + 边光；非玻璃主题下就是 SPlayer 的 surfaceBright 实心浮层。
                .setGlassPlate(GlassLook.Surface.PLAYER)
                .setBeforeRenderCallback(() -> {
                    double menuWidth = qualityMenuWidth(1.0, qualityColumnWidths);
                    double menuHeight = qualityRows * QUALITY_OPTION_HEIGHT + QUALITY_MENU_PADDING * 2.0;
                    // 底边固定贴在标签上方，只有上边沿随进度向上展开，看起来是从标签里长出来的。
                    double progress = smoothProgress(qualityMenuAnimation);
                    double bottom = qualitySelector.getRelativeY() - SPlayerStyle.px(8);
                    double visibleHeight = Math.max(.01, menuHeight * progress);
                    // 两列比标签宽得多，所以对齐的是<b>右</b>边缘：标签在右侧，菜单往左长才一直看得见。
                    qualityMenuBackground
                            .setBounds(menuWidth, visibleHeight)
                            .setPosition(Math.max(4.0, qualitySelector.getRelativeX()
                                    + qualitySelector.getWidth() - menuWidth), bottom - visibleHeight)
                            .setRadius(SPlayerStyle.RADIUS_LG)
                            .setColor(SPlayerStyle.bright())
                            // 底色压薄，让玻璃主题下贴上去的糊背景透出来（非玻璃主题 washAlpha 就是 1）。
                            .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f))
                            .setAlpha((float) (ControlsBar.this.getAlpha() * progress));
                });

        qualityOptions = new RoundedRectWidget[selectableQualities.length];
        for (int index = 0; index < selectableQualities.length; index++) {
            final Quality option = selectableQualities[index];
            // 行优先：左列是「标准 → 极高 → Hi-Res → 沉浸音质」，右列是「高品质 → 无损 → 高清环绕 → 母带」，
            // 读的顺序和 Quality 枚举的档位顺序一致。
            final int optionColumn = index % QUALITY_COLUMNS;
            final int optionRow = index / QUALITY_COLUMNS;
            RoundedRectWidget qualityOption = new RoundedRectWidget() {
                @Override
                public void onRender(double mouseX, double mouseY) {
                    boolean selected = option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality);
                    boolean unavailable = isQualityUnavailable(option);
                    float alpha = this.getAlpha();
                    // 底（含玻璃主题的 RIM 边光）交给 RoundedRectWidget；这里只画图标、文字与勾。
                    super.onRender(mouseX, mouseY);
                    int ink = selected && !unavailable ? SPlayerStyle.primary() : SPlayerStyle.onSurface();
                    float inkAlpha = unavailable ? .38f : 1f;
                    double centerY = getY() + getHeight() * .5;
                    FoliaLucideIcons.draw(qualityTierIcon(option), getX() + QUALITY_ROW_INSET + QUALITY_ICON_SIZE * .5,
                            centerY, QUALITY_ICON_SIZE, SPlayerStyle.a(ink, .7 * inkAlpha, alpha));
                    double textX = getX() + QUALITY_ROW_INSET + QUALITY_ICON_SIZE + QUALITY_ICON_GAP;
                    SPlayerStyle.text(FontManager.pf12bold, formatQuality(option), textX, centerY,
                            getX() + getWidth() - textX - QUALITY_CHECK_GAP - QUALITY_CHECK_SIZE - QUALITY_ROW_INSET,
                            SPlayerStyle.a(ink, inkAlpha, alpha));
                    if (selected) {
                        FoliaLucideIcons.draw(FoliaLucideIcons.CHECK,
                                getX() + getWidth() - QUALITY_ROW_INSET - QUALITY_CHECK_SIZE * .5, centerY,
                                QUALITY_CHECK_SIZE, SPlayerStyle.a(ink, inkAlpha, alpha));
                    }
                }
            };
            qualityOptions[index] = qualityOption;
            this.addChild(qualityOption);
            qualityOption
                    .setShouldOverrideMouseCursor(true)
                    .setHidden(true)
                    .setBeforeRenderCallback(() -> {
                        double optionHeight = QUALITY_OPTION_HEIGHT;
                        // 与背景共用同一条固定底边，所以展开过程里选项不会整体滑动，只是逐行露出来。
                        double bottom = qualitySelector.getRelativeY() - SPlayerStyle.px(8);
                        double slotY = bottom - QUALITY_MENU_PADDING - (qualityRows - optionRow) * optionHeight;
                        // 背景的上边沿扫过这一项时它才显形：任何时候都不会有选项浮在面板之外。
                        double reveal = smoothProgress((slotY + optionHeight - qualityMenuBackground.getRelativeY())
                                / Math.max(1.0, optionHeight));
                        double slotX = qualityMenuBackground.getRelativeX() + QUALITY_MENU_PADDING;
                        for (int previous = 0; previous < optionColumn; previous++) {
                            slotX += qualityColumnWidths[previous] + QUALITY_COLUMN_GAP;
                        }
                        boolean selected = option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality);
                        boolean unavailable = isQualityUnavailable(option);
                        qualityOption
                                .setBounds(qualityColumnWidths[optionColumn], optionHeight - 2.0)
                                .setPosition(slotX, slotY + (1.0 - reveal) * 3.0)
                                .setRadius(SPlayerStyle.RADIUS_MD)
                                // 选项排在菜单那块玻璃<em>里面</em>，不能再各自去贴一遍真背景：
                                // 那样每格透的是比周围更清楚的画面。所以走 RIM —— 只上那一圈边缘光。
                                .setGlassRim(GlassLook.Surface.PLAYER)
                                // SPlayer 下拉的高亮：悬停 on-surface/12、选中 primary/10，其余格透明。
                                // 选中与悬停那一格不压薄：玻璃主题下也要看得出「现在用的是哪一档」。
                                .setFillAlpha(qualityOption.isHovering() && !unavailable ? .12f
                                        : selected && !unavailable ? .10f : 0f)
                                .setColor(qualityOption.isHovering() && !unavailable
                                        ? SPlayerStyle.onSurface() : SPlayerStyle.primary())
                                .setAlpha((float) (ControlsBar.this.getAlpha() * reveal));
                        // 收起动画期间不再接受点击，避免"已经看不清"的选项还能被误中。
                        qualityOption.setClickable(qualityMenuOpen && reveal > .35);
                    })
                    .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                        if (mouseButton != 0) return true;
                        if (isQualityUnavailable(option)) {
                            // 不切、也不关菜单：用户多半是怀疑这一档被吞了，留在看得见的位置上
                            // 并说清楚原因，比换一个「看起来没反应」的界面有用得多。
                            DownloadDynamicIsland.showPlaybackQuality("这首歌没有「" + formatQuality(option) + "」",
                                    "酷狗没有给这一档，换一档或换一首");
                            return true;
                        }
                        CloudMusic.quality = option;
                        NCMPlayerConfig.setAudioQuality(option);
                        // 收起同样走动画，弹出层由 isQualityMenuVisible() 保留到播完。
                        qualityMenuOpen = false;
                        DownloadDynamicIsland.showPlaybackQuality("已切换 " + formatQuality(option), "下次播放生效");
                        return true;
                    });
        }
    }

    /** 整块菜单的宽度：两列各自加自己那一列的宽，再算上列间距与四周留白。 */
    private static double qualityMenuWidth(double scale, double[] columnWidths) {
        double width = QUALITY_MENU_PADDING * 2.0 + QUALITY_COLUMN_GAP * (QUALITY_COLUMNS - 1);
        for (double columnWidth : columnWidths) width += columnWidth;
        return width * scale;
    }

    private static String formatQuality(Quality quality) {
        // 档位短名与播放条那枚音质标签共用 Quality.displayNameOf(quality)：两处各写一套词，
        // 用户就会看到「我选的是极高、标签上却写着 EXHIGH」这种对不上的说法。
        return Quality.displayNameOf(quality);
    }

    /** 「音质 · xx」标签与它的量宽，只在 {@link CloudMusic#quality} 变化时重建（每帧热路径）。 */
    private Quality cachedQuality;
    private String cachedQualityLabel = "";
    private double cachedQualityLabelWidth;

    private String qualityLabelCache() {
        Quality current = CloudMusic.quality;
        if (cachedQuality != current) {
            cachedQuality = current;
            cachedQualityLabel = "音质 · " + formatQuality(current);
            cachedQualityLabelWidth = FontManager.pf12bold.getStringWidthD(cachedQualityLabel);
        }
        return cachedQualityLabel;
    }

    private double qualityLabelWidthCache(String label) {
        return cachedQualityLabel == label ? cachedQualityLabelWidth
                : FontManager.pf12bold.getStringWidthD(label);
    }
    private String formatDuration(float totalMillis) {
        int totalSeconds = (int) (totalMillis / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        String result = "";

        if (hours > 0) {
            result += (hours < 10 ? "0" : "") + hours + ":";
        }

        result += (minutes < 10 ? "0" : "") + minutes + ":";
        result += (seconds < 10 ? "0" : "") + seconds;

        return result;
    }
}
