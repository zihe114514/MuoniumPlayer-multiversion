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
    void previewIsARealPlanSnapshotRatherThanAVisualOnlyCurve() {
        AutomixTransitionCurve preview = AutomixTransitionCurve.preview();

        assertNotNull(preview);
        assertEquals(10_000L, preview.durationMillis());
        assertEquals(AutomixTransitionCurve.DEFAULT_SAMPLE_COUNT, preview.samples());
        assertTrue(preview.value(AutomixTransitionCurve.Band.INCOMING_BASS, .35) < 0f);
        assertTrue(preview.value(AutomixTransitionCurve.Band.INCOMING_BASS, 1.0) == 0f);
    }
}
