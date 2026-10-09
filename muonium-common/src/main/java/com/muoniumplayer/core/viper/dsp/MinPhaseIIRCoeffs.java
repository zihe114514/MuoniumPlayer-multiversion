// 逐行移植自 ViPERDSP/viper/utils/MinPhaseIIRCoeffs（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class MinPhaseIIRCoeffs {
    // 上游为 constexpr float 频率表
    private static final float[] K_MIN_PHASE_IIR_COEFFS_FREQ10_BANDS = {
            31.0f, 62.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
    };

    private static final float[] K_MIN_PHASE_IIR_COEFFS_FREQ15_BANDS = {
            25.0f, 40.0f, 63.0f, 100.0f, 160.0f, 250.0f, 400.0f, 630.0f, 1000.0f, 1600.0f,
            2500.0f, 4000.0f, 6300.0f, 10000.0f, 16000.0f
    };

    private static final float[] K_MIN_PHASE_IIR_COEFFS_FREQ25_BANDS = {
            20.0f, 31.5f, 40.0f, 50.0f, 80.0f, 100.0f, 125.0f, 160.0f, 250.0f, 315.0f, 400.0f,
            500.0f, 800.0f, 1000.0f, 1250.0f, 1600.0f, 2500.0f, 3150.0f, 4000.0f, 5000.0f,
            8000.0f, 10000.0f, 12500.0f, 16000.0f, 20000.0f
    };

    private static final float[] K_MIN_PHASE_IIR_COEFFS_FREQ31_BANDS = {
            20.0f, 25.0f, 31.5f, 40.0f, 50.0f, 63.0f, 80.0f, 100.0f, 125.0f, 160.0f, 200.0f,
            250.0f, 315.0f, 400.0f, 500.0f, 630.0f, 800.0f, 1000.0f, 1250.0f, 1600.0f, 2000.0f,
            2500.0f, 3150.0f, 4000.0f, 5000.0f, 6300.0f, 8000.0f, 10000.0f, 12500.0f, 16000.0f, 20000.0f
    };

    // 上游 bands_ 为 uint32_t -> int
    private int bands_;

    // coeffs_ 维持 float[]：公开 getCoefficients() 须返回 float[] 以兼容 IIRFilter（不可改其文件）；
    // 内部计算已按上游使用 double，仅在末级存入 float[] 时截断。
    private float[] coeffs_;

    public MinPhaseIIRCoeffs() {
        bands_ = 0;
        coeffs_ = null;
    }

    public float[] getCoefficients() {
        return coeffs_;
    }

    public float getIndexFrequency(int index) {
        switch (bands_) {
            case 10:
                return K_MIN_PHASE_IIR_COEFFS_FREQ10_BANDS[index];
            case 15:
                return K_MIN_PHASE_IIR_COEFFS_FREQ15_BANDS[index];
            case 25:
                return K_MIN_PHASE_IIR_COEFFS_FREQ25_BANDS[index];
            case 31:
                return K_MIN_PHASE_IIR_COEFFS_FREQ31_BANDS[index];
            default:
                return 0.0f;
        }
    }

    public int updateCoeffs(int bands, int sampling_rate) {
        if (bands != 10 && bands != 15 && bands != 25 && bands != 31) {
            return 0;
        }

        bands_ = bands;

        coeffs_ = new float[bands * 4];

        float[] band_freqs;
        double tmp = 0;

        switch (bands) {
            case 10:
                band_freqs = K_MIN_PHASE_IIR_COEFFS_FREQ10_BANDS;
                tmp = 1.0;
                break;
            case 15:
                band_freqs = K_MIN_PHASE_IIR_COEFFS_FREQ15_BANDS;
                tmp = 2.0 / 3.0;
                break;
            case 25:
                band_freqs = K_MIN_PHASE_IIR_COEFFS_FREQ25_BANDS;
                tmp = 1.0 / 3.0;
                break;
            case 31:
                band_freqs = K_MIN_PHASE_IIR_COEFFS_FREQ31_BANDS;
                tmp = 1.0 / 3.0;
                break;
            default:
                band_freqs = null;
        }

        final double[] f1f2 = new double[2];

        for (int i = 0; i < bands; i++) {
            findF1F2(band_freqs[i], tmp, f1f2);

            final double ret2 = f1f2[0];
            double ret1 = f1f2[1];

            final double x = 2.0 * Math.PI * band_freqs[i] / sampling_rate;
            final double y = 2.0 * Math.PI * ret2 / sampling_rate;

            final double cos_x = Math.cos(x);
            final double cos_y = Math.cos(y);
            final double sin_y = Math.sin(y);

            final double a = cos_x * cos_y;
            final double b = cos_x * cos_x / 2.0;
            final double c = sin_y * sin_y;

            final double d = b - a + 0.5 - c;
            final double e = c + (b + cos_y * cos_y - a - 0.5);
            final double f = cos_x * cos_x * 0.125 - cos_x * cos_y * 0.25 + 0.125 - c * 0.25;

            final double[] root = new double[1];
            if (solveRoot(d, e, f, root) == 0) {
                ret1 = root[0];
                coeffs_[i * 4] = (float) (ret1 + ret1);
                coeffs_[i * 4 + 1] = (float) (0.5 - ret1);
                coeffs_[i * 4 + 2] = (float) ((ret1 + 0.5) * cos_x * 2.0);
            }
        }

        return 1;
    }

    private static void findF1F2(double center_freq, double bandwidth_octaves, double[] out) {
        final double x = Math.pow(2.0, bandwidth_octaves / 2.0);
        out[0] = center_freq / x;
        out[1] = center_freq * x;
    }

    private static int solveRoot(double coeff_a, double coeff_b, double coeff_c, double[] out) {
        final double x = (coeff_c - coeff_b * coeff_b / (coeff_a * 4.0)) / coeff_a;
        final double y = coeff_b / (coeff_a + coeff_a);

        if (x >= 0.0) {
            return -1;
        }

        final double z = Math.sqrt(-x);
        final double a = -y - z;
        final double b = z - y;
        if (a > b) {
            out[0] = b;
        } else {
            out[0] = a;
        }

        return 0;
    }
}
