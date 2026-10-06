package com.muoniumplayer.core.ncm.music;

/**
 * Folia mel 前端的 Java 移植（对照资源 {@code muonium/automix/folia_beat_this.py}）。
 *
 * <p>这个前端的每一处常数都来自 Beat This! 检查点的训练契约：22050Hz、1024 点 FFT、
 * 441 点跳跃（恰好 50 帧/秒）、128 个 mel 带、30Hz～11kHz、amplitude 谱 × 1/√N、
 * {@code log1p(1000·x)}。mel 刻度是 Folia 自己的（1kHz 以下线性、以上对数），
 * 与 librosa/hutil 的 Hz→mel 都不同，不能混用。
 *
 * <p>精度对齐：滤波器组、窗、FFT 全程 double（numpy 的 rfft 是 double 实现），
 * 只在写 mel 帧时落 float32——Python 参考路径同样是「double 中间值、float32 存储」。
 */
final class FoliaMelFrontend {

    static final int SR = 22_050;
    static final int N_FFT = 1024;
    static final int HOP = 441;
    static final int N_MELS = 128;
    static final int FRAMES_PER_SECOND = SR / HOP;

    private static final double F_MIN = 30.0;
    private static final double F_MAX = 11_000.0;
    private static final double LOG_MULTIPLIER = 1000.0;
    private static final double AMIN = 1e-10;
    private static final int BINS = N_FFT / 2 + 1;

    private static volatile double[][] filterbank;
    private static volatile int[] filterbankStarts;
    private static volatile int[] filterbankEnds;
    private static volatile double[] hann;

    private FoliaMelFrontend() {}

    /** log-mel 谱：行 = 帧（50 帧/秒），列 = 128 个 mel 带。 */
    static float[][] spectrogram(float[] mono) {
        int pad = N_FFT / 2;
        // numpy: x = pad(mono, pad, 'reflect'); frames = (len(x) - N_FFT) // HOP + 1
        int frames = Math.max(0, (mono.length + 2 * pad - N_FFT) / HOP + 1);
        if (frames <= 0) return new float[0][];
        double[] window = hann();
        double[][] bank = filterbank();
        int[] bankStarts = filterbankStarts();
        int[] bankEnds = filterbankEnds();
        float[][] out = new float[frames][];
        double[] frame = new double[N_FFT];
        double[] re = new double[N_FFT];
        double[] im = new double[N_FFT];
        double[] magnitude = new double[BINS];
        for (int f = 0; f < frames; f++) {
            int base = f * HOP;
            for (int n = 0; n < N_FFT; n++) {
                int index = base - pad + n;
                if (index < 0) index = reflect(index, mono.length);
                else if (index >= mono.length) index = reflect(index, mono.length);
                frame[n] = mono[index] * window[n];
            }
            System.arraycopy(frame, 0, re, 0, N_FFT);
            java.util.Arrays.fill(im, 0.0);
            fft(re, im);
            double scale = 1.0 / Math.sqrt(N_FFT);
            for (int bin = 0; bin < BINS; bin++) {
                magnitude[bin] = Math.sqrt(re[bin] * re[bin] + im[bin] * im[bin]) * scale;
            }
            float[] melFrame = new float[N_MELS];
            for (int band = 0; band < N_MELS; band++) {
                double energy = 0.0;
                int from = bankStarts[band];
                int to = bankEnds[band];
                for (int bin = from; bin < to; bin++) {
                    energy += bank[band][bin] * magnitude[bin];
                }
                melFrame[band] = (float) Math.log1p(LOG_MULTIPLIER * Math.max(energy, AMIN));
            }
            out[f] = melFrame;
        }
        return out;
    }

    /** numpy 'reflect'：以端点为镜、不重复端点样本。 */
    private static int reflect(int index, int length) {
        if (length <= 1) return 0;
        int period = 2 * (length - 1);
        int m = Math.floorMod(index, period);
        return m < length ? m : period - m;
    }

    /**
     * mel 三角滤波器组，double 精度（Python 参考实现同精度）。
     * {@code starts[band]} / {@code ends[band]} 记录每个带的三角支撑区
     * 〔最后一个非零 bin + 1〕——相邻三角滤波器在角点两侧重叠（band b 的右角
     * 正是 band b+1 的中心），区间必须各自独立取，不能拿上一个带的末端当本带起点，
     * 否则每个带都会漏掉一段重叠区的能量。内积只扫支撑区，每帧省约 40 倍乘法。
     */
    private static double[][] filterbank() {
        double[][] bank = filterbank;
        if (bank != null) return bank;
        synchronized (FoliaMelFrontend.class) {
            if (filterbank != null) return filterbank;
            double melMin = hzToMel(F_MIN);
            double melMax = hzToMel(F_MAX);
            double[] corners = new double[N_MELS + 2];
            for (int i = 0; i < corners.length; i++) {
                corners[i] = melToHz(melMin + (melMax - melMin) * i / (N_MELS + 1));
            }
            double[] result = new double[N_MELS * BINS];
            int[] starts = new int[N_MELS];
            int[] ends = new int[N_MELS];
            for (int band = 0; band < N_MELS; band++) {
                double left = corners[band], centre = corners[band + 1], right = corners[band + 2];
                int first = -1, last = 0;
                for (int bin = 0; bin < BINS; bin++) {
                    double freq = bin * (double) SR / N_FFT;
                    double up = (freq - left) / (centre - left);
                    double down = (right - freq) / (right - centre);
                    double weight = Math.max(0.0, Math.min(up, down));
                    result[band * BINS + bin] = weight;
                    if (weight > 0.0) {
                        if (first < 0) first = bin;
                        last = bin + 1;
                    }
                }
                starts[band] = Math.max(0, first);
                ends[band] = last;
            }
            filterbank = bank = new double[N_MELS][BINS];
            for (int band = 0; band < N_MELS; band++) {
                System.arraycopy(result, band * BINS, bank[band], 0, BINS);
            }
            filterbankStarts = starts;
            filterbankEnds = ends;
            return bank;
        }
    }

    private static int[] filterbankStarts() {
        filterbank();
        return filterbankStarts;
    }

    private static int[] filterbankEnds() {
        filterbank();
        return filterbankEnds;
    }

    private static double[] hann() {
        double[] window = hann;
        if (window != null) return window;
        synchronized (FoliaMelFrontend.class) {
            if (hann == null) {
                window = new double[N_FFT];
                for (int n = 0; n < N_FFT; n++) {
                    window[n] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / N_FFT);
                }
                hann = window;
            }
            return hann;
        }
    }

    /** Folia 的 Hz→mel：1kHz 以下线性（200/3 mel 每 Hz），以上按 ln(6.4)/27 的斜率对数。 */
    static double hzToMel(double hz) {
        double fSp = 200.0 / 3.0;
        if (hz < 1000.0) return hz / fSp;
        return 1000.0 / fSp + Math.log(hz / 1000.0) / (Math.log(6.4) / 27.0);
    }

    static double melToHz(double mel) {
        double fSp = 200.0 / 3.0;
        double minLogMel = 1000.0 / fSp;
        if (mel < minLogMel) return fSp * mel;
        return 1000.0 * Math.exp((Math.log(6.4) / 27.0) * (mel - minLogMel));
    }

    /** 原位 radix-2 FFT（双精度，N 为 2 的幂；逆变换不需要——只要幅度谱）。 */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double stepRe = Math.cos(angle), stepIm = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    double tre = re[b] * curRe - im[b] * curIm;
                    double tim = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tre; im[b] = im[a] - tim;
                    re[a] += tre; im[a] += tim;
                    double nextRe = curRe * stepRe - curIm * stepIm;
                    curIm = curRe * stepIm + curIm * stepRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
