// 逐行移植自 ViPERDSP/viper/effects/SpectrumExtend（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class SpectrumExtend {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private static final float[] K_SPECTRUM_HARMONICS = {
            0.02f, 0.0f, 0.02f, 0.0f, 0.02f, 0.0f, 0.02f, 0.0f, 0.02f, 0.0f,
    };

    private boolean enabled_;

    private int sampling_rate_;
    private int reference_freq_;

    private float exciter_;

    private final MultiBiquad[] highpass_ = new MultiBiquad[2];
    private final MultiBiquad[] lowpass_ = new MultiBiquad[2];
    private final Harmonic[] harmonics_ = new Harmonic[2];

    public SpectrumExtend() {
        enabled_ = false;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        reference_freq_ = 7600;
        exciter_ = 0.0f;
        highpass_[0] = new MultiBiquad();
        highpass_[1] = new MultiBiquad();
        lowpass_[0] = new MultiBiquad();
        lowpass_[1] = new MultiBiquad();
        harmonics_[0] = new Harmonic();
        harmonics_[1] = new Harmonic();
        reset();
    }

    public void process(float[] samples, int frames) {
        if (!enabled_) return;

        for (int i = 0; i < frames * 2; i += 2) {
            double tmp = highpass_[0].processSample(samples[i]);
            tmp = harmonics_[0].process((float) tmp);
            tmp = lowpass_[0].processSample((float) (tmp * exciter_));
            samples[i] = samples[i] + (float) tmp;

            tmp = highpass_[1].processSample(samples[i + 1]);
            tmp = harmonics_[1].process((float) tmp);
            tmp = lowpass_[1].processSample((float) (tmp * exciter_));
            samples[i + 1] = samples[i + 1] + (float) tmp;
        }
    }

    public void reset() {
        highpass_[0].reset();
        highpass_[1].reset();
        lowpass_[0].reset();
        lowpass_[1].reset();

        highpass_[0].refreshFilter(
                MultiBiquad.FilterType.HIGH_PASS,
                0.0f,
                (float) reference_freq_,
                sampling_rate_,
                0.717f,
                false
        );
        highpass_[1].refreshFilter(
                MultiBiquad.FilterType.HIGH_PASS,
                0.0f,
                (float) reference_freq_,
                sampling_rate_,
                0.717f,
                false
        );

        lowpass_[0].refreshFilter(
                MultiBiquad.FilterType.LOW_PASS,
                0.0f,
                (float) sampling_rate_ / 2.0f - 2000.0f,
                sampling_rate_,
                0.717f,
                false
        );
        lowpass_[1].refreshFilter(
                MultiBiquad.FilterType.LOW_PASS,
                0.0f,
                (float) sampling_rate_ / 2.0f - 2000.0f,
                sampling_rate_,
                0.717f,
                false
        );

        harmonics_[0].reset();
        harmonics_[1].reset();

        harmonics_[0].setHarmonics(K_SPECTRUM_HARMONICS);
        harmonics_[1].setHarmonics(K_SPECTRUM_HARMONICS);
    }

    public void setEnable(boolean enable) {
        if (enabled_ != enable) {
            if (enable) {
                reset();
            }
            enabled_ = enable;
        }
    }

    public void setExciter(float value) {
        exciter_ = value;
    }

    public void setReferenceFrequency(int value) {
        if (sampling_rate_ / 2 - 100 < value) {
            value = sampling_rate_ / 2 - 100;
        }
        reference_freq_ = value;
        reset();
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            if (sampling_rate / 2 - 100 < reference_freq_) {
                reference_freq_ = sampling_rate / 2 - 100;
            }
            reset();
        }
    }
}
