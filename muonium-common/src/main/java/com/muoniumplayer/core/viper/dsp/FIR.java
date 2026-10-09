// 逐行移植自 ViPERDSP/viper/utils/FIR（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class FIR {
    private boolean has_coefficients_;

    private int coeffs_size_;
    private int block_length_;

    private float[] offset_block_;
    private float[] coeffs_;
    private float[] block_;

    public FIR() {
        has_coefficients_ = false;
        coeffs_size_ = 0;
        block_length_ = 0;
    }

    public void filterSamplesInterleaved(float[] samples, int offset, int size, int channels) {
        if (!has_coefficients_ || size == 0) {
            return;
        }

        for (int i = 0; i < size; i++) {
            block_[i] = samples[offset + i * channels];
        }

        if (block_length_ > size) {
            for (int i = size; i < block_length_; i++) {
                block_[i] = 0.0f;
            }
        }

        System.arraycopy(block_, 0, offset_block_, coeffs_size_ - 1, block_length_);

        for (int i = 0; i < block_length_; i++) {
            float sample = 0.0f;

            for (int j = 0; j < coeffs_size_; j++) {
                sample += coeffs_[j] * offset_block_[coeffs_size_ + i - j - 1];
            }

            if (i < size) {
                samples[offset + i * channels] = sample;
            }
        }

        if (coeffs_size_ > 1) {
            final int carry_count = coeffs_size_ - 1;
            for (int i = 0; i < carry_count; i++) {
                offset_block_[i] = block_[block_length_ - carry_count + i];
            }
        }
    }

    public void reset() {
        if (coeffs_size_ + block_length_ > 0) {
            for (int i = 0; i < coeffs_size_ + block_length_ + 1; i++) {
                offset_block_[i] = 0.0f;
            }
        }
    }

    public int loadCoefficients(float[] coeffs, int coeffs_size, int block_length) {
        if (coeffs == null || coeffs_size == 0 || block_length == 0) {
            return 0;
        }

        offset_block_ = new float[coeffs_size + block_length + 1];
        coeffs_ = new float[coeffs_size];
        block_ = new float[block_length];

        coeffs_size_ = coeffs_size;
        block_length_ = block_length;

        System.arraycopy(coeffs, 0, coeffs_, 0, coeffs_size);

        reset();
        has_coefficients_ = true;

        return 1;
    }

    public int getBlockLength() {
        return block_length_;
    }
}
