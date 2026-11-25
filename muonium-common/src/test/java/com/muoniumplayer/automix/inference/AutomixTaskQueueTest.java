package com.muoniumplayer.automix.inference;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实排队、拒绝和取消行为，不用睡眠猜测工作线程的状态。 */
class AutomixTaskQueueTest {
    @Test void runsOnlyOneAnalysisAndRejectsOverflowWithoutBlockingCaller() throws Exception {
        try (AutomixTaskQueue queue = new AutomixTaskQueue(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicReference<String> worker = new AtomicReference<>();
            CompletableFuture<Void> first = queue.submit(() -> {
                worker.set(Thread.currentThread().getName());
                maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
                entered.countDown();
                await(release);
                running.decrementAndGet();
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS), "第一份任务未开始");
            CompletableFuture<Void> second = queue.submit(() -> {
                maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
                running.decrementAndGet();
            });
            CompletableFuture<Void> rejected = queue.submit(() -> fail("队列满时不能在调用线程执行"));
            assertTrue(rejected.isCompletedExceptionally(), "溢出任务必须返回失败信号");
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
            assertEquals(1, maximum.get());
            assertEquals("muonium-automix-analysis", worker.get());
        }
    }

    @Test void cancellationInterruptsRunningTaskAndRemovesQueuedTask() throws Exception {
        try (AutomixTaskQueue queue = new AutomixTaskQueue(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger obsoleteRuns = new AtomicInteger();
            CompletableFuture<Void> running = queue.submit(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            CompletableFuture<Void> obsolete = queue.submit(obsoleteRuns::incrementAndGet);
            assertTrue(obsolete.cancel(true));
            CompletableFuture<Void> replacement = queue.submit(() -> {});
            assertFalse(replacement.isCompletedExceptionally(), "取消后必须立即腾出排队槽位");
            assertTrue(running.cancel(true));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS), "执行中的过期分析没有收到中断");
            replacement.get(3, TimeUnit.SECONDS);
            assertEquals(0, obsoleteRuns.get());
        }
    }

    @Test void shutdownCompletesSignalsOfQueuedTasks() throws Exception {
        AutomixTaskQueue queue = new AutomixTaskQueue(1);
        CountDownLatch entered = new CountDownLatch(1);
        queue.submit(() -> { entered.countDown(); await(new CountDownLatch(1)); });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        CompletableFuture<Void> queued = queue.submit(() -> fail("关闭后不能执行积压任务"));
        queue.close();
        assertTrue(queued.isCancelled(), "关闭队列后不能留下永不完成的 future");
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
    }
}
