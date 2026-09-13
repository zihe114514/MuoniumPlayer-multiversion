package repackage.com.jsyn.unitgen;

import repackage.com.jsyn.ports.UnitInputPort;

/**
 * Automix卡组滤波器： {@link FilterLowShelf}加上带限中心抵消器，
 * 在曲目淡入时将声音从曲目中引出。
 *
 * <h3>为什么再加一个处理器而不是另一条音量曲线</h3>
 * <p>低音交换已经阻止了两个踢鼓堆叠，但听众实际上可以
 * 名字是同时两个主唱。那个不能被搁置：一个声音占据了与
 * 吉他和军鼓，因此任何把它消掉的 EQ 都会把编曲一并消掉。它的作用是让
 * 将主唱与混音的其余部分分开是<em> ，其中</em>是-死点，在
 * 两个通道。减去（带限） MID信号，从而在离开时消除声音
 * 几乎没有触及的任何东西，这正是经典的卡拉OK技巧，它足以
 * 进入的轨道以器乐垫底的形式进入，直到待切出轨道的人声退出后才成为歌曲。
 * gone. </p>
 *
 * <h3>侧通道如何到达此处</h3>
 * <p> {@code Effect}运行两个独立的滤波器实例，每个通道一个，每个只看到自己的
 * 信号-因此，如果没有另一个通道，就不可能进行中心估计。额外的{@link #side}输入是
 * 它是如何到达的：{@code UnitGenerator.pullData} 在调用前会遍历每个 {@code ConnectableInput} 端口，
 * {@code generate}，因此只需声明端口，框架就会自动获取它，并且
 * 因为单元生成器每帧计数只生成一个块，所以不生成共享源
 * 两次。</p>
 *
 * <h3>三个刻意为之的特性</h3>
 * <ul>
 *   <li><b>比特精确的旁路。</b>在深度 0 时，搁架自身输出之上不叠加任何东西，因此
 *       始终接入的链路在普通播放时仍不会染色。</li>
 *   <li> <b>平滑深度。</b>驾驶员每10毫秒刻度一次；步进取消将被听到
 *       声音被切换，因此深度以一极旋转跟随其目标。</li>
 *   <li> <b>单声道回退。</b>将单声道文件平移到两个相同的通道，其中中心<em>为</em>
 *       信号和完全减法将使整个频带沉默。{@link #setMonoFallback(boolean)}
 *       切换到部分中频DIP ，这是较弱的，但音乐，它是默认的，所以
 *       侧通道无法接线的链条静静地退化，而不是吃掉混合物。</li>
 * </ul>
 */
public class VocalDuckFilter extends FilterLowShelf {

    /**
     * 被消除的频段。在约 180 Hz 以下，没有值得消除的人声基频，却有大量
     * 低音，在~4.2 kHz以上，大多数是同音异音和音调-取出任一音调都可以听到
     * 作为减肥或空气的混合物，而不是声带离开。
     */
    private static final double HIGH_PASS_HZ = 180.0;
    private static final double LOW_PASS_HZ = 4_200.0;
    /** 两个频段边缘都用 Butterworth Q：最平坦、无波纹的响应。 */
    private static final double BAND_Q = .70710678;
    /** 低于此值的深度变化是听不到的，并被视为“不变”。*/
    private static final double DEPTH_EPSILON = .002;
    /** 转换时间常数。长到听不见，又短到足以跟上 10 ms 的驱动。 */
    private static final double SLEW_SECONDS = .035;
    /**
     * 单声道源在最大深度时该频段被衰减多少。比立体声路径用得少，
     * 因为这里没有可隔离的中心：这是对整个信号做的一个 EQ 陷波，而且深入
     * 进一步只会使曲目声音破损。
     */
    private static final double MONO_DIP_SCALE = .55;

    /** 另一个通道的预效应信号，仅用于估计中心。*/
    public UnitInputPort side;

    private volatile double targetDepth;
    private volatile double targetMidGainDb;
    private volatile double targetHighGainDb;
    /** 默认为安全模式：如果没有有线侧通道，则完全减法是错误的。*/
    private volatile boolean monoFallback = true;

    private double currentDepth;
    private double currentMidGainDb;
    private double currentHighGainDb;
    private double lowState;
    private double midState;
    private int cachedRate;
    private int computedRate;

    private double highB0 = 1.0, highB1, highB2, highA1, highA2;
    private double lowB0 = 1.0, lowB1, lowB2, lowA1, lowA2;
    private double highX1, highX2, highY1, highY2;
    private double lowX1, lowX2, lowY1, lowY2;

    public VocalDuckFilter() {
        addPort(side = new UnitInputPort("Side"));
    }

    /**
     * @param depth 0 保持信号不变，1 为完全消除该限带中心。
     */
    public void setDepth(float depth) {
        if (Float.isNaN(depth) || Float.isInfinite(depth)) return;
        this.targetDepth = Math.max(0.0, Math.min(1.0, depth));
    }

    public float getDepth() {
        return (float) this.targetDepth;
    }

    /** 跳到一个没有回转的深度，对于尚未开始发声的卡组。*/
    public void resetDepthTo(float depth) {
        setDepth(depth);
        this.currentDepth = this.targetDepth;
        clearBandState();
    }

    public void setMidGainDb(float db) {
        if (Float.isNaN(db) || Float.isInfinite(db)) return;
        this.targetMidGainDb = Math.max(-3.0, Math.min(3.0, db));
    }

    public float getMidGainDb() { return (float) this.targetMidGainDb; }

    public void setHighGainDb(float db) {
        if (Float.isNaN(db) || Float.isInfinite(db)) return;
        this.targetHighGainDb = Math.max(-3.0, Math.min(3.0, db));
    }

    public float getHighGainDb() { return (float) this.targetHighGainDb; }

    public void resetToneTo(float midDb, float highDb) {
        setMidGainDb(midDb); setHighGainDb(highDb);
        this.currentMidGainDb = this.targetMidGainDb; this.currentHighGainDb = this.targetHighGainDb;
        this.lowState = 0.0; this.midState = 0.0;
    }

    /** 当此通道没有可用的侧面信号并且必须回落到中频带倾斜时为真。*/
    public void setMonoFallback(boolean mono) {
        this.monoFallback = mono;
    }

    @Override
    public void generate(int start, int limit) {
        super.generate(start, limit);
        int frames = limit - start;
        if (frames <= 0) return;
        int rate = this.cachedRate;
        if (rate <= 0) {
            try { rate = getFrameRate(); } catch (Throwable ignored) { rate = 0; }
            if (rate <= 0) rate = 44_100;
            this.cachedRate = rate;
        }
        double coefficient = 1.0 - Math.exp(-frames / (SLEW_SECONDS * rate));
        double target = this.targetDepth;
        this.currentDepth += (target - this.currentDepth) * coefficient;
        if (Math.abs(target - this.currentDepth) < DEPTH_EPSILON) this.currentDepth = target;
        double targetMid = this.targetMidGainDb, targetHigh = this.targetHighGainDb;
        this.currentMidGainDb += (targetMid - this.currentMidGainDb) * coefficient;
        this.currentHighGainDb += (targetHigh - this.currentHighGainDb) * coefficient;
        boolean toneFlat = Math.abs(this.currentMidGainDb) < .002 && Math.abs(this.currentHighGainDb) < .002
                && Math.abs(targetMid) < .002 && Math.abs(targetHigh) < .002;
        if (this.currentDepth < DEPTH_EPSILON && target < DEPTH_EPSILON && toneFlat) {
            clearBandState();
            return;
        }
        if (rate != this.computedRate) { updateBand(rate); this.computedRate = rate; }
        double[] outputs = output.getValues();
        double[] own = input.getValues();
        double[] other = this.side.getValues();
        boolean mono = this.monoFallback;
        double depth = this.currentDepth;
        double lowAlpha = 1.0 - Math.exp(-1.0 / (.004 * rate));
        double midAlpha = 1.0 - Math.exp(-1.0 / (.012 * rate));
        double midGain = Math.pow(10.0, this.currentMidGainDb / 20.0);
        double highGain = Math.pow(10.0, this.currentHighGainDb / 20.0);
        for (int i = start; i < limit; i++) {
            double centre = mono ? own[i] * MONO_DIP_SCALE : (own[i] + other[i]) * .5;
            double band = bandPass(centre);
            double value = outputs[i] - depth * band;
            this.lowState += lowAlpha * (value - this.lowState);
            this.midState += midAlpha * (this.lowState - this.midState);
            double mid = this.lowState - this.midState;
            double high = value - this.lowState;
            value += (midGain - 1.0) * mid + (highGain - 1.0) * high;
            double level = Math.abs(value);
            if (level > .95) {
                double room = .05;
                double held = .95 + room * Math.tanh((level - .95) / room);
                value = value < 0.0 ? -held : held;
            }
            if (value != value || Double.isInfinite(value)) { clearBandState(); value = outputs[i]; }
            outputs[i] = value;
        }
    }
    /** 级联高通然后低通，即声乐所在的乐队。*/
    private double bandPass(double x0) {
        double high = this.highB0 * x0 + this.highB1 * this.highX1 + this.highB2 * this.highX2
                - this.highA1 * this.highY1 - this.highA2 * this.highY2;
        this.highX2 = this.highX1;
        this.highX1 = x0;
        this.highY2 = this.highY1;
        this.highY1 = high;

        double low = this.lowB0 * high + this.lowB1 * this.lowX1 + this.lowB2 * this.lowX2
                - this.lowA1 * this.lowY1 - this.lowA2 * this.lowY2;
        this.lowX2 = this.lowX1;
        this.lowX1 = high;
        this.lowY2 = this.lowY1;
        this.lowY1 = low;
        return low;
    }

    private void clearBandState() {
        this.highX1 = 0.0;
        this.highX2 = 0.0;
        this.highY1 = 0.0;
        this.highY2 = 0.0;
        this.lowX1 = 0.0;
        this.lowX2 = 0.0;
        this.lowY1 = 0.0;
        this.lowY2 = 0.0;
    }

    /** Robert Bristow-Johnson 配方书中的高通和低通，按实际帧速率推导。 */
    private void updateBand(int rate) {
        double highHz = Math.max(20.0, Math.min(rate * .45, HIGH_PASS_HZ));
        double w0 = 2.0 * Math.PI * highHz / rate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * BAND_Q);
        double a0 = 1.0 + alpha;
        this.highB0 = ((1.0 + cos) * .5) / a0;
        this.highB1 = (-(1.0 + cos)) / a0;
        this.highB2 = this.highB0;
        this.highA1 = (-2.0 * cos) / a0;
        this.highA2 = (1.0 - alpha) / a0;

        double lowHz = Math.max(highHz * 2.0, Math.min(rate * .45, LOW_PASS_HZ));
        w0 = 2.0 * Math.PI * lowHz / rate;
        cos = Math.cos(w0);
        alpha = Math.sin(w0) / (2.0 * BAND_Q);
        a0 = 1.0 + alpha;
        this.lowB0 = ((1.0 - cos) * .5) / a0;
        this.lowB1 = (1.0 - cos) / a0;
        this.lowB2 = this.lowB0;
        this.lowA1 = (-2.0 * cos) / a0;
        this.lowA2 = (1.0 - alpha) / a0;

        clearBandState();
    }
}
