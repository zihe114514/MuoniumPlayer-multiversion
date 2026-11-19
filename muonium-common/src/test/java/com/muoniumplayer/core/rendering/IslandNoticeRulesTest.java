package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IslandNoticeRulesTest {
    @Test
    void auxiliaryNoticesDefaultQuietlyWithoutHidingActionableFeedback() {
        assertTrue(IslandNoticeRules.suppressAuxiliaryNotice("LISTENING_DURATION_SUCCESS", "时长上报", false));
        assertTrue(IslandNoticeRules.suppressAuxiliaryNotice("MODEL_INFERENCE", "模型推理中", false));
        assertTrue(IslandNoticeRules.suppressAuxiliaryNotice("MODEL_INFERENCE_SUCCESS", "模型推理完成", false));
        assertFalse(IslandNoticeRules.suppressAuxiliaryNotice("MODEL_INFERENCE", "模型推理中", false, true));
        assertFalse(IslandNoticeRules.suppressAuxiliaryNotice("MODEL_INFERENCE_SUCCESS", "模型推理完成", false, true));
        assertTrue(IslandNoticeRules.suppressAuxiliaryNotice("QUALITY", "获取音质：标准", false));
        assertTrue(IslandNoticeRules.suppressAuxiliaryNotice("AMBIENT_SCENE", "平静", false));
        assertFalse(IslandNoticeRules.suppressAuxiliaryNotice("MODEL_INFERENCE_FAILURE", "模型回退", false));
        assertFalse(IslandNoticeRules.suppressAuxiliaryNotice("QUALITY", "已切换无损", false));
        assertFalse(IslandNoticeRules.suppressAuxiliaryNotice("AMBIENT_SCENE", "环境选曲未开启", false));
        assertFalse(IslandNoticeRules.suppressAuxiliaryNotice("LISTENING_DURATION_SUCCESS", "时长上报", true));
    }

    @Test
    void lowerPrioritiesNeverPreemptHigherOnes() {
        for (int current = 1; current <= 3; current++) {
            for (int incoming = 0; incoming < current; incoming++) {
                assertFalse(IslandNoticeRules.preempts(incoming, current, true, false, true, false));
            }
        }
    }

    @Test
    void highPriorityPeersAreFifo() {
        assertFalse(IslandNoticeRules.preempts(3, 3, false, false, false, false));
        assertFalse(IslandNoticeRules.preempts(3, 3, true, false, true, false));
    }

    @Test
    void instantStateInterruptsPeerButCannotStealError() {
        assertTrue(IslandNoticeRules.preempts(2, 2, false, false, true, false));
        assertFalse(IslandNoticeRules.preempts(2, 3, false, false, true, false));
    }

    @Test
    void resumedMessageGetsLegibilityProtectionExceptAgainstErrors() {
        assertFalse(IslandNoticeRules.preempts(2, 1, false, false, true, true));
        assertTrue(IslandNoticeRules.preempts(3, 1, false, false, false, true));
    }

    @Test
    void ordinaryResultsCanInterruptLoaderButNotAnotherOrdinaryResult() {
        assertTrue(IslandNoticeRules.preempts(1, 1, true, false, false, false));
        assertFalse(IslandNoticeRules.preempts(1, 1, false, false, false, false));
        assertFalse(IslandNoticeRules.preempts(1, 1, true, true, false, false));
    }

    @Test
    void errorsAndImmediateStatesOverrideDownloadsWithoutConsumingNormalNotices() {
        for (int priority = 2; priority <= 3; priority++) {
            assertTrue(IslandNoticeRules.ownsSurface(true, priority, true, false));
            assertTrue(IslandNoticeRules.ownsSurface(true, priority, false, true));
        }
        assertFalse(IslandNoticeRules.ownsSurface(true, 1, true, false));
        assertFalse(IslandNoticeRules.ownsSurface(true, 0, false, true));
        assertTrue(IslandNoticeRules.ownsSurface(true, 1, false, false));
        assertFalse(IslandNoticeRules.ownsSurface(false, 3, false, false));
    }

    @Test
    void musicCardProtectsItsSurfaceFromEveryNoticeIncludingErrors() {
        for (int priority = 0; priority <= 3; priority++) {
            for (boolean download : new boolean[]{false, true}) {
                for (boolean completion : new boolean[]{false, true}) {
                    assertFalse(IslandNoticeRules.ownsSurface(true, priority, download, completion, true));
                }
            }
        }
        assertFalse(IslandNoticeRules.ownsSurface(false, 3, false, false, true));
    }

    @Test
    void hiddenBackendlessAndCoveredFramesNeverSpendDisplayBudget() {
        assertTrue(IslandNoticeRules.presentationReady(true, true, 1f, 1.0, 1f, 1f, 1f, false));
        assertFalse(IslandNoticeRules.presentationReady(false, true, 1f, 1.0, 1f, 1f, 1f, false));
        assertFalse(IslandNoticeRules.presentationReady(true, false, 1f, 1.0, 1f, 1f, 1f, false));
        assertFalse(IslandNoticeRules.presentationReady(true, true, .50f, 1.0, 1f, 1f, 1f, false));
        assertFalse(IslandNoticeRules.presentationReady(true, true, 1f, .50, 1f, 1f, 1f, false));
        assertFalse(IslandNoticeRules.presentationReady(true, true, 1f, 1.0, .50f, 1f, 1f, false));
        assertFalse(IslandNoticeRules.presentationReady(true, true, 1f, 1.0, 1f, .50f, 1f, false));
        assertFalse(IslandNoticeRules.presentationReady(true, true, 1f, 1.0, 1f, 1f, .50f, false));
        assertTrue(IslandNoticeRules.presentationReady(true, true, 1f, 1.0, 1f, 1f, .50f, true));
    }

    @Test
    void displayMinimumsKeepMessagesReadable() {
        assertEquals(1_800L, IslandNoticeRules.minimumVisibleMillis(3, false));
        assertEquals(500L, IslandNoticeRules.minimumVisibleMillis(2, true));
        assertEquals(1_000L, IslandNoticeRules.minimumVisibleMillis(1, false));
    }
}
