// 逐行移植自 ViPERDSP/viper/effects/PsychoacousticBass（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class PsychoacousticBass {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private static final float[] K_HARMONIC_ORDER_2 = {
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
    };

    private static final float[] K_HARMONIC_ORDER_3 = {
            0.0f, 0.7f, 0.3f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
    };

    private static final float[] K_HARMONIC_ORDER_4 = {
            0.0f, 0.5f, 0.3f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
    };

    private static final float[] K_HARMONIC_ORDER_5 = {
            0.0f, 0.4f, 0.25f, 0.2f, 0.15f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
    };

    private boolean enabled_;

    private int sampling_rate_;
    private int cutoff_;
    private int harmonic_order_;

    private float intensity_;
    private float original_bass_level_;

    private double envelope_;

    private final MultiBiquad[] lowpass_ = new MultiBiquad[2];
    private final MultiBiquad[] highpass_ = new MultiBiquad[2];
    private final Harmonic[] harmonics_ = new Harmonic[2];

    public PsychoacousticBass() {
        enabled_ = false;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        cutoff_ = 80;
        harmonic_order_ = 3;
        intensity_ = 0.5f;
        original_bass_level_ = 1.0f;
        envelope_ = 1e-10;
        lowpass_[0] = new MultiBiquad();
        lowpass_[1] = new MultiBiquad();
        highpass_[0] = new MultiBiquad();
        highpass_[1] = new MultiBiquad();
        harmonics_[0] = new Harmonic();
        harmonics_[1] = new Harmonic();
        reset();
    }

    public void process(float[] samples, int frames) {
        if (!enabled_) return;

        for (int i = 0; i < frames * 2; i += 2) {
            final double bass_l = lowpass_[0].processSample(samples[i]);
            final double bass_r = lowpass_[1].processSample(samples[i + 1]);

            double abs_l = Math.abs(bass_l);
            double abs_r = Math.abs(bass_r);
            final double peak = abs_l > abs_r ? abs_l : abs_r;
            if (peak > envelope_) {
                envelope_ += 0.01 * (peak - envelope_);
            } else {
                envelope_ += 0.0001 * (peak - envelope_);
            }
            if (envelope_ < 1e-10) envelope_ = 1e-10;

            double norm_l = bass_l / envelope_;
            double norm_r = bass_r / envelope_;
            if (norm_l > 1.0) norm_l = 1.0;
            if (norm_l < -1.0) norm_l = -1.0;
            if (norm_r > 1.0) norm_r = 1.0;
            if (norm_r < -1.0) norm_r = -1.0;

            double harmonic_l = harmonics_[0].process((float) norm_l) * envelope_;
            double harmonic_r = harmonics_[1].process((float) norm_r) * envelope_;

            harmonic_l = highpass_[0].processSample((float) harmonic_l);
            harmonic_r = highpass_[1].processSample((float) harmonic_r);

            samples[i] = samples[i] + (float) (harmonic_l * intensity_);
            samples[i + 1] = samples[i + 1] + (float) (harmonic_r * intensity_);

            if (original_bass_level_ < 1.0f) {
                final double dry_l = samples[i] - (float) bass_l;
                final double dry_r = samples[i + 1] - (float) bass_r;
                samples[i] = (float) (dry_l + bass_l * original_bass_level_);
                samples[i + 1] = (float) (dry_r + bass_r * original_bass_level_);
            }
        }
    }

    public void reset() {
        envelope_ = 1e-10;
        for (int ch = 0; ch < 2; ch++) {
            lowpass_[ch].reset();
            highpass_[ch].reset();
        }
        refreshFilters();
        applyHarmonicCoeffs();
    }

    public void setEnable(boolean enable) {
        if (enabled_ != enable) {
            if (enable) {
                reset();
            }
            enabled_ = enable;
        }
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            reset();
        }
    }

    public void setCutoff(int value) {
        if (value < 60) value = 60;
        if (value > 150) value = 150;
        if (cutoff_ != value) {
            cutoff_ = value;
            refreshFilters();
        }
    }

    public void setIntensity(int value) {
        if (value > 100) value = 100;
        intensity_ = (float) value / 100.0f;
    }

    public void setHarmonicOrder(int value) {
        if (value < 2) value = 2;
        if (value > 5) value = 5;
        if (harmonic_order_ != value) {
            harmonic_order_ = value;
            applyHarmonicCoeffs();
        }
    }

    public void setOriginalBassLevel(int value) {
        if (value > 100) value = 100;
        original_bass_level_ = (float) value / 100.0f;
    }

    private void refreshFilters() {
        for (int ch = 0; ch < 2; ch++) {
            lowpass_[ch].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS,
                    0.0f,
                    (float) cutoff_,
                    sampling_rate_,
                    0.717f,
                    false
            );
            highpass_[ch].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS,
                    0.0f,
                    (float) cutoff_,
                    sampling_rate_,
                    0.717f,
                    false
            );
        }
    }

    private void applyHarmonicCoeffs() {
        final float[] coeffs;
        switch (harmonic_order_) {
            case 2:
                coeffs = K_HARMONIC_ORDER_2;
                break;
            case 3:
                coeffs = K_HARMONIC_ORDER_3;
                break;
            case 4:
                coeffs = K_HARMONIC_ORDER_4;
                break;
            case 5:
                coeffs = K_HARMONIC_ORDER_5;
                break;
            default:
                coeffs = K_HARMONIC_ORDER_3;
                break;
        }
        harmonics_[0].reset();
        harmonics_[1].reset();
        harmonics_[0].setHarmonics(coeffs);
        harmonics_[1].setHarmonics(coeffs);
    }
}
