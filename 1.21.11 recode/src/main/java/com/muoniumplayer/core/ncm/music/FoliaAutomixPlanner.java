package com.muoniumplayer.core.ncm.music;

import java.util.Locale;

/**
 * 证据优先的 Folia 风格过渡规划器。
 *
 * <p>它刻意把选择过渡族与调度增益分开。旧规划器
 * 仍可用，由 {@link AutomixSettings.Engine} 选择；本类绝不改变用户的
 * DSP 偏好，只为测得的配对选择一个更安全的音乐计划。</p>
 *
 * <p>实现是仓库原生 Java，使用既有的离线分析原语。
 * 模型驱动的 Beat This 与音轨分离阶段在后续叠加；当模型不可用时，同一策略
 * 在确定性分析回退上运行。</p>
 */
final class FoliaAutomixPlanner {

    private static final long ANALYSIS_WINDOW_MILLIS = 30_000L;
    private static final long INTRO_SKIP_LIMIT_MILLIS = 12_000L;
    private static final long LYRIC_TAIL_MILLIS = 1_500L;
    private static final long MAX_TRIM_MILLIS = 12_000L;
    private static final double MIN_TRIM_POSITION = .60;
    private static final long MIN_OVERLAP_MILLIS = 800L;
    private static final long MAX_OVERLAP_MILLIS = 25_000L;
    private static final long BEAT_CUT_MILLIS = 40L;
    /**
     * 进阶模式的退场点必须落在播放头<b>之后</b>这么多毫秒。见
     * {@link #advancedCue} 里对 {@code minimumFireMillis} 的说明：计划会被缓存复用，
     * 复用时的播放头比算它的时候更靠后。
     */
    private static final long MIN_ADVANCED_FIRE_LEAD_MILLIS = 5_000L;
    /** 包内可见：{@link AutomixAutoCue} 用同一道置信度闸决定要不要按小节铺候选。 */
    static final float MIN_GRID_CONFIDENCE = .28f;
    private static final float MIN_KEY_CONFIDENCE = .25f;
    private static final float MIN_GAIN = .65f;
    private static final float MAX_GAIN = 1.60f;
    private static final long MAX_PHASE_ERROR_MILLIS = 140L;
    private static final long DEFAULT_OVERLAP_MILLIS = 5_000L;
    private static final int BEATS_PER_BAR = 4;
    private static final int BEATS_PER_PHRASE = BEATS_PER_BAR * 4;
    private static final double MIN_LENGTH_SCALE = .25;

    private FoliaAutomixPlanner() {
    }

    static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming, long lastVocalEndMillis) {
        if (outgoing == null || incoming == null || !outgoing.isUsable() || !incoming.isUsable()) return null;
        long outgoingMillis = (long) outgoing.getTotalTimeMillis();
        long incomingMillis = (long) incoming.getTotalTimeMillis();
        if (outgoingMillis < 12_000L || incomingMillis < 8_000L) return null;

        AnalysisAudio outgoingAudio = AnalysisAudioSource.open(outgoing.getSourceFile());
        AnalysisAudio incomingAudio = AnalysisAudioSource.open(incoming.getSourceFile());
        try {
            return plan(outgoing, incoming, outgoingMillis, incomingMillis, lastVocalEndMillis, outgoingAudio, incomingAudio);
        } finally {
            if (outgoingAudio != null) outgoingAudio.close();
            if (incomingAudio != null) incomingAudio.close();
        }
    }

    private static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming,
                                    long outgoingMillis, long incomingMillis, long lastVocalEndMillis,
                                    AnalysisAudio outgoingAudio, AnalysisAudio incomingAudio) {
        boolean analysed = outgoingAudio != null && incomingAudio != null
                && outgoingAudio.sampleRate() > 0 && incomingAudio.sampleRate() > 0;
        FoliaTrackProfile outgoingProfile = FoliaTrackProfiler.loadOrAnalyse(outgoing, true);
        FoliaTrackProfile incomingProfile = FoliaTrackProfiler.loadOrAnalyse(incoming, false);

        long outgoingWindowStart = Math.max(0L, outgoingMillis - ANALYSIS_WINDOW_MILLIS);
        long outgoingWindowLength = outgoingMillis - outgoingWindowStart;
        long incomingWindowLength = Math.min(ANALYSIS_WINDOW_MILLIS, incomingMillis);
        float[] outgoingTail = analysed
                ? outgoingAudio.readMono(outgoingWindowStart, outgoingWindowLength) : new float[0];
        float[] incomingHead = analysed
                ? incomingAudio.readMono(0L, incomingWindowLength) : new float[0];

        AutomixAnalyzer.BeatGrid outgoingGrid = outgoingProfile != null && outgoingProfile.outroBeatConfidence > 0f
                ? new AutomixAnalyzer.BeatGrid(outgoingProfile.outroBpm, outgoingProfile.beatPhaseMillis,
                        outgoingProfile.barPhaseMillis, outgoingProfile.outroBeatConfidence)
                : (analysed ? AutomixAnalyzer.analyzeBeats(outgoingTail, outgoingAudio.sampleRate()) : null);
        AutomixAnalyzer.BeatGrid incomingGrid = incomingProfile != null && incomingProfile.beatConfidence > 0f
                ? new AutomixAnalyzer.BeatGrid(incomingProfile.bpm, incomingProfile.headBeatPhaseMillis,
                        incomingProfile.headBarPhaseMillis, incomingProfile.beatConfidence)
                : (analysed ? AutomixAnalyzer.analyzeBeats(incomingHead, incomingAudio.sampleRate()) : null);
        AutomixAnalyzer.KeyEstimate outgoingKey = outgoingProfile != null && outgoingProfile.outroKeyConfidence > 0f
                ? new AutomixAnalyzer.KeyEstimate(outgoingProfile.outroKeyTonic, outgoingProfile.outroKeyMajor, outgoingProfile.outroKeyConfidence)
                : (analysed ? AutomixAnalyzer.analyzeKey(outgoingTail, outgoingAudio.sampleRate()) : null);
        AutomixAnalyzer.KeyEstimate incomingKey = incomingProfile != null && incomingProfile.introKeyConfidence > 0f
                ? new AutomixAnalyzer.KeyEstimate(incomingProfile.introKeyTonic, incomingProfile.introKeyMajor, incomingProfile.introKeyConfidence)
                : (analysed ? AutomixAnalyzer.analyzeKey(incomingHead, incomingAudio.sampleRate()) : null);

        long introEndMillis = incomingProfile != null ? Math.round(incomingProfile.leadInMillis)
                : (incomingHead.length > 0 ? Math.round(AutomixAnalyzer.detectIntroEnd(incomingHead, incomingAudio.sampleRate())) : 0L);
        introEndMillis = Math.max(0L, Math.min(incomingMillis, introEndMillis));
        // 先判哨兵，再换算成绝对时间：detectOutroLanding 返回 -1 表示「测不到」，
        // 而 -1 加进窗口起点会变成一个正数，让 :125 的 `> 0` 判断把「没有证据」
        // 当成一条证据。这与 LEGACY 侧的写法一致（AutomixPlanner 里是 `if (landing >= 0f)`）。
        long landing = outgoingTail.length > 0
                ? Math.round(AutomixAnalyzer.detectOutroLanding(outgoingTail, outgoingAudio.sampleRate()))
                : -1L;
        long outroLandingMillis = outgoingProfile != null && outgoingProfile.outroLandingMillis > 0f
                ? Math.round(outgoingProfile.outroLandingMillis)
                : (landing < 0L ? -1L : outgoingWindowStart + landing);

        boolean outgoingUsable = usableGrid(outgoingGrid);
        boolean incomingUsable = usableGrid(incomingGrid);
        TempoMatch tempo = tempoMatch(outgoingUsable ? outgoingGrid.bpm : null,
                incomingUsable ? incomingGrid.bpm : null);
        KeyRelation keyRelation = keyRelation(outgoingKey, incomingKey);
        // 与 endsHot 对称：两者都优先取证据层已经判定的结论，只有在没有 profile 时
        // 才回退到就地测量。曾经这里是 `introEndMillis <= 350L` 的重算 —— 那让同一个
        // 概念在「有 profile」和「没 profile」两条路上各有一套算术，而 profile 里的
        // leadInMillis 正是同一个量。
        boolean startsHot = incomingProfile != null ? incomingProfile.startsHot
                : introEndMillis <= 350L;
        boolean endsHot = outgoingProfile != null ? outgoingProfile.endsHot
                : endsHot(outgoingTail, outgoingAudio == null ? 0 : outgoingAudio.sampleRate());
        float outroSlope = outgoingProfile != null ? outgoingProfile.outroSlopeDbPerSecond
                : outroSlope(outgoingTail, outgoingAudio == null ? 0 : outgoingAudio.sampleRate());

        FoliaTransitionStyle style = chooseStyle(tempo, startsHot, endsHot, outroSlope,
                outgoingUsable, incomingUsable, introEndMillis,
                outgoingGrid == null ? null : outgoingGrid);

        boolean performance = AutomixSettings.isPerformanceModeEnabled();
        if (performance && style == FoliaTransitionStyle.PLAIN_BLEND) style = FoliaTransitionStyle.BASS_SWAP;
        long outgoingEnd = Math.max(1_000L, outgoingMillis);
        float energyStepDb = energyStepDb(outgoingProfile, incomingProfile);
        long overlap = chooseOverlap(style, tempo, keyRelation, outgoingGrid,
                outgoingMillis / 4L, incomingMillis, startsHot, energyStepDb);
        if (style == FoliaTransitionStyle.BEAT_CUT) overlap = BEAT_CUT_MILLIS;
        else if (performance) overlap = Math.min(MAX_OVERLAP_MILLIS, (long) (overlap * 1.18));

        long latestFire = Math.max(0L, outgoingEnd - overlap);
        long earliestFire = Math.max((long) (outgoingMillis * MIN_TRIM_POSITION),
                outgoingMillis - MAX_TRIM_MILLIS);
        if (earliestFire > latestFire) earliestFire = latestFire;

        long anchor = latestFire;
        if (outroLandingMillis > 0L) {
            if (outroLandingMillis >= earliestFire && outroLandingMillis <= latestFire) {
                anchor = outroLandingMillis;
            }
        }
        long stemVocalGuard = stemVocalGuardMillis(outgoingProfile);
        if (stemVocalGuard > 0L) {
            long afterStem = stemVocalGuard + 450L;
            if (afterStem >= earliestFire && afterStem <= latestFire) anchor = Math.max(anchor, afterStem);
        }
        if (lastVocalEndMillis > 0L) {
            long afterVocal = lastVocalEndMillis + LYRIC_TAIL_MILLIS;
            if (afterVocal >= earliestFire && afterVocal <= latestFire) anchor = Math.max(anchor, afterVocal);
        }
        if (outgoingProfile != null && outgoingProfile.sectionMillis != null) {
            long boundary = -1L;
            long best = 2_001L;
            for (float section : outgoingProfile.sectionMillis) {
                long candidate = (long) section;
                long distance = Math.abs(candidate - anchor);
                if (candidate >= earliestFire && candidate <= latestFire && distance < best) {
                    best = distance; boundary = candidate;
                }
            }
            if (boundary >= 0L) anchor = boundary;
        }
        long fire = AutomixSettings.isBeatAlignEnabled()
                ? snapToBeat(anchor, outgoingGrid, outgoingWindowStart) : anchor;
        if (fire < earliestFire || fire > latestFire) fire = anchor;

        long cue = 0L;
        if (AutomixSettings.isSkipIntroEnabled() && introEndMillis > 50L) {
            cue = Math.min(INTRO_SKIP_LIMIT_MILLIS, introEndMillis);
            if (AutomixSettings.isBeatAlignEnabled() && incomingGrid != null && incomingUsable) {
                long beat = Math.max(180L, (long) (60_000f / incomingGrid.bpm));
                long phase = positiveModulo((long) incomingGrid.barPhaseMillis, beat);
                long snapped = cue + positiveModulo(phase - cue, beat);
                if (snapped + overlap < incomingMillis) cue = snapped;
            }
        }

        // 进阶模式：把上面那对「启发式位置」当成候选之一，交给整曲包络上的配对搜索去比。
        // 必须放在相位误差、响度匹配、分轨让路点<b>之前</b>——那些全都按最终这一对点求值，
        // 先改点后算曲线，才不会「按 A 点打分、按 B 点播放」。
        String advancedNote = "";
        AutomixEnvelope outgoingEnvelope = null;
        if (AutomixSettings.isAdvancedModeEnabled()) {
            outgoingEnvelope = AutomixEnvelope.load(outgoing.getSourceFile());
            AutomixEnvelope incomingEnvelope = AutomixEnvelope.load(incoming.getSourceFile());
            AutomixAutoCue.Result adopted = advancedCue(outgoingEnvelope, incomingEnvelope,
                    outgoingProfile, outgoingGrid, incomingGrid, introEndMillis,
                    lastVocalEndMillis, fire, cue, overlap, (long) outgoing.getCurrentTimeMillis());
            if (adopted != null) {
                fire = adopted.fireMillis;
                cue = adopted.cueMillis;
                overlap = adopted.overlapMillis;
                advancedNote = ", " + adopted.note;
            } else {
                advancedNote = ", autocue 未采纳";
            }
        }

        long phaseError = AutomixSettings.isBeatAlignEnabled() && outgoingUsable && incomingUsable
                ? residualPhaseMillis(outgoingGrid, outgoingWindowStart, fire, incomingGrid, 0L, cue) : 0L;
        if (Math.abs(phaseError) > MAX_PHASE_ERROR_MILLIS) phaseError = 0L;

        float gain = loudnessMatch(analysed, outgoingAudio, incomingAudio, fire, cue,
                outgoingMillis, incomingMillis, overlap);
        float tempoRatio = 1f;
        if (AutomixSettings.isTempoLockEnabled() && outgoingUsable && incomingUsable
                && (tempo.relation == TempoRelation.LOCKED || tempo.relation == TempoRelation.NEAR
                || tempo.relation == TempoRelation.STRETCHABLE)) {
            tempoRatio = clampRate((float) tempo.ratio);
            if (Math.abs(tempoRatio - 1f) < .004f) tempoRatio = 1f;
        }


        FoliaStemProfile outgoingStems = outgoingProfile == null ? null : outgoingProfile.stems;
        FoliaStemProfile incomingStems = incomingProfile == null ? null : incomingProfile.stems;
        boolean stemDriven = usableStems(outgoingStems) || usableStems(incomingStems);
        double drumAt = fractionOfBlend(outgoingStems == null ? -1L : outgoingStems.drumEndMillis,
                fire, overlap, .42);
        double bassAt = fractionOfBlend(outgoingStems == null ? -1L : outgoingStems.bassEndMillis,
                fire, overlap, .42);
        double vocalEntry = fractionOfBlend(stemVocalGuard, fire, overlap, .45);
        if (usableStems(incomingStems) && incomingStems.vocalStartMillis >= 0L) {
            double incomingEntry = fractionOfBlend(incomingStems.vocalStartMillis - cue, 0L,
                    overlap, vocalEntry);
            vocalEntry = Math.max(vocalEntry, incomingEntry);
        }

        boolean bassSwap = AutomixSettings.isBassSwapEnabled() && style.usesBassSwap();
        float vocalDuck = AutomixSettings.isVocalDuckEnabled()
                ? (keyRelation == KeyRelation.CLASHING ? .74f : AutomixSettings.VOCAL_DUCK_DEPTH) : 0f;

        // 进阶模式的人声证据。整曲包络能回答两件事：「这一句越过交叠窗了没有」和
        // 「出场侧在窗内的哪一处掉的」。于是进场方从哪里长回人声按实测来，出场侧如果还在唱
        // 也要让路——只压进场方时，中段是两个主唱同时唱。
        // 没有包络（没预热到、外部格式还没扫完）时两个都是默认值，曲线与今天逐位一致。
        float outgoingVocalDuck = 0f;
        double vocalReleaseAt = 0.0;
        if (outgoingEnvelope != null && vocalDuck > 0f) {
            double body = outgoingEnvelope.meanLevel(fire, fire + overlap);
            double tail = outgoingEnvelope.meanLevel(fire + overlap, fire + overlap + 2_000L);
            if (body > 1e-4 && tail / body > .55) outgoingVocalDuck = vocalDuck;
            vocalReleaseAt = outgoingEnvelope.releaseFraction(fire, overlap);
            if (outgoingVocalDuck > 0f) {
                advancedNote += ", 出场人声让路";
            } else if (vocalReleaseAt > 0.0) {
                advancedNote += String.format(Locale.ROOT, ", 人声释放点 %.2f", vocalReleaseAt);
            }
        }
        float midTilt = outgoingProfile == null || incomingProfile == null ? 0f
                : toneTilt(outgoingProfile.toneMid, incomingProfile.toneMid);
        float highTilt = outgoingProfile == null || incomingProfile == null ? 0f
                : toneTilt(outgoingProfile.toneHigh, incomingProfile.toneHigh);
        boolean beatMatched = AutomixSettings.isBeatAlignEnabled() && outgoingUsable && incomingUsable && tempoRatio != 1f;
        String summary = String.format(Locale.ROOT,
                "%s %.2fs overlap, %s keys, %s, fire %dms cue %dms%s%s%s%s",
                style.id, overlap / 1000.0, keyRelation.id, tempo.relation.id, fire, cue,
                bassSwap ? ", bass swap" : "", vocalDuck > 0f ? ", vocal hold" : "",
                stemDriven ? ", stem timing" : "", advancedNote);

        double animationPeriod = outgoingUsable && outgoingGrid != null && outgoingGrid.bpm > 1f
                ? 60_000.0 / outgoingGrid.bpm : 500.0;
        if (tempoRatio > 0f) animationPeriod /= tempoRatio;
        System.out.println("[Automix/Folia] " + summary);
        return new AutomixPlan(fire, overlap, cue, gain, beatMatched, bassSwap, tempoRatio,
                false, phaseError, vocalDuck, summary, style,
                midTilt, highTilt, false,
                drumAt, bassAt, vocalEntry, stemDriven, animationPeriod,
                outgoingVocalDuck, vocalReleaseAt);
    }

    // 「窗口内均值」与「这一段什么时候掉的」两条量都在 AutomixEnvelope 上：选点时的第 ⑧ 项打分
    // 与这里播放侧的释放曲线必须问同一个地方，否则会出现「按一条曲线挑的接缝、按另一条曲线执行」。

    private static boolean usableStems(FoliaStemProfile stems) {
        return AutomixSettings.isPerformanceModeEnabled() && stems != null && stems.isUsable();
    }

    /**
     * 进阶模式的选点入口。<b>只读缓存，绝不扫描。</b>整曲包络必须已经由预热线程
     * （{@link AutomixEnvelope#ensure}）扫好落在盘上；这里如果现场扫，会在预备期起若干个
     * ffmpeg 进程解码整首歌，而那条窗口本来只有 30 秒、且此前是<b>零 IO</b> 的。
     * 读不到就返回 null，启发式照常跑——「没预热」的表现与「没开进阶模式」完全一致。
     */
    private static AutomixAutoCue.Result advancedCue(AutomixEnvelope a, AutomixEnvelope b,
                                                     FoliaTrackProfile outgoingProfile,
                                                     AutomixAnalyzer.BeatGrid outgoingGrid,
                                                     AutomixAnalyzer.BeatGrid incomingGrid,
                                                     long introEndMillis, long lastVocalEndMillis,
                                                     long fire, long cue, long overlap,
                                                     long playheadMillis) {
        if (a == null || b == null) {
            System.out.println("[Automix/Folia] 进阶选点未采纳：整曲包络未就绪");
            return null;
        }
        AutomixAutoCue.Request request = new AutomixAutoCue.Request();
        request.outgoing = a;
        request.incoming = b;
        request.outgoingGrid = outgoingGrid;
        request.incomingGrid = incomingGrid;
        request.introEndMillis = introEndMillis;
        request.lastVocalEndMillis = lastVocalEndMillis;
        request.stemVocalEndMillis = stemVocalGuardMillis(outgoingProfile);
        request.outgoingSectionMillis = sectionMillis(outgoingProfile);
        request.heuristicFireMillis = fire;
        request.heuristicCueMillis = cue;
        request.heuristicOverlapMillis = overlap;
        request.preferredOverlapMillis = overlap;
        request.beatAlignEnabled = AutomixSettings.isBeatAlignEnabled();
        // 播放头之后至少留出这些毫秒才允许当退场点。计划会被 AutomixPlanCache 按「这一对歌」
        // 复用，同一对第二次播放时预备可能比第一次晚几秒——不留余量就会复用一个「已经过去了」
        // 的退场点，交接当场开始，听起来像在中段硬切。
        request.minimumFireMillis = Math.max(0L, playheadMillis + MIN_ADVANCED_FIRE_LEAD_MILLIS);

        AutomixAutoCue.Result result = AutomixAutoCue.plan(request);
        if (result == null) {
            System.out.println("[Automix/Folia] 进阶选点：两首歌放不下任何候选窗口，沿用启发式");
            return null;
        }
        System.out.println("[Automix/Folia] 进阶选点 " + result.detail + " → " + result.note);
        if (!result.isAdopted()) return null;
        // 采纳前再自证一次范围。搜索里已经保证了，但这一条结论直接决定什么时候开始淡出，
        // 而一处算错的候选是「好好的歌提前二十秒退场」这种最难查的投诉。
        if (result.fireMillis < request.minimumFireMillis) return null;   // 不许选已经播过去的位置
        if (result.fireMillis < 0L || result.fireMillis + result.overlapMillis > a.durationMillis - 500L) return null;
        if (result.cueMillis < 0L || result.cueMillis + result.overlapMillis > b.durationMillis - 500L) return null;
        if (result.overlapMillis < MIN_OVERLAP_MILLIS || result.overlapMillis > MAX_OVERLAP_MILLIS) return null;
        return result;
    }

    private static long[] sectionMillis(FoliaTrackProfile profile) {
        if (profile == null || profile.sectionMillis == null || profile.sectionMillis.length == 0) return null;
        long[] out = new long[profile.sectionMillis.length];
        for (int i = 0; i < out.length; i++) out[i] = (long) profile.sectionMillis[i];
        return out;
    }

    private static long stemVocalGuardMillis(FoliaTrackProfile profile) {
        if (profile == null || !usableStems(profile.stems)) return -1L;
        return Math.max(profile.stems.vocalEndMillis, profile.stems.sustainedVocalEndMillis);
    }

    private static double fractionOfBlend(long marker, long start, long overlap, double fallback) {
        if (marker < 0L || overlap <= 0L) return fallback;
        double fraction = (marker - start) / (double) overlap;
        if (Double.isNaN(fraction) || Double.isInfinite(fraction)) return fallback;
        return Math.max(.18, Math.min(.88, fraction));
    }
    private static FoliaTransitionStyle chooseStyle(TempoMatch tempo, boolean startsHot, boolean endsHot,
                                                    float outroSlope, boolean outgoingUsable,
                                                    boolean incomingUsable, long introEndMillis,
                                                    AutomixAnalyzer.BeatGrid outgoingGrid) {
        long beatMillis = outgoingUsable && outgoingGrid != null && outgoingGrid.bpm > 1f
                ? Math.max(1L, (long) (60_000f / outgoingGrid.bpm)) : 500L;
        if (startsHot && outgoingUsable) {
            return introEndMillis >= beatMillis ? FoliaTransitionStyle.BEAT_CUT
                    : FoliaTransitionStyle.BASS_SWAP;
        }
        if (tempo.relation == TempoRelation.FAR && outgoingUsable
                && introEndMillis >= beatMillis) return FoliaTransitionStyle.BEAT_CUT;
        if (outroSlope <= -1.5f) return FoliaTransitionStyle.BASS_SWAP;
        if (!endsHot && (!startsHot || introEndMillis > 300L)) return FoliaTransitionStyle.TAIL_RIDE;
        if (outgoingUsable || incomingUsable) return FoliaTransitionStyle.BASS_SWAP;
        return FoliaTransitionStyle.PLAIN_BLEND;
    }

    private static long chooseOverlap(FoliaTransitionStyle style, TempoMatch tempo, KeyRelation key,
                                      AutomixAnalyzer.BeatGrid outgoingGrid,
                                      long outgoingRoomMillis, long incomingRoomMillis,
                                      boolean startsHot, float energyStepDb) {
        boolean usable = outgoingGrid != null && outgoingGrid.bpm > 1f
                && outgoingGrid.confidence >= MIN_GRID_CONFIDENCE;
        double beatMillis = usable ? 60_000.0 / outgoingGrid.bpm : 500.0;
        double wanted = usable ? beatMillis * BEATS_PER_PHRASE * 2.0 : DEFAULT_OVERLAP_MILLIS;
        wanted *= style == FoliaTransitionStyle.TAIL_RIDE ? 1.4 : 1.0;
        double energyScale = 1.0 + Math.min(12f, Math.max(0f, energyStepDb)) / 12.0 * .4;
        double lengthScale = Math.max(MIN_LENGTH_SCALE,
                key.lengthScale() * tempo.lengthScale() * energyScale
                        * (startsHot && style != FoliaTransitionStyle.BEAT_CUT ? .6 : 1.0));
        wanted *= lengthScale;
        double ceiling = Math.min(MAX_OVERLAP_MILLIS,
                Math.min(outgoingRoomMillis, incomingRoomMillis));
        wanted = Math.min(wanted, ceiling);
        if (usable) wanted = quantiseToMusic(wanted, beatMillis, ceiling);
        return Math.max(MIN_OVERLAP_MILLIS, Math.min(MAX_OVERLAP_MILLIS, (long) wanted));
    }

    /** Folia 按整句、小节或拍取整，取能容纳的最大单位。 */
    private static double quantiseToMusic(double seconds, double beatMillis, double maxMillis) {
        if (!(beatMillis > 0.0)) return Math.min(seconds, maxMillis);
        double wantedBeats = seconds / beatMillis;
        int unit = unitBeats(wantedBeats);
        int beats = (int) Math.max(1L, Math.round(wantedBeats / unit)) * unit;
        while (beats * beatMillis > maxMillis && beats > 1) {
            int next = unitBeats(beats - unit);
            if (next < unit) unit = next;
            beats = Math.max(1, beats - unit);
        }
        return beats * beatMillis;
    }

    private static int unitBeats(double beats) {
        return beats >= BEATS_PER_PHRASE ? BEATS_PER_PHRASE : beats >= BEATS_PER_BAR ? BEATS_PER_BAR : 1;
    }

    private static float energyStepDb(FoliaTrackProfile outgoing, FoliaTrackProfile incoming) {
        if (outgoing == null || incoming == null) return 0f;
        float a = outgoing.tailLufs;
        float b = incoming.headLufs;
        if (Float.isNaN(a) || Float.isNaN(b) || Float.isInfinite(a) || Float.isInfinite(b)) return 0f;
        return Math.min(12f, Math.abs(a - b));
    }

    private static float loudnessMatch(boolean analysed, AnalysisAudio outgoingAudio,
                                       AnalysisAudio incomingAudio, long fire, long cue,
                                       long outgoingMillis, long incomingMillis, long overlap) {
        if (!analysed) return 1f;
        float[] outgoing = outgoingAudio.readMono(fire, Math.min(overlap, outgoingMillis - fire));
        float[] incoming = incomingAudio.readMono(cue, Math.min(overlap, incomingMillis - cue));
        float a = AutomixAnalyzer.kWeightedLoudness(outgoing, outgoingAudio.sampleRate());
        float b = AutomixAnalyzer.kWeightedLoudness(incoming, incomingAudio.sampleRate());
        float gain;
        if (!Float.isNaN(a) && !Float.isNaN(b)) gain = (float) Math.pow(10.0, (a - b) / 20.0);
        else {
            float ar = AutomixAnalyzer.rms(outgoing);
            float br = AutomixAnalyzer.rms(incoming);
            gain = ar > .001f && br > .001f ? ar / br : 1f;
        }
        return Math.max(MIN_GAIN, Math.min(MAX_GAIN, gain));
    }

    private static boolean usableGrid(AutomixAnalyzer.BeatGrid grid) {
        return grid != null && grid.bpm > 1f && grid.confidence >= MIN_GRID_CONFIDENCE;
    }

    private static boolean usableKey(AutomixAnalyzer.KeyEstimate key) {
        return key != null && key.confidence >= MIN_KEY_CONFIDENCE;
    }

    private static KeyRelation keyRelation(AutomixAnalyzer.KeyEstimate from, AutomixAnalyzer.KeyEstimate to) {
        if (!usableKey(from) || !usableKey(to)) return KeyRelation.UNKNOWN;
        int interval = (to.tonic - from.tonic + 12) % 12;
        if (from.major == to.major) {
            if (interval == 0 || interval == 7 || interval == 5) return KeyRelation.COMPATIBLE;
            if (interval == 2 || interval == 10) return KeyRelation.ADJACENT;
        } else if ((from.major && interval == 9) || (!from.major && interval == 3)) {
            return KeyRelation.COMPATIBLE;
        } else if (interval == 0) return KeyRelation.ADJACENT;
        if (interval == 1 || interval == 11 || interval == 6) return KeyRelation.CLASHING;
        return KeyRelation.NEUTRAL;
    }

    private static TempoMatch tempoMatch(Float fromBpm, Float toBpm) {
        if (fromBpm == null || toBpm == null || fromBpm <= 0f || toBpm <= 0f) return TempoMatch.UNKNOWN;
        double ratio = foldRatio(toBpm / fromBpm);
        double deviation = Math.abs(ratio - 1.0);
        if (deviation < .015) return new TempoMatch(TempoRelation.LOCKED, ratio);
        if (deviation <= .0594) return new TempoMatch(TempoRelation.NEAR, ratio);
        if (deviation <= .12) return new TempoMatch(TempoRelation.STRETCHABLE, ratio);
        if (deviation <= .25) return new TempoMatch(TempoRelation.DRIFTING, ratio);
        return new TempoMatch(TempoRelation.FAR, ratio);
    }

    private static float toneTilt(float outgoingShare, float incomingShare) {
        if (!(outgoingShare > 0f) || !(incomingShare > 0f)) return 0f;
        double tilt = 10.0 * Math.log10(outgoingShare / incomingShare);
        return (float) Math.max(-3.0, Math.min(3.0, tilt));
    }

    private static double foldRatio(double ratio) {
        if (!(ratio > 0.0)) return 1.0;
        while (ratio > 2.0) ratio *= .5;
        while (ratio < .5) ratio *= 2.0;
        return Math.abs(ratio - 1.0) <= Math.abs(ratio * .5 - 1.0) ? ratio : ratio * .5;
    }

    private static long snapToBeat(long position, AutomixAnalyzer.BeatGrid grid, long gridOrigin) {
        if (grid == null || grid.bpm <= 1f || grid.confidence < MIN_GRID_CONFIDENCE) return position;
        double beat = 60_000.0 / grid.bpm;
        double phase = positiveModulo((long) grid.barPhaseMillis, (long) beat);
        double delta = positiveModulo(position - gridOrigin - phase + beat * .5, beat) - beat * .5;
        return Math.max(0L, position - (long) Math.round(delta));
    }

    private static long residualPhaseMillis(AutomixAnalyzer.BeatGrid outgoing, long outgoingOrigin,
                                            long outgoingPosition, AutomixAnalyzer.BeatGrid incoming,
                                            long incomingOrigin, long incomingPosition) {
        if (outgoing == null || incoming == null || outgoing.bpm <= 1f || incoming.bpm <= 1f) return 0L;
        double outBeat = 60_000.0 / outgoing.bpm;
        double inBeat = 60_000.0 / incoming.bpm;
        double outPhase = positiveModulo(outgoingPosition - outgoingOrigin, outBeat);
        double inPhase = positiveModulo(incomingPosition - incomingOrigin, inBeat);
        return Math.round(wrapSigned(inPhase - outPhase, inBeat));
    }

    private static boolean endsHot(float[] tail, int rate) {
        if (tail == null || tail.length == 0 || rate <= 0) return true;
        int end = Math.min(tail.length, rate * 2);
        int previous = Math.min(tail.length - end, rate * 6);
        if (previous <= 0) return true;
        float a = AutomixAnalyzer.rms(java.util.Arrays.copyOfRange(tail, tail.length - end, tail.length));
        float b = AutomixAnalyzer.rms(java.util.Arrays.copyOfRange(tail, tail.length - end - previous, tail.length - end));
        return a > .003f && a >= b * .45f;
    }

    private static float outroSlope(float[] tail, int rate) {
        if (tail == null || tail.length < rate * 2 || rate <= 0) return 0f;
        int length = Math.min(tail.length, rate * 10);
        float[] window = java.util.Arrays.copyOfRange(tail, tail.length - length, tail.length);
        int split = length / 2;
        float first = AutomixAnalyzer.rms(java.util.Arrays.copyOfRange(window, 0, split));
        float second = AutomixAnalyzer.rms(java.util.Arrays.copyOfRange(window, split, length));
        if (first <= 1e-5f || second <= 1e-5f) return 0f;
        return (float) (20.0 * Math.log10(second / first) / 5.0);
    }

    private static float clampRate(float rate) {
        if (Float.isNaN(rate)) return 1f;
        return Math.max(AutomixSettings.FOLIA_MIN_TEMPO_RATE,
                Math.min(AutomixSettings.FOLIA_MAX_TEMPO_RATE, rate));
    }

    private static long positiveModulo(long value, long span) {
        if (span <= 0L) return 0L;
        long result = value % span;
        return result < 0L ? result + span : result;
    }

    private static double positiveModulo(double value, double span) {
        if (!(span > 0.0)) return 0.0;
        double result = value % span;
        return result < 0.0 ? result + span : result;
    }

    private static double wrapSigned(double value, double period) {
        if (!(period > 0.0)) return 0.0;
        double wrapped = positiveModulo(value + period * .5, period) - period * .5;
        return wrapped;
    }

    private enum TempoRelation {
        LOCKED("locked", 1.3), NEAR("near", 1.2), STRETCHABLE("stretchable", 1.1),
        DRIFTING("drifting", .6), FAR("far", .5), UNKNOWN("unknown", 1.0);

        final String id;
        final double scale;

        TempoRelation(String id, double scale) { this.id = id; this.scale = scale; }
    }

    private static final class TempoMatch {
        static final TempoMatch UNKNOWN = new TempoMatch(TempoRelation.UNKNOWN, 1.0);
        final TempoRelation relation;
        final double ratio;
        TempoMatch(TempoRelation relation, double ratio) { this.relation = relation; this.ratio = ratio; }
        double lengthScale() {
            return relation == TempoRelation.FAR && ratio > 1.0 ? .5 : relation.scale;
        }
    }

    private enum KeyRelation {
        COMPATIBLE("compatible", 1.0), ADJACENT("adjacent", .85),
        NEUTRAL("neutral", .70), CLASHING("clashing", .40), UNKNOWN("unknown", 1.0);

        final String id;
        final double scale;
        KeyRelation(String id, double scale) { this.id = id; this.scale = scale; }
        double lengthScale() { return scale; }
    }
}
