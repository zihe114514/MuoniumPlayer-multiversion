package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 场景声学:按听者所处的声学环境实时修饰播放输出。全部效果在音频线程上对
 * 交错的 PCM 帧原地完成。
 *
 * <h2>网络(房间 = 早期反射 + 晚期尾)</h2>
 * <ul>
 *   <li><b>早期反射 = 真抽头</b>:玩家即声源,射线确认可达的每条反射是一次
 *       真实回声 —— 延迟/能量/声像由 {@link RoomPlan} 交付。抽头写进一条环形
 *       延迟线,按各自位置读出、按左右能量相加;房间变化时新旧两组抽头
 *       0.4s 交叉淡化,不爆音。真实感主要来自这里:离散回声的空间图案是
 *       任何「混响算法」都伪造不出来的房间指纹。</li>
 *   <li><b>晚期尾 = Freeverb 拓扑</b>:每耳 8 梳并联 + 4 全通串联(经典调参,
 *       双耳错开 23 样本去相关)。回授不写死:按解析衰减时长导出
 *       g = 10<sup>−3·t̄/RT60</sup>(夹 0.30~0.93);梳内一阶低通由音色驱动。
 *       输入按经典注入量(0.015)进网络 —— 高回授下的动态余量全靠它。</li>
 *   <li><b>湿声入口直流阻断</b>:回授网络对直流的增益是 1/(1−g),一点直流
 *       偏置就会撑爆内部动态;进网络前先过一节一阶高通(≈10Hz)。</li>
 *   <li><b>干声让位</b>:湿声大时干声按计划压低(duck),混响才不被满幅干声
 *       掩蔽;整体响度(浸液/降水)最后统一乘。</li>
 *   <li><b>频带上限</b>:每声道一条双二阶低通(RBJ,Q=1/&radic;2),截止在
 *       对数域滑移 —— 浸液那条链,与混响无关。</li>
 * </ul>
 *
 * <h2>目标/现状分离与实时纪律</h2>
 * <p>游戏线程只通过 {@link #applyTarget} 交付不可变的 {@link RoomPlan}(volatile
 * 引用交接,无锁);音频线程每个缓冲朝目标收敛一次。{@link #render} 稳态零分配、
 * 零锁;延迟线只在采样率变化时重建。全部静息时整个方法提前返回。</p>
 */
public final class SceneAcoustics {

    // ---- 调音常量(前置:字段初始化要读它们) ----

    /** 静息态的频带上限:高于它的采样率一半,等同「不压」。 */
    private static final double BAND_OPEN_HZ = 20000.0;
    /** 目标频带的下限:再低就纯粹是轰隆声了,到此为止。 */
    private static final double MIN_BAND_HZ = 120.0;
    private static final double LOG_BAND_OPEN = Math.log10(BAND_OPEN_HZ);

    /** 参数收敛时间常数(秒)。频带慢一点听感自然,其余共用稍快的一档。 */
    private static final double BAND_TAU_SEC = 0.22;
    private static final double WET_TAU_SEC = 0.30;

    /** 湿声低于此值视为关:省掉整条网络。 */
    private static final float WET_FLOOR = 0.003f;
    private static final double BUTTERWORTH_Q = 0.7071067811865476;

    /** Freeverb 梳调参(样点 @44.1kHz),互质错开;右耳整体 +23 样本去相关。 */
    private static final int[] COMB_TUNING = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    /** Freeverb 全通调参(样点 @44.1kHz)。 */
    private static final int[] AP_TUNING = {556, 441, 341, 225};
    private static final int COMBS = COMB_TUNING.length;
    private static final int APS = AP_TUNING.length;
    private static final double EARS_OFFSET_SAMPLES = 23.0;
    /** 梳均值折秒:RT60 → 回授换算用的单一基准(8 梳均值 ≈ 1403 样 @44.1k)。 */
    private static final double COMB_MEAN_SEC = 0.0318;
    /** 经典注入量:回授网络的输入增益,高回授下的动态余量来源。 */
    private static final double COMB_INPUT_GAIN = 0.015;
    private static final double AP_FEEDBACK = 0.5;
    /** 回授上下限:下限保「衰减极短时网络还有东西可响」,上限保稳定。 */
    private static final double REGAIN_MIN = 0.30;
    private static final double REGAIN_MAX = 0.93;
    /** 尾部输出补偿:经典注入量很小,湿声通路按此放大到可闻量级。 */
    private static final double TAIL_MAKEUP = 1.6;

    /** 预延迟上限(毫秒):更远的「墙」交给衰减时长表达。 */
    private static final double PRE_DELAY_MAX_MS = 60.0;
    /** 早期反射抽头窗口(秒):更晚的反射归尾部管。 */
    private static final double ER_WINDOW_SEC = 0.16;
    /** 抽头组交叉淡化时长(秒)。 */
    private static final double TAP_FADE_SEC = 0.4;

    // ---- 目标量(游戏线程写,音频线程读) ----

    private static volatile double targetLogBand = LOG_BAND_OPEN;
    private static volatile float targetWet = 0f;
    private static volatile float targetLoudness = 1f;
    private static volatile float targetDryGain = 1f;
    private static volatile float targetPreDelaySec = 0f;
    private static volatile float targetDecaySec = 0f;
    private static volatile float targetTone = 0.28f;
    /** 不可变房间计划(volatile 引用交接);抽头延迟/能量/声像的真来源。 */
    private static volatile RoomPlan targetPlan = RoomPlan.idle();

    private static volatile long targetGeneration;

    // ---- 现状(仅音频线程触碰) ----

    private static double logBand = LOG_BAND_OPEN;
    private static float wet = 0f;
    private static float loudness = 1f;
    private static float dryGain = 1f;
    private static float preDelaySec;
    private static float decaySec;
    private static float tone = 0.28f;

    private static int ownedRate = -1;
    private static double lastCoefBand = -1.0;
    /** 一次性诊断:「声场生效」只在静息 → 激活的跳变时打一条(回静息复位)。 */
    private static boolean activeLogged;

    /** 每声道一条低通;状态 [ch][x1,x2,y1,y2]。 */
    private static double[][] bandState = new double[0][];
    private static double cB0, cB1, cB2, cA1, cA2;

    /** 湿声入口直流阻断的记忆位。 */
    private static double dcX, dcY;

    /** 尾部预延迟线(可变读距)。 */
    private static float[] preDelayBuf = new float[0];
    private static int preDelayPos;
    private static int preDelaySamples;

    /** 早期反射环(单条,mono 写入,抽头按位置读)。 */
    private static float[] erRing = new float[0];
    private static int erPos;
    /** 抽头组容量:与推导侧的截断上限同级;预分配,稳态零运行时分配。 */
    private static final int TAP_CAPACITY = 32;
    /** 抽头组 A(生效)与 B(淡入中):位置=采样点,能量已分左右,count 为有效条数。 */
    private static final int[] tapPosA = new int[TAP_CAPACITY];
    private static final float[] tapGLA = new float[TAP_CAPACITY];
    private static final float[] tapGRA = new float[TAP_CAPACITY];
    private static int tapCountA;
    private static final int[] tapPosB = new int[TAP_CAPACITY];
    private static final float[] tapGLB = new float[TAP_CAPACITY];
    private static final float[] tapGRB = new float[TAP_CAPACITY];
    private static int tapCountB;
    private static boolean tapFading;
    private static float tapFadeW = 1f;
    private static RoomPlan lastPlanRef;

    /** Freeverb:双耳 × 8 梳 + 双耳 × 4 全通。 */
    private static float[][][] combBuf = new float[2][COMBS][];
    private static int[][] combIdx = new int[2][COMBS];
    private static float[][] combStore = new float[2][COMBS];
    private static float[][][] apBuf = new float[2][APS][];
    private static int[][] apIdx = new int[2][APS];
    private static double combRegain = 0.5;

    private SceneAcoustics() {
    }

    // ════════════════════════ 游戏线程侧 ════════════════════════

    /** 交付本轮房间计划(不可变;null 按静息处理)。 */
    public static void applyTarget(RoomPlan plan) {
        final RoomPlan safe = plan == null ? RoomPlan.idle() : plan;
        targetLogBand = safe.bandHz >= BAND_OPEN_HZ
                ? LOG_BAND_OPEN
                : Math.log10(Math.max(MIN_BAND_HZ, Math.min(BAND_OPEN_HZ, safe.bandHz)));
        targetWet = clamp01(safe.wet);
        targetLoudness = clamp01(safe.loudness);
        targetDryGain = clamp01(safe.dryGain);
        targetPreDelaySec = Math.max(0f, Math.min((float) (PRE_DELAY_MAX_MS / 1000.0), safe.predelaySec));
        targetDecaySec = Math.max(0f, Math.min(4.6f, safe.decaySec));
        targetTone = (float) Math.max(0.10, Math.min(0.45, safe.tone));
        targetPlan = safe;
        targetGeneration++;
    }

    /** 全部目标回到静息态(关掉、不在世界、配置为空)。 */
    public static void clearTarget() {
        applyTarget(RoomPlan.idle());
    }

    /** 曲目切换 / 拖动进度:把现状硬对齐到目标,并清空全部滤波与延迟状态。 */
    public static void flush() {
        logBand = targetLogBand;
        wet = targetWet;
        loudness = targetLoudness;
        dryGain = targetDryGain;
        preDelaySec = targetPreDelaySec;
        decaySec = targetDecaySec;
        tone = targetTone;
        for (double[] ch : bandState) java.util.Arrays.fill(ch, 0.0);
        if (preDelayBuf.length > 0) java.util.Arrays.fill(preDelayBuf, 0f);
        if (erRing.length > 0) java.util.Arrays.fill(erRing, 0f);
        for (float[] line : combBuf[0]) java.util.Arrays.fill(line, 0f);
        for (float[] line : combBuf[1]) java.util.Arrays.fill(line, 0f);
        for (float[] line : apBuf[0]) java.util.Arrays.fill(line, 0f);
        for (float[] line : apBuf[1]) java.util.Arrays.fill(line, 0f);
        for (float[] s : combStore) java.util.Arrays.fill(s, 0f);
        preDelayPos = 0;
        erPos = 0;
        java.util.Arrays.fill(combIdx[0], 0);
        java.util.Arrays.fill(combIdx[1], 0);
        java.util.Arrays.fill(apIdx[0], 0);
        java.util.Arrays.fill(apIdx[1], 0);
        dcX = 0;
        dcY = 0;
        lastPlanRef = null;
        tapCountA = 0;
        tapCountB = 0;
        tapFading = false;
        tapFadeW = 1f;
        lastCoefBand = -1.0;
    }

    // ════════════════════════ 音频线程侧 ════════════════════════

    /** 处理一个输出缓冲;湿声送前两声道,其余声道只吃响度。 */
    public static void render(double[] interleaved, int channels, int sampleRate) {
        if (interleaved == null || channels <= 0 || interleaved.length < channels) {
            return;
        }
        final int ch = Math.min(channels, 2);
        final int frames = interleaved.length / channels;
        if (frames <= 0) {
            return;
        }

        final double tLogBand = targetLogBand;
        final float tWet = targetWet;
        final float tLoudness = targetLoudness;
        final float tDry = targetDryGain;
        final float tPreDelay = targetPreDelaySec;
        final float tDecay = targetDecaySec;
        final float tTone = targetTone;

        final double bandStep = 1.0 - Math.exp(-frames / (sampleRate * BAND_TAU_SEC));
        final float wetStep = (float) (1.0 - Math.exp(-frames / (sampleRate * WET_TAU_SEC)));
        logBand += (tLogBand - logBand) * bandStep;
        wet += (tWet - wet) * wetStep;
        loudness += (tLoudness - loudness) * wetStep;
        dryGain += (tDry - dryGain) * wetStep;
        preDelaySec += (tPreDelay - preDelaySec) * wetStep;
        decaySec += (tDecay - decaySec) * wetStep;
        tone += (tTone - tone) * wetStep;

        final boolean bandActive = logBand < LOG_BAND_OPEN - 0.001;
        final boolean wetActive = wet > WET_FLOOR;
        final boolean loudActive = loudness < 0.999f || dryGain < 0.999f;
        if (!bandActive && !wetActive && !loudActive) {
            activeLogged = false; // 回到静息:下次再生效时允许再报一条。
            return;
        }
        if (!activeLogged) {
            activeLogged = true;
            System.out.println("[MuoniumPlayer/Acoustics] 声场生效: " + targetPlan);
        }

        if (sampleRate != ownedRate) {
            rebuild(sampleRate, ch);
        }
        if (bandActive && logBand != lastCoefBand) {
            designLowpass(Math.pow(10.0, logBand), sampleRate);
            lastCoefBand = logBand;
        }

        // 回授与预延迟:每缓冲整定一次。
        combRegain = decaySec > 0.05
                ? Math.max(REGAIN_MIN, Math.min(REGAIN_MAX,
                        Math.pow(10.0, -3.0 * COMB_MEAN_SEC / decaySec)))
                : REGAIN_MIN;
        preDelaySamples = Math.min(preDelayBuf.length - 1, (int) (preDelaySec * sampleRate));

        // 抽头组管理:计划换了就按需交叉淡化;只有延迟位置变才淡,能量就地更新。
        // A 组为空(起播/flush 后)直接采用,不从零淡起。
        final RoomPlan plan = targetPlan;
        if (plan != lastPlanRef) {
            lastPlanRef = plan;
            if (tapCountA == 0 && !tapFading) {
                buildTapSet(tapPosA, tapGLA, tapGRA, plan, sampleRate);
                tapCountA = plan.tapCount;
            } else if (!tapFading && tapsMatchActive(plan)) {
                copyTapGains(plan);
            } else {
                buildTapSet(tapPosB, tapGLB, tapGRB, plan, sampleRate);
                tapCountB = plan.tapCount;
                tapFading = true;
                tapFadeW = 0f;
            }
        }
        if (tapFading) {
            tapFadeW += frames / (float) (sampleRate * TAP_FADE_SEC);
            if (tapFadeW >= 1f) {
                System.arraycopy(tapPosB, 0, tapPosA, 0, tapCountB);
                System.arraycopy(tapGLB, 0, tapGLA, 0, tapCountB);
                System.arraycopy(tapGRB, 0, tapGRA, 0, tapCountB);
                tapCountA = tapCountB;
                tapFading = false;
                tapFadeW = 1f;
            }
        }
        final float fadeW = tapFading ? tapFadeW : 0f;

        final double[] state0 = bandState.length > 0 ? bandState[0] : null;
        final double[] state1 = ch > 1 && bandState.length > 1 ? bandState[1] : null;
        final int ringLen = erRing.length;
        final float dampCoef = tone;
        final float tailIn = (float) (COMB_INPUT_GAIN);

        int idx = 0;
        for (int frame = 0; frame < frames; frame++) {
            double left = interleaved[idx];
            double right = ch > 1 ? interleaved[idx + 1] : left;

            if (bandActive) {
                left = biquad(left, state0);
                if (state1 != null) {
                    right = biquad(right, state1);
                }
            }

            float wetL = 0f;
            float wetR = 0f;
            if (wetActive) {
                final double mono = (left + right) * 0.5;
                final double blocked = mono - dcX + 0.9985 * dcY;
                dcX = mono;
                dcY = blocked;

                // 早期反射:mono 写环,抽头按位置读
                erRing[erPos] = (float) blocked;
                erPos = erPos + 1 == ringLen ? 0 : erPos + 1;

                float erL = 0f;
                float erR = 0f;
                for (int i = 0; i < tapCountA; i++) {
                    int read = erPos - tapPosA[i];
                    if (read < 0) {
                        read += ringLen;
                    }
                    final float echo = erRing[read];
                    erL += echo * tapGLA[i];
                    erR += echo * tapGRA[i];
                }
                if (tapFading) {
                    float erLB = 0f;
                    float erRB = 0f;
                    for (int i = 0; i < tapCountB; i++) {
                        int read = erPos - tapPosB[i];
                        if (read < 0) {
                            read += ringLen;
                        }
                        final float echo = erRing[read];
                        erLB += echo * tapGLB[i];
                        erRB += echo * tapGRB[i];
                    }
                    erL = erL * (1f - fadeW) + erLB * fadeW;
                    erR = erR * (1f - fadeW) + erRB * fadeW;
                }

                // 尾部:预延迟 → 每耳 Freeverb
                int preRead = preDelayPos - preDelaySamples;
                if (preRead < 0) {
                    preRead += preDelayBuf.length;
                }
                final float delayed = preDelayBuf[preRead];
                preDelayBuf[preDelayPos] = (float) blocked;
                preDelayPos = preDelayPos + 1 == preDelayBuf.length ? 0 : preDelayPos + 1;

                final float driven = delayed * tailIn;
                wetL = (erL + freeverb(0, driven, dampCoef) * (float) TAIL_MAKEUP) * wet;
                wetR = (erR + freeverb(1, driven, dampCoef) * (float) TAIL_MAKEUP) * wet;
            }

            if (ch > 1) {
                interleaved[idx] = (left * dryGain + wetL) * loudness;
                interleaved[idx + 1] = (right * dryGain + wetR) * loudness;
            } else {
                interleaved[idx] = (left * dryGain + (wetL + wetR) * 0.5f) * loudness;
            }
            idx += channels;
        }
    }

    /** 单耳 Freeverb:8 梳并联(带一阶低通回授)→ 4 全通串联。 */
    private static float freeverb(int ear, float input, float dampCoef) {
        float out = 0f;
        final float[][] earBufs = combBuf[ear];
        final int[] idxs = combIdx[ear];
        final float[] stores = combStore[ear];
        for (int c = 0; c < COMBS; c++) {
            final float[] buf = earBufs[c];
            int pos = idxs[c];
            final float echo = buf[pos];
            float store = stores[c];
            store += (echo - store) * dampCoef;
            stores[c] = store;
            buf[pos] = input + store * (float) combRegain;
            idxs[c] = pos + 1 == buf.length ? 0 : pos + 1;
            out += echo;
        }
        final float[][] earAps = apBuf[ear];
        final int[] apIs = apIdx[ear];
        for (int a = 0; a < APS; a++) {
            final float[] buf = earAps[a];
            int pos = apIs[a];
            final float stored = buf[pos];
            buf[pos] = out + stored * (float) AP_FEEDBACK;
            out = stored - out;
            apIs[a] = pos + 1 == buf.length ? 0 : pos + 1;
        }
        return out;
    }

    private static double biquad(double in, double[] s) {
        final double out = cB0 * in + cB1 * s[0] + cB2 * s[1] - cA1 * s[2] - cA2 * s[3];
        s[1] = s[0];
        s[0] = in;
        s[3] = s[2];
        s[2] = out;
        return out;
    }

    /** RBJ 低通,Q = 1/√2(Butterworth,Q 值再高点低点都会出现可闻的谷/峰)。 */
    private static void designLowpass(double fc, int rate) {
        final double capped = Math.min(fc, rate * 0.475);
        final double w0 = 2.0 * Math.PI * capped / rate;
        final double cosw = Math.cos(w0);
        final double alpha = Math.sin(w0) / (2.0 * BUTTERWORTH_Q);

        final double a0 = 1.0 + alpha;
        cB0 = ((1.0 - cosw) / 2.0) / a0;
        cB1 = (1.0 - cosw) / a0;
        cB2 = cB0;
        cA1 = (-2.0 * cosw) / a0;
        cA2 = (1.0 - alpha) / a0;
    }

    /** 当前生效抽头组与计划的延迟位置是否一致(能量允许漂移,位置不允许)。 */
    private static boolean tapsMatchActive(RoomPlan plan) {
        if (tapCountA != plan.tapCount) {
            return false;
        }
        for (int i = 0; i < plan.tapCount; i++) {
            if (Math.abs(tapSamplePos(plan, i, ownedRate) - tapPosA[i]) > 2) {
                return false;
            }
        }
        return true;
    }

    private static void copyTapGains(RoomPlan plan) {
        for (int i = 0; i < plan.tapCount; i++) {
            tapGLA[i] = plan.tapGainL[i];
            tapGRA[i] = plan.tapGainR[i];
        }
    }

    private static void buildTapSet(int[] posOut, float[] glOut, float[] grOut,
                                    RoomPlan plan, int rate) {
        for (int i = 0; i < plan.tapCount; i++) {
            posOut[i] = tapSamplePos(plan, i, rate);
            glOut[i] = plan.tapGainL[i];
            grOut[i] = plan.tapGainR[i];
        }
    }

    private static int tapSamplePos(RoomPlan plan, int i, int rate) {
        final int len = erRing.length;
        final int pos = Math.round(plan.tapDelaySec[i] * rate);
        return Math.max(1, Math.min(len - 1, pos));
    }

    // ---- 资源 ----

    /** 采样率变了:重建各条延迟线并复位状态;抽头组按当前计划重建。 */
    private static void rebuild(int rate, int channels) {
        ownedRate = rate;
        bandState = new double[channels][4];
        for (double[] s : bandState) java.util.Arrays.fill(s, 0.0);

        preDelayBuf = new float[Math.max(2, (int) (PRE_DELAY_MAX_MS * rate / 1000.0) + 2)];
        preDelayPos = 0;

        erRing = new float[Math.max(4, (int) (ER_WINDOW_SEC * rate) + 2)];
        erPos = 0;
        final RoomPlan plan = targetPlan;
        buildTapSet(tapPosA, tapGLA, tapGRA, plan, rate);
        tapCountA = plan.tapCount;
        tapCountB = 0;
        tapFading = false;
        tapFadeW = 1f;
        lastPlanRef = plan;

        final double srScale = rate / 44100.0;
        for (int ear = 0; ear < 2; ear++) {
            for (int c = 0; c < COMBS; c++) {
                combBuf[ear][c] = new float[Math.max(4,
                        (int) Math.round((COMB_TUNING[c] + ear * EARS_OFFSET_SAMPLES) * srScale))];
                combIdx[ear][c] = 0;
            }
            java.util.Arrays.fill(combStore[ear], 0f);
            for (int a = 0; a < APS; a++) {
                apBuf[ear][a] = new float[Math.max(2,
                        (int) Math.round((AP_TUNING[a] + ear * EARS_OFFSET_SAMPLES) * srScale))];
                apIdx[ear][a] = 0;
            }
        }
        lastCoefBand = -1.0;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 静息频带(Hz),供推导侧判断「是否真的压了」。 */
    public static double bandOpenHz() {
        return BAND_OPEN_HZ;
    }

    /** 频带下限(Hz),供推导侧夹取。 */
    public static double bandFloorHz() {
        return MIN_BAND_HZ;
    }
}
