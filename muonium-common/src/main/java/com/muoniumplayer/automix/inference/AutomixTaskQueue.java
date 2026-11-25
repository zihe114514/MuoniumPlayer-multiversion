package com.muoniumplayer.automix.inference;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 单工作线程、有界积压；取消 future 会移除排队任务并中断正在执行的任务。 */
final class AutomixTaskQueue implements AutoCloseable {
    private final ThreadPoolExecutor executor;

    AutomixTaskQueue(int capacity) {
        executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), action -> {
                    Thread thread = new Thread(action, "muonium-automix-analysis");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    CompletableFuture<Void> submit(Runnable action) {
        Objects.requireNonNull(action, "分析任务不能为空");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (completion.isCancelled()) return null;
            try {
                AutomixWork.checkCancelled();
                action.run();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
            return null;
        }) {
            @Override protected void done() {
                if (isCancelled()) completion.cancel(false);
            }
        };
        completion.whenComplete((ignored, failure) -> {
            if (completion.isCancelled()) {
                executor.remove(task);
                task.cancel(true);
            }
        });
        try {
            executor.execute(task);
        } catch (RuntimeException rejected) {
            completion.completeExceptionally(rejected);
        }
        return completion;
    }

    boolean isBusy() {
        return executor.getActiveCount() > 0 || !executor.getQueue().isEmpty();
    }

    @Override
    public void close() {
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof FutureTask<?> future) future.cancel(false);
        }
    }
}
