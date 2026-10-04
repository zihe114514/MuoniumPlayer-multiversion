package com.muoniumplayer.core.screens.ncm;

import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Render-thread cooperative preparation: reading window first, then the rest of the song. */
final class LyricPreparationQueue {
    static final int RADIUS = 24;
    private final LongSupplier clock;
    private int size, center, nearCursor, backgroundCursor;

    LyricPreparationQueue() { this(System::nanoTime); }
    LyricPreparationQueue(LongSupplier clock) { this.clock = clock; }

    void reset(int count, int anchor) {
        size = Math.max(0, count);
        center = Math.max(0, Math.min(size - 1, anchor));
        nearCursor = 0;
        backgroundCursor = 0;
    }

    boolean finished() {
        return size == 0 || (nearCursor > RADIUS * 2 && backgroundCursor >= size);
    }

    int advance(int maxItems, long budgetNanos, IntConsumer prepare) {
        if (maxItems <= 0 || budgetNanos <= 0 || finished()) return 0;
        long start = clock.getAsLong();
        int processed = 0;
        while (processed < maxItems && clock.getAsLong() - start < budgetNanos) {
            int index;
            if (nearCursor <= RADIUS * 2) {
                // 0, +1, -1, +2, -2 ...; a short budget always warms the actual reading line first.
                int offset = nearCursor == 0 ? 0
                        : ((nearCursor + 1) / 2) * (nearCursor % 2 == 1 ? 1 : -1);
                index = center + offset;
                if (index < 0 || index >= size) { nearCursor++; continue; }
                prepare.accept(index);
                nearCursor++;
            } else {
                while (backgroundCursor < size
                        && Math.abs(backgroundCursor - center) <= RADIUS) backgroundCursor++;
                if (backgroundCursor >= size) break;
                index = backgroundCursor;
                prepare.accept(index);
                backgroundCursor++;
            }
            processed++;
        }
        return processed;
    }
}
