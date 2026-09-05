package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.animation.spring.SpringParams;

import java.util.List;

/**
 * 「逐行滚动」样式的滚动动效调度器，逐条照搬 modernMP：策略取自它的 {@code LyricSprings}，
 * 时机与门禁取自它 {@code MusicPlayerGui.drawLyricsArea} 里的弹簧段。两者的源头是 AMLL
 * （applemusic-like-lyrics），也就是 Apple Music 的那套观感。
 *
 * <p>只管运动，不管几何：行高、行距、阅读位置、对唱抬升全部由 {@link MusicLyricsPanel} 算好，
 * 以「滚动偏移 {@code restOffset} + 文档坐标 {@code docTop[]}」的形式传进来，本类一个像素都不
 * 会挪动它们。因此换掉这一层不会改变任何一帧的最终停留位置。</p>
 *
 * <h2>为什么坐标要拆成两半</h2>
 * <p>modernMP 判断「布局变了」用的是 {@code restOffset} 这<b>一个标量</b>，不是逐行比较目标。
 * 这不是省事：行高是异步加载字形之后才算准的，逐行比较会让锚点之后任何一行的高度变化都触发
 * 一次全量重新下发——而重新下发会 {@code resetSolver()} 并把级联队列整个覆盖，于是级联永远
 * 等不到倒计时结束，弹簧也每帧被重新起解。表现出来就是字在原地来回抖。</p>
 *
 * <h2>三种时机对应三套参数</h2>
 * <ul>
 *   <li><b>正常换行</b>：按上一行结束到本行开始的<b>句间隔</b>取阻尼比与响应时间——间隔越长，
 *       走得越慢越松，读起来像"歌停下来了所以字也慢下来"；纯 LRC 没有可信的句间隔，退回固定
 *       的一组。</li>
 *   <li><b>本行快唱完了</b>：改用临界阻尼包络，保证在下一行到来之前一定停稳，不会带着残余
 *       速度撞进下一次换行。</li>
 *   <li><b>时钟硬跳变</b>（seek / 切歌 / 外部同步）：按跨度分档，大跨度用快而不过冲的一组，
 *       小跨度用 0.1 秒的临界包络，同时关掉级联——seek 时逐行错落只会看起来像卡顿。</li>
 * </ul>
 */
final class LyricScrollMotion {

    private static final double TAU = Math.PI * 2;
    private static final double LOG_ONE_PERCENT = -Math.log(0.01);
    /** 级联步长：相邻两行的出发时间差（秒）。modernMP {@code LyricSprings.CASCADE_STEP}。 */
    private static final double CASCADE_STEP = 0.05;
    /**
     * 单帧时间上限（秒）。掉帧时如果把真实 dt 直接喂给弹簧，闭式解会在一帧里跨过大半个周期，
     * 欠阻尼分支的 cos 项直接跳到反相位——表现为卡一下之后歌词"弹回去"。
     */
    private static final double MAX_FRAME_SECONDS = 0.05;
    /** 「布局变了」的判定阈值（像素），照 modernMP 的 0.5f。 */
    private static final double LAYOUT_EPSILON = 0.5;

    /** 一组弹簧参数：质量 / 阻尼 / 刚度。对应 modernMP 的 {@code LyricSprings.Physics}。 */
    private static final class Physics {
        final double mass, damping, stiffness;

        Physics(double mass, double damping, double stiffness) {
            this.mass = mass;
            this.damping = damping;
            this.stiffness = stiffness;
        }

        SpringParams toParams() {
            return new SpringParams(mass, damping, stiffness, false);
        }
    }

    /** 纯 LRC 换行与大跨度 seek 共用的一组：ζ=0.9、周期 0.63s，快、几乎不过冲。 */
    private static final Physics STIFF = new Physics(1, 18, 100);

    // ── 帧间状态（对应 modernMP 的 lastFramePosMs / springAnchorPrev / lastRestOffset） ──
    private long springLastNs = 0L;
    /** 上一帧的播放位置（ms），负值表示还没有参考帧。 */
    private double lastPlayheadMs = -1.0;
    private int springAnchorPrev = -1;
    private double lastRestOffset = Double.NaN;
    /** 对应 modernMP 的 {@code lineSpringInit}：还没喂过初值时用硬吸附而不是弹簧。 */
    private boolean springInit = false;

    /**
     * 「还是同一份歌词吗」的指纹。换歌时 {@link LyricLine} 是全新对象、弹簧从 0 开始，不认出
     * 这件事的话新歌第一帧整屏文字会从画面顶端飞下来。modernMP 那边靠 {@code lineSpring.length
     * != n} 重建数组来达到同样效果。
     */
    private int trackSize = -1;
    private long trackFirstTimestamp = Long.MIN_VALUE;
    private long trackLastTimestamp = Long.MIN_VALUE;
    private boolean timedCached = false;

    /** 换歌 / 重开歌词页：丢掉上一首的时间轴与布局指纹，避免拿旧锚点做一次误判。 */
    void reset() {
        springLastNs = 0L;
        lastPlayheadMs = -1.0;
        springAnchorPrev = -1;
        lastRestOffset = Double.NaN;
        springInit = false;
        trackSize = -1;
        trackFirstTimestamp = Long.MIN_VALUE;
        trackLastTimestamp = Long.MIN_VALUE;
        timedCached = false;
    }
    /**
     * 锚点去抖，在算 {@code restOffset} 之前调用。
     *
     * <p>modernMP 的 {@code activeIdx} 是每帧从本帧 {@code posMs} 现算的纯函数，同一个时钟值必然
     * 得到同一行。Mu 这边当前行是 {@code CloudMusic.currentLyric} 这个共享字段，由一条 10ms 的
     * 音频监督线程反复改写，而 {@code findCurrentLyric} 的"提前跳到下一行"是个单向阈值比较：
     * 音频时钟在阈值附近抖一下，当前行就会在相邻两行之间来回。那会让下面每一帧都判成换行——
     * 每帧重设参数、重下发目标、把级联队列冲掉，看起来就是字在原地来回抖。</p>
     *
     * <p>正常播放锚点只会前进，所以非 seek 的后退一律不认；真正的 seek（回退超过 1 秒、跨度
     * 超过 1.5 秒等）由 {@link #isHardClockDiscontinuity} 兜住，仍然照常回退。</p>
     */
    int stabilizeAnchor(int anchorIndex, int count, double playheadMs) {
        // 歌词换了一份：历史锚点是上一首的下标，没有任何参考价值。
        if (count != trackSize || springAnchorPrev < 0 || lastPlayheadMs < 0.0) return anchorIndex;
        if (anchorIndex >= springAnchorPrev) return anchorIndex;
        boolean seek = isHardClockDiscontinuity(lastPlayheadMs / 1000.0, playheadMs / 1000.0);
        return seek ? anchorIndex : springAnchorPrev;
    }

    /**
     * 推进一帧。整个方法体是 modernMP {@code drawLyricsArea} 里那段弹簧逻辑的逐条搬运。
     *
     * @param lines        当前歌词全表，下标与 {@code docTop} 一一对应
     * @param docTop       每一行在<b>文档坐标</b>里的顶边（只由行高与行距决定，与播放位置无关）
     * @param restOffset   文档坐标到屏幕坐标的滚动偏移：{@code 屏幕Y = restOffset + docTop[i]}
     * @param anchorIndex  当前行（对唱组已归一到组首）的下标
     * @param playheadMs   本帧的播放位置，拖动进度条时是预览位置
     * @param viewportTop  歌词视口上边界，只用来找级联从哪一行起算
     * @param manualScroll 用户正在滚动歌词或拖动进度条
     */
    void apply(List<LyricLine> lines, double[] docTop, double restOffset, int anchorIndex,
               double playheadMs, double viewportTop, boolean manualScroll) {
        int count = Math.min(lines.size(), docTop.length);
        if (count <= 0 || anchorIndex < 0 || anchorIndex >= count) return;

        // ── 弹簧时间步长（modernMP 用 System.nanoTime 直接量，不走渲染层的帧计时器） ──
        long nowNs = System.nanoTime();
        double springDt = springLastNs == 0L ? 0.0 : (nowNs - springLastNs) / 1_000_000_000.0;
        if (springDt > MAX_FRAME_SECONDS) springDt = MAX_FRAME_SECONDS;
        if (springDt < 0.0) springDt = 0.0;
        springLastNs = nowNs;

        // ── 时钟硬跳变 = seek。看时间差而不是下标差：长间奏里连跨好几行也可能是正常播放，
        //    而重新解析一次歌词会让下标突变但时间根本没动。 ──
        double posSec = playheadMs / 1000.0;
        boolean seek = lastPlayheadMs >= 0.0
                && isHardClockDiscontinuity(lastPlayheadMs / 1000.0, posSec);
        double seekDeltaSeconds = lastPlayheadMs >= 0.0 ? posSec - lastPlayheadMs / 1000.0 : 0.0;
        lastPlayheadMs = playheadMs;

        if (trackChanged(lines, count)) springInit = false;

        boolean anchorMoved = anchorIndex != springAnchorPrev;
        boolean layoutMoved = Double.isNaN(lastRestOffset)
                || Math.abs(restOffset - lastRestOffset) > LAYOUT_EPSILON;

        if (anchorMoved || layoutMoved || seek) {
            int oldAnchor = springAnchorPrev;
            springAnchorPrev = anchorIndex;
            lastRestOffset = restOffset;

            Physics physics;
            boolean continuous = false;
            if (seek) {
                physics = seekSpring(anchorIndex - oldAnchor, seekDeltaSeconds);
            } else {
                physics = lineTransitionSpring(timedCached, interLineGap(lines, anchorIndex));
                LyricLine anchor = lines.get(anchorIndex);
                double lineEndSeconds = (anchor.timestamp + Math.max(anchor.duration, 0L)) / 1000.0;
                continuous = !manualScroll && lineEndSeconds - posSec - 0.5 < 0.6;
                physics = retimeLineSpring(physics, lineEndSeconds, posSec, continuous);
            }

            if (anchorMoved || seek) {
                SpringParams params = physics.toParams();
                for (int i = 0; i < count; i++) {
                    lines.get(i).spring.setParams(params);
                }
            }

            // 级联：Y 方向的延迟 = continuous ? 0 : max(有效距离-1,0)·0.05；手动滚动 / seek 全 0。
            // 起点是视口内第一行，所以整屏歌词自上而下依次落位，而不是像一整块面板那样平移。
            int cascadeAnchor = firstVisibleLine(lines, docTop, count, restOffset, viewportTop, anchorIndex);
            boolean disabled = manualScroll || seek;
            for (int i = 0; i < count; i++) {
                double restTop = restOffset + docTop[i];
                double delay = continuous ? 0.0 : cascadeDelay(validLineDistance(cascadeAnchor, i), disabled);
                if (springInit) lines.get(i).spring.setTargetPosition(restTop, delay);
                else lines.get(i).spring.setPosition(restTop);
            }
            springInit = true;
        }

        for (int i = 0; i < count; i++) {
            LyricLine line = lines.get(i);
            if (springDt > 0.0) line.spring.update(springDt);
            line.posY = line.spring.getCurrentPosition();
        }
    }

    /**
     * 歌词是否换成了另一份。顺带在这里刷新"有没有逐字时间"的缓存：那是一次全表扫描，
     * 每帧做一遍纯属浪费，而它只可能随歌词整体变化。
     */
    private boolean trackChanged(List<LyricLine> lines, int count) {
        long first = count > 0 ? lines.get(0).timestamp : Long.MIN_VALUE;
        long last = count > 0 ? lines.get(count - 1).timestamp : Long.MIN_VALUE;
        if (count == trackSize && first == trackFirstTimestamp && last == trackLastTimestamp) return false;
        trackSize = count;
        trackFirstTimestamp = first;
        trackLastTimestamp = last;
        timedCached = LyricParser.hasWordByWordTiming(lines);
        return true;
    }

    /** 级联锚点：视口内第一个可见行。照搬 modernMP {@code firstVisibleLineIndex}。 */
    private static int firstVisibleLine(List<LyricLine> lines, double[] docTop, int count,
                                       double restOffset, double viewportTop, int fallback) {
        for (int i = 0; i < count; i++) {
            if (restOffset + docTop[i] + lines.get(i).height > viewportTop) return i;
        }
        return Math.max(fallback, 0);
    }

    /** 上一行唱完到这一行开始之间的空白（秒）。第一行没有上一行，按 0 算。 */
    private static double interLineGap(List<LyricLine> lines, int index) {
        if (index <= 0) return 0.0;
        LyricLine previous = lines.get(index - 1);
        LyricLine current = lines.get(index);
        long previousEnd = previous.timestamp + Math.max(previous.duration, 0L);
        return Math.max(current.timestamp - previousEnd, 0L) / 1000.0;
    }

    /**
     * 有效行距离。modernMP 在这里跳过 TTML 的背景和声行，因为那些行不参与布局；Mu 的经典样式
     * 里每一行都占布局，所以就是下标差。
     */
    private static int validLineDistance(int first, int second) {
        if (first < 0 || second < 0) return 0;
        return Math.abs(second - first);
    }
    // ── 弹簧策略（逐条照搬 modernMP LyricSprings / AMLL） ───────────────────────

    private static double clamp(double value, double low, double high) {
        return value < low ? low : value > high ? high : value;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** 阻尼比 + 响应时间 → m/k/c：ω=2π/T，k=m·ω²，c=2·ζ·m·ω。 */
    private static Physics responsePhysics(double dampingRatio, double response, double mass) {
        double omega = TAU / Math.max(response, 0.001);
        return new Physics(mass, 2 * dampingRatio * mass * omega, mass * omega * omega);
    }

    /** 句间隔驱动的行切换弹簧：gap 0.20→0.75s → ζ 0.90→0.78、T 0.48→0.75s。 */
    private static Physics gapDrivenLineSpring(double interLineGap) {
        double amount = clamp((interLineGap - 0.20) / 0.55, 0, 1);
        return responsePhysics(lerp(0.90, 0.78, amount), lerp(0.48, 0.75, amount), 1);
    }

    /** 行切换弹簧：有逐字时间 → gap 驱动；纯 LRC → 固定 1/18/100。 */
    private static Physics lineTransitionSpring(boolean hasTimedSyllables, double interLineGap) {
        return hasTimedSyllables ? gapDrivenLineSpring(interLineGap) : STIFF;
    }

    /** 临界包络：rate = ln(100)/T，k = m·rate²，c = 2·m·rate。 */
    private static Physics criticalEnvelopeSpring(double mass, double response) {
        double rate = LOG_ONE_PERCENT / Math.max(response, 0.01);
        return new Physics(mass, 2 * mass * rate, mass * rate * rate);
    }

    /** 临近行重调：continuous → 临界 T=0.3；否则按剩余时间窗。 */
    private static Physics retimeLineSpring(Physics physics, double lineEnd, double playhead, boolean continuous) {
        if (continuous) return criticalEnvelopeSpring(physics.mass, 0.30);
        double remaining = lineEnd - playhead - 0.50;
        double naturalRate = Math.sqrt(physics.stiffness / physics.mass);
        double dampingRatio = physics.damping / (2 * Math.sqrt(physics.mass * physics.stiffness));
        double oldEnvelope = LOG_ONE_PERCENT / (naturalRate * clamp(dampingRatio, 0.10, 1));
        if (remaining < 0.80 && oldEnvelope - remaining < -0.05) {
            double response = Math.max(0.01, Math.max(remaining - 0.40, 0.30));
            return criticalEnvelopeSpring(physics.mass, response);
        }
        return physics;
    }

    private static boolean isLargeSeek(int indexDelta, double timeDelta) {
        return Math.abs(indexDelta) > 3 || Math.abs(timeDelta) > 2;
    }

    /** 时钟硬跳变（seek / 回退 / 循环重播检测）。 */
    private static boolean isHardClockDiscontinuity(double oldTime, double newTime) {
        return Math.abs(newTime - oldTime) >= 1.5
                || oldTime - newTime > 1
                || (oldTime > 5 && newTime < oldTime * 0.1)
                || Math.abs(newTime - oldTime) > 2;
    }

    /** seek 弹簧：大跨度 → 固定 1/18/100；短距 → 临界 T=0.1（m=2）。 */
    private static Physics seekSpring(int indexDelta, double timeDelta) {
        return isLargeSeek(indexDelta, timeDelta) ? STIFF : criticalEnvelopeSpring(2, 0.10);
    }

    /** 级联延迟：max(有效距离-1, 0)·0.05s；禁用（手动滚动 / seek）时 0。 */
    private static double cascadeDelay(int validDistance, boolean disabled) {
        return disabled ? 0 : Math.max(validDistance - 1, 0) * CASCADE_STEP;
    }
}