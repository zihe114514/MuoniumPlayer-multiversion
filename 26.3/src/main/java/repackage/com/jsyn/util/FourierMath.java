/*
 * Copyright 2009 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.util;

//简单的快速傅里叶变换。
public class FourierMath {
    static private final int MAX_SIZE_LOG_2 = 16;
    static BitReverseTable[] reverseTables = new BitReverseTable[MAX_SIZE_LOG_2];
    static DoubleSineTable[] sineTables = new DoubleSineTable[MAX_SIZE_LOG_2];
    static FloatSineTable[] floatSineTables = new FloatSineTable[MAX_SIZE_LOG_2];

    private static class DoubleSineTable {
        double[] sineValues;

        DoubleSineTable(int numBits) {
            int len = 1 << numBits;
            sineValues = new double[1 << numBits];
            for (int i = 0; i < len; i++) {
                sineValues[i] = Math.sin((i * Math.PI * 2.0) / len);
            }
        }
    }

    private static double[] getDoubleSineTable(int n) {
        DoubleSineTable sineTable = sineTables[n];
        if (sineTable == null) {
            sineTable = new DoubleSineTable(n);
            sineTables[n] = sineTable;
        }
        return sineTable.sineValues;
    }

    private static class FloatSineTable {
        float[] sineValues;

        FloatSineTable(int numBits) {
            int len = 1 << numBits;
            sineValues = new float[1 << numBits];
            for (int i = 0; i < len; i++) {
                sineValues[i] = (float) Math.sin((i * Math.PI * 2.0) / len);
            }
        }
    }

    private static float[] getFloatSineTable(int n) {
        FloatSineTable sineTable = floatSineTables[n];
        if (sineTable == null) {
            sineTable = new FloatSineTable(n);
            floatSineTables[n] = sineTable;
        }
        return sineTable.sineValues;
    }

    private static class BitReverseTable {
        int[] reversedBits;

        BitReverseTable(int numBits) {
            reversedBits = new int[1 << numBits];
            for (int i = 0; i < reversedBits.length; i++) {
                reversedBits[i] = reverseBits(i, numBits);
            }
        }

        static int reverseBits(int index, int numBits) {
            int i, rev;

            for (i = rev = 0; i < numBits; i++) {
                rev = (rev << 1) | (index & 1);
                index >>= 1;
            }

            return rev;
        }
    }

    private static int[] getReverseTable(int n) {
        BitReverseTable reverseTable = reverseTables[n];
        if (reverseTable == null) {
            reverseTable = new BitReverseTable(n);
            reverseTables[n] = reverseTable;
        }
        return reverseTable.reversedBits;
    }

    /**
     * 计算复数 FFT 结果每个 bin 对应正弦波的振幅。
     * 
     * @param ar
     * @param ai
     * @param magnitudes
     */
    public static void calculateMagnitudes(double[] ar, double[] ai, double[] magnitudes) {
        for (int i = 0; i < magnitudes.length; ++i) {
            magnitudes[i] = Math.sqrt((ar[i] * ar[i]) + (ai[i] * ai[i]));
        }
    }

    /**
     * 计算复数 FFT 结果每个 bin 对应正弦波的振幅。
     * 
     * @param ar
     * @param ai
     * @param magnitudes
     */
    public static void calculateMagnitudes(float[] ar, float[] ai, float[] magnitudes) {
        for (int i = 0; i < magnitudes.length; ++i) {
            magnitudes[i] = (float) Math.sqrt((ar[i] * ar[i]) + (ai[i] * ai[i]));
        }
    }

    public static void transform(int sign, int n, double[] ar, double[] ai) {
        double scale = (sign > 0) ? (2.0 / n) : (0.5);

        int numBits = FourierMath.numBits(n);
        int[] reverseTable = getReverseTable(numBits);
        double[] sineTable = getDoubleSineTable(numBits);
        int mask = n - 1;
        int cosineOffset = n / 4; // cos 与 sin 之间的相位偏移

        int i, j;
        for (i = 0; i < n; i++) {
            j = reverseTable[i];
            if (j >= i) {
                double tempr = ar[j] * scale;
                double tempi = ai[j] * scale;
                ar[j] = ar[i] * scale;
                ai[j] = ai[i] * scale;
                ar[i] = tempr;
                ai[i] = tempi;
            }
        }

        int mmax, stride;
        int numerator = sign * n;
        for (mmax = 1, stride = 2 * mmax; mmax < n; mmax = stride, stride = 2 * mmax) {
            int phase = 0;
            int phaseIncrement = numerator / (2 * mmax);
            for (int m = 0; m < mmax; ++m) {
                double wr = sineTable[(phase + cosineOffset) & mask]; // 余弦
                double wi = sineTable[phase];

                for (i = m; i < n; i += stride) {
                    j = i + mmax;
                    double tr = (wr * ar[j]) - (wi * ai[j]);
                    double ti = (wr * ai[j]) + (wi * ar[j]);
                    ar[j] = ar[i] - tr;
                    ai[j] = ai[i] - ti;
                    ar[i] += tr;
                    ai[i] += ti;
                }

                phase = (phase + phaseIncrement) & mask;
            }
            mmax = stride;
        }
    }

    public static void transform(int sign, int n, float[] ar, float[] ai) {
        float scale = (sign > 0) ? (2.0f / n) : (0.5f);

        int numBits = FourierMath.numBits(n);
        int[] reverseTable = getReverseTable(numBits);
        float[] sineTable = getFloatSineTable(numBits);
        int mask = n - 1;
        int cosineOffset = n / 4; // cos 与 sin 之间的相位偏移

        int i, j;
        for (i = 0; i < n; i++) {
            j = reverseTable[i];
            if (j >= i) {
                float tempr = ar[j] * scale;
                float tempi = ai[j] * scale;
                ar[j] = ar[i] * scale;
                ai[j] = ai[i] * scale;
                ar[i] = tempr;
                ai[i] = tempi;
            }
        }

        int mmax, stride;
        int numerator = sign * n;
        for (mmax = 1, stride = 2 * mmax; mmax < n; mmax = stride, stride = 2 * mmax) {
            int phase = 0;
            int phaseIncrement = numerator / (2 * mmax);
            for (int m = 0; m < mmax; ++m) {
                float wr = sineTable[(phase + cosineOffset) & mask]; // 余弦
                float wi = sineTable[phase];

                for (i = m; i < n; i += stride) {
                    j = i + mmax;
                    float tr = (wr * ar[j]) - (wi * ai[j]);
                    float ti = (wr * ai[j]) + (wi * ar[j]);
                    ar[j] = ar[i] - tr;
                    ai[j] = ai[i] - ti;
                    ar[i] += tr;
                    ai[i] += ti;
                }

                phase = (phase + phaseIncrement) & mask;
            }
            mmax = stride;
        }
    }

    /**
     * 计算 log2(n)
     * 
     * @param powerOf2 必须是 2 的幂，例如 512 或 1024
     * 例如， @return ，输入值为512为9
     */
    public static int numBits(int powerOf2) {
        int i;
        assert ((powerOf2 & (powerOf2 - 1)) == 0); // 是2的幂吗？
        for (i = -1; powerOf2 > 0; powerOf2 = powerOf2 >> 1, i++)
            ;
        return i;
    }

    /**
     * 就地计算 FFT，修改输入数组。
     * 
     * @param n
     * @param ar
     * @param ai
     */
    public static void fft(int n, double[] ar, double[] ai) {
        transform(1, n, ar, ai); // TODO -1 或 1
    }

    /**
     * 计算到位的逆FFT ，修改输入数组。
     * 
     * @param n
     * @param ar
     * @param ai
     */
    public static void ifft(int n, double[] ar, double[] ai) {
        transform(-1, n, ar, ai); // TODO -1 或 1
    }
}
