package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.BilibiliFavoriteService;
import com.muoniumplayer.core.ncm.music.BilibiliMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouLikedCache;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.AnimatedSvgWidget;
import com.muoniumplayer.core.rendering.ui.widgets.ThemedTextureIconWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

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
final class LikeButton {

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

    /**
     * 喜欢 / 取消收藏这一个动作唯一的家。网易云改本地红心表再异步回写，B 站走它自己的收藏夹，
     * 其余按酷狗处理；两边的失败都要把本地判断回滚掉，否则界面会停在一颗点亮的红心上，
     * 而服务器那边什么都没发生。
     *
     * @param burst 播一次爆心动画的那枚控件；宿主没有动画时传 {@code null}，只走数据那一半。
     */
    static boolean toggleLiked(Music music, AnimatedSvgWidget burst) {
        if (!isLiked(music)) {
            if (burst != null) {
                // 换一条新的一次性时钟：这一枚控件记的是自己那条墙上时钟的起点，不重开的话
                // 第二次点心就不会再有粒子。走完停在 .47，正好就是平时那张常驻的实心帧。
                burst.setPlayback(AnimatedIcons.likeBurst());
            }
            if (music.isNetease()) {
                if (CloudMusic.likeList != null && !CloudMusic.likeList.contains(music.getId())) {
                    CloudMusic.likeList.add(music.getId());
                }
                MultiThreadingUtil.runAsync(() -> music.setLike(true));
            } else if (music.isQishui()) {
                QishuiMusicService.noteTrackLikedLocal(music.getSourceId(), true);
                MultiThreadingUtil.runAsync(() -> {
                    if (!QishuiMusicService.setTrackLiked(music.getSourceId(), true)) {
                        QishuiMusicService.noteTrackLikedLocal(music.getSourceId(), false);
                    }
                });
            } else if (music.isBilibili()) {
                noteBilibiliFavoured(music, true);
                MultiThreadingUtil.runAsync(() -> {
                    if (!BilibiliFavoriteService.setFavourite(music, true)) {
                        noteBilibiliFavoured(music, false);
                    }
                });
            } else {
                noteKuGouLiked(music, true);
                MultiThreadingUtil.runAsync(() -> {
                    boolean ok = KuGouMusicService.likeTrack(music);
                    if (!ok) noteKuGouLiked(music, false);
                });
            }
            return true;
        }

        NCMScreen.getInstance().openConfirmation(
                "取消收藏歌曲？",
                music.isBilibili()
                        ? "取消后，这首稿件将从你的默认收藏夹中移除。"
                        : "取消后，这首歌曲将从“我喜欢的音乐”中移除。",
                "取消收藏",
                () -> {
                    if (music.isNetease()) {
                        if (CloudMusic.likeList != null) {
                            CloudMusic.likeList.remove(music.getId());
                        }
                        MultiThreadingUtil.runAsync(() -> music.setLike(false));
                    } else if (music.isQishui()) {
                        QishuiMusicService.noteTrackLikedLocal(music.getSourceId(), false);
                        MultiThreadingUtil.runAsync(() -> {
                            if (!QishuiMusicService.setTrackLiked(music.getSourceId(), false)) {
                                QishuiMusicService.noteTrackLikedLocal(music.getSourceId(), true);
                            }
                        });
                    } else if (music.isBilibili()) {
                        noteBilibiliFavoured(music, false);
                        MultiThreadingUtil.runAsync(() -> {
                            if (!BilibiliFavoriteService.setFavourite(music, false)) {
                                noteBilibiliFavoured(music, true);
                            }
                        });
                    } else {
                        noteKuGouLiked(music, false);
                        MultiThreadingUtil.runAsync(() -> {
                            boolean ok = KuGouMusicService.unlikeTrack(music);
                            if (!ok) noteKuGouLiked(music, true);
                        });
                    }
                });
        return true;
    }

    /**
     * 这颗红心现在亮不亮。
     *
     * <p>只读本地状态，绝不在这里发请求：这个方法每帧、每一行都要问一次，挂上去就是每帧一次
     * 网络往返。酷狗读 {@link KuGouLikedCache}，B 站读它自己的本地会话记忆。</p>
     */
    static boolean isLiked(Music music) {
        if (music == null) return false;
        if (music.isNetease()) {
            return CloudMusic.likeList != null && CloudMusic.likeList.contains(music.getId());
        }
        if (music.isKuGou()) {
            return KuGouLikedCache.contains(music.getStableKey());
        }
        if (music.isQishui()) {
            QishuiMusicService.ensureLikedTrackIdsLoaded();
            return QishuiMusicService.isTrackLikedLocal(music.getSourceId());
        }
        if (music.isBilibili()) {
            return BilibiliFavoriteService.isFavouredLocally(music);
        }
        return false;
    }

    /**
     * 红心该不该画出来。
     *
     * <p>B 站的收藏落在它自己的收藏夹上，和网易云那套是两条链路；而且没扫码时写入必然失败，
     * 与其点了闪一下又弹回来，不如不画。</p>
     */
    static boolean canFavourite(Music music) {
        if (music == null) return false;
        if (music.isQishui()) {
            return QishuiMusicService.isLoggedIn() && !QishuiMusicService.isReauthRequired();
        }
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
