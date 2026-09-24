package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.ListenTogetherService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.PersonalFmManager;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.PlayerQueueIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.CoverHeroTransition;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.screens.ncm.VolumeControl;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.party.MpPartyClient;

import java.util.List;


/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/17 21:24
 */
public class ControlsBar extends NCMPanel {

    private boolean qualityMenuOpen;
    /**
     * 展开/收起的动画进度，0 为完全收起、1 为完全展开。由音质按钮的每帧回调推进：按钮常驻可见，
     * 而弹出层收起后会被隐藏、回调不再执行，进度放在弹出层里就没有帧可以播完收起动画。
     */
    private double qualityMenuAnimation;
    private RoundedRectWidget qualityMenuBackground;
    private RoundedRectWidget[] qualityOptions;
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

    /** 弹出层在动画彻底结束前必须继续渲染，否则收起会变成瞬间消失。 */
    private boolean isQualityMenuVisible() {
        return qualityMenuOpen || qualityMenuAnimation > .004;
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
        RectWidget bg = new RectWidget();

        this.addChild(bg);

        RoundedImageWidget playingCover = new RoundedImageWidget(() -> {
            if (CloudMusic.currentlyPlaying == null)
                return null;

            // 正在播放的曲目有动态封面时,播放条上的这张也跟着动;没有则仍是静态小封面。
            return CloudMusic.preferredCoverLocation(CloudMusic.currentlyPlaying,
                    CloudMusic.currentlyPlaying.getSmallCoverLocation());
        }, 0 , 0, 0, 0);

        this.addChild(playingCover);

        // 隐藏小封面这件事必须挂在 bg 的每帧回调上：被 setHidden 的组件自己的 beforeRenderCallback
        // 不再执行，写在 playingCover 上就再也取消不掉隐藏。bg 永远可见，而且它是第一个子组件、
        // 每帧都排在 playingCover 之前，是这条链上唯一安全的挂点。
        bg.setBeforeRenderCallback(() -> {
            bg.setMargin(0);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            bg.setAlpha(.95f - NCMTheme.getLiquidGlassAmount() * .22f);
            playingCover.setHidden(CoverHeroTransition.hidesSmallCover());
        });

        playingCover
                .fadeIn()
                .setLinearFilter(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> playingCover
                        .setMargin(5)
                        .setBounds(playingCover.getHeight(), playingCover.getHeight())
                        .setRadius(2))
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (CloudMusic.currentlyPlaying != null) {
                        // 起点必须在造面板之前交出去：面板构造函数会兜底启动转场时钟，那之后再报
                        // 起点就晚了一帧，封面会从上一次的位置起飞。
                        CoverHeroTransition.open(playingCover.getX(), playingCover.getY(),
                                playingCover.getWidth(), playingCover.getRadius());
                        NCMScreen.getInstance().musicLyricsPanel = new MusicLyricsPanel(CloudMusic.currentlyPlaying);
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

        playMode
                .setBeforeRenderCallback(() -> {
                    CloudMusic.PlayMode mode = CloudMusic.playMode;
                    boolean personalFm = CloudMusic.isPersonalFmActive();
                    playMode
                            .center()
                            .setPosition(next.getRelativeX() + next.getWidth() + 10, next.getRelativeY())
                            .setClickable(!personalFm)
                            .setAlpha(ControlsBar.this.getAlpha() * (personalFm ? .35f : 1.0f))
                            // 所给模式图标共享一种视觉语言；对顺序、随机和单曲循环使用一种
                            //一致的主题着色。
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
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

        // 显式私人 FM 动作。它在 FM 会话之外完全隐藏，
        //因此普通播放列表从不暴露也不调用电台垃圾端点。
        RoundedButtonWidget fmSkip = new RoundedButtonWidget("换一首", FontManager.pf12bold);
        this.addChild(fmSkip);
        fmSkip.setShouldOverrideMouseCursor(true);
        // 显示与否必须由播放条来判定，不能写在按钮自己的回调里：AbstractWidget#renderWidget
        // 在跑 beforeRenderCallback 之前就会因为 isHidden() 直接返回（父组件的渲染循环也会跳过
        // 隐藏的子组件），而唯一能把它重新显示出来的代码就在那个回调里。播放条是常驻的，首帧
        // 通常还没进入私人 FM，于是这个按钮会永久卡在隐藏状态，进了私人 FM 也不会出现。
        // 这一判定放到播放条自己的每帧回调里，和窄面板让位量一起算（见 onInit 末尾）。
        fmSkip.setBeforeRenderCallback(() -> {
            boolean active = CloudMusic.isPersonalFmActive();
            fmSkip.setClickable(active && !PersonalFmManager.isLoading());
            fmSkip.setBounds(44, 17);
            fmSkip.setPosition(playMode.getRelativeX() + playMode.getWidth() + 10, playMode.getRelativeY() + 1);
            fmSkip.setRadius(4);
            fmSkip.setAlpha(ControlsBar.this.getAlpha() * (PersonalFmManager.isLoading() ? .50f : 1.0f));
            fmSkip.setColor(fmSkip.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            fmSkip.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        fmSkip.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0 || !CloudMusic.isPersonalFmActive()) return mouseButton == 0;
            PersonalFmManager.skipCurrentAndRequestNext();
            return true;
        });



        RoundedRectWidget progressBarBg = new RoundedRectWidget() {

            boolean prevMouse = false;

            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);

                if (prevMouse && !Mouse.isButtonDown(0))
                    prevMouse = false;

                if (this.testHovered(mouseX, mouseY, 1) && Mouse.isButtonDown(0) && !prevMouse) {
                    prevMouse = true;
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
            }
        };

        this.addChild(progressBarBg);

        progressBarBg
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))
                .setRadius(1)
                .setBounds(135, 3)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> progressBarBg
                        .center()
                        .setPosition(progressBarBg.getRelativeX(), progressBarBg.getRelativeY() + 8));

        RoundedRectWidget progressBar = new RoundedRectWidget();

        progressBarBg.addChild(progressBar);
        progressBar
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setWidth(0)
                .setClickable(false)
                .setBeforeRenderCallback(() -> {
                    progressBar.setMargin(0);

                    // 镜像态下没有内部引擎，进度与总长都取自快照（见 CloudMusic 那两个 Now 方法）。
                    if (!CloudMusic.hasPlaybackClock())
                        return;

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

        LabelWidget lblMusicName = new LabelWidget(() -> CloudMusic.currentlyPlaying == null ? "未在播放" : CloudMusic.currentlyPlaying.getName(), FontManager.pf14bold);
        this.addChild(lblMusicName);

        lblMusicName
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblMusicName
                        .centerVertically()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicName.getRelativeY() - lblMusicName.getHeight() * .5 - 2
                        )
                        .setMaxWidth(Math.max(0.0,
                                lblCurTime.getRelativeX() - lblMusicName.getRelativeX() - 4)));

        LabelWidget lblMusicArtist = new LabelWidget(
                () -> {
                    if (CloudMusic.currentlyPlaying == null)
                        return "无";
                    // 专辑可以是 null（单曲、电台、以及一起听下发的曲目都可能没有专辑），
                    // 那时只显示歌手，不要为了拼一个「 - 」把整条播放条崩掉。
                    Music playing = CloudMusic.currentlyPlaying;
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
                        .centerVertically()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicArtist.getRelativeY() + lblMusicArtist.getHeight() * .5 + 2
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
                                    Math.max(1.0, (compactVolumeWidget.getParentHeight() - 20.0) * .5))
                            .setAlpha(ControlsBar.this.getAlpha());
                });

        // 紧凑质量选择器。可用的提供方档位在向上的
        //弹出窗中暴露，使用户能选择已知目标，而非反复循环按钮。
        // 把所有控件保持为 ControlsBar 的直接子级，也使弹出窗渲染
        //在紧凑音量控件之上，并防止它被选择器裁剪。
        final Quality[] selectableQualities = Quality.values();
        final double qualityButtonHeight = 20.0;
        final double qualityOptionHeight = 18.0;
        final double qualityMenuPadding = 3.0;


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
                    qualityMenuAnimation = Interpolations.interpolate(qualityMenuAnimation,
                            qualityMenuOpen ? 1.0 : 0.0, .3);
                    if (qualityMenuOpen && qualityMenuAnimation > .996) qualityMenuAnimation = 1.0;
                    if (!qualityMenuOpen && qualityMenuAnimation < .004) qualityMenuAnimation = 0.0;

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
                    double selectorGap = Math.min(38.0, Math.max(14.0, parentWidth * .045));
                    String selectorLabel = "音质 · " + formatQuality(CloudMusic.quality) + (qualityMenuOpen ? " ︿" : " ﹀");
                    double desiredWidth = (scale < .9 ? FontManager.pf10bold : FontManager.pf12bold).getStringWidthD(selectorLabel) + 14.0 * scale;
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
                            .setAlpha(ControlsBar.this.getAlpha() * .94f)
                            .setPosition(selectorX,
                                    Math.max(1.0, (qualitySelector.getParentHeight() - buttonHeight) * .5));
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

        LabelWidget qualityText = new LabelWidget(() -> "音质 · " + formatQuality(CloudMusic.quality) + (qualityMenuOpen ? " ︿" : " ﹀"), FontManager.pf12bold);
        qualitySelector.addChild(qualityText);
        qualityText.setClickable(false);
        qualityText.setBeforeRenderCallback(() -> qualityText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setMaxWidth(Math.max(0.0, qualitySelector.getWidth() - 14.0 * Math.max(.82, NCMPlayerConfig.getPlayerScale())))
                .setPosition(7.0, Math.max(2.0, (qualitySelector.getHeight() - qualityText.getHeight()) * .5)));

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
                    .setAlpha(fits ? ControlsBar.this.getAlpha() : 0f)
                    .centerVertically();
            queueToggle
                    .setPosition(toggleX, queueToggle.getRelativeY() - ControlsBar.this.iconBandLift)
                    .setColor(opened || CloudMusic.getQueuedNextCount() > 0
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        queueToggle.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            PlayQueuePanel queuePanel = NCMScreen.getInstance().getPlayQueuePanel();
            if (queuePanel != null) queuePanel.toggle();
            return true;
        });

        // 待播数量贴在图标右上角。作为图标的子组件，它自动继承那一份 alpha，
        // 因此窄面板下不需要第二处可见性判断。
        LabelWidget queueCount = new LabelWidget(() -> {
            int queued = CloudMusic.getQueuedNextCount();
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
                    .setAlpha(usable ? ControlsBar.this.getAlpha() : 0f)
                    .centerVertically();
            listenTogether
                    .setPosition(toggleX, listenTogether.getRelativeY() - ControlsBar.this.iconBandLift)
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

        // 播放条自己的每帧回调：既决定私人 FM 按钮的显示，也算出窄面板下的让位量。放在 onInit
        // 末尾是因为它要用到上面才声明的剩余时间标签与一起听入口 —— lambda 只能捕获已声明的
        // 局部变量。
        this.setBeforeRenderCallback(() -> {
            fmSkip.setHidden(!CloudMusic.isPersonalFmActive());

            double half = this.getWidth() * .5;
            // 一律按「未让位」的几何算：改读 playMode 让位后的实际坐标，会把让位量自己反馈进
            // 输入，每帧收敛一点，肉眼看到的就是控件一直在抖。
            double transportRight = half + 70.0 + (CloudMusic.isPersonalFmActive() ? 54.0 : 0.0);
            double progressRight = half + 71.5 + lblRemainingTime.getWidth();
            double bandLeft = listenTogetherPrimaryX;
            boolean firstFrame = bandLeft <= 0.0;

            // 图标带默认与音质/音量同高，那个高度上它跟剩余时间标签同排，必须为标签让路。让不
            // 出来（面板太窄）就整条抬到传输键那一行：标签在下面一行，抬 4 个单位就压不到字，
            // 于是只剩播放模式/换一首需要避让，一下省出一大截宽度。
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
                .setBeforeRenderCallback(() -> {
                    double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                    double menuHeight = selectableQualities.length * qualityOptionHeight * scale + qualityMenuPadding * 2.0 * scale;
                    // 底边固定贴在按钮上方，只有上边沿随进度向上展开，看起来是从按钮里长出来的。
                    double progress = smoothProgress(qualityMenuAnimation);
                    double bottom = qualitySelector.getRelativeY() - 4.0;
                    double visibleHeight = Math.max(.01, menuHeight * progress);
                    qualityMenuBackground
                            .setBounds(qualitySelector.getWidth(), visibleHeight)
                            .setPosition(qualitySelector.getRelativeX(), bottom - visibleHeight)
                            .setRadius(6.0 * Math.max(.82, NCMPlayerConfig.getPlayerScale()))
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            .setAlpha((float) (ControlsBar.this.getAlpha() * .98f * progress));
                });

        for (int index = 0; index < selectableQualities.length; index++) {
            final Quality option = selectableQualities[index];
            final int optionIndex = index;
            qualityOptions[index] = new RoundedRectWidget();
            RoundedRectWidget qualityOption = qualityOptions[index];
            this.addChild(qualityOption);
            qualityOption
                    .setShouldOverrideMouseCursor(true)
                    .setHidden(true)
                    .setBeforeRenderCallback(() -> {
                        boolean selected = option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality);
                        // 探到「这一首没有这一档」才淡下去；还没探到一律按正常画。
                        boolean unavailable = isQualityUnavailable(option);
                        double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                        double optionHeight = qualityOptionHeight * scale;
                        // 与背景共用同一条固定底边，所以展开过程里选项不会整体滑动，只是逐个露出来。
                        double bottom = qualitySelector.getRelativeY() - 4.0;
                        double slotY = bottom - qualityMenuPadding * scale
                                - (selectableQualities.length - optionIndex) * optionHeight;
                        // 背景的上边沿扫过这一项时它才显形：任何时候都不会有选项浮在面板之外。
                        double reveal = smoothProgress((slotY + optionHeight - qualityMenuBackground.getRelativeY())
                                / Math.max(1.0, optionHeight));
                        qualityOption
                                .setBounds(Math.max(0.0, qualitySelector.getWidth() - qualityMenuPadding * 2.0), optionHeight)
                                .setPosition(qualitySelector.getRelativeX() + qualityMenuPadding,
                                        slotY + (1.0 - reveal) * 3.0)
                                .setRadius(4.0 * scale)
                                .setColor(qualityOption.isHovering()
                                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                        : (selected && !unavailable
                                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)))
                                .setAlpha((float) (ControlsBar.this.getAlpha()
                                        * (unavailable ? .38f : selected ? .72f : .98f) * reveal));
                        // 收起动画期间不再接受点击，避免"已经看不清"的选项还能被误中。
                        qualityOption.setClickable(qualityMenuOpen && reveal > .35);
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

            LabelWidget optionText = new LabelWidget(() -> option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality)
                    ? "✓  " + formatQuality(option)
                    : "   " + formatQuality(option), FontManager.pf12bold);
            qualityOption.addChild(optionText);
            optionText.setClickable(false);
            optionText.setBeforeRenderCallback(() -> optionText
                    .setColor(isQualityUnavailable(option)
                            ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)
                            : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .setPosition(5.0, Math.max(1.0, (qualityOption.getHeight() - optionText.getHeight()) * .5)));
        }
    }

    private static String formatQuality(Quality quality) {
        if (quality == null) return "无损";
        switch (quality) {
            case STANDARD: return "标准";
            case HIGHER: return "高品质";
            case EXHIGH: return "极高";
            case LOSSLESS: return "无损";
            case HIRES: return "Hi-Res";
            case JYEFFECT: return "高清环绕";
            case SKY: return "沉浸音质";
            case JYMASTER: return "母带";
            default: return quality.getQuality();
        }
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
