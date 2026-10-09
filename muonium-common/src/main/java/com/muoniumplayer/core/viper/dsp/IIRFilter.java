// 逐行移植自 ViPERDSP/viper/effects/IIRFilter（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class IIRFilter {
    private boolean enable_;

    private int bands_;
    private int samplingRate_;
    private int bufIndex0_;
    private int bufIndex1_;
    private int bufIndex2_;

    private double[] buf_;
    private float[] bandLevelsWithQ_;
    private MinPhaseIIRCoeffs minPhaseIirCoeffs_;

    public IIRFilter(final int bands) {
        enable_ = false;
        bands_ = 0;
        samplingRate_ = 44100;

        buf_ = new double[496];
        bandLevelsWithQ_ = new float[31];
        minPhaseIirCoeffs_ = new MinPhaseIIRCoeffs();

        if (bands == 10 || bands == 15 || bands == 25 || bands == 31) {
            bands_ = bands;
            minPhaseIirCoeffs_.updateCoeffs(bands_, samplingRate_);
        }

        for (int i = 0; i < 31; i++) {
            bandLevelsWithQ_[i] = 0.636f;
        }

        reset();
    }

    public void process(final float[] samples, final int frames) {
        if (!enable_) return;

        final float[] coeffs = minPhaseIirCoeffs_.getCoefficients();
        if (coeffs == null || frames == 0) return;

        for (int i = 0; i < frames; i++) {
            for (int j = 0; j < 2; j++) {
                final double sample = samples[i * 2 + j];
                double accumulated = 0.0;

                for (int k = 0; k < bands_; k++) {
                    final int bufIdx = bufIndex0_ + j * 8 + k * 16;
                    buf_[bufIdx] = sample;

                    final double coeff1 = coeffs[k * 4];
                    final double coeff2 = coeffs[k * 4 + 1];
                    final double coeff3 = coeffs[k * 4 + 2];

                    final double a = coeff3 * buf_[bufIdx + (bufIndex1_ + 3 - bufIndex0_)];
                    final double b =
                            coeff2 * (sample - buf_[bufIdx + (bufIndex2_ - bufIndex0_)]);
                    final double c = coeff1 * buf_[bufIdx + (bufIndex2_ - bufIndex0_ + 3)];

                    final double tmp = a + b - c;

                    buf_[bufIdx + 3] = tmp;
                    accumulated += tmp * bandLevelsWithQ_[k];
                }

                samples[i * 2 + j] = (float) accumulated;
            }

            bufIndex0_ = (bufIndex0_ + 1) % 3;
            bufIndex1_ = (bufIndex1_ + 1) % 3;
            bufIndex2_ = (bufIndex2_ + 1) % 3;
        }
    }

    public void reset() {
        for (int i = 0; i < 496; i++) {
            buf_[i] = 0.0;
        }
        bufIndex0_ = 2;
        bufIndex1_ = 1;
        bufIndex2_ = 0;
    }

    public void setEnable(final boolean enable) {
        if (enable_ != enable) {
            if (enable) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setBandCount(final int bands) {
        if (bands != 10 && bands != 15 && bands != 25 && bands != 31) return;
        if (bands_ == bands) return;
        bands_ = bands;
        minPhaseIirCoeffs_.updateCoeffs(bands_, samplingRate_);
        for (int i = 0; i < 31; i++) {
            bandLevelsWithQ_[i] = 0.636f;
        }
        reset();
    }

    public void setBandLevel(final int band, final float level) {
        if (band > 30) return;
        final double bandLevel = Math.pow(10.0, (double) level / 20.0);
        bandLevelsWithQ_[band] = (float) (bandLevel * 0.636);
    }

    public void setBandLevels(final float[] levels, final int count) {
        final int n = count > 31 ? 31 : count;
        for (int i = 0; i < n; i++) {
            setBandLevel(i, levels[i]);
        }
    }

    public void setSampleRate(final int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            if (bands_ != 0) {
                minPhaseIirCoeffs_.updateCoeffs(bands_, samplingRate);
            }
            reset();
        }
    }
}
