package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.input.TouchGestures;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.AutomixCueMarker;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPresentation;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.ListenTogetherService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.rendering.AutomixCuePainter;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.PlayerQueueIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.GlyphInk;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.CoverHeroTransition;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.screens.ncm.PlayerIslandTransition;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TrackSelectionMotion;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.screens.ncm.VolumeControl;
import com.muoniumplayer.core.viper.ViperRuntime;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.List;


/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/17 21:24
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

    /**
     * 窄面板下传输簇（上一首/播放/下一首/播放模式）整体左移的量，0 表示宽度够用、版式不变。
     * 传输簇按面板中线排，一起听/待播/音质/音量贴右边缘，面板一窄两者必然重叠；原来的处理是把
     * 一起听与待播入口 alpha 归零，于是 GUI 缩放 3 以上根本进不去一起听。让传输簇让位是唯一
     * 不丢功能的办法。
     */
    private double centerShift;
    /** 让位后居中簇的右边界。取目标值而不是动画中的值，免得拖窗口时入口一闪一闪。 */
    private double centerClusterRight;
    /** 图标带整条上抬的量：0 与音质/音量同高，4 抬到传输键那一行，让开下面一行的剩余时间标签。 */
    private double iconBandLift;
    /**
     * 一起听入口在「音量→音质→待播」这条右锚点链上的首选左边界。它只由面板宽度决定、与让位量
     * 无关，因此可以安全地反馈给让位计算，不会形成逐帧抖动的回路。
     */
    private double listenTogetherPrimaryX;

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
        if (!qualityMenuOpen || qualityMenuBackground == null) return false;
        boolean withinMenu = mouseX >= qualityMenuBackground.getX() && mouseX <= qualityMenuBackground.getX() + qualityMenuBackground.getWidth()
                && mouseY >= qualityMenuBackground.getY() && mouseY <= qualityMenuBackground.getY() + qualityMenuBackground.getHeight();
        if (!withinMenu) return false;
        this.onMouseClickReceived(mouseX, mouseY, mouseButton);
        return true;
    }

    public ControlsBar() {
    }

    @Override
    public void onInit() {
        RectWidget bg = new RectWidget() {
            @Override public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);
                // After the bar background, before any labels/buttons; inherits player clip and motion.
                com.muoniumplayer.core.rendering.MusicSpectrumRenderer.renderPlayer(
                        getX(), getY(), getWidth(), getHeight(), getAlpha());
            }
        };

        this.addChild(bg);

        RoundedImageWidget playingCover = new RoundedImageWidget(() -> {
            Music displayed = presentation.displayTrack;
            return displayed == null ? null : CloudMusic.preferredCoverLocation(displayed,
                    displayed.getSmallCoverLocation());
        }, 0, 0, 0, 0) {
            @Override public void onRender(double mouseX, double mouseY) {
                int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
                // 新封面未到也显示目标曲目的独立占位，不能拿旧封面冒充新歌。
                roundedRect(getX(), getY(), getWidth(), getHeight(), getRadius(), reAlpha(accent, getAlpha() * .17f));
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
                .setBeforeRenderCallback(() -> playingCover
                        .setClickable(presentation.displayTrack != null && !presentation.preparing)
                        .setShouldOverrideMouseCursor(!presentation.preparing)
                        .setAlpha(selectionMotion.contentAlpha())
                        .setMargin(5)
                        .setBounds(playingCover.getHeight(), playingCover.getHeight())
                        .setRadius(2))
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (!presentation.preparing && CloudMusic.currentlyPlaying != null) {
                        // 起点必须在造面板之前交出去：面板构造函数会兜底启动转场时钟，那之后再报
                        // 起点就晚了一帧，封面会从上一次的位置起飞。
                        CoverHeroTransition.open(playingCover.getX(), playingCover.getY(),
                                playingCover.getWidth(), playingCover.getRadius());
                        NCMScreen.getInstance().musicLyricsPanel =
                                MusicLyricsPanel.takePrewarmed(CloudMusic.currentlyPlaying);
                    }

                    return true;
                });
        double buttonsYOffset = -4;

        IconWidget playPause = new IconWidget("B", FontManager.icon30, 0, 0, 20, 20);

        this.addChild(playPause);

        playPause
                .setBeforeRenderCallback(() -> {
                    // 镜像态下出声的是外部播放器，player 恒为 null：图标一律问「现在有没有在响」，
                    // 它同时覆盖内部暂停与外部暂停两种情况。
                    boolean showPausingIcon = !CloudMusic.isAudibleNow();

                    playPause
                            .center()
                            .setIcon(showPausingIcon ? "B" : "A")
                            .setPosition(playPause.getRelativeX() - centerShift, playPause.getRelativeY() + buttonsYOffset)
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                })
                .setOnClickCallback((x, y, i) -> {
                    // 走 CloudMusic.togglePlayPause() 而不是直接动 player：一起听房间里传输控制权
                    // 归服务端，那条路径上有房间拦截（房主/管理员变成房间指令，普通成员被挡住）。
                    CloudMusic.togglePlayPause();
                    return true;
                });

        IconWidget prev = new IconWidget("H", FontManager.icon30, 0, 0, 20, 20);

        this.addChild(prev);

        prev
                .setOnClickCallback((x, y, i) -> {
                    if (CloudMusic.hasPlaybackClock() && CloudMusic.currentlyPlaying != null)
                        CloudMusic.prev();

                    return true;
                })
                .setBeforeRenderCallback(() -> prev
                        .center()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setPosition(prev.getRelativeX() - 20 - prev.getWidth() * .5 - centerShift, prev.getRelativeY() + buttonsYOffset));
        IconWidget next = new IconWidget("E", FontManager.icon30, 0, 0, 20, 20);
        this.addChild(next);

        next
                .setOnClickCallback((x, y, i) -> {
                    if (CloudMusic.hasPlaybackClock() && CloudMusic.currentlyPlaying != null)
                        CloudMusic.next();

                    return true;
                })
                .setBeforeRenderCallback(() -> next
                        .center()
                        .setPosition(next.getRelativeX() + next.getWidth() * .5 + 20 - centerShift, next.getRelativeY() + buttonsYOffset)
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        // 播放模式与核心控件并列：顺序播放 → 随机播放 → 单曲循环。
        ThemedTextureIconWidget playMode = new ThemedTextureIconWidget(
                () -> CloudMusic.isPersonalFmActive() ? null : PlayerIconAssets.forPlayMode(CloudMusic.playMode),
                () -> CloudMusic.isPersonalFmActive() ? "F" : CloudMusic.playMode.getIcon(),
                FontManager.icon30, 0, 0, 20, 20);
        this.addChild(playMode);

        // 这一排四颗里只有播放模式走贴图，而它的画布留白和图标字体对不上：整张 96×96 拉满
        // 20×20 的框，墨迹是 10.8×8.75，相邻三颗 icomoon 字形却只有 6.2×6.9 —— 用户看到的
        // 「大小不一样、不在一个水平面上」就是这个 74% 的宽度差。按邻居的<b>实测</b>墨迹高把
        // 贴图往里缩，交互框与悬停底仍然吃满 20×20，所以点击范围一点没变。
        GlyphInk transportInk = GlyphInk.measure(FontManager.icon30, 'H');
        double transportHeight = transportInk == null ? 7.0 : transportInk.inkHeight() * .5;
        playMode.setContentInset((playMode.getHeight()
                - transportHeight / PlayerIconAssets.PLAY_MODE_INK_HEIGHT_RATIO) * .5);

        playMode
                .setBeforeRenderCallback(() -> {
                    CloudMusic.PlayMode mode = CloudMusic.playMode;
                    boolean personalFm = CloudMusic.isPersonalFmActive();
                    playMode
                            .center()
                            .setPosition(next.getRelativeX() + next.getWidth() + 10, next.getRelativeY())
                            .setClickable(!personalFm)
                            .setAlpha(ControlsBar.this.getAlpha() * (personalFm ? .35f : 1.0f))
                            // 顺序播放是这一排的「没开任何东西」，所以它和邻居同色；随机与单曲循环
                            // 是用户特意拧出来的状态，用强调色标出来 —— 原来四档一律正文色，
                            // 换了模式界面没有任何回应。
                            .setColor(mode == CloudMusic.PlayMode.Sequential
                                    ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                })
                .setOnClickCallback((x, y, mouseButton) -> {
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

        // 私人 FM 没有「换一首」按钮：底栏是「居中传输簇 + 居中进度条」的两行式版式，
        // 追加一颗 44 宽的按钮就得让整簇外扩，与右下角待播/一起听一挤，让位量会把按钮组
        // 推离进度条（2026-10-13 的错位截图）。跳歌动作由 FM 页面自己的「换一首」承担。



        RoundedRectWidget progressBarBg = new RoundedRectWidget() {

            boolean prevMouse = false;

            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);

                if (presentation.preparing) {
                    prevMouse = Mouse.isButtonDown(0);
                    // 不把旧歌的 01:14 显示成新歌进度，也不让点击这根等待轨去 seek 旧歌。
                    double travel = .5 - .5 * Math.cos(selectionMotion.travel() * Math.PI * 2);
                    double span = getWidth() * .24;
                    roundedRect(getX() + (getWidth() - span) * travel, getY(), span, getHeight(), 1,
                            reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), getAlpha() * .8f));
                    return;
                }

                // 触屏时代的点击时机：按下可能是滚动的起手，seek 改在「松手沿 + 这一按
                // 从头到尾没拖过」（TouchGestures#lastPressWasDrag）时才结算。
                // prevMouse 仍是按压锁存：按下沿置位，抬起沿结算点按后复位。
                boolean lmbDownNow = Mouse.isButtonDown(0);
                boolean tapEdge = prevMouse && !lmbDownNow
                        && !TouchGestures.get().lastPressWasDrag();
                if (lmbDownNow) {
                    prevMouse = true;
                } else {
                    prevMouse = false;
                }

                // 模态浮层（歌词选词、建单、导入等）压在界面上时这一帧的鼠标状态不属于播放条：
                // 点遮罩的那一下会同时落在这里，把底下的进度条当成拖到起点 seek 到 0——
                // 用户看到的就是「开了个弹窗，歌回到开头」。命中判定与 Mouse 轮询一起停。
                boolean modalOpen = NCMScreen.getInstance().isModalOverlayActive();
                if (modalOpen) {
                    prevMouse = false;
                } else if (tapEdge && this.testHovered(mouseX, mouseY, 1)) {
                    double xDelta = Math.max(0, Math.min(this.getWidth(), (mouseX - this.getX())));
                    double percent = xDelta / this.getWidth();

                    // 总时长与「能不能拖」都不能问 CloudMusic.player：镜像态下出声的是外部播放器，
                    // 内部引擎是空的，照原样判就是进度条拖不动、进度也不显示。
                    if (CloudMusic.hasPlaybackClock()) {
                        float total = (float) CloudMusic.durationMsNow();
                        if (total > 0.0f) {
                            // 统一走 CloudMusic.seekToMillis()：里面既有一起听房间的拦截，
                            // 也负责 seek 后按真实音频时钟对齐歌词与进度。
                            CloudMusic.seekToMillis((float) (percent * total));
                        }
                    }
                }

                // automix 的接缝画在这条进度条上。位置刻意排在拖拽判定<b>之后</b>：新代码与那条
                // 命中逻辑物理隔开，命中的条件与顺序一个字都不动（记号本身不可点）。
                drawAutomixCue(this.getX(), this.getY(), this.getWidth(), this.getHeight(),
                        CloudMusic.hasPlaybackClock() && CloudMusic.durationMsNow() > 0.0
                                ? CloudMusic.positionMsNow() / CloudMusic.durationMsNow() : -1.0);
            }
        };

        this.addChild(progressBarBg);

        progressBarBg
                .setRadius(1)
                .setBounds(135, 3)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    // 底色必须在每帧回调里取：主题色（含「封面跟随」与切主题的渐变）一直在动，
                    // 放在下面那条建控件的链上，就是把整根进度条钉死在开屏那一刻的颜色上 ——
                    // 同面板的音量条是每帧重取的，两者并排放在一起时这个差别一眼可见。
                    progressBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
                    progressBarBg
                            .center()
                            .setPosition(progressBarBg.getRelativeX(), progressBarBg.getRelativeY() + 8);
                });

        RoundedRectWidget progressBar = new RoundedRectWidget();

        progressBarBg.addChild(progressBar);
        progressBar
                .setWidth(0)
                .setClickable(false)
                .setBeforeRenderCallback(() -> {
                    progressBar.setMargin(0);
                    // 填充同样每帧重取强调色，且刻意排在下面那几个 return <b>之前</b>：
                    // 系统播放器镜像态与「总时长还没落地」时进度条仍要画出来，那时候它也得跟着主题走。
                    progressBar.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

                    // 镜像态下没有内部引擎，进度与总长都取自快照（见 CloudMusic 那两个 Now 方法）。
                    if (presentation.preparing || !CloudMusic.hasPlaybackClock()) {
                        progressBar.setWidth(0).setRadius(0);
                        return;
                    }

                    float total = (float) CloudMusic.durationMsNow();
                    if (total <= 0.0f) {
                        progressBar.setWidth(0).setRadius(0);
                        return;
                    }

                    float perc = Math.max(0.0f,
                            Math.min(1.0f, (float) CloudMusic.positionMsNow() / total));
                    progressBar
                            .setWidth(perc * progressBarBg.getWidth())
                            .setRadius(perc);
                });

        LabelWidget lblCurTime = new LabelWidget(
                () -> {
                    if (presentation.preparing) return "准备中";
                    if (!CloudMusic.hasPlaybackClock())
                        return "00:00";
                    return formatDuration((float) CloudMusic.positionMsNow());
                },
                FontManager.pf12
        );
        this.addChild(lblCurTime);

        lblCurTime
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblCurTime
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(
                                progressBarBg.getRelativeX() - lblCurTime.getWidth() - 4,
                                progressBarBg.getRelativeY() + progressBarBg.getHeight() * .5 - lblCurTime.getHeight() * .5
                        ));

        LabelWidget lblRemainingTime = new LabelWidget(
                () -> {
                    if (presentation.preparing) return "--:--";
                    if (!CloudMusic.hasPlaybackClock())
                        return "00:00";
                    float total = (float) CloudMusic.durationMsNow();
                    if (total <= 0.0f)
                        return "00:00";
                    return "-" + formatDuration(Math.max(0.0f,
                            total - (float) CloudMusic.positionMsNow()));
                },
                FontManager.pf12
        );
        this.addChild(lblRemainingTime);

        lblRemainingTime
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblRemainingTime
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(progressBarBg.getRelativeX() + progressBarBg.getWidth() + 4, lblCurTime.getRelativeY()));

        LabelWidget lblMusicName = new LabelWidget(() -> presentation.displayTrack == null ? "未在播放" : presentation.displayTrack.getName(), FontManager.pf14bold);
        this.addChild(lblMusicName);

        lblMusicName
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblMusicName
                        .setAlpha(selectionMotion.contentAlpha())
                        .centerVertically()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicName.getRelativeY() - lblMusicName.getHeight() * .5 - 2 + selectionMotion.offset()
                        )
                        .setMaxWidth(Math.max(0.0,
                                lblCurTime.getRelativeX() - lblMusicName.getRelativeX() - 4)));

        LabelWidget lblMusicArtist = new LabelWidget(
                () -> {
                    if (presentation.displayTrack == null)
                        return "无";
                    // 专辑可以是 null（单曲、电台、以及一起听下发的曲目都可能没有专辑），
                    // 那时只显示歌手，不要为了拼一个「 - 」把整条播放条崩掉。
                    Music playing = presentation.displayTrack;
                    if (presentation.preparing) return "准备播放 · " + playing.getArtistsName() + " · 原曲继续播放";
                    String album = playing.getAlbum() == null ? null : playing.getAlbum().getName();
                    return album == null || album.isEmpty()
                            ? playing.getArtistsName()
                            : playing.getArtistsName() + " - " + album;
                },
                FontManager.pf14bold
        );
        this.addChild(lblMusicArtist);

        lblMusicArtist
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblMusicArtist
                        .setAlpha(selectionMotion.contentAlpha())
                        .centerVertically()
                        .setColor(NCMScreen.getColor(presentation.preparing ? NCMScreen.ColorType.ACCENT : NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicArtist.getRelativeY() + lblMusicArtist.getHeight() * .5 + 2 + selectionMotion.offset()
                        )
                        .setMaxWidth(Math.max(0.0,
                                lblCurTime.getRelativeX() - lblMusicArtist.getRelativeX() - 4)));

        // 右对齐的紧凑音量条。它把所有视觉和输入行为委托给
        //全屏控件实现，因此两个界面保持视觉同步。
        final VolumeControl compactVolumeControl = new VolumeControl();
        RoundedRectWidget compactVolumeWidget = new RoundedRectWidget() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                compactVolumeControl.render(mouseX, mouseY, this.getX(),
                        this.getY() + this.getHeight() * .5, this.getWidth(), this.getAlpha());
            }
        };
        this.addChild(compactVolumeWidget);
        compactVolumeWidget
                .setClickable(false)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    double controlWidth = Math.max(74.0,
                            Math.min(122.0, compactVolumeWidget.getParentWidth() * .18));
                    compactVolumeWidget
                            .setBounds(controlWidth, 20.0)
                            .setPosition(compactVolumeWidget.getParentWidth() - controlWidth - 8.0,
                                    Math.max(1.0, (compactVolumeWidget.getParentHeight() - 20.0) * .5
                                            - iconBandLift))
                            .setAlpha(ControlsBar.this.getAlpha());
                });

        // 紧凑音质选择器：可用的档位在向上生长的弹出菜单里摆出来，用户看得见全部选项，
        // 而不是对着一颗按钮反复循环。所有控件都保持为 ControlsBar 的直接子级，弹出层才不会被
        // 选择器裁剪，也才能压在音量控件之上。
        final Quality[] selectableQualities = Quality.values();
        final double qualityButtonHeight = 20.0;
        final int qualityRows = (selectableQualities.length + QUALITY_COLUMNS - 1) / QUALITY_COLUMNS;
        // 每一列按自己最宽的那一行定宽，两列不必一样宽：「沉浸音质」和「母带」差着一倍。
        final double[] qualityColumnWidths = new double[QUALITY_COLUMNS];
        for (int index = 0; index < selectableQualities.length; index++) {
            int column = index % QUALITY_COLUMNS;
            qualityColumnWidths[column] = Math.max(qualityColumnWidths[column],
                    qualityRowWidth(formatQuality(selectableQualities[index])));
        }

        qualityOptions = new RoundedRectWidget[selectableQualities.length];
        RoundedRectWidget qualitySelector = new RoundedRectWidget();
        this.addChild(qualitySelector);
        qualitySelector
                .setClickable(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    // 专用页脚槽：紧挨音量控件左侧，带一个
                    //与播放器布局匹配的固定视觉间隙。不要让瞬时
                    //布局值隐藏这个主要动作。
                    // 常驻回调：在这里推进展开动画，收起过程才有帧可用。
                    // 实时毫秒而不是帧耦合的指数逼近：后者在默认 3x 倍率下两三帧就吃掉九成，
                    // 读起来是「先窜一下再慢慢糊住」，而不是「落到位」。中途反悔从当前位置折回。
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

                    double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                    double buttonHeight = qualityButtonHeight * scale;
                    double parentWidth = qualitySelector.getParentWidth();
                    double volumeWidth = Math.max(74.0, Math.min(122.0, parentWidth * .18));
                    double volumeX = compactVolumeWidget.getRelativeX();
                    if (volumeX <= 0.0 || volumeX >= parentWidth) {
                        volumeX = parentWidth - volumeWidth - 8.0;
                    }
                    // 音效入口（18 图标）钉死在芯片与音量之间：音量 ←10← 音效 ←10← 芯片。
                    // 原来这里是一段随面板宽度伸缩的空隙（clamp(宽×4.5%,14,38)+26，宽面板上
                    // 60px+ 的死空间），右侧看着永远空一块；改固定间距链之后整条右簇收紧，
                    // 无论面板多宽四颗入口都排在同一段里。
                    double selectorGap = 10.0 + 18.0 + 10.0;
                    // 箭头交给矢量画，文字里就不该再留那枚全角「﹀」：它既占宽又和文字基线对不齐，
                    // 而 LucideIconWidget 那一枚是随缩放算出来的，和菜单里的勾选/档位图标同一套笔画。
                    // 量宽与渲染必须同一份字体：标签固定 pf12bold，量宽也用它 —— 原来 scale<.9
                    // 时按 pf10bold 量、按 pf12bold 渲染，量出来的宽偏小，文字会被提前截断。
                    // 标签只在切档那一刻才变：按 quality 缓存拼接结果与量宽，不每帧重做。
                    String selectorLabel = qualityLabelCache();
                    double chevronWidth = 10.0 * scale;
                    double desiredWidth = qualityLabelWidthCache(selectorLabel)
                            + 7.0 * scale + chevronWidth + 6.0 * scale;
                    double selectorWidth = Math.max(56.0 * scale, Math.min(112.0 * scale, desiredWidth));
                    double selectorX = volumeX - selectorGap - selectorWidth;

                    // 在极窄的播放器上，仅收缩到足以留在页脚内；
                    //标签本身安全裁剪，而非整个质量动作消失。
                    if (selectorX < 6.0) {
                        selectorWidth = Math.max(34.0 * scale, volumeX - selectorGap - 6.0);
                        selectorX = Math.max(6.0, volumeX - selectorGap - selectorWidth);
                    }

                    qualitySelector
                            .setHidden(false)
                            .setClickable(true)
                            .setBounds(selectorWidth, buttonHeight)
                            .setRadius(5.0 * scale)
                            .setColor(qualitySelector.isHovering()
                                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            // 这颗按钮和它弹出的那圈选项是同一块玻璃：贴在面板上，背后就是这一帧
                            // 采进来的画面，所以走 PLATE（贴真背景 + 薄折射 + 边光），底色按浓度压薄。
                            // 不压的话它还是一页脚那颗实心深色胶囊，跟刚上玻璃的播放键不是一套。
                            .setGlassPlate(GlassLook.Surface.PLAYER)
                            .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f))
                            .setAlpha(ControlsBar.this.getAlpha() * .94f)
                            .setPosition(selectorX,
                                    Math.max(1.0, (qualitySelector.getParentHeight() - buttonHeight) * .5
                                            - iconBandLift));
                })
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (mouseButton == 0) {
                        // 打开的那一次顺手去问一次「这一首有哪些档」。这个请求只能挂在用户动作上
                        // 异步发：菜单是每帧渲染的，放在渲染回调里就是每帧一次网络往返。
                        if (!qualityMenuOpen) refreshKuGouQualityOptions();
                        // 只翻转状态：是否渲染交给动画进度判断，收起时弹出层要留到动画播完。
                        qualityMenuOpen = !qualityMenuOpen;
                    }
                    return true;
                });

        // 供应函数读同一份缓存：LabelWidget 每帧调一次，里面不再重新拼接字符串。
        LabelWidget qualityText = new LabelWidget(this::qualityLabelCache, FontManager.pf12bold);
        qualitySelector.addChild(qualityText);
        qualityText.setClickable(false);
        qualityText.setBeforeRenderCallback(() -> qualityText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setMaxWidth(Math.max(0.0, qualitySelector.getWidth() - 13.0 * Math.max(.82, NCMPlayerConfig.getPlayerScale()) - 10.0))
                .setPosition(7.0, Math.max(2.0, (qualitySelector.getHeight() - qualityText.getHeight()) * .5)));

        LucideIconWidget qualityChevron = new LucideIconWidget(
                () -> qualityMenuOpen ? FoliaLucideIcons.CHEVRON_UP : FoliaLucideIcons.CHEVRON_DOWN,
                () -> qualityMenuOpen ? "︿" : "﹀", FontManager.pf12bold, 0, 0, 10, 10);
        qualitySelector.addChild(qualityChevron);
        qualityChevron.setClickable(false);
        qualityChevron.setBeforeRenderCallback(() -> {
            double chevronScale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
            double chevronSize = 10.0 * chevronScale;
            qualityChevron
                    .setBounds(chevronSize, chevronSize)
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setPosition(qualitySelector.getWidth() - chevronSize - 6.0 * chevronScale,
                            Math.max(0.0, (qualitySelector.getHeight() - chevronSize) * .5));
        });

        // 蝰蛇音效入口：用户指定放在音质选择的右边、音量滑条左边（2026-10-07）。
        // 位置完全由音质芯片的几何推出：芯片右缘 +10 就是它的左缘（selectorGap 的两个 10
        // 就是给这一步留的），任何面板宽度下都落在芯片与音量之间、不与两者重叠。
        // 垂直位置跟随整条右侧簇的共用中线（面板半高 − iconBandLift）——它以前走自己的
        // centerVertically()，窄面板下待播/一起听抬升 4px 时就它不抬，四颗入口高低不齐。
        // 启用时点亮强调色，其余时候跟次级文字同色。
        final LucideIconWidget viperToggle = new LucideIconWidget(() -> FoliaLucideIcons.TUNE,
                () -> PartyIcons.HEADPHONES, FontManager.partyIcon32, 0, 0, 18, 18);
        this.addChild(viperToggle);
        viperToggle.setShouldOverrideMouseCursor(true);
        viperToggle.setDrawScale(.78);
        viperToggle.setBeforeRenderCallback(() -> {
            viperToggle
                    .setClickable(true)
                    .setPosition(qualitySelector.getRelativeX() + qualitySelector.getWidth() + 10.0,
                            Math.max(1.0, (viperToggle.getParentHeight() - 18.0) * .5 - iconBandLift));
            viperToggle
                    .setColor(ViperRuntime.isEnabled()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        viperToggle.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().openViperEffects();
            return true;
        });
        // 与点亮状态同步：图标亮着说明 ViPER 在跑，说明里的「开启中」让这层含义不用猜。
        viperToggle.setTooltip(() -> ViperRuntime.isEnabled()
                ? "音效调音台（蝰蛇 · 开启中）"
                : "音效调音台（蝰蛇）");

        // 下一首播放列表：队列抽屉的开关排在音质按钮左边，沿用右下角次级控件的同一条锚点链。
        // 用「播放三角 + 列表」（player-action-icons U+E144）而不是歌曲行那枚「下一首播放」
        // （U+E309）：这里打开的是整张待播列表，和单曲的"插到下一首"不是同一个动作。
        IconWidget queueToggle = PlayerQueueIcons.newPlayQueueButton(18);
        this.addChild(queueToggle);
        queueToggle.setShouldOverrideMouseCursor(true);
        queueToggle.setBeforeRenderCallback(() -> {
            PlayQueuePanel queuePanel = NCMScreen.getInstance().getPlayQueuePanel();
            boolean opened = queuePanel != null && queuePanel.isOpen();
            double toggleX = qualitySelector.getRelativeX() - 8.0 - queueToggle.getWidth();
            // 面板很窄时右下角排不下了：用 alpha 让它隐形并停止响应，而不是 setHidden——
            // 被隐藏的组件不会再执行自己的回调，也就再没有机会把自己显示回来。
            // 右界由播放条统一算（centerClusterRight）：让位之后才知道居中簇到哪里结束。
            // 这里额外减掉一颗图标的宽度，是给一起听入口留的：两颗排不下时先让待播退场，
            // 一起听顶上这个槽位，绝不能出现两个入口一起消失。
            boolean fits = toggleX - 8.0 - queueToggle.getWidth() > ControlsBar.this.centerClusterRight + 6.0;
            queueToggle
                    .setClickable(fits)
                    .setAlpha(fits ? ControlsBar.this.getAlpha() : 0f);
            queueToggle
                    .setPosition(toggleX, Math.max(1.0,
                            (queueToggle.getParentHeight() - queueToggle.getHeight()) * .5
                                    - ControlsBar.this.iconBandLift))
                    // 一起听房间里这颗入口换成房间歌单的读数：抽屉里显示的就是那份队列，
                    // 按钮点亮条件必须跟它是同一份数字，否则会出现「亮着、打开却是空的」。
                    .setColor(opened || PlayQueueRoomBridge.displayQueuedCount() > 0
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 把按钮的右边缘交给抽屉，抽屉据此右对齐 —— 点开之后视线不用再横穿半屏去找它。
            // 两者共用同一个 x 原点（都按 currentPanelBg.getX() 摆），所以绝对坐标直接可比。
            if (queuePanel != null) {
                queuePanel.setAnchorRight(queueToggle.getX() + queueToggle.getWidth());
            }
        });
        queueToggle.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            PlayQueuePanel queuePanel = NCMScreen.getInstance().getPlayQueuePanel();
            if (queuePanel != null) queuePanel.toggle();
            return true;
        });
        // 数量与右上角徽标、抽屉里的列表读同一份（PlayQueueRoomBridge），说明里带上省得点开数。
        queueToggle.setTooltip(() -> {
            int queued = PlayQueueRoomBridge.displayQueuedCount();
            return queued > 0 ? "下一首播放列表 · 待播 " + queued + " 首" : "下一首播放列表";
        });

        // 待播数量贴在图标右上角。作为图标的子组件，它自动继承那一份 alpha，
        // 因此窄面板下不需要第二处可见性判断。房间模式下数的是一起听歌单，与抽屉一致。
        LabelWidget queueCount = new LabelWidget(() -> {
            int queued = PlayQueueRoomBridge.displayQueuedCount();
            return queued > 0 ? String.valueOf(Math.min(99, queued)) : "";
        }, FontManager.pf10bold);
        queueToggle.addChild(queueCount);
        queueCount.setClickable(false);
        queueCount.setBeforeRenderCallback(() -> queueCount
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setPosition(queueToggle.getWidth() - 3.0, -1.0));

        // 一起听入口排在待播列表按钮左边，沿用同一条锚点链。两种一起听共用这一颗图标：
        // 网易云官方一起听（依赖网易云登录态）与服务器房间（依赖服务端 mod），面板里再分流。
        // 两者都用不上时 alpha 隐形并停止响应，而不是 setHidden —— 被隐藏的组件不再执行自己的
        // 回调，也就没机会把自己显示回来。
        IconWidget listenTogether = new IconWidget(FontelloIcons.ACCOUNT_ADD, FontManager.fontello16, 0, 0, 18, 18);
        this.addChild(listenTogether);
        listenTogether.setShouldOverrideMouseCursor(true);
        listenTogether.setBeforeRenderCallback(() -> {
            MusicPlatform currentPlatform = NeteaseMusicService.getCurrentPlatform();
            boolean netease = currentPlatform == MusicPlatform.NETEASE;
            // 酷狗也有官方房间实现，入口条件与网易云同形。注意这里<b>只看音源、不看登录态</b>：
            // 没登录时这颗按钮照样要能点，人才看得到面板里那句「去登录」——原来网易云那侧
            // 就是这个行为，别「顺手」改成 isAvailable()。
            boolean kugou = currentPlatform == MusicPlatform.KUGOU;
            // 首选槽位在待播入口左边。这条右锚点链只跟面板宽度有关，可以回喂给让位计算。
            double primaryX = queueToggle.getRelativeX() - 8.0 - listenTogether.getWidth();
            ControlsBar.this.listenTogetherPrimaryX = primaryX;
            boolean pairFits = primaryX > ControlsBar.this.centerClusterRight + 6.0;
            // 待播入口已经因为排不下退场了，这里接过它的槽位：一起听是主入口，不能跟着消失。
            double toggleX = pairFits ? primaryX : queueToggle.getRelativeX();
            boolean fits = pairFits || toggleX > ControlsBar.this.centerClusterRight + 6.0;
            // 服务器装了一起听服务端时，即使当前音源没有官方房间也要让入口可点：
            // 房间面板本身要能进去看状态。
            boolean usable = (netease || kugou || MpPartyClient.serverPresent()) && fits;
            listenTogether
                    .setClickable(usable)
                    .setAlpha(usable ? ControlsBar.this.getAlpha() : 0f);
            listenTogether
                    .setPosition(toggleX, Math.max(1.0,
                            (listenTogether.getParentHeight() - listenTogether.getHeight()) * .5
                                    - ControlsBar.this.iconBandLift))
                    // 门面同时看得见两个源的房间：任一家的房间开着，图标就点亮。
                    .setColor(ListenTogetherService.isActive() || MpPartyClient.inRoom()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        listenTogether.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().openListenTogether();
            return true;
        });
        // 房间开着时与点亮状态同步加个「进行中」，静态时只报功能名。
        listenTogether.setTooltip(() -> ListenTogetherService.isActive() || MpPartyClient.inRoom()
                ? "一起听（进行中）"
                : "一起听");

        // 播放条自己的每帧回调：算出窄面板下的让位量。放在 onInit 末尾是因为它要用到上面
        // 才声明的剩余时间标签与一起听入口 —— lambda 只能捕获已声明的局部变量。
        this.setBeforeRenderCallback(() -> {
            double half = this.getWidth() * .5;
            // 一律按「未让位」的几何算：改读 playMode 让位后的实际坐标，会把让位量自己反馈进
            // 输入，每帧收敛一点，肉眼看到的就是控件一直在抖。
            double transportRight = half + 70.0;
            double progressRight = half + 71.5 + lblRemainingTime.getWidth();
            double bandLeft = listenTogetherPrimaryX;
            boolean firstFrame = bandLeft <= 0.0;

            // 右侧整条簇（音量/音质/音效/待播/一起听）默认都落在面板垂直中线，那条线上还排着
            // 剩余时间标签。让不出来（簇的左缘顶进标签）就整条簇一起抬 4 个单位：五颗入口
            // 永远在同一条水平线上，抬升是整簇的行为，不再是某两颗图标自己的事。
            boolean besideLabel = firstFrame || bandLeft > progressRight + 6.0;
            iconBandLift = Interpolations.interpolate(iconBandLift, besideLabel ? 0.0 : 4.0, .35);

            // 只让传输簇左移，进度条与时间标签仍然居中：一起拖走会挤掉封面右边的歌名，窄面板上
            // 「传输键略微偏左」远好过「歌名没了」。
            double shiftLimit = Math.max(0.0, half - 70.0 - 31.0);
            double shiftTarget = firstFrame ? 0.0
                    : Math.max(0.0, Math.min(shiftLimit, transportRight + 6.5 - bandLeft));
            centerShift = Interpolations.interpolate(centerShift, shiftTarget, .3);
            double shiftedTransportRight = transportRight - shiftTarget;
            centerClusterRight = besideLabel
                    ? Math.max(shiftedTransportRight, progressRight)
                    : shiftedTransportRight;
        });

        // 菜单声明在选择器之后，因此它在底栏之上渲染并命中测试。
        //它向上增长，因此从不覆盖紧凑控件。
        qualityMenuBackground = new RoundedRectWidget();
        this.addChild(qualityMenuBackground);
        qualityMenuBackground
                .setClickable(false)
                .setHidden(true)
                // 那圈「音质」选项是从按钮里长出来的一块小玻璃：它背后就是这一帧采进来的画面，
                // 所以贴真背景 + 薄折射 + 边光，而不是一块浮在界面之上的实心深色板。
                .setGlassPlate(GlassLook.Surface.PLAYER)
                .setBeforeRenderCallback(() -> {
                    double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                    double menuWidth = qualityMenuWidth(scale, qualityColumnWidths);
                    double menuHeight = qualityRows * QUALITY_OPTION_HEIGHT * scale
                            + QUALITY_MENU_PADDING * 2.0 * scale;
                    // 底边固定贴在按钮上方，只有上边沿随进度向上展开，看起来是从按钮里长出来的。
                    double progress = smoothProgress(qualityMenuAnimation);
                    double bottom = qualitySelector.getRelativeY() - 4.0;
                    double visibleHeight = Math.max(.01, menuHeight * progress);
                    // 两列比那颗按钮宽得多，所以对齐的是<b>右</b>边缘：按钮贴在页脚右侧，
                    // 菜单往左长才一直看得见；只有窄到左边放不下时才整体顶回去。
                    qualityMenuBackground
                            .setBounds(menuWidth, visibleHeight)
                            .setPosition(Math.max(4.0, qualitySelector.getRelativeX()
                                    + qualitySelector.getWidth() - menuWidth), bottom - visibleHeight)
                            .setRadius(6.0 * scale)
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            // 底色压薄，让刚贴上去的糊背景透出来（不压就仍然是一块实心深色菜单）。
                            .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f))
                            .setAlpha((float) (ControlsBar.this.getAlpha() * .98f * progress));
                });

        for (int index = 0; index < selectableQualities.length; index++) {
            final Quality option = selectableQualities[index];
            // 行优先：左列是「标准 → 极高 → Hi-Res → 沉浸音质」，右列是「高品质 → 无损 → 高清环绕 → 母带」，
            // 读的顺序和 Quality 枚举的档位顺序一致，不用在脑子里重排一遍。
            final int optionColumn = index % QUALITY_COLUMNS;
            final int optionRow = index / QUALITY_COLUMNS;
            qualityOptions[index] = new RoundedRectWidget();
            RoundedRectWidget qualityOption = qualityOptions[index];
            this.addChild(qualityOption);

            // 档位图标。墨迹按 24 格网原样落到 12 逻辑像素的盒子里（drawScale 1.0），
            // 和出图时用户看过的那一张是同一档尺寸。
            LucideIconWidget qualityOptionIcon = new LucideIconWidget(() -> qualityTierIcon(option),
                    () -> "", FontManager.pf12bold, 0, 0, QUALITY_ICON_SIZE, QUALITY_ICON_SIZE);
            qualityOption.addChild(qualityOptionIcon);
            qualityOptionIcon.setClickable(false).setDrawScale(1.0);
            qualityOptionIcon.setBeforeRenderCallback(() -> {
                double iconScale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                double iconSize = QUALITY_ICON_SIZE * iconScale;
                qualityOptionIcon
                        .setBounds(iconSize, iconSize)
                        .setColor(isQualityUnavailable(option)
                                ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)
                                : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setPosition(QUALITY_ROW_INSET * iconScale,
                                Math.max(0.0, (qualityOption.getHeight() - iconSize) * .5));
            });

            // 勾选改成矢量：以前那个「✓」是塞在文字前面的字形，既占宽、和中文基线又对不齐，
            // 未选中的行还得补三个空格才不跳字。空位现在由列宽一次性留好。
            LucideIconWidget qualityOptionCheck = new LucideIconWidget(() -> FoliaLucideIcons.CHECK,
                    () -> "✓", FontManager.pf12bold, 0, 0, QUALITY_CHECK_SIZE, QUALITY_CHECK_SIZE);
            qualityOption.addChild(qualityOptionCheck);
            qualityOptionCheck.setClickable(false).setDrawScale(1.0);
            qualityOptionCheck.setBeforeRenderCallback(() -> {
                double checkScale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                double checkSize = QUALITY_CHECK_SIZE * checkScale;
                qualityOptionCheck
                        .setBounds(checkSize, checkSize)
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setPosition(qualityOption.getWidth() - checkSize - QUALITY_ROW_INSET * checkScale,
                                Math.max(0.0, (qualityOption.getHeight() - checkSize) * .5));
            });

            qualityOption
                    .setShouldOverrideMouseCursor(true)
                    .setHidden(true)
                    .setBeforeRenderCallback(() -> {
                        boolean selected = option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality);
                        // 探到「这一首没有这一档」才淡下去；还没探到一律按正常画。
                        boolean unavailable = isQualityUnavailable(option);
                        double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                        double optionHeight = QUALITY_OPTION_HEIGHT * scale;
                        // 与背景共用同一条固定底边，所以展开过程里选项不会整体滑动，只是逐行露出来。
                        double bottom = qualitySelector.getRelativeY() - 4.0;
                        double slotY = bottom - QUALITY_MENU_PADDING * scale
                                - (qualityRows - optionRow) * optionHeight;
                        // 背景的上边沿扫过这一项时它才显形：任何时候都不会有选项浮在面板之外。
                        double reveal = smoothProgress((slotY + optionHeight - qualityMenuBackground.getRelativeY())
                                / Math.max(1.0, optionHeight));
                        double slotX = qualityMenuBackground.getRelativeX() + QUALITY_MENU_PADDING * scale;
                        for (int previous = 0; previous < optionColumn; previous++) {
                            slotX += qualityColumnWidths[previous] * scale + QUALITY_COLUMN_GAP * scale;
                        }
                        qualityOption
                                .setBounds(qualityColumnWidths[optionColumn] * scale, optionHeight - 2.0 * scale)
                                .setPosition(slotX, slotY + (1.0 - reveal) * 3.0)
                                .setRadius(4.0 * scale)
                                // 选项排在菜单那块玻璃<em>里面</em>，不能再各自去贴一遍真背景：
                                // 那样每格透的是比周围更清楚的画面，读起来是一排挖在玻璃上的洞。
                                // 所以走 RIM —— 只上那一圈边缘光，靠底色深浅分档。
                                // 选中与探到的那一格不压薄：选中靠的就是这块实心强调色，
                                // 压成玻璃会让「现在用的是哪一档」看不出来。
                                .setGlassRim(GlassLook.Surface.PLAYER)
                                .setFillAlpha(qualityOption.isHovering() || (selected && !unavailable)
                                        ? 1.0f
                                        : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f))
                                .setColor(qualityOption.isHovering()
                                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                        : (selected && !unavailable
                                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)))
                                .setAlpha((float) (ControlsBar.this.getAlpha()
                                        * (unavailable ? .38f : selected ? .72f : .98f) * reveal));
                        // 收起动画期间不再接受点击，避免"已经看不清"的选项还能被误中。
                        qualityOption.setClickable(qualityMenuOpen && reveal > .35);
                        qualityOptionCheck.setHidden(!selected);
                    })
                    .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                        if (mouseButton != 0) return true;
                        if (isQualityUnavailable(option)) {
                            // 不切、也不关菜单：用户多半是怀疑这一档被吞了，留在他看得见的位置上
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

            LabelWidget optionText = new LabelWidget(() -> formatQuality(option), FontManager.pf12bold);
            qualityOption.addChild(optionText);
            optionText.setClickable(false);
            optionText.setBeforeRenderCallback(() -> {
                double textScale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                double textX = (QUALITY_ROW_INSET + QUALITY_ICON_SIZE + QUALITY_ICON_GAP) * textScale;
                optionText
                        .setColor(isQualityUnavailable(option)
                                ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)
                                : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                        .setMaxWidth(Math.max(0.0, qualityOption.getWidth() - textX
                                - (QUALITY_CHECK_GAP + QUALITY_CHECK_SIZE + QUALITY_ROW_INSET) * textScale))
                        .setPosition(textX, Math.max(1.0, (qualityOption.getHeight() - optionText.getHeight()) * .5));
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
