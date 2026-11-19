package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardInteractionTest {
    private static void settle(MusicCardInteraction state, boolean chat, boolean blocked, int trigger) {
        for (int i = 0; i < 30; i++) state.update(true, chat, true, blocked, trigger, 40.0);
    }

    private static void present(MusicCardInteraction state, boolean chat, int trigger, int frames, long startedAt) {
        for (int i = 0; i < frames; i++) {
            state.update(true, chat, true, false, trigger, 40.0);
            state.recordPresentation(state.eventRevision(), true, startedAt + i * 40L);
        }
    }


    @Test
    void manualCardRetainsSeekWhileOpen() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 1);
        state.expand();
        settle(state, true, false, 1);
        state.beginSeek(4L, "NETEASE:123", .42);
        assertTrue(state.userExpanded());
        assertTrue(state.dragging(4L, "NETEASE:123"));

        state.update(true, true, true, false, 1, 16.0);
        assertTrue(state.userExpanded());
        assertTrue(state.dragging(4L, "NETEASE:123"), "track notice must not steal the user's open card");
    }

    @Test
    void leavingChatCanFinishAClosingSurfaceAfterTrackMetadataChanges() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.collapse();
        state.update(true, false, true, false, 0, 16.0);
        assertTrue(state.animationActive());
        assertTrue(state.openness() < 1.0);
    }

    @Test
    void autoExpansionBelongsToEachChatSessionAndOutsideDismissalDoesNotImmediatelyReopen() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, false, false, 0);
        assertFalse(state.wantsOpen());
        settle(state, true, false, 0);
        assertTrue(state.canInteract());
        state.collapse();
        for (int i = 0; i < 18; i++) state.update(true, true, true, false, 0, 40.0);
        assertEquals(0.0, state.openness());
        settle(state, false, false, 0);
        settle(state, true, false, 0);
        assertTrue(state.canInteract());
    }

    @Test
    void droppingAutomaticTrackNoticeLetsTheUserDismissTheChatCard() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 1);
        state.startTrackNotice(1_000.0);
        state.update(true, true, true, false, 1, 16.0);
        assertTrue(state.dismissEvent());
        assertFalse(state.eventLocked());
        state.collapse();
        assertTrue(state.animationActive());
        settle(state, true, false, 1);
        assertFalse(state.userExpanded());
        assertFalse(state.trackNoticeActive());
        assertEquals(0.0, state.openness(), 1e-8);
    }

    @Test
    void clickModeDoesNotOpenUntilTheIslandIsClickedAndCanReopenAfterDismissal() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 1);
        assertTrue(state.available());
        assertFalse(state.wantsOpen());
        state.expand();
        settle(state, true, false, 1);
        assertTrue(state.canInteract());
        state.collapse();
        for (int i = 0; i < 30; i++) state.update(true, true, true, false, 1, 40.0);
        assertFalse(state.canInteract());
        state.expand();
        for (int i = 0; i < 30; i++) state.update(true, true, true, false, 1, 40.0);
        assertTrue(state.canInteract());
    }

    @Test
    void downloadsAndNotificationsSuspendInteractionWithoutLosingTheChatTrigger() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 1);
        state.expand();
        settle(state, true, false, 1);
        state.beginSeek(4L, "NETEASE:123", .35);
        state.update(true, true, true, true, 1, 16.0);
        assertFalse(state.canInteract());
        assertFalse(state.dragging(4L, "NETEASE:123"));
        settle(state, true, false, 1);
        assertTrue(state.canInteract());
        assertFalse(state.dragging(4L, "NETEASE:123"), "恢复卡片不能恢复已经取消的拖动");
    }

    @Test
    void disablingLeavingChatOrLosingTheTrackCancelsSeekImmediately() {
        for (int scenario = 0; scenario < 3; scenario++) {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.beginSeek(2L, "QQ:1", .5);

            state.update(scenario != 0, scenario != 1, scenario != 2, false, 0, 16.0);
            assertFalse(state.canInteract());
            assertFalse(state.dragging(2L, "QQ:1"));
        }
    }

    @Test
    void seekDoesNotCrossSessionOrPlatformEvenWhenNumericSongIdsMatch() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.beginSeek(1L, "NETEASE:42", .2);
        state.previewSeek(1.5);
        assertEquals(1.0, state.seekFraction());
        assertTrue(state.dragging(1L, "NETEASE:42"));
        assertFalse(state.dragging(1L, "QQ:42"));
        state.beginSeek(1L, "NETEASE:42", .2);
        assertFalse(state.dragging(2L, "NETEASE:42"));
    }

    @Test
    void capturedMouseReleaseIsConsumedOnceEvenAfterTheCardCloses() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.capturePress();
        state.beginSeek(9L, "LOCAL:a", .5);
        state.collapse();
        assertTrue(state.release());
        assertFalse(state.release());
        assertFalse(state.dragging(9L, "LOCAL:a"));
    }

    @Test
    void contentAndGeometryDoNotJumpWhenDirectionChanges() {
        MusicCardInteraction state = new MusicCardInteraction();
        for (int i = 0; i < 8; i++) state.update(true, true, true, false, 0, 16.0);
        double open = state.openness();
        double content = state.contentReveal();
        state.collapse();
        state.update(true, true, true, false, 0, 0.0);
        assertEquals(open, state.openness());
        assertEquals(content, state.contentReveal());
        state.update(true, true, true, false, 0, 16.0);
        open = state.openness();
        content = state.contentReveal();
        state.expand();
        state.update(true, true, true, false, 0, 0.0);
        assertEquals(open, state.openness());
        assertEquals(content, state.contentReveal());
    }

    @Test
    void noticePausesWithoutRestartingAndDoesNotCloseAnOpenChatCard() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.startTrackNotice(1_000.0);
        state.update(true, true, true, false, 0, 40.0);
        double open = state.openness();
        for (int i = 0; i < 50; i++) state.update(true, true, true, true, 0, 40.0);
        assertEquals(open, state.openness());
        assertTrue(state.eventLocked());
        state.resumeTrackNotice();
        state.update(true, true, true, false, 0, 40.0);
        assertEquals(open, state.openness());
        present(state, true, 0, 41, 1_000L);
        assertFalse(state.eventLocked());
        assertFalse(state.trackNoticeActive());
        assertTrue(state.canInteract());
        assertEquals(1.0, state.openness());
    }

    @Test
    void noChatNoticeReturnsToIslandWithoutASecondOscillatingTail() {
        MusicCardInteraction state = new MusicCardInteraction();
        state.startTrackNotice(100.0);
        boolean closing = false;
        for (int i = 0; i < 70; i++) {
            state.update(true, false, true, false, 0, 40.0);
            state.recordPresentation(state.eventRevision(), true, 1_000L + i * 40L);
            closing |= !state.wantsOpen() && state.animationActive();
        }
        assertTrue(closing);
        assertEquals(0.0, state.openness());
        assertEquals(0.0, state.contentReveal());
        assertFalse(state.animationActive());
        assertFalse(state.trackNoticeActive());
    }

    @Test
    void missingCoverDoesNotConsumeNoticeHoldAndInvalidPlaybackCancelsLock() {
        MusicCardInteraction state = new MusicCardInteraction();
        state.startTrackNotice(100.0);
        for (int i = 0; i < 50; i++) state.update(true, false, true, false, 0, 40.0, false);
        assertTrue(state.eventLocked());
        assertEquals(1.0, state.openness(), "missing art cannot postpone the card's opening response");
        assertEquals(0L, state.visibleHoldMillis());
        state.update(true, false, false, false, 0, 16.0, false);
        assertFalse(state.eventLocked());
        settle(state, false, false, 0);
        assertFalse(state.animationActive());
    }

    @Test
    void animationUsesElapsedTimeAndReversesWithoutJumping() {
        MusicCardInteraction fast = new MusicCardInteraction();
        MusicCardInteraction slow = new MusicCardInteraction();
        for (int i = 0; i < 12; i++) fast.update(true, true, true, false, 0, 10.0);
        for (int i = 0; i < 6; i++) slow.update(true, true, true, false, 0, 20.0);
        assertEquals(fast.openness(), slow.openness(), 1e-10);
        double previous = fast.openness();
        fast.collapse();
        assertEquals(previous, fast.openness());
        fast.update(true, true, true, false, 0, 1.0);
        assertTrue(fast.openness() >= previous, "reversing must first brake the currently displayed velocity");
        for (int i = 0; i < 10; i++) fast.update(true, true, true, false, 0, 16.0);
        assertTrue(fast.openness() < previous && fast.openness() > 0.0);
        assertTrue(fast.transitionVelocity() < 0.0);
        fast.update(true, true, true, false, 0, Double.NaN);
        assertTrue(Double.isFinite(fast.openness()));
    }

    @Test
    void currentCardRequestOwnsSurfaceBeforeFirstFrameAndThroughTheClosingTail() {
        MusicCardInteraction auto = new MusicCardInteraction();
        assertTrue(auto.claimsSurface(true, true, true, 0), "chat auto-open intention must win before rendering");
        MusicCardInteraction click = new MusicCardInteraction();
        settle(click, true, false, 1);
        assertFalse(click.claimsSurface(true, true, true, 1));
        click.expand();
        assertTrue(click.claimsSurface(true, true, true, 1));
        settle(click, true, false, 1);
        click.collapse();
        click.update(true, false, true, false, 1, 16.0);
        assertTrue(click.claimsSurface(true, false, true, 1), "notifications cannot steal the closing tail");
        MusicCardInteraction event = new MusicCardInteraction();
        event.startTrackNotice(MusicCardInteraction.TRACK_NOTICE_HOLD_MILLIS);
        assertTrue(event.claimsSurface(true, false, true, 1));
        assertFalse(event.claimsSurface(false, false, true, 1));
        assertFalse(event.claimsSurface(true, false, false, 1));
    }

    @Test
    void threeSecondHoldBeginsOnlyAfterSuccessfulFullyOpenPresentation() {
        MusicCardInteraction state = new MusicCardInteraction();
        state.startTrackNotice(MusicCardInteraction.TRACK_NOTICE_HOLD_MILLIS);
        for (int i = 0; i < 100; i++) {
            state.update(true, false, true, false, 0, 40.0, false);
            state.recordPresentation(state.eventRevision(), false, 1_000L + i * 40L);
        }
        assertEquals(1.0, state.openness(), "card can open with a placeholder while the visible hold remains unspent");
        assertEquals(0L, state.visibleHoldMillis());
        settle(state, false, false, 0);
        assertEquals(1.0, state.openness());
        assertEquals(0L, state.visibleHoldMillis(), "update ticks alone cannot spend the visible hold");
        present(state, false, 0, 76, 10_000L);
        assertEquals(3_000L, state.visibleHoldMillis());
        state.update(true, false, true, false, 0, 16.0);
        assertFalse(state.eventLocked());
        assertTrue(state.animationActive());
        settle(state, false, false, 0);
        assertFalse(state.animationActive());
    }

    @Test
    void hiddenFramesPauseTheHoldAndRapidTrackChangesNeverClearTheCurrentPose() {
        MusicCardInteraction state = new MusicCardInteraction();
        state.startTrackNotice(MusicCardInteraction.TRACK_NOTICE_HOLD_MILLIS);
        present(state, false, 1, 40, 1_000L);
        long spent = state.visibleHoldMillis();
        double openness = state.openness();
        state.recordPresentation(state.eventRevision(), false, 5_000L);
        for (int i = 0; i < 100; i++) state.update(true, false, true, true, 1, 40.0);
        assertEquals(spent, state.visibleHoldMillis());
        assertEquals(openness, state.openness());
        state.resumeTrackNotice();
        long oldRevision = state.eventRevision();
        state.onTrackChanged(false);
        assertEquals(openness, state.openness(), "new track restarts hold, not the surface geometry");
        assertEquals(0L, state.visibleHoldMillis());
        state.recordPresentation(oldRevision, true, 100_000L);
        assertEquals(0L, state.visibleHoldMillis(), "stale draw must not credit the next track");
        state.recordPresentation(state.eventRevision(), true, 100_040L);
        assertEquals(0L, state.visibleHoldMillis(), "hidden wall time must not create catch-up debt");
    }

    @Test
    void changingTracksInsideAnOpenChatCardCancelsOnlyTheOldSeek() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 1);
        state.expand();
        settle(state, true, false, 1);
        state.beginSeek(4L, "NETEASE:123", .4);
        state.onTrackChanged(true);
        assertTrue(state.userExpanded());
        assertEquals(1.0, state.openness());
        assertFalse(state.dragging(4L, "NETEASE:123"));
        assertFalse(state.eventLocked(), "chat track change must not turn an interactive card into a locked event");
    }

    @Test
    void closeVacatesContentThenReturnsDirectlyToTheCapsuleWithoutADot() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.collapse();
        state.update(true, true, true, false, 0, 0.0);
        double previousContent = state.contentReveal();
        boolean joined = false;
        double previousWidth = 0.0, previousHeight = 0.0;
        for (int i = 1; i <= 800; i++) {
            state.update(true, true, true, false, 0, 1.0);
            double p = state.openness();
            MusicCardMotion.Frame f = MusicCardMotion.frame(p, 400, 8, 176, 26,
                    46, 14, 1.0, state.contentReveal());
            assertTrue(state.contentReveal() <= previousContent + 1e-10);
            if (i == 180) {
                assertEquals(0.0, state.contentReveal(), 1e-10);
                assertTrue(p > .6, "content should leave while a substantial card silhouette still remains");
            }
            if (p > MusicCardMotion.JOIN_OPENNESS) {
                assertEquals(176.0, f.anchorWidth(), 1e-8, "anchor stays a capsule, never a camera dot");
                if (p >= .24) assertEquals(0.0, f.anchorAlpha());
                assertEquals(1.0, f.alpha(), 1e-10, "incoming card must not vanish before contact");
            } else if (!joined) {
                assertEquals(f.anchorY() + f.anchorHeight() * .5, f.y() + f.height() * .5, 1e-8);
                assertEquals(176.0, f.width());
                assertEquals(26.0, f.height());
                joined = true;
            }
            if (joined) {
                assertTrue(f.anchorWidth() >= previousWidth - 1e-8, "restoring island cannot retract a second time");
                assertTrue(f.anchorHeight() >= previousHeight - 1e-8, "restoring height cannot overshoot and twitch back");
                assertTrue(f.anchorWidth() <= 176.0 && f.anchorHeight() <= 26.0);
                assertEquals(21.0, f.anchorY() + f.anchorHeight() * .5, 1e-8);
                if (state.animationActive()) assertTrue(state.claimsSurface(true, true, true, 0));
            }
            previousWidth = f.anchorWidth();
            previousHeight = f.anchorHeight();
            previousContent = state.contentReveal();
        }
        assertTrue(joined);
        assertEquals(0.0, state.openness());
        assertFalse(state.animationActive());
    }

    @Test
    void reopeningDuringRestorationPreservesGeometryContentAndDirectionBeforeBraking() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.collapse();
        for (int i = 0; i < 59; i++) state.update(true, true, true, false, 0, 8.0);
        assertTrue(state.openness() > .02 && state.openness() < .10);
        double openness = state.openness();
        double content = state.contentReveal();
        MusicCardMotion.Frame before = MusicCardMotion.frame(openness, 400, 8, 176, 26, 46, 14, 1.0, content);
        double velocity = state.transitionVelocity();
        state.expand();
        state.update(true, true, true, false, 0, 0.0);
        assertEquals(openness, state.openness());
        assertEquals(content, state.contentReveal());
        assertEquals(before, MusicCardMotion.frame(state.openness(), 400, 8, 176, 26,
                46, 14, 1.0, state.contentReveal()));
        assertEquals(velocity, state.transitionVelocity());
        state.update(true, true, true, false, 0, .002);
        assertEquals(velocity, (state.openness() - openness) / .002, .00008,
                "new opening must inherit the current closing velocity rather than flip its sign");
        settle(state, true, false, 0);
        assertEquals(1.0, state.openness());
        assertEquals(1.0, state.contentReveal());
    }

    @Test
    void contactAndRestorationAreConsistentAcrossThirtyAndOneHundredTwentyFramesPerSecond() {
        MusicCardInteraction coarse = new MusicCardInteraction(), fine = new MusicCardInteraction();
        settle(coarse, true, false, 0);
        settle(fine, true, false, 0);
        coarse.collapse();
        fine.collapse();
        for (int i = 0; i < 24; i++) {
            coarse.update(true, true, true, false, 0, 1000.0 / 30.0);
            for (int j = 0; j < 4; j++) fine.update(true, true, true, false, 0, 1000.0 / 120.0);
            assertEquals(fine.openness(), coarse.openness(), 1e-10);
            assertEquals(fine.contentReveal(), coarse.contentReveal(), 1e-10);
            assertEquals(MusicCardMotion.frame(fine.openness(), 400, 8, 176, 26, 46, 14, 1.0).anchorWidth(),
                    MusicCardMotion.frame(coarse.openness(), 400, 8, 176, 26, 46, 14, 1.0).anchorWidth(), 1e-8,
                    "restoration must use the same progress rather than another clock's phase");
        }
        assertFalse(coarse.animationActive());
        assertFalse(fine.animationActive());
    }

    @Test
    void closingChatAtTheRestorationTailCannotDismissTheNextTracksAutomaticCard() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.collapse();
        for (int i = 0; i < 79; i++) state.update(true, true, true, false, 0, 8.0);
        assertTrue(state.openness() < .002 && state.animationActive());
        state.collapse(); // Context maintenance closes the old chat while its tail still owns the surface.
        state.update(true, false, true, false, 0, 0.0);
        settle(state, false, false, 0);
        state.startTrackNotice(MusicCardInteraction.TRACK_NOTICE_HOLD_MILLIS);
        state.update(true, false, true, false, 0, 16.0);
        assertTrue(state.eventLocked(), "old chat dismissal must not cancel a younger track event");
        assertTrue(state.openness() > 0.0);
        settle(state, false, false, 0);
        assertEquals(1.0, state.openness());
    }

    @Test
    void resettingWhileClosingClearsVelocityAndContentForTheNextSession() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.collapse();
        for (int i = 0; i < 65; i++) state.update(true, true, true, false, 0, 8.0);
        assertTrue(state.animationActive() && state.openness() < .10);
        state.reset();
        assertEquals(0.0, state.openness());
        assertEquals(0.0, state.transitionVelocity());
        assertEquals(0.0, state.contentReveal());
        assertFalse(state.animationActive());
        settle(state, true, false, 0);
        assertEquals(1.0, state.openness());
        assertEquals(1.0, state.contentReveal());
    }

    @Test
    void openingSpreadsItsVisibleMotionAcrossTheDurationWithoutDelayingItsFirstResponse() {
        MusicCardInteraction state = new MusicCardInteraction();
        state.update(true, true, true, false, 0, 40.0);
        assertTrue(state.openness() > .02 && state.openness() < .10, "first visible frame should respond without jumping");
        for (int i = 1; i < 4; i++) state.update(true, true, true, false, 0, 40.0);
        assertTrue(state.openness() > .12 && state.openness() < .25, "early motion should detach rather than finish in one burst");
        for (int i = 4; i < 9; i++) state.update(true, true, true, false, 0, 40.0);
        assertTrue(state.openness() > .45 && state.openness() < .70, "midpoint retains a substantial unfold");
        for (int i = 9; i < 16; i++) state.update(true, true, true, false, 0, 40.0);
        assertTrue(state.openness() < 1.0 && state.openness() > .95);
        for (int i = 16; i < 19; i++) state.update(true, true, true, false, 0, 40.0);
        assertEquals(1.0, state.openness());
        assertEquals(1.0, state.contentReveal());
    }

    @Test
    void placeholderCanOpenImmediatelyButCannotSpendHoldBeforePlaybackIsReady() {
        MusicCardInteraction state = new MusicCardInteraction();
        state.startTrackNotice(MusicCardInteraction.TRACK_NOTICE_HOLD_MILLIS);
        for (int i = 0; i < 30; i++) {
            state.update(true, false, true, false, 0, 40.0, false);
            state.recordPresentation(state.eventRevision(), true, 1_000L + i * 40L);
        }
        assertEquals(1.0, state.openness());
        assertEquals(0L, state.visibleHoldMillis(), "unready preview must not consume actual track exposure");
        assertTrue(state.eventLocked());
        present(state, false, 0, 76, 10_000L);
        assertEquals(3_000L, state.visibleHoldMillis());
        state.update(true, false, true, false, 0, 16.0, true);
        assertFalse(state.eventLocked());
    }

    @Test
    void closingGeometryFinishesOnceAtSixHundredEightyMillisWithoutALaterTwitch() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        state.collapse();
        for (int i = 0; i < 67; i++) state.update(true, true, true, false, 0, 10.0);
        assertTrue(state.animationActive());
        state.update(true, true, true, false, 0, 10.0);
        assertEquals(0.0, state.openness());
        assertFalse(state.animationActive(), "there must not be another rebound clock after the geometry lands");
        MusicCardMotion.Frame settled = MusicCardMotion.frame(0.0, 400, 8, 176, 26, 46, 14, 1.0);
        for (int i = 0; i < 30; i++) {
            state.update(true, true, true, false, 0, 10.0);
            assertFalse(state.animationActive());
            assertEquals(settled, MusicCardMotion.frame(state.openness(), 400, 8, 176, 26, 46, 14, 1.0));
        }
    }

    @Test
    void chatCardClosingRetainsItsSurfaceUntilTheSlowerTailCompletes() {
        MusicCardInteraction state = new MusicCardInteraction();
        settle(state, true, false, 0);
        assertTrue(state.canInteract());
        state.collapse();
        for (int i = 0; i < 20; i++) {
            state.update(true, false, true, false, 0, 16.0);
            assertTrue(state.animationActive(), "closing tail must retain the transition surface");
            assertTrue(state.openness() > 0.0);
        }
        for (int i = 0; i < 30; i++) state.update(true, false, true, false, 0, 16.0);
        assertEquals(0.0, state.openness());
        assertFalse(state.animationActive());
    }
}
