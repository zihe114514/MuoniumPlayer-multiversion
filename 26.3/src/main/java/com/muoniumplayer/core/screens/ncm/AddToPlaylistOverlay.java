package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.List;

/**
 * 加入歌单弹窗：展示请求中的状态，并根据网易云接口的 HTTP 状态与业务 code 给出真实结果反馈。
 */
public class AddToPlaylistOverlay extends NCMPanel {

    private static final int STATUS_PROCESSING_COLOR = 0xC4C9D4;
    private static final int STATUS_SUCCESS_COLOR = 0x53C68C;
    private static final int STATUS_ERROR_COLOR = 0xF1767D;

    public Music music;
    /** 接力板的落点：标题下面那一行「将《歌名》加入歌单」。 */
    private LabelWidget heroTarget;
    private SheetHeroRelay.Origin heroOrigin;
    private boolean closing = false;
    /** 进出场那一条时钟，和 {@code NcmSheet} 那六个浮层共用同一个 {@link SheetClock}。 */
    private final SheetClock clock = new SheetClock();
    /** 遮罩与弹窗共同读的那一档进度（已经过缓动）。 */
    private double presentation;
    private boolean submitting = false;
    private PlayList selectedPlaylist;
    private String statusMessage = "";
    private FeedbackState feedbackState = FeedbackState.IDLE;
    private long closeAfterMillis = -1L;

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

    /**
     * 飞行板画在所有子组件之后，好让它起飞时压得住自己那层遮罩。
     *
     * <p>这一层没有「一张卡」可以落，落点是顶部那行歌名，所以起飞与淡出都跟整块弹窗同一条
     * 进度 —— 不像 {@code NcmSheet} 那样和某张卡自己的入场进度互补。</p>
     */
    @Override
    protected void afterRenderChildren(double mouseX, double mouseY) {
        super.afterRenderChildren(mouseX, mouseY);
        if (heroOrigin == null || heroTarget == null) return;
        double ink = NcmSheet.labelInkHeight(heroTarget);
        SheetHeroRelay.render(heroOrigin, heroTarget.getX() - 6, heroTarget.getY() + (heroTarget.getHeight() - ink) * .5 - 4,
                Math.max(1, heroTarget.getWidth()) + 12, ink + 8, 5.0, presentation, presentation);
    }

    /** 收到关闭请求：置位 closing 让在途请求作废，同时让退场动画开始播。 */
    private void requestClose() {
        this.closing = true;
        clock.requestDismiss();
    }

    @Override
    public void onInit() {
        // 时钟跑在根组件这一格：它先于所有子组件，遮罩和弹窗于是读到同一个值。
        this.setBeforeRenderCallback(() -> {
            presentation = TransitionCurveCache.MOTION.at(clock.tick());
            if (!submitting && closeAfterMillis > 0L && System.currentTimeMillis() >= closeAfterMillis) {
                requestClose();
            }
        });

        // 半透明遮罩只负责弱化下方 UI。关闭逻辑由 overlay 根组件处理，避免吞掉歌单行点击。
        RectWidget mask = new RectWidget();
        this.addChild(mask);
        mask.setColor(0x000000);
        mask.setClickable(false);
        // 原来整层没有任何进度：mask 写死 .45f，弹窗也没有淡入，点「加入歌单」是硬切出一张卡片。
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .45));
        });

        // 提交期间保留弹窗，防止用户误以为请求已结束，也避免离开后无法看到结果。
        this.setOnClickCallback((x, y, button) -> {
            if (button != 0) {
                return false;
            }
            if (submitting) {
                showStatus("正在加入「" + getPlaylistName(selectedPlaylist) + "」…", FeedbackState.PROCESSING);
                return true;
            }
            requestClose();
            return true;
        });

        Panel dialog = new Panel();
        this.addChild(dialog);
        dialog.setBeforeRenderCallback(() -> {
            double w = Math.min(360, this.getWidth() - 80);
            double h = Math.min(440, this.getHeight() - 80);
            dialog.setBounds(w, h);
            dialog.center();
            // 上浮 10 像素落位，和模态确认、主题调色板同一档动作。
            dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1.0 - presentation) * 10.0);
            dialog.setAlpha((float) presentation);
        });

        RoundedRectWidget dialogBg = new RoundedRectWidget();
        dialog.addChild(dialogBg);
        dialogBg.setRadius(6);
        dialogBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        dialogBg.setBeforeRenderCallback(() -> dialogBg.setMargin(0));

        LabelWidget lblTitle = new LabelWidget("加入歌单", FontManager.pf18bold);
        dialog.addChild(lblTitle);
        lblTitle.setClickable(false);
        lblTitle.setBeforeRenderCallback(() -> {
            lblTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            lblTitle.setPosition(14, 12);
        });

        LabelWidget lblHint = new LabelWidget(
                () -> "将「" + (music == null ? "" : music.getName()) + "」加入歌单",
                FontManager.pf12
        );
        dialog.addChild(lblHint);
        this.heroTarget = lblHint;
        lblHint.setClickable(false);
        lblHint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        lblHint.setBeforeRenderCallback(() -> {
            lblHint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblHint.setMaxWidth(dialog.getWidth() - 28);
            lblHint.setPosition(14, lblTitle.getRelativeY() + lblTitle.getHeight() + 4);
        });

        // 状态栏始终保留一行空间，列表不会因为反馈文本出现/消失而跳动。
        LabelWidget lblStatus = new LabelWidget(() -> statusMessage, FontManager.pf12bold);
        dialog.addChild(lblStatus);
        lblStatus.setClickable(false);
        lblStatus.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        lblStatus.setBeforeRenderCallback(() -> {
            lblStatus.setHidden(statusMessage.isEmpty());
            lblStatus.setColor(getStatusColor());
            lblStatus.setMaxWidth(dialog.getWidth() - 28);
            lblStatus.setPosition(14, lblHint.getRelativeY() + lblHint.getHeight() + 5);
        });

        RectWidget divider = new RectWidget();
        dialog.addChild(divider);
        divider.setClickable(false);
        divider.setBeforeRenderCallback(() -> {
            double dividerY = lblHint.getRelativeY() + lblHint.getHeight() + 23;
            divider.setBounds(12, dividerY, dialog.getWidth() - 24, 1);
            divider.setColor(RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .1f));
        });

        ScrollPanel list = new ScrollPanel();
        dialog.addChild(list);
        list.setSpacing(2);
        list.setBeforeRenderCallback(() -> {
            double listY = divider.getRelativeY() + divider.getHeight() + 7;
            list.setBounds(12, listY, dialog.getWidth() - 24, dialog.getHeight() - listY - 12);
        });

        // 歌单来源按曲目所属音源取：酷狗曲目要加到酷狗歌单里，两边的 id 体系不能混。
        List<PlayList> playLists = playListsForSource();
        if (playLists == null || playLists.isEmpty()) {
            LabelWidget empty = new LabelWidget("暂无可用歌单", FontManager.pf14bold);
            list.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> {
                empty.setBounds(list.getWidth(), 28);
                empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                empty.setPosition(10, 7);
            });
            return;
        }

        for (PlayList pl : playLists) {
            list.addChild(new PlaylistRow(pl));
        }
    }

    /**
     * 弹窗该列哪些歌单。
     *
     * <p>网易云用 {@code CloudMusic.playLists}（登录时一次性拉全量）；酷狗那边用户歌单是懒加载的，
     * 所以先读快照，没加载过就顺手触发一次，并在回调里重画列表——不然第一次打开弹窗只会看到「暂无可用歌单」。</p>
     */
    private List<PlayList> playListsForSource() {
        if (music != null && music.isKuGou()) {
            if (!KuGouMusicService.areUserPlaylistsLoaded()) {
                KuGouMusicService.ensureUserPlaylistsAsync(() -> {
                    // 回调在后台线程，重建 UI 必须回主线程。
                    MultiThreadingUtil.runOnMainThread(() -> {
                        if (closing) return;
                        // 列表是构建期一次性铺出来的，没有增量刷新入口，所以只能整体重建：
                        // 先丢掉旧的子组件（包括那个"暂无可用歌单"占位），再跑一遍 onInit。
                        getChildren().clear();
                        onInit();
                    });
                });
            }
            List<PlayList> snapshot = KuGouMusicService.getUserPlaylistsSnapshot();
            return snapshot == null || snapshot.isEmpty() ? null : snapshot;
        }
        return CloudMusic.playLists;
    }

    private void submitToPlaylist(PlayList playlist) {
        if (submitting || music == null || playlist == null) {
            return;
        }

        selectedPlaylist = playlist;
        final String playlistName = getPlaylistName(playlist);
        // 曲目与目标歌单必须是同一个音源：两边的曲目 id 体系不同，跨源提交只会写进一首错误的歌。
        boolean sameSource = (music.isNetease() && playlist.getPlatform() == MusicPlatform.NETEASE)
                || (music.isKuGou() && playlist.getPlatform() == MusicPlatform.KUGOU);
        if (!sameSource) {
            submitting = false;
            closeAfterMillis = -1L;
            showStatus("仅支持把同源歌曲加入同源歌单", FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, "歌曲与歌单不是同一个音源");
            requestClose();
            return;
        }

        if (music.isKuGou()) {
            submitKuGou(playlist, playlistName);
            return;
        }

        final long musicId = music.getId();
        if (musicId <= 0L) {
            submitting = false;
            closeAfterMillis = -1L;
            showStatus("歌曲 ID 无效，无法加入歌单", FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, "歌曲 ID 无效");
            requestClose();
            return;
        }

        submitting = true;
        closeAfterMillis = -1L;
        showStatus("正在加入「" + playlistName + "」…", FeedbackState.PROCESSING);
        DownloadDynamicIsland.showPlaylistTrackAddInProgress(playlistName);

        MultiThreadingUtil.runAsync(() -> {
            CloudMusicApi.PlaylistTrackOperationResult result;
            try {
                result = playlist.addToListWithResult(musicId);
            } catch (Throwable ignored) {
                result = new CloudMusicApi.PlaylistTrackOperationResult(
                        false, false, 502, -1, "请求发生异常，请稍后重试"
                );
            }

            final CloudMusicApi.PlaylistTrackOperationResult finalResult = result;
            MultiThreadingUtil.runOnMainThread(() -> handleSubmitResult(playlist, finalResult));
        });

        // 选择目标歌单即返回播放器页面；网络请求和结果校验仍在后台继续执行。
        requestClose();
    }

    /**
     * 酷狗分支：加歌走 {@code cloudlist.service/v6/add_song}。
     *
     * <p>结果一律按「提交成功 / 失败」处理并复用同一个结果回调：酷狗这个端点只回一个
     * {@code status}，没有网易云那种「已存在」的独立语义，所以
     * {@code alreadyExists} 恒为 false。</p>
     */
    private void submitKuGou(PlayList playlist, String playlistName) {
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
            MultiThreadingUtil.runOnMainThread(() -> handleSubmitResult(playlist,
                    new CloudMusicApi.PlaylistTrackOperationResult(
                            finalOk, false, finalOk ? 200 : 502, -1,
                            finalOk ? "已加入歌单" : "加入歌单失败")));
        });

        requestClose();
    }

    private void handleSubmitResult(PlayList playlist, CloudMusicApi.PlaylistTrackOperationResult result) {
        // 弹窗会在点击后立即关闭，不能把 closing 视为请求过期；仅忽略真正不匹配的请求。
        if (playlist != selectedPlaylist) {
            return;
        }

        submitting = false;
        closeAfterMillis = -1L;
        String playlistName = getPlaylistName(playlist);
        if (result.isAlreadyExists() && result.isVerified()) {
            showStatus("已确认该歌曲已在「" + playlistName + "」中", FeedbackState.ALREADY_EXISTS);
            DownloadDynamicIsland.showPlaylistTrackAlreadyExists(playlistName);
        } else if (result.isSuccess() && result.isVerified()) {
            showStatus("网易云已确认加入「" + playlistName + "」", FeedbackState.SUCCESS);
            DownloadDynamicIsland.showPlaylistTrackAddSuccess(playlistName);
        } else {
            String message = result.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = "服务端未确认加入成功，请重试";
            }
            showStatus("加入失败：" + message, FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, message);
        }
    }

    private void showStatus(String message, FeedbackState state) {
        statusMessage = message == null ? "" : message;
        feedbackState = state == null ? FeedbackState.IDLE : state;
    }

    private int getStatusColor() {
        if (feedbackState == FeedbackState.SUCCESS || feedbackState == FeedbackState.ALREADY_EXISTS) {
            return STATUS_SUCCESS_COLOR;
        }
        if (feedbackState == FeedbackState.ERROR) {
            return STATUS_ERROR_COLOR;
        }
        return STATUS_PROCESSING_COLOR;
    }

    private String getPlaylistName(PlayList playlist) {
        return playlist == null || playlist.getName() == null ? "此歌单" : playlist.getName();
    }

    private class PlaylistRow extends Panel {

        private final PlayList playlist;

        PlaylistRow(PlayList playlist) {
            this.playlist = playlist;
            this.setShouldOverrideMouseCursor(true);

            RoundedRectWidget bg = new RoundedRectWidget();
            this.addChild(bg);
            bg.setClickable(false);
            bg.setRadius(3);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            bg.setBeforeRenderCallback(() -> bg.setMargin(0));

            RoundedRectWidget hover = new RoundedRectWidget();
            this.addChild(hover);
            hover.setAlpha(0f);
            hover.setClickable(false);
            hover.setRadius(3);
            hover.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
            hover.setBeforeRenderCallback(() -> hover.setMargin(0));

            LabelWidget lbl = new LabelWidget(playlist.getName(), FontManager.pf14bold);
            this.addChild(lbl);
            lbl.setClickable(false);
            lbl.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            lbl.setBeforeRenderCallback(() -> {
                lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lbl.setMaxWidth(this.getWidth() - 104);
                lbl.setPosition(12, this.getHeight() * .5 - lbl.getHeight() * .5);
            });

            LabelWidget action = new LabelWidget(this::getActionText, FontManager.pf12bold);
            this.addChild(action);
            action.setClickable(false);
            action.setBeforeRenderCallback(() -> {
                String text = action.getLabel();
                action.setHidden(text.isEmpty());
                action.setColor(getActionColor());
                action.setPosition(this.getWidth() - action.getFont().getStringWidthD(text) - 11,
                        this.getHeight() * .5 - action.getHeight() * .5);
            });

            this.setBeforeRenderCallback(() -> {
                this.setBounds(this.getParentWidth(), 28);
                boolean active = this.isHovering() && !submitting && closeAfterMillis <= 0L;
                hover.setAlpha(Interpolations.interpolate(hover.getWidgetAlpha(), active ? 1f : 0f, .3f));
                hover.setHidden(hover.getWidgetAlpha() <= .05f);
            });

            this.setOnClickCallback((x, y, button) -> {
                if (button != 0 || submitting || closeAfterMillis > 0L) {
                    return button == 0;
                }
                submitToPlaylist(playlist);
                return true;
            });
        }

        private String getActionText() {
            if (playlist != selectedPlaylist) {
                return "";
            }
            if (submitting) {
                return "校验中…";
            }
            if (feedbackState == FeedbackState.SUCCESS) {
                return "已确认";
            }
            if (feedbackState == FeedbackState.ALREADY_EXISTS) {
                return "已存在";
            }
            if (feedbackState == FeedbackState.ERROR) {
                return "点击重试";
            }
            return "";
        }

        private int getActionColor() {
            if (feedbackState == FeedbackState.ERROR) {
                return STATUS_ERROR_COLOR;
            }
            if (feedbackState == FeedbackState.SUCCESS || feedbackState == FeedbackState.ALREADY_EXISTS) {
                return STATUS_SUCCESS_COLOR;
            }
            return STATUS_PROCESSING_COLOR;
        }
    }
}
