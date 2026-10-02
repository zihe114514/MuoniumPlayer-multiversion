package com.muoniumplayer.party;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicAudioCache;
import com.muoniumplayer.core.ncm.music.dto.Music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 房间歌单的<b>提前下载</b>与 automix <b>单曲 profile 预热</b>。
 *
 * <p><b>问题。</b>房间只把「下一首」提前处理一首（{@link MpPlayback#stageNext}），歌单里第二首之后
 * 的东西在本机磁盘上压根不存在。于是队列里排着的每一首轮到时都要现场走一遍「取直链 → 下载几十兆
 * → 探测容器 →（可能再）转码」，房间里那句「无缝交接」永远只混得动第一首。</p>
 *
 * <p><b>怎么做。</b>服务端每次广播歌单（{@code S_QUEUE}）与房间快照（{@code S_ROOM_STATE}）都把
 * 整份待播列表带过来，而<b>条目本身就带着点歌人解析好的直链</b>。这里在当前那首之后取最多
 * {@value #PREFETCH_LIMIT} 首，用 {@link CloudMusic#prefetchRoomTrackAudio} 按<b>与实播完全相同</b>
 * 的缓存键落到磁盘 —— 等它真轮到，播放线程查一次盘就走完了，一段网络等待都不占。</p>
 *
 * <p><b>为什么必须同一个键。</b>缓存键是「曲目稳定键 + 音质」，不含直链（同一条曲目每次解析出的
 * 直链都不一样）。提前下载只要在这个键上分叉一次，实播就永远命中不上，整件事退化成「同一首歌
 * 下两遍」，而且没有任何现象会提示。所以本类一行键都不自己拼，只调 CloudMusic 里那唯一的实现。</p>
 *
 * <h3>三条不许越过的线</h3>
 * <ul>
 *   <li><b>不解析。</b>条目没有可用直链、或直链快过期，就跳过这一首。解析是「代解析」那套链路的活
 *       （由服务端分级求助决定问谁），在这里替它解析一次等于用本机账号取一条没人授权的流，
 *       而且会把 {@code Music#getPlayUrl()} 那条会发请求、会动播放侧状态的路径拖进预热。</li>
 *   <li><b>不占 {@code BackgroundWork}。</b>那条低优先级池只有
 *       {@code max(1, min(2, cores-2))} 一条到两条线程，是 automix 预备的生命线。十首无损一次性
 *       压进去，预备解码就永远排在后面，交接点直接错过 —— 那是「越优化越难听」。所以自带一条
 *       并发 {@value #DOWNLOAD_CONCURRENCY} 的池：daemon、{@link Thread#MIN_PRIORITY}、
 *       线程名 {@code mp-prefetch}。</li>
 *   <li><b>不在收帧线程上做事。</b>{@code MpPartyClient.dispatch} 跑在网络线程上，
 *       {@link #onQueue} 只允许复制引用 + 提交，一次目录查询都不许有。</li>
 * </ul>
 *
 * <h3>幂等</h3>
 * 服务端每次队列变动都重发整份歌单（一秒可能好几份）。{@link #onQueue} 每收到一次只把最新列表
 * 放进一个 volatile 并排一次「盘点」。盘点按 {@code 曲目键 + 直链} 记账，下过/在下的直接跳过，
 * 所以刷新十次也只排十首，不会把清单重排十遍。换房、退房、队列清空都会让 {@link #generation}
 * 进位，在排的任务开工前对这个号，对不上就直接放弃（正在下载的那一条无法中断，但它写完的只是
 * 一个 {@code .download} 残片，下一轮容量兜底会收走）。
 */
public final class MpRoomPrefetch {

    private MpRoomPrefetch() {
    }

    /** 当前播放曲之后最多提前下载几首。写死：再多就是拿别人的上行去赌命中率。 */
    static final int PREFETCH_LIMIT = 10;

    /** 已经下好的里面最多预热几首的模型 profile。Python 一次 120–180 秒，五首已经排一整轮。 */
    static final int PROFILE_LIMIT = 5;

    /**
     * 预下载的并发上限。刻意不复用 {@code MultiThreadingUtil.runAsync}（那是无界的 cached pool，
     * 一次刷新能把十几条并发拉满，上行和 CDN 连接一起被打爆），也不用 {@code BackgroundWork}。
     */
    private static final int DOWNLOAD_CONCURRENCY = 2;

    /** 直链到期前多少毫秒就不再动它：快过期的链接下到一半也会断，白占一次并发。 */
    private static final long URL_EXPIRY_MARGIN_MILLIS = 20_000L;

    /**
     * 磁盘余量闸门。低于这个量就不再预热 —— 把用户的盘写满比少预下载几首严重得多，
     * 而且缓存里谁该被删由 LRU 淘汰决定，不该由「预热想多占一点」决定。
     */
    private static final long MIN_FREE_SPACE_BYTES = 512L * 1024L * 1024L;

    /** 真人交接的分析最多让预热等这么久；到点这一轮就不做了，下一份歌单还会再来一次机会。 */
    private static final long PROFILE_YIELD_CEILING_MILLIS = 90_000L;

    /** 让路时的轮询间隔。分析一次本身要一两分钟，没必要贴得更紧。 */
    private static final long PROFILE_YIELD_POLL_MILLIS = 2_000L;

    /** 房间代号：换房（含退房）时它变。 */
    private static volatile String roomToken = "";

    /** 最新一份待播队列；由收帧线程整体替换，盘点线程只读。 */
    private static volatile List<MpTrack> latestQueue = Collections.emptyList();

    /** 这一轮房间里已经排过/下过的条目：{@code 曲目键 + '|' + 直链}。换房即清。 */
    private static final Set<String> attempted = ConcurrentHashMap.newKeySet();

    /** 已经预热过 profile 的曲目键（同一首不重跑 Python）。换房即清。 */
    private static final Set<String> warmed = ConcurrentHashMap.newKeySet();

    /** 上一次「停」的原因，用于同一状态只吵一行，不每秒刷一条。 */
    private static volatile String lastStopReason = "";

    /** 作废计数：换房/退房/队列清空时进位，在排的任务对不上号就放弃。 */
    private static final AtomicLong generation = new AtomicLong();

    private static final Object POOL_LOCK = new Object();
    private static ExecutorService downloads;
    private static ExecutorService profiles;
    private static ExecutorService passes;

    // ───────────────────────────── 入口 ─────────────────────────────

    /**
     * 收到一份房间歌单（{@code S_QUEUE} 或房间快照）。
     *
     * <p>跑在<b>网络线程</b>上，所以这里只复制引用并提交任务：不查盘、不算键、不下载。
     * 不在房间里时直接作废并返回，一条任务都不排。</p>
     */
    public static void onQueue(List<MpTrack> queue) {
        latestQueue = queue == null
                ? Collections.<MpTrack>emptyList()
                : new ArrayList<MpTrack>(queue);
        if (!MpPartyClient.inRoom()) {
            invalidate();
            return;
        }
        final long token = generation.get();
        try {
            passPool().execute(new Runnable() {
                @Override
                public void run() {
                    pass(token);
                }
            });
        } catch (Throwable ignored) {
            // 池子被关（游戏退出中）或提交被拒：预热不是「必须发生」的事，绝不把收帧链路带崩。
        }
    }

    /** 换房 / 退房 / 队列清空：作废在排的一切，并清掉本房间的记账。 */
    public static void invalidate() {
        generation.incrementAndGet();
        attempted.clear();
        warmed.clear();
        lastStopReason = "";
        roomToken = "";
    }

    // ───────────────────────────── 盘点 ─────────────────────────────

    /** 一次盘点：挑出该预热谁、把房间那几首在淘汰账本上置顶、排下载、排 profile 预热。 */
    private static void pass(long token) {
        MpRoom room = MpPartyClient.room();
        List<MpTrack> queue = latestQueue;
        if (room == null || queue.isEmpty()) {
            invalidate();
            return;
        }
        String roomNow = roomTokenOf(room);
        if (!roomNow.equals(roomToken)) {
            // 换房：上一间房排下的任务全部作废，这一份盘点是为新房子的，用新的号继续。
            invalidate();
            roomToken = roomNow;
            token = generation.get();
        }
        if (token != generation.get()) return;

        List<MpTrack> targets = selectPrefetchTargets(queue, MpPartyClient.currentTrack(),
                PREFETCH_LIMIT, System.currentTimeMillis());
        if (targets.isEmpty()) return;

        // 缓存键由 Music 的身份（source + id）决定，所以这里必须和实播一样用 toMusic() 重造，
        // 而不是拿别的构造路径 —— 那是第二个实现，也就是永远命中不上的那个 bug。
        List<Music> songs = new ArrayList<Music>(targets.size());
        for (MpTrack track : targets) songs.add(musicOf(track));

        // 房间即将播到的那些必须在容量淘汰里置顶。判据用现成的那本账：查一次盘命中就 touch
        // 一笔（CloudMusic#cachedAudioFileFor 里那唯一一处键实现），越靠近队首的越后 touch，
        // 于是在「最冷的组先删」的排序里它们排得最远。倒序问，所以队首最热。只碰磁盘。
        pinRoomTracks(targets, songs);

        String stop = capacityStopReason(CloudMusic.audioCacheUsedBytes(),
                CloudMusic.audioCacheLimitBytes(), CloudMusic.audioCacheFreeSpaceBytes());
        if (stop != null) {
            // 停下来必须说清楚为什么：静默不预热的话，用户只会看到「房间交接偶尔还是硬切」。
            if (!stop.equals(lastStopReason)) {
                lastStopReason = stop;
                System.out.println("[MpPrefetch] " + stop);
            }
            return;
        }
        lastStopReason = "";

        int scheduled = 0;
        for (int i = 0; i < targets.size(); i++) {
            MpTrack track = targets.get(i);
            Music song = songs.get(i);
            if (song == null) {
                attempted.add(slotOf(track));       // 元数据都造不出来，重试也不会有别的结果
                continue;
            }
            if (!attempted.add(slotOf(track))) continue;   // 下过/正在下：跳过，不重排
            scheduled++;
            final String url = track.url;
            final long validToken = token;
            try {
                downloadPool().execute(new Runnable() {
                    @Override
                    public void run() {
                        if (validToken != generation.get()) return;
                        CloudMusic.prefetchRoomTrackAudio(url, null, song);
                    }
                });
            } catch (Throwable rejected) {
                break;
            }
        }
        if (scheduled > 0) {
            System.out.println("[MpPrefetch] 排队预热 " + scheduled + " 首（当前曲之后最多 "
                    + PREFETCH_LIMIT + " 首，只挑自带可用直链的）");
        }

        // profile 预热走<b>另一条</b>单线程 daemon，而不是这条下载池：一次 Folia 分析要
        // 120–180 秒，占着并发只有 2 的下载池就等于把「先把文件弄下磁盘」挤到几分钟后 ——
        // 文件在不在磁盘决定「轮到时还下不下」，profile 只决定「轮到时要不要多等一次 Python」。
        // 前者晚了会硬切，后者晚了只是慢一点，所以后者不许排在前者前面。
        // 单线程本身就是「Python 并发 1」这条约束的实现，不需要再加锁。
        final List<MpTrack> warmTracks = new ArrayList<MpTrack>();
        final List<Music> warmSongs = new ArrayList<Music>();
        for (int i = 0; i < targets.size() && i < PROFILE_LIMIT; i++) {
            MpTrack track = targets.get(i);
            Music song = songs.get(i);
            if (song == null || warmed.contains(track.key())) continue;
            warmTracks.add(track);
            warmSongs.add(song);
        }
        if (warmSongs.isEmpty()) return;
        final long warmToken = token;
        try {
            profilePool().execute(new Runnable() {
                @Override
                public void run() {
                    warmProfiles(warmToken, warmTracks, warmSongs);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 最多 {@value #PROFILE_LIMIT} 首、按队列顺序；每一首之前先确认没人在做真人那一次分析。 */
    private static void warmProfiles(long token, List<MpTrack> tracks, List<Music> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            if (token != generation.get()) return;
            // 同一份歌单可能连着排进两个任务：前一个已经开始的那一首不再重复烧 Python。
            if (warmed.contains(tracks.get(i).key())) continue;
            // 引擎关了 / 运行时或模型掉了：整轮都不该跑，一行日志都不用留。
            if (!CloudMusic.automixProfileWarmupReady()) return;
            if (!yieldToRealHandover(token)) return;
            Music song = candidates.get(i);
            warmed.add(tracks.get(i).key());
            if (CloudMusic.warmAutomixProfile(song)) {
                System.out.println("[MpPrefetch] 已预热交接 profile：" + song.getName());
            }
        }
    }

    /**
     * 真人那一次交接的分析在跑就让路。
     *
     * <p>预热是可以整轮放弃的那一方：它算出来的东西晚点算没人受影响，而预备是「几秒后就要出声」。
     * 等到超过 {@link #PROFILE_YIELD_CEILING_MILLIS} 还不空就整轮放弃（绝不硬抢），
     * 下一份歌单再来一次机会。</p>
     *
     * @return 可以继续了
     */
    private static boolean yieldToRealHandover(long token) {
        long waited = 0L;
        while (CloudMusic.automixAnalysisBusy()) {
            if (token != generation.get()) return false;
            if (waited >= PROFILE_YIELD_CEILING_MILLIS) {
                System.out.println("[MpPrefetch] 真人交接的分析一直在跑，这一轮 profile 预热让路放弃");
                return false;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(PROFILE_YIELD_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            waited += PROFILE_YIELD_POLL_MILLIS;
        }
        return true;
    }

    /**
     * 把房间那几首在淘汰的最近使用账本上置顶。
     *
     * <p>{@code MusicAudioCache} 的淘汰按「最冷的组先删」，而查一次盘命中就会顺手
     * {@code touch} 一笔 —— 所以「反复问一遍房间要播的那几首」本身就是置顶。
     * 只问磁盘（{@code downloadIfMissing = false} 那一张脸），一个请求都不发。</p>
     */
    private static void pinRoomTracks(List<MpTrack> targets, List<Music> songs) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            if (!attempted.contains(slotOf(targets.get(i)))) continue;
            Music song = songs.get(i);
            if (song == null) continue;
            CloudMusic.cachedAudioFileFor(song);
        }
    }

    // ─────────────────────── 纯函数（单测钉住） ───────────────────────

    /**
     * 从待播队列里挑出「当前这首之后、最多 {@code limit} 首、而且条目自带可用直链」的那些。
     *
     * <p>纯函数：不查盘、不联网、不读任何全局状态，所以能脱离游戏环境逐条钉住。规则：</p>
     * <ol>
     *   <li>当前曲<b>列在这份队列里</b>时从它之后开始算；没列进来（一起听歌单本来就是「待播」
     *       列表，正播的那首不在里面）时整份队列都在它之后 —— 认错这一条的后果是「一首都不排」；</li>
     *   <li>同一曲目重复登记只取第一次（按 {@code source + songId} 的键）；</li>
     *   <li>没有直链、或直链在 {@code now + }{@link #URL_EXPIRY_MARGIN_MILLIS} 之内到期 →
     *       <b>跳过</b>，这里绝不解析；</li>
     *   <li>{@code urlExpiresAt} 为 0 = 老服务端没带到期时间，按「可用」处理。</li>
     * </ol>
     */
    public static List<MpTrack> selectPrefetchTargets(List<MpTrack> queue, MpTrack current,
                                                      int limit, long nowMillis) {
        List<MpTrack> out = new ArrayList<MpTrack>();
        if (queue == null || queue.isEmpty() || limit <= 0) return out;
        Set<String> seen = new HashSet<String>();
        // 当前曲没被列进这份队列（一起听歌单本来就是「待播」列表）时，整份队列都在它之后。
        boolean afterCurrent = current == null || !lists(queue, current.key());
        for (MpTrack track : queue) {
            if (track == null) continue;
            if (!afterCurrent) {
                if (track.key().equals(current.key())) afterCurrent = true;
                continue;
            }
            if (!seen.add(track.key())) continue;
            if (!hasUsableStreamUrl(track, nowMillis)) continue;
            out.add(track);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 这份队列里是不是列着给定键的那一首。 */
    private static boolean lists(List<MpTrack> queue, String key) {
        for (MpTrack track : queue) {
            if (track != null && track.key().equals(key)) return true;
        }
        return false;
    }

    /** 这条队列条目自带「现在就能下」的直链吗。没有就是没有，这里不替它解析。 */
    public static boolean hasUsableStreamUrl(MpTrack track, long nowMillis) {
        if (track == null) return false;
        String url = track.url;
        if (url == null || url.trim().isEmpty()) return false;
        return track.urlExpiresAt <= 0L || track.urlExpiresAt > nowMillis + URL_EXPIRY_MARGIN_MILLIS;
    }

    /**
     * 容量闸门：现在还能不能再往下预热。返回 {@code null} 表示可以继续。
     *
     * <p>两道闸，读的都是既有机制（{@code MusicAudioCache} 的上限淘汰 + 缓存所在分区余量）。
     * 不够时的正确答案是<b>停手并说明</b>，而不是接着下、再让淘汰去删刚下好的那些。</p>
     *
     * @param usedBytes      音频缓存目录当前总量
     * @param limitBytes     用户设置的缓存上限，{@code <= 0} = 不限制
     * @param freeSpaceBytes 缓存所在分区的剩余空间
     */
    public static String capacityStopReason(long usedBytes, long limitBytes, long freeSpaceBytes) {
        if (limitBytes > 0L && usedBytes >= limitBytes) {
            return "音频缓存已用 " + MusicAudioCache.formatBytes(usedBytes)
                    + "，已达上限 " + MusicAudioCache.formatBytes(limitBytes)
                    + " —— 停止房间提前下载，空间留给实播";
        }
        if (freeSpaceBytes < MIN_FREE_SPACE_BYTES) {
            return "缓存所在分区只剩 " + MusicAudioCache.formatBytes(freeSpaceBytes)
                    + "，低于 " + MusicAudioCache.formatBytes(MIN_FREE_SPACE_BYTES)
                    + " —— 停止房间提前下载，不把用户的磁盘写满";
        }
        return null;
    }

    /** 一条队列条目的记账身份：曲目键 + 直链。换了直链（服务端续签）允许再试一次。 */
    private static String slotOf(MpTrack track) {
        return track.key() + '|' + track.url;
    }

    /** 房间代号。id 为空时退到房主，再不行退到房间名：只要换房就会变。 */
    private static String roomTokenOf(MpRoom room) {
        if (room == null) return "";
        if (room.id != null && !room.id.isEmpty()) return room.id;
        if (room.owner != null) return room.owner.toString();
        return room.name == null ? "" : room.name;
    }

    /** 用房间下发的元数据重造 Music：缓存键只由它的身份决定，所以必须走实播那同一个构造入口。 */
    private static Music musicOf(MpTrack track) {
        try {
            return track.toMusic();
        } catch (Throwable throwable) {
            System.err.println("[MpPrefetch] 跳过 " + track.display() + "：构造曲目异常 " + throwable);
            return null;
        }
    }

    // ───────────────────────────── 线程池 ─────────────────────────────

    /** 预下载池：并发 {@value #DOWNLOAD_CONCURRENCY}、{@link Thread#MIN_PRIORITY}、daemon。 */
    private static ExecutorService downloadPool() {
        synchronized (POOL_LOCK) {
            if (downloads == null) {
                downloads = newPool(DOWNLOAD_CONCURRENCY, "mp-prefetch-");
            }
            return downloads;
        }
    }

    /**
     * profile 预热池：恒为 1 条线程。
     *
     * <p>并发 1 是硬要求 —— 每一次分析都要起一个 Python 子进程（Beat This 上限 120 秒、
     * HTDemucs 上限 180 秒），两条一起跑就是两份模型权重进内存，而这两个子进程本来就是串行的。
     * 与下载池分开，理由见 {@link #pass(long)}。</p>
     */
    private static ExecutorService profilePool() {
        synchronized (POOL_LOCK) {
            if (profiles == null) {
                profiles = newPool(1, "mp-prefetch-profile-");
            }
            return profiles;
        }
    }

    /**
     * 盘点池：恒为 1 条线程，和下载池分开。
     *
     * <p>盘点（{@link #pass(long)}）里最要紧的一步是 {@code pinRoomTracks} —— 把房间马上要播的
     * 那几首在缓存淘汰账本上置顶。它本身只碰磁盘、很快，但以前和下载共用并发只有 2 的下载池：
     * 两条线程被几十兆的大文件下载占满时（一首 5–30 秒），{@code S_QUEUE} 一秒可能来好几份、
     * 每份都排一次盘点，全堆在下载后面，置顶被推迟 —— 缓存吃紧时「马上要播那首」可能先被淘汰，
     * 正是预取要消除的硬切。单独一条线程让盘点永不排在下载后面。</p>
     */
    private static ExecutorService passPool() {
        synchronized (POOL_LOCK) {
            if (passes == null) {
                passes = newPool(1, "mp-prefetch-pass-");
            }
            return passes;
        }
    }

    /** 两条池同构：固定并发、无界队列（预热稀疏，丢任务的代价是这次没预热上）、空闲回收。 */
    private static ExecutorService newPool(int concurrency, String namePrefix) {
        final String prefix = namePrefix;
        final AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
                thread.setDaemon(true);
                try {
                    // 渲染线程与音频线程永远优先拿到 CPU：预热是「有空再干」的活。
                    thread.setPriority(Thread.MIN_PRIORITY);
                } catch (RuntimeException ignored) {
                    // 安全管理器不让改优先级；按默认优先级跑，功能不受影响。
                }
                return thread;
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(concurrency, concurrency,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), factory);
        // 退房之后线程不该一直挂着：空闲 60 秒归还，下一次进房再起来。
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
