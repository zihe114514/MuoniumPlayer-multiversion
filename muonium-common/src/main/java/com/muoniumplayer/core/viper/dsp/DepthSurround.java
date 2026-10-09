// 逐行移植自 ViPERDSP/viper/utils/DepthSurround（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class DepthSurround {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private boolean enable_;
    private boolean strengthAtLeast500_;

    private int strength_;

    private float gain_;
    private final float[] prev_ = new float[2];

    private final TimeConstDelay[] timeConstDelay_ = new TimeConstDelay[2];
    private final Biquad highpass_ = new Biquad();

    public DepthSurround() {
        enable_ = false;
        strengthAtLeast500_ = false;
        strength_ = 0;
        gain_ = 0.0f;
        prev_[0] = 0.0f;
        prev_[1] = 0.0f;
        timeConstDelay_[0] = new TimeConstDelay();
        timeConstDelay_[1] = new TimeConstDelay();
        setSampleRate(VIPER_DEFAULT_SAMPLING_RATE);
        refreshStrength(strength_);
    }

    public void process(float[] samples, int frames) {
        if (!enable_) {
            return;
        }

        final float gainR = strengthAtLeast500_ ? -gain_ : gain_;

        for (int i = 0; i < frames; i++) {
            final float sampleL = samples[2 * i];
            final float sampleR = samples[2 * i + 1];

            prev_[0] = gain_ * timeConstDelay_[0].processSample(sampleL + prev_[1]);
            prev_[1] = gainR * timeConstDelay_[1].processSample(sampleR + prev_[0]);

            final float l = prev_[0] + sampleL;
            final float r = prev_[1] + sampleR;

            final float diff = (l - r) / 2.0f;
            final float avg = (l + r) / 2.0f;
            final float avgOut = highpass_.processSample(diff);
            samples[2 * i] = avg + (diff - avgOut);
            samples[2 * i + 1] = avg - (diff - avgOut);
        }
    }

    public void setStrength(int value) {
        strength_ = value;
        refreshStrength(value);
    }

    public void setSampleRate(int samplingRate) {
        timeConstDelay_[0].setParameters(samplingRate, 0.02f);
        timeConstDelay_[1].setParameters(samplingRate, 0.014f);
        highpass_.setHighPassParameter(800.0f, samplingRate, -11.0f, 0.72f);
        prev_[0] = 0.0f;
        prev_[1] = 0.0f;
    }

    public void refreshStrength(int strength) {
        strengthAtLeast500_ = strength >= 500;
        enable_ = strength != 0;
        if (strength != 0) {
            float gain = (float) Math.pow(10.0, (strength / 1000.0 * 10.0 - 15.0) / 20.0);
            gain = Math.min(gain, 1.0f);
            gain_ = gain;
        } else {
            gain_ = 0.0f;
        }
    }
}
