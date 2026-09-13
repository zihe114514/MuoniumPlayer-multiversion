package com.muoniumplayer.core.ncm.music;

/**
 * A planned automix handover. Positions are absolute milliseconds inside their own track.
 *
 * <p>{@link #fireMillis} is where the outgoing deck starts fading, {@link #incomingCueMillis} is where
 * the incoming deck starts playing, and both ramps last {@link #overlapMillis}. The ramp is equal power
 * ({@code cos}/{@code sin}), so perceived loudness stays flat through the blend instead of dipping in
 * the middle the way a linear fade does.</p>
 *
 * <p>On top of the volume ramp the plan carries the four things that separate a DJ blend from a
 * crossfade:</p>
 * <ul>
 *   <li>a <b>bass swap</b> ({@link #outgoingBassDb}/{@link #incomingBassDb}), which keeps the low band
 *       belonging to exactly one track at a time;</li>
 *   <li>a <b>vocal duck</b> ({@link #incomingVocalDepth}), which lets the arriving track enter as an
 *       instrumental bed so two lead vocals are never audible together;</li>
 *   <li>a <b>tempo lock</b> and a <b>rhythm buffer</b> ({@link #outgoingRate}), which bends the outgoing
 *       deck onto the incoming tempo and then walks off the residual phase error instead of letting the
 *       two grids flam for the whole overlap;</li>
 *   <li>or, where there is no beat worth locking, a <b>whole-semitone transposition</b> - also carried by
 *       {@link #tempoRatio}, because one rate port cannot serve tempo and pitch at the same time.</li>
 * </ul>
 *
 * <p>Every curve here is a pure function of the ramp position, which is what makes the whole schedule
 * checkable offline: the driver in the player only samples them.</p>
 */
final class AutomixPlan {

    /** The outgoing low band is fully out of the way by this point in the ramp. */
    private static final double BASS_CUT_COMPLETE = .5;
    /** The incoming low band stays held back until here, then comes up. */
    private static final double BASS_ENTRY_START = .3;
    private static final double BASS_ENTRY_COMPLETE = .8;
    /**
     * The tempo bend is applied over the first sliver of the ramp. Instantly would be a step in pitch;
     * slowly would let the grids drift for exactly as long as the correction takes to arrive.
     */
    private static final double RATE_RAMP_COMPLETE = .15;
    /**
     * The rhythm buffer: the stretch of the ramp over which the residual phase error is walked off.
     *
     * <p>It starts only after the tempo bend has settled and ends before the ramp does, so the outgoing
     * deck is back at its locked tempo - not still being pushed - by the time it goes quiet. A raised
     * cosine is used rather than a rectangular nudge because its derivative is zero at both ends: the
     * correction has no edges, and the 10 ms driver cannot sample a step out of it.</p>
     */
    private static final double PHASE_BUFFER_START = RATE_RAMP_COMPLETE;
    private static final double PHASE_BUFFER_COMPLETE = .85;
    /**
     * Ceiling on the extra rate used for phase correction, on top of the tempo lock. 2 % is roughly a
     * third of a semitone and is inaudible on a deck that is already several dB down; more than that
     * starts to be heard as a wobble rather than as a correction.
     */
    private static final double MAX_PHASE_RATE_BUMP = .02;
    /**
     * Shortest ramp that may still bend the outgoing deck's playback rate.
     *
     * <p>Rate correction is heard as a pitch change, because the reader resamples rather than
     * time-stretches. That trade is worth it across a planned multi-bar blend, where the two grids
     * really do lock. It is never worth it on a ramp this short: less than a beat of the outgoing
     * track is left, so there is no grid left to lock, and all that reaches the listener is the
     * semitone of pitch. A user-pressed skip and a late fire both land here.</p>
     */
    private static final long MIN_RATE_CORRECTION_OVERLAP_MILLIS = 1_500L;

    /** Vocal cancellation is held at full depth until here, then let go. */
    private static final double VOCAL_DUCK_RELEASE_START = .45;
    /** …and is completely gone by here, so nothing from the blend survives into the track. */
    private static final double VOCAL_DUCK_RELEASE_COMPLETE = .92;

    final long fireMillis;
    final long overlapMillis;
    final long incomingCueMillis;
    /** Loudness match for the incoming deck, applied only during the blend. */
    final float matchGain;
    final boolean barAligned;
    /** Whether the low band is swapped between the decks during the blend. */
    final boolean bassSwap;
    /** Folia strategy fields; null on the legacy planner. */
    final FoliaTransitionStyle style;
    final float midTiltDb;
    final float highTiltDb;
    final boolean echoThrow;
    /** Stem-derived handover positions as fractions of the blend; defaults are the legacy curves. */
    final double drumHandoverAt;
    final double bassHandoverAt;
    final double vocalEntryAt;
    final boolean stemDriven;
    /** Wall-clock milliseconds per outgoing beat, for the transition-visual pulse. */
    final double animationPeriodMillis;
    /**
     * Playback rate the outgoing deck is bent to, 1 meaning "leave it alone". Either a tempo lock (both
     * beat grids measurable, close, and confident) or a whole-semitone key snap (no beat worth locking),
     * never both - and clamped either way so the pitch move stays inside one semitone.
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
     * Residual offset between the two beat grids at the moment of the handover, in ms. Positive means
     * the outgoing deck's downbeat arrives <em>late</em> and has to be nudged forward.
     *
     * <p>This is what is left after the incoming cue has been snapped to a downbeat: two grids measured
     * on an ~11.6 ms envelope hop cannot be quantised into exact agreement, and a few tens of ms is
     * audible as flamming when both tracks are at full level. {@link #outgoingRate} spends the rhythm
     * buffer on it.</p>
     */
    final long phaseErrorMillis;
    /**
     * Peak vocal-band cancellation depth for the incoming deck, 0 disabling it. Held for the first part
     * of the blend and released before the end.
     */
    final float vocalDuckDepth;
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
        this.drumHandoverAt = clampFraction(drumHandoverAt, .18, .72);
        this.bassHandoverAt = clampFraction(bassHandoverAt, .18, .72);
        this.vocalEntryAt = clampFraction(vocalEntryAt, .28, .88);
        this.stemDriven = stemDriven;
        this.animationPeriodMillis = Double.isFinite(animationPeriodMillis)
                ? Math.max(120.0, Math.min(4_000.0, animationPeriodMillis)) : 500.0;
    }

    /** Outgoing gain at {@code progress} in [0,1] of the ramp. */
    float outgoingGain(double progress) {
        if (style != null && style.together > 0.0) return (float) togetherGain(clamp(progress), true);
        return (float) Math.cos(clamp(progress) * Math.PI * .5);
    }

    /**
     * Incoming gain at {@code progress} in [0,1] of the ramp.
     *
     * <p>The loudness match is strongest at the start of the blend, where the incoming track has to
     * hold its own against the outgoing one, and is interpolated back to unity by the end. That way the
     * compensation never survives into the rest of the track as a stuck volume offset, and there is no
     * audible step when the blend finishes.</p>
     */
    float incomingGain(double progress) {
        double p = clamp(progress);
        double remaining = 1.0 - p;
        double match = 1.0 + (matchGain - 1.0) * remaining * remaining;
        double shape = style != null && style.together > 0.0
                ? togetherGain(p, false) : Math.sin(p * Math.PI * .5);
        return (float) (shape * match);
    }

    /** Equal-power curves with a plateau where both decks stay present. */
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
     * Low shelf gain in dB for the outgoing deck.
     *
     * <p>Pulled down early and held there: the outgoing track hands its low end over well before it
     * hands over its volume, which is what stops two kick drums from stacking. It reads as the outgoing
     * track "thinning out", the same gesture a DJ makes with the low knob.</p>
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
     * Low shelf gain in dB for the incoming deck.
     *
     * <p>Held back while the outgoing track still owns the low end, then brought up to flat before the
     * blend finishes so the new track is never left sounding thin once it is alone.</p>
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
     * Vocal-band cancellation depth for the incoming deck, 0..1.
     *
     * <p>Full depth while the outgoing track is still singing, then released along a smoothstep so the
     * arriving track grows into a full mix instead of switching its vocal on. Zero by
     * {@value #VOCAL_DUCK_RELEASE_COMPLETE} of the ramp: the deck the listener keeps has to be flat
     * before the blend ends, not when the driver happens to notice.</p>
     */
    /** Mid-band compensation for the incoming deck, eased back to flat before the blend ends. */
    float incomingMidDb(double progress) {
        return toneEntry(midTiltDb, progress, stemDriven ? drumHandoverAt : .85);
    }

    /** High-band compensation for the incoming deck, eased back to flat before the blend ends. */
    float incomingHighDb(double progress) {
        return toneEntry(highTiltDb, progress, .85);
    }

    /**
     * Matching the previous track's spectral balance at entry removes the most obvious "new master"
     * cue, then the compensation walks back to zero so the listener keeps the incoming master intact.
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
        double releaseStart = stemDriven ? vocalEntryAt : VOCAL_DUCK_RELEASE_START;
        double releaseComplete = stemDriven ? Math.min(.96, Math.max(.72, releaseStart + .18))
                : VOCAL_DUCK_RELEASE_COMPLETE;
        if (p <= releaseStart) return vocalDuckDepth;
        if (p >= releaseComplete) return 0f;
        double share = (p - releaseStart) / (releaseComplete - releaseStart);
        return (float) (vocalDuckDepth * (1.0 - smoothstep(share)));
    }

    /**
     * Playback rate for the outgoing deck at this point in the ramp; 1 when there is nothing to correct.
     *
     * <p>Two things are summed here. The <b>tempo lock</b> eases from 1 to {@link #tempoRatio} over the
     * first {@value #RATE_RAMP_COMPLETE} of the ramp - along a smoothstep rather than a straight line,
     * because a linear ramp starts and stops with a corner and a corner in rate is a click in pitch. The
     * <b>rhythm buffer</b> then adds a raised-cosine bump whose area is exactly the residual phase error,
     * so the outgoing grid slides into agreement with the incoming one and ends up back at its locked
     * tempo.</p>
     *
     * <p>The result is clamped to the shared rate bounds: the correction may never push the deck outside
     * the one-semitone budget, however large the measured error was.</p>
     */
    float outgoingRate(double progress) {
        return outgoingRate(progress, overlapMillis);
    }

    /**
     * Playback rate for the outgoing deck, evaluated against the ramp actually being run.
     *
     * <p>{@code rampMillis} is not always {@link #overlapMillis}: a user-pressed skip and a late fire
     * both shorten it. It matters twice. The rhythm buffer sizes its amplitude from the window it has,
     * so using the planned length there would move the deck by only a fraction of the error it was
     * solving for. And below {@value #MIN_RATE_CORRECTION_OVERLAP_MILLIS} the correction is dropped
     * outright - see that constant.</p>
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
        return (float) (pitchShift ? bump : clampRate(baseBend(p)) * bump);
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
     * The rhythm buffer's extra rate at {@code p}, as a fraction (0 outside the buffer window).
     *
     * <p>Amplitude is derived, not tuned: a raised cosine {@code .5(1-cos 2πu)} has mean {@code .5}, so a
     * bump of amplitude {@code A} over a window of {@code W} ms moves the deck by {@code A·W/2} ms. To
     * absorb an error of {@code e} ms the amplitude therefore has to be {@code 2e/W}, and that is capped
     * at {@link #MAX_PHASE_RATE_BUMP} - an error too large for the budget is partially corrected rather
     * than corrected with an audible lurch.</p>
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

    /** How much of {@link #phaseErrorMillis} the rhythm buffer can actually absorb, in ms. */
    long correctablePhaseMillis() {
        return correctablePhaseMillis(overlapMillis);
    }

    /** As {@link #correctablePhaseMillis()}, for a ramp that was shortened at handover time. */
    long correctablePhaseMillis(long rampMillis) {
        if (rampMillis <= 0L || rampMillis < MIN_RATE_CORRECTION_OVERLAP_MILLIS) return 0L;
        double windowMillis = (PHASE_BUFFER_COMPLETE - PHASE_BUFFER_START) * rampMillis;
        long capacity = (long) (MAX_PHASE_RATE_BUMP * windowMillis * .5);
        long needed = Math.abs(phaseErrorMillis);
        return Math.min(capacity, needed);
    }

    private static double clampRate(double rate) {
        if (Double.isNaN(rate)) return 1.0;
        if (rate < AutomixSettings.MIN_PLAYBACK_RATE) return AutomixSettings.MIN_PLAYBACK_RATE;
        if (rate > AutomixSettings.MAX_PLAYBACK_RATE) return AutomixSettings.MAX_PLAYBACK_RATE;
        return rate;
    }

    /** Hermite ease in/out on [0,1]; values outside are clamped. */
    private static double smoothstep(double value) {
        double t = value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double progress) {
        if (Double.isNaN(progress)) return 0.0;
        return progress < 0.0 ? 0.0 : (progress > 1.0 ? 1.0 : progress);
    }
}
