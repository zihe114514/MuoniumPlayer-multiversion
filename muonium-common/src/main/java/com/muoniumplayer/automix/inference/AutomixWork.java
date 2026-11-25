package com.muoniumplayer.automix.inference;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/** AUTOMIX 分析的独占工作入口，与封面、网络和播放控制线程隔离。 */
public final class AutomixWork {
    private static final AutomixTaskQueue QUEUE = new AutomixTaskQueue(8);
    private static final ReentrantLock ANALYSIS_LOCK = new ReentrantLock(true);

    private AutomixWork() {}

    /** 队列满时返回失败信号，由预备状态机冷却重试，不在调用线程执行重活。 */
    public static CompletableFuture<Void> submit(Runnable action) {
        return QUEUE.submit(action);
    }

    public static boolean isBusy() {
        return QUEUE.isBusy() || ANALYSIS_LOCK.isLocked();
    }

    /** 房间预热等同步入口也共用这一道闸，防止两份模型同时争抢 CPU 和缓存文件。 */
    public static <T> T runExclusive(Callable<T> action) throws Exception {
        checkCancelled();
        ANALYSIS_LOCK.lockInterruptibly();
        try {
            checkCancelled();
            return action.call();
        } finally {
            ANALYSIS_LOCK.unlock();
        }
    }

    /** 退出时只发取消信号，正在执行的原生调用在退出分块后回收会话。 */
    public static void shutdown() {
        QUEUE.close();
    }

    /** 分块边界与写缓存前检查；保留中断位，让上游取消能够继续传播。 */
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("AUTOMIX 分析已取消");
        }
    }
}
