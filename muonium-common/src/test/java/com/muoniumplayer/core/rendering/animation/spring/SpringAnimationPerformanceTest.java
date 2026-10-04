package com.muoniumplayer.core.rendering.animation.spring;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.function.DoubleUnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class SpringAnimationPerformanceTest {
    private static Object field(SpringAnimation animation, String name) throws Exception {
        Field field = SpringAnimation.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(animation);
    }

    private static double value(SpringAnimation animation, String name, double t) throws Exception {
        return ((DoubleUnaryOperator) field(animation, name)).applyAsDouble(t);
    }

    @Test void unchangedTargetKeepsSolverAndElapsedTime() throws Exception {
        SpringAnimation animation = new SpringAnimation();
        animation.setTargetPosition(100.0);
        animation.update(0.05);
        Object solver = field(animation, "currentSolver");
        Object velocity = field(animation, "getV");
        for (int i = 0; i < 100; i++) animation.setTargetPosition(100.0);
        assertSame(solver, field(animation, "currentSolver"));
        assertSame(velocity, field(animation, "getV"));
        assertEquals(0.05, (double) field(animation, "currentTime"));
    }

    @Test void idleAndSettledUpdatesDoNotAllocateSolvers() throws Exception {
        SpringAnimation animation = new SpringAnimation(12.0);
        Object solver = field(animation, "currentSolver");
        Object zero = field(animation, "getV");
        for (int i = 0; i < 10_000; i++) {
            animation.setTargetPosition(12.0);
            animation.update(1.0 / 144.0);
            assertTrue(animation.isArrived());
        }
        assertSame(solver, field(animation, "currentSolver"));
        assertSame(zero, field(animation, "getV"));
        assertEquals(12.0, animation.getCurrentPosition());
    }

    @Test void analyticalDerivativesMatchIndependentFiniteDifferences() throws Exception {
        SpringParams[] cases = {
                new SpringParams(1, 10, 100, false), new SpringParams(1, 20, 100, false),
                new SpringParams(2, 40, 80, false), new SpringParams(1, 2, 70, true),
                new SpringParams(3, 9, 170, false)};
        for (SpringParams params : cases) {
            SpringAnimation animation = new SpringAnimation(params);
            animation.setTargetPosition(72.0);
            DoubleUnaryOperator position = (DoubleUnaryOperator) field(animation, "currentSolver");
            for (double t : new double[]{0, 0.031, 0.17, 0.35}) {
                double h = 1e-4;
                double velocity = (position.applyAsDouble(t + h) - position.applyAsDouble(t - h)) / (2 * h);
                double acceleration = (position.applyAsDouble(t + h) - 2 * position.applyAsDouble(t)
                        + position.applyAsDouble(t - h)) / (h * h);
                assertEquals(velocity, value(animation, "getV", t), 0.001);
                assertEquals(acceleration, value(animation, "getV2", t), 0.01);
            }
        }
    }

    @Test void trajectoryStillMatchesOriginalSpringEquation() {
        SpringAnimation animation = new SpringAnimation(new SpringParams(1, 10, 100, false));
        animation.setTargetPosition(80.0);
        double t = 0;
        for (int frame = 0; frame < 40; frame++) {
            double dt = 1.0 / 144.0;
            t += dt;
            animation.setTargetPosition(80.0);
            animation.update(dt);
            double frequency = Math.sqrt(300.0) / 2.0;
            double expected = 80 - (80 * Math.cos(t * frequency)
                    + 800.0 / Math.sqrt(300.0) * Math.sin(t * frequency)) * Math.exp(-5 * t);
            assertEquals(expected, animation.getCurrentPosition(), 1e-10);
        }
    }

    @Test void retargetingPreservesPositionAndVelocity() throws Exception {
        SpringAnimation animation = new SpringAnimation();
        animation.setTargetPosition(120.0);
        animation.update(0.12);
        double position = animation.getCurrentPosition();
        double velocity = value(animation, "getV", 0.12);
        animation.setTargetPosition(-40.0);
        assertEquals(position, animation.getCurrentPosition());
        assertEquals(position, value(animation, "currentSolver", 0), 1e-10);
        assertEquals(velocity, value(animation, "getV", 0), 1e-10);
    }

    @Test void idleSpringStillWakesForDelayedTargetsAndParameters() {
        SpringAnimation animation = new SpringAnimation(5.0);
        animation.setParams(new SpringParams(1, 20, 100, false), 0.05);
        animation.setTargetPosition(30.0, 0.10);
        assertFalse(animation.isArrived());
        animation.update(0.06);
        assertEquals(5.0, animation.getCurrentPosition());
        animation.update(0.05);
        animation.update(0.03);
        assertTrue(animation.getCurrentPosition() > 5.0);
        for (int i = 0; i < 400; i++) animation.update(0.01);
        assertTrue(animation.isArrived());
        assertEquals(30.0, animation.getCurrentPosition());
    }

    @Test void immediateTargetCancelsQueuedTargetEvenWhenCurrentTargetIsUnchanged() {
        SpringAnimation animation = new SpringAnimation(5.0);
        animation.setTargetPosition(50.0, 0.5);
        animation.setTargetPosition(5.0);
        for (int i = 0; i < 200; i++) animation.update(0.01);
        assertTrue(animation.isArrived());
        assertEquals(5.0, animation.getCurrentPosition());
    }

    @Test void changingParametersDuringMotionPreservesMomentum() throws Exception {
        SpringAnimation animation = new SpringAnimation();
        animation.setTargetPosition(90.0);
        animation.update(0.2);
        double velocity = value(animation, "getV", 0.2);
        animation.setParams(new SpringParams(2, 50, 80, false));
        assertEquals(velocity, value(animation, "getV", 0), 1e-10);
    }
}
