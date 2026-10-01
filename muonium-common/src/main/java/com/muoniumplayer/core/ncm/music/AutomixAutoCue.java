package com.muoniumplayer.core.ncm.music;

import java.util.Locale;

/**
 * 进阶模式的<b>配对选点</b>：把「从 A 的哪一帧退场」和「从 B 的哪一帧进场」当成<b>一对</b>候选来枚举、
 * 打分、必要时弃权。
 *
 * <h2>为什么这不是「把点火点算得更准一点」</h2>
 *
 * <p>{@link FoliaAutomixPlanner} 原本的启发式把出场点压在 {@code [0.6·dur, dur−overlap]} 这几秒里，
 * 进场点则由前奏长度独立决定——两侧从不互相看。于是一首歌若是<b>从副歌硬接进尾奏</b>再淡出，
 * 或者进场曲的头 20 秒其实还在铺垫，启发式看不见：它只在小节线附近找一个「差不多的位置」。</p>
 *
 * <p>这里把两侧同时放开，在整条包络上找一对点，再问九个问题给这一对打分。找不到明显更好的一对，
 * 或者最好的一对也不过 0.56，就<b>返回 null 让原启发式继续用</b>——这不是保守，是因为
 * 照着一个勉强结论去提前二十秒淡出，比不淡出更糟，而且用户完全看不出原因。</p>
 *
 * <h2>四条不能动的规矩</h2>
 *
 * <ol>
 *   <li><b>原启发式永远是候选之一。</b>它以自己的位置进入枚举，因此日志里能给出
 *       「候选 0.71 vs 启发式 0.63」这种可比数字，也保证最坏情况就是今天的行为，不会更差。</li>
 *   <li><b>比启发式吃得更早，就要明显更好。</b>放开的代价不是算错，是<b>吞歌</b>：选在 60 秒处退场
 *       意味着一首四分钟的歌剩下的三分钟永远不会被听到，而这件事在包络上完全看不出来——中段
 *       两段安静的verse 互相关很容易赢过嘈杂的 outro。所以两把尺一起用：候选<b>名次</b>里摊一份
 *       尾段保留的先验（{@link Result#preference}，见 {@link #TAIL_KEEP_SECONDS}），而<b>采纳线</b>
 *       随「比启发式多吃掉的那一段」按分钟往上抬（见 {@link #EARLY_CUT_MARGIN_PER_MINUTE}）。
 *       先验刻意不进判定用的那个分数，否则「位置安全」会把「接缝不行」的一对抬过弃权线。
 *       往<b>晚</b>退的候选不受加价影响：它只会少切一点。</li>
 *   <li><b>测不到就中性，不猜。</b>任何一项缺原料（没有网格、没有两侧都可用的带通、窗内几乎静音）
 *       时，该项连同它的权重一起从分母里<b>去掉</b>，而不是补一个 0 分或补一个均值。
 *       「不知道」和「确定为差」是两件事，混成一会让缺数据的歌系统性被判低。</li>
 *   <li><b>本类不碰音频、不起进程、不读文件。</b>所有原料由调用方给（{@link AutomixEnvelope#load}
 *       只读缓存）。规划路径上绝不允许出现「当场扫整曲」——预备期只有 30 秒，
 *       而一次外部格式整曲解码要起好几个 ffmpeg 进程。</li>
 * </ol>
 */
final class AutomixAutoCue {

    /**
     * 弃权线。低于这个分就不采纳候选，沿用启发式。
     *
     * <p>取 0.56 的理由不是「经验值」而是它的<b>位置</b>：八项证据里每一项都在 0..1，全中性
     * （形状 0.5、电平差 0.5、互补 0.5、弧 0.5、人声 0.5）大约落在 0.5 附近，
     * 所以弃权线必须高于「什么都没测出来」，否则一段纯噪声包络也能通过。
     * 0.56 意味着至少有两三项真的有据。第⑨项尾段保留<b>不在这里</b>：它是政策先验不是证据，
     * 只参与候选名次（{@link Result#preference}）——否则「这个位置安全」会把「这段接缝好不了」
     * 的一对抬过线来。</p>
     */
    static final double ABSTAIN_SCORE = 0.56;

    /**
     * 至少要量够这么多证据权重才允许采纳（八项合计 7.15 的约六成；第⑨项是先验，不在里面）。
     *
     * <p>弃权线只看分数是不够的：缺料的项会<b>连同权重一起退出分母</b>，于是「只量出一项、
     * 而那一项恰好很好」的一对能算出接近满分的分数。合成包络一测就现形——平坦段之间
     * 形状相关算不出（方差为零），剩下的电平与频段互补偏偏都容易给高分。
     * 这条下限把「没量到什么但运气好」挡在采纳之外：它不是<b>低分</b>弃权，
     * 而是<b>没资格被打分</b>的弃权。</p>
     */
    static final double MIN_MEASURED_WEIGHT = 4.30;

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

    /** 短名单规模：粗排只用来砍搜索量，最终名次一律由细排决定。 */
    static final int SHORTLIST = 96;

    /** 窗内最少帧数；少于此形状相关不可信，该项按缺料处理。 */
    private static final int MIN_SHAPE_FRAMES = 20;
    /** 形状相关只比窗口开头这几秒——比完整窗口更稳，且四分钟的歌不必整段比。 */
    private static final double SHAPE_SPAN_SECONDS = 4.0;
    /** 出场侧至少要给包络留下这些毫秒，否则窗口会越过文件末尾。 */
    private static final long TAIL_GUARD_MILLIS = 2_000L;
    /** 进场侧最多扫到这里，与 {@code FoliaAutomixPlanner} 只看头 30 秒的旧约束相容。 */
    private static final long ENTRY_SCAN_LIMIT_MILLIS = 25_000L;
    /** 出场点不早于这两者中的较大值：没人愿意在歌刚开始就退场。包内可见给测试用。 */
    private static final long MIN_CUT_MILLIS = 20_000L;
    static final double MIN_CUT_FRACTION = 0.20;
    /** 候选窗长相对用户设定的倍率；两端都夹在 {@link AutomixSettings} 的 overlap 上下限内。 */
    private static final double[] WINDOW_SCALES = {0.5, 0.75, 1.0, 1.5, 2.0};

    /** 九项权重，顺序与 {@link #evaluate} 里 {@code terms} 的填充顺序一致。 */
    static final double[] WEIGHTS = {
            1.60,   // ① 形状相关：两段的律动轮廓是否本就连得上
            1.20,   // ② 电平差：窗口两端平均响度差，落差大就得靠增益硬补
            0.80,   // ③ 涟漪：等功率相加后起伏，起伏大就是忽响忽轻
            1.00,   // ④ 频段互补：低频/高频占比是否互相让开
            0.80,   // ⑤ 起声密度：两段是不是同一类活跃度
            0.50,   // ⑥ 拍点漂移：两个 BPM 在窗口长度内会错开多少拍
            0.35,   // ⑦ 弧：出场侧在往下走、进场侧在往上走
            0.90,   // ⑧ 人声冲突：出场曲的尾句能不能在进场曲人声起来之前唱完
            1.00    // ⑨ 尾段保留：这一退场会吃掉出场曲剩下的多少秒（永远量得到，不缺料）
    };
    static final double WEIGHT_SUM = 8.15;

    /**
     * 尾段保留项在 {@link #WEIGHTS} 里的下标。
     *
     * <p>它特殊：其余八项都要<b>量</b>才有线（缺料就连权重一起退出分母），而这一项只看两个时长，
     * 永远不缺料。所以它只进 {@link Result#preference}（候选名次），不进 {@link Result#score}
     * 与 {@link Result#measuredWeight}——一条纯平的包络也能把它算到 0.9 以上，若让它进分，
     * 「没量够就不许改用户接缝」与弃权线两道闸会一起被架空（实测就是这么暴露的）。</p>
     */
    static final int TAIL_TERM = 8;

    private AutomixAutoCue() {
    }

    /** 一次选点所需的全部原料，由调用方备齐。 */
    static final class Request {
        AutomixEnvelope outgoing;
        AutomixEnvelope incoming;
        AutomixAnalyzer.BeatGrid outgoingGrid;
        AutomixAnalyzer.BeatGrid incomingGrid;
        /** 进场曲前奏结束（{@code leadInMillis}），用于铺进场候选。 */
        long introEndMillis = -1L;
        /** 出场曲<b>歌词</b>最后一次人声结束；没有歌词为 -1。 */
        long lastVocalEndMillis = -1L;
        /** 出场曲<b>分轨证据</b>的人声结束；没测到为 -1。 */
        long stemVocalEndMillis = -1L;
        /** 出场曲的段落边界（{@code FoliaTrackProfile.sectionMillis}），可空。 */
        long[] outgoingSectionMillis;
        /** 原启发式给出的三个位置，作为候选之一进入比较。 */
        long heuristicFireMillis;
        long heuristicCueMillis;
        long heuristicOverlapMillis;
        /** 用户配置的交叠长度，窗口候选以它为基准。 */
        long preferredOverlapMillis;
        /**
         * 出场点的最早可能位置：规划那一刻<b>出场曲的播放头</b>（调用方已含安全余量）。
         *
         * <p>没有它，搜索会挑出一个已经播过去的时间点——交接逻辑下一秒就会发现「位置已过
         * 点火点」，于是混音在任意一处立刻开始，听起来像在歌中间硬切了一下。整曲包络能让我们
         * 看见更丰富的接缝，但能选的范围仍然只能是<b>未来</b>。</p>
         */
        long minimumFireMillis;
        boolean beatAlignEnabled = true;
    }

    /** 选点结论。全部是曲目内的绝对毫秒。 */
    static final class Result {
        long fireMillis;
        long cueMillis;
        long overlapMillis;
        double score;
        /**
         * 名次分 = 八项证据分再把尾段先验摊进去。<b>只用来排候选</b>：弃权线、采纳线、日志里
         * 「候选 vs 启发式」那两个可比数字一律用 {@link #score}，先验不参与判定。
         */
        double preference;
        /** 这一对实际量到的权重之和；缺料的项连同其权重一起不在里面。见 {@link #MIN_MEASURED_WEIGHT}。 */
        double measuredWeight;
        /** 与 {@link #score} 同式的启发式对照分，用于日志里那句「候选 X vs 启发式 Y」。 */
        double baselineScore;
        /**
         * 采纳真正要越过的线：默认就是 {@link #baselineScore}，但如果这一对退场<b>比启发式更早</b>
         * （会多吃掉出场曲的一段），它按分钟往上抬。见类注释第二条规矩。
         */
        double requiredScore;
        /** 这一退场之后被跳过的那一段有多长；启发式对照位的同一量用作基准。 */
        long discardedMillis;
        /** 逐项得分，仅用于诊断输出。 */
        String detail;
        /** 采用/弃权的成句说明。 */
        String note = "";

        boolean isAdopted() {
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
    static Result plan(Request request) {
        if (request == null || request.outgoing == null || request.incoming == null
                || !request.outgoing.isUsable() || !request.incoming.isUsable()) return null;
        long outgoingDuration = request.outgoing.durationMillis;
        long incomingDuration = request.incoming.durationMillis;
        if (outgoingDuration <= 0L || incomingDuration <= 0L) return null;

        long[] windows = candidateWindows(request);
        if (windows.length == 0) return null;
        long widest = windows[windows.length - 1];

        long cutEarliest = Math.max(MIN_CUT_MILLIS, (long) (outgoingDuration * MIN_CUT_FRACTION));
        // 不能选已经播过去的位置。规划发生在预备的那一刻，出场曲的播放头已经在那儿了——
        // 整曲包络放开的是「往前找落点」，不是「往回找落点」：一个落在播放头之后的
        // fireMillis 会让 `position < plan.fireMillis` 当场为假，交接在任意位置立刻开始。
        cutEarliest = Math.max(cutEarliest, request.minimumFireMillis);
        long cutLatest = outgoingDuration - widest - TAIL_GUARD_MILLIS;
        long entryEarliest = 0L;
        long entryLatest = Math.min(ENTRY_SCAN_LIMIT_MILLIS, incomingDuration - widest - TAIL_GUARD_MILLIS);
        if (cutLatest < cutEarliest || entryLatest < entryEarliest) return null;

        long[] cuts = cutCandidates(request, cutEarliest, cutLatest, widest);
        long[] entries = entryCandidates(request, entryEarliest, entryLatest);
        if (cuts.length == 0 || entries.length == 0) return null;

        // 粗排：只用窗口平均电平差与局部起声密度砍搜索量。这两项可以前缀和 O(1) 取，
        // 而细排（九项）在 cuts×entries×windows 上是几万倍的成本。
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
        int filled = reserveSeatsFor(new long[]{request.heuristicFireMillis, cutLatest},
                cuts, coarse, entries.length, taken, keepIndex, keep);
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

        // 对照位先算：名次里要把「早退场的加价」算进去，不能等选完了再补一把尺。
        Result baseline = score(request, request.heuristicFireMillis, request.heuristicCueMillis,
                request.heuristicOverlapMillis, false);
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
    private static long[] candidateWindows(Request request) {
        long floor = (long) (AutomixSettings.MIN_OVERLAP_SECONDS * 1000.0);
        long ceiling = (long) (AutomixSettings.MAX_OVERLAP_SECONDS * 1000.0);
        long preferred = clamp(request.preferredOverlapMillis > 0L ? request.preferredOverlapMillis
                : request.heuristicOverlapMillis, floor, ceiling);
        long maxFit = Math.min(request.outgoing.durationMillis - TAIL_GUARD_MILLIS,
                request.incoming.durationMillis - TAIL_GUARD_MILLIS);
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

    /**
     * 出场点：有可用节拍网格时铺在<b>小节</b>上（不是拍上——小节才不会把乐句从中间劈开），
     * 否则按 500 ms 网格。原启发式的位置总是额外加进来。
     */
    private static long[] cutCandidates(Request request, long earliest, long latest, long window) {
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        long step = 500L;
        if (request.beatAlignEnabled && usable(request.outgoingGrid)) {
            step = barMillis(request.outgoingGrid);
        }
        // 从区间末尾往前走，避免长歌在小数累加下漂移。
        for (long position = latest; position >= earliest; position -= step) set.add(position);
        if (request.lastVocalEndMillis > 0L) {
            addIfInRange(set, request.lastVocalEndMillis + 450L, earliest, latest);
        }
        if (request.stemVocalEndMillis > 0L) {
            addIfInRange(set, request.stemVocalEndMillis + 450L, earliest, latest);
        }
        if (request.outgoingSectionMillis != null) {
            for (long section : request.outgoingSectionMillis) {
                addIfInRange(set, section, earliest, latest);
            }
        }
        addIfInRange(set, request.heuristicFireMillis, earliest, latest);
        long[] out = new long[set.size()];
        int i = 0;
        for (long value : set) out[i++] = value;
        return out;
    }

    /**
     * 进场点：0（整段前奏都要）、前奏结束、以及落在区间内的小节线；<b>没有可用网格时退回
     * 500 ms 网格</b>——与 {@link #cutCandidates} 同一套退路。
     *
     * <p>这条退路不是补全，是修一个真 bug：曾经这里只加 0、前奏结束和网格点，于是没网格
     * 也没前奏的歌<b>只剩「第 0 秒」一个进场候选</b>，整曲扫描等于白扫——出场点在全曲
     * 铺开、进场点却钉死在开头，配对搜索退化成「只找从哪退」。</p>
     */
    private static long[] entryCandidates(Request request, long earliest, long latest) {
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        addIfInRange(set, 0L, earliest, latest);
        addIfInRange(set, request.introEndMillis, earliest, latest);
        if (request.beatAlignEnabled && usable(request.incomingGrid)) {
            long step = barMillis(request.incomingGrid);
            long origin = Math.max(0L, request.introEndMillis);
            for (long position = origin; position <= latest; position += step) {
                addIfInRange(set, position, earliest, latest);
            }
        } else {
            // 与出场侧同一个 500 ms 退路，两侧密度要对得上，否则搜索天然是偏的。
            for (long position = latest; position >= earliest; position -= 500L) {
                addIfInRange(set, position, earliest, latest);
            }
        }
        addIfInRange(set, request.heuristicCueMillis, earliest, latest);
        long[] out = new long[set.size()];
        int i = 0;
        for (long value : set) out[i++] = value;
        return out;
    }

    private static void addIfInRange(java.util.TreeSet<Long> set, long value, long earliest, long latest) {
        if (value < earliest || value > latest) return;
        set.add(Math.max(0L, value));
    }

    // ---------------------------------------------------------------------------------------------
    // 打分
    // ---------------------------------------------------------------------------------------------

    /** 粗排分：窗口平均电平差 + 局部起声密度。只求相对量，别指望它能定名次。 */
    private static double coarseScore(Request request, long cut, long entry, long window) {
        double a = mean(request.outgoing.rms, cut, window);
        double b = mean(request.incoming.rms, entry, window);
        double level = 1.0 - Math.min(1.0, Math.abs(a - b));
        double onsetA = mean(request.outgoing.onset, cut, window);
        double onsetB = mean(request.incoming.onset, entry, window);
        double activity = 1.0 - Math.min(1.0, Math.abs(onsetA - onsetB));
        return 0.7 * level + 0.3 * activity;
    }

    /**
     * 细排：八项证据 + 一项尾段先验。缺料的证据项<b>连同权重一起</b>从分母去掉（见类注释第三条
     * 规矩），先验项则按 {@link #TAIL_TERM} 的说明只进名次。
     *
     * @param shapePhase 是否允许用节拍做形状相关的滞后偏移；启发式对照位也走同一条式子，
     *                   这样两个分数永远可比。
     */
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

        // ④ 频段互补：低频撞不撞、高频撞不撞，各比一次再平均。用占比而非绝对量，
        //    否则只是把②的电平差又数了一遍。
        double complement = bandComplement(request, cut, entry, window);
        if (!Double.isNaN(complement)) {
            terms[3] = complement;
            known[3] = true;
        }

        // ⑤ 起声密度相关：两段是不是同一类活跃度。相关而不是差，因为「都很活跃」也是接得上。
        double activity = correlation(request.outgoing.onset, cut, request.incoming.onset, entry, window);
        if (!Double.isNaN(activity)) {
            terms[4] = activity;
            known[4] = true;
        }

        // ⑥ 拍点漂移：窗口长度内两个网格会错开几拍，4 拍以上算完全跑飞。
        if (usable(request.outgoingGrid) && usable(request.incomingGrid)) {
            double driftBeats = Math.abs(request.outgoingGrid.bpm - request.incomingGrid.bpm)
                    / 60.0 * windowSeconds;
            terms[5] = 1.0 - Math.min(1.0, driftBeats / 4.0);
            known[5] = true;
        }

        // ⑦ 弧：出场侧在往下走、进场侧在往上走。两边都命中才算满分，只中一边给一半。
        double arc = arcScore(request, cut, entry, windowSeconds);
        if (!Double.isNaN(arc)) {
            terms[6] = arc;
            known[6] = true;
        }

        // ⑧ 人声冲突：出场曲的尾句能不能在进场曲人声起来之前唱完。这一项权重高，
        //    因为它是唯一「一听就知道坏了」的失败模式，其余几项最多是「不够好」。
        double vocal = vocalScore(request, cut, window);
        if (!Double.isNaN(vocal)) {
            terms[7] = vocal;
            known[7] = true;
        }

        // ⑨ 尾段保留：这一退场会把出场曲剩下的多少秒永久跳过。它只看时长，永远量得到，
        //    所以这一项不缺料——早退场必须在这里明码标价，而不是指望「别的项总会扣分」。
        long discarded = Math.max(0L, request.outgoing.durationMillis - (cut + window));
        terms[8] = clamp01(1.0 - (discarded / 1000.0) / TAIL_KEEP_SECONDS);
        known[8] = true;

        double total = 0.0;
        double weight = 0.0;
        // 前八项是<b>量出来的证据</b>；第⑨项是政策先验，只进名次不进这两个数。
        // 混在一起会出事：曲尾那一对音频上明明接不上（形状 0.10），却能靠「尾段没被砍」加
        // 「人声不冲突」两项安全分把平均分抬过弃权线 —— 弃权线问的是「这段接缝好不好」，
        // 不是「这个位置安不安全」。
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

    /** 频段互补：1 − Σmin/Σmax。两首歌低频都顶满会得到接近 0（浑浊），各占一头会得到 1。 */
    private static double bandComplement(Request request, long cut, long entry, long window) {
        int frames = request.outgoing.framesIn(window);
        if (frames < 4) return Double.NaN;
        double low = complementOf(request.outgoing.low, cut, request.incoming.low, entry, frames);
        double high = complementOf(request.outgoing.high, cut, request.incoming.high, entry, frames);
        if (Double.isNaN(low) && Double.isNaN(high)) return Double.NaN;
        if (Double.isNaN(low)) return high;
        if (Double.isNaN(high)) return low;
        return 0.5 * (low + high);
    }

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
        String[] names = {"形状", "电平", "涟漪", "互补", "起声", "漂移", "弧", "人声", "尾段"};
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
