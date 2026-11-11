package com.muoniumplayer.core.rendering.font;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TextGlowBloomFieldBenchmarkTest {
    @Test
    @EnabledIfSystemProperty(named = "muonium.test.bloomFieldBenchmark", matches = "true")
    void reuseReportsCompletedCpuTimeAndAllocationWithoutChangingPixels() {
        int width = 154, height = 189;
        float[] mask = new float[width * height];
        for (int y = 55; y < 135; y++) {
            for (int x = 54; x < 98; x++) {
                mask[y * width + x] = (x < 59 || x > 92 || y < 61 || y > 128) ? .35f : 1f;
            }
        }
        TextGlow.BloomGlowField field = TextGlow.prepareBloomGlowField(mask, width, height, 40f, 1.3);
        assertNotNull(field);
        int[][] expected = new int[8][];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = TextGlow.bakeBloomGlowPixels(mask, width, height, 40f, 1.3,
                    strength(i), color(i));
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        int rounds = 48;
        double[][] millis = new double[2][rounds];
        long[] allocations = new long[2];
        for (int mode = 0; mode < 2; mode++) {
            for (int round = -12; round < rounds; round++) {
                int index = Math.floorMod(round, expected.length);
                long bytes = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                int[] result = mode == 0
                        ? TextGlow.bakeBloomGlowPixels(mask, width, height, 40f, 1.3, strength(index), color(index))
                        : TextGlow.shadeBloomGlowPixels(field, strength(index), color(index));
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                long allocated = bean.getThreadAllocatedBytes(thread) - bytes;
                assertArrayEquals(expected[index], result);
                if (round >= 0) {
                    millis[mode][round] = elapsed;
                    allocations[mode] += allocated;
                }
            }
        }
        Arrays.sort(millis[0]);
        Arrays.sort(millis[1]);
        System.out.printf(Locale.ROOT,
                "[BloomField/bench] %dx%d color/strength variants | full median=%.3fms reuse median=%.3fms | Java allocation full=%d reuse=%d B/variant | retained=%d B%n",
                width, height, millis[0][rounds / 2], millis[1][rounds / 2],
                allocations[0] / rounds, allocations[1] / rounds, field.floatCount() * Float.BYTES);
    }

    private static double strength(int index) {
        return .1 + index * .1;
    }

    private static int color(int index) {
        return 0x408020 + index * 0x080808;
    }
}
