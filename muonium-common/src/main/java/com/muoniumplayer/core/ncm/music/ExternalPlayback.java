package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「系统播放器镜像」这一模式的总控。
 *
 * <p>它做的事情只有一件：把外部播放器正在播的那首歌，写成 {@link CloudMusic} 里那三个
 * 全局字段（{@code currentlyPlaying} / {@code currentSession} / 歌词时间轴），并且只从
 * {@link CloudMusic#getSnapshot()} 这一个时钟入口分叉一次。之所以这样接而不是另建一套
 * 「外部播放状态」抽象：本模组的播放态消费点是散着的（曲名 80 处、歌词 64 处、当前行 55 处），
 * 新开一条通路意味着那十几处界面全都要各写一份分支；而反过来只要写出一个真的
 * {@link Music} 和一条合法会话，桌面歌词、全屏歌词页、灵动岛、封面三级优先链、
 * 手动选词、歌词偏移、重取歌词这些能力是一次不改就全部可用的。</p>
 *
 * <p>反过来，它也是唯一一个会把内部播放挡在外面的地方：镜像开着的时候 {@link CloudMusic}
 * 起播会被 {@link #refuseInternalPlayback()} 拒掉并说明原因，一个系统里同时只有
 * 一个"正在播放"是真的。</p>
 */
public final class ExternalPlayback {

    /** 换歌解析期间与已接管键；都只在主线程读写，跨线程的只有下面那个 volatile。 */
    private static volatile boolean mirroring;
    private static volatile ExternalSnapshot raw = ExternalSnapshot.NONE;

    private static String appliedTrackKey = "";
    private static String resolvingTrackKey = "";
    private static final AtomicBoolean RESOLVE_IN_FLIGHT = new AtomicBoolean(false);
    /** 解析完成、等待主线程落地的一次结果。为 null 表示没有待办。 */
    private static volatile PendingResolution pending;

    /**
     * 上一次落地的那首是「曲库里没匹配到」的合成曲目，以及它当时报回来的时长。
     * 只给合成曲目用：真曲目已经带着词与封面，不需要再问一次。
     */
    private static boolean appliedWasSynthetic;
    private static long appliedDurationMs;
    /** 同一首因为时长变化重解析过的次数，见 {@link #needsSettleRetry}。 */
    private static int settleRetries;
    /** 同一首最多重解析几次，再多就是在抖而不是在等元数据落地。 */
    static final int SETTLE_RETRY_LIMIT = 3;
    /** 时长至少差这么多才值得为同一首再解析一次。比一次轮询抖动宽，比「前奏与整首」窄。 */
    static final long SETTLE_DURATION_STEP_MS = 5_000L;

    /**
     * 外部播放器那一格音量。SMTC 没有音量这一层，写的是 Windows 音量合成器里的会话
     * （见 {@link ExternalMediaService#sendVolume(float)}），所以「知道当前值」这件事
     * 也只能由助手的状态行带回来。
     *
     * <p>三个值分开是有原因的：{@code wanted} 是玩家此刻拖到的位置（必须立刻显示，
     * 否则滑块会跟手打架），{@code sent} 是已经交给助手的最后一个值（用来合并拖动，
     * 一帧一条命令会把合成器刷爆），{@code echo} 是助手读回来的真值。</p>
     */
    private static volatile float wantedVolume = Float.NaN;
    private static float sentVolume = Float.NaN;
    private static volatile float echoVolume = Float.NaN;
    private static long volumeSendAtMs;
    private static long volumeDragAtMs;
    /** 「这台播放器没有可调音量」上次播报的时刻，防止每帧刷同一条提示。 */
    private static long noVolumeNoticeAtMs;
    /** 拖动时最多每这么多毫秒往助手写一次；松手前的最后一档会在下一个 tick 补上。 */
    static final long VOLUME_SEND_INTERVAL_MS = 120L;
    /** 刚拖完这么久之内不信助手的读数，避免把「写之前那一刻的旧值」倒灌回滑块。 */
    static final long VOLUME_DRAG_GRACE_MS = 1_200L;

    private static final class PendingResolution {
        final String trackKey;
        final Music music;
        final ExternalSnapshot snapshot;

        PendingResolution(String trackKey, Music music, ExternalSnapshot snapshot) {
            this.trackKey = trackKey;
            this.music = music;
            this.snapshot = snapshot;
        }
    }

    /** 上一次给用户播报过的会话身份，用来做到「只在变化时说一次」。 */
    private static String lastAnnouncedTrackKey = "";
    private static boolean announcedNoSession;

    private ExternalPlayback() {
    }

    /** SMTC 只存在于 Windows。这一条决定设置项是否可用，也决定状态标签说什么。 */
    public static boolean supportsPlatform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Spotify Connect 镜像（{@code SpotifyConnectMirror}）要接管时请 SMTC 让位。
     * 两个镜像写的是同一组全局字段，一个系统里只能有一个在供态——谁后开谁接管。
     */
    public static void forceDetachIfMirroring() {
        if (mirroring) detach("系统播放器镜像让位给 Spotify 设备");
    }

    /** 用户是否选了镜像模式。 */
    public static boolean enabled() {
        return supportsPlatform()
                && HudConfig.HUD_PLAYBACK_FEED_MIRROR.equals(HudConfig.playbackFeed);
    }

    /**
     * 当前是否真的由外部播放器供态。
     *
     * <p>注意这与 {@link #enabled()} 不是一回事：模式开着但系统里没有在播的媒体时，
     * {@code enabled()} 为真而 {@code isMirroring()} 为假，此时内部播放器完全正常可用。
     * 所有"要不要让位给外部"的判断都问这一个。</p>
     */
    public static boolean isMirroring() {
        return mirroring;
    }

    /** 最近一次外部快照。永不为 null；{@link ExternalSnapshot#hasSession()} 为假表示没有会话。 */
    public static ExternalSnapshot rawSnapshot() {
        return raw;
    }

    /** 设置项改完之后调用：要么把助手起起来，要么把接管状态让出去。 */
    public static void syncFromSettings() {
        if (!enabled()) {
            ExternalMediaService.stop();
            detach("已关闭镜像");
            return;
        }
        ExternalMediaService.requestStart("设置项切换");
    }

    /**
     * 主线程每 tick 调用。除了第一次启动助手之外不做任何耗时事。
     *
     * <p>整段包在 try 里：这个位置抛异常会直接崩游戏，而它依赖的是一个外部进程的输出，
     * 外部进程说什么都有可能。</p>
     */
    public static void onClientTick() {
        try {
            // Spotify Connect 镜像接管期间 SMTC 整条路让位：不起助手、不读快照，也不把
            // 已经让出去的接管状态抢回来（用户在设置里还开着镜像模式时，tick 每拍都会
            // 尝试把它拉起来，必须在最前面拦住）。
            if (com.muoniumplayer.core.ncm.spotify.SpotifyConnectMirror.isMirroring()) {
                if (mirroring) detach("系统播放器镜像让位给 Spotify 设备");
                return;
            }
            if (!enabled()) {
                if (mirroring) detach("");
                return;
            }
            ExternalMediaService.requestStart("进世界");
            ExternalSnapshot snapshot = ExternalMediaService.snapshot();
            raw = snapshot;
            // 外部封面每拍问一次：它只是比对文件名 + 装一张已经解好的纹理，没变化时是几次
            // 字符串比较。真正的读盘与解码在后台线程，见 ExternalArtwork。
            ExternalArtwork.onSnapshot(snapshot);
            // 命令回执每 tick 都要取，不能只会在有会话时取：玩家在会话刚结束的那一拍
            // 按了「下一首」，回执会在会话没了之后才回来，那条「外部播放器没响应」就丢了。
            reportCommandResult();
            tickVolume(snapshot);

            PendingResolution ready = pending;
            if (ready != null && ready.trackKey.equals(snapshot.trackKey())) {
                pending = null;
                apply(ready);
            }

            if (!snapshot.hasSession() || !ExternalMediaService.isSnapshotFresh()
                    || snapshot.normalizedTitle().isEmpty()) {
                // 曲名还没报出来的那一拍不算「有一首歌」：有的播放器在换歌瞬间会把元数据清空，
                // 照旧接管就会显示一张既没歌名也没封面的空卡片，比什么都不显示更让人困惑。
                if (mirroring) detach("");
                announceNoSessionOnce();
                return;
            }
            announcedNoSession = false;
            maybeResolve(snapshot);
            reportCommandResult();
        } catch (Throwable failure) {
            System.err.println("[Music/SMTC] 镜像 tick 异常: " + failure);
        }
    }

    /** 换歌时提交一次后台解析；同一时刻最多一条在飞。 */
    private static void maybeResolve(ExternalSnapshot snapshot) {
        String key = snapshot.trackKey();
        boolean sameTrack = key.equals(appliedTrackKey);
        // 同一首默认不再重复解析，只放时长这一种情况过去，见 {@link #needsSettleRetry}。
        if (sameTrack && !needsSettleRetry(snapshot.durationMs())) return;
        if (key.equals(resolvingTrackKey)) return;
        if (!RESOLVE_IN_FLIGHT.compareAndSet(false, true)) return;
        resolvingTrackKey = key;
        final ExternalSnapshot captured = snapshot;
        MultiThreadingUtil.runAsync(() -> {
            Music music = null;
            try {
                music = ExternalTrackMatcher.resolve(captured);
            } catch (Throwable failure) {
                System.err.println("[Music/SMTC] 解析外部曲目失败: " + failure);
            } finally {
                RESOLVE_IN_FLIGHT.set(false);
            }
            pending = new PendingResolution(captured.trackKey(), music, captured);
        });
    }

    /** 用当前落地状态问一次：这一首值不值得因为时长变了再解析一遍。 */
    private static boolean needsSettleRetry(long durationMs) {
        return needsSettleRetry(appliedWasSynthetic, settleRetries, appliedDurationMs, durationMs);
    }

    /**
     * 外部播放器头一拍报的时长常常还没定下来：浏览器要等媒体元数据读全，某些客户端
     * 先报一个占位值再改成真实时长。而曲库匹配里「时长对不对得上」正是最硬的一道判据
     * （见 {@link ExternalTrackMatcher#durationAgrees}），于是那一拍会判成「曲库里没匹配到」，
     * 之后同一首的 {@code trackKey} 不再变，就永远不会有第二次解析 ——
     * 玩家看到的是全屏页一直挂着「暂无歌词」，而词其实一直在那儿。
     *
     * <p>所以这里给合成曲目（= 上一拍没匹配到曲库）留一条重解析的路：同一首、时长确实
     * 变了、且没超过次数上限。真曲目不走这条路，匹配到了就是匹配到了，不该再打网络。</p>
     */
    static boolean needsSettleRetry(boolean wasSynthetic, int retries, long beforeMs, long nowMs) {
        if (!wasSynthetic || retries >= SETTLE_RETRY_LIMIT) return false;
        // 两边都得是播放器真报出来的时长：0 是「没报」，拿它当变化来看会抖个没完。
        if (beforeMs <= 0L || nowMs <= 0L) return false;
        return Math.abs(nowMs - beforeMs) >= SETTLE_DURATION_STEP_MS;
    }

    /** 只在主线程调用：把一条解析结果落地成"正在播放"。 */
    private static void apply(PendingResolution resolution) {
        ExternalSnapshot snapshot = resolution.snapshot;
        Music music = resolution.music;
        // 同一首的第 N 次落地：重解析的额度只在键没变时累加，换歌一律从头计。
        boolean sameTrackAsApplied = resolution.trackKey.equals(appliedTrackKey);
        if (music == null) music = synthetic(snapshot);

        // 接管之前必须先把内部音频真的停下来。漏了这一条的话，「正在播放队列里的一首歌」
        // 与「外部播放器正在播的另一首」会同时出声，而进度条与歌词只会显示后一个。
        // stopPlaybackAndClear 排到主线程的那次字段清理带身份校验（我们马上要换掉
        // currentlyPlaying，所以它会自己放弃），因此这里把 player 也一并清掉。
        if (CloudMusic.player != null) {
            try {
                CloudMusic.stopPlaybackAndClear(CloudMusic.currentlyPlaying);
            } catch (Throwable failure) {
                System.err.println("[Music/SMTC] 接管前停内部播放失败: " + failure);
            }
            CloudMusic.player = null;
        }
        // 直播流也在被停之列：它不写 CloudMusic.player，上面的判断看不见它 —— 不显式
        // 停掉就是系统播放器与广播电台同时出声。
        LiveStreamSession.stop("mirrorTakeover");

        // 与播放线程同一套身份协议：先递增 generation 让上一段会话作废，再建会话，
        // 这样任何还在飞的旧歌词任务都会在提交前自己认输。
        long sessionId = CloudMusic.generation.incrementAndGet();
        PlaybackSession session = new PlaybackSession(sessionId, music.getStableKey(), music.getId());
        CloudMusic.currentSession = session;
        CloudMusic.currentlyPlaying = music;
        // 空队列必须是可变的：automix 预备与队列面板都会往 playList 里塞东西，
        // 给一个 Collections.emptyList() 会让它们在下一次追加时抛 UnsupportedOperationException。
        CloudMusic.playList = new java.util.ArrayList<Music>();
        CloudMusic.curIdx = 0;
        // 队列整条换成了一条空列表：用户那份「下一首播放」的账也跟着作废，留着只会让下一次
        // 读数凭空长出一段待播。
        CloudMusic.clearQueuedNext();
        mirroring = true;
        appliedTrackKey = resolution.trackKey;
        resolvingTrackKey = "";
        // 这一首到底有没有配上曲库，决定下一拍要不要给「时长后落地」留重解析的口子。
        appliedWasSynthetic = resolution.music == null;
        appliedDurationMs = snapshot.durationMs();
        settleRetries = appliedWasSynthetic && sameTrackAsApplied ? settleRetries + 1 : 0;

        if (resolution.music == null) {
            // 没匹配到曲库：显式提交一份空时间轴。不清的话 CloudMusic.lyrics 里
            // 还留着上一首的时间轴，而 lyricsMatchCurrentTrack() 会因为归属键没换而
            // 让它继续跟着外部进度滚 —— 表现就是「换了首歌，歌词还是上一首」。
            CloudMusic.applyLyricTimeline(session, null, Collections.<LyricLine>emptyList());
            notifyIsland("曲库里没匹配到这首歌 · 无词", false);
        } else {
            CloudMusic.loadLyric(music, session);
        }
        announceTrackOnce(music, snapshot);
        System.out.println("[Music/SMTC] 接管外部曲目: " + music.getName()
                + " <- " + ExternalMediaService.appLabel(snapshot.app()));
    }

    /**
     * 没匹配到曲库时也要能显示曲名，于是照 YouTube／汽水适配器的写法合成一条曲目。
     *
     * <p>稳定键带 {@code smtc:} 前缀再哈希，与任何真实平台的 id 都在不同的命名空间里，
     * 所以它不会去污染歌词缓存或封面缓存。{@code source = SMTC} 让徽标与状态行能说出
     * 「这是系统播放器」，而不是假装自己是网易云。</p>
     */
    private static Music synthetic(ExternalSnapshot snapshot) {
        String title = snapshot.title().trim();
        String artist = snapshot.artist().trim();
        long id = stableLong("smtc:" + snapshot.normalizedTitle() + "|" + snapshot.normalizedArtist());
        List<Artist> artists = artist.isEmpty()
                ? Collections.<Artist>emptyList()
                : Collections.singletonList(new Artist(
                        stableLong("smtc:artist:" + snapshot.normalizedArtist()),
                        artist, Collections.<String>emptyList(), Collections.<String>emptyList()));
        Album album = new Album(stableLong("smtc:album:" + snapshot.normalizedTitle()),
                snapshot.album().trim(), "", Collections.<String>emptyList());
        Music music = new Music(title, title, "", id, artists, Collections.<String>emptyList(),
                album, snapshot.durationMs(), 0L, 0L, Collections.<String>emptyList());
        music.setSource(MusicPlatform.SMTC);
        music.setSourceId(snapshot.trackKey());
        return music;
    }

    /** 与 {@code Music.stableLong} 同一族的做法：FNV-1a 折成 long，用于合成主键。 */
    private static long stableLong(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;
    }

    /** 让出播放态：把镜像写进去的那几个字段收干净，回到「内部播放器说了算」。 */
    private static void detach(String reasonForUser) {
        if (!mirroring && appliedTrackKey.isEmpty()) {
            if (!reasonForUser.isEmpty()) notifyIsland(reasonForUser, true);
            return;
        }
        mirroring = false;
        appliedTrackKey = "";
        resolvingTrackKey = "";
        appliedWasSynthetic = false;
        appliedDurationMs = 0L;
        settleRetries = 0;
        // 音量的三个值一起清：下次进镜像要重新从合成器读，不能拿上一支播放器的格子顶着，
        // 更不能把没发出去的最后一档在换了程序之后补发出去。
        wantedVolume = Float.NaN;
        sentVolume = Float.NaN;
        echoVolume = Float.NaN;
        volumeDragAtMs = 0L;
        pending = null;
        raw = ExternalSnapshot.NONE;
        ExternalArtwork.clear();
        CloudMusic.clearExternalMirrorState();
        if (!reasonForUser.isEmpty()) notifyIsland(reasonForUser, true);
    }

    // ── 反向控制 ────────────────────────────────────────────────────────────────

    /** 外部播放器是否接受这条命令。能力位来自 SMTC 本身，不是我们猜的。 */
    public static boolean canToggle() {
        ExternalSnapshot snapshot = raw;
        return mirroring && (snapshot.canToggle() || snapshot.canPlay() || snapshot.canPause());
    }

    public static boolean canNext() {
        return mirroring && raw.canNext();
    }

    public static boolean canPrevious() {
        return mirroring && raw.canPrevious();
    }

    public static boolean canSeek() {
        return mirroring && raw.canSeek();
    }

    /**
     * 音量能不能镜像：正在镜像，且助手真的把那支程序与音量合成器里的一格对上了。
     * 为 false 时 {@link #writeVolume(float)} 什么都不做 —— 宁可滑块不动，也不能去拧主音量。
     */
    public static boolean canVolume() {
        return mirroring && raw.hasVolume();
    }

    /**
     * 玩家拖音量条那一刻的入口。
     *
     * <p>返回 true 的含意是「这根滑块此刻归外部播放器管」，调用方（{@code CloudMusic.setVolume}）
     * 因此不会去写模组自己那份持久化音量 —— 玩家只是拖了一下外部的音量，不该回来发现自己
     * 播放器的音量被改掉了。配不上会话时也是这样：宁可只提示不动，也不能退回去动主音量。</p>
     *
     * @return 这个值是否交给了外部播放器（false 表示当前不是镜像态，调用方走自己那套）
     */
    public static boolean writeVolume(float level) {
        if (!mirroring) return false;
        if (!canVolume()) {
            announceNoVolumeOnce();
            return true;
        }
        float clamped = Math.max(0f, Math.min(1f, level));
        wantedVolume = clamped;
        volumeDragAtMs = System.currentTimeMillis();
        return true;
    }

    /** 拖动是每帧一次的，这条提示最多 3 秒说一次。 */
    private static void announceNoVolumeOnce() {
        long now = System.currentTimeMillis();
        if (now - noVolumeNoticeAtMs < 3_000L) return;
        noVolumeNoticeAtMs = now;
        notifyIsland("这台播放器没有可调的音量（独占模式或没配上会话）", true);
    }

    /**
     * 音量滑块该画到哪一格：镜像时是外部播放器那一格（正在拖的那一档优先，要跟手），
     * 不在镜像、或还不知道外部是多少时，原样交出调用方自己那份 {@code internal}。
     *
     * <p>这是音量读数的唯一分叉点，界面上每一根音量条都走它。</p>
     */
    public static float volumeOr(float internal) {
        if (!mirroring) return internal;
        return displayedVolume(wantedVolume, echoVolume, internal);
    }

    /**
     * 每 tick 做两件事：把助手的读数收进来（除非刚拖完，那几拍读数还是旧的），
     * 以及把玩家拖到的那一档按 120ms 的节奏往下送。
     *
     * <p>刻意在这里合并而不是在 {@link #writeVolume(float)} 里直接写：拖动是每帧一次的，
     * 一次写要跨进程枚举一遍会话，逐帧推等于让一个 UI 事件刷爆音量合成器。</p>
     */
    private static void tickVolume(ExternalSnapshot snapshot) {
        long now = System.currentTimeMillis();
        if (shouldAdoptEcho(snapshot.hasVolume(), snapshot.volumeLevel(), wantedVolume,
                now, volumeDragAtMs)) {
            echoVolume = snapshot.volumeLevel();
        }
        if (!shouldSendVolume(wantedVolume, sentVolume, now, volumeSendAtMs)) return;
        volumeSendAtMs = now;
        sentVolume = wantedVolume;
        ExternalMediaService.sendVolume(wantedVolume);
    }

    /**
     * 纯函数：这一拍该不该把 {@code wanted} 交给助手。三个「不该」——
     * 没人在拖、与已经发出去的那一档是同一个值、离上一次发出去还不够久。
     *
     * <p>最后一条是有原因的：拖动是每帧一次的，而每一次写都要跨进程把会话枚举一遍。
     * 逐帧推等于用一个 UI 事件去刷音量合成器。</p>
     */
    static boolean shouldSendVolume(float wanted, float sent, long nowMs, long lastSendMs) {
        if (Float.isNaN(wanted)) return false;
        if (!Float.isNaN(sent) && Math.abs(wanted - sent) < .001f) return false;
        return nowMs - lastSendMs >= VOLUME_SEND_INTERVAL_MS;
    }

    /**
     * 纯函数：助手读回来的那一格音量这一拍能不能采信。
     *
     * <p>刚拖完的那 {@value #VOLUME_DRAG_GRACE_MS} 毫秒里不能 —— 状态行是 500ms 一拍采的，
     * 那几拍带回来的还是写之前的旧值，采信就等于滑块往回跳。等过了这个窗口再采信，
     * 玩家在系统合成器里自己调音量时模组才跟得上。</p>
     */
    static boolean shouldAdoptEcho(boolean known, float echo, float wanted, long nowMs, long dragAtMs) {
        if (!known || Float.isNaN(echo) || echo < 0f) return false;
        if (nowMs - dragAtMs > VOLUME_DRAG_GRACE_MS) return true;
        return !Float.isNaN(wanted) && Math.abs(echo - wanted) < .001f;
    }

    /** 纯函数：滑块该画到哪。正在拖的那一档优先（要跟手），其次助手的读数，最后才是调用方自己的值。 */
    static float displayedVolume(float wanted, float echo, float internal) {
        if (!Float.isNaN(wanted)) return wanted;
        if (!Float.isNaN(echo)) return echo;
        return internal;
    }

    public static void toggle() {
        if (!canToggle()) {
            notifyIsland("外部播放器不接受暂停/继续", true);
            return;
        }
        ExternalMediaService.sendCommand("toggle");
    }

    public static void next() {
        if (!canNext()) {
            notifyIsland("外部播放器不接受「下一首」", true);
            return;
        }
        ExternalMediaService.sendCommand("next");
    }

    public static void previous() {
        if (!canPrevious()) {
            notifyIsland("外部播放器不接受「上一首」", true);
            return;
        }
        ExternalMediaService.sendCommand("prev");
    }

    /**
     * 请求外部播放器跳到某个位置。
     *
     * <p>不在成功后动本地进度：本地进度就是 SMTC 报回来的那份，命令落地后下一拍自然会跟上。
     * 本地先跳反而会在外部不接受（能力位说行、播放器自己没实现）时留下一个假位置。</p>
     */
    public static void seekTo(long positionMs) {
        if (!canSeek()) {
            notifyIsland("外部播放器不接受拖动进度", true);
            return;
        }
        ExternalMediaService.seekTo(positionMs);
    }

    /** 把助手回来的命令结果转成一次岛通知。只报失败与拒绝，成功不必说。 */
    private static void reportCommandResult() {
        String line = ExternalMediaService.drainCommandResult();
        if (line == null) return;
        boolean accepted = line.contains("\"accepted\":true");
        if (accepted) return;
        String key = extract(line, "key");
        String error = extract(line, "error");
        String label = "seek".equals(key) ? "调整进度"
                : "next".equals(key) ? "下一首"
                : "prev".equals(key) ? "上一首" : "播放/暂停";
        notifyIsland("外部播放器没响应「" + label + "」"
                + ("no_session".equals(error) ? "（会话已结束）" : ""), true);
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int from = json.indexOf(marker);
        if (from < 0) return "";
        int start = from + marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    // ── 与内部播放的互斥 ────────────────────────────────────────────────────────

    /**
     * 镜像开着且确实在接管时，拒绝由内部播放器起播。
     *
     * @return true 表示这次内部起播已经被拦下，调用方直接 return
     */
    public static boolean refuseInternalPlayback() {
        if (!mirroring) return false;
        notifyIsland("系统播放器镜像开启中，先关镜像才能用内置播放", true);
        return true;
    }

    /**
     * @param success true 走成功外观（接管成功、状态告知）；false 走失败外观
     *                （外部播放器不接受命令、曲库里没匹配到、镜像挡下内部起播）
     */
    private static void notifyIsland(String detail, boolean success) {
        try {
            DownloadDynamicIsland.showExternalMedia(detail, success);
        } catch (Throwable ignored) {
            // 岛没起来也不该影响镜像本身；这条通知纯属可选反馈。
        }
    }

    /** 接管某一首时只说一句，避免每 tick 重复播报。 */
    private static void announceTrackOnce(Music music, ExternalSnapshot snapshot) {
        String key = appliedTrackKey;
        if (key.equals(lastAnnouncedTrackKey)) return;
        lastAnnouncedTrackKey = key;
        String artist = music.getArtists() == null || music.getArtists().isEmpty()
                ? "" : music.getArtists().get(0).getName();
        String detail = artist.isEmpty() ? music.getName() : music.getName() + " - " + artist;
        notifyIsland("正在镜像 " + ExternalMediaService.appLabel(snapshot.app()) + "：" + detail, false);
    }

    private static void announceNoSessionOnce() {
        if (announcedNoSession) return;
        announcedNoSession = true;
        lastAnnouncedTrackKey = "";
        // 这一条只在"刚刚还在接管、现在会话没了"时才有意义，静默处理即可，不打扰玩家。
    }

    /** 供设置页与状态行使用。 */
    public static String statusLabel() {
        return ExternalMediaService.statusLabel();
    }

    /** 供 HUD 编辑器里的「重新探测」按钮使用。 */
    public static void retryProbe() {
        SmtcTooling.invalidateCache();
        ExternalMediaService.retry();
    }
}
