package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IntegratedLoudnessMeterTest {

    @Test
    void stereoPowerDoesNotCancelWhenChannelsAreOppositePhase() {
        IntegratedLoudnessMeter.Measurement mono = sine(48_000, 1, false);
        IntegratedLoudnessMeter.Measurement stereo = sine(48_000, 2, false);
        IntegratedLoudnessMeter.Measurement oppositePhase = sine(48_000, 2, true);

        assertNotNull(mono);
        assertNotNull(stereo);
        assertNotNull(oppositePhase);
        assertEquals(10.0 * Math.log10(2.0), stereo.integratedLufs - mono.integratedLufs, .02);
        assertEquals(stereo.integratedLufs, oppositePhase.integratedLufs, .01);
        assertEquals(.2, stereo.samplePeak, .0001);
    }

    @Test
    void kWeightingRemainsConsistentAcrossCommonSampleRates() {
        IntegratedLoudnessMeter.Measurement at44100 = sine(44_100, 2, false);
        IntegratedLoudnessMeter.Measurement at48000 = sine(48_000, 2, false);

        assertNotNull(at44100);
        assertNotNull(at48000);
        assertEquals(at44100.integratedLufs, at48000.integratedLufs, .05);
    }

    @Test
    void silenceHasNoIntegratedLoudness() {
        IntegratedLoudnessMeter meter = new IntegratedLoudnessMeter(48_000);
        for (int i = 0; i < 48_000; i++) meter.acceptFrame(0f, 0f, 2);
        assertNull(meter.finish());
    }

    private static IntegratedLoudnessMeter.Measurement sine(int sampleRate, int channels, boolean invertRight) {
        IntegratedLoudnessMeter meter = new IntegratedLoudnessMeter(sampleRate);
        for (int frame = 0; frame < sampleRate * 2; frame++) {
            float sample = (float) (.2 * Math.sin(2.0 * Math.PI * 997.0 * frame / sampleRate));
            float right = invertRight ? -sample : sample;
            meter.acceptFrame(sample, right, channels);
        }
        return meter.finish();
    }
}
