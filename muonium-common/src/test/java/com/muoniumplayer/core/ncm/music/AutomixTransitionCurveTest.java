package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomixTransitionCurveTest {
    @Test
    void snapshotsTheSameCurvesUsedByAudioPlan() {
        AutomixPlan plan = AutomixPlan.manualQuickMix(10_000L, true);
        AutomixTransitionCurve curve = AutomixTransitionCurve.from(plan);

        assertNotNull(curve);
        assertEquals(AutomixTransitionCurve.DEFAULT_SAMPLE_COUNT, curve.samples());
        assertEquals(plan.overlapMillis, curve.durationMillis());
        assertEquals(.5, curve.crossover(), .0001);

        for (AutomixTransitionCurve.Band band : AutomixTransitionCurve.Band.values()) {
            for (int index = 0; index < curve.samples(); index++) {
                assertTrue(Float.isFinite(curve.value(band, index)), band + " sample must be finite");
            }
        }

        assertEquals(plan.outgoingGain(0.0), curve.value(
                AutomixTransitionCurve.Band.OUTGOING_GAIN, 0), .0001);
        assertEquals(plan.incomingGain(1.0), curve.value(
                AutomixTransitionCurve.Band.INCOMING_GAIN, 1.0), .0001);
        assertEquals(plan.incomingBassDb(.5), curve.value(
                AutomixTransitionCurve.Band.INCOMING_BASS, .5), .02);
        assertEquals(plan.incomingMidDb(.5), curve.value(
                AutomixTransitionCurve.Band.INCOMING_MID, .5), .02);
    }

    @Test
    void naturalQuickMixIsAnEqualPowerPlainCrossfade() {
        AutomixPlan plan = AutomixPlan.naturalQuickMix(1_200L);

        assertEquals(1_200L, plan.overlapMillis);
        assertEquals(1f, plan.outgoingGain(0.0), .0001f);
        assertEquals(0f, plan.incomingGain(0.0), .0001f);
        assertEquals(0f, plan.outgoingGain(1.0), .0001f);
        assertEquals(1f, plan.incomingGain(1.0), .0001f);
        assertEquals(0f, plan.incomingBassDb(.5), .0001f);
        assertEquals(0f, plan.incomingMidDb(.5), .0001f);
        assertEquals(0f, plan.incomingHighDb(.5), .0001f);
        assertEquals(0f, plan.incomingVocalDepth(.5), .0001f);
        for (int i = 0; i <= 100; i++) {
            double p = i / 100.0;
            double sum = Math.pow(plan.outgoingGain(p), 2) + Math.pow(plan.incomingGain(p), 2);
            assertEquals(1.0, sum, .0001, "equal-power progress=" + p);
        }
    }

    @Test
    void trackNormalizationRatioRemovesDuplicatedWholeTrackMatch() {
        AutomixPlan matched = new AutomixPlan(0L, 2_000L, 0L, 1.4f,
                false, false, 1f, false, 0L, 0f, "matched");
        AutomixPlan neutral = new AutomixPlan(0L, 2_000L, 0L, 1f,
                false, false, 1f, false, 0L, 0f, "neutral");
        double outgoingToIncomingGain = 1.0 / 1.4;

        for (double progress : new double[]{0.0, .25, .5, .9, 1.0}) {
            assertEquals(neutral.incomingGain(progress),
                    matched.incomingGain(progress, outgoingToIncomingGain), .0001f);
        }
    }

    @Test
    void previewIsARealPlanSnapshotRatherThanAVisualOnlyCurve() {
        AutomixTransitionCurve preview = AutomixTransitionCurve.preview();

        assertNotNull(preview);
        assertEquals(10_000L, preview.durationMillis());
        assertEquals(AutomixTransitionCurve.DEFAULT_SAMPLE_COUNT, preview.samples());
        assertTrue(preview.value(AutomixTransitionCurve.Band.INCOMING_BASS, .35) < 0f);
        assertTrue(preview.value(AutomixTransitionCurve.Band.INCOMING_BASS, 1.0) == 0f);
    }
}
