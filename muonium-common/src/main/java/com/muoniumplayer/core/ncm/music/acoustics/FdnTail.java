package com.muoniumplayer.core.ncm.music.acoustics;

import java.util.Arrays;

/**
 * 晚期混响尾:源于 Faust {@code zita_rev_fdn} 的八线反馈延迟网络。
 * 主延迟 125~257ms,环内各有一个全通梳和三带 T60 阻尼,反馈用单位化
 * Hadamard 蝶形。短输入全通与带符号的注入/读出填密第一批回声,
 * 不增加固定预延迟。延迟线/滤波状态预分配,调制在缓冲间连续,逐样本无三角函数。
 */
final class FdnTail {

    /** 环路数:8 = 2³,Hadamard 蝶形免乘法的前提。 */
    static final int LINES = 8;

    /** 主延迟(秒,zita 原表),不包括环路全通。 */
    private static final double[] LOOP_DELAYS_SEC = {
            0.153129, 0.210389, 0.127837, 0.256891, 0.174713, 0.192303, 0.125000, 0.219991};
    /** 全通梳前馈延迟(秒,zita 原表)。 */
    private static final double[] AP_DELAYS_SEC = {
            0.020346, 0.024421, 0.031604, 0.027333, 0.022904, 0.029291, 0.013458, 0.019123};
    /** 数值地板:反馈与输入全通的延迟里留一点,防 denormal。 */
    private static final float STAY_NORMAL = 1e-20f;
    private static final float INPUT_GAIN = 0.45f;

    /** 双 shelf 拐点(Hz):EAX 惯例 LFReference/HFReference。 */
    private static final double LOW_XOVER_HZ = 250.0;
    private static final double HIGH_XOVER_HZ = 5000.0;

    /** 调制:小数读位的缓冲余量 / 摆动半幅 / 速率。 */
    private static final int MOD_CENTER = 4;
    private static final double MOD_DEPTH = 1.5;
    /** 低扩散(离散回声)场景的额外调制深度(样点):打散残留周期性。 */
    private static final double MOD_DIFFUSION_EXTRA = 2.5;
    private static final double MOD_MASTER_HZ = 0.24;
    private static final double MOD_DEPTH_TAU_SEC = 0.025;
    private static final double TAU = 2.0 * Math.PI;
    private static final double[] MOD_SPEED = {0.61, 0.83, 0.53, 0.97, 0.71, 0.59, 0.89, 0.47};

    /** Hadamard 正交缩放 1/√8。 */
    private static final float HADAMARD_SCALE = 0.35355339059327373f;
    /** 注入极性(低位对应线 0),奇偶两组各有两个正、两个负,不改变输入能量。 */
    private static final int INPUT_NEGATIVE = 0b1000_1101;

    // ---- 延迟线与状态(采样率变化时重建) ----
    private final float[][] buf = new float[LINES][];
    private final int[] pos = new int[LINES];
    private final int[] baseDelay = new int[LINES];
    private final AllPass[] loopDiffusers = new AllPass[LINES];
    private final float[] mixed = new float[LINES];
    /** 每线 T60 增益指数的分子(-3 ln(10) × 环路秒数)。 */
    private final double[] decayFactor = new double[LINES];

    /** 串联的低/高搁架,各一个梯形积分低通状态。 */
    private final double[] lpLo = new double[LINES];
    private final double[] lpHi = new double[LINES];
    private final double[] lowDelta = new double[LINES];
    private final double[] highGain = new double[LINES];
    private final double[] midMinusHigh = new double[LINES];
    private double coefLo, coefHi;

    /** 输入高通(220Hz 二阶 Butterworth):DJ 底鼓不进尾网络。 */
    private static final double INPUT_HPF_HZ = 220.0;
    private static final double BUTTERWORTH_Q = 0.7071067811865476;
    private double hpfB0, hpfB1, hpfB2, hpfA1, hpfA2;
    private double hpfX1, hpfX2, hpfY1, hpfY2;
    /** 右耳独立 HPF 状态(立体声尾:两耳各过一份高通)。 */
    private double hpfX1R, hpfX2R, hpfY1R, hpfY2R;
    /** 保留原有 side 差量延迟,不推迟 mid。 */
    private static final double SIDE_DELAY_SEC = 0.007;
    private float[] sideBuf = new float[1];
    private int sidePos;
    private final AllPass inputL1 = new AllPass(0.64f);
    private final AllPass inputL2 = new AllPass(-0.64f);
    private final AllPass inputR1 = new AllPass(-0.64f);
    private final AllPass inputR2 = new AllPass(0.64f);

    private final double[] modPhase = new double[LINES];
    private final float[] modFrom = new float[LINES];
    private final float[] modDelta = new float[LINES];
    private int modFrame, modFrames;
    private float modInvFrames;
    private double modDepth = MOD_DEPTH;
    private double modDepthTarget = MOD_DEPTH;
    private double modDepthStep;
    private int ownedRate = -1;
    /** 上次整定的 T60(秒):差异过小则跳过重算。 */
    private double tunedLow = -1.0;
    private double tunedMid = -1.0;
    private double tunedHigh = -1.0;

    FdnTail() {
        for (int i = 0; i < LINES; i++) {
            loopDiffusers[i] = new AllPass((i & 1) == 0 ? 0.6f : -0.6f);
        }
    }

    /** 采样率变化 / 首次使用:重建延迟线并清全部状态。 */
    void rebuild(int rate) {
        ownedRate = rate;
        coefLo = shelfPole(LOW_XOVER_HZ, rate);
        coefHi = shelfPole(HIGH_XOVER_HZ, rate);
        modDepthStep = -Math.expm1(-1.0 / (rate * MOD_DEPTH_TAU_SEC));
        // 输入端 220Hz 二阶 Butterworth 高通:DJ/劲爆歌曲的底鼓(808 类 sub,
        // 主能量 60-110Hz)原样进网络会让每一记底鼓在尾里循环 1-2s 与下一记
        // 叠加 —— 低频轰头 + 节拍性冲击。150Hz 档实测泄漏仍大(60Hz 仅 -16dB,
        // 110Hz -6.5dB),220Hz 档把 110Hz 压到 -13dB;低音走干声体感,尾巴只
        // 保留中高频「空气感」。
        final double w0 = 2.0 * Math.PI * Math.min(INPUT_HPF_HZ, rate * 0.45) / rate;
        final double cosw = Math.cos(w0);
        final double alpha = Math.sin(w0) / (2.0 * BUTTERWORTH_Q);
        final double a0 = 1.0 + alpha;
        hpfB0 = ((1.0 + cosw) / 2.0) / a0;
        hpfB1 = -(1.0 + cosw) / a0;
        hpfB2 = hpfB0;
        hpfA1 = (-2.0 * cosw) / a0;
        hpfA2 = (1.0 - alpha) / a0;
        hpfX1 = hpfX2 = hpfY1 = hpfY2 = 0.0;
        hpfX1R = hpfX2R = hpfY1R = hpfY2R = 0.0;
        sideBuf = new float[Math.max(4, (int) (SIDE_DELAY_SEC * Math.max(8000, rate)) + 1)];
        sidePos = 0;
        // 两耳错开短延迟,每段有直通项,增加密度但没有串联纯延迟。
        inputL1.rebuild(Math.max(1, (int) Math.round(0.0029 * rate)));
        inputL2.rebuild(Math.max(1, (int) Math.round(0.0067 * rate)));
        inputR1.rebuild(Math.max(1, (int) Math.round(0.0041 * rate)));
        inputR2.rebuild(Math.max(1, (int) Math.round(0.0083 * rate)));
        for (int i = 0; i < LINES; i++) {
            final int loop = Math.max(8, (int) Math.round(LOOP_DELAYS_SEC[i] * rate));
            final int ap = Math.max(2, (int) Math.round(AP_DELAYS_SEC[i] * rate)) + 1;
            baseDelay[i] = loop;
            // 名义环路长度包含调制中心和全通段的平均群延迟。
            decayFactor[i] = -3.0 * Math.log(10.0) * (loop + MOD_CENTER + ap) / rate;
            buf[i] = new float[loop + 2 * MOD_CENTER + 2];
            pos[i] = 0;
            loopDiffusers[i].rebuild(ap);
            lpLo[i] = 0.0;
            lpHi[i] = 0.0;
            highGain[i] = midMinusHigh[i] = lowDelta[i] = 0.0;
        }
        resetModulation();
        tunedLow = tunedMid = tunedHigh = -1.0;
    }

    private static double shelfPole(double hz, int rate) {
        final double g = Math.tan(Math.PI * Math.min(hz / rate, 0.45));
        return g / (1.0 + g);
    }

    /**
     * 整定阻尼参数(每缓冲一次)。t60Mid ≤ 0 视为静息(不更新,增益沿用旧值,
     * 调用方负责湿声闸);三次都几乎没变则跳过(省 24 次 exp)。
     */
    void update(double t60LowSec, double t60MidSec, double t60HighSec) {
        if (ownedRate <= 0 || !(t60MidSec > 0.0) || !Double.isFinite(t60MidSec)
                || !Double.isFinite(t60LowSec) || !Double.isFinite(t60HighSec)) {
            return;
        }
        if (Math.abs(t60LowSec - tunedLow) < 1e-4
                && Math.abs(t60MidSec - tunedMid) < 1e-4
                && Math.abs(t60HighSec - tunedHigh) < 1e-4) {
            return;
        }
        tunedLow = t60LowSec;
        tunedMid = t60MidSec;
        tunedHigh = t60HighSec;

        final double invMid = 1.0 / t60MidSec;
        final double invHigh = 1.0 / Math.max(0.05, t60HighSec);
        final double invLowDelta = Math.max(0.0, 1.0 / Math.max(0.05, t60LowSec) - invMid);
        for (int i = 0; i < LINES; i++) {
            final double base = decayFactor[i];
            // g = 10^(-3 * loopSec / T60)。低架只衰减(低频不得比中频更长),
            // 高架用绝对高/中频增益插值,不除以可能接近 0 的中频增益。
            lowDelta[i] = invLowDelta == 0.0 ? 0.0 : Math.expm1(base * invLowDelta);
            highGain[i] = Math.exp(base * invHigh);
            midMinusHigh[i] = Math.exp(base * invMid) - highGain[i];
        }
    }

    /**
     * 渲染一个样本(双耳入,双耳出)。先 {@link #update} 过才有正确的阻尼;
     * outLR 长度 ≥2,写入左右湿声(未乘 wet,调用方统一乘)。
     */
    void process(float inL, float inR, float[] outLR) {
        // 输入高通先于注入:低频(底鼓)不进尾网络,防止每记底鼓在反馈环里
        // 循环叠加(轰头+节拍性冲击);二阶 Butterworth,直通带无染色。
        final double hpL = hpfB0 * inL + hpfB1 * hpfX1 + hpfB2 * hpfX2
                - hpfA1 * hpfY1 - hpfA2 * hpfY2;
        hpfX2 = hpfX1;
        hpfX1 = inL;
        hpfY2 = hpfY1;
        hpfY1 = hpL;
        // 右耳:同一组滤波系数(对称),独立状态
        final double hpR = hpfB0 * inR + hpfB1 * hpfX1R + hpfB2 * hpfX2R
                - hpfA1 * hpfY1R - hpfA2 * hpfY2R;
        hpfX2R = hpfX1R;
        hpfX1R = inR;
        hpfY2R = hpfY1R;
        hpfY1R = hpR;
        final float mono = (float) ((hpL + hpR) * 0.5);
        final float sideDelayed = sideDelay((float) ((hpL - hpR) * 0.5));
        final float gain = AcousticsDebugTuning.fdnInputGain(INPUT_GAIN);
        final float inEven = inputL2.process(inputL1.process(mono + sideDelayed + STAY_NORMAL)) * gain;
        final float inOdd = inputR2.process(inputR1.process(mono - sideDelayed + STAY_NORMAL)) * gain;

        // 1) 每线:延迟读(插值调制)→ 全通梳 → 收进蝶形
        final float ramp = modFrame < modFrames ? modFrame++ * modInvFrames : 1f;
        modDepth += (modDepthTarget - modDepth) * modDepthStep;
        final float depth = (float) modDepth;
        for (int i = 0; i < LINES; i++) {
            final float[] line = buf[i];
            // 偏移恒为非负,向更旧的样本插值,热循环不需要 floor。
            final float offset = MOD_CENTER + (modFrom[i] + modDelta[i] * ramp) * depth;
            final int whole = (int) offset;
            int read = pos[i] - baseDelay[i] - whole;
            if (read < 0) {
                read += line.length;
            }
            final int older = read == 0 ? line.length - 1 : read - 1;
            final float recent = line[read];
            mixed[i] = loopDiffusers[i].process(recent + (line[older] - recent) * (offset - whole));
        }

        // 2) 单位化 Hadamard 蝶形(8 点,三级加减)
        float b0 = mixed[0] + mixed[1];
        float b1 = mixed[0] - mixed[1];
        float b2 = mixed[2] + mixed[3];
        float b3 = mixed[2] - mixed[3];
        float b4 = mixed[4] + mixed[5];
        float b5 = mixed[4] - mixed[5];
        float b6 = mixed[6] + mixed[7];
        float b7 = mixed[6] - mixed[7];
        float c0 = b0 + b2;
        float c1 = b1 + b3;
        float c2 = b0 - b2;
        float c3 = b1 - b3;
        float c4 = b4 + b6;
        float c5 = b5 + b7;
        float c6 = b4 - b6;
        float c7 = b5 - b7;
        mixed[0] = (c0 + c4) * HADAMARD_SCALE;
        mixed[1] = (c1 + c5) * HADAMARD_SCALE;
        mixed[2] = (c2 + c6) * HADAMARD_SCALE;
        mixed[3] = (c3 + c7) * HADAMARD_SCALE;
        mixed[4] = (c0 - c4) * HADAMARD_SCALE;
        mixed[5] = (c1 - c5) * HADAMARD_SCALE;
        mixed[6] = (c2 - c6) * HADAMARD_SCALE;
        mixed[7] = (c3 - c7) * HADAMARD_SCALE;

        // 3) 串联搁架。梯形低/高通互补且相差 90°,单段增益不超过两端的最大值。
        //    所以高频 T60 即使长于中频也不会把定参数搁架的增益推过 1。
        for (int i = 0; i < LINES; i++) {
            final float fb = mixed[i];
            final double vLo = coefLo * (fb - lpLo[i]);
            final double low = lpLo[i] + vLo;
            lpLo[i] = low + vLo;
            final double lowShelved = fb + lowDelta[i] * low;
            final double vHi = coefHi * (lowShelved - lpHi[i]);
            final double lp = lpHi[i] + vHi;
            lpHi[i] = lp + vHi;
            final float damped = (float) (highGain[i] * lowShelved + midMinusHigh[i] * lp);

            final float[] line = buf[i];
            final int p = pos[i];
            final float in = (i & 1) == 0 ? inEven : inOdd;
            final float signedIn = (INPUT_NEGATIVE & (1 << i)) == 0 ? in : -in;
            line[p] = signedIn + damped + STAY_NORMAL;
            pos[i] = p + 1 == line.length ? 0 : p + 1;
            mixed[i] = damped;
        }
        // 每耳四线、1/2 单位归一,两组混配长短线,避免短衰减时明显偏耳。
        // 每组反相一线:等阻尼时仍读出所有 Hadamard 列,全正求和则会抵消六路。
        outLR[0] = (mixed[0] + mixed[2] + mixed[5] - mixed[7]) * 0.5f;
        outLR[1] = (-mixed[1] + mixed[3] + mixed[4] + mixed[6]) * 0.5f;
    }

    /** side 差量的互质延迟(7ms):输入去相关的最小件。 */
    private float sideDelay(float side) {
        final float delayed = sideBuf[sidePos];
        sideBuf[sidePos] = side;
        sidePos = sidePos + 1 == sideBuf.length ? 0 : sidePos + 1;
        return delayed;
    }

    /** 每缓冲求调制终点,从当前偏移逐样本缓移;各线独立回卷相位。 */
    void advanceModulation(int frames, int rate) {
        if (frames <= 0 || rate <= 0) return;
        final double phasePerFrame = TAU * MOD_MASTER_HZ / rate;
        final float ramp = modFrame < modFrames ? modFrame * modInvFrames : 1f;
        for (int i = 0; i < LINES; i++) {
            final double step = phasePerFrame * MOD_SPEED[i];
            final double phase = modPhase[i] + step * modFrame;
            modPhase[i] = phase >= TAU ? phase % TAU : phase;
            modFrom[i] += modDelta[i] * ramp;
            modDelta[i] = (float) Math.sin(modPhase[i] + step * frames) - modFrom[i];
        }
        modFrame = 0;
        modFrames = frames;
        modInvFrames = 1f / frames;
    }

    /** 低扩散场景的调制更深,深度按样本平滑,与缓冲大小无关。 */
    void setDiffusion(float target) {
        if (Float.isFinite(target)) {
            modDepthTarget = MOD_DEPTH + (1.0 - Math.max(0f, Math.min(1f, target))) * MOD_DIFFUSION_EXTRA;
        }
    }

    private void resetModulation() {
        for (int i = 0; i < LINES; i++) {
            modPhase[i] = (i * 2.399) % TAU;
            modFrom[i] = (float) Math.sin(modPhase[i]);
            modDelta[i] = 0f;
        }
        modFrame = modFrames = 0;
        modInvFrames = 0f;
        modDepth = modDepthTarget;
    }

    /** 清空全部延迟线与滤波状态(曲目切换)。 */
    void flush() {
        hpfX1 = hpfX2 = hpfY1 = hpfY2 = 0.0;
        hpfX1R = hpfX2R = hpfY1R = hpfY2R = 0.0;
        Arrays.fill(sideBuf, 0f);
        sidePos = 0;
        inputL1.flush();
        inputL2.flush();
        inputR1.flush();
        inputR2.flush();
        for (int i = 0; i < LINES; i++) {
            if (buf[i] != null) {
                Arrays.fill(buf[i], 0f);
            }
            loopDiffusers[i].flush();
            pos[i] = 0;
            lpLo[i] = 0.0;
            lpHi[i] = 0.0;
        }
        resetModulation();
    }

    /** 当前采样率(诊断;-1 = 未重建)。 */
    int ownedRate() {
        return ownedRate;
    }

    /** DF2 全通梳:单位幅频响应,且首项为 -coefficient,不是纯延迟。 */
    private static final class AllPass {
        private final float coefficient;
        private float[] delay;
        private int pos;

        AllPass(float coefficient) {
            this.coefficient = coefficient;
        }

        void rebuild(int samples) {
            delay = new float[samples];
            pos = 0;
        }

        float process(float in) {
            final float stored = delay[pos];
            final float w = in + coefficient * stored;
            delay[pos] = w;
            pos = pos + 1 == delay.length ? 0 : pos + 1;
            return stored - coefficient * w;
        }

        void flush() {
            if (delay != null) Arrays.fill(delay, 0f);
            pos = 0;
        }
    }
}
