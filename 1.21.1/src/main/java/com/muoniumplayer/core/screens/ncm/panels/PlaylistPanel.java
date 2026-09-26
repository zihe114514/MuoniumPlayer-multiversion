package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.FontelloIcons;
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/17 18:42
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
        super.onRender(mouseX, mouseY);
        this.revealQueue.beginFrame();
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

    @Override
    public void onInit() {
        // onInit 也会在账户元数据（包括云盘 ID）
        // 异步到达时被调用。使其具备幂等性，以便刷新时替换
        // 旧行，而不是在第一份之上再叠加第二份。
        this.getChildren().clear();
        this.musicsLoading = true;

        // 搜索结果页不放返回按钮：搜索是从左侧导航栏的搜索框直接进入的，右上角这枚返回键在那里
        // 没有对应的"上一页"，只会把内容区右上角挤掉一块。歌单详情、发现页曲目列表照旧保留。
        boolean backButtonAllowed = showBackButton && !playList.isSearchResultList();
        RoundedButtonWidget btnBack = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
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

        double musicsContainerOffsetY;

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
                cover.setRadius(4);
                // 每帧回报落点：接力封面要朝这里飞，而这一帧封面本身让位（纹理供给换成 null，
                // 控件照常跑回调），所以屏幕上任何时刻只有一张封面。
                PlaylistCoverHero.setTarget(this, this.playList, cover.getX(), cover.getY(),
                        cover.getWidth(), cover.getRadius(), null);
            });
            cover.setLocImg(() -> PlaylistCoverHero.coversYield(this) ? null : this.playList.getCoverLocation());

//        LabelWidget lblPlaylistName = new LabelWidget(playList.name, FontManager.pf);
            RoundedButtonWidget btnPlay = new RoundedButtonWidget("播放歌单", FontManager.pf16bold);
            this.addChild(btnPlay);

            btnPlay.setBeforeRenderCallback(() -> {
                btnPlay.setBounds(57, 17);
                btnPlay.setPosition(cover.getRelativeX() + cover.getWidth() + 12, cover.getRelativeY() + cover.getHeight() - btnPlay.getHeight());
                btnPlay.setRadius(3);
                btnPlay.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnPlay.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
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


            RoundedButtonWidget btnCoverflow = new RoundedButtonWidget("Coverflow", FontManager.pf16bold);
            this.addChild(btnCoverflow);

            btnCoverflow.setBeforeRenderCallback(() -> {
                btnCoverflow.setBounds(57, 17);
                btnCoverflow.setPosition(btnPlay.getRelativeX() + btnPlay.getWidth() + 8, cover.getRelativeY() + cover.getHeight() - btnCoverflow.getHeight());
                btnCoverflow.setRadius(3);
                btnCoverflow.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnCoverflow.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            btnCoverflow.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    api.displayScreen(CoverflowOverlay.byPlaylist(playList));
                }

                return true;
            });

            RoundedButtonWidget btnSubscribe = new RoundedButtonWidget(
                    () -> playList.isSubscribed() ? "取消收藏" : "收藏歌单", FontManager.pf16bold);
            this.addChild(btnSubscribe);
            btnSubscribe.setBeforeRenderCallback(() -> {
                boolean canToggleSubscription = canToggleSubscription();
                btnSubscribe.setHidden(!canToggleSubscription);
                btnSubscribe.setBounds(57, 17);
                btnSubscribe.setPosition(btnCoverflow.getRelativeX() + btnCoverflow.getWidth() + 8,
                        cover.getRelativeY() + cover.getHeight() - btnSubscribe.getHeight());
                btnSubscribe.setRadius(3);
                btnSubscribe.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnSubscribe.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
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
            RoundedButtonWidget btnRename = new RoundedButtonWidget("重命名", FontManager.pf16bold);
            this.addChild(btnRename);
            btnRename.setBeforeRenderCallback(() -> {
                boolean local = playList != null && playList.isLocalPlaylist();
                btnRename.setBounds(57, 17);
                btnRename.setPosition(btnCoverflow.getRelativeX() + btnCoverflow.getWidth() + 8,
                        cover.getRelativeY() + cover.getHeight() - btnRename.getHeight());
                btnRename.setRadius(3);
                btnRename.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnRename.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                btnRename.setHidden(!local);
            });
            btnRename.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0) return false;
                NCMScreen.getInstance().openPlaylistRename(playList);
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
                                .setAlpha(.6f)
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
                lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            LabelWidget lblPlaylistInfo = new LabelWidget(this::getPlayListInfo, FontManager.pf12);
            this.addChild(lblPlaylistInfo);

            lblPlaylistInfo.setBeforeRenderCallback(() -> {
                double creatorTop = creatorAvatar == null
                        ? lblCreator.getRelativeY()
                        : creatorAvatar.getRelativeY();
                lblPlaylistInfo.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                        creatorTop - 8 - lblPlaylistInfo.getHeight());
                lblPlaylistInfo.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            });
            LabelWidget lblPlaylistName = new LabelWidget(playList.getName(), FontManager.pf32);
            this.addChild(lblPlaylistName);

            lblPlaylistName.setBeforeRenderCallback(() -> {
                lblPlaylistName.setPosition(cover.getRelativeX() + cover.getWidth() + 12, lblPlaylistInfo.getRelativeY() - 4 - lblPlaylistName.getHeight());
                lblPlaylistName.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            musicsContainerOffsetY = cover.getRelativeY() + cover.getHeight() + 24;
        } else {
            musicsContainerOffsetY = 18;
        }

        Panel rwMusicsContainer = new Panel();
        final double bottomSafeInset = 8.0;

        this.addChild(rwMusicsContainer);

        rwMusicsContainer.setBeforeRenderCallback(() -> {
            // 让最后一个可见行与控制栏保持间距。嵌套的 ScrollPanel
            // 将歌曲行裁剪到这个缩小后的视口，而不是让文字贴边。
            rwMusicsContainer.setBounds(
                    Math.max(0.0, this.getWidth() - 36),
                    Math.max(0.0, this.getHeight() - musicsContainerOffsetY - bottomSafeInset)
            );
            rwMusicsContainer.centerHorizontally();
            rwMusicsContainer.setPosition(rwMusicsContainer.getRelativeX(), musicsContainerOffsetY);
        });

        ScrollPanel musicsPanel = new ScrollPanel();

        rwMusicsContainer.addChild(musicsPanel);
        musicsPanel.setSpacing(0);

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
            // 不要对每首歌都调用 List#indexOf：那是 O(n²)，对大型
            // 播放列表会明显变慢。改为一次性构建一批子控件。
            List<AbstractWidget<?>> musicWidgets = new ArrayList<>(musics == null ? 0 : musics.size());
            if (musics != null) {
                for (int index = 0; index < musics.size(); index++) {
                    MusicWidget musicWidget = new MusicWidget(musics.get(index), playList, index);
                    musicWidget.setShouldOverrideMouseCursor(true);
                    musicWidgets.add(musicWidget);
                }
            }

            // 建完再挂进场：超大歌单不该把淡入时间耗在构建上。挂上的是发号器而不是起始时刻 ——
            // 每一行在自己第一次被渲染（= 刚进视口）的那一帧才领号。
            this.revealQueue.restart();
            for (AbstractWidget<?> musicWidget : musicWidgets) {
                ((MusicWidget) musicWidget).setEntranceAnimation(this.revealQueue);
            }
            musicsPanel.addChild(musicWidgets);
            // 空白的列表区看上去像没加载出来。本地单新建出来就是空的，这一句是它唯一的说明。
            if (musicWidgets.isEmpty() && playList.isLocalPlaylist()) {
                LabelWidget empty = new LabelWidget("这张单还是空的 · 到任意列表点歌曲行右侧的「＋」，选它就能收进来",
                        FontManager.pf14bold);
                empty.setClickable(false);
                empty.setBeforeRenderCallback(() -> {
                    empty.setBounds(Math.max(1, musicsPanel.getWidth()), 28);
                    empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                    empty.setPosition(10, 7);
                });
                musicsPanel.addChild(empty);
            }
            musicsLoading = false;
        });

        if (this.tfSearch != null) {
            this.tfSearch.setTextChangedCallback(text -> {
                if (text.isEmpty()) {
                    musicsPanel.getChildren().forEach(child -> child.setHidden(false));
                } else {
                    musicsPanel.getChildren()
                            .stream()
                            .filter(child -> child instanceof MusicWidget)
                            .map(child -> (MusicWidget) child)
                            .forEach(widget -> {
                                    if (
                                            widget.music.getName().toLowerCase().contains(text.toLowerCase()) ||
                                            widget.music.getTranslatedNames().toLowerCase().contains(text.toLowerCase()) ||
                                            widget.music.getArtists().stream().anyMatch(artist -> artist != null && artist.getName() != null && artist.getName().toLowerCase().contains(text.toLowerCase())) ||
                                            (widget.music.getAlbum() != null && widget.music.getAlbum().getName() != null && widget.music.getAlbum().getName().toLowerCase().contains(text.toLowerCase()))
                                    ) {
                                        widget.setHidden(false);
                                    } else {
                                        widget.setHidden(true);
                                    }
                            });
                }

            });
        }
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
