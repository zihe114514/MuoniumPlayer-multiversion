package com.muoniumplayer.party;

import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.Collections;

/**
 * 房间指令与本机播放器之间的那一层：把服务端下发的「播这首、跳到这里、暂停」翻译成
 * 播放器现有的调用，不新开第二套播放通路。
 *
 * <p>刻意复用 {@link CloudMusic#play(java.util.List, int)}：下载、容器识别、AAC 转码、
 * 封面、歌词、灵动岛全都挂在那条链路上，绕过它就等于在房间里丢掉播放器一半的功能。
 * 房间下发的链接通过 {@code Music#setPartyStreamUrl} 注入，取流时优先于本机各源解析 ——
 * 会员成员解析出的完整音频，非会员成员也能听到同一条流。
 *
 * <p>起播位置不能在调用 {@code play} 时就设：那一刻还没解析链接、没下载、没解码。
 * 因此这里只记一个「待应用」标记，由 {@link #tick()} 在播放器真的可用之后再对齐到
 * 房间当前时钟（而不是当初那个已经过期的位置）。
 */
public final class MpPlayback {

    private MpPlayback() {
    }

    // 漂移阈值不再写死在这里：软／硬阈值、确认次数、冷却全在 MpSyncPolicy，由服务端 hello
    // 统一下发，保证同一房间里所有客户端（含 modernMP）的纠偏口径一致。

    /** 一首歌从「开始起播」到「播放器可用」的等待上限（解析 + 下载 + 转码）。 */
    private static final long START_TIMEOUT_MS = 120_000L;

    /** 房间当前曲目对应的 Music 实例；本地误起播的拦截也靠它做身份判断。 */
    private static volatile Music partyMusic;
    /** 正在等「解析+下载+解码」完成，等到了由 {@link #tick()} 对齐一次。 */
    private static volatile boolean startPending;
    private static volatile long pendingSince;

    /** 进房前的播放模式。单曲循环会让本机自己重播，和房间时钟对不上。 */
    private static volatile CloudMusic.PlayMode savedPlayMode;

    /** 该实例是否就是房间正在放的那一首（供拦截逻辑放行自己的起播）。 */
    public static boolean isPartyMusic(Music music) {
        return music != null && music == partyMusic;
    }

    public static Music currentMusic() {
        return partyMusic;
    }

    /**
     * 起播（或换歌）。
     *
     * <p>曲目<b>完全用房间下发的元数据</b>构造，不再拿 songId 回平台重查一遍。原来那条路是
     * 「{@code MpNetease.songById(numericId)}」，它有两个后果：非数字 id（酷狗 hash）直接被静默
     * 丢掉，什么都不会发生；而 QQ 的数字 id 更糟 —— 会查到一首完全不相干的网易云歌然后照播。
     * 现在不需要本机装有该平台，甚至不需要网易云会话，只要能下载那条直链就行。</p>
     */
    public static void play(final MpTrack track, final String streamUrl, final long posMs, final boolean playing) {
        if (track == null || streamUrl == null || streamUrl.trim().isEmpty()) return;
        if (track.songId == null || track.songId.trim().isEmpty()) return;

        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                Music music;
                try {
                    music = track.toMusic();
                } catch (Throwable throwable) {
                    System.err.println("[MpParty] 构造曲目失败：" + throwable);
                    MpPartyClient.note("无法识别房间下发的歌曲：" + track.display(), true);
                    return;
                }
                music.setPartyStreamUrl(streamUrl);
                music.setPartyStreamType("");
                // 起播链路一旦静默失败（下载好了却没声音）就完全没有痕迹可查，所以这一路
                // 留三条 INFO：拿到元数据、交给播放器、以及 tick() 真正对齐成功。
                System.out.println("[MpParty] 起播 " + track.display() + " key=" + track.key()
                        + " pos=" + posMs + " playing=" + playing + " url=" + brief(streamUrl));

                forceSequentialMode();
                partyMusic = music;
                startPending = true;
                pendingSince = System.currentTimeMillis();

                MpPartyClient.beginRemote();
                try {
                    CloudMusic.play(Collections.singletonList(music), 0);
                } catch (Throwable throwable) {
                    System.err.println("[MpParty] 起播失败：" + throwable);
                    MpPartyClient.note("起播失败：" + track.display(), true);
                    startPending = false;
                } finally {
                    MpPartyClient.endRemote();
                }
            }
        });
    }

    public static void pause(final long posMs) {
        MpSyncPolicy.markCommand();
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer player = usablePlayer();
                if (player == null) return;
                player.setPlaybackTime(posMs);
                if (!player.isPausing()) player.pause();
            }
        });
    }

    public static void resume(final long posMs) {
        MpSyncPolicy.markCommand();
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer player = usablePlayer();
                if (player == null) return;
                player.setPlaybackTime(posMs);
                if (player.isPausing()) player.unpause();
            }
        });
    }

    public static void seek(final long posMs) {
        MpSyncPolicy.markCommand();
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer player = usablePlayer();
                if (player != null) player.setPlaybackTime(posMs);
            }
        });
    }

    /** 房间停了（队列播完 / 离开 / 解散）：停下房间那一路播放，把播放模式还回去。 */
    public static void stop() {
        startPending = false;
        final Music music = partyMusic;
        partyMusic = null;
        restorePlayMode();
        if (music == null) return;
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer player = CloudMusic.player;
                if (player != null && player.isUsable() && CloudMusic.currentlyPlaying == music
                        && !player.isPausing()) {
                    player.pause();
                }
            }
        });
    }

    /**
     * 服务端时钟帧：只纠偏，不重启。
     *
     * <p>暂停/播放状态也在这里兜底 —— 单个 pause/resume 帧可能在起播还没完成时到达，
     * 那一帧就只能靠随后的 time 帧把状态补齐。
     */
    public static void time(final long posMs, long durationMs, final boolean playing) {
        if (startPending) return;             // 起播还没完成，等 tick() 一次性对齐
        AudioPlayer player = usablePlayer();
        if (player == null) return;

        final boolean pausing = player.isPausing();
        final long local = (long) player.getCurrentTimeMillis();

        // 播放状态本身永远要跟上（暂停/恢复帧可能在起播还没完成时就到了，只能靠心跳补齐），
        // 位置纠偏则走三档判定。
        final boolean stateWrong = pausing == playing;
        final boolean needSeek = decideSeek(playing, pausing, local, posMs);
        if (!needSeek && !stateWrong) return;

        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer active = usablePlayer();
                if (active == null) return;
                if (needSeek) active.setPlaybackTime(posMs);
                if (playing && active.isPausing()) active.unpause();
                if (!playing && !active.isPausing()) active.pause();
            }
        });
    }

    /**
     * 这一帧心跳要不要硬跳播放头。
     *
     * <p>暂停时位置是死的，不存在「越对越乱」，超过软阈值就直接对齐。正在播放时交给
     * {@link MpSyncPolicy#shouldHardSeek}：只有连续多次超硬阈值、且过了冷却，才真的跳一次；
     * 软档（软阈值~硬阈值）刻意什么都不做 —— 宁可差几秒，也不要为了对齐而反复扯动播放头。</p>
     */
    private static boolean decideSeek(boolean playing, boolean pausing, long local, long posMs) {
        long drift = local - posMs;
        long abs = drift < 0L ? -drift : drift;
        if (!playing) return abs > MpSyncPolicy.softDriftMs();
        // 房间在放而本机还是暂停态：先把播放按下去，这一帧不动位置（下面会 unpause），
        // 让它跑起来再谈对齐。
        if (pausing) {
            MpSyncPolicy.markCommand();
            return false;
        }
        if (MpSyncPolicy.inGrace()) return false;
        return MpSyncPolicy.shouldHardSeek(drift);
    }

    /**
     * 每个心跳周期调一次：把「已经起播完成」的那一首对齐到房间此刻的位置。
     *
     * <p>对齐用的是 {@link MpPartyClient#positionMs()} 而不是当初 {@code play} 帧里的位置：
     * 解析 + 下载 + 转码可能过去了十几秒，用旧值会一起播就落后。
     */
    public static void tick() {
        if (!startPending) return;

        if (System.currentTimeMillis() - pendingSince > START_TIMEOUT_MS) {
            startPending = false;
            MpPartyClient.note("起播超时，已放弃与房间对齐（等房主切下一首会自动重试）", true);
            return;
        }

        AudioPlayer player = CloudMusic.player;
        Music music = partyMusic;
        if (player == null || !player.isUsable() || music == null
                || CloudMusic.currentlyPlaying != music || player.getTotalTimeMillis() <= 0.0f) {
            return;
        }

        startPending = false;
        // 这是一次明确的对齐（刚起播完成），随后给一段静默期让缓冲跑起来，别马上又量漂移。
        MpSyncPolicy.markCommand();
        final long target = MpPartyClient.positionMs();
        final boolean playing = MpPartyClient.playing();
        System.out.println("[MpParty] 已就绪，对齐到 " + target + "/"
                + (long) player.getTotalTimeMillis() + " playing=" + playing
                + " pausing=" + player.isPausing() + " vol=" + player.volume
                + " fade=" + player.getFadeGain());
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer active = usablePlayer();
                if (active == null) return;
                if (target > 0L) active.setPlaybackTime(target);
                if (playing && active.isPausing()) active.unpause();
                if (!playing && !active.isPausing()) active.pause();
            }
        });
    }

    public static long localPositionMs() {
        AudioPlayer player = usablePlayer();
        return player == null ? 0L : (long) player.getCurrentTimeMillis();
    }

    public static boolean localPlaying() {
        AudioPlayer player = usablePlayer();
        return player != null && !player.isPausing();
    }

    /** 房间正在放的那一首对应的播放器，其它情况一律 null（绝不动用户自己的播放）。 */
    private static AudioPlayer usablePlayer() {
        Music music = partyMusic;
        AudioPlayer player = CloudMusic.player;
        if (music == null || player == null || !player.isUsable()) return null;
        return CloudMusic.currentlyPlaying == music ? player : null;
    }

    /** 日志里只留链接的路径部分：网易云的签名参数又长又没有诊断价值。 */
    private static String brief(String url) {
        if (url == null) return "-";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static void onMainThread(Runnable task) {
        try {
            MultiThreadingUtil.runOnMainThread(task);
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 主线程任务提交失败：" + throwable);
        }
    }

    /**
     * 房间里本机不能自己循环：单曲循环会在房间换歌之前把同一首重播一遍。
     * 顺序播放下队列只有一首，播完就停，正好等服务端下发下一首。
     */
    private static void forceSequentialMode() {
        if (savedPlayMode != null) return;
        CloudMusic.PlayMode mode = CloudMusic.playMode;
        if (mode == CloudMusic.PlayMode.Sequential) return;
        savedPlayMode = mode;
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
    }

    private static void restorePlayMode() {
        CloudMusic.PlayMode mode = savedPlayMode;
        savedPlayMode = null;
        if (mode != null) CloudMusic.playMode = mode;
    }
}
