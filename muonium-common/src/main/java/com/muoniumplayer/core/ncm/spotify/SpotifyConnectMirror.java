package com.muoniumplayer.core.ncm.spotify;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ExternalPlayback;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/**
 * Spotify Connect 的「镜像接管」：把用户自己的 Spotify 播放（手机/电脑/音箱上正在响的那个
 * 客户端）写进 {@link CloudMusic} 的全局播放态，让底栏进度条、全屏歌词页、灵动岛、桌面歌词
 * 全部跟着 Spotify 走——模组自己的音频引擎全程不出声。
 *
 * <p>协议与 {@link ExternalPlayback}（SMTC 系统播放器镜像）同源，只换供态源：SMTC 读
 * Windows 的媒体会话进程，这里轮询 {@code GET /me/player}（3 秒一拍 + 墙钟外推，见
 * {@link SpotifyConnectService.PlaybackState#positionMsNow()}）。两条铁律照抄：</p>
 * <ul>
 *   <li><b>一个系统里只有一个「正在播放」是真的</b>——接管时停掉内部播放并拒绝再起播
 *       （{@link #refuseInternalPlayback()}），也让位/请 SMTC 让位（谁后开谁接管）。</li>
 *   <li><b>只从 {@code CloudMusic.getSnapshot()} 这一个时钟入口分叉</b>——落地走
 *       {@link CloudMusic#adoptExternalMirrorTrack}，界面一处都不用改。</li>
 * </ul>
 *
 * <p><b>会话级</b>：接管状态不落盘，重开游戏后在设备面板重新接管。Spotify Connect 本来
 * 就是「此刻哪台设备在放」的实时状态，隔夜恢复没有意义。</p>
 *
 * <p><b>线程模型</b>：唯一的常驻线程是轮询（daemon，仅接管期间活着）；所有对 CloudMusic
 * 全局字段的写入经 {@link MultiThreadingUtil#runOnMainThread} 排到主线程，与播放线程
 * 同一套身份协议（先递增 generation 作废旧会话）。读侧（{@code positionMs()} 等）只碰
 * volatile 快照，渲染线程每帧调用零开销。</p>
 */
public final class SpotifyConnectMirror {

    private static final long POLL_INTERVAL_MS = 3_000L;
    private static final long MAX_POLL_INTERVAL_MS = 30_000L;
    /** 连续多少拍「什么都没播」（204）判定 Spotify 已停，自动让出。 */
    private static final int EMPTY_STREAK_LIMIT = 2;
    /** 连续多少拍硬失败（网络类）才放手：个别抖动不该打断接管。 */
    private static final int FAILURE_STREAK_LIMIT = 5;
    /** 提示的最小间隔：设备调不了音量这类事不该每帧刷一条。 */
    private static final long NOTICE_INTERVAL_MS = 3_000L;

    private static volatile boolean mirroring;
    private static volatile boolean running;
    /** 用户点「接管」时选的设备；空 = 跟随当前活动设备。轮询首拍据此转移一次。 */
    private static volatile String desiredDeviceId = "";

    private static volatile String appliedTrackKey = "";
    private static volatile String lastAnnouncedTrackKey = "";
    private static volatile long lastNoticeAtMillis;

    // 音量三值，与 SMTC 同构：wanted=拖动中的档位（必须立刻显示），sent=已发出的档位
    // （合并拖动，一帧一条命令会把设备刷爆），echo=设备报回来的真值。
    private static volatile float wantedVolume = Float.NaN;
    private static float sentVolume = Float.NaN;
    private static volatile float echoVolume = Float.NaN;
    private static volatile long volumeSendAtMs;
    private static volatile long volumeDragAtMs;
    private static final long VOLUME_SEND_INTERVAL_MS = 200L;
    private static final long VOLUME_DRAG_GRACE_MS = 1_500L;

    private SpotifyConnectMirror() {
    }

    // ── 读侧（渲染线程每帧调用） ─────────────────────────────────────────────

    public static boolean isMirroring() {
        return mirroring;
    }

    /** 此刻有没有一台 Spotify 设备在供态：接管开着，且最近一拍真的读到了状态。 */
    public static boolean hasSession() {
        return mirroring && SpotifyConnectService.lastKnownPlayback() != null;
    }

    public static boolean isPlaying() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        return state != null && state.playing;
    }

    /** 逐帧连续的播放位置（快照位置 + 正在播期间的墙钟外推）。 */
    public static long positionMs() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        return state == null ? 0L : state.positionMsNow();
    }

    public static long durationMs() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state != null && state.durationMs > 0L) return state.durationMs;
        Music applied = CloudMusic.currentlyPlaying;
        return applied == null ? 0L : applied.getDuration();
    }

    /** 当前正在供态的设备（命令的对象）；没有快照时交空串，让 Spotify 自己选活动设备。 */
    public static String currentDeviceId() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        return state == null ? "" : state.deviceId;
    }

    /**
     * 音量滑块该画到哪一格：拖动中的档位优先（要跟手），其次设备报回来的真值，最后回落
     * 调用方自己的值。语义与 {@code ExternalPlayback.volumeOr} 一致，是音量读数的分叉点。
     */
    public static float volumeOr(float internal) {
        if (!mirroring) return internal;
        if (!Float.isNaN(wantedVolume)) return wantedVolume;
        if (!Float.isNaN(echoVolume)) return echoVolume;
        return internal;
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    /**
     * 开始接管。{@code deviceId} 为空表示跟随当前活动设备。
     * 与 SMTC 的互斥在这里兑现：先请它让位，再开自己的轮询。
     */
    public static void start(String deviceId) {
        if (!SpotifyMusicService.isLoggedIn()) {
            notify("先登录 Spotify 再接管播放");
            return;
        }
        if (!SpotifyAuthStore.snapshot().hasPlaybackScopes()) {
            notify("Spotify 授权缺少播放控制权限，请在登录页重新授权");
            return;
        }
        ExternalPlayback.forceDetachIfMirroring();
        desiredDeviceId = deviceId == null ? "" : deviceId.trim();
        appliedTrackKey = "";
        lastAnnouncedTrackKey = "";
        wantedVolume = Float.NaN;
        sentVolume = Float.NaN;
        echoVolume = Float.NaN;
        mirroring = true;
        if (!running) {
            Thread thread = new Thread(SpotifyConnectMirror::pollLoop, "Spotify Connect Mirror");
            thread.setDaemon(true);
            running = true;
            thread.start();
        }
        notify("正在接管 Spotify 播放…");
    }

    /** 结束接管并清掉写进 CloudMusic 的播放态。{@code reasonForUser} 可为空。 */
    public static void stop(String reasonForUser) {
        boolean was = mirroring;
        mirroring = false;
        desiredDeviceId = "";
        appliedTrackKey = "";
        if (was) {
            MultiThreadingUtil.runOnMainThread(() -> {
                // 清场前再看一眼：如果接管结束的间隙里内部播放已经起来了，别把它误杀。
                if (!mirroring && CloudMusic.player == null) {
                    try {
                        CloudMusic.clearExternalMirrorState();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        if (reasonForUser != null && !reasonForUser.isEmpty()) notify(reasonForUser);
    }

    /** 面板状态行用的一句话。 */
    public static String statusLabel() {
        if (!mirroring) return "";
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state == null) return "等待 Spotify 设备响应…";
        String device = state.deviceName.isEmpty() ? "Spotify 设备" : state.deviceName;
        return device + (state.playing ? " · 播放中" : " · 已暂停");
    }

    // ── 反向控制（CloudMusic 的 toggle/next/prev/seek/volume 分叉到这些） ────

    public static void toggle() {
        final boolean wasPlaying = isPlaying();
        runAsync(() -> {
            if (wasPlaying) {
                SpotifyConnectService.pause(currentDeviceId());
            } else {
                SpotifyConnectService.resume(currentDeviceId());
            }
        });
    }

    public static void next() {
        runAsync(() -> SpotifyConnectService.next(currentDeviceId()));
    }

    public static void previous() {
        runAsync(() -> SpotifyConnectService.previous(currentDeviceId()));
    }

    public static boolean canSeek() {
        return mirroring && SpotifyConnectService.lastKnownPlayback() != null;
    }

    /**
     * 跳到绝对位置。本地不先改进度：命令真落地了，下一拍轮询自然会带回新位置——
     * 先跳会在设备拒绝时留下一个假进度（与 SMTC 的 seek 同一取舍）。
     */
    public static void seekTo(long positionMs) {
        if (!canSeek()) {
            noticeThrottled("Spotify 设备没就绪，暂时不能拖动进度");
            return;
        }
        runAsync(() -> SpotifyConnectService.seek(positionMs, currentDeviceId()));
    }

    /** 相对跳转：目标按当前外推位置夹进 [0, 时长]，交给 {@link #seekTo}。 */
    public static void seekBy(float deltaMillis) {
        long target = Math.max(0L, Math.min(durationMs(), positionMs() + (long) deltaMillis));
        seekTo(target);
    }

    /**
     * 玩家拖音量条那一刻的入口。返回 true 表示「这根滑块此刻归 Spotify 设备管」，
     * 调用方（{@code CloudMusic.setVolume}）因此不去写模组自己那份持久化音量。
     */
    public static boolean writeVolume(float level) {
        if (!mirroring) return false;
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state == null || !state.supportsVolume || state.volumePercent < 0) {
            noticeThrottled("这台 Spotify 设备不可调音量");
            return true;
        }
        wantedVolume = Math.max(0f, Math.min(1f, level));
        volumeDragAtMs = System.currentTimeMillis();
        return true;
    }

    /** 镜像开着时拒绝内部起播，理由与 SMTC 一致：两个「正在播放」会打架。 */
    public static boolean refuseInternalPlayback() {
        if (!mirroring) return false;
        noticeThrottled("Spotify 接管中：先在「设备控制」断开，才能用内置播放");
        return true;
    }

    // ── 轮询 ────────────────────────────────────────────────────────────────

    private static void pollLoop() {
        long interval = POLL_INTERVAL_MS;
        int emptyStreak = 0;
        int failureStreak = 0;
        boolean transferPending = !desiredDeviceId.isEmpty();
        while (running) {
            sleepSlice(interval);
            if (!mirroring || !running) break;
            if (!SpotifyMusicService.isLoggedIn()) {
                stop("Spotify 登录已失效，接管结束");
                break;
            }
            try {
                SpotifyConnectService.PlaybackState state = SpotifyConnectService.fetchPlayback();
                if (state == null) {
                    // 204：没有任何设备在播。偶尔一拍可能只是切歌间隙，连着两拍才放手。
                    emptyStreak++;
                    failureStreak = 0;
                    if (emptyStreak >= EMPTY_STREAK_LIMIT) {
                        stop("Spotify 已停止播放，接管结束");
                        break;
                    }
                    continue;
                }
                emptyStreak = 0;
                failureStreak = 0;
                interval = POLL_INTERVAL_MS;
                SpotifyConnectService.recordPlayback(state);
                if (transferPending) {
                    transferPending = false;
                    String wanted = desiredDeviceId;
                    if (!wanted.isEmpty() && !wanted.equals(state.deviceId)) {
                        SpotifyConnectService.transfer(wanted, true);
                        // 转移后这一拍的状态已是旧的，下一拍再落地，不做额外处理。
                        continue;
                    }
                }
                adoptEchoVolume(state);
                tickVolumeSend();
                String trackKey = state.trackKey();
                if (!trackKey.isEmpty() && !trackKey.equals(appliedTrackKey)) {
                    applyTrack(trackKey, state.track);
                }
            } catch (Throwable failure) {
                String message = SpotifyConnectService.userMessage(failure);
                if (isFatal(failure)) {
                    stop(message.isEmpty() ? "Spotify 接管已结束" : message);
                    break;
                }
                failureStreak++;
                interval = nextInterval(interval);
                if (failureStreak >= FAILURE_STREAK_LIMIT) {
                    stop("Spotify 连接持续失败，接管结束");
                    break;
                }
            }
        }
        running = false;
    }

    private static boolean isFatal(Throwable failure) {
        if (!(failure instanceof SpotifyWebApiClient.SpotifyApiException)) return false;
        SpotifyWebApiClient.SpotifyApiException api = (SpotifyWebApiClient.SpotifyApiException) failure;
        if (api.reauthRequired) return true;
        // 403 = 缺 scope 或非 Premium：这不是抖动，重试多少次都是同一个答案。
        return api.statusCode == 403;
    }

    /** 指数退避：3s 起步每次翻倍，封顶 30s（异常小的当前值按「基准 ×2」兜底）。 */
    static long nextInterval(long current) {
        return Math.min(MAX_POLL_INTERVAL_MS, Math.max(POLL_INTERVAL_MS, current * 2));
    }

    // ── 落地 ────────────────────────────────────────────────────────────────

    /** 换歌：解析出的曲目交给主线程按镜像协议落地。无论落地成败先记账，同一首不重复解析。 */
    private static void applyTrack(String trackKey, SpotifyMusicService.Track track) {
        appliedTrackKey = trackKey;
        final Music music = Music.fromSpotifyTrack(track);
        if (music == null) return;
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!mirroring) return;
            try {
                CloudMusic.adoptExternalMirrorTrack(music);
                if (!trackKey.equals(lastAnnouncedTrackKey)) {
                    lastAnnouncedTrackKey = trackKey;
                    String artist = music.getArtists() == null || music.getArtists().isEmpty()
                            ? "" : music.getArtists().get(0).getName();
                    notify("正在镜像 Spotify：" + music.getName()
                            + (artist.isEmpty() ? "" : " - " + artist));
                }
            } catch (Throwable failure) {
                System.err.println("[Music/Spotify] 镜像落地失败: " + failure);
            }
        });
    }

    // ── 音量 ────────────────────────────────────────────────────────────────

    /** 收设备的音量回读。刚拖完的宽限窗口里不采信，避免滑块被写之前的旧值拽回去。 */
    private static void adoptEchoVolume(SpotifyConnectService.PlaybackState state) {
        if (!state.supportsVolume || state.volumePercent < 0) return;
        float echo = state.volumePercent / 100f;
        long now = System.currentTimeMillis();
        if (now - volumeDragAtMs <= VOLUME_DRAG_GRACE_MS
                && !Float.isNaN(wantedVolume) && Math.abs(echo - wantedVolume) >= .001f) {
            return;
        }
        echoVolume = echo;
    }

    /** 轮询线程每拍调：按节流节奏把拖动的档位发给设备。 */
    private static void tickVolumeSend() {
        float wanted = wantedVolume;
        if (Float.isNaN(wanted)) return;
        if (!Float.isNaN(sentVolume) && Math.abs(wanted - sentVolume) < .001f) return;
        long now = System.currentTimeMillis();
        if (now - volumeSendAtMs < VOLUME_SEND_INTERVAL_MS) return;
        volumeSendAtMs = now;
        sentVolume = wanted;
        final String device = currentDeviceId();
        final int percent = Math.round(wanted * 100f);
        runAsync(() -> {
            try {
                SpotifyConnectService.setVolume(percent, device);
            } catch (Throwable failure) {
                noticeThrottled(SpotifyConnectService.userMessage(failure));
            }
        });
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private static void runAsync(ThrowingRunnable command) {
        MultiThreadingUtil.runAsync(() -> {
            if (!mirroring) return;
            try {
                command.run();
            } catch (Throwable failure) {
                String message = SpotifyConnectService.userMessage(failure);
                if (isFatal(failure)) {
                    stop(message.isEmpty() ? "Spotify 接管已结束" : message);
                } else {
                    System.err.println("[Music/Spotify] 控制命令失败: " + failure.getMessage());
                }
            }
        });
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 长睡眠切成小片，stop() 之后至多半秒内线程就退出。 */
    private static void sleepSlice(long totalMillis) {
        long slept = 0L;
        while (running && slept < totalMillis) {
            long slice = Math.min(300L, totalMillis - slept);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            slept += slice;
        }
    }

    private static void notify(String detail) {
        try {
            DownloadDynamicIsland.showExternalMedia(detail, false);
        } catch (Throwable ignored) {
            // 岛没起来也不该影响镜像本身。
        }
    }

    private static void noticeThrottled(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastNoticeAtMillis < NOTICE_INTERVAL_MS) return;
        lastNoticeAtMillis = now;
        notify(detail);
    }
}
