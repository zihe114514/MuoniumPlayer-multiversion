package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import today.opai.api.enums.EnumChatColor;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
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
     * automix 交接期间正在淡出的那一路 deck。它已经不是活动播放器，但
     * 仍然听得见，所以音量变化也必须送到它那里。
     */
    static volatile AudioPlayer fadingOutPlayer;
    private static final Object PLAYER_STATE_LOCK = new Object();
    private static final long PLAY_THREAD_JOIN_TIMEOUT_MS = 1000L;
    // 当前播放列表
    public static List<Music> playList = new ArrayList<>();
    public static int curIdx = 0;
    public static volatile Music currentlyPlaying;
    /**
     * 启动当前队列的那张真正的网易云歌单；搜索结果/临时列表时为 null。
     */
    public static volatile PlayList currentPlaylistContext;
    public static Thread playThread;
    /** 当前曲目在 URL 解析、下载、解码或播放器初始化失败后置为 true。 */
    private static volatile boolean awaitingPlaybackAction;

    public static volatile User profile;
    public static volatile List<PlayList> playLists;
    public static volatile List<Long> likeList;
    /**
     * 来自 /api/v1/cloud/get 的 ID，用来在列表里凡出现同一首歌的地方把它们标成同一首。
     */
    private static volatile Set<Long> userCloudSongIds = Collections.emptySet();

    /**
     * 在上一次账号刷新还在进行时，防止重复发起刷新请求。
     */
    private static final AtomicBoolean NETEASE_REFRESHING = new AtomicBoolean(false);

    public static volatile PlayMode playMode = PlayMode.Sequential;
    /**
     * 当前队列是否属于隔离的私人 FM 会话。
     */
    private static volatile boolean personalFmActive;
    private static volatile PlayMode playModeBeforePersonalFm = PlayMode.Sequential;

    public static Quality quality = Quality.LOSSLESS;

    public static final List<LyricLine> lyrics = new CopyOnWriteArrayList<>();
    public static volatile LyricLine currentLyric = null;
    /**
     * 当前 {@link #lyrics} 所属曲目的稳定键；尚未提交任何内容时为 {@code null}。
     *
     * <p>歌词列表是全局的，只有加载成功时才替换，所以一次加载失败后它仍握着<em>上一首</em>曲目的时间轴。
     * 凡要从这些时间戳下结论的地方——automix 靠最后唱到的那行选混音退出点——
     * 都必须知道这是谁的歌词，否则会悄无声息地把一首曲子的结构套到另一首上。</p>
     * 
     */
    public static volatile String lyricsTrackKey = null;

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
     * 尝试抢占唯一的网易云刷新名额。
     */
    public static boolean beginNeteaseRefresh() {
        return NETEASE_REFRESHING.compareAndSet(false, true);
    }

    public static void endNeteaseRefresh() {
        NETEASE_REFRESHING.set(false);
    }

    /**
     * 重新加载已登录的网易云账号数据，但不触发初次登录的副作用。
     * 网络工作在调用方线程上进行。对外发布的快照只在所有请求都成功完成后才替换，
     * 因此短暂的失败会保留旧数据。
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
     * 返回某首网易云歌曲是否存在于已登录用户的网盘中。
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
        // 私人 FM 拥有自己的队列顺序；普通模式不去改动它。
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
    // 但它能用
    public static volatile boolean dontAdd = false;

    public static void prev() {
        if (MpPartyClient.interceptPrev()) return;
        // 私人 FM 没有"上一首"。原先的 curIdx <= 0 在实践中等于永远拦住：FM 队列以前每首歌都是一条
        // 独立的单曲队列。现在预备下一首会让队列真的长出历史，如果不改成无条件拦住，FM 就凭空多出
        // 一个官方客户端里并不存在的回退按钮。
        if (personalFmActive) return;
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
        if (MpPartyClient.interceptNext()) return;
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
    // 主流播放器允许用户把几首歌钉在播放列表原本会播的下一首之前，播完再回到播放列表。
    // 这恰好就是对活队列的一次插入：选中的曲目紧接当前曲之后、按选中顺序排着，
    // 队列和播放模式的其他一切都不变。
    // {@link #playList} 本来就是来源列表的一份私有副本（见 startPlaybackList），
    // 所以用户正在浏览的播放列表绝不会被动到，从没用过这个控件的用户得到与旧行为逐字节一致。
    // 

    /** 守护插入，免得快速点两下交错成错误的顺序。 */
    private static final Object PLAY_NEXT_LOCK = new Object();
    /**
     * 通过 {@link #playNext(Music)} 排队、且尚未播到的那最后一首的绝对下标。
     * 这个块会自行愈合：一旦播放越过尾部，值就重新掉到 {@link #curIdx} 之下，
     * 于是下一次插入会在当前曲之后开一个新块。
     */
    private static volatile int playNextTail = -1;
    /**
     * 每次队列变化时递增。automix 会预解码它预测的下一首，
     * 所以在用户改主意之前预备好的那一路 deck 必须丢掉。
     */
    static volatile long queueRevision;

    /** 用户排队的曲目是否还在等待播放。 */
    static boolean hasQueuedNext() {
        int tail = playNextTail;
        return tail > curIdx && tail < playList.size();
    }

    /** 还在等待的用户排队曲目数量；用户队列为空时为 0。 */
    public static int getQueuedNextCount() {
        int tail = playNextTail;
        int current = curIdx;
        if (tail <= current || tail >= playList.size()) return 0;
        return tail - current;
    }

    /** 这首曲目是否是当前用户队列中正在等待的曲目之一。 */
    public static boolean isQueuedNext(Music song) {
        if (song == null) return false;
        List<Music> queue = playList;
        int tail = playNextTail;
        int current = curIdx;
        if (queue == null || tail <= current || tail >= queue.size()) return false;
        for (int index = Math.max(0, current + 1); index <= tail; index++) {
            Music queued = queue.get(index);
            if (queued != null && queued.equals(song)) return true;
        }
        return false;
    }

    /**
     * 把 {@code song} 排队到当前曲之后播放，排在所有已经这样排队的曲目之后。
     *
     * @return 这首是否被排队；没有可插入的队列时则直接起播
     */
    public static boolean playNext(Music song) {
        if (song == null) return false;
        if (MpPartyClient.interceptPlayNext(song)) return true;

        List<Music> queue = playList;
        if (queue == null || queue.isEmpty() || currentlyPlaying == null || curIdx < 0) {
            // 没有东西在播放，因此没有「下一首」可插入。这里直接起播这首，
            // 是主流播放器的做法，比悄无声息什么都不做更有用。
            play(Collections.singletonList(song), 0);
            DownloadDynamicIsland.showQueuedNextStarted(song.getName());
            return true;
        }

        int pending;
        synchronized (PLAY_NEXT_LOCK) {
            int current = Math.max(0, curIdx);
            int tail = playNextTail;
            int insertAt = (tail > current && tail < queue.size()) ? tail + 1 : current + 1;
            if (insertAt > queue.size()) insertAt = queue.size();
            queue.add(insertAt, song);
            playNextTail = insertAt;
            queueRevision++;
            pending = Math.max(1, insertAt - current);
        }
        DownloadDynamicIsland.showQueuedNext(song.getName(), pending);
        return true;
    }

    /**
     * 当前用户队列里还没播到的曲目，按播放顺序返回快照。给"下一首播放"列表用：界面每帧读一次，
     * 不能拿到会被后台修改的活列表。
     */
    public static List<Music> getQueuedNextSongs() {
        List<Music> queue = playList;
        int tail = playNextTail;
        int current = curIdx;
        if (queue == null || tail <= current || tail >= queue.size()) return Collections.emptyList();
        List<Music> result = new ArrayList<>(Math.max(1, tail - current));
        for (int index = current + 1; index <= tail && index < queue.size(); index++) {
            Music song = queue.get(index);
            if (song != null) result.add(song);
        }
        return result;
    }

    /**
     * 在用户队列内部重新排序：把第 {@code fromOffset} 个待播曲目移动到第 {@code toOffset} 个位置
     * （下标都相对于队列自身，0 就是下一首要播的那个）。
     *
     * <p>只搬动 {@code curIdx} 之后、{@code playNextTail} 之内的元素，所以当前播放位置和队列长度都
     * 不变，正在播的曲目不受影响。整个搬动持有 {@link #PLAY_NEXT_LOCK}，与 {@link #playNext} 的插入
     * 互斥；{@code queueRevision} 递增会让 automix 丢弃按旧顺序预备好的那一路。</p>
     *
     * @return 是否真的发生了移动
     */
    public static boolean moveQueuedNext(int fromOffset, int toOffset) {
        if (fromOffset == toOffset) return false;
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int tail = playNextTail;
            if (queue == null || tail <= current || tail >= queue.size()) return false;

            int size = tail - current;
            if (fromOffset < 0 || fromOffset >= size) return false;
            if (toOffset < 0 || toOffset >= size) return false;

            Music song = queue.remove(current + 1 + fromOffset);
            if (song == null) return false;
            queue.add(current + 1 + toOffset, song);
            queueRevision++;
            return true;
        }
    }

    /**
     * 从用户队列里移除第 {@code offset} 个待播曲目（0 就是下一首要播的那个）。
     *
     * <p>被移除的曲目是当初 {@link #playNext(Music)} 插进来的那一条，所以直接从 {@code playList} 里
     * 删掉就回到了插入前的顺序；队列块因此短一格，{@code playNextTail} 同步下移。正在播放的曲目和
     * {@code curIdx} 都不受影响，队列清空后 {@code playNextTail} 复位为 -1。</p>
     *
     * @return 是否真的移除了一条
     */
    public static boolean removeQueuedNext(int offset) {
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int tail = playNextTail;
            if (queue == null || tail <= current || tail >= queue.size()) return false;

            int size = tail - current;
            if (offset < 0 || offset >= size) return false;

            queue.remove(current + 1 + offset);
            playNextTail = tail - 1 > current ? tail - 1 : -1;
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
            List<Music> queue = playList;
            int current = curIdx;
            int tail = playNextTail;
            if (queue == null || tail <= current || tail >= queue.size()) return 0;

            int removed = 0;
            for (int index = tail; index > current; index--) {
                queue.remove(index);
                removed++;
            }
            playNextTail = -1;
            queueRevision++;
            return removed;
        }
    }

    /** 忘掉用户队列；曲目本身仍留在当初被插入的位置。 */
    private static void clearQueuedNext() {
        playNextTail = -1;
        queueRevision++;
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
     * 返回归一化到 {@code 0.0..1.0} 的持久化播放器音量。
     * mod 自有的 HUD 配置是唯一事实来源，因此这个值
     * 在整个 Minecraft 重启以及切歌之后都能保留。
     */
    public static float getVolume() {
        return clampVolume(HudConfig.playerVolume);
    }

    /**
     * 为两个播放器界面和全局快捷键统一走一条路径来施加音量。
     * 数值按既有的 1% 配置精度量化。
     *
     * @return 持久化的值是否发生了变化
     */
    public static boolean setVolume(float volume, boolean showNotice) {
        float safeVolume = Math.round(clampVolume(volume) * 100.0f) / 100.0f;
        float previousVolume = getVolume();
        if (Math.abs(previousVolume - safeVolume) < .0001f) {
            return false;
        }

        // 独立于宿主的数值管理器持久化。它的数值存储在 Minecraft 关闭时不保证一定写盘，
        // 这在过去会导致下次启动时把滑块重置到 10%。
        // 
        HudConfig.playerVolume = safeVolume;
        HudConfig.save();
        try {
            // 为兼容 HUD 模块以及任何直接读取它的旧集成，保持既有的隐藏 OpenAPI 设置同步。
            // 
            MuoniumPlayerExtension.getInstance().musicInfo.volume.setValue((double) safeVolume);
        } catch (RuntimeException ignored) {
            // 如果模块还没就绪，下面仍会把新值送到活动播放器。
        }

        AudioPlayer activePlayer = player;
        if (activePlayer != null) {
            activePlayer.setVolume(safeVolume);
        }
        // 让仍在淡出的那一路 deck 也跟上，否则淡化的电平会跳一下。
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
     * 按一个归一化的增量调整音量，并报出调整后的百分比。
     */
    public static boolean adjustVolume(float delta) {
        return setVolume(getVolume() + delta, true);
    }

    private static float clampVolume(float volume) {
        if (Float.isNaN(volume) || Float.isInfinite(volume)) {
            return .10f;
        }
        return Math.max(0.0f, Math.min(1.0f, volume));
    }

    /**
     * 把当前曲目相对移动一段，并立即让两个歌词渲染器对齐音频播放器的真实时钟。
     * 把这一步放在播放层，能避免每个界面各自实现一条略有出入的定位路径。
     * 
     * 
     */
    public static boolean seekByMillis(float deltaMillis) {
        if (MpPartyClient.interceptSeekBy(deltaMillis)) return true;
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
        return true;
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

    /**
     * 切换当前播放的暂停/继续。播放器界面的空格键与全局快捷键共用这一条路径，避免两处各写一份判空
     * 逻辑之后行为慢慢分叉；正在进行的无缝淡化由 {@code driveAutomixFade} 一并挂起，这里不必特殊处理。
     *
     * @return 本次调用的实际结果；没有可操作的播放时返回 {@link PlayPauseResult#UNAVAILABLE}
     */
    public static PlayPauseResult togglePlayPause() {
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

    /** 只在一次曲目失败之后、用户显式按下一首/上一首时才重启队列。 */
    private static void restartFromUserTrackSelection() {
        awaitingPlaybackAction = false;
        startPlaybackList(playList, curIdx);
    }

    /**
     * 播放来源, 用于记录播放时长
     */
    public static PlayList playedFrom = null;

    /**
     * 从搜索结果起播：只把用户点的那一首装进队列，随后用刷新过的"最近播放"续上后面的曲目。
     *
     * <p>搜索结果不是歌单，顺着它一路往下播是搜索关键词的排序，不是用户在听的东西。主流播放器在这
     * 里接的都是用户自己的播放上下文，本项目最接近的就是网易云的"最近播放"。</p>
     *
     * <p>刻意先起播、再异步补队列：拉一次最近播放要走一次网络，让点击等它回来会有几百毫秒的"点了
     * 没反应"。补队列时会校验队列还是刚装进去的那一条，用户在这段时间里点了别的歌或者用了"下一首
     * 播放"就直接放弃追加。最近播放为空（未登录 / Cookie 失效 / 接口变更）时退回用搜索结果本身续，
     * 也就是旧行为。</p>
     *
     * @param selected      用户点的那一首
     * @param searchResults 当前搜索结果，作为最近播放不可用时的兜底队列
     * @param selectedIndex {@code selected} 在搜索结果里的下标，仅在完全无法起播时使用
     */
    public static void playFromSearchSelection(Music selected, List<Music> searchResults, int selectedIndex) {
        if (selected == null) {
            play(searchResults, selectedIndex);
            return;
        }

        // 搜索结果不是歌单，播放时长上报没有来源歌单可归属，与旧行为一致。
        currentPlaylistContext = null;
        play(Collections.singletonList(selected), 0);

        final List<Music> installedQueue = playList;
        final List<Music> fallback = searchResults == null
                ? Collections.<Music>emptyList() : new ArrayList<>(searchResults);
        MultiThreadingUtil.runAsync(() -> {
            List<Music> recent = NeteaseRecentPlaysService.fetchRecentSongs(
                    NeteaseRecentPlaysService.MAX_RECENT_SONGS);
            final boolean usedRecent = !recent.isEmpty();
            final List<Music> queue = NeteaseRecentPlaysService.buildQueue(selected,
                    usedRecent ? recent : fallback);
            MultiThreadingUtil.runOnMainThread(() ->
                    appendSearchSelectionTail(installedQueue, selected, queue, usedRecent));
        });
    }

    /**
     * 把 {@link #playFromSearchSelection} 拉到的队列尾部接到活队列上。必须在主线程调用。
     */
    private static void appendSearchSelectionTail(List<Music> installedQueue, Music selected,
                                                  List<Music> queue, boolean usedRecent) {
        if (installedQueue == null || selected == null || queue == null || queue.size() <= 1) return;

        int appended;
        synchronized (PLAY_NEXT_LOCK) {
            // 队列必须还是当初装进去的那一条实例，并且仍然只有那一首：用户在这几百毫秒里换了歌、
            // 换了歌单、或者用"下一首播放"插了曲目，都不能再往里追加。
            if (playList != installedQueue || installedQueue.size() != 1) return;
            Music head = installedQueue.get(0);
            if (head == null || !head.equals(selected)) return;

            List<Music> tail = queue.subList(1, queue.size());
            installedQueue.addAll(tail);
            appended = tail.size();
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
     * 不起用普通播放列表模式，直接起播一小批私人 FM。
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

        // 一起听房间里传输控制权归服务端：本地起播改成点歌，由服务端安排给全房同时播。
        // 房间自己下发的那一首会被放行（MpPlayback 起播时带着身份标记）。
        if (MpPartyClient.interceptStartPlayback(songs, startIdx)) {
            return;
        }

        // 旧队列即将被整条换掉，任何在途的 FM 预备结果都不再属于它。
        PersonalFmManager.clearPrefetchState();

        // 深拷贝一份以避免打乱时影响来源列表；FM 会话始终保持接口返回顺序。
        List<Music> safeSongList = new ArrayList<>(songs);
        stopExistingPlayThread();

        if (!personalFmActive && playMode == PlayMode.Random) {
            startIdx = handleRandomPlayMode(safeSongList, startIdx);
        }

        startIdx = normalizeStartIndex(startIdx);
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
        private final List<Music> songs;
        private final int startIdx;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private PlaybackSession session;
        private volatile AudioPlayer ownedPlayer;
        /** 已解码完成、在空闲 deck 上静静等待的下一首。 */
        private volatile ArmedTrack armed;
        /** 后台预备任务运行期间为 true，保证同一时刻只有一个预备在飞。 */
        private volatile boolean arming;
        /** 为当前曲目做过的预备次数；短暂失败会重试，但彻底坏掉的就停下。 */
        private volatile int armAttempts;
        /** 下一次预备尝试最早可开始的墙钟时刻，即重试冷却。 */
        private volatile long nextArmAt;
        /** 这条线程最近响应过的队列版本号，好让用户改队列时重置预备。 */
        private volatile long seenQueueRevision = -1L;
        /** 预备好的 deck 接管时由等待循环设置，好让 {@code run()} 跳过一次全新起播。 */
        private volatile boolean automixHandover;
        /**
         * 当用户按的「下一首」应由预备好的 deck 服务时，由 {@link #requestManualSkip()} 设置。
         * 在等待循环的下一 tick 被消费，因此即便请求来自界面线程，交接也始终在播放线程上运行。
         * 
         */
        private volatile boolean manualSkipRequested;
        /** 为当前曲目做过的预取次数；不稳的 FM 响应会重试，但失败就停下。 */
        private volatile int fmPrefetchAttempts;
        /** 下一次私人 FM 预取尝试最早可开始的墙钟时刻。 */
        private volatile long nextFmPrefetchAt;
        private volatile AutomixFade activeFade;

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
                        // 预备好的 deck 已经听得见并持有 curIdx/session/lyrics。无需再启动任何东西；
                        // 只需继续监督现在正在播的这首。
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
                            // 绝不自动跳过失败曲目。让失败的歌保持选中，
                            // 等用户重试它、选上一首/下一首、或另选一首。
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
                        // curIdx、session 和活动播放器都已由交接本身推进过了。
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
                // 正处于淡化中的 deck 不是活动播放器，否则没有别的地方会去释放它。
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
            PlaybackSession activeSession = session;
            if (activeSession != null) activeSession.invalidate();
            interrupt();

            // 先释放 automix 的两路 deck：它们都不是 CloudMusic.player，否则下面那段
            // 会让它们继续运行、继续出声。
            cancelArmedTrack();
            finishAutomixFade();

            synchronized (PLAYER_STATE_LOCK) {
                AudioPlayer activePlayer = ownedPlayer;
                NeteasePlaybackHistoryReporter.finish(activePlayer,
                        NeteasePlaybackHistoryReporter.EndReason.REPLACED);
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

        private boolean playSong(Music song) {
            if (isPlaybackCancelled()) return false;

            // 先使旧 session 失效并生成新 session/generation，绑定本次歌曲。
            session = CloudMusic.beginNewSession(song);
            if (!isSessionUsable(session)) {
                session.invalidate();
                return false;
            }
            currentlyPlaying = song;
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
                handleUnplayableSong(song);
                return false;
            }

            boolean started = initializeAndPlaySong(song, playUrl, session);
            if (started && isSessionUsable(session)) {
                DownloadDynamicIsland.showPlaybackQuality(
                        song.getPlaybackQuality().getDisplayName(), playUrl.getB());
            }
            return started;
        }

        private boolean initializeAndPlaySong(Music song, Tuple<String, String> playUrl, PlaybackSession targetSession) {
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.cancelDownload();

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

            // 第三方搜索响应（GD Studio）不带时长。解码器现在知道真实长度了，
            // 赶在歌词/进度的消费者读到之前把它记下来。
            AudioPlayer startedPlayer = ownedPlayer;
            if (startedPlayer != null) {
                song.applyDecodedDuration((long) startedPlayer.getTotalTimeMillis());
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

                // 10 ms 的监督 tick 兼作交叉淡化时钟：粒度足够做平滑斜坡，
                // 而且本来就绑定在上面的 session/取消检查上。
                driveAutomixFade();
                // 在预备记账之前服务：maybeArmNextTrack 内部的一次队列版本变化会丢掉已预备的 deck，
                // 早到一 tick 的请求绝不能跟着它一起被扔，而应当改走普通切歌。
                // 
                if (tryManualNextHandover(activePlayer)) {
                    automixHandover = true;
                    return;
                }
                // 私人 FM 的下一首得先请求回来才存在，所以排在 arm 之前：预备成功会递增 queueRevision，
                // 紧接着的那次 arm 正好看到长出来的队列并立刻开始预解码。
                maybePrefetchPersonalFm(activePlayer);
                maybeArmNextTrack(activePlayer);
                maybePreRollArmedDeck(activePlayer);
                if (tryAutomixHandover(activePlayer)) {
                    automixHandover = true;
                    return;
                }

                // 放在交接尝试之后检查，好让刚结束的曲目仍能交给已预备的 deck，
                // 而不是退回到「关闭/下载/解码」那段空白。
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

        /** 把最深层的原因暴露出来，好让解码/内存限制被解释清楚而不是被隐藏。 */
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
            NeteasePlaybackHistoryReporter.start(song, currentPlaylistContext, activePlayer);
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

            // CDN 扩展名和 API 上报的类型仅供参考。缓存按下面下载到的字节命名，
            // 这样当 API 上报通用或错误的 MIME 类型时，M4A/AAC 也能安全回退。
            // 
            try {
                return getCachedOrTempFile(url, reportedType, song);
            } catch (RuntimeException primaryFailure) {
                return getValidationFallbackFile(song, url, primaryFailure);
            }
        }

        /**
         * 一个 URL 语法上合法，却可能返回 DASH 视频或其他非音频的 MP4 载荷。
         * 一旦字节级校验否决了这样的主响应，就先按来源换一条合适的流重试，再判这首不可播：
         * 网易云退回标准 MP3 端点，GD Studio 的曲子退回另一条健康的 GD 源。
         * 
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
         * CDN URL 所声称的格式并不总是服务器实际返回的容器。
         * 尤其是有些网易云无损 URL 被观测到返回 FLAC 字节却上报 MP3 类型。
         * SoundFile 按扩展名选解码器，因此缓存扩展名必须跟随下载到的字节，而不是 URL 上报的类型。
         * 
         */
        private File getCachedOrTempFile(String playUrl, String type, Music song) {
            File musicCacheDir = MusicAudioCache.directory();
            if (!musicCacheDir.exists() && !musicCacheDir.mkdirs()) {
                throw new IllegalStateException("Unable to create music cache directory: " + musicCacheDir.getAbsolutePath());
            }

            String cacheKey = song.getStableKey() + "_" + quality.getQuality();
            File cachedMusic = AudioCacheFiles.findCachedAudioFile(musicCacheDir, cacheKey, type);
            if (cachedMusic != null) {
                // 命中也要记一笔：容量淘汰只看 lastModified 的话，一首常听但下载得早的歌
                // 会被当成最冷的缓存删掉。
                MusicAudioCache.touch(cachedMusic);
                return resolvePlayableAudioFile(cachedMusic);
            }

            // 绝不要把只下载了一半的文件暴露给解码器或缓存查找。
            File temporaryMusic = new File(musicCacheDir, cacheKey + ".download");
            if (temporaryMusic.exists() && !temporaryMusic.delete()) {
                throw new IllegalStateException("Unable to replace incomplete music download: " + temporaryMusic.getName());
            }

            downloadMusic(playUrl, temporaryMusic, song);
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

            removeOtherQualityCaches(musicCacheDir, song.getStableKey(), quality.getQuality());
            MusicAudioCache.touch(music);
            // 目录只会因为下载而变大，所以容量兜底只需要挂在这一处；解码边车在下一句产生，
            // 淘汰按组进行，所以这里先执行也不会把边车漏在外面。
            MusicAudioCache.enforceLimitAsync();
            return resolvePlayableAudioFile(music);
        }

        /**
         * JSyn 可直接流式播放 MP3、FLAC 和 WAV。AAC/ADTS 与 ISO-BMFF 载荷会先解码成一份已校验的 WAV 边车。
         * 解码器只接受 AAC 音轨，因此纯视频、DRM 保护或不支持的 MP4 流仍会安全失败。
         * 
         */
        private File resolvePlayableAudioFile(File sourceFile) {
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
            DownloadDynamicIsland.beginTranscode(sourceFile.getName(), decodedFile.getName());
            try {
                AacAudioDecoder.decodeToWav(sourceFile, container, decodedFile,
                        new AacAudioDecoder.ProgressListener() {
                            @Override
                            public void onProgress(double progress) {
                                DownloadDynamicIsland.updateTranscodeProgress(progress);
                            }
                        });
                if (!AudioCacheFiles.isReusableDecodedWav(sourceFile, decodedFile)) {
                    throw new IOException("Decoded AAC cache is invalid: " + sourceFile.getName());
                }
            } catch (Throwable exception) {
                DownloadDynamicIsland.cancelTranscode();
                if (decodedFile.exists()) {
                    decodedFile.delete();
                }
                if (sourceFile.exists() && !sourceFile.delete()) {
                    System.err.println("[NCM] Unable to remove undecodable AAC cache: " + sourceFile.getName());
                }
                throw new IllegalStateException("Unable to decode AAC audio into a playable WAV cache", exception);
            }
            DownloadDynamicIsland.finishTranscode(sourceFile.getName(), decodedFile.getName(),
                    System.currentTimeMillis() - transcodeStartedAt);
            return decodedFile;
        }

        private void discardLegacyDecodedWav(File sourceFile) {
            File decodedFile = AudioCacheFiles.getDecodedWavFile(sourceFile);
            if (decodedFile.exists() && !decodedFile.delete()) {
                System.err.println("[NCM] Unable to remove legacy MP4 decoded cache: " + decodedFile.getName());
            }
        }

        private void removeOtherQualityCaches(File musicCacheDir, String stableKey, String currentQuality) {
            MultiThreadingUtil.runAsync(() -> {
                File[] cacheFiles = musicCacheDir.listFiles();
                if (cacheFiles == null) {
                    return;
                }

                String allQualitiesPrefix = stableKey + "_";
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
        // 原来的顺序是：曲目结束 → 关闭播放器 → sleep(250) → 解析 URL → 下载 → 解码 → 起播。
        // 两首之间是好几秒的静音。automix 把这些活全部提前：
        // 当前曲还在放时，下一首就被解析、解码、在第二路 deck 上静音待命，
        // 到预定时刻两路 deck 交叉淡化。关掉 AutomixSettings 时这些一概不跑，仍用原顺序。
        // 

        /** 越过这个播放位置后，就可以开始预备下一首。 */
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
        /** automix 放弃并改走普通切歌之前，每首歌允许的尝试次数。 */
        private static final int MAX_ARM_ATTEMPTS = 3;
        /** 两次预备之间的冷却，免得一个失败的源被每个 tick 反复敲。 */
        private static final long ARM_RETRY_MILLIS = 20_000L;
        /** 交接触发时出去曲目（几乎）不剩多少时所用的斜坡。 */
        private static final long LATE_FIRE_FADE_MILLIS = 700L;
        /**
         * 用户自己按下「下一首」时所用的斜坡。
         *
         * <p>预定重叠按小节计，可能有好几秒：对听众根本没要求的淡化来说正合适，对按键来说却太慢。
         * 略短于一秒，短到能读成「现在就切了」，又长到仍是一条增益斜坡、而不是接缝处的一声咔。</p>
         * 
         * 
         */
        private static final long MANUAL_SKIP_FADE_MILLIS = 900L;
        /**
         * 在预定交接之前多久、以静音方式启动已预备好的 deck。
         *
         * <p>一路 deck 通过一个小滑动窗口从磁盘流式读取 PCM，窗口由 JSyn 引擎线程回填，
         * 启动它还会给运行中的合成器加单元并重接电路。
         * 在交叉淡化那一刻做这些，等于把一次文件 seek、一次读、一次信号图变更塞进同时还在服务出去 deck 的音频回调——
         * 这正好会在本该无缝的地方听出一个卡顿。
         * 所以 deck 提前以完全静音启动，接缝本身只动增益。</p>
         * 
         */
        private static final long PRE_ROLL_MILLIS = 400L;

        /**
         * 出去曲目可以落后预卷点多远，之后静音的 deck 才算过期。
         * 只有一次刻意的 seek 才会把它挪那么远；普通 tick 抖动只有 10 ms。
         */
        private static final long PRE_ROLL_REWIND_SLACK_MILLIS = 500L;

        /** 下一首，已完整解码、在空闲 deck 上静音等待。 */
        private static final class ArmedTrack {
            private final Music song;
            private final File file;
            private final AudioPlayer deck;
            private final int index;
            /** 这次预备所针对的播放 generation；出现更新的说明这次预备已过期。 */
            private final long generation;
            /** 做出预测时所在的队列版本号；出现更新的说明它预测错了。 */
            private final long queueRevision;
            private volatile AutomixPlan plan;
            /** 实际可用的静音前奏，即 min(PRE_ROLL_MILLIS, plan.incomingCueMillis)。 */
            private volatile long preRollMillis;
            /** 这一路 deck 已经（静音地）出声了，绝不能再 cue 或再启动。 */
            private volatile boolean preRolled;
            /** 因播放本身被暂停而把已预卷的 deck 保持暂停期间为 true。 */
            private volatile boolean preRollPaused;

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
         * 正在进行的一次交叉淡化。由墙钟驱动，免得一次卡顿拉长斜坡；
         * 但播放暂停时把钟按住不动——否则淡化中途暂停会让斜坡在只剩出去 deck 出声时跑完。
         * 
         */
        private static final class AutomixFade {
            private final AudioPlayer outgoing;
            private final AudioPlayer incoming;
            private final AutomixPlan plan;
            /**
             * 实际斜坡长度。通常是 {@code plan.overlapMillis}，但交接触发得晚时会缩短：
             * 出去曲目已经结束了还在四秒里淡入新歌，会让新歌从近乎静音里冒出来。
             * 
             */
            private final long overlapMillis;
            private long startedAt;
            private long tickedAt;
            /** 仅当是这次淡化暂停了出去的 deck 时为 true，免得它被误恢复。 */
            private boolean outgoingPaused;

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
         * 预测 {@link #updateCurIdx()} 会落到的下标，但不触碰任何队列状态。
         * 凡是无法预知下一首时（有待处理的手动切歌、循环结束时的随机重排、还得去取的一批 FM）就返回 -1，
         * 这种情况下 automix 干脆不预备，走普通切歌。
         * 
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
                // 单曲循环会重放这同一首。把它和自己淡化，每次重复都会削掉它的尾，
                // 这与「单曲循环」要的正相反，所以什么都不预备，让它自然放到头再重开。
                // 
                return -1;
            }
            int next = curIdx + 1;
            if (next < queue.size()) return next;
            // 随机模式在队列回绕时会重排，因此这里下一首确实是未知的。
            if (playMode == PlayMode.LoopInList) return 0;
            return -1;
        }

        /**
         * 一次预备只有在播放 generation 仍是当前这一个、且队列仍预测同一首下一首时才有用。
         * 用「下一首播放」排队会改变那个预测，因此版本号也是有效性检查的一部分。
         * 
         */
        private boolean armStillValid(long armGeneration, long armRevision) {
            return !isPlaybackCancelled() && !playListChanged() && !doBreak
                    && generation.get() == armGeneration
                    && CloudMusic.queueRevision == armRevision
                    && AutomixSettings.isEnabled();
        }

        /** 清空预备记账，好让新起播的曲目有自己的尝试次数。 */
        private void resetArmState() {
            armAttempts = 0;
            nextArmAt = 0L;
            // 一个始终没被服务的请求（它的 session 先死了）绝不能对着接下来要起的这首触发：
            // 用户当初要离开的是另一首歌。
            manualSkipRequested = false;
            // 预备下一首同样按"每首歌一份预算"计：交接到新的一首之后要能重新预备。
            fmPrefetchAttempts = 0;
            nextFmPrefetchAt = 0L;
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
         * 开始解析并解码下一首。刻意在当前曲很早期就开始，而不是临结束前几秒：
         * 第三方源可能要先解析、下载、转码，然后才解码，这往往比重叠本身还久。
         * 没能及时备好的预备就不是淡化，所以「早」就是这个功能的全部。
         * 失败的尝试会在冷却之后重试几次，因为一条不稳的 URL 不该让剩下的曲目静默失去淡化。
         * 
         * 
         */
        private void maybeArmNextTrack(AudioPlayer activePlayer) {
            long revision = CloudMusic.queueRevision;
            if (revision != seenQueueRevision) {
                // 用户改了接下来要播什么。之前预备的都是错的那首，
                // 而尝试预算属于旧预测，所以两者都重置。
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
            // 要么曲目已经稳定播放，要么它已临近尾声、预备再等就来不及了。
            // 以先到者为准。
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

        /** 解析、下载、解码并暂存下一首。运行在播放线程之外。 */
        private void armTrack(Music song, int index, long armGeneration, long armRevision) {
            AudioPlayer deck = null;
            try {
                if (!armStillValid(armGeneration, armRevision)) return;

                Tuple<String, String> playUrl = song.getPlayUrl();
                if (playUrl == null || !armStillValid(armGeneration, armRevision)) return;

                File file = getMusicFile(playUrl, song);
                if (file == null || !file.isFile() || !armStillValid(armGeneration, armRevision)) return;

                // 再一路活的 deck 会把常驻 PCM 翻倍，因此超大的曲目绝不预备。
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
                song.applyDecodedDuration((long) deck.getTotalTimeMillis());
                if (!armStillValid(armGeneration, armRevision)) return;

                ArmedTrack track = new ArmedTrack(song, file, deck, index, armGeneration, armRevision);
                track.plan = AutomixPlanner.plan(ownedPlayer, deck,
                        estimateLastVocalEndMillis(ownedPlayer));
                if (track.plan == null || !armStillValid(armGeneration, armRevision)) return;

                // 做一次启动所需的一切（唯独不出声），都在这条预备线程上完成：
                // 定位读头、接上低搁架、把第一个流式窗口从磁盘拉下来。
                track.preRollMillis = Math.min(PRE_ROLL_MILLIS, Math.max(0L, track.plan.incomingCueMillis));
                deck.setBassGainDb(track.plan.incomingBassDb(0.0));
                // 放在 prepareStart 之前，好让处理链直接拿到这两个值而不 slew：已预卷的 deck
                // 早在接缝之前就已经（静音地）在渲染块了，如果闪避到交接时才开始滑入，
                // 会让第一个字先漏过去。
                deck.setVocalDuckDepth(track.plan.incomingVocalDepth(0.0));
                deck.prepareStart(track.plan.incomingCueMillis - track.preRollMillis);
                if (!armStillValid(armGeneration, armRevision)) return;

                armed = track;
                deck = null;   // 所有权已交给已预备的曲目
                System.out.println("[Automix] armed " + song.getName() + " · " + track.plan.summary);
                DownloadDynamicIsland.showAutomixArmed(song.getName());
            } catch (Throwable failure) {
                System.err.println("[Automix] unable to arm " + (song == null ? "next track" : song.getName())
                        + ": " + failure.getMessage());
            } finally {
                if (deck != null) closeQuietly(deck);
                arming = false;
            }
        }

        /**
         * 当前播放曲目最后一行歌词的末尾位置；没有带时间轴的歌词时为 {@code -1}。
         * 在最后一个字之后再混音退出，正是防止两套人声撞在一起的做法。
         */
        private long estimateLastVocalEndMillis(AudioPlayer outgoing) {
            try {
                Music current = currentlyPlaying;
                if (current == null) return -1L;
                // 歌词列表在一次失败加载后仍保留，因此它可能仍在描述上一首。
                // 用那些时间戳会按错误歌曲的结构来定混音退出点。
                String owner = CloudMusic.lyricsTrackKey;
                if (owner == null || !owner.equals(current.getStableKey())) return -1L;

                List<LyricLine> lines = CloudMusic.lyrics;
                if (lines == null || lines.isEmpty()) return -1L;
                for (int index = lines.size() - 1; index >= 0; index--) {
                    LyricLine line = lines.get(index);
                    if (line == null || line.lyric == null || line.lyric.trim().isEmpty()) continue;
                    // 普通 LRC 行不带时长；假定是一小句唱词。
                    long span = line.duration > 0L ? line.duration : 2_500L;
                    long end = line.timestamp + span;
                    if (end <= 0L) return -1L;
                    // 超出音频末尾的歌词属于这首歌的另一个版本。
                    long total = outgoing == null ? 0L : (long) outgoing.getTotalTimeMillis();
                    if (total > 0L && end > total) return -1L;
                    return end;
                }
                return -1L;
            } catch (Throwable ignored) {
                // 歌词由加载器并发替换；找不到它们不算错误。
                return -1L;
            }
        }

        /**
         * 在预定交接前不久、以完全静音启动已预备的 deck，
         * 这样接缝本身只动增益，不会在两路 deck 都被渲染时再去分配、定位、重接或从磁盘读取。
         * 这复刻了参考实现里 arm/release 分离的做法，并按采样读取引擎做了改造。
         * 
         *
         * <p>静音的 deck 要与暂停状态保持一致：放任它跑，它会在听众已暂停播放时冲过计划对齐到小节线的定位点。</p>
         * 
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
                // 向后 seek 会在这路 deck 仍在跑的同时把交接又推远：
                // 等它最终触发时会从错误位置进歌，或已经静音地放完了。
                // 把它放回定位点，稍后再重新预卷。
                long current = (long) activePlayer.getCurrentTimeMillis();
                if (deck.isFinished() || current + PRE_ROLL_REWIND_SLACK_MILLIS
                        < track.plan.fireMillis - track.preRollMillis) {
                    rearmPreRoll(track, deck);
                }
                return;
            }
            if (playbackPaused || !deck.isPrepared()) return;
            // 没有可预卷的前奏：进来的曲目从（或几乎从）自己的开头进入，
            // 所以提前跑它会吞掉歌曲开头那几秒。它仍会被预热后启动。
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

        /** 把已预卷的 deck 在其定位点处重新静音，以便稍后再预卷。 */
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
         * 用户按的「下一首」能否由已预备的 deck 服务，而不走普通的关闭/解析/下载/解码切歌。
         * 从界面线程调用，因此它只查看状态并置一个标记——这里不触碰 {@code player}、{@code session} 或队列。
         * 
         *
         * @return 请求是否被接受；{@code false} 表示调用方必须走普通切歌
         */
        boolean requestManualSkip() {
            if (doBreak || isPlaybackCancelled() || !isSessionUsable(session)) return false;
            // 已经请求过了；待处理的请求会在下一 tick 被服务。
            if (manualSkipRequested) return false;
            // 一次淡化仍在进行。现在把第三路 deck 提上来，会让两首曲子对着新曲一起出声，
            // 所以这次切歌只能走普通的硬切。
            if (activeFade != null) return false;

            AudioPlayer active = ownedPlayer;
            if (active == null || !active.isUsable() || active.isFinished() || active.isPausing()) return false;

            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null || !track.deck.isUsable()) return false;
            if (!armStillValid(track.generation, track.queueRevision)) return false;
            // 已解码的 deck 必须正好是队列此刻预测的那首，
            // 否则手动「下一首」会落到一首与普通切歌本该播放的不同的歌上。
            if (track.index != peekNextIndex()) return false;

            manualSkipRequested = true;
            return true;
        }

        /**
         * 服务一个待处理的手动切歌。与自动路径不同，它忽略 {@code plan.fireMillis}：
         * 用户要求现在就离开当前曲，因此接缝在这一 tick 以一条短斜坡发生，而不是在预定的小节线上。
         * 
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
                // 从请求发出到现在的几毫秒里，那路 deck 已经没了。用户毕竟按了「下一首」，
                // 所以退到普通切歌，而不是什么都不做。
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
         * 原来的手动切歌路径，在无缝路径用不了时从播放线程运行。
         * 与没有任何预备时 {@link CloudMusic#next()} 的做法完全相同：给出去曲目记账、把下标标记为被播放器控件改动、
         * 然后关闭播放器，好让监督循环起下一首。
         * 
         */
        private void performOrdinaryNextAfterFailedHandover() {
            if (doBreak || isPlaybackCancelled() || !isSessionUsable(session)) return;
            if (playList.isEmpty() || player == null || !canPlayNext()) return;
            updatePlayCountIfNeeded();
            prepareForTrackChange();
            curIdx++;
            stopCurrentPlayback();
        }

        /**
         * 出去曲目到达预定点时触发交接；若它已经结束就立即触发（预备得晚也比一段静音强）。
         * 
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
            // 暂停的曲目绝不能交接：进来的 deck 会自己开始出声，
            // 而界面还显示播放处于暂停。
            if (!ended && activePlayer.isPausing()) return false;
            if (!ended && position < track.plan.fireMillis) return false;
            return performAutomixHandover(activePlayer, track);
        }

        /**
         * 把已预备的 deck 提升为活动播放器：出去曲目在淡化时继续出声，
         * 进来的那一路立刻接管队列下标、session、歌词和历史上报。
         */
        private boolean performAutomixHandover(AudioPlayer outgoing, ArmedTrack track) {
            return performAutomixHandover(outgoing, track, 0L);
        }

        /**
         * @param overlapOverrideMillis 改用这条斜坡代替预定重叠，传 {@code 0} 则保留计划自身的长度。
         * 用户按的「下一首」会覆盖它：预定淡化按小节计，用来响应一次按键会显得拖沓。
         * 
         * 
         */
        private boolean performAutomixHandover(AudioPlayer outgoing, ArmedTrack track,
                                               long overlapOverrideMillis) {
            armed = null;
            AutomixPlan plan = track.plan;
            AudioPlayer incoming = track.deck;
            if (incoming == null || !incoming.isUsable()) {
                closeQuietly(incoming);
                return false;
            }

            // 出去曲目实际还剩多少，决定斜坡能有多长。
            // 一个过了预定点才备好的预备——源慢、转码久——
            // 否则会让新歌在四秒里淡入、旁边却没有任何声音，
            // 听着像一段空白后跟一首歌偷偷冒进来，而不是一次淡化。
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
                if (!isSessionUsable(session) || ownedPlayer != outgoing) {
                    closeQuietly(incoming);
                    return false;
                }
                incoming.setVolume(CloudMusic.getVolume());
                // 在 deck 出声之前设置：它直接拿到这个值而不 slew，
                // 这样进来的曲目绝不会在交换生效之前先挨上一记响亮的低音。
                incoming.setBassGainDb(plan.incomingBassDb(0.0));
                // 同理：抵消器会 slew，所以深度必须在渲染第一个块之前就位，
                // 否则进来的人声会在闪避生效前先溜进一个字。
                incoming.setVocalDuckDepth(plan.incomingVocalDepth(0.0));
                incoming.setMidGainDb(plan.incomingMidDb(0.0));
                incoming.setHighGainDb(plan.incomingHighDb(0.0));
                incoming.setFadeGain(plan.incomingGain(0.0));
                try {
                    if (track.preRolled) {
                        // 从预卷起就以完全静音出声了：上面那行增益就是整个交接，
                        // 这正是接缝听不见的原因。
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

                // 在这里记账，而不是在 deck 启动之前：上面每个提前 return 都让出去曲目继续播放，
                // 若走普通完成路径就会把它再上报和计数一次。
                // 
                try {
                    NeteasePlaybackHistoryReporter.finish(outgoing,
                            NeteasePlaybackHistoryReporter.EndReason.COMPLETED);
                } catch (Throwable ignored) {
                }
                if (!dontAdd && playedFrom != null && curIdx >= 0 && curIdx < playList.size()) {
                    try {
                        playList.get(curIdx).updPlayCount(playedFrom, outgoing.getCurrentTimeSeconds());
                    } catch (Throwable ignored) {
                    }
                }

                curIdx = track.index;
                currentlyPlaying = track.song;
                nextSession = CloudMusic.beginNewSession(track.song);
                session = nextSession;
                nextSession.player = incoming;
                nextSession.audioActive = true;
                player = incoming;
                ownedPlayer = incoming;
                fadingOutPlayer = outgoing;
                activeFade = new AutomixFade(outgoing, incoming, plan, effectiveOverlap,
                        System.currentTimeMillis());
                playing.set(true);
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
            NeteasePlaybackHistoryReporter.start(track.song, currentPlaylistContext, incoming);
            if (AutomixSettings.isTransitionAnimationEnabled() && effectiveOverlap >= 5_000L) {
                if (AutomixSettings.isFoliaEngine() && plan.style != null
                        && plan.style != FoliaTransitionStyle.PLAIN_BLEND) {
                    double crossover = plan.style == FoliaTransitionStyle.BASS_SWAP
                            ? plan.bassHandoverAt
                            : plan.style == FoliaTransitionStyle.TAIL_RIDE ? plan.drumHandoverAt : .5;
                    AutomixTransitionAnimation.start(effectiveOverlap, crossover, plan.animationPeriodMillis);
                } else if (!AutomixSettings.isFoliaEngine()) {
                    DownloadDynamicIsland.showAutomixHandover(track.song.getName());
                }
            }
            // 无缝交接同样是"一首 FM 曲目开始出声"，面板与预备标记都要跟着走。
            if (personalFmActive) PersonalFmManager.noteFmTrackStarted(track.song);
            return true;
        }

        /** 推进等功率斜坡。从监督播放的同一个 10 ms tick 调用。 */
        private void driveAutomixFade() {
            AutomixFade fade = activeFade;
            if (fade == null) return;

            long now = System.currentTimeMillis();
            AudioPlayer active = ownedPlayer;
            boolean paused = active != null && active.isUsable() && !active.isFinished() && active.isPausing();
            if (paused) {
                AutomixTransitionAnimation.setPaused(true);
                // 按住斜坡而不让墙钟越过暂停一直跑，并把出去的 deck 和活动的那一路一起压下去——
                // 播放器里没有别的地方知道它的存在。
                fade.startedAt += Math.max(0L, now - fade.tickedAt);
                fade.tickedAt = now;
                if (!fade.outgoingPaused && fade.outgoing != null && fade.outgoing.isUsable()
                        && !fade.outgoing.isPausing()) {
                    fade.outgoing.pause();
                    fade.outgoingPaused = true;
                }
                return;
            }
            AutomixTransitionAnimation.setPaused(false);
            if (fade.outgoingPaused && fade.outgoing != null) {
                // 只在是这次淡化暂停了它时才恢复：unpause() 会把自己停下来的 deck 从它被 cue 的位置重新起播，
                // 那会把出去的曲目从头再放一遍。
                // 
                fade.outgoing.unpause();
                fade.outgoingPaused = false;
            }
            fade.tickedAt = now;

            double progress = (now - fade.startedAt) / (double) fade.overlapMillis;
            if (fade.incoming != null) {
                fade.incoming.setFadeGain(fade.plan.incomingGain(progress));
                fade.incoming.setBassGainDb(fade.plan.incomingBassDb(progress));
                fade.incoming.setVocalDuckDepth(fade.plan.incomingVocalDepth(progress));
                fade.incoming.setMidGainDb(fade.plan.incomingMidDb(progress));
                fade.incoming.setHighGainDb(fade.plan.incomingHighDb(progress));
            }
            if (fade.outgoing != null) {
                fade.outgoing.setFadeGain(fade.plan.outgoingGain(progress));
                fade.outgoing.setBassGainDb(fade.plan.outgoingBassDb(progress));
                // 按实际正在跑的斜坡来算，而不是计划的那条：缩短的斜坡
                // 绝不能把一个半音的节奏锁定压缩进几百毫秒。
                // 速度与音高分开给：节奏锁定只动速度（WSOLA，音高不变），整半音吸附只动音高
                // （重采样 + WSOLA 把时长还原）。合成一个数会让 WSOLA 把「移调」当成「变速」。
                fade.outgoing.setSpeedAndPitch(
                        fade.plan.outgoingTempo(progress, fade.overlapMillis),
                        fade.plan.outgoingPitch(progress, fade.overlapMillis));
            }
            if (progress < 1.0) return;

            activeFade = null;
            if (fade.incoming != null) {
                fade.incoming.setFadeGain(1.0f);
                // 剩余曲目恢复平直响应与自然速度：淡化的任何痕迹都不能带进普通播放。
                // 
                fade.incoming.resetAutomixProcessing();
            }
            releaseFadedDeck(fade.outgoing);
        }

        /** 提前结束一次淡化（取消、手动切歌），且不让任何一路 deck 仍听得见。 */
        private void finishAutomixFade() {
            AutomixTransitionAnimation.cancel();
            AutomixFade fade = activeFade;
            activeFade = null;
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
                // 被一次与本次淡化竞态的切歌提升为活动播放器：不该由我们来关。
                if (deck == ownedPlayer || deck == CloudMusic.player) {
                    // 但它必须被拉平，否则剩下的曲目里它会一直带着淡化的低音切除和走调速度播放。
                    // 
                    deck.resetAutomixProcessing();
                    return;
                }
            }
            closeQuietly(deck);
        }

        /** 丢弃一次从未触发的预备，释放它解码好的采样。 */
        private void cancelArmedTrack() {
            ArmedTrack track = armed;
            armed = null;
            // 没有可交接的对象了，因此一个待处理的手动请求必须退到普通切歌，
            // 而不是等一路已经不存在的 deck。
            manualSkipRequested = false;
            if (track == null || track.deck == null) return;
            if (track.deck == ownedPlayer || track.deck == CloudMusic.player) return;
            closeQuietly(track.deck);
        }

        private void closeQuietly(AudioPlayer deck) {
            if (deck == null) return;
            try {
                deck.close();
            } catch (Throwable ignored) {
                // 一路从未完全初始化的 deck 绝不能挡住切歌。
            }
        }

        private void loadMusicCover(Music song) {
            CloudMusic.loadMusicCover(song);
        }

        private void updateCurIdx() {
            if (personalFmActive) {
                // FM 批次是顺序的，绝不循环、重排或重复。
                if (dontAdd) dontAdd = false;
                else curIdx++;
                return;
            }
            if (playMode == PlayMode.LoopSingle) {
                // 单曲循环只影响自然播放结束；用户手动上一首/下一首仍可正常切歌。
                if (dontAdd) {
                    dontAdd = false;
                } else if (hasQueuedNext()) {
                    // 用户明确排队的曲目优先于单曲循环，且只生效一次：队列被消费掉，
                    // 之后循环在落到这里的下一首上继续。
                    curIdx++;
                }

                if (curIdx < 0 || curIdx >= playList.size()) {
                    curIdx = 0;
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
                }
                return;
            }

            if (playMode == PlayMode.LoopInList && curIdx >= playList.size()) {
                curIdx = 0;
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
                song != null && song.isYouTube() ? YouTubeProxySettings.createHttpProxy() : null);
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
        AudioPlayer p = player;
        Music m = currentlyPlaying;
        PlaybackSession s = currentSession;
        long sid = s == null ? -1L : s.sessionId;
        long songId = m == null ? -1L : m.getId();
        long rawPositionMs = (p == null || m == null) ? 0L : Math.round(p.getCurrentTimeMillis());
        long durationMs = (p == null || m == null) ? 0L : Math.round(p.getTotalTimeMillis());
        boolean playing = (p != null && m != null) && !p.isFinished();
        long positionMs = (p == null || m == null)
                ? 0L
                : smoothPositionMs(songId, rawPositionMs, durationMs, p.isPausing());
        return new PlaybackSnapshot(sid, songId, positionMs, durationMs, playing, p, m);
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
     */
    public static void reloadLyrics() {
        final Music music = currentlyPlaying;
        final PlaybackSession session = currentSession;
        if (music == null || session == null || !session.isActive()) return;

        int source = LyricSourceMemory.read(music.getStableKey());
        System.out.println("[Music] 重取歌词: " + music.getStableKey()
                + "（来源 " + LyricSourceMemory.displayName(source) + "）");
        LyricLoadService.invalidateAll(music);
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
     */
    public static void setLyricSource(int source) {
        final Music music = currentlyPlaying;
        if (music == null) return;
        LyricSourceMemory.write(music.getStableKey(), LyricSourceMemory.clamp(source));
        reloadLyrics();
    }

    /** 在可选来源之间绕圈，{@code forward=false} 时往前一档。 */
    public static void cycleLyricSource(boolean forward) {
        int current = getLyricSource();
        setLyricSource(forward ? LyricSourceMemory.next(current) : LyricSourceMemory.previous(current));
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

    public static String qrCodeLogin() {
        String key = CloudMusic.qrKey();

        QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);

        while (true) {

            if (Thread.currentThread().isInterrupted()) {
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
                throw new RuntimeException(e);
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
