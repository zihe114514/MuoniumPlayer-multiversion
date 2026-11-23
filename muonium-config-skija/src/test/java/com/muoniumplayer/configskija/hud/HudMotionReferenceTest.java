package com.muoniumplayer.configskija.ui.hud;

import java.lang.reflect.Method;

/** Optional test-only oracle against the original build's resolved animation-core jar. */
public final class HudMotionReferenceTest {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("androidx.compose.animation.core.FloatSpringSpec");
        Method
                value =
                        type.getMethod(
                                "getValueFromNanos",
                                long.class,
                                float.class,
                                float.class,
                                float.class),
                velocity =
                        type.getMethod(
                                "getVelocityFromNanos",
                                long.class,
                                float.class,
                                float.class,
                                float.class),
                duration =
                        type.getMethod("getDurationNanos", float.class, float.class, float.class);
        double maxValue = 0, maxVelocity = 0;
        int samples = 0;
        for (float stiffness : new float[] {1500, 400})
            for (float damping : new float[] {1, .5f})
                for (float[] tuple :
                        new float[][] {
                            {3, 24, 0}, {1, 1.15f, 0}, {0, 2400, 0}, {20, -100, 75}, {.4f, .1f, -3}
                        }) {
                    Object oracle =
                            type.getConstructor(float.class, float.class, float.class)
                                    .newInstance(damping, stiffness, .01f);
                    for (int i = 0; i <= 240; i++) {
                        double time = i / 240d;
                        long nanos = Math.round(time * 1e9);
                        HudMotion.Sample actual =
                                HudMotion.sample(
                                        tuple[0], tuple[1], tuple[2], time, stiffness, damping);
                        float
                                ev =
                                        (Float)
                                                value.invoke(
                                                        oracle, nanos, tuple[0], tuple[1],
                                                        tuple[2]),
                                evel =
                                        (Float)
                                                velocity.invoke(
                                                        oracle, nanos, tuple[0], tuple[1],
                                                        tuple[2]);
                        maxValue = Math.max(maxValue, Math.abs(ev - actual.value()));
                        maxVelocity = Math.max(maxVelocity, Math.abs(evel - actual.velocity()));
                        samples++;
                    }
                    for (float threshold : new float[] {.01f, .1f, 1}) {
                        Object durationOracle =
                                type.getConstructor(float.class, float.class, float.class)
                                        .newInstance(damping, stiffness, threshold);
                        long
                                expectedNanos =
                                        (Long)
                                                duration.invoke(
                                                        durationOracle,
                                                        tuple[0],
                                                        tuple[1],
                                                        tuple[2]),
                                actualNanos =
                                        Math.round(
                                                HudMotion.settlingSeconds(
                                                                tuple[0], tuple[1], tuple[2],
                                                                stiffness, damping, threshold)
                                                        * 1e9);
                        if (expectedNanos != actualNanos)
                            throw new AssertionError(
                                    "Spring end time mismatch "
                                            + stiffness
                                            + "/"
                                            + damping
                                            + " threshold="
                                            + threshold
                                            + " expected="
                                            + expectedNanos
                                            + " actual="
                                            + actualNanos);
                    }
                }
        System.out.println(
                "HUD Compose spring oracle: "
                        + samples
                        + " samples, max value error="
                        + maxValue
                        + ", max velocity error="
                        + maxVelocity);
        if (maxValue != 0 || maxVelocity != 0)
            throw new AssertionError("Resolved spring arithmetic mismatch");
        Object critical = type.getConstructor().newInstance();
        HudMotion motion = new HudMotion();
        motion.seed("retarget", 3, 0);
        motion.spring("retarget", 24, 0);
        double at = .075;
        float position = (Float) value.invoke(critical, 75_000_000L, 3f, 24f, 0f),
                vel = (Float) velocity.invoke(critical, 75_000_000L, 3f, 24f, 0f);
        motion.spring("retarget", -12, at);
        for (int i = 0; i <= 120; i++) {
            double time = i / 240d;
            float expected =
                    (Float) value.invoke(critical, Math.round(time * 1e9), position, -12f, vel);
            double actual = motion.spring("retarget", -12, at + time);
            if (expected != actual)
                throw new AssertionError(
                        "Retarget velocity lost at " + i + ": " + expected + " != " + actual);
        }
        System.out.println(
                "HUD spring retarget: 121 velocity-preserving samples matched; 60 finish times"
                    + " matched");
    }
}
