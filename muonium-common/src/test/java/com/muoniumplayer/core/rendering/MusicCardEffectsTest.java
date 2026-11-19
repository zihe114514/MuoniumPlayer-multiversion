package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardEffectsTest {
    @Test
    void rippleFinishesAfterCoverFlipAndCanRetriggerWithoutTextureReadiness() {
        MusicCardEffects effects = new MusicCardEffects();
        assertFalse(effects.ripple.active());
        effects.ripple.trigger();
        for (int i = 0; i < 11; i++) effects.tick(40.0, true);
        assertTrue(effects.ripple.active(), "clearing the old cover at 420ms must not stop the wave");
        assertTrue(effects.ripple.pulse() > .5);
        assertTrue(effects.ripple.expansion() > .97);
        for (int i = 0; i < 5; i++) effects.tick(40.0, true);
        assertFalse(effects.ripple.active());
        assertEquals(0.0, effects.ripple.pulse());
        effects.ripple.trigger();
        assertEquals(0.0, effects.ripple.progress());
        effects.tick(20.0, false);
        assertTrue(effects.ripple.pulse() > 0.0);
    }

    @Test
    void coverWaitHasFrameBasedDeadlineAndCanRestartForAYoungerTrack() {
        MusicCardEffects effects = new MusicCardEffects();
        effects.coverWait.begin();
        for (int i = 0; i < 19; i++) effects.tick(40.0, true);
        assertFalse(effects.coverWait.expired());
        effects.tick(40.0, true);
        assertTrue(effects.coverWait.expired(), "missing artwork cannot pin a track notice forever");
        effects.coverWait.begin();
        effects.tick(Double.NaN, true);
        assertFalse(effects.coverWait.expired());
    }

    @Test
    void tapsCompressImmediatelyMoveInOppositeDirectionsAndSettleElastically() {
        MusicCardEffects effects = new MusicCardEffects();
        effects.previous.trigger();
        effects.next.trigger();
        effects.playPause.trigger();
        assertEquals(.84, effects.next.scale(), 1e-12);
        assertEquals(3.4, effects.next.offsetX(), 1e-12);
        assertEquals(-3.4, effects.previous.offsetX(), 1e-12);
        assertEquals(0.0, effects.playPause.offsetX());
        boolean rebound = false;
        for (int i = 0; i < 56; i++) {
            effects.tick(10.0, true);
            assertEquals(-effects.previous.offsetX(), effects.next.offsetX(), 1e-12);
            assertEquals(effects.previous.scale(), effects.next.scale(), 1e-12);
            assertTrue(effects.next.scale() >= .84 && effects.next.scale() < 1.05);
            rebound |= effects.next.scale() > 1.0 && effects.next.offsetX() < 0.0;
        }
        assertTrue(rebound, "return must include a damped rebound, not a linear snap-back");
        assertEquals(1.0, effects.next.scale());
        assertEquals(0.0, effects.next.offsetX());
        effects.next.trigger();
        effects.tick(60.0, true);
        effects.next.trigger();
        assertEquals(.84, effects.next.scale(), 1e-12, "rapid clicks restart bounded feedback immediately");
    }

    @Test
    void animationUsesFiniteBoundedFrameDeltaAndIsIndependentOfFramePartition() {
        MusicCardEffects a = new MusicCardEffects(), b = new MusicCardEffects();
        a.next.trigger();
        b.next.trigger();
        a.ripple.trigger();
        b.ripple.trigger();
        for (int i = 0; i < 12; i++) a.tick(20.0, true);
        for (int i = 0; i < 24; i++) b.tick(10.0, true);
        assertEquals(a.next.amount(), b.next.amount(), 1e-12);
        assertEquals(a.ripple.progress(), b.ripple.progress(), 1e-12);
        double amount = a.next.amount(), progress = a.ripple.progress();
        a.tick(Double.NaN, true);
        a.tick(Double.POSITIVE_INFINITY, true);
        a.tick(-20.0, true);
        assertEquals(amount, a.next.amount());
        assertEquals(progress, a.ripple.progress());
        assertEquals(40.0, MusicCardEffects.frameDelta(5000.0));
    }

    @Test
    void actualPlayPauseResultDrivesMorphAndRapidReversalIsContinuous() {
        MusicCardEffects.Morph morph = new MusicCardEffects.Morph();
        morph.tick(16.0, true);
        assertEquals(1.0, morph.pauseAmount());
        morph.accept(false, false);
        morph.tick(16.0, true);
        assertEquals(1.0, morph.pauseAmount(), "unavailable room command must not pretend to pause");
        morph.accept(true, false);
        assertEquals(1.0, morph.pauseAmount(), "state change does not switch glyphs abruptly");
        morph.tick(40.0, true);
        double halfway = morph.pauseAmount();
        assertTrue(halfway > 0.0 && halfway < 1.0, "successful pause must override a lagging playing snapshot");
        morph.accept(true, true);
        assertEquals(halfway, morph.pauseAmount(), "rapid resume must start at the currently displayed contour");
        morph.tick(20.0, false);
        assertTrue(morph.pauseAmount() > halfway);
        morph.tick(20.0, true);
        for (int i = 0; i < 40; i++) morph.tick(20.0, true);
        assertEquals(1.0, morph.pauseAmount());
        morph.accept(true, false);
        for (int i = 0; i < 40; i++) morph.tick(20.0, true);
        assertTrue(morph.pauseAmount() > .5, "real snapshot must correct an unacknowledged external result");
    }

    @Test
    void playPauseOpticalOffsetCentersTheFilledAreaAtEveryPhaseAndLeavesPauseBarsUnshifted() {
        double previousOffset = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 100; i++) {
            double p = i / 100.0;
            double[] points = MusicCardEffects.vertices(MusicCardEffects.Icon.PLAY_PAUSE, p);
            double offset = MusicCardEffects.opticalOffsetX(MusicCardEffects.Icon.PLAY_PAUSE, p);
            assertTrue(offset >= 0.0 && offset <= previousOffset);
            double totalCross = 0.0, centerX = 0.0, centerY = 0.0;
            for (int contour = 0; contour < 2; contour++) {
                int first = contour * 8;
                for (int j = 0; j < 4; j++) {
                    int current = first + j * 2, next = first + ((j + 1) % 4) * 2;
                    double x0 = points[current] + offset, y0 = points[current + 1];
                    double x1 = points[next] + offset, y1 = points[next + 1];
                    double cross = x0 * y1 - x1 * y0;
                    totalCross += cross;
                    centerX += (x0 + x1) * cross;
                    centerY += (y0 + y1) * cross;
                }
            }
            assertTrue(totalCross > .5, "centering must not be achieved by erasing the icon");
            assertEquals(0.0, centerX / (3.0 * totalCross), 1e-12);
            assertEquals(0.0, centerY / (3.0 * totalCross), 1e-12);
            previousOffset = offset;
        }
        assertEquals(0.0, MusicCardEffects.opticalOffsetX(MusicCardEffects.Icon.PLAY_PAUSE, 1.0));
        assertEquals(0.0, MusicCardEffects.opticalOffsetX(MusicCardEffects.Icon.PREVIOUS, .0));
        assertEquals(0.0, MusicCardEffects.opticalOffsetX(MusicCardEffects.Icon.NEXT, .5));
        assertTrue(Double.isFinite(MusicCardEffects.opticalOffsetX(MusicCardEffects.Icon.PLAY_PAUSE, Double.NaN)));
    }

    @Test
    void transportInkBoundsStayExactlyCenteredThroughoutMorphAndSkipIsMirrored() {
        double[] next = MusicCardEffects.vertices(MusicCardEffects.Icon.NEXT, 0.0);
        double[] previous = MusicCardEffects.vertices(MusicCardEffects.Icon.PREVIOUS, 0.0);
        for (int i = 0; i < next.length; i += 2) {
            assertEquals(-next[i], previous[i]);
            assertEquals(next[i + 1], previous[i + 1]);
        }
        for (int i = 0; i <= 100; i++) {
            for (MusicCardEffects.Icon icon : MusicCardEffects.Icon.values()) {
                double[] points = MusicCardEffects.vertices(icon, i / 100.0);
                double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                for (int p = 0; p < points.length; p += 2) {
                    assertTrue(Double.isFinite(points[p]) && Double.isFinite(points[p + 1]));
                    minX = Math.min(minX, points[p]); maxX = Math.max(maxX, points[p]);
                    minY = Math.min(minY, points[p + 1]); maxY = Math.max(maxY, points[p + 1]);
                }
                assertEquals(0.0, minX + maxX, 1e-12);
                assertEquals(0.0, minY + maxY, 1e-12);
                assertEquals(1.0, maxY - minY, 1e-12);
            }
        }
        double[] mid = MusicCardEffects.vertices(MusicCardEffects.Icon.PLAY_PAUSE, .5);
        double[] play = MusicCardEffects.vertices(MusicCardEffects.Icon.PLAY_PAUSE, 0.0);
        double[] pause = MusicCardEffects.vertices(MusicCardEffects.Icon.PLAY_PAUSE, 1.0);
        for (int i = 0; i < mid.length; i++) assertEquals((play[i] + pause[i]) * .5, mid[i], 1e-12);
    }
}
