package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Numeric fixtures from Folia's program, render-hint and scene-transition formulas, in milliseconds. */
class SonnetSceneTimingTest {
    private static final double EPSILON = 1e-6;

    @Test
    void microLinesHaveA67MsVisualFloorAndNoPassOrExitTail() {
        long[] durations = {0, 20, 66, 67, 99};
        double[] ends = {1067, 1067, 1067, 1067, 1099};
        for (int i = 0; i < durations.length; i++) {
            LyricLine line = timedLine(1000, durations[i], "音");
            assertEquals(ends[i], SonnetProgram.visualEnd(line, 1000 + durations[i]), EPSILON,
                    "micro duration " + durations[i]);
        }
    }

    @Test
    void shortLinesUse30MsPassAndClampedFastExitIncludingThe100MsBoundary() {
        long[] durations = {100, 120, 150, 179};
        // 100/120 ms use a 30 ms exit; 150/179 ms use duration * .22.
        double[] ends = {1160, 1180, 1213, 1248.38};
        for (int i = 0; i < durations.length; i++) {
            LyricLine line = timedLine(1000, durations[i], "音");
            assertEquals(ends[i], SonnetProgram.visualEnd(line, 1000 + durations[i]), EPSILON,
                    "short duration " + durations[i]);
        }
    }

    @Test
    void normalLinesUse60MsPassAnd180To320MsExitIncludingThe180MsBoundary() {
        long[] durations = {180, 1000, 1500, 2000};
        double[] ends = {1420, 2240, 2830, 3380};
        for (int i = 0; i < durations.length; i++) {
            LyricLine line = timedLine(1000, durations[i], "音");
            assertEquals(ends[i], SonnetProgram.visualEnd(line, 1000 + durations[i]), EPSILON,
                    "normal duration " + durations[i]);
        }
        LyricLine plain = new LyricLine(1000, "没有逐字时间");
        plain.duration = 1000;
        assertEquals(2240, SonnetProgram.visualEnd(plain, 2000), EPSILON,
                "Without words the authored line end supplies the pass start");
    }

    @Test
    void visualTailFollowsTheLastWordAndDoesNotRewriteItsAuthoredTiming() {
        LyricLine early = new LyricLine(1000, "微光 夜色");
        early.duration = 1000;
        early.words.add(new LyricLine.Word("微光", 1000, 300));
        early.words.add(new LyricLine.Word("夜色", 1400, 200));
        assertEquals(2000, SonnetProgram.visualEnd(early, 2000), EPSILON,
                "An existing authored hold can already contain the normal pass and exit");

        LyricLine late = new LyricLine(1000, "微光 夜色");
        late.duration = 1000;
        late.words.add(new LyricLine.Word("微光", 1000, 300));
        late.words.add(new LyricLine.Word("夜色", 1900, 200));
        assertEquals(2340, SonnetProgram.visualEnd(late, 2000), EPSILON,
                "Last word ends at 2100, then 60 ms pass and 180 ms exit");
        assertEquals(1000, late.duration);
        assertEquals(1900, late.words.get(1).timestamp);
        assertEquals(200, late.words.get(1).duration);
    }

    @Test
    void finalContentEndAndVisualEndRemainSeparateWithoutExtendingSungGlyphs() {
        LyricLine line = timedLine(1000, 1000, "微光");
        SonnetProgram program = SonnetProgram.compile(List.of(line), "final-tail");
        SonnetProgram.Shot shot = program.shots.get(0);
        assertEquals(2000, shot.endMs, EPSILON);
        assertEquals(2240, shot.renderEndMs, EPSILON);
        assertEquals(2240, program.paragraphs.get(0).renderEndMs, EPSILON);
        assertEquals(2240, program.finalRenderEndMs(), EPSILON);
        assertEquals(2000, lastGlyphEnd(shot), EPSILON);
        assertEquals(1000, line.duration);
        assertEquals(1000, line.words.get(0).duration);
    }

    @Test
    void followingLineClipsVisualTailAndContentIndependentlyWithoutClippingGlyphTiming() {
        // The first case clips only the 240 ms visual tail; the second also clips sung content.
        long[] durations = {1000, 2500};
        long[] nextStarts = {2100, 2000};
        for (int i = 0; i < durations.length; i++) {
            SonnetProgram program = twoParagraphs("clipped-tail", durations[i], nextStarts[i]);
            SonnetProgram.Shot first = program.shots.get(0);
            assertEquals(2000, first.endMs, EPSILON, "legacy content/clipped semantics");
            assertEquals(nextStarts[i], first.renderEndMs, EPSILON,
                    "A visual tail must never extend into the following line");
            assertEquals(nextStarts[i], program.paragraphs.get(0).renderEndMs, EPSILON);
            assertEquals(1000 + durations[i], lastGlyphEnd(first), EPSILON,
                    "Clipping the scene does not rewrite authored glyph end times");
        }
    }

    @Test
    void renderTailsPreventAnAuthored1350MsGapFromCreatingASpuriousParagraph() {
        SonnetProgram program = SonnetProgram.compile(List.of(
                timedLine(1000, 1000, "微光"), timedLine(3350, 1000, "穿过"),
                timedLine(4450, 1000, "夜色"), timedLine(5550, 1000, "归途")), "gap-tail");
        // Visual ends are 2240, 4450, 5550, 6790 after clipping to subsequent starts.
        // Only the 1110 ms positive gap enters the median; threshold = 1110 * 2.5 = 2775.
        assertEquals(1, program.paragraphs.size());
        assertEquals(1, program.shots.size());
        assertArrayEquals(new int[]{0, 1, 2, 3}, program.shots.get(0).sourceLineIndices);
        assertEquals(6550, program.shots.get(0).endMs, EPSILON);
        assertEquals(6790, program.shots.get(0).renderEndMs, EPSILON);
    }

    @Test
    void visualEndRatherThanContentEndDeterminesTheSixSecondShotCap() {
        SonnetProgram program = SonnetProgram.compile(List.of(
                timedLine(1000, 3000, "微光"), timedLine(6000, 1000, "归途")), "shot-tail");
        // Content fits exactly in 6000 ms; the second line's 240 ms visual tail does not.
        assertEquals(1, program.paragraphs.size());
        assertEquals(2, program.shots.size());
        assertEquals(0, program.paragraphs.get(0).firstShot);
        assertEquals(2, program.paragraphs.get(0).shotCount);
        assertArrayEquals(new int[]{0}, program.shots.get(0).sourceLineIndices);
        assertArrayEquals(new int[]{1}, program.shots.get(1).sourceLineIndices);
        assertEquals(4000, program.shots.get(0).endMs, EPSILON);
        assertEquals(4380, program.shots.get(0).renderEndMs, EPSILON);
        assertEquals(7000, program.shots.get(1).endMs, EPSILON);
        assertEquals(7240, program.finalRenderEndMs(), EPSILON);
    }

    @Test
    void paragraphExitIsAnchoredToNextStartWith160To300MsClampsAnd200MsForZeroGap() {
        long[] nextStarts = {2380, 2640, 3240, 2240};
        double[] exitStarts = {2220, 2440, 2940, 2040};
        for (int i = 0; i < nextStarts.length; i++) {
            SonnetProgram program = twoParagraphs("paragraph-clamps", 1000, nextStarts[i]);
            SonnetProgram.Paragraph outgoing = program.paragraphs.get(0);
            assertEquals(0, outgoing.index);
            assertEquals(0, outgoing.firstShot);
            assertEquals(1, outgoing.shotCount);
            assertEquals(1000, outgoing.startMs, EPSILON);
            assertEquals(2240, outgoing.renderEndMs, EPSILON);
            assertEquals(exitStarts[i], outgoing.exitStartMs, EPSILON, "next start " + nextStarts[i]);
            assertEquals(nextStarts[i], outgoing.exitEndMs, EPSILON);
            assertEquals(1, program.shots.get(1).paragraphIndex);
            assertEquals(1, program.paragraphs.get(1).firstShot);
        }
    }

    @Test
    void longGapHoldsTheOldSceneUntilTheNextParagraphExitWindow() {
        SonnetProgram program = twoParagraphs("paragraph-timing", 1000, 10000);
        SonnetProgram.Paragraph outgoing = program.paragraphs.get(0);
        assertEquals(2240, outgoing.renderEndMs, EPSILON);
        assertEquals(9700, outgoing.exitStartMs, EPSILON);
        assertEquals(10000, outgoing.exitEndMs, EPSILON);
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        for (double time : new double[]{900, 2000, 2240, 2241, 5000, 9699.999}) {
            assertEquals(0, program.shotAt(time), "held scene at " + time);
            program.transitionAt(0, time, frame);
            assertIdle(frame);
        }
        program.transitionAt(0, 9850, frame);
        assertFrame(frame, 0.5, 7, 0, 0);
        program.transitionAt(0, 10000, frame);
        assertFrame(frame, 0, 14, 0, 0);
        assertEquals(1, program.shotAt(10000));
        program.transitionAt(1, 10000, frame);
        assertFrame(frame, 0.18, 14, 0, 0);
        program.transitionAt(1, 10150, frame);
        assertFrame(frame, 0.59, 7, 0, 0);
        program.transitionAt(1, 10301, frame);
        assertIdle(frame);
    }

    @Test
    void paragraphEnterInheritsPreviousKindAndUsesIncomingParagraphSeedForAllThreeEffects() {
        String[] seeds = {"paragraph-timing", "scene-timing", "scene-timing-0"};
        long[] incomingSeeds = {377506150L, 3459843332L, 2598457227L};
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        for (int kind = 0; kind < seeds.length; kind++) {
            SonnetProgram program = twoParagraphs(seeds[kind], 1000, 2640);
            SonnetProgram.Paragraph incoming = program.paragraphs.get(1);
            assertEquals(kind, program.paragraphs.get(0).transitionKind);
            assertEquals(-1, incoming.transitionKind, "The final paragraph has no outgoing transition");
            assertEquals(incomingSeeds[kind], Integer.toUnsignedLong(incoming.transitionSeed));
            program.transitionAt(incoming.firstShot, 2640, frame);
            assertEnterStart(frame, kind, incomingSeeds[kind]);
            program.transitionAt(incoming.firstShot, 2740, frame);
            assertMidpoint(frame, kind, false, incomingSeeds[kind]);
            program.transitionAt(incoming.firstShot, 2841, frame);
            assertIdle(frame);
        }
    }

    @Test
    void exitCannotStartBeforeItsParagraphButIncomingDurationStillHas160MsFloor() {
        SonnetProgram program = twoParagraphs("paragraph-timing", 20, 1020);
        SonnetProgram.Paragraph outgoing = program.paragraphs.get(0);
        assertEquals(1000, outgoing.exitStartMs, EPSILON);
        assertEquals(1020, outgoing.exitEndMs, EPSILON);
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        program.transitionAt(0, 1010, frame);
        assertFrame(frame, 0.5, 7, 0, 0);
        // The outgoing window is only 20 ms; entering is clamped back up to 160 ms.
        program.transitionAt(1, 1020, frame);
        assertFrame(frame, 0.18, 14, 0, 0);
        program.transitionAt(1, 1100, frame);
        assertFrame(frame, 0.59, 7, 0, 0);
        program.transitionAt(1, 1181, frame);
        assertIdle(frame);
    }

    @Test
    void intraParagraphBoundaryHasItsOwnKindAnd240MsWindowsIndependentOfParagraphExit() {
        SonnetProgram program = groupedProgram("scene-timing");
        SonnetProgram.Paragraph first = program.paragraphs.get(0);
        assertEquals(2, program.paragraphs.size());
        assertEquals(2, first.shotCount);
        assertArrayEquals(new int[]{0, 1, 2, 3}, program.shots.get(0).sourceLineIndices);
        assertArrayEquals(new int[]{4, 5}, program.shots.get(1).sourceLineIndices);
        assertEquals(1, first.transitionKind, "Paragraph uses monochrome glitch");
        assertEquals(2, SonnetProgram.boundaryKind(first.transitionSeed, 0), "Internal boundary uses fade");
        assertEquals(9700, first.exitStartMs, EPSILON);
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        program.transitionAt(0, 4759, frame);
        assertIdle(frame);
        program.transitionAt(0, 4880, frame);
        assertFrame(frame, 0.5, 0, 0, 0);
        assertEquals(1, program.shotAt(5000));
        program.transitionAt(1, 5000, frame);
        assertFrame(frame, 0.28, 0, 0, 0);
        program.transitionAt(1, 5120, frame);
        assertFrame(frame, 0.64, 0, 0, 0);
        program.transitionAt(1, 5241, frame);
        assertIdle(frame);
        program.transitionAt(1, 9850, frame);
        assertMidpoint(frame, 1, true, 2748048297L);
    }

    @Test
    void rapidFourLineBoundaryStillGets140MsExitAndEnterWindows() {
        SonnetProgram program = SonnetProgram.compile(microLines(1000, false), "scene-timing");
        assertEquals(1, program.paragraphs.size());
        assertEquals(2, program.shots.size());
        assertEquals(1080, program.shots.get(1).startMs, EPSILON);
        assertEquals(-1, program.paragraphs.get(0).transitionKind);
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        // 80 ms between shot starts would imply 14.4 ms without the 140 ms floor.
        program.transitionAt(0, 1010, frame); // (1010 - (1080 - 140)) / 140 = .5
        assertFrame(frame, 0.5, 0, 0, 0);
        program.transitionAt(1, 1080, frame);
        assertFrame(frame, 0.28, 0, 0, 0);
        program.transitionAt(1, 1150, frame);
        assertFrame(frame, 0.64, 0, 0, 0);
        program.transitionAt(1, 1221, frame);
        assertIdle(frame);
    }

    @Test
    void internalBoundaryUses18PercentWhenNeitherDurationClampApplies() {
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) lines.add(timedLine(1000 + i * 250L, 250, "音"));
        SonnetProgram program = SonnetProgram.compile(lines, "scene-timing");
        assertEquals(1, program.paragraphs.size());
        assertEquals(2, program.shots.size());
        assertEquals(2000, program.shots.get(1).startMs, EPSILON);
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        // Shot starts are 1000 ms apart: the internal window is 180 ms, not 140 or 240.
        program.transitionAt(0, 1819, frame);
        assertIdle(frame);
        program.transitionAt(0, 1910, frame);
        assertFrame(frame, 0.5, 0, 0, 0);
        program.transitionAt(1, 2090, frame);
        assertFrame(frame, 0.64, 0, 0, 0);
        program.transitionAt(1, 2181, frame);
        assertIdle(frame);
    }

    @Test
    void shotBoundaryWinsWhenItsExitOrEnterOverlapsTheParagraphEnter() {
        List<LyricLine> lines = new ArrayList<>();
        lines.add(timedLine(1000, 1000, "前奏"));
        lines.addAll(microLines(10000, true));
        SonnetProgram program = SonnetProgram.compile(lines, "scene-timing");
        SonnetProgram.Paragraph incoming = program.paragraphs.get(1);
        assertEquals(2, incoming.shotCount);
        assertEquals(1, program.paragraphs.get(0).transitionKind);
        assertEquals(0, SonnetProgram.boundaryKind(incoming.transitionSeed, 0));
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        // The 140 ms internal exit begins at 9940; paragraph enter would use glitch until 10300.
        program.transitionAt(incoming.firstShot, 10010, frame);
        assertFrame(frame, 0.5, 7, 0, 0);
        program.transitionAt(incoming.firstShot + 1, 10150, frame);
        assertFrame(frame, 0.59, 7, 0, 0);
        program.transitionAt(incoming.firstShot + 1, 10250, frame);
        assertEquals(0, frame.blur, EPSILON);
        assertTrue(frame.glitch > 0, "Paragraph entry resumes once the internal entry is finished");
        program.transitionAt(incoming.firstShot + 1, 10301, frame);
        assertIdle(frame);
    }

    @Test
    void boundaryKindsUseUnsigned32BitMixing() {
        int[] seeds = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x12345678};
        int[][] kinds = {{1, 1, 2}, {2, 2, 1}, {2, 0, 0}, {1, 0, 0}, {1, 0, 2}};
        for (int i = 0; i < seeds.length; i++) {
            for (int boundary = 0; boundary < 3; boundary++) {
                assertEquals(kinds[i][boundary], SonnetProgram.boundaryKind(seeds[i], boundary),
                        "seed " + seeds[i] + ", boundary " + boundary);
            }
        }
    }

    @Test
    void forwardReverseAndDirectSeeksReproduceTheSameFramesAndResetReusedOutput() {
        SonnetProgram program = groupedProgram("seek-timing");
        double[] times = {900, 1000, 4759, 4760, 4880, 4999.5, 5000, 5120, 5241,
                7000, 9000, 9700, 9850, 9999.5, 10000, 10150, 10301, 11240, 60000};
        Frame[] expected = new Frame[times.length];
        SonnetTextMotion.Transition scratch = new SonnetTextMotion.Transition();
        for (int i = 0; i < times.length; i++) expected[i] = sample(program, times[i], scratch);
        for (int i = times.length - 1; i >= 0; i--) {
            scratch.alpha = scratch.blur = scratch.glitch = scratch.seed = Double.NaN;
            assertEquals(expected[i], sample(program, times[i], scratch), "reverse seek " + times[i]);
        }
        for (int i = 0; i < times.length; i++) {
            sample(program, 60000, scratch);
            sample(program, 900, scratch);
            assertEquals(expected[i], sample(program, times[i], scratch), "direct seek " + times[i]);
            assertEquals(expected[i], sample(groupedProgram("seek-timing"), times[i], scratch),
                    "recompiled seek " + times[i]);
        }
    }

    @Test
    void finalParagraphHasNoOutgoingTransitionEvenAfterItsVisualEnd() {
        SonnetProgram program = twoParagraphs("paragraph-timing", 1000, 10000);
        SonnetProgram.Paragraph last = program.paragraphs.get(1);
        assertEquals(-1, last.transitionKind);
        assertEquals(11240, program.finalRenderEndMs(), EPSILON);
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        for (double time : new double[]{11240, 12000, 60000}) {
            assertEquals(last.firstShot, program.shotAt(time));
            program.transitionAt(last.firstShot, time, frame);
            assertIdle(frame);
        }
    }

    @Test
    void emptyProgramHasNoActiveShotOrFinalVisualEnd() {
        SonnetProgram program = SonnetProgram.compile(List.of(), "empty-timing");
        assertTrue(program.paragraphs.isEmpty());
        assertEquals(-1, program.shotAt(1000));
        assertEquals(0, program.finalRenderEndMs(), EPSILON);
    }

    private static LyricLine timedLine(long start, long duration, String text) {
        LyricLine line = new LyricLine(start, text);
        line.duration = duration;
        if (duration > 0) line.words.add(new LyricLine.Word(text, start, duration));
        return line;
    }

    private static SonnetProgram twoParagraphs(String seed, long firstDuration, long nextStart) {
        LyricLine next = timedLine(nextStart, 1000, "归途");
        next.isChorus = true; // Force a boundary even when a small timing gap would not split a paragraph.
        return SonnetProgram.compile(List.of(timedLine(1000, firstDuration, "微光"), next), seed);
    }

    private static SonnetProgram groupedProgram(String seed) {
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LyricLine line = timedLine(1000 + i * 1000L, 1000, "微光");
            line.words.clear();
            line.words.add(new LyricLine.Word("微光", line.timestamp, 700));
            lines.add(line); // The authored 300 ms hold already contains the visual pass/exit.
        }
        LyricLine next = timedLine(10000, 1000, "归途");
        next.isChorus = true;
        lines.add(next);
        return SonnetProgram.compile(lines, seed);
    }

    private static List<LyricLine> microLines(long start, boolean chorus) {
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LyricLine line = timedLine(start + i * 20L, 20, "音");
            line.isChorus = chorus;
            lines.add(line);
        }
        return lines;
    }

    private static double lastGlyphEnd(SonnetProgram.Shot shot) {
        return shot.lines.stream().flatMap(List::stream).flatMap(segment -> segment.glyphs.stream())
                .mapToDouble(glyph -> glyph.endMs).max().orElseThrow();
    }

    private static void assertEnterStart(SonnetTextMotion.Transition frame, int kind, long seed) {
        switch (kind) {
            case 0 -> assertFrame(frame, 0.18, 14, 0, 0);
            case 1 -> assertFrame(frame, 1, 0, 1, seed * 0.0001);
            case 2 -> assertFrame(frame, 0.28, 0, 0, 0);
            default -> throw new AssertionError("Unexpected reference transition kind " + kind);
        }
    }

    private static void assertMidpoint(SonnetTextMotion.Transition frame, int kind, boolean exiting, long seed) {
        // At exactly half of the symmetric (0.65, 0, 0.35, 1) curve, eased amount is .5.
        switch (kind) {
            case 0 -> assertFrame(frame, exiting ? 0.5 : 0.59, 7, 0, 0);
            case 1 -> assertFrame(frame, 1, 0, 0.5, seed * 0.0001 + 1.211);
            case 2 -> assertFrame(frame, exiting ? 0.5 : 0.64, 0, 0, 0);
            default -> throw new AssertionError("Unexpected reference transition kind " + kind);
        }
    }

    private static void assertIdle(SonnetTextMotion.Transition frame) {
        assertFrame(frame, 1, 0, 0, 0);
    }

    private static void assertFrame(SonnetTextMotion.Transition frame,
                                    double alpha, double blur, double glitch, double seed) {
        assertEquals(alpha, frame.alpha, EPSILON, "alpha");
        assertEquals(blur, frame.blur, EPSILON, "blur");
        assertEquals(glitch, frame.glitch, EPSILON, "glitch");
        assertEquals(seed, frame.seed, EPSILON, "glitch seed");
    }

    private record Frame(int shotIndex, double alpha, double blur, double glitch, double seed) { }

    private static Frame sample(SonnetProgram program, double time, SonnetTextMotion.Transition frame) {
        int shot = program.shotAt(time);
        program.transitionAt(shot, time, frame);
        return new Frame(shot, frame.alpha, frame.blur, frame.glitch, frame.seed);
    }
}
