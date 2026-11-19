package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Numeric Java 17 tests; no client, native renderer, GL context or wall-clock sleeps. */
class PlayerIslandMotionTest {
    private static final double EPS = 1e-7;
    private static final double PANEL_X = 140.0, PANEL_Y = 90.0, PANEL_W = 720.0, PANEL_H = 460.0;
    private static final double PILL_X = 477.0, PILL_Y = 8.0, PILL_W = 46.0, PILL_H = 14.0;

    @Test
    void firstVisibleFrameIsZeroEvenAtNanoTimeZeroAndAfterReset() {
        PlayerIslandMotion.FrameClock clock = new PlayerIslandMotion.FrameClock();
        assertEquals(0.0, clock.step(0L, 880.0 / 3.0));
        assertEquals(8.0, clock.step(8_000_000L, 880.0 / 3.0));
        clock.reset();
        assertEquals(0.0, clock.step(50_000_000_000L, 650.0 / 3.0));
        assertEquals(8.0, clock.step(50_008_000_000L, 650.0 / 3.0));

        PlayerIslandMotion motion = opening(3.0);
        PlayerIslandMotion.Sample first = sample(motion);
        assertEquals(0.0, motion.tick(50_000_000_000L));
        sameState(first, sample(motion));
    }

    @Test
    void ordinaryThirtyHzAndUpToEightyMillisUseRealTimeNotTwelveSteps() {
        for (double duration : new double[]{880.0 / .3, 880.0, 880.0 / 3.0, 650.0 / 3.0, 88.0}) {
            PlayerIslandMotion.FrameClock clock = new PlayerIslandMotion.FrameClock();
            clock.step(0L, duration);
            assertEquals(1000.0 / 30.0, clock.step(33_333_333L, duration), 1e-6);
            assertEquals(80.0, clock.step(113_333_333L, duration));
            assertEquals(0.0, clock.step(113_000_000L, duration));
            assertEquals(8.0, clock.step(121_333_333L, duration));
        }
    }

    @Test
    void configuredOpenAndCloseHaveTheSameTimelineAtThirtySixtyAndOneTwentyHz() {
        for (double speed : new double[]{.3, 1.0, 3.0}) {
            double openDuration = PlayerIslandMotion.OPEN_MILLIS / speed;
            double closeDuration = PlayerIslandMotion.CLOSE_MILLIS / speed;
            for (int hz : new int[]{30, 60, 120}) {
                assertDuration(opening(speed), hz, openDuration, true);
                assertDuration(closing(speed), hz, closeDuration, false);
            }
            PlayerIslandMotion.Sample reference = at(opening(speed), 30, openDuration * .57);
            for (int hz : new int[]{60, 120}) sameState(reference, at(opening(speed), hz, openDuration * .57));
            reference = at(closing(speed), 30, closeDuration * .57);
            for (int hz : new int[]{60, 120}) sameState(reference, at(closing(speed), hz, closeDuration * .57));
        }
    }

    @Test
    void interruptedFramesDiscardExcessWithoutJumpingOrRetainingDebt() {
        for (double speed : new double[]{.3, 1.0, 3.0}) {
            PlayerIslandMotion motion = opening(speed);
            motion.tick(0L);
            double delta = motion.tick(5_000_000_000L);
            assertEquals(PlayerIslandMotion.NEIGHBOR_MILLIS, delta, EPS);
            assertTrue(motion.isRunning());
            PlayerIslandMotion.Sample resumed = sample(motion);
            assertTrue(resumed.width < PANEL_W - 1.0);
            assertEquals(8.0, motion.tick(5_008_000_000L), EPS);
            assertEquals(delta + 8.0, sample(motion).timeMillis, EPS);
            double previous = sample(motion).timeMillis;
            assertEquals(8.0, motion.tick(5_016_000_000L), EPS);
            assertEquals(previous + 8.0, sample(motion).timeMillis, EPS);
            long now = 5_016_000_000L;
            for (int frame = 0; motion.isRunning() && frame < 500; frame++) {
                now += 16_000_000L;
                motion.tick(now);
            }
            assertFalse(motion.isRunning(), "stalls must not create an unbounded catch-up backlog");
            assertOpen(sample(motion));
        }
        PlayerIslandMotion fastestClose = closing(3.0);
        fastestClose.tick(0L);
        fastestClose.tick(3_000_000_000L);
        assertTrue(fastestClose.isRunning());
        assertTrue(sample(fastestClose).width > PILL_W);
    }

    @Test
    void repeatedStallsStillFinishInBoundedVisibleSteps() {
        PlayerIslandMotion motion = closing(3.0);
        motion.tick(0L);
        int frames = 0;
        while (motion.isRunning() && frames < 30) motion.tick(++frames * 3_000_000_000L);
        assertEquals(7, frames, "there is no mandatory twelve-step close");
        assertPill(sample(motion));
    }

    @Test
    void openingAndClosingNaturallyDecelerateWithoutExtraBulgeOrCompression() {
        for (boolean closing : new boolean[]{false, true}) {
            PlayerIslandMotion motion = closing ? closing(3.0) : opening(3.0);
            double duration = (closing ? PlayerIslandMotion.CLOSE_MILLIS : PlayerIslandMotion.OPEN_MILLIS) / 3.0;
            motion.tick(0L);
            double priorWidth = closing ? PANEL_W : PILL_W;
            double priorHeight = closing ? PANEL_H : PILL_H;
            double peakVelocity = 0.0, lateVelocity = 0.0;
            for (int index = 1; index <= 200; index++) {
                double time = duration * index / 200.0;
                motion.tick(nanos(time));
                PlayerIslandMotion.Sample out = sample(motion);
                legal(out);
                assertTrue(out.width >= PILL_W - EPS && out.width <= PANEL_W + EPS);
                assertTrue(out.height >= PILL_H - EPS && out.height <= PANEL_H + EPS);
                assertTrue(closing ? out.width <= priorWidth + EPS : out.width >= priorWidth - EPS);
                assertTrue(closing ? out.height <= priorHeight + EPS : out.height >= priorHeight - EPS);
                priorWidth = out.width;
                priorHeight = out.height;
                peakVelocity = Math.max(peakVelocity, Math.abs(out.velocityWidth));
                if (index == 180) lateVelocity = Math.abs(out.velocityWidth);
            }
            assertTrue(lateVelocity < peakVelocity * .03, "the tail must decelerate, not hard-stop at full speed");
            assertEquals(0.0, sample(motion).velocityWidth);
        }
    }

    @Test
    void reversalInheritsGeometryAlphaAndFullContentTransformVelocity() {
        PlayerIslandMotion motion = opening(3.0);
        at(motion, 120, 55.0);
        PlayerIslandMotion.Sample before = sample(motion);
        assertTrue(before.velocityWidth > 0.0);
        motion.retarget(PILL_X, PILL_Y, PILL_W, PILL_H, 7.0, 0.0,
                PlayerIslandMotion.CLOSE_MILLIS / 3.0, true, true);
        sameState(before, sample(motion));
        assertEquals(0.0, sample(motion).timeMillis);
        motion.tick(20_000_000_000L);
        sameState(before, sample(motion));
        motion.tick(20_000_000_100L);
        PlayerIslandMotion.Sample justAfter = sample(motion);
        assertEquals(before.velocityWidth, justAfter.velocityWidth, 2.0);
        assertEquals(before.contentScaleVelocity, justAfter.contentScaleVelocity, .01);
        assertEquals(before.contentTranslateVelocityX, justAfter.contentTranslateVelocityX, 5.0);

        for (int index = 1; index <= 8; index++) motion.tick(20_000_010_000L + index * 8_000_000L);
        PlayerIslandMotion.Sample returning = sample(motion);
        assertTrue(returning.velocityWidth < 0.0);
        motion.retarget(PANEL_X, PANEL_Y, PANEL_W, PANEL_H, 18.0, 1.0,
                PlayerIslandMotion.OPEN_MILLIS / 3.0, false, true);
        sameState(returning, sample(motion));
        motion.tick(30_000_000_000L);
        motion.tick(30_000_000_100L);
        assertEquals(returning.velocityWidth, sample(motion).velocityWidth, 2.0);
    }

    @Test
    void resizeRetargetKeepsGeometryAndClockAndEndsAtTheNewTarget() {
        PlayerIslandMotion motion = opening(3.0);
        at(motion, 60, 70.0);
        PlayerIslandMotion.Sample before = sample(motion);
        motion.setPanel(80.0, 42.0, 850.0, 540.0);
        motion.retarget(80.0, 42.0, 850.0, 540.0, 22.0, 1.0, motion.remainingMillis(), false, false);
        PlayerIslandMotion.Sample resized = sample(motion);
        sameGeometry(before, resized);
        assertEquals(before.contentAlpha, resized.contentAlpha);
        motion.tick(nanos(78.0));
        assertEquals(8.0, motion.lastFrameMillis(), EPS);
        assertNotEquals(before.width, sample(motion).width);
        for (int index = 1; index <= 100; index++) motion.tick(nanos(78.0 + index * 8.0));
        assertFalse(motion.isRunning());
        PlayerIslandMotion.Sample end = sample(motion);
        assertEquals(80.0, end.x);
        assertEquals(42.0, end.y);
        assertEquals(850.0, end.width);
        assertEquals(540.0, end.height);
        assertEquals(22.0, end.radius);
        assertEquals(1.0, end.contentScale);
        assertEquals(0.0, end.contentTranslateX);
        assertEquals(0.0, end.contentTranslateY);
    }

    @Test
    void neighborSamplesAreAnalyticSideEffectFreeAndNeverCrossARetargetBoundary() {
        PlayerIslandMotion motion = opening(3.0);
        at(motion, 120, 55.0);
        PlayerIslandMotion.Sample current = sample(motion);
        PlayerIslandMotion.Sample past = new PlayerIslandMotion.Sample();
        PlayerIslandMotion.Sample future = new PlayerIslandMotion.Sample();
        motion.sample(-5.0, past);
        motion.sample(5.0, future);
        sameState(at(opening(3.0), 60, 50.0), past);
        sameState(at(opening(3.0), 30, 60.0), future);
        sameState(current, sample(motion));
        assertTrue(motion.isRunning());
        long oldSegment = current.segment;
        motion.retarget(PILL_X, PILL_Y, PILL_W, PILL_H, 7.0, 0.0, 650.0 / 3.0, true, true);
        motion.sample(-1000.0, past);
        assertTrue(past.segment > oldSegment);
        assertEquals(0.0, past.offsetMillis);
        sameState(current, past);
        motion.sample(1000.0, future);
        assertEquals(PlayerIslandMotion.NEIGHBOR_MILLIS, future.offsetMillis, EPS);
        motion.sample(Double.NaN, past);
        sameState(current, past);
    }

    @Test
    void exposedVelocitiesMatchNearbyPositionAndAffineMappingDerivatives() {
        PlayerIslandMotion motion = opening(3.0);
        at(motion, 120, 120.0);
        PlayerIslandMotion.Sample current = sample(motion);
        PlayerIslandMotion.Sample left = new PlayerIslandMotion.Sample();
        PlayerIslandMotion.Sample right = new PlayerIslandMotion.Sample();
        motion.sample(-.01, left);
        motion.sample(.01, right);
        double seconds = .00002;
        assertEquals(current.velocityX, (right.x - left.x) / seconds, .001);
        assertEquals(current.velocityHeight, (right.height - left.height) / seconds, .001);
        assertEquals(current.contentAlphaVelocity, (right.contentAlpha - left.contentAlpha) / seconds, .001);
        assertEquals(current.contentScaleVelocity, (right.contentScale - left.contentScale) / seconds, .001);
        assertEquals(current.contentTranslateVelocityX, (right.contentTranslateX - left.contentTranslateX) / seconds, .001);
        assertEquals(current.contentTranslateVelocityY, (right.contentTranslateY - left.contentTranslateY) / seconds, .001);
        assertEquals(current.surfaceCenterX,
                current.contentScale * current.targetCenterX + current.contentTranslateX, EPS);
        assertEquals(current.surfaceCenterY,
                current.contentScale * current.targetCenterY + current.contentTranslateY, EPS);
    }

    @Test
    void tailHasZeroTerminalVelocityAndExactLegalCapsuleWithoutPermanentResidue() {
        PlayerIslandMotion motion = closing(3.0);
        double duration = PlayerIslandMotion.CLOSE_MILLIS / 3.0;
        at(motion, 120, duration - .01);
        PlayerIslandMotion.Sample near = sample(motion);
        assertTrue(Math.abs(near.velocityWidth) < .02);
        motion.tick(nanos(duration));
        assertPill(sample(motion));
        assertFalse(motion.isRunning());
        motion.tick(nanos(duration + 5_000.0));
        assertPill(sample(motion));

        motion.snap(0.0, 0.0, 2.0, 1.0, 900.0, 2.0);
        legal(sample(motion));
        assertEquals(.5, sample(motion).radius);
        motion.retarget(1.0, 2.0, 4.0, 3.0, 100.0, -1.0, 200.0, true, true);
        motion.tick(0L);
        for (int index = 1; index <= 25; index++) {
            motion.tick(index * 8_000_000L);
            legal(sample(motion));
        }
        assertEquals(1.5, sample(motion).radius);
        assertEquals(0.0, sample(motion).contentAlpha);
    }

    @Test
    void rapidAlternatingTargetsRemainFiniteWithLegalAlphaScaleAndRadius() {
        PlayerIslandMotion motion = opening(3.0);
        motion.tick(0L);
        long now = 0L;
        for (int index = 0; index < 250; index++) {
            boolean close = index % 2 == 0;
            motion.retarget(close ? PILL_X : PANEL_X, close ? PILL_Y : PANEL_Y,
                    close ? PILL_W : PANEL_W, close ? PILL_H : PANEL_H,
                    close ? 7.0 : 18.0, close ? 0.0 : 1.0, close ? 650.0 / 3.0 : 880.0 / 3.0,
                    close, false);
            motion.tick(now += 8_000_000L);
            legal(sample(motion));
            PlayerIslandMotion.Sample neighbor = new PlayerIslandMotion.Sample();
            motion.sample(33.0, neighbor);
            legal(neighbor);
        }
    }

    private static PlayerIslandMotion opening(double speed) {
        PlayerIslandMotion motion = new PlayerIslandMotion();
        motion.setPanel(PANEL_X, PANEL_Y, PANEL_W, PANEL_H);
        motion.snap(PILL_X, PILL_Y, PILL_W, PILL_H, 7.0, 0.0);
        motion.retarget(PANEL_X, PANEL_Y, PANEL_W, PANEL_H, 18.0, 1.0,
                PlayerIslandMotion.OPEN_MILLIS / speed, false, true);
        return motion;
    }

    private static PlayerIslandMotion closing(double speed) {
        PlayerIslandMotion motion = new PlayerIslandMotion();
        motion.setPanel(PANEL_X, PANEL_Y, PANEL_W, PANEL_H);
        motion.snap(PANEL_X, PANEL_Y, PANEL_W, PANEL_H, 18.0, 1.0);
        motion.retarget(PILL_X, PILL_Y, PILL_W, PILL_H, 7.0, 0.0,
                PlayerIslandMotion.CLOSE_MILLIS / speed, true, true);
        return motion;
    }

    private static PlayerIslandMotion.Sample at(PlayerIslandMotion motion, int hz, double millis) {
        motion.tick(0L);
        for (int index = 1; index * 1000.0 / hz < millis; index++) motion.tick(nanos(index * 1000.0 / hz));
        motion.tick(nanos(millis));
        return sample(motion);
    }

    private static void assertDuration(PlayerIslandMotion motion, int hz, double duration, boolean open) {
        motion.tick(0L);
        int frames = 0;
        while (motion.isRunning() && frames < 1000) motion.tick(nanos(++frames * 1000.0 / hz));
        assertEquals((int) Math.ceil(duration * hz / 1000.0 - 1e-8), frames);
        if (open) assertOpen(sample(motion)); else assertPill(sample(motion));
    }

    private static long nanos(double millis) {
        return Math.round(millis * 1_000_000.0);
    }

    private static PlayerIslandMotion.Sample sample(PlayerIslandMotion motion) {
        PlayerIslandMotion.Sample out = new PlayerIslandMotion.Sample();
        motion.sample(0.0, out);
        return out;
    }

    private static void sameState(PlayerIslandMotion.Sample expected, PlayerIslandMotion.Sample actual) {
        sameGeometry(expected, actual);
        assertEquals(expected.contentAlpha, actual.contentAlpha, EPS);
        assertEquals(expected.contentAlphaVelocity, actual.contentAlphaVelocity, EPS);
        assertEquals(expected.contentScale, actual.contentScale, EPS);
        assertEquals(expected.contentScaleVelocity, actual.contentScaleVelocity, EPS);
        assertEquals(expected.contentTranslateX, actual.contentTranslateX, EPS);
        assertEquals(expected.contentTranslateY, actual.contentTranslateY, EPS);
        assertEquals(expected.contentTranslateVelocityX, actual.contentTranslateVelocityX, EPS);
        assertEquals(expected.contentTranslateVelocityY, actual.contentTranslateVelocityY, EPS);
    }

    private static void sameGeometry(PlayerIslandMotion.Sample expected, PlayerIslandMotion.Sample actual) {
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.width, actual.width, EPS);
        assertEquals(expected.height, actual.height, EPS);
        assertEquals(expected.radius, actual.radius, EPS);
        assertEquals(expected.velocityX, actual.velocityX, EPS);
        assertEquals(expected.velocityY, actual.velocityY, EPS);
        assertEquals(expected.velocityWidth, actual.velocityWidth, EPS);
        assertEquals(expected.velocityHeight, actual.velocityHeight, EPS);
        assertEquals(expected.velocityRadius, actual.velocityRadius, EPS);
    }

    private static void assertOpen(PlayerIslandMotion.Sample out) {
        legal(out);
        assertEquals(PANEL_X, out.x);
        assertEquals(PANEL_Y, out.y);
        assertEquals(PANEL_W, out.width);
        assertEquals(PANEL_H, out.height);
        assertEquals(18.0, out.radius);
        assertEquals(1.0, out.contentAlpha);
        assertEquals(1.0, out.contentScale);
        assertEquals(0.0, out.contentTranslateX);
        assertEquals(0.0, out.contentTranslateY);
        stopped(out);
    }

    private static void assertPill(PlayerIslandMotion.Sample out) {
        legal(out);
        assertEquals(PILL_X, out.x);
        assertEquals(PILL_Y, out.y);
        assertEquals(PILL_W, out.width);
        assertEquals(PILL_H, out.height);
        assertEquals(7.0, out.radius);
        assertEquals(0.0, out.contentAlpha);
        stopped(out);
    }

    private static void stopped(PlayerIslandMotion.Sample out) {
        assertEquals(0.0, out.velocityX);
        assertEquals(0.0, out.velocityY);
        assertEquals(0.0, out.velocityWidth);
        assertEquals(0.0, out.velocityHeight);
        assertEquals(0.0, out.velocityRadius);
        assertEquals(0.0, out.contentAlphaVelocity);
        assertEquals(0.0, out.contentScaleVelocity);
        assertEquals(0.0, out.contentTranslateVelocityX);
        assertEquals(0.0, out.contentTranslateVelocityY);
    }

    private static void legal(PlayerIslandMotion.Sample out) {
        for (double value : new double[]{out.x, out.y, out.width, out.height, out.radius,
                out.velocityX, out.velocityY, out.velocityWidth, out.velocityHeight, out.velocityRadius,
                out.contentAlpha, out.contentAlphaVelocity, out.contentScale, out.contentScaleVelocity,
                out.contentTranslateX, out.contentTranslateY, out.contentTranslateVelocityX, out.contentTranslateVelocityY}) {
            assertTrue(Double.isFinite(value));
        }
        assertTrue(out.width >= 1.0 && out.height >= 1.0);
        assertTrue(out.radius >= 0.0 && out.radius <= Math.min(out.width, out.height) * .5 + EPS);
        assertTrue(out.contentAlpha >= 0.0 && out.contentAlpha <= 1.0);
        assertTrue(out.contentScale > 0.0);
        assertTrue(out.contentScale * out.targetWidth >= out.width - EPS);
        assertTrue(out.contentScale * out.targetHeight >= out.height - EPS);
    }
}
