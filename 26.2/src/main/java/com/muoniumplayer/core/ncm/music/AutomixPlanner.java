package com.muoniumplayer.core.ncm.music;

import java.util.Locale;

/**
 * 为两个已解码 deck 之间的交接构建 {@link AutomixPlan}。
 *
 * <p>结构性选择遵循参考实现所记载的 DJ 逻辑：在退出曲目人声唱完、
 * 编曲变薄处混音退出，在接入曲目前奏真正结束处混音进入，
 * 把交接落在小节边界上，并匹配响度使两边
 * 都不像掉了。</p>
 *
 * <p>在此之上，该计划还携带真正把 DJ 混音与普通交叉淡化区分开的东西。</p>
 * <ul>
 *   <li><b>低频段是交换而非叠加。</b>两首重叠曲目会把两个底鼓和两条
 *       贝斯线放进同一个八度，叠加成任何音量曲线都救不了的闷响，因此
 *       退出方 deck 的低 shelf 早早抽出，接入方的则压着直到它掌控
 *       混音。</li>
 *   <li><b>到达的曲目不带人声进入。</b>两个主唱同时出现是听众
 *       真能指出的毛病，而且它无法用 shelf 滤掉，因为人声与
 *       其他所有东西共用频段——因此接入方 deck 的限带中央被消除后再释放。</li>
 *   <li><b>两个节拍网格是相位锁定，而不只是速度匹配。</b>混音退出被量化到
 *       退出方的强拍、cue 被量化到接入方的强拍；量化之后剩下的部分
 *       被测量并交给计划的节奏缓冲，由它在渐变中段走掉。
 * 只匹配速度而不匹配相位，会让两个网格在整个重叠期内相差几分之拍，
 *       这正是 flamming。</li>
 *   <li><b>没有节拍时，速率预算转而花在调性上。</b>一个速率端口无法同时服务
 *       速度和音高，而整个 ±6 % 预算恰好是一个半音。因此落在 outro 或铺底上的
 *       混音退出（网格明显很弱）会得到一次整半音转调，使两个调性一致，
 *       而有真实节拍的混音退出则得到速度锁定。</li>
 * </ul>
 *
 * <p>只弯曲正在离开的 deck：JSyn 的速率控制同时移动音高和速度，
 * 在已衰减数 dB、正在退出的曲目上动几个百分点听不见，而对到达曲目这样做
 * 会让歌曲其余部分走调。</p>
 *
 * <p><b>分析从来不是必需的</b>：当窗口无法测量时，计划退化为普通的等功率交叉淡化，
 * 而非硬切——因为整个意义就在于
 * 队列在两首曲目之间永远不会静默。</p>
 */
final class AutomixPlanner {

    /** 用于速度推算的窗口；锁定一个节拍周期需要几秒。 */
    private static final long BEAT_WINDOW_MILLIS = 12_000L;
    /**
     * 接入曲目前奏可跳过多少。刻意保守：目的是避免
     * 混进死寂，而不是剪辑歌曲，因此一段刻意的长前奏会完整播放。
     */
    private static final long MAX_INTRO_SKIP_MILLIS = 12_000L;
    /** 前奏绝不允许吃掉短曲目超过这一份额。 */
    private static final double MAX_INTRO_SKIP_FRACTION = .12;
    /** 只在这个速度带内才声称节拍对齐，对齐参考实现的氛围判断。 */
    private static final float MIN_TEMPO_RATIO = .92f;
    private static final float MAX_TEMPO_RATIO = 1.08f;
    /**
     * 节拍网格需要达到多高置信度，才会在其上构建任何东西。
     *
     * <p>速度推算对任何输入都会返回一个数字，包括弦乐铺底。在一个并不
     * 真实存在的网格上动手，会把混音退出移动至多两秒到一条假想小节线上，并声称
     * 已对拍，这比完全不对齐更糟。同一阈值还决定
     * 速率预算花在速度还是调性上：低于它就没有节拍可锁定。</p>
     */
    private static final float MIN_GRID_CONFIDENCE = .28f;
    /** 这么小的弯曲听不见，不值得为此碰速率端口。 */
    private static final float MIN_USEFUL_RATE_DELTA = .004f;
    /**
     * 对两个网格自身速度精度的安全系数，用于构建死区，死区内
     * 两个速度视为相同。
     *
     * <p>分析纸面精度可达小数点后一位，但两个网格是在不同曲目、
     * 不同窗口上测得的——其中之一通常是变薄的 outro——实践中
     * 它们分歧大于理想值。把合并容差加倍，正是为了阻止锁定
     * 去"纠正"一对本已同步的曲子：在四秒重叠上弯曲 1.5 %，会在原本没有漂移处凭空
     * 多出 60 ms 漂移，这正是锁定所要防止的 flamming。</p>
     */
    private static final double TEMPO_NOISE_FACTOR = 2.0;
    /** 响度补偿保持温和；大修正听起来只会像音量跳变。 */
    private static final float MIN_GAIN = .65f;
    private static final float MAX_GAIN = 1.60f;
    /** 低于此值时退出曲目过短，重叠只能是硬切。 */
    private static final long MIN_OUTGOING_MILLIS = 12_000L;
    private static final long MIN_INCOMING_MILLIS = 8_000L;
    /**
     * 退出曲目尾声可拿出多少，换一个更早、更有音乐性的混音退出。在
     * 结构性 outro 处混音退出，正是让过渡听起来像有意为之的关键，但砍掉歌曲结尾
     * 正是用户会注意并抱怨的地方，因此修剪同时受绝对上限和
     * 曲目份额约束——落在这些界外的混音退出估计会被丢弃，
     * 而非被钳到界内。
     */
    private static final long MAX_TRIM_MILLIS = 12_000L;
    private static final double MIN_TRIM_POSITION = .60;
    /** 无歌词边界可用时，为寻找结构性 outro 而扫描的尾声长度。 */
    private static final long OUTRO_SCAN_MILLIS = 45_000L;
    /** 普通 LRC 行没有结束时间；假定最后一个词大致延续这么长。 */
    private static final long LYRIC_TAIL_MILLIS = 1_500L;

    /**
     * 要求节奏缓冲吸收的最大残余网格偏移。超过此值，两个网格
     * 其实并未描述同一个脉冲，而把退出方 deck 推来推去半拍
     * 会被听成 deck 绊了一下，而非修正。
     */
    private static final long MAX_PHASE_ERROR_MILLIS = 140L;
    /** 这么小的残余已在耳朵的 flamming 阈值之内。 */
    private static final long MIN_PHASE_ERROR_MILLIS = 6L;

    /** 低于此值的调性估计视为"未测得调性"。 */
    private static final float MIN_KEY_CONFIDENCE = .35f;
    /** 只有冲突至少这么糟，才值得为避免它而转调一个 deck。 */
    private static final float MIN_CLASH_TO_TRANSPOSE = .50f;
    /** ……而且转调必须至少换来这么多改进，才会施加。 */
    private static final float MIN_HARMONY_GAIN = .25f;
    /** 从此处起两个调性相互冲突，混音保持短促。 */
    private static final float CLASH_TRIM_PENALTY = .70f;
    private static final double CLASH_OVERLAP_SHARE = .55;
    private static final long MIN_CLASH_OVERLAP_MILLIS = 1_200L;
    /** 为冲突配对额外增加的人声消除，叠加在配置的深度之上。 */
    private static final float CLASH_EXTRA_DUCK = .12f;
    private static final float MAX_VOCAL_DUCK = .85f;

    private AutomixPlanner() {
    }

    static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming) {
        return plan(outgoing, incoming, -1L);
    }

    /**
     * @param lastVocalEndMillis 退出曲目最后一句歌词结束处的绝对位置，
     *                            无定时歌词时为非正值。在最后一句人声之后混音退出，
     *                            是混音听起来无缝的最主要原因：
     *                            两个人声永远不会相撞。
     * @return 计划；仅当两首曲子短到根本无法混音时为 {@code null}
     */
    static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming, long lastVocalEndMillis) {
        if (AutomixSettings.isFoliaEngine()) {
            return FoliaAutomixPlanner.plan(outgoing, incoming, lastVocalEndMillis);
        }
        if (outgoing == null || incoming == null) return null;
        if (!outgoing.isUsable() || !incoming.isUsable()) return null;

        long outgoingMillis = (long) outgoing.getTotalTimeMillis();
        long incomingMillis = (long) incoming.getTotalTimeMillis();
        if (outgoingMillis < MIN_OUTGOING_MILLIS || incomingMillis < MIN_INCOMING_MILLIS) return null;

        // 重叠时长绝不能超过退出曲目的四分之一或接入曲目的一半，
        // 否则短曲目会被几乎从头到尾地混音。
        long overlap = AutomixSettings.getOverlapMillis();
        overlap = Math.min(overlap, outgoingMillis / 4L);
        overlap = Math.min(overlap, incomingMillis / 2L);
        if (overlap < 800L) return null;

        // 分析用自己的句柄读取已解码缓存文件，绝不读取实时
        // JSyn 采样：那些采样经由一个共享滑动窗口流出，在音频
        // 线程下移动它会直接停掉播放（见 PcmWindowReader）。
        AnalysisAudio outgoingAudio = AnalysisAudioSource.open(outgoing.getSourceFile());
        AnalysisAudio incomingAudio = AnalysisAudioSource.open(incoming.getSourceFile());
        try {
            return plan(outgoingMillis, incomingMillis, overlap, lastVocalEndMillis,
                    outgoingAudio, incomingAudio);
        } finally {
            if (outgoingAudio != null) outgoingAudio.close();
            if (incomingAudio != null) incomingAudio.close();
        }
    }

    private static AutomixPlan plan(long outgoingMillis, long incomingMillis, long overlap,
                                    long lastVocalEndMillis, AnalysisAudio outgoingAudio,
                                    AnalysisAudio incomingAudio) {
        // 分析是加分项，不是必需项。缓存文件无法解析的配对仍会得到
        // 普通的等功率交叉淡化，而不是退回到旧的静默间隙。
        boolean analysable = outgoingAudio != null && incomingAudio != null;
        int outgoingRate = analysable ? outgoingAudio.sampleRate() : 0;
        int incomingRate = analysable ? incomingAudio.sampleRate() : 0;
        if (outgoingRate <= 0 || incomingRate <= 0) analysable = false;
        boolean beatAlign = AutomixSettings.isBeatAlignEnabled();

        // -- 接入方：跳过死寂前奏，然后对齐到其首个强拍。 --
        long cue = 0L;
        long incomingGridOrigin = 0L;
        AutomixAnalyzer.BeatGrid incomingGrid = null;
        AutomixAnalyzer.KeyEstimate incomingKey = null;
        if (analysable) {
            // 无论如何都会测量前奏在哪里结束，即使设置要求完整播放。节拍
            // 网格和调性必须从真正有内容的素材上读取：安静的前奏
            // 会被测成无脉冲、无主音，从而静默地禁用整个交接的速度锁定、相位
            // 缓冲与和声门控。只有*进入点*是可选的。
            long introEnd = 0L;
            long introScan = Math.min(MAX_INTRO_SKIP_MILLIS + BEAT_WINDOW_MILLIS, incomingMillis / 2L);
            float[] introWindow = incomingAudio.readMono(0L, introScan);
            if (introWindow.length > 0) {
                long detected = (long) AutomixAnalyzer.detectIntroEnd(introWindow, incomingRate);
                long introCap = Math.min(MAX_INTRO_SKIP_MILLIS,
                        (long) (incomingMillis * MAX_INTRO_SKIP_FRACTION));
                introEnd = Math.max(0L, Math.min(introCap, detected));
            }

            incomingGridOrigin = introEnd;
            float[] incomingBeatWindow = incomingAudio.readMono(introEnd,
                    Math.min(BEAT_WINDOW_MILLIS, incomingMillis - introEnd));
            incomingGrid = AutomixAnalyzer.analyzeBeats(incomingBeatWindow, incomingRate);
            // 同一个窗口回答调性问题。再读一个窗口会为已经在手的素材
            // 额外付出一次 ffmpeg 解码。
            incomingKey = AutomixAnalyzer.analyzeKey(incomingBeatWindow, incomingRate);
            if (AutomixSettings.isSkipIntroEnabled()) {
                cue = introEnd;
                if (beatAlign && usableGrid(incomingGrid)) {
                    long snapped = cue + (long) incomingGrid.barPhaseMillis;
                    // 仅当对齐之后身后仍留有完整的音频重叠时，才接受该对齐。
                    if (snapped + overlap < incomingMillis) cue = snapped;
                }
            }
            // 否则 cue 保持 0。下面的残余相位数学把每个网格折到其自身
            // 原点并卷绕到 ±半拍内，因此落在网格原点之前的 cue 由同一表达式
            // 处理——这里没有任何地方假定 cue >= incomingGridOrigin。
        }

        // -- 退出方：在歌唱完处混音退出，而不是文件结束处。 --
        long latestFire = outgoingMillis - overlap;
        long earliestFire = Math.max((long) (outgoingMillis * MIN_TRIM_POSITION),
                outgoingMillis - MAX_TRIM_MILLIS);
        if (earliestFire > latestFire) earliestFire = latestFire;

        long preferredFire = -1L;
        String mixOutReason = "尾部混出";
        if (lastVocalEndMillis > 0L) {
            long candidate = lastVocalEndMillis + LYRIC_TAIL_MILLIS;
            // 只在本曲目可被修剪的窗口内才信任。远早于
            // 该窗口的歌词结束不是长 outro，而是坏数据——歌词加载失败会保留上一首曲目的
            // 时间线——把它钳到边界上会静默地从每首歌末尾砍掉最大
            // 允许量。
            if (candidate >= earliestFire && candidate <= latestFire) {
                preferredFire = candidate;
                mixOutReason = "唱完后混出";
            }
        }
        if (preferredFire <= 0L && analysable) {
            long scanStart = Math.max(0L, latestFire - OUTRO_SCAN_MILLIS);
            float[] tail = outgoingAudio.readMono(scanStart, outgoingMillis - scanStart);
            float landing = AutomixAnalyzer.detectOutroLanding(tail, outgoingRate);
            if (landing >= 0f) {
                // 从音频本身测得，因此落在修剪预算之外的落点是真实证据，
                // 只是被拉回到边界。
                preferredFire = scanStart + (long) landing;
                mixOutReason = "尾奏混出";
            }
        }

        long fire = latestFire;
        if (preferredFire > 0L) {
            fire = Math.max(earliestFire, Math.min(latestFire, preferredFire));
        }

        // -- 把交接落在退出曲目的小节边界上。 --
        long outgoingGridOrigin = 0L;
        AutomixAnalyzer.BeatGrid outgoingGrid = null;
        AutomixAnalyzer.KeyEstimate outgoingKey = null;
        boolean barAligned = false;
        if (analysable) {
            // 从混音退出点反向分析：一个正向运行的窗口会在
            // 混音退出点靠近曲目末尾时恰好超出结尾，从而什么都返回不了。
            long gridStart = Math.max(0L, fire - BEAT_WINDOW_MILLIS);
            outgoingGridOrigin = gridStart;
            float[] outgoingBeatWindow = outgoingAudio.readMono(gridStart, fire - gridStart);
            outgoingGrid = AutomixAnalyzer.analyzeBeats(outgoingBeatWindow, outgoingRate);
            outgoingKey = AutomixAnalyzer.analyzeKey(outgoingBeatWindow, outgoingRate);
            if (beatAlign && usableGrid(outgoingGrid)) {
                float barPeriod = outgoingGrid.barPeriodMillis();
                if (barPeriod > 200f && barPeriod < 8_000f) {
                    long firstBar = gridStart + (long) outgoingGrid.barPhaseMillis;
                    long nearest = Math.round((fire - firstBar) / (double) barPeriod);
                    // 最近的小节线常常就在混音退出允许的窗口之外——
                    // 默认混音退出*就是* latestFire，因此有一半时间最近小节线在它之后，
                    // 旧代码随后就完全放弃对齐。前一小节仍是一条小节线，
                    // 仍有音乐性，因此按距离顺序尝试邻近小节线。
                    long[] bars = {nearest, nearest - 1L, nearest + 1L, nearest - 2L, nearest + 2L};
                    for (int index = 0; index < bars.length; index++) {
                        long candidate = firstBar + (long) (bars[index] * barPeriod);
                        if (candidate >= earliestFire && candidate <= latestFire && candidate > 0L) {
                            fire = candidate;
                            barAligned = true;
                            break;
                        }
                    }
                }
            }
        }

        // -- 在真正会重叠的两个区间上做响度匹配。 --
        float gain = 1f;
        if (analysable) {
            float[] outgoingZone = outgoingAudio.readMono(fire, Math.min(overlap, outgoingMillis - fire));
            float[] incomingZone = incomingAudio.readMono(cue, Math.min(overlap, incomingMillis - cue));
            // 先用 K 加权：那是耳朵的工作方式。RMS 只作为被门限拒绝窗口的兜底，
            // 例如重叠恰好落在近静默尾声上时。
            float outgoingLufs = AutomixAnalyzer.kWeightedLoudness(outgoingZone, outgoingRate);
            float incomingLufs = AutomixAnalyzer.kWeightedLoudness(incomingZone, incomingRate);
            if (!Float.isNaN(outgoingLufs) && !Float.isNaN(incomingLufs)) {
                gain = (float) Math.pow(10.0, (outgoingLufs - incomingLufs) / 20.0);
            } else {
                float outgoingRms = AutomixAnalyzer.rms(outgoingZone);
                float incomingRms = AutomixAnalyzer.rms(incomingZone);
                if (outgoingRms > .001f && incomingRms > .001f) {
                    gain = outgoingRms / incomingRms;
                }
            }
            gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, gain));
        }

        // -- 氛围判断：只有当速度确实接近时才声称已对拍。 --
        boolean gridsUsable = usableGrid(outgoingGrid) && usableGrid(incomingGrid);
        boolean tempoMatched = false;
        if (gridsUsable) {
            float normalisedIncoming = AutomixAnalyzer.octaveNormalise(incomingGrid.bpm, outgoingGrid.bpm);
            float ratio = outgoingGrid.bpm / normalisedIncoming;
            tempoMatched = ratio >= MIN_TEMPO_RATIO && ratio <= MAX_TEMPO_RATIO;
        }

        // -- 速率预算。速度或音高，从不兼有：一个端口同时移动二者，而可移动的整个
        //     ±6 % 恰好是一个半音。 --
        float tempoRatio = 1f;
        int semitoneShift = 0;
        long phaseError = 0L;
        boolean tempoLocked = false;
        if (tempoMatched && AutomixSettings.isTempoLockEnabled()) {
            float normalisedIncoming = AutomixAnalyzer.octaveNormalise(incomingGrid.bpm, outgoingGrid.bpm);
            float desired = normalisedIncoming / outgoingGrid.bpm;
            tempoRatio = Math.max(AutomixSettings.MIN_PLAYBACK_RATE,
                    Math.min(AutomixSettings.MAX_PLAYBACK_RATE, desired));
            // 由测量自身精度得出的死区，绝不小于可闻下限。
            double deadband = Math.max(MIN_USEFUL_RATE_DELTA, TEMPO_NOISE_FACTOR
                    * Math.hypot(outgoingGrid.tempoTolerance, incomingGrid.tempoTolerance));
            if (Math.abs(tempoRatio - 1f) < deadband) tempoRatio = 1f;
            tempoLocked = true;
            // 相位锁定只有在两个网格都被信任、且混音退出确实落在
            // 退出方强拍上时才有意义；否则"残余"是对照一条
            // 从未使用过的线测得的。
            if (barAligned) {
                phaseError = residualPhaseMillis(outgoingGrid, outgoingGridOrigin, fire,
                        incomingGrid, incomingGridOrigin, cue);
            }
        } else if (AutomixSettings.isPitchMatchEnabled() && !tempoMatched
                && confidentKey(outgoingKey) && confidentKey(incomingKey)) {
            // 仅在没有节拍关系可保护时到达此处：要么网格太弱
            // 不足信（outro、淡出、铺底），要么两个速度相差超过 8 %，此时
            // 无论如何都会漂移，再动几个百分点也无济于事。因此速率
            // 预算是空闲的，而这种情况下听众听到的是和声。注意
            // 一对锁定结果恰好为 1.000 的配对*不会*落到这里：弯曲那个
            // deck 会破坏本已存在的对拍。
            float straight = AutomixAnalyzer.harmonicPenalty(outgoingKey, incomingKey, 0);
            if (straight >= MIN_CLASH_TO_TRANSPOSE) {
                int shift = AutomixAnalyzer.bestSemitoneShift(outgoingKey, incomingKey, 1);
                float shifted = AutomixAnalyzer.harmonicPenalty(outgoingKey, incomingKey, shift);
                if (shift != 0 && shifted <= straight - MIN_HARMONY_GAIN) {
                    semitoneShift = shift;
                    tempoRatio = shift > 0
                            ? AutomixSettings.SEMITONE_RATIO
                            : 1f / AutomixSettings.SEMITONE_RATIO;
                }
            }
        }

        // -- 和声门控。相差一个半音或三全音的两个调性，是持续越久就越糟的唯一配对，
        //    因此缩短混音并更重地压下到达方人声。
        //    只缩短不延长：更长的重叠会使上面的混音退出窗口失效。 --
        boolean keysKnown = confidentKey(outgoingKey) && confidentKey(incomingKey);
        float harmony = keysKnown
                ? AutomixAnalyzer.harmonicPenalty(outgoingKey, incomingKey, semitoneShift)
                : .5f;
        boolean clashing = keysKnown && harmony >= CLASH_TRIM_PENALTY;
        if (clashing) {
            long trimmed = Math.max(MIN_CLASH_OVERLAP_MILLIS, (long) (overlap * CLASH_OVERLAP_SHARE));
            if (trimmed < overlap) overlap = trimmed;
        }

        boolean bassSwap = AutomixSettings.isBassSwapEnabled();
        float vocalDuck = AutomixSettings.isVocalDuckEnabled() ? AutomixSettings.VOCAL_DUCK_DEPTH : 0f;
        if (vocalDuck > 0f && clashing) {
            vocalDuck = Math.min(MAX_VOCAL_DUCK, vocalDuck + CLASH_EXTRA_DUCK);
        }

        boolean beatMatched = barAligned && tempoMatched;
        String summary = describe(overlap, cue, gain, beatMatched, mixOutReason, outgoingGrid,
                incomingGrid, bassSwap, tempoRatio, semitoneShift, phaseError, vocalDuck,
                keysKnown ? outgoingKey : null, keysKnown ? incomingKey : null);
        System.out.println("[Automix] plan: fire=" + fire + "/" + outgoingMillis + "ms overlap=" + overlap
                + "ms cue=" + cue + "ms gain=" + String.format(Locale.ROOT, "%.2f", gain)
                + " (" + mixOutReason + (beatMatched ? ", 小节对齐" : "") + (bassSwap ? ", 低频交换" : "")
                + (vocalDuck > 0f ? String.format(Locale.ROOT, ", 去人声 %.0f%%", vocalDuck * 100f) : "")
                + (tempoRatio == 1f ? "" : String.format(Locale.ROOT,
                        tempoLocked ? ", 速率 %.3f" : ", 移调 %.3f", tempoRatio))
                + (semitoneShift == 0 ? "" : (semitoneShift > 0 ? ", +1 半音" : ", -1 半音"))
                + (phaseError == 0L ? "" : ", 相位 " + phaseError + "ms")
                + (keysKnown ? ", 调性 " + outgoingKey.label() + "→" + incomingKey.label()
                        + String.format(Locale.ROOT, "(%.2f)", harmony) : "")
                + (analysable ? "" : ", 无分析") + ")");
        // semitoneShift != 0 是「移调」分支的唯一标记：那时 tempoRatio 表达的是音高倍率，
        // 交给 WSOLA 当速度用会把意图整个搞反（见 AutomixPlan#pitchShift）。
        return new AutomixPlan(fire, overlap, cue, gain, beatMatched, bassSwap, tempoRatio,
                semitoneShift != 0, phaseError, vocalDuck, summary);
    }

    /** 一个网格只有在存在、速度合理且测得有置信度时才可用。 */
    private static boolean usableGrid(AutomixAnalyzer.BeatGrid grid) {
        return grid != null && grid.bpm > 1f && grid.confidence >= MIN_GRID_CONFIDENCE;
    }

    private static boolean confidentKey(AutomixAnalyzer.KeyEstimate key) {
        return key != null && key.confidence >= MIN_KEY_CONFIDENCE;
    }

    /**
     * 为使两个强拍网格在交接处对齐，退出方 deck 需要向前推多远，
     * 单位 ms。正值表示退出方强拍晚到。
     *
     * <p>两边都按"交接瞬间该 deck 越过了强拍多远"来测，使用各自网格的原点，
     * 差值卷绕到 ±半拍内。是半
     * <em>拍</em>，不是半小节：耳朵在两个网格不一致时听到的是拍的 flamming，而为对齐小节数把 deck 推三拍，
     * 比对问题本身的干预
     * 大得多。</p>
     *
     * <p>当答案已在 flamming 阈值之内，或大到两个网格
     * 显然未描述同一脉冲时，返回 0。</p>
     */
    private static long residualPhaseMillis(AutomixAnalyzer.BeatGrid outgoingGrid, long outgoingOrigin,
                                            long fire, AutomixAnalyzer.BeatGrid incomingGrid,
                                            long incomingOrigin, long cue) {
        if (outgoingGrid == null || incomingGrid == null) return 0L;
        double outgoingBeat = outgoingGrid.beatPeriodMillis();
        double incomingBeat = incomingGrid.beatPeriodMillis();
        if (outgoingBeat <= 20.0 || incomingBeat <= 20.0) return 0L;

        double outgoingPhase = wrapSigned(
                fire - (outgoingOrigin + outgoingGrid.barPhaseMillis), outgoingBeat);
        double incomingPhase = wrapSigned(
                cue - (incomingOrigin + incomingGrid.barPhaseMillis), incomingBeat);

        double reference = (outgoingBeat + incomingBeat) * .5;
        long error = Math.round(wrapSigned(incomingPhase - outgoingPhase, reference));
        if (Math.abs(error) < MIN_PHASE_ERROR_MILLIS) return 0L;
        if (Math.abs(error) > MAX_PHASE_ERROR_MILLIS) return 0L;
        return error;
    }

    /** 到最近的 {@code period} 整数倍的有符号距离，范围 [-period/2, period/2]。 */
    private static double wrapSigned(double value, double period) {
        if (period <= 0.0 || Double.isNaN(value)) return 0.0;
        double wrapped = value % period;
        if (wrapped > period * .5) wrapped -= period;
        if (wrapped < -period * .5) wrapped += period;
        return wrapped;
    }

    private static String describe(long overlap, long cue, float gain, boolean beatMatched,
                                   String mixOutReason,
                                   AutomixAnalyzer.BeatGrid outgoingGrid,
                                   AutomixAnalyzer.BeatGrid incomingGrid,
                                   boolean bassSwap, float tempoRatio, int semitoneShift,
                                   long phaseError, float vocalDuck,
                                   AutomixAnalyzer.KeyEstimate outgoingKey,
                                   AutomixAnalyzer.KeyEstimate incomingKey) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.ROOT, "%.1fs 重叠", overlap / 1000f));
        builder.append(" · ").append(mixOutReason);
        if (beatMatched && outgoingGrid != null && incomingGrid != null) {
            builder.append(" · 小节对齐 ")
                    .append(Math.round(outgoingGrid.bpm)).append("→").append(Math.round(incomingGrid.bpm))
                    .append(" BPM");
        } else {
            builder.append(" · 等功率淡化");
        }
        if (cue > 400L) {
            builder.append(String.format(Locale.ROOT, " · 跳过前奏 %.1fs", cue / 1000f));
        }
        if (bassSwap) {
            builder.append(" · 低频交换");
        }
        if (vocalDuck > 0f) {
            builder.append(String.format(Locale.ROOT, " · 去人声 %.0f%%", vocalDuck * 100f));
        }
        if (semitoneShift != 0) {
            builder.append(semitoneShift > 0 ? " · 移调 +1 半音" : " · 移调 -1 半音");
        } else if (tempoRatio != 1f) {
            builder.append(String.format(Locale.ROOT, " · 节奏锁定 %+.1f%%", (tempoRatio - 1f) * 100f));
        }
        if (phaseError != 0L) {
            builder.append(String.format(Locale.ROOT, " · 相位补偿 %+dms", phaseError));
        }
        if (outgoingKey != null && incomingKey != null) {
            builder.append(" · ").append(outgoingKey.label()).append("→").append(incomingKey.label());
        }
        if (Math.abs(gain - 1f) > .08f) {
            builder.append(String.format(Locale.ROOT, " · 音量补偿 %.2fx", gain));
        }
        return builder.toString();
    }
}
