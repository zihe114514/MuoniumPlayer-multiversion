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
    public static synchronized void markCommand() {
        hits = 0;
        graceUntil = System.currentTimeMillis() + graceMs;
    }

    /** 换房间／断线：策略状态和时钟偏移都要清掉，别把上一台服务器的偏移带过来。 */
    public static synchronized void reset() {
        hits = 0;
        lastHardSeekAt = 0L;
        graceUntil = 0L;
        curMin = Long.MAX_VALUE;
        prevMin = Long.MAX_VALUE;
        windowStart = 0L;
    }

    /** 当前是否处在命令静默期内（此期间不做任何漂移判定）。 */
    public static boolean inGrace() {
        return System.currentTimeMillis() < graceUntil;
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
