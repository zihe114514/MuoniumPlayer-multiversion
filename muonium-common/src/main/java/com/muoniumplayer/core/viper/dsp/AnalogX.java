// 逐行移植自 ViPERDSP/viper/effects/AnalogX.cpp 与 AnalogX.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class AnalogX {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private static final float[] K_ANALOG_X_HARMONICS = {
            0.01f, 0.02f, 0.0001f, 0.001f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f
    };

    private boolean enable_;

    private int processing_model_;
    private int sampling_rate_;
    private int freq_range_;

    private float gain_;

    private final MultiBiquad[] high_pass_ = new MultiBiquad[2];
    private final Harmonic[] harmonic_ = new Harmonic[2];
    private final MultiBiquad[] low_pass_ = new MultiBiquad[2];
    private final MultiBiquad[] peak_ = new MultiBiquad[2];

    public AnalogX() {
        enable_ = false;
        processing_model_ = 0;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        freq_range_ = 0;
        gain_ = 0.0f;

        high_pass_[0] = new MultiBiquad();
        high_pass_[1] = new MultiBiquad();
        harmonic_[0] = new Harmonic();
        harmonic_[1] = new Harmonic();
        low_pass_[0] = new MultiBiquad();
        low_pass_[1] = new MultiBiquad();
        peak_[0] = new MultiBiquad();
        peak_[1] = new MultiBiquad();

        reset();
    }

    public void process(float[] samples, int frames) {
        if (enable_) {
            for (int i = 0; i < frames * 2; i += 2) {
                final double in_l = samples[i];
                double out_l = high_pass_[0].processSample((float) in_l);
                out_l = harmonic_[0].process((float) out_l);
                out_l = low_pass_[0].processSample((float) (in_l + out_l * gain_));
                out_l = peak_[0].processSample((float) (out_l * 0.8));
                samples[i] = (float) out_l;

                final double in_r = samples[i + 1];
                double out_r = high_pass_[1].processSample((float) in_r);
                out_r = harmonic_[1].process((float) out_r);
                out_r = low_pass_[1].processSample((float) (in_r + out_r * gain_));
                out_r = peak_[1].processSample((float) (out_r * 0.8));
                samples[i + 1] = (float) out_r;
            }

            if (freq_range_ < sampling_rate_ / 4) {
                freq_range_ += frames;
                // 上游 memset(samples, 0, size * 2 * sizeof(float))：仅清零本帧 size*2 个交织样本。
                Arrays.fill(samples, 0, frames * 2, 0.0f);
            }
        }
    }

    public void reset() {
        final float[][] models = {
                {0.6f, 19650.0f}, {1.2f, 18233.0f}, {2.4f, 16307.0f}
        };

        high_pass_[0].refreshFilter(
                MultiBiquad.FilterType.HIGH_PASS, 0.0f, 240.0f, sampling_rate_, 0.717f, false
        );
        high_pass_[1].refreshFilter(
                MultiBiquad.FilterType.HIGH_PASS, 0.0f, 240.0f, sampling_rate_, 0.717f, false
        );

        peak_[0].refreshFilter(
                MultiBiquad.FilterType.PEAK, 0.58f, 633.0f, sampling_rate_, 6.28f, true
        );
        peak_[1].refreshFilter(
                MultiBiquad.FilterType.PEAK, 0.58f, 633.0f, sampling_rate_, 6.28f, true
        );

        harmonic_[0].reset();
        harmonic_[1].reset();

        if (processing_model_ >= 0 && processing_model_ <= 2) {
            harmonic_[0].setHarmonics(K_ANALOG_X_HARMONICS);
            harmonic_[1].setHarmonics(K_ANALOG_X_HARMONICS);
            gain_ = models[processing_model_][0];
            final float cutoff = models[processing_model_][1];
            low_pass_[0].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS, 0.0f, cutoff, sampling_rate_, 0.717f, false
            );
            low_pass_[1].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS, 0.0f, cutoff, sampling_rate_, 0.717f, false
            );
        }

        freq_range_ = 0;
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (!enable_) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setProcessingModel(int model) {
        if (processing_model_ != model) {
            processing_model_ = model;
            reset();
        }
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            reset();
        }
    }
}
