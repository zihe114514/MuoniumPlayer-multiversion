package com.muoniumplayer.core.ncm.music;

import java.util.Locale;

/**
 * Evidence-first Folia-style transition planner.
 *
 * <p>It deliberately separates selecting a transition family from scheduling gains. The legacy planner
 * remains available and is selected by {@link AutomixSettings.Engine}; this class never changes user
 * DSP preferences, it only chooses a musically safer plan for the measured pair.</p>
 *
 * <p>The implementation is repository-native Java and uses the existing offline analysis primitives.
 * The model-backed Beat This and stem phases are layered on later; when a model is unavailable the same
 * policy runs on the deterministic analysis fallback.</p>
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
    private static final float MIN_GRID_CONFIDENCE = .28f;
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

        float introEnd = incomingProfile != null ? incomingProfile.leadInMillis / 1000f
                : (incomingHead.length > 0 ? AutomixAnalyzer.detectIntroEnd(incomingHead, incomingAudio.sampleRate()) : 0f);
        introEnd = Math.max(0f, Math.min((float) incomingMillis, introEnd));
        float outroLanding = outgoingProfile != null && outgoingProfile.outroLandingMillis > 0f
                ? (outgoingProfile.outroLandingMillis - outgoingWindowStart) / 1000f
                : (outgoingTail.length > 0 ? AutomixAnalyzer.detectOutroLanding(outgoingTail, outgoingAudio.sampleRate()) : -1f);

        boolean outgoingUsable = usableGrid(outgoingGrid);
        boolean incomingUsable = usableGrid(incomingGrid);
        TempoMatch tempo = tempoMatch(outgoingUsable ? outgoingGrid.bpm : null,
                incomingUsable ? incomingGrid.bpm : null);
        KeyRelation keyRelation = keyRelation(outgoingKey, incomingKey);
        boolean startsHot = introEnd <= .35f;
        boolean endsHot = outgoingProfile != null ? outgoingProfile.endsHot
                : endsHot(outgoingTail, outgoingAudio == null ? 0 : outgoingAudio.sampleRate());
        float outroSlope = outgoingProfile != null ? outgoingProfile.outroSlopeDbPerSecond
                : outroSlope(outgoingTail, outgoingAudio == null ? 0 : outgoingAudio.sampleRate());

        FoliaTransitionStyle style = chooseStyle(tempo, startsHot, endsHot, outroSlope,
                outgoingUsable, incomingUsable, introEnd,
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
        if (outroLanding > 0f) {
            long landing = outgoingWindowStart + (long) outroLanding;
            if (landing >= earliestFire && landing <= latestFire) anchor = landing;
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
        if (AutomixSettings.isSkipIntroEnabled() && introEnd > 50f) {
            cue = Math.min(INTRO_SKIP_LIMIT_MILLIS, (long) introEnd);
            if (AutomixSettings.isBeatAlignEnabled() && incomingGrid != null && incomingUsable) {
                long beat = Math.max(180L, (long) (60_000f / incomingGrid.bpm));
                long phase = positiveModulo((long) incomingGrid.barPhaseMillis, beat);
                long snapped = cue + positiveModulo(phase - cue, beat);
                if (snapped + overlap < incomingMillis) cue = snapped;
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
        float midTilt = outgoingProfile == null || incomingProfile == null ? 0f
                : toneTilt(outgoingProfile.toneMid, incomingProfile.toneMid);
        float highTilt = outgoingProfile == null || incomingProfile == null ? 0f
                : toneTilt(outgoingProfile.toneHigh, incomingProfile.toneHigh);
        boolean beatMatched = AutomixSettings.isBeatAlignEnabled() && outgoingUsable && incomingUsable && tempoRatio != 1f;
        String summary = String.format(Locale.ROOT,
                "%s %.2fs overlap, %s keys, %s, fire %dms cue %dms%s%s%s",
                style.id, overlap / 1000.0, keyRelation.id, tempo.relation.id, fire, cue,
                bassSwap ? ", bass swap" : "", vocalDuck > 0f ? ", vocal hold" : "",
                stemDriven ? ", stem timing" : "");

        double animationPeriod = outgoingUsable && outgoingGrid != null && outgoingGrid.bpm > 1f
                ? 60_000.0 / outgoingGrid.bpm : 500.0;
        if (tempoRatio > 0f) animationPeriod /= tempoRatio;
        System.out.println("[Automix/Folia] " + summary);
        return new AutomixPlan(fire, overlap, cue, gain, beatMatched, bassSwap, tempoRatio,
                false, phaseError, vocalDuck, summary, style,
                midTilt, highTilt, style.usesEchoThrow() || endsHot,
                drumAt, bassAt, vocalEntry, stemDriven, animationPeriod);
    }

    private static boolean usableStems(FoliaStemProfile stems) {
        return AutomixSettings.isPerformanceModeEnabled() && stems != null && stems.isUsable();
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
                                                    boolean incomingUsable, float introEnd,
                                                    AutomixAnalyzer.BeatGrid outgoingGrid) {
        long beatMillis = outgoingUsable && outgoingGrid != null && outgoingGrid.bpm > 1f
                ? Math.max(1L, (long) (60_000f / outgoingGrid.bpm)) : 500L;
        if (startsHot && outgoingUsable) {
            return introEnd >= beatMillis / 1000f ? FoliaTransitionStyle.BEAT_CUT
                    : FoliaTransitionStyle.BASS_SWAP;
        }
        if (tempo.relation == TempoRelation.FAR && outgoingUsable
                && introEnd * 1000f >= beatMillis) return FoliaTransitionStyle.BEAT_CUT;
        if (outroSlope <= -1.5f) return FoliaTransitionStyle.BASS_SWAP;
        if (!endsHot && (!startsHot || introEnd > .3f)) return FoliaTransitionStyle.TAIL_RIDE;
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

    /** Folia rounds to whole phrases, bars or beats, largest unit that fits. */
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
        return Math.max(AutomixSettings.MIN_PLAYBACK_RATE,
                Math.min(AutomixSettings.MAX_PLAYBACK_RATE, rate));
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
