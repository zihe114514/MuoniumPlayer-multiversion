package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/**
 * 记录酷狗曲目的真实播放时长，并以 CSCC 的 start / end 事件成对上报。
 *
 * <p>和 {@link NeteasePlaybackHistoryReporter} 的结构几乎一样（同样是「播放中采样 + 结束时结算一次」，
 * 暂停与拖动都不计时），差别在<b>上报形态</b>：</p>
 * <ul>
 *   <li>网易云是「结束时提交一个累计秒数」；</li>
 *   <li>酷狗要求 <b>开始时就发一次 start</b>，结束时再发一次带 {@code duration} 的 end。
 *       只发 end 的话时长不会计入成长值——这是服务端的既有要求，不是我们能省掉的。</li>
 * </ul>
 *
 * <p>另外网易云还会在开始时单独上报一条「最近播放」，酷狗那边对应的是
 * {@code playhistory/v1/upload_songs}（只记听过、不记时长），也在这里一起发。</p>
 *
 * <p><b>失败不重试</b>：超时也可能已经被记账，重试会重复计入时长。</p>
 */
public final class KuGouPlaybackReporter {

    /** 卡顿的客户端/渲染线程不能把一次冻结算成听歌时长。 */
    private static final long MAXIMUM_OBSERVED_STEP_NANOS = 1_500_000_000L;

    private static final Object LOCK = new Object();
    private static ActiveSession activeSession;

    private KuGouPlaybackReporter() {
    }

    public enum EndReason {
        COMPLETED,
        REPLACED
    }

    /** 音频真正开始播放之后才开始计时。 */
    public static void start(Music song, AudioPlayer player) {
        if (song == null || player == null || !song.isKuGou()) return;
        if (!KuGouMusicService.isLoggedIn()) return;

        synchronized (LOCK) {
            // 没有正常停止回调就换了歌时，先把上一段结算掉，否则那段时长永远丢失。
            finishLocked(null, EndReason.REPLACED, true);
            activeSession = new ActiveSession(song, player, System.nanoTime());
        }

        final Music startedSong = song;
        MultiThreadingUtil.runAsync(() -> {
            // 上报只记「听过」，与时长上报相互独立；它失败不影响 CSCC。
            KuGouMusicService.uploadPlayHistory(startedSong, 1);
            KuGouMusicService.reportPlaybackStart(startedSong);
        });
    }

    /** 采样正在跑的播放器；暂停状态不累加。 */
    public static void observe(AudioPlayer player) {
        if (player == null) return;
        synchronized (LOCK) {
            if (activeSession != null && activeSession.player == player) {
                sampleLocked(activeSession, System.nanoTime());
            }
        }
    }

    /** 停止计时并为这一段提交唯一一次 end 事件。 */
    public static void finish(AudioPlayer player, EndReason reason) {
        synchronized (LOCK) {
            finishLocked(player, reason == null ? EndReason.REPLACED : reason, true);
        }
    }

    private static void finishLocked(AudioPlayer player, EndReason reason, boolean submit) {
        ActiveSession session = activeSession;
        if (session == null || (player != null && session.player != player)) return;

        sampleLocked(session, System.nanoTime());
        activeSession = null;
        if (!submit || !isEligible(session)) return;

        long playedMillis = session.playedNanos / 1_000_000L;
        if (playedMillis <= 0L) return;

        final Music song = session.song;
        final long finalPlayedMillis = playedMillis;
        MultiThreadingUtil.runAsync(() -> KuGouMusicService.reportPlaybackEnd(song, finalPlayedMillis));
    }

    private static void sampleLocked(ActiveSession session, long now) {
        long elapsed = Math.max(0L, now - session.lastObservedNanos);
        session.lastObservedNanos = now;
        if (!session.player.isPausing()) {
            session.playedNanos += Math.min(elapsed, MAXIMUM_OBSERVED_STEP_NANOS);
        }
    }

    private static boolean isEligible(ActiveSession session) {
        return session.song.isKuGou() && KuGouMusicService.isLoggedIn();
    }

    /** 清掉当前会话而不上报。登出时用。 */
    public static void abandon() {
        synchronized (LOCK) {
            activeSession = null;
        }
    }

    private static final class ActiveSession {
        private final Music song;
        private final AudioPlayer player;
        private long lastObservedNanos;
        private long playedNanos;

        private ActiveSession(Music song, AudioPlayer player, long now) {
            this.song = song;
            this.player = player;
            this.lastObservedNanos = now;
        }
    }
}
