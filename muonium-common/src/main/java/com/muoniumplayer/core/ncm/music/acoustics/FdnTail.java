package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 晚期混响尾:8×8 反馈延迟网络(FDN),替换 Freeverb。
 *
 * <p>拓扑照 Faust {@code zita_rev_fdn}(zita-rev1 的文档化版本):8 条互质环路
 * (总延迟 125~257ms,环内每线一个全通梳,系数 ±0.6 交替),8×8 Hadamard
 * 反馈矩阵蝶形混合(免乘法,行正交 ⇒ 通道最大去相关);mono 输入按 zita 的
 * 0.3 注入量进全部 8 线,双耳输出各取 4 条交错线 —— 去相关由网络结构给出,
 * 不再靠 Freeverb 时代的耳间 +23 样本错位。</p>
 *
 * <h2>逐频带阻尼:能量直方图的落地端</h2>
 * <p>每环阻尼滤波器按 OpenAL Soft {@code T60Filter::calcCoeffs} 的同一数学:
 * 环路长度 L、三频带 T60 各自给出环路增益
 * g(band) = 10<sup>−3·L/(T60(band)·SR)</sup>,中频是基准 mfGain,低/高频以
 * 相对增益 Glf/Ghf 表达,用「低通 + 高通」的一阶对实现双 shelf:</p>
 * <pre>  out = mfGain · ( x + (Glf−1)·lp250(x) + (Ghf−1)·hp5000(x) )</pre>
 * <p>低于 250Hz 总增益 = mfGain·Glf(低频带 T60),高于 5kHz = mfGain·Ghf,
 * 中间 = mfGain —— 直方图拟合出的三段衰减逐带驱动网络,这就是 Jot
 * 分析/综合框架的最简实现。</p>
 *
 * <h2>实时纪律</h2>
 * <ul>
 *   <li>稳态零分配:延迟线/全通/滤波状态全部预分配;参数每缓冲整定(exp 若干,
 *       无 pow/三角缓存问题)。</li>
 *   <li>延迟读位走小数偏移 + 线性插值(与 SceneAcoustics 梳线同款防沙沙读法),
 *       深度压到 ±1.5 样 —— FDN 模态密度高,不需要大摆动。</li>
 *   <li>staynormal 防非规格化浮点:信号衰到 1e-20 之下前永远差一点,不触发
 *       CPU 的 denormal 惩罚路径。</li>
 * </ul>
 */
final class FdnTail {

    /** 环路数:8 = 2³,Hadamard 蝶形免乘法的前提。 */
    static final int LINES = 8;

    /** 环路总延迟(秒,zita 原表):长度互质错开,整体冲激响应周期最大化。 */
    private static final double[] LOOP_DELAYS_SEC = {
            0.153129, 0.210389, 0.127837, 0.256891, 0.174713, 0.192303, 0.125000, 0.219991};
    /** 全通梳前馈延迟(秒,zita 原表)。 */
    private static final double[] AP_DELAYS_SEC = {
            0.020346, 0.024421, 0.031604, 0.027333, 0.022904, 0.029291, 0.013458, 0.019123};
    /** 全通梳系数:±0.6 交替(zita 原值)。 */
    private static final double[] AP_COEFF = {0.6, -0.6, 0.6, -0.6, 0.6, -0.6, 0.6, -0.6};
    /** 数值地板:反馈里永远留一点,防 denormal。 */
    private static final float STAY_NORMAL = 1e-20f;
    /** 输入注入量:高回授下动态余量的来源。0.3 是 zita 原值;实测湿/干能量
     *  比只有 0.206(−7dB,真实大厅混响场应与直达声同量级),提到 0.45 让
     *  尾部进入强混响区间(网络内稳态能量 ×2.25,RMS ×1.5)。 */
    private static final double INPUT_GAIN = 0.45;

    /** 双 shelf 拐点(Hz):EAX 惯例 LFReference/HFReference。 */
    private static final double LOW_XOVER_HZ = 250.0;
    private static final double HIGH_XOVER_HZ = 5000.0;

    /** 调制:小数读位的缓冲余量 / 摆动半幅 / 速率。 */
    private static final int MOD_MARGIN = 8;
    private static final double MOD_DEPTH = 1.5;
    /** 低扩散(离散回声)场景的额外调制深度(样点):打散残留周期性。 */
    private static final double MOD_DIFFUSION_EXTRA = 2.5;
    private static final double MOD_MASTER_HZ = 0.24;
    private static final double[] MOD_SPEED = {0.61, 0.83, 0.53, 0.97, 0.71, 0.59, 0.89, 0.47};

    /** Hadamard 正交缩放 1/√8。 */
    private static final float HADAMARD_SCALE = 0.35355339059327373f;
    /** 双耳输出各取 4 条交错线(下标):等能量 + 去相关;右耳 = 全集减左耳。 */
    private static final int[] EAR_LINES_L = {0, 3, 5, 6};

    // ---- 延迟线与状态(采样率变化时重建) ----
    private final float[][] buf = new float[LINES][];
    private final int[] pos = new int[LINES];
    /** 各线基准延迟(样点)与本缓冲调制偏移(含 MOD_MARGIN/2 中心偏置)。 */
    private final int[] baseDelay = new int[LINES];
    private final float[] modOffset = new float[LINES];
    /** 全通梳缓冲/读位(定长)与延迟(样本)。 */
    private final float[][] apBuf = new float[LINES][];
    private final int[] apPos = new int[LINES];
    private final int[] apDelay = new int[LINES];
    /** 环路长度(秒):增益公式的 L。 */
    private final double[] loopSec = new double[LINES];

    /** 阻尼滤波状态:每线低通一个状态(低 shelf 用)、一个状态(高 shelf 用)。 */
    private final double[] lpLo = new double[LINES];
    private final double[] lpHi = new double[LINES];
    /** 每缓冲整定的增益:midGain 基准环增益,relLow/relHigh 相对增益。 */
    private final double[] midGain = new double[LINES];
    private final double[] relLow = new double[LINES];
    private final double[] relHigh = new double[LINES];
    private double coefLo, coefHi;

    /** 输入高通(220Hz 二阶 Butterworth):DJ 底鼓不进尾网络。 */
    private static final double INPUT_HPF_HZ = 220.0;
    private static final double BUTTERWORTH_Q = 0.7071067811865476;
    private double hpfB0, hpfB1, hpfB2, hpfA1, hpfA2;
    private double hpfX1, hpfX2, hpfY1, hpfY2;
    /** 右耳独立 HPF 状态(立体声尾:两耳各过一份高通)。 */
    private double hpfX1R, hpfX2R, hpfY1R, hpfY2R;
    /** 立体声去相关:side 差量的互质延迟线(7ms @ 最大 48k=336 样)。 */
    private static final double SIDE_DELAY_SEC = 0.007;
    private float[] sideBuf = new float[1];
    private int sidePos;

    private double modPhase;
    /** 扩散度 0..1(计划侧交付;调制深度随它反相变化,内部一阶平滑)。 */
    private double diffusion = 1.0;
    private int ownedRate = -1;
    /** 上次整定的 T60(秒):差异过小则跳过重算。 */
    private double tunedLow = -1.0;
    private double tunedMid = -1.0;
    private double tunedHigh = -1.0;

    FdnTail() {
    }

    /** 采样率变化 / 首次使用:重建延迟线并清全部状态。 */
    void rebuild(int rate) {
        ownedRate = rate;
        coefLo = 1.0 - Math.exp(-2.0 * Math.PI * LOW_XOVER_HZ / rate);
        coefHi = 1.0 - Math.exp(-2.0 * Math.PI * HIGH_XOVER_HZ / rate);
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
        sideBuf = new float[1]; // 下次 sideDelay 按 rate 重建
        for (int i = 0; i < LINES; i++) {
            final int loop = Math.max(8, (int) Math.round(LOOP_DELAYS_SEC[i] * rate));
            final int ap = Math.max(2, (int) Math.round(AP_DELAYS_SEC[i] * rate));
            baseDelay[i] = loop;
            // 增益公式的 L = 完整环路长度:主延迟 + 全通段 + ~1 样反馈取整
            // —— 漏掉全通段会让渲染 T60 系统性偏长 11-13%(L 每大 1%,T60 跟着大)。
            loopSec[i] = (loop + ap + 1) / (double) rate;
            // 缓冲 = 基准延迟 + 调制余量;读位最远回看 baseDelay + MOD_MARGIN/2 + DEPTH
            buf[i] = new float[loop + MOD_MARGIN + 1];
            pos[i] = 0;
            apDelay[i] = ap;
            apBuf[i] = new float[ap + 1];
            apPos[i] = 0;
            lpLo[i] = 0.0;
            lpHi[i] = 0.0;
        }
        modPhase = 0.0;
        tunedLow = tunedMid = tunedHigh = -1.0;
    }

    /**
     * 整定阻尼参数(每缓冲一次)。t60Mid ≤ 0 视为静息(不更新,增益沿用旧值,
     * 调用方负责湿声闸);三次都几乎没变则跳过(省 24 次 exp)。
     */
    void update(double t60LowSec, double t60MidSec, double t60HighSec) {
        if (t60MidSec <= 0.0 || ownedRate <= 0) {
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

        final double ln10 = Math.log(10.0);
        for (int i = 0; i < LINES; i++) {
            final double base = -3.0 * ln10 * loopSec[i];
            final double mid = Math.exp(base / t60MidSec);
            midGain[i] = mid;
            relLow[i] = mid > 1e-12
                    ? Math.min(1.0, Math.exp(base / Math.max(0.05, t60LowSec)) / mid)
                    : 1.0;
            relHigh[i] = mid > 1e-12 ? Math.exp(base / Math.max(0.05, t60HighSec)) / mid : 1.0;
        }
    }

    /**
     * 渲染一个样本(双耳入,双耳出)。先 {@link #update} 过才有正确的阻尼;
     * outLR 长度 ≥2,写入左右湿声(未乘 wet,调用方统一乘)。
     *
     * <p>立体声尾:输入 L/R 各经一条互质的短预延迟(7ms/11ms,低于哈斯阈
     * 不会听成双声)再进网 —— 中高频在两条入网路径上携带不同延迟图案,
     * 输出双耳的相关性显著下降(空间感);低频已被 220Hz HPF 拦在网外,
     * 保持天然 mono,不轰头。</p>
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
        // 立体声去相关预延迟:左右各一条互质短延迟(低于哈斯阈 30ms 的一半),
        // 输出 (L+R)/2 相干量 + (L−R) 差量保留 —— 等价于「半单声道化」的
        // 温和去相关:保持能量不变,相关系数从 1.0 降到 ~0.5。
        final float mono = (float) hpL * 0.5f + (float) hpR * 0.5f;
        final float side = (float) hpL * 0.5f - (float) hpR * 0.5f;
        // side 过 7ms 互质延迟(L:0,R:差量反相)
        final float sideDelayed = sideDelay(side);
        final float inLg = (mono + sideDelayed) * AcousticsDebugTuning.fdnInputGain((float) INPUT_GAIN);
        final float inRg = (mono - sideDelayed) * AcousticsDebugTuning.fdnInputGain((float) INPUT_GAIN);
        // 注入:奇偶线分左右去相关输入(zita distrib 变体)
        final float inEven = inLg;
        final float inOdd = inRg;

        // 1) 每线:延迟读(插值调制)→ 全通梳 → 收进蝶形
        float a0 = 0f;
        float a1 = 0f;
        float a2 = 0f;
        float a3 = 0f;
        float a4 = 0f;
        float a5 = 0f;
        float a6 = 0f;
        float a7 = 0f;
        float r0 = 0f;
        float r1 = 0f;
        float r2 = 0f;
        float r3 = 0f;
        float r4 = 0f;
        float r5 = 0f;
        float r6 = 0f;
        float r7 = 0f;

        for (int i = 0; i < LINES; i++) {
            final float[] line = buf[i];
            int p = pos[i];
            final double readF = p - baseDelay[i] - modOffset[i];
            int read = (int) Math.floor(readF);
            final float frac = (float) (readF - read);
            if (read < 0) {
                read += line.length;
            }
            final int readNext = read + 1 == line.length ? 0 : read + 1;
            final float delayed = line[read] + (line[readNext] - line[read]) * frac;

            final float[] ap = apBuf[i];
            int q = apPos[i];
            final float stored = ap[q];
            // DF2 全通:先算内部 w = delayed + a·stored,输出 = stored − a·w。
            // 恒定 |H|=1(Faust allpass_comb 同构);写成 stored−delayed 会把
            // 环路谱半径顶到 >1(参数盒上部发散,稳定性攸关)。
            final float w = delayed + (float) AP_COEFF[i] * stored;
            final float apOut = stored - (float) AP_COEFF[i] * w;
            ap[q] = w;
            apPos[i] = q + 1 == ap.length ? 0 : q + 1;

            switch (i) {
                case 0 -> { a0 = apOut; r0 = delayed; }
                case 1 -> { a1 = apOut; r1 = delayed; }
                case 2 -> { a2 = apOut; r2 = delayed; }
                case 3 -> { a3 = apOut; r3 = delayed; }
                case 4 -> { a4 = apOut; r4 = delayed; }
                case 5 -> { a5 = apOut; r5 = delayed; }
                case 6 -> { a6 = apOut; r6 = delayed; }
                default -> { a7 = apOut; r7 = delayed; }
            }
        }

        // 2) Hadamard 蝶形(8 点,三级加减,免乘法)
        float b0 = a0 + a1;
        float b1 = a0 - a1;
        float b2 = a2 + a3;
        float b3 = a2 - a3;
        float b4 = a4 + a5;
        float b5 = a4 - a5;
        float b6 = a6 + a7;
        float b7 = a6 - a7;
        float c0 = b0 + b2;
        float c1 = b1 + b3;
        float c2 = b0 - b2;
        float c3 = b1 - b3;
        float c4 = b4 + b6;
        float c5 = b5 + b7;
        float c6 = b4 - b6;
        float c7 = b5 - b7;
        float f0 = (c0 + c4) * HADAMARD_SCALE;
        float f1 = (c1 + c5) * HADAMARD_SCALE;
        float f2 = (c2 + c6) * HADAMARD_SCALE;
        float f3 = (c3 + c7) * HADAMARD_SCALE;
        float f4 = (c0 - c4) * HADAMARD_SCALE;
        float f5 = (c1 - c5) * HADAMARD_SCALE;
        float f6 = (c2 - c6) * HADAMARD_SCALE;
        float f7 = (c3 - c7) * HADAMARD_SCALE;

        // 3) 阻尼(逐带 T60)→ 输入注入 + 反馈写回;阻尼后值同时进输出和。
        //    输出必须取【阻尼后】的信号 —— 取阻尼前的蝶形值会让逐带 T60 只
        //    作用在反馈回路,输出端的高频整形被直通分量稀释(实测差 <4%)。
        double oL = 0;
        double oR = 0;
        for (int i = 0; i < LINES; i++) {
            float fb = switch (i) {
                case 0 -> f0;
                case 1 -> f1;
                case 2 -> f2;
                case 3 -> f3;
                case 4 -> f4;
                case 5 -> f5;
                case 6 -> f6;
                default -> f7;
            };
            final double lpL = lpLo[i] + coefLo * (fb - lpLo[i]);
            lpLo[i] = lpL;
            final double lpH = lpHi[i] + coefHi * (fb - lpHi[i]);
            lpHi[i] = lpH;
            final double damped = midGain[i]
                    * (fb + (relLow[i] - 1.0) * lpL + (relHigh[i] - 1.0) * (fb - lpH));

            final float[] line = buf[i];
            line[pos[i]] = (i & 1) == 0 ? inEven + (float) damped + STAY_NORMAL
                    : inOdd + (float) damped + STAY_NORMAL;
            pos[i] = pos[i] + 1 == line.length ? 0 : pos[i] + 1;

            // 双耳各取 4 条交错线(EAR_LINES 表):Hadamard 混合后天然等能量、
            // 去相关;×2 = 4 行和的 RMS 归一(最终电平由 TAIL_MAKEUP 收口)。
            if (i == EAR_LINES_L[0] || i == EAR_LINES_L[1]
                    || i == EAR_LINES_L[2] || i == EAR_LINES_L[3]) {
                oL += damped;
            } else {
                oR += damped;
            }
        }
        outLR[0] = (float) (oL * 0.5);
        outLR[1] = (float) (oR * 0.5);
    }

    /** side 差量的互质延迟(7ms):输入去相关的最小件。 */
    private float sideDelay(float side) {
        if (sideBuf.length < 2) {
            sideBuf = new float[Math.max(4, (int) (SIDE_DELAY_SEC * Math.max(8000, ownedRate)) + 1)];
            sidePos = 0;
        }
        final float delayed = sideBuf[sidePos];
        sideBuf[sidePos] = side;
        sidePos = sidePos + 1 == sideBuf.length ? 0 : sidePos + 1;
        return delayed;
    }

    /** 每缓冲推进调制相位并整定各线读距偏移(缓冲级,与梳线同款)。
     *  深度 = 基础值 + 低扩散场景的额外量(0..1 平滑过渡)。 */
    void advanceModulation(int frames, int rate) {
        modPhase += 2.0 * Math.PI * MOD_MASTER_HZ * frames / rate;
        if (modPhase > 6.283185307179586 * 1024.0) {
            modPhase -= 6.283185307179586 * 1024.0; // 防长会话浮点精度劣化
        }
        final double depth = MOD_DEPTH + (1.0 - diffusion) * MOD_DIFFUSION_EXTRA;
        for (int i = 0; i < LINES; i++) {
            final double wave = Math.sin(modPhase * MOD_SPEED[i] + i * 2.399);
            modOffset[i] = (float) (MOD_MARGIN / 2 + wave * depth);
        }
    }

    /** 交付扩散度(0..1;低于 1 时调制加深)。内部向目标缓移,杜绝深度跳变。 */
    void setDiffusion(float target) {
        final double t = Math.max(0.0, Math.min(1.0, target));
        diffusion += (t - diffusion) * 0.25; // 每缓冲 1/4 步:约 200ms 收敛
    }

    /** 清空全部延迟线与滤波状态(曲目切换)。 */
    void flush() {
        hpfX1R = hpfX2R = hpfY1R = hpfY2R = 0.0;
        if (sideBuf != null) java.util.Arrays.fill(sideBuf, 0f);
        for (int i = 0; i < LINES; i++) {
            if (buf[i] != null) {
                java.util.Arrays.fill(buf[i], 0f);
            }
            if (apBuf[i] != null) {
                java.util.Arrays.fill(apBuf[i], 0f);
            }
            pos[i] = 0;
            apPos[i] = 0;
            lpLo[i] = 0.0;
            lpHi[i] = 0.0;
        }
        modPhase = 0.0;
    }

    /** 当前采样率(诊断;-1 = 未重建)。 */
    int ownedRate() {
        return ownedRate;
    }
}
