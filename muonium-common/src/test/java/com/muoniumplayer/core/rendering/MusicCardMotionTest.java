package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardMotionTest {
    private static MusicCardMotion.Frame frame(double openness) {
        return MusicCardMotion.frame(openness, 400.0, 8.0, 180.0, 22.0, 46.0, 14.0, 1.0);
    }

    @Test
    void endpointsHaveOneOwnerAndExactCardDesignBounds() {
        MusicCardMotion.Frame closed = frame(0.0);
        assertEquals(180.0, closed.anchorWidth());
        assertEquals(22.0, closed.anchorHeight());
        assertEquals(1.0, closed.anchorAlpha());
        assertEquals(0.0, closed.alpha());
        assertEquals(0.0, closed.contentReveal());
        MusicCardMotion.Frame open = frame(1.0);
        assertEquals(0.0, open.anchorAlpha());
        assertEquals(1.0, open.alpha());
        assertEquals(MusicCardLayout.WIDTH, open.width());
        assertEquals(MusicCardLayout.HEIGHT, open.height());
        assertEquals(MusicCardLayout.RADIUS, open.radius());
        assertEquals(open.x(), open.contentX());
        assertEquals(open.y(), open.contentY(), 1e-8);
        assertEquals(1.0, open.contentScale());
        assertEquals(0.0, open.motion(), 1e-8);
        assertEquals(8.0 + 14.0 + 12.0, open.y(), 1e-8,
                "removing the camera dot must not move the settled card down");
    }

    @Test
    void lowerCardSeparatesProgressivelyAndKeepsContentUniformlyScaled() {
        MusicCardMotion.Frame early = frame(.10);
        MusicCardMotion.Frame middle = frame(.50);
        assertTrue(early.contentReveal() == 0.0 && early.anchorAlpha() > .5);
        assertTrue(middle.y() > middle.anchorY() + 10.0);
        assertTrue(middle.height() > early.height());
        assertEquals(early.anchorWidth(), middle.anchorWidth(),
                "anchor keeps capsule geometry while its opacity hands over to the card");
        assertTrue(middle.contentScale() > .94 && middle.contentScale() < 1.0);
        for (int i = 0; i <= 1000; i++) {
            MusicCardMotion.Frame current = frame(i / 1000.0);
            assertTrue(current.width() > 0 && current.height() > 0);
            assertTrue(current.radius() <= current.height() * .5);
            assertEquals(400.0, current.x() + current.width() * .5, 1e-8);
            assertTrue(current.alpha() >= 0.0 && current.alpha() <= 1.0);
            assertTrue(current.anchorAlpha() >= 0.0 && current.anchorAlpha() <= 1.0);
            assertEquals(19.0, current.anchorY() + current.anchorHeight() * .5, 1e-8,
                    "anchor squash must stay centered rather than pinning its top edge");
            if (i >= 420 && current.alpha() > .05 && current.anchorAlpha() > .05) {
                assertTrue(current.y() >= current.anchorY() + current.anchorHeight() + 6.0,
                        "fully detached phase must keep a clear gap at openness " + i / 1000.0);
            }
            if (i / 1000.0 <= MusicCardMotion.JOIN_OPENNESS && current.alpha() > .05) {
                assertEquals(19.0, current.y() + current.height() * .5, 1e-8,
                        "joined card must stay at the same island center");
            }
        }
    }

    @Test
    void islandDisappearsAfterSeparationAndCardReturnsToItsCenterBeforeReappearing() {
        MusicCardMotion.Frame early = frame(.10);
        MusicCardMotion.Frame settling = frame(.78);
        MusicCardMotion.Frame open = frame(1.0);
        MusicCardMotion.Frame gathered = frame(.04);
        MusicCardMotion.Frame closed = frame(0.0);
        assertTrue(early.alpha() > .5 && early.anchorAlpha() > .9);
        assertTrue(settling.anchorAlpha() < early.anchorAlpha());
        assertEquals(0.0, open.anchorAlpha());
        assertEquals(1.0, open.contentReveal());
        assertTrue(gathered.alpha() > 0.0, "card may fade only after it has merged at the island center");
        assertTrue(gathered.anchorAlpha() > .95);
        assertEquals(closed.anchorY() + closed.anchorHeight() * .5,
                closed.y() + closed.height() * .5, 1e-8, "closed card geometry must gather at the island center");
        assertEquals(0.0, MusicCardMotion.islandHandoff(0.0));
        assertEquals(1.0, MusicCardMotion.islandHandoff(1.0));
    }

    @Test
    void bothSurfacesHaveMatchingCapsuleBoundsAtContactWithoutACameraDot() {
        MusicCardMotion.Frame joined = frame(MusicCardMotion.JOIN_OPENNESS);
        assertEquals(joined.anchorX(), joined.x(), 1e-8);
        assertEquals(joined.anchorY(), joined.y(), 1e-8);
        assertEquals(joined.anchorWidth(), joined.width(), 1e-8);
        assertEquals(joined.anchorHeight(), joined.height(), 1e-8);
        assertEquals(180.0, joined.width());
        assertEquals(22.0, joined.height());
        assertEquals(1.0, joined.alpha());
        assertTrue(joined.anchorAlpha() > .5 && joined.anchorAlpha() < 1.0);
        MusicCardMotion.Frame incoming = frame(.22);
        assertTrue(incoming.y() + incoming.height() * .5 > joined.y() + joined.height() * .5);
        assertEquals(joined.anchorWidth(), incoming.anchorWidth(), 1e-8);
        MusicCardMotion.Frame restoring = frame(.09);
        assertEquals(joined.anchorWidth(), restoring.anchorWidth());
        assertEquals(joined.y() + joined.height() * .5, restoring.y() + restoring.height() * .5, 1e-8);
        assertEquals(0.0, restoring.contentReveal());
        for (double baseHeight : new double[]{14, 22, 26, 32}) {
            MusicCardMotion.Frame f = MusicCardMotion.frame(.08, 400, 8, 176, baseHeight,
                    46, baseHeight, 1.0);
            assertEquals(8 + baseHeight * .5, f.anchorY() + f.anchorHeight() * .5, 1e-8);
            assertEquals(8 + baseHeight * .5, f.y() + f.height() * .5, 1e-8);
        }
    }

    @Test
    void closingSilhouetteNeverShrinksBelowTheRestingCapsuleOrLeavesADetachedBlackDot() {
        for (double scale : new double[]{.5, 1.0, 2.0}) {
            for (int i = 0; i <= 1000; i++) {
                double p = i / 1000.0;
                MusicCardMotion.Frame current = MusicCardMotion.frame(p, 400, 8, 176, 26, 46, 14, scale);
                assertTrue(current.width() >= Math.min(176.0, MusicCardLayout.WIDTH * scale));
                assertTrue(current.height() >= 26.0);
                assertEquals(176.0, current.anchorWidth());
                assertEquals(26.0, current.anchorHeight());
                if (p >= .24) assertEquals(0.0, current.anchorAlpha(),
                        "detached card cannot leave a camera dot above it");
                if (p <= MusicCardMotion.JOIN_OPENNESS && current.alpha() > .05) {
                    assertEquals(176.0, current.width());
                    assertEquals(26.0, current.height());
                    assertEquals(current.anchorY(), current.y(), 1e-8);
                }
            }
        }
    }

    @Test
    void closeContentCanVacateTheUnchangedDesignBoundsWithoutSwitchingGeometry() {
        MusicCardMotion.Frame open = frame(.80);
        MusicCardMotion.Frame closing = MusicCardMotion.frame(.80, 400, 8, 180, 22,
                46, 14, 1.0, 0.0);
        assertEquals(open.x(), closing.x());
        assertEquals(open.y(), closing.y());
        assertEquals(open.width(), closing.width());
        assertEquals(open.height(), closing.height());
        assertEquals(open.anchorWidth(), closing.anchorWidth());
        assertEquals(0.0, closing.contentReveal());
        assertTrue(open.contentReveal() > .9);
        assertEquals(1.0, closing.alpha());
    }

    @Test
    void musicRowBaseWidthStillFollowsItsSettingAndViewport() {
        double narrow = DynamicIslandLayoutCalculator.musicRowWidth(160.0, 1.0, 800.0);
        double regular = DynamicIslandLayoutCalculator.musicRowWidth(250.0, 1.0, 800.0);
        double wide = DynamicIslandLayoutCalculator.musicRowWidth(400.0, 1.0, 800.0);
        assertEquals(176.0, regular, 1e-8);
        assertTrue(narrow < regular && regular < wide);
        assertTrue(DynamicIslandLayoutCalculator.musicRowWidth(720.0, 1.35, 220.0) <= (220.0 - 24.0) / 1.35);
        assertEquals(regular, DynamicIslandLayoutCalculator.musicRowWidth(250.0, 1.0, Double.NaN));
        assertEquals(regular, DynamicIslandLayoutCalculator.musicRowWidth(Double.NaN, Double.NaN, 800.0));
        assertEquals(26.0, MusicCapsuleCard.ROW_HEIGHT);
        assertEquals(18.0, MusicCapsuleCard.ROW_COVER_SIZE);
    }

    @Test
    void motionBlurTracksDirectionSpeedAndDropsHistoryAcrossLongFrameGaps() {
        MusicCardMotionBlur blur = new MusicCardMotionBlur();
        MusicCardMotion.Frame first = MusicCardMotion.frame(.31, 100, 80, 120, 70,
                46, 14, 1.0);
        assertFalse(blur.advance(first, 1_000_000_000L).active(), "first frame has no prior velocity");

        MusicCardMotion.Frame right = MusicCardMotion.frame(.31, 106, 80, 120, 70,
                46, 14, 1.0);
        MusicCardMotionBlur.Sample horizontal = blur.advance(right, 1_016_000_000L);
        assertTrue(horizontal.active());
        assertTrue(horizontal.directionX() > .90);
        assertEquals(0.0, horizontal.directionY(), .25);
        assertTrue(horizontal.trailDistance() > 0.0 && horizontal.trailOpacity() > 0.0);
        assertDirectionalKernel(1.0, 0.0, horizontal.speed(), true, false);

        MusicCardMotionBlur.Sample stopped = blur.advance(right, 1_032_000_000L);
        assertEquals(0.0, stopped.speed(), 1e-8);
        assertFalse(stopped.active());

        MusicCardMotion.Frame diagonal = MusicCardMotion.frame(.31, 114, 88, 120, 70,
                46, 14, 1.0);
        MusicCardMotionBlur.Sample diagonalSample = blur.advance(diagonal, 1_048_000_000L);
        assertTrue(diagonalSample.directionX() > .0 && diagonalSample.directionY() > .0);
        assertDirectionalKernel(diagonalSample.directionX(), diagonalSample.directionY(),
                diagonalSample.speed(), false, false);

        MusicCardMotionBlur.Sample afterGap = blur.advance(first, 1_200_000_000L);
        assertFalse(afterGap.active(), "a delayed frame must not inherit pre-pause motion");
        blur.reset();
        assertFalse(blur.advance(right, 1_216_000_000L).active());
    }

    @Test
    void motionBlurParametersStayBoundedAtTheFastestTransition() {
        MusicCardMotionBlur blur = new MusicCardMotionBlur();
        MusicCardMotion.Frame start = MusicCardMotion.frame(0.0, 200, 0, 40, 14,
                46, 14, 1.0);
        MusicCardMotion.Frame fast = MusicCardMotion.frame(.8, 260, 130, 300, 140,
                46, 14, 1.0);
        blur.advance(start, 2_000_000_000L);
        MusicCardMotionBlur.Sample sample = blur.advance(fast, 2_016_000_000L);
        assertTrue(sample.speed() <= 1.35);
        assertTrue(sample.sigmaLong() <= 5.2);
        assertTrue(sample.sigmaShort() <= 1.8);
        assertTrue(sample.trailDistance() <= 9.0);
        assertTrue(sample.blurMix() <= .42);
    }

    private static void assertDirectionalKernel(double directionX, double directionY, double speed,
                                                boolean horizontal, boolean vertical) {
        float[] kernel = MusicCardMotionBlur.directionalKernel(directionX, directionY, speed);
        assertEquals(49, kernel.length);
        double total = 0.0, varianceX = 0.0, varianceY = 0.0;
        for (int y = -3; y <= 3; y++) for (int x = -3; x <= 3; x++) {
            double weight = kernel[(y + 3) * 7 + x + 3];
            assertTrue(weight >= 0.0 && Double.isFinite(weight));
            total += weight;
            varianceX += x * x * weight;
            varianceY += y * y * weight;
        }
        assertEquals(1.0, total, 1e-5);
        if (horizontal) assertTrue(varianceX > varianceY * 1.35, "horizontal motion should stretch x");
        if (vertical) assertTrue(varianceY > varianceX * 1.35, "vertical motion should stretch y");
        if (!horizontal && !vertical) assertTrue(Math.abs(varianceX - varianceY) < varianceX * .35,
                "diagonal motion should spread on both axes");
    }

    @Test
    void mixedDirectionalExposureRetainsSharpInkAndOneNormalizedFilterPass() {
        float[] kernel = MusicCardMotionBlur.directionalKernel(1.0, .5, 1.0, .04);
        double total = 0.0;
        for (float weight : kernel) {
            assertTrue(weight >= 0.0);
            total += weight;
        }
        assertEquals(1.0, total, 1e-5);
        assertTrue(kernel[24] >= .96f, "small exposure is blended into the identity kernel, not another full redraw");
        float[] plain = MusicCardMotionBlur.directionalKernel(1.0, .5, 1.0);
        assertTrue(plain[24] < kernel[24]);
    }

    @Test
    void restoredMemoryKeepsTheIdleCapsuleRowWithoutChangingOrdinaryPauseTimeout() {
        assertTrue(MusicCapsuleCard.rowContentActive(true, 1L, 60_000L, true),
                "a matching startup resume point keeps its remembered track visible");
        assertFalse(MusicCapsuleCard.rowContentActive(false, 0L, 60_000L, true),
                "resume memory cannot create a row without a remembered track");
        assertFalse(MusicCapsuleCard.rowContentActive(true, 1L, 60_000L, false),
                "ordinary paused playback still expires after the existing hold window");
        assertTrue(MusicCapsuleCard.rowContentActive(true, 59_999L, 60_000L, false),
                "ordinary pause remains visible before its timeout");
    }

    @Test
    void musicRowNeverSwallowsDownloadCompletionOrNotice() {
        assertTrue(MusicCardMotion.showCapsuleRow(false, false, false, false, false, true));
        assertTrue(MusicCardMotion.showCapsuleRow(true, false, false, false, false, false));
        assertFalse(MusicCardMotion.showCapsuleRow(false, true, false, false, false, true));
        assertFalse(MusicCardMotion.showCapsuleRow(false, false, true, false, false, true));
        assertFalse(MusicCardMotion.showCapsuleRow(false, false, false, true, false, true));
        assertFalse(MusicCardMotion.showCapsuleRow(false, false, false, false, true, true));
        assertFalse(MusicCardMotion.showCapsuleRow(false, false, false, true, false, false, true),
                "downloads yield the row unless the border progress surface is explicitly active");
        assertTrue(MusicCardMotion.showCapsuleRow(false, false, true, true, false, false, true),
                "downloads keep the row while border progress is active");
        assertTrue(MusicCardMotion.showCapsuleRow(false, true, true, false, false, false, true),
                "model inference keeps the music row while its border carries progress");
    }

    @Test
    void capsuleNoticeAndRowPoliciesKeepTheSelectedStyleAndReserveReadableContent() {
        assertFalse(MusicCapsuleCard.showCapsuleRow(false, false, false, false,
                false, false, true, false));
        assertTrue(MusicCapsuleCard.showCapsuleRow(true, false, false, false,
                false, false, true, false));
        assertFalse(MusicCapsuleCard.showCapsuleRow(true, false, false, false,
                false, false, true, true), "transition surface is the sole owner during handoff");
        assertFalse(MusicCapsuleCard.showCapsuleRow(true, false, true, false,
                false, false, true, false), "ordinary notices cannot be painted as song metadata");
        assertTrue(MusicCapsuleCard.showCapsuleRow(true, false, true, false,
                false, false, true, false, true), "inference uses the song row as its progress surface");
        assertFalse(MusicCapsuleCard.showCapsuleRow(true, false, true, false,
                false, false, true, false, false));
        assertTrue(MusicCapsuleCard.capsuleNoticeWidth(140.0, 50.0, 1.0, 800.0) >= 176.0);
        assertTrue(MusicCapsuleCard.capsuleNoticeWidth(300.0, 400.0, 1.0, 400.0) <= 376.0);
    }

    @Test
    void longLyricsScrollInsideAStableViewportWithoutEllipsis() {
        double initial = MusicCapsuleCard.marqueeOffset(520.0, 175.0, 0.0);
        assertEquals(0.0, initial);
        assertEquals(0.0, MusicCapsuleCard.marqueeOffset(100.0, 175.0, 2_000.0));
        assertTrue(MusicCapsuleCard.marqueeOffset(520.0, 175.0, 1_600.0) > 0.0);
        assertEquals(0.0, MusicCapsuleCard.marqueeOffset(520.0, 175.0, Double.NaN));
    }
}
