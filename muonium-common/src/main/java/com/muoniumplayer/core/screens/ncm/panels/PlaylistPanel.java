package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.CoverflowOverlay;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.PlaylistCoverHero;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 */
public class PlaylistPanel extends NCMPanel {

    public PlayList playList;
    private final boolean showBackButton;

    public PlaylistPanel(PlayList playlist) {
        this(playlist, true);
    }

    /** 供最近播放列表使用，其父级发现页面已负责导航。 */
    public PlaylistPanel(PlayList playlist, boolean showBackButton) {
        this.playList = playlist;
        this.showBackButton = showBackButton;
    }

    /**
     * 曲目行的进场错峰号。
     *
     * <p>号是在行<b>第一次被渲染</b>时领的，不是建页时发好的：{@code ScrollPanel} 不渲染视口外的行，
     * 建页就发号等于只有首屏那几行有动画，往下滚什么也看不见。这里只负责每帧把头一批重新计数，
     * 见 {@link PanelItemEntrance.RevealQueue}。</p>
     */
    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();

    /** 左上角那张大封面。返回时要问它现在在屏幕上的哪儿，好把接力交回主页卡片。 */
    private RoundedImageWidget heroCover;

    @Override
    public void onRender(double mouseX, double mouseY) {
        this.revealQueue.beginFrame();
        this.revealQueue.setAnimationsSuppressed(this.musicsPanel != null && this.musicsPanel.isScrollActive());
        this.buildPendingMusicWidgets();
        super.onRender(mouseX, mouseY);
    }

    /**
     * 返回时把这张大封面交给接力：起点就是它此刻在屏幕上的矩形，落点是主页里同一个歌单的那张卡片。
     *
     * <p>只在目标确实是主页时调用 —— 主页可能把这张卡片滚在视口外，那样没人认领落点，
     * {@code PlaylistCoverHero} 会在第一帧自己收手，退化成纯交叉位移。</p>
     */
    public void handCoverBack() {
        if (this.heroCover == null) return;
        // 视口传 null：详情页这张大封面直接挂在面板上，不在任何滚动容器里，永远全须全尾。
        PlaylistCoverHero.begin(this, null, this.playList, this.playList.getCoverLocation(),
                this.heroCover.getX(), this.heroCover.getY(),
                this.heroCover.getWidth(), this.heroCover.getRadius(), null);
    }

    private TextFieldWidget tfSearch;
    private double tfOpenAnimation = 20;
    private volatile boolean musicsLoading = true;

    /** 歌曲行分帧构建：先发布首屏，余下行在后续渲染帧里补齐，避免大歌单回调卡住主线程。 */
    private static final int MUSIC_WIDGET_BUILD_BATCH = 32;
    private ScrollPanel musicsPanel;
    private List<Music> pendingMusicBuild;
    private int pendingMusicIndex;
    private String musicFilterText = "";
    private boolean emptyMusicMessageAdded;

    @Override
    public void onInit() {
        // onInit 也会在账户元数据（包括云盘 ID）
        // 异步到达时被调用。使其具备幂等性，以便刷新时替换
        // 旧行，而不是在第一份之上再叠加第二份。
        this.getChildren().clear();
        this.musicsLoading = true;
        this.musicsPanel = null;
        this.pendingMusicBuild = null;
        this.pendingMusicIndex = 0;
        this.musicFilterText = "";
        this.emptyMusicMessageAdded = false;

        // 搜索结果页不放返回按钮：搜索是从左侧导航栏的搜索框直接进入的，右上角这枚返回键在那里
        // 没有对应的"上一页"，只会把内容区右上角挤掉一块。歌单详情、发现页曲目列表照旧保留。
        boolean backButtonAllowed = showBackButton && !playList.isSearchResultList();
        RoundedButtonWidget btnBack = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
        btnBack.setGlassPlate(GlassLook.Surface.PLAYER)
                .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        this.addChild(btnBack);
        btnBack.setShouldOverrideMouseCursor(true);
        btnBack.setBeforeRenderCallback(() -> {
            btnBack.setHidden(!backButtonAllowed);
            btnBack.setBounds(28, 22);
            btnBack.setPosition(Math.max(8, getWidth() - btnBack.getWidth() - 12), 8);
            btnBack.setRadius(6);
            btnBack.setColor(btnBack.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            btnBack.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        btnBack.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().navigateBack();
            return true;
        });

        // 头部让位槽：非搜索模式由封面回调每帧写入（宽档 176 / 窄档 144），
        // 搜索模式建页时定格 18。列表容器按它让位。
        final double[] headerHeight = new double[1];
        headerHeight[0] = 176.0;

        // 头部按钮排的窄窗折行槽：[0] 下一颗按钮的 x 锚点、[1] 下一颗按钮的 y。
        // 由链上前一颗按钮的回调写入、下一颗读（渲染顺序 = addChild 顺序）。
        final double[] headerButtonNextX = new double[1];
        final double[] headerButtonWrapY = new double[1];
        // 手机比例的窄内容区才启用紧凑头：电脑等宽窗口保持原来的大气头不动。
        // 本面板的尺寸就是内容区矩形（NCMScreen 每帧把 currentPanelBg 的矩形设给它），
        // 所以渲染回调里的 getWidth() 就是内容区宽。<b>必须在回调里读</b>：构建那一刻
        // 面板还没被设过边界，getWidth() 恒 0，按它判档会让所有设备都掉进窄档
        // （2026-10 真机翻过这个车：1920 宽的电脑屏幕上头部被压成 96）。
        // 阈值 400：按钮链四颗 57×4 + 3×8 + 起点 164 = 424，内容区 < 400 时必然折行，
        // 与其让按钮跳到第二行占掉列表，不如整头一起收。

        if (!playList.isSearchMode()) {
            RoundedImageWidget cover = new RoundedImageWidget(this.playList.getCoverLocation(), 0, 0, 0, 0);

            cover.setPosition(24, 24);
            cover.setBounds(128, 128);
            cover.fadeIn();
            cover.setLinearFilter(true);

            this.addChild(cover);
            this.heroCover = cover;
            this.loadCover();

            cover.setBeforeRenderCallback(() -> {
                if (PlaylistCoverHero.consumeHandoff(this)) {
                    cover.setAlpha(1.0f);
                }
                cover.setRadius(4);
                // 窄内容区收成 96：判据每帧现读（见上方注释），跨过阈值当场切档。
                double coverSize = getWidth() < 400.0 ? 96.0 : 128.0;
                cover.setBounds(coverSize, coverSize);
                headerHeight[0] = cover.getRelativeY() + cover.getHeight() + 24;
                // 每帧回报落点：接力封面要朝这里飞，而这一帧封面本身让位（纹理供给换成 null，
                // 控件照常跑回调），所以屏幕上任何时刻只有一张封面。
                PlaylistCoverHero.setTarget(this, this.playList, cover.getX(), cover.getY(),
                        cover.getWidth(), cover.getRadius(), null);
            });
            cover.setLocImg(() -> PlaylistCoverHero.coversYield(this) ? null : this.playList.getCoverLocation());

//        LabelWidget lblPlaylistName = new LabelWidget(playList.name, FontManager.pf);
            RoundedButtonWidget btnPlay = new RoundedButtonWidget("播放歌单", FontManager.pf16bold);
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnPlay.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnPlay);

            btnPlay.setBeforeRenderCallback(() -> {
                btnPlay.setBounds(57, 17);
                btnPlay.setPosition(cover.getRelativeX() + cover.getWidth() + 12, cover.getRelativeY() + cover.getHeight() - btnPlay.getHeight());
                btnPlay.setRadius(3);
                btnPlay.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnPlay.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                // 窄内容区保护：按钮排 57 宽 + 8 间隙逐颗链下去，内容区不够时后续按钮
                // 折到封面下方第二行（y 写进共享槽，渲染顺序按 addChild 先后，前一颗
                // 写完下一颗才读）。行会占掉列表 17px —— 折行只在真的放不下时发生。
                headerButtonWrapY[0] = btnPlay.getRelativeX() + btnPlay.getWidth() + 8 + 57 > getWidth() - 12
                        ? cover.getRelativeY() + cover.getHeight() + 6
                        : cover.getRelativeY() + cover.getHeight() - 17;
                headerButtonNextX[0] = btnPlay.getRelativeX() + btnPlay.getWidth() + 8;
            });

            btnPlay.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    playList.loadMusicsWithCallback(musics -> {
                        CloudMusic.currentPlaylistContext = playList.isSearchMode() ? null : playList;
                        CloudMusic.play(musics, 0);
                    });
                }

                return true;
            });


            // 「排进房间」= 把这一整张单子挂到一起听的队列后面。它只在<b>真的在一间房间里、
            // 而且这台服务端认识这个功能</b>时才出现（频道里不出现：全服共用的房间不该被一个人
            // 倒进一百首）。
            //
            // 按钮文字用「排进」而不是「播放」是有意的：它不会动正在播的那首歌，也不会插到别人
            // 前面 —— 说「播放」会让人以为按下去立刻换歌，而实际要到队列排空才听得见。
            //
            // 显隐走 alpha 而不是 setHidden：renderWidget 对隐藏的控件整帧跳过，挂在它身上的
            // beforeRenderCallback 也就不再执行 —— 一旦在建页那一瞬还没进房（房间状态晚到、
            // 重连的空档），setHidden(true) 之后这颗按钮就永远没有机会把自己显示回来，只能靠
            // 换一张歌单重建面板。alpha 每帧照算，进房后自己淡出来，正在翻的歌单、滚动位置
            // 一概不动。
            RoundedButtonWidget btnParty = new RoundedButtonWidget("排进房间", FontManager.pf16bold);
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnParty.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnParty);
            final double[] partyFade = new double[1];
            btnParty.setBeforeRenderCallback(() -> {
                boolean show = MpPartyClient.playlistSupported();
                partyFade[0] = Interpolations.interpolate(partyFade[0], show ? 1.0 : 0.0, .3f);
                boolean visible = partyFade[0] > .04f;
                btnParty.setClickable(visible);
                btnParty.setAlpha((float) partyFade[0]);
                btnParty.setBounds(57, 17);
                btnParty.setPosition(headerButtonNextX[0], headerButtonWrapY[0]);
                btnParty.setRadius(3);
                btnParty.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnParty.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                // 无论显隐都续写链：这颗淡出时下一颗的锚点要顶到它原来的位置。
                // 折行判据只看「下一颗的槽右缘」：越界就把后续按钮整体放到封面下方第二行，
                // 行高 17 + 上下呼吸 6。
                double nextX = headerButtonNextX[0] + 57 + 8;
                headerButtonNextX[0] = nextX;
                if (nextX + 57 > getWidth() - 12) {
                    headerButtonWrapY[0] = cover.getRelativeY() + cover.getHeight() + 6;
                }
            });

            btnParty.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0) {
                    // 曲目没在手上的单子（导入单、没点开过的账号单）由 requestPlaylist 内部先去加载，
                    // 加载回来再分帧发；这里不需要照抄「播放歌单」那一段 loadMusicsWithCallback。
                    MpPartyClient.requestPlaylist(playList);
                }
                return true;
            });


            RoundedButtonWidget btnCoverflow = new RoundedButtonWidget("Coverflow", FontManager.pf16bold);
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnCoverflow.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnCoverflow);

            btnCoverflow.setBeforeRenderCallback(() -> {
                btnCoverflow.setBounds(57, 17);
                // 这一排按钮是<b>链式</b>定位的，「排进房间」收起时 Coverflow 要顶回那个位置，
                // 否则不在一起听的界面上会留一块谁都不属于的空位。锚点按淡出进度连续过渡
                // 而不是按布尔跳变：按钮淡出的那几帧里 Coverflow 平滑跟上来，不会瞬移盖住它。
                // 折行时 btnParty 已经落到第二行，淡出锚点跟着它的实际位置算，过渡仍然连续。
                double partyAnchor = btnParty.getRelativeX() + btnParty.getWidth();
                double collapsedAnchor = btnPlay.getRelativeX() + btnPlay.getWidth();
                double anchor = collapsedAnchor + (partyAnchor - collapsedAnchor) * partyFade[0];
                btnCoverflow.setPosition(anchor + 8, headerButtonWrapY[0]);
                btnCoverflow.setRadius(3);
                btnCoverflow.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnCoverflow.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                double nextX = anchor + 8 + 57 + 8;
                headerButtonNextX[0] = nextX;
                if (nextX + 57 > getWidth() - 12) {
                    headerButtonWrapY[0] = cover.getRelativeY() + cover.getHeight() + 6;
                }
            });

            btnCoverflow.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    api.displayScreen(CoverflowOverlay.byPlaylist(playList));
                }

                return true;
            });

            RoundedButtonWidget btnSubscribe = new RoundedButtonWidget(
                    () -> playList.isSubscribed() ? "取消收藏" : "收藏歌单", FontManager.pf16bold);
                    // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
                    btnSubscribe.setGlassPlate(GlassLook.Surface.PLAYER)
                            .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnSubscribe);
            btnSubscribe.setBeforeRenderCallback(() -> {
                boolean canToggleSubscription = canToggleSubscription();
                btnSubscribe.setHidden(!canToggleSubscription);
                btnSubscribe.setBounds(57, 17);
                btnSubscribe.setPosition(headerButtonNextX[0], headerButtonWrapY[0]);
                btnSubscribe.setRadius(3);
                btnSubscribe.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnSubscribe.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                double nextX = headerButtonNextX[0] + 57 + 8;
                headerButtonNextX[0] = nextX;
                if (nextX + 57 > getWidth() - 12) {
                    headerButtonWrapY[0] = cover.getRelativeY() + cover.getHeight() + 6;
                }
            });
            btnSubscribe.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0 || !canToggleSubscription()) {
                    return false;
                }
                if (!playList.isSubscribed()) {
                    playList.setSubscribed(true);
                    MultiThreadingUtil.runAsync(() -> playList.subscribe(true));
                    return true;
                }
                NCMScreen.getInstance().openConfirmation(
                        "取消收藏歌单？",
                        "取消后，该歌单将从“我的账号歌单”中移除。",
                        "取消收藏",
                        () -> {
                            playList.setSubscribed(false);
                            MultiThreadingUtil.runAsync(() -> playList.subscribe(false));
                        }
                );
                return true;
            });
            // 本地单占的是上面「收藏歌单」那一格：那张单子没有任何上游可以收藏，那一格对它没有
            // 意义，而它确实需要一个改名字的地方（名字是这张单在本机上唯一的标识）。
            // 汽水自建云歌单同一格给「重命名」：写回 /luna/pc/me/playlist/update，提交走云表单。
            RoundedButtonWidget btnRename = new RoundedButtonWidget("重命名", FontManager.pf16bold);
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnRename.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnRename);
            btnRename.setBeforeRenderCallback(() -> {
                boolean renamable = playList != null
                        && (playList.isLocalPlaylist() || ownQishuiCloudPlaylist());
                btnRename.setBounds(57, 17);
                // 与「收藏歌单」同槽互斥（两者都挂在本格），锚点读共享槽而不是上一颗的实际位置：
                // 收藏格这帧 hidden 时它的坐标仍在续写链，读槽保证本格位置稳定。
                btnRename.setPosition(headerButtonNextX[0], headerButtonWrapY[0]);
                btnRename.setRadius(3);
                btnRename.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnRename.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                btnRename.setHidden(!renamable);
            });
            btnRename.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0) return false;
                if (playList != null && ownQishuiCloudPlaylist()) {
                    NCMScreen.getInstance().openQishuiCloudPlaylistRename(playList);
                } else {
                    NCMScreen.getInstance().openPlaylistRename(playList);
                }
                return true;
            });

            // 汽水自建云歌单才有「删除歌单」：真删上游，虚拟单（每日推荐/电台）与别家歌单不配。
            RoundedButtonWidget btnDeleteCloud = new RoundedButtonWidget("删除歌单", FontManager.pf16bold);
            btnDeleteCloud.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnDeleteCloud);
            btnDeleteCloud.setBeforeRenderCallback(() -> {
                btnDeleteCloud.setBounds(57, 17);
                btnDeleteCloud.setPosition(headerButtonNextX[0], headerButtonWrapY[0]);
                btnDeleteCloud.setRadius(3);
                // 危险动作的红色与表单层的 BAD_COLOR(NcmSheet) 同值：全界面的「删除红」就一种。
                btnDeleteCloud.setColor(0xF1767D);
                btnDeleteCloud.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                btnDeleteCloud.setHidden(!ownQishuiCloudPlaylist());
            });
            btnDeleteCloud.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0 || !ownQishuiCloudPlaylist()) return false;
                NCMScreen.getInstance().openConfirmation(
                        "删除云歌单？",
                        "「" + playList.getName() + "」将从你的汽水账号中删除，客户端同步消失，不可恢复。",
                        "删除",
                        () -> MultiThreadingUtil.runAsync(() -> {
                            boolean ok = QishuiMusicService.deleteCloudPlaylist(
                                    playList.getPlatformPlaylistId());
                            MultiThreadingUtil.runOnMainThread(() -> {
                                DownloadDynamicIsland.showModelOperationResult("汽水云歌单",
                                        ok ? "已删除「" + playList.getName() + "」"
                                                : "删除失败：签名后端没通或上游拒绝",
                                        ok);
                                if (ok) {
                                    NCMScreen.getInstance().markDirty();
                                    NCMScreen.getInstance().setCurrentPanel(new HomePanel());
                                }
                            });
                        }));
                return true;
            });

            RoundedRectWidget searchBar = new RoundedRectWidget();
            this.addChild(searchBar);

            searchBar
                    .setShouldOverrideMouseCursor(true)
                    .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                        if (mouseButton == 0) {
                            if (!this.tfSearch.isFocused()) {
                                this.tfSearch.setFocused(true);
                                this.tfSearch.getTextField().lmbPressed = true;
                            }
                        }

                        return true;
                    })
                    .setBeforeRenderCallback(() -> {
                        tfOpenAnimation = Interpolations.interpolate(tfOpenAnimation, this.tfSearch.isFocused() ? 80 : 20, .3f);

                        this.tfSearch.setHidden(!this.tfSearch.isFocused() && tfOpenAnimation < 21);

                        boolean canToggleSubscription = canToggleSubscription();
                        double searchAnchorX = btnCoverflow.getRelativeX() + btnCoverflow.getWidth();
                        // 收藏与重命名共用同一格、互斥出现，所以两边都要参与取最右；隐藏的控件不能读
                        // 它的位置（它的回调不再跑，那里停的是上一次的值），只能按各自的可见条件判。
                        if (canToggleSubscription) {
                            searchAnchorX = btnSubscribe.getRelativeX() + btnSubscribe.getWidth();
                        } else if (ownQishuiCloudPlaylist()) {
                            // 汽水自建单：重命名 + 删除两颗并排，搜索框锚在删除那颗后面。
                            searchAnchorX = btnDeleteCloud.getRelativeX() + btnDeleteCloud.getWidth();
                        } else if (playList != null && playList.isLocalPlaylist()) {
                            searchAnchorX = btnRename.getRelativeX() + btnRename.getWidth();
                        }
                        searchBar
                                .setAlpha(1f)
                                .setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))
                                .setWidth(tfOpenAnimation)
                                .setHeight(btnCoverflow.getHeight())
                                .setRadius(7)
                                .setPosition(searchAnchorX + 8, btnCoverflow.getRelativeY());
                    });

            RoundedRectWidget searchBarBg = new RoundedRectWidget();
            searchBar.addChild(searchBarBg);
            searchBarBg
                    .setClickable(false)
                    .setBeforeRenderCallback(() -> {
                        searchBarBg
                                .setMargin(.5)
                                // 只压这块底：搜索框里的文字与图标是 searchBar 的子控件，不归它管。
                                .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, .6f))
                                // 这一格本身是一块小玻璃（贴背景 + 薄折射 + 边缘高光）。
                                .setGlassPlate(GlassLook.Surface.PLAYER)
                                .setRadius(searchBar.getRadius() - .5);
                        searchBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
                    });

            LabelWidget lblSearchIcon = new LabelWidget("K", FontManager.music18);
            searchBar.addChild(lblSearchIcon);
            lblSearchIcon
                    .setClickable(false)
                    .setColor(hexColor(100, 100, 100))
                    .setBeforeRenderCallback(() -> lblSearchIcon
                            .centerVertically()
                            .setPosition(lblSearchIcon.getRelativeY(), lblSearchIcon.getRelativeY()));

            this.tfSearch = new TextFieldWidget(FontManager.pf14bold);
            searchBar.addChild(tfSearch);

            this.tfSearch.setOnKeyTypedCallback((character, keyCode) -> {
                if (this.tfSearch.isFocused()) {
                    if (keyCode == Keyboard.KEY_ESCAPE)
                        this.tfSearch.setFocused(false);


                    return true;
                }

                return false;
            });

            this.setOnKeyTypedCallback((character, keyCode) -> {

                if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_G)) {
                    this.tfSearch.setFocused(true);
                    this.tfSearch.getTextField().selectAll();
                    return true;
                }

                return false;
            });

            tfSearch.setBeforeRenderCallback(() -> {
                tfSearch.drawUnderline(false);
                tfSearch.setMargin(2);
                double xSpacing = lblSearchIcon.getRelativeX() + lblSearchIcon.getWidth() + 4;
                tfSearch.setBounds(xSpacing, tfSearch.getRelativeY(), tfSearch.getWidth() - xSpacing, tfSearch.getHeight());
                tfSearch.setColor(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                tfSearch.setDisabledTextColor(RenderSystem.reAlpha(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .4f));
            });

            User creator = this.playList.getCreator();
            boolean hasCreatorAvatar = creator != null
                    && creator.getAvatarUrl() != null
                    && !creator.getAvatarUrl().trim().isEmpty();
            RoundedImageWidget creatorAvatar = hasCreatorAvatar
                    ? new RoundedImageWidget(creator.getAvatarLocation(), 0, 0, 0, 0)
                    : null;

            if (creatorAvatar != null) {
                this.addChild(creatorAvatar);
                creatorAvatar.fadeIn();
                creatorAvatar.setLinearFilter(true);
                this.loadAvatar();
                creatorAvatar.setBeforeRenderCallback(() -> {
                    creatorAvatar.setBounds(16, 16);
                    creatorAvatar.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                            btnPlay.getRelativeY() - 6 - creatorAvatar.getHeight());
                    creatorAvatar.setRadius(7.25);
                });
            }

            // 没有创建者时退到「这张单来自哪个平台」。本地单不能这么退：它的 platform 字段顶着
            // NETEASE（那只是它被造出来时所处的世界），照抄就会在本机自建的单子上写「网易云」。
            String creatorName;
            if (playList.isLocalPlaylist()) {
                creatorName = "Mu本地";
            } else {
                creatorName = creator == null || creator.getName() == null || creator.getName().trim().isEmpty()
                        ? playList.getPlatform().getDisplayName()
                        : creator.getName();
            }
            LabelWidget lblCreator = new LabelWidget(creatorName, FontManager.pf16bold);
            this.addChild(lblCreator);

            lblCreator.setBeforeRenderCallback(() -> {
                if (creatorAvatar != null) {
                    lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4,
                            creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
                } else {
                    lblCreator.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                            btnPlay.getRelativeY() - 6 - lblCreator.getHeight());
                }
                // 创建者名与统计行同一条右让位（返回键左缘）：长作者名窄窗下不再顶出去。
                lblCreator.setMaxWidth(Math.max(40, getWidth() - (cover.getRelativeX() + cover.getWidth() + 12) - 44));
                lblCreator.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            LabelWidget lblPlaylistInfo = new LabelWidget(this::getPlayListInfo, FontManager.pf12);
            this.addChild(lblPlaylistInfo);

            lblPlaylistInfo.setBeforeRenderCallback(() -> {
                double creatorTop = creatorAvatar == null
                        ? lblCreator.getRelativeY()
                        : creatorAvatar.getRelativeY();
                lblPlaylistInfo.setMaxWidth(Math.max(40, getWidth() - (cover.getRelativeX() + cover.getWidth() + 12) - 12));
                lblPlaylistInfo.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                lblPlaylistInfo.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                        creatorTop - 8 - lblPlaylistInfo.getHeight());
                lblPlaylistInfo.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            });
            LabelWidget lblPlaylistName = new LabelWidget(playList.getName(), FontManager.pf32);
            this.addChild(lblPlaylistName);

            lblPlaylistName.setBeforeRenderCallback(() -> {
                // 宽窗口保持 pf32 大字；窄内容区降一档到 pf24bold。判据与封面同一条、
                // 每帧现读。截断保护两档都要：原来这个名字没有任何让位，长歌名直接画出右缘。
                lblPlaylistName.setFont(getWidth() < 400.0 ? FontManager.pf24bold : FontManager.pf32);
                lblPlaylistName.setMaxWidth(Math.max(40, getWidth() - (cover.getRelativeX() + cover.getWidth() + 12) - 44));
                lblPlaylistName.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                lblPlaylistName.setPosition(cover.getRelativeX() + cover.getWidth() + 12, lblPlaylistInfo.getRelativeY() - 4 - lblPlaylistName.getHeight());
                lblPlaylistName.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            // 非搜索模式的让位由 headerHeight 槽逐帧承载（封面回调写入），这里不再定格。
        } else {
            headerHeight[0] = 18.0;
        }

        Panel rwMusicsContainer = new Panel();
        final double bottomSafeInset = 8.0;
        final boolean searchLayout = playList.isSearchMode();

        this.addChild(rwMusicsContainer);

        rwMusicsContainer.setBeforeRenderCallback(() -> {
            // 让最后一个可见行与控制栏保持间距。嵌套的 ScrollPanel
            // 将歌曲行裁剪到这个缩小后的视口，而不是让文字贴边。
            // 非搜索模式读 headerHeight 槽（宽档 176 / 窄档 144 随内容区宽切换），
            // 搜索模式恒 18。
            double offsetY = searchLayout ? 18.0 : headerHeight[0];
            rwMusicsContainer.setBounds(
                    Math.max(0.0, this.getWidth() - 36),
                    Math.max(0.0, this.getHeight() - offsetY - bottomSafeInset)
            );
            rwMusicsContainer.centerHorizontally();
            rwMusicsContainer.setPosition(rwMusicsContainer.getRelativeX(), offsetY);
        });

        final ScrollPanel musicsPanel = new ScrollPanel();
        this.musicsPanel = musicsPanel;

        rwMusicsContainer.addChild(musicsPanel);
        musicsPanel.setSpacing(0)
                .setUniformVerticalItemHeight(30.0);
        // 行与行挨着，就按<em>一块</em>玻璃收尾：这一组里每行登记自己的轮廓，画完子控件之后
        // 由一趟着色器按整张距离场画边（设置里「玻璃融合半径」推到十几才看得见变化，0 就是
        // 原来那样每行一圈）。为什么点名的这个容器而不是那一排行：融合必须括在<em>同一层裁剪</em>
        // 里，ScrollPanel 的 begin/endClip 正好就是那一层（见 AbstractWidget#renderWidget）。
        musicsPanel.setGlassFusion(GlassLook.Surface.PLAYER);

        musicsPanel.setBeforeRenderCallback(() -> musicsPanel.setMargin(0));

        // 将加载反馈保持在被裁剪的歌曲列表上方。该控件会在
        // 异步请求完成后自行淡出。
        PanelLoadingWidget loadingWidget = new PanelLoadingWidget("正在加载曲目…");
        // 只把搜索结果页换成 search.svg 那枚取景框动画。同一份控件也服务歌单详情、主页、发现页
        // 与导航等待遮罩，那边等的是"一批曲目"而不是"一次搜索"，换成放大镜是在骗人。
        if (playList.isSearchResultList()) {
            loadingWidget.setAnimatedIcon(AnimatedIcons.search(), true);
        }
        this.addChild(loadingWidget);
        loadingWidget
                .setClickable(false)
                .setAlpha(0f)
                .setBeforeRenderCallback(() -> {
                    loadingWidget.setBounds(rwMusicsContainer.getRelativeX(), rwMusicsContainer.getRelativeY(),
                            rwMusicsContainer.getWidth(), rwMusicsContainer.getHeight());

                    float targetAlpha = musicsLoading ? 1f : 0f;
                    float nextAlpha = Interpolations.interpolate(loadingWidget.getWidgetAlpha(), targetAlpha, .24f);
                    loadingWidget.setAlpha(nextAlpha);
                    loadingWidget.setHidden(!musicsLoading && nextAlpha <= .015f);
                });

        playList.loadMusicsWithCallback(musics -> {
            // 不在网络回调里一次性构建整张歌单。先复制快照，再由 onRender 每帧补一小批，
            // 让首屏先出来，滚动和播放器其它动画不被几百个控件的构造阻塞。
            this.pendingMusicBuild = musics == null ? new ArrayList<>() : new ArrayList<>(musics);
            this.pendingMusicIndex = 0;
            this.revealQueue.restart();
            this.musicsLoading = false;
            this.buildPendingMusicWidgets();
        });

        if (this.tfSearch != null) {
            this.tfSearch.setTextChangedCallback(text -> {
                this.musicFilterText = text == null ? "" : text.trim().toLowerCase();
                this.applyMusicFilter();
            });
        }
    }

    /**
     * 每帧只构建一小批歌曲行。歌曲对象已经在后台线程准备好，真正昂贵的是 MusicWidget
     * 及其子控件树的创建和首次布局；把它摊到渲染帧之间可以避免大歌单打开/滚动时长帧。
     */
    private void buildPendingMusicWidgets() {
        if (this.musicsPanel == null || this.pendingMusicBuild == null) return;

        int total = this.pendingMusicBuild.size();
        int end = Math.min(total, this.pendingMusicIndex + MUSIC_WIDGET_BUILD_BATCH);
        if (this.pendingMusicIndex < end) {
            List<AbstractWidget<?>> batch = new ArrayList<>(end - this.pendingMusicIndex);
            for (int index = this.pendingMusicIndex; index < end; index++) {
                MusicWidget musicWidget = new MusicWidget(this.pendingMusicBuild.get(index), this.playList, index);
                musicWidget.setShouldOverrideMouseCursor(true);
                musicWidget.setEntranceAnimation(this.revealQueue);
                if (!this.musicFilterText.isEmpty()) {
                    musicWidget.setHidden(!matchesMusicFilter(musicWidget.music, this.musicFilterText));
                }
                batch.add(musicWidget);
            }
            this.musicsPanel.addChild(batch);
            this.pendingMusicIndex = end;
        }

        if (this.pendingMusicIndex >= total) {
            this.pendingMusicBuild = null;
            if (total == 0 && this.playList.isLocalPlaylist()) {
                addEmptyMusicMessage();
            }
        }
    }

    private void addEmptyMusicMessage() {
        if (this.emptyMusicMessageAdded || this.musicsPanel == null) return;
        this.musicsPanel.setUniformVerticalItemHeight(-1.0);
        this.emptyMusicMessageAdded = true;
        LabelWidget empty = new LabelWidget("这张单还是空的 · 到任意列表点歌曲行右侧的「＋」，选它就能收进来",
                FontManager.pf14bold);
        empty.setClickable(false);
        empty.setBeforeRenderCallback(() -> {
            empty.setBounds(Math.max(1, this.musicsPanel.getWidth()), 28);
            empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            empty.setPosition(10, 7);
        });
        this.musicsPanel.addChild(empty);
    }

    private void applyMusicFilter() {
        if (this.musicsPanel == null) return;
        // 无筛选时歌曲行恒为 30px，恢复等高快速路径；筛选会隐藏行，必须回到通用排版。
        this.musicsPanel.setUniformVerticalItemHeight(this.musicFilterText.isEmpty() ? 30.0 : -1.0);
        for (AbstractWidget<?> child : this.musicsPanel.getChildren()) {
            if (child instanceof MusicWidget) {
                MusicWidget widget = (MusicWidget) child;
                widget.setHidden(!matchesMusicFilter(widget.music, this.musicFilterText));
            }
        }
    }

    private static boolean matchesMusicFilter(Music music, String filter) {
        if (music == null || filter == null || filter.isEmpty()) return true;
        String name = music.getName() == null ? "" : music.getName().toLowerCase();
        String translated = music.getTranslatedNames() == null ? "" : music.getTranslatedNames().toLowerCase();
        if (name.contains(filter) || translated.contains(filter)) return true;
        if (music.getArtists() != null && music.getArtists().stream()
                .anyMatch(artist -> artist != null && artist.getName() != null
                        && artist.getName().toLowerCase().contains(filter))) {
            return true;
        }
        return music.getAlbum() != null && music.getAlbum().getName() != null
                && music.getAlbum().getName().toLowerCase().contains(filter);
    }

    /**
     * 仅外部网易云歌单支持订阅/取消订阅开关。
     * 当前账号创建的歌单本身已属于“我的歌单”，
     * 因此在那里显示“取消收藏”既具误导性又无实际作用。
     */
    private boolean canToggleSubscription() {
        if (playList == null) return false;
        // 本地单没有「收藏」这个动作可以对上游做：它的 platform 字段顶着 NETEASE（那只是它被造出来时
        // 所处的世界），拿一个负数 id 去调 /weapi/playlist/subscribe 是一次发给网易云的垃圾写请求。
        if (playList.isLocalPlaylist()) return false;
        if (playList.getPlatform() == MusicPlatform.KUGOU) {
            // 酷狗要登录才能收藏；而且「自己的歌单」不该显示收藏按钮——那边收藏会复制一份新歌单，
            // 点自己的歌单复制它没有意义。用户歌单在 platformPlaylistId 里带 #listid，用这个区分。
            return KuGouMusicService.isLoggedIn()
                    && KuGouMusicService.listIdOf(playList.getPlatformPlaylistId()).isEmpty();
        }
        if (playList.getPlatform() != MusicPlatform.NETEASE) {
            return false;
        }
        User creator = playList.getCreator();
        User currentUser = CloudMusic.profile;
        return creator == null || currentUser == null || creator.getId() != currentUser.getId();
    }

    /**
     * 打开的这张是不是<b>汽水自建云歌单</b>（acct: 前缀）：只有这类单子能改名、能删除、
     * 行内能移除歌曲。每日推荐（qsdaily:）与电台（radio:）是虚拟单，收藏歌单来自别人，
     * 都没有这些写权限。
     */
    private boolean ownQishuiCloudPlaylist() {
        return playList != null
                && playList.getPlatform() == MusicPlatform.QISHUI
                && playList.getPlatformPlaylistId() != null
                && playList.getPlatformPlaylistId().startsWith("acct:");
    }

    // 转子的实现已提到 PanelLoadingWidget：主页、发现页、导航等待遮罩用的是同一个控件。
    private String formatDuration(long totalMillis) {
        long totalSeconds = totalMillis / 1000;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();

        if (hours > 0) {
            sb.append(String.format("%02d时", hours));
        }

        if (minutes > 0) {
            sb.append(String.format("%02d分", minutes));
        }

        sb.append(String.format("%02d秒", seconds));

        return sb.toString();
    }

    String cached = "";
    int lastSize = -1;

    private String getPlayListInfo() {
        if (!playList.musicsLoaded)
            return "";

        List<Music> musics = playList.musics;

        if (lastSize != musics.size()) {
            lastSize = musics.size();
            if (musics.isEmpty()) {
                // 本地单没有「上游那张单里有几首」这个数：它的曲目就是它的全部，空的就是 0 首。
                // （{@code count} 是 final，删空之后它在本次会话里仍停在加载时那个值。）
                cached = (playList.isLocalPlaylist() ? 0 : playList.getCount()) + "首歌曲";
            } else {
                cached = musics.size() + "首歌曲 · " + this.formatDuration(musics.stream().mapToLong(Music::getDuration).sum());
            }
        }

        return cached;
    }

    private void loadCover() {
        String coverUrl = this.playList.getCoverUrl();
        if (coverUrl == null || coverUrl.trim().isEmpty()) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location coverLoc = this.playList.getCoverLocation();
        if (textureManager.getTexture(coverLoc) != null) {
            return;
        }

        String secureUrl = coverUrl.replace("http://", "https://");
        String requestUrl = this.playList.getPlatform() == MusicPlatform.QQ
                ? secureUrl
                : secureUrl + "?param=256y256";
        Textures.downloadTextureAndLoadAsync(requestUrl, coverLoc);
    }

    private void loadAvatar() {
        User creator = this.playList.getCreator();
        if (creator == null || creator.getAvatarUrl() == null || creator.getAvatarUrl().trim().isEmpty()) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location avatarLoc = creator.getAvatarLocation();
        if (textureManager.getTexture(avatarLoc) != null) {
            return;
        }

        String secureUrl = creator.getAvatarUrl().replace("http://", "https://");
        Textures.downloadTextureAndLoadAsync(secureUrl + "?param=32y32", avatarLoc);
    }

}
