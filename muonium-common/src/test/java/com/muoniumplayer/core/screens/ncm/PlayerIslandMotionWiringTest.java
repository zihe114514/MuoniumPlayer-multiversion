package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** Source wiring checks complement numeric motion tests without initializing the renderer. */
class PlayerIslandMotionWiringTest {
    @Test
    void transitionRetainsSharedBoundsAndPublishesCallerOwnedMotionSamples() throws IOException {
        String source = transition();
        assertTrue(source.contains("private static final double[] surfaceBoundsCache = new double[5]"));
        assertTrue(body(source, "public static double[] surfaceBounds()", "public static void sampleMotion(")
                .contains("return surfaceBoundsCache;"));
        String sample = body(source, "public static void sampleMotion(", "public static double lastFrameMillis()");
        assertTrue(sample.contains("MOVEMENT.sample(offsetMillis, out);"));
        assertFalse(sample.contains("new "));
        assertFalse(sample.contains("System.nanoTime"));
        assertFalse(sample.contains(".tick("));
        assertTrue(source.contains("surfaceBoundsCache[4] = FRAME.radius"));
        assertTrue(source.contains("cachedContentScale = FRAME.contentScale"));
    }

    @Test
    void closingCompletesAtOneStateEdgeAndDoesNotReplayAnImpactOnAbort() throws IOException {
        String source = transition();
        String tick = body(source, "public static void tick()", "private static void advanceSurfaceHandoff(");
        String closeEdge = body(tick, "else if (state == CLOSING && !MOVEMENT.isRunning())", "refreshFrameCache();");
        assertTrue(closeEdge.indexOf("state = IDLE;") < closeEdge.indexOf("beginPlayerAbsorbImpact();"));
        assertEquals(1, source.split("beginPlayerAbsorbImpact\\(\\)", -1).length - 1);
        String close = body(source, "public static void close()", "public static void abort()");
        assertTrue(close.contains("MOVEMENT.retarget(capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius, 0.0"));
        assertTrue(close.contains("closeDuration(), true, true"));
        String abort = body(source, "public static void abort()", "public static void tick()");
        assertTrue(abort.contains("if (state == IDLE && t == 0.0)"));
        assertTrue(abort.indexOf("return;") < abort.indexOf("cancelPlayerSurfaceTransition()"));
    }

    @Test
    void materialOnlyHandsOffAfterGeometrySettlesAndKeepsTheFrameDelta() throws IOException {
        String source = transition();
        String handoff = body(source, "private static void advanceSurfaceHandoff(", "public static double surfaceHandoff()");
        assertTrue(handoff.contains("if (state == OPEN)"));
        assertFalse(handoff.contains("System.nanoTime"));
        assertFalse(handoff.contains("if (t >= 1.0)"));
        assertTrue(source.contains("handoffDeltaMs = Math.max(0.0, deltaMs - remaining)"));
    }

    @Test
    void speedAndColorKeepBoundedLegacyEntryContractWithoutExtraGeometricPulses() throws IOException {
        String source = transition();
        assertTrue(source.contains("public static void open(float speedMultiplier)"));
        assertTrue(source.contains("return OPEN_MS / speed"));
        assertTrue(source.contains("return CLOSE_MS / speed"));
        assertTrue(source.contains("Math.max(.30f, Math.min(3.00f, value))"));
        assertFalse(source.contains("HudConfig.playerFadeSpeed"), "configuration must keep its existing screen entry consumer");
        assertTrue(source.contains("Math.max(0, Math.min(COLOR_STEPS, colorIndex))"));
        String color = body(source, "private static int mixColor(", "private static double sub(");
        assertTrue(color.contains("fraction = Math.max(0.0, Math.min(1.0, fraction))"));
        assertFalse(source.contains("capsuleHandoffMix"));
        assertFalse(source.contains("settlePulseRaw"));
        assertFalse(source.contains("double bulge"));
    }

    private static String transition() throws IOException {
        String source = Files.readString(ProjectPaths.sourceFile(
                "com/muoniumplayer/core/screens/ncm/PlayerIslandTransition.java"));
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\n]*", "");
    }

    private static String body(String source, String from, String until) {
        int start = source.indexOf(from);
        int end = source.indexOf(until, start + from.length());
        assertTrue(start >= 0 && end > start, "missing wiring anchor: " + from);
        return source.substring(start, end);
    }
}
