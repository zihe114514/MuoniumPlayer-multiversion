package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardLayoutTest {
    @Test
    void everyVisibleControlHasItsOwnHitRegionWithAndWithoutFavourite() {
        for (boolean like : new boolean[]{false, true}) {
            for (MusicCardLayout.Target target : new MusicCardLayout.Target[]{MusicCardLayout.Target.PREVIOUS,
                    MusicCardLayout.Target.PLAY_PAUSE, MusicCardLayout.Target.NEXT, MusicCardLayout.Target.MODE}) {
                assertEquals(target, MusicCardLayout.hit(MusicCardLayout.controlX(target),
                        MusicCardLayout.CONTROLS_Y, like, true));
            }
            assertEquals(like ? MusicCardLayout.Target.LIKE : MusicCardLayout.Target.PLAYER,
                    MusicCardLayout.hit(MusicCardLayout.controlX(MusicCardLayout.Target.LIKE),
                            MusicCardLayout.CONTROLS_Y, like, true));
        }
    }

    @Test
    void coverHeadlinePlatformAndUnusedCardSpaceOpenThePlayer() {
        assertEquals(MusicCardLayout.Target.PLAYER, MusicCardLayout.hit(40, 40, true, true));
        assertEquals(MusicCardLayout.Target.PLAYER, MusicCardLayout.hit(110, 26, true, true));
        assertEquals(MusicCardLayout.Target.PLAYER, MusicCardLayout.hit(306, 28, true, true));
        assertEquals(MusicCardLayout.Target.PLAYER, MusicCardLayout.hit(160, 64, true, true));
    }

    @Test
    void transparentRoundedCornersDoNotStealChatClicks() {
        assertEquals(MusicCardLayout.Target.NONE, MusicCardLayout.hit(0, 0, true, true));
        assertEquals(MusicCardLayout.Target.NONE, MusicCardLayout.hit(335, 147, true, true));
        assertEquals(MusicCardLayout.Target.NONE, MusicCardLayout.hit(-1, 84, true, true));
        assertEquals(MusicCardLayout.Target.NONE, MusicCardLayout.hit(Double.NaN, 84, true, true));
        assertTrue(MusicCardLayout.contains(MusicCardShape.extent(MusicCardLayout.WIDTH,
                MusicCardLayout.HEIGHT, MusicCardLayout.RADIUS), 0));
    }

    @Test
    void progressOnlySeeksWhenDurationIsKnownAndDragClampsAtTheEnds() {
        double midpoint = MusicCardLayout.WIDTH * .5;
        assertEquals(MusicCardLayout.Target.SEEK, MusicCardLayout.hit(midpoint, MusicCardLayout.PROGRESS_Y, true, true));
        assertEquals(MusicCardLayout.Target.PLAYER, MusicCardLayout.hit(midpoint, MusicCardLayout.PROGRESS_Y, true, false));
        assertEquals(.5, MusicCardLayout.seekFraction(midpoint), 1e-10);
        assertEquals(0.0, MusicCardLayout.seekFraction(-500));
        assertEquals(1.0, MusicCardLayout.seekFraction(5000));
        assertEquals(0.0, MusicCardLayout.seekFraction(Double.NaN));
    }

    @Test
    void hitTestingFollowsTheActualScaledAndAnimatedRectangle() {
        for (double sx : new double[]{.35, .6, 1, 1.35, 2}) {
            for (double sy : new double[]{.3, .7, 1, 1.8}) {
                double[] bounds = {45.5, 9.25, MusicCardLayout.WIDTH * sx, MusicCardLayout.HEIGHT * sy};
                double x = MusicCardLayout.controlX(MusicCardLayout.Target.NEXT);
                double[] point = MusicCardLayout.designPoint(bounds[0] + x * sx,
                        bounds[1] + MusicCardLayout.CONTROLS_Y * sy, bounds);
                assertNotNull(point);
                assertEquals(MusicCardLayout.Target.NEXT, MusicCardLayout.hit(point[0], point[1], true, true));
            }
        }
        assertNull(MusicCardLayout.designPoint(0, 0, new double[]{0, 0, 0, 10}));
    }
}
