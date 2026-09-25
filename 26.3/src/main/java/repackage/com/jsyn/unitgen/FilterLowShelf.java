package repackage.com.jsyn.unitgen;

/**
 * 二阶低架滤波器（ Robert Bristow-Johnson的音频均衡烹饪书形式）具有平滑，
 * 螺纹安全增益参数。
 *
 * <p>它的存在是为了自动混音的低音切换：当两个轨道重叠时，最容易立刻暴露
 * 混音痕迹的，就是两者都把底鼓和低音塞进同一个 20-250 Hz 窗口，叠加后
 * 变成浑浊的轰鸣，无论怎样做音量淡入淡出都无法挽回。DJ 的做法是把待切出轨道的低频段抽掉，
 * 在新轨道掌控混音后才让它的低音进来。这需要一个
 * 真实滤波器，而不是体积曲线，所以这是一个。</p>
 *
 * <h3>两个刻意为之的特性</h3>
 * <ul>
 *   <li><b>比特精确的旁路。</b>在 0 dB（或极接近）处，滤波器直接复制输入并清空其
 *       状态，因此将一个卡组永久连接到每个卡组不能为普通播放着色。</li>
 *   <li> <b>平滑增益。</b>从玩家的10ms主管调用{@link #setGainDb(float)}
 *       勾选；以该速率步进biquad系数会拉链。增益跟随目标与
 *       a一极回转，系数仅在实际移动时重新计算， </li>
 * </ul>
 */
public class FilterLowShelf extends UnitFilter {

    /** 在这上面，架子也会开始吃低中音。*/
    private static final double MAX_FREQUENCY = 1_000.0;
    private static final double MIN_FREQUENCY = 30.0;
    /** 低于此值的增益变化是听不到的，并被视为“未更改”以跳过系数更新。*/
    private static final double GAIN_EPSILON_DB = .02;
    /** 转换时间常数。长到听不见，又短到足以跟上 10 ms 的驱动。 */
    private static final double SLEW_SECONDS = .025;

    private volatile double targetGainDb;
    private volatile double frequency = 200.0;

    private double currentGainDb;
    private double computedGainDb = Double.NaN;
    private double computedFrequency = Double.NaN;

    /**
     * 帧速率，首次使用时解析。{@code getFrameRate()} 通过合成引擎读取，
     * 在单元分离时抛出，这正是单元测试的状态-或具有
     * 已从图形中间块中删除-已进入。
     */
    private int cachedRate;

    private double b0 = 1.0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;

    /**
     * @param db 低频段货架增益（以dB为单位） ；负切割低音。0绕过滤波器。
     */
    public void setGainDb(float db) {
        if (Float.isNaN(db) || Float.isInfinite(db)) return;
        this.targetGainDb = Math.max(-40.0, Math.min(12.0, db));
    }

    public float getGainDb() {
        return (float) this.targetGainDb;
    }

    /** 货架的角频率，以Hz为单位。*/
    public void setFrequency(float hz) {
        if (Float.isNaN(hz) || Float.isInfinite(hz)) return;
        this.frequency = Math.max(MIN_FREQUENCY, Math.min(MAX_FREQUENCY, hz));
    }

    /** 将滤波器直接跳到单位增益而不做转换，用于即将启动的唱盘。 */
    public void resetTo(float db) {
        setGainDb(db);
        this.currentGainDb = this.targetGainDb;
        this.computedGainDb = Double.NaN;
        clearState();
    }

    private void clearState() {
        this.x1 = 0.0;
        this.x2 = 0.0;
        this.y1 = 0.0;
        this.y2 = 0.0;
    }

    @Override
    public void generate(int start, int limit) {
        double[] inputs = input.getValues();
        double[] outputs = output.getValues();

        int frames = limit - start;
        if (frames <= 0) return;

        double target = this.targetGainDb;
        int rate = this.cachedRate;
        if (rate <= 0) {
            try {
                rate = getFrameRate();
            } catch (Throwable ignored) {
                rate = 0;
            }
            if (rate <= 0) rate = 44100;
            this.cachedRate = rate;
        }

        // 一极转向目标，每个区块评估一次：区块是
        // 毫秒，因此每个样本的插值只会花费周期。
        double coefficient = 1.0 - Math.exp(-frames / (SLEW_SECONDS * rate));
        this.currentGainDb += (target - this.currentGainDb) * coefficient;
        if (Math.abs(target - this.currentGainDb) < GAIN_EPSILON_DB) {
            this.currentGainDb = target;
        }

        if (Math.abs(this.currentGainDb) < GAIN_EPSILON_DB && Math.abs(target) < GAIN_EPSILON_DB) {
            // 直通：信号原样通过，而非经过一个单位增益的双二阶滤波器，从而
            // 始终接入的滤波器在普通播放时可证明是透明的。
            clearState();
            for (int i = start; i < limit; i++) {
                outputs[i] = inputs[i];
            }
            return;
        }

        double frequencyNow = this.frequency;
        if (Double.isNaN(this.computedGainDb)
                || Math.abs(this.currentGainDb - this.computedGainDb) >= GAIN_EPSILON_DB
                || frequencyNow != this.computedFrequency) {
            updateCoefficients(this.currentGainDb, frequencyNow, rate);
        }

        for (int i = start; i < limit; i++) {
            double x0 = inputs[i];
            double y0 = this.b0 * x0 + this.b1 * this.x1 + this.b2 * this.x2
                    - this.a1 * this.y1 - this.a2 * this.y2;
            if (y0 != y0 || Double.isInfinite(y0)) {
                // 来自损坏解码器的非规格化数风暴或 NaN，绝不能把滤波器锁死
                // 在整首曲目剩余部分输出静音（甚至更糟）。
                clearState();
                y0 = x0;
            }
            this.x2 = this.x1;
            this.x1 = x0;
            this.y2 = this.y1;
            this.y1 = y0;
            outputs[i] = y0;
        }
    }

    private void updateCoefficients(double gainDb, double frequencyHz, int rate) {
        this.computedGainDb = gainDb;
        this.computedFrequency = frequencyHz;

        double amplitude = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * Math.max(MIN_FREQUENCY, Math.min(rate * .45, frequencyHz)) / rate;
        double cos = Math.cos(w0);
        // 货架斜率S = 1 ，即仍然没有增益波动的最陡斜率。
        double alpha = Math.sin(w0) * .5 * Math.sqrt(2.0);
        double sqrtA = Math.sqrt(amplitude);
        double shared = 2.0 * sqrtA * alpha;

        double a0 = (amplitude + 1.0) + (amplitude - 1.0) * cos + shared;
        if (a0 == 0.0 || Double.isNaN(a0)) {
            this.b0 = 1.0;
            this.b1 = 0.0;
            this.b2 = 0.0;
            this.a1 = 0.0;
            this.a2 = 0.0;
            return;
        }

        this.b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cos + shared) / a0;
        this.b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cos) / a0;
        this.b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cos - shared) / a0;
        this.a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cos) / a0;
        this.a2 = ((amplitude + 1.0) + (amplitude - 1.0) * cos - shared) / a0;
    }
}
