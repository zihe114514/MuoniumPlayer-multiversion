package com.muoniumplayer.configskija.appearance;

import com.muoniumplayer.configskija.ui.Theme;
import com.muoniumplayer.configskija.ui.pages.AppearanceMotion;

import java.lang.reflect.*;
import java.util.*;

/** Optional read-only oracle against the original build's resolved Compose 1.13.0-alpha02 jars. */
public final class AppearanceComposeReferenceTest {
    public static void main(String[] args) throws Exception {
        Class<?> color = Class.forName("androidx.compose.ui.graphics.ColorKt");
        Method create = color.getMethod("Color", int.class),
                lerp = color.getMethod("lerp-jxsXWHM", long.class, long.class, float.class),
                argb = color.getMethod("toArgb-8_81llA", long.class);
        List<Integer> palette = new ArrayList<>();
        for (Theme t : Theme.BUILTIN)
            palette.addAll(
                    List.of(
                            t.page(),
                            t.sidebar(),
                            t.chip(),
                            t.card(),
                            t.component(),
                            t.popup(),
                            t.border(),
                            t.text(),
                            t.secondary(),
                            t.accentText(),
                            t.thumb(),
                            t.favorite()));
        palette = new ArrayList<>(new LinkedHashSet<>(palette));
        int checks = 0, maximum = 0;
        String worst = "";
        for (int a : palette)
            for (int b : palette)
                for (float f : new float[] {0, .1f, .25f, .5f, .75f, .9f, 1}) {
                    int expected =
                            (Integer)
                                    argb.invoke(
                                            null,
                                            (Long)
                                                    lerp.invoke(
                                                            null,
                                                            (Long) create.invoke(null, a),
                                                            (Long) create.invoke(null, b),
                                                            f));
                    int actual = Theme.mixOklab(a, b, f);
                    for (int shift = 0; shift < 32; shift += 8) {
                        int delta =
                                Math.abs(((expected >>> shift) & 255) - ((actual >>> shift) & 255));
                        if (delta > maximum) {
                            maximum = delta;
                            worst =
                                    Theme.hex(a)
                                            + " -> "
                                            + Theme.hex(b)
                                            + " @ "
                                            + f
                                            + " expected "
                                            + Theme.hex(expected)
                                            + " actual "
                                            + Theme.hex(actual);
                        }
                    }
                    checks++;
                }
        System.out.println(
                "Compose palette comparison: "
                        + checks
                        + " samples; max 8-bit channel delta="
                        + maximum
                        + "; "
                        + worst);
        if (maximum > 1)
            throw new AssertionError(
                    "Oklab differs by more than one color channel quantization step");
        Class<?> spec = Class.forName("androidx.compose.animation.core.FloatSpringSpec");
        Object reference = spec.getConstructor().newInstance();
        Method sample =
                spec.getMethod(
                        "getValueFromNanos", long.class, float.class, float.class, float.class);
        AppearanceMotion motion = new AppearanceMotion();
        motion.spring("x", 3, 0, true);
        motion.spring("x", 24, 0, true);
        double maximumSpringError = 0;
        for (int i = 1; i <= 120; i++) {
            double time = i / 240d,
                    expected =
                            ((Float) sample.invoke(reference, Math.round(time * 1e9), 3f, 24f, 0f))
                                    .doubleValue(),
                    actual = motion.spring("x", 24, time, true);
            maximumSpringError = Math.max(maximumSpringError, Math.abs(expected - actual));
        }
        System.out.println(
                "Compose spring comparison: 120 samples; max absolute dp error="
                        + maximumSpringError);
        if (maximumSpringError > 1e-4) throw new AssertionError("default critical spring mismatch");
    }
}
