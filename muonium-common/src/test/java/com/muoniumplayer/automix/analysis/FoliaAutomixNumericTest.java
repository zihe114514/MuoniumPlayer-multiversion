package com.muoniumplayer.automix.analysis;

import com.muoniumplayer.automix.planning.AutomixPlan;
import com.muoniumplayer.automix.planning.FoliaAutomixPlanner;
import com.muoniumplayer.automix.planning.FoliaTransitionStyle;

import com.muoniumplayer.automix.config.AutomixSettings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Folia 规划的数值契约，源存在性测试无法覆盖这些约束。 */
class FoliaAutomixNumericTest {

    @Test
    void outgoingCutFloorUsesCeilingRoundedFiveSevenths() {
        assertEquals(0L, FoliaAutomixPlanner.minimumPlayedMillis(0L));
        assertEquals(71_429L, FoliaAutomixPlanner.minimumPlayedMillis(100_000L));
        assertEquals(85_715L, FoliaAutomixPlanner.minimumPlayedMillis(120_000L));
        assertEquals(FoliaAutomixPlanner.minimumPlayedMillis(120_000L),
                AutomixAutoCue.earliestCutMillis(120_000L, 0L));
        assertEquals(90_000L, AutomixAutoCue.earliestCutMillis(120_000L, 90_000L));
    }

    @Test
    void barQuantisationUsesMeasuredWindowOriginAndChoosesNearestLegalBar() {
        AutomixAnalyzer.BeatGrid grid = new AutomixAnalyzer.BeatGrid(120f, 0f, 500f, .9f);
        assertEquals(91_500L, FoliaAutomixPlanner.nearestBarWithin(92_000L, 90_000L, 95_000L,
                7_000L, grid));
        assertEquals(-1L, FoliaAutomixPlanner.nearestBarWithin(92_000L, 92_000L, 92_500L,
                7_000L, grid));
        assertEquals(4_500L, FoliaAutomixPlanner.nextBarAtOrAfter(4_000L, grid, 0L));
        assertEquals(4_000L, FoliaAutomixPlanner.nextBarAtOrAfter(4_000L, null, 0L));
        assertEquals(-1L, FoliaAutomixPlanner.nearestBarWithin(4_000L, 0L, 8_000L, 0L, null));
    }

    @Test
    void analysisEdgesAreReportedInMilliseconds() {
        int rate = 44_100;
        float[] audio = new float[rate * 4];
        for (int i = rate; i < audio.length; i++) {
            audio[i] = (float) (.2 * Math.sin(2.0 * Math.PI * 440.0 * i / rate));
        }

        float introMillis = AutomixAnalyzer.detectIntroEnd(audio, rate);

        assertTrue(introMillis >= 900f && introMillis <= 1_100f,
                "a one-second silent lead must be measured near 1000 ms, not converted to seconds twice");
    }

    @Test
    void foliaTempoUsesTwelvePercentRangeWithoutChangingLegacyBudget() {
        AutomixPlan folia = plan(1.10f, FoliaTransitionStyle.BASS_SWAP, .42, .42, .45);
        AutomixPlan legacy = plan(1.10f, null, .42, .42, .45);

        assertEquals(1.10f, folia.outgoingTempo(.25, 8_000L), .0001f);
        assertEquals(AutomixSettings.MAX_PLAYBACK_RATE, legacy.outgoingTempo(.25, 8_000L), .0001f);
        assertEquals(.88f, AutomixSettings.FOLIA_MIN_TEMPO_RATE, .0001f);
        assertEquals(1.12f, AutomixSettings.FOLIA_MAX_TEMPO_RATE, .0001f);
    }

    @Test
    void measuredStemHandoversKeepTheLatePartOfTheBlend() {
        AutomixPlan plan = plan(1f, FoliaTransitionStyle.BASS_SWAP, .84, .86, .87);

        assertEquals(.84, plan.drumHandoverAt, .0001);
        assertEquals(.86, plan.bassHandoverAt, .0001);
        assertEquals(.87, plan.vocalEntryAt, .0001);
    }

    @Test
    void foliaBlendAddsHeadroomOnlyInTheMiddle() {
        AutomixPlan folia = plan(1f, FoliaTransitionStyle.BASS_SWAP, .42, .42, .45);
        AutomixPlan legacy = plan(1f, null, .42, .42, .45);

        assertEquals(1f, folia.outgoingGain(0), .0001f);
        assertEquals(1f, folia.incomingGain(1), .0001f);
        assertTrue(folia.outgoingGain(.5) < legacy.outgoingGain(.5));
        double middlePower = Math.pow(folia.outgoingGain(.5), 2) + Math.pow(folia.incomingGain(.5), 2);
        assertEquals(Math.pow(10.0, -1.5 / 10.0), middlePower, .001);
    }

    @Test
    void unimplementedEchoIsNotAdvertisedByThePlan() {
        AutomixPlan plan = plan(1f, FoliaTransitionStyle.BEAT_CUT, .42, .42, .45);
        assertTrue(!plan.echoThrow);
    }

    private static AutomixPlan plan(float tempo, FoliaTransitionStyle style,
                                    double drums, double bass, double vocals) {
        return new AutomixPlan(60_000L, 8_000L, 0L, 1f,
                true, true, tempo, false, 0L, .62f, "test", style,
                0f, 0f, false, drums, bass, vocals, true, 500.0);
    }
}
