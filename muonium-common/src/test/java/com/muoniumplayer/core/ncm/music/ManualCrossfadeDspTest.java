package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import repackage.com.jsyn.unitgen.VocalDuckFilter;

import static org.junit.jupiter.api.Assertions.*;

/** 既验证曲线，也直接生成 PCM，防止“只改参数却没有真正处理音频”。 */
class ManualCrossfadeDspTest {
    @Test
    void manualProfileHasNeutralEndpointsAndNoAnalysisDependentProcessing() {
        AutomixPlan plan = AutomixPlan.manualQuickMix(1_200L);
        assertTrue(plan.manualQuickMix);
        assertFalse(plan.barAligned);
        assertFalse(plan.stemDriven);
        assertFalse(plan.pitchShift);
        assertEquals(0L, plan.incomingCueMillis);
        assertEquals(0L, plan.phaseErrorMillis);
        assertEquals(1f, plan.matchGain);
        assertEquals(0f, plan.outgoingBassDb(0));
        assertEquals(0f, plan.outgoingMidDb(0));
        assertEquals(0f, plan.outgoingHighDb(0));
        for (double p : new double[]{.85, 1.0, 2.0, Double.POSITIVE_INFINITY}) {
            assertEquals(0f, plan.incomingBassDb(p));
            assertEquals(0f, plan.incomingMidDb(p));
            assertEquals(0f, plan.incomingHighDb(p));
        }
        for (double p : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, -.5, 0, .5, 1, 2}) {
            assertTrue(Float.isFinite(plan.outgoingGain(p)));
            assertTrue(Float.isFinite(plan.incomingGain(p)));
            assertEquals(0f, plan.incomingVocalDepth(p));
            assertEquals(0f, plan.outgoingVocalDepth(p));
            assertEquals(1f, plan.outgoingTempo(p, 1_200L));
            assertEquals(1f, plan.outgoingPitch(p, 1_200L));
        }
    }

    @Test
    void disablingProcessingKeepsEveryFrequencyBandAndVocalNeutral() {
        AutomixPlan plan = AutomixPlan.manualQuickMix(1_200L, false);
        for (int i = 0; i <= 100; i++) {
            double p = i / 100.0;
            assertEquals(0f, plan.incomingBassDb(p));
            assertEquals(0f, plan.outgoingBassDb(p));
            assertEquals(0f, plan.incomingMidDb(p));
            assertEquals(0f, plan.outgoingMidDb(p));
            assertEquals(0f, plan.incomingHighDb(p));
            assertEquals(0f, plan.outgoingHighDb(p));
            assertEquals(0f, plan.incomingVocalDepth(p));
            assertEquals(0f, plan.outgoingVocalDepth(p));
            assertEquals(1.0, Math.pow(plan.incomingGain(p), 2) + Math.pow(plan.outgoingGain(p), 2), 1e-6);
        }
    }

    @Test
    void frequencyHandoverIsSmoothMonotonicAndLeavesHeadroom() {
        AutomixPlan plan = AutomixPlan.manualQuickMix(1_200L);
        float oldBass = plan.outgoingBassDb(0), newBass = plan.incomingBassDb(0);
        float oldMid = plan.outgoingMidDb(0), oldHigh = plan.outgoingHighDb(0);
        float newMid = plan.incomingMidDb(0), newHigh = plan.incomingHighDb(0);
        for (int i = 1; i <= 1_200; i++) {
            double p = i / 1_200.0;
            float bassOut = plan.outgoingBassDb(p), bassIn = plan.incomingBassDb(p);
            float midOut = plan.outgoingMidDb(p), highOut = plan.outgoingHighDb(p);
            float midIn = plan.incomingMidDb(p), highIn = plan.incomingHighDb(p);
            assertTrue(bassOut <= oldBass && bassIn >= newBass);
            assertTrue(midOut <= oldMid && highOut <= oldHigh);
            assertTrue(midIn >= newMid && highIn >= newHigh);
            assertTrue(Math.abs(bassOut - oldBass) < .04f);
            assertTrue(Math.abs(bassIn - newBass) < .04f);
            assertTrue(Math.abs(midOut - oldMid) < .02f);
            assertTrue(Math.abs(highOut - oldHigh) < .02f);
            double power = Math.pow(plan.outgoingGain(p), 2) + Math.pow(plan.incomingGain(p), 2);
            assertTrue(power <= 1.000001 && power >= .74);
            oldBass = bassOut; newBass = bassIn;
            oldMid = midOut; oldHigh = highOut;
            newMid = midIn; newHigh = highIn;
        }
        assertEquals(-12f, plan.outgoingBassDb(.55));
        assertEquals(0f, plan.incomingBassDb(.78));
        assertEquals(Math.pow(10.0, -1.25 / 10.0),
                Math.pow(plan.incomingGain(.5), 2) + Math.pow(plan.outgoingGain(.5), 2), 1e-6);
    }

    @Test
    void bassCutChangesLowFrequencySamplesNotJustOverallVolume() {
        assertTrue(response(60, -12, 0, 0) < .40);
        assertTrue(response(1_200, -12, 0, 0) > .97);
        assertTrue(response(10_000, -12, 0, 0) > .99);
    }

    @Test
    void midCutReallyTargetsTheMidBand() {
        assertTrue(response(60, 0, -3, 0) > .94);
        assertTrue(response(1_200, 0, -3, 0) < .85);
        assertTrue(response(10_000, 0, -3, 0) > .88);
    }

    @Test
    void highCutSoftensTrebleWithoutDullingTheBass() {
        assertTrue(response(60, 0, 0, -3) > .99);
        assertTrue(response(1_200, 0, 0, -3) > .96);
        assertTrue(response(10_000, 0, 0, -3) < .87);
    }

    @Test
    void resettingAfterProcessedAudioReturnsToBitExactBypass() {
        VocalDuckFilter filter = filter(-12, -2.5f, -3);
        double[] input = filter.input.getValues();
        for (int block = 0; block < 200; block++) {
            for (int i = 0; i < input.length; i++) input[i] = .2 * Math.sin((block * input.length + i) * .03);
            filter.generate(0, input.length);
        }
        filter.resetTo(0);
        filter.resetDepthTo(0);
        filter.resetToneTo(0, 0);
        filter.setTransitionToneBands(false);
        for (int i = 0; i < input.length; i++) input[i] = .2 * Math.sin(i * .17);
        filter.generate(0, input.length);
        assertArrayEquals(input, filter.output.getValues(), 0.0);
    }

    private static VocalDuckFilter filter(float bass, float mid, float high) {
        VocalDuckFilter filter = new VocalDuckFilter();
        filter.setTransitionToneBands(true);
        filter.setFrequency(AutomixSettings.BASS_SHELF_HZ);
        filter.resetTo(bass);
        filter.resetDepthTo(0);
        filter.resetToneTo(mid, high);
        return filter;
    }

    private static double response(double frequency, float bass, float mid, float high) {
        VocalDuckFilter filter = filter(bass, mid, high);
        double[] input = filter.input.getValues();
        double inputEnergy = 0, outputEnergy = 0;
        for (int block = 0; block < 1_024; block++) {
            for (int i = 0; i < input.length; i++) {
                input[i] = .2 * Math.sin(2 * Math.PI * frequency * (block * input.length + i) / 44_100);
            }
            filter.generate(0, input.length);
            if (block < 512) continue;
            double[] output = filter.output.getValues();
            for (int i = 0; i < input.length; i++) {
                assertTrue(Double.isFinite(output[i]));
                inputEnergy += input[i] * input[i];
                outputEnergy += output[i] * output[i];
            }
        }
        return Math.sqrt(outputEnergy / inputEnergy);
    }
}
