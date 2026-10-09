// 逐行移植自 ViPERDSP/viper/utils/DynamicBass（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class DynamicBass {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private int lowFreqX_;
    private int highFreqX_;
    private int lowFreqY_;
    private int highFreqY_;
    private int samplingRate_;

    private float qPeak_;
    private float bassGain_;
    private float sideGainX_;
    private float sideGainY_;

    private final PolesFilter filterX_ = new PolesFilter();
    private final PolesFilter filterY_ = new PolesFilter();
    private final Biquad lowPass_ = new Biquad();

    private final float[] fxL_ = new float[3];
    private final float[] fxR_ = new float[3];
    private final float[] fyL_ = new float[3];
    private final float[] fyR_ = new float[3];

    public DynamicBass() {
        lowFreqX_ = 120;
        highFreqX_ = 80;
        lowFreqY_ = 40;
        highFreqY_ = 0;
        samplingRate_ = 0;
        qPeak_ = 0.0f;
        bassGain_ = 1.0f;
        sideGainX_ = 1.0f;
        sideGainY_ = 1.0f;
        setSampleRate(VIPER_DEFAULT_SAMPLING_RATE);
        highFreqY_ = (int) ((float) samplingRate_ / 4.0f);
        filterX_.setPassFilter(lowFreqX_, highFreqX_);
        filterY_.setPassFilter(lowFreqY_, highFreqY_);
        lowPass_.setLowPassParameter(55.0f, samplingRate_, qPeak_ / 666.0f + 0.5f);
    }

    public void process(float[] samples, int frames) {
        filterSamples(samples, frames);
    }

    public void filterSamples(float[] samples, int size) {
        if (lowFreqX_ <= 120) {
            for (int i = 0; i < size; i++) {
                final float sampleL = samples[2 * i];
                final float sampleR = samples[2 * i + 1];
                final float avg = lowPass_.processSample(sampleL + sampleR);
                samples[2 * i] = sampleL + avg;
                samples[2 * i + 1] = sampleR + avg;
            }
        } else {
            for (int i = 0; i < size; i++) {
                filterX_.filterLeft(samples[2 * i], fxL_);
                filterX_.filterRight(samples[2 * i + 1], fxR_);
                filterY_.filterLeft(bassGain_ * fxL_[0], fyL_);
                filterY_.filterRight(bassGain_ * fxR_[0], fyR_);

                samples[2 * i] = fxL_[1] + fyL_[2] + sideGainX_ * fyL_[1] + sideGainY_ * fyL_[0] + fxL_[2];
                samples[2 * i + 1] =
                        fxR_[1] + fyR_[2] + sideGainX_ * fyR_[1] + sideGainY_ * fyR_[0] + fxR_[2];
            }
        }
    }

    public void reset() {
        filterX_.reset();
        filterY_.reset();
        lowPass_.setLowPassParameter(55.0f, samplingRate_, qPeak_ / 666.0f + 0.5f);
    }

    public void setBassGain(float value) {
        bassGain_ = value;

        double x = (value - 1.0) / 20.0 * 1600.0;
        if (x > 1600.0) {
            x = 1600.0;
        }
        qPeak_ = (float) x;

        lowPass_.setLowPassParameter(55.0f, samplingRate_, qPeak_ / 666.0f + 0.5f);
    }

    public void setFilterXPassFrequency(int low, int high) {
        lowFreqX_ = low;
        highFreqX_ = high;

        filterX_.setPassFilter(low, high);
        filterX_.setSampleRate(samplingRate_);
        lowPass_.setLowPassParameter(55.0f, samplingRate_, qPeak_ / 666.0f + 0.5f);
    }

    public void setFilterYPassFrequency(int low, int high) {
        lowFreqY_ = low;
        highFreqY_ = high;

        filterY_.setPassFilter(low, high);
        filterY_.setSampleRate(samplingRate_);
        lowPass_.setLowPassParameter(55.0f, samplingRate_, qPeak_ / 666.0f + 0.5f);
    }

    public void setSideGain(float gainX, float gainY) {
        sideGainX_ = gainX;
        sideGainY_ = gainY;
    }

    public void setSampleRate(int samplingRate) {
        samplingRate_ = samplingRate;
        filterX_.setSampleRate(samplingRate);
        filterY_.setSampleRate(samplingRate);
        lowPass_.setLowPassParameter(55.0f, samplingRate, qPeak_ / 666.0f + 0.5f);
    }
}
