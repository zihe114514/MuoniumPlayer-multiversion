package com.muoniumplayer.core.rendering.texture;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TextureUploadQueueTest {
    @Test void stopsBeforeNextUploadWhenTimeBudgetIsSpent() {
        AtomicLong now = new AtomicLong();
        TextureUploadQueue queue = new TextureUploadQueue(now::get);
        List<Integer> completed = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int id = i;
            queue.submit(() -> { completed.add(id); now.addAndGet(800_000); });
        }
        assertEquals(3, queue.drain(2_000_000, 4));
        assertEquals(List.of(0, 1, 2), completed);
        assertEquals(5, queue.pending());
        assertEquals(3, queue.drain(2_000_000, 4));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), completed);
    }

    @Test void countBudgetCapsCheapUploadsToo() {
        TextureUploadQueue queue = new TextureUploadQueue(() -> 0);
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 12; i++) queue.submit(done::incrementAndGet);
        assertEquals(4, queue.drain(2_000_000, 4));
        assertEquals(4, done.get());
        assertEquals(8, queue.pending());
    }

    @Test void oneSlowUploadCannotStarveOrTriggerAnEntireBurst() {
        AtomicLong now = new AtomicLong();
        TextureUploadQueue queue = new TextureUploadQueue(now::get);
        queue.submit(() -> now.addAndGet(20_000_000));
        queue.submit(() -> fail("second upload should wait for the next tick"));
        assertEquals(1, queue.drain(2_000_000, 4));
        assertEquals(1, queue.pending());
    }

    @Test void emptyQueueDoesNotReadClockAndZeroBudgetDoesNoWork() {
        TextureUploadQueue queue = new TextureUploadQueue(() -> { fail("idle clock read"); return 0; });
        assertEquals(0, queue.drain(2_000_000, 4));
        queue.submit(() -> fail("zero budget task"));
        assertEquals(0, queue.drain(0, 4));
        assertEquals(0, queue.drain(2_000_000, 0));
        assertEquals(1, queue.pending());
    }

    @Test void uploadsCanEnqueueMoreWorkWithoutUnboundedDrain() {
        TextureUploadQueue queue = new TextureUploadQueue(() -> 0);
        Runnable[] again = new Runnable[1];
        again[0] = () -> queue.submit(again[0]);
        queue.submit(again[0]);
        assertEquals(4, queue.drain(2_000_000, 4));
        assertEquals(1, queue.pending());
    }

    @Test void concurrentProducersDoNotLoseWork() throws Exception {
        TextureUploadQueue queue = new TextureUploadQueue(() -> 0);
        AtomicInteger done = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 500; i++) queue.submit(done::incrementAndGet);
            });
            workers.add(thread); thread.start();
        }
        for (Thread worker : workers) worker.join();
        assertEquals(2000, queue.pending());
        while (queue.pending() > 0) queue.drain(2_000_000, 4);
        assertEquals(2000, done.get());
    }

    @Test void failingTaskDoesNotLoseFollowingUploads() {
        TextureUploadQueue queue = new TextureUploadQueue(() -> 0);
        queue.submit(() -> { throw new IllegalStateException("decode failed"); });
        AtomicInteger done = new AtomicInteger();
        queue.submit(done::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> queue.drain(2_000_000, 4));
        assertEquals(1, queue.pending());
        assertEquals(1, queue.drain(2_000_000, 4));
        assertEquals(1, done.get());
    }
}
