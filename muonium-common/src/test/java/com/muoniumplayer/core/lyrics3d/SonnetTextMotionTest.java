package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SonnetTextMotionTest {
    @Test void cubicTimingMatchesTheReferenceCurveAndRemainsMonotonic() {
        assertEquals(0, SonnetTextMotion.inOut(0));
        assertEquals(1, SonnetTextMotion.inOut(1));
        assertEquals(0.5, SonnetTextMotion.inOut(0.5), 1e-7);
        double previous = 0;
        for (int i = 0; i <= 200; i++) {
            double value = SonnetTextMotion.inOut(i / 200.0);
            assertTrue(value >= previous - 1e-8);
            assertEquals(1 - value, SonnetTextMotion.inOut(1 - i / 200.0), 1e-6);
            previous = value;
        }
    }

    @Test void authoredDurationsControlTheSettlingWindow() {
        assertEquals(144, SonnetTextMotion.motionDuration(0, 200), 1e-7);
        assertEquals(650, SonnetTextMotion.motionDuration(0, 1000), 1e-7);
        assertEquals(1260, SonnetTextMotion.motionDuration(0, 3000), 1e-7);
        assertEquals(1800, SonnetTextMotion.motionDuration(0, 8000), 1e-7);
        assertEquals(0, SonnetTextMotion.progress(1000, 2800, 900));
        assertTrue(SonnetTextMotion.progress(1000, 2800, 1450) > 0.8);
        assertEquals(1, SonnetTextMotion.progress(1000, 2800, 2900));
    }

    @Test void hollowEchoRisesBeforeItFallsAndSpreadsOnlyOnce() {
        assertEquals(0, SonnetTextMotion.ghostEnvelope(0));
        assertEquals(1, SonnetTextMotion.ghostEnvelope(0.2), 1e-7);
        assertEquals(0.25, SonnetTextMotion.ghostEnvelope(0.6), 1e-7);
        assertEquals(0, SonnetTextMotion.ghostEnvelope(1));
        assertEquals(0.875, SonnetTextMotion.ghostSpread(0.5), 1e-7);
        assertEquals(400, SonnetTextMotion.ghostDuration(1000));
        assertEquals(700, SonnetTextMotion.ghostDuration(6000));
    }

    @Test void sceneTransitionsDoNotIntroduceChromaticCopiesOrInventCameraScaling() {
        SonnetTextMotion.Transition transition = new SonnetTextMotion.Transition();
        SonnetTextMotion.transition(0, true, 1, 7, transition);
        assertEquals(14, transition.blur); assertEquals(0, transition.alpha);
        SonnetTextMotion.transition(1, true, 0.8, 7, transition);
        assertTrue(transition.glitch > 0); assertEquals(1, transition.alpha);
        SonnetTextMotion.transition(2, false, 0, 7, transition);
        assertEquals(0.28, transition.alpha, 1e-7);
        assertEquals(0, transition.glitch); assertEquals(0, transition.blur);
    }
}