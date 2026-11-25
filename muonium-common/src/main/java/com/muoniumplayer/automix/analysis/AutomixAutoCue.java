package com.muoniumplayer.automix.analysis;

import com.muoniumplayer.automix.config.AutomixSettings;
import com.muoniumplayer.automix.planning.AutomixPlan;
import com.muoniumplayer.automix.planning.FoliaAutomixPlanner;

import java.util.Locale;

/**
 * Folia 普通模式的配对选点器：把 A 的退场点和 B 的进场点作为一组候选共同枚举、评分，必要时沿用基线。
 * 房间模式仅由权威分析端调用；其他成员执行共享计划。
 *
 * <p>Folia 原启发式分别从短尾窗与前奏长度选点，未比较两侧可能相连的结构。这里用已缓存的全曲
 * 包络和节拍/profile 锚点联合搜索，找不到有充分证据且优于基线的接缝时沿用 Folia 基线。</p>
 *
 * <h2>选择约束</h2>
 * <ol>
 *   <li>启发式计划保留为候选基线，采用门槛始终与其比较。</li>
 *   <li>更早退场意味着吞歌，因此按被跳过时长提高采用线；偏晚没有惩罚。</li>
 *   <li>缺证据的评分项连同权重一起从分母移除，并另设最低测量权重。</li>
 *   <li>本类只消费调用方提供的包络、网格和 profile，不做音频 I/O 或现场扫描。</li>
 * </ol>
 */
public final class AutomixAutoCue {

    /** 十项音频评分；尾段保留仅用于名次先验。 */
    public static final double ABSTAIN_SCORE = 0.56;

    /** 每个接缝必须量到的最低证据权重；尾段先验不计入。 */
    static final double MIN_MEASURED_WEIGHT = 5.0;

    /**
     * 尾段保留这项的尺度：出场点之后还剩这么多秒就一分不给。
     *
     * <p>取 120 秒是为了让「自然收尾」与「砍掉半首」拉开：正常交接留下的尾巴只有交叠那几秒，
     * 这一项接近 1；中段退场在四分钟的歌上直接归零。它不是一项「偏好长歌」的口味判断，
     * 而是把「用户点进来的这首歌会不会被吃掉」这件事放进分数里。</p>
     */
    private static final double TAIL_KEEP_SECONDS = 120.0;

    /** 每比启发式多吃掉一分钟，采纳线往上抬这么多。 */
    private static final double EARLY_CUT_MARGIN_PER_MINUTE = 0.05;
    /** 上面那条的上限；再早也只要求这么多，避免出现「永远不可能采纳」。 */
    private static final double EARLY_CUT_MARGIN_CEILING = 0.20;

    /** 两侧密集回退网格各自最多保留的候选点；长曲仍由结构锚点代表。 */
    static final int MAX_SEARCH_CANDIDATES = 256;
    /** 细排短名单：先按局部电平/动态、低频相容和结构位置剪枝，再跑十项评分。 */
    static final int SHORTLIST = 96;

    /** 窗内最少帧数；少于此形状相关不可信，该项按缺料处理。 */
    private static final int MIN_SHAPE_FRAMES = 20;
    /** 形状相关只比窗口开头这几秒——比完整窗口更稳，且四分钟的歌不必整段比。 */
    private static final double SHAPE_SPAN_SECONDS = 4.0;
    private static final int STRUCTURE_BAR_EVERY = 8;
    private static final long STRUCTURE_MAX_GAP_MILLIS = 40_000L;
    private static final long STRUCTURE_JUMP_HALF_WINDOW_MILLIS = 500L;
    private static final double STRUCTURE_JUMP_MIN_DELTA = 0.05;
    private static final int MAX_STRUCTURAL_CANDIDATES = 256;
    private static final double STRUCTURE_BONUS_WEIGHT = 1.0;
    /** 出场侧至少要给包络留下这些毫秒，否则窗口会越过文件末尾。 */
    private static final long TAIL_GUARD_MILLIS = 2_000L;
    /** 进场侧最多扫到这里，与 {@code FoliaAutomixPlanner} 只看头 30 秒的旧约束相容。 */
    private static final long ENTRY_SCAN_LIMIT_MILLIS = 25_000L;
    /** 出场点不早于这两者中的较大值：保留必要的最短曲内位置及播放头安全余量。 */
    private static final long MIN_CUT_MILLIS = 20_000L;
    /** 至少播放的比例；候选排序可以在它之后挑点，但不能越过它。 */
    static final double MIN_CUT_FRACTION = FoliaAutomixPlanner.MIN_PLAYED_FRACTION;
    /** 候选窗长相对用户设定的倍率；两端都夹在 {@link AutomixSettings} 的 overlap 上下限内。 */
    private static final double[] WINDOW_SCALES = {0.5, 0.75, 1.0, 1.5, 2.0};

    /** 可用的音频接缝证据项：形状、响度、ripple、高低频、动态、拍点、过渡弧、人声。 */
    static final double[] WEIGHTS = {
            1.60,   // ① 细包络形状与节奏接缝
            1.20,   // ② 电平连续性
            0.80,   // ③ 等功率混合 ripple
            0.70,   // ④ 高频互补
            0.80,   // ⑤ 起声密度/动态相容
            0.50,   // ⑥ 拍点漂移
            0.35,   // ⑦ 出场下降、进场上升
            0.90,   // ⑧ 人声尾句冲突
            0.90,   // ⑨ 低频相容
            1.00    // ⑩ 尾段保留先验
    };
    static final double WEIGHT_SUM = 8.75;

    /** 尾段保留只参与候选偏好，不计入音频证据或弃权分。 */
    static final int TAIL_TERM = 9;
    static final double EVIDENCE_WEIGHT_SUM = WEIGHT_SUM - WEIGHTS[TAIL_TERM];

    private AutomixAutoCue() {
    }

    /** 一次选点所需的全部原料，由调用方备齐。 */
    public static final class Request {
        public AutomixEnvelope outgoing;
        public AutomixEnvelope incoming;
        public AutomixAnalyzer.BeatGrid outgoingGrid;
        public AutomixAnalyzer.BeatGrid incomingGrid;
        /** Curve-local time zeroes for the two measured grids. */
        public long outgoingGridOriginMillis;
        public long incomingGridOriginMillis;
        /** 进场曲前奏结束（{@code leadInMillis}），用于铺进场候选。 */
        public long introEndMillis = -1L;
        /** 出场曲<b>歌词</b>最后一次人声结束；没有歌词为 -1。 */
        public long lastVocalEndMillis = -1L;
        /** 出场曲<b>分轨证据</b>的人声结束；没测到为 -1。 */
        public long stemVocalEndMillis = -1L;
        /** 出场/进场 profile 的段落边界，可空。 */
        public long[] outgoingSectionMillis;
        public long[] incomingSectionMillis;
        /** 原启发式给出的三个位置，作为候选之一进入比较。 */
        public long heuristicFireMillis;
        public long heuristicCueMillis;
        public long heuristicOverlapMillis;
        /** 用户配置的交叠长度，窗口候选以它为基准。 */
        public long preferredOverlapMillis;
        /**
         * 出场点的最早可能位置：规划那一刻<b>出场曲的播放头</b>（调用方已含安全余量）。
         *
         * <p>没有它，搜索会挑出一个已经播过去的时间点——交接逻辑下一秒就会发现「位置已过
         * 点火点」，于是混音在任意一处立刻开始，听起来像在歌中间硬切了一下。整曲包络能让我们
         * 看见更丰富的接缝，但能选的范围仍然只能是<b>未来</b>。</p>
         */
        public long minimumFireMillis;
        public boolean beatAlignEnabled = true;
    }

    /** 选点结论。全部是曲目内的绝对毫秒。 */
    public static final class Result {
        public long fireMillis;
        public long cueMillis;
        public long overlapMillis;
        public double score;
        /** 名次分 = 音频证据分加尾段保留先验，只用于候选排序。 */
        public double preference;
        /** 这一对实际量到的权重之和；缺料的项连同其权重一起不在里面。见 {@link #MIN_MEASURED_WEIGHT}。 */
        public double measuredWeight;
        /** 与 {@link #score} 同式的 Folia 启发式基线分。 */
        public double baselineScore;
        /**
         * 采纳真正要越过的线：默认就是 {@link #baselineScore}，但如果这一对退场<b>比启发式更早</b>
         * （会多吃掉出场曲的一段），它按分钟往上抬。见类注释第二条规矩。
         */
        public double requiredScore;
        /** 这一退场之后被跳过的那一段有多长；启发式对照位的同一量用作基准。 */
        public long discardedMillis;
        /** 逐项得分，仅用于诊断输出。 */
        public String detail;
        /** 采用/弃权的成句说明。 */
        public String note = "";

        public boolean isAdopted() {
            // 三条都要成立：过弃权线、真的量够了东西、并且越过它要替换掉的那个位置的采纳线。
            return score >= ABSTAIN_SCORE && measuredWeight >= MIN_MEASURED_WEIGHT && score >= requiredScore;
        }
    }

    /**
     * 找一对最佳接缝。
     *
     * @return 结论；{@code null} 表示原料不足（缺包络、时长不合法、窗口一个都放不下）。
     *         注意<b>弃权不是 null</b>：弃权会返回一个 {@code score < ABSTAIN_SCORE} 的结论，
     *         因为调用方需要那个分数去打日志。
     */
    public static Result plan(Request request) {
        if (request == null || request.outgoing == null || request.incoming == null
                || !request.outgoing.isUsable() || !request.incoming.isUsable()) return null;
        long outgoingDuration = request.outgoing.durationMillis;
        long incomingDuration = request.incoming.durationMillis;
        if (outgoingDuration <= 0L || incomingDuration <= 0L) return null;

        long cutEarliest = earliestCutMillis(outgoingDuration, request.minimumFireMillis);
        long baselineOverlap = Math.max(0L, Math.min(request.heuristicOverlapMillis,
                outgoingDuration - cutEarliest - TAIL_GUARD_MILLIS));
        long incomingBaselineOverlap = Math.max(0L, Math.min(baselineOverlap,
                incomingDuration - TAIL_GUARD_MILLIS));
        baselineOverlap = Math.min(baselineOverlap, incomingBaselineOverlap);
        long baselineLatestFire = outgoingDuration - baselineOverlap - TAIL_GUARD_MILLIS;
        long baselineFire = baselineOverlap >= AutomixSettings.MIN_OVERLAP_SECONDS * 1000L
                ? clamp(request.heuristicFireMillis, cutEarliest, baselineLatestFire) : -1L;
        long baselineCue = baselineOverlap >= AutomixSettings.MIN_OVERLAP_SECONDS * 1000L
                ? clamp(request.heuristicCueMillis, 0L,
                        incomingDuration - baselineOverlap - TAIL_GUARD_MILLIS) : -1L;
        Result baseline = baselineFire >= 0L && baselineCue >= 0L
                ? score(request, baselineFire, baselineCue, baselineOverlap, false) : null;
        if (baseline != null) baseline.requiredScore = baseline.score;
        long[] windows = candidateWindows(request, cutEarliest);
        if (windows.length == 0) return baseline;
        long widest = windows[windows.length - 1];

        // 不能选已经播过去的位置。规划发生在预备的那一刻，出场曲的播放头已经在那儿了——
        // 整曲包络放开的是「往前找落点」，不是「往回找落点」：一个落在播放头之后的
        // fireMillis 会让 `position < plan.fireMillis` 当场为假，交接在任意位置立刻开始。
        cutEarliest = Math.max(cutEarliest, request.minimumFireMillis);
        long cutLatest = outgoingDuration - widest - TAIL_GUARD_MILLIS;
        long entryEarliest = 0L;
        long entryLatest = Math.min(ENTRY_SCAN_LIMIT_MILLIS, incomingDuration - widest - TAIL_GUARD_MILLIS);
        if (cutLatest < cutEarliest || entryLatest < entryEarliest) return baseline;

        long[] cuts = cutCandidates(request, cutEarliest, cutLatest, widest);
        long[] entries = entryCandidates(request, entryEarliest, entryLatest);
        if (cuts.length == 0 || entries.length == 0) return baseline;

        // 粗排（高频段）只估电平、动态、低频相容和结构位置；精排会对短名单计算十项加权计划。
        double[] coarse = new double[cuts.length * entries.length];
        long nominal = windows[windows.length / 2];
        for (int c = 0; c < cuts.length; c++) {
            for (int e = 0; e < entries.length; e++) {
                coarse[c * entries.length + e] = coarseScore(request, cuts[c], entries[e], nominal);
            }
        }
        int keep = Math.min(SHORTLIST, coarse.length);
        boolean[] taken = new boolean[coarse.length];
        int[] keepIndex = new int[keep];
        // 对照位与曲尾那两个退场点必须进细排，粗排不许把它们挤掉（见 reserveSeatsFor）。
        int filled = reserveSeatsFor(new long[]{baseline == null ? request.heuristicFireMillis : baseline.fireMillis,
                cutLatest}, cuts, coarse, entries.length, taken, keepIndex, keep);
        for (int k = filled; k < keep; k++) {
            int best = -1;
            double bestValue = -Double.MAX_VALUE;
            for (int i = 0; i < coarse.length; i++) {
                if (taken[i]) continue;
                if (coarse[i] > bestValue) {
                    bestValue = coarse[i];
                    best = i;
                }
            }
            if (best < 0) break;
            taken[best] = true;
            keepIndex[k] = best;
        }

        // 对照位沿用已经过范围验证的基线；候选排序和早退加价都与它比较。
        double baselineScore = baseline == null ? 0.0 : baseline.score;
        long baselineDiscarded = baseline == null ? 0L : baseline.discardedMillis;

        // 名次只在<b>量够了的那批</b>里比。这一步很要紧：缺料项会退出分母，于是
        // 「只量出一两项、恰好都好看」的一对能算出虚高的分数，把真正证据齐全的一对顶掉。
        // 一条都没量够时才退回全体最优，为的是日志里还能给出一个数，而不是为了采纳它。
        // 比的是「分数减掉早退场的加价」而不是裸分：多吃了 90 秒的那一对要先把这 90 秒的
        // 价钱付掉，才有资格跟更晚的一对排在一起——否则名次用一把尺、采纳线用另一把。
        Result best = null;
        Result bestMeasured = null;
        double bestRank = -Double.MAX_VALUE;
        double bestMeasuredRank = -Double.MAX_VALUE;
        for (int index : keepIndex) {
            long cut = cuts[index / entries.length];
            long entry = entries[index % entries.length];
            for (long window : windows) {
                Result candidate = score(request, cut, entry, window, true);
                if (candidate == null) continue;
                double rank = candidate.preference - earlyCutMargin(candidate.discardedMillis, baselineDiscarded);
                if (rank > bestRank) {
                    bestRank = rank;
                    best = candidate;
                }
                if (candidate.measuredWeight >= MIN_MEASURED_WEIGHT && rank > bestMeasuredRank) {
                    bestMeasuredRank = rank;
                    bestMeasured = candidate;
                }
            }
        }
        if (bestMeasured != null) best = bestMeasured;

        if (best == null) {
            // 一个候选都没算成（权重全为零）：把对照位原样交回去，它本来就是今天的行为。
            if (baseline != null) baseline.requiredScore = baseline.score;
            return baseline;
        }
        best.baselineScore = baselineScore;
        double margin = earlyCutMargin(best.discardedMillis, baselineDiscarded);
        best.requiredScore = baselineScore + margin;
        String why;
        if (best.score < ABSTAIN_SCORE) {
            why = "分数不足";
        } else if (best.measuredWeight < MIN_MEASURED_WEIGHT) {
            why = "没量够";
        } else if (best.score < best.requiredScore) {
            why = margin > 0.0 ? "早退场不够好" : "不如启发式";
        } else {
            why = "采纳";
        }
        best.note = String.format(Locale.ROOT, "autocue %.3f(%s)/启发式 %.3f%s/%s", best.score, why,
                baselineScore,
                margin > 0.0 ? String.format(Locale.ROOT, "，要求%.3f·多吃%d秒", best.requiredScore,
                        (best.discardedMillis - baselineDiscarded) / 1000L) : "",
                fmtWeight(best.measuredWeight));
        return best;
    }

    // ---------------------------------------------------------------------------------------------
    // 候选
    // ---------------------------------------------------------------------------------------------

    /**
     * 候选窗长：以用户设定为基准取 0.5..2 倍，两端夹在 automix 的交叠上下限内，
     * 并保证两侧都放得下。用户自己的那一个值<b>永远</b>在列表里，即使它落在倍率区间之外。
     */
    private static long[] candidateWindows(Request request, long cutEarliest) {
        long floor = (long) (AutomixSettings.MIN_OVERLAP_SECONDS * 1000.0);
        long ceiling = (long) (AutomixSettings.MAX_OVERLAP_SECONDS * 1000.0);
        long preferred = clamp(request.preferredOverlapMillis > 0L ? request.preferredOverlapMillis
                : request.heuristicOverlapMillis, floor, ceiling);
        long maxFit = Math.min(request.outgoing.durationMillis - cutEarliest - TAIL_GUARD_MILLIS,
                request.incoming.durationMillis - TAIL_GUARD_MILLIS);
        if (maxFit < floor) return new long[0];
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        for (double scale : WINDOW_SCALES) {
            long window = clamp(Math.round(preferred * scale), floor, Math.min(ceiling, maxFit));
            if (window >= floor) set.add(window);
        }
        long heuristic = clamp(request.heuristicOverlapMillis, floor, Math.min(ceiling, maxFit));
        if (heuristic >= floor) set.add(heuristic);
        if (set.isEmpty()) return new long[0];
        long[] out = new long[set.size()];
        int i = 0;
        for (long value : set) out[i++] = value;
        return out;
    }

    /** Structure and energy-change anchors inspired by qplayer's cue candidates. */
    static long[] structuralCandidates(Request request, boolean outgoing, long earliest, long latest) {
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        if (outgoing) {
            if (request.outgoingSectionMillis != null) {
                for (long section : request.outgoingSectionMillis) addIfInRange(set, section, earliest, latest);
            }
            addIfInRange(set, request.lastVocalEndMillis > 0L ? request.lastVocalEndMillis + 450L : -1L,
                    earliest, latest);
            addIfInRange(set, request.stemVocalEndMillis > 0L ? request.stemVocalEndMillis + 450L : -1L,
                    earliest, latest);
        } else if (request.incomingSectionMillis != null) {
            for (long section : request.incomingSectionMillis) addIfInRange(set, section, earliest, latest);
        }
        AutomixAnalyzer.BeatGrid grid = outgoing ? request.outgoingGrid : request.incomingGrid;
        long origin = outgoing ? request.outgoingGridOriginMillis : request.incomingGridOriginMillis;
        if (request.beatAlignEnabled && usable(grid)) {
            addEightBarCandidates(set, earliest, latest, origin, grid);
        }
        addEnergyJumpCandidates(set, outgoing ? request.outgoing : request.incoming, earliest, latest);
        long[] points = new long[set.size()];
        int index = 0;
        for (long value : set) points[index++] = value;
        return points;
    }

    private static void addEightBarCandidates(java.util.TreeSet<Long> set, long earliest, long latest,
                                              long originMillis, AutomixAnalyzer.BeatGrid grid) {
        double barMillis = grid.barPeriodMillis();
        double phraseMillis = barMillis * STRUCTURE_BAR_EVERY;
        if (!Double.isFinite(phraseMillis) || phraseMillis < 800.0 || phraseMillis > 160_000.0) return;
        double anchor = originMillis + grid.barPhaseMillis;
        long index = (long) Math.ceil((earliest - anchor) / phraseMillis);
        for (double candidate = anchor + index * phraseMillis;
             candidate <= latest + .5 && set.size() < MAX_STRUCTURAL_CANDIDATES;
             candidate = anchor + (++index) * phraseMillis) {
            addIfInRange(set, Math.round(candidate), earliest, latest);
        }
    }

    private static void addEnergyJumpCandidates(java.util.TreeSet<Long> set, AutomixEnvelope envelope,
                                                long earliest, long latest) {
        if (envelope == null || envelope.frameCount < 4) return;
        int half = Math.max(1, (int) (STRUCTURE_JUMP_HALF_WINDOW_MILLIS / AutomixEnvelope.HOP_MILLIS));
        int smooth = Math.max(1, (int) (1_000L / AutomixEnvelope.HOP_MILLIS));
        double[] level = new double[envelope.frameCount];
        double running = 0.0;
        for (int i = 0; i < level.length; i++) {
            running += envelope.rmsAt((long) i * AutomixEnvelope.HOP_MILLIS);
            if (i >= smooth) running -= envelope.rmsAt((long) (i - smooth) * AutomixEnvelope.HOP_MILLIS);
            level[i] = running / Math.min(i + 1, smooth);
        }
        for (int i = half; i + half <= level.length && set.size() < MAX_STRUCTURAL_CANDIDATES; ) {
            double jump = Math.abs(meanValues(level, i, half) - meanValues(level, i - half, half));
            if (jump < STRUCTURE_JUMP_MIN_DELTA) {
                i++;
                continue;
            }
            double weight = 0.0;
            double weighted = 0.0;
            while (i + half <= level.length) {
                jump = Math.abs(meanValues(level, i, half) - meanValues(level, i - half, half));
                if (jump < STRUCTURE_JUMP_MIN_DELTA) break;
                weight += jump;
                weighted += jump * i;
                i++;
            }
            if (weight > 0.0) {
                addIfInRange(set, Math.round(weighted / weight) * AutomixEnvelope.HOP_MILLIS,
                        earliest, latest);
            }
        }
    }

    private static double meanValues(double[] values, int from, int count) {
        int start = Math.max(0, Math.min(values.length, from));
        int end = Math.max(start, Math.min(values.length, start + count));
        if (end <= start) return 0.0;
        double sum = 0.0;
        for (int i = start; i < end; i++) sum += values[i];
        return sum / (end - start);
    }

    /**
     * Prefer eight-bar/profile/energy boundaries when they cover the search range densely enough;
     * otherwise keep the Folia downbeat grid or its deterministic 500 ms fallback.
     */
    static long[] cutCandidates(Request request, long earliest, long latest, long window) {
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        long[] structure = structuralCandidates(request, true, earliest, latest);
        if (structure.length >= 4 && maxGap(structure, earliest, latest) <= STRUCTURE_MAX_GAP_MILLIS) {
            for (long point : structure) set.add(point);
        } else if (request.beatAlignEnabled && usable(request.outgoingGrid)) {
            addGridCandidates(set, earliest, latest, request.outgoingGridOriginMillis,
                    request.outgoingGrid);
        } else {
            for (long position = latest; position >= earliest; position -= 500L) set.add(position);
        }
        addIfInRange(set, request.lastVocalEndMillis > 0L ? request.lastVocalEndMillis + 450L : -1L,
                earliest, latest);
        addIfInRange(set, request.stemVocalEndMillis > 0L ? request.stemVocalEndMillis + 450L : -1L,
                earliest, latest);
        addIfInRange(set, request.heuristicFireMillis, earliest, latest);
        return boundedCandidates(set, earliest, latest, request.heuristicFireMillis, latest);
    }

    /**
     * Prefer measured intro structure, while retaining cue zero, the Folia bar grid and a dense
     * fallback when a reliable grid is not available.
     */
    static long[] entryCandidates(Request request, long earliest, long latest) {
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        addIfInRange(set, 0L, earliest, latest);
        addIfInRange(set, request.introEndMillis, earliest, latest);
        long[] structure = structuralCandidates(request, false, earliest, latest);
        if (structure.length >= 4 && maxGap(structure, earliest, latest) <= STRUCTURE_MAX_GAP_MILLIS) {
            for (long point : structure) set.add(point);
        } else if (request.beatAlignEnabled && usable(request.incomingGrid)) {
            long first = Math.max(earliest, Math.max(0L, request.introEndMillis));
            addGridCandidates(set, first, latest, request.incomingGridOriginMillis,
                    request.incomingGrid);
        } else {
            for (long position = latest; position >= earliest; position -= 500L) {
                addIfInRange(set, position, earliest, latest);
            }
        }
        addIfInRange(set, request.heuristicCueMillis, earliest, latest);
        return boundedCandidates(set, earliest, latest, request.heuristicCueMillis, latest);
    }

    private static long[] boundedCandidates(java.util.TreeSet<Long> candidates, long earliest, long latest,
                                            long preferred, long boundary) {
        if (candidates.size() <= MAX_SEARCH_CANDIDATES) {
            long[] result = new long[candidates.size()];
            int index = 0;
            for (long candidate : candidates) result[index++] = candidate;
            return result;
        }
        java.util.ArrayList<Long> all = new java.util.ArrayList<>(candidates);
        java.util.TreeSet<Long> selected = new java.util.TreeSet<>();
        addIfInRange(selected, preferred, earliest, latest);
        addIfInRange(selected, boundary, earliest, latest);
        addIfInRange(selected, earliest, earliest, latest);
        addIfInRange(selected, latest, earliest, latest);
        int slots = Math.max(0, MAX_SEARCH_CANDIDATES - selected.size());
        if (slots == 1) {
            selected.add(all.get(all.size() / 2));
        } else if (slots > 1) {
            for (int i = 0; i < slots; i++) {
                int index = (int) Math.round(i * (all.size() - 1.0) / (slots - 1.0));
                selected.add(all.get(index));
            }
        }
        long[] result = new long[selected.size()];
        int index = 0;
        for (long candidate : selected) result[index++] = candidate;
        return result;
    }

    private static long maxGap(long[] sorted, long earliest, long latest) {
        if (sorted.length == 0) return Long.MAX_VALUE;
        long largest = Math.max(0L, sorted[0] - earliest);
        for (int i = 1; i < sorted.length; i++) largest = Math.max(largest, sorted[i] - sorted[i - 1]);
        return Math.max(largest, Math.max(0L, latest - sorted[sorted.length - 1]));
    }

    static long earliestCutMillis(long durationMillis, long minimumFireMillis) {
        if (durationMillis <= 0L) return Math.max(0L, minimumFireMillis);
        long whole = durationMillis / 7L;
        long remainder = durationMillis % 7L;
        long playedFloor = whole * 5L + (remainder * 5L + 6L) / 7L;
        return Math.max(playedFloor, Math.max(20_000L, minimumFireMillis));
    }

    private static void addGridCandidates(java.util.TreeSet<Long> set, long earliest, long latest,
                                          long originMillis, AutomixAnalyzer.BeatGrid grid) {
        double period = grid.barPeriodMillis();
        if (!Double.isFinite(period) || period < 200.0 || period > 20_000.0) return;
        double anchor = originMillis + grid.barPhaseMillis;
        long index = (long) Math.ceil((earliest - anchor) / period);
        for (double candidate = anchor + index * period; candidate <= latest + .5;
             candidate = anchor + (++index) * period) {
            addIfInRange(set, Math.round(candidate), earliest, latest);
        }
    }

    private static void addIfInRange(java.util.TreeSet<Long> set, long value, long earliest, long latest) {
        if (value < earliest || value > latest) return;
        set.add(Math.max(0L, value));
    }

    // ---------------------------------------------------------------------------------------------
    // 打分
    // ---------------------------------------------------------------------------------------------

    /** 粗排分：响度、动态、低频相容与结构落点。 */
    private static double coarseScore(Request request, long cut, long entry, long window) {
        double a = mean(request.outgoing.rms, cut, window);
        double b = mean(request.incoming.rms, entry, window);
        double level = 1.0 - Math.min(1.0, Math.abs(a - b));
        double onsetA = mean(request.outgoing.onset, cut, window);
        double onsetB = mean(request.incoming.onset, entry, window);
        double activity = 1.0 - Math.min(1.0, Math.abs(onsetA - onsetB));
        double lowCompatibility = lowEndCompatibility(request, cut, entry, window);
        if (Double.isNaN(lowCompatibility)) lowCompatibility = .5;
        double structure = structuralPointBonus(request, cut, entry);
        return .45 * level + .25 * activity + .20 * lowCompatibility + .10 * structure;
    }

    private static double structuralPointBonus(Request request, long cut, long entry) {
        double score = 0.0;
        double weight = 0.0;
        if (request.outgoingSectionMillis != null) {
            score += sectionAlignment(request.outgoingSectionMillis, cut);
            weight += 1.0;
        }
        if (request.incomingSectionMillis != null) {
            score += sectionAlignment(request.incomingSectionMillis, entry);
            weight += 1.0;
        }
        if (request.beatAlignEnabled && usable(request.outgoingGrid)) {
            score += STRUCTURE_BONUS_WEIGHT * barAlignment(request.outgoingGrid,
                    request.outgoingGridOriginMillis, cut);
            weight += STRUCTURE_BONUS_WEIGHT;
        }
        if (request.beatAlignEnabled && usable(request.incomingGrid)) {
            score += STRUCTURE_BONUS_WEIGHT * barAlignment(request.incomingGrid,
                    request.incomingGridOriginMillis, entry);
            weight += STRUCTURE_BONUS_WEIGHT;
        }
        score += energyJumpAlignment(request.outgoing, cut);
        score += energyJumpAlignment(request.incoming, entry);
        weight += 2.0;
        return weight <= 0.0 ? .5 : score / weight;
    }

    private static double barAlignment(AutomixAnalyzer.BeatGrid grid, long origin, long point) {
        double period = grid.barPeriodMillis();
        if (!(period > 0.0) || !Double.isFinite(period)) return .5;
        double anchor = origin + grid.barPhaseMillis;
        double remainder = Math.abs((point - anchor) - Math.rint((point - anchor) / period) * period);
        return clamp01(1.0 - 2.0 * remainder / period);
    }

    private static double sectionAlignment(long[] sections, long point) {
        if (sections == null || sections.length == 0) return .5;
        long nearest = Long.MAX_VALUE;
        for (long section : sections) nearest = Math.min(nearest, Math.abs(section - point));
        return clamp01(1.0 - nearest / 2_000.0);
    }

    private static double energyJumpAlignment(AutomixEnvelope envelope, long point) {
        if (envelope == null) return .5;
        double before = envelope.meanLevel(point - STRUCTURE_JUMP_HALF_WINDOW_MILLIS, point);
        double after = envelope.meanLevel(point, point + STRUCTURE_JUMP_HALF_WINDOW_MILLIS);
        if (before < 0.0 || after < 0.0) return .5;
        return clamp01(Math.abs(after - before) / (STRUCTURE_JUMP_MIN_DELTA * 2.0));
    }


    /** 细排：九项音频证据与尾段先验排序；缺料证据及其权重一并从分母移除。 */
    private static Result score(Request request, long cut, long entry, long window, boolean shapePhase) {
        double windowSeconds = window / 1000.0;
        double[] terms = new double[WEIGHTS.length];
        boolean[] known = new boolean[WEIGHTS.length];

        // ① 形状相关
        double shape = shapeCorrelation(request, cut, entry, window, shapePhase);
        if (!Double.isNaN(shape)) {
            terms[0] = shape;
            known[0] = true;
        }

        // ② 电平差
        double a = mean(request.outgoing.rms, cut, window);
        double b = mean(request.incoming.rms, entry, window);
        if (a >= 0.0 && b >= 0.0) {
            terms[1] = 1.0 - Math.min(1.0, Math.abs(a - b));
            known[1] = true;
        }

        // ③ 涟漪：等功率相加后的起伏。窗口内两头都在唱或都在退会让中间下凹/鼓起，
        //    这一项就是抓它的——均值极小的窗口（两边都快静音）按缺料处理，不给满分。
        double ripple = ripple(request, cut, entry, window);
        if (!Double.isNaN(ripple)) {
            terms[2] = ripple;
            known[2] = true;
        }

        // ④ 高频互补；低频冲突由第⑨项独立计分。
        double complement = highComplement(request, cut, entry, window);
        if (!Double.isNaN(complement)) {
            terms[3] = complement;
            known[3] = true;
        }

        // ⑤ 起声密度相关。
        double activity = correlation(request.outgoing.onset, cut, request.incoming.onset, entry, window);
        if (!Double.isNaN(activity)) {
            terms[4] = activity;
            known[4] = true;
        }

        // ⑥ 拍点漂移。
        if (usable(request.outgoingGrid) && usable(request.incomingGrid)) {
            double driftBeats = Math.abs(request.outgoingGrid.bpm - request.incomingGrid.bpm)
                    / 60.0 * windowSeconds;
            terms[5] = 1.0 - Math.min(1.0, driftBeats / 4.0);
            known[5] = true;
        }

        // ⑦ 出场下降、进场上升的过渡弧。
        double arc = arcScore(request, cut, entry, windowSeconds);
        if (!Double.isNaN(arc)) {
            terms[6] = arc;
            known[6] = true;
        }

        // ⑧ 人声冲突。
        double vocal = vocalScore(request, cut, window);
        if (!Double.isNaN(vocal)) {
            terms[7] = vocal;
            known[7] = true;
        }

        // ⑨ 低频冲突项按「少冲突得高分」计，缺低频信号时不计入证据。
        double lowConflict = lowEndCompatibility(request, cut, entry, window);
        if (!Double.isNaN(lowConflict)) {
            terms[8] = lowConflict;
            known[8] = true;
        }

        // ⑩ 尾段保留是位置先验，只进名次。
        long discarded = Math.max(0L, request.outgoing.durationMillis - (cut + window));
        terms[TAIL_TERM] = clamp01(1.0 - (discarded / 1000.0) / TAIL_KEEP_SECONDS);
        known[TAIL_TERM] = true;

        double total = 0.0;
        double weight = 0.0;
        for (int i = 0; i < TAIL_TERM; i++) {
            if (!known[i]) continue;
            total += WEIGHTS[i] * terms[i];
            weight += WEIGHTS[i];
        }
        if (weight <= 0.0) return null;
        Result result = new Result();
        result.fireMillis = cut;
        result.cueMillis = entry;
        result.overlapMillis = window;
        result.score = total / weight;
        result.measuredWeight = weight;
        result.preference = (total + WEIGHTS[TAIL_TERM] * terms[TAIL_TERM])
                / (weight + WEIGHTS[TAIL_TERM]);
        result.discardedMillis = discarded;
        result.detail = describe(terms, known);
        return result;
    }

    /** 每个「必须进细排」的退场点占这么多席位，剩下的仍然归粗排决定。 */
    private static final int RESERVED_SEATS_PER_CUT = 8;

    /**
     * 短名单先给若干个<b>必须被认真比过</b>的退场点留座，返回已经占掉的格数。
     *
     * <p>粗排手里只有「窗口平均电平差 + 局部起声密度」两个量。碰到一条平坦或均匀的包络，所有
     * 并列，而「取分数最高的 96 对」在完全并列时实际是照数组顺序取<b>最早</b>的那批 —— 曲尾那些
     * 位置连细排都没进过，尾段保留项也就没机会说话。实测症状：纯平包络上自选点永远钉在 20%
     * 那个最早允许的位置，而它替换掉的正是启发式那一对，对照位却根本不在场。</p>
     *
     * <p>留座的是<b>对照位</b>与<b>曲尾那一个点</b>，每个只留 {@link #RESERVED_SEATS_PER_CUT} 格：
     * 前者保证类注释第一条规矩在细排层面也成立（最坏情况就是选回它自己，而不是选一个从没被
     * 认真比过的位置），后者保证「更晚退场」这一族始终有人替它说话。剩下的席位照旧归粗排。</p>
     */
    private static int reserveSeatsFor(long[] wantedCuts, long[] cuts, double[] coarse, int entryCount,
                                       boolean[] taken, int[] keepIndex, int keep) {
        int filled = 0;
        for (long wanted : wantedCuts) {
            int cut = -1;
            for (int c = 0; c < cuts.length; c++) {
                if (cuts[c] == wanted) {
                    cut = c;
                    break;
                }
            }
            if (cut < 0) continue;
            for (int seat = 0; seat < RESERVED_SEATS_PER_CUT && filled < keep; seat++) {
                int bestIndex = -1;
                double bestValue = -Double.MAX_VALUE;
                for (int e = 0; e < entryCount; e++) {
                    int index = cut * entryCount + e;
                    if (taken[index]) continue;
                    if (coarse[index] > bestValue) {
                        bestValue = coarse[index];
                        bestIndex = index;
                    }
                }
                if (bestIndex < 0) break;
                taken[bestIndex] = true;
                keepIndex[filled++] = bestIndex;
            }
        }
        return filled;
    }

    /**
     * 早退场的加价：这一对候选比启发式对照位<b>多吃掉</b>的那一段越长，采纳线抬得越高。
     *
     * <p>只管「更早」：往晚退的候选吃得更少，不加价也不打折——它本来就更接近「让这首唱完」。
     * 抬幅线性到分钟，封顶 {@link #EARLY_CUT_MARGIN_CEILING}，为的是别让「多切三分钟」
     * 变成数学上不可能越过、从而把整曲搜索永久废掉。</p>
     */
    static double earlyCutMargin(long discardedMillis, long baselineDiscardedMillis) {
        long eatenEarly = Math.max(0L, discardedMillis - baselineDiscardedMillis);
        if (eatenEarly <= 0L) return 0.0;
        return Math.min(EARLY_CUT_MARGIN_CEILING, EARLY_CUT_MARGIN_PER_MINUTE * eatenEarly / 60_000.0);
    }

    /** 尾段保留项的下标。它特殊：不是量出来的证据，而是政策先验（见 {@link #TAIL_KEEP_SECONDS}）。 */
    /**
     * 形状相关：比窗口开头几秒的 10 ms 细包络。允许在 {@code 0, ±½拍, ±1拍} 五个滞后里取最大，
     * 因为两段差半拍仍然是接得上的——真正要排除的是「完全对不上」。
     */
    private static double shapeCorrelation(Request request, long cut, long entry, long window, boolean phase) {
        int frames = (int) Math.min(window / AutomixEnvelope.FINE_HOP_MILLIS,
                (long) (SHAPE_SPAN_SECONDS * 1000.0) / AutomixEnvelope.FINE_HOP_MILLIS);
        if (frames < MIN_SHAPE_FRAMES) return Double.NaN;
        double[] a = request.outgoing.fineRange(cut, frames);
        double[] b = request.incoming.fineRange(entry, frames);
        if (a.length < MIN_SHAPE_FRAMES || b.length < MIN_SHAPE_FRAMES) return Double.NaN;
        int length = Math.min(a.length, b.length);
        if (!phase || !usable(request.outgoingGrid)) return normalisedCorrelation(a, b, length, 0);
        int beatFrames = (int) Math.round(barMillis(request.outgoingGrid) / 2.0
                / AutomixEnvelope.FINE_HOP_MILLIS);
        double best = Double.NaN;
        int[] lags = {0, -beatFrames, beatFrames, -2 * beatFrames, 2 * beatFrames};
        for (int lag : lags) {
            double value = normalisedCorrelation(a, b, length, lag);
            if (!Double.isNaN(value) && (Double.isNaN(best) || value > best)) best = value;
        }
        return best;
    }

    /** 相关系数映射到 0..1：负相关与不相关一样差，都归 0。 */
    private static double normalisedCorrelation(double[] a, double[] b, int length, int lag) {
        double sumA = 0.0, sumB = 0.0, sumAA = 0.0, sumBB = 0.0, sumAB = 0.0;
        int used = 0;
        for (int i = 0; i < length; i++) {
            int j = i + lag;
            if (j < 0 || j >= b.length) continue;
            double x = a[i], y = b[j];
            sumA += x;
            sumB += y;
            sumAA += x * x;
            sumBB += y * y;
            sumAB += x * y;
            used++;
        }
        if (used < MIN_SHAPE_FRAMES) return Double.NaN;
        double cov = sumAB - sumA * sumB / used;
        double varA = sumAA - sumA * sumA / used;
        double varB = sumBB - sumB * sumB / used;
        if (varA <= 1e-9 || varB <= 1e-9) return Double.NaN;   // 一段是平的：无从判断
        double r = cov / Math.sqrt(varA * varB);
        if (!Double.isFinite(r)) return Double.NaN;
        return Math.max(0.0, r);
    }

    /** 把两段等功率相加，看结果的起伏相对均值有多大。起伏小 = 一条平的河，好。 */
    private static double ripple(Request request, long cut, long entry, long window) {
        int frames = request.outgoing.framesIn(window);
        if (frames < 4) return Double.NaN;
        double[] a = request.outgoing.range(request.outgoing.rms, cut, frames);
        double[] b = request.incoming.range(request.incoming.rms, entry, frames);
        if (a.length != b.length || a.length < 4) return Double.NaN;
        double mean = 0.0;
        double[] mixed = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            double progress = (i + 1.0) / (a.length + 1.0);
            double angle = progress * Math.PI / 2.0;
            mixed[i] = a[i] * Math.cos(angle) + b[i] * Math.sin(angle);
            mean += mixed[i];
        }
        mean /= mixed.length;
        if (mean <= 1e-6) return Double.NaN;
        double variance = 0.0;
        for (double value : mixed) variance += (value - mean) * (value - mean);
        variance /= mixed.length;
        return 1.0 - Math.min(1.0, Math.sqrt(variance) / mean);
    }

    /** 高频能量重叠率：1 表示两首都把亮度压在同一段，0 表示各自让位。 */
    static double highComplement(Request request, long cut, long entry, long window) {
        int frames = request.outgoing.framesIn(window);
        if (frames < 4) return Double.NaN;
        double a = mean(request.outgoing.high, cut, window);
        double b = mean(request.incoming.high, entry, window);
        if (a < 0.0 || b < 0.0) return Double.NaN;
        double overlap = Math.min(a, b) / Math.max(1e-9, Math.max(a, b));
        return 1.0 - overlap;
    }

    /** 低频相容度：低频分布互相让位得高分。 */
    static double lowEndCompatibility(Request request, long cut, long entry, long window) {
        int frames = request.outgoing.framesIn(window);
        if (frames < 4) return Double.NaN;
        double a = mean(request.outgoing.low, cut, window);
        double b = mean(request.incoming.low, entry, window);
        if (a < 0.0 || b < 0.0 || Math.max(a, b) <= 1.0 / 255.0) return Double.NaN;
        return 1.0 - Math.min(a, b) / Math.max(a, b);
    }

    /** 频带互补：1 − Σmin/Σmax。 */
    private static double complementOf(byte[] aBand, long aStart, byte[] bBand, long bStart, int frames) {
        double[] a = quantized(aBand, frames, aStart);
        double[] b = quantized(bBand, frames, bStart);
        if (a == null || b == null) return Double.NaN;
        double min = 0.0, max = 0.0;
        for (int i = 0; i < a.length; i++) {
            min += Math.min(a[i], b[i]);
            max += Math.max(a[i], b[i]);
        }
        if (max <= 1e-9) return Double.NaN;
        return 1.0 - min / max;
    }

    /** 弧：出场侧窗口内是否往下、进场侧是否往上。用前后半段均值的 dB 差，±1 dB 内算平。 */
    private static double arcScore(Request request, long cut, long entry, double windowSeconds) {
        double fall = slopeDb(request.outgoing.rms, cut, (long) (windowSeconds * 1000.0));
        double rise = slopeDb(request.incoming.rms, entry, (long) (windowSeconds * 1000.0));
        if (Double.isNaN(fall) || Double.isNaN(rise)) return Double.NaN;
        double falling = clamp01(0.5 - fall / 6.0);
        double rising = clamp01(0.5 + rise / 6.0);
        return 0.5 * (falling + rising);
    }

    /**
     * 人声冲突：出场曲最后一句唱到 {@code voiceEnd}，而播放侧要到窗口的
     * {@link AutomixPlan#VOCAL_DUCK_RELEASE_START}（有包络证据时按实测）才开始把人声放开——
     * 理想情况是这两件事错开。
     *
     * <p>释放点<b>按当前这一对候选实测</b>（{@link AutomixEnvelope#releaseFraction}），不用常数：
     * 否则就是「按一条曲线挑的接缝、按另一条曲线执行的混音」。没有人声证据（没歌词、没分轨）
     * 时退化成只看包络什么时候掉下去，那是一个真实但更弱的信号。</p>
     */
    private static double vocalScore(Request request, long cut, long window) {
        double measured = request.outgoing.releaseFraction(cut, window);
        double release = measured > 0.0 ? measured : AutomixPlan.VOCAL_DUCK_RELEASE_START;
        long voiceEnd = Math.max(request.stemVocalEndMillis, request.lastVocalEndMillis);
        if (voiceEnd <= 0L) {
            if (measured <= 0.0) return Double.NaN;
            return clamp01(1.0 - measured);     // 越早掉下去，越有余地留给进来的那一句
        }
        double room = (cut + release * window - voiceEnd) / (double) Math.max(1.0, window);
        if (room >= 0.10) return 1.0;           // 尾句在让路点之前就唱完了
        return clamp01(0.75 + room * 2.5);      // 刚好接上；负的就是两个人同时唱
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 诊断用的「这一对到底量出了多少东西」。 */
    private static String fmtWeight(double weight) {
        return "量到" + String.format(Locale.ROOT, "%.2f/%.2f", weight, WEIGHT_SUM);
    }

    private static double[] quantized(byte[] values, int frames, long startMillis) {
        if (values == null || values.length == 0) return null;
        int from = (int) Math.min(values.length - 1L, Math.max(0L, startMillis / AutomixEnvelope.HOP_MILLIS));
        int count = Math.min(frames, values.length - from);
        if (count < 4) return null;
        double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = (values[from + i] & 0xFF) / 255.0;
        return out;
    }

    private static double mean(byte[] values, long startMillis, long windowMillis) {
        if (values == null || values.length == 0) return -1.0;
        int from = (int) Math.min(values.length - 1L, Math.max(0L, startMillis / AutomixEnvelope.HOP_MILLIS));
        int count = Math.min((int) (windowMillis / AutomixEnvelope.HOP_MILLIS), values.length - from);
        if (count <= 0) return -1.0;
        double sum = 0.0;
        for (int i = 0; i < count; i++) sum += (values[from + i] & 0xFF) / 255.0;
        return sum / count;
    }

    /** 前后半段的 RMS 差，折成整窗的 dB/秒；两半都接近静音给 NaN。 */
    private static double slopeDb(byte[] values, long startMillis, long windowMillis) {
        int half = (int) (windowMillis / AutomixEnvelope.HOP_MILLIS / 2);
        if (half < 2) return Double.NaN;
        double first = mean(values, startMillis, half * (long) AutomixEnvelope.HOP_MILLIS);
        double second = mean(values, startMillis + half * (long) AutomixEnvelope.HOP_MILLIS,
                half * (long) AutomixEnvelope.HOP_MILLIS);
        if (first < 0.0 || second < 0.0 || first <= 1e-4 || second <= 1e-4) return Double.NaN;
        return 20.0 * Math.log10(second / first);
    }

    private static double correlation(byte[] aValues, long aStart, byte[] bValues, long bStart, long window) {
        int frames = (int) Math.min(window / AutomixEnvelope.HOP_MILLIS, 1_000);
        double[] a = quantized(aValues, frames, aStart);
        double[] b = quantized(bValues, frames, bStart);
        if (a == null || b == null) return Double.NaN;
        return normalisedCorrelation(a, b, Math.min(a.length, b.length), 0);
    }

    private static String describe(double[] terms, boolean[] known) {
        StringBuilder builder = new StringBuilder(96);
        String[] names = {"形状", "电平", "涟漪", "高频互补", "起声", "漂移", "弧", "人声", "低频冲突", "尾段"};
        for (int i = 0; i < names.length; i++) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(names[i]).append('=')
                    .append(known[i] ? String.format(Locale.ROOT, "%.2f", terms[i]) : "—");
        }
        return builder.toString();
    }

    private static boolean usable(AutomixAnalyzer.BeatGrid grid) {
        return grid != null && grid.bpm > 1f && grid.confidence >= FoliaAutomixPlanner.MIN_GRID_CONFIDENCE;
    }

    private static long barMillis(AutomixAnalyzer.BeatGrid grid) {
        return Math.max(200L, Math.min(20_000L, Math.round(grid.barPeriodMillis())));
    }

    private static long clamp(long value, long floor, long ceiling) {
        if (ceiling < floor) return floor;
        return Math.max(floor, Math.min(ceiling, value));
    }
}
