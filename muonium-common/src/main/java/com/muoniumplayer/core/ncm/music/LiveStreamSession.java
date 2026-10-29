package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 广播电台直播流的中央会话：一个系统里只能有一个「正在播放」是真的，所以直播与
 * 内部引擎（{@code CloudMusic} 的 PlayThread/JSyn）严格互斥 —— 起直播先停引擎、
 * 起引擎先停直播（{@code CloudMusic} 在起播与停止两条路径上各查一次本类）。
 *
 * <p>状态暴露走 {@link ExternalPlayback} 的同一模式：volatile 单例 + 快照方法，
 * UI（底栏、快照、音量）只在既有的三个分叉点上加一次直播分支，别的渲染代码不动。</p>
 *
 * <p>直播没有队列、没有 seek、没有自动续播：断流重连在 {@link LiveStreamPlayer} 内部
 * 完成，重试用尽即收尾清场——界面上表现为「播放条回到空闲」，用户重新点台即可。
 * 广播不等人，暂停也不会追进度。</p>
 */
public final class LiveStreamSession {

    private static final AtomicReference<LiveStreamPlayer> ACTIVE = new AtomicReference<>(null);
    /** 直播正在播的那张卡（BroadcastPanel 传进来的模型）。 */
    private static volatile Object activeStation;
    /** 岛屿反馈里说过的「正在连播」文案用的频道名。 */
    private static volatile String activeStationName = "";

    private LiveStreamSession() {
    }

    // ── 起播 / 停止 ──────────────────────────────────────────────────────────

    /**
     * 起播一个广播频道。内部先停掉既有引擎与既有直播，再拉新流；
     * 流地址为空（频道未开播等）时返回 {@code false} 并弹岛屿提示。
     *
     * @param station        频道模型（{@code DjRadio}），只透传回 UI 侧，本类不解引用
     * @param stationName    显示名
     * @param playUrl        直播流地址（HTTP 直推 MPEG）
     * @param listenerOpt    可选的监听器（UI 要感知 started/ended 时传，可为 {@code null}）
     * @return 是否已开始连接
     */
    public static boolean start(Object station, String stationName, String playUrl,
                                LiveStreamPlayer.Listener listenerOpt) {
        // 一起听房间与官方一起听：房间按曲目本体同步，本地挂一路私有的直播就是整房脱队 ——
        // 与镜像 refuseInternalPlayback 同一条纪律，直播在房间态被拦下。
        if (NeteaseListenTogetherService.isActive() || KuGouListenTogetherService.isActive()
                || MpPartyClient.inRoom()) {
            DownloadDynamicIsland.showPlaybackFailure(stationName,
                    "一起听房间中不能收听广播电台：房间播放以房主的队列为准");
            return false;
        }
        if (playUrl == null || playUrl.trim().isEmpty()) {
            DownloadDynamicIsland.showPlaybackFailure(stationName,
                    "这个频道暂时没有直播流（未开播或已下线）");
            return false;
        }
        // 互斥第一步：停掉内部引擎的当前播放（等它收尾，避免两路声源叠 0.5s）。
        // FM 会话标志一并退出：FM 的"队列/上一首禁用"等语义都挂在 personalFmActive 上，
        // 引擎停了标志还挂着，侧栏 FM 入口的高亮与 next() 的 FM 分支就会错乱。
        CloudMusic.exitPersonalFmSessionForLiveStream();
        CloudMusic.stopExistingPlayThreadForLiveStream();
        // 互斥第二步：停掉已有的直播（换台也走这里）。
        stop("切换频道");

        LiveStreamPlayer.Listener listener = new LiveStreamPlayer.Listener() {
            @Override public void onStarted(LiveStreamPlayer player) {
                MultiThreadingUtil.runOnMainThread(() -> DownloadDynamicIsland
                        .showModelOperationResult("广播电台", "正在收听「" + activeStationName + "」直播", true));
                if (listenerOpt != null) listenerOpt.onStarted(player);
            }

            @Override public void onStreamEnded(LiveStreamPlayer player, String reason) {
                // 只有仍是当前会话的断流才清场：换台瞬间旧流的遗言不该抹掉新台。
                if (ACTIVE.get() == player) {
                    clearState();
                    MultiThreadingUtil.runOnMainThread(() -> DownloadDynamicIsland
                            .showPlaybackFailure(activeStationName, "直播中断：" + reason + "，可重新点击频道重试"));
                }
                if (listenerOpt != null) listenerOpt.onStreamEnded(player, reason);
            }
        };

        LiveStreamPlayer player = new LiveStreamPlayer(playUrl, stationName, CloudMusic.getVolume(), listener);
        player.setOnClosed(() -> {
            // UI 主动 close（换台/停播）也走到这里：只有还是当前会话时才清状态。
            if (ACTIVE.get() == player) clearState();
        });
        activeStation = station;
        activeStationName = stationName == null ? "" : stationName;
        ACTIVE.set(player);
        player.start();
        return true;
    }

    /** 当前是否有直播在播（含暂停）。 */
    public static boolean isActive() {
        LiveStreamPlayer player = ACTIVE.get();
        return player != null && !player.isClosed();
    }

    /** 直播是否正在出声（暂停中为 false）。 */
    public static boolean isPlaying() {
        LiveStreamPlayer player = ACTIVE.get();
        return player != null && player.isPlaying();
    }

    /** 当前直播的频道模型（UI 判断「这张卡是不是正在播的那个」）；无直播为 {@code null}。 */
    public static Object activeStation() {
        return activeStation;
    }

    public static String activeStationName() {
        return activeStationName;
    }

    /** 已收听毫秒数；无直播返回 0。 */
    public static long elapsedMillis() {
        LiveStreamPlayer player = ACTIVE.get();
        return player == null || player.isClosed() ? 0L : player.elapsedMillis();
    }

    // ── 控制命令（底栏 / 快捷键入口）────────────────────────────────────────

    /** 暂停/继续当前直播；没有直播返回 {@code false}。 */
    public static boolean togglePause() {
        LiveStreamPlayer player = ACTIVE.get();
        if (player == null || player.isClosed()) return false;
        if (player.isPaused()) {
            player.resume();
            DownloadDynamicIsland.showModelOperationResult("广播电台",
                    "继续收听「" + activeStationName + "」", true);
        } else {
            player.pause();
            DownloadDynamicIsland.showModelOperationResult("广播电台",
                    "已暂停「" + activeStationName + "」（直播不追进度）", true);
        }
        return true;
    }

    /** 停掉当前直播并清场。幂等。 */
    public static void stop(String reason) {
        LiveStreamPlayer player = ACTIVE.getAndSet(null);
        activeStation = null;
        activeStationName = "";
        if (player != null && !player.isClosed()) {
            player.close();
        }
    }

    /** 跟随全局音量滑块；无直播时是 no-op。 */
    public static void applyVolume(float volume) {
        LiveStreamPlayer player = ACTIVE.get();
        if (player != null) player.setVolume(volume);
    }

    /** 游戏退出钩子：无条件停流（LiveStreamPlayer 的线程是守护线程，这里只是提前收线）。 */
    public static void shutdown() {
        stop("shutdown");
    }

    private static void clearState() {
        ACTIVE.set(null);
        activeStation = null;
        activeStationName = "";
    }
}
