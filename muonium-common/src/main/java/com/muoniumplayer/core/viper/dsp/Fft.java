// 逐行移植自 ViPERDSP/viper/utils/pffft.{h,c}（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 上游 pffft 依赖 http://www.netlib.org/fftpack 的 FFTPACKv4（Paul Swarztrauber, NCAR, 1985），
// 版权归 UCAR 所有，以 BSD 风格许可发布（详见 pffft.h 头部）。
// 本文件为等价的最小 Java 实现：仅覆盖 ViPERDSP 实际用到的 PFFFT_REAL 路径，
// 采用干净的迭代 radix-2 算法（float 精度）。与 pffft 的差异见本报告末尾说明。
package com.muoniumplayer.core.viper.dsp;

/**
 * 等价替代上游 pffft 所需子集的最小实数 FFT 工具类。
 *
 * <p>调用方（MatrixConvolver / PConvSingle）只使用 {@code PFFFT_REAL} 变换，因此本类
 * 仅实现实数变换：
 * <ul>
 *   <li>{@link #FORWARD}：标准 DFT（不缩放），输出按 FFTPACK 实部打包顺序写入 {@code length == n}
 *       的数组：[re0, re1, im1, re2, im2, ..., re(n/2-1), im(n/2-1), re(n/2)]。</li>
 *   <li>{@link #BACKWARD}：逆 DFT（不缩放），输入为上述打包顺序的频谱，输出为时域
 *       {@code length == n} 的实数数组，等于 {@code n * 原始信号}（与 pffft 的
 *       PFFFT_BACKWARD(PFFFT_FORWARD(x)) == N*x 语义一致）。</li>
 * </ul>
 * 变换大小 {@code n} 必须是 2 的幂（上游实际只使用 2 的幂；segment_size 经位运算校验为 2 的幂）。
 */
public final class Fft {

    public static final int FORWARD = 0;
    public static final int BACKWARD = 1;

    private final int n;
    private final int half;

    // 复数暂存区（交错布局 [re0,im0,re1,im1,...]，长度 2n），在构造器中分配，变换内复用。
    private final float[] scratch;

    // 预计算的旋转因子表（正向角度 2*pi*i/n，i in [0, n/2)），构造器中分配一次。
    private final float[] cosTab;
    private final float[] sinTab;

    /**
     * 准备对长度为 {@code n} 的实数信号做变换。
     *
     * @param n 变换大小，必须是 >= 4 的 2 的幂
     * @throws IllegalArgumentException 当 {@code n} 不是合法的 2 的幂时
     */
    public Fft(int n) {
        if (n < 4 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Fft size must be a power of two >= 4, got " + n);
        }
        this.n = n;
        this.half = n / 2;
        this.scratch = new float[2 * n];
        this.cosTab = new float[half];
        this.sinTab = new float[half];
        for (int i = 0; i < half; i++) {
            final double angle = 2.0 * Math.PI * i / n;
            cosTab[i] = (float) Math.cos(angle);
            sinTab[i] = (float) Math.sin(angle);
        }
    }

    /** 变换大小（与上游 PFFFT_Setup.N 对应）。 */
    public int size() {
        return n;
    }

    /**
     * 等价替代 {@code pffft_transform}。{@code work} 参数保留以镜像上游签名，本实现无需使用。
     *
     * @param input   输入数组，长度必须 == n
     * @param output  输出数组，长度必须 == n
     * @param work    未使用（保留以兼容 pffft_transform 签名）
     * @param direction {@link #FORWARD} 或 {@link #BACKWARD}
     */
    public void transform(float[] input, float[] output, float[] work, int direction) {
        if (direction == FORWARD) {
            forward(input, output);
        } else {
            backward(input, output);
        }
    }

    private void forward(float[] x, float[] out) {
        // 将实数输入填入复数暂存区（虚部为 0），做标准 DFT，再按 FFTPACK 实部顺序打包。
        for (int i = 0; i < n; i++) {
            scratch[2 * i] = x[i];
            scratch[2 * i + 1] = 0.0f;
        }
        fft(true);

        out[0] = scratch[0];                 // re0
        out[n - 1] = scratch[2 * half];      // re(n/2)（Nyquist，实数）
        for (int k = 1; k < half; k++) {
            out[2 * k - 1] = scratch[2 * k];     // re(k)
            out[2 * k] = scratch[2 * k + 1];     // im(k)
        }
    }

    private void backward(float[] x, float[] out) {
        // 从 FFTPACK 实部打包顺序还原完整复频谱（利用实信号的共轭对称性），做逆 DFT（不缩放）。
        scratch[0] = x[0];
        scratch[1] = 0.0f;
        scratch[2 * half] = x[n - 1];
        scratch[2 * half + 1] = 0.0f;
        for (int k = 1; k < half; k++) {
            scratch[2 * k] = x[2 * k - 1];
            scratch[2 * k + 1] = x[2 * k];
        }
        for (int k = half + 1; k < n; k++) {
            final int conj = 2 * (n - k);
            scratch[2 * k] = scratch[conj];
            scratch[2 * k + 1] = -scratch[conj + 1];
        }
        fft(false);

        for (int i = 0; i < n; i++) {
            out[i] = scratch[2 * i];
        }
    }

    private void fft(boolean forward) {
        final int m = n;

        // 位反转置换（仅支持 2 的幂）。
        int j = 0;
        for (int i = 1; i < m; i++) {
            int bit = m >> 1;
            while (j >= bit) {
                j -= bit;
                bit >>= 1;
            }
            j += bit;
            if (i < j) {
                final int i2 = 2 * i;
                final int j2 = 2 * j;
                final float tr = scratch[i2];
                final float ti = scratch[i2 + 1];
                scratch[i2] = scratch[j2];
                scratch[i2 + 1] = scratch[j2 + 1];
                scratch[j2] = tr;
                scratch[j2 + 1] = ti;
            }
        }

        // 蝶形运算：逐段长度 len = 2,4,...,n。
        for (int len = 2; len <= m; len <<= 1) {
            final int halfLen = len >> 1;
            final int step = m / len;
            for (int base = 0; base < m; base += len) {
                for (int k = 0; k < halfLen; k++) {
                    final int idx = k * step;
                    final float wr = cosTab[idx];
                    final float wi = forward ? -sinTab[idx] : sinTab[idx];
                    final int aIdx = 2 * (base + k);
                    final int bIdx = 2 * (base + k + halfLen);
                    final float ar = scratch[aIdx];
                    final float ai = scratch[aIdx + 1];
                    final float br = scratch[bIdx];
                    final float bi = scratch[bIdx + 1];
                    final float tr = wr * br - wi * bi;
                    final float ti = wr * bi + wi * br;
                    scratch[aIdx] = ar + tr;
                    scratch[aIdx + 1] = ai + ti;
                    scratch[bIdx] = ar - tr;
                    scratch[bIdx + 1] = ai - ti;
                }
            }
        }
    }

    /**
     * 等价替代 {@code pffft_zconvolve_accumulate}：在打包后的频谱上做复数乘累加
     * {@code ab += (a * b) * scaling}，其中 a、b、ab 均为本类 forward 输出的打包布局。
     * DC 与 Nyquist 为实数分量，单独处理；其余按复数对处理。
     *
     * @param a       输入频谱 a，长度必须 >= n
     * @param b       输入频谱 b，长度必须 >= n
     * @param ab      累加目标频谱，长度必须 >= n（会被原地修改）
     * @param scaling 缩放系数
     */
    public void zconvolveAccumulate(float[] a, float[] b, float[] ab, float scaling) {
        zconvolveAccumulate(a, 0, b, 0, ab, 0, scaling);
    }

    /**
     * 带偏移的重载，等价替代以 {@code a + aOff}、{@code b + bOff}、{@code ab + abOff} 调用 pffft。
     *
     * @param aOff 频谱 a 的起始偏移（字节/样本偏移，需为段对齐）
     * @param bOff 频谱 b 的起始偏移
     * @param abOff 累加目标的起始偏移
     */
    public void zconvolveAccumulate(
            float[] a, int aOff, float[] b, int bOff, float[] ab, int abOff, float scaling) {
        // DC（实数）。
        ab[abOff] += a[aOff] * b[bOff] * scaling;
        // Nyquist（实数，位于末位）。
        ab[abOff + n - 1] += a[aOff + n - 1] * b[bOff + n - 1] * scaling;
        // 其余复数对 (re, im)。
        for (int i = 1; i < n - 1; i += 2) {
            final int ai = aOff + i;
            final int bi = bOff + i;
            final int abi = abOff + i;
            final float ar = a[ai];
            final float ai2 = a[ai + 1];
            final float br = b[bi];
            final float bi2 = b[bi + 1];
            final float rr = ar * br - ai2 * bi2;
            final float ri = ar * bi2 + ai2 * br;
            ab[abi] += rr * scaling;
            ab[abi + 1] += ri * scaling;
        }
    }
}
