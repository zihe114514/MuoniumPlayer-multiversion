package com.muoniumplayer.core.ncm.music;

/**
 * Folia 模型推理的资源预算：把「推理能吃掉多少 CPU」收在一个地方。
 *
 * <h2>为什么需要这个类</h2>
 * 推理本身跑在 {@code BackgroundWork} 的低优先级池上（线程 {@link Thread#MIN_PRIORITY}、
 * 并发上限 1–2），那一层没问题。但 ONNX Runtime 的算子级并行是<b>它自己开的线程池</b>：
 * {@code SessionOptions.setIntraOpNumThreads(n)} 出来的 n 条线程与调用它的后台线程没有
 * 任何关系 —— 既不在那个池里，也不受它的并发上限约束。于是「我把推理丢进低优先级池」
 * 这句话只在 chunk 与 chunk 之间的那点 Java 代码上成立；真正烧 CPU 的矩阵乘法跑在
 * 另外 n 条线程上，与 Minecraft 渲染线程争同一批核心。
 *
 * <p>实测症状就是手机上的掉帧：4 核机器按旧代码开 4 条 intra-op 线程，加上渲染线程与
 * 音频线程，可运行队列里塞满了等核的线程。核数一满，优先级也救不了 —— 每个核上都只有
 * 同权任务在排队。
 *
 * <h2>这里能做什么、不能做什么</h2>
 * <ul>
 *   <li><b>能：把线程数压下来。</b>这是本类的主要手段，也是唯一有确定效果的手段。
 *       上限 2，且不超过 {@code cores - 2}。4 核手机得到 2 条，8 核机也还是 2 条 ——
 *       推理是后台活，用户没有在等它；让它在 4 条线程上快 1.5 倍、代价是游戏掉帧，
 *       这笔账不划算。少一半线程同时也少一半的并发内存占用。</li>
 *   <li><b>不能：改 ORT worker 的优先级。</b>ORT 没有公开的线程优先级开关，而
 *       {@link Thread#setPriority} 只作用于<b>当前这一条</b>线程：HotSpot 在 Linux/Android
 *       上以 {@code PTHREAD_EXPLICIT_SCHED} 建线程，优先级不沿创建关系传递，所以
 *       「先把自己的线程压低，再让 ORT 去 fork」不会影响到 ORT 的 worker。
 *       {@link #lowerCurrentThread()} 保留下来是因为它对<b>调用线程自己</b>确实有效
 *       ——能压低的是包在 {@code run()} 外面的那段 Java（张量构造、输出解包、重叠相加），
 *       而那段在长窗口上并不小。它不承诺影响 ORT 内部。</li>
 * </ul>
 *
 * <p>代价是分析变慢。这是刻意的取舍：分析发生在<b>预备期</b>（下一首的解析、下载、解码
 * 之后，交接点之前），时间预算本该宽裕；交接点等不到计划时会退回普通切歌，功能不会坏。
 * 而掉帧是每一帧都在发生的、用户直接看得见的东西。
 */
final class FoliaInferenceBudget {

    /**
     * 单次推理的算子级线程上限。
     *
     * <p>压在 2 而不是「核数 - 2」：手机上 {@code availableProcessors()} 报的既可能是
     * 小核数量也可能是大小核总数（取决于内核与厂商），按它减 2 在 8 核机上会给出 6，
     * 那等于没限制。推理一条线程跑 30 秒和在两条线程上跑 20 秒，对「交接点之前有没有
     * 算完」这件事几乎没有区别，但对帧率区别很大。
     */
    private static final int THREAD_CEILING = 2;

    private FoliaInferenceBudget() {}

    /**
     * 本次推理允许的 intra-op 线程数。
     *
     * <p>留两个核给渲染与音频：4 核 → 2 条，3 核 → 1 条，2 核及以下 → 1 条。
     * 上限恒 ≤ {@link #THREAD_CEILING}。
     */
    static int intraOpThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(THREAD_CEILING, cores - 2));
    }

    /**
     * 把当前线程压到最低优先级，返回原优先级以便还原。
     *
     * <p><b>它只影响当前这一条线程</b>，不影响 ORT 内部的 worker（见类注释：优先级不沿
     * 创建关系传递）。有效范围是包在 {@code session.run()} 外面的那段 Java —— 张量构造、
     * ORT 返回值的解包、以及重叠相加的归一化。30 秒窗口上这段是百万级的浮点运算，
     * 值得压低。
     *
     * <p>安全管理者不允许改优先级时静默跳过（返回 -1）：那只是失去一层保护，不是功能失败。
     */
    static int lowerCurrentThread() {
        Thread thread = Thread.currentThread();
        try {
            int previous = thread.getPriority();
            if (previous > Thread.MIN_PRIORITY) thread.setPriority(Thread.MIN_PRIORITY);
            return previous;
        } catch (RuntimeException ignored) {
            // SecurityException 也是 RuntimeException：安全管理者不允许改优先级时走这里。
            return -1;
        }
    }

    /** 还原 {@link #lowerCurrentThread()} 的优先级；{@code previous < 0} 表示当初没改成，不动。 */
    static void restoreCurrentThread(int previous) {
        if (previous < 0) return;
        try {
            Thread thread = Thread.currentThread();
            if (thread.getPriority() != previous) thread.setPriority(previous);
        } catch (RuntimeException ignored) {
            // 还原失败无害：这条线程属于后台池，它下一次任务自己会重新压低。
        }
    }

    /**
     * chunk 之间的让步点：短暂休眠，把核心真正空出来给渲染线程。
     *
     * <p>分批推理的循环里，每个 chunk 都是一次几十到几百毫秒的连续占核。中间不留让步点时，
     * 一个大窗口的十几个 chunk 会连成几秒的不间断负载；那几秒正是用户能感觉到的「一分析就卡」。
     *
     * <p>用 {@link Thread#sleep} 而不是 {@code Thread.yield()}：{@code yield()} 只是把线程
     * 放回可运行队列，在负载已满的机器上调度器可以立刻又选中它 —— 等于什么都没做。而睡眠
     * 会让出核心，代价是可控的固定开销。
     *
     * <p>1 毫秒是权衡后的值：它远短于一个渲染帧（60fps 的一帧是 16.7ms），对渲染线程而言
     * 相当于「立刻就能拿到核」；同时 30 秒窗口的十来个 chunk 总共只多花十几毫秒，对
     * 「交接点之前算没算完」没有影响。
     *
     * <p>被打断时立即返回：中断意味着这次预备已经被取消（换歌、退出世界），继续拖时间没有意义。
     */
    static void yieldToGame() {
        try {
            Thread.sleep(YIELD_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** chunk 之间让出的毫秒数。见 {@link #yieldToGame()} 的取值理由。 */
    private static final long YIELD_MILLIS = 1L;
}
