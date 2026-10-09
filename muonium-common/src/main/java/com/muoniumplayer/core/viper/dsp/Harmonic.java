// 逐行移植自 ViPERDSP/viper/utils/Harmonic（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class Harmonic {
    // 上游为 constexpr float 表
    private static final float[] K_HARMONIC_DEFAULT = {
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
    };

    // 上游 biggest_coeff_ / sample_counter_ 为 uint32_t -> int；coeffs_ 为 float[11]
    private int biggest_coeff_;
    private int sample_counter_;

    private final float[] coeffs_ = new float[11];
    // 上游 last_processed_ / prev_out_ 为 double
    private double last_processed_;
    private double prev_out_;

    public Harmonic() {
        updateCoeffs(K_HARMONIC_DEFAULT);
        reset();
    }

    // 返回类型保持 float（上游 double；内部双精度计算后截断返回）
    public float process(float sample) {
        final double prev_last = last_processed_;

        final double x = sample;
        final float[] c = coeffs_;
        double y = c[10];

        // 上游 fma(x, y, c[i])：x,y 为 double，c[i] 为 float 提升为 double
        y = Math.fma(x, y, c[9]);
        y = Math.fma(x, y, c[8]);
        y = Math.fma(x, y, c[7]);
        y = Math.fma(x, y, c[6]);
        y = Math.fma(x, y, c[5]);
        y = Math.fma(x, y, c[4]);
        y = Math.fma(x, y, c[3]);
        y = Math.fma(x, y, c[2]);
        y = Math.fma(x, y, c[1]);
        y = Math.fma(x, y, c[0]);

        last_processed_ = y;
        prev_out_ = last_processed_ + prev_out_ * 0.999 - prev_last;

        if (sample_counter_ < biggest_coeff_) {
            sample_counter_++;
            return 0.0f;
        }

        return (float) prev_out_;
    }

    public void reset() {
        last_processed_ = 0.0;
        sample_counter_ = 0;
        prev_out_ = 0.0;
    }

    public void setHarmonics(float[] coeffs) {
        updateCoeffs(coeffs);
        reset();
    }

    // 上游 arr1/arr2/biggest_coeff/abs_coeff_sum/norm_factor/tmp 均为 float
    public void updateCoeffs(float[] coeffs) {
        final float[] arr1 = new float[11];
        final float[] arr2 = new float[11];

        Arrays.fill(arr1, 0.0f);

        float biggest_coeff = 0.0f;
        float abs_coeff_sum = 0.0f;
        for (int i = 0; i < 10; i++) {
            final float abs_coeff = Math.abs(coeffs[i]);
            abs_coeff_sum += abs_coeff;
            if (abs_coeff > biggest_coeff) {
                biggest_coeff = abs_coeff;
            }
        }
        biggest_coeff_ = (int) (biggest_coeff * 10000.0f);

        System.arraycopy(coeffs, 0, arr1, 1, 10);

        float norm_factor = 1.0f;
        if (abs_coeff_sum > 1.0f) {
            norm_factor = 1.0f / abs_coeff_sum;
        }
        for (int i = 1; i < 11; i++) {
            arr1[i] *= norm_factor;
        }

        Arrays.fill(coeffs_, 0.0f);
        Arrays.fill(arr2, 0.0f);

        coeffs_[10] = arr1[10];

        for (int i = 2; i < 11; i++) {
            for (int j = 0; j < i; j++) {
                final float tmp = arr2[i - j];
                arr2[i - j] = coeffs_[i - j];
                coeffs_[i - j] = coeffs_[i - j - 1] * 2.0f - tmp;
            }
            final float tmp = arr1[10 - i + 1] - arr2[0];
            arr2[0] = coeffs_[0];
            coeffs_[0] = tmp;
        }

        for (int i = 1; i < 11; i++) {
            coeffs_[10 - i + 1] = coeffs_[10 - i] - arr2[10 - i + 1];
        }

        coeffs_[0] = arr1[0] / 2.0f - arr2[0];
    }
}
