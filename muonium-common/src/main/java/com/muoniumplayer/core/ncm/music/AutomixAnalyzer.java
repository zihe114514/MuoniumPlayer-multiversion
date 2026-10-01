package com.muoniumplayer.core.ncm.music;

/**
 * 用于放置 automix 交接点的离线分析。纯 DSP：每个方法都接收一个单声道窗口，
 * 该窗口已由 {@link PcmWindowReader} 从已解码缓存文件中读出。
 *
 * <p>这些窗口刻意<em>不</em>取自实时 JSyn 采样。该采样来自
 * 共享的滑动窗口流，因此从正在播放的 deck 中读取任意位置，会在音频线程下
 * 重定位缓冲区并毁掉播放——关于这在实际中造成的故障，
 * 参见 {@link PcmWindowReader}。</p>
 *
 * <p>回答三个问题：
 * <ul>
 *   <li><b>节拍网格</b>——速度加上首拍和首小节的相位，使
 *       交接能落在乐句边界而非小节中间。在时域能量包络上做经典的起音自相关
 *       速度推算（节拍间隔无需 FFT）。</li>
 *   <li><b>前奏结束点</b>——能量第一次持续上升处，即接入曲目真正
 *       开始之处，避免重叠浪费在死寂上。</li>
 *   <li><b>响度</b>——K 加权响度（ITU-R BS.1770-4），让安静的接入曲目可以
 *       被抬升以匹配退出曲目，而不是听起来像掉了。保留普通 RMS 作为
 *       兜底，但它会高估低频厚重的素材：底鼓大、高频缺失的曲目测得
 *       比听起来更响，按它匹配会让混音一冲一顿。</li>
 * </ul>
 *
 * <p>这些都是估计值。每个调用方都把 {@code null}/非正结果当作"无结构
 * 信息"，并退回到普通的等功率交叉淡化。</p>
 */
final class AutomixAnalyzer {

    /** 包络的跳跃步长（采样数，44.1 kHz 下约 11.6 ms）——精度足够用于节拍间隔。 */
    private static final int HOP = 512;
    private static final float MIN_BPM = 70f;
    private static final float MAX_BPM = 180f;
    /** outro 包络使用更粗的步长（约 23 ms）：关注结构而非节拍。 */
    private static final int OUTRO_HOP = 1024;
    /** 进入点选在鼓点进入前一瞬，而不是正好压在鼓点上，听感更好。 */
    private static final float INTRO_BACKOFF_MILLIS = 200f;
    /**
     * 视为完全信任节拍网格的归一化自相关阈值。这是实测而非猜测：
     * 四四拍素材约在 .4-.6，带现场鼓手的乐队约 .3，
     * 而铺底音色或人声独白低于 .1。因此把 .35 映射到 1，可使"这里有节拍"高于
     * 阈值、而"这是 outro"低于阈值。
     */
    private static final float FULL_CONFIDENCE_CORRELATION = .35f;
    /**
     * 抛物线拟合定位自相关峰值的好坏，以一个滞后 bin 的分数计。十分之一个
     * bin 是平滑峰值常用的经验法则；由此得到的相对速度精度等于该值
     * 除以滞后量，即对慢曲（每拍步长更多）比快曲更好。
     */
    private static final double PEAK_FIT_PRECISION = .12;

    /**
     * 谱通量的帧长与帧移，秒。取 BeatRoot 原始参数（44.1kHz 下的 2048 / 512 点）。
     *
     * <p>不能改成本类原有的 {@link #HOP}：22.05kHz 下那是 23.2ms 一格，而 BeatRoot 的整套参数
     * （IOI 聚类宽度 25ms、内窗 40ms）都是按 11.6ms 分辨率的 onset 时刻调出来的，喂 23.2ms 的
     * 量化时刻进去，聚类宽度里只能装下一格，速度假设会碎成一堆。</p>
     */
    private static final double FLUX_FRAME_SECONDS = .04644;
    private static final double FLUX_HOP_SECONDS = .011609;
    /**
     * BeatRoot 网格低于这个置信度就不如退回自相关。
     *
     * <p>比 {@code AutomixPlanner.MIN_GRID_CONFIDENCE}（.28）低一档：这里只是决定「两个测速
     * 方法用哪个」，真正「这条网格能不能用」还要再过一遍那个门槛。</p>
     */
    private static final float MIN_BEATROOT_CONFIDENCE = .18f;
    /** 两种测速方法的速度相差在这个比例内就算彼此印证。 */
    private static final float TEMPO_AGREEMENT = .03f;
    /** 彼此印证时给置信度的加成。 */
    private static final float AGREEMENT_BONUS = 1.15f;
    /** 彼此矛盾时给置信度打的折。 */
    private static final float DISAGREEMENT_DISCOUNT = .70f;
    /** selectivity 到这里就算健康：鼓上带镲时只有一半 onset 会被采纳为拍。 */
    private static final float MIN_HEALTHY_SELECTIVITY = .45f;
    /** 拍点残差按拍长归一后到这个比例就算完全不规整。 */
    private static final double RESIDUAL_TOLERANCE = .06;
    /** 绝对静音门限（dB）。低于这个电平的一段当作「文件里的空白」，不是「安静的音乐」。 */
    private static final double SILENCE_FLOOR_DB = repackage.be.tarsos.dsp.SilenceDetector
            .DEFAULT_SILENCE_THRESHOLD;
    /** 稳健响度参考取到这个分位，避免一声拍手把参考电平顶上去。 */
    private static final double LOUDNESS_PERCENTILE = .95;
    /** 入场门限，相对稳健参考的 dB。-8dB 约等于原来的「峰值 40%」。 */
    private static final double ENTRANCE_DB = -8.0;
    /** 入场之后要维持住的门限，相对稳健参考的 dB。-10.5dB 约等于原来的「峰值 30%」。 */
    private static final double SUSTAIN_DB = -10.5;
    /** {@code FourierMath} 懒初始化静态表的发布锁，见 {@link #spectralFlux}。 */
    private static final Object FFT_TABLE_LOCK = new Object();

    /**
     * 色度分析的块长，22.05 kHz 下约 372 ms。此时 Goertzel bin 约 2.7 Hz 宽，
     * 在分析范围低端仍能分开相邻半音（C3 处相隔 7.8 Hz），
     * 同时又足够短，使一次和弦变化不会涂抹到整个窗口。
     */
    private static final int CHROMA_BLOCK = 8_192;
    /** C3 到 B6，以 MIDI 编号表示。C3 以下 bins 无法再分辨半音；B6 以上则基本
     * 是已统计音符的和声内容。 */
    private static final int CHROMA_LOWEST_MIDI = 48;
    private static final int CHROMA_HIGHEST_MIDI = 83;

    /**
     * Krumhansl-Kessler 调性轮廓：在 probe-tone 实验中测得的调性层级，
     * 至今仍是模板匹配式调性识别器的标准模板。
     */
    private static final double[] MAJOR_PROFILE =
            {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR_PROFILE =
            {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /**
     * 两个调性之间的和声距离，按它们在 Camelot 轮盘上位置的音程索引，
     * 以各自相对大调的半音数表示。
     *
     * <p>这就是轮盘的展开写法。沿轮盘一步是纯五度（7 个半音）或纯四度
     * （5），DJ 正是据此混音；两步是拉伸；而相差半音或三全音是
     * 经典的调性冲突，会让原本干净的混音听起来像坏掉了。上移半音是合理的
     * <em>顺序</em>能量提升手法，但它是两条<em>同时</em>曲目之间最糟的情况，
     * 而这里讨论的正是这种情况。</p>
     *
     * <p>刻意比较相对大调而非字面主音，一举两得。这既符合轮盘的真实定义
     * ——A 小调和 C 大调共用一个槽位——又让整张表对色度模板匹配
     * 必然会犯的那个错误免疫：小调与其
     * 关系大调具有相同的音级集合，再怎么调也分不开；若按字面主音索引，
     * G 大调对 E 小调会被读成相差四个半音
     * （冲突），而它们其实是同一个槽位。</p>
     */
    private static final double[] WHEEL_PENALTY =
            {.00, 1.00, .45, .55, .70, .18, .95, .15, .70, .55, .45, 1.00};
    /**
     * 轮盘同一槽位中大调相对于小调的附加距离，即一对关系大小调。故意设小：
     * 它们的音阶相同，只是重心不同，因此是音色
     * 差异而非冲突——而且必须低于触发转调的阈值，
     * 因为关系大小调正是调性误读时看起来的样子。
     */
    private static final double MODE_MISMATCH_PENALTY = .12;

    private AutomixAnalyzer() {
    }

    /** 估算的速度，以及节拍相位和小节相位，均为距窗口起点的毫秒数。 */
    static final class BeatGrid {
        final float bpm;
        final float phaseMillis;
        final float barPhaseMillis;
        /**
         * 该网格值不值得信任，0..1。
         *
         * <p>速度推算总会返回<em>一个</em>数字：即使在弦乐铺底上自相关也有极大值。
         * 没有它，规划器会高兴地把退出混音量化到 outro 中一条假想的小节线上，
         * 并声称已对拍，毫无理由地把交接移动至多两秒。
         * 它还决定速率预算花在速度上（有真实节拍要锁定）还是音高上
         * （无节拍，调性一致是唯一听得见的东西）。</p>
         */
        final float confidence;
        /**
         * 本次测量自身的相对精度，例如 {@code .006} 表示"该速度大致精确到
         * 0.6 %"。
         *
         * <p>速度来自对整段包络步长的自相关，因此分辨率取决于
         * 一拍有多少个步长：快曲测量所用步长更少、精度更低。规划器需要这个数字，
         * 因为对 deck 的弯曲小于分析误差就不是校正，而是在对噪声下手——
         * 而<em>同一</em>曲目的两个窗口经常相差约一个百分点，
         * 过去这就足以让一对本已同步的曲子背上 1.5 % 的"速度锁定"。</p>
         */
        final float tempoTolerance;

        BeatGrid(float bpm, float phaseMillis, float barPhaseMillis, float confidence) {
            // 为未测量精度的调用方提供的保守默认值。
            this(bpm, phaseMillis, barPhaseMillis, confidence, .01f);
        }

        BeatGrid(float bpm, float phaseMillis, float barPhaseMillis, float confidence,
                 float tempoTolerance) {
            this.bpm = bpm;
            this.phaseMillis = phaseMillis;
            this.barPhaseMillis = barPhaseMillis;
            this.confidence = Float.isNaN(confidence) ? 0f
                    : Math.max(0f, Math.min(1f, confidence));
            this.tempoTolerance = Float.isNaN(tempoTolerance) ? .01f
                    : Math.max(.002f, Math.min(.05f, tempoTolerance));
        }

        float beatPeriodMillis() {
            return 60_000f / bpm;
        }

        /** 该速度下一个 4 拍小节。 */
        float barPeriodMillis() {
            return 4f * 60_000f / bpm;
        }
    }

    /** 估算的调性：主音音级（0 = C … 11 = B）、调式，以及 0..1 的置信度。 */
    static final class KeyEstimate {
        final int tonic;
        final boolean major;
        final float confidence;

        KeyEstimate(int tonic, boolean major, float confidence) {
            this.tonic = ((tonic % 12) + 12) % 12;
            this.major = major;
            this.confidence = Float.isNaN(confidence) ? 0f
                    : Math.max(0f, Math.min(1f, confidence));
        }

        /** 例如 {@code "F#m"}。 */
        String label() {
            return NOTE_NAMES[this.tonic] + (this.major ? "" : "m");
        }
    }

    private static final String[] NOTE_NAMES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /**
     * 窗口的积分 K 加权响度（LUFS）；无法测量时为 {@link Float#NaN}
     * （空窗口、不可用速率，或没有任何信号超过绝对门限）。
     *
     * <p>对单声道遵循 ITU-R BS.1770-4：两级 K 加权滤波（一个约 +4 dB 的高 shelf
     * 代替头部相关传递函数，再加一个 38 Hz 高通），以 75 % 重叠在 400 ms 块上
     * 取均方，−70 LUFS 绝对门限和 −10 LU 相对门限。
     * 滤波器系数按窗口自身采样率推导，而非硬编码为
     * 48 kHz，因此 44.1 kHz 的曲目不会经过失谐的滤波器测量。</p>
     *
     * <p>混音正是按此匹配。两首 RMS 相同的曲目可能相差数 LU，
     * 而几个 LU 正是听众会听成"交接时音量跳了一下"的步长。</p>
     */
    static float kWeightedLoudness(float[] mono, int sampleRate) {
        if (mono == null || mono.length == 0 || sampleRate <= 0) return Float.NaN;

        double[] filtered = kWeight(mono, sampleRate);

        int blockFrames = (int) Math.round(sampleRate * .4);
        if (blockFrames <= 0) return Float.NaN;
        int step = Math.max(1, blockFrames / 4);

        if (filtered.length < blockFrames) {
            // 短于标准的块结构要求：应直接报告未做门限的均方值，而不是
            // 拒绝作答，因为 1.5 s 的重叠仍需要一个可供匹配的电平。
            double mean = meanSquare(filtered, 0, filtered.length);
            return mean > 1e-12 ? (float) (-.691 + 10.0 * Math.log10(mean)) : Float.NaN;
        }

        int blocks = (filtered.length - blockFrames) / step + 1;
        double[] blockPower = new double[blocks];
        int kept = 0;
        double gatedSum = 0.0;
        for (int block = 0; block < blocks; block++) {
            double mean = meanSquare(filtered, block * step, blockFrames);
            blockPower[block] = mean;
            // 绝对门限：-70 LUFS。
            if (mean > 1e-12 && -.691 + 10.0 * Math.log10(mean) > -70.0) {
                gatedSum += mean;
                kept++;
            }
        }
        if (kept == 0) return Float.NaN;

        // 相对门限：比通过绝对门限的所有信号电平低 10 LU。
        double relativeThreshold = -.691 + 10.0 * Math.log10(gatedSum / kept) - 10.0;
        double finalSum = 0.0;
        int finalCount = 0;
        for (int block = 0; block < blocks; block++) {
            double mean = blockPower[block];
            if (mean <= 1e-12) continue;
            double level = -.691 + 10.0 * Math.log10(mean);
            if (level > -70.0 && level > relativeThreshold) {
                finalSum += mean;
                finalCount++;
            }
        }
        if (finalCount == 0) {
            return (float) (-.691 + 10.0 * Math.log10(gatedSum / kept));
        }
        return (float) (-.691 + 10.0 * Math.log10(finalSum / finalCount));
    }

    private static double meanSquare(double[] values, int offset, int length) {
        if (length <= 0) return 0.0;
        double sum = 0.0;
        int end = Math.min(values.length, offset + length);
        for (int i = offset; i < end; i++) {
            sum += values[i] * values[i];
        }
        int used = end - offset;
        return used <= 0 ? 0.0 : sum / used;
    }

    /**
     * 应用 BS.1770 的 K 加权两级。两级都是双二阶，系数取自
     * 标准参考设计（高 shelf：1681.97 Hz，Q 0.7071，+3.9998 dB；高通：38.135 Hz，
     * Q 0.5003），再通过双线性变换映射到实际采样率。
     */
    private static double[] kWeight(float[] mono, int sampleRate) {
        double[] output = new double[mono.length];

        // 第 1 级：高 shelf。
        double shelfGainDb = 3.999843853973347;
        double shelfQ = .7071752369554196;
        double shelfHz = 1681.974450955533;
        double k = Math.tan(Math.PI * shelfHz / sampleRate);
        double vh = Math.pow(10.0, shelfGainDb / 20.0);
        double vb = Math.pow(vh, .4996667741545416);
        double denominator = 1.0 + k / shelfQ + k * k;
        double b0 = (vh + vb * k / shelfQ + k * k) / denominator;
        double b1 = 2.0 * (k * k - vh) / denominator;
        double b2 = (vh - vb * k / shelfQ + k * k) / denominator;
        double a1 = 2.0 * (k * k - 1.0) / denominator;
        double a2 = (1.0 - k / shelfQ + k * k) / denominator;

        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        for (int i = 0; i < mono.length; i++) {
            double x0 = mono[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            output[i] = y0;
        }

        // 第 2 级：高通（RLB 加权）。
        double passQ = .5003270373238773;
        double passHz = 38.13547087602444;
        k = Math.tan(Math.PI * passHz / sampleRate);
        denominator = 1.0 + k / passQ + k * k;
        a1 = 2.0 * (k * k - 1.0) / denominator;
        a2 = (1.0 - k / passQ + k * k) / denominator;
        b0 = 1.0;
        b1 = -2.0;
        b2 = 1.0;

        x1 = 0.0;
        x2 = 0.0;
        y1 = 0.0;
        y2 = 0.0;
        for (int i = 0; i < output.length; i++) {
            double x0 = output[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            output[i] = y0;
        }
        return output;
    }

    /** 窗口的均方根；无可测量内容时为 0。 */
    static float rms(float[] mono) {
        if (mono == null || mono.length == 0) return 0f;
        double squared = 0.0;
        for (float value : mono) {
            squared += (double) value * value;
        }
        return (float) Math.sqrt(squared / mono.length);
    }

    /**
     * 估算单声道窗口的节拍网格。
     *
     * @return 网格；窗口过短或实际静音时为 {@code null}。
     */
    static BeatGrid analyzeBeats(float[] mono, int sampleRate) {
        BeatGrid autocorrelated = autocorrelationGrid(mono, sampleRate);
        // 自相关都立不起网格（窗口太短、或者整段几乎没有能量变化）时不让 BeatRoot 顶上来：
        // 那种素材上「测不出节拍」本身就是正确答案，硬凑一条网格出来只会让 planner 去对齐一条
        // 不存在的小节线。
        if (autocorrelated == null) return null;
        return preferBeatRoot(mono, sampleRate, autocorrelated);
    }

    /**
     * 原来的测速路径：onset 能量通量的自相关。现在只作为 {@link #preferBeatRoot} 的兜底与对照。
     *
     * @return the grid, or {@code null} when the window is too short or effectively silent.
     */
    static BeatGrid autocorrelationGrid(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return null;
        int hops = mono.length / HOP;
        if (hops < 16) return null;

        // 1. 起音包络：半波整流的能量通量足以跟踪打击乐起音
        // 以获取节拍间距，且无需 FFT。
        float[] onset = new float[hops];
        float previousEnergy = 0f;
        double total = 0.0;
        for (int hop = 0; hop < hops; hop++) {
            int base = hop * HOP;
            float energy = 0f;
            for (int index = 0; index < HOP; index++) {
                float value = mono[base + index];
                energy += value * value;
            }
            energy = (float) Math.sqrt(energy / HOP);
            float flux = energy - previousEnergy;
            onset[hop] = flux > 0f ? flux : 0f;
            previousEnergy = energy;
            total += onset[hop];
        }
        if (total < 1e-4) return null;

        // 去均值，让自相关测量周期性而非直流电平。
        float mean = (float) (total / hops);
        for (int hop = 0; hop < hops; hop++) {
            onset[hop] -= mean;
        }

        float envelopeRate = (float) sampleRate / HOP;
        int minLag = Math.max(1, Math.round(envelopeRate * 60f / MAX_BPM));
        int maxLag = Math.min(hops - 1, Math.round(envelopeRate * 60f / MIN_BPM));
        if (maxLag <= minLag) return null;

        // 2. 在速度滞后范围内做自相关。
        int lagCount = maxLag - minLag + 1;
        double[] scores = new double[lagCount];
        int bestLag = minLag;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double correlation = 0.0;
            for (int hop = lag; hop < hops; hop++) {
                correlation += (double) onset[hop] * onset[hop - lag];
            }
            // 略微偏向更快的速度，以抵消长滞后处的自然衰减。
            correlation *= 1.0 + 0.0005 * (maxLag - lag);
            scores[lag - minLag] = correlation;
            if (correlation > bestScore) {
                bestScore = correlation;
                bestLag = lag;
            }
        }

        // 2a. 子步长精化：用胜出滞后及其两个邻点拟合抛物线。
        //
        //     不这样做的话，速度会被量化到整个包络步长，而该量化
        //     粗到足以产生影响：22.05 kHz 下一个步长为 23.2 ms，因此 120 BPM 的一拍是 21.5 个步长，
        //     会四舍五入到 117.5 或 123.1 BPM。因此*同一*曲目的两个窗口可能落到
        //     相反两侧，看起来相差 4.8 %——速度锁定随后会忠实地"纠正"
        //     它，毫无理由地把 deck 弯曲 5 %。对峰值插值可把误差降到半个
        //     百分点以内，这正是速度锁定值得施加的前提。
        double refinedLag = bestLag;
        int peak = bestLag - minLag;
        if (peak > 0 && peak < lagCount - 1) {
            double left = scores[peak - 1];
            double centre = scores[peak];
            double right = scores[peak + 1];
            double curvature = left - 2.0 * centre + right;
            // 曲率为负才说明它确实是极大值；其余情况都是平台或噪声，
            // 保留整数滞后。
            if (curvature < -1e-12) {
                double delta = .5 * (left - right) / curvature;
                if (delta > .5) delta = .5;
                if (delta < -.5) delta = -.5;
                refinedLag = bestLag + delta;
            }
        }
        if (refinedLag < 1.0) refinedLag = 1.0;
        float bpm = (float) (60.0 * envelopeRate / refinedLag);
        if (bpm < MIN_BPM) bpm = MIN_BPM;
        if (bpm > MAX_BPM) bpm = MAX_BPM;

        // 2b. 置信度：胜出滞后处的*归一化*自相关。上面的原始分数
        //     不能用于此——它受快速度加权影响，且随窗口
        //     能量缩放。按两个重叠半窗各自的能量分别归一化，还能消除
        //     长滞后因项数较少而本会承担的系统性惩罚。
        double product = 0.0;
        double energyLate = 0.0;
        double energyEarly = 0.0;
        for (int hop = bestLag; hop < hops; hop++) {
            double late = onset[hop];
            double early = onset[hop - bestLag];
            product += late * early;
            energyLate += late * late;
            energyEarly += early * early;
        }
        double normaliser = Math.sqrt(energyLate * energyEarly);
        double normalised = normaliser > 1e-12 ? product / normaliser : 0.0;
        float confidence = (float) Math.max(0.0,
                Math.min(1.0, normalised / FULL_CONFIDENCE_CORRELATION));

        // 3. 节拍相位：起音总和最高的脉冲串偏移。
        int bestOffset = 0;
        float bestSum = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < bestLag; offset++) {
            float sum = 0f;
            for (int hop = offset; hop < hops; hop += bestLag) {
                sum += onset[hop];
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestOffset = offset;
            }
        }
        float millisPerHop = HOP * 1000f / sampleRate;
        float phaseMillis = bestOffset * millisPerHop;

        // 3b. 小节相位：以四倍周期重新拟合。只测试与节拍相位相差整数拍的四个偏移，
        //     因此结果是真正的强拍（4 拍中的第 1 拍），而非
        //     一拍内的任意点。在此处按几分之拍步进——很容易
        //     不小心做到——会返回根本不在网格上的偏移。
        int barLag = bestLag * 4;
        float tolerance = (float) (PEAK_FIT_PRECISION / refinedLag);
        if (barLag >= hops) {
            return new BeatGrid(bpm, phaseMillis, phaseMillis, confidence, tolerance);
        }

        float bestBarSum = Float.NEGATIVE_INFINITY;
        int bestBarOffset = bestOffset;
        for (int beat = 0; beat < 4; beat++) {
            int offset = bestOffset + beat * bestLag;
            if (offset >= hops) break;
            float sum = 0f;
            for (int hop = offset; hop < hops; hop += barLag) {
                sum += onset[hop];
            }
            if (sum > bestBarSum) {
                bestBarSum = sum;
                bestBarOffset = offset;
            }
        }
        return new BeatGrid(bpm, phaseMillis, bestBarOffset * millisPerHop, confidence, tolerance);
    }

    /**
     * 用 BeatRoot 的多智能体节拍跟踪重测一次，测得比自相关好就用它的结果。
     *
     * <p>自相关只回答「平均隔多久有一下」，相位得靠脉冲串套出来；BeatRoot 直接给出<b>每一拍在哪</b>，
     * 于是相位是真实拍点、速度是这条拍点列表的回归斜率、精度是斜率的标准误 —— 三个数都不再是估的。</p>
     *
     * <p>两者的关系是<b>互相印证</b>而不是替换：速度对得上就给置信度加成，对不上就给 BeatRoot 打折
     * （仍然用它的速度：它才是有拍点证据的那一方），BeatRoot 立不起网格时原样退回自相关的结果。
     * 所以这条路只会让结果更好或者不变，不会凭空变差。</p>
     *
     * @param fallback 自相关测出来的网格，非 {@code null}
     */
    private static BeatGrid preferBeatRoot(float[] mono, int sampleRate, BeatGrid fallback) {
        try {
            int hopSize = (int) Math.round(sampleRate * FLUX_HOP_SECONDS);
            if (hopSize < 32) return fallback;
            int fftSize = 1;
            int wanted = (int) Math.round(sampleRate * FLUX_FRAME_SECONDS);
            while (fftSize < wanted) fftSize <<= 1;
            if (mono.length < fftSize + hopSize * 32) return fallback;

            double[] flux = spectralFlux(mono, fftSize, hopSize);
            if (flux == null) return fallback;
            // track() 会原地归一化，而下面挑小节相位还要用原始通量，所以留一份。
            double[] rawFlux = new double[flux.length];
            System.arraycopy(flux, 0, rawFlux, 0, flux.length);

            double hopSeconds = hopSize / (double) sampleRate;
            repackage.be.tarsos.dsp.beatroot.BeatRootTracker.Result result =
                    repackage.be.tarsos.dsp.beatroot.BeatRootTracker.track(flux, hopSeconds);
            if (result == null) return fallback;

            // 1. 节拍层级。BeatRoot 只保证把周期折进 60~200 BPM，它经常锁在八分音符上
            //    （鼓 + 闭合镲的素材实测就是这样），于是一首 100 BPM 的歌会报 200。
            //    用自相关那个数当锚点来折，比硬折进 [MIN_BPM, MAX_BPM] 靠得住得多：后者对
            //    「88 BPM 被报成 176」这种恰好落在范围内的情况完全无能为力。
            float bpm = octaveNormalise((float) result.bpm(), fallback.bpm);
            while (bpm < MIN_BPM) bpm *= 2f;
            while (bpm > MAX_BPM) bpm /= 2f;
            if (bpm < MIN_BPM || bpm > MAX_BPM) return fallback;

            // 2. 相位。谱通量把 onset 时刻系统性地报早了：一帧覆盖 fftSize 个采样，鼓头落进
            //    第一个<b>包含</b>它的帧，而那一帧的起点最早可以比鼓头早整整一个帧长。取帧中心
            //    作为时刻是通行做法，实测正好把偏差从 -27ms 拉回 0 附近。
            float frameCentreMillis = fftSize * 500f / sampleRate;
            float beatPeriod = 60_000f / bpm;
            double originMillis = result.gridOriginSeconds * 1000.0 + frameCentreMillis;
            // 网格原点可能是负的（回归外推到第 0 拍），取模后补回正区间。
            float phaseMillis = (float) (originMillis % beatPeriod);
            if (phaseMillis < 0f) phaseMillis += beatPeriod;

            // 3. 置信度。这里<b>不能</b>用「拍点拟合得多好」当证据：onset 密的素材上（噪声、
            //    持续音、念白）总能挑出一串间隔相等的 onset 来，拟合残差一样小，
            //    matchedFraction 甚至能到 1.0 —— 实测一段没有节拍的双音 pad 就是这样。
            //    真正能区分「有没有拍」的是两件事：
            //      selectivity：被采纳为拍的 onset 占全部 onset 的比例。真有鼓的素材上 onset
            //        本来就都落在拍或八分位上（实测 0.5~1.0），而噪声与持续音的 onset 满地都是
            //        （实测 0.25~0.34）。0.45 以上都算正常，所以到那里就封顶。
            //      regularity：拍点相对拟合网格的残差，按拍长归一。真鼓 1~2%，念白 5%，噪声 15%。
            float selectivity = result.onsetCount > 0
                    ? result.matchedBeats / (float) result.onsetCount : 0f;
            float selectivityScore = Math.min(1f, selectivity / MIN_HEALTHY_SELECTIVITY);
            float regularity = (float) (1.0 - result.residualSeconds
                    / (RESIDUAL_TOLERANCE * Math.max(1e-6, result.beatIntervalSeconds)));
            if (regularity < 0f) regularity = 0f;
            if (regularity > 1f) regularity = 1f;
            float quality = selectivityScore * regularity;

            boolean agrees = Math.abs(bpm - fallback.bpm) <= fallback.bpm * TEMPO_AGREEMENT;
            float confidence;
            if (agrees) {
                // 两个独立方法测出同一个速度，取高的那个再给一点加成。
                confidence = Math.min(1f,
                        Math.max(quality, fallback.confidence) * AGREEMENT_BONUS);
            } else {
                // 各说各话：取低的那个再打折。仍然用 BeatRoot 的速度与相位（它才有逐拍证据），
                // 但绝不让这种情况下的网格顶着高置信度去触发节奏锁定。
                confidence = Math.min(quality, fallback.confidence) * DISAGREEMENT_DISCOUNT;
            }
            if (confidence < MIN_BEATROOT_CONFIDENCE) return fallback;

            float barPhaseMillis = pickBarPhase(rawFlux, hopSeconds, frameCentreMillis,
                    phaseMillis, beatPeriod);
            return new BeatGrid(bpm, phaseMillis, barPhaseMillis, confidence,
                    (float) result.tempoTolerance);
        } catch (RuntimeException ignored) {
            // 分析永远不能让一次交接失败：测不出来就用自相关那份。
            return fallback;
        }
    }

    /**
     * 在 {@code phaseMillis + k·beatPeriod}（{@code k} 取 0..3）里挑一个当小节头：四个候选各自
     * 隔四拍取一次通量求和，最响的那一路就是 downbeat。
     *
     * <p>只测这四个偏移，所以答案一定落在拍上，而不是拍与拍之间的某个点。</p>
     */
    private static float pickBarPhase(double[] flux, double hopSeconds, float frameCentreMillis,
                                      float phaseMillis, float beatPeriod) {
        double barPeriod = beatPeriod * 4.0;
        float best = phaseMillis;
        double bestSum = Double.NEGATIVE_INFINITY;
        for (int beat = 0; beat < 4; beat++) {
            double offset = phaseMillis + beat * (double) beatPeriod;
            double sum = 0.0;
            int counted = 0;
            for (double at = offset; ; at += barPeriod) {
                // 时刻里含了帧中心的偏移，回到帧下标时要减掉。
                int hop = (int) Math.round((at - frameCentreMillis) / 1000.0 / hopSeconds);
                if (hop >= flux.length) break;
                if (hop >= 0) {
                    sum += flux[hop];
                    counted++;
                }
            }
            // 四个候选覆盖的拍数可能差一格，按拍数取平均才公平。
            double score = counted > 0 ? sum / counted : Double.NEGATIVE_INFINITY;
            if (score > bestSum) {
                bestSum = score;
                best = (float) offset;
            }
        }
        return best;
    }

    /**
     * 谱通量 onset 强度：相邻两帧幅度谱里<b>变大</b>的那部分求和。
     *
     * <p>移植自 TarsosDSP 的 {@code BeatRootSpectralFluxOnsetDetector}（它自己也是 Simon Dixon 的
     * BeatRoot 那一版）。比本类原有的能量通量灵敏得多：能量通量只看总响度的变化，一个和弦换成
     * 另一个等响的和弦它一点反应都没有，而谱通量会看到能量在频率上搬了家。</p>
     *
     * <h3>与上游的不同</h3>
     * <ul>
     *   <li>窗函数用 Hamming 的精确系数（25/46、21/46），上游是 {@code ScaledHammingWindow}
     *       —— 同一个形状乘一个常数。后面 {@code Peaks.normalise} 要做 z-score，常数会被完全约掉，
     *       所以这个差别在结果上是零。</li>
     *   <li>只算幅度不算相位：上游的 {@code powerPhaseFFTBeatRootOnset} 会顺带填一个相位数组，
     *       但通量只用到幅度。</li>
     *   <li>没有上游那套 {@code frames[][]} / {@code energy[]} / {@code ltAverage} 归一化。那些是给
     *       画频谱图和别的检测器用的，通量本身不经过它们。</li>
     * </ul>
     */
    static double[] spectralFlux(float[] mono, int fftSize, int hopSize) {
        if (mono == null || fftSize < 8 || hopSize < 1) return null;
        int frames = (mono.length - fftSize) / hopSize + 1;
        if (frames < 32) return null;

        float[] window = new float[fftSize];
        double factor = 2.0 * Math.PI / (fftSize - 1);
        for (int index = 0; index < fftSize; index++) {
            window[index] = (float) (25.0 / 46.0 - 21.0 / 46.0 * Math.cos(factor * index));
        }

        int bins = fftSize / 2;
        float[] real = new float[fftSize];
        float[] imaginary = new float[fftSize];
        float[] previous = new float[bins];
        float[] magnitude = new float[bins];
        double[] flux = new double[frames];

        for (int frame = 0; frame < frames; frame++) {
            int base = frame * hopSize;
            for (int index = 0; index < fftSize; index++) {
                real[index] = mono[base + index] * window[index];
                imaginary[index] = 0f;
            }
            if (frame == 0) {
                // FourierMath 的正弦表与位反转表是懒初始化的静态数组，没有任何同步。分析跑在
                // 后台线程池上（最多两条并发），第一次调用统一在一个锁里做，另一条线程在同一个
                // 锁上进出一次之后就一定能看到建好的表，后面的帧不用再进锁。
                synchronized (FFT_TABLE_LOCK) {
                    repackage.com.jsyn.util.FourierMath.transform(1, fftSize, real, imaginary);
                }
            } else {
                repackage.com.jsyn.util.FourierMath.transform(1, fftSize, real, imaginary);
            }
            double sum = 0.0;
            for (int bin = 0; bin < bins; bin++) {
                float value = (float) Math.sqrt(real[bin] * real[bin]
                        + imaginary[bin] * imaginary[bin]);
                magnitude[bin] = value;
                if (value > previous[bin]) sum += value - previous[bin];
            }
            // 第 0 帧没有上一帧可比，通量按 0 记。上游那里 previous 是全零，于是第 0 帧的通量等于
            // 整帧幅度之和 —— 上游从文件开头（通常近乎静音）起算所以无害，而本项目的分析窗可能
            // 从副歌中间开始，那个离群的巨值会把后面 z-score 的标准差顶高一大截，真正的鼓点反而
            // 全被 0.35σ 的门限挡掉。
            flux[frame] = frame == 0 ? 0.0 : sum;
            float[] swap = previous;
            previous = magnitude;
            magnitude = swap;
        }
        return flux;
    }

    /**
     * 找到前奏结束处：第一个达到窗口峰值有意义比例并
     * 随后保持约一秒的步长。单独一击鼓点不算进入，因此持续检查
     * 比步进本身更重要。
     *
     * @return 距窗口起点的毫秒偏移；未找到清晰进入点时为 0。
     */
    static float detectIntroEnd(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return 0f;
        int hops = mono.length / HOP;
        if (hops < 16) return 0f;

        float[] levels = new float[hops];
        for (int hop = 0; hop < hops; hop++) {
            levels[hop] = (float) repackage.be.tarsos.dsp.SilenceDetector
                    .calculateRMS(mono, hop * HOP, HOP);
        }

        float millisPerHop = HOP * 1000f / sampleRate;
        // 1. 文件里真正开始有信号的位置。要求连续 120ms 都在绝对静音门限之上，一次爆音不算。
        int audibleHops = Math.max(2, Math.round(120f / millisPerHop));
        int audioStart = firstAudibleHop(levels, audibleHops);
        if (audioStart < 0) return 0f;

        // 2. 稳健响度参考。原来这里用的是窗口峰值，于是整首歌里最响的那一下（一次拍手、一次
        //    军鼓滚奏的尖峰）会把参考电平顶高，入场门限跟着抬上去，前奏就再也测不出来。
        //    取 95 分位数之后单个离群样点影响不到参考。
        float reference = percentile(levels, LOUDNESS_PERCENTILE);
        if (reference < .001f) return 0f;

        float entranceFloor = (float) (reference * Math.pow(10.0, ENTRANCE_DB / 20.0));
        float sustainFloor = (float) (reference * Math.pow(10.0, SUSTAIN_DB / 20.0));
        int sustainHops = Math.max(4, Math.round(1000f / millisPerHop));

        for (int hop = audioStart; hop < hops; hop++) {
            if (levels[hop] < entranceFloor) continue;
            int end = Math.min(hops, hop + sustainHops);
            boolean sustained = true;
            for (int probe = hop; probe < end; probe++) {
                if (levels[probe] < sustainFloor) {
                    sustained = false;
                    break;
                }
            }
            // 稍微后退一点，使进入点落在最后一个安静的拍上，而不是切到
            // downbeat that starts the song. 但退不到曲首之前 —— 那边是文件空白，不是音乐。
            if (sustained) {
                float entry = hop * millisPerHop - INTRO_BACKOFF_MILLIS;
                return Math.max(audioStart * millisPerHop, Math.max(0f, entry));
            }
        }

        // 测不到入场（整首都很轻、或者开头是念白）：至少把文件开头那段空白跳掉。原来这里返回 0，
        // 于是一首前面挂了两秒静音的歌，「跳过前奏」一秒都不跳，新歌淡入时听到的是两秒无声。
        return audioStart * millisPerHop;
    }

    /**
     * 第一个「连续 {@code needed} 格都不是静音」的位置，没有则 {@code -1}。
     *
     * <p>用的是绝对 dB 门限（见 {@link #SILENCE_FLOOR_DB}）。相对门限在这里根本没法用：它只有
     * 「比别处安静」没有「安静」，一段纯数字静音和一段极轻的铺底在它眼里一模一样。</p>
     */
    private static int firstAudibleHop(float[] levels, int needed) {
        int run = 0;
        for (int hop = 0; hop < levels.length; hop++) {
            boolean audible = repackage.be.tarsos.dsp.SilenceDetector
                    .linearToDecibel(levels[hop]) >= SILENCE_FLOOR_DB;
            if (audible) {
                run++;
                if (run >= needed) return hop - run + 1;
            } else {
                run = 0;
            }
        }
        return -1;
    }

    /** 最后一个「连续 {@code needed} 格都不是静音」的段落的末格，没有则 {@code -1}。 */
    private static int lastAudibleHop(float[] levels, int limit, int needed) {
        int run = 0;
        for (int hop = Math.min(limit, levels.length) - 1; hop >= 0; hop--) {
            boolean audible = repackage.be.tarsos.dsp.SilenceDetector
                    .linearToDecibel(levels[hop]) >= SILENCE_FLOOR_DB;
            if (audible) {
                run++;
                if (run >= needed) return hop + run - 1;
            } else {
                run = 0;
            }
        }
        return -1;
    }

    /** {@code fraction} 分位数（0..1），用一份排序副本算，不动入参。 */
    private static float percentile(float[] values, double fraction) {
        if (values.length == 0) return 0f;
        float[] sorted = new float[values.length];
        System.arraycopy(values, 0, sorted, 0, values.length);
        java.util.Arrays.sort(sorted);
        int index = (int) Math.round(fraction * (sorted.length - 1));
        if (index < 0) index = 0;
        if (index >= sorted.length) index = sorted.length - 1;
        return sorted[index];
    }

    /**
     * 找到退出曲目结构性 outro 的起点：最后一段持续高能量区的结尾，
     * 随后是跌入更平静结尾的下降处。DJ 正是在此处混音退出，
     * 并落在音乐边界上，而不是文件字面的最后几秒。
     *
     * <p>退而求其次取最后一段持续的安静间隙（淡出式结尾没有下降可找），
     * 再不行取末尾附近最安静的步长。仅当窗口完全没有任何电平时才返回 {@code -1}，
     * 此时调用方保留其按位置的默认值。</p>
     *
     * @return 距窗口起点的毫秒偏移；无法测量任何内容时为 {@code -1}
     */
    static float detectOutroLanding(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return -1f;
        int hop = OUTRO_HOP;
        int hops = mono.length / hop;
        if (hops < 8) return -1f;

        float[] levels = new float[hops];
        for (int index = 0; index < hops; index++) {
            levels[index] = (float) repackage.be.tarsos.dsp.SilenceDetector
                    .calculateRMS(mono, index * hop, hop);
        }

        float millisPerHop = hop * 1000f / sampleRate;
        // 文件尾巴上那段空白不算音乐。不切掉的话下面两条兜底都会掉进去：「最后一段持续安静的空隙」
        // 会命中静音本身，「最后 15 秒里最轻的一格」更是必然落在静音里，于是混出点被摆到
        // 已经没声音的地方，听起来是旧歌提前几秒就没了。
        int audibleHops = Math.max(2, Math.round(120f / millisPerHop));
        int lastAudible = lastAudibleHop(levels, hops, audibleHops);
        if (lastAudible < 0) return -1f;
        hops = lastAudible + 1;
        if (hops < 8) return -1f;

        double sum = 0.0;
        float peak = 0f;
        for (int index = 0; index < hops; index++) {
            sum += levels[index];
            if (levels[index] > peak) peak = levels[index];
        }
        float mean = (float) (sum / hops);
        if (mean < 1e-4f) return -1f;
        float highFloor = peak * .70f;
        float dropFloor = mean * .55f;
        int neededHigh = Math.max(2, Math.round(2000f / millisPerHop));

        // 最后一段持续响亮区的结束点——最后的副歌或 drop。
        int lastHighEnd = -1;
        int index = 0;
        while (index < hops) {
            if (levels[index] < highFloor) {
                index++;
                continue;
            }
            int start = index;
            while (index < hops && levels[index] >= highFloor) index++;
            if (index - start >= neededHigh) lastHighEnd = index;
        }
        if (lastHighEnd >= 0 && lastHighEnd < hops - 1) {
            int lookahead = Math.max(2, Math.round(300f / millisPerHop));
            for (int probe = lastHighEnd; probe < hops - lookahead; probe++) {
                boolean below = true;
                for (int inner = probe; inner < probe + lookahead; inner++) {
                    if (levels[inner] >= dropFloor) {
                        below = false;
                        break;
                    }
                }
                if (below) return probe * millisPerHop;
            }
            // 响亮区一直延续到结尾：直接在它之后混音退出。
            return lastHighEnd * millisPerHop;
        }

        // 淡出式结尾：最后一段持续的安静间隙是仅有的呼吸点。
        int needed = Math.max(1, Math.round(600f / millisPerHop));
        for (int probe = hops - needed; probe >= 0; probe--) {
            boolean quiet = true;
            for (int inner = probe; inner < probe + needed; inner++) {
                if (levels[inner] > dropFloor) {
                    quiet = false;
                    break;
                }
            }
            if (quiet) return probe * millisPerHop;
        }

        // 密集尾声：在最后 15 秒内取最安静的瞬间。
        int scanFrom = Math.max(0, hops - Math.round(15_000f / millisPerHop));
        int quietest = scanFrom;
        for (int probe = scanFrom; probe < hops; probe++) {
            if (levels[probe] < levels[quietest]) quietest = probe;
        }
        return quietest * millisPerHop;
    }

    /**
     * 把速度折回到与参考相同的八度，使半速/倍速检测不会
     * 看起来像失配。
     */
    static float octaveNormalise(float bpm, float reference) {
        if (bpm <= 1f || reference <= 1f) return bpm;
        float best = bpm;
        float bestDistance = Math.abs(bpm - reference);
        float[] candidates = {bpm * .5f, bpm * 2f, bpm * (2f / 3f), bpm * 1.5f};
        for (float candidate : candidates) {
            if (candidate < MIN_BPM || candidate > MAX_BPM) continue;
            float distance = Math.abs(candidate - reference);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 窗口的十二 bin 色度（音级）轮廓，归一化为和为 1。
     *
     * <p>用一组 Goertzel 谐振器而非 FFT 计算：只需要 36 个频率，
     * 从 C3 到 B6 每个半音一个，Goertzel 对每个频率每个采样只需一次乘加，
     * 无需变换、无需窗函数决策、无需 bin 到音符的插值。幅度按求和而非按功率累加，
     * 因为在功率域里一个响亮的低音音符会淹没它上方的整个和弦，
     * 轮廓便不再描述和声。</p>
     *
     * @return 轮廓；窗口过短或不带任何调性能量时为 {@code null}
     */
    static float[] chroma(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return null;
        int block = Math.min(CHROMA_BLOCK, mono.length);
        if (block < 2_048) return null;
        int blocks = mono.length / block;
        if (blocks < 1) return null;

        int notes = CHROMA_HIGHEST_MIDI - CHROMA_LOWEST_MIDI + 1;
        double[] coefficients = new double[notes];
        double nyquistGuard = sampleRate * .45;
        for (int note = 0; note < notes; note++) {
            double frequency = 440.0 * Math.pow(2.0, (CHROMA_LOWEST_MIDI + note - 69) / 12.0);
            coefficients[note] = frequency >= nyquistGuard
                    ? Double.NaN
                    : 2.0 * Math.cos(2.0 * Math.PI * frequency / sampleRate);
        }

        float[] chroma = new float[12];
        double total = 0.0;
        for (int index = 0; index < blocks; index++) {
            int base = index * block;
            for (int note = 0; note < notes; note++) {
                double coefficient = coefficients[note];
                if (Double.isNaN(coefficient)) continue;
                double s1 = 0.0;
                double s2 = 0.0;
                for (int n = 0; n < block; n++) {
                    double s = mono[base + n] + coefficient * s1 - s2;
                    s2 = s1;
                    s1 = s;
                }
                double power = s2 * s2 + s1 * s1 - coefficient * s1 * s2;
                if (Double.isNaN(power) || power <= 0.0) continue;
                float magnitude = (float) (Math.sqrt(power) / block);
                chroma[(CHROMA_LOWEST_MIDI + note) % 12] += magnitude;
                total += magnitude;
            }
        }
        if (total < 1e-9) return null;
        for (int bin = 0; bin < 12; bin++) {
            chroma[bin] = (float) (chroma[bin] / total);
        }
        return chroma;
    }

    /**
     * 通过把窗口色度轮廓与 Krumhansl-Kessler 大调、小调模板的 24 个旋转做相关，
     * 估算窗口调性。
     *
     * <p>置信度刻意需要同时满足两点：与胜出者的相关度要高，
     * <em>且</em>与第二名要有差距。一个对 C 大调和 G 大调拟合得几乎一样好的窗口，
     * 并未识别出调性，它识别出的是一个自然音集合；据此转调曲目，
     * 正是一次"和声"混音最终错了一个半音的原因。</p>
     *
     * @return 估计值；无法测量调性时为 {@code null}
     */
    static KeyEstimate analyzeKey(float[] mono, int sampleRate) {
        float[] chroma = chroma(mono, sampleRate);
        if (chroma == null) return null;

        double best = -2.0;
        double runnerUp = -2.0;
        int bestTonic = 0;
        boolean bestMajor = true;
        for (int tonic = 0; tonic < 12; tonic++) {
            for (int mode = 0; mode < 2; mode++) {
                double score = correlateProfile(chroma, mode == 0 ? MAJOR_PROFILE : MINOR_PROFILE, tonic);
                if (score > best) {
                    runnerUp = best;
                    best = score;
                    bestTonic = tonic;
                    bestMajor = mode == 0;
                } else if (score > runnerUp) {
                    runnerUp = score;
                }
            }
        }
        if (best <= 0.0) return null;

        double margin = Math.max(0.0, best - runnerUp);
        double confidence = Math.min(1.0, best / .55) * Math.min(1.0, margin / .08);
        return new KeyEstimate(bestTonic, bestMajor, (float) confidence);
    }

    /** 色度与旋转后使其主音落在 {@code rotation} 上的模板之间的 Pearson 相关。 */
    private static double correlateProfile(float[] chroma, double[] profile, int rotation) {
        double meanChroma = 0.0;
        double meanProfile = 0.0;
        for (int bin = 0; bin < 12; bin++) {
            meanChroma += chroma[bin];
            meanProfile += profile[bin];
        }
        meanChroma /= 12.0;
        meanProfile /= 12.0;

        double covariance = 0.0;
        double varianceChroma = 0.0;
        double varianceProfile = 0.0;
        for (int degree = 0; degree < 12; degree++) {
            double a = chroma[(degree + rotation) % 12] - meanChroma;
            double b = profile[degree] - meanProfile;
            covariance += a * b;
            varianceChroma += a * a;
            varianceProfile += b * b;
        }
        double denominator = Math.sqrt(varianceChroma * varianceProfile);
        return denominator > 1e-12 ? covariance / denominator : 0.0;
    }

    /**
     * 在退出曲目已按 {@code semitoneShift} 转调后，把 {@code incoming} 叠在 {@code outgoing} 之上时
     * 的和声惩罚，取值 [0,1]。0 表示"二者调性相同"，
     * 1 表示"相差一个半音"，这是两条同时播放的曲目之间最糟的情况。
     *
     * <p>任一侧调性未知时返回中性的 .5 而非 0："无信息"绝不能被
     * 误认为"测得为兼容"，否则每个无法分析的配对都会看起来像完美的
     * 和声匹配，并给自己争取到最长的重叠。</p>
     */
    static float harmonicPenalty(KeyEstimate outgoing, KeyEstimate incoming, int semitoneShift) {
        if (outgoing == null || incoming == null) return .5f;
        int from = ((relativeMajorTonic(outgoing) + semitoneShift) % 12 + 12) % 12;
        int to = relativeMajorTonic(incoming);
        int interval = ((to - from) % 12 + 12) % 12;
        double penalty = WHEEL_PENALTY[interval];
        if (outgoing.major != incoming.major) penalty += MODE_MISMATCH_PENALTY;
        return (float) Math.min(1.0, penalty);
    }

    /** 轮盘槽位：小调与上方小三度的大调位于同一槽位。 */
    private static int relativeMajorTonic(KeyEstimate key) {
        return key.major ? key.tonic : (key.tonic + 3) % 12;
    }

    /**
     * 在 ±{@code maxShift} 范围内，对 {@code outgoing} 的整半音转调，
     * 与 {@code incoming} 最吻合。
     *
     * <p>平局取 0：对正在退出的 deck 做弯曲，只有能换来真实
     * 改进时才值得。调用方还须自行判断该改进是否值得这次音高移动——
     * 本方法回答"哪个移位最好"，而不是"是否该移位"。</p>
     */
    static int bestSemitoneShift(KeyEstimate outgoing, KeyEstimate incoming, int maxShift) {
        if (outgoing == null || incoming == null) return 0;
        int limit = Math.max(0, Math.min(6, maxShift));
        int best = 0;
        float bestPenalty = harmonicPenalty(outgoing, incoming, 0);
        for (int shift = -limit; shift <= limit; shift++) {
            if (shift == 0) continue;
            float penalty = harmonicPenalty(outgoing, incoming, shift);
            // 严格更优，且 +1 与 -1 平局时取更近的移位。
            if (penalty < bestPenalty - 1e-4f
                    || (Math.abs(penalty - bestPenalty) <= 1e-4f && Math.abs(shift) < Math.abs(best))) {
                bestPenalty = penalty;
                best = shift;
            }
        }
        return best;
    }
}
