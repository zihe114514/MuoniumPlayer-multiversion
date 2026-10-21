package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import repackage.processing.sound.Engine;
import com.muoniumplayer.api.enums.EnumChatColor;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.kugou.KuGouMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.AutomixTransitionAnimation;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.StringUtils;
import com.muoniumplayer.core.utils.other.WrappedInputStream;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author MuoniumPlayer Contributors
 * @since 6/16/2023 9:34 AM
 * <p>
 * S3 适配：仅保留音频状态机 + 播放线程 + 下载/解码链（play/pause/seek/next/prev/stop/loop/shuffle）。
 * 歌词（LyricLine/LyricParser/MusicLyricsPanel）、封面（TextureManager/Textures/GaussianKernel）、
 * 登录二维码（QRCodeGenerator/NCMScreen）等 UI/渲染/歌词依赖分别归入 S4/S5/S6，
 * 相关方法在 S3 阶段以 no-op 占位并标注，届时从原项目原样回填。
 */
public class CloudMusic implements SharedConstants {

    @Getter
    private static final Map<String, String> headers = new HashMap<>();

    // 播放会话 generation：每次开始新歌递增，用于逻辑取消（logical cancellation）旧歌词任务。
    // 不要依赖 Thread.interrupt 判断歌词任务是否过期；结果提交前校验 generation + songId。
    public static final AtomicLong generation = new AtomicLong(0);

    /**
     * 当前播放会话（volatile：播放线程写、主线程读，保证跨线程可见性）。
     */
    public static volatile PlaybackSession currentSession = null;

    public static volatile AudioPlayer player;
    /**
 * 在 automix 交接期间正在淡出的播放牌。它已不再是活动播放器，但仍在出声，
 * 所以音量变化也必须送达它。
 */
    static volatile AudioPlayer fadingOutPlayer;
    private static final Object PLAYER_STATE_LOCK = new Object();
    private static final long PLAY_THREAD_JOIN_TIMEOUT_MS = 1000L;
    // 当前播放列表
    public static List<Music> playList = new ArrayList<>();
    public static int curIdx = 0;
    public static volatile Music currentlyPlaying;
    /**
 * 启动当前队列的真实网易云歌单；搜索/临时列表时为 null。
 */
    public static volatile PlayList currentPlaylistContext;
    public static Thread playThread;
    /** 当前曲目在 URL 解析、下载、解码或播放器初始化失败后为 true。 */
    private static volatile boolean awaitingPlaybackAction;

    public static volatile User profile;
    public static volatile List<PlayList> playLists;
    public static volatile List<Long> likeList;
    /**
 * 来自 /api/v1/cloud/get 的 ID，用于在列表中标记同一首歌。
 */
    private static volatile Set<Long> userCloudSongIds = Collections.emptySet();

    /**
 * 防止前一个刷新请求仍在运行时发起重复的账号刷新请求。
 */
    private static final AtomicBoolean NETEASE_REFRESHING = new AtomicBoolean(false);

    public static volatile PlayMode playMode = PlayMode.Sequential;
    /**
 * 当前队列是否属于独立的私人 FM 会话。
 */
    private static volatile boolean personalFmActive;
    private static volatile PlayMode playModeBeforePersonalFm = PlayMode.Sequential;

    public static Quality quality = Quality.LOSSLESS;

    public static final List<LyricLine> lyrics = new CopyOnWriteArrayList<>();
    public static volatile LyricLine currentLyric = null;
    /**
 * {@link #lyrics} 所属曲目的稳定键，未提交任何内容时为 {@code null}。
 *
 * <p>歌词列表是全局的，只有在加载成功时才会被替换，所以加载失败后它仍保留着
 * <em>上一首</em>曲目的时间轴。任何根据这些时间戳得出结论的逻辑——automix 会从最后一句
 * 唱词选取混出点——都必须知道这些歌词属于谁，否则就会悄悄把一首歌的结构套到另一首上。</p>
 */
    public static volatile String lyricsTrackKey = null;
    /** Increments on timeline publication/clear, including reloading the same song. */
    public static volatile long lyricsRevision;

    /**
     * 当前提交的歌词时间轴是否真的属于正在出声的那首歌。
     *
     * <p>切歌（普通切换与 automix 无缝交接都一样）会先把 {@link #currentlyPlaying} 和 {@link #player}
     * 换成新歌，而歌词要等一次网络请求才回来。这中间的几百毫秒到数秒里，{@link #lyrics} 还是<b>上一首</b>
     * 的时间轴，却已经在被<b>新歌</b>的音频时钟驱动 —— 表现就是切歌瞬间歌词跳到一个毫不相干的位置、
     * 或者旧歌词继续按新歌进度滚。所有以时间轴下结论的地方都必须先过这一关。</p>
     */
    public static boolean lyricsMatchCurrentTrack() {
        Music current = currentlyPlaying;
        String owner = lyricsTrackKey;
        return current != null && owner != null && owner.equals(current.getStableKey());
    }
    public static boolean hasTransLyrics = false;
    public static boolean hasRomanization = false;
    public static boolean haveNoWords = false;

    public static final File COOKIE_FILE = ConfigPaths.NETEASE_COOKIE;

    @SneakyThrows
    public static void initNCM() {
        String cookie = getCookieFromFileOrOptions();
        NeteaseMusicService.initialize(cookie);
        // Cadence 的凭证存储可能持有从新版账号管理中保存的网易云 Cookie。
        // 初始化后重新读取 Options，保证旧资料/歌单加载链路仍可复用。
        if (cookie.isEmpty()) {
            cookie = OptionsUtil.getCookie();
        }

        if (cookie.isEmpty()) {
            System.out.println("[NCM] Not logged in.");
        } else {
            loadNCM(cookie);
        }

        // 酷狗的红心索引是「可再生缓存」：启动时先从磁盘恢复（一次文件读、不发请求），
        // 已登录时再在后台把服务端那份拉全。少了这一步，重启后所有已收藏的歌都会画成未收藏，
        // 用户再点一次就是重复收藏——网易云那边由 likeList 天然不会有这个问题，这是酷狗补齐的对等项。
        KuGouLikedCache.restoreFromDisk();
        KuGouMusicService.warmLikedSongsAsync();
    }

    @SneakyThrows
    private static String loadCookie() {
        if (!COOKIE_FILE.exists()) {
            return "";
        }

        List<String> cookieLines = Files.readAllLines(COOKIE_FILE.toPath());
        return cookieLines.isEmpty() ? "" : cookieLines.get(0);
    }

    private static String getCookieFromFileOrOptions() {
        String optionCookie = OptionsUtil.getCookie();
        return optionCookie == null || optionCookie.isEmpty() ? loadCookie() : optionCookie;
    }

    public static void loadNCM(String cookie) {
        OptionsUtil.setCookie(cookie);
        // 获取用户信息
        profile = NeteaseAccountRepository.getUserProfile();

        if (profile == null) {
            return;
        }

        System.out.printf("[NCM] Logged in as %s(%s)\n", profile.getName(), profile.getId());

        // 听歌打卡组件（zstd-jni，2MB JNI 包，不进主包）在登录这一刻触发下载：
        // 扫码 / Cookie / 启动自动恢复三条登录路都汇聚在 loadNCM，这里是唯一触发点。
        // 打卡按天记账，组件就位前上报轮次安静跳过，就位后下一轮播放自然带上。
        ZstdNativeDownload.ensureJar();

        if (!OptionsUtil.getCookie().isEmpty()) {
            onStop();
        }

        CloudMusic.playLists = NeteaseAccountRepository.loadUserPlaylists(profile);
        System.out.printf("[NCM] Loaded %s playlists\n", playLists.size());

        likeList = NeteaseAccountRepository.loadLikeList(profile);
        Set<Long> loadedCloudSongIds = NeteaseAccountRepository.loadCloudSongIds();
        if (loadedCloudSongIds != null) {
            userCloudSongIds = loadedCloudSongIds;
            System.out.printf("[NCM] Loaded %s cloud-drive song markers%n", userCloudSongIds.size());
        }
        NCMScreen.getInstance().markDirty();
        MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().reloadCurrentPanel());
    }

    /**
     * Attempts to claim the single网易云刷新 slot.
     */
    public static boolean beginNeteaseRefresh() {
        return NETEASE_REFRESHING.compareAndSet(false, true);
    }

    public static void endNeteaseRefresh() {
        NETEASE_REFRESHING.set(false);
    }

    /**
     * 重新加载已登录网易云账号数据，且不触发初始登录的副作用。
     * 网络工作在调用者的线程上执行。已发布的快照仅在
     * 所有请求全部成功完成后才被替换，因此瞬时故障会保留旧数据。
     */
    public static NeteaseRefreshResult refreshNeteaseAccountData() {
        long startedAt = System.currentTimeMillis();
        String cookie = getCookieFromFileOrOptions();
        if (cookie == null || cookie.trim().isEmpty()) {
            return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, "未登录网易云账号");
        }

        try {
            OptionsUtil.setCookie(cookie);
            User refreshedProfile = NeteaseAccountRepository.getUserProfile();
            if (refreshedProfile == null) {
                return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, "登录状态已失效");
            }

            List<PlayList> refreshedPlaylists = NeteaseAccountRepository.loadUserPlaylistsStrict(refreshedProfile);
            List<Long> refreshedLikeList = NeteaseAccountRepository.loadLikeList(refreshedProfile);
            Set<Long> refreshedCloudSongIds = NeteaseAccountRepository.loadCloudSongIds();

            profile = refreshedProfile;
            playLists = refreshedPlaylists;
            likeList = refreshedLikeList;
            if (refreshedCloudSongIds != null) {
                userCloudSongIds = refreshedCloudSongIds;
            }
            return NeteaseRefreshResult.success(System.currentTimeMillis() - startedAt,
                    refreshedPlaylists.size(), refreshedLikeList.size());
        } catch (Throwable throwable) {
            String detail = throwable.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = "网络请求失败";
            }
            System.err.println("[NCM] Playlist refresh failed: " + detail);
            return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, detail);
        }
    }

    /**
 * 返回已登录用户的网易云网盘中是否存在某首网易云歌曲。
 */
    public static boolean isUserCloudSong(long songId) {
        return songId > 0L && userCloudSongIds.contains(songId);
    }

    @lombok.Getter
    public static final class NeteaseRefreshResult {
        private final boolean success;
        private final int playlistCount;
        private final int likeCount;
        private final long elapsedMillis;
        private final String message;

        private NeteaseRefreshResult(boolean success, long elapsedMillis, int playlistCount,
                                     int likeCount, String message) {
            this.success = success;
            this.elapsedMillis = elapsedMillis;
            this.playlistCount = playlistCount;
            this.likeCount = likeCount;
            this.message = message;
        }

        private static NeteaseRefreshResult success(long elapsedMillis, int playlistCount, int likeCount) {
            return new NeteaseRefreshResult(true, elapsedMillis, playlistCount, likeCount, "刷新成功");
        }

        private static NeteaseRefreshResult failure(long elapsedMillis, String message) {
            return new NeteaseRefreshResult(false, elapsedMillis, 0, 0,
                    message == null || message.trim().isEmpty() ? "刷新失败" : message.trim());
        }
    }

    @SneakyThrows
    public static void onStop() {
        File parent = COOKIE_FILE.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create MuoniumPlayer config directory: " + parent.getAbsolutePath());
        }
        Files.write(COOKIE_FILE.toPath(), OptionsUtil.getCookie().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Getter
    public enum PlayMode {
        Random("F", "随机播放"),
        LoopInList("I", "列表循环"),
        LoopSingle("L", "单曲循环"),
        Sequential("G", "顺序播放");

        private final String icon;
        private final String displayName;

        PlayMode(String icon, String displayName) {
            this.icon = icon;
            this.displayName = displayName;
        }
    }

    /**
     * 供播放器控制栏使用的播放模式循环：顺序播放 → 随机播放 → 单曲循环。
     * 列表循环保留给旧数据/兼容逻辑，不再作为主播放器的可见模式。
     */
    public static synchronized PlayMode cyclePlayMode() {
        if (MpPartyClient.interceptPlayModeCycle()) return playMode;
        // 私人 FM 拥有自己的队列顺序；普通模式不会改动它。
        if (personalFmActive) return playMode;
        switch (playMode) {
            case Sequential:
            case LoopInList:
                playMode = PlayMode.Random;
                prepareRandomQueue();
                break;
            case Random:
                playMode = PlayMode.LoopSingle;
                break;
            case LoopSingle:
            default:
                playMode = PlayMode.Sequential;
                break;
        }
        return playMode;
    }

    /**
     * 切入随机模式时保留正在播放的歌曲，并把它放在随机队列的开头。
     * 这样当前歌曲不会被中断，下一首立即按随机顺序播放。
     */
    private static void prepareRandomQueue() {
        if (playList == null || playList.size() < 2) {
            return;
        }

        Music currentSong = currentlyPlaying;
        int currentIndex = currentSong == null ? curIdx : playList.indexOf(currentSong);
        if (currentIndex < 0 || currentIndex >= playList.size()) {
            currentIndex = 0;
        }

        Music anchorSong = playList.get(currentIndex);
        List<Music> shuffledSongs = new ArrayList<>(playList);
        shuffledSongs.remove(anchorSong);
        Collections.shuffle(shuffledSongs);
        shuffledSongs.add(0, anchorSong);

        // 保持 List 实例不变，播放线程据此识别“是否切换了播放列表”。
        // 使用逐项覆盖而非 clear/addAll，避免播放线程在极短窗口读取到空队列。
        Collections.copy(playList, shuffledSongs);
        curIdx = 0;
    }

    private static void reshuffleRandomQueueForNextCycle(List<Music> songs) {
        if (songs.size() < 2) {
            return;
        }

        Music justFinished = currentlyPlaying;
        Collections.shuffle(songs);

        // 避免一个随机轮次结束后立刻又播放刚刚结束的那首。
        if (justFinished != null && songs.get(0).equals(justFinished)) {
            for (int i = 1; i < songs.size(); i++) {
                if (!songs.get(i).equals(justFinished)) {
                    Collections.swap(songs, 0, i);
                    break;
                }
            }
        }
    }

    // 以下这些 上一首/下一首 以及 播放/暂停 的逻辑我写完我自己都看不懂
    // 但就是能用
    public static volatile boolean dontAdd = false;

    public static void prev() {
        if (ExternalPlayback.isMirroring()) {
            ExternalPlayback.previous();
            return;
        }
        if (MpPartyClient.interceptPrev()) return;
        // 私人 FM 没有"上一首"。原先的 curIdx <= 0 在实践中等于永远拦住：FM 队列以前每首歌都是一条
        // 独立的单曲队列。现在预备下一首会让队列真的长出历史，如果不改成无条件拦住，FM 就凭空多出
        // 一个官方客户端里并不存在的回退按钮。
        if (personalFmActive) return;
        if (!awaitingPlaybackAction && requestManualAutomixStep(-1)) return;

        updatePlayCountIfNeeded();

        if (!canPlayPrevious() || playList.isEmpty()) {
            return;
        }

        if (awaitingPlaybackAction) {
            curIdx--;
            restartFromUserTrackSelection();
            return;
        }

        if (player != null) {
            prepareForTrackChange();
            curIdx--;
            stopCurrentPlayback();
        }
    }

    private static boolean canPlayPrevious() {
        if (curIdx - 1 >= 0) {
            return true;
        }

        if (playMode == PlayMode.LoopInList || playMode == PlayMode.Random || playMode == PlayMode.LoopSingle) {
            curIdx = playList.size();
            return true;
        }

        return false;
    }

    public static void next() {
        // 镜像态下「下一首」属于外部播放器：我们的队列是空的，走下面的路径会变成
        // 在本机悄悄起播一首自己的歌，而玩家的意图是让外部播放器切歌。
        if (ExternalPlayback.isMirroring()) {
            ExternalPlayback.next();
            return;
        }
        if (MpPartyClient.interceptNext()) return;
        // FM 只在「队列里真的没有下一首」（预取还没回来）时才整体换批：
        // 换批走 playFm → stopExistingPlayThread，正在放的歌当场断掉、静音等网络。
        // 预取已把下一首接进队列时，落进下面的 requestManualSelection（原曲不停、
        // 就绪才交接）——FM 的 automix 因此与普通歌单同一条手动路径。
        if (personalFmActive && playList.isEmpty()) return;
        if (personalFmActive && curIdx + 1 >= playList.size()) {
            PersonalFmManager.requestNextBatchAsync();
            return;
        }
        if (playList.isEmpty()) {
            return;
        }

        // 手动切歌优先交给已经预解码好的 automix 备用轨，避免"关闭播放器 → 重新解析下载解码"的静音断点。
        // 必须放在 canPlayNext() 之前：后者在循环模式走到队尾时会把 curIdx 改成 -1，而交接是在播放线程
        // 的下一个 10ms tick 上完成的，中途让 curIdx 停在 -1 会让读 playList.get(curIdx) 的界面越界。
        if (!awaitingPlaybackAction && player != null && requestManualAutomixSkip()) {
            return;
        }

        if (!awaitingPlaybackAction && requestManualAutomixStep(1)) return;

        if (!canPlayNext()) {
            return;
        }

        if (awaitingPlaybackAction) {
            curIdx++;
            restartFromUserTrackSelection();
            return;
        }

        if (player != null) {
            updatePlayCountIfNeeded();
            prepareForTrackChange();
            curIdx++;
            stopCurrentPlayback();
        }
    }


    // ─────────────────────────── 下一首播放（用户指定队列） ───────────────────────────
    //
    // 主流播放器允许用户把几首曲目钉在歌单将要播放的内容前面，
    // 播完后再回到歌单。这正是对活队列的一次插入：被选中的曲目紧跟在当前曲目之后，
    // 按选择顺序排列，队列或播放模式的其他任何内容都不变。
    // {@link #playList} 本来就是源列表的私有副本（见 startPlaybackList），
    // 所以用户正在浏览的歌单永远不会被改动，
    // 而从不使用该功能的用户得到的仍是逐字节不变的旧行为。

    /** 保护插入操作，防止两次快速点击交错导致顺序错乱。 */
    private static final Object PLAY_NEXT_LOCK = new Object();
    /**
 * 用户「下一首播放」的账本，只记曲目身份，一格位置都不记。
 *
 * <p>这里没有绝对下标可维护，正是这一处改动要买的东西：上一版记的是用户块在 {@code playList}
 * 里的尾下标，而队列会被整条换掉、被随机模式就地重洗、也会在列表循环走到头时让播放指针跳回 0，
 * 那些地方没有一个会去挪它。它一旦停在很远处，{@code 尾下标 - curIdx} 就把几十上百格正常歌序
 * 报成用户排过的队。现在待播段每次从活队列现算，见 {@link PlayNextLedger}。</p>
 */
    /**
     * 「下一首播放」的账本。
     *
     * <p>包可见只为测试能把「用户排过的歌」登记上（{@code QueueReorderIdentityTest} 要在不启动
     * Minecraft、不碰灵动岛的前提下搭出一段真实待播队列）；运行期没有任何外部访问。</p>
     */
    static final PlayNextLedger playNextLedger = new PlayNextLedger();
    /**
 * 每次队列变化时递增。Automix 会预解码它预测的下一首，所以用户改变主意之前
 * 预备好的播放牌必须被丢弃。
 */
    static volatile long queueRevision;

    /** 用户加入队列的曲目是否仍在等待播放。 */
    static boolean hasQueuedNext() {
        synchronized (PLAY_NEXT_LOCK) {
            return playNextLedger.pendingRun(playList, curIdx) > 0;
        }
    }

    /** 仍在等待播放的用户队列曲目数量；用户队列为空时为 0。 */
    public static int getQueuedNextCount() {
        synchronized (PLAY_NEXT_LOCK) {
            return playNextLedger.pendingRun(playList, curIdx);
        }
    }

    /** 该曲目是否是当前用户队列中正在等待播放的曲目之一。 */
    public static boolean isQueuedNext(Music song) {
        synchronized (PLAY_NEXT_LOCK) {
            return playNextLedger.isPending(playList, curIdx, song);
        }
    }

    /**
     * 一首歌开播了，顺手把它从待播账上销掉。由播放线程在每个起播点调用。
     *
     * <p>账本记的是身份而不是位置，所以「播过的歌还留在账上」通常不会立刻出问题：它已经不在
     * 当前曲目后面那一段里，读数自然不算它。真正会被它坑到的只有一种情况——同一首歌在队列里
     * 出现两次，前一首刚播完，后一首恰好紧跟在当前曲目后面，那一格就会被误认成用户排过的队。
     * 在起播这一刻销账，就把这个口子一起堵上。</p>
     */
    static void noteQueuedTrackStarted(Music song) {
        synchronized (PLAY_NEXT_LOCK) {
            playNextLedger.forget(song);
        }
    }

    /**
 * 将 {@code song} 排入队列，在当前曲目之后播放，排在所有已按此方式排队的曲目之后。
 *
 * @return 曲目是否已入队；当没有可插入的队列时，是否直接开始播放
 */
    public static boolean playNext(Music song) {
        if (song == null) return false;
        if (MpPartyClient.interceptPlayNext(song)) return true;

        int rank;
        boolean startDirectly;
        synchronized (PLAY_NEXT_LOCK) {
            // 队列在锁里重读一次：上面那行拦截回调可能已经把整条 playList 换掉了。旧写法拿着
            // 锁外捕获的实例往里插，歌单换得快的话那一首就进了一条没人播的孤儿列表，
            // 而通知照样告诉用户「已排在第 N 位」。
            List<Music> queue = playList;
            startDirectly = queue == null || queue.isEmpty() || currentlyPlaying == null || curIdx < 0;
            if (startDirectly) {
                rank = 1;
            } else {
                int insertAt = playNextLedger.insertIndex(queue, curIdx);
                queue.add(insertAt, song);
                playNextLedger.remember(song);
                queueRevision++;
                // 通知里那个「第几位」数的是真正的待播段，不再是从 curIdx 到某个尾下标的距离。
                rank = Math.max(1, insertAt - Math.max(0, curIdx));
            }
        }

        if (startDirectly) {
            // 当前没有在播放，所以没有"下一首"可以插入。直接开始播放这首歌正是主流播放器
            // 在这里的做法，比什么都不做更有用。
            play(Collections.singletonList(song), 0);
            DownloadDynamicIsland.showQueuedNextStarted(song.getName());
            return true;
        }
        DownloadDynamicIsland.showQueuedNext(song.getName(), rank);
        return true;
    }

    /**
     * 当前用户队列里还没播到的曲目，按播放顺序返回快照。给"下一首播放"列表用：界面每帧读一次，
     * 不能拿到会被后台修改的活列表。
     */
    public static List<Music> getQueuedNextSongs() {
        synchronized (PLAY_NEXT_LOCK) {
            return playNextLedger.pending(playList, curIdx);
        }
    }

    /**
     * 在用户队列内部重新排序：把第 {@code fromOffset} 个待播曲目移动到第 {@code toOffset} 个位置
     * （下标都相对于队列自身，0 就是下一首要播的那个）。
     *
     * <p>只搬动紧跟在 {@code curIdx} 之后那一段里的元素，所以当前播放位置和队列长度都不变，
     * 正在播的曲目不受影响。整个搬动持有 {@link #PLAY_NEXT_LOCK}，与 {@link #playNext} 的插入互斥；
     * {@code queueRevision} 递增会让 automix 丢弃按旧顺序预备好的那一路。段内都是用户排过的歌，
     * 所以账本（记身份）不需要跟着改。</p>
     *
     * @return 是否真的发生了移动
     */
    public static boolean moveQueuedNext(int fromOffset, int toOffset) {
        if (fromOffset == toOffset) return false;
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int size = pendingRunLocked();
            if (fromOffset < 0 || fromOffset >= size) return false;
            if (toOffset < 0 || toOffset >= size) return false;

            int from = current + 1 + fromOffset;
            if (queue == null || from >= queue.size() || queue.get(from) == null) return false;
            Music song = queue.remove(from);
            queue.add(current + 1 + toOffset, song);
            queueRevision++;
            return true;
        }
    }

    /**
     * 一次性按给定排布重排待播队列<b>的前 {@code order.length} 格</b>——进阶模式「一键排列」
     * （{@link QueueFlowSorter}）的落地口。
     *
     * <p>{@code order[i] = j} 表示新的第 i 格放扫描快照里的第 j 首，必须是 0..n-1 的一个<b>全排列</b>。
     * 逐对调用 {@link #moveQueuedNext(int, int)} 也能排出来，但每搬一次都会递增
     * {@code queueRevision}，而预备线程每一次看到版本号变化就丢掉一张已经解码到一半的牌——
     * 一次排列会被烧成几十次重下重解。所以这里整份持有同一把锁、只递增一次。</p>
     *
     * <p>拒绝半排列：接受它之后队列里会凭空少几首歌或多出 null，而这两样都要等下一首才暴露。</p>
     *
     * <h3>{@code expected} 是这份排布的有效期</h3>
     * 排布是在后台按<b>几十秒前</b>的那份队列算出来的，中间用户完全可能又插了一首「下一首播放」、
     * 或者当前曲目刚好结束让 {@code curIdx} 前移一格。只比长度挡不住「少一首又多一首」那种变化，
     * 所以逐格比<b>身份</b>：任何一格对不上就整个不改。同一段快照也决定了改动的范围 —— 排了
     * 前 n 格，第 n 格之后的歌原位不动。
     *
     * @param order    新的第 i 格取快照里的第几首
     * @param expected 算出这份排布时那份待播前缀的快照，长度与 {@code order} 一致
     * @return 是否真的改了顺序
     */
    public static boolean reorderQueuedNext(int[] order, List<Music> expected) {
        if (order == null || order.length < 2) return false;
        if (expected == null || expected.size() != order.length) return false;
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int size = pendingRunLocked();
            if (queue == null || size < order.length) return false;
            for (int i = 0; i < order.length; i++) {
                int slot = current + 1 + i;
                if (slot >= queue.size()) return false;
                if (queue.get(slot) != expected.get(i)) return false;
            }
            boolean[] seen = new boolean[order.length];
            for (int index : order) {
                if (index < 0 || index >= order.length || seen[index]) return false;
                seen[index] = true;
            }
            java.util.ArrayList<Music> reordered = new java.util.ArrayList<>(order.length);
            for (int index : order) {
                Music song = queue.get(current + 1 + index);
                if (song == null) return false;
                reordered.add(song);
            }
            // 只覆盖前 order.length 格：后面的那些这次没量，搬它们就等于凭猜测改用户的顺序。
            for (int i = 0; i < order.length; i++) queue.set(current + 1 + i, reordered.get(i));
            queueRevision++;
            return true;
        }
    }

    /**
     * 从用户队列里移除第 {@code offset} 个待播曲目（0 就是下一首要播的那个）。
     *
     * <p>被移除的曲目是当初 {@link #playNext(Music)} 插进来的那一条，所以从 {@code playList} 里删掉
     * 就回到了插入前的顺序；同时要把它的身份从账上销掉，否则同一首歌在列表里别处再出现时会被当成
     * 用户还排着队。正在播放的曲目和 {@code curIdx} 都不受影响。</p>
     *
     * @return 是否真的移除了一条
     */
    public static boolean removeQueuedNext(int offset) {
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            if (offset < 0 || offset >= pendingRunLocked()) return false;
            int index = current + 1 + offset;
            if (queue == null || index >= queue.size()) return false;

            playNextLedger.forget(queue.remove(index));
            queueRevision++;
            return true;
        }
    }

    /**
     * 清空用户队列：把所有待播曲目从 {@code playList} 里删掉，恢复到没用过"下一首播放"的顺序。
     *
     * @return 被移除的曲目数量
     */
    public static int clearQueuedNextSongs() {
        synchronized (PLAY_NEXT_LOCK) {
            int removed = playNextLedger.dropPending(playList, curIdx);
            if (removed > 0) queueRevision++;
            return removed;
        }
    }

    /**
     * 忘记用户队列；曲目本身保留在被插入的位置。
     *
     * <p>包内可见是因为「整条队列被换掉」这件事不止 {@link #startPlaybackList} 一处会做（镜像接管
     * 也会把 {@code playList} 换成一条空列表），而每一处都必须同时作废账本 —— 留着一批指向已经
     * 不存在的格子的身份，下一次读数就可能在那儿凭空长出一段待播。</p>
     */
    static void clearQueuedNext() {
        synchronized (PLAY_NEXT_LOCK) {
            playNextLedger.clear();
        }
        queueRevision++;
    }

    /** 当前待播段有几格。调用方必须已经持有 {@link #PLAY_NEXT_LOCK}。 */
    private static int pendingRunLocked() {
        return playNextLedger.pendingRun(playList, curIdx);
    }

    /**
     * 把私人 FM 预备好的下一首接到活队列尾部。必须在主线程调用。
     *
     * <p>FM 会话原本每首歌都是一条独立的单曲队列，{@code peekNextIndex()} 因此永远看不到下一首，无缝
     * 切换在私人 FM 下从来没有生效过。这里让队列真的长出下一格：automix 看到 {@code queueRevision}
     * 变化后会重新预测并提前预解码，普通切歌路径也不再需要"播完再请求"。</p>
     *
     * <p>校验都是为了让一次迟到的预备安全作废：用户可能已经退出 FM、换成了别的歌单（{@code playList}
     * 换成另一条实例）、或者这一格已经被填上了。任一条不成立就返回 false，调用方当作没预备过，旧的
     * "播完再请求下一批"兜底路径照旧生效。</p>
     *
     * @param expectedQueue 发起预备时的活队列实例
     * @param song          预备好的曲目
     * @return 是否真的接上了
     */
    public static boolean appendPrefetchedPersonalFmTrack(List<Music> expectedQueue, Music song) {
        if (expectedQueue == null || song == null) return false;
        synchronized (PLAY_NEXT_LOCK) {
            if (!personalFmActive || playList != expectedQueue) return false;
            int size = expectedQueue.size();
            // 只在"队尾就是正在播的这一首"时接：已经有下一首，或者下标已经越界，都不该再动队列。
            if (curIdx < 0 || curIdx + 1 != size) return false;

            Music tail = expectedQueue.get(size - 1);
            // 接口偶尔会把刚推过的那一首再推一遍，连着播同一首不是无缝切换该有的效果。
            if (tail != null && tail.getId() == song.getId()) return false;

            expectedQueue.add(song);
            queueRevision++;
        }
        return true;
    }

    /**
     * 一起听房间：把房间预告的「下一首」接进本机队列当前曲目之后（传 null 则撤下来）。
     *
     * <p>房间的换歌是服务端一首一首下发的，{@code MpPlayback} 起播时队列只有一首，于是
     * {@code peekNextIndex()} 恒为 -1，{@code maybeArmNextTrack} 直接把尝试次数打满 ——
     * 无缝切换在房间里从来没有生效过。这里让队列真的长出下一格：automix 看到
     * {@code queueRevision} 变化后会重新预测并提前预解码，换歌时才有东西可交叠。</p>
     *
     * <p>校验与 {@link #appendPrefetchedPersonalFmTrack} 同款：只在「当前曲目确实在播」
     * 且队列还是那一条时才动它。一次迟到的调用因此绝不会去改用户自己的歌单 —— 房间之外
     * 这个入口从来没有被调用过。</p>
     *
     * <p>多出来的格子一律先收回再放，保证队列永远是「当前 + 至多一首下一首」：房间的下一首
     * 会随服务端广播变化，留着旧的那一首会让交接落到错误的曲目上。</p>
     *
     * @param next 房间的下一首；{@code null} 表示这一刻没有下一首
     * @return 队列是否真的变了
     */
    public static boolean setRoomNextTrack(Music next) {
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            if (queue == null || curIdx < 0 || curIdx >= queue.size()) return false;
            // 房间的「下一首」就是正在播的这一首：没有任何东西可预置。
            if (next != null && next == queue.get(curIdx)) return false;

            boolean changed = false;
            // 已经正好是「当前 + 这一首」时什么都不做：重复调用会白白递增修订号，把刚预备好的
            // 播放牌一次次作废（调用方每秒都会补装一次），结果每次换歌都来不及交叠。
            if (queue.size() == curIdx + 2 && queue.get(curIdx + 1) == next) return false;
            while (queue.size() > curIdx + 1) {
                queue.remove(queue.size() - 1);
                changed = true;
            }
            if (next != null) {
                queue.add(next);
                changed = true;
            }
            if (changed) queueRevision++;
            return changed;
        }
    }

    /**
     * 一起听房间：服务端说「现在就换下一首」，让播放线程用已经预解码好的那一首立刻交叠交接。
     *
     * <p>帧才是权威时刻。本机位置与房间时钟之间始终有几十毫秒抖动，按本地位置推算交叠点会让
     * 「帧先到还是本机先到」变成掷硬币；而帧先到时若走普通起播，本地刚叠上的尾巴会被整段掐掉。
     * 所以帧一到就交接，本机推算的 {@code fire} 只作为帧丢了时的兜底。</p>
     *
     * <p>与 {@link #requestManualAutomixSkip()} 同款：这里只置位，真正的交接仍在播放线程的
     * 10 ms 监督 tick 上完成，因此不会出现两个线程同时替换 player/session 的竞态。任一前置
     * 条件不满足（没有预备好的播放牌、那一首不是房间的下一个、用户关掉了无缝切换）都返回
     * false，调用方原样走旧的起播路径。</p>
     *
     * @return true 表示这次换歌已经交给交叠链路接走
     */
    public static boolean roomAutomixHandover() {
        try {
            if (!RoomAutomix.active()) return false;
            Thread active = playThread;
            if (!(active instanceof PlayThread)) return false;
            return ((PlayThread) active).requestRoomHandover();
        } catch (Throwable ignored) {
            // 房间换歌绝不能因为无缝路径上的意外而卡住：失败就当没有这条捷径。
            return false;
        }
    }
    /**
     * 房间里的 automix 交叠还剩多少毫秒；本机没在交叠时为 0。
     *
     * <p>存在的唯一理由是让一起听那一层知道「此刻这条时间轴正被两段播放着」。服务端心跳量的是
     * 本机播放头与房间位置之差，而交叠期间这两者本来就不该相等：一条在淡出，另一条刚从入口点
     * 淡进来，本机开始淡入的时刻又天然比房间晚几秒（帧延迟 + 一条落在软档里没纠的漂移 + 新
     * 播放牌的缓冲）。把那段读数拿去比硬阈值，就会在淡入还没结束时对刚进来的播放牌做一次硬跳
     * —— 用户报的「automix 听感被截断」正是这么来的。</p>
     *
     * <p>读的是渐变自己的进度：{@code AutomixFade.startedAt} 在暂停时会被平移，所以暂停中的
     * 混音不会一边不动、一边把保护窗越推越长。</p>
     */
    public static long roomBlendRemainingMillis() {
        try {
            Thread active = playThread;
            if (!(active instanceof PlayThread)) return 0L;
            return ((PlayThread) active).blendRemainingMillis();
        } catch (Throwable ignored) {
            // 问不出长度就当没有交叠：纠偏照旧跑，最多是少了一层保护。
            return 0L;
        }
    }

    /**
 * 返回归一化到 {@code 0.0..1.0} 的持久化播放器音量。
 * 模组自有的 HUD 配置是权威来源，因此该值在完全重启 Minecraft 以及切换曲目后依然保留。
 */
    public static float getVolume() {
        return clampVolume(HudConfig.playerVolume);
    }

    /**
     * 界面上该显示的音量：正在镜像且知道外部播放器那一格时用它，否则回落到模组自己的持久化值。
     *
     * <p>{@link #getVolume()} 的语义要保持不变：它在起播时被直接喂给真正的音频播放器
     * （{@code player.setVolume(CloudMusic.getVolume())} 那几处），把它改成外部电平会让
     * 内置播放器按外部音量出声，退出镜像后还会留下一份被悄悄改掉的玩家音量设置。</p>
     */
    public static float displayVolume() {
        return ExternalPlayback.volumeOr(getVolume());
    }

    /**
 * 通过同一条路径为两个播放器界面和全局快捷键应用音量。
 * 数值会被量化到现有的百分之一配置精度。
 *
 * @return 持久化值是否发生了变化
 */
    public static boolean setVolume(float volume, boolean showNotice) {
        float safeVolume = Math.round(clampVolume(volume) * 100.0f) / 100.0f;
        // 镜像态下这根滑块管的是外部播放器在音量合成器里的那一格：既不写持久化配置，也不碰
        // 内部播放器，否则玩家只是拖了一下外部音量，回来发现自己播放器的音量被改了。
        if (ExternalPlayback.writeVolume(safeVolume)) {
            if (showNotice) {
                DownloadDynamicIsland.showVolume(Math.round(displayVolume() * 100.0f));
            }
            return true;
        }
        float previousVolume = getVolume();
        if (Math.abs(previousVolume - safeVolume) < .0001f) {
            return false;
        }

        // 独立于宿主值管理器持久化。它的值存储在 Minecraft 关闭时不保证被写入，
        // 之前这会导致下次启动时滑块被重置为 10%。
        // 之前这会导致下次启动时滑块被重置为 10%。
        HudConfig.playerVolume = safeVolume;
        HudConfig.save();
        try {
            // 保持既有的隐藏 OpenAPI 设置同步，以便与 HUD 模块以及任何直接读取它的
            // 旧集成兼容。
            MuoniumPlayerExtension.getInstance().musicInfo.volume.setValue((double) safeVolume);
        } catch (RuntimeException ignored) {
            // 即使模块尚未就绪，下面的活动播放器仍会收到新值。
        }

        AudioPlayer activePlayer = player;
        if (activePlayer != null) {
            activePlayer.setVolume(safeVolume);
        }
        // 让仍在淡出的播放牌保持同步，否则混音的电平会跳变。
        AudioPlayer fadingPlayer = fadingOutPlayer;
        if (fadingPlayer != null && fadingPlayer != activePlayer) {
            fadingPlayer.setVolume(safeVolume);
        }

        if (showNotice) {
            DownloadDynamicIsland.showVolume(Math.round(safeVolume * 100.0f));
        }
        return true;
    }

    /**
 * 按归一化的增量调整音量，并报告得到的百分比。
 */
    public static boolean adjustVolume(float delta) {
        return setVolume(displayVolume() + delta, true);
    }

    private static float clampVolume(float volume) {
        if (Float.isNaN(volume) || Float.isInfinite(volume)) {
            return .10f;
        }
        return Math.max(0.0f, Math.min(1.0f, volume));
    }

    /**
 * 将当前曲目按相对量移动，并立即让两个歌词渲染器与音频播放器的真实时钟对齐。
 * 把它放在播放层里，可以避免每个界面各自实现一条细节略有不同的跳转路径。
 */
    public static boolean seekByMillis(float deltaMillis) {
        if (MpPartyClient.interceptSeekBy(deltaMillis)) return true;
        if (ExternalPlayback.isMirroring()) {
            ExternalSnapshot external = ExternalPlayback.rawSnapshot();
            long target = Math.max(0L, Math.min(external.durationMs(),
                    external.positionMs() + (long) deltaMillis));
            ExternalPlayback.seekTo(target);
            // 本地不改位置：外部播放器真跳了才会报回新位置。先跳会在它不实现这条命令时
            // 留下一个假进度（能力位说支持、播放器自己没做，这种播放器确实存在）。
            return external.canSeek();
        }
        AudioPlayer activePlayer = player;
        if (activePlayer == null || currentlyPlaying == null) {
            return false;
        }

        float total = activePlayer.getTotalTimeMillis();
        if (total <= 0.0f) {
            return false;
        }

        float target = Math.max(0.0f, Math.min(total,
                activePlayer.getCurrentTimeMillis() + deltaMillis));
        activePlayer.setPlaybackTime(target);

        float actual = activePlayer.getCurrentTimeMillis();
        MusicLyricsWidget.resetProgress(actual);
        MusicLyricsPanel.resetProgress(actual);
        onSeekApplied();
        return true;
    }

    /**
     * 把当前曲目跳到一个绝对位置。控制栏与歌词页的进度条原先各自直接调
     * {@code player.setPlaybackTime}，房间里那条路径绕过了传输控制权，会让拖条的人单独脱队；
     * 现在两处共用这一条，拦截与歌词对齐都只有一份。
     *
     * @return true 表示这次跳转已经生效（或已交给房间处理）
     */
    public static boolean seekToMillis(float absoluteMillis) {
        if (MpPartyClient.interceptSeekTo(absoluteMillis)) return true;
        if (ExternalPlayback.isMirroring()) {
            ExternalPlayback.seekTo((long) absoluteMillis);
            return ExternalPlayback.rawSnapshot().canSeek();
        }
        AudioPlayer activePlayer = player;
        if (activePlayer == null || currentlyPlaying == null) {
            return false;
        }

        float total = activePlayer.getTotalTimeMillis();
        if (total <= 0.0f) {
            return false;
        }

        float target = Math.max(0.0f, Math.min(total, absoluteMillis));
        activePlayer.setPlaybackTime(target);

        float actual = activePlayer.getCurrentTimeMillis();
        MusicLyricsWidget.resetProgress(actual);
        MusicLyricsPanel.resetProgress(actual);
        onSeekApplied();
        return true;
    }

    /**
     * 本地播放头<b>已经</b>移动之后通知 automix。
     *
     * <h2>为什么进度条必须过这一道</h2>
     *
     * <p>交叉淡化的曲线读的是<b>墙钟</b>（{@code (now − startedAt) / overlapMillis}），不是任何一张
     * 牌的位置。所以把新歌拖到 1:20 之后，那几条曲线还会照着「新歌刚从入点进来」继续跑完剩下的
     * 几秒：旧歌不停、新歌被低搁架与人声让路压着、音量从一半慢慢抬起来。用户听见的就是
     * 「我都拖走了那首还在响」。</p>
     *
     * <p>已经预备、还没点火的那一段不需要这里操心：{@code maybePreRollArmedDeck} 早就按位置复核过
     * 预卷牌有没有跑过头，而点火点被甩到身后由监督 tick 自己发现（{@code holdSeamForFreshCue}）——
     * 那一条判的是播放头与计划，与是谁移动了它无关。所以这里<b>不传位置</b>：本方法只能保证
     * 「动过」，真正该照哪个位置算，永远以本线程下一次读到的那个为准。</p>
     *
     * <p>本方法只做记账，<b>一张牌都不碰</b>：它会被界面线程、房间的帧处理线程、官方一起听的同步
     * 线程调到，而收尾要动两张牌、还要撤过渡动画，那些都归播放线程管。于是收尾排在它自己的
     * 10 ms tick 上，与自然结束时的交接同一根线程同一把锁。</p>
     */
    public static void onSeekApplied() {
        try {
            Thread active = playThread;
            if (!(active instanceof PlayThread)) return;
            ((PlayThread) active).recordSeek();
        } catch (Throwable ignored) {
            // 进度条是用户操作：automix 这边出任何意外都不能让它不生效。
        }
    }

    private static boolean canPlayNext() {
        if (curIdx + 1 <= playList.size() - 1) {
            return true;
        }

        if (playMode == PlayMode.Sequential) {
            return false;
        }

        // 非顺序模式下，手动下一首可从队列首位继续。
        curIdx = -1;
        return true;
    }

    /**
     * 给网易云发送当前歌曲的播放时长
     */
    private static void updatePlayCountIfNeeded() {
        if (playedFrom != null && player != null) {
            playList.get(curIdx).updPlayCount(playedFrom, player.getCurrentTimeSeconds());
        }
    }

    /**
     * 请求正在播放的 {@code PlayThread} 用已经预解码好的下一首完成一次无缝交接。
     *
     * <p>这里只是"请求"：真正的交接仍然发生在播放线程的 10ms 监督 tick 上，和自然结束时的自动切歌
     * 走同一段 {@code performAutomixHandover}，因此不会出现两个线程同时替换 player/session 的竞态。
     * 任何一个前置条件不满足（无缝切换被关掉、下一首还没备好、备好的不是队列此刻预测的那一首、正在
     * 暂停、上一次淡化还没跑完）都返回 false，调用方原样走旧的切歌路径。</p>
     */
    private static boolean requestManualAutomixSkip() {
        try {
            if (!AutomixSettings.isEnabled()) return false;
            Thread active = playThread;
            if (!(active instanceof PlayThread)) return false;
            return ((PlayThread) active).requestManualSkip();
        } catch (Throwable ignored) {
            // 手动切歌是用户操作，绝不能因为无缝路径上的意外而卡住：失败就当没有这条捷径。
            return false;
        }
    }

    /** 未预备的手动上一首/下一首，不先改 curIdx，更不先停掉仍在出声的歌。 */
    private static boolean requestManualAutomixStep(int direction) {
        Thread active = playThread;
        return active instanceof PlayThread && ((PlayThread) active).requestManualStep(direction);
    }

    /** 渲染只读的选曲快照；真正的 currentlyPlaying/session 仍等音频交接才变。 */
    public static PlaybackPresentation playbackPresentation() {
        Music audible = currentlyPlaying;
        Thread active = playThread;
        if (active instanceof PlayThread) {
            PlayThread owner = (PlayThread) active;
            PlayThread.ManualSelection selection = owner.manualSelection;
            if (selection != null && owner.isSessionUsable(selection.session)) {
                return new PlaybackPresentation(audible, selection.selectedSong, selection.requestNanos);
            }
        }
        return new PlaybackPresentation(audible, null, 0L);
    }

    /** 搜索续播结果可能先于音频回来，此时应追加到待交接队列，而不是旧歌的队列。 */
    private static List<Music> selectedPlaybackQueue() {
        Thread active = playThread;
        if (active instanceof PlayThread) {
            PlayThread.ManualSelection selection = ((PlayThread) active).manualSelection;
            if (selection != null) return selection.songs;
        }
        return playList;
    }

    /**
     * 切换当前播放的暂停/继续。播放器界面的空格键与全局快捷键共用这一条路径，避免两处各写一份判空
     * 逻辑之后行为慢慢分叉；正在进行的无缝淡化由 {@code driveAutomixFade} 一并挂起，这里不必特殊处理。
     *
     * @return 本次调用的实际结果；没有可操作的播放时返回 {@link PlayPauseResult#UNAVAILABLE}
     */
    public static PlayPauseResult togglePlayPause() {
        // 镜像态下这条命令的对象是外部播放器，不是我们的音频引擎。结果按「当前是不是在响」
        // 乐观回报：真正的状态下一拍（≤500ms）由 SMTC 快照纠正，而玩家点按钮那一下
        // 必须立刻有一句反馈，否则会被当成按键没生效。
        if (ExternalPlayback.isMirroring()) {
            boolean wasPlaying = ExternalPlayback.rawSnapshot().isPlaying();
            ExternalPlayback.toggle();
            return wasPlaying ? PlayPauseResult.PAUSED : PlayPauseResult.RESUMED;
        }
        // 一起听房间中：房主/管理员按一下变成房间指令；普通成员被挡住，按 UNAVAILABLE 反馈。
        int party = MpPartyClient.interceptToggleState();
        if (party == 1) return PlayPauseResult.PAUSED;
        if (party == 2) return PlayPauseResult.RESUMED;
        if (party == 3) return PlayPauseResult.UNAVAILABLE;
        AudioPlayer activePlayer = player;
        if (currentlyPlaying == null || activePlayer == null || activePlayer.isFinished()) {
            return PlayPauseResult.UNAVAILABLE;
        }
        if (activePlayer.isPausing()) {
            activePlayer.unpause();
            return PlayPauseResult.RESUMED;
        }
        activePlayer.pause();
        return PlayPauseResult.PAUSED;
    }

    /** {@link #togglePlayPause()} 的结果，供界面与快捷键决定要不要给反馈。 */
    public enum PlayPauseResult {
        /** 当前没有可暂停/继续的播放。 */
        UNAVAILABLE,
        PAUSED,
        RESUMED
    }

    private static void prepareForTrackChange() {
        dontAdd = true;
    }

    private static void stopCurrentPlayback() {
        player.close();
        playing.set(false);
    }

    /**
     * 停下播放并把界面收回「未在播放」。
     *
     * <p>播放器本来没有这个状态：{@link #currentlyPlaying} 只会被换成下一首，从来不清空。本地
     * 歌单播完最后一首这样没问题 —— 队列还在，按一下播放就能重播。一起听不一样：房间放完最后
     * 一首、服务端广播停止之后，这一路播放的曲目和队列都属于房间，而房间已经空了。此时把
     * 「正在播放」留在那里，播放条就会一直挂着一个歌名和一条不动的进度条，点了也不会播。</p>
     *
     * <p>清完之后这几个静态字段与刚进游戏时逐字段相同（{@code player == null}、
     * {@code currentlyPlaying == null}、空队列），所以所有读它们的地方本来就处理过这种情况。</p>
     *
     * @param expected 只有正在播放的就是这一首时才停：房间的停止指令可能比用户手动点的下一首
     *                 晚到，不校验就会把用户刚点的歌掐掉。传 {@code null} 表示无条件停
     * @return 是否受理了这次停止；字段清空排在紧随其后的一次主线程回调里
     */
    @SneakyThrows
    public static boolean stopPlaybackAndClear(Music expected) {
        if (expected != null && currentlyPlaying != expected) return false;

        // 走和「换一条队列」同一条收尾路径：它会让 session 失效、取消预备与正在淡出的另外两副
        // 牌，并关掉播放线程自己持有的那一副。
        stopExistingPlayThread();

        AudioPlayer active = player;
        if (active != null) {
            try {
                active.close();
            } catch (Throwable ignored) {
                // 可能还没初始化完就被停掉；不能因此吞掉后面的字段清理。
            }
        }
        // 字段清空排到主线程：界面上十几处都是「先判空、再取值」地读这两个 volatile 字段（最
        // 典型的是播放条歌名 lblMusicName），后台线程把它们从非空改成 null 的话，那两次读之间
        // 就能插进一个 NPE。渲染与客户端 tick 同在主线程，排上去之后这条缝根本不存在。
        MultiThreadingUtil.runOnMainThread(() -> {
            // 上面的收尾最长要等 1 秒，这中间用户完全可能自己点了新的一首 —— 那就什么都不该
            // 动，所以身份校验在这里再做一次。
            if (expected != null && currentlyPlaying != expected) return;
            player = null;
            currentlyPlaying = null;
            // 换一个新的 List 实例而不是 clear()：播放线程靠「playList 还是不是我启动时那一条」
            // 判断队列有没有被整条换掉，复用同一实例会让残存的线程以为队列没动过。
            playList = new ArrayList<>();
            curIdx = 0;
            playing.set(false);
        });
        return true;
    }

    /** 仅在一首曲目失败后用户明确执行上一首/下一首操作时才重启队列。 */
    private static void restartFromUserTrackSelection() {
        awaitingPlaybackAction = false;
        startPlaybackList(playList, curIdx);
    }

    /**
     * 播放来源, 用于记录播放时长
     */
    public static PlayList playedFrom = null;

    /**
     * 从搜索结果起播。
     *
     * <p>主流播放器在这里接的都是用户自己的播放上下文：点的是网易云的歌、而网易云确实登着时，
     * 只把用户点的那一首装进队列起播，随后用刷新过的"最近播放"续上后面的曲目 —— 搜索结果不是
     * 歌单，顺着它一路往下播是搜索关键词的排序，不是用户在听的东西。</p>
     *
     * <p><b>但这个上下文不是谁都有的，所以另一条路必须先判</b>：换别的音源（酷狗 / 汽水 / B 站 /
     * GD）点一首歌，或者网易云根本没登录时，退回与普通歌单完全同一条路 —— 整条列表当队列、
     * 从这一首往后播。原来这里不看音源也不看登录态，固定去拉网易云的最近播放，于是在酷狗的搜索
     * 结果里点一首，队列变成「1 首酷狗 + 上百首网易云听歌历史」；Cookie 过期时接口只回一份空历史，
     * 它又一声不吭地把整份搜索结果塞进队列。用户看到的症状就是「通知有时冒出来有时没有，
     * 而队列已经不是我点的那份了」。</p>
     *
     * <p>刻意先起播、再异步补队列：拉一次最近播放要走一次网络，让点击等它回来会有几百毫秒的"点了
     * 没反应"。补队列时会校验队列还是刚装进去的那一条，用户在这段时间里点了别的歌或者用了"下一首
     * 播放"就直接放弃追加。</p>
     *
     * @param selected      用户点的那一首
     * @param searchResults 当前搜索结果；没有续播上下文时它就是队列本身
     * @param selectedIndex {@code selected} 在搜索结果里的下标
     */
    public static void playFromSearchSelection(Music selected, List<Music> searchResults, int selectedIndex) {
        List<Music> results = searchResults == null
                ? Collections.<Music>emptyList() : new ArrayList<>(searchResults);
        // 搜索结果不是歌单，播放时长上报没有来源歌单可归属，与旧行为一致。
        currentPlaylistContext = null;

        if (selected == null || results.isEmpty()) {
            if (selected != null) play(Collections.singletonList(selected), 0);
            else play(results, selectedIndex);
            return;
        }

        if (!hasRecentPlayContext(selected)) {
            play(results, Math.max(0, Math.min(selectedIndex, results.size() - 1)));
            return;
        }

        play(Collections.singletonList(selected), 0);

        final List<Music> installedQueue = selectedPlaybackQueue();
        final List<Music> fallbackTail = tailAfterSelected(results, selectedIndex);
        MultiThreadingUtil.runAsync(() -> {
            List<Music> recent = NeteaseRecentPlaysService.fetchRecentSongs(
                    NeteaseRecentPlaysService.MAX_RECENT_SONGS);
            final boolean usedRecent = !recent.isEmpty();
            final List<Music> tail = usedRecent
                    ? NeteaseRecentPlaysService.buildTail(selected, recent)
                    : fallbackTail;
            MultiThreadingUtil.runOnMainThread(() ->
                    appendSearchSelectionTail(installedQueue, selected, tail, usedRecent));
        });
    }

    /**
     * 这次起播有没有「用户自己的续播上下文」可接。
     *
     * <p>判据用<b>曲目自己的来源</b>，不用 {@code NeteaseMusicService.getCurrentPlatform()}：当前音源
     * 是个全局设置，而用户完全可能挂着网易云音源去点一张汽水收藏的列表（那个列表就复用了
     * {@code searchResultList} 标记），那时候该接的是那份列表自己的后面，不是网易云的历史。</p>
     *
     * <p>登录态只查本地有没有 Cookie，不发请求：这一步在渲染线程上，一次点击多一次网络往返就是
     * 卡顿。Cookie 在但服务端已经作废的情况下，异步那趟只会回一份空历史，于是接的是搜索列表自己
     * 的后面 —— 与不接的效果一致。</p>
     */
    private static boolean hasRecentPlayContext(Music selected) {
        return selected.isNetease() && NeteaseMusicService.isLoggedIn(MusicPlatform.NETEASE);
    }

    /**
     * 普通歌单语义下「这一首后面还跟着什么」：从它的下一格一直到列表末尾，一格不多一格不少。
     *
     * <p>顺序必须正好是这样。旧写法兜底时把<b>整份</b>搜索结果接到后面，等于把用户点的那一首之前
     * 的那些歌塞到了它后面，跟直接点歌单里一行的行为不一致，也正是这里要修掉的一条。</p>
     */
    private static List<Music> tailAfterSelected(List<Music> results, int selectedIndex) {
        int from = selectedIndex + 1;
        if (from < 0 || from >= results.size()) return Collections.emptyList();
        return new ArrayList<>(results.subList(from, results.size()));
    }

    /**
     * 把 {@link #playFromSearchSelection} 拉到的队列尾部接到活队列上。必须在主线程调用。
     *
     * @param tail       要接上的曲目；空则什么都不做
     * @param usedRecent 尾部来自网易云最近播放而非搜索结果本身。只有前者会告诉用户，
     *                   因为后者就是这个列表本来该有的顺序，说一句"已接入"反而是在骗人
     */
    private static void appendSearchSelectionTail(List<Music> installedQueue, Music selected,
                                                  List<Music> tail, boolean usedRecent) {
        if (installedQueue == null || selected == null || tail == null || tail.isEmpty()) return;

        int appended;
        synchronized (PLAY_NEXT_LOCK) {
            // 队列必须还是当初装进去的那一条实例，并且仍然只有那一首：用户在这几百毫秒里换了歌、
            // 换了歌单、或者用"下一首播放"插了曲目，都不能再往里追加。
            if (selectedPlaybackQueue() != installedQueue || installedQueue.size() != 1) return;
            Music head = installedQueue.get(0);
            if (head == null || !head.equals(selected)) return;

            List<Music> rest = new ArrayList<>(tail);
            // 用户点的这首如果就在历史里，它不该在队列里排两遍。
            rest.removeIf(head::equals);
            if (rest.isEmpty()) return;
            installedQueue.addAll(rest);
            appended = rest.size();
            // automix 之前按"没有下一首"预测过一次，递增 revision 让它重新预测并重新预解码。
            queueRevision++;
        }
        System.out.println("[NCM] 搜索起播：已接入" + (usedRecent ? "最近播放" : "搜索结果")
                + " " + appended + " 首");
        if (usedRecent) {
            DownloadDynamicIsland.showSearchQueueFromRecentPlays(appended);
        }
    }

    /**
     * 播放给定的列表中的所有歌曲
     *
     * @param songs    歌曲列表
     * @param startIdx 第一首播放的索引
     */
    @SneakyThrows
    public static void play(List<Music> songs, int startIdx) {
        exitPersonalFmSession();
        startPlaybackList(songs, startIdx);
    }

    /**
 * 启动一小批私人 FM 曲目，不应用普通的歌单播放模式。
 */
    @SneakyThrows
    public static void playFm(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) {
            System.err.println("[NCM] Ignoring empty personal FM batch.");
            return;
        }
        enterPersonalFmSession();
        startPlaybackList(songs, startIdx);
    }

    @SneakyThrows
    private static void startPlaybackList(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) {
            System.err.println("[NCM] Ignoring empty playlist.");
            return;
        }

        // 镜像模式开着时内部不起播：一个系统里只能有一个「正在播放」是真的，两个声源
        // 同放是体验事故，而把镜像悄悄切回内部又是一次玩家没要求的模式变更。
        // 这里是 play() 与 playFm() 的共用入口，拦这一处就够了，界面上不必各写一份。
        if (ExternalPlayback.refuseInternalPlayback()) return;

        // 一起听房间里传输控制权归服务端：本地起播改成点歌，由服务端安排给全房同时播。
        // 房间自己下发的那一首会被放行（MpPlayback 起播时带着身份标记）。
        if (MpPartyClient.interceptStartPlayback(songs, startIdx)) {
            return;
        }

        // 旧队列即将被整条换掉，任何在途的 FM 预备结果都不再属于它。
        PersonalFmManager.clearPrefetchState();

        // 深拷贝一份以避免打乱时影响来源列表；FM 会话始终保持接口返回顺序。
        List<Music> safeSongList = new ArrayList<>(songs);
        if (!personalFmActive && playMode == PlayMode.Random) {
            startIdx = handleRandomPlayMode(safeSongList, startIdx);
        }

        startIdx = Math.max(0, Math.min(normalizeStartIndex(startIdx), safeSongList.size() - 1));
        Thread active = playThread;
        if (active instanceof PlayThread
                && ((PlayThread) active).requestManualSelection(safeSongList, startIdx, true)) return;

        stopExistingPlayThread();
        loadMusicCover(safeSongList.get(Math.min(startIdx, safeSongList.size() - 1)));
        playList = safeSongList;
        clearQueuedNext();
        startNewPlayThread(safeSongList, startIdx);
    }

    public static boolean isPersonalFmActive() {
        return personalFmActive;
    }

    public static synchronized void enterPersonalFmSession() {
        if (!personalFmActive) {
            playModeBeforePersonalFm = playMode;
            personalFmActive = true;
            playMode = PlayMode.Sequential;
        }
    }

    public static synchronized void exitPersonalFmSession() {
        if (!personalFmActive) return;
        personalFmActive = false;
        playMode = playModeBeforePersonalFm;
        PersonalFmManager.clearPrefetchState();
    }

    /**
     * 使当前播放会话失效（逻辑取消），用于切歌/切列表前阻止旧歌词任务污染新状态。
     * 不在此 increment generation：新歌 beginNewSession() 时再递增。
     */
    private static void invalidateCurrentSession() {
        PlaybackSession old = currentSession;
        if (old != null) {
            old.invalidate();
        }
    }

    /**
     * 开始播放一首新歌时生成唯一 Session：先失效旧 Session，再递增 generation，最后绑定新 Session。
     */
    public static PlaybackSession beginNewSession(Music song) {
        PlaybackSession old = currentSession;
        if (old != null) {
            old.invalidate();
        }
        long id = generation.incrementAndGet();
        PlaybackSession session = new PlaybackSession(id, song.getStableKey(), song.getId());
        currentSession = session;
        return session;
    }

    private static void stopExistingPlayThread() throws InterruptedException {
        invalidateCurrentSession();
        Thread previousThread = playThread;
        if (previousThread == null) return;

        doBreak = true;
        playing.set(false);
        if (previousThread instanceof PlayThread) {
            ((PlayThread) previousThread).cancelPlayback();
        } else {
            previousThread.interrupt();
        }

        // 网络实现可能忽略 interrupt。禁止无限 join 卡住 GUI/重新点歌入口。
        previousThread.join(PLAY_THREAD_JOIN_TIMEOUT_MS);
        if (previousThread.isAlive()) {
            System.err.println("[NCM] Previous play thread did not stop in time; continuing with a new session.");
        }
        if (playThread == previousThread) {
            playThread = null;
        }
    }

    private static int handleRandomPlayMode(List<Music> songs, int startIdx) {
        if (startIdx == -1) {
            Collections.shuffle(songs);
        } else {
            Music selectedMusic = songs.get(startIdx);
            Collections.shuffle(songs);
            startIdx = songs.indexOf(selectedMusic);
        }
        return startIdx;
    }

    private static int normalizeStartIndex(int startIdx) {
        return startIdx == -1 ? 0 : startIdx;
    }

    private static void startNewPlayThread(List<Music> songs, int startIdx) {
        playThread = new PlayThread(songs, startIdx);
        awaitingPlaybackAction = false;
        doBreak = false;
        playing.set(false);
        playThread.start();
    }

    static volatile boolean doBreak = false;

    static AtomicBoolean playing = new AtomicBoolean(true);

    private static class PlayThread extends Thread {
        private volatile List<Music> songs;
        private final int startIdx;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private PlaybackSession session;
        private volatile AudioPlayer ownedPlayer;
        /** 下一首曲目已解码完成，正在空闲播放牌上静默等待。 */
        private volatile ArmedTrack armed;
        /** 点击只提交意图；下载/解码不占用 UI、监督 tick 或低优先级分析队列。 */
        private volatile ManualSelection manualSelection;
        /** 后台预备任务运行期间为 true，确保同一时间只有一次预备在进行。 */
        private volatile boolean arming;
        /** 当前曲目已进行的预备尝试次数：瞬时失败会重试，而彻底损坏则停止。 */
        private volatile int armAttempts;
        /** 下一次预备尝试可以开始的最早墙钟时间，即重试冷却时间。 */
        private volatile long nextArmAt;
        /** 本线程最后响应过的队列修订号；用户改动队列会使预备失效。 */
        private volatile long seenQueueRevision = -1L;
        /** 当预备好的播放牌接管时由等待循环设置，使 {@code run()} 跳过重新开始。 */
        private volatile boolean automixHandover;
        /**
 * 当用户按下的"下一首"应由已预备的播放牌来服务时，由 {@link #requestManualSkip()} 设置。
 * 等待循环在下一个 tick 消费它，因此交接总是在播放线程上执行，即使请求来自界面线程。
 */
        private volatile boolean manualSkipRequested;
        /**
         * 一起听房间下发的换歌帧要求立刻做一次交叠交接，见 {@link #requestRoomHandover()}。
         *
         * <p>与手动切换同一套「置位 → 由播放线程在下一个 tick 消费」的做法：交接永远发生在
         * 播放线程上，即使请求来自网络线程。</p>
         */
        private volatile boolean roomHandoverRequested;
        /** 当前曲目已进行的预取尝试次数：不稳定的 FM 响应会重试，但最终停止。 */
        private volatile int fmPrefetchAttempts;
        /** 下一次私人 FM 预取尝试可以开始的最早墙钟时间。 */
        private volatile long nextFmPrefetchAt;
        /**
         * 下一首「只下载不解播放牌」的预取是否在跑，见 {@link #maybePrefetchNextTrackAudio}。
         *
         * <p>与 {@link #arming} 分开记：这一条是挂在逐首预备没做成的那些场合后面的（用户关了
         * 无缝切换、或它这一首三份预算都用完了），两者不该共用一个开关。</p>
         */
        private volatile boolean prefetchingAudio;
        /** 当前曲目已进行的音频预取尝试次数（与 {@link #armAttempts} 同一本账，各自一首一份）。 */
        private volatile int audioPrefetchAttempts;
        /** 下一次音频预取可以开始的最早墙钟时间。 */
        private volatile long nextAudioPrefetchAt;
        private volatile AutomixFade activeFade;
        /**
         * 混音进行中有人拖了进度条，见 {@link #recordSeek()}。
         *
         * <p>与 {@link #manualSkipRequested} 同一套「别的线程只置位，本线程的 10 ms tick 消费」：
         * 收尾要动两张牌（把进场方拉平、把出场方放掉）还要撤掉过渡动画，那些都归这根线程管。
         * 从界面线程直接 {@code finishAutomixFade()} 会跟正在跑的 {@code driveAutomixFade} 抢
         * 同一个 {@code activeFade}，最坏的情况是两张牌各自被释放一次。</p>
         */
        private volatile boolean seekDuringFadeRequested;
        /**
         * 顶住交接、等一条新接缝的截止墙钟时间；{@code 0} 表示没在顶。见
         * {@link #holdSeamForFreshCue(AudioPlayer)}。
         */
        private volatile long seamHoldUntil;


        /**
 * 在监督 tick 收回曲线写入权之前，{@link AutomixFade#audioDrivenAt} 可以陈旧多久。
 *
 * <p>大约几个音频缓冲区的时长：足够长，使错过一个缓冲区不会让写入方来回切换；
 * 足够短，使死掉的音频任务在一个 10 ms tick 内就会被发现。</p>
 */
        private static final long AUDIO_DRIVEN_WINDOW_MILLIS = 40L;

        /**
 * 交叉淡化曲线的音频线程写入者，在混音运行的整个期间注册。
 *
 * <p>关于为什么值得拥有它，见 {@link #registerAutomixAudioTask()}；
 * 关于轮到它时做什么，见 {@link #writeTransitionFrameOnAudioThread()}。</p>
 */
        private final Runnable automixAudioTask = this::writeTransitionFrameOnAudioThread;
        private volatile boolean automixAudioTaskRegistered;

        public PlayThread(List<Music> songs, int startIdx) {
            this.songs = songs;
            this.setName("Play Thread");
            this.startIdx = startIdx;
        }

        /**
         * 连续跳过的上限。整张歌单都是 VIP 专享时不能一路跳到底 —— 那等于把用户的歌单静默清空，
         * 到了上限就停下让用户处理（换歌单，或关掉自动跳过）。
         */
        private static final int ROOM_SKIP_LIMIT = 8;

        private int consecutiveRoomSkips;

        @Override
        public void run() {
            curIdx = startIdx;

            try {
                while (shouldContinuePlayback()) {
                    if (playListChanged()) {
                        break;
                    }

                    if (automixHandover) {
                        // 已预备的播放牌已经在出声，并拥有 curIdx/session/lyrics。无需
                        // 再启动什么；只需恢复监督当前正在播放的曲目。
                        automixHandover = false;
                        resetArmState();
                    } else {
                        Music currentSong = playList.get(curIdx);

                        // 一起听：房里有非会员时，VIP 专享曲对他们只有 30 秒试听，官方客户端会让整房
                        // 一起切歌。这里在起播前就跳过，避免"房主接着放、房员早换歌了"的脱队。
                        //
                        // 刻意不走 playSong 返回 false 那条路：那条是"解析失败停下等用户操作"，
                        // 而这里要的是继续往下放。推进也刻意用线性 curIdx++ 而不是 updateCurrentIndex()
                        // —— 单曲循环下后者会原地打转，跳过就变成死循环。
                        if (NeteaseListenTogetherService.shouldHostSkip(currentSong)) {
                            consecutiveRoomSkips++;
                            if (consecutiveRoomSkips > ROOM_SKIP_LIMIT || curIdx + 1 >= playList.size()) {
                                DownloadDynamicIsland.showListenTogetherNothingPlayable(consecutiveRoomSkips);
                                consecutiveRoomSkips = 0;
                                awaitingPlaybackAction = true;
                                playing.set(false);
                                break;
                            }
                            DownloadDynamicIsland.showListenTogetherVipSkip(currentSong.getName(),
                                    NeteaseListenTogetherService.skipReason());
                            curIdx++;
                            continue;
                        }
                        consecutiveRoomSkips = 0;

                        prepareForPlayback();

                        if (!playSong(currentSong)) {
                            // 绝不自动跳过播放失败的曲目。保持失败歌曲处于选中状态，并
                            // 等待用户重试、选择上一首/下一首，或另选一首歌。
                            awaitingPlaybackAction = !isPlaybackCancelled() && !playListChanged() && !doBreak;
                            playing.set(false);
                            break;
                        }

                        awaitingPlaybackAction = false;
                        resetArmState();
                        preloadNextCover();
                    }

                    waitForPlaybackCompletion();
                    if (automixHandover) {
                        // curIdx、session 和活动播放器已由交接本身推进。
                        continue;
                    }

                    cancelArmedTrack();
                    finishAutomixFade();
                    if (isPlaybackCancelled() || playListChanged() || session == null || !session.isActive()) {
                        break;
                    }
                    handlePlaybackCompletion();
                    updateCurrentIndex();
                    if (personalFmActive && curIdx >= playList.size()) {
                        PersonalFmManager.requestNextBatchAsync();
                        break;
                    }
                }
            } catch (Throwable failure) {
                // 最后一道防线。播放链路里任何漏网的 Throwable（解码器 LinkageError、内存不足、
                // 第三方源解析里意外的 Error）如果直接逃出 run()，Play Thread 会静默死亡：
                // playing 留在 true、灵动岛不收尾、也不会有任何提示，表现为"下载完成却不播放"。
                // 这里统一提示一次并复位成"等待用户操作"，与解析失败不自动跳下一首保持一致。
                if (!isPlaybackCancelled() && !playListChanged()) {
                    handlePlayerInitializationError(currentlyPlaying, failure);
                    awaitingPlaybackAction = true;
                } else {
                    failure.printStackTrace();
                }
                playing.set(false);
            } finally {
                // 正在混音中途的播放牌不是活动播放器，所以其他任何路径都不会释放它。
                cancelManualSelection();
                cancelArmedTrack();
                finishAutomixFade();
            }
        }

        private boolean shouldContinuePlayback() {
            return curIdx >= 0 && curIdx < playList.size() && !doBreak && !isPlaybackCancelled();
        }

        private boolean isPlaybackCancelled() {
            return cancelled.get() || this.isInterrupted();
        }

        private boolean isSessionUsable(PlaybackSession targetSession) {
            return !isPlaybackCancelled() && targetSession != null && targetSession.isActive()
                    && !playListChanged();
        }

        private void cancelPlayback() {
            cancelled.set(true);
            cancelManualSelection();
            PlaybackSession activeSession = session;
            if (activeSession != null) activeSession.invalidate();
            interrupt();

            // 先释放 automix 播放牌：它们都不是 CloudMusic.player，所以下面的代码块
            // 会让它们继续运行并出声。
            cancelArmedTrack();
            finishAutomixFade();

            synchronized (PLAYER_STATE_LOCK) {
                AudioPlayer activePlayer = ownedPlayer;
                NeteasePlaybackHistoryReporter.finish(activePlayer,
                        NeteasePlaybackHistoryReporter.EndReason.REPLACED);
                KuGouPlaybackReporter.finish(activePlayer, KuGouPlaybackReporter.EndReason.REPLACED);
                if (activePlayer != null && CloudMusic.player == activePlayer) {
                    try {
                        activePlayer.close();
                    } catch (Throwable ignored) {
                        // 播放器可能尚未完全初始化；取消流程不能再阻塞新的点歌请求。
                    }
                }
                playing.set(false);
            }
        }

        private boolean playListChanged() {
            return playList != songs;
        }

        private void prepareForPlayback() {
            stopPreviousPlayer();
            loadMusicCover(playList.get(curIdx));
        }

        /**
         * 「只拿到试听片段」的归因文案：汽水按时下状态说真话，其他平台维持原判。
         *
         * <p>判定依据不是解码时长（那只说明「是试听」），而是取流层随流上传的权益状态：
         * 本机签名后端不在 → 临时降级，就绪后重播即得；未登录 → 登录后可放；
         * 都不是 → 才轮到「账号权益拿不到完整版」。此前一律归因「当前账号」，
         * 用户在后端未就绪时被误导去检查自己的会员。</p>
         */
        private static String trialFailureReason(Music song) {
            if (song != null && song.isQishui()) {
                if (!QishuiBackend.peekAvailable()) {
                    return "本机签名后端未就绪，就绪后重播可获取完整版";
                }
                if (!QishuiMusicService.isLoggedIn()) {
                    return "汽水账号未登录，登录后可播放完整版";
                }
                return "当前账号权益拿不到完整版";
            }
            return "当前账号只能拿到试听片段";
        }

        private boolean playSong(Music song) {
            if (isPlaybackCancelled()) return false;

            // 先使旧 session 失效并生成新 session/generation，绑定本次歌曲。
            session = CloudMusic.beginNewSession(song);
            if (!isSessionUsable(session)) {
                session.invalidate();
                return false;
            }
            currentlyPlaying = song;
            // 起播即销账：这一首已经轮到它播了，用户为它点的那次"下一首播放"到此兑现。
            noteQueuedTrackStarted(song);
            // 封面可能已经由上一轮预加载完成；切歌这一刻才把它提升为活动主题色。
            CoverThemePalette.set(song.getCoverPalette());
            // 有了 FM 预备之后，推进不再每首都经过 PersonalFmManager.requestBatch，界面同步必须在这里补。
            if (personalFmActive) PersonalFmManager.noteFmTrackStarted(song);
            // 动态封面查找是可选的，绝不会阻塞音频启动或静态封面渲染。
            loadDynamicMusicCover(song);

            // 每次点击/切歌都会重新创建解析任务；失败结果不会被缓存。
            Tuple<String, String> playUrl = song.getPlayUrl();
            if (!isSessionUsable(session)) return false;

            if (playUrl == null) {
                // 官方链路与解灰都已扑空：最后试一次跨平台换源 —— 拿曲名 / 艺人去别的平台
                // 重新匹配一条真正的曲目，换掉队列里的这一格。失败或不在候选半径内时
                // 维持原行为（报「没有可用音频源」并停下等用户操作）。
                CrossSourceSwitcher.Match replacement = null;
                if (CrossSourceSwitcher.canAutoSwitch(song)) {
                    DownloadDynamicIsland.showCrossSourceSearching(song.getName());
                    try {
                        replacement = CrossSourceSwitcher.findReplacementForAuto(song, CloudMusic.quality);
                    } catch (Throwable failure) {
                        System.err.println("[Music/Switch] 自动换源流水线异常 "
                                + song.getStableKey() + ": " + failure);
                    }
                }
                if (replacement == null || isPlaybackCancelled() || playListChanged()) {
                    handleUnplayableSong(song);
                    return false;
                }
                if (!adoptCrossSourceReplacement(song, replacement)) {
                    handleUnplayableSong(song);
                    return false;
                }
                song = replacement.music;
                playUrl = replacement.url;
            }

            boolean started = initializeAndPlaySong(song, playUrl, session);
            if (!started && isSessionUsable(session) && !isPlaybackCancelled() && !playListChanged()) {
                // 直链拿到了却放不出来 —— 两种成因共用这一条换源救援：
                // ① 下载 / 字节校验 / 解码抛错（CDN 直链过期、上游交付非音频载荷）；
                // ② 播得出来但只是试听片段（非会员在本平台开不了 VIP，60/30 秒就到底）。
                // 网易云与 GD 在 getMusicFile 内部已有各自的平台内兜底，那些也扑空之后才到这里；
                // AUTO_ATTEMPTED 保证每首每进程最多试一次，不会连环烧整张候选表的额度。
                boolean switchable = CrossSourceSwitcher.canAutoSwitch(song);
                if (!switchable && lastStreamIncomplete) {
                    // 不救场时至少说清为什么停下：试听不是网络故障，归因要说准——
                    // 汽水的「只有试听」多半是本机后端未就绪或没登录，不是账号的错。
                    DownloadDynamicIsland.showPlaybackFailure(song.getName(),
                            trialFailureReason(song));
                }
                CrossSourceSwitcher.Match brokenStream = null;
                if (switchable) {
                    DownloadDynamicIsland.showCrossSourceSearching(song.getName());
                    try {
                        brokenStream = CrossSourceSwitcher.findReplacementForAuto(song, CloudMusic.quality);
                    } catch (Throwable failure) {
                        System.err.println("[Music/Switch] 流失败换源流水线异常 "
                                + song.getStableKey() + ": " + failure);
                    }
                    if (brokenStream != null && !isPlaybackCancelled() && !playListChanged()
                            && adoptCrossSourceReplacement(song, brokenStream)) {
                        song = brokenStream.music;
                        playUrl = brokenStream.url;
                        started = initializeAndPlaySong(song, playUrl, session);
                        if (!started && lastStreamIncomplete) {
                            DownloadDynamicIsland.showPlaybackFailure(song.getName(),
                                    "换到的平台同样只有试听片段");
                        }
                    }
                }
            }
            if (started && isSessionUsable(session)) {
                DownloadDynamicIsland.showPlaybackQuality(
                        song.getPlaybackQuality().getDisplayName(), playUrl.getB());
            }
            return started;
        }

        /**
         * 换源成功后接管播放状态：把队列里的这一格换成新曲目，重建 session，
         * 并把封面 / 主题色 / FM 界面推进到新曲目上。返回 false 表示在接管过程中
         * 播放又被取消或替换，调用方按解析失败收尾。
         */
        private boolean adoptCrossSourceReplacement(Music oldSong, CrossSourceSwitcher.Match replacement) {
            try {
                if (curIdx >= 0 && curIdx < playList.size()) {
                    playList.set(curIdx, replacement.music);
                }
            } catch (Throwable ignored) {
                // 队列正被面板遍历：换不上列表不该挡住这次的替身播放。
            }
            session = CloudMusic.beginNewSession(replacement.music);
            if (!isSessionUsable(session)) {
                session.invalidate();
                return false;
            }
            // 队列是面板列表的浅拷贝，行 widget 还绑在 oldSong 上：替身若走的是脚本流，
            // 徽标不回写这一行就永远显示不出来（验证关只写到了替身对象上）。
            CrossSourceSwitcher.propagateUnblockLabel(oldSong, replacement.music);
            currentlyPlaying = replacement.music;
            // 这里刻意不动待播账。两件事分开看：oldSong 就是正在开播的这一首，它在 {@link #playSong}
            // 起播那一刻已经销过账了；而这一格的身份是「正在播」，替身更不该继承任何待播位。
            // 更要紧的是二次换源（直链放不出来那一路）时 oldSong 可能是上一轮的替身，而那个身份
            // 恰好是用户在后面排着队的那首 —— 在这里 forget 一次就会把真待播的那格弄断。
            CoverThemePalette.set(replacement.music.getCoverPalette());
            if (personalFmActive) PersonalFmManager.noteFmTrackStarted(replacement.music);
            loadMusicCover(replacement.music);
            loadDynamicMusicCover(replacement.music);
            System.out.printf("[NCM] 自动换源: %s(%s) → %s - %s [%s] 匹配度 %.2f\n",
                    oldSong.getName(), oldSong.getSource(), replacement.music.getName(),
                    replacement.music.getArtistsName(), replacement.platformLabel, replacement.score);
            return true;
        }

        /** 上一次 {@link #initializeAndPlaySong} 是否因「只有试听片段」中止；只在 Play Thread 上读写。 */
        private boolean lastStreamIncomplete;

        private boolean initializeAndPlaySong(Music song, Tuple<String, String> playUrl, PlaybackSession targetSession) {
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.cancelDownload();
            lastStreamIncomplete = false;

            File musicFile;
            try {
                musicFile = getMusicFile(playUrl, song);
            } catch (Throwable e) {
                if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                    handlePlayerInitializationError(song, e);
                }
                return false;
            }

            // URL 获取、下载或解码期间可能已经切歌。旧线程绝不能覆盖新会话的播放器。
            if (!isSessionUsable(targetSession)) return false;

            synchronized (PLAYER_STATE_LOCK) {
                if (!isSessionUsable(targetSession)) return false;
                try {
                    player = initializePlayer(musicFile, song);
                    ownedPlayer = player;
                    targetSession.player = ownedPlayer;
                    // 必须捕 Throwable 而不是 Exception：下载与解码链路会抛 Error。随 mod 打包的
                    // jflac 在读 FLAC 的 VORBIS_COMMENT 时才第一次用到 VorbisString，PCM 分配失败
                    // 抛的是 OutOfMemoryError。这些 Error 从 catch (Exception) 漏出去会一直逃到
                    // run() 之外杀死 Play Thread：没有任何失败提示、playing 停在 true、灵动岛不收尾，
                    // 用户看到的就是"下载完成却不播放"。
                } catch (Throwable e) {
                    if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                        handlePlayerInitializationError(song, e);
                    }
                    return false;
                }
            }

            if (!isSessionUsable(targetSession)) return false;

            // 第三方搜索响应（GD Studio）不带时长。解码器现在知道了真实
            // 长度，所以在歌词/进度消费者读取它之前记录下来。
            AudioPlayer startedPlayer = ownedPlayer;
            long decodedMillis = startedPlayer == null ? 0L : (long) startedPlayer.getTotalTimeMillis();
            if (startedPlayer != null) {
                song.applyDecodedDuration(decodedMillis);
            }

            // 试听片段检测：非会员在别的平台也可能「取流成功、字节合法、解码无误」，只是
            // 60/30 秒就到底 —— 前两级换源触发（取流失败 / 流是坏的）都看不见这种「完整地
            // 播半首」。拿解码器报出的真实长度对上曲目声明时长，判据在 CrossSourceSwitcher，
            // 与换源共享同一本 AUTO_ATTEMPTED 账，短歌与时长未知的曲目不会被误伤。
            if (CrossSourceSwitcher.isIncompleteStream(song.getDuration(), decodedMillis)) {
                // 汽水在自动换源关闭时的特殊约定：试听也是用户此刻真实能拿到的东西——
                // 后端未就绪/未登录只是暂时的，30 秒片段照常播完比掐断更尊重「我就想听听」。
                // 文件照留（权益世代在缓存键里，世代一翻它自然失配），界面常驻「仅试听」标识
                // 说明现状；automix/手动切歌的预备路径不适用——下一曲要在无人值守时无缝出声，
                // 预备了半首到点才现出原形更糟，那边维持丢弃重预备。
                boolean autoSwitchable = CrossSourceSwitcher.canAutoSwitch(song);
                if (!autoSwitchable && song.isQishui()) {
                    song.setQishuiTrialResolve(true);
                    lastStreamIncomplete = false;
                    System.out.println("[Music/Qishui] 仅试听播放（自动换源未开启，不打断）："
                            + song.getName() + "，解码 " + (decodedMillis / 1000L) + "s / 声明 "
                            + (song.getDuration() / 1000L) + "s");
                } else {
                    lastStreamIncomplete = true;
                    if (startedPlayer != null) {
                        synchronized (PLAYER_STATE_LOCK) {
                            if (ownedPlayer == startedPlayer) {
                                try {
                                    startedPlayer.close();
                                } catch (Throwable ignored) {
                                    // 收尾失败不改变「这是不完整流」的判定。
                                }
                            }
                        }
                    }
                    // 缓存键只有「曲目 + 音质」，不认直链：这份试听片段必须删掉，否则以后开了
                    // 会员、官方直链能拿到完整流了，点歌却还命中这份 60 秒的坏缓存。删除排在
                    // close 之后 —— 解码器拿着这个文件的 RandomAccessFile 句柄，Windows 上
                    // 打开中的文件删不掉（File.delete 只会静默返回 false）。
                    try {
                        musicFile.delete();
                    } catch (Throwable ignored) {
                    }
                    return false;
                }
            }

            // 歌词异步加载绑定本 Session（两阶段提交），歌词可先到或后到。
            // 网盘歌曲额外携带本地缓存文件，用于优先读取音频标签中的内嵌 LRC/YRC。
            loadLyric(song, targetSession, musicFile);
            if (!startPlayback(song, playUrl, musicFile, targetSession)) return false;
            targetSession.audioActive = true;
            return true;
        }

        private void waitForPlaybackCompletion() {
            while (true) {
                if (doBreak || !isSessionUsable(session)) break;

                AudioPlayer activePlayer = ownedPlayer;
                if (activePlayer == null) break;
                // 归属校验后再推进歌词：切歌后新解码器已经在出声，而歌词还是上一首的时间轴，
                // 用新时钟去查旧时间轴查出来的行必然是错的（这就是"切歌歌词不同步"的根因）。
                if (CloudMusic.lyricsMatchCurrentTrack()) {
                    CloudMusic.updateCurrentLyric(activePlayer.getCurrentTimeMillis());
                }
                NeteasePlaybackHistoryReporter.observe(activePlayer);
                KuGouPlaybackReporter.observe(activePlayer);

                // 10 ms 监督 tick 兼任交叉淡化时钟：粒度足以
                // 平滑渐变，并且已经绑定了上面的会话/取消检查。
                // 拖进度条的收尾排在推进曲线之前：同一 tick 里先推进再收尾，等于明知这次混音
                // 已经作废还要再按旧曲线写一帧。
                if (seekDuringFadeRequested) {
                    seekDuringFadeRequested = false;
                    finishAutomixFade();
                }
                driveAutomixFade();
                if (manualSelection != null) {
                    if (tryManualSelectionHandover(activePlayer)) {
                        automixHandover = true;
                        return;
                    }
                    // 用户选择优先于自动点火。暂停期间不提交，旧歌自然结束也不偷播队列下一首。
                    if (manualSelection != null) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                }
                // 在预备簿记之前处理：maybeArmNextTrack 内部的队列修订号变化
                // 会丢弃已预备的播放牌，而早一个 tick 到达的请求不能连同它一起
                // 被丢弃，必须改为执行普通切换。
                if (tryManualNextHandover(activePlayer)) {
                    automixHandover = true;
                    return;
                }
                // 私人 FM 的下一首得先请求回来才存在，所以排在 arm 之前：预备成功会递增 queueRevision，
                // 紧接着的那次 arm 正好看到长出来的队列并立刻开始预解码。
                maybePrefetchPersonalFm(activePlayer);
                maybeArmNextTrack(activePlayer);
                // 必须排在 arm 之后同一 tick：arm 刚把这一首接下来时 arming/armed 已经置位，
                // 这一条看见就让路。它只补 arm 没做成的场合（见方法注释）。
                maybePrefetchNextTrackAudio(activePlayer);
                // 复查接缝必须排在预卷之前：它可能在这一个 tick 里换掉入点，而预卷是按
                // plan.fireMillis − preRollMillis 判的。顺序反了就会先按旧入点出声、再被换掉。
                maybeReplanArmedTrack(activePlayer);
                // 顶住交接只跳过这两步（预卷 + 点火），其余照旧：预备、歌词、上报都不该因为
                // 多等一条新接缝就停一停。预卷也要跳——它按旧点火点算，这时候卷了等新计划
                // 装上还得重新定位一次，等于白做一遍。
                if (!holdSeamForFreshCue(activePlayer)) {
                    maybePreRollArmedDeck(activePlayer);
                    if (tryAutomixHandover(activePlayer)) {
                        automixHandover = true;
                        return;
                    }
                }

                // 在交接尝试之后检查，这样刚结束的曲目仍可以交接给已预备的
                // 播放牌，而不是退回到关闭/下载/解码的空档。
                if (!playing.get()) break;

                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void handlePlaybackCompletion() {
            AudioPlayer completedPlayer = ownedPlayer;
            if (completedPlayer == null || isPlaybackCancelled()) return;

            NeteasePlaybackHistoryReporter.finish(completedPlayer,
                    NeteasePlaybackHistoryReporter.EndReason.COMPLETED);
            KuGouPlaybackReporter.finish(completedPlayer, KuGouPlaybackReporter.EndReason.COMPLETED);

            if (!dontAdd && playedFrom != null && curIdx >= 0 && curIdx < playList.size()) {
                playList.get(curIdx).updPlayCount(playedFrom, completedPlayer.getCurrentTimeSeconds());
            }

            synchronized (PLAYER_STATE_LOCK) {
                if (!isPlaybackCancelled() && ownedPlayer == completedPlayer) {
                    completedPlayer.close();
                }
            }
        }

        private void stopPreviousPlayer() {
            synchronized (PLAYER_STATE_LOCK) {
                AudioPlayer previousPlayer = CloudMusic.player;
                if (previousPlayer != null && !previousPlayer.isFinished()) {
                    previousPlayer.close();
                }
            }
            if (!isPlaybackCancelled()) sleep(250);
        }

        private void handleUnplayableSong(Music song) {
            api.printMessage(EnumChatColor.RED + "无法播放: " + song.getName() + " - " + song.getArtistsName());
            DownloadDynamicIsland.showPlaybackFailure(song.getName(), "没有可用音频源");

            System.err.printf("%s无法播放: %s - %s, 可能因为该歌曲没有版权\n", EnumChatColor.RED, song.getName(), song.getArtistsName());
        }

        private void handlePlayerInitializationError(Music song, Throwable e) {
            e.printStackTrace();
            String message = rootCauseMessage(e);
            DownloadDynamicIsland.showPlaybackFailure(song == null ? "当前歌曲" : song.getName(),
                    message.isEmpty() ? "音频加载或解码失败" : message);
            System.err.printf(EnumChatColor.RED + "[NCM] Failed to initiate audio player! Error: %s\n", message);
        }

        /** 暴露最深层的原因，使解码/内存限制被解释而不是被隐藏。 */
        private String rootCauseMessage(Throwable throwable) {
            Throwable cause = throwable;
            String message = "";
            int depth = 0;
            while (cause != null && depth++ < 6) {
                String candidate = cause.getMessage();
                if (candidate != null && !candidate.trim().isEmpty()) {
                    message = candidate.trim();
                }
                cause = cause.getCause();
            }
            return message.length() > 90 ? message.substring(0, 90) : message;
        }

        private boolean startPlayback(Music song, Tuple<String, String> playUrl, File musicFile,
                                      PlaybackSession targetSession) {
            AudioPlayer activePlayer = ownedPlayer;
            if (activePlayer == null || !isSessionUsable(targetSession)) return false;

            try {
                synchronized (PLAYER_STATE_LOCK) {
                    if (!isSessionUsable(targetSession) || ownedPlayer != activePlayer) return false;
                    activePlayer.play();
                }
            } catch (ChannelMismatchException mismatch) {
                try {
                    activePlayer.player.cleanUp();
                    musicFile.delete();
                    if (!isSessionUsable(targetSession)) return false;

                    File retryFile = getMusicFile(playUrl, song);
                    synchronized (PLAYER_STATE_LOCK) {
                        if (!isSessionUsable(targetSession)) return false;
                        player = initializePlayer(retryFile, song);
                        ownedPlayer = player;
                        targetSession.player = ownedPlayer;
                        activePlayer = ownedPlayer;
                        activePlayer.play();
                    }
                } catch (Throwable retryFailure) {
                    if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                        handlePlayerInitializationError(song, retryFailure);
                    }
                    return false;
                }
            }

            if (!isSessionUsable(targetSession)) return false;
            playing.set(true);
            // 进阶模式：这首歌<b>现在是出场方</b>了，下一条交接要的是它的整曲包络。起播这一刻
            // 起扫，预算就是整首歌的播放时长——这是唯一来得及的时机，预备窗只有 30 秒。
            // 总开关关闭时这个方法第一行就返回：不排队、不起线程、不读盘。
            warmAdvancedEnvelope(activePlayer);
            NeteasePlaybackHistoryReporter.start(song, currentPlaylistContext, activePlayer);
            KuGouPlaybackReporter.start(song, activePlayer);
            QishuiPlaybackReporter.start(song, currentPlaylistContext);
            final AudioPlayer callbackPlayer = activePlayer;
            callbackPlayer.setAfterPlayed(() -> {
                // 已被下一首复用/替换的播放器回调不能结束新会话。
                if (ownedPlayer == callbackPlayer && isSessionUsable(targetSession)) {
                    this.notifyWaitLock();
                }
            });
            return true;
        }

        private void preloadNextCover() {
            if (curIdx + 1 < playList.size()) {
                loadMusicCover(playList.get(curIdx + 1));
            }
        }

        private void updateCurrentIndex() {
            updateCurIdx();
        }

        private File getMusicFile(Tuple<String, String> playUrl, Music song) {
            String url = playUrl.getA();
            String reportedType = AudioContainerSupport.normalizeReportedContainer(playUrl.getB());
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("Music URL is empty");
            }

            // CDN 扩展名和 API 上报的类型仅供参考。缓存以
            // 下面下载到的字节来命名，这也让 M4A/AAC 在 API
            // 上报通用或不正确的 MIME 类型时能安全回退。
            try {
                return getCachedOrTempFile(url, reportedType, song);
            } catch (RuntimeException primaryFailure) {
                return getValidationFallbackFile(song, url, primaryFailure);
            }
        }

        /**
 * 一个 URL 在语法上可能有效，但实际返回的是 DASH 视频或其他非音频 MP4
 * 载荷。一旦字节级校验拒绝了这样的主响应，在把歌曲判定为不可播放之前，
 * 先重试一条符合来源的流：网易云回退到其标准 MP3 端点，
 * GD Studio 曲目回退到另一个健康的 GD 源。
 */
        private File getValidationFallbackFile(Music song, String failedUrl, RuntimeException primaryFailure) {
            if (song == null || !(song.isNetease() || song.isGd())) {
                throw primaryFailure;
            }

            Tuple<String, String> standardPlayUrl = song.isGd()
                    ? song.getGdCrossSourceFallbackPlayUrl()
                    : song.getStandardMp3PlayUrl();
            if (standardPlayUrl == null || standardPlayUrl.getA() == null
                    || standardPlayUrl.getA().trim().isEmpty()
                    || standardPlayUrl.getA().equals(failedUrl)) {
                throw primaryFailure;
            }

            try {
                System.err.println("[NCM] Primary response failed byte-level audio validation for "
                        + song.getStableKey() + "; retrying "
                        + (song.isGd() ? "another GD source." : "standard MP3."));
                return getCachedOrTempFile(standardPlayUrl.getA(),
                        AudioContainerSupport.normalizeReportedContainer(standardPlayUrl.getB()), song);
            } catch (RuntimeException standardFailure) {
                primaryFailure.addSuppressed(standardFailure);
                throw primaryFailure;
            }
        }

        /**
         * 缓存名里「音质之前」的那一段。
         *
         * <p>酷狗源多带一段 {@link KuGouMusicApi#AUDIO_CACHE_EPOCH}：交付校验上线前落盘的酷狗缓存
         * 可能装着另一首歌（上游对取不到的 hash 会静默交付别人的文件），而缓存名只由「曲目 + 音质」
         * 决定，键不变就会被一直命中，只修取流等于没修。换掉这一段，旧文件自然失配，
         * 随后由容量兜底回收。</p>
         *
         * <p>汽水同理带一段 {@link QishuiMusicService#audioCacheEpoch()}：登录态/账号 VIP/后端可用性
         * 任一翻转就换一代——后端未就绪窗口里落盘的试听文件，不能在就绪后继续被当成完整版命中
         * （键原样不变的话，点歌直接命中缓存，重新解析出的完整流 URL 会被丢弃）。其它音源
         * 没有这个问题，名字保持原样，不牵连它们的缓存。</p>
         */
        private static String audioCachePrefix(Music song) {
            return song.getStableKey() + "_"
                    + (song.isKuGou() ? KuGouMusicApi.AUDIO_CACHE_EPOCH : "")
                    + (song.isQishui() ? QishuiMusicService.audioCacheEpoch() + "_" : "");
        }

        /** 下载缓存的主键（不含扩展名），淘汰分组的组名也因此天然带上世代标记。 */
        private static String audioCacheKey(Music song, String qualityId) {
            return audioCachePrefix(song) + qualityId;
        }

        private static final Object[] AUDIO_PREPARATION_LOCKS = new Object[64];
        static {
            Arrays.setAll(AUDIO_PREPARATION_LOCKS, index -> new Object());
        }

        private static Object audioPreparationLock(Music song, String fallback) {
            String key = song == null ? fallback : song.getStableKey();
            return AUDIO_PREPARATION_LOCKS[(key.hashCode() & Integer.MAX_VALUE) % AUDIO_PREPARATION_LOCKS.length];
        }

        /**
 * CDN URL 声明的格式并不总是服务器实际返回的容器。尤其是观察到一些无损
 * 网易云 URL 在报告 MP3 类型的同时返回 FLAC 字节。SoundFile 按扩展名选择解码器，
 * 所以缓存扩展名必须跟随下载到的字节，而不是上报的 URL 类型。
 *
 * <p>本方法只剩委托：真正的落盘序列在 {@link #cachedAudioFile}，与房间的提前下载共用同一条。
 * 实播比那条序列多出来的唯一一步是 {@link #resolvePlayableAudioFile}（AAC/MP4 转 WAV 边车），
 * 那是「能不能出声」的事，提前下载不做。</p>
 */
        private File getCachedOrTempFile(String playUrl, String type, Music song) {
            return resolvePlayableAudioFile(cachedAudioFile(playUrl, type, song, quality, true), song);
        }

        /**
 * 把一首歌按实播的缓存键落到磁盘：<b>实播与房间提前下载共用的唯一一条序列</b>。
 *
 * <p>为什么收成一个静态方法：缓存键由「曲目 + 音质」决定，提前下载只要在这里之外另写一份
 * 算法，哪怕只差一个字节，实播就永远命中不上那份预下载，功能整体退化成「白下一遍」。
 * 所以 {@link #audioCacheKey} 的调用点全文件只有这一处；容器探测命名、{@code touch}、
 * {@link #removeOtherQualityCaches}、容量兜底、汽水 DRM 解密也全在这里，谁都不再第二遍。</p>
 *
 * <p>音质由调用方<b>显式传进来</b>，本方法不读 {@code CloudMusic.quality} 那一个全局：原写法
 * 在同一次调用里读了两次（算键一次、删其余音质一次），中途换音质会留下一个删不掉的孤儿缓存。
 * 参数名刻意沿用 {@code quality}（遮蔽那个全局）正是为了这件事 —— 整条序列自始至终只有调用方
 * 取走的那一档，读到第二遍的口子被关掉了。看到 {@code quality.getQuality()} 别以为在读全局。</p>
 *
 * @param quality           这一首要按哪一档音质落盘 —— 也就是缓存键里的音质那一段
 * @param downloadIfMissing {@code false} = 只问磁盘，没有缓存就回 {@code null}，
 *                          一个网络请求都不发（提前下载问「下好了吗」、profile 预热问「文件在哪」用）。
 *                          {@code true} = 实播那一条路，没有就下。
 */
        static File cachedAudioFile(String playUrl, String type, Music song, Quality quality,
                                    boolean downloadIfMissing) {
            // 只读探测不能被后台下载挡住；半成品不会以音频扩展名暴露。
            if (!downloadIfMissing) return cachedAudioFileLocked(playUrl, type, song, quality, false);
            // 手动点歌可能与自动预取命中同一曲；复用在途缓存，而不是互删同一个 .download。
            synchronized (audioPreparationLock(song, playUrl)) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Audio preparation cancelled");
                return cachedAudioFileLocked(playUrl, type, song, quality, downloadIfMissing);
            }
        }

        private static File cachedAudioFileLocked(String playUrl, String type, Music song, Quality quality,
                                                  boolean downloadIfMissing) {
            File musicCacheDir = MusicAudioCache.directory();
            if (!musicCacheDir.exists() && !musicCacheDir.mkdirs()) {
                throw new IllegalStateException("Unable to create music cache directory: " + musicCacheDir.getAbsolutePath());
            }

            String cacheKey = audioCacheKey(song, quality.getQuality());
            File cachedMusic = AudioCacheFiles.findCachedAudioFile(musicCacheDir, cacheKey, type);
            if (cachedMusic != null) {
                // 命中也要记一笔：容量淘汰只看 lastModified 的话，一首常听但下载得早的歌
                // 会被当成最冷的缓存删掉。
                MusicAudioCache.touch(cachedMusic);
                return cachedMusic;
            }
            if (!downloadIfMissing) {
                return null;
            }

            // 绝不把下载了一半的文件暴露给解码器或缓存查找。
            File temporaryMusic = new File(musicCacheDir, cacheKey + ".download");
            if (temporaryMusic.exists() && !temporaryMusic.delete()) {
                throw new IllegalStateException("Unable to replace incomplete music download: " + temporaryMusic.getName());
            }

            downloadMusic(playUrl, temporaryMusic, song);
            // 汽水 DRM：房间队列里的曲目是从协议元数据重造出来的 Music，playAuth 是 transient
            // 字段，压根不会随队列下发 —— 这个分支对提前下载天然不成立，留着是因为实播要它。
            if (song != null && song.isQishui() && !song.getQishuiPlayAuth().isEmpty()) {
                try {
                    QishuiMusicService.decryptDownloadedAudio(temporaryMusic, song.getQishuiPlayAuth());
                } catch (java.io.IOException error) {
                    throw new IllegalStateException("Unable to decrypt Qishui audio", error);
                } finally {
                    song.clearQishuiPlayAuth();
                }
            }
            if (!temporaryMusic.isFile() || temporaryMusic.length() < 4L) {
                throw new IllegalStateException("music download failed or was interrupted: " + temporaryMusic.getName());
            }

            String actualType = AudioContainerSupport.detectContainer(temporaryMusic);
            if (!AudioContainerSupport.isSupportedContainer(actualType)) {
                temporaryMusic.delete();
                throw new IllegalStateException("Downloaded audio has an unsupported or invalid container: " + cacheKey);
            }

            File music = new File(musicCacheDir, cacheKey + "." + actualType);
            if (music.exists()) {
                String existingType = AudioContainerSupport.detectContainer(music);
                if (actualType.equals(existingType)) {
                    temporaryMusic.delete();
                } else {
                    music.delete();
                    AudioCacheFiles.moveCacheFile(temporaryMusic, music);
                }
            } else {
                AudioCacheFiles.moveCacheFile(temporaryMusic, music);
            }

            removeOtherQualityCaches(musicCacheDir, song, quality.getQuality());
            MusicAudioCache.touch(music);
            // 目录只会因为下载而变大，所以容量兜底只需要挂在这一处；解码边车在实播那一句产生，
            // 淘汰按组进行，所以这里先执行也不会把边车漏在外面。
            MusicAudioCache.enforceLimitAsync();
            return music;
        }

        /**
 * JSyn 可以直接流式播放 MP3、FLAC 和 WAV。AAC/ADTS 与 ISO-BMFF 载荷会被解码
 * 一次并写入经过校验的 WAV 边车。解码器只接受 AAC 音轨，因此纯视频、
 * DRM 保护或不支持的 MP4 流仍然会安全失败。
 */
        static File resolvePlayableAudioFile(File sourceFile, Music song) {
            // AAC/WAV 边车也只能有一个写入者；锁在转码结束就释放，不包含模型分析。
            synchronized (audioPreparationLock(song, sourceFile.getAbsolutePath())) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Audio preparation cancelled");
                return resolvePlayableAudioFileLocked(sourceFile, song);
            }
        }

        private static File resolvePlayableAudioFileLocked(File sourceFile, Music song) {
            String container = AudioContainerSupport.detectContainer(sourceFile);
            if (!AudioContainerSupport.isSupportedContainer(container)) {
                throw new IllegalStateException("Cached audio has an unsupported or invalid container: "
                        + sourceFile.getName());
            }
            if (!AudioContainerSupport.requiresAacDecode(container)) {
                return sourceFile;
            }

            File decodedFile = AudioCacheFiles.getDecodedWavFile(sourceFile);
            if (AudioCacheFiles.isReusableDecodedWav(sourceFile, decodedFile)) {
                return decodedFile;
            }
            if (decodedFile.exists() && !decodedFile.delete()) {
                throw new IllegalStateException("Unable to replace invalid decoded audio cache: "
                        + decodedFile.getName());
            }

            long transcodeStartedAt = System.currentTimeMillis();
            // 卡片上写歌名和真实容器，别再暴露 <缓存键>_<音质>.mp4 这种磁盘名；
            // 拿不到歌名时 trackDisplayName 会退到「当前歌曲」。
            String trackName = trackDisplayName(song, null);
            String targetFormat = audioFileExtension(decodedFile.getName());
            // 房间预热走到这里时不给当前曲目挂「转码中」卡片：转的不是正在播的这一首。
            final boolean quietTranscode = quietDownload();
            if (!quietTranscode) {
                DownloadDynamicIsland.beginTranscode(trackName, container, targetFormat);
            }
            try {
                AacAudioDecoder.decodeToWav(sourceFile, container, decodedFile,
                        new AacAudioDecoder.ProgressListener() {
                            @Override
                            public void onProgress(double progress) {
                                if (!quietTranscode) {
                                    DownloadDynamicIsland.updateTranscodeProgress(trackName, progress);
                                }
                            }
                        });
                if (!AudioCacheFiles.isReusableDecodedWav(sourceFile, decodedFile)) {
                    throw new IOException("Decoded AAC cache is invalid: " + sourceFile.getName());
                }
            } catch (Throwable exception) {
                if (!quietTranscode) {
                    DownloadDynamicIsland.cancelTranscode(trackName);
                }
                if (decodedFile.exists()) {
                    decodedFile.delete();
                }
                if (sourceFile.exists() && !sourceFile.delete()) {
                    System.err.println("[NCM] Unable to remove undecodable AAC cache: " + sourceFile.getName());
                }
                throw new IllegalStateException("Unable to decode AAC audio into a playable WAV cache", exception);
            }
            if (!quietTranscode) {
                DownloadDynamicIsland.finishTranscode(trackName, container, targetFormat,
                        System.currentTimeMillis() - transcodeStartedAt);
            }
            return decodedFile;
        }

        /** 取缓存文件的后缀作为转码卡片上的格式标签，例如 ".decoded.wav" 取到 "wav"。 */
        private static String audioFileExtension(String fileName) {
            if (fileName == null) {
                return null;
            }
            int extensionIndex = fileName.lastIndexOf('.');
            if (extensionIndex < 0 || extensionIndex == fileName.length() - 1) {
                return null;
            }
            return fileName.substring(extensionIndex + 1).toLowerCase(java.util.Locale.ROOT);
        }

        private void discardLegacyDecodedWav(File sourceFile) {
            File decodedFile = AudioCacheFiles.getDecodedWavFile(sourceFile);
            if (decodedFile.exists() && !decodedFile.delete()) {
                System.err.println("[NCM] Unable to remove legacy MP4 decoded cache: " + decodedFile.getName());
            }
        }

        private static void removeOtherQualityCaches(File musicCacheDir, Music song, String currentQuality) {
            MultiThreadingUtil.runAsync(() -> {
                File[] cacheFiles = musicCacheDir.listFiles();
                if (cacheFiles == null) {
                    return;
                }

                // 前缀从同一个构造函数取：换了缓存名规则（例如酷狗的世代标记）也不会漏删别的音质。
                String allQualitiesPrefix = audioCachePrefix(song);
                String currentQualityPrefix = allQualitiesPrefix + currentQuality;
                for (File file : cacheFiles) {
                    if (file.getName().startsWith(allQualitiesPrefix) && !file.getName().startsWith(currentQualityPrefix)) {
                        file.delete();
                    }
                }
            });
        }

        private static String trackDisplayName(Music song, File file) {
            String name = song == null ? null : song.getName();
            if (name != null && !name.trim().isEmpty()) return name;
            return file == null || file.getName() == null ? "当前歌曲" : file.getName();
        }
        private AudioPlayer initializePlayer(File musicFile, Music song) {
            AudioPlayer player = CloudMusic.player;
            if (player == null) {
                player = new AudioPlayer(musicFile);
                player.setVolume(CloudMusic.getVolume());
                CloudMusic.player = player;
                player.setTrackName(trackDisplayName(song, musicFile));
            } else {
                player.setAudio(musicFile);
                player.setTrackName(trackDisplayName(song, musicFile));
            }
            return player;
        }

        private void notifyWaitLock() {
            playing.set(false);
        }

        // ─────────────────────────── automix（无缝交接） ───────────────────────────
        //
        // 原来的流程是：曲目结束 → 关闭播放器 → sleep(250) → 解析 URL →
        // 下载 → 解码 → 开始播放。那是两首歌之间数秒的静默。Automix 把所有这些
        // 工作提前：当前曲目仍在播放时，下一首就被解析、解码
        // 并静默保持在第二副播放牌上，到计划时刻两副播放牌交叉淡化。
        // 关闭 AutomixSettings 后这一切都不运行，原流程原样使用。

        /** 下一首曲目可以开始预备的播放位置之后。 */
        private static final long EARLY_ARM_MILLIS = 10_000L;
        /**
         * 私人 FM 开始预备下一首的播放位置。比 {@link #EARLY_ARM_MILLIS} 更早：预备本身要走一次网络
         * 请求，拿回来之后 automix 还要解析、下载、可能转码、再解码，越早接上队列越有机会真的无缝。
         * 又不是 0：用户连点跳过时每首都立刻打一次接口纯属浪费，还会把推荐白白塞进去重表。
         */
        private static final long FM_PREFETCH_AFTER_MILLIS = 5_000L;
        /** 每首歌允许的预备次数；连续失败就让位给"播完再请求下一批"的旧兜底路径。 */
        private static final int MAX_FM_PREFETCH_ATTEMPTS = 3;
        /** 预备失败后的冷却，避免接口异常时被 10ms 的监督 tick 反复敲。 */
        private static final long FM_PREFETCH_RETRY_MILLIS = 15_000L;
        /** automix 放弃并让普通切换运行之前，每首歌允许的尝试次数。 */
        private static final int MAX_ARM_ATTEMPTS = 3;
        /** 预备尝试之间的冷却时间，避免每个 tick 都敲打一个失败的源。 */
        private static final long ARM_RETRY_MILLIS = 20_000L;
        /**
         * 音频预取最早开始的位置。设得很早，但它真正的开始时间不由这里决定：automix 开着时，
         * 它要等逐首预备把自己那三份预算用完（一份冷却 20 秒）才让过来，那道闸才是实际下限。
         * 这里只管「别在开播第一秒就去抢」。
         *
         * <p>为什么值得提前：YouTube 那一份配额是一台出口 IP 的字节余额，约「一秒攒一秒播放量」，
         * 一首四分钟的歌最坏要等将近曲长才取得完。这段等待消不掉，但能挪到上一首还在响的时候花掉。
         * 汽水更极端：它落盘之后还有一次整曲 AAC→WAV 转码（见
         * {@link CloudMusic#worthAudioPrefetch}），那一趟以前是几十秒量级，现在换成 ffmpeg
         * 快路径也要一两秒。同样是消不掉的，同样只能挪到前面花。</p>
         */
        private static final long AUDIO_PREFETCH_AFTER_MILLIS = 3_000L;
        /** 每首歌允许的音频预取次数；失败几次就认了，真轮到它时实播那条链会自己重走。 */
        private static final int MAX_AUDIO_PREFETCH_ATTEMPTS = 2;
        /** 音频预取失败后的冷却（预取本身可能跑几分钟，冷却给得比 arm 宽）。 */
        private static final long AUDIO_PREFETCH_RETRY_MILLIS = 60_000L;
        /** 交接触发时（几乎）没有剩余离场曲目时使用的渐变。 */
        private static final long LATE_FIRE_FADE_MILLIS = 700L;
        /**
 * 用户自己按下"下一首"时使用的渐变。
 *
 * <p>计划的交叠以小节计算，可能长达数秒：适合听众没有要求的混音，
 * 但对按钮按压来说太慢，后者必须感觉立即响应。略低于一秒
 * 足够短以读作"已切换"，又足够长以保持增益渐变而不是接缝处的咔嗒声。</p>
 */
        private static final long MANUAL_SKIP_FADE_MILLIS = 1_200L;
        /**
 * 在计划的交接前多久，静默启动已预备的播放牌。
 *
 * <p>播放牌通过一个小的滑动窗口从磁盘流式读取 PCM，该窗口由 JSyn 引擎线程
 * 补充，启动它还会向运行中的合成器添加单元并重新接线其电路。在交叉淡化
 * 的瞬间做这些，会把文件寻道、一次读取和一次图结构变更放进正在同时服务
 * 离场播放牌的音频回调里——在应该无缝的地方听起来就是一次卡顿。所以播放牌
 * 提前以完全静默启动，接缝本身只移动增益。</p>
 */
        private static final long PRE_ROLL_MILLIS = 400L;

        /**
 * 在静默播放牌被视为过期之前，离场曲目可以落后预卷点多远。
 * 只有故意的 seek 才会把它推到那么远；普通 tick 抖动只有 10 ms。
 */
        private static final long PRE_ROLL_REWIND_SLACK_MILLIS = 500L;

        /** 下一首曲目，已完全解码并在空闲播放牌上静默等待。 */
        private static final class ArmedTrack {
            private final Music song;
            private final File file;
            private final AudioPlayer deck;
            private final int index;
            /** 预备所针对的播放代数；更新的代数意味着预备已过期。 */
            private final long generation;
            /** 预测所依据的队列修订号；更新的修订号意味着预测错误。 */
            private final long queueRevision;
            private volatile AutomixPlan plan;
            /**
             * 该预备是为一起听房间准备的（计划来自 {@link RoomAutomix}）。
             *
             * <p>它同时意味着这份计划的来源是房主那一台：交接点、入口点与曲线都是房主用
             * 真正的音频分析算出来的，本机只负责照做。这一点很重要 —— 房间里的位置由服务端的
             * 权威时钟决定，本机若自己另算一条曲线，两台机器就会在同一刻叠出两条不同的波形。</p>
             *
             * <p>房间里的换歌点同样由服务端决定，本机按自己的音频位置推算会差出网络抖动，
             * 因此这类预备的交接以服务端下发的帧为触发（见 {@link CloudMusic#roomAutomixHandover()}），
             * 本地推算只作兜底。</p>
             */
            private volatile boolean roomMode;
            /** 实际可用的静默导入，即 min(PRE_ROLL_MILLIS, plan.incomingCueMillis)。 */
            private volatile long preRollMillis;
            /** 该播放牌已在（静默地）出声，不得再次入队或启动。 */
            private volatile boolean preRolled;
            /** 当播放本身暂停时，预卷播放牌被保持暂停期间为 true。 */
            private volatile boolean preRollPaused;
            /**
             * 规划那一刻<b>两份整曲包络都在</b>（进阶模式）。
             *
             * <p>它为「要不要回头重算一次」而存在：配对选点要读整曲包络，而包络是起播之后才在
             * 后台扫的。预备发生得很早（出场曲播到 10 秒就开始了），于是第一次播的一对歌很可能
             * 踩着<b>没证据的那次规划</b>定下来。这个位就是「那次没证据」的记账；进阶模式关闭时
             * 它恒为 true，因为那时候根本不存在需要等的证据。</p>
             */
            private volatile boolean advancedEvidence = true;
            /** 证据到货那一次复查的记号（不论结果）——补算只许一遍，不许每 tick 抖一次接缝。 */
            private volatile boolean replanned;
            /**
             * 这条预备一共重算过几次（两个触发源合在一起记）：证据到货与「播放头越过点火点」
             * 都要花这份预算，拖十次进度条不该烧十趟整曲分析。
             */
            private volatile int replanCount;
            /** 复查正在后台算：算完把结果放进 {@link #replannedPlan}，由播放线程决定装不装。 */
            private volatile boolean replanning;
            /**
             * 后台算好的新计划，等播放线程装上；没有就为 {@code null}。
             *
             * <p>为什么绕这一道：换接缝要按新入点重新 {@code prepareStart}，而这张牌的入点归
             * 播放线程管（预卷、提升都在这里）。后台只算不改，装上时就不需要任何锁，也不可能
             * 出现「后台正在改入点、播放线程同时开始出声」这种两边都以为自己在负责的事。</p>
             */
            private volatile AutomixPlan replannedPlan;

            /**
             * 已经接到这张牌上的<b>人声素材</b>；没接上时为 {@code null}。
             *
             * <p>留着它只为一件事：证据到货后重算的接缝可能换一个入点，而素材只覆盖歌曲开头那一窗。
             * 入点跑出窗口时要把素材整个退掉 —— 接了素材就意味着带限抵消器被按在 0，留着一份读不到
             * 的素材等于两条路同时不管这条 deck 的人声。</p>
             */
            private volatile FoliaVocalStem vocalMaterial;

            private ArmedTrack(Music song, File file, AudioPlayer deck, int index, long generation,
                               long queueRevision) {
                this.song = song;
                this.file = file;
                this.deck = deck;
                this.index = index;
                this.generation = generation;
                this.queueRevision = queueRevision;
            }
        }

        /**
 * 进行中的交叉淡化。由墙钟驱动，使延迟尖峰无法拉长渐变，
 * 但播放暂停时时钟被保持静止——否则混音期间的暂停
 * 会让渐变在只有离场播放牌出声的情况下跑完。
 */
        private static final class AutomixFade {
            private final AudioPlayer outgoing;
            private final AudioPlayer incoming;
            private final AutomixPlan plan;
            /**
 * 有效渐变长度。通常为 {@code plan.overlapMillis}，但当交接触发较晚时会缩短：
 * 在离场曲目已经结束后用四秒渐入，会让新歌从近乎静默中打开。
 */
            private final long overlapMillis;
            /**
 * 渐变读取进度 0 时的墙钟时间。
 *
 * <p>{@code volatile} 因为 JSyn 音频任务也会采样它：见
 * {@link #writeTransitionFrameOnAudioThread()}。</p>
 */
            private volatile long startedAt;
            private long tickedAt;
            /** 仅当该淡化暂停了离场播放牌时为 true，确保它绝不会被意外恢复。 */
            private boolean outgoingPaused;
            /**
 * 活动播放牌暂停时要保持的进度，渐变运行时为 {@code -1}。
 *
 * <p>{@link #driveAutomixFade()} 在暂停时已经冻结 {@link #startedAt}，但它
 * 每个 tick 只能做一次；tick 之间墙钟会继续移动音频任务正在采样的渐变。
 * 发布保持的进度可以让音频任务在这些 tick 之间静止不动，
 * 而不是前冲之后再被拉回来。</p>
 */
            private volatile double pausedProgress = -1.0;
            /**
 * 音频任务写入最后一帧的墙钟时间，从未写入时为 {@code 0}。
 *
 * <p>tick 读取它来决定是否仍拥有曲线写入权：一个未能注册、抛错或不再被调用的
 * 音频任务只是停止更新它，tick 会在 {@code AUDIO_DRIVEN_WINDOW_MILLIS} 内重新接管。
 * 这样更高分辨率的路径永远不会把正常的过渡变成卡死的过渡。</p>
 */
            private volatile long audioDrivenAt;

            private AutomixFade(AudioPlayer outgoing, AudioPlayer incoming, AutomixPlan plan,
                                long overlapMillis, long startedAt) {
                this.outgoing = outgoing;
                this.incoming = incoming;
                this.plan = plan;
                this.overlapMillis = Math.max(1L, overlapMillis);
                this.startedAt = startedAt;
                this.tickedAt = startedAt;
            }
        }

        /**
 * 预测 {@link #updateCurIdx()} 将落在哪个下标，不触碰任何队列状态。
 * 只要下一首无法提前得知（挂起的手动切换、周期结束时的随机模式重排、
 * 仍需拉取的 FM 批次），就返回 -1，此时 automix 干脆不预备，
 * 由普通切换运行。
 */
        private int peekNextIndex() {
            List<Music> queue = playList;
            if (queue == null || queue.isEmpty()) return -1;
            if (dontAdd) return -1;
            if (personalFmActive) {
                int next = curIdx + 1;
                return next < queue.size() ? next : -1;
            }
            if (playMode == PlayMode.LoopSingle) {
                if (hasQueuedNext()) {
                    int queuedNext = curIdx + 1;
                    return queuedNext < queue.size() ? queuedNext : -1;
                }
                // 单曲循环重播的就是这同一首曲目。把它混音到它自身，会在每次重复时截掉它的结尾，
                // 而这恰恰与"单曲循环"的意图相反，因此不预备任何东西，
                // 曲目会播放到其自然结束后再重新开始。
                return -1;
            }
            int next = curIdx + 1;
            if (next < queue.size()) return next;
            // 随机播放会在队列循环时重新洗牌，因此这里的下一首曲目是真正未知的。
            if (playMode == PlayMode.LoopInList) return 0;
            return -1;
        }

        /**
         * 预备只有在当前这一代播放世代仍是最新一代、且队列仍预测同一首下一首曲目时才有用。
         * 用"播放下一首"排队其他内容会改变这一预测，因此修订号是有效性检查的一部分。
         */
        private boolean armStillValid(long armGeneration, long armRevision) {
            return !isPlaybackCancelled() && !playListChanged() && !doBreak
                    && manualSelection == null
                    && generation.get() == armGeneration
                    && CloudMusic.queueRevision == armRevision
                    && AutomixSettings.isEnabled();
        }

        /** 清除预备相关的簿记状态，让新启动的曲目拥有自己的尝试次数。 */
        private void resetArmState() {
            armAttempts = 0;
            nextArmAt = 0L;
            // 一个从未被服务的请求（其 session 先已消亡）不得作用于接下来启动的曲目，
            // 因为用户要求退出的是另一首歌。
            manualSkipRequested = false;
            // 预备下一首同样按"每首歌一份预算"计：交接到新的一首之后要能重新预备。
            roomHandoverRequested = false;
            fmPrefetchAttempts = 0;
            nextFmPrefetchAt = 0L;
            // 试听救援的乒乓预算同样一首一份：交接落到新的一首之后要能再救。
            armTrialSwitches = 0;
            // 音频预取的预算也按「一首一份」重开：交接落到新的一首，就要能再替它的下一首选一次。
            audioPrefetchAttempts = 0;
            nextAudioPrefetchAt = 0L;
        }

        /**
         * 私人 FM 专属：在当前这首还在放的时候就把下一首推荐拉回来接到队列尾部。
         *
         * <p>普通歌单的下一首本来就躺在队列里，automix 直接预解码即可；私人 FM 的下一首却必须先发一次
         * 请求才存在，所以旧流程只能"播完 → 请求 → 重开播放线程"，中间的静音躲不掉，automix 也因为
         * {@code peekNextIndex()} 恒为 -1 而完全没生效。</p>
         *
         * <p>刻意不看 {@link AutomixSettings#isEnabled()}：即使用户关掉了无缝切换，提前把下一首接进队列
         * 也能省掉那一段"播完才开始下载解码"的空白，效果上就是普通的顺序播放。解析失败等待用户操作
         * （{@code awaitingPlaybackAction}）或用户正在手动切歌（{@code dontAdd}）时不预备，避免替一条
         * 马上要被换掉的队列白跑一次请求。</p>
         */
        private void maybePrefetchPersonalFm(AudioPlayer activePlayer) {
            if (!personalFmActive || dontAdd || awaitingPlaybackAction) return;
            if (fmPrefetchAttempts >= MAX_FM_PREFETCH_ATTEMPTS) return;
            if (activePlayer == null || !isSessionUsable(session)) return;

            List<Music> queue = playList;
            // 只有队尾就是正在播的这一首时才需要预备；已经有下一首就什么都不做。
            if (queue == null || curIdx < 0 || curIdx + 1 != queue.size()) return;

            long now = System.currentTimeMillis();
            if (now < nextFmPrefetchAt) return;

            long total = (long) activePlayer.getTotalTimeMillis();
            long position = (long) activePlayer.getCurrentTimeMillis();
            if (total <= 0L) return;

            // 播够一段时间，或者已经逼近尾声、再不预备就赶不上了。以先到者为准，和 arm 的判定同构。
            long lead = AutomixSettings.getOverlapMillis() + AutomixSettings.ARM_LEAD_MILLIS;
            if (position < FM_PREFETCH_AFTER_MILLIS && total - position > lead) return;

            fmPrefetchAttempts++;
            nextFmPrefetchAt = now + FM_PREFETCH_RETRY_MILLIS;
            PersonalFmManager.prefetchNextAsync(queue);
        }

        /**
         * 当前这首还在放的时候，把下一首的音频按实播那套缓存键提前落到磁盘。
         *
         * <p><b>逐首预备</b>那条链（{@link #maybeArmNextTrack} → {@code armTrack}）本来就在做这件事，
         * 而且做得更多：它还顺手解码并持有第二张播放牌。所以这一条只补它没做成的那些场合 ——
         * 用户关掉了无缝切换、它这一首三次尝试都没成、或它下一首根本猜不出来。判定就用它自己的
         * 那几个状态位，不加第二套协商：</p>
         *
         * <ul>
         *   <li>{@code arming}／{@code armed} 有一个活着 → 让路，那张牌已经在下了；</li>
         *   <li>automix 开着而 {@code armAttempts} 还没用完 → 也让路，先让 arm 把它那三份预算
         *       试完（它一次冷却 20 秒，最晚约在开播一分钟后交棒给这里）。</li>
         * </ul>
         *
         * <p>只做「落到磁盘」这一步，不建播放牌、不碰 {@code curIdx} 与队列，也绝不改动正在播的
         * 这一首的任何状态：解析下一首时写入的是它自己那个 {@code Music} 对象上的播放侧字段，
         * 与逐首预备那条链做的是同一件事。</p>
         */
        private void maybePrefetchNextTrackAudio(AudioPlayer activePlayer) {
            if (prefetchingAudio || arming || armed != null) return;
            if (AutomixSettings.isEnabled() && armAttempts < MAX_ARM_ATTEMPTS) return;
            if (audioPrefetchAttempts >= MAX_AUDIO_PREFETCH_ATTEMPTS) return;
            if (dontAdd || awaitingPlaybackAction || manualSkipRequested) return;
            if (activePlayer == null || !isSessionUsable(session)) return;

            long now = System.currentTimeMillis();
            if (now < nextAudioPrefetchAt) return;

            long total = (long) activePlayer.getTotalTimeMillis();
            long position = (long) activePlayer.getCurrentTimeMillis();
            if (total <= 0L) return;
            if (position < AUDIO_PREFETCH_AFTER_MILLIS) return;

            int nextIndex = peekNextIndex();
            if (nextIndex < 0 || nextIndex >= playList.size()) return;
            final Music song = playList.get(nextIndex);
            if (!worthAudioPrefetch(song)) return;
            // 磁盘上已经有了就别再占那份余额 —— 实播会直接命中同一份缓存（缓存键只有曲目加音质）。
            if (cachedAudioFileFor(song) != null) return;

            audioPrefetchAttempts++;
            nextAudioPrefetchAt = now + AUDIO_PREFETCH_RETRY_MILLIS;
            prefetchingAudio = true;
            final long armGeneration = generation.get();
            final long armRevision = queueRevision;
            // 低优先级池：整首歌取字节 + 一次整首解码，是后台最重的一类活，用户并没在等它。
            BackgroundWork.submit(() -> prefetchTrackAudio(song, armGeneration, armRevision));
        }

        /**
         * 后台跑一次「解析 → 下载 → 解码」，把结果留在磁盘上就走。
         *
         * <p>失败只留一行日志：提前取没取到，正确的处理是「等真轮到它时按实播再走一遍完整链路」
         * （包括超 4 分钟放弃、跨源换轨那些），预取没有资格替这首歌判定能不能播。</p>
         */
        private void prefetchTrackAudio(Music song, long expectedGeneration, long expectedRevision) {
            QUIET_DOWNLOAD.set(Boolean.TRUE);
            try {
                if (!prefetchStillValid(expectedGeneration, expectedRevision)) return;
                Tuple<String, String> playUrl = song.getPlayUrl();
                if (playUrl == null) return;      // 解析不出直链：留给实播那条链去换源
                if (!prefetchStillValid(expectedGeneration, expectedRevision)) return;
                File file = getMusicFile(playUrl, song);
                if (file != null && file.isFile()) {
                    System.out.println("[Music/Prefetch] 已提前取满 " + song.getName()
                            + "：" + PlaybackMemoryLimits.megabytes(file.length()) + "MB");
                }
            } catch (Throwable failure) {
                System.out.println("[Music/Prefetch] 提前取流未命中 " + song.getName() + "：" + failure);
            } finally {
                QUIET_DOWNLOAD.remove();
                prefetchingAudio = false;
            }
        }

        /** 预取只有在播放世代与队列预测都没变时才有意义（用户可能已经把下一首选成了别的）。 */
        private boolean prefetchStillValid(long expectedGeneration, long expectedRevision) {
            return !isPlaybackCancelled() && !playListChanged() && !doBreak
                    && generation.get() == expectedGeneration
                    && CloudMusic.queueRevision == expectedRevision;
        }

        /**
         * 开始解析并解码下一首曲目。这里刻意在当前曲目播放很早时就启动，而不是等到结束前几秒：
         * 第三方音源可能需要先解析、下载、转码，然后才能解码，这通常比交叠本身耗时更久。
         * 预备未能及时就绪就无法混音，因此"尽早开始"正是这一功能的全部意义。
         * 失败的尝试会在冷却后重试几次，因为一个不稳定的 URL 不应在曲目剩余播放期间静默地禁用混音。
         */
        private void maybeArmNextTrack(AudioPlayer activePlayer) {
            long revision = CloudMusic.queueRevision;
            if (revision != seenQueueRevision) {
                // 用户改变了接下来要播放的内容。之前预备的东西解码的是错误的曲目，而且
                // 尝试预算属于旧的预测，因此两者都被重置。
                seenQueueRevision = revision;
                armAttempts = 0;
                nextArmAt = 0L;
                cancelArmedTrack();
            }
            if (armed != null || arming) return;
            if (armAttempts >= MAX_ARM_ATTEMPTS) return;
            if (!AutomixSettings.isEnabled() || awaitingPlaybackAction) return;
            if (activePlayer == null || !isSessionUsable(session)) return;

            long now = System.currentTimeMillis();
            if (now < nextArmAt) return;

            long total = (long) activePlayer.getTotalTimeMillis();
            long position = (long) activePlayer.getCurrentTimeMillis();
            if (total <= 0L) return;

            long lead = AutomixSettings.getOverlapMillis() + AutomixSettings.ARM_LEAD_MILLIS;
            // 曲目要么已稳定进入播放，要么已接近尾声，预备不能再等待。
            // 两者中先发生者为准。
            if (position < EARLY_ARM_MILLIS && total - position > lead) return;

            int nextIndex = peekNextIndex();
            if (nextIndex < 0 || nextIndex >= playList.size()) {
                armAttempts = MAX_ARM_ATTEMPTS;
                return;
            }
            Music nextSong = playList.get(nextIndex);
            if (nextSong == null) {
                armAttempts = MAX_ARM_ATTEMPTS;
                return;
            }

            // 房间的下一首：房主的计划还没到就先不预备。入口点与曲线都该由房主那份计划决定，
            // 先按本机自己的判据解一遍再作废，等于白跑一次最重的后台任务。这里不计入尝试次数，
            // 于是计划一到（服务端广播）下一 tick 自然就开始预备，不需要任何唤醒机制。
            if (RoomAutomix.awaitingPlan(nextSong)) return;

            armAttempts++;
            nextArmAt = now + ARM_RETRY_MILLIS;
            arming = true;
            final long armGeneration = generation.get();
            final Music song = nextSong;
            final int index = nextIndex;
            final long armRevision = revision;
            // 低优先级池：这条链是整首歌解码 + 若干次 ffmpeg 分析窗口，是本项目最重的后台活。
            // 用户并没有在等它（等不到就退回普通切歌），所以绝不该和渲染线程争 CPU。
            BackgroundWork.submit(() -> armTrack(song, index, armGeneration, armRevision));
        }

        /** 解析、下载、解码并持有下一首曲目。运行于播放线程之外。 */
        private void armTrack(Music song, int index, long armGeneration, long armRevision) {
            AudioPlayer deck = null;
            // 真人要的那一次交接开始了：房间 profile 预热看到这一位就让路，不再启动新的一首
            // （见 CloudMusic#automixAnalysisBusy）。只置一个位，不改下面任何判断。
            AUTOMIX_ANALYSIS_BUSY.set(true);
            try {
                if (!armStillValid(armGeneration, armRevision)) return;

                // 自定义源还在起的时候，先在这条允许阻塞的后台线程上把它等就绪，再去解析。
                // getPlayUrl() 里的那一跳只敢等 8 秒（链的另一头可能是游戏主线程），而脚本发
                // inited 的上限是 30 秒 —— 野花这类要先跑一次「检查更新」的源经常刚好差一点。
                // 预备期弃权不等于「下一首再说」：交接点直接把这张牌提上去、不会重新解析，
                // 于是这一首就这么按非 LX 的结果定死了。
                long readyWaitMs = lxArmReadyWaitMillis();
                if (readyWaitMs > 0L && !com.muoniumplayer.core.ncm.lxsource.LxSourceService
                        .awaitReady(readyWaitMs)) {
                    System.out.println("[Automix] arming " + song.getName() + ": custom source still"
                            + " not ready after " + (readyWaitMs / 1000L) + "s, resolving without it");
                }

                Tuple<String, String> playUrl = song.getPlayUrl();
                if (playUrl == null) {
                    // 预备路径也认「整条链路取不到流」这一路。只 return 的话，换源的搜索匹配就要
                    // 等队列真轮到这一首才由 playSong 现搜现验 —— 那一下就是可听的卡顿加硬切，
                    // automix 这一轮等于白预备。
                    armNoStreamRescue(song, index, armGeneration, armRevision);
                    return;
                }
                if (!armStillValid(armGeneration, armRevision)) return;

                File file = getMusicFile(playUrl, song);
                if (file == null || !file.isFile() || !armStillValid(armGeneration, armRevision)) return;

                // 第二个活跃播放牌会使常驻 PCM 翻倍，因此过大的曲目绝不会被预备。
                long limit = AutomixSettings.maxDeckBytes();
                if (file.length() > limit) {
                    System.out.println("[Automix] skipping arm for " + song.getName()
                            + ": decoded size " + PlaybackMemoryLimits.megabytes(file.length())
                            + "MB exceeds the " + PlaybackMemoryLimits.megabytes(limit) + "MB deck budget");
                    return;
                }

                deck = new AudioPlayer(file);
                deck.setTrackName(trackDisplayName(song, file));
                deck.setVolume(CloudMusic.getVolume());
                deck.setFadeGain(0.0f);
                // 第三方搜索结果不带时长；解码器现在知道真实时长了。
                long decodedMillis = (long) deck.getTotalTimeMillis();
                song.applyDecodedDuration(decodedMillis);
                if (!armStillValid(armGeneration, armRevision)) return;

                // 试听片段判定的第四条路径：开着 automix 时「下一曲」根本走不到 playSong ——
                // 它在上面这里被解析、下载、解码，然后在交接点直接出声，三级换源触发谁也看不见
                // 它。非会员在下一平台的试听流同样「合法」：能下、能解、无声异议。所以预备平台
                // 的解码器报出真实长度之后做同一判定；是试听就丢掉这张牌，先去跨平台换源把队列
                // 里这一格换成完整曲目，下一轮预备对着替身重来。
                if (CrossSourceSwitcher.isIncompleteStream(song.getDuration(), decodedMillis)) {
                    // 与 playSong 同一约定：自动换源关闭且是汽水时，试听照常预备——
                    // 到点出声的是 30 秒片段，界面会亮「仅试听」标识，比预备期直接弃牌
                    // （表现为下一曲没声）诚实。换源开着才丢牌走救援；文件同样照留，
                    // 交给缓存键的权益世代自然失配。
                    if (!CrossSourceSwitcher.canAutoSwitch(song) && song.isQishui()) {
                        song.setQishuiTrialResolve(true);
                        System.out.println("[Music/Qishui] 预备试听播放（自动换源未开启）："
                                + song.getName());
                    } else {
                        // 先放句柄再删文件：解码器正用 RandomAccessFile 读着这份缓存，Windows 上
                        // 打开中的文件删不掉。删缓存与 playSong 路径同一本账 —— 缓存键只有
                        // 「曲目 + 音质」不认直链，不删的话以后开了会员仍会预备命中这份试听坏缓存。
                        closeQuietly(deck);
                        deck = null;
                        try {
                            file.delete();
                        } catch (Throwable ignored) {
                        }
                        armTrialClipRescue(song, index, armGeneration, armRevision);
                        return;
                    }
                }

                // 进阶模式：进场这一首的整曲包络必须在这次规划<b>之前</b>就位。上一轮这里只是把
                // 它提交给后台池，而规划就在同一根线程上紧随其后 —— 于是第一次播的一对歌永远
                // 拿不到进场侧的证据，配对选点等于没开（出场侧还能靠上一次播留下的缓存侥幸命中，
                // 进场侧是彻底不可能）。这条链本来就在低优先级池上、已经付过一次下载加解码，
                // 多解一遍整曲是它自己该干的活；总开关关闭时这个方法第一行就返回。
                boolean incomingEnvelope = warmAdvancedEnvelopeNow(file, decodedMillis);

                ArmedTrack track = new ArmedTrack(song, file, deck, index, armGeneration, armRevision);
                // 这次规划到底有没有证据，记在预备上：回头复查（{@link #maybeReplanArmedTrack}）
                // 只看这一位。出场侧的包络是它自己起播时扫的，此刻可能还在队列里排队。
                track.advancedEvidence = !AutomixSettings.isAdvancedModeEnabled()
                        || (incomingEnvelope && AutomixEnvelope.isPresent(
                                ownedPlayer == null ? null : ownedPlayer.getSourceFile()));
                // 一起听房间：交接点由房主那台用它自己选的引擎（Folia 或内置信号处理）分析出来，
                // 服务端裁决后广播给全房 —— 房间里跑分析的只有房主一台，所以几台机器拿到的是
                // 同一份计划。计划还没到时本机不会自己编一条（那会让两台机器在同一刻叠出两条
                // 不同的波形，比硬切更难听）：这一首先不预备，房主的计划一到就会开始预备。
                long lastVocalEnd = estimateLastVocalEndMillis(ownedPlayer);
                boolean roomOwns = RoomAutomix.handles(song);
                track.plan = RoomAutomix.planWith(ownedPlayer, deck, song, lastVocalEnd);
                track.roomMode = roomOwns && track.plan != null;
                if (track.plan == null && !roomOwns) {
                    // 本机自己的交接：先看之前算过的同一对曲目。列表循环、以及「同一首歌反复
                    // 播放」这两种最常见的场景下，这一下直接省掉整轮分析（装了模型就是一次
                    // Python 的 stem 分离）。键里带着两首歌的音频与全部相关设置，所以命中的
                    // 计划与现在重算一遍得到的必然是同一条。
                    track.plan = AutomixPlanCache.load(ownedPlayer, deck, lastVocalEnd);
                    // 缓存那一份是按<b>上一次</b>预备时的播放头成立的，重放时它不再自证。预备晚几秒
                    // （前几次尝试失败、自定义源脚本起得慢）就会拿到一个已经过去的退场点，
                    // 「位置已过点火点」当场为假 → 混音在歌的中段立刻开始，听起来就是一次硬切。
                    // 计算路径上这条复查在规划器里（FoliaAutomixPlanner 的 5 秒余量），复用路径补在这里。
                    if (track.plan != null && ownedPlayer != null) {
                        long playhead = (long) ownedPlayer.getCurrentTimeMillis();
                        if (track.plan.fireMillis < playhead + REUSED_PLAN_FIRE_LEAD_MILLIS) {
                            System.out.println("[Automix] 复用计划已过期：fire " + track.plan.fireMillis
                                    + " < 播放头 " + playhead + " + " + REUSED_PLAN_FIRE_LEAD_MILLIS
                                    + "，改为重算");
                            track.plan = null;
                        }
                    }
                }
                if (track.plan == null && !roomOwns) {
                    track.plan = AutomixPlanner.plan(ownedPlayer, deck, lastVocalEnd);
                    // 只缓存算得出来的计划：null 里混着「这次音频没读到」这种暂时状态，
                    // 把它记成命中会让这首曲子之后永远不交叠。
                    //
                    // 进阶模式开着而这一次没证据时同样不落盘。这条计划其实只是启发式兜底，写进
                    // 缓存等于把「这次包络没扫到」钉成「这对歌就这样」：同一对歌第二次播放会直接
                    // 命中这份没证据的计划，配对选点永远轮不到，症状是「功能明明开了却从来没生效
                    // 过」。让它只在内存里活这一次。
                    if (track.advancedEvidence) {
                        AutomixPlanCache.store(ownedPlayer, deck, lastVocalEnd, track.plan);
                    }
                }
                if (track.plan == null || !armStillValid(armGeneration, armRevision)) return;

                // 启动所需的一切（除了发声之外）都在预备线程上完成：
                // 给读取器设入点、接好低搁架滤波、从磁盘拉取第一个流式窗口。
                track.preRollMillis = Math.min(PRE_ROLL_MILLIS, Math.max(0L, track.plan.incomingCueMillis));
                deck.setBassGainDb(track.plan.incomingBassDb(0.0));
                // 人声素材：量得出、也对得上就接上，之后这条 deck 的人声让路整个改由素材来做
                // （分派在 AudioPlayer.routeVocalDepth，接上时它会把带限抵消器的深度按在 0）。
                // 这一步必须在 setVocalDuckDepth 之前，也要在 prepareStart 之前 —— 预卷一旦开始
                // 读盘，素材就要已经在那儿等着，否则第一个音节是用抵消器减的，接缝中段才换成素材。
                track.vocalMaterial = attachVocalMaterial(deck, track.plan);
                // 在 prepareStart 之前调用，这样链路会直接采用这两个值而无需渐变过渡：预卷好的
                // 播放牌早在接缝之前就已（静默地）渲染数据块，而一个直到交接时才开始淡入的闪避
                // 会让第一个音节漏出来。
                deck.setVocalDuckDepth(track.plan.incomingVocalDepth(0.0));
                deck.prepareStart(track.plan.incomingCueMillis - track.preRollMillis);
                if (!armStillValid(armGeneration, armRevision)) return;

                synchronized (PLAYER_STATE_LOCK) {
                    if (!armStillValid(armGeneration, armRevision)) return;
                    armed = track;
                    deck = null; // 在取消路径可见之前完成所有权交接。
                }
                // 上一张牌的记账不该跟着新牌活下来（顶住交接的截止时间、拖进度条的收尾位）。
                seamHoldUntil = 0L;
                seekDuringFadeRequested = false;
                deck = null;   // 所有权移交给已预备的曲目
                System.out.println("[Automix] armed " + song.getName() + " · " + track.plan.summary);
                DownloadDynamicIsland.showAutomixArmed(song.getName());
            } catch (Throwable failure) {
                System.err.println("[Automix] unable to arm " + (song == null ? "next track" : song.getName())
                        + ": " + failure.getMessage());
            } finally {
                if (deck != null) closeQuietly(deck);
                arming = false;
                AUTOMIX_ANALYSIS_BUSY.set(false);
            }
        }

        /** 预备阶段每次交接给「新一首」都重新计数：换源救援的乒乓上限。 */
        private int armTrialSwitches;

        /**
         * 预备期为「脚本还没起完」最多等多久。取 {@code LxRuntime.INIT_TIMEOUT_MS} 再减一截：
         * 过了那个点脚本注定是失败了，继续等只是把这一轮的预备预算白白吃掉。
         */
        private static final long LX_ARM_READY_WAIT_CEILING_MS = 22_000L;

        /** 等完脚本还要留给「解析 + 下载 + 解码」的时间；剩余跑道不够就干脆不等。 */
        private static final long ARM_RESOLVE_BUDGET_MILLIS = 30_000L;

        /**
         * 这一轮预备愿意为脚本就绪等多久，返回 0 表示不等。
         *
         * <p>只有「启用了脚本、且它正在起」才有等的必要 —— 没启用是常态，起失败是既成事实，
         * 两种都不该挡路。再按当前曲目的剩余跑道裁剪：等完已经来不及解析下载解码的等待没有意义，
         * 那种情况本来就该退回普通切歌。</p>
         */
        private long lxArmReadyWaitMillis() {
            if (!com.muoniumplayer.core.ncm.lxsource.LxSourceService.isEnabled()) return 0L;
            if (!com.muoniumplayer.core.ncm.lxsource.LxSourceService.isLoading()) return 0L;
            AudioPlayer active = ownedPlayer;
            if (active == null) return 0L;
            long total = (long) active.getTotalTimeMillis();
            long position = (long) active.getCurrentTimeMillis();
            if (total <= 0L) return 0L;
            long runway = total - position
                    - (AutomixSettings.getOverlapMillis() + AutomixSettings.ARM_LEAD_MILLIS
                            + ARM_RESOLVE_BUDGET_MILLIS);
            if (runway <= 0L) return 0L;
            return Math.min(LX_ARM_READY_WAIT_CEILING_MS, runway);
        }

        /**
         * 「下一曲」是试听片段时在预备阶段救场：不预备半首歌，先跨平台换源。
         */
        private void armTrialClipRescue(Music song, int index, long armGeneration, long armRevision) {
            armCrossSourceRescue(song, index, armGeneration, armRevision,
                    "下一曲在该平台只有试听片段");
        }

        /**
         * 「下一曲」整条链路取不到流时在预备阶段救场 —— 和 {@code playSong} 的第一级触发同一件事，
         * 只是提前到预备期做。换源的搜索匹配若等到真播这一首才开工，用户听到的就是切歌前那几秒
         * 空白加一次硬切，automix 这一轮等于白预备。
         *
         * <p>脚本仍在 {@code LOADING} 时这一轮<b>不跑</b>：候选表里靠脚本解 VIP 的那几格此刻必然
         * 解不出来，整条流水线跑一遍等于把这一首唯一一次自动换源机会（{@code AUTO_ATTEMPTED}）
         * 烧在「脚本还没起完」上，等真能解的时候反而不再换 —— 那比不救更糟。留给下一轮预备
         * （{@code ARM_RETRY_MILLIS} 之后）与 {@code playSong} 的兜底触发。</p>
         */
        private void armNoStreamRescue(Music song, int index, long armGeneration, long armRevision) {
            if (com.muoniumplayer.core.ncm.lxsource.LxSourceService.isLoading()) {
                System.out.println("[Automix] arm skipped for " + song.getName()
                        + ": no stream yet and the custom source is still loading");
                return;
            }
            System.out.println("[Automix] arm switching for " + song.getName()
                    + ": no playable stream on " + song.getSource());
            // 没换成时这一路不出灵动岛提示：队列真轮到它时 playSong 会把原因说清楚。
            armCrossSourceRescue(song, index, armGeneration, armRevision, null);
        }

        /**
         * 预备期跨平台换源的公共身体：跑流水线、把队列里那一格换成替身、作废旧计划。
         *
         * <p>整体跑在 BackgroundWork 低优先级池上，允许网络。找到替身后直接换掉队列里那一格并
         * bump {@code queueRevision}（丢弃按旧格做的计划与重试预算归零），下一轮 arm 会对替身
         * 重新预备 —— 交接与出声走的是正常 automix 链路。找不到替身时保持沉默：队列真正轮到
         * 这首歌时 {@code playSong} 的三级触发还会兜底并给出提示。{@code armTrialSwitches}
         * 防候选表乒乓连环换（智能过度）。</p>
         *
         * @param failureHint 本轮不救场时给灵动岛的一句话原因；{@code null} 表示这条路上不说话，
         *                    留给 {@code playSong} 收尾
         */
        private void armCrossSourceRescue(Music song, int index, long armGeneration, long armRevision,
                                          String failureHint) {
            if (armTrialSwitches >= 2 || !CrossSourceSwitcher.canAutoSwitch(song)) {
                if (failureHint != null) {
                    DownloadDynamicIsland.showPlaybackFailure(song.getName(), failureHint);
                }
                return;
            }
            DownloadDynamicIsland.showCrossSourceSearching(song.getName());
            CrossSourceSwitcher.Match replacement = null;
            try {
                replacement = CrossSourceSwitcher.findReplacementForAuto(song, CloudMusic.quality);
            } catch (Throwable failure) {
                System.err.println("[Music/Switch] 预备期换源流水线异常 "
                        + song.getStableKey() + ": " + failure);
            }
            if (replacement == null) {
                // 预备期没换成不报错：这一首真到队尾时 playSong 的三级触发还会再兜一次，
                // 由那边给出用户看得懂的原因。这里只留一行日志，供事后查「为什么没提前换」。
                System.out.println("[Automix] arm switch found no replacement for "
                        + song.getStableKey());
                return;
            }
            if (!armStillValid(armGeneration, armRevision)) return;
            armTrialSwitches++;
            // 预备期换源同样只动队列格：面板行 widget 绑的还是 song 这个老对象，
            // 替身是脚本解出来的流时把徽标回写过去。
            CrossSourceSwitcher.propagateUnblockLabel(song, replacement.music);
            try {
                List<Music> queue = playList;
                if (queue != null && index >= 0 && index < queue.size() && queue.get(index) == song) {
                    // 这一格可能正是用户「下一首播放」排出来的那一格：替身是个陌生身份，不迁移的话
                    // 待播段数到这里就断了，界面看起来就是换一次源把待播列表清空了，单曲循环还会
                    // 因此假报「没有下一首」而重播当前这首。迁移的语义见 PlayNextLedger。
                    playNextLedger.replacePendingIdentity(song, replacement.music);
                    queue.set(index, replacement.music);
                    // automix：「接下来要放的」变了，丢弃按旧格做出的计划并重开预备预算。
                    CloudMusic.queueRevision++;
                }
            } catch (Throwable ignored) {
                // 队列正被遍历：换不上也不要丢换源结果，playSong 那边还会接管。
            }
            DownloadDynamicIsland.showCrossSourceSwitched(replacement.music.getName(),
                    replacement.platformLabel);
        }

        /**
         * 当前播放曲目最后一行歌词的结束位置；若其无时间轴歌词则为 {@code -1}。
         * 在最后一个词之后混音退出，正是为了避免两组人声发生碰撞。
         */
        private long estimateLastVocalEndMillis(AudioPlayer outgoing) {
            try {
                Music current = currentlyPlaying;
                if (current == null) return -1L;
                // 歌词列表在加载失败后仍会保留，因此它可能仍描述着上一首曲目。
                // 使用那些时间戳会导致按错误歌曲的结构来安排混音退出。
                String owner = CloudMusic.lyricsTrackKey;
                if (owner == null || !owner.equals(current.getStableKey())) return -1L;

                List<LyricLine> lines = CloudMusic.lyrics;
                if (lines == null || lines.isEmpty()) return -1L;
                for (int index = lines.size() - 1; index >= 0; index--) {
                    LyricLine line = lines.get(index);
                    if (line == null || line.lyric == null || line.lyric.trim().isEmpty()) continue;
                    // 普通 LRC 行不带时长；假定为一个短小的演唱乐句。
                    long span = line.duration > 0L ? line.duration : 2_500L;
                    long end = line.timestamp + span;
                    if (end <= 0L) return -1L;
                    // 超出音频末尾的歌词属于这首歌的另一个版本剪辑。
                    long total = outgoing == null ? 0L : (long) outgoing.getTotalTimeMillis();
                    if (total > 0L && end > total) return -1L;
                    return end;
                }
                return -1L;
            } catch (Throwable ignored) {
                // 歌词会被加载器并发替换；找不到它们并非错误。
                return -1L;
            }
        }

        /**
         * 在计划交接前不久，让已预备的播放牌以全静音启动，这样接缝本身只移动增益，
         * 在两个播放牌同时被渲染时，无需再分配、设入点、重新接线或从磁盘读取。
         * 这复刻了参考实现中预备/释放分离的做法，并适配到采样读取器引擎上。
         *
         * <p>静音播放牌与暂停状态保持同步：若放任其继续运行，在听者已停止播放时，
         * 它会越过计划对齐到小节线的入点。</p>
         */
        private void maybePreRollArmedDeck(AudioPlayer activePlayer) {
            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null) return;
            if (activePlayer == null || !activePlayer.isUsable()) return;

            AudioPlayer deck = track.deck;
            boolean playbackPaused = activePlayer.isPausing() || !playing.get();
            if (track.preRolled) {
                if (playbackPaused && !track.preRollPaused) {
                    if (!deck.isPausing()) deck.pause();
                    track.preRollPaused = true;
                } else if (!playbackPaused && track.preRollPaused) {
                    deck.startPrepared();
                    track.preRollPaused = false;
                }
                // 向后拖动会把交接点再次推远，而该播放牌仍在继续运行：
                // 等到它最终触发时，它会在错误的位置进入歌曲，或者已经
                // 静默地播完了。应把它放回入点，并在稍后重新预卷。
                long current = (long) activePlayer.getCurrentTimeMillis();
                if (deck.isFinished() || current + PRE_ROLL_REWIND_SLACK_MILLIS
                        < track.plan.fireMillis - track.preRollMillis) {
                    rearmPreRoll(track, deck);
                }
                return;
            }
            if (playbackPaused || !deck.isPrepared()) return;
            // 没有可预卷进去的内容：进入的曲目在（或几乎在）其自身起点进入，因此
            // 提前运行它会吞掉歌曲最初的片刻。它仍会预热启动。
            if (track.preRollMillis <= 0L) return;
            if (!armStillValid(track.generation, track.queueRevision)) return;

            long position = (long) activePlayer.getCurrentTimeMillis();
            if (position < track.plan.fireMillis - track.preRollMillis) return;

            try {
                deck.setVolume(CloudMusic.getVolume());
                deck.setFadeGain(0.0f);
                deck.setBassGainDb(track.plan.incomingBassDb(0.0));
                deck.startPrepared();
                track.preRolled = true;
            } catch (Throwable failure) {
                System.err.println("[Automix] pre-roll failed for " + track.song.getName() + ": "
                        + failure.getMessage());
            }
        }

        /** 把已预卷的播放牌在其入点处恢复为静音，以便稍后重新预卷。 */
        private void rearmPreRoll(ArmedTrack track, AudioPlayer deck) {
            try {
                if (!deck.isPausing()) deck.pause();
                deck.setFadeGain(0.0f);
                deck.prepareStart(track.plan.incomingCueMillis - track.preRollMillis);
            } catch (Throwable failure) {
                System.err.println("[Automix] unable to re-cue the pre-rolled deck for "
                        + track.song.getName() + ": " + failure.getMessage());
            } finally {
                track.preRolled = false;
                track.preRollPaused = false;
            }
        }

        /**
         * 复查一条接缝需要的最小跑道：换了退场点要按新入点重新预卷，贴着接缝改计划只会把这一次
         * 交接做成错位。取预备窗的同一量级，宁可少改一次，也不要改在半道上。
         */
        private static final long REPLAN_MIN_LEAD_MILLIS = 12_000L;

        /**
         * 旧接缝<b>已经作废</b>时只要这么点跑道。
         *
         * <p>常规复查的对照物是「什么都不做」，那很安全；播放头越过点火点之后的对照物是
         * 「当场在一个任意位置叠起来」，那不安全。所以这一类允许用更短的跑道，代价是新入点只能
         * 少预卷一点。与规划器自己那条 {@code MIN_ADVANCED_FIRE_LEAD_MILLIS} 同量级。</p>
         */
        private static final long STALE_REPLAN_MIN_LEAD_MILLIS = 5_000L;

        /**
         * 顶住交接、等新接缝算出来的最长时间。到点还没结果就照今天的行为交接 ——
         * 「晚一点接」永远胜过「静默空隙」，但也不许为了等一条更好的接缝把出场曲拖到播完。
         */
        private static final long SEAM_HOLD_MILLIS = 1_500L;

        /** 一条预备最多重算几次：拖十次进度条不该烧十趟整曲分析。 */
        private static final int MAX_REPLAN_PER_ARM = 3;

        /**
         * 复用缓存计划时要求的余量：点火点至少要落在当前播放头之后这么多毫秒，否则那份计划
         * 描述的是一个<b>已经过去</b>的位置，照它执行就等于在歌的中段硬切。与规划器里那条
         * {@code MIN_ADVANCED_FIRE_LEAD_MILLIS} 同量级，那条只管算出来的计划，这条管捡回来的计划。
         */
        private static final long REUSED_PLAN_FIRE_LEAD_MILLIS = 5_000L;

        /**
         * 证据到货后把接缝<b>回头重算一次</b>（一条预备只许一遍）。
         *
         * <h2>为什么需要这一条</h2>
         *
         * <p>预备发生得出奇地早：{@code position >= EARLY_ARM_MILLIS}（10 秒）就开始了，而不是等到
         * 曲尾。而出场那一首的整曲包络是它自己起播时才提交去扫的 —— 于是第一次播的一对歌，
         * 规划经常踩着「没有包络」那条兜底路径定下来，之后 <b>没人再算第二遍</b>：包络二十秒后
         * 落盘了，接缝却还是那条没证据的。名义上是整曲配对，实际一次都没跑过。</p>
         *
         * <p>这里在接缝还没动之前补一次。刻意保守，四个条件同时成立才走：进阶模式开着；这一条
         * 不是房间的（房主那份计划全房共用，本机另算会让两台机器在同一刻叠出两条不同的波形）；
         * 两份包络<b>此刻</b>都在；离点火点还有足够跑道。装的时候还要再按新计划的点火点复验一次
         * —— 因为「跑道够」是相对于<b>旧</b>计划判的，而新计划的退场点可能更早。</p>
         *
         * <p>本方法只做两件事：把后台算好的结果装上（同一根线程，与预卷改入点同一套动作），
         * 或者把重算提交给低优先级池。算本身绝不在这根线程上跑。</p>
         *
         * <p>播放头越过点火点之后的那一次重算是另一条路（{@link #holdSeamForFreshCue}）：那条的
         * 判据是「旧计划已经作废」，跟证据有没有到货无关，也不要求这一条还没走过。</p>
         */
        private void maybeReplanArmedTrack(AudioPlayer activePlayer) {
            if (!AutomixSettings.isAdvancedModeEnabled()) return;
            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null) return;
            // 装结果排在所有「要不要再算」的判据之前：预算位（replanned / replanCount）在提交时
            // 就已经花掉了，把它放在下面的早退里会让后台算好的那份永远没人装。
            if (track.replannedPlan != null) {
                installReplannedPlan(track, activePlayer);
                return;
            }
            if (track.replanned || track.roomMode) return;
            if (activePlayer == null || !activePlayer.isUsable()) return;
            if (track.advancedEvidence || track.replanning || track.preRolled) return;
            long position = (long) activePlayer.getCurrentTimeMillis();
            if (position + REPLAN_MIN_LEAD_MILLIS > track.plan.fireMillis - track.preRollMillis) return;
            // 没到货就别白跑一趟：重算的动机就是证据，证据没来只会算出同一条兜底结论。
            if (!AutomixEnvelope.isPresent(track.file)) return;
            if (!AutomixEnvelope.isPresent(activePlayer.getSourceFile())) return;

            requestReplan(track, activePlayer);
        }

        /**
         * 提交一次后台重算。谁该重算是调用方的事（证据到货、还是接缝作废），这里只管预算、
         * 互斥与「别在播放线程上算」。
         */
        private void requestReplan(ArmedTrack track, AudioPlayer activePlayer) {
            if (track.replanning || track.replanCount >= MAX_REPLAN_PER_ARM) return;
            if (!armStillValid(track.generation, track.queueRevision)) return;
            track.replanning = true;
            track.replanCount++;
            final long generation = track.generation;
            final long revision = track.queueRevision;
            try {
                BackgroundWork.submit(() -> replanArmedTrack(track, generation, revision));
            } catch (Throwable rejected) {
                // 池子拒了（关服、队列满）就当这次没复查，绝不留一个永远为真的进行中位。
                track.replanning = false;
                track.replanned = true;
            }
        }

        /** {@link #maybeReplanArmedTrack} 的算的那一半，跑在低优先级池上，一个采样都不改。 */
        private void replanArmedTrack(ArmedTrack track, long armGeneration, long armRevision) {
            try {
                if (!armStillValid(armGeneration, armRevision) || armed != track) return;
                AudioPlayer outgoing = ownedPlayer;
                if (outgoing == null) return;
                long lastVocalEnd = estimateLastVocalEndMillis(outgoing);
                AutomixPlan plan = AutomixPlanner.plan(outgoing, track.deck, lastVocalEnd);
                if (plan == null) return;
                // 落盘放在这条后台线程上：install 跑在 10 ms 监督 tick 里，那里一次都不许写文件。
                // 存了之后万一装不上（跑道不够、已经预卷），这份计划也只是多缓存了一对
                // 有证据的位置 —— 它按同一对歌与同样的设置算出来，下次命中是对的。
                AutomixPlanCache.store(outgoing, track.deck, lastVocalEnd, plan);
                track.replannedPlan = plan;
            } catch (Throwable failure) {
                System.err.println("[Automix] unable to re-plan " + track.song.getName() + ": "
                        + failure.getMessage());
            } finally {
                track.replanning = false;
                track.replanned = true;
            }
        }

        /**
         * 见 {@link CloudMusic#onSeekApplied()}：别的线程只留个记号，收尾排在这根线程的 tick 上。
         */
        void recordSeek() {
            if (activeFade != null) seekDuringFadeRequested = true;
        }

        /**
         * 播放头已经赶上或越过点火点时，<b>顶住这次交接</b>等新接缝算出来（最多
         * {@link #SEAM_HOLD_MILLIS} 毫秒）。返回 true 表示本 tick 不许预卷、不许点火。
         *
         * <h2>这是「拖进度条」真正缺的那一半</h2>
         *
         * <p>接缝定的是<b>出场曲里的绝对位置</b>。用户把出场曲往后拖过那个位置，计划就作废了：
         * {@code tryAutomixHandover} 看见 {@code position >= fireMillis} 立刻点火，两首歌就在用户
         * 随手拖到的那一句中间叠起来 —— 对拍、低频互补、人声让路在那个位置全都谈不上。上一轮给
         * 规划器加了 {@code minimumFireMillis}（不许选已经播过去的位置），可那是<b>算的时候</b>的
         * 约束；拖完进度条没人再算一遍，那条约束也就没人再遵守。这里补的就是「再算一遍」。</p>
         *
         * <h2>顶住不等于静音</h2>
         *
         * <p>顶住的这段时间里出场曲照常出声（它本来就在播），只是这一次不点火，所以最坏的情况
         * 「白等一趟、接缝也没了」并不存在：到点照今天的行为交接。这也意味着顶住的时间不能长 ——
         * 它上限 1.5 秒，且要求剩余长度还放得下一次交叠加上这 1.5 秒。</p>
         *
         * <h2>六道闸</h2>
         *
         * <p>进阶模式开着（默认关时普通路径的行为一字不改）、不是房间（点火点归服务端）、两份整曲
         * 包络都在（否则重算只会得出同一条启发式结论，白等）、还没预卷（预卷了改动就太晚了）、
         * 出场曲没播完也没暂停、重算预算没花完。</p>
         */
        private boolean holdSeamForFreshCue(AudioPlayer activePlayer) {
            if (!AutomixSettings.isAdvancedModeEnabled()) return false;
            if (seamHoldUntil == 0L) return startSeamHold(activePlayer);

            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null) {
                seamHoldUntil = 0L;
                return false;
            }
            if (track.replannedPlan != null) {
                // 结果到了但还没装：安装在前面那一步（maybeReplanArmedTrack）里做，它装完会把
                // 这个位清掉。这里继续顶一个 tick，避免出现「一边装一边点火」。
                return true;
            }
            if (activePlayer == null || activePlayer.isFinished()) {
                seamHoldUntil = 0L;
                return false;
            }
            if (!track.replanning || System.currentTimeMillis() >= seamHoldUntil) {
                // 那一趟结束了却没给出结果（算失败、或池子拒了），或者等过头了：别再拖。
                seamHoldUntil = 0L;
                return false;
            }
            return true;
        }

        /** {@link #holdSeamForFreshCue} 的第一次判定：条件全成立才起顶，起顶必须先真的把活儿交出去。 */
        private boolean startSeamHold(AudioPlayer activePlayer) {
            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null || track.roomMode) return false;
            if (track.preRolled || track.replanning || track.replannedPlan != null) return false;
            if (activePlayer == null || !activePlayer.isUsable() || activePlayer.isFinished()
                    || activePlayer.isPausing()) return false;
            if (!AutomixEnvelope.isPresent(track.file)
                    || !AutomixEnvelope.isPresent(activePlayer.getSourceFile())) return false;

            long position = (long) activePlayer.getCurrentTimeMillis();
            if (position + track.preRollMillis < track.plan.fireMillis) return false;   // 计划还赶得上
            // 剩下的长度还得放得下一次交叠 + 这段等待，否则更好的接缝根本不存在。
            long remaining = (long) activePlayer.getTotalTimeMillis() - position;
            if (remaining <= track.plan.overlapMillis + SEAM_HOLD_MILLIS + 2_000L) return false;

            requestReplan(track, activePlayer);
            if (!track.replanning && track.replannedPlan == null) return false;   // 没提交成功就别顶
            seamHoldUntil = System.currentTimeMillis() + SEAM_HOLD_MILLIS;
            System.out.println("[Automix] 播放头越过点火点，顶住交接等新接缝 · " + track.song.getName()
                    + " · fire " + track.plan.fireMillis + "ms position " + position + "ms");
            return true;
        }

        /**
         * 给进场播放牌接上离线分好的人声素材；接上了才把那份素材还回去。
         *
         * <p>整条路是尽力而为，任何一种「对不上」都直接不要素材：没跑过分离模型、缓存被清、
         * 采样率跟播放素材不一致、接缝落在素材覆盖之外。退回来就是今天已经验证过的那条路
         * （带限中央声道抵消器），它永远可用，只是没那么准。</p>
         *
         * <p>这里可以同步读盘（一份素材约 5 MB），因为调用点在预备线程上，那条线程本来就要为
         * {@code prepareStart} 从磁盘拉第一个流式窗口。监督 tick 与音频线程都不许走到这儿。</p>
         */
        private FoliaVocalStem attachVocalMaterial(AudioPlayer deck, AutomixPlan plan) {
            try {
                // 闸在消费侧，不在生产侧：素材是离线分析顺手写的（写了就有 5 MB），而 profile
                // 一旦落盘就不会重算 —— 把「写」也挂在进阶模式后面，就等于「先关着进阶模式听过
                // 的歌，以后打开进阶模式永远没有素材」。关掉时这条 deck 保持今天的信号图，逐位一致。
                if (!AutomixSettings.isAdvancedModeEnabled()) return null;
                int rate = deck.sampleRate();
                FoliaVocalStem stem = FoliaVocalStem.load(deck.getSourceFile(), rate);
                if (stem == null) return null;
                if (!stem.covers(plan.incomingCueMillis, plan.overlapMillis, rate)) {
                    System.out.println("[Automix/VocalMaterial] 接缝在素材窗口之外，退回抵消器 · 入点 "
                            + plan.incomingCueMillis + "ms 交叠 " + plan.overlapMillis
                            + "ms · 素材 " + stem.firstMillis(rate) + "-" + stem.endMillis(rate) + "ms");
                    return null;
                }
                if (!deck.attachVocalMaterial(stem)) return null;
                System.out.println("[Automix/VocalMaterial] 人声走素材淡入 · " + rate + "Hz · 投影 "
                        + stem.ratio + " · 自第 " + stem.firstFrame + " 帧起 " + stem.samples.length + " 帧");
                return stem;
            } catch (Throwable failure) {
                System.err.println("[Automix/VocalMaterial] 接料失败: " + failure.getMessage());
                return null;
            }
        }

        /**
         * 把后台算好的新计划装上：换入点、换曲线，然后照旧交给预卷与交接那两步。
         *
         * <p>装不上就整个丢掉、沿用旧接缝 —— 旧接缝再差也是今天已经验证过的行为，一个装到一半
         * 的新接缝不是。<b>例外</b>是旧接缝已经作废（播放头越过点火点，见
         * {@link #holdSeamForFreshCue}）：那时候「沿用」等于在一个任意位置当场叠起来，
         * 所以这一类只要求 {@link #STALE_REPLAN_MIN_LEAD_MILLIS} 那么点跑道。</p>
         */
        private void installReplannedPlan(ArmedTrack track, AudioPlayer activePlayer) {
            AutomixPlan fresh = track.replannedPlan;
            track.replannedPlan = null;
            AutomixPlan old = track.plan;
            // 顶住交接是为了等这一份：不管装不装得上，等都有了结论，先把位清掉。
            seamHoldUntil = 0L;
            if (fresh == null || old == null || armed != track) return;
            // 结论一样就别动：换入点要重新预卷，代价远大于「原地不动」，而原地不动正是结果。
            if (fresh.fireMillis == old.fireMillis && fresh.incomingCueMillis == old.incomingCueMillis
                    && fresh.overlapMillis == old.overlapMillis) {
                track.advancedEvidence = true;
                return;
            }
            if (track.preRolled) return;
            long position = (long) activePlayer.getCurrentTimeMillis();
            long preRoll = Math.min(PRE_ROLL_MILLIS, Math.max(0L, fresh.incomingCueMillis));
            // 「跑道够」上次是按旧计划判的，新计划的退场点可能更早，这里按它自己再复验一次。
            // 判据也按旧计划是否已经作废分两档：不作废时不动是安全选项，作废时不动才是危险选项。
            long requiredLead = old.fireMillis - track.preRollMillis <= position
                    ? STALE_REPLAN_MIN_LEAD_MILLIS : REPLAN_MIN_LEAD_MILLIS;
            if (position + requiredLead > fresh.fireMillis - preRoll) return;

            AudioPlayer deck = track.deck;
            try {
                // 入点换了，素材可能就不盖住新接缝了。盖不住要整个退掉再往下设深度：留着那份
                // 读不到的素材，抵消器还按在 0，交接期就变成两个人一起唱。
                if (track.vocalMaterial != null && !track.vocalMaterial
                        .covers(fresh.incomingCueMillis, fresh.overlapMillis, deck.sampleRate())) {
                    System.out.println("[Automix/VocalMaterial] 重算后的接缝出了素材窗口，退回抵消器 · "
                            + track.song.getName());
                    deck.releaseVocalMaterial();
                    track.vocalMaterial = null;
                }
                track.preRollMillis = preRoll;
                deck.setBassGainDb(fresh.incomingBassDb(0.0));
                deck.setVocalDuckDepth(fresh.incomingVocalDepth(0.0));
                deck.prepareStart(fresh.incomingCueMillis - preRoll);
            } catch (Throwable failure) {
                System.err.println("[Automix] re-cue for the re-planned handover failed for "
                        + track.song.getName() + ": " + failure.getMessage());
                return;
            }
            track.plan = fresh;
            track.advancedEvidence = true;
            // 这一条在 10 ms 监督 tick 上跑：只改内存与播放牌，落盘已经在后台做完了。
            System.out.println("[Automix] 整曲包络到货，接缝重算 -> " + track.song.getName()
                    + " · 旧 fire " + old.fireMillis + "ms cue " + old.incomingCueMillis
                    + "ms → " + fresh.summary);
            DownloadDynamicIsland.showAutomixArmed(track.song.getName());
        }
        /** 独立于自动预备的用户意图。所有权发布/撤销与播放器提交共用 PLAYER_STATE_LOCK。 */
        private static final class ManualSelection {
            final List<Music> songs;
            final int index;
            final Music selectedSong;
            final long requestNanos = System.nanoTime();
            final boolean replaceQueue;
            final PlaybackSession session;
            volatile Thread worker;
            volatile ArmedTrack ready;
            volatile Throwable failure;

            ManualSelection(List<Music> songs, int index, boolean replaceQueue, PlaybackSession session) {
                this.songs = songs;
                this.index = index;
                this.selectedSong = songs.get(index);
                this.replaceQueue = replaceQueue;
                this.session = session;
            }
        }

        private boolean requestManualStep(int direction) {
            synchronized (PLAYER_STATE_LOCK) {
                ManualSelection pending = manualSelection;
                List<Music> queue = pending == null ? songs : pending.songs;
                int index = (pending == null ? curIdx : pending.index) + direction;
                if (queue.isEmpty()) return false;
                if (index < 0 || index >= queue.size()) {
                    if (playMode == PlayMode.Sequential) return pending != null;
                    index = Math.floorMod(index, queue.size());
                }
                return requestManualSelection(queue, index, pending != null && pending.replaceQueue);
            }
        }

        private boolean requestManualSelection(List<Music> queue, int index, boolean replaceQueue) {
            synchronized (PLAYER_STATE_LOCK) {
                // 私人 FM 不再排除：这条路径本来就是「原曲不停、新歌取流解码就绪才短渐变交接」，
                // 交接尾部走 performAutomixHandover（noteFmTrackStarted 在那里照常同步面板）。
                // 旧歌播完而新歌还没解码好时，监督 tick 会一直等到 ready 才动，不会偷播也不会断流。
                // （一起听两个房间仍排除：传输控制权归服务端，本机换歌要走房间帧。）
                if (!AutomixSettings.isEnabled() || awaitingPlaybackAction
                        || MpPartyClient.inRoom() || NeteaseListenTogetherService.isActive()
                        || KuGouListenTogetherService.isActive()
                        || doBreak || !isSessionUsable(session)) return false;
                AudioPlayer outgoing = ownedPlayer;
                if (outgoing == null || outgoing != player || !outgoing.isUsable()
                        || (manualSelection == null && (outgoing.isFinished() || outgoing.isPausing()))) return false;
                if (index < 0 || index >= queue.size() || queue.get(index) == null) return false;

                cancelManualSelection();
                ManualSelection selection = new ManualSelection(queue, index, replaceQueue, session);
                // 点击的正好是已解码的下一首时直接复用备用轨，不能因「视觉上先选中」而退化成重新下载。
                ArmedTrack ready = armed;
                if (ready != null && ready.index == index && !ready.preRolled
                        && ready.deck != null && ready.deck.isUsable()
                        && armStillValid(ready.generation, ready.queueRevision)) {
                    armed = null;
                    selection.ready = ready;
                } else {
                    queueRevision++;
                }
                manualSelection = selection;
                manualSkipRequested = false;
                // 使已经在分析/下载的自动预备永远失效，即使手动加载很快失败也不能重新发布旧牌。
                queueRevision++;
                // 快速连点时以前每击新建一条裸线程（取流+解码各来一份）。
                // BackgroundWork 是低优先级、有并发上限的池，正是为这类活准备的；interrupt 语义
                // 保持不变——取消时对执行线程发中断，跑在池线程上同样生效。
                final ManualSelection finalSelection = selection;
                BackgroundWork.submit(() -> {
                    // 取消旧预备时 cancelManualSelection 会中断池线程。裸线程时代中断随线程
                    // 死亡；池线程会把中断位带给下一个任务（ffmpeg waitFor、Thread.sleep
                    // 会当场炸）。进任务先清掉本任务不该背的旧中断。
                    Thread.interrupted();
                    finalSelection.worker = Thread.currentThread();
                    prepareManualSelection(finalSelection);
                });
                // 封面与音频并行准备，显示层立刻认新曲，但歌词与音频会话仍属于旧曲。
                try { loadMusicCover(selection.selectedSong); } catch (Throwable ignored) { }
                DownloadDynamicIsland.showManualTrackLoading(selection.selectedSong.getName());
                return true;
            }
        }

        private boolean manualSelectionCurrent(ManualSelection selection) {
            return manualSelection == selection && !Thread.currentThread().isInterrupted()
                    && isSessionUsable(selection.session);
        }

        /** 不等模型、stem、节拍或队尾：只做新歌出声所需的取流、解码和首窗口预热。 */
        private void prepareManualSelection(ManualSelection selection) {
            AudioPlayer deck = null;
            Music song = selection.selectedSong;
            try {
                if (!manualSelectionCurrent(selection)) return;
                Tuple<String, String> url = song.getPlayUrl();
                if (!manualSelectionCurrent(selection)) return;
                for (int attempt = 0; attempt < 2; attempt++) {
                    Throwable streamFailure = null;
                    if (url != null) {
                        try {
                            File file = getMusicFile(url, song);
                            if (!manualSelectionCurrent(selection)) return;
                            deck = new AudioPlayer(file);
                            deck.setTrackName(trackDisplayName(song, file));
                            deck.setVolume(CloudMusic.getVolume());
                            deck.setFadeGain(0.0f);
                            long duration = (long) deck.getTotalTimeMillis();
                            song.applyDecodedDuration(duration);
                            if (!manualSelectionCurrent(selection)) return;
                            if (CrossSourceSwitcher.isIncompleteStream(song.getDuration(), duration)) {
                                // 手动切歌预备与 armTrack 同一约定：换源关且汽水时试听照常预备。
                                if (!CrossSourceSwitcher.canAutoSwitch(song) && song.isQishui()) {
                                    song.setQishuiTrialResolve(true);
                                    System.out.println("[Music/Qishui] 手动切歌预备试听（自动换源未开启）："
                                            + song.getName());
                                } else {
                                    closeQuietly(deck);
                                    deck = null;
                                    file.delete();
                                    throw new IllegalStateException(trialFailureReason(song));
                                }
                            }
                            deck.enableManualTransitionProcessing();
                            deck.prepareStart(0f);
                            if (!deck.isUsable()) throw new IllegalStateException("音频解码失败");
                            ArmedTrack track = new ArmedTrack(song, file, deck, selection.index,
                                    generation.get(), queueRevision);
                            track.plan = manualSelectionPlan();
                            synchronized (PLAYER_STATE_LOCK) {
                                if (!manualSelectionCurrent(selection)) return;
                                selection.ready = track;
                                // 预备成功了就摘掉 worker 引用：之后播放链路 finally 里的
                                // cancelManualSelection 不会再对这条池线程发中断——
                                // 中断打在同池正在跑的别的任务（armTrack 等）身上会
                                // 让那次预备被误弃一次（有重试自愈，但能免则免）。
                                selection.worker = null;
                                deck = null;
                            }
                            return;
                        } catch (Throwable failure) {
                            closeQuietly(deck);
                            deck = null;
                            streamFailure = failure;
                        }
                    }
                    if (!manualSelectionCurrent(selection)) return;
                    // 保留普通起播的跨平台救援，但绝不为失败而关掉旧歌，也不循环尝试整张队列。
                    CrossSourceSwitcher.Match replacement = attempt == 0 && CrossSourceSwitcher.canAutoSwitch(song)
                            ? CrossSourceSwitcher.findReplacementForAuto(song, CloudMusic.quality) : null;
                    if (replacement == null) {
                        if (streamFailure != null) throw streamFailure;
                        throw new IllegalStateException("没有可用音频源");
                    }
                    song = replacement.music;
                    url = replacement.url;
                    if (!manualSelectionCurrent(selection)) return;
                }
            } catch (Throwable failure) {
                synchronized (PLAYER_STATE_LOCK) {
                    if (manualSelectionCurrent(selection)) selection.failure = failure;
                }
            } finally {
                closeQuietly(deck);
            }
        }

        /** 固定短频谱交接：从歌曲开头起播，轻量 EQ，不等待分析、变速或移调。 */
        private static AutomixPlan manualSelectionPlan() {
            return AutomixPlan.manualQuickMix(MANUAL_SKIP_FADE_MILLIS);
        }

        private boolean tryManualSelectionHandover(AudioPlayer outgoing) {
            synchronized (PLAYER_STATE_LOCK) {
                ManualSelection selection = manualSelection;
                if (selection == null) return false;
                cancelArmedTrack();
                if (selection.failure != null) {
                    handlePlayerInitializationError(selection.selectedSong, selection.failure);
                    cancelManualSelection();
                    return false;
                }
                ArmedTrack track = selection.ready;
                if (track == null) return false;
                // 加载期间用户按了暂停，准备好的新歌也等用户恢复，不能自行开始出声。
                if (!outgoing.isFinished() && outgoing.isPausing()) return false;
                if (!selection.replaceQueue) {
                    int index = songs.indexOf(selection.selectedSong);
                    if (index < 0) {
                        cancelManualSelection();
                        return false;
                    }
                    if (index != track.index) {
                        ArmedTrack moved = new ArmedTrack(track.song, track.file, track.deck, index,
                                track.generation, track.queueRevision);
                        moved.plan = track.plan;
                        track = moved;
                    }
                }
                if (performAutomixHandover(outgoing, track,
                        AutomixSettings.isEnabled() ? MANUAL_SKIP_FADE_MILLIS : 1L, selection)) return true;
                if (manualSelection == selection) {
                    DownloadDynamicIsland.showPlaybackFailure(selection.selectedSong.getName(), "新歌启动失败，保留原曲");
                    cancelManualSelection();
                }
                return false;
            }
        }

        private void cancelManualSelection() {
            synchronized (PLAYER_STATE_LOCK) {
                ManualSelection selection = manualSelection;
                manualSelection = null;
                if (selection == null) return;
                if (selection.worker != null) selection.worker.interrupt();
                ArmedTrack ready = selection.ready;
                selection.ready = null;
                if (ready != null) closeQuietly(ready.deck);
            }
        }

        /**
         * 用户按下的"下一首"能否由已预备的播放牌来服务，而不是走常规的
         * 关闭/解析/下载/解码切换流程。本方法从界面线程调用，因此它只检查状态并置位标志——
         * 这里不触碰 {@code player}、{@code session} 或队列。
         *
         * @return 该请求是否被接受；{@code false} 表示调用方必须按常规方式切换
         */
        boolean requestManualSkip() {
            if (doBreak || isPlaybackCancelled() || !isSessionUsable(session)) return false;
            // 已经请求过了；待处理请求将在下一个 tick 被服务。
            if (manualSkipRequested || manualSelection != null) return false;
            // 混音仍在进行。现在提升第三个播放牌会让两首曲目与新的一首同时可闻，
            // 因此这次切换必须走常规的硬切。
            if (activeFade != null) return false;

            AudioPlayer active = ownedPlayer;
            if (active == null || !active.isUsable() || active.isFinished() || active.isPausing()) return false;

            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null || !track.deck.isUsable()) return false;
            if (!armStillValid(track.generation, track.queueRevision)) return false;
            // 已预卷的 deck 从预卷那一刻起就在静音地往前放，而预卷时刻是按
            // plan.fireMillis 算的（见 maybePreRollArmedDeck）。手动切歌忽略 fireMillis、
            // 立刻就要接缝，于是进场曲目会从 cue 点之后若干秒才出声 —— 预卷得越早，
            // 被吃掉的开头越多。这种 deck 只能走普通切歌重新定位，不能拿来做无缝交接。
            //
            // 注意这里只拦手动切歌：自动路径恰好等到 fireMillis，preRolled 正是它想要的状态。
            if (track.preRolled) return false;
            // 已解码的播放牌必须恰好是队列当前预测的那首曲目，否则手动
            // “next” 落到的歌曲会与普通切换本来要播放的那首不同。
            if (track.index != peekNextIndex()) return false;

            manualSkipRequested = true;
            return true;
        }

        /**
         * 一起听房间的换歌帧要求立即交叠交接：置位，由本线程的 10 ms 监督 tick 消费。
         *
         * <p>只有「房间模式下预备出来的那一首」才认这个请求 —— 本机自己的歌单不该被房间帧
         * 提前拿走。不满足时返回 false，{@code MpPlayback} 随后照常走普通起播（硬切），
         * 也就是房间没开交叠、或下一首还没解码完时的行为。</p>
         */
        private boolean requestRoomHandover() {
            ArmedTrack track = armed;
            if (track == null || track.plan == null || !track.roomMode) return false;
            if (!armStillValid(track.generation, track.queueRevision)) return false;
            roomHandoverRequested = true;
            return true;
        }
        /**
         * 当前交叠还剩多少毫秒；没有在交叠、或渐变已经走完时为 0。
         *
         * <p>用 {@link #progressAt} 而不是「墙钟减 {@code startedAt}」：暂停时渐变时钟被冻住，
         * 剩余长度必须跟着冻住，否则调用方（一起听的纠偏那层）会按一个跑得更快的时钟去延长
         * 它的保护窗。</p>
         */
        long blendRemainingMillis() {
            AutomixFade fade = activeFade;
            if (fade == null) return 0L;
            double progress = progressAt(fade, System.currentTimeMillis());
            if (progress >= 1.0) return 0L;
            if (progress <= 0.0) return fade.overlapMillis;
            return (long) Math.ceil((1.0 - progress) * fade.overlapMillis);
        }

        /**
         * 处理待处理的手动跳过。与自动路径不同，这里会忽略 {@code plan.fireMillis}：
         * 用户要求立即离开当前曲目，因此接缝在本 tick 发生，使用较短的
         * 渐变，而不是在计划的小节线处。
         */
        private boolean tryManualNextHandover(AudioPlayer activePlayer) {
            if (!manualSkipRequested) return false;

            ArmedTrack track = armed;
            boolean usable = activePlayer != null && activePlayer.isUsable() && !activePlayer.isFinished()
                    && !activePlayer.isPausing()
                    && track != null && track.plan != null && track.deck != null
                    && armStillValid(track.generation, track.queueRevision)
                    && track.index >= 0 && track.index < playList.size();
            manualSkipRequested = false;
            if (!usable) {
                // 自从请求发出后这几毫秒内，播放牌已经消失了。用户仍然按了
                // “next”，因此回退到普通切换，而不是什么都不做。
                cancelArmedTrack();
                performOrdinaryNextAfterFailedHandover();
                return false;
            }

            if (!performAutomixHandover(activePlayer, track, MANUAL_SKIP_FADE_MILLIS)) {
                performOrdinaryNextAfterFailedHandover();
                return false;
            }
            return true;
        }

        /**
         * 原始的手动切换路径，在无缝切换路径无法使用时由播放线程运行。
         * 与 {@link CloudMusic#next()} 在没有任何预备时的行为完全相同：归属
         * 正在退出的曲目，将索引标记为被播放器控件修改，并关闭播放器，使
         * 监督循环开始下一首曲目。
         */
        private void performOrdinaryNextAfterFailedHandover() {
            if (doBreak || isPlaybackCancelled() || !isSessionUsable(session) || manualSelection != null) return;
            if (playList.isEmpty() || player == null || !canPlayNext()) return;
            updatePlayCountIfNeeded();
            prepareForTrackChange();
            curIdx++;
            stopCurrentPlayback();
        }

        /**
         * 一旦退出曲目到达计划点就触发交接；如果它已经结束则立即触发
         * （较晚的预备仍然胜过静默空隙）。
         */
        private boolean tryAutomixHandover(AudioPlayer activePlayer) {
            ArmedTrack track = armed;
            if (track == null || track.plan == null || activePlayer == null) return false;
            if (!armStillValid(track.generation, track.queueRevision)) {
                cancelArmedTrack();
                return false;
            }
            if (track.index < 0 || track.index >= playList.size()) {
                cancelArmedTrack();
                return false;
            }
            long position = (long) activePlayer.getCurrentTimeMillis();
            boolean ended = activePlayer.isFinished() || !playing.get();
            // 暂停中的曲目不得交接：进入的播放牌会自行开始发声
            // 而界面仍显示播放处于暂停状态。
            if (!ended && activePlayer.isPausing()) return false;
            // 一起听房间：服务端的换歌帧就是交叠起点。各端本地位置与房间时钟之间有几十毫秒
            // 抖动，按本地位置推算会让「帧先到还是本机先到」变成掷硬币 —— 而帧先到的那一刻这
            // 里还没触发，随后那记 S_PLAY 会把刚叠上的尾巴整段掐掉。所以帧到了就立刻交接。
            boolean byRoomFrame = track.roomMode && roomHandoverRequested;
            if (byRoomFrame) roomHandoverRequested = false;
            if (!byRoomFrame && !ended && position < track.plan.fireMillis) return false;
            return performAutomixHandover(activePlayer, track);
        }

        /**
         * 将已预备的播放牌提升为活动播放器：退出曲目在淡出期间持续发声，
         * 进入曲目则立即接管队列索引、session、歌词和历史上报。
         */
        /**
         * 本帧该画在进度条上的接缝位置，没有则 {@code null}。渲染线程每帧调一次
         * （经 {@link CloudMusic#automixCueNow()}）。
         *
         * <p>两个来源、两种归属，顺序不能反：交接<b>已经发生</b>时进度条显示的是进场那一首，
         * 位置只能取计划的 {@code incomingCueMillis}；还没交接时进度条是出场曲的，取
         * {@code fireMillis}。先问 {@code activeFade} 也正是这个意思——交接一旦开始，
         * {@code currentlyPlaying} 就已经换成新歌了。</p>
         *
         * <p>读的全是 {@code volatile} 引用与 {@code final} 字段，不加锁也不写任何东西：
         * 计划由预备线程与播放线程换手的规矩（见 {@link #installReplannedPlan}）不归这里管，
         * 界面只可能被滞后一帧看见，不可能看见半个计划。</p>
         */
        private AutomixCueMarker automixCueView() {
            AutomixFade fade = activeFade;
            if (fade != null && fade.plan != null) {
                long duration = deckDurationMillis(fade.incoming);
                if (duration <= 0L) return null;
                return new AutomixCueMarker(fade.plan.incomingCueMillis,
                        fade.plan.incomingCueMillis + fade.overlapMillis, duration, true);
            }
            ArmedTrack track = armed;
            if (track == null || track.plan == null) return null;
            // 预备已经作废（换了一首、队列动了、播放换了世代）就别预报：那份计划描述的
            // 是一次不会发生的交接。判据与预卷与点火用的是同一条，不另立一套。
            if (!armStillValid(track.generation, track.queueRevision)) return null;
            long duration = deckDurationMillis(ownedPlayer);
            if (duration <= 0L) return null;
            AutomixPlan plan = track.plan;
            return new AutomixCueMarker(plan.fireMillis,
                    plan.fireMillis + plan.overlapMillis, duration, false);
        }

        /** 这张牌已知的总时长，毫秒；牌不可用或时长还没落地时为 0。 */
        private static long deckDurationMillis(AudioPlayer deck) {
            AudioPlayer active = deck;
            if (active == null || !active.isUsable()) return 0L;
            long duration = Math.round(active.getTotalTimeMillis());
            return duration > 0L ? duration : 0L;
        }

        private boolean performAutomixHandover(AudioPlayer outgoing, ArmedTrack track) {
            return performAutomixHandover(outgoing, track, 0L);
        }

        /**
         * @param overlapOverrideMillis 用于替代计划重叠的渐变，或传 {@code 0} 以保留
         *                              计划自身的长度。用户按下的“next”会覆盖它：
         *                              计划的混音以小节为单位衡量，在响应按钮
         *                              按下时会显得迟缓。
         */
        private boolean performAutomixHandover(AudioPlayer outgoing, ArmedTrack track,
                                               long overlapOverrideMillis) {
            return performAutomixHandover(outgoing, track, overlapOverrideMillis, null);
        }

        private boolean performAutomixHandover(AudioPlayer outgoing, ArmedTrack track,
                                               long overlapOverrideMillis, ManualSelection selection) {
            armed = null;
            // 交接已经开始，等一条更好的接缝这件事到此为止（预算与截止时间都归零）。
            seamHoldUntil = 0L;
            // 手动下一首与选歌用同一套短 EQ 曲线，不能继承自动计划的 cue/分轨参数。
            boolean manualTransition = overlapOverrideMillis > 0L;
            AutomixPlan plan = manualTransition ? manualSelectionPlan() : track.plan;
            // 下载期间关闭总开关时，已挂好的链也不能继续收到 EQ/人声处理目标。
            if (manualTransition && !AutomixSettings.isEnabled()) {
                plan = AutomixPlan.manualQuickMix(MANUAL_SKIP_FADE_MILLIS, false);
            }
            AudioPlayer incoming = track.deck;
            if (incoming == null || !incoming.isUsable()) {
                closeQuietly(incoming);
                return false;
            }

            // 退出曲目实际还剩多少决定了渐变可以有多长。一个
            // 在计划点之后才就绪的预备——缓慢的音源、漫长的转码——
            // 否则会在四秒内让新曲目淡入，而没有任何内容与之对比，
            // 听起来就像一段空隙之后一首曲目悄悄冒出来，而不是一次混音。
            long remaining = Math.max(0L,
                    (long) outgoing.getTotalTimeMillis() - (long) outgoing.getCurrentTimeMillis());
            long requestedOverlap = overlapOverrideMillis > 0L ? overlapOverrideMillis : plan.overlapMillis;
            long effectiveOverlap = requestedOverlap;
            if (!outgoing.isUsable() || outgoing.isFinished() || remaining < 400L) {
                effectiveOverlap = LATE_FIRE_FADE_MILLIS;
            } else if (remaining < effectiveOverlap) {
                effectiveOverlap = Math.max(LATE_FIRE_FADE_MILLIS, remaining);
            }
            System.out.println("[Automix] handover -> " + track.song.getName() + " · " + plan.summary
                    + (overlapOverrideMillis > 0L ? " (manual skip)" : "")
                    + (plan.outgoingRate(0.5, effectiveOverlap) == 1f && plan.tempoRatio != 1f
                            ? " (rate correction dropped: " + effectiveOverlap + "ms ramp)" : "")
                    + (effectiveOverlap == requestedOverlap
                            ? "" : " (ramp shortened to " + effectiveOverlap + "ms, " + remaining + "ms left)"));

            PlaybackSession nextSession;
            synchronized (PLAYER_STATE_LOCK) {
                if (!isSessionUsable(session) || ownedPlayer != outgoing
                        || (selection == null ? manualSelection != null : manualSelection != selection)) {
                    closeQuietly(incoming);
                    return false;
                }
                incoming.setVolume(CloudMusic.getVolume());
                if (manualTransition) {
                    incoming.resetAutomixProcessing();
                    if (AutomixSettings.isEnabled()) incoming.enableManualTransitionProcessing();
                    // 自动预备已定位到分析 cue；手动切歌必须保留新歌开头。
                    if (selection == null || !track.plan.manualQuickMix) incoming.prepareStart(0f);
                }
                // 在播放牌发声之前设置：它会直接取该值而不做渐变过渡，因此进入的
                // 曲目在切换生效前绝不会先出现一声响亮的低音冲击。
                incoming.setBassGainDb(plan.incomingBassDb(0.0));
                // 同样的原因：抵消器会渐变过渡，因此深度必须在第一个
                // 块被渲染之前就位，否则到达的人声会在闪避启动前唱出一个音节。
                incoming.setVocalDuckDepth(plan.incomingVocalDepth(0.0));
                incoming.setMidGainDb(plan.incomingMidDb(0.0));
                incoming.setHighGainDb(plan.incomingHighDb(0.0));
                incoming.setFadeGain(plan.incomingGain(0.0));
                try {
                    if (track.preRolled) {
                        // 自预卷起就已经以全静音发声：上面的增益线就是
                        // 整个交接过程，正是这一点让接缝无法被听见。
                        if (track.preRollPaused) {
                            incoming.startPrepared();
                            track.preRollPaused = false;
                        }
                    } else if (incoming.isPrepared()) {
                        incoming.startPrepared();
                    } else {
                        incoming.playFrom(plan.incomingCueMillis);
                    }
                } catch (Throwable startFailure) {
                    System.err.println("[Automix] incoming deck failed to start: " + startFailure.getMessage());
                    closeQuietly(incoming);
                    return false;
                }

                // AudioPlayer 会吞掉某些引擎异常；没有接受播放命令就不能淡出旧歌。
                if (incoming.isPausing()) {
                    closeQuietly(incoming);
                    return false;
                }

                // 新牌已接受静音起播后，才收尾上一次渐变，避免连续点歌留下第三路声音。
                if (selection != null) finishAutomixFade();
                if (manualTransition && AutomixSettings.isEnabled()) outgoing.enableManualTransitionProcessing();

                // 在此处归属，而不是在播放牌启动之前：上面的每个提前返回
                // 都会让退出曲目继续播放，而普通完成路径随后会上报
                // 并再次将其计数。
                try {
                    NeteasePlaybackHistoryReporter.finish(outgoing,
                            NeteasePlaybackHistoryReporter.EndReason.COMPLETED);
                } catch (Throwable ignored) {
                }
                try {
                    KuGouPlaybackReporter.finish(outgoing, KuGouPlaybackReporter.EndReason.COMPLETED);
                } catch (Throwable ignored) {
                }
                if (!dontAdd && playedFrom != null && curIdx >= 0 && curIdx < playList.size()) {
                    try {
                        playList.get(curIdx).updPlayCount(playedFrom, outgoing.getCurrentTimeSeconds());
                    } catch (Throwable ignored) {
                    }
                }

                if (selection != null) {
                    if (selection.replaceQueue) {
                        songs = selection.songs;
                        playList = songs;
                        clearQueuedNext();
                    }
                    if (!selection.replaceQueue) {
                        playNextLedger.replacePendingIdentity(selection.selectedSong, track.song);
                    }
                    songs.set(track.index, track.song);
                    manualSelection = null;
                    selection.ready = null; // 所有权交给活动播放器，不再归取消路径释放。
                    dontAdd = false;
                }
                curIdx = track.index;
                currentlyPlaying = track.song;
                // 无缝交接是一条独立起播路径，销账得在这也做一次，否则用户排过的歌会一直挂在账上。
                noteQueuedTrackStarted(track.song);
                nextSession = CloudMusic.beginNewSession(track.song);
                session = nextSession;
                nextSession.player = incoming;
                nextSession.audioActive = true;
                player = incoming;
                ownedPlayer = incoming;
                fadingOutPlayer = outgoing;
                activeFade = new AutomixFade(outgoing, incoming, plan, effectiveOverlap,
                        System.currentTimeMillis());
                // 仅在混音期间注册：音频任务将此线程作为
                // 捕获的引用持有，因此让它在淡出结束后仍保持注册会使一个已结束的
                // 播放 session 在游戏剩余时间里一直存活。
                registerAutomixAudioTask();
                playing.set(true);
            }

            // 在封面、歌词与上报工作之前对齐视觉时钟；UI 不等待这些异步内容。
            boolean curveAnimation = AutomixSettings.getTransitionAnimationStyle()
                    == AutomixSettings.TransitionAnimationStyle.CURVE;
            if (AutomixSettings.isTransitionAnimationEnabled()
                    && (effectiveOverlap >= 5_000L || curveAnimation
                    && effectiveOverlap >= AutomixTransitionAnimation.MIN_ISLAND_DURATION_MILLIS)) {
                if (curveAnimation || AutomixSettings.isFoliaEngine() && plan.style != null
                        && plan.style != FoliaTransitionStyle.PLAIN_BLEND) {
                    AutomixTransitionCurve curve = AutomixTransitionCurve.from(plan);
                    AutomixTransitionAnimation.start(effectiveOverlap, curve.crossover(),
                            plan.animationPeriodMillis, curve);
                } else if (!AutomixSettings.isFoliaEngine()) {
                    DownloadDynamicIsland.showAutomixHandover(track.song.getName());
                }
            }

            final AudioPlayer callbackPlayer = incoming;
            final PlaybackSession callbackSession = nextSession;
            incoming.setAfterPlayed(() -> {
                if (ownedPlayer == callbackPlayer && isSessionUsable(callbackSession)) {
                    this.notifyWaitLock();
                }
            });

            loadMusicCover(track.song);
            loadDynamicMusicCover(track.song);
            loadLyric(track.song, nextSession, track.file);
            // 手动切歌不等分析；已出声后才为后续自动交接异步预热。
            if (selection != null) warmAdvancedEnvelope(incoming);
            NeteasePlaybackHistoryReporter.start(track.song, currentPlaylistContext, incoming);
            KuGouPlaybackReporter.start(track.song, incoming);
            QishuiPlaybackReporter.start(track.song, currentPlaylistContext);
            // 无缝交接同样是"一首 FM 曲目开始出声"，面板与预备标记都要跟着走。
            if (personalFmActive) PersonalFmManager.noteFmTrackStarted(track.song);
            return true;
        }

        /**
         * 推进等功率渐变。从监督播放的同一个 10 ms tick 调用。
         *
         * <p>曲线写入本身通常属于 JSyn 音频任务（见
         * {@link #writeTransitionFrameOnAudioThread()}），该任务每个输出缓冲
         * 区获得一次执行机会，而不是每个 tick 一次。本方法负责维持生命周期——
         * 暂停、恢复、结束混音——并在该任务未运行时担任写入者，因此过渡
         * 永远不依赖它。</p>
         */
        private void driveAutomixFade() {
            AutomixFade fade = activeFade;
            if (fade == null) return;

            long now = System.currentTimeMillis();
            AudioPlayer active = ownedPlayer;
            boolean paused = active != null && active.isUsable() && !active.isFinished() && active.isPausing();
            if (paused) {
                AutomixTransitionAnimation.setPaused(true);
                // 保持渐变，而不是让挂钟时间在暂停期间继续流逝，并将
                // 退出播放牌随活动播放牌一起关闭——播放器中没有其他部分知道它的存在。
                fade.startedAt += Math.max(0L, now - fade.tickedAt);
                fade.tickedAt = now;
                // 将 startedAt 平移空闲间隔正是让 now - startedAt 成为
                // 被保持的已用时间的做法，因此这是音频任务必须停留在其上的冻结进度。
                fade.pausedProgress = progressAt(fade, now);
                if (!fade.outgoingPaused && fade.outgoing != null && fade.outgoing.isUsable()
                        && !fade.outgoing.isPausing()) {
                    fade.outgoing.pause();
                    fade.outgoingPaused = true;
                }
                return;
            }
            AutomixTransitionAnimation.setPaused(false);
            if (fade.outgoingPaused && fade.outgoing != null) {
                // 仅当本次淡出是暂停它的那次淡出时才恢复：unpause() 会重新启动一个
                // 自行停止的播放牌从其入点位置继续，这会导致重新播放
                // 退出曲目从头开始。
                fade.outgoing.unpause();
                fade.outgoingPaused = false;
            }
            fade.pausedProgress = -1.0;
            fade.tickedAt = now;

            double progress = progressAt(fade, now);
            if (now - fade.audioDrivenAt >= AUDIO_DRIVEN_WINDOW_MILLIS) {
                // 最近没有音频任务写入过帧——从未注册、抛出异常，或者引擎
                // 已经消失——因此完全像它存在之前那样回退到 tick。
                writeTransitionFrame(fade, progress);
            }
            if (progress < 1.0) return;

            // 在下面将播放牌扁平化之前清除。音频任务已经在处理中的帧
            // 最坏情况下会重写计划自身的结束状态——对于进入播放牌来说，这个状态是
            // 完全平坦的——而且它是直接的端口写入，而下面的重置则通过
            // 命令队列进行，因此在它之后才被排空。
            activeFade = null;
            unregisterAutomixAudioTask();
            if (fade.incoming != null) {
                fade.incoming.setFadeGain(1.0f);
                // 曲目剩余部分采用平坦响应和自然速度：混音不留下任何痕迹
                // 可能会延续到普通播放中。
                fade.incoming.resetAutomixProcessing();
            }
            releaseFadedDeck(fade.outgoing);
        }

        /** 在 {@code now} 处的渐变位置，以渐变长度计；计划本身会对越界值做截断。 */
        private static double progressAt(AutomixFade fade, long now) {
            return (now - fade.startedAt) / (double) fade.overlapMillis;
        }

        /**
         * 在 {@code progress} 处对计划中的每条曲线采样，并写入两个播放牌。
         *
         * <p>一份实现，两个调用方：监督 tick 与 JSyn 音频任务都经过这里，因此回退帧与音频线程帧形态完全一致，二者对某个 progress 应当听感如何永远不会出现分歧。写入本身经由 {@code AudioPlayer.applyAutomixFrame}，它直接写端口而不把命令入队——参见 {@link repackage.processing.sound.SoundFile#setAmplitudeNow(float)}。</p>
         */
        private void writeTransitionFrame(AutomixFade fade, double progress) {
            if (fade.incoming != null) {
                fade.incoming.applyAutomixFrame(fade.plan.incomingGain(progress),
                        fade.plan.incomingBassDb(progress),
                        fade.plan.incomingVocalDepth(progress),
                        fade.plan.incomingMidDb(progress),
                        fade.plan.incomingHighDb(progress));
            }
            if (fade.outgoing != null) {
                // 依据实际正在运行的渐变来评估，而非计划中的渐变：缩短的渐变
                // 绝不能把一个半音的 tempo 锁定压缩到区区几百毫秒内。
                // 速度与音高分开给：节奏锁定只动速度（WSOLA，音高不变），整半音吸附只动音高
                // （重采样 + WSOLA 把时长还原）。合成一个数会让 WSOLA 把「移调」当成「变速」。
                fade.outgoing.applyOutgoingAutomixFrame(fade.plan.outgoingGain(progress),
                        fade.plan.outgoingBassDb(progress),
                        fade.plan.outgoingVocalDepth(progress),
                        fade.plan.outgoingMidDb(progress),
                        fade.plan.outgoingHighDb(progress),
                        fade.plan.outgoingTempo(progress, fade.overlapMillis),
                        fade.plan.outgoingPitch(progress, fade.overlapMillis));
            }
        }

        /**
         * 曲线写入器，运行在 JSyn 音频线程上，每个输出缓冲区执行一次（64 帧，即
         * 44.1 kHz 下约 1.5 ms），而非每个 10 ms 监督 tick 一次。
         *
         * <p>这正是关键所在：监督 tick 是一个休眠循环，与歌词查询、历史上报和 arm（预备）检查共享同一线程，因此它的节奏既更粗又不均匀，而它写入的渐变也继承了这种不均匀。音频线程依据声音时钟运行，而那才是渐变实际对照的时钟。</p>
         *
         * <p><b>纯粹附加。</b>此处没有任何承重逻辑：tick 用同样的公式写入同样的曲线，只有在 {@link AutomixFade#audioDrivenAt} 持续被刷新时才让出给本任务。异常会被吞掉并停止刷新，从而在下一个 tick 把写回交还给 tick。</p>
         */
        private void writeTransitionFrameOnAudioThread() {
            try {
                AutomixFade fade = activeFade;
                if (fade == null) return;
                double held = fade.pausedProgress;
                long now = System.currentTimeMillis();
                double progress = held >= 0.0 ? held : progressAt(fade, now);
                writeTransitionFrame(fade, progress);
                fade.audioDrivenAt = now;
            } catch (Throwable ignored) {
                // 刻意保持静默，也刻意不标记该帧：tick 会接手
                // 把写入重新接回，因此一次失败付出的代价只是分辨率下降。
            }
        }

        /**
         * 将曲线写入交由音频线程，持续长度为一次混音。
         *
         * <p>尽力而为：引擎尚未就绪，或拒绝该任务时，仍由 tick 担任写入者，其余一切不变。按每次混音注册、而非在整个线程生命周期注册，是刻意为之——任务会被捕获进引擎自己的列表，因此若注册存活过了它的渐变，就会把一个已结束的播放 session 滞留在内存中。</p>
         */
        private void registerAutomixAudioTask() {
            if (automixAudioTaskRegistered) return;
            try {
                Engine.addAudioTask(automixAudioTask);
                automixAudioTaskRegistered = true;
            } catch (Throwable ignored) {
                automixAudioTaskRegistered = false;
            }
        }

        private void unregisterAutomixAudioTask() {
            if (!automixAudioTaskRegistered) return;
            automixAudioTaskRegistered = false;
            try {
                Engine.removeAudioTask(automixAudioTask);
            } catch (Throwable ignored) {
            }
        }

        /** 提前结束一次混音（取消、手动切换），且不留下任何一个播放牌仍在发声。 */
        private void finishAutomixFade() {
            AutomixTransitionAnimation.cancel();
            AutomixFade fade = activeFade;
            activeFade = null;
            // 这同时也是 session 拆除路径（见 PlayThread.run），因此音频任务在此被丢弃
            // 而不是仅在混音自然走到结束处才丢弃。
            unregisterAutomixAudioTask();
            if (fade == null) return;
            if (fade.incoming != null && fade.incoming == ownedPlayer) {
                fade.incoming.setFadeGain(1.0f);
                fade.incoming.resetAutomixProcessing();
            }
            releaseFadedDeck(fade.outgoing);
        }

        private void releaseFadedDeck(AudioPlayer deck) {
            if (deck == null) return;
            synchronized (PLAYER_STATE_LOCK) {
                if (fadingOutPlayer == deck) fadingOutPlayer = null;
                // 被一个与本次渐变竞争的切换提升为活跃播放器：不归我们负责关闭。
                if (deck == ownedPlayer || deck == CloudMusic.player) {
                    // 但它必须被拉平，否则它会带着混音的低音切除继续播放
                    // 并在曲目剩余部分保持被扭曲的 tempo。
                    deck.resetAutomixProcessing();
                    return;
                }
            }
            closeQuietly(deck);
        }

        /** 丢弃一个从未触发的 arm（预备），释放其已解码的采样。 */
        private void cancelArmedTrack() {
            ArmedTrack track = armed;
            armed = null;
            // 已没有可交接的对象，因此一个挂起的手动请求只能回退到
            // 普通切换，而不是等待一个已不复存在的播放牌。
            manualSkipRequested = false;
            // 房间换歌请求同理：它只对「当时那一张」播放牌有意义。
            roomHandoverRequested = false;
            // 顶住交接等的是「这一张牌的新接缝」：牌没了，等也没有意义，立刻放行。
            seamHoldUntil = 0L;
            if (track == null || track.deck == null) return;
            if (track.deck == ownedPlayer || track.deck == CloudMusic.player) return;
            closeQuietly(track.deck);
        }

        private void closeQuietly(AudioPlayer deck) {
            if (deck == null) return;
            try {
                deck.close();
            } catch (Throwable ignored) {
                // 一个从未完成初始化的播放牌绝不能阻塞切换。
            }
        }

        private void loadMusicCover(Music song) {
            CloudMusic.loadMusicCover(song);
        }

        private void updateCurIdx() {
            if (personalFmActive) {
                // FM 批次是顺序的，绝不循环、重新洗牌或重复。
                if (dontAdd) dontAdd = false;
                else curIdx++;
                return;
            }
            if (playMode == PlayMode.LoopSingle) {
                // 单曲循环只影响自然播放结束；用户手动上一首/下一首仍可正常切歌。
                if (dontAdd) {
                    dontAdd = false;
                } else if (hasQueuedNext()) {
                    // 用户明确加入队列的曲目优先级高于单曲循环，且仅生效一次：
                    // 队列被消耗，循环则在接下来落到这里的曲目上恢复。
                    curIdx++;
                }

                if (curIdx < 0 || curIdx >= playList.size()) {
                    curIdx = 0;
                    // 指针被拽回队首，那块插入就不再"紧跟当前曲目"了，账本跟着作废。
                    clearQueuedNext();
                }
                return;
            }

            boolean changedByPlayerControl = dontAdd;
            dontAdd = false;
            if (!changedByPlayerControl) {
                curIdx++;
            }

            if (playMode == PlayMode.Random) {
                if (curIdx >= playList.size()) {
                    reshuffleRandomQueueForNextCycle(songs);
                    curIdx = 0;
                    // 整条列表刚被就地重洗，用户那点"下一首播放"排出来的顺序已经不存在了：账本作废。
                    // 曲目本身留在列表里，会按新的随机顺序被播到，这里不清它。
                    clearQueuedNext();
                }
                return;
            }

            if (playMode == PlayMode.LoopInList && curIdx >= playList.size()) {
                curIdx = 0;
                // 回到队首就是新一轮歌单，旧的那块插入已经落在播放指针后面了。
                // 这一处正是原来那个绝对尾下标会算出「待播 99」的地方：指针跳回 0，
                // 而下标还停在很远处，中间那几十格正常歌序全被读成了用户排过的队。
                clearQueuedNext();
            }
        }

        private void sleep(int millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void loadMusicCover(Music music) {
        loadMusicCover(music, false);
    }

    public static void loadMusicCover(Music music, boolean forceReload) {
        MusicCoverService.loadMusicCover(music, forceReload);
    }

    public static void loadDynamicMusicCover(Music music) {
        MusicCoverService.loadDynamicMusicCover(music);
    }

    /**
     * 渲染层用这个决定绑哪张封面:动态封面(网易云 MP4 抽帧)就绪时用它,否则用传入的静态封面。
     * 只有正在播放的曲目会有动态封面,歌单缩略图始终走静态图。
     */
    public static Location preferredCoverLocation(Music music, Location fallback) {
        return MusicCoverService.preferredCoverLocation(music, fallback);
    }

    /**
     * 背景（全屏歌词页与桌面歌曲信息条那张底）该绑哪张模糊图。
     *
     * <p>镜像中且外部播放器自己给了封面时，用由那张图现算的模糊；否则用曲目自己那张
     * {@code cover_blurred}。传 null 也返回 null，调用方不必先判空。</p>
     */
    public static Location preferredBlurredCoverLocation(Music music) {
        if (music == null) return null;
        Location external = ExternalArtwork.blurredLocation(music);
        return external != null ? external : music.getBlurredCoverLocation();
    }

    /**
     * 动态封面所需的 ffmpeg 是否就绪。返回 1 已找到、-1 探测过但没找到、0 尚未探测。
     * 只读缓存结论,可以安全地在渲染线程里调用(HUD 编辑器用它给开关加一行提示)。
     */
    public static int animatedCoverToolingState() {
        return MusicCoverService.ffmpegState();
    }
    public static BufferedImage gaussianBlur(BufferedImage imgIn, int blur) {
        return MusicCoverService.gaussianBlur(imgIn, blur);
    }


    private static void downloadMusic(String playUrl, File music, Music song) {
        // 只有 YouTube 曲目读取 YouTubeProxySettings；按曲目来源分流，避免按 URL 猜域名。
        MusicDownloadService.downloadMusic(playUrl, music,
                song != null && song.isYouTube() ? YouTubeProxySettings.createHttpProxy() : null, song);
    }

    // ─────────────────── 房间队列的提前下载与 automix profile 预热 ───────────────────
    //
    // 给 mpparty-common 的 MpRoomPrefetch 用：收到房间歌单后，把「当前这首之后」的若干首按实播
    // 那套缓存键提前落到磁盘，再把已经下好的那几首的交接 profile 先算出来。
    //
    // 三条约束，都是这套东西能成立的前提：
    //   1. 缓存键只有一份实现（PlayThread#cachedAudioFile）。提前下载算出另一个键，实播就永远
    //      命中不上那份预下载，功能整体退化成「同一首歌下两遍」。
    //   2. 直链由调用方给。房间队列条目本身就带着点歌人解析好的 url，这里绝不再去解析一次
    //      —— 解析是「代解析」那套链路的活，而且 Music#getPlayUrl() 会真发网络请求、会动
    //      playbackQuality 这类播放侧状态。
    //   3. 不建常驻播放牌、不碰队列与 curIdx。这两个方法跑在调用方自己的低优先级线程上，
    //      和播放线程唯一的交集就是磁盘。

    /**
     * 提前下载的去重集：{@code 稳定键 + 音质}，与缓存键同构（不含直链 —— 同一条曲目的直链
     * 每次解析都不一样，拿它去重等于没去）。一次队列刷新会排下几份同样的下载，CDN 连接与
     * 上行带宽一起被打爆，所以同一个键同时只允许一个在跑。
     */
    private static final Set<String> PREFETCH_IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 本线程正在做「提前下载 / profile 预热」这一类后台取流，下载卡片与
     * {@code musicInfo.downloading} 一律不许因此打到<b>正在播放的那一首</b>上。
     *
     * <p>刻意按线程记：预热线程与播放线程会同时在下载不同的歌，共用一位就会把真人那一次的
     * 进度条一起吞掉，那比多弹一张卡更糟。只在这两条链的入口置位，出口必清除。
     */
    private static final ThreadLocal<Boolean> QUIET_DOWNLOAD = new ThreadLocal<>();

    /** 后台预取链路自己问一句：这一趟的下载/转码要不要免扰界面。 */
    static boolean quietDownload() {
        return Boolean.TRUE.equals(QUIET_DOWNLOAD.get());
    }

    /**
     * 这一首值不值得在后台先替它把音频取满。
     *
     * <p>两类源值得：</p>
     *
     * <ul>
     *   <li><b>YouTube</b>：它的字节由那台出口 IP 的余额配给（约一秒攒一秒播放量），
     *       现取最坏要等接近曲长。把这段时间挪到「上一首还在响」的时候花掉，点下去就是本地秒开。</li>
     *   <li><b>汽水</b>：它的直链也是普通 CDN，一两秒就落盘 —— 贵的不是网络，是<b>落盘之后</b>。
     *       汽水交付的是 AAC/M4A，模组必须先做一次 AES-CTR 逐样本解密、再做一次整曲 AAC→WAV
     *       转码（引擎只按扩展名认 wav/flac/mp3，没有 AAC 解码器，见
     *       {@code SampleLoader#loadStreamedFloatSample}），这两步都与曲长成正比、单线程、跑在
     *       取流线程上。现取就是「点下去等好几秒的黑屏」。其余源没有这两步，所以不在此列：
     *       它们提前取只是白占用户的带宽与缓存。</li>
     * </ul>
     *
     * <p>只判「是不是这类源」，不判「此刻值不值得」——后者由调用点的位置闸与磁盘命中检查管。</p>
     */
    static boolean worthAudioPrefetch(Music song) {
        return song != null && (song.isYouTube() || song.isQishui());
    }

    /**
     * 真人那一次交接的分析此刻是不是正在跑（{@code armTrack} 从取流到出计划的全过程）。
     *
     * <p>房间 profile 预热挂在这个位子上让路：它每次要开动一首之前先看一眼，正在忙就不启动新的
     * 一首。预热是「可能白烧一次 Python」的活，真人交接是「几秒后就要出声」的活，两者抢同一套
     * 串行的 Python 子进程时永远让后者先走。</p>
     */
    private static final AtomicBoolean AUTOMIX_ANALYSIS_BUSY = new AtomicBoolean(false);

    /**
     * 把房间队列里的一首按<b>与实播完全相同</b>的缓存键落到磁盘。
     *
     * <p>语义就是「等它真轮到时，播放线程直接命中磁盘」：命中已有缓存时立刻返回，不发任何网络
     * 请求；没命中才走 {@link PlayThread#cachedAudioFile} 那条序列（容器探测命名、touch、
     * 删同曲其余音质、容量兜底、汽水 DRM）。不建播放牌、不碰播放状态、不改队列。</p>
     *
     * <p>与实播的两处有意差异：① 不做 AAC→WAV 转码（那是「能不能出声」的一步，实播与 profile
     * 预热各自按需做，见 {@link #warmAutomixProfile}）；② 失败只回 {@code false} 并留一行日志，
     * 绝不抛给调用方 —— 提前下载失败的正确处理是「等真轮到时按实播再下一遍」。</p>
     *
     * <p>调用方必须在<b>非渲染帧</b>的线程上（这里会真的下载）。失败/进行中的同一首由调用方自己
     * 记账，本方法只保证不会同时跑两份。</p>
     *
     * @param playUrl       点歌人给好的直链；为空即拒绝，本方法不解析、不调 {@code Music#getPlayUrl}
     * @param reportedType  协议里报的容器类型，可空。按 {@code getMusicFile} 同样的方式归一化，
     *                      只影响「先试哪个扩展名」的顺序，落盘文件名恒由真实字节决定
     * @return 结束时磁盘上有没有这份缓存
     */
    public static boolean prefetchRoomTrackAudio(String playUrl, String reportedType, Music song) {
        if (song == null || playUrl == null || playUrl.trim().isEmpty()) {
            return false;   // 没有可用直链：跳过，绝不为它去解析
        }
        // 取一次当前音质，去重槽与落盘用同一档：中途换音质不会让两份下载错开键。
        final Quality selected = quality;
        String slot = song.getStableKey() + '|' + selected.getQuality();
        if (!PREFETCH_IN_FLIGHT.add(slot)) {
            return false;   // 同一首已经有一份在下了
        }
        QUIET_DOWNLOAD.set(Boolean.TRUE);
        try {
            return PlayThread.cachedAudioFile(playUrl,
                    AudioContainerSupport.normalizeReportedContainer(reportedType),
                    song, selected, true) != null;
        } catch (Throwable failure) {
            // 坏链接、CDN 抽风、容器校验拒绝都走这里：留一行日志，队列真轮到它时实播会重走一遍
            // 完整链路（包括按字节的换源兜底），提前下载没有资格替它判定「这首歌不能播」。
            System.out.println("[MpPrefetch] 提前下载未命中 " + song.getName() + "：" + failure);
            return false;
        } finally {
            QUIET_DOWNLOAD.remove();
            PREFETCH_IN_FLIGHT.remove(slot);
        }
    }

    /**
     * 这一首在实播会用的那个缓存键上是不是已经有文件了。
     *
     * <p>只问磁盘，绝不联网、绝不下载。命中时顺手 {@code touch} 一笔（那是共用序列的一部分），
     * 所以房间反复问同一份歌单就等于把这几首在容量淘汰的最近使用账本上<b>置顶</b>：淘汰按
     * 「最冷的组」下手，房间即将播到的那些因此排在别人后面被删。</p>
     *
     * <p>缓存目录还不存在／权限异常等一律当作「没有缓存」：这是一个探测，不是播放步骤，
     * 没有资格抛异常把预热整条链路带崩。</p>
     */
    public static File cachedAudioFileFor(Music song) {
        if (song == null) return null;
        try {
            return PlayThread.cachedAudioFile(null, null, song, quality, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 音频缓存目录当前占用（字节）。目录不存在时为 0。 */
    public static long audioCacheUsedBytes() {
        try {
            return MusicAudioCache.totalBytes();
        } catch (Throwable ignored) {
            return Long.MAX_VALUE;      // 问不出来就别下新东西
        }
    }

    /** 用户设置的音频缓存上限（字节）；{@code 0} = 不限制。 */
    public static long audioCacheLimitBytes() {
        try {
            return NCMPlayerConfig.getMusicCacheLimitBytes();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 缓存所在分区的剩余空间（字节）；问不出来时按 0 处理（宁可不预热）。 */
    public static long audioCacheFreeSpaceBytes() {
        try {
            return MusicAudioCache.directory().getUsableSpace();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 本机此刻能不能做 profile 预热。
     *
     * <p>判据与 {@code MpPlayback.capabilityEngine()} 报 {@code "model"} 的那一条一致：
     * 引擎选了 Folia <b>且</b>运行时与两套权重（Beat This! 出节拍栅格、HTDemucs 出 stem 分离）
     * 都在盘上。缺一样时 Folia 内部会退回纯信号处理，那套根本不用 Python，预热它没有任何意义，
     * 只是白起一次子进程。多加一条「用户开着无缝切换」：关了 automix 就没有任何东西会用这份 profile。</p>
     */
    public static boolean automixProfileWarmupReady() {
        try {
            if (!AutomixSettings.isEnabled()) return false;
            if (!AutomixSettings.isFoliaEngine()) return false;
            return FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS)
                    && FoliaModelManager.isInstalled(FoliaModelManager.Kind.HTDEMUCS)
                    && FoliaModelManager.runtimeReady();
        } catch (Throwable ignored) {
            return false;   // 问不出来就按「不能跑」处理，绝不为预热去惊动模型下载
        }
    }

    /** 真人那一次交接的分析是不是正在跑。见 {@link #AUTOMIX_ANALYSIS_BUSY}。 */
    public static boolean automixAnalysisBusy() {
        return AUTOMIX_ANALYSIS_BUSY.get();
    }

    /**
     * 只找<b>已经存在</b>的可播文件，绝不转码、绝不删文件。
     *
     * <p>与 {@link PlayThread#resolvePlayableAudioFile} 的区别就在这儿：那一条发现边车无效时会
     * 删掉重转一遍，是播放路径的活儿；本方法是「现在就想知道有没有」，读不到就算没有。
     * AAC/MP4 容器认那张可复用的 {@code .decoded.wav} 边车，其它容器就是缓存本体。</p>
     */
    static File existingPlayableAudioFile(Music song) {
        File cached = cachedAudioFileFor(song);
        if (cached == null || !cached.isFile()) return null;
        try {
            String container = AudioContainerSupport.detectContainer(cached);
            if (!AudioContainerSupport.requiresAacDecode(container)) return cached;
            File decoded = AudioCacheFiles.getDecodedWavFile(cached);
            return AudioCacheFiles.isReusableDecodedWav(cached, decoded) ? decoded : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 进阶模式包络的<b>同步</b>版：就在调用线程上扫完、落盘，返回「此刻能不能读到」。
     *
     * <p>只给预备线程用（{@code armTrack} 在规划进场侧之前）。那条链本来就在低优先级池上、
     * 已经付过一次下载与解码，多解一遍整曲是它自己该干的活。异步的
     * {@link #warmAdvancedEnvelope(AudioPlayer)} 只能用于「以后再来拿」的场合：规划紧跟在同一段
     * 代码后面，提交完就走等于没提交。</p>
     *
     * @return 总开关关闭时返回 {@code true}（那时候不存在需要等的证据，缓存与复查都不该被它牵动）；
     *         否则返回这份包络此刻是否可用。
     */
    static boolean warmAdvancedEnvelopeNow(File source, long durationMillis) {
        if (!AutomixSettings.isAdvancedModeEnabled()) return true;
        if (source == null || !source.isFile() || durationMillis <= 0L) return false;
        if (AutomixEnvelope.isPresent(source)) return true;
        try {
            AutomixEnvelope.ensure(source, durationMillis);
        } catch (Throwable failure) {
            System.out.println("[Automix/Envelope] 预备期扫描失败 " + source.getName() + "：" + failure);
        }
        return AutomixEnvelope.isPresent(source);
    }

    /**
     * 进阶模式的整曲包络预热：把这一首的 {@link AutomixEnvelope} 丢进后台池扫一遍并落盘。
     *
     * <p>调用方可以是任何线程——本方法自己只做两次判空加一次入队，绝不解码、绝不起进程。</p>
     *
     * <p>总开关关闭时它<b>第一行就返回</b>：不入队、不读盘、不写第二份缓存。进阶模式关掉
     * 必须等价于「这个功能不存在」，而不是「存在但每首歌都白扫一遍」——一次整曲扫描是
     * 几百万次滤波，摊到几百首缓存上不是可以忽略的开销。</p>
     */
    static void warmAdvancedEnvelope(AudioPlayer deck) {
        if (!AutomixSettings.isAdvancedModeEnabled()) return;
        if (deck == null) return;
        final File source = deck.getSourceFile();
        final long duration = (long) deck.getTotalTimeMillis();
        if (source == null || !source.isFile() || duration <= 0L) return;
        if (AutomixEnvelope.isPresent(source)) return;   // 早就扫好了：连入队都不必
        BackgroundWork.submit(() -> {
            try {
                AutomixEnvelope.ensure(source, duration);
            } catch (Throwable failure) {
                System.out.println("[Automix/Envelope] 预热失败 " + source.getName() + "：" + failure);
            }
        });
    }

    /**
     * 预热这一首的 <b>automix 单曲 profile</b>：把 Folia 那套 Python 分析（节拍栅格 + stem 分离）
     * 的结果落进 {@code config/cache/automix-profiles}，等它真轮到交接时直接读盘。
     *
     * <h3>刻意不算配对计划（pair plan）</h3>
     * {@code AutomixPlanCache.keyOf} 的键带着「退出曲的最后一句人声位置」与两路 deck 的缓存文件名，
     * 前者要歌词、后者要正在预备的那对 deck —— 提前算必然算出不命中的键，还会因歌词没加载而
     * 白烧一次 Python。所以这里只做 {@code FoliaTrackProfiler.loadOrAnalyse(deck, false)}
     * 那一半（这一首将来是「进来」的那一首），pair 计划留给交接点前那次真正的预备。
     *
     * <h3>profile 键必须和实播算出来的那个是同一个</h3>
     * profile 的盘键是「音频文件绝对路径 + 长度 + mtime + 模型集」，而实播 deck 建在
     * {@link PlayThread#resolvePlayableAudioFile} 的结果上（AAC/MP4 是 {@code .decoded.wav} 边车，
     * 不是下载到的原始文件）。所以这里也先解析再建牌：直接用原始文件建牌会写出一份键不同的
     * profile，看着像预热过了，真交接时照样重跑一遍。
     *
     * <p>调用方必须在非渲染帧的低优先级线程上：这里会解码整曲、起 Python 子进程（一次 120–180 秒）。
     * 播放牌是局部的、用完即关，不挂进 {@link #player}，也不碰 {@code armed}。</p>
     *
     * <p>「一键排列」里量不出特征的那些就走这一条补料，见 {@link QueueFlowSorter}。</p>
     *
     * @return 这一首的 profile 现在是不是已经在盘上（命中缓存也算 true）
     */
    public static boolean warmAutomixProfile(Music song) {
        if (!automixProfileWarmupReady()) return false;
        File cached = cachedAudioFileFor(song);
        if (cached == null) return false;   // 还没下载好：什么都不做，更不联网
        AudioPlayer deck = null;
        QUIET_DOWNLOAD.set(Boolean.TRUE);
        try {
            File playable = PlayThread.resolvePlayableAudioFile(cached, song);
            long limit = AutomixSettings.maxDeckBytes();
            if (playable.length() > limit) {
                // 与预备同一道闸：两个活跃播放牌的常驻 PCM 会翻倍，预热没有资格把它抬高。
                System.out.println("[MpPrefetch] 跳过 profile 预热 " + song.getName() + "："
                        + PlaybackMemoryLimits.megabytes(playable.length()) + "MB 超出 "
                        + PlaybackMemoryLimits.megabytes(limit) + "MB 的播放牌预算");
                return false;
            }
            deck = new AudioPlayer(playable);
            deck.setTrackName(PlayThread.trackDisplayName(song, playable));
            FoliaTrackProfiler.loadOrAnalyse(deck, false);
            // 进阶模式的整曲包络就在这条<b>已经付过成本</b>的预热里一起扫：同一个低优先级线程、
            // 同一个已经解析好的可播文件。另起一条预热链只会让同一首歌被解码两遍。
            if (AutomixSettings.isAdvancedModeEnabled()) {
                AutomixEnvelope.ensure(playable, (long) deck.getTotalTimeMillis());
            }
            return true;
        } catch (Throwable failure) {
            System.out.println("[MpPrefetch] profile 预热失败 " + song.getName() + "：" + failure);
            return false;
        } finally {
            QUIET_DOWNLOAD.remove();
            if (deck != null) {
                try {
                    deck.close();
                } catch (Throwable ignored) {
                    // 一个从未完成初始化的播放牌绝不能阻塞预热。
                }
            }
        }
    }

    public static void writeTo(InputStream src, OutputStream dest) {
        MusicDownloadService.writeTo(src, dest);
    }

    public static void initLyrics(JsonObject rawLyricData, Music music, List<LyricLine> parsedLyrics) {
        resetLyricFlags();
        if (rawLyricData != null) {
            detectTranslations(rawLyricData);
        }
        if (parsedLyrics != null) {
            for (LyricLine line : parsedLyrics) {
                if (line == null) continue;
                if (line.translationText != null && !line.translationText.trim().isEmpty()) hasTransLyrics = true;
                if (line.romanizationText != null && !line.romanizationText.trim().isEmpty()) hasRomanization = true;
            }
        }

        synchronized (lyrics) {
            LyricTimelineSupport.PreparedTimeline timeline = LyricTimelineSupport.prepare(
                    parsedLyrics == null ? Collections.<LyricLine>emptyList() : parsedLyrics);
            lyrics.clear();
            lyrics.addAll(timeline.lines);
            currentLyric = lyrics.get(0);
            haveNoWords = timeline.haveNoWords;
            lyricsTrackKey = music == null ? null : music.getStableKey();
            lyricsRevision++;
        }

        MusicLyricsPanel.updateLyricPositionsImmediate(NCMScreen.getInstance().getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
    }

    /**
     * 两阶段提交的第二阶段：只在 Minecraft 主线程调用，把已验证的歌词结果提交为当前 Timeline。
     * 提交前再做一次 sessionId + songId 双重校验，并用当前 AudioPlayer 的真实 positionMs 对齐
     * currentLyric（歌词可先到或后到，均以声音时钟为准）。
     */
    public static void applyLyricTimeline(PlaybackSession session, JsonObject rawLyricData, List<LyricLine> parsedLyrics) {
        Music current = currentlyPlaying;
        if (!session.isActive() || current == null || !current.getStableKey().equals(session.trackKey)) {
            return;  // 提交瞬间已切歌：丢弃旧歌词，不污染新状态
        }

        initLyrics(rawLyricData, current, parsedLyrics);
        // 歌词换了歌，时间轴偏移也要换成这一首的。放在 initLyrics 之后：initLyrics 会用
        // currentLyric = lyrics.get(0) 定个初值，那一步不该受上一首的偏移影响。
        restoreLyricOffsetFor(current);

        AudioPlayer p = session.player != null ? session.player : player;
        if (p != null) {
            updateCurrentLyric(p.getCurrentTimeMillis());
            // 歌词迟到时按真实 position 立即重新居中，避免先按首句定位再跳变
            MusicLyricsPanel.updateLyricPositionsImmediate(NCMScreen.getInstance().getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
        }
    }

    /**
     * 不可变播放快照：渲染一帧只获取一次，本帧歌词、进度条、逐字高亮全部使用同一快照，
     * 避免一帧内跨线程读取到不同歌曲 / 不同 position。
     */
    public static class PlaybackSnapshot {
        public final long sessionId;
        public final long songId;
        public final long positionMs;
        public final long durationMs;
        public final boolean playing;
        public final AudioPlayer player;
        public final Music music;

        public PlaybackSnapshot(long sessionId, long songId, long positionMs, long durationMs, boolean playing, AudioPlayer player, Music music) {
            this.sessionId = sessionId;
            this.songId = songId;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.player = player;
            this.music = music;
        }
    }

    public static PlaybackSnapshot getSnapshot() {
        // 系统播放器镜像：整条渲染时钟只在这里分叉一次。
        //
        // 之所以能只改这一处，是因为下面那套墙钟外推本来就是为「音频时钟只在解码回调里
        // 前进、两次回调之间完全冻结」设计的；把 SMTC 每 500ms 报回来的位置喂进同一个
        // 外推器，得到的仍然是逐帧连续的位置。桌面歌词、全屏歌词页、逐字高亮、灵动岛
        // 常驻页因此一行都不用动。
        if (ExternalPlayback.isMirroring()) {
            PlaybackSnapshot mirrored = mirroredSnapshot();
            if (mirrored != null) return mirrored;
        }
        AudioPlayer p = player;
        Music m = currentlyPlaying;
        PlaybackSession s = currentSession;
        long sid = s == null ? -1L : s.sessionId;
        long songId = m == null ? -1L : m.getId();
        long rawPositionMs = (p == null || m == null) ? 0L : Math.round(p.getCurrentTimeMillis());
        long durationMs = (p == null || m == null) ? 0L : Math.round(p.getTotalTimeMillis());
        // 这里回答的是“从用户视角看它算不算在播放”，与暂停
// 图标读取的是同一来源（二者都调用 isPausing()）。它过去只测试 isFinished()，因此一条暂停的曲目——以及
// 一条尚未开始的已解码曲目——都被上报为 playing == true：该字段
// 名为 playing，回答的却是“有一条曲目且它尚未结束”，而任何把它
// 当作“它是否正在发声”来用的调用方都会得到错误答案。
        boolean playing = p != null && m != null && !p.isFinished() && !p.isPausing();
        long positionMs = (p == null || m == null)
                ? 0L
                : smoothPositionMs(songId, rawPositionMs, durationMs, p.isPausing());
        return new PlaybackSnapshot(sid, songId, positionMs, durationMs, playing, p, m);
    }

    /**
     * 由系统媒体会话（SMTC）构造的一次快照。
     *
     * <p>{@code player} 恒为 {@code null}：镜像不产生任何声音，本模组的音频链路完全没参与。
     * 因此凡是拿 {@code snapshot.player} 当「有没有播放时钟」用的地方都必须改问
     * {@link #hasPlaybackClock()}，否则镜像态下进度条与歌词会一起停在 0。</p>
     */
    private static PlaybackSnapshot mirroredSnapshot() {
        Music m = currentlyPlaying;
        ExternalSnapshot external = ExternalPlayback.rawSnapshot();
        if (m == null || external == null || !external.hasSession()) return null;
        PlaybackSession s = currentSession;
        long sid = s == null ? -1L : s.sessionId;
        boolean playing = external.isPlaying();
        long durationMs = external.durationMs() > 0L ? external.durationMs() : m.getDuration();
        long positionMs = smoothPositionMs(m.getId(), external.positionMs(), durationMs, !playing);
        return new PlaybackSnapshot(sid, m.getId(), positionMs, durationMs, playing, null, m);
    }

    /**
     * 这一帧有没有一个可用的播放时钟。
     *
     * <p>界面上原先写的是 {@code CloudMusic.player != null}，那在镜像态下是假的：
     * 外部播放器在响，只是不经过我们的音频引擎。这个判断就是给那几处用的。</p>
     */
    public static boolean hasPlaybackClock() {
        return ExternalPlayback.isMirroring() || player != null;
    }

    /**
     * 本帧进度条该不该画出 automix 的接缝，画在哪儿；不该画时返回 {@code null}。
     *
     * <p>界面原本对 automix 一无所知：进度条在交接前后长得一模一样，玩家听得到接缝却看不见
     * 它。这里把已经算好的那份计划（{@code AutomixPlan} 与其字段都是包内可见的，界面跨着包
     * 读不到）转成四个进度条用得上的数，三处绘制点只问这一处。</p>
     *
     * <p>四道短路，都是「宁可不画也不要画错」：</p>
     * <ul>
     *   <li>设置里关了「切点标记」；</li>
     *   <li>关着「无缝切换」——这时根本不会有交接，标记会预报一件不会发生的事；</li>
     *   <li>系统播放器镜像态（{@link ExternalPlayback#isMirroring()}）——出声的是外部播放器，
     *       本机的音频链路完全没参与，一份本机计划都不存在；</li>
     *   <li>播放线程还没换到这一代（{@code playThread} 声明成 {@code Thread}，拿不到
     *       {@code PlayThread} 上的 {@code armed} / {@code activeFade}）。</li>
     * </ul>
     */
    public static AutomixCueMarker automixCueNow() {
        if (!AutomixSettings.isCueMarkerEnabled() || !AutomixSettings.isEnabled()) return null;
        if (ExternalPlayback.isMirroring()) return null;
        Thread thread = playThread;
        if (!(thread instanceof PlayThread)) return null;
        return ((PlayThread) thread).automixCueView();
    }

    /**
     * 从玩家视角看「现在有没有声音在放」。暂停与没在放走同一支显示。
     *
     * <p>原先各处直接写 {@code player == null || player.isPausing()}。这里保留内部播放时
     * 的逐字段语义（播完一首但引擎还在也算「在响」，跟旧行为一致），只额外接上镜像。</p>
     */
    public static boolean isAudibleNow() {
        if (ExternalPlayback.isMirroring()) return ExternalPlayback.rawSnapshot().isPlaying();
        AudioPlayer active = player;
        return active != null && !active.isPausing();
    }

    /**
     * 有没有一首「正在播或暂停中」的曲目 —— 内部引擎与外部镜像都算。
     *
     * <p>界面上原先写的是 {@code player != null && !player.isFinished()}。镜像态下没有内部
     * 引擎，照原样判会让桌面歌词与歌曲信息条整块不画，表现成「镜像明明接管了，屏幕上却
     * 什么都没多出来」。内部播放的语义这里一个字都不改：还是「引擎在且这首没放完」。</p>
     */
    public static boolean hasLiveTrack() {
        if (ExternalPlayback.isMirroring()) {
            ExternalSnapshot external = ExternalPlayback.rawSnapshot();
            return external.hasSession() && !external.isStopped();
        }
        AudioPlayer active = player;
        return active != null && !active.isFinished();
    }

    /**
     * 这一帧该显示的位置，毫秒。
     *
     * <p>内部播放照旧直接读引擎，不改动逐字高亮现有的时序口径；镜像态下出声的是外部
     * 播放器，只能取快照里那份（外报位置已经过同一套墙钟外推，逐帧仍然连续）。</p>
     */
    public static long positionMsNow() {
        AudioPlayer active = player;
        if (ExternalPlayback.isMirroring() || active == null) return getSnapshot().positionMs;
        return Math.round(active.getCurrentTimeMillis());
    }

    /** 这一帧该显示的总时长，毫秒。口径与 {@link #positionMsNow()} 一致。 */
    public static long durationMsNow() {
        AudioPlayer active = player;
        if (ExternalPlayback.isMirroring() || active == null) return getSnapshot().durationMs;
        return Math.round(active.getTotalTimeMillis());
    }

    /**
     * 镜像让出播放态时的清场。
     *
     * <p>歌词一定要跟着清：{@link #lyrics} 是全局的，只有加载成功才会被替换，留着上一首
     * （很可能正是外部那首）的时间轴、而归属键 {@link #lyricsTrackKey} 还指得上的话，
     * 关掉镜像后桌面歌词会继续按内部播放器的进度滚 external 的词。</p>
     */
    public static void clearExternalMirrorState() {
        player = null;
        currentlyPlaying = null;
        currentSession = null;
        playList = new ArrayList<Music>();
        curIdx = 0;
        synchronized (lyrics) {
            lyrics.clear();
            lyricsRevision++;
        }
        currentLyric = null;
        lyricsTrackKey = null;
        haveNoWords = false;
        hasTransLyrics = false;
        hasRomanization = false;
    }

    // ── 渲染侧平滑播放位置 ──────────────────────────────────────────────────────

    private static final Object SMOOTH_CLOCK_LOCK = new Object();
    /**
     * 外推上限（毫秒）。音频线程真卡住时，歌词最多往前跑这么多就停住等它，而不是一路飞走。
     */
    private static final double SMOOTH_CLOCK_MAX_EXTRAPOLATE_MS = 500.0;
    /** 判定为 seek 的回退量与前跳量（毫秒）。 */
    private static final long SMOOTH_CLOCK_BACK_JUMP_MS = 200L;
    private static final long SMOOTH_CLOCK_FWD_JUMP_MS = 2000L;

    private static long smoothClockAnchorNs = 0L;
    private static double smoothClockAnchorMs = 0.0;
    private static double smoothClockOutMs = -1.0;
    private static long smoothClockRawMs = -1L;
    private static long smoothClockSongId = Long.MIN_VALUE;

    /**
     * 每帧连续前进的播放位置。
     *
     * <p>{@code AudioSample.position()} 只在解码回调里前进，两次回调之间完全冻结。而逐字上浮、
     * KTV 填涂前沿、逐字缩放都是以毫秒为自变量的连续动画——自变量是阶梯的时候，动画曲线再顺
     * 也没用，看起来就是一跳一跳。</p>
     *
     * <p>照 modernMP 的做法：音频时钟只用来校准锚点，每帧的位置由系统墙钟从锚点外推。这里比它
     * 多做一件事——每次音频时钟真的前进就重新锚定，因此不会累积墙钟与音频硬件时钟之间的漂移；
     * 输出再取一次历史最大值，保证同一段播放内单调，不会因为重锚而倒退一两毫秒。</p>
     *
     * <p>换歌、seek、暂停这三种情况下没有可信的历史，直接跟随音频时钟并重新锚定。</p>
     */
    private static long smoothPositionMs(long songId, long rawMs, long durationMs, boolean paused) {
        synchronized (SMOOTH_CLOCK_LOCK) {
            long nowNs = System.nanoTime();

            boolean discontinuous = songId != smoothClockSongId
                    || smoothClockRawMs < 0L
                    || rawMs < smoothClockRawMs - SMOOTH_CLOCK_BACK_JUMP_MS
                    || rawMs > smoothClockRawMs + SMOOTH_CLOCK_FWD_JUMP_MS;
            if (discontinuous || paused) {
                smoothClockSongId = songId;
                smoothClockRawMs = rawMs;
                smoothClockAnchorNs = nowNs;
                smoothClockAnchorMs = rawMs;
                smoothClockOutMs = rawMs;
                return rawMs;
            }

            // 音频时钟前进了一格：把锚点挪到这一格上，外推从这里重新起算。
            if (rawMs != smoothClockRawMs) {
                smoothClockRawMs = rawMs;
                smoothClockAnchorNs = nowNs;
                smoothClockAnchorMs = rawMs;
            }

            double elapsedMs = (nowNs - smoothClockAnchorNs) / 1_000_000.0;
            if (elapsedMs < 0.0) elapsedMs = 0.0;
            double extrapolated = smoothClockAnchorMs
                    + Math.min(elapsedMs, SMOOTH_CLOCK_MAX_EXTRAPOLATE_MS);
            double out = Math.max(smoothClockOutMs, extrapolated);
            if (durationMs > 0L) out = Math.min(out, durationMs);
            smoothClockOutMs = out;
            return Math.round(out);
        }
    }

    private static void resetLyricFlags() {
        hasTransLyrics = false;
        hasRomanization = false;
    }

    private static void detectTranslations(JsonObject lyric) {
        if (hasLyricsType(lyric, "tlyric") || hasLyricsType(lyric, "ytlrc")) hasTransLyrics = true;
        if (hasLyricsType(lyric, "romalrc") || hasLyricsType(lyric, "yromalrc")) hasRomanization = true;
    }

    private static boolean hasLyricsType(JsonObject lyric, String type) {
        if (lyric.has(type) && lyric.get(type).isJsonObject()) {
            JsonObject lyricTypeObj = lyric.get(type).getAsJsonObject();
            return lyricTypeObj.has("lyric") && !lyricTypeObj.get("lyric").getAsString().isEmpty();
        }
        return false;
    }

    /**
     * 更新当前歌词行
     *
     * @param songProgress 歌曲进度 (ms)
     */
    /**
     * 当前歌的歌词时间轴偏移（毫秒），正数表示<b>歌词提前</b>。
     *
     * <p>曲子与歌词版本对不齐是常态（现场版、重制版、第三方歌词源），自动匹配再准也会有需要手动
     * 微调的时候。所有歌词时钟都汇到 {@link #findCurrentLyric(double)}，所以补偿只需要在那里加一次——
     * 逐字高亮、扫光、发光、居中定位读的都是同一个 {@code currentLyric}，不会出现某一层跟着偏、
     * 另一层没偏的错位。</p>
     */
    private static volatile long lyricOffsetMillis = 0L;

    public static long getLyricOffsetMillis() {
        return lyricOffsetMillis;
    }

    /**
     * 设置当前歌的偏移并记住它。调用方自行保证在主线程执行（会重算当前行）。
     *
     * @return 实际生效的偏移（已夹取到 ±10 秒）
     */
    public static long setLyricOffsetMillis(long offsetMillis) {
        long clamped = LyricOffsetMemory.clamp(offsetMillis);
        lyricOffsetMillis = clamped;
        Music current = currentlyPlaying;
        if (current != null) {
            LyricOffsetMemory.write(current.getStableKey(), clamped);
        }
        applyOffsetImmediately();
        return clamped;
    }

    /** 在当前偏移上加一个增量。 */
    public static long adjustLyricOffsetMillis(long deltaMillis) {
        return setLyricOffsetMillis(lyricOffsetMillis + deltaMillis);
    }

    /**
     * 拖动中的实时预览：只改内存值并立刻重算，<b>不写盘</b>。
     *
     * <p>滑块是逐帧回调的，一帧一次 {@code LyricOffsetMemory.write} 会把整张「歌曲→偏移」表
     * 反复序列化（最多上千条）。所以拖动期间走这条轻量路径，松手时再 {@link #commitLyricOffset()}
     * 落盘一次。</p>
     */
    public static long previewLyricOffsetMillis(long offsetMillis) {
        lyricOffsetMillis = LyricOffsetMemory.clamp(offsetMillis);
        applyOffsetImmediately();
        return lyricOffsetMillis;
    }

    /** 把当前偏移（通常来自拖动预览）落盘到这首歌的记忆里。 */
    public static void commitLyricOffset() {
        Music current = currentlyPlaying;
        if (current == null) return;
        LyricOffsetMemory.write(current.getStableKey(), lyricOffsetMillis);
    }

    /** 偏移归零并从记忆里删掉这一首。 */
    public static void resetLyricOffset() {
        setLyricOffsetMillis(0L);
    }

    /** 切歌时把这一首上次用过的偏移捡回来。 */
    private static void restoreLyricOffsetFor(Music music) {
        lyricOffsetMillis = music == null ? 0L : LyricOffsetMemory.read(music.getStableKey());
    }

    /** 改完偏移立刻按当前播放位置重算，用户马上能看到对不对齐。 */
    private static void applyOffsetImmediately() {
        final PlaybackSession session = currentSession;
        AudioPlayer active = session != null && session.player != null ? session.player : player;
        if (active == null) return;
        MultiThreadingUtil.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (lyricsMatchCurrentTrack()) {
                    updateCurrentLyric(active.getCurrentTimeMillis());
                }
            }
        });
    }

    /**
     * 手动「重取歌词」：清掉全部缓存与各源的记忆，再走一遍完整解析链。
     *
     * <p>自动匹配必然有边界情况（同名不同版本、歌词库缺条目、上游换了歌词），没有手动兜底时用户
     * 只能等缓存过期。这里刻意<b>只清当前这一首</b>，队列里其它歌的缓存不动。</p>
     *
     * <p><b>它修不好「匹配错了」。</b>重取只是把同一条确定性链路再跑一遍——输入（本地元数据、
     * 检索词、打分规则）没变，输出必然一模一样。用户手里的错歌词属于这种情况时，唯一出路是换一个源，
     * 那由 {@link #setLyricSource(int)} 负责。两者刻意分开：换源是「这首歌用哪个库」的持久偏好，
     * 重取是「同一个库再问一次」的一次性动作。</p>
     *
     * <p><b>重取会把用户手动钉住的那一条一起丢掉。</b>这一条是「把上一份结果整个作废」的总开关，
     * 手动结果同样是上一份结果；留着它的话，重取永远拿回同一份人工歌词，那个按钮就白点了。
     * 改来源偏好（{@link #setLyricSource(int)}）不清它——钉住比偏好更具体，用户挑过的词不该被
     * 一次「换库」顺带走。</p>
     */
    public static void reloadLyrics() {
        reloadLyrics(true);
    }

    /**
     * @param dropManualPin 是否连带丢掉用户手动钉住的那一条。界面上的「重取歌词」传 {@code true}
     *                      （那就是「上一份结果整个作废」的入口）；换来源只是改偏好，传 {@code false}。
     */
    private static void reloadLyrics(boolean dropManualPin) {
        final Music music = currentlyPlaying;
        final PlaybackSession session = currentSession;
        if (music == null || session == null || !session.isActive()) return;

        int source = LyricSourceMemory.read(music.getStableKey());
        System.out.println("[Music] 重取歌词: " + music.getStableKey()
                + "（来源 " + LyricSourceMemory.displayName(source) + "）"
                + (dropManualPin && LyricManualMemory.has(music.getStableKey())
                ? "，并丢掉手动钉住的那一条" : ""));
        // 先清缓存、后删钉住记录：缓存键里带着钉住的那一条，记录还在才算得出那个键。
        LyricLoadService.invalidateAll(music);
        if (dropManualPin) LyricManualMemory.clear(music.getStableKey());
        loadLyric(music, session);
    }

    /** 当前这首歌指定的歌词来源；没有正在播放的曲子时返回「自动」。 */
    public static int getLyricSource() {
        Music current = currentlyPlaying;
        return current == null ? LyricSourceMemory.AUTO
                : LyricSourceMemory.read(current.getStableKey());
    }

    /**
     * 上一轮实际服务这首歌的来源；界面显示「现在用的是哪个源」用。
     *
     * @return {@link LyricSourceMemory#AMLL} 等具体来源，或 {@code -1} 表示本次进程还没解析过
     */
    public static int getServedLyricSource() {
        Music current = currentlyPlaying;
        return current == null ? -1 : LyricLoadService.servedSource(current.getStableKey());
    }

    /**
     * 指定这首歌的歌词来源并立刻重取。
     *
     * <p>写盘与重取一起做：来源是<b>按歌记住的偏好</b>，切歌回来、重启之后仍然有效，
     * 所以不能在内存里飘着。</p>
     *
     * <p>这里<b>不</b>清手动钉住的那一条：钉住是「这一首用哪份词」的人工确认，比来源偏好更具体，
     * 换库不该把它顺带走。代价是钉住期间换源看不出效果——界面上「歌词同步」那一行会把「已手动
     * 选择 …」显示出来，这本身就是解释。想丢掉那份结果有「取消钉住」与「重取歌词」两个入口。</p>
     */
    public static void setLyricSource(int source) {
        final Music music = currentlyPlaying;
        if (music == null) return;
        LyricSourceMemory.write(music.getStableKey(), LyricSourceMemory.clamp(source));
        reloadLyrics(false);
    }

    /** 在可选来源之间绕圈，{@code forward=false} 时往前一档。 */
    public static void cycleLyricSource(boolean forward) {
        int current = getLyricSource();
        setLyricSource(forward ? LyricSourceMemory.next(current) : LyricSourceMemory.previous(current));
    }

    /**
     * 用户在歌词搜索弹窗里亲手挑中的那一条：钉住它，并立刻按它重取。
     *
     * <p>存的是「源 + 候选」而不是歌词正文本身（见 {@link LyricManualMemory}），所以这里走的是
     * 正常的重取链路：钉住的那一条会在 {@code LyricLoadService.resolve} 的第 0 级被精确重取，
     * 与其他歌词用同一套缓存、同一套上屏流程。</p>
     *
     * <p>顺序上先清缓存、再写新记录：清的那一步要把<b>旧</b>钉住留在盘上的那份结果一起抹掉，
     * 而缓存键算得出来靠的还是旧记录。</p>
     */
    public static void pinLyric(LyricSearchHit hit) {
        pinLyric(currentlyPlaying, hit);
    }

    /**
     * 把某一条歌词钉给<b>指定</b>的那首歌。
     *
     * <p>存在的理由：选词弹窗也能从歌曲列表的某一行打开，而那一行通常不是正在放的歌。钉词记录
     * 本来就是按 {@code stableKey} 存的，给没在放的歌提前钉一份词是合法的 —— 下次播到它时自然
     * 生效。只有正在这首的时候才有会话可刷，别的歌只需落盘。</p>
     *
     * <p>顺序上先清缓存、再写新记录：清的那一步要把<b>旧</b>钉住留在盘上的那份结果一起抹掉，
     * 而缓存键算得出来靠的还是旧记录。</p>
     */
    public static void pinLyric(Music music, LyricSearchHit hit) {
        if (music == null || hit == null) return;

        LyricLoadService.invalidateAll(music);
        LyricManualMemory.Pin pin = hit.toPin();
        LyricManualMemory.write(music.getStableKey(), pin);
        System.out.println("[Music/Manual] 钉住歌词 " + pin.describe()
                + " for " + music.getStableKey());

        final PlaybackSession session = currentSession;
        if (currentlyPlaying == music && session != null && session.isActive()) {
            loadLyric(music, session);
        }
    }

    /** 取消钉住，回到「指定来源 / 自动匹配」的正常链路。 */
    public static void clearPinnedLyric() {
        final Music music = currentlyPlaying;
        if (music == null) return;
        if (!LyricManualMemory.has(music.getStableKey())) return;
        LyricManualMemory.clear(music.getStableKey());
        System.out.println("[Music/Manual] 取消钉住歌词 for " + music.getStableKey());
        reloadLyrics(false);
    }

    /** 当前这首歌钉住的那一条；没有钉过返回 {@code null}。 */
    public static LyricManualMemory.Pin getPinnedLyric() {
        Music current = currentlyPlaying;
        return current == null ? null : LyricManualMemory.read(current.getStableKey());
    }

    public static void updateCurrentLyric(float songProgress) {
        LyricLine previousLyric = currentLyric;
        currentLyric = findCurrentLyric(songProgress);

        if (previousLyric != currentLyric) {
            resetLyricPositionUpdate();
        }
    }

    /**
     * 按播放位置查当前行。
     *
     * <p>用户设置的歌词偏移在这里统一生效，因此这个方法是唯一的时钟入口：内部调用、面板布局、
     * 全屏样式都走它。</p>
     */
    public static LyricLine findCurrentLyric(double songProgress) {
        return LyricTimelineSupport.findCurrentLyric(lyrics, haveNoWords, songProgress + lyricOffsetMillis);
    }

    /**
     * 副歌强调量 0..1，供渲染层直接使用。
     *
     * <p>{@code LyricChorusMarker} 是包内私有类，渲染层在别的包里，所以由这个门面转一手——
     * 顺便把「传进去的进度要不要加偏移」这个决定收在一处：这里加，渲染层只管把播放进度递进来。</p>
     */
    public static double chorusEmphasis(LyricLine line, double songProgress) {
        return LyricChorusMarker.emphasis(line, songProgress + lyricOffsetMillis);
    }

    public static void resetLyricPositionUpdate() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();
        });
    }

    public static void resetLyricStatus() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();

            for (LyricLine.Word word : l.words) {
                Arrays.fill(word.emphasizes, 0);
            }

            l.markDirty();
        });
    }

    public static void setLyricsProgress(float progress) {
        if (lyrics.isEmpty()) return;

        try {
            resetLyricDisplayStates();
            updateCurrentLyric(progress);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void resetLyricDisplayStates() {
        resetAllLyricsState();
        resetWordStates();
    }

    private static void resetAllLyricsState() {
        for (LyricLine lyric : lyrics) {
            lyric.scrollWidth = 0;
            lyric.offsetX = 0;
            lyric.offsetY = Double.MIN_VALUE;
            lyric.targetOffsetX = 0;
        }
    }

    private static void resetWordStates() {
        for (LyricLine lyric : lyrics) {
            for (LyricLine.Word word : lyric.words) {
                word.alpha = 0.0f;
                word.progress = 0.0;
            }
        }
    }

    public static String getSecondaryLyrics(LyricLine lyricLine) {
        if (hasTransLyrics) {
            return getTranslationOrRomanizationText(lyricLine);
        }

        if (hasRomanization) {
            return getRomanizationTextIfEnabled(lyricLine);
        }

        return "";
    }

    private static String getTranslationOrRomanizationText(LyricLine lyricLine) {
        boolean showRoman = MuoniumPlayerExtension.getInstance().musicLyrics.showRoman.getValue();

        if (!showRoman) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
        }

        if (hasRomanization) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }

        return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
    }

    private static String getRomanizationTextIfEnabled(LyricLine lyricLine) {
        if (MuoniumPlayerExtension.getInstance().musicLyrics.showRoman.getValue()) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }
        return "";
    }

    public static boolean hasSecondaryLyrics() {
        boolean hasAvailableLyrics = hasTransLyrics || hasRomanization;
        boolean showTranslationEnabled = MuoniumPlayerExtension.getInstance().musicLyrics.showTranslation.getValue();
        return hasAvailableLyrics && showTranslationEnabled;
    }

    public static void loadLyric(Music music, PlaybackSession session) {
        loadLyric(music, session, null);
    }

    private static void loadLyric(Music music, PlaybackSession session, File playbackFile) {
        final long songId = music.getId();
        final String trackKey = music.getStableKey();
        final File embeddedLyricFile = music.isCloudSong()
                ? LyricLoadService.resolveEmbeddedLyricFile(playbackFile) : null;

        MultiThreadingUtil.runAsync(() -> {
            User currentProfile = profile;
            long profileId = currentProfile == null ? 0L : currentProfile.getId();
            LyricLoadService.LyricLoadResult result;
            try {
                result = LyricLoadService.load(music, songId, profileId, embeddedLyricFile);
            } catch (Throwable failure) {
                // 必须提交一个结果，哪怕是空的：lyricsMatchCurrentTrack() 用 lyricsTrackKey 判断归属，
                // 这里直接 return 会让归属键永远停在上一首，于是这首歌的歌词区永久空白。
                System.err.println("[Music] lyric load failed for " + trackKey + ": " + failure);
                result = LyricLoadService.LyricLoadResult.empty();
            }

            Music current = currentlyPlaying;
            if (!session.isActive() || current == null || !trackKey.equals(current.getStableKey())
                    || !trackKey.equals(session.trackKey)) {
                return;
            }

            final JsonObject committedJson = result.rawJson;
            final List<LyricLine> committedLyrics = result.lines == null
                    ? Collections.<LyricLine>emptyList() : result.lines;
            session.pendingLyrics = committedLyrics;
            MultiThreadingUtil.runOnMainThread(() -> applyLyricTimeline(session, committedJson, committedLyrics));

            // 这一首的歌词已经落定，顺手把队列里下一首也解析好落盘。弱网下这几秒的提前量，
            // 就是"切歌歌词立刻出现"和"切歌后干等几秒"的区别。
            LyricCacheStore.prefetchNext(playList, curIdx);
        });
    }

    /**
     * 轮询扫码结果，直到确认（803）、被中断，或超过轮询上限。
     *
     * <p>上限是有意的防线：以前是纯 {@code while(true)}，唯一出口是 interrupt——一旦调用方
     * （LoginRenderer）不再持有这条线程的引用，它就会以 3 秒一次的频率永久打接口。虽然当前
     * 代码树里登录流程走的是账号浮层，这里仍是公开 API，所以按<b>总轮询次数</b>计上限
     * （200 轮 × 3 秒 ≈ 10 分钟；key 过期换新也照常计入），超时返回空串，由调用方按「未登录」处理。</p>
     */
    public static String qrCodeLogin() {
        String key = CloudMusic.qrKey();

        QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);

        int totalPolls = 0;
        final int maxTotalPolls = 200;

        while (true) {

            if (Thread.currentThread().isInterrupted()) {
                return "";
            }

            if (++totalPolls > maxTotalPolls) {
                System.out.println("[MuoniumPlayer] 扫码登录轮询超时，放弃本次登录");
                return "";
            }

            JsonObject json = CloudMusicApi.loginQrCheck(key).toJsonObject();

            int code = json.get("code").getAsInt();
            if (code == 800) {
                key = CloudMusic.qrKey();

                QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);
            }

            if (code == 802) {
                if (json.has("nickname")) {
                    NCMScreen.getInstance().loginRenderer.tempUsername = json.get("nickname").getAsString();
                }

                if (json.has("avatarUrl")) {
                    String url = json.get("avatarUrl").getAsString();

                    if (!NCMScreen.getInstance().loginRenderer.avatarLoaded) {
                        NCMScreen.getInstance().loginRenderer.avatarLoaded = true;
                        MultiThreadingUtil.runAsync(() -> {
                            try (InputStream is = HttpUtils.get(url, null)) {
                                BufferedImage img = DynamicTexture.readImage(is);

                                Textures.loadTextureAsyncly(NCMScreen.getInstance().loginRenderer.tempAvatar, img);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }
            }

            if (code == 803) {

                String cookie = json.get("cookie").getAsString();

                String[] split = cookie.split(";");
                StringBuilder sb = new StringBuilder();
                for (String s : split) {
                    if (s.contains("MUSIC_U") || s.contains("__csrf")) {
                        sb.append(s).append("; ");
                    }
                }

                return sb.substring(0, sb.length() - 2);
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                // 中断即退出：恢复中断位让调用方感知，返回空串按「未登录」收尾。
                Thread.currentThread().interrupt();
                return "";
            }
        }
    }

    public static User getUserProfile() {
        return NeteaseAccountRepository.getUserProfile();
    }

    public static List<Music> search(String keyWord) {
        List<Music> cadenceResults = NeteaseMusicService.search(keyWord, 50);
        MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
        // 只有网易云才有"原 API 兜底"这条退路。这里按"不是网易云就直接返回"来判定，而不是把非网易云的
        // 平台一个个列出来 —— 漏掉一个（酷狗就差点漏掉）就会让该平台的空搜索结果偷偷落到网易云搜索上。
        if (!cadenceResults.isEmpty() || platform != MusicPlatform.NETEASE) {
            return cadenceResults;
        }

        return NeteaseAccountRepository.searchSongs(keyWord);
    }

    public static List<Long> likeList() {
        return NeteaseAccountRepository.loadLikeList(profile);
    }

    public static String qrKey() {
        return NeteaseAccountRepository.qrKey();
    }

}
