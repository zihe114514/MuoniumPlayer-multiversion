package com.muoniumplayer.party;

/**
 * 房间对时策略：把服务端权威时钟落到本机播放器上的那一套阈值和状态机。
 *
 * <p><b>为什么需要它。</b>原来的做法是「每收到一帧心跳，就把服务端位置和本地位置比一下，
 * 差超过 1.5 秒立刻硬跳」。网络不好时这会自己震荡：
 * <ol>
 *   <li>心跳到达时间是抖的，{@code compensate} 又把抖动整段加进目标位置，同一个真实位置报出来
 *       能差出一两秒 —— 抖动幅度本身就超过判定阈值，于是随机往前／往后跳；</li>
 *   <li>跳一次要重设播放头（重开解码器时更贵），这段时间本地位置停着不走，下一帧心跳量出的
 *       偏差更大 → 再跳。正反馈，结果是进度来回跳、根本听不成。</li>
 * </ol>
 *
 * <p><b>改成三档。</b>{@code softDriftMs} 以内完全不管；软硬阈值之间也不动播放头（宁可差几秒，
 * 也不要为了对齐而反复扯动）；只有超过 {@code hardDriftMs}、<b>连续</b> {@code confirmHits}
 * 次心跳都超、并且距上次硬跳已过 {@code cooldownMs}、且不在命令静默期内，才真的跳一次。
 * 命令帧（换歌／暂停／恢复／房主拖进度）不走这套判定，永远立刻执行。
 *
 * <p><b>另外两道闸。</b>三档判定默认「本地位置和房间位置量的是同一条时间轴」，房间里有两种
 * 场合不成立：
 * <ul>
 *   <li><b>automix 交叠进行中</b> —— 一条在淡出、另一条刚从入口点淡进来，两边「故意」不同步。
 *       此时心跳量出来的偏差其实是本机开始淡入的时刻比房间晚了几秒（帧延迟 + 一条落在软档里
 *       没纠的漂移 + 新 deck 的缓冲），拿它去比硬阈值会把刚叠上的尾巴薅回去，听感上就是交叠被
 *       截断。所以{@link #markCommand(long)} 允许按<b>实际交叠长度</b>开静默窗，而不是只按
 *       服务端那个配置提前量。</li>
 *   <li><b>流卡住</b> —— 播放头冻着不走而房间照常在走，于是每发心跳都像脱节，硬跳又要重开
 *       解码器、于是更卡。{@link #isStalled} 把这种读数判成缓冲；只有连冻约 6 秒（那时确实
 *       只剩重开这一条自救路）才把判定放回去。</li>
 * </ul>
 *
 * <p>阈值由服务端 {@code hello} 下发（{@link #configure}），所以同一房间里所有客户端 ——
 * MuoniumPlayer 和 modernMP、各个游戏版本 —— 用的是同一套口径。收不到就用这里的默认值，
 * 它们与服务端 {@code MpConfig} 的默认值一致。
 */
public final class MpSyncPolicy {

    private MpSyncPolicy() {
    }

    /** 默认值与服务端 {@code MpConfig} 保持一致。 */
    private static volatile long softDriftMs = 2500L;
    private static volatile long hardDriftMs = 6000L;
    private static volatile long cooldownMs = 10000L;
    private static volatile int confirmHits = 2;
    private static volatile long graceMs = 4000L;

    /**
     * 单帧允许被算作「本帧额外网络延迟」的上限。
     *
     * <p>原来是 2000ms —— 比判定阈值还大，等于把抖动放大成一次硬跳。收到 600ms：真正的往返抖动
     * 通常远小于此，超出的部分更可能是排队／GC／时钟被校正，宁可不补也别乱补。</p>
     */
    private static final long MAX_LAG_MS = 600L;

    // ── 时钟偏移：滑动窗口最小值 ──────────────────────────────────────────────
    //
    // localNow - serverTime = 时钟偏移 + 单向延迟，取最小值近似「延迟最低的那一帧」。
    // 原来取「有史以来最小」，一次异常快的帧（或 NTP 把某一端时钟往回拨）会把它永久压低，
    // 之后每帧都被算成有延迟，位置被系统性加上一个常量。改成双桶滑动窗口，偏移能自己爬回来。

    private static final long OFFSET_WINDOW_MS = 30000L;
    private static long curMin = Long.MAX_VALUE;
    private static long prevMin = Long.MAX_VALUE;
    private static long windowStart;

    // ── 纠偏状态机 ────────────────────────────────────────────────────────────
    private static int hits;
    private static long lastHardSeekAt;
    private static long graceUntil;

    /**
     * 一次保护窗的上限。
     *
     * <p>交叠长度是内容决定的（{@code plan.overlapMillis}，Folia 能挑出一段 20 秒以上的长尾奏），
     * 但「完全不校准」这件事本身也有代价：真脱节的机器要靠心跳拉回来。30 秒是两者都能接受的
     * 上界 —— 比任何一段实际的混音都长，比一首歌的剩余部分短得多。</p>
     */
    private static final long MAX_PROTECT_MS = 30_000L;

    /** 交叠淡完之后再多给的落地时间：两条 deck 刚收成一条，缓冲正是不稳的时候。 */
    public static final long BLEND_SETTLE_MS = 1500L;

    // ── 卡流：位置冻住 ≠ 落后 ─────────────────────────────────────────────────
    //
    // 流卡住时本机位置停着不走，房间时钟照常在走，于是每一发心跳量出来的偏差都更像「脱节」，
    // 硬跳 → 重开解码器 → 更卡。这是正反馈，也是「网络差的时候被强行拉到播放进度」的主要来源。
    // 真卡住时该等它自己缓过来，而不是拿播放头去追一个它还没取到的位置。
    //
    // 但也不能无限等：连冻 {@link #STALL_RELEASE_HITS} 发（约 6 秒完全没出声）就说明这条流已经
    // 死了，此时重新开流正是唯一自救手段 —— 把判定放回去，让正常的硬跳路径去救它。

    /** 两次采样的最小间隔：太密的采样量不出「这一段时间里它到底走没走」。 */
    private static final long STALL_MIN_GAP_MS = 800L;

    /** 这么长的间隔里位置只推进了这么多以内，判为冻住。 */
    private static final long STALL_MAX_ADVANCE_MS = 150L;

    /** 连冻几发之后不再当缓冲、放行硬跳去重开这条流。 */
    private static final int STALL_RELEASE_HITS = 3;

    private static long stallSample = -1L;
    private static long stallSampleAt;
    private static int stallHits;
    /**
     * 已经判过「这条流死了」。
     *
     * <p>置位之后就不再把冻住的读数当缓冲 —— 否则每三发释放一次、每一发释放前又拿缓冲当借口
     * 重开静默窗，一条真死了的流永远等不到那次「重开解码器」的自救。清掉它的唯一条件是
     * 播放头真的又开始往前走了。</p>
     */
    private static boolean stallReleased;

    /** 应用服务端下发的阈值；任一项 &lt;= 0 视为「未下发」，保留当前值。 */
    public static void configure(long soft, long hard, long cooldown, int wantHits, long grace) {
        if (soft > 0L) softDriftMs = soft;
        if (hard > 0L) hardDriftMs = hard;
        if (cooldown > 0L) cooldownMs = cooldown;
        if (wantHits > 0) confirmHits = wantHits;
        if (grace >= 0L) graceMs = grace;
        // 服主把配置改成自相矛盾时兜一下，否则软档会把硬档整个吃掉。
        if (hardDriftMs < softDriftMs + 1000L) hardDriftMs = softDriftMs + 1000L;
    }

    public static long softDriftMs() {
        return softDriftMs;
    }

    public static long hardDriftMs() {
        return hardDriftMs;
    }

    /**
     * 记一次明确命令（换歌／暂停／恢复／拖进度／本地硬跳）。
     *
     * <p>清空确认计数并开一段静默期：命令刚下去时播放器正在解析／下载／重设播放头，本地位置
     * 必然落后，这段时间量出来的偏差不是「脱节」而是「还没开起来」。</p>
     */
    public static void markCommand() {
        markCommand(0L);
    }

    /**
     * 同上，但把静默期按一次<b>已知的过渡长度</b>来开：取 {@code protectMs} 与
     * {@code graceMs} 里较远的那个，并夹到 {@link #MAX_PROTECT_MS}。
     *
     * <p><b>为什么固定窗盖不住 automix。</b>房间里的交叠长度由音频内容决定（{@code overlapMillis}，
     * 长尾奏能挑到 20 秒以上），而 {@code graceMs} 跟着服务端的配置提前量走（默认 4000 → 抬到
     * 5000）。窗只开 5 秒、淡入还在继续时心跳就恢复了：此时量出来的「偏差」其实是本机比房间
     * 晚了几秒开始淡入（帧延迟 + 一条本来就落在软档里没纠的漂移 + 新 deck 的缓冲），不是脱节，
     * 拿去比硬阈值就会把刚叠上的那一条薅回去 —— 听感上正是交叠被截断。</p>
     */
    public static synchronized void markCommand(long protectMs) {
        hits = 0;
        long window = graceMs;
        if (protectMs > window) window = Math.min(protectMs, MAX_PROTECT_MS);
        graceUntil = System.currentTimeMillis() + window;
    }

    /** 换房间／断线：策略状态和时钟偏移都要清掉，别把上一台服务器的偏移带过来。 */
    public static synchronized void reset() {
        hits = 0;
        lastHardSeekAt = 0L;
        graceUntil = 0L;
        stallSample = -1L;
        stallSampleAt = 0L;
        stallHits = 0;
        stallReleased = false;
        curMin = Long.MAX_VALUE;
        prevMin = Long.MAX_VALUE;
        windowStart = 0L;
    }

    /** 当前是否处在命令静默期内（此期间不做任何漂移判定）。 */
    public static boolean inGrace() {
        return System.currentTimeMillis() < graceUntil;
    }

    /**
     * 静默期还剩多久（毫秒）；已经不在窗内时为 0。
     *
     * <p>诊断用：交接那一下打一行，就能在真机日志里看出这段保护到底盖住了多长的交叠 ——
     * 窗开短了就是「淡入还没结束就被心跳薅断」。</p>
     */
    public static synchronized long graceRemainingMs() {
        long left = graceUntil - System.currentTimeMillis();
        return left > 0L ? left : 0L;
    }

    /**
     * 采一次本机播放头，问一句「这条流真的在走吗」。
     *
     * <p>判据只有位置本身：要求它播着（调用方负责别在暂停态问），却在一发心跳的间隔里几乎
     * 没推进。这种情况把偏差拿去比硬阈值是自伤 —— 缓冲期的位置本来就还没取到，跳过去只是
     * 把解码器重开一遍，然后下一发量出更大的偏差。详见上面的卡流注释。</p>
     *
     * @param localMs 本机当前位置；负数（读不到）当没有样本
     * @return true 表示这一发的偏差是缓冲造成的，不要拿去判脱节
     */
    public static synchronized boolean isStalled(long localMs) {
        long now = System.currentTimeMillis();
        if (localMs < 0L) {
            stallSample = -1L;
            stallHits = 0;
            stallReleased = false;
            return false;
        }
        long previous = stallSample;
        long gap = now - stallSampleAt;
        stallSample = localMs;
        stallSampleAt = now;
        // 没有前一发、或者这一发离上一发太近：量不出「这段时间里到底走了几步」。
        if (previous < 0L || gap < STALL_MIN_GAP_MS) {
            stallHits = 0;
            return false;
        }
        long advanced = localMs - previous;
        if (advanced > STALL_MAX_ADVANCE_MS) {
            // 真的在推进 —— 缓冲到货、以及那次自救硬跳重新开起来都算，判死的账在这里清。
            stallHits = 0;
            stallReleased = false;
            return false;
        }
        if (advanced < 0L) {
            stallHits = 0;            // 往回走是拖进度或换曲（新曲目从 0 起），不是卡住
            return false;
        }
        if (stallReleased) return false;
        if (++stallHits < STALL_RELEASE_HITS) return true;
        stallReleased = true;         // 连冻三轮：剩下的自救路只有把流重开一遍
        stallHits = 0;
        return false;
    }

    /**
     * 把服务端报告的位置修正到「现在」。
     *
     * <p>{@code localNow - serverTime} 里超出窗口最小偏移的部分是本帧的额外延迟，可以加回位置；
     * 但只加不超过 {@link #MAX_LAG_MS} 的部分，剩下的当抖动丢弃。</p>
     */
    public static synchronized long compensate(long pos, long serverTime) {
        if (serverTime <= 0L) return pos;
        long now = System.currentTimeMillis();
        long delta = now - serverTime;

        if (windowStart == 0L) windowStart = now;
        if (now - windowStart >= OFFSET_WINDOW_MS) {
            // 滚动一格：当前桶变成上一桶，新桶从头开始收集。
            prevMin = curMin;
            curMin = Long.MAX_VALUE;
            windowStart = now;
        }
        if (delta < curMin) curMin = delta;

        long offset = Math.min(curMin, prevMin);
        long lag = delta - offset;
        if (lag < 0L) lag = 0L;
        if (lag > MAX_LAG_MS) lag = MAX_LAG_MS;
        return pos + lag;
    }

    /**
     * 该不该为这个偏差做一次硬跳。
     *
     * @param driftMs 本地位置减服务端位置，可正可负
     * @return true 表示批准硬跳，同时内部已记账（开冷却和静默期）
     */
    public static synchronized boolean shouldHardSeek(long driftMs) {
        long now = System.currentTimeMillis();
        if (now < graceUntil) return false;            // 还在开流／重设播放头，别添乱
        long abs = driftMs < 0L ? -driftMs : driftMs;
        if (abs <= hardDriftMs) {
            hits = 0;                                   // 一次没越线就重新数
            return false;
        }
        if (++hits < confirmHits) return false;          // 单帧越线可能只是抖动
        if (lastHardSeekAt > 0L && now - lastHardSeekAt < cooldownMs) return false;
        hits = 0;
        lastHardSeekAt = now;
        graceUntil = now + graceMs;                      // 跳完要重新缓冲，先别再量
        return true;
    }
}
