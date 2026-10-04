package com.muoniumplayer.core.rendering.texture;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.function.LongSupplier;

/** Workers submit; only the render thread drains. Never hold the lock during an upload. */
final class TextureUploadQueue {
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final LongSupplier clock;

    TextureUploadQueue() {
        this(System::nanoTime);
    }

    TextureUploadQueue(LongSupplier clock) {
        this.clock = clock;
    }

    void submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (tasks) {
            tasks.add(task);
        }
    }

    int pending() {
        synchronized (tasks) {
            return tasks.size();
        }
    }

    /** A running upload cannot be preempted; stop before the next one when over budget. */
    int drain(long budgetNanos, int maxUploads) {
        if (budgetNanos <= 0 || maxUploads <= 0 || pending() == 0) {
            return 0;
        }
        long start = clock.getAsLong();
        int uploaded = 0;
        do {
            Runnable task;
            synchronized (tasks) {
                task = tasks.poll();
            }
            if (task == null) {
                break;
            }
            task.run();
            uploaded++;
        } while (uploaded < maxUploads && clock.getAsLong() - start < budgetNanos);
        return uploaded;
    }
}
