package repackage.com.jsyn.util.soundfile.streamed.raf;

import lombok.SneakyThrows;
import repackage.com.jsyn.data.FloatSample;

/**
 * 日期： 2025/5/5 12:48
 */
public class RafFloatSample extends FloatSample {

    public final RafWAVEFileParser.StreamedByteDataReader reader;

    /** 顺序读的环形缓存：把窗口 IO 从 JSyn 引擎线程挪到一条低优先级预取线程。 */
    private final PrefetchRing prefetch;

    /** 带数据的多声道采样构造函数。 */
    public RafFloatSample(RafWAVEFileParser.StreamedByteDataReader reader, int numFrames, int channelsPerFrame) {
        this.reader = reader;
        this.numFrames = numFrames;
        this.channelsPerFrame = channelsPerFrame;
        this.prefetch = new PrefetchRing(reader);
    }

    @Override
    public void allocate(int numFrames, int channelsPerFrame) {
//            buffer = new float[numFrames * channelsPerFrame];

    }

    @Override
    public void read(int startFrame, float[] data, int startIndex, int numFrames) {
        int numSamplesToRead = numFrames * channelsPerFrame;
        int firstSampleIndexToRead = startFrame * channelsPerFrame;
//        System.arraycopy(buffer, firstSampleIndexToRead, data, startIndex, numSamplesToRead);
    }

    @Override
    @SneakyThrows
    public double readDouble(int index) {

        if (closed)
            return 0;

        return prefetch.read(index);
    }

    boolean closed = false;

    @Override
    @SneakyThrows
    public void cleanUp() {

        closed = true;

        // 先让预取线程停下再去关 RAF：shutdown 会取一次 SYNC 锁，
        // 返回之后预取线程不可能再碰那个文件句柄（见 PrefetchRing.loop 的双重 running 核对）。
        prefetch.shutdown();

        if (this.reader != null)
            this.reader.parser.raf.close();
    }

    /** 白盒计数：走锁内应急路径的读次数。正常稳态播放增长为 0（仅冷启动/seek/EOF 附近计入）。 */
    long slowReadCount() {
        return prefetch.slowReads;
    }

    /** 白盒探测：预取线程是否还活着。 */
    boolean prefetchAlive() {
        return prefetch.isAlive();
    }

    /**
     * 预热旁路：从任意位置读一个窗口并<b>丢弃</b>，完全不经过环形缓存。
     *
     * <p>给 automix 的 {@code prepareStart} 预备线程用。它要预热的不是 ring
     * （ring 由预取线程管理，读写锚点只属于<b>正在出声的那一路</b> deck），
     * 而是操作系统页缓存——交接那一刻的真正目的是让接缝附近的数据已经在 RAM 里。
     * 旧实现的窗口读恰好也只做了这件事；上一版把它继续路由进
     * {@code readDouble} 是个错误：预备位置通常在<b>未来几分钟处</b>，一次远读把
     * {@code written} 拖到那里，正在出声的引擎线程从此逐采样 miss、逐采样进锁
     * 同步读盘——开启智能混音即持续掉帧的根因。读的结果被刻意丢掉，返回的只是
     * 「读成功与否」。</p>
     *
     * <p>线程安全：与预取线程、应急读共用 {@code SYNC} 锁串行化文件句柄访问；
     * 调用发生在预备线程（出声之前），拿不到锁的等待不影响任何实时路径。</p>
     *
     * @return 是否成功把目标窗口拉进了页缓存
     */
    public boolean primeWindowAt(int interleavedIndex) {
        return prefetch.primeAt(interleavedIndex);
    }

    /**
     * 在后台为即将起播的读头播种环形缓存，并让顺序预取从该位置继续。
     * 调用方必须保证这个样本尚未出声，或已经暂停。
     */
    public boolean preparePlaybackAt(int interleavedIndex) {
        return prefetch.prepareAt(interleavedIndex);
    }

    /**
     * 单生产者环形缓存。引擎线程的稳态读路径是一次 volatile 读、两次比较、一次掩码取值
     * ——零锁、零分配、零 IO。
     *
     * <h3>为什么必须这样改</h3>
     * <p>历史上 {@code readDouble} 在窗口 miss 时当场做 seek＋读盘＋解码
     * （{@code StreamedByteDataReader.read}），而调用它的正是 JSyn 引擎线程
     * ——音频回调里每约 42.7 ms 埋一次同步磁盘读。Windows 上设备缓冲只有
     * 80 ms（{@code JavaSoundAudioDevice} 的 {@code suggestedOutputLatency = 0.08}），
     * 磁盘任何一次同量级抖动（杀毒扫描、页面缓存逐出、HDD 寻道）就会让设备欠载，
     * 听感是「音乐停顿一下又瞬间恢复」。根治只有一条路：引擎线程稳态零 IO，
     * 窗口由后台线程提前填好。</p>
     *
     * <h3>访问模式契约</h3>
     * <p>读头（{@code VariableRateStereoReader}）严格顺序、逐采样 +1，永不回头
     * （{@code WsolaStretchSample} 类注释钉着这一点，WSOLA 自带重锚计数器守卫违约者）；
     * WSOLA 接管后变前向突发（一个块约 82+28 ms 素材）。因此缓存只需覆盖「顺序＋前向跳」：
     * 冷启动、seek 与预取线被追上这几个过渡时刻走一次锁内应急读，单次代价与旧
     * {@code readDouble} 的一次窗口 miss 相同。读侧只有一个消费者（引擎线程；
     * {@code prepareStart} 的预热那一笔也发生在出声之前），多消费者不受支持。</p>
     *
     * <h3>正确性骨架</h3>
     * <ul>
     *   <li><b>单字段有效性</b>：数据先落入 {@code ring}，随后才 volatile 发布 {@code written}。
     *       读侧只依赖这一个锚点判定「index 落在 [written-VALID_SPAN, written)」。
     *       只用一个 volatile，基址/长度各读到一半的撕裂组合从根上不存在。</li>
     *   <li><b>掩码防乒乓撕裂</b>：{@code VALID_SPAN = RING - WINDOW} 保证生产者写入
     *       [written, written+WINDOW) 时触碰的槽位只别名到 written-RING 之前的旧位置——
     *       按上面的判据那些位置一律无效，消费者读不到正被覆写的槽。</li>
     *   <li><b>流控防覆写未读数据</b>：生产者停在「领先 consumed 一个 VALID_SPAN」处
     *       （consumed 由消费者在窗口边界顺手刷新，一次 volatile 写）。这同时保证了
     *       消费者手里的旧快照永远够旧：即使它在读到 written 之后被调度器冻结几十毫秒，
     *       期间所有新写入的别名区都落在它快照的有效区之外。</li>
     * </ul>
     *
     * <p>两条线程共享的 {@code StreamedByteDataReader}（含解码缓冲与 {@code RandomAccessFile}
     * 句柄）只允许在 {@code SYNC} 锁内调用：读侧 miss 与预取线程的下一窗口读写锁串行。
     * 预取线程任何异常都只是熄火，之后所有 miss 由读侧自己应急读——等价于改动前的行为，
     * 出声链路不受影响。应急读最坏情形（消费速度长期超过预取、预取恰在锁内读盘）与旧实现
     * 同量级；稳态（磁盘抖动短于缓冲余量）由约 0.65 秒的环深吸收，这正是本改动要买的东西。</p>
     */
    static final class PrefetchRing {

        /** 窗口刻度，沿用 {@code StreamedByteDataReader#floatArrLen}（4096 采样）。 */
        static final int WINDOW = 4096;
        /** 环深（交错采样数）。必须是 2 的幂，取模才会被编译成掩码。 */
        static final int RING = 64 * 1024;
        /** 单字段有效半径；留一个窗口的掩码余量防乒乓撕裂（见类注释）。 */
        static final int VALID_SPAN = RING - WINDOW;
        /** 预取线程的歇拍时长；也是流控已满／EOF 之后的轮询间隔。 */
        private static final long IDLE_POLL_MILLIS = 20L;

        private final RafWAVEFileParser.StreamedByteDataReader reader;
        private final float[] ring = new float[RING];

        /** 数据先落 ring、再发布到这里；读侧唯一的有效性锚点。 */
        private volatile long written;
        /** 消费者的粗略位置（仅流控用；窗口边界处顺手刷一次，一次 volatile 写）。 */
        private volatile long consumed;

        private volatile boolean running = true;
        /** 白盒计数：应急读次数（仅诊断与测试）。 */
        private volatile long slowReads;

        private final Object SYNC = new Object();
        private boolean failureLogged;
        private Thread worker;

        PrefetchRing(RafWAVEFileParser.StreamedByteDataReader reader) {
            this.reader = reader;
            worker = new Thread(this::loop, "muonium-raf-prefetch");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }

        /**
         * 预热旁路（见外层 {@link RafFloatSample#primeWindowAt} 的契约）：
         * 锁内读一个窗口并丢弃，不发布、不写 {@code written}/{@code consumed}。
         * StreamedByteDataReader 的文件指针会被挪走，但预取线程与应急读
         * 下一次都按绝对位置 seek，不依赖指针的连续性。
         */
        boolean primeAt(int interleavedIndex) {
            if (interleavedIndex < 0) return false;
            synchronized (SYNC) {
                try {
                    float[] got = reader.read((long) interleavedIndex * reader.getSampleSizeInBytes());
                    return got != null && got.length > 0;
                } catch (Throwable t) {
                    logFailure(t);
                    return false;
                }
            }
        }

        boolean isAlive() {
            Thread thread = worker;
            return thread != null && thread.isAlive();
        }

        /** 播放起点的启动余量；8 个窗口在 44.1kHz 立体声下约 371ms。 */
        static final int PREPARED_STARTUP_SPAN = WINDOW * 8;

        /** 把播放起点及其前向启动余量发布到 ring；只用于尚未出声的 deck。 */
        boolean prepareAt(int interleavedIndex) {
            if (interleavedIndex < 0) return false;
            int base = interleavedIndex & ~(WINDOW - 1);
            synchronized (SYNC) {
                try {
                    long cursor = base;
                    long preparedUntil = (long) interleavedIndex + PREPARED_STARTUP_SPAN;
                    boolean containsCue = false;
                    while (cursor < preparedUntil) {
                        float[] got = reader.read(cursor * reader.getSampleSizeInBytes());
                        if (got == null || got.length == 0) break;
                        if (interleavedIndex >= cursor && interleavedIndex < cursor + got.length) {
                            containsCue = true;
                        }
                        publish(cursor, got);
                        cursor += got.length;
                        if (got.length < WINDOW) break;
                    }
                    if (!containsCue) return false;
                    consumed = base;
                    return true;
                } catch (Throwable t) {
                    logFailure(t);
                    return false;
                }
            }
        }

        /** 引擎线程读一个采样。稳态走无锁快路径；miss 落到锁内应急读。 */
        double read(int index) {
            if (index < 0)
                return 0.0;
            long w = written;
            if (index < w && index >= w - VALID_SPAN) {
                if ((index & (WINDOW - 1)) == 0)
                    consumed = index;
                return ring[index & (RING - 1)];
            }
            return slow(index);
        }

        /**
         * 锁内应急读：冷启动、seek、或消费速度暂时追上预取线时，由读线程自己付一次
         * 窗口读——与旧 {@code readDouble} 的一次窗口 miss 同代价，只是稳态永不发生。
         */
        private double slow(int index) {
            synchronized (SYNC) {
                slowReads++;
                long w = written;
                if (index < w && index >= w - VALID_SPAN) {
                    // 进锁之前预取线程刚好发布：命中即可，不必再读。
                    if ((index & (WINDOW - 1)) == 0)
                        consumed = index;
                    return ring[index & (RING - 1)];
                }
                float[] got;
                try {
                    got = reader.read((long) index * reader.getSampleSizeInBytes());
                } catch (Throwable t) {
                    logFailure(t);
                    return 0.0;
                }
                if (got == null || got.length == 0) {
                    // EOF（或畸形位置）：与旧行为一致返回静音。
                    // 刻意不发布空窗口，免得把 written 拖到数据边界之外。
                    return 0.0;
                }
                publish(index, got);
                // 跳变后的消费位置必须由读线程自己登记，预取线程不会替它知道。
                consumed = index;
                w = written;
                if (index < w && index >= w - VALID_SPAN) {
                    return ring[index & (RING - 1)];
                }
                return 0.0;
            }
        }

        /**
         * 锁内发布一个刚读到的窗口。只由持 SYNC 的线程调用。
         * 刻意不动 {@code consumed}：那是消费端的位置，生产端无权改写；
         * 流控因此永远以消费者的真实进度为准（否则预取会越过停顿的消费端
         * 把未读数据覆写掉）。
         */
        private void publish(long base, float[] got) {
            int len = Math.min(got.length, RING);
            int start = (int) (base & (RING - 1L));
            int first = Math.min(len, RING - start);
            System.arraycopy(got, 0, ring, start, first);
            if (len > first) {
                System.arraycopy(got, first, ring, 0, len - first);
            }
            written = base + len;
            SYNC.notifyAll();
        }

        /** 预取线程主循环：领先消费位置不足一个有效半径就补读一个窗口。 */
        private void loop() {
            while (running) {
                try {
                    synchronized (SYNC) {
                        if (!running)
                            return;
                        long w = written;
                        if (w - consumed >= VALID_SPAN) {
                            // 领先量已够（或播放暂停/慢于预取）：歇一拍再看。
                            SYNC.wait(IDLE_POLL_MILLIS);
                            continue;
                        }
                        // 读盘与解码都在锁内：StreamedByteDataReader 的文件指针与
                        // 解码缓冲不是线程安全的，读侧应急读靠同一把锁与这里串行。
                        float[] got = reader.read(w * reader.getSampleSizeInBytes());
                        if (got == null || got.length == 0) {
                            // EOF：数据不会凭空多出来，歇一拍等可能的 seek。
                            SYNC.wait(IDLE_POLL_MILLIS);
                            continue;
                        }
                        publish(w, got);
                    }
                } catch (InterruptedException ie) {
                    // shutdown() 的 notify 或 spurious wake：回外层核对 running。
                    if (!running)
                        return;
                } catch (Throwable t) {
                    // 熄火即退：之后所有 miss 由读侧自己应急读，等价旧行为。
                    logFailure(t);
                    return;
                }
            }
        }

        /**
         * 停预取线程。返回语义是「线程已退出或无法等待」：
         * 先置标志、取锁唤醒，再带超时 join（cleanUp 走的是释放路径，
         * 等这一两毫秒换「返回后绝无可能再碰文件句柄」的确定性是划算的；
         * 等不到也只是极小概率漏等一个已无关紧要的守护线程——RAF 由调用方随后关闭，
         * 预取线程此后至多再空转一次循环即退出）。
         */
        void shutdown() {
            running = false;
            synchronized (SYNC) {
                SYNC.notifyAll();
            }
            Thread thread = worker;
            worker = null;
            if (thread == null) return;
            try {
                thread.join(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private void logFailure(Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                System.err.println("[MuoniumPlayer/Audio] RAF 预取线程熄火，"
                        + "窗口读退回音频线程同步路径：" + t);
            }
        }
    }
}
