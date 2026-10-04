package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IslandVisibleClockTest {
    @Test
    void creditsNormalAndLowFpsVisibleFrames() {
        IslandVisibleClock clock = new IslandVisibleClock();
        clock.record(1_000L);
        clock.record(1_100L);
        clock.record(1_700L);
        assertEquals(350L, clock.elapsed());
    }

    @Test
    void pauseDropsHiddenGap() {
        IslandVisibleClock clock = new IslandVisibleClock();
        clock.record(1_000L);
        clock.record(1_100L);
        clock.pause();
        clock.record(10_000L);
        clock.record(10_100L);
        assertEquals(200L, clock.elapsed());
    }

    @Test
    void veryLongStallDoesNotConsumeAWholeHiddenInterval() {
        IslandVisibleClock clock = new IslandVisibleClock();
        clock.record(1_000L);
        clock.record(5_000L);
        assertEquals(250L, clock.elapsed());
    }
}
