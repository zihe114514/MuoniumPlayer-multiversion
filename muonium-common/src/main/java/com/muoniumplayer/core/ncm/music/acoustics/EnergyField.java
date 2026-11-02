package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 多频带能量直方图:把「多少能量、什么频带、何时回到听者」按时间分桶累积。
 *
 * <p>这是完全体声学的核心中间表示 —— 树侧射线模拟的最终产物不再是几个单值
 * 统计量,而是这张「时 × 频」的能量到达图;common 侧从它解析出逐频带衰减
 * ({@link HistogramEstimator}),Freeverb 时代的「一套衰减参数走天下」到此为止。</p>
 *
 * <h2>结构</h2>
 * <ul>
 *   <li><b>3 频带</b>:低 0-800Hz / 中 800-8000Hz / 高 8000-22000Hz,与 Steam
 *       Audio 的 {@code kLowCutoffFrequencies = {0, 800, 8000}} 同一定义 ——
 *       低频绕射强、高频被空气与软面吃掉,三段恰好覆盖「同一面墙对不同频带
 *       长得不一样」这个物理事实。</li>
 *   <li><b>20 桶 × 20ms</b>:覆盖 0-400ms。早期反射(前 140ms)逐桶可辨,
 *       晚期尾从拟合窗里读出衰减斜率;更晚的能量对音乐混响没有听感意义。</li>
 * </ul>
 *
 * <p>树侧在采样线程写入(射线命中 → 分频带能量入桶),common 在同一线程读取
 * —— 一帧采样内部先写完再读,没有跨线程共享,不需要锁。桶本身是 double,
 * 累加顺序不影响结果到听感精度。</p>
 */
public final class EnergyField {

    /** 频带数:低 / 中 / 高。 */
    public static final int BANDS = 3;
    /** 时间桶数:150 桶 × 20ms = 3s 观察窗。窗长决定 T60 拟合上限:
     *  拟合窗 [−2.5,−0.5]dB 要完整落在观察窗内需要窗长 ≥ 0.42·T60 ——
     *  2s 窗对 5s 尾的拟合终点压线(2.08s),T60 被钳在 ~4.1;3s 窗下
     *  5.5s 尾的拟合终点 2.29s 完整落窗。512 射线 × 13 弹 ≈ 6.6k 命中
     *  分摊到 150 桶,每桶 ~44 条,统计密度充裕。 */
    public static final int BINS = 150;
    /** 每桶时长(秒)。 */
    public static final double BIN_DURATION_SEC = 0.02;
    /** 观察窗总时长(秒)。 */
    public static final double WINDOW_SEC = BINS * BIN_DURATION_SEC;
    /** 频带下限(Hz):{0, 800, 8000};与 Steam Audio bands.h 同一定义。 */
    public static final double[] BAND_LO_HZ = {0.0, 800.0, 8000.0};
    /** 频带上限(Hz):{800, 8000, 22000}。 */
    public static final double[] BAND_HI_HZ = {800.0, 8000.0, 22000.0};

    /** 能量桶:[band][bin];新建即全零。 */
    private final double[][] data = new double[BANDS][BINS];

    /** 空直方图(全零)。 */
    public EnergyField() {
    }

    /** 拷贝构造:树侧缓存几何时连同直方图一起缓存用。 */
    public EnergyField(EnergyField other) {
        for (int b = 0; b < BANDS; b++) {
            System.arraycopy(other.data[b], 0, data[b], 0, BINS);
        }
    }

    /**
     * 把一份能量按到达时间累进对应频带的对应桶。越界(时间超窗、频带号越界)
     * 直接忽略 —— 超窗的能量对拟合没有意义,坏频带号说明调用方有 bug 但不必炸。
     *
     * @param band     频带号 0/1/2
     * @param timeSec  能量到达时刻(秒,通常 = 全路径 ÷ 声速)
     * @param energy   能量值(≥0;负数忽略)
     */
    public void accumulate(int band, double timeSec, double energy) {
        if (band < 0 || band >= BANDS || energy <= 0.0 || timeSec < 0.0 || timeSec >= WINDOW_SEC) {
            return;
        }
        final int bin = (int) (timeSec / BIN_DURATION_SEC);
        if (bin < 0 || bin >= BINS) {
            return;
        }
        data[band][bin] += energy;
    }

    /** 读某频带某桶(只读;估计器与调试用)。 */
    public double get(int band, int bin) {
        return data[band][bin];
    }

    /** 某频带全窗总能量(估计器判「这条频带有没有东西」用)。 */
    public double totalEnergy(int band) {
        double sum = 0.0;
        for (int i = 0; i < BINS; i++) {
            sum += data[band][i];
        }
        return sum;
    }

    /** 三频带合计能量为零 = 这帧采样什么都没听到(旷野/关闭)。 */
    public boolean isEmpty() {
        return totalEnergy(0) + totalEnergy(1) + totalEnergy(2) <= 0.0;
    }
}
