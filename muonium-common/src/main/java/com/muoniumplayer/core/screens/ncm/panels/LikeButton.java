package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.BilibiliFavoriteService;
import com.muoniumplayer.core.ncm.music.BilibiliMusicService;
import com.muoniumplayer.core.ncm.music.BodianMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouLikedCache;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.YouTubeMusicService;
import com.muoniumplayer.core.ncm.music.YouTubeSessionStore;
import com.muoniumplayer.core.ncm.apple.AppleMusicService;
import com.muoniumplayer.core.ncm.spotify.SpotifyAuthStore;
import com.muoniumplayer.core.ncm.spotify.SpotifyMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.AnimatedSvgWidget;
import com.muoniumplayer.core.rendering.ui.widgets.ThemedTextureIconWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.DoubleSupplier;

/**
 * 「喜欢」那一颗：描边图标与实心红心动画两态，加点击行为（取消收藏走二次确认）。
 *
 * <p>从 {@link MusicWidget} 里搬出来的，因为私人 FM 的 hero 卡也要同一颗。搬它不是为了少写几行，
 * 而是这套两态切换每一步都是踩过的坑，两处各写一份迟早有一份再踩回去：</p>
 * <ul>
 *   <li>已喜欢时<b>不能</b>把静态那枚 {@code setHidden}：{@code AbstractWidget#renderWidget}
 *       在隐藏态连 {@code beforeRenderCallback} 都不跑，而唯一能把动画那枚翻回来的代码就在这里，
 *       藏了之后红心再也出不来。只能把整枚的 alpha 压成 0。</li>
 *   <li>也<b>不能</b>用 {@code setColor(0x00FFFFFF)} 假装透明：{@code AbstractWidget#getColor}
 *       会拿控件自己的 alpha 顶掉颜色里那个 alpha 字节，于是那圈白描边照样画出来，
 *       从红心底下透出一圈毛边。</li>
 *   <li>常驻态冻在实心那一帧（{@link AnimatedIcons#likeSolid()}），而不是从起播相位走：
 *       一列两百行都从起播走的话，整列红心会跟着一起爆心。爆心只在点下去那一刻发一条新时钟。</li>
 *   <li>两枚素材在各自画布里占的比例不一样：同一格 1:1 画，描边那枚实测 22 设备像素宽，
 *       like.svg 的红心只有 19.2，一列里两种心大小不一致，一眼就看得出来。所以实心放大 1.15
 *       把宽度对齐（19.2 × 1.15 ≈ 22），再同心到描边那枚上。</li>
 * </ul>
 */
public final class LikeButton {

    /** 描边那枚的边长。宿主排自己那一排时按这个宽度算间距。 */
    static final double BOX = 20.0;
    /** 实心那枚相对描边的放大倍率，见类注释最后一条。 */
    private static final double SOLID_SCALE = 1.15;

    private final ThemedTextureIconWidget outline;
    private final AnimatedSvgWidget solid;
    private final boolean likeable;

    /**
     * 两枚都挂在 {@code host} 上，所以它们跟着宿主一起被裁进列表行或 hero 卡里。
     *
     * @param leftX 每帧报一次这一颗的左边缘 —— 由宿主决定它排在哪，红心自己只管两态与同心。
     */
    LikeButton(AbstractWidget<?> host, Music music, DoubleSupplier leftX) {
        this.likeable = canFavourite(music);

        this.outline = new ThemedTextureIconWidget(PlayerIconAssets.FAVORITE, "☆", FontManager.pf16bold,
                0, 0, (int) BOX, (int) BOX);
        host.addChild(outline);
        outline.setShouldOverrideMouseCursor(true);
        outline.setHidden(!likeable);
        outline.setClickable(likeable);

        this.solid = new AnimatedSvgWidget(AnimatedIcons.like());
        host.addChild(solid);
        solid.setShouldOverrideMouseCursor(true);
        solid.setClickable(likeable);
        // 先按未喜欢收起来，等下面那个每帧都跑的回调来翻 —— 别让它自己藏自己，
        // renderWidget 在 isHidden() 时连 beforeRenderCallback 都不跑，藏了就没机会回来。
        solid.setHidden(true);
        solid.setPlayback(AnimatedIcons.likeSolid());

        final AbstractWidget.OnClickCallback click = (x, y, mouseButton) ->
                mouseButton == 0 && likeable && toggleLiked(music, solid);
        // 同一份处理挂在两枚图标上：未喜欢点静态那枚，已喜欢点动画那枚，行为完全一致
        // （取消收藏仍要走二次确认）。
        outline.setOnClickCallback(click);
        solid.setOnClickCallback(click);

        outline.setBeforeRenderCallback(() -> {
            boolean liked = isLiked(music);
            outline.setAlpha(liked ? 0f : 1f);
            outline.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            outline.centerVertically();
            outline.setPosition(leftX.getAsDouble(), outline.getRelativeY());
            // 动画那枚的显隐统一由这里管：这一格每帧都跑，两个方向都还有机会翻回来。
            solid.setHidden(!likeable || !liked);

            double box = outline.getWidth() * SOLID_SCALE;
            solid.setBounds(box, outline.getHeight() * SOLID_SCALE);
            solid.setPosition(
                    outline.getRelativeX() + (outline.getWidth() - solid.getWidth()) * .5,
                    outline.getRelativeY() + (outline.getHeight() - solid.getHeight()) * .5);
        });
    }

    double getWidth() {
        return outline.getWidth();
    }

    double getRelativeX() {
        return outline.getRelativeX();
    }

    boolean isLikeable() {
        return likeable;
    }

    /** 同一首歌的曲行、FM 与灵动岛共用请求占位和确认票据。 */
    private static final PendingLikes PENDING = new PendingLikes();
    private static final Set<String> QQ_LIKED = new HashSet<>();

    @FunctionalInterface
    public interface ConfirmationHost {
        void open(String title, String message, String confirmText, Runnable onConfirm);
    }

    /** @param burst 爆心动画的控件；没有动画时传 {@code null}。 */
    static boolean toggleLiked(Music music, AnimatedSvgWidget burst) {
        return toggleLiked(music, burst == null ? null
                : () -> burst.setPlayback(AnimatedIcons.likeBurst()),
                (title, message, confirmText, onConfirm) -> NCMScreen.getInstance()
                        .openConfirmation(title, message, confirmText, onConfirm));
    }

    /**
     * 歌曲行与灵动岛共用数据动作；在客户端线程调用，确认与爆心由各自可见的宿主承接。
     * 同曲请求完成并在客户端线程处理结果前，重复点击只消费事件，不发相反请求。
     */
    public static boolean toggleLiked(Music music, Runnable onLiked, ConfirmationHost confirmation) {
        if (!canFavourite(music)) return false;
        String key = music.getStableKey();
        if (PENDING.isPending(key)) return true;
        if (!isLiked(music)) {
            Object request = PENDING.begin(key, null);
            if (request != null) changeLiked(music, key, request, true, onLiked);
            return true;
        }

        if (confirmation == null) return false;
        Object ticket = PENDING.confirmation(key);
        if (ticket == null) return true;
        confirmation.open(
                "取消收藏歌曲？",
                music.isBilibili()
                        ? "取消后，这首稿件将从你的默认收藏夹中移除。"
                        : "取消后，这首歌曲将从“我喜欢的音乐”中移除。",
                "取消收藏",
                () -> {
                    if (!key.equals(music.getStableKey()) || !canFavourite(music) || !isLiked(music)) return;
                    Object request = PENDING.begin(key, ticket);
                    if (request != null) changeLiked(music, key, request, false, null);
                });
        return true;
    }

    private static void changeLiked(Music music, String key, Object request, boolean liked, Runnable onLiked) {
        boolean submitted = false;
        try {
            if (onLiked != null) onLiked.run();
            noteLiked(music, liked);
            MultiThreadingUtil.runAsync(() -> {
                boolean completed = false;
                try {
                    completed = writeLiked(music, liked);
                    if (!completed) {
                        MultiThreadingUtil.LOGGER.warn("Favourite request failed for {} (liked={})", key, liked);
                    }
                } finally {
                    boolean rollback = !completed;
                    MultiThreadingUtil.runOnMainThread(() -> finishLiked(music, key, request, liked, rollback));
                }
            }).whenComplete((unused, failure) -> {
                if (failure != null) MultiThreadingUtil.LOGGER.catching(failure);
            });
            submitted = true;
        } finally {
            // 动画、乐观写入或提交任务抛错也不能留下永久占位；原异常继续向调用方传播。
            if (!submitted) {
                MultiThreadingUtil.runOnMainThread(() -> finishLiked(music, key, request, liked, true));
            }
        }
    }

    /** 这里只表示没有已知失败；网易云的 void 入口不能证明上游确认成功。 */
    private static boolean writeLiked(Music music, boolean liked) {
        if (music.isNetease()) {
            music.setLike(liked);
            return true;
        }
        if (music.isQQ()) return NeteaseMusicService.setQQLike(music, liked);
        if (music.isQishui()) return QishuiMusicService.setTrackLiked(music.getSourceId(), liked);
        if (music.isBodian()) return BodianMusicService.setTrackLiked(music.getSourceId(), liked);
        if (music.isBilibili()) return BilibiliFavoriteService.setFavourite(music, liked);
        if (music.isKuGou()) return liked ? KuGouMusicService.likeTrack(music) : KuGouMusicService.unlikeTrack(music);
        if (music.isSpotify()) return SpotifyMusicService.setTrackLiked(music.getSourceId(), liked);
        if (music.isYouTube()) return YouTubeMusicService.setTrackLiked(music.getSourceId(), liked);
        if (music.getSource() == MusicPlatform.APPLE) return AppleMusicService.setTrackLiked(music.getSourceId(), liked);
        return false;
    }

    /** 仅由客户端线程执行；回滚仍须属于这次请求，且不能覆盖已改变的本地判断。 */
    private static void finishLiked(Music music, String key, Object request, boolean liked, boolean rollback) {
        if (!PENDING.owns(key, request)) return;
        try {
            if (rollback && key.equals(music.getStableKey()) && isLiked(music) == liked) {
                noteLiked(music, !liked);
            }
        } finally {
            PENDING.finish(key, request);
        }
    }

    private static void noteLiked(Music music, boolean liked) {
        if (music.isNetease()) {
            var likes = CloudMusic.likeList;
            if (likes != null) {
                if (liked) {
                    if (!likes.contains(music.getId())) likes.add(music.getId());
                } else {
                    likes.remove(music.getId());
                }
            }
        } else if (music.isQQ()) {
            synchronized (QQ_LIKED) {
                if (liked) QQ_LIKED.add(music.getStableKey());
                else QQ_LIKED.remove(music.getStableKey());
            }
        } else if (music.isQishui()) {
            QishuiMusicService.noteTrackLikedLocal(music.getSourceId(), liked);
        } else if (music.isBilibili()) {
            noteBilibiliFavoured(music, liked);
        } else if (music.isKuGou()) {
            noteKuGouLiked(music, liked);
        } else if (music.isBodian()) {
            BodianMusicService.noteTrackLikedLocal(music.getSourceId(), liked);
        } else if (music.isSpotify()) {
            SpotifyMusicService.noteTrackLikedLocal(music.getSourceId(), liked);
        } else if (music.isYouTube()) {
            YouTubeMusicService.noteTrackLikedLocal(music.getSourceId(), liked);
        } else if (music.getSource() == MusicPlatform.APPLE) {
            AppleMusicService.noteTrackLikedLocal(music.getSourceId(), liked);
        }
    }

    /** 确认框可取消而没有通知，因此弱持有票据；只有实际请求才占 pending。 */
    static final class PendingLikes {
        private final Map<String, Object> pending = new HashMap<>();
        private final Map<Object, String> confirmations = new WeakHashMap<>();

        synchronized boolean isPending(String key) {
            return pending.containsKey(key);
        }

        synchronized Object confirmation(String key) {
            if (pending.containsKey(key)) return null;
            Object ticket = new Object();
            confirmations.put(ticket, key);
            return ticket;
        }

        synchronized Object begin(String key, Object ticket) {
            if (pending.containsKey(key)) return null;
            if (ticket != null && !key.equals(confirmations.get(ticket))) return null;
            // 即使本次失败后红心回到了原值，旧确认也不能重新生效。
            confirmations.values().removeIf(key::equals);
            Object request = new Object();
            pending.put(key, request);
            return request;
        }

        synchronized boolean owns(String key, Object request) {
            return pending.get(key) == request;
        }

        synchronized void finish(String key, Object request) {
            pending.remove(key, request);
        }
    }

    /**
     * 这颗红心现在亮不亮。
     *
     * <p>只读本地状态，绝不在这里发请求：这个方法每帧、每一行都要问一次，挂上去就是每帧一次
     * 网络往返。酷狗读 {@link KuGouLikedCache}，B 站读它自己的本地会话记忆。</p>
     */
    public static boolean isLiked(Music music) {
        if (music == null) return false;
        if (music.isNetease()) {
            return CloudMusic.likeList != null && CloudMusic.likeList.contains(music.getId());
        }
        if (music.isKuGou()) {
            return KuGouLikedCache.contains(music.getStableKey());
        }
        if (music.isQQ()) {
            synchronized (QQ_LIKED) {
                return QQ_LIKED.contains(music.getStableKey());
            }
        }
        if (music.isQishui()) {
            QishuiMusicService.ensureLikedTrackIdsLoaded();
            return QishuiMusicService.isTrackLikedLocal(music.getSourceId());
        }
        if (music.isBilibili()) {
            return BilibiliFavoriteService.isFavouredLocally(music);
        }
        if (music.isBodian()) {
            BodianMusicService.ensureLikedTrackIdsLoaded();
            return BodianMusicService.isTrackLikedLocal(music.getSourceId());
        }
        if (music.isSpotify()) return SpotifyMusicService.isTrackLiked(music.getSourceId());
        if (music.isYouTube()) return YouTubeMusicService.isTrackLiked(music.getSourceId());
        if (music.getSource() == MusicPlatform.APPLE) return AppleMusicService.isTrackLiked(music.getSourceId());
        return false;
    }

    /**
     * 红心该不该画出来。
     *
     * <p>B 站的收藏落在它自己的收藏夹上，和网易云那套是两条链路；而且没扫码时写入必然失败，
     * 与其点了闪一下又弹回来，不如不画。</p>
     */
    public static boolean canFavourite(Music music) {
        if (music == null) return false;
        if (music.isQishui()) {
            return QishuiMusicService.isLoggedIn() && !QishuiMusicService.isReauthRequired();
        }
        if (music.isBodian()) {
            return BodianMusicService.isLoggedIn();
        }
        if (music.isQQ()) return NeteaseMusicService.isLoggedIn(MusicPlatform.QQ);
        if (music.isSpotify()) {
            return SpotifyMusicService.isLoggedIn()
                    && SpotifyAuthStore.snapshot().hasLibraryModifyScope();
        }
        if (music.isYouTube()) return YouTubeSessionStore.isLoggedIn();
        if (music.getSource() == MusicPlatform.APPLE) return AppleMusicService.isLoggedIn();
        return music.isNetease() || music.isKuGou()
                || (music.isBilibili() && BilibiliMusicService.isLoggedIn());
    }

    /** 记录/撤销 B 站红心的本地判断，语义同 {@link #noteKuGouLiked}。 */
    private static void noteBilibiliFavoured(Music music, boolean favoured) {
        if (music == null) return;
        BilibiliFavoriteService.rememberFavoured(music.getSourceId(), favoured);
    }

    /** 记录/撤销「已经在「我喜欢」里」这个本地判断。失败回滚用得到。 */
    private static void noteKuGouLiked(Music music, boolean liked) {
        if (music == null) return;
        if (liked) {
            KuGouLikedCache.add(music.getStableKey());
        } else {
            KuGouLikedCache.remove(music.getStableKey());
        }
    }
}
