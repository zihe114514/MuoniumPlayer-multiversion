package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LyricPreparationQueueTest {
    @Test
    void preparesReadingWindowBeforeDistantRows() {
        AtomicLong now = new AtomicLong();
        LyricPreparationQueue queue = new LyricPreparationQueue(() -> now.addAndGet(1));
        queue.reset(100, 50);
        List<Integer> indexes = new ArrayList<>();
        queue.advance(7, 1000, indexes::add);
        assertEquals(List.of(50, 51, 49, 52, 48, 53, 47), indexes);
    }

    @Test
    void skipsOutOfRangeNearWindowWithoutProducingInvalidIndexes() {
        AtomicLong now = new AtomicLong();
        LyricPreparationQueue queue = new LyricPreparationQueue(() -> now.addAndGet(1));
        queue.reset(3, 0);
        List<Integer> indexes = new ArrayList<>();
        queue.advance(100, 10000, indexes::add);
        assertFalse(indexes.isEmpty());
        assertTrue(indexes.stream().allMatch(i -> i >= 0 && i < 3));
        assertEquals(3, indexes.stream().distinct().count());
        assertTrue(queue.finished());
    }

    @Test
    void timeBudgetStopsCooperatively() {
        AtomicLong now = new AtomicLong();
        LyricPreparationQueue queue = new LyricPreparationQueue(() -> now.addAndGet(10));
        queue.reset(100, 50);
        List<Integer> indexes = new ArrayList<>();
        int processed = queue.advance(100, 25, indexes::add);
        assertTrue(processed > 0 && processed < 100);
    }
}
