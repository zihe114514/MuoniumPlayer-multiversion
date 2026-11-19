package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Opening-only choreography and a frozen numeric contract for the accepted close. */
class PlayerOpeningMotionTest {
    @Test
    void freshOpeningSoftensTheInitialAccelerationAndUnfoldsWidthBeforeHeight() {
        PlayerIslandMotion motion = freshOpening();
        motion.tick(0L);
        motion.tick(16_666_667L);
        PlayerIslandMotion.Sample frame = sample(motion);
        double width = (frame.width - 60.0) / (720.0 - 60.0);
        double height = (frame.height - 16.0) / (460.0 - 16.0);
        assertTrue(width > height, "width should lead the vertical unfurl");
        double oldStep = 1.0 - (1.0 + 8.0 * frame.timeMillis / 300.0)
                * Math.exp(-8.0 * frame.timeMillis / 300.0);
        assertTrue(width < oldStep * .4, "the first movement should no longer burst to the old position");
        assertTrue(frame.contentAlpha > 0.0 && frame.contentAlpha < height);
        for (int i = 2; i <= 18; i++) motion.tick(Math.round(i * 300_000_000.0 / 18));
        frame = sample(motion);
        assertEquals(720.0, frame.width);
        assertEquals(460.0, frame.height);
        assertEquals(1.0, frame.contentAlpha);
        assertFalse(motion.isRunning());
    }

    @Test
    void normalCloseKeepsThePreviouslyAcceptedResponse() {
        PlayerIslandMotion motion = new PlayerIslandMotion();
        motion.setPanel(100, 80, 720, 460);
        motion.snap(100, 80, 720, 460, 18, 1);
        motion.retarget(430, 8, 60, 16, 8, 0, 300, true, true);
        motion.tick(0L);
        for (int millis : new int[]{20, 40, 80, 120, 180}) {
            motion.tick(millis * 1_000_000L);
            PlayerIslandMotion.Sample frame = sample(motion);
            double t = millis / 1000.0, omega = 8.0 / .3;
            assertEquals(60 + 660 * (1 + omega * t) * Math.exp(-omega * t), frame.width, 1e-8);
            assertEquals(-660 * omega * omega * t * Math.exp(-omega * t), frame.velocityWidth, 1e-8);
            double alphaOmega = 9.0 / .3;
            assertEquals((1 + alphaOmega * t) * Math.exp(-alphaOmega * t), frame.contentAlpha, 1e-8);
        }
    }

    @Test
    void coldSectionsDoNotWaitForHomeOrRestartTheWholeScreenFade() {
        PlayerOpeningReveal reveal = new PlayerOpeningReveal();
        reveal.reset(1);
        reveal.advance(3, 33);
        assertEquals(0f, reveal.baseAlpha());
        reveal.advance(3, 33);
        assertTrue(reveal.baseAlpha() > 0f);
        assertEquals(0f, reveal.pageAlpha());
        reveal.advance(4, 16);
        assertEquals(0f, reveal.controlsAlpha());
        reveal.advance(4, 16);
        assertTrue(reveal.controlsAlpha() > 0f);
        float visibleBase = reveal.baseAlpha();
        reveal.advance(0, 16);
        assertTrue(reveal.baseAlpha() >= visibleBase);
        assertEquals(0f, reveal.pageAlpha());
        for (int i = 0; i < 6; i++) reveal.advance(0, 16);
        assertTrue(reveal.isComplete());
    }

    @Test
    void prewarmedAndRetainedSectionsFollowTheMainMotionWithoutAnotherDelay() {
        PlayerOpeningReveal reveal = new PlayerOpeningReveal();
        reveal.reset(5);
        assertEquals(1f, reveal.baseAlpha());
        assertEquals(1f, reveal.controlsAlpha());
        assertEquals(0f, reveal.pageAlpha());
        reveal.reset(0);
        assertTrue(reveal.isComplete());
        reveal.advance(0, 0);
        assertTrue(reveal.isComplete());
    }

    @Test
    void revealAndSurfaceHandoffUseReadySectionsInsteadOfAnotherWallClock() throws Exception {
        String screen = java.nio.file.Files.readString(com.muoniumplayer.testsupport.ProjectPaths.sourceFile(
                "com/muoniumplayer/core/screens/ncm/NCMScreen.java"));
        assertFalse(screen.contains("contentReadyAtNanos"));
        assertTrue(screen.contains("this.openingReveal.advance(this.initialLayoutStage, PlayerIslandTransition.lastFrameMillis())"));
        assertTrue(screen.contains("this.controlsBar != null && (this.initialLayoutStage == 0 || this.initialLayoutStage >= 4)"));
        assertTrue(screen.contains("this.basePanel.getAlpha() * pageReveal"));
        String transition = java.nio.file.Files.readString(com.muoniumplayer.testsupport.ProjectPaths.sourceFile(
                "com/muoniumplayer/core/screens/ncm/PlayerIslandTransition.java"));
        int begin = transition.indexOf("private static void advanceSurfaceHandoff(");
        int end = transition.indexOf("public static double surfaceHandoff()", begin);
        String handoff = transition.substring(begin, end);
        assertTrue(handoff.contains("if (state == OPEN)"));
        assertTrue(handoff.contains("isOpeningSurfaceReady()"));
        assertFalse(handoff.contains("System.nanoTime()"));
    }

    private static PlayerIslandMotion freshOpening() {
        PlayerIslandMotion motion = new PlayerIslandMotion();
        motion.setPanel(100, 80, 720, 460);
        motion.snap(430, 8, 60, 16, 8, 0);
        motion.retarget(100, 80, 720, 460, 18, 1, 300, false, true);
        return motion;
    }

    private static PlayerIslandMotion.Sample sample(PlayerIslandMotion motion) {
        PlayerIslandMotion.Sample frame = new PlayerIslandMotion.Sample();
        motion.sample(0, frame);
        return frame;
    }
}
