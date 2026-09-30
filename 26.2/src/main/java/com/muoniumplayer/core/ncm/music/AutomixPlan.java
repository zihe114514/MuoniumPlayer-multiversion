package com.muoniumplayer.core.ncm.music;

/**
 * 一次规划好的 automix 交接。位置为各自曲目内的绝对毫秒。
 *
 * <p>{@link #fireMillis} 是退出方 deck 开始淡出之处，{@link #incomingCueMillis} 是
 * 接入方 deck 开始播放之处，两条渐变都持续 {@link #overlapMillis}。渐变是等功率的
 * （{@code cos}/{@code sin}），因此混音过程中感知响度保持平直，而不像线性淡入淡出那样
 * 在中间下凹。</p>
 *
 * <p>在音量渐变之上，该计划还携带把 DJ 混音与普通交叉淡化区分开的
 * 四样东西：</p>
 * <ul>
 *   <li><b>低音交换</b>（{@link #outgoingBassDb}/{@link #incomingBassDb}），使低频段
 *       任一时刻只属于一首曲目；</li>
 *   <li><b>人声闪避</b>（{@link #incomingVocalDepth}），让到达的曲目以
 *       纯伴奏底形式进入，使两个主唱永不同时可闻；</li>
 *   <li><b>速度锁定</b>和<b>节奏缓冲</b>（{@link #outgoingRate}），把退出方
 *       deck 弯曲到接入方速度，再逐步走掉残余相位误差，而不是让两个网格在整个重叠期内
 *       飘忽（flam）；</li>
 *   <li>或在没有值得锁定的节拍时，做一次<b>整半音转调</b>——同样由
 *       {@link #tempoRatio} 承载，因为一个速率端口无法同时服务速度和音高。</li>
 * </ul>
 *
 * <p>这里的每条曲线都是渐变位置的纯函数，这正是整个时间表可离线检查的原因：
 * 播放器中的驱动器只负责采样它们。</p>
 */
final class AutomixPlan {

    /** 到渐变的这一点时，退出方低频段已完全让开。 */
    private static final double BASS_CUT_COMPLETE = .5;
    /** 接入方低频段一直压到这里，然后抬升。 */
    private static final double BASS_ENTRY_START = .3;
    private static final double BASS_ENTRY_COMPLETE = .8;
    /**
     * 速度弯曲施加在渐变最开头的一小段。瞬间施加会造成音高台阶；
     * 太慢则会让网格在修正生效所需的整段时间里一直漂移。
     */
    private static final double RATE_RAMP_COMPLETE = .15;
    /**
     * 节奏缓冲：渐变中逐步走掉残余相位误差的那一段。
     *
     * <p>它只在速度弯曲稳定后才开始，并在渐变结束前结束，因此退出方
     * deck 在静默时已回到锁定速度——而不是仍被推着走。使用升余弦
     * 而非矩形脉冲，是因为它两端导数为零：
     * 修正没有边缘，10 ms 的驱动器不会从中采样出台阶。</p>
     */
    private static final double PHASE_BUFFER_START = RATE_RAMP_COMPLETE;
    private static final double PHASE_BUFFER_COMPLETE = .85;
    /**
     * 在速度锁定之上，相位修正所用额外速率的上限。2 % 约为
     * 三分之一个半音，在已衰减数 dB 的 deck 上听不见；超过这个量
     * 就会被听成抖动而非修正。
     */
    private static final double MAX_PHASE_RATE_BUMP = .02;
    /**
     * 仍可弯曲退出方 deck 播放速率的最短渐变。
     *
     * <p>速率修正会被听成音高变化，因为 reader 是重采样而非
     * 时间拉伸。在规划好的多小节混音中这种取舍值得，两个网格确实会锁定。
     * 但在这么短的渐变上绝不值得：退出曲目剩下的不足一拍，
     * 没有网格可锁，听众听到的只有
     * 那一个半音的音高。用户点的跳过和迟到触发都会落到这里。</p>
     */
    private static final long MIN_RATE_CORRECTION_OVERLAP_MILLIS = 1_500L;

    /** 人声消除在达到全深之前一直保持，然后释放。
     *
     * <p>包内可见而不是 private：{@link AutomixAutoCue} 的第 ⑧ 项要按<b>同一条曲线</b>判断
     * 「尾句唱到哪儿才算让开了」。这个数在这里写两遍，选点就会按一条曲线打分、播放时按另一条执行。</p>
     */
    static final double VOCAL_DUCK_RELEASE_START = .45;
    /** ……到这里完全消失，使混音中不再有任何东西残留进曲目。 */
    private static final double VOCAL_DUCK_RELEASE_COMPLETE = .92;
    /**
     * 进阶模式下<b>出场方</b>人声开始让路之处。
     *
     * <p>出场方的人声在整个前半段都必须完整——听众要跟的是这一句。只有当包络证据显示
     * <b>这一句越过了窗口还没唱完</b>时才启用这条曲线，否则它恒为 0，两侧听感与今天逐位一致。</p>
     */
    static final double OUTGOING_VOCAL_DUCK_START = .5;
    private static final double OUTGOING_VOCAL_DUCK_COMPLETE = .96;

    final long fireMillis;
    final long overlapMillis;
    final long incomingCueMillis;
    /** 接入方 deck 的响度匹配，仅在混音期间施加。 */
    final float matchGain;
    final boolean barAligned;
    /** 混音期间是否在两个 deck 之间交换低频段。 */
    final boolean bassSwap;
    /** Folia 策略字段；旧规划器上为 null。 */
    final FoliaTransitionStyle style;
    final float midTiltDb;
    final float highTiltDb;
    final boolean echoThrow;
    /** 由音轨分离（stem）得出的交接位置，以混音的分数表示；默认为旧曲线。 */
    final double drumHandoverAt;
    final double bassHandoverAt;
    final double vocalEntryAt;
    final boolean stemDriven;
    /** 每个退出方拍的墙钟毫秒数，用于过渡视觉的脉冲。 */
    final double animationPeriodMillis;
    /**
     * 退出方 deck 被弯曲到的播放速率，1 表示"不要动它"。要么是速度锁定（两个
     * 节拍网格都可测、接近且置信），要么是整半音的调性对齐（没有值得锁定的节拍），
     * 二者从不兼有——且无论哪种都被钳制，使音高移动保持在一个半音内。
     */
    final float tempoRatio;
    /**
     * {@link #tempoRatio} 承载的是<b>移调</b>而不是变速。
     *
     * <p>两条互斥的分支都用同一个 {@code tempoRatio} 表达（见 {@link AutomixPlanner}）：节奏锁定想改的是
     * <b>速度</b>，整半音吸附想改的是<b>音高</b>。在只有重采样的年代这个区分没有意义 —— 一个 rate 端口
     * 同时改速度和音高，两种意图碰巧共用一个数。</p>
     *
     * <p>接上 WSOLA 之后就必须分开了：时间伸缩只改速度、不改音高，如果照旧把 {@code tempoRatio} 一律
     * 当速度用，「移调 +1 半音」会变成「变速 +6%」—— 想改的音高一点没动，不想改的速度动了 6%。
     * 所以这里记下意图，由 {@link #outgoingTempo} 与 {@link #outgoingPitch} 分别产出两个倍率。</p>
     */
    final boolean pitchShift;
    /**
     * 交接瞬间两个节拍网格之间的残余偏移，单位 ms。正值表示
     * 退出方 deck 的强拍<em>晚</em>到，需要向前推。
     *
     * <p>这是接入 cue 已对齐到强拍之后剩下的部分：在约 11.6 ms 包络步长上测得的两个网格
     * 无法被量化成完全一致，而几十 ms 在两首曲子都满音量时
     * 会被听成飘忽（flam）。{@link #outgoingRate} 把节奏
     * 缓冲用在它身上。</p>
     */
    final long phaseErrorMillis;
    /**
     * 接入方 deck 的人声频段消除峰值深度，0 表示禁用。在混音前半段保持，
     * 并在结束前释放。
     */
    final float vocalDuckDepth;
    /**
     * 进阶模式下<b>出场方</b>的人声让路深度，0 表示不启用这条曲线。
     *
     * <p>它只在整曲包络显示出场曲这一句越过交叠窗还没唱完时才非零——那种情况下
     * 只压进场方会留下两个人同时唱，压出场方才是对的（听众跟的那一句已经过了落点）。
     * 默认 0，因此旧计划、房间里的旧版本、以及关闭进阶模式时，出场方的这条口一直是
     * {@code VocalDuckFilter} 的位精确直通分支。</p>
     */
    final float outgoingVocalDuckDepth;
    /**
     * 人声让路的释放点，交叠窗的分数；{@code <= 0} 表示没有整曲包络证据，用默认常数。
     *
     * <p>与 {@link #vocalEntryAt} 分工：那个是 ONNX 分轨测出来的，只有装了模型且
     * 表现模式打开才有；这个是包络上量出来的「出场曲掉到哪儿为止还在唱」，
     * 没有模型时也照样能用。分轨证据优先。</p>
     */
    final double vocalReleaseAt;
    final String summary;

    AutomixPlan(long fireMillis, long overlapMillis, long incomingCueMillis, float matchGain,
                boolean barAligned, boolean bassSwap, float tempoRatio, boolean pitchShift,
                long phaseErrorMillis, float vocalDuckDepth, String summary) {
        this(fireMillis, overlapMillis, incomingCueMillis, matchGain, barAligned, bassSwap,
                tempoRatio, pitchShift, phaseErrorMillis, vocalDuckDepth, summary,
                null, 0f, 0f, false, .42, .42, .45, false, 500.0);
    }

    AutomixPlan(long fireMillis, long overlapMillis, long incomingCueMillis, float matchGain,
                boolean barAligned, boolean bassSwap, float tempoRatio, boolean pitchShift,
                long phaseErrorMillis, float vocalDuckDepth, String summary,
                FoliaTransitionStyle style, float midTiltDb, float highTiltDb, boolean echoThrow,
                double drumHandoverAt, double bassHandoverAt, double vocalEntryAt, boolean stemDriven,
                double animationPeriodMillis) {
        this(fireMillis, overlapMillis, incomingCueMillis, matchGain, barAligned, bassSwap,
                tempoRatio, pitchShift, phaseErrorMillis, vocalDuckDepth, summary, style,
                midTiltDb, highTiltDb, echoThrow, drumHandoverAt, bassHandoverAt, vocalEntryAt,
                stemDriven, animationPeriodMillis, 0f, 0.0);
    }

    AutomixPlan(long fireMillis, long overlapMillis, long incomingCueMillis, float matchGain,
                boolean barAligned, boolean bassSwap, float tempoRatio, boolean pitchShift,
                long phaseErrorMillis, float vocalDuckDepth, String summary,
                FoliaTransitionStyle style, float midTiltDb, float highTiltDb, boolean echoThrow,
                double drumHandoverAt, double bassHandoverAt, double vocalEntryAt, boolean stemDriven,
                double animationPeriodMillis, float outgoingVocalDuckDepth, double vocalReleaseAt) {
        this.pitchShift = pitchShift;
        this.fireMillis = fireMillis;
        this.overlapMillis = overlapMillis;
        this.incomingCueMillis = incomingCueMillis;
        this.matchGain = matchGain;
        this.barAligned = barAligned;
        this.bassSwap = bassSwap;
        this.tempoRatio = tempoRatio <= 0f || Float.isNaN(tempoRatio) ? 1f : tempoRatio;
        this.phaseErrorMillis = phaseErrorMillis;
        this.vocalDuckDepth = Float.isNaN(vocalDuckDepth)
                ? 0f : Math.max(0f, Math.min(1f, vocalDuckDepth));
        this.summary = summary == null ? "" : summary;
        this.style = style;
        this.midTiltDb = clampTilt(midTiltDb);
        this.highTiltDb = clampTilt(highTiltDb);
        this.echoThrow = echoThrow;
        // 规划器已经把 stem 证据限制在可用窗口内；这里不能再把模型测得的后段鼓/低音换手静默提前。
        this.drumHandoverAt = clampFraction(drumHandoverAt, .18, .88);
        this.bassHandoverAt = clampFraction(bassHandoverAt, .18, .88);
        this.vocalEntryAt = clampFraction(vocalEntryAt, .28, .88);
        this.stemDriven = stemDriven;
        this.animationPeriodMillis = Double.isFinite(animationPeriodMillis)
                ? Math.max(120.0, Math.min(4_000.0, animationPeriodMillis)) : 500.0;
        this.outgoingVocalDuckDepth = Float.isNaN(outgoingVocalDuckDepth)
                ? 0f : Math.max(0f, Math.min(1f, outgoingVocalDuckDepth));
        // 0 或负数是「没有包络证据」，不是「在第 0 帧释放」——后者会让进场方一进来就开人声。
        this.vocalReleaseAt = Double.isFinite(vocalReleaseAt) && vocalReleaseAt > 0.0
                ? Math.max(.20, Math.min(.85, vocalReleaseAt)) : 0.0;
    }

    /** Folia 只降低混音中段，并让两端保持精确单位增益。 */
    private static final double FOLIA_BLEND_HEADROOM_DB = 1.5;

    /** 渐变 [0,1] 中 {@code progress} 处的退出方增益。 */
    float outgoingGain(double progress) {
        double p = clamp(progress);
        double shape = style != null && style.together > 0.0
                ? togetherGain(p, true) : Math.cos(p * Math.PI * .5);
        return (float) (shape * blendHeadroom(p));
    }

    /**
     * 渐变 [0,1] 中 {@code progress} 处的接入方增益。
     *
     * <p>响度匹配在混音开始时最强，此时接入曲目必须
     * 顶住退出曲目，然后在结束前插值回单位增益。这样补偿
     * 永远不会以卡住的音量偏移形式残留进曲目其余部分，混音结束时也没有
     * 可闻的台阶。</p>
     */
    float incomingGain(double progress) {
        double p = clamp(progress);
        double remaining = 1.0 - p;
        double match = 1.0 + (matchGain - 1.0) * remaining * remaining;
        double shape = style != null && style.together > 0.0
                ? togetherGain(p, false) : Math.sin(p * Math.PI * .5);
        return (float) (shape * match * blendHeadroom(p));
    }

    private double blendHeadroom(double progress) {
        if (style == null) return 1.0;
        double attenuationDb = -FOLIA_BLEND_HEADROOM_DB * Math.sin(progress * Math.PI);
        return Math.pow(10.0, attenuationDb / 20.0);
    }

    /** 带平台期的等功率曲线，两 deck 在平台期都保持在场。 */
    private double togetherGain(double progress, boolean outgoing) {
        double together = Math.max(0.0, Math.min(0.8, style.together));
        double edge = (1.0 - together) * 0.5;
        if (edge < 1e-6) return outgoing ? Math.cos(Math.PI * .25) : Math.sin(Math.PI * .25);
        double u;
        if (progress < edge) u = 0.25 * (progress / edge);
        else if (progress > 1.0 - edge) u = 0.25 + 0.25 * ((progress - (1.0 - edge)) / edge);
        else u = 0.25;
        return outgoing ? Math.cos(u * Math.PI) : Math.sin(u * Math.PI);
    }

    private static double clampFraction(double value, double minimum, double maximum) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
    private static float clampTilt(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(-3f, Math.min(3f, value));
    }

    /**
     * 退出方 deck 的低 shelf 增益（dB）。
     *
     * <p>早早拉下并保持：退出曲目远在交音量之前就把低频交出去，
     * 这正是阻止两个底鼓叠加的关键。听起来像退出曲目在"变薄"，
     * 和 DJ 拧低频旋钮是同一个动作。</p>
     */
    float outgoingBassDb(double progress) {
        if (!bassSwap) return 0f;
        double p = clamp(progress);
        if (stemDriven) {
            double start = Math.max(0.0, bassHandoverAt - .12);
            if (p <= start) return 0f;
            if (p >= bassHandoverAt) return AutomixSettings.BASS_CUT_DB;
            double share = (p - start) / Math.max(.01, bassHandoverAt - start);
            return (float) (AutomixSettings.BASS_CUT_DB * smoothstep(share));
        }
        double legacy = p / BASS_CUT_COMPLETE;
        if (legacy > 1.0) legacy = 1.0;
        return (float) (AutomixSettings.BASS_CUT_DB * legacy);
    }

    /**
     * 接入方 deck 的低 shelf 增益（dB）。
     *
     * <p>在退出曲目仍掌控低频时压着，在混音结束前拉回平直，
     * 使新曲独自播放时永远不会显得单薄。</p>
     */
    float incomingBassDb(double progress) {
        if (!bassSwap) return 0f;
        double p = clamp(progress);
        if (stemDriven) {
            if (p <= bassHandoverAt) return AutomixSettings.BASS_ENTRY_CUT_DB;
            double complete = Math.min(.94, bassHandoverAt + .14);
            if (p >= complete) return 0f;
            double share = (p - bassHandoverAt) / Math.max(.01, complete - bassHandoverAt);
            return (float) (AutomixSettings.BASS_ENTRY_CUT_DB * (1.0 - smoothstep(share)));
        }
        if (p <= BASS_ENTRY_START) return AutomixSettings.BASS_ENTRY_CUT_DB;
        if (p >= BASS_ENTRY_COMPLETE) return 0f;
        double share = (p - BASS_ENTRY_START) / (BASS_ENTRY_COMPLETE - BASS_ENTRY_START);
        return (float) (AutomixSettings.BASS_ENTRY_CUT_DB * (1.0 - share));
    }

    /**
     * 接入方 deck 的人声频段消除深度，0..1。
     *
     * <p>退出曲目仍在唱时保持全深，然后沿 smoothstep 释放，使
     * 到达的曲目逐渐长成完整混音，而不是突然打开人声。在渐变的
     * {@value #VOCAL_DUCK_RELEASE_COMPLETE} 处归零：听众保留的那个 deck 必须平直，
     * 且要在混音结束前——而不是驱动器恰好注意到时。</p>
     */
    /** 接入方 deck 的中频补偿，在混音结束前缓回平直。 */
    float incomingMidDb(double progress) {
        return toneEntry(midTiltDb, progress, stemDriven ? drumHandoverAt : .85);
    }

    /** 接入方 deck 的高频补偿，在混音结束前缓回平直。 */
    float incomingHighDb(double progress) {
        return toneEntry(highTiltDb, progress, .85);
    }

    /**
     * 进入时匹配前一首曲目的频谱平衡，可去掉最明显的"换了个母带"
     * 信号，随后补偿走回零，使听众保持接入曲目母带的完整性。
     */
    private static float toneEntry(float tiltDb, double progress, double releaseAt) {
        if (tiltDb == 0f) return 0f;
        double end = Math.max(.18, Math.min(.95, releaseAt));
        double p = clamp(progress);
        if (p >= end) return 0f;
        return (float) (tiltDb * (1.0 - smoothstep(p / end)));
    }
    float incomingVocalDepth(double progress) {
        if (vocalDuckDepth <= 0f) return 0f;
        double p = clamp(progress);
        // 分轨证据（ONNX）优先，其次是整曲包络量出来的释放点，两者都没有才用默认常数。
        double releaseStart = stemDriven ? vocalEntryAt
                : (vocalReleaseAt > 0.0 ? vocalReleaseAt : VOCAL_DUCK_RELEASE_START);
        double releaseComplete = stemDriven || vocalReleaseAt > 0.0
                ? Math.min(.96, Math.max(.72, releaseStart + .18))
                : VOCAL_DUCK_RELEASE_COMPLETE;
        if (p <= releaseStart) return vocalDuckDepth;
        if (p >= releaseComplete) return 0f;
        double share = (p - releaseStart) / (releaseComplete - releaseStart);
        return (float) (vocalDuckDepth * (1.0 - smoothstep(share)));
    }

    /**
     * 出场方 deck 的人声让路深度，0..1。与进场方<b>反向</b>：进场方是先没声音再长回来，
     * 出场方是前半段必须完整（听众跟的是这一句），到窗口后半段才逐渐让开。
     *
     * <p>恒为 0 除非规划器确认「这一句越过了交叠窗还没唱完」。所以关闭进阶模式、
     * 房间里的旧版本、以及所有旧计划，这一条都是 {@code VocalDuckFilter} 的位精确直通分支，
     * 听感与今天逐位一致。</p>
     */
    float outgoingVocalDepth(double progress) {
        if (outgoingVocalDuckDepth <= 0f) return 0f;
        double p = clamp(progress);
        double start = stemDriven ? Math.min(.85, Math.max(.35, vocalEntryAt)) : OUTGOING_VOCAL_DUCK_START;
        double complete = Math.min(OUTGOING_VOCAL_DUCK_COMPLETE, start + .30);
        if (p <= start) return 0f;
        if (p >= complete) return outgoingVocalDuckDepth;
        double share = (p - start) / Math.max(.01, complete - start);
        return (float) (outgoingVocalDuckDepth * smoothstep(share));
    }

    /**
     * 渐变此时刻退出方 deck 的播放速率；无需修正时为 1。
     *
     * <p>这里求和了两样东西。<b>速度锁定</b>在渐变的第一个 {@value #RATE_RAMP_COMPLETE}
     * 内从 1 缓到 {@link #tempoRatio}——沿 smoothstep 而非直线，
     * 因为线性渐变起止都有拐角，而速率上的拐角就是音高上的咔哒声。
     * <b>节奏缓冲</b>再叠加一个升余弦凸起，其面积恰好等于残余相位误差，
     * 使退出方网格滑入与接入方一致，并最终回到其锁定
     * 速度。</p>
     *
     * <p>结果被钳制到共享速率上下限：无论测得误差多大，修正都不能把 deck
     * 推出一个半音的预算之外。</p>
     */
    float outgoingRate(double progress) {
        return outgoingRate(progress, overlapMillis);
    }

    /**
     * 退出方 deck 的播放速率，按实际正在运行的渐变求值。
     *
     * <p>{@code rampMillis} 并不总是 {@link #overlapMillis}：用户点的跳过和迟到触发
     * 都会缩短它。这影响两处。节奏缓冲按其实际拥有的窗口来定幅，
     * 若在那里使用规划长度，只会把 deck 移动所求解误差的一小部分。
     * 而低于 {@value #MIN_RATE_CORRECTION_OVERLAP_MILLIS} 时修正被直接
     * 丢弃——见该常量。</p>
     */
    float outgoingRate(double progress, long rampMillis) {
        if (rampMillis < MIN_RATE_CORRECTION_OVERLAP_MILLIS) return 1f;
        double p = clamp(progress);
        double base = baseBend(p);
        double bump = phaseBump(p, rampMillis);
        if (bump == 0.0) {
            return (float) clampRate(base);
        }
        return (float) clampRate(base * (1.0 + bump));
    }

    /** {@link #tempoRatio} 在 {@code p} 处已经生效到多少（缓入曲线，1 表示还没开始弯）。 */
    private double baseBend(double p) {
        return tempoRatio == 1f
                ? 1.0
                : 1.0 + (tempoRatio - 1.0) * smoothstep(p / RATE_RAMP_COMPLETE);
    }

    /**
     * 淡出 deck 在 {@code progress} 处的<b>速度</b>倍率（由 WSOLA 实现，音高不动）。
     *
     * <p>节奏锁定分支：整条曲线都是速度 —— 缓入到 {@link #tempoRatio}，再叠上节奏缓冲的升余弦。</p>
     *
     * <p>移调分支：{@link #tempoRatio} 归 {@link #outgoingPitch} 用，这里只剩节奏缓冲。
     * 节奏缓冲本身要解决的是相位误差（把 downbeat 挪几十毫秒），那是纯粹的时间问题，
     * 和调性无关，所以两种分支下它都应该走速度这一路。</p>
     */
    float outgoingTempo(double progress, long rampMillis) {
        if (rampMillis < MIN_RATE_CORRECTION_OVERLAP_MILLIS) return 1f;
        double p = clamp(progress);
        double bump = 1.0 + phaseBump(p, rampMillis);
        return (float) (pitchShift ? bump : clampRate(clampRate(baseBend(p)) * bump));
    }

    /**
     * 淡出 deck 在 {@code progress} 处的<b>音高</b>倍率（由重采样实现）。
     *
     * <p>只有移调分支非 1。节奏锁定分支恒为 1 —— 那才是「变速不变调」的全部意思。</p>
     */
    float outgoingPitch(double progress, long rampMillis) {
        if (rampMillis < MIN_RATE_CORRECTION_OVERLAP_MILLIS) return 1f;
        if (!pitchShift) return 1f;
        return (float) clampRate(baseBend(clamp(progress)));
    }

    /**
     * 节奏缓冲在 {@code p} 处的额外速率，以分数表示（缓冲窗口外为 0）。
     *
     * <p>幅度是推导出来的，不是调出来的：升余弦 {@code .5(1-cos 2πu)} 均值为 {@code .5}，
     * 因此幅度为 {@code A}、窗口 {@code W} ms 的凸起使 deck 移动 {@code A·W/2} ms。要
     * 吸收 {@code e} ms 的误差，幅度就必须是 {@code 2e/W}，并被
     * {@link #MAX_PHASE_RATE_BUMP} 截断——超出预算的误差只做部分修正，
     * 而不是带着可闻的一冲一顿去修完。</p>
     */
    private double phaseBump(double p, long rampMillis) {
        if (phaseErrorMillis == 0L || rampMillis <= 0L) return 0.0;
        if (p <= PHASE_BUFFER_START || p >= PHASE_BUFFER_COMPLETE) return 0.0;
        double windowMillis = (PHASE_BUFFER_COMPLETE - PHASE_BUFFER_START) * rampMillis;
        if (windowMillis < 1.0) return 0.0;
        double amplitude = 2.0 * phaseErrorMillis / windowMillis;
        if (amplitude > MAX_PHASE_RATE_BUMP) amplitude = MAX_PHASE_RATE_BUMP;
        if (amplitude < -MAX_PHASE_RATE_BUMP) amplitude = -MAX_PHASE_RATE_BUMP;
        double u = (p - PHASE_BUFFER_START) / (PHASE_BUFFER_COMPLETE - PHASE_BUFFER_START);
        return amplitude * .5 * (1.0 - Math.cos(2.0 * Math.PI * u));
    }

    /** 节奏缓冲实际能吸收 {@link #phaseErrorMillis} 的多少，单位 ms。 */
    long correctablePhaseMillis() {
        return correctablePhaseMillis(overlapMillis);
    }

    /** 同 {@link #correctablePhaseMillis()}，但针对在交接时被缩短的渐变。 */
    long correctablePhaseMillis(long rampMillis) {
        if (rampMillis <= 0L || rampMillis < MIN_RATE_CORRECTION_OVERLAP_MILLIS) return 0L;
        double windowMillis = (PHASE_BUFFER_COMPLETE - PHASE_BUFFER_START) * rampMillis;
        long capacity = (long) (MAX_PHASE_RATE_BUMP * windowMillis * .5);
        long needed = Math.abs(phaseErrorMillis);
        return Math.min(capacity, needed);
    }

    private double clampRate(double rate) {
        if (Double.isNaN(rate)) return 1.0;
        double minimum = style == null ? AutomixSettings.MIN_PLAYBACK_RATE
                : AutomixSettings.FOLIA_MIN_TEMPO_RATE;
        double maximum = style == null ? AutomixSettings.MAX_PLAYBACK_RATE
                : AutomixSettings.FOLIA_MAX_TEMPO_RATE;
        if (rate < minimum) return minimum;
        if (rate > maximum) return maximum;
        return rate;
    }

    /** [0,1] 上的 Hermite 缓入缓出；超出范围的值被钳制。 */
    private static double smoothstep(double value) {
        double t = value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double progress) {
        if (Double.isNaN(progress)) return 0.0;
        return progress < 0.0 ? 0.0 : (progress > 1.0 ? 1.0 : progress);
    }
}
