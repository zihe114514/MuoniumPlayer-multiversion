// 逐行移植自 ViPERDSP/viper/effects/DynamicSystem（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

// 逐行移植自 ViPERDSP/viper/effects/DynamicSystem.h/.cpp
// 依赖 U2 工具类 DynamicBass（由其他代理按上游 DynamicBass.h 约定提供）。
public final class DynamicSystem {
    private static final int DEFAULT_SAMPLING_RATE = 44100;

    private boolean enable_;

    private int samplingRate_;

    private int xLow_ = 0;
    private int xHigh_ = 0;
    private int yLow_ = 0;
    private int yHigh_ = 0;
    private float sideGainLow_ = 0.0f;
    private float sideGainHigh_ = 0.0f;

    private final DynamicBass dynamicBass_ = new DynamicBass();

    public DynamicSystem() {
        enable_ = false;
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        dynamicBass_.setSampleRate(samplingRate_);
        dynamicBass_.reset();
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;

        dynamicBass_.filterSamples(samples, frames);
    }

    public void reset() {
        dynamicBass_.setSampleRate(samplingRate_);
        dynamicBass_.reset();
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setBassGain(float gain) {
        dynamicBass_.setBassGain(gain);
    }

    public void setSampleRate(int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            dynamicBass_.setSampleRate(samplingRate);
        }
    }

    public void setSideGain(float gainLow, float gainHigh) {
        if (gainLow >= 0.0f) sideGainLow_ = gainLow;
        if (gainHigh >= 0.0f) sideGainHigh_ = gainHigh;
        dynamicBass_.setSideGain(sideGainLow_, sideGainHigh_);
    }

    public void setXCoeffs(int low, int high) {
        if (low >= 0) xLow_ = low;
        if (high >= 0) xHigh_ = high;
        dynamicBass_.setFilterXPassFrequency(xLow_, xHigh_);
    }

    public void setYCoeffs(int low, int high) {
        if (low >= 0) yLow_ = low;
        if (high >= 0) yHigh_ = high;
        dynamicBass_.setFilterYPassFrequency(yLow_, yHigh_);
    }
}
