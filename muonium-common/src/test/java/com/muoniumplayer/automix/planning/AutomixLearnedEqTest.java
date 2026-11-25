package com.muoniumplayer.automix.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomixLearnedEqTest {

    @Test
    void disabledPlanKeepsAllLearnedBandsFlat() {
        AutomixPlan plan = new AutomixPlan(60_000L, 8_000L, 0L, 1f,
                true, true, 1f, false, 0L, .5f, "test",
                FoliaTransitionStyle.BASS_SWAP, 0f, 0f, false,
                .42, .42, .45, false, 500.0, 0f, 0.0, null);

        assertFalse(plan.learnedEqEnabled);
        assertEquals(0f, plan.incomingLearnedEqDb(0, .5), .0001f);
        assertEquals(0f, plan.incomingLearnedEqDb(1, .5), .0001f);
        assertEquals(0f, plan.incomingLearnedEqDb(2, .5), .0001f);
    }

    @Test
    void learnedControlsAreFiniteAndReturnToFlatAtTheEnd() {
        float[] controls = {
                0f, .2f, .4f, 1f,
                .1f, .3f, .5f, 1f,
                .2f, .4f, .6f, 1f
        };
        AutomixPlan plan = new AutomixPlan(60_000L, 8_000L, 0L, 1f,
                true, true, 1f, false, 0L, .5f, "test",
                FoliaTransitionStyle.BASS_SWAP, 0f, 0f, false,
                .42, .42, .45, false, 500.0, 0f, 0.0, controls);

        assertTrue(plan.learnedEqEnabled);
        assertTrue(plan.incomingLearnedEqDb(0, .5) < 0f);
        assertTrue(plan.incomingLearnedEqDb(1, .5) < 0f);
        assertTrue(plan.incomingLearnedEqDb(2, .5) < 0f);
        assertEquals(0f, plan.incomingLearnedEqDb(0, 1.0), .0001f);
        assertEquals(0f, plan.incomingLearnedEqDb(1, 1.0), .0001f);
        assertEquals(0f, plan.incomingLearnedEqDb(2, 1.0), .0001f);
    }
}
