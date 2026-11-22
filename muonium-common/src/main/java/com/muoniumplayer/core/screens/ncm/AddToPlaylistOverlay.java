package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.BodianMusicService;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * 加入歌单弹窗：展示请求中的状态，并根据网易云接口的 HTTP 状态与业务 code 给出真实结果反馈。
 */
public class AddToPlaylistOverlay extends NCMPanel {

    private static final double ROW_HEIGHT = 72.0;
    private static final double ROW_GAP = 8.0;

    public Music music;
    /** 接力板起点由 NCMScreen 从被点击的列表控件传入。 */
    private SheetHeroRelay.Origin heroOrigin;
    private LabelWidget heroTarget;
    private Panel dialog;
    private boolean closing = false;
    /** 每个实例只触发一次懒加载；未接受的回调由加载状态观察补上，只重铺列表、不重开弹层。 */
    private boolean kuGouCallbackPending = false;
    private boolean bodianCallbackPending = false;
    /** 同一弹层只请求一次；纹理由 Textures 的全局键去重与主线程上传队列拥有。 */
    private final java.util.Set<Location> coverRequests = new java.util.HashSet<>();
    /** 进出场那一条时钟，和 {@code NcmSheet} 那六个浮层共用同一个 {@link SheetClock}。 */
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final SheetClock clock = new SheetClock();
    private double presentation;
    private boolean submitting = false;
    private PlayList selectedPlaylist;
    private String statusMessage = "";
    private FeedbackState feedbackState = FeedbackState.IDLE;
    private long closeAfterMillis = -1L;
    private long feedbackStartedNanos;
    private final NcmPromptMotion feedbackMotion = new NcmPromptMotion(0);
    private double feedbackPresentation;
    private Panel feedbackPanel;
    private String[] feedbackLines = new String[0];
    private String wrappedFeedback = "";
    private double wrappedFeedbackWidth = -1;
    private long wrappedFeedbackRevision = -1;
    private static final long RESULT_HOLD_MILLIS = 1400L;
    private long requestSequence;
    private long hostGeneration;
    private boolean hostGenerationCaptured;
    private final NcmPromptMotion heightMotion = new NcmPromptMotion(640);
    private double preferredHeight = 640;
    private String accountSourceLabel = "本地与云端";
    private ScrollPanel playlistList;
    private boolean observedKuGouLoading;
    private boolean observedKuGouLoaded;
    private boolean observedBodianLoading;
    private boolean observedBodianLoaded;
    private List<PlayList> displayedPlaylists = new ArrayList<>();

    private enum FeedbackState {
        IDLE,
        PROCESSING,
        SUCCESS,
        ALREADY_EXISTS,
        ERROR
    }

    public AddToPlaylistOverlay(Music music) {
        this.music = music;
    }

    /** 正在退场：还画得着，但 {@link NCMPanel} 会把它这一帧的输入整个吞掉。 */
    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public boolean shouldClose() {
        // 退场播完才回收：closing 一置位下一帧就把这一层抹掉，等于没有退场动画。
        return clock.isGone();
    }

    /** @param from 侧栏/列表里那枚「+」在按下那一刻的矩形；由 {@code NCMScreen#openAddToPlaylist} 转交。 */
    public void claimHero(SheetHeroRelay.Origin from) {
        if (from != null && this.heroOrigin == null) this.heroOrigin = from;
    }

    private void requestPlaylistCover(PlayList playlist) {
        if (playlist == null || LocalPlaylistStore.isLocal(playlist)) return;
        String coverUrl = playlist.getCoverUrl();
        if (coverUrl == null || coverUrl.trim().isEmpty()) return;
        Location location = playlist.getCoverLocation();
        if (TextureManager.getInstance().getTexture(location) != null || !coverRequests.add(location)) return;
        // 与歌单详情页使用相同的地址规则和纹理键，首次打开选择器也能主动加载。
        String secureUrl = coverUrl.replace("http://", "https://");
        String requestUrl = playlist.getPlatform() == MusicPlatform.QQ
                ? secureUrl : secureUrl + (secureUrl.contains("?") ? "&" : "?") + "param=256y256";
        Textures.downloadTextureAndLoadAsync(requestUrl, location);
    }

    /**
     * 飞行板画在所有子组件之后，好让它起飞时压得住自己那层遮罩。
     *
     * <p>这一层没有「一张卡」可以落，落点是顶部那行歌名，所以起飞与淡出都跟整块弹窗同一条
     * 进度 —— 不像 {@code NcmSheet} 那样和某张卡自己的入场进度互补。</p>
     */
    @Override
    protected void afterRenderChildren(double mouseX, double mouseY) {
        super.afterRenderChildren(mouseX, mouseY);
        if (heroOrigin == null || heroTarget == null || Math.abs(layout.scale() - 1) > .001) return;
        if (playlistList != null && !SheetHeroRelay.fullyInside(playlistList, heroTarget.getX(), heroTarget.getY(),
                heroTarget.getWidth(), heroTarget.getHeight())) return;
        double ink = NcmSheet.labelInkHeight(heroTarget);
        SheetHeroRelay.render(heroOrigin, heroTarget.getX() - 6, heroTarget.getY() + (heroTarget.getHeight() - ink) * .5 - 4,
                Math.max(1, heroTarget.getWidth()) + 12, ink + 8, 5.0, presentation, presentation);
    }

    /** Close requests share the same dismiss clock. */
    void requestClose() {
        if (closing) return;
        closing = true;
        clock.requestDismiss();
    }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        layout.render(this, mouseX, mouseY, dWheel, (x, y, wheel) -> {
            super.renderWidget(x, y, 0);
            if (wheel != 0 && !isDismissing()) super.onDWheelReceived(x, y, wheel);
        });
    }

    @Override
    public void onMouseClickReceived(double mouseX, double mouseY, int button) {
        layout.dispatch(this, mouseX, mouseY, button, (x, y, input) -> super.onMouseClickReceived(x, y, input));
    }

    @Override
    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        layout.dispatch(this, mouseX, mouseY, dWheel, (x, y, input) -> super.onDWheelReceived(x, y, input));
    }

    @Override
    public void onInit() {
        setBeforeRenderCallback(() -> {
            presentation = TransitionCurveCache.MOTION.at(clock.tick());
            long now = System.nanoTime();
            if (!hostGenerationCaptured) {
                hostGeneration = NCMScreen.getInstance().getContentHostGeneration();
                hostGenerationCaptured = true;
            } else if (hostGeneration != NCMScreen.getInstance().getContentHostGeneration()) requestClose();
            preferredHeight = heightMotion.update(feedbackState == FeedbackState.IDLE ? 640 : 550, 65, now);
            feedbackPresentation = feedbackMotion.update(feedbackState == FeedbackState.IDLE ? 0 : 1, 55, now);
            if (!submitting && closeAfterMillis > 0L && now >= closeAfterMillis) requestClose();
            if (playlistList != null) playlistList.setHidden(feedbackState != FeedbackState.IDLE);
            if (feedbackPanel != null) feedbackPanel.setHidden(feedbackState == FeedbackState.IDLE);
            if (!closing && music != null && music.isKuGou()) {
                boolean loading = KuGouMusicService.areUserPlaylistsLoading();
                boolean loaded = KuGouMusicService.areUserPlaylistsLoaded();
                accountSourceLabel = loading ? "正在加载酷狗歌单" : "本地 · 酷狗";
                if (loading != observedKuGouLoading || loaded != observedKuGouLoaded) {
                    observedKuGouLoading = loading;
                    observedKuGouLoaded = loaded;
                    rebuildPlaylistList();
                }
            }
            if (!closing && music != null && music.isBodian()) {
                boolean loading = BodianMusicService.areUserPlaylistsLoading();
                boolean loaded = BodianMusicService.areUserPlaylistsLoaded();
                accountSourceLabel = loading ? "正在加载波点歌单" : "本地 · 波点音乐";
                if (loading != observedBodianLoading || loaded != observedBodianLoaded) {
                    observedBodianLoading = loading;
                    observedBodianLoaded = loaded;
                    rebuildPlaylistList();
                }
            }
        });
        dialog = NcmExpressive.shell(this, 620, () -> preferredHeight,
                () -> presentation, this::requestClose);
        NcmExpressive.header(dialog, PartyIcons.QUEUE_MUSIC, () -> "收藏音乐", () -> "添加到歌单", this::requestClose);
        if (music != null) CloudMusic.loadMusicCover(music);
        playlistList = new ScrollPanel();
        dialog.addChild(playlistList);
        playlistList.setSpacing(ROW_GAP).setScrollStrength(36).setAlignment(ScrollPanel.Alignment.VERTICAL);
        playlistList.setBeforeRenderCallback(() -> {
            double top = NcmExpressive.headerHeight(dialog) + 14;
            playlistList.setBounds(Math.max(1, dialog.getWidth() - 48),
                    Math.max(1, dialog.getHeight() - top - 88));
            playlistList.setPosition(24, top);
            for (AbstractWidget<?> row : playlistList.getChildren()) {
                row.setBounds(playlistList.getWidth(), row.getHeight());
            }
        });
        buildFeedbackPanel();
        NcmExpressive.label(dialog, () -> submitting ? "正在请求平台，请稍候" : feedbackState == FeedbackState.ERROR
                        ? "未完成的操作不会显示为成功" : "本地歌单支持跨音源收藏", NcmExpressive.captionFont(),
                () -> 24, () -> dialog.getHeight() - 49, () -> Math.max(1, dialog.getWidth() - 212),
                () -> NcmExpressivePalette.live().muted());
        NcmExpressive.action(dialog, null, () -> submitting ? "稍后查看" : feedbackState == FeedbackState.SUCCESS
                        || feedbackState == FeedbackState.ALREADY_EXISTS ? "完成" : "取消", NcmExpressive.ActionStyle.NEUTRAL,
                () -> Math.max(24, dialog.getWidth() - 158), () -> dialog.getHeight() - 74,
                () -> Math.min(134, Math.max(1, dialog.getWidth() - 48)), () -> NcmExpressive.BUTTON_HEIGHT,
                this::requestClose);
        rebuildPlaylistList();
        if (music != null && music.isKuGou() && !KuGouMusicService.areUserPlaylistsLoaded() && !kuGouCallbackPending) {
            kuGouCallbackPending = true;
            KuGouMusicService.ensureUserPlaylistsAsync(() -> MultiThreadingUtil.runOnMainThread(() -> {
                    if (!closing && canPresentResult()) rebuildPlaylistList();
            }));
        }
        if (music != null && music.isBodian() && !BodianMusicService.areUserPlaylistsLoaded()
                && !bodianCallbackPending) {
            bodianCallbackPending = true;
            BodianMusicService.ensureUserPlaylistsAsync(() -> MultiThreadingUtil.runOnMainThread(() -> {
                if (!closing && canPresentResult()) rebuildPlaylistList();
            }));
        }
    }

    private void buildFeedbackPanel() {
        feedbackPanel = new Panel();
        dialog.addChild(feedbackPanel);
        feedbackPanel.setBeforeRenderCallback(() -> {
            double top = NcmExpressive.headerHeight(dialog) + 12;
            feedbackPanel.setBounds(Math.max(1, dialog.getWidth() - 48),
                    Math.max(1, dialog.getHeight() - top - 86));
            feedbackPanel.setPosition(24, top + (1 - feedbackPresentation) * 8);
            feedbackPanel.setAlpha((float) feedbackPresentation);
            wrapFeedback(Math.max(1, feedbackPanel.getWidth() - 40));
        });
        NcmExpressive.surface(feedbackPanel, 0, 0, feedbackPanel::getWidth, feedbackPanel::getHeight,
                22, () -> NcmExpressivePalette.mix(NcmExpressivePalette.live().container(), getStatusColor(), .08));
        ScrollPanel feedbackViewport = new ScrollPanel();
        feedbackPanel.addChild(feedbackViewport);
        feedbackViewport.setScrollStrength(28).setBeforeRenderCallback(() -> {
            feedbackViewport.setBounds(feedbackPanel.getWidth(), Math.max(1, feedbackPanel.getHeight() -
                    (feedbackState == FeedbackState.ERROR ? 78 : 14)));
            feedbackViewport.setPosition(0, 0);
        });
        Panel feedbackContent = new Panel();
        feedbackContent.setBounds(1, 260);
        feedbackViewport.addChild(feedbackContent);
        feedbackContent.setBeforeRenderCallback(() -> feedbackContent.setBounds(feedbackPanel.getWidth(),
                Math.max(260, 160 + feedbackLines.length * 21 + 28)));
        NcmExpressive.loading(feedbackContent, () -> feedbackContent.getWidth() * .5, () -> 62,
                () -> submitting ? 56 : 0, () -> NcmExpressivePalette.live().primary());
        NcmExpressive.surface(feedbackContent, () -> feedbackContent.getWidth() * .5 - 36, () -> 26,
                () -> terminalSuccess() ? 72 : 0, () -> terminalSuccess() ? 72 : 0, 24,
                () -> NcmExpressivePalette.mix(NcmExpressivePalette.live().container(), getStatusColor(), .18));
        NcmExpressive.successMark(feedbackContent, () -> feedbackContent.getWidth() * .5, () -> 62,
                () -> terminalSuccess() ? 42 : 0,
                () -> NcmPromptMotion.reveal(feedbackStartedNanos, System.nanoTime(), 380), this::getStatusColor);
        NcmExpressive.icon(feedbackContent, () -> feedbackState == FeedbackState.ERROR ? PartyIcons.WARNING : "",
                NcmSheet.ICON_XL, () -> feedbackContent.getWidth() * .5, () -> 62, this::getStatusColor);
        NcmExpressive.centered(feedbackContent, this::feedbackTitle, NcmExpressive.dialogTitleFont(),
                () -> feedbackContent.getWidth() * .5, () -> 123, () -> feedbackContent.getWidth() - 32,
                () -> NcmExpressivePalette.live().text());
        Panel details = new Panel();
        feedbackContent.addChild(details);
        details.setClickable(false).setBeforeRenderCallback(() -> {
            details.setPosition(20, 148);
            details.setBounds(Math.max(1, feedbackContent.getWidth() - 40), feedbackLines.length * 21);
            while (details.getChildren().size() < feedbackLines.length) {
                final int line = details.getChildren().size();
                NcmExpressive.centered(details, () -> line < feedbackLines.length ? feedbackLines[line] : "",
                        NcmExpressive.captionFont(), () -> details.getWidth() * .5, () -> 12 + line * 21,
                        details::getWidth, () -> NcmExpressivePalette.live().muted());
            }
        });
        NcmExpressive.progress(feedbackPanel, () -> 24, () -> feedbackPanel.getHeight() - 24,
                () -> submitting ? Math.max(0, feedbackPanel.getWidth() - 48) : 0, () -> -1, () -> submitting);
        NcmExpressive.action(feedbackPanel, PartyIcons.SYNC, () -> "重试加入", NcmExpressive.ActionStyle.PRIMARY,
                () -> Math.max(16, feedbackPanel.getWidth() * .5 - 104), () -> feedbackPanel.getHeight() - 68,
                () -> feedbackState == FeedbackState.ERROR && selectedPlaylist != null ? Math.min(208, feedbackPanel.getWidth() - 32) : 0,
                () -> feedbackState == FeedbackState.ERROR ? 44 : 0, () -> submitToPlaylist(selectedPlaylist));
    }

    private boolean terminalSuccess() {
        return feedbackState == FeedbackState.SUCCESS || feedbackState == FeedbackState.ALREADY_EXISTS;
    }

    private String feedbackTitle() {
        if (submitting) return "正在加入歌单";
        if (feedbackState == FeedbackState.SUCCESS) return "已加入歌单";
        if (feedbackState == FeedbackState.ALREADY_EXISTS) return "歌单中已存在";
        return "这次没有加入成功";
    }

    private void wrapFeedback(double width) {
        long revision = NcmExpressive.captionFont() == null ? -1 : NcmExpressive.captionFont().metricsRevision();
        if (!statusMessage.equals(wrappedFeedback) || Math.abs(width - wrappedFeedbackWidth) > .5 || revision != wrappedFeedbackRevision) {
            wrappedFeedback = statusMessage;
            wrappedFeedbackWidth = width;
            wrappedFeedbackRevision = revision;
            feedbackLines = NcmExpressive.wrap(NcmExpressive.captionFont(), statusMessage, width);
        }
    }

    private void rebuildPlaylistList() {
        if (closing || playlistList == null) return;
        List<PlayList> available = playListsForSource();
        if (available == null) available = new ArrayList<>();
        if (samePlaylistRows(available) && !playlistList.getChildren().isEmpty()) return;
        displayedPlaylists = new ArrayList<>(available);
        playlistList.getChildren().clear();
        addTrackPreview();
        if (available.isEmpty()) {
            Panel empty = new Panel();
            empty.setBounds(1, 120);
            playlistList.addChild(empty);
            empty.setBeforeRenderCallback(() -> empty.setBounds(Math.max(1, playlistList.getWidth()), 120));
            NcmExpressive.loading(empty, () -> empty.getWidth() * .5, () -> 34,
                    () -> isAccountPlaylistLoading() ? 36 : 0,
                    () -> NcmExpressivePalette.live().primary());
            NcmExpressive.centered(empty, () -> isAccountPlaylistLoading() ? "正在获取你的歌单" : "暂无可用歌单",
                    NcmExpressive.labelFont(), () -> empty.getWidth() * .5, () -> 80,
                    () -> empty.getWidth() - 24, () -> NcmExpressivePalette.live().muted());
        } else {
            for (PlayList playlist : available) {
                if (playlist != null) playlistList.addChild(new PlaylistRow(playlist));
            }
        }
        // 这些行在渲染回调中量尺寸，不能开启依赖显式失效的布局快路径。
    }

    private boolean isAccountPlaylistLoading() {
        return music != null && ((music.isKuGou() && KuGouMusicService.areUserPlaylistsLoading())
                || (music.isBodian() && BodianMusicService.areUserPlaylistsLoading()));
    }

    private void addTrackPreview() {
        Panel preview = new Panel();
        preview.setBounds(1, 82);
        playlistList.addChild(preview);
        preview.setBeforeRenderCallback(() -> preview.setBounds(Math.max(1, playlistList.getWidth()), 82));
        NcmExpressive.surface(preview, 0, 0, preview::getWidth, preview::getHeight,
                NcmExpressive.CARD_RADIUS, () -> NcmExpressivePalette.live().container());
        heroTarget = NcmExpressive.label(preview, () -> music == null ? "" : music.getName(), NcmExpressive.labelFont(),
                () -> 88, () -> 28, () -> preview.getWidth() - 112, () -> NcmExpressivePalette.live().containerInk());
        NcmExpressive.label(preview, () -> music == null ? "" : music.getArtistsName(), NcmExpressive.captionFont(),
                () -> 88, () -> 54, () -> preview.getWidth() - 112, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.surface(preview, 14, 14, () -> 54, () -> 54, 12,
                () -> NcmExpressivePalette.live().primaryContainer());
        NcmExpressive.icon(preview, PartyIcons.NOTE, NcmSheet.ICON_L, () -> 41, () -> 41,
                () -> NcmExpressivePalette.live().primary());
        RoundedImageWidget trackCover = new RoundedImageWidget(
                () -> music == null ? null : music.getSmallCoverLocation(), 14, 14, 54, 54);
        preview.addChild(trackCover);
        trackCover.setClickable(false).setRadius(12).setLinearFilter(true).fadeIn();
        Panel heading = new Panel();
        heading.setBounds(1, 42);
        playlistList.addChild(heading);
        heading.setBeforeRenderCallback(() -> heading.setBounds(Math.max(1, playlistList.getWidth()), 42));
        NcmExpressive.label(heading, () -> "点击目标歌单即可加入", NcmExpressive.labelFont(),
                () -> 0, () -> 21, () -> Math.max(1, heading.getWidth() * .56), () -> NcmExpressivePalette.live().text());
        NcmExpressive.rightAligned(heading, () -> accountSourceLabel, NcmExpressive.captionFont(),
                heading::getWidth, () -> 21, () -> Math.max(1, heading.getWidth() * .40),
                () -> NcmExpressivePalette.live().primary());
    }

    private boolean samePlaylistRows(List<PlayList> available) {
        if (available.size() != displayedPlaylists.size()) return false;
        for (int index = 0; index < available.size(); index++) {
            if (available.get(index) != displayedPlaylists.get(index)) return false;
        }
        return true;
    }

    /**
     * 弹窗该列哪些歌单：本地自建单永远排在最前，后面接当前音源的账号歌单。
     *
     * <p>网易云用 {@code CloudMusic.playLists}（登录时一次性拉全量）；酷狗那边用户歌单是懒加载的，
     * 所以先读快照，没加载过就顺手触发一次，并在回调里重画列表——不然第一次打开弹窗只会看到「暂无可用歌单」。</p>
     *
     * <p>本地单要排在同源限制之外：下面 {@link #submitToPlaylist} 那道闸说的是「提交给某个平台云端的
     * 歌单，曲目 id 必须与该平台的体系一致」，而本地单的曲目存在本机、取流按逐曲的 {@code source}
     * 分支走，所以它是跨源歌曲唯一还能落地的目标 —— 一个没登录任何账号的用户，这里也得有地方放歌。</p>
     */
    private List<PlayList> playListsForSource() {
        List<PlayList> combined = new ArrayList<>(LocalPlaylistStore.list());
        accountSourceLabel = "仅本地歌单";
        if (music != null && music.isKuGou()) {
            accountSourceLabel = KuGouMusicService.areUserPlaylistsLoading() ? "正在加载酷狗歌单" : "本地 · 酷狗";
            combined.addAll(KuGouMusicService.getUserPlaylistsSnapshot());
        } else if (music != null && music.isNetease()) {
            accountSourceLabel = "本地 · 网易云";
            if (CloudMusic.playLists != null) {
                for (PlayList playlist : CloudMusic.playLists) {
                    if (playlist != null && playlist.getPlatform() == MusicPlatform.NETEASE) combined.add(playlist);
                }
            }
        } else if (music != null && music.isBodian()) {
            accountSourceLabel = BodianMusicService.areUserPlaylistsLoading()
                    ? "正在加载波点歌单" : "本地 · 波点音乐";
            combined.addAll(BodianMusicService.getUserPlaylistsSnapshot());
        }
        return combined;
    }

    private void submitToPlaylist(PlayList playlist) {
        if (closing || submitting || terminalSuccess() || music == null || playlist == null) {
            return;
        }

        selectedPlaylist = playlist;
        if (!hostGenerationCaptured) {
            hostGeneration = NCMScreen.getInstance().getContentHostGeneration();
            hostGenerationCaptured = true;
        }
        final String playlistName = getPlaylistName(playlist);
        // 本地单先走：它没有任何上游，也就没有「同源」这回事。
        if (playlist.isLocalPlaylist()) {
            submitToLocal(playlist, playlistName);
            return;
        }
        // 曲目与目标歌单必须是同一个音源：两边的曲目 id 体系不同，跨源提交只会写进一首错误的歌。
        boolean sameSource = (music.isNetease() && playlist.getPlatform() == MusicPlatform.NETEASE)
                || (music.isQQ() && playlist.getPlatform() == MusicPlatform.QQ)
                || (music.isKuGou() && playlist.getPlatform() == MusicPlatform.KUGOU)
                || (music.isBodian() && playlist.getPlatform() == MusicPlatform.BODIAN);
        if (!sameSource) {
            submitting = false;
            closeAfterMillis = -1L;
            showStatus("仅支持把同源歌曲加入同源歌单", FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, "歌曲与歌单不是同一个音源");
            return;
        }

        if (music.isKuGou()) {
            submitKuGou(playlist, playlistName);
            return;
        }
        if (music.isQQ()) {
            submitQQ(playlist, playlistName);
            return;
        }

        final long musicId = music.getId();
        if (musicId <= 0L && !music.isBodian()) {
            submitting = false;
            closeAfterMillis = -1L;
            showStatus("歌曲 ID 无效，无法加入歌单", FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, "歌曲 ID 无效");
            return;
        }

        final long operation = ++requestSequence;
        if (!hostGenerationCaptured) {
            hostGeneration = NCMScreen.getInstance().getContentHostGeneration();
            hostGenerationCaptured = true;
        }
        submitting = true;
        closeAfterMillis = -1L;
        showStatus("正在加入「" + playlistName + "」…", FeedbackState.PROCESSING);
        DownloadDynamicIsland.showPlaylistTrackAddInProgress(playlistName);

        MultiThreadingUtil.runAsync(() -> {
            CloudMusicApi.PlaylistTrackOperationResult result;
            try {
                result = music.isBodian()
                        ? playlist.addToListWithResult(music.getSourceId())
                        : playlist.addToListWithResult(musicId);
            } catch (Throwable ignored) {
                result = new CloudMusicApi.PlaylistTrackOperationResult(
                        false, false, 502, -1, "请求发生异常，请稍后重试"
                );
            }

            final CloudMusicApi.PlaylistTrackOperationResult finalResult = result;
            MultiThreadingUtil.runOnMainThread(() -> handleSubmitResult(operation, playlist, finalResult));
        });

        // 请求在后台执行；保留本层等待真实验证结果，不提前播放成功动画。
    }

    /** 本地布尔返回值不能区分重复和失效目标；不把所有 false 都伪装成已存在。 */
    private void submitToLocal(PlayList playlist, String playlistName) {
        closeAfterMillis = -1L;
        if (LocalPlaylistStore.addTrack(playlist, music)) {
            showStatus("已添加到本地歌单「" + playlistName + "」", FeedbackState.SUCCESS);
            DownloadDynamicIsland.showPlaylistTrackAddSuccess(playlistName);
        } else {
            showStatus("未添加：歌曲可能已存在，或这张本地歌单已不可用。", FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, "可能已存在或歌单不可用");
        }
        NCMScreen.getInstance().markDirty();
        // 本地操作立即给出真实结果，不人为制造联网等待。
    }

    /**
     * 酷狗分支：加歌走 {@code cloudlist.service/v6/add_song}。
     *
     * <p>结果一律按「提交成功 / 失败」处理并复用同一个结果回调：酷狗这个端点只回一个
     * {@code status}，没有网易云那种「已存在」的独立语义，所以
     * {@code alreadyExists} 恒为 false。</p>
     */
    private void submitKuGou(PlayList playlist, String playlistName) {
        final long operation = ++requestSequence;
        submitting = true;
        closeAfterMillis = -1L;
        showStatus("正在加入「" + playlistName + "」…", FeedbackState.PROCESSING);
        DownloadDynamicIsland.showPlaylistTrackAddInProgress(playlistName);

        final Music target = music;
        MultiThreadingUtil.runAsync(() -> {
            boolean ok;
            try {
                ok = KuGouMusicService.addTrackToPlaylist(playlist.getPlatformPlaylistId(), target);
            } catch (Throwable ignored) {
                ok = false;
            }
            final boolean finalOk = ok;
            MultiThreadingUtil.runOnMainThread(() -> handleKuGouResult(operation, playlist, finalOk));
        });
    }

    private void submitQQ(PlayList playlist, String playlistName) {
        final long operation = ++requestSequence;
        submitting = true;
        closeAfterMillis = -1L;
        showStatus("正在加入「" + playlistName + "」…", FeedbackState.PROCESSING);
        DownloadDynamicIsland.showPlaylistTrackAddInProgress(playlistName);
        final Music target = music;
        MultiThreadingUtil.runAsync(() -> {
            boolean accepted;
            try {
                accepted = NeteaseMusicService.updateQQPlaylistTrack(
                        playlist.getPlatformPlaylistId(), target, true);
            } catch (Throwable throwable) {
                accepted = false;
                System.err.println("[Music/QQ] Playlist add failed: " + throwable.getMessage());
            }
            final boolean finalAccepted = accepted;
            MultiThreadingUtil.runOnMainThread(() -> handleQQResult(operation, playlist, finalAccepted));
        });
    }

    private void handleQQResult(long operation, PlayList playlist, boolean accepted) {
        if (operation != requestSequence || playlist != selectedPlaylist) return;
        submitting = false;
        String name = getPlaylistName(playlist);
        if (accepted) {
            DownloadDynamicIsland.showPlaylistTrackAddSuccess(name);
            if (canPresentResult()) showStatus("QQ 音乐已确认加入「" + name + "」", FeedbackState.SUCCESS);
        } else {
            DownloadDynamicIsland.showPlaylistTrackAddFailure(name, "QQ 音乐未确认歌单变更");
            if (canPresentResult()) showStatus("QQ 音乐未确认加入成功，请检查登录状态或歌单权限。", FeedbackState.ERROR);
        }
    }

    private void handleSubmitResult(long operation, PlayList playlist, CloudMusicApi.PlaylistTrackOperationResult result) {
        if (operation != requestSequence || playlist != selectedPlaylist) return;
        submitting = false;
        String playlistName = getPlaylistName(playlist);
        if (result != null && result.isAlreadyExists() && result.isVerified()) {
            DownloadDynamicIsland.showPlaylistTrackAlreadyExists(playlistName);
            if (canPresentResult()) showStatus("已确认「" + playlistName + "」中已有这首歌。", FeedbackState.ALREADY_EXISTS);
        } else if (result != null && result.isSuccess() && result.isVerified()) {
            DownloadDynamicIsland.showPlaylistTrackAddSuccess(playlistName);
            if (canPresentResult()) showStatus("网易云已确认加入「" + playlistName + "」", FeedbackState.SUCCESS);
        } else {
            String message = result == null ? null : result.getMessage();
            if (message == null || message.trim().isEmpty()) message = "服务端未确认加入成功，请重试";
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, message);
            if (canPresentResult()) showStatus("加入失败：" + message, FeedbackState.ERROR);
        }
    }

    private void handleKuGouResult(long operation, PlayList playlist, boolean accepted) {
        if (operation != requestSequence || playlist != selectedPlaylist) return;
        submitting = false;
        String name = getPlaylistName(playlist);
        if (accepted) {
            DownloadDynamicIsland.showPlaylistTrackAddSuccess(name);
            if (canPresentResult()) showStatus("酷狗接口返回成功，已加入「" + name + "」", FeedbackState.SUCCESS);
        } else {
            DownloadDynamicIsland.showPlaylistTrackAddFailure(name, "酷狗接口未返回成功");
            if (canPresentResult()) showStatus("酷狗未确认成功，请检查网络或登录状态后重试。", FeedbackState.ERROR);
        }
    }

    private boolean canPresentResult() {
        NCMScreen host = NCMScreen.getInstance();
        return !closing && (!hostGenerationCaptured || hostGeneration == host.getContentHostGeneration())
                && (host.addToPlaylistOverlay == null || host.addToPlaylistOverlay == this);
    }

    private static final int STATUS_PROCESSING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_ERROR = 2;
    private static final int STATUS_EXISTS = 3;
    private static final int STATUS_SOURCE = 4;

    private void showStatus(String message, FeedbackState state) {
        statusMessage = message == null ? "" : message;
        feedbackState = state == null ? FeedbackState.IDLE : state;
        feedbackStartedNanos = System.nanoTime();
        closeAfterMillis = terminalSuccess() ? feedbackStartedNanos + RESULT_HOLD_MILLIS * 1_000_000L : -1L;
    }

    private int getStatusColor() {
        switch (getStatusTone()) {
            case STATUS_SUCCESS:
            case STATUS_EXISTS:
                return NcmExpressivePalette.live().success();
            case STATUS_ERROR:
                return NcmExpressivePalette.live().error();
            case STATUS_PROCESSING:
                return NcmExpressivePalette.live().primary();
            default:
                return NcmExpressivePalette.live().muted();
        }
    }

    private String getStatusGlyph() {
        switch (getStatusTone()) {
            case STATUS_SUCCESS:
            case STATUS_EXISTS:
                return PartyIcons.CHECK;
            case STATUS_ERROR:
                return PartyIcons.CLOSE;
            case STATUS_PROCESSING:
                return PartyIcons.HOURGLASS;
            default:
                return PartyIcons.INFO;
        }
    }

    private String getPlaylistName(PlayList playlist) {
        if (playlist == null || playlist.getName() == null || playlist.getName().trim().isEmpty()) return "未命名歌单";
        return playlist.getName();
    }

    private class PlaylistRow extends Panel {
        PlaylistRow(PlayList playlist) {
            setBounds(1, ROW_HEIGHT);
            setShouldOverrideMouseCursor(true);
            requestPlaylistCover(playlist);
            NcmExpressive.surface(this, 0, 0, this::getWidth, this::getHeight, NcmExpressive.CARD_RADIUS,
                    () -> isHovering() ? NcmExpressivePalette.live().primaryContainer() : NcmExpressivePalette.live().container());
            NcmExpressive.surface(this, 12, 10, () -> 52, () -> 52, 12,
                    () -> NcmExpressivePalette.live().primaryContainer());
            NcmExpressive.icon(this, LocalPlaylistStore.isLocal(playlist) ? PartyIcons.ALBUM : PartyIcons.QUEUE_MUSIC,
                    NcmSheet.ICON_M, () -> 38, () -> 36, () -> NcmExpressivePalette.live().primary());
            RoundedImageWidget cover = new RoundedImageWidget(playlist.getCoverLocation(), 12, 10, 52, 52);
            addChild(cover);
            cover.setClickable(false).setRadius(12).setLinearFilter(true).fadeIn();
            NcmExpressive.label(this, () -> getPlaylistName(playlist), NcmExpressive.labelFont(),
                    () -> 76, () -> 27, () -> Math.max(1, getWidth() - 140), () -> NcmExpressivePalette.live().text());
            String source = LocalPlaylistStore.isLocal(playlist) ? "本地歌单" : playlist.getPlatform().getDisplayName() + "歌单";
            int count = LocalPlaylistStore.isLocal(playlist) && playlist.musics != null ? playlist.musics.size() : playlist.getCount();
            NcmExpressive.label(this, () -> source + " · " + Math.max(0, count) + " 首", NcmExpressive.captionFont(),
                    () -> 76, () -> 50, () -> Math.max(1, getWidth() - 140), () -> NcmExpressivePalette.live().muted());
            NcmExpressive.hitSurface(this, () -> getWidth() - 54, () -> 17, () -> 38, () -> 38, 19,
                    () -> NcmExpressivePalette.live().primaryContainer(), () -> submitToPlaylist(playlist));
            NcmExpressive.icon(this, PartyIcons.ADD, NcmSheet.ICON_M,
                    () -> getWidth() - 35, () -> 36, () -> NcmExpressivePalette.live().primary());
            setBeforeRenderCallback(() -> setBounds(Math.max(1, getParentWidth()), ROW_HEIGHT));
            setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                submitToPlaylist(playlist);
                return true;
            });
        }
    }

    private int getStatusTone() {
        if (feedbackState == FeedbackState.SUCCESS) return STATUS_SUCCESS;
        if (feedbackState == FeedbackState.ALREADY_EXISTS) return STATUS_EXISTS;
        if (feedbackState == FeedbackState.ERROR) return STATUS_ERROR;
        if (feedbackState == FeedbackState.PROCESSING) return STATUS_PROCESSING;
        return STATUS_SOURCE;
    }
}
